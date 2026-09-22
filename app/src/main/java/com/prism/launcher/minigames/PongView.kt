package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Pong, drawn in pencil on the ruled page.
 *
 * ## The loop
 *
 * [Pong] advances in fixed steps of 1/120 second. This view catches up: it measures how long since
 * the last frame and runs however many whole steps fit, capped so a stalled frame does not try to
 * simulate two seconds at once and lock the UI thread. That is the standard fixed-timestep
 * arrangement, and it is what keeps the simulation identical to the one running on a mesh
 * opponent's phone regardless of either device's frame rate.
 *
 * ## Against a person
 *
 * In a mesh game this device owns exactly one paddle and sends its position; the opponent's paddle
 * comes in over [MinigameMesh]. Neither end sends the ball, because both compute it — see
 * [MinigameMesh] for why that is the whole point.
 */
@SuppressLint("ViewConstructor")
class PongView(
    context: Context,
    private val difficulty: Pong.Difficulty,
    private val match: MinigameMesh.Match?,
    private val onFinished: (won: Boolean) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Which paddle the player has. Always the left one offline; the mesh decides online. */
    private val mySide: Pong.Side =
        if (match == null || match.youAreFirst) Pong.Side.LEFT else Pong.Side.RIGHT

    private val seed = match?.seed ?: System.currentTimeMillis()
    private val rng = Rng(seed)

    private var state = Pong.newGame(seed)
    private var myTarget: Double = Pong.HEIGHT / 2.0
    private var peerTarget: Double = Pong.HEIGHT / 2.0

    private var lastFrame = 0L
    private var carry = 0.0
    private var running = true
    private var reported = false
    private var lastSent = 0L

    init {
        isClickable = true
        if (match != null) {
            MinigameMesh.listenForMoves { peerIp, payload ->
                if (peerIp != match.peerIp) return@listenForMoves
                payload.toDoubleOrNull()?.let { peerTarget = it }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        MinigameMesh.stopListening()
    }

    // -- Input ----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // The paddle follows the finger's height anywhere on the screen, not just over the
                // paddle. Requiring the thumb to sit on a 14-unit-wide bat is how touch Pong is
                // usually ruined.
                val scale = Pong.HEIGHT.toDouble() / courtRect().height()
                myTarget = ((event.y - courtRect().top) * scale).coerceIn(0.0, Pong.HEIGHT.toDouble())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // -- The loop -------------------------------------------------------------

    private fun advance() {
        val now = System.nanoTime()
        if (lastFrame == 0L) {
            lastFrame = now
            return
        }
        val elapsedMs = (now - lastFrame) / 1_000_000.0
        lastFrame = now

        // Capped at a quarter second: a device that was asleep must not simulate the whole nap.
        carry += elapsedMs.coerceAtMost(250.0)

        var steps = 0
        while (carry >= Pong.STEP_MILLIS && steps < 64) {
            val mine = Pong.Input(targetY = myTarget)
            val theirs = when {
                match != null -> Pong.Input(targetY = peerTarget)
                mySide == Pong.Side.LEFT -> Pong.aiInput(state, Pong.Side.RIGHT, difficulty)
                else -> Pong.aiInput(state, Pong.Side.LEFT, difficulty)
            }
            state = if (mySide == Pong.Side.LEFT) {
                Pong.step(state, mine, theirs, rng)
            } else {
                Pong.step(state, theirs, mine, rng)
            }
            carry -= Pong.STEP_MILLIS
            steps++
        }

        if (match != null && System.currentTimeMillis() - lastSent > 40) {
            lastSent = System.currentTimeMillis()
            MinigameMesh.sendMove(match.peerIp, state.paddleOf(mySide).toString())
        }

        if (Pong.isOver(state) && !reported) {
            reported = true
            val won = Pong.winner(state) == mySide
            MinigameStore.recordPong(won)
            postDelayed({ onFinished(won) }, 1_400)
        }
    }

    // -- Drawing --------------------------------------------------------------

    private fun courtRect(): RectF {
        // The court keeps Pong's aspect ratio and sits in the middle of whatever it is given, so
        // the physics never has to know about the screen.
        val pad = 12f * density
        val availW = width - pad * 2
        val availH = height - pad * 2 - 44f * density
        val scale = minOf(availW / Pong.WIDTH, availH / Pong.HEIGHT)
        val w = Pong.WIDTH * scale
        val h = Pong.HEIGHT * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f + 16f * density
        return RectF(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        if (running) advance()

        PencilStyle.drawPaper(canvas, width, height, dark, density)
        val court = courtRect()
        val scale = court.width() / Pong.WIDTH
        val ink = PencilStyle.pencil(2.2f * density, PencilStyle.graphite(dark))

        // The court: a box, and a dashed centre line drawn as a column of short strokes.
        PencilStyle.rect(canvas, court, ink, seed = 1, amount = 1.4f)
        val dash = PencilStyle.pencil(1.8f * density, PencilStyle.graphiteLight(dark), alpha = 170)
        var y = court.top + 10f * density
        var i = 0L
        while (y < court.bottom - 10f * density) {
            PencilStyle.line(
                canvas, court.centerX(), y, court.centerX(), y + 14f * density, dash, 100 + i, 0.8f,
            )
            y += 26f * density
            i++
        }

        fun px(x: Double) = court.left + (x * scale).toFloat()
        fun py(y: Double) = court.top + (y * scale).toFloat()

        // The paddles. The player's is the one in coloured pencil, which is how you know which is
        // yours without a label.
        drawPaddle(canvas, court, scale, Pong.Side.LEFT, ink, dark)
        drawPaddle(canvas, court, scale, Pong.Side.RIGHT, ink, dark)

        // The ball: a circle, gone round twice.
        val r = Pong.BALL_RADIUS * scale
        canvas.drawCircle(px(state.ballX), py(state.ballY), r, ink)
        canvas.drawCircle(
            px(state.ballX) + 0.8f * density, py(state.ballY) - 0.6f * density, r * 0.92f,
            Paint(ink).apply { alpha = 110 },
        )

        // The score, written above the court.
        val scorePaint = PencilStyle.textPaint(30f * density, dark).apply {
            textAlign = Paint.Align.CENTER
        }
        PencilStyle.writtenText(
            canvas,
            "${state.leftScore}   –   ${state.rightScore}",
            court.centerX(), court.top - 14f * density, scorePaint, seed = 5,
        )

        val label = PencilStyle.textPaint(12f * density, dark, PencilStyle.graphiteLight(dark))
        val who = if (match != null) "vs. a player on the mesh" else "vs. ${difficulty.label}"
        PencilStyle.writtenText(canvas, who, court.left, court.bottom + 20f * density, label, seed = 6)
        PencilStyle.writtenText(
            canvas, "first to ${Pong.WINNING_SCORE}, win by two",
            court.left, court.bottom + 36f * density, label, seed = 7,
        )

        if (state.serveDelay > 0 && !Pong.isOver(state)) {
            val ready = PencilStyle.textPaint(16f * density, dark, PencilStyle.RED_PENCIL).apply {
                textAlign = Paint.Align.CENTER
            }
            PencilStyle.writtenText(
                canvas, "ready…", court.centerX(), court.centerY() - 30f * density, ready, seed = 8,
            )
        }

        if (Pong.isOver(state)) {
            val won = Pong.winner(state) == mySide
            val banner = PencilStyle.textPaint(26f * density, dark, if (won) PencilStyle.GREEN_PENCIL else PencilStyle.RED_PENCIL)
                .apply { textAlign = Paint.Align.CENTER }
            val text = if (won) "you win" else "you lose"
            PencilStyle.writtenText(canvas, text, court.centerX(), court.centerY(), banner, seed = 9)
            val bounds = RectF(
                court.centerX() - 70f * density, court.centerY() - 26f * density,
                court.centerX() + 70f * density, court.centerY() + 10f * density,
            )
            PencilStyle.circleAround(canvas, bounds, PencilStyle.pencil(2f * density, if (won) PencilStyle.GREEN_PENCIL else PencilStyle.RED_PENCIL), seed = 10)
        }

        if (running) postInvalidateOnAnimation()
    }

    private fun drawPaddle(canvas: Canvas, court: RectF, scale: Float, side: Pong.Side, ink: Paint, dark: Boolean) {
        val y = state.paddleOf(side)
        val x = if (side == Pong.Side.LEFT) Pong.PADDLE_INSET.toDouble()
        else (Pong.WIDTH - Pong.PADDLE_INSET - Pong.PADDLE_WIDTH).toDouble()

        val r = RectF(
            court.left + (x * scale).toFloat(),
            court.top + ((y - Pong.PADDLE_HEIGHT / 2.0) * scale).toFloat(),
            court.left + ((x + Pong.PADDLE_WIDTH) * scale).toFloat(),
            court.top + ((y + Pong.PADDLE_HEIGHT / 2.0) * scale).toFloat(),
        )
        val mine = side == mySide
        val paint = if (mine) {
            PencilStyle.pencil(2.4f * density, PencilStyle.BLUE_PENCIL)
        } else {
            ink
        }
        // Seeded by the side rather than by position, so the wobble does not crawl as it moves.
        PencilStyle.rect(canvas, r, paint, seed = if (side == Pong.Side.LEFT) 20 else 21, amount = 1f)
        PencilStyle.hatch(
            canvas, r,
            Paint(paint).apply { alpha = 90; strokeWidth = 1.2f * density },
            seed = if (side == Pong.Side.LEFT) 22 else 23,
            spacing = 5f * density,
        )
    }
}
