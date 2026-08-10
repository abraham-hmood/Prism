package com.prism.launcher.nora

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.prism.core.PrismPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs an auto-caption pass in the background, with a persistent notification.
 *
 * A FOREGROUND SERVICE RATHER THAN WorkManager, and the distinction is not cosmetic. WorkManager
 * is for DEFERRABLE work -- the system decides when it runs, and may delay it for hours to batch
 * with other jobs. This is work the user just asked for and is waiting on, which is exactly what
 * a foreground service is for and exactly what WorkManager's own documentation says not to use it
 * for. The persistent notification is the price Android charges for that, and it is also the
 * feature: it is what makes the run visible after the settings screen is gone.
 *
 * THE JOB OUTLIVES THE UI ON PURPOSE. [current] is a process-wide handle, so the progress dialog
 * can be destroyed by a rotation, or the whole app backgrounded, and reattach to the same run
 * afterwards. Holding the job inside the activity would mean a rotation silently abandoned a
 * twenty-minute captioning pass.
 */
class CaptionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_DIR)
        val autoApply = intent?.getBooleanExtra(EXTRA_AUTO_APPLY, false) ?: false

        if (path == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val directory = File(path)
        val job = CaptionJob(
            directory = directory,
            captioner = VisionModelCaptioner(),
            applyAutomatically = autoApply,
        )
        current = job

        startForeground(NOTIFICATION_ID, buildNotification(job.progress.value, directory.name))

        // Mirrors progress into the notification. Separate from the worker so the notification
        // keeps updating even while a single caption call is blocked on the network.
        scope.launch {
            job.progress.collect { progress ->
                notify(buildNotification(progress, directory.name))
                if (progress.phase == CaptionJob.Phase.DONE ||
                    progress.phase == CaptionJob.Phase.FAILED ||
                    progress.phase == CaptionJob.Phase.CANCELLED
                ) {
                    finish(progress, directory.name)
                }
            }
        }

        worker = scope.launch {
            try {
                job.run()
            } catch (e: Exception) {
                PrismPlatform.log.error("Prism/caption", "Captioning failed", e)
            }
        }

        // NOT_STICKY: if Android kills this, restarting it with no intent would re-run a pass the
        // user did not ask for a second time -- and on the auto-apply path that means renaming
        // files again against names that have already changed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        current?.cancel()
        scope.coroutineContext[Job]?.cancel()
        current = null
        super.onDestroy()
    }

    /**
     * Replaces the ongoing notification with a completion summary, then stands down.
     *
     * A separate id so the summary is not the ongoing notification: the ongoing one is tied to the
     * foreground service and disappears when it stops, taking the result with it.
     */
    private fun finish(progress: CaptionJob.Progress, folder: String) {
        val summary = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                when (progress.phase) {
                    CaptionJob.Phase.CANCELLED -> "Captioning cancelled"
                    CaptionJob.Phase.FAILED -> "Captioning failed"
                    else -> "Captioning finished"
                }
            )
            .setContentText(progress.message.ifBlank { folder })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append(progress.message.ifBlank { "Done" })
                    append("\n").append(folder)
                    if (progress.renamed > 0) {
                        append("\n\nAn undo manifest was written into the folder as ")
                        append(".prism-caption-<timestamp>.tsv")
                    }
                }
            ))
            .setAutoCancel(true)
            .build()

        try {
            manager().notify(NOTIFICATION_ID + 1, summary)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied. The work still completed; only the report is lost.
            PrismPlatform.log.debug("Prism/caption", "Completion notification suppressed")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(progress: CaptionJob.Progress, folder: String): android.app.Notification {
        ensureChannel()

        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptionForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The line the user actually wants: which file is being worked on now, and what the last
        // one turned into. A bare percentage tells you nothing about whether the captions are any
        // good, which is the thing you would want to stop the run over.
        val detail = buildString {
            if (progress.currentName.isNotBlank()) append(progress.currentName)
            if (progress.lastCaption.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("last: ").append(progress.lastName).append(" → ").append(progress.lastCaption)
            }
        }.ifBlank { folder }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Captioning ${progress.done} of ${progress.total}")
            .setContentText(CaptionJob.formatEta(progress.etaMillis))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setProgress(progress.total.coerceAtLeast(1), progress.done, progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelIntent)
            .build()
    }

    private fun notify(notification: android.app.Notification) {
        try {
            manager().notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            PrismPlatform.log.debug("Prism/caption", "Progress notification suppressed")
        }
    }

    private fun manager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Auto-captioning", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while Prism captions a folder of images"
            setShowBadge(false)
        }
        manager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "prism_captioning"
        private const val NOTIFICATION_ID = 8801
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_AUTO_APPLY = "auto_apply"
        const val ACTION_CANCEL = "com.prism.launcher.CAPTION_CANCEL"

        /**
         * The job in flight, or null.
         *
         * Process-wide so a rotated or reopened settings screen can reattach. See the class
         * comment for why this is not held by the activity.
         */
        @Volatile
        var current: CaptionJob? = null
            private set

        fun start(context: Context, directory: File, applyAutomatically: Boolean) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .putExtra(EXTRA_DIR, directory.absolutePath)
                .putExtra(EXTRA_AUTO_APPLY, applyAutomatically)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            current?.cancel()
            context.stopService(Intent(context, CaptionForegroundService::class.java))
        }
    }
}
