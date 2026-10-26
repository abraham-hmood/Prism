package com.prism.core

import com.prism.launcher.wallet.Ed25519
import com.prism.launcher.wallet.WalletVault
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptographic identity behind a P2P DNS record.
 *
 * ## Why a signature at all
 *
 * The ledger is a gossip map of names to addresses, and every peer on the mesh may write to it.
 * Source-address binding -- "you may only point a name at your own address" -- stops a peer from
 * redirecting somebody else's site, but it cannot stop a STRANGER FROM CLAIMING A NEW NAME. Anyone
 * who is first to announce `bank.p2p` owns it, and the next peer to look it up is sent wherever
 * they said. On a network whose whole premise is that no server decides, deciding by first-come is
 * the one rule nobody agreed to.
 *
 * A signature does not make names globally unique -- there is no registrar, and there does not
 * need to be. What it does is make a name BIND to an identity: `bank.p2p` under key A is a
 * different name from `bank.p2p` under key B, and a later peer cannot silently swap one for the
 * other. That is the property that was missing, and it is the one that matters for a name somebody
 * is asked to trust.
 *
 * ## Why the wallet
 *
 * Because it is the only secret a Prism user has already been made to write down, and because it
 * is already the root of every other mesh key. The same recovery phrase restores the wallet, the
 * cloud, the messaging relay and now the identity a site is published under. A device that joins
 * later with the same phrase can re-sign its records and keep them verifiable; a device with no
 * wallet simply cannot publish a signed name, which is honest rather than a failure.
 *
 * ## Domain separation
 *
 * The seed is HKDF-derived from the BIP-39 seed under its own label and never from a coin's
 * private key. Reusing a signing key across a payment key and a DNS identity is the kind of
 * cross-protocol reuse that turns a signature bug into a wallet drain, so the two are separated at
 * derivation rather than by convention.
 */
object DnsSignature {

    /** Versioned so a later scheme cannot be mistaken for this one. */
    private const val LABEL = "prism-p2p-dns-ed25519-v1"

    private const val TAG = "P2pDns"

    /** A keypair for this wallet: the Ed25519 seed and its compressed public key. */
    private class Identity(val seed: ByteArray, val publicKey: ByteArray) {
        val publicHex: String get() = publicKey.joinToString("") { "%02x".format(it) }
        /** Short stable id for logs and the settings page. */
        val shortId: String get() = publicHex.take(16)
    }

    @Volatile
    private var cached: Pair<String, Identity>? = null

    /** Whether this device has a wallet, and therefore an identity to sign with. */
    fun available(): Boolean = identity() != null

    /**
     * The identity derived from the current recovery phrase, or null without a wallet.
     *
     * Cached on a fingerprint of the encrypted phrase, like [CloudVault.namespace]: deriving a
     * seed is PBKDF2, and this is read on every record write and every verification. The
     * fingerprint is of the stored ciphertext, so importing or wiping a wallet invalidates it
     * without decrypting anything.
     */
    fun identityOrNull(): Pair<ByteArray, ByteArray>? = identity()?.let { it.seed to it.publicKey }

    fun publicKeyHex(): String? = identity()?.publicHex

    private fun identity(): Identity? {
        val words = WalletVault.phrase() ?: return null
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(words.joinToString(" ").toByteArray())
            .joinToString("") { "%02x".format(it) }
        cached?.let { (forPhrase, value) -> if (forPhrase == fingerprint) return value }

        val seed = WalletVault.seed() ?: return null
        val root = hkdf(seed, "prism-cloud-root-v1".toByteArray(), 32)
        val signingSeed = hkdf(root, (LABEL + "-seed").toByteArray(), 32)
        val value = Identity(signingSeed, Ed25519.publicKeyFromSeed(signingSeed))
        cached = fingerprint to value
        return value
    }

    /**
     * The bytes a signature covers.
     *
     * EVERY FIELD THAT AFFECTS RESOLUTION, in a fixed order, with alternates sorted. A timestamp is
     * included so an old record cannot be replayed over a newer one, and the alternates because a
     * host list is as much a part of where a name points as its primary address.
     */
    fun canonical(domain: String, ip: String, timestamp: Long, alternates: Collection<String>): ByteArray =
        buildString {
            append(domain.trim().lowercase())
            append('\n')
            append(ip)
            append('\n')
            append(timestamp)
            append('\n')
            append(alternates.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(","))
        }.toByteArray()

    /** Base64 signature over [canonical], or null without a wallet. */
    fun sign(domain: String, ip: String, timestamp: Long, alternates: Collection<String>): String? {
        val id = identity() ?: return null
        val signature = Ed25519.sign(id.seed, canonical(domain, ip, timestamp, alternates))
        return Base64.getEncoder().encodeToString(signature)
    }

    /**
     * Verifies a record against the public key its sender published.
     *
     * Returns false for a malformed key or signature, which is the right answer for a record: an
     * unreadable claim is not a claim. A record with NO signature is not passed through here --
     * the caller decides whether unsigned records are allowed, because refusing them outright
     * would cut off every peer running a build that predates this.
     */
    fun verify(
        publicKeyHex: String,
        signatureBase64: String,
        domain: String,
        ip: String,
        timestamp: Long,
        alternates: Collection<String>,
    ): Boolean {
        if (publicKeyHex.length != 64 || signatureBase64.isBlank()) return false
        val key = runCatching {
            ByteArray(32) { index ->
                publicKeyHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return false
        val signature = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?: return false
        return Ed25519.verify(key, canonical(domain, ip, timestamp, alternates), signature)
    }

    /** A short, human-readable id for the current identity, or empty without a wallet. */
    fun shortId(): String = identity()?.shortId.orEmpty()

    private fun hkdf(input: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        }
        val prk = extract.doFinal(input)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            expand.reset()
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val take = minOf(previous.size, length - produced)
            previous.copyInto(out, produced, 0, take)
            produced += take
            counter++
        }
        return out
    }

    /** Logged once when a record cannot be verified, so the reason is visible without a stack trace. */
    fun explainRejection(domain: String, publicKeyHex: String): String =
        "Dropped a DNS record for $domain: its signature does not verify against ${publicKeyHex.take(16)}…"
}
