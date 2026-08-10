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

    suspend fun serve(context: Context, socket: Socket) = withContext(Dispatchers.IO) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            val requestLine = readLine(input)
            if (requestLine == null || !requestLine.startsWith("POST")) {
                sendError(output, 405, "Method Not Allowed — POST a prompt to /generate")
                return@withContext
            }

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val h = line.split(":", limit = 2)
                if (h.size == 2 && h[0].trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = h[1].trim().toIntOrNull() ?: 0
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

            if (PrismSettings.getStreamingEnabled()) {
                AiManager.getResponse(context, prompt, onToken = { delta ->
                    try {
                        output.write(delta.toByteArray())
                        output.flush()
                    } catch (e: Exception) {
                        // Peer disconnected mid-stream — nothing more to do
                    }
                })
            } else {
                val (finalText, _) = AiManager.getResponse(context, prompt)
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
