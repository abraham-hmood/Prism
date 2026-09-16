package com.prism.launcher.lock

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * First-run setup for the lock screen.
 *
 * ## The order, and why it is this order
 *
 * Mechanism, then the real credential, then the duress credential. The duress step comes last and is
 * explained rather than presented as another field, because a user who does not understand what it
 * is will either skip it or -- much worse -- set something they will forget, which turns a safety
 * feature into a random chance of silently alerting their contacts.
 *
 * It is skippable. A duress code nobody understood is worse than none.
 *
 * ## What it tells the truth about
 *
 * That Android will not let any app replace the keyguard, and that this is a second lock unless the
 * system one is set to None or Swipe. Every third-party lock screen on the store has this property
 * and almost none of them say so.
 */
class LockSetupActivity : PrismBaseActivity() {

    private val content by lazy { LinearLayout(this) }

    private var mechanism: LockStore.Mechanism? = null
    private var firstEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@LockSetupActivity))
        }
        content.orientation = LinearLayout.VERTICAL
        val pad = IosUi.dp(this, 20f)
        content.setPadding(pad, pad, pad, pad)
        root.addView(content)
        setContentView(root)

        stepChooseMechanism()
    }

    // ── Step 1: how ────────────────────────────────────────────────────────

    private fun stepChooseMechanism() {
        content.removeAllViews()

        heading("Lock screen")
        body(
            "Prism can draw its own lock screen with your medical information on it and a second, " +
                "silent code for emergencies.\n\n" +
                "Android does not let any app replace the system lock — that has not been possible " +
                "since Android 5. Until you set the system lock to None or Swipe, you will unlock " +
                "twice: Android's, then Prism's."
        )

        LockStore.Mechanism.entries.forEach { option ->
            content.addView(
                IosUi.filledButton(this, option.label).apply {
                    setOnClickListener {
                        mechanism = option
                        firstEntry = null
                        stepEnterCredential(duress = false)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = IosUi.dp(this@LockSetupActivity, 10f) }
            )
        }

        if (LockGate.biometricAvailable(this)) {
            body(
                "\nThis device has a fingerprint sensor, so unlocking with it will be offered too. " +
                    "Note that a fingerprint cannot trigger the emergency code — it is one identity, " +
                    "and the emergency code depends on there being a second secret only you know."
            )
        }
    }

    // ── Step 2 and 3: what ─────────────────────────────────────────────────

    private fun stepEnterCredential(duress: Boolean) {
        content.removeAllViews()
        val kind = mechanism ?: return stepChooseMechanism()

        heading(if (duress) "Emergency code" else "Set your ${kind.label.lowercase()}")

        if (duress) {
            body(
                "This is a second ${kind.label.lowercase()} that also unlocks the phone — but when " +
                    "you use it, Prism quietly sends your location to your emergency contacts and " +
                    "opens a launcher with nothing on it.\n\n" +
                    "It looks and behaves exactly like a normal unlock. Nobody watching can tell.\n\n" +
                    "Choose something you will remember under pressure and would never type by " +
                    "accident. You can skip this and set it later in Settings."
            )
        } else {
            body("You will enter this to unlock. Choose something you will not forget — Prism " +
                "cannot recover it for you.")
        }

        when (kind) {
            LockStore.Mechanism.PATTERN -> patternEntry(duress)
            else -> textEntry(kind, duress)
        }

        if (duress) {
            content.addView(
                IosUi.tintedButton(this, "Skip for now").apply { setOnClickListener { finishSetup() } },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = IosUi.dp(this@LockSetupActivity, 12f) }
            )
        }
    }

    private fun patternEntry(duress: Boolean) {
        val view = PatternView(this)
        val prompt = TextView(this).apply {
            text = if (firstEntry == null) "Draw the pattern" else "Draw it again to confirm"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@LockSetupActivity))
        }
        content.addView(prompt)
        content.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 320f)
        ))

        view.onPattern = { drawn ->
            if (drawn.length < MIN_PATTERN_DOTS) {
                toast("Use at least $MIN_PATTERN_DOTS dots")
                view.errorState = true
                view.postDelayed({ view.reset() }, 600)
            } else {
                handleEntry(drawn, duress) {
                    prompt.text = "Draw it again to confirm"
                    view.reset()
                }
            }
        }
    }

    private fun textEntry(kind: LockStore.Mechanism, duress: Boolean) {
        val field = EditText(this).apply {
            inputType = if (kind == LockStore.Mechanism.PIN) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            hint = if (firstEntry == null) kind.label else "Confirm ${kind.label.lowercase()}"
            textSize = 17f
            setTextColor(IosUi.label(this@LockSetupActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@LockSetupActivity))
            background = IosUi.fieldBackground(this@LockSetupActivity)
            val pad = IosUi.dp(this@LockSetupActivity, 14f)
            setPadding(pad, pad, pad, pad)
        }
        content.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        content.addView(
            IosUi.filledButton(this, "Continue").apply {
                setOnClickListener {
                    val value = field.text.toString()
                    val minimum = if (kind == LockStore.Mechanism.PIN) MIN_PIN else MIN_PASSWORD
                    if (value.length < minimum) {
                        toast("At least $minimum characters")
                        return@setOnClickListener
                    }
                    handleEntry(value, duress) {
                        field.setText("")
                        field.hint = "Confirm ${kind.label.lowercase()}"
                    }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(this@LockSetupActivity, 12f) }
        )
    }

    /**
     * Collects a credential twice before committing it.
     *
     * Confirmation is not ceremony here. A mistyped lock credential on a launcher that is also the
     * home screen means the user cannot get into their own phone, and there is no recovery path
     * that would not also be a way around the lock.
     */
    private fun handleEntry(value: String, duress: Boolean, onFirst: () -> Unit) {
        val first = firstEntry
        if (first == null) {
            firstEntry = value
            onFirst()
            return
        }
        if (first != value) {
            toast("Those did not match — start that step again")
            firstEntry = null
            stepEnterCredential(duress)
            return
        }

        firstEntry = null
        if (duress) {
            val error = LockStore.setDuress(this, value)
            if (error != null) {
                toast(error)
                stepEnterCredential(duress = true)
                return
            }
            finishSetup()
        } else {
            LockStore.configure(this, mechanism!!, value)
            stepEnterCredential(duress = true)
        }
    }

    private fun finishSetup() {
        PrismSettings.setLockScreenEnabled(true)
        LockGate.install(this)
        toast(
            if (LockStore.hasDuress(this)) "Lock screen on, with an emergency code set"
            else "Lock screen on"
        )
        finish()
    }

    // ── Pieces ─────────────────────────────────────────────────────────────

    private fun heading(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(IosUi.label(this@LockSetupActivity))
            setPadding(0, 0, 0, IosUi.dp(this@LockSetupActivity, 10f))
        })
    }

    private fun body(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@LockSetupActivity))
            setPadding(0, 0, 0, IosUi.dp(this@LockSetupActivity, 14f))
        })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val MIN_PIN = 4
        const val MIN_PASSWORD = 6
        const val MIN_PATTERN_DOTS = 4
    }
}
