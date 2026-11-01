package com.prism.launcher.datasetcraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.speech.LanguageId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DatasetCraft's auto-fill, on clips made here with known pitch.
 *
 * The point of the control is that a folder of takes becomes a training CSV without the user
 * typing anything, so the assertions are the values a trainer reads: the transcript whisper would
 * have produced, the language those words identify, one generated speaker label, and the side of
 * the 160 Hz split the synthetic voice is on. A half-labelled dataset must keep what the user typed.
 */
class AudioAutofillTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-audio-autofill-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        DatasetCraft.fetcher = null
        LanguageId.transcriber = null
    }

    @AfterTest
    fun cleanUp() {
        DatasetCraft.fetcher = null
        LanguageId.transcriber = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `fills language transcript speaker and gender from the clip`() {
        val dataset = DatasetCraft.create("Takes", DatasetCraft.Kind.AUDIO)
        writeWav(File(dataset.folder, "low.wav"), f0 = 120.0)
        writeWav(File(dataset.folder, "high.wav"), f0 = 220.0)
        DatasetCraft.addRow(dataset, "low.wav")
        DatasetCraft.addRow(dataset, "high.wav")
        LanguageId.transcriber = { "The quick brown fox jumps over the lazy dog." }

        val result = AudioAutofill.fill(dataset)

        assertEquals(2, result.filled, "both blank rows should be filled")
        assertEquals(2, result.transcribed, "both clips have words to write down")
        assertEquals(AudioAutofill.GENERATED_SPEAKER, result.speaker)
        assertTrue(result.skipped.isEmpty(), "both clips were in the dataset folder: ${result.skipped}")

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id), "the dataset was not saved")
        val low = reloaded.rows.first { it.path == "low.wav" }
        val high = reloaded.rows.first { it.path == "high.wav" }
        assertEquals("en", low.value("language"))
        assertEquals("en", high.value("language"))
        assertEquals("The quick brown fox jumps over the lazy dog.", low.value("transcript"))
        assertEquals(AudioAutofill.GENERATED_SPEAKER, low.value("speaker"))
        assertEquals(AudioAutofill.GENERATED_SPEAKER, high.value("speaker"))
        assertEquals("male", low.value(DatasetCraft.GENDER_COLUMN))
        assertEquals("female", high.value(DatasetCraft.GENDER_COLUMN))
        assertTrue(
            DatasetCraft.Csv.read(reloaded.csvFile).header.containsAll(
                listOf("language", "transcript", "speaker", DatasetCraft.GENDER_COLUMN),
            ),
            "the filled columns must be in the CSV the trainer reads: ${reloaded.columns}",
        )
    }

    @Test
    fun `a value the user typed is left alone`() {
        val dataset = DatasetCraft.create("Hand labelled", DatasetCraft.Kind.AUDIO)
        writeWav(File(dataset.folder, "take.wav"), f0 = 220.0)
        DatasetCraft.addRow(
            dataset,
            "take.wav",
            mapOf(
                "language" to "fr",
                "transcript" to "Bonjour, ceci est un texte.",
                "speaker" to "Ada",
                "gender" to "male",
            ),
        )
        LanguageId.transcriber = { "This should not replace the typed transcript." }

        val result = AudioAutofill.fill(dataset)

        assertEquals(0, result.filled, "a fully labelled row has nothing missing")
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        val row = reloaded.rows.single()
        assertEquals("fr", row.value("language"))
        assertEquals("Bonjour, ceci est un texte.", row.value("transcript"))
        assertEquals("Ada", row.value("speaker"))
        assertEquals("male", row.value("gender"), "a typed gender must not be inferred over")
    }

    @Test
    fun `missing audio columns are added for an imported or sparse audio dataset`() {
        val dataset = DatasetCraft.create("Sparse", DatasetCraft.Kind.AUDIO)
        assertTrue(DatasetCraft.removeColumn(dataset, "transcript"))
        assertTrue(DatasetCraft.removeColumn(dataset, "speaker"))
        assertTrue(DatasetCraft.removeColumn(dataset, DatasetCraft.GENDER_COLUMN))
        writeWav(File(dataset.folder, "one.wav"), f0 = 120.0)
        DatasetCraft.addRow(dataset, "one.wav")
        LanguageId.transcriber = { "A short sentence in English." }

        AudioAutofill.fill(dataset)

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        val row = reloaded.rows.single()
        assertTrue(reloaded.columns.containsAll(listOf("language", "transcript", "speaker", "gender")))
        assertEquals("A short sentence in English.", row.value("transcript"))
        assertEquals("male", row.value("gender"))
    }

    @Test
    fun `a downloaded URL row is filled by the file the link names`() {
        val dataset = DatasetCraft.create("Linked takes", DatasetCraft.Kind.AUDIO)
        DatasetCraft.addUrlRow(dataset, "https://example.invalid/clips/take-9.wav")
        val source = File(scratch, "source.wav")
        writeWav(source, f0 = 120.0)
        DatasetCraft.fetcher = { source.readBytes() }
        assertEquals(1, DatasetCraft.materialise(dataset.folder))
        LanguageId.transcriber = { "The link's file is beside the manifest." }

        val result = AudioAutofill.fill(dataset)

        assertEquals(1, result.filled)
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        val row = reloaded.rows.single()
        assertEquals("en", row.value("language"))
        assertEquals("The link's file is beside the manifest.", row.value("transcript"))
        assertEquals("male", row.value("gender"))
    }

    @Test
    fun `a video dataset is transcribed without inventing a speaker`() {
        val dataset = DatasetCraft.create("Shots", DatasetCraft.Kind.VIDEO)
        // The bytes are a WAV under a video name; the shared reader decodes the audio track on a
        // phone and on a desktop, and this test covers the dataset side of the same path.
        writeWav(File(dataset.folder, "scene.mp4"), f0 = 180.0)
        DatasetCraft.addRow(dataset, "scene.mp4")
        LanguageId.transcriber = { "The line spoken in the scene." }

        val result = AudioAutofill.fill(dataset)

        assertEquals(1, result.transcribed)
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        val row = reloaded.rows.single()
        assertEquals("The line spoken in the scene.", row.value("transcript"))
        assertEquals("en", row.value("language"))
        assertFalse(
            reloaded.columns.contains("speaker"),
            "a video is not one voice, so a generated speaker label would be a false claim: ${reloaded.columns}",
        )
    }

    @Test
    fun `a typed transcript supplies its language without being heard again`() {
        val dataset = DatasetCraft.create("Typed", DatasetCraft.Kind.AUDIO)
        writeWav(File(dataset.folder, "take.wav"), f0 = 120.0)
        DatasetCraft.addRow(
            dataset,
            "take.wav",
            mapOf("transcript" to "The quick brown fox jumps over the lazy dog."),
        )
        // No engine at all: the transcript is already the answer, and reading its language needs
        // no model.
        LanguageId.transcriber = null

        val result = AudioAutofill.fill(dataset)

        assertEquals(0, result.transcribed, "a filled transcript must never be transcribed again")
        assertNull(result.unavailableReason, "nothing needed transcription, so no engine was needed")
        val row = assertNotNull(DatasetCraft.find(dataset.id)).rows.single()
        assertEquals("The quick brown fox jumps over the lazy dog.", row.value("transcript"))
        assertEquals("en", row.value("language"))
    }

    @Test
    fun `a filled description beside an empty transcript is never heard again`() {
        val dataset = DatasetCraft.create("Described", DatasetCraft.Kind.AUDIO)
        // The default dataset has a transcript column. Add a description column the way an imported
        // export would, fill ONLY the description, and leave the transcript empty.
        assertTrue(DatasetCraft.addColumn(dataset, "description"))
        writeWav(File(dataset.folder, "take.wav"), f0 = 120.0)
        DatasetCraft.addRow(
            dataset,
            "take.wav",
            mapOf("description" to "The quick brown fox jumps over the lazy dog."),
        )
        var calls = 0
        LanguageId.transcriber = {
            calls++
            "This must not replace the description."
        }

        val result = AudioAutofill.fill(dataset)

        assertEquals(0, result.transcribed, "an empty transcript must not hide the filled description")
        assertEquals(0, calls, "the words were already in the CSV, so the clip must not be heard")
        assertNull(result.unavailableReason, "nothing needed transcription, so no engine was needed")
        val row = assertNotNull(DatasetCraft.find(dataset.id)).rows.single()
        assertEquals("The quick brown fox jumps over the lazy dog.", row.value("description"))
        assertEquals("en", row.value("language"), "the description supplies the language")
    }

    @Test
    fun `an exported text column is treated as the transcript it already is`() {
        val dataset = DatasetCraft.create("Exported", DatasetCraft.Kind.AUDIO)
        assertTrue(DatasetCraft.removeColumn(dataset, "transcript"), "the default column must be removable here")
        assertTrue(DatasetCraft.addColumn(dataset, "text"))
        writeWav(File(dataset.folder, "take.wav"), f0 = 120.0)
        DatasetCraft.addRow(
            dataset,
            "take.wav",
            mapOf("text" to "The quick brown fox jumps over the lazy dog."),
        )
        LanguageId.transcriber = null

        val result = AudioAutofill.fill(dataset)

        assertEquals(0, result.transcribed, "a filled `text` column is a transcript")
        assertNull(result.unavailableReason, "no transcription was needed")
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        assertFalse(
            reloaded.columns.any { it.equals("transcript", true) },
            "a second words column was added beside the one the user exported: ${reloaded.columns}",
        )
        assertEquals("en", reloaded.rows.single().value("language"))
    }

    @Test
    fun `no engine is named instead of counting every clip as silent`() {
        val dataset = DatasetCraft.create("Unheard", DatasetCraft.Kind.AUDIO)
        writeWav(File(dataset.folder, "take.wav"), f0 = 120.0)
        DatasetCraft.addRow(dataset, "take.wav")

        val result = AudioAutofill.fill(dataset)

        assertNotNull(result.unavailableReason, "a missing engine must be named")
        assertTrue(
            result.summary.contains("transcription"),
            "the summary hid the reason behind a silence claim: ${result.summary}",
        )
        assertEquals(0, result.transcribed)
        assertEquals(
            0,
            result.withoutWords,
            "without a transcriber the clips were never heard, so they cannot be called wordless",
        )
        // Speaker and gender are properties of the waveform, so they are still measured; only the
        // words and the language they identify need an engine.
        assertEquals("male", assertNotNull(DatasetCraft.find(dataset.id)).rows.single().value("gender"))
    }

    @Test
    fun `the auto-fill choice is remembered with the dataset`() {
        val dataset = DatasetCraft.create("Remembered", DatasetCraft.Kind.AUDIO)
        dataset.autoFillAudio = true
        DatasetCraft.save(dataset)

        assertTrue(
            assertNotNull(DatasetCraft.find(dataset.id)).autoFillAudio,
            "a checkbox that forgets itself is a checkbox the user has to keep ticking",
        )
    }

    @Test
    fun `the control does nothing for a non-audio dataset`() {
        val dataset = DatasetCraft.create("Notes", DatasetCraft.Kind.TEXT)
        DatasetCraft.addRow(dataset, "note.txt")
        LanguageId.transcriber = { "This must never run for a text corpus." }

        val result = AudioAutofill.fill(dataset)

        assertEquals(0, result.filled)
        assertFalse(dataset.columns.contains(DatasetCraft.GENDER_COLUMN))
    }

    /**
     * A RIFF/WAVE file with a known fundamental.
     *
     * The same synthetic voice the speech tests use: harmonics shaped by two formants, an amplitude
     * envelope at syllable rate, and a silent stretch so the analysis has a pause to find. Only the
     * pitch matters here, but a pure sine would give the pitch tracker a signal no real voice has.
     */
    private fun writeWav(file: File, f0: Double, seconds: Double = 2.0, rate: Int = 16_000) {
        val frameCount = (seconds * rate).toInt()
        val samples = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            val t = i.toDouble() / rate
            var value = 0.0
            var harmonic = 1
            while (harmonic <= 12) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                val first = 1.0 / (1.0 + Math.pow((frequency - 700.0) / 300.0, 2.0))
                val second = 0.6 / (1.0 + Math.pow((frequency - 1200.0) / 400.0, 2.0))
                value += (first + second) * Math.sin(2 * Math.PI * frequency * t) / harmonic
                harmonic++
            }
            val syllables = 0.5 + 0.5 * Math.sin(2 * Math.PI * 5.0 * t)
            val pause = if (t > seconds * 0.45 && t < seconds * 0.55) 0.0 else 1.0
            samples[i] = (value * syllables * pause * 6000.0).toInt().coerceIn(-32_000, 32_000).toShort()
        }

        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) = out.write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))

        val dataBytes = frameCount * 2
        ascii("RIFF"); int32(4 + 8 + 16 + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(dataBytes)
        samples.forEach { int16(it.toInt()) }

        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
