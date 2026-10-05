package com.prism.desktop.social

import com.prism.core.PrismPlatform
import com.prism.launcher.social.LykeStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Serving Lyke's video files to a browser engine. PHASE 104.
 *
 * ## Why a browser plays the video
 *
 * Android uses `VideoView`, which is the framework's own player. Compose Desktop has no player at
 * all, and the JVM has none worth the name. The real options were:
 *
 *  - VLCJ, which means a native libVLC install the user has to have and Prism has to find.
 *  - A pure-Java decoder. JCodec can decode baseline H.264 at a few frames a second; a short video
 *    would play as a slideshow.
 *  - CHROMIUM, which is already a dependency for the browser page and plays H.264, VP8, VP9 and
 *    WebM with hardware acceleration.
 *
 * The third costs nothing new. A `<video>` element in a JCEF frame is a real player, with seeking,
 * looping and volume, and the same engine the Browser page already runs.
 *
 * ## RANGE REQUESTS ARE NOT OPTIONAL HERE
 *
 * A `<video>` element issues `Range: bytes=0-` first and then seeks by asking for byte windows. A
 * server that ignores `Range` and answers 200 with the whole file makes the video play from the
 * start and refuse to seek -- and on a large file Chromium buffers the entire thing before showing a
 * frame. So this answers 206 with `Content-Range`, which is about thirty lines and is the difference
 * between a player and a progress bar that does nothing.
 *
 * ## Only files Lyke knows about
 *
 * The path is a VIDEO ID, not a filename, and the id is looked up in `LykeStore` to get the real
 * path. So the server cannot be asked for an arbitrary file: there is no filename in the URL to
 * manipulate. That is a stronger guarantee than a path check, and it is why the design is this way
 * round rather than serving a directory.
 */
object LykeVideoServer {

    private const val TAG = "PrismLyke"

    private var server: HttpServer? = null

    @Volatile
    private var token: String = ""

    @Volatile
    private var port: Int = 0

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return runCatching {
            val instance = HttpServer.create(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0,
            )
            token = java.util.UUID.randomUUID().toString().replace("-", "")
            instance.createContext("/") { exchange -> handle(exchange) }
            // Several at once: the player page, the video stream and a seek can all be in flight,
            // and the default single-threaded executor would serialise them into a stall.
            instance.executor = java.util.concurrent.Executors.newFixedThreadPool(3) { runnable ->
                Thread(runnable, "lyke-http").apply { isDaemon = true }
            }
            instance.start()
            port = instance.address.port
            server = instance
            PrismPlatform.log.info(TAG, "Lyke video on 127.0.0.1:" + port)
            true
        }.getOrElse {
            PrismPlatform.log.error(TAG, "The Lyke video server would not start", it)
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop(0) } }
        server = null
        port = 0
        token = ""
    }

    /**
     * The page that plays one video, or null if the server is not up.
     *
     * The player is generated rather than a file, because it is nine lines of HTML whose only
     * variable is the video id -- a static page would need a query parameter and a script to read it.
     */
    fun playerUrl(videoId: String, loop: Boolean = true, muted: Boolean = false): String? {
        if (!start()) return null
        return "http://127.0.0.1:" + port + "/" + token + "/play/" +
            java.net.URLEncoder.encode(videoId, "UTF-8") +
            "?loop=" + loop + "&muted=" + muted
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.orEmpty()
            val expected = "/" + token + "/"
            if (token.isEmpty() || !path.startsWith(expected)) {
                respond(exchange, 404, "text/plain", "Not found".toByteArray())
                return
            }
            val relative = path.removePrefix(expected)
            when {
                relative.startsWith("play/") -> servePlayer(
                    exchange,
                    java.net.URLDecoder.decode(relative.removePrefix("play/"), "UTF-8"),
                )
                relative.startsWith("file/") -> serveVideo(
                    exchange,
                    java.net.URLDecoder.decode(relative.removePrefix("file/"), "UTF-8"),
                )
                else -> respond(exchange, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (failure: Throwable) {
            PrismPlatform.log.warn(TAG, "Lyke request failed: " + failure.message)
            runCatching { respond(exchange, 500, "text/plain", "Error".toByteArray()) }
        }
    }

    private fun servePlayer(exchange: HttpExchange, videoId: String) {
        val query = exchange.requestURI.query.orEmpty()
        val loop = !query.contains("loop=false")
        val muted = query.contains("muted=true")
        val source = "/" + token + "/file/" + java.net.URLEncoder.encode(videoId, "UTF-8")

        // `object-fit: cover` and a black ground: a short vertical video in a landscape frame is
        // what this always is, and letterboxing it would waste most of the panel.
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}")
            append("video{width:100%;height:100%;object-fit:cover;display:block}</style>")
            append("</head><body><video src=\"").append(source).append("\" autoplay playsinline")
            if (loop) append(" loop")
            if (muted) append(" muted")
            append(" controls></video></body></html>")
        }
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())
    }

    /**
     * Streams a video by ID, honouring `Range`.
     *
     * See the class comment: without the 206 path a `<video>` plays from the start and cannot seek,
     * and Chromium buffers the whole file before the first frame.
     */
    private fun serveVideo(exchange: HttpExchange, videoId: String) {
        val video = LykeStore.videos().firstOrNull { it.id == videoId }
        if (video == null || video.localPath.isBlank()) {
            respond(exchange, 404, "text/plain", "No such video".toByteArray())
            return
        }
        val file = File(video.localPath)
        if (!file.isFile) {
            respond(exchange, 404, "text/plain", "The file is not on this device".toByteArray())
            return
        }

        val length = file.length()
        val range = exchange.requestHeaders.getFirst("Range")
        exchange.responseHeaders.add("Content-Type", contentTypeOf(file.name))
        // Advertised explicitly: Chromium will not attempt a seek without it.
        exchange.responseHeaders.add("Accept-Ranges", "bytes")

        if (range == null || !range.startsWith("bytes=")) {
            exchange.sendResponseHeaders(200, length)
            exchange.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
            return
        }

        val spec = range.removePrefix("bytes=").split('-')
        val from = spec.getOrNull(0)?.toLongOrNull() ?: 0L
        // An open-ended range is the common case -- `bytes=0-` is what the first request is -- and
        // answering it with the rest of the file is correct.
        val to = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: (length - 1)
        if (from >= length) {
            exchange.responseHeaders.add("Content-Range", "bytes */" + length)
            respond(exchange, 416, "text/plain", ByteArray(0))
            return
        }
        val end = to.coerceAtMost(length - 1)
        val count = end - from + 1

        exchange.responseHeaders.add(
            "Content-Range", "bytes " + from + "-" + end + "/" + length,
        )
        exchange.sendResponseHeaders(206, count)
        RandomAccessFile(file, "r").use { source ->
            source.seek(from)
            exchange.responseBody.use { out ->
                val buffer = ByteArray(256 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = source.read(buffer, 0, want)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun contentTypeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
}
