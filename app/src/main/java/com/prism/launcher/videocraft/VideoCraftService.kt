package com.prism.launcher.videocraft

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
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place the page and the notification agree about a render.
 *
 * A render survives the page being swiped away because the engine runs in a foreground service, not
 * in the view. The page observes this flow, so returning to VideoCraft reattaches to a run that
 * started before the activity was recreated.
 */
object VideoCraftRuns {

    data class State(
        val projectId: String = "",
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val previewPath: String = "",
        val videoPath: String = "",
        val error: String? = null,
        val complete: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun publish(state: State) {
        _state.value = state
    }

    fun isRunning(id: String): Boolean = _state.value.running && _state.value.projectId == id
}

class VideoCraftService : Service() {

    companion object {
        const val ACTION_START = "com.prism.launcher.videocraft.START"
        const val ACTION_CANCEL = "com.prism.launcher.videocraft.CANCEL"
        const val EXTRA_ID = "videocraft.project"
        const val CHANNEL = "videocraft_render"
        const val NOTIFICATION_ID = 7412

        fun start(context: Context, projectId: String) {
            val intent = Intent(context, VideoCraftService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, projectId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, projectId: String) {
            val intent = Intent(context, VideoCraftService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, projectId)
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null
    private var ticker: Job? = null
    private var projectId: String = ""
    private var lastNotifyAt = 0L
    private var stageStartedAt = 0L
    private var currentStage = ""
    private var currentProgress = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            job?.cancel()
            ticker?.cancel()
            VideoCraftRuns.publish(
                VideoCraftRuns.state.value.copy(running = false, stage = "Paused", error = null),
            )
            stopForeground(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (id.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        projectId = id
        cancelled.set(false)
        currentStage = "Preparing"
        currentProgress = 0f
        stageStartedAt = System.currentTimeMillis()
        startForeground(
            NOTIFICATION_ID,
            notification("Preparing", 0f, indeterminate = true),
        )
        VideoCraftRuns.publish(VideoCraftRuns.State(projectId = id, running = true, stage = "Preparing"))
        job = scope.launch { render(id) }
        // The clock is not decoration. A language-model call for the story bible, or one native
        // sampling pass, can take minutes and reports nothing while it runs; without a number that
        // moves, the notification and the page look exactly like a render that has died.
        ticker?.cancel()
        ticker = scope.launch { tick() }
        return START_NOT_STICKY
    }

    private fun render(id: String) {
        val project = VideoCraftStore.load(id) ?: run {
            finishWithError(id, "That project could not be read")
            return
        }
        val sink = AndroidVideoCraftSink(this)
        val engine = VideoCraftEngine(
            text = VideoCraftText.fromSettings(),
            // Shared with the desktop build, so the same selected checkpoint is what draws the
            // frames on both. See VideoCraftImageRouter.
            image = com.prism.launcher.videocraft.VideoCraftImageRouter(),
            interpolator = AndroidVideoCraftInterpolators.create(),
            speech = AndroidVideoCraftSpeech(),
            // AN ACTIVE CLIP MODEL SUPPLIES THE WHOLE FRAME PIPELINE. This stays null when none is
            // selected, and then the engine draws keyframes and interpolates between them as before.
            video = com.prism.launcher.videocraft.VideoCraftVideoRouter.create(),
            cancelled = { cancelled.get() },
        )
        try {
            engine.render(project, sink) { progress ->
                val preview = project.lastFramePath
                if (progress.stage != currentStage) {
                    currentStage = progress.stage
                    stageStartedAt = System.currentTimeMillis()
                    // One line per stage in the log the user can actually send. A render that stops
                    // moving can only be diagnosed if the log says which stage it reached.
                    PrismPlatform.log.info(
                        "VideoCraft",
                        "${project.id}: ${progress.stage} at ${(progress.fraction * 100).toInt()}%",
                    )
                }
                currentProgress = progress.fraction
                VideoCraftRuns.publish(
                    VideoCraftRuns.State(
                        projectId = id,
                        running = true,
                        stage = progress.stage,
                        progress = progress.fraction,
                        previewPath = preview,
                    ),
                )
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt > 350 || progress.fraction >= 1f) {
                    lastNotifyAt = now
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID,
                        notification(progress.stage, progress.fraction, indeterminate = false),
                    )
                }
            }
            VideoCraftRuns.publish(
                VideoCraftRuns.State(
                    projectId = id,
                    running = false,
                    stage = "Complete",
                    progress = 1f,
                    videoPath = project.outputPath,
                    previewPath = project.lastFramePath,
                    complete = true,
                ),
            )
            val done = notification("Complete — tap to watch", 1f, indeterminate = false)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, done)
            stopForeground(false)
        } catch (c: VideoCraftCancelled) {
            VideoCraftRuns.publish(
                VideoCraftRuns.State(
                    projectId = id, running = false, stage = "Paused",
                    previewPath = project.lastFramePath,
                ),
            )
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, notification("Paused — open VideoCraft to finish", 0f, false),
            )
            stopForeground(false)
        } catch (t: Throwable) {
            finishWithError(id, t.message ?: "The render was interrupted")
        } finally {
            stopSelf()
        }
    }

    /** Reposts the notification every two seconds with the time spent on the current stage. */
    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            if (!VideoCraftRuns.state.value.running || currentStage.isEmpty()) continue
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(stampedStage(), currentProgress, indeterminate = false),
                )
            }
        }
    }

    private fun elapsedSeconds(): Long =
        if (stageStartedAt == 0L) 0 else (System.currentTimeMillis() - stageStartedAt) / 1000

    private fun stampedStage(): String =
        if (stageStartedAt == 0L) currentStage else "$currentStage · ${clock(elapsedSeconds())}"

    private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun finishWithError(id: String, message: String) {
        VideoCraftRuns.publish(
            VideoCraftRuns.State(projectId = id, running = false, stage = "Interrupted", error = message),
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification("Interrupted: $message", 0f, false),
        )
        ticker?.cancel()
        stopForeground(false)
    }

    private fun notification(text: String, progress: Float, indeterminate: Boolean): Notification {
        val open = Intent(this, VideoCraftActivity::class.java).apply {
            putExtra(EXTRA_ID, projectId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val content = PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = Intent(this, VideoCraftService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_ID, projectId)
        }
        val cancelPending = PendingIntent.getService(
            this, 2, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_video_craft)
            .setContentTitle("VideoCraft")
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(!indeterminate && progress < 1f)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), indeterminate)
            .addAction(0, "Pause", cancelPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL, "VideoCraft renders", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for a film being generated in the background"
                setShowBadge(false)
            },
        )
    }

    override fun onDestroy() {
        cancelled.set(true)
        job?.cancel()
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
