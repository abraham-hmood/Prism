package com.prism.core

import com.prism.launcher.minigames.Echo
import com.prism.launcher.minigames.EchoAssets
import com.prism.launcher.minigames.EchoMonster
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parts of Echo Chamber that can be wrong without anybody noticing on a screen: whether the
 * exit is reachable, whether sound -- and only sound -- reveals the maze, and whether a hunter
 * actually responds to a shout.
 */
class EchoChamberTest {

    @Test
    fun `maze always connects the spawn to the exit`() {
        repeat(8) { seed ->
            val maze = Echo.EchoMaze.generate(seed.toLong() + 1)
            val startX = maze.spawnX.toInt()
            val startY = maze.spawnY.toInt()
            val endX = maze.exitX.toInt()
            val endY = maze.exitY.toInt()
            assertFalse(maze.wall(startX, startY), "spawn is in a wall")
            assertFalse(maze.wall(endX, endY), "exit is in a wall")

            val seen = HashSet<Pair<Int, Int>>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(startX to startY)
            seen.add(startX to startY)
            while (queue.isNotEmpty()) {
                val (x, y) = queue.removeFirst()
                for (d in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val next = (x + d.first) to (y + d.second)
                    if (next in seen) continue
                    if (maze.wall(next.first, next.second)) continue
                    seen.add(next)
                    queue.add(next)
                }
            }
            assertTrue(endX to endY in seen, "seed $seed has no path from spawn to exit")
        }
    }

    @Test
    fun `standing still is dark and a shout reveals and marks surfaces`() {
        val world = Echo.World(seed = 44L, difficulty = 1, enemyCount = 2, playerIndex = 0)
        val before = world.revealed.count { it }
        // One frame of standing still must not reveal anything new.
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertEquals(before, world.revealed.count { it })
        assertTrue(world.marks.isEmpty())

        world.update(Echo.STEP_SECONDS, Echo.Input(shout = true))
        assertTrue(world.ripples.isNotEmpty())
        repeat(90) { world.update(Echo.STEP_SECONDS, Echo.Input()) }
        assertTrue(world.revealed.count { it } > before, "no surfaces were revealed by the shout")
        assertTrue(world.marks.isNotEmpty(), "no returning marks were created")
    }

    @Test
    fun `hunters spawn on open floor and never inside a wall`() {
        repeat(12) { index ->
            val world = Echo.World(seed = index.toLong() + 50, difficulty = 1, enemyCount = 4, playerIndex = 0)
            world.enemies.forEach { hunter ->
                assertFalse(
                    world.maze.blocked(hunter.x, hunter.y, Echo.PLAYER_RADIUS * 0.85),
                    "seed $index spawned hunter ${hunter.id} inside a wall at ${hunter.x},${hunter.y}",
                )
            }
        }
    }

    @Test
    fun `an alerted hunter actually walks through the maze`() {
        val world = Echo.World(seed = 17L, difficulty = 1, enemyCount = 2, playerIndex = 0)
        val hunter = world.enemies.first()
        val startX = hunter.x
        val startY = hunter.y
        hunter.state = Echo.EnemyState.HUNT
        hunter.targetX = world.playerX
        hunter.targetY = world.playerY
        repeat(180) {
            world.update(Echo.STEP_SECONDS, Echo.Input())
            assertFalse(
                world.maze.blocked(hunter.x, hunter.y, Echo.PLAYER_RADIUS * 0.85),
                "the hunter walked into a wall at ${hunter.x},${hunter.y}",
            )
        }
        assertTrue(
            hypot(hunter.x - startX, hunter.y - startY) > 0.4,
            "the hunter never moved from ${startX},${startY}",
        )
        assertTrue(hunter.gait > 0.1, "the hunter's tentacle gait never advanced")
    }

