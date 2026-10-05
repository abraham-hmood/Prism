package com.prism.launcher.messaging

import com.prism.core.MeshConnect
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Answers other devices' AI requests. The serving half of PHASE 53.
 *
 * ## Why this is not an API
 *
 * `POST /generate` with the prompt as the raw body, and the answer as the raw body back. No JSON
 * envelope, no request id, no model parameter. This is Prism talking to Prism over a tunnel that already
 * authenticated the peer by reaching it, and every field an envelope would carry is already known: which
 * model, because the host announced exactly one; who is asking, because they are on the mesh. The Android
 * build made the same choice and this speaks its shape, so a phone can use a desktop's model with no
 * change on the phone.
 *
 * ## Why hosting is off by default
 *
 * Serving a model is somebody else's work running on your processor and your electricity, for minutes at
 * a time. That is a decision, not a default, and [PrismSettings.getP2pModelHostingEnabled] is where it is
 * recorded. A request that arrives while hosting is off gets a plain refusal rather than silence, because
 * a peer that cannot tell "off" from "broken" will keep retrying.
 *
 * ## Why the model file is served too
 *
 * `GET /model/<name>` hands over the weights themselves, for a peer that would rather have its own copy
 * than depend on this machine being awake. Same route the Android build serves, and the reason the model
 * shop works between two phones.
 */
object MeshAiHost : MeshConnect.DomainHost {

    private const val TAG = "PrismMeshAiHost"

    /**
     * Extra POST routes a platform adds on this domain.
     *
     * ## Why a registry rather than another domain
     *
     * `MeshConnect` allows ONE host per domain, and the reserved `ai-model.prism.p2p` name is what
     * every peer-to-peer client already sets as its host hint -- the model transfer, the cloud vault
     * and the cloud-gaming control channel all post to it. A second feature wanting a route on that
     * domain cannot register a second host; it has to be let in by the one that owns it.
     *
     * So a platform adds its path here. The key is the exact path after the method, and the handler
     * is given the peer's address and the request body and returns a response body or null to decline
     * -- declining falls through to this host's own 404, which is what should happen for a path
     * nobody claimed.
     *
     * Added for PHASE 99: the desktop serves `POST /cloud` for cloud-gaming control, and the phone
     * has no equivalent because a phone is deliberately never a host.
     */
    private val extraRoutes = java.util.concurrent.ConcurrentHashMap<
        String, (peerIp: String, body: String) -> String?
        >()

    /** Claims a POST path on the AI domain. See [extraRoutes]. */
    fun route(path: String, handler: (peerIp: String, body: String) -> String?) {
        extraRoutes[path] = handler
        PrismPlatform.log.info(TAG, "Serving POST " + path + " on " + MeshModels.HOST_DOMAIN)
    }

    /** Registers this host for the reserved AI domain. Called at startup. */
    fun install() {
        MeshConnect.host(MeshModels.HOST_DOMAIN, this)
        PrismPlatform.log.info(TAG, "Serving AI requests for peers that ask for " + MeshModels.HOST_DOMAIN)
    }

    override fun serve(socket: Socket, domain: String, head: String?, input: InputStream) {
        val output = socket.getOutputStream()
        val requestLine = head.orEmpty().lineSequence().firstOrNull().orEmpty()

        when {
            requestLine.startsWith("GET /model/") -> serveModelFile(requestLine, output)
            requestLine.startsWith("POST /generate") -> serveGeneration(head.orEmpty(), input, output)
            // A platform's own route. Checked before the 404 and after the two this host owns, so a
            // registration can never shadow model serving or generation.
            requestLine.startsWith("POST ") &&
                extraRoutes.containsKey(requestLine.pathOfRequestLine()) ->
                serveExtra(
                    requestLine.pathOfRequestLine(),
                    socket, head.orEmpty(), input, output,
                )
            else -> respond(
                output, 404,
                "This peer answers POST /generate, GET /model/<name>" +
                    (if (extraRoutes.isEmpty()) "." else
                        " and " + extraRoutes.keys.sorted().joinToString(", ") { "POST " + it } + "."),
            )
        }
        runCatching { socket.close() }
    }

    /** The path out of a request line: "POST /cloud HTTP/1.1" -> "/cloud". */
    private fun String.pathOfRequestLine(): String =
        substringAfter(' ', "").substringBefore(' ').substringBefore('?')

