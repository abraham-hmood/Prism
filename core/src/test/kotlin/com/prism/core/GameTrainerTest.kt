package com.prism.core

import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.GameTrainer
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Creating and training a game model on this device.
 *
 * The important promises are not "a file was written": they are that both heads a game model can be
 * built with actually train, that a second model can use a first one as its base when the switch is
 * off, and that the sliding-window control reaches the trained artifact rather than only the form.
 * A model is then sampled and the mesh is checked to be valid geometry, because a trained file that
 * cannot become an asset is not a game model.
 */
class GameTrainerTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-game-trainer-test-${System.nanoTime()}",
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
    fun `diffusion and token game models both train and sample real geometry`() {
        val diffusion = model(
            name = "Diffused props",
            architecture = "Diffuser model",
            family = "Mesh diffusion (MeshDiffusion)",
        )
        attachMeshes(diffusion)
        ModelCraft.setAttention(diffusion, ModelCraft.Attention(true, 512, 2))
        ModelCraft.Store.save(diffusion)

        train(diffusion)
        val diffusionTrained = assertNotNull(
            GameTrainer.load(diffusion),
            "the diffusion game model wrote no loadable artifact",
        )
        assertTrue(diffusionTrained.diffusion)
        assertTrue(diffusionTrained.slidingWindow)
        assertEquals(512, diffusionTrained.window)
        assertEquals(2, diffusionTrained.fullAttentionEveryNthLayer)
        assertTrue(diffusionTrained.summary().contains("sliding window"))
        val diffusionAttention = assertNotNull(
            diffusionTrained.metadata.optJSONObject("attention"),
            "the run said nothing about attention",
        )
        assertEquals(true, diffusionAttention.optBoolean("applied"), "the window was not built")
        assertEquals(2, diffusionAttention.optInt("layers"))
        val diffused = diffusionTrained.sample("a mossy boulder", seed = 7L)
        assertValid(diffused)

        val token = model(
            name = "Token props",
            architecture = "Byte latent transformer model",
            family = "Shape-token latent transformer",
        )
        attachMeshes(token)
        train(token)
        val tokenTrained = assertNotNull(GameTrainer.load(token))
        assertFalse(tokenTrained.diffusion)
        assertFalse(tokenTrained.slidingWindow)
        assertTrue(tokenTrained.summary().contains("shape tokens"))
        val built = tokenTrained.sample("a pine tree", seed = 8L)
        assertValid(built)
        assertTrue(built.category in setOf("tree", "rock", "building", "creature", "crystal", "prop"))
    }

    @Test
    fun `a sliding window reaches the token head and survives a reload`() {
        val model = model(
            name = "Windowed props",
            architecture = "Byte latent transformer model",
            family = "Shape-token latent transformer",
        )
        attachMeshes(model)
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 3, 2))
        ModelCraft.Store.save(model)
        train(model)

        val trained = assertNotNull(GameTrainer.load(model))
        assertFalse(trained.diffusion)
        assertTrue(trained.slidingWindow)
        assertEquals(3, trained.window)
        val attention = assertNotNull(trained.metadata.optJSONObject("attention"))
        assertEquals(true, attention.optBoolean("applied"))
        assertEquals(2, attention.optInt("layers"))

        val first = trained.sample("a stone arch", seed = 13L)
        assertValid(first)
        val again = assertNotNull(GameTrainer.load(model)).sample("a stone arch", seed = 13L)
        assertTrue(first.vertices.contentEquals(again.vertices), "the reloaded model sampled a different mesh")
    }

    @Test
    fun `a second game model warm starts from the first when it has a base`() {
        val base = model(
            name = "Base props",
            architecture = "Normal transformer model (decoder only)",
            family = "Autoregressive mesh transformer (MeshGPT)",
        )
        attachMeshes(base)
        train(base)

        val baseWeights = File(ModelCraft.Store.datasetDir(base.id), GameTrainer.WEIGHTS_FILE)
        assertTrue(baseWeights.isFile, "the base trained without writing weights")

        val child = model(
            name = "Fine-tuned props",
            architecture = "Normal transformer model (decoder only)",
            family = "Autoregressive mesh transformer (MeshGPT)",
        )
        ModelCraft.setFromScratch(child, false)
        child.params["base_model"] = baseWeights.absolutePath
        attachMeshes(child)
        train(child)

        val childTrained = assertNotNull(GameTrainer.load(child))
        assertTrue(
            childTrained.metadata.optBoolean("warm_started", false),
            "the child trained without recording that it used a base",
        )
        assertEquals(
            baseWeights.absolutePath,
            childTrained.metadata.optString("base_model", ""),
        )
        assertValid(childTrained.sample("a wooden crate", seed = 3L))
    }

    @Test
    fun `a from scratch game run records that it used no base`() {
        val model = model(
            name = "From nothing",
            architecture = "Diffuser model",
            family = "Gaussian splat generator",
        )
        attachMeshes(model)
        train(model)

        val trained = assertNotNull(GameTrainer.load(model))
        assertFalse(trained.metadata.optBoolean("warm_started", true))
        assertEquals("", trained.metadata.optString("base_model", "nothing"))
        assertTrue(trained.parameterCount() > 0)
        assertTrue(trained.trainingSteps > 0)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun model(name: String, architecture: String, family: String): CraftedModel =
        ModelCraft.create(
            name = name,
            kind = ModelCraft.Kind.GAME,
            architecture = architecture,
            family = family,
        ).apply {
            ModelCraft.setFromScratch(this, true)
            ModelCraft.setTrainsLocally(this, true)
            ModelCraft.setSchedule(this, ModelCraft.Schedule(epochs = 2, stepsPerEpoch = 2))
            ModelCraft.Store.save(this)
        }

    private fun attachMeshes(model: CraftedModel) {
        val rock = File(scratch, "rock.obj").apply {
            writeText(
                """
                v -0.8 0 -0.6
                v 0.7 0 -0.4
                v 0 1.1 0
                v 0.1 0.2 0.8
                f 1 2 3
                f 1 2 4
                f 2 3 4
                """.trimIndent(),
            )
        }
        val tree = File(scratch, "tree.obj").apply {
            writeText(
                """
                v -0.3 0 -0.3
                v 0.3 0 -0.3
                v 0.3 2.2 -0.3
                v -0.3 2.2 -0.3
                v -0.3 0 0.3
                v 0.3 0 0.3
                v 0.3 2.2 0.3
                v -0.3 2.2 0.3
                f 1 2 3
                f 1 3 4
                f 5 6 7
                f 5 7 8
                f 1 2 6
                f 1 6 5
                """.trimIndent(),
            )
        }
        val captions = File(scratch, "captions.csv").apply {
            writeText("path,caption\nrock.obj,a mossy rock\ntree.obj,a tall pine tree\n")
        }
        val added = ModelCraft.attachDataset(model, listOf(rock, tree, captions))
        assertTrue(added >= 2, "the game dataset imported only $added files")
    }

    private fun train(model: CraftedModel) {
        assertTrue(
            ModelCraft.train(model),
            "${model.name} was refused: ${ModelCraft.state.value.error}",
        )
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (!ModelCraft.state.value.running) return
            Thread.sleep(20)
        }
        error("${model.name} never finished training: ${ModelCraft.state.value}")
    }

    private fun assertValid(mesh: com.prism.launcher.assetcraft.GameMesh) {
        assertTrue(mesh.vertexCount > 0, "${mesh.name} has no vertices")
        assertTrue(mesh.triangleCount > 0, "${mesh.name} has no triangles")
        assertEquals(0, mesh.triangles.size % 3)
        assertTrue(
            mesh.triangles.all { it in 0 until mesh.vertexCount },
            "${mesh.name} has an index outside its vertex list",
        )
    }
}
