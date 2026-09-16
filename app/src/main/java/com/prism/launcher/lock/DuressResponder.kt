package com.prism.launcher.lock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings

/**
 * What happens when the duress credential is entered.
 *
 * ## The rule everything here follows
 *
 * **Nothing observable may differ from a normal unlock.** No toast, no dialog, no vibration, no
 * delay, no notification, no sound. Someone is watching the screen -- that is the only situation in
 * which this code ever runs -- and any visible difference at all converts a safety feature into a
 * provocation.
 *
 * So: the alert is sent from a background thread, the phone unlocks at exactly the usual speed, and
 * what it unlocks into is a launcher that looks ordinary and holds nothing (see
 * [DummyLauncherActivity]).
 *
 * ## What it sends, and what it does not
 *
 * A short message with a coarse location and a maps link, by SMS, to the emergency contacts. SMS
 * rather than anything cleverer because it is the one channel that works on a locked-down network,
 * needs no account on the other end, and arrives on a phone that is not running Prism.
 *
 * It does not call anyone, does not contact emergency services, and does not record audio. Calling
 * makes noise. Contacting emergency services on an automated trigger is a decision with real
 * consequences for the user that an app should not take on a PIN entry, and a false one wastes a
 * response somebody else needed.
 *
 * ## The location it sends
 *
 * Last known, not a fresh fix. Requesting a new fix wakes the GPS, takes seconds, and can show in
 * the status bar -- all three break the rule above. A last-known position minutes old is enough to
 * say which building someone is in, which is what the message is for.
 */
object DuressResponder {

    private const val TAG = "PrismLock"

    /**
     * Fires the alert and puts the launcher into its decoy state.
     *
     * Returns immediately. Everything slow happens on a daemon thread, because this is called from
     * the unlock path and the unlock must not hesitate.
     */
    fun trigger(context: Context) {
        val app = context.applicationContext
        PrismSettings.setDuressActive(true)

        Thread({
            runCatching { sendAlerts(app) }
                .onFailure {
                    // Logged and swallowed. A failure here must never surface -- there is no way to
                    // report it that the person standing over the user would not see.
                    PrismLogger.logWarning(TAG, "Duress alert could not be sent: ${it.message}")
                }
        }, "duress-alert").apply { isDaemon = true; start() }
    }

    /** Leaves the decoy state. Called from Settings, behind the normal credential. */
    fun standDown(context: Context) {
        PrismSettings.setDuressActive(false)
    }

    val isActive: Boolean get() = PrismSettings.getDuressActive()

    // ── The message ────────────────────────────────────────────────────────

    private fun sendAlerts(context: Context) {
        val contacts = EmergencyContacts.all(context)
        if (contacts.isEmpty()) {
            PrismLogger.logWarning(TAG, "Duress entered with no emergency contacts set")
            return
        }

        val body = composeMessage(context)
        val manager = smsManager(context) ?: run {
            PrismLogger.logWarning(TAG, "No SMS service on this device")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            PrismLogger.logWarning(TAG, "Duress alert not sent: SMS permission was never granted")
            return
        }

        contacts.forEach { contact ->
            runCatching {
                // Split, because a location line plus a maps URL runs past 160 characters and an
                // over-long message is silently dropped by some carriers rather than segmented.
                val parts = manager.divideMessage(body)
                manager.sendMultipartTextMessage(contact.number, null, parts, null, null)
                PrismLogger.logInfo(TAG, "Duress alert sent to ${contact.name}")
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not message ${contact.name}: ${it.message}")
            }
        }
    }

    private fun composeMessage(context: Context): String {
        val location = lastKnownLocation(context)
        val name = PrismSettings.getLykeUserName().ifBlank { "Someone" }

        return buildString {
            append(name)
            append(" triggered an emergency alert from their phone.")
            if (location != null) {
                val lat = "%.5f".format(location.latitude)
                val lon = "%.5f".format(location.longitude)
                append("\n\nLocation: ").append(lat).append(", ").append(lon)
                append("\nhttps://maps.google.com/?q=").append(lat).append(',').append(lon)
                val ageMinutes = (System.currentTimeMillis() - location.time) / 60_000
                if (ageMinutes > 1) append("\n(position is about ").append(ageMinutes).append(" minutes old)")
            } else {
                append("\n\nNo location was available.")
            }
            append("\n\nSent automatically by Prism.")
        }
    }

    /**
     * The most recent fix any provider already has.
     *
     * Deliberately passive. See the class note: asking for a fresh fix is slow and visible, and this
     * runs in the one situation where neither is acceptable.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }.getOrNull()
}
