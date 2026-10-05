package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismCpu
import com.prism.launcher.PrismSettings
import com.prism.launcher.quant.PrismQuantizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Making a model smaller. PHASE 107.
 *
 * ## Why this belongs on the desktop more than on the phone
 *
 * The plan says it outright and the numbers agree: quantising is hours on a phone and minutes on a PC, and
 * the result is an ordinary GGUF that loads on either. So the intended workflow is to do it here and carry
 * the file over -- which is what makes the mesh model transfer and the profile archive useful rather than
 * incidental.
 *
 * ## Why the progress bar reads the output file
 *
 * `llama_model_quantize` is one blocking native call with no progress callback. The only observable
 * evidence of progress is the destination file growing, so that is what is watched, against
 * [PrismQuantizer.estimateOutputBytes]. The estimate is derived from the SOURCE's bits per weight rather
 * than from its size -- requantising a Q4_K_M to Q2_K roughly halves it while the same target from an F16
 * cuts it by six, so a size-only guess produces a bar that finishes at 40% or runs past 100%.
 *
 * ## The low-bit levels carry their own warnings
 *
 * Binary, ternary and quaternary sit in the same list as Q8_0 and are not the same kind of thing. Each
 * says so in its own row, from [PrismQuantizer.Level.note], because a list that presented 1.13 bits per
 * weight next to 8.5 with no comment would be actively misleading.
 */
@Composable
fun QuantizePage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(PrismSettings.getLocalAiModelPath()) }
    var level by remember { mutableStateOf(PrismQuantizer.Level.Q4_K_M) }
    var running by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(0L) }
    var estimate by remember { mutableStateOf(0L) }
    var outcome by remember { mutableStateOf("") }
    var outcomeOk by remember { mutableStateOf(false) }

    val sourceFile = remember(source) { File(source).takeIf { it.isFile } }
    val available = remember { PrismQuantizer.isAvailable() }

    fun destinationFor(file: File, target: PrismQuantizer.Level) = File(
        file.parentFile,
        file.nameWithoutExtension
            .replace(Regex("""(?i)[.-](Q[0-9][_A-Z0-9]*|F16|F32|BF16)"""), "") +
            "." + target.name + ".gguf",
    )

    PageScaffold("Quantize", "Make a model smaller, here, and carry it to the phone") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("the quantiser")
            Card {
                InfoRow("Native quantiser", if (available) "available" else "not available")
                Hairline()
                InfoRow("Threads", PrismCpu.inferenceThreads().toString())
            }
            if (!available) {
                SectionFooter(
                    "gguf_bridge did not load, so there is nothing to quantise with. The Diagnostics " +
                        "page says which libraries were found and where it looked."
                )
            }

            SectionHeader("model")
            Card {
                Column(Modifier.padding(16.dp)) {
                    CommittingField(value = source, modifier = Modifier.fillMaxWidth()) { source = it }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val dialog = java.awt.FileDialog(
                                null as java.awt.Frame?, "Choose a GGUF model", java.awt.FileDialog.LOAD,
                            )
                            dialog.isVisible = true
                            val name = dialog.file
                            if (name != null) source = File(dialog.directory, name).absolutePath
                        }) { Text("Choose a file", fontSize = 13.sp) }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            sourceFile?.let { (it.length() / (1024 * 1024)).toString() + " MB" }
                                ?: "not a file",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    }
                }
            }

            SectionHeader("target")
            Card {
                Column(Modifier.padding(16.dp)) {
                    PrismQuantizer.Level.entries.forEach { candidate ->
                        val active = candidate == level
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow { level = candidate }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = if (active) colors.accent else Color(0xFF2A2A33),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.size(9.dp),
                            ) {}
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(candidate.label, fontSize = 13.sp)
                                if (candidate.note.isNotBlank()) {
                                    Text(
                                        candidate.note,
                                        fontSize = 11.sp,
                                        color = Color(0xFFFFC46B),
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                            Text(
                                String.format("%.2f", candidate.bitsPerWeight) + " bpw",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.faint,
                            )
                        }
                    }
                }
            }

            sourceFile?.let { file ->
                Spacer(Modifier.height(10.dp))
                Card {
                    InfoRow("Result", destinationFor(file, level).name, mono = true)
                    Hairline()
                    InfoRow(
                        "Estimated size",
                        (PrismQuantizer.estimateOutputBytes(file, level) / (1024 * 1024)).toString() + " MB",
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = available && sourceFile != null && !running,
                    onClick = {
                        val file = sourceFile ?: return@Button
                        val destination = destinationFor(file, level)
                        running = true
                        outcome = ""
                        written = 0
                        estimate = PrismQuantizer.estimateOutputBytes(file, level)

                        scope.launch {
                            // The only observable progress is the file growing; see the class comment.
                            val watcher = launch(Dispatchers.IO) {
                                while (running) {
                                    written = runCatching { destination.length() }.getOrDefault(0L)
                                    delay(400)
                                }
                            }
                            val problem = withContext(Dispatchers.IO) {
                                PrismQuantizer.quantize(
                                    source = file,
                                    destination = destination,
                                    level = level,
                                    threads = PrismCpu.inferenceThreads(),
                                )
                            }
                            running = false
                            watcher.cancel()
                            outcomeOk = problem == null
                            outcome = if (problem == null) {
                                destination.name + " written, " +
                                    (destination.length() / (1024 * 1024)) + " MB. It is an ordinary " +
                                    "GGUF and loads on the phone too."
                            } else {
                                problem
                            }
                        }
                    },
                ) { Text(if (running) "Working…" else "Quantize") }

                if (running) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    val percent = if (estimate > 0) ((written * 100) / estimate).coerceIn(0, 99) else 0
                    Text(
                        (written / (1024 * 1024)).toString() + " MB written · about " + percent + "%",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                }
            }

            if (outcome.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = if (outcomeOk) Color(0xFF14301F) else Color(0xFF331A1A),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        outcome,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (outcomeOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionFooter(
                "Requantising an already-quantised model works and used to abort the whole process: " +
                    "ggml asserts rather than failing when `pure` mode meets a block it cannot convert, " +
                    "and an assert is not something Kotlin can catch. The bridge now keeps llama.cpp's " +
                    "own per-tensor mixing for a quantised source and forces `pure` only from full " +
                    "precision, which is where it is safe."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
