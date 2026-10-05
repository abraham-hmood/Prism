package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE 36's portable half: the WAV header, the level meter, and engine selection.
 *
 * THE HEADER IS THE PART WORTH TESTING MOST. A hosted transcription endpoint identifies the audio format
 * from the container, so a header with a wrong field is not a parse error at the boundary — it is a 400
 * from the server, or worse, audio decoded at the wrong rate and transcribed as gibberish. And since
 * RIFF is little-endian while `DataOutputStream` is big-endian, every multi-byte field is a place to get
 * it backwards in a way that still produces 44 plausible-looking bytes.
 *
 * The engines themselves are not tested here: whisper needs a 75 MB model and the cloud needs a key.
 * Both are verified end to end by the `dictate` console command, which is where a real transcription is
 * checked against known speech.
 */
class DictationTest {

    @BeforeTest
    fun reset() {
        Dictation.reset()
        PrismSettings.setDictationEngineId("")
    }

    @AfterTest
    fun cleanUp() {
        Dictation.reset()
        PrismSettings.setDictationEngineId("")
    }

    // ── The WAV header ─────────────────────────────────────────────────────

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or
        ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test
    fun `the header is a valid 16-bit mono RIFF WAVE`() {
        val pcm = ByteArray(1600) { (it % 251).toByte() }   // 0.05 s at 16 kHz
        val wav = Dictation.wrapAsWav(pcm, 16_000)

        assertEquals(pcm.size + 44, wav.size, "header should be exactly 44 bytes")
        assertEquals("RIFF", tag(wav, 0))
        // The RIFF size counts everything AFTER this field, which is 36 plus the payload -- not the
        // whole file and not the payload alone.
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVE", tag(wav, 8))
        assertEquals("fmt ", tag(wav, 12))
        assertEquals(16, le32(wav, 16), "PCM fmt chunks are 16 bytes")
        assertEquals(1, le16(wav, 20), "format 1 is uncompressed PCM")
        assertEquals(1, le16(wav, 22), "mono")
        assertEquals(16_000, le32(wav, 24), "sample rate")
        // Byte rate and block align are derived and are the two fields most often wrong: a decoder
        // that trusts them plays the audio at the wrong speed rather than failing.
        assertEquals(16_000 * 2, le32(wav, 28), "byte rate = rate * channels * bytesPerSample")
        assertEquals(2, le16(wav, 32), "block align = channels * bytesPerSample")
        assertEquals(16, le16(wav, 34), "bits per sample")
        assertEquals("data", tag(wav, 36))
        assertEquals(pcm.size, le32(wav, 40))
    }

    @Test
    fun `the payload is copied through byte for byte`() {
        // If the samples were re-encoded or byte-swapped on the way into the container, the audio would
        // still decode -- as noise.
        val pcm = ByteArray(512) { (it * 7 % 256 - 128).toByte() }
        val wav = Dictation.wrapAsWav(pcm, 16_000)
        assertTrue(pcm.contentEquals(wav.copyOfRange(44, wav.size)))
    }

    @Test
    fun `a different sample rate is carried through, not silently normalised`() {
        val wav = Dictation.wrapAsWav(ByteArray(100), 44_100)
        assertEquals(44_100, le32(wav, 24))
        assertEquals(44_100 * 2, le32(wav, 28))
    }

    @Test
    fun `stereo sets both the channel count and the derived fields`() {
        val wav = Dictation.wrapAsWav(ByteArray(400), 16_000, channels = 2)
        assertEquals(2, le16(wav, 22))
        assertEquals(16_000 * 2 * 2, le32(wav, 28))
        assertEquals(4, le16(wav, 32))
    }

