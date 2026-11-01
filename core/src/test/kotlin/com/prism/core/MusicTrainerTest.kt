package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.MusicTrainer
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The music model: instruments and singing timbres learned from audio, then composed.
 *
 * The clips are small synthesised chords, so nothing here claims a good song. What it pins is that
 * the trainer reads a dataset's clips, learns a timbre codebook and a next-timbre predictor, writes
 * both beside the model, loads them back, and generates finite, non-silent audio for a prompt.
 */
class MusicTrainerTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-music-trainer-${System.nanoTime()}",
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

    /** A short chord with a moving bass note: enough spectrum for a codebook to have something to cluster. */
    private fun chord(root: Double, seconds: Double = 1.2, rate: Int = 22_050): FloatArray {
        val out = FloatArray((rate * seconds).toInt())
        for (index in out.indices) {
            val time = index.toDouble() / rate
            val envelope = sin(PI * index / out.size).coerceAtLeast(0.0)
            out[index] = (
                0.24 * sin(2 * PI * root * time) +
                    0.16 * sin(2 * PI * root * 1.25 * time) +
                    0.12 * sin(2 * PI * root * 1.5 * time) +
                    0.06 * sin(2 * PI * root * 2.0 * time)
                ).toFloat() * envelope.toFloat()
        }
        return out
    }

    @Test
    fun `a music model trains on clips and composes a prompt`() {
        val model = ModelCraft.create(
            "Music smoke",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.MUSIC.recipeValue
        model.params["epochs"] = "2"
        model.params["sample_rate"] = "22050"
        model.params["instruments"] = "piano, strings"
        model.params["singing_voices"] = "1"
        model.params["genre"] = "ambient"
        model.params["tempo_bpm"] = "84"
        model.params["key"] = "A minor"
        model.params["music_temperature"] = "0.9"
        model.params["music_top_k"] = "20"

        val directory = ModelCraft.Store.datasetDir(model.id)
        directory.mkdirs()
        val roots = listOf(220.0, 261.63, 293.66, 329.63)
        for ((index, root) in roots.withIndex()) {
            File(directory, "chord-$index.wav")
                .writeBytes(SpeechMeshWire.wav(chord(root), 22_050))
        }

        val result = assertNotNull(
            MusicTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "a music model with decodable clips must train",
        )
        assertTrue(result.summary.contains("learned timbres"), result.summary)
        assertTrue(File(directory, MusicTrainer.METADATA_FILE).isFile, "no metadata was written")
        assertTrue(File(directory, MusicTrainer.WEIGHTS_FILE).isFile, "no weights were written")

        val metadata = JSONObject(File(directory, MusicTrainer.METADATA_FILE).readText())
        assertEquals("music-model", metadata.optString("kind"))
        assertEquals(MusicTrainer.CODES, metadata.optInt("codes"))
        assertTrue(metadata.optInt("frames") >= MusicTrainer.CODES * 2)

        val loaded = assertNotNull(MusicTrainer.load(directory), "the artifact did not load back")
        val audio = loaded.generate("warm ambient strings with a soft female voice", seconds = 2, seed = 7L)
        assertEquals(loaded.sampleRate * 2, audio.size, "the clip is not the requested length")
        assertTrue(audio.all { it.isFinite() }, "generation produced a non-number")
        val peak = audio.maxOf { kotlin.math.abs(it) }
        assertTrue(peak > 0.01f, "generation produced silence")
        assertTrue(peak <= 1.0f, "generation clipped past full scale")
    }
}
