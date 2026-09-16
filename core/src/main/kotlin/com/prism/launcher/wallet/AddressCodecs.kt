package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * Base58Check -- the address encoding of Bitcoin, Litecoin, Dogecoin and their forks.
 *
 * The alphabet omits 0, O, I and l on purpose: these strings get read aloud, retyped and
 * transcribed from screenshots, and those four are the characters people confuse. The trailing
 * checksum is what turns a typo into a rejected address instead of a payment into the void.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Leading zero bytes are not representable as digits in a base conversion, so they are
        // carried across as literal '1's -- this is what makes a P2PKH address start with 1.
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros] == 0.toByte()) leadingZeros++

        var value = BigInteger(1, input)
        val fiftyEight = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (value.signum() > 0) {
            val (q, r) = value.divideAndRemainder(fiftyEight)
            sb.append(ALPHABET[r.toInt()])
            value = q
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var value = BigInteger.ZERO
        val fiftyEight = BigInteger.valueOf(58)
        for (c in input) {
            val digit = if (c.code < 128) INDEX[c.code] else -1
            require(digit >= 0) { "invalid base58 character '$c'" }
            value = value.multiply(fiftyEight).add(BigInteger.valueOf(digit.toLong()))
        }
        var body = value.toByteArray()
        if (body.size > 1 && body[0] == 0.toByte()) body = body.copyOfRange(1, body.size)

        var leadingOnes = 0
        while (leadingOnes < input.length && input[leadingOnes] == ALPHABET[0]) leadingOnes++

        val out = ByteArray(leadingOnes + body.size)
        System.arraycopy(body, 0, out, leadingOnes, body.size)
        return out
    }

    /** Appends the 4-byte double-SHA256 checksum and encodes. */
    fun encodeChecked(payload: ByteArray): String {
        val checksum = WalletCrypto.sha256d(payload).copyOfRange(0, 4)
        return encode(payload + checksum)
    }

    /** Decodes and verifies the checksum, returning the payload without it. */
    fun decodeChecked(input: String): ByteArray {
        val raw = decode(input)
        require(raw.size >= 5) { "too short to be a checked address" }
        val payload = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = WalletCrypto.sha256d(payload).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "checksum mismatch" }
        return payload
    }

    fun isValidChecked(input: String): Boolean = runCatching { decodeChecked(input) }.isSuccess
}

/**
 * Bech32 (BIP-173) and Bech32m (BIP-350) -- the native SegWit address encodings.
 *
 * WHY BOTH: the two differ only in a constant, but using the wrong one produces an address that
 * every conforming wallet rejects. Witness version 0 (the P2WPKH addresses this wallet issues) uses
 * Bech32; versions 1 and up (Taproot) use Bech32m. The constant is chosen from the version rather
 * than fixed, so decoding stays correct if a later version is ever added.
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[i + hrp.length + 1] = hrp[i].code and 31
        }
        out[hrp.length] = 0
        return out
    }

    private fun encodeRaw(hrp: String, data: IntArray, constant: Int): String {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor constant
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        val sb = StringBuilder(hrp).append('1')
        for (d in data + checksum) sb.append(CHARSET[d])
        return sb.toString()
    }

    /**
     * Regroups bits between packings -- 8-bit bytes to the 5-bit symbols bech32 stores, or back.
     *
     * The padding rules are asymmetric and that asymmetry is the security-relevant part: when
     * packing up to 5 bits, a partial final group is zero-padded; when unpacking back to 8, a
     * partial group must be discarded AND verified to be zero, or two distinct encodings would
     * decode to the same witness program.
     */
    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || (value ushr from) != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    /** A native SegWit address for [program] under the given human-readable prefix. */
    fun encodeSegwit(hrp: String, witnessVersion: Int, program: ByteArray): String {
        val squashed = convertBits(IntArray(program.size) { program[it].toInt() and 0xff }, 8, 5, true)
            ?: throw IllegalArgumentException("witness program could not be repacked")
        val data = intArrayOf(witnessVersion) + squashed
        val constant = if (witnessVersion == 0) BECH32_CONST else BECH32M_CONST
        return encodeRaw(hrp.lowercase(), data, constant)
    }

    data class Decoded(val hrp: String, val witnessVersion: Int, val program: ByteArray)

    fun decodeSegwit(address: String): Decoded? {
        // Mixed case is rejected outright: the checksum is computed over one case, so a mixed
        // string is either a transcription error or an attempt to slip one past the checksum.
        if (address != address.lowercase() && address != address.uppercase()) return null
        val s = address.lowercase()
        val split = s.lastIndexOf('1')
        if (split < 1 || split + 7 > s.length || s.length > 90) return null

        val hrp = s.substring(0, split)
        val dataPart = s.substring(split + 1)
        val data = IntArray(dataPart.length) {
            val idx = CHARSET.indexOf(dataPart[it])
            if (idx < 0) return null
            idx
        }

        val version = data[0]
        val constant = if (version == 0) BECH32_CONST else BECH32M_CONST
        if (polymod(hrpExpand(hrp) + data) != constant) return null

        val program = convertBits(data.copyOfRange(1, data.size - 6), 5, 8, false) ?: return null
        if (program.size < 2 || program.size > 40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null
        if (version > 16) return null

        return Decoded(hrp, version, ByteArray(program.size) { program[it].toByte() })
    }

    fun isValidSegwit(address: String, expectedHrp: String): Boolean =
        decodeSegwit(address)?.hrp == expectedHrp.lowercase()
}
