package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.minigames.Pinball.State
import kotlin.math.min

/**
 * Pinball, drawn as a pencil diagram of its own table.
 *
 * ## Fixed steps, like Pong
 *
 * [Pinball] integrates at 120 steps a second. This view accumulates real time and runs whole steps,
 * capped so a frame that was interrupted by the window manager cannot replay a second of physics.
 * The ball's motion is therefore identical to the one the model's tests assert, regardless of the
 * frame rate this machine happens to hit.
 *
 * ## Two zones, no on-screen buttons
 *
 * The bottom-left half of the screen is the left flipper and the bottom-right half is the right one;
 * while the ball is still in the lane, a tap anywhere pulls the plunger. Buttons would cover the
 * playfield, and a pinball table is the one game where the whole field is the point.
 *
 * ## The table is geometry
 *
 * Everything is drawn from [Pinball.Table] -- segments, circles and flipper pivots -- scaled to the
 * view. There is no bitmap: the drawing is the same pencils the rest of the minigames use, so the
 * page's theme still reads through it.
 */
@SuppressLint("ViewConstructor")
class PinballView(
    context: Context,
    private val onFinished: (score: Int) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private var state: State = Pinball.newGame(System.currentTimeMillis())
    private var leftDown = false
    private var rightDown = false
    private var running = true
    private var lastNanos = 0L
    private var carry = 0.0
    private var reported = false

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    // -- Geometry -------------------------------------------------------------

    private fun fit() {
        val pad = 12f * density
        scale = min(
            (width - pad * 2) / Pinball.WIDTH.toFloat(),
            (height - pad * 2) / Pinball.HEIGHT.toFloat(),
        )
        originX = (width - Pinball.WIDTH * scale) / 2f
        originY = (height - Pinball.HEIGHT * scale) / 2f
    }

    private fun sx(x: Double): Float = originX + x.toFloat() * scale
    private fun sy(y: Double): Float = originY + y.toFloat() * scale

    // -- Input ----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (state.inLane) {
                    state = Pinball.launch(state)
                    lastNanos = 0L
                }
                assign(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                assign(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                leftDown = false
                rightDown = false
                // A finger that is still down after one lifts should keep its flipper up.
                if (event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL
                ) {
                    assign(event, skipIndex = event.actionIndex)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun assign(event: MotionEvent, skipIndex: Int = -1) {
        leftDown = false
        rightDown = false
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            if (event.getX(i) < width / 2f) leftDown = true else rightDown = true
        }
    }

    // -- The loop -------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }

    private fun advance() {
        val now = System.nanoTime()
        if (lastNanos == 0L) {
            lastNanos = now
            return
        }
        val elapsedMs = (now - lastNanos) / 1_000_000.0
        lastNanos = now
        carry += elapsedMs.coerceAtMost(200.0)

        var steps = 0
        while (carry >= Pinball.STEP_MILLIS && steps < 48) {
            state = Pinball.step(state, Pinball.Input(left = leftDown, right = rightDown))
            carry -= Pinball.STEP_MILLIS
            steps++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        PencilStyle.drawPaper(canvas, width, height, dark, density)
        fit()

        if (running) {
            advance()
            if (state.status == State.Status.GAME_OVER && !reported) {
                reported = true
                postDelayed({ onFinished(state.score) }, 700L)
            }
            postInvalidateOnAnimation()
        }

        val graphite = PencilStyle.graphite(dark)
        val light = PencilStyle.graphiteLight(dark)
        val table = Pinball.table

        // Header.
        text.color = graphite
        text.textSize = 12f * density
        canvas.drawText("${state.score}", 14f * density, 22f * density, text)
        text.color = light
        text.textSize = 11f * density
        canvas.drawText(
            when {
                state.status == State.Status.GAME_OVER -> "Game over · best ${maxOf(state.score, state.best)}"
                state.inLane -> "Tap to pull the plunger · ${state.ballsLeft} balls left"
                else -> "Balls left ${state.ballsLeft} · left and right halves are the flippers"
            },
            14f * density, 37f * density, text,
        )

        ink.color = graphite
        table.walls.forEach { segment ->
            canvas.drawLine(sx(segment.x1), sy(segment.y1), sx(segment.x2), sy(segment.y2), ink)
        }

        ink.color = PencilStyle.BLUE_PENCIL
        table.slings.forEach { segment ->
            canvas.drawLine(sx(segment.x1), sy(segment.y1), sx(segment.x2), sy(segment.y2), ink)
        }

        // Bumpers.
        table.bumpers.forEach { bumper ->
            val lit = state.lit == bumper.id && state.litFor > 0
            fill.color = if (lit) PencilStyle.RED_PENCIL else PencilStyle.paper(dark)
            canvas.drawCircle(sx(bumper.x), sy(bumper.y), bumper.radius.toFloat() * scale, fill)
            ink.color = if (lit) PencilStyle.RED_PENCIL else graphite
            canvas.drawCircle(sx(bumper.x), sy(bumper.y), bumper.radius.toFloat() * scale, ink)
            text.color = if (lit) PencilStyle.RED_PENCIL else light
            text.textSize = 9f * density
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(
                bumper.points.toString(),
                sx(bumper.x),
                sy(bumper.y) + 3f * density,
                text,
            )
            text.textAlign = Paint.Align.LEFT
        }

        // Flippers: a tapering wedge, drawn as two lines to the tip so it reads as a bat rather
        // than as a wall.
        drawFlipper(canvas, table.leftFlipper.pivotX, table.leftFlipper.pivotY, table.leftFlipper.length, state.leftAngle, graphite)
        drawFlipper(canvas, table.rightFlipper.pivotX, table.rightFlipper.pivotY, table.rightFlipper.length, state.rightAngle, graphite)

        // The ball.
        fill.color = PencilStyle.graphite(dark)
        canvas.drawCircle(sx(state.ballX), sy(state.ballY), Pinball.BALL_RADIUS.toFloat() * scale, fill)
        ink.color = graphite
        canvas.drawCircle(sx(state.ballX), sy(state.ballY), Pinball.BALL_RADIUS.toFloat() * scale, ink)
    }

    private fun drawFlipper(canvas: Canvas, px: Double, py: Double, length: Double, angle: Double, colour: Int) {
        ink.color = colour
        val tipX = px + Math.cos(angle) * length
        val tipY = py + Math.sin(angle) * length
        val halfWidth = 7.0
        // A wedge from the pivot to a slightly wider tip, drawn as an outline so the paper shows
        // through and the flipper still reads as a pencil shape.
        val path = Path()
        path.moveTo(sx(px), sy(py + halfWidth))
        path.lineTo(sx(tipX), sy(tipY + halfWidth * 0.5))
        path.lineTo(sx(tipX), sy(tipY - halfWidth * 0.5))
        path.lineTo(sx(px), sy(py - halfWidth))
        path.close()
        canvas.drawPath(path, ink)
        fill.color = (60 shl 24) or (colour and 0x00FFFFFF)
        canvas.drawPath(path, fill)
    }
}
