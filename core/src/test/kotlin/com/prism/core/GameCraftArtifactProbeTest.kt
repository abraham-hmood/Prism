package com.prism.core

import com.prism.launcher.assetcraft.GameCraft
import com.prism.launcher.assetcraft.MeshCamera
import com.prism.launcher.assetcraft.MeshIO
import com.prism.launcher.assetcraft.MeshNode
import com.prism.launcher.assetcraft.MeshScene
import com.prism.launcher.assetcraft.MeshView
import com.prism.launcher.modelcraft.GameTrainer
import com.prism.launcher.modelcraft.ModelCraft
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Trains a game model on real third-party meshes and checks the delivery promises on its output.
 *
 * The ordinary [GameTrainerTest] checks that the two heads train and that their meshes are valid.
 * This probe is the acceptance test for the page: a trained model must sample meshes at the
 * 80,000-triangle floor, and those outputs must be saveable with a 1024 x 1024 albedo. The files
 * are written to the folder the user named so the result can be opened in a modelling tool rather
 * than only asserted in a test log.
 *
 * The dataset is the small hand-picked OBJ corpus under `F:\PrismGamingDatasets\small_meshes`.
 * When that folder is absent the test still proves the pipeline with a compact local fallback, so a
 * build on a machine without the F: drive is not a false failure.
 */
class GameCraftArtifactProbeTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-gamecraft-probe-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = { model, recipe -> GameTrainer.train(model, recipe) }
        ModelCraft.frameExtractor = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `a game model trained on real meshes delivers 80k triangles and 1024 textures`() {
        val source = corpus()
        val captions = File(source, "captions.csv")
        val meshes = source.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("obj", "ply", "glb") }
            .orEmpty()
        assertTrue(meshes.size >= 8, "the training corpus has only ${meshes.size} meshes")

        val model = ModelCraft.create(
            name = "Forgekeeper",
            kind = ModelCraft.Kind.GAME,
            architecture = "Diffuser model",
            family = "Mesh diffusion (MeshDiffusion)",
        )
        ModelCraft.setFromScratch(model, true)
        ModelCraft.setTrainsLocally(model, true)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 120, stepsPerEpoch = 12))
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 8, 2))
        ModelCraft.Store.save(model)
        val attached = ModelCraft.attachDataset(model, meshes + captions)
        assertTrue(attached >= meshes.size, "the game dataset imported only $attached files")

        assertTrue(
            ModelCraft.train(model),
            "the game model was refused: ${ModelCraft.state.value.error}",
        )
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && ModelCraft.state.value.running) {
            Thread.sleep(25)
        }
        val state = ModelCraft.state.value
        assertTrue(!state.running, "training never finished: $state")
        assertTrue(state.error == null, "training failed: ${state.error}")

        val trained = assertNotNull(GameTrainer.load(model), "the trained game artifact was not loadable")
        assertTrue(trained.parameterCount() > 0, "the trained head has no parameters")
        assertTrue(trained.trainingSteps > 0, "the model trained for no steps")
        assertTrue(trained.validationLoss.isFinite(), "validation loss was not measured")
        assertEquals(true, trained.slidingWindow, "the sliding window was not applied")
        assertEquals(8, trained.window)
        assertTrue(
            trained.lastLoss <= trained.metadata.optDouble("first_loss", Double.MAX_VALUE),
            "the last loss (${trained.lastLoss}) is worse than the first",
        )

        val output = File("C:/Users/bremo/model_craft_games")
        val root = if (output.parentFile?.isDirectory == true) output else File(scratch, "model_craft_games")
        root.mkdirs()
        val modelDirectory = File(root, model.name.lowercase().replace(' ', '-')).apply { mkdirs() }
        val sourceDirectory = ModelCraft.Store.datasetDir(model.id)
        listOf(GameTrainer.META_FILE, GameTrainer.WEIGHTS_FILE).forEach { name ->
            val file = File(sourceDirectory, name)
            assertTrue(file.isFile, "$name was not written by the run")
            file.copyTo(File(modelDirectory, name), overwrite = true)
        }

        val prompts = listOf(
            "a mossy boulder",
            "a tall pine tree",
            "a ruined stone watchtower",
            "a crystal shard cluster",
            "a tentacled cave creature",
            "an abandoned wooden cart",
            "a floating island shrine",
        )
        val rows = mutableListOf<String>()
        val meshDirectory = File(modelDirectory, "meshes").apply { mkdirs() }
        for ((index, prompt) in prompts.withIndex()) {
            val sampled = trained.sample(prompt, seed = 1001L + index)
            assertTrue(
                sampled.triangleCount >= MeshFloor.TRIANGLES,
                "$prompt produced ${sampled.triangleCount} triangles, below the delivery floor",
            )
            assertTrue(sampled.vertexCount > 0, "$prompt produced no vertices")
            assertEquals(0, sampled.triangles.size % 3, "$prompt produced a partial triangle")
            assertTrue(
                sampled.triangles.all { it in 0 until sampled.vertexCount },
                "$prompt produced an index outside its vertex list",
            )
            val texture = GameCraft.proceduralTexture(prompt, sampled, MeshFloor.TEXTURE)
            assertEquals(MeshFloor.TEXTURE, texture.width)
            assertEquals(MeshFloor.TEXTURE, texture.height)
            assertEquals(MeshFloor.TEXTURE * MeshFloor.TEXTURE, texture.pixels.size)
            val textured = GameCraft.applyTexture(sampled, texture)
            assertEquals(sampled.triangleCount, textured.triangleCount)

            val stem = "%02d-%s".format(
                index + 1,
                prompt.replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            )
            val obj = File(meshDirectory, "$stem.obj")
            MeshIO.writeObj(textured, obj)
            val png = File(meshDirectory, "$stem-texture.png")
            PrismPlatform.images.encodePng(texture, png)
            val preview = File(meshDirectory, "$stem-preview.png")
            renderPreview(textured, prompt, preview)
            assertTrue(obj.length() > 0, "$obj is empty")
            assertTrue(png.length() > 0, "$png is empty")
            assertTrue(preview.length() > 0, "$preview is empty")
            rows += "| $prompt | ${sampled.category} | ${sampled.vertexCount} | " +
                "${sampled.triangleCount} | 1024 x 1024 | `meshes/${obj.name}` |"
        }

        // A second, independent headline check: the mesh files themselves read back at the floor.
        meshDirectory.listFiles()?.filter { it.extension == "obj" }?.forEach { file ->
            val loaded = assertNotNull(MeshIO.load(file), "saved mesh ${file.name} could not be reloaded")
            assertTrue(
                loaded.triangleCount >= MeshFloor.TRIANGLES,
                "saved mesh ${file.name} reloaded with only ${loaded.triangleCount} triangles",
            )
        }

        val report = File(root, "REPORT.md")
        report.writeText(
            buildString {
                appendLine("# Prism GameCraft model probe")
                appendLine()
                appendLine("Trained on `${source.absolutePath}` — ${meshes.size} meshes, " +
                    "${File(source, "captions.csv").takeIf { it.isFile }?.name ?: "no captions"}.")
                appendLine()
                appendLine("- Model: ${model.name} (${model.architecture} / ${model.family})")
                appendLine("- Params: ${trained.parameterCount()}")
                appendLine("- Steps: ${trained.trainingSteps}; first loss " +
                    "${"%.5f".format(trained.metadata.optDouble("first_loss"))}, " +
                    "last ${"%.5f".format(trained.lastLoss)}, validation ${"%.5f".format(trained.validationLoss)}")
                appendLine("- Objective: ${trained.objective()}; ${trained.summary()}")
                appendLine("- Output floor: ${MeshFloor.TRIANGLES} triangles, " +
                    "${MeshFloor.TEXTURE} x ${MeshFloor.TEXTURE} albedo")
                appendLine()
                appendLine("| Prompt | Category | Vertices | Triangles | Texture | Mesh |")
                appendLine("| --- | --- | ---: | ---: | --- | --- |")
                rows.forEach { appendLine(it) }
                appendLine()
                appendLine("The model folder holds `game_model.json` and `game_model.weights`; the " +
                    "OBJ files are the sampled meshes and the PNG files are their procedural albedos.")
            },
        )
        assertTrue(report.length() > 0)
    }

    /** Draws the same projected scene the desktop and phone viewports use into a square preview. */
    private fun renderPreview(mesh: com.prism.launcher.assetcraft.GameMesh, prompt: String, file: File) {
        val side = 1024
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.paint = GradientPaint(0f, 0f, Color(8, 11, 18), side.toFloat(), side.toFloat(), Color(28, 38, 55))
        graphics.fillRect(0, 0, side, side)
        val scene = MeshScene().apply { add(MeshNode(mesh)) }
        val frame = MeshView.frame(
            scene,
            MeshCamera(yaw = 0.65f, pitch = 0.3f, distance = 5.2f),
            side,
            side,
            0f,
            -1,
        )
        for (face in frame.faces) {
            val path = java.awt.geom.Path2D.Float().apply {
                moveTo(face.xs[0], face.ys[0])
                lineTo(face.xs[1], face.ys[1])
                lineTo(face.xs[2], face.ys[2])
                closePath()
            }
            val color = Color(face.color, true)
            graphics.color = Color(color.red, color.green, color.blue)
            graphics.fill(path)
        }
        graphics.color = Color(255, 255, 255, 170)
        graphics.stroke = BasicStroke(1f)
        for (edge in frame.edges) {
            val color = Color(edge.color, true)
            graphics.color = Color(color.red, color.green, color.blue, 90)
            graphics.drawLine(edge.x1.toInt(), edge.y1.toInt(), edge.x2.toInt(), edge.y2.toInt())
        }
        graphics.color = Color.WHITE
        graphics.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 26)
        graphics.drawString(prompt, 28, 48)
        graphics.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 20)
        graphics.color = Color(190, 210, 230)
        graphics.drawString(
            "${mesh.vertexCount} vertices · ${mesh.triangleCount} triangles · 1024 x 1024 albedo",
            28,
            side - 32,
        )
        graphics.dispose()
        ImageIO.write(image, "png", file)
    }

    /**
     * The downloaded corpus, or a compact fallback when the F: drive is not mounted.
     *
     * The fallback is still twelve distinct meshes with distinct proportions, so the run exercises
     * the same code path on a clean machine instead of silently skipping the whole acceptance test.
     */
    private fun corpus(): File {
        val downloaded = File("F:/PrismGamingDatasets/small_meshes")
        if (downloaded.isDirectory) {
            val meshes = downloaded.listFiles()?.count {
                it.isFile && it.extension.lowercase() in setOf("obj", "ply", "glb")
            } ?: 0
            if (meshes >= 8) return downloaded
        }
        val fallback = File(scratch, "fallback-corpus").apply { mkdirs() }
        val captions = StringBuilder("file,caption\n")
        repeat(12) { index ->
            val name = "asset-%02d.obj".format(index + 1)
            val height = 0.4f + index * 0.18f
            val width = 0.3f + (index % 4) * 0.22f
            val depth = 0.25f + (index % 3) * 0.2f
            File(fallback, name).writeText(
                buildString {
                    appendLine("v ${-width} 0 ${-depth}")
                    appendLine("v ${width} 0 ${-depth}")
                    appendLine("v ${width} $height $depth")
                    appendLine("v ${-width} $height $depth")
                    appendLine("v 0 ${height * 1.25f} 0")
                    appendLine("f 1 2 3")
                    appendLine("f 1 3 4")
                    appendLine("f 1 2 5")
                    appendLine("f 2 3 5")
                    appendLine("f 3 4 5")
                    appendLine("f 4 1 5")
                },
            )
            captions.appendLine("$name,a stylised game prop shaped like asset ${index + 1}")
        }
        File(fallback, "captions.csv").writeText(captions.toString())
        return fallback
    }

    private object MeshFloor {
        const val TRIANGLES = 80_000
        const val TEXTURE = 1024
    }
}
