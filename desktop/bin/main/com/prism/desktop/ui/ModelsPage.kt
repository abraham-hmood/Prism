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
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.ModelDiscoveryService
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
    var cakeChatActive by remember { mutableStateOf(PrismSettings.getUseCakeChat()) }
    var cakeChatTrained by remember {
        mutableStateOf(DesktopCakeChat.state() == DesktopCakeChat.State.TRAINED)
    }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PrismSettings.ImportedModel?>(null) }
    var importPath by remember { mutableStateOf("") }

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
        installed = PrismSettings.getImportedModels()
        activeText = PrismSettings.getLocalAiModelPath()
        activeImage = PrismSettings.getLocalImageModelPath()
        cakeChatActive = PrismSettings.getUseCakeChat()
        // Re-read rather than cached: training or importing a bundle happens on the CakeChat page,
        // so this list has to notice a model that was not there when it was first drawn.
        cakeChatTrained = DesktopCakeChat.state() == DesktopCakeChat.State.TRAINED
    }

    /**
     * Registers a downloaded or hand-placed file.
     *
     * The type is decided by INSPECTING the file, not by its extension or by which button was
     * pressed. A `.bin` from HuggingFace may be either; a mislabelled model fails much later and
     * much less legibly than it does here.
     */
    fun import(file: File, preferredName: String? = null) {
        if (!file.isFile) {
            status = "${file.name} is not a file"
            return
        }
        val isGguf = GgufInferenceService.isGgufFile(file.absolutePath)
        val type = if (isGguf) PrismSettings.MODEL_TYPE_TEXT else PrismSettings.MODEL_TYPE_IMAGE
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = file.absolutePath,
                displayName = preferredName ?: file.nameWithoutExtension,
                type = type,
            )
        )
        status = "Imported ${file.name} as ${if (isGguf) "TEXT" else "IMAGE"}"
        reload()
    }

    PageScaffold("Models", "Installed models, and where to find more") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            // ── Installed (Phase 33) ──────────────────────────────────────────────────────
            SectionHeader("INSTALLED")
            Card {
                if (installed.isEmpty()) {
                    Text(
                        "No models imported yet.",
                        fontSize = 12.sp, color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                installed.forEachIndexed { i, model ->
                    if (i > 0) Hairline()
                    val isText = model.type == PrismSettings.MODEL_TYPE_TEXT
                    val active = if (isText) model.path == activeText else model.path == activeImage
                    val exists = remember(model.path, installed) { File(model.path).isFile }

                    Row(
                        Modifier.fillMaxWidth()
                            .clickableRow {
                                if (!exists) {
                                    status = "That file is gone; remove it or re-download."
                                    return@clickableRow
                                }
                                if (isText) {
                                    PrismSettings.setLocalAiModelPath(model.path)
                                    // Or this would appear to do nothing: CakeChat is checked
                                    // first among local engines, so it has to be released when a
                                    // GGUF model is chosen instead.
                                    PrismSettings.setUseCakeChat(false)
                                } else {
                                    PrismSettings.setLocalImageModelPath(model.path)
                                }
                                status = "${model.displayName} is now the active " +
                                    (if (isText) "text" else "image") + " model"
                                reload()
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                TypePill(isText)
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
                            modifier = Modifier.size(15.dp).clickableRow { confirmDelete = model },
                        )
                    }
                }
            }
            SectionFooter(
                "Click a model to make it active. Text and image models are tracked separately, " +
                    "so activating one does not deactivate the other."
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

            SectionHeader("IMPORT A FILE")
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommittingField(importPath, modifier = Modifier.weight(1f)) { importPath = it }
                    TextButton(
                        enabled = importPath.isNotBlank(),
                        onClick = { import(File(importPath.trim())); importPath = "" },
                    ) { Text("Import") }
                }
            }
            SectionFooter(
                "The type is read from the file's header, not its name -- a GGUF file is a text " +
                    "model whatever it is called."
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
                                        ModelDiscoveryService.discoverAll(query.ifBlank { "gguf" })
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
                                val dir = File(PrismPlatform.host.documentsDir(), "Models")
                                val target = File(dir, model.downloadUrl.substringAfterLast('/'))
                                PrismPlatform.downloader.enqueue(model.name, model.downloadUrl, target)
                                downloads = PrismPlatform.downloader.active()
                                status = "Downloading ${model.name}"
                                // Registered when the download completes, not now -- registering
                                // a file that does not exist yet produces a model the user can
                                // select and that fails to load.
                                scope.launch { watchAndImport(target, model.name) { reload() } }
                            }) { Text("Get") }
                        }
                    }
                }

                if (results.isEmpty() && !searching) {
                    Text(
                        "Search HuggingFace and GitHub for models. GGUF files run locally " +
                            "through llama.cpp.",
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
 * Waits for a download to land, then registers it.
 *
 * Polls the file rather than hooking completion because the Downloader's callback is global and
 * this needs to react to one specific file; a per-download listener would be a nicer API and is
 * worth adding if a second caller ever needs it.
 */
private suspend fun watchAndImport(target: File, name: String, onDone: () -> Unit) {
    withContext(Dispatchers.IO) {
        repeat(3600) {
            if (target.isFile) {
                val isGguf = GgufInferenceService.isGgufFile(target.absolutePath)
                PrismSettings.addImportedModel(
                    PrismSettings.ImportedModel(
                        path = target.absolutePath,
                        displayName = name,
                        type = if (isGguf) PrismSettings.MODEL_TYPE_TEXT
                        else PrismSettings.MODEL_TYPE_IMAGE,
                    )
                )
                onDone()
                return@withContext
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}

@Composable
private fun TypePill(isText: Boolean) {
    Surface(
        color = if (isText) Color(0x332F6FEB) else Color(0x33B36FEB),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            if (isText) "TEXT" else "IMAGE",
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
            color = if (isText) Color(0xFF8FB4FF) else Color(0xFFD0A8FF),
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
