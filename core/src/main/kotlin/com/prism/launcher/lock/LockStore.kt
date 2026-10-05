package com.prism.launcher.lock

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The lock's credentials, and the one that is not what it looks like.
 *
 * ## Two credentials, one indistinguishable check
 *
 * There is a normal credential and a duress credential. [verify] returns which one was entered, and
 * **both unlock**. That is the entire point: a duress code that refused entry, showed an error, or
 * took a visibly different path would tell the person standing over you that it was a duress code.
 *
 * Everything downstream of that is built the same way -- see [DuressResponder]. The failure mode
 * this design exists to avoid is not "someone guesses the PIN", it is "someone watches the screen
 * while you type it".
 *
 * ## Storage
 *
 * Salted SHA-256, iterated. Not a password-hashing function like Argon2 or scrypt, and worth being
 * honest about why: a four-digit PIN has ten thousand possibilities and no amount of iteration makes
 * that expensive to exhaust for anyone holding the file. The hash protects against a casual read of
 * the file, and nothing here pretends it protects against a determined offline attacker with the
 * device unlocked -- for that, the credential would have to be backed by hardware, which is what
 * Android's own keyguard does and what this deliberately does not replace.
 *
 * ## What this is not
 *
 * Android does not let an app replace the keyguard. This is a lock screen Prism draws, and it is
 * only *the* lock screen if the system lock is set to None or Swipe. The lock settings say so to the
 * user rather than implying a guarantee the platform will not give.
 *
 * ## PHASE 101: THE CREDENTIAL IS SHARED, THE SCOPE OF THE LOCK IS NOT
 *
 * This file moved to :core because a salted hash and a duress check are arithmetic, and the two
 * platforms must agree about them exactly -- a duress code that worked on one and not the other
 * would be worse than no duress code at all.
 *
 * What does NOT port is what locking MEANS. On Android the lock replaces the launcher's own surface.
 * On a PC the OS owns the login screen, and an application claiming to lock the machine while the
 * user can alt-tab past it would be security theatre. The desktop lock locks PRISM -- its window, its
 * wallet, its stored data -- and says so in those words. See the desktop's own lock page.
 */
object LockStore {

    private const val TAG = "PrismLock"
    private const val FILE = "lock/credentials.json"

    private const val ITERATIONS = 20_000

    /** How the user unlocks. Chosen once, at setup. */
    enum class Mechanism { PIN, PASSWORD, PATTERN;

        val label: String get() = when (this) {
            PIN -> "PIN"
            PASSWORD -> "Password"
            PATTERN -> "Pattern"
        }
    }

    /** What [verify] found. */
    enum class Outcome { NORMAL, DURESS, WRONG }

    private fun file() = File(PrismPlatform.host.dataDir(), FILE).apply { parentFile?.mkdirs() }

    // ── State ──────────────────────────────────────────────────────────────

    fun isConfigured(): Boolean = read() != null

    fun mechanism(): Mechanism? = read()?.let { json ->
        runCatching { Mechanism.valueOf(json.optString("mechanism")) }.getOrNull()
    }

    fun hasDuress(): Boolean =
        read()?.optString("duress_hash").orEmpty().isNotBlank()

    /** Whether the lock screen is armed at all. Separate from being configured. */
    fun isEnabled(): Boolean =
        isConfigured() && read()?.optBoolean("enabled", true) != false

    fun setEnabled(enabled: Boolean) {
        val json = read() ?: return
        json.put("enabled", enabled)
        write(json)
    }

    // ── Setting credentials ────────────────────────────────────────────────

    /**
     * Writes the primary credential, replacing any existing configuration.
     *
     * Generates a fresh salt each time. Reusing a salt across a credential change would let anyone
     * holding both files tell that the new credential is or is not the old one.
     */
    fun configure(mechanism: Mechanism, secret: String) {
        val salt = newSalt()
        val json = read() ?: JSONObject()
        json.put("mechanism", mechanism.name)
        json.put("salt", salt)
        json.put("hash", hash(secret, salt))
        json.put("enabled", true)
        json.put("length", secret.length)
        // A duress code set against the old credential's salt would no longer verify, and a stale
        // one that silently stopped working is worse than none.
        json.put("duress_hash", "")
        write(json)
        PrismPlatform.log.info(TAG, "Lock configured: ${mechanism.name}")
    }

    /** How long the primary credential is. Used only to decide when a PIN pad may auto-submit. */
    fun credentialLength(): Int = read()?.optInt("length", 0) ?: 0

    fun setDuress(secret: String): String? {
        val json = read() ?: return "Set the main lock first"
        val salt = json.optString("salt")
        if (salt.isBlank()) return "Set the main lock first"
        if (hash(secret, salt) == json.optString("hash")) {
            return "The duress code has to be different from your normal one"
        }

        // SAME LENGTH, and this is a security requirement rather than an implementation
        // convenience. A PIN pad submits when the entry reaches the expected length; a duress PIN
        // of a different length would either fail to submit or submit early, and either way anyone
        // watching could count the taps and see that two different codes exist. Being
        // indistinguishable is the entire feature.
        if (Mechanism.valueOf(json.optString("mechanism")) == Mechanism.PIN &&
            secret.length != json.optInt("length", secret.length)
        ) {
            return "The duress PIN has to be the same length as your normal one, so nobody " +
                "watching can tell them apart"
        }
        json.put("duress_hash", hash(secret, salt))
        write(json)
        PrismPlatform.log.info(TAG, "Duress credential set")
        return null
    }

    fun clearDuress() {
        val json = read() ?: return
        json.put("duress_hash", "")
        write(json)
    }

    /** Removes the lock entirely. */
    fun clear() {
        runCatching { file().delete() }
    }

    // ── Checking ───────────────────────────────────────────────────────────

    /**
     * Which credential this was.
     *
     * Duress is checked FIRST and both branches do the same amount of work, so the two cannot be
     * told apart by how long the check takes. That is not paranoia about a timing attack on a
     * phone -- it is that the whole feature is worthless if anything observable differs.
     */
    fun verify(secret: String): Outcome {
        val json = read() ?: return Outcome.WRONG
        val salt = json.optString("salt")
        if (salt.isBlank()) return Outcome.WRONG

        val candidate = hash(secret, salt)
        val duress = json.optString("duress_hash")

        val matchesDuress = duress.isNotBlank() && constantTimeEquals(candidate, duress)
        val matchesNormal = constantTimeEquals(candidate, json.optString("hash"))

        return when {
            matchesDuress -> Outcome.DURESS
            matchesNormal -> Outcome.NORMAL
            else -> Outcome.WRONG
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun read(): JSONObject? = runCatching {
        val source = file()
        if (!source.isFile) return null
        JSONObject(source.readText())
    }.getOrNull()

    private fun write(json: JSONObject) {
        runCatching { file().writeText(json.toString()) }
            .onFailure { PrismPlatform.log.error(TAG, "Could not write lock credentials", it) }
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(secret: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = (salt + secret).toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            digest.reset()
            current = digest.digest(current)
        }
        return current.joinToString("") { "%02x".format(it) }
    }

    /** Compares without leaking where the first difference is. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
        return difference == 0
    }
}
