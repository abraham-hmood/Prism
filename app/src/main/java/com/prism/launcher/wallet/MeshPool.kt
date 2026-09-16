package com.prism.launcher.wallet

import android.content.Context
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.mesh.PrismMeshService
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pooling the mesh's hash power behind one upstream connection.
 *
 * ## What this is, and what it is not
 *
 * It is a Stratum PROXY over the mesh, not a pool of its own. One device -- the coordinator -- holds
 * the connection to the real pool, slices the nonce space of each job, and hands the slices to mesh
 * peers. Peers hash their slice and send back any nonce that meets the share target; the
 * coordinator verifies it and submits it upstream under its own worker name.
 *
 * It is NOT an independent pool. Paying contributors directly would mean building blocks for those
 * chains, which needs a full node per chain, and the whole point of this is that the participants
 * are phones. See "Payout" below for what that costs and how it is handled honestly.
 *
 * ## Why it is worth having anyway
 *
 * Two reasons, neither of which is "more hashes".
 *
 * ISP CIRCUMVENTION. Only the coordinator needs to reach the pool. Everyone else reaches it through
 * the mesh tunnel, so a network that blocks mining pools stops one device instead of all of them.
 * The pool rotation in [MiningService] only helps when SOME pool is reachable; this helps when none
 * is.
 *
 * PAYOUT THRESHOLDS. This is the one that actually changes outcomes. A single phone mining XMR may
 * never accumulate a pool's minimum payout, so its work is not merely small -- it is unpayable.
 * Combined under one worker the contribution reaches the threshold and is actually paid.
 *
 * What it does not do is change the economics of SHA-256 coins. Ten phones is still
 * indistinguishable from zero against Bitcoin, and nothing here pretends otherwise.
 *
 * ## Shares are verified, never trusted
 *
 * A returned share is re-hashed by the coordinator before it goes upstream. This is one hash
 * against a peer's whole slice, so it costs nothing, and without it any peer on the mesh could
 * flood the coordinator with invalid nonces -- which does not just waste time, it gets the
 * coordinator's worker banned by the pool for a bad share rate. The peer supplies a nonce and
 * nothing else that is believed: the template, the target and the job are the coordinator's own.
 *
 * ## Payout, stated plainly
 *
 * The pool pays the coordinator's address. This records how many valid shares each peer
 * contributed, so the split is measurable, and shows it to both sides -- but Prism does not move
 * anyone's money for them. Describing this as "automatic profit sharing" would be a lie; it is an
 * accounting of who did what, and settlement is a decision between the people involved.
 */
object MeshPool {

    /** Coordinator to member: hash this template over this nonce range. */
    const val OPCODE_WORK: Byte = 0x11

    /** Member to coordinator: this nonce meets the target. */
    const val OPCODE_SHARE: Byte = 0x12

    /** How many nonces one peer is given at a time. */
    private const val SLICE = 40_000_000L

    private const val PREFS = "prism_mesh_pool"

    // ── Coordinator state ──────────────────────────────────────────────────

    /** The job a returned share must belong to, and everything needed to submit it. */
    private data class Outstanding(
        val coin: String,
        val algorithm: String,
        val jobId: String,
        val prefix: ByteArray,
        val extraNonce2: String,
        val nTime: String,
        val target: BigInteger,
    )

    private val outstanding = ConcurrentHashMap<String, Outstanding>()
    private val nextSlice = AtomicLong(0)

    /**
     * Called with a verified share. [MiningService] wires this to the live Stratum client.
     *
     * A callback rather than a direct call because this object has no business knowing which
     * client is connected, or whether one still is.
     */
    @Volatile
    var onVerifiedShare: ((jobId: String, extraNonce2: String, nTime: String, nonce: String) -> Unit)? = null

    // ── Member state ───────────────────────────────────────────────────────

    @Volatile
    private var memberThread: Thread? = null

    @Volatile
    private var memberEnabled = false

