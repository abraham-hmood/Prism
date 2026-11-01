package com.prism.launcher.speech

import com.prism.launcher.modelcraft.CraftedAudioModel
import com.prism.launcher.modelcraft.ModelCraft
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The speech preparation pipeline, end to end, on audio made here.
 *
 * ## What this is really testing
 *
 * The feature extraction is arithmetic over a waveform, and "it compiled" says nothing about whether
 * the numbers mean anything: a pitch tracker with an off-by-one in its lag search still returns a
 * frequency, still writes a CSV, and trains a model on nonsense. So the input is SYNTHESISED PULSE
 * TRAINS with a known fundamental, through known formants, with known pauses -- and the assertions
 * are that the measured pitch is the pitch that was played, that the pauses were found, and that the
 * merge put the measured columns next to the dataset's own rather than instead of them.
 *
 * The whole chain is covered because the failure modes are at the seams: WAV -> features (does the
 * header scan find the data chunk?), features -> CSV (are the columns the header says?), CSV -> merge
 * (is a row the dataset never mentioned still trained on?), rows -> voices (three voice types asked
 * for, three built?), rows -> model document (does a text-to-speech model really differ on disk from
 * a cloning one?).
 */
class SpeechDatasetTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "prism-speech-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        LanguageId.transcriber = null
        root.deleteRecursively()
    }

    @Test
    fun `the streamed wav reader finds the data chunk and decodes every frame`() {
        val file = File(root, "one.wav").also { it.parentFile.mkdirs() }
        writeWav(file, f0 = 120.0, seconds = 1.5, rate = 16_000)
        val clip = assertNotNull(AudioDecoding.readWav(file), "the reader did not read its own writer's file")
        assertEquals(24_000, clip.samples.size, "every frame of the data chunk should be decoded")
        assertEquals(16_000, clip.sampleRate)
        assertTrue(clip.samples.any { it != 0f }, "the samples came back silent")
    }

    @Test
    fun `a transcript already in the dataset is used instead of transcribing the clip again`() {
        val dir = File(root, "labelled").also { it.mkdirs() }
        writeWav(File(dir, "one.wav"), f0 = 120.0, seconds = 1.5)
        var calls = 0
        LanguageId.transcriber = {
            calls++
            "This must not be called when the CSV already has the words."
        }

        val extraction = SpeechDataset.extract(
            dir,
            transcripts = mapOf("one.wav" to "The quick brown fox jumps over the lazy dog."),
        )

        assertEquals(0, calls, "the clip was sent to transcription although its words were known")
        assertEquals(1, extraction.languages["en"] ?: 0)
    }

    @Test
    fun `features are measured, the language is detected per clip, and the merge keeps both halves`() {
        val dir = File(root, "dataset").also { it.mkdirs() }
        // Four speakers' worth of pitch, far enough apart that clustering has something to separate.
        val clips = listOf("one.wav" to 120.0, "two.wav" to 165.0, "three.wav" to 215.0, "four.wav" to 260.0)
        clips.forEach { (name, f0) -> writeWav(File(dir, name), f0 = f0, seconds = 2.0) }

        // What a TTS dataset actually ships: clip names and their transcripts, and NOTHING about the
        // voice. Two of the four clips are listed, deliberately: the other two must still be trained on.
        File(dir, "metadata.csv").writeText(
            "file,text,language\n" +
                "one.wav,\"The quick brown fox jumps over the lazy dog.\",\n" +
                "two.wav,\"And the dog was not impressed.\",\n",
        )

        LanguageId.transcriber = { "The quick brown fox jumps over the lazy dog." }
        val extraction = SpeechDataset.extract(dir, { _, _ -> })
        assertEquals(4, extraction.decoded, "every clip should decode")
        assertEquals(4, extraction.languages["en"] ?: 0, "the transcriber's language should be recorded")
        assertEquals(0, extraction.skipped.size)

        val first = extraction.rows.first { it.file == "one.wav" }
        val pitch = first.value("f0_mean_hz")
        assertTrue(
            pitch in 100.0..150.0,
            "the pulse train was played at 120 Hz and measured at $pitch Hz",
        )
        assertTrue(first.value("duration_s") in 1.9..2.1, "duration was ${first.value("duration_s")}")
        assertTrue(first.value("voiced_fraction") > 0.3, "most of this clip is voiced")
        assertTrue(first.value("pause_count") >= 1.0, "the silent stretch in the middle is a pause")
        assertTrue(first.value("hnr_db").let { !it.isNaN() }, "harmonicity should be measurable")
        // The two formants the pulse train was shaped through. An LPC model that found neither
        // would leave the columns that identify a speaker empty while reporting success.
        val f1 = first.value("formant_1_hz")
        val f2 = first.value("formant_2_hz")
        // WHAT IS PINNED HERE: that formants are FOUND, ORDERED and inside the band a vocal tract's
        // resonances occupy. What is deliberately not pinned here is HOW CLOSE they land to the 700
        // and 1200 Hz this clip was built with -- this clip's resonances are 300 and 400 Hz wide
        // Gaussians rather than two-pole filters, so "the formant" is not a number it has, and the
        // ACCURACY of the estimator is checked instead in FormantEstimateTest, on a resonator cascade
        // whose formants are the filters' own frequencies.
        assertTrue(f1 in 150.0..3500.0, "F1 is outside the band formants live in: $f1")
        assertTrue(f1 < f2, "formants must come back in order: F1=$f1, F2=$f2")
        assertTrue(f2 <= 3500.0, "a formant above the analysis band was reported: $f2")

        // EVERY CLIP'S PITCH MUST BE THE PITCH THAT WAS PLAYED. This is the assertion that makes the
        // rest of the file trustworthy: four clips at four known fundamentals, and clustering that
        // separates the wrong things because the pitch tracker reports the wrong numbers is the
        // failure this catches -- and it reported 120 Hz for all four before the lag search was right.
        clips.forEach { (name, playedPitch) ->
            val measured = extraction.rows.first { it.file == name }.value("f0_mean_hz")
            assertTrue(
                measured in playedPitch * 0.85..playedPitch * 1.15,
                "$name was played at $playedPitch Hz and measured at $measured Hz",
            )
        }

        val featuresFile = assertNotNull(extraction.featuresFile, "no features CSV was written")
        val featureLines = featuresFile.readLines().filter { it.isNotBlank() }
        assertEquals(VocalFeatures.HEADER.joinToString(","), featureLines.first())
        assertEquals(5, featureLines.size, "one header and one row per clip")

        val merged = SpeechDataset.merge(dir, featuresFile, listOf(File(dir, "metadata.csv")), { _, _ -> })
        assertEquals(SpeechDataset.MERGED_FILE, merged.name)
        val rows = readCsv(merged)
        val header = rows.first()
        assertTrue("text" in header, "the dataset's own columns must survive the merge")
        assertTrue("f0_mean_hz" in header, "the vocal columns must be added")
        assertTrue(
            "language_detected" in header,
            "a DETECTED language must not overwrite the dataset's own language column",
        )
        val body = rows.drop(1)
        assertEquals(
            4, body.size,
            "two listed clips plus the two the CSV never mentioned; got " +
                body.joinToString(", ") { it.first() },
        )
        // Every row names its recording, and the order is the dataset's own rows first -- its
        // ordering is its business -- then the clips it never mentioned, in the features file's order.
        assertEquals(
            listOf("one.wav", "two.wav", "four.wav", "three.wav"),
            body.map { it.first() },
            "every row must name the recording it describes",
        )
        val textIndex = header.indexOf("text")
        val pitchIndex = header.indexOf("f0_mean_hz")
        val oneRow = body.first { it.first().contains("one.wav") }
        assertTrue(oneRow[textIndex].contains("quick brown fox"), "the transcript was not carried over")
        assertTrue(oneRow[pitchIndex].toDouble() > 100.0, "the vocal columns are empty beside the dataset's")
        val appended = body.first { it.first().contains("three.wav") }
        assertEquals("", appended[textIndex], "a clip the CSV never mentioned has no transcript")

        // ── The voice bank ───────────────────────────────────────────────────────────────────────
        val bank = assertNotNull(
            SpeechDataset.voices(extraction.rows, 3, dir),
            "no voice plan was produced",
        )
        assertEquals(3L, bank.optLong("built"), "three voice types were asked for")
        assertTrue(File(dir, SpeechDataset.VOICES_FILE).isFile, "voices.json was not written")
        val one = assertNotNull(SpeechDataset.voices(extraction.rows, 1, dir), "one voice could not be built")
        assertEquals(1L, one.optLong("built"))

        val profile = assertNotNull(
            SpeechDataset.speakerProfile(extraction.rows, dir, "Tester"),
            "no speaker profile was written",
        )
        assertTrue(profile.readText().contains("\"speaker\":\"Tester\""))

        // ── The two kinds of audio model ─────────────────────────────────────────────────────────
        val tts = assertNotNull(
            CraftedAudioModel.write(
                dir, "Tester", ModelCraft.AudioType.TEXT_TO_SPEECH, extraction.rows, 3, bank,
            ),
        )
        assertEquals(3, tts.voices)
        val ttsText = tts.file.readText()
        assertTrue(ttsText.contains("\"task\":\"text-to-speech\""), "the task is not on the document")
        assertTrue(ttsText.contains("\"built_voices\":3"), "the voice count is not on the document")

        val clone = assertNotNull(
            CraftedAudioModel.write(dir, "Tester", ModelCraft.AudioType.VOICE_CLONING, extraction.rows),
        )
        assertEquals(1, clone.voices, "a cloning model is one speaker")
        val cloneText = clone.file.readText()
        assertTrue(cloneText.contains("\"task\":\"voice-cloning\""))
        assertTrue(cloneText.contains("\"speaker\""), "a cloning model has no speaker section")
        assertTrue(clone.reference.isNotBlank(), "no reference clip was chosen")
        // The two kinds must not be the same file with one field changed.
        assertTrue(cloneText != ttsText, "both audio tasks produced the same document")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A RIFF/WAVE file with a known voice in it.
     *
     * Harmonics at [f0] shaped by two formants (what a vocal tract does to a glottal pulse), an
     * amplitude envelope with syllable-rate peaks, and a silent stretch in the middle for the pause
     * detector. A LIST chunk with an ODD size is written before the data deliberately: chunks are
     * word-aligned, and a reader that ignores the pad byte finds the audio one byte early.
     */
    private fun writeWav(file: File, f0: Double, seconds: Double, rate: Int = 16_000) {
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
        ascii("RIFF"); int32(4 + 8 + 16 + 8 + 6 + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("LIST"); int32(5); ascii("INFO!"); out.write(0) // odd size + the pad byte that follows it
        ascii("data"); int32(dataBytes)
        samples.forEach { int16(it.toInt()) }

        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    /** A small quote-aware CSV reader, so the test does not trust the writer's own parser. */
    private fun readCsv(file: File): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        file.readText().split('\n').filter { it.isNotBlank() }.forEach { line ->
            val cells = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val character = line[index]
                when {
                    character == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                        cell.append('"'); index++
                    }
                    character == '"' -> quoted = !quoted
                    character == ',' && !quoted -> {
                        cells += cell.toString(); cell.setLength(0)
                    }
                    else -> cell.append(character)
                }
                index++
            }
            cells += cell.toString()
            rows += cells
        }
        return rows
    }
}
