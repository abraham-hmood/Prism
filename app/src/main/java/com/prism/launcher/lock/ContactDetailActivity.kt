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
import androidx.activity.result.contract.ActivityResultContracts
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

    /** The name this contact knows the user by. Required before they can be added. */
    private var knownAsField: EditText? = null

    /** True while an add is waiting for the SMS/location permission dialog. */
    private var awaitingPermissions = false

    /**
     * Asked for at the moment the contact is added, not at first launch.
     *
     * BOTH the alert and its location need runtime grants. Prism used to declare them in the
     * manifest and never ask, so every duress alert went out as "No location was available" -- the
     * permission was present in the APK and absent on the device.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!awaitingPermissions) return@registerForActivityResult
        awaitingPermissions = false
        addEmergencyNow()
    }

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

        val existing = EmergencyContacts.all().firstOrNull {
            it.number.filter(Char::isDigit).takeLast(9) == contactNumber.filter(Char::isDigit).takeLast(9)
        }
        val already = existing != null

        // THE NAME FIELD IS PART OF SETTING UP A CONTACT, NOT A SECOND STEP. The alert cannot be
        // useful without it, so it is asked for here and refused when blank.
        if (!already || existing?.knownAs.isNullOrBlank()) {
            knownAsField = EditText(this).apply {
                hint = "Your name they know (required)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(existing?.knownAs.orEmpty())
                setSingleLine(true)
                setTextColor(IosUi.label(this@ContactDetailActivity))
                setHintTextColor(IosUi.tertiaryLabel(this@ContactDetailActivity))
            }
            content.addView(knownAsField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(this@ContactDetailActivity, 16f) })
            content.addView(TextView(this).apply {
                text = "The emergency alert uses this name so the person knows who needs help. " +
                    "It is sent together with your current location."
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(this@ContactDetailActivity))
                setPadding(0, IosUi.dp(this@ContactDetailActivity, 6f), 0, 0)
            })
        }

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
        if (!LockStore.hasDuress()) {
            toast(
                "This needs your fingerprint or your emergency code, and this device has neither " +
                    "set up. Add an emergency code in Settings > Medical first."
            )
            return
        }

        val mechanism = LockStore.mechanism()
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

        com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Confirm it is you")
            .setMessage("Enter your emergency code. Nothing is sent — this only confirms who you are.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm") { _, _ ->
                // DURESS ONLY. Checking it here never fires the alert -- verify() reports which
                // credential was entered and this path acts on nothing else.
                if (LockStore.verify(field.text.toString()) == LockStore.Outcome.DURESS) {
                    action()
                } else {
                    toast("That was not your emergency code")
                }
            }
            .show()
    }

    private fun askForDuressPattern(action: () -> Unit) {
        val view = PatternView(this)
        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Draw your emergency pattern")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()

        view.minimumHeight = IosUi.dp(this, 300f)
        view.onPattern = { drawn ->
            if (LockStore.verify(drawn) == LockStore.Outcome.DURESS) {
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
        val knownAs = knownAsField?.text?.toString()?.trim().orEmpty()
        if (knownAs.isBlank()) {
            toast("Enter the name this person knows you by — the alert uses it.")
            knownAsField?.requestFocus()
            return
        }
        if (contactNumber.isBlank()) {
            toast("This contact has no phone number, so an alert has nowhere to go.")
            return
        }

        val missing = EMERGENCY_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            awaitingPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        addEmergencyNow()
    }

    private fun addEmergencyNow() {
        val knownAs = knownAsField?.text?.toString()?.trim().orEmpty()
        if (knownAs.isBlank()) {
            toast("Enter the name this person knows you by — the alert uses it.")
            return
        }
        val added = EmergencyContacts.add(contactName, contactNumber, knownAs)
        if (!added) {
            toast("Could not add this contact. Check the number and the name.")
            return
        }

        val noSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        val noLocation = EMERGENCY_LOCATION_PERMISSIONS.none {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        toast(
            when {
                noSms -> "$contactName added, but SMS permission was refused — the alert has no way out."
                noLocation -> "$contactName added, but location permission was refused — the alert will not say where."
                else -> "$contactName is now an emergency contact. They will know you as $knownAs."
            }
        )
        render()
    }

    private fun removeEmergency() {
        EmergencyContacts.remove(contactNumber)
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

        /**
         * What the alert needs to actually arrive and say where.
         *
         * The manifest declared these from the beginning; nothing ever asked for them at runtime,
         * which is why every duress message said "No location was available". Requested here, at
         * the one moment adding a contact can justify a permission dialog.
         */
        val EMERGENCY_LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val EMERGENCY_PERMISSIONS = EMERGENCY_LOCATION_PERMISSIONS + Manifest.permission.SEND_SMS
    }
}
