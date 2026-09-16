package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.GgufInferenceService
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Running one model across several phones.
 *
 * ## What is actually distributed
 *
 * The model's **layers**. ggml's RPC backend makes a peer into a device that tensors can be
 * allocated on and graph nodes dispatched to; llama.cpp already spreads a model across however
 * many devices it is handed. So a model whose weights do not fit in this phone's RAM loads with
 * its later layers living in another phone's RAM, and a token passes through both on its way out.
 *
 * The peers never see the model file. Weights are uploaded to them at load time and the tokenizer,
 * the sampler and the conversation stay here -- a contributing peer lends memory and arithmetic,
 * not comprehension. That also means a peer can help with a model it has no licence to and no copy
 * of, which is the entire point when the reason for splitting is that one device is short of RAM.
 *
 * ## What it costs
 *
 * Every device boundary a layer crosses is a round trip carrying the hidden state. On a local
 * network that is small per token, but latency adds up and the slowest peer sets the pace. One fast
 * peer usually beats two slow ones, which is why the market sorts by capability and why automatic
 * selection picks a single best peer rather than everything available.
 *
 * ## Two different remote paths, and why
 *
 * ONE peer selected: the prompt is sent to that peer and it runs the whole model itself, through
 * the [com.prism.launcher.vpn.PrismAiHost] path that already exists. No weights move, nothing is
 * split, and it works with whatever model that peer has loaded. This is the cheap case.
 *
 * SEVERAL peers selected: this device holds the model file and splits it across them over RPC. The
 * weights move once per load, which is slow for a large model and pointless for a small one -- so
 * this only happens when the user explicitly picks more than one peer.
 */
object MeshInference {

    private const val TAG = "MeshCompute"

    /** The port this device serves compute on. Fixed, because peers have to be told it anyway. */
    const val RPC_PORT = 47811

    /** Rough token count for billing. Tokens are ~4 characters of English on average. */
    private const val CHARS_PER_TOKEN = 4

    @Volatile private var hosting = false
    private val tokensServed = AtomicLong(0)

    // ── Choosing peers ─────────────────────────────────────────────────────

    fun sortKey(): MeshComputeRegistry.SortKey =
        runCatching { MeshComputeRegistry.SortKey.valueOf(PrismSettings.getComputeSortKey()) }
            .getOrDefault(MeshComputeRegistry.SortKey.OVERALL)

    /**
     * The peers a generation should use, or empty to stay local.
     *
     * Automatic mode returns exactly one -- the best by the chosen key. It does not return every
     * available peer, because distributing across peers that are not needed makes generation slower,
     * not faster: see the class comment.
     */
    fun selectedPeers(): List<MeshComputeRegistry.Peer> {
        if (PrismSettings.getComputeAutoSelect()) {
            return listOfNotNull(MeshComputeRegistry.best(sortKey()))
        }
        val chosen = PrismSettings.getComputePeers().toSet()
        if (chosen.isEmpty()) return emptyList()
        // Ordered by the market's own sort so the first peer -- which llama.cpp fills first -- is
        // the strongest one the user picked.
        return MeshComputeRegistry.sorted(sortKey()).filter { it.peerIp in chosen }
    }

    /**
     * Whether a generation should leave this device at all.
     *
     * Requires a local model to be active, because that is the user's stated intent: the market
     * offers someone else's hardware for the model you chose, not someone else's choice of model.
     * With no active model there is nothing to distribute and nothing to ask a peer to run.
     */
    fun shouldOffload(): Boolean =
        selectedPeers().isNotEmpty() && PrismSettings.getLocalAiModelPath().isNotBlank()

    // ── Selling this device's compute ──────────────────────────────────────

    /**
     * Starts serving compute, if the user has opted in.
     *
     * The server runs for the rest of the process's life -- ggml has no shutdown -- so this is
     * deliberately not called on a whim: only when a peer asks for a device, and only when hosting
     * is switched on. See GgufInferenceService.startRpcServer.
     */
    fun startHosting(context: Context) {
        if (hosting || !PrismSettings.getComputeHostEnabled()) return
        hosting = true

        Thread({
            val cacheDir = File(context.cacheDir, "ggml-rpc").apply { mkdirs() }

            // BOUND TO THE MESH INTERFACE, NOT 0.0.0.0.
            //
            // ggml's RPC server has no authentication and is documented upstream as unsafe to
            // expose: a client can allocate buffers and read them back, which on an open port is a
            // memory-disclosure primitive for anyone on the same Wi-Fi. Binding to the mesh address
            // means only devices inside the Prism tunnel can reach it -- peers that already hold
            // the tunnel's credentials. It is not a substitute for authentication in the protocol,
            // and it is the strongest thing available without forking ggml.
            val bind = MeshUtils.getLocalMeshIp().ifBlank { "127.0.0.1" }
            PrismLogger.logInfo(TAG, "Serving compute on $bind:$RPC_PORT")
            val ok = GgufInferenceService.startRpcServer(
                endpoint = "$bind:$RPC_PORT",
                cacheDir = cacheDir.absolutePath,
                nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
            )
            if (!ok) {
                hosting = false
                PrismLogger.logWarning(TAG, "This build has no RPC backend; compute cannot be sold")
            }
        }, "mesh-compute-host").apply { isDaemon = true; start() }
    }

