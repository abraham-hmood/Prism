package com.prism.launcher.social

import android.content.Context
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * Serves the Nebula feed to connections arriving over the Prism mesh tunnel.
 *
 * Exactly the role [com.prism.launcher.aether.AetherConnectomeHost] plays for connectomes, and
 * for the same reason: `PrismProxyServer` recognises [NebulaMeshSync.NEBULA_HOST_DOMAIN] in a
 * PRISM_CONNECT handshake (or a `Host:` header) and hands the socket here instead of to
 * `PrismWebHost`. The actual request handling lives in [NebulaMeshSync.serveHttp] so that the
 * page, the JSON API and the federation logic are one implementation rather than two.
 */
object NebulaSocialHost {

    private const val TAG = "NebulaSocialHost"

    suspend fun serve(context: Context, socket: Socket, preReadHeader: String? = null) = withContext(Dispatchers.IO) {
        try {
            NebulaMeshSync.serveHttp(socket, preReadHeader)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Serving error", e)
            runCatching { socket.close() }
        }
    }
}
