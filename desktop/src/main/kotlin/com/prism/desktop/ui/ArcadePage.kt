package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.minigames.ArcadeAction
import com.prism.launcher.minigames.ArcadeGame
import com.prism.launcher.minigames.ArcadeGames
import com.prism.launcher.minigames.ArcadeInput
import com.prism.launcher.minigames.ArcadeScene
import com.prism.launcher.minigames.ArcadeSprite
import com.prism.launcher.minigames.MinigameStore
import kotlin.math.abs

/**
 * The desktop cabinet for the thirteen short arcade games.
 *
 * It draws the exact scene the phone draws -- [ArcadeScene] is platform-free -- and the only desktop
 * specific pieces are pointer input and Compose text. Neon colours are the same indices mapped to the
 * same values as ArcadeGameView, so a game looks like the same game on both screens.
 */
@Composable
fun ArcadePage(gameId: String) {
    val info = remember(gameId) { ArcadeGames.info(gameId) }
    val game = remember(gameId) { ArcadeGames.create(gameId) }
    if (game == null || info == null) {
        PageScaffold("Arcade", "Unknown cabinet") {
            Text("No game is registered as $gameId.", modifier = Modifier.padding(28.dp))
        }
        return
    }

    var scene by remember(game) { mutableStateOf(game.scene()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    var recorded by remember(game) { mutableStateOf(false) }

    LaunchedEffect(game) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
                last = now
                game.update(dt)
                com.prism.desktop.DesktopMinigameAudio.update(game.intensity)
                if (game.over && !recorded) {
                    recorded = true
                    MinigameStore.recordArcade(gameId, game.score)
                }
                scene = game.scene()
            }
        }
    }

    fun restart() {
        recorded = false
        game.reset(System.nanoTime())
        scene = game.scene()
    }

    fun press(action: ArcadeAction) {
        if (game.over) restart() else game.input(ArcadeInput(action = action))
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF030108))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(info.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF39E7FF))
                Text(scene.hud, fontSize = 11.sp, color = Color(0xFF8A8AB0))
            }
            Text(info.controls, fontSize = 10.sp, color = Color(0xFF6E6E7A))
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .pointerInput(game) {
                    detectTapGestures { offset ->
                        if (game.over) {
                            restart()
                            return@detectTapGestures
                        }
                        val rect = boardRect(canvasSize, scene.aspect)
                        if (rect.contains(offset)) {
                            game.input(ArcadeInput(
                                tapX = ((offset.x - rect.left) / rect.width).coerceIn(0f, 1f),
                                tapY = ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f),
                            ))
                        }
                        scene = game.scene()
                    }
                }
                .pointerInput(game) {
                    var start = Offset.Zero
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; total = Offset.Zero },
                        onDragEnd = {
                            if (!game.over) {
                                val slop = 26f
                                val action = when {
                                    abs(total.x) <= slop && abs(total.y) <= slop -> null
                                    abs(total.x) > abs(total.y) ->
                                        if (total.x > 0) ArcadeAction.RIGHT else ArcadeAction.LEFT
                                    total.y > 0 -> ArcadeAction.DOWN
                                    else -> ArcadeAction.UP
                                }
                                if (action != null) game.input(ArcadeInput(swipe = action))
                            }
                            scene = game.scene()
                        },
                        onDrag = { change, drag ->
                            total += drag
                            if (game.id == "brick") {
                                val rect = boardRect(canvasSize, scene.aspect)
                                if (rect.width > 0f) {
                                    game.input(ArcadeInput(
                                        dragX = ((change.position.x - rect.left) / rect.width)
                                            .coerceIn(0f, 1f),
                                    ))
                                }
                            }
                            scene = game.scene()
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val rect = boardRect(
                    IntSize(size.width.toInt(), size.height.toInt()),
                    scene.aspect,
                )
                drawBoard(rect, scene, measurer)
            }
        }

        val buttons = if (game.over) listOf("Restart" to ArcadeAction.ACTION)
        else scene.buttons.map { it.label to it.action }
        if (buttons.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buttons.forEach { (label, action) ->
                    OutlinedButton(onClick = { press(action) }, modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 11.sp, color = Color(0xFF39E7FF))
                    }
                }
            }
        }
        if (scene.message != null) {
            Text(
                scene.message.orEmpty(),
                fontSize = 11.sp,
                color = Color(0xFF6E6E7A),
                modifier = Modifier.padding(start = 28.dp, bottom = 10.dp),
            )
        }
    }
}

