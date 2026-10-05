package com.prism.launcher.lock

import com.prism.core.PrismPlatform

/**
 * What locking Prism means, as a decision recorded in code. PHASE 101.
 *
 * ## THE DECISION THE PHASE ASKED FOR, STATED PLAINLY
 *
 * The phase said: "NEEDS A DECISION FIRST, and it is not a small one. A desktop lock screen cannot be
 * what the Android one is." It cannot, and here is what was decided.
 *
 * ON ANDROID, Prism's lock replaces the launcher's own surface. That is close to being a real lock,
 * because when Prism IS the launcher there is very little visible behind it -- though even there it is
 * only *the* lock screen if the system lock is set to None or Swipe, which `LockStore` is careful to
 * say.
 *
 * ON A DESKTOP IT IS NOT A LOCK ON THE MACHINE AND MUST NOT CLAIM TO BE. The OS owns the login
 * screen. An application that drew a full-screen panel and called the computer locked would be
 * security theatre of the worst kind -- alt-tab, the taskbar, a second monitor, Win+D, or simply
 * killing the process from another window all walk past it, and a user who believed the claim would
 * leave the machine unattended on the strength of it.
 *
 * So the desktop lock locks PRISM. Everything in [Gate] below, and nothing else. That is a smaller
 * promise and it is one that can actually be kept.
 *
 * ## Why this is a list rather than a boolean
 *
 * Because a lock that only hid the window would be worthless: the wallet phrase, the message history
 * and the clipboard are reachable from the console harness and from the mesh while the process runs.
 * Naming each gate means each one is a thing that was thought about, and a new surface that needs
 * gating is a line here rather than a bug nobody notices.
 */
object PrismLockScope {

    private const val TAG = "PrismLock"

    /**
     * Something the lock closes.
     *
     * Each has a [promise] that is true of the desktop and a [caveat] that says where it stops. The
     * caveats are the honest half and are shown to the user, because a security feature whose limits
     * are only in the source is a security feature that will be trusted too far.
     */
    enum class Gate(val label: String, val promise: String, val caveat: String) {
        WINDOW(
            "Prism's window",
            "The window shows the lock screen and nothing else until the credential is entered.",
            "This does not lock the computer. Other applications, the desktop and the taskbar are " +
                "all still there, and killing Prism from the task manager closes the lock with it.",
        ),
        WALLET(
            "The wallet",
            "The seed phrase, the private keys and the signing path all refuse while locked.",
            "A wallet file that was already decrypted into memory before the lock engaged stays " +
                "there until Prism exits; locking does not scrub the heap.",
        ),
        MESSAGES(
            "Messages and history",
            "Conversations, the clipboard relay and the trusted-device bridge stop answering.",
            "A message that arrives while locked is still received and stored — it is not shown.",
        ),
        TRUSTED(
            "Trusted-device pairing",
            "A pairing offer is refused outright rather than queued for a consent prompt.",
            "Devices already paired stay paired. Locking is not unpairing.",
        ),
        CONSOLE(
            "The console harness",
            "Commands that read private data refuse while locked.",
            "Prism's own data files are still on disk and readable by anything running as you. " +
                "This is a lock on Prism, not on the filesystem.",
        ),
    }

    @Volatile
    private var locked: Boolean = false

    /**
     * Whether Prism is locked right now.
     *
     * Not persisted. A lock that survived a restart would be indistinguishable from one that had been
     * engaged by the user, and the thing that persists is the CREDENTIAL -- see [LockStore]. Whether a
     * fresh start begins locked is a separate question answered by [startsLocked].
     */
    fun isLocked(): Boolean = locked

    /** Whether a lock exists to engage at all. */
    fun isAvailable(): Boolean = LockStore.isConfigured() && LockStore.isEnabled()

    /**
     * Whether a fresh launch should come up locked.
     *
     * True whenever a lock is configured and armed, which is the only defensible default: a lock
     * that engaged only on request would protect a machine its owner remembered to lock and leave
     * the one they walked away from.
     */
    fun startsLocked(): Boolean = isAvailable()

    fun lock() {
        if (!isAvailable()) {
            PrismPlatform.log.info(TAG, "Nothing to lock: no credential is configured.")
            return
        }
        locked = true
        PrismPlatform.log.info(TAG, "Prism is locked.")
        listeners.toList().forEach { runCatching { it(true) } }
    }

    /**
     * Unlocks if [secret] is right, and reports WHICH credential it was.
     *
     * BOTH CREDENTIALS UNLOCK, and the normal and duress paths are indistinguishable from outside --
     * see [LockStore]. The caller is responsible for acting on a duress outcome without showing that
     * it has: anything visible here would tell the person standing over the user that the code they
     * watched being typed was a duress code, which is the entire failure this design exists to stop.
     */
    fun unlock(secret: String): LockStore.Outcome {
        val outcome = LockStore.verify(secret)
        if (outcome != LockStore.Outcome.WRONG) {
            locked = false
            listeners.toList().forEach { runCatching { it(false) } }
        }
        return outcome
    }

    /**
     * Refuses an action while locked, for a gate to call.
     *
     * Returns true when the caller should go ahead. Logged at warn when it refuses, because a feature
     * silently doing nothing is how a gate gets diagnosed as a bug.
     */
    fun permit(gate: Gate): Boolean {
        if (!locked) return true
        PrismPlatform.log.warn(TAG, "Refused " + gate.label + ": Prism is locked.")
        return false
    }

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** Notified whenever the lock engages or releases, so a UI can follow it. */
    @Synchronized
    fun onChange(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun forget(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
