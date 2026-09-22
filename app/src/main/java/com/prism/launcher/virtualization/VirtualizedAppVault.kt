package com.prism.launcher.virtualization

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where a virtualized Android app's APK and data live, and how they are kept encrypted.
 *
 * ## The threat this actually defends against
 *
 * The app being virtualized is the adversary. It is arbitrary third-party code, and the promise is
 * that it cannot read the key protecting its own data at rest, nor anybody else's.
 *
 * That promise is kept by WHERE the code runs, not by anything in this file: a virtualized app runs
 * inside the guest VM, and the guest has no path to the host's app-private storage or to the host's
 * hardware keystore. Encryption here protects the data when the guest is not running -- against
 * another app on the phone, against someone with the device, against a backup. It is not what stops
 * the virtualized app itself, because the VM boundary already did that.
 *
 * Saying which mechanism provides which guarantee matters, because an in-process virtualizer (the
 * VirtualApp approach) would run the app under Prism's own uid, and then nothing in this file would
 * mean anything at all -- the app could simply read the key out of the process it was sharing.
 *
 * ## Two layers, and why there are two
 *
 * 1. A 60-character passphrase of upper case, lower case, digits and symbols, generated once from
 *    [SecureRandom]. It is the credential the data is encrypted with.
 * 2. That passphrase is itself sealed with a key held in the Android Keystore, which is
 *    hardware-backed on any device that has a TEE or StrongBox. The Keystore key is not exportable:
 *    it can be used, never read, not by another app and not by Prism either.
 *
 * A 60-character password stored in a file beside the data it protects would be decoration. The
 * Keystore is what makes it a secret -- and the passphrase is what makes it portable, because a
 * Keystore key cannot leave the device and a user who wants their data on a new phone needs
 * something that can.
 */
object VirtualizedAppVault {

    private const val TAG = "PrismVirt"

    /** The Keystore alias that seals [passphrase]. Never changes; a new alias orphans every vault. */
    private const val KEYSTORE_ALIAS = "prism_virtualized_app_vault_v1"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Exactly what was asked for: sixty characters of mixed case, digits and symbols. */
    private const val PASSPHRASE_LENGTH = 60

    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /** PBKDF2 rounds. High enough to cost a brute-forcer, low enough not to stall a launch. */
    private const val KDF_ITERATIONS = 120_000

    private val UPPER = ('A'..'Z').toList()
    private val LOWER = ('a'..'z').toList()
    private val DIGITS = ('0'..'9').toList()

    /**
     * Symbols, chosen to survive being passed through a shell, a properties file and a JSON string.
     * Quotes, backslashes and backticks are left out -- a passphrase that breaks the tool that has
     * to carry it is a passphrase that gets silently truncated somewhere.
     */
    private val SYMBOLS = "!#%&()*+,-./:;<=>?@[]^_{|}~".toList()

    private val ALPHABET = UPPER + LOWER + DIGITS + SYMBOLS

    // ── Layout ─────────────────────────────────────────────────────────────

    /** Everything about virtualized apps, inside Prism's private storage. */
    private fun root(context: Context): File =
        File(context.filesDir, "virtualized").apply { mkdirs() }

    /** One app's home: its backed-up APKs and its encrypted data. */
    fun appDir(context: Context, packageName: String): File =
        File(root(context), sanitize(packageName)).apply { mkdirs() }

    fun apkDir(context: Context, packageName: String): File =
        File(appDir(context, packageName), "apk").apply { mkdirs() }

    fun dataFile(context: Context, packageName: String): File =
        File(appDir(context, packageName), "data.enc")

    private fun sealedPassphraseFile(context: Context): File = File(root(context), "vault.key")

