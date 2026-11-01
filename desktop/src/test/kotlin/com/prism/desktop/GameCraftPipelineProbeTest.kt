package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.assetcraft.AssetCraft
import com.prism.launcher.assetcraft.GameCraft
import com.prism.launcher.assetcraft.MeshCamera
import com.prism.launcher.assetcraft.MeshView
import com.prism.launcher.diffusion.LocalDiffusionGenerator
import com.prism.launcher.messaging.ImageGeneration
import com.prism.launcher.modelcraft.ModelCraftImageGenerator
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
 * The end-to-end GameCraft run: Forgekeeper builds the meshes and the scene, qwen2.5-coder writes the
 * code through GameCraft's own text route, AbsoluteReality draws the hero albedo, Prism textures the
 * rest, and the game is compiled and played.
 *
 * ## Why this is opt-in
 *
 * It needs a game model, a diffusion checkpoint, a running Ollama and a C++ toolchain, and it spends
 * minutes rendering. A checkout with none of those should not fail; a machine with all of them should
 * prove the whole chain rather than a unit of it. `PRISM_GAMECRAFT_PROBE=1` turns it on.
 *
 * ## What "playable" is checked as
 *
 * The compiled binary runs twice without a window: `--replay` drives the generated `update()` for
 * 240 frames and reports how far the player moved, and `--shot` renders a frame to BMP. Movement
 * proves the model's code is in the binary and actually runs; the shot proves the meshes and albedos
 * made it into the pak and the rasteriser draws them. The interactive window is the same loop with
 * a Win32 message pump around it.
 */
class GameCraftPipelineProbeTest {

    private lateinit var previousHost: com.prism.core.PlatformHost
    private lateinit var root: File

    private val output = File("C:/Users/bremo/model_craft_games")
    private val checkpoint = File("C:/Users/bremo/Downloads/absolutereality_v181.safetensors")
    private val forgekeeper = File("C:/Users/bremo/model_craft_games/forgekeeper")

    private val prompt =
        "a tentacled abyssal creature circles a glowing crystal altar in a flooded arena"

    @BeforeTest
    fun installHost() {
        previousHost = PrismPlatform.host
        root = Files.createTempDirectory("prism-gamecraft-pipeline").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
        ImageGeneration.reset()
        ImageGeneration.register(LocalDiffusionGenerator())
        ImageGeneration.register(ModelCraftImageGenerator())
    }

    @AfterTest
    fun restore() {
        ImageGeneration.reset()
        PrismPlatform.host = previousHost
        root.deleteRecursively()
    }

