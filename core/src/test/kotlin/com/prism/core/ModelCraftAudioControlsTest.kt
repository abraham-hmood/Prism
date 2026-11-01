package com.prism.core

import com.prism.launcher.modelcraft.AcousticTrainer
import com.prism.launcher.modelcraft.CraftedAudioModel
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import com.prism.launcher.speech.LanguageId
import com.prism.launcher.speech.SpeechDataset
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
 * ModelCraft's two special controls, and the audio run behind them.
 *
 * ## Why this file exists
 *
 * The attention checkbox, its two numbers, the audio-type radio group and the required voice count
 * are the only parts of the creator that are not a field on a form -- which makes them the parts a
 * page can get subtly wrong (a number that never reaches the recipe, a required box that is only
 * required in the label). So they are pinned here, from the model record, not from either UI.
 *
 * The second half drives a real audio run end to end: an imported dataset of synthesised clips, both
 * tasks, and the artifacts each one must leave behind. "It must be able to make both types of models"
 * is a claim about files on disk, so that is what is asserted.
 */
class ModelCraftAudioControlsTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelcraft-audio-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
        LanguageId.transcriber = { "the quick brown fox" }
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        LanguageId.transcriber = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    // ── The attention checkbox ───────────────────────────────────────────────

    @Test
    fun `attention is off by default, round-trips through the record, and is carried by the recipe`() {
        val model = ModelCraft.create(
            name = "Tone", kind = ModelCraft.Kind.IMAGE,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.IMAGE).first().name,
        )
        val off = ModelCraft.attention(model)
        assertEquals(false, off.enabled)
        assertEquals(ModelCraft.DEFAULT_WINDOW, off.window)
        assertEquals(ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER, off.fullAttentionEveryNthLayer)
        assertTrue(ModelCraft.Kind.IMAGE in ModelCraft.attentionKinds)

        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 6))
        ModelCraft.Store.save(model)
        // Read back from the store rather than from the object just written to: what a page sees when
        // it reopens a model is the record on disk.
        val reloaded = ModelCraft.models().first { it.id == model.id }
        assertEquals(ModelCraft.Attention(true, 512, 6), ModelCraft.attention(reloaded))

        val recipe = ModelCraft.recipeFile(model).readText()
        assertTrue(recipe.contains("\"sliding_window\":true"), "the checkbox did not reach the recipe")
        assertTrue(recipe.contains("\"sliding_window_size\":512"))
        assertTrue(recipe.contains("\"full_attention_per_nth_layer\":6"))
        assertTrue(recipe.contains("\"interleaved\":true"), "the interleaving is not described")

        // N = 1 is full attention on every layer, and the recipe has to say so rather than claim a
        // window that is never used.
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 1))
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 1))
        assertTrue(ModelCraft.attention(model).effectivelyFullAttention)
        assertTrue(ModelCraft.recipeFile(model).readText().contains("\"interleaved\":false"))

        // A text model has the same attention section now: every kind the user builds here can be
        // shaped, text included, and the recipe carries the off state rather than omitting the keys.
        val text = ModelCraft.create(
            name = "Words", kind = ModelCraft.Kind.TEXT,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        assertTrue(ModelCraft.Kind.TEXT in ModelCraft.attentionKinds)
        assertTrue(ModelCraft.recipeFile(text).readText().contains("\"sliding_window\":false"))
    }

    // ── The audio type and the voice count ───────────────────────────────────

    @Test
    fun `a text to speech model without a voice count is refused, and one with it is not`() {
        val model = audioModel()
        // Default: voice cloning, which needs no count.
        assertEquals(ModelCraft.AudioType.VOICE_CLONING, ModelCraft.audioType(model))
        assertNull(ModelCraft.validationError(model))

        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        assertEquals(ModelCraft.AudioType.TEXT_TO_SPEECH, ModelCraft.audioType(model))
        val problem = ModelCraft.validationError(model)
        assertNotNull(problem, "text to speech must require the voice count")
        assertTrue(problem.contains("Voice types to build"))

        // A dataset, because "there is nothing to train on" is checked first and would answer a
        // different question than the one this test is asking. Imported from outside the dataset
        // folder the way a real import is -- a file already in the folder is copied onto itself.
        val csv = File(File(scratch, "imports").apply { mkdirs() }, "metadata.csv").apply {
            writeText("file,text\na.wav,hello there\n")
        }
        ModelCraft.attachDataset(model, listOf(csv))
        ModelCraft.Store.save(model)
        assertEquals(false, ModelCraft.train(model), "a run should not start without the required box")
        assertEquals(problem, ModelCraft.state.value.error)

        model.params[ModelCraft.KEY_VOICE_COUNT] = "3"
        assertEquals(3, ModelCraft.voiceCount(model))
        assertNull(ModelCraft.validationError(model))

        // A window of one token attends to nothing and is refused.
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 1, 4))
        assertNotNull(ModelCraft.validationError(model), "a one-token window is not a window")
        // A non-positive Nth reads as 1 -- every layer full attention -- which is a coherent answer
        // rather than an error, and the recipe describes it as such.
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 1024, 0))
        assertEquals(1, ModelCraft.attention(model).fullAttentionEveryNthLayer)
        assertTrue(ModelCraft.attention(model).effectivelyFullAttention)
        assertNull(ModelCraft.validationError(model))
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 1024, 4))
        assertNull(ModelCraft.validationError(model))
    }

    // ── A run, for both tasks ────────────────────────────────────────────────

    @Test
    fun `a voice cloning run writes a speaker model and a text to speech run writes a voice bank`() {
        val model = audioModel()
        // Staged outside the model's dataset folder, because that is what an import IS: ModelCraft
        // copies a picked file in, and a file already in the folder would be copied onto itself.
        val staging = File(scratch, "imports").apply { mkdirs() }
        val clips = listOf("a.wav" to 130.0, "b.wav" to 175.0, "c.wav" to 240.0)
        clips.forEach { (name, pitch) -> writeWav(File(staging, name), f0 = pitch, seconds = 2.0) }
        File(staging, "metadata.csv").writeText(
            "file,text\na.wav,The quick brown fox.\nb.wav,And the dog.\n",
        )
        val imported = ModelCraft.attachDataset(model, staging.listFiles()?.toList().orEmpty())
        assertEquals(4, imported, "nothing was imported")
        ModelCraft.Store.save(model)

        // One: the cloning model.
        assertTrue(ModelCraft.train(model), "the run was refused: ${ModelCraft.state.value.error}")
        awaitRun()
        assertNull(ModelCraft.state.value.error, "the run failed")
        val cloneFile = ModelCraft.Store.audioModelFile(model.id)
        assertTrue(cloneFile.isFile, "a cloning run left no model behind")
        val clone = cloneFile.readText()
        assertTrue(clone.contains("\"task\":\"voice-cloning\""))
        assertTrue(clone.contains("\"speaker\""))
        assertTrue(clone.contains(VocalFeatureColumns.FIRST), "the identity columns are not in the model")
        assertEquals(ModelCraft.STATUS_READY, model.status)
        assertTrue(
            model.datasetSummary.contains("voice model written"),
            "the run did not say what it made: ${model.datasetSummary}",
        )
        assertTrue(
            model.datasetSummary.contains("clips analysed") || model.datasetSummary.contains("clip analysed"),
            "the run did not say how much audio it measured: ${model.datasetSummary}",
        )
        // The merged metadata is what a trainer is pointed at, and it must exist.
        assertTrue(ModelCraft.Store.mergedCsvFile(model.id).isFile, "no merged CSV was written")
        assertTrue(ModelCraft.Store.speakerProfileFile(model.id).isFile, "no speaker profile was written")
        // A cloning model has no voice bank: the speaker IS the voice.
        assertTrue(!cloneFile.readText().contains("voice_plan"))

        // Two: the same dataset, built as a text-to-speech model with two voices.
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        model.params[ModelCraft.KEY_VOICE_COUNT] = "2"
        model.params[ModelCraft.KEY_SLIDING_WINDOW] = "true"
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 2048, 4))
        ModelCraft.Store.save(model)
        assertTrue(ModelCraft.train(model), "the run was refused: ${ModelCraft.state.value.error}")
        awaitRun()

        val tts = ModelCraft.Store.audioModelFile(model.id).readText()
        assertTrue(tts.contains("\"task\":\"text-to-speech\""))
        assertTrue(tts.contains("\"built_voices\":2"), "two voice types were asked for: $tts")
        assertTrue(tts.contains("\"voice_plan\""), "the voice bank is not in the model")
        assertNull(ModelCraft.state.value.error, "the run reported an error")
        assertTrue(ModelCraft.Store.voicesFile(model.id).isFile)

        // And the recipe a trainer reads names both halves of what the run made.
        val recipe = ModelCraft.recipeFile(model).readText()
        assertTrue(recipe.contains("\"crafted_model\""))
        assertTrue(recipe.contains("\"audio_task\":\"text-to-speech\""))
        assertTrue(recipe.contains("\"voice_types_to_build\":2"))
        assertTrue(recipe.contains(SpeechDataset.MERGED_FILE))
        assertTrue(recipe.contains("\"sliding_window\":true"))

        // The features and the merged CSV are this pipeline's own output, so a second run must not
        // treat them as part of the dataset -- or the manifest would double.
        val manifest = ModelCraft.prepare(model)
        assertTrue(
            !manifest.contains(SpeechDataset.FEATURES_FILE) && !manifest.contains(SpeechDataset.MERGED_FILE),
            "the pipeline's own CSVs were read back as dataset files: $manifest",
        )
    }

    @Test
    fun `the installed trainer turns an audio run into weights and says so`() {
        // The wiring, not the arithmetic: this is what makes the trainer reachable from the Train
        // button, and a build that forgot to install it would look exactly like a build without one.
        ModelCraft.installDefaultTrainer()
        val model = audioModel()
        model.params["epochs"] = "3"
        model.params["learning_rate"] = "0.002"
        val staging = File(scratch, "trained-imports").apply { mkdirs() }
        listOf("one.wav" to 120.0, "two.wav" to 190.0, "three.wav" to 260.0).forEach { (name, pitch) ->
            writeWav(File(staging, name), f0 = pitch, seconds = 0.8)
        }
        ModelCraft.attachDataset(model, staging.listFiles()?.toList().orEmpty())
        ModelCraft.Store.save(model)

        assertTrue(ModelCraft.train(model), "the run was refused: ${ModelCraft.state.value.error}")
        awaitRun(timeoutMs = 120_000)

        assertNull(ModelCraft.state.value.error, "the run failed")
        assertEquals(ModelCraft.STATUS_TRAINED, model.status, "the run did not report a model")
        assertTrue(model.note.contains("parameters trained"), "the note does not say what was made: ${model.note}")
        assertTrue(
            File(ModelCraft.Store.datasetDir(model.id), AcousticTrainer.METADATA_FILE).isFile,
            "the run claimed to train but wrote no acoustic model",
        )

        // And the same run for a text model makes a text model: every kind has a trainer now, and
        // the page's Train button is expected to end in weights rather than in a recipe.
        val text = ModelCraft.create(
            name = "Words", kind = ModelCraft.Kind.TEXT,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        text.params["epochs"] = "5"
        text.params["learning_rate"] = "0.002"
        val words = File(staging, "stories.txt").apply {
            writeText("Once upon a time, in a repository far away, a small model learned to speak. ".repeat(10))
        }
        ModelCraft.attachDataset(text, listOf(words))
        assertTrue(ModelCraft.train(text))
        awaitRun(timeoutMs = 120_000)
        assertEquals(ModelCraft.STATUS_TRAINED, text.status, "the text run did not train: ${text.note}")
        assertTrue(
            File(ModelCraft.Store.datasetDir(text.id), TextTrainer.METADATA_FILE).isFile,
            "the run claimed to train but wrote no text model",
        )

        // And a build whose runner has nothing for a kind still says exactly that, rather than
        // reporting a model it does not have.
        ModelCraft.runner = { _, _ -> null }
        val unmade = ModelCraft.create(
            name = "Unmade", kind = ModelCraft.Kind.TEXT,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        val more = File(staging, "elsewhere.txt").apply { writeText("hello there, this is a sentence.") }
        ModelCraft.attachDataset(unmade, listOf(more))
        assertTrue(ModelCraft.train(unmade))
        awaitRun(timeoutMs = 60_000)
        assertEquals(ModelCraft.STATUS_READY, unmade.status)
        assertTrue(
            unmade.note.contains("the local trainer produced no model"),
            "the run did not explain itself: ${unmade.note}",
        )
    }

    @Test
    fun `a dataset whose rows name URLs is downloaded before the clips are measured`() {
        val model = audioModel()
        // The clip exists only at the other end of a URL: this is what a published speech dataset
        // looks like, and the run has to fetch the audio before it can hear any of it.
        val served = File(scratch, "served.wav")
        writeWav(served, f0 = 150.0, seconds = 2.0)
        val bytes = served.readBytes()
        val urls = mutableListOf<String>()
        com.prism.launcher.datasetcraft.DatasetCraft.fetcher = { url ->
            urls += url
            bytes
        }
        val staging = File(scratch, "url-imports").apply { mkdirs() }
        val manifest = File(staging, "metadata.csv")
        manifest.writeText(
            "file,text\n" +
                "https://example.invalid/clips/take-1.wav,Hello there.\n",
        )
        try {
            ModelCraft.attachDataset(model, listOf(manifest))

            // The import is where a link-only dataset becomes a real one, so the file has to be there
            // before anything is trained.
            assertTrue(
                File(ModelCraft.Store.datasetDir(model.id), "take-1.wav").isFile,
                "the imported CSV's URL was not downloaded",
            )
            assertEquals(listOf("https://example.invalid/clips/take-1.wav"), urls)

            assertTrue(ModelCraft.train(model), "the run was refused: ${ModelCraft.state.value.error}")
            awaitRun()

            assertNull(ModelCraft.state.value.error, "the run failed")
            val merged = ModelCraft.Store.mergedCsvFile(model.id)
            assertTrue(merged.isFile, "no merged CSV was written")
            val row = ModelCraft.parseCsv(merged).firstOrNull { entry ->
                entry.values.any { it.contains("take-1.wav") }
            }
            assertNotNull(row, "the downloaded clip is not in the merged CSV: ${merged.readText()}")
            val pitch = row["f0_mean_hz"]?.toDoubleOrNull()
            assertNotNull(
                pitch,
                "the clip was never measured, so the merged row has no pitch: ${row["f0_mean_hz"]}",
            )
            assertTrue(pitch in 100.0..220.0, "the 150 Hz clip measured at $pitch Hz")
            // The run reports what it measured, and does NOT claim a download it did not have to make:
            // the import already fetched this file, so the second pass found it on disk.
            assertTrue(
                model.datasetSummary.contains("1 clip analysed"),
                "the run did not say what it measured: ${model.datasetSummary}",
            )
        } finally {
            com.prism.launcher.datasetcraft.DatasetCraft.fetcher = null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun audioModel(): CraftedModel = ModelCraft.create(
        name = "Voice 1", kind = ModelCraft.Kind.AUDIO,
        architecture = ModelCraft.architectures.first(),
        family = ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
    )

    private fun awaitRun(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ModelCraft.state.value.running) return
            Thread.sleep(20)
        }
        error("the run never finished: ${ModelCraft.state.value}")
    }

    /** One identity column, named, so a failure here does not depend on the whole column list. */
    private object VocalFeatureColumns {
        const val FIRST = "ltas_0_125_hz_db"
    }

    /** A RIFF/WAVE file with a voiced pulse train in it, at [f0]. */
    private fun writeWav(file: File, f0: Double, seconds: Double, rate: Int = 16_000) {
        val frames = (seconds * rate).toInt()
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) = out.write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))

        ascii("RIFF"); int32(36 + frames * 2); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            var value = 0.0
            var harmonic = 1
            while (harmonic <= 10) {
                val frequency = f0 * harmonic
                if (frequency > rate / 2.0) break
                value += Math.sin(2 * Math.PI * frequency * t) / harmonic
                harmonic++
            }
            val envelope = if (t > seconds * 0.4 && t < seconds * 0.5) 0.0 else 1.0
            int16((value * envelope * 6000.0).toInt().coerceIn(-32_000, 32_000))
        }
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
