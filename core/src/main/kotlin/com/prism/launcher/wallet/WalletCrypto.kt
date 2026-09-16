package com.prism.launcher.wallet

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The hash and curve primitives the wallet is built on.
 *
 * NOTHING HERE IS HAND-ROLLED. Every primitive is either the JDK's or BouncyCastle's. A wallet is
 * one of the few places where "I implemented the algorithm from the spec and the test vectors pass"
 * is still not good enough: the vectors prove the happy path, not constant-time behaviour, not
 * carry handling at the edges of a 256-bit field, and a bug in any of it costs somebody their
 * coins irreversibly. The wrapper exists so callers read as intent (`hash160`, `keccak256`) rather
 * than as digest plumbing.
 */
object WalletCrypto {

    /** The secp256k1 group, shared by Bitcoin, Ethereum and effectively every coin here. */
    val SECP256K1: org.bouncycastle.crypto.params.ECDomainParameters by lazy {
        val c = CustomNamedCurves.getByName("secp256k1")
        org.bouncycastle.crypto.params.ECDomainParameters(c.curve, c.g, c.n, c.h)
    }

    /**
     * A SHA-256 instance per thread, reused.
     *
     * `MessageDigest.getInstance` walks the JCE provider list on every call, which is fine once
     * and ruinous in a mining loop doing it millions of times a minute -- it was costing more than
     * the hashing. `MessageDigest` is not thread-safe, so this is per-thread rather than shared;
     * `digest()` resets the instance, so no explicit reset is needed between uses.
     */
    private val sha256Digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun sha256(data: ByteArray): ByteArray = sha256Digest.get().digest(data)

    /** Double SHA-256 -- Bitcoin's hash for transaction ids, checksums and headers. */
    fun sha256d(data: ByteArray): ByteArray {
        val md = sha256Digest.get()
        return md.digest(md.digest(data))
    }

    /** As [sha256d], over a slice, so a caller need not copy a sub-array first. */
    fun sha256d(data: ByteArray, offset: Int, length: Int): ByteArray {
        val md = sha256Digest.get()
        md.update(data, offset, length)
        return md.digest(md.digest())
    }

    fun ripemd160(data: ByteArray): ByteArray {
        val d = RIPEMD160Digest()
        d.update(data, 0, data.size)
        val out = ByteArray(d.digestSize)
        d.doFinal(out, 0)
        return out
    }

    /** RIPEMD160(SHA256(x)) -- how Bitcoin-family addresses commit to a public key. */
    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    /**
     * Keccak-256 as Ethereum uses it.
     *
     * NOT SHA3-256, despite the same output size. Ethereum standardised on the original Keccak
     * padding before NIST changed it for the final SHA-3; using the JDK's "SHA3-256" here would
     * produce plausible-looking addresses that belong to nobody.
     */
    fun keccak256(data: ByteArray): ByteArray {
        val d = KeccakDigest(256)
        d.update(data, 0, data.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** Public key point for a private scalar, as the compressed 33-byte SEC encoding. */
    fun publicKeyPoint(privateKey: BigInteger): ECPoint =
        SECP256K1.g.multiply(privateKey).normalize()

    fun compressedPublicKey(privateKey: BigInteger): ByteArray =
        publicKeyPoint(privateKey).getEncoded(true)

    /** The 64-byte X||Y form, without the 0x04 prefix -- what Ethereum addresses hash. */
    fun uncompressedPublicKeyBody(privateKey: BigInteger): ByteArray =
        publicKeyPoint(privateKey).getEncoded(false).copyOfRange(1, 65)

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").trim()
        require(clean.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Left-pads (or validates) a big-endian integer to a fixed width.
     *
     * BigInteger.toByteArray() is not a fixed-width encoding: it emits a leading zero byte when the
     * top bit is set, and drops leading zero bytes otherwise. Both are wrong for a 32-byte key
     * field, and the second is the dangerous one -- a private key that happens to start with a zero
     * byte would silently serialise to 31 bytes and derive a different address every time.
     */
    fun toFixedBytes(value: BigInteger, width: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(width)
        when {
            raw.size == width -> return raw
            raw.size < width -> System.arraycopy(raw, 0, out, width - raw.size, raw.size)
            else -> {
                // Strip BigInteger's sign byte, but only if that is all the excess is.
                val excess = raw.size - width
                require(raw.take(excess).all { it == 0.toByte() }) {
                    "value does not fit in $width bytes"
                }
                System.arraycopy(raw, excess, out, 0, width)
            }
        }
        return out
    }
}
