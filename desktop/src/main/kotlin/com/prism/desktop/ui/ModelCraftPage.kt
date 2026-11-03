package com.prism.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.PrismPlatform
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.DatasetVoiceEngine
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TrainingTechniqueSmokeTest
import com.prism.launcher.modelcraft.ModelCraftExport
import com.prism.launcher.modelcraft.ModelCraftTest
import com.prism.launcher.modelcraft.ModelFiles
import com.prism.launcher.modelcraft.PrismNeuralVoice
import com.prism.launcher.modelcraft.VoicePack
import com.prism.launcher.modelcraft.VoiceTrainingMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser

/**
 * ModelCraft: the models that have been described here, then the form that describes one.
 *
 * THE FORM IS DATA. Architectures, per-kind families, the dataset each tab accepts and every training
 * field come from [ModelCraft], which is also what the phone reads. Nothing about a model's shape is
 * written twice, so a field added there appears on both platforms and the two recipes cannot drift.
 *
 * TRAINING HANDS THE RECIPE TO A RUNNER. Where one exists the run reports its stage; where it does not
 * the recipe is still written and the status says so, which is the difference between "no trainer on
 * this machine" and "the run failed".
 */
@Composable
fun ModelCraftPage() {
    var models by remember { mutableStateOf(ModelCraft.models()) }
    var open by remember { mutableStateOf<CraftedModel?>(null) }
    var creating by remember { mutableStateOf(false) }
    val state by ModelCraft.state.collectAsState()
    // The draft is not written to the store until something in it is committed, so opening the
    // creator and backing out again leaves no half-described model behind.
    var draft by remember(creating) {
        mutableStateOf(
            CraftedModel(
                id = CraftedModel.newId(),
                name = "",
                kind = ModelCraft.Kind.TEXT,
                architecture = ModelCraft.architectures.first(),
                family = "",
            ),
        )
    }

    // A finished run has rewritten the model's row on disk -- status, note, and the recipe path.
    LaunchedEffect(state.complete) {
        if (state.complete) models = ModelCraft.models()
    }

    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "ModelCraft",
            subtitle = when {
                creating -> "Describe a new model, then train it."
                open != null -> "${open!!.kind.label} model, made here."
                models.isEmpty() -> "No models yet. The plus describes the first one."
                else -> "${models.size} ${if (models.size == 1) "model" else "models"}."
            },
            actions = {
                if (creating || open != null) {
                    IconButton(onClick = { creating = false; open = null; ModelCraft.clear() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the models")
                    }
                }
            },
        ) {
            val current = open
            when {
                creating -> CreatorPanel(
                    // One draft per visit, remembered: a model created inside composition would be a
                    // new row on disk on every recomposition.
                    model = draft,
                    creating = true,
                    // The tab IS the kind of model being built, so choosing one rewrites the draft's
                    // kind (same id, same dataset folder) rather than only the form above it.
                    onKindChanged = { entry -> draft = draftAs(draft, entry) },
                    onDeleted = { creating = false; models = ModelCraft.models() },
                )
                current != null -> CreatorPanel(
                    model = current,
                    creating = false,
                    // Opening the Neural voice tab on a non-audio model rewrites the draft as an
                    // audio model, exactly as the kind tabs do while creating one. Nothing is
                    // saved until a field is committed, so backing out still leaves the record
                    // untouched.
                    onKindChanged = { entry -> open = draftAs(current, entry) },
                    onDeleted = { open = null; models = ModelCraft.models() },
                )
                else -> ModelList(models = models, onOpen = { open = it })
            }
        }

        if (!creating && open == null) {
            Surface(
                color = LocalPrismColors.current.accent,
                shape = RoundedCornerShape(if (LocalPrismColors.current.pill) 999.dp else LocalPrismColors.current.buttonRadius),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(52.dp)
                    .clickableRow { creating = true },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "New model", tint = LocalPrismColors.current.background)
                }
            }
        }
    }
}

@Composable
private fun ModelList(models: List<CraftedModel>, onOpen: (CraftedModel) -> Unit) {
    val colors = LocalPrismColors.current
    if (models.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize()) {
        items(models, key = { it.id }) { model ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableRow { onOpen(model) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(colors.cardRadius),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Memory, null, tint = colors.accent, modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOf(model.kind.label, model.architecture, model.family.ifBlank { "family not chosen" })
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        fontSize = 12.sp,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(model.status, fontSize = 11.sp, color = colors.faint)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${model.dataset.size} ${if (model.dataset.size == 1) "file" else "files"} · " +
                            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(model.updatedAt)),
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
            }
            Hairline()
        }
    }
}

