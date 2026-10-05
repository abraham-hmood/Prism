package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.minigames.MinigameStore
import com.prism.launcher.minigames.Pong
import com.prism.launcher.minigames.Rng
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Pong. PHASE 95.
 *
 * ## The engine decides everything; this draws it and reads the keyboard
 *
 * `Pong.step` is 120 fixed steps a second in `:core`, with the rng advancing only on a serve so two
 * devices that step the same number of times stay in lockstep. None of that changes here. What this
 * file does is accumulate real time into whole steps, hand the engine two [Pong.Input]s, and draw the
 * state it gets back.
 *
 * ## Fixed steps accumulated from real time, not one step per frame
 *
 * A frame-rate-tied loop makes the ball faster on a 144 Hz monitor than a 60 Hz one, and this is a game
 * where that is the difference between winnable and not. So the loop measures elapsed milliseconds and
 * runs however many 1/120 s steps fit -- the same reason the engine has `STEP_MILLIS` at all.
 *
 * A CEILING ON CATCH-UP, because the alternative is worse than a dropped frame: a window dragged to
 * another monitor or a machine that went to sleep hands back a gap of seconds, and running two hundred
 * steps in one frame teleports the ball through a paddle. Past the ceiling the missing time is
 * discarded, which looks like a brief pause and is what actually happened.
 *
 * ## Mouse OR keyboard, not a choice the player has to make
 *
 * A desktop has both and people reach for whichever. The keyboard sets an up/down input; the mouse sets
 * a target Y, which the engine already supports because the phone's touch control needed it. Moving the
 * mouse takes over and pressing a key takes back, with no mode to switch.
 */
