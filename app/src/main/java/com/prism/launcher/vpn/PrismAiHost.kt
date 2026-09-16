package com.prism.launcher.vpn

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.AiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Serves AI generation requests over the Prism mesh — mirrors [com.prism.launcher.browser.PrismWebHost]'s
 * shape (minimal hand-rolled HTTP/1.1 over the socket) but forwards the request into
 * [AiManager]'s local-generation path instead of reading files, and streams tokens back as
 * they're produced instead of serving a static payload. Dispatched from [PrismProxyServer] when
 * a PRISM_CONNECT domain is the reserved [com.prism.launcher.mesh.P2pModelRegistry.MODEL_HOST_DOMAIN]
 * marker instead of a hosted website.
 *
 * Minimal protocol: client sends `POST /generate` with the prompt as the raw request body
 * (Content-Length required, no JSON envelope — this is peer-to-peer within one app, not a public
 * API). Response is `200 OK` with the generated text written as it streams in, or a plain-text
 * error status on failure.
 */
object PrismAiHost {

    private const val TAG = "PrismAiHost"

    /**
     * Serves one peer request: a prompt to run, or a model file to send.
     *
     * ## [preReadHeader] and [inputOverride] are not optional extras
     *
     * THIS IS WHAT MADE EVERY MESH REQUEST TIME OUT. PrismProxyServer sniffs the start of each
     * connection to decide whether it is TLS, which pulls bytes off the socket into a
     * BufferedInputStream, and then reads the whole request head out of that buffer to find the
     * domain it dispatches on. Every other host is handed that head back -- the web host, the
     * search host, Aether, Nebula all take a `preReadHeader`. This one did not. It was given a bare
     * socket whose request line had already been consumed and no way to be told, so it sat in
     * readLine() waiting for a request that had been read minutes earlier, until the CLIENT's read
     * timeout fired. Raising that timeout from 5 seconds to 60 changed nothing, because nothing was
     * ever going to arrive.
     *
     * [inputOverride] closes the second half of the same trap. The proxy's buffer may hold body
     * bytes as well as head bytes -- a BufferedInputStream reads ahead in blocks, not in lines --
     * and those are invisible to anything reading the raw socket afterwards. Reading a POSTed
     * prompt from the socket could therefore lose its first chunk and then block for the rest.
     * Serving from the same stream the proxy read from is what keeps the bytes.
     */
    suspend fun serve(
        context: Context,
        socket: Socket,
        preReadHeader: String? = null,
        inputOverride: java.io.InputStream? = null,
    ) = withContext(Dispatchers.IO) {
        val input = inputOverride ?: socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            // Lines the proxy already consumed, if any. When this is non-empty the request line and
            // the headers must come from HERE and not from the stream, which is now positioned at
            // the body.
            val preLines = preReadHeader
                ?.split('\n')
                ?.map { it.trimEnd('\r') }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val requestLine = preLines.firstOrNull() ?: readLine(input)
            if (requestLine == null) {
                sendError(output, 400, "Empty request")
                return@withContext
            }

            // A model SALE is served over the same channel as inference, because it is the same
            // tunnel to the same peer -- see P2pModelTransfer. Inference posts a prompt; a transfer
            // gets a file. Handled before the POST check so the two do not have to share a verb.
            if (requestLine.startsWith("GET /model/")) {
                serveModelFile(context, requestLine, input, output, headersAlreadyRead = preLines.isNotEmpty())
                return@withContext
            }

            if (!requestLine.startsWith("POST")) {
                sendError(output, 405, "Method Not Allowed — POST a prompt to /generate, or GET /model/<name>")
                return@withContext
            }

            var contentLength = 0
            if (preLines.isNotEmpty()) {
                for (line in preLines.drop(1)) {
                    val h = line.split(":", limit = 2)
                    if (h.size == 2 && h[0].trim().equals("Content-Length", ignoreCase = true)) {
                        contentLength = h[1].trim().toIntOrNull() ?: 0
                    }
                }
            } else {
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val h = line.split(":", limit = 2)
                    if (h.size == 2 && h[0].trim().equals("Content-Length", ignoreCase = true)) {
                        contentLength = h[1].trim().toIntOrNull() ?: 0
                    }
                }
            }

            if (contentLength <= 0) {
                sendError(output, 400, "Missing or empty request body")
                return@withContext
            }

            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBytes, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            val prompt = String(bodyBytes, 0, read).trim()
            if (prompt.isEmpty()) {
                sendError(output, 400, "Empty prompt")
                return@withContext
            }

            writeOkHeader(output)

            // allowOffload = false: this device is ANSWERING, so it runs the model itself rather
            // than forwarding the prompt on to yet another peer. Two devices that have each
            // selected the other in the compute market would otherwise wait on each other until
            // both sockets time out. See AiManager.getResponse.
            if (PrismSettings.getStreamingEnabled()) {
                AiManager.getResponse(context, prompt, allowOffload = false, onToken = { delta ->
                    try {
                        output.write(delta.toByteArray())
                        output.flush()
                    } catch (e: Exception) {
                        // Peer disconnected mid-stream — nothing more to do
                    }
                })
            } else {
                val (finalText, _) = AiManager.getResponse(context, prompt, allowOffload = false)
                output.write(finalText.toByteArray())
                output.flush()
            }
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Serving error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * No Content-Length (the total size isn't known upfront while streaming) and no real
     * Transfer-Encoding: chunked framing either (this is a bespoke peer talking to this same
     * app's own reader, not a general HTTP client) — the body simply runs until the connection
     * closes, which [serve]'s `finally` block does once generation finishes.
     */
    private fun writeOkHeader(output: OutputStream) {
        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: text/plain; charset=utf-8\r\n")
        response.append("Server: PrismMesh/1.0-AI\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        output.write(response.toString().toByteArray())
        output.flush()
    }

    /**
     * Streams a model file to a peer that bought it.
     *
     * ONLY FILES THIS DEVICE ACTUALLY LISTED. The requested name is resolved against the local
     * models directory and the result is checked to be inside it, because the name arrives from
     * the network: without that check a request for `/model/../../databases/wallet` would walk out
     * of the directory and serve something else entirely.
     *
     * Content-Length is always sent. The receiving side refuses a transfer without one rather than
     * writing a file it cannot tell is complete.
     */
    private fun serveModelFile(
        context: Context,
        requestLine: String,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        headersAlreadyRead: Boolean = false,
    ) {
        // Drain the remaining headers so the socket is positioned cleanly -- but only if they are
        // still on the stream. When the proxy has already read the head, draining here would block
        // on a request that is finished, which is the same hang this whole path just came from.
        if (!headersAlreadyRead) {
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
        }

        val raw = requestLine.removePrefix("GET /model/").substringBefore(" ").trim()
        val name = java.net.URLDecoder.decode(raw, "UTF-8")
        val dir = java.io.File(context.filesDir, "models")
        val file = java.io.File(dir, name)

        if (name.isBlank() || !file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)) {
            sendError(output, 403, "Forbidden")
            return
        }
        if (!file.isFile) {
            sendError(output, 404, "No such model on this peer")
            return
        }

        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        file.inputStream().use { it.copyTo(output, 64 * 1024) }
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val body = "HTTP Error $code: $message"
        val response = StringBuilder()
        response.append("HTTP/1.1 $code Error\r\n")
        response.append("Content-Type: text/plain\r\n")
        response.append("Content-Length: ${body.toByteArray().size}\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(body)
        output.write(response.toString().toByteArray())
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        val line = sb.toString()
        return if (line.isEmpty()) null else line
    }
}
