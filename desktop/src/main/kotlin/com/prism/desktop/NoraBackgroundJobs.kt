package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.nora.NoraBrain
import com.prism.launcher.nora.NoraConfig
import com.prism.launcher.nora.NoraPersistence
import com.prism.launcher.nora.NoraTrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nora's training, surviving the window being closed. PHASE 44.
 *
 * ## What "foreground service semantics" becomes on a desktop
 *
 * On Android, `NoraService` is a foreground service with a notification, a wake lock and a process the OS
 * is told not to kill. None of those concepts exist here and none of them need to: a desktop JVM is not
 * reclaimed under memory pressure and its CPU does not sleep because the screen did. What the Android
 * service actually provides — work that outlives the screen, and progress the user can see without the
 * screen — becomes a non-daemon thread plus a tray balloon.
 *
 * ## Why the thread is NOT a daemon, and why that is the whole design
 *
 * A daemon thread dies when the last non-daemon thread exits, which on a Compose Desktop app is the moment
 * the window closes. Training on a daemon thread would therefore be cancelled by closing the window —
 * exactly the behaviour this phase exists to prevent. A non-daemon thread keeps the JVM alive until it
 * finishes, so a run started before closing the window completes and saves.
 *
 * THAT ALSO MEANS THE PROCESS OUTLIVES THE WINDOW, and a user who closes Prism will see it still running.
 * [describe] is what the tray shows so that is explained rather than mysterious, and [stop] is how they
 * end it deliberately. An app that silently refused to quit would be worse than one that cancelled
 * training.
 *
 * ## Why checkpoints are per epoch and not only at the end
 *
 * A run is minutes to hours. The process can still be killed — a reboot, a power cut, Task Manager — and
 * an epoch boundary is the natural point where the connectome is consistent. Saving there means a killed
 * run loses at most one epoch instead of everything.
 */
object NoraBackgroundJobs {

    private const val TAG = "Nora/background"

    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var worker: Thread? = null

    @Volatile private var progressLine: String = "Not training."
    @Volatile private var lastEpoch = 0
    @Volatile private var totalEpochs = 0
    @Volatile private var cancelRequested = false

    val isTraining: Boolean get() = running.get()

    /** One line for the tray tooltip and the settings page. */
    fun describe(): String = progressLine

    /**
     * Starts a training run on a non-daemon thread.
     *
     * Returns false when one is already going: two trainers on one connectome interleave their writes and
     * produce weights that are neither run's result. The Android service refuses for the same reason.
     */
    fun start(epochs: Int, focus: String? = null, onUpdate: (String) -> Unit = {}): Boolean {
        if (!running.compareAndSet(false, true)) return false

        cancelRequested = false
        totalEpochs = epochs
        lastEpoch = 0
        progressLine = "Starting…"

        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = jobScope

        val thread = Thread({
            runCatching {
                val brain = NoraBrain()
                val loaded = NoraPersistence.load(brain)
                note(
                    if (loaded) "Connectome loaded; training $epochs epoch(s)."
                    else "No connectome yet; training from scratch for $epochs epoch(s).",
                    onUpdate,
                )

                val dataset = NoraTrainer.loadDataset()
                if (dataset.isEmpty()) {
                    note(
                        "No dataset. Drop captioned images into ${NoraConfig.datasetDir().absolutePath} " +
                            "— the filename is the caption.",
                        onUpdate,
                    )
                    return@runCatching
                }
                note("${dataset.size} example(s) found.", onUpdate)

                // runBlocking on THIS thread, not the UI one. NoraTrainer.train is suspending, and this
                // thread exists precisely to be blocked by it.
                runBlocking {
                    NoraTrainer(brain).train(epochs = epochs, focus = focus) { progress ->
                        if (cancelRequested) return@train
                        if (progress.epoch != lastEpoch) {
                            lastEpoch = progress.epoch
                            // Checkpoint at the epoch boundary: the one point where the connectome is
                            // consistent and a killed process loses only this epoch.
                            runCatching { NoraPersistence.save(brain) }
                            PrismPlatform.notifier.notify(
                                CHANNEL, NOTIFICATION_ID,
                                "Nora training",
                                "Epoch ${progress.epoch} of ${progress.totalEpochs} complete.",
                            )
                        }
                        note(
                            "Epoch ${progress.epoch}/${progress.totalEpochs}, " +
                                "example ${progress.sample}/${progress.totalSamples}" +
                                progress.caption.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                            onUpdate,
                        )
                    }
                }

                runCatching { NoraPersistence.save(brain) }
                note(
                    if (cancelRequested) "Stopped after epoch $lastEpoch; the connectome was saved."
                    else "Finished $epochs epoch(s); the connectome was saved.",
                    onUpdate,
                )
                PrismPlatform.notifier.notify(
                    CHANNEL, NOTIFICATION_ID,
                    "Nora training",
                    if (cancelRequested) "Stopped and saved." else "Finished and saved.",
                )
            }.onFailure {
                PrismPlatform.log.error(TAG, "Training failed", it)
                note("Failed: ${it.message ?: it::class.simpleName}", onUpdate)
            }

            running.set(false)
            jobScope.cancel()
            scope = null
            worker = null
        }, "nora-training")

        // NOT a daemon. See the class comment: a daemon dies with the window, which is the exact thing
        // this phase exists to stop.
        thread.isDaemon = false
        // Below normal, so a long training run does not make the rest of the machine sluggish. The
        // Android service has no equivalent knob and does not need one -- there the OS throttles it.
        thread.priority = Thread.NORM_PRIORITY - 2
        worker = thread
        thread.start()
        return true
    }

    /**
     * Asks the run to stop at the next example.
     *
     * Cooperative rather than interrupting the thread. A trainer killed mid-example leaves the connectome
     * half-updated, and there is no way to tell that from a trained one afterwards — so it finishes what
     * it is doing, saves, and exits.
     */
    fun stop() {
        if (!running.get()) return
        cancelRequested = true
        progressLine = "Stopping after this example…"
    }

    /** Blocks until a run finishes. For a caller that is shutting the process down deliberately. */
    fun awaitIdle(timeoutMs: Long = 60_000) {
        val thread = worker ?: return
        runCatching { thread.join(timeoutMs) }
    }

    private fun note(line: String, onUpdate: (String) -> Unit) {
        progressLine = line
        PrismPlatform.log.info(TAG, line)
        runCatching { onUpdate(line) }
    }

    /**
     * Re-posting the same id replaces the notification on Android and stacks on desktop.
     *
     * DesktopNotifier says so in its own docs: AWT's tray balloon has no handle to revoke and no identity
     * to replace. So progress is reported per EPOCH rather than per example — per-example balloons would
     * be hundreds of popups, which is why the frequent updates go to [describe] and the log instead.
     */
    private const val NOTIFICATION_ID = 4401

    private const val CHANNEL = "nora-training"
}
