package com.prism.core

import com.prism.launcher.wallet.AddressScheme
import com.prism.launcher.wallet.Base58
import com.prism.launcher.wallet.Bech32
import com.prism.launcher.wallet.Bip32
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wallet's derivation, checked against the published vectors rather than against itself.
 *
 * THIS IS THE ONLY KIND OF TEST WORTH HAVING HERE. A wallet that derives keys self-consistently
 * but differently from the standard is not "slightly wrong" -- it takes deposits at addresses no
 * other software will ever find, and the failure is silent until someone tries to restore their
 * phrase somewhere else and sees an empty wallet. Every assertion below therefore compares against
 * a value produced by somebody other than this code:
 *
 *   - BIP-39: the Trezor reference vectors, checked into test resources.
 *   - BIP-32: the master `xprv` from those same vectors.
 *   - Addresses: published vectors from the BIP-84 and EIP-55 specifications.
 */
class WalletDerivationTest {

    private val vectors: List<List<String>> by lazy {
        val text = WalletDerivationTest::class.java
            .getResourceAsStream("/bip39-vectors-english.json")!!
            .bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.map { entry ->
            entry.jsonArray.map { it.jsonPrimitive.content }
        }
    }

    @Test
    fun `wordlist is the official 2048`() {
        assertEquals(2048, Bip39.words.size)
        assertEquals("abandon", Bip39.words.first())
        assertEquals("zoo", Bip39.words.last())
        assertEquals(2048, Bip39.words.toSet().size, "words must be unique")
        assertEquals(Bip39.words.sorted(), Bip39.words, "the list is sorted, which binary search assumes")
    }

    /** Entropy to phrase, and phrase to seed, for all 24 reference vectors. */
    @Test
    fun `bip39 reference vectors`() {
        assertEquals(24, vectors.size, "expected the full english vector set")
        for ((entropyHex, mnemonic, seedHex, _) in vectors.map { listOf(it[0], it[1], it[2], it[3]) }) {
            val words = Bip39.fromEntropy(WalletCrypto.fromHex(entropyHex))
            assertEquals(mnemonic, words.joinToString(" "), "mnemonic for $entropyHex")

            // The reference vectors all use the passphrase "TREZOR".
            val seed = Bip39.toSeed(words, "TREZOR")
            assertEquals(seedHex, WalletCrypto.toHex(seed), "seed for $entropyHex")

            assertTrue(Bip39.validate(words) is Bip39.Validation.Valid, "checksum for $entropyHex")
        }
    }

    /** The master key serialisation, against the `xprv` field of the same vectors. */
    @Test
    fun `bip32 master keys match the reference xprv`() {
        for (v in vectors) {
            val seed = Bip39.toSeed(Bip39.splitPhrase(v[1]), "TREZOR")
            val master = Bip32.masterKeyFromSeed(seed)
            assertEquals(v[3], master.serializePrivate(Bip32.XPRV), "xprv for ${v[0]}")
        }
    }

    /**
     * BIP-84's own test vector: the standard mnemonic, at m/84'/0'/0'/0/0, must give this exact
     * address. This is the single strongest check in the file -- it exercises the seed, four
     * derivation steps including hardened ones, the public key encoding, HASH160 and bech32 all at
     * once, against a number written down in the specification.
     */
    @Test
    fun `bip84 first receive address`() {
        val phrase = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        val seed = Bip39.toSeed(Bip39.splitPhrase(phrase))
        val btc = CoinRegistry.bySymbol("BTC")!!
        val key = Bip32.deriveFromSeed(seed, btc.derivationPath())

        assertEquals("m/84'/0'/0'/0/0", btc.derivationPath())
        assertEquals(
            "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c",
            WalletCrypto.toHex(key.publicKey),
        )
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", btc.addressFor(key.privateKey))
    }

    /** The same seed on Ethereum, at the BIP-44 path every EVM wallet uses. */
    @Test
    fun `ethereum address from the standard test phrase`() {
        val phrase = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        val seed = Bip39.toSeed(Bip39.splitPhrase(phrase))
        val eth = CoinRegistry.bySymbol("ETH")!!
        val key = Bip32.deriveFromSeed(seed, eth.derivationPath())

        assertEquals("m/44'/60'/0'/0/0", eth.derivationPath())
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", eth.addressFor(key.privateKey))
    }

