package com.prism.launcher.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.PrismMeshService

/**
 * The Android half of mesh calling: the plan probe, the audio device, the incoming-call surface and
 * the carrier call Prism places itself.
 *
 * ## Placing a carrier call without the default phone app
 *
 * Prism asks Telecom to place the call on the user's own SIM, from Prism's dialer, and then shows
 * its own call screen. What it does NOT do is launch `ACTION_DIAL`/`ACTION_CALL` as an intent for
 * another app to handle: the number never leaves Prism's process, and the system's dialer is never
 * the thing the user typed into.
 *
 * Android does still draw its own in-call notification for a carrier call, because the radio belongs
 * to the platform's Telecom stack and a third-party app cannot own that audio path. For a mesh call
 * there is no carrier at all, so the whole call -- signalling, audio, encryption, UI -- is Prism's.
 * The call screen says which kind of call is on screen rather than implying both are the same.
 *
 * ## Why the plan is read from the SIM
 *
 * "Active phone plan" is the carrier subscription, not a Prism purchase. A phone with no SIM, a SIM
 * with no service, or a device in airplane mode has no plan and the dialer says so instead of
 * pretending a call could be placed.
 */
object PrismCalls {

    private const val TAG = "PrismCalls"
    const val CHANNEL_INCOMING = "prism_calls"
    private const val NOTIFICATION_RINGING = 4711

    @Volatile private var audio: CallAudio? = null
    @Volatile private var ringtone: Ringtone? = null
    @Volatile private var installed = false

    // ── Install ────────────────────────────────────────────────────────────

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext

        // THE PROBE WAS NEVER INSTALLED, and every plan check therefore answered "no plan" with
        // the object's own fallback -- the dialer, Meshvoice and Settings all disagreed with the SIM
        // in the phone. Installing it first makes the one telephony answer the one Prism reads, even
        // if the audio device fails to open later in install.
        PrismPhonePlan.probe = { planStatus(app) }
        PrismPhonePlan.refresh()

        val device = CallAudio(app)
        audio = device
        MeshCalls.audio = device
        MeshCalls.phonePlan = { planStatus(app).active }
        MeshCalls.pstnDialer = { number -> placeCarrierCall(app, number).isSuccess }

        // An invite has to reach the user even when Prism is in the background, which is most of the
        // time. A full-screen notification is the only route a non-phone app has to a call screen.
        MeshCalls.onIncoming = { call -> announceIncoming(app, call) }

