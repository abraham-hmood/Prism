package com.prism.launcher.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.prism.core.Notifier
import com.prism.core.PrismPlatform
import com.prism.core.TaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's [TaskScheduler].
 *
 * WHY THIS DOES NOT DELEGATE TO WorkManager. WorkManager persists a job by storing the *class
 * name* of a `ListenableWorker` and reconstructing it later by reflection. There is no way to
 * persist a lambda, so a [TaskScheduler.Job] carrying a `suspend () -> Unit` cannot be handed to
 * it -- the block would have to already exist as a registered Worker class, at which point the
 * scheduler interface is not scheduling anything, it is looking up a name.
 *
 * So the division is: Prism's three real background jobs (AppSyncWorker, SocialBotWorker,
 * NoraAutoTrainWorker) KEEP their existing WorkManager registrations, because their whole value
 * is surviving process death and that is exactly what WorkManager provides and this does not.
 * This implementation serves :core code that wants in-process periodic work while the app is
 * alive, which is the only kind :core can portably express.
 *
 * Stating it plainly because the alternative -- an interface that looks like it gives you
 * WorkManager's durability and silently does not -- is the more dangerous design.
 */
class AndroidTaskScheduler : TaskScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override fun schedule(job: TaskScheduler.Job) {
        cancel(job.name)
        jobs[job.name] = scope.launch {
            kotlinx.coroutines.delay(job.initialDelayMillis)
            while (true) {
                try {
                    job.block()
                } catch (e: Throwable) {
                    // Never let one failed cycle end the loop -- see JvmTaskScheduler for the
                    // same reasoning against a scheduler that silently stops.
                    PrismPlatform.log.error("Prism/scheduler", "Job \"${job.name}\" failed", e)
                }
                kotlinx.coroutines.delay(job.intervalMillis)
            }
        }
    }

    override fun scheduleOnce(name: String, delayMillis: Long, block: suspend () -> Unit) {
        cancel(name)
        jobs[name] = scope.launch {
            kotlinx.coroutines.delay(delayMillis)
            try {
                block()
            } catch (e: Throwable) {
                PrismPlatform.log.error("Prism/scheduler", "Task \"$name\" failed", e)
            }
        }
    }

    override fun cancel(name: String) {
        jobs.remove(name)?.cancel()
    }
}

/** Android's [Notifier]. Channels are created lazily, once per id. */
class AndroidNotifier(private val context: Context) : Notifier {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channels = mutableSetOf<String>()

    override fun notify(channel: String, id: Int, title: String, body: String) {
        ensureChannel(channel)
        val n = androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+. Not an error worth crashing over; the user
            // said no, which is a valid answer.
            PrismPlatform.log.debug("Prism/notify", "Notification suppressed: not permitted")
        }
    }

    /**
     * Ongoing progress notification.
     *
     * Silent and low-importance: a download that re-posts its notification every few percent would
     * otherwise buzz continuously. `setOnlyAlertOnce` covers the same ground for older API levels
     * where the channel importance is not consulted per-post.
     */
    override fun notifyProgress(
        channel: String, id: Int, title: String, body: String, percent: Int, ongoing: Boolean
    ) {
        ensureChannel(channel, NotificationManager.IMPORTANCE_LOW)
        val builder = androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            if (percent in 0..100) builder.setProgress(100, percent, false)
            else builder.setProgress(0, 0, true)
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            PrismPlatform.log.debug("Prism/notify", "Progress notification suppressed: not permitted")
        }
    }

    override fun cancel(id: Int) = manager.cancel(id)

    override fun isAvailable(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(id: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channels.add(id)) return
        manager.createNotificationChannel(NotificationChannel(id, id, importance))
    }
}
