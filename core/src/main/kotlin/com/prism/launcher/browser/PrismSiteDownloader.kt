package com.prism.launcher.browser

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an ordinary website by CRAWLING it, so it can be hosted on the mesh.
 *
 * DISTINCT FROM [MeshMirror.mirror], which is not a general download at all: that
 * fetches `https://<domain>.remote/manifest.json` and replicates a site ALREADY hosted on the mesh
 * by a Prism peer, verifying each file against a published hash. A site on the open web publishes
 * no such manifest and has no `.remote` host, so mirroring one fails at the first request -- which
 * is exactly what "site download failed" meant when this was pointed at duckduckgo.com.
 *
 * The two are complementary and both are worth having: mirroring is fast and integrity-checked for
 * mesh content, and this is the only thing that can work for the rest of the web.
 *
 * SAME-HOST ONLY, AND BOUNDED. A crawler that follows every outbound link does not download a
 * site, it downloads the internet. Pages are restricted to the origin host, and the page count,
 * total bytes and per-file size are all capped -- a phone should not fill its storage because a
 * site had a large media library.
 */
object PrismSiteDownloader {

    /**
     * What to do with a site once it is on disk. Installed per platform.
     *
     * A hook because "registered" means different things: Android files it with its mirror manager and
     * its DNS record, a desktop hands it to MeshWebHost. Both then SERVE it, which is the point -- a
     * downloaded site that only sits in a folder is a folder.
     */
    @Volatile
    var onSiteSaved: ((host: String, path: String) -> Unit)? = null

    /** Progress, 0..100 or -1. Installed per platform so a notification can follow a long crawl. */
    @Volatile
    var onProgress: ((host: String, percent: Int) -> Unit)? = null


    private const val TAG = "PrismSiteDownload"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val MAX_PAGES = 150
    private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val PER_REQUEST_DELAY_MS = 250L
    private const val TIMEOUT_MS = 15_000

    /** Assets referenced by a page that are worth saving so it renders offline. */
    private val ASSET_ATTRS = listOf("src", "href")

    fun isDownloading(domain: String): Boolean = active.contains(domain)
    private val active = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Crawls [startUrl]'s host and writes the result into the mirrors directory, then registers it
     * as a hosted mesh site through [onSiteSaved] -- the same
     * registration mesh mirroring uses, so a downloaded site is hosted, DNS-registered and
     * announced identically however it was obtained.
     */
    fun download(startUrl: String) {
        val origin = try { com.prism.core.Urls.of(startUrl) } catch (e: Exception) { null }
        val host = origin?.host?.lowercase()
        if (origin == null || host.isNullOrBlank()) {
            runCatching { onProgress?.invoke(startUrl, -1) }
            return
        }
        if (!active.add(host)) return          // already downloading this site

        scope.launch {
            val siteDir = File(PrismSettings.getMirrorsDir(), host)
            try {
                runCatching { onProgress?.invoke(host, 0) }
                siteDir.mkdirs()

                val queue = ArrayDeque<String>()
                val seen = HashSet<String>()
                var savedBytes = 0L
                var saved = 0

                val root = "${origin.protocol}://${origin.authority}"
                queue.addLast(startUrl)
                seen.add(startUrl)

                while (queue.isNotEmpty() && saved < MAX_PAGES && savedBytes < MAX_TOTAL_BYTES) {
                    val url = queue.removeFirst()
                    val fetched = fetch(url) ?: continue
                    if (fetched.bytes.size > MAX_FILE_BYTES) continue

                    val relative = relativePathFor(url, host) ?: continue
                    val target = File(siteDir, relative)
                    target.parentFile?.mkdirs()
                    target.writeBytes(fetched.bytes)

                    savedBytes += fetched.bytes.size
                    saved++

                    if (fetched.isHtml) {
                        val html = String(fetched.bytes, Charsets.UTF_8)
                        for (link in extractReferences(html, url)) {
                            if (seen.size > MAX_PAGES * 8) break
                            val abs = try { com.prism.core.Urls.resolve(url, link).toString().substringBefore('#') }
                                      catch (e: Exception) { continue }
                            if (!abs.startsWith(root)) continue      // same host only
                            if (seen.add(abs)) queue.addLast(abs)
                        }
                    }

                    // Progress against the page budget rather than the queue: the queue grows as
                    // pages are found, so a queue-based percentage would move backwards.
                    runCatching { onProgress?.invoke(host, ((saved * 100) / MAX_PAGES).coerceIn(1, 99)) }
                    Thread.sleep(PER_REQUEST_DELAY_MS)
                }

                if (saved == 0) throw IllegalStateException("nothing could be downloaded from $host")

                runCatching { onSiteSaved?.invoke(host, siteDir.absolutePath) }
                runCatching { onProgress?.invoke(host, 100) }
                PrismPlatform.log.info(TAG, "Downloaded $host: $saved file(s), ${savedBytes / 1024} KB")
            } catch (e: Exception) {
                PrismPlatform.log.error(TAG, "Site download failed for $host", e)
                runCatching { onProgress?.invoke(host, -1) }
            } finally {
                active.remove(host)
            }
        }
    }

    private class Fetched(val bytes: ByteArray, val isHtml: Boolean)

    private fun fetch(urlStr: String): Fetched? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (com.prism.core.Urls.of(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", com.prism.launcher.search.PrismCrawler.USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return null
            val isHtml = (conn.contentType ?: "").contains("html", ignoreCase = true)
            val bytes = conn.inputStream.use { it.readBytes() }
            Fetched(bytes, isHtml)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Where a URL's content is stored under the site directory.
     *
     * A path ending in `/` (or empty) becomes `index.html`, because that is what a web server
     * serves for a directory and what `PrismWebHost` will look for when serving the mirror back.
     * Query strings are folded into the filename rather than dropped, so two pages differing only
     * by query do not overwrite each other.
     */
    private fun relativePathFor(urlStr: String, host: String): String? {
        val url = try { com.prism.core.Urls.of(urlStr) } catch (e: Exception) { return null }
        if (!url.host.equals(host, ignoreCase = true)) return null

        var path = url.path.orEmpty()
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        if (!url.query.isNullOrBlank()) {
            val safeQuery = url.query.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            path = if (path.contains('.')) {
                val dot = path.lastIndexOf('.')
                path.substring(0, dot) + "_" + safeQuery + path.substring(dot)
            } else "$path" + "_" + safeQuery + ".html"
        }
        // Refuse anything that would climb out of the site directory.
        val cleaned = path.trimStart('/').replace("..", "_")
        return cleaned.ifBlank { "index.html" }
    }

    /** href/src references found in [html]; resolution against [baseUrl] happens at the call site. */
    private fun extractReferences(html: String, baseUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        for (attr in ASSET_ATTRS) {
            val re = Regex("(?is)\\b$attr\\s*=\\s*[\"']([^\"']+)[\"']")
            for (m in re.findAll(html)) {
                val v = m.groupValues[1].trim()
                if (v.isEmpty() || v.startsWith("javascript:") || v.startsWith("mailto:") ||
                    v.startsWith("data:") || v.startsWith("#")) continue
                out.add(v)
            }
        }
        return out.toList()
    }
}
