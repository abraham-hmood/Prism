package com.prism.desktop.wallet

import com.prism.core.PrismPlatform
import com.prism.launcher.wallet.WalletCipher
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where a desktop keeps the key that protects the recovery phrase. PHASE 81.
 *
 * ## The rule this phase exists to honour
 *
 * `WalletVault` refuses to store a phrase unless a cipher is installed, and there is deliberately no
 * plaintext fallback. The plan is equally explicit about what must NOT be built:
 *
 *   > DO NOT ship a key derived from something sitting on disk next to the ciphertext. That protects
 *   > against nothing while looking like it protects against everything.
 *
 * So there are exactly two acceptable answers, and this implements both in the order the plan gives.
 *
 * ## First choice: the operating system's own keychain
 *
 *   WINDOWS   DPAPI (`CryptProtectData`). The key is derived from the user's logon credentials and held
 *             by the OS; a copy of the data directory on another machine, or read by another user on
 *             this one, is undecryptable. That is the closest a PC gets to the Android Keystore.
 *   macOS     the login keychain, through the `security` command. Same guarantee, different door.
 *   LINUX     libsecret through `secret-tool`, which is what GNOME Keyring and KWallet both back.
 *
 * ## Second choice: a passphrase the user sets
 *
 * When no keychain is available — a headless Linux box, a session with no keyring daemon — the vault
 * can be unlocked by a passphrase stretched with PBKDF2. It is WEAKER, because it is only as good as
 * what somebody chose, and it is a decision the user makes rather than a silent downgrade: nothing is
 * stored until [usePassphrase] is called.
 *
 * ## What happens when neither is set up
 *
 * Encryption fails, and the vault refuses to store anything. That is the designed behaviour and it is
 * better than the alternative — a wallet that appears to work and whose phrase is readable by anything
 * that can open a file.
 */
class DesktopWalletCipher(private val dataDir: File) : WalletCipher {

    private companion object {
        const val TAG = "PrismWalletCipher"

        /** Marks which route produced a given ciphertext, so a stored value can be read back. */
        const val PREFIX_KEYCHAIN = "k1:"
        const val PREFIX_PASSPHRASE = "p1:"

        const val KEYCHAIN_SERVICE = "PrismWallet"
        const val KEYCHAIN_ACCOUNT = "recovery-phrase-key"

        const val PBKDF2_ROUNDS = 310_000
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val NONCE_BYTES = 12
        const val SALT_BYTES = 16
    }

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private val random = SecureRandom()

    /** Set by [usePassphrase] for this session only. Never written anywhere. */
    @Volatile private var sessionKey: ByteArray? = null

    // ── What this machine can do ───────────────────────────────────────────

    enum class Backing { DPAPI, MAC_KEYCHAIN, LIBSECRET, PASSPHRASE, NONE }

    /**
     * Which route is available, preferring the keychain.
     *
     * Probed rather than assumed: `secret-tool` is often absent, and a Windows session can be one
     * where DPAPI is unavailable (a service account with no profile loaded).
     */
    fun backing(): Backing = when {
        windows && dpapiWorks() -> Backing.DPAPI
        mac && commandExists("security") -> Backing.MAC_KEYCHAIN
        !windows && !mac && commandExists("secret-tool") -> Backing.LIBSECRET
        sessionKey != null -> Backing.PASSPHRASE
        else -> Backing.NONE
    }

    fun describe(): String = when (backing()) {
        Backing.DPAPI ->
            "Windows DPAPI. The key comes from your Windows sign-in, so a copy of Prism's data " +
                "folder on another machine cannot be decrypted."

        Backing.MAC_KEYCHAIN ->
            "The macOS login keychain. The key is held by the keychain and released to Prism only " +
                "while you are signed in."

        Backing.LIBSECRET ->
            "The system keyring, through libsecret. The key is held by the keyring daemon rather " +
                "than by Prism."

        Backing.PASSPHRASE ->
            "A passphrase you set this session, stretched with PBKDF2. Weaker than a keychain and " +
                "only as strong as what you chose — and it has to be entered again after a restart."

        Backing.NONE ->
            "Nothing. No keychain was found and no passphrase has been set, so the wallet will " +
                "refuse to store a recovery phrase rather than write one somewhere readable."
    }

