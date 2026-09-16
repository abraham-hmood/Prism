package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.nora.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * Nora's desktop pages.
 *
 * Every one of these is a pure consumer of `:core` -- no new extraction was needed for any of
 * them, which is the payoff from Phases 1 and 2. `NoraSelfTestState`'s StateFlows drive the model
 * test screen here exactly as they drive `NoraSelfTestActivity` on Android, so the two screens
 * cannot drift in what they report.
 */

// ── Chat ────────────────────────────────────────────────────────────────────

/**
 * Prompt in, image out, with the same four generation routes the Android build offers.
 *
 * The brain is held for the lifetime of the page rather than rebuilt per generation. Building one
 * at a large geometry is seconds of work and allocates the whole connectome; doing it per prompt
 * would make the first token of every request wait on it.
 */
/**
 * Every route the chat page can drive, still-image `NoraImageryMode`s plus the two non-saccadic
 * routes that aren't modes of the same settle loop -- see NORA.md S6e. A sealed wrapper rather
 * than extending `NoraImageryMode` itself, because `/expose` and `/hallucinate` call different
 * `MentalImagery` methods entirely (`generateDeepExposure`, `generateHallucination`), not
 * `generateStill` with a different settle.
 */
private sealed class ChatRoute {
    data class Still(val mode: NoraImageryMode) : ChatRoute()
    object Expose : ChatRoute()
    object Hallucinate : ChatRoute()

    fun label(): String = when (this) {
        is Still -> NoraSelfTest.routeName(mode)
        Expose -> "/expose"
        Hallucinate -> "/hallucinate"
    }
}

