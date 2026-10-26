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
    fun `gravity brings the ball back down`() {
        var game = Pinball.launch(ready())
        val startY = game.ballY
        var drained = false
        // Two seconds of table time: the ball rises, falls back past its launch point, and either
        // drains (gravity did its job) or is still on the table below where it started.
        repeat(Pinball.STEPS_PER_SECOND * 2) {
            if (game.inLane) {
                drained = true
                return@repeat
            }
            game = Pinball.step(game, Pinball.Input())
        }
        assertTrue(drained || game.ballY > startY, "ball neither fell nor drained: y=${game.ballY}")
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
