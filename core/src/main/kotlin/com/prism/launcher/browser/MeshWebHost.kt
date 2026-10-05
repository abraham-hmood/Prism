package com.prism.launcher.browser

import com.prism.core.MeshConnect
import com.prism.core.MeshDns
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Serving a folder as a website on the mesh. PHASE 52.
 *
 * ## Any name, not just `.p2p`
 *
 * A hosted site can be called anything — `notes.p2p`, `wiki.com`, `library.gov`, a name with a suffix
 * nobody has ever registered. THE SUFFIX IS NOT A PERMISSION CHECK and never was: what makes a name a
 * mesh name is that a peer announced a record for it, which is a fact [MeshDns] holds rather than
 * something readable off the end of a string. `.p2p` is a convention that avoids colliding with the
 * public internet, and a user who wants their own `.com` on their own mesh is not doing anything the
 * software needs to prevent.
 *
 * What that DOES mean is that a hosted name shadows the public one for devices on this mesh. That is the
 * feature working as asked; it is also why [shadowsPublicName] exists, so a UI can say so rather than
 * leaving somebody wondering why one machine sees a different site from the rest of the world.
 *
 * ## Why this is a file server and not much else
 *
 * No PHP, no CGI, no server-side anything. A folder of files, an index for a directory, and the right
 * content type. Everything Prism hosts is static, and a scripting runtime on a port reachable by every
 * device on the network is a large attack surface bought for no feature anyone asked for.
 *
 * ## Why directory listings are generated rather than refused
 *
 * A folder with no `index.html` is usually somebody sharing files rather than a site, and a 403 would
 * make that look broken. The listing is plain and does not expose anything outside the folder.
 */
object MeshWebHost : MeshConnect.DomainHost {

    private const val TAG = "PrismWebHost"

    /** One hosted site. */
    data class Site(
        val domain: String,
        val root: File,
        val addedAt: Long = System.currentTimeMillis(),
    )

    private val sites = ConcurrentHashMap<String, Site>()

    @Volatile private var storage: File? = null

    /** Loads what was hosted before, and starts serving it. */
    fun install(directory: File) {
        storage = directory.apply { mkdirs() }
        load()
        sites.keys.forEach { MeshConnect.host(it, this) }
        if (sites.isNotEmpty()) {
            PrismPlatform.log.info(TAG, "Hosting " + sites.size + " site(s)")
        }
    }

    fun all(): List<Site> = sites.values.sortedBy { it.domain }

    /**
     * Starts serving [root] as [domain], and registers the name on the mesh.
     *
     * The DNS record is LOCAL and points at loopback; MeshDns rewrites that to this device's mesh
     * address on the way out, which is why a peer can reach it and this device can still serve it to
     * itself. Returns an error to show, or null.
     */
    fun host(domain: String, root: File): String? {
        val name = domain.lowercase().trim().removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
        if (name.isBlank()) return "Name the site something."
        if (!name.contains('.')) return "A site name needs a suffix, like " + name + ".p2p"
        if (!root.isDirectory) return "There is no folder at " + root.absolutePath

        sites[name] = Site(name, root)
        save()
        MeshConnect.host(name, this)
        MeshDns.put(name, "127.0.0.1", MeshDns.Source.LOCAL)
        MeshDns.announce(name)
        PrismPlatform.log.info(TAG, "Hosting " + name + " from " + root.absolutePath)
        return null
    }

    fun stopHosting(domain: String) {
        val name = domain.lowercase().trim()
        if (sites.remove(name) != null) {
            save()
            MeshConnect.unhost(name)
            MeshDns.remove(name)
            PrismPlatform.log.info(TAG, "Stopped hosting " + name)
        }
    }

    /**
     * Whether a hosted name also exists on the public internet.
     *
     * Not a check that stops anything -- hosting `example.com` on a private mesh is allowed and is what
     * was asked for. It is so a UI can SAY that devices on this mesh will see this site instead of the
     * public one, which is surprising if nobody mentions it.
     */
    fun shadowsPublicName(domain: String): Boolean {
        val suffix = domain.substringAfterLast('.', "")
        return suffix.isNotBlank() && suffix !in MESH_ONLY_SUFFIXES
    }

    /** Suffixes that cannot exist publicly, so hosting them shadows nothing. */
    private val MESH_ONLY_SUFFIXES = setOf("p2p", "local", "prism", "mesh", "internal")

    // ── Serving ────────────────────────────────────────────────────────────

    override fun serve(socket: Socket, domain: String, head: String?, input: InputStream) {
        val output = socket.getOutputStream()
        val site = sites[domain.lowercase()]
        if (site == null) {
            respond(output, 404, "text/plain", "Nothing is hosted at $domain on this device.".toByteArray())
            runCatching { socket.close() }
            return
        }

        val requestLine = head.orEmpty().lineSequence().firstOrNull().orEmpty()
        if (!requestLine.startsWith("GET") && !requestLine.startsWith("HEAD")) {
            respond(output, 405, "text/plain", "This site only answers GET.".toByteArray())
            runCatching { socket.close() }
            return
        }

        val rawPath = requestLine.split(" ").getOrNull(1).orEmpty().substringBefore('?')

        // The mirror manifest. Generated rather than stored, so it cannot go stale against the folder:
        // a site whose manifest listed a file that had been deleted would fail halfway through every
        // mirror attempt with nothing to point at.
        if (rawPath == "/manifest.json") {
            respond(output, 200, "application/json; charset=utf-8", manifestFor(site))
            runCatching { socket.close() }
            return
        }

        val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val target = resolve(site.root, decoded)

        when {
            target == null ->
                // A path that climbed out of the folder. Refused with 403 rather than 404, because the
                // difference matters to whoever is looking at the log: one is a typo, the other is not.
                respond(output, 403, "text/plain", "That path is outside the hosted folder.".toByteArray())

            target.isDirectory -> {
                val index = File(target, "index.html")
                if (index.isFile) {
                    sendFile(output, index, requestLine.startsWith("HEAD"))
                } else {
                    respond(output, 200, "text/html; charset=utf-8", listing(site, target, decoded))
                }
            }

            target.isFile -> sendFile(output, target, requestLine.startsWith("HEAD"))

            else -> respond(output, 404, "text/plain", "No such page on $domain.".toByteArray())
        }
        runCatching { socket.close() }
    }