    /**
     * The toolchain leg on its own, so the native build can be iterated without a diffusion render.
     *
     * `PRISM_GAMECRAFT_BUILD_ONLY=1`. It writes a two-node scene from Forgekeeper, a hand-written
     * movement script, compiles it and runs both headless modes. A failure here is a build or runner
     * bug; the full probe adds the models on top.
     */
    @Test
    fun `the native build produces a binary that runs the script`() {
        if (System.getenv("PRISM_GAMECRAFT_BUILD_ONLY") != "1") return
        val imported = assertNotNull(AssetCraft.importGameFolder(forgekeeper), "no game model to sample")
        AssetCraft.setActiveGame(imported)
        val hero = assertNotNull(AssetCraft.sampleActive(prompt, 991L), "sample failed")
        val prop = assertNotNull(AssetCraft.sampleActive("a ruined stone watchtower", 992L), "sample failed")
        val scene = com.prism.launcher.assetcraft.MeshScene().apply {
            add(com.prism.launcher.assetcraft.MeshNode(hero.mesh))
            add(com.prism.launcher.assetcraft.MeshNode(prop.mesh, x = 2.2f, z = -1.4f, scale = 0.8f))
        }
        val albedo = GameCraft.proceduralTexture(prompt, hero.mesh)
        val propAlbedo = GameCraft.proceduralTexture(prompt, prop.mesh)
        val directory = File(root, "build-probe").apply { mkdirs() }
        val source = """
            #include <cmath>
            void update(float* player, float moveX, float moveZ, float turn, float dt) {
                player[0] += moveX * dt * 3.0f;
                player[2] += moveZ * dt * 3.0f;
                player[3] += turn * dt * 2.0f;
                player[1] = sin(player[3]) * 0.05f;
            }
        """.trimIndent()
        val program = com.prism.launcher.assetcraft.GameCode.compile(source, directory)
        assertTrue(program.executable, "the probe script did not parse: ${program.note}")
        val build = com.prism.launcher.assetcraft.GameBuild.build(
            directory, program, scene, prompt, "build probe", listOf(albedo, propAlbedo),
        )
        // The model's own shapes, framed by the same viewport the app uses, so the pipeline's
        // "playable" question can be separated from "is this mesh recognisable".
        listOf("hero" to hero.mesh, "prop" to prop.mesh).forEach { (name, mesh) ->
            val bounds = mesh.bounds()
            log("$name bounds: ${bounds.width} x ${bounds.height} x ${bounds.depth}, ${mesh.triangleCount} tris")
            val preview = MeshView.render(
                com.prism.launcher.assetcraft.MeshScene().apply { add(com.prism.launcher.assetcraft.MeshNode(mesh)) },
                640, 640,
                MeshCamera(yaw = 0.65f, pitch = 0.3f, distance = 5.2f),
                0f, -1,
            )
            PrismPlatform.images.encodePng(
                PrismImage(640, 640, preview),
                File("../.artifacts/gamecraft-build-$name.png"),
            )
        }
        log("build: ${build.note}")
        log(File(build.log).readText())
        val binary = assertNotNull(build.binary, "no binary: ${File(build.log).readText()}")
        val replay = File(root, "build-replay.txt")
        val replayRun = run(
            listOf(binary.absolutePath, "--pak", File(directory, "game.pak").absolutePath,
                "--replay", replay.absolutePath, "--frames", "120"),
            directory,
        )
        log("replay exit=${replayRun.exit}: ${replayRun.output.trim()}")
        assertEquals(0, replayRun.exit, "replay failed: ${replayRun.output}")
        val shot = File(root, "build-shot.bmp")
        val shotRun = run(
            listOf(binary.absolutePath, "--pak", File(directory, "game.pak").absolutePath,
                "--shot", shot.absolutePath),
            directory,
        )
        log("shot exit=${shotRun.exit}: ${shotRun.output.trim()}")
        assertEquals(0, shotRun.exit, "shot failed: ${shotRun.output}")
        assertTrue(shot.copyTo(File(".artifacts/gamecraft-build-shot.bmp"), overwrite = true).isFile)
    }

    @Test
    fun `a model scene, generated code and a rendered texture become a playable game`() {
        if (System.getenv("PRISM_GAMECRAFT_PROBE") != "1") return
        assertTrue(forgekeeper.isDirectory, "the trained game model is missing: ${forgekeeper.absolutePath}")
        assertTrue(checkpoint.isFile, "the diffusion checkpoint is missing: ${checkpoint.absolutePath}")

        val imported = assertNotNull(
            AssetCraft.importGameFolder(forgekeeper),
            "GameCraft refused the trained game model",
        )
        AssetCraft.setActiveGame(imported)

        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL_CLOUD)
        PrismSettings.setSelectedOllamaEndpoint(PrismSettings.OllamaEndpoint("127.0.0.1", 11434, "qwen2.5-coder:3b"))
        PrismSettings.setAgenticToolsEnabled(true)
        PrismSettings.setLocalImageModelPath(checkpoint.absolutePath)

        val gate = GameCraft.gate()
        log("mesh: ${gate.meshName} | code: ${gate.canCode} (${gate.textName}) | texture: ${gate.canTexture} (${gate.imageName})")
        assertTrue(gate.ready, "no mesh generator: ${gate.reason}")
        assertTrue(gate.canCode, "the text model is not usable for code: ${gate.informative}")
        assertTrue(gate.canTexture, "the image model is not usable for textures")
        assertTrue(gate.sceneCapable, "Forgekeeper is not treated as a scene-capable ModelCraft model")

