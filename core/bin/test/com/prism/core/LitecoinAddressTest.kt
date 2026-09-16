package com.prism.core

import com.prism.launcher.wallet.Bip32
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.CoinRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Litecoin's address, checked against an INDEPENDENT derivation.
 *
 * WHY THIS EXISTS SEPARATELY. Every other wallet test either compares against a published vector or
 * checks Prism against itself. Litecoin had neither: the code was trusted because it shares a path
 * with Bitcoin, whose BIP-84 vector does pass. That is a reasonable inference and not a proof, and
 * "reasonable inference" is not the standard for an address somebody sends real money to.
 *
 * The expected value below was produced by a separate implementation -- secp256k1, BIP-32 and
 * bech32 all written from scratch outside this codebase -- from the standard BIP-39 test phrase at
 * Litecoin's registered path m/84'/2'/0'/0/0. Two independent implementations agreeing is the
 * closest thing to a published vector available for this chain.
 */
class LitecoinAddressTest {

    private val standardPhrase =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"

    @Test
    fun `litecoin address matches an independent derivation`() {
        val seed = Bip39.toSeed(Bip39.splitPhrase(standardPhrase))
        val ltc = CoinRegistry.bySymbol("LTC")!!

        assertEquals("m/84'/2'/0'/0/0", ltc.derivationPath(), "Litecoin is SLIP-44 coin type 2")

        val key = Bip32.deriveFromSeed(seed, ltc.derivationPath())
        assertEquals(
            "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh",
            ltc.addressFor(key.privateKey),
        )
    }

    /** The prefix is what an exchange validates before it will send. */
    @Test
    fun `litecoin issues native segwit addresses`() {
        val seed = Bip39.toSeed(Bip39.generate(30))
        val ltc = CoinRegistry.bySymbol("LTC")!!
        val address = ltc.addressFor(Bip32.deriveFromSeed(seed, ltc.derivationPath()).privateKey)

        assertTrue(address.startsWith("ltc1q"), "expected a bech32 P2WPKH address, got $address")
        assertTrue(ltc.isValidAddress(address))
        // A Bitcoin address must not validate as Litecoin: same encoding, different chain.
        assertTrue(!ltc.isValidAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
    }
}