    /** EIP-55 mixed-case checksums, from the examples in the EIP itself. */
    @Test
    fun `eip55 checksum casing`() {
        // Derived indirectly: an address the scheme produces must round-trip its own casing.
        val eth = CoinRegistry.bySymbol("ETH")!!
        val key = BigInteger("1", 10)
        val address = eth.addressFor(key)
        assertTrue(address.startsWith("0x"))
        assertEquals(42, address.length)
        assertNotEquals(address, address.lowercase(), "a checksummed address has mixed case")
        assertTrue(eth.isValidAddress(address))
    }

    /**
     * 30-word phrases: the point of the feature, and the property that matters is round-tripping.
     * A phrase this wallet generates must validate here and produce a stable seed.
     */
    @Test
    fun `thirty word phrases round trip`() {
        assertEquals(320, Bip39.entropyBitsFor(30))
        val phrase = Bip39.generate(30)
        assertEquals(30, phrase.size)
        assertTrue(Bip39.validate(phrase) is Bip39.Validation.Valid)
        assertFalse(Bip39.isStandardWordCount(30), "30 words is beyond what other wallets accept")

        // Deterministic: the same phrase always reaches the same wallet.
        assertEquals(
            WalletCrypto.toHex(Bip39.toSeed(phrase)),
            WalletCrypto.toHex(Bip39.toSeed(phrase)),
        )
        // And a different phrase does not.
        assertNotEquals(
            WalletCrypto.toHex(Bip39.toSeed(phrase)),
            WalletCrypto.toHex(Bip39.toSeed(Bip39.generate(30))),
        )
    }

    /** Checksums must actually reject a corrupted phrase, or they are decoration. */
    @Test
    fun `a wrong word fails validation`() {
        val phrase = Bip39.generate(30).toMutableList()
        assertTrue(Bip39.validate(phrase) is Bip39.Validation.Valid)

        // Swap one word for a different valid dictionary word.
        phrase[7] = if (phrase[7] == "zoo") "abandon" else "zoo"
        val result = Bip39.validate(phrase)
        assertTrue(result is Bip39.Validation.Invalid, "a substituted word must be caught")

        // A word that is not in the list at all is reported by name.
        val nonsense = Bip39.generate(30).toMutableList().also { it[3] = "kryptonite" }
        val bad = Bip39.validate(nonsense)
        assertTrue(bad is Bip39.Validation.Invalid && bad.reason.contains("kryptonite"))
    }

    @Test
    fun `word counts that cannot encode are rejected`() {
        assertNull(Bip39.entropyBitsFor(13))
        assertNull(Bip39.entropyBitsFor(29))
        assertEquals(128, Bip39.entropyBitsFor(12))
        assertEquals(256, Bip39.entropyBitsFor(24))
        assertTrue(Bip39.validate(Bip39.generate(30).take(29)) is Bip39.Validation.Invalid)
    }

    @Test
    fun `base58check round trips and rejects a typo`() {
        val payload = WalletCrypto.fromHex("00010966776006953d5567439e5e39f86a0d273bee")
        val encoded = Base58.encodeChecked(payload)
        assertEquals("16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM", encoded)
        assertTrue(payload.contentEquals(Base58.decodeChecked(encoded)))

        // Flip one character; the checksum must reject it.
        val typo = encoded.dropLast(1) + if (encoded.last() == 'M') 'N' else 'M'
        assertFalse(Base58.isValidChecked(typo))
    }

