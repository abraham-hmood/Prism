package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * BIP-32 hierarchical deterministic key derivation.
 *
 * One seed produces every key for every coin, at a defined path, forever. That is what lets a
 * recovery phrase written on paper restore a wallet that did not exist when the phrase was
 * created -- adding a coin later derives its keys from the same seed rather than needing a new
 * backup.
 *
 * ONLY HARDENED AND NORMAL PRIVATE DERIVATION IS IMPLEMENTED. Public (neutered) derivation is
 * omitted deliberately: its only use here would be watch-only accounts, and shipping an untested
 * second code path that produces addresses the wallet cannot spend from is worse than not having
 * the feature.
 */
data class ExtendedKey(
    val privateKey: BigInteger,
    val chainCode: ByteArray,
    val depth: Int,
    val parentFingerprint: ByteArray,
    val childNumber: Long,
) {
    val publicKey: ByteArray get() = WalletCrypto.compressedPublicKey(privateKey)

    /** First 4 bytes of HASH160(pubkey) -- how a child names its parent in a serialised key. */
    val fingerprint: ByteArray get() = WalletCrypto.hash160(publicKey).copyOfRange(0, 4)

    val privateKeyBytes: ByteArray get() = WalletCrypto.toFixedBytes(privateKey, 32)

    /**
     * The `xprv`/`xpub`-style serialisation, parameterised by version so coins with their own
     * prefixes (Litecoin's `Ltpv`, and the `zprv` set for native SegWit) encode correctly.
     */
    fun serializePrivate(version: Int): String {
        val out = java.io.ByteArrayOutputStream(78)
        out.write(intToBigEndian(version))
        out.write(depth)
        out.write(parentFingerprint)
        out.write(intToBigEndian(childNumber.toInt()))
        out.write(chainCode)
        out.write(0)                       // pads the 32-byte key to the 33 bytes a pubkey occupies
        out.write(privateKeyBytes)
        return Base58.encodeChecked(out.toByteArray())
    }

    fun serializePublic(version: Int): String {
        val out = java.io.ByteArrayOutputStream(78)
        out.write(intToBigEndian(version))
        out.write(depth)
        out.write(parentFingerprint)
        out.write(intToBigEndian(childNumber.toInt()))
        out.write(chainCode)
        out.write(publicKey)
        return Base58.encodeChecked(out.toByteArray())
    }

    private fun intToBigEndian(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    // Data classes compare ByteArray by identity, which would make two structurally identical keys
    // unequal and is exactly the kind of silent wrongness this code cannot afford.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtendedKey) return false
        return privateKey == other.privateKey &&
            chainCode.contentEquals(other.chainCode) &&
            depth == other.depth &&
            parentFingerprint.contentEquals(other.parentFingerprint) &&
            childNumber == other.childNumber
    }

    override fun hashCode(): Int {
        var result = privateKey.hashCode()
        result = 31 * result + chainCode.contentHashCode()
        result = 31 * result + depth
        result = 31 * result + parentFingerprint.contentHashCode()
        result = 31 * result + childNumber.hashCode()
        return result
    }

    /** Never log or serialise a key by accident. */
    override fun toString(): String = "ExtendedKey(depth=$depth, child=$childNumber, <private>)"
}

object Bip32 {

    const val HARDENED_OFFSET = 0x80000000L

    /** Standard mainnet version bytes for BIP-44 (`xprv`/`xpub`). */
    const val XPRV = 0x0488ADE4.toInt()
    const val XPUB = 0x0488B21E.toInt()

    fun masterKeyFromSeed(seed: ByteArray): ExtendedKey {
        require(seed.size in 16..64) { "seed must be 128-512 bits" }
        val i = WalletCrypto.hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)
        val key = BigInteger(1, il)
        // Vanishingly unlikely (~2^-127) but specified: such a seed is invalid, not "close enough".
        require(key.signum() != 0 && key < WalletCrypto.SECP256K1.n) { "seed produced an invalid master key" }
        return ExtendedKey(key, ir, depth = 0, parentFingerprint = ByteArray(4), childNumber = 0)
    }

    /**
     * One CKDpriv step.
     *
     * Hardened children (index >= 2^31) hash the PRIVATE key, normal children hash the public one.
     * The distinction is the whole security argument for the account level of BIP-44: with a normal
     * child, anyone holding the parent public key and one child private key can recover the parent
     * private key, and therefore every sibling.
     */
    fun deriveChild(parent: ExtendedKey, index: Long): ExtendedKey {
        require(index >= 0 && index < (1L shl 32)) { "child index out of range" }
        val hardened = index >= HARDENED_OFFSET

        val data = java.io.ByteArrayOutputStream(37)
        if (hardened) {
            data.write(0)
            data.write(parent.privateKeyBytes)
        } else {
            data.write(parent.publicKey)
        }
        data.write(
            byteArrayOf(
                (index ushr 24).toByte(), (index ushr 16).toByte(),
                (index ushr 8).toByte(), index.toByte()
            )
        )

        val i = WalletCrypto.hmacSha512(parent.chainCode, data.toByteArray())
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val ir = i.copyOfRange(32, 64)

        val n = WalletCrypto.SECP256K1.n
        // BIP-32 says to skip to the next index in this case rather than clamp. It has never been
        // observed in practice, but "handle it wrong quietly" is not an option in key derivation.
        if (il >= n) return deriveChild(parent, index + 1)
        val childKey = il.add(parent.privateKey).mod(n)
        if (childKey.signum() == 0) return deriveChild(parent, index + 1)

        return ExtendedKey(
            privateKey = childKey,
            chainCode = ir,
            depth = parent.depth + 1,
            parentFingerprint = parent.fingerprint,
            childNumber = index,
        )
    }

    /** Walks a path such as `m/44'/0'/0'/0/0`. Both `'` and `h` mark a hardened level. */
    fun derivePath(master: ExtendedKey, path: String): ExtendedKey {
        val trimmed = path.trim()
        require(trimmed.startsWith("m")) { "a derivation path starts at 'm', got '$path'" }
        var key = master
        for (part in trimmed.removePrefix("m").split('/')) {
            if (part.isBlank()) continue
            val hardened = part.endsWith("'") || part.endsWith("h") || part.endsWith("H")
            val number = part.trimEnd('\'', 'h', 'H').toLongOrNull()
                ?: throw IllegalArgumentException("bad path element '$part' in '$path'")
            key = deriveChild(key, if (hardened) number + HARDENED_OFFSET else number)
        }
        return key
    }

    fun deriveFromSeed(seed: ByteArray, path: String): ExtendedKey =
        derivePath(masterKeyFromSeed(seed), path)
}