/** One model's form, tabbed by what it generates. */
@Composable
private fun CreatorPanel(
    model: CraftedModel,
    creating: Boolean,
    onKindChanged: (ModelCraft.Kind) -> Unit = {},
    onDeleted: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val state by ModelCraft.state.collectAsState()

    // The tab is the kind while a model is being described. Once it exists its kind is settled, so the
    // other tabs are shown but inert rather than hidden: the shape of the form stays the same.
    val kinds = ModelCraft.Kind.entries
    var kind by remember(model.id) { mutableStateOf(model.kind) }
    var name by remember(model.id) { mutableStateOf(model.name) }
    var architecture by remember(model.id) { mutableStateOf(model.architecture) }
    var family by remember(model.id) { mutableStateOf(model.family) }
    var note by remember(model.id) { mutableStateOf("") }
    var busy by remember(model.id) { mutableStateOf(false) }
    // The voices dialog: opened by the Voices button, and by Train on a text-to-speech model
    // before the run starts. It is the same dialog either way, so a pack can be edited again long
    // after training.
    var voicesOpen by remember(model.id) { mutableStateOf(false) }
    // True when the dialog is naming the voices a PENDING RUN will package rather than editing an
    // existing pack. The extraction just re-proposed them, and re-opening the old names would hide
    // that. The Voices button reads the pack back instead.
    var voicesFresh by remember(model.id) { mutableStateOf(false) }
    // True when the dialog was opened by the Train button, so OK starts the run it was blocking.
    var voicesStartTraining by remember(model.id) { mutableStateOf(false) }
    // The two controls that are not plain recipe fields. Remembered per model, like every other
    // value in this form, so switching tabs does not lose a half-chosen window.
    var attention by remember(model.id) { mutableStateOf(ModelCraft.attention(model)) }
    var audioType by remember(model.id) { mutableStateOf(ModelCraft.audioType(model)) }
    // The voice workshop. It edits the current audio model rather than being a seventh kind, so it
    // is a peer of the kind tabs only in the tab row; the form below it is the workshop itself.
    var neuralTab by remember(model.id) { mutableStateOf(false) }
    // Which engine speaks a text-to-speech model: the one measured from this dataset, or the
    // explicitly chosen Pocket TTS path. The fallback is separate and off by default.
    var builtInEngine by remember(model.id) { mutableStateOf(ModelCraft.usesBuiltInVoiceEngine(model)) }
    var allowPocket by remember(model.id) { mutableStateOf(ModelCraft.allowsPocketFallback(model)) }
    var allowKokoro by remember(model.id) { mutableStateOf(ModelCraft.allowsKokoro(model)) }
    var useCustomVoice by remember(model.id) { mutableStateOf(ModelCraft.usesCustomVoice(model)) }
    // Off unless ticked: the miniature renderer does not speak the words, so it must never stand in
    // for a real speech model without the user asking for it.
    var miniature by remember(model.id) { mutableStateOf(ModelCraft.miniatureFallback(model)) }
    // The two creation switches. From scratch drops the base-only fields from the recipe; local
    // training decides whether this device runs one at all.
    var fromScratch by remember(model.id) { mutableStateOf(ModelCraft.fromScratch(model)) }
    var trainLocally by remember(model.id) { mutableStateOf(ModelCraft.trainsLocally(model)) }
    // And the one control about what the run PRODUCES rather than what it reads.
    var fileFormat by remember(model.id) { mutableStateOf(ModelCraft.fileFormat(model)) }
    // How long the run trains. Keyed by the kind as well, because each kind starts from its own
    // defaults and switching tabs should offer that kind's schedule rather than the last one's.
    var schedule by remember(model.id, kind) { mutableStateOf(ModelCraft.schedule(model)) }
    // The two capacity boxes, present for every kind. The context box shows the EFFECTIVE window
    // while sliding attention is on, because that is the number the KV cache holds.
    var parameterCount by remember(model.id) {
        mutableStateOf(ModelCraft.capacity(model).requestedParameters.toString())
    }
    var contextWindow by remember(model.id) {
        mutableStateOf(ModelCraft.capacity(model).effectiveContext.toString())
    }

    // The smoke test's own state. Its result is a paragraph rather than a dialog because the answer
    // -- which technique won, and why -- is what the user needs to see beside the dropdown it just
    // changed, not in a window they have to dismiss first.
    var smokeBusy by remember(model.id) { mutableStateOf(false) }
    var smokeNote by remember(model.id) { mutableStateOf("") }
    var paramNote by remember(model.id) { mutableStateOf("") }
    var choosing by remember(model.id) { mutableStateOf("") }

    val spec = ModelCraft.datasets.getValue(kind)
    val families = ModelCraft.archTypes.getValue(kind)
    val fields = ModelCraft.fields.getValue(kind)
    val running = state.running && state.modelId == model.id


    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))
        TabRow(
            selectedTabIndex = if (neuralTab) kinds.size else kinds.indexOf(kind),
            containerColor = colors.surface,
        ) {
            kinds.forEach { entry ->
                Tab(
                    selected = entry == kind && !neuralTab,
                    enabled = creating,
                    onClick = {
                        neuralTab = false
                        kind = entry
                        architecture = ModelCraft.architectures.first()
                        family = ""
                        onKindChanged(entry)
                    },
                    text = {
                        Text(
                            entry.label,
                            fontSize = 13.sp,
                            color = if (entry == kind && !neuralTab) colors.onSurface else colors.faint,
                        )
                    },
                )
            }
            Tab(
                selected = neuralTab,
                onClick = {
                    neuralTab = true
                    if (kind != ModelCraft.Kind.AUDIO) {
                        kind = ModelCraft.Kind.AUDIO
                        onKindChanged(ModelCraft.Kind.AUDIO)
                    }
                },
                text = {
                    Text(
                        "Neural voice",
                        fontSize = 13.sp,
                        color = if (neuralTab) colors.onSurface else colors.faint,
                    )
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        if (neuralTab) {
            NeuralVoiceWorkshop(model)
            return
        }

        CommittingField(value = name, modifier = Modifier.fillMaxWidth().padding(end = 60.dp)) {
            name = it.ifBlank { "Untitled ${kind.label.lowercase()} model" }
            model.name = name
            ModelCraft.Store.save(model)
        }
        Spacer(Modifier.height(12.dp))

        PrismDropdown(
            label = "Architecture",
            selection = architecture.ifBlank { ModelCraft.architectures.first() },
            options = ModelCraft.architectures,
            modifier = Modifier.padding(end = 60.dp),
        ) {
            architecture = it
            model.architecture = it
            ModelCraft.Store.save(model)
        }

        Spacer(Modifier.height(12.dp))
        PrismDropdown(
            label = "${kind.label} architecture family",
            selection = family.ifBlank { families.first().name },
            options = families.map { it.name },
            modifier = Modifier.padding(end = 60.dp),
        ) {
            family = it
            model.family = it
            ModelCraft.Store.save(model)
        }
        val familyNote = families.firstOrNull { it.name == family.ifBlank { families.first().name } }?.note
        if (familyNote != null) {
            Spacer(Modifier.height(6.dp))
            Text(familyNote, fontSize = 12.sp, color = colors.faint)
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("Dataset")
        Text(spec.help, fontSize = 12.sp, color = colors.muted)
        if (spec.frames) {
            Spacer(Modifier.height(4.dp))
            // Said plainly rather than promised: whether the videos become frames here depends on
            // whether this build has an extractor at all, and the phone build is the one that does.
            Text(
                if (ModelCraft.frameExtractor == null) {
                    "This build has no frame extractor: the dataset is written as the clips, and " +
                        "the recipe says no frames were extracted."
                } else {
                    "The videos are broken into frames before training."
                },
                fontSize = 12.sp,
                color = colors.faint,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.Folder, "Import ${spec.label.lowercase()}") {
                val picked = pickFiles()
                if (picked.isNotEmpty()) {
                    // Copying, transcribing and reading the manifest can take minutes on a speech
                    // dataset, so none of it belongs on the UI thread.
                    scope.launch {
                        busy = true
                        note = "Importing the dataset…"
                        val added = withContext(Dispatchers.IO) {
                            ModelCraft.attachDataset(
                                model,
                                picked,
                                preserveSplits = model.kind == ModelCraft.Kind.IMAGE,
                                onStage = { line ->
                                    scope.launch(Dispatchers.Main) { note = line }
                                },
                            )
                        }
                        val summary = withContext(Dispatchers.IO) { ModelCraft.prepare(model) }
                        model.dataset.clear()
                        model.dataset.addAll(
                            ModelCraft.Store.datasetDir(model.id).listFiles()?.map { it.name }.orEmpty(),
                        )
                        note = "$added ${if (added == 1) "file" else "files"} copied into the " +
                            "model's dataset. $summary"
                        busy = false
                    }
                }
            }
            if (model.dataset.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ActionButton(Icons.Filled.Refresh, "Re-sync") {
                    val picked = pickFiles("Re-sync dataset files")
                    if (picked.isNotEmpty()) {
                        scope.launch {
                            busy = true
                            note = "Re-syncing the dataset…"
                            val summary = withContext(Dispatchers.IO) {
                                ModelCraft.resyncDataset(model, picked, onStage = { line ->
                                    scope.launch(Dispatchers.Main) { note = line }
                                })
                            }
                            model.dataset.clear()
                            model.dataset.addAll(
                                ModelCraft.Store.datasetDir(model.id).listFiles()?.map { it.name }.orEmpty(),
                            )
                            note = "Dataset re-synced from the picked files. $summary"
                            busy = false
                        }
                    }
                }
            }
            if (model.dataset.size > 1) {
                Spacer(Modifier.width(10.dp))
                ActionButton(Icons.Filled.PlayArrow, "Inspect") {
                    scope.launch {
                        busy = true
                        note = "Reading the dataset…"
                        note = withContext(Dispatchers.IO) { ModelCraft.prepare(model) }
                        busy = false
                    }
                }
            }
        }
        if (model.dataset.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                // File names rather than the paths they are stored under: the folder is the model's
                // own, and six absolute paths do not fit on a row.
                "${model.dataset.size} ${if (model.dataset.size == 1) "file" else "files"}: " +
                    model.dataset.take(6).joinToString(", ") { File(it).name } +
                    if (model.dataset.size > 6) " …" else "",
                fontSize = 12.sp,
                color = colors.faint,
            )
        }
        if (model.datasetSummary.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(model.datasetSummary, fontSize = 12.sp, color = colors.muted)
        }

        // ── What the model IS, before how it trains ────────────────────────────────────────────
        //
        // Neither control is a recipe field, and both are here rather than in the field grid for
        // the same reason: a checkbox that turns two numbers on, and a radio group whose second
        // answer adds a required one, are controls a text box cannot be.
        if (kind == ModelCraft.Kind.AUDIO) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("Audio model type")
            Text(
                "Every type is trained during a run on the same audio; the type decides what the " +
                    "run produces and how it is used.",
                fontSize = 12.sp,
                color = colors.muted,
            )
            Spacer(Modifier.height(8.dp))
            ModelCraft.AudioType.entries.forEach { entry ->
                AudioTypeChoice(
                    type = entry,
                    selected = entry == audioType,
                    onSelect = {
                        audioType = entry
                        model.params[ModelCraft.KEY_AUDIO_TYPE] = entry.recipeValue
                        ModelCraft.Store.save(model)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Use the miniature voice model",
                detail = "Only for a dataset with no transcripts. It renders the acoustic model's " +
                    "predicted frames; it does not say the words. Off by default, and a trained " +
                    "text-to-speech model always wins when there is one.",
                checked = miniature,
                enabled = !running,
            ) { on ->
                miniature = on
                ModelCraft.setMiniatureFallback(model, on)
                ModelCraft.Store.save(model)
            }
            if (audioType == ModelCraft.AudioType.TEXT_TO_SPEECH) {
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Use the dataset voice engine",
                    detail = "The engine is measured from this dataset's own recordings: each " +
                        "voice's pitch and spectral profile are written beside the model, and the " +
                        "local studio voice speaks through them. The pack is written either way; " +
                        "off makes Pocket TTS the engine (its models must be installed).",
                    checked = builtInEngine,
                    enabled = !running,
                ) { on ->
                    builtInEngine = on
                    ModelCraft.setVoiceEngine(
                        model,
                        if (on) ModelCraft.VOICE_ENGINE_DATASET else ModelCraft.VOICE_ENGINE_POCKET,
                    )
                    // setVoiceEngine records Pocket as allowed when it is the chosen engine; the
                    // box beside this one has to show what the model actually says.
                    if (!on) allowPocket = true
                    ModelCraft.Store.save(model)
                }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Allow Kokoro studio voice",
                    detail = "Off by default. Kokoro is a pretrained voice; leaving it off is what " +
                        "keeps this model speaking through the voice Prism built from its dataset.",
                    checked = allowKokoro,
                    enabled = !running,
                ) { on ->
                    allowKokoro = on
                    ModelCraft.setAllowsKokoro(model, on)
                    ModelCraft.Store.save(model)
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Use a custom pretrained neural voice",
                    detail = "A Prism neural voice folder, imported and used instead of the one " +
                        "trained here. Its recordings are copied in, so the original folder can move.",
                    checked = useCustomVoice,
                    enabled = !running,
                ) { on ->
                    useCustomVoice = on
                    if (on && ModelCraft.customVoicePath(model).isBlank()) {
                        val picked = pickDirectory("Choose a Prism neural voice folder")
                        if (picked != null) ModelCraft.setCustomVoice(model, picked.absolutePath, true)
                    } else {
                        ModelCraft.setCustomVoice(model, ModelCraft.customVoicePath(model), on)
                    }
                    ModelCraft.Store.save(model)
                }
                if (useCustomVoice && ModelCraft.customVoicePath(model).isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ModelCraft.customVoicePath(model),
                        fontSize = 11.sp,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Allow Pocket TTS fallback",
                    detail = "Off by default. On, Pocket TTS covers a language the studio voice " +
                        "cannot say or a device without it; off means the failure is named instead " +
                        "of changing engines silently.",
                    checked = allowPocket,
                    enabled = !running,
                ) { on ->
                    allowPocket = on
                    ModelCraft.setAllowsPocketFallback(model, on)
                    ModelCraft.Store.save(model)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                    Text("VOICE TYPES TO BUILD", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                    Spacer(Modifier.height(5.dp))
                    CommittingField(
                        value = model.params[ModelCraft.KEY_VOICE_COUNT] ?: "4",
                        enabled = !running,
                    ) { value ->
                        val before = ModelCraft.voiceCount(model)
                        model.params[ModelCraft.KEY_VOICE_COUNT] = value.trim()
                        if (before != ModelCraft.voiceCount(model)) ModelCraft.invalidateVoicePreparation(model)
                        ModelCraft.Store.save(model)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Required for text to speech: how many different voice types this model " +
                            "must be able to speak in once it is built.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 15.sp,
                    )
                }
            }
            if (audioType == ModelCraft.AudioType.MUSIC) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "The music model learns the timbres and progressions in this dataset's clips: " +
                        "the instruments it heard, the singing voices it heard, and how they follow " +
                        "one another. Generation samples new progressions and vocodes them, so a " +
                        "prompt steers the learned palette rather than reading lyrics.",
                    fontSize = 11.sp,
                    color = colors.faint,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("INSTRUMENTS", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["instruments"] ?: "piano, strings",
                            enabled = !running,
                        ) { value ->
                            model.params["instruments"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("SINGING VOICES", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["singing_voices"] ?: "1",
                            enabled = !running,
                        ) { value ->
                            model.params["singing_voices"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("GENRE", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["genre"] ?: "",
                            enabled = !running,
                        ) { value ->
                            model.params["genre"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("TEMPO (BPM)", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["tempo_bpm"] ?: "100",
                            enabled = !running,
                        ) { value ->
                            model.params["tempo_bpm"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("KEY", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["key"] ?: "C minor",
                            enabled = !running,
                        ) { value ->
                            model.params["key"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "LENGTH (SECONDS)",
                            fontSize = 10.sp,
                            letterSpacing = 1.1.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["music_seconds"] ?: "8",
                            enabled = !running,
                        ) { value ->
                            model.params["music_seconds"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("TEMPERATURE", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["music_temperature"] ?: "0.9",
                            enabled = !running,
                        ) { value ->
                            model.params["music_temperature"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("TOP-K TIMBRES", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = model.params["music_top_k"] ?: "20",
                            enabled = !running,
                        ) { value ->
                            model.params["music_top_k"] = value.trim()
                            ModelCraft.Store.save(model)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Lower temperature and top-k repeat the safest learned timbres; higher values " +
                        "wander. The prompt names what the clip is for (\"warm analog strings with a " +
                        "soft female voice\"), and the fields above are always part of the condition.",
                    fontSize = 11.sp,
                    color = colors.faint,
                    lineHeight = 15.sp,
                )
            }
        }

        if (kind in ModelCraft.attentionKinds) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("Attention")
            ToggleRow(
                title = "Sliding window attention",
                detail = "Every layer attends to the most recent window of tokens, except every " +
                    "Nth layer, which attends to everything.",
                checked = attention.enabled,
                enabled = !running,
            ) { on ->
                val next = attention.copy(enabled = on)
                attention = next
                ModelCraft.setAttention(model, next)
                // The context box follows: sliding attention does not raise the parameter count,
                // it raises the number of tokens the same cache can hold.
                contextWindow = ModelCraft.capacity(model).effectiveContext.toString()
                ModelCraft.Store.save(model)
            }
            if (attention.enabled) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("WINDOW SIZE", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = attention.window.toString(),
                            enabled = !running,
                        ) { value ->
                            val next = attention.copy(
                                window = value.trim().toIntOrNull()?.coerceAtLeast(1)
                                    ?: ModelCraft.DEFAULT_WINDOW,
                            )
                            attention = next
                            ModelCraft.setAttention(model, next)
                            contextWindow = ModelCraft.capacity(model).effectiveContext.toString()
                            ModelCraft.Store.save(model)
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "PER NTH LAYER FULL ATTENTION",
                            fontSize = 10.sp,
                            letterSpacing = 1.1.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(5.dp))
                        CommittingField(
                            value = attention.fullAttentionEveryNthLayer.toString(),
                            enabled = !running,
                        ) { value ->
                            val next = attention.copy(
                                fullAttentionEveryNthLayer = value.trim().toIntOrNull()
                                    ?.coerceAtLeast(1) ?: ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER,
                            )
                            attention = next
                            ModelCraft.setAttention(model, next)
                            contextWindow = ModelCraft.capacity(model).effectiveContext.toString()
                            ModelCraft.Store.save(model)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (attention.effectivelyFullAttention) {
                        "N is 1, so every layer attends fully: the checkbox is on and the model is " +
                            "the one with no window at all."
                    } else {
                        "${attention.window} tokens of context on every layer, one full-attention " +
                            "layer every ${attention.fullAttentionEveryNthLayer}: the local layers " +
                            "carry the detail and the periodic one carries the sentence."
                    },
                    fontSize = 11.sp,
                    color = colors.faint,
                    lineHeight = 15.sp,
                )
            }
        }

        // ── What size it is asked to be ─────────────────────────────────────────────────────────
        //
        // Two boxes for every kind, because every recipe a full-scale trainer reads carries both.
        // They sit after the attention controls on purpose: the context number is the one sliding
        // attention raises, and a box that showed the pre-boost figure would hide the feature.
        Spacer(Modifier.height(22.dp))
        SectionHeader("Model size")
        Text(
            "The count the model is asked to build, and the token limit it is asked to hold. The " +
                "count is turned into a concrete shape and the real total is shown below; the " +
                "context box follows sliding attention, because local layers cache less per token.",
            fontSize = 12.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("PARAMETER COUNT", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(5.dp))
                CommittingField(value = parameterCount, enabled = !running) { value ->
                    parameterCount = ModelCraft.setParameterCount(model, value).toString()
                    ModelCraft.Store.save(model)
                }
            }
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("CONTEXT WINDOW (TOKENS)", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(5.dp))
                CommittingField(value = contextWindow, enabled = !running) { value ->
                    contextWindow = ModelCraft.setContextWindow(model, value).toString()
                    ModelCraft.Store.save(model)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ModelCraft.capacity(model).summary(),
            fontSize = 11.sp,
            color = colors.muted,
            lineHeight = 16.sp,
        )

        // ── How the model comes into being ──────────────────────────────────────────────────────
        //
        // Two switches, for every kind on every tab, because they are the two things a run can be
        // told to skip: the base it starts from, and this machine as the place it trains.
        Spacer(Modifier.height(22.dp))
        SectionHeader("Creation")
        ToggleRow(
            title = "Create from scratch (no base model)",
            detail = "The architecture below is created at a default shape and trained from random " +
                "weights: no base checkpoint, no fine-tune, nothing to download. The base-only fields " +
                "are greyed and are not written to the recipe — the values stay on the model, so " +
                "switching this back off restores them.",
            checked = fromScratch,
            enabled = !running,
        ) { on ->
            fromScratch = on
            ModelCraft.setFromScratch(model, on)
            ModelCraft.Store.save(model)
        }
        ToggleRow(
            title = "Train on this device",
            detail = "Off: the run writes the recipe, prepares the dataset and exports both, and " +
                "trains nothing here — which is what training on another machine wants. On: a " +
                "trainer for this architecture is looked for here, and when there is none the status " +
                "says so instead of pretending.",
            checked = trainLocally,
            enabled = !running,
        ) { on ->
            trainLocally = on
            ModelCraft.setTrainsLocally(model, on)
            ModelCraft.Store.save(model)
        }
        if (fromScratch) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Shape from scratch: " +
                    ModelCraft.scratchConfig(model).keys().asSequence().joinToString(", "),
                fontSize = 11.sp,
                color = colors.faint,
                lineHeight = 15.sp,
            )
        }

        // ── What the run must produce ───────────────────────────────────────────────────────────
        //
        // The one control on this form about output rather than input. It is here for every kind,
        // including the ones whose build is a single network, because the answer is the file the
        // user carries away and finding out afterwards is finding out too late.
        Spacer(Modifier.height(22.dp))
        SectionHeader("Model files")
        Text(
            "The container the trained weights are written in. Both hold the same weights; they " +
                "differ in how many files come out and what reads them.",
            fontSize = 12.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(8.dp))
        ModelCraft.FileFormat.entries.forEach { entry ->
            FileFormatChoice(
                format = entry,
                selected = entry == fileFormat,
                onSelect = {
                    fileFormat = entry
                    ModelCraft.setFileFormat(model, entry)
                    ModelCraft.Store.save(model)
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        val planned = ModelFiles.fileNames(
            ModelCraft.fileStem(kind),
            fileFormat,
            ModelCraft.fileComponents(kind),
        )
        Text(
            "This kind writes ${planned.size} ${if (planned.size == 1) "file" else "files"}: " +
                planned.joinToString(", ") + ".",
            fontSize = 11.sp,
            color = colors.faint,
            lineHeight = 15.sp,
        )

        // ── How long the run trains ─────────────────────────────────────────────────────────────
        //
        // Two boxes, every kind, one section: passes and examples per pass. They are here rather
        // than among the twenty-odd training fields because they are the two numbers a user changes
        // on every run, and because the local trainers read them as their loop bounds.
        Spacer(Modifier.height(22.dp))
        SectionHeader("Training schedule")
        Text(
            "How long the run trains. One step is one example here; steps per epoch caps how many " +
                "examples a pass takes, and 0 means every example in the dataset.",
            fontSize = 12.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("STEPS PER EPOCH", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(5.dp))
                ScheduleField(
                    value = schedule.stepsPerEpoch,
                    enabled = !running,
                ) { value ->
                    val next = schedule.copy(
                        stepsPerEpoch = value.coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH),
                    )
                    schedule = next
                    ModelCraft.setSchedule(model, next)
                    ModelCraft.Store.save(model)
                }
            }
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("EPOCHS", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(5.dp))
                ScheduleField(
                    value = schedule.epochs,
                    enabled = !running,
                ) { value ->
                    val next = schedule.copy(
                        epochs = value.coerceIn(1, ModelCraft.MAX_EPOCHS),
                    )
                    schedule = next
                    ModelCraft.setSchedule(model, next)
                    ModelCraft.Store.save(model)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (schedule.plannedSteps > 0) {
                "${schedule.epochs} epoch(s) of ${schedule.stepsPerEpoch} step(s): exactly " +
                    "${schedule.plannedSteps} steps. One step is one example, so the run takes all of them."
            } else {
                "${schedule.epochs} epoch(s), each a full pass over the training split: steps per " +
                    "epoch is 0, so every example is trained on that many times."
            },
            fontSize = 11.sp,
            color = colors.faint,
            lineHeight = 15.sp,
        )

        Spacer(Modifier.height(22.dp))
        SectionHeader("Training setup")
        Text(
            "${fields.size} inputs, the shape a trainer for this kind expects." +
                if (fromScratch) {
                    " The base-only ones are greyed: a from-scratch run has no base to load them into."
                } else {
                    ""
                },
            fontSize = 12.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))

        // Two columns, so thirty fields are a form rather than a corridor. Committing on blur means a
        // half-typed value never reaches the recipe.
        val baseFields = ModelCraft.baseFields.getValue(kind)
        fields.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                pair.forEach { field ->
                    val used = !(fromScratch && field.key in baseFields)
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            field.label.uppercase() + if (used) "" else " — not used",
                            fontSize = 10.sp,
                            letterSpacing = 1.1.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(5.dp))
                        // A field that declares choices is a dropdown, not a text box. Both
                        // platforms read the same list from ModelCraft, so a recipe key cannot
                        // accept one set of values here and a different set on the phone.
                        if (field.choices.isNotEmpty()) {
                            PrismDropdown(
                                label = "",
                                selection = model.params[field.key] ?: field.default,
                                options = field.choices,
                                enabled = !running && used,
                                modifier = Modifier.fillMaxWidth(),
                            ) { value ->
                                model.params[field.key] = value
                                ModelCraft.Store.save(model)
                            }
                        } else {
                            CommittingField(
                                value = model.params[field.key] ?: field.default,
                                enabled = !running && used,
                            ) { value ->
                                model.params[field.key] = value
                                ModelCraft.Store.save(model)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f).padding(end = 12.dp))
            }
        }

        // ── The smoke test ────────────────────────────────────────────────────────────────────
        //
        // Beside the dropdown it sets, and above the Train button, because that is the order the
        // decision is made in: pick a technique, or let this pick one, then run.
        Spacer(Modifier.height(14.dp))
        Text(
            "Not sure which technique to pick? The smoke test reads the loaded dataset and times " +
                "this machine, ranks all nine, and adopts the fastest one that keeps the most quality.",
            fontSize = 12.sp,
            color = colors.muted,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                Icons.Filled.PlayArrow,
                if (smokeBusy) "Working…" else "Run smoke test",
                enabled = !smokeBusy && !running && model.dataset.isNotEmpty(),
            ) { focus.clearFocus(); choosing = "smoke" }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                Icons.Filled.PlayArrow,
                if (smokeBusy) "Working…" else "Auto-determine parameters",
                enabled = !smokeBusy && !running,
            ) { focus.clearFocus(); choosing = "parameters" }
        }
        if (choosing.isNotEmpty()) {
            val isSmoke = choosing == "smoke"
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { choosing = "" },
                title = { Text(if (isSmoke) "Run smoke test" else "Auto-determine parameters") },
                text = { Text("Quick takes seconds; the lengthy test runs 50 epochs.", fontSize = 13.sp) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val kind = choosing
                        choosing = ""
                        scope.launch {
                            smokeBusy = true
                            runModelTest(model, kind, 1)?.let { if (kind == "smoke") smokeNote = it else paramNote = it }
                            smokeBusy = false
                        }
                    }) { Text("Quick test") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val kind = choosing
                        choosing = ""
                        scope.launch {
                            smokeBusy = true
                            runModelTest(model, kind, 50)?.let { if (kind == "smoke") smokeNote = it else paramNote = it }
                            smokeBusy = false
                        }
                    }) { Text("Lengthy test (50 epochs)") }
                },
            )
        }
        if (paramNote.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(paramNote, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
        }
        if (model.kind == ModelCraft.Kind.VIDEO) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = ModelCraft.wholeVideo(model),
                    onCheckedChange = {
                        ModelCraft.setWholeVideo(model, it)
                        ModelCraft.Store.save(model)
                    },
                )
                Text("Train on the whole video", fontSize = 13.sp, color = colors.onSurface)
            }
            Text(
                "On: every frame of a clip is used before the next clip.",
                fontSize = 11.sp,
                color = colors.muted,
            )
        }
        if (smokeNote.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(smokeNote, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
        }

        // What still has to be filled in, said before the button rather than after it is pressed:
        // a text-to-speech model with no voice count is one nobody can ask for a voice.
        ModelCraft.validationError(model)?.let { problem ->
            Spacer(Modifier.height(12.dp))
            Text(problem, fontSize = 12.sp, color = colors.destructive, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(
                Icons.Filled.PlayArrow,
                if (running) "Stop" else "Train",
                enabled = running || model.dataset.isNotEmpty(),
            ) {
                if (running) {
                    ModelCraft.cancel()
                } else {
                    // A CommittingField writes on blur, and a click on a button does not necessarily
                    // move focus. Without this, typing 100 steps and pressing Train runs the OLD
                    // schedule -- the bug that made a 100-step recipe train nine examples and stop.
                    focus.clearFocus()
                    note = ""
                    if (ModelCraft.wantsVoiceReview(model)) {
                        // THE VOICES COME BEFORE THE RUN. The extraction that used to happen inside
                        // training now happens here, then the dialog opens; its OK builds the engine
                        // and starts the run, and Cancel leaves the model untrained.
                        scope.launch {
                            busy = true
                            note = "Listening to the dataset before the voices dialog…"
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    ModelCraft.prepareVoices(model) { line, _ ->
                                        scope.launch(Dispatchers.Main) { note = line }
                                    }
                                }
                            }
                            busy = false
                            result.onSuccess { voices ->
                                if (voices.isEmpty()) {
                                    note = "No voice recordings were found, so there is nothing " +
                                        "to build an engine from. Import audio with transcripts first."
                                } else {
                                    voicesFresh = true
                                    voicesStartTraining = true
                                    voicesOpen = true
                                }
                            }.onFailure {
                                note = it.message ?: "The voice data could not be prepared."
                            }
                        }
                    } else {
                        ModelCraft.train(model)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            // The export is a button as well as something every run does: a model that is already
            // trained, or one that has never run, can be taken somewhere else in one click.
            ActionButton(Icons.Filled.Folder, "Export + script", enabled = !running) {
                scope.launch {
                    busy = true
                    val exported = withContext(Dispatchers.IO) { ModelCraftExport.export(model) }
                    busy = false
                    note = "Exported to ${exported.absolutePath} — ${ModelCraftExport.scriptName(model)} " +
                        "rebuilds this model on any machine with Python."
                }
            }
            Spacer(Modifier.width(10.dp))
            if (model.kind == ModelCraft.Kind.AUDIO) {
                ActionButton(Icons.Filled.Memory, "Voices", enabled = !running) {
                    voicesFresh = false
                    voicesStartTraining = false
                    voicesOpen = true
                }
                Spacer(Modifier.width(10.dp))
            }
            ActionButton(Icons.Filled.Delete, "Delete model", destructive = true) {
                ModelCraft.delete(model)
                onDeleted()
            }
        }

        if (running || (state.modelId == model.id && state.complete)) {
            Spacer(Modifier.height(16.dp))
            Text(state.stage, fontSize = 12.sp, color = colors.muted)
            if (state.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(state.detail, fontSize = 12.sp, color = colors.accent)
            }
            Spacer(Modifier.height(8.dp))
            PrismProgressBar(state.progress)
        }
        if (state.modelId == model.id) {
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = colors.destructive)
            }
            if (state.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(state.note, fontSize = 12.sp, color = colors.muted)
            }
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text("Working…", fontSize = 12.sp, color = colors.faint)
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(note, fontSize = 12.sp, color = colors.muted)
        }
        if (model.recipePath.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Recipe: ${model.recipePath}", fontSize = 11.sp, color = colors.faint)
        }

        ModelTestSection(model, running)
        Spacer(Modifier.height(26.dp))
    }

    if (voicesOpen) {
        VoicePackDialog(
            model = model,
            propose = voicesFresh,
            startTraining = voicesStartTraining,
            onDismiss = {
                voicesOpen = false
                voicesStartTraining = false
            },
            onPackaged = { summary ->
                note = summary
                if (voicesStartTraining) {
                    voicesStartTraining = false
                    ModelCraft.train(model)
                }
            },
        )
    }
}

/** One editable row of the voices dialog: the three answers the user can correct. */
private data class EditableVoice(
    val name: String,
    val gender: String,
    val language: String,
    val source: VoicePack.Voice,
)

/**
 * The voices a speech model ships with, as a table the user edits before it is written.
 *
 * Three columns -- speaker, gender, language -- because those are the three things a recording does
 * not say about itself. Tapping a row opens the three boxes; OK writes the WAVs and the table into
 * `voices/` beside the model and points the model at them, and the same thing happens after every
 * text-to-speech run. Cancel leaves the last pack (or the proposal) untouched.
 *
 * The list comes from [VoicePack.packaged] when a pack exists, so opening this again edits what was
 * written rather than starting over from the dataset.
 */
@Composable
private fun VoicePackDialog(
    model: CraftedModel,
    /** True to propose this run's voices; false to edit the pack as it was last written. */
    propose: Boolean = false,
    /** True when OK should start the run the dialog was opened for. */
    startTraining: Boolean = false,
    onDismiss: () -> Unit,
    onPackaged: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var rows by remember(model.id, propose) {
        mutableStateOf(
            (if (propose) VoicePack.proposal(model) else VoicePack.packaged(model).ifEmpty { VoicePack.proposal(model) })
                .map { voice -> EditableVoice(voice.name, voice.gender, voice.language, voice) },
        )
    }
    var expanded by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    val requested = ModelCraft.voiceCount(model) ?: 1

    fun update(index: Int, change: EditableVoice.() -> EditableVoice) {
        rows = rows.toMutableList().also { list ->
            list[index] = list[index].change()
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
            // A table needs room for three columns; the minimum is what keeps the fields from
            // collapsing into each other when the dialog is sized by its text.
            modifier = Modifier.widthIn(min = 520.dp, max = 640.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Voices", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "The $requested voice(s) this model should be able to speak in. Each row is a " +
                        "recording from the dataset: tap it to change the speaker's name, gender or " +
                        "language. OK writes them into voices/ beside the model, measures them into " +
                        "the dataset voice engine" + (if (startTraining) ", and starts training." else ".") +
                        " The same WAVs and table work in any other speech tool.",
                    fontSize = 12.sp,
                    color = colors.muted,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))

                when {
                    rows.isEmpty() -> Text(
                        "This model has no recordings to package yet. Import audio with " +
                            "transcripts, then train it — or tick \"Train on this device\" and run it.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        lineHeight = 17.sp,
                    )
                    else -> {
                        VoiceTableHeader(
                            Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
                        )
                        Hairline()
                        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                            rows.forEachIndexed { index, row ->
                                VoiceRow(
                                    row = row,
                                    open = expanded == index,
                                    onToggle = { expanded = if (expanded == index) -1 else index },
                                    onChange = { change -> update(index, change) },
                                )
                                if (index != rows.lastIndex) Hairline()
                            }
                        }
                        if (rows.size < requested) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The dataset holds ${rows.size} distinct recording(s), so ${
                                    if (rows.size == 1) "one voice" else "${rows.size} voices"
                                } can be built rather than $requested. Add more speakers to build the rest.",
                                fontSize = 11.sp,
                                color = colors.faint,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }

                if (failure.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(failure, fontSize = 12.sp, color = colors.destructive, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(Icons.Filled.Folder, "Cancel", enabled = !busy) { onDismiss() }
                    Spacer(Modifier.weight(1f))
                    ActionButton(
                        Icons.Filled.PlayArrow,
                        if (busy) {
                            "Writing…"
                        } else if (startTraining) {
                            "OK — build engine and train"
                        } else {
                            "OK — rebuild engine"
                        },
                        enabled = !busy && rows.isNotEmpty(),
                    ) {
                        busy = true
                        failure = ""
                        val voices = rows.mapNotNull { row ->
                            val source = row.source
                            if (row.name.isBlank()) {
                                failure = "Every voice needs a speaker name."
                                return@mapNotNull null
                            }
                            source.copy(
                                name = row.name.trim(),
                                gender = row.gender.trim(),
                                language = row.language.trim(),
                            )
                        }
                        if (voices.size != rows.size) {
                            busy = false
                            return@ActionButton
                        }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { VoicePack.write(model, voices) }
                            }
                            busy = false
                            result.onSuccess { packaged ->
                                onPackaged(packaged.summary)
                                onDismiss()
                            }.onFailure {
                                failure = it.message ?: "The voices could not be written."
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Where they go: ${VoicePack.directory(model).absolutePath}",
                    fontSize = 10.sp,
                    color = colors.faint,
                )
            }
        }
    }
}

/** The three column names, once above the list. */
@Composable
private fun VoiceTableHeader(modifier: Modifier = Modifier) {
    val colors = LocalPrismColors.current
    Row(modifier.fillMaxWidth()) {
        listOf("SPEAKER" to 0.46f, "GENDER" to 0.24f, "LANGUAGE" to 0.18f, "" to 0.12f).forEach { (label, weight) ->
            Text(
                label,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
                color = colors.faint,
                modifier = Modifier.weight(weight),
            )
        }
    }
}

/** One row: three answers, and the source recording's name underneath when it is open. */
@Composable
private fun VoiceRow(
    row: EditableVoice,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: (EditableVoice.() -> EditableVoice) -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(Modifier.fillMaxWidth().clickableRow(onToggle).padding(vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name.ifBlank { "Unnamed voice" },
                fontSize = 13.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.46f),
            )
            Text(
                row.gender.ifBlank { "—" },
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.weight(0.24f),
            )
            Text(
                row.language.ifBlank { "—" },
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.weight(0.18f),
            )
            Text(
                if (open) "Close" else "Edit",
                fontSize = 11.sp,
                color = colors.accent,
                modifier = Modifier.weight(0.12f),
            )
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                VoiceField(
                    label = "SPEAKER NAME",
                    value = row.name,
                    modifier = Modifier.weight(0.46f).padding(end = 8.dp),
                ) { value -> onChange { copy(name = value) } }
                VoiceField(
                    label = "GENDER",
                    value = row.gender,
                    modifier = Modifier.weight(0.24f).padding(end = 8.dp),
                ) { value -> onChange { copy(gender = value) } }
                VoiceField(
                    label = "LANGUAGE",
                    value = row.language,
                    modifier = Modifier.weight(0.18f),
                ) { value -> onChange { copy(language = value) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "From ${row.source.clip.ifBlank { "the dataset" }}" +
                    (if (row.source.clips > 1) " · ${row.source.clips} clips in this voice" else "") +
                    " · gender is female, male or neutral; language is a code such as en or es.",
                fontSize = 10.sp,
                color = colors.faint,
                lineHeight = 14.sp,
            )
        }
    }
}