@Composable
fun NoraChatPage() {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<ChatRoute>(ChatRoute.Still(NoraImageryMode.DETERMINISTIC)) }
    var routeMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var feedbackToken by remember { mutableStateOf<String?>(null) }
    var rated by remember { mutableStateOf(false) }

    val brain = remember { mutableStateOf<NoraBrain?>(null) }
    var vocabulary by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val b = NoraBrain()
            val loaded = NoraPersistence.load(b)
            brain.value = b
            vocabulary = b.semanticHub.knownWords()
            status = if (loaded) {
                "Connectome loaded — $vocabulary words."
            } else {
                "No connectome on disk. Output will be untrained noise until you train her."
            }
        }
    }

    PageScaffold("Nora", "Brain-based image generation") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommittingField(prompt, enabled = !busy, modifier = Modifier.weight(1f)) { prompt = it }
            Spacer(Modifier.width(10.dp))

            Box {
                OutlinedButton(onClick = { routeMenu = true }, enabled = !busy) {
                    Text(route.label(), fontSize = 13.sp)
                }
                DropdownMenu(routeMenu, onDismissRequest = { routeMenu = false }) {
                    NoraImageryMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(NoraSelfTest.routeName(mode)) },
                            onClick = { route = ChatRoute.Still(mode); routeMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("/expose — one held gaze, prompt released partway") },
                        onClick = { route = ChatRoute.Expose; routeMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("/hallucinate — recursive video, no fixation") },
                        onClick = { route = ChatRoute.Hallucinate; routeMenu = false }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))

            Button(
                enabled = !busy && prompt.isNotBlank() && brain.value != null,
                onClick = {
                    val b = brain.value ?: return@Button
                    val text = prompt
                    val currentRoute = route
                    busy = true; rated = false; status = "Generating via ${currentRoute.label()}…"
                    scope.launch {
                        when (currentRoute) {
                            is ChatRoute.Still -> {
                                val result = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val imagery = MentalImagery(b)
                                        val bias = NoraFeedback.biasFor(text)
                                        val img = imagery.generateStill(text, mode = currentRoute.mode, bias = bias)
                                        // Recorded so a rating has something to attach to. The
                                        // concept is the IT pattern the image came from, which is
                                        // what feedback actually reinforces or unwinds -- not the
                                        // pixels.
                                        val token = imagery.lastConcept?.let { concept ->
                                            NoraFeedback.record(
                                                prompt = text,
                                                caption = text,
                                                semantic = b.semanticHub.encode(text),
                                                itPattern = concept
                                            )
                                        }
                                        Triple(img, token, b.lastSurfaceRange)
                                    }
                                }
                                result.onSuccess { (img, token, range) ->
                                    image = img.toComposeBitmap()
                                    feedbackToken = token
                                    val out = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.png")
                                    PrismPlatform.images.encodePng(img, out)
                                    status = if (range <= 1e-4f) {
                                        "Surface range %.6f — this is a constant, not a picture. See NORA.md."
                                            .format(range)
                                    } else {
                                        "Written to ${out.name} · surface range %.4f".format(range)
                                    }
                                }.onFailure {
                                    status = "Failed: ${it.javaClass.simpleName}: ${it.message}"
                                }
                            }

                            ChatRoute.Expose -> {
                                val result = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val imagery = MentalImagery(b)
                                        val bias = NoraFeedback.biasFor(text)
                                        val img = imagery.generateDeepExposure(text, bias = bias) { done, total ->
                                            val phase = if (done <= NoraConfig.EXPOSURE_PRIME_ITERATIONS) {
                                                "primed"
                                            } else "free-running"
                                            status = "Settling $done/$total ($phase)…"
                                        }
                                        val token = imagery.lastConcept?.let { concept ->
                                            NoraFeedback.record(
                                                prompt = text,
                                                caption = text,
                                                semantic = b.semanticHub.encode(text),
                                                itPattern = concept
                                            )
                                        }
                                        Triple(img, token, b.lastSurfaceRange)
                                    }
                                }
                                result.onSuccess { (img, token, range) ->
                                    image = img.toComposeBitmap()
                                    feedbackToken = token
                                    val out = File(NoraConfig.outputDir(), "nora_expose_${System.currentTimeMillis()}.png")
                                    PrismPlatform.images.encodePng(img, out)
                                    status = if (range <= 1e-4f) {
                                        "Surface range %.6f — this is a constant, not a picture. See NORA.md."
                                            .format(range)
                                    } else {
                                        "Written to ${out.name} · surface range %.4f".format(range)
                                    }
                                }.onFailure {
                                    status = "Failed: ${it.javaClass.simpleName}: ${it.message}"
                                }
                            }

                            ChatRoute.Hallucinate -> {
                                val result = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val imagery = MentalImagery(b)
                                        val bias = NoraFeedback.biasFor(text)
                                        val frames = imagery.generateHallucination(text, bias = bias) { done, total ->
                                            status = "Dreaming frame $done/$total…"
                                        }
                                        val token = imagery.lastConcept?.let { concept ->
                                            NoraFeedback.record(
                                                prompt = text,
                                                caption = text,
                                                semantic = b.semanticHub.encode(text),
                                                itPattern = concept
                                            )
                                        }
                                        Pair(frames, token)
                                    }
                                }
                                result.onSuccess { (frames, token) ->
                                    if (frames.isEmpty()) {
                                        status = "No frames were produced."
                                        return@onSuccess
                                    }
                                    // Shown as a preview only -- Compose Desktop has no video
                                    // widget, so the last frame stands in for the clip the way a
                                    // thumbnail would.
                                    image = frames.last().toComposeBitmap()
                                    feedbackToken = token
                                    val dir = File(
                                        NoraConfig.outputDir(),
                                        "nora_hallucination_${System.currentTimeMillis()}"
                                    ).apply { mkdirs() }
                                    var written = 0
                                    for ((i, frame) in frames.withIndex()) {
                                        val out = File(dir, "frame_${i.toString().padStart(3, '0')}.png")
                                        if (PrismPlatform.images.encodePng(frame, out)) written++
                                    }
                                    status = "$written/${frames.size} frames written to ${dir.name} " +
                                        "(showing the last one) — not a video file, assemble with ffmpeg."
                                }.onFailure {
                                    status = "Failed: ${it.javaClass.simpleName}: ${it.message}"
                                }
                            }
                        }
                        busy = false
                    }
                }
            ) { Text("Generate") }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }

        Text(status, fontSize = 12.sp, color = Color(0xFF83838F),
            modifier = Modifier.padding(top = 12.dp))

        Spacer(Modifier.height(18.dp))

        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF121216), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val img = image
            if (img == null) {
                Text("No image yet", color = Color(0xFF55555F), fontSize = 14.sp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(img, null, Modifier.size(340.dp))
                    if (feedbackToken != null) {
                        Spacer(Modifier.height(14.dp))
                        if (rated) {
                            Text("Thanks — applied on the next training run.",
                                fontSize = 12.sp, color = Color(0xFF83838F))
                        } else {
                            Row {
                                // Ratings are durable immediately and applied later, matching
                                // Android: NoraFeedback.rate writes the trace, and the next
                                // training run or explicit apply consumes it.
                                IconButton(onClick = {
                                    NoraFeedback.rate(feedbackToken!!, true); rated = true
                                }) { Icon(Icons.Filled.ThumbUp, "Good", tint = Color(0xFF6FCF97)) }
                                IconButton(onClick = {
                                    NoraFeedback.rate(feedbackToken!!, false); rated = true
                                }) { Icon(Icons.Filled.ThumbDown, "Bad", tint = Color(0xFFEB5757)) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Training ────────────────────────────────────────────────────────────────

@Composable
fun NoraTrainingPage() {
    val scope = rememberCoroutineScope()
    var epochs by remember { mutableStateOf("4") }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<String>() }
    var datasetSize by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        datasetSize = withContext(Dispatchers.IO) { NoraTrainer.loadDataset().size }
    }

    PageScaffold("Training", NoraConfig.datasetDir().absolutePath) {
        Card {
            InfoRow("Dataset images", if (datasetSize < 0) "counting…" else "$datasetSize")
            Hairline()
            InfoRow("Geometry", NoraConfig.geometry.signature(), mono = true)
            Hairline()
            InfoRow("Neurons", NoraGeometry.formatCount(NoraConfig.geometry.totalNeurons))
            Hairline()
            InfoRow("Placement", NoraPerformance.describe())
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Epochs", fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            CommittingField(epochs, enabled = !running, modifier = Modifier.width(90.dp)) { epochs = it }
            Spacer(Modifier.width(16.dp))
            Button(
                enabled = !running && datasetSize > 0,
                onClick = {
                    val n = epochs.toIntOrNull() ?: 4
                    running = true; lines.clear(); summary = ""
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching {
                                val brain = NoraBrain()
                                NoraTrainer(brain).train(n) { p ->
                                    val line = if (p.phase == "sleep") {
                                        "e${p.epoch}/${p.totalEpochs}  sleep — consolidating"
                                    } else {
                                        "e${p.epoch}/${p.totalEpochs}  ${p.sample}/${p.totalSamples}  " +
                                            "err %.4f  %s".format(p.errorRms, p.caption)
                                    }
                                    // Bounded: a long run over a real dataset produces tens of
                                    // thousands of lines and an unbounded list would grow without
                                    // limit for no benefit -- the tail is what anyone reads.
                                    synchronized(lines) {
                                        lines.add(line)
                                        if (lines.size > 400) lines.removeAt(0)
                                    }
                                }
                            }
                        }
                        summary = result.getOrElse { "Failed: ${it.message}" }
                        running = false
                    }
                }
            ) { Text(if (running) "Training…" else "Start training") }
        }

        if (datasetSize == 0) {
            SectionFooter(
                "No images found. Drop files into the folder above — the filename becomes the " +
                    "caption, so \"a red bicycle.png\" trains that phrase."
            )
        }

        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))

        SectionHeader("Log")
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF101014), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.verticalScroll(scroll)) {
                lines.forEach {
                    Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9A9AA6))
                }
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(summary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = Color(0xFFCFCFDA))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Model test ──────────────────────────────────────────────────────────────

