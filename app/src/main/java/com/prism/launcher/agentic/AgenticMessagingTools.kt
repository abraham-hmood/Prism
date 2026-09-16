package com.prism.launcher.agentic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Real Android implementation behind [AgenticBuiltinTools.readTextHandler]/`sendTextHandler`/
 * `makeCallHandler` -- wired up in `PrismApp.onCreateMainProcess`, the same pattern
 * [AgenticP2pHost] already uses for the one other builtin tool `:core` cannot reach directly.
 *
 * The `hasActiveCellularLine()` gate itself lives in [com.prism.core.PlatformHost]/`AndroidHost`
 * and is checked by [AgenticBuiltinTools] before any of these run -- nothing here re-checks it,
 * this file only resolves `contact` (a phone number OR a saved contact name) and does the actual
 * SMS/telephony work.
 */
object AgenticMessagingTools {

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun looksLikePhoneNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        val other = s.count { !it.isDigit() && it !in "+-().  " }
        return digits >= 5 && other == 0
    }

    /** [contact] is a raw phone number if it's mostly digits/phone punctuation, otherwise a
     * contact display name looked up via [ContactsContract.CommonDataKinds.Phone]. Returns null
     * if it's a name and either READ_CONTACTS isn't granted or no contact matches. */
    private fun resolveNumber(context: Context, contact: String): String? {
        val trimmed = contact.trim()
        if (trimmed.isEmpty()) return null
        if (looksLikePhoneNumber(trimmed)) return trimmed

        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$trimmed%"), null
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIdx >= 0 && cursor.moveToFirst()) return cursor.getString(numberIdx)
        }
        return null
    }

    suspend fun readMostRecentText(context: Context, contact: String): String = withContext(Dispatchers.IO) {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return@withContext "Error: READ_SMS permission not granted. Grant it in Settings > Apps > Prism > Permissions."
        }
        val number = resolveNumber(context, contact)
            ?: return@withContext "Error: couldn't resolve '$contact' to a phone number or a saved contact."

        try {
            // Matched in-memory via PhoneNumberUtils.compare rather than a SQL WHERE on address --
            // stored addresses and the number a caller supplies are formatted inconsistently
            // (+1 country code, dashes/parens, etc.), and compare() is Android's own answer for
            // "are these the same number" despite that.
            context.contentResolver.query(
                Uri.parse("content://sms/"), arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                var scanned = 0
                while (cursor.moveToNext() && scanned < 2000) {
                    scanned++
                    val address = if (addressIdx >= 0) cursor.getString(addressIdx) else null
                    if (address != null && PhoneNumberUtils.compare(address, number)) {
                        val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                        val when_ = if (dateIdx >= 0) DateFormat.getDateTimeInstance()
                            .format(Date(cursor.getLong(dateIdx))) else "unknown time"
                        return@withContext "Most recent text from $contact ($when_): \"$body\""
                    }
                }
            }
            "No text messages found from $contact."
        } catch (e: Exception) {
            "Error reading text messages: ${e.message}"
        }
    }

    suspend fun sendText(context: Context, contact: String, message: String): String = withContext(Dispatchers.IO) {
        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            return@withContext "Error: SEND_SMS permission not granted. Grant it in Settings > Apps > Prism > Permissions."
        }
        if (message.isBlank()) return@withContext "Error: message is required."
        val number = resolveNumber(context, contact)
            ?: return@withContext "Error: couldn't resolve '$contact' to a phone number or a saved contact."

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(number, null, message, null, null)
            "Text sent to $contact ($number)."
        } catch (e: Exception) {
            "Error sending text to $contact: ${e.message}"
        }
    }

    suspend fun makeCall(context: Context, contact: String): String = withContext(Dispatchers.IO) {
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return@withContext "Error: CALL_PHONE permission not granted. Grant it in Settings > Apps > Prism > Permissions."
        }
        val number = resolveNumber(context, contact)
            ?: return@withContext "Error: couldn't resolve '$contact' to a phone number or a saved contact."

        return@withContext try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Calling $contact ($number)."
        } catch (e: Exception) {
            "Error placing call to $contact: ${e.message}"
        }
    }
}
