package com.prism.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.NoraBackgroundJobs
import com.prism.launcher.nora.NoraArchiveCore
import com.prism.launcher.nora.NoraChatTranscript
import com.prism.launcher.nora.NoraCommands
import com.prism.launcher.nora.NoraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Nora's slash commands, her backup, her visualizer and her background training, on one page.
 *
 * PHASES 40, 41, 43 and 44. Together rather than four pages because they are the same kind of thing — the
 * parts of Nora that are not the chat itself — and a rail with four more entries for one screen's worth of
 * content would be worse than one page with four sections.
 *
 * The visualizer is here as well as usable from the chat page: it is the one thing on this page that is
 * live, and watching it while a background run trains is exactly when it is interesting.
 */
@Composable
fun NoraExtrasPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var archiveStatus by remember { mutableStateOf<String?>(null) }
    // Inside the composable, not at file scope. A top-level var would survive navigating away and
    // back -- so an archive inspected, abandoned, and then returned to would still be armed for a
    // restore the user had forgotten about, and a restore replaces the connectome.
    var pendingRestore by remember { mutableStateOf<File?>(null) }
    var trainingLine by remember { mutableStateOf(NoraBackgroundJobs.describe()) }
    var epochs by remember { mutableStateOf("10") }
    var commandFilter by remember { mutableStateOf("") }

    // Polled rather than pushed: the job runs on its own thread and reports through a volatile field, and
    // a callback into composition from that thread would be a recomposition off the main thread.
    LaunchedEffect(Unit) {
        while (true) {
            trainingLine = NoraBackgroundJobs.describe()
            delay(500)
        }
    }

    PageScaffold("Nora — tools", "Commands, backup, anatomy and background training") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            // ── PHASE 43 ─────────────────────────────────────────────────────
            SectionHeader("ANATOMY, LIVE")
            Card {
                NoraBrainVisualizer()
            }
            SectionFooter(
                "Two lines per pathway, because prediction and error travel in opposite directions — that " +
                    "is what a predictive-coding hierarchy is. The accent colour is ascending error, the " +
                    "cool line is the descending prediction. During perception the error dominates; during " +
                    "generation the prediction does. Activity is scaled across regions per frame, because " +
                    "the raw units differ by orders of magnitude between the retina and IT."
            )

            // ── PHASE 44 ─────────────────────────────────────────────────────
            SectionHeader("BACKGROUND TRAINING")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(trainingLine, fontSize = 12.sp, color = colors.faint)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommittingField(
                            epochs,
                            enabled = !NoraBackgroundJobs.isTraining,
                            modifier = Modifier.width(110.dp),
                        ) { epochs = it }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            enabled = !NoraBackgroundJobs.isTraining,
                            onClick = {
                                val count = epochs.toIntOrNull()?.coerceIn(1, 1000) ?: 10
                                val started = NoraBackgroundJobs.start(count) { line ->
                                    trainingLine = line
                                }
                                if (!started) trainingLine = "A training run is already going."
                            },
                        ) { Text("Train in the background") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(
                            enabled = NoraBackgroundJobs.isTraining,
                            onClick = { NoraBackgroundJobs.stop() },
                        ) { Text("Stop") }
                    }
                }
            }
            SectionFooter(
                "The run is on a non-daemon thread, which is the whole point: closing this window does not " +
                    "cancel it, and the process stays alive until it finishes and saves. That also means " +
                    "Prism will still be running after you close it — use Stop to end a run deliberately. " +
                    "The connectome is checkpointed at every epoch boundary, so a machine that is shut " +
                    "down loses at most one epoch. Progress goes to the system tray."
            )

            // ── PHASE 41 ─────────────────────────────────────────────────────
            SectionHeader("BACKUP AND RESTORE")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            val target = chooseFile(save = true, suggested = NoraArchiveCore.suggestedFileName())
                            if (target == null) {
                                archiveStatus = "No destination chosen."
                                return@Button
                            }
                            archiveStatus = "Writing ${target.name}…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        target.outputStream().use { NoraArchiveCore.backup(it) }
                                    }.getOrElse {
                                        NoraArchiveCore.Result(false, "Could not write there: ${it.message}")
                                    }
                                }
                                archiveStatus = result.message
                            }
                        }) { Text("Back up") }

                        Spacer(Modifier.width(10.dp))

                        OutlinedButton(onClick = {
                            val source = chooseFile(save = false)
                            if (source == null) {
                                archiveStatus = "No archive chosen."
                                return@OutlinedButton
                            }
                            // INSPECTED BEFORE RESTORING, because a restore replaces the connectome and
                            // whatever the brain has learned since is gone. The geometry check in the
                            // manifest is what catches the case that would otherwise load weights as
                            // noise, and showing it first means the user decides.
                            scope.launch {
                                val summary = withContext(Dispatchers.IO) {
                                    runCatching { source.inputStream().use { NoraArchiveCore.inspect(it) } }
                                        .getOrDefault("That file could not be read.")
                                }
                                archiveStatus = "${source.name}\n$summary\n\nPress Restore again to apply it."
                                pendingRestore = source
                            }
                        }) { Text("Inspect an archive") }

                        Spacer(Modifier.width(10.dp))

                        OutlinedButton(
                            enabled = pendingRestore != null,
                            onClick = {
                                val source = pendingRestore ?: return@OutlinedButton
                                archiveStatus = "Restoring ${source.name}…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            source.inputStream().use { NoraArchiveCore.restore(it) }
                                        }.getOrElse {
                                            NoraArchiveCore.Result(false, "Could not read it: ${it.message}")
                                        }
                                    }
                                    archiveStatus = result.message
                                    pendingRestore = null
                                }
                            },
                        ) { Text("Restore") }
                    }

                    archiveStatus?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 12.sp, color = colors.faint)
                    }
                }
            }
            SectionFooter(
                "The archive holds the connectome, the dataset, the feedback and the conversation — not " +
                    "generated output, which is regenerable and would dominate the file. A restore refuses " +
                    "outright if the archive was made at a different brain geometry: those weights would " +
                    "load without error and produce a brain full of noise that looks trained. An archive " +
                    "made on Android restores here and the reverse, because it is the same format."
            )

            // ── PHASE 40 ─────────────────────────────────────────────────────
            SectionHeader("SLASH COMMANDS")
            Card {
                Column(Modifier.padding(14.dp)) {
                    CommittingField(commandFilter, modifier = Modifier.fillMaxWidth()) {
                        commandFilter = it
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Type a slash to filter. This is the same list the phone shows — one definition in " +
                            ":core, so a command cannot exist in one build and not the other.",
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
                Hairline()

                val shown = if (commandFilter.startsWith("/")) {
                    NoraCommands.matching(commandFilter).ifEmpty { NoraCommands.ALL }
                } else {
                    NoraCommands.ALL
                }
                shown.forEachIndexed { index, command ->
                    if (index > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            command.label(),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.accent,
                            modifier = Modifier.width(190.dp),
                        )
                        Text(command.summary, fontSize = 12.sp, color = colors.faint)
                    }
                }
            }

            SectionHeader("TRANSCRIPT")
            Card {
                Column(Modifier.padding(14.dp)) {
                    val entries = remember { NoraChatTranscript.load() }
                    Text(
                        if (entries.isEmpty()) "Nothing yet."
                        else "${entries.size} entries · ${NoraConfig.chatFile().absolutePath}",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        NoraChatTranscript.clear()
                        archiveStatus = "Transcript cleared. The connectome is untouched — /forget is what " +
                            "erases what she has learned."
                    }) { Text("Clear the transcript") }
                }
            }
            SectionFooter(
                "Her transcript is a flat JSON file rather than a database row, which is why she is a " +
                    "separate thread from Sam: an entry carries a feedback token and a rating that the AI " +
                    "message table has no columns for, and a file can be read with a text editor."
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The platform's own file dialog.
 *
 * AWT rather than a Compose one, because Compose Desktop has no file picker and AWT's is the real system
 * dialog — it looks and behaves like every other open or save box on the machine, including remembering
 * the last directory, which a hand-drawn one would not.
 */
private fun chooseFile(save: Boolean, suggested: String = ""): File? {
    val dialog = FileDialog(
        null as Frame?,
        if (save) "Save Nora's backup" else "Open a Nora backup",
        if (save) FileDialog.SAVE else FileDialog.LOAD,
    ).apply {
        if (suggested.isNotEmpty()) file = suggested
        isMultipleMode = false
        isVisible = true
    }
    val chosen = dialog.files?.firstOrNull() ?: return null
    // The SAVE dialog returns a name the user may have typed without an extension; a zip without one is
    // harder to find later and some tools refuse it.
    return if (save && !chosen.name.endsWith(".zip", ignoreCase = true)) {
        File(chosen.parentFile, chosen.name + ".zip")
    } else {
        chosen
    }
}
