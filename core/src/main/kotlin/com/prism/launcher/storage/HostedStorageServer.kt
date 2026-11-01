package com.prism.launcher.storage

import com.prism.core.PrismPlatform
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves a hosted folder over HTTP/WebDAV on every interface this device has.
 *
 * ## Why hosting is a server, not an announcement
 *
 * Advertising a folder to trusted devices is only half of "host as networked storage"; the other
 * half is something that answers when a client connects. Binding to `0.0.0.0` covers both cases at
 * once: a device on the same Wi-Fi reaches the LAN address, and a device on a Prism mesh reaches the
 * mesh address. No mesh is required, and no trusted pairing is required to serve -- pairing only
 * decides who may be *bridged* to the folder.
 *
 * HTTP is the transport because it is the one protocol a browser, `curl`, a file manager and Prism
 * itself can all speak without a dependency. `PROPFIND` and `OPTIONS` make it a real WebDAV share
 * for clients that ask for one; `GET`/`HEAD`/`PUT` cover everything else. The port is the one the
 * host chose in the sheet.
 *
 * ## What is deliberately absent
 *
 * No chunked transfer encoding, no keep-alive, no digest auth, no TLS. Each request gets a
 * connection, an answer and a close. That is enough for listings, downloads, uploads and media
 * seeking by range, and it keeps the parser small enough to reason about -- a file share is a bad
 * place for a hand-written protocol with corners.
 */
object HostedStorageServer {

    private const val TAG = "Prism/host"
    private const val MAX_HEADER_BYTES = 32 * 1024

    private class Running(val storage: HostedStorage, val socket: ServerSocket, val stop: AtomicBoolean) {
        val thread = Thread({ loop(socket, storage, stop) }, "prism-host-${storage.port}").apply {
            isDaemon = true
        }
    }

