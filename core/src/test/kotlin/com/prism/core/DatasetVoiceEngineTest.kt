package com.prism.core

import com.prism.launcher.modelcraft.DatasetVoiceEngine
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.VoicePack
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The measured profile is the part a default voice cannot provide; it has to survive a reload. */
class DatasetVoiceEngineTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-voice-engine-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
    }

    @AfterTest
    fun cleanUp() {
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun tone(rate: Int = 22_050, f0: Double = 180.0, seconds: Double = 2.0): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var value = 0.0
            var harmonic = 1
            while (f0 * harmonic < 6_000.0) {
                value += sin(2.0 * PI * f0 * harmonic * t) / harmonic
                harmonic++
            }
            (0.12 * value).toFloat()
        }
    }

    @Test
    fun `engine measures a voice and reloads it`() {
        val model = ModelCraft.create(
            "Engine probe",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        model.params[ModelCraft.KEY_VOICE_COUNT] = "1"

        val directory = VoicePack.directory(model).apply { mkdirs() }
        val wav = File(directory, "iris.wav")
        wav.writeBytes(SpeechMeshWire.wav(tone(), 22_050))

        val voice = VoicePack.Voice(
            name = "Iris",
            gender = "female",
            language = "en",
            clip = "sample_12.wav",
            file = "iris.wav",
            seconds = 2.0,
            sampleRate = 22_050,
        )
        // The dataset engine is the default. Pocket TTS is an explicit choice, not something a
        // model falls back to just because the artifact does not mention a switch.
        assertTrue(ModelCraft.usesBuiltInVoiceEngine(model))
        assertFalse(ModelCraft.allowsPocketFallback(model))

        val engine = DatasetVoiceEngine.build(model, listOf(voice), allowPocket = false)
        assertNotNull(engine)
        assertEquals(1, engine.voices.size)
        val profile = engine.voices.single()
        assertEquals("Iris", profile.name)
        assertTrue(profile.pitchHz in 160.0..200.0, "measured pitch ${profile.pitchHz}")
        assertTrue(profile.centroidHz > 500.0, "measured centroid ${profile.centroidHz}")
        assertTrue(profile.air > 0.0, "the synthetic harmonic stack should have some upper band")

        val loaded = DatasetVoiceEngine.load(model)
        assertNotNull(loaded)
        assertEquals(1, loaded.voices.size)
        assertTrue(loaded.voices.single().pitchHz in 160.0..200.0)
        assertTrue(File(directory, DatasetVoiceEngine.FILE).isFile)

        // The test page's voice buttons select by speaker name or WAV name, so both must resolve
        // to the same measured profile rather than the first language-and-gender match.
        assertEquals("Iris", loaded.voiceNamed("Iris")?.name)
        assertEquals("Iris", loaded.voiceNamed("iris.wav")?.name)
        assertNull(loaded.voiceNamed("not a voice"))

        // The switch in the model is authoritative: flipping it after the artifact was written
        // must change what the engine is allowed to do, not be overruled by stale JSON.
        ModelCraft.setAllowsPocketFallback(model, true)
        assertTrue(DatasetVoiceEngine.load(model)!!.allowPocket)
        ModelCraft.setVoiceEngine(model, ModelCraft.VOICE_ENGINE_POCKET)
        assertFalse(ModelCraft.usesBuiltInVoiceEngine(model))
    }

    @Test
    fun `engine without a readable recording is not written`() {
        val model = ModelCraft.create(
            "Engine probe",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        val voice = VoicePack.Voice(
            name = "Missing",
            gender = "female",
            language = "en",
            clip = "missing.wav",
            file = "missing.wav",
        )
        assertNull(DatasetVoiceEngine.build(model, listOf(voice)))
        assertNull(DatasetVoiceEngine.load(model))
    }
}
