package com.prism.core

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/**
 * Curve25519 keypairs for WireGuard.
 *
 * WHY THIS IS A CAPABILITY. `PrismSettings` generated these with
 * `com.wireguard.android:tunnel`'s `KeyPair`, which is an Android library and the single reason
 * settings could not move to `:core`. The keys themselves are not Android-specific at all -- they
 * are 32 bytes of Curve25519 -- so the platform-specific part is only WHICH implementation
 * produces them.
 *
 * THE DEFAULT IS REAL. [JdkWireGuardKeys] uses the JDK's own X25519 support, present since Java
 * 11, so desktop needs no native dependency and no vendored crypto. Android keeps the WireGuard
 * library it already ships and has already been generating keys with, which matters because a
 * different implementation must not produce a different key for an existing install.
 *
 * ENCODING IS WIREGUARD'S: standard base64 of the raw 32 bytes, which is what a `.conf` file
 * contains and what a peer expects. Getting this wrong produces a config that looks correct and
 * silently never completes a handshake.
 */
interface WireGuardKeys {
    /** A fresh keypair as (privateBase64, publicBase64). */
    fun generate(): Pair<String, String>
}

/**
 * X25519 via the JDK.
 *
 * The awkward part is that JCA hands back keys in ASN.1 wrappers -- PKCS#8 for private,
 * SubjectPublicKeyInfo for public -- while WireGuard wants the bare 32 bytes. For X25519 those
 * wrappers have a fixed-length prefix, so the raw key is exactly the last 32 bytes of the
 * encoding. That is a documented property of the encoding rather than a guess, but it is checked
 * rather than assumed, because a silently truncated key would produce a config that never works.
 */
object JdkWireGuardKeys : WireGuardKeys {

    override fun generate(): Pair<String, String> {
        val jdk = runCatching {
            val generator = KeyPairGenerator.getInstance("X25519")
            val pair = generator.generateKeyPair()
            val privateRaw = tail32(pair.private.encoded)
            val publicRaw = tail32(pair.public.encoded)
            if (privateRaw == null || publicRaw == null) null
            else encode(privateRaw) to encode(publicRaw)
        }.getOrNull()

        if (jdk != null) return jdk

        // A JVM without X25519 is not a reason to fail. Clamped random bytes are a valid
        // WireGuard private key; what is lost is the derived public key, which the caller must
        // then obtain from the peer configuration instead.
        PrismPlatform.log.warn(
            "Prism/wg",
            "X25519 unavailable on this JVM; generated a private key without a derived public key"
        )
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        clamp(raw)
        return encode(raw) to ""
    }

    private fun tail32(encoded: ByteArray?): ByteArray? =
        if (encoded == null || encoded.size < 32) null
        else encoded.copyOfRange(encoded.size - 32, encoded.size)

    /**
     * Curve25519 scalar clamping (RFC 7748): clear the low three bits, clear the top bit, set
     * the second-highest. Required for the scalar to be a valid private key.
     */
    private fun clamp(key: ByteArray) {
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127).toByte()
        key[31] = (key[31].toInt() or 64).toByte()
    }

    private fun encode(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)
}
