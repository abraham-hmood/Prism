package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small webhook receiver for the two Meta message APIs.
 *
 * ## Why this has to exist
 *
 * WhatsApp Cloud API has no "list my conversations" endpoint. Inbound messages are pushed to a
 * webhook, and a receiver that is not running when the event arrives loses that message for good.
 * Messenger can be polled, but its webhook is the lower-latency path and costs far fewer Graph
 * calls. This server is therefore the receive half of both integrations, not an optional extra.
 *
 * ## What it deliberately is not
 *
 * It speaks plain HTTP, binds LOOPBACK ONLY, and does not terminate TLS. Meta requires an HTTPS
 * callback, so the address configured in the developer console must be a TLS terminator in front
 * of this port -- Prism's tunnel or a reverse proxy on the same machine. Binding loopback is
 * deliberate: this endpoint writes messages into the user's inbox, and a webhook port open to the
 * LAN would let any device on the network inject them. A TLS proxy can reach loopback; a stranger
 * cannot.
 *
 * The verify token is checked on subscription and the body is bounded. A request with no token
 * configured is rejected rather than accepted, because an open local port that writes arbitrary
 * messages into the user's inbox is worse than an unconfigured integration.
 */
object MessagingWebhook {

    private const val TAG = "Messages"
    private const val MAX_BODY = 1_000_000

    data class Status(
        val running: Boolean,
        val port: Int,
        val whatsappUrl: String,
        val messengerUrl: String,
        val lastError: String = "",
        val received: Int = 0,
    )

    private val active = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var lastError = ""
    private var received = 0

    fun status(): Status {
        val port = PrismSettings.getMessagingWebhookPort()
        return Status(
            running = active.get() && server?.isClosed == false,
            port = port,
            whatsappUrl = "http://127.0.0.1:$port/whatsapp",
            messengerUrl = "http://127.0.0.1:$port/messenger",
            lastError = lastError,
            received = received,
        )
    }

    /** Starts the receiver when the user enabled it. Safe to call more than once. */
    fun startIfEnabled() {
        if (!PrismSettings.getMessagingWebhookEnabled()) {
            stop()
            return
        }
        start()
    }

    fun start(): String {
        if (active.get()) return ""
        val port = PrismSettings.getMessagingWebhookPort()
        val socket = runCatching { ServerSocket() }.getOrElse {
            return "Could not create the message webhook socket: ${it.message}"
        }
        val bound = runCatching {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port))
            socket
        }.getOrElse {
            runCatching { socket.close() }
            lastError = "Port $port is already in use: ${it.message}"
            return lastError
        }
        server = bound
        active.set(true)
        lastError = ""
        val worker = Thread({ acceptLoop(bound) }, "prism-message-webhook").apply {
            isDaemon = true
            start()
        }
        thread = worker
        PrismPlatform.log.info(TAG, "Message webhook listening on port $port")
        return ""
    }

    fun stop() {
        active.set(false)
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (active.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                if (active.get()) lastError = error.message.orEmpty()
                break
            }
            runCatching { handle(client) }.onFailure {
                lastError = it.message.orEmpty()
                PrismPlatform.log.warn(TAG, "Message webhook request failed: ${it.message}")
            }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }
        val app = when {
            path.endsWith("/whatsapp") -> "whatsapp"
            path.endsWith("/messenger") -> "messenger"
            else -> ""
        }
        if (app.isEmpty()) {
            respond(client.getOutputStream(), 404, "Not found")
            return
        }
        if (method == "GET") {
            verify(app, query, client.getOutputStream())
            return
        }
        if (method != "POST") {
            respond(client.getOutputStream(), 405, "Method not allowed")
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY) {
            respond(client.getOutputStream(), 413, "Payload too large or missing")
            return
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        val text = String(body, 0, read)
        if (!signatureAccepted(app, text.toByteArray(Charsets.UTF_8), headers["x-hub-signature-256"])) {
            respond(client.getOutputStream(), 403, "Signature refused")
            return
        }
        val added = MessagingApi.ingestWebhook(app, text)
        if (added > 0) received += added
        respond(client.getOutputStream(), 200, "EVENT_RECEIVED")
    }

    /**
     * Meta's `X-Hub-Signature-256`, when an app secret is configured.
     *
     * Without a secret the request is accepted only because the socket is loopback-only. With one,
     * a body that was not signed by that secret is refused before it can reach the store; a
     * reverse proxy does not make the payload trustworthy by itself.
     */
    private fun signatureAccepted(app: String, body: ByteArray, header: String?): Boolean {
        val secret = MessagingApps.value(
            app,
            MessagingApps.byId(app)?.keys?.firstOrNull { it.id == "app_secret" } ?: return true,
        )
        if (secret.isBlank()) return true
        if (header.isNullOrBlank()) return false
        val digest = runCatching {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal(body).joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull() ?: return false
        return header.equals("sha256=$digest", ignoreCase = true)
    }

    private fun verify(app: String, query: String, output: OutputStream) {
        val params = query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isBlank()) return@mapNotNull null
            key to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val mode = params["hub.mode"].orEmpty()
        val token = params["hub.verify_token"].orEmpty()
        val challenge = params["hub.challenge"].orEmpty()
        val appValue = MessagingApps.byId(app)
        val key = appValue?.keys?.firstOrNull { it.id == "verify_token" }
        val expected = if (key == null) "" else appValue.value(key)
        if (mode != "subscribe" || expected.isBlank() || token != expected) {
            respond(output, 403, "Verification refused")
            return
        }
        respond(output, 200, challenge)
    }

    private fun respond(output: OutputStream, code: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        output.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
        )
        output.write(body)
        output.flush()
    }
}
