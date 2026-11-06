package com.prism.launcher.storage.remote

import com.prism.core.PrismPlatform
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves drives over HTTP: WebDAV for operating systems, plus a few JSON routes for Prism.
 *
 * ## The URL is the authorisation
 *
 * Everything lives under `/<token>/`, and the token is a random secret that only travels inside the
 * trusted-device channel (sealed with the pair's key). A request without it sees an empty server.
 * A token in the path rather than an Authorization header is deliberate: Windows' WebDAV client
 * refuses to send Basic credentials over plain HTTP, and a path survives every client.
 *
 * ## Layout
 *
 *  - `/<token>/` lists the drives as folders.
 *  - `/<token>/<drive>/<path>` is a file or folder on that drive.
 *  - `?prism=list|stat|drives|mkdir|move` are Prism's own JSON routes, because Java's
 *    HttpURLConnection refuses to send PROPFIND, MKCOL or MOVE.
 *
 * WebDAV verbs: OPTIONS, PROPFIND, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, plus LOCK, UNLOCK and
 * PROPPATCH answered just well enough that Windows Explorer will copy files onto the drive.
 */
class DriveServer(
    private val token: () -> String,
    private val drives: () -> List<DeviceDrive>,
    private val access: (String) -> DriveAccess?,
    private val name: String = "drives",
    /**
     * The keys a connection may prove it holds ([SecureDriveChannel]): one per trusted device this
     * one shares its drives with. Null only for a loopback-only server (the desktop's gateway, which
     * the OS's own file explorer talks to and which never leaves the machine); [start] refuses a
     * network address without it. An empty list (a locked wallet) turns every connection away.
     */
    private val channelKeys: (() -> List<ByteArray>)? = null,
) {

    private companion object {
        const val TAG = "PrismDriveServer"
        const val MAX_HEADER_BYTES = 64 * 1024
        const val IDLE_MS = 30_000
    }

    @Volatile private var socket: ServerSocket? = null
    private val stopping = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "prism-$name-worker").apply { isDaemon = true }
    }

    val port: Int get() = socket?.localPort ?: -1
    val isRunning: Boolean get() = socket?.isClosed == false

    /**
     * Listens on [bindAddress] at the first free port in [ports]. Returns the port, or -1.
     * "0.0.0.0" serves the LAN and the mesh; "127.0.0.1" serves only this machine.
     */
    @Synchronized
    fun start(bindAddress: String, ports: IntRange): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        if (channelKeys == null && !InetAddress.getByName(bindAddress).isLoopbackAddress) {
            // Drive contents never cross a network unencrypted.
            PrismPlatform.log.warn(TAG, "Refusing to serve $name unencrypted on $bindAddress")
            return -1
        }
        stopping.set(false)
        for (candidate in ports) {
            val bound = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(bindAddress), candidate))
                }
            }.getOrNull() ?: continue
            socket = bound
            Thread({ acceptLoop(bound) }, "prism-$name").apply { isDaemon = true; start() }
            PrismPlatform.log.info(TAG, "Serving $name on $bindAddress:$candidate")
            return candidate
        }
        PrismPlatform.log.warn(TAG, "No free port for $name in $ports")
        return -1
    }

    @Synchronized
    fun stop() {
        stopping.set(true)
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopping.get() && !server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            workers.execute { serve(client) }
        }
    }

    // ── One connection, possibly several requests (keep-alive) ───────────────────

    private class Request(
        val method: String,
        val rawPath: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val bodyLength: Long,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = IDLE_MS
            socket.tcpNoDelay = true
            var rawIn: InputStream = socket.getInputStream()
            var rawOut: OutputStream = socket.getOutputStream()
            if (channelKeys != null) {
                // No keys (a locked wallet) or a peer that is not trusted for drives: hang up before
                // any HTTP.
                val keys = channelKeys.invoke().ifEmpty { return }
                val pipe = runCatching { SecureDriveChannel.accept(rawIn, rawOut, keys).pipe }.getOrElse {
                    PrismPlatform.log.info(TAG, "Turned away ${socket.inetAddress.hostAddress}: ${it.message}")
                    return
                }
                rawIn = pipe.input
                rawOut = pipe.output
            }
            val input = BufferedInputStream(rawIn, 64 * 1024)
            val output = BufferedOutputStream(rawOut, 64 * 1024)
            while (!socket.isClosed) {
                val request = try {
                    readRequest(input) ?: return
                } catch (_: SocketTimeoutException) {
                    return
                } catch (_: IOException) {
                    return
                }
                val keepAlive = !request.header("connection").equals("close", ignoreCase = true)
                val handled = runCatching { handle(request, output, keepAlive) }
                handled.onFailure { failure ->
                    PrismPlatform.log.warn(TAG, "${request.method} ${request.rawPath}: ${failure.message}")
                    runCatching { text(output, 500, failure.message ?: "Server error", keepAlive = false) }
                    return
                }
                // Whatever the handler did not read of the body must go, or it would be parsed as
                // the next request.
                runCatching { drain(request.body) }
                output.flush()
                if (!keepAlive) return
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > MAX_HEADER_BYTES) throw IOException("Header too large")
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val target = first[1]
        val pathPart = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.toLongOrNull() ?: if (chunked) -1 else 0
        val body: InputStream = when {
            chunked -> ChunkedInput(input)
            else -> LimitedInput(input, length.coerceAtLeast(0))
        }
        return Request(first[0].uppercase(), pathPart, query, headers, body, length)
    }

    // ── Routing ───────────────────────────────────────────────────────────────────

    private class Target(val drive: DeviceDrive?, val access: DriveAccess?, val path: String, val prefix: String)

    /**
     * Splits "/token/drive/a/b" into the drive and "/a/b". Null for a path outside the token, which
     * answers exactly like a missing file: an outsider learns nothing, not even that a token exists.
     */
    private fun route(rawPath: String): Target? {
        val segments = rawPath.split('/').filter { it.isNotEmpty() }.map(::decode)
        if (segments.isEmpty() || segments[0] != token()) return null
        val prefix = "/" + encodeSegment(segments[0])
        if (segments.size == 1) return Target(null, null, "/", prefix)
        val id = segments[1]
        val drive = drives().firstOrNull { it.id == id } ?: return Target(null, null, "", prefix)
        val access = access(id) ?: return Target(null, null, "", prefix)
        val path = DriveFileSystem.normalise("/" + segments.drop(2).joinToString("/"))
        return Target(drive, access, path, prefix + "/" + encodeSegment(drive.id))
    }

    private fun handle(request: Request, output: OutputStream, keepAlive: Boolean) {
        if (request.method == "OPTIONS") {
            writeHead(
                output, 200, 0, keepAlive,
                listOf(
                    "DAV: 1, 2",
                    "MS-Author-Via: DAV",
                    "Allow: OPTIONS, PROPFIND, PROPPATCH, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK, POST",
                ),
            )
            return
        }
        // The server root is a folder with nothing visible in it; clients probe it before the share.
        if (request.rawPath == "/" || request.rawPath.isEmpty()) {
            if (request.method == "PROPFIND") {
                multistatus(output, listOf(davResponse("/", "", true, 0, 0)), keepAlive)
            } else {
                text(output, 200, "Prism", keepAlive)
            }
            return
        }

        val target = route(request.rawPath) ?: return text(output, 404, "Not found", keepAlive)
        val prismOp = request.query["prism"]

        // The token's own folder: the drive list.
        if (target.drive == null) {
            if (target.path != "/") return text(output, 404, "No such drive", keepAlive)
            when {
                prismOp == "drives" -> json(output, JSONArray().also { array -> drives().forEach { array.put(it.toJson()) } }.toString(), keepAlive)
                request.method == "PROPFIND" -> {
                    val depth = request.header("depth") ?: "1"
                    val responses = mutableListOf(davResponse(target.prefix + "/", "", true, 0, 0))
                    if (depth != "0") drives().forEach { drive ->
                        responses += davResponse(target.prefix + "/" + encodeSegment(drive.id) + "/", drive.name, true, 0, 0)
                    }
                    multistatus(output, responses, keepAlive)
                }
                request.method == "GET" || request.method == "HEAD" ->
                    text(output, 200, drives().joinToString("\n") { it.id + "  " + it.name }, keepAlive)
                else -> text(output, 405, "Not allowed here", keepAlive)
            }
            return
        }

        val access = target.access!!
        when (prismOp) {
            "list" -> {
                val items = access.list(target.path)
                json(output, JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString(), keepAlive)
                return
            }
            "stat" -> {
                val item = access.stat(target.path) ?: return text(output, 404, "Not found", keepAlive)
                json(output, item.toJson().toString(), keepAlive)
                return
            }
            "mkdir" -> { guardWrite(access); access.mkdir(target.path); return text(output, 201, "Created", keepAlive) }
            "move" -> {
                guardWrite(access)
                val to = request.query["to"] ?: return text(output, 400, "No destination", keepAlive)
                access.move(target.path, DriveFileSystem.normalise(to))
                return text(output, 201, "Moved", keepAlive)
            }
        }

        when (request.method) {
            "PROPFIND" -> propfind(request, output, target, access, keepAlive)
            "GET", "HEAD" -> get(request, output, target, access, keepAlive)
            "PUT" -> {
                if (access.readOnly) return text(output, 403, "This drive is read-only", keepAlive)
                val existed = access.stat(target.path) != null
                access.write(target.path, request.body, request.bodyLength)
                text(output, if (existed) 204 else 201, "", keepAlive)
            }
            "MKCOL" -> {
                if (access.readOnly) return text(output, 403, "This drive is read-only", keepAlive)
                if (access.stat(target.path) != null) return text(output, 405, "Already exists", keepAlive)
                access.mkdir(target.path)
                text(output, 201, "", keepAlive)
            }
            "DELETE" -> {
                if (access.readOnly) return text(output, 403, "This drive is read-only", keepAlive)
                if (access.stat(target.path) == null) return text(output, 404, "Not found", keepAlive)
                access.delete(target.path)
                text(output, 204, "", keepAlive)
            }
            "MOVE", "COPY" -> {
                if (access.readOnly) return text(output, 403, "This drive is read-only", keepAlive)
                val destination = request.header("destination") ?: return text(output, 400, "No destination", keepAlive)
                val destinationPath = destination.replace(Regex("^[a-zA-Z]+://[^/]+"), "")
                if (!destinationPath.startsWith(target.prefix + "/") && destinationPath != target.prefix) {
                    return text(output, 502, "Moving between drives is not supported", keepAlive)
                }
                val to = DriveFileSystem.normalise(decode(destinationPath.removePrefix(target.prefix)))
                val overwrite = request.header("overwrite")?.equals("F", ignoreCase = true) != true
                val existed = access.stat(to) != null
                if (existed && !overwrite) return text(output, 412, "Destination exists", keepAlive)
                if (existed) access.delete(to)
                if (request.method == "MOVE") access.move(target.path, to) else copy(access, target.path, to)
                text(output, if (existed) 204 else 201, "", keepAlive)
            }
            "LOCK" -> lock(output, target, keepAlive)
            "UNLOCK" -> text(output, 204, "", keepAlive)
            "PROPPATCH" -> multistatus(output, listOf(davResponse(target.prefix + encodePath(target.path), "", false, 0, 0)), keepAlive)
            else -> text(output, 405, "Method not allowed", keepAlive)
        }
    }

    private fun guardWrite(access: DriveAccess) {
        if (access.readOnly) throw IOException("This drive is read-only")
    }

    private fun copy(access: DriveAccess, from: String, to: String) {
        val item = access.stat(from) ?: throw IOException("Not found: $from")
        if (item.isDirectory) {
            access.mkdir(to)
            access.list(from).forEach { copy(access, it.path, DriveFileSystem.child(to, it.name)) }
        } else {
            access.open(from).use { access.write(to, it, item.size) }
        }
    }

    // ── WebDAV ────────────────────────────────────────────────────────────────────

    private fun propfind(request: Request, output: OutputStream, target: Target, access: DriveAccess, keepAlive: Boolean) {
        val item = access.stat(target.path) ?: return text(output, 404, "Not found", keepAlive)
        val href = target.prefix + encodePath(target.path) + if (item.isDirectory && target.path != "/") "/" else if (target.path == "/") "/" else ""
        val name = if (target.path == "/") target.drive!!.name else item.name
        val responses = mutableListOf(
            davResponse(href, name, item.isDirectory, item.size, item.modified, if (target.path == "/") access.freeBytes() else -1),
        )
        if (item.isDirectory && request.header("depth") != "0") {
            runCatching { access.list(target.path) }.getOrDefault(emptyList()).forEach { child ->
                responses += davResponse(
                    target.prefix + encodePath(child.path) + if (child.isDirectory) "/" else "",
                    child.name, child.isDirectory, child.size, child.modified,
                )
            }
        }
        multistatus(output, responses, keepAlive)
    }

    private fun davResponse(href: String, name: String, directory: Boolean, size: Long, modified: Long, free: Long = -1): String =
        buildString {
            append("<D:response><D:href>").append(xml(href)).append("</D:href><D:propstat><D:prop>")
            append("<D:displayname>").append(xml(name)).append("</D:displayname>")
            if (directory) append("<D:resourcetype><D:collection/></D:resourcetype>")
            else {
                append("<D:resourcetype/>")
                append("<D:getcontentlength>").append(size).append("</D:getcontentlength>")
                append("<D:getcontenttype>").append(contentType(name)).append("</D:getcontenttype>")
            }
            val stamp = if (modified > 0) modified else System.currentTimeMillis()
            append("<D:getlastmodified>").append(httpDate(stamp)).append("</D:getlastmodified>")
            append("<D:creationdate>").append(Instant.ofEpochMilli(stamp).toString()).append("</D:creationdate>")
            if (free >= 0) append("<D:quota-available-bytes>").append(free).append("</D:quota-available-bytes>")
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }

    private fun multistatus(output: OutputStream, responses: List<String>, keepAlive: Boolean) {
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">" +
            responses.joinToString("") + "</D:multistatus>").toByteArray(Charsets.UTF_8)
        writeHead(output, 207, body.size.toLong(), keepAlive, listOf("Content-Type: application/xml; charset=utf-8"))
        output.write(body)
    }

    /** A lock that locks nothing, which is all Windows needs before it will write a file. */
    private fun lock(output: OutputStream, target: Target, keepAlive: Boolean) {
        val lockToken = "opaquelocktoken:" + UUID.randomUUID()
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth>" +
            "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + lockToken + "</D:href></D:locktoken>" +
            "<D:lockroot><D:href>" + xml(target.prefix + encodePath(target.path)) + "</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>").toByteArray(Charsets.UTF_8)
        writeHead(output, 200, body.size.toLong(), keepAlive, listOf("Content-Type: application/xml; charset=utf-8", "Lock-Token: <$lockToken>"))
        output.write(body)
    }

    private fun get(request: Request, output: OutputStream, target: Target, access: DriveAccess, keepAlive: Boolean) {
        val item = access.stat(target.path) ?: return text(output, 404, "Not found", keepAlive)
        if (item.isDirectory) {
            val listing = runCatching { access.list(target.path) }.getOrDefault(emptyList())
            return text(output, 200, listing.joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }, keepAlive, head = request.method == "HEAD")
        }
        val size = item.size
        var start = 0L
        var end = size - 1
        var status = 200
        val range = request.header("range")
        if (range != null && range.startsWith("bytes=") && size > 0) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').toLongOrNull()
            val to = spec.substringAfter('-').toLongOrNull()
            when {
                from != null -> { start = from; end = (to ?: (size - 1)).coerceAtMost(size - 1) }
                to != null -> { start = (size - to).coerceAtLeast(0); end = size - 1 }
            }
            if (start > end || start >= size) {
                writeHead(output, 416, 0, keepAlive, listOf("Content-Range: bytes */$size"))
                return
            }
            status = 206
        }
        val length = if (size == 0L) 0 else end - start + 1
        val headers = mutableListOf(
            "Content-Type: " + contentType(item.name),
            "Accept-Ranges: bytes",
            "Last-Modified: " + httpDate(item.modified.takeIf { it > 0 } ?: System.currentTimeMillis()),
        )
        if (status == 206) headers += "Content-Range: bytes $start-$end/$size"
        writeHead(output, status, length, keepAlive, headers)
        if (request.method == "HEAD" || length == 0L) return
        access.open(target.path, start).use { stream -> copyLimited(stream, output, length) }
    }

    // ── Responses ─────────────────────────────────────────────────────────────────

    private fun json(output: OutputStream, body: String, keepAlive: Boolean) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(output, 200, bytes.size.toLong(), keepAlive, listOf("Content-Type: application/json; charset=utf-8"))
        output.write(bytes)
    }

    private fun text(output: OutputStream, status: Int, body: String, keepAlive: Boolean, head: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(output, status, bytes.size.toLong(), keepAlive, listOf("Content-Type: text/plain; charset=utf-8"))
        if (!head) output.write(bytes)
    }

    private fun writeHead(output: OutputStream, status: Int, length: Long, keepAlive: Boolean, headers: List<String>) {
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"; 207 -> "Multi-Status"
            400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            412 -> "Precondition Failed"; 416 -> "Range Not Satisfiable"; 502 -> "Bad Gateway"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Server: Prism\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(16 * 1024)
        while (input.read(buffer) >= 0) Unit
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun httpDate(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    /** At most [limit] bytes of the connection: one request's body and not the next request. */
    private class LimitedInput(input: InputStream, private var left: Long) : FilterInputStream(input) {
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

        override fun close() = Unit
    }

    /** A chunked request body, decoded. */
    private class ChunkedInput(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun nextChunk() {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) { done = true; return }
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            if (line.isEmpty()) return nextChunk()
            left = line.toString().substringBefore(';').trim().toLongOrNull(16) ?: 0
            if (left == 0L) {
                // The trailer section ends with an empty line. The size line's own newline was
                // just consumed, so an immediate CRLF is that empty line.
                var previous = '\n'.code
                while (true) {
                    val b = input.read()
                    if (b < 0 || (b == '\n'.code && previous == '\n'.code)) break
                    if (b != '\r'.code) previous = b
                }
                done = true
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0L) {
                nextChunk()
                if (done) return -1
            }
            val read = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (read < 0) { done = true; return -1 }
            left -= read
            if (left == 0L) { input.read(); input.read() } // the chunk's own CRLF
            return read
        }

        override fun close() = Unit
    }
}

/** A path's segments, each percent-encoded. */
internal fun encodePath(path: String): String =
    if (path == "/" || path.isEmpty()) "" else path.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + encodeSegment(it) }

internal fun encodeSegment(segment: String): String =
    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
