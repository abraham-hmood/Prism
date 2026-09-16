package com.prism.launcher.lock

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * What a paramedic needs, readable without unlocking the phone.
 *
 * ## Why this is on the lock screen at all
 *
 * The person this is for is not the owner. It is a stranger kneeling next to someone unconscious,
 * and everything about its design follows from that: it is reachable without a credential, it opens
 * in one tap, and it says the few things that change treatment -- blood type, severe allergies,
 * resuscitation status, who to call.
 *
 * ## The trade it makes
 *
 * Anyone who picks up the phone can read it. That is not a flaw to be mitigated, it is the entire
 * purpose, and the honest framing is that the user is choosing to publish these facts to whoever
 * holds their phone. So it is **off until filled in** -- an empty card is not shown, nothing is
 * pre-populated, and every field is optional. A user who fills in nothing has published nothing.
 *
 * ## DNR
 *
 * Shown because it is the thing most likely to change what happens in the next sixty seconds, and
 * labelled as the user's stated wish rather than as a legal instruction, because a phone screen is
 * not an advance directive and no clinician should be led to believe otherwise.
 */
object MedicalRecord {

    private const val FILE = "lock/medical.json"

    data class Record(
        val bloodType: String,
        val allergies: String,
        val conditions: String,
        val medications: String,
        val dnr: Boolean,
        val notes: String,
        val organDonor: Boolean,
    ) {
        /** Nothing to show means nothing is shown. */
        val isEmpty: Boolean get() =
            bloodType.isBlank() && allergies.isBlank() && conditions.isBlank() &&
                medications.isBlank() && notes.isBlank() && !dnr && !organDonor

        /** The one-line form, for the collapsed card. */
        val summary: String get() = buildString {
            if (bloodType.isNotBlank()) append(bloodType)
            if (allergies.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("allergies")
            }
            if (dnr) {
                if (isNotEmpty()) append(" · ")
                append("DNR")
            }
            if (isEmpty()) append("Medical information")
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE).apply { parentFile?.mkdirs() }

    fun get(context: Context): Record = runCatching {
        val source = file(context)
        if (!source.isFile) return blank()
        val json = JSONObject(source.readText())
        Record(
            bloodType = json.optString("blood"),
            allergies = json.optString("allergies"),
            conditions = json.optString("conditions"),
            medications = json.optString("medications"),
            dnr = json.optBoolean("dnr"),
            notes = json.optString("notes"),
            organDonor = json.optBoolean("organ_donor"),
        )
    }.getOrDefault(blank())

    fun save(context: Context, record: Record) {
        runCatching {
            file(context).writeText(
                JSONObject().apply {
                    put("blood", record.bloodType)
                    put("allergies", record.allergies)
                    put("conditions", record.conditions)
                    put("medications", record.medications)
                    put("dnr", record.dnr)
                    put("notes", record.notes)
                    put("organ_donor", record.organDonor)
                }.toString()
            )
        }
    }

    private fun blank() = Record("", "", "", "", false, "", false)

    val BLOOD_TYPES = listOf("", "O−", "O+", "A−", "A+", "B−", "B+", "AB−", "AB+")
}

/**
 * The people to call, and the people a duress unlock tells.
 *
 * ## One list, two jobs
 *
 * The same contacts appear on the medical card and receive the duress alert. That is deliberate:
 * maintaining two lists means one of them is out of date, and the person you would want called if
 * you were unconscious is the person you would want told if you were in trouble.
 *
 * ## Stored by number, not by contact id
 *
 * A contact id is a row in the system database, and it moves -- a sync, a restore, a merge, and the
 * id points at somebody else or nothing. The number is copied in at the moment the user chose it, so
 * an emergency message goes where they meant it to go even after their address book has been
 * rebuilt.
 */
object EmergencyContacts {

    private const val FILE = "lock/emergency.json"

    data class Contact(val name: String, val number: String, val addedAt: Long)

    private fun file(context: Context) = File(context.filesDir, FILE).apply { parentFile?.mkdirs() }

    fun all(context: Context): List<Contact> = runCatching {
        val source = file(context)
        if (!source.isFile) return emptyList()
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val number = item.optString("number")
            if (number.isBlank()) null
            else Contact(item.optString("name"), number, item.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun isEmergency(context: Context, number: String): Boolean =
        all(context).any { sameNumber(it.number, number) }

    fun add(context: Context, name: String, number: String) {
        if (number.isBlank()) return
        if (isEmergency(context, number)) return
        write(context, all(context) + Contact(name, number, System.currentTimeMillis()))
    }

    fun remove(context: Context, number: String) {
        write(context, all(context).filterNot { sameNumber(it.number, number) })
    }

    private fun write(context: Context, contacts: List<Contact>) {
        runCatching {
            val array = JSONArray()
            contacts.forEach {
                array.put(JSONObject().apply {
                    put("name", it.name); put("number", it.number); put("at", it.addedAt)
                })
            }
            file(context).writeText(array.toString())
        }
    }

    /**
     * Compares numbers the way people dial them.
     *
     * Formatting is not identity: the same phone is "+1 (555) 010-9999" in one place and
     * "5550109999" in another, and a naive string comparison would let the same person be added
     * twice and then be alerted twice.
     */
    private fun sameNumber(a: String, b: String): Boolean {
        val left = a.filter { it.isDigit() }.takeLast(9)
        val right = b.filter { it.isDigit() }.takeLast(9)
        return left.isNotEmpty() && left == right
    }
}
