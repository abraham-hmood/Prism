package com.prism.launcher.wallet.psc

import java.math.BigInteger

/**
 * The PrismCoin ledger: blocks, balances, and which chain wins.
 *
 * ## Most work, not longest
 *
 * Chain selection compares CUMULATIVE WORK, never height. On a mesh this is not a technicality --
 * a partition of three phones can produce more blocks than a partition of thirty while having done
 * a fraction of the work, and a longest-chain rule would hand the ledger to the smaller group every
 * time the network healed.
 *
 * ## Reorgs are normal here, and the design says so
 *
 * A conventional chain treats a reorg as an event. A mesh partitions constantly, so competing
 * branches are the expected steady state and [considerBlock] keeps every branch it can validate
 * until one clearly wins. [REORG_DEPTH_WARNING] exists so callers can tell a user that a payment
 * they believed settled has been undone -- which on this network will happen, and silently
 * swallowing it would be the dishonest choice.
 *
 * ## Validation is total
 *
 * A block is accepted only if its proof of work stands, its target matches what the rules say it
 * must be at that height, its timestamp is sane, and EVERY transaction in it verifies against the
 * state as of its parent. There is no partial acceptance: a block with one bad transaction is not
 * a block.
 */
class PscChain(genesis: PscBlock = PscBlock.genesis()) {

    companion object {
        /** Past this, a reorg has undone something a user was probably shown as confirmed. */
        const val REORG_DEPTH_WARNING = 3

        /** A block dated further ahead than this is rejected outright. */
        const val MAX_FUTURE_DRIFT_SECONDS = 2 * 60 * 60L

        /** Confirmations before the UI should call a payment settled on a mesh. */
        const val RECOMMENDED_CONFIRMATIONS = 12
    }

