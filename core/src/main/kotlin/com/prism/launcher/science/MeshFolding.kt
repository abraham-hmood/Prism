package com.prism.launcher.science

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.mesh.MeshBridges
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.MeshInference
import com.prism.launcher.mesh.P2pModelRegistry
import com.prism.launcher.mesh.PayLaterPolicy
import com.prism.launcher.protein.FoldingModel
import com.prism.launcher.protein.FoldingPrediction
import com.prism.launcher.protein.FoldingWire
import com.prism.launcher.protein.ProteinChain
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Folding and training on other people's phones.
 *
 * ## What is distributed, and what is not
 *
 * **Folding many sequences is distributed by sequence.** Each peer gets whole sequences to fold.
 * That is the one split with no communication inside it, so it scales with however many peers turn
 * up and a peer that vanishes costs only its own share.
 *
 * **Folding ONE sequence is not split.** It could be, in principle: the Evoformer's pair stack is
 * an L×L tensor and could be tiled across devices. It is not, because the triangle updates make
 * every tile depend on every other tile in the same row and column, so tiling one 300-residue chain
 * across three phones means shipping megabytes of pair representation between them per block, per
 * iteration, over Wi-Fi. That is slower than folding it on the slowest single device. So one
 * sequence goes whole to the strongest peer, and only when that peer actually outscores this device
 * — offloading a fold to a weaker phone to be able to say it was distributed would just be slower.
 *
 * **Training is distributed by local SGD**, with each peer running a run of steps on its own shard
 * and returning how far its weights moved. The reasoning for that over gradient-per-step is in
 * [FoldingWire].
 *
 * ## Why there is no attempt to verify what comes back
 *
 * A peer could return plausible-looking coordinates it did not compute. There is no cheap check for
 * that — verifying a fold means folding it, and then the peer was pointless. What there is instead is
 * that the returned structure is scored by the same local metrics as any other prediction, and a peer
 * whose work is nonsense shows up as a pLDDT that does not match its geometry. This is stated rather
 * than hidden because "distributed" here means "trusted peers you chose", and that is a real limit.
 */
object MeshFolding {

    private const val TAG = "PrismFolding"

    /** The mesh HTTP port every peer service already listens on. */
    private const val PEER_PORT = 8080

    /** Enough for a peer to load weights and fold a few hundred residues on a phone. */
    private const val FOLD_TIMEOUT_MS = 180_000

    /** A training round is a run of steps, not one, so it gets longer. */
    private const val TRAIN_TIMEOUT_MS = 300_000

    private const val WEIGHTS_TIMEOUT_MS = 120_000

    /**
     * Which peer is believed to hold which weights.
     *
     * A cache of a remote fact, so it is allowed to be wrong in one direction only: if it says a peer
     * has the weights and the peer does not, the peer says so and the weights are sent. If it says a
     * peer does not have them, they are sent again needlessly. Both are recoverable; neither
     * corrupts anything, which is why a plain map with no invalidation is enough.
     */
    private val weightsOnPeer = HashMap<String, String>()

    // ── Availability ───────────────────────────────────────────────────────

    fun peers(): List<MeshComputeRegistry.Peer> = MeshInference.selectedPeers()

    /** What the panel shows about where the work will run. */
    // THE Context PARAMETERS WERE ALREADY DEAD. Every one of them -- describe, allowed,
    // betterPeerFor, the two distribute entry points and bill -- took a Context and never touched
    // it: the policy check, the peer registry and the debt ledger had all been made portable
    // earlier and nobody removed the argument. So this file needed almost no seam at all, only
    // the parameters deleting and the socket going through MeshBridges. PHASE 97.
    fun describe(training: Boolean): String {
        val work = if (training) {
            PayLaterPolicy.Work.PROTEIN_TRAINING
        } else {
            PayLaterPolicy.Work.PROTEIN_FOLDING
        }
        return when (val verdict = PayLaterPolicy.check(work)) {
            is PayLaterPolicy.Verdict.Allowed -> "Across the meshnet — ${verdict.note}"
            is PayLaterPolicy.Verdict.Blocked -> verdict.reason
        }
    }

