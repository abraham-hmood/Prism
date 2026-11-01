package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.PixelTrainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in probe for the image model, on real images.
 *
 * `PRISM_IMAGE_PROBE=1`, `PRISM_IMAGE_DATASET=<folder with train/validation/test>`,
 * `PRISM_IMAGE_OUT=<folder for the pictures>` and the optional run knobs. It trains the same
 * conditional pixel denoiser the app trains, then samples the prompts below and writes them as PNGs.
 *
 * The point is not a unit test: it is the look-at-the-pictures pass. The loss says the velocity field
 * fits; only the saved images say whether the prompt produced what it asked for.
 */
class ImageModelProbeTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-image-probe-${System.nanoTime()}",
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
    fun `train on a real image split and sample prompts`() {
        if (System.getenv("PRISM_IMAGE_PROBE") != "1") return
        val source = File(System.getenv("PRISM_IMAGE_DATASET") ?: "")
        assertTrue(source.isDirectory, "dataset is missing: ${source.absolutePath}")
        val output = File(System.getenv("PRISM_IMAGE_OUT") ?: "C:\\Users\\bremo\\prism_generated_images")
        output.mkdirs()
        val label = System.getenv("PRISM_IMAGE_LABEL") ?: source.name

        val model = ModelCraft.create(
            "Image probe $label",
            ModelCraft.Kind.IMAGE,
            ModelCraft.architectures[1],
            "Diffusion transformer (DiT)",
        )
        val target = ModelCraft.Store.datasetDir(model.id)
        for (split in listOf("train", "validation", "test")) {
            val from = File(source, split)
            if (!from.isDirectory) continue
            from.copyRecursively(File(target, split), overwrite = true)
        }
        model.params[ModelCraft.KEY_FROM_SCRATCH] = "true"
        model.params[ModelCraft.KEY_TRAIN_LOCALLY] = "true"
        model.params["resolution"] = System.getenv("PRISM_IMAGE_RES") ?: "32"
        model.params["epochs"] = System.getenv("PRISM_IMAGE_EPOCHS") ?: "4"
        model.params["steps_per_epoch"] = System.getenv("PRISM_IMAGE_STEPS") ?: "900"
        model.params["learning_rate"] = System.getenv("PRISM_IMAGE_LR") ?: "0.0018"
        model.params["max_grad_norm"] = "1.0"
        model.params["seed"] = System.getenv("PRISM_IMAGE_SEED") ?: "42"
        model.params["caption_column"] = "caption"
        model.params["train_split"] = "train"
        model.params["validation_split"] = "validation"
        model.params["test_split"] = "test"
        model.params["random_flip"] = System.getenv("PRISM_IMAGE_FLIP") ?: "false"
        model.params["hidden_size"] = System.getenv("PRISM_IMAGE_HIDDEN") ?: "192"
        ModelCraft.setAttention(
            model,
            ModelCraft.Attention(
                enabled = (System.getenv("PRISM_IMAGE_ATTENTION") ?: "1") != "0",
                window = (System.getenv("PRISM_IMAGE_WINDOW") ?: "12").toInt(),
                fullAttentionEveryNthLayer = (System.getenv("PRISM_IMAGE_FULL_EVERY") ?: "2").toInt(),
            ),
        )

        val recipe = ModelCraft.recipeFile(model)
        val started = System.currentTimeMillis()
        val result = assertNotNull(
            PixelTrainer.trainResult(PixelTrainer.Task.IMAGE, model, recipe),
            "the image trainer produced no model for $label",
        )
        val trained = result.trained
        val side = trained.config.side
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val prompts = when {
            label.contains("mnist", true) -> listOf(
                "a handwritten digit zero",
                "a handwritten digit three",
                "a handwritten digit seven",
                "a handwritten digit eight",
            )
            label.contains("fashion", true) -> listOf(
                "a photo of a sneaker",
                "a photo of a dress",
                "a photo of a bag",
                "a photo of a trouser",
            )
            else -> listOf(
                "a photography of a woman smiling",
                "a photography of a man with a beard",
                "a photography of a child",
                "a photography of a woman with glasses",
            )
        }
        val files = mutableListOf<File>()
        val samples = (System.getenv("PRISM_IMAGE_SAMPLES") ?: "1").toInt().coerceIn(1, 8)
        val steps = (System.getenv("PRISM_IMAGE_SAMPLE_STEPS") ?: "50").toInt().coerceIn(4, 80)
        val guidance = (System.getenv("PRISM_IMAGE_GUIDANCE") ?: "2.0").toDouble()
        for ((index, prompt) in prompts.withIndex()) {
            // Several seeds per prompt, averaged. The digit is what the seeds agree on; the
            // speckle is what they do not, so averaging removes variance without blurring the
            // shape the model actually committed to.
            val average = DoubleArray(3 * side * side)
            for (sample in 0 until samples) {
                val pixels = trained.generate(
                    prompt,
                    java.util.Random(1_000L + index * 97L + sample * 7_919L),
                    steps = steps,
                    guidance = guidance,
                )
                for (pixel in average.indices) average[pixel] += pixels[pixel] / samples
            }
            val file = File(output, "$label-%02d-%s.png".format(index, slug(prompt)))
            write(file, average, side)
            files += file
        }
        // One unconditional sample beside the prompts: the difference is what the caption bought.
        val plain = trained.generate("", java.util.Random(9_999L), steps = 28, guidance = 1.0)
        val plainFile = File(output, "$label-unconditioned.png")
        write(plainFile, plain, side)

        val metadata = JSONObject(File(target, PixelTrainer.Task.IMAGE.metadataFile).readText())
        val report = buildString {
            appendLine("# Image model probe — $label")
            appendLine()
            appendLine("- ${result.summary}")
            appendLine("- side: $side · parameters: ${trained.net.parameterCount()}")
            appendLine("- train/validation/test: ${trained.trainingCount}/${trained.validationCount}/${trained.testCount}")
            appendLine("- train loss: ${"%.5f".format(metadata.optDouble("loss_first"))} → ${"%.5f".format(metadata.optDouble("loss_last"))}")
            appendLine("- validation: ${"%.5f".format(metadata.optDouble("loss_validation", Double.NaN))}")
            appendLine("- test: ${"%.5f".format(metadata.optDouble("loss_test", Double.NaN))}")
            appendLine("- conditional: ${trained.conditional} · guidance $guidance · $samples samples × $steps steps")
            appendLine("- sliding attention: ${metadata.optJSONObject("attention")?.optBoolean("applied")} " +
                "window ${trained.config.window}, full every ${trained.config.fullEvery}")
            appendLine("- ${elapsed}s")
            appendLine()
            appendLine("## Prompts")
            for ((index, prompt) in prompts.withIndex()) {
                appendLine("- \"$prompt\" → ${files[index].name}")
            }
            appendLine("- unconditioned → ${plainFile.name}")
        }
        // Keep the artifact, so sampling settings can be swept without retraining.
        val kept = File(".artifacts/image_models/$label")
        kept.mkdirs()
        for (artifact in target.listFiles().orEmpty()) {
            if (artifact.isFile && artifact.extension in setOf("json", "safetensors", "weights", "gguf")) {
                artifact.copyTo(File(kept, artifact.name), overwrite = true)
            }
        }
        File(scratch, "image-probe-$label.md").writeText(report)
        File(".artifacts").mkdirs()
        File(".artifacts/image-probe-$label.md").writeText(report)
        println(report)
    }

    /** Samples a kept artifact at several guidance/step settings, for choosing the defaults. */
    @Test
    fun `sweep sampling settings of a kept model`() {
        if (System.getenv("PRISM_IMAGE_SWEEP") != "1") return
        val directory = File(System.getenv("PRISM_IMAGE_MODEL_DIR") ?: "")
        assertTrue(directory.isDirectory, "model directory is missing: ${directory.absolutePath}")
        val output = File(System.getenv("PRISM_IMAGE_OUT") ?: ".artifacts/image_sweep")
        output.mkdirs()
        val trained = PixelTrainer.load(directory, PixelTrainer.Task.IMAGE)
        assertNotNull(trained, "no image artifact in ${directory.absolutePath}")
        val label = System.getenv("PRISM_IMAGE_LABEL") ?: directory.name
        val prompt = System.getenv("PRISM_IMAGE_PROMPT") ?: "a handwritten digit seven"
        val guidances = (System.getenv("PRISM_IMAGE_GUIDANCES") ?: "1.0,1.5,2.0,3.0")
            .split(',').mapNotNull { it.trim().toDoubleOrNull() }
        val steps = (System.getenv("PRISM_IMAGE_STEPS_SET") ?: "30,50,80")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        // Averaging seeds is the cheapest variance reduction there is: every sample integrates the
        // same learned field from different noise, so what they agree on is the subject and what
        // they do not agree on is speckle. It is not free -- the average of four blurry samples is
        // blurrier -- so the sweep writes each count rather than assuming more is better.
        val averages = (System.getenv("PRISM_IMAGE_AVG") ?: "1")
            .split(',').mapNotNull { it.trim().toIntOrNull()?.coerceIn(1, 8) }
        val side = trained.config.side
        for (guidance in guidances) {
            for (count in steps) {
                for (average in averages) {
                    val pixels = DoubleArray(3 * side * side)
                    for (sample in 0 until average) {
                        val one = trained.generate(
                            prompt,
                            java.util.Random(7_919L + sample * 104_729L),
                            steps = count,
                            guidance = guidance,
                        )
                        for (index in pixels.indices) pixels[index] += one[index] / average
                    }
                    val file = File(
                        output,
                        "$label-g${guidance}-s${count}-n${average}-${slug(prompt)}.png",
                    )
                    write(file, pixels, side)
                }
            }
        }
        println("swept $label: $guidances × $steps × $averages")
    }

    private fun write(file: File, pixels: DoubleArray, side: Int) {
        val argb = IntArray(side * side) { index ->
            val plane = side * side
            val r = to8(pixels[index])
            val g = to8(pixels[plane + index])
            val b = to8(pixels[2 * plane + index])
            PrismImage.argb(r, g, b)
        }
        val image = PrismImage(side, side, argb).scaledTo(256, 256)
        assertTrue(RasterCodec.encodePng(image, file), "could not write ${file.name}")
    }

    private fun to8(value: Double): Int = ((value + 1.0) * 127.5).toInt().coerceIn(0, 255)

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
}
