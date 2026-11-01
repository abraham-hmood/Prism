package com.prism.launcher.email

import com.prism.launcher.wallet.WalletVault
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals mail at rest with a key derived from the wallet's recovery phrase.
 *
 * WHY THE WALLET AND NOT THE KEYSTORE. The requirement is that the mailbox is readable on the
 * user's other devices. A Keystore key is per device and cannot leave it, so a mailbox sealed with
 * one would be a mailbox that exists only where it was written. The recovery phrase is the one
 * secret the user already has on every device, and it is already the root of Prism's mesh
 * encryption -- reusing it means a restored device can read mail that arrived before it existed.
 *
 * WHAT THAT COSTS, stated rather than hidden. Anyone holding the phrase can read the mailbox, and
 * losing the phrase loses it. That is the same trade every other wallet-derived feature in Prism
 * makes, and the reason no mail is written at all without a wallet: a plaintext mailbox on disk
 * would be worse than an unavailable one.
 *
 * AES-GCM, not CBC: a mailbox is edited in place, and CBC without a MAC lets a file be altered
 * without detection. GCM authenticates the whole blob, so a truncated or edited mailbox fails to
 * open instead of loading as a shorter, subtly wrong mailbox.
 */
object EmailVault {

    private const val PREFIX = "prism-mail-v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** Set by tests and by a profile import before a wallet is unlocked. Never written to disk. */
    @Volatile
    var phraseOverride: (() -> String?)? = null

    private fun phrase(): String? =
        phraseOverride?.invoke() ?: WalletVault.phrase()?.joinToString(" ")

    fun unlocked(): Boolean = !phrase().isNullOrBlank()

    private fun key(): SecretKeySpec? {
        val phrase = phrase()?.takeIf { it.isNotBlank() } ?: return null
        // A domain separator, so this key is not the same key as any other wallet-derived feature.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("prism-email-v1|" + phrase).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /** Seals [plaintext], or returns null when there is no wallet to derive a key from. */
    fun seal(plaintext: String): String? {
        val key = key() ?: return null
        return runCatching {
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + sealed.size)
            iv.copyInto(packed)
            sealed.copyInto(packed, iv.size)
            PREFIX + Base64.getEncoder().encodeToString(packed)
        }.getOrNull()
    }

    /** Opens a blob made by [seal]. Null for a missing wallet, a wrong phrase, or a damaged file. */
    fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        val key = key() ?: return null
        return runCatching {
            val packed = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            if (packed.size <= IV_BYTES) return null
            val iv = packed.copyOfRange(0, IV_BYTES)
            val body = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Decrypts a stored account password. Empty when sealed with another wallet. */
    fun passwordOf(account: EmailAccount): String =
        if (account.password.startsWith(PREFIX)) open(account.password).orEmpty() else account.password
}
