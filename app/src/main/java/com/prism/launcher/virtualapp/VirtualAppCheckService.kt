package com.prism.launcher.virtualapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.prism.launcher.PrismLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs [VirtualAppProbe] over one app, or over every app on the phone, in `:virtualapp`.
 *
 * ## Why a service in the guest's own process
 *
 * The probe executes the app's code. That has to happen in the process that is allowed to execute
 * it -- the one without the vault key -- for the same reason the real host is there. It also means a
 * probe that wedges takes down only this process.
 *
 * ## One app at a time, by design
 *
 * There is no batch loop here that keeps going after a failure, because the interesting failure is
 * the main thread never returning. When that happens nothing in this process can run again, so the
 * caller is the one that must notice and move on. [VirtualAppCheckReceiver] starts one probe per
 * request, and results are appended to a file the caller can read even after the process died.
 */
class VirtualAppCheckService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        append("> $packageName")

        Thread({
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                VirtualAppProbe.probe(this, packageName)
            }.getOrElse {
                VirtualAppProbe.Outcome(VirtualAppProbe.Verdict.FAILED, describe(it))
            }

            append(
                listOf(
                    packageName,
                    outcome.verdict.name.lowercase(Locale.US),
                    (System.currentTimeMillis() - started).toString(),
                    outcome.launchActivity.orEmpty(),
                    outcome.detail.replace('\n', ' ').replace('|', '/'),
                ).joinToString("|"),
            )

            stopSelf(startId)
        }, "virtualapp-probe-$packageName").apply { isDaemon = true; start() }

        return START_NOT_STICKY
    }

    /**
     * Appends one line to the shared report.
     *
     * Plain text and one line per result rather than JSON: this file is read by `adb shell` while a
     * probe may be halfway through writing it, and a truncated JSON document is unreadable where a
     * truncated line is simply ignored.
     */
    private fun append(line: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(filesDir, REPORT).appendText("$stamp $line\n")
        }.onFailure { PrismLogger.logWarning(TAG, "Could not write the check report: ${it.message}") }
    }

    companion object {
        private const val TAG = "PrismVirtualApp"

        const val EXTRA_PACKAGE = "package"

        /** Where results accumulate, relative to Prism's files directory. */
        const val REPORT = "virtualization-check.txt"

        fun start(context: Context, packageName: String) {
            runCatching {
                context.startService(
                    Intent(context, VirtualAppCheckService::class.java)
                        .putExtra(EXTRA_PACKAGE, packageName),
                )
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not start a probe for $packageName: ${it.message}")
            }
        }

        /** Every app that has a launcher entry, which is the set "every app" actually means. */
        fun launchablePackages(context: Context): List<String> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return runCatching {
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .filter { it != context.packageName }
                    .distinct()
                    .sorted()
            }.getOrElse {
                PrismLogger.logWarning(TAG, "Could not list launchable apps: ${it.message}")
                emptyList()
            }
        }
    }
}
