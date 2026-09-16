package com.prism.launcher.browser

import android.content.Context
import android.webkit.WebView
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps a copy of ordinary web pages as the user browses them, and -- only if the user says so --
 * serves those copies to the mesh.
 *
 * ## Why this is not [PrismSiteDownloader]
 *
 * That crawls a site on demand: it re-fetches every page with its own HTTP client, which gets the
 * logged-out, pre-JavaScript version of each one. This captures the page the user is actually
 * looking at, after scripts have run, at the moment it finished loading. The two are complementary
 * and neither replaces the other -- a crawl can reach pages nobody visited, and only a capture can
 * preserve a page that does not exist until JavaScript builds it.
 *
 * ## The cached copy does NOT impersonate the original site
 *
 * A cache of `example.com` is published as `example.com.cache.p2p`, never as `example.com`.
 *
 * This is the whole design, not a naming preference. Registering the real domain in Prism's DNS is
 * what [PrismMirrorManager.finalizeMirror] does for a deliberate mirror, and it works because the
 * user asked for that one site: [P2pDnsManager.resolve] then answers `127.0.0.1` for it forever,
 * `PrivateDnsVpnService` hands that answer to EVERY app on the device, and the record is gossiped
 * to every peer with no expiry and no unpublish opcode. Doing that automatically for each site
 * visited would replace the mesh's view of the real internet with one-shot snapshots taken on a
 * phone. Under a `.p2p` name the live site keeps resolving normally, and a cached copy is something
 * a person opens on purpose.
 *
 * ## Unshared means unreachable, structurally
 *
 * With sharing off, a cached host gets no DNS record and no hosted-site entry -- so there is
 * nothing for a peer to find even if it asks, and a cached page is readable only through
 * [resolveLocal], which serves it straight off local disk inside this device's own WebView. That
 * matters because [com.prism.launcher.mesh.PrismMeshService] answers a sync request with the whole
 * DNS ledger AND the whole hosted-site list; a "local only" flag on a published record would leak
 * on the next gossip round. Absence does not leak.
 *
 * ## What is deliberately never cached
 *
 * Private tabs, anything with a query string (that is where search terms and session tokens live,
 * and refusing them outright means turning sharing on later cannot publish one), pages served from
 * the mesh already, and pages whose author asked not to be archived via `<meta name="robots">`.
 */
object PrismWebCache {

    private const val TAG = "PrismWebCache"

    /**
     * Suffix that turns a cached host into a mesh name. `.p2p` matters: [P2pDnsManager.isP2pDomain]
     * recognises the suffix directly, so resolution and the browser's mesh interception work for a
     * published cache without any special-casing.
     */
    const val MESH_SUFFIX = ".cache.p2p"

    /** Where the index of what has been cached lives, inside the cache directory itself. */
    private const val INDEX_FILE = "index.json"

    /** Content hashes already posted to Lyke, so a re-capture does not post a duplicate. */
    private const val LYKE_LEDGER_FILE = "lyke_uploads.json"

    /**
     * Ceiling for the whole cache. Generous because video is in scope now -- at a few tens of
     * megabytes a clip, the old 250 MB held about six of them, which is not a cache so much as a
     * rounding error. Eviction (whole least-recently-saved hosts) is what protects the device.
     */
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_HTML_BYTES = 3L * 1024 * 1024
    private const val MAX_ASSETS_PER_PAGE = 24

    /** Page furniture: stylesheets, scripts, icons. Small by nature; anything large here is a bug. */
    private const val MAX_ASSET_BYTES = 1_500L * 1024
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_MEDIA_BYTES = 40L * 1024 * 1024

    /** Video and audio per page, counted separately from furniture: a few clips, not a library. */
    private const val MAX_MEDIA_PER_PAGE = 8

    /** Below this an image is page furniture, not a picture. See `worthIndexing`. */
    private const val MIN_GALLERY_IMAGE_BYTES = 60L * 1024

    private const val ASSET_TIMEOUT_MS = 10_000
    private const val MEDIA_TIMEOUT_MS = 45_000

    /** Where a cross-origin file lands inside the host's directory. See [saveAssets]. */
    private const val EXTERNAL_DIR = "_ext"

