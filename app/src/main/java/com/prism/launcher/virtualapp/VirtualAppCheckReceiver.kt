package com.prism.launcher.virtualapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * The way a developer or a script asks for a virtualization check.
 *
 * Debug builds only -- it is declared in `src/debug/AndroidManifest.xml`, so a release build has no
 * component that can be asked to run arbitrary installed apps. That restriction matters: the action
 * runs the code of whatever package it is given, so in a shipped build it would be a way to make
 * Prism execute anything on the device.
 *
 * Usage:
 *
 *     adb shell am broadcast -a com.prism.launcher.PROBE_VIRTUALIZATION \
 *         -n com.prism.launcher/.virtualapp.VirtualAppCheckReceiver --es package com.example.app
 *
 * Results are appended to `files/virtualization-check.txt`, which survives the probe's process.
 */
class VirtualAppCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROBE -> {
                val packageName = intent.getStringExtra(VirtualAppCheckService.EXTRA_PACKAGE)
                if (packageName.isNullOrBlank()) {
                    PrismLogger.logWarning(TAG, "A probe was requested with no package")
                    return
                }
                VirtualAppCheckService.start(context, packageName)
            }

            ACTION_PROBE_ALL -> probeAll(context)

            ACTION_RESET -> runCatching {
                File(context.filesDir, VirtualAppCheckService.REPORT).delete()
                PrismLogger.logInfo(TAG, "Cleared the virtualization check report")
            }
        }
    }

    /**
     * Every launchable app, in one pass.
     *
     * Deliberately sequential in one process: it is much faster than restarting the process per app,
     * and when an app wedges the process the report file says which one was being probed, so a
     * scripted caller can resume after it.
     */
    private fun probeAll(context: Context) {
        val app = context.applicationContext
        Thread({
            for (packageName in VirtualAppCheckService.launchablePackages(app)) {
                val started = System.currentTimeMillis()
                val outcome = runCatching {
                    VirtualAppProbe.probe(app, packageName)
                }.getOrElse {
                    VirtualAppProbe.Outcome(VirtualAppProbe.Verdict.FAILED, describe(it))
                }
                runCatching {
                    File(app.filesDir, VirtualAppCheckService.REPORT).appendText(
                        listOf(
                            packageName,
                            outcome.verdict.name.lowercase(),
                            (System.currentTimeMillis() - started).toString(),
                            outcome.launchActivity.orEmpty(),
                            outcome.detail.replace('\n', ' ').replace('|', '/'),
                        ).joinToString("|") + "\n",
                    )
                }
            }
            PrismLogger.logInfo(TAG, "Virtualization check finished")
        }, "virtualapp-probe-all").apply { isDaemon = true; start() }
    }

    companion object {
        private const val TAG = "PrismVirtualApp"

        const val ACTION_PROBE = "com.prism.launcher.PROBE_VIRTUALIZATION"
        const val ACTION_PROBE_ALL = "com.prism.launcher.PROBE_ALL_VIRTUALIZATION"
        const val ACTION_RESET = "com.prism.launcher.RESET_VIRTUALIZATION_CHECK"
    }
}
