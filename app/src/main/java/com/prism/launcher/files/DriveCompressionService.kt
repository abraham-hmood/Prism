package com.prism.launcher.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import com.prism.launcher.storage.compress.CompressionJobs

/**
 * The persistent notification for drive compression, and the foreground service that keeps a
 * compression running when Prism is in the background.
 *
 * The work itself runs in [CompressionJobs]' own thread; this service exists so Android does not
 * stop the process halfway through a drive, and to carry the notification: what is being
 * compressed, how much is done, how much is left, and a Stop button.
 *
 * A job another device runs (a computer compressing its own disk because this phone asked) shows
 * in the same notification without the service: nothing is working here, so nothing needs to be
 * kept alive. The same is the fallback when Android refuses to start a foreground service from the
 * background -- which is what a request from a trusted device can be.
 */
class DriveCompressionService : Service() {

    companion object {
        private const val TAG = "PrismDriveCompress"
        private const val CHANNEL = "drive_compression"
        private const val NOTIFICATION_ID = 7431
        private const val DONE_ID = 7432
        private const val ACTION_SHOW = "com.prism.launcher.files.COMPRESS_SHOW"
        private const val ACTION_STOP = "com.prism.launcher.files.COMPRESS_STOP"
        private const val EXTRA_KEY = "key"
        private const val UPDATE_EVERY_MS = 750L

        @Volatile private var running = false
        @Volatile private var lastUpdate = 0L
        private val main = Handler(Looper.getMainLooper())
        private var installed = false
        private val reported = HashSet<String>()

        /** Watches every compression from now on. Called once at startup. */
        fun install(context: Context) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            createChannel(app)
            CompressionJobs.addListener { job ->
                val now = System.currentTimeMillis()
                // Every change of state is shown at once; progress at most a few times a second.
                if (job.active && job.state == CompressionJobs.State.RUNNING && now - lastUpdate < UPDATE_EVERY_MS) return@addListener
                lastUpdate = now
                main.post { refresh(app) }
            }
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Drive compression", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while a drive is being compressed or decompressed"
                    setShowBadge(false)
                },
            )
        }

        private fun refresh(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val jobs = CompressionJobs.all().filter { it.visible }
            val active = jobs.filter { it.active }
            jobs.filter { !it.active && it.key !in reported }.forEach { finished ->
                reported += finished.key
                manager.notify(DONE_ID + (finished.key.hashCode() and 0xFFF), finishedNotification(context, finished))
            }
            active.forEach { reported -= it.key }

            if (active.isEmpty()) {
                if (running) context.stopService(Intent(context, DriveCompressionService::class.java))
                manager.cancel(NOTIFICATION_ID)
                return
            }
            val notification = progressNotification(context, active)
            if (active.any { !it.remote } && !running) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, DriveCompressionService::class.java).setAction(ACTION_SHOW))
                    return
                } catch (refused: Exception) {
                    // Android 12+ refuses a foreground service started from the background. The job
                    // carries on regardless; the notification is shown without the service.
                    PrismLogger.logWarning(TAG, "No foreground service (${refused.javaClass.simpleName}); showing the notification only")
                }
            }
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun stopIntent(context: Context, key: String): PendingIntent = PendingIntent.getService(
            context, key.hashCode(),
            Intent(context, DriveCompressionService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openIntent(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }

        internal fun progressNotification(context: Context, active: List<CompressionJobs.Job>): Notification {
            val first = active.first()
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_video_craft)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openIntent(context))
            if (active.size == 1) {
                builder.setContentTitle(first.headline())
                    .setContentText(first.detail())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(first.detail() + if (first.current.isNotBlank()) "\n" + first.current else ""))
                    .setSubText(first.method.substringBefore(" --"))
            } else {
                val inbox = NotificationCompat.InboxStyle()
                active.forEach { inbox.addLine(it.headline() + ": " + it.detail()) }
                builder.setContentTitle("Compressing ${active.size} drives")
                    .setContentText(first.headline() + " · " + first.detail())
                    .setStyle(inbox)
            }
            if (first.state == CompressionJobs.State.RUNNING) {
                builder.setProgress(1000, (first.fraction * 1000).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            active.take(2).forEach { job ->
                builder.addAction(0, if (active.size == 1) "Stop" else "Stop ${job.title}", stopIntent(context, job.key))
            }
            return builder.build()
        }

        private fun finishedNotification(context: Context, job: CompressionJobs.Job): Notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_video_craft)
                .setContentTitle(job.headline())
                .setContentText(job.detail())
                .setStyle(NotificationCompat.BigTextStyle().bigText(job.detail()))
                .setAutoCancel(true)
                .setContentIntent(openIntent(context))
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_KEY)?.let(CompressionJobs::stop)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val active = CompressionJobs.running()
        if (active.isEmpty()) {
            // Started for a job that finished before the service came up: Android still requires
            // startForeground once a foreground start was asked for.
            startForegroundCompat(progressNotification(this, listOf(CompressionJobs.Job(key = "-", driveId = "-", title = "drives"))))
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(progressNotification(this, active))
        running = true
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Android 15 caps data-sync services at six hours a day. A drive that takes longer keeps going
     * in the background thread; only the service goes, and the notification stays as a plain one.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        PrismLogger.logWarning(TAG, "Android ended the compression service after its time limit; the work continues")
        running = false
        stopSelf()
        main.post { refresh(applicationContext) }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
