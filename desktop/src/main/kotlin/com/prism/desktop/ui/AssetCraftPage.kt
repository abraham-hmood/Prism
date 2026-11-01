package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.assetcraft.AssetCraft
import com.prism.launcher.assetcraft.MeshView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * AssetCraft: an asset viewport with a prompt under it.
 *
 * The viewport is the page. It holds the largest share of the window, it is orbitable with a drag,
 * zoomable with the wheel buttons, and clicking an object selects it for deletion and for the stats
 * line above the prompt. Only below it do the two model selectors and the prompt live -- the same
 * "canvas first, field under the thumb" arrangement ImageCraft uses.
 *
 * The model gate is drawn rather than implied: when either the game model or the text model is
 * missing, the reason is on screen and Generate is disabled, instead of a button that fails.
 */
@Composable
fun AssetCraftPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(AssetCraft.state.value) }
    var prompt by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var enhancing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var gamePicker by remember { mutableStateOf(false) }
    var textPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AssetCraft.state.collect { state = it }
    }
    // THE CLOCK LIVES IN THE PAGE. AssetCraft exposes tick() rather than running a ticker so a page
    // that is not visible cannot animate, and the same page drives the same clock on both platforms.
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        var previous = System.currentTimeMillis()
        while (true) {
            delay(33)
            val now = System.currentTimeMillis()
            AssetCraft.tick(((now - previous) / 1000f).coerceAtMost(0.1f))
            previous = now
        }
    }

    val gameChoices = remember(refresh) { AssetCraft.gameChoices() }
    val textChoices = remember(refresh) { AssetCraft.textChoices() }
    val activeGame = remember(refresh, gameChoices) {
        val id = AssetCraft.activeGame()?.id
        gameChoices.firstOrNull { it.id == id }
    }
    val activeText = remember(refresh, textChoices) {
        val id = AssetCraft.activeText()?.id
        textChoices.firstOrNull { it.id == id }
    }
    val gate = remember(refresh, activeGame, activeText) { AssetCraft.gate() }

    PageScaffold(
        title = "AssetCraft",
        subtitle = "Game assets, generated and posed in a viewport. Both an active game model and an " +
            "active text model are required.",
        actions = {
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Filled.Refresh, "Reload the model list")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelSelector(
                    label = "GAME MODEL",
                    choice = activeGame,
                    empty = "None active",
                    ready = gate.gameReady,
                ) { gamePicker = true }
                Spacer(Modifier.width(10.dp))
                ModelSelector(
                    label = "TEXT MODEL · MAGIC WAND",
                    choice = activeText,
                    empty = "None active",
                    ready = gate.textReady,
                ) { textPicker = true }
                Spacer(Modifier.weight(1f))
                if (!state.isEmptyScene) {
                    Text(
                        "${state.scene.nodes.size} object${if (state.scene.nodes.size == 1) "" else "s"}" +
                            " · ${state.scene.nodes.sumOf { it.mesh.triangleCount }} triangles",
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
            }

            Surface(
                color = Color(0xFF0D1119),
                shape = RoundedCornerShape(colors.cardRadius),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    AssetViewport(
                        stateScene = state,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (gate.gameReady.not() || gate.textReady.not()) {
                        Surface(
                            color = colors.card.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Two models are required", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(gate.reason, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
                            }
                        }
                    }
                    if (state.isEmptyScene && gate.ready) {
                        Text(
                            "The viewport is empty. Describe an asset and press Generate.",
                            fontSize = 12.sp,
                            color = Color(0xFF7F8EA3),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ViewportButton(Icons.Filled.Remove, "Zoom out") { AssetCraft.zoom(1.15f) }
                        Spacer(Modifier.width(4.dp))
                        ViewportButton(Icons.Filled.Add, "Zoom in") { AssetCraft.zoom(0.87f) }
                        Spacer(Modifier.width(4.dp))
                        ViewportButton(Icons.Filled.SportsEsports, "Reset camera") { AssetCraft.resetCamera() }
                    }
                    Text(
                        "Drag to orbit · tap an object to select it · animation follows its own kind",
                        fontSize = 10.sp,
                        color = Color(0xFF7F8EA3),
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }
            }

            // THE PROMPT, WITH THE WAND BESIDE IT. The wand acts on the field, so it is an icon
            // button attached to the field rather than a labelled button in the action row.
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(colors.fieldRadius),
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (prompt.isBlank()) {
                                Text(
                                    "Describe the game asset: a mossy rock, a ruined tower, a crystal shard…",
                                    fontSize = 14.sp,
                                    color = colors.faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(colors.fieldRadius),
                    modifier = Modifier.size(48.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (enhancing) return@IconButton
                            if (prompt.isBlank()) {
                                message = "Type a prompt first, then press the wand."
                                return@IconButton
                            }
                            if (!AssetCraft.canEnhance()) {
                                message = "No text model is active, so the wand has nothing to ask."
                                return@IconButton
                            }
                            enhancing = true
                            scope.launch {
                                val rewritten = runCatching { AssetCraft.enhance(prompt) }.getOrNull()
                                enhancing = false
                                if (rewritten.isNullOrBlank()) {
                                    message = "The text model had nothing usable — your words are unchanged."
                                } else {
                                    prompt = rewritten
                                    message = "Rewritten by the active text model. Edit it, then Generate."
                                }
                            }
                        },
                        enabled = AssetCraft.canEnhance() && !enhancing,
                    ) {
                        Icon(
                            Icons.Filled.AutoFixHigh,
                            "Rewrite the prompt with the active text model",
                            tint = colors.accent,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    icon = Icons.Filled.Add,
                    label = "Generate",
                    enabled = gate.ready && !state.running,
                    accent = true,
                ) {
                    if (!AssetCraft.start(prompt)) {
                        val error = AssetCraft.state.value.error
                        if (!error.isNullOrBlank()) message = error
                    }
                }
                Spacer(Modifier.width(8.dp))
                ActionButton(
                    icon = Icons.Filled.PlayArrow,
                    label = if (state.playing) "Pause" else "Animate",
                    enabled = !state.isEmptyScene,
                ) { AssetCraft.togglePlay() }
                Spacer(Modifier.width(8.dp))
                ActionButton(
                    icon = Icons.Filled.Delete,
                    label = "Delete selected",
                    enabled = state.selectedNode != null,
                ) { AssetCraft.removeSelected() }
                Spacer(Modifier.width(8.dp))
                ActionButton(
                    icon = Icons.Filled.Refresh,
                    label = "Clear scene",
                    enabled = !state.isEmptyScene,
                ) { AssetCraft.clearScene() }
                Spacer(Modifier.weight(1f))
                state.selectedNode?.let { node ->
                    Text(
                        "${node.mesh.name} · ${node.mesh.category} · ${node.mesh.animation.label}",
                        fontSize = 11.sp,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val status = when {
                state.running -> state.stage
                state.error != null -> "Failed: ${state.error}"
                state.complete -> "Generated in ${"%.1f".format(state.millis / 1000.0)}s" +
                    if (state.note.isNotBlank()) " · ${state.note}" else ""
                message.isNotBlank() -> message
                else -> ""
            }
            Text(
                status,
                fontSize = 11.sp,
                color = if (state.error != null) colors.destructive else colors.faint,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    if (gamePicker) {
        ModelPickerDialog(
            title = "Game asset model",
            subtitle = "Only a trained game model or a readable mesh can generate. Import an .obj, .ply or .glb file to add one.",
            choices = gameChoices,
            activeId = activeGame?.id,
            empty = "No game models yet. Train one in ModelCraft's Game tab, or import a mesh.",
            onPick = { choice ->
                AssetCraft.setActiveGame(choice)
                refresh++
                gamePicker = false
            },
            onImport = {
                val files = pickFiles(
                    AssetCraftGameExtensions,
                    "Import game asset models",
                )
                if (files.isNotEmpty()) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { AssetCraft.importGame(files) }
                        message = result.summary
                        refresh++
                    }
                }
            },
            onDismiss = { gamePicker = false },
        )
    }

    if (textPicker) {
        ModelPickerDialog(
            title = "Text model for the wand",
            subtitle = "The wand asks this model to rewrite your prompt. It is never used for the mesh itself.",
            choices = textChoices,
            activeId = activeText?.id,
            empty = "No text models yet. Import a GGUF on the Models page, or train one in ModelCraft.",
            onPick = { choice ->
                AssetCraft.setActiveText(choice)
                refresh++
                textPicker = false
            },
            onImport = {
                val files = pickFiles(
                    setOf("gguf", "task", "bin", "onnx", "safetensors"),
                    "Import text models",
                )
                if (files.isNotEmpty()) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { AssetCraft.importText(files) }
                        message = result.summary
                        refresh++
                    }
                }
            },
            onDismiss = { textPicker = false },
        )
    }
}

@Composable
private fun AssetViewport(
    stateScene: AssetCraft.State,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .background(Color(0xFF0D1119))
            .pointerInput(stateScene.scene.nodes.size) {
                detectDragGestures { change, drag ->
                    AssetCraft.orbit(drag.x, drag.y)
                    change.consume()
                }
            }
            .pointerInput(stateScene.scene.nodes.size) {
                detectTapGestures { offset ->
                    AssetCraft.pick(offset.x, offset.y, size.width, size.height)
                }
            },
    ) {
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        val frame = MeshView.frame(
            stateScene.scene,
            stateScene.camera,
            width,
            height,
            stateScene.time,
            stateScene.selected,
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(frame.backgroundTop), Color(frame.backgroundBottom)),
            ),
        )
        for (line in frame.grid) {
            drawLine(
                Color(line.color),
                Offset(line.x1, line.y1),
                Offset(line.x2, line.y2),
                strokeWidth = line.width,
            )
        }
        for (face in frame.faces) {
            val path = Path().apply {
                moveTo(face.xs[0], face.ys[0])
                lineTo(face.xs[1], face.ys[1])
                lineTo(face.xs[2], face.ys[2])
                close()
            }
            drawPath(path, Color(face.color))
        }
        for (edge in frame.edges) {
            drawLine(
                Color(edge.color),
                Offset(edge.x1, edge.y1),
                Offset(edge.x2, edge.y2),
                strokeWidth = 1.2f,
            )
        }
        // A soft contact shadow under each object, which is what stops a rock floating in a void.
        for (point in frame.centers) {
            drawOval(
                color = Color(0x33000000),
                topLeft = Offset(point.x - 28f, height - 46f),
                size = androidx.compose.ui.geometry.Size(56f, 12f),
            )
        }
        if (stateScene.scene.nodes.isEmpty()) {
            drawCircle(
                color = Color(0xFF243246),
                radius = 3f,
                center = Offset(width / 2f, height / 2f),
            )
        }
    }
}

