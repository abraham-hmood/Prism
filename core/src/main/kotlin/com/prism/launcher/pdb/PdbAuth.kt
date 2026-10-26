package com.prism.launcher.pdb

import com.prism.launcher.cloud.CloudVault
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authentication for every bridge session, not only the first one.
 *
 * ## The hole this closes
 *
 * The old handshake trusted a fingerprint sent in the clear and an address that matched the trust
 * record. Once a pair had been verified, ANY connection claiming that fingerprint from that
 * address got a shell; there was no secret to prove otherwise. A public fingerprint plus a mesh
 * address is not a credential, and on a shared network an address is not an identity.
 *
 * ## The shape of the fix
 *
 * The phrase ceremony still bootstraps the pair. On success the target generates 32 random bytes,
 * seals them with the phrase-derived key and sends them to the controller. Both sides keep that
 * secret. Every later session is a challenge: the target sends a fresh nonce, the controller
 * returns HMAC-SHA256(secret, nonce || both fingerprints), and the target compares in constant
 * time. The secret never crosses the wire, a captured proof is useless against a new nonce, and a
 * stolen fingerprint is no longer enough to run a command.
 *
 * Re-verification remains possible -- a controller that lost its copy can ask to see a new phrase
 * -- but it requires the physical screen and is rate-limited by [Pdb].
 */
internal object PdbAuth {

    private const val SECRET_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val PROOF_CONTEXT = "prism-pdb-session-v2"
    private const val SERVER_CONTEXT = "prism-pdb-target-v2"
    private val random = SecureRandom()

    fun newSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    fun proof(
        secret: String,
        nonce: ByteArray,
        clientFingerprint: String,
        targetFingerprint: String,
    ): ByteArray? = mac(secret, nonce, PROOF_CONTEXT, clientFingerprint, targetFingerprint)

    /**
     * The target's half of the handshake.
     *
     * Without this the controller authenticated itself to a name, not a machine: a peer that
     * claimed the trusted address could answer the challenge, accept the controller's proof, say
     * "trusted" and receive every command sent to that device. A different context byte string
     * keeps the two directions from being interchangeable.
     */
    fun serverProof(
        secret: String,
        nonce: ByteArray,
        clientFingerprint: String,
        targetFingerprint: String,
    ): ByteArray? = mac(secret, nonce, SERVER_CONTEXT, clientFingerprint, targetFingerprint)

    private fun mac(
        secret: String,
        nonce: ByteArray,
        context: String,
        clientFingerprint: String,
        targetFingerprint: String,
    ): ByteArray? = runCatching {
        val key = Base64.getDecoder().decode(secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(context.toByteArray(Charsets.UTF_8))
        mac.update(clientFingerprint.toByteArray(Charsets.UTF_8))
        mac.update(targetFingerprint.toByteArray(Charsets.UTF_8))
        mac.doFinal()
    }.getOrNull()

    fun verify(
        proof: ByteArray?,
        secret: String,
        nonce: ByteArray,
        clientFingerprint: String,
        targetFingerprint: String,
    ): Boolean {
        if (proof == null) return false
        val expected = proof(secret, nonce, clientFingerprint, targetFingerprint) ?: return false
        return MessageDigest.isEqual(expected, proof)
    }

    fun verifyServer(
        proof: ByteArray?,
        secret: String,
        nonce: ByteArray,
        clientFingerprint: String,
        targetFingerprint: String,
    ): Boolean {
        if (proof == null) return false
        val expected = serverProof(secret, nonce, clientFingerprint, targetFingerprint) ?: return false
        return MessageDigest.isEqual(expected, proof)
    }

    /** Seals the pair secret with the phrase key. The phrase itself still never travels. */
    fun sealSecret(secret: String, key: ByteArray, salt: ByteArray): String =
        Base64.getEncoder().encodeToString(
            CloudVault.seal(secret.toByteArray(Charsets.UTF_8), key, salt),
        )

    fun openSecret(sealed: String, key: ByteArray, salt: ByteArray): String? =
        runCatching {
            Base64.getDecoder().decode(sealed)
        }.getOrNull()?.let { bytes ->
            CloudVault.open(bytes, key, salt)?.toString(Charsets.UTF_8)
        }?.takeIf { it.isNotBlank() }

    /** True when a stored value looks like one of our 256-bit pair secrets. */
    fun isValidSecret(secret: String?): Boolean = runCatching {
        Base64.getDecoder().decode(secret.orEmpty()).size == SECRET_BYTES
    }.getOrDefault(false)
}
