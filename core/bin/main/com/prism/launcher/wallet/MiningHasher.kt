package com.prism.launcher.wallet

import java.security.MessageDigest

/**
 * The double SHA-256 and target test at the centre of the mining loop, without the per-attempt
 * allocations the old path could not avoid.
 *
 * ## What was actually slow, measured rather than assumed
 *
 * The miner was managing a few hundred kH/s per device against an issuance peg whose reference
 * rate is 2 MH/s, so a device was minting well under the one PSC an hour that one reference-hour
 * of work is supposed to buy. The obvious suspect was SHA-256 itself, and the obvious fix -- a
 * hand-written compression function keeping a midstate across the constant head of the header --
 * was written, tested for correctness, and benchmarked against what already shipped:
 *
 *     sha256d + BigInteger (what shipped)     1,985,000 H/s
 *     sha256d + byte compare                  2,192,000 H/s   1.10x
 *     reused MessageDigest + byte compare     2,516,000 H/s   1.27x
 *     hand-written SHA-256 with midstate        551,000 H/s   0.28x
 *
 * The hand-written version was nearly four times SLOWER, and it was slower for a reason that
 * applies just as much on the phone as on the bench: both the JVM and Android's Conscrypt lower
 * SHA-256 onto the CPU's dedicated instructions, so the platform digest is already doing in
 * hardware what any Kotlin loop has to do by hand. Beating it in Kotlin is not possible, and the
 * midstate saves one compression out of four while giving up all of that.
 *
 * So this class does not implement SHA-256. It keeps the platform's implementation and removes
 * everything around it: the two arrays each double hash allocated, and the BigInteger the target
 * comparison allocated. That is the 1.27x above. The rest of the gap to 2 MH/s is not in the hash
 * at all -- it is that only one core was ever hashing. See PrismCoinNode.mineOnce.
 *
 * ## Contract
 *
 * NOT THREAD-SAFE, deliberately: the reused buffers are the entire point, and MessageDigest itself
 * is not thread-safe either. Each mining thread constructs its own, exactly as each holds its own
 * [HeaderTemplate].
 */
class MiningHasher {

    private val md = MessageDigest.getInstance("SHA-256")

    /** The inner digest, fed straight back in for the outer one. */
    private val inner = ByteArray(32)

    /** The result of the most recent [hash], big-endian. Overwritten by the next call. */
    val digest = ByteArray(32)

    /**
     * Hashes [message] into [digest].
     *
     * `digest(buf, offset, len)` rather than the `digest()` that returns a fresh array: the return
     * value is what allocates, and at millions of attempts a minute two 32-byte arrays per hash is
     * a garbage collector running flat out for no reason. `digest` also resets the instance, so
     * consecutive calls need no explicit reset between them.
     */
    fun hash(message: ByteArray) {
        md.update(message, 0, message.size)
        md.digest(inner, 0, 32)
        md.update(inner, 0, 32)
        md.digest(digest, 0, 32)
    }

    /**
     * Is [digest] numerically at or below [target]?
     *
     * Both are unsigned 32-byte big-endian, so a scan from the top decides it -- and decides it on
     * the first byte almost every time, since a hash that misses usually misses immediately. This
     * is the BigInteger comparison the loop used to allocate, minus the allocation.
     *
     * The masks are load-bearing. A Kotlin `Byte` is signed, so 0xFF compares as -1, and dropping
     * them would rank the largest possible target as the smallest and reject every valid block.
     *
     * Returns true on equality, because consensus accepts `hash <= target`, not `hash < target`.
     */
    fun digestAtMost(target: ByteArray): Boolean {
        for (i in 0 until 32) {
            val a = digest[i].toInt() and 0xff
            val b = target[i].toInt() and 0xff
            if (a != b) return a < b
        }
        return true
    }
}
