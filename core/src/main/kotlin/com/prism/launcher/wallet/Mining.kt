package com.prism.launcher.wallet

import org.bouncycastle.crypto.generators.SCrypt
import java.math.BigInteger

/**
 * Proof-of-work hashing, and the arithmetic that decides whether a hash is a share.
 *
 * ## What can honestly be mined on a phone
 *
 * Two of the three algorithms below are implementable in portable code and are implemented here.
 * The third, and the only one that would actually earn anything, is not:
 *
 *   SHA-256d (Bitcoin, Bitcoin Cash) -- works, and is pointless. A phone reaches a few MH/s
 *   against a network measured in hundreds of EH/s: something like 10^14 times the hash rate. The
 *   expected wait for a single share at a pool's lowest difficulty runs to years, and for a block,
 *   longer than the universe has existed. It is implemented because it was asked for and it does
 *   genuinely mine; the numbers are stated so nobody mistakes a share counter that never moves for
 *   a bug.
 *
 *   SCRYPT (Litecoin, Dogecoin) -- works, and is merely very bad rather than absurd. Scrypt is
 *   memory-hard, which narrows the ASIC advantage from ~10^8 to ~10^5. Still no realistic income.
 *
 *   RANDOMX (Monero and its forks) -- IMPLEMENTED NATIVELY, and it is the one that matters.
 *   RandomX is deliberately CPU-friendly and phones are within a few multiples of a desktop core
 *   on it, which is exactly why it is the algorithm people actually mine on Android. It cannot be
 *   done in JVM code -- it is a virtual machine that JIT-compiles random programs -- so it is
 *   compiled from vendored C++ into the APK for every ABI and reached through [randomX].
 *   [supportsRandomX] reports whether that library actually loaded on this device rather than
 *   assuming it did.
 *
 * ## Heat, battery and hardware
 *
 * Sustained full-core hashing on a phone means thermal throttling within minutes, a hot device,
 * and battery wear from charging at maximum load. That is not a policy objection, it is what the
 * hardware does.
 */
object MiningAlgorithms {

    const val SHA256D = "sha256d"
    const val SCRYPT = "scrypt"
    const val RANDOMX = "randomx"

    /**
     * The native RandomX implementation, installed by the platform at startup.
     *
     * An injected interface rather than a direct call, because :core has no JNI and no Android:
     * the library is built and loaded on the platform side, and :core only needs to know whether
     * something answered.
     */
    @Volatile
    var randomX: RandomXProvider? = null

    /**
     * Algorithms this build can execute right now.
     *
     * Computed rather than constant: RandomX is present only if its native library loaded, which
     * is a property of the device and not of the build.
     */
    val supported: List<String>
        get() = buildList {
            add(SHA256D)
            add(SCRYPT)
            if (supportsRandomX()) add(RANDOMX)
        }

    /** Whether the native RandomX library loaded on this device. */
    fun supportsRandomX(): Boolean = randomX?.isAvailable() == true

    fun isSupported(algorithm: String): Boolean = algorithm.lowercase() in supported

    /**
     * @param seedKey RandomX's key. Ignored by the other algorithms, which are unkeyed.
     */
    fun hash(algorithm: String, header: ByteArray, seedKey: ByteArray = ByteArray(0)): ByteArray =
        when (algorithm.lowercase()) {
            SHA256D -> WalletCrypto.sha256d(header)
            // Litecoin's parameters: N=1024, r=1, p=1, 32-byte output, salt equal to the input.
            SCRYPT -> SCrypt.generate(header, header, 1024, 1, 1, 32)
            RANDOMX -> {
                val provider = randomX
                    ?: throw IllegalArgumentException("RandomX is not available on this device")
                provider.hash(seedKey, header)
                    ?: throw IllegalStateException("RandomX could not hash on this device")
            }
            else -> throw IllegalArgumentException("$algorithm cannot be mined by this build")
        }

    /**
     * Relative cost of one hash, for turning a hash count into a comparable rate.
     *
     * Scrypt at N=1024 is thousands of times more work than a double SHA-256, so reporting both as
     * "hashes per second" without saying which algorithm produced the number is meaningless.
     */
    fun relativeCost(algorithm: String): Long = when (algorithm.lowercase()) {
        SHA256D -> 1
        SCRYPT -> 1000
        // A RandomX hash runs a whole generated program against a 2 MB scratchpad. Comparing its
        // rate to a SHA-256d rate without saying so is meaningless -- H/s here is a different unit.
        RANDOMX -> 1_000_000
        else -> 1
    }
}

/**
 * A native RandomX implementation, supplied by the platform.
 *
 * Exists so :core can express "hash with RandomX" without owning any JNI. The Android side builds
 * the library into the APK for every ABI and installs itself here at startup.
 */
interface RandomXProvider {
    fun isAvailable(): Boolean

    /** 32 bytes, or null when RandomX is unavailable or a VM could not be created. */
    fun hash(seedKey: ByteArray, input: ByteArray): ByteArray?
}

/**
 * Stratum's share arithmetic.
 *
 * A pool hands out work at a difficulty far below the network's, so a miner produces frequent
 * "shares" proving it is working. The comparison is on the hash read as a LITTLE-ENDIAN integer,
 * which is the single most common mistake in a from-scratch miner: read big-endian, every hash
 * looks astronomically large and no share is ever submitted, and the miner appears to run
 * perfectly while finding nothing.
 */