    /**
     * Turns a request path into a file inside the site, or null if it escapes.
     *
     * The canonical path is compared rather than the requested one, so `..`, a symlink, and an absolute
     * path all end up checked against where they actually point rather than against how they were
     * spelled.
     */
    private fun resolve(root: File, path: String): File? {
        val relative = path.trimStart('/').ifBlank { "." }
        val candidate = File(root, relative)
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val targetPath = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        return if (targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            candidate
        } else {
            null
        }
    }

    private fun sendFile(output: OutputStream, file: File, headOnly: Boolean) {
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: " + contentType(file.name) +
                    "\r\nContent-Length: " + file.length() +
                    "\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (!headOnly) file.inputStream().use { it.copyTo(output, 64 * 1024) }
            output.flush()
        }
    }

    private fun listing(site: Site, directory: File, path: String): ByteArray {
        val entries = directory.listFiles().orEmpty().sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        )
        val rows = entries.joinToString("\n") { entry ->
            val name = entry.name + if (entry.isDirectory) "/" else ""
            val href = (path.trimEnd('/') + "/" + entry.name).replace("//", "/")
            val size = if (entry.isDirectory) "" else " <span>" + human(entry.length()) + "</span>"
            "<li><a href=\"$href\">$name</a>$size</li>"
        }
        return ("<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<title>" + site.domain + path + "</title><style>" +
            "body{font-family:system-ui,sans-serif;background:#111;color:#ddd;padding:32px}" +
            "a{color:#8ab4ff;text-decoration:none}a:hover{text-decoration:underline}" +
            "li{margin:4px 0;list-style:none}span{color:#777;font-size:.85em;margin-left:8px}" +
            "h2{font-weight:600}</style></head><body>" +
            "<h2>" + site.domain + path + "</h2><ul>" + rows + "</ul>" +
            "<p style=\"color:#666;font-size:.8em\">Served from this device over the Prism mesh.</p>" +
            "</body></html>").toByteArray()
    }

    /**
     * Everything in a site, with a hash each, as [MeshMirror] expects.
     *
     * HASHED ON DEMAND, which costs a read of the whole site per request. That is acceptable because a
     * manifest is fetched once per mirror rather than per page, and the alternative -- a cached
     * manifest -- is a second thing that can disagree with the folder.
     */
    fun manifestFor(site: Site): ByteArray {
        val files = JSONArray()
        var count = 0
        site.root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                if (count >= MANIFEST_LIMIT) return@forEach
                val relative = file.absolutePath
                    .removePrefix(site.root.absolutePath)
                    .replace(File.separatorChar, '/')
                    .trimStart('/')
                if (relative.isBlank()) return@forEach
                files.put(
                    JSONObject().apply {
                        put("path", relative)
                        put("hash", sha256(file))
                        put("bytes", file.length())
                    }
                )
                count++
            }
        return JSONObject().apply {
            put("domain", site.domain)
            put("files", files)
        }.toString().toByteArray()
    }

    /**
     * A ceiling on how many files a manifest lists.
     *
     * A folder somebody pointed at by mistake -- a home directory, a drive root -- would otherwise
     * hash every file on the machine on the first request. The limit turns that into a truncated
     * manifest rather than a machine that stops responding.
     */
    private const val MANIFEST_LIMIT = 5_000

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> (bytes shr 30).toString() + " GB"
        bytes >= 1L shl 20 -> (bytes shr 20).toString() + " MB"
        bytes >= 1L shl 10 -> (bytes shr 10).toString() + " KB"
        else -> bytes.toString() + " B"
    }

    /**
     * The content type for a file name.
     *
     * Hand-written rather than asked of the platform, because the platforms disagree: Android's
     * MimeTypeMap and the JVM's `Files.probeContentType` return different answers for the same file, and
     * a stylesheet served as text/plain is ignored by every browser.
     */
    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "txt", "md" -> "text/plain; charset=utf-8"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: ByteArray) {
        runCatching {
            output.write(
                ("HTTP/1.1 " + code + " " + (if (code == 200) "OK" else "Error") + "\r\n" +
                    "Content-Type: " + type + "\r\nContent-Length: " + body.size +
                    "\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun file(): File? = storage?.let { File(it, "hosted-sites.json") }

    private fun load() {
        val source = file() ?: return
        if (!source.isFile) return
        runCatching {
            val array = JSONArray(source.readText())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val domain = item.optString("domain")
                val root = File(item.optString("root"))
                if (domain.isNotBlank() && root.isDirectory) {
                    sites[domain] = Site(domain, root, item.optLong("at", 0))
                }
            }
        }
    }

    private fun save() {
        val target = file() ?: return
        val array = JSONArray()
        sites.values.forEach { site ->
            array.put(
                JSONObject().apply {
                    put("domain", site.domain)
                    put("root", site.root.absolutePath)
                    put("at", site.addedAt)
                }
            )
        }
        runCatching { target.writeText(array.toString()) }
    }
}