    private fun sanitize(packageName: String): String =
        packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }

    // ── The passphrase ─────────────────────────────────────────────────────

    /**
     * The vault passphrase, generated on first use and sealed in the Keystore thereafter.
     *
     * Returned as a CharArray rather than a String so a caller can wipe it. Strings are immutable
     * and interned by the JVM; a passphrase in one sits in the heap until garbage collection
     * happens to reclaim it, which is exactly the window a heap dump needs.
     */
    fun passphrase(context: Context): CharArray {
        val sealed = sealedPassphraseFile(context)
        if (sealed.isFile) {
            unsealPassphrase(sealed)?.let { return it }
            PrismLogger.logWarning(TAG, "The sealed passphrase could not be opened; making a new one")
        }

        val fresh = generatePassphrase()
        sealPassphrase(sealed, fresh)
        return fresh
    }

    /**
     * Sixty characters, with at least one from each class.
     *
     * Drawn from [SecureRandom] and then checked rather than assembled class by class: taking one of
     * each and shuffling biases the distribution, while rejecting the rare draw that misses a class
     * does not. At sixty characters a redraw is vanishingly unlikely; the loop is there for
     * correctness, not because it is expected to run.
     */
    private fun generatePassphrase(): CharArray {
        val random = SecureRandom()
        while (true) {
            val out = CharArray(PASSPHRASE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.size)] }
            val hasUpper = out.any { it in UPPER }
            val hasLower = out.any { it in LOWER }
            val hasDigit = out.any { it in DIGITS }
            val hasSymbol = out.any { it in SYMBOLS }
            if (hasUpper && hasLower && hasDigit && hasSymbol) return out
        }
    }

    /** The Keystore key that seals the passphrase. Created once, never exportable. */
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately NOT setUserAuthenticationRequired: a virtualized app has to be able to
                // start from a launcher tap, and a key that demands a fingerprint every time would
                // mean an unlock prompt before every app. The data is protected against another app
                // and against an attacker with the file, which is what this layer is for.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun sealPassphrase(target: File, passphrase: CharArray) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
            val sealed = cipher.doFinal(bytes)
            java.util.Arrays.fill(bytes, 0)

            target.outputStream().use { out ->
                out.write(cipher.iv.size)
                out.write(cipher.iv)
                out.write(sealed)
            }
            PrismLogger.logInfo(TAG, "Vault passphrase generated and sealed")
        }.onFailure {
            PrismLogger.logError(TAG, "Could not seal the vault passphrase", it)
        }
    }

    private fun unsealPassphrase(source: File): CharArray? = runCatching {
        source.inputStream().use { input ->
            val ivLength = input.read()
            if (ivLength <= 0 || ivLength > 32) return null
            val iv = ByteArray(ivLength)
            if (input.read(iv) != ivLength) return null
            val sealed = input.readBytes()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(sealed)
            val chars = String(plain, Charsets.UTF_8).toCharArray()
            java.util.Arrays.fill(plain, 0)
            chars
        }
    }.getOrNull()

    // ── Encrypting an app's data ───────────────────────────────────────────

    /**
     * The AES key this app's data is encrypted with.
     *
     * Derived from the passphrase with PBKDF2, salted per package so two apps never share a key --
     * one compromised app's data should not decrypt another's. The salt is the package name, which
     * is public: a salt's job is to make the same passphrase produce different keys, not to be
     * secret.
     */
    private fun dataKey(context: Context, packageName: String): SecretKey {
        val passphrase = passphrase(context)
        try {
            val spec = PBEKeySpec(
                passphrase,
                "prism-vault-$packageName".toByteArray(Charsets.UTF_8),
                KDF_ITERATIONS,
                256,
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            java.util.Arrays.fill(passphrase, ' ')
        }
    }

    /** Encrypts [plain] into [target] with this app's key. */
    fun encryptTo(context: Context, packageName: String, target: File, plain: InputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(context, packageName))
        target.outputStream().use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            javax.crypto.CipherOutputStream(out, cipher).use { encrypted ->
                plain.copyTo(encrypted, 64 * 1024)
            }
        }
    }

    /** Decrypts [source] into [out]. Throws if the file was tampered with -- GCM authenticates. */
    fun decryptFrom(context: Context, packageName: String, source: File, out: OutputStream) {
        source.inputStream().use { input ->
            val ivLength = input.read()
            require(ivLength in 1..32) { "not a vault file" }
            val iv = ByteArray(ivLength)
            require(input.read(iv) == ivLength) { "truncated vault file" }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, dataKey(context, packageName), GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                decrypted.copyTo(out, 64 * 1024)
            }
        }
    }

    // ── Backing up an installed app ────────────────────────────────────────

    data class Backup(val packageName: String, val apks: List<File>, val bytes: Long)

    /**
     * Copies an installed app's APKs into Prism's storage, so the guest can install it.
     *
     * SPLIT APKS ARE COPIED TOO. A modern app from Play is rarely one file -- there is a base and
     * then per-density, per-ABI and per-language splits, and installing the base alone gives an app
     * that launches and then crashes looking for resources that are not there.
     *
     * Skipped when the backup is already present and the same size, so opening a virtualized app for
     * the hundredth time does not copy a few hundred megabytes again.
     */
    fun backUpApk(context: Context, packageName: String): Backup? {
        val info: ApplicationInfo = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return null

        val sources = buildList {
            info.sourceDir?.let { add(File(it)) }
            info.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }

        if (sources.isEmpty()) {
            PrismLogger.logWarning(TAG, "No APK on disk for $packageName")
            return null
        }

        val destination = apkDir(context, packageName)
        val copied = mutableListOf<File>()
        var bytes = 0L

        for (source in sources) {
            val target = File(destination, source.name)
            if (target.isFile && target.length() == source.length()) {
                copied += target
                bytes += target.length()
                continue
            }
            val ok = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
            if (!ok) {
                PrismLogger.logWarning(TAG, "Could not back up ${source.name} for $packageName")
                continue
            }
            copied += target
            bytes += target.length()
        }

        if (copied.isEmpty()) return null
        markReadOnly(copied)
        PrismLogger.logInfo(TAG, "Backed up ${copied.size} APK(s) for $packageName (${bytes shr 20} MB)")
        return Backup(packageName, copied, bytes)
    }

    /**
     * Makes backed-up APKs read-only, which Android 14 requires before they can be loaded.
     *
     * ART refuses to open a dex file the calling app can write to -- "Writable dex file ... is not
     * allowed" -- because a file that can change after it is verified is a way to run unverified
     * code. A freshly copied file is writable by its owner, so every backup needs this before a
     * ClassLoader will touch it.
     *
     * Also applied on load rather than only here, because backups made before this existed are
     * still writable and would fail the same way forever.
     */
    fun markReadOnly(apks: List<File>) {
        for (apk in apks) {
            runCatching {
                apk.setWritable(false, false)
                apk.setReadOnly()
            }
        }
    }

    /** Whether this app has been virtualized before. */
    fun isBackedUp(context: Context, packageName: String): Boolean =
        apkDir(context, packageName).listFiles()?.any { it.extension.equals("apk", true) } == true

    /** Every app that has been virtualized. */
    fun virtualizedPackages(context: Context): List<String> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && isBackedUp(context, it.name) }
            .map { it.name }
            .sorted()

    /** Removes one app's backup and data. Irreversible, which is the point. */
    fun forget(context: Context, packageName: String) {
        runCatching { appDir(context, packageName).deleteRecursively() }
    }
}
