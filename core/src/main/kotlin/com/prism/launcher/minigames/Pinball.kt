package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pinball, as a table of segments, circles and two flippers.
 *
 * ## Fixed steps, like every other game here
 *
 * The ball is integrated at [STEPS_PER_SECOND] steps a second regardless of frame time. A slow
 * frame therefore runs several steps instead of one long one, which is the difference between a
 * ball that tunnels through a wall on a bad frame and a ball that does not. It also makes the whole
 * table a pure function of its state and its inputs, so a replay is a list of flipper presses.
 *
 * ## The table is geometry, not a bitmap
 *
 * Walls are line segments, bumpers are circles and a flipper is a rotating segment. Collision is a
 * closest-point test against each, which is small enough to be obviously correct and fast enough
 * for a few dozen shapes at 120 Hz. A polygonal outline traced from a picture would look better and
 * would need a content pipeline this project does not have.
 */
object Pinball {

    const val WIDTH = 420
    const val HEIGHT = 720

    const val BALL_RADIUS = 8.0
    const val GRAVITY = 0.34
    const val STEPS_PER_SECOND = 120
    const val STEP_MILLIS = 1000.0 / STEPS_PER_SECOND
    const val MAX_SPEED = 26.0

    /** How hard the plunger sends the ball up the lane. */
    const val LAUNCH_SPEED = 21.0

    /** Restitution of walls. Above 1 a wall adds energy, which turns the table into a pinball machine. */
    const val WALL_BOUNCE = 0.72
    const val BUMPER_BOUNCE = 1.24
    const val SLING_BOUNCE = 1.42

    const val BALLS_PER_GAME = 3

