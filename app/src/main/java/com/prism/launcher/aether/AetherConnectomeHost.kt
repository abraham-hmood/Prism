package com.prism.launcher.aether

import android.content.Context
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * Serves Aether connectome requests arriving over the Prism mesh tunnel -- mirrors
 * [com.prism.launcher.vpn.PrismAiHost]'s role (dispatched from `PrismProxyServer` when a
 * PRISM_CONNECT domain is a reserved marker, here [AetherMeshSync.AETHER_HOST_DOMAIN] instead of
 * [com.prism.launcher.mesh.P2pModelRegistry.MODEL_HOST_DOMAIN]) but delegates the actual
 * request-serving to [AetherKnowledgeSync.serveHttp] rather than reimplementing it -- the LAN
 * accept loop and this mesh dispatch path both end up handing a connected [Socket] to the exact
 * same `GET /akef/manifest` / `GET /akef/connectome` handler, so there is one implementation of
 * "how a request for this device's connectome gets answered," not two.
 */
object AetherConnectomeHost {

    private const val TAG = "AetherConnectomeHost"

    suspend fun serve(context: Context, socket: Socket, preReadHeader: String? = null) = withContext(Dispatchers.IO) {
        try {
            AetherKnowledgeSync.serveHttp(socket, preReadHeader)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Serving error", e)
            runCatching { socket.close() }
        }
    }
}
