package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.random.Random

class ArcadeGamesTest {

    @Test
    fun `every registered cabinet builds and produces a playable scene`() {
        ArcadeGames.ALL.forEach { info ->
            val game = assertNotNull(ArcadeGames.create(info.id), "no engine for ${info.id}")
            game.reset(7L)
            repeat(30) { game.update(0.016f) }
            game.input(ArcadeInput(tapX = 0.5f, tapY = 0.5f))
            game.input(ArcadeInput(action = ArcadeAction.NEXT))
            game.input(ArcadeInput(action = ArcadeAction.SWITCH))
            val scene = game.scene()
            assertTrue(scene.hud.isNotBlank(), "${info.id} has no HUD")
            assertTrue(scene.sprites.isNotEmpty(), "${info.id} drew an empty board")
        }
    }

    @Test
    fun `every cabinet survives a few hundred frames of random play`() {
        val random = Random(11)
        ArcadeGames.ALL.forEach { info ->
            val game = assertNotNull(ArcadeGames.create(info.id))
            game.reset(info.id.hashCode().toLong())
            repeat(600) {
                game.update(0.016f)
                when (random.nextInt(6)) {
                    0 -> game.input(ArcadeInput(tapX = random.nextFloat(), tapY = random.nextFloat()))
                    1 -> game.input(ArcadeInput(swipe = ArcadeAction.entries.random(random)))
                    2 -> game.input(ArcadeInput(action = ArcadeAction.entries.random(random)))
                    3 -> game.input(ArcadeInput(dragX = random.nextFloat()))
                    else -> Unit
                }
                game.scene()
            }
        }
    }

    @Test
    fun `chutes and ladders reaches a winner and survives a state round trip`() {
        val game = ChutesGame(players = 2, autoAi = false, seed = 42L)
        repeat(2_000) {
            if (game.over) return@repeat
            game.roll()
        }
        assertTrue(game.over, "two hundred turns did not finish a hundred-square board")
        assertTrue(game.winner >= 0)

        val state = game.stateLine()
        val copy = ChutesGame(players = 2, autoAi = false, seed = 99L)
        copy.applyLine(state)
        assertEquals(game.winner, copy.winner)
        assertTrue(copy.positions.contentEquals(game.positions))
    }

    @Test
    fun `a huge army is simulated with a bounded cast and still reported in full`() {
        val defender = WorldMap.aiBase(
            WorldMap.generateAi(worldSeed = 42, playerLevel = 120, count = 1).first()
        )
        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 120,
            attackerSoldiers = 5_000,
            attackerWeaponId = "longbow",
            orders = emptyList(),
            seed = 9L,
            defenderSortie = true,
        )
        assertTrue(playback.cast.size <= 400, "5000 soldiers produced ${playback.cast.size} figures")
        assertEquals(5_000, playback.result.attackersSent)
        assertTrue(playback.frames.first().positions.size <= 800)
    }

    @Test
    fun `the defending garrison leaves its posts and appears in the playback`() {
        val defender = WorldMap.aiBase(
            WorldMap.generateAi(worldSeed = 42, playerLevel = 60, count = 1).first()
        )
        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 60,
            attackerSoldiers = 40,
            attackerWeaponId = "longbow",
            orders = emptyList(),
            seed = 4L,
            defenderSortie = true,
        )
        assertTrue(playback.cast.guardCount > 0, "no defenders came out")
        val first = playback.frames.first()
        assertTrue(first.guardPositions.isNotEmpty())
        assertTrue(playback.frames.any { it.guardCount() > 0 })
    }
}
