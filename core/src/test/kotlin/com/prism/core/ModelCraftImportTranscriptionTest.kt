package com.prism.core

import com.prism.launcher.datasetcraft.DatasetCraft
import com.prism.launcher.messaging.Dictation
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TtsTrainer
import com.prism.launcher.speech.LanguageId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Importing a folder of speech without a CSV must still produce a CSV a trainer can read.
 *
 * This is the difference between "a dataset" and "a folder of files": the importer listens to the
 * clips and writes their words into the dataset CSV, so the next train sees transcripts instead of
 * reporting that there are none.
 */
class ModelCraftImportTranscriptionTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-import-transcription-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
        LanguageId.transcriber = null
        Dictation.reset()
    }

    @AfterTest
    fun cleanUp() {
        LanguageId.transcriber = null
        Dictation.reset()
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `an audio import creates the transcript column the trainer reads`() {
        val model = ModelCraft.create(
            "Imported narrator",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params["epochs"] = "1"
        model.params[ModelCraft.KEY_STEPS_PER_EPOCH] = "1"
        val first = File(scratch, "take-one.wav")
        val second = File(scratch, "take-two.wav")
        writeWav(first, f0 = 150.0)
        writeWav(second, f0 = 210.0)
        LanguageId.transcriber = { "The quick brown fox jumps over the lazy dog." }

        ModelCraft.attachDataset(model, listOf(first, second))

        val directory = ModelCraft.Store.datasetDir(model.id)
        val csv = File(directory, DatasetCraft.CSV_FILE)
        assertTrue(csv.isFile, "the import did not create a dataset CSV")
        val table = DatasetCraft.Csv.read(csv)
        val transcript = table.header.indexOfFirst { it.equals("transcript", true) }
        assertTrue(transcript >= 0, "the CSV has no transcript column: ${table.header}")
        val row = assertNotNull(table.rows.firstOrNull())
        assertEquals("The quick brown fox jumps over the lazy dog.", row[transcript])
        assertTrue(csv.readText().contains("en"), "the transcript's language was not written")

        // The whole point of writing into the CSV: the trainer that previously reported "no
        // transcripts" now has words to align.
        assertNotNull(
            TtsTrainer.train(model, ModelCraft.recipeFile(model)),
            "the transcribed import still trained no text-to-speech model",
        )
    }

    @Test
    fun `resync replaces an edited clip and CSV instead of adding timestamped copies`() {
        val model = ModelCraft.create(
            "Re-synced narrator",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        val sourceCsv = File(scratch, "metadata.csv")
        val sourceClip = File(scratch, "take.wav")
        writeWav(sourceClip, f0 = 150.0, seconds = 1.0)
        sourceCsv.writeText("file,transcript\ntake.wav,\"Words from the first import.\"\n")
        ModelCraft.attachDataset(model, listOf(sourceCsv, sourceClip))

        val directory = ModelCraft.Store.datasetDir(model.id)
        val copied = File(directory, "take.wav")
        val originalLength = copied.length()

        // The person edits the source clip and the CSV, then re-syncs. The new take is a different
        // length, which a normal import would treat as a second file and copy under a timestamp.
        writeWav(sourceClip, f0 = 220.0, seconds = 1.8)
        sourceCsv.writeText(
            "file,transcript\ntake.wav,\"The edited words the trainer must see.\"\n",
        )
        ModelCraft.resyncDataset(model, listOf(sourceCsv, sourceClip))

        val clips = directory.listFiles().orEmpty().filter { it.extension.equals("wav", true) }
        assertEquals(1, clips.size, "re-sync left a second, timestamped copy: ${clips.map { it.name }}")
        assertTrue(copied.length() != originalLength, "the changed clip was not replaced")
        assertEquals(sourceClip.length(), copied.length(), "the copied clip is not the picked one")

        val table = DatasetCraft.Csv.read(File(directory, "metadata.csv"))
        val transcript = table.header.indexOfFirst { it.equals("transcript", true) }
        assertTrue(transcript >= 0, "the CSV lost its transcript column: ${table.header}")
        assertEquals(
            "The edited words the trainer must see.",
            assertNotNull(table.rows.firstOrNull())[transcript],
            "the edited CSV in the model's folder was not updated",
        )
    }

    @Test
    fun `a video import writes the words spoken in its audio track`() {
        val model = ModelCraft.create(
            "Imported scenes",
            ModelCraft.Kind.VIDEO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.VIDEO).first().name,
        )
        // WAV bytes under a video name: the container is the platform decoder's business, and the
        // point here is that the video import runs the same audio track through transcription.
        val clip = File(scratch, "scene.mp4")
        writeWav(clip, f0 = 180.0)
        LanguageId.transcriber = { "The line spoken in the scene." }

        ModelCraft.attachDataset(model, listOf(clip))

        val directory = ModelCraft.Store.datasetDir(model.id)
        val csv = File(directory, DatasetCraft.CSV_FILE)
        assertTrue(csv.isFile, "the video import did not create a dataset CSV")
        val table = DatasetCraft.Csv.read(csv)
        val transcript = table.header.indexOfFirst { it.equals("transcript", true) }
        assertTrue(transcript >= 0, "the CSV has no transcript column: ${table.header}")
        assertEquals(
            "The line spoken in the scene.",
            assertNotNull(table.rows.firstOrNull())[transcript],
            "the video's audio track was not transcribed into the CSV",
        )
    }

    private fun writeWav(file: File, f0: Double = 150.0, seconds: Double = 1.2, rate: Int = 16_000) {
        val count = (seconds * rate).toInt()
        val samples = ShortArray(count) { index ->
            val t = index.toDouble() / rate
            var value = 0.0
            for (harmonic in 1..10) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                value += Math.sin(2 * Math.PI * frequency * t) / harmonic
            }
            (value * 6000).toInt().coerceIn(-32_000, 32_000).toShort()
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