    data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val bounce: Double = WALL_BOUNCE)

    data class Bumper(val x: Double, val y: Double, val radius: Double, val points: Int, val id: String)

    data class Flipper(val pivotX: Double, val pivotY: Double, val length: Double, val restAngle: Double, val activeAngle: Double) {
        fun tip(angle: Double): Pair<Double, Double> =
            pivotX + cos(angle) * length to pivotY + sin(angle) * length
    }

    /**
     * A static table.
     *
     * Held as a value so the tests can build a small table of their own; the shipped one is
     * [table].
     */
    data class Table(
        val walls: List<Segment>,
        val bumpers: List<Bumper>,
        val slings: List<Segment>,
        val leftFlipper: Flipper,
        val rightFlipper: Flipper,
        val drainY: Double,
        val laneX: Double,
    )

    data class State(
        val ballX: Double,
        val ballY: Double,
        val vx: Double,
        val vy: Double,
        val score: Int,
        val ballsLeft: Int,
        val leftAngle: Double,
        val rightAngle: Double,
        val step: Long,
        /** True while the ball is waiting in the launch lane. */
        val inLane: Boolean = false,
        val status: Status = Status.PLAYING,
        /** Which bumper is lit from the last hit, for the renderer. */
        val lit: String? = null,
        /** Milliseconds-equivalent of the flash, decremented per step. */
        val litFor: Int = 0,
        val best: Int = 0,
    ) {
        enum class Status { READY, PLAYING, GAME_OVER }
    }

    /** What the player is doing this step. */
    data class Input(val left: Boolean = false, val right: Boolean = false, val launch: Boolean = false)

    // ── The shipped table ─────────────────────────────────────────────────

    private fun l(x1: Double, y1: Double, x2: Double, y2: Double, bounce: Double = WALL_BOUNCE) =
        Segment(x1, y1, x2, y2, bounce)

    private fun arc(cx: Double, cy: Double, radius: Double, from: Double, to: Double, steps: Int = 10): List<Segment> {
        val out = ArrayList<Segment>(steps)
        var previous = from
        for (i in 1..steps) {
            val angle = from + (to - from) * i / steps
            out.add(
                l(
                    cx + cos(previous) * radius, cy + sin(previous) * radius,
                    cx + cos(angle) * radius, cy + sin(angle) * radius,
                )
            )
            previous = angle
        }
        return out
    }

    /**
     * The table: a rounded top, two side walls, a launch lane on the right, three bumpers, two
     * slingshots and the flippers.
     *
     * Coordinates are in table units with the origin at the top left. The renderer scales the whole
     * thing to whatever it has, which is why none of these numbers know about pixels.
     */
    val table: Table by lazy {
        val walls = ArrayList<Segment>()
        // Outer shell, from the lane mouth round the top and down the left.
        walls += l(376.0, 700.0, 376.0, 512.0, 0.55)   // lane divider
        walls += l(404.0, 700.0, 404.0, 120.0)
        walls += arc(210.0, 120.0, 194.0, 0.0, -Math.PI)
        walls += l(16.0, 120.0, 16.0, 560.0)
        // Funnels into the drain.
        walls += l(16.0, 560.0, 120.0, 640.0)
        walls += l(404.0, 560.0, 300.0, 640.0)
        walls += l(120.0, 640.0, 120.0, 700.0)
        walls += l(300.0, 640.0, 300.0, 700.0)

        val bumpers = listOf(
            Bumper(132.0, 248.0, 26.0, points = 100, id = "left"),
            Bumper(268.0, 232.0, 24.0, points = 150, id = "right"),
            Bumper(200.0, 330.0, 21.0, points = 75, id = "centre"),
        )

        // Slingshots sit just above each flipper and kick the ball back up.
        val slings = listOf(
            l(96.0, 470.0, 140.0, 548.0, SLING_BOUNCE),
            l(324.0, 470.0, 280.0, 548.0, SLING_BOUNCE),
        )

        Table(
            walls = walls,
            bumpers = bumpers,
            slings = slings,
            leftFlipper = Flipper(pivotX = 132.0, pivotY = 604.0, length = 76.0, restAngle = 0.30, activeAngle = -0.62),
            rightFlipper = Flipper(pivotX = 288.0, pivotY = 604.0, length = 76.0, restAngle = Math.PI - 0.30, activeAngle = Math.PI + 0.62),
            drainY = 716.0,
            laneX = 390.0,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun newGame(seed: Long, table: Table = Pinball.table): State {
        // The seed is not used for physics -- the table is not random. It is kept so a future
        // multiplayer table with a skill shot or a random award replays from the same value, and it
        // is deliberately part of the signature rather than added later.
        @Suppress("UNUSED_VARIABLE")
        val unusedSeed = seed
        return State(
            ballX = table.laneX,
            ballY = 640.0,
            vx = 0.0,
            vy = 0.0,
            score = 0,
            ballsLeft = BALLS_PER_GAME,
            leftAngle = table.leftFlipper.restAngle,
            rightAngle = table.rightFlipper.restAngle,
            step = 0,
            inLane = true,
            status = State.Status.READY,
        )
    }

    /** The player pulls the plunger. Only does anything while the ball is in the lane. */
    fun launch(state: State): State {
        if (!state.inLane || state.status == State.Status.GAME_OVER) return state
        return state.copy(
            vx = -0.6,
            vy = -LAUNCH_SPEED,
            inLane = false,
            status = State.Status.PLAYING,
        )
    }

    fun isOver(state: State): Boolean = state.status == State.Status.GAME_OVER

    // ── Physics ───────────────────────────────────────────────────────────

    /**
     * One step.
     *
     * The flippers move first, because the impulse they impart depends on how fast they were
     * travelling when the ball reached them.
     */
    fun step(state: State, input: Input, table: Table = Pinball.table): State {
        if (state.status == State.Status.GAME_OVER) return state

        val leftBefore = state.leftAngle
        val rightBefore = state.rightAngle
        val leftAngle = moveFlipper(leftBefore, if (input.left) table.leftFlipper.activeAngle else table.leftFlipper.restAngle, up = input.left)
        val rightAngle = moveFlipper(rightBefore, if (input.right) table.rightFlipper.activeAngle else table.rightFlipper.restAngle, up = input.right)

        var s = state.copy(leftAngle = leftAngle, rightAngle = rightAngle, step = state.step + 1)
        s = s.copy(litFor = (s.litFor - 1).coerceAtLeast(0), lit = if (s.litFor <= 1) null else s.lit)

        if (s.inLane) {
            if (input.launch) s = launch(s)
            return s
        }

        var x = s.ballX + s.vx
        var y = s.ballY + s.vy
        var vx = s.vx
        var vy = s.vy + GRAVITY
        var score = s.score
        var lit = s.lit
        var litFor = s.litFor

        fun clampSpeed() {
            val speed = hypot(vx, vy)
            if (speed > MAX_SPEED) {
                vx = vx / speed * MAX_SPEED
                vy = vy / speed * MAX_SPEED
            }
        }

        // Walls and slings: closest point on the segment, then push out and reflect.
        val solids = table.walls + table.slings
        for (seg in solids) {
            val (px, py) = closestPoint(seg, x, y)
            val dx = x - px
            val dy = y - py
            val distance = hypot(dx, dy)
            if (distance < BALL_RADIUS && distance > 0.0001) {
                val nx = dx / distance
                val ny = dy / distance
                x = px + nx * BALL_RADIUS
                y = py + ny * BALL_RADIUS
                val dot = vx * nx + vy * ny
                vx = (vx - 2 * dot * nx) * seg.bounce
                vy = (vy - 2 * dot * ny) * seg.bounce
                if (seg.bounce > 1.0) {
                    // A slingshot adds an impulse along its normal rather than merely reflecting.
                    vx += nx * 3.2
                    vy += ny * 3.2
                    score += 25
                }
            }
        }

        // Bumpers.
        for (bumper in table.bumpers) {
            val dx = x - bumper.x
            val dy = y - bumper.y
            val distance = hypot(dx, dy)
            val reach = BALL_RADIUS + bumper.radius
            if (distance < reach && distance > 0.0001) {
                val nx = dx / distance
                val ny = dy / distance
                x = bumper.x + nx * reach
                y = bumper.y + ny * reach
                val dot = vx * nx + vy * ny
                vx = (vx - 2 * dot * nx) * BUMPER_BOUNCE
                vy = (vy - 2 * dot * ny) * BUMPER_BOUNCE
                score += bumper.points
                lit = bumper.id
                litFor = STEPS_PER_SECOND / 3
            }
        }

        // Flippers, as moving segments.
        fun hitFlipper(flipper: Flipper, angle: Double, previous: Double) {
            val (tipX, tipY) = flipper.tip(angle)
            val seg = Segment(flipper.pivotX, flipper.pivotY, tipX, tipY)
            val (px, py) = closestPoint(seg, x, y)
            val dx = x - px
            val dy = y - py
            val distance = hypot(dx, dy)
            if (distance >= BALL_RADIUS || distance <= 0.0001) return
            val nx = dx / distance
            val ny = dy / distance
            x = px + nx * BALL_RADIUS
            y = py + ny * BALL_RADIUS
            val dot = vx * nx + vy * ny
            vx = (vx - 2 * dot * nx) * 0.62
            vy = (vy - 2 * dot * ny) * 0.62
            // The flipper's surface speed at the contact point, converted into a kick. This is what
            // makes a flipper a flipper and not a wall with a hinge.
            val angularVelocity = angle - previous
            if (abs(angularVelocity) > 0.0001) {
                val rx = px - flipper.pivotX
                val ry = py - flipper.pivotY
                val surfaceVx = -angularVelocity * ry
                val surfaceVy = angularVelocity * rx
                vx += surfaceVx * 2.6
                vy += surfaceVy * 2.6
                score += 10
            }
        }
        hitFlipper(table.leftFlipper, leftAngle, leftBefore)
        hitFlipper(table.rightFlipper, rightAngle, rightBefore)

        clampSpeed()

        s = s.copy(
            ballX = x,
            ballY = y,
            vx = vx,
            vy = vy,
            score = score,
            lit = lit,
            litFor = litFor,
        )

        // Drain.
        if (y > table.drainY) {
            val balls = s.ballsLeft - 1
            if (balls <= 0) {
                return s.copy(
                    ballsLeft = 0,
                    status = State.Status.GAME_OVER,
                    best = maxOf(s.best, s.score),
                    vx = 0.0,
                    vy = 0.0,
                )
            }
            return s.copy(
                ballX = table.laneX,
                ballY = 640.0,
                vx = 0.0,
                vy = 0.0,
                inLane = true,
                ballsLeft = balls,
                status = State.Status.READY,
                lit = null,
                litFor = 0,
            )
        }

        return s
    }

    private fun moveFlipper(current: Double, target: Double, up: Boolean): Double {
        val speed = if (up) 0.62 else 0.34
        val delta = target - current
        if (abs(delta) <= speed) return target
        return current + if (delta > 0) speed else -speed
    }

    /** Closest point on a segment to (x, y). */
    fun closestPoint(segment: Segment, x: Double, y: Double): Pair<Double, Double> {
        val dx = segment.x2 - segment.x1
        val dy = segment.y2 - segment.y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001) return segment.x1 to segment.y1
        val t = (((x - segment.x1) * dx + (y - segment.y1) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return (segment.x1 + t * dx) to (segment.y1 + t * dy)
    }

    /** Marketing copy for the menu, computed rather than typed twice. */
    fun describe(state: State): String = when (state.status) {
        State.Status.READY -> "Ball ${BALLS_PER_GAME - state.ballsLeft + 1} of $BALLS_PER_GAME · pull to launch"
        State.Status.PLAYING -> "${state.score} points"
        State.Status.GAME_OVER -> "Final score ${state.score}"
    }
}