    @Test
    fun `marks on the walls do not expire`() {
        val world = Echo.World(seed = 44L, difficulty = 1, enemyCount = 0, playerIndex = 0)
        world.update(Echo.STEP_SECONDS, Echo.Input(shout = true))
        // Let the wavefront finish expanding and leave every one of its marks.
        repeat(300) { world.update(Echo.STEP_SECONDS, Echo.Input()) }
        val marked = world.marks.size
        assertTrue(marked > 0)
        // Well past the old two-second glow and eight-second scent.
        repeat(600) { world.update(Echo.STEP_SECONDS, Echo.Input()) }
        assertEquals(marked, world.marks.size, "marks were removed from the walls")
    }

    @Test
    fun `a shout wakes a hunter`() {
        val world = Echo.World(seed = 7L, difficulty = 2, enemyCount = 3, playerIndex = 0)
        val hunter = world.enemies.first()
        hunter.x = world.playerX + 1.4
        hunter.y = world.playerY
        world.update(Echo.STEP_SECONDS, Echo.Input(shout = true))
        assertEquals(Echo.EnemyState.HUNT, hunter.state)
        assertTrue(hunter.visibleUntil > world.clock)
    }

    @Test
    fun `two strikes kill a hunter and score`() {
        val world = Echo.World(seed = 9L, difficulty = 1, enemyCount = 1, playerIndex = 0)
        val hunter = world.enemies.first()
        hunter.x = world.playerX + 0.8
        hunter.y = world.playerY
        hunter.state = Echo.EnemyState.STUNNED
        hunter.stunUntil = world.clock + 10.0
        world.playerAngle = 0.0
        world.strike()
        assertTrue(world.lastStrikeHit)
        assertEquals(1, world.tags)
        world.strikeCooldown = 0.0
        world.strike()
        assertFalse(hunter.alive)
        assertEquals(1, world.kills)
        // 25 for the tag that stunned it, then 100 for the kill.
        assertEquals(125, world.score)
    }

    @Test
    fun `generated art is 1024 and the models have geometry`() {
        val pack = EchoAssets.pack
        assertEquals(1024 * 1024, pack.wall.size)
        assertEquals(1024 * 1024, pack.floor.size)
        assertEquals(1024 * 1024, pack.ceiling.size)
        assertEquals(1024 * 1024, pack.enemySkin.size)
        assertEquals(1024 * 1024, pack.exitSkin.size)
        assertEquals(1024 * 1024, pack.enemySprite.size)
        assertTrue(pack.enemyMesh.faces.isNotEmpty())
        assertTrue(pack.exitMesh.faces.isNotEmpty())
        assertTrue(pack.enemyMesh.vertices.size >= 9)
        assertTrue(pack.exitMesh.vertices.size >= 9)
    }

    @Test
    fun `projection puts a model in front of the camera and not behind it`() {
        val mesh = EchoAssets.pack.enemyMesh
        val front = EchoAssets.project(
            mesh, 5.0, 5.0, 0.2, 5.0, 4.0, Math.PI / 2, Echo.FOV, 800, 600,
        )
        assertTrue(front.isNotEmpty())
        val behind = EchoAssets.project(
            mesh, 5.0, 3.0, 0.2, 5.0, 4.0, Math.PI / 2, Echo.FOV, 800, 600,
        )
        assertTrue(behind.isEmpty())
    }

    @Test
    fun `a patrol follows the marks a shout left on the walls`() {
        val world = Echo.World(seed = 31L, difficulty = 1, enemyCount = 1, playerIndex = 0)
        val hunter = world.enemies.first()
        // Park the hunter in a corridor cell away from the player and drop a fresh trail near it.
        world.playerX = world.maze.spawnX
        world.playerY = world.maze.spawnY
        hunter.x = world.playerX + 4.0
        hunter.y = world.playerY
        hunter.state = Echo.EnemyState.PATROL
        // Deliberately older than the glow, so the test pins the permanent trail rather than
        // only the two-second visual. It goes through addMark so the cell buckets see it too.
        world.addMark(
            Echo.EchoMark(
                hunter.x + 0.4, hunter.y, 190,
                world.clock - (Echo.MARK_SECONDS + 0.5), Echo.MARK_SECONDS,
                hunter.x - 2.0, hunter.y,
            ),
        )
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertEquals(Echo.EnemyState.HUNT, hunter.state)
        assertTrue(hypot(hunter.targetX - hunter.x, hunter.targetY - hunter.y) < 1.5)
    }

