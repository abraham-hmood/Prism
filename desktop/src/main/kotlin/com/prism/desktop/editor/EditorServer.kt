package com.prism.desktop.editor

import com.prism.core.PrismPlatform
import com.prism.launcher.editor.EditorAssets
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * A loopback HTTP server for the editor's front end. PHASE 93.
 *
 * ## Why a server and not `file://`
 *
 * Monaco loads through its own AMD loader, which fetches `vs/editor/editor.main.js` and dozens of
 * other modules, and it starts web workers. Chromium applies its file-scheme restrictions to all of
 * that: a `file://` page gets a null origin, cannot create a worker from a file URL, and treats every
 * sibling file as cross-origin. The usual answer is `--allow-file-access-from-files`, which turns
 * those protections off for the whole browser process -- including the Browser page, which loads
 * arbitrary websites in the same [org.cef.CefApp]. That is not a trade worth making for an editor.
 *
 * A real origin on 127.0.0.1 costs about ninety lines and the editor gets a normal web page's
 * security model. Android reaches the same place with `WebViewAssetLoader`, which synthesises an
 * https origin for exactly this reason -- so this is the same decision, made with the tool each
 * platform has.
 *
 * ## BOUND TO LOOPBACK, ON AN EPHEMERAL PORT, WITH A TOKEN
 *
 *  - `InetAddress.getLoopbackAddress()`, so nothing off this machine can reach it. Binding to the
 *    wildcard would publish the editor to the local network.
 *  - Port 0, so the OS picks one. A fixed port is a thing another program can squat, and the failure
 *    mode of squatting an editor's asset server is serving the editor somebody else's page.
 *  - A random path token on `/assets/`. Loopback is not a boundary on a shared machine: another
 *    local user's browser can reach 127.0.0.1 too, and the token means knowing the port is not
 *    enough to drive the editor's front end.
 *
 * ## `/monaco/` IS DELIBERATELY NOT BEHIND THE TOKEN
 *
 * `prism-editor.js` configures the AMD loader with `paths: { vs: '/monaco/vs' }` -- an absolute path,
 * hardcoded, and SHARED WITH ANDROID, where the same string is what `WebViewAssetLoader` maps. Making
 * it token-dependent would mean the two platforms' front ends differed in the one file that exists to
 * be identical, so the server accepts that path as it is written.
 *
 * It is an acceptable exception rather than a hole, because of what is actually behind each path:
 * `/monaco/` is minified JavaScript downloaded from npm, published to the world, and identical on
 * every install. The user's source tree is NOT served over HTTP at all -- it reaches the editor
 * through the bridge, where `EditorBridge.permitted` is the sandbox. There is nothing private here
 * for a token to protect.
 *
 * ## What it serves, and nothing else
 *
 * The front end out of the module's resources, and Monaco out of its install directory. Both paths
 * are canonicalised and checked against their root before anything is read, because `..` survives
 * percent-decoding and the JDK's `HttpServer` does not normalise it away.
 */
object EditorServer {

    private const val TAG = "PrismEditor"

    private var server: HttpServer? = null

    @Volatile
    private var token: String = ""

    @Volatile
    private var port: Int = 0

    /**
     * The page to load, or null if the server could not start.
     *
     * The path mirrors Android's `/assets/editor/index.html` exactly, so the front end's relative
     * references -- `prism-editor.js`, `new Worker('extension-host.js')` -- resolve the same way on
     * both platforms.
     */
    @Synchronized
    fun indexUrl(): String? {
        start() ?: return null
        return "http://127.0.0.1:" + port + "/" + token + "/assets/editor/index.html"
    }

    /** True for a URL this server produced, so the page can tell its own navigation apart. */
    fun isOurs(url: String): Boolean =
        port != 0 && token.isNotEmpty() && url.startsWith("http://127.0.0.1:" + port + "/" + token + "/")

    @Synchronized
    fun start(): HttpServer? {
        server?.let { return it }

        val created = runCatching {
            // Port 0 and loopback only. See the class comment.
            val instance = HttpServer.create(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0,
            )
            token = java.util.UUID.randomUUID().toString().replace("-", "")
            instance.createContext("/") { exchange -> handle(exchange) }
            // The default executor runs handlers on the dispatch thread one at a time, which
            // deadlocks Monaco: its loader fetches dozens of modules in parallel and a worker's
            // request would queue behind the page that is waiting for it.
            instance.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "editor-http").apply { isDaemon = true }
            }
            instance.start()
            port = instance.address.port
            instance
        }.getOrElse {
            PrismPlatform.log.error(TAG, "The editor's asset server would not start", it)
            return null
        }

        server = created
        PrismPlatform.log.info(TAG, "Editor assets on 127.0.0.1:" + port)
        return created
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop(0) } }
        server = null
        port = 0
        token = ""
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val raw = exchange.requestURI.path.orEmpty()
            val path = URLDecoder.decode(raw, "UTF-8")

            // Monaco first, and ungated. See the class comment for why this one path is an exception.
            if (path.startsWith("/monaco/")) {
                serveUnder(
                    exchange,
                    root = EditorAssets.monacoDir(),
                    relative = path.removePrefix("/monaco/"),
                )
                return
            }

            // Everything else carries the token as its first segment, checked before any path is
            // resolved so a request without it never reaches the filesystem.
            val expected = "/" + token + "/assets/"
            if (token.isEmpty() || !path.startsWith(expected)) {
                respond(exchange, 404, "text/plain", "Not found".toByteArray())
                return
            }
            serveResource(exchange, path.removePrefix(expected))
        } catch (failure: Throwable) {
            PrismPlatform.log.warn(TAG, "Editor request failed: " + failure.message)
            runCatching { respond(exchange, 500, "text/plain", "Error".toByteArray()) }
        }
    }

    /**
     * Serves a file from under [root], or 404.
     *
     * CANONICALISED AND CHECKED. `..` in a URL path survives percent-decoding, and the JDK's
     * `HttpServer` does not normalise it for you -- so without this, `GET /<token>/monaco/../../../`
     * would walk out of the install directory.
     */
    private fun serveUnder(exchange: HttpExchange, root: File, relative: String) {
        val base = runCatching { root.canonicalFile }.getOrNull()
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull()
        if (base == null || file == null ||
            !(file.path == base.path || file.path.startsWith(base.path + File.separator))
        ) {
            PrismPlatform.log.warn(TAG, "Refused an editor path that escapes its root: " + relative)
            respond(exchange, 403, "text/plain", "Forbidden".toByteArray())
            return
        }
        if (!file.isFile) {
            respond(exchange, 404, "text/plain", "Not found".toByteArray())
            return
        }
        respond(exchange, 200, contentTypeOf(file.name), file.readBytes())
    }

    /** The front end itself, out of the module's resources. */
    private fun serveResource(exchange: HttpExchange, relative: String) {
        // Normalised to a flat name rather than resolved: a resource path is not a filesystem path,
        // and `..` in one has no meaning worth supporting.
        if (relative.contains("..")) {
            respond(exchange, 403, "text/plain", "Forbidden".toByteArray())
            return
        }
        val bytes = javaClass.classLoader.getResourceAsStream(relative)?.use { it.readBytes() }
        if (bytes == null) {
            respond(exchange, 404, "text/plain", "Not found".toByteArray())
            return
        }
        respond(exchange, 200, contentTypeOf(relative), bytes)
    }

    private fun respond(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        // No caching: the whole point of this server is that Prism's own files are being developed
        // and an extension's files change when one is installed.
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun contentTypeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "map" -> "application/json; charset=utf-8"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
}