    val isHosting: Boolean get() = hosting

    /** A peer asking this device to stand up a server for it. */
    fun onRpcRequest(context: Context, peerIp: String) {
        if (!PrismSettings.getComputeHostEnabled()) {
            PrismLogger.logInfo(TAG, "Declined a compute request from $peerIp: hosting is off")
            return
        }
        startHosting(context)
        PrismMeshService.sendToPeer(
            peerIp,
            MeshComputeRegistry.OPCODE_RPC_READY,
            JSONObject().apply { put("port", RPC_PORT) }.toString(),
        )
    }

    // ── Buying someone else's ──────────────────────────────────────────────

    /**
     * Asks the chosen peers to bring their servers up, and waits for them to say they have.
     *
     * Returns the endpoints that answered in time. A peer that does not answer is left out rather
     * than waited on: the remaining peers plus local memory may still be enough, and one asleep
     * phone should not stall a generation indefinitely.
     */
    private fun gatherEndpoints(peers: List<MeshComputeRegistry.Peer>, timeoutMs: Long = 8_000): List<String> {
        peers.forEach { peer ->
            PrismMeshService.sendToPeer(peer.peerIp, MeshComputeRegistry.OPCODE_RPC_REQUEST, "{}")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = peers.mapNotNull { peer ->
                MeshComputeRegistry.rpcPortOf(peer.peerIp)?.let { "${peer.peerIp}:$it" }
            }
            if (ready.size == peers.size) return ready
            try { Thread.sleep(200) } catch (e: InterruptedException) { break }
        }
        return peers.mapNotNull { peer ->
            MeshComputeRegistry.rpcPortOf(peer.peerIp)?.let { "${peer.peerIp}:$it" }
        }
    }

    /**
     * Loads the active model with its layers spread across the selected peers.
     *
     * Returns a handle for the same generate calls a local load produces, or 0 when it could not be
     * done -- an unreachable peer, no RPC backend, a model that failed to load. Callers fall back to
     * running locally on 0 rather than reporting a failure to the user: a slower answer is better
     * than none.
     */
    fun loadDistributed(context: Context, modelPath: String, nCtx: Int): Long {
        val peers = selectedPeers()
        if (peers.size < 2) return 0L

        val endpoints = gatherEndpoints(peers)
        if (endpoints.isEmpty()) {
            PrismLogger.logWarning(TAG, "No peer brought up a compute server; staying local")
            return 0L
        }

        PrismLogger.logInfo(TAG, "Splitting $modelPath across ${endpoints.size} peer(s): $endpoints")
        return GgufInferenceService.loadDistributed(
            modelPath = modelPath,
            endpoints = endpoints,
            nCtx = nCtx,
            nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
            kvCacheMode = 0,
        )
    }

    /**
     * Runs a prompt entirely on one peer, over the mesh tunnel.
     *
     * Speaks the same minimal protocol [com.prism.launcher.vpn.PrismAiHost] already serves: POST a
     * prompt, read the answer until the connection closes. Nothing new on the wire, because the
     * single-peer case is exactly what that host was built for.
     */
    fun runOnPeer(
        context: Context,
        peer: MeshComputeRegistry.Peer,
        prompt: String,
        onToken: ((String) -> Unit)? = null,
    ): String? {
        val answer = com.prism.launcher.messaging.AiManager.generateOnPeer(peer.peerIp, prompt, onToken)
        if (answer.isNullOrBlank()) return null
        // Billed on what came back, because that is the part the peer actually spent itself
        // producing -- and it is the only figure both sides can agree on without a protocol for
        // reporting work done.
        ComputeDebtLedger.charge(context, peer, answer.length / CHARS_PER_TOKEN)
        return answer
    }

    /** Charges every peer that took part in a distributed generation. */
    fun billDistributed(context: Context, peers: List<MeshComputeRegistry.Peer>, characters: Int) {
        if (peers.isEmpty()) return
        // Split evenly rather than by layer count: llama.cpp decides the split by memory at load
        // time and does not report it, so any weighting here would be invented.
        val each = (characters / CHARS_PER_TOKEN) / peers.size
        peers.forEach { ComputeDebtLedger.charge(context, it, each) }
    }

    /** This device's own address, for the market UI to mark its own row. */
    fun localIp(): String = MeshUtils.getLocalMeshIp()

    fun tokensServedSoFar(): Long = tokensServed.get()
}
