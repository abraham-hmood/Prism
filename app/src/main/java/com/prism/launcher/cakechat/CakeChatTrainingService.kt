package com.prism.launcher.cakechat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Runs a CakeChat training run, and keeps it alive while the user looks away.
 *
 * ## Why a foreground service rather than a thread in the activity
 *
 * Training a recurrent seq2seq on a phone runs for a long time. A thread owned by an activity dies
 * with it -- the moment the screen locks or the user checks a message, Android is free to tear the
 * process down and the run is lost with no way to resume, because CakeChat checkpoints only at the
 * end. A foreground service with an ongoing notification is the one arrangement Android will not
 * kill, and the notification is required for that rather than being decoration.
 *
 * ## Progress is polled, not pushed
 *
 * Python holds the state and Kotlin reads it once a second. The alternative -- calling back into
 * Kotlin from inside Keras's batch callback -- would cross the JNI boundary on every batch, which
 * costs more than the read and puts a Java frame in the middle of the training loop.
 */
class CakeChatTrainingService : Service() {

    companion object {
        private const val CHANNEL_ID = "prism_cakechat_training"
        private const val NOTIFICATION_ID = 0x0CAC

        const val ACTION_START = "com.prism.launcher.cakechat.TRAIN"
        const val ACTION_STOP = "com.prism.launcher.cakechat.STOP"
        const val ACTION_CONVERT = "com.prism.launcher.cakechat.CONVERT"
        const val EXTRA_EPOCHS = "epochs"
        const val EXTRA_BATCH_SIZE = "batch_size"
        const val EXTRA_SUBSET = "subset_size"
        const val EXTRA_HIDDEN_DIM = "hidden_dim"

        /** What the activity renders. Survives the activity, because the service does. */
        val state = MutableStateFlow(Progress())

        fun start(
            context: Context, epochs: Int, batchSize: Int, subsetSize: Int, hiddenDim: Int,
        ) {
            val intent = Intent(context, CakeChatTrainingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_EPOCHS, epochs)
                .putExtra(EXTRA_BATCH_SIZE, batchSize)
                .putExtra(EXTRA_SUBSET, subsetSize)
                .putExtra(EXTRA_HIDDEN_DIM, hiddenDim)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Converts an already-trained model to TFLite.
         *
         * IN THIS SERVICE BECAUSE IT LOADS TENSORFLOW. Conversion builds the network and reads the
         * weights, which is the same several hundred megabytes training needs -- doing it in the
         * launcher's process is what the `:cakechat` split exists to avoid.
         */
        fun convert(context: Context) {
            val intent = Intent(context, CakeChatTrainingService::class.java)
                .setAction(ACTION_CONVERT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CakeChatTrainingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    data class Progress(
        val running: Boolean = false,
        val phase: String = "idle",
        val step: Int = 0,
        val total: Int = 0,
        val epoch: Int = 0,
        val epochs: Int = 0,
        val loss: Double = 0.0,
        val elapsedSeconds: Long = 0,
        val etaSeconds: Long = 0,
        val logs: List<String> = emptyList(),
        val error: String? = null,
        val finished: Boolean = false,
    ) {
        val percent: Int get() = if (total <= 0) 0 else ((step * 100) / total).coerceIn(0, 100)
        val stepsLeft: Int get() = (total - step).coerceAtLeast(0)
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var poller: Thread? = null

    /** Screens currently watching. Empty for most of a long run, which is fine. */
    private val clients = java.util.concurrent.CopyOnWriteArrayList<android.os.Messenger>()

    /**
     * Keeps the CPU alive while training.
     *
     * WITHOUT THIS, LOCKING THE SCREEN STOPS THE RUN. A foreground service is protected from being
     * killed, but it is NOT protected from the CPU sleeping -- Android suspends the application
     * processor shortly after the screen goes off, and a compute loop simply stops advancing. A
     * partial wake lock is what says "keep the CPU running, the screen can go".
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val incoming = android.os.Handler(android.os.Looper.getMainLooper()) { message ->
        when (message.what) {
            CakeChatIpc.MSG_REGISTER_CLIENT -> {
                message.replyTo?.let { client ->
                    clients.add(client)
                    // Answered immediately so a screen opened mid-run shows real numbers rather
                    // than waiting up to a second for the next poll.
                    runCatching { client.send(progressMessage(state.value)) }
                }
                true
            }
            CakeChatIpc.MSG_UNREGISTER_CLIENT -> {
                message.replyTo?.let { clients.remove(it) }
                true
            }
            CakeChatIpc.MSG_INFER -> {
                val reply = message.replyTo
                val dialog = message.data?.getStringArray(CakeChatIpc.KEY_INFER_TEXT)?.toList()
                    ?: emptyList()
                val emotion = message.data?.getString(CakeChatIpc.KEY_INFER_EMOTION)
                    ?: CakeChatEngine.DEFAULT_EMOTION
                if (reply != null && dialog.isNotEmpty()) answerInBackground(reply, dialog, emotion)
                true
            }
            else -> false
        }
    }

    private val binder = android.os.Messenger(incoming)

    /**
     * Runs one inference and answers the caller.
     *
     * OFF THE HANDLER THREAD, because this is not quick: the first call in a process imports
     * TensorFlow, builds the network and loads the weights, which is tens of seconds. Blocking the
     * Messenger's Looper would also block every progress message and any second request.
     *
     * Refuses while training. The two cannot share a process: both build a TensorFlow graph, and
     * running them together doubles the peak memory on a device that is already the reason
     * inference was moved out here.
     */
    private fun answerInBackground(
        client: android.os.Messenger, dialog: List<String>, emotion: String,
    ) {
        if (worker != null) {
            sendInferResult(client, null, "CakeChat is training; inference is paused until it finishes.")
            return
        }

        Thread({
            logMemoryBudget()
            val result = runCatching {
                val module = CakeChatBridge.module(applicationContext)
                module.callAttr(
                    "infer",
                    CakeChatInstall.root(applicationContext).absolutePath,
                    CakeChatInstall.weightsDir(applicationContext).absolutePath,
                    dialog.toTypedArray(),
                    emotion,
                )?.toString()?.trim()
            }

            val reply = result.getOrNull()
            val error = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: "Inference threw."
                reply.isNullOrEmpty() ->
                    runCatching { CakeChatBridge.lastError(applicationContext) }.getOrNull()
                        ?: "CakeChat produced no reply."
                else -> null
            }
            if (error != null) PrismLogger.logError("CakeChat", "Inference failed: $error", null)
            sendInferResult(client, reply?.takeIf { it.isNotEmpty() }, error)
        }, "cakechat-infer").apply { isDaemon = true; start() }
    }

    /**
     * Records what this device has to work with, before the allocation that may end the process.
     *
     * Written up front for the same reason the Python side writes breadcrumbs: if the model does
     * not fit, the process dies without reporting anything, and these numbers are then the only
     * evidence of why. "Killed with 180 MB free while loading 84 MB of weights" is a diagnosis;
     * "it crashed" is not.
     */
    private fun logMemoryBudget() {
        runCatching {
            val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)

            val weights = CakeChatInstall.weightsDir(applicationContext)
                .listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

            fun mb(bytes: Long) = bytes / (1024 * 1024)
            PrismLogger.logInfo(
                "CakeChat",
                "Loading model: ${mb(weights)} MB of weights · device has ${mb(info.availMem)} MB " +
                    "free of ${mb(info.totalMem)} MB" +
                    (if (info.lowMemory) " · SYSTEM IS LOW ON MEMORY" else "") +
                    " · kill threshold ${mb(info.threshold)} MB"
            )
        }
    }

    private fun sendInferResult(client: android.os.Messenger, reply: String?, error: String?) {
        runCatching {
            client.send(
                android.os.Message.obtain(null, CakeChatIpc.MSG_INFER_RESULT).apply {
                    data = android.os.Bundle().apply {
                        putString(CakeChatIpc.KEY_INFER_REPLY, reply)
                        putString(CakeChatIpc.KEY_INFER_ERROR, error)
                    }
                }
            )
        }
    }

    private fun progressMessage(progress: Progress): android.os.Message =
        android.os.Message.obtain(null, CakeChatIpc.MSG_PROGRESS).apply {
            data = CakeChatIpc.pack(progress)
        }

    /** Pushes a snapshot to every watcher, dropping any whose process has gone. */
    private fun publish(progress: Progress) {
        state.value = progress
        for (client in clients) {
            runCatching { client.send(progressMessage(progress)) }
                .onFailure { clients.remove(client) }
        }
    }

    /** Python log lines already forwarded to diagnostics, so polling cannot duplicate them. */
    private val mirroredLogs = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun onBind(intent: Intent?): IBinder = binder.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_CONVERT -> beginConversion()
            ACTION_START -> beginTraining(
                intent.getIntExtra(EXTRA_EPOCHS, 1),
                intent.getIntExtra(EXTRA_BATCH_SIZE, 32),
                intent.getIntExtra(EXTRA_SUBSET, 0),
                intent.getIntExtra(EXTRA_HIDDEN_DIM, 0),
            )
            // A restart with no intent means Android killed and revived us. There is no checkpoint
            // to resume from, so the honest thing is to stop rather than silently start over.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Runs the TFLite conversion as its own job, with the same progress the trainer publishes.
     *
     * Shares [worker] with training so the two cannot overlap: both build a TensorFlow graph, and
     * running them together on a phone is the memory spike that gets the process killed.
     */
    private fun beginConversion() {
        if (worker != null) return
        ensureChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(Progress(running = true, phase = "converting")))
        publish(Progress(running = true, phase = "converting"))

        worker = Thread({
            val python = runCatching { CakeChatBridge.module(applicationContext) }.getOrNull()
            if (python == null) {
                finish(Progress(error = "Python could not be started on this device.", finished = true))
                return@Thread
            }

            val ok = runCatching {
                python.callAttr(
                    "export_tflite",
                    CakeChatInstall.root(applicationContext).absolutePath,
                    CakeChatInstall.weightsDir(applicationContext).absolutePath,
                ).toBoolean()
            }.getOrElse {
                PrismLogger.logError("CakeChat", "TFLite conversion threw", it)
                false
            }

            val error = if (ok) null else
                runCatching { python.callAttr("last_error")?.toString() }.getOrNull()
                    ?: "Converting the model for mobile did not work."
            if (error != null) PrismLogger.logError("CakeChat", "Conversion failed: $error", null)
            else PrismLogger.logSuccess("CakeChat", "Converted the model to TFLite")

            finish(Progress(running = false, finished = true, phase = "idle", error = error))
        }, "cakechat-convert").apply { isDaemon = true; start() }
    }

    private fun beginTraining(
        epochs: Int, batchSize: Int, subsetSize: Int, hiddenDim: Int,
    ) {
        if (worker != null) return
        ensureChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(Progress(running = true, phase = "starting")))

        publish(Progress(running = true, phase = "starting", epochs = epochs))

        worker = Thread({
            val python = runCatching { CakeChatBridge.module(applicationContext) }.getOrNull()
            if (python == null) {
                finish(Progress(error = "Python could not be started on this device.", finished = true))
                return@Thread
            }

            startPolling()

            val dataset = CakeChatInstall.activeDatasetFile(applicationContext)
            if (dataset == null) {
                finish(
                    Progress(
                        error = "No corpus selected. Choose a file, or tick the bundled dataset.",
                        finished = true,
                    )
                )
                return@Thread
            }

            val ok = runCatching {
                python.callAttr(
                    "train",
                    CakeChatInstall.root(applicationContext).absolutePath,
                    dataset.absolutePath,
                    CakeChatInstall.weightsDir(applicationContext).absolutePath,
                    epochs,
                    0,          // train_subset_size: superseded by subset_size below
                    batchSize,
                    subsetSize,
                    hiddenDim,
                ).toBoolean()
            }.getOrElse {
                PrismLogger.logError("CakeChat", "Training threw", it)
                false
            }

            // CONVERTED AS PART OF TRAINING, not as a step the user has to know about. Without a
            // TFLite build, answering falls back to TensorFlow and its ~500 MB peak, which on most
            // phones is the difference between a reply and a killed process.
            //
            // Garbage collected first: the training graph is still reachable at this point, and
            // conversion traces a second copy of the network. Failure is logged, never fatal --
            // the weights are saved either way.
            if (ok) {
                runCatching {
                    python.callAttr("collect_garbage")
                    publish(state.value.copy(phase = "converting", running = true))
                    val converted = python.callAttr(
                        "export_tflite",
                        CakeChatInstall.root(applicationContext).absolutePath,
                        CakeChatInstall.weightsDir(applicationContext).absolutePath,
                    ).toBoolean()
                    if (converted) {
                        PrismLogger.logSuccess("CakeChat", "Converted the model to TFLite")
                    } else {
                        PrismLogger.logWarning(
                            "CakeChat",
                            "Trained, but the TFLite conversion failed: " +
                                (python.callAttr("last_error")?.toString() ?: "no reason given"),
                        )
                    }
                }.onFailure {
                    PrismLogger.logError("CakeChat", "TFLite conversion threw", it)
                }
            }

            val error = if (ok) null else
                runCatching { python.callAttr("last_error")?.toString() }.getOrNull()
                    ?: "Training failed."

            // INTO DIAGNOSTICS, not only into the activity's caption. A training run fails after
            // the user has stopped watching, and the caption is gone the moment the screen is
            // closed -- while the traceback is the only thing that says which of the bridges
            // (keras alias, TF1 compat, the S3 stubs, the corpus format) actually gave way.
            if (error != null) {
                PrismLogger.logError("CakeChat", "Training failed:\n$error", null)
            } else {
                PrismLogger.logSuccess("CakeChat", "Training finished; weights written")
            }

            finish(
                state.value.copy(
                    running = false,
                    finished = true,
                    error = error,
                )
            )
        }, "cakechat-train").also {
            // BELOW the UI. TensorFlow's own thread budget is capped in prism_cakechat, but the
            // Python thread driving it still competes with the launcher for scheduling; at default
            // priority the whole device stutters for the length of a run. Android's THREAD_PRIORITY
            // is the Linux nice value, so this asks the scheduler to prefer anything interactive.
            it.priority = Thread.MIN_PRIORITY
            it.start()
        }
    }

    /**
     * Reads Python's state once a second and republishes it.
     *
     * One second because that is the fastest a notification is worth updating -- Android rate-limits
     * them anyway -- and because a tighter loop would spend more time crossing into Python than the
     * numbers change.
     */
    private fun startPolling() {
        poller = Thread({
            val python = runCatching { CakeChatBridge.module(applicationContext) }.getOrNull()
                ?: return@Thread
            while (worker?.isAlive == true) {
                runCatching {
                    // Re-keyed by STRING rather than indexed with PyObject.fromJava. asMap() hands
                    // back PyObject keys, and relying on a synthesised PyObject comparing equal to
                    // an interpreter-owned one is the kind of assumption that silently yields null
                    // for every field and reports a run stuck at zero.
                    val snapshot = python.callAttr("progress").asMap()
                        .entries.associate { (k, v) -> k.toString() to v }
                    fun num(key: String): Double = snapshot[key]?.toDouble() ?: 0.0
                    fun str(key: String): String = snapshot[key]?.toString().orEmpty()

                    val logs = python.callAttr("recent_logs", 60).asList().map { it.toString() }
                    // Mirrored once each. Python keeps its own ring buffer and this polls it every
                    // second, so without tracking what has already been forwarded the same line
                    // would be written to diagnostics sixty times a minute.
                    for (line in logs) {
                        if (mirroredLogs.add(line)) PrismLogger.logInfo("CakeChat", line)
                    }

                    publish(state.value.copy(
                        running = true,
                        phase = str("phase"),
                        step = num("step").toInt(),
                        total = num("total").toInt(),
                        epoch = num("epoch").toInt(),
                        epochs = num("epochs").toInt(),
                        loss = num("loss"),
                        elapsedSeconds = num("elapsed").toLong(),
                        etaSeconds = num("eta").toLong(),
                        logs = logs,
                    ))
                    updateNotification(state.value)
                }
                Thread.sleep(1_000)
            }
        }, "cakechat-poll").also {
            it.priority = Thread.MIN_PRIORITY
            it.start()
        }
    }

    private fun requestStop() {
        runCatching { CakeChatBridge.module(applicationContext).callAttr("stop") }
        // The Python side stops at the next batch boundary; the service stays up until the worker
        // actually returns, so a half-written weights file is never left behind as if it were done.
        PrismLogger.logInfo("CakeChat", "Stop requested; finishing the current batch")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = power.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "Prism:CakeChatTraining"
            ).apply {
                setReferenceCounted(false)
                // No timeout: a run has no predictable length, and a lock that expired mid-training
                // would stall it exactly as the screen lock did. Released in finish(), which every
                // exit path goes through.
                acquire()
            }
            PrismLogger.logInfo("CakeChat", "Wake lock held for the duration of training")
        }.onFailure {
            PrismLogger.logWarning("CakeChat", "No wake lock; locking the screen may stall training")
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun finish(final: Progress) {
        releaseWakeLock()
        publish(final)
        worker = null
        poller = null
        clients.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (final.error != null) {
            // The failure notification is NOT ongoing and outlives the service, because by the time
            // a long run fails the user has certainly stopped watching.
            notificationManager().notify(NOTIFICATION_ID, buildFailureNotification(final.error))
        }
        stopSelf()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "CakeChat training", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while a CakeChat model trains"
                setShowBadge(false)
            }
        )
    }

    /** Tapping the notification returns to the trainer, which is the only place to act on it. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, CakeChatTrainingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(progress: Progress) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Training CakeChat")
            .setContentText(summaryOf(progress))
            .setSubText(if (progress.total > 0) "${progress.percent}%" else null)
            .setProgress(100, progress.percent, progress.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    private fun buildFailureNotification(reason: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("CakeChat training failed")
            .setContentText(reason.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason.take(1500)))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    private fun updateNotification(progress: Progress) {
        runCatching { notificationManager().notify(NOTIFICATION_ID, buildNotification(progress)) }
    }

    private fun summaryOf(progress: Progress): String = buildString {
        if (progress.phase == "preprocessing") {
            append("Preparing corpus and index files")
            return@buildString
        }
        if (progress.total > 0) {
            append("Step ").append(progress.step).append('/').append(progress.total)
            append("  ·  ").append(progress.stepsLeft).append(" left")
        } else {
            append("Starting")
        }
        append("  ·  ").append(clock(progress.elapsedSeconds)).append(" elapsed")
        if (progress.etaSeconds > 0) {
            append("  ·  ETA ").append(clock(progress.etaSeconds))
        }
    }

    /** `h:mm:ss` past an hour, `m:ss` below it. */
    private fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