/** A labelled box inside an expanded voice row; edits commit per keystroke. */
@Composable
private fun VoiceField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(modifier) {
        Text(label, fontSize = 9.sp, letterSpacing = 1.sp, color = colors.faint)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = colors.surfaceRaised,
            shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.fieldRadius),
            border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * A number box that commits AS IT IS TYPED, not on blur.
 *
 * The two schedule boxes are read by the Train button in the same breath as the click, and a click on
 * a button does not necessarily move focus away from a field. A committing-on-blur box therefore ran
 * the previous schedule -- which is how a run the user set to 100 steps trained its nine examples and
 * stopped. A schedule is one small number, so saving it per digit costs nothing.
 */
@Composable
private fun ScheduleField(value: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    val colors = LocalPrismColors.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    Surface(
        color = if (enabled) colors.surfaceRaised else colors.surface,
        shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.fieldRadius),
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(7)
                text = digits
                digits.toIntOrNull()?.let(onValue)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) colors.onSurface else colors.faint,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            decorationBox = { inner -> inner() },
        )
    }
}

/**
 * Trying the model that was just trained.
 *
 * The result comes first and the box last, because that is the order the eye reads a test: here is
 * what it made, now change the input and make another. Both are disabled until the model is actually
 * trained, and the sentence above the box says what a seed does for kinds that do not read text.
 */