    fun allowed(training: Boolean): Boolean {
        val work = if (training) {
            PayLaterPolicy.Work.PROTEIN_TRAINING
        } else {
            PayLaterPolicy.Work.PROTEIN_FOLDING
        }
        return PayLaterPolicy.check(work) is PayLaterPolicy.Verdict.Allowed
    }

    // ── Folding ────────────────────────────────────────────────────────────

    data class FoldOutcome(
        val predictions: List<FoldingPrediction>,
        /** Sequences no peer managed, left for the caller to fold locally. */
        val unfinished: List<String>,
        val peersUsed: Int,
    )

    /**
     * Folds [sequences] across the peers, in parallel, one request per peer at a time.
     *
     * Blocking; callers are already off the main thread because folding is. Peers are worked in
     * parallel because they are independent devices — doing them in sequence would make three peers
     * exactly as slow as one, which is the whole point of having three.
     *
     * Anything unclaimed comes back in [FoldOutcome.unfinished] rather than being silently dropped
     * or silently folded here. The caller decides whether to fold it locally, and the panel says
     * which structures came from where, because a user who chose to distribute work should be able
     * to see that it happened.
     */
    fun foldDistributed(
        model: FoldingModel,
        sequences: List<String>,
        step: Int = 0,
        onProgress: (done: Int, total: Int, where: String) -> Unit = { _, _, _ -> },
    ): FoldOutcome {
        val chosen = peers()
        if (chosen.isEmpty() || sequences.isEmpty()) return FoldOutcome(emptyList(), sequences, 0)

        val bytes = FoldingWire.serialize(model, step)
        val hash = FoldingWire.hashOf(bytes)

        // Round-robin rather than by score. Weighting the shares by peer score sounds better and is
        // worse: folding time grows with L², so a "fair" split by device speed would still have the
        // fast peer sitting idle whenever it happened to draw the short sequences.
        val shards = HashMap<String, MutableList<String>>()
        sequences.forEachIndexed { index, sequence ->
            val peer = chosen[index % chosen.size]
            shards.getOrPut(peer.peerIp) { mutableListOf() }.add(sequence)
        }

        val pool = Executors.newFixedThreadPool(chosen.size.coerceAtMost(8))
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = java.util.Collections.synchronizedList(ArrayList<FoldingPrediction>())
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())

        try {
            val tasks = chosen.mapNotNull { peer ->
                val shard = shards[peer.peerIp] ?: return@mapNotNull null
                Callable {
                    val folded = foldOnPeer(peer, shard, bytes, hash)
                    if (folded.isEmpty()) {
                        failed.addAll(shard)
                    } else {
                        results.addAll(folded)
                        // Charge for what came back, not for what was asked: a peer that returned
                        // three of five structures did three structures of work.
                        bill(peer, folded.sumOf { it.length })
                        val stillMissing = shard.filter { asked ->
                            folded.none { it.sequence == asked }
                        }
                        failed.addAll(stillMissing)
                    }
                    onProgress(done.addAndGet(shard.size), sequences.size, peer.deviceName)
                }
            }
            pool.invokeAll(tasks, FOLD_TIMEOUT_MS.toLong() + 30_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            PrismPlatform.log.error(TAG, "Distributed fold failed", e)
        } finally {
            pool.shutdownNow()
        }

        PayLaterPolicy.afterWork()
        return FoldOutcome(results.toList(), failed.toList(), chosen.size)
    }

    /**
     * Whether one sequence is worth sending away at all.
     *
     * Compares the best peer's capability score against this device's own. A fold is a single
     * indivisible job, so sending it to a weaker phone makes it slower and costs money for the
     * privilege — the only reason to offload one sequence is that the other device is genuinely
     * better at it.
     */
    fun betterPeerFor(): MeshComputeRegistry.Peer? {
        val best = peers().maxByOrNull { it.score } ?: return null
        val local = runCatching {
            val capacity = MeshComputeRegistry.localCapacity()
            com.prism.launcher.mesh.ComputePricing.score(
                ramTotalBytes = capacity.ramTotalBytes,
                vramBytes = capacity.vramBytes,
                swapRamBytes = capacity.swapRamBytes,
                swapVramBytes = capacity.swapVramBytes,
                hasNpu = capacity.hasNpu,
                npuIndex = capacity.npuIndex,
                cpuIndex = capacity.cpuIndex,
            )
        }.getOrDefault(0.0)
        return if (best.score > local * 1.15) best else null
    }

