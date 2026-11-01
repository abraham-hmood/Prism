package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshTransport
import com.prism.desktop.DesktopFpsAudio
import com.prism.desktop.DesktopMinigameAudio
import com.prism.launcher.PrismSettings
import com.prism.launcher.minigames.DoomGame
import com.prism.launcher.minigames.EchoSessionLock
import com.prism.launcher.minigames.Fps
import com.prism.launcher.minigames.FpsEvent
import com.prism.launcher.minigames.FpsInput
import com.prism.launcher.minigames.FpsScene
import com.prism.launcher.minigames.FpsSprites
import com.prism.launcher.minigames.FpsSpeech
import com.prism.launcher.minigames.MinigameStore
import com.prism.launcher.minigames.ZombiesGame
import com.prism.launcher.minigames.ZombieNet
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.PrismSpeaker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * DOOM and Zombies on the desktop.
 *
 * The rules are the same `:core` engines the phone runs; this is only a renderer and a keyboard.
 * Canvas rather than Compose nodes for the world, because a ray caster needs thousands of tiny
 * rectangles and a composable per wall column would be absurd.
 *
 * The desktop camera is mouse-look while dragging and WASD to walk, which is the smallest control
 * set that still feels like a shooter on a trackpad.
 */
private interface FpsSession {
    /** Live run heat, so the soundtrack follows the fight instead of the menu. */
    val intensity: Float

    fun update(dt: Float, input: FpsInput)
    fun scene(): FpsScene
    fun takeEvents(): List<FpsEvent>
    fun takeSpeech(): List<FpsSpeech>
}

private class DoomSession(private val game: DoomGame) : FpsSession {
    override fun update(dt: Float, input: FpsInput) = game.update(dt, input)
    override fun scene(): FpsScene = game.scene()
    override fun takeEvents(): List<FpsEvent> = game.takeEvents()
    override fun takeSpeech(): List<FpsSpeech> = game.takeSpeech()
    override val intensity: Float get() = game.intensity
}

private class ZombiesSession(
    private val role: Int,
    playerName: String,
    seed: Long,
    playerId: Int,
) : FpsSession {

    private var game = ZombiesGame(
        runSeed = seed,
        localName = playerName,
        playerId = playerId,
        aiTeammates = if (role == ZOMBIE_CLIENT) 0 else 3,
        client = role == ZOMBIE_CLIENT,
    )
    private val remoteIds = LinkedHashSet<Int>()
    private var stopped = false

    init {
        if (role == ZOMBIE_HOST) game.networked = true
    }

    override fun update(dt: Float, input: FpsInput) {
        when (role) {
            ZOMBIE_CLIENT -> {
                pollNetwork()
                ZombieNet.sendInput(input)
                ZombieNet.latestSnapshot()?.let { snapshot ->
                    if (snapshot.seed == game.runSeed) {
                        game.applySnapshot(snapshot)
                    } else {
                        game = ZombiesGame(
                            runSeed = snapshot.seed,
                            localName = "SURVIVOR",
                            playerId = ZombieNet.localId,
                            aiTeammates = 0,
                            client = true,
                        )
                        game.applySnapshot(snapshot)
                    }
                }
                game.update(dt, emptyMap())
            }
            else -> {
                pollNetwork()
                val inputs = HashMap<Int, FpsInput>(remoteIds.size + 1)
                inputs[0] = input
                for (id in remoteIds) inputs[id] = ZombieNet.inputFor(id)
                game.update(dt, inputs)
                if (role == ZOMBIE_HOST) ZombieNet.publish(game.snapshot())
            }
        }
    }

    private fun pollNetwork() {
        if (role == ZOMBIE_HOST) {
            for ((id, name) in ZombieNet.drainJoins()) {
                remoteIds.add(id)
                game.addRemotePlayer(id, name)
                game.networked = true
            }
            for (id in ZombieNet.drainLeaves()) remoteIds.remove(id)
        } else if (role == ZOMBIE_CLIENT) {
            ZombieNet.takeWelcome() ?: return
            game.networked = true
        }
    }

    override fun scene(): FpsScene = game.scene()
    override fun takeEvents(): List<FpsEvent> = game.takeEvents()
    override fun takeSpeech(): List<FpsSpeech> = game.takeSpeech()
    override val intensity: Float get() = game.intensity

    fun stop() {
        if (stopped) return
        stopped = true
        if (role != ZOMBIE_SOLO) ZombieNet.stop()
    }
}

