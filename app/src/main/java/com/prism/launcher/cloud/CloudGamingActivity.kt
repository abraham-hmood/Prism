package com.prism.launcher.cloud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.nora.IosUi
import com.prism.launcher.virtualization.VncKeysyms

/**
 * The screen a cloud game is played on.
 *
 * ## Why a separate activity and not a panel on the Cloud page
 *
 * A game wants the whole screen, the screen kept awake, and the hardware keyboard. None of those are
 * things a view inside a swipeable pager can have: the pager steals horizontal drags, which is most of
 * the input a game needs, and a page has no way to hold a wake lock or claim key events. It also means
 * leaving the game is pressing back rather than accidentally swiping to the next page mid-match.
 *
 * ## Input
 *
 * Touch becomes mouse. A tap is a left click at that point, a drag is a move with the button held, and
 * a two-finger tap is a right click — the mapping every remote-desktop client on Android converges on,
 * because it is the one people already know. Coordinates are scaled onto the remote framebuffer by
 * [CloudGaming.Session], which is the only thing that knows the remote size and knows it changes when
 * the game does.
 *
 * A hardware or on-screen keyboard goes through as X11 keysyms via [VncKeysyms], the same translation
 * Prism's own VM display uses.
 *
 * ## What is deliberately not here
 *
 * No gamepad mapping, no pointer lock, no relative-mouse mode. A game that captures the cursor — which
 * is most first-person games — will not behave correctly with absolute touch coordinates, and pretending
 * otherwise by sending synthetic deltas would be worse than the honest limitation. The performance note
 * on the Cloud page says which games this suits.
 */
class CloudGamingActivity : PrismBaseActivity() {

    private lateinit var display: SurfaceView
    private lateinit var status: TextView

    private var session: CloudGaming.Session? = null
    private var peer: MeshComputeRegistry.Peer? = null
    private var title = ""

    /** Set once the Surface exists, so a connect requested before that is not lost. */
    private var surfaceReady = false
    private var connectPending = false

    @Volatile private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val peerIp = intent.getStringExtra(EXTRA_PEER_IP).orEmpty()
        peer = MeshComputeRegistry.all().firstOrNull { it.peerIp == peerIp }

