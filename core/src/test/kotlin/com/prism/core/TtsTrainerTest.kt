package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.AcousticTrainer
import com.prism.launcher.modelcraft.CraftedAudioModel
import com.prism.launcher.modelcraft.ModelCapacity
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftSpeechEngine
import com.prism.launcher.modelcraft.ModelCraftTest as CraftedTest
import com.prism.launcher.modelcraft.PrismPhonemes
import com.prism.launcher.modelcraft.TtsSignal
import com.prism.launcher.modelcraft.TtsTrainer
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The text-to-speech and cloning trainer: that it learns from transcript + language + gender and the
 * raw waveform, that the sliding-attention recipe reaches the encoder, that its artifacts load back,
 * that it emits a WAV with speech in it, and that a reference recording changes the voice.
 *
 * The audio is synthetic, so nothing here claims to sound human. What it pins is the pipeline: the
 * phonemizer emits symbols, the aligner sums to the clip's frames, the codec has real tensors, the
 * vocoder produces a finite signal, and the test page speaks typed text instead of a seed.
 */
class TtsTrainerTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-tts-test-${System.nanoTime()}",
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

    private fun model(name: String = "Speaker"): com.prism.launcher.modelcraft.CraftedModel {
        val model = ModelCraft.create(
            name,
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        model.params["language"] = "en"
        model.params["gender"] = "female"
        model.params[ModelCraft.KEY_VOICE_COUNT] = "2"
        model.params[ModelCraft.KEY_EPOCHS] = "1"
        model.params[ModelCraft.KEY_STEPS_PER_EPOCH] = "2"
        model.params[ModelCapacity.KEY_PARAMETER_COUNT] = "82000000"
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 8, 2))
        return model
    }

    private val sentences = listOf(
        "hello prism, this is a test",
        "the quick brown fox jumps",
        "voices carry through the room",
    )

    /**
     * Three synthetic vowels with words to say, and the CSV that pairs the two.
     *
     * [emptyTranscript] writes BOTH a transcript column and a description column: the transcript is
     * blank and the description holds the words, which is how a dataset exported with a description
     * column but a default empty transcript looks. Nothing but a trainer that checks both columns
     * can read it.
     */
    private fun dataset(
        model: com.prism.launcher.modelcraft.CraftedModel,
        emptyTranscript: Boolean = false,
    ): File {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val pitches = listOf(120.0, 185.0, 240.0)
        pitches.forEachIndexed { index, f0 ->
            writeVowel(File(directory, "clip${index + 1}.wav"), f0, 800.0 + 120.0 * index)
        }
        val header = if (emptyTranscript) {
            "file,transcript,description,language,gender,f0_median_hz"
        } else {
            "file,transcript,language,gender,f0_median_hz"
        }
        val rows = pitches.mapIndexed { index, f0 ->
            val gender = if (index == 1) "male" else "female"
            val words = "\"${sentences[index]}\""
            if (emptyTranscript) {
                "clip${index + 1}.wav,,$words,en,$gender,$f0"
            } else {
                "clip${index + 1}.wav,$words,en,$gender,$f0"
            }
        }.joinToString("\n")
        File(directory, "dataset_merged.csv").writeText(header + "\n" + rows + "\n")
        File(directory, "recipe.json").writeText(
            """
            {
              "epochs": 1,
              "steps_per_epoch": 2,
              "output_sample_rate": 16000,
              "language": "en",
              "gender": "female",
              "sliding_window": true,
              "sliding_window_size": 8,
              "full_attention_per_nth_layer": 2,
              "learning_rate": 0.0015,
              "grad_clip": 1.0,
              "seed": 7
            }
            """.trimIndent(),
        )
        return directory
    }

    private fun writeVowel(file: File, f0: Double, formant: Double, rate: Int = 16_000, seconds: Double = 0.7) {
        val count = (seconds * rate).toInt()
        val samples = FloatArray(count)
        for (index in 0 until count) {
            val time = index.toDouble() / rate
            var value = 0.0
            var harmonic = 1
            while (harmonic <= 16) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                val resonance = 1.0 / (1.0 + Math.pow((frequency - formant) / 320.0, 2.0))
                val second = 1.0 / (1.0 + Math.pow((frequency - formant * 1.9) / 420.0, 2.0))
                value += (resonance + 0.6 * second) * kotlin.math.sin(2.0 * Math.PI * frequency * time) / harmonic
                harmonic++
            }
            // A slow amplitude movement, so the frames are not all identical.
            val envelope = 0.6 + 0.4 * kotlin.math.sin(2.0 * Math.PI * 3.0 * time)
            samples[index] = (value * 0.18 * envelope).toFloat()
        }
        file.parentFile?.mkdirs()
        file.writeBytes(SpeechMeshWire.wav(samples, rate))
    }

    @Test
    fun `phonemes are symbols and the aligner sums to the frame count`() {
        val tokens = PrismPhonemes.phonemize("Hello, Prism 2026", "en")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains(PrismPhonemes.STRESS), "no stress was inserted: $tokens")
        val aligned = TtsTrainer.align(tokens, 120)
        assertEquals(120, aligned.sum(), "the alignment must cover every frame")
        assertTrue(aligned.all { it >= 1 }, "a phoneme was given no frames")
    }

    @Test
    fun `a dataset with transcripts trains a speech model that loads, speaks and clones`() {
        val model = model()
        val directory = dataset(model)
        val trained = assertNotNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "no speech model was trained from a dataset with transcripts",
        )

        assertTrue(trained.net.parameterCount() > 10_000, "the network is too small to be one")
        assertEquals(8, trained.net.config.window, "the recipe's window did not reach the encoder")
        assertFalse(trained.net.isFull(0), "the first layer should be sliding")
        assertTrue(trained.net.isFull(1), "every second layer should be full")

        val metadataFile = File(directory, TtsTrainer.METADATA_FILE)
        val weightsFile = File(directory, TtsTrainer.WEIGHTS_FILE)
        assertTrue(metadataFile.isFile, "no metadata was written")
        assertTrue(weightsFile.isFile, "no weights were written")
        val metadata = JSONObject(metadataFile.readText())
        assertEquals("prism-tts-model", metadata.optString("format"))
        assertEquals(8, metadata.optJSONArray("components")?.length(), "a component is missing")
        assertEquals(TtsSignal.BANDS, metadata.optJSONObject("codec")?.optInt("inputs"))
        assertEquals(ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue, metadata.optString("audio_task"))

        // The voice is a recording from the dataset, chosen and named at training time, and the
        // neural renderer's rate is what the artifact reports.
        val voice = metadata.optJSONObject("voice")
        // Prism's own dataset engine is the default; Pocket is now an explicit opt-in.
        assertEquals("dataset", voice?.optString("engine"))
        val voiceClip = voice?.optJSONObject("clips")?.optString("default", "").orEmpty()
        assertTrue(voiceClip.isNotBlank(), "no recording was chosen as the voice")
        assertTrue(File(directory, voiceClip).isFile, "the chosen voice $voiceClip is not there")
        assertEquals(24_000, metadata.optInt("output_rate"))

        val loaded = assertNotNull(TtsTrainer.load(directory), "the written artifacts did not load back")
        val language = loaded.languages.firstOrNull() ?: "en"
        assertEquals(voiceClip, loaded.voiceClipName(language, "female"), "the voice did not load back")
        assertEquals(24_000, loaded.outputRate)

        // The from-scratch pipeline is the diagnostic renderer, and this checks it still runs: the
        // words are the neural engine's job, which the archive probe exercises end to end.
        val speech = loaded.synthesizeAcoustic("hello, this is a test", language, "female")
        assertTrue(speech.size > 1_000, "the synthesiser produced only ${speech.size} samples")
        assertTrue(speech.all { it.isFinite() }, "the vocoder produced a non-finite sample")
        val rms = sqrt(speech.sumOf { (it * it).toDouble() } / speech.size)
        assertTrue(rms > 1e-5, "the rendered audio is silent (rms $rms)")

        // Cloning: the same text through a reference recording's speaker vector must be a different
        // signal, because the voice is the conditioning.
        val reference = assertNotNull(AudioDecoding.read(File(directory, "clip2.wav"))).samples
        val cloned = loaded.synthesizeAcoustic("hello, this is a test", language, "female", reference)
        assertTrue(cloned.size > 1_000)
        val meanDifference = speech.indices.take(minOf(speech.size, cloned.size))
            .sumOf { abs(speech[it].toDouble() - cloned[it].toDouble()) } /
            minOf(speech.size, cloned.size).coerceAtLeast(1)
        assertTrue(meanDifference > 1e-6, "the reference recording did not change the voice")

        // And it is an engine Prism can speak through, not only a file the test page opens.
        assertEquals(
            com.prism.launcher.speech.ImportedAudioEngine.Format.PRISM_TTS,
            com.prism.launcher.speech.ImportedAudioEngine.formatOf(directory.absolutePath),
        )
        val engine = assertNotNull(
            com.prism.launcher.speech.ImportedAudioEngine.openOrNull(directory.absolutePath),
            "the trained folder was not recognised as a speech engine",
        )
        assertTrue(engine is ModelCraftSpeechEngine)
        assertTrue(engine.isReady())
        assertTrue(engine.displayName.contains("Speaker"), engine.displayName)
    }

    /**
     * The page tells a person what to install instead of playing babble or silence.
     *
     * This scratch profile has no Pocket TTS files, so the renderer cannot speak; the message
     * must name where they come from. The sound itself is checked by the archive probe, which runs
     * against a profile where the engine is installed.
     */
    @Test
    fun `the model test page asks for the voice engine instead of guessing`() {
        val model = model("Reader")
        val directory = dataset(model)
        assertNotNull(TtsTrainer.train(model, File(directory, "recipe.json")))
        model.status = ModelCraft.STATUS_TRAINED

        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            CraftedTest.run(model, "prism can speak this sentence")
        }
        assertTrue(failure.message.orEmpty().contains("Voice Cloning"), failure.message)

        // A cloning build resolves its reference from the audio-model document rather than only
        // from the dataset, so the same message must come back for it -- not "no recording".
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.VOICE_CLONING.recipeValue
        CraftedAudioModel.write(
            directory,
            "Reader",
            ModelCraft.AudioType.VOICE_CLONING,
            com.prism.launcher.speech.SpeechDataset.extract(directory).rows,
        )
        val cloneFailure = kotlin.test.assertFailsWith<IllegalStateException> {
            CraftedTest.run(model, "this is the cloned voice")
        }
        assertTrue(cloneFailure.message.orEmpty().contains("Voice Cloning"), cloneFailure.message)
    }

    /**
     * Three vowels and an ordinary dataset CSV beside them, the way a published corpus ships.
     *
     * Deliberately not named `dataset_merged.csv`: going through [ModelCraft.recipeFile] runs the
     * real preparation, which measures the clips and writes the merged file itself.
     */
    private fun datasetWithCsv(model: com.prism.launcher.modelcraft.CraftedModel, textColumn: String): File {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val pitches = listOf(120.0, 185.0, 240.0)
        val sentences = listOf(
            "hello prism, this is a test",
            "the quick brown fox jumps",
            "voices carry through the room",
        )
        pitches.forEachIndexed { index, f0 ->
            writeVowel(File(directory, "clip${index + 1}.wav"), f0, 800.0 + 120.0 * index)
        }
        val header = "file,$textColumn,language,gender"
        val rows = pitches.mapIndexed { index, _ ->
            "clip${index + 1}.wav,\"${sentences[index]}\",en,${if (index == 1) "male" else "female"}"
        }.joinToString("\n")
        File(directory, "metadata.csv").writeText(header + "\n" + rows + "\n")
        return directory
    }

    /** Renames the transcript column, the way another dataset would have written it. */
    private fun renameTextColumn(directory: File, name: String) {
        val csv = File(directory, "dataset_merged.csv")
        val lines = csv.readLines().toMutableList()
        lines[0] = lines[0].replaceFirst("transcript", name)
        csv.writeText(lines.joinToString("\n") + "\n")
    }

    @Test
    fun `an empty transcript column does not hide a filled description column`() {
        val model = model("Two columns")
        val directory = dataset(model, emptyTranscript = true)

        assertNotNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "a blank transcript column hid the description column that holds the words",
        )
    }

    @Test
    fun `the test page's diagnosis names the description column and how many rows have text`() {
        val model = model("Diagnosis")
        val directory = dataset(model, emptyTranscript = true)

        val seen = TtsTrainer.inspect(directory)

        assertTrue(seen.contains("description"), "the diagnosis hid the column that holds the words: $seen")
        assertTrue(seen.contains("3 row(s) with text"), "the diagnosis miscounted the labelled rows: $seen")
    }

    @Test
    fun `a stale merged CSV with blank transcripts does not hide the dataset CSV`() {
        val model = model("Stale merge")
        val directory = dataset(model)
        // The words are in the dataset's own CSV, while old preparation left a merged file that has
        // the transcript HEADER and no cells. Choosing a file by its header alone reads the empty
        // one and reports that nothing has a transcript.
        val metadata = sentences.mapIndexed { index, sentence ->
            "clip${index + 1}.wav,\"$sentence\",en,female"
        }.joinToString("\n")
        File(directory, "metadata.csv").writeText("file,description,language,gender\n$metadata\n")
        File(directory, "dataset_merged.csv").writeText(
            "file,transcript,language\nclip1.wav,,\nclip2.wav,,\nclip3.wav,,\n",
        )

        assertNotNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "the empty merged CSV was preferred over the dataset CSV that has the words",
        )
    }

    @Test
    fun `a CSV that calls its transcript column description still trains a speech model`() {
        val model = model("Describer")
        val directory = dataset(model)
        renameTextColumn(directory, "description")

        assertNotNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "a description column was not read as the transcript",
        )
    }

    @Test
    fun `an audio run reads a description column and reports the speech model it trained`() {
        val model = model("Summarised")
        datasetWithCsv(model, "description")

        val result = assertNotNull(
            AcousticTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "the audio run did not train from a dataset whose transcript column is called description",
        )
        assertTrue(
            result.summary.contains("WAV synthesis ready"),
            "the run trained a speech model but its summary did not say so: ${result.summary}",
        )
        assertFalse(
            result.summary.contains("no text-to-speech model"),
            "the run still reported no transcripts: ${result.summary}",
        )
    }

    @Test
    fun `an unrecognised prose column is still read as the transcript`() {
        val model = model("Unlabelled")
        val directory = dataset(model)
        renameTextColumn(directory, "spoken_words")

        assertNotNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "the only sentence-shaped column in the CSV was not read as the transcript",
        )
    }

    @Test
    fun `a silent frame does not make the speaker vector or the training loss NaN`() {
        // A frame with no energy in either tilt band used to measure 0/0. One such frame made the
        // tilt NaN, the speaker vector NaN, and every TTS training step NaN -- which the trainer
        // then reported as "no model was saved". Digital silence is the shortest way to hit it.
        val rate = 16_000
        val silence = FloatArray(rate)
        val config = TtsSignal.Config(rate)
        val frames = TtsSignal.analyse(silence, config)
        assertTrue(frames.isNotEmpty(), "no frames were analysed")
        assertTrue(frames.all { it.tilt.isFinite() }, "a silent frame had no finite tilt")
        val speaker = TtsSignal.speakerVector(silence, config)
        assertTrue(speaker.all { it.isFinite() }, "the speaker vector contains a non-number")
    }

    @Test
    fun `a dataset with no transcripts trains no speech model`() {
        val model = model("Silent")
        val directory = ModelCraft.Store.datasetDir(model.id)
        writeVowel(File(directory, "one.wav"), 140.0, 800.0)
        writeVowel(File(directory, "two.wav"), 200.0, 1_000.0)
        assertNull(
            TtsTrainer.train(model, File(directory, "recipe.json")),
            "a dataset with no text must not claim a speech model",
        )
    }
}
