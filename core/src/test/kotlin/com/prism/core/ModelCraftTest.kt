package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ModelCraft's shared half: the shapes each kind can be built as, the datasets it accepts, and what
 * training writes.
 *
 * The pages on either platform are a tab row and some fields; every decision a user can be told is
 * happening -- a CSV read as one row per example, media that is not there, frames broken out of a
 * video, a status that says whether a trainer ran -- happens here.
 */
class ModelCraftTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelcraft-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
        ModelCraft.frameExtractor = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        ModelCraft.frameExtractor = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun scratchFile(name: String, body: String): File =
        File(scratch, name).apply { writeText(body) }

    private fun awaitRun(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ModelCraft.state.value.running) return
            Thread.sleep(20)
        }
        error("the run never finished: ${ModelCraft.state.value}")
    }

    // ── The shapes ─────────────────────────────────────────────────────────

    @Test
    fun `the five architectures are offered on every tab`() {
        val architectures = ModelCraft.architectures
        assertEquals(5, architectures.size, "expected exactly the five the creator asked for")
        listOf(
            "transformer",
            "diffuser",
            "byte latent transformer",
            "encoder only",
            "encoder and decoder",
        ).forEach { wanted ->
            assertTrue(
                architectures.any { it.lowercase().contains(wanted) },
                "no '$wanted' among $architectures",
            )
        }
    }

    @Test
    fun `every kind has its own families, each with a line of what it is for`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val families = ModelCraft.archTypes.getValue(kind)
            assertTrue(families.size >= 8, "${kind.label} offers only ${families.size} families")
            families.forEach { family ->
                assertTrue(family.name.isNotBlank(), "${kind.label} has an unnamed family")
                assertTrue(family.note.isNotBlank(), "${family.name} has no note")
            }
            assertEquals(
                families.size,
                families.map { it.name }.distinct().size,
                "${kind.label} repeats a family name",
            )
        }
    }

    @Test
    fun `every kind carries at least twenty training fields, with distinct keys`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val fields = ModelCraft.fields.getValue(kind)
            assertTrue(fields.size >= 20, "${kind.label} has only ${fields.size} fields")
            assertEquals(fields.size, fields.map { it.key }.distinct().size, "${kind.label} repeats a key")
            fields.forEach { field ->
                assertTrue(field.label.isNotBlank(), "${field.key} has no label")
            }
        }
    }

    @Test
    fun `only the kinds a CSV points at say they take one, and only the interpolator breaks out frames`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val spec = ModelCraft.datasets.getValue(kind)
            assertTrue(spec.help.isNotBlank(), "${kind.label} does not say what to import")
            when (kind) {
                ModelCraft.Kind.TEXT -> {
                    assertFalse(spec.csv, "text datasets are the files themselves")
                    assertFalse(spec.frames)
                }
                ModelCraft.Kind.INTERPOLATION -> {
                    assertTrue(spec.csv)
                    assertTrue(spec.frames, "the interpolator trains on frames, not clips")
                }
                else -> assertTrue(spec.csv, "${kind.label} datasets name their media from a CSV")
            }
        }
    }

    // ── Datasets ───────────────────────────────────────────────────────────

    @Test
    fun `a quoted comma stays inside its cell`() {
        val csv = scratchFile(
            "photos.csv",
            "path,caption\n\"a,b.png\",\"a cat, on a mat\"\nplain.png,a dog\n",
        )

        val rows = ModelCraft.parseCsv(csv)

        assertEquals(2, rows.size)
        assertEquals("a,b.png", rows[0]["path"])
        assertEquals("a cat, on a mat", rows[0]["caption"])
        assertEquals("plain.png", rows[1]["path"])
    }

    @Test
    fun `importing copies the files, and preparing counts the rows and the media that is missing`() {
        val model = ModelCraft.create("Photo model", ModelCraft.Kind.IMAGE, ModelCraft.architectures[1], "Latent diffusion (U-Net)")
        val csv = scratchFile("photos.csv", "path,caption\nhere.png,a dog\ngone.png,a cat\n")

        val added = ModelCraft.attachDataset(model, listOf(csv))

        assertEquals(1, added)
        // The page trains on this list, so an import that copied files without recording them would
        // leave a model that cannot be trained. Entries are the copies' full paths: the file's home
        // from the import onward is the model's own folder, wherever it came from.
        assertTrue(
            model.dataset.any { it.replace('\\', '/').endsWith("/dataset/photos.csv") },
            "the import is not in the model's dataset: ${model.dataset}",
        )

        // One of the two referenced photos is put next to the CSV, the other deliberately is not.
        File(ModelCraft.Store.datasetDir(model.id), "here.png").writeBytes(byteArrayOf(1, 2, 3))

        val summary = ModelCraft.prepare(model)

        assertTrue(summary.contains("2 CSV rows"), "expected two rows in '$summary'")
        assertTrue(summary.contains("not found"), "expected the absent photo to be named in '$summary'")
        assertEquals(summary, model.datasetSummary, "the summary is what the page shows")
    }

    @Test
    fun `a CSV's references are found whatever platform wrote their separators`() {
        val media = mapOf(
            ModelCraft.Kind.IMAGE to "still image.png",
            ModelCraft.Kind.AUDIO to "take one.wav",
            ModelCraft.Kind.VIDEO to "clip one.mp4",
            ModelCraft.Kind.INTERPOLATION to "clip two.mp4",
            ModelCraft.Kind.GAME to "mesh one.glb",
        )
        for ((kind, fileName) in media) {
            val model = ModelCraft.create(
                "References ${kind.label}", kind, ModelCraft.architectures.first(),
                ModelCraft.archTypes.getValue(kind).first().name,
            )
            val source = File(scratch, "source-${kind.name}").apply { mkdirs() }
            File(source, "clips").apply { mkdirs() }
            File(source, "clips/$fileName").writeBytes(byteArrayOf(1, 2, 3, 4))
            // A Windows-separated path into a subfolder, with a space in the file name: both were
            // enough to make the import copy the wrong name and the preparer report a missing file.
            val csv = File(source, "manifest-${kind.name}.csv").apply {
                writeText("path,caption\nclips\\$fileName,a row for ${kind.label}\n")
            }

            val added = ModelCraft.attachDataset(model, listOf(csv))

            assertEquals(2, added, "${kind.label} did not import the CSV and the file it names")
            assertTrue(
                File(ModelCraft.Store.datasetDir(model.id), fileName).isFile,
                "${kind.label} stored the file under a name the CSV no longer matches",
            )
            val summary = ModelCraft.prepare(model)
            assertFalse(
                summary.contains("not found"),
                "${kind.label} reported a file it had just imported: $summary",
            )
            assertTrue(
                ModelCraft.Store.manifestFile(model.id).readText().contains("\"media_found\":true"),
                "${kind.label} marked the row missing in its manifest",
            )
        }
    }

    @Test
    fun `a text CSV's referenced chapters are imported and read`() {
        val model = ModelCraft.create(
            "Chapters", ModelCraft.Kind.TEXT, ModelCraft.architectures.first(), "Decoder-only causal transformer",
        )
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 1, stepsPerEpoch = 1))
        val source = File(scratch, "chapters-source").apply { mkdirs() }
        File(source, "chapters").apply { mkdirs() }
        val sentence = "the cat sat on the warm mat and watched the rain. "
        File(source, "chapters/chapter one.txt").writeText(sentence.repeat(40))
        val csv = File(source, "chapters.csv").apply {
            writeText("file,language\nchapters\\chapter one.txt,en\n")
        }

        val added = ModelCraft.attachDataset(model, listOf(csv))

        assertEquals(2, added, "the text file named by the CSV was not imported")
        assertTrue(
            File(ModelCraft.Store.datasetDir(model.id), "chapter one.txt").isFile,
            "the chapter was stored under a name the CSV no longer matches",
        )
        assertNotNull(
            com.prism.launcher.modelcraft.TextTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "the trainer read the manifest instead of the chapter it names",
        )
    }

    @Test
    fun `a manifest still finds files imported under an older sanitized name`() {
        val model = ModelCraft.create(
            "Old import", ModelCraft.Kind.AUDIO, ModelCraft.architectures.first(), "VITS",
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        File(directory, "take_1.wav").writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "older.csv").writeText("path,transcript\ntake 1.wav,hello\n")

        val summary = ModelCraft.prepare(model)

        assertFalse(summary.contains("not found"), "an earlier import's renamed file was lost: $summary")
    }

    @Test
    fun `frames are broken out of the video for the kinds whose trainer reads frames`() {
        var extracted = 0
        ModelCraft.frameExtractor = { _, _, _, _ ->
            extracted++
            4
        }

        val interpolator = ModelCraft.create("Inbetweener", ModelCraft.Kind.INTERPOLATION, ModelCraft.architectures[0], "Flow-guided learned synthesis")
        ModelCraft.attachDataset(interpolator, listOf(scratchFile("clip.mp4", "not really a video")))
        val interpolatorSummary = ModelCraft.prepare(interpolator)

        assertTrue(interpolatorSummary.contains("4 frames"), "expected frames in '$interpolatorSummary'")

        // The video model reads frames too: its trainer predicts the next frame from the ones before
        // it, and a container decoder is not what runs on a phone.
        extracted = 0
        val video = ModelCraft.create("Video model", ModelCraft.Kind.VIDEO, ModelCraft.architectures[0], "Latent video diffusion (VideoLDM)")
        ModelCraft.attachDataset(video, listOf(scratchFile("clip2.mp4", "not really a video")))
        val videoSummary = ModelCraft.prepare(video)

        assertEquals(1, extracted, "a video model's clips have to become frames")
        assertTrue(videoSummary.contains("4 frames"), "expected frames in '$videoSummary'")

        // And a kind whose trainer reads something else does not pay for the extraction.
        extracted = 0
        val words = ModelCraft.create("Story model", ModelCraft.Kind.TEXT, ModelCraft.architectures[0], "Decoder-only causal transformer")
        ModelCraft.attachDataset(words, listOf(scratchFile("story.mp4", "not really a video")))
        assertFalse(ModelCraft.prepare(words).contains("frames"), "a text model broke a video into frames")
        assertEquals(0, extracted, "a text dataset called the frame extractor")
    }

    // ── Training ───────────────────────────────────────────────────────────

    @Test
    fun `the recipe carries every field, the family and the manifest's path`() {
        val model = ModelCraft.create("Story model", ModelCraft.Kind.TEXT, ModelCraft.architectures[0], "Decoder-only causal transformer")
        ModelCraft.attachDataset(model, listOf(scratchFile("stories.txt", "Once upon a time.")))
        model.params["epochs"] = "7"
        model.params["max_seq_length"] = "2048"

        val recipe = ModelCraft.recipeFile(model).readText()

        // The schedule is written as numbers, not as the strings the form held: a trainer's loop
        // should not have to parse a text box.
        assertTrue(recipe.contains("\"epochs\":7"), "the typed schedule is not in $recipe")
        assertTrue(recipe.contains("\"max_seq_length\":\"2048\""), "the length field is not in $recipe")
        assertTrue(recipe.contains("\"kind\":\"TEXT\""), "the kind is not in $recipe")
        assertTrue(recipe.contains("Decoder-only causal transformer"), "the family is not in $recipe")
        assertTrue(recipe.contains("dataset.json"), "the manifest is not named in $recipe")
        assertEquals(ModelCraft.Store.recipeFile(model.id).absolutePath, model.recipePath)
    }

    @Test
    fun `every kind writes the schedule boxes into its recipe, as numbers`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val model = ModelCraft.create(
                "Scheduled ${kind.label}", kind, ModelCraft.architectures.first(),
                ModelCraft.archTypes.getValue(kind).first().name,
            )
            ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 4, stepsPerEpoch = 7))
            ModelCraft.Store.save(model)

            val reloaded = ModelCraft.models().first { it.id == model.id }
            assertEquals(
                ModelCraft.Schedule(4, 7), ModelCraft.schedule(reloaded),
                "${kind.label}'s schedule did not survive the store",
            )

            val recipe = JSONObject(ModelCraft.recipeFile(model).readText())
            assertEquals(4, recipe.optInt(ModelCraft.KEY_EPOCHS), "${kind.label} wrote no epochs count")
            assertEquals(7, recipe.optInt(ModelCraft.KEY_STEPS_PER_EPOCH), "${kind.label} wrote no step count")
            val schedule = assertNotNull(recipe.optJSONObject("schedule"), "${kind.label} wrote no schedule")
            assertEquals(4, schedule.optInt("epochs"))
            assertEquals(7, schedule.optInt("steps_per_epoch"))
            assertEquals(28, schedule.optInt("planned_steps"), "${kind.label} miscounted its planned steps")
        }
    }

    @Test
    fun `training with nothing imported says why instead of starting`() {
        val model = ModelCraft.create("Empty", ModelCraft.Kind.TEXT, ModelCraft.architectures[0], "RWKV")

        assertFalse(ModelCraft.train(model))
        assertNotNull(ModelCraft.state.value.error)
        assertFalse(ModelCraft.state.value.running)
    }

    @Test
    fun `a run that finishes marks the model trained and leaves the recipe on disk`() {
        ModelCraft.runner = { _, _ -> "trained" }
        val model = ModelCraft.create("Tiny", ModelCraft.Kind.TEXT, ModelCraft.architectures[0], "RWKV")
        ModelCraft.attachDataset(model, listOf(scratchFile("tiny.txt", "hello")))

        assertTrue(ModelCraft.train(model), "the run should have started")
        awaitRun()

        assertEquals(ModelCraft.STATUS_TRAINED, model.status, "state was ${ModelCraft.state.value}")
        assertTrue(ModelCraft.state.value.complete)
        assertTrue(File(model.recipePath).isFile, "the recipe has to survive the run")
    }

    @Test
    fun `without a trainer for that architecture the recipe is still written and said so`() {
        ModelCraft.runner = { _, _ -> null }
        val model = ModelCraft.create("No engine", ModelCraft.Kind.VIDEO, ModelCraft.architectures[0], "Video GAN")
        ModelCraft.attachDataset(model, listOf(scratchFile("shot.mp4", "not really a video")))

        ModelCraft.train(model)
        awaitRun()

        // Not a failure: the recipe is the deliverable, and "no trainer here" is a different thing
        // from "the run did not work".
        assertEquals(ModelCraft.STATUS_READY, model.status)
        assertTrue(File(model.recipePath).isFile)
    }

    // ── Precision ──────────────────────────────────────────────────────────

    @Test
    fun `every kind offers the same twelve precisions`() {
        // The order is the dropdown's order, so this is also the assertion that the list is the
        // quantiser's own rather than a second hand-typed copy that can drift from it.
        val expected = listOf(
            "Q2_K", "Q3_K_S", "Q4_0", "Q4_K_S", "Q4_K_M", "Q5_K_M", "Q6_K", "Q8_0",
            "BQ", "TQ", "QQ", "5QQ",
        )
        assertEquals(expected, ModelCraft.quantizations)
        for (kind in ModelCraft.Kind.entries) {
            val field = ModelCraft.fields.getValue(kind)
                .firstOrNull { it.key == ModelCraft.KEY_QUANTIZATION }
            assertNotNull(field, "$kind has no model-quantization field")
            assertEquals(expected, field.choices, "$kind offers a different precision list")
            assertEquals(ModelCraft.DEFAULT_QUANTIZATION, field.default, "$kind defaults differently")
        }
    }

    @Test
    fun `a precision this build cannot write is refused before the run`() {
        val model = ModelCraft.create(
            "Precise", ModelCraft.Kind.TEXT, ModelCraft.architectures[0], "RWKV"
        )
        model.params[ModelCraft.KEY_QUANTIZATION] = "Q9_K"
        assertNotNull(ModelCraft.validationError(model), "an unknown level must not reach a trainer")

        ModelCraft.setQuantization(model, "5qq")
        assertEquals("5QQ", model.params[ModelCraft.KEY_QUANTIZATION], "names are case-insensitive")
        assertEquals(null, ModelCraft.validationError(model))
    }

    @Test
    fun `the chosen precision reaches the recipe`() {
        ModelCraft.runner = { _, _ -> null }
        val model = ModelCraft.create(
            "Recipe precision", ModelCraft.Kind.VIDEO, ModelCraft.architectures[0], "Video GAN"
        )
        ModelCraft.attachDataset(model, listOf(scratchFile("precise.mp4", "not really a video")))
        ModelCraft.setQuantization(model, "Q4_K_M")

        ModelCraft.train(model)
        awaitRun()

        val recipe = JSONObject(File(model.recipePath).readText())
        assertEquals("Q4_K_M", recipe.optString(ModelCraft.KEY_QUANTIZATION))
        assertEquals(4.85, recipe.optDouble("quantization_bits_per_weight"), 0.001)
    }

    @Test
    fun `deleting a model takes its dataset folder with it`() {
        val model = ModelCraft.create("Doomed", ModelCraft.Kind.AUDIO, ModelCraft.architectures[0], "VITS")
        ModelCraft.attachDataset(model, listOf(scratchFile("voice.csv", "path,transcript\n")))

        // Asked for by path before the delete, so the assertion is about the folder going away
        // rather than about the accessor making a new one.
        assertTrue(ModelCraft.Store.modelDir(model.id).isDirectory)
        val folder = ModelCraft.Store.modelDir(model.id)
        ModelCraft.delete(model)

        assertFalse(folder.exists(), "the model's folder outlived the model")
        assertTrue(ModelCraft.models().none { it.id == model.id }, "the model is still in the index")
    }
}
