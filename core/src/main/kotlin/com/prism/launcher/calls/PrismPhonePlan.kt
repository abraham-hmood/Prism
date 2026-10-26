package com.prism.launcher.calls

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The phone plan this device is on, and the voicemail that comes with it.
 *
 * ## What "a phone plan" means here
 *
 * It is the carrier subscription the SIM is on, not a Prism product. The platform answers through
 * [probe], because answering needs the telephony stack -- a SIM, a subscription list, a signal
 * state -- and `:core` is not allowed to know `android.telephony` exists. The desktop installs no
 * probe, so a desktop correctly reports no plan and offers mesh calls only.
 *
 * ## Why the plan gates anything at all
 *
 * Because the request behind this file is that a call placed from a phone with a plan goes through
 * Prism's own dialer and never through the system phone app. Prism cannot place a carrier call with
 * its own UI unless it owns the whole path, so the honest split is: with a plan, Prism shows the
 * dialer and drives the carrier call; without one, there is nothing to dial and the keypad says so.
 *
 * ## Voicemail is two different things and this file keeps them apart
 *
 * [Voicemail] is the carrier's mailbox: files under `voicemail/`, imported when the plan hands them
 * over. `MeshCalls.MeshVoice` is the meshnet mailbox and lives with the calls, sealed with the
 * wallet. They are listed in the same screen but are deliberately not the same store: one is audio
 * from a carrier that has already seen it, the other is end-to-end sealed.
 */
object PrismPhonePlan {

    /**
     * What the platform knows about the plan.
     *
     * [reason] is for the UI to show when [active] is false: "No SIM", "Airplane mode", or the
     * subscription being unprovisioned all look identical to a boolean, and the difference is what
     * tells the user whether to wait or to fix something.
     */
    data class Status(
        val active: Boolean,
        val number: String = "",
        val carrier: String = "",
        val reason: String = "",
    )

    /** Installed by the platform adapter. Null on a desktop, which has no phone plan. */
    @Volatile
    var probe: (() -> Status)? = null

    private const val CACHE_MS = 5_000L
    @Volatile private var cached: Status? = null
    @Volatile private var cachedAt = 0L

    fun status(): Status {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        val fresh = runCatching { probe?.invoke() }.getOrNull()
            ?: Status(false, reason = "This device has no phone plan, so there is no number to call from.")
        cached = fresh
        cachedAt = now
        return fresh
    }

    fun refresh(): Status {
        cached = null
        return status()
    }

    fun isActive(): Boolean = status().active

    /** The plan's own number, for the dialer's header. Blank when there is no plan. */
    fun number(): String = status().number

    /**
     * The carrier's voicemail mailbox: `voicemail/` under the app's data directory.
     *
     * Files are plain WAV on purpose. This audio has already passed through a carrier, so sealing it
     * with the wallet would protect it from exactly the one party that already has it, while making
     * export and playback need the wallet for no gain. The meshnet mailbox is the sealed one.
     */
    object Voicemail {

