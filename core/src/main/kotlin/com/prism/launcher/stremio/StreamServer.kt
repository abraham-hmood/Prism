package com.prism.launcher.stremio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Where a streaming server gets its bytes. Implemented by the torrent session. */
internal interface StreamSource {
    val length: Long
    fun contentType(): String

    /**
     * Reads up to [max] bytes at [offset].
     *
     * Returns the number read, 0 when nothing is ready before [timeoutMs], or -1 when the source
     * has ended or failed.
     */
    fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, max: Int, timeoutMs: Long): Int
}

/** One `Range: bytes=...` request, resolved against a known length. */
internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
    val headerValue: String get() = "bytes $start-$endInclusive/"
}

/**
 * The `Range` header parser, separated from the server so it can be tested without sockets.
 *
 * Deliberately narrow: one range, the three forms players actually send, and null for everything
 * else. A multi-range request is answered in full rather than assembled -- no video player asks
 * for one, and the alternative is a multipart response nobody needs.
 */
internal object RangeHeader {

    fun parse(header: String?, length: Long): ByteRange? {
        if (length <= 0 || header == null) return null
        val text = header.trim()
        if (!text.startsWith("bytes=", ignoreCase = true)) return null
        val spec = text.substring(6).substringBefore(',').trim()
        if (!spec.contains('-')) return null

        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()

        if (startText.isEmpty()) {
            // A suffix range: the last N bytes, used by some players to probe containers.
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            return ByteRange((length - suffix).coerceAtLeast(0L), length - 1)
        }

        val start = startText.toLongOrNull() ?: return null
        if (start < 0L || start >= length) return null
        val end = if (endText.isEmpty()) length - 1 else endText.toLongOrNull() ?: return null
        val clamped = end.coerceAtMost(length - 1)
        if (clamped < start) return null
        return ByteRange(start, clamped)
    }
}

/**
 * A loopback HTTP server that turns a [StreamSource] into something `VideoView` can play.
 *
 * ## Why a server at all
 *
 * Android's player plays URLs and files, not "a torrent". BitTorrent has no notion of a byte
 * offset it can seek to without downloading everything in between, so the one thing that makes a
 * magnet playable is a seekable HTTP view of a file that is still arriving. That is what this is:
 * `Range` support is not a nicety here, it is the whole feature -- a player seeks to the end of an
 * MKV to read its index before it shows a frame.
 *
 * ## Loopback only
 *
 * Bound to 127.0.0.1 and to a random port, so nothing on the network can reach it, and the token in
 * the path means another app on the device cannot guess it either. The server is not an API; it
 * exists to hand one player one file.
 */
internal class StreamServer(
    private val source: StreamSource,
    private val token: String,
    private val fileName: String,
) : Closeable {

    private val monitor = Object()
    private val server: ServerSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val connections = AtomicInteger(0)
    private var closed = false

    val port: Int get() = server.localPort

    /** The URL handed to the player. The filename is cosmetic; only [token] is checked. */
    val url: String get() = "http://127.0.0.1:$port/$token/" + encodeSegment(fileName)

    private val acceptThread = Thread({ acceptLoop() }, "prism-torrent-http").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!synchronized(monitor) { closed }) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                continue
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            Thread({
                try {
                    serve(socket)
                } catch (_: Exception) {
                    // A player that seeks twice closes the connection it stopped reading. That is
                    // normal traffic, not an error to log on every scrub.
                } finally {
                    connections.decrementAndGet()
                    runCatching { socket.close() }
                }
            }, "prism-torrent-http-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (path.removePrefix("/").substringBefore('/') != token) {
            respondError(socket.getOutputStream(), "404 Not Found", "Unknown stream")
            return
        }

        val length = source.length
        if (length <= 0L) {
            respondError(socket.getOutputStream(), "503 Service Unavailable", "Nothing to stream yet")
            return
        }

        val range = RangeHeader.parse(headers["range"], length)
        if (headers["range"] != null && range == null) {
            respondError(socket.getOutputStream(), "416 Range Not Satisfiable", "Bad range")
            return
        }

        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val start = range?.start ?: 0L
        val count = range?.length ?: length

        val head = buildString {
            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: ").append(source.contentType()).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(count).append("\r\n")
            if (range != null) {
                append("Content-Range: ").append(range.headerValue).append(length).append("\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        if (method == "HEAD") return

        val buffer = ByteArray(64 * 1024)
        var position = start
        var remaining = count
        while (remaining > 0L && !synchronized(monitor) { closed }) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = try {
                source.read(position, buffer, 0, want, READ_TIMEOUT_MS)
            } catch (_: Exception) {
                -1
            }
            // ZERO IS "NOT YET", NOT "THE END". A piece that has not arrived within the read
            // timeout is a slow torrent, not a short file; breaking here would answer a player
            // with fewer bytes than Content-Length promised and make it reject the stream. The
            // storage itself ends the wait when the session fails.
            if (read == 0) continue
            if (read < 0) break
            output.write(buffer, 0, read)
            position += read
            remaining -= read
        }
        output.flush()
    }

    private fun respondError(output: OutputStream, status: String, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1)
        )
        output.write(body)
        output.flush()
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
        }
        runCatching { server.close() }
        acceptThread.interrupt()
    }

    companion object {
        private const val MAX_CONNECTIONS = 8
        private const val READ_TIMEOUT_MS = 30_000L

        /** Header lines are ASCII and bounded; a peer app cannot make this allocate without limit. */
        private fun readLine(input: InputStream): String? {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
                if (byte == '\n'.code) return out.toString("ISO-8859-1").trimEnd('\r')
                if (out.size() > 8 * 1024) return null
                out.write(byte)
            }
        }

        private fun encodeSegment(name: String): String = buildString {
            name.substringAfterLast('/').ifBlank { "stream" }.toByteArray(Charsets.UTF_8).forEach {
                val value = it.toInt() and 0xFF
                if (value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
                    value in '0'.code..'9'.code || value == '.'.code || value == '-'.code ||
                    value == '_'.code
                ) {
                    append(value.toChar())
                } else {
                    append('%').append("%02X".format(value))
                }
            }
        }
    }
}
