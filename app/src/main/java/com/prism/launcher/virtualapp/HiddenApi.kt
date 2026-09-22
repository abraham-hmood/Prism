package com.prism.launcher.virtualapp

import android.os.Build
import com.prism.launcher.PrismLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Unblocks reflection into the framework's private classes.
 *
 * ## Why anything needs this
 *
 * Running another app's code inside this process means standing where the framework expects the app
 * itself to be: `ActivityThread`, `LoadedApk`, `Instrumentation`, `ActivityManager`'s binder proxy.
 * None of those are public API, and from Android 9 onwards reflecting at them from an app returns
 * `NoSuchMethodException` even though the method is plainly there -- the runtime keeps a
 * "greylist/blacklist" and refuses lookups that cross it.
 *
 * ## How the exemption is obtained
 *
 * `VMRuntime.setHiddenApiExemptions(String...)` turns the check off for matching prefixes, and an
 * empty prefix matches every class signature there is. But that method is ITSELF hidden, so looking
 * it up directly hits the same wall, and the obvious way through no longer exists.
 *
 * The way through USED to be that the restriction applies to the caller: performing the lookup with
 * `Method.invoke` on `Class.getDeclaredMethod` made it appear to come from `java.lang.Class`, which
 * is exempt. Android 11 closed that -- ART now skips `java.lang.Class` frames while deciding who the
 * caller is -- and on API 34 it fails exactly that way, which is what this code did before and why
 * every app reported "this device will not allow the framework access that app virtualization
 * needs".
 *
 * What still works reparents a helper class onto the boot class loader, so the lookup genuinely does
 * come from platform code rather than appearing to. That is fiddly and changes with releases, so it
 * is taken from a library maintained against each one rather than rewritten here.
 *
 * ## This is a documented compatibility risk
 *
 * The exemption is not API. It has survived every release so far because AOSP's own CTS and a great
 * deal of the ecosystem depend on it, but it is Google's to remove, and virtualization frameworks are
 * exactly what they would remove it to stop. [unlocked] reports what actually happened rather than
 * assuming, so the caller can say "this app cannot be virtualized on this device" instead of
 * crashing somewhere further in.
 */
object HiddenApi {

    private const val TAG = "PrismVirtualApp"

    @Volatile
    private var attempted = false

    @Volatile
    var unlocked: Boolean = false
        private set

    /**
     * Turns off hidden-API enforcement for this process. Safe to call repeatedly.
     *
     * Returns true when reflection into framework internals will work -- which is trivially true
     * below Android 9, where no such enforcement exists.
     */
    @Synchronized
    fun unlock(): Boolean {
        if (attempted) return unlocked
        attempted = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            unlocked = true
            return true
        }

        unlocked = runCatching { exempt() }.getOrElse {
            PrismLogger.logWarning(TAG, "Hidden API exemption failed: ${it.message}")
            false
        }

        if (unlocked) {
            PrismLogger.logInfo(TAG, "Hidden API enforcement lifted for this process")
        }
        return unlocked
    }

    private fun exempt(): Boolean {
        // An empty prefix matches every signature, which is what hosting arbitrary app code needs:
        // the framework internals an app touches are spread across android.app, android.content
        // and libcore, and enumerating them would mean guessing which ones the NEXT app uses.
        val applied = HiddenApiBypass.addHiddenApiExemptions("")

        // Verified rather than assumed: the call above can report success and still not take effect
        // on a vendor build that has moved something. Looking up a method that is definitely hidden
        // is the only honest check, and ActivityThread.currentActivityThread is the one this whole
        // feature is about to depend on anyway.
        val reachable = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
        }.isSuccess

        if (applied && !reachable) {
            PrismLogger.logWarning(TAG, "Exemption reported success but hidden lookups still fail")
        }
        return reachable
    }
}