    private val IMAGE_EXTENSIONS =
        setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "svg")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "m4v", "mov", "ogv", "mkv")
    private val MEDIA_EXTENSIONS =
        VIDEO_EXTENSIONS + setOf("mp3", "m4a", "aac", "ogg", "opus", "wav")

    /**
     * Playlists rather than media: an `.m3u8` or `.mpd` names hundreds of segments held elsewhere.
     * Saving the manifest alone produces a file that looks like a video and plays as nothing, which
     * is worse than not caching it -- so these are skipped outright rather than half-captured.
     */
    private val STREAM_MANIFESTS = setOf("m3u8", "mpd")

    /** Serialised so two page-finishes cannot interleave an index read-modify-write. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "prism-web-cache").apply { isDaemon = true }
    }

    private val indexLock = Any()

    /** One cached host, as the Downloads list and the eviction pass see it. */
    data class CachedSite(
        val host: String,
        val pages: Int,
        val bytes: Long,
        val lastSavedAt: Long,
        /** Title of the most recently captured page, for a row subtitle. */
        val lastTitle: String,
    ) {
        /** The name this cache answers to, published or not. */
        val meshDomain: String get() = host + MESH_SUFFIX
    }

    // ── Gating ─────────────────────────────────────────────────────────────
    //
    // The settings screen and the capture path ask the same functions, so a row that looks
    // available cannot disagree with what actually happens on the next page load.

    /**
     * Whether there is a live Prism P2P tunnel under us, either role.
     *
     * Both halves are needed: the preference says what the user wants, [PrismTunnelEngine] says
     * whether it is running. A cache is only meaningful with the tunnel up, because the point of it
     * is mesh reachability.
     */
    fun tunnelReady(): Boolean {
        if (!PrismSettings.getVpnTunnelingEnabled()) return false
        val app = com.prism.launcher.PrismApp.instance
        return runCatching { app.tunnelEngine.isPrismVpnActive() }.getOrDefault(false)
    }

    /**
     * Why caching cannot be switched on, or null when it can.
     *
     * A greyed-out row that says only "needs a connection" is a dead end: every one of the four
     * conditions below looks identical from the outside, and the user is left toggling things to see
     * which one it wanted. Naming the missing piece turns it into an instruction.
     *
     * [cachingAvailable] is defined as this returning null, so the explanation and the enabled state
     * cannot drift apart -- there is no second copy of the rule to forget to update.
     */
    fun cachingUnavailableReason(): String? = when {
        !PrismSettings.getVpnTunnelingEnabled() ->
            "Needs VPN tunnelling — turn on Enable VPN Tunneling above."
        PrismSettings.getVpnMode() != PrismSettings.VPN_MODE_PRISM ->
            "Only for the Prism P2P VPN — VPN Mode is set to External VPN."
        PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_CLIENT &&
            PrismSettings.getPrismServers().isEmpty() ->
            "No Prism server saved yet — add one under Manage Prism Servers, or switch the role to Server."
        !tunnelReady() ->
            "Waiting for the Prism VPN to come up — start it with Persistent VPN Server, " +
                "or by opening a private tab."
        else -> null
    }

    /** Whether the "Allow web caching" row can be switched at all. */
    fun cachingAvailable(): Boolean = cachingUnavailableReason() == null

    /** Whether pages should actually be captured right now. */
    fun cachingEnabled(): Boolean = cachingAvailable() && PrismSettings.getWebCacheEnabled()

    /** Whether the "make cached sites available on the Meshnet" row can be switched. */
    fun meshSharingAvailable(): Boolean = tunnelReady() && PrismSettings.getWebCacheEnabled()

    /** Whether cached pages are being served to peers right now. */
    fun meshSharingEnabled(): Boolean =
        meshSharingAvailable() && PrismSettings.getWebCacheMeshSharing()

    // ── Capture ────────────────────────────────────────────────────────────

    /**
     * Captures the finished page in [webView].
     *
     * MUST be called on the UI thread -- it reads the live DOM. Everything after that runs on
     * [worker], because writing a few hundred KB and fetching assets has no business on the thread
     * that is about to paint the page the user just waited for.
     *
     * The DOM is taken rather than the HTML re-fetched, which is the entire reason to hook
     * page-finish: `document.documentElement.outerHTML` is what the user can see, including
     * whatever scripts built. It is also why [isPrivateTab] is refused here rather than filtered
     * later -- that DOM may hold content that exists nowhere on disk by design.
     */
    fun capture(context: Context, webView: WebView, url: String, isPrivateTab: Boolean) {
        if (isPrivateTab || !cachingEnabled()) return
        val target = cacheableUrl(url) ?: return

        val appContext = context.applicationContext
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { encoded ->
            val html = decodeJsString(encoded)
            if (html == null || html.length < 200) return@evaluateJavascript
            worker.execute {
                runCatching { store(appContext, target, html) }
                    .onFailure { PrismLogger.logError(TAG, "Could not cache $url", it) }
            }
        }
    }

    /**
     * The URL this page should be filed under, or null if it must not be cached.
     *
     * Rejections are all structural rather than heuristic, so the same page is always treated the
     * same way:
     * - non-HTTP schemes have no host to file under (`about:blank`, `data:`, `file:`);
     * - credentials in the URL mean the page is behind them;
     * - a query string is refused for the reason in the class comment;
     * - a mesh domain is already served from the mesh, and caching a cache is a loop.
     */
    private fun cacheableUrl(url: String): URL? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol !in listOf("http", "https")) return null
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return null
        if (!parsed.userInfo.isNullOrBlank()) return null
        if (!parsed.query.isNullOrBlank()) return null
        if (host.endsWith(".p2p", ignoreCase = true)) return null
        if (P2pDnsManager.isP2pDomain(host)) return null
        return parsed
    }

    /** Writes one page and its assets, then updates the index and evicts if needed. */
    private fun store(context: Context, url: URL, rawHtml: String) {
        if (declinesArchiving(rawHtml)) {
            PrismLogger.logDebug(TAG, "${url.host} asked not to be archived; skipping")
            return
        }
        if (rawHtml.toByteArray(Charsets.UTF_8).size > MAX_HTML_BYTES) {
            PrismLogger.logDebug(TAG, "Page at $url is larger than the per-page cap; skipping")
            return
        }

        val host = url.host.lowercase()
        val siteDir = dirFor(host)
        siteDir.mkdirs()

        val relative = relativePathFor(url) ?: return

        // ASSETS FIRST, then the page. Saving cross-origin files means the HTML has to be rewritten
        // to point at wherever they landed, and only the download pass knows that -- it decides per
        // file whether one was kept at all. Writing the page first would mean either rewriting it
        // twice or guessing.
        val saved = saveAssets(context, rawHtml, url, siteDir)
        val html = rewriteToLocalPaths(rawHtml, url, saved.rewrites)

        val pageFile = File(siteDir, relative)
        pageFile.parentFile?.mkdirs()
        pageFile.writeText(html, Charsets.UTF_8)

        val title = extractTitle(rawHtml).ifBlank { url.toString() }
        recordPage(host, relative, title)

        // Media is indexed and offered to Lyke AFTER the page is on disk, so a failure in either
        // cannot cost the user the cached page itself -- the thing they were actually browsing.
        publishMediaToGallery(context, saved.media)
        if (PrismSettings.getWebCacheLykeUpload()) {
            saved.media.filter { isVideo(it) }.forEach { video ->
                runCatching { postToLyke(context, video, host, title) }
                    .onFailure { PrismLogger.logError(TAG, "Could not add ${video.name} to Lyke", it) }
            }
        }

        // A newly cached host has to be published as it appears, not only when the setting is
        // flipped -- otherwise sharing would silently cover the sites cached before the toggle and
        // nothing since.
        if (meshSharingEnabled()) publish(context, host)

        evictIfOverBudget(context)
    }

    /**
     * Honours `<meta name="robots" content="noarchive">` and its relatives.
     *
     * This is the one machine-readable way a publisher says "do not keep a copy of this page", and
     * the cache is exactly what it is addressed to. Checked against the raw DOM rather than the
     * response headers because headers are not available at page-finish.
     */
    private fun declinesArchiving(html: String): Boolean {
        val metas = Regex(
            "(?is)<meta[^>]+name\\s*=\\s*[\"']?(?:robots|prism)[\"']?[^>]*>"
        ).findAll(html)
        for (m in metas) {
            val content = Regex("(?is)content\\s*=\\s*[\"']([^\"']*)[\"']")
                .find(m.value)?.groupValues?.get(1)?.lowercase() ?: continue
            // `noarchive` and `nocache` both mean "do not keep a copy people can read"; `none` is
            // the blanket form. `noindex` is deliberately NOT here -- it asks search engines not to
            // list a page, which plenty of ordinary pages do, and it says nothing about keeping one.
            if (listOf("noarchive", "nocache", "none").any { content.contains(it) }) {
                return true
            }
        }
        return false
    }

    // ── Storage layout ─────────────────────────────────────────────────────

    /** This host's directory under the cache root. */
    fun dirFor(host: String): File = File(PrismSettings.getWebCacheDir(), host.lowercase())

    /**
     * Where a URL's bytes are stored, relative to the host directory.
     *
     * A directory path becomes `index.html` and an extensionless path gains `.html`, because both
     * are served back through mime-type-from-extension: without the suffix a perfectly good page
     * arrives as `application/octet-stream` and the browser offers to download it instead of
     * rendering it. Links written against the original path still resolve -- [resolveLocal] and
     * `PrismWebHost` both retry with the suffix.
     */
    private fun relativePathFor(url: URL): String? {
        var path = url.path.orEmpty()
        if (path.isEmpty() || path.endsWith("/")) {
            path += "index.html"
        } else if (!path.substringAfterLast('/').contains('.')) {
            path += ".html"
        }

        if (!url.query.isNullOrBlank()) {
            val safeQuery = url.query.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val dot = path.lastIndexOf('.')
            path = if (dot > path.lastIndexOf('/')) {
                path.substring(0, dot) + "_" + safeQuery + path.substring(dot)
            } else {
                path + "_" + safeQuery
            }
        }

        // `..` cannot be allowed to climb out of the host directory, and a blank result would
        // write over the directory itself.
        val cleaned = path.trimStart('/').replace("..", "_")
        return cleaned.ifBlank { "index.html" }
    }

    /**
     * Finds the file backing [path] for a cached host, or null.
     *
     * Tries the literal path first, then the two shapes [relativePathFor] may have produced, so a
     * page that links to `/about` finds `about.html` and one that links to `/docs` finds
     * `docs/index.html`.
     */
    fun resolveLocal(host: String, path: String): File? {
        val dir = dirFor(host)
        if (!dir.isDirectory) return null

        val clean = path.substringBefore('?').substringBefore('#').trimStart('/')
        val candidates = if (clean.isEmpty()) {
            listOf("index.html")
        } else {
            listOf(clean, "$clean.html", "$clean/index.html")
        }

        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        for (candidate in candidates) {
            val file = File(dir, candidate)
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: continue
            // Refuses anything that resolved outside the host directory, whatever the path said.
            if (!canonical.toPath().startsWith(root.toPath())) continue
            if (canonical.isFile) return canonical
        }
        return null
    }

    /**
     * Whether this device currently has no usable route to the internet.
     *
     * VALIDATED rather than merely CONNECTED, so a captive portal or a network that answers nothing
     * counts as offline -- those are exactly the cases where a cached copy is the only thing that
     * will render. A live Prism VPN does not by itself make this false: the VPN transport is present
     * with Wi-Fi and data off, but without validated connectivity underneath it, which is the
     * situation the cache exists for.
     *
     * Fails CLOSED -- an unanswerable question returns false, so an unreadable ConnectivityManager
     * can never make the browser serve a stale page to somebody who is actually online.
     */
    fun isOffline(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return@runCatching true
        val caps = manager.getNetworkCapabilities(network) ?: return@runCatching true
        !(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }.getOrDefault(false)

    /**
     * A cached page's bytes with a line at the top saying it is a cached copy.
     *
     * Served at the REAL address when the device is offline, so without this the browser would show
     * a page that looks live at a URL that looks live -- and the user has no way to tell whether
     * they are reading today's front page or last Tuesday's. The notice carries the capture time
     * because staleness is the one thing a cached copy cannot tell you by looking at it.
     *
     * Injected after the opening `<body>` tag, falling back to the front of the document when there
     * is no recognisable body -- a malformed page still renders the notice, which matters more than
     * where it lands.
     */
    fun htmlWithOfflineNotice(file: File): ByteArray {
        val html = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return runCatching { file.readBytes() }.getOrDefault(ByteArray(0))

        val age = System.currentTimeMillis() - file.lastModified()
        val captured = when {
            age < 60_000L -> "moments ago"
            age < 3_600_000L -> "${age / 60_000L} minute(s) ago"
            age < 86_400_000L -> "${age / 3_600_000L} hour(s) ago"
            else -> "${age / 86_400_000L} day(s) ago"
        }

        val notice = """
            <div style="position:sticky;top:0;z-index:2147483647;background:#1c1c1e;color:#f2f2f7;
                        font:13px/1.5 -apple-system,Roboto,sans-serif;padding:8px 12px;
                        border-bottom:1px solid #3a3a3c">
              &#x1F5C3; Cached copy &middot; saved $captured &middot; you are offline
            </div>
        """.trimIndent()

        val bodyTag = Regex("(?is)<body[^>]*>").find(html)
        val withNotice = if (bodyTag != null) {
            html.substring(0, bodyTag.range.last + 1) + notice + html.substring(bodyTag.range.last + 1)
        } else {
            notice + html
        }
        return withNotice.toByteArray(Charsets.UTF_8)
    }

    /**
     * Whether the active connection charges for data.
     *
     * Fails CLOSED, the opposite way round from [isOffline]: an unanswerable question is treated as
     * metered, so an unreadable ConnectivityManager cannot lead to hundreds of megabytes of video
     * being pulled over someone's cellular plan. Refusing to cache a video is recoverable; a data
     * bill is not.
     */
    fun isMetered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return@runCatching true
        val caps = manager.getNetworkCapabilities(network) ?: return@runCatching true
        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    /** Whether a cached copy of this exact URL exists, so a caller can offer it as a fallback. */
    fun hasCopyOf(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        return resolveLocal(host, parsed.path.orEmpty().ifEmpty { "/" }) != null
    }

    /** The cached host a mesh name refers to, or null if this is not one of ours. */
    fun hostForMeshDomain(domain: String): String? {
        if (!domain.endsWith(MESH_SUFFIX, ignoreCase = true)) return null
        val host = domain.dropLast(MESH_SUFFIX.length).lowercase()
        return host.ifBlank { null }
    }

    // ── Assets ─────────────────────────────────────────────────────────────

    /** What one page's download pass produced. */
    private class SavedAssets(
        /** Original HTML reference -> the local path to point it at. */
        val rewrites: Map<String, String>,
        /** Media files written by this pass, for gallery indexing and Lyke. */
        val media: List<File>,
    )

    /**
     * Fetches what the page referenced, so the copy renders -- and plays -- offline.
     *
     * CROSS-ORIGIN FILES ARE KEPT NOW, which they were not before, and media is the reason. A site's
     * own HTML almost never holds its video: that lives on a CDN under a different host, so a
     * same-origin rule cached every stylesheet on the page and none of the thing the user was
     * actually watching. Foreign files land under `_ext/<their host>/...` -- a namespace the site
     * cannot collide with, since its own paths are stored from the root -- and the HTML reference is
     * rewritten to point there.
     *
     * THREE BUDGETS, BECAUSE THE THINGS ARE NOT COMPARABLE. Page furniture is capped tightly and by
     * count; images get more room; video gets much more but only a few per page. One shared cap
     * would either refuse every video or let a page full of images fill the disk.
     *
     * Still not a crawler: only what THIS page referenced, each file once, no link following.
     */
    private fun saveAssets(
        context: Context,
        html: String,
        pageUrl: URL,
        siteDir: File,
    ): SavedAssets {
        val rewrites = HashMap<String, String>()
        val media = ArrayList<File>()
        var assetCount = 0
        var mediaCount = 0

        for (reference in extractReferences(html)) {
            val absolute = runCatching { URL(pageUrl, reference) }.getOrNull() ?: continue
            if (absolute.protocol !in listOf("http", "https")) continue

            val extension = extensionOf(absolute)
            if (extension in STREAM_MANIFESTS) continue

            val kind = kindOf(extension)
            if (kind == Kind.MEDIA) {
                if (mediaCount >= MAX_MEDIA_PER_PAGE) continue
                // NOT ON CELLULAR. A page can reference eight clips the user never pressed play on,
                // and at the per-file ceiling that is 320 MB of someone's data allowance spent
                // silently by a cache they enabled for pages. Wi-Fi caches the video; mobile data
                // caches the page around it, which is the part they were reading.
                if (isMetered(context)) continue
            } else {
                if (assetCount >= MAX_ASSETS_PER_PAGE) continue
            }

            val sameHost = absolute.host.equals(pageUrl.host, ignoreCase = true)
            val relative = if (sameHost) {
                relativePathFor(absolute) ?: continue
            } else {
                // Foreign host: its own subtree, named after it, so two CDNs cannot overwrite each
                // other and neither can overwrite the site.
                val inner = relativePathFor(absolute) ?: continue
                "$EXTERNAL_DIR/${absolute.host.lowercase()}/$inner"
            }

            val target = File(siteDir, relative)
            // Held from an earlier page: still needs rewriting, just not re-downloading.
            if (target.isFile) {
                if (!sameHost) rewrites[reference] = "/$relative"
                if (kind == Kind.MEDIA) {
                    mediaCount++
                    media.add(target)
                } else {
                    assetCount++
                }
                continue
            }

            if (!fetch(absolute, kind, target)) continue

            if (!sameHost) rewrites[reference] = "/$relative"
            when (kind) {
                Kind.MEDIA -> {
                    mediaCount++
                    media.add(target)
                }
                Kind.IMAGE -> {
                    assetCount++
                    media.add(target)
                }
                Kind.ASSET -> assetCount++
            }
        }

        return SavedAssets(rewrites, media)
    }

    // ── Media: the gallery, and Lyke ───────────────────────────────────────

    /**
     * Makes cached media visible to the gallery and to every other app on the device.
     *
     * SCANNED IN PLACE RATHER THAN COPIED INTO MediaStore. A MediaStore insert would duplicate every
     * video -- two copies of a 40 MB clip for one cached page -- and leave the copy behind when the
     * cache evicts the original, so the gallery would fill with entries nothing maintains. The cache
     * already lives in shared storage (`/sdcard/Prism/WebCache`), so asking the media scanner to
     * index the file it finds there is enough: one copy, and when eviction deletes it the scanner
     * drops the entry on its next pass.
     *
     * This is also the answer to "can other apps use the cached data" for media specifically: a video
     * indexed here opens in any player, with no knowledge of Prism required.
     */
    private fun publishMediaToGallery(context: Context, media: List<File>) {
        if (media.isEmpty()) return

        val paths = media.filter { it.isFile && worthIndexing(it) }
            .map { it.absolutePath }
            .toTypedArray()
        if (paths.isEmpty()) return

        runCatching {
            android.media.MediaScannerConnection.scanFile(context, paths, null, null)
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not index ${paths.size} cached file(s): ${it.message}")
        }
    }

    /**
     * Whether a cached file belongs in the user's gallery.
     *
     * Every page carries a favicon, a logo, and a dozen icons; indexing those would bury the photos
     * somebody might actually want under a pile of 2 KB sprites, and the gallery is not ours to fill.
     * Video always qualifies. An image has to be a plausible photograph: a real raster format, and
     * large enough not to be furniture.
     */
    private fun worthIndexing(file: File): Boolean {
        val extension = file.extension.lowercase()
        if (extension in VIDEO_EXTENSIONS) return true
        // Icons and vectors are interface parts by definition, whatever their size.
        if (extension == "ico" || extension == "svg") return false
        if (extension !in IMAGE_EXTENSIONS) return false
        return file.length() >= MIN_GALLERY_IMAGE_BYTES
    }

    /**
     * Posts a cached video to Lyke, once.
     *
     * COPIED INTO LYKE'S OWN STORAGE rather than referenced where it lies. The cache evicts whole
     * hosts to stay in budget, and a Lyke entry pointing into an evicted directory is a post that
     * plays nothing with no explanation -- the same trap `LauncherActivity.importLykeVideo` copies to
     * avoid. A Lyke post outlives the cache that produced it.
     *
     * DE-DUPLICATED BY CONTENT, not by URL. Revisiting a page re-captures it, the same clip is often
     * served from several URLs, and a feed that gains a duplicate every time the user reloads is
     * useless. The hash is of bytes already on disk, so this costs one read.
     */
    private fun postToLyke(context: Context, video: File, sourceHost: String, pageTitle: String) {
        if (!video.isFile) return

        val digest = hashOf(video) ?: return
        if (!claimLykeUpload(digest)) return

        val mine = File(context.filesDir, "lyke/mine").apply { mkdirs() }
        val target = File(mine, "cached-${digest.take(16)}.${video.extension.ifBlank { "mp4" }}")
        if (!target.isFile) {
            runCatching { video.copyTo(target, overwrite = true) }
                .onFailure {
                    PrismLogger.logError(TAG, "Could not copy ${video.name} into Lyke", it)
                    releaseLykeUpload(digest)
                    return
                }
        }

        // Says where it came from. A feed of unattributed clips lifted off other people's sites is
        // not something to hand to peers without saying so, and the caption is the only place on a
        // Lyke post that can.
        val caption = buildString {
            append(pageTitle.take(120))
            append("\nCached from ")
            append(sourceHost)
        }

        com.prism.launcher.social.LykeStore.addVideo(target.absolutePath, caption)
        PrismLogger.logSuccess(TAG, "Added a cached video from $sourceHost to Lyke")
    }

    private fun hashOf(file: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun lykeLedger(): File = File(PrismSettings.getWebCacheDir(), LYKE_LEDGER_FILE)

    /**
     * Records [digest] as posted, returning false if it already was.
     *
     * Claimed BEFORE the copy rather than after the post, so two captures of the same video racing
     * through here cannot both decide they are first. The claim is released again if the copy fails,
     * which is the only path that can leave a digest recorded with no post behind it.
     */
    private fun claimLykeUpload(digest: String): Boolean = synchronized(indexLock) {
        val posted = readLykeLedger()
        if (digest in posted) return false
        writeLykeLedger(posted + digest)
        true
    }

    private fun releaseLykeUpload(digest: String) = synchronized(indexLock) {
        writeLykeLedger(readLykeLedger() - digest)
    }

    private fun readLykeLedger(): Set<String> {
        val file = lykeLedger()
        if (!file.isFile) return emptySet()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeLykeLedger(digests: Set<String>) {
        PrismSettings.getWebCacheDir().mkdirs()
        val array = JSONArray()
        digests.forEach { array.put(it) }
        runCatching { lykeLedger().writeText(array.toString()) }
            .onFailure { PrismLogger.logError(TAG, "Could not write the Lyke upload ledger", it) }
    }

    private enum class Kind { ASSET, IMAGE, MEDIA }

    private fun kindOf(extension: String): Kind = when (extension) {
        in MEDIA_EXTENSIONS -> Kind.MEDIA
        in IMAGE_EXTENSIONS -> Kind.IMAGE
        else -> Kind.ASSET
    }

    private fun extensionOf(url: URL): String =
        url.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun isVideo(file: File): Boolean =
        file.extension.lowercase() in VIDEO_EXTENSIONS

    /**
     * Downloads one file, refusing anything over its kind's ceiling.
     *
     * The declared length is checked first so an oversized video is abandoned before a single byte of
     * it is read, and the running total enforces the same cap for servers that under-report.
     *
     * @return true when [target] now holds the file.
     */
    private fun fetch(url: URL, kind: Kind, target: File): Boolean {
        val limit = when (kind) {
            Kind.MEDIA -> MAX_MEDIA_BYTES
            Kind.IMAGE -> MAX_IMAGE_BYTES
            Kind.ASSET -> MAX_ASSET_BYTES
        }
        val timeout = if (kind == Kind.MEDIA) MEDIA_TIMEOUT_MS else ASSET_TIMEOUT_MS

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", com.prism.launcher.search.PrismCrawler.USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return false
            if (conn.contentLengthLong > limit) return false

            // STREAMED TO DISK, never held whole in memory. Reading a 40 MB video into a ByteArray
            // and then writing it out costs 40 MB of Java heap for the length of the download, on a
            // device that may already be short of it -- and the bytes are on their way to a file
            // either way. The running total still enforces the cap, because Content-Length is a
            // claim rather than a promise: a server that lies about it, or sends none at all, would
            // otherwise be able to fill the disk.
            target.parentFile?.mkdirs()
            var written = 0L
            val ok = conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > limit) return@use false
                        output.write(buffer, 0, read)
                    }
                    true
                }
            }

            if (!ok) {
                // Over the cap: the partial file is not a smaller asset, it is a broken one.
                runCatching { target.delete() }
                return false
            }
            written > 0L
        } catch (e: Exception) {
            runCatching { target.delete() }
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * References worth saving, in document order, deduplicated.
     *
     * `src` and `href` alone miss most video. A player writes its source as a `<source>` child, its
     * still as `poster`, and lazy-loaded media as `data-src` -- so a pass over those two attributes
     * captured the page's furniture and skipped the clip. `srcset` is reduced to its first candidate:
     * they are the same image at different widths, and caching five sizes of one picture spends the
     * budget a video needs.
     */
    private fun extractReferences(html: String): List<String> {
        val out = LinkedHashSet<String>()

        for (attr in listOf("src", "href", "poster", "data-src", "data-video-src")) {
            for (m in Regex("(?is)\\b$attr\\s*=\\s*[\"']([^\"']+)[\"']").findAll(html)) {
                addReference(out, m.groupValues[1])
            }
        }

        for (m in Regex("(?is)\\bsrcset\\s*=\\s*[\"']([^\"']+)[\"']").findAll(html)) {
            val first = m.groupValues[1].split(',').firstOrNull()?.trim()?.substringBefore(' ')
            if (first != null) addReference(out, first)
        }

        return out.toList()
    }

    private fun addReference(out: MutableSet<String>, raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        // Nothing fetchable: scripts, mail links, in-page anchors, and data/blob URLs that are either
        // already inline or exist only inside the page's own JavaScript.
        if (value.startsWith("javascript:") || value.startsWith("mailto:") ||
            value.startsWith("data:") || value.startsWith("#") ||
            value.startsWith("blob:") || value.startsWith("tel:")
        ) return
        out.add(value)
    }

    /**
     * Points the saved page at the saved files.
     *
     * Two jobs. This site's own absolute URLs become root-relative, because the copy is served from a
     * different name (`host.cache.p2p`) and an absolute URL would send the page back out to the live
     * internet -- the one thing a cached copy must not need. And every cross-origin file that was
     * actually kept is repointed at where it landed; the ones that were not kept are left alone, so
     * they still load when there is a network and fail harmlessly when there is not.
     */
    private fun rewriteToLocalPaths(
        html: String,
        pageUrl: URL,
        rewrites: Map<String, String>,
    ): String {
        val host = Regex.escape(pageUrl.host)
        var out = html
            .replace(Regex("(?i)https?://$host(?=[/\"'])"), "")
            .replace(Regex("(?i)(?<=[\"'])//$host(?=[/\"'])"), "")

        // Longest first: a short reference that is a prefix of a longer one would otherwise corrupt
        // it -- replacing `/v/a.mp4` inside `/v/a.mp4?x` leaves a mangled tail.
        for ((reference, local) in rewrites.entries.sortedByDescending { it.key.length }) {
            out = out.replace(reference, local)
        }
        return out
    }

    private fun extractTitle(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    /**
     * Turns `evaluateJavascript`'s result back into a string.
     *
     * That callback hands over a JSON *value*, so the HTML arrives quoted and escaped. Wrapping it
     * in an array is the shortest way to get a parser to unescape it correctly; doing it by hand
     * with `replace` gets `\\n` wrong the first time a page contains a literal backslash.
     */
    private fun decodeJsString(encoded: String?): String? {
        if (encoded.isNullOrBlank() || encoded == "null") return null
        return runCatching { JSONArray("[$encoded]").getString(0) }.getOrNull()
    }

    // ── Index ──────────────────────────────────────────────────────────────

    private fun indexFile(): File = File(PrismSettings.getWebCacheDir(), INDEX_FILE)

    /**
     * Last known index, so repeated reads do not go to disk.
     *
     * Worth having because the settings screen asks for the cache size several times while building
     * one list -- each row that mentions it, plus its own enabled check -- and that happens on the
     * main thread. Only ever written under [indexLock] together with the file, so the two cannot
     * disagree.
     */
    @Volatile
    private var snapshot: List<CachedSite>? = null

    /** Every cached host, most recently saved first. */
    fun sites(): List<CachedSite> = synchronized(indexLock) { readIndex() }
        .sortedByDescending { it.lastSavedAt }

    fun totalBytes(): Long = sites().sumOf { it.bytes }

    private fun readIndex(): List<CachedSite> {
        snapshot?.let { return it }

        val file = indexFile()
        if (!file.isFile) return emptyList<CachedSite>().also { snapshot = it }
        return runCatching {
            val json = JSONObject(file.readText())
            val out = ArrayList<CachedSite>()
            json.keys().forEach { host ->
                val obj = json.getJSONObject(host)
                out.add(
                    CachedSite(
                        host = host,
                        pages = obj.optInt("pages", 0),
                        bytes = obj.optLong("bytes", 0L),
                        lastSavedAt = obj.optLong("last", 0L),
                        lastTitle = obj.optString("title"),
                    )
                )
            }
            out.toList().also { snapshot = it }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(sites: List<CachedSite>) {
        PrismSettings.getWebCacheDir().mkdirs()
        val json = JSONObject()
        for (site in sites) {
            json.put(
                site.host,
                JSONObject().apply {
                    put("pages", site.pages)
                    put("bytes", site.bytes)
                    put("last", site.lastSavedAt)
                    put("title", site.lastTitle)
                }
            )
        }
        runCatching { indexFile().writeText(json.toString()) }
            .onFailure { PrismLogger.logError(TAG, "Could not write the cache index", it) }
        // Updated even if the write failed: the in-memory list is what actually happened to the
        // files, and reverting it would make the app disagree with its own disk.
        snapshot = sites
    }

    /**
     * Folds one freshly written page into its host's entry.
     *
     * A page re-captured on a later visit does not bump the page count -- the file was overwritten,
     * not added -- so the count is recomputed from disk rather than incremented. Cheap, and it
     * cannot drift the way a running total does.
     */
    private fun recordPage(host: String, relativePath: String, title: String) {
        synchronized(indexLock) {
            val existing = readIndex().filterNot { it.host.equals(host, ignoreCase = true) }
            val dir = dirFor(host)
            val pages = dir.walkTopDown().filter { it.isFile && it.extension.equals("html", true) }.count()
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            writeIndex(
                existing + CachedSite(
                    host = host.lowercase(),
                    pages = pages,
                    bytes = bytes,
                    lastSavedAt = System.currentTimeMillis(),
                    lastTitle = title.take(160),
                )
            )
        }
        PrismLogger.logDebug(TAG, "Cached $host/$relativePath")
    }

    /**
     * Drops whole hosts, least recently saved first, until the cache fits its budget.
     *
     * By host rather than by page: half a site is worse than none -- its internal links break and
     * it looks like the cache is failing -- and a host is also the unit that gets published, so a
     * partial eviction would leave a hosted site serving 404s.
     */
    private fun evictIfOverBudget(context: Context) {
        val sites = synchronized(indexLock) { readIndex() }
        var total = sites.sumOf { it.bytes }
        if (total <= MAX_TOTAL_BYTES) return

        val oldestFirst = sites.sortedBy { it.lastSavedAt }.toMutableList()
        while (total > MAX_TOTAL_BYTES && oldestFirst.isNotEmpty()) {
            val victim = oldestFirst.removeAt(0)
            remove(context, victim.host)
            total -= victim.bytes
            PrismLogger.logInfo(TAG, "Evicted ${victim.host} from the web cache to stay in budget")
        }
    }

    // ── Removal ────────────────────────────────────────────────────────────

    /**
     * Forgets one cached host: unpublished, dropped from the index, deleted from disk.
     *
     * Deletes only under the cache directory. The hosted-site list also holds folders the user
     * pointed at themselves through P2P hosting, and a name collision must never turn into a
     * recursive delete of somebody's originals -- the same protection [PrismMirrorManager.removeSite]
     * spells out for mirrors.
     */
    fun remove(context: Context, host: String): Boolean {
        val key = host.lowercase()
        unpublish(context, key)

        synchronized(indexLock) {
            val remaining = readIndex().filterNot { it.host.equals(key, ignoreCase = true) }
            writeIndex(remaining)
        }

        val dir = dirFor(key)
        val root = runCatching { PrismSettings.getWebCacheDir().canonicalFile }.getOrNull() ?: return false
        val underCache = runCatching {
            dir.canonicalFile.toPath().startsWith(root.toPath())
        }.getOrDefault(false)

        if (!underCache) {
            PrismLogger.logWarning(TAG, "Left ${dir.absolutePath} alone: outside the cache directory")
            return false
        }
        return !dir.exists() || dir.deleteRecursively()
    }

    /** Empties the cache entirely. Used when the user turns caching off and asks to discard it. */
    fun clearAll(context: Context) {
        sites().forEach { remove(context, it.host) }
    }

    // ── Publishing to the mesh ─────────────────────────────────────────────

    /**
     * Registers one cached host as a mesh site under its `.cache.p2p` name.
     *
     * Idempotent, so it is safe to call on every capture. Announces through the same two steps a
     * mirror uses -- a DNS record pointing at this device, then a gossip broadcast -- which is what
     * lets a peer resolve the name at all.
     */
    fun publish(context: Context, host: String) {
        val domain = host.lowercase() + MESH_SUFFIX
        val localPath = dirFor(host).absolutePath

        val hosted = PrismSettings.getP2pHostedSites().toMutableList()
        if (hosted.none { it.domain.equals(domain, ignoreCase = true) }) {
            hosted.add(
                PrismSettings.P2pHostedSite(domain = domain, localPath = localPath, isActive = true)
            )
            PrismSettings.setP2pHostedSites(hosted)
        }

        P2pDnsManager.updateRecord(context, domain, "127.0.0.1", isVerified = true)
        com.prism.launcher.mesh.PrismMeshService.broadcastDnsUpdate(domain)
    }

    /**
     * Stops serving one cached host.
     *
     * Only the entry whose localPath is this cache's is removed, so a domain the user happens to
     * host from their own folder is left alone. As with mirrors there is no unpublish opcode: peers
     * hold the record until it is replaced, and get a 404 meanwhile, which is the truthful answer
     * once the content is no longer served.
     */
    fun unpublish(context: Context, host: String) {
        val domain = host.lowercase() + MESH_SUFFIX
        val localPath = dirFor(host).absolutePath

        PrismSettings.setP2pHostedSites(
            PrismSettings.getP2pHostedSites().filterNot {
                it.domain.equals(domain, ignoreCase = true) && it.localPath == localPath
            }
        )
        P2pDnsManager.deleteRecord(context, domain)
        PrismWebHost.clearCache(domain)
    }

    /** Publishes every cached host. Called when the user turns mesh sharing on. */
    fun publishAll(context: Context) {
        val cached = sites()
        cached.forEach { publish(context, it.host) }
        PrismLogger.logSuccess(TAG, "Published ${cached.size} cached site(s) to the mesh")
    }

    /** Unpublishes every cached host, leaving the files in place. Turning sharing back on restores them. */
    fun unpublishAll(context: Context) {
        val cached = sites()
        cached.forEach { unpublish(context, it.host) }
        PrismLogger.logInfo(TAG, "Stopped sharing ${cached.size} cached site(s)")
    }
}