    /** A block plus everything derived from the branch it sits on. */
    data class Entry(
        val block: PscBlock,
        val totalWork: BigInteger,
        val balances: Map<String, BigInteger>,
        val nonces: Map<String, Long>,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val mempool = LinkedHashMap<String, PscTransaction>()

    var tip: Entry private set

    /** Blocks that arrived before their parent, held in case it turns up. */
    private val orphans = LinkedHashMap<String, PscBlock>()

    var lastReorgDepth: Int = 0
        private set

    init {
        val entry = Entry(genesis, PrismCoinConsensus.workOf(genesis.target), emptyMap(), emptyMap())
        entries[genesis.id()] = entry
        tip = entry
    }

    // ── Reading ────────────────────────────────────────────────────────────

    fun height(): Long = tip.block.height

    fun balanceOf(address: String): BigInteger = tip.balances[address] ?: BigInteger.ZERO

    fun nonceOf(address: String): Long = tip.nonces[address] ?: 0L

    /**
     * How much of [balanceOf] was mined too recently to be relied on.
     *
     * Block rewards are credited the moment a block joins the chain, which is right -- the coins
     * exist -- but a block near the tip can still be undone by a reorg, and on a mesh the tip moves
     * without warning. So the wallet shows the whole balance and says which part of it is still
     * settling, rather than hiding recent rewards until they mature: a miner who just found a block
     * and sees no change at all assumes mining is broken.
     *
     * Counts only rewards, not received payments. An incoming transfer is the sender's risk to
     * explain, and its confirmations are already reported by the payment flow.
     */
    fun unconfirmedMined(
        address: String,
        confirmations: Int = RECOMMENDED_CONFIRMATIONS,
    ): BigInteger {
        var total = BigInteger.ZERO
        // chainFromTip walks back from the tip, so the first `confirmations` entries are exactly
        // the blocks that do not yet have that many blocks built on top of them.
        for (block in chainFromTip(confirmations)) {
            if (block.height == 0L) continue          // genesis mints nothing
            if (block.miner == address) total = total.add(block.reward())
        }
        return total
    }

    fun blockById(id: String): PscBlock? = entries[id]?.block

    fun contains(id: String): Boolean = entries.containsKey(id)

    fun totalSupply(): BigInteger =
        tip.balances.values.fold(BigInteger.ZERO) { acc, v -> acc.add(v) }

    /** Total hours of work the chain represents -- one per PSC, by construction. */
    fun totalWorkHours(): Double =
        totalSupply().toBigDecimal().divide(PrismCoinConsensus.ONE_PSC.toBigDecimal()).toDouble()

    fun chainFromTip(limit: Int = 50): List<PscBlock> {
        val out = ArrayList<PscBlock>()
        var cursor: Entry? = tip
        while (cursor != null && out.size < limit) {
            out.add(cursor.block)
            cursor = entries[cursor.block.previousHash]
        }
        return out
    }

    /** The target the rules demand at [height], given the branch ending at [parent]. */
    fun targetForNext(parent: Entry): BigInteger {
        val nextHeight = parent.block.height + 1
        if (nextHeight % PrismCoinConsensus.RETARGET_INTERVAL != 0L) return parent.block.target

        var cursor: Entry? = parent
        var walked = 0
        while (cursor != null && walked < PrismCoinConsensus.RETARGET_INTERVAL - 1) {
            cursor = entries[cursor.block.previousHash]
            walked++
        }
        val start = cursor ?: return parent.block.target
        val elapsed = parent.block.timestamp - start.block.timestamp
        return PrismCoinConsensus.nextTarget(parent.block.target, elapsed.coerceAtLeast(1L))
    }

    // ── Mempool ────────────────────────────────────────────────────────────

    sealed class TxResult {
        data object Accepted : TxResult()
        data class Rejected(val reason: String) : TxResult()
    }

    fun submit(transaction: PscTransaction): TxResult {
        if (mempool.containsKey(transaction.id())) return TxResult.Accepted
        if (!transaction.verify()) return TxResult.Rejected("Signature or sender does not check out.")

        val required = transaction.amount.add(transaction.fee)
        if (balanceOf(transaction.from) < required) {
            return TxResult.Rejected("Insufficient balance.")
        }
        if (transaction.nonce != nonceOf(transaction.from)) {
            return TxResult.Rejected(
                "Wrong nonce: expected ${nonceOf(transaction.from)}, got ${transaction.nonce}."
            )
        }
        mempool[transaction.id()] = transaction
        return TxResult.Accepted
    }

    fun pending(limit: Int = 500): List<PscTransaction> = mempool.values.take(limit)

    fun mempoolSize(): Int = mempool.size

    // ── Blocks ─────────────────────────────────────────────────────────────

    sealed class BlockResult {
        /** Extended the current best chain. */
        data object Extended : BlockResult()

        /** Replaced the tip with a heavier branch. [depth] blocks were undone. */
        data class Reorganised(val depth: Int) : BlockResult()

        /** Valid, but on a branch with less work than the current one. */
        data object SideBranch : BlockResult()

        data object AlreadyHave : BlockResult()

        /** Parent unknown; held in case it arrives. */
        data object Orphaned : BlockResult()

        data class Rejected(val reason: String) : BlockResult()
    }

    /**
     * Validates a block and, if it wins on work, adopts it.
     *
     * Validation happens against the state at the block's PARENT, not at the current tip. That
     * distinction is the whole reason competing branches can be evaluated at all -- a block on a
     * side branch is perfectly valid there while being nonsense against the tip.
     */
    fun considerBlock(block: PscBlock, now: Long = System.currentTimeMillis() / 1000): BlockResult {
        if (entries.containsKey(block.id())) return BlockResult.AlreadyHave

        if (!block.meetsTarget()) return BlockResult.Rejected("Proof of work does not meet the target.")
        if (block.timestamp > now + MAX_FUTURE_DRIFT_SECONDS) {
            return BlockResult.Rejected("Block is dated too far in the future.")
        }
        if (!PrismCoinConsensus.isValidAddress(block.miner) && block.height > 0) {
            return BlockResult.Rejected("Miner address is not a valid PSC address.")
        }

        val parent = entries[block.previousHash]
            ?: run {
                orphans[block.id()] = block
                return BlockResult.Orphaned
            }

        if (block.height != parent.block.height + 1) {
            return BlockResult.Rejected("Height does not follow its parent.")
        }
        if (block.timestamp < parent.block.timestamp) {
            return BlockResult.Rejected("Block is older than its parent.")
        }
        // The target is not the miner's choice. Accepting a self-declared target would let anyone
        // mine at difficulty 1 and mint an unbounded reward.
        val requiredTarget = targetForNext(parent)
        if (block.target != requiredTarget) {
            return BlockResult.Rejected("Target does not match the retargeting rules.")
        }

        val applied = applyTransactions(parent, block)
            ?: return BlockResult.Rejected("A transaction in this block is not valid on this branch.")

        val entry = Entry(
            block = block,
            totalWork = parent.totalWork.add(PrismCoinConsensus.workOf(block.target)),
            balances = applied.first,
            nonces = applied.second,
        )
        entries[block.id()] = entry

        val result = when {
            block.previousHash == tip.block.id() -> {
                tip = entry
                lastReorgDepth = 0
                BlockResult.Extended
            }
            entry.totalWork > tip.totalWork -> {
                val depth = forkDepth(tip, entry)
                tip = entry
                lastReorgDepth = depth
                BlockResult.Reorganised(depth)
            }
            else -> BlockResult.SideBranch
        }

        // Transactions that made it into a block are no longer pending.
        for (tx in block.transactions) mempool.remove(tx.id())
        adoptOrphans(now)
        return result
    }

    /** Replays held orphans once their parent is known. */
    private fun adoptOrphans(now: Long) {
        if (orphans.isEmpty()) return
        val ready = orphans.values.filter { entries.containsKey(it.previousHash) }
        for (block in ready) {
            orphans.remove(block.id())
            considerBlock(block, now)
        }
    }

    /** How many blocks the old tip loses when [next} takes over. */
    private fun forkDepth(from: Entry, to: Entry): Int {
        val ancestors = HashSet<String>()
        var cursor: Entry? = to
        while (cursor != null) {
            ancestors.add(cursor.block.id())
            cursor = entries[cursor.block.previousHash]
        }
        var depth = 0
        var walk: Entry? = from
        while (walk != null && !ancestors.contains(walk.block.id())) {
            depth++
            walk = entries[walk.block.previousHash]
        }
        return depth
    }

    /**
     * Applies a block's transactions plus its implicit coinbase, or null if anything is invalid.
     *
     * The miner is credited the derived reward plus fees. Because the reward comes from
     * [PrismCoinConsensus.blockReward] rather than from the block itself, a miner cannot claim more
     * than the work they did.
     */
    private fun applyTransactions(
        parent: Entry,
        block: PscBlock,
    ): Pair<Map<String, BigInteger>, Map<String, Long>>? {
        val balances = HashMap(parent.balances)
        val nonces = HashMap(parent.nonces)
        var fees = BigInteger.ZERO

        val seen = HashSet<String>()
        for (tx in block.transactions) {
            if (!seen.add(tx.id())) return null                 // duplicate inside one block
            if (!tx.verify()) return null

            val expectedNonce = nonces[tx.from] ?: 0L
            if (tx.nonce != expectedNonce) return null

            val required = tx.amount.add(tx.fee)
            val available = balances[tx.from] ?: BigInteger.ZERO
            if (available < required) return null

            balances[tx.from] = available.subtract(required)
            balances[tx.to] = (balances[tx.to] ?: BigInteger.ZERO).add(tx.amount)
            nonces[tx.from] = expectedNonce + 1
            fees = fees.add(tx.fee)
        }

        if (block.height > 0) {
            val payout = block.reward().add(fees)
            balances[block.miner] = (balances[block.miner] ?: BigInteger.ZERO).add(payout)
        }
        return balances to nonces
    }

    /**
     * Assembles a candidate block for a miner.
     *
     * Only transactions valid against the current tip go in -- including one whose sender is spent
     * out by an earlier transaction in the same block, which is why balances are tracked as the
     * candidate is built rather than checked once at the start.
     */
    fun buildCandidate(minerAddress: String, timestamp: Long = System.currentTimeMillis() / 1000): PscBlock {
        val balances = HashMap(tip.balances)
        val nonces = HashMap(tip.nonces)
        val included = ArrayList<PscTransaction>()

        for (tx in mempool.values.sortedByDescending { it.fee }) {
            val expected = nonces[tx.from] ?: 0L
            if (tx.nonce != expected) continue
            val required = tx.amount.add(tx.fee)
            val available = balances[tx.from] ?: BigInteger.ZERO
            if (available < required) continue

            balances[tx.from] = available.subtract(required)
            balances[tx.to] = (balances[tx.to] ?: BigInteger.ZERO).add(tx.amount)
            nonces[tx.from] = expected + 1
            included.add(tx)
            if (included.size >= 500) break
        }

        return PscBlock(
            version = 1,
            previousHash = tip.block.id(),
            height = tip.block.height + 1,
            // A parent-dated block would be rejected by everyone else, so never emit one.
            timestamp = maxOf(timestamp, tip.block.timestamp + 1),
            target = targetForNext(tip),
            nonce = 0,
            miner = minerAddress,
            transactions = included,
        )
    }
}
