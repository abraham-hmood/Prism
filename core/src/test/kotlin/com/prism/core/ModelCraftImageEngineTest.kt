package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageGeneration
import com.prism.launcher.messaging.ImageSize
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftImageGenerator
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ModelCraft image model as what GameCraft actually talks to: a registered image engine.
 *
 * The trainer tests prove the artifact trains and reloads; this proves the join between the two
 * halves of the feature. A run has to make itself the active local image model, the registry has to
 * serve the ModelCraft engine by id, and the engine has to return a 1024 x 1024 picture instead of
 * the 16-pixel field it learned. Without this test the page can compile and still fail at the first
 * Generate, which is exactly the gap that made an earlier image pass look finished when it was not.
 */
class ModelCraftImageEngineTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelcraft-image-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.installDefaultTrainer()
        ImageGeneration.reset()
        ImageGeneration.register(ModelCraftImageGenerator())
    }

    @AfterTest
    fun cleanUp() {
        ImageGeneration.reset()
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `a finished image run becomes the active engine and paints a 1024 texture`() {
        val model = ModelCraft.create(
            "Texture Probe",
            ModelCraft.Kind.IMAGE,
            ModelCraft.architectures[1],
            "Diffusion transformer (DiT)",
        )
        ModelCraft.setFromScratch(model, true)
        ModelCraft.setTrainsLocally(model, true)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 4, stepsPerEpoch = 8))
        model.params["resolution"] = "16"
        model.params["caption_column"] = "text"
        ModelCraft.Store.save(model)

        val source = File(scratch, "texture-dataset").apply { mkdirs() }
        val files = (0 until 8).map { index ->
            val file = File(source, "texture-%02d.png".format(index))
            assertTrue(
                RasterCodec.encodePng(picture(index), file),
                "could not write ${file.name}",
            )
            file
        }
        assertTrue(ModelCraft.attachDataset(model, files) >= files.size, "the dataset was not imported")
        val dataset = ModelCraft.Store.datasetDir(model.id)

        assertTrue(ModelCraft.train(model), "the image run was refused: ${ModelCraft.state.value.error}")
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline && ModelCraft.state.value.running) {
            Thread.sleep(25)
        }
        assertTrue(!ModelCraft.state.value.running, "training never finished")
        assertTrue(ModelCraft.state.value.error == null, "training failed: ${ModelCraft.state.value.error}")

        // THE AUTOMATIC IMPORT. The Models page and both camps read this one path; a run that did
        // not write it is a run the rest of the app cannot see.
        val active = PrismSettings.getLocalImageModelPath()
        assertEquals(
            dataset.absolutePath,
            active,
            "the completed run did not make itself the active local image model",
        )
        assertNotNull(
            ModelCraftImageGenerator.directoryOf(active),
            "the active path does not name a ModelCraft artifact pair",
        )

        val engine = assertNotNull(ImageGeneration.byId(ModelCraftImageGenerator.ID))
        assertTrue(
            engine.availability() is com.prism.launcher.messaging.ImageGenerator.Availability.Ready,
            "the registered engine reports itself unavailable: ${engine.availability()}",
        )

        val result = ImageGeneration.generate(
            prompt = "mossy stone with warm lantern light",
            engineId = ModelCraftImageGenerator.ID,
            size = ImageSize(1024, 1024),
        )
        val image = assertNotNull(result.image, "the engine returned nothing: ${result.error}")
        assertEquals(1024, image.width)
        assertEquals(1024, image.height)
        val distinct = image.pixels.toHashSet().size
        assertTrue(distinct > 16, "the texture has only $distinct colours, so it is a flat field")

        val output = File("C:/Users/bremo/model_craft_games")
        val directory = if (output.parentFile?.isDirectory == true) output else scratch
        directory.mkdirs()
        val file = File(directory, "modelcraft-image-texture-probe.png")
        assertTrue(RasterCodec.encodePng(image, file), "could not save ${file.absolutePath}")
        println("ModelCraft image engine wrote ${file.absolutePath} ($distinct colours)")
    }

    /** Broad strokes rather than noise: a model with nothing to learn would return one colour. */
    private fun picture(frame: Int): PrismImage {
        val side = 16
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val red = (x * 255 / (side - 1))
                val green = (y * 255 / (side - 1))
                val blue = ((x + y + frame * 3) % side) * 255 / (side - 1)
                pixels[y * side + x] = PrismImage.argb(red, green, blue)
            }
        }
        return PrismImage(side, side, pixels)
    }
}
