package com.prism.launcher.platform

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.prism.core.Downloader
import com.prism.core.PrismPlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's [Downloader], on the system `DownloadManager`.
 *
 * WHY ANDROID KEEPS ITS OWN RATHER THAN USING THE PORTABLE ONE. `DownloadManager` is a system
 * service, and the guarantees it provides cannot be reimplemented inside an app process: the
 * transfer continues after Prism is killed, survives a reboot, pauses and resumes across
 * connectivity changes, and is subject to the user's metered-network preferences. Model files run
 * to several gigabytes, so on a phone -- where the OS kills backgrounded apps aggressively --
 * those are the difference between a download that finishes and one that does not.
 *
 * The desktop implementation deliberately does not pretend to any of that; it resumes instead.
 * See [com.prism.core.JvmDownloader].
 *
 * PROGRESS IS POLLED, not pushed. DownloadManager exposes progress only through a cursor query;
 * there is no callback. So a light poll runs while anything is in flight and stops when nothing
 * is -- an always-on poller on a phone is a battery cost for no benefit.
 */
class AndroidDownloader(context: Context) : Downloader {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override var onProgress: ((Downloader.Progress) -> Unit)? = null

    private val names = ConcurrentHashMap<Long, String>()
    private val latest = ConcurrentHashMap<Long, Downloader.Progress>()

    @Volatile
    private var polling = false

    override fun enqueue(name: String, url: String, destination: File): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Prism model download")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            // Into the app's own external files dir, which needs no runtime permission on any
            // supported API level.
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverRoaming(false)

        destination.parentFile?.mkdirs()
        val id = manager.enqueue(request)
        names[id] = name
        latest[id] = Downloader.Progress(id, name, 0, -1, Downloader.State.QUEUED)
        startPolling()
        return id
    }

    override fun cancel(id: Long) {
        manager.remove(id)
        names[id]?.let { name ->
            publish(Downloader.Progress(id, name, 0, -1, Downloader.State.CANCELLED))
        }
    }

    override fun active(): List<Downloader.Progress> = latest.values.sortedByDescending { it.id }

    private fun startPolling() {
        if (polling) return
        polling = true
        Thread({
            try {
                while (names.isNotEmpty()) {
                    poll()
                    // One second: fast enough that a progress bar looks live, slow enough that
                    // the cursor query is not itself a measurable cost.
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                polling = false
            }
        }, "prism-download-poll").apply { isDaemon = true }.start()
    }

    private fun poll() {
        val query = DownloadManager.Query().setFilterById(*names.keys.toLongArray())
        try {
            manager.query(query)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val soFarCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = names[id] ?: continue
                    val soFar = cursor.getLong(soFarCol)
                    val total = cursor.getLong(totalCol)
                    val state = when (cursor.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> Downloader.State.COMPLETE
                        DownloadManager.STATUS_FAILED -> Downloader.State.FAILED
                        DownloadManager.STATUS_PENDING -> Downloader.State.QUEUED
                        else -> Downloader.State.RUNNING
                    }
                    val error = if (state == Downloader.State.FAILED) {
                        "DownloadManager reason ${cursor.getInt(reasonCol)}"
                    } else null

                    publish(Downloader.Progress(id, name, soFar, total, state, error))

                    // Stop tracking anything terminal, which is also what lets the poll thread
                    // exit rather than spinning forever on completed downloads.
                    if (state == Downloader.State.COMPLETE || state == Downloader.State.FAILED) {
                        names.remove(id)
                    }
                }
            }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/download", "Progress query failed", e)
        }
    }

    private fun publish(progress: Downloader.Progress) {
        latest[progress.id] = progress
        onProgress?.invoke(progress)
    }
}