@Composable
private fun ModelSelector(
    label: String,
    choice: AssetCraft.Choice?,
    empty: String,
    ready: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Column {
        Text(label, fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.faint)
        Spacer(Modifier.height(3.dp))
        Surface(
            color = if (ready) colors.surfaceRaised else colors.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickableRow(onClick),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(6.dp).background(
                        if (ready) colors.accent else colors.destructive,
                        RoundedCornerShape(50),
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    choice?.name ?: empty,
                    fontSize = 12.sp,
                    color = if (choice != null) colors.onSurface else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    title: String,
    subtitle: String,
    choices: List<AssetCraft.Choice>,
    activeId: String?,
    empty: String,
    onPick: (AssetCraft.Choice) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPrismColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = onImport) {
                Icon(Icons.Filled.Folder, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Import")
            }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 420.dp)) {
                Text(subtitle, fontSize = 12.sp, color = colors.faint, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                if (choices.isEmpty()) {
                    Text(empty, fontSize = 12.sp, color = colors.muted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(choices) { choice ->
                            val selected = choice.id == activeId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickableRow { onPick(choice) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        "Active",
                                        tint = colors.accent,
                                        modifier = Modifier.size(17.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(17.dp))
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(choice.name, fontSize = 13.sp)
                                    Text(
                                        choice.detail,
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(
        color = when {
            !enabled -> colors.surface
            accent -> colors.accent
            else -> colors.surfaceRaised
        },
        shape = RoundedCornerShape(colors.buttonRadius),
        modifier = Modifier.then(if (enabled) Modifier.clickableRow(onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                null,
                tint = when {
                    !enabled -> colors.faint
                    accent -> Color(0xFF0D0D10)
                    else -> colors.muted
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 12.sp,
                color = when {
                    !enabled -> colors.faint
                    accent -> Color(0xFF0D0D10)
                    else -> colors.onSurface
                },
            )
        }
    }
}

@Composable
private fun ViewportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(0xAA1E2A3A),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.clickableRow(onClick),
    ) {
        Icon(
            icon,
            description,
            tint = Color(0xFFD5E2F0),
            modifier = Modifier.padding(6.dp).size(15.dp),
        )
    }
}

/** The extensions the game picker offers. Kept beside the page so the chooser filter is data-driven. */
private val AssetCraftGameExtensions = setOf("obj", "ply", "glb")

private fun pickFiles(extensions: Set<String>, title: String): List<File> {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        addChoosableFileFilter(
            FileNameExtensionFilter(
                extensions.joinToString(", ") { it.uppercase() },
                *extensions.toTypedArray(),
            ),
        )
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFiles.toList()
    } else {
        emptyList()
    }
}
