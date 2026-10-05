package com.prism.desktop.vm

import com.prism.core.PrismPlatform
import com.prism.desktop.wallet.DesktopWalletCipher
import com.prism.launcher.virtualapp.VaultArchive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted store a virtualized app's data lives in. PHASE 111.
 *
 * ## THE VAULT COMES FIRST, WHICH IS WHAT THE PHASE SAYS
 *
 * "THE VAULT COMES FIRST REGARDLESS. VaultArchive and VaultPreferences encrypt an app's data at rest
 * with a 60-character passphrase the virtualized app can never read, and that requirement holds
 * identically on desktop. Porting the vault is useful on its own and is a prerequisite for running
 * anything."
 *
 * So this exists before any dex is loaded, and nothing in the runtime touches an app's data except
 * through it.
 *
 * ## The 60-character passphrase, and why it is checked rather than assembled
 *
 * Sixty characters drawn from upper case, lower case, digits and symbols. Drawn uniformly from the
 * whole alphabet and then CHECKED for one of each class, rather than assembled by taking one of each
 * and shuffling: assembly biases the distribution -- the first four positions are no longer uniform --
 * and over sixty characters a uniform draw contains all four classes with overwhelming probability
 * anyway, so the loop almost never runs twice.
 *
 * ## WHAT THE APP CAN AND CANNOT REACH
 *
 * On Android the passphrase lives in a separate process with its own uid, so the virtualized app
 * cannot read it even in principle. A DESKTOP HAS NO SUCH BOUNDARY HERE AND THIS SAYS SO: Prism's
 * runtime and the app's code are in one JVM, so the honest claim is narrower -- the passphrase is
 * never handed to the app's own code, never placed in a field the app can reach, and never written
 * anywhere in plaintext. It is not protected from a hostile app that reflects over Prism's own
 * classes. Making that true would need the runtime in a second process, which is the next piece of
 * work and is named here rather than glossed over.
 *
 * ## The passphrase is wrapped by the OS, not stored
 *
 * Through [DesktopWalletCipher], which is DPAPI on Windows and the keychain or libsecret elsewhere --
 * the same component the wallet seed uses, for the same reason: a copy of Prism's data directory
 * taken to another machine does not decrypt.
 */
object DesktopAppVault {

    private const val TAG = "PrismVirtualApp"

    private const val PASSPHRASE_LENGTH = 60
    private const val PASSPHRASE_FILE = "passphrase.enc"

    /** PBKDF2 rounds. 210,000 is OWASP's 2023 figure for SHA-256. */
    private const val KDF_ROUNDS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val SALT_BYTES
        = 16

    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!#%&*+-=?@^_~"
    private val ALPHABET = UPPER + LOWER + DIGITS + SYMBOLS

    // ── Layout ──────────────────────────────────────────────────────────────

    /**
     * The OS-backed cipher that wraps the passphrase.
     *
     * Built lazily and held, because `DesktopWalletCipher` is a class rather than an object and
     * constructing one per call would re-probe DPAPI each time. It holds no secret of its own -- the
     * key stays with the OS -- so holding it is not holding a credential.
     */
    private val cipher: DesktopWalletCipher by lazy {
        DesktopWalletCipher(PrismPlatform.host.dataDir())
    }

    private fun root(): File =
        File(PrismPlatform.host.dataDir(), "virtualapps").apply { mkdirs() }

    fun appDir(packageName: String): File = File(root(), packageName).apply { mkdirs() }

    /** The app's own APK, kept so it can be re-run without re-importing. */
    fun apkFile(packageName: String): File = File(appDir(packageName), "base.apk")

    /** The sealed data archive. The only form the app's data exists in at rest. */
    fun dataFile(packageName: String): File = File(appDir(packageName), "data.sealed")

    /**
     * Where the data is unsealed to while the app runs.
     *
     * Under the CACHE directory rather than the data directory, deliberately. The live copy is
     * plaintext, and cache is the thing an OS or a user clears -- so a crash that leaves it behind
     * loses an app's unsaved state rather than leaving plaintext in a directory that looks permanent.
     */
    fun liveDir(packageName: String): File =
        File(File(PrismPlatform.host.cacheDir(), "virtualapps-live"), packageName)

