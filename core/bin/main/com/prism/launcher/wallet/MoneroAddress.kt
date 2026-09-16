package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * Monero addresses: decoding and validation.
 *
 * ## Why Prism cannot derive one
 *
 * Every other coin here hangs off the same BIP-39 seed through secp256k1. Monero does not: it uses
 * ed25519, a DIFFERENT curve, and an address is a PAIR of keys (spend and view) rather than a hash
 * of one. There is no standard, interoperable way to derive a Monero address from a BIP-39 phrase
 * -- the conventions that exist disagree with each other, and a wallet derived under the wrong one
 * holds coins the official wallet will never find.
 *
 * So Prism does not invent an address. The user supplies one from a real Monero wallet, and this
 * file's job is to make sure a typo cannot send a week of mining into the void.
 *
 * ## Monero's base58 is not Bitcoin's
 *
 * Same alphabet, different structure. Bitcoin treats the whole payload as one big number; Monero
 * encodes in BLOCKS of 8 bytes to 11 characters, with a shorter final block. Decoding a Monero
 * address with a Bitcoin base58 routine produces plausible-looking bytes that are simply wrong,
 * which is exactly the kind of failure that ends with somebody's payout gone.
 */
object MoneroAddress {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** How many characters encode a block of 1..8 bytes. Index is the byte count. */
    private val ENCODED_BLOCK_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)

    private const val FULL_BLOCK_BYTES = 8
    private const val FULL_BLOCK_CHARS = 11

    /** Mainnet network bytes. */
    const val PREFIX_STANDARD = 18      // addresses beginning with 4
    const val PREFIX_INTEGRATED = 19    // 4..., carries an 8-byte payment id
    const val PREFIX_SUBADDRESS = 42    // addresses beginning with 8

    sealed class Kind {
        data object Standard : Kind()
        data object Integrated : Kind()
        data object Subaddress : Kind()
    }

    data class Decoded(val prefix: Int, val kind: Kind, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Decoded && prefix == other.prefix && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = prefix * 31 + bytes.contentHashCode()
    }

    /**
     * Decodes and verifies the checksum.
     *
     * Returns null for anything that is not a valid mainnet Monero address. The checksum is the
     * point: Monero addresses are 95 characters that nobody reads, and the last four bytes are a
     * Keccak digest of the rest precisely so a mistyped one is rejected rather than accepted as
     * somebody else's wallet.
     */
    fun decode(address: String): Decoded? {
        val clean = address.trim()
        if (clean.isEmpty()) return null

        val raw = decodeBase58Blocks(clean) ?: return null
        // 1 prefix byte + spend key + view key + 4 checksum, plus 8 more for an integrated address.
        if (raw.size != 69 && raw.size != 77) return null

        val body = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = WalletCrypto.keccak256(body).copyOfRange(0, 4)
        if (!checksum.contentEquals(expected)) return null

        val prefix = raw[0].toInt() and 0xff
        val kind = when (prefix) {
            PREFIX_STANDARD -> Kind.Standard
            PREFIX_INTEGRATED -> Kind.Integrated
            PREFIX_SUBADDRESS -> Kind.Subaddress
            else -> return null
        }
        // An integrated address carries a payment id and is the only one that is longer.
        if ((kind is Kind.Integrated) != (raw.size == 77)) return null

        return Decoded(prefix, kind, raw)
    }

    fun isValid(address: String): Boolean = decode(address) != null

    /** A short description for the UI, so the user can confirm they pasted what they meant to. */
    fun describe(address: String): String = when (decode(address)?.kind) {
        Kind.Standard -> "Standard Monero address"
        Kind.Integrated -> "Integrated address (carries a payment ID)"
        Kind.Subaddress -> "Subaddress"
        null -> "Not a valid Monero address"
    }

    /**
     * Monero's block-wise base58.
     *
     * Each 11-character block decodes to 8 bytes; a shorter trailing block decodes to whatever
     * byte count that character count corresponds to. A block whose value overflows its byte
     * width is rejected -- otherwise two different strings could decode to the same address.
     */
    private fun decodeBase58Blocks(input: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(input.length)
        var index = 0
        while (index < input.length) {
            val chars = minOf(FULL_BLOCK_CHARS, input.length - index)
            val byteCount = ENCODED_BLOCK_SIZES.indexOf(chars)
            if (byteCount <= 0) return null                 // not a legal block length

            var value = BigInteger.ZERO
            for (i in 0 until chars) {
                val digit = ALPHABET.indexOf(input[index + i])
                if (digit < 0) return null
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
            }
            // Must fit the block's width exactly; anything wider is a non-canonical encoding.
            if (value.bitLength() > byteCount * 8) return null

            val block = WalletCrypto.toFixedBytes(value, byteCount)
            out.write(block)
            index += chars
        }
        val result = out.toByteArray()
        return if (result.isEmpty()) null else result
    }

    /** Encodes bytes back to Monero base58. Present so the decoder can be tested by round trip. */
    fun encodeBase58Blocks(data: ByteArray): String {
        val sb = StringBuilder()
        var index = 0
        while (index < data.size) {
            val byteCount = minOf(FULL_BLOCK_BYTES, data.size - index)
            val chars = ENCODED_BLOCK_SIZES[byteCount]
            var value = BigInteger(1, data.copyOfRange(index, index + byteCount))

            val block = CharArray(chars) { ALPHABET[0] }
            var position = chars - 1
            while (value.signum() > 0 && position >= 0) {
                val (q, r) = value.divideAndRemainder(BigInteger.valueOf(58))
                block[position] = ALPHABET[r.toInt()]
                value = q
                position--
            }
            sb.append(block)
            index += byteCount
        }
        return sb.toString()
    }
}