@Composable
fun PongPage() {
    val colors = LocalPrismColors.current
    val focus = remember { FocusRequester() }

    var difficulty by remember { mutableStateOf(MinigameStore.pongDifficulty()) }
    var state by remember { mutableStateOf<Pong.State?>(null) }
    var rng by remember { mutableStateOf(Rng(0)) }
    var running by remember { mutableStateOf(false) }
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var mouseTarget by remember { mutableStateOf<Double?>(null) }
    var recorded by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf(MinigameStore.pongRecord()) }

    fun start() {
        val seed = System.nanoTime()
        rng = Rng(seed)
        state = Pong.newGame(seed)
        recorded = false
        running = true
        up = false
        down = false
        mouseTarget = null
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = System.nanoTime()
        var owed = 0.0
        while (isActive && running) {
            // ~120 Hz, which matches the engine's step rate so a normal frame runs one step.
            delay(8)
            val now = System.nanoTime()
            owed += (now - last) / 1_000_000.0
            last = now
            // The catch-up ceiling. See the class comment: a gap of seconds is time that did not
            // happen to the player, and simulating it is how a ball ends up behind a paddle.
            if (owed > 250.0) owed = 250.0

            var current = state ?: return@LaunchedEffect
            while (owed >= Pong.STEP_MILLIS) {
                owed -= Pong.STEP_MILLIS
                current = Pong.step(
                    current,
                    left = Pong.Input(up = up, down = down, targetY = mouseTarget),
                    right = Pong.aiInput(current, Pong.Side.RIGHT, difficulty),
                    rng = rng,
                )
                if (Pong.isOver(current)) break
            }
            state = current

            if (Pong.isOver(current)) {
                running = false
                if (!recorded) {
                    recorded = true
                    MinigameStore.recordPong(Pong.winner(current) == Pong.Side.LEFT)
                    record = MinigameStore.pongRecord()
                }
            }
        }
    }

    PageScaffold("Pong", "The same 120 Hz engine the phone runs, to eleven") {
        Column {
            val current = state

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current == null || Pong.isOver(current)) {
                    Button(onClick = { start(); focus.requestFocus() }) {
                        Text(if (current == null) "Play" else "Play again")
                    }
                } else {
                    OutlinedButton(onClick = { running = !running }) {
                        Text(if (running) "Pause" else "Resume", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Pong.Difficulty.entries.forEach { level ->
                    OutlinedButton(
                        onClick = {
                            difficulty = level
                            MinigameStore.setPongDifficulty(level)
                        },
                        modifier = Modifier.padding(end = 5.dp),
                    ) {
                        Text(
                            level.label,
                            fontSize = 11.sp,
                            color = if (level == difficulty) colors.accent else colors.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    record.first.toString() + "W / " + record.second + "L",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.faint,
                )
            }

            Spacer(Modifier.height(10.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Pong.WIDTH.toFloat() / Pong.HEIGHT)
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { event ->
                        val pressed = event.type == KeyEventType.KeyDown
                        when (event.key) {
                            Key.W, Key.DirectionUp -> {
                                up = pressed
                                // A key press takes control back from the mouse, with no mode to
                                // switch: the engine reads targetY only when it is non-null.
                                if (pressed) mouseTarget = null
                                true
                            }
                            Key.S, Key.DirectionDown -> {
                                down = pressed
                                if (pressed) mouseTarget = null
                                true
                            }
                            Key.Spacebar -> {
                                if (pressed) running = !running
                                true
                            }
                            else -> false
                        }
                    }
                    .pointerInput(Unit) {
                        // Every pointer event, not just presses: this is a paddle, so hovering over
                        // the court is the control.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val position = event.changes.lastOrNull()?.position ?: continue
                                val scale = Pong.HEIGHT / size.height.toDouble()
                                mouseTarget = (position.y * scale).coerceIn(0.0, Pong.HEIGHT.toDouble())
                            }
                        }
                    },
            ) {
                drawPongCourt(current, colors.accent)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "W/S or the arrow keys, or just move the mouse over the court. Space pauses. " +
                    "Click the court first if the keys do nothing — it has to hold focus.",
                fontSize = 11.sp,
                color = colors.faint,
                lineHeight = 16.sp,
            )

            if (current != null && Pong.isOver(current)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (Pong.winner(current) == Pong.Side.LEFT) "You won." else "You lost.",
                    fontSize = 15.sp,
                    color = if (Pong.winner(current) == Pong.Side.LEFT) colors.accent else colors.muted,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPongCourt(
    state: Pong.State?,
    accent: Color,
) {
    val sx = size.width / Pong.WIDTH
    val sy = size.height / Pong.HEIGHT

    drawRect(color = Color(0xFF0A0A0C), size = size)

    // A dashed centre line, drawn rather than a texture so it scales with the window.
    val dashes = 18
    for (i in 0 until dashes) {
        if (i % 2 == 1) continue
        drawRect(
            color = Color(0xFF23232B),
            topLeft = Offset(size.width / 2f - 1.5f, i * size.height / dashes),
            size = Size(3f, size.height / dashes),
        )
    }

    if (state == null) return

    drawRect(
        color = accent,
        topLeft = Offset(
            Pong.PADDLE_INSET * sx,
            (state.leftY - Pong.PADDLE_HEIGHT / 2.0).toFloat() * sy,
        ),
        size = Size(Pong.PADDLE_WIDTH * sx, Pong.PADDLE_HEIGHT * sy),
    )
    drawRect(
        color = Color(0xFFB9B9C4),
        topLeft = Offset(
            (Pong.WIDTH - Pong.PADDLE_INSET - Pong.PADDLE_WIDTH) * sx,
            (state.rightY - Pong.PADDLE_HEIGHT / 2.0).toFloat() * sy,
        ),
        size = Size(Pong.PADDLE_WIDTH * sx, Pong.PADDLE_HEIGHT * sy),
    )

    // The ball is hidden during the serve delay on purpose: it is sitting at the centre waiting, and
    // drawing it there makes it look stuck rather than about to be served.
    if (state.serveDelay <= 0) {
        drawCircle(
            color = Color.White,
            radius = Pong.BALL_RADIUS * minOf(sx, sy),
            center = Offset(state.ballX.toFloat() * sx, state.ballY.toFloat() * sy),
        )
    }
}
