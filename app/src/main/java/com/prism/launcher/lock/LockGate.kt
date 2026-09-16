package com.prism.launcher.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings

/**
 * Decides when the lock screen is shown.
 *
 * ## The mechanism, and its one honest limitation
 *
 * The screen going off arms the lock; the screen coming on shows it. `ACTION_SCREEN_ON` and
 * `ACTION_SCREEN_OFF` cannot be declared in a manifest -- Android requires them to be registered at
 * runtime -- so [install] is called from the Application, which is alive for as long as the launcher
 * is.
 *
 * **This does not replace the keyguard.** Nothing can; there has been no API for it since Android 5.
 * If the system lock is a PIN, the user unlocks twice. Setting the system lock to None or Swipe
 * makes Prism's the only one, and the setup screen says so outright rather than letting someone
 * discover it.
 *
 * ## Why the launcher re-checks
 *
 * Home is not interceptable, but Prism *is* the home app -- so a press of Home brings
 * LauncherActivity forward, and it asks [showIfLocked] on resume. That closes the obvious hole
 * without needing a permission or an overlay.
 */
object LockGate {

    private const val TAG = "PrismLock"

    @Volatile private var locked = false

    /** Set while the lock activity is on screen, so it is not launched on top of itself. */
    @Volatile private var showing = false

    val isLocked: Boolean get() = locked

    private var receiver: BroadcastReceiver? = null

    /**
     * Starts watching the screen. Safe to call more than once.
     *
     * Registered even when the lock is switched off: the setting can be turned on while the app is
     * running, and a receiver that only existed when the feature was already enabled would not take
     * effect until the next restart.
     */
    fun install(context: Context) {
        if (receiver != null) return

        val watcher = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> arm(context)
                    Intent.ACTION_SCREEN_ON -> show(context)
                }
            }
        }

        runCatching {
            context.applicationContext.registerReceiver(
                watcher,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
            receiver = watcher
            PrismLogger.logInfo(TAG, "Lock gate installed")
        }.onFailure { PrismLogger.logWarning(TAG, "Could not watch the screen: ${it.message}") }
    }

    private fun arm(context: Context) {
        if (!enabled(context)) return
        locked = true
    }

    private fun show(context: Context) {
        if (!locked || showing || !enabled(context)) return
        launch(context)
    }

    /** Called by the launcher when it comes forward, in case Home was used to leave the lock. */
    fun showIfLocked(context: Context) {
        if (locked && !showing && enabled(context)) launch(context)
    }

    private fun launch(context: Context) {
        showing = true
        runCatching {
            context.applicationContext.startActivity(
                Intent(context.applicationContext, PrismLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
            )
        }.onFailure {
            showing = false
            PrismLogger.logWarning(TAG, "Could not show the lock: ${it.message}")
        }
    }

    /** The lock activity reporting that it let the user in. */
    fun markUnlocked() {
        locked = false
        showing = false
    }

    /** The lock activity reporting that it is gone without unlocking (it was never configured). */
    fun markDismissed() {
        showing = false
    }

    /** Locks immediately, without waiting for the screen to go off. */
    fun lockNow(context: Context) {
        if (!enabled(context)) return
        locked = true
        launch(context)
    }

    private fun enabled(context: Context): Boolean =
        PrismSettings.getLockScreenEnabled() && LockStore.isEnabled(context)

    /**
     * Whether this device has a usable fingerprint or face sensor.
     *
     * Asked in several places -- setup, settings, the consent prompt for an emergency contact -- so
     * the answer lives in one place rather than three slightly different checks.
     */
    fun biometricAvailable(context: Context): Boolean = runCatching {
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /** Whether the running build can show an activity over the keyguard at all. */
    val canShowOverKeyguard: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
}
