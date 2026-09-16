package com.prism.launcher.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.documentfile.provider.DocumentFile
import com.prism.launcher.messaging.AiManager
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Handle serving local web content over the Prism Mesh.
 * This class implements a minimal HTTP/1.1 server specifically for P2P Hosting.
 */
object PrismWebHost {

    private const val TAG = "PrismWebHost"
    
    /**
     * Structure: domain -> (path -> File or DocumentFile).
     *
     * BOTH LEVELS ARE BOUNDED. This is a long-lived object in a process that is also a launcher, and
     * the inner map gains an entry for every path ever requested -- including one per miss, since
     * misses are cached too. Serving a mirrored site of a few thousand files, or any site at all to
     * a peer that probes for paths, grew it without limit for the life of the process. The entries
     * are small; there are simply no longer an unbounded number of them.
     */
    private const val MAX_CACHED_DOMAINS = 16
    private const val MAX_CACHED_PATHS_PER_DOMAIN = 512

    private val siteCache = object : LinkedHashMap<String, MutableMap<String, Any>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, Any>>?) =
            size > MAX_CACHED_DOMAINS
    }

    private fun newPathCache(): MutableMap<String, Any> =
        object : LinkedHashMap<String, Any>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) =
                size > MAX_CACHED_PATHS_PER_DOMAIN
        }
    private val cacheMutex = Any()

    suspend fun serve(context: Context, socket: Socket, domain: String, preReadHeader: String? = null) = withContext(Dispatchers.IO) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        try {
            // 1. Normal site lookup - Mapping all hosted websites to port 8080
            val sites = com.prism.launcher.PrismSettings.getP2pHostedSites()
            val site = sites.find { it.domain.equals(domain, ignoreCase = true) && it.isActive }
            
            if (site == null) {
                // Secondary Check: Mirror Registry (P2P CDN)
                val mirrors = com.prism.launcher.PrismSettings.getP2pMirroredSites()
                val mirror = mirrors.find { it.domain.equals(domain, ignoreCase = true) && it.isActive }
                
                if (mirror == null) {
                    // Third: the web cache -- but ONLY for a request that originated on this
                    // device.
                    //
                    // This is what lets other apps read the cache while it is private. A cached
                    // site is not in the hosted list until the user shares it, so the lookups above
                    // both miss, and a 404 here would mean the only way to read your own cache was
                    // to publish it to every peer first -- trading the whole point of the private
                    // setting for offline access.
                    //
                    // The loopback condition is what keeps that honest. A peer arrives over the mesh
                    // from a real address and still gets the 404 it should; only 127.0.0.1, which no
                    // other machine can forge a route from, sees an unpublished cache. Once the user
                    // does share it, the hosted-site lookup above answers first and this never runs.
                    val cachedHost = PrismWebCache.hostForMeshDomain(domain)
                    val cacheDir = cachedHost?.let { PrismWebCache.dirFor(it) }
                    val fromThisDevice = socket.inetAddress?.isLoopbackAddress == true

                    if (cacheDir != null && cacheDir.isDirectory && fromThisDevice) {
                        serveContent(
                            context, socket, input, output, domain,
                            cacheDir.absolutePath, preReadHeader
                        )
                        return@withContext
                    }

                    sendError(output, 404, "Site Not Found: $domain")
                    return@withContext
                }
                
                // We found a mirror! Serve it using the mirror's local path.
                serveContent(context, socket, input, output, domain, mirror.localPath, preReadHeader)
                return@withContext
            }

            serveContent(context, socket, input, output, domain, site.localPath, preReadHeader)
        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logError(TAG, "Serving error for $domain", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun serveContent(
        context: Context, 
        socket: Socket, 
        input: InputStream, 
        output: OutputStream, 
        domain: String, 
        localPath: String, 
        preReadHeader: String?
    ) {
        // Read the HTTP request (Simple parsing for GET)
        val header = preReadHeader ?: readLine(input) ?: return
        
        val lines = header.lines()
        val requestLine = lines[0]
        // Present only when the caller pre-read the whole head, which is the path every non-Prism
        // client arrives on -- and those are the ones that play video.
        val rangeHeader = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        
        val method = parts[0]
        var path = parts[1]

        if (method != "GET") {
            sendError(output, 405, "Method Not Allowed")
            return
        }

        if (path.contains("..")) {
            sendError(output, 403, "Forbidden")
            return
        }

        if (path == "/") path = "/index.html"
        
        // Mesh Mirroring: Virtual Manifest Generation
        if (path == "/manifest.json") {
            sendManifest(context, output, domain, localPath)
            return
        }
        
        // Cache Check: Fast-path for repeated requests (icons, CSS, etc)
        val domainCache = synchronized(cacheMutex) {
            siteCache.getOrPut(domain) { newPathCache() }
        }
        
        val cachedResult = domainCache[path]
        if (cachedResult != null) {
            handleFileResult(context, output, cachedResult, path, rangeHeader)
            return
        }
        
        val rootUri = Uri.parse(localPath)
        val fileResult = resolveFile(context, rootUri, path)
        
        if (fileResult != null) {
            synchronized(cacheMutex) { domainCache[path] = fileResult }
            handleFileResult(context, output, fileResult, path, rangeHeader)
        } else {
            // Negative cache to prevent repeated scanning of missing files
            synchronized(cacheMutex) { domainCache[path] = "NULL_MARKER" }
            handleMissingFile(context, output, path)
        }
    }

    private suspend fun handleFileResult(
        context: Context,
        output: OutputStream,
        result: Any,
        path: String,
        rangeHeader: String? = null,
    ) {
        when {
            result is File -> {
                if (result.name.endsWith(".prism")) {
                    sendAiTemplate(context, output, result)
                } else {
                    sendFile(output, result, rangeHeader)
                }
            }
            result is DocumentFile -> {
                if (result.name?.endsWith(".prism") == true) {
                    sendAiTemplate(context, output, result)
                } else {
                    sendDocumentFile(context, output, result)
                }
            }
            result == "NULL_MARKER" -> {
                handleMissingFile(context, output, path)
            }
        }
    }

    private fun handleMissingFile(context: Context, output: OutputStream, path: String) {
        val isImage = path.endsWith(".png", true) || path.endsWith(".jpg", true) || 
                      path.endsWith(".jpeg", true) || path.endsWith(".gif", true) || 
                      path.endsWith(".ico", true) || path.endsWith(".svg", true) || 
                      path.endsWith(".webp", true)
        
        if (isImage) {
            sendAppIcon(context, output)
        } else {
            sendError(output, 404, "File Not Found: $path")
        }
    }
    
    /**
     * Drops every site's resolved-path cache.
     *
     * Called under memory pressure. Everything here is a filesystem lookup that costs one stat to
     * redo, so there is nothing to lose by letting it go and nothing to rebuild eagerly.
     */
    fun trimCaches() {
        synchronized(cacheMutex) { siteCache.clear() }
    }

    /**
     * Clear the cache for a specific site (e.g. if the user updates the source folder)
     */
    fun clearCache(domain: String) {
        synchronized(cacheMutex) {
            siteCache.remove(domain)
        }
    }

    /**
     * The on-disk spellings a request path may correspond to, in preference order.
     *
     * Only ever ADDS candidates for an extensionless path, so a request that names a real file
     * still resolves to exactly that file and nothing about hosting a hand-made folder changes.
     */
    private fun candidatePaths(cleanPath: String): List<String> {
        if (cleanPath.isEmpty()) return listOf("index.html")
        if (cleanPath.substringAfterLast('/').contains('.')) return listOf(cleanPath)
        val stem = cleanPath.trimEnd('/')
        return listOf(cleanPath, "$stem.html", "$stem/index.html")
    }

    private fun resolveFile(context: Context, rootUri: Uri, reqPath: String): Any? {
        val cleanPath = reqPath.removePrefix("/")
        
        var baseDir = rootUri.path ?: ""
        if (baseDir.startsWith("/tree/primary:")) {
            baseDir = baseDir.replace("/tree/primary:", "/storage/emulated/0/")
        } else if (baseDir.startsWith("/document/primary:")) {
            baseDir = baseDir.replace("/document/primary:", "/storage/emulated/0/")
        }
        
        // The literal path first, then the two shapes a saved page may have taken.
        //
        // A crawled or captured page has to be stored with an extension -- content type is derived
        // from it, and without one a perfectly good page is served as octet-stream and offered as a
        // download instead of rendered. That means a site linking to `/about` has its page on disk
        // as `about.html`, and a link to `/docs` as `docs/index.html`. Without these fallbacks
        // every such link 404s for the peer even though the file is right there.
        for (candidate in candidatePaths(cleanPath)) {
            val file = File(baseDir, candidate)
            if (file.exists() && !file.isDirectory) {
                return file
            }
        }

        try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            if (rootDoc != null && rootDoc.exists()) {
                var current: DocumentFile? = rootDoc
                val segments = cleanPath.split("/")
                for (seg in segments) {
                    if (seg.isEmpty()) continue
                    current = current?.findFile(seg)
                }
                if (current != null && current.isFile) {
                    return current
                }
            }
        } catch (e: Exception) {}
        
        return null
    }

    fun getAppIconBytes(context: Context): ByteArray? {
        try {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = if (icon is BitmapDrawable) {
                icon.bitmap
            } else {
                val b = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                icon.setBounds(0, 0, canvas.width, canvas.height)
                icon.draw(canvas)
                b
            }
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            return stream.toByteArray()
        } catch (e: Exception) {
            return null
        }
    }

    private fun sendAppIcon(context: Context, output: OutputStream) {
        val bytes = getAppIconBytes(context)
        if (bytes == null) {
            sendError(output, 404, "Icon Not Found")
            return
        }
        
        try {
            val response = StringBuilder()
            response.append("HTTP/1.1 200 OK\r\n")
            response.append("Content-Type: image/png\r\n")
            response.append("Content-Length: ${bytes.size}\r\n")
            response.append("Date: ${getServerTime()}\r\n")
            response.append("Server: PrismMesh/1.0\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")

            output.write(response.toString().toByteArray())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {}
    }

    private suspend fun sendAiTemplate(context: Context, output: OutputStream, source: Any) {
        val prompt = when(source) {
            is File -> source.readText()
            is DocumentFile -> context.contentResolver.openInputStream(source.uri)?.bufferedReader()?.use { it.readText() } ?: ""
            else -> ""
        }
        
        val systemContext = "Output ONLY valid HTML. Do not include markdown code blocks like ```html. Just the raw HTML code."
        val finalPrompt = "$systemContext\n\nUser Content Request:\n$prompt"
        
        val (htmlContent, _) = AiManager.getResponse(context, finalPrompt)
        
        val cleanedHtml = htmlContent.trim()
            .removePrefix("```html")
            .removeSuffix("```")
            .trim()

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: text/html\r\n")
        response.append("Content-Length: ${cleanedHtml.toByteArray().size}\r\n")
        response.append("Server: PrismMesh/1.0-AI\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(cleanedHtml)

        output.write(response.toString().toByteArray())
        output.flush()
    }

    private fun sendDocumentFile(context: Context, output: OutputStream, doc: DocumentFile) {
        val mime = doc.type ?: "application/octet-stream"
        val size = doc.length()

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: $mime\r\n")
        response.append("Content-Length: $size\r\n")
        response.append("Date: ${getServerTime()}\r\n")
        response.append("Server: PrismMesh/1.0\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        output.write(response.toString().toByteArray())

        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    /**
     * Sends a file, honouring a single `Range` header.
     *
     * RANGE IS WHAT MAKES VIDEO WORK. Every media player -- Android's own, a browser's `<video>`, VLC
     * -- opens a clip by asking for a byte range, and many ask for the last few kilobytes first to
     * read the container's index. A server that answers each of those with the whole file from byte
     * zero and a 200 leaves the player either unable to seek or unable to start at all. Advertising
     * `Accept-Ranges` and answering 206 with the slice asked for costs a seek and fixes both.
     *
     * Only a single `bytes=start-end` range is handled, which is all any player in practice sends;
     * a multipart range request falls back to the whole file, which is a correct if unhelpful answer.
     */
    private fun sendFile(output: OutputStream, file: File, rangeHeader: String? = null) {
        val ext = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val total = file.length()

        val range = parseSingleRange(rangeHeader, total)

        val response = StringBuilder()
        if (range != null) {
            response.append("HTTP/1.1 206 Partial Content\r\n")
            response.append("Content-Range: bytes ${range.first}-${range.last}/$total\r\n")
        } else {
            response.append("HTTP/1.1 200 OK\r\n")
        }
        response.append("Content-Type: $mime\r\n")
        response.append("Content-Length: ${range?.let { it.last - it.first + 1 } ?: total}\r\n")
        // Told unconditionally: a player that does not know ranges are available will not ask, and
        // then cannot seek even though the file on disk is perfectly seekable.
        response.append("Accept-Ranges: bytes\r\n")
        response.append("Date: ${getServerTime()}\r\n")
        response.append("Server: PrismMesh/1.0\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")

        output.write(response.toString().toByteArray())

        file.inputStream().use { input ->
            var remaining = if (range != null) {
                input.skip(range.first)
                range.last - range.first + 1
            } else {
                total
            }
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    /**
     * `bytes=start-end` as an inclusive range, or null when there is nothing usable to honour.
     *
     * Both halves are optional in the header and mean different things: `bytes=500-` is "from 500 to
     * the end", `bytes=-500` is "the LAST 500 bytes" -- not "up to 500", which is the easy mistake
     * and the one that breaks players reading a container index from the tail. A range starting past
     * the end of the file is unsatisfiable and returns null so the caller sends the whole file rather
     * than an empty 206.
     */
    private fun parseSingleRange(header: String?, total: Long): LongRange? {
        if (header.isNullOrBlank() || total <= 0L) return null
        val spec = header.substringAfter("bytes=", "").trim()
        if (spec.isEmpty() || spec.contains(',')) return null

        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return runCatching {
            if (startText.isEmpty()) {
                val lastN = endText.toLong()
                if (lastN <= 0L) return null
                LongRange(maxOf(0L, total - lastN), total - 1)
            } else {
                val start = startText.toLong()
                if (start >= total) return null
                val end = if (endText.isEmpty()) total - 1 else minOf(endText.toLong(), total - 1)
                if (end < start) return null
                LongRange(start, end)
            }
        }.getOrNull()
    }

    private suspend fun sendManifest(context: Context, output: OutputStream, domain: String, localPath: String) {
        val json = com.prism.core.json.JSONObject()
        json.put("domain", domain)
        json.put("timestamp", System.currentTimeMillis())
        
        val filesArray = com.prism.core.json.JSONArray()
        val rootUri = Uri.parse(localPath)
        
        val files = mutableListOf<Pair<String, Any>>()
        collectFiles(context, rootUri, "", files)
        
        for (f in files) {
            val fileObj = com.prism.core.json.JSONObject()
            fileObj.put("path", f.first)
            fileObj.put("size", when(val res = f.second) {
                is File -> res.length()
                is DocumentFile -> res.length()
                else -> 0L
            })
            fileObj.put("hash", getFileHash(context, f.second))
            filesArray.put(fileObj)
        }
        
        json.put("files", filesArray)
        val body = json.toString()
        
        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: application/json\r\n")
        response.append("Content-Length: ${body.toByteArray().size}\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(body)
        
        output.write(response.toString().toByteArray())
        output.flush()
    }

    private fun collectFiles(context: Context, uri: Uri, relativePath: String, out: MutableList<Pair<String, Any>>) {
        val doc = if (relativePath.isEmpty()) DocumentFile.fromTreeUri(context, uri) else {
            // This is simplified; real implementation would need to resolve segments
            null 
        }
        
        // Better: Use File API if it's a local storage path
        var baseDir = uri.path ?: ""
        if (baseDir.startsWith("/tree/primary:")) baseDir = baseDir.replace("/tree/primary:", "/storage/emulated/0/")
        else if (baseDir.startsWith("/document/primary:")) baseDir = baseDir.replace("/document/primary:", "/storage/emulated/0/")
        
        val root = File(baseDir)
        if (root.exists() && root.isDirectory) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.absolutePath.removePrefix(root.absolutePath).replace("\\", "/")
                out.add(rel.removePrefix("/") to file)
            }
        }
    }

    private fun getFileHash(context: Context, source: Any): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16384)
            val stream = when (source) {
                is File -> source.inputStream()
                is DocumentFile -> context.contentResolver.openInputStream(source.uri)
                else -> null
            }
            
            stream?.use { 
                var read: Int
                while (it.read(buffer).also { r -> read = r } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val statusText = when (code) {
            404 -> "Not Found"
            403 -> "Forbidden"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        
        val body = "HTTP Error $code: $message"
        val response = StringBuilder()
        response.append("HTTP/1.1 $code $statusText\r\n")
        response.append("Content-Type: text/plain\r\n")
        response.append("Content-Length: ${body.toByteArray().size}\r\n")
        response.append("Connection: close\r\n")
        response.append("Date: ${getServerTime()}\r\n")
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

    private fun getServerTime(): String {
        val calendar = java.util.Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        return dateFormat.format(calendar.time)
    }
}
