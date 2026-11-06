package com.prism.launcher.podcasts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.R

/**
 * The Android half of podcasts: the speaker line the player streams into, and the foreground service
 * that keeps an episode being made when Prism is in the background.
 */
object PodcastAndroid {

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        PodcastPlayer.openLine = { rate -> AudioTrackLine.open(rate) }
        PodcastStudioService.install(app)
        Thread({
            val job = PodcastDiagnostics.takeInterrupted(lastExitReason(app)) ?: return@Thread
            PodcastStudio.resume(job)
        }, "prism-podcast-recover").apply { isDaemon = true; start() }
    }

    /**
     * Why this app's previous process ended, as Android recorded it (Android 11+). A force-stop by
     * Samsung's Device Health Manager for overheating shows as a user-requested stop "due to from
     * pid" of `com.sec.android.sdhms`; the low-memory killer shows as LOW_MEMORY. Null on older
     * releases, where the platform keeps no record.
     */
    private fun lastExitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed by Android for low memory"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crashed (Java exception)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native code)"
                android.app.ApplicationExitInfo.REASON_ANR -> "stopped responding (ANR)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${exit.status}"
                android.app.ApplicationExitInfo.REASON_OTHER -> "stopped by the system"
                else -> "ended (reason ${exit.reason})"
            }
            val overheating = exit.description.orEmpty().contains("5698") || exit.description.orEmpty().contains("sdhms", true)
            reason + (exit.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                (if (overheating) " (Samsung's Device Health Manager stops apps when the phone overheats)" else "") +
                ", memory in use ${exit.pss / 1024} MB"
        }.getOrNull()
    }
}

/** One AudioTrack in streaming mode for a whole episode -- see [PcmLine] for why not AudioSink. */
private class AudioTrackLine(private val track: AudioTrack) : PcmLine {

    override fun write(samples: ShortArray, count: Int): Int =
        track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)

    override fun pause() { runCatching { track.pause() } }
    override fun resume() { runCatching { track.play() } }
    override fun flush() { runCatching { track.pause(); track.flush(); track.play() } }
    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    companion object {
        fun open(rate: Int): PcmLine? = runCatching {
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, rate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            AudioTrackLine(track) as PcmLine
        }.onFailure { PrismLogger.logWarning("Podcasts", "No audio track: ${it.message}") }.getOrNull()
    }
}

/**
 * Keeps the process alive while [PodcastStudio] is making something, and carries its progress.
 *
 * An episode can take many minutes -- research, a dozen model calls and every line recorded -- and
 * a launcher page that has been swiped away is exactly when Android would otherwise reclaim the
 * process. Finished episodes get a notification of their own.
 */
class PodcastStudioService : Service() {

    companion object {
        private const val TAG = "Podcasts"
        private const val CHANNEL = "podcast_studio"
        private const val NOTIFICATION_ID = 7531
        private const val READY_ID = 7532
        private const val ACTION_SHOW = "com.prism.launcher.podcasts.SHOW"
        private const val ACTION_STOP = "com.prism.launcher.podcasts.STOP"
        private const val UPDATE_EVERY_MS = 1_000L

        @Volatile private var running = false
        @Volatile private var lastUpdate = 0L
        private val main = Handler(Looper.getMainLooper())

        fun install(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Podcasts", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while a podcast episode is being made, and when it is ready"
                    setShowBadge(false)
                },
            )
            PodcastStudio.addListener {
                val now = System.currentTimeMillis()
                if (PodcastStudio.active && now - lastUpdate < UPDATE_EVERY_MS) return@addListener
                lastUpdate = now
                main.post { refresh(context) }
            }
            PodcastStudio.onEpisodeReady = { podcast, episode ->
                main.post {
                    manager.notify(
                        READY_ID + (episode.id.hashCode() and 0xFFF),
                        NotificationCompat.Builder(context, CHANNEL)
                            .setSmallIcon(R.drawable.ic_video_craft)
                            .setContentTitle("New episode of ${podcast.title}")
                            .setContentText(episode.title)
                            .setAutoCancel(true)
                            .setContentIntent(openIntent(context))
                            .build(),
                    )
                }
            }
        }

        private fun refresh(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val jobs = PodcastStudio.all().filter { it.error == null }
            if (jobs.isEmpty()) {
                if (running) context.stopService(Intent(context, PodcastStudioService::class.java))
                manager.cancel(NOTIFICATION_ID)
                return
            }
            if (!running) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, PodcastStudioService::class.java).setAction(ACTION_SHOW))
                    return
                } catch (refused: Exception) {
                    PrismLogger.logWarning(TAG, "No foreground service (${refused.javaClass.simpleName}); showing the notification only")
                }
            }
            manager.notify(NOTIFICATION_ID, progress(context))
        }

        private fun openIntent(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }

        internal fun progress(context: Context): Notification {
            val jobs = PodcastStudio.all().filter { it.error == null }
            val first = jobs.firstOrNull { !it.queued } ?: jobs.firstOrNull()
            val title = first?.let { PodcastStore.find(it.podcastId)?.title } ?: "Podcasts"
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_video_craft)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openIntent(context))
                .setContentTitle("Making $title")
                .setContentText(first?.stage ?: "Starting…")
            if (jobs.size > 1) builder.setSubText("${jobs.size - 1} more queued")
            if (first != null && !first.queued) builder.setProgress(1000, (first.fraction * 1000).toInt(), false)
            else builder.setProgress(0, 0, true)
            if (first != null) {
                builder.addAction(
                    0, "Stop",
                    PendingIntent.getService(
                        context, first.podcastId.hashCode(),
                        Intent(context, PodcastStudioService::class.java).setAction(ACTION_STOP).putExtra("id", first.podcastId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return builder.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra("id")?.let(PodcastStudio::cancel)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(progress(this))
        running = true
        if (!PodcastStudio.active) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        PrismLogger.logWarning(TAG, "Android ended the podcast service after its time limit; the work continues")
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
