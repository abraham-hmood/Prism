package com.prism.launcher.lock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.nora.IosUi

/**
 * One contact, and the decision to make them an emergency contact.
 *
 * ## Why that decision is gated
 *
 * An emergency contact receives the user's location when the duress code is used. Adding one
 * silently would mean anyone who picked up an unlocked phone could redirect that alert to
 * themselves -- which is precisely the person the feature exists to protect against.
 *
 * So it asks for proof it is the owner: the fingerprint where there is a sensor, and otherwise the
 * duress credential.
 *
 * ## One note on using the duress code here
 *
 * It is the right choice for the reason it was asked for -- it is the secret least likely to have
 * been shoulder-surfed, so it proves ownership better than the everyday unlock does. The cost is
 * that it trains the user to type it in a calm moment, and a code you have typed casually is one you
 * might type reflexively. Verifying it here never triggers the alert, so nothing fires by accident;
 * the residual risk is only to the habit, not to the mechanism.
 */
class ContactDetailActivity : FragmentActivity() {

    private val content by lazy { LinearLayout(this) }

    private val contactName by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val contactNumber by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val contactId by lazy { intent.getStringExtra(EXTRA_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@ContactDetailActivity))
        }
        content.orientation = LinearLayout.VERTICAL
        val pad = IosUi.dp(this, 20f)
        content.setPadding(pad, pad, pad, pad)
        root.addView(content)
        setContentView(root)

        render()
    }

    private fun render() {
        content.removeAllViews()

        content.addView(TextView(this).apply {
            text = contactName
            textSize = 26f
            setTextColor(IosUi.label(this@ContactDetailActivity))
        })

        val card = IosUi.card(this)
        numbersFor(contactId).ifEmpty { listOf(contactNumber) }.forEach { number ->
            card.addView(TextView(this).apply {
                text = number
                textSize = 16f
                setTextColor(IosUi.label(this@ContactDetailActivity))
                setPadding(0, IosUi.dp(this@ContactDetailActivity, 6f), 0, IosUi.dp(this@ContactDetailActivity, 6f))
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                    }
                }
            })
        }
        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@ContactDetailActivity, 14f) })

        val already = EmergencyContacts.isEmergency(this, contactNumber)

        content.addView(
            if (already) {
                IosUi.tintedButton(this, "Remove as an emergency contact", IosUi.destructive(this)).apply {
                    setOnClickListener { confirmThen { removeEmergency() } }
                }
            } else {
                IosUi.filledButton(this, "Make an emergency contact").apply {
                    setOnClickListener { confirmThen { addEmergency() } }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(this@ContactDetailActivity, 18f) }
        )

        content.addView(IosUi.sectionFooter(
            this,
            if (already) {
                "This person is sent your location when you unlock with your emergency code, and " +
                    "appears on the medical card on your lock screen."
            } else {
                "An emergency contact is sent your location when you unlock with your emergency " +
                    "code, and is shown on the medical card on your lock screen. Changing this " +
                    "needs your fingerprint or your emergency code."
            }
        ))
    }

    // ── Consent ────────────────────────────────────────────────────────────

    /**
     * Proves it is the owner, then runs [action].
     *
     * Fingerprint where there is one, the duress credential otherwise. If neither exists -- no
     * sensor and no duress code set -- the change is refused rather than waved through, and says
     * what to do about it. Waving it through would mean a phone left unlocked on a table is a phone
     * whose emergency alerts can be redirected.
     */
    private fun confirmThen(action: () -> Unit) {
        if (LockGate.biometricAvailable(this)) {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        action()
                    }
                }
            )
            runCatching {
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Confirm it is you")
                        .setSubtitle("Changing who receives your emergency alerts")
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build()
                )
            }.onFailure { askForDuress(action) }
            return
        }
        askForDuress(action)
    }

    private fun askForDuress(action: () -> Unit) {
        if (!LockStore.hasDuress(this)) {
            toast(
                "This needs your fingerprint or your emergency code, and this device has neither " +
                    "set up. Add an emergency code in Settings > Medical first."
            )
            return
        }

        val mechanism = LockStore.mechanism(this)
        if (mechanism == LockStore.Mechanism.PATTERN) {
            askForDuressPattern(action)
            return
        }

        val field = EditText(this).apply {
            inputType = if (mechanism == LockStore.Mechanism.PIN) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            hint = "Emergency ${mechanism?.label?.lowercase() ?: "code"}"
            val pad = IosUi.dp(this@ContactDetailActivity, 14f)
            setPadding(pad, pad, pad, pad)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm it is you")
            .setMessage("Enter your emergency code. Nothing is sent — this only confirms who you are.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm") { _, _ ->
                // DURESS ONLY. Checking it here never fires the alert -- verify() reports which
                // credential was entered and this path acts on nothing else.
                if (LockStore.verify(this, field.text.toString()) == LockStore.Outcome.DURESS) {
                    action()
                } else {
                    toast("That was not your emergency code")
                }
            }
            .show()
    }

    private fun askForDuressPattern(action: () -> Unit) {
        val view = PatternView(this)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Draw your emergency pattern")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()

        view.minimumHeight = IosUi.dp(this, 300f)
        view.onPattern = { drawn ->
            if (LockStore.verify(this, drawn) == LockStore.Outcome.DURESS) {
                dialog.dismiss()
                action()
            } else {
                view.errorState = true
                view.postDelayed({ view.reset() }, 600)
            }
        }
        dialog.show()
    }

    // ── The change ─────────────────────────────────────────────────────────

    private fun addEmergency() {
        EmergencyContacts.add(this, contactName, contactNumber)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(
                "$contactName added. Prism cannot send SMS yet, so grant that permission or the " +
                    "alert will have no way out."
            )
        } else {
            toast("$contactName is now an emergency contact")
        }
        render()
    }

    private fun removeEmergency() {
        EmergencyContacts.remove(this, contactNumber)
        toast("$contactName is no longer an emergency contact")
        render()
    }

    /** Every number this contact has, so the detail view is not limited to the one in the list. */
    private fun numbersFor(id: String): List<String> = runCatching {
        if (id.isBlank()) return emptyList()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val out = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) cursor.getString(column)?.let(out::add)
        }
        out.distinct()
    }.getOrDefault(emptyList())

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "contact_id"
        const val EXTRA_NAME = "contact_name"
        const val EXTRA_NUMBER = "contact_number"
    }
}