/**
 * The voice workshop, shared by the tab and by anything that needs to rebuild a model's voice.
 *
 * It edits the current audio model: the dataset is the same folder the acoustic run reads, the
 * parameters build the Prism neural voice, and the switches decide which optional pretrained voice
 * may stand in. Nothing here is a separate model kind; the artifact is written beside the dataset
 * and picked up by [DatasetVoiceEngine] immediately.
 */
@Composable
private fun NeuralVoiceWorkshop(model: CraftedModel) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var settings by remember(model.id) { mutableStateOf(ModelCraft.neuralVoiceSettings(model)) }
    var note by remember(model.id) { mutableStateOf("") }
    var busy by remember(model.id) { mutableStateOf(false) }
    var trained by remember(model.id) { mutableStateOf<PrismNeuralVoice.Model?>(null) }
    var selected by remember(model.id) { mutableStateOf<String?>(null) }
    var sentence by remember(model.id) { mutableStateOf("") }
    val directory = ModelCraft.Store.datasetDir(model.id)

    LaunchedEffect(model.id) {
        trained = withContext(Dispatchers.IO) { PrismNeuralVoice.load(directory) }
    }

    fun commit(next: PrismNeuralVoice.Settings) {
        settings = next.cleaned()
        ModelCraft.setNeuralVoiceSettings(model, settings)
        ModelCraft.Store.save(model)
    }

    Column(Modifier.fillMaxWidth().padding(end = 12.dp)) {
        SectionHeader("Neural voice dataset")
        Text(
            "The same recordings the audio model reads. Each one is aligned to its transcript " +
                "and becomes a speaker profile plus the units that speaker's voice is built from. " +
                "No pretrained voice is involved in this path.",
            fontSize = 12.sp,
            color = colors.muted,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.Folder, "Import folder", enabled = !busy) {
                val picked = pickFiles("Import a neural voice dataset")
                if (picked.isNotEmpty()) {
                    scope.launch {
                        busy = true
                        note = "Importing the dataset…"
                        val added = withContext(Dispatchers.IO) {
                            ModelCraft.attachDataset(model, picked, onStage = { line ->
                                scope.launch(Dispatchers.Main) { note = line }
                            })
                        }
                        val summary = withContext(Dispatchers.IO) { ModelCraft.prepare(model) }
                        model.dataset.clear()
                        model.dataset.addAll(
                            ModelCraft.Store.datasetDir(model.id).listFiles()?.map { it.name }.orEmpty(),
                        )
                        note = "$added ${if (added == 1) "file" else "files"} copied. $summary"
                        busy = false
                    }
                }
            }
            if (model.dataset.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ActionButton(Icons.Filled.Refresh, "Re-sync", enabled = !busy) {
                    val picked = pickFiles("Re-sync neural voice files")
                    if (picked.isNotEmpty()) {
                        scope.launch {
                            busy = true
                            note = "Re-syncing the dataset…"
                            val summary = withContext(Dispatchers.IO) {
                                ModelCraft.resyncDataset(model, picked, onStage = { line ->
                                    scope.launch(Dispatchers.Main) { note = line }
                                })
                            }
                            model.dataset.clear()
                            model.dataset.addAll(
                                ModelCraft.Store.datasetDir(model.id).listFiles()?.map { it.name }.orEmpty(),
                            )
                            note = "Re-synced. $summary"
                            busy = false
                        }
                    }
                }
            }
            if (model.dataset.isNotEmpty() && !busy) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "${model.dataset.size} ${if (model.dataset.size == 1) "file" else "files"}",
                    fontSize = 12.sp,
                    color = colors.faint,
                )
            }
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(note, fontSize = 12.sp, color = colors.muted, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("Model parameters")
        Text(
            "The network width and embedding decide how much phonetic context the prosody model " +
                "can learn; the unit bounds decide what counts as one sound; pitch, tempo, breath " +
                "and warmth render the same trained units without retraining.",
            fontSize = 12.sp,
            color = colors.muted,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuralField("LAYER WIDTH", settings.hidden.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(hidden = it.trim().toIntOrNull() ?: settings.hidden))
            }
            NeuralField("VOICE EMBEDDING", settings.embedding.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(embedding = it.trim().toIntOrNull() ?: settings.embedding))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            NeuralField("TRAINING EPOCHS", settings.epochs.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(epochs = it.trim().toIntOrNull() ?: settings.epochs))
            }
            NeuralField("LEARNING RATE", "%.4f".format(settings.learningRate), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(learningRate = it.trim().toDoubleOrNull() ?: settings.learningRate))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            NeuralField("JOIN CROSSFADE (MS)", settings.crossfadeMs.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(crossfadeMs = it.trim().toIntOrNull() ?: settings.crossfadeMs))
            }
            NeuralField("UNITS PER PHONEME", settings.maxUnitsPerSymbol.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(maxUnitsPerSymbol = it.trim().toIntOrNull() ?: settings.maxUnitsPerSymbol))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            NeuralField("MIN UNIT (MS)", settings.minUnitMs.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(minUnitMs = it.trim().toIntOrNull() ?: settings.minUnitMs))
            }
            NeuralField("MAX UNIT (MS)", settings.maxUnitMs.toString(), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(maxUnitMs = it.trim().toIntOrNull() ?: settings.maxUnitMs))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            NeuralField("PITCH RANGE", "%.2f".format(settings.pitchRange), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(pitchRange = it.trim().toDoubleOrNull() ?: settings.pitchRange))
            }
            NeuralField("TEMPO", "%.2f".format(settings.tempo), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(tempo = it.trim().toDoubleOrNull() ?: settings.tempo))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            NeuralField("BREATH", "%.2f".format(settings.breath), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(breath = it.trim().toDoubleOrNull() ?: settings.breath))
            }
            NeuralField("WARMTH", "%.2f".format(settings.warmth), !busy, Modifier.weight(1f).padding(end = 10.dp)) {
                commit(settings.copy(warmth = it.trim().toDoubleOrNull() ?: settings.warmth))
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("Voice engine")
        ToggleRow(
            title = "Allow Kokoro studio voice",
            detail = "Off by default. Kokoro is a pretrained voice; leaving it off is what keeps " +
                "the model speaking through the voice Prism built from this dataset.",
            checked = ModelCraft.allowsKokoro(model),
            enabled = !busy,
        ) { on ->
            ModelCraft.setAllowsKokoro(model, on)
            ModelCraft.Store.save(model)
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Use a custom pretrained neural voice",
            detail = "A Prism neural voice folder, copied into this model and used instead of " +
                "the one trained here.",
            checked = ModelCraft.usesCustomVoice(model),
            enabled = !busy,
        ) { on ->
            if (on && ModelCraft.customVoicePath(model).isBlank()) {
                val picked = pickDirectory("Choose a Prism neural voice folder")
                if (picked != null) {
                    scope.launch {
                        busy = true
                        note = "Importing the neural voice…"
                        val destination = File(ModelCraft.Store.modelDir(model.id), "custom_voice")
                        val imported = withContext(Dispatchers.IO) {
                            runCatching {
                                picked.copyRecursively(destination, overwrite = true)
                                PrismNeuralVoice.load(destination)
                            }.getOrNull()
                        }
                        if (imported == null) {
                            note = "That folder has no readable ${PrismNeuralVoice.FILE} and " +
                                "${PrismNeuralVoice.WEIGHTS_FILE}."
                        } else {
                            ModelCraft.setCustomVoice(model, destination.absolutePath, true)
                            ModelCraft.Store.save(model)
                            note = "Imported ${PrismNeuralVoice.summary(imported)}"
                        }
                        busy = false
                    }
                }
            } else {
                ModelCraft.setCustomVoice(model, ModelCraft.customVoicePath(model), on)
                ModelCraft.Store.save(model)
            }
        }
        if (ModelCraft.customVoicePath(model).isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                ModelCraft.customVoicePath(model),
                fontSize = 11.sp,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Allow Pocket TTS fallback",
            detail = "Last resort, and off by default. Pocket TTS is another pretrained engine, " +
                "not part of the Prism voice.",
            checked = ModelCraft.allowsPocketFallback(model),
            enabled = !busy,
        ) { on ->
            ModelCraft.setAllowsPocketFallback(model, on)
            ModelCraft.Store.save(model)
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("Build the voice")
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.PlayArrow, "Build neural voice", enabled = !busy) {
                scope.launch {
                    busy = true
                    note = "Aligning recordings and training the prosody model…"
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            PrismNeuralVoice.train(directory, settings) { line, fraction ->
                                scope.launch(Dispatchers.Main) {
                                    note = "$line · ${(fraction * 100).toInt()}%"
                                }
                            }
                        }.also {
                            // Pick the new artifact up in the engine immediately, so Speak and
                            // every Prism caller use it without a restart.
                            runCatching { DatasetVoiceEngine.rebuild(model) }
                        }
                    }
                    busy = false
                    trained = result.getOrNull()
                    note = if (trained == null) {
                        result.exceptionOrNull()?.message
                            ?: "No voice could be built. Import audio with transcripts first."
                    } else {
                        PrismNeuralVoice.summary(trained!!) + " · trained in ${trained!!.steps} step(s)"
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(if (busy) "Working…" else "Units are cut from the recordings; no external model is used.",
                fontSize = 11.sp, color = colors.faint)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            note.ifBlank { trained?.let { PrismNeuralVoice.summary(it) } ?: "No neural voice has been built yet." },
            fontSize = 12.sp,
            color = colors.muted,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(22.dp))
        SectionHeader("Test the voice")
        if (trained != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                trained!!.voices.forEach { voice ->
                    val selectedNow = voice.name == (selected ?: trained!!.voices.first().name)
                    Surface(
                        color = if (selectedNow) colors.accent else colors.surfaceRaised,
                        shape = RoundedCornerShape(999.dp),
                        border = if (!selectedNow && colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
                        modifier = Modifier.clickableRow {
                            selected = voice.name
                            if (sentence.isNotBlank() && !busy) {
                                scope.launch {
                                    val samples = withContext(Dispatchers.IO) {
                                        runCatching {
                                            trained!!.synthesize(
                                                sentence,
                                                language = voice.language,
                                                gender = voice.gender,
                                                voiceName = voice.name,
                                            )
                                        }.getOrNull()
                                    }
                                    if (samples != null) PrismPlatform.audio.play(samples, PrismNeuralVoice.RATE)
                                }
                            }
                        },
                    ) {
                        Text(
                            voice.name + " · " + voice.gender,
                            fontSize = 11.sp,
                            color = if (selectedNow) colors.background else colors.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text("SENTENCE", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(5.dp))
                CommittingField(value = sentence) { sentence = it }
            }
            ActionButton(Icons.Filled.PlayArrow, "Speak", enabled = trained != null && sentence.isNotBlank() && !busy) {
                val voice = trained ?: return@ActionButton
                val chosen = voice.voices.firstOrNull { it.name == selected } ?: voice.voices.firstOrNull()
                scope.launch {
                    busy = true
                    val samples = withContext(Dispatchers.IO) {
                        runCatching {
                            voice.synthesize(
                                sentence,
                                language = chosen?.language ?: "en",
                                gender = chosen?.gender.orEmpty(),
                                voiceName = chosen?.name,
                            )
                        }.getOrNull()
                    }
                    busy = false
                    if (samples == null) {
                        note = "The voice could not speak that sentence."
                    } else {
                        PrismPlatform.audio.play(samples, PrismNeuralVoice.RATE)
                        note = "Played ${samples.size / PrismNeuralVoice.RATE.toFloat()} s."
                    }
                }
            }
        }
    }
}

@Composable
private fun NeuralField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(modifier) {
        Text(label, fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
        Spacer(Modifier.height(5.dp))
        CommittingField(value = value, enabled = enabled, onCommit = onValue)
    }
}

@Composable
private fun ModelTestSection(model: CraftedModel, running: Boolean) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    // Read the run state here as well as in the parent: the parent only passes a boolean, so a
    // progress update that keeps `running` true would otherwise not recompose the graph.
    val state by ModelCraft.state.collectAsState()
    val graphing = running && state.modelId == model.id &&
        model.kind == ModelCraft.Kind.AUDIO && !ModelCraft.isMusic(model)
    var input by remember(model.id) { mutableStateOf("") }
    var output by remember(model.id) { mutableStateOf<ModelCraftTest.Output?>(null) }
    var failure by remember(model.id) { mutableStateOf("") }
    var busy by remember(model.id) { mutableStateOf(false) }
    val ready = ModelCraftTest.ready(model)
    // The voices to try, read from the engine the Voices dialog built: its rows are what the
    // per-voice speaking path can resolve. A cloning model speaks in its reference recording and
    // a Pocket-engine model has no dataset engine, so neither gets a strip.
    val voices = remember(model.id, model.status) {
        if (
            model.kind == ModelCraft.Kind.AUDIO &&
            ModelCraft.audioType(model) != ModelCraft.AudioType.VOICE_CLONING &&
            ModelCraft.usesBuiltInVoiceEngine(model)
        ) {
            runCatching { DatasetVoiceEngine.load(model) }.getOrNull()?.voices.orEmpty()
        } else {
            emptyList()
        }
    }
    var voice by remember(model.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(voices) {
        if (voice != null && voices.none { it.name == voice || it.file == voice }) voice = null
    }

    // A model that is being retrained has no result worth showing; the old weights are gone from
    // under it the moment the run starts.
    LaunchedEffect(model.status, running) {
        if (!ready || running) {
            output = null
            failure = ""
        }
    }

    Spacer(Modifier.height(26.dp))
    SectionHeader("Test the model")
    Text(
        when {
            graphing -> "Live voice features from the training clips: pitch contour, rhythm, " +
                "stress, pauses, micro-variation, jitter and shimmer, breathiness, vocal fry, " +
                "expression, source/filter, timbre, identity, duration, coarticulation, " +
                "temporal detail and phonation types."
            ready -> ModelCraftTest.promptHint(model.kind)
            else -> "Train the model first. Once it has weights, this is where you try them " +
                "without leaving the form."
        },
        fontSize = 12.sp,
        color = colors.muted,
        lineHeight = 17.sp,
    )
    Spacer(Modifier.height(10.dp))

    // Directly above the waveform: the voices this model can speak in. Selecting one makes the
    // next Test use it; a sentence already tested is spoken again so the difference is immediate.
    if (voices.isNotEmpty()) {
        Text(
            "VOICE",
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            color = colors.faint,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            voices.forEach { candidate ->
                val selected = candidate.name == voice
                Surface(
                    color = if (selected) colors.accent else colors.surfaceRaised,
                    shape = RoundedCornerShape(999.dp),
                    border = if (!selected && colors.border > 0) {
                        BorderStroke(colors.border.dp, colors.divider)
                    } else {
                        null
                    },
                    modifier = Modifier.clickableRow {
                        voice = candidate.name
                        if (input.isNotBlank() && ready && !busy && !running) {
                            failure = ""
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ModelCraftTest.run(model, input, candidate.name) }
                                }
                                busy = false
                                result.onSuccess { output = it }
                                    .onFailure { failure = it.message ?: "The test could not run." }
                            }
                        }
                    },
                ) {
                    Text(
                        buildString {
                            append(candidate.name)
                            if (candidate.gender.isNotBlank()) append(" · ${candidate.gender}")
                            if (
                                candidate.language.isNotBlank() &&
                                !candidate.language.startsWith("en", true)
                            ) {
                                append(" · ${candidate.language}")
                            }
                        },
                        fontSize = 11.sp,
                        color = if (selected) colors.background else colors.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(if (colors.pill) 18.dp else colors.cardRadius),
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier.fillMaxWidth().height(if (graphing) 340.dp else 230.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            if (graphing) {
                VoiceTrainingGraph(VoiceTrainingMonitor.current(), Modifier.fillMaxSize())
            } else {
                val current = output
                when (current) {
                    null -> Text(
                        if (ready) "Nothing tested yet — type below and press Test."
                        else "No model to test yet.",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                    is ModelCraftTest.Output.Text -> Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        Text(current.text, fontSize = 12.sp, color = colors.onSurface, lineHeight = 18.sp)
                    }
                    is ModelCraftTest.Output.Film -> FilmView(current)
                    is ModelCraftTest.Output.Sound -> Column(Modifier.fillMaxSize()) {
                        PictureCanvas(current.waveform, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(6.dp))
                        ActionButton(Icons.Filled.PlayArrow, "Play") {
                            scope.launch(Dispatchers.IO) {
                                PrismPlatform.audio.play(current.samples, current.sampleRate)
                            }
                        }
                    }
                }
            }
        }
    }
    (output?.caption ?: "").takeIf { it.isNotBlank() }?.let { caption ->
        Spacer(Modifier.height(8.dp))
        Text(caption, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
    }
    if (failure.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(failure, fontSize = 12.sp, color = colors.destructive, lineHeight = 16.sp)
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                ModelCraftTest.promptLabel(model.kind).uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.1.sp,
                color = colors.faint,
            )
            Spacer(Modifier.height(5.dp))
            Surface(
                color = if (ready && !busy && !running) colors.surfaceRaised else colors.surface,
                shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.fieldRadius),
                border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = ready && !busy && !running,
                    singleLine = false,
                    textStyle = TextStyle(
                        color = if (ready) colors.onSurface else colors.faint,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                if (ready) "Type something to test with" else "Train the model to test it",
                                fontSize = 13.sp,
                                color = colors.faint,
                            )
                        }
                        inner()
                    },
                )
            }
        }
        ActionButton(
            Icons.Filled.PlayArrow,
            if (busy) "Testing…" else "Test",
            enabled = ready && !busy && !running,
        ) {
            failure = ""
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ModelCraftTest.run(model, input, voice) }
                }
                busy = false
                result.onSuccess { output = it }
                    .onFailure { failure = it.message ?: "The test could not run." }
            }
        }
    }
}

