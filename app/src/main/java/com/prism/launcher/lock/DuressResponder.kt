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
 * A RECENT fix, and a fresh one when there is none. The first version of this sent the last known
 * position only, on the argument that waking the GPS is slow and visible. That argument is wrong
 * for the situation this code exists for: a position that is hours old -- or absent because the
 * phone had not used location all day -- turns the alert into "no location available", which is
 * the single sentence an emergency contact can do nothing with.
 *
 * So a fix under five minutes old is used as-is, and otherwise one current position is requested
 * with a short timeout. The alert is sent on a background thread and the unlock is not delayed by
 * it. The status-bar indicator is a real cost and it is accepted deliberately: a visible alert is
 * enormously better than an alert that names no place.
 *
 * ## The name it sends
 *
 * Each contact carries the name THAT PERSON knows the user by, chosen when the contact was added
 * and required by the form. The message says "Alice triggered an emergency alert", not "Someone
 * did": the recipient cannot act on "someone", and an anonymous alert reads as a wrong number.
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
        val contacts = EmergencyContacts.all()
        if (contacts.isEmpty()) {
            PrismLogger.logWarning(TAG, "Duress entered with no emergency contacts set")
            return
        }

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

        // One fix for every contact. Asking twice would take twice as long and could put two
        // different positions in two alerts about the same moment.
        val location = currentLocation(context)

        contacts.forEach { contact ->
            runCatching {
                val body = composeMessage(contact, location)
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

    private fun composeMessage(contact: EmergencyContacts.Contact, location: Location?): String {
        // The contact's own name for the user, never a generic one. The profile name is a fallback
        // only for a contact saved before the field existed; it is still a name the sender chose
        // to publish, unlike "Someone".
        val name = contact.knownAs.ifBlank {
            PrismSettings.getLykeUserName().ifBlank { "Someone you know" }
        }

        return buildString {
            append(name)
            append(" triggered an emergency alert from their phone.")
            if (location != null) {
                val lat = "%.5f".format(location.latitude)
                val lon = "%.5f".format(location.longitude)
                append("\n\nLocation: ").append(lat).append(", ").append(lon)
                append("\nhttps://www.openstreetmap.org/?mlat=").append(lat)
                    .append("&mlon=").append(lon).append("#map=17/").append(lat).append('/').append(lon)
                val ageMinutes = (System.currentTimeMillis() - location.time) / 60_000
                if (ageMinutes > 1) append("\n(position is about ").append(ageMinutes).append(" minutes old)")
            } else {
                append("\n\nNo location was available — the phone could not get a fix.")
            }
            append("\n\nSent automatically by Prism.")
        }
    }

    /**
     * A position good enough to send: a recent cached fix, else one fresh fix.
     *
     * Blocking, with a bounded wait, and only ever called on the alert thread. Providers are tried
     * GPS first and network second; `getCurrentLocation` is the API-30 replacement for the old
     * single-update listener and is what makes a fresh fix possible without a Looper.
     */
    @SuppressLint("MissingPermission")
    private fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val last = lastKnownLocation(context)
        if (last != null && System.currentTimeMillis() - last.time < FRESH_FIX_MS) return last
        if (last != null && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return last

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false).not()) continue
                val signal = android.os.CancellationSignal()
                val latch = java.util.concurrent.CountDownLatch(1)
                // An AtomicReference rather than a captured `var`: the callback runs on the executor
                // and the alert thread reads the result, so the handoff has to be a real one.
                val fix = java.util.concurrent.atomic.AtomicReference<Location?>(null)
                val delivered = runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        java.util.concurrent.Executor { it.run() },
                    ) { location ->
                        fix.set(location)
                        latch.countDown()
                    }
                    latch.await(FIX_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                }.getOrDefault(false)
                signal.cancel()
                if (delivered) fix.get()?.let { return it }
            }
        }

        // Returning the stale fix is still better than returning nothing: "about 40 minutes old"
        // tells the contact where to start looking, and the alternative is no information at all.
        return last
    }

    private const val FRESH_FIX_MS = 5 * 60 * 1000L
    private const val FIX_TIMEOUT_SECONDS = 8L

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
