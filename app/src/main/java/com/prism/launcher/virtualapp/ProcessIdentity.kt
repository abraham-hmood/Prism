package com.prism.launcher.virtualapp

import android.os.Process
import com.prism.launcher.PrismLogger
import java.lang.reflect.Field

/**
 * Makes the process answer "who are you?" with the virtualized app's name.
 *
 * ## Why the package name alone was not enough
 *
 * [SystemServiceHook] rewrites the names Prism passes to the system, which stops the system refusing
 * calls made on the app's behalf. It does nothing about the names the APP asks for itself, and a
 * large number of apps decide what to initialise by comparing their own process name against their
 * package name -- `if (!isMainProcess()) return` at the top of `Application.onCreate` is one of the
 * most common patterns in Android. Under virtualization that comparison read
 * `com.prism.launcher:virtualapp`, decided it was not the app's main process, and skipped the
 * initialisation the rest of the app then crashed for want of.
 *
 * Amazon is the clearest example: its ShopKit service registry is filled by initialisation that only
 * runs in what it believes is the main process, so the splash screen throws
 * "Service registry not initialized yet" with nothing in the stack pointing at the real cause.
 *
 * ## What is patched
 *
 *  - `ActivityThread.mBoundApplication.processName`, which is what `ActivityThread.currentProcessName()`
 *    and `Application.getProcessName()` read;
 *  - the zygote-side process name behind `Process.myProcessName()`, which some libraries use
 *    instead.
 *
 * Both are best effort. A platform that has moved either field leaves the process named as it was,
 * which is the previous behaviour rather than a new failure.
 */
object ProcessIdentity {

    private const val TAG = "PrismVirtualApp"

    /** Applies [packageName] as this process's name. Safe to call for each app hosted. */
    fun adopt(packageName: String) {
        patchActivityThread(packageName)
        patchZygoteProcess(packageName)
    }

    private fun patchActivityThread(packageName: String) {
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return

            val bound = field(thread.javaClass, "mBoundApplication")?.get(thread) ?: return
            field(bound.javaClass, "processName")?.set(bound, packageName)
            PrismLogger.logInfo(TAG, "Process name is now $packageName (ActivityThread)")
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not rename the bound application: ${it.message}")
        }
    }

    private fun patchZygoteProcess(packageName: String) {
        runCatching {
            val zygote = field(Process::class.java, "ZYGOTE_PROCESS")?.get(null) ?: return
            field(zygote.javaClass, "mProcessName")?.set(zygote, packageName)
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not rename the zygote process: ${it.message}")
        }
    }

    private fun field(start: Class<*>, name: String): Field? {
        var clazz: Class<*>? = start
        while (clazz != null) {
            runCatching { return clazz!!.getDeclaredField(name).apply { isAccessible = true } }
            clazz = clazz.superclass
        }
        return null
    }
}
