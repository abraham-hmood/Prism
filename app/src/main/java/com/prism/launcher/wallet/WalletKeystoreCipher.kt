package com.prism.launcher.wallet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.WalletCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the recovery phrase with a key held in the Android Keystore.
 *
 * THE KEY NEVER ENTERS THE APP'S MEMORY. Keystore keys live in the system keystore daemon, backed
 * by the device's secure element or TEE where one exists; this process holds a handle and asks the
 * daemon to perform each operation. So a stolen phone with an unlocked bootloader, a backup
 * extraction, or a bug that dumps this app's private storage yields ciphertext and nothing else --
 * the key is not in the file, not in the APK, and not derivable from either.
 *
 * NOT REQUIRING USER AUTHENTICATION PER OPERATION, deliberately. `setUserAuthenticationRequired`
 * would demand a biometric prompt every time the wallet list needs an address to display, which in
 * practice trains people to approve prompts without reading them. The device lock screen already
 * gates access to the app; the phrase is additionally never shown without an explicit action.
 *
 * AES-GCM, so tampering is detected rather than silently decrypting to garbage. A corrupted or
 * substituted ciphertext fails the authentication tag and [decrypt] returns null, which callers
 * treat as "no wallet" rather than as an empty phrase.
 */
class WalletKeystoreCipher : WalletCipher {

    private companion object {
        const val TAG = "PrismWallet"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "prism_wallet_seed_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Randomised IV per operation is required for GCM; letting the caller supply one
                // is how GCM implementations get catastrophically reused nonces.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // The IV is not a secret and must travel with the ciphertext, or nothing can decrypt it.
        return android.util.Base64.encodeToString(
            cipher.iv + ciphertext, android.util.Base64.NO_WRAP
        )
    }

    override fun decrypt(ciphertext: String): String? = try {
        val raw = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES)
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // Reaching here means the ciphertext failed authentication or the key is gone -- the
        // latter happens if the user removes their screen lock on some devices, which invalidates
        // keystore entries. Either way the wallet is unrecoverable from this device alone, which
        // is exactly what the recovery phrase is for.
        PrismLogger.logError(TAG, "Wallet decryption failed; the phrase is needed to restore", e)
        null
    }
}
