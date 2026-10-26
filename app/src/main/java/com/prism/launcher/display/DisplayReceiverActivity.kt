package com.prism.launcher.display

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.display.mirror.MirrorStreamClient
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * What a wireless display shows.
 *
 * Full screen, no chrome, screen kept awake: this is meant for a TV or a tablet propped up as
 * one. The only control is a small close button, because the way out of a display someone else is
 * driving must not depend on that other device still being in the room.
 *
 * ## It shows a live picture
 *
 * This used to rebuild a page from a slot name. A page is not a screen: it showed the receiver's
 * own files page when the sender was on Files, and it could never show a game, a video or a
 * gesture. The display now receives the sender's actual screen as a stream of JPEG frames over the
 * control link's transport, with no browser and no local page factory in between.
 *
 * ## Two screens in one activity
 *
 * Before a sender is allowed to show anything, an untrusted one has to prove the user is in the
 * room. That proof is a code, and the code belongs on THIS screen -- the device that will be
 * looked at. So the activity opens first with the code, filling the display, and the first mirror
 * frame replaces it. A separate pairing activity would blink between two fullscreen windows at
 * exactly the moment the user is reading a code.
 *
 * ## When the sender leaves
 *
 * The last frame stays on screen if the stream stops without the activity being closed, rather
 * than flashing to black at the instant a phone is pocketed.
 */
class DisplayReceiverActivity : PrismBaseActivity() {

    private lateinit var container: FrameLayout
    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private var rendered: String? = null
    private var showingCode = false
    private var showingMirror = false
    private var showingPage = false

    @Volatile private var client: MirrorStreamClient? = null
    private var readerThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private val codeTicker = object : Runnable {
        override fun run() {
            if (!showingCode) return
            // On a REVERSE link -- the other device long-pressed this one -- this side is the
            // sender, not the display. Once the proof is accepted there is no mirror coming here,
            // so the code screen would stay fullscreen over the app that has to drive the pages.
            if (WirelessDisplay.isConnected() && WirelessDisplay.isSending()) {
                finish()
                return
            }
            handler.postDelayed(this, 600L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                // A display that is a phone on a shelf may be asleep or locked when the sender
                // connects. Turning the screen on and drawing over the lock is what makes it a
                // display rather than a notification the user has to dismiss first.
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        // Draw behind the bars as well as hiding them: some devices briefly show a navigation
        // gesture bar on a resumed activity, and an inset would letterbox the screen around it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContentView(build())
        showFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            showFromIntent(intent)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(codeTicker)
        stopMirror()
        // A code or capture request that was answered here must not linger in the shade after
        // the fullscreen window is gone. [PrismApp] clears it on an incoming stream as well.
        DisplayRequestNotification.clear(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Immersive sticky, on every API this app supports. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(dp(28f), dp(16f), dp(28f), dp(16f))
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        // BOTTOM-RIGHT, not a top bar. A screen that has its own controls up there deserves a
        // close pill that stays out of the way of them.
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(8f), dp(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#66000000"))
            }
        }

        caption = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#88FFFFFF"))
        }
        chrome.addView(caption, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        chrome.addView(TextView(this).apply {
            text = "Close"
            textSize = 12f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setBackgroundColor(Color.parseColor("#33000000"))
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                // The far side is showing a live screen here. Closing the display ends that
                // session; leaving the link open would keep the sender's notification up for a
                // screen nobody is looking at.
                if (showingCode || showingMirror || showingPage) WirelessDisplay.disconnect()
                finish()
            }
        })

        root.addView(chrome, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            setMargins(0, 0, dp(16f), dp(16f))
        })
        return root
    }

