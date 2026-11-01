package com.prism.launcher.imagecraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prism.core.PrismPlatform
import com.prism.launcher.R
import com.prism.launcher.messaging.ImageCraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Makes an ImageCraft run survive the page.
 *
 * ## Why a service rather than the page's own coroutine
 *
 * A render is fifteen seconds on a good phone and minutes on a bad one, and the page it was started
 * from is the sort of thing a user swipes away the moment they are bored of watching a progress bar.
 * [ImageCraft] already runs the work in a process-scoped coroutine, but a coroutine in a
 * backgrounded process is exactly what the low-memory killer is allowed to reap -- which it does, mid
 * render, with no error anywhere. A foreground service raises the process's priority and, more
 * usefully, gives the progress somewhere visible to live: the notification.
 *
 * ## What it reports
 *
 * The same [ImageCraft.State] the page shows, so the two can never disagree -- which is the reason the
 * state lives in :core rather than in either of them. Progress is real when the engine has steps to
 * report and indeterminate when it does not (a MediaPipe bundle has no step callback, and inventing
 * percentages for it would be inventing them). The stage text is kept verbatim because it is where the
 * engine explains a substitution -- a clamped resolution, a backend that could not be used.
 */
class ImageCraftService : Service() {

    companion object {
        const val ACTION_START = "com.prism.launcher.imagecraft.START"
        const val ACTION_CANCEL = "com.prism.launcher.imagecraft.CANCEL"
        const val EXTRA_PROMPT = "imagecraft.prompt"
        const val CHANNEL = "imagecraft_generation"
        const val NOTIFICATION_ID = 7413

        /**
         * Starts a run in the foreground.
         *
         * The prompt travels in the intent because the service is what starts it: a page that called
         * `ImageCraft.start` itself and then asked the service to watch would leave a window where the
         * process could be killed with the render already begun and nothing holding the priority.
         */
        fun start(context: Context, prompt: String) {
            val intent = Intent(context, ImageCraftService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROMPT, prompt)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ImageCraftService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        /**
         * Makes sure the channel exists, without starting anything.
         *
         * Called at startup as well as from [onCreate], because a run started from somewhere this
         * service is not driving -- a tool call, or the desktop-side page -- still has to be able to
         * announce itself, and posting to a channel that does not exist is silently dropped on
         * Android 8 and later.
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "ImageCraft", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Progress for an image being generated in the background"
                        setShowBadge(false)
                    },
            )
        }

        /**
         * Announces a run that finished without this service watching it.
         *
         * Deliberately not a second channel or a second wording: the notification the service posts
         * for a run it drives and this one for a run it does not are the same message, so a user
         * cannot tell (or need to tell) which of the two paths produced it.
         */
        fun notifyFinished(context: Context, state: ImageCraft.State) {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val text = when {
                state.complete && state.savedTo.isNotBlank() -> "Saved to ${state.savedTo}"
                state.complete -> "Ready${if (state.note.isBlank()) "" else " — ${state.note}"}"
                state.error != null -> state.error.orEmpty()
                else -> "Stopped"
            }
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, ImageCraftActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_wand_24)
                    .setContentTitle(if (state.complete) "Image ready" else "ImageCraft")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private var ticker: Job? = null
    private var lastNotifyAt = 0L
    private var stageStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ImageCraft.cancel()
            stopForeground(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val prompt = intent?.getStringExtra(EXTRA_PROMPT).orEmpty().trim()
        if (prompt.isEmpty() || watch?.isActive == true) {
            stopSelf()
            return START_NOT_STICKY
        }

        stageStartedAt = System.currentTimeMillis()
        startForeground(
            NOTIFICATION_ID,
            notification(
                title = "Drawing",
                text = "Asking the text model for a prompt…",
                progress = 0f,
                indeterminate = true,
                running = true,
            ),
        )

        if (!ImageCraft.start(prompt)) {
            // The gate refused: no text model, no image model, or a run already in flight. Whatever
            // the reason, it is in the state as an error, so the notification says it rather than
            // leaving a spinner on screen for a run that never began.
            val reason = ImageCraft.state.value.error ?: "Nothing was started."
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("ImageCraft", reason, 0f, false, running = false),
            )
            stopForeground(false)
            stopSelf()
            return START_NOT_STICKY
        }

        watch = scope.launch { follow() }
        ticker = scope.launch { tick() }
        return START_NOT_STICKY
    }

    /** Mirrors [ImageCraft.state] into the notification until the run stops being in flight. */
    private suspend fun follow() {
        var lastStage = ""
        ImageCraft.state.collect { state ->
            if (state.stage.isNotEmpty() && state.stage != lastStage) {
                lastStage = state.stage
                stageStartedAt = System.currentTimeMillis()
                PrismPlatform.log.info(
                    "ImageCraft",
                    "${state.engine.ifEmpty { "engine" }}: ${state.stage}",
                )
            }

            val now = System.currentTimeMillis()
            if (state.running && now - lastNotifyAt < 350) return@collect
            lastNotifyAt = now

            if (state.running) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        title = "ImageCraft",
                        text = state.stage.ifEmpty { "Working…" },
                        progress = state.progress,
                        indeterminate = state.progress < 0f,
                        running = true,
                        canCancel = true,
                    ),
                )
                return@collect
            }

            // The run has stopped: either it produced a picture, or it said why it could not.
            val done = when {
                state.complete && state.savedTo.isNotBlank() -> "Saved to ${state.savedTo}"
                state.complete -> "Finished${if (state.note.isBlank()) "" else " — ${state.note}"}"
                state.error != null -> state.error.orEmpty()
                else -> "Stopped"
            }
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(
                    title = if (state.complete) "Image ready" else "ImageCraft",
                    text = done,
                    progress = if (state.complete) 1f else 0f,
                    indeterminate = false,
                    running = false,
                ),
            )
            ticker?.cancel()
            stopForeground(false)
            stopSelf()
        }
    }

    /** Reposts every two seconds so the time spent on the current stage is visible. */
    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            val state = ImageCraft.state.value
            if (!state.running || state.stage.isEmpty()) continue
            runCatching {
                val elapsed = if (stageStartedAt == 0L) 0 else
                    (System.currentTimeMillis() - stageStartedAt) / 1000
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        title = "ImageCraft",
                        text = "${state.stage} · %d:%02d".format(elapsed / 60, elapsed % 60),
                        progress = state.progress,
                        indeterminate = state.progress < 0f,
                        running = true,
                        canCancel = true,
                    ),
                )
            }
        }
    }

    private fun notification(
        title: String,
        text: String,
        progress: Float,
        indeterminate: Boolean,
        running: Boolean,
        canCancel: Boolean = false,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, ImageCraftActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wand_24)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (running) {
            builder.setProgress(
                100,
                (progress.coerceIn(0f, 1f) * 100).toInt(),
                indeterminate || progress < 0f,
            )
        } else {
            builder.setProgress(0, 0, false)
        }

        if (canCancel) {
            val cancel = PendingIntent.getService(
                this,
                2,
                Intent(this, ImageCraftService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Stop", cancel)
        }
        return builder.build()
    }

    private fun createChannel() = ensureChannel(this)

    override fun onDestroy() {
        watch?.cancel()
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
