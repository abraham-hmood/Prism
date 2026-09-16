package com.prism.launcher.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Response
import java.io.ByteArrayInputStream

class PrismWebViewClient(
    private val blocklist: HostBlocklist,
    private val isPrivateTab: Boolean,
    private val onTitle: (String) -> Unit,
    private val onUrl: (String) -> Unit,
    private val getEngine: () -> PrismTunnelEngine?,
    /**
     * The page has finished loading and its DOM is complete.
     *
     * Separate from [onUrl], which fires on navigation START as well and therefore cannot be used
     * to capture a page: at that point there is nothing rendered to capture.
     */
    private val onPageComplete: (WebView, String) -> Unit = { _, _ -> },
) : WebViewClient() {

    private val hostHealthMap = mutableMapOf<String, Long>() // host -> last_failure_timestamp
    private val HEALTH_TRACK_MS = 30000L // 30 seconds

    // NOTE: History recording must explicitly check 'isPrivateTab' to ensure
    // private browsing URLs are NEVER logged to a persistent database.

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return super.shouldInterceptRequest(view, request)
        val host = url.host ?: return super.shouldInterceptRequest(view, request)

        // 0. A cached page this device holds, served straight off local disk.
        //
        // BEFORE the mesh branch, for the same reason P2pDnsManager.resolve prefers 127.0.0.1: a
        // copy that is already here should never be fetched over the network. It also means the
        // cache is readable with sharing turned OFF, when there is deliberately no DNS record and
        // no hosted-site entry for any peer -- or this device -- to resolve.
        serveFromLocalCache(host, url)?.let { return it }

        // 0b. The REAL site, while there is no way to reach it.
        //
        // This is what makes a cache worth having. Publishing under `.cache.p2p` keeps cached copies
        // from impersonating live sites -- see PrismWebCache -- but that name is no help to somebody
        // who has turned their data off and typed the address they actually know. Serving the real
        // URL from disk is safe HERE in a way that a DNS record is not: the decision is local, made
        // per request, and conditional on being offline, so nothing is written down, nothing reaches
        // a peer, and the live site wins again the moment there is a network.
        serveOfflineCopy(view, host, url, request.isForMainFrame)?.let { return it }

        // 1. P2P Domain interception (Universal — works in public AND private tabs)
        if (P2pDnsManager.resolve(host, onlyP2p = true) != null) {
            val now = System.currentTimeMillis()
            val lastFail = hostHealthMap[host] ?: 0L
            val isHealthy = (now - lastFail) > HEALTH_TRACK_MS

            if (!isHealthy && !request.isForMainFrame) {
                // If the host is unhealthy, immediately skip mesh fetch for sub-resources
                return handleSubResourceFallback(view?.context, url)
            }

            val engine = getEngine()
            
            // Sub-resources get a much shorter timeout to prevent page hanging
            val cTimeout = if (request.isForMainFrame) 8000L else 2000L
            val rTimeout = if (request.isForMainFrame) 10000L else 3000L
            
            val response: Response? = engine?.fetchMeshContent(url.toString(), cTimeout, rTimeout)
 
            if (response == null) {
                // Mark host as unhealthy on timeout
                hostHealthMap[host] = System.currentTimeMillis()

                if (request.isForMainFrame) {
                    // Return an explicit error page ONLY for the main frame.
                    val errorHtml = """
                        <html><body style="font-family:sans-serif;padding:32px;color:#ccc;background:#111">
                        <h2>&#x26D4; Mesh Unreachable</h2>
                        <p>Could not connect to <b>$host</b> via the P2P network.</p>
                        <p style="color:#888;font-size:0.9em">Check that the domain is mapped correctly in P2P DNS settings and that the peer node is online.</p>
                        </body></html>
                    """.trimIndent()
                    return WebResourceResponse(
                        "text/html", "utf-8", 503, "Service Unavailable",
                        mapOf("Content-Type" to "text/html; charset=utf-8"),
                        java.io.ByteArrayInputStream(errorHtml.toByteArray())
                    )
                } else {
                    // Silent fallback for sub-resources (favicon, scripts, etc.)
                    return handleSubResourceFallback(view?.context, url)
                }
            }

            val statusCode = response.code
            if (statusCode == 404 && !request.isForMainFrame) {
                return handleSubResourceFallback(view?.context, url)
            }

            val contentType = response.header("Content-Type") ?: "text/html"
            val parts = contentType.split(";")
            val mimeType = parts[0].trim().lowercase()
            var encoding = "utf-8"
            for (i in 1 until parts.size) {
                val p = parts[i].trim().lowercase()
                if (p.startsWith("charset=")) {
                    encoding = p.substringAfter("charset=").trim()
                    break
                }
            }

            val headers = mutableMapOf<String, String>()
            for (name in response.headers.names()) {
                response.header(name)?.let { headers[name] = it }
            }

            val reasonPhrase = response.message.ifBlank {
                when (statusCode) {
                    200 -> "OK"; 301 -> "Moved Permanently"; 302 -> "Found"
                    404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Unknown"
                }
            }

            return WebResourceResponse(
                mimeType, encoding, statusCode, reasonPhrase, headers,
                response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
            )
        }

        // 2. Ad-blocking (Private Mode ONLY)
        if (isPrivateTab && blocklist.shouldBlockHost(host)) {
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        return super.shouldInterceptRequest(view, request)
    }

    /**
     * Answers a `<host>.cache.p2p` request from this device's own web cache, or null to let the
     * request go on to the mesh.
     *
     * Null rather than a 404 when the file is missing: a peer may well be holding the page this
     * device never cached, and the mesh branch below is what finds it. Returning an error here
     * would make every cache name resolvable only on the device that happened to capture it.
     */
    private fun serveFromLocalCache(host: String, url: android.net.Uri): WebResourceResponse? {
        val cachedHost = PrismWebCache.hostForMeshDomain(host) ?: return null
        val file = PrismWebCache.resolveLocal(cachedHost, url.path ?: "/") ?: return null
        return cachedFileResponse(file, cachedHost, withNotice = false)
    }

    /**
     * The cached copy of [host]'s page, but only while the device is offline.
     *
     * The offline check runs LAST, after establishing that a copy exists, because it is the more
     * expensive of the two and the overwhelmingly common case is a request for something never
     * cached at all.
     */
    private fun serveOfflineCopy(
        view: WebView?,
        host: String,
        url: android.net.Uri,
        isMainFrame: Boolean,
    ): WebResourceResponse? {
        if (host.endsWith(".p2p", ignoreCase = true)) return null
        val file = PrismWebCache.resolveLocal(host, url.path ?: "/") ?: return null

        val context = view?.context ?: com.prism.launcher.PrismApp.instance
        if (!PrismWebCache.isOffline(context)) return null

        // Only the page itself gets the banner; stamping it into every cached stylesheet would
        // corrupt them, and an image is not a document to annotate.
        val isHtml = file.extension.equals("html", ignoreCase = true)
        return cachedFileResponse(file, host, withNotice = isMainFrame && isHtml)
    }

    private fun cachedFileResponse(
        file: java.io.File,
        cachedHost: String,
        withNotice: Boolean,
    ): WebResourceResponse {
        val extension = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"

        val body: java.io.InputStream = if (withNotice) {
            java.io.ByteArrayInputStream(PrismWebCache.htmlWithOfflineNotice(file))
        } else {
            file.inputStream()
        }

        return WebResourceResponse(
            mime,
            if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "utf-8" else null,
            200,
            "OK",
            // Explicitly not cacheable by the WebView: the file on disk is the cache, and a second
            // layer of it would keep serving a page the user has since re-captured or evicted.
            mapOf("Cache-Control" to "no-store", "X-Prism-Cache" to cachedHost),
            body,
        )
    }

    /**
     * Last resort for a page that failed to load: offer the cached copy under its own name.
     *
     * Covers what the offline check in [serveOfflineCopy] cannot -- a network that reports itself
     * validated but cannot reach this particular host, a DNS failure, a site that is simply down.
     * The redirect is to the `.cache.p2p` name rather than a silent swap at the real address,
     * because here the user asked for the live site and got something else, and the address bar is
     * the only place that can say so.
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)

        if (view == null || request == null || !request.isForMainFrame) return
        val url = request.url ?: return
        val host = url.host ?: return
        // Already the cached name: there is nothing further to fall back to, and redirecting to
        // ourselves would loop.
        if (host.endsWith(".p2p", ignoreCase = true)) return
        if (!PrismWebCache.hasCopyOf(url.toString())) return

        view.loadUrl("http://$host${PrismWebCache.MESH_SUFFIX}${url.path.orEmpty()}")
    }

    private fun handleSubResourceFallback(context: android.content.Context?, url: android.net.Uri): WebResourceResponse {
        val path = url.path?.lowercase() ?: ""
        val isImage = path.endsWith(".png") || path.endsWith(".jpg") || 
                      path.endsWith(".jpeg") || path.endsWith(".gif") || 
                      path.endsWith(".ico") || path.endsWith(".svg") || 
                      path.endsWith(".webp")
        
        if (isImage) {
            val ctx = context ?: com.prism.launcher.PrismApp.instance
            val iconBytes = PrismWebHost.getAppIconBytes(ctx)
            if (iconBytes != null) {
                return WebResourceResponse("image/png", "utf-8", 200, "OK", 
                    mapOf("Content-Type" to "image/png"), 
                    java.io.ByteArrayInputStream(iconBytes))
            }
        }
        
        // Default silent 404 for other sub-resources (scripts, CSS, etc.)
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", 
            mapOf("Content-Type" to "text/plain"), 
            java.io.ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) onUrl(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        val title = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "")
        onTitle(title)
        if (url != null) onUrl(url)

        // The DOM is complete here and nowhere earlier, which is what makes a capture possible at
        // all. The callback decides for itself whether this page qualifies.
        if (view != null && url != null) onPageComplete(view, url)

        // --- Custom Font Injection ---
        view?.context?.let { ctx ->
            val css = com.prism.launcher.PrismFontEngine.getWebViewCss(ctx)
            if (css.isNotEmpty()) {
                val script = """
                    (function() {
                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = `${css.replace("`", "\\`")}`;
                        document.head.appendChild(style);
                    })();
                """.trimIndent()
                view.evaluateJavascript(script, null)
            }
        }
        // -----------------------------
    }
}
