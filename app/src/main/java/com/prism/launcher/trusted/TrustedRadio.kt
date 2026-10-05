package com.prism.launcher.trusted

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import com.prism.launcher.messaging.SmsRelay

/**
 * The phone's radio, as the rest of Prism sees it.
 *
 * ## Why this is the only place SmsManager appears
 *
 * A text typed on a PC is sent by the phone, because the phone is the thing with a SIM. That means the
 * phone's build needs one entry point that puts a string on the radio, and everything above it —
 * [TrustedMessages], the messages page on either device — can be written without knowing whether the
 * device it is running on even has a radio.
 *
 * ## Why a long message is split rather than truncated
 *
 * SMS carries 160 characters, or 70 if the text contains anything outside GSM 7-bit — an emoji, a curly
 * quote pasted from a web page. `divideMessage` and `sendMultipartTextMessage` are what every messaging
 * app uses for this, and the alternative is a reply that silently loses its second half.
 *
 * ## What this does NOT do
 *
 * MMS and RCS. Both are real gaps and both are named here rather than papered over. MMS needs a
 * carrier-specific MMSC transaction that Android only exposes through a system-privileged path, and RCS
 * runs through Google's Jibe stack with no public API at all. A picture sent from the PC would have to
 * go out as a link or not at all, and inventing a silent fallback would make a message look sent when it
 * was not. Text is what a non-default SMS app can send, so text is what this claims.
 */
object TrustedRadio {

    private const val TAG = "PrismTrust"

    /** True when this device could actually send. */
    fun available(context: Context): Boolean =
        hasPermission(context) && hasTelephony(context)

    /**
     * Sends one text. Returns null on success, or a sentence describing what stopped it.
     *
     * The string return is deliberate: the caller is usually a mesh receive thread acting for another
     * device, and "it failed" without a reason would be untraceable from the machine that asked.
     */
    fun send(context: Context, address: String, body: String): String? {
        if (address.isBlank() || body.isBlank()) return "Nothing to send."
        if (!hasTelephony(context)) return "This device has no cellular radio."
        if (!hasPermission(context)) {
            return "Prism does not have permission to send texts on this phone."
        }

        return runCatching {
            val manager = smsManager(context)
            val parts = manager.divideMessage(body)
            if (parts.size <= 1) {
                manager.sendTextMessage(address, null, body, null, null)
            } else {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            PrismLogger.logInfo(TAG, "Sent a text to $address (${parts.size} part(s))")
            null
        }.getOrElse { it.message ?: "The radio refused the message." }
    }

    /**
     * Reads the phone's existing conversations, newest first.
     *
     * THE BACKFILL A PAIRING SENDS. Without it a paired PC shows an empty Messages page until somebody
     * texts the phone, which reads as the pairing not having worked rather than as there being nothing
     * yet.
     *
     * Both inbox and sent, because a conversation is both sides. Read through the SMS provider, which is
     * available to any app holding READ_SMS — no default-SMS-app role required.
     */
    fun history(context: Context, limit: Int = TrustedMessages.BACKFILL_LIMIT): List<SmsRelay.Relayed> {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) return emptyList()

        val results = mutableListOf<SmsRelay.Relayed>()
        runCatching {
            context.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.TYPE,
                ),
                null,
                null,
                android.provider.Telephony.Sms.DATE + " DESC LIMIT " + limit,
            )?.use { cursor ->
                val address = cursor.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndex(android.provider.Telephony.Sms.BODY)
                val date = cursor.getColumnIndex(android.provider.Telephony.Sms.DATE)
                val type = cursor.getColumnIndex(android.provider.Telephony.Sms.TYPE)
                while (cursor.moveToNext()) {
                    val text = cursor.getString(body).orEmpty()
                    if (text.isBlank()) continue
                    val who = cursor.getString(address).orEmpty().ifBlank { "unknown" }
                    results += SmsRelay.Relayed(
                        address = who,
                        body = text,
                        receivedAt = cursor.getLong(date),
                        threadKey = who,
                        fromDevice = TrustedDevices.localFingerprint(),
                        // MESSAGE_TYPE_SENT is what the user wrote. Keeping the direction is what makes
                        // the relayed copy a conversation rather than a list of things that arrived.
                        outgoing = cursor.getInt(type) == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT,
                    )
                }
            }
        }.onFailure { PrismLogger.logWarning(TAG, "Could not read the message history: ${it.message}") }

        PrismPlatform.log.info(TAG, "Read ${results.size} messages from this phone")
        return results
    }

    private fun smsManager(context: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun hasPermission(
        context: Context,
        permission: String = Manifest.permission.SEND_SMS,
    ): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}
