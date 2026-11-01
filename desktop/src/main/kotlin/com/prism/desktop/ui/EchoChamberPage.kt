package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.EchoDesktopAudio
import com.prism.launcher.PrismSettings
import com.prism.launcher.minigames.Echo
import com.prism.launcher.minigames.EchoAssets
import com.prism.launcher.minigames.EchoMonster
import com.prism.launcher.minigames.MinigameMesh
import com.prism.launcher.minigames.MinigameStore
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The before-match screen. Difficulty and hunter count are stored in the same settings the phone
 * uses, so a preference chosen in one build is the preference the other starts with.
 */
@Composable
fun EchoChamberMenu(onStart: (MinigameMesh.Match?) -> Unit) {
    val colors = LocalPrismColors.current
    var seeking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf(PrismSettings.getEchoChamberDifficulty()) }
    var hunters by remember { mutableStateOf(PrismSettings.getEchoChamberEnemies()) }

    DisposableEffect(Unit) {
        onDispose { if (seeking) MinigameMesh.stopSeeking() }
    }
    LaunchedEffect(seeking) {
        if (!seeking) return@LaunchedEffect
        while (isActive && seeking) {
            MinigameMesh.reannounce()
            delay(2_000)
        }
    }

    PageScaffold("Echo Chamber", "A dark maze, a loud you, and hunters that listen") {
        Column(Modifier.padding(bottom = 24.dp)) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Nothing is visible until your own sound reaches it. A shout lights only the patches of wall it touches, and the bright marks it leaves are breadcrumbs the hunters can follow. Reach the magenta exit, or tag and kill every hunter between you and it.",
                        fontSize = 13.sp, color = colors.muted, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The microphone is the shout. SPACE is the same shout for a machine without one.",
                        fontSize = 11.sp, color = colors.faint,
                    )
                }
            }
            SectionHeader("difficulty")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Echo.DIFFICULTY_LABELS.forEachIndexed { index, label ->
                            EchoChip(label, index == difficulty) {
                                difficulty = index
                                PrismSettings.setEchoChamberDifficulty(index)
                            }
                        }
                    }
                }
            }
            SectionHeader("hunters")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        (1..5).forEach { count ->
                            EchoChip(count.toString(), count == hunters) {
                                hunters = count
                                PrismSettings.setEchoChamberEnemies(count)
                            }
                        }
                    }
                }
            }
            SectionHeader("begin")
            Card {
                Column(Modifier.padding(14.dp)) {
                    androidx.compose.material3.Button(
                        onClick = { onStart(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enter the dark", fontSize = 13.sp) }
                    if (MinigameMesh.isAvailable()) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                seeking = true
                                note = "Looking for another player on the mesh…"
                                MinigameMesh.seek(MinigameMesh.GAME_ECHO) { match ->
                                    seeking = false
                                    MinigameMesh.stopSeeking()
                                    note = ""
                                    onStart(match)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (seeking) "Looking…" else "Play someone on the mesh", fontSize = 13.sp) }
                    } else {
                        Text(
                            "Not on the mesh. Offline is fully playable; the hunters are simulated here.",
                            fontSize = 11.sp, color = colors.faint,
                        )
                    }
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(note, fontSize = 11.sp, color = colors.accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Best ${MinigameStore.echoBest()} · ${MinigameStore.echoRecord().first} escaped, " +
                            "${MinigameStore.echoRecord().second} lost",
                        fontSize = 11.sp, color = colors.faint,
                    )
                }
            }
            SectionFooter(
                "Walls, floor, ceiling and both 3D models are generated at 1024². A custom pack can " +
                    "override any of wall/floor/ceiling/enemy/exit .png and the two .obj models; " +
                    "Prism Settings → Minigames → Echo Chamber points at the folder.",
            )
        }
    }
}

@Composable
private fun EchoChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (active) colors.accent else Color(0xFF23232B),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(end = 6.dp).clickableRow(onClick),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (active) Color.White else Color(0xFFB8B8D8),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Echo Chamber on the desktop.
 *
 * The world is the phone's [Echo.Session]. What differs is the input and the presentation: WASD and
 * mouse look replace the sticks, and the walls are Compose rectangles sampled from the same 1024²
 * texture arrays. There is no OpenGL context here because the page lives inside the Compose window.
 */
