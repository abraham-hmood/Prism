package com.prism.launcher.wallet.psc

import com.prism.launcher.wallet.Base58
import com.prism.launcher.wallet.EcdsaSigner
import com.prism.launcher.wallet.WalletCrypto
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * PrismCoin's consensus rules.
 *
 * ## The issuance peg: one PSC per hour of work
 *
 * This is the point of the coin, and it is enforced by construction rather than by policy.
 *
 * A block at difficulty `D` takes `D * 2^32` hashes to find, on average. One "hour of work" is
 * defined as [REFERENCE_HASHES_PER_HOUR] -- what a reference device produces in an hour. So the
 * block reward is not a fixed schedule; it is DERIVED from the difficulty the block was actually
 * mined at:
 *
 *     reward = (D * 2^32) / REFERENCE_HASHES_PER_HOUR
 *
 * The consequence is exact: every PSC that has ever existed represents one reference-hour of
 * computation, no matter when it was minted, how many miners were competing, or what the difficulty
 * was at the time. Difficulty retargeting keeps block INTERVALS steady; the reward formula keeps
 * the work-per-coin steady. Two knobs, two independent guarantees.
 *
 * ## What this peg is and is not
 *
 * IT PEGS ISSUANCE, NOT PRICE. One PSC always costs one reference-hour to produce; what anyone will
 * pay for it is a separate question that no consensus rule can answer. See [PrismCoinValuation],
 * which computes both numbers and never conflates them.
 *
 * SUPPLY IS UNBOUNDED, deliberately. There is no halving and no cap, because a labour-hour unit
 * that stopped being issued would stop tracking labour. Total supply grows exactly in proportion to
 * work performed on the network, which is what "one PSC per hour of work" means when taken
 * seriously. It is inflationary by design in a way Bitcoin is not.
 *
 * REFERENCE HOURS ARE MACHINE HOURS. As hardware improves, one reference-hour becomes cheaper in
 * real terms. The peg holds against the definition, not against human wages -- the gap between the
 * two is exactly what [PrismCoinValuation] surfaces.
 *
 * ## Account model, not UTXO
 *
 * Balances and nonces, in the style of Ethereum rather than Bitcoin. Chosen because it is a
 * fraction of the code of a UTXO set and every bug in it is a bug about somebody's money -- on a
 * chain written from scratch, "smaller and fully testable" beats "matches Bitcoin's design".
 */
object PrismCoinConsensus {

    const val SYMBOL = "PSC"
    const val NAME = "PrismCoin"
    const val DECIMALS = 8

    /** Smallest unit, 1e-8 PSC. */
    val ONE_PSC: BigInteger = BigInteger.TEN.pow(DECIMALS)

    /** Base58 version byte; PSC addresses begin with `P`. */
    const val ADDRESS_VERSION = 0x37

    /** Distinguishes PrismCoin messages on the mesh from every other gossip payload. */
    val MAGIC = byteArrayOf(0x50, 0x53, 0x43, 0x01)

    /**
     * A reference device's SHA-256d rate, in hashes per second, and therefore the definition of
     * "an hour of work".
     *
     * A CONSENSUS CONSTANT, not a measurement. It cannot be sampled from real hardware without
     * every node computing a different reward for the same block, which would split the chain
     * instantly. 2 MH/s is chosen as a mid-range phone doing SHA-256d in the JVM.
     */
    const val REFERENCE_HASHES_PER_SECOND = 2_000_000L
    const val REFERENCE_HASHES_PER_HOUR = REFERENCE_HASHES_PER_SECOND * 3600L

    /** Target seconds between blocks. */
    const val TARGET_BLOCK_SECONDS = 120L

    /** Blocks between difficulty retargets. */
    const val RETARGET_INTERVAL = 30

