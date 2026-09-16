package com.prism.launcher.science

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import kotlin.math.abs
import kotlin.math.exp

/**
 * The camera as a particle detector.
 *
 * ## How this works at all
 *
 * A CMOS image sensor is a grid of silicon diodes. A charged particle passing through one deposits
 * energy exactly as a photon would, and the pixel reads bright. Cover the lens so no light can
 * reach it, take long exposures, and almost every bright pixel left is either a particle or sensor
 * noise. CRAYFIS and DECO both established that this works on stock phone hardware; neither
 * survived as a product, partly because a single phone cannot tell those two cases apart.
 *
 * ## Coincidence is the whole point
 *
 * One phone reporting a bright cluster has, overwhelmingly, reported thermal noise. Two phones
 * reporting one in the same instant have seen the same air shower -- a cascade of secondary
 * particles from one high-energy primary, spread over tens of metres at ground level. Every serious
 * cosmic-ray detector is an array for this reason, and coincidence logic is what turns a pile of
 * noisy sensors into an instrument.
 *
 * So this panel reports **three** numbers rather than one: local candidate hits, mesh coincidences,
 * and the rate of coincidences you would expect *by chance* given both devices' hit rates and the
 * timing window. If the observed coincidence rate is not clearly above the accidental rate, you
 * have measured nothing, and the panel says so rather than letting a number imply a discovery.
 *
 * ## Hot pixels
 *
 * Every sensor has dead or leaky pixels that read bright in every single frame. Left in, they
 * produce a hit on every exposure and swamp everything real. The first 60 frames are spent building
 * a mask of pixels that are bright too often to be particles, and that mask is excluded thereafter.
 */
class CosmicRayPanel(context: Context) : LinearLayout(context) {

    private val status = TextView(context)
    private val stats = TextView(context)
    private val verdict = TextView(context)
    private val meshNote = TextView(context)
    private val startButton = IosUi.filledButton(context, "Start exposure")

    private var running = false

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var background: HandlerThread? = null
    private var handler: Handler? = null

    // ── Measurement state ──────────────────────────────────────────────────

    private var framesAnalysed = 0L
    private var exposureStartedAt = 0L
    private var localHits = 0L
    private var lastFrameMeanLuma = 0.0

    /** Pixels that are bright far too often to be particles. Index = y * width + x. */
    private val hotPixels = HashSet<Int>()
    private val brightCounts = HashMap<Int, Int>()
    private var calibrationFrames = 0

    /** The most recent candidate tracks, for the strip at the bottom. */
    private val recent = ArrayDeque<String>()

    init {
        orientation = VERTICAL
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, pad, pad, pad)

        addView(IosUi.sectionHeader(context, "COSMIC-RAY DETECTOR"))

