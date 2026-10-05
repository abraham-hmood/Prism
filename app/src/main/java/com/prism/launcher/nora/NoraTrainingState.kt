package com.prism.launcher.nora

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide training state, owned by [NoraTrainingService] and observed by the UI.
 *
 * The activity cannot own any of this. An overnight run has to survive the screen turning off,
 * the activity being destroyed to reclaim memory, and the user coming back hours later expecting
 * to see where things got to. So the log, the progress, and the run flag all live here, outside
 * any Activity lifecycle, and the UI is a pure observer that can be torn down and rebuilt at any
 * point without interrupting anything.
 *
 * The log replays, so a recreated activity immediately repaints the history rather than showing
 * an empty pane next to a service that is clearly still working.
 */
object NoraTrainingState {

    private const val LOG_REPLAY = 300

    private val _log = MutableSharedFlow<String>(
        replay = LOG_REPLAY,
        extraBufferCapacity = 128
    )
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val _progress = MutableStateFlow<NoraTrainer.Progress?>(null)
    val progress: StateFlow<NoraTrainer.Progress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Final summary of the last completed run, or null while running / before the first run. */
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    /** Overall completion 0..100, derived from the last progress event. */
    val percent: Int
        get() {
            val p = _progress.value ?: return 0
            if (p.totalEpochs <= 0) return 0
            val epochFraction = (p.epoch - 1).toFloat() / p.totalEpochs
            val within = (p.sample.toFloat() / p.totalSamples.coerceAtLeast(1)) / p.totalEpochs
            return ((epochFraction + within) * 100).toInt().coerceIn(0, 100)
        }

    fun emitLog(line: String) {
        _log.tryEmit(line)
    }

    fun publish(p: NoraTrainer.Progress) {
        _progress.value = p
    }

    fun markStarted() {
        _summary.value = null
        _running.value = true
    }

    fun markFinished(summary: String) {
        _summary.value = summary
        _running.value = false
        _progress.value = null
    }

    /** Clears the replayed log. Only the UI calls this, on an explicit user action. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearLog() {
        _log.resetReplayCache()
    }
}