/**
 * The live voice-training graph, drawn from the same measurements the phone uses.
 *
 * Seven lanes rather than seven overlaid lines: the groups measure different things in different
 * units, and a single shared Y axis made the quiet ones flat and the loud ones unreadable. The
 * faint trace is the whole dataset, the bright part is what the run has reached, and the vertical
 * rule is the clip being trained at this moment.
 */
@Composable
private fun VoiceTrainingGraph(
    snapshot: VoiceTrainingMonitor.Snapshot,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPrismColors.current
    val dark = colors.background.luminance() < 0.5f
    val laneColors = if (dark) {
        listOf(
            colors.accent,
            Color(0xFF8AB4F8),
            Color(0xFF4FC3F7),
            Color(0xFFFFB74D),
            Color(0xFF81C784),
            Color(0xFFBA68C8),
            Color(0xFFE57373),
        )
    } else {
        listOf(
            colors.accent,
            Color(0xFF1A73E8),
            Color(0xFF0277BD),
            Color(0xFFEF6C00),
            Color(0xFF2E7D32),
            Color(0xFF6A1B9A),
            Color(0xFFC62828),
        )
    }
    val frames = snapshot.frames
    val count = frames.size
    val cursor = snapshot.cursor.coerceIn(0, (count - 1).coerceAtLeast(0))
    val cursorValid = count > 0 && snapshot.cursor in frames.indices
    val header = if (snapshot.epoch > 0) {
        "EPOCH ${snapshot.epoch}/${snapshot.epochs.coerceAtLeast(snapshot.epoch)} · " +
            "STEP ${snapshot.step}/${snapshot.steps}"
    } else {
        snapshot.phase.ifBlank { "MEASURING THE VOICE…" }.uppercase(Locale.US)
    }
    val file = snapshot.currentFile.ifBlank {
        snapshot.files.getOrNull(cursor)?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "VOICE TRAINING",
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold,
                color = colors.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(header, fontSize = 8.5.sp, color = colors.faint, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))

        if (!snapshot.ready) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        snapshot.phase.ifBlank { "MEASURING THE VOICE…" }.uppercase(Locale.US),
                        fontSize = 12.sp,
                        color = colors.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pitch, rhythm, voice quality, timbre and timing appear here " +
                            "as the training dataset is measured.",
                        fontSize = 8.5.sp,
                        color = colors.faint,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(84.dp).fillMaxHeight()) {
                    VoiceTrainingMonitor.GROUPS.forEachIndexed { index, group ->
                        val active = cursorValid && frames[cursor][index].isFinite()
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                group.label,
                                fontSize = 7.5.sp,
                                color = if (active) colors.onSurface else colors.faint,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val laneHeight = size.height / VoiceTrainingMonitor.GROUPS.size
                    fun xAt(index: Int): Float = if (count <= 1) {
                        size.width / 2f
                    } else {
                        size.width * index.toFloat() / (count - 1)
                    }
                    fun yAt(group: Int, value: Float): Float =
                        group * laneHeight + laneHeight - 4f -
                            value.coerceIn(0f, 1f) * (laneHeight - 8f)

                    VoiceTrainingMonitor.GROUPS.indices.forEach { group ->
                        if (group > 0) {
                            drawLine(
                                colors.divider.copy(alpha = 0.45f),
                                Offset(0f, group * laneHeight),
                                Offset(size.width, group * laneHeight),
                                strokeWidth = 1f,
                            )
                        }
                        // A sparse draw: a large corpus would otherwise make every recomposition
                        // build tens of thousands of segments per lane. The shape survives; the
                        // cost does not.
                        val stride = (count / 260).coerceAtLeast(1)
                        val all = Path()
                        var started = false
                        var index = 0
                        while (index < count) {
                            val value = frames[index][group]
                            if (!value.isFinite()) {
                                started = false
                            } else {
                                if (started) all.lineTo(xAt(index), yAt(group, value))
                                else all.moveTo(xAt(index), yAt(group, value))
                                started = true
                            }
                            index += stride
                        }
                        drawPath(all, laneColors[group].copy(alpha = 0.22f), style = Stroke(1f))

                        val seen = Path()
                        started = false
                        index = 0
                        while (index <= cursor) {
                            val value = frames[index][group]
                            if (!value.isFinite()) {
                                started = false
                            } else {
                                if (started) seen.lineTo(xAt(index), yAt(group, value))
                                else seen.moveTo(xAt(index), yAt(group, value))
                                started = true
                            }
                            index += stride
                        }
                        drawPath(seen, laneColors[group], style = Stroke(1.6f))

                        if (cursorValid) {
                            val value = frames[cursor][group]
                            if (value.isFinite()) {
                                drawCircle(
                                    laneColors[group],
                                    radius = 2.6f,
                                    center = Offset(xAt(cursor), yAt(group, value)),
                                )
                            }
                        }
                    }
                    drawLine(
                        colors.onSurface.copy(alpha = 0.18f),
                        Offset(if (count <= 1) size.width / 2f else size.width * cursor.toFloat() / (count - 1), 0f),
                        Offset(if (count <= 1) size.width / 2f else size.width * cursor.toFloat() / (count - 1), size.height),
                        strokeWidth = 1f,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.ifBlank { "waiting for the first step" },
                fontSize = 8.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (snapshot.loss.isFinite()) {
                Text(
                    String.format(Locale.US, "loss %.4f", snapshot.loss),
                    fontSize = 8.sp,
                    color = colors.faint,
                )
            }
        }
    }
}

