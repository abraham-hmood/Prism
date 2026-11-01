package com.prism.core

import com.prism.launcher.minigames.DoomGame
import com.prism.launcher.minigames.FpsInput
import com.prism.launcher.minigames.FpsStatus
import com.prism.launcher.minigames.MinigameSfx
import com.prism.launcher.minigames.ZombieKit
import com.prism.launcher.minigames.ZombieSnapshotCodec
import com.prism.launcher.minigames.ZombiesGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FpsGamesTest {

    @Test
    fun doomFloorIsPlayableFromTheFirstFrame() {
        val game = DoomGame(runSeed = 42L, level = 1, difficulty = 1)
        val first = game.scene()
        assertTrue(first.health > 0f)
        assertTrue(game.enemies.isNotEmpty())
        assertTrue(game.players.size == 1)
        repeat(240) { game.update(1f / 60f, FpsInput(forward = 0.6f, fire = true)) }
        assertEquals(FpsStatus.RUNNING, game.status)
        assertTrue(game.time > 3.5)
    }

    @Test
    fun doomExitClearsTheFloorWhenNoEnemiesRemain() {
        val game = DoomGame(runSeed = 7L, level = 1, difficulty = 0)
        game.enemies.forEach { it.health = 0f }
        val player = game.human
        player.x = game.map.exitX
        player.y = game.map.exitY
        game.update(1f / 60f, FpsInput())
        assertEquals(FpsStatus.WON, game.status)
        assertEquals(1, game.scene().status)
    }

    @Test
    fun zombiesStartsWithFourSlotsAndASpawnableWave() {
        val game = ZombiesGame(runSeed = 99L, aiTeammates = 3)
        assertEquals(4, game.players.size)
        assertTrue(game.players.count { it.isBot } == 3)
        repeat(300) { game.update(1f / 60f, mapOf(0 to FpsInput(forward = 0.2f, fire = true))) }
        assertEquals(FpsStatus.RUNNING, game.status)
        assertTrue(game.time > 4.0)
        assertTrue(game.wave >= 1)
        assertTrue(game.enemies.isNotEmpty(), "the first wave should have spawned hunters")
    }

    @Test
    fun zombieSnapshotSurvivesTheWireFormat() {
        val game = ZombiesGame(runSeed = 1234L, aiTeammates = 3)
        repeat(120) { game.update(1f / 60f, mapOf(0 to FpsInput(forward = 0.4f))) }
        val snapshot = game.snapshot()
        val encoded = ZombieSnapshotCodec.encode(snapshot)
        val decoded = assertNotNull(ZombieSnapshotCodec.decode(encoded))
        assertEquals(snapshot.seed, decoded.seed)
        assertEquals(snapshot.wave, decoded.wave)
        assertEquals(snapshot.players.size, decoded.players.size)
        assertEquals(snapshot.enemies.size, decoded.enemies.size)
        assertEquals(snapshot.players.first().name, decoded.players.first().name)
    }

    @Test
    fun baseMeshesMeetTheEightyThousandTriangleFloor() {
        val gun = ZombieKit.gun(1L)
        val walker = ZombieKit.zombie(1L)
        val brute = ZombieKit.zombie(1L, brute = true)
        val map = ZombieKit.mapKit(1L)
        assertTrue(gun.triangleCount >= 80_000, "gun: ${gun.triangleCount}")
        assertTrue(walker.triangleCount >= 80_000, "walker: ${walker.triangleCount}")
        assertTrue(brute.triangleCount >= 80_000, "brute: ${brute.triangleCount}")
        assertTrue(map.triangleCount >= 80_000, "map: ${map.triangleCount}")
    }

    @Test
    fun texturesAreOneThousandTwentyFourSquare() {
        val set = ZombieKit.pbr(ZombieKit.Material.WALL, seed = 5L)
        assertEquals(1024, set.albedo.width)
        assertEquals(1024, set.albedo.height)
        assertEquals(1024, set.normal.width)
        assertEquals(1024, set.ao.height)
        val distinct = set.albedo.pixels.toSet().size
        assertTrue(distinct > 8, "albedo should not be one flat colour, saw $distinct")
    }

    @Test
    fun layoutIsAReadableGridWithSpawnAndExit() {
        val layout = ZombieKit.layout(77L)
        assertTrue(layout.contains("\"rows\""))
        assertTrue(layout.contains('S'))
        assertTrue(layout.contains('E'))
        assertTrue(layout.contains('#'))
        assertTrue(layout.contains('.'))
    }

    @Test
    fun generatedWavHasAValidHeader() {
        val bytes = MinigameSfx.wav(MinigameSfx.Sound.SHOT)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertTrue(bytes.size > 44)
    }
}
