package com.prism.launcher.search

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.browser.P2pDnsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * Serves the Prism search engine to connections arriving over the mesh tunnel.
 *
 * The same role [com.prism.launcher.aether.AetherConnectomeHost] and
 * [com.prism.launcher.social.NebulaSocialHost] play for their features: `PrismProxyServer`
 * recognises the reserved domain in a PRISM_CONNECT handshake (or a `Host:` header) and hands the
 * socket here rather than to `PrismWebHost`. The request handling itself lives in
 * [PrismSearchServer.serveHttp], so the mesh and the loopback listener answer identically.
 */
object PrismSearchHost {

    private const val TAG = "PrismSearchHost"

    /**
     * Claims [PrismSettings.PRISM_SEARCH_DOMAIN] in Prism's own DNS so the browser stops resolving
     * it on the public internet.
     *
     * `prism.com` is a real registered domain owned by somebody else, so with no local record a
     * lookup leaves the device and lands on their website -- which is precisely what it did. A P2P
     * DNS record short-circuits that: the proxy resolves the name to this device and dispatches it
     * to the search engine, the same mechanism P2pHostingActivity uses to publish a hosted folder
     * and PrismMirrorManager uses to point a mirrored domain at loopback.
     *
     * Registered whether or not there is a mesh, because the name should work either way: on a
     * mesh server node it points at the mesh IP so peers reach it, and otherwise at loopback so at
     * least this device can. Cheap and idempotent, so it is safe to call on every start.
     */
    fun claimDomain(context: Context) {
        try {
            val onMesh = PrismSettings.isPrismSearchOnMesh()
            val ip = if (onMesh) MeshUtils.getLocalMeshIp().ifBlank { "127.0.0.1" } else "127.0.0.1"
            P2pDnsManager.updateRecord(context, PrismSettings.PRISM_SEARCH_DOMAIN, ip)
            PrismLogger.logSuccess(
                TAG, "Claimed ${PrismSettings.PRISM_SEARCH_DOMAIN} -> $ip " +
                    (if (onMesh) "(mesh)" else "(local)")
            )
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Could not claim ${PrismSettings.PRISM_SEARCH_DOMAIN}", e)
        }
    }

    suspend fun serve(context: Context, socket: Socket, preReadHeader: String? = null) = withContext(Dispatchers.IO) {
        try {
            PrismSearchServer.serveHttp(socket, preReadHeader)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Serving error", e)
            runCatching { socket.close() }
        }
    }
}
