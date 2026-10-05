package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.protein.FoldingConfig
import com.prism.launcher.protein.FoldingModel
import com.prism.launcher.protein.FoldingPrediction
import com.prism.launcher.protein.FoldingTrainer
import com.prism.launcher.protein.ProteinChemistry
import com.prism.launcher.protein.ProteinLibrary
import com.prism.launcher.protein.TrainingPlan
import com.prism.launcher.science.MeshFolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Protein folding. PHASE 97.
 *
 * ## The phase said this belongs on the desktop, and the presets are where that shows
 *
 * "The model sizes are capped at what a phone can train; a desktop can train the standard and large
 * presets." So the presets below are not the phone's. A phone's default is a 128-wide trunk with two
 * pair blocks because the triangle update is O(L³) and a phone has neither the memory nor the
 * thermal headroom; a desktop with a 4 GB heap can carry the 256-wide, four-pair-block configuration
 * that actually produces a usable contact map, and [Preset] says what each one costs rather than
 * offering a slider with no consequences attached.
 *
 * ## Nothing about the model was ported, because none of it needed to be
 *
 * `FoldTensor`, `FoldingModel`, `StructureModule`, `FoldingTrainer`, `ProteinChemistry`,
 * `ProteinLibrary` and `FoldingWire` were already in `:core` and already tested there, gradient
 * checks against finite differences included. What moved in this phase was `MeshFolding` and
 * `ProteinHost` -- and every `Context` in both of them turned out to be an argument nobody had read
 * in a long time, except one `cacheDir`.
 *
 * ## A checkpoint written on either platform loads on the other
 *
 * Which is the phase's own completion criterion. `FoldingCheckpoint` is a `DataOutputStream` of
 * floats in a fixed order with the config in the header: no platform serialisation, no `Parcelable`,
 * nothing version-dependent. The `models` directory is under `documentsDir()` for the same reason
 * downloaded models are -- a trained checkpoint is a file the user may well want to copy to another
 * machine, and burying it in `%LOCALAPPDATA%` makes that harder than it needs to be.
 */
