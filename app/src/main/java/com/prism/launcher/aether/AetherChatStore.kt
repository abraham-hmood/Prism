package com.prism.launcher.aether

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Aether's conversation history -- a flat JSON file on her own storage, same rationale and shape
 * as `NoraChatStore`.
 *
 * CROSS-PROCESS SAFE, NOW THAT AetherService RUNS IN ITS OWN PROCESS. `@Synchronized` only
 * excludes threads within ONE process's JVM -- it does nothing between this process and
 * `:aether`. Three call sites write this file: `AetherService.kt` (an AI reply, and the "busy"
 * auto-response), both running in `:aether`, and `ConversationActivity.kt` (the user's own
 * outgoing message), running in the main process. Before the process split those three writers
 * were three threads in one process and `@Synchronized` genuinely serialized them; after it, a
 * main-process write and an `:aether`-process write can race on the same file with NOTHING
 * guarding them, and `append`'s load-then-write round trip means the loser doesn't just reorder,
 * it CLOBBERS -- overwriting the whole file with a copy that never saw the other write, silently
 * dropping an entire message. [withFileLock] wraps every read-modify-write round trip in a real
 * OS-level [FileLock] on the chat file itself, which (unlike `@Synchronized`) is honoured across
 * processes on the same file by the kernel, not just within one JVM.
 */
object AetherChatStore {

    data class Entry(
        val text: String,
        val isSent: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val attachmentUri: String? = null,
        val attachmentType: String? = null
    )

    private const val MAX_ENTRIES = 400

    fun load(ctx: Context): List<Entry> =
        withFileLock { raf -> readEntriesLocked(raf) } ?: emptyList()

    fun append(ctx: Context, entry: Entry) {
        withFileLock { raf ->
            val all = (readEntriesLocked(raf) + entry).takeLast(MAX_ENTRIES)
            writeEntriesLocked(raf, all)
        }
    }

    fun clear(ctx: Context) {
        withFileLock { raf -> writeEntriesLocked(raf, emptyList()) }
    }

    fun lastSnippet(ctx: Context): String =
        load(ctx).lastOrNull()?.text?.take(60) ?: "Fully biological text, image and video generation."

    // ── Locked read-modify-write ────────────────────────────────────────────

    /**
     * Opens the chat file, takes an exclusive [FileLock] on it (blocking until any other
     * process's writer releases its own), runs [block] with that lock held, then releases and
     * closes. Every public function above goes through this -- including plain reads, since a
     * read racing a concurrent truncate-then-rewrite from another process could otherwise observe
     * a half-written file. Not `@Synchronized` as well: the file lock alone is both the
     * within-process and cross-process guard, and a real [FileLock] already blocks other threads
     * in this same JVM that are locking the same file, so stacking a Kotlin monitor on top would
     * only add a second, redundant serialization point.
     */
    private fun <T> withFileLock(block: (RandomAccessFile) -> T): T? {
        val file = AetherConfig.chatFile()
        // OPENING THE FILE CAN FAIL OUTRIGHT, and used to take the app with it. The transcript
        // lives under the shared Documents directory, not in app-private storage, so the directory
        // may not exist and may not be creatable -- storage permission not granted yet, scoped
        // storage refusing the path, the volume unmounted. mkdirs() reports that by returning
        // false, which nothing checked, and RandomAccessFile then threw FileNotFoundException out
        // of load() and crashed the messages page before it could draw.
        //
        // A missing transcript is not an error worth crashing over: it is what every conversation
        // looks like before its first message. Callers get null and treat it as empty.
        return try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                var lock: FileLock? = null
                try {
                    lock = raf.channel.lock()
                    block(raf)
                } finally {
                    try { lock?.release() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            PrismLogger.logError(
                "Aether",
                "Conversation store unavailable at ${file.absolutePath}: ${e.message}"
            )
            null
        }
    }

    private fun readEntriesLocked(raf: RandomAccessFile): List<Entry> {
        return try {
            val length = raf.length()
            if (length == 0L) return emptyList()
            raf.seek(0)
            val bytes = ByteArray(length.toInt())
            raf.readFully(bytes)
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    text = o.optString("text"),
                    isSent = o.optBoolean("sent"),
                    timestamp = o.optLong("ts", System.currentTimeMillis()),
                    attachmentUri = o.optString("uri").ifBlank { null },
                    attachmentType = o.optString("type").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            PrismLogger.logError("Aether", "Could not read conversation: ${e.message}")
            emptyList()
        }
    }

    private fun writeEntriesLocked(raf: RandomAccessFile, entries: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().apply {
                    put("text", e.text)
                    put("sent", e.isSent)
                    put("ts", e.timestamp)
                    e.attachmentUri?.let { put("uri", it) }
                    e.attachmentType?.let { put("type", it) }
                })
            }
            val bytes = arr.toString().toByteArray(Charsets.UTF_8)
            raf.seek(0)
            raf.write(bytes)
            raf.setLength(bytes.size.toLong())
        } catch (e: Exception) {
            PrismLogger.logError("Aether", "Could not write conversation: ${e.message}")
        }
    }
}
