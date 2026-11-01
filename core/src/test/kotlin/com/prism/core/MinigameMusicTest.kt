package com.prism.core

import com.prism.launcher.minigames.MinigameMusic
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MinigameMusicTest {

    @Test
    fun barIsAudibleAndNotClipping() {
        val bar = MinigameMusic.renderBar("snake", intensity = 0.5f, bar = 0, seed = 3)
        assertTrue(bar.size > 10_000, "a bar should be at least half a second, was ${bar.size}")
        val peak = bar.maxOf { abs(it.toInt()) }
        assertTrue(peak > 2_000, "the bar is effectively silent (peak $peak)")
        assertTrue(peak <= Short.MAX_VALUE, "the bar clips (peak $peak)")
    }

    @Test
    fun sameInputsGiveTheSameBar() {
        val first = MinigameMusic.renderBar("split", 0.4f, 2, 11)
        val second = MinigameMusic.renderBar("split", 0.4f, 2, 11)
        assertContentEquals(first, second)
    }

    @Test
    fun tempoRisesWithIntensity() {
        val calm = MinigameMusic.renderBar("tetris", 0f, 0)
        val hot = MinigameMusic.renderBar("tetris", 1f, 0)
        assertTrue(
            hot.size < calm.size,
            "higher intensity must mean a shorter bar (calm ${calm.size}, hot ${hot.size})",
        )
    }

    @Test
    fun differentGamesAreDifferentKeys() {
        val snake = MinigameMusic.renderBar("snake", 0.3f, 0)
        val split = MinigameMusic.renderBar("split", 0.3f, 0)
        assertTrue(!snake.contentEquals(split), "two cabinets should not share the exact same tune")
    }
}
