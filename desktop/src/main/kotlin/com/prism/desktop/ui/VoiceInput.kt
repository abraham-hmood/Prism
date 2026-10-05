package com.prism.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.DesktopMicrophone
import com.prism.launcher.messaging.Dictation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dictation, wherever Prism takes text. PHASE 90.
 *
 * ## What this phase actually needed
 *
 * Not transcription -- that already worked. `Dictation` is in `:core`, `WhisperCppEngine` runs on the
 * desktop through the native-loading path, and `prism dictate` proved it. What was missing was dictation
 * IN THE PLACES ANDROID HAS IT, which is what the phase is measured by: a microphone button next to a text
 * field, in the conversation, in the compose box, in the search bar.
 *
 * So this is one composable, dropped beside any field, and it is the whole desktop half of the phase.
 *
 * ## No OS speech API, and the reason
 *
 * The plan sketched Windows' own recogniser as an option. It is not taken: Windows exposes it through WinRT
 * or SAPI, Linux has nothing comparable, and dictation that produced text on one desktop and silence on the
 * other would be worse than one local model that behaves identically. Whisper is already here, already
 * loaded, already used by the console command -- so there is one implementation and one set of results.
 *
 * ## Recording stops when the button is pressed, not on a timer
 *
 * Android's SpeechRecognizer decides when you have stopped talking. Whisper does not: it transcribes a
 * finished buffer. So the button toggles, the level meter shows that something is arriving, and a cap exists
 * only so a forgotten recording cannot grow without bound.
 *
 * ## The failure that is not a failure
 *
 * A peak level of essentially zero means a muted or unselected input, not a transcription problem, and the
 * two look identical if all you report is "no text". The button says which -- the same distinction the
 * console command makes, for the same reason.
 */
@Composable
fun MicButton(
    enabled: Boolean = true,
    onText: (String) -> Unit,
    onStatus: (String) -> Unit = {},
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    val microphone = remember { DesktopMicrophone() }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var peak by remember { mutableStateOf(0f) }

    // A recording left running when the page closes would hold the input line open for the rest of the
    // process, and nothing would ever read the buffer.
    DisposableEffect(Unit) {
        onDispose { if (recording) runCatching { microphone.stop() } }
    }

    val available = remember { microphone.isAvailable() }
    val busy = recording || transcribing

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = when {
                !available -> Color(0xFF26262E)
                recording -> Color(0xFF7A1F2A)
                transcribing -> Color(0xFF2A2A33)
                else -> Color(0xFF26262E)
            },
            shape = CircleShape,
            modifier = Modifier.size(34.dp),
        ) {
            Box(
                Modifier
                    .clickableRow {
                        if (!enabled || !available) {
                            onStatus(microphone.unavailableReason())
                            return@clickableRow
                        }
                        if (transcribing) return@clickableRow

                        if (recording) {
                            // Stop only; the coroutine started below is what finishes and transcribes.
                            runCatching { microphone.stop() }
                            return@clickableRow
                        }

                        recording = true
                        peak = 0f
                        onStatus("Listening…")
                        scope.launch {
                            val pcm = withContext(Dispatchers.IO) {
                                runCatching {
                                    microphone.record(
                                        onLevel = { value ->
                                            level = value
                                            if (value > peak) peak = value
                                        },
                                        // A cap, not a duration: the button is what ends a recording. This
                                        // only stops a forgotten one growing without bound.
                                        maxSeconds = 120,
                                    )
                                }.getOrNull()
                            }
                            recording = false
                            level = 0f

                            if (pcm == null || pcm.isEmpty()) {
                                onStatus("Nothing was recorded.")
                                return@launch
                            }

                            val seconds = Dictation.durationSeconds(pcm)
                            if (peak < 0.01f) {
                                // A muted input and a transcription failure look identical if all that is
                                // reported is "no text". They have completely different fixes.
                                onStatus(
                                    "Nothing reached the microphone -- the peak level was essentially " +
                                        "zero. That is a muted or unselected input rather than a " +
                                        "transcription problem."
                                )
                                return@launch
                            }
                            if (seconds < 0.4) {
                                onStatus("Too short to transcribe.")
                                return@launch
                            }

                            transcribing = true
                            onStatus("Transcribing " + String.format("%.1f", seconds) + " s…")
                            val result = withContext(Dispatchers.IO) {
                                runCatching { Dictation.transcribe(pcm) }.getOrNull()
                            }
                            transcribing = false

                            val text = result?.text
                            when {
                                text != null && text.isNotBlank() -> {
                                    onText(text.trim())
                                    onStatus("")
                                }
                                result != null -> onStatus(
                                    result.error ?: Dictation.unavailableReason()
                                )
                                else -> onStatus(Dictation.unavailableReason())
                            }
                        }
                    }
                    .size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recording) "Stop recording" else "Dictate",
                    tint = when {
                        !available -> Color(0xFF55555F)
                        recording -> Color.White
                        else -> colors.muted
                    },
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        // A level meter while recording, because the commonest failure is an input that is not receiving
        // anything and the only way to know before transcribing is to watch it move.
        if (recording) {
            Spacer(Modifier.width(8.dp))
            Surface(color = Color(0xFF1E1E26), shape = CircleShape, modifier = Modifier.size(width = 54.dp, height = 5.dp)) {
                Surface(
                    color = colors.accent,
                    shape = CircleShape,
                    modifier = Modifier.size(
                        width = (54f * level.coerceIn(0f, 1f)).dp.coerceAtLeast(2.dp),
                        height = 5.dp,
                    ),
                ) {}
            }
        }

        if (transcribing) {
            Spacer(Modifier.width(8.dp))
            Text("…", fontSize = 13.sp, color = colors.faint)
        }
    }
}

/** Whether dictation can work at all here, for a page that wants to say so before offering it. */
fun dictationAvailable(): Boolean = runCatching {
    DesktopMicrophone().isAvailable() && Dictation.preferred() != null
}.getOrDefault(false)

/** Why it cannot, in one sentence. */
fun dictationUnavailableReason(): String = runCatching {
    val microphone = DesktopMicrophone()
    when {
        !microphone.isAvailable() -> microphone.unavailableReason()
        Dictation.preferred() == null -> Dictation.unavailableReason()
        else -> ""
    }
}.getOrElse { it.message.orEmpty() }
