package com.prism.core

import com.prism.launcher.minigames.ZombiesAssets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generates the whole Zombies pack, not just the mesh arithmetic.
 *
 * The default suite should not spend a minute writing 25 PNGs and ten large OBJ files, so the
 * heavy build is opt-in. The cheap arithmetic and WAV-header checks run everywhere in
 * `FpsGamesTest`; this probe is the one that proves the files a person opens in Godot really exist.
 */
class ZombieGodotProbeTest {

    @Test
    fun fullPackWritesAPlayableGodotProject() {
        if (System.getenv("PRISM_ZOMBIES_PROBE") != "1") return
        val previous = PrismPlatform.host
        try {
            // The production host, so the pack lands where the phone's equivalent does and can be
            // opened directly instead of disappearing with a temp folder.
            PrismPlatform.host = JvmHost()
            ZombiesAssets.invalidate()
            val pack = ZombiesAssets.build(seed = 20261030L, includeTextures = true, force = true)

            assertTrue(pack.directory.isDirectory, "pack root")
            assertTrue(pack.layout.isFile, "layout.json")
            assertTrue(File(pack.godot, "project.godot").isFile, "project.godot")
            assertTrue(File(pack.godot, "scenes/main.tscn").isFile, "main.tscn")
            assertTrue(File(pack.godot, "scenes/player.tscn").isFile, "player.tscn")
            assertTrue(File(pack.godot, "scenes/zombie.tscn").isFile, "zombie.tscn")
            assertTrue(File(pack.godot, "scripts/game.gd").isFile, "game.gd")
            assertTrue(File(pack.godot, "scripts/player.gd").isFile, "player.gd")
            assertTrue(File(pack.godot, "scripts/zombie.gd").isFile, "zombie.gd")
            assertTrue(File(pack.godot, "assets/music/music_calm.wav").isFile, "calm loop")
            assertTrue(File(pack.godot, "assets/music/music_wave.wav").isFile, "wave loop")
            assertTrue(File(pack.godot, "assets/music/music_hunt.wav").isFile, "hunt loop")

            assertEquals(10, pack.meshes.size, "guns, full zombies, map kit and four rig parts")
            assertEquals(25, pack.textures.size, "five materials x five PBR maps")
            assertEquals(12, pack.sounds.size, "generated effects")
            assertEquals(3, pack.music.size, "generated music loops")
            pack.triangleCounts.forEach { (name, count) ->
                assertTrue(count > 0, "$name has triangles")
            }
            assertTrue(
                pack.triangleCounts["zombie_torso"]!! >= 80_000,
                "the rig's torso is itself a high-poly base: ${pack.triangleCounts["zombie_torso"]}",
            )
            assertTrue(pack.warnings.isEmpty(), "no pack warnings: ${pack.warnings}")
        } finally {
            PrismPlatform.host = previous
        }
    }
}
