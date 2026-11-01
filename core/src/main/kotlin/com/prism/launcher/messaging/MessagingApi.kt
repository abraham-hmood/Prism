package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.Urls
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.UUID

/**
 * Sending and receiving through the three public message APIs.
 *
 * ## Why this is not one generic chat client
 *
 * The networks agree on almost nothing: WhatsApp addresses a business phone number and reports
 * inbound messages only through a webhook; Messenger is a Page API with a readable conversation
 * list; Signal is a local bridge that queues inbound messages for whoever asks. Flattening those
 * into a "send(to, text)" abstraction above this file is useful, but the abstraction must not hide
 * the differences from the user -- the Messages page shows which service a thread belongs to, and
 * Settings says what each connection actually is.
 *
 * ## Group messages
 *
 * WhatsApp Cloud API supports group addressing (`recipient_type` = group). signal-cli supports a
 * group id. Messenger's Send API does not address a group at all, so a Messenger group thread is
 * delivered as individual messages to its participants and the UI says so. That is a real
 * limitation of the API, not of this implementation.
 */
object MessagingApi {

    private const val GRAPH = "https://graph.facebook.com/v23.0"

    sealed interface SendResult {
        data class Sent(val detail: String = "") : SendResult
        data class Failed(val message: String) : SendResult
    }

    data class SyncResult(val received: Int = 0, val error: String = "")

    /** A thread the user is about to start, before any message exists. */
    fun startThread(
        appId: String,
        title: String,
        recipient: String,
        group: Boolean = false,
        participants: List<String> = emptyList(),
    ): MessagingStore.Thread {
        MessagingApps.byId(appId) ?: error("Unknown message service")
        val remote = recipient.trim()
        // The stored id is namespaced by service. The same phone number can be a WhatsApp
        // recipient and a Signal recipient, and keying both as the bare number made them one
        // conversation with two histories.
        //
        // RCS IS PAIRWISE, NOT ADDRESSED. Its id is the sorted set of participants, so the same two
        // people derive the same conversation whether one of them starts it or the other does, and
        // two different senders writing to this device cannot land in one thread just because both
        // put this user's name in the recipient field.
        val id = if (appId == RcsChat.APP_ID) {
            val me = com.prism.launcher.PrismSettings.getLykeUserName().ifBlank { "you" }
            val members = if (group) participants + me + remote else listOf(me, remote)
            appId + ":" + members.map { it.trim().lowercase() }.filter { it.isNotBlank() }
                .distinct().sorted().joinToString("~")
        } else {
            appId + ":" + remote
        }
        val thread = MessagingStore.Thread(
            id = id,
            app = appId,
            title = title.trim().ifBlank { remote },
            group = group,
            participants = (if (group) participants else listOf(remote))
                .map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            lastAt = System.currentTimeMillis(),
            snippet = "",
            unread = 0,
        )
        MessagingStore.upsert(thread)
        return thread
    }