/** A film loops itself while it has more than one frame; a single frame just sits there. */
@Composable
private fun FilmView(film: ModelCraftTest.Output.Film) {
    var index by remember(film) { mutableStateOf(0) }
    LaunchedEffect(film) {
        if (film.frames.size > 1 && film.fps > 0) {
            while (true) {
                delay(1_000L / film.fps)
                index = (index + 1) % film.frames.size
            }
        }
    }
    val picture = film.frames.getOrNull(index) ?: return
    PictureCanvas(picture, Modifier.fillMaxSize())
}

/** Draws a small ARGB picture, scaled to fit and centred, nearest-neighbour so pixels stay square. */
@Composable
private fun PictureCanvas(picture: ModelCraftTest.Picture, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / picture.width, size.height / picture.height)
        val left = (size.width - picture.width * scale) / 2f
        val top = (size.height - picture.height * scale) / 2f
        for (row in 0 until picture.height) {
            for (column in 0 until picture.width) {
                val argb = picture.argb[row * picture.width + column]
                if ((argb ushr 24) and 0xFF == 0) continue
                drawRect(
                    color = Color(argb),
                    topLeft = Offset(left + column * scale, top + row * scale),
                    size = Size(scale + 0.5f, scale + 0.5f),
                )
            }
        }
    }
}