        assertTrue(GameCraft.generate(prompt), "the generator refused the prompt")
        // A CPU-only diffusion render at 512 plus a 1024 refinement is minutes, not seconds, and
        // the code and compile legs come after it. The budget is generous so a slow machine
        // reports the real result instead of a timeout that says nothing about the pipeline.
        val deadline = System.currentTimeMillis() + 90 * 60_000L
        var lastStage = ""
        while (System.currentTimeMillis() < deadline) {
            val state = GameCraft.state.value
            if (state.stage != lastStage) {
                lastStage = state.stage
                log("stage: $lastStage (${(state.progress * 100).toInt()}%)")
            }
            if (!state.generating) break
            Thread.sleep(250)
        }
        val state = GameCraft.state.value
        assertTrue(!state.generating, "generation never finished; last stage: ${state.stage}")
        assertEquals(null, state.error, "generation failed: ${state.error}")
        assertTrue(state.complete, "the maker did not report a finished game")

        // ── The assets ────────────────────────────────────────────────────────────────────────
        val scene = state.scene
        assertTrue(scene.nodes.size >= 3, "the scene has only ${scene.nodes.size} meshes")
        scene.nodes.forEachIndexed { index, node ->
            assertTrue(
                node.mesh.triangleCount >= GameCraft.TARGET_TRIANGLES,
                "asset $index is ${node.mesh.triangleCount} triangles, below ${GameCraft.TARGET_TRIANGLES}",
            )
            log("asset $index: ${node.mesh.name} · ${node.mesh.category} · ${node.mesh.vertexCount} verts · ${node.mesh.triangleCount} tris")
        }
        assertEquals(1024, state.texture?.width, "the hero albedo is not 1024 wide")
        assertEquals(1024, state.texture?.height, "the hero albedo is not 1024 tall")

        val summary = assertNotNull(
            GameCraft.games().firstOrNull { it.id == state.gameId },
            "the finished game is not in the library",
        )
        assertTrue(summary.hasBinary, "no compiled binary was produced")

        // ── The binary actually runs the generated code ───────────────────────────────────────
        val binary = File(summary.directory, "game.exe").takeIf { it.isFile }
            ?: File(summary.directory, "game")
        assertTrue(binary.isFile, "the binary ${binary.name} is missing")
        val replay = File(root, "replay.txt")
        val replayRun = run(listOf(binary.absolutePath, "--pak", File(summary.directory, "game.pak").absolutePath,
            "--replay", replay.absolutePath, "--frames", "240"), summary.directory)
        log("replay exit=${replayRun.exit} ${replayRun.output.trim()}")
        assertEquals(0, replayRun.exit, "the binary's replay failed: ${replayRun.output}")
        val report = replay.readText()
        val moved = Regex("moved=([0-9.]+)").find(report)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        assertTrue(moved > 0.10f, "the compiled script moved the player only $moved units")

        val shot = File(root, "shot.bmp")
        val shotRun = run(listOf(binary.absolutePath, "--pak", File(summary.directory, "game.pak").absolutePath,
            "--shot", shot.absolutePath), summary.directory)
        log("shot exit=${shotRun.exit} ${shotRun.output.trim()}")
        assertEquals(0, shotRun.exit, "the binary's frame render failed: ${shotRun.output}")
        assertTrue(shot.length() > 100_000, "the rendered frame is only ${shot.length()} bytes")