        // A game is watched, not read. Without this the screen dims mid-session, because Android has
        // no idea anything is happening -- there is no touch input during a cutscene.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        display = SurfaceView(this)
        display.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (connectPending) {
                    connectPending = false
                    connect()
                } else {
                    // Coming back from being backgrounded: the Surface was destroyed and recreated, so
                    // the renderer is pointed at the new one and asked for a full frame. Without this
                    // the picture is blank until something on the remote side happens to repaint.
                    session?.rebind(holder.surface)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                session?.rebind(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
        root.addView(display, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            val pad = IosUi.dp(this@CloudGamingActivity, 12f)
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
        }
        controls.addView(IosUi.tintedButton(this, "Keyboard").apply {
            setOnClickListener { toggleKeyboard() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(IosUi.tintedButton(this, "Esc").apply {
            setOnClickListener { tapKey(VncKeysyms.ESCAPE) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(IosUi.tintedButton(this, "Disconnect").apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        setContentView(root)

        val target = peer
        if (target == null) {
            status.text = "That machine is no longer on the mesh."
            return
        }
        if (!target.canHostGames) {
            status.text = "${target.deviceName} is not a PC with Steam."
            return
        }

        status.text = "Starting $title on ${target.deviceName}…"
        if (surfaceReady) connect() else connectPending = true
    }

    private fun connect() {
        val target = peer ?: return
        if (connecting || session != null) return
        connecting = true

        Thread({
            val result = CloudGaming.play(this, target, title, display.width, display.height)
            runOnUiThread {
                connecting = false
                when (result) {
                    is CloudGaming.Launch.Failed -> status.text = result.reason
                    is CloudGaming.Launch.Started -> {
                        val attached = CloudGaming.attach(result.socket, display.holder.surface)
                        if (attached == null) {
                            status.text = "Connected, but the display handshake failed."
                            runCatching { result.socket.close() }
                        } else {
                            session = attached
                            val (w, h) = attached.remoteSize
                            status.text = "$title on ${target.deviceName} — ${w}×$h"
                            // The banner is in the way of the game once it has nothing left to say.
                            status.postDelayed({ status.visibility = View.GONE }, 2500)
                        }
                    }
                }
            }
        }, "cloud-game-connect").start()
    }

    // ── Input ──────────────────────────────────────────────────────────────

    companion object {
        /**
         * RFB button-mask bits, which are a bitfield rather than button numbers.
         *
         * Left is bit 0 and right is bit 2 -- so right is 4, not 2. Bit 1 is the middle button, which
         * nothing here sends.
         */
        private const val LEFT = 1
        private const val RIGHT = 4

        private const val EXTRA_PEER_IP = "peer_ip"
        private const val EXTRA_TITLE = "title"

        fun launch(context: Context, peerIp: String, title: String) {
            context.startActivity(
                Intent(context, CloudGamingActivity::class.java)
                    .putExtra(EXTRA_PEER_IP, peerIp)
                    .putExtra(EXTRA_TITLE, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val live = session ?: return super.onTouchEvent(event)
        val width = display.width
        val height = display.height

        // Two fingers means right button, decided at DOWN and held for the gesture. Deciding per-event
        // would flip the button mid-drag when a second finger landed or lifted, which the remote side
        // sees as a button release and a different button press in the middle of a drag.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerButton = LEFT
                live.sendPointer(event.x, event.y, width, height, LEFT)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // Release the left press that the first finger already sent, then press right.
                    live.sendPointer(event.x, event.y, width, height, 0)
                    pointerButton = RIGHT
                    live.sendPointer(event.x, event.y, width, height, RIGHT)
                }
            }

            MotionEvent.ACTION_MOVE ->
                live.sendPointer(event.x, event.y, width, height, pointerButton)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // The zero is not optional. RFB has no separate release message, so a gesture that
                // ends without it leaves the remote button held down for good.
                live.sendPointer(event.x, event.y, width, height, 0)
                pointerButton = 0
            }
        }
        return true
    }

    private var pointerButton = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back is how you leave, so it is never forwarded. Everything else goes to the game.
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val live = session ?: return super.onKeyDown(keyCode, event)
        val keysym = keysymFor(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        live.sendKey(keysym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        val live = session ?: return super.onKeyUp(keyCode, event)
        val keysym = keysymFor(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        live.sendKey(keysym, false)
        return true
    }

    /**
     * An Android key event as an X11 keysym.
     *
     * Keycode first, character second, which is the order [VncKeysyms] is built around: the keycode
     * table covers the keys that produce no character (arrows, function keys, Escape), and everything
     * printable comes from `unicodeChar`, because that already accounts for shift state and the
     * keyboard layout in a way a keycode cannot.
     */
    private fun keysymFor(keyCode: Int, event: KeyEvent): Int? =
        VncKeysyms.forKeyCode(keyCode)
            ?: event.unicodeChar.takeIf { it != 0 }?.let { VncKeysyms.forCharacter(it.toChar()) }

    private fun tapKey(keysym: Int) {
        val live = session ?: return
        live.sendKey(keysym, true)
        // A real hold, because a press and release sent back to back can both land inside one polling
        // interval on the remote side and be seen as neither.
        display.postDelayed({ live.sendKey(keysym, false) }, 60)
    }

    private fun toggleKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        display.isFocusableInTouchMode = true
        display.requestFocus()
        manager.showSoftInput(display, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        // Closed here and not in onPause: backgrounding the app should not end the session, because a
        // notification or a phone call would then kill the game. Leaving the activity does.
        runCatching { session?.close() }
        session = null
        peer?.let { CloudGaming.stop(it) }
    }
}
