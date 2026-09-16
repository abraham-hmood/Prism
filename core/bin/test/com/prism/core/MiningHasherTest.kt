package com.prism.core

import com.prism.launcher.wallet.HeaderTemplate
import com.prism.launcher.wallet.MiningHasher
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.psc.PscBlock
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The miner's hash must be bit-identical to the one consensus uses.
 *
 * THIS IS THE TEST THAT MATTERS FOR [MiningHasher]. It is an optimisation, and the failure mode of
 * a wrong optimisation here is not a slow miner but an invalid one: every peer validates a
 * submitted block with [WalletCrypto.sha256d], so a hasher disagreeing by a single bit would burn
 * the device's battery producing blocks the whole mesh rejects, and the only symptom would be that
 * nothing ever confirmed.
 */
class MiningHasherTest {

    @Test
    fun `matches the reference hash at every message length`() {
        val rng = Random(20260818)
        val hasher = MiningHasher()
        // Lengths either side of every 64-byte block boundary, where SHA-256 padding spills into an
        // extra block -- the classic place a digest wrapper goes wrong.
        for (length in 1..200) {
            val message = ByteArray(length).also { rng.nextBytes(it) }
            hasher.hash(message)
            assertContentEquals(
                WalletCrypto.sha256d(message), hasher.digest,
                "length $length disagrees with WalletCrypto.sha256d",
            )
        }
    }

    @Test
    fun `one hasher stays correct across a nonce sweep`() {
        // The real usage: one long-lived hasher, reused for millions of attempts. If digest() ever
        // failed to reset the instance, this diverges on the second call.
        val template = PscBlock.genesis().headerTemplate()
        val hasher = MiningHasher()
        for (nonce in listOf(0L, 1L, 2L, 255L, 256L, 65_535L, 1_000_003L, 0xFFFFFFFEL)) {
            hasher.hash(template.withNonce(nonce))
            assertContentEquals(
                WalletCrypto.sha256d(template.snapshot(nonce)), hasher.digest,
                "nonce $nonce disagrees",
            )
        }
    }

    @Test
    fun `target comparison agrees with the arbitrary-precision one`() {
        val rng = Random(99)
        val hasher = MiningHasher()
        for (trial in 0 until 500) {
            val message = ByteArray(80).also { rng.nextBytes(it) }
            hasher.hash(message)
            // A target drawn from a hash keeps the comparison near the boundary, where an
            // off-by-one or sign-extension bug actually shows up. Random 32-byte targets would
            // almost always be decided by the first byte.
            val target = WalletCrypto.sha256d(ByteArray(8).also { rng.nextBytes(it) })
            val expected = BigInteger(1, hasher.digest) <= BigInteger(1, target)
            assertEquals(
                expected, hasher.digestAtMost(target),
                "trial $trial: byte compare disagrees with BigInteger",
            )
        }
    }

    @Test
    fun `equal values count as meeting the target`() {
        val hasher = MiningHasher()
        hasher.hash("prism".toByteArray())
        // Consensus accepts hash <= target. A scan that returned early on the last byte would
        // reject a block that landed exactly on the target.
        assertTrue(hasher.digestAtMost(hasher.digest.copyOf()))
        assertFalse(hasher.digestAtMost(hasher.digest.copyOf().also { it[31] = (it[31] - 1).toByte() }))
    }

    @Test
    fun `high bit in a target byte is treated as unsigned`() {
        // 0xFF is -1 as a signed Byte. Forgetting the mask ranks the largest possible target as the
        // smallest, and the miner rejects every valid block.
        val hasher = MiningHasher()
        hasher.hash(ByteArray(64))
        assertTrue(hasher.digestAtMost(ByteArray(32) { 0xFF.toByte() }))
        assertFalse(hasher.digestAtMost(ByteArray(32)))
    }

    @Test
    fun `header template reports where the nonce starts`() {
        val template = HeaderTemplate.of(
            prefix = ByteArray(70) { 1 },
            suffix = ByteArray(30) { 2 },
            nonceSize = 8,
            littleEndian = false,
        )
        assertEquals(70, template.nonceStart)
        assertEquals(108, template.size)
    }
}