    @Test
    fun `reaching the player is a jumpscare before the loss`() {
        val world = Echo.World(seed = 33L, difficulty = 1, enemyCount = 1, playerIndex = 0)
        val hunter = world.enemies.first()
        world.playerHealth = 5
        hunter.x = world.playerX + 0.3
        hunter.y = world.playerY
        hunter.attackCooldown = 0.0
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertTrue(world.dying)
        assertEquals(Echo.Status.RUNNING, world.status)
        assertTrue(world.jumpscare > 0.0)
        assertEquals(hunter.id, world.jumpscareEnemy)

        repeat(300) { world.update(Echo.STEP_SECONDS, Echo.Input()) }
        assertFalse(world.dying)
        assertEquals(Echo.Status.LOST, world.status)
    }

    @Test
    fun `hunter sprite has maws, eyes, green colonies and transparent margins`() {
        val sprite = EchoAssets.pack.enemySprite
        var opaque = 0
        var purple = 0
        var green = 0
        var clear = 0
        for (pixel in sprite) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 180) {
                clear++
                continue
            }
            opaque++
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            if (b > r && b > g) purple++
            if (g > r && g > b) green++
        }
        assertTrue(opaque > 200_000, "the body is too small: $opaque opaque pixels")
        assertTrue(purple > 20_000, "no violet flesh was painted")
        assertTrue(green > 8_000, "no olive colonies were painted")
        assertTrue(clear > 400_000, "the sprite has no transparent margin for tentacles")

        // A disposable copy for visual review; the test itself only asserts the pixels above.
        val output = File("build/echo-hunter-sprite.png")
        output.parentFile?.mkdirs()
        val image = BufferedImage(EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE, sprite, 0, EchoMonster.SPRITE_SIZE)
        ImageIO.write(image, "png", output)
    }

    @Test
    fun `monster billboard and tentacles stay finite and move with the gait`() {
        val body = EchoMonster.billboard(
            5.0, 5.0, 5.0, 4.0, PI / 2, Echo.FOV, 800, 600,
        )
        assertNotNull(body)
        val still = EchoMonster.tentacles(body, gait = 0.0, clock = 0.0, seed = 7)
        val stepping = EchoMonster.tentacles(body, gait = 4.0, clock = 2.0, seed = 7)
        assertEquals(EchoMonster.TENTACLES, still.size)
        assertTrue(still.all { EchoMonster.isFinite(it) })
        assertTrue(stepping.all { EchoMonster.isFinite(it) })
        assertNotEquals(still.first().centerX.last(), stepping.first().centerX.last())
        assertTrue(
            EchoMonster.billboard(5.0, 3.0, 5.0, 4.0, PI / 2, Echo.FOV, 800, 600) == null,
            "a hunter behind the camera was drawn",
        )
    }

    @Test
    fun `a mesh guest does not win by spawning on the exit`() {
        val world = Echo.World(seed = 21L, difficulty = 1, enemyCount = 0, playerIndex = 1)
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertEquals(Echo.Status.RUNNING, world.status)

        // Walk away, then return: now the exit is a destination rather than a starting tile.
        world.playerX = world.maze.spawnX
        world.playerY = world.maze.spawnY
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertEquals(Echo.Status.RUNNING, world.status)
        world.playerX = world.maze.exitX
        world.playerY = world.maze.exitY
        world.update(Echo.STEP_SECONDS, Echo.Input())
        assertEquals(Echo.Status.WON, world.status)
    }

    @Test
    fun `ray cast finds the corridor wall it points at`() {
        val world = Echo.World(seed = 11L, difficulty = 1, enemyCount = 0, playerIndex = 0)
        val hit = world.maze.cast(world.playerX, world.playerY, 0.0, 20.0)
        assertNotNull(hit)
        assertTrue(hit!!.dist > 0.1)
        assertTrue(hypot(hit.x - world.playerX, hit.y - world.playerY) <= 20.0)
    }
}
