package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.PixelTrainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The on-device pixel trainers: an image denoiser, a frame interpolator and a video predictor.
 *
 * The pictures are small synthetic patterns, so nothing here claims good images. What it pins is that
 * each kind actually trains on its own data -- the flow-matching loss falls on images, the middle
 * frame is reconstructed from its neighbours, the next frame is predicted from the ones before -- and
 * that the artifacts load back and can be asked for what they were trained to produce.
 */
class PixelTrainerTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-pixel-trainer-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
        ModelCraft.frameExtractor = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        ModelCraft.frameExtractor = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun model(kind: ModelCraft.Kind, epochs: Int = 25): CraftedModel {
        val created = ModelCraft.create(
            "Patterns",
            kind,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(kind).first().name,
        )
        created.params["epochs"] = epochs.toString()
        created.params["learning_rate"] = "0.002"
        // Small on purpose: the point is the machinery, and a test that trains a 1024-pixel model
        // would be a test that times out.
        created.params["resolution"] = "16"
        created.params["crop_size"] = "16"
        created.params["width"] = "16"
        created.params["height"] = "16"
        return created
    }

    /** A moving band: neighbouring frames differ, which is what an interpolator has to learn. */
    private fun picture(side: Int, frame: Int): PrismImage {
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val band = ((x + frame) % side) * 255 / side
                val shade = ((y + frame * 2) % side) * 255 / side
                pixels[y * side + x] = PrismImage.argb(band, shade, 128)
            }
        }
        return PrismImage(side, side, pixels)
    }

    private fun writeImage(file: File, frame: Int, side: Int = 16) {
        file.parentFile?.mkdirs()
        assertTrue(RasterCodec.encodePng(picture(side, frame), file), "could not write ${file.name}")
    }

    private fun frames(model: CraftedModel, clip: String, count: Int, side: Int = 16) {
        for (index in 0 until count) {
            writeImage(File(ModelCraft.Store.framesDir(model.id), "$clip/frame-%05d.png".format(index)), index, side)
        }
    }

    @Test
    fun `an image model trains by denoising and can generate`() {
        val model = model(ModelCraft.Kind.IMAGE)
        for (index in 0 until 4) {
            writeImage(File(ModelCraft.Store.datasetDir(model.id), "image-$index.png"), index)
        }
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.IMAGE, model, ModelCraft.recipeFile(model)),
            "an image model with decodable pictures in its dataset must train",
        )
        assertTrue(
            result.summary.contains("flow-matching"),
            "a diffusion-family model must be trained by flow matching: ${result.summary}",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, PixelTrainer.Task.IMAGE.metadataFile).readText())
        val first = metadata.optDouble("loss_first")
        val last = metadata.optDouble("loss_last")
        assertTrue(last < first, "training did not reduce the loss: $first -> $last")

        val loaded = assertNotNull(PixelTrainer.load(directory, PixelTrainer.Task.IMAGE))
        val generated = loaded.generate(java.util.Random(7), steps = 4)
        assertEquals(3 * loaded.config.side * loaded.config.side, generated.size)
        assertTrue(generated.all { it.isFinite() }, "sampling produced a non-number")
    }

    @Test
    fun `a dataset with train validation and test folders is read as those splits`() {
        val model = model(ModelCraft.Kind.IMAGE, epochs = 2)
        val root = ModelCraft.Store.datasetDir(model.id)
        // Four, three and two: unequal on purpose, so a run that re-splits the flat pile cannot
        // coincidentally report the same counts.
        for ((split, count) in listOf("train" to 4, "validation" to 3, "test" to 2)) {
            for (index in 0 until count) {
                writeImage(File(root, "$split/image-$index.png"), index)
            }
        }
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.IMAGE, model, ModelCraft.recipeFile(model)),
            "the trainer did not read a folder-split dataset",
        )
        assertEquals(4, result.trained.trainingCount, "the train folder was not read as training")
        assertEquals(3, result.trained.validationCount, "the validation folder was not held out")
        assertEquals(2, result.trained.testCount, "the test folder was not held out")
        val metadata = JSONObject(
            File(root, PixelTrainer.Task.IMAGE.metadataFile).readText(),
        )
        assertEquals(4, metadata.optInt("train_examples"))
        assertEquals(3, metadata.optInt("validation_examples"))
        assertEquals(2, metadata.optInt("test_examples"))
        assertTrue(metadata.optDouble("loss_test").isFinite(), "the test split was not measured")
    }

    @Test
    fun `an image import keeps the train validation and test folders`() {
        val model = model(ModelCraft.Kind.IMAGE, epochs = 1)
        val source = File(scratch, "picked-image-set").apply { mkdirs() }
        val picked = mutableListOf<File>()
        for (split in listOf("train", "validation", "test")) {
            val file = File(source, "$split/frame.png")
            writeImage(file, 1)
            picked += file
        }
        ModelCraft.attachDataset(model, picked, preserveSplits = true)
        val target = ModelCraft.Store.datasetDir(model.id)
        for (split in listOf("train", "validation", "test")) {
            assertTrue(
                File(target, "$split/frame.png").isFile,
                "$split/frame.png was flattened out of its split folder",
            )
        }
    }

    @Test
    fun `a non-diffusion image family is trained by masking and says so`() {
        val model = model(ModelCraft.Kind.IMAGE, epochs = 40)
        model.family = ModelCraft.archTypes.getValue(ModelCraft.Kind.IMAGE)
            .first { it.name.contains("Generative adversarial") }.name
        // More pictures than the denoising test needs: masking one square and learning to fill it in
        // from the rest is a harder objective than predicting the average of a noise field, and a
        // smoke test that expects learning has to give the run something to learn.
        for (index in 0 until 8) {
            writeImage(File(ModelCraft.Store.datasetDir(model.id), "image-$index.png"), index)
        }
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.IMAGE, model, ModelCraft.recipeFile(model)),
        )
        assertTrue(
            result.summary.contains("masked-patch"),
            "a family with no denoiser in it must be trained by reconstruction: ${result.summary}",
        )
        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), PixelTrainer.Task.IMAGE.metadataFile).readText(),
        )
        assertEquals(false, metadata.optBoolean("diffusion"))
        assertTrue(metadata.optDouble("loss_last") < metadata.optDouble("loss_first"), "it did not learn")
    }

    @Test
    fun `an interpolator reconstructs the middle frame from its neighbours`() {
        val model = model(ModelCraft.Kind.INTERPOLATION)
        frames(model, "clip-a", 8)
        frames(model, "clip-b", 8)
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.INTERPOLATION, model, ModelCraft.recipeFile(model)),
            "an interpolator with extracted frames must train",
        )
        assertTrue(
            result.summary.contains("middle-frame"),
            "the interpolator was not trained to reconstruct the middle frame: ${result.summary}",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, PixelTrainer.Task.INTERPOLATION.metadataFile).readText())
        assertTrue(
            metadata.optDouble("loss_last") < metadata.optDouble("loss_first"),
            "training did not reduce the loss",
        )

        val loaded = assertNotNull(PixelTrainer.load(directory, PixelTrainer.Task.INTERPOLATION))
        val side = loaded.config.side
        val plane = 3 * side * side
        val left = DoubleArray(plane) { -1.0 }
        val right = DoubleArray(plane) { 1.0 }
        val middle = loaded.interpolate(left, right)
        assertEquals(plane, middle.size)
        assertTrue(middle.all { it.isFinite() }, "the interpolator produced a non-number")
    }

    @Test
    fun `a video model predicts the next frame from the ones before it`() {
        val model = model(ModelCraft.Kind.VIDEO)
        frames(model, "clip-a", 8)
        frames(model, "clip-b", 8)
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.VIDEO, model, ModelCraft.recipeFile(model)),
            "a video model with extracted frames must train",
        )
        assertTrue(
            result.summary.contains("next-frame"),
            "the video model was not trained to predict the next frame: ${result.summary}",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, PixelTrainer.Task.VIDEO.metadataFile).readText())
        assertTrue(
            metadata.optDouble("loss_last") < metadata.optDouble("loss_first"),
            "training did not reduce the loss",
        )
        val loaded = assertNotNull(PixelTrainer.load(directory, PixelTrainer.Task.VIDEO))
        val plane = 3 * loaded.config.side * loaded.config.side
        val predicted = loaded.nextFrame(listOf(DoubleArray(plane), DoubleArray(plane) { 1.0 }))
        assertEquals(plane, predicted.size)
    }

    @Test
    fun `a video build also trains the frame encoder and decoder`() {
        val model = model(ModelCraft.Kind.VIDEO, epochs = 6)
        frames(model, "clip-a", 8)
        frames(model, "clip-b", 8)
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.VIDEO, model, ModelCraft.recipeFile(model)),
            "a video model with extracted frames must train",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val plane = 3 * 16 * 16
        val names = listOf(
            "video_model.safetensors",
            "video_model.encoder.safetensors",
            "video_model.decoder.safetensors",
        )
        names.forEach { assertTrue(File(directory, it).isFile, "$it was not written") }
        names.forEach {
            assertTrue(result.summary.contains(it), "the summary does not name $it: ${result.summary}")
        }

        val metadata = JSONObject(File(directory, PixelTrainer.Task.VIDEO.metadataFile).readText())
        val coder = assertNotNull(metadata.optJSONObject("encoder_decoder"), "the pair was not described")
        assertEquals(plane, coder.optInt("inputs"), "the pair must be trained on the frames themselves")
        assertTrue(coder.optInt("code") < plane, "the code is not narrower than a frame")
        assertTrue(
            coder.optDouble("reconstruction_loss_last").isFinite(),
            "no reconstruction loss was recorded",
        )

        // The pair is in memory, and it codes and decodes a frame rather than returning nothing.
        val pair = assertNotNull(result.trained.coder, "the trained model kept no encoder/decoder")
        val frame = DoubleArray(plane) { index -> (index % 11) / 11.0 - 0.5 }
        val code = pair.encode(frame)
        assertEquals(coder.optInt("code"), code.size)
        val back = pair.decode(code)
        assertEquals(plane, back.size)
        assertTrue(back.all { it.isFinite() }, "the decoder produced a non-number")

        // And the model's own kind of build leaves the model itself in the same folder.
        assertTrue(File(directory, PixelTrainer.Task.VIDEO.weightsFile).isFile)
    }

    @Test
    fun `a sliding window builds patch attention and the loaded model uses it`() {
        val model = model(ModelCraft.Kind.IMAGE, epochs = 4)
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 4, 2))
        for (index in 0 until 4) {
            writeImage(File(ModelCraft.Store.datasetDir(model.id), "image-$index.png"), index)
        }
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.IMAGE, model, ModelCraft.recipeFile(model)),
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, PixelTrainer.Task.IMAGE.metadataFile).readText())
        val attention = assertNotNull(metadata.optJSONObject("attention"), "the run said nothing about attention")
        assertEquals(true, attention.optBoolean("applied"), "the window was not applied to the pixel model")
        assertEquals(4, attention.optInt("window"))
        assertTrue(attention.optInt("tokens") > 1, "a one-token field cannot attend to anything")
        assertEquals(4, attention.optInt("patch"))
        assertEquals(2, attention.optInt("layers"))
        val perLayer = assertNotNull(attention.optJSONArray("per_layer"))
        val plan = (0 until perLayer.length()).map { perLayer.optString(it) }
        assertTrue(plan.contains("full"), "no layer attends fully: $plan")
        assertTrue(plan.any { it.startsWith("sliding:4") }, "no layer is windowed: $plan")

        // Inference loads the window with the weights: same weights, same seed, same image.
        val loaded = assertNotNull(PixelTrainer.load(directory, PixelTrainer.Task.IMAGE))
        assertEquals(4, loaded.config.window)
        assertNotNull(loaded.net.attention, "the reloaded model lost its attention body")
        val first = result.trained.generate(java.util.Random(11), steps = 3)
        val second = loaded.generate(java.util.Random(11), steps = 3)
        assertTrue(first.contentEquals(second), "the reloaded model generates a different image")
    }

    @Test
    fun `the installed trainer turns an image run into weights and says so`() {
        // The wiring, not the arithmetic: this is the run the page's Train button starts, and the
        // message it used to end with was that no local trainer exists for the kind.
        ModelCraft.installDefaultTrainer()
        val model = model(ModelCraft.Kind.IMAGE, epochs = 5)
        val staging = File(scratch, "image-imports").apply { mkdirs() }
        for (index in 0 until 3) {
            writeImage(File(staging, "photo-$index.png"), index)
        }
        ModelCraft.attachDataset(model, staging.listFiles()?.toList().orEmpty())
        ModelCraft.Store.save(model)

        assertTrue(ModelCraft.train(model), "the run was refused: ${ModelCraft.state.value.error}")
        awaitRun(timeoutMs = 120_000)

        assertEquals(null, ModelCraft.state.value.error, "the run failed")
        assertEquals(ModelCraft.STATUS_TRAINED, model.status, "the run did not report a model: ${model.note}")
        assertTrue(
            model.note.contains("parameters trained"),
            "the note does not say what was made: ${model.note}",
        )
        assertTrue(
            File(ModelCraft.Store.datasetDir(model.id), PixelTrainer.Task.IMAGE.metadataFile).isFile,
            "the run claimed to train but wrote no image model",
        )
        assertTrue(
            File(ModelCraft.Store.datasetDir(model.id), "image_model.safetensors").isFile,
            "the run wrote no container file",
        )
    }

    @Test
    fun `the schedule boxes bound a video run and its encoder and decoder as well`() {
        val model = model(ModelCraft.Kind.VIDEO, epochs = 6)
        frames(model, "clip-a", 8)
        frames(model, "clip-b", 8)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 2, stepsPerEpoch = 1))

        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.VIDEO, model, ModelCraft.recipeFile(model)),
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, PixelTrainer.Task.VIDEO.metadataFile).readText())
        assertEquals(2, metadata.optInt("steps"), "two epochs of one step each did not stop at two")
        assertEquals(2, metadata.optInt("epochs"))
        assertEquals(1, metadata.optInt("steps_per_epoch"))
        assertTrue(
            result.summary.contains("trained over 2 step(s)"),
            "the summary does not name the steps actually taken: ${result.summary}",
        )

        // The pair beside the model is part of the same build, so it follows the same schedule
        // rather than training for as long as it likes.
        val coder = assertNotNull(metadata.optJSONObject("encoder_decoder"))
        assertEquals(2, coder.optInt("steps"), "the encoder and decoder ignored the schedule")
        assertEquals(2, assertNotNull(result.trained.coder).steps)
    }

    private fun awaitRun(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ModelCraft.state.value.running) return
            Thread.sleep(20)
        }
        error("the run never finished: ${ModelCraft.state.value}")
    }
}
