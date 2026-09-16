package com.prism.launcher.quant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.quant.PrismQuantizer.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs a quantisation in the background, with a persistent notification.
 *
 * A FOREGROUND SERVICE, for the reason [com.prism.launcher.nora.CaptionForegroundService] spells
 * out: this is work the user just asked for and is waiting on, which is what a foreground service is
 * for and what WorkManager explicitly is not. Quantising even a small model takes minutes, and the
 * user will leave the page -- the notification is what keeps the run visible after they do.
 *
 * ## Progress is measured, not animated
 *
 * `llama_model_quantize` has no progress callback, so rather than invent a timer this watches the
 * output file grow against an estimate of its final size. That number is real: the file only extends
 * as tensors are finished. It is capped below 100% until the native call actually returns, because
 * an estimate that runs ahead of the work would show "done" on a model that cannot be opened yet.
 */
class QuantizationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRun()
            return START_NOT_STICKY
        }

        val sourcePath = intent?.getStringExtra(EXTRA_SOURCE)
        val levelName = intent?.getStringExtra(EXTRA_LEVEL)
        if (sourcePath == null || levelName == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val level = runCatching { Level.valueOf(levelName) }.getOrNull()
        val source = File(sourcePath)
        if (level == null || !source.isFile) {
            stopSelf()
            return START_NOT_STICKY
        }

        // One at a time. Two quantisations at once would compete for the same cores and the same
        // memory bandwidth and finish later than running them in sequence, and the notification can
        // only show one.
        if (worker?.isActive == true) {
            PrismLogger.logWarning(TAG, "A quantisation is already running; ignoring the new request")
            return START_NOT_STICKY
        }

        val destination = destinationFor(source, level)
        val expectedBytes = PrismQuantizer.estimateOutputBytes(source, level)
        val modelName = source.nameWithoutExtension

        if (!hasRoomFor(destination, expectedBytes)) {
            val needed = (expectedBytes + SPACE_MARGIN_BYTES) / (1024 * 1024)
            _state.value = State(
                running = false, modelName = modelName, level = level, finished = true,
                error = "Not enough free space — about $needed MB is needed",
            )
            PrismLogger.logWarning(TAG, "Refusing to quantise ${source.name}: not enough free space")
            stopSelf()
            return START_NOT_STICKY
        }

        _state.value = State(
            running = true,
            modelName = modelName,
            level = level,
            percent = 0,
            outputPath = destination.absolutePath,
        )

        startForeground(NOTIFICATION_ID, buildNotification(_state.value))

        watcher = scope.launch {
            while (isActive) {
                delay(1_000)
                val current = _state.value
                if (!current.running) break
                val written = destination.length()
                // Never 100 from here: only the native call returning proves the file is complete.
                val percent = if (expectedBytes > 0) {
                    ((written * 100) / expectedBytes).toInt().coerceIn(0, 99)
                } else 0
                _state.value = current.copy(percent = percent, bytesWritten = written)
                notify(buildNotification(_state.value))
            }
        }

        worker = scope.launch {
            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
            PrismLogger.logInfo(TAG, "Quantising ${source.name} to ${level.label} on $threads thread(s)")

            val error = PrismQuantizer.quantize(source, destination, level, threads)

            watcher?.cancel()
            if (error == null) {
                importResult(destination, modelName, level)
                _state.value = State(
                    running = false,
                    modelName = modelName,
                    level = level,
                    percent = 100,
                    outputPath = destination.absolutePath,
                    finished = true,
                )
                PrismLogger.logSuccess(TAG, "Quantised ${source.name} to ${level.label}")
            } else {
                _state.value = State(
                    running = false,
                    modelName = modelName,
                    level = level,
                    percent = 0,
                    outputPath = null,
                    finished = true,
                    error = error,
                )
                PrismLogger.logError(TAG, "Quantising ${source.name} failed: $error")
            }
            finish(_state.value)
        }

        // NOT_STICKY: restarted with no intent there is nothing to resume, and a quantisation the
        // user did not ask for a second time would spend another twenty minutes of their battery.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    /**
     * Stops the run and removes the half-written file.
     *
     * The native call cannot be interrupted from here -- it is a blocking call inside llama.cpp with
     * no cancellation hook -- so cancelling tears down the coroutine and deletes the output. The
     * native work keeps going until it finishes writing into a file nothing will read, which is not
     * ideal and is the honest limit of cancelling a third-party blocking call.
     */
    private fun cancelRun() {
        val path = _state.value.outputPath
        worker?.cancel()
        watcher?.cancel()
        runCatching { if (path != null) File(path).delete() }
        _state.value = State(running = false, finished = true, error = "Cancelled")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Where the quantised copy goes, named so the level is visible without opening anything.
     *
     * Beside the source rather than in an app-private directory: the user picked that location for
     * their models, the export action hands the file out from there, and a model the user cannot see
     * in a file browser is one they cannot back up.
     */
    private fun destinationFor(source: File, level: Level): File {
        val base = source.nameWithoutExtension
            // Strip a quantisation already in the name so requantising Q4_K_M to Q2_K does not
            // produce "model-Q4_K_M-Q2_K", which reads as a model that is somehow both.
            .replace(Regex("(?i)[-_.](Q\\d[_A-Z0-9]*|F16|F32|BF16|BQ|TQ|QQ|5QQ)$"), "")
        val name = "$base-${level.fileSuffix}.gguf"

        // Beside the source when that is writable, which is the useful place -- the user's other
        // models are there and a file browser can find it. But an imported model can perfectly well
        // live somewhere this process cannot write: a read-only mount, or a directory handed over
        // through a picker that granted read access only. Falling back to Prism's own models
        // directory means the run succeeds instead of failing at the first write.
        val beside = source.parentFile
        if (beside != null && beside.canWrite()) return File(beside, name)

        val fallback = File(filesDir, "models").apply { mkdirs() }
        PrismLogger.logInfo(
            TAG, "${beside?.absolutePath} is not writable; writing the result to ${fallback.absolutePath}"
        )
        return File(fallback, name)
    }

    /**
     * Whether there is room for the result, with a margin.
     *
     * Checked before starting rather than discovered at 90%: llama.cpp's writer fails partway
     * through on a full disk, which costs the user the whole run and leaves a truncated GGUF that
     * looks like a model. The margin exists because the estimate is an estimate, and because a
     * device with nothing spare will start killing things for other reasons anyway.
     */
    private fun hasRoomFor(destination: File, expectedBytes: Long): Boolean {
        val free = runCatching {
            val dir = destination.parentFile ?: return true
            android.os.StatFs(dir.absolutePath).availableBytes
        }.getOrElse { return true }
        return free > expectedBytes + SPACE_MARGIN_BYTES
    }

    /** Registers the result as an imported model so it appears in the models list immediately. */
    private fun importResult(destination: File, sourceName: String, level: Level) {
        runCatching {
            PrismSettings.addImportedModel(
                PrismSettings.ImportedModel(
                    path = destination.absolutePath,
                    displayName = "$sourceName (${level.fileSuffix})",
                    type = PrismSettings.MODEL_TYPE_TEXT,
                )
            )
        }.onFailure {
            PrismLogger.logError(TAG, "Quantised model could not be added to the model list", it)
        }
    }

    /**
     * Swaps the ongoing notification for a result the user can act on.
     *
     * A separate id, because the ongoing notification belongs to the foreground service and vanishes
     * with it -- posting the summary under the same id would delete the result along with it.
     */
    private fun finish(state: State) {
        val summary = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                if (state.error == null) "Quantised to ${state.level?.fileSuffix}"
                else "Quantisation failed"
            )
            .setContentText(
                if (state.error == null) "${state.modelName} is ready in your models"
                else state.error
            )
            .setContentIntent(openQuantView())
            .setAutoCancel(true)
            .build()

        runCatching { manager().notify(NOTIFICATION_ID + 1, summary) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: State): android.app.Notification {
        ensureChannel()

        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, QuantizationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Quantising to ${state.level?.fileSuffix ?: ""}")
            .setContentText(state.modelName)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(state.modelName)
                        append("\n").append(state.level?.label ?: "")
                        if (state.bytesWritten > 0) {
                            append("\n").append(state.bytesWritten / (1024 * 1024)).append(" MB written")
                        }
                    }
                )
            )
            .setProgress(100, state.percent, state.percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openQuantView())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelIntent)
            .build()
    }

    /**
     * Opens Prism on the models page, showing the quantisation view.
     *
     * SINGLE_TOP rather than a fresh task: the launcher is `singleTask` and already running, so this
     * has to arrive as a new intent on the existing instance -- `onNewIntent` is what actually turns
     * the pager to the right page. Starting a new task would show the launcher on whatever page it
     * was already on and drop the request.
     */
    private fun openQuantView(): PendingIntent {
        val intent = Intent(this, LauncherActivity::class.java)
            .setAction(LauncherActivity.ACTION_SHOW_QUANTISATION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(notification: android.app.Notification) {
        runCatching { manager().notify(NOTIFICATION_ID, notification) }
    }

    private fun manager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quantisation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while Prism quantises a model"
                setShowBadge(false)
            }
        )
    }

    /** What the quantisation view reads to draw itself. */
    data class State(
        val running: Boolean = false,
        val modelName: String = "",
        val level: Level? = null,
        val percent: Int = 0,
        val bytesWritten: Long = 0L,
        /** Where the finished model landed, for the export action. */
        val outputPath: String? = null,
        val finished: Boolean = false,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "PrismQuant"
        private const val CHANNEL_ID = "prism_quantisation"
        private const val NOTIFICATION_ID = 8901
        /** Headroom over the estimate, so a slightly-wrong guess does not fill the device. */
        private const val SPACE_MARGIN_BYTES = 256L * 1024 * 1024

        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_LEVEL = "level"
        const val ACTION_CANCEL = "com.prism.launcher.QUANT_CANCEL"

        /**
         * Process-wide, so the page can be swiped away and come back to a run still in flight.
         * Holding this in the view would mean leaving the page abandoned the progress display while
         * the work carried on invisibly.
         */
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun start(context: Context, source: File, level: Level) {
            val intent = Intent(context, QuantizationService::class.java)
                .putExtra(EXTRA_SOURCE, source.absolutePath)
                .putExtra(EXTRA_LEVEL, level.name)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, QuantizationService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