@Composable
fun DoomDesktopPage(onExit: () -> Unit) {
    var floor by remember { mutableStateOf(1) }
    val seed = remember { System.currentTimeMillis() }
    val session = remember(floor) { DoomSession(DoomGame(seed, floor, 1)) }

    DisposableEffect(Unit) {
        EchoSessionLock.begin("DOOM")
        onDispose { EchoSessionLock.end() }
    }

    FpsSurface(
        session = session,
        label = "DOOM",
        detail = "FLOOR $floor · WASD move · mouse look · click fire · R reload · E use · SPACE fire",
        onResult = { won, score ->
            MinigameStore.recordArcade("doom", score)
            if (won && floor < 3) floor++ else onExit()
        },
        onExit = onExit,
    )
}

@Composable
fun ZombiesDesktopPage(onExit: () -> Unit) {
    val colors = LocalPrismColors.current
    val meshAvailable = remember { runCatching { ZombieNet.available() }.getOrDefault(false) }
    var session by remember { mutableStateOf<ZombiesSession?>(null) }
    var joining by remember { mutableStateOf(false) }
    var lobbyStatus by remember { mutableStateOf("") }

    fun begin(role: Int, seed: Long, playerId: Int) {
        session = ZombiesSession(role, "SURVIVOR", seed, playerId)
        joining = false
    }

    LaunchedEffect(joining) {
        if (!joining) return@LaunchedEffect
        while (joining && session == null) {
            val welcome = ZombieNet.takeWelcome()
            if (welcome != null) {
                begin(ZOMBIE_CLIENT, welcome.seed, welcome.id)
                break
            }
            lobbyStatus = "Looking for a host on the mesh…"
            kotlinx.coroutines.delay(300)
        }
    }

    DisposableEffect(Unit) {
        onDispose { ZombieNet.stop() }
    }

    val active = session
    if (active == null) {
        Column(Modifier.fillMaxSize().background(colors.background).padding(28.dp)) {
            Text("ZOMBIES", fontSize = 24.sp, color = colors.accent, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text(
                "WORK IN PROGRESS — the maps, zombie models and textures are still being built.",
                fontSize = 12.sp,
                color = colors.destructive,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Wave survival. Three AI survivors hold the other windows; on a Prism mesh " +
                    "up to four phones or PCs can share one host's world.",
                fontSize = 12.sp,
                color = colors.muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { begin(ZOMBIE_SOLO, System.currentTimeMillis(), 0) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SURVIVE ALONE", fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (ZombieNet.host("SURVIVOR")) {
                        begin(ZOMBIE_HOST, ZombieNet.seed, 0)
                    } else {
                        lobbyStatus = ZombieNet.lastError
                    }
                },
                enabled = meshAvailable && !joining,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("HOST A MESH GAME", fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (ZombieNet.join("SURVIVOR")) {
                        joining = true
                        lobbyStatus = "Looking for a host on the mesh…"
                    } else {
                        lobbyStatus = ZombieNet.lastError
                    }
                },
                enabled = meshAvailable && !joining,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("JOIN A MESH GAME", fontSize = 13.sp) }
            if (joining) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        joining = false
                        ZombieNet.stop()
                        lobbyStatus = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("CANCEL", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (meshAvailable) {
                    if (joining) lobbyStatus else "On the mesh. A host owns the world; joiners send controls."
                } else {
                    "Not on a Prism MeshNet — single-player only right now."
                },
                fontSize = 11.sp,
                color = colors.faint,
                lineHeight = 16.sp,
            )
            if (PrismSettings.getZombiesUseCustomAssets()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Custom baseline: " + PrismSettings.getZombiesAssetPath().ifBlank { "not chosen" },
                    fontSize = 10.sp,
                    color = colors.faint,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("← all games", fontSize = 12.sp, color = colors.faint,
                modifier = Modifier.clickableRow(onExit).padding(4.dp))
        }
    } else {
        DisposableEffect(active) {
            EchoSessionLock.begin("Zombies")
            onDispose {
                EchoSessionLock.end()
                active.stop()
            }
        }
        FpsSurface(
            session = active,
            label = "ZOMBIES",
            detail = "WASD move · mouse look · click fire · R reload · E revive · SPACE fire",
            onResult = { _, score ->
                MinigameStore.recordArcade("zombies", score)
                onExit()
            },
            onExit = onExit,
        )
    }
}

@Composable
private fun FpsSurface(
    session: FpsSession,
    label: String,
    detail: String,
    onResult: (won: Boolean, score: Int) -> Unit,
    onExit: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val focus = remember { FocusRequester() }
    val held = remember { mutableSetOf<Key>() }
    var fireHeld by remember { mutableStateOf(false) }
    var turn by remember { mutableFloatStateOf(0f) }
    var look by remember { mutableFloatStateOf(0f) }
    var scene by remember(session) { mutableStateOf(session.scene()) }
    var reported by remember(session) { mutableStateOf(false) }

    DisposableEffect(focus) {
        focus.requestFocus()
        onDispose {
            DesktopFpsAudio.stop()
            DesktopMinigameAudio.stop()
        }
    }

    LaunchedEffect(session) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                last = now
                if (scene.status == 0) {
                    val forward = when {
                        Key.W in held -> 1f
                        Key.S in held -> -1f
                        else -> 0f
                    }
                    val strafe = when {
                        Key.D in held -> 1f
                        Key.A in held -> -1f
                        else -> 0f
                    }
                    if (Key.DirectionLeft in held) turn -= 0.06f
                    if (Key.DirectionRight in held) turn += 0.06f
                    if (Key.DirectionUp in held) look += 0.02f
                    if (Key.DirectionDown in held) look -= 0.02f
                    val input = FpsInput(
                        forward = forward,
                        strafe = strafe,
                        turn = turn,
                        look = look,
                        fire = fireHeld || Key.Spacebar in held,
                        reload = Key.R in held,
                        use = Key.E in held,
                        switchWeapon = if (Key.Q in held) 1 else -1,
                    )
                    turn = 0f
                    look = 0f
                    session.update(dt, input)
                    for (event in session.takeEvents()) {
                        DesktopFpsAudio.play(event, scene.cameraX, scene.cameraY)
                    }
                    for (line in session.takeSpeech()) speak(line)
                    scene = session.scene()
                    // Read live: a snapshot taken when the run started would leave the tempo
                    // flat through a wave that is going badly.
                    DesktopMinigameAudio.update(session.intensity)
                    if (scene.status != 0 && !reported) {
                        reported = true
                        DesktopMinigameAudio.update(0f)
                        onResult(scene.status == 1, scene.score)
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 20.sp, color = colors.accent, fontFamily = FontFamily.Monospace)
                Text(detail, fontSize = 10.sp, color = colors.muted)
            }
            Text("ESC back", fontSize = 10.sp, color = colors.faint)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        // A live session owns the page until its result sheet. This is the same
                        // lock the Minigames picker shows; Escape must not be a back door out.
                        if (!EchoSessionLock.isActive()) onExit()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        held.add(event.key)
                        true
                    } else if (event.type == KeyEventType.KeyUp) {
                        held.remove(event.key)
                        true
                    } else false
                }
                .pointerInput(session) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fireHeld = true
                        var previous = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            turn += (change.position.x - previous.x) * 0.004f
                            look -= (change.position.y - previous.y) * 0.0022f
                            previous = change.position
                            change.consume()
                        }
                        fireHeld = false
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawFpsScene(scene, colors)
            }
            Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text(
                    "${scene.waveLabel}   HP ${scene.health.toInt()}   ${scene.ammoMag}/${scene.ammoReserve}   ${scene.points} PTS",
                    fontSize = 11.sp,
                    color = colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
                scene.objective?.let {
                    Text(it, fontSize = 10.sp, color = colors.muted, fontFamily = FontFamily.Monospace)
                }
            }
            scene.message?.let { message ->
                Box(Modifier.align(Alignment.Center)) {
                    Text(message, fontSize = 14.sp, color = colors.accent, fontFamily = FontFamily.Monospace)
                }
            }
            if (scene.status != 0) {
                Column(
                    Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.94f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (scene.status == 1) "FLOOR CLEARED" else "YOU DIED",
                        fontSize = 30.sp,
                        color = if (scene.status == 1) colors.accent else colors.destructive,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${scene.score} points · ${scene.kills} kills",
                        fontSize = 12.sp,
                        color = colors.muted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                scene.subtitles.takeLast(2).forEach {
                    Text(it, fontSize = 10.sp, color = colors.onSurface, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun speak(line: FpsSpeech) {
    val voices = KokoroVoices.ALL.filter { it.language == KokoroVoices.Language.AMERICAN_ENGLISH }
    if (voices.isEmpty()) return
    runCatching {
        PrismSpeaker.speakAs(
            text = line.text,
            voiceId = voices[abs(line.voiceKey) % voices.size].id,
            speed = 1.02f,
        )
    }
}

private fun DrawScope.drawFpsScene(scene: FpsScene, colors: PrismColors) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.44f + (scene.pitch.toFloat() * h * 0.55f)
    drawRect(colors.background)
    drawRect(blend(colors.background, colors.surfaceRaised, 0.45f), Offset(0f, 0f), Size(w, horizon))
    drawRect(blend(colors.background, colors.surface, 0.70f), Offset(0f, horizon), Size(w, h - horizon))

    val fov = (Math.PI / 3.0).toFloat()
    val column = 3f
    var x = 0f
    val wallBase = blend(colors.surfaceRaised, colors.accent, 0.16f)
    while (x < w) {
        val angle = scene.cameraAngle + ((x / w) - 0.5) * fov
        val hit = Fps.cast(scene.map, scene.cameraX, scene.cameraY, angle, 34.0)
        val corrected = kotlin.math.max(0.12, hit.distance * cos(angle - scene.cameraAngle))
        val wallHeight = (h * 0.92 / corrected).toFloat().coerceAtMost(h * 2.2f)
        val top = horizon - wallHeight * 0.5f
        val bottom = horizon + wallHeight * 0.5f
        val light = (1.0 / (1.0 + corrected * 0.17)).toFloat().coerceIn(0.08f, 1f)
        val base = if (hit.tile == Fps.EXIT) colors.accent else wallBase
        val band = if (((hit.u * 8).toInt() + hit.cellX + hit.cellY) % 2 == 0) 1f else 0.9f
        val shade = (light * (if (hit.side == 0) 1f else 0.8f) * band).coerceIn(0f, 1f)
        drawRect(
            Color(base.red * shade, base.green * shade, base.blue * shade),
            Offset(x, top),
            Size(column + 0.5f, bottom - top),
        )
        x += column
    }

    data class P(val sprite: com.prism.launcher.minigames.FpsSprite, val depth: Double, val sx: Float)
    val cosA = cos(scene.cameraAngle)
    val sinA = sin(scene.cameraAngle)
    val projected = scene.sprites.mapNotNull { sprite ->
        val dx = sprite.x - scene.cameraX
        val dy = sprite.y - scene.cameraY
        val depth = dx * cosA + dy * sinA
        if (depth < 0.25) return@mapNotNull null
        if (!Fps.visible(scene.map, scene.cameraX, scene.cameraY, sprite.x, sprite.y)) return@mapNotNull null
        val side = -dx * sinA + dy * cosA
        val sx = (w * 0.5 + side / depth * (w * 0.5) / tan(Math.PI / 6.0)).toFloat()
        P(sprite, depth, sx)
    }.sortedByDescending { it.depth }

    for (item in projected) {
        val depth = item.depth.coerceAtLeast(0.35)
        val spriteSize = (h * item.sprite.size / depth).toFloat().coerceIn(6f, h * 1.8f)
        val bottom = horizon + (h * 0.44 / depth).toFloat().coerceAtMost(h * 0.95f)
        val cx = item.sx
        when (item.sprite.kind) {
            FpsSprites.ZOMBIE, FpsSprites.BRUTE, FpsSprites.SPITTER -> {
                val colour = when (item.sprite.kind) {
                    FpsSprites.BRUTE -> blend(colors.surfaceRaised, colors.destructive, 0.26f)
                    FpsSprites.SPITTER -> blend(colors.accent, colors.background, 0.42f)
                    else -> blend(colors.muted, colors.accent, 0.22f)
                }
                drawRect(colour, Offset(cx - spriteSize * 0.19f, bottom - spriteSize * 0.82f), Size(spriteSize * 0.38f, spriteSize * 0.42f))
                drawCircle(colour, spriteSize * 0.11f, Offset(cx, bottom - spriteSize * 0.90f))
                drawCircle(colors.destructive, spriteSize * 0.025f, Offset(cx - spriteSize * 0.05f, bottom - spriteSize * 0.92f))
                drawCircle(colors.destructive, spriteSize * 0.025f, Offset(cx + spriteSize * 0.05f, bottom - spriteSize * 0.92f))
            }
            FpsSprites.TEAMMATE -> {
                drawRect(colors.accent, Offset(cx - spriteSize * 0.17f, bottom - spriteSize * 0.78f), Size(spriteSize * 0.34f, spriteSize * 0.4f))
                drawCircle(colors.accent, spriteSize * 0.16f, Offset(cx, bottom - spriteSize * 0.87f))
            }
            FpsSprites.EXIT -> {
                drawCircle(colors.accent.copy(alpha = 0.33f), spriteSize * 0.36f, Offset(cx, bottom - spriteSize * 0.55f))
                drawCircle(colors.accent.copy(alpha = 0.66f), spriteSize * 0.23f, Offset(cx, bottom - spriteSize * 0.55f))
            }
            FpsSprites.PROJECTILE -> drawCircle(colors.destructive, spriteSize * 0.25f, Offset(cx, bottom - spriteSize * 0.5f))
            FpsSprites.WEAPON -> drawRect(colors.accent, Offset(cx - spriteSize * 0.2f, bottom - spriteSize * 0.45f), Size(spriteSize * 0.4f, spriteSize * 0.08f))
            else -> drawCircle(
                when (item.sprite.kind) {
                    FpsSprites.HEALTH -> colors.accent
                    FpsSprites.AMMO -> colors.onSurface
                    FpsSprites.ARMOR -> colors.destructive
                    else -> colors.faint
                },
                spriteSize * 0.2f,
                Offset(cx, bottom - spriteSize * 0.35f),
            )
        }
    }

    // Gun, from the same shapes as the phone but scaled to the window.
    val gunY = h - h * 0.1f
    val gunX = w * 0.64f
    val s = kotlin.math.min(w, h) * 0.15f
    drawRect(blend(colors.surfaceRaised, colors.background, 0.35f), Offset(gunX - s, gunY), Size(s * 2.1f, s * 0.55f))
    drawRect(blend(colors.surface, colors.onSurface, 0.12f), Offset(gunX - s * 0.3f, gunY - s * 0.45f), Size(s * 1.25f, s * 0.22f))
    drawRect(colors.accent, Offset(gunX + s * 0.55f, gunY - s * 0.40f), Size(s * 0.65f, s * 0.06f))
    drawRect(colors.background, Offset(gunX - s * 0.55f, gunY + s * 0.3f), Size(s * 0.45f, s * 0.5f))

    // Crosshair.
    val cx = w / 2f
    val cy = horizon
    val crosshair = colors.onSurface.copy(alpha = 0.8f)
    drawLine(crosshair, Offset(cx - 14f, cy), Offset(cx - 5f, cy), 2f)
    drawLine(crosshair, Offset(cx + 5f, cy), Offset(cx + 14f, cy), 2f)
    drawLine(crosshair, Offset(cx, cy - 14f), Offset(cx, cy - 5f), 2f)
    drawLine(crosshair, Offset(cx, cy + 5f), Offset(cx, cy + 14f), 2f)

    if (scene.hurt > 0.01f) {
        drawRect(colors.destructive.copy(alpha = (scene.hurt * 0.34f).coerceIn(0f, 1f)))
    }
}

private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

private const val ZOMBIE_SOLO = 0
private const val ZOMBIE_HOST = 1
private const val ZOMBIE_CLIENT = 2
