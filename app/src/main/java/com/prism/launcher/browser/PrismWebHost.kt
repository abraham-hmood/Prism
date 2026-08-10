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
    
    // Structure: domain -> (path -> File or DocumentFile)
    private val siteCache = mutableMapOf<String, MutableMap<String, Any>>()
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
            siteCache.getOrPut(domain) { mutableMapOf() }
        }
        
        val cachedResult = domainCache[path]
        if (cachedResult != null) {
            handleFileResult(context, output, cachedResult, path)
            return
        }
        
        val rootUri = Uri.parse(localPath)
        val fileResult = resolveFile(context, rootUri, path)
        
        if (fileResult != null) {
            synchronized(cacheMutex) { domainCache[path] = fileResult }
            handleFileResult(context, output, fileResult, path)
        } else {
            // Negative cache to prevent repeated scanning of missing files
            synchronized(cacheMutex) { domainCache[path] = "NULL_MARKER" }
            handleMissingFile(context, output, path)
        }
    }

    private suspend fun handleFileResult(context: Context, output: OutputStream, result: Any, path: String) {
        when {
            result is File -> {
                if (result.name.endsWith(".prism")) {
                    sendAiTemplate(context, output, result)
                } else {
                    sendFile(output, result)
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
     * Clear the cache for a specific site (e.g. if the user updates the source folder)
     */
    fun clearCache(domain: String) {
        synchronized(cacheMutex) {
            siteCache.remove(domain)
        }
    }

    private fun resolveFile(context: Context, rootUri: Uri, reqPath: String): Any? {
        val cleanPath = reqPath.removePrefix("/")
        
        var baseDir = rootUri.path ?: ""
        if (baseDir.startsWith("/tree/primary:")) {
            baseDir = baseDir.replace("/tree/primary:", "/storage/emulated/0/")
        } else if (baseDir.startsWith("/document/primary:")) {
            baseDir = baseDir.replace("/document/primary:", "/storage/emulated/0/")
        }
        
        val file = File(baseDir, cleanPath)
        if (file.exists() && !file.isDirectory) {
            return file
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

    private fun sendFile(output: OutputStream, file: File) {
        val ext = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: $mime\r\n")
        response.append("Content-Length: ${file.length()}\r\n")
        response.append("Date: ${getServerTime()}\r\n")
        response.append("Server: PrismMesh/1.0\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")

        output.write(response.toString().toByteArray())
        
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
        }
        output.flush()
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
