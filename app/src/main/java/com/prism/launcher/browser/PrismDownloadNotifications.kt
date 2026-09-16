package com.prism.launcher.browser

import android.app.DownloadManager
import android.content.Context
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent progress notifications for both kinds of download Prism does.
 *
 * ONE NOTIFICATION PER DOWNLOAD, whichever kind it is. Files go through Android's
 * `DownloadManager`, which has its own notification, and mesh site mirroring has none at all --
 * so left alone the user would see a system notification for one kind and nothing for the other.
 * `DownloadManager`'s notification is suppressed (the `DOWNLOAD_WITHOUT_NOTIFICATION` permission
 * is already declared) and Prism posts its own for both, so the two look and behave the same.
 *
 * IDs ARE DERIVED FROM THE DOWNLOAD'S IDENTITY, not from a counter. Re-posting the same id is what
 * updates a notification in place instead of stacking a new one every few percent; a counter would
 * produce a wall of notifications for a single file.
 */
object PrismDownloadNotifications {

    private const val TAG = "PrismDownloads"
    private const val CHANNEL = "prism_downloads"

    /** Offset so these can never collide with the fixed ids other features post (4712/4713). */
    private const val ID_BASE = 90_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSites = ConcurrentHashMap<String, Int>()

    private fun idFor(key: String): Int = ID_BASE + (key.hashCode() and 0xFFFF)

    // -- Files ------------------------------------------------------------------------------

    /**
     * Watches a `DownloadManager` job and mirrors its progress into a Prism notification.
     *
     * Polled rather than observed because `DownloadManager` offers no progress callback -- only a
     * completion broadcast and a cursor you query. One second is frequent enough to look live and
     * slow enough to cost nothing.
     */
    fun trackFile(context: Context, downloadId: Long, fileName: String) {
        val id = idFor("file:$downloadId")
        scope.launch {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            PrismPlatform.notifier.notifyProgress(CHANNEL, id, fileName, "Starting download…", -1, true)
            try {
                while (true) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = manager.query(query) ?: break
                    cursor.use { c ->
                        if (!c.moveToFirst()) return@use
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                PrismPlatform.notifier.notifyProgress(
                                    CHANNEL, id, fileName, "Download complete", 100, false
                                )
                                return@launch
                            }
                            DownloadManager.STATUS_FAILED -> {
                                // Reported, not silently dropped: a download that disappears looks
                                // the same as one still running.
                                PrismPlatform.notifier.notifyProgress(
                                    CHANNEL, id, fileName, "Download failed", -1, false
                                )
                                return@launch
                            }
                            else -> {
                                // total is -1 until the server sends a Content-Length, which is why
                                // this falls back to an indeterminate bar rather than dividing by it.
                                val percent = if (total > 0) ((soFar * 100) / total).toInt() else -1
                                val body = if (total > 0) "${humanBytes(soFar)} of ${humanBytes(total)}"
                                           else humanBytes(soFar)
                                PrismPlatform.notifier.notifyProgress(CHANNEL, id, fileName, body, percent, true)
                            }
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Progress tracking failed for $fileName", e)
                PrismPlatform.notifier.cancel(id)
            }
        }
    }

    // -- Mesh sites -------------------------------------------------------------------------

    /**
     * Progress for a site being mirrored for the mesh, called from `PrismMirrorManager`'s single
     * progress funnel. [percent] of 100 completes the notification and a negative value reports
     * failure; anything between updates it in place.
     */
    fun siteProgress(domain: String, percent: Int) {
        // Off-mesh there is nothing to host, and mirroring should not have started -- but a
        // notification for work the user cannot use would be noise either way.
        if (!PrismSettings.getMeshEnabled()) return

        val id = activeSites.getOrPut(domain) { idFor("site:$domain") }
        when {
            percent >= 100 -> {
                PrismPlatform.notifier.notifyProgress(
                    CHANNEL, id, domain, "Downloaded and now hosted on the mesh", 100, false
                )
                activeSites.remove(domain)
            }
            percent < 0 -> {
                PrismPlatform.notifier.notifyProgress(
                    CHANNEL, id, domain, "Site download failed", -1, false
                )
                activeSites.remove(domain)
            }
            else -> PrismPlatform.notifier.notifyProgress(
                CHANNEL, id, domain, "Downloading site for the mesh… $percent%", percent, true
            )
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 0 -> "…"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
