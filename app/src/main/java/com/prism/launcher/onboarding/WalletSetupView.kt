package com.prism.launcher.onboarding

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.WalletVault

/**
 * Wallet creation and restore, inline in the tour.
 *
 * ## Why the tour cannot let somebody past this
 *
 * The wallet's recovery phrase is the root of every other mesh key. Clipboard sharing between a
 * user's own devices, the identity a P2P site is published under, the cloud vault and the message
 * relay are all derived from it, and every one of those features is encrypted rather than merely
 * gated. A user who left the tour without a wallet would find that half of Prism's sharing does
 * nothing, with no explanation, and the setting that fixes it is several screens away. So the
 * third screen creates or restores one, and Next stays disabled until it exists.
 *
 * ## Why the phrase is shown here rather than only on the Wallet page
 *
 * Because this IS the moment it is created, and this is the only point at which the user has not
 * yet been told they can find it later. The confirmation checkbox is not ceremony: the phrase is
 * the wallet, and there is no recovery for a phrase nobody wrote down.
 */
class WalletSetupView(context: Context) : LinearLayout(context) {

    /** Fired once a wallet exists, so the tour can enable its Next button. */
    var onWalletReady: (() -> Unit)? = null

    private val d = { value: Int -> IosUi.dp(context, value.toFloat()) }

    init {
        orientation = VERTICAL
        render()
    }

    private fun render() {
        removeAllViews()
        if (WalletVault.isInitialized()) {
            readyState()
        } else {
            setupState()
        }
    }

    private fun setupState() {
        addView(IosUi.filledButton(context, "Create a new wallet").apply {
            setOnClickListener { create() }
        })

        addView(IosUi.filledButton(context, "Restore an existing wallet").apply {
            setOnClickListener { showRestoreField() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(10)
        })

        addView(TextView(context).apply {
            text = "If you already use Prism on another device, restore that wallet instead of " +
                "creating one: devices share encrypted data only when they hold the SAME phrase."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, d(10), 0, 0)
        })
    }

    private fun create() {
        when (val outcome = WalletVault.create()) {
            is WalletVault.Outcome.Created -> showPhrase(outcome.phrase, created = true)
            is WalletVault.Outcome.Failed -> toast(outcome.reason)
        }
    }

    private fun showRestoreField() {
        removeAllViews()

        val field = EditText(context).apply {
            hint = "Your recovery phrase, words separated by spaces"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
        }
        addView(field)

        addView(IosUi.filledButton(context, "Restore").apply {
            setOnClickListener {
                val phrase = field.text.toString()
                if (phrase.isBlank()) {
                    toast("Enter the phrase first.")
                    return@setOnClickListener
                }
                when (val outcome = WalletVault.import(phrase)) {
                    is WalletVault.Outcome.Created -> {
                        toast("Wallet restored")
                        render()
                        onWalletReady?.invoke()
                    }
                    is WalletVault.Outcome.Failed -> toast(outcome.reason)
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(10)
        })

        addView(IosUi.hairline(context).apply {})

        addView(TextView(context).apply {
            text = "Any BIP-39 phrase of ${Bip39.MIN_IMPORT_WORDS} words or more, including the " +
                "12- and 24-word phrases other wallets use."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, d(10), 0, 0)
        })
    }

    private fun showPhrase(phrase: List<String>, created: Boolean) {
        removeAllViews()

        addView(TextView(context).apply {
            text = "Write these ${phrase.size} words down"
            textSize = 17f
            setTextColor(IosUi.label(context))
        })

        addView(TextView(context).apply {
            text = "This phrase IS the wallet. Anyone who reads it can take every coin in it, and " +
                "it is also the key to your clipboard sharing and your P2P sites. Keep it offline " +
                "and never paste it into a message."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, d(6), 0, d(10))
        })

        val words = TextView(context).apply {
            text = phrase.mapIndexed { index, word -> "${index + 1}. $word" }.joinToString("\n")
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.label(context))
            setTextIsSelectable(true)
            setPadding(d(12), d(12), d(12), d(12))
        }
        val scroll = ScrollView(context).apply { addView(words) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, d(220)))

        val confirmed = CheckBox(context).apply {
            text = "I have written the phrase down and stored it somewhere safe"
            textSize = 13f
            setTextColor(IosUi.label(context))
        }
        addView(confirmed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(8)
        })

        val finish = IosUi.filledButton(context, "Finish wallet setup").apply {
            isEnabled = false
            alpha = 0.45f
        }
        confirmed.setOnCheckedChangeListener { _, checked ->
            finish.isEnabled = checked
            finish.alpha = if (checked) 1f else 0.45f
        }
        finish.setOnClickListener {
            if (!confirmed.isChecked) return@setOnClickListener
            toast(if (created) "Wallet created" else "Wallet imported")
            render()
            onWalletReady?.invoke()
        }
        addView(finish, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(10)
        })
    }

    private fun readyState() {
        addView(TextView(context).apply {
            text = "✓ Wallet ready"
            textSize = 17f
            setTextColor(IosUi.accent(context))
        })

        addView(TextView(context).apply {
            text = "The phrase on this device is the key to encrypted sharing between your " +
                "devices. You can see the phrase, back the wallet up, or add coins from the " +
                "Wallet page."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, d(6), 0, 0)
        })

        com.prism.core.DnsSignature.publicKeyHex()?.let { key ->
            addView(TextView(context).apply {
                text = "Mesh identity: ${key.take(16)}…"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, d(8), 0, 0)
            })
        }

        addView(TrustedWalletNote(context))
    }

    /** One sentence about what the phrase is now doing, shown once the wallet exists. */
    private class TrustedWalletNote(context: Context) : TextView(context) {
        init {
            text = "Your clipboard and P2P site identity are derived from this phrase, so a device " +
                "paired with the same wallet can read what this one shares — and a device with a " +
                "different phrase cannot."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