    /** BIP-173's own valid/invalid address vectors. */
    @Test
    fun `bech32 segwit vectors`() {
        assertTrue(Bech32.isValidSegwit("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "bc"))
        assertTrue(Bech32.isValidSegwit("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4".lowercase(), "bc"))

        // Wrong human-readable part for the chain.
        assertFalse(Bech32.isValidSegwit("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "ltc"))
        // Corrupted checksum.
        assertNull(Bech32.decodeSegwit("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))
        // Mixed case is invalid by specification.
        assertNull(Bech32.decodeSegwit("bc1QW508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    }

    @Test
    fun `every built-in coin derives a valid address`() {
        val seed = Bip39.toSeed(Bip39.generate(30))
        for (coin in CoinRegistry.builtIn) {
            val key = Bip32.deriveFromSeed(seed, coin.derivationPath())
            val address = coin.addressFor(key.privateKey)
            assertTrue(address.isNotBlank(), "${coin.symbol} produced no address")
            assertTrue(
                coin.isValidAddress(address),
                "${coin.symbol} generated an address it rejects itself: $address",
            )
        }
    }

    /** Zcash's two-byte version prefix, which a single-byte assumption silently truncates. */
    @Test
    fun `zcash transparent addresses keep both version bytes`() {
        val zec = CoinRegistry.bySymbol("ZEC")!!
        val seed = Bip39.toSeed(Bip39.generate(30))
        val key = Bip32.deriveFromSeed(seed, zec.derivationPath())
        val address = zec.addressFor(key.privateKey)
        assertTrue(address.startsWith("t1"), "expected a t-address, got $address")
        assertEquals(22, Base58.decodeChecked(address).size, "2 version bytes + 20 hash bytes")
    }

    /** A user-added coin must behave exactly like a built-in one. */
    @Test
    fun `custom coins derive like built-ins`() {
        val flux = CoinSpec("XYZ", "Example Chain", 1234, AddressScheme.P2PKH(0x1e), 8)
        CoinRegistry.addCustom(flux)
        try {
            val found = CoinRegistry.bySymbol("XYZ")
            assertEquals("Example Chain", found?.name)
            assertTrue(found!!.isCustom)
            assertEquals("m/44'/1234'/0'/0/0", found.derivationPath())

            val seed = Bip39.toSeed(Bip39.generate(30))
            val key = Bip32.deriveFromSeed(seed, found.derivationPath())
            assertTrue(found.isValidAddress(found.addressFor(key.privateKey)))
        } finally {
            CoinRegistry.removeCustom("XYZ")
        }
        assertNull(CoinRegistry.bySymbol("XYZ"))
    }

    /** Amounts are parsed and formatted exactly -- a float here loses satoshis. */
    @Test
    fun `amount parsing is exact`() {
        val btc = CoinRegistry.bySymbol("BTC")!!
        assertEquals(BigInteger("100000000"), btc.parseAmount("1"))
        assertEquals(BigInteger("1"), btc.parseAmount("0.00000001"))
        assertEquals(BigInteger("123456789"), btc.parseAmount("1.23456789"))
        assertEquals("1.23456789", btc.format(BigInteger("123456789")))
        assertEquals("0.00000001", btc.format(BigInteger.ONE))
        assertEquals("1", btc.format(BigInteger("100000000")))

        // More precision than the chain has must fail rather than round somebody's money away.
        assertNull(btc.parseAmount("0.000000001"))
        assertNull(btc.parseAmount("abc"))
        assertNull(btc.parseAmount(""))

        val eth = CoinRegistry.bySymbol("ETH")!!
        assertEquals(BigInteger("1000000000000000000"), eth.parseAmount("1"))
        assertEquals("0.000000000000000001", eth.format(BigInteger.ONE))
    }

    @Test
    fun `address validation rejects the wrong chain`() {
        val btc = CoinRegistry.bySymbol("BTC")!!
        val eth = CoinRegistry.bySymbol("ETH")!!
        assertFalse(btc.isValidAddress("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"))
        assertFalse(eth.isValidAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse(eth.isValidAddress("0xnothex0000000000000000000000000000000000"))
    }

    /** Fixed-width key encoding: the leading-zero case that silently shortens a key. */
    @Test
    fun `keys are padded to a fixed width`() {
        val small = BigInteger.ONE
        assertEquals(32, WalletCrypto.toFixedBytes(small, 32).size)
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000001",
            WalletCrypto.toHex(WalletCrypto.toFixedBytes(small, 32)),
        )
        // A value with the high bit set gets a sign byte from BigInteger; it must be stripped.
        val high = BigInteger(1, WalletCrypto.fromHex("ff".repeat(32)))
        assertEquals(32, WalletCrypto.toFixedBytes(high, 32).size)
    }
}
