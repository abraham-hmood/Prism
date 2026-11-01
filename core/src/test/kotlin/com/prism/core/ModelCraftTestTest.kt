package com.prism.core

import com.prism.launcher.modelcraft.AcousticTrainer
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftTest
import com.prism.launcher.modelcraft.PixelTrainer
import com.prism.launcher.modelcraft.TextTrainer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The test bench every kind shares: that a built model can be asked for something, that what comes
 * back is the shape the pages know how to show, and that it needs a built model before it will run.
 *
 * The models here are trained for one step, so nothing claims a good picture or an intelligible
 * voice. What is pinned is the plumbing between the trainer, the artifacts and the view: text comes
 * back as text, pictures as pictures, sound as playable samples plus a waveform, and the same input
 * gives the same result twice.
 */
class ModelCraftTestTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelcraft-testbench-${System.nanoTime()}",
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

    private fun model(kind: ModelCraft.Kind): CraftedModel {
        val created = ModelCraft.create(
            "Bench ${kind.label}",
            kind,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(kind).first().name,
        )
        created.params["epochs"] = "1"
        created.params["learning_rate"] = "0.002"
        created.params["resolution"] = "16"
        created.params["crop_size"] = "16"
        created.params["width"] = "16"
        created.params["height"] = "16"
        ModelCraft.setSchedule(created, ModelCraft.Schedule(epochs = 1, stepsPerEpoch = 1))
        return created
    }

    @Test
    fun `nothing is testable before the model is built`() {
        val model = model(ModelCraft.Kind.TEXT)
        assertFalse(ModelCraftTest.ready(model))
        assertFailsWith<IllegalStateException> { ModelCraftTest.run(model, "hello") }

        assertEquals("Prompt", ModelCraftTest.promptLabel(ModelCraft.Kind.TEXT))
        for (kind in ModelCraft.Kind.entries) {
            assertTrue(ModelCraftTest.promptHint(kind).isNotBlank(), "${kind.label} has no hint")
            assertTrue(ModelCraftTest.promptLabel(kind).isNotBlank(), "${kind.label} has no label")
        }
    }

    @Test
    fun `a trained text model continues the prompt and the same words give the same text`() {
        val model = model(ModelCraft.Kind.TEXT)
        val directory = ModelCraft.Store.datasetDir(model.id)
        directory.mkdirs()
        File(directory, "corpus.txt").writeText(
            ("Prism writes the same sentence over and over so a byte model has something to learn. "
                ).repeat(20),
        )
        assertTrue(TextTrainer.train(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED

        val first = ModelCraftTest.run(model, "Prism") as ModelCraftTest.Output.Text
        val again = ModelCraftTest.run(model, "Prism") as ModelCraftTest.Output.Text
        assertTrue(first.text.startsWith("Prism"), "the prompt was dropped: ${first.text}")
        assertEquals(first.text, again.text, "the same prompt produced a different continuation")
        assertTrue(first.caption.contains("Byte-level"), "the caption does not say what made it")
    }

    @Test
    fun `a trained image model produces a picture at the model's own size`() {
        val model = model(ModelCraft.Kind.IMAGE)
        for (index in 0 until 4) {
            writeImage(File(ModelCraft.Store.datasetDir(model.id), "image-$index.png"), index)
        }
        assertTrue(PixelTrainer.trainImage(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED

        val output = ModelCraftTest.run(model, "pattern") as ModelCraftTest.Output.Film
        assertEquals(1, output.frames.size, "an image test should show one picture")
        assertEquals(0, output.fps, "a still image should not animate")
        val picture = output.frames.first()
        assertEquals(16, picture.width, "the picture is not the model's resolution")
        assertEquals(picture.width * picture.height, picture.argb.size)
        assertTrue(picture.argb.any { (it ushr 24) and 0xFF == 0xFF }, "the picture has no opaque pixel")

        val again = ModelCraftTest.run(model, "pattern") as ModelCraftTest.Output.Film
        assertTrue(
            picture.argb.contentEquals(again.frames.first().argb),
            "the same seed produced a different picture",
        )
    }

    @Test
    fun `a masked-patch image model reconstructs a hidden square`() {
        val model = model(ModelCraft.Kind.IMAGE)
        model.family = ModelCraft.archTypes.getValue(ModelCraft.Kind.IMAGE)
            .first { it.name.contains("Generative adversarial") }.name
        for (index in 0 until 8) {
            writeImage(File(ModelCraft.Store.datasetDir(model.id), "image-$index.png"), index)
        }
        assertTrue(PixelTrainer.trainImage(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED

        val output = ModelCraftTest.run(model, "patch") as ModelCraftTest.Output.Film
        assertEquals(1, output.frames.size)
        assertTrue(
            output.caption.contains("Reconstructed a hidden patch"),
            "the caption does not say what the masked model was asked to do: ${output.caption}",
        )
        assertTrue(output.frames.first().argb.all { (it ushr 24) and 0xFF == 0xFF })
    }

    @Test
    fun `a trained interpolator shows the frames on either side and its middle`() {
        val model = model(ModelCraft.Kind.INTERPOLATION)
        for (index in 0 until 5) {
            writeImage(
                File(ModelCraft.Store.framesDir(model.id), "clip-a/frame-%05d.png".format(index)),
                index,
            )
        }
        assertTrue(PixelTrainer.trainInterpolation(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED

        val output = ModelCraftTest.run(model, "clip") as ModelCraftTest.Output.Film
        assertEquals(3, output.frames.size, "interpolation should show left, middle and right")
        assertEquals(2, output.fps, "the three frames should step so the middle is visible")
    }

    @Test
    fun `a trained video model continues a clip frame by frame`() {
        val model = model(ModelCraft.Kind.VIDEO)
        for (index in 0 until 6) {
            writeImage(
                File(ModelCraft.Store.framesDir(model.id), "clip-a/frame-%05d.png".format(index)),
                index,
            )
        }
        assertTrue(PixelTrainer.trainVideo(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED

        val output = ModelCraftTest.run(model, "clip") as ModelCraftTest.Output.Film
        assertTrue(output.frames.size > 2, "the video test generated no new frames")
        assertTrue(output.fps > 0, "the generated frames will not play")
        assertEquals(output.frames.first().argb.size, output.frames.last().argb.size)
    }

    @Test
    fun `a trained audio model renders predicted frames as a playable waveform`() {
        val model = model(ModelCraft.Kind.AUDIO)
        val directory = ModelCraft.Store.datasetDir(model.id)
        directory.mkdirs()
        writeWav(File(directory, "clip1.wav"), f0 = 130.0)
        writeWav(File(directory, "clip2.wav"), f0 = 180.0)
        File(directory, "dataset_merged.csv").writeText(
            "file,f0_mean_hz\nclip1.wav,130\nclip2.wav,180\n",
        )
        assertTrue(AcousticTrainer.train(model, ModelCraft.recipeFile(model)) != null)
        model.status = ModelCraft.STATUS_TRAINED
        // This dataset has no transcripts, so there is no text-to-speech model; the miniature
        // renderer is opt-in now, and this test is the one that asks for it.
        ModelCraft.setMiniatureFallback(model, true)

        val output = ModelCraftTest.run(model, "low") as ModelCraftTest.Output.Sound
        assertTrue(output.samples.isNotEmpty(), "the acoustic model rendered no samples")
        assertTrue(output.samples.any { it != 0f }, "the render is silent")
        assertEquals(16_000, output.sampleRate)
        assertTrue(output.waveform.width > 0 && output.waveform.height > 0)
        assertEquals("RIFF", String(output.wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(output.wav, 8, 4, Charsets.US_ASCII))
        assertTrue(output.caption.contains("predicted acoustic frames"))

        val again = ModelCraftTest.run(model, "low") as ModelCraftTest.Output.Sound
        assertTrue(
            output.samples.contentEquals(again.samples),
            "the same seed produced a different render",
        )
    }

    /** A moving band, so neighbouring frames differ and interpolation has something to predict. */
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

    /** A synthetic vowel with two resonances: enough for the acoustic front end to measure. */
    private fun writeWav(file: File, f0: Double, rate: Int = 16_000, seconds: Double = 0.4) {
        val count = (seconds * rate).toInt()
        val samples = ShortArray(count)
        for (index in 0 until count) {
            val time = index.toDouble() / rate
            var value = 0.0
            for (harmonic in 1..10) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                val first = 1.0 / (1.0 + ((frequency - 700.0) / 300.0).pow(2.0))
                val second = 0.6 / (1.0 + ((frequency - 1200.0) / 400.0).pow(2.0))
                value += (first + second) * sin(2.0 * PI * frequency * time) / harmonic
            }
            samples[index] = (value * 7000.0).toInt().coerceIn(-32_000, 32_000).toShort()
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