    fun virtualizedPackages(): List<String> =
        root().listFiles()?.filter { it.isDirectory && File(it, "base.apk").isFile }
            ?.map { it.name }?.sorted().orEmpty()

    fun isImported(packageName: String): Boolean = apkFile(packageName).isFile

    // ── The passphrase ──────────────────────────────────────────────────────

    /**
     * The vault passphrase, created once.
     *
     * A `CharArray` rather than a `String` so the caller can clear it: a String stays in the heap
     * until the GC decides otherwise and shows up in a dump. Not that this is a strong guarantee on a
     * JVM -- see the class comment about what the boundary actually is -- but it costs nothing and is
     * the right habit.
     *
     * NOT CACHED IN A FIELD. A held copy is a copy the app's code could reach by reflecting over this
     * object, and the whole point is that it cannot. The cost is a keychain unwrap per seal or unseal,
     * which happens twice per app launch.
     */
    private fun passphrase(): CharArray {
        val file = File(root(), PASSPHRASE_FILE)
        if (file.isFile) {
            val wrapped = runCatching { file.readText() }.getOrNull()
            val plain = wrapped?.let { cipher.decrypt(it) }
            if (plain != null && plain.length == PASSPHRASE_LENGTH) {
                return plain.toCharArray()
            }
            // A passphrase that will not unwrap means the OS key is gone -- a different Windows
            // account, a reinstalled OS, a cleared keychain. The data sealed with it is
            // unrecoverable, and generating a new one silently would make that look like corruption
            // instead. Said, loudly, and refused.
            PrismPlatform.log.error(
                TAG,
                "The vault passphrase will not unwrap. Any sealed app data is unrecoverable: the " +
                    "key came from this machine's own keychain and that key is no longer available.",
                null,
            )
            throw IllegalStateException(
                "The vault passphrase cannot be unwrapped on this machine.",
            )
        }

        val generated = generate()
        val wrapped = cipher.encrypt(String(generated))
        file.parentFile?.mkdirs()
        file.writeText(wrapped)
        // 0600 where the filesystem supports it. On Windows the DPAPI wrapping is what protects it;
        // on Linux the wrapping may be weaker (see DesktopWalletCipher.backing) and the mode matters.
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        PrismPlatform.log.success(
            TAG,
            "Generated a " + PASSPHRASE_LENGTH + "-character vault passphrase, wrapped by " +
                cipher.describe(),
        )
        return generated
    }

