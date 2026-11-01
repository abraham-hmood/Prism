package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.assetcraft.GameBuild
import com.prism.launcher.assetcraft.GameCode
import com.prism.launcher.assetcraft.GameCraft
import com.prism.launcher.assetcraft.GameMesh
import com.prism.launcher.assetcraft.MeshAnimation
import com.prism.launcher.assetcraft.MeshIO
import com.prism.launcher.assetcraft.MeshNode
import com.prism.launcher.assetcraft.MeshScene
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rebuilds the exported game's textures, pak and binary after a change to the procedural texture
 * generator, without spending another twenty minutes in the diffusion pass.
 *
 * The AbsoluteReality hero sheet is the real one the pipeline produced and is reused as-is. Only
 * the two Prism sheets are regenerated, because those are what the generator change affects. The
 * meshes are read back from the export's own OBJ files and the node transforms from its
 * `scene.json`, so the rebuilt pak is the scene that was exported, not an approximation of it.
 *
 * `PRISM_GAMECRAFT_REBUILD=1` turns it on.
 */
class GameCraftTextureRebuildTest {

    private lateinit var previousHost: com.prism.core.PlatformHost
    private var temp: File? = null

    private val export =
        File("C:/Users/bremo/model_craft_games/a-tentacled-abyssal-creature-circles-a")
    private val prompt =
        "a tentacled abyssal creature circles a glowing crystal altar in a flooded arena"
    private val name = "a tentacled abyssal creature circles a"

    private val palettes = mapOf(
        "building" to 0xFF8C7A5E.toInt(),
        "crystal" to 0xFF5FA8C8.toInt(),
    )

    @BeforeTest
    fun installHost() {
        previousHost = PrismPlatform.host
        val root = Files.createTempDirectory("prism-gamecraft-rebuild").toFile()
        temp = root
        PrismPlatform.host = JvmHost(rootOverride = root)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
        temp?.deleteRecursively()
    }

    @Test
    fun `rebuild the exported game with faceted textures`() {
        if (System.getenv("PRISM_GAMECRAFT_REBUILD") != "1") return
        assertTrue(export.isDirectory, "the exported game is missing: ${export.absolutePath}")

        val scene = scene()
        assertEquals(3, scene.nodes.size, "the export did not carry three meshes")

        // The hero sheet is the diffusion model's own output; nothing here regenerates it.
        val hero = assertNotNull(
            PrismPlatform.images.decode(File(export, "assets/00-creature.png"), 1024),
            "the exported hero albedo could not be read",
        )
        assertEquals(1024, hero.width)

        val albedos = scene.nodes.mapIndexed { index, node ->
            when (index) {
                0 -> hero
                else -> GameCraft.proceduralTexture(prompt, node.mesh)
            }
        }
        albedos.forEachIndexed { index, image ->
            assertEquals(1024, image.width, "albedo $index is not 1024 wide")
            assertEquals(1024, image.height, "albedo $index is not 1024 tall")
        }

        val build = Files.createTempDirectory("prism-gamecraft-rebuild-out").toFile()
        try {
            File(export, "game.cpp").copyTo(File(build, "game.cpp"), overwrite = true)
            val program = GameCode.compile(File(export, "game.cpp").readText(), build)
            val result = GameBuild.build(build, program, scene, prompt, name, albedos)
            val binary = assertNotNull(result.binary, "the rebuild produced no binary: ${result.log}")
            val replay = File(build, "replay.txt")
            val replayRun = run(
                listOf(binary.absolutePath, "--pak", File(build, "game.pak").absolutePath,
                    "--replay", replay.absolutePath, "--frames", "240"),
                build,
            )
            assertEquals(0, replayRun.exit, "the rebuilt game's replay failed: ${replayRun.output}")
            val moved = Regex("moved=([0-9.]+)").find(replay.readText())
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            assertTrue(moved > 0.10f, "the rebuilt game moved $moved units")

            val shot = File(build, "shot.bmp")
            val shotRun = run(
                listOf(binary.absolutePath, "--pak", File(build, "game.pak").absolutePath,
                    "--shot", shot.absolutePath),
                build,
            )
            assertEquals(0, shotRun.exit, "the rebuilt game could not render: ${shotRun.output}")
            assertTrue(shot.length() > 100_000, "the rebuilt frame is ${shot.length()} bytes")

            // The in-place export is only touched once the rebuild has proved it runs.
            File(build, "game.pak").copyTo(File(export, "game.pak"), overwrite = true)
            binary.copyTo(File(export, binary.name), overwrite = true)
            File(build, "native").copyRecursively(File(export, "native"), overwrite = true)
            scene.nodes.forEachIndexed { index, node ->
                val category = node.mesh.category
                val file = File(export, "assets/%02d-%s.png".format(index, category))
                if (index == 0) return@forEachIndexed
                PrismPlatform.images.encodePng(albedos[index], file)
                PrismPlatform.images.encodePng(albedos[index], File(export, "previews/%02d-%s-albedo.png".format(index, category)))
                small(albedos[index]).let {
                    PrismPlatform.images.encodePng(it, File(export, "previews/%02d-%s-albedo-small.png".format(index, category)))
                }
            }
            println("[rebuild] textures regenerated; replay moved $moved units")
        } finally {
            build.deleteRecursively()
        }
    }