    /** Hashes this device has contributed to somebody else's pool. */
    val contributedHashes = AtomicLong(0)

    // ── Eligibility ────────────────────────────────────────────────────────

    /**
     * Whether mesh pooling can run at all right now.
     *
     * Requires the mesh to be enabled AND actually up -- either listening for peers or gossiping to
     * them, which is what "serving a mesh" and "connected to a mesh" respectively look like from
     * here. [PrismMeshService.isOnMesh] already draws exactly that line, deliberately, because the
     * local mesh IP falls back to the plain LAN address and so proves nothing on its own.
     *
     * A mode that could be selected while the mesh was down would start a miner that distributes
     * work to nobody and reports a hash rate of zero, which reads as a broken miner rather than as
     * a missing prerequisite.
     */
    fun isAvailable(): Boolean = PrismMeshService.isOnMesh()

    /** Why it is unavailable, for a UI that has to say something better than "no". */
    fun unavailableReason(): String? = when {
        !com.prism.launcher.PrismSettings.getMeshEnabled() ->
            "Prism Mesh is switched off. Mesh pooling needs it, and the mesh needs VPN tunnelling."
        !PrismMeshService.isOnMesh() ->
            "The mesh is enabled but not running yet. Join a mesh or start serving one, then " +
                "this becomes selectable."
        else -> null
    }

    /** Peers currently reachable to hand work to. */
    fun memberCount(): Int = PrismMeshService.activePeerIps().size

    // ── Coordinator ────────────────────────────────────────────────────────

    /**
     * Hands one slice of a job to every reachable peer.
     *
     * Each peer gets a DISTINCT range, drawn from a monotonic counter rather than from the peer's
     * position in the list. Peers come and go between jobs, so an index-based split would silently
     * hand two peers the same nonces the moment the list changed length -- they would burn the same
     * work twice and find the same share twice, and the pool would reject the duplicate.
     *
     * Fire and forget. A peer that is offline, uninterested, or not running this mode simply never
     * answers, and the coordinator's own workers are still covering the full range themselves.
     */
    fun distribute(
        coin: String,
        algorithm: String,
        jobId: String,
        prefix: ByteArray,
        extraNonce2: String,
        nTime: String,
        target: BigInteger,
    ) {
        val peers = PrismMeshService.activePeerIps()
        if (peers.isEmpty()) return

        outstanding[jobId] = Outstanding(
            coin, algorithm, jobId, prefix.copyOf(), extraNonce2, nTime, target
        )
        // Jobs arrive every few seconds; without this the map would grow for the lifetime of the
        // service. Keeping a handful covers shares that arrive just after a new job replaced theirs.
        if (outstanding.size > 8) {
            outstanding.keys.take(outstanding.size - 8).forEach { outstanding.remove(it) }
        }

        for (peer in peers) {
            val start = nextSlice.getAndAdd(SLICE) % 0xFFFFFFFFL
            val payload = JSONObject().apply {
                put("coin", coin)
                put("algo", algorithm)
                put("job", jobId)
                put("prefix", WalletCrypto.toHex(prefix))
                put("start", start)
                put("count", SLICE)
                put("target", target.toString(16))
            }.toString()
            runCatching { PrismMeshService.sendToPeer(peer, OPCODE_WORK, payload) }
        }
    }

