package com.prism.launcher.display.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.prism.launcher.R
import com.prism.launcher.display.WirelessDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The sending half of Prism's screen mirroring.
 *
 * ## Capture, JPEG, publish
 *
 * A [MediaProjection] renders the display into an [ImageReader]. Each available image becomes a
 * bitmap, one JPEG, and one frame on [MirrorStreamServer]. There is no encoder negotiation and no
 * container: a Prism receiver is the only consumer, so the format needs to be understandable by
 * exactly two codebases rather than by a browser.
 *
 * ## Why this is a foreground service
 *
 * Android 14 refuses to let an app keep a projection unless a foreground service of type
 * `mediaProjection` is running, and it insists the service be in the foreground *before*
 * `getMediaProjection`. The notification is therefore not decoration -- without it there is no
 * mirror at all. It also gives the user a Stop button that survives leaving the screen, which
 * matters when the thing being mirrored is the screen they would use to stop it.
 *
 * ## The screen stays awake
 *
 * A locked or sleeping display can drop the projection (Android 15 stops it outright). The
 * receiver keeps its own screen awake; this service keeps the capture alive after the controlling
 * activity closes, and a platform that suspends the display will end the session rather than
 * leave the display showing a frozen frame forever.
 */
class ScreenMirrorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var server: MirrorStreamServer? = null

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    @Volatile private var stopping = false
    @Volatile private var lastFrameAt = 0L

    private val frames = AtomicLong(0)
    private val startedAt = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirror()
                return START_NOT_STICKY
            }
            ACTION_START -> startMirror(intent)
            else -> {
                // A restart with no intent would either silently capture the screen again or
                // leave a notification for a session that no longer exists. Neither is right.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        releaseCapture()
        server?.close()
        server = null
        // The control link outlives this service only long enough to tell the display the stream
        // is over. Leaving it open would make the far side wait for frames that will never come.
        runCatching { WirelessDisplay.stopMirror() }
        runCatching { WirelessDisplay.disconnect() }
        _state.value = State(error = _state.value.error)
        super.onDestroy()
    }

    // ── Start / stop ───────────────────────────────────────────────────────

    private fun startMirror(intent: Intent) {
        if (projection != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            fail("Screen capture permission was not returned.")
            return
        }

        createChannel()
        val notification = buildNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val capture = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (error: Exception) {
            fail("Android refused the screen capture: " + (error.message ?: "unknown reason"))
            return
        }
        if (capture == null) {
            fail("Android refused the screen capture.")
            return
        }
        projection = capture
        stopping = false
        frames.set(0)
        lastFrameAt = 0L
        startedAt.set(System.currentTimeMillis())

        // MUST be registered before createVirtualDisplay. Android 14 invalidates a projection
        // with no callback, and the callback is also the only notice that the user revoked
        // capture from the system UI.
        capture.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "The system stopped the projection")
                stopMirror()
            }
        }, mainHandler)

        val token = newMirrorToken()
        val stream = MirrorStreamServer(
            token = token,
            onViewersChanged = { viewers ->
                updateState { it.copy(viewers = viewers) }
                mainHandler.post { notifyViewers(viewers) }
            },
            onError = { message -> Log.w(TAG, "Mirror stream: $message") },
        )
        val port = try {
            stream.start()
        } catch (error: Exception) {
            fail("Could not open the screen stream: " + (error.message ?: "no free port"))
            return
        }
        server = stream

        displayManager = getSystemService(DisplayManager::class.java)
        installDisplayListener()

        try {
            beginCapture()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start screen capture", error)
            fail("Could not start screen capture: " + (error.message ?: error.javaClass.simpleName))
            return
        }

        _state.value = State(
            running = true,
            port = port,
            token = token,
            width = width,
            height = height,
            startedAt = startedAt.get(),
        )
        // The control link may not be verified yet. WirelessDisplay holds this until it is, so
        // starting capture before the TCP connect finishes does not lose the mirror.
        WirelessDisplay.announceMirror(port, token, width, height)
        Log.i(TAG, "Mirror stream ready on port $port ($width x $height)")
    }

    private fun beginCapture() {
        val capture = projection ?: return
        val point = android.graphics.Point()
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        runCatching { display?.getRealSize(point) }
        val sourceWidth = (if (point.x > 0) point.x else resources.displayMetrics.widthPixels).coerceAtLeast(2)
        val sourceHeight = (if (point.y > 0) point.y else resources.displayMetrics.heightPixels).coerceAtLeast(2)
        val scale = minOf(1.0, MAX_LONG_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble())
        width = (sourceWidth * scale).toInt().coerceAtLeast(320)
        height = (sourceHeight * scale).toInt().coerceAtLeast(320)
        densityDpi = resources.displayMetrics.densityDpi

        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = newReader
        newReader.setOnImageAvailableListener({ available -> onImageAvailable(available) }, mainHandler)
        val created = capture.createVirtualDisplay(
            "Prism Mirror",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface,
            null,
            mainHandler,
        )
        if (created == null) {
            runCatching { newReader.close() }
            reader = null
            fail("Android did not create the screen capture surface.")
            return
        }
        virtualDisplay = created
        Log.i(TAG, "Mirroring at ${width}x$height")
    }

    /**
     * Rebuilds the reader when the panel changes shape.
     *
     * A virtual display does not follow a rotation by itself. Keeping the old buffer would mirror
     * a landscape screen into a portrait rectangle, which is exactly the kind of broken that looks
     * like a codec fault rather than a geometry one.
     */
    private fun rebuildIfNeeded() {
        if (stopping || projection == null) return
        val point = android.graphics.Point()
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        runCatching { display?.getRealSize(point) }
        if (point.x <= 0 || point.y <= 0) return
        val scale = minOf(1.0, MAX_LONG_EDGE.toDouble() / maxOf(point.x, point.y).toDouble())
        val expectedWidth = (point.x * scale).toInt().coerceAtLeast(320)
        val expectedHeight = (point.y * scale).toInt().coerceAtLeast(320)
        if (expectedWidth == width && expectedHeight == height) return

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { beginCapture() }
            .onFailure { fail("The screen changed and capture could not follow: " + it.message) }
        server?.let { stream ->
            _state.value = _state.value.copy(width = width, height = height)
            WirelessDisplay.announceMirror(stream.port, _state.value.token, width, height)
        }
    }

    private fun installDisplayListener() {
        val manager = displayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) mainHandler.post { rebuildIfNeeded() }
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, mainHandler)
    }

    private fun onImageAvailable(available: ImageReader) {
        val image = try {
            available.acquireLatestImage()
        } catch (error: Exception) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now

            val imageWidth = image.width
            val imageHeight = image.height
            if (imageWidth <= 0 || imageHeight <= 0) return
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return
            val paddedWidth = imageWidth + (rowStride - pixelStride * imageWidth) / pixelStride
            if (paddedWidth <= 0) return

            var bitmap = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth != imageWidth) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight)
                bitmap.recycle()
                bitmap = cropped
            }

            val jpeg = ByteArrayOutputStream(128 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            bitmap.recycle()
            val bytes = jpeg.toByteArray()
            if (bytes.isEmpty()) return

            // A screen the operating system already knows about is shown locally through
            // Presentation. It receives the same JPEGs as a network viewer, so one capture path
            // serves a TV on the wall and a Prism device across the room.
            localFrames?.invoke(imageWidth, imageHeight, bytes)
            server?.sendFrame(imageWidth, imageHeight, bytes)
            val count = frames.incrementAndGet()
            if (count == 1L || count % 30L == 0L) {
                updateState { it.copy(frames = count) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Skipped a screen frame: " + error.message)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun stopMirror() {
        if (stopping) return
        stopping = true
        runCatching { WirelessDisplay.stopMirror() }
        releaseCapture()
        server?.close()
        server = null
        frames.set(0)
        _state.value = State()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun releaseCapture() {
        displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        displayManager = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.value = State(error = message)
        stopping = true
        // Cleanup is posted because fail() can be called from the image thread, and tearing down
        // the reader from inside its own callback is a dependable deadlock.
        mainHandler.post {
            releaseCapture()
            server?.close()
            server = null
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIFICATION_ID)
            }
            stopSelf()
        }
    }

    // ── State and notification ─────────────────────────────────────────────

    private fun updateState(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen mirroring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps a Prism display receiving this screen while you use other apps."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(viewers: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, com.prism.launcher.display.WirelessDisplaysActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mirror_24)
            .setContentTitle("Sharing this screen")
            .setContentText(
                when {
                    viewers <= 0 -> "Waiting for the display to open the stream"
                    viewers == 1 -> "1 display connected · tap Stop to end"
                    else -> "$viewers displays connected · tap Stop to end"
                }
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifyViewers(viewers: Int) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(viewers))
        }
    }

    data class State(
        val running: Boolean = false,
        val port: Int = 0,
        val token: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val viewers: Int = 0,
        val frames: Long = 0,
        val startedAt: Long = 0,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "PrismMirror"
        private const val MAX_LONG_EDGE = 1280
        private const val JPEG_QUALITY = 62
        private const val FRAME_INTERVAL_MS = 80L

        const val ACTION_START = "com.prism.launcher.mirror.START"
        const val ACTION_STOP = "com.prism.launcher.mirror.STOP"
        const val EXTRA_RESULT_CODE = "com.prism.launcher.mirror.resultCode"
        const val EXTRA_RESULT_DATA = "com.prism.launcher.mirror.resultData"

        private const val CHANNEL_ID = "screen_mirror"
        private const val NOTIFICATION_ID = 8099

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /**
         * The frame sink for a display attached to this phone.
         *
         * A system display (a cast target, a TV the platform exposes, an HDMI panel) is not a
         * network receiver. It still wants the same pixels, so the service hands them to this
         * callback as well as to [MirrorStreamServer]. One capture, two destinations.
         */
        @Volatile
        var localFrames: ((width: Int, height: Int, jpeg: ByteArray) -> Unit)? = null

        fun isRunning(): Boolean = _state.value.running

        fun stop(context: Context) {
            // stopService, not an ACTION_STOP intent: this is called from the disconnect callback
            // while the service is already tearing down, and starting a new instance just to tell
            // it to stop would resurrect the capture it had finished releasing.
            runCatching { context.stopService(Intent(context, ScreenMirrorService::class.java)) }
        }

        /** Sends the stream address again after a link is established. */
        fun announce() {
            val current = _state.value
            if (!current.running) return
            WirelessDisplay.announceMirror(current.port, current.token, current.width, current.height)
        }
    }
}