    /**
     * Unlocks with a passphrase, for a machine with no keychain.
     *
     * The derived key lives in memory for this session only. A key written to disk beside the
     * ciphertext is the thing this whole class exists not to do.
     */
    fun usePassphrase(passphrase: CharArray): Boolean {
        if (passphrase.size < 8) return false
        val salt = passphraseSalt()
        sessionKey = runCatching {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS))
                .encoded
        }.getOrNull()
        java.util.Arrays.fill(passphrase, '\u0000')
        return sessionKey != null
    }

    fun forgetPassphrase() {
        sessionKey?.let { java.util.Arrays.fill(it, 0) }
        sessionKey = null
    }

    // ── WalletCipher ───────────────────────────────────────────────────────

    override fun encrypt(plaintext: String): String {
        val keychain = protectWithKeychain(plaintext.toByteArray())
        if (keychain != null) return PREFIX_KEYCHAIN + Base64.getEncoder().encodeToString(keychain)

        val key = sessionKey
        if (key != null) return PREFIX_PASSPHRASE + sealWithKey(plaintext.toByteArray(), key)

        // THROWN, not returned empty. WalletVault stores whatever encrypt returns, so a silent
        // failure here would write an empty string over somebody's phrase.
        throw IllegalStateException(
            "Prism has nowhere safe to keep the wallet key on this machine. " + describe()
        )
    }

    override fun decrypt(ciphertext: String): String? = when {
        ciphertext.startsWith(PREFIX_KEYCHAIN) -> runCatching {
            unprotectWithKeychain(Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX_KEYCHAIN)))
                ?.toString(Charsets.UTF_8)
        }.getOrNull()

        ciphertext.startsWith(PREFIX_PASSPHRASE) -> sessionKey?.let { key ->
            openWithKey(ciphertext.removePrefix(PREFIX_PASSPHRASE), key)
        }

        // Anything else is from a build that stored it differently, or is corrupt. Returning null is
        // right: the vault treats that as "no wallet" rather than crashing.
        else -> null
    }

    // ── The keychain routes ────────────────────────────────────────────────

    private fun protectWithKeychain(plain: ByteArray): ByteArray? = when {
        windows -> dpapi(plain, protect = true)
        mac || !windows -> keyringSeal(plain)
        else -> null
    }

    private fun unprotectWithKeychain(sealed: ByteArray): ByteArray? = when {
        windows -> dpapi(sealed, protect = false)
        mac || !windows -> keyringOpen(sealed)
        else -> null
    }

    /**
     * Windows DPAPI, through JNA's typed Crypt32 binding.
     *
     * Per-USER rather than per-machine: `CryptProtectData` with no machine flag ties the ciphertext to
     * the logged-in account, so another account on the same PC cannot read it either.
     */
    private fun dpapi(data: ByteArray, protect: Boolean): ByteArray? = runCatching {
        if (protect) {
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data)
        } else {
            com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data)
        }
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "DPAPI failed: " + it.message)
        null
    }

    private fun dpapiWorks(): Boolean = runCatching {
        val probe = "prism".toByteArray()
        val sealed = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(probe)
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(sealed).contentEquals(probe)
    }.getOrDefault(false)

    /**
     * macOS and Linux: a random key held by the keychain, used to seal the phrase here.
     *
     * The keychain holds a KEY rather than the phrase itself, for one practical reason: both tools are
     * awkward with large secrets and with binary, and a 32-byte key encodes to a short base64 string
     * that neither mangles. The phrase is then sealed with AES-GCM in this process.
     */
    private fun keyringSeal(plain: ByteArray): ByteArray? {
        val key = keyringKey(create = true) ?: return null
        return sealWithKey(plain, key).toByteArray()
    }

    private fun keyringOpen(sealed: ByteArray): ByteArray? {
        val key = keyringKey(create = false) ?: return null
        return openWithKey(sealed.toString(Charsets.UTF_8), key)?.toByteArray()
    }

    /** Reads the stored key, creating one the first time. */
    private fun keyringKey(create: Boolean): ByteArray? {
        val existing = if (mac) macKeychainRead() else secretToolRead()
        if (existing != null) return runCatching { Base64.getDecoder().decode(existing) }.getOrNull()
        if (!create) return null

        val fresh = ByteArray(32).also { random.nextBytes(it) }
        val encoded = Base64.getEncoder().encodeToString(fresh)
        val stored = if (mac) macKeychainWrite(encoded) else secretToolWrite(encoded)
        return if (stored) fresh else null
    }

    private fun macKeychainRead(): String? = runCommand(
        listOf("security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", KEYCHAIN_ACCOUNT, "-w"),
    )?.trim()?.takeIf { it.isNotBlank() }

    private fun macKeychainWrite(value: String): Boolean = runCommand(
        listOf(
            "security", "add-generic-password", "-U",
            "-s", KEYCHAIN_SERVICE, "-a", KEYCHAIN_ACCOUNT, "-w", value,
        ),
    ) != null

    private fun secretToolRead(): String? = runCommand(
        listOf("secret-tool", "lookup", "service", KEYCHAIN_SERVICE, "account", KEYCHAIN_ACCOUNT),
    )?.trim()?.takeIf { it.isNotBlank() }

    private fun secretToolWrite(value: String): Boolean = runCatching {
        val process = ProcessBuilder(
            "secret-tool", "store", "--label=Prism wallet key",
            "service", KEYCHAIN_SERVICE, "account", KEYCHAIN_ACCOUNT,
        ).redirectErrorStream(true).start()
        // secret-tool takes the secret on stdin rather than as an argument, which is the point: an
        // argument would be visible in the process list to every other process on the machine.
        process.outputStream.use { it.write(value.toByteArray()) }
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    // ── AES-GCM, used by the passphrase and keyring routes ─────────────────

    private fun sealWithKey(plain: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
    }

    private fun openWithKey(encoded: String, key: ByteArray): String? = runCatching {
        val raw = Base64.getDecoder().decode(encoded)
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val body = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        cipher.doFinal(body).toString(Charsets.UTF_8)
    }.getOrNull()

    /**
     * The salt for a passphrase-derived key.
     *
     * ON DISK, and that is fine: a salt is not a secret. It exists so the same passphrase on two
     * machines produces different keys, and so a precomputed table is useless. The KEY is never
     * written.
     */
    private fun passphraseSalt(): ByteArray {
        val file = File(dataDir, "wallet-kdf.salt")
        if (file.isFile && file.length() == SALT_BYTES.toLong()) {
            return runCatching { file.readBytes() }.getOrElse { ByteArray(SALT_BYTES) }
        }
        val fresh = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        runCatching { file.parentFile?.mkdirs(); file.writeBytes(fresh) }
        return fresh
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun commandExists(name: String): Boolean =
        runCommand(if (windows) listOf("where", name) else listOf("which", name))
            ?.lineSequence()?.firstOrNull()?.isNotBlank() == true

    private fun runCommand(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0) output else null
    }.getOrNull()
}