    private fun serveExtra(
        path: String,
        socket: Socket,
        head: String,
        input: InputStream,
        output: OutputStream,
    ) {
        val handler = extraRoutes[path] ?: run {
            respond(output, 404, "No handler for " + path + ".")
            return
        }
        val length = contentLength(head)
        val body = if (length <= 0) "" else runCatching {
            readExactly(input, length).toString(Charsets.UTF_8)
        }.getOrDefault("")

        val peerIp = runCatching { socket.inetAddress?.hostAddress }.getOrNull().orEmpty()
        val reply = runCatching { handler(peerIp, body) }.getOrElse {
            PrismPlatform.log.warn(TAG, path + " failed for " + peerIp + ": " + it.message)
            null
        }
        if (reply == null) {
            respond(output, 503, "This device declined " + path + ".")
            return
        }
        val bytes = reply.toByteArray()
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK" + CRLF +
                    "Content-Type: application/json; charset=utf-8" + CRLF +
                    "Content-Length: " + bytes.size + CRLF +
                    "Connection: close" + CRLF + CRLF).toByteArray(),
            )
            output.write(bytes)
            output.flush()
        }
    }

    private const val CRLF = "\r\n"

    // ── Generation ─────────────────────────────────────────────────────────

    private fun serveGeneration(head: String, input: InputStream, output: OutputStream) {
        if (!PrismSettings.getP2pModelHostingEnabled()) {
            respond(output, 403, "This device is not sharing its model right now.")
            return
        }

        val length = contentLength(head)
        if (length <= 0) {
            respond(output, 400, "Send the prompt as the request body.")
            return
        }

        val prompt = runCatching { readExactly(input, length).toString(Charsets.UTF_8) }.getOrNull()
        if (prompt.isNullOrBlank()) {
            respond(output, 400, "The request body was empty.")
            return
        }

        // THE LOCAL PATH ONLY. A host that quietly forwarded a peer's prompt to a cloud model would be
        // spending the host's API credits on somebody else's request, and sending a prompt somewhere the
        // asking device never agreed to. If there is no local model, say so.
        val path = PrismSettings.getLocalAiModelPath()
        if (path.isBlank() || !File(path).isFile) {
            respond(output, 503, "This device has no local model loaded.")
            return
        }

        PrismPlatform.log.info(TAG, "Generating for a peer (" + prompt.length + " characters)")
        val answer = runCatching {
            GgufInferenceService.generateResponse(path, prompt)
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Generation for a peer failed", it)
            respond(output, 500, "Generation failed on this device.")
            return
        }

        val body = answer.toByteArray()
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
        }
    }

    // ── The weights themselves ─────────────────────────────────────────────

    private fun serveModelFile(requestLine: String, output: OutputStream) {
        if (!PrismSettings.getP2pModelHostingEnabled()) {
            respond(output, 403, "This device is not sharing its model right now.")
            return
        }

        val name = runCatching {
            java.net.URLDecoder.decode(requestLine.removePrefix("GET /model/").substringBefore(" ").trim(), "UTF-8")
        }.getOrDefault("")

        // Matched against the ACTIVE model by name rather than resolved as a path. A peer naming a file
        // is a peer naming a path, and the only file this device has agreed to share is the one it
        // announced.
        val active = File(PrismSettings.getLocalAiModelPath())
        if (name.isBlank() || !active.isFile || active.name != name) {
            respond(output, 404, "This device is not sharing a model by that name.")
            return
        }

        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: " + active.length() + "\r\nConnection: close\r\n\r\n").toByteArray()
            )
            active.inputStream().use { it.copyTo(output, 64 * 1024) }
            output.flush()
            PrismPlatform.log.info(TAG, "Sent " + active.name + " to a peer")
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun contentLength(head: String): Int =
        head.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                    parts[1].trim().toIntOrNull()
                } else {
                    null
                }
            }
            .firstOrNull() ?: 0

    /**
     * Reads exactly [length] bytes.
     *
     * Not `readBytes()`: the connection stays open until the response is written, so reading to the end
     * of the stream would block until the client gave up. The declared length is the only thing that says
     * where the body stops.
     */
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val got = input.read(buffer, read, length - read)
            if (got < 0) break
            read += got
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        runCatching {
            output.write(
                ("HTTP/1.1 " + code + " " + (if (code == 200) "OK" else "Error") + "\r\n" +
                    "Content-Type: text/plain\r\nContent-Length: " + body.size +
                    "\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
        }
    }
}