    /** The exported scene, read back from its OBJ files and its node table. */
    private fun scene(): MeshScene {
        val text = File(export, "scene.json").readText()
        val entry = Regex(
            "\\{\"name\":\"([^\"]*)\",\"mesh\":\"([^\"]+)\",\"texture\":\"[^\"]+\"," +
                "\"category\":\"([^\"]+)\".*?\"x\":([-0-9.E]+),\"y\":([-0-9.E]+)," +
                "\"z\":([-0-9.E]+),\"scale\":([-0-9.E]+),\"yaw\":([-0-9.E]+)\\}",
        )
        val scene = MeshScene()
        entry.findAll(text).forEach { match ->
            val (label, path, category, x, y, z, scale, yaw) = match.destructured
            val loaded = assertNotNull(
                MeshIO.load(File(export, path), label),
                "the export's mesh $path could not be read",
            )
            val mesh = GameMesh(
                name = loaded.name,
                vertices = loaded.vertices,
                triangles = loaded.triangles,
                // The OBJ sidecar carries per-face colour only; the sheet's palette comes from the
                // asset's own colour, which the export does not repeat. Use the category palette the
                // pipeline drew with.
                color = palettes[category] ?: loaded.color,
                faceColors = loaded.faceColors,
                category = category,
                animation = if (category == "creature") MeshAnimation.WALK else MeshAnimation.IDLE,
            )
            scene.add(
                MeshNode(
                    mesh,
                    x = x.toFloat(), y = y.toFloat(), z = z.toFloat(),
                    scale = scale.toFloat(), yaw = yaw.toFloat(),
                ),
            )
        }
        return scene
    }

    /** A 512 copy for the report, box-filtered, so a 1.8 MB albedo still opens in a chat. */
    private fun small(image: PrismImage): PrismImage {
        val side = 512
        val step = (image.width / side).coerceAtLeast(1)
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in 0 until step) {
                    for (dx in 0 until step) {
                        val sx = (x * step + dx).coerceIn(0, image.width - 1)
                        val sy = (y * step + dy).coerceIn(0, image.height - 1)
                        val p = image.pixels[sy * image.width + sx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                pixels[y * side + x] = (0xFF shl 24) or
                    ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        return PrismImage(side, side, pixels)
    }

    private data class Run(val exit: Int, val output: String)

    private fun run(command: List<String>, working: File): Run = runCatching {
        val process = ProcessBuilder(command).directory(working).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(4, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        Run(if (finished) process.exitValue() else -1, text)
    }.getOrElse { Run(-1, it.message ?: it.javaClass.simpleName) }
}
