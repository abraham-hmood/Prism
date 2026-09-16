package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * A Monero wallet derived from Prism's recovery phrase.
 *
 * ## The interoperability problem, and how it is actually solved
 *
 * There is no standard for deriving a Monero wallet from a BIP-39 phrase. Prism picks one
 * ([DERIVATION_PATH], reducing the BIP-32 node's key to an ed25519 scalar), and on its own that
 * would be a trap: coins mined into it would be reachable only from Prism, exactly the situation
 * the recovery-phrase export was added to prevent.
 *
 * WHAT MAKES IT SAFE is [mnemonic]. Monero's own 25-word seed encodes nothing but the 32-byte
 * private spend key, so once Prism has derived that key it can emit the standard Monero seed for
 * it. That seed restores in monero-wallet-cli, Feather, Cake, Stack -- anything. The derivation
 * being non-standard stops mattering, because what the user writes down IS standard.
 *
 * [privateSpendKey] and [privateViewKey] are exported too, for wallets that prefer restore-from-keys.
 *
 * ## Two keys, not one
 *
 * A Monero address contains a spend key and a VIEW key. The view key is derived from the spend key
 * as `sc_reduce32(keccak256(spend))` -- that is Monero's rule, not a choice -- which is what lets a
 * single 25-word seed restore both.
 */
object MoneroKeys {

    /**
     * SLIP-44 coin type 128 is Monero's registered index. The path is otherwise BIP-44 shaped, so
     * it sits alongside every other coin in the same tree.
     */
    const val DERIVATION_PATH = "m/44'/128'/0'/0/0"

    const val MNEMONIC_WORDS = 25
    private const val WORDLIST_SIZE = 1626

    /** English prefix length for Monero's checksum: the first three letters of each word. */
    private const val PREFIX_LENGTH = 3

    private val words: List<String> by lazy {
        val stream = MoneroKeys::class.java.getResourceAsStream("/monero-english.txt")
            ?: error("Monero wordlist missing from the build")
        val loaded = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        check(loaded.size == WORDLIST_SIZE) {
            "Monero wordlist must hold $WORDLIST_SIZE words, found ${loaded.size}"
        }
        loaded
    }

    /** Everything a Monero wallet is. */
    data class Wallet(
        val privateSpendKey: ByteArray,
        val publicSpendKey: ByteArray,
        val privateViewKey: ByteArray,
        val publicViewKey: ByteArray,
        val address: String,
    ) {
        /** The standard 25-word Monero seed, which restores this wallet in any Monero software. */
        val mnemonic: List<String> get() = mnemonicFor(privateSpendKey)

        override fun equals(other: Any?): Boolean =
            other is Wallet && address == other.address

        override fun hashCode(): Int = address.hashCode()

        /** Never let a key reach a log through a default toString. */
        override fun toString(): String = "MoneroWallet($address)"
    }

    /** Builds the whole wallet from 32 bytes of key material. */
    fun fromSeedBytes(material: ByteArray): Wallet {
        require(material.size == 32) { "expected 32 bytes of key material" }

        val spendSecret = Ed25519.scReduce32(material)
        val spendPublic = Ed25519.scalarMultBase(spendSecret)
        // Monero's rule, not a choice: the view key is the reduced Keccak of the spend key.
        val viewSecret = Ed25519.scReduce32(WalletCrypto.keccak256(spendSecret))
        val viewPublic = Ed25519.scalarMultBase(viewSecret)

        return Wallet(
            privateSpendKey = spendSecret,
            publicSpendKey = spendPublic,
            privateViewKey = viewSecret,
            publicViewKey = viewPublic,
            address = addressFor(spendPublic, viewPublic),
        )
    }

    /** Derives from a BIP-32 private key, which is how the wallet reaches it from the phrase. */
    fun fromPrivateKey(privateKey: BigInteger): Wallet =
        fromSeedBytes(WalletCrypto.toFixedBytes(privateKey, 32))

    /** Assembles a standard mainnet address from the two public keys. */
    fun addressFor(publicSpend: ByteArray, publicView: ByteArray): String {
        require(publicSpend.size == 32 && publicView.size == 32) { "public keys are 32 bytes" }
        val body = byteArrayOf(MoneroAddress.PREFIX_STANDARD.toByte()) + publicSpend + publicView
        val checksum = WalletCrypto.keccak256(body).copyOfRange(0, 4)
        return MoneroAddress.encodeBase58Blocks(body + checksum)
    }

    // ── The 25-word seed ───────────────────────────────────────────────────

    /**
     * Encodes a private spend key as Monero's 25-word mnemonic.
     *
     * Three words per four bytes, in Monero's own scheme -- NOT BIP-39's. Each little-endian
     * 32-bit group becomes three indices into a 1626-word list, and a 25th word repeats one of the
     * first 24 as a checksum, chosen by a CRC-32 over their three-letter prefixes.
     */
    fun mnemonicFor(privateSpendKey: ByteArray): List<String> {
        require(privateSpendKey.size == 32) { "a spend key is 32 bytes" }

        val out = ArrayList<String>(MNEMONIC_WORDS)
        for (group in 0 until 8) {
            val value = readLittleEndianUInt32(privateSpendKey, group * 4)
            val n = WORDLIST_SIZE.toLong()
            val w1 = (value % n).toInt()
            val w2 = (((value / n) + w1) % n).toInt()
            val w3 = (((value / n / n) + w2) % n).toInt()
            out.add(words[w1]); out.add(words[w2]); out.add(words[w3])
        }
        out.add(out[checksumIndex(out)])
        return out
    }

    /** Recovers the spend key from a 25-word seed, so an imported one can be checked. */
    fun keyFromMnemonic(mnemonic: List<String>): ByteArray? {
        val clean = mnemonic.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (clean.size != MNEMONIC_WORDS && clean.size != 24) return null

        val body = clean.take(24)
        if (clean.size == MNEMONIC_WORDS && clean[24] != body[checksumIndex(body)]) return null

        val out = ByteArray(32)
        for (group in 0 until 8) {
            val w1 = words.indexOf(body[group * 3])
            val w2 = words.indexOf(body[group * 3 + 1])
            val w3 = words.indexOf(body[group * 3 + 2])
            if (w1 < 0 || w2 < 0 || w3 < 0) return null

            val n = WORDLIST_SIZE.toLong()
            val value = (w1.toLong() +
                n * (((w2 - w1) % n + n) % n) +
                n * n * (((w3 - w2) % n + n) % n)) and 0xFFFFFFFFL
            writeLittleEndianUInt32(out, group * 4, value)
        }
        return out
    }

    /** Which of the first 24 words is repeated as the checksum. */
    private fun checksumIndex(words24: List<String>): Int {
        val joined = words24.take(24).joinToString("") { it.take(PREFIX_LENGTH) }
        val crc = java.util.zip.CRC32().apply { update(joined.toByteArray(Charsets.UTF_8)) }.value
        return (crc % 24).toInt()
    }

    private fun readLittleEndianUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun writeLittleEndianUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
