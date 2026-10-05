package com.prism.desktop.editor

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.editor.CompletionEngine
import com.prism.launcher.editor.EditorBridge
import com.prism.launcher.editor.ExtensionStore
import com.prism.launcher.editor.jsString
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.io.File

/**
 * The Kotlin half of the editor's bridge, on the desktop. PHASE 93.
 *
 * ## The same three-method protocol, over a different pipe
 *
 * Android gives the page a Java object with `@JavascriptInterface` methods, so the front end calls
 * `Prism.request(id, method, payload)` directly. JCEF has no equivalent; what it has is
 * `CefMessageRouter`, which injects one global function -- `window.cefQuery({request, onSuccess,
 * onFailure})` -- and routes its string argument to Kotlin.
 *
 * So the front end is NOT changed. A shim injected at load defines `window.Prism` with the same three
 * methods, each packing its arguments into one JSON string and handing it to `cefQuery`. The page
 * cannot tell the difference, which is what keeps `prism-editor.js` identical on both platforms.
 *
 * ## Why the shim and not a native function per method
 *
 * `CefMessageRouter` can be configured with a custom function name, so three routers with three names
 * would also work. One router and a shim is less: one place that encodes, one that decodes, and
 * `onSuccess`/`onFailure` wired once rather than three times. It also means a fourth bridge method is
 * a line of JavaScript rather than a new router.
 *
 * ## Replies go back the way Android's do
 *
 * Not through `cefQuery`'s own `onSuccess`, which would be the obvious thing. `PrismEditor.resolve(id,
 * reply)` is what the front end is written against -- the Android side answers asynchronously the same
 * way, precisely so nothing blocks the page's JS thread -- and using the callback here would mean two
 * reply paths to keep in step for no benefit.
 */
