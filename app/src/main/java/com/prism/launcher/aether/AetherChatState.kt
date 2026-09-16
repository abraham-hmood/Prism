package com.prism.launcher.aether

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state for an in-flight Aether generation, owned by [AetherService]. Mirrors `NoraChatState`. */
object AetherChatState {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun markBusy(initialStatus: String) { _status.value = initialStatus; _busy.value = true }
    fun progress(line: String) { _status.value = line }
    fun markIdle() { _busy.value = false; _status.value = "" }
    fun bumpRevision() { _revision.value = _revision.value + 1 }
}