private fun boardRect(size: IntSize, aspect: Float): Rect {
    val padding = 20f
    val availableW = (size.width - padding * 2).coerceAtLeast(40f)
    val availableH = (size.height - padding * 2).coerceAtLeast(40f)
    val a = aspect.coerceAtLeast(0.25f)
    var w = availableW
    var h = w / a
    if (h > availableH) {
        h = availableH
        w = h * a
    }
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private val ARCADE_PALETTE = intArrayOf(
    0xFF39E7FF.toInt(), 0xFFFF4FD8.toInt(), 0xFFFFE36E.toInt(), 0xFF5CFF9D.toInt(),
    0xFFFF9C42.toInt(), 0xFF6E8BFF.toInt(), 0xFFFF5B6E.toInt(), 0xFFFFFFFF.toInt(),
    0xFF20203A.toInt(),
)

private fun arcadeColour(index: Int): Color =
    Color(ARCADE_PALETTE[index.coerceIn(0, ARCADE_PALETTE.size - 1)])

private fun DrawScope.drawBoard(rect: Rect, scene: ArcadeScene, measurer: TextMeasurer) {
    drawRoundRect(
        color = Color(0x5539E7FF),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
        style = Stroke(width = 1.2f),
    )
    if (scene.gridW > 0 && scene.gridH > 0) {
        for (i in 1 until scene.gridW) {
            val x = rect.left + rect.width * i / scene.gridW
            drawLine(Color(0x1739E7FF), Offset(x, rect.top), Offset(x, rect.bottom), 0.7f)
        }
        for (i in 1 until scene.gridH) {
            val y = rect.top + rect.height * i / scene.gridH
            drawLine(Color(0x1739E7FF), Offset(rect.left, y), Offset(rect.right, y), 0.7f)
        }
    }

    scene.sprites.forEach { sprite -> drawArcadeSprite(rect, sprite, measurer) }

    scene.message?.let { message ->
        val box = Rect(rect.left, rect.center.y - 24f, rect.right, rect.center.y + 24f)
        drawRect(Color(0xCC030108), box.topLeft, box.size)
        val measured = measurer.measure(
            message.uppercase(),
            TextStyle(fontSize = 13.sp, color = Color(0xFF39E7FF), fontWeight = FontWeight.Bold),
        )
        drawText(
            measured,
            topLeft = Offset(
                box.center.x - measured.size.width / 2f,
                box.center.y - measured.size.height / 2f,
            ),
        )
    }
}

private fun DrawScope.drawArcadeSprite(rect: Rect, sprite: ArcadeSprite, measurer: TextMeasurer) {
    val colour = arcadeColour(sprite.colour)
    val left = rect.left + sprite.x * rect.width
    val top = rect.top + sprite.y * rect.height
    val w = sprite.w * rect.width
    val h = sprite.h * rect.height
    val size = Size(w, h)
    val alpha = sprite.alpha.coerceIn(0f, 1f)

    when (sprite.kind) {
        1 -> {
            drawCircle(colour.copy(alpha = alpha * 0.2f), w * 0.62f, Offset(left + w / 2f, top + h / 2f))
            drawCircle(colour.copy(alpha = alpha), w * 0.43f, Offset(left + w / 2f, top + h / 2f))
        }
        2 -> {
            drawCircle(colour.copy(alpha = alpha * 0.18f), w * 0.6f, Offset(left + w / 2f, top + h / 2f))
            drawCircle(
                colour.copy(alpha = alpha), w * 0.46f,
                Offset(left + w / 2f, top + h / 2f), style = Stroke(width = 3f),
            )
        }
        else -> {
            drawRoundRect(
                colour.copy(alpha = alpha * 0.18f),
                Offset(left - 2f, top - 2f), Size(w + 4f, h + 4f),
                androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            drawRoundRect(
                colour.copy(alpha = alpha), Offset(left, top), size,
                androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            )
        }
    }

    sprite.label?.let { label ->
        val measured = measurer.measure(
            label,
            TextStyle(
                fontSize = (h * 0.42f).coerceIn(7f, 22f).sp,
                color = if (sprite.colour == 8) Color(0xFFB8B8D8) else Color(0xFF030108),
                fontWeight = FontWeight.Bold,
            ),
        )
        drawText(
            measured,
            topLeft = Offset(left + w / 2f - measured.size.width / 2f, top + h / 2f - measured.size.height / 2f),
        )
    }
}