class EditorHost(
    private val client: CefClient,
    /** Called when the page reports itself ready, so the page can load state and extensions. */
    private val onReady: () -> Unit,
    /** The page's own notifications, for the Compose shell to react to. */
    private val onNotify: (event: String, args: JSONObject) -> Unit,
) {

    private companion object {
        const val TAG = "PrismEditor"

        /**
         * Defines `window.Prism` in terms of `cefQuery`.
         *
         * Injected on every frame load rather than once: a reload of the page would otherwise find
         * `Prism` undefined and fail at the first bridge call, which looks like the editor hanging
         * on "Loading editor…".
         *
         * Arguments are packed as a JSON array, so a payload containing a separator character cannot
         * be mistaken for an argument boundary -- which a delimiter-joined string would allow, and
         * every payload here is user data (paths, file contents, search text).
         */
        val SHIM = """
            (function () {
              if (window.Prism && window.Prism.__prismDesktop) return;
              function send(parts) {
                window.cefQuery({
                  request: JSON.stringify(parts),
                  persistent: false,
                  onSuccess: function () {},
                  onFailure: function (code, message) {
                    if (window.console) console.error('Prism bridge failed', code, message);
                  }
                });
              }
              window.Prism = {
                __prismDesktop: true,
                request: function (id, method, payload) { send(['request', String(id), method, payload]); },
                nodeSend: function (json) { send(['nodeSend', json]); },
                notify: function (event, payload) { send(['notify', event, payload]); }
              };
            })();
        """.trimIndent()
    }

    @Volatile
    private var browser: CefBrowser? = null

    @Volatile
    var ready: Boolean = false
        private set

    /** The Node extension host, started only when an extension actually needs one. */
    private val node = NodeHost { line -> deliverFromNode(line) }

    /**
     * Attaches the router to the client and returns the browser to show.
     *
     * The router is added before the browser is created, because CEF binds routers per client at
     * creation time -- adding one afterwards leaves `cefQuery` undefined in the page that is already
     * loading, and the failure is a page that sits on its loading message forever.
     */
    fun createBrowser(url: String): CefBrowser {
        val router = org.cef.browser.CefMessageRouter.create()
        router.addHandler(Handler(), true)
        client.addMessageRouter(router)

        // The shim goes in on every load, including reloads. See SHIM.
        client.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
            override fun onLoadEnd(target: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                runCatching { target?.executeJavaScript(SHIM, target.url, 0) }
            }
        })

        val created = client.createBrowser(url, false, false)
        browser = created
        return created
    }

    /** Runs a snippet in the page. A no-op before the page says it is ready. */
    fun js(script: String) {
        if (!ready) return
        val target = browser ?: return
        runCatching { target.executeJavaScript(script, target.url, 0) }
    }

    /** Runs a snippet whether or not the page has reported ready, for the shim and for setup. */
    fun jsNow(script: String) {
        val target = browser ?: return
        runCatching { target.executeJavaScript(script, target.url, 0) }
    }

    fun dispose() {
        ready = false
        node.stop()
        browser = null
    }

    // ── The router ──────────────────────────────────────────────────────────

    private inner class Handler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            target: CefBrowser?,
            frame: CefFrame?,
            queryId: Long,
            request: String?,
            persistent: Boolean,
            callback: CefQueryCallback?,
        ): Boolean {
            val parts = runCatching { JSONArray(request.orEmpty()) }.getOrNull()
                ?: return false
            // Acknowledged immediately. The real answer goes back through PrismEditor.resolve, so
            // holding the callback open would be holding a Chromium IPC slot for nothing.
            callback?.success("")

            // On a worker thread, because a readFile of a large file and a glob over a source tree
            // both happen here and the router's thread is Chromium's.
            Thread({
                runCatching { dispatch(parts) }
                    .onFailure { PrismPlatform.log.warn(TAG, "Bridge call failed: " + it.message) }
            }, "editor-bridge").apply { isDaemon = true }.start()
            return true
        }
    }

    private fun dispatch(parts: JSONArray) {
        when (parts.optString(0)) {
            "request" -> {
                val id = parts.optString(1).toIntOrNull() ?: return
                val method = parts.optString(2)
                val args = runCatching { JSONObject(parts.optString(3)) }.getOrElse { JSONObject() }
                val reply = when {
                    method == "complete" -> completionReply(args)
                    method == "executeCommand" -> "{}"
                    // The page forwards an extension's host calls under a `host:` prefix precisely
                    // so they are recognisable here and cannot collide with the page's own calls.
                    method.startsWith("host:") ->
                        EditorBridge.handle(method.removePrefix("host:"), args)
                    else -> "{}"
                }
                jsNow("window.PrismEditor && PrismEditor.resolve(" + id + ", " + jsString(reply) + ");")
            }

            "nodeSend" -> node.send(parts.optString(1))

            "notify" -> {
                val event = parts.optString(1)
                val args = runCatching { JSONObject(parts.optString(2)) }.getOrElse { JSONObject() }
                if (event == "ready") {
                    ready = true
                    onReady()
                }
                if (event == "extension-failed") {
                    PrismPlatform.log.warn(
                        TAG,
                        "Extension " + args.optString("id") + " did not activate: " +
                            args.optString("message"),
                    )
                }
                onNotify(event, args)
            }
        }
    }

    private fun completionReply(args: JSONObject): String {
        val items = CompletionEngine.complete(
            language = args.optString("language"),
            prefix = args.optString("prefix"),
            lineToCursor = args.optString("line"),
            text = args.optString("text"),
        )
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("label", it.label)
                    .put("kind", it.kind)
                    .put("insert", it.insert)
                    .put("detail", it.detail)
                    .put("doc", it.doc)
                    .put("sort", it.sort),
            )
        }
        return JSONObject().put("items", array).toString()
    }

    private fun deliverFromNode(line: String) {
        // Straight back into the page, which routes it to the extension that is waiting. The
        // protocol between the Worker host and the Node host is deliberately identical, so the page
        // does not care which one answered.
        jsNow("window.PrismEditor && PrismEditor.nodeMessage(" + jsString(line) + ");")
    }

    // ── Extensions ──────────────────────────────────────────────────────────

    /**
     * Hands every enabled extension to the host it was built for.
     *
     * A web extension is read here and shipped across as text, because the Worker has no filesystem.
     * A Node extension travels as a PATH, because Node does -- and only the path lets it `require`
     * its own dependencies out of its own `node_modules`.
     */
    fun loadExtensions() {
        val installed = runCatching { ExtensionStore.installed().filter { it.enabled } }
            .getOrDefault(emptyList())
        if (installed.isEmpty()) return

        if (installed.any { it.runtime == ExtensionStore.RUNTIME_NODE }) {
            // Started before the first activation rather than lazily on the first message: Node
            // takes about a second to boot, and a message sent into a host that is not up yet is
            // lost rather than queued.
            node.start()
            installMissingDependencies(installed)
        }

        js("PrismEditor.startExtensionHost()")
        var activated = 0
        installed.forEach { extension ->
            val runtime = extension.runtime ?: return@forEach
            val source = runCatching { ExtensionStore.entrySource(extension) }.getOrNull()
                ?: return@forEach
            js(
                "PrismEditor.activateExtension(" +
                    jsString(extension.id) + ", " +
                    jsString(extension.directory.absolutePath) + ", " +
                    jsString(source) + ", " +
                    jsString(runtime) + ", " +
                    jsString(contextJson(extension)) + ")",
            )
            activated++
        }
        if (activated > 0) {
            PrismPlatform.log.info(TAG, "Activated " + activated + " extension(s)")
        }
    }

    private fun contextJson(extension: ExtensionStore.Installed): String =
        JSONObject()
            .put("extensionPath", extension.directory.absolutePath)
            .put("globalStoragePath", File(extension.directory, ".storage").apply { mkdirs() }.absolutePath)
            .put("displayName", extension.displayName)
            .put("version", extension.version)
            .toString()

    /**
     * Runs `npm install` for any Node extension that declares dependencies and has none unpacked.
     *
     * Blocking, and only for extensions that need it -- an extension whose `node_modules` is already
     * there is left alone, because re-running npm on every editor open would add seconds to the
     * start for nothing.
     */
    private fun installMissingDependencies(installed: List<ExtensionStore.Installed>) {
        val npm = DesktopNode.npm() ?: return
        installed.filter { it.runtime == ExtensionStore.RUNTIME_NODE }.forEach { extension ->
            val manifest = File(extension.directory, "package.json")
            if (!manifest.isFile) return@forEach
            val declares = runCatching {
                val json = JSONObject(manifest.readText())
                (json.optJSONObject("dependencies")?.length() ?: 0) > 0
            }.getOrDefault(false)
            if (!declares) return@forEach
            if (File(extension.directory, "node_modules").isDirectory) return@forEach

            PrismPlatform.log.info(TAG, "Installing dependencies for " + extension.id)
            runCatching {
                val builder = ProcessBuilder(
                    npm.absolutePath, "install", "--omit=dev", "--no-audit", "--no-fund",
                ).redirectErrorStream(true)
                DesktopNode.prepare(builder, extension.directory)
                val process = builder.start()
                val output = process.inputStream.readBytes().decodeToString()
                // A ceiling, because npm against an unreachable registry waits a long time and this
                // is on the path to the editor being usable.
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    PrismPlatform.log.warn(TAG, "npm install for " + extension.id + " timed out")
                } else if (process.exitValue() != 0) {
                    PrismPlatform.log.warn(
                        TAG,
                        "npm install for " + extension.id + " failed: " + output.takeLast(400),
                    )
                }
            }
        }
    }
}