    private fun showFromIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_CLOSE, false)) {
            finish()
            return
        }

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()

        // A stream or a page wins over a code. The first offer after acceptance is what turns this
        // window from the pairing screen into the display, and it may arrive while the code is
        // still on screen -- that is the normal case, not a race to avoid.
        if (!host.isNullOrBlank() && port > 0 && token.isNotBlank()) {
            showMirror(host, port, token, from)
            return
        }

        val slot = intent.getStringExtra(EXTRA_SLOT)
        if (!slot.isNullOrBlank()) {
            showPage(
                WirelessDisplay.Show(
                    slot = slot,
                    index = intent.getIntExtra(EXTRA_INDEX, 0),
                    from = from,
                ),
            )
            return
        }

        val code = intent.getStringExtra(EXTRA_CODE)
        if (!code.isNullOrBlank()) {
            showCode(
                code,
                from,
                intent.getBooleanExtra(EXTRA_SENDING, false),
                intent.getBooleanExtra(EXTRA_EXTENDING, false),
            )
        }
    }

    private fun showMirror(host: String, port: Int, token: String, from: String) {
        val key = "mirror:$host:$port:$token"
        if (rendered == key && showingMirror && client != null) return

        stopMirror()
        showingCode = false
        showingPage = false
        showingMirror = true
        handler.removeCallbacks(codeTicker)
        rendered = key
        container.removeAllViews()
        status.visibility = View.VISIBLE
        status.text = "Connecting to " + from.ifBlank { "the other device" } + "…"
        caption.text = (("From " + from).takeIf { from.isNotBlank() }) ?: "Prism display"

        image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val stream = MirrorStreamClient(host, port, token)
        client = stream
        readerThread = thread(name = "prism-mirror-viewer", isDaemon = true) {
            try {
                stream.connect()
                runOnUiThread { if (client === stream) status.visibility = View.GONE }
                while (client === stream) {
                    val frame = stream.readFrame() ?: break
                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                    }.getOrNull() ?: continue
                    runOnUiThread {
                        if (client === stream && ::image.isInitialized) {
                            image.setImageBitmap(bitmap)
                            status.visibility = View.GONE
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            } catch (error: Exception) {
                if (client === stream) {
                    runOnUiThread {
                        if (client === stream) {
                            status.visibility = View.VISIBLE
                            status.text = "Could not receive the screen: " +
                                (error.message ?: error.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                runCatching { stream.close() }
                if (client === stream) {
                    client = null
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && client == null) {
                            status.visibility = View.VISIBLE
                            status.text = "The screen share ended."
                        }
                    }
                }
            }
        }
    }

    /**
     * EXTEND: the sender named a page, and this display renders that page locally.
     *
     * A page is not a screenshot, so a running mirror is stopped first: leaving the video under a
     * page would show the old screen through any transparent part of it, and keeping the reader
     * thread alive would hold a stream nobody is looking at.
     */
    private fun showPage(show: WirelessDisplay.Show) {
        val key = "page:" + show.slot + ":" + show.index
        if (rendered == key && showingPage) return

        stopMirror()
        showingCode = false
        showingPage = true
        rendered = key
        handler.removeCallbacks(codeTicker)
        container.removeAllViews()
        status.visibility = View.GONE
        caption.text = (("Extended from " + show.from).takeIf { show.from.isNotBlank() })
            ?: "Prism display"
        container.addView(
            DisplayPageFactory.build(this, show),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun stopMirror() {
        runCatching { client?.close() }
        client = null
        readerThread?.interrupt()
        readerThread = null
        showingMirror = false
    }

    /**
     * The code, as large as the display can make it.
     *
     * Read off a screen across a room, so the size is capped by width rather than fixed: six
     * characters at 72sp on a phone fit, and the same six at 160sp on a TV are legible from a
     * sofa. `letterSpacing` is what keeps a symbol like `%^&` from reading as a smudge.
     */
    private fun showCode(code: String, from: String, sending: Boolean, extending: Boolean) {
        val key = "code:" + code
        if (rendered == key) return
        // This window IS the code now; leaving the notification as well would make the same
        // request look like two separate ones.
        DisplayRequestNotification.clear(this)
        stopMirror()
        rendered = key
        showingCode = true
        handler.removeCallbacks(codeTicker)
        handler.postDelayed(codeTicker, 600L)
        status.visibility = View.GONE
        caption.text = if (from.isBlank()) "Pairing" else "Pairing with $from"
        container.removeAllViews()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40f), dp(40f), dp(40f), dp(40f))
            setBackgroundColor(Color.BLACK)
        }

        column.addView(TextView(this).apply {
            text = if (sending) {
                if (extending) {
                    "Sharing this device's next page with " + from.ifBlank { "the other device" }
                } else {
                    "Sharing this screen with " + from.ifBlank { "the other device" }
                }
            } else if (extending) {
                "Let " + from.ifBlank { "the other device" } + " show its next page here"
            } else {
                "Let " + from.ifBlank { "the other device" } + " show its screen here"
            }
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAFFFFFF"))
        })

        column.addView(TextView(this).apply {
            text = code
            // Shrink by length rather than let a long code run off the screen. Six characters --
            // the default -- keeps the full 64sp; the setting allows up to 32.
            textSize = (384f / code.length.coerceAtLeast(6)).coerceIn(18f, 64f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(28f), 0, dp(28f))
            // One line, shrinking rather than wrapping: a wrapped code is a code people get wrong.
            maxLines = 1
            isSingleLine = true
        })

        column.addView(TextView(this).apply {
            text = "Type this code on " + (if (from.isBlank()) "the other device" else from) +
                " to allow it. It never leaves this screen."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#77FFFFFF"))
        })

        container.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    companion object {
        private const val EXTRA_HOST = "prism.display.mirror.host"
        private const val EXTRA_PORT = "prism.display.mirror.port"
        private const val EXTRA_TOKEN = "prism.display.mirror.token"
        private const val EXTRA_FROM = "prism.display.from"
        private const val EXTRA_CODE = "prism.display.code"
        private const val EXTRA_SENDING = "prism.display.sending"
        private const val EXTRA_EXTENDING = "prism.display.extending"
        private const val EXTRA_CLOSE = "prism.display.close"
        private const val EXTRA_SLOT = "prism.display.page.slot"
        private const val EXTRA_INDEX = "prism.display.page.index"

        fun intent(context: Context, mirror: WirelessDisplay.Mirror): Intent =
            base(context)
                .putExtra(EXTRA_HOST, mirror.host)
                .putExtra(EXTRA_PORT, mirror.port)
                .putExtra(EXTRA_TOKEN, mirror.token)
                .putExtra(EXTRA_FROM, mirror.from)

        /** EXTEND: the page this display should render. */
        fun pageIntent(context: Context, show: WirelessDisplay.Show): Intent =
            base(context)
                .putExtra(EXTRA_SLOT, show.slot)
                .putExtra(EXTRA_INDEX, show.index)
                .putExtra(EXTRA_FROM, show.from)

        /** The code screen, shown on the device that is going to be looked at. */
        fun codeIntent(
            context: Context,
            code: String,
            from: String,
            sending: Boolean,
            extending: Boolean,
        ): Intent =
            base(context)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_FROM, from)
                .putExtra(EXTRA_SENDING, sending)
                .putExtra(EXTRA_EXTENDING, extending)

        /** Asks a code screen that is no longer useful to close itself. */
        fun closeIntent(context: Context): Intent =
            base(context).putExtra(EXTRA_CLOSE, true)

        /**
         * One full-screen window, whatever is sent next.
         *
         * A second stream replaces the first rather than stacking behind it, and the code screen
         * is the same window as the display it turns into.
         */
        private fun base(context: Context): Intent =
            Intent(context, DisplayReceiverActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
    }
}
