package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in real-corpus probe.
 *
 * Run with `PRISM_TEXT_PROBE=1` and the paths below. The synthetic tests pin the machinery; this
 * test is the listening/reading pass: dailyDialog's own train/validation/test files plus a bounded
 * OpenHermes conversation sample, then a few typed prompts.
 */
class TextModelProbeTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-text-probe-${System.nanoTime()}",
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

    @Test
    fun `train on dailydialog and openhermes and answer prompts`() {
        if (System.getenv("PRISM_TEXT_PROBE") != "1") return
        val daily = File(System.getenv("PRISM_TEXT_DAILY") ?: "")
        val openHermes = File(System.getenv("PRISM_TEXT_OPENHERMES") ?: "")
        assertTrue(daily.isDirectory, "dailyDialog directory is missing: ${daily.absolutePath}")
        assertTrue(openHermes.isFile, "OpenHermes sample is missing: ${openHermes.absolutePath}")

        val model = ModelCraft.create(
            "Probe conversation model",
            ModelCraft.Kind.TEXT,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        val directory = ModelCraft.Store.datasetDir(model.id)
        File(directory, "train").mkdirs()
        File(directory, "validation").mkdirs()
        File(directory, "test").mkdirs()
        File(daily, "train/dialogues_train.txt").copyTo(File(directory, "train/dialogues_train.txt"))
        File(daily, "validation/dialogues_validation.txt").copyTo(
            File(directory, "validation/dialogues_validation.txt"),
        )
        File(daily, "test/dialogues_test.txt").copyTo(File(directory, "test/dialogues_test.txt"))
        openHermes.copyTo(File(directory, "train/openhermes.json"))

        model.params[ModelCraft.KEY_FROM_SCRATCH] = "true"
        System.getenv("PRISM_TEXT_DIM")?.let { model.params["dim"] = it }
        System.getenv("PRISM_TEXT_LAYERS")?.let { model.params["layers"] = it }
        System.getenv("PRISM_TEXT_HEADS")?.let { model.params["heads"] = it }
        model.params["loss_on_prompt"] = System.getenv("PRISM_TEXT_LOSS_ON_PROMPT") ?: "false"
        model.params["dataset_format"] = "conversation"
        model.params["chat_template"] = "User/Assistant"
        model.params["tokenizer"] = "word"
        model.params["max_seq_length"] = System.getenv("PRISM_TEXT_POSITIONS") ?: "96"
        model.params["vocabulary"] = System.getenv("PRISM_TEXT_VOCAB") ?: "8192"
        model.params["learning_rate"] = System.getenv("PRISM_TEXT_LR") ?: "0.002"
        model.params["max_grad_norm"] = "1.0"
        model.params["weight_decay"] = "0.01"
        model.params["warmup_ratio"] = "0.05"
        model.params["lr_scheduler"] = "cosine"
        model.params["seed"] = "42"
        model.params["packing"] = "true"
        model.params["batch_size"] = System.getenv("PRISM_TEXT_BATCH") ?: "1"
        model.params["eval_steps"] = System.getenv("PRISM_TEXT_EVAL") ?: "50"
        model.params["max_steps"] = System.getenv("PRISM_TEXT_MAX_STEPS") ?: "100"
        model.params["train_split"] = "train"
        model.params["validation_split"] = "validation"
        model.params["test_split"] = "test"
        model.params["temperature"] = System.getenv("PRISM_TEXT_TEMPERATURE") ?: "0.5"
        model.params["top_k"] = System.getenv("PRISM_TEXT_TOP_K") ?: "10"
        model.params["repetition_penalty"] = System.getenv("PRISM_TEXT_REPETITION") ?: "1.15"
        ModelCraft.setSchedule(model, ModelCraft.Schedule(epochs = 1, stepsPerEpoch = 1000))
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 48, 2))

        val recipe = ModelCraft.recipeFile(model)
        val started = System.nanoTime()
        val result = assertNotNull(TextTrainer.trainResult(model, recipe))
        val seconds = (System.nanoTime() - started) / 1e9
        val metadata = JSONObject(File(directory, TextTrainer.METADATA_FILE).readText())
        val prompts = listOf(
            "Hello",
            "What is the weather like today?",
            "I am feeling a little tired.",
            "Can you help me plan a small dinner?",
            "Tell me something interesting.",
            "How are you?",
            "What did you do yesterday?",
            "I'm sorry to hear that.",
            "Good morning.",
        )
        val temperatures = listOf(0.5, 0.8)
        val answers = prompts.map { prompt ->
            prompt to temperatures.map { temperature ->
                temperature to result.trained.respond(prompt, 80, temperature = temperature)
            }
        }
        val saved = System.getenv("PRISM_TEXT_SAVE")
        if (!saved.isNullOrBlank()) {
            val out = File(saved).apply { mkdirs() }
            listOf(TextTrainer.METADATA_FILE, TextTrainer.WEIGHTS_FILE, TextTrainer.VOCAB_FILE).forEach { name ->
                File(directory, name).takeIf { it.isFile }?.copyTo(File(out, name), overwrite = true)
            }
            result.trained.artifactFiles.forEach { file ->
                file.takeIf { it.isFile }?.copyTo(File(out, file.name), overwrite = true)
            }
        }

        val report = buildString {
            appendLine("# Text Model Probe")
            appendLine()
            appendLine("Dataset: dailyDialog train/validation/test + OpenHermes-2.5 sample")
            appendLine()
            appendLine("## Run")
            appendLine()
            appendLine("- ${result.summary}")
            appendLine("- wall clock: ${"%.1f".format(seconds)} s")
            appendLine("- architecture: ${model.architecture} / ${model.family}")
            appendLine("- positions: ${metadata.optInt("positions")}")
            appendLine("- vocabulary: ${metadata.optInt("vocabulary")} (limit ${metadata.optInt("vocabulary_limit")})")
            appendLine("- batch: ${metadata.optInt("batch_size")} · prompt masked: ${metadata.optBoolean("prompt_masked")}")
            appendLine("- parameters: ${metadata.optInt("parameters")}")
            appendLine("- validation loss: ${metadata.optDouble("loss_validation")}")
            appendLine("- test loss: ${metadata.optDouble("loss_test")}")
            appendLine("- sliding window: ${metadata.optInt("sliding_window_size")} every ${metadata.optInt("full_attention_per_nth_layer")}")
            appendLine()
            appendLine("## Prompts and answers")
            appendLine()
            for ((prompt, byTemperature) in answers) {
                appendLine("### ${prompt}")
                appendLine()
                for ((temperature, answer) in byTemperature) {
                    appendLine("temperature $temperature: ${answer.ifBlank { "(empty)" }}")
                }
                appendLine()
            }
        }
        val reportPath = System.getenv("PRISM_TEXT_REPORT")
        if (!reportPath.isNullOrBlank()) File(reportPath).writeText(report)
        println(report)
        for ((prompt, byTemperature) in answers) {
            println("PROMPT: $prompt")
            for ((temperature, answer) in byTemperature) {
                println("ANSWER @ $temperature: $answer")
            }
            println()
        }
    }
}
