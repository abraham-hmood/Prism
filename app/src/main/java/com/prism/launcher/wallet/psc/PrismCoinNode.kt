package com.prism.launcher.wallet.psc

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import com.prism.launcher.wallet.MiningHasher
import com.prism.launcher.wallet.WalletCrypto
import java.io.File
import java.math.BigInteger

/**
 * PrismCoin's node: the chain, and its life on the mesh.
 *
 * ## Only wallet-page devices participate
 *
 * [isEligible] checks the device's own slot layout for [SlotAssignment.Wallet]. A phone that has
 * not put the wallet on its desktop neither mines nor relays -- it is not opted in, and quietly
 * conscripting every mesh peer into running a blockchain would be a rude thing for a launcher to
 * do. The practical consequence is that PrismCoin's network is a SUBSET of the mesh, and a small
 * one at first, which matters for everything below.
 *
 * ## Gossip, not a connection
 *
 * Blocks and transactions ride the existing mesh gossip rather than opening dedicated sockets.
 * That gives peer discovery and NAT traversal for free, and it inherits the mesh's delivery
 * characteristics: best-effort, unordered, and lossy across partitions. The chain is built to
 * survive exactly that -- orphan blocks are held rather than discarded, and chain selection is by
 * cumulative work so a rejoining partition converges rather than argues.
 *
 * ## What a small network means, stated plainly
 *
 * Proof-of-work security is proportional to total hash rate. On a network of a few dozen phones,
 * anyone with a desktop GPU out-hashes the entire chain and can rewrite recent history at will.
 * [PscChain.RECOMMENDED_CONFIRMATIONS] is set high for this reason, and it is still not a
 * substitute for scale. PrismCoin is secure in proportion to how many people mine it, which at the
 * start is not very.
 */
object PrismCoinNode {

    private const val TAG = "PrismCoin"

    /** Mesh gossip opcodes, distinct from the DNS and social ones already in use. */
    const val OPCODE_BLOCK: Byte = 0x40
    const val OPCODE_TX: Byte = 0x41
    const val OPCODE_HEAD: Byte = 0x42

    val chain = PscChain()

    @Volatile
    private var loaded = false

    /**
     * Whether this device takes part.
     *
     * Reads the slot layout rather than a separate setting, exactly as specified: having the wallet
     * page on the desktop IS the opt-in.
     */
    fun isEligible(): Boolean =
        SlotPreferences().getAssignments().any { it is SlotAssignment.Wallet }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun chainFile(context: Context) = File(context.filesDir, "psc/chain.json")

    /**
     * Replays the stored chain.
     *
     * Blocks are re-validated on load rather than trusted: a chain file is app storage, which is
     * writable, and accepting it wholesale would let anyone with file access mint themselves a
     * balance.
     */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = chainFile(context)
        if (!file.exists()) return

