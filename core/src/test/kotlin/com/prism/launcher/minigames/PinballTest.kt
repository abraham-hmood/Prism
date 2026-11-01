package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinballTest {

    private fun ready(seed: Long = 1L) = Pinball.newGame(seed)

    @Test
    fun `a new game waits in the lane with three balls`() {
        val game = ready()
        assertTrue(game.inLane)
        assertEquals(Pinball.BALLS_PER_GAME, game.ballsLeft)
        assertEquals(Pinball.State.Status.READY, game.status)
    }

    @Test
    fun `launching sends the ball up the table`() {
        val game = Pinball.launch(ready())
        assertFalse(game.inLane)
        assertTrue(game.vy < 0, "launch should give upward velocity, was ${game.vy}")
        val stepped = Pinball.step(game, Pinball.Input())
        assertTrue(stepped.ballY < game.ballY)
    }

    @Test
    fun `a launched ball leaves the lane and travels the playfield`() {
        var game = Pinball.launch(ready())
        var bestY = game.ballY
        repeat(Pinball.STEPS_PER_SECOND * 2) {
            game = Pinball.step(game, Pinball.Input())
            bestY = minOf(bestY, game.ballY)
            if (game.inLane) return@repeat
        }
        assertTrue(bestY < 480.0, "the ball never climbed out of the lane: best y=$bestY")
    }

    @Test
    fun `generated tables never block the launch lane`() {
        repeat(24) { i ->
            val seed = i.toLong() + 100
            val table = Pinball.tableFor(seed)
            assertTrue(table.bumpers.size >= 3, "seed $seed generated only ${table.bumpers.size} bumpers")
            table.bumpers.forEach { bumper ->
                assertTrue(
                    bumper.x + bumper.radius < table.laneX - Pinball.BALL_RADIUS * 3,
                    "seed $seed put bumper ${bumper.id} into the launch lane",
                )
                assertTrue(bumper.y + bumper.radius < 470.0, "seed $seed put a bumper over the flippers")
            }
            var game = Pinball.launch(Pinball.newGame(seed, table))
            var bestY = game.ballY
            repeat(Pinball.STEPS_PER_SECOND * 2) {
                game = Pinball.step(game, Pinball.Input())
                bestY = minOf(bestY, game.ballY)
                if (game.inLane) game = Pinball.launch(game)
            }
            assertTrue(bestY < 480.0, "seed $seed never launched out of the lane: best y=$bestY")
        }
    }

    @Test
    fun `a launch is deflected out of the lane into the playfield`() {
        val table = Pinball.tableFor(4242L)
        var game = Pinball.launch(Pinball.newGame(4242L, table))
        var leftmost = game.ballX
        repeat(Pinball.STEPS_PER_SECOND * 3) {
            game = Pinball.step(game, Pinball.Input())
            leftmost = minOf(leftmost, game.ballX)
            if (game.inLane) game = Pinball.launch(game)
        }
        assertTrue(
            leftmost < 340.0,
            "the ball never crossed from the launch lane into the playfield: leftmost x=$leftmost",
        )
    }

    @Test
    fun `the same seed generates the same table`() {
        assertEquals(Pinball.tableFor(77L), Pinball.tableFor(77L))
        assertTrue(Pinball.tableFor(77L) != Pinball.tableFor(78L))
    }

    @Test
    fun `gravity brings the ball back down`() {
        var game = Pinball.launch(ready())
        var apex = game.ballY
        var drained = false
        // Two seconds of table time: the ball leaves the lane, reaches an apex, and comes back
        // down. It may be caught by a flipper or a sling on the way -- that is the table working --
        // so the honest gravity check is that it is below its own apex, not below the plunger.
        repeat(Pinball.STEPS_PER_SECOND * 2) {
            if (game.inLane) {
                drained = true
                return@repeat
            }
            game = Pinball.step(game, Pinball.Input())
            apex = minOf(apex, game.ballY)
        }
        assertTrue(
            drained || game.ballY > apex + 1.0,
            "ball never fell from its apex: apex=$apex y=${game.ballY}",
        )
    }

    @Test
    fun `a fast ball cannot tunnel through a wall`() {
        val table = Pinball.Table(
            walls = listOf(Pinball.Segment(20.0, 300.0, 400.0, 300.0)),
            bumpers = emptyList(),
            slings = emptyList(),
            leftFlipper = Pinball.Flipper(10.0, 500.0, 40.0, 0.2, -0.4),
            rightFlipper = Pinball.Flipper(200.0, 500.0, 40.0, 3.0, 3.4),
            drainY = 600.0,
            laneX = 50.0,
        )
        val falling = Pinball.State(
            ballX = 200.0,
            ballY = 280.0,
            vx = 0.0,
            vy = Pinball.MAX_SPEED,
            score = 0,
            ballsLeft = 3,
            leftAngle = table.leftFlipper.restAngle,
            rightAngle = table.rightFlipper.restAngle,
            step = 0,
        )

        val bounced = Pinball.step(falling, Pinball.Input(), table)

        assertTrue(
            bounced.ballY <= 300.0 - Pinball.BALL_RADIUS + 0.01,
            "ball tunnelled through the wall: y=${bounced.ballY}",
        )
        assertTrue(bounced.vy < 0, "wall should reflect the ball upward, was ${bounced.vy}")
    }

    @Test
    fun `a fast ball is returned to the side of the wall it came from`() {
        // A vertical wall and a ball crossing it in one tick. The old collision measured the
        // normal from the ball's NEW centre, which is on the far side, so it pushed the ball
        // through the wall and reported a bounce. The previous centre is the side information the
        // resolver needs; this test pins that down so the normal cannot regress.
        val table = Pinball.Table(
            walls = listOf(Pinball.Segment(300.0, 100.0, 300.0, 500.0)),
            bumpers = emptyList(),
            slings = emptyList(),
            leftFlipper = Pinball.Flipper(10.0, 600.0, 40.0, 0.2, -0.4),
            rightFlipper = Pinball.Flipper(350.0, 600.0, 40.0, 3.0, 3.4),
            drainY = 900.0,
            laneX = 50.0,
        )
        val crossing = Pinball.State(
            ballX = 280.0,
            ballY = 300.0,
            vx = Pinball.MAX_SPEED,
            vy = 0.0,
            score = 0,
            ballsLeft = 3,
            leftAngle = table.leftFlipper.restAngle,
            rightAngle = table.rightFlipper.restAngle,
            step = 0,
        )

        val bounced = Pinball.step(crossing, Pinball.Input(), table)

        assertTrue(
            bounced.ballX <= 300.0 - Pinball.BALL_RADIUS + 0.01,
            "ball ended on the far side of the wall: x=${bounced.ballX}",
        )
        assertTrue(bounced.vx < 0, "wall should reflect the ball back left, was ${bounced.vx}")
    }

    @Test
    fun `bumpers score and kick the ball away`() {
        val bumper = Pinball.Bumper(100.0, 100.0, 20.0, points = 150, id = "test")
        val table = Pinball.Table(
            walls = emptyList(),
            bumpers = listOf(bumper),
            slings = emptyList(),
            leftFlipper = Pinball.Flipper(10.0, 500.0, 40.0, 0.2, -0.4),
            rightFlipper = Pinball.Flipper(200.0, 500.0, 40.0, 3.0, 3.4),
            drainY = 600.0,
            laneX = 50.0,
        )
        val ball = Pinball.State(
            ballX = 100.0,
            ballY = 78.0,
            vx = 0.0,
            vy = 2.0,
            score = 0,
            ballsLeft = 3,
            leftAngle = table.leftFlipper.restAngle,
            rightAngle = table.rightFlipper.restAngle,
            step = 0,
        )
        val hit = Pinball.step(ball, Pinball.Input(), table)
        assertEquals(150, hit.score)
        assertEquals("test", hit.lit)
        assertTrue(hit.vy < 0, "the bumper should push the ball back up, was ${hit.vy}")
    }

    @Test
    fun `flippers move toward the pressed position`() {
        val rest = ready()
        val pressed = Pinball.step(rest, Pinball.Input(left = true, right = true))
        assertTrue(pressed.leftAngle < rest.leftAngle, "left flipper should swing up")
        assertTrue(pressed.rightAngle > rest.rightAngle, "right flipper should swing up")

        val released = Pinball.step(pressed, Pinball.Input())
        assertTrue(released.leftAngle > pressed.leftAngle, "left flipper should fall back")
    }

    @Test
    fun `a drained ball comes back to the lane and the game eventually ends`() {
        var game = Pinball.launch(ready())
        var steps = 0
        var returned = false
        while (game.status != Pinball.State.Status.GAME_OVER && steps < Pinball.STEPS_PER_SECOND * 240) {
            game = Pinball.step(game, Pinball.Input())
            steps++
            if (game.inLane) {
                returned = true
                game = Pinball.launch(game)
            }
        }
        assertTrue(returned, "the ball never drained in four minutes of table time")
        assertEquals(Pinball.State.Status.GAME_OVER, game.status)
        assertEquals(0, game.ballsLeft)
        assertTrue(game.score >= 0)
    }

    @Test
    fun `the same inputs replay the same game`() {
        fun play(): Pinball.State {
            var game = Pinball.launch(Pinball.newGame(4L))
            repeat(600) { i ->
                val input = Pinball.Input(
                    left = i % 37 in 0..6,
                    right = i % 53 in 0..6,
                )
                game = Pinball.step(game, input)
            }
            return game
        }
        val a = play()
        val b = play()
        assertEquals(a, b)
    }

    @Test
    fun `a game over state does not keep stepping`() {
        val over = ready().copy(status = Pinball.State.Status.GAME_OVER, ballsLeft = 0)
        assertEquals(over, Pinball.step(over, Pinball.Input(left = true, right = true)))
    }
}