object ShareTarget {

    /** Difficulty 1 for Bitcoin-style pools: 0x00000000FFFF * 2^208. */
    val DIFF1_TARGET: BigInteger = BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16)

    fun targetFor(difficulty: Double): BigInteger {
        if (difficulty <= 0) return DIFF1_TARGET
        // Scaled integer division: difficulty is fractional, and BigInteger is not.
        val scaled = BigInteger.valueOf((difficulty * 65536.0).toLong().coerceAtLeast(1))
        return DIFF1_TARGET.multiply(BigInteger.valueOf(65536)).divide(scaled)
    }

    /** Interprets a 32-byte hash the way a pool does, little-endian. */
    fun hashToInteger(hash: ByteArray): BigInteger = BigInteger(1, hash.reversedArray())

    fun meetsTarget(hash: ByteArray, target: BigInteger): Boolean =
        hashToInteger(hash) <= target

    /** How many difficulty-1 shares a hash is worth, for reporting. */
    fun shareDifficulty(hash: ByteArray): Double {
        val value = hashToInteger(hash)
        if (value.signum() == 0) return Double.MAX_VALUE
        return BigInteger.ONE.shiftLeft(256).divide(value).toDouble() /
            BigInteger.ONE.shiftLeft(256).divide(DIFF1_TARGET).toDouble()
    }
}

/**
 * A block header with everything except the nonce already assembled.
 *
 * ## Why this exists
 *
 * Only the nonce changes between attempts. Rebuilding the whole header each time -- re-parsing hex,
 * re-folding the merkle tree, allocating a stream -- was costing far more than the hash it was
 * feeding, on both the PrismCoin and Stratum paths. Everything constant is computed once, and each
 * attempt writes eight (or four) bytes into a buffer that already exists.
 *
 * ## Two sharp edges, both deliberate
 *
 * [withNonce] RETURNS THE INTERNAL BUFFER rather than a copy, because copying it per attempt is
 * the allocation this class exists to remove. The result is valid only until the next call, so
 * callers must hash it immediately and never retain it.
 *
 * ONE TEMPLATE PER THREAD. It is mutable and holds no lock; two mining threads sharing one would
 * overwrite each other's nonces and produce hashes for headers neither of them submitted.
 */
class HeaderTemplate(
    private val buffer: ByteArray,
    private val nonceOffset: Int,
    private val nonceSize: Int,
    private val littleEndian: Boolean,
) {
    val size: Int get() = buffer.size

    /**
     * Where the nonce field begins.
     *
     * Exposed so a hasher can work out which leading 64-byte blocks the nonce cannot reach and
     * cache their SHA-256 state across attempts. See [FastSha256d].
     */
    val nonceStart: Int get() = nonceOffset

    fun withNonce(nonce: Long): ByteArray {
        if (littleEndian) {
            for (i in 0 until nonceSize) {
                buffer[nonceOffset + i] = ((nonce ushr (8 * i)) and 0xff).toByte()
            }
        } else {
            for (i in 0 until nonceSize) {
                buffer[nonceOffset + i] = ((nonce ushr (8 * (nonceSize - 1 - i))) and 0xff).toByte()
            }
        }
        return buffer
    }

    /** A private copy, for a caller that needs to keep the bytes. */
    fun snapshot(nonce: Long): ByteArray = withNonce(nonce).copyOf()

    companion object {
        /** Builds from the bytes before and after the nonce field. */
        fun of(
            prefix: ByteArray,
            suffix: ByteArray,
            nonceSize: Int,
            littleEndian: Boolean,
        ): HeaderTemplate {
            val buffer = ByteArray(prefix.size + nonceSize + suffix.size)
            System.arraycopy(prefix, 0, buffer, 0, prefix.size)
            System.arraycopy(suffix, 0, buffer, prefix.size + nonceSize, suffix.size)
            return HeaderTemplate(buffer, prefix.size, nonceSize, littleEndian)
        }
    }
}

/**
 * An 80-byte block header, which is what proof-of-work actually hashes.
 *
 * Every field is little-endian on the wire while being displayed big-endian everywhere else, which
 * is why the hashes are reversed on the way in and the resulting id is reversed on the way out.
 */
data class BlockHeader(
    val version: Int,
    val previousHashBigEndian: String,
    val merkleRootBigEndian: String,
    val timestamp: Long,
    val bits: Long,
    val nonce: Long,
) {
    fun serialize(): ByteArray {
        val out = java.io.ByteArrayOutputStream(80)
        out.write(le32(version))
        out.write(WalletCrypto.fromHex(previousHashBigEndian).reversedArray())
        out.write(WalletCrypto.fromHex(merkleRootBigEndian).reversedArray())
        out.write(le32(timestamp.toInt()))
        out.write(le32(bits.toInt()))
        out.write(le32(nonce.toInt()))
        return out.toByteArray()
    }

    /** The block id as everyone writes it: the hash, reversed. */
    fun blockId(): String = WalletCrypto.toHex(WalletCrypto.sha256d(serialize()).reversedArray())

    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
}
