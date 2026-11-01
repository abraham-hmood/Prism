package com.prism.launcher.speech

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.Dictation
import com.prism.launcher.messaging.ImageGenerator
import com.prism.launcher.messaging.TranscriptionEngine
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The language column's wiring: a clip is transcribed by the installed dictation engine and the
 * transcript's language is what the dataset records.
 *
 * The engine here is a stand-in that answers with a fixed sentence, because what is under test is the
 * seam — that installing the transcriber makes [LanguageId.detect] use it, that the audio reaches it
 * as 16 kHz PCM, and that no engine means an empty cell rather than a guess.
 */
class LanguageTranscriberTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-language-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        Dictation.reset()
        LanguageId.transcriber = null
    }

    @AfterTest
    fun cleanUp() {
        LanguageId.transcriber = null
        Dictation.reset()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    /** Two seconds at 8 kHz, so the transcriber's own resampling has something to do. */
    private fun clip(rate: Int = 8_000): SpeechClip {
        val samples = FloatArray(rate * 2) { index ->
            (0.3 * StrictMath.sin(2 * Math.PI * 180.0 * index / rate)).toFloat()
        }
        return SpeechClip(samples, rate)
    }

    private class FakeEngine(
        private val sentence: String,
    ) : TranscriptionEngine {
        var rate: Int = 0
        var language: String? = "unset"

        override val id = "fake-whisper"
        override val label = "Fake whisper"
        override val description = "Answers with a fixed sentence, for the language test."

        override fun availability(): ImageGenerator.Availability = ImageGenerator.Availability.Ready

        override fun transcribe(
            pcm16: ByteArray,
            sampleRate: Int,
            language: String?,
            onStage: ((String) -> Unit)?,
        ): String? {
            this.rate = sampleRate
            this.language = language
            return sentence
        }
    }

    @Test
    fun `installing a dictation engine makes the language column come from its transcript`() {
        val engine = FakeEngine("No sé qué decir, pero esta frase es claramente española y no otra cosa.")
        Dictation.register(engine)

        assertTrue(LanguageId.installFromDictation(), "the registered engine must be picked up")

        val detected = LanguageId.detect(clip())

        assertEquals("es", detected.code, "the Spanish transcript was read as '${detected.code}'")
        assertEquals(LanguageId.SAMPLE_RATE, engine.rate, "the clip must reach the engine at whisper's rate")
        assertEquals(null, engine.language, "no language hint: detecting it is the measurement")
    }

    @Test
    fun `with no engine the language cell stays empty rather than guessed`() {
        assertTrue(!LanguageId.installFromDictation(), "no engine was registered")

        val detected = LanguageId.detect(clip())

        // The transcriber is installed either way -- it resolves the engine per clip, so a whisper
        // model chosen later is picked up without a restart -- and with nothing to resolve it answers
        // nothing. An empty cell teaches a model nothing, which is recoverable; a guess does not.
        assertEquals(LanguageId.UNKNOWN, detected.code)
        assertEquals("no transcription", detected.source)
    }

    @Test
    fun `a clip too short to hold a sentence is not sent to the engine`() {
        val engine = FakeEngine("This would be English if it were asked.")
        Dictation.register(engine)
        LanguageId.installFromDictation()

        val detected = LanguageId.detect(SpeechClip(FloatArray(4_000), 16_000))

        assertEquals(LanguageId.UNKNOWN, detected.code)
        assertTrue(detected.source.contains("shorter"), "reported as '${detected.source}'")
        assertEquals(0, engine.rate, "a sub-second clip must not be transcribed")
    }

    @Test
    fun `the clip's float samples become 16-bit PCM at full scale without wrapping`() {
        val pcm = SpeechClip(floatArrayOf(1.0f, -1.0f, 0.0f, 1.5f), 16_000).pcm16()

        assertEquals(8, pcm.size)
        // 32767, -32767, 0, clamped to 32767: a sample past full scale must not flip sign.
        assertEquals(0xFF, pcm[0].toInt() and 0xFF)
        assertEquals(0x7F, pcm[1].toInt() and 0xFF)
        assertEquals(0x01, pcm[2].toInt() and 0xFF)
        assertEquals(0x80, pcm[3].toInt() and 0xFF)
        assertEquals(0, pcm[4].toInt())
        assertEquals(0, pcm[5].toInt())
        assertEquals(0xFF, pcm[6].toInt() and 0xFF)
        assertEquals(0x7F, pcm[7].toInt() and 0xFF)
    }
}
