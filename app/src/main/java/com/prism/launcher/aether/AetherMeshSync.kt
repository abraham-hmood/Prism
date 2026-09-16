package com.prism.launcher.aether

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.vpn.PrismSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Gossips which Prism mesh peers are running Aether with a trained connectome available -- the
 * mesh-side counterpart to [AetherKnowledgeSync]'s LAN discovery, mirroring
 * [com.prism.launcher.mesh.P2pModelRegistry]'s exact shape (local map, gossiped mesh-wide via
 * [PrismMeshService], `StateFlow` for the settings UI, stale eviction on read).
 *
 * WHY A SEPARATE REGISTRY FROM [AetherKnowledgeSync]'s LAN PEER MAP, NOT A SHARED ONE. The two
 * key spaces are genuinely different (a mesh IP behind the tunnel vs. a raw LAN address+port) and
 * so is the transport for actually fetching a connectome: LAN peers are fetched via a direct HTTP
 * GET (see [AetherKnowledgeSync.fetchAndStage]), mesh peers via the mesh's own TCP tunnel and a
 * reserved [AETHER_HOST_DOMAIN] dispatched to `AetherConnectomeHost` -- a raw HTTP GET to a mesh
 * peer's IP would try to leave the tunnel entirely and likely fail (NAT, no route).
 * `AetherSettingsActivity`'s device list merges both maps for display, tagging each entry with
 * which transport it came from.
 *
 * LIVES IN THE APP MODULE, NOT CORE, UNLIKE MOST OF AETHER'S KOTLIN PORT. `PrismMeshService` is
 * an app-module class (core cannot depend on app), so anything that gossips through it -- this
 * object, same as `P2pModelRegistry` -- has to live here too.
 */
object AetherMeshSync {

    /** Reserved PRISM_CONNECT domain for Aether connectome requests over the mesh tunnel -- never
     *  resolved via DNS; `PrismProxyServer` dispatches a connection whose handshake names this
     *  domain to `AetherConnectomeHost` instead of PrismWebHost/PrismAiHost. */
    const val AETHER_HOST_DOMAIN = "aether-knowledge.prism.p2p"

    const val OPCODE_AETHER_ANNOUNCE: Byte = 0x0D

    private const val STALE_MS = 10 * 60 * 1000L

    data class AetherPeerInfo(
        val peerIp: String, val hasModel: Boolean, val geometrySignature: String, val timestamp: Long,
        val score: Int = AetherKnowledgeSync.DEFAULT_SCORE
    )

    private val peers = mutableMapOf<String, AetherPeerInfo>()

    private val _peers = MutableStateFlow<Map<String, AetherPeerInfo>>(emptyMap())
    val peersFlow: StateFlow<Map<String, AetherPeerInfo>> = _peers

    /**
     * Called when Aether's mesh-sharing state changes (toggle, geometry resize, first checkpoint
     * saved) -- gossips it mesh-wide. Called from both the main process (toggling the setting)
     * and `:aether` (after training) -- uses [AetherStudio.hasTrainedWeights], not [AetherStudio.isTrained],
     * so the main-process call site never builds a connectome there.
     */
    fun announce(context: Context) {
        val myIp = MeshUtils.getLocalMeshIp()
        val hasModel = AetherStudio.hasTrainedWeights()
        val geometry = AetherConfig.geometry.signature()
        val score = AetherKnowledgeSync.readLocalScore()
        peers[myIp] = AetherPeerInfo(myIp, hasModel, geometry, System.currentTimeMillis(), score)
        _peers.value = peers.toMap()
        broadcast(hasModel, geometry, score)
    }

    fun revoke() {
        val myIp = MeshUtils.getLocalMeshIp()
        peers.remove(myIp)
        _peers.value = peers.toMap()
        broadcast(hasModel = false, geometrySignature = "", score = AetherKnowledgeSync.DEFAULT_SCORE)
    }

    private fun broadcast(hasModel: Boolean, geometrySignature: String, score: Int) {
        val payload = JSONObject().apply {
            put("has_model", hasModel)
            put("geometry_signature", geometrySignature)
            put("score", score)
        }.toString()
        PrismMeshService.broadcastToOthers(OPCODE_AETHER_ANNOUNCE, payload)
    }

    /** Called by [PrismMeshService] when a peer's Aether-announce packet arrives. */
    fun ingestFromPeer(peerIp: String, hasModel: Boolean, geometrySignature: String, score: Int = AetherKnowledgeSync.DEFAULT_SCORE) {
        if (!hasModel && geometrySignature.isEmpty()) {
            peers.remove(peerIp)
        } else {
            peers[peerIp] = AetherPeerInfo(peerIp, hasModel, geometrySignature, System.currentTimeMillis(), score)
        }
        _peers.value = peers.toMap()
    }

    /** All currently-known Aether mesh peers, dropping any we haven't heard from recently. */
    fun getAll(): List<AetherPeerInfo> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val fresh = peers.filterValues { it.timestamp >= cutoff }
        if (fresh.size != peers.size) {
            peers.keys.retainAll(fresh.keys)
            _peers.value = peers.toMap()
        }
        return fresh.values.toList()
    }

    // ── Fetching a peer's connectome over the mesh tunnel ──────────────────

    /**
     * Downloads and stages a merge from a mesh peer, via [PrismSocket]'s PRISM_CONNECT handshake
     * into [AETHER_HOST_DOMAIN] rather than a direct HTTP connection to [peer]'s IP -- a mesh
     * peer's real address is inside the tunnel and typically isn't reachable any other way (NAT,
     * no route). Delegates the actual staging to [AetherKnowledgeSync.stageBlob] so mesh-sourced
     * and LAN-sourced merges share one apply path.
     */
    fun fetchAndStage(peer: AetherPeerInfo): Boolean {
        val label = "mesh:${peer.peerIp} (${peer.geometrySignature})"
        return try {
            val manifestBody = requestOverMesh(peer.peerIp, "/akef/manifest")
                ?: return false.also { AetherKnowledgeSync.setLastStatus("$label: manifest request failed") }
            val manifestDigest = JSONObject(String(manifestBody, Charsets.UTF_8)).optString("sha256")
            if (AetherKnowledgeSync.isAlreadyMerged(manifestDigest)) {
                AetherKnowledgeSync.setLastStatus("$label unchanged since last merge")
                return false
            }
            val blob = requestOverMesh(peer.peerIp, "/akef/connectome")
                ?: return false.also { AetherKnowledgeSync.setLastStatus("$label: connectome request failed") }
            AetherKnowledgeSync.stageBlob(blob, manifestDigest, label)
        } catch (e: Exception) {
            PrismLogger.logError("AetherMeshSync", "fetchAndStage($label) failed", e)
            false
        }
    }

    private fun requestOverMesh(peerIp: String, path: String): ByteArray? {
        val socket = PrismSocket()
        socket.setHostHint(AETHER_HOST_DOMAIN)
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(peerIp), 8080), 8000)
            socket.soTimeout = 15000
            socket.getOutputStream().apply {
                write("GET $path\r\n\r\n".toByteArray())
                flush()
            }
            val (status, body) = readHttpResponse(socket.getInputStream())
            if (status != 200) null else body
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHttpResponse(input: InputStream): Pair<Int, ByteArray> {
        fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val statusLine = readLine()
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        var contentLength = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val h = line.split(":", limit = 2)
            if (h.size == 2 && h[0].trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = h[1].trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return status to body
    }
}