    /**
     * Sixty characters with at least one of each class.
     *
     * Drawn uniformly and then checked -- see the class comment for why that is the right way round.
     * The alphabets omit look-alikes (`I`, `l`, `1`, `O`, `0`) because this passphrase is shown to the
     * user once for backup, and a character nobody can transcribe is worse than a slightly smaller
     * alphabet: 69 characters over 60 positions is still 366 bits.
     */
    private fun generate(): CharArray {
        val random = SecureRandom()
        while (true) {
            val out = CharArray(PASSPHRASE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            val text = String(out)
            if (text.any { it in UPPER } && text.any { it in LOWER } &&
                text.any { it in DIGITS } && text.any { it in SYMBOLS }
            ) {
                return out
            }
        }
    }

    /**
     * The passphrase, for showing the user once so they can write it down.
     *
     * The one place it leaves this object, and it exists because a vault whose key is only in a
     * machine's keychain is a vault that dies with the machine. A page that calls this says what it
     * is showing.
     */
    fun revealForBackup(): String = String(passphrase())

    fun describeProtection(): String =
        PASSPHRASE_LENGTH.toString() + " random characters, wrapped by " +
            cipher.describe() +
            ". The app's own code is never given it and it is never written in plaintext — but " +
            "Prism's runtime and the app share one JVM here, so a hostile app that reflected over " +
            "Prism's classes could reach it. Closing that needs the runtime in a second process."

    // ── Sealing ─────────────────────────────────────────────────────────────

    /**
     * Encrypts [plain] into [target].
     *
     * Layout: salt, nonce, then the GCM ciphertext with its tag. The salt is per-FILE rather than
     * per-install, so two apps' vaults never share a derived key even though they share a passphrase
     * -- which means breaking one does not break the other.
     */
    fun encryptTo(target: File, plain: InputStream) {
        val secret = passphrase()
        try {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(secret, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            }
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { raw ->
                raw.write(salt)
                raw.write(nonce)
                javax.crypto.CipherOutputStream(raw, cipher).use { plain.copyTo(it, 64 * 1024) }
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    /** Decrypts [source] into [out]. Throws if the tag fails, which means tampering or a wrong key. */
    fun decryptFrom(source: File, out: OutputStream) {
        val secret = passphrase()
        try {
            source.inputStream().buffered().use { raw ->
                val salt = ByteArray(SALT_BYTES).also { raw.readNBytes(it, 0, it.size) }
                val nonce = ByteArray(NONCE_BYTES).also { raw.readNBytes(it, 0, it.size) }
                val key = deriveKey(secret, salt)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
                }
                javax.crypto.CipherInputStream(raw, cipher).use { it.copyTo(out, 64 * 1024) }
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun deriveKey(secret: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(secret, salt, KDF_ROUNDS, KEY_BITS)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    // ── Sealing a whole app ─────────────────────────────────────────────────

    /**
     * Unseals an app's data so it can run, or creates an empty live directory for a first run.
     *
     * THE WHOLE TREE IS ONE AUTHENTICATED OBJECT. `VaultArchive` packs it first, because AES-GCM
     * authenticates one message: encrypting file by file would mean one tag per file and no protection
     * over the SHAPE of the directory -- a file could be removed, renamed or swapped between apps
     * without any tag failing.
     */
    fun unseal(packageName: String): File {
        val live = liveDir(packageName)
        // Cleared first. Leftovers from a crashed session would merge with the sealed state and
        // produce a directory that is neither what was sealed nor what the app last wrote.
        runCatching { live.deleteRecursively() }
        live.mkdirs()

        val sealed = dataFile(packageName)
        if (!sealed.isFile) {
            PrismPlatform.log.info(TAG, "First run for " + packageName + ": empty data directory.")
            return live
        }

        val scratch = File.createTempFile("prism-vault-", ".zip")
        try {
            scratch.outputStream().buffered().use { decryptFrom(sealed, it) }
            VaultArchive.unpack(scratch, live)
            PrismPlatform.log.info(TAG, "Unsealed " + packageName + "'s data.")
        } finally {
            runCatching { scratch.delete() }
        }
        return live
    }

    /** Seals the live directory back and removes the plaintext. */
    fun seal(packageName: String) {
        val live = liveDir(packageName)
        if (!live.isDirectory) return
        val scratch = File.createTempFile("prism-vault-", ".zip")
        try {
            VaultArchive.pack(live, scratch)
            // Written to a temporary and moved into place: a seal interrupted halfway through would
            // otherwise leave a truncated archive where the app's only copy of its data used to be.
            val staging = File(dataFile(packageName).parentFile, "data.sealed.part")
            scratch.inputStream().buffered().use { encryptTo(staging, it) }
            if (!staging.renameTo(dataFile(packageName))) {
                staging.copyTo(dataFile(packageName), overwrite = true)
                staging.delete()
            }
            PrismPlatform.log.info(TAG, "Sealed " + packageName + "'s data.")
        } finally {
            runCatching { scratch.delete() }
            // THE PLAINTEXT GOES. Leaving it would make the encryption decorative: the live copy is
            // the whole of the app's data in the clear.
            runCatching { live.deleteRecursively() }
        }
    }

    /** Removes an app and everything sealed for it. */
    fun forget(packageName: String): Boolean {
        runCatching { liveDir(packageName).deleteRecursively() }
        return runCatching { appDir(packageName).deleteRecursively() }.getOrDefault(false)
    }

    fun sealedBytes(packageName: String): Long =
        dataFile(packageName).takeIf { it.isFile }?.length() ?: 0L

    /** Whether an app's plaintext is currently on disk, for a page that should say so. */
    fun isUnsealed(packageName: String): Boolean =
        liveDir(packageName).isDirectory && liveDir(packageName).listFiles()?.isNotEmpty() == true
}
