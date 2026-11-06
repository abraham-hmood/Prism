package com.prism.launcher.storage.compress

import com.prism.core.PrismPlatform
import com.prism.launcher.storage.remote.TrustedDrives
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps compressed drives compressed: what Windows' drive attribute does for files that arrive
 * after the drive was compressed.
 *
 * Files written through Prism are compressed as they are written (see FileDriveAccess), and on
 * NTFS the file system does it itself. Everything else -- a file another app saved on a phone, a
 * file on a WOF-compressed disk -- is picked up here: every [INTERVAL_MS], each drive set to
 * compress new files gets a pass over just the files changed since the last one. A pass that finds
 * nothing is never shown; one that does shows the same notification as any other compression.
 */
object AutoCompression {

    private const val TAG = "PrismAutoCompress"
    private const val PREFS = "prism_auto_compress_passes"
    private const val INTERVAL_MS = 30 * 60_000L

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            // A pause first: startup is not the moment to walk a whole drive.
            runCatching { Thread.sleep(3 * 60_000L) }.onFailure { return@Thread }
            while (true) {
                runCatching { runOnce() }.onFailure { PrismPlatform.log.warn(TAG, "Auto-compression failed: ${it.message}") }
                runCatching { Thread.sleep(INTERVAL_MS) }.onFailure { return@Thread }
            }
        }, "prism-auto-compress").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** One pass over every drive that is set to compress new files and is here now. */
    fun runOnce() {
        val prefs = PrismPlatform.host.prefs(PREFS)
        TrustedDrives.offered(fresh = true).forEach { offered ->
            val id = offered.drive.id
            val setting = DriveCompression.setting(id)
            if (!setting.compressed || !setting.auto || offered.root == null || offered.drive.readOnly) return@forEach
            if (CompressionJobs.get(CompressionJobs.localKey(id))?.active == true) return@forEach
            val now = System.currentTimeMillis()
            // Changed since the last pass began, less a minute for clocks and slow writers.
            val since = prefs.getLong(id, now - INTERVAL_MS) - 60_000L
            val problem = CompressionJobs.start(id, setting.methodId, compressing = true, auto = true, changedSince = since, quiet = true)
            if (problem == null) prefs.edit().putLong(id, now).apply()
            else PrismPlatform.log.info(TAG, "Skipped ${offered.drive.name}: $problem")
        }
    }
}