/**
 * One answer of the model-files radio group.
 *
 * A radio rather than a dropdown for the same reason the audio type is one: two answers, and each
 * one's consequence -- how many files, and what reads them -- belongs under the answer rather than
 * hidden until it is chosen.
 */
@Composable
private fun FileFormatChoice(
    format: ModelCraft.FileFormat,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = colors.faint,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(format.label, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(format.note, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
        }
    }
}

/**
 * One answer of the audio-type radio group.
 *
 * A real radio rather than a dropdown, because there are exactly two answers and the second one
 * adds a required box underneath it -- which a menu would hide exactly when the user needs to see it.
 */
@Composable
private fun AudioTypeChoice(
    type: ModelCraft.AudioType,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = colors.faint,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(type.label, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(type.note, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
        }
    }
}

/**
 * The draft re-made as [kind], keeping its identity and everything already imported.
 *
 * `kind` is fixed when a record is constructed, so changing the tab has to construct a new record --
 * with the same id, because the dataset folder and the store's row are keyed by it. Nothing is saved
 * here: the draft becomes a row when something else on the form is committed, which is what keeps
 * opening the creator and backing out from leaving a model behind.
 */
private fun draftAs(model: CraftedModel, kind: ModelCraft.Kind): CraftedModel {
    if (model.kind == kind) return model
    val replacement = CraftedModel(
        id = model.id,
        name = model.name,
        kind = kind,
        architecture = ModelCraft.architectures.first(),
        family = ModelCraft.archTypes.getValue(kind).first().name,
        params = model.params,
    )
    replacement.dataset.addAll(model.dataset)
    replacement.datasetSummary = model.datasetSummary
    return replacement
}