        data class Message(
            val id: String,
            val fromName: String,
            val fromNumber: String,
            val receivedAt: Long,
            val durationMs: Long,
            val file: File,
            var heard: Boolean,
        ) {
            /** `+15551234 — 0:12`, the label both the list and an export use. */
            fun label(): String {
                val who = fromName.ifBlank { fromNumber.ifBlank { "Unknown caller" } }
                val seconds = (durationMs / 1000).toInt()
                return "$who — ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            }
        }

        private const val TAG = "PrismPhoneVoicemail"

        private fun root(): File? = runCatching {
            File(PrismPlatform.host.dataDir(), "voicemail").apply { mkdirs() }
        }.getOrNull()

        private fun indexFile(): File? = root()?.let { File(it, "index.json") }

        private fun readIndex(): MutableMap<String, JSONObject> {
            val file = indexFile() ?: return mutableMapOf()
            if (!file.isFile) return mutableMapOf()
            val parsed = runCatching { JSONObject(file.readText()) }
                .onFailure { PrismPlatform.log.warn(TAG, "Could not read the voicemail index: ${it.message}") }
                .getOrNull() ?: return mutableMapOf()
            val out = mutableMapOf<String, JSONObject>()
            parsed.keys().forEach { key ->
                parsed.optJSONObject(key)?.let { out[key] = it }
            }
            return out
        }

        private fun writeIndex(entries: Map<String, JSONObject>) {
            val file = indexFile() ?: return
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, value) }
            runCatching { file.writeText(json.toString()) }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not save the voicemail index: ${it.message}")
            }
        }

        fun all(): List<Message> {
            val dir = root() ?: return emptyList()
            val index = readIndex()
            return index.mapNotNull { (id, meta) ->
                val file = File(dir, "$id.wav")
                if (!file.isFile) return@mapNotNull null
                Message(
                    id = id,
                    fromName = meta.optString("name"),
                    fromNumber = meta.optString("number"),
                    receivedAt = meta.optLong("at", file.lastModified()),
                    durationMs = meta.optLong("ms", 0),
                    file = file,
                    heard = meta.optBoolean("heard"),
                )
            }.sortedByDescending { it.receivedAt }
        }

        /**
         * Stores one voicemail.
         *
         * The id is derived from the arrival time and the caller rather than random, so the same
         * message imported twice -- which is what a re-sync does -- replaces its own file instead of
         * appearing twice in the list.
         */
        fun store(
            wav: ByteArray,
            fromNumber: String,
            fromName: String = "",
            at: Long = System.currentTimeMillis(),
        ): Message? {
            val dir = root() ?: return null
            if (wav.size <= 44) return null
            val id = "%d-%s".format(at, fromNumber.filter { it.isDigit() }.takeLast(6).ifBlank { "unknown" })
            val file = File(dir, "$id.wav")
            runCatching { file.writeBytes(wav) }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not store a voicemail: ${it.message}")
                return null
            }
            val meta = JSONObject().apply {
                put("name", fromName)
                put("number", fromNumber)
                put("at", at)
                put("ms", wavDurationMs(wav))
                put("heard", false)
            }
            val index = readIndex()
            index[id] = meta
            writeIndex(index)
            return Message(id, fromName, fromNumber, at, meta.optLong("ms"), file, false)
        }

        fun audioOf(message: Message): ByteArray? =
            runCatching { message.file.readBytes() }.getOrNull()

        fun markHeard(message: Message) {
            val index = readIndex()
            val meta = index[message.id] ?: return
            meta.put("heard", true)
            index[message.id] = meta
            writeIndex(index)
            message.heard = true
        }

        fun delete(message: Message): Boolean {
            val removed = runCatching { message.file.delete() }.getOrDefault(false)
            val index = readIndex()
            if (index.remove(message.id) != null) writeIndex(index)
            return removed
        }

        fun unreadCount(): Int = all().count { !it.heard }

        private fun wavDurationMs(wav: ByteArray): Long {
            if (wav.size <= 44) return 0
            val byteRate = ((wav[28].toInt() and 0xff) or ((wav[29].toInt() and 0xff) shl 8) or
                ((wav[30].toInt() and 0xff) shl 16) or ((wav[31].toInt() and 0xff) shl 24))
            if (byteRate <= 0) return 0
            return (wav.size - 44).toLong() * 1000 / byteRate
        }
    }

    /**
     * Turning selected voicemails into something the user can keep.
     *
     * The store is WAV. The Android page asks the platform for an MP3 encoder at export and writes
     * MP3 only when one really exists; otherwise it writes the WAV it has rather than naming PCM
     * audio `.mp3`, which would fail to open everywhere the user took it. One message downloads as
     * that one file; several are zipped, because a save picker takes one document.
     */
    object Export {

        /** A safe, unique file name for one message. */
        fun fileName(message: Voicemail.Message, index: Int = 0): String {
            val who = message.fromNumber.filter { it.isDigit() || it == '+' }.ifBlank { "voicemail" }
            val suffix = if (index > 0) "-$index" else ""
            return "voicemail-$who$suffix.wav"
        }

        /**
         * Packages several recordings into one archive.
         *
         * Entries are written in the order given and names are de-duplicated: two voicemails from
         * the same number would otherwise collide, and a zip with a duplicate name extracts as one
         * file, silently losing audio.
         */
        fun zip(items: List<Pair<String, ByteArray>>): ByteArray? {
            if (items.isEmpty()) return null
            val used = mutableSetOf<String>()
            val out = ByteArrayOutputStream()
            val ok = runCatching {
                ZipOutputStream(out).use { zip ->
                    items.forEachIndexed { position, (name, bytes) ->
                        var candidate = name
                        var counter = 2
                        while (!used.add(candidate)) {
                            val dot = name.lastIndexOf('.')
                            candidate = if (dot > 0) {
                                name.substring(0, dot) + "-$counter" + name.substring(dot)
                            } else {
                                "$name-$counter"
                            }
                            counter += 1
                        }
                        zip.putNextEntry(ZipEntry(candidate))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.onFailure {
                PrismPlatform.log.warn("PrismPhoneVoicemail", "Could not build an export archive: ${it.message}")
            }.isSuccess
            return if (ok) out.toByteArray() else null
        }
    }
}
