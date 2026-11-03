package com.prism.launcher.trusted

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key two paired devices share with each other, and with nobody else.
 *
 * ## Why pairing has to produce one
 *
 * Calls, voicemail and video between trusted devices used to be sealed with the WALLET's calls key.
 * That only works when both devices hold the same recovery phrase, and pairing does not make that
 * so: the wallet is handed over only to a device that has none. Two devices that each already had a
 * wallet were "trusted" in both lists and still could not open a single sealed packet from each
 * other -- the phone logged "did not decrypt — different recovery phrase", the desktop dropped every
 * invitation, and no call between them ever connected.
 *
 * ## How it is made
 *
 * The offer carries an ephemeral P-256 public key and so does the answer; each side computes the
 * same ECDH secret, so a device that only watched the pairing learns nothing. That secret is then
 * mixed with the key the pairing CODE derives to, which only the two devices that saw the code on
 * screen have: a device in the middle that swapped in its own public keys would also have to know
 * the code, and the code is what the existing proof already checks.
 *
 * P-256 rather than X25519 because it is in every JVM and in Android since API 11; X25519 needs
 * Android 12. The derivation is HMAC-SHA256, giving the 32-byte key CloudVault's AES-GCM takes.
 */
object PairLink {

    private const val CURVE = "secp256r1"
    private val LABEL = "prism-pair-link-v1".toByteArray()

    /** A fresh ephemeral key pair for one pairing attempt. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(CURVE))
    }.generateKeyPair()

    /** The public half, for the wire. */
    fun publicOf(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    /**
     * The link key, or null when the peer's public key is not one this device can use.
     *
     * [codeKey] is the key the pairing code derives to on both sides; [salt] is the offer's salt, so
     * two pairings with the same code still produce different link keys.
     */
    fun derive(own: KeyPair, peerPublic: String, codeKey: ByteArray, salt: ByteArray): ByteArray? = runCatching {
        val encoded = Base64.getDecoder().decode(peerPublic)
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(codeKey, "HmacSHA256"))
            update(LABEL)
            update(salt)
            update(shared)
            doFinal()
        }
    }.getOrNull()

    fun encode(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    fun decode(value: String): ByteArray? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == 32 }
}
