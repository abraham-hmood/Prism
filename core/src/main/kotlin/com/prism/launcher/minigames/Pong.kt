package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pong, as a state machine with no opinions about pixels.
 *
 * ## Fixed-step, integer-ish, reproducible
 *
 * The court is 1000 by 600 arbitrary units and the game advances in fixed steps, never by however
 * long the last frame happened to take. Two reasons, and the second is the important one:
 *
 * 1. A variable step makes the ball tunnel through a paddle on a slow frame. Everybody has met the
 *    Pong where the ball occasionally just leaves.
 * 2. Mesh play. Two phones running the same steps from the same seed see the same ball, so the
 *    network only has to carry each player's paddle position — a few bytes, a few times a second —
 *    instead of the whole world state. The moment the physics depends on frame timing, that stops
 *    being true and the game needs a server.
 *
 * The renderer scales the court to whatever it has; nothing here knows the screen exists.
 */
object Pong {

    const val WIDTH = 1000
    const val HEIGHT = 600

    const val PADDLE_HEIGHT = 110
    const val PADDLE_WIDTH = 14
    const val PADDLE_INSET = 34
    const val BALL_RADIUS = 10

    /** Steps per second. 120 is smooth, cheap, and divides evenly into common frame rates. */
    const val STEPS_PER_SECOND = 120
    const val STEP_MILLIS = 1000.0 / STEPS_PER_SECOND

    const val PADDLE_SPEED = 7.4

    enum class Side { LEFT, RIGHT }

    enum class Difficulty(val label: String, val reaction: Double, val topSpeed: Double) {
        CASUAL("Casual", reaction = 0.055, topSpeed = 4.6),
        KEEN("Keen", reaction = 0.10, topSpeed = 6.4),
        BRUTAL("Brutal", reaction = 0.19, topSpeed = 8.8),
    }

    data class State(
        val ballX: Double = WIDTH / 2.0,
        val ballY: Double = HEIGHT / 2.0,
        val ballVx: Double = 5.0,
        val ballVy: Double = 2.2,
        val leftY: Double = HEIGHT / 2.0,
        val rightY: Double = HEIGHT / 2.0,
        val leftScore: Int = 0,
        val rightScore: Int = 0,
        val step: Long = 0,
        /** Counts down after a point, so the ball does not restart in somebody's face. */
        val serveDelay: Int = STEPS_PER_SECOND,
        val rallyHits: Int = 0,
        val lastScorer: Side? = null,
    ) {
        fun scoreOf(side: Side): Int = if (side == Side.LEFT) leftScore else rightScore
        fun paddleOf(side: Side): Double = if (side == Side.LEFT) leftY else rightY
    }

    /** What a player is doing with their thumb this step. */
    data class Input(val up: Boolean = false, val down: Boolean = false, val targetY: Double? = null)

    const val WINNING_SCORE = 11

    fun isOver(state: State): Boolean =
        (state.leftScore >= WINNING_SCORE || state.rightScore >= WINNING_SCORE) &&
            abs(state.leftScore - state.rightScore) >= 2

    fun winner(state: State): Side? = when {
        !isOver(state) -> null
        state.leftScore > state.rightScore -> Side.LEFT
        else -> Side.RIGHT
    }

    fun newGame(seed: Long): State {
        val rng = Rng(seed)
        return serve(State(step = 0), rng, toward = if (rng.nextBoolean()) Side.LEFT else Side.RIGHT)
    }

    private fun serve(state: State, rng: Rng, toward: Side): State {
        // A serve angle that is never flat and never steep: a flat serve is a free point and a
        // steep one is thirty seconds of the ball bouncing between the walls.
        val angle = (rng.nextDouble() * 0.9 - 0.45)
        val speed = 5.2
        val vx = if (toward == Side.LEFT) -speed else speed
        return state.copy(
            ballX = WIDTH / 2.0,
            ballY = HEIGHT / 2.0,
            ballVx = vx,
            ballVy = speed * angle,
            serveDelay = STEPS_PER_SECOND / 2,
            rallyHits = 0,
        )
    }

    /**
     * One step.
     *
     * [rng] advances only when the ball is served, so two devices that step the same number of
     * times draw the same number of randoms and stay in lockstep.
     */
    fun step(state: State, left: Input, right: Input, rng: Rng): State {
        var s = state.copy(step = state.step + 1)

        s = s.copy(
            leftY = movePaddle(s.leftY, left),
            rightY = movePaddle(s.rightY, right),
        )

        if (s.serveDelay > 0) return s.copy(serveDelay = s.serveDelay - 1)

        var x = s.ballX + s.ballVx
        var y = s.ballY + s.ballVy
        var vx = s.ballVx
        var vy = s.ballVy
        var hits = s.rallyHits

        // Walls.
        if (y - BALL_RADIUS < 0) { y = BALL_RADIUS.toDouble(); vy = abs(vy) }
        if (y + BALL_RADIUS > HEIGHT) { y = (HEIGHT - BALL_RADIUS).toDouble(); vy = -abs(vy) }

        // Paddles. The check is on the crossing rather than on overlap, which is what stops a fast
        // ball from passing through a paddle between two steps.
        val leftFace = (PADDLE_INSET + PADDLE_WIDTH).toDouble()
        val rightFace = (WIDTH - PADDLE_INSET - PADDLE_WIDTH).toDouble()

        if (vx < 0 && s.ballX - BALL_RADIUS >= leftFace && x - BALL_RADIUS <= leftFace) {
            if (abs(y - s.leftY) <= PADDLE_HEIGHT / 2.0 + BALL_RADIUS) {
                x = leftFace + BALL_RADIUS
                val bounced = bounce(vx, vy, y, s.leftY)
                vx = bounced.first; vy = bounced.second
                hits++
            }
        }
        if (vx > 0 && s.ballX + BALL_RADIUS <= rightFace && x + BALL_RADIUS >= rightFace) {
            if (abs(y - s.rightY) <= PADDLE_HEIGHT / 2.0 + BALL_RADIUS) {
                x = rightFace - BALL_RADIUS
                val bounced = bounce(vx, vy, y, s.rightY)
                vx = bounced.first; vy = bounced.second
                hits++
            }
        }

        // Points.
        if (x < -BALL_RADIUS) {
            val scored = s.copy(rightScore = s.rightScore + 1, lastScorer = Side.RIGHT)
            return if (isOver(scored)) scored else serve(scored, rng, toward = Side.LEFT)
        }
        if (x > WIDTH + BALL_RADIUS) {
            val scored = s.copy(leftScore = s.leftScore + 1, lastScorer = Side.LEFT)
            return if (isOver(scored)) scored else serve(scored, rng, toward = Side.RIGHT)
        }

        return s.copy(ballX = x, ballY = y, ballVx = vx, ballVy = vy, rallyHits = hits)
    }