        // ── The look-at-it pass, exported for the user ────────────────────────────────────────
        val destination = File(output, slug(state.gameName))
        assertTrue(GameCraft.export(summary, destination), "the game could not be exported")
        val assets = File(destination, "assets")
        val previews = File(destination, "previews").apply { mkdirs() }
        val rows = mutableListOf<String>()
        scene.nodes.forEachIndexed { index, node ->
            val albedoFile = File(assets, "%02d-%s.png".format(
                index,
                node.mesh.category.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "asset" },
            ))
            val albedo = if (albedoFile.isFile) {
                PrismPlatform.images.decode(albedoFile, 1024)
            } else {
                null
            }
            if (albedo != null) {
                assertEquals(1024, albedo.width, "saved albedo ${albedoFile.name} lost its resolution")
            }
            val preview = renderPreview(node.mesh, index, albedo, previews)
            rows += "| ${node.mesh.name} | ${node.mesh.category} | ${node.mesh.triangleCount} | " +
                "${albedo?.let { "${it.width} x ${it.height}" } ?: "—"} | `assets/${albedoFile.name}` | " +
                "`previews/${preview.name}` |"
        }
        shot.copyTo(File(previews, "native-${binary.nameWithoutExtension}-frame.bmp"), overwrite = true)

        val metadata = File(summary.directory, "game.json").readText()
        val reportFile = File(destination, "REPORT.md")
        reportFile.writeText(
            buildString {
                appendLine("# GameCraft pipeline run — ${state.gameName}")
                appendLine()
                appendLine("Prompt: \"$prompt\"")
                appendLine()
                appendLine("- Mesh + scene model: ${gate.meshName} (Forgekeeper, ModelCraft, trained on real meshes)")
                appendLine("- Code model: qwen2.5-coder:3b through Ollama, asked by GameCraft's own text route")
                appendLine("- Hero texture model: AbsoluteReality v1.81 through Prism's bundled stable-diffusion.cpp")
                appendLine("- Scene textures: Prism's procedural TextureForge at 1024 x 1024")
                appendLine("- Binary: ${binary.name}, built by Prism with ${if (System.getProperty("os.name").contains("win")) "MSVC" else "the host C++ compiler"}")
                appendLine("- Replay: moved $moved units over 240 frames (exit 0)")
                appendLine()
                appendLine("## Assets")
                appendLine()
                appendLine("| Mesh | Category | Triangles | Albedo | Texture | Preview |")
                appendLine("| --- | --- | ---: | ---: | --- | --- |")
                rows.forEach { appendLine(it) }
                appendLine()
                appendLine("## the compiled script")
                appendLine()
                appendLine("```cpp")
                appendLine(state.code.ifBlank { "(no model code; the built-in movement was attached)" })
                appendLine("```")
                appendLine()
                appendLine("## Metadata")
                appendLine()
                appendLine("```json")
                appendLine(metadata)
                appendLine("```")
                appendLine()
                appendLine("The build log is `native/build.log`; the scene pak is `game.pak`.")
            },
        )
        log("exported ${destination.absolutePath}")
        log("native frame: ${File(previews, "native-${binary.nameWithoutExtension}-frame.bmp").absolutePath}")
    }

    /** Renders the scene exactly as the app's viewport does, at 1024 square, and saves a PNG. */
    private fun renderPreview(
        mesh: com.prism.launcher.assetcraft.GameMesh,
        index: Int,
        albedo: PrismImage?,
        directory: File,
    ): File {
        val side = 1024
        val scene = com.prism.launcher.assetcraft.MeshScene().apply {
            add(com.prism.launcher.assetcraft.MeshNode(mesh))
        }
        val pixels = MeshView.render(
            scene,
            side,
            side,
            MeshCamera(yaw = 0.65f, pitch = 0.3f, distance = 5.2f),
            0f,
            -1,
        )
        val image = PrismImage(side, side, pixels)
        val preview = File(directory, "%02d-%s-preview.png".format(index, slug(mesh.category)))
        PrismPlatform.images.encodePng(image, preview)
        // Sidecar albedo at its native 1024, so the export shows the texture as well as the mesh.
        if (albedo != null) {
            PrismPlatform.images.encodePng(albedo, File(directory, "%02d-%s-albedo.png".format(index, slug(mesh.category))))
        }
        return preview
    }

    private data class Run(val exit: Int, val output: String)

    private fun run(command: List<String>, working: File): Run = runCatching {
        val process = ProcessBuilder(command).directory(working).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(4, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        Run(if (finished) process.exitValue() else -1, text)
    }.getOrElse { Run(-1, it.message ?: it.javaClass.simpleName) }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64).ifBlank { "game" }

    private fun log(message: String) {
        println("[gamecraft] $message")
        System.out.flush()
    }
}
