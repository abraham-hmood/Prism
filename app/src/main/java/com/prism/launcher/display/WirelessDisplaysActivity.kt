package com.prism.launcher.display

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.display.mirror.ScreenMirrorService
import com.prism.launcher.nora.IosUi
import kotlin.math.roundToInt

/**
 * Wireless displays on this network.
 *
 * ## Two kinds of screen, one list
 *
 * A PRISM DEVICE runs this application and receives a live picture of the other screen. It needs
 * verification unless the two devices are already paired: the display shows a code, the sender
 * types it. A TRUSTED device skips that entirely -- it was verified when it was paired, and asking
 * again every time would make the pairing meaningless.
 *
 * A SYSTEM DISPLAY (a TV or monitor the OS exposes) cannot run Prism, so it is presented through
 * Android's own display API by [SystemDisplayActivity]. There is no code because there is no
 * remote device to verify: the screen is attached to this one.
 *
 * A TV that is neither of those is reached through Android's cast settings. Prism cannot start
 * Miracast itself -- the platform hides the Wi-Fi Display API behind a signature permission -- so
 * the honest route is the system UI that owns those negotiations.
 *
 * ## Click and long-press are opposites
 *
 * Clicking a Prism device mirrors THIS screen to it. Long-pressing it does the reverse: the pressed
 * device becomes the sender and this one becomes the display. The verification rule still follows
 * the initiator -- the device whose user acted types the code, the other one shows it.
 */
class WirelessDisplaysActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var disconnect: TextView
    private lateinit var modeGroup: RadioGroup

    private var challengeDialog: androidx.appcompat.app.AlertDialog? = null

    /** The display waiting for Android's screen-capture consent. */
    private var pendingDisplay: WirelessDisplay.Display? = null

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val display = pendingDisplay
        pendingDisplay = null
        val data = result.data
        if (display == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenMirrorService::class.java)
                .setAction(ScreenMirrorService.ACTION_START)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, intent)
            connect(display, reverse = false, captureAlreadyRunning = true)
        } else {
            Toast.makeText(
                this,
                "Screen capture permission is needed to mirror this screen.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_500L)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())

        // The initiating side's prompt. Set on this activity rather than globally because this
        // screen is the only place a connection starts; a prompt over an unrelated page would be
        // a dialog the user could not explain.
        WirelessDisplay.onChallenge = { from ->
            runOnUiThread { promptForCode(from) }
        }
        WirelessDisplay.onRejected = { reason ->
            runOnUiThread {
                challengeDialog?.dismiss()
                challengeDialog = null
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                render()
            }
        }
        WirelessDisplay.onAccepted = {
            runOnUiThread {
                challengeDialog?.dismiss()
                challengeDialog = null
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.postDelayed(ticker, 1_500L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        // Only clear callbacks still pointing at this instance: a challenge that arrived while
        // the screen was closing must not keep a reference to a destroyed activity.
        if (WirelessDisplay.onChallenge != null) WirelessDisplay.onChallenge = null
        WirelessDisplay.onRejected = null
        WirelessDisplay.onAccepted = null
        challengeDialog?.dismiss()
        // Leaving the screen while waiting for a code would strand the other device on a code
        // nobody is going to type. A VERIFIED link is kept: the page can keep following the user
        // after this screen closes.
        if (WirelessDisplay.isAwaitingCode()) WirelessDisplay.disconnect()
        super.onDestroy()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun build(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            setBackgroundColor(IosUi.groupedBackground(this@WirelessDisplaysActivity))
        }

        column.addView(TextView(this).apply {
            text = "‹ Prism Settings"
            textSize = 13f
            setTextColor(IosUi.accent(this@WirelessDisplaysActivity))
            setPadding(0, 0, 0, dp(12f))
            setOnClickListener { finish() }
        })

        column.addView(TextView(this).apply {
            text = "Wireless displays"
            textSize = 26f
            setTextColor(IosUi.label(this@WirelessDisplaysActivity))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(6f), 0, dp(14f))
        }
        column.addView(status)

        disconnect = TextView(this).apply {
            text = "Disconnect"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8A8A"))
            setPadding(0, dp(4f), 0, dp(14f))
            visibility = View.GONE
            setOnClickListener {
                WirelessDisplay.disconnect()
                render()
            }
        }
        column.addView(disconnect)

        // MIMIC AND EXTEND. The choice belongs to the sender, so it lives here rather than on
        // the display: the device the user is holding decides whether the far screen gets a copy
        // of this one or the page after it.
        column.addView(TextView(this).apply {
            text = "HOW TO SHOW IT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(4f), 0, dp(6f))
        })

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#0FFFFFFF"))
                setStroke(dp(1f), Color.parseColor("#22FFFFFF"))
            }
            addView(radioRow(
                MODE_MIMIC,
                "Mimic this screen",
                "Tap: this screen becomes the other screen. Long-press: its screen comes here.",
            ))
            addView(radioRow(
                MODE_EXTEND,
                "Extend the next page",
                "Tap: the next page becomes a second monitor there. Long-press: its next page comes here.",
            ))
            check(if (WirelessDisplay.isExtend()) MODE_EXTEND else MODE_MIMIC)
            setOnCheckedChangeListener { _, checkedId ->
                val extend = checkedId == MODE_EXTEND
                PrismSettings.setWirelessDisplayMode(
                    if (extend) PrismSettings.WIRELESS_DISPLAY_EXTEND
                    else PrismSettings.WIRELESS_DISPLAY_MIMIC,
                )
                // A live link was set up for the other transport. Switching under it would leave
                // a capture running for a display that is no longer expecting frames, so the
                // choice takes effect on the next connection.
                if (WirelessDisplay.isConnected()) {
                    WirelessDisplay.disconnect()
                    Toast.makeText(
                        this@WirelessDisplaysActivity,
                        "Mode changed. Pick a display to connect again.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                render()
            }
        }
        column.addView(modeGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10f) })

        column.addView(TextView(this).apply {
            text = "PRISM DEVICES"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(4f), 0, dp(6f))
        })

        // THE LIST SCROLLS ON ITS OWN. A wall of paired devices plus the screens attached to
        // this phone used to push the page past the window and take the close control with it.
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        column.addView(TextView(this).apply {
            text = "Tap a Prism device to use it as a second monitor. Long-press it to bring its " +
                "screen or next page here instead. Unpaired devices ask for a code shown on the " +
                "other screen."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(10f), 0, 0)
        })

        return column
    }

    private fun radioRow(id: Int, title: String, detail: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
            isClickable = true
            addView(RadioButton(this@WirelessDisplaysActivity).apply {
                this.id = id
                text = title
                textSize = 15f
                setTextColor(IosUi.label(this@WirelessDisplaysActivity))
                buttonTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(this@WirelessDisplaysActivity))
                setPadding(0, 0, 0, 0)
                setOnClickListener { modeGroup.check(id) }
            })
            addView(TextView(this@WirelessDisplaysActivity).apply {
                text = detail
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
                setPadding(dp(34f), 0, 0, 0)
            })
            setOnClickListener { modeGroup.check(id) }
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        // The far side closing while the code prompt is up would otherwise leave a dialog
        // waiting for a connection that is already gone.
        if (challengeDialog != null &&
            !WirelessDisplay.isAwaitingCode() &&
            !WirelessDisplay.isConnected()
        ) {
            challengeDialog?.dismiss()
            challengeDialog = null
        }
        val connected = WirelessDisplay.isConnected()
        val displays = WirelessDisplay.displays()
        val screens = SystemDisplays.screens(this)
        // Read the pairing list ONCE per refresh rather than once per display row: this redraw
        // happens every 1.5 seconds, and a disk read per row is a stutter nobody would connect to
        // a list that only polls for beacons.
        val trustedFingerprints = runCatching {
            com.prism.launcher.trusted.TrustedDevices.all()
                .filter { it.confirmed }
                // Aliases too: a display that announced its identity before a wallet import or a
                // rename is the same paired device, and showing it as "not paired" invited the
                // user to pair it again and create a second record for one screen.
                .flatMap { listOf(it.fingerprint) + it.aliases }
                .toSet()
        }.getOrDefault(emptySet())

        val extend = WirelessDisplay.isExtend()
        val sending = connected && WirelessDisplay.isSending()
        status.text = when {
            connected && sending -> (if (extend) "Extending to " else "Mirroring with ") +
                WirelessDisplay.connectedTo() + "."
            connected && extend -> "Showing " + WirelessDisplay.connectedTo() + "'s next page here."
            connected -> "Showing " + WirelessDisplay.connectedTo() + "'s screen here."
            WirelessDisplay.isAwaitingCode() -> "Waiting for the code on " + WirelessDisplay.connectedTo() + "…"
            displays.isEmpty() && screens.isEmpty() -> "Looking for displays on this network…"
            else -> displays.size.toString() + " Prism device(s) · " +
                screens.size.toString() + " system display(s)."
        }
        disconnect.visibility = if (connected) View.VISIBLE else View.GONE
        if (modeGroup.checkedRadioButtonId != (if (WirelessDisplay.isExtend()) MODE_EXTEND else MODE_MIMIC)) {
            modeGroup.check(if (WirelessDisplay.isExtend()) MODE_EXTEND else MODE_MIMIC)
        }

        list.removeAllViews()
        if (displays.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No Prism devices yet. Open Prism on the other device, and make sure both " +
                    "are on the same Wi-Fi."
                textSize = 13f
                setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
                setPadding(0, dp(10f), 0, dp(14f))
            })
        }

        displays.forEach { display ->
            val isCurrent = connected && WirelessDisplay.connectedTo() == display.name
            list.addView(card(display, isCurrent, display.fingerprint in trustedFingerprints))
        }

        // A system display is local and needs no verification. Each one gets its own controller,
        // so two attached screens can show two pages at once.
        if (screens.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "SYSTEM DISPLAYS"
                textSize = 10f
                letterSpacing = 0.12f
                setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
                setPadding(0, dp(12f), 0, dp(6f))
            })
            screens.forEach { screen -> list.addView(systemCard(screen)) }
        }
    }

    private fun card(
        display: WirelessDisplay.Display,
        connected: Boolean,
        paired: Boolean,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(
                    dp(1f),
                    if (connected) IosUi.accent(this@WirelessDisplaysActivity)
                    else Color.parseColor("#22FFFFFF"),
                )
            }
            isClickable = true
            isLongClickable = true
            setOnClickListener { connect(display, reverse = false) }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                connect(display, reverse = true)
                true
            }
        }

        card.addView(TextView(this).apply {
            text = (if (connected) "● " else "") + display.name
            textSize = 15f
            setTextColor(if (connected) IosUi.accent(this@WirelessDisplaysActivity) else IosUi.label(this@WirelessDisplaysActivity))
        })
        card.addView(TextView(this).apply {
            text = display.platform.ifBlank { "Prism device" } + " · " + display.ip +
                (if (paired) " · paired" else " · not paired") +
                (if (connected) " · connected" else "")
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(3f), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(TextView(this@WirelessDisplaysActivity).apply {
                text = when {
                    connected -> "Disconnect"
                    WirelessDisplay.isExtend() -> "Extend there  ›"
                    else -> "Mirror there  ›"
                }
                textSize = 12f
                setTextColor(IosUi.accent(this@WirelessDisplaysActivity))
                setPadding(dp(10f), dp(6f), 0, 0)
                setOnClickListener {
                    if (connected) {
                        WirelessDisplay.disconnect()
                        render()
                    } else {
                        connect(display, reverse = false)
                    }
                }
            })
        }
        card.addView(row)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10f))
            addView(card)
        }
    }

    private fun systemCard(screen: SystemDisplays.Screen): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#0FFFFFFF"))
                setStroke(dp(1f), Color.parseColor("#22FFFFFF"))
            }
            isClickable = true
            setOnClickListener {
                startActivity(SystemDisplayActivity.intent(this@WirelessDisplaysActivity, screen))
            }
        }

        card.addView(TextView(this).apply {
            text = screen.name
            textSize = 15f
            setTextColor(IosUi.label(this@WirelessDisplaysActivity))
        })
        card.addView(TextView(this).apply {
            text = "Non-Prism display · " + screen.resolution + " · this screen"
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@WirelessDisplaysActivity))
            setPadding(0, dp(3f), 0, dp(6f))
        })

        card.addView(TextView(this).apply {
            text = "Mirror here  ›"
            textSize = 12f
            setTextColor(IosUi.accent(this@WirelessDisplaysActivity))
            setPadding(0, dp(2f), 0, 0)
        })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10f))
            addView(card)
        }
    }

    private fun connect(
        display: WirelessDisplay.Display,
        reverse: Boolean,
        captureAlreadyRunning: Boolean = false,
    ) {
        // A display link must not leave a capture from the previous direction running. Long-press
        // is a request for the other screen, not a second publisher of this one. Extend sends
        // pages, so it has nothing to capture either.
        val extend = WirelessDisplay.isExtend()
        if ((reverse || extend) && ScreenMirrorService.isRunning()) ScreenMirrorService.stop(this)

        // A MIMIC link sends THIS screen, and Android will not hand out a projection without
        // the system consent dialog. Asking here, while the picker is foreground, is the only
        // point in the flow where that dialog can be shown. Extend is page data and needs none.
        if (!reverse && !extend && !captureAlreadyRunning && !ScreenMirrorService.isRunning()) {
            pendingDisplay = display
            val manager = getSystemService(MediaProjectionManager::class.java)
            projectionPermission.launch(manager.createScreenCaptureIntent())
            return
        }

        status.text = if (reverse) {
            if (extend) "Asking " + display.name + " for its next page…"
            else "Asking " + display.name + " for its screen…"
        } else if (extend) {
            "Extending to " + display.name + "…"
        } else {
            "Connecting to " + display.name + "…"
        }
        Thread({
            val message = WirelessDisplay.connect(display, reverse)
            if (!reverse && !extend && message.startsWith("Could not")) {
                // A failed link must not leave a screen-capture notification running for a display
                // that never received anything.
                ScreenMirrorService.stop(this)
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                render()
            }
        }, "prism-display-connect").start()
    }

    /**
     * The code prompt, on the device that initiated.
     *
     * It says whose screen to look at because a code is useless without knowing where it is. Cancel
     * drops the connection rather than leaving a socket waiting for an answer nobody will type.
     */
    private fun promptForCode(from: String) {
        if (isFinishing || isDestroyed) return
        val who = from.ifBlank { "the other device" }
        val input = EditText(this).apply {
            hint = "Code"
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            letterSpacing = 0.12f
        }

        challengeDialog?.dismiss()
        challengeDialog = PrismDialogFactory.show(
            context = this,
            title = "Connect to $who",
            message = "$who should now be showing a code. Type it here to let the ${
                if (WirelessDisplay.willSend()) "screen be shown there" else "other screen be shown here"
            }. The code never leaves that screen.",
            positiveText = "Connect",
            negativeText = "Cancel",
            onPositive = { WirelessDisplay.submitCode(input.text.toString()) },
            onNegative = { WirelessDisplay.disconnect() },
            customView = input,
        )
    }

    companion object {
        private const val MODE_MIMIC = 1
        private const val MODE_EXTEND = 2

        fun intent(context: Context): Intent = Intent(context, WirelessDisplaysActivity::class.java)
    }
}
