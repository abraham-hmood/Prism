package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The on-device text trainer: that a dataset of words becomes a model, that the model reads back as
 * the same model, and that the objective follows the architecture the user chose.
 *
 * The corpus is one repeated sentence, so nothing here claims to be a good language model. What it
 * pins is the machinery: the forward and backward pass move the loss on byte-level cross-entropy, the
 * weights round-trip exactly, and an encoder-shaped architecture is trained by masking rather than by
 * pretending to be causal.
 */
class TextTrainerTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-text-trainer-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun model(
        architecture: String? = null,
        family: String? = null,
        epochs: Int = 20,
    ): CraftedModel {
        val created = ModelCraft.create(
            "Storyteller",
            ModelCraft.Kind.TEXT,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        created.params["epochs"] = epochs.toString()
        created.params["learning_rate"] = "0.002"
        architecture?.let { created.architecture = it }
        family?.let { created.family = it }
        return created
    }

    /** One sentence, repeated: enough bytes for blocks, and regular enough to learn in seconds. */
    private fun dataset(model: CraftedModel): File {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val sentence = "the cat sat on the warm mat and watched the rain. "
        File(directory, "stories.txt").writeText(sentence.repeat(30))
        return directory
    }

    @Test
    fun `training reduces the loss and the weights load back as the same model`() {
        val model = model()
        dataset(model)
        val result = assertNotNull(
            TextTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "a text model with a text file in its dataset must train",
        )
        assertTrue(
            result.summary.contains("causal"),
            "a decoder-only architecture must be trained causally: ${result.summary}",
        )

        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadataFile = File(directory, TextTrainer.METADATA_FILE)
        assertTrue(metadataFile.isFile, "no metadata was written")
        assertTrue(File(directory, TextTrainer.WEIGHTS_FILE).isFile, "no weights were written")
        val metadata = JSONObject(metadataFile.readText())
        val first = metadata.optDouble("loss_first")
        val last = metadata.optDouble("loss_last")
        assertTrue(first.isFinite() && last.isFinite(), "the losses were not recorded: $first -> $last")
        assertTrue(last < first, "training did not reduce the loss: $first -> $last")
        assertTrue(metadata.optInt("steps") > 0, "no step was taken")
        assertEquals(
            "words", metadata.optString("tokenizer"),
            "a new text model must learn words rather than fall back to bytes",
        )
        assertTrue(metadata.optInt("vocabulary") > 4, "the learned vocabulary is empty")
        assertTrue(metadata.optInt("corpus_bytes") > 0, "the run did not record what it read")
        assertTrue(metadata.optInt("corpus_tokens") > 0, "the run did not tokenize what it read")

        // A reader must get the SAME model back: a weights file that loads but computes something else
        // is worse than none, because nothing downstream would notice.
        val loaded = assertNotNull(TextTrainer.load(directory), "the artifacts did not load back")
        val before = result.trained.continueBytes("the cat ", 24)
        val after = loaded.continueBytes("the cat ", 24)
        assertEquals(before, after, "the reloaded model continues differently")
        assertTrue(before.isNotEmpty(), "the model produced nothing when asked to continue")
    }

    @Test
    fun `the from-scratch shape under creation reaches the trainer`() {
        val model = model(epochs = 1)
        dataset(model)
        // Without this the recipe is written in base mode and `creation.initialisation` is null,
        // so the trainer can only fall back to defaults -- which is exactly the failure this test
        // is here to catch.
        model.params[ModelCraft.KEY_FROM_SCRATCH] = "true"
        model.params["vocabulary"] = "2048"
        model.params["max_seq_length"] = "48"
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 1, stepsPerEpoch = 1))

        assertNotNull(TextTrainer.trainResult(model, ModelCraft.recipeFile(model)))
        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), TextTrainer.METADATA_FILE).readText(),
        )
        assertEquals(2_048, metadata.optInt("vocabulary_limit"), "the vocabulary field was ignored")
        assertEquals(48, metadata.optInt("positions"), "the context field was ignored")
    }

    @Test
    fun `a CSV that names chapters in another folder is read as their text`() {
        val model = model(epochs = 1)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 1, stepsPerEpoch = 1))
        val directory = ModelCraft.Store.datasetDir(model.id)
        File(directory, "chapters").apply { mkdirs() }
        val sentence = "the cat sat on the warm mat and watched the rain. "
        File(directory, "chapters/chapter one.txt").writeText(sentence.repeat(30))
        File(directory, "contents.csv").writeText(
            "file,language\nchapters\\chapter one.txt,en\n",
        )

        val result = assertNotNull(
            TextTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "the manifest's Windows-separated chapter was not read as language",
        )

        assertTrue(
            result.summary.contains("train ") && result.summary.contains("document"),
            "the summary does not report the corpus it read: ${result.summary}",
        )
        assertTrue(
            File(directory, "chapter one.txt").isFile,
            "preparing the dataset should have localised the chapter the CSV names",
        )
    }

    @Test
    fun `an encoder architecture is trained by masking and says so`() {
        val model = model(
            architecture = ModelCraft.architectures.first { it.contains("Encoder only") },
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT)
                .first { it.name.contains("masked") }.name,
        )
        dataset(model)
        val result = assertNotNull(TextTrainer.trainResult(model, ModelCraft.recipeFile(model)))
        assertTrue(
            result.summary.contains("masked"),
            "an encoder-only model must be trained by denoising, not by predicting the next byte: " +
                result.summary,
        )
        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), TextTrainer.METADATA_FILE).readText(),
        )
        assertEquals("masked token denoising", metadata.optString("objective"))
        assertTrue(metadata.optInt("masked_positions_per_block") > 0, "nothing was masked")
    }

    @Test
    fun `the container the model chose is written beside the trainer's own artifact`() {
        // The default: safetensors, one file for this kind.
        val loose = model()
        dataset(loose)
        val looseResult = assertNotNull(TextTrainer.trainResult(loose, ModelCraft.recipeFile(loose)))
        val looseDirectory = ModelCraft.Store.datasetDir(loose.id)
        val safetensors = File(looseDirectory, "${TextTrainer.BASE_NAME}.safetensors")
        assertTrue(safetensors.isFile, "the default container was not written")
        assertTrue(
            looseResult.summary.contains(safetensors.name),
            "the summary does not say what was written: ${looseResult.summary}",
        )
        val metadata = JSONObject(File(looseDirectory, TextTrainer.METADATA_FILE).readText())
        assertEquals(
            ModelCraft.FileFormat.SAFETENSORS.recipeValue,
            metadata.optString(ModelCraft.KEY_FILE_FORMAT),
        )
        val recorded = assertNotNull(metadata.optJSONArray("model_files"), "no files were recorded")
        assertEquals(1, recorded.length(), "a text build is one file")
        assertEquals(safetensors.name, recorded.optString(0))

        // Chosen: GGUF, one file, and NOT the other container as well.
        val packed = model()
        dataset(packed)
        ModelCraft.setFileFormat(packed, ModelCraft.FileFormat.GGUF)
        val packedResult = assertNotNull(TextTrainer.trainResult(packed, ModelCraft.recipeFile(packed)))
        val packedDirectory = ModelCraft.Store.datasetDir(packed.id)
        val gguf = File(packedDirectory, "${TextTrainer.BASE_NAME}.gguf")
        assertTrue(gguf.isFile, "the chosen GGUF was not written")
        assertEquals("GGUF", String(gguf.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(
            packedResult.summary.contains(gguf.name),
            "the summary does not say what was written: ${packedResult.summary}",
        )
        assertTrue(
            !File(packedDirectory, "${TextTrainer.BASE_NAME}.safetensors").exists(),
            "the GGUF run wrote the other container as well",
        )
    }

    @Test
    fun `a sliding window is used in training and travels into the loaded model`() {
        val model = model(epochs = 3)
        dataset(model)
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 8, 2))
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 3, stepsPerEpoch = 2))
        val result = assertNotNull(TextTrainer.trainResult(model, ModelCraft.recipeFile(model)))

        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadata = JSONObject(File(directory, TextTrainer.METADATA_FILE).readText())
        assertEquals(8, metadata.optInt("sliding_window_size"), "the window did not reach the trainer")
        assertEquals(2, metadata.optInt("full_attention_per_nth_layer"))
        val layers = metadata.optJSONArray("attention_layers")
        assertNotNull(layers, "the run did not say which layers are full")
        val plan = (0 until layers.length()).map { layers.optString(it) }
        assertTrue(plan.any { it == "full" }, "no layer attends fully: $plan")
        assertTrue(plan.any { it.startsWith("sliding:8") }, "no layer is windowed: $plan")

        // Inference reads the same setting back: the weights alone cannot carry it, so a load that
        // dropped it would silently turn the model back into full attention.
        val loaded = assertNotNull(TextTrainer.load(directory), "the windowed artifacts did not load")
        assertEquals(8, loaded.config.window)
        assertEquals(2, loaded.config.fullEvery)
        assertEquals(
            result.trained.continueBytes("the cat ", 12),
            loaded.continueBytes("the cat ", 12),
        )
    }

    @Test
    fun `the same dataset and recipe produce the same weights`() {
        val first = model()
        dataset(first)
        TextTrainer.train(first, ModelCraft.recipeFile(first))
        val second = model()
        dataset(second)
        TextTrainer.train(second, ModelCraft.recipeFile(second))

        val a = JSONObject(File(ModelCraft.Store.datasetDir(first.id), TextTrainer.METADATA_FILE).readText())
        val b = JSONObject(File(ModelCraft.Store.datasetDir(second.id), TextTrainer.METADATA_FILE).readText())
        assertEquals(a.optDouble("loss_last"), b.optDouble("loss_last"), 1e-12, "two runs disagreed")
        assertEquals(a.optInt("steps"), b.optInt("steps"), "two runs took a different number of steps")
    }

    @Test
    fun `the schedule boxes bound the run to epochs times steps per epoch`() {
        val model = model(epochs = 2)
        dataset(model)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 2, stepsPerEpoch = 1))

        val result = assertNotNull(TextTrainer.trainResult(model, ModelCraft.recipeFile(model)))

        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), TextTrainer.METADATA_FILE).readText(),
        )
        assertEquals(2, metadata.optInt("steps"), "a two-epoch run of one step each did not stop at two")
        assertEquals(2, metadata.optInt("epochs"), "the recipe's epoch count did not reach the trainer")
        assertEquals(
            1, metadata.optInt("steps_per_epoch"),
            "the recipe's steps per epoch did not reach the trainer",
        )
        assertTrue(
            result.summary.contains("trained over 2 step(s)"),
            "the summary does not name the steps actually taken: ${result.summary}",
        )
    }
}