/**
 * The same model test the Android build runs, driven by the same state object.
 *
 * `NoraSelfTestState` is a process-wide singleton of StateFlows, so this screen is a pure view
 * over it -- exactly like `NoraSelfTestActivity`. That is what makes a verdict here comparable
 * with a verdict on the phone: not two implementations that agree, one implementation observed
 * twice.
 */
@Composable
fun ModelTestPage() {
    val scope = rememberCoroutineScope()
    val running by NoraSelfTestState.running.collectAsState()
    val outcome by NoraSelfTestState.outcome.collectAsState()
    val log by NoraSelfTestState.log.collectAsState()
    val regenerable by NoraSelfTestState.regenerable.collectAsState()
    var route by remember { mutableStateOf(NoraImageryMode.DETERMINISTIC) }
    var routeMenu by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<Pair<String, ImageBitmap>>>(emptyList()) }

    LaunchedEffect(outcome) {
        val o = outcome
        samples = if (o == null) emptyList() else withContext(Dispatchers.IO) {
            o.samplePaths.mapNotNull { (prompt, path) ->
                PrismPlatform.images.decode(File(path), 512)?.let { prompt to it.toComposeBitmap() }
            }
        }
    }

    PageScaffold("Model Test", "Trains on nine generated shapes and judges the result") {
        Card {
            InfoRow("Geometry", NoraConfig.geometry.signature(), mono = true)
            Hairline()
            InfoRow("Neurons", NoraGeometry.formatCount(NoraConfig.geometry.totalNeurons))
            Hairline()
            InfoRow("Estimated size", NoraGeometry.formatBytes(NoraConfig.geometry.estimateBytes()))
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { routeMenu = true }, enabled = !running) {
                    Text("Route: ${NoraSelfTest.routeName(route)}", fontSize = 13.sp)
                }
                DropdownMenu(routeMenu, onDismissRequest = { routeMenu = false }) {
                    NoraImageryMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(NoraSelfTest.routeName(mode)) },
                            onClick = { route = mode; routeMenu = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(enabled = !running, onClick = {
                scope.launch { withContext(Dispatchers.Default) { NoraSelfTest.run(mode = route) } }
            }) { Text("Run test") }

            // Only offered once a trained brain is standing by, because that is the only time it
            // does anything -- training is route-independent and slow, generation is route-
            // specific and fast, so comparing routes should cost one training run and not four.
            if (regenerable && !running) {
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = {
                    scope.launch { withContext(Dispatchers.Default) { NoraSelfTest.regenerate(route) } }
                }) { Text("Regenerate") }
            }
        }

        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))

        outcome?.let { o ->
            Spacer(Modifier.height(16.dp))
            Text(o.headline, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = if (o.headline == "Passed") Color(0xFF6FCF97) else Color(0xFFF2C94C))
        }

        if (samples.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                samples.forEach { (prompt, bmp) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bmp, prompt, Modifier.size(120.dp))
                        Text(prompt, fontSize = 11.sp, color = Color(0xFF83838F),
                            modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }

        SectionHeader("Log")
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF101014), RoundedCornerShape(10.dp)).padding(12.dp)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.verticalScroll(scroll)) {
                log.forEach {
                    Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9A9AA6))
                }
                outcome?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it.report, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = Color(0xFFCFCFDA))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Settings ────────────────────────────────────────────────────────────────