    /** Difficulty-1 target, the same convention Bitcoin uses. */
    val DIFF1_TARGET: BigInteger =
        BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16)

    /**
     * The opening difficulty target.
     *
     * Far easier than Bitcoin's genesis, because a mesh of phones starts with a hash rate measured
     * in MH/s rather than EH/s. Starting too hard would mean no second block ever gets found.
     */
    val GENESIS_TARGET: BigInteger = DIFF1_TARGET.multiply(BigInteger.valueOf(65536))

    /** Retargeting is clamped so one anomalous span cannot swing the chain wildly. */
    const val MAX_RETARGET_FACTOR = 4.0

    /**
     * Reward for a block mined at [target], in smallest units.
     *
     * This is the peg. `difficulty = DIFF1_TARGET / target`, expected hashes = `difficulty * 2^32`,
     * and the reward is that divided by an hour of reference work.
     */
    fun blockReward(target: BigInteger): BigInteger {
        if (target.signum() <= 0) return BigInteger.ZERO
        val expectedHashes = DIFF1_TARGET
            .multiply(BigInteger.ONE.shiftLeft(32))
            .divide(target)
        return expectedHashes
            .multiply(ONE_PSC)
            .divide(BigInteger.valueOf(REFERENCE_HASHES_PER_HOUR))
    }

    /** Difficulty as a multiple of difficulty 1, for display. */
    fun difficultyOf(target: BigInteger): Double {
        if (target.signum() <= 0) return 0.0
        return DIFF1_TARGET.toBigDecimal()
            .divide(target.toBigDecimal(), java.math.MathContext.DECIMAL64)
            .toDouble()
    }

    /**
     * The next target, from how long the last [RETARGET_INTERVAL] blocks actually took.
     *
     * Standard Bitcoin-style retargeting: slower than intended means ease off, faster means tighten.
     * The clamp matters more here than on a well-connected chain -- a mesh partition can deliver a
     * burst of blocks with wild timestamps when it rejoins.
     */
    fun nextTarget(currentTarget: BigInteger, actualSeconds: Long): BigInteger {
        val expected = TARGET_BLOCK_SECONDS * RETARGET_INTERVAL
        val clamped = actualSeconds.coerceIn(
            (expected / MAX_RETARGET_FACTOR).toLong().coerceAtLeast(1L),
            (expected * MAX_RETARGET_FACTOR).toLong(),
        )
        val next = currentTarget
            .multiply(BigInteger.valueOf(clamped))
            .divide(BigInteger.valueOf(expected))
        // Never easier than the genesis target, or the chain can be trivially rewritten.
        return if (next > GENESIS_TARGET) GENESIS_TARGET else next.max(BigInteger.ONE)
    }

    /** Work represented by a block, for most-work chain selection. */
    fun workOf(target: BigInteger): BigInteger {
        if (target.signum() <= 0) return BigInteger.ZERO
        return BigInteger.ONE.shiftLeft(256).divide(target.add(BigInteger.ONE))
    }

    fun address(publicKeyHash: ByteArray): String =
        Base58.encodeChecked(byteArrayOf(ADDRESS_VERSION.toByte()) + publicKeyHash)

    fun addressFor(privateKey: BigInteger): String =
        address(WalletCrypto.hash160(WalletCrypto.compressedPublicKey(privateKey)))

    fun isValidAddress(candidate: String): Boolean = runCatching {
        val payload = Base58.decodeChecked(candidate)
        payload.size == 21 && (payload[0].toInt() and 0xff) == ADDRESS_VERSION
    }.getOrDefault(false)
}

/**
 * A value transfer.
 *
 * The NONCE is what stops a transaction being replayed: each account's transactions must arrive in
 * order, starting at zero, so a captured and re-broadcast transaction is rejected as already spent
 * rather than draining an account repeatedly.
 */
data class PscTransaction(
    val from: String,
    val to: String,
    val amount: BigInteger,
    val fee: BigInteger,
    val nonce: Long,
    val publicKey: ByteArray,
    /**
     * An optional message to the recipient.
     *
     * PART OF THE SIGNED BYTES, deliberately. A note that travelled outside the signature could be
     * rewritten by any relaying node -- so a payment marked "rent, March" could arrive saying
     * something else entirely. Signing it makes the note as trustworthy as the amount.
     */
    val note: String = "",
    val signature: ByteArray = ByteArray(0),
) {
    /** Everything the signature commits to. The signature itself is excluded, necessarily. */
    fun signingBytes(): ByteArray = ByteArrayOutputStream().apply {
        write(PrismCoinConsensus.MAGIC)
        write(from.toByteArray(Charsets.UTF_8))
        write(to.toByteArray(Charsets.UTF_8))
        write(amount.toString().toByteArray(Charsets.US_ASCII))
        write(fee.toString().toByteArray(Charsets.US_ASCII))
        write(nonce.toString().toByteArray(Charsets.US_ASCII))
        write(publicKey)
        write(note.toByteArray(Charsets.UTF_8))
    }.toByteArray()

    fun hash(): ByteArray = WalletCrypto.sha256d(signingBytes() + signature)

    fun id(): String = WalletCrypto.toHex(hash())

    fun sign(privateKey: BigInteger): PscTransaction {
        val sig = EcdsaSigner.sign(WalletCrypto.sha256d(signingBytes()), privateKey)
        val encoded = WalletCrypto.toFixedBytes(sig.r, 32) + WalletCrypto.toFixedBytes(sig.s, 32)
        return copy(signature = encoded)
    }

    /**
     * Checks the signature AND that the public key actually derives [from].
     *
     * The second half is the part that is easy to forget and fatal to omit: a valid signature by
     * some other key proves only that somebody signed something, not that the sender authorised
     * spending from their own account.
     */
    fun verify(): Boolean {
        if (signature.size != 64) return false
        if (amount.signum() < 0 || fee.signum() < 0) return false
        if (nonce < 0) return false
        if (note.length > MAX_NOTE_LENGTH) return false
        if (!PrismCoinConsensus.isValidAddress(from)) return false
        if (!PrismCoinConsensus.isValidAddress(to)) return false
        if (PrismCoinConsensus.address(WalletCrypto.hash160(publicKey)) != from) return false

        return runCatching {
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            val point = WalletCrypto.SECP256K1.curve.decodePoint(publicKey)
            val verifier = org.bouncycastle.crypto.signers.ECDSASigner()
            verifier.init(
                false,
                org.bouncycastle.crypto.params.ECPublicKeyParameters(point, WalletCrypto.SECP256K1)
            )
            verifier.verifySignature(WalletCrypto.sha256d(signingBytes()), r, s)
        }.getOrDefault(false)
    }

    override fun equals(other: Any?): Boolean =
        other is PscTransaction && id() == other.id()

    override fun hashCode(): Int = id().hashCode()

    companion object {
        /** Enough for a human message, short enough that notes cannot be used to bloat blocks. */
        const val MAX_NOTE_LENGTH = 140
    }
}

