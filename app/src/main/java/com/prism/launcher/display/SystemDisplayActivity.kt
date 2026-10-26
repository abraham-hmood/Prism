package com.prism.launcher.display

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.display.mirror.ScreenMirrorService
import com.prism.launcher.nora.IosUi
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * A screen attached to this phone, which is how an HDMI monitor or a television exposed by the
 * platform's own cast stack appears.
 *
 * These are not Prism devices: there is no Prism process on the far end. They are also not a
 * browser. Android's `Presentation` API draws into the display directly, and the phone's existing
 * screen capture feeds it the same JPEG frames a Prism device receives, so this screen shows the
 * phone rather than rebuilding a page of it.
 *
 * Discovered through `DISPLAY_CATEGORY_PRESENTATION` rather than by listing every display: a
 * display only qualifies if it can host a presentation window, and one that cannot would appear
 * in the list and then fail when picked.
 */
object SystemDisplays {

    data class Screen(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
    ) {
        val resolution: String
            get() = width.toString() + " × " + height
    }

    fun screens(context: Context): List<Screen> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return runCatching {
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { display ->
                val mode = display.mode
                Screen(
                    id = display.displayId,
                    name = display.name?.takeIf { it.isNotBlank() } ?: "Display " + display.displayId,
                    width = mode?.physicalWidth ?: 0,
                    height = mode?.physicalHeight ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Mirroring the phone onto a non-Prism screen.
 *
 * ## One capture, two destinations
 *
 * [ScreenMirrorService] already renders the phone into JPEG frames for a Prism device. Here those
 * same frames go to an [ImageView] on the far display. Starting this activity with a mirror
 * already running attaches to it; otherwise the activity asks for capture consent and starts it.
 *
 * ## Why this is an activity and not a presenter the settings page owns
 *
 * A `Presentation` is a window attached to its owner. If the settings list owned it, backing out
 * of settings would blank the TV. Owning it here gives the presentation a lifetime of its own:
 * Home leaves this activity alive in the background with the mirror still on the television, and
 * the Stop button is the deliberate way to end it. Back is redirected to Home for that reason.
 */
class SystemDisplayActivity : PrismBaseActivity() {

    private lateinit var presentation: MirrorPresentation
    private lateinit var status: TextView
    private var displayName: String = "Display"
    private var startedService = false

    @Volatile private var receiving = false
    @Volatile private var pendingFrame: ByteArray? = null
    private var decoder: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderServiceState()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startedService = !ScreenMirrorService.isRunning()
            val intent = Intent(this, ScreenMirrorService::class.java)
                .setAction(ScreenMirrorService.ACTION_START)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, intent)
            attachToCapture()
        } else {
            Toast.makeText(this, "Screen capture permission was declined.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        displayName = intent.getStringExtra(EXTRA_NAME) ?: "Display"
        val screen = SystemDisplays.screens(this).firstOrNull { it.id == id }
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(id)
        if (screen == null || display == null) {
            Toast.makeText(this, "That display is no longer connected.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(controller())
        presentation = MirrorPresentation(this, display)
        runCatching { presentation.show() }
            .onFailure {
                Toast.makeText(this, "Could not use that display: " + it.message, Toast.LENGTH_LONG).show()
                finish()
                return
            }

        if (ScreenMirrorService.isRunning()) {
            attachToCapture()
        } else {
            status.text = "Waiting for screen capture permission…"
            val manager = getSystemService(MediaProjectionManager::class.java)
            projectionPermission.launch(manager.createScreenCaptureIntent())
        }
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onResume() {
        super.onResume()
        renderServiceState()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        detachFromCapture()
        runCatching { presentation.dismiss() }
        super.onDestroy()
    }

    /** Keeps the display alive when the user goes back to the phone; Stop is the way out. */
    @Deprecated("Back is treated as Home here on purpose")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun controller(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28f), dp(28f), dp(28f), dp(28f))
            setBackgroundColor(IosUi.groupedBackground(this@SystemDisplayActivity))
        }

        column.addView(TextView(this).apply {
            text = "MIRRORING TO"
            textSize = 11f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.tertiaryLabel(this@SystemDisplayActivity))
        })

        column.addView(TextView(this).apply {
            text = displayName
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setTextColor(IosUi.label(this@SystemDisplayActivity))
            setPadding(0, dp(6f), 0, dp(10f))
        })

        status = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@SystemDisplayActivity))
            setPadding(0, 0, 0, dp(24f))
        }
        column.addView(status)

        column.addView(TextView(this).apply {
            text = "Stop mirroring"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF8A8A"))
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            setOnClickListener { stopAndFinish() }
        })

        column.addView(TextView(this).apply {
            text = "The screen fills that display. Press Home to use the phone while it stays on; " +
                "this screen stops it."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(IosUi.tertiaryLabel(this@SystemDisplayActivity))
            setPadding(0, dp(22f), 0, 0)
        })

        return column
    }

    private fun attachToCapture() {
        if (receiving) return
        receiving = true
        status.text = "Starting screen capture…"

        ScreenMirrorService.localFrames = { width, height, jpeg ->
            // The capture service calls this on the main thread. Decoding there would spend the
            // frame budget on the phone's UI, so only the newest bytes are handed to a worker.
            pendingFrame = jpeg
        }

        decoder = thread(name = "prism-system-display", isDaemon = true) {
            while (receiving) {
                val bytes = pendingFrame
                if (bytes == null) {
                    Thread.sleep(6L)
                    continue
                }
                pendingFrame = null
                val bitmap = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: continue
                runOnUiThread {
                    if (receiving) {
                        presentation.showFrame(bitmap)
                        status.text = "Mirroring this screen"
                    } else {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun detachFromCapture() {
        receiving = false
        pendingFrame = null
        if (ScreenMirrorService.localFrames != null) ScreenMirrorService.localFrames = null
        decoder?.interrupt()
        decoder = null
        if (startedService) {
            ScreenMirrorService.stop(this)
            startedService = false
        }
    }

    private fun stopAndFinish() {
        detachFromCapture()
        runCatching { presentation.dismiss() }
        finish()
    }

    private fun renderServiceState() {
        if (!::status.isInitialized) return
        if (!ScreenMirrorService.isRunning()) {
            if (startedService) {
                val error = ScreenMirrorService.state.value.error
                status.text = error ?: "Screen capture stopped."
            }
            return
        }
        if (status.text == "Starting screen capture…") {
            status.text = "Waiting for the first frame…"
        }
    }

    /**
     * The window on the far display.
     *
     * `Presentation` already fills the display it was created for; the flags here remove the
     * system bars from it as well, because a TV showing a status bar is showing the phone's
     * chrome rather than the screen.
     */
    private class MirrorPresentation(context: Context, display: Display) :
        Presentation(context, display) {

        private val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        private val view = ImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { win ->
                WindowCompat.setDecorFitsSystemWindows(win, false)
                WindowInsetsControllerCompat(win, win.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setContentView(root)
        }

        fun showFrame(bitmap: android.graphics.Bitmap) {
            view.setImageBitmap(bitmap)
        }
    }

    companion object {
        private const val EXTRA_DISPLAY_ID = "prism.display.system.id"
        private const val EXTRA_NAME = "prism.display.system.name"

        fun intent(context: Context, screen: SystemDisplays.Screen): Intent =
            Intent(context, SystemDisplayActivity::class.java)
                .putExtra(EXTRA_DISPLAY_ID, screen.id)
                .putExtra(EXTRA_NAME, screen.name)
    }
}
