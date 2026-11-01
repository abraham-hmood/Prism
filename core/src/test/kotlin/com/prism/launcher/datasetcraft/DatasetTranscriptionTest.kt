package com.prism.launcher.datasetcraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.Dictation
import com.prism.launcher.speech.LanguageId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic transcription of a dataset that has no transcript column yet.
 *
 * The dataset's own CSV is the contract: a trainer reads that file, so the words have to be in it,
 * not in a sidecar text file. The other half of the contract is what must NOT happen -- a typed
 * transcript is never replaced, and a machine with no transcription engine says why instead of
 * writing empty cells.
 */
class DatasetTranscriptionTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-transcription-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        LanguageId.transcriber = null
        Dictation.reset()
    }

    @AfterTest
    fun cleanUp() {
        LanguageId.transcriber = null
        Dictation.reset()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `an empty description column is filled and language is added`() {
        val directory = File(scratch, "dataset").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        File(directory, "dataset.csv").writeText("file,description\ntake.wav,\n")
        LanguageId.transcriber = { "A sentence the model can learn from." }

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(1, result.transcribed, result.summary)
        val table = DatasetCraft.Csv.read(File(directory, "dataset.csv"))
        val row = assertNotNull(table.rows.firstOrNull())
        val description = table.header.indexOf("description")
        val language = table.header.indexOf("language")
        assertTrue(description >= 0 && language >= 0, "the CSV did not keep or add both columns: ${table.header}")
        assertEquals("A sentence the model can learn from.", row[description])
        assertEquals("en", row[language])
    }

    @Test
    fun `auto transcription writes into the existing transcript column and saves it`() {
        val directory = File(scratch, "both").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        File(directory, "dataset.csv").writeText("file,transcript,description\ntake.wav,,\n")
        LanguageId.transcriber = { "Words the trainer must find in the CSV." }

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(1, result.transcribed, result.summary)
        // Reopened from disk: the point is that the words were SAVED, not merely returned.
        val table = DatasetCraft.Csv.read(File(directory, "dataset.csv"))
        assertEquals(
            listOf("file", "transcript", "description", "language"),
            table.header,
            "auto transcription added a second words column instead of filling the existing one",
        )
        val row = table.rows.single()
        assertEquals("Words the trainer must find in the CSV.", row[1])
        assertEquals("", row[2], "the blank description column must be left alone")
    }

    @Test
    fun `a transcript the user typed is never replaced`() {
        val directory = File(scratch, "typed").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        File(directory, "dataset.csv").writeText("file,transcript\ntake.wav,Already written.\n")
        var calls = 0
        LanguageId.transcriber = {
            calls++
            "This must not overwrite the typed words."
        }

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(0, result.transcribed)
        assertEquals(1, result.alreadyLabelled)
        assertEquals(0, calls, "a CSV with every transcript must not be heard again")
        assertNull(result.unavailableReason, "nothing was missing, so no engine was needed")
        val table = DatasetCraft.Csv.read(File(directory, "dataset.csv"))
        assertEquals("Already written.", table.rows.single()[1])
    }

    @Test
    fun `a CSV with every transcript needs no engine and starts no download`() {
        val directory = File(scratch, "complete").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        File(directory, "dataset.csv").writeText("file,transcript\ntake.wav,Already written.\n")
        // No transcriber and no registered engine: an empty pending list must return before either
        // is consulted, so a transcribed dataset never pays for a model download.
        LanguageId.transcriber = null
        Dictation.reset()

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(0, result.transcribed)
        assertEquals(1, result.alreadyLabelled)
        assertNull(result.unavailableReason, "an engine was asked for although nothing was missing")
    }

    @Test
    fun `a filled description column needs no transcription`() {
        val directory = File(scratch, "described").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        // The shape of a hand-written TTS manifest: a path and a sentence per row, no transcript
        // column at all. The description IS the words, so importing it must not start whisper.
        File(directory, "audio_descriptions.csv").writeText(
            "path,description\r\ntake.wav,The quick brown fox jumps over the lazy dog.\r\n",
        )
        var calls = 0
        LanguageId.transcriber = {
            calls++
            "This must not replace the typed description."
        }

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(0, result.transcribed, result.summary)
        assertEquals(1, result.alreadyLabelled)
        assertEquals(0, calls, "a filled description column must not be heard again")
        assertNull(result.unavailableReason)
        assertEquals(
            "The quick brown fox jumps over the lazy dog.",
            DatasetTranscription.transcripts(directory)["take.wav"],
            "the existing description should be available for language detection",
        )
    }

    @Test
    fun `a semicolon CSV with a description column is already transcribed`() {
        val directory = File(scratch, "semicolon").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))
        // What a spreadsheet in a comma-decimal locale writes: semicolons, and a description with a
        // comma inside quotes. Read as one comma-separated column, the header and the words vanish.
        File(directory, "dataset.csv").writeText(
            "file;description;language\r\ntake.wav;\"A sentence, already typed.\";en\r\n",
        )
        var calls = 0
        LanguageId.transcriber = {
            calls++
            "This must not replace the typed description."
        }

        val result = DatasetTranscription.transcribeMissing(directory)

        assertEquals(0, result.transcribed, "the CSV's description was not recognised as text")
        assertEquals(1, result.alreadyLabelled)
        assertEquals(0, calls, "a transcribed dataset must not be heard again")
        assertNull(result.unavailableReason)
    }

    @Test
    fun `a video file is transcribed from its audio track`() {
        val directory = File(scratch, "video").apply { mkdirs() }
        // A WAV's bytes under a video name: the container check is the decoder's job, and the
        // built-in reader proves the video path uses the same audio pipeline on a JVM with no
        // platform video decoder installed.
        writeWav(File(directory, "scene.mp4"))
        File(directory, "dataset.csv").writeText("file\nscene.mp4\n")
        LanguageId.transcriber = { "This is what the people in the shot said." }

        val result = DatasetTranscription.transcribeMissing(directory, includeVideo = true)

        assertEquals(1, result.transcribed, result.summary)
        val table = DatasetCraft.Csv.read(File(directory, "dataset.csv"))
        val transcript = table.header.indexOf("transcript")
        assertTrue(transcript >= 0, "no transcript column was added: ${table.header}")
        assertEquals("This is what the people in the shot said.", table.rows.single()[transcript])
    }

    @Test
    fun `no engine reports why instead of writing empty cells`() {
        val directory = File(scratch, "silent").apply { mkdirs() }
        writeWav(File(directory, "take.wav"))

        val result = DatasetTranscription.transcribeMissing(directory)

        assertNotNull(result.unavailableReason, "a missing engine must be named")
        assertEquals(0, result.transcribed)
        assertNull(File(directory, DatasetCraft.CSV_FILE).takeIf { it.exists() }, "a failed pass wrote a CSV")
    }

    /** A second of mono 16 kHz PCM. */
    private fun writeWav(file: File, seconds: Double = 1.0, rate: Int = 16_000) {
        val frames = (seconds * rate).toInt()
        val pcm = ShortArray(frames) { index ->
            (Math.sin(2 * Math.PI * 220.0 * index / rate) * 6000).toInt().toShort()
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

        val dataBytes = frames * 2
        ascii("RIFF"); int32(4 + 8 + 16 + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(dataBytes)
        pcm.forEach { int16(it.toInt()) }
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
