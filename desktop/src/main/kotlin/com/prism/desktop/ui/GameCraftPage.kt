package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.assetcraft.AssetCraft
import com.prism.launcher.assetcraft.GameCraft
import com.prism.launcher.assetcraft.MeshCamera
import com.prism.launcher.assetcraft.MeshView
import kotlinx.coroutines.delay

/**
 * GameCraft on the desktop: the same saved-game library and the same maker as the phone.
 *
 * The maker is a mode of this page, the way it is on Android, because a generated game is meant to
 * be seen immediately. The viewport is the only fixed-dark surface; everything else comes from the
 * active theme, including the two/three-dimensional switch and the chips that explain the model
 * gate. Desktop control is WASD plus the mouse: dragging orbits, and the keys drive the compiled
 * player transform, which is the same transform the phone's joysticks drive.
 */
@Composable
fun GameCraftPage() {
    val colors = LocalPrismColors.current
    var state by remember { mutableStateOf(GameCraft.state.value) }
    var prompt by remember { mutableStateOf(state.prompt) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var gamePicker by remember { mutableStateOf(false) }
    var textPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        GameCraft.state.collect { latest ->
            state = latest
            if (latest.prompt.isNotBlank() && prompt.isBlank()) prompt = latest.prompt
        }
    }

    val gate = remember(refresh, state.screen) { GameCraft.gate() }
    val games = remember(refresh, state.screen) { GameCraft.games() }
    val gameChoices = remember(refresh, gamePicker) { AssetCraft.gameChoices() }
    val textChoices = remember(refresh, textPicker) { AssetCraft.textChoices() }
    val activeMesh = remember(refresh, gameChoices) {
        val id = AssetCraft.activeGame()?.id
        gameChoices.firstOrNull { it.id == id }
    }
    val activeText = remember(refresh, textChoices) {
        val id = AssetCraft.activeText()?.id
        textChoices.firstOrNull { it.id == id }
    }

    if (state.screen == GameCraft.Screen.LIST) {
        PageScaffold(
            title = "GameCraft",
            subtitle = "Games and scenes built from your mesh generator. A text model adds compiled " +
                "movement; an image model adds a 1024 x 1024 texture.",
            actions = {
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Filled.Refresh, "Reload the game list")
                }
                IconButton(onClick = { GameCraft.beginNew() }) {
                    Icon(Icons.Filled.Add, "Make a game or mesh")
                }
            },
        ) {
            if (games.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.SportsEsports,
                            null,
                            tint = colors.faint,
                            modifier = Modifier.size(46.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Nothing made yet", fontSize = 15.sp, color = colors.muted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Press + to make a game. A mesh generator is required; the text and " +
                                "image models are optional and need agentic tools.",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(games, key = { it.id }) { game ->
                        Surface(
                            color = colors.card,
                            shape = RoundedCornerShape(colors.cardRadius),
                            modifier = Modifier.fillMaxWidth().clickableRow { GameCraft.open(game) },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(42.dp)
                                        .background(colors.surfaceRaised, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.SportsEsports,
                                        null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(game.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        buildString {
                                            append("${game.triangles} triangles")
                                            if (game.hasTexture) append(" · 1024 texture")
                                            if (game.hasCode) append(" · compiled code")
                                            if (game.isScene) append(" · scene")
                                        },
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = {
                                    if (GameCraft.delete(game)) refresh++ else message = "Could not delete that folder."
                                }) {
                                    Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (message.isNotBlank()) {
                Text(message, fontSize = 11.sp, color = colors.destructive, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }

    PageScaffold(
        title = if (state.gameName.isBlank()) "New game" else state.gameName,
        subtitle = "A mesh is the minimum. Add a text model for compiled movement and an image " +
            "model for a texture; both are gated on Agentic Tools.",
        actions = {
            IconButton(onClick = { GameCraft.showList(); refresh++ }) {
                Icon(Icons.Filled.ArrowBack, "Back to the game library")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ModelChip("MESH GENERATOR", gate.meshName.ifBlank { "None active" }, gate.ready) { gamePicker = true }
                Spacer(Modifier.width(10.dp))
                ModelChip("TEXT · CODE", gate.textName.ifBlank { "None active" }, gate.canCode) { textPicker = true }
                Spacer(Modifier.width(10.dp))
                ModelChip("IMAGE · TEXTURE", gate.imageName.ifBlank { "None active" }, gate.canTexture) {}
                Spacer(Modifier.weight(1f))
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickableRow { GameCraft.setView3d(!state.view3d) },
                ) {
                    Text(
                        if (state.view3d) "3D" else "2D",
                        fontSize = 11.sp,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Surface(
                color = Color(0xFF0D1119),
                shape = RoundedCornerShape(colors.cardRadius),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    GameViewport(state = state, modifier = Modifier.fillMaxSize())
                    if (!gate.ready) {
                        Surface(
                            color = colors.card.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("A mesh generator is required", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(gate.reason, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
                            }
                        }
                    }
                    if (state.isEmpty && gate.ready) {
                        Text(
                            "The viewport is empty. Describe a game and press Generate.",
                            fontSize = 12.sp,
                            color = Color(0xFF7F8EA3),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (state.controllable) {
                        Text(
                            "WASD move · mouse drag to orbit",
                            fontSize = 10.sp,
                            color = Color(0xFF7F8EA3),
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        )
                    }
                }
            }

            if (!gate.canCode && gate.textName.isNotBlank()) {
                Text(gate.informative, fontSize = 11.sp, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
            }

            Surface(
                color = colors.surfaceRaised,
                shape = RoundedCornerShape(colors.fieldRadius),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
                                "Describe the game: a flooded cathedral with a controllable skiff…",
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

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (gate.ready && !state.generating) colors.accent else colors.surface,
                    shape = RoundedCornerShape(colors.buttonRadius),
                    modifier = Modifier.then(
                        if (gate.ready && !state.generating) Modifier.clickableRow {
                            if (!GameCraft.generate(prompt)) {
                                message = GameCraft.state.value.error ?: "The generator refused that prompt."
                            }
                        } else Modifier,
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            null,
                            tint = if (gate.ready) Color(0xFF0D0D10) else colors.faint,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.generating) "Generating…" else "Generate",
                            fontSize = 13.sp,
                            color = if (gate.ready) Color(0xFF0D0D10) else colors.faint,
                        )
                    }
                }
                if (state.binary.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    // The compiled game is the point. Making the user find game.exe in a documents
                    // folder to see whether their game works is not a product.
                    Surface(
                        color = colors.surfaceRaised,
                        shape = RoundedCornerShape(colors.buttonRadius),
                        modifier = Modifier.clickableRow {
                            runCatching {
                                ProcessBuilder(state.binary)
                                    .directory(java.io.File(state.binary).parentFile)
                                    .start()
                            }
                        },
                    ) {
                        Text(
                            "Play",
                            fontSize = 13.sp,
                            color = colors.onSurface,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        state.generating -> state.stage
                        state.error != null -> "Failed: ${state.error}"
                        state.note.isNotBlank() -> state.note
                        message.isNotBlank() -> message
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = if (state.error != null) colors.destructive else colors.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.generating) {
                Spacer(Modifier.height(6.dp))
                PrismProgressBar(state.progress)
            }
        }
    }

    if (gamePicker) {
        ModelPicker(
            title = "Mesh generator",
            subtitle = "ModelCraft game models are registered automatically. Pick the one this game should use.",
            choices = gameChoices,
            activeId = activeMesh?.id,
        ) { choice ->
            AssetCraft.setActiveGame(choice)
            refresh++
            gamePicker = false
        }
    }
    if (textPicker) {
        ModelPicker(
            title = "Text model for code",
            subtitle = "Only used when Agentic Tools is on. It writes the C++ movement script.",
            choices = textChoices,
            activeId = activeText?.id,
        ) { choice ->
            AssetCraft.setActiveText(choice)
            refresh++
            textPicker = false
        }
    }
}

@Composable
private fun ModelChip(label: String, value: String, ready: Boolean, onClick: () -> Unit) {
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
                    Modifier.size(6.dp)
                        .background(if (ready) colors.accent else colors.destructive, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    value,
                    fontSize = 12.sp,
                    color = if (ready) colors.onSurface else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(148.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    title: String,
    subtitle: String,
    choices: List<AssetCraft.Choice>,
    activeId: String?,
    onPick: (AssetCraft.Choice) -> Unit,
) {
    val colors = LocalPrismColors.current
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        confirmButton = { TextButton(onClick = { dismissed = true }) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column(Modifier.width(460.dp).heightIn(max = 360.dp)) {
                Text(subtitle, fontSize = 12.sp, color = colors.faint, lineHeight = 17.sp)
                Spacer(Modifier.height(8.dp))
                if (choices.isEmpty()) {
                    Text("No models available yet.", fontSize = 12.sp, color = colors.muted)
                } else {
                    LazyColumn {
                        items(choices) { choice ->
                            Row(
                                Modifier.fillMaxWidth().clickableRow { onPick(choice) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (choice.id == activeId) {
                                    Icon(Icons.Filled.Check, "Active", tint = colors.accent, modifier = Modifier.size(17.dp))
                                } else {
                                    Spacer(Modifier.size(17.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(choice.name, fontSize = 13.sp)
                                    Text(choice.detail, fontSize = 11.sp, color = colors.faint, maxLines = 1)
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
private fun GameViewport(state: GameCraft.State, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.controllable) {
        if (state.controllable) runCatching { focus.requestFocus() }
    }
    Canvas(
        modifier
            .background(Color(0xFF0D1119))
            .pointerInput(state.view3d) {
                detectDragGestures { change, drag ->
                    GameCraft.orbit(drag.x, drag.y)
                    change.consume()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val dt = 1f / 40f
                when (event.key) {
                    Key.W -> GameCraft.control(0f, -1f, 0f, dt)
                    Key.S -> GameCraft.control(0f, 1f, 0f, dt)
                    Key.A -> GameCraft.control(-1f, 0f, 0f, dt)
                    Key.D -> GameCraft.control(1f, 0f, 0f, dt)
                    Key.Q -> GameCraft.control(0f, 0f, -1f, dt)
                    Key.E -> GameCraft.control(0f, 0f, 1f, dt)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        val camera = if (state.view3d) state.camera else MeshCamera(yaw = 0f, pitch = 0f, distance = 3.6f)
        // ONE BITMAP, NOT ONE PATH PER TRIANGLE. A game asset is 80,000 triangles at the floor and
        // the model often returns three times that; building a Compose Path for every face meant
        // every drag or WASD step paid tens of thousands of allocations and draw calls. The shared
        // rasteriser fills a pixel buffer and the canvas blits it once, which is the difference
        // between a slideshow and a playable viewport.
        val pixels = MeshView.render(state.scene, width, height, camera, 0f, -1)
        val bitmap = com.prism.core.PrismImage(width, height, pixels).toComposeBitmap()
        drawImage(
            bitmap,
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = androidx.compose.ui.unit.IntSize(width, height),
            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
            dstSize = androidx.compose.ui.unit.IntSize(width, height),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )
    }
}
