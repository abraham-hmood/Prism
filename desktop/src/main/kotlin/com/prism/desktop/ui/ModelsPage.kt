package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.Downloader
import com.prism.core.PrismPlatform
import com.prism.desktop.cakechat.DesktopCakeChat
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.MeshModels
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.ModelDiscoveryService
import com.prism.launcher.messaging.ModelRegistry
import com.prism.launcher.messaging.OllamaDiscoveryService
import com.prism.launcher.messaging.OllamaGgufDownloads
import com.prism.launcher.videocraft.VideoCraftModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Models: what is installed, what is active, and where to get more.
 *
 * PHASES 32 AND 33 ON ONE PAGE. Android splits them across a Models page and a Model Store, which
 * makes sense on a phone where a screen holds one thing. On a desktop the two halves of "manage
 * my models" fit side by side, and separating them would mean navigating away to answer "do I
 * already have this one?" -- which is the question you have while browsing the store.
 *
 * THE TYPE PILL IS NOT COSMETIC. A text model handed to the image pipeline, or the reverse,
 * fails deep inside a native library with an error that names neither. The type is stored with
 * the model and shown on every row, and activation writes to the setting that matches the type.
 */
@Composable
fun ModelsPage() {
    // CakeChat takes the whole page when open rather than living in a panel: installing an
    // interpreter, converting a corpus and watching a training run are each long enough to want the
    // room, and none of them is something you do while also browsing the store.
    var cakeChatOpen by remember { mutableStateOf(false) }
    if (cakeChatOpen) {
        CakeChatPage(onBack = { cakeChatOpen = false })
        return
    }

    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current

    var installed by remember { mutableStateOf(PrismSettings.getImportedModels()) }
    var activeText by remember { mutableStateOf(PrismSettings.getLocalAiModelPath()) }
    var activeImage by remember { mutableStateOf(PrismSettings.getLocalImageModelPath()) }
    var activeAudioGen by remember { mutableStateOf(PrismSettings.getLocalAudioGenModelPath()) }
    var activeInterpolation by remember { mutableStateOf(PrismSettings.getLocalInterpolationModelPath()) }
    var activeVideo by remember { mutableStateOf(PrismSettings.getLocalVideoModelPath()) }
    var activeSpeech by remember { mutableStateOf(PrismSettings.getLocalAudioModelPath()) }
    var cakeChatActive by remember { mutableStateOf(PrismSettings.getUseCakeChat()) }
    var cakeChatTrained by remember {
        mutableStateOf(DesktopCakeChat.state() == DesktopCakeChat.State.TRAINED)
    }
    var status by remember { mutableStateOf<String?>(null) }

    // Models other devices are offering. Polled rather than observed: announcements arrive on a mesh
    // thread that knows nothing about Compose, and the set changes when a machine wakes or sleeps.
    var meshModels by remember { mutableStateOf<List<MeshModels.Hosted>>(emptyList()) }
    var meshRevision by remember { mutableStateOf(0) }
    var hosting by remember { mutableStateOf(PrismSettings.getP2pModelHostingEnabled()) }
    val selectedMesh = remember(meshRevision) { PrismSettings.getSelectedP2pModel() }

    LaunchedEffect(meshRevision) {
        while (true) {
            meshModels = MeshModels.available()
            kotlinx.coroutines.delay(3_000)
        }
    }
    // Ollama servers on this network, and the conversions of their models into GGUFs. Scanned on
    // entering the page because a server can have been started or stopped since the last visit.
    var ollamaServers by remember { mutableStateOf<List<OllamaDiscoveryService.OllamaServer>>(emptyList()) }
    // The report, not just the servers: when nothing answers, the page names the networks it asked.
    var ollamaReport by remember { mutableStateOf<OllamaDiscoveryService.ScanReport?>(null) }
    var ollamaAddress by remember { mutableStateOf("") }
    var ollamaScanning by remember { mutableStateOf(false) }
    var ollamaScanned by remember { mutableStateOf(false) }
    var ollamaScanRevision by remember { mutableStateOf(0) }
    var ollamaMenuFor by remember { mutableStateOf<String?>(null) }
    val ollamaTasks by OllamaGgufDownloads.tasks.collectAsState()

    LaunchedEffect(ollamaScanRevision) {
        ollamaScanning = true
        val report = withContext(Dispatchers.IO) { OllamaDiscoveryService.scanReport() }
        ollamaServers = report.servers
        ollamaReport = report
        ollamaScanning = false
        ollamaScanned = true
    }

    // AN ADDRESS THE SWEEP CANNOT SEE: another VLAN, a router that drops broadcast, or a machine
    // whose IP the user already knows. Debounced because a text field reports every keystroke and
    // each probe opens a socket.
    LaunchedEffect(ollamaAddress) {
        val target = OllamaDiscoveryService.parseAddress(ollamaAddress) ?: return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        val server = withContext(Dispatchers.IO) {
            OllamaDiscoveryService.probeHost(target.first, target.second)
        }
        if (server != null) {
            ollamaServers = (ollamaServers + server).distinctBy { it.host + ":" + it.port }
        }
    }

    // A CONVERSION THAT FINISHED IS A NEW MODEL, so the list above has to be re-read. Keyed by the
    // number of finished conversions rather than the task list itself: the list changes on every
    // progress tick, and reconciling the models folder ten times a second is not a refresh. The
    // effect itself sits below `reload`, which is a local function and has to be declared first.
    var convertedCount by remember { mutableStateOf(0) }

    var confirmDelete by remember { mutableStateOf<PrismSettings.ImportedModel?>(null) }
    var importPath by remember { mutableStateOf("") }
    var importType by remember { mutableStateOf(PrismSettings.MODEL_TYPE_IMAGE) }
    // The store category picks what a download is registered as. An ONNX file can be an image
    // generator or a frame interpolator, and the extension does not say which.
    var storeCategory by remember { mutableStateOf("text") }

    // Store state.
    var query by remember { mutableStateOf("gguf") }
    var results by remember { mutableStateOf<List<ModelDiscoveryService.DiscoveredModel>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf<List<Downloader.Progress>>(emptyList()) }

    // Progress arrives on a download thread; this republishes it into composition state.
    DisposableEffect(Unit) {
        PrismPlatform.downloader.onProgress = { downloads = PrismPlatform.downloader.active() }
        onDispose { PrismPlatform.downloader.onProgress = null }
    }

    fun reload() {
        // Reconciled first: a download that completed while this page was closed was registered by
        // ModelRegistry, and a file dropped into the folder by hand was not registered by anything.
        runCatching { com.prism.launcher.messaging.ModelRegistry.reconcile() }
        installed = PrismSettings.getImportedModels()
        activeText = PrismSettings.getLocalAiModelPath()
        activeImage = PrismSettings.getLocalImageModelPath()
        activeAudioGen = PrismSettings.getLocalAudioGenModelPath()
        activeInterpolation = PrismSettings.getLocalInterpolationModelPath()
        activeVideo = PrismSettings.getLocalVideoModelPath()
        activeSpeech = PrismSettings.getLocalAudioModelPath()
        cakeChatActive = PrismSettings.getUseCakeChat()
        // Re-read rather than cached: training or importing a bundle happens on the CakeChat page,
        // so this list has to notice a model that was not there when it was first drawn.
        cakeChatTrained = DesktopCakeChat.state() == DesktopCakeChat.State.TRAINED
    }

    LaunchedEffect(ollamaTasks.count { it.state == OllamaGgufDownloads.State.DONE }) {
        val done = ollamaTasks.count { it.state == OllamaGgufDownloads.State.DONE }
        if (done != convertedCount) {
            convertedCount = done
            reload()
            status = ollamaTasks.lastOrNull { it.state == OllamaGgufDownloads.State.DONE }?.note
        }
    }

    fun activate(model: PrismSettings.ImportedModel) {
        // EXISTS IS THE QUESTION, not IS A FILE: a safetensors pipeline -- image or video -- is a
        // directory of shards, and the isFile test refused those silently. The row was listed, the
        // click did nothing at all, and no status line ever said why.
        if (!File(model.path).exists()) {
            status = "That file is gone; remove it or re-download."
            return
        }
        when (model.type) {
            PrismSettings.MODEL_TYPE_IMAGE -> {
                PrismSettings.setLocalImageModelPath(model.path)
                // AND THE ROUTE, so the Image page does not keep showing the engine the old path
                // belonged to -- the model list and the engine picker describe one choice.
                PrismSettings.setImageEngineId(
                    com.prism.launcher.messaging.ImageGeneration.engineIdForActiveModel().orEmpty()
                )
                // THE DIFFUSION ENGINE KEEPS ITS LOADED CHECKPOINT, so a new selection has to drop
                // it or the next render keeps drawing with the previous file -- activation that
                // looked like it did nothing.
                runCatching { com.prism.launcher.diffusion.StableDiffusion.unload() }
            }
            PrismSettings.MODEL_TYPE_AUDIO_GEN -> PrismSettings.setLocalAudioGenModelPath(model.path)
            PrismSettings.MODEL_TYPE_INTERPOLATION ->
                PrismSettings.setLocalInterpolationModelPath(model.path)
            PrismSettings.MODEL_TYPE_VIDEO -> PrismSettings.setLocalVideoModelPath(model.path)
            PrismSettings.MODEL_TYPE_AUDIO -> {
                PrismSettings.setLocalAudioModelPath(model.path)
                com.prism.launcher.speech.PrismSpeaker.invalidate()
            }
            else -> {
                PrismSettings.setLocalAiModelPath(model.path)
                PrismSettings.setUseCakeChat(false)
            }
        }
        status = "${model.displayName} is now the active ${typeLabel(model.type).lowercase()} model"
        reload()
    }

    /**
     * Registers a downloaded or hand-placed file.
     *
     * The type is decided by INSPECTING the file, not by its extension or by which button was
     * pressed. A `.bin` from HuggingFace may be either; a mislabelled model fails much later and
     * much less legibly than it does here.
     */
    fun import(file: File, type: String, preferredName: String? = null) {
        // Video is the one family whose import can be a directory, for safetensors pipelines.
        val folderOk = type == PrismSettings.MODEL_TYPE_VIDEO && file.isDirectory
        if (!file.isFile && !folderOk) {
            status = "${file.name} is not a file"
            return
        }
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = file.absolutePath,
                displayName = preferredName ?: file.nameWithoutExtension,
                type = type,
            )
        )
        status = "Imported ${file.name} as ${typeLabel(type)}"
        reload()
    }

    PageScaffold("Models", "Installed models, and where to find more") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            // ── Installed (Phase 33) ──────────────────────────────────────────────────────
            val textModels = installed.filter { it.type == PrismSettings.MODEL_TYPE_TEXT }
            val imageModels = installed.filter { it.type == PrismSettings.MODEL_TYPE_IMAGE }
            val audioGenModels = installed.filter { it.type == PrismSettings.MODEL_TYPE_AUDIO_GEN }
            val interpolationModels =
                installed.filter { it.type == PrismSettings.MODEL_TYPE_INTERPOLATION }
            val videoModels = installed.filter { it.type == PrismSettings.MODEL_TYPE_VIDEO }
            val speechModels = installed.filter { it.type == PrismSettings.MODEL_TYPE_AUDIO }

            ModelSection(
                title = "TEXT MODELS",
                models = textModels,
                activePath = activeText,
                media = emptyList(),
                emptyText = "No text models imported yet.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            ModelSection(
                title = "IMAGE MODELS",
                models = imageModels,
                activePath = activeImage,
                media = VideoCraftModels.entries(VideoCraftModels.Kind.IMAGE)
                    .filter { it.builtIn || it.cloud },
                emptyText = "No image models imported yet.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            ModelSection(
                title = "AUDIO GENERATION MODELS",
                models = audioGenModels,
                activePath = activeAudioGen,
                media = VideoCraftModels.entries(VideoCraftModels.Kind.AUDIO_GEN)
                    .filter { it.builtIn || it.cloud },
                emptyText = "No audio generators imported yet.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            ModelSection(
                title = "FRAME INTERPOLATION MODELS",
                models = interpolationModels,
                activePath = activeInterpolation,
                media = VideoCraftModels.entries(VideoCraftModels.Kind.INTERPOLATION)
                    .filter { it.builtIn || it.cloud },
                emptyText = "No frame interpolation models imported yet.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            ModelSection(
                title = "VIDEO MODELS",
                models = videoModels,
                activePath = activeVideo,
                media = VideoCraftModels.entries(VideoCraftModels.Kind.VIDEO)
                    // CRAFTED TOO: a clip model trained in ModelCraft lives in its own dataset
                    // folder and is not an import, so filtering to cloud alone would hide the one
                    // model this section exists for.
                    .filter { it.cloud || it.crafted },
                emptyText = "No video models imported yet. A clip model replaces the image, " +
                    "interpolation, audio and speech families in VideoCraft.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            ModelSection(
                title = "SPEECH MODELS",
                models = speechModels,
                activePath = activeSpeech,
                media = if (com.prism.launcher.speech.KokoroInstall.isModelInstalled()) {
                    listOf(
                        VideoCraftModels.Entry(
                            id = VideoCraftModels.KOKORO_ID,
                            displayName = "Kokoro-82M",
                            kind = VideoCraftModels.Kind.SPEECH,
                            detail = "Built-in speech engine · installed",
                            thePath = "",
                            builtIn = true,
                        ),
                    )
                } else emptyList(),
                emptyText = "No speech models imported yet. Kokoro can be installed from its settings page.",
                onActivate = ::activate,
                onDelete = { confirmDelete = it },
            )
            SectionFooter(
                "Every family has its own active model. VideoCraft needs one active image generator, " +
                    "audio generator, frame interpolator, text model and speech engine; image, audio " +
                    "and interpolation models can be local or the cloud endpoint."
            )

            Spacer(Modifier.height(14.dp))

            // ── Models on other devices ───────────────────────────────────
            //
            // LISTED WITH THE LOCAL ONES, because from here they are the same decision: which model
            // answers when you ask something. That one of them lives on another machine is a property
            // of the model, like its size, rather than a different feature.
            SectionHeader("ON YOUR MESHNET")
            Card {
                Row(
                    Modifier.fillMaxWidth()
                        .clickableRow {
                            PrismSettings.clearSelectedP2pModel()
                            status = "Back to this machine's own models"
                            meshRevision++
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Use this machine's own models", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (selectedMesh == null) {
                        Text("active", fontSize = 11.sp, color = colors.accent)
                    }
                }

                meshModels.forEach { model ->
                    Hairline()
                    val active = selectedMesh?.peerIp == model.peerIp &&
                        selectedMesh?.modelName == model.modelName
                    Row(
                        Modifier.fillMaxWidth()
                            .clickableRow {
                                PrismSettings.setSelectedP2pModel(model.peerIp, model.modelName)
                                // CakeChat is checked before everything else among the local engines,
                                // so it has to be released or the choice would appear to do nothing --
                                // the same trap the local list above documents.
                                PrismSettings.setUseCakeChat(false)
                                status = model.modelName + " on " + model.peerIp +
                                    " will now answer everywhere in Prism"
                                meshRevision++
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.modelName, fontSize = 14.sp)
                            Text(
                                "on " + model.peerIp + " — the weights stay there",
                                fontSize = 11.sp,
                                color = colors.faint,
                            )
                        }
                        if (active) Text("active", fontSize = 11.sp, color = colors.accent)
                    }
                }

                if (meshModels.isEmpty()) {
                    Hairline()
                    Text(
                        "No other device is offering one. A device offers its active model by turning " +
                            "on model hosting; the announcement arrives with the next gossip round.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            SectionFooter(
                "Choosing one of these points Sam, Nebula and everything else in Prism at that machine " +
                    "— nothing is downloaded and nothing is copied, which is the point: the phone that " +
                    "wants a 14B model is exactly the device that cannot hold one. If that machine stops " +
                    "answering, Prism says so rather than quietly using a different model."
            )

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Share this machine's model",
                detail = "Other devices on your meshnet can use whichever text model is active here. " +
                    "It is their prompts running on your processor, so it is off until you say so.",
                checked = hosting,
                onChange = { on ->
                    PrismSettings.setP2pModelHostingEnabled(on)
                    MeshModels.announce()
                    hosting = on
                    meshRevision++
                },
            )

            Spacer(Modifier.height(14.dp))

            // ── Ollama on the network ─────────────────────────────────────────────────────
            //
            // LISTED HERE RATHER THAN ONLY IN CLOUD AI because the decision is the same one this
            // page is about: which model answers, and whether its weights can live on this machine.
            // Left-click uses the server's model as-is; RIGHT-CLICK fetches the weights from Ollama's
            // registry and saves them here as a GGUF, which is the only way a model that lives on
            // another machine's disk becomes a file this one can load.
            SectionHeader("OLLAMA ON YOUR NETWORK")
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            ollamaScanning -> "Scanning the local network\u2026"
                            ollamaScanned && ollamaServers.isEmpty() -> "No Ollama server answered"
                            else -> ollamaServers.sumOf { it.models.size }.toString() + " model" +
                                (if (ollamaServers.sumOf { it.models.size } == 1) "" else "s") +
                                " on " + ollamaServers.size + " server" +
                                (if (ollamaServers.size == 1) "" else "s")
                        },
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.weight(1f),
                    )
                    if (ollamaScanning) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { ollamaScanRevision++ }) { Text("Rescan") }
                    }
                }

                if (ollamaScanned && ollamaServers.isEmpty()) {
                    Hairline()
                    Text(
                        "No answer from " + (ollamaReport?.sweptLabel() ?: "the local network") +
                            ". Start Ollama (it listens on port 11434) and rescan. Servers on the same " +
                            "WiFi are found automatically, including one running on this machine \u2014 " +
                            "for a server on another machine, start it with OLLAMA_HOST=0.0.0.0 and " +
                            "allow port 11434 in that machine's firewall, or type its address here.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                    OutlinedTextField(
                        value = ollamaAddress,
                        onValueChange = { typed -> ollamaAddress = typed },
                        placeholder = {
                            Text("192.168.1.42 or 192.168.1.42:11434", fontSize = 12.sp)
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                ollamaServers.forEach { server ->
                    server.models.forEach { modelName ->
                        val key = server.host + ":" + server.port + "/" + modelName
                        val task = ollamaTasks.firstOrNull { it.model == modelName }
                        val active = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL_CLOUD &&
                            PrismSettings.getSelectedOllamaEndpoint()?.let {
                                it.host == server.host && it.port == server.port && it.model == modelName
                            } == true
                        Hairline()
                        Box {
                            Column(
                                Modifier.fillMaxWidth()
                                    .rightClickable { ollamaMenuFor = key }
                                    .clickableRow {
                                        PrismSettings.clearSelectedP2pModel()
                                        PrismSettings.setSelectedOllamaEndpoint(
                                            PrismSettings.OllamaEndpoint(server.host, server.port, modelName)
                                        )
                                        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL_CLOUD)
                                        status = modelName + " on " + server.host +
                                            " will now answer in Prism"
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        modelName,
                                        fontSize = 14.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (active) Text("active", fontSize = 11.sp, color = colors.accent)
                                }
                                Text(
                                    when {
                                        task != null && task.isRunning ->
                                            server.host + ":" + server.port + " \u00b7 converting " +
                                                (task.fraction * 100).toInt() + "%"
                                        task != null && task.state == OllamaGgufDownloads.State.DONE ->
                                            server.host + ":" + server.port + " \u00b7 saved as GGUF"
                                        task != null && task.state == OllamaGgufDownloads.State.FAILED ->
                                            task.note
                                        else -> server.host + ":" + server.port +
                                            " \u00b7 right-click to save the weights as GGUF"
                                    },
                                    fontSize = 10.sp,
                                    color = if (task?.state == OllamaGgufDownloads.State.FAILED)
                                        Color(0xFFE57373) else colors.faint,
                                )
                                if (task != null && task.isRunning) {
                                    Spacer(Modifier.height(5.dp))
                                    if (task.total > 0L) {
                                        LinearProgressIndicator(
                                            progress = { task.fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = ollamaMenuFor == key,
                                onDismissRequest = { ollamaMenuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (task != null && task.isRunning)
                                                "Converting\u2026 " + (task.fraction * 100).toInt() + "%"
                                            else "Download & convert to GGUF",
                                            fontSize = 13.sp,
                                        )
                                    },
                                    enabled = task?.isRunning != true,
                                    onClick = {
                                        ollamaMenuFor = null
                                        status = if (OllamaGgufDownloads.start(modelName))
                                            "Fetching " + modelName +
                                                " from Ollama; it will be saved as a GGUF text model"
                                        else modelName + " is already being converted"
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Use it from this server", fontSize = 13.sp) },
                                    onClick = {
                                        ollamaMenuFor = null
                                        PrismSettings.clearSelectedP2pModel()
                                        PrismSettings.setSelectedOllamaEndpoint(
                                            PrismSettings.OllamaEndpoint(server.host, server.port, modelName)
                                        )
                                        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL_CLOUD)
                                        status = modelName + " on " + server.host +
                                            " will now answer in Prism"
                                    },
                                )
                            }
                        }
                    }
                }
            }
            SectionFooter(
                "A model an Ollama server lists lives in that server's own blob store -- there is no " +
                    "file to import. Downloading one reads its published weights instead: the " +
                    "manifest from ollama.com, or the GGUF from Hugging Face when the name starts " +
                    "hf.co/. The sha256 the publisher names is checked as the bytes arrive, and what " +
                    "is written out is a plain .gguf -- the same weights, without Ollama's packaging " +
                    "-- which is what the local text engine reads. It is then imported as a text " +
                    "model and appears in the list above. A model made locally with `ollama create` " +
                    "is not published anywhere and cannot be fetched this way."
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("CAKECHAT")
            Card {
                // SELECTABLE ONLY ONCE TRAINED. Until then there is nothing to answer with, and a
                // row that can be activated but cannot reply is worse than one that is absent --
                // it looks like Sam is ignoring the choice.
                if (cakeChatTrained) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickableRow {
                                PrismSettings.setUseCakeChat(true)
                                status = "CakeChat is now the active text model"
                                reload()
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (cakeChatActive) Icons.Filled.RadioButtonChecked
                            else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (cakeChatActive) colors.accent else colors.faint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "CakeChat",
                                fontSize = 14.sp,
                                fontWeight =
                                    if (cakeChatActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "Trained on this machine · emotion-conditioned",
                                fontSize = 10.sp, color = colors.faint,
                            )
                        }
                        TextButton(onClick = { cakeChatOpen = true }) { Text("Manage") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Replika's conversational model", fontSize = 13.sp)
                            Text(
                                "A conditioned seq2seq trained on your own corpus. Needs Python.",
                                fontSize = 11.sp, color = colors.faint,
                            )
                        }
                        TextButton(onClick = { cakeChatOpen = true }) { Text("Open") }
                    }
                }
            }
            SectionFooter(
                if (cakeChatTrained)
                    "Selecting CakeChat routes Sam — and any character using Sam as its backend — " +
                        "through it instead of the GGUF model above."
                else
                    "Not a GGUF model and not listed above -- it runs through its own Python " +
                        "rather than llama.cpp, so it is managed on its own page. It becomes " +
                        "selectable here once it has been trained or a model bundle imported."
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("IMPORT LOCAL MODELS")
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            "Text" to PrismSettings.MODEL_TYPE_TEXT,
                            "Image" to PrismSettings.MODEL_TYPE_IMAGE,
                            "Audio gen" to PrismSettings.MODEL_TYPE_AUDIO_GEN,
                            "Frames" to PrismSettings.MODEL_TYPE_INTERPOLATION,
                            "Video" to PrismSettings.MODEL_TYPE_VIDEO,
                            "Speech" to PrismSettings.MODEL_TYPE_AUDIO,
                        ).forEach { (label, type) ->
                            OutlinedButton(
                                onClick = { importType = type },
                                modifier = Modifier.padding(end = 5.dp),
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (type == importType) colors.accent else colors.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommittingField(importPath, modifier = Modifier.weight(1f)) { importPath = it }
                        TextButton(
                            enabled = importPath.isNotBlank(),
                            onClick = {
                                import(File(importPath.trim()), importType)
                                importPath = ""
                            },
                        ) { Text("Import as ${typeLabel(importType)}") }
                    }
                }
            }
            SectionFooter(
                "Image, audio, frame interpolation and video models can be imported here, as " +
                    "GGUF, safetensors, ONNX or a MediaPipe .task bundle. The file is registered " +
                    "under the family you picked \u2014 text generation reads GGUF, image generation " +
                    "reads GGUF or safetensors, and a video model may be a GGUF file or a " +
                    "safetensors FOLDER holding its encoder, decoder and shards. Choosing Video " +
                    "makes VideoCraft skip the image, interpolation, audio and speech families " +
                    "entirely and render every shot with the clip model. A format an engine cannot " +
                    "open is stored and flagged rather than silently activated."
            )

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = colors.accent, modifier = Modifier.padding(4.dp))
            }

            // ── Downloads in flight ───────────────────────────────────────────────────────
            if (downloads.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionHeader("DOWNLOADS")
                Card {
                    downloads.forEachIndexed { i, d ->
                        if (i > 0) Hairline()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (d.state) {
                                        Downloader.State.COMPLETE -> "done"
                                        Downloader.State.FAILED -> d.error ?: "failed"
                                        Downloader.State.CANCELLED -> "cancelled"
                                        else -> if (d.totalBytes > 0) "${(d.fraction * 100).toInt()}%"
                                        else humanBytes(d.bytesDownloaded)
                                    },
                                    fontSize = 11.sp,
                                    color = if (d.state == Downloader.State.FAILED) Color(0xFFE57373)
                                    else colors.faint,
                                )
                                if (d.state == Downloader.State.RUNNING) {
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        Icons.Filled.Close, "Cancel",
                                        tint = colors.faint,
                                        modifier = Modifier.size(14.dp).clickableRow {
                                            PrismPlatform.downloader.cancel(d.id)
                                        },
                                    )
                                }
                            }
                            if (d.state == Downloader.State.RUNNING) {
                                Spacer(Modifier.height(5.dp))
                                if (d.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = { d.fraction },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    // No Content-Length. An indeterminate bar is honest; a fake
                                    // percentage is not.
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }

            // ── Store (Phase 32) ──────────────────────────────────────────────────────────
            Spacer(Modifier.height(18.dp))
            SectionHeader("MODEL STORE")
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        "Text" to "text",
                        "Image" to "generative",
                        "Audio" to "audio",
                        "Interpolation" to "interpolation",
                        "All" to "all",
                    ).forEach { (label, category) ->
                        OutlinedButton(
                            onClick = { storeCategory = category },
                            modifier = Modifier.padding(end = 5.dp),
                        ) {
                            Text(
                                label,
                                fontSize = 11.sp,
                                color = if (storeCategory == category) colors.accent else colors.onSurface,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommittingField(query, modifier = Modifier.weight(1f)) { query = it }
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        TextButton(onClick = {
                            searching = true
                            scope.launch {
                                results = try {
                                    withContext(Dispatchers.IO) {
                                        val term = if (query.isBlank()) {
                                            when (storeCategory) {
                                                "audio" -> "text to speech onnx"
                                                "interpolation" -> "frame interpolation onnx"
                                                "generative" -> "stable diffusion"
                                                "text" -> "gguf"
                                                else -> "onnx"
                                            }
                                        } else query
                                        ModelDiscoveryService.discoverAll(term, storeCategory)
                                    }
                                } catch (e: Exception) {
                                    PrismPlatform.log.error("Prism/models", "Search failed", e)
                                    status = "Search failed: ${e.message}"
                                    emptyList()
                                }
                                searching = false
                            }
                        }) { Text("Search") }
                    }
                }

                results.forEach { model ->
                    Hairline()
                    val already = installed.any { it.displayName == model.name }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${model.source}  ·  ${model.sizeLabel}",
                                fontSize = 10.sp, color = colors.faint,
                            )
                        }
                        if (already) {
                            Text("installed", fontSize = 11.sp, color = colors.faint)
                        } else {
                            TextButton(onClick = {
                                // Through ModelRegistry, so the store, the mesh and the reconcile scan
                                // all agree on one folder. This page deciding for itself is how the two
                                // diverged in the first place.
                                val dir = ModelRegistry.directory()
                                val target = File(dir, model.downloadUrl.substringAfterLast('/'))
                                // Recorded before enqueue: completion is on another thread and
                                // an ONNX extension cannot distinguish image from interpolation.
                                storeType(storeCategory)?.let { ModelRegistry.expect(target, it) }
                                PrismPlatform.downloader.enqueue(model.name, model.downloadUrl, target)
                                downloads = PrismPlatform.downloader.active()
                                status = "Downloading ${model.name}"
                                // REGISTRATION IS NOT THIS PAGE'S JOB ANY MORE. It used to launch a
                                // watcher in this composition scope, which meant navigating away
                                // cancelled it while the download carried on -- the file landed and
                                // nothing recorded it, so it vanished on restart. ModelRegistry
                                // watches the downloader for the life of the process instead. This
                                // only refreshes the list once the file appears, which is cosmetic.
                                scope.launch { watchForDisplay(target) { reload() } }
                            }) { Text("Get") }
                        }
                    }
                }

                if (results.isEmpty() && !searching) {
                    Text(
                        when (storeCategory) {
                            "audio" -> "Search HuggingFace and GitHub for audio models. ONNX and " +
                                "safetensors exports can be imported into the audio family."
                            "interpolation" -> "Search for frame interpolation models. ONNX RIFE-style " +
                                "exports work with Prism Flow as the fallback."
                            "generative" -> "Search HuggingFace and GitHub for image models. " +
                                "GGUF and safetensors checkpoints both run through " +
                                "stable-diffusion.cpp; a download is filed under this filter, not " +
                                "by its extension."
                            else -> "Search HuggingFace and GitHub for models. GGUF files run locally " +
                                "through llama.cpp, safetensors and GGUF image checkpoints through " +
                                "stable-diffusion.cpp, and everything else is imported into its own " +
                                "list under the filter it was found with."
                        },
                        fontSize = 12.sp, color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            SectionFooter(
                "Downloads resume if interrupted -- the partial file is kept and continued with " +
                    "a range request. They do not survive Prism exiting, which Android's system " +
                    "downloader does and a desktop JVM cannot."
            )

            Spacer(Modifier.height(20.dp))
        }
    }

    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    PrismSettings.removeImportedModel(model.path)
                    confirmDelete = null
                    status = "Removed ${model.displayName} from the list"
                    reload()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Remove ${model.displayName}?") },
            text = {
                Column(Modifier.width(380.dp)) {
                    Text(
                        "This removes it from Prism's list. The file itself is left on disk.",
                        fontSize = 12.sp,
                    )
                    Text(
                        model.path,
                        fontSize = 10.sp, color = colors.faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
}

/**
 * Refreshes the list once a download has landed.
 *
 * COSMETIC ONLY, AND THAT IS THE POINT. This used to be where a completed download got REGISTERED, in a
 * coroutine owned by the page -- so closing the page or opening another one cancelled it, the download
 * finished anyway, and nothing wrote the entry. The model then showed for the rest of the session (the
 * downloader still listed it) and was gone after a restart.
 *
 * Registration now belongs to `ModelRegistry.watchDownloads`, which lives as long as the process. If this
 * coroutine is cancelled, the only thing lost is a list that refreshes a moment later than it could have.
 */
private suspend fun watchForDisplay(target: File, onDone: () -> Unit) {
    withContext(Dispatchers.IO) {
        repeat(3600) {
            if (target.isFile) {
                onDone()
                return@withContext
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}

@Composable
private fun ModelSection(
    title: String,
    models: List<PrismSettings.ImportedModel>,
    activePath: String,
    media: List<VideoCraftModels.Entry>,
    emptyText: String,
    onActivate: (PrismSettings.ImportedModel) -> Unit,
    onDelete: (PrismSettings.ImportedModel) -> Unit,
) {
    val colors = LocalPrismColors.current
    SectionHeader(title)
    Card {
        if (models.isEmpty() && media.isEmpty()) {
            Text(
                emptyText,
                fontSize = 12.sp, color = colors.faint,
                modifier = Modifier.padding(16.dp),
            )
        }
        var row = 0
        models.forEach { model ->
            if (row++ > 0) Hairline()
            InstalledModelRow(
                model = model,
                // CASE-INSENSITIVE: on Windows the registry can hold the same file with different
                // casing than the stored selection, and a case-sensitive compare left the radio
                // unchecked after a successful activation -- a click that looked like it did nothing.
                active = model.path.equals(activePath, ignoreCase = true),
                onActivate = { onActivate(model) },
                onDelete = { onDelete(model) },
            )
        }
        media.forEach { entry ->
            if (row++ > 0) Hairline()
            MediaModelRow(entry)
        }
    }
}

@Composable
private fun InstalledModelRow(
    model: PrismSettings.ImportedModel,
    active: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val exists = File(model.path).exists()
    Row(
        Modifier.fillMaxWidth()
            // ALWAYS ONACTIVATE. A row whose file has moved still has to answer the click: activate
            // reports the missing path, where a do-nothing click looked like a broken page.
            .clickableRow(onActivate)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) Icons.Filled.RadioButtonChecked
            else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (active) colors.accent else colors.faint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TypePill(model.type)
                if (!exists) {
                    Spacer(Modifier.width(6.dp))
                    MissingPill()
                }
            }
            Text(
                model.path,
                fontSize = 10.sp, color = colors.faint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Delete, "Remove",
            tint = colors.faint,
            modifier = Modifier.size(15.dp).clickableRow(onDelete),
        )
    }
}

@Composable
private fun MediaModelRow(entry: VideoCraftModels.Entry) {
    val colors = LocalPrismColors.current
    var active by remember(entry.kind, entry.id) {
        mutableStateOf(VideoCraftModels.isActive(entry.kind, entry.id))
    }
    Row(
        Modifier.fillMaxWidth()
            .clickableRow {
                VideoCraftModels.activate(entry.kind, entry)
                active = VideoCraftModels.isActive(entry.kind, entry.id)
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) Icons.Filled.RadioButtonChecked
            else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (active) colors.accent else colors.faint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayName,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(entry.detail, fontSize = 10.sp, color = colors.faint)
        }
        if (active) Text("active", fontSize = 11.sp, color = colors.accent)
    }
}

private fun typeLabel(type: String): String = when (type) {
    PrismSettings.MODEL_TYPE_IMAGE -> "Image"
    PrismSettings.MODEL_TYPE_AUDIO_GEN -> "Audio"
    PrismSettings.MODEL_TYPE_INTERPOLATION -> "Frames"
    PrismSettings.MODEL_TYPE_VIDEO -> "Video"
    PrismSettings.MODEL_TYPE_AUDIO -> "Speech"
    else -> "Text"
}

private fun storeType(category: String): String? = when (category) {
    "text" -> PrismSettings.MODEL_TYPE_TEXT
    "generative" -> PrismSettings.MODEL_TYPE_IMAGE
    "audio" -> PrismSettings.MODEL_TYPE_AUDIO_GEN
    "interpolation" -> PrismSettings.MODEL_TYPE_INTERPOLATION
    "video" -> PrismSettings.MODEL_TYPE_VIDEO
    else -> null
}

@Composable
private fun TypePill(type: String) {
    val label = typeLabel(type).uppercase()
    val blue = type == PrismSettings.MODEL_TYPE_TEXT
    Surface(
        color = if (blue) Color(0x332F6FEB) else Color(0x33B36FEB),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            label,
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
            color = if (blue) Color(0xFF8FB4FF) else Color(0xFFD0A8FF),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** Shown when the registry names a file that is no longer there. */
@Composable
private fun MissingPill() {
    Surface(color = Color(0x33B3261E), shape = RoundedCornerShape(5.dp)) {
        Text(
            "MISSING",
            fontSize = 8.sp, letterSpacing = 0.8.sp, color = Color(0xFFE57373),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