    private fun foldOnPeer(
        peer: MeshComputeRegistry.Peer,
        sequences: List<String>,
        weights: ByteArray,
        hash: String,
    ): List<FoldingPrediction> {
        if (!ensureWeights(peer, weights, hash)) return emptyList()

        val body = FoldingWire.foldRequest(sequences, hash)
        var response = post(peer.peerIp, FoldingWire.PATH_CONTROL, body.toByteArray(), FOLD_TIMEOUT_MS)
            ?: return emptyList()

        // One retry, for the case where the peer dropped the weights between the probe and the job —
        // it restarted, or its cache was trimmed. A second failure is a real failure.
        if (needsWeights(response)) {
            weightsOnPeer.remove(peer.peerIp)
            if (!ensureWeights(peer, weights, hash)) return emptyList()
            response = post(peer.peerIp, FoldingWire.PATH_CONTROL, body.toByteArray(), FOLD_TIMEOUT_MS)
                ?: return emptyList()
        }

        return runCatching {
            val json = JSONObject(String(response))
            val array = json.optJSONArray("results") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let {
                    FoldingWire.decodePrediction(it, peer.deviceName)
                }
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not read ${peer.deviceName}'s fold reply", it)
            emptyList()
        }
    }

    // ── Training ───────────────────────────────────────────────────────────

    data class RoundOutcome(
        /** The averaged movement, already applied to the model. Null when nothing came back. */
        val applied: Boolean,
        val peersAnswered: Int,
        val peersAsked: Int,
        val stepsRun: Int,
        val meanLoss: Float,
        val elapsedMillis: Long,
    ) {
        fun describe(): String = when {
            !applied -> "No peer completed the round; nothing was applied"
            else -> "$peersAnswered/$peersAsked peer(s) · $stepsRun steps · loss %.4f · %.1fs"
                .format(meanLoss, elapsedMillis / 1000f)
        }
    }