        ensureChannel(app)
        PrismLogger.logInfo(TAG, "Mesh calls installed")
    }

    fun device(): CallAudio? = audio

    // ── The plan ───────────────────────────────────────────────────────────

    /**
     * Asks the telephony stack what plan this device is on.
     *
     * The default voice subscription rather than the first one: a dual-SIM phone with a data-only
     * eSIM in slot 2 and the user's number in slot 1 would otherwise report the wrong carrier, and
     * `line1Number` is frequently blank because carriers are not obliged to publish it. A blank
     * number is not a failure -- the plan is still usable, the dialer just cannot show "calling from".
     */
    fun planStatus(context: Context): PrismPhonePlan.Status {
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?: return PrismPhonePlan.Status(false, reason = "This device has no telephony service.")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return PrismPhonePlan.Status(
                false,
                reason = "Prism needs phone access to see whether there is a plan to call from.",
            )
        }

        if (tm.simState != TelephonyManager.SIM_STATE_READY) {
            return PrismPhonePlan.Status(
                false,
                reason = when (tm.simState) {
                    TelephonyManager.SIM_STATE_ABSENT -> "No SIM card, so there is no plan to call from."
                    TelephonyManager.SIM_STATE_PIN_REQUIRED,
                    TelephonyManager.SIM_STATE_PUK_REQUIRED ->
                        "The SIM is locked. Unlock it to use your plan."
                    else -> "The SIM is not ready yet."
                },
            )
        }

        val subscriptions = runCatching {
            SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not list subscriptions: ${it.message}")
            emptyList()
        }
        if (subscriptions.isEmpty()) {
            return PrismPhonePlan.Status(false, reason = "The SIM is not subscribed to a plan.")
        }

        val defaultVoiceId = runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val primary = subscriptions.firstOrNull { it.subscriptionId == defaultVoiceId }
            ?: subscriptions.first()

        val number = runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
            ) tm.line1Number else null
        }.getOrNull().orEmpty()

        return PrismPhonePlan.Status(
            active = true,
            number = number,
            carrier = primary.carrierName?.toString().orEmpty(),
        )
    }

    // ── Carrier calls ──────────────────────────────────────────────────────

    sealed class DialResult {
        data object Placed : DialResult()
        data class Refused(val reason: String) : DialResult()
        val isSuccess: Boolean get() = this is Placed
    }

    /**
     * Asks Telecom to place [number] on the user's SIM.
     *
     * The phone-account handle is pinned to a non-Prism account, so the call goes out on the carrier
     * plan rather than being routed to a self-managed account this app does not have. If the platform
     * offers no handle the call is still placed -- Telecom picks the default -- because refusing to
     * dial because a handle could not be named would be worse than the default it would have chosen.
     */
    fun placeCarrierCall(context: Context, rawNumber: String): DialResult {
        val number = normalizeNumber(rawNumber)
        if (number.isEmpty()) return DialResult.Refused("That is not a number Prism can dial.")
        if (!planStatus(context).active) {
            return DialResult.Refused("There is no phone plan to call from.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return DialResult.Refused("Prism needs permission to place calls.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return DialResult.Refused("This device has no telecom service.")

        val extras = Bundle()
        // TelecomManager.putExtras is static: it writes the handle under the key placeCall reads.
        // Picking the first account that is not Prism's own avoids asking Telecom to route the
        // call back into this app, which is not a carrier and cannot carry it.
        val handle = runCatching {
            telecom.callCapablePhoneAccounts.firstOrNull {
                it.componentName?.packageName != context.packageName
            }
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not pick a SIM for the call: ${it.message}")
        }.getOrNull()
        if (handle != null) {
            // The public key, not TelecomManager.putExtras -- that helper is a hidden system API.
            runCatching {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not attach the SIM to the call: ${it.message}")
            }
        }
        // Speaker off at the start: the earpiece is what a phone call expects, and the call screen
        // offers the toggle for the times it is not.
        extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)

        return runCatching {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            PrismLogger.logInfo(TAG, "Carrier call placed through Prism")
            DialResult.Placed
        }.getOrElse { error ->
            PrismLogger.logWarning(TAG, "Carrier call refused: ${error.message}")
            DialResult.Refused("The call could not be placed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Ends the carrier call Prism placed, where the platform allows it. */
    fun endCarrierCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
                true
            } else false
        }.getOrDefault(false)
    }

    /**
     * Strips a number to something Telecom accepts, keeping a leading `+` and `*`/`#`.
     *
     * Deliberately permissive: `*#06#` and a `+44` number are both dialable, and rejecting either
     * because it is not a plain run of digits would break the keypad the user is looking at.
     */
    fun normalizeNumber(raw: String): String =
        raw.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.let { cleaned ->
            // A '+' anywhere but the front would make an invalid URI; keep the first only.
            if (cleaned.startsWith("+")) "+" + cleaned.drop(1).replace("+", "")
            else cleaned.replace("+", "")
        }

    // ── Incoming calls ─────────────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_INCOMING) != null) return
        val channel = NotificationChannel(
            CHANNEL_INCOMING,
            "Mesh calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming calls from your trusted devices."
            enableVibration(true)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun announceIncoming(context: Context, call: MeshCalls.Call) {
        ensureChannel(context)
        // The caller's display name is the only thing in the notification. Not the address, not the
        // fingerprint: a lock screen showing who is calling is expected, one showing where they are
        // on the mesh is not.
        val intent = CallActivity.incomingIntent(context, call.peerIp, call.peerName, call.trusted)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_RINGING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(call.peerName)
            .setContentText(
                if (call.trusted) "Incoming mesh call" else "Incoming unencrypted mesh call"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .addAction(0, "Answer", pending)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_RINGING, notification)
        }.onFailure { PrismLogger.logWarning(TAG, "Could not show the incoming call: ${it.message}") }
    }

    fun clearIncomingNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_RINGING)
        }
    }

    // ── Finding a device ───────────────────────────────────────────────────

    /**
     * Rings this device at alarm volume so its owner can find it.
     *
     * The alarm stream, not the ringtone stream: a phone on silent still plays alarms, and a phone
     * left in a bag with the ringer down is exactly the phone this has to find.
     */
    fun playFindSound(context: Context, seconds: Int = 12) {
        stopFindSound()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val tone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return
        runCatching {
            val manager = context.getSystemService(AudioManager::class.java)
            manager?.setStreamVolume(
                AudioManager.STREAM_ALARM,
                (manager.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8f).toInt(),
                0,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) tone.audioAttributes =
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            tone.play()
        }.onFailure { PrismLogger.logWarning(TAG, "Could not play the find sound: ${it.message}") }
        ringtone = tone
        Thread({
            runCatching { Thread.sleep(seconds * 1000L) }
            stopFindSound()
        }, "prism-find-sound").apply { isDaemon = true; start() }
    }

    fun stopFindSound() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    fun openDialer(context: Context, meshTab: Boolean = false) {
        context.startActivity(
            Intent(context, DialerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DialerActivity.EXTRA_TAB, if (meshTab) DialerActivity.TAB_MESH else DialerActivity.TAB_DIAL)
        )
    }

    fun openMeshvoice(context: Context, meshTab: Boolean = false) {
        context.startActivity(
            Intent(context, MeshvoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MeshvoiceActivity.EXTRA_TAB, if (meshTab) MeshvoiceActivity.TAB_MESH else MeshvoiceActivity.TAB_CARRIER)
        )
    }

    /** Whether the mesh can carry a call at all: connected, with at least one peer. */
    fun meshReady(): Boolean = runCatching {
        PrismMeshService.isOnMesh() && PrismMeshService.getPeerCount() > 0
    }.getOrDefault(false)

    fun meshDescription(): String = when {
        !PrismMeshService.isOnMesh() -> "Not on a meshnet"
        PrismMeshService.getPeerCount() <= 0 -> "On a meshnet with no other devices"
        else -> "On a meshnet with ${PrismMeshService.getPeerCount()} other device(s)"
    }

    /** Sharing of this device's position is what both the map and duress-adjacent features read. */
    fun locationSharingOn(): Boolean = PrismSettings.getLocationSharingEnabled()
}
