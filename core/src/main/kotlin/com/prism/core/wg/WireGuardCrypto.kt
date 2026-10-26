package com.prism.core.wg

import java.math.BigInteger

/**
 * BLAKE2s, unkeyed and keyed.
 *
 * WireGuard uses the 32-byte variant with no salt and no personalisation, so that is the only shape
 * implemented. [hash] with a key is BLAKE2s keyed mode, which WireGuard uses for MAC1.
 */
class Blake2s(
    private val key: ByteArray = ByteArray(0),
    // 32 for WireGuard's handshake hashes, 16 for MAC1 and MAC2. The length is part of the
    // parameter block, so it is NOT the same as truncating a 32-byte digest.
    private val digestLength: Int = 32,
) {

    // Int, not Long. BLAKE2s words are 32 bits and every operation is a 32-bit one; holding them
    // in Longs means masking after every add and rotating with the wrong width, and both mistakes
    // produce a digest that is simply wrong without looking wrong.
    private val h = IntArray(8)
    private val block = ByteArray(64)
    private var blockLength = 0
    private var counter = 0L
    private var finished = false

    init {
        require(key.size <= 32) { "BLAKE2s keys are at most 32 bytes" }
        require(digestLength in 1..32) { "BLAKE2s digests are 1 to 32 bytes" }
        IV.copyInto(h)
        // Parameter block: fanout 1, depth 1, key length in bytes 8..15, digest length 32.
        // Writing only `key.size` here -- which is what this did at first -- silently produces a
        // valid-looking BLAKE2s with the wrong digest length in its parameter block, so every
        // digest is wrong and nothing about it looks broken.
        h[0] = h[0] xor 0x01010000 xor (key.size shl 8) xor digestLength
        if (key.isNotEmpty()) {
            // Keyed mode prepends the key, zero-padded to a full block.
            val padded = ByteArray(64)
            key.copyInto(padded)
            update(padded)
        }
    }

    fun update(data: ByteArray): Blake2s {
        check(!finished) { "digest already taken" }
        var offset = 0

        // A full buffer is not compressed until more input arrives: the final block has to be
        // flagged as final, so it cannot be compressed until it is known to be last.
        if (blockLength > 0) {
            val take = minOf(64 - blockLength, data.size)
            data.copyInto(block, blockLength, 0, take)
            blockLength += take
            offset += take
            if (blockLength == 64 && offset < data.size) {
                counter += 64
                compress(last = false)
                blockLength = 0
            }
        }
        while (data.size - offset > 64) {
            data.copyInto(block, 0, offset, offset + 64)
            counter += 64
            compress(last = false)
            offset += 64
        }
        if (offset < data.size) {
            blockLength = data.size - offset
            data.copyInto(block, 0, offset, data.size)
        }
        return this
    }

    fun digest(): ByteArray {
        check(!finished) { "digest already taken" }
        finished = true
        counter += blockLength.toLong()
        block.fill(0, blockLength, 64)
        compress(last = true)
        val out = ByteArray(digestLength)
        for (i in 0 until 8) {
            for (j in 0 until 4) {
                val index = i * 4 + j
                if (index < digestLength) out[index] = (h[i] ushr (8 * j)).toByte()
            }
        }
        return out
    }

    internal var debugRounds = 10

    internal fun debugH(): String = h.joinToString(",") { (it.toUInt()).toString(16) }

    private fun compress(last: Boolean) {
        val m = IntArray(16)
        for (i in 0 until 16) {
            m[i] = (block[i * 4].toInt() and 0xFF) or
                ((block[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((block[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((block[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        val v = IntArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[8 + i] = IV[i]
        v[12] = v[12] xor counter.toInt()
        v[13] = v[13] xor (counter ushr 32).toInt()
        if (last) v[14] = v[14].inv()

        fun g(a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            v[a] = v[a] + v[b] + x
            v[d] = Integer.rotateRight(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 12)
            v[a] = v[a] + v[b] + y
            v[d] = Integer.rotateRight(v[d] xor v[a], 8)
            v[c] = v[c] + v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 7)
        }

        for (round in 0 until debugRounds) {
            val s = SIGMA[round]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        lastV = v.copyOf()
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    internal var lastV: IntArray = IntArray(16)

    companion object {
        private val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        fun hash(vararg parts: ByteArray): ByteArray {
            val state = Blake2s()
            for (part in parts) state.update(part)
            return state.digest()
        }

        fun hash(key: ByteArray, data: ByteArray, length: Int = 32): ByteArray =
            Blake2s(key, length).update(data).digest()
    }
}

/** HMAC with BLAKE2s, block size 64. */
object HmacBlake2s {

    private const val BLOCK = 64

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val k = if (key.size > BLOCK) Blake2s.hash(key) else key
        val padded = ByteArray(BLOCK)
        k.copyInto(padded)
        val inner = ByteArray(BLOCK) { (padded[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (padded[it].toInt() xor 0x5c).toByte() }
        val innerHash = Blake2s.hash(inner + message)
        return Blake2s.hash(outer + innerHash)
    }
}

/**
 * X25519, the Diffie-Hellman function WireGuard uses.
 *
 * Written against RFC 7748's Montgomery ladder rather than JCA's `XDH`, because `XDH` only exists
 * from Android 13 and this has to run on the API 26 devices the launcher supports.
 *
 * NOT CONSTANT-TIME. `BigInteger` arithmetic leaks through timing, so a co-located attacker
 * measuring handshake durations could in principle learn something about a static key. The
 * alternative is hand-written limb arithmetic, which is a much larger surface to get wrong. On a
 * phone sharing a LAN this is accepted; it is written down rather than left to be discovered.
 */
object X25519 {

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    val BASE_POINT = ByteArray(32).also { it[0] = 9 }

    fun clamp(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 keys are 32 bytes" }
        val out = privateKey.copyOf()
        out[0] = (out[0].toInt() and 248).toByte()
        out[31] = (out[31].toInt() and 127).toByte()
        out[31] = (out[31].toInt() or 64).toByte()
        return out
    }

    fun scalarMult(privateKey: ByteArray, uCoordinate: ByteArray): ByteArray {
        val k = clamp(privateKey)
        val x1 = decode(uCoordinate)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }

        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
        return encode(result)
    }

    private fun decode(bytes: ByteArray): BigInteger {
        val little = bytes.copyOf()
        little[31] = (little[31].toInt() and 127).toByte()
        little.reverse()
        return BigInteger(1, little).mod(P)
    }

    private fun encode(value: BigInteger): ByteArray {
        // `toByteArray` is big-endian; X25519 coordinates go on the wire little-endian, so the
        // least significant byte -- the last one of the array -- lands at index zero.
        val big = value.mod(P).toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, big.size)) {
            out[i] = big[big.size - 1 - i]
        }
        return out
    }
}

/**
 * ChaCha20 with the Poly1305 one-time authenticator, as RFC 8439 defines them.
 *
 * Implemented here for the same reason as everything else in this file: Android only provides the
 * `ChaCha20-Poly1305` cipher from API 28, and this runs on 26.
 */
object ChaChaPoly {

    private val P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    /** Encrypts and authenticates: ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val polyKey = block(key, nonce, 0).copyOfRange(0, 32)
        val ciphertext = chacha20(key, nonce, 1, plaintext)
        val tag = poly1305(polyKey, macData(aad, ciphertext))
        return ciphertext + tag
    }

    /** Verifies and decrypts, or returns null. A bad tag is indistinguishable from a bad packet. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val polyKey = block(key, nonce, 0).copyOfRange(0, 32)
        val expected = poly1305(polyKey, macData(aad, ciphertext))
        if (!WireGuardNoise.constantTimeEquals(expected, tag)) return null
        return chacha20(key, nonce, 1, ciphertext)
    }

    /** Counter-mode ChaCha20. Counter starts at 1 for the payload: block 0 is the Poly1305 key. */
    internal fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 keys are 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonces are 12 bytes" }
        val out = ByteArray(input.size)
        var blockCounter = counter
        var offset = 0
        while (offset < input.size) {
            val keystream = block(key, nonce, blockCounter)
            val take = minOf(64, input.size - offset)
            for (i in 0 until take) out[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            offset += take
            blockCounter++
        }
        return out
    }

    internal fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)

        val working = state.copyOf()
        fun quarterRound(a: Int, b: Int, c: Int, d: Int) {
            working[a] += working[b]; working[d] = Integer.rotateLeft(working[d] xor working[a], 16)
            working[c] += working[d]; working[b] = Integer.rotateLeft(working[b] xor working[c], 12)
            working[a] += working[b]; working[d] = Integer.rotateLeft(working[d] xor working[a], 8)
            working[c] += working[d]; working[b] = Integer.rotateLeft(working[b] xor working[c], 7)
        }

        repeat(10) {
            quarterRound(0, 4, 8, 12)
            quarterRound(1, 5, 9, 13)
            quarterRound(2, 6, 10, 14)
            quarterRound(3, 7, 11, 15)
            quarterRound(0, 5, 10, 15)
            quarterRound(1, 6, 11, 12)
            quarterRound(2, 7, 8, 13)
            quarterRound(3, 4, 9, 14)
        }

        val out = ByteArray(64)
        for (i in 0 until 16) {
            val value = working[i] + state[i]
            for (j in 0 until 4) out[i * 4 + j] = (value ushr (8 * j)).toByte()
        }
        return out
    }

    private fun macData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val paddedAad = aad + ByteArray((16 - aad.size % 16) % 16)
        val paddedCipher = ciphertext + ByteArray((16 - ciphertext.size % 16) % 16)
        val lengths = ByteArray(16)
        writeLe64(lengths, 0, aad.size.toLong())
        writeLe64(lengths, 8, ciphertext.size.toLong())
        return paddedAad + paddedCipher + lengths
    }

    /** Poly1305 over 16-byte blocks, which is what the AEAD construction always feeds it. */
    internal fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32) { "Poly1305 keys are 32 bytes" }
        val rBytes = key.copyOfRange(0, 16)
        clampR(rBytes)
        rBytes.reverse()
        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, key.copyOfRange(16, 32).reversedArray())
        var accumulator = BigInteger.ZERO

        var offset = 0
        while (offset < message.size) {
            val take = minOf(16, message.size - offset)
            val block = message.copyOfRange(offset, offset + take)
            val number = BigInteger(1, block.reversedArray()).add(BigInteger.ONE.shiftLeft(8 * take))
            accumulator = accumulator.add(number).multiply(r).mod(P1305)
            offset += take
        }

        val big = accumulator.add(s).and(MASK128).toByteArray()
        val out = ByteArray(16)
        for (i in 0 until minOf(16, big.size)) out[15 - i] = big[big.size - 1 - i]
        return out
    }

    private fun clampR(r: ByteArray) {
        r[3] = (r[3].toInt() and 15).toByte()
        r[7] = (r[7].toInt() and 15).toByte()
        r[11] = (r[11].toInt() and 15).toByte()
        r[15] = (r[15].toInt() and 15).toByte()
        r[4] = (r[4].toInt() and 252).toByte()
        r[8] = (r[8].toInt() and 252).toByte()
        r[12] = (r[12].toInt() and 252).toByte()
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
}