@Composable
fun EchoChamberGame(
    match: MinigameMesh.Match?,
    options: Echo.StartOptions,
    onFinished: (won: Boolean, score: Int) -> Unit,
) {
    val session = remember(match, options) { Echo.Session(match, options) }
    val focus = remember { FocusRequester() }
    val held = remember { HashSet<Key>() }
    var mouseTurn by remember { mutableStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }
    var pack by remember { mutableStateOf<EchoAssets.Pack?>(null) }
    var monster by remember { mutableStateOf<ImageBitmap?>(null) }
    var showHelp by remember { mutableStateOf(true) }
    // The world is a plain object, not snapshot state. This mirrors the one field the screen needs
    // to react to, so the results card and the scare are not waiting on a recomposition that never
    // comes.
    var gameStatus by remember { mutableStateOf(Echo.Status.RUNNING) }
    val audio = remember { EchoDesktopAudio() }

    DisposableEffect(session) {
        onDispose {
            audio.close()
            session.close()
        }
    }

    LaunchedEffect(Unit) {
        EchoAssets.imageLoader = EchoAssets.EchoImageLoader { file ->
            runCatching {
                val image = ImageIO.read(file) ?: return@runCatching null
                val pixels = IntArray(image.width * image.height)
                image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                image.flush()
                pixels
            }.getOrNull()
        }
        val loaded = withContext(Dispatchers.IO) { runCatching { EchoAssets.pack }.getOrNull() }
        pack = loaded
        monster = loaded?.let { art ->
            withContext(Dispatchers.IO) { runCatching { art.enemySprite.toHunterBitmap() }.getOrNull() }
        }
        audio.start()
        focus.requestFocus()
    }

    LaunchedEffect(session) {
        var last = 0L
        var scared = false
        while (isActive && session.state.status == Echo.Status.RUNNING) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(0.08)
                last = now
                val shout = Key.Spacebar in held || audio.level > PrismSettings.getEchoChamberMicSensitivity()
                val input = Echo.Input(
                    forward = if (Key.W in held || Key.DirectionUp in held) 1.0 else if (Key.S in held || Key.DirectionDown in held) -1.0 else 0.0,
                    strafe = if (Key.D in held || Key.DirectionRight in held) 1.0 else if (Key.A in held || Key.DirectionLeft in held) -1.0 else 0.0,
                    turn = mouseTurn.toDouble(),
                    shout = shout,
                    strike = Key.E in held,
                )
                mouseTurn = 0f
                val before = session.state.lastPing
                session.update(dt, input)
                if (session.state.lastPing !== before && session.state.lastPing != null) {
                    audio.ping(0.8)
                }
                if (input.strike) session.reportStrike()
                if (session.state.dying && !scared) {
                    scared = true
                    audio.scare()
                }
                if (session.state.status != gameStatus) gameStatus = session.state.status
                frame++
            }
        }
    }

    val world = session.state
    LaunchedEffect(gameStatus) {
        if (gameStatus != Echo.Status.RUNNING) {
            MinigameStore.recordEcho(gameStatus == Echo.Status.WON, world.score)
            // The jumpscare has already played by the time the status flips; give the results card
            // a moment to be read before the page returns to its menu.
            delay(2400)
            onFinished(gameStatus == Echo.Status.WON, world.score)
        }
    }

    val colors = LocalPrismColors.current
    Surface(color = Color(0xFF030108), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        held.add(event.key)
                        if (event.key == Key.Escape) showHelp = !showHelp
                        if (event.key == Key.P) showHelp = !showHelp
                        true
                    } else if (event.type == KeyEventType.KeyUp) {
                        held.remove(event.key)
                        true
                    } else false
                }
                .pointerInput(Unit) {
                    detectTapGestures { focus.requestFocus() }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var lastX: Float? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Move) {
                                val x = event.changes.firstOrNull()?.position?.x
                                if (x != null) {
                                    val delta = if (lastX == null) 0f else x - lastX!!
                                    lastX = x
                                    // A full window width is roughly a quarter turn; that is slow
                                    // enough to aim and fast enough to spin when something growls.
                                    mouseTurn = (mouseTurn + delta / size.width * 3.2f).toFloat()
                                }
                            } else if (event.type == PointerEventType.Exit) {
                                lastX = null
                            }
                        }
                    }
                },
        ) {
            val art = pack
            if (art == null) {
                Text(
                    "ECHO CHAMBER\ncalibrating 1024² surfaces…",
                    color = Color(0xFF39E7FF),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    // Reading the frame counter inside the draw scope is what makes the canvas
                    // redraw as the simulation advances; the world itself is not observable.
                    @Suppress("UNUSED_EXPRESSION") frame
                    drawEchoScene(session.state, art, monster)
                }
                EchoHud(world, match)
                if (showHelp) {
                    Surface(
                        color = Color(0xDD08040F),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.align(Alignment.Center).padding(22.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("ECHO CHAMBER", color = Color(0xFF39E7FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The maze is black until sound touches it.\n\n" +
                                    "WASD / arrows   move\nmouse           look\nSPACE           shout (microphone also works)\nE               strike the nearest hunter\nP / ESC         hide this card\n\n" +
                                    "Every shout reveals you to the hunters. Find the magenta exit, or clear them out.",
                                color = Color(0xFFB8B8D8),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("click the page to focus it", color = Color(0xFF8A8AB0), fontSize = 10.sp)
                        }
                    }
                }
                if (gameStatus != Echo.Status.RUNNING) {
                    Surface(
                        color = Color(0xEE05020C),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (gameStatus == Echo.Status.WON) "YOU FOUND THE EXIT" else "THE DARK TOOK YOU",
                                color = if (gameStatus == Echo.Status.WON) Color(0xFF9DFF3C) else Color(0xFFFF3B5C),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "score ${world.score} · ${world.kills} killed · ${world.tags} tagged",
                                color = Color(0xFFB8B8D8),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One frame of the world. Kept outside the composable so it is testable and cheap to read. */
private fun DrawScope.drawEchoScene(world: Echo.World, art: EchoAssets.Pack, monster: ImageBitmap?) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.47f
    // PITCH BLACK. No floor grid and no wall pass: the maze is only drawn where sound has
    // touched it. The marks below are the entire geometry budget of a frame.
    drawRect(Color(0xFF020106), topLeft = Offset.Zero, size = size)

    // Sound wavefronts.
    world.ripples.forEach { ripple ->
        val life = ripple.maxRadius / ripple.speed
        val t = (ripple.age / life).coerceIn(0.0, 1.0)
        if (t >= 1.0) return@forEach
        val radius = (w * 0.46f * t + 10f).toFloat()
        drawCircle(
            Color(EchoAssets.hsl(ripple.hue.toDouble(), 0.9, 0.6)).copy(alpha = ((1 - t) * 0.36).toFloat()),
            radius = radius,
            center = Offset(w / 2f, horizon),
            style = Stroke(width = 2f),
        )
    }

    // Marks on the walls, projected the same way as everything else. A mark belongs to a surface,
    // so a wall in front of it hides it.
    // The marks are permanent, so only the cells around the player are visited. Every mark is
    // drawn at full strength: an old shout still paints the wall it reached.
    var marksDrawn = 0
    world.forEachNearbyMark(world.playerX, world.playerY, 8, 2400) { mark ->
        if (marksDrawn >= 160) return@forEachNearbyMark false
        val dx = mark.x - world.playerX
        val dy = mark.y - world.playerY
        val distance = hypot(dx, dy)
        if (distance < 0.2 || distance > 25.0) return@forEachNearbyMark true
        var relative = atan2(dy, dx) - world.playerAngle
        while (relative > Math.PI) relative -= Math.PI * 2
        while (relative < -Math.PI) relative += Math.PI * 2
        if (abs(relative) > Echo.FOV / 2 + 0.12) return@forEachNearbyMark true
        val wall = world.maze.cast(world.playerX, world.playerY, atan2(dy, dx), 28.0)
        if (wall != null && wall.dist < distance - 0.12) return@forEachNearbyMark true
        val sx = (w / 2.0 + tan(relative) / tan(Echo.FOV / 2) * (w / 2)).toFloat()
        val sy = horizon - (h * 0.10f / distance.toFloat())
        val radius = (h / distance * 0.075).toFloat().coerceIn(2f, 22f)
        val color = Color(EchoAssets.hsl(mark.hue.toDouble(), 0.95, 0.62))
        // A crescent of the wavefront, centred on the shout that made it. The curvature is what
        // says "this is sound" rather than "a dot was drawn here".
        val rotation = Math.toDegrees(atan2(mark.originY - mark.y, mark.originX - mark.x)).toFloat()
        val swept = radius
        drawArc(
            color = color.copy(alpha = 0.28f),
            startAngle = rotation + 55f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(sx - swept, sy - swept),
            size = Size(swept * 2f, swept * 2f),
            style = Stroke(width = (radius * 1.15f).coerceAtLeast(3.4f)),
        )
        drawArc(
            color = color.copy(alpha = 0.9f),
            startAngle = rotation + 55f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(sx - swept, sy - swept),
            size = Size(swept * 2f, swept * 2f),
            style = Stroke(width = (radius * 0.55f).coerceAtLeast(1.6f)),
        )
        val echoColor = Color(EchoAssets.hsl((mark.hue + 42) % 360.0, 0.95, 0.68))
        val echoR = swept * 1.9f
        drawArc(
            color = echoColor.copy(alpha = 0.4f),
            startAngle = rotation - 18f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(sx - echoR, sy - echoR),
            size = Size(echoR * 2f, echoR * 2f),
            style = Stroke(width = (radius * 0.32f).coerceAtLeast(1f)),
        )
        marksDrawn++
        true
    }

    // Models, far to near. The enemy is only on screen while a sound says it is there.
    val entities = ArrayList<Pair<Double, () -> Unit>>()
    world.enemies.forEach { enemy ->
        if (!enemy.alive || world.clock > enemy.visibleUntil) return@forEach
        val dx = enemy.x - world.playerX
        val dy = enemy.y - world.playerY
        val distance = hypot(dx, dy)
        if (distance > 20.0) return@forEach
        val wall = world.maze.cast(world.playerX, world.playerY, atan2(dy, dx), 28.0)
        if (wall != null && wall.dist < distance - 0.16) return@forEach
        entities.add(distance to {
            val body = EchoMonster.billboard(
                enemy.x, enemy.y, world.playerX, world.playerY, world.playerAngle,
                Echo.FOV, w.toInt(), h.toInt(), scale = 0.92,
            )
            val fog = (1.0 - distance / 26.0).coerceIn(0.42, 1.0).toFloat()
            if (body != null && monster != null) {
                drawEchoHunter(body, monster, enemy.gait, world.clock, enemy.id, alpha = fog)
            } else {
                drawEchoFaces(
                    EchoAssets.project(
                        art.enemyMesh, enemy.x, enemy.y, 0.18,
                        world.playerX, world.playerY, world.playerAngle,
                        Echo.FOV, w.toInt(), h.toInt(), scale = 0.86,
                    ),
                    0xFF39E7FF.toInt(),
                )
            }
        })
    }
    world.peer?.let { peer ->
        if (peer.alive && world.clock <= peer.visibleUntil) {
            val distance = hypot(peer.x - world.playerX, peer.y - world.playerY)
            entities.add(distance to {
                drawEchoFaces(
                    EchoAssets.project(
                        art.enemyMesh, peer.x, peer.y, 0.16,
                        world.playerX, world.playerY, world.playerAngle,
                        Echo.FOV, w.toInt(), h.toInt(), scale = 0.92,
                    ),
                    0xFF9DFF3C.toInt(),
                )
            })
        }
    }
    if (world.clock <= world.exitVisibleUntil) {
        val distance = hypot(world.maze.exitX - world.playerX, world.maze.exitY - world.playerY)
        entities.add(distance to {
            drawEchoFaces(
                EchoAssets.project(
                    art.exitMesh, world.maze.exitX, world.maze.exitY, 0.62,
                    world.playerX, world.playerY, world.playerAngle,
                    Echo.FOV, w.toInt(), h.toInt(), scale = 1.0,
                ),
                0xFFFF2BD6.toInt(),
            )
        })
    }
    entities.sortByDescending { it.first }
    entities.forEach { it.second() }

    if (world.damageFlash > 0.0) {
        drawRect(Color(0xFFFF1744).copy(alpha = (world.damageFlash * 0.45).toFloat()))
    }

    // The minimap: only cells a sound has crossed. An unexplored maze is a black square.
    val mapSize = 108f
    val mapLeft = w - mapSize - 18f
    val mapTop = 18f
    drawRoundRect(
        Color(0xCC020107), Offset(mapLeft, mapTop), Size(mapSize, mapSize),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
    )
    val cell = mapSize / world.maze.size
    for (y in 0 until world.maze.size) for (x in 0 until world.maze.size) {
        if (!world.isRevealed(x, y)) continue
        drawRect(
            if (world.maze.wall(x, y)) Color(0xAA1B6F8A) else Color(0x55206080),
            Offset(mapLeft + x * cell, mapTop + y * cell),
            Size(cell + 0.5f, cell + 0.5f),
        )
    }
    if (world.clock <= world.exitVisibleUntil) {
        drawCircle(Color(0xFFFF2BD6), cell * 1.5f, Offset(mapLeft + world.maze.exitX.toFloat() * cell, mapTop + world.maze.exitY.toFloat() * cell))
    }
    world.enemies.forEach { enemy ->
        if (!enemy.alive || world.clock > enemy.visibleUntil) return@forEach
        drawCircle(Color(0xFFFF3B5C), cell * 1.4f, Offset(mapLeft + enemy.x.toFloat() * cell, mapTop + enemy.y.toFloat() * cell))
    }
    world.peer?.let { peer ->
        if (world.clock <= peer.visibleUntil) {
            drawCircle(Color(0xFF9DFF3C), cell * 1.4f, Offset(mapLeft + peer.x.toFloat() * cell, mapTop + peer.y.toFloat() * cell))
        }
    }
    drawCircle(Color.White, cell * 1.4f, Offset(mapLeft + world.playerX.toFloat() * cell, mapTop + world.playerY.toFloat() * cell))
    drawCircle(
        Color(0xCC39E7FF), cell * 3.2f,
        Offset(mapLeft + world.playerX.toFloat() * cell, mapTop + world.playerY.toFloat() * cell),
        style = Stroke(width = 1f),
    )
    drawLine(
        Color(0xCC39E7FF),
        Offset(mapLeft + world.playerX.toFloat() * cell, mapTop + world.playerY.toFloat() * cell),
        Offset(
            mapLeft + (world.playerX + cos(world.playerAngle) * 3.2).toFloat() * cell,
            mapTop + (world.playerY + sin(world.playerAngle) * 3.2).toFloat() * cell,
        ),
        strokeWidth = 1f,
    )

    if (world.dying) drawEchoJumpScare(world, monster)
}

/**
 * The hunter arriving, on the desktop.
 *
 * Same creature as the phone: a wet billboard body with too many maws and eyes, live tentacles, a
 * central mouth that opens as the scream peaks, red flicker and torn scanlines. The only difference
 * is the canvas it is painted on.
 */
private fun DrawScope.drawEchoJumpScare(world: Echo.World, monster: ImageBitmap?) {
    val progress = (1.0 - world.jumpscare / Echo.JUMPSCARE_SECONDS).coerceIn(0.0, 1.0)
    val flicker = (progress * 90).toInt()
    drawRect(if (flicker % 2 == 0) Color(0xF2390012) else Color(0xF2080006))

    val enemy = world.enemies.firstOrNull { it.id == world.jumpscareEnemy }
        ?: world.enemies.firstOrNull { it.alive }
    val seed = enemy?.id ?: 0
    val shake = (1f - progress.toFloat() * 0.35f) * 7f
    val cx = size.width / 2f + sin(progress * 31.0).toFloat() * shake
    val cy = size.height * 0.50f + cos(progress * 37.0).toFloat() * shake * 0.65f
    val side = min(size.width, size.height) * (1.02f + progress.toFloat() * 0.93f)
    val body = EchoMonster.Billboard(
        cx, cy,
        side * EchoMonster.BODY_FRACTION,
        1.0,
    )
    rotate(degrees = sin(progress * 9.0).toFloat() * 2.7f, pivot = Offset(cx, cy)) {
        drawEchoHunter(body, monster, progress * 26.0, world.clock, seed, count = 13)
        if (progress > 0.08) {
            val mawRadius = side * (0.12f + progress.toFloat() * 0.10f)
            drawEchoGapingMaw(
                cx, cy + side * 0.035f, mawRadius,
                mawRadius * (0.72f + progress.toFloat() * 0.66f), progress,
            )
        }
    }

    var y = (progress * 7).toInt().toFloat()
    while (y < size.height) {
        drawRect(
            Color(0x66000000),
            topLeft = Offset(0f, y),
            size = Size(size.width, 3f),
        )
        y += 19f
    }
    if (progress > 0.86) {
        val fade = ((progress - 0.86) / 0.14).toFloat().coerceIn(0f, 1f)
        drawRect(Color.Black.copy(alpha = fade))
    }
}

/** One hunter in the maze, or the full-screen version at death. */
private fun DrawScope.drawEchoHunter(
    body: EchoMonster.Billboard,
    image: ImageBitmap?,
    gait: Double,
    clock: Double,
    seed: Int,
    count: Int = EchoMonster.TENTACLES,
    alpha: Float = 1f,
) {
    if (image == null) return
    val bob = sin(gait * 1.35 + seed * 0.71).toFloat() * body.radius * 0.014f +
        sin(clock * 2.4 + seed).toFloat() * body.radius * 0.006f
    val shown = body.copy(centerY = body.centerY + bob)
    drawEchoTentacles(shown, gait, clock, seed, count, alpha)

    val side = EchoMonster.spriteSide(shown.radius)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE),
        dstOffset = IntOffset(
            (shown.centerX - side / 2f).roundToInt(),
            (shown.centerY - side / 2f).roundToInt(),
        ),
        dstSize = IntSize(
            side.roundToInt().coerceAtLeast(1),
            side.roundToInt().coerceAtLeast(1),
        ),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

/** Ribbons with suckers, drawn behind the body. */
private fun DrawScope.drawEchoTentacles(
    body: EchoMonster.Billboard,
    gait: Double,
    clock: Double,
    seed: Int,
    count: Int,
    alpha: Float,
) {
    val edge = max(1f, body.radius * 0.014f)
    EchoMonster.tentacles(body, gait, clock, seed, count).forEach { tentacle ->
        val path = Path().apply {
            moveTo(tentacle.leftX[0], tentacle.leftY[0])
            for (i in 1 until tentacle.leftX.size) lineTo(tentacle.leftX[i], tentacle.leftY[i])
            for (i in tentacle.rightX.indices.reversed()) lineTo(tentacle.rightX[i], tentacle.rightY[i])
            close()
        }
        val base = if (tentacle.accent) Color(0xFF52632A) else Color(0xFF4A2468)
        drawPath(path, base.copy(alpha = alpha))
        drawPath(
            path,
            (if (tentacle.accent) Color(0xFF2E3414) else Color(0xFF24102E)).copy(alpha = alpha),
            style = Stroke(width = edge),
        )

        val centerline = Path().apply {
            moveTo(tentacle.centerX[0], tentacle.centerY[0])
            for (i in 1 until tentacle.centerX.size) lineTo(tentacle.centerX[i], tentacle.centerY[i])
        }
        drawPath(
            centerline,
            (if (tentacle.accent) Color(0xFFB3C66A) else Color(0xFFB77BD8)).copy(alpha = alpha * 0.55f),
            style = Stroke(width = max(1f, tentacle.width[tentacle.width.size / 2] * 0.20f)),
        )

        for (i in 2..6 step 2) {
            val radius = max(0.8f, tentacle.width[i] * 0.24f)
            drawCircle(
                if (tentacle.accent) Color(0xFFD7E28E).copy(alpha = alpha) else Color(0xFFE0A2EB).copy(alpha = alpha),
                radius,
                Offset(tentacle.centerX[i], tentacle.centerY[i]),
            )
            drawCircle(
                Color(0xFF2A1238).copy(alpha = alpha * 0.55f),
                radius * 0.43f,
                Offset(tentacle.centerX[i], tentacle.centerY[i]),
            )
        }
    }
}

/** The central tooth-lined maw, opening as the hunter arrives. */
private fun DrawScope.drawEchoGapingMaw(
    cx: Float,
    cy: Float,
    radius: Float,
    verticalRadius: Float,
    progress: Double,
) {
    drawOval(
        Color(0xFF0B0209),
        topLeft = Offset(cx - radius, cy - verticalRadius),
        size = Size(radius * 2, verticalRadius * 2),
    )
    drawOval(
        Color(0xFF240718),
        topLeft = Offset(cx - radius * 0.74f, cy - verticalRadius * 0.74f),
        size = Size(radius * 1.48f, verticalRadius * 1.48f),
    )
    drawOval(
        Color(0xFF7E3D75),
        topLeft = Offset(cx - radius * 0.94f, cy - verticalRadius * 0.94f),
        size = Size(radius * 1.88f, verticalRadius * 1.88f),
        style = Stroke(width = max(2f, radius * 0.045f)),
    )

    val teeth = Path()
    val count = 28
    for (i in 0 until count) {
        val angle = Math.PI * 2 * i / count
        val half = Math.PI / count * 0.52
        val tip = 0.53 + 0.06 * sin(progress * 8.0 + i * 1.7)
        teeth.moveTo(
            (cx + cos(angle - half) * radius * 0.94).toFloat(),
            (cy + sin(angle - half) * verticalRadius * 0.94).toFloat(),
        )
        teeth.lineTo(
            (cx + cos(angle) * radius * tip).toFloat(),
            (cy + sin(angle) * verticalRadius * tip).toFloat(),
        )
        teeth.lineTo(
            (cx + cos(angle + half) * radius * 0.94).toFloat(),
            (cy + sin(angle + half) * verticalRadius * 0.94).toFloat(),
        )
        teeth.close()
    }
    drawPath(teeth, Color(0xFFE5D7B8))
}

/** The 1024 x 1024 hunter sprite as a Compose bitmap, built once on the IO dispatcher. */
private fun IntArray.toHunterBitmap(): ImageBitmap {
    val side = EchoMonster.SPRITE_SIZE
    val buffered = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, side, side, this, 0, side)
    return buffered.toComposeImageBitmap()
}

private fun DrawScope.drawEchoFaces(faces: List<EchoAssets.ProjectedFace>, ambient: Int) {
    faces.forEach { face ->
        val path = Path().apply {
            moveTo(face.x[0], face.y[0])
            lineTo(face.x[1], face.y[1])
            lineTo(face.x[2], face.y[2])
            close()
        }
        // A wider translucent copy behind the polygon is the Compose equivalent of a glow; the
        // renderer here has no shadow layer and this is cheap.
        drawPath(path, Color(ambient).copy(alpha = 0.18f), style = Stroke(width = 7f))
        drawPath(path, Color(face.color))
    }
}

@Composable
private fun EchoHud(world: Echo.World, match: MinigameMesh.Match?) {
    val colors = LocalPrismColors.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HULL", color = Color(0xFF39E7FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(150.dp).height(6.dp).background(Color(0x66000000), RoundedCornerShape(3.dp))) {
                    Box(
                        Modifier
                            .width(150.dp * (world.playerHealth.coerceIn(0, 100) / 100f))
                            .height(6.dp)
                            .background(
                                when {
                                    world.playerHealth < 30 -> Color(0xFFFF3B5C)
                                    world.playerHealth < 60 -> Color(0xFFFFB000)
                                    else -> Color(0xFF39E7FF)
                                },
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text("${world.playerHealth.coerceIn(0, 100)}", color = Color(0xFFB8B8D8), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "SCORE ${world.score}   HUNTERS ${world.enemies.count { it.alive }}   " +
                    "PING ${if (world.shoutCooldown <= 0.0) "READY" else "…"}",
                color = Color(0xFFB8B8D8), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    world.status == Echo.Status.WON -> "EXIT REACHED"
                    world.status == Echo.Status.LOST -> "SIGNAL LOST"
                    world.clock <= world.exitVisibleUntil -> "EXIT ECHO — MOVE TOWARD THE MAGENTA"
                    else -> "SHOUT TO SEE"
                },
                color = if (world.status == Echo.Status.RUNNING) Color(0xFF8A8AB0) else Color(0xFFFFB000),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(
                if (match == null) "OFFLINE · hunters are simulated here" else "MESH · vs ${match.peerIp}",
                color = colors.faint, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Text(
                "WASD move · mouse look · SPACE shout · E strike · P help",
                color = colors.faint, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }
    }
}
