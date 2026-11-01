package com.prism.core

import com.prism.launcher.minigames.ArcadeAction
import com.prism.launcher.minigames.ArcadeGames
import com.prism.launcher.minigames.ArcadeInput
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every cabinet must be able to run for a whole session.
 *
 * A crash on entry is the worst kind of bug a game can have: the player cannot get past it, and it
 * takes the launcher down with it. These runs are deliberately longer than a real session and fire
 * random input, because the failures that matter are the ones a happy path never reaches --
 * a food placement that cannot find a free cell, a puzzle generator that re-rolls forever, a list
 * mutated while it is being walked.
 */
class ArcadeGameTest {

    private val actions = ArcadeAction.entries.toTypedArray()

    @Test
    fun everyGameSurvivesALongSession() {
        val random = Random(20261030)
        for (info in ArcadeGames.ALL) {
            val game = ArcadeGames.create(info.id)
            requireNotNull(game) { "no game for ${info.id}" }
            game.reset(99)
            repeat(3_000) { frame ->
                if (frame % 7 == 0) {
                    val action = actions[random.nextInt(actions.size)]
                    val tap = random.nextFloat()
                    game.input(ArcadeInput(tapX = tap, tapY = random.nextFloat(), action = action))
                }
                game.update(0.016f)
                val scene = game.scene()
                assertTrue(scene.sprites.size < 2_000, "${info.id} produced ${scene.sprites.size} sprites")
                if (game.over) game.reset(random.nextLong())
            }
        }
    }

    @Test
    fun everyGameIsPlayableBeforeAnExplicitReset() {
        // The crash this covers: the renderer ticks a cabinet as soon as it is attached, but the
        // old factory returned a game that had not been dealt yet. Snake's body was empty and
        // `body.first()` killed the process before the first frame was drawn.
        for (info in ArcadeGames.ALL) {
            val game = requireNotNull(ArcadeGames.create(info.id)) { "no game for ${info.id}" }
            // The renderer draws before the first tick, so an undrawn scene is a crash too.
            game.scene()
            repeat(240) {
                game.update(0.016f)
                game.scene()
            }
        }
    }

    @Test
    fun snakeRunsPastTheFirstFood() {
        val snake = ArcadeGames.create("snake")!!
        snake.reset(7)
        repeat(4_000) { frame ->
            if (frame % 40 == 0) {
                snake.input(ArcadeInput(action = actions[Random(frame).nextInt(4)]))
            }
            snake.update(0.016f)
            snake.scene()
            if (snake.over) snake.reset(frame.toLong())
        }
    }

    @Test
    fun splitAlwaysBuildsAPlayablePuzzle() {
        // The generator re-rolls when a layout is already solved. A re-roll that recurses can eat
        // the stack before the player ever sees a board, so a hundred boards in a row is the check.
        repeat(100) { seed ->
            val split = ArcadeGames.create("split")!!
            split.reset(seed.toLong())
            val scene = split.scene()
            assertTrue(scene.sprites.isNotEmpty(), "empty split board for seed $seed")
            // "beam connected" on the first frame means the player was handed a finished puzzle
            // and every tap appeared to do nothing. It must always start unsolved and ask for a tap.
            assertTrue(
                scene.message != "beam connected",
                "seed $seed started already solved",
            )
        }
    }

    @Test
    fun splitRespondsToTaps() {
        val split = ArcadeGames.create("split")!!
        split.reset(4)
        val before = split.scene().hud
        // Tap every cell; the mirrors are somewhere on the 7x7 board and one of these must hit.
        for (row in 0 until 7) for (col in 0 until 7) {
            split.input(
                ArcadeInput(
                    tapX = (col + 0.5f) / 7f,
                    tapY = (row + 0.5f) / 7f,
                ),
            )
        }
        assertTrue(split.scene().hud != before, "tapping mirrors did not change anything")
    }
}
