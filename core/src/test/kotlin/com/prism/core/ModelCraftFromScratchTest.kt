package com.prism.core

import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftExport
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Building a model from nothing, training it elsewhere, and taking it with you.
 *
 * ## Why this file exists
 *
 * Three promises the creator makes that are only true if files say so:
 *
 *  1. **From scratch, for all five kinds.** Turning the base off has to drop the base-only fields from
 *     the recipe — a trainer handed both `base_checkpoint` and "build it yourself" has to guess — and
 *     it has to say what shape the model is created at instead.
 *  2. **Local training is optional.** With the switch off nothing on this device may run, and the
 *     status has to say that rather than report a run that did not happen.
 *  3. **A run leaves something that can rebuild the model.** Both outcomes export the model and write a
 *     Python script, and the script has to be Python: it is parsed by an interpreter here when the
 *     machine has one, rather than only searched for substrings.
 *
 * The dataset half is pinned too: the media a CSV's rows name is imported by path, which is the
 * difference between a voice dataset that trains and a folder holding one CSV.
 */
class ModelCraftFromScratchTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelcraft-scratch-${System.nanoTime()}",
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

    // ── From scratch ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every kind can be built from scratch, and its base only fields leave the recipe`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val model = model(kind)
            val baseKeys = ModelCraft.baseFields.getValue(kind)
            assertTrue(baseKeys.isNotEmpty(), "${kind.label} has no base-only fields listed")

            // Off: the base field is in the recipe and the mode says it is a fine-tune.
            assertFalse(ModelCraft.fromScratch(model), "${kind.label} starts from scratch by default")
            val baseFirst = ModelCraft.recipeFile(model).readText()
            assertTrue(
                baseFirst.contains("\"from_scratch\":false"),
                "${kind.label}'s recipe does not carry the switch: $baseFirst",
            )
            assertTrue(
                baseFirst.contains("\"${baseKeys.first()}\""),
                "${kind.label}'s recipe lost ${baseKeys.first()} while fine-tuning",
            )
            assertTrue(baseFirst.contains(ModelCraft.BASE_MODE), "${kind.label}'s recipe does not say what it is")

            // On: the base-only fields are gone, and the shape it IS created at is written down.
            ModelCraft.setFromScratch(model, true)
            ModelCraft.Store.save(model)
            val reloaded = ModelCraft.models().first { it.id == model.id }
            assertTrue(ModelCraft.fromScratch(reloaded), "the switch did not survive the store")

            val recipe = ModelCraft.recipeFile(model).readText()
            assertTrue(recipe.contains("\"from_scratch\":true"), "${kind.label}: $recipe")
            assertTrue(recipe.contains(ModelCraft.SCRATCH_MODE))
            assertTrue(recipe.contains("\"initialisation\""), "${kind.label} writes no shape to create at")
            assertTrue(recipe.contains("\"layers\""), "${kind.label}'s shape has no layer count")
            baseKeys.forEach { key ->
                assertFalse(
                    recipe.contains("\"$key\":"),
                    "${kind.label}'s from-scratch recipe still carries the base-only field '$key'",
                )
            }
            assertTrue(
                recipe.contains("\"fields_not_used\""),
                "${kind.label}'s recipe does not say which fields it left out",
            )

            // The training fields are NOT base-only: a model built from nothing still learns.
            assertTrue(recipe.contains("\"learning_rate\""), "${kind.label} dropped its learning rate")
        }
    }

    @Test
    fun `the shape a from scratch model is created at follows the form where the form says anything`() {
        val model = model(ModelCraft.Kind.TEXT)
        ModelCraft.setFromScratch(model, true)
        model.params["max_seq_length"] = "2048"
        val shape = ModelCraft.scratchConfig(model)
        assertEquals(2048, shape.optInt("context_length", 0))
        assertTrue(shape.optInt("layers", 0) > 0)
        assertTrue(shape.optString("objective", shape.optString("note", "")).isNotBlank())

        // A value nobody can parse falls back to the documented default rather than to zero.
        model.params["max_seq_length"] = "as long as it takes"
        assertEquals(1024, ModelCraft.scratchConfig(model).optInt("context_length", 0))
    }

    // ── Local training, on and off ──────────────────────────────────────────────────────────────

    @Test
    fun `with local training switched off no trainer runs and the status says so`() {
        var ran = false
        ModelCraft.runner = { _, _ ->
            ran = true
            "trained"
        }
        val model = model(ModelCraft.Kind.TEXT)
        ModelCraft.attachDataset(model, listOf(File(scratch, "stories.txt").apply { writeText("hello") }))
        ModelCraft.setTrainsLocally(model, false)
        ModelCraft.Store.save(model)

        assertTrue(ModelCraft.train(model), "the run was refused")
        awaitRun()

        assertFalse(ran, "a trainer ran with local training switched off")
        assertEquals(ModelCraft.STATUS_READY, model.status)
        assertTrue(
            model.note.contains("local training is switched off"),
            "the note does not say why nothing trained: ${model.note}",
        )

        // And with it on, the trainer is the one that runs.
        ModelCraft.setTrainsLocally(model, true)
        ModelCraft.Store.save(model)
        assertTrue(ModelCraft.train(model))
        awaitRun()
        assertTrue(ran, "the trainer did not run with local training switched on")
        assertEquals(ModelCraft.STATUS_TRAINED, model.status)
    }

    @Test
    fun `a record that has never been near the switch still trains here`() {
        val model = model(ModelCraft.Kind.TEXT)
        model.params.remove(ModelCraft.KEY_TRAIN_LOCALLY)
        assertTrue(
            ModelCraft.trainsLocally(model),
            "a model from before this switch existed has to keep training where a trainer exists",
        )
        ModelCraft.setTrainsLocally(model, false)
        assertFalse(ModelCraft.trainsLocally(model))
    }

    // ── The export ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a run exports the model and writes a Python script for every kind`() {
        val python = pythonExecutable()
        ModelCraft.Kind.entries.forEach { kind ->
            val model = model(kind)
            ModelCraft.attachDataset(model, listOf(sample(kind)))
            ModelCraft.setFromScratch(model, kind != ModelCraft.Kind.TEXT)
            ModelCraft.Store.save(model)

            assertTrue(ModelCraft.train(model), "${kind.label} was refused: ${ModelCraft.state.value.error}")
            awaitRun()

            val export = ModelCraftExport.dir(model)
            assertTrue(export.isDirectory, "${kind.label} left no export folder")
            listOf(
                ModelCraftExport.CONFIG_FILE,
                ModelCraftExport.RECORD_FILE,
                ModelCraftExport.INVENTORY_FILE,
                ModelCraftExport.scriptName(model),
            ).forEach { name ->
                assertTrue(File(export, name).isFile, "${kind.label}'s export has no $name")
            }
            // The recipe is written before the export, so it is in the folder too -- it is what a
            // trainer on another machine is pointed at.
            assertTrue(File(export, "recipe.json").isFile, "${kind.label}'s export has no recipe")
            // The dataset's own rows, so the paths in them still resolve somewhere.
            assertTrue(
                File(export, ModelCraftExport.DATASET_DIR).isDirectory,
                "${kind.label}'s export has no dataset folder",
            )

            val config = ModelCraftExport.config(model)
            assertEquals(kind.name, config.optString("kind", ""))
            assertEquals(
                ModelCraft.fromScratch(model),
                config.optBoolean("from_scratch", !ModelCraft.fromScratch(model)),
            )
            assertTrue(config.optJSONObject("initialisation") != null)

            // The script has to be Python. Parsed by an interpreter where there is one; the
            // structure below is checked either way.
            val script = File(export, ModelCraftExport.scriptName(model))
            val text = script.readText()
            assertTrue(text.startsWith("#!/usr/bin/env python3"), "${kind.label}'s script has no shebang")
            assertTrue(text.contains("def build_model(cfg):"), "${kind.label}'s script builds nothing")
            assertTrue(text.contains("def load_dataset(cfg, data_dir):"), "${kind.label}'s script reads nothing")
            assertTrue(text.contains("def train(model, data, steps"), "${kind.label}'s script trains nothing")
            assertTrue(text.contains("--print-config"), "${kind.label}'s script has no command line")
            assertTrue(
                text.contains(kind.name) || config.optString("model_type", "").isNotBlank(),
                "${kind.label}'s script does not say what it is",
            )
            if (python != null) {
                val (code, output) = run(python, "-c", PYTHON_PARSE, script.absolutePath)
                assertEquals(0, code, "${kind.label}'s generated script does not parse: $output")
                // And it RUNS: --print-config loads config.json, prints it and stops before any torch
                // import, so a script that is only structurally right is caught here rather than on
                // the machine of whoever tried it.
                val (runCode, runOutput) = run(python, script.absolutePath, "--print-config")
                assertEquals(0, runCode, "${kind.label}'s script would not run: $runOutput")
                assertTrue(
                    runOutput.contains(model.name),
                    "${kind.label}'s script printed no configuration: $runOutput",
                )
                assertTrue(
                    runOutput.contains("\"kind\": \"${kind.name}\""),
                    "${kind.label}'s script printed another kind's configuration: $runOutput",
                )
            }
        }
        if (python == null) {
            println("no Python interpreter on this machine: the generated scripts were not parsed")
        }
    }

    @Test
    fun `an export says what it could not copy rather than leaving a hole`() {
        val model = model(ModelCraft.Kind.AUDIO)
        val export = ModelCraftExport.export(model)
        val inventory = File(export, ModelCraftExport.INVENTORY_FILE).readText()
        assertTrue(inventory.contains("\"files\""), "the inventory lists nothing")
        assertTrue(inventory.contains("\"not_copied\""), "the inventory has no place to say what is missing")
        assertTrue(inventory.contains(ModelCraftExport.scriptName(model)))
        // A model with no dataset still exports -- a user who wants to train elsewhere asks for
        // exactly this, and an empty dataset is not a reason to refuse.
        assertTrue(File(export, ModelCraftExport.scriptName(model)).isFile)
    }

    // ── The dataset's own paths ─────────────────────────────────────────────────────────────────

    @Test
    fun `importing a CSV imports the files its rows name, by the paths in them`() {
        val folder = File(scratch, "voice-dataset").apply { mkdirs() }
        File(folder, "wavs").mkdirs()
        val clips = listOf("wavs/a.wav", "wavs/b.wav")
        clips.forEach { relative -> File(folder, relative).writeBytes(byteArrayOf(1, 2, 3, 4)) }
        // An absolute path in the row, the way a manifest written on a training box names its clips.
        val elsewhere = File(scratch, "outside/c.wav").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val csv = File(folder, "metadata.csv").apply {
            writeText(
                "file,text\n" +
                    "wavs/a.wav,hello there\n" +
                    "wavs/b.wav,and a second clip\n" +
                    "${elsewhere.absolutePath},from somewhere else\n" +
                    "wavs/missing.wav,this one is not there\n",
            )
        }

        val model = model(ModelCraft.Kind.AUDIO)
        val added = ModelCraft.attachDataset(model, listOf(csv))

        // The CSV itself, the two clips beside it, and the one the row names by absolute path.
        assertEquals(4, added, "the CSV's own paths were not imported: ${model.dataset}")
        val dataset = ModelCraft.Store.datasetDir(model.id)
        listOf("metadata.csv", "a.wav", "b.wav", "c.wav").forEach { name ->
            assertTrue(File(dataset, name).isFile, "$name was not copied into the dataset folder")
        }

        val summary = ModelCraft.prepare(model)
        assertTrue(summary.contains("4 CSV rows"), "expected every row to be read: $summary")
        assertTrue(
            summary.contains("1 referenced file") && summary.contains("not found"),
            "the row pointing at nothing should be the only one reported: $summary",
        )

        // Running the import a second time adds nothing: the media the CSV names is already there,
        // which is the whole point of copying it in. The one row that names a file nobody has is
        // still reported, because a second import cannot conjure it -- and a trainer that quietly
        // dropped that row would be training on three quarters of the dataset without saying so.
        val again = ModelCraft.attachDataset(model, listOf(csv))
        assertEquals(0, again, "a second import of the same CSV copied files again")
        val second = ModelCraft.prepare(model)
        assertTrue(
            second.contains("3 files") && second.contains("1 referenced file"),
            "the second pass should find the same three clips and the same hole: $second",
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun model(kind: ModelCraft.Kind): CraftedModel = ModelCraft.create(
        name = "Test ${kind.label}",
        kind = kind,
        architecture = ModelCraft.architectures.first(),
        family = ModelCraft.archTypes.getValue(kind).first().name,
    )

    /** One importable file of the right shape for [kind], so a run has a dataset to prepare. */
    private fun sample(kind: ModelCraft.Kind): File {
        val name = when (kind) {
            ModelCraft.Kind.TEXT -> "stories.txt"
            ModelCraft.Kind.AUDIO -> "voice.csv"
            ModelCraft.Kind.IMAGE -> "photos.csv"
            ModelCraft.Kind.VIDEO -> "shots.csv"
            ModelCraft.Kind.INTERPOLATION -> "clips.csv"
            ModelCraft.Kind.GAME -> "props.csv"
            ModelCraft.Kind.COMPRESSION -> "corpus.bin"
        }
        val body = when (kind) {
            ModelCraft.Kind.TEXT -> "Once upon a time, in a repository far away…\n"
            ModelCraft.Kind.AUDIO -> "file,text\na.wav,hello there\n"
            ModelCraft.Kind.IMAGE -> "path,caption\nphoto.png,a cat\n"
            ModelCraft.Kind.VIDEO -> "path,caption\nshot.mp4,a dog running\n"
            ModelCraft.Kind.INTERPOLATION -> "path,caption\nclip.mp4,a pan\n"
            ModelCraft.Kind.GAME -> "path,caption\nrock.obj,a mossy rock\n"
            ModelCraft.Kind.COMPRESSION -> "Any bytes at all are a compression dataset.\n"
        }
        return File(scratch, name).apply { writeText(body) }
    }

    private fun awaitRun(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ModelCraft.state.value.running) return
            Thread.sleep(20)
        }
        error("the run never finished: ${ModelCraft.state.value}")
    }

    /** An interpreter to parse a generated script with, or null on a machine without Python. */
    private fun pythonExecutable(): String? {
        val candidates = listOf("python", "python3", "py")
        for (candidate in candidates) {
            val ok = runCatching {
                val process = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.readBytes()
                process.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    /** Runs a command and returns its exit code and its combined output. */
    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return process.waitFor() to output
    }

    private companion object {
        /** Parses a file as Python without running it. */
        const val PYTHON_PARSE =
            "import ast, sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())"
    }
}
