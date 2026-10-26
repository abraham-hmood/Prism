package com.prism.launcher.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import com.prism.launcher.PrismSettings

/**
 * Points the in-app browser at a Prism proxy server.
 *
 * ## The crash this used to cause
 *
 * [ProxyController] is a WebView API, and WebView's Chromium must be STARTED before its proxy can be
 * changed. Both calls here used to run from whatever background thread happened to reach them. In
 * server mode that is `PrismTunnelEngine`'s "prism-local-server-client" thread, and the result was a
 * fatal `RuntimeException: Must be started before we block!` -- an uncaught exception on a plain
 * Thread, which kills the whole process. The VPN service was then restarted by the system, entered
 * server mode again, and crashed again, so a phone that looked like it was serving a mesh was in a
 * restart loop and had no tunnel at all.
 *
 * Two fixes, both required:
 *
 *  - every WebView proxy call is posted to the main looper, which is where the API has to be used;
 *  - a WebView is created once (also on the main thread) so Chromium is running before the override
 *    is attempted. A device whose WebView cannot start is logged and skipped rather than crashing.
 */
object PrismProxyClient {

    private const val TAG = "PrismProxyClient"

    /**
     * Kept to keep Chromium alive.
     *
     * Created but never attached: a detached WebView is enough to initialize the provider, and
     * destroying it would put the process back in the state this class exists to avoid. It uses the
     * application context so it cannot hold a service or activity alive.
     */
    @Volatile
    private var chromiumBootstrap: WebView? = null

    /**
     * Runs [block] on the UI thread.
     *
     * Failures are logged rather than thrown: this is an optimisation of the browser's routing, and a
     * device with no WebView must still be able to run a mesh server.
     */
    private fun onMainThread(what: String, block: () -> Unit) {
        val main = Looper.getMainLooper()
        val work = Runnable {
            runCatching { block() }.onFailure {
                Log.w(TAG, "Could not $what: ${it.message}", it)
            }
        }
        if (Looper.myLooper() == main) work.run() else Handler(main).post(work)
    }

    private fun ensureChromium(context: Context) {
        if (chromiumBootstrap != null) return
        val app = context.applicationContext ?: context
        chromiumBootstrap = runCatching { WebView(app) }.onFailure {
            Log.w(TAG, "WebView is unavailable; browser proxy routing will not be changed", it)
        }.getOrNull()
    }

    fun connectToLocalServer(context: Context, port: Int) {
        onMainThread("route the browser through the local server") {
            ensureChromium(context)
            val builder = ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:$port")
                .addDirect()
            ProxyController.getInstance().setProxyOverride(builder.build(), { it.run() }, {})
        }
    }

    fun start(context: Context) {
        val servers = PrismSettings.getPrismServers()
        if (servers.isEmpty()) return

        onMainThread("route the browser through the Prism server") {
            ensureChromium(context)
            val builder = ProxyConfig.Builder()

            // 1. Add Active server first
            val active = servers.find { it.isActive }
            if (active != null) {
                builder.addProxyRule("http://${active.address}:${active.port}")
            }

            // 2. Add others as fallbacks
            servers.filter { it != active }.forEach {
                builder.addProxyRule("http://${it.address}:${it.port}")
            }

            builder.addDirect()

            ProxyController.getInstance().setProxyOverride(
                builder.build(), { it.run() }, {}
            )
        }
    }

    fun stop() {
        onMainThread("restore the browser's direct routing") {
            ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
        }
    }
}