    /**
     * Sends [text] in [thread].
     *
     * The thread id is the address for a direct chat; for a group it is the provider's group id
     * (WhatsApp `group:...`, Signal bare id) or, on Messenger, the conversation whose participants
     * receive a copy each.
     */
    fun send(thread: MessagingStore.Thread, text: String): SendResult {
        if (text.isBlank()) return SendResult.Failed("Nothing to send.")
        val app = MessagingApps.byId(thread.app)
            ?: return SendResult.Failed("Unknown message service.")
        if (!app.api) {
            return SendResult.Failed("${app.label} is handled by your carrier messaging app.")
        }
        if (!app.configured()) {
            return SendResult.Failed(app.missing().joinToString(", ") { it.label } + " missing in Settings.")
        }
        val result = when (thread.app) {
            "whatsapp" -> sendWhatsApp(app, thread, text)
            "messenger" -> sendMessenger(app, thread, text)
            "signal" -> sendSignal(app, thread, text)
            // Prism's own transport. It needs no key and no provider, and it is the one path here
            // that is not a business/Page/bridge API.
            RcsChat.APP_ID -> RcsChat.send(thread, text)
            else -> SendResult.Failed("This service does not have a send API.")
        }
        if (result is SendResult.Sent) {
            MessagingStore.append(
                MessagingStore.Message(
                    id = UUID.randomUUID().toString(),
                    threadId = thread.id,
                    app = thread.app,
                    author = "You",
                    body = text,
                    at = System.currentTimeMillis(),
                    outgoing = true,
                ),
            )
        }
        return result
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────

    private fun sendWhatsApp(app: MessagingApps.App, thread: MessagingStore.Thread, text: String): SendResult {
        val phoneId = app.value("phone_number_id")
        val token = app.value("access_token")
        val remote = remoteOf(thread)
        val body = JSONObject().apply {
            put("messaging_product", "whatsapp")
            put("recipient_type", if (thread.group) "group" else "individual")
            put("to", remote)
            put("type", "text")
            put("text", JSONObject().apply {
                put("preview_url", false)
                put("body", text)
            })
        }
        val response = post("$GRAPH/$phoneId/messages", token, body.toString())
        if (response.ok) return SendResult.Sent()
        // Group messaging is the newest part of the Cloud API and is not enabled on every
        // business account. If the group send is refused, deliver the same text to each
        // participant rather than leaving the user with a failed message and no explanation.
        if (thread.group && thread.participants.isNotEmpty()) {
            val failures = mutableListOf<String>()
            var delivered = 0
            for (recipient in thread.participants.map { it.trim() }.filter { it.isNotBlank() }) {
                val individual = JSONObject().apply {
                    put("messaging_product", "whatsapp")
                    put("recipient_type", "individual")
                    put("to", recipient)
                    put("type", "text")
                    put("text", JSONObject().apply {
                        put("preview_url", false)
                        put("body", text)
                    })
                }
                val each = post("$GRAPH/$phoneId/messages", token, individual.toString())
                if (each.ok) delivered++ else failures += recipient + ": " + each.error
            }
            if (delivered > 0) {
                return SendResult.Sent(
                    "Sent separately to $delivered participant(s)" +
                        if (failures.isEmpty()) "" else "; " + failures.joinToString(" · "),
                )
            }
        }
        return SendResult.Failed(response.error)
    }

    // ── Messenger ────────────────────────────────────────────────────────────

    private fun sendMessenger(app: MessagingApps.App, thread: MessagingStore.Thread, text: String): SendResult {
        val pageId = app.value("page_id")
        val token = app.value("page_access_token")
        val remote = remoteOf(thread)
        val recipients = if (thread.group) {
            thread.participants.filter { it.isNotBlank() && it != pageId }
        } else {
            listOf(remote)
        }
        if (recipients.isEmpty()) return SendResult.Failed("This Messenger thread has no recipient.")
        val failures = mutableListOf<String>()
        var delivered = 0
        for (recipient in recipients) {
            val body = JSONObject().apply {
                put("recipient", JSONObject().put("id", recipient))
                put("messaging_type", "RESPONSE")
                put("message", JSONObject().put("text", text))
            }
            val response = post("$GRAPH/$pageId/messages?access_token=${encode(token)}", token, body.toString())
            if (response.ok) delivered++ else failures += response.error
        }
        return if (delivered > 0) {
            SendResult.Sent(
                if (thread.group) "Messenger has no group send API; sent to $delivered participant(s)" else "",
            )
        } else {
            SendResult.Failed(failures.firstOrNull() ?: "Messenger refused the message.")
        }
    }

    // ── Signal ───────────────────────────────────────────────────────────────

    private fun sendSignal(app: MessagingApps.App, thread: MessagingStore.Thread, text: String): SendResult {
        val base = app.value("bridge_url").trimEnd('/')
        val number = app.value("phone_number")
        val remote = remoteOf(thread)
        val body = JSONObject().apply {
            put("message", text)
            put("number", number)
            if (thread.group && !remote.startsWith("+") && !remote.startsWith("group.")) {
                put("group_id", remote)
            } else {
                put("recipients", JSONArray(listOf(remote)))
            }
        }
        val response = post("$base/v2/send", app.value("bridge_token"), body.toString())
        if (response.ok) return SendResult.Sent()
        return SendResult.Failed(response.error)
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    /**
     * Pulls whatever [appId] can provide.
     *
     * WhatsApp returns nothing here on purpose: the Cloud API has no endpoint that reads a
     * conversation, so its history comes from [ingestWebhook]. The page calls this for the other
     * two while it is open, which is often enough for a conversation view and costs one request.
     */
    fun sync(appId: String): SyncResult {
        val app = MessagingApps.byId(appId) ?: return SyncResult(error = "Unknown service.")
        if (!app.api || !app.configured()) return SyncResult()
        return when (app.id) {
            "messenger" -> syncMessenger(app)
            "signal" -> syncSignal(app)
            else -> SyncResult()
        }
    }

    fun syncAll(): SyncResult {
        var total = 0
        val errors = mutableListOf<String>()
        for (app in MessagingApps.configured()) {
            val result = sync(app.id)
            total += result.received
            if (result.error.isNotBlank()) errors += app.label + ": " + result.error
        }
        return SyncResult(total, errors.joinToString(" · "))
    }

    private fun syncMessenger(app: MessagingApps.App): SyncResult {
        val pageId = app.value("page_id")
        val token = app.value("page_access_token")
        val url = "$GRAPH/$pageId/conversations?platform=messenger" +
            "&fields=id,updated_time,unread_count,snippet,participants,message_count" +
            "&limit=30&access_token=${encode(token)}"
        val list = get(url, token)
        if (!list.ok) return SyncResult(error = list.error)
        val data = list.json?.optJSONArray("data") ?: return SyncResult(error = "Messenger returned no conversations.")
        var received = 0
        for (index in 0 until data.length()) {
            val conversation = data.optJSONObject(index) ?: continue
            val conversationId = conversation.optString("id")
            val participants = conversation.optJSONObject("participants")
                ?.optJSONArray("data")
                ?.let { array ->
                    (0 until array.length()).mapNotNull { at ->
                        array.optJSONObject(at)?.let { person ->
                            Person(
                                id = person.optString("id"),
                                name = person.optString("name").ifBlank { person.optString("id") },
                            )
                        }
                    }
                }.orEmpty()
            val others = participants.filter { it.id != pageId }
            val psid = others.firstOrNull()?.id.orEmpty()
            val title = when {
                others.size > 1 -> others.joinToString(", ") { it.name }
                others.size == 1 -> others.first().name
                else -> "Messenger"
            }
            val threadId = app.id + ":" + psid.ifBlank { conversationId }
            val group = others.size > 1
            MessagingStore.upsert(
                MessagingStore.Thread(
                    id = threadId,
                    app = app.id,
                    title = title,
                    group = group,
                    participants = others.map { it.id },
                    lastAt = graphTime(conversation, "updated_time"),
                    snippet = conversation.optString("snippet"),
                    unread = conversation.optInt("unread_count", 0),
                ),
            )
            val messagesUrl = "$GRAPH/$conversationId/messages" +
                "?fields=id,message,from,created_time&limit=50&access_token=${encode(token)}"
            val messages = get(messagesUrl, token)
            if (!messages.ok) continue
            val rows = messages.json?.optJSONArray("data") ?: continue
            for (row in 0 until rows.length()) {
                val message = rows.optJSONObject(row) ?: continue
                val text = message.optString("message")
                if (text.isBlank()) continue
                val from = message.optJSONObject("from")
                val fromId = from?.optString("id").orEmpty()
                val fromName = from?.optString("name").orEmpty().ifBlank { fromId }
                val at = graphTime(message, "created_time")
                if (at <= 0L) continue
                val added = MessagingStore.append(
                    MessagingStore.Message(
                        id = UUID.randomUUID().toString(),
                        threadId = threadId,
                        app = app.id,
                        author = if (fromId == pageId) "You" else fromName,
                        body = text,
                        at = at,
                        outgoing = fromId == pageId,
                        remoteId = message.optString("id"),
                    ),
                )
                if (added) received++
            }
        }
        return SyncResult(received)
    }

    private fun syncSignal(app: MessagingApps.App): SyncResult {
        val base = app.value("bridge_url").trimEnd('/')
        val number = app.value("phone_number")
        val result = getRaw("$base/v1/receive/${encode(number)}", app.value("bridge_token"), readTimeout = 8_000)
        if (!result.ok) {
            // An idle long-poll can end with 408/504; that is not an error to show the user.
            return if (result.code == 408 || result.code == 504) SyncResult() else SyncResult(error = result.error)
        }
        val array = runCatching { JSONArray(result.body) }.getOrNull() ?: return SyncResult()
        var received = 0
        for (index in 0 until array.length()) {
            val envelope = array.optJSONObject(index) ?: continue
            val data = envelope.optJSONObject("dataMessage") ?: continue
            val text = data.optString("message")
            if (text.isBlank()) continue
            val group = data.optJSONObject("groupInfo")
            val groupId = group?.optString("groupId").orEmpty()
            val source = envelope.optString("sourceNumber")
                .ifBlank { envelope.optString("source") }
            if (source.isBlank()) continue
            val threadId = app.id + ":" + groupId.ifBlank { source }
            val title = group?.optString("groupName").orEmpty().ifBlank { source }
            MessagingStore.upsert(
                MessagingStore.Thread(
                    id = threadId,
                    app = app.id,
                    title = title,
                    group = groupId.isNotBlank(),
                    participants = listOf(source),
                    lastAt = data.optLong("timestamp", envelope.optLong("timestamp", 0L)),
                    snippet = text,
                    unread = -1,
                ),
            )
            val added = MessagingStore.append(
                MessagingStore.Message(
                    id = UUID.randomUUID().toString(),
                    threadId = threadId,
                    app = app.id,
                    author = envelope.optString("sourceName").ifBlank { source },
                    body = text,
                    at = data.optLong("timestamp", System.currentTimeMillis()),
                    outgoing = source == number,
                    remoteId = data.optLong("timestamp", 0L).toString() + ":" + source,
                ),
            )
            if (added) received++
        }
        return SyncResult(received)
    }

    /**
     * Ingests one Meta webhook body.
     *
     * Both platforms wrap events differently, so [appId] selects the parser; the payload is
     * untrusted input and every field is optional. Anything that does not match is ignored rather
     * than throwing, because Meta retries a webhook that does not answer 200 and a malformed
     * status event must not become a retry loop.
     */
    fun ingestWebhook(appId: String, payload: String): Int {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return 0
        return when (appId) {
            "whatsapp" -> ingestWhatsApp(root)
            "messenger" -> ingestMessenger(root)
            else -> 0
        }
    }

    private fun ingestWhatsApp(root: JSONObject): Int {
        val entries = root.optJSONArray("entry") ?: return 0
        var received = 0
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val changes = entry.optJSONArray("changes") ?: continue
            for (changeIndex in 0 until changes.length()) {
                val value = changes.optJSONObject(changeIndex)?.optJSONObject("value") ?: continue
                val names = value.optJSONArray("contacts")?.let { contacts ->
                    (0 until contacts.length()).mapNotNull { at ->
                        contacts.optJSONObject(at)
                            ?.optJSONObject("profile")
                            ?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                    }
                }.orEmpty()
                val messages = value.optJSONArray("messages") ?: continue
                for (messageIndex in 0 until messages.length()) {
                    val message = messages.optJSONObject(messageIndex) ?: continue
                    val from = message.optString("from")
                    if (from.isBlank()) continue
                    val text = message.optJSONObject("text")?.optString("body").orEmpty()
                    if (text.isBlank()) continue
                    val at = message.optLong("timestamp", 0L).let { if (it > 0) it * 1_000L else System.currentTimeMillis() }
                    // A Cloud API group event carries the group id under `context.group_id`.
                    // Without it every group message would open a private thread with the sender,
                    // which is both the wrong conversation and the wrong reply target.
                    val groupId = message.optJSONObject("context")?.optString("group_id").orEmpty()
                    val threadId = "whatsapp:" + groupId.ifBlank { from }
                    val group = groupId.isNotBlank()
                    MessagingStore.upsert(
                        MessagingStore.Thread(
                            id = threadId,
                            app = "whatsapp",
                            title = if (group) "WhatsApp group" else
                                names.firstOrNull().orEmpty().ifBlank { from },
                            group = group,
                            participants = listOf(from),
                            lastAt = at,
                            snippet = text,
                            unread = -1,
                        ),
                    )
                    if (MessagingStore.append(
                            MessagingStore.Message(
                                id = UUID.randomUUID().toString(),
                                threadId = threadId,
                                app = "whatsapp",
                                author = names.firstOrNull().orEmpty().ifBlank { from },
                                body = text,
                                at = at,
                                outgoing = false,
                                remoteId = message.optString("id"),
                            ),
                        )
                    ) received++
                }
            }
        }
        return received
    }

    private fun ingestMessenger(root: JSONObject): Int {
        val entries = root.optJSONArray("entry") ?: return 0
        var received = 0
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val pageId = entry.optString("id")
            val events = entry.optJSONArray("messaging") ?: continue
            for (eventIndex in 0 until events.length()) {
                val event = events.optJSONObject(eventIndex) ?: continue
                val message = event.optJSONObject("message") ?: continue
                val text = message.optString("text")
                if (text.isBlank()) continue
                val sender = event.optJSONObject("sender")?.optString("id").orEmpty()
                if (sender.isBlank() || sender == pageId) continue
                val at = event.optLong("timestamp", System.currentTimeMillis()).let {
                    if (it < 10_000_000_000L) it * 1_000L else it
                }
                MessagingStore.upsert(
                    MessagingStore.Thread(
                        id = "messenger:" + sender,
                        app = "messenger",
                        title = sender,
                        group = false,
                        participants = listOf(sender),
                        lastAt = at,
                        snippet = text,
                        unread = -1,
                    ),
                )
                if (MessagingStore.append(
                        MessagingStore.Message(
                            id = UUID.randomUUID().toString(),
                            threadId = "messenger:" + sender,
                            app = "messenger",
                            author = sender,
                            body = text,
                            at = at,
                            outgoing = false,
                            remoteId = message.optString("mid"),
                        ),
                    )
                ) received++
            }
        }
        return received
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    /** The provider's own address, stripped of the service namespace in [MessagingStore.Thread.id]. */
    private fun remoteOf(thread: MessagingStore.Thread): String =
        thread.id.removePrefix(thread.app + ":")

    /**
     * A Graph API timestamp.
     *
     * Graph returns ISO-8601 strings (`2026-10-28T15:04:05+0000`), not epoch seconds. Reading
     * `created_time` with `optLong` returned 0 for every message, which put every Messenger
     * transcript at 1970 and defeated the duplicate check. Numeric values are still accepted
     * because a test payload or a different edge may send them.
     */
    private fun graphTime(json: JSONObject, key: String): Long {
        val raw = json.opt(key) ?: return 0L
        if (raw is Number) {
            val value = raw.toLong()
            return if (value < 10_000_000_000L) value * 1_000L else value
        }
        val text = raw.toString()
        text.toLongOrNull()?.let { value ->
            return if (value < 10_000_000_000L) value * 1_000L else value
        }
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ")) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    private data class Person(val id: String, val name: String)

    private class Response(
        val code: Int,
        val body: String,
        val json: JSONObject?,
    ) {
        val ok: Boolean get() = code in 200..299
        val error: String
            get() {
                if (ok) return ""
                val detail = json?.optJSONObject("error")?.let { error ->
                    error.optString("error_user_msg").ifBlank {
                        error.optString("message").ifBlank { error.optString("error_data") }
                    }
                }.orEmpty()
                return "HTTP $code" + if (detail.isBlank()) "" else ": " + detail
            }
    }

    private fun get(url: String, token: String): Response = getRaw(url, token, 15_000)

    private fun getRaw(url: String, token: String, readTimeout: Int): Response {
        val connection = runCatching {
            (Urls.of(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                this.readTimeout = readTimeout
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
        }.getOrNull() ?: return Response(0, "", null)
        return read(connection)
    }

    private fun post(url: String, token: String, body: String): Response {
        val connection = runCatching {
            (Urls.of(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 25_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
        }.getOrNull() ?: return Response(0, "", null)
        return runCatching {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            read(connection)
        }.getOrElse {
            connection.disconnect()
            Response(0, "", null)
        }
    }

    private fun read(connection: HttpURLConnection): Response {
        val code = runCatching { connection.responseCode }.getOrDefault(0)
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }.orEmpty()
        connection.disconnect()
        if (code == 0) {
            PrismPlatform.log.warn("Messages", "The message API could not be reached for $connection")
        }
        return Response(code, text, runCatching { JSONObject(text) }.getOrNull())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