        status.textSize = 14f
        status.setTextColor(IosUi.label(context))
        status.background = IosUi.fieldBackground(context)
        status.setPadding(pad, pad, pad, pad)
        status.text = INSTRUCTIONS
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        startButton.setOnClickListener { if (running) stop() else start() }
        addView(startButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 14f)
        })

        stats.textSize = 13f
        stats.setTextColor(IosUi.secondaryLabel(context))
        stats.setPadding(0, IosUi.dp(context, 16f), 0, 0)
        addView(stats)

        verdict.textSize = 13f
        verdict.setTextColor(IosUi.label(context))
        verdict.setPadding(0, IosUi.dp(context, 12f), 0, 0)
        addView(verdict)

        meshNote.textSize = 12f
        meshNote.setTextColor(IosUi.secondaryLabel(context))
        meshNote.setPadding(0, IosUi.dp(context, 14f), 0, 0)
        addView(meshNote)

        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    // ── Camera lifecycle ───────────────────────────────────────────────────

    private fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "Prism needs camera access to use the sensor as a detector. " +
                "Grant it in Android Settings > Apps > Prism > Permissions, then start again."
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = pickBackCamera(manager) ?: run {
            status.text = "No usable camera on this device."
            return
        }

        background = HandlerThread("cosmic-ray").apply { start() }
        handler = Handler(background!!.looper)

        // Deliberately small. Resolution buys nothing here -- a particle lights up one pixel or a
        // short track, and a 12-megapixel frame costs 12 million comparisons per exposure for the
        // same physics. Small frames mean more exposures per second, which is what matters.
        reader = ImageReader.newInstance(FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> analyse(r) }, handler)
        }

        framesAnalysed = 0
        localHits = 0
        calibrationFrames = 0
        hotPixels.clear()
        brightCounts.clear()
        recent.clear()
        MeshScience.resetCoincidences()
        exposureStartedAt = System.currentTimeMillis()
        running = true
        render()

        runCatching {
            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    configure(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    PrismLogger.logWarning(TAG, "Camera error $error")
                    camera.close(); cameraDevice = null
                    post { status.text = "The camera could not be opened (error $error)."; stop() }
                }
            }, handler)
        }.onFailure {
            status.text = "Could not open the camera: ${it.message}"
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice) {
        val surface = reader?.surface ?: return
        runCatching {
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(surface)
                        // Manual exposure, as long and as sensitive as the sensor allows. Auto
                        // exposure would see a black frame, decide the scene is dark, and do
                        // exactly this -- but it would also keep hunting, so the exposure time
                        // would vary frame to frame and the hit RATE would become meaningless.
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS)
                        set(CaptureRequest.SENSOR_SENSITIVITY, ISO)
                        // Noise reduction would erase exactly the isolated bright pixels this is
                        // looking for. It is the single most important setting here.
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    }.build()
                    runCatching { configured.setRepeatingRequest(request, null, handler) }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    post { status.text = "The camera would not accept the detector settings."; stop() }
                }
            }, handler)
        }
    }

    private fun stop() {
        running = false
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { reader?.close() }
        session = null; cameraDevice = null; reader = null
        background?.quitSafely()
        background = null; handler = null
        post { render() }
    }

    // ── The analysis ───────────────────────────────────────────────────────

    private fun analyse(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            var sum = 0L
            var brightest = 0
            var brightPixels = 0
            val candidates = ArrayList<Int>(8)

            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val toRead = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, toRead)
                for (x in 0 until width) {
                    val value = row[x].toInt() and 0xFF
                    sum += value
                    if (value < THRESHOLD) continue
                    val index = y * width + x
                    if (index in hotPixels) continue
                    brightPixels++
                    if (value > brightest) brightest = value
                    if (candidates.size < 8) candidates.add(index)
                }
            }

            val meanLuma = sum.toDouble() / (width * height)
            lastFrameMeanLuma = meanLuma
            framesAnalysed++

            if (calibrationFrames < CALIBRATION_FRAMES) {
                // Learning which pixels are simply broken. Anything bright in more than a fifth of
                // the calibration frames is a sensor defect, not a particle -- particles do not
                // repeat at the same coordinate.
                candidates.forEach { brightCounts[it] = (brightCounts[it] ?: 0) + 1 }
                calibrationFrames++
                if (calibrationFrames == CALIBRATION_FRAMES) {
                    brightCounts.forEach { (index, count) ->
                        if (count > CALIBRATION_FRAMES / 5) hotPixels.add(index)
                    }
                    brightCounts.clear()
                    exposureStartedAt = System.currentTimeMillis()   // the real run starts here
                }
                post { render() }
                return
            }

            // A frame whose average brightness is above the dark floor is a light leak, not a
            // shower of particles. Counting those as hits is how this kind of detector produces
            // spectacular and completely false results.
            if (meanLuma > DARK_FLOOR) {
                post {
                    status.text = "Light is reaching the sensor (mean level ${"%.1f".format(meanLuma)}). " +
                        "Cover the camera completely -- frames are being discarded."
                    render()
                }
                return
            }

            if (brightPixels > 0 && brightPixels <= MAX_PIXELS_PER_HIT) {
                val at = System.currentTimeMillis()
                localHits++
                MeshScience.broadcastHit(at, brightest, brightPixels)
                val match = MeshScience.matchCoincidence(at)
                synchronized(recent) {
                    recent.addLast(
                        "${timeOf(at)}  ${brightPixels}px  peak $brightest" +
                            if (match != null) "  ✓ coincident with ${match.peerIp}" else ""
                    )
                    while (recent.size > 6) recent.removeFirst()
                }
            }

            post { render() }
        } finally {
            image.close()
        }
    }

    // ── Reporting ──────────────────────────────────────────────────────────

    private fun render() {
        startButton.text = if (running) "Stop exposure" else "Start exposure"

        if (!running) {
            status.text = INSTRUCTIONS
        } else if (calibrationFrames < CALIBRATION_FRAMES) {
            status.text = "Mapping this sensor's hot pixels ($calibrationFrames of $CALIBRATION_FRAMES frames). " +
                "Keep the camera covered."
        } else if (lastFrameMeanLuma <= DARK_FLOOR) {
            status.text = "Exposing. Keep the camera covered and the phone still."
        }

        val elapsedSec = ((System.currentTimeMillis() - exposureStartedAt) / 1000.0).coerceAtLeast(1.0)
        val perHour = localHits / elapsedSec * 3600.0
        val coincidences = MeshScience.coincidences.value

        stats.text = buildString {
            append("Exposure       ${formatDuration(elapsedSec)}\n")
            append("Frames         $framesAnalysed\n")
            append("Hot pixels     ${hotPixels.size} masked\n")
            append("Dark level     ${"%.2f".format(lastFrameMeanLuma)} (floor $DARK_FLOOR)\n")
            append("Candidate hits $localHits   (${"%.1f".format(perHour)}/hour)\n")
            append("Coincidences   $coincidences")
        }

        verdict.text = verdictFor(localHits, coincidences, elapsedSec)

        meshNote.text = if (!MeshScience.isOnMesh()) {
            "Not on the mesh, so only local hits are counted — and a local hit on its own is " +
                "indistinguishable from sensor noise. Join or serve a mesh and run this on two " +
                "devices to measure anything real."
        } else {
            val recentText = synchronized(recent) { recent.joinToString("\n") }
            "${MeshScience.peerCount()} peer(s) on the mesh · coincidence window " +
                "${MeshScience.COINCIDENCE_WINDOW_MS} ms" +
                if (recentText.isNotBlank()) "\n\n$recentText" else ""
        }
    }

    /**
     * Says whether the coincidence count means anything.
     *
     * Two independent detectors with rates r1 and r2 produce accidental coincidences at
     * 2·r1·r2·τ, where τ is the timing window. With a 50 ms window forced on us by phone clock
     * accuracy, that accidental rate is not small, and a run that has not clearly beaten it has
     * measured nothing. Reporting the comparison rather than the raw count is the difference
     * between an instrument and a toy that produces impressive numbers.
     */
    private fun verdictFor(hits: Long, coincidences: Int, elapsedSec: Double): String {
        if (!MeshScience.isUsable()) return ""
        if (elapsedSec < 60) return "Too early to say anything — let it run for a few minutes."

        val localRate = hits / elapsedSec
        // Assumes the peer's rate is similar to ours, which is the best available estimate without
        // a protocol for exchanging rates. Stated rather than hidden.
        val window = MeshScience.COINCIDENCE_WINDOW_MS / 1000.0
        val accidentalPerSec = 2 * localRate * localRate * window
        val expected = accidentalPerSec * elapsedSec

        return when {
            expected <= 0.0 -> "No hits yet on either device."
            coincidences > expected * 3 ->
                "$coincidences coincidences against ~${"%.1f".format(expected)} expected by chance. " +
                    "That is a real excess — you are almost certainly seeing air showers."
            coincidences > expected ->
                "$coincidences coincidences against ~${"%.1f".format(expected)} expected by chance. " +
                    "Suggestive, not conclusive. Keep running."
            else ->
                "$coincidences coincidences against ~${"%.1f".format(expected)} expected by chance. " +
                    "Nothing measured yet — this is what noise looks like."
        }
    }

    private fun pickBackCamera(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }.getOrNull()

    private fun timeOf(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private companion object {
        const val TAG = "PrismScience"

        const val INSTRUCTIONS =
            "Cover the camera lens completely — electrical tape over it, or the phone face-down on " +
                "a dark surface. Plug the phone in: long exposures at high sensitivity are not " +
                "gentle on the battery. Then start, and leave it alone."

        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480

        /** Long exposure and high gain: the particle flux is low, so the detector must stare. */
        const val EXPOSURE_NS = 200_000_000L   // 0.2 s
        const val ISO = 1600

        /** Luma above which a pixel is a candidate. Well clear of the dark-frame noise floor. */
        const val THRESHOLD = 90

        /** A frame brighter than this on average is leaking light and is discarded entirely. */
        const val DARK_FLOOR = 6.0

        /** More lit pixels than this is a light leak or a screen reflection, not one particle. */
        const val MAX_PIXELS_PER_HIT = 40

        const val CALIBRATION_FRAMES = 60
    }
}