/**
 * A block.
 *
 * The coinbase is IMPLICIT: rather than carrying a synthetic transaction, a block names its [miner]
 * and the reward is computed from the target it was mined at. That removes an entire class of
 * validation bug -- there is no way to write a coinbase claiming the wrong amount, because the
 * amount is not written down anywhere.
 */
data class PscBlock(
    val version: Int,
    val previousHash: String,
    val height: Long,
    val timestamp: Long,
    val target: BigInteger,
    val nonce: Long,
    val miner: String,
    val transactions: List<PscTransaction>,
) {
    fun merkleRoot(): ByteArray {
        if (transactions.isEmpty()) return ByteArray(32)
        var layer = transactions.map { it.hash() }
        while (layer.size > 1) {
            layer = layer.chunked(2).map { pair ->
                WalletCrypto.sha256d(pair[0] + (pair.getOrNull(1) ?: pair[0]))
            }
        }
        return layer.first()
    }

    /** The bytes proof-of-work is computed over. */
    fun headerBytes(): ByteArray = headerTemplate().snapshot(nonce)

    /**
     * The header with everything but the nonce precomputed.
     *
     * The merkle root is the expensive part -- it hashes every transaction in the block -- and it
     * does not depend on the nonce, so a miner must build this ONCE per candidate rather than
     * recomputing it for every attempt. See [com.prism.launcher.wallet.HeaderTemplate] for the
     * buffer-reuse contract.
     */
    fun headerTemplate(): com.prism.launcher.wallet.HeaderTemplate {
        val prefix = ByteArrayOutputStream().apply {
            write(PrismCoinConsensus.MAGIC)
            write(intToBytes(version))
            write(previousHash.toByteArray(Charsets.US_ASCII))
            write(longToBytes(height))
            write(longToBytes(timestamp))
            write(WalletCrypto.toFixedBytes(target, 32))
        }.toByteArray()

        val suffix = ByteArrayOutputStream().apply {
            write(miner.toByteArray(Charsets.UTF_8))
            write(merkleRoot())
        }.toByteArray()

        return com.prism.launcher.wallet.HeaderTemplate.of(
            prefix, suffix, nonceSize = 8, littleEndian = false
        )
    }

    fun hash(): ByteArray = WalletCrypto.sha256d(headerBytes())

    fun id(): String = WalletCrypto.toHex(hash())

    /** Proof of work, read as a big-endian integer against the target. */
    fun meetsTarget(): Boolean = BigInteger(1, hash()) <= target

    fun reward(): BigInteger = PrismCoinConsensus.blockReward(target)

    private fun intToBytes(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun longToBytes(v: Long) = ByteArray(8) { ((v ushr (8 * (7 - it))) and 0xff).toByte() }

    companion object {
        /**
         * The genesis block. Every node must produce this byte-for-byte or they are on different
         * chains, so every field is a fixed constant -- including the timestamp.
         */
        fun genesis(): PscBlock = PscBlock(
            version = 1,
            previousHash = "0".repeat(64),
            height = 0,
            // 2026-01-01T00:00:00Z, fixed. A generated timestamp here would give every device its
            // own genesis and therefore its own chain.
            timestamp = 1767225600L,
            target = PrismCoinConsensus.GENESIS_TARGET,
            nonce = 0,
            miner = "PrismCoin genesis: one PSC is one hour of work",
            transactions = emptyList(),
        )
    }
}
