package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.AcousticTrainer
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.VocalAnalysis
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The on-device acoustic trainer: that it learns, that it writes artifacts a reader can load back, and
 * that the recipe's attention controls reach the network instead of only the recipe file.
 *
 * The clips are synthetic vowels, so nothing here claims to be a good model. What it pins is the
 * machinery: the forward/backward pass moves the loss, the merged CSV's vocal columns become the
 * conditioning (with unmeasured columns imputed and named), the weights round-trip exactly, and the
 * sliding window really limits what a layer can see.
 */
class AcousticTrainerTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-acoustic-test-${System.nanoTime()}",
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

    private fun model(window: Int? = null, fullEvery: Int? = null, epochs: Int = 12): CraftedModel {
        val model = ModelCraft.create(
            "Narrator",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params["epochs"] = epochs.toString()
        model.params["learning_rate"] = "0.002"
        if (window != null) {
            ModelCraft.setAttention(
                model,
                ModelCraft.Attention(
                    enabled = true,
                    window = window,
                    fullAttentionEveryNthLayer = fullEvery ?: 2,
                ),
            )
        }
        return model
    }

    /** Four synthetic vowels plus the merged CSV that measures their prosody. */
    private fun dataset(model: CraftedModel): File {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val pitches = listOf(110.0, 145.0, 190.0, 235.0)
        pitches.forEachIndexed { index, f0 ->
            writeWav(File(directory, "clip${index + 1}.wav"), f0 = f0, formants = 700.0 + 90.0 * index)
        }
        // Only a few voice columns are filled: the empty ones must be imputed, and named as imputed.
        val header = listOf("file", "f0_mean_hz", "hnr_db") + AcousticTrainer.CONDITIONING_COLUMNS
            .filterNot { it == "f0_mean_hz" || it == "hnr_db" }
        val rows = pitches.mapIndexed { index, f0 ->
            listOf("clip${index + 1}.wav", f0.toString(), (18.0 + index).toString()) +
                List(header.size - 3) { "" }
        }.joinToString("\n") { it.joinToString(",") }
        File(directory, "dataset_merged.csv").writeText(header.joinToString(",") + "\n" + rows + "\n")
        return directory
    }

    private fun writeWav(file: File, f0: Double, formants: Double, rate: Int = 16_000, seconds: Double = 0.6) {
        val count = (seconds * rate).toInt()
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            var value = 0.0
            var harmonic = 1
            while (harmonic <= 14) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                val first = 1.0 / (1.0 + Math.pow((frequency - formants) / 300.0, 2.0))
                val second = 0.6 / (1.0 + Math.pow((frequency - (formants + 500.0)) / 400.0, 2.0))
                value += (first + second) * Math.sin(2 * Math.PI * frequency * t) / harmonic
                harmonic++
            }
            val envelope = 0.5 + 0.5 * Math.sin(2 * Math.PI * 4.0 * t)
            samples[i] = (value * envelope * 7000.0).toInt().coerceIn(-32_000, 32_000).toShort()
        }
        val out = java.io.ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) =
            out.write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
        val dataBytes = count * 2
        ascii("RIFF"); int32(4 + 8 + 16 + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(dataBytes)
        samples.forEach { int16(it.toInt()) }
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    @Test
    fun `training reduces the loss and writes artifacts that load back exactly`() {
        val model = model(window = 24, fullEvery = 2)
        dataset(model)
        val recipe = ModelCraft.recipeFile(model)

        val result = assertNotNull(
            AcousticTrainer.trainResult(model, recipe),
            "an audio model with a merged CSV and decodable clips must train",
        )
        val summary = result.summary
        assertTrue(
            summary.contains("sliding window"),
            "the recipe asked for a sliding window and the summary does not say so: $summary",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val metadataFile = File(directory, AcousticTrainer.METADATA_FILE)
        assertTrue(metadataFile.isFile, "no metadata was written")
        assertTrue(File(directory, AcousticTrainer.WEIGHTS_FILE).isFile, "no weights were written")

        val metadata = JSONObject(metadataFile.readText())
        val first = metadata.optDouble("loss_first")
        val last = metadata.optDouble("loss_last")
        assertTrue(first.isFinite() && last.isFinite(), "the losses were not recorded: $first -> $last")
        assertTrue(last < first, "training did not reduce the loss: $first -> $last")
        assertTrue(metadata.optInt("steps") > 0, "no step was taken")
        assertTrue(
            (metadata.optJSONArray("imputed_conditioning_columns")?.length() ?: 0) > 0,
            "the columns that could not be measured must be named",
        )
        assertEquals(
            AcousticTrainer.CONDITIONING_COLUMNS.size,
            metadata.optJSONArray("conditioning_columns")?.length(),
            "every conditioning column must be recorded, measured or imputed",
        )

        // A reader must get the SAME model back: a weights file that loads but computes something else
        // is worse than none, because nothing downstream would notice.
        val loaded = assertNotNull(AcousticTrainer.load(directory), "the artifacts did not load back")
        val clip = assertNotNull(AudioDecoding.read(File(directory, "clip1.wav")))
        val frames = VocalAnalysis.framesOf(clip)
        val conditioning = DoubleArray(AcousticTrainer.CONDITIONING_COLUMNS.size) { it.toDouble() }
        val before = result.trained.predict(conditioning, frames)
        val after = loaded.predict(conditioning, frames)
        assertEquals(before.size, after.size, "the reloaded model predicted a different number of frames")
        for (index in before.indices) {
            for (dim in before[index].indices) {
                assertEquals(
                    before[index][dim],
                    after[index][dim],
                    1e-9,
                    "frame $index dimension $dim differs after a round trip",
                )
            }
        }
    }

    @Test
    fun `an audio build writes the model with its acoustic encoder and decoder`() {
        val model = model(epochs = 6)
        dataset(model)
        val result = assertNotNull(
            AcousticTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "an audio model with decodable clips must train",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        val names = listOf(
            "${AcousticTrainer.BASE_NAME}.safetensors",
            "${AcousticTrainer.BASE_NAME}.encoder.safetensors",
            "${AcousticTrainer.BASE_NAME}.decoder.safetensors",
        )
        names.forEach { assertTrue(File(directory, it).isFile, "$it was not written") }
        names.forEach {
            assertTrue(result.summary.contains(it), "the summary does not name $it: ${result.summary}")
        }

        val metadata = JSONObject(File(directory, AcousticTrainer.METADATA_FILE).readText())
        assertEquals(
            ModelCraft.FileFormat.SAFETENSORS.recipeValue,
            metadata.optString(ModelCraft.KEY_FILE_FORMAT),
        )
        val coder = assertNotNull(metadata.optJSONObject("encoder_decoder"), "the pair was not described")
        val inputs = coder.optInt("inputs")
        assertEquals(
            AcousticTrainer.TARGETS,
            inputs,
            "the pair must be trained on the acoustic frames the model predicts",
        )
        assertTrue(coder.optInt("code") < inputs, "the code is not narrower than what it codes")
        assertTrue(
            coder.optDouble("reconstruction_loss_last").isFinite(),
            "no reconstruction loss was recorded",
        )

        // And the pair is in memory: it reconstructs a frame rather than returning nothing.
        val pair = assertNotNull(result.trained.coder, "the trained model kept no encoder/decoder")
        val frame = DoubleArray(inputs) { index -> index / inputs.toDouble() }
        val back = pair.reconstruct(frame)
        assertEquals(inputs, back.size)
        assertTrue(back.all { it.isFinite() }, "the decoder produced a non-number")
    }

    @Test
    fun `a GGUF audio build is one file holding all three components`() {
        val model = model(epochs = 3)
        dataset(model)
        ModelCraft.setFileFormat(model, ModelCraft.FileFormat.GGUF)
        assertNotNull(AcousticTrainer.trainResult(model, ModelCraft.recipeFile(model)))
        val directory = ModelCraft.Store.datasetDir(model.id)
        val gguf = File(directory, "${AcousticTrainer.BASE_NAME}.gguf")
        assertTrue(gguf.isFile, "the chosen GGUF was not written")
        assertEquals("GGUF", String(gguf.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(
            File(directory, AcousticTrainer.METADATA_FILE).readText().contains(gguf.name),
            "the metadata does not name the file that was written",
        )
        assertTrue(
            !File(directory, "${AcousticTrainer.BASE_NAME}.safetensors").exists(),
            "the GGUF run wrote the other container as well",
        )
    }

    @Test
    fun `the conditioning vector changes what the model predicts`() {
        val model = model(epochs = 3)
        dataset(model)
        AcousticTrainer.train(model, ModelCraft.recipeFile(model))
        val directory = ModelCraft.Store.datasetDir(model.id)
        val trained = assertNotNull(AcousticTrainer.load(directory))
        val clip = assertNotNull(AudioDecoding.read(File(directory, "clip2.wav")))
        val frames = VocalAnalysis.framesOf(clip)

        val low = DoubleArray(AcousticTrainer.CONDITIONING_COLUMNS.size) { 0.0 }
        val high = DoubleArray(AcousticTrainer.CONDITIONING_COLUMNS.size) { 3.0 }
        val a = trained.predict(low, frames)
        val b = trained.predict(high, frames)

        val difference = a.indices.sumOf { index ->
            a[index].indices.sumOf { dim -> Math.abs(a[index][dim] - b[index][dim]) }
        }
        assertTrue(
            difference > 1e-6,
            "the prosody conditioning has no effect on the prediction: total difference $difference",
        )
    }

    @Test
    fun `a sliding layer sees only its window and the periodic full layer sees everything`() {
        val config = AcousticTrainer.Config(
            window = 3,
            fullEvery = 2,
            epochs = 1,
            learningRate = 1e-3,
            gradientClip = 1.0,
            seed = 7L,
        )
        val net = AcousticTrainer.Net(config, java.util.Random(config.seed))
        assertTrue(!net.isFull(0), "the 1st layer must slide")
        assertTrue(net.isFull(1), "every 2nd layer must be full")

        val length = 10
        val inputs = Array(length) { index -> DoubleArray(AcousticTrainer.FRAME_FEATURES) { (index + it).toDouble() } }
        val forward = net.Forward(inputs, DoubleArray(AcousticTrainer.CONDITIONING_COLUMNS.size))

        for (layer in 0 until AcousticTrainer.LAYERS) {
            val weights = forward.attention[layer]
            for (position in 0 until length) {
                for (other in 0 until length) {
                    val weight = weights[position][other]
                    if (other > position) {
                        assertEquals(0.0, weight, "a frame attended to the future at $position -> $other")
                    }
                    if (!net.isFull(layer) && other < position - config.window + 1) {
                        assertEquals(
                            0.0,
                            weight,
                            "a sliding layer looked past its window at $position -> $other",
                        )
                    }
                }
            }
        }

        // And the full layer's row for the last position must actually reach back beyond the window.
        val full = forward.attention[AcousticTrainer.LAYERS - 1]
        val last = length - 1
        val beyondWindow = (0 until last - config.window + 1).sumOf { other -> full[last][other] }
        assertTrue(beyondWindow > 0.0, "the full layer did not attend past the sliding window")
    }

    @Test
    fun `the schedule boxes bound an audio run and its encoder and decoder too`() {
        val model = model(epochs = 2)
        dataset(model)
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 2, stepsPerEpoch = 1))

        val result = assertNotNull(AcousticTrainer.trainResult(model, ModelCraft.recipeFile(model)))

        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), AcousticTrainer.METADATA_FILE).readText(),
        )
        assertEquals(2, metadata.optInt("steps"), "two epochs of one step each did not stop at two")
        assertEquals(2, metadata.optInt("epochs"))
        assertEquals(1, metadata.optInt("steps_per_epoch"))
        assertTrue(
            result.summary.contains("trained over 2 step(s)"),
            "the summary does not name the steps actually taken: ${result.summary}",
        )
        val coder = assertNotNull(metadata.optJSONObject("encoder_decoder"))
        assertEquals(2, coder.optInt("steps"), "the encoder and decoder ignored the schedule")
    }
}
