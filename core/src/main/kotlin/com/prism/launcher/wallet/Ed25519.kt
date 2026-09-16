package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * Raw ed25519 point arithmetic, for Monero.
 *
 * ## Why this is not BouncyCastle's Ed25519
 *
 * BouncyCastle's implementation is built for SIGNING: `generatePublicKey` hashes the private key
 * with SHA-512 and clamps the result, because that is what RFC 8032 specifies for signatures.
 * Monero does neither. Its keys are plain scalars reduced modulo the group order, and its public
 * keys are `k·B` with no hashing and no clamping. Feeding a Monero spend key through the RFC 8032
 * path produces a perfectly valid ed25519 key that has nothing to do with the user's wallet.
 *
 * ## Extended coordinates, one inversion
 *
 * Points are held as (X:Y:Z:T) so the addition law needs no modular inverse; a single inversion
 * happens when a point is compressed at the end. Affine addition would be simpler and roughly two
 * hundred times slower per scalar multiplication, which matters because address derivation happens
 * on every wallet-list render.
 *
 * The twisted Edwards addition law for a = -1 is COMPLETE on this curve -- it is correct for
 * doubling, for the identity and for every other case -- so doubling reuses [add] rather than
 * carrying a second formula that would need its own tests.
 */
object Ed25519 {

    /** Field modulus, 2^255 - 19. */
    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** Group order, 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** Curve constant d = -121665/121666 mod p. */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    private val TWO_D: BigInteger = D.multiply(BigInteger.TWO).mod(P)

    /** Base point: y = 4/5, with the even x. */
    private val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("ed25519 base point could not be recovered")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    /** add-2008-hwcd-3, the complete addition law for a = -1. */
    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(TWO_D).multiply(q.t).mod(P)
        val d = p.z.multiply(BigInteger.TWO).multiply(q.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    private fun scalarMult(k: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        var scalar = k
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    /**
     * Compresses to the 32-byte wire form: y little-endian, with x's low bit as the top bit.
     */
    private fun compress(point: Point): ByteArray {
        val inverse = point.z.modInverse(P)
        val x = point.x.multiply(inverse).mod(P)
        val y = point.y.multiply(inverse).mod(P)

        val out = littleEndian(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Solves the curve equation for x given y and the wanted sign bit. */
    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)

        // x = (u/v)^((p+3)/8), with the standard correction when that root is off by sqrt(-1).
        val candidate = u.multiply(v.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
            .modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)

        var x = candidate
        val check = x.multiply(x).multiply(v).mod(P)
        if (check != u.mod(P)) {
            if (check != u.negate().mod(P)) return null
            val sqrtMinusOne = BigInteger.TWO.modPow(
                P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P
            )
            x = x.multiply(sqrtMinusOne).mod(P)
        }
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
        return x
    }

    /**
     * The public key for a scalar: `k·B`, compressed.
     *
     * NO CLAMPING AND NO HASHING, which is the whole reason this exists -- see the class note.
     */
    fun scalarMultBase(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "an ed25519 scalar is 32 bytes" }
        return compress(scalarMult(fromLittleEndian(scalar), BASE))
    }

    /**
     * `sc_reduce32`: interprets 32 little-endian bytes as an integer and reduces it modulo the
     * group order.
     *
     * Monero private keys MUST be reduced. An unreduced scalar produces a public key that is still
     * a valid point but does not correspond to the key the wallet will later derive, so funds
     * would be sent to an address nothing can spend from.
     */
    fun scReduce32(bytes: ByteArray): ByteArray {
        require(bytes.size == 32) { "expected 32 bytes" }
        return littleEndian(fromLittleEndian(bytes).mod(L))
    }

    /** True when the scalar is already reduced, i.e. a canonical Monero private key. */
    fun isReduced(bytes: ByteArray): Boolean =
        bytes.size == 32 && fromLittleEndian(bytes) < L

    fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    fun littleEndian(value: BigInteger): ByteArray =
        WalletCrypto.toFixedBytes(value, 32).reversedArray()
}