@Composable
fun ProteinPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    val root = remember {
        File(PrismPlatform.host.documentsDir(), "Proteins").apply { mkdirs() }
    }

    var models by remember { mutableStateOf<List<ProteinLibrary.ModelEntry>>(emptyList()) }
    var datasets by remember { mutableStateOf<List<ProteinLibrary.Dataset>>(emptyList()) }
    var selected by remember { mutableStateOf<ProteinLibrary.ModelEntry?>(null) }
    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var newName by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(Preset.STANDARD) }

    var sequence by remember { mutableStateOf(UBIQUITIN) }
    var prediction by remember { mutableStateOf<FoldingPrediction?>(null) }
    var yaw by remember { mutableStateOf(0.6f) }
    var pitch by remember { mutableStateOf(0.3f) }

    var steps by remember { mutableStateOf(200) }
    var trainingProgress by remember { mutableStateOf(0f) }
    var trainingLine by remember { mutableStateOf("") }
    var losses by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            models = runCatching { ProteinLibrary.models(root) }.getOrDefault(emptyList())
            datasets = runCatching { ProteinLibrary.datasets(root) }.getOrDefault(emptyList())
        }
        if (selected == null || models.none { it.name == selected?.name }) {
            selected = models.firstOrNull()
        }
    }

    PageScaffold(
        "Proteins",
        "A folding model with its own autodiff, trained here and folded here",
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── Models ─────────────────────────────────────────────────────
            SectionHeader("models")
            Card {
                Column {
                    if (models.isEmpty()) {
                        Text(
                            "No models yet. A model is created with its weights at initialisation " +
                                "and saved immediately, so one you have named and sized exists on " +
                                "disk before it has been trained at all.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    models.forEachIndexed { index, entry ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow { selected = entry }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    fontSize = 13.sp,
                                    color = if (entry.name == selected?.name) colors.accent
                                    else colors.onSurface,
                                )
                                Text(
                                    entry.describe(),
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                )
                                if (entry.trainedOn.isNotEmpty()) {
                                    Text(
                                        "trained on " + entry.trainedOn.joinToString(", "),
                                        fontSize = 9.sp,
                                        color = Color(0xFF6E6E7A),
                                    )
                                }
                            }
                            Text(
                                "delete",
                                fontSize = 10.sp,
                                color = Color(0xFFE08080),
                                modifier = Modifier
                                    .clickableRow {
                                        ProteinLibrary.deleteModel(entry)
                                        if (selected?.name == entry.name) selected = null
                                        revision++
                                    }
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }

            SectionHeader("new model")
            Card {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("A name", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Preset.entries.forEach { candidate ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow { preset = candidate }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = if (candidate == preset) colors.accent else Color(0xFF2A2A31),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                modifier = Modifier.size(8.dp),
                            ) {}
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(candidate.label, fontSize = 12.sp)
                                Text(
                                    candidate.note,
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                    lineHeight = 14.sp,
                                )
                            }
                            Text(
                                formatCount(candidate.config.parameterCount().toLong()) + " params",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.muted,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = newName.isNotBlank() && !busy,
                        onClick = {
                            val name = newName.trim()
                            newName = ""
                            scope.launch {
                                val created = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ProteinLibrary.createModel(root, name, preset.config)
                                    }.getOrNull()
                                }
                                revision++
                                note = if (created == null) {
                                    "A model called " + name + " already exists, or the " +
                                        "configuration was rejected."
                                } else {
                                    "Created " + created.name + " — " + created.describe()
                                }
                                selected = created ?: selected
                            }
                        },
                    ) { Text("Create") }
                }
            }

            // ── Folding ────────────────────────────────────────────────────
            SectionHeader("fold a sequence")
            Card {
                Column(Modifier.padding(14.dp)) {
                    val model = selected
                    if (model == null) {
                        Text(
                            "Select or create a model first.",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    } else {
                        OutlinedTextField(
                            value = sequence,
                            onValueChange = { typed ->
                                sequence = typed.uppercase().filter { !it.isWhitespace() }
                            },
                            placeholder = { Text("A one-letter amino acid sequence", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            maxLines = 4,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            sequence.length.toString() + " residues" +
                                (if (sequence.length > model.config.maxLength) {
                                    " — longer than this model's crop (" + model.config.maxLength +
                                        "), so it will be folded in overlapping windows"
                                } else ""),
                            fontSize = 10.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = sequence.length >= 8 && !busy,
                                onClick = {
                                    busy = true
                                    note = "Folding " + sequence.length + " residues…"
                                    val target = sequence
                                    scope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching {
                                                val loaded = ProteinLibrary.loadModel(model)
                                                    ?: return@runCatching null
                                                loaded.model.fold(target)
                                            }.getOrNull()
                                        }
                                        busy = false
                                        prediction = result
                                        note = if (result == null) {
                                            "The fold failed — the checkpoint may not match its " +
                                                "recorded configuration."
                                        } else {
                                            "Folded in " + result.elapsedMillis + " ms on " +
                                                result.computedOn + ". " + result.confidenceBand()
                                        }
                                    }
                                },
                            ) { Text(if (busy) "Working…" else "Fold here") }
                            Spacer(Modifier.width(8.dp))
                            // The mesh route, which is what the peers add: throughput, not
                            // capability. Gated on the pay-later policy exactly as the phone is.
                            val meshAllowed = remember(revision) {
                                runCatching { MeshFolding.allowed(training = false) }.getOrDefault(false)
                            }
                            OutlinedButton(
                                enabled = meshAllowed && !busy && sequence.length >= 8,
                                onClick = {
                                    busy = true
                                    note = "Asking the mesh…"
                                    val target = sequence
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val loaded = ProteinLibrary.loadModel(model)
                                                    ?: return@runCatching null
                                                MeshFolding.foldDistributed(
                                                    loaded.model, listOf(target), model.step,
                                                ).predictions.firstOrNull()
                                            }.getOrNull()
                                        }
                                        busy = false
                                        prediction = result ?: prediction
                                        note = result?.let {
                                            "Folded on " + it.computedOn + " in " +
                                                it.elapsedMillis + " ms."
                                        } ?: MeshFolding.describe(training = false)
                                    }
                                },
                            ) { Text("Fold on the mesh", fontSize = 12.sp) }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                MeshFolding.describe(training = false),
                                fontSize = 10.sp,
                                color = colors.faint,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }

            prediction?.let { result ->
                SectionHeader("structure")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Mean pLDDT " + String.format("%.1f", result.confidence) + " — " +
                                result.confidenceBand(),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.3f)
                                .pointerInput(Unit) {
                                    // Drag to rotate. A protein drawn from one angle is a tangle;
                                    // being able to turn it is what makes a backbone trace readable.
                                    awaitPointerEventScope {
                                        var last: Offset? = null
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: continue
                                            if (!change.pressed) {
                                                last = null
                                                continue
                                            }
                                            val previous = last
                                            if (previous != null) {
                                                yaw += (change.position.x - previous.x) * 0.01f
                                                pitch += (change.position.y - previous.y) * 0.01f
                                            }
                                            last = change.position
                                        }
                                    }
                                },
                        ) {
                            drawBackbone(result, yaw, pitch)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Drag to rotate. Colour is pLDDT: blue is confident, orange is not — " +
                                "the same scale the EBI publishes with every AlphaFold model, " +
                                "because reading a low-confidence loop as a real conformation is " +
                                "the commonest mistake with a predicted structure.",
                            fontSize = 10.sp,
                            color = colors.faint,
                            lineHeight = 15.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("contact map", fontSize = 11.sp, color = colors.accent)
                        Spacer(Modifier.height(4.dp))
                        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            drawContacts(result)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Cβ–Cβ contact probability under 8 Å. The diagonal is trivial; the " +
                                "off-diagonal bands are secondary structure, and isolated far-off " +
                                "-diagonal density is a tertiary contact the model believes in.",
                            fontSize = 10.sp,
                            color = colors.faint,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            // ── Training ───────────────────────────────────────────────────
            SectionHeader("training")
            Card {
                Column(Modifier.padding(14.dp)) {
                    if (datasets.isEmpty()) {
                        Text(
                            "No datasets. Put a FASTA file or a directory of mmCIF/PDB structures " +
                                "under " + root.absolutePath + " and Prism will index it — a " +
                                "sequence database trains the trunk by masked language modelling, " +
                                "resolved structures train the structure module against FAPE.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                        )
                    } else {
                        datasets.forEach { dataset ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(dataset.name, fontSize = 12.sp)
                                    Text(
                                        dataset.describe(),
                                        fontSize = 10.sp,
                                        color = colors.faint,
                                    )
                                }
                            }
                        }
                    }

                    val model = selected
                    if (model != null && datasets.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(100, 200, 500, 1000, 5000).forEach { candidate ->
                                OutlinedButton(
                                    onClick = { steps = candidate },
                                    modifier = Modifier.padding(end = 5.dp),
                                ) {
                                    Text(
                                        candidate.toString(),
                                        fontSize = 11.sp,
                                        color = if (candidate == steps) colors.accent
                                        else colors.onSurface,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("steps", fontSize = 10.sp, color = colors.faint)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                losses = emptyList()
                                trainingProgress = 0f
                                trainingLine = "Loading the checkpoint…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.Default) {
                                        runTraining(
                                            root = root,
                                            entry = model,
                                            datasets = datasets,
                                            steps = steps,
                                            onStep = { done, total, line, loss ->
                                                trainingProgress = done.toFloat() / total
                                                trainingLine = line
                                                // Bounded: a five-thousand-step run would hold five thousand
                                                // floats in composition and redraw the chart from all of them.
                                                losses = (losses + loss).takeLast(240)
                                            },
                                        )
                                    }
                                    busy = false
                                    trainingLine = ""
                                    revision++
                                    note = outcome
                                }
                            },
                        ) { Text(if (busy) "Training…" else "Train " + steps + " steps") }

                        if (trainingLine.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(trainingLine, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.muted)
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { trainingProgress },
                                color = colors.accent,
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                            )
                        }

                        if (losses.size > 2) {
                            Spacer(Modifier.height(10.dp))
                            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                                drawRect(color = Color(0xFF14141A), size = size)
                                // Log scale, because a training loss falls by orders of magnitude
                                // and a linear axis shows the first twenty steps and then a flat
                                // line that is still improving.
                                val logs = losses.map {
                                    kotlin.math.ln((it.coerceAtLeast(1e-6f)).toDouble()).toFloat()
                                }
                                val lo = logs.min()
                                val hi = logs.max()
                                val span = (hi - lo).coerceAtLeast(1e-3f)
                                for (i in 0 until logs.size - 1) {
                                    val x1 = size.width * i / (logs.size - 1)
                                    val x2 = size.width * (i + 1) / (logs.size - 1)
                                    val y1 = size.height * (1f - (logs[i] - lo) / span)
                                    val y2 = size.height * (1f - (logs[i + 1] - lo) / span)
                                    drawLine(
                                        color = colors.accent,
                                        start = Offset(x1, y1),
                                        end = Offset(x2, y2),
                                        strokeWidth = 1.4f,
                                    )
                                }
                            }
                            Text(
                                "loss, log scale · " + losses.size + " steps · last " +
                                    String.format("%.4f", losses.last()),
                                fontSize = 9.sp,
                                color = colors.faint,
                            )
                        }
                    }
                }
            }

            SectionFooter(
                "The model, its autodiff, the trainer and the checkpoint format are all :core and " +
                    "all tested there — gradient checks against finite differences included. A " +
                    "checkpoint written here loads on the phone and the reverse, because the file " +
                    "is floats in a fixed order with the configuration in its header and nothing " +
                    "platform-specific anywhere in it."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The sizes offered, and what each costs.
 *
 * ## THESE ARE NOT THE PHONE'S PRESETS, WHICH IS THE POINT OF THE PHASE
 *
 * The pair stack's triangle update is O(L³) in the crop length and its memory is O(L² × dPair), so
 * every number here is a real constraint rather than a preference. A phone is capped at the first
 * row; a desktop with a 4 GB heap can carry the third, which is roughly where a contact map starts
 * being worth looking at.
 */
private enum class Preset(val label: String, val note: String, val config: FoldingConfig) {
    SMALL(
        "Small — what a phone can train",
        "128 wide, 2 pair blocks, 128 crop. Here for compatibility: a checkpoint trained on a " +
            "phone is this shape, and it loads and continues here.",
        FoldingConfig(dModel = 128, dPair = 32, sequenceBlocks = 4, pairBlocks = 2, maxLength = 128),
    ),
    STANDARD(
        "Standard — a desktop default",
        "192 wide, 3 pair blocks, 192 crop. About four times the work of the small preset per step " +
            "and comfortably inside a 4 GB heap.",
        FoldingConfig(
            dModel = 192, dPair = 48, sequenceBlocks = 6, pairBlocks = 3,
            structureIterations = 4, attentionHeads = 6, maxLength = 192, recycles = 2,
        ),
    ),
    LARGE(
        "Large — the one worth training",
        "256 wide, 4 pair blocks, 256 crop, 3 recycles. Minutes per step rather than seconds, and " +
            "the smallest configuration whose contact map is reliably informative.",
        FoldingConfig(
            dModel = 256, dPair = 64, sequenceBlocks = 8, pairBlocks = 4,
            structureIterations = 6, attentionHeads = 8, maxLength = 256, recycles = 3,
        ),
    ),
}

/**
 * Human ubiquitin, as a starting sequence.
 *
 * 76 residues, which is short enough to fold in seconds on every preset and long enough to have real
 * secondary structure -- a five-strand beta sheet and an alpha helix, both of which show up in a
 * contact map as recognisable off-diagonal bands. A sequence that was all helix would make a correct
 * model and a wrong one look alike.
 */
private const val UBIQUITIN =
    "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG"

private fun formatCount(n: Long): String = when {
    n >= 1_000_000_000 -> String.format("%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format("%.1fM", n / 1e6)
    n >= 1_000 -> String.format("%.0fk", n / 1e3)
    else -> n.toString()
}

/**
 * Runs a training session and saves the checkpoint.
 *
 * ## Checkpointed DURING the run, not only at the end
 *
 * `TrainingPlan.checkpointEvery` is 50 by default and it is honoured here: a thousand-step run on the
 * large preset is tens of minutes, and losing all of it to a closed window would make long training
 * something nobody does twice. The saved step count is what the model list shows, so a half-finished
 * run is visibly half-finished rather than looking untrained.
 *
 * ## A diverged step aborts rather than continuing
 *
 * `TrainingStep.diverged` is NaN, infinity, or a loss above 1e6. Carrying on from one writes NaN
 * into every weight on the next optimiser step, and the checkpoint on disk is then worse than
 * useless -- it loads, folds, and returns coordinates that are all NaN. Stopping keeps the last good
 * checkpoint.
 */
private fun runTraining(
    root: File,
    entry: ProteinLibrary.ModelEntry,
    datasets: List<ProteinLibrary.Dataset>,
    steps: Int,
    onStep: (done: Int, total: Int, line: String, loss: Float) -> Unit,
): String {
    val loaded = ProteinLibrary.loadModel(entry)
        ?: return "That checkpoint could not be loaded."
    val model: FoldingModel = loaded.model
    val plan = TrainingPlan(steps = steps)
    val trainer = FoldingTrainer(model, plan, seed = System.nanoTime())

    val sequences = datasets.filter { it.kind == ProteinLibrary.Kind.SEQUENCES }
    val structures = datasets.filter { it.kind == ProteinLibrary.Kind.STRUCTURES }
    if (sequences.isEmpty() && structures.isEmpty()) {
        return "No indexed dataset to train on."
    }

    val rng = java.util.Random(System.nanoTime())
    val trainedOn = (entry.trainedOn + datasets.map { it.name }).distinct()
    var lastLoss = entry.lastLoss
    var completed = 0

    for (i in 0 until steps) {
        // Interleaved on purpose: the trunk is shared, and a trunk that has seen sequences folds
        // better than one that has seen only structures. Alternating rather than running one phase
        // then the other keeps the structure module from overfitting a trunk that is still moving.
        val useStructures = structures.isNotEmpty() && (sequences.isEmpty() || i % 2 == 1)
        // The dataset is already indexed -- an index is written once when the directory is first
        // scanned, and sampleChain opens it. Re-indexing per step would re-read the whole FASTA.
        val pool = if (useStructures) structures else sequences
        val chain = pool.random().sampleChain(rng)
        if (chain == null) {
            onStep(i + 1, steps, "no usable chain in the dataset; skipping", lastLoss)
            continue
        }

        val step = if (useStructures) trainer.trainOnStructure(chain) else trainer.trainOnSequence(chain)
        completed = i + 1
        lastLoss = step.loss
        onStep(
            completed, steps,
            "step " + completed + "/" + steps + "  " + step.objective +
                "  loss " + String.format("%.4f", step.loss) +
                "  |g| " + String.format("%.3f", step.gradientNorm) +
                "  " + step.residues + " residues  " + step.elapsedMillis + " ms",
            step.loss,
        )

        if (step.diverged) {
            ProteinLibrary.saveModel(
                root, entry.name, model, entry.step + completed - 1, trainedOn, entry.lastLoss,
            )
            return "Training diverged at step " + completed + ". The last good checkpoint was " +
                "kept — continuing from a NaN would write NaN into every weight and the model " +
                "would still load, still fold, and return nothing but NaN coordinates."
        }

        if (completed % plan.checkpointEvery == 0) {
            ProteinLibrary.saveModel(
                root, entry.name, model, entry.step + completed, trainedOn, lastLoss,
            )
        }
    }

    ProteinLibrary.saveModel(root, entry.name, model, entry.step + completed, trainedOn, lastLoss)
    return "Trained " + completed + " step(s). Average loss " +
        String.format("%.4f", trainer.averageLoss()) + ", now at step " + (entry.step + completed) + "."
}

/** A chain out of a dataset, whichever index kind it has. */
private fun ProteinLibrary.Dataset.sampleChain(rng: java.util.Random): com.prism.launcher.protein.ProteinChain? =
    runCatching {
        when (kind) {
            ProteinLibrary.Kind.SEQUENCES ->
                ProteinLibrary.SequenceIndex.open(directory)?.sample(kotlin.random.Random(rng.nextLong()))
            ProteinLibrary.Kind.STRUCTURES ->
                ProteinLibrary.StructureIndex.open(directory)?.sample(kotlin.random.Random(rng.nextLong()))
        }
    }.getOrNull()

// ── Drawing ─────────────────────────────────────────────────────────────────

/**
 * The backbone, as a rotated orthographic projection.
 *
 * ## Orthographic, not perspective, and painter's-algorithm depth
 *
 * A protein is a few tens of Ångströms across and a perspective projection of something that small
 * adds foreshortening nobody wants to interpret. Depth is conveyed by SORTING the segments back to
 * front and thinning the far ones, which is what the Android view does and is the right amount of
 * machinery for a Canvas: no z-buffer, no shading model, and the trace stays readable.
 *
 * ## Coloured by pLDDT, on the EBI's own scale
 *
 * Blue for confident, orange for not. The commonest mistake with a predicted structure is reading a
 * low-confidence loop as a real conformation, and colouring the trace is the only thing that puts
 * that in front of somebody at the moment they are looking at it.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackbone(
    prediction: FoldingPrediction,
    yaw: Float,
    pitch: Float,
) {
    drawRect(color = Color(0xFF101016), size = size)
    val trace = prediction.caTrace()
    if (trace.size < 2) return

    // Centred on the centroid and scaled to fit, recomputed per frame: a rotation changes the
    // bounding box, and a fixed scale makes the molecule drift off the edge as it turns.
    val cx = trace.map { it[0] }.average().toFloat()
    val cy = trace.map { it[1] }.average().toFloat()
    val cz = trace.map { it[2] }.average().toFloat()

    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val cosPitch = cos(pitch)
    val sinPitch = sin(pitch)

    val projected = trace.map { point ->
        val x = point[0] - cx
        val y = point[1] - cy
        val z = point[2] - cz
        val x1 = x * cosYaw - z * sinYaw
        val z1 = x * sinYaw + z * cosYaw
        val y1 = y * cosPitch - z1 * sinPitch
        val z2 = y * sinPitch + z1 * cosPitch
        floatArrayOf(x1, y1, z2)
    }

    val extent = projected.flatMap { listOf(kotlin.math.abs(it[0]), kotlin.math.abs(it[1])) }
        .maxOrNull() ?: 1f
    val scale = (minOf(size.width, size.height) * 0.42f) / extent.coerceAtLeast(1f)

    fun screen(p: FloatArray) = Offset(
        size.width / 2f + p[0] * scale,
        size.height / 2f - p[1] * scale,
    )

    // Back to front, so a near segment covers a far one.
    val order = (0 until projected.size - 1).sortedBy { projected[it][2] + projected[it + 1][2] }
    val zMin = projected.minOf { it[2] }
    val zMax = projected.maxOf { it[2] }
    val zSpan = (zMax - zMin).coerceAtLeast(0.001f)

    order.forEach { i ->
        val depth = ((projected[i][2] - zMin) / zSpan).coerceIn(0f, 1f)
        val confidence = ((prediction.plddt.getOrNull(i) ?: 50f) / 100f).coerceIn(0f, 1f)
        drawLine(
            color = plddtColour(confidence).copy(alpha = 0.45f + 0.55f * depth),
            start = screen(projected[i]),
            end = screen(projected[i + 1]),
            // Far segments thinner, which is the whole depth cue in an orthographic projection.
            strokeWidth = 1.5f + 2.5f * depth,
            cap = StrokeCap.Round,
        )
    }
}

/** The EBI's pLDDT colours, which is the scale anybody who has seen an AlphaFold model knows. */
private fun plddtColour(confidence: Float): Color = when {
    confidence >= 0.9f -> Color(0xFF0053D6)
    confidence >= 0.7f -> Color(0xFF65CBF3)
    confidence >= 0.5f -> Color(0xFFFFDB13)
    else -> Color(0xFFFF7D45)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawContacts(
    prediction: FoldingPrediction,
) {
    drawRect(color = Color(0xFF101016), size = size)
    val n = prediction.length
    if (n < 2) return
    val cell = size.width / n
    for (i in 0 until n) {
        for (j in 0 until n) {
            val p = prediction.contacts.getOrNull(i * n + j) ?: continue
            if (p < 0.05f) continue
            drawRect(
                color = Color(0xFF7C6CFF).copy(alpha = p.coerceIn(0f, 1f)),
                topLeft = Offset(j * cell, i * cell),
                size = androidx.compose.ui.geometry.Size(cell.coerceAtLeast(1f), cell.coerceAtLeast(1f)),
            )
        }
    }
}