    /**
     * One federated round: hand every peer a shard and average what comes back.
     *
     * The model is mutated in place on success, so a caller loops this and checkpoints between
     * rounds. Blocking, and long — a round is deliberately many steps of work per request.
     *
     * A peer that fails contributes nothing and is not waited for beyond the timeout. Note what that
     * means for reproducibility: which peers answered changes the averaged update, so a mesh training
     * run is not bit-reproducible from its seed the way a local one is. That is inherent to training
     * on machines that come and go, and pretending otherwise by dropping rounds with absentees would
     * throw away most of the work.
     */
    fun trainRound(
        model: FoldingModel,
        examples: List<ProteinChain>,
        stepsPerPeer: Int,
        learningRate: Float,
        seed: Long,
        step: Int = 0,
    ): RoundOutcome {
        val startedAt = System.currentTimeMillis()
        val chosen = peers()
        if (chosen.isEmpty() || examples.isEmpty()) {
            return RoundOutcome(false, 0, chosen.size, 0, 0f, 0)
        }

        val before = FoldingWire.flatten(model)
        val bytes = FoldingWire.serialize(model, step)
        val hash = FoldingWire.hashOf(bytes)

        // Disjoint shards. Two peers training the same examples and having their movements averaged
        // is two peers doing one peer's worth of work.
        val shards = HashMap<String, MutableList<ProteinChain>>()
        examples.forEachIndexed { index, chain ->
            shards.getOrPut(chosen[index % chosen.size].peerIp) { mutableListOf() }.add(chain)
        }

        val deltas = java.util.Collections.synchronizedList(ArrayList<Pair<FloatArray, Int>>())
        val losses = java.util.Collections.synchronizedList(ArrayList<Float>())

        val pool = Executors.newFixedThreadPool(chosen.size.coerceAtMost(8))
        try {
            val tasks = chosen.mapNotNull { peer ->
                val shard = shards[peer.peerIp] ?: return@mapNotNull null
                Callable {
                    val result = trainOnPeer(peer, shard, bytes, hash, stepsPerPeer, learningRate, seed)
                    if (result != null) {
                        deltas.add(result.first to result.second)
                        losses.add(result.third)
                        // Residues, not tokens, but the ledger's unit is "work" and a residue is the
                        // unit of work in a fold. Being explicit about that here rather than
                        // inventing a conversion.
                        bill(peer, shard.sumOf { it.length } * stepsPerPeer)
                    }
                }
            }
            pool.invokeAll(tasks, TRAIN_TIMEOUT_MS.toLong() + 30_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            PrismPlatform.log.error(TAG, "Distributed training round failed", e)
        } finally {
            pool.shutdownNow()
        }

        PayLaterPolicy.afterWork()

        val averaged = FoldingWire.averageDeltas(deltas.toList(), before.size)
        if (averaged == null) {
            return RoundOutcome(false, 0, chosen.size, 0, 0f, System.currentTimeMillis() - startedAt)
        }

        val after = FloatArray(before.size) { before[it] + averaged[it] }
        FoldingWire.unflatten(model, after)

        return RoundOutcome(
            applied = true,
            peersAnswered = deltas.size,
            peersAsked = chosen.size,
            stepsRun = deltas.sumOf { it.second },
            meanLoss = if (losses.isEmpty()) 0f else losses.average().toFloat(),
            elapsedMillis = System.currentTimeMillis() - startedAt,
        )
    }

    /** Returns the peer's weight movement, the steps it ran, and its mean loss. */
    private fun trainOnPeer(
        peer: MeshComputeRegistry.Peer,
        shard: List<ProteinChain>,
        weights: ByteArray,
        hash: String,
        steps: Int,
        learningRate: Float,
        seed: Long,
    ): Triple<FloatArray, Int, Float>? {
        if (!ensureWeights(peer, weights, hash)) return null

        val body = FoldingWire.trainRequest(shard, hash, steps, learningRate, seed)
        var response = post(peer.peerIp, FoldingWire.PATH_CONTROL, body.toByteArray(), TRAIN_TIMEOUT_MS)
            ?: return null
        if (needsWeights(response)) {
            weightsOnPeer.remove(peer.peerIp)
            if (!ensureWeights(peer, weights, hash)) return null
            response = post(peer.peerIp, FoldingWire.PATH_CONTROL, body.toByteArray(), TRAIN_TIMEOUT_MS)
                ?: return null
        }

        // `ok <loss> <steps>\n` then raw float bytes. A text header over a binary body because the
        // body is a million floats and putting those in JSON would triple the transfer and round the
        // numbers on the way.
        val newline = response.indexOf('\n'.code.toByte())
        if (newline <= 0) return null
        val header = String(response, 0, newline).trim().split(' ')
        if (header.firstOrNull() != "ok") {
            PrismPlatform.log.warn(TAG, "${peer.deviceName} refused the round: ${header.joinToString(" ")}")
            return null
        }
        val loss = header.getOrNull(1)?.toFloatOrNull() ?: 0f
        val ranSteps = header.getOrNull(2)?.toIntOrNull() ?: steps
        val delta = FoldingWire.unpackFloats(response, newline + 1)
        return Triple(delta, ranSteps, loss)
    }

    // ── Weight transfer ────────────────────────────────────────────────────

    private fun needsWeights(response: ByteArray): Boolean {
        // Cheap prefix check before parsing: a successful training reply is megabytes of floats and
        // handing that to a JSON parser to discover it is not JSON is not free.
        if (response.size > 512) return false
        val text = String(response, 0, response.size.coerceAtMost(512))
        return text.contains("\"need\"")
    }

    private fun ensureWeights(peer: MeshComputeRegistry.Peer, weights: ByteArray, hash: String): Boolean {
        if (weightsOnPeer[peer.peerIp] == hash) return true

        // Ask before sending. The probe is 80 bytes and the weights are megabytes, so on a training
        // run where the model has not changed since the last round this saves the entire transfer.
        val probe = post(
            peer.peerIp,
            FoldingWire.PATH_CONTROL,
            FoldingWire.probeRequest(hash).toByteArray(),
            15_000,
        )
        if (probe != null && !needsWeights(probe)) {
            weightsOnPeer[peer.peerIp] = hash
            return true
        }

        val payload = ByteArrayOutputStream(weights.size + 96).apply {
            write(hash.toByteArray())
            write('\n'.code)
            write(weights)
        }.toByteArray()

        val reply = post(peer.peerIp, FoldingWire.PATH_WEIGHTS, payload, WEIGHTS_TIMEOUT_MS)
        if (reply == null) {
            PrismPlatform.log.warn(TAG, "Could not send weights to ${peer.deviceName}")
            return false
        }
        weightsOnPeer[peer.peerIp] = hash
        PrismPlatform.log.info(
            TAG,
            "Sent ${weights.size / 1024} KB of weights to ${peer.deviceName}",
        )
        return true
    }

    // ── Billing ────────────────────────────────────────────────────────────

    /**
     * Charges the peer's price, or records a debt when the wallet is empty.
     *
     * [ComputeDebtLedger.charge] already decides which. What matters here is that it is called at all:
     * a distributed fold that skipped billing would be using somebody's battery for free, which is
     * exactly the thing the compute market exists to stop.
     */
    private fun bill(peer: MeshComputeRegistry.Peer, units: Int) {
        if (units <= 0) return
        runCatching { ComputeDebtLedger.charge(peer, units) }
            .onFailure { PrismPlatform.log.error(TAG, "Could not bill ${peer.deviceName}", it) }
    }

    // ── Transport ──────────────────────────────────────────────────────────

    /**
     * One POST to a peer over the mesh tunnel, returning the whole body.
     *
     * Through [MeshBridges.connect] and the same reserved model-host domain as peer inference,
     * because it is the same tunnel to the same port on the same peer -- the mesh routing, the VPN
     * protection and the PRISM_CONNECT handshake all already work there, and a second transport
     * would be a second set of ways for this to fail on a device where the first one works.
     *
     * THE HOST HINT IS WHY THIS IS A HOOK rather than a plain socket: Android's PrismSocket tells
     * the VPN which mesh NAME the connection is for so the tunnel can route it, and a desktop has
     * no per-application tunnel and needs nothing of the kind.
     */
    private fun post(peerIp: String, path: String, body: ByteArray, timeoutMs: Int): ByteArray? {
        var socket: java.net.Socket? = null
        return try {
            socket = MeshBridges.connect(
                peerIp, PEER_PORT, 15_000, P2pModelRegistry.MODEL_HOST_DOMAIN,
            ) ?: return null
            // After connect, for the same reason peer inference does it after connect: the handshake
            // inside connect() uses its own much shorter timeout and would otherwise be left on the
            // socket, failing every job that takes longer than a handshake.
            socket.soTimeout = timeoutMs

            val output = socket.getOutputStream()
            output.write(
                ("POST $path HTTP/1.1\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            output.write(body)
            output.flush()

            val input = socket.getInputStream()
            skipHeaders(input)
            readAll(input)
        } catch (e: Exception) {
            PrismPlatform.log.error(TAG, "POST $path to $peerIp failed", e)
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun skipHeaders(input: InputStream) {
        var matched = 0
        while (matched < 4) {
            val c = input.read()
            if (c == -1) return
            matched = when {
                matched == 0 && c == '\r'.code -> 1
                matched == 1 && c == '\n'.code -> 2
                matched == 2 && c == '\r'.code -> 3
                matched == 3 && c == '\n'.code -> 4
                c == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            total += n
            // A bound, because the length is not known in advance (the peer streams until close) and
            // an unbounded read from the network into a phone's heap is an OOM waiting for a bad peer.
            if (total > MAX_RESPONSE_BYTES) break
        }
        return out.toByteArray()
    }

    /** Big enough for a training delta of a large model, small enough not to be the heap. */
    private const val MAX_RESPONSE_BYTES = 192L * 1024 * 1024
}
