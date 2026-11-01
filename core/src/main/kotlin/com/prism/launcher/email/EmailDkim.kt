package com.prism.launcher.email

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale

/**
 * DKIM signing, so Prism-hosted mail survives a receiving server's authentication check.
 *
 * ## Why this has to exist
 *
 * A message with no DKIM signature and no SPF alignment is what every spam filter is trained to
 * distrust. Running a mail server without it does not just lower deliverability -- it makes the
 * server's own abuse reports meaningless, because there is no way to prove which messages it sent.
 *
 * ## The key belongs to the device
 *
 * Generated on first use and kept in the email settings, sealed with the rest of them. It is not a
 * shared Prism key: a key every installation had would let any installation sign as any other, and
 * the whole point is that a recipient can attribute a message to one domain.
 *
 * ## What is not implemented, stated plainly
 *
 * Verification of INBOUND signatures is not done here. That needs the sender's public key from
 * their DNS, canonicalisation of arbitrary headers, and a policy for what to do when a signature
 * is broken -- and getting it subtly wrong is worse than not claiming it. Inbound mail is scored
 * by [EmailSecurity] and the receiving user sees the score.
 */
object EmailDkim {

    data class Keys(val privateKey: String, val publicKey: String)

    fun ensureKeys(config: EmailConfig): Keys {
        if (config.dkimPrivateKey.isNotBlank() && config.dkimPublicKey.isNotBlank()) {
            return Keys(config.dkimPrivateKey, config.dkimPublicKey)
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keys = Keys(
            privateKey = Base64.getEncoder().encodeToString(pair.private.encoded),
            publicKey = Base64.getEncoder().encodeToString(pair.public.encoded),
        )
        config.copy(dkimPrivateKey = keys.privateKey, dkimPublicKey = keys.publicKey).save()
        return keys
    }

    /**
     * Returns the `DKIM-Signature` header value for a message, or null when signing is impossible.
     *
     * [headers] must be the exact header lines that will be written, in order, and [body] the exact
     * body. Signing anything other than what is sent is the classic DKIM bug: the signature covers
     * the values in memory while the wire carries the values after a later transform.
     */
    fun sign(
        headers: List<Pair<String, String>>,
        body: String,
        config: EmailConfig,
        domain: String,
    ): String? {
        val keys = ensureKeys(config)
        if (domain.isBlank()) return null
        val signedNames = headers.map { it.first.lowercase(Locale.ROOT) }
            .filter { it in SIGNED_HEADERS }
            .distinct()
        if ("from" !in signedNames) return null

        val bodyHash = sha256Base64(canonicalBody(body))
        val timestamp = System.currentTimeMillis() / 1000
        val value = buildString {
            append("v=1; a=rsa-sha256; c=relaxed/simple; d=").append(domain.trimEnd('.'))
            append("; s=").append(config.dkimSelector.ifBlank { "prism" })
            append("; t=").append(timestamp)
            append("; h=").append(signedNames.joinToString(":"))
            append("; bh=").append(bodyHash)
            append("; b=")
        }

        val canonicalHeaders = headers
            .filter { it.first.lowercase(Locale.ROOT) in signedNames }
            .joinToString("") { (name, headerValue) -> canonicalHeader(name, headerValue) }

        // The signature header is signed with an empty b=, as the specification requires. Signing
        // the completed value would sign the signature itself.
        val signingInput = canonicalHeaders + canonicalHeader("DKIM-Signature", value)
        val signature = runCatching {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.privateKey)))
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(signingInput.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(sign())
            }
        }.getOrNull() ?: return null
        return value + signature
    }

    private val SIGNED_HEADERS = setOf("from", "to", "cc", "subject", "date", "message-id", "mime-version")

    private fun canonicalHeader(name: String, value: String): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        return name.lowercase(Locale.ROOT) + ":" + clean + "\r\n"
    }

    /** Simple body canonicalisation: CRLF line endings and no trailing empty lines. */
    private fun canonicalBody(body: String): String {
        val normalised = body.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n")
            .joinToString("\r\n")
        return normalised.trimEnd('\r', '\n') + "\r\n"
    }

    private fun sha256Base64(value: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
        )

    /** The TXT record the selector needs. Shown on the server's DNS tab. */
    fun dnsRecord(config: EmailConfig, domain: String): String {
        val keys = ensureKeys(config)
        return "${config.dkimSelector}._domainkey.$domain IN TXT \"v=DKIM1; k=rsa; p=${keys.publicKey}\""
    }
}
