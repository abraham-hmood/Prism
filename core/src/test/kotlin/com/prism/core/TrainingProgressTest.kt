package com.prism.core

import com.prism.launcher.modelcraft.AcousticTrainer
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The live training line: a trainer reports its epoch, step, file, loss and accuracy as it goes.
 *
 * Without this a run shows "Training…" and nothing else for as long as it takes, which on an audio
 * dataset looks the same as a hang. The assertions are the fields the page prints, including that
 * accuracy is a real fraction rather than a value derived from the loss.
 */
class TrainingProgressTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-progress-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
        ModelCraft.progressListener = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.progressListener = null
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `an acoustic run reports every epoch step file loss and accuracy`() {
        val model = ModelCraft.create(
            "Narrator",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params["epochs"] = "2"
        model.params[ModelCraft.KEY_STEPS_PER_EPOCH] = "1"
        val directory = ModelCraft.Store.datasetDir(model.id)
        writeWav(File(directory, "low.wav"), 120.0)
        writeWav(File(directory, "high.wav"), 220.0)
        File(directory, "dataset.csv").writeText("file\nlow.wav\nhigh.wav\n")

        val reports = mutableListOf<ModelCraft.Progress>()
        ModelCraft.progressListener = { reports += it }

        assertNotNull(
            AcousticTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "two decodable clips must train",
        )

        assertTrue(reports.isNotEmpty(), "a two-step run reported nothing")
        val first = reports.first()
        assertEquals(1, first.epoch)
        assertEquals(2, first.epochs)
        assertEquals(1, first.step)
        assertEquals(1, first.steps)
        assertTrue(first.file in setOf("low.wav", "high.wav"), "the report named no clip: ${first.file}")
        assertTrue(first.loss.isFinite() && first.loss >= 0.0, "the loss was not a number: ${first.loss}")
        assertTrue(
            first.accuracy.isFinite() && first.accuracy in 0.0..1.0,
            "accuracy must be a fraction, not a label or a loss: ${first.accuracy}",
        )

        val last = reports.last()
        assertEquals(2, last.epoch, "the run stopped reporting before its last epoch")
        assertEquals(1, last.step)
        assertTrue(reports.all { it.line().contains("Epoch ") && it.line().contains("loss ") })
    }

    @Test
    fun `a text run reports the file each block came from`() {
        val model = ModelCraft.create(
            "Storyteller",
            ModelCraft.Kind.TEXT,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 2, stepsPerEpoch = 2))
        val directory = ModelCraft.Store.datasetDir(model.id)
        val sentence = "the cat sat on the warm mat and watched the rain. "
        File(directory, "first.txt").writeText(sentence.repeat(40))
        File(directory, "second.txt").writeText(sentence.repeat(40))

        val reports = mutableListOf<ModelCraft.Progress>()
        ModelCraft.progressListener = { reports += it }

        assertNotNull(
            TextTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "two text files are enough to train",
        )

        assertTrue(reports.isNotEmpty(), "a text run reported nothing")
        val names = reports.map { it.file }.toSet()
        assertTrue(
            names.all { it == "first.txt" || it == "second.txt" },
            "text progress named something other than the file it was reading: $names",
        )
        assertTrue(reports.all { it.loss.isFinite() }, "a text step reported no loss")
        assertTrue(reports.last().epoch == 2, "the run stopped reporting before its last epoch")
    }

    private fun writeWav(file: File, f0: Double, seconds: Double = 0.6, rate: Int = 16_000) {
        val count = (seconds * rate).toInt()
        val samples = ShortArray(count) { index ->
            val t = index.toDouble() / rate
            var value = 0.0
            for (harmonic in 1..8) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                value += Math.sin(2 * Math.PI * frequency * t) / harmonic
            }
            (value * 5000).toInt().coerceIn(-32_000, 32_000).toShort()
        }
        val out = ByteArrayOutputStream()
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
}