    @Test
    fun `an empty payload still produces a well-formed header`() {
        val wav = Dictation.wrapAsWav(ByteArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(36, le32(wav, 4))
        assertEquals(0, le32(wav, 40))
    }

    // ── Level and duration ─────────────────────────────────────────────────

    @Test
    fun `the peak level reads a full-scale tone as near one and silence as zero`() {
        assertEquals(0f, Dictation.peakLevel(ByteArray(1000)), "silence should be zero")

        val loud = ByteArray(1600)
        for (i in 0 until 800) {
            val sample = (sin(i * 0.1) * Short.MAX_VALUE).toInt().toShort()
            loud[i * 2] = (sample.toInt() and 0xFF).toByte()
            loud[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val peak = Dictation.peakLevel(loud)
        assertTrue(peak > 0.9f, "a full-scale sine should peak near 1.0, was $peak")
        assertTrue(peak <= 1f, "the level must not exceed 1.0, was $peak")
    }

    @Test
    fun `the most negative sample does not report a level above one`() {
        // Short.MIN_VALUE is -32768 and the divisor is 32767's magnitude; taking abs of it overflows
        // back to itself in two's complement, so a naive implementation reports a negative level or
        // one slightly above 1.0. Either breaks a meter that assumes 0..1.
        val pcm = byteArrayOf(0x00, 0x80.toByte())   // -32768, little-endian
        val peak = Dictation.peakLevel(pcm)
        assertTrue(peak in 0f..1f, "expected 0..1, was $peak")
        assertTrue(peak > 0.9f, "it is still a full-scale sample, was $peak")
    }

    @Test
    fun `an odd or empty buffer does not throw`() {
        assertEquals(0f, Dictation.peakLevel(ByteArray(0)))
        assertEquals(0f, Dictation.peakLevel(ByteArray(1)))
    }

    @Test
    fun `duration is bytes over two over the rate`() {
        assertTrue(abs(Dictation.durationSeconds(ByteArray(32_000), 16_000) - 1f) < 1e-6f)
        assertTrue(abs(Dictation.durationSeconds(ByteArray(16_000), 16_000) - 0.5f) < 1e-6f)
        assertEquals(0f, Dictation.durationSeconds(ByteArray(0), 16_000))
    }

    // ── Engine selection ───────────────────────────────────────────────────

    private class Fake(
        override val id: String,
        private var ready: Boolean = true,
        private val answer: String? = "heard it",
    ) : TranscriptionEngine {
        override val label = id
        override val description = "fake"
        var calls = 0

        fun setReady(value: Boolean) { ready = value }

        override fun availability(): ImageGenerator.Availability =
            if (ready) ImageGenerator.Availability.Ready
            else ImageGenerator.Availability.Unavailable("not today")

        override fun transcribe(
            pcm16: ByteArray,
            sampleRate: Int,
            language: String?,
            onStage: ((String) -> Unit)?,
        ): String? {
            calls++
            return answer
        }
    }

    /** Long enough to pass the too-short guard: a second of 16 kHz audio. */
    private fun clip() = ByteArray(32_000)

    @Test
    fun `a clip shorter than a fifth of a second is refused before an engine is called`() {
        val engine = Fake("e")
        Dictation.register(engine)
        // The guard exists because every engine answers a tap with an empty string, so without it the
        // user sees dictation "working" and producing nothing -- and pays for the upload.
        val result = Dictation.transcribe(ByteArray(100))
        assertNull(result.text)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `a stale engine choice falls back rather than failing`() {
        val whisper = Fake("whisper")
        val cloud = Fake("cloud")
        Dictation.register(whisper)
        Dictation.register(cloud)
        PrismSettings.setDictationEngineId("whisper")
        assertEquals("whisper", Dictation.preferred()?.id)

        // The model file gets deleted; the user did nothing.
        whisper.setReady(false)

        assertEquals("cloud", Dictation.preferred()?.id)
        val result = Dictation.transcribe(clip())
        assertEquals("heard it", result.text)
        assertEquals("cloud", result.engine)
    }

    @Test
    fun `an engine that hears nothing is reported rather than returning blank text`() {
        Dictation.register(Fake("silent", answer = ""))
        val result = Dictation.transcribe(clip())
        assertNull(result.text, "an empty transcript is not a result")
        assertNotNull(result.error)
    }

    @Test
    fun `no engines is a result and not a crash`() {
        val result = Dictation.transcribe(clip())
        assertNull(result.text)
        assertEquals("none", result.engine)
        assertNotNull(result.error)
    }

    @Test
    fun `whitespace is trimmed off a transcript`() {
        Dictation.register(Fake("padded", answer = "  hello there  \n"))
        assertEquals("hello there", Dictation.transcribe(clip()).text)
    }

    @Test
    fun `the whisper engine names which prerequisite is missing when one is`() {
        // NOT asserted to be unavailable. Whether it is depends on the machine: the settings are shared
        // with the running app, so a developer who has pointed Prism at a model and built the native
        // library gets Ready here -- which is correct, and asserting otherwise would make this test
        // fail on exactly the machines where the feature works.
        //
        // What IS a contract regardless of environment: an unavailable whisper says which of its two
        // prerequisites is missing, because the library and the model need different actions from the
        // user and a bare "unavailable" leaves them guessing.
        val engine = WhisperCppEngine()
        when (val availability = engine.availability()) {
            is ImageGenerator.Availability.Ready -> {
                // Then the model it named must really be there -- a Ready engine that cannot load is
                // the failure this check exists to rule out.
                val path = PrismSettings.getWhisperModelPath()
                assertTrue(path.isNotBlank() && java.io.File(path).isFile,
                    "reported Ready with no model behind it: \"$path\"")
            }

            is ImageGenerator.Availability.Unavailable -> assertTrue(
                availability.reason.contains("library", ignoreCase = true) ||
                    availability.reason.contains("model", ignoreCase = true),
                "the reason should name the library or the model, was: ${availability.reason}",
            )
        }
    }

    @Test
    fun `whisper refuses audio at a rate it cannot use`() {
        // Whisper is trained at 16 kHz and this build does not resample. A clip at another rate would
        // transcribe as gibberish rather than fail, so it is refused -- and that refusal is testable
        // without a model, because it happens before the model is touched.
        val engine = WhisperCppEngine()
        assertNull(engine.transcribe(ByteArray(32_000), sampleRate = 44_100))
    }
}
