package com.prism.launcher.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.PrismMeshService

/**
 * Forwards an incoming text to this user's other devices. The sending half of PHASE 39.
 *
 * ## Why a receiver and not a poll
 *
 * A text is an event and Android delivers it as one. Polling `content://sms` would mean a job that runs
 * on a schedule and relays messages minutes late, which for the one thing people expect to be instant is
 * the wrong trade — and it would burn battery discovering nothing most of the time.
 *
 * ## Why this does not need to be the default SMS app
 *
 * `SMS_RECEIVED_ACTION` is a broadcast any app holding RECEIVE_SMS can observe, and Prism already holds
 * it. Becoming the default SMS handler is a much larger commitment — it would make Prism responsible for
 * storing and sending every message on the phone — and relaying does not need it.
 *
 * ## Off by default
 *
 * Relaying texts to other machines is not something to opt anyone into silently, even encrypted and even
 * to their own devices. It is a setting, and the receiver returns immediately when it is off, so an
 * install that has never turned it on does no work per message.
 */
class SmsRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PrismSettings.getSmsRelayEnabled()) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull() ?: return
        if (messages.isEmpty()) return

        // MULTIPART TEXTS ARRIVE AS SEVERAL PDUs and are one message. Concatenating the bodies in the
        // order the platform hands them over is what the messaging app itself does; relaying each part
        // separately would deliver a long text as three fragments with no way to reassemble them.
        val body = messages.mapNotNull { it.displayMessageBody }.joinToString("")
        if (body.isBlank()) return

        val address = messages.firstOrNull()?.displayOriginatingAddress.orEmpty().ifBlank { "unknown" }
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val relayed = SmsRelay.Relayed(
            address = address,
            body = body,
            receivedAt = timestamp,
            threadKey = address,
            fromDevice = android.os.Build.MODEL.orEmpty(),
        )

        val sealed = SmsRelay.seal(listOf(relayed), android.os.Build.MODEL.orEmpty())
        if (sealed == null) {
            // No wallet means no key, and sending it in the clear is the one thing this must never do.
            PrismLogger.logWarning(
                TAG,
                "Relay is on but there is no wallet to derive a key from; the text was not relayed",
            )
            return
        }

        // Through the real mesh service, which knows the peers and reaches them inside the tunnel. The
        // broadcast fallback in SmsRelay is for platforms with no mesh service -- not for this one.
        runCatching {
            PrismMeshService.broadcastToOthers(SmsRelay.OPCODE_SMS, SmsRelay.payloadString(sealed))
            PrismLogger.logInfo(TAG, "Relayed a text from $address to the mesh")
        }.onFailure { PrismLogger.logError(TAG, "Could not relay a text", it) }
    }

    private companion object {
        const val TAG = "PrismSmsRelay"
    }
}
