package com.prism.launcher.aether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.prism.launcher.aether.AetherIpcProtocol as Ipc

/**
 * Owns Aether's long-running work (training, chat generation) -- same shape and rationale as
 * `NoraService`, and a fully independent job/wake-lock/notification from it: Nora and Aether are
 * two different brains that can legitimately run concurrently, so this does not share
 * `NoraService`'s single-job guard.
 *
 * BOUND AS WELL AS STARTED, now that this runs in its own process ([android.R.attr.process]
 * `:aether` on the manifest entry). [onBind] returns a [Messenger] so [AetherIpcClient] in the
 * main process can subscribe to the state-OUT relay -- see [Ipc]'s doc comment for why that
 * bridge exists at all. This does not change the command-IN path at all: `startTraining`/
 * `sendChat`/`testGenerate`/`stop` below are unchanged, since `startService`/
 * `startForegroundService` Intents already cross the process boundary natively.
 */
class AetherService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + PrismLogger.coroutineHandler(AetherLog.Area.SERVICE)
    )
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private val isWorking: Boolean get() = job?.isActive == true

    // ── IPC: relay state-OUT to bound clients in the main process ───────────

    private val clients = mutableListOf<Messenger>()
    private var lastRelayAt = 0L

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            Ipc.MSG_REGISTER_CLIENT -> msg.replyTo?.let { client ->
                val isNew = synchronized(clients) { if (client !in clients) { clients.add(client); true } else false }
                if (isNew) sendCatchUp(client)
            }
            Ipc.MSG_UNREGISTER_CLIENT -> msg.replyTo?.let { synchronized(clients) { clients.remove(it) } }
        }
        true
    }
    private val messenger = Messenger(incomingHandler)

    /**
     * [relay] is fire-and-forget to whoever happens to be bound at the moment -- there is no
     * queue for the rest of the time, so anything relayed while no Aether screen was open (e.g.
     * the "Applied received knowledge…"/"Merged knowledge from…" log line from a Settings-screen
     * "tap to receive," which fires from [applyPendingKnowledge] regardless of whether
     * [AetherTrainingActivity] is even open) would otherwise be lost forever instead of showing up
     * the next time someone opens the training screen to check it, as the UI explicitly invites
     * them to do. [AetherTrainingState] in this (`:aether`) process already keeps its own 300-line
     * log replay buffer for its own same-process collectors -- reuse that instead of a second
     * buffer just for IPC, and also catch the client up on current progress/running/summary so the
     * screen doesn't sit blank until the next throttled relay tick.
     */
    private fun sendCatchUp(client: Messenger) {
        try {
            for (line in AetherTrainingState.log.replayCache) {
                client.send(Message.obtain(null, Ipc.MSG_TRAINING_LOG).apply {
                    data = Bundle().apply { putString(Ipc.KEY_LOG_LINE, line) }
                })
            }
            if (AetherTrainingState.running.value) {
                client.send(Message.obtain(null, Ipc.MSG_TRAINING_STARTED))
            }
            AetherTrainingState.progress.value?.let { p ->
                client.send(Message.obtain(null, Ipc.MSG_TRAINING_PROGRESS).apply { data = progressBundle(p) })
            }
            AetherTrainingState.summary.value?.let { s ->
                client.send(Message.obtain(null, Ipc.MSG_TRAINING_FINISHED).apply {
                    data = Bundle().apply { putString(Ipc.KEY_SUMMARY, s) }
                })
            }
        } catch (e: RemoteException) {
            // Client died between registering and this catch-up -- the next relay's dead-client cleanup handles it.
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(clients) { clients.clear() }
        // A bound service outlives stopSelf() while anything is bound. Once nothing is, re-check
        // whether there is still a reason to be alive -- mirrors finishWork()/stopWork()'s own
        // stopSelf() calls for the case where those fired while a client was still bound.
        if (!isWorking) stopSelf()
        return false
    }

    /**
     * Sends [what]/[bundle] to every currently-bound client, dropping any that throw
     * [RemoteException] (its process died mid-send) rather than letting one dead subscriber break
     * delivery to the rest. [force] bypasses the throttle -- used for start/finish and for
     * [relayLog], so none of those ever lose a race against [relayProgress]'s own throttled,
     * per-training-sample relay calls.
     */
    private fun relay(what: Int, bundle: Bundle, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRelayAt < Ipc.RELAY_THROTTLE_MS) return
        lastRelayAt = now
        val dead = mutableListOf<Messenger>()
        synchronized(clients) {
            for (client in clients) {
                try {
                    client.send(Message.obtain(null, what).apply { data = bundle })
                } catch (e: RemoteException) {
                    dead.add(client)
                }
            }
            clients.removeAll(dead)
        }
    }

    private fun progressBundle(p: AetherTrainer.Progress): Bundle = Bundle().apply {
        putInt(Ipc.KEY_P_EPOCH, p.epoch)
        putInt(Ipc.KEY_P_TOTAL_EPOCHS, p.totalEpochs)
        putInt(Ipc.KEY_P_SAMPLE, p.sample)
        putInt(Ipc.KEY_P_TOTAL_SAMPLES, p.totalSamples)
        putString(Ipc.KEY_P_CAPTION, p.caption)
        putFloat(Ipc.KEY_P_LOSS, p.loss)
        putString(Ipc.KEY_P_PHASE, p.phase)
    }

    private fun relayProgress(p: AetherTrainer.Progress) {
        relay(Ipc.MSG_TRAINING_PROGRESS, progressBundle(p))
    }

    // force=true: log lines are discrete, already-rate-limited events (one per "epoch sleep", one
    // per 5th training sample, etc. -- see the call sites below), not a high-frequency stream like
    // relayProgress. They must never share relayProgress's throttle gate, or a log line racing
    // against the training loop's constant per-sample progress relays would have a near-certain
    // chance of being silently dropped even while a client is actively bound and watching.
    private fun relayLog(line: String) =
        relay(Ipc.MSG_TRAINING_LOG, Bundle().apply { putString(Ipc.KEY_LOG_LINE, line) }, force = true)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopWork(); return START_NOT_STICKY }
            ACTION_CHAT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (isWorking) { respondBusy(); return START_NOT_STICKY }
                startChat(text)
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                val prompt = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (isWorking) return START_NOT_STICKY
                startTest(prompt)
                return START_NOT_STICKY
            }
            ACTION_APPLY_KNOWLEDGE -> {
                if (isWorking) { relayLog("Aether is busy -- couldn't apply the received knowledge yet. It stays staged; try again shortly."); return START_NOT_STICKY }
                applyPendingKnowledge()
                return START_NOT_STICKY
            }
            else -> {
                if (isWorking) return START_REDELIVER_INTENT
                val epochs = intent?.getIntExtra(EXTRA_EPOCHS, 10) ?: 10
                startTraining(epochs)
                return START_REDELIVER_INTENT
            }
        }
    }

    /**
     * Applies whatever `AetherKnowledgeSync`/`AetherMeshSync` has staged onto the resident
     * connectome -- run here, never from `AetherSettingsActivity` directly, because [AetherStudio.brain]
     * must only ever be called from this process. A main-process caller calling it directly would
     * build a SECOND, independent connectome there instead of reaching this one -- see
     * [AetherStudio.hasTrainedWeights]'s doc comment for the full reasoning. Not run on [scope]/
     * tracked as [job] (it's a quick in-memory merge, not a long job the UI needs to watch), but
     * still reports through the training log so a manual "tap to receive" gets visible feedback.
     */
    private fun applyPendingKnowledge() {
        Thread {
            try {
                val brain = AetherStudio.brain(applicationContext)
                val applied = AetherKnowledgeSync.applyPending(brain, AetherStudio.hasTrainedWeights())
                val line = if (applied) "Applied received knowledge to the connectome." else "Nothing staged to apply."
                AetherTrainingState.emitLog(line)
                relayLog(line)
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.SERVICE, "Applying received knowledge failed", e)
                relayLog("Applying received knowledge failed: ${e.message}")
            }
        }.start()
    }

    private fun startTraining(epochs: Int) {
        beginForeground("Starting…", indeterminate = true)
        AetherTrainingState.markStarted()
        relay(Ipc.MSG_TRAINING_STARTED, Bundle(), force = true)
        AetherTrainingState.emitLog("Training for $epochs epochs…")
        relayLog("Training for $epochs epochs…")

        job = scope.launch {
            val summary = try {
                AetherStudio.train(
                    applicationContext, epochs,
                    onProgress = { p ->
                        AetherTrainingState.publish(p)
                        relayProgress(p)
                        if (p.phase == "sleep") {
                            AetherTrainingState.emitLog("epoch ${p.epoch}: sleep -- pruning and growing")
                            relayLog("epoch ${p.epoch}: sleep -- pruning and growing")
                        } else if (p.phase == "validation" || p.phase == "test") {
                            // Held-out evaluation (dataset/validation|testing/) -- a distinct log
                            // line, not the per-sample training line below, so it doesn't read as
                            // just another training sample with a suspiciously round loss.
                            val label = if (p.phase == "validation") "Validation" else "Final Test"
                            val line = "[$label] epoch ${p.epoch}: ${p.caption}"
                            AetherTrainingState.emitLog(line)
                            relayLog(line)
                        } else if (p.sample % 5 == 0 || p.sample == p.totalSamples) {
                            val line = "e${p.epoch}/${p.totalEpochs} · ${p.sample}/${p.totalSamples} · loss %.4f · %s"
                                .format(p.loss, p.caption)
                            AetherTrainingState.emitLog(line)
                            relayLog(line)
                        }
                        updateTrainingNotification(p)
                    },
                    // ANN-baseline-conversion calibration (model conversion / attention
                    // distillation) runs before the epoch loop even starts -- forwarded through
                    // the same emitLog/relayLog pair so it shows up in the training Log tab too.
                    onCalibrationProgress = { line ->
                        AetherTrainingState.emitLog(line)
                        relayLog(line)
                    }
                )
            } catch (e: CancellationException) {
                AetherLog.info(AetherLog.Area.SERVICE, "Training cancelled")
                throw e
            } catch (e: OutOfMemoryError) {
                AetherLog.fatal(AetherLog.Area.SERVICE, "Training ran out of memory", e)
                "Training ran out of memory."
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.SERVICE, "Training failed", e)
                "Training failed: ${e.message}"
            }
            AetherTrainingState.emitLog(summary)
            relayLog(summary)
            AetherTrainingState.markFinished(summary)
            relay(Ipc.MSG_TRAINING_FINISHED, Bundle().apply { putString(Ipc.KEY_SUMMARY, summary) }, force = true)
            finishWork()
        }
    }

    private fun startChat(text: String) {
        beginForeground("Thinking…", indeterminate = true)
        AetherChatState.markBusy("Aether is thinking")
        relay(Ipc.MSG_CHAT_BUSY, Bundle().apply { putString(Ipc.KEY_STATUS, "Aether is thinking") }, force = true)

        job = scope.launch {
            val reply = try {
                AetherChat.respond(applicationContext, text) { status ->
                    AetherChatState.progress(status)
                    relay(Ipc.MSG_CHAT_PROGRESS, Bundle().apply { putString(Ipc.KEY_STATUS, status) })
                    notifyWork("Aether", status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                AetherLog.fatal(AetherLog.Area.SERVICE, "Out of memory on a chat turn", e)
                AetherChat.Reply("I ran out of memory.")
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.SERVICE, "Chat turn failed", e)
                AetherChat.Reply("Something went wrong: ${e.message}")
            }

            AetherChatStore.append(
                applicationContext,
                AetherChatStore.Entry(
                    reply.text, isSent = false,
                    attachmentUri = reply.attachmentUri?.toString(),
                    attachmentType = reply.attachmentType
                )
            )
            AetherChatState.markIdle()
            relay(Ipc.MSG_CHAT_IDLE, Bundle(), force = true)
            AetherChatState.bumpRevision()
            relay(Ipc.MSG_CHAT_REVISION_BUMP, Bundle(), force = true)
            finishWork()
        }
    }

    /** Mirrors `NoraService.startTestImage` -- a single plain-inference sample, logged to the training screen. */
    private fun startTest(prompt: String) {
        beginForeground("Generating…", indeterminate = true)
        AetherTrainingState.emitLog("Generating \"$prompt\"…")
        relayLog("Generating \"$prompt\"…")

        job = scope.launch {
            val result = try {
                AetherStudio.generateSimple(applicationContext, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AetherStudio.Generated(null, null, "Generation failed: ${e.message}")
            }
            val line = result.file?.let { "Saved ${it.name}\n${result.note}" } ?: result.note
            AetherTrainingState.emitLog(line)
            relayLog(line)
            finishWork()
        }
    }

    private fun respondBusy() {
        scope.launch {
            AetherChatStore.append(
                applicationContext,
                AetherChatStore.Entry("I'm mid-thought on something else -- one cortex, one job at a time. Try again shortly.", isSent = false)
            )
            AetherChatState.bumpRevision()
            relay(Ipc.MSG_CHAT_REVISION_BUMP, Bundle(), force = true)
        }
    }

    private fun beginForeground(text: String, indeterminate: Boolean) {
        startForeground(NOTIFICATION_ID, buildNotification("Aether", text, 0, indeterminate))
        acquireWakeLock()
    }

    private fun finishWork() { job = null; releaseWakeLock(); stopForegroundCompat(); stopSelf() }

    private fun stopWork() {
        job?.cancel(); job = null
        if (AetherTrainingState.running.value) {
            AetherTrainingState.emitLog("Cancelled.")
            relayLog("Cancelled.")
            AetherTrainingState.markFinished("Cancelled.")
            relay(Ipc.MSG_TRAINING_FINISHED, Bundle().apply { putString(Ipc.KEY_SUMMARY, "Cancelled.") }, force = true)
        }
        AetherChatState.markIdle()
        relay(Ipc.MSG_CHAT_IDLE, Bundle(), force = true)
        releaseWakeLock(); stopForegroundCompat(); stopSelf()
    }

    override fun onDestroy() {
        job?.cancel(); releaseWakeLock(); scope.cancel()
        if (AetherTrainingState.running.value) {
            AetherTrainingState.markFinished("Training stopped -- the service was shut down.")
            relay(Ipc.MSG_TRAINING_FINISHED, Bundle().apply { putString(Ipc.KEY_SUMMARY, "Training stopped -- the service was shut down.") }, force = true)
        }
        AetherChatState.markIdle()
        relay(Ipc.MSG_CHAT_IDLE, Bundle(), force = true)
        synchronized(clients) { clients.clear() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Prism:Aether").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            PrismLogger.logError("Aether", "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Aether", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while Aether is training or generating."
            setShowBadge(false); enableVibration(false); setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateTrainingNotification(p: AetherTrainer.Progress) {
        if (!throttle()) return
        val text = if (p.phase == "sleep") "Epoch ${p.epoch} of ${p.totalEpochs} · consolidating"
            else "Epoch ${p.epoch}/${p.totalEpochs} · ${p.sample}/${p.totalSamples} · loss %.4f".format(p.loss)
        notify(buildNotification("Aether is training", text, AetherTrainingState.percent, false))
    }

    private fun notifyWork(title: String, text: String) {
        if (!throttle()) return
        notify(buildNotification(title, text, 0, true))
    }

    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < 1000L) return false
        lastNotificationAt = now
        return true
    }

    private fun notify(notification: Notification) {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) } catch (e: Exception) {}
    }

    private fun buildNotification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AetherTrainingActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags
        )
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, AetherService::class.java).setAction(ACTION_STOP), pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_wand_24)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, percent, indeterminate)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stopIntent).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "aether_training"
        private const val NOTIFICATION_ID = 4712

        private const val ACTION_STOP = "com.prism.launcher.aether.STOP"
        private const val ACTION_CHAT = "com.prism.launcher.aether.CHAT"
        private const val ACTION_TEST = "com.prism.launcher.aether.TEST"
        private const val ACTION_APPLY_KNOWLEDGE = "com.prism.launcher.aether.APPLY_KNOWLEDGE"
        private const val EXTRA_EPOCHS = "epochs"
        private const val EXTRA_TEXT = "text"

        fun startTraining(ctx: Context, epochs: Int) = launch(ctx, Intent(ctx, AetherService::class.java).putExtra(EXTRA_EPOCHS, epochs))
        fun sendChat(ctx: Context, text: String) = launch(ctx, Intent(ctx, AetherService::class.java).setAction(ACTION_CHAT).putExtra(EXTRA_TEXT, text))
        fun testGenerate(ctx: Context, prompt: String) = launch(ctx, Intent(ctx, AetherService::class.java).setAction(ACTION_TEST).putExtra(EXTRA_TEXT, prompt))

        /**
         * Tells the service (`:aether` process) to apply whatever `AetherKnowledgeSync`/
         * `AetherMeshSync` already has staged, onto the ONE resident connectome it owns. The
         * fetch+stage itself (network I/O, writing to a temp file) can and does happen from
         * wherever `AetherKnowledgeSync.fetchAndStage`/`AetherMeshSync.fetchAndStage` was called
         * (typically the main-process Settings screen) -- only the final in-place mutation of
         * the live connectome is routed here.
         */
        fun applyKnowledge(ctx: Context) {
            // Plain startService, not launch()/startForegroundService: this triggers a quick
            // in-memory merge with no foreground notification of its own, so it must not be
            // required to call startForeground() within Android's few-second window the way an
            // actual startForegroundService() launch would demand.
            try { ctx.startService(Intent(ctx, AetherService::class.java).setAction(ACTION_APPLY_KNOWLEDGE)) } catch (e: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, AetherService::class.java).setAction(ACTION_STOP)) } catch (e: Exception) {}
        }

        private fun launch(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
        }
    }
}
