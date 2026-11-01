package com.prism.launcher.onboarding

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * The forced Lyke account step.
 *
 * ## Why this cannot be skipped
 *
 * The mail client signs in to a Prism mail server with the Lyke username and password. That is not
 * a preference: the server authenticates against the same identity, and the address it serves is
 * `<username>@<domain>`. A user who left onboarding without one would open the client and have no
 * credentials to enter, then find the SMTP and IMAP settings that explain it several screens away.
 * So onboarding asks for both here, and the tour's Next is disabled until they exist.
 *
 * ## Why the password is not generated
 *
 * It used to be: a random default was created on first read so the feature worked before anyone
 * visited settings. That is fine for a social feed nobody signed in to, and wrong for a mailbox.
 * A password the user has never seen is one they cannot enter on another device, and this is the
 * password a real mail client will ask for. Asking once, here, is the smallest honest fix.
 */
class LykeAccountSetupView(context: Context) : LinearLayout(context) {

    /** Fired when the account exists, so the tour can enable its Next button. */
    var onAccountReady: (() -> Unit)? = null

    private val d = { value: Int -> IosUi.dp(context, value.toFloat()) }

    init {
        orientation = VERTICAL
        build()
    }

    private fun build() {
        removeAllViews()

        if (PrismSettings.getLykeProfileConfirmed() && PrismSettings.hasLykePassword()) {
            addView(TextView(context).apply {
                text = "Signed in as " + PrismSettings.getLykeUserName() +
                    ". Mail for this account is " + PrismSettings.getLykeUserName() +
                    "@" + runCatching {
                        com.prism.launcher.email.EmailSettings.load().domain
                    }.getOrDefault("prism.com") + "."
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
            })
            onAccountReady?.invoke()
            return
        }

        val user = field("Username", "e.g. yourname", password = false)
        val password = field("Password", "At least 8 characters", password = true)
        val confirm = field("Confirm password", "Type it again", password = true)

        addView(label("USERNAME"))
        addView(user)
        addView(label("PASSWORD"))
        addView(password)
        addView(label("CONFIRM PASSWORD"))
        addView(confirm)

        addView(IosUi.filledButton(context, "Create my account").apply {
            setOnClickListener {
                val name = user.text.toString().trim()
                val first = password.text.toString()
                val again = confirm.text.toString()
                when {
                    name.length < 3 ->
                        toast("A username needs at least three characters.")
                    !name.matches(Regex("[A-Za-z0-9._-]+")) ->
                        toast("Usernames can use letters, numbers, dots, dashes and underscores.")
                    first.length < 8 ->
                        toast("The password needs at least eight characters.")
                    first != again ->
                        toast("The two passwords do not match.")
                    else -> {
                        PrismSettings.setLykeUserName(name)
                        PrismSettings.setLykePassword(first)
                        PrismSettings.setLykeProfileConfirmed(true)
                        toast("Account created.")
                        build()
                        onAccountReady?.invoke()
                    }
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(14)
        })

        addView(TextView(context).apply {
            text = "This is the account your Prism mail server serves and your mail client signs " +
                "in with. It is also your Lyke name. The password is stored encrypted with your " +
                "wallet phrase, so only this wallet can read it back."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, d(10), 0, 0)
        })
    }

    private fun field(hintText: String, hint: String, password: Boolean): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 14f
            setSingleLine()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
            }
            setPadding(d(10), d(8), d(10), d(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = d(9).toFloat()
            }
        }

    private fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 9f
        letterSpacing = 0.1f
        setTextColor(IosUi.tertiaryLabel(context))
        setPadding(0, d(9), 0, d(3))
    }

    private fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
