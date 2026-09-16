package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.cakechat.DesktopCakeChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CakeChat: install it, give it a corpus, train it, move the result around.
 *
 * The desktop counterpart of Android's CakeChatTrainingActivity, with the same capabilities in the
 * order you actually use them — install, corpus, train, then import/export. It renders
 * [DesktopCakeChat.progress], which is fed by a JSON stream from the Python subprocess, so the
 * screen and the trainer cannot disagree about what is happening.
 */
@Composable
fun CakeChatPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress by DesktopCakeChat.progress.collectAsState()

    var state by remember { mutableStateOf(DesktopCakeChat.state()) }
    var setupLog by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var corpusStatus by remember {
        mutableStateOf(
            if (DesktopCakeChat.datasetFile().isFile)
                "Using ${DesktopCakeChat.datasetFile().name}"
            else "No corpus chosen yet."
        )
    }
    var bundleStatus by remember { mutableStateOf("") }
    var epochs by remember { mutableStateOf("2") }
    var batchSize by remember { mutableStateOf("32") }
    var subsetSize by remember { mutableStateOf("") }
    var hiddenDim by remember { mutableStateOf("") }

    fun refresh() { state = DesktopCakeChat.state() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Spacer(Modifier.width(8.dp))
            Text("CakeChat", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Replika's original conversational model — a conditioned seq2seq with five emotions. " +
                "It ships no pretrained weights any more, so it has to be trained on a corpus " +
                "before it can answer anything. Everything it needs, Python included, is " +
                "installed under Prism's own data directory.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )

        // ── Environment ────────────────────────────────────────────────────
        SectionCard("Environment") {
            val python = remember { DesktopCakeChat.findPythonInfo() }
            Text(
                when (state) {
                    DesktopCakeChat.State.NO_PYTHON ->
                        "No Python is available for this platform, and there is no prebuilt one to " +
                            "download for it either. Install Python 3.11 and set PRISM_PYTHON to it."
                    DesktopCakeChat.State.ABSENT -> when {
                        python == null ->
                            "CakeChat is not installed yet. Prism will download a Python for it."
                        python.isSupported ->
                            "Python ${python.version} found at ${python.exe.path}. " +
                                "CakeChat is not installed yet."
                        else ->
                            "Python ${python.version} is too new for CakeChat, so Prism will " +
                                "download its own CPython 3.11 alongside it and leave yours alone."
                    }
                    DesktopCakeChat.State.INSTALLED ->
                        "Installed and ready to train."
                    DesktopCakeChat.State.TRAINED ->
                        "Installed, with a trained model."
                },
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !busy && state != DesktopCakeChat.State.NO_PYTHON,
                onClick = {
                    busy = true
                    setupLog = listOf("Starting…")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            DesktopCakeChat.install { line ->
                                setupLog = (setupLog + line).takeLast(200)
                            }
                        }
                        busy = false
                        refresh()
                    }
                },
            ) {
                Text(
                    if (state == DesktopCakeChat.State.ABSENT) "Download and install"
                    else "Reinstall"
                )
            }
            if (setupLog.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LogBox(setupLog)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Corpus ─────────────────────────────────────────────────────────
        SectionCard("Corpus") {
            Text(corpusStatus, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row {
            Button(
                enabled = !busy && state != DesktopCakeChat.State.NO_PYTHON,
                onClick = {
                    pickFile("Choose a corpus (DailyDialog zip, or a dialog file)")?.let { file ->
                        busy = true
                        corpusStatus = "Converting…"
                        scope.launch {
                            corpusStatus = withContext(Dispatchers.IO) {
                                DesktopCakeChat.importCorpus(file) { line -> corpusStatus = line }
                            }
                            busy = false
                        }
                    }
                },
            ) { Text("Choose corpus…") }
            Spacer(Modifier.width(12.dp))
            // A SECOND BUTTON RATHER THAN ONE THAT TAKES EITHER. No native chooser on Windows can
            // select a file or a folder in the same dialog, so offering one control would mean
            // silently refusing half of what it appeared to accept.
            OutlinedButton(
                enabled = !busy && state != DesktopCakeChat.State.NO_PYTHON,
                onClick = {
                    pickDirectory("Choose a dataset folder")?.let { folder ->
                        busy = true
                        scope.launch {
                            corpusStatus = withContext(Dispatchers.IO) {
                                DesktopCakeChat.importCorpus(folder) { line -> corpusStatus = line }
                            }
                            busy = false
                        }
                    }
                },
            ) { Text("Choose folder…") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "A DailyDialog zip is converted automatically, including its parallel emotion " +
                    "labels. Use the folder button for a Parquet dataset published as numbered " +
                    "shards — every .parquet file in it is read, in name order. Anything without " +
                    "emotion labels is read as best it can be and left neutral, which trains but " +
                    "teaches the emotion conditioning nothing.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Training ───────────────────────────────────────────────────────
        SectionCard("Training") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = epochs, onValueChange = { epochs = it.filter(Char::isDigit) },
                    label = { Text("Epochs") }, singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = batchSize, onValueChange = { batchSize = it.filter(Char::isDigit) },
                    label = { Text("Batch size") }, singleLine = true,
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = subsetSize, onValueChange = { subsetSize = it.filter(Char::isDigit) },
                    label = { Text("Limit to N dialogs") },
                    placeholder = { Text("all") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = hiddenDim, onValueChange = { hiddenDim = it.filter(Char::isDigit) },
                    label = { Text("Layer width") },
                    placeholder = { Text("768") },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Layer width is what decides whether the result runs on a phone: a recurrent " +
                    "layer's parameters grow with the SQUARE of it, and CakeChat's default of 768 " +
                    "needs about 850 MB to load — more than Android gives one process. 256 is " +
                    "roughly a ninth of those parameters. A model must be loaded at the width it " +
                    "was trained at, so changing this means retraining. " +
                    "Steps per epoch is the corpus divided by batch size, so the dialog limit is what " +
                    "actually decides how long a run takes — leave it blank to train on " +
                    "everything. Batch size halves automatically if a run exhausts memory, so a " +
                    "value that turns out to be too large costs time rather than the whole run.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            // Indeterminate until there is a real step count. Preparing the corpus and building the
            // index files happens before Keras reports any total, and a bar pinned at 0% for that
            // whole stretch reads as nothing happening rather than as work without a known length.
            if (progress.running && progress.total <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Deliberately the same wording and the same three lines as the phone's training
            // screen, so a run started on one and inspected on the other reads identically.
            Text(
                when {
                    // First line only: the full text, traceback included, is in the log panel
                    // below, which can scroll. This line is a summary and has to stay short.
                    progress.error != null -> "Failed: ${progress.error!!.lineSequence().first()}"
                    progress.finished -> "Training finished. Weights are in the model directory."
                    progress.phase == "preprocessing" -> "Preparing corpus and index files…"
                    progress.phase == "converting" ->
                        "Converting the model for mobile (TFLite)…"
                    progress.running && progress.total > 0 -> buildString {
                        append("Step ").append(progress.step).append(" of ").append(progress.total)
                        append("  ·  ").append(progress.stepsLeft).append(" steps left\n")
                        append("Epoch ").append(progress.epoch).append('/').append(progress.epochs)
                        if (progress.loss > 0) append("  ·  loss %.4f".format(progress.loss))
                        append('\n')
                        append(clock(progress.elapsedSeconds)).append(" elapsed")
                        if (progress.etaSeconds > 0) {
                            append("  ·  ETA ").append(clock(progress.etaSeconds))
                        }
                    }
                    progress.running -> "Starting…"
                    else -> "Not training."
                },
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    enabled = !progress.running && !busy &&
                        state != DesktopCakeChat.State.NO_PYTHON &&
                        state != DesktopCakeChat.State.ABSENT &&
                        DesktopCakeChat.datasetFile().isFile,
                    onClick = {
                        DesktopCakeChat.startTraining(
                            epochs.toIntOrNull() ?: 2,
                            batchSize.toIntOrNull() ?: 32,
                            subsetSize.toIntOrNull() ?: 0,
                            hiddenDim.toIntOrNull() ?: 0,
                        )
                    },
                ) { Text("Train") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    enabled = progress.running,
                    onClick = { DesktopCakeChat.stopTraining() },
                ) { Text("Stop") }
                Spacer(Modifier.width(12.dp))
                // Only useful for a model trained before conversion became part of training, which
                // is why it sits beside Train rather than replacing anything.
                OutlinedButton(
                    enabled = !progress.running && !busy && state == DesktopCakeChat.State.TRAINED,
                    onClick = { DesktopCakeChat.startConversion() },
                ) { Text("Convert for mobile") }
            }

            // ALWAYS RENDERED, even with nothing in it. A panel that appears only once there is
            // output gives the user nowhere to look when the problem is that there is no output --
            // which is exactly the case worth diagnosing.
            Spacer(Modifier.height(12.dp))
            Text(
                "Log", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LogBox(
                when {
                    // The error is appended rather than shown alone: what a run printed before it
                    // died is usually what explains why it died.
                    progress.error != null ->
                        progress.logs + "" + progress.error!!.lines()
                    progress.logs.isNotEmpty() -> progress.logs
                    else -> listOf("No output yet.")
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Bundles ────────────────────────────────────────────────────────
        SectionCard("Model bundle") {
            Text(
                "The same zip the phone reads and writes: weights plus both index files. They only " +
                    "mean anything together — weights are sized by the vocabulary that produced " +
                    "them, so one without the other loads into the wrong shape.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    enabled = !busy,
                    onClick = {
                        pickFile("Choose a model bundle (.zip)")?.let { file ->
                            scope.launch {
                                bundleStatus = withContext(Dispatchers.IO) {
                                    DesktopCakeChat.importBundle(file)
                                }
                                refresh()
                            }
                        }
                    },
                ) { Text("Import bundle…") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    enabled = !busy && state == DesktopCakeChat.State.TRAINED,
                    onClick = {
                        saveFile("prism-cakechat-model.zip")?.let { file ->
                            scope.launch {
                                bundleStatus = withContext(Dispatchers.IO) {
                                    DesktopCakeChat.exportBundle(file)
                                }
                            }
                        }
                    },
                ) { Text("Export bundle…") }
            }
            if (bundleStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(bundleStatus, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

/** Monospaced and scrolling, because the content is tracebacks as often as it is progress. */
@Composable
private fun LogBox(lines: List<String>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
            for (line in lines.takeLast(200)) {
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** AWT rather than Compose: Compose Desktop has no file chooser, and this is the native one. */
private fun pickFile(title: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/**
 * Picks a folder, for datasets published as numbered shards.
 *
 * SWING RATHER THAN AWT HERE, because AWT's FileDialog cannot select a directory on Windows at all
 * -- the platform dialog it wraps is a file chooser, and the usual `apple.awt.fileDialogForDirectories`
 * trick is macOS-only. JFileChooser is uglier and is the only one that can do this everywhere.
 */
private fun pickDirectory(title: String): File? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}

private fun saveFile(suggested: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export model", java.awt.FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
