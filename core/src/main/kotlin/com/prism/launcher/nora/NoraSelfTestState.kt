package com.prism.launcher.nora

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state for a model test, owned by [NoraService].
 *
 * The test used to live in the activity's lifecycleScope, which meant backing out of the screen
 * cancelled it — and a test whose whole job is measuring how long a size takes to train is
 * exactly the thing a user will navigate away from while it runs. State lives here so the run
 * survives the screen, and so returning to it shows the run in progress rather than an empty
 * form.
 *
 * The log is a StateFlow<List<String>> rather than a SharedFlow of lines, deliberately: a
 * replaying SharedFlow gives a *recent* window, and re-entering the screen halfway through a run
 * should show the whole run so far, not the tail of it.
 */
object NoraSelfTestState {

    /** Log lines retained. A 4-epoch run over 9 images produces well under this. */
    private const val MAX_LOG = 600

    data class Progress(
        val epoch: Int,
        val totalEpochs: Int,
        val image: Int,
        val totalImages: Int,
        val errorRms: Float,
        val caption: String,
        val phase: String
    ) {
        val percent: Int
            get() {
                val total = (totalEpochs * totalImages).coerceAtLeast(1)
                val done = (epoch - 1).coerceAtLeast(0) * totalImages + image
                return (done * 100 / total).coerceIn(0, 100)
            }

        /** One line, for the notification. */
        fun summary(): String = when (phase) {
            "sleep" -> "Epoch $epoch of $totalEpochs · consolidating"
            else -> "Epoch $epoch/$totalEpochs · image $image/$totalImages · $caption"
        }
    }

    /**
     * @param samplePaths generated images written to cache rather than held as Bitmaps, so the
     *        result survives the activity being destroyed and rebuilt without pinning bitmap
     *        memory in a process-lifetime singleton.
     */
    data class Outcome(
        val headline: String,
        val report: String,
        val samplePaths: List<Pair<String, String>>
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** The geometry the current or last run was measuring. */
    @Volatile
    var testedGeometry: String = ""
        private set

    // ── The trained brain, kept alive between runs ──────────────────────────

    /**
     * The sandbox brain from the last completed test.
     *
     * RETAINED ON PURPOSE, and it is the expensive kind of on purpose: this pins an entire
     * connectome in the process for as long as a result is on screen. The alternative is worse.
     * Comparing four generation routes at one size used to mean four full training runs whose
     * only difference was the last few seconds, and at a large geometry that is most of an hour
     * spent re-deriving identical weights. Training is the slow part and it is route-independent,
     * so doing it once and generating four times is the only sane shape for the experiment.
     *
     * Released whenever a new run starts, so at most one is ever held, and releasable by hand
     * from the screen for anyone who would rather have the memory back.
     */
    @Volatile
    private var retained: NoraBrain? = null

    private val _regenerable = MutableStateFlow(false)

    /** Whether a trained brain is standing by, so the screen can show or hide its button. */
    val regenerable: StateFlow<Boolean> = _regenerable.asStateFlow()

    fun retain(brain: NoraBrain) {
        retained = brain
        _regenerable.value = true
    }

    fun retainedBrain(): NoraBrain? = retained

    /**
     * Drops the retained brain and everything it holds off-heap.
     *
     * The hippocampal spill file goes with it: its slot indices live only in this brain's episode
     * list, so keeping the file would be keeping bytes nothing can interpret.
     */
    fun releaseRetained() {
        retained?.hippocampus?.closeSpill()
        retained = null
        _regenerable.value = false
    }

    fun markStarted(geometrySignature: String) {
        // Before a new brain is built, not after. Two connectomes resident at once is the
        // difference between fitting and an OutOfMemoryError at any interesting size, and the
        // old one is worthless the moment a new run begins.
        releaseRetained()
        testedGeometry = geometrySignature
        _log.value = emptyList()
        _outcome.value = null
        _progress.value = null
        _running.value = true
    }

    /** A regeneration reuses the retained brain, so the log and result are kept, not cleared. */
    fun markRegenerating() {
        _outcome.value = null
        _running.value = true
    }

    fun publish(p: Progress) {
        _progress.value = p
    }

    /** Appends to the on-screen log and to system diagnostics in one call. */
    fun emit(line: String, alsoDiagnostics: Boolean = true) {
        _log.value = (_log.value + line).takeLast(MAX_LOG)
        if (alsoDiagnostics && line.isNotBlank()) {
            NoraLog.info(NoraLog.Area.SELFTEST, line)
        }
    }

    fun markFinished(outcome: Outcome) {
        _outcome.value = outcome
        _running.value = false
    }

    fun markCancelled() {
        _running.value = false
        emit("Cancelled.")
    }
}
