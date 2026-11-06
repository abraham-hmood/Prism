package com.prism.launcher.storage.remote

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.storage.drive.DriveFileSystem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Another device's drive, reached through its [DriveServer] inside a [SecureDriveChannel].
 *
 * Every request opens a connection, proves this device holds the wallet's drive key, and speaks
 * HTTP/1.1 inside the encrypted pipe. [endpoint] is looked up per request, because a trusted
 * device's address is a lease that can change while a folder is open; [key] -- the pair's drive key
 * -- is too, so locking the wallet stops access immediately.
 */
class RemoteDriveAccess(
    private val endpoint: () -> Pair<String, Int>?,
    private val key: () -> ByteArray?,
    private val token: String,
    private val driveId: String,
    override val readOnly: Boolean,
) : DriveAccess {

    private class Response(val status: Int, val body: InputStream, private val socket: Socket) : AutoCloseable {
        fun text(): String = body.use { it.readBytes().decodeToString() }
        override fun close() {
            runCatching { body.close() }
            runCatching { socket.close() }
        }
    }

    private fun target(path: String, op: String? = null, extra: String = ""): String {
        val query = buildString {
            if (op != null) append("?prism=").append(op)
            if (extra.isNotEmpty()) append(if (op == null) "?" else "&").append(extra)
        }
        return "/" + encodeSegment(token) + "/" + encodeSegment(driveId) +
            encodePath(DriveFileSystem.normalise(path)) + query
    }

    /**
     * Sends one request. [body] writes the request body; [length] is its size, or -1 to send it
     * chunked. The response body is limited to its Content-Length, which the server always sends.
     */
    private fun request(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        length: Long = 0,
        body: ((OutputStream) -> Unit)? = null,
    ): Response {
        val (host, port) = endpoint() ?: throw IOException("The device is offline")
        val secret = key() ?: throw IOException(
            "No key for that device yet: unlock your wallet, and keep both devices on the meshnet " +
                "for a moment so they can exchange keys",
        )
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 6_000)
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            val pipe = SecureDriveChannel.connect(socket.getInputStream(), socket.getOutputStream(), secret)
            val out = BufferedOutputStream(pipe.output, 64 * 1024)
            val head = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Connection: close\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (body != null) {
                    if (length >= 0) append("Content-Length: ").append(length).append("\r\n")
                    else append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: 0\r\n")
                }
                append("\r\n")
            }
            out.write(head.toByteArray(Charsets.UTF_8))
            if (body != null) {
                if (length >= 0) body(out) else ChunkedOutput(out).also(body).close()
            }
            out.flush()

            val input = BufferedInputStream(pipe.input, 64 * 1024)
            val lines = readHead(input)
            val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                ?: throw IOException("No answer from the device")
            val contentLength = lines.drop(1).firstNotNullOfOrNull { line ->
                if (line.startsWith("content-length:", ignoreCase = true)) line.substringAfter(':').trim().toLongOrNull() else null
            } ?: 0L
            return Response(status, LimitedBody(input, contentLength), socket)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun readHead(input: InputStream): List<String> {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("The device closed the connection")
            head.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > 64 * 1024) throw IOException("Header too large")
        }
        return head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
    }

    private fun expectOk(response: Response): Response {
        if (response.status in 200..299) return response
        val message = runCatching { response.text() }.getOrDefault("")
        response.close()
        throw IOException(message.ifBlank { "HTTP ${response.status}" })
    }

    override fun list(path: String): List<DriveItem> {
        val array = JSONArray(expectOk(request("GET", target(path, "list"))).use { it.text() })
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(DriveItem::fromJson) }
    }

    override fun stat(path: String): DriveItem? {
        val response = request("GET", target(path, "stat"))
        if (response.status == 404) { response.close(); return null }
        return DriveItem.fromJson(JSONObject(expectOk(response).use { it.text() }))
    }

    override fun open(path: String, offset: Long): InputStream {
        val headers = if (offset > 0) listOf("Range: bytes=$offset-") else emptyList()
        val response = expectOk(request("GET", target(path), headers))
        // A server that ignored the range sent the whole file; skip to where the caller asked.
        if (offset > 0 && response.status == 200) skipFully(response.body, offset)
        return object : FilterInputStream(response.body) {
            override fun close() = response.close()
        }
    }

    override fun write(path: String, input: InputStream, length: Long) {
        expectOk(request("PUT", target(path), length = length) { out -> copyLimited(input, out, length) }).close()
    }

    override fun mkdir(path: String) = expectOk(request("POST", target(path, "mkdir"))).close()

    override fun delete(path: String) = expectOk(request("DELETE", target(path))).close()

    override fun move(from: String, to: String) {
        val extra = "to=" + java.net.URLEncoder.encode(DriveFileSystem.normalise(to), "UTF-8")
        expectOk(request("POST", target(from, "move", extra))).close()
    }

    /** One response's body and nothing after it. */
    private class LimitedBody(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val read = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }
    }

    /** HTTP chunked encoding, for an upload whose length is not known in advance. */
    private class ChunkedOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.US_ASCII))
            out.write(b, off, len)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }

        override fun close() {
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
