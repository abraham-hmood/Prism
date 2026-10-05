package com.prism.launcher.trusted

import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.nora.IosUi

/**
 * The prompt the RECEIVING device shows. Step 3 of the pairing, and the step that makes it safe.
 *
 * ## Why this has to be on the receiver
 *
 * Because the receiver is the one that will store the data. A sender deciding what another machine keeps
 * would make the other machine's owner a bystander to a decision about their own device — and if that
 * device is shared, or belongs to somebody else entirely, the sender has no standing to make it at all.
 *
 * ## Why it appears when the app opens rather than as a notification
 *
 * A notification can be swiped away without being read, and the answer to this question should not default
 * to "no, silently" any more than it should default to yes. It is asked when somebody is looking at Prism,
 * which is when they can actually judge whether they recognise the device asking. An unanswered offer stays
 * pending in memory and is re-sent by the far side, so dismissing it is safe and repeatable.
 *
 * ## Why the kinds are individually checkable here too
 *
 * The receiver may accept a SUBSET. Agreeing to a shared clipboard is not agreeing to receive somebody's
 * text messages, and a single Accept button would make the two the same decision.
 */
object TrustedDeviceOffers {

    /**
     * Shows the prompt for one offer.
     *
     * Guarded against showing twice for the same device: [showing] holds the fingerprint currently on
     * screen, because both the settings activity and the app-open hook call this and a user would otherwise
     * get two identical dialogs stacked.
     */
    @Volatile private var showing: String? = null

    fun prompt(activity: Activity, offer: TrustedDevices.PendingOffer, onAnswered: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (showing == offer.fingerprint) return
        showing = offer.fingerprint

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(activity, 4f)
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(activity).apply {
            text = "${offer.name} wants to automatically send this device:"
            textSize = 14f
            setTextColor(IosUi.label(activity))
        })

        val boxes = offer.kinds.map { kind ->
            val box = CheckBox(activity).apply {
                text = kind.label
                textSize = 15f
                // Pre-ticked, because the user opened a dialog they are about to answer and the common
                // case is accepting what was asked. Unticking is one tap; the decision is still theirs
                // and nothing is accepted without pressing the button.
                isChecked = true
                setTextColor(IosUi.label(activity))
            }
            column.addView(box)
            column.addView(TextView(activity).apply {
                text = "    ${kind.detail}"
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(activity))
            })
            kind to box
        }

        // The code field, when the offering device is showing one. Absent for an offer from a build
        // that predates codes -- see PendingOffer.needsCode for why that case is allowed rather than
        // refused.
        val codeField = if (!offer.needsCode) null else android.widget.EditText(activity).apply {
            hint = "Code shown on ${offer.name}"
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Autocorrect would "fix" a random string into a word, and the keyboard's automatic capital
            // would change a lower-case first character. Both silently break a code that was typed right.
            setTextColor(IosUi.label(activity))
        }
        if (codeField != null) {
            column.addView(TextView(activity).apply {
                text = "\n${offer.name} is showing a short code. Type it here."
                textSize = 13f
                setTextColor(IosUi.label(activity))
            })
            column.addView(codeField)
            column.addView(TextView(activity).apply {
                text = "It is never sent over the network by either device, so typing it correctly is " +
                    "what proves you can see that screen."
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(activity))
            })
        }

        column.addView(TextView(activity).apply {
            text = "\nFrom ${offer.platform.ifBlank { "an unknown device" }} at ${offer.ip}.\n\n" +
                "Only accept this if you recognise the device and it is yours. Anything you accept is " +
                "shared in BOTH directions from then on — it arrives here, and this device sends the " +
                "same back — until you stop trusting it in Settings.\n\n" +
                "It is encrypted with your recovery phrase, so it only opens here if that device holds " +
                "the same phrase — other devices on the meshnet carry the packets and cannot read them."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(activity))
        })

        PrismDialogFactory.show(
            activity,
            "Receive data from ${offer.name}?",
            message = "",
            customView = ScrollView(activity).apply { addView(column) },
            positiveText = "Accept",
            negativeText = "No",
            onPositive = {
                val accepted = boxes.filter { it.second.isChecked }.map { it.first }.toSet()
                val typed = codeField?.text?.toString()?.trim()
                val ok = TrustedDevices.respondToOffer(offer.fingerprint, accepted, typed)
                showing = null
                if (!ok && accepted.isNotEmpty()) {
                    // The offer is still pending, so this is recoverable: say what went wrong and put
                    // the dialog back rather than leaving somebody holding a code and no box.
                    android.widget.Toast.makeText(
                        activity,
                        "That code was not accepted. Check " + offer.name + "'s screen and try again.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    activity.window.decorView.post { promptIfPending(activity) }
                }
                onAnswered()
            },
            onNegative = {
                // Answered with nothing accepted, rather than left pending. A declined offer that stayed
                // pending would be re-prompted on every app open, which trains people to tap through it.
                TrustedDevices.respondToOffer(offer.fingerprint, emptySet())
                showing = null
                onAnswered()
            },
        )
    }

    /**
     * Shows the oldest unanswered offer, if any. Called when the launcher becomes visible.
     *
     * One at a time. Two devices offering at once is rare and stacking dialogs is worse than asking twice —
     * the second is shown the next time the app is opened.
     */
    fun promptIfPending(activity: Activity) {
        val offer = TrustedDevices.pendingOffers().firstOrNull() ?: return
        prompt(activity, offer)
    }
}
