package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.assetcraft.AssetCraft
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract the AssetCraft page depends on: generation is refused until BOTH an active game model
 * and an active text model exist, and an imported mesh is enough on the game side.
 *
 * The text model here is a file with the right extension, not a runnable GGUF: the gate is about a
 * model being chosen, and the wand is tested where it can be answered. The game side is a real OBJ
 * so generation actually produces a node in the shared scene.
 */
class AssetCraftTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-assetcraft-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        AssetCraft.clearScene()
    }

    @AfterTest
    fun cleanUp() {
        AssetCraft.clearScene()
        AssetCraft.setActiveGame(null)
        AssetCraft.setActiveText(null)
        ModelCraft.clear()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `generation waits for both active models and then builds a mesh`() {
        val mesh = File(scratch, "rock.obj").apply {
            writeText(
                """
                v -1 0 -1
                v 1 0 -1
                v 0 1 0
                f 1 2 3
                """.trimIndent(),
            )
        }
        val text = File(scratch, "writer.gguf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val missing = AssetCraft.gate()
        assertFalse(missing.ready)
        assertFalse(missing.gameReady)
        assertFalse(missing.textReady)
        assertTrue(missing.reason.contains("game asset model"))
        assertTrue(missing.reason.contains("text model"))

        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = mesh.absolutePath,
                displayName = "Test rock",
                type = PrismSettings.MODEL_TYPE_GAME,
            ),
        )
        val game = AssetCraft.gameChoices().first { it.name == "Test rock" }
        AssetCraft.setActiveGame(game)

        val stillMissing = AssetCraft.gate()
        assertFalse(stillMissing.ready)
        assertTrue(stillMissing.gameReady)
        assertFalse(stillMissing.textReady)
        assertFalse(AssetCraft.start("a mossy rock"), "a run started without a text model")
        assertTrue(AssetCraft.state.value.error.orEmpty().contains("text model"))

        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = text.absolutePath,
                displayName = "Test writer",
                type = PrismSettings.MODEL_TYPE_TEXT,
            ),
        )
        val writer = AssetCraft.textChoices().first { it.name == "Test writer" }
        AssetCraft.setActiveText(writer)

        val ready = AssetCraft.gate()
        assertTrue(ready.ready, "the gate still refuses with both models active: ${ready.reason}")
        assertTrue(ready.gameReady)
        assertTrue(ready.textReady)

        AssetCraft.clearScene()
        assertTrue(AssetCraft.start("a mossy rock"), "the run was refused: ${AssetCraft.state.value.error}")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!AssetCraft.state.value.running) break
            Thread.sleep(20)
        }
        val state = AssetCraft.state.value
        assertFalse(state.running, "the run never finished")
        assertTrue(state.error == null, "the run failed: ${state.error}")
        assertEquals(1, state.scene.nodes.size)
        assertTrue(state.scene.nodes.first().mesh.triangleCount > 0)
    }
}