    private val servers = ConcurrentHashMap<String, Running>()
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "prism-host-worker").apply { isDaemon = true }
    }

    /** Whether a share is answering right now. */
    fun isRunning(id: String): Boolean = servers.containsKey(id)

    /** Why the last start attempt failed, keyed by share id. */
    private val failures = ConcurrentHashMap<String, String>()

    fun failureFor(id: String): String? = failures[id]

    /**
     * Starts every saved share that is not already running.
     *
     * Called when the app comes up and after a share changes, so a folder the user hosted yesterday
     * is still reachable today without opening Files first.
     */
    fun sync() {
        val saved = HostedStorageStore.all()
        val ids = saved.map { it.id }.toSet()
        servers.keys.filterNot { it in ids }.forEach { stop(it) }
        saved.forEach { saved ->
            // Shares saved before hosting listened on a high port carry the protocol's client
            // port. Move them once, persist the new port, and then start as usual.
            val storage = if (saved.port < 1024) saved.copy(port = suggestPort()) else saved
            if (storage.port != saved.port) HostedStorageStore.update(storage)
            if (!isRunning(storage.id)) start(storage)
        }
    }

    /**
     * A port this device can actually listen on.
     *
     * The protocol catalog's defaults are the ports a CLIENT connects to (21, 22, 445, 2049). An
     * unprivileged app cannot bind any of those, so a host that copied the type's default always
     * failed with permission denied. Hosting listens on a high port instead, and this picks the
     * first free one so two shares do not collide.
     */
    fun suggestPort(): Int {
        for (candidate in 8080..8200) {
            if (servers.values.any { it.storage.port == candidate }) continue
            val free = runCatching {
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress("0.0.0.0", candidate))
                }
                true
            }.getOrDefault(false)
            if (free) return candidate
        }
        return 8080
    }

    /** Starts one share. Returns null on success, or a human-readable reason. */
    fun start(storage: HostedStorage): String? {
        if (isRunning(storage.id)) return null
        val folder = storage.folder
        if (!folder.isDirectory) {
            val reason = "That folder is no longer available."
            failures[storage.id] = reason
            return reason
        }
        if (storage.port < 1024) {
            val reason = "Android does not allow apps to listen below port 1024. Pick 1024 or higher."
            failures[storage.id] = reason
            return reason
        }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", storage.port))
            }
        } catch (e: Exception) {
            val reason = "Port ${storage.port} is already in use. Choose another port."
            failures[storage.id] = reason
            PrismPlatform.log.warn(TAG, "Could not bind ${storage.port}: ${e.message}")
            return reason
        }
        val running = Running(storage, socket, AtomicBoolean(false))
        servers[storage.id] = running
        failures.remove(storage.id)
        running.thread.start()
        PrismPlatform.log.info(
            TAG,
            "Serving '${storage.name}' on port ${storage.port} at ${endpoints(storage).joinToString(", ")}",
        )
        return null
    }

    fun stop(id: String) {
        val running = servers.remove(id) ?: return
        running.stop.set(true)
        runCatching { running.socket.close() }
        PrismPlatform.log.info(TAG, "Stopped serving '${running.storage.name}'")
    }

    fun restart(storage: HostedStorage): String? {
        stop(storage.id)
        return start(storage)
    }

    /**
     * Every address a client could use, LAN first.
     *
     * The interface addresses are read live rather than cached: Wi-Fi to mesh transitions change
     * them, and a stale address in the sheet is worse than none.
     */
    fun endpoints(storage: HostedStorage): List<String> {
        val port = storage.port
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val ordered = addresses.distinct().sortedBy { address ->
            when {
                address.startsWith("192.168.") -> 0
                address.startsWith("10.") -> 1
                address.startsWith("172.") -> 2
                address.startsWith("100.") -> 3 // Mesh leases often land in the CGNAT range.
                else -> 4
            }
        }
        return ordered.map { "http://$it:$port/" }
    }

    /** True when the address the request arrived on is reachable from another machine. */
    private fun loop(socket: ServerSocket, storage: HostedStorage, stop: AtomicBoolean) {
        while (!stop.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!stop.get()) PrismPlatform.log.warn(TAG, "accept failed: ${e.message}")
                return
            }
            workers.execute {
                runCatching { handle(client, storage) }
                    .onFailure { PrismPlatform.log.warn(TAG, "request failed: ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    // ── Request handling ───────────────────────────────────────────────────

    private fun handle(client: Socket, storage: HostedStorage) {
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream())
        val request = readRequest(input) ?: return reply(output, 400, "Bad request", "text/plain")
        val method = request.method.uppercase()

        if (!authorised(storage, request.headers["authorization"])) {
            reply(
                output, 401, "Authentication required", "text/plain",
                extraHeaders = listOf("WWW-Authenticate: Basic realm=\"${storage.name}\""),
            )
            return
        }

        val relative = decodePath(request.path)
        if (relative == null) {
            reply(output, 400, "Bad path", "text/plain")
            return
        }
        val root = storage.folder.canonicalFile
        val target = File(root, relative).canonicalFile
        // Canonical containment: `..` and a symlink both have to end up inside the shared folder.
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            reply(output, 403, "Outside the shared folder", "text/plain")
            return
        }

        when (method) {
            "OPTIONS" -> reply(
                output, 200, "", "text/plain",
                extraHeaders = listOf(
                    "DAV: 1, 2",
                    "Allow: OPTIONS, GET, HEAD, PUT, PROPFIND, DELETE, MKCOL",
                    "MS-Author-Via: DAV",
                ),
            )
            "PROPFIND" -> propfind(output, root, target)
            "GET" -> get(output, target, head = false, request.headers["range"])
            "HEAD" -> get(output, target, head = true, request.headers["range"])
            "PUT" -> put(output, target, input, request.headers["content-length"])
            "MKCOL" -> {
                if (target.isDirectory || target.mkdirs()) reply(output, 201, "Created", "text/plain")
                else reply(output, 409, "Could not create that folder", "text/plain")
            }
            "DELETE" -> {
                if (target == root) reply(output, 403, "The share root cannot be removed", "text/plain")
                else if (target.deleteRecursively()) reply(output, 204, "", "text/plain")
                else reply(output, 404, "Not found", "text/plain")
            }
            else -> reply(output, 405, "Method not allowed", "text/plain")
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val bytes = ArrayList<Byte>(1024)
        var matched = 0
        // Read a byte at a time up to the blank line: a body comes after the header block and must
        // stay in the stream for PUT, so buffering past the terminator would eat the upload.
        while (bytes.size < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) break
            bytes.add(next.toByte())
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (bytes.isEmpty()) return null
        val text = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }
        return Request(first[0], first[1], headers)
    }

    /** Percent-decodes a request path into a relative file path, or null when it is malformed. */
    private fun decodePath(raw: String): String? {
        val withoutQuery = raw.substringBefore('?').substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(withoutQuery, "UTF-8") }.getOrNull() ?: return null
        return decoded.trim('/').replace('\\', '/').replace("\u0000", "")
    }

    private fun authorised(storage: HostedStorage, header: String?): Boolean {
        if (storage.username.isBlank()) return true
        if (header.isNullOrBlank() || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        if (separator < 0) return false
        return decoded.substring(0, separator) == storage.username &&
            decoded.substring(separator + 1) == storage.password
    }

    private fun propfind(output: OutputStream, root: File, target: File) {
        if (!target.exists()) {
            reply(output, 404, "Not found", "text/plain")
            return
        }
        val entries = if (target.isDirectory) {
            listOf(target) + (target.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList())
        } else {
            listOf(target)
        }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<D:multistatus xmlns:D=\"DAV:\">")
            entries.forEach { entry ->
                val href = entry.relativeTo(root).invariantSeparatorsPath
                val isDir = entry.isDirectory
                val suffix = if (isDir && href.isNotEmpty() && !href.endsWith("/")) "/" else ""
                append("<D:response><D:href>/")
                append(xmlEscape(href + suffix))
                append("</D:href><D:propstat><D:prop>")
                append("<D:displayname>${xmlEscape(entry.name.ifBlank { "share" })}</D:displayname>")
                append("<D:resourcetype>")
                if (isDir) append("<D:collection/>")
                append("</D:resourcetype>")
                if (!isDir) {
                    append("<D:getcontentlength>${entry.length()}</D:getcontentlength>")
                    append("<D:getcontenttype>${contentType(entry)}</D:getcontenttype>")
                }
                append("<D:getlastmodified>${httpDate(entry.lastModified())}</D:getlastmodified>")
                append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
            }
            append("</D:multistatus>")
        }
        reply(output, 207, body, "application/xml; charset=utf-8")
    }

    private fun get(output: OutputStream, target: File, head: Boolean, range: String?) {
        if (!target.exists() || target.isDirectory && !target.canRead()) {
            reply(output, 404, "Not found", "text/plain")
            return
        }
        if (target.isDirectory) {
            val root = target
            val entries = root.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            val parent = if (root.parentFile != null) "../" else ""
            val html = buildString {
                append("<!doctype html><meta charset=\"utf-8\"><title>")
                append(htmlEscape(root.name.ifBlank { "shared folder" }))
                append("</title><style>body{font-family:system-ui,sans-serif;margin:2rem;background:#101018;color:#e6e6f0}")
                append("a{color:#6ee7ff;text-decoration:none}a:hover{text-decoration:underline}")
                append("li{margin:.35rem 0}small{opacity:.6}</style><h1>")
                append(htmlEscape(root.name.ifBlank { "shared folder" }))
                append("</h1><ul>")
                if (parent.isNotEmpty()) append("<li><a href=\"$parent\">../</a></li>")
                entries.filterNot { it.name.startsWith(".") }.forEach { entry ->
                    val name = entry.name + if (entry.isDirectory) "/" else ""
                    append("<li><a href=\"").append(urlPath(name)).append("\">")
                    append(htmlEscape(name)).append("</a> ")
                    append("<small>")
                    append(if (entry.isDirectory) "folder" else humanSize(entry.length()))
                    append("</small></li>")
                }
                append("</ul>")
            }
            reply(output, 200, if (head) "" else html, "text/html; charset=utf-8", head = head)
            return
        }
        val length = target.length()
        val start: Long
        val end: Long
        var status = 200
        if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
            val parts = range.substringAfter("bytes=").split("-", limit = 2)
            val from = parts.getOrNull(0)?.toLongOrNull()
            val to = parts.getOrNull(1)?.toLongOrNull()
            start = (from ?: 0L).coerceIn(0, maxOf(0, length - 1))
            end = (to ?: (length - 1)).coerceIn(start, maxOf(0, length - 1))
            status = 206
        } else {
            start = 0
            end = maxOf(0, length - 1)
        }
        val count = if (length == 0L) 0L else end - start + 1
        val extra = mutableListOf(
            "Content-Type: ${contentType(target)}",
            "Accept-Ranges: bytes",
        )
        if (status == 206) extra += "Content-Range: bytes $start-$end/$length"
        writeHead(output, status, count, extra)
        if (head) {
            output.flush()
            return
        }
        if (length == 0L) {
            output.flush()
            return
        }
        target.inputStream().use { file ->
            file.skip(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining > 0) {
                val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    private fun put(output: OutputStream, target: File, input: InputStream, lengthHeader: String?) {
        val declared = lengthHeader?.toLongOrNull() ?: -1L
        target.parentFile?.mkdirs()
        try {
            target.outputStream().use { file ->
                if (declared >= 0) {
                    var remaining = declared
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        remaining -= read
                    }
                } else {
                    input.copyTo(file)
                }
            }
        } catch (e: Exception) {
            reply(output, 500, "Could not write ${target.name}", "text/plain")
            return
        }
        reply(output, if (target.exists()) 204 else 201, "", "text/plain")
    }

    // ── Responses ──────────────────────────────────────────────────────────

    private fun reply(
        output: OutputStream,
        status: Int,
        body: String,
        contentType: String,
        extraHeaders: List<String> = emptyList(),
        head: Boolean = false,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(output, status, bytes.size.toLong(), extraHeaders + "Content-Type: $contentType")
        if (!head && bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun writeHead(output: OutputStream, status: Int, length: Long, headers: List<String>) {
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            207 -> "Multi-Status"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            else -> "Error"
        }
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n")
            append("Server: PrismHost\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun contentType(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "txt", "md", "log", "csv" -> "text/plain; charset=utf-8"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun xmlEscape(value: String): String = htmlEscape(value).replace("'", "&apos;")

    /** Percent-encodes one path segment for an href; spaces and `#` otherwise break listings. */
    private fun urlPath(value: String): String = value.split("/").joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    private fun httpDate(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(if (millis <= 0) System.currentTimeMillis() else millis)
                .atZone(ZoneOffset.UTC),
        )
}
