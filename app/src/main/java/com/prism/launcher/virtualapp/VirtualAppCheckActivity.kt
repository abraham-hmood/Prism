package com.prism.launcher.virtualapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.prism.launcher.PrismLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The probe's entry point when a broadcast cannot reach it.
 *
 * ## Why this exists as well as [VirtualAppCheckReceiver]
 *
 * A manifest receiver is started in the BACKGROUND, and Android refuses to create a process for one
 * once the app has been put to sleep or has accumulated too many kills -- the broadcast is recorded
 * as `startProcessLocked failed` and nothing runs. That is exactly what happened to the full-device
 * sweep: the first dozen apps reported, then every later probe was silently refused.
 *
 * An ACTIVITY start has no such restriction, because someone asking for a screen is a visible
 * action. So the same work is offered here too: a transparent window that starts the probe, writes
 * the result, and finishes. Debug builds only -- see `src/debug/AndroidManifest.xml` -- because it
 * executes the code of whatever package it is given.
 */
class VirtualAppCheckActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent?.getStringExtra(VirtualAppCheckService.EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }

        val label = TextView(this).apply {
            text = "Checking $packageName…"
            gravity = Gravity.CENTER
            textSize = 12f
        }
        setContentView(label)

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

            runOnUiThread { finish() }
        }, "virtualapp-probe-activity-$packageName").apply { isDaemon = true; start() }
    }

    private fun append(line: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(filesDir, VirtualAppCheckService.REPORT).appendText("$stamp $line\n")
        }.onFailure { PrismLogger.logWarning(TAG, "Could not write the check report: ${it.message}") }
    }

    private companion object {
        const val TAG = "PrismVirtualApp"
    }
}