    /**
     * A peer claims to have found a share. Verify it, then submit it.
     *
     * THE CLAIM IS RE-HASHED HERE. One hash against a slice of forty million is free, and skipping
     * it would let any device on the mesh feed the coordinator invalid nonces -- which costs more
     * than wasted work, because pools ban workers on a bad share rate. Everything except the nonce
     * comes from the coordinator's own record of the job, so a peer cannot substitute a different
     * template, target or job either.
     */
    fun onShare(context: Context, peerIp: String, payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val jobId = json.optString("job")
        val nonce = runCatching { json.optString("nonce").toLong(16) }.getOrNull() ?: return
        val job = outstanding[jobId] ?: return          // stale job; the share is worthless now

        val header = HeaderTemplate
            .of(job.prefix, ByteArray(0), nonceSize = 4, littleEndian = true)
            .snapshot(nonce)
        val hash = MiningAlgorithms.hash(job.algorithm, header)
        if (!ShareTarget.meetsTarget(hash, job.target)) {
            PrismLogger.logWarning(
                "MeshPool", "Rejected an invalid share from $peerIp for ${job.coin}; not submitted."
            )
            return
        }

        recordContribution(context, peerIp, job.coin)
        onVerifiedShare?.invoke(
            job.jobId, job.extraNonce2, job.nTime,
            WalletCrypto.toHex(
                byteArrayOf(
                    (nonce ushr 24).toByte(), (nonce ushr 16).toByte(),
                    (nonce ushr 8).toByte(), nonce.toByte()
                )
            )
        )
        PrismLogger.logSuccess("MeshPool", "Accepted a share from $peerIp for ${job.coin}")
    }

    fun clearJobs() {
        outstanding.clear()
    }

    // ── Member ─────────────────────────────────────────────────────────────

    /** Whether this device answers work requests from a coordinator. */
    fun setMemberEnabled(enabled: Boolean) {
        memberEnabled = enabled
        if (!enabled) stopMember()
    }

    fun stopMember() {
        memberThread?.interrupt()
        memberThread = null
    }

    /**
     * A coordinator has sent work. Hash the assigned slice and answer with anything that lands.
     *
     * ONE SLICE AT A TIME. A new work packet interrupts whatever this device was doing, because the
     * old slice almost always belongs to a job the coordinator has already replaced -- finishing it
     * would be hashing against a block that is gone.
     */
    fun onWork(peerIp: String, payload: String) {
        if (!memberEnabled || !isAvailable()) return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return

        val algorithm = json.optString("algo")
        val jobId = json.optString("job")
        val prefix = runCatching { WalletCrypto.fromHex(json.optString("prefix")) }.getOrNull()
            ?: return
        val start = json.optLong("start")
        val count = json.optLong("count")
        val target = runCatching { BigInteger(json.optString("target"), 16) }.getOrNull() ?: return
        if (count <= 0L) return

        stopMember()
        memberThread = Thread({
            val template = HeaderTemplate.of(prefix, ByteArray(0), 4, littleEndian = true)
            var nonce = start
            val end = start + count
            var hashed = 0L
            while (nonce < end && !Thread.currentThread().isInterrupted) {
                val hash = MiningAlgorithms.hash(algorithm, template.withNonce(nonce))
                hashed++
                if (ShareTarget.meetsTarget(hash, target)) {
                    runCatching {
                        PrismMeshService.sendToPeer(
                            peerIp, OPCODE_SHARE,
                            JSONObject().apply {
                                put("job", jobId)
                                put("nonce", java.lang.Long.toHexString(nonce))
                            }.toString()
                        )
                    }
                    // Keep going: the slice may hold more than one share, and the coordinator
                    // discards any that no longer match a live job.
                }
                nonce++
                if (hashed >= 512) {
                    contributedHashes.addAndGet(hashed)
                    hashed = 0
                }
            }
            contributedHashes.addAndGet(hashed)
        }, "mesh-pool-member").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    // ── Contribution accounting ────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun recordContribution(context: Context, peerIp: String, coin: String) {
        val key = "shares_${coin.uppercase()}_$peerIp"
        prefs(context).edit().putLong(key, prefs(context).getLong(key, 0L) + 1).apply()
    }

    /** Valid shares each peer has contributed for a coin, highest first. */
    fun contributions(context: Context, coin: String): List<Pair<String, Long>> {
        val marker = "shares_${coin.uppercase()}_"
        return prefs(context).all
            .filterKeys { it.startsWith(marker) }
            .map { (k, v) -> k.removePrefix(marker) to (v as? Long ?: 0L) }
            .sortedByDescending { it.second }
    }
}
