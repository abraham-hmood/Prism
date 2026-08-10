package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.prism.core.json.JSONObject

/**
 * Gossips which peers on the Prism mesh are hosting a local AI model, mirroring
 * [com.prism.launcher.browser.P2pDnsManager]'s shape (local map, gossiped mesh-wide via
 * [PrismMeshService]) but carrying model metadata (a name) instead of just an IP — DNS records
 * have no room for that, so this is its own small registry rather than piggybacking on DNS.
 *
 * Not persisted across restarts — each entry is re-announced by its hosting peer and expires if
 * not refreshed (see [getAll]), the same reasoning [com.prism.launcher.messaging.OllamaDiscoveryService]
 * uses for not persisting scan results: a stale "peer X is hosting model Y" claim surviving that
 * peer going offline is worse than just re-discovering it live.
 */
object P2pModelRegistry {

    /** Reserved PRISM_CONNECT domain marker for AI-model requests — never resolved via DNS,
     *  the client connects directly to a peer IP taken from [getAll] and sends this as the
     *  handshake's domain so [PrismProxyServer] knows to dispatch to PrismAiHost, not PrismWebHost. */
    const val MODEL_HOST_DOMAIN = "ai-model.prism.p2p"

    const val OPCODE_MODEL_ANNOUNCE: Byte = 0x0C

    private const val STALE_MS = 10 * 60 * 1000L // 10 minutes — re-announced well before this on any hosting-state check

    data class HostedModelInfo(val peerIp: String, val modelName: String, val timestamp: Long)

    private val hostedModels = mutableMapOf<String, HostedModelInfo>()

    private val _models = MutableStateFlow<Map<String, HostedModelInfo>>(emptyMap())
    val models: StateFlow<Map<String, HostedModelInfo>> = _models

    /** Called when this device enables/updates AI model hosting — gossips it mesh-wide. */
    fun announce(context: Context, modelName: String) {
        val myIp = MeshUtils.getLocalMeshIp()
        hostedModels[myIp] = HostedModelInfo(myIp, modelName, System.currentTimeMillis())
        _models.value = hostedModels.toMap()
        broadcast(modelName)
    }

    /** Called when this device disables AI model hosting. */
    fun revoke(context: Context) {
        val myIp = MeshUtils.getLocalMeshIp()
        hostedModels.remove(myIp)
        _models.value = hostedModels.toMap()
        broadcast("")
    }

    private fun broadcast(modelName: String) {
        val payload = JSONObject().apply { put("model", modelName) }.toString()
        PrismMeshService.broadcastToOthers(OPCODE_MODEL_ANNOUNCE, payload)
    }

    /** Called by [PrismMeshService] when a peer's model-announce packet arrives. */
    fun ingestFromPeer(peerIp: String, modelName: String) {
        if (modelName.isBlank()) {
            hostedModels.remove(peerIp)
        } else {
            hostedModels[peerIp] = HostedModelInfo(peerIp, modelName, System.currentTimeMillis())
        }
        _models.value = hostedModels.toMap()
    }

    /** All currently-known hosted models, dropping any peer we haven't heard from recently. */
    fun getAll(): List<HostedModelInfo> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val fresh = hostedModels.filterValues { it.timestamp >= cutoff }
        if (fresh.size != hostedModels.size) {
            hostedModels.keys.retainAll(fresh.keys)
            _models.value = hostedModels.toMap()
        }
        return fresh.values.toList()
    }
}