    /**
     * The bounce that makes Pong a game rather than a screensaver.
     *
     * Where the ball hits the paddle sets the angle — middle sends it flat, edge sends it steep —
     * so a player has an actual shot to aim, and speed creeps up through a rally so a long one ends.
     */
    private fun bounce(vx: Double, vy: Double, ballY: Double, paddleY: Double): Pair<Double, Double> {
        val offset = ((ballY - paddleY) / (PADDLE_HEIGHT / 2.0)).coerceIn(-1.0, 1.0)
        val speed = (kotlin.math.hypot(vx, vy) * 1.045).coerceAtMost(17.0)
        val angle = offset * 0.85
        val newVx = -sign(vx) * speed * kotlin.math.cos(angle)
        val newVy = speed * kotlin.math.sin(angle)
        return newVx to newVy
    }

    private fun movePaddle(y: Double, input: Input): Double {
        val target = input.targetY
        val moved = when {
            target != null -> {
                // Dragging: the paddle chases the finger rather than teleporting, so a mesh
                // opponent sees a paddle that moves at a legal speed.
                val delta = (target - y).coerceIn(-PADDLE_SPEED * 2.2, PADDLE_SPEED * 2.2)
                y + delta
            }
            input.up && !input.down -> y - PADDLE_SPEED
            input.down && !input.up -> y + PADDLE_SPEED
            else -> y
        }
        val half = PADDLE_HEIGHT / 2.0
        return moved.coerceIn(half, HEIGHT - half)
    }

    /**
     * The computer's thumb.
     *
     * ## Why the error is per rally and not per step
     *
     * The obvious implementation — aim at the predicted point, plus a random wobble, every step —
     * produces an AI that never misses at any difficulty. The wobble is re-rolled sixty times on
     * the way to the ball and averages to zero, so the paddle converges on the exact right place
     * regardless of how large the error is supposed to be. Two of these played each other for
     * fifty-five minutes without scoring a point, which is how the bug was found.
     *
     * So the error is drawn ONCE per hit, from the rally count and the score, and held for the
     * whole approach. A weak AI now commits to being in the wrong place, which is what being weak
     * looks like.
     *
     * ## Why there is no random source passed in
     *
     * Determinism. Both ends of a mesh game run this, and an AI whose aim depends on how many
     * randoms the caller happened to draw would desynchronise the moment one device dropped a
     * frame. Everything here is a function of the state.
     */
    fun aiInput(state: State, side: Side, difficulty: Difficulty): Input {
        val mine = state.paddleOf(side)
        val coming = if (side == Side.LEFT) state.ballVx < 0 else state.ballVx > 0

        val aim = if (!coming) {
            // Drift home when the ball is going away. Also what makes it beatable.
            HEIGHT / 2.0
        } else {
            val face = if (side == Side.LEFT) (PADDLE_INSET + PADDLE_WIDTH).toDouble()
            else (WIDTH - PADDLE_INSET - PADDLE_WIDTH).toDouble()
            val steps = ((face - state.ballX) / state.ballVx).coerceIn(0.0, 400.0)
            var predicted = state.ballY + state.ballVy * steps
            // Fold the prediction back off the walls, the way the ball will actually bounce.
            val span = HEIGHT.toDouble()
            predicted = ((predicted % (2 * span)) + 2 * span) % (2 * span)
            if (predicted > span) predicted = 2 * span - predicted
            predicted + aimError(state, side, difficulty)
        }

        val delta = (aim - mine).coerceIn(-difficulty.topSpeed, difficulty.topSpeed)
        return Input(targetY = mine + delta)
    }

    /**
     * How far off this AI is on this particular ball.
     *
     * Scaled by the ball's speed as well as by the difficulty, so a rally that has been going long
     * enough to reach the speed cap eventually beats even the hardest setting. Without that, two
     * Brutal paddles rally forever: at full speed the paddle still covers more ground per step than
     * the ball does vertically, and a perfect predictor simply never loses.
     */
    private fun aimError(state: State, side: Side, difficulty: Difficulty): Double {
        val rng = Rng(
            Rng.seedOf(side.name, state.rallyHits, state.leftScore, state.rightScore, difficulty.name)
        )
        val speed = kotlin.math.hypot(state.ballVx, state.ballVy)
        val pace = 0.55 + (speed / 17.0) * 0.8
        return (rng.nextDouble() - 0.5) * (1.0 - difficulty.reaction) * 150 * pace
    }
}
