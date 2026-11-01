package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The linked message history Prism owns.
 *
 * ## Why a store of Prism's own
 *
 * WhatsApp Cloud API and a Signal bridge do not provide a browsable history the way a personal
 * client does: WhatsApp only delivers new messages to the webhook, and signal-cli's receive
 * endpoint is a queue that is drained once. If Prism did not write what arrives, the conversation
 * would disappear the moment it was read. This is that write, and it is also where a message
 * Prism sends is recorded so the thread is complete even before the network echoes it back.
 *
 * ## Shape
 *
 * One `threads.json` index, one JSON array per thread. A thread is small and the number of
 * messages in it is bounded by the user's usage, so parsing on open is simpler and more honest
 * than a schema migration. The index is rewritten atomically through a temporary file; a torn
 * write must not take every conversation with it.
 *
 * Everything here is local. Nothing is uploaded, and the files live under Prism's data directory
 * (the same place as mail and the model registry), not in a cache the system may clear.
 */
object MessagingStore {

    data class Thread(
        val id: String,
        val app: String,
        val title: String,
        val group: Boolean,
        val participants: List<String>,
        val lastAt: Long,
        val snippet: String,
        val unread: Int,
    ) {
        /** A key that is stable across the phone and desktop, for a mirrored row. */
        val key: String get() = app + ":" + id
    }

    data class Message(
        val id: String,
        val threadId: String,
        val app: String,
        val author: String,
        val body: String,
        val at: Long,
        val outgoing: Boolean,
        /** A provider's own id, when it supplied one. */
        val remoteId: String = "",
    )

    private val lock = Any()

    fun root(): File {
        val dir = File(PrismPlatform.host.dataDir(), "messaging")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun threadsFile(): File = File(root(), "threads.json")

    private fun messagesFile(threadId: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(threadId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val dir = File(root(), "threads")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, digest + ".json")
    }

    fun threads(): List<Thread> = synchronized(lock) {
        val file = threadsFile()
        if (!file.isFile) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::threadOf)
            }
        }.getOrElse {
            PrismPlatform.log.warn("Messages", "Could not read linked-message threads: ${it.message}")
            emptyList()
        }.sortedByDescending { it.lastAt }
    }

    fun threadsFor(app: String): List<Thread> = threads().filter { it.app == app }

    fun thread(id: String): Thread? = threads().firstOrNull { it.id == id }

    /** Adds or updates a thread without touching its messages. */
    fun upsert(thread: Thread) = synchronized(lock) {
        val all = threads().toMutableList()
        val index = all.indexOfFirst { it.id == thread.id && it.app == thread.app }
        if (index >= 0) {
            val old = all[index]
            all[index] = thread.copy(
                lastAt = maxOf(old.lastAt, thread.lastAt),
                unread = if (thread.unread >= 0) thread.unread else old.unread,
                snippet = thread.snippet.ifBlank { old.snippet },
            )
        } else {
            all += thread
        }
        writeThreads(all)
    }

    fun markRead(threadId: String) = synchronized(lock) {
        val all = threads().map { if (it.id == threadId) it.copy(unread = 0) else it }
        writeThreads(all)
    }

    fun messages(threadId: String): List<Message> = synchronized(lock) {
        val file = messagesFile(threadId)
        if (!file.isFile) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::messageOf)
            }
        }.getOrElse {
            PrismPlatform.log.warn("Messages", "Could not read ${threadId}: ${it.message}")
            emptyList()
        }.sortedBy { it.at }
    }

    /**
     * Appends [message] once.
     *
     * De-duplication is by the provider's own id when it has one, and otherwise by the full
     * content. A webhook retry and a polling pass can both deliver the same message; showing it
     * twice would look like the person said it twice.
     */
    fun append(message: Message): Boolean = synchronized(lock) {
        val existing = messages(message.threadId)
        if (existing.any { old -> matches(old, message) }) return false
        val file = messagesFile(message.threadId)
        val array = JSONArray()
        for (old in existing + message) array.put(old.toJson())
        writeAtomic(file, array.toString())
        val thread = thread(message.threadId)
        if (thread == null) {
            upsert(
                Thread(
                    id = message.threadId,
                    app = message.app,
                    title = if (message.outgoing) "You" else message.author.ifBlank { message.threadId },
                    group = false,
                    participants = emptyList(),
                    lastAt = message.at,
                    snippet = message.body,
                    unread = if (message.outgoing) 0 else 1,
                ),
            )
        } else {
            upsert(
                thread.copy(
                    lastAt = maxOf(thread.lastAt, message.at),
                    snippet = message.body,
                    unread = if (message.outgoing) thread.unread else thread.unread + 1,
                ),
            )
        }
        true
    }

    private fun matches(old: Message, new: Message): Boolean =
        if (old.remoteId.isNotBlank() && new.remoteId.isNotBlank()) {
            old.remoteId == new.remoteId
        } else {
            old.threadId == new.threadId && old.author == new.author &&
                old.body == new.body && kotlin.math.abs(old.at - new.at) < 5_000
        }

    private fun threadOf(json: JSONObject) = Thread(
        id = json.optString("id", ""),
        app = json.optString("app", ""),
        title = json.optString("title", ""),
        group = json.optBoolean("group", false),
        participants = json.optJSONArray("participants")?.let { array ->
            (0 until array.length()).map { array.optString(it, "") }.filter { it.isNotBlank() }
        }.orEmpty(),
        lastAt = json.optLong("lastAt", 0L),
        snippet = json.optString("snippet", ""),
        unread = json.optInt("unread", 0),
    )

    private fun messageOf(json: JSONObject) = Message(
        id = json.optString("id", ""),
        threadId = json.optString("threadId", ""),
        app = json.optString("app", ""),
        author = json.optString("author", ""),
        body = json.optString("body", ""),
        at = json.optLong("at", 0L),
        outgoing = json.optBoolean("outgoing", false),
        remoteId = json.optString("remoteId", ""),
    )

    private fun Message.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("threadId", threadId)
        put("app", app)
        put("author", author)
        put("body", body)
        put("at", at)
        put("outgoing", outgoing)
        if (remoteId.isNotBlank()) put("remoteId", remoteId)
    }

    private fun Thread.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("app", app)
        put("title", title)
        put("group", group)
        put("participants", JSONArray(participants.toMutableList<Any?>()))
        put("lastAt", lastAt)
        put("snippet", snippet)
        put("unread", unread)
    }

    private fun writeThreads(all: List<Thread>) {
        val array = JSONArray()
        all.sortedByDescending { it.lastAt }.forEach { array.put(it.toJson()) }
        writeAtomic(threadsFile(), array.toString())
    }

    private fun writeAtomic(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
    }
}
