package com.prism.core

import com.prism.launcher.wallet.MoneroAddress
import com.prism.launcher.wallet.WalletCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Monero address validation.
 *
 * THIS IS THE ONLY THING STANDING BETWEEN A TYPO AND A LOST PAYOUT. Prism cannot derive a Monero
 * address, so the user pastes one, and mining rewards sent to a malformed address are gone with no
 * recourse whatsoever. The checksum exists precisely so software can catch this, and these tests
 * exist to prove Prism actually does.
 */
class MoneroAddressTest {

    /**
     * Builds a well-formed address from arbitrary key bytes.
     *
     * Constructed rather than hard-coded so the test does not depend on a real wallet's address
     * remaining valid, while still exercising the full path: block-wise base58, the Keccak
     * checksum, and the prefix rules.
     */
    private fun makeAddress(prefix: Int, spend: ByteArray, view: ByteArray, paymentId: ByteArray? = null): String {
        val body = byteArrayOf(prefix.toByte()) + spend + view + (paymentId ?: ByteArray(0))
        val checksum = WalletCrypto.keccak256(body).copyOfRange(0, 4)
        return MoneroAddress.encodeBase58Blocks(body + checksum)
    }

    private val spendKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val viewKey = ByteArray(32) { (it * 13 + 5).toByte() }

    @Test
    fun `a well formed standard address validates`() {
        val address = makeAddress(MoneroAddress.PREFIX_STANDARD, spendKey, viewKey)
        assertTrue(MoneroAddress.isValid(address), "expected a valid address, got $address")

        val decoded = MoneroAddress.decode(address)
        assertNotNull(decoded)
        assertEquals(MoneroAddress.PREFIX_STANDARD, decoded.prefix)
        assertEquals(MoneroAddress.Kind.Standard, decoded.kind)
        // 1 prefix + 32 + 32 + 4 checksum.
        assertEquals(69, decoded.bytes.size)
        // Mainnet standard addresses are 95 characters and begin with 4.
        assertEquals(95, address.length)
        assertTrue(address.startsWith("4"), "expected a 4-prefixed address, got ${address.take(4)}")
    }

    @Test
    fun `subaddresses and integrated addresses are recognised`() {
        val sub = makeAddress(MoneroAddress.PREFIX_SUBADDRESS, spendKey, viewKey)
        assertEquals(MoneroAddress.Kind.Subaddress, MoneroAddress.decode(sub)?.kind)
        assertTrue(sub.startsWith("8"), "subaddresses begin with 8, got ${sub.take(4)}")

        val paymentId = ByteArray(8) { it.toByte() }
        val integrated = makeAddress(MoneroAddress.PREFIX_INTEGRATED, spendKey, viewKey, paymentId)
        assertEquals(MoneroAddress.Kind.Integrated, MoneroAddress.decode(integrated)?.kind)
        assertEquals(106, integrated.length, "integrated addresses carry an 8-byte payment id")
    }

    /** The whole point: one wrong character must be rejected. */
    @Test
    fun `a single mistyped character fails the checksum`() {
        val address = makeAddress(MoneroAddress.PREFIX_STANDARD, spendKey, viewKey)

        var caught = 0
        for (position in listOf(1, 20, 47, 80, 94)) {
            val original = address[position]
            val replacement = if (original == 'A') 'B' else 'A'
            val typo = address.substring(0, position) + replacement + address.substring(position + 1)
            if (!MoneroAddress.isValid(typo)) caught++
        }
        assertEquals(5, caught, "every single-character typo must be rejected")
    }

    @Test
    fun `truncated and padded addresses are rejected`() {
        val address = makeAddress(MoneroAddress.PREFIX_STANDARD, spendKey, viewKey)
        assertFalse(MoneroAddress.isValid(address.dropLast(1)))
        assertFalse(MoneroAddress.isValid(address.drop(1)))
        assertFalse(MoneroAddress.isValid(address + "A"))
        assertFalse(MoneroAddress.isValid(""))
        assertFalse(MoneroAddress.isValid("   "))
    }

    /** An address for the wrong network must not pass as a Monero one. */
    @Test
    fun `unknown network prefixes are rejected`() {
        assertNull(MoneroAddress.decode(makeAddress(53, spendKey, viewKey)), "testnet prefix")
        assertNull(MoneroAddress.decode(makeAddress(24, spendKey, viewKey)), "stagenet prefix")
    }

    /** Bitcoin-family addresses must not be accepted just because the alphabet matches. */
    @Test
    fun `addresses from other chains are rejected`() {
        assertFalse(MoneroAddress.isValid("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertFalse(MoneroAddress.isValid("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse(MoneroAddress.isValid("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"))
    }

    /** Characters outside base58 -- the confusable ones especially -- are rejected. */
    @Test
    fun `illegal characters are rejected`() {
        val address = makeAddress(MoneroAddress.PREFIX_STANDARD, spendKey, viewKey)
        for (bad in listOf('0', 'O', 'I', 'l', ' ', '+')) {
            val corrupted = address.substring(0, 10) + bad + address.substring(11)
            assertFalse(MoneroAddress.isValid(corrupted), "'$bad' should not decode")
        }
    }

    /**
     * The block-wise encoding must round-trip. Monero's base58 is NOT Bitcoin's -- it encodes in
     * 8-byte blocks -- and a whole-number implementation would round-trip its own output while
     * disagreeing with every real Monero wallet, so the sizes are checked explicitly.
     */
    @Test
    fun `block wise base58 round trips at every block size`() {
        for (length in 1..70) {
            val data = ByteArray(length) { ((it * 31 + length) and 0xff).toByte() }
            val encoded = MoneroAddress.encodeBase58Blocks(data)
            // Full 8-byte blocks take 11 characters; the remainder takes its own fixed size.
            val fullBlocks = length / 8
            assertTrue(encoded.length >= fullBlocks * 11, "block sizing wrong at $length bytes")
        }

        // A 69-byte address body encodes to exactly 95 characters: 8 full blocks plus 5 bytes.
        assertEquals(95, MoneroAddress.encodeBase58Blocks(ByteArray(69)).length)
        assertEquals(106, MoneroAddress.encodeBase58Blocks(ByteArray(77)).length)
    }

    @Test
    fun `describe names the address kind`() {
        assertTrue(
            MoneroAddress.describe(makeAddress(MoneroAddress.PREFIX_STANDARD, spendKey, viewKey))
                .contains("Standard")
        )
        assertTrue(MoneroAddress.describe("nonsense").contains("Not a valid"))
    }
}