        runCatching {
            val array = JSONArray(file.readText())
            var replayed = 0
            for (i in 0 until array.length()) {
                val block = blockFromJson(array.getJSONObject(i)) ?: continue
                if (block.height == 0L) continue                 // genesis is already present
                val result = chain.considerBlock(block, Long.MAX_VALUE / 2)
                if (result is PscChain.BlockResult.Rejected) {
                    PrismLogger.logError(TAG, "Stored block ${block.height} rejected: ${result.reason}", null)
                    break
                }
                replayed++
            }
            PrismLogger.logSuccess(TAG, "Replayed $replayed PrismCoin blocks to height ${chain.height()}")
        }.onFailure {
            PrismLogger.logError(TAG, "Chain file could not be read; starting from genesis", it)
        }
    }

    /**
     * Writes the chain out, oldest block first.
     *
     * REFUSES TO WRITE IF THE CHAIN WAS NEVER LOADED. save() serialises from the tip, so calling it
     * on a node still sitting at genesis replaces a file full of history with a file containing
     * almost nothing. That is exactly what happened: load() was called from nowhere, every launch
     * started at height 0, and the first block mined wiped every block found in every previous
     * session. A device with hundreds of shares to its name reported height 0 and no balance.
     *
     * Losing the write is always better than losing the chain -- the block is still in memory and
     * the next save after a load will persist it -- so this fails loudly and does nothing rather
     * than doing the destructive thing.
     */
    fun save(context: Context) {
        if (!loaded) {
            PrismLogger.logError(
                TAG,
                "Refusing to save the chain before it has been loaded; this would overwrite " +
                    "stored blocks with an empty chain. Call load() during startup.",
                null,
            )
            return
        }
        runCatching {
            val file = chainFile(context)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            // Oldest first, so a replay applies them in order.
            for (block in chain.chainFromTip(limit = 100_000).reversed()) {
                array.put(blockToJson(block))
            }
            file.writeText(array.toString())
        }.onFailure { PrismLogger.logError(TAG, "Could not persist the chain", it) }
    }

    // ── Mesh traffic ───────────────────────────────────────────────────────

    /** Announces a block to every peer. */
    fun broadcastBlock(block: PscBlock) {
        if (!isEligible()) return
        runCatching {
            com.prism.launcher.mesh.PrismMeshService.broadcastToOthers(
                OPCODE_BLOCK, blockToJson(block).toString()
            )
        }
    }

    fun broadcastTransaction(tx: PscTransaction) {
        if (!isEligible()) return
        runCatching {
            com.prism.launcher.mesh.PrismMeshService.broadcastToOthers(
                OPCODE_TX, txToJson(tx).toString()
            )
        }
    }

    /**
     * Handles an inbound gossip payload.
     *
     * Returns true when the message was PrismCoin's, so the mesh dispatcher can stop looking.
     * Anything that fails to parse or validate is dropped silently -- gossip is untrusted input
     * arriving from anyone on the network.
     */
    fun onMeshMessage(context: Context, opcode: Byte, payload: String): Boolean {
        if (!isEligible()) return opcode in listOf(OPCODE_BLOCK, OPCODE_TX, OPCODE_HEAD)

        return when (opcode) {
            OPCODE_BLOCK -> {
                val block = runCatching { blockFromJson(JSONObject(payload)) }.getOrNull()
                if (block != null) {
                    when (val result = chain.considerBlock(block)) {
                        is PscChain.BlockResult.Extended -> {
                            save(context)
                            com.prism.launcher.wallet.WalletReceiveNotifier
                                .onPrismCoinBlock(context, block.transactions)
                            // Relay onward: gossip has no routing, so propagation is every node
                            // repeating what it accepted.
                            broadcastBlock(block)
                        }
                        is PscChain.BlockResult.Reorganised -> {
                            save(context)
                            if (result.depth >= PscChain.REORG_DEPTH_WARNING) {
                                PrismLogger.logError(
                                    TAG,
                                    "Reorg ${result.depth} blocks deep — payments considered " +
                                        "settled may have been undone",
                                    null,
                                )
                            }
                            broadcastBlock(block)
                        }
                        else -> Unit
                    }
                }
                true
            }
            OPCODE_TX -> {
                val tx = runCatching { txFromJson(JSONObject(payload)) }.getOrNull()
                if (tx != null && chain.submit(tx) == PscChain.TxResult.Accepted) {
                    broadcastTransaction(tx)
                }
                true
            }
            OPCODE_HEAD -> true
            else -> false
        }
    }

    // ── Mining ─────────────────────────────────────────────────────────────

    /**
     * How many threads hash at once.
     *
     * One short of the core count, so the UI thread and the mesh's own I/O still get a core on an
     * otherwise busy phone -- a miner that pins every core makes the launcher itself stutter, and
     * the last core buys proportionally less than it costs in heat and responsiveness. Floored at
     * one so a single-core device still mines.
     */
    private val miningThreads: Int =
        maxOf(1, Runtime.getRuntime().availableProcessors() - 1)

    /**
     * Hashes one candidate for up to [budgetMillis], returning a solved block or null.
     *
     * Time-boxed rather than run to completion so the caller can rebuild on a new tip: a block
     * found on a stale parent is wasted work, and on a mesh the tip moves without warning.
     *
     * ## Why this is threaded
     *
     * On real devices this loop managed a few hundred kH/s, far below the 2 MH/s the issuance peg
     * uses as its reference rate -- so a device was minting well under the one PSC per hour that
     * one reference-hour of work is supposed to buy.
     *
     * THE HASH WAS NOT THE PROBLEM, which is worth recording because it is the natural place to
     * look. Benchmarked, the platform digest already runs at roughly the reference rate on one
     * core, because both the JVM and Android's Conscrypt lower SHA-256 onto dedicated CPU
     * instructions; a hand-written replacement keeping a midstate across the constant head of the
     * header came out nearly four times slower and was thrown away. The numbers are in
     * [MiningHasher]. What was left was that only ONE core ever hashed, and that every attempt
     * allocated two arrays in the digest plus a BigInteger for the target comparison. This method
     * fixes the first and [MiningHasher] the second, which together is the order of magnitude.
     *
     * NONCES ARE STRIDED, NOT BLOCKED. Thread i takes i, i+n, i+2n and so on. A contiguous split
     * would be equivalent only if every range were equally likely to hold the answer and every
     * thread ran equally fast, and neither holds on a big.LITTLE phone: the little cores would
     * still be grinding the low end of their range while the big cores had finished theirs and gone
     * idle. Striding keeps every thread on live work for the whole budget.
     *
     * Each thread gets its OWN template and hasher, which their contracts require -- both are
     * mutable buffers with no lock, and sharing one would have threads overwriting each other's
     * nonces and hashing headers nobody submitted.
     */
    fun mineOnce(minerAddress: String, budgetMillis: Long = 15_000): PscBlock? {
        val candidate = chain.buildCandidate(minerAddress)
        val deadline = System.currentTimeMillis() + budgetMillis
        val parentId = candidate.previousHash

        // Built once. Everything but the nonce -- including the merkle root over every
        // transaction -- is constant for this candidate, and recomputing it per attempt was
        // costing far more than the hashing itself.
        val target = candidate.target
        // The target as 32 unsigned big-endian bytes, so the comparison is a byte scan rather than
        // a BigInteger allocated per hash.
        val targetBytes = WalletCrypto.toFixedBytes(target, 32)

        val found = java.util.concurrent.atomic.AtomicReference<PscBlock?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val threads = miningThreads

        val workers = (0 until threads).map { index ->
            Thread({
                val template = candidate.headerTemplate()
                val hasher = MiningHasher()

                var nonce = index.toLong()
                var sinceCheck = 0L
                try {
                    while (nonce < 0xFFFFFFFFL) {
                        hasher.hash(template.withNonce(nonce))
                        sinceCheck++
                        if (hasher.digestAtMost(targetBytes)) {
                            hashesAttempted.addAndGet(sinceCheck)
                            sinceCheck = 0
                            // First writer wins; the rest fall through and stop.
                            found.compareAndSet(null, candidate.copy(nonce = nonce))
                            stop.set(true)
                            return@Thread
                        }
                        nonce += threads

                        // Batched: a clock read, a tip comparison and an atomic add per hash would
                        // each cost more than the hash they are guarding.
                        if (sinceCheck and 0x3FFF == 0L) {
                            hashesAttempted.addAndGet(sinceCheck)
                            sinceCheck = 0
                            if (stop.get()) return@Thread
                            if (System.currentTimeMillis() >= deadline) return@Thread
                            // Abandon promptly if somebody else extended the chain under us.
                            if (chain.tip.block.id() != parentId) {
                                stop.set(true)
                                return@Thread
                            }
                        }
                    }
                } finally {
                    if (sinceCheck > 0) hashesAttempted.addAndGet(sinceCheck)
                }
            }, "psc-mine-$index").apply {
                // Below the UI so a foreground miner cannot make the launcher drop frames.
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }
        }

        // Bounded by the budget the workers already honour; the join is just the rendezvous.
        workers.forEach { runCatching { it.join() } }
        return found.get()
    }

    /**
     * Hashes attempted since the process started.
     *
     * Counted here rather than estimated from elapsed time, which is what the service was doing
     * before -- a wall-clock guess reports a hash rate even when the miner is stalled.
     */
    val hashesAttempted = java.util.concurrent.atomic.AtomicLong(0)

    fun submitMined(context: Context, block: PscBlock): Boolean {
        val result = chain.considerBlock(block)
        val accepted = result is PscChain.BlockResult.Extended ||
            result is PscChain.BlockResult.Reorganised
        if (accepted) {
            save(context)
            com.prism.launcher.wallet.WalletReceiveNotifier
                .onPrismCoinBlock(context, block.transactions)
            broadcastBlock(block)
            PrismLogger.logSuccess(
                TAG,
                "Mined PrismCoin block ${block.height}, reward " +
                    "${block.reward().toBigDecimal().divide(PrismCoinConsensus.ONE_PSC.toBigDecimal())} PSC"
            )
        }
        return accepted
    }

    // ── Serialisation ──────────────────────────────────────────────────────

    private fun blockToJson(b: PscBlock): JSONObject = JSONObject()
        .put("version", b.version)
        .put("prev", b.previousHash)
        .put("height", b.height)
        .put("time", b.timestamp)
        .put("target", b.target.toString(16))
        .put("nonce", b.nonce)
        .put("miner", b.miner)
        .put("txs", JSONArray().also { arr -> b.transactions.forEach { arr.put(txToJson(it)) } })

    private fun blockFromJson(o: JSONObject): PscBlock? = runCatching {
        val txArray = o.optJSONArray("txs") ?: JSONArray()
        PscBlock(
            version = o.optInt("version", 1),
            previousHash = o.getString("prev"),
            height = o.getLong("height"),
            timestamp = o.getLong("time"),
            target = BigInteger(o.getString("target"), 16),
            nonce = o.getLong("nonce"),
            miner = o.getString("miner"),
            transactions = (0 until txArray.length()).mapNotNull {
                txFromJson(txArray.getJSONObject(it))
            },
        )
    }.getOrNull()

    /**
     * A signed transaction as text, so it can be stored and sent later.
     *
     * The model shop signs a purchase at checkout and deliberately holds it unsent until the
     * listing passes its first scan -- see [com.prism.launcher.ModelPurchaseLedger] for why the
     * refund rule is met by not paying yet rather than by paying and reversing.
     */
    fun serialiseTransaction(t: PscTransaction): String = txToJson(t).toString()

    /** Submits and gossips a transaction that was signed earlier. */
    fun submitSignedTransaction(context: Context, json: String): Boolean {
        val tx = runCatching { txFromJson(JSONObject(json)) }.getOrNull() ?: return false
        val result = chain.submit(tx)
        if (result is PscChain.TxResult.Rejected) {
            PrismLogger.logWarning(TAG, "Held payment rejected: ${result.reason}")
            return false
        }
        broadcastTransaction(tx)
        save(context)
        return true
    }

    private fun txToJson(t: PscTransaction): JSONObject = JSONObject()
        .put("from", t.from)
        .put("to", t.to)
        .put("amount", t.amount.toString())
        .put("fee", t.fee.toString())
        .put("nonce", t.nonce)
        .put("pub", WalletCrypto.toHex(t.publicKey))
        .put("note", t.note)
        .put("sig", WalletCrypto.toHex(t.signature))

    private fun txFromJson(o: JSONObject): PscTransaction? = runCatching {
        PscTransaction(
            from = o.getString("from"),
            to = o.getString("to"),
            amount = BigInteger(o.getString("amount")),
            fee = BigInteger(o.getString("fee")),
            nonce = o.getLong("nonce"),
            publicKey = WalletCrypto.fromHex(o.getString("pub")),
            note = o.optString("note", ""),
            signature = WalletCrypto.fromHex(o.getString("sig")),
        )
    }.getOrNull()
}
