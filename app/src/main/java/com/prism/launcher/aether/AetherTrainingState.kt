package com.prism.launcher.aether

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide Aether training state, owned by [AetherService]. Mirrors `NoraTrainingState`. */
object AetherTrainingState {

    private const val LOG_REPLAY = 300

    private val _log = MutableSharedFlow<String>(replay = LOG_REPLAY, extraBufferCapacity = 128)
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val _progress = MutableStateFlow<AetherTrainer.Progress?>(null)
    val progress: StateFlow<AetherTrainer.Progress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    val percent: Int
        get() {
            val p = _progress.value ?: return 0
            if (p.totalEpochs <= 0) return 0
            val epochFraction = (p.epoch - 1).toFloat() / p.totalEpochs
            val within = (p.sample.toFloat() / p.totalSamples.coerceAtLeast(1)) / p.totalEpochs
            return ((epochFraction + within) * 100).toInt().coerceIn(0, 100)
        }

    fun emitLog(line: String) { _log.tryEmit(line) }
    fun publish(p: AetherTrainer.Progress) { _progress.value = p }
    fun markStarted() { _summary.value = null; _running.value = true }
    fun markFinished(summary: String) { _summary.value = summary; _running.value = false; _progress.value = null }
    fun clearLog() { _log.resetReplayCache() }
}