/** A theme-coloured control, matching the one AudioCraft uses. */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.buttonRadius),
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier.alphaIf(enabled).clickableRow { if (enabled) onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null,
                tint = when {
                    !enabled -> colors.faint
                    destructive -> colors.destructive
                    else -> colors.accent
                },
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = if (enabled) colors.onSurface else colors.faint)
        }
    }
}

/**
 * Any file, deliberately.
 *
 * The dataset a model wants is a CSV plus the media it points at, and which extensions count depends
 * on the kind. Filtering the picker to a guess would refuse the CSV half of a photo dataset, so the
 * import is open and [ModelCraft.prepare] reports what it could and could not use.
 */
private fun pickDirectory(title: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.takeIf { it.isDirectory }
}

private fun pickFiles(title: String = "Import dataset files"): List<File> {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
    val picked = mutableListOf<File>()
    chooser.selectedFiles.filterNotNull().forEach { file ->
        if (file.isDirectory) {
            file.listFiles()?.filter { it.isFile }?.let { picked.addAll(it) }
        } else {
            picked.add(file)
        }
    }
    return picked
}

/**
 * Runs one of the two tests and returns the paragraph the page shows.
 *
 * The lengthy option is 50 epochs of the same measurement rather than a different test: the ranking
 * of techniques, or the proposed inputs, come out of the same code, and what the extra epochs buy is
 * a number that includes the storage's steady state instead of a single warm pass.
 */
private suspend fun runModelTest(
    model: com.prism.launcher.modelcraft.CraftedModel,
    kind: String,
    epochs: Int,
): String? = withContext(Dispatchers.IO) {
    if (kind == "smoke") {
        runCatching {
            com.prism.launcher.modelcraft.TrainingTechniqueSmokeTest.run(model, epochs).also {
                com.prism.launcher.modelcraft.TrainingTechniqueSmokeTest.apply(model, it)
            }.summary
        }.getOrNull()
    } else {
        runCatching {
            com.prism.launcher.modelcraft.ParameterAdvisor.run(model, epochs).also {
                com.prism.launcher.modelcraft.ParameterAdvisor.apply(model, it)
            }.let {
                it.summary + System.lineSeparator() + it.lines.joinToString(System.lineSeparator())
            }
        }.getOrNull()
    }
}
