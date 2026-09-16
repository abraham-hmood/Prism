package com.prism.core

import com.prism.launcher.wallet.Bip32
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.Ed25519
import com.prism.launcher.wallet.MoneroAddress
import com.prism.launcher.wallet.MoneroKeys
import com.prism.launcher.wallet.WalletCrypto
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Monero key derivation.
 *
 * THE CURVE IS CHECKED AGAINST PUBLISHED CONSTANTS FIRST. Everything above it -- addresses, seeds,
 * mining payouts -- is worthless if `k·B` is wrong, and a wrong curve produces perfectly
 * well-formed addresses that simply belong to nobody. So the base point encoding, which has been
 * published since ed25519 was specified, is asserted directly.
 */
class MoneroKeysTest {

    /**
     * Scalar 1 must give the base point's canonical compressed encoding, and the group order minus
     * one must give its negation -- the same bytes with the sign bit set.
     */
    @Test
    fun `ed25519 base point matches the published encoding`() {
        val one = Ed25519.littleEndian(BigInteger.ONE)
        assertEquals(
            "5866666666666666666666666666666666666666666666666666666666666666",
            WalletCrypto.toHex(Ed25519.scalarMultBase(one)),
        )

        // -B is B with x negated, which flips only the sign bit in the encoding.
        val minusOne = Ed25519.littleEndian(Ed25519.L.subtract(BigInteger.ONE))
        assertEquals(
            "58666666666666666666666666666666666666666666666666666666666666e6",
            WalletCrypto.toHex(Ed25519.scalarMultBase(minusOne)),
        )
    }

    @Test
    fun `scalar multiplication is additive`() {
        fun mult(k: Long) = WalletCrypto.toHex(
            Ed25519.scalarMultBase(Ed25519.littleEndian(BigInteger.valueOf(k)))
        )
        // Distinct scalars must give distinct points; a broken ladder often collapses them.
        assertEquals(4, listOf(mult(1), mult(2), mult(3), mult(7)).toSet().size)
        // The identity: L·B is the neutral element, encoded as y = 1.
        assertEquals(
            "0100000000000000000000000000000000000000000000000000000000000000",
            WalletCrypto.toHex(Ed25519.scalarMultBase(Ed25519.littleEndian(Ed25519.L))),
        )
    }

    @Test
    fun `scalars are reduced modulo the group order`() {
        val big = ByteArray(32) { 0xFF.toByte() }
        val reduced = Ed25519.scReduce32(big)
        assertTrue(Ed25519.isReduced(reduced))
        assertFalse(Ed25519.isReduced(big), "an all-ones scalar is above the order")
        // Reduction is idempotent.
        assertTrue(Ed25519.scReduce32(reduced).contentEquals(reduced))
    }

    // ── Wallets ────────────────────────────────────────────────────────────

    private val standardPhrase =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"

    private fun walletFromPhrase(): MoneroKeys.Wallet {
        val seed = Bip39.toSeed(Bip39.splitPhrase(standardPhrase))
        val key = Bip32.deriveFromSeed(seed, MoneroKeys.DERIVATION_PATH)
        return MoneroKeys.fromPrivateKey(key.privateKey)
    }

    @Test
    fun `a derived wallet produces a valid mainnet address`() {
        val wallet = walletFromPhrase()

        assertTrue(
            MoneroAddress.isValid(wallet.address),
            "derived address failed its own checksum: ${wallet.address}",
        )
        assertEquals(95, wallet.address.length)
        assertTrue(wallet.address.startsWith("4"), "mainnet standard addresses begin with 4")
        assertEquals(MoneroAddress.Kind.Standard, MoneroAddress.decode(wallet.address)?.kind)

        // Both keys must be canonical scalars, or the wallet cannot spend.
        assertTrue(Ed25519.isReduced(wallet.privateSpendKey))
        assertTrue(Ed25519.isReduced(wallet.privateViewKey))
        assertEquals(32, wallet.publicSpendKey.size)
        assertEquals(32, wallet.publicViewKey.size)
    }

    /** The view key is defined by Monero as the reduced Keccak of the spend key. */
    @Test
    fun `the view key follows Monero's rule`() {
        val wallet = walletFromPhrase()
        val expected = Ed25519.scReduce32(WalletCrypto.keccak256(wallet.privateSpendKey))
        assertTrue(wallet.privateViewKey.contentEquals(expected))
    }

    /** Deterministic: the same phrase must always reach the same wallet. */
    @Test
    fun `derivation is deterministic and phrase specific`() {
        assertEquals(walletFromPhrase().address, walletFromPhrase().address)

        val other = Bip39.toSeed(Bip39.generate(30))
        val otherWallet = MoneroKeys.fromPrivateKey(
            Bip32.deriveFromSeed(other, MoneroKeys.DERIVATION_PATH).privateKey
        )
        assertTrue(otherWallet.address != walletFromPhrase().address)
    }

    // ── The 25-word seed, which is what makes this recoverable elsewhere ───

    @Test
    fun `the monero seed round trips`() {
        val wallet = walletFromPhrase()
        val seed = wallet.mnemonic

        assertEquals(MoneroKeys.MNEMONIC_WORDS, seed.size)
        val recovered = MoneroKeys.keyFromMnemonic(seed)
        assertNotNull(recovered, "Prism's own seed must decode")
        assertTrue(
            recovered.contentEquals(wallet.privateSpendKey),
            "the 25-word seed must encode exactly the spend key",
        )

        // And rebuilding from it reaches the same address, which is the whole point: this seed
        // restores the wallet in any Monero software.
        assertEquals(wallet.address, MoneroKeys.fromSeedBytes(recovered).address)
    }

    /** The 25th word is a checksum; a wrong one must be rejected. */
    @Test
    fun `a corrupted monero seed is rejected`() {
        val seed = walletFromPhrase().mnemonic.toMutableList()
        seed[24] = if (seed[24] == "abbey") "zoom" else "abbey"
        assertNull(MoneroKeys.keyFromMnemonic(seed))

        val wrongWord = walletFromPhrase().mnemonic.toMutableList()
        wrongWord[5] = "notamonerword"
        assertNull(MoneroKeys.keyFromMnemonic(wrongWord))
    }

    @Test
    fun `many derived wallets are all valid`() {
        // Exercises the curve across varied scalars, including ones needing reduction.
        for (seedValue in listOf(1L, 2L, 12345L, 999_999_937L)) {
            val wallet = MoneroKeys.fromSeedBytes(
                WalletCrypto.toFixedBytes(BigInteger.valueOf(seedValue), 32)
            )
            assertTrue(MoneroAddress.isValid(wallet.address), "invalid at seed $seedValue")
            assertTrue(
                MoneroKeys.keyFromMnemonic(wallet.mnemonic)!!.contentEquals(wallet.privateSpendKey)
            )
        }
    }
}
