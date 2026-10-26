package com.prism.launcher.vpn

import android.content.Context
import com.prism.core.MeshTls
import com.prism.launcher.PrismLogger
import java.io.File
import java.security.KeyPair
import java.security.cert.X509Certificate

/**
 * On-device TLS for the Prism Mesh, as seen by Android's hosting code.
 *
 * ## Why this is now a wrapper and not a certificate authority
 *
 * There used to be TWO certificate authorities in this project: this file generated and stored an
 * EC root CA with BouncyCastle, and `:core`'s `MeshTls` generated another one for the desktop. Both
 * minted leaves, both served `.p2p` names, and they agreed on none of it -- different subject
 * names, different file names, different validity handling. A mesh serves content from a phone and
 * a laptop to browsers on both, so "TLS works within one platform" was the honest description of
 * that arrangement.
 *
 * The authority now lives in [MeshTls], which both platforms compile. This class does the two
 * Android-specific things that remain: it decides where the CA lives (the no-backup directory, not
 * `filesDir`, which is eligible for Auto Backup) and it migrates an existing Android CA into the
 * shared layout so a device that already issued certificates does not silently replace its root.
 *
 * ## The key at rest
 *
 * The old implementation wrapped the private key with an AES key in Android Keystore. The shared
 * implementation stores a plaintext PKCS#8 file in the no-backup directory, which is the desktop's
 * model and is a real reduction for Android. It is recorded here rather than glossed over: the
 * directory is app-private, it is excluded from backup, and the CA signs only names this device
 * chooses to serve. A keystore-wrapped key would be better, and moving it back would mean teaching
 * `:core` an optional key-wrapper seam that the desktop has no use for. That is the trade, and it
 * belongs in the source rather than in a comment nobody reads.
 */
object PrismCertificateManager {

    private const val TAG = "PrismCertManager"

    private const val CA_KEY_FILE = "prism_ca_key.der.enc"
    private const val CA_CERT_FILE = "prism_ca_cert.der"
    private const val LEGACY_KEY_FILE = "prism_ca_key.der"

    /** Where [MeshTls] keeps its CA: one layout, the same names on both platforms. */
    private fun sharedDir(context: Context): File =
        File(context.noBackupFilesDir, "mesh-tls")

    /**
     * Loads the device CA into [MeshTls], generating one on first use.
     *
     * Migrates the Android-format CA when it is the only one present. The migration reads the old
     * encrypted key through the Keystore exactly as the old code did, then writes it into the shared
     * directory as PKCS#8. A failure leaves the old files untouched and lets [MeshTls] create a new
     * CA, which is what the old code did when its key would not decrypt.
     */
    fun init(context: Context) {
        val target = sharedDir(context)
        val sharedKey = File(target, "prism_ca_key.der")
        val sharedCert = File(target, "prism_ca_cert.der")

        if (!sharedKey.isFile || !sharedCert.isFile) {
            migrateLegacy(context, target)
        }

        val ok = MeshTls.install(target)
        if (ok) {
            PrismLogger.logInfo(TAG, "Mesh CA loaded from ${target.absolutePath}")
        } else {
            PrismLogger.logError(TAG, "Mesh CA could not be installed; .p2p hosting will fall back to no TLS")
        }
    }

    /**
     * Moves an Android CA into the shared layout, once.
     *
     * The encrypted key is read with the Keystore-wrapped AES key the old code created. If that
     * Keystore entry is gone -- a restore onto a new device, or a cleared keystore -- the key is
     * unreadable and there is nothing to migrate; regenerating is the only option and is what
     * [MeshTls.install] will do.
     */
    private fun migrateLegacy(context: Context, target: File) {
        val noBackupKey = File(context.noBackupFilesDir, CA_KEY_FILE)
        val noBackupCert = File(context.noBackupFilesDir, CA_CERT_FILE)
        val legacyKey = File(context.filesDir, LEGACY_KEY_FILE)
        val legacyCert = File(context.filesDir, CA_CERT_FILE)

        val keyFile = when {
            noBackupKey.isFile -> noBackupKey
            legacyKey.isFile -> legacyKey
            else -> return
        }
        val certFile = when {
            noBackupCert.isFile -> noBackupCert
            legacyCert.isFile -> legacyCert
            else -> return
        }

        val plaintext = runCatching { decryptLegacyKey(keyFile.readBytes()) }.getOrElse { error ->
            PrismLogger.logWarning(
                TAG,
                "Existing Android CA could not be opened (${error.message}); a new CA will be generated",
            )
            return
        }

        runCatching {
            target.mkdirs()
            File(target, "prism_ca_key.der").writeBytes(plaintext)
            certFile.copyTo(File(target, "prism_ca_cert.der"), overwrite = true)
            // Only removed after both writes succeed. Deleting first would destroy the only copy of
            // a CA some phone may already have installed.
            noBackupKey.delete()
            legacyKey.delete()
            noBackupCert.delete()
            legacyCert.delete()
            PrismLogger.logInfo(TAG, "Migrated the Android CA into the shared mesh TLS layout")
        }.onFailure {
            PrismLogger.logWarning(TAG, "CA migration failed: ${it.message}")
        }
    }

    /**
     * Unwraps a key written by the old implementation.
     *
     * A plaintext PKCS#8 key starts with a SEQUENCE (0x30); the wrapped form starts with a one-byte
     * IV length. Same detection as before, so both old files remain readable.
     */
    private fun decryptLegacyKey(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes[0].toInt() == 0x30) return bytes
        val ivLength = bytes[0].toInt() and 0xff
        require(ivLength > 0 && bytes.size > 1 + ivLength) { "not a wrapped key" }

        val store = java.security.KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val wrapping = store.getKey("prism_mesh_ca_wrap", null)
            ?: error("the Android Keystore no longer holds the wrapping key")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            wrapping,
            javax.crypto.spec.GCMParameterSpec(128, bytes, 1, ivLength),
        )
        return cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength)
    }

    /**
     * A leaf for [domain], signed by the same CA the desktop uses.
     *
     * The issuer is returned alongside the leaf because `PrismSslManager` builds the presented
     * chain from it, and because a client that trusts the root needs the intermediate path.
     */
    fun generateLeafCertificate(domain: String): KeyPairWithCert? {
        val leaf = MeshTls.leafFor(domain) ?: run {
            PrismLogger.logWarning(TAG, "No mesh CA is loaded, so $domain cannot be served over TLS")
            return null
        }
        return KeyPairWithCert(leaf.keyPair, leaf.certificate, leaf.issuer)
    }

    /** Writes the root CA as PEM to Downloads, for the user to install. Returns its path. */
    fun exportRootCA(context: Context): String? {
        val downloads = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        val file = File(downloads, "Prism_Mesh_Root_CA.crt")
        return MeshTls.exportRootCa(file)?.absolutePath
    }

    data class KeyPairWithCert(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val issuer: X509Certificate,
    )
}
