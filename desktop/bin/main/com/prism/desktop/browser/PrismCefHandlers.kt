package com.prism.desktop.browser

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.browser.HostBlocklist
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The policy layer around Chromium: P2P interception, ad blocking, and font injection.
 *
 * PORTS `PrismWebViewClient` FEATURE FOR FEATURE. That class does four things inside
 * `shouldInterceptRequest`, and all four are here:
 *
 *   1. `.p2p` domains are fetched over the mesh tunnel rather than the network, in BOTH normal
 *      and private tabs. This is the point of the whole browser; it is not a privacy feature.
 *   2. Per-host health tracking. A `.p2p` host that times out is marked unhealthy for 30 seconds,
 *      during which sub-resources skip the mesh entirely rather than each waiting for their own
 *      timeout -- without this a page with forty images takes forty timeouts to fail.
 *   3. Ad blocking, in private tabs ONLY. That asymmetry is deliberate in the Android build and
 *      is preserved rather than "improved".
 *   4. The user's chosen font, injected as CSS on page load.
 *
 * Different timeouts for the main frame (8s connect, 10s read) than for sub-resources (2s, 3s),
 * again as Android has them: a slow main document is worth waiting for, a slow favicon is not.
 */
class PrismCefHandlers(
    private val isPrivateTab: Boolean,
    private val blocklist: HostBlocklist,
    private val onUrl: (String) -> Unit,
    private val onTitle: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onP2pResolution: (String, Boolean) -> Unit,
) {

    /** Host -> when it last failed. Mirrors PrismWebViewClient's hostHealthMap. */
    private val hostHealth = HashMap<String, Long>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val displayHandler = object : CefDisplayHandlerAdapter() {
        override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
            if (frame?.isMain == true && url != null) onUrl(url)
        }

        override fun onTitleChange(browser: CefBrowser?, title: String?) {
            if (!title.isNullOrBlank()) onTitle(title)
        }
    }

    val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(
            browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean,
        ) {
            onProgress(if (isLoading) 40 else 100)
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            onProgress(100)
            injectFontCss(browser)
        }
    }

    val requestHandler = object : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler = resourceHandler
    }

    private val resourceHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
        ): CefResourceHandler? {
            val url = request?.url ?: return null
            val host = hostOf(url) ?: return null
            val isMainFrame = frame?.isMain == true

            // 1. P2P interception -- universal, both tab types.
            if (isP2pDomain(host)) {
                onP2pResolution(host, true)
                val now = System.currentTimeMillis()
                val lastFail = synchronized(hostHealth) { hostHealth[host] ?: 0L }
                val healthy = (now - lastFail) > HEALTH_TRACK_MS
                if (!healthy && !isMainFrame) return SubResourceFallback(url)
                return MeshResourceHandler(url, host, isMainFrame)
            }
            onP2pResolution(host, false)

            // 2. Ad blocking -- private tabs only, exactly as on Android.
            if (isPrivateTab && blocklist.shouldBlockHost(host)) {
                return EmptyResourceHandler()
            }

            return null
        }
    }

    // ── Mesh fetch ───────────────────────────────────────────────────────────────────────────

    /**
     * Serves a `.p2p` URL from the mesh.
     *
     * CEF drives a resource handler as a state machine across threads: `processRequest` starts
     * the work and must return quickly, `getResponseHeaders` fills in the status, then `readResponse`
     * is called repeatedly for the body. The fetch therefore happens on its own thread and the
     * result is handed back through `callback.Continue()`.
     */
    private inner class MeshResourceHandler(
        private val url: String,
        private val host: String,
        private val isMainFrame: Boolean,
    ) : CefResourceHandler {

        private var stream: InputStream? = null
        private var status = 200
        private var mimeType = "text/html"
        private var length = -1

        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            Thread({
                try {
                    val connectMs = if (isMainFrame) 8000L else 2000L
                    val readMs = if (isMainFrame) 10000L else 3000L
                    val client = http.newBuilder()
                        .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                        .readTimeout(readMs, TimeUnit.MILLISECONDS)
                        .build()

                    val response = client.newCall(
                        Request.Builder().url(meshUrl(url)).build()
                    ).execute()

                    status = response.code
                    mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()
                        ?: "text/html"
                    stream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                } catch (e: Exception) {
                    synchronized(hostHealth) { hostHealth[host] = System.currentTimeMillis() }
                    if (isMainFrame) {
                        status = 503
                        mimeType = "text/html"
                        stream = ByteArrayInputStream(meshUnreachableHtml(host).toByteArray())
                    } else {
                        status = 404
                        mimeType = "text/plain"
                        stream = ByteArrayInputStream(ByteArray(0))
                    }
                }
                callback?.Continue()
            }, "prism-mesh-fetch").apply { isDaemon = true }.start()
            return true
        }

        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
            response?.status = status
            response?.mimeType = mimeType
            responseLength?.set(length)
        }

        override fun readResponse(out: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
            val s = stream ?: return false
            val n = try { s.read(out, 0, bytesToRead) } catch (e: Exception) { -1 }
            return if (n <= 0) {
                bytesRead?.set(0)
                false
            } else {
                bytesRead?.set(n)
                true
            }
        }

        override fun cancel() {
            try { stream?.close() } catch (e: Exception) { }
            stream = null
        }
    }

    /** Silent 404 for a sub-resource of an unhealthy host. */
    private inner class SubResourceFallback(private val url: String) : CefResourceHandler {
        private var stream: InputStream = ByteArrayInputStream(ByteArray(0))

        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            callback?.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
            response?.status = 404
            response?.mimeType = "text/plain"
            responseLength?.set(0)
        }

        override fun readResponse(out: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
            bytesRead?.set(0)
            return false
        }

        override fun cancel() = Unit
    }

    /** A blocked request: 200 with nothing in it, which breaks fewer pages than an error does. */
    private class EmptyResourceHandler : CefResourceHandler {
        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            callback?.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
            response?.status = 200
            response?.mimeType = "text/plain"
            responseLength?.set(0)
        }

        override fun readResponse(out: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
            bytesRead?.set(0)
            return false
        }

        override fun cancel() = Unit
    }

    // ── Font injection ───────────────────────────────────────────────────────────────────────

    /**
     * Applies the user's chosen font to page content.
     *
     * `PrismFontEngine.getWebViewCss` on Android; the same idea via `executeJavaScript` here.
     * Backticks in the CSS are escaped because the script embeds it in a template literal --
     * the Android version has the same escape for the same reason.
     */
    private fun injectFontCss(browser: CefBrowser?) {
        val css = webFontCss() ?: return
        val script = """
            (function() {
                var s = document.createElement('style');
                s.type = 'text/css';
                s.innerHTML = `${css.replace("`", "\\`")}`;
                document.head.appendChild(s);
            })();
        """.trimIndent()
        try {
            browser?.executeJavaScript(script, browser.url, 0)
        } catch (e: Exception) {
            PrismPlatform.log.debug("Prism/browser", "Font injection failed: ${e.message}")
        }
    }

    private fun webFontCss(): String? {
        val path = when (PrismSettings.getFontStyle()) {
            PrismSettings.FONT_STYLE_CUSTOM -> PrismSettings.getCustomFontPath()
            else -> return null
        }
        if (path.isBlank() || !java.io.File(path).isFile) return null
        val uri = java.io.File(path).toURI().toString()
        return """
            @font-face { font-family: 'PrismUser'; src: url('$uri'); }
            * { font-family: 'PrismUser' !important; }
        """.trimIndent()
    }

    companion object {
        private const val HEALTH_TRACK_MS = 30_000L

        fun hostOf(url: String): String? = try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

        /** `.p2p` is Prism's mesh TLD, matching P2pDnsManager.isP2pDomain. */
        fun isP2pDomain(host: String): Boolean =
            host.endsWith(".p2p") || host.endsWith(".p2p.remote")

        /**
         * Where the local mesh proxy serves `.p2p` content.
         *
         * The Android build hands the URL to `PrismTunnelEngine.fetchMeshContent`, which is not
         * ported yet (Phase 7 of the plan -- it needs WinTun on Windows). Until it is, requests
         * go to the local proxy port, which is the same thing the engine talks to.
         */
        fun meshUrl(url: String): String {
            val port = PrismSettings.getMeshBootstrapPort()
            val host = hostOf(url) ?: return url
            val path = try { URI(url).path.orEmpty().ifBlank { "/" } } catch (e: Exception) { "/" }
            return "http://127.0.0.1:$port/_p2p/$host$path"
        }

        fun meshUnreachableHtml(host: String): String = """
            <html><body style="font-family:sans-serif;padding:32px;color:#ccc;background:#111">
            <h2>&#x26D4; Mesh Unreachable</h2>
            <p>Could not connect to <b>$host</b> via the P2P network.</p>
            <p style="color:#888;font-size:0.9em">Check that the domain is mapped correctly in
            P2P DNS settings and that the peer node is online. On desktop the mesh tunnel itself
            is not ported yet, so only peers reachable through the local proxy will resolve.</p>
            </body></html>
        """.trimIndent()
    }
}