/**
 * Generated from the parameter registries, never hand-written.
 *
 * Same rule as `NoraSettingsActivity`: a parameter cannot exist in the model without appearing
 * here, because the screen is built by iterating `NoraTuning.PARAMS` and `NoraPerformance.PARAMS`.
 * The failure mode of a hand-written settings screen is a knob that silently stops being
 * reachable, and it is not detectable by testing the thing you forgot.
 */
@Composable
fun NoraSettingsPage() {
    var revision by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var archiveBusy by remember { mutableStateOf(false) }
    var archiveResult by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<File?>(null) }

    PageScaffold("Nora Settings", "${NoraTuning.PARAMS.size} parameters, ${NoraPerformance.FLAGS.size} switches") {
        Column(Modifier.verticalScroll(scroll).weight(1f)) {
            key(revision) {
                SectionHeader("Brain size")
                Card {
                    InfoRow("Neurons", NoraGeometry.formatCount(NoraConfig.geometry.totalNeurons))
                    Hairline()
                    InfoRow("Parameters", NoraGeometry.formatCount(NoraConfig.geometry.totalParameters))
                    Hairline()
                    InfoRow("Estimated size", NoraGeometry.formatBytes(NoraConfig.geometry.estimateBytes()))
                    Hairline()
                    InfoRow("Budget", NoraGeometry.formatBytes(NoraGeometry.memoryBudgetBytes()))
                    Hairline()
                    NavRow(
                        "Use this machine's maximum",
                        "Largest brain that fits the heap ceiling — raise it with -Xmx"
                    ) {
                        NoraStudioLite.applyGeometry(NoraGeometry.maxForDevice()); revision++
                    }
                    Hairline()
                    MemoryRow { revision++ }
                    Hairline()
                    NavRow("Reset to default size", "Back to the tuned baseline geometry") {
                        NoraStudioLite.applyGeometry(NoraGeometry.DEFAULT); revision++
                    }
                }

                SectionHeader("Performance")
                Card {
                    NoraPerformance.FLAGS.forEachIndexed { i, flag ->
                        if (i > 0) Hairline()
                        val unavailable = flag.key == "native_conv" && !NoraNative.available()
                        ToggleRow(
                            flag.label,
                            if (unavailable) {
                                "${flag.detail}\n\nUnavailable here — nora_conv is built arm64-only " +
                                    "by the Android module's CMake."
                            } else flag.detail,
                            flag.read(),
                            enabled = !unavailable
                        ) { flag.write(it); NoraPerformance.save(); revision++ }
                    }
                }

                for (group in NoraPerformance.GROUPS) {
                    SectionHeader(group)
                    Card {
                        NoraPerformance.PARAMS.filter { it.group == group }.forEachIndexed { i, p ->
                            if (i > 0) Hairline()
                            ParamRow(p) { NoraPerformance.save(); revision++ }
                        }
                    }
                }

                for (group in NoraTuning.GROUPS) {
                    SectionHeader(group)
                    Card {
                        NoraTuning.PARAMS.filter { it.group == group }.forEachIndexed { i, p ->
                            if (i > 0) Hairline()
                            ParamRow(p) { NoraTuning.save(); revision++ }
                        }
                    }
                }

                SectionHeader("Data")
                Card {
                    NavRow(
                        "Backup Nora",
                        "Connectome, dataset, feedback and conversation to a zip"
                    ) {
                        val chooser = javax.swing.JFileChooser().apply {
                            dialogTitle = "Save Nora backup"
                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Zip archive", "zip")
                            selectedFile = File(NoraArchiveDesktop.suggestedFileName())
                        }
                        if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            var target = chooser.selectedFile
                            if (!target.name.endsWith(".zip", ignoreCase = true)) {
                                target = File(target.parentFile, "${target.name}.zip")
                            }
                            archiveBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { NoraArchiveDesktop.backup(target) }
                                archiveResult = result.message
                                archiveBusy = false
                            }
                        }
                    }
                    Hairline()
                    NavRow(
                        "Import Nora",
                        "Restore everything from a backup zip -- overwrites the current connectome",
                        destructive = true
                    ) {
                        val chooser = javax.swing.JFileChooser().apply {
                            dialogTitle = "Choose a Nora backup"
                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Zip archive", "zip")
                        }
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            pendingImport = chooser.selectedFile
                        }
                    }
                    if (archiveBusy) {
                        Hairline()
                        Text(
                            "Working…", fontSize = 12.sp, color = Color(0xFF83838F),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }

                SectionHeader("Defaults")
                Card {
                    NavRow(
                        "Reset all parameters",
                        "${NoraTuning.changedCount()} of ${NoraTuning.PARAMS.size} differ from default",
                        destructive = true
                    ) { NoraTuning.resetToDefaults(); revision++ }
                    Hairline()
                    NavRow(
                        "Reset performance settings",
                        "${NoraPerformance.changedCount()} differ from default",
                        destructive = true
                    ) { NoraPerformance.resetToDefaults(); NoraSwap.delete(); revision++ }
                }
                SectionFooter(
                    "Tuning parameters change WHAT Nora computes; performance switches change only " +
                        "HOW the same computation runs. A worse image after a tuning change is a " +
                        "modelling result. After a performance change it is a bug."
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    pendingImport?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import Nora?") },
            text = {
                Text(
                    "This overwrites the connectome on disk and merges the dataset from " +
                        "\"${file.name}\". Anything learned since your last backup is lost. This " +
                        "can't be undone.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    archiveBusy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { NoraArchiveDesktop.import(file) }
                        archiveResult = result.message
                        archiveBusy = false
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }

    archiveResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { archiveResult = null },
            confirmButton = { TextButton(onClick = { archiveResult = null }) { Text("OK") } },
            title = { Text("Nora archive") },
            text = { Text(msg, fontSize = 13.sp) }
        )
    }
}

/**
 * Continuous version of "Use this machine's maximum" -- drag to any RAM figure between the
 * smallest legal brain and the same heap-ceiling budget that button targets, rather than only
 * being able to jump straight to the ceiling or to the tuned default.
 *
 * Mirrors `NoraSettingsActivity.memoryRow()` on Android exactly: the slider previews locally
 * while dragging (re-solving geometry on every pixel of drag would rewrite preferences and drop
 * the brain dozens of times per swipe) and only commits -- `NoraStudioLite.applyGeometry`, which
 * rebuilds the connectome at the new size -- once the drag ends.
 */
@Composable
private fun MemoryRow(onCommit: () -> Unit) {
    val sliderMinBytes = NoraGeometry.minimum().estimateBytes()
    val sliderMaxBytes = NoraGeometry.memoryBudgetBytes()
    var sliderBytes by remember {
        mutableStateOf(NoraConfig.geometry.estimateBytes().coerceIn(sliderMinBytes, sliderMaxBytes))
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Memory budget", fontSize = 15.sp)

        if (sliderMaxBytes <= sliderMinBytes) {
            // Same floor Android's slider hits on a heap too small for even the smallest brain --
            // better to say so than show a control with no usable range.
            Text(
                "This process's heap is too small for a resizable brain — " +
                    "${NoraGeometry.formatBytes(sliderMaxBytes)} available, " +
                    "${NoraGeometry.formatBytes(sliderMinBytes)} needed at minimum.",
                fontSize = 12.sp, color = Color(0xFF83838F),
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }

        Slider(
            value = sliderBytes.toFloat(),
            valueRange = sliderMinBytes.toFloat()..sliderMaxBytes.toFloat(),
            onValueChange = { sliderBytes = it.toLong() },
            onValueChangeFinished = {
                NoraStudioLite.applyGeometry(NoraGeometry.largestWithin(sliderBytes))
                onCommit()
            },
            modifier = Modifier.padding(top = 4.dp)
        )

        val preview = NoraGeometry.largestWithin(sliderBytes)
        Text(
            "${NoraGeometry.formatBytes(sliderBytes)} of ${NoraGeometry.formatBytes(sliderMaxBytes)} " +
                "available → ${NoraGeometry.formatCount(preview.totalNeurons)} neurons · " +
                "%.1fx training cost".format(preview.relativeTrainingCost()),
            fontSize = 12.sp, color = Color(0xFFA8A8B4),
            modifier = Modifier.padding(top = 2.dp)
        )

        // Why the ceiling is what it is -- without this the control looks broken on a high-RAM
        // machine: the JVM's -Xmx cap is what actually bounds Nora's FloatArrays, not whatever
        // memory happens to be free.
        val ram = NoraGeometry.deviceRamBytes()
        Text(
            "Ceiling is this process's heap limit (${NoraGeometry.formatBytes(sliderMaxBytes)}" +
                (if (ram > 0) " of ${NoraGeometry.formatBytes(ram)} machine RAM" else "") +
                "), not total RAM. Raise it with -Xmx and restart to widen this range.",
            fontSize = 11.sp, color = Color(0xFF7A7A88), lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ParamRow(param: NoraTuning.Param, onCommit: () -> Unit) {
    val enabled = param.enabled()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).alphaIf(enabled)) {
        Text(param.label, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        CommittingField(param.display(), enabled = enabled) {
            if (param.apply(it)) onCommit()
        }
        Text(
            "${param.detail}\nRange ${trimNumber(param.min)}–${trimNumber(param.max)}",
            fontSize = 11.sp, color = Color(0xFF7A7A88), lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun trimNumber(v: Float): String =
    "%.6f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/**
 * The bit of `NoraStudio` the desktop needs, without the Android parts.
 *
 * `NoraStudio` itself is still in `:app` because it publishes to the media gallery and holds a
 * Context. Resizing is the only thing this screen needs from it, and the order matters: the
 * geometry must be persisted and the config updated before anything constructs a brain from it.
 */
private object NoraStudioLite {
    fun applyGeometry(g: NoraGeometry) {
        val before = NoraConfig.geometry.signature()
        // Persist first, then install. NoraConfig.install is the supported way to change the
        // live geometry -- the property's setter is private precisely so a caller cannot update
        // the in-memory value without also writing it, which would silently revert on restart.
        NoraConfig.saveGeometry(g)
        NoraConfig.install(g)
        NoraHealth.reset()
        // Swap regions belong to the previous geometry and are unreachable now.
        NoraSwap.reset()
        NoraLog.info(NoraLog.Area.GEOMETRY, "Resized $before -> ${NoraLog.describeGeometry()}")
    }
}

/**
 * Desktop port of `NoraArchive` (`app/.../nora/NoraArchive.kt`) -- identical zip format,
 * manifest, zip-slip guard and geometry check. The only thing that changes is the I/O surface:
 * Android goes through `Context`/`Uri`/`ContentResolver` because it writes through the Storage
 * Access Framework; desktop has no SAF, so this reads and writes a plain [File] chosen by a
 * native file dialog instead. `NoraStudio.reload(ctx)` has no desktop counterpart to call --
 * there is no cached global brain instance here, each page builds its own -- so the caller is
 * told to revisit the Nora page instead, which is honest rather than silently doing nothing.
 */
private object NoraArchiveDesktop {

    private const val MANIFEST_ENTRY = "nora-backup.json"
    private const val FORMAT_VERSION = 1

    data class Result(val ok: Boolean, val message: String)

    /** Suggested filename for the save dialog. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "nora-backup-$stamp.zip"
    }

    fun backup(destination: File): Result {
        val root = NoraConfig.rootDir()
        val connectome = NoraConfig.weightsDir()
        val dataset = NoraConfig.datasetDir()
        val chat = NoraConfig.chatFile()

        val datasetFiles = dataset.listFiles()?.filter { it.isFile } ?: emptyList()
        val connectomeFiles = connectome.listFiles()?.filter { it.isFile } ?: emptyList()
        val feedbackFiles = NoraConfig.feedbackDir().listFiles()?.filter { it.isFile } ?: emptyList()

        if (datasetFiles.isEmpty() && connectomeFiles.isEmpty()) {
            return Result(
                false,
                "Nothing to back up — no connectome and no dataset images were found in " +
                    root.absolutePath
            )
        }

        return try {
            var bytes = 0L
            var count = 0

            java.util.zip.ZipOutputStream(BufferedOutputStream(destination.outputStream())).use { zip ->
                val manifest = JSONObject().apply {
                    put("format", FORMAT_VERSION)
                    put("created", System.currentTimeMillis())
                    put("datasetImages", datasetFiles.size)
                    put("hasConnectome", connectomeFiles.isNotEmpty())
                    put("rings", NoraConfig.RINGS)
                    put("wedges", NoraConfig.WEDGES)
                    put("itChannels", NoraConfig.IT_CH)
                    put("semanticUnits", NoraConfig.SEMANTIC_UNITS)
                }
                zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()

                for (f in connectomeFiles) { bytes += addFile(zip, f, "connectome/${f.name}"); count++ }
                for (f in datasetFiles) { bytes += addFile(zip, f, "dataset/${f.name}"); count++ }
                for (f in feedbackFiles) { bytes += addFile(zip, f, "feedback/${f.name}"); count++ }
                if (chat.exists()) { bytes += addFile(zip, chat, chat.name); count++ }
            }

            Result(
                true,
                "Backed up $count files (${bytes / 1024} KB uncompressed):\n" +
                    "· ${connectomeFiles.size} connectome file(s)\n" +
                    "· ${datasetFiles.size} dataset image(s)\n" +
                    "· ${feedbackFiles.size} feedback file(s)\n" +
                    "· conversation history\n\n" +
                    "Generated output was not included — it's regenerable and would dominate " +
                    "the archive size."
            )
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.STORAGE, "Backup failed: ${e.message}", e)
            Result(false, "Backup failed: ${e.message}")
        }
    }

    private fun addFile(zip: java.util.zip.ZipOutputStream, file: File, entryName: String): Long {
        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
        var total = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zip.write(buf, 0, n)
                total += n
            }
        }
        zip.closeEntry()
        return total
    }

    fun import(source: File): Result {
        val root = NoraConfig.rootDir()
        val rootPath = root.canonicalPath

        return try {
            var connectomeFiles = 0
            var datasetFiles = 0
            var feedbackFiles = 0
            var chatRestored = false
            var manifest: JSONObject? = null

            java.util.zip.ZipInputStream(BufferedInputStream(source.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }

                    if (entry.name == MANIFEST_ENTRY) {
                        manifest = try {
                            JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        } catch (e: Exception) { null }
                        zip.closeEntry()
                        continue
                    }

                    val target = File(root, entry.name)
                    // Zip-slip guard: an entry named "../../../..." would otherwise write
                    // anywhere this process can reach. Never trust a path from an archive.
                    if (!target.canonicalPath.startsWith(rootPath + File.separator)) {
                        NoraLog.error(
                            NoraLog.Area.STORAGE,
                            "Rejected archive entry escaping the Nora directory: ${entry.name}"
                        )
                        zip.closeEntry()
                        continue
                    }

                    target.parentFile?.mkdirs()
                    BufferedOutputStream(target.outputStream()).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()

                    when {
                        entry.name.startsWith("connectome/") -> connectomeFiles++
                        entry.name.startsWith("dataset/") -> datasetFiles++
                        entry.name.startsWith("feedback/") -> feedbackFiles++
                        entry.name == NoraConfig.chatFile().name -> chatRestored = true
                    }
                }
            }

            if (connectomeFiles == 0 && datasetFiles == 0) {
                return Result(
                    false,
                    "That archive didn't contain a Nora connectome or dataset. Is it the right zip?"
                )
            }

            val geometryNote = manifest?.let { m ->
                val matches = m.optInt("rings", -1) == NoraConfig.RINGS &&
                    m.optInt("wedges", -1) == NoraConfig.WEDGES &&
                    m.optInt("itChannels", -1) == NoraConfig.IT_CH &&
                    m.optInt("semanticUnits", -1) == NoraConfig.SEMANTIC_UNITS
                if (matches) "" else
                    "\n\nWarning: this backup was made with a different brain geometry. The " +
                        "dataset restored fine, but the connectome will be rejected on load " +
                        "and you'll need to retrain."
            } ?: ""

            Result(
                true,
                "Imported into ${root.absolutePath}:\n" +
                    "· $connectomeFiles connectome file(s)\n" +
                    "· $datasetFiles dataset image(s)" +
                    (if (feedbackFiles > 0) "\n· $feedbackFiles feedback file(s)" else "") +
                    (if (chatRestored) "\n· conversation history" else "") +
                    geometryNote +
                    "\n\nRevisit the Nora or Training page to load what was just restored -- " +
                    "each builds its own brain when it's opened, and won't pick this up while " +
                    "already showing."
            )
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.STORAGE, "Import failed: ${e.message}", e)
            Result(false, "Import failed: ${e.message}")
        }
    }
}
