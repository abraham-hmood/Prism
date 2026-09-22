package com.prism.launcher.virtualization

import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.virtualapp.VirtualAppActivity

/**
 * Runs an installed app's code inside Prism instead of launching it normally.
 *
 * ## The first launch backs the app up
 *
 * Its APKs are copied into Prism's vault -- base and every split, because a modern app is several
 * files and the base alone produces something that starts and then cannot find its own resources.
 * Its data lives in the same place. After that the vault is the source of truth, which is what lets
 * a virtualized app keep working once it has been uninstalled from the phone.
 *
 * ## What this approach can and cannot give you
 *
 * This is the VirtualApp/VirtualXposed design: the app's code is loaded into Prism's own process.
 * That makes it fast and makes it work without a VM, and it has consequences worth being plain
 * about, because two of them bear directly on what was asked for:
 *
 *  - **Sandboxing.** There is none between Prism and the app. It runs under Prism's uid, in Prism's
 *    process, with Prism's permissions. It is isolated from OTHER apps by the same Android sandbox
 *    that isolates Prism, and not at all from Prism.
 *  - **The vault passphrase.** Code sharing a process can read that process's memory. The passphrase
 *    protects the data at rest -- against another app, a stolen phone, a backup -- and cannot be
 *    hidden from the virtualized app itself while that app is running inside Prism.
 *  - **Play Protect and Play Integrity.** These check that the process is the app it claims to be.
 *    Under virtualization it is not, so they fail. Nothing here can change that; an app that refuses
 *    to run without them will refuse here.
 *
 * A guest VM is the design that gives real isolation and a key the app cannot reach, at the cost of
 * speed. Both are legitimate; this one was chosen deliberately.
 */
object VirtualizedAppLauncher {

    private const val TAG = "PrismVirtualApp"

    /**
     * Whether this launch should be virtualized.
     *
     * Prism excludes itself, which is less a special case than a loop: virtualizing the launcher
     * would load the launcher into the launcher.
     */
    fun shouldVirtualize(context: Context, cn: ComponentName): Boolean {
        if (!PrismSettings.getVirtualizeAndroidApps()) return false
        return cn.packageName != context.packageName
    }

    /**
     * Backs the app up if this is the first time, then opens it inside Prism.
     *
     * The backup runs off the main thread: copying a few hundred megabytes of split APKs is not
     * something to do while the launcher is animating.
     */
    fun launch(activity: LauncherActivity, cn: ComponentName) {
        val packageName = cn.packageName
        val known = VirtualizedAppVault.isBackedUp(activity, packageName)

        if (known) {
            open(activity, cn)
            return
        }

        Toast.makeText(activity, "Preparing $packageName…", Toast.LENGTH_SHORT).show()
        Thread({
            val backup = VirtualizedAppVault.backUpApk(activity, packageName)
            activity.runOnUiThread {
                if (backup == null) {
                    Toast.makeText(
                        activity,
                        "Could not read $packageName's APK, so it cannot be virtualized",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    PrismLogger.logInfo(TAG, "Backed up $packageName (${backup.bytes shr 20} MB)")
                    open(activity, cn)
                }
            }
        }, "virtualize-$packageName").apply { isDaemon = true; start() }
    }

    /**
     * Shows the app, on the virtualization page where there is one.
     *
     * The activity to start is NOT taken from the launcher icon that was tapped. It is resolved from
     * the app's own manifest, by the intent filters that mark an entry point -- see [ApkManifest].
     * The tapped component is the right answer only while the app is still installed; the vault is
     * meant to keep working after it is not, and an app whose entry point is an `<activity-alias>`
     * needs the manifest either way.
     *
     * The separate window is the fallback, not the intent: it is what happens when no virtualization
     * page is assigned to a slot, or on Android 10 and older where a window cannot be embedded.
     */
    private fun open(activity: LauncherActivity, cn: ComponentName) {
        if (activity.showVirtualizedApp(cn.packageName)) return

        PrismLogger.logInfo(TAG, "No virtualization page; opening ${cn.packageName} in its own window")
        runCatching {
            activity.startActivity(VirtualAppActivity.intentFor(activity, cn.packageName))
        }.onFailure {
            PrismLogger.logError(TAG, "Could not open ${cn.packageName} virtualized", it)
            Toast.makeText(activity, "Could not open ${cn.packageName}", Toast.LENGTH_LONG).show()
        }
    }
}
