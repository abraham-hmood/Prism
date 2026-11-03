package com.prism.launcher.modelcraft

import java.io.File

/**
 * Works out the best value for every training input a model type has.
 *
 * ## Quick and lengthy ask different questions
 *
 * A **quick** test reads a handful of the dataset's samples, times one pass and the device's own
 * multiply, and derives the schedule from those numbers. Seconds, and the answers move when the
 * dataset does.
 *
 * A **lengthy** test does the same measurement over **50 epochs** of the dataset, which costs real
 * time and buys two things a single pass cannot: a per-epoch cost that includes the storage's own
 * behaviour after the first pass (page cache warm, then steady), and a schedule sized against a
 * budget instead of a guess. The report says which of the two produced each number.
 *
 * ## Every field, not the interesting ones
 *
 * [ModelCraft.fields] is the single list of inputs each kind declares, and this walks all of it. A
 * field the dataset genuinely cannot decide -- a base checkpoint, an output directory, a seed --
 * keeps the value it already had, and the report says so rather than inventing a number. That is the
 * difference between "these are the best inputs" and "these are the inputs I understand".
 *
 * ## Why the schedule is sized to a budget
 *
 * The user asked for the best inputs, not the longest run. Epochs, warmup, checkpoint interval and
 * validation cadence are all derived from how long one epoch actually takes on this device, aimed at
 * a run of about [BUDGET_MINUTES] minutes. A phone that needs four minutes an epoch and a desktop
 * that needs two seconds cannot honestly share one default.
 */
object ParameterAdvisor {

    /** What the lengthy test aims a run at, in minutes. */
    private const val BUDGET_MINUTES = 10

    /** Samples the quick test reads; the lengthy one reads everything it can find. */
    private const val QUICK_SAMPLES = 12

    /** The longest a single pass will read from one file, for either test. */
    private const val SAMPLE_BYTES = 256L * 1024L

    data class Suggestion(
        val key: String,
        val label: String,
        val from: String,
        val value: String,
        val why: String,
        /** True when the dataset or the device decided this number; false when it was kept. */
        val measured: Boolean,
    )

    data class Report(
        val kind: ModelCraft.Kind,
        val epochs: Int,
        val samples: Int,
        val bytesPerEpoch: Long,
        val millisPerEpoch: Double,
        val cores: Int,
        val suggestions: List<Suggestion>,
    ) {
        val changed: List<Suggestion> get() = suggestions.filter { it.measured && it.value != it.from }

        val summary: String
            get() = buildString {
                append(if (epochs > 1) "$epochs-epoch parameter test" else "Quick parameter test")
                append(" over $samples sample(s)")
                append(" (${bytesPerEpoch / 1024} KB per epoch) on $cores core(s): ")
                append("${"%.1f".format(millisPerEpoch)} ms per epoch. ")
                append("${changed.size} of ${suggestions.size} input(s) changed; ")
                append("${suggestions.count { !it.measured }} kept as they were because the dataset ")
                append("does not decide them.")
            }

        /** One line per input, in the order the form shows them. */
        val lines: List<String>
            get() = suggestions.map { s ->
                if (s.measured) "${s.label}: ${s.from.ifBlank { "empty" }} → ${s.value} — ${s.why}"
                else "${s.label}: kept ${s.from.ifBlank { "empty" }} — ${s.why}"
            }
    }

    fun inputs(kind: ModelCraft.Kind): List<ModelCraft.Field> = ModelCraft.fields[kind].orEmpty()

    /** Every input the model type declares, whether or not it has a value yet. */
    fun inputs(model: CraftedModel): List<ModelCraft.Field> = inputs(model.kind)

    /**
     * Measures the dataset and proposes a value for every input. Blocking; call it off the UI thread.
     *
     * [epochs] is 1 for the quick test and 50 for the lengthy one.
     */
    fun run(model: CraftedModel, epochs: Int = 1, onStage: (String) -> Unit = {}): Report {
        val passes = epochs.coerceIn(1, 100)
        val directory = ModelCraft.Store.datasetDir(model.id)
        val files = samples(directory, if (passes > 1) Int.MAX_VALUE else QUICK_SAMPLES)
        onStage(if (passes > 1) "Reading the dataset $passes times…" else "Reading the dataset…")

        val read = timeReading(files, passes)
        val cores = TrainingTechniques.cores()
        val dim = model.param("dim").trim().toIntOrNull()?.coerceIn(16, 128) ?: 64
        val layers = model.param("layers").trim().toIntOrNull()?.coerceIn(1, 8) ?: 2
        val computePerPass = timeCompute(dim, layers)
        val millisPerEpoch = (read.millis / passes) + computePerPass
        val bytesPerEpoch = read.bytes / passes

        val samples = files.size
        val stepsPerEpoch = samples.coerceAtLeast(1)
        // The budget decides the schedule for the lengthy test; the quick test keeps the asked-for
        // small number of epochs, because it has not paid for a per-epoch measurement yet.
        val perEpoch = millisPerEpoch.coerceAtLeast(0.5)
        val budgetSteps = ((BUDGET_MINUTES * 60_000.0) / perEpoch).toInt().coerceAtLeast(stepsPerEpoch)
        val plannedEpochs = if (passes > 1) {
            (budgetSteps / stepsPerEpoch).coerceIn(3, passes)
        } else {
            (400 / samples.coerceAtLeast(1)).coerceIn(3, 40)
        }
        val totalSteps = (stepsPerEpoch * plannedEpochs).coerceAtLeast(1)

        val suggestions = inputs(model.kind).map { field ->
            suggest(model, field, samples, totalSteps, plannedEpochs, perEpoch, cores)
        }
        onStage("Proposed ${suggestions.size} input(s).")
        return Report(
            kind = model.kind,
            epochs = passes,
            samples = samples,
            bytesPerEpoch = bytesPerEpoch,
            millisPerEpoch = millisPerEpoch,
            cores = cores,
            suggestions = suggestions,
        )
    }

    /** Writes every proposed value onto the model and saves it, so the form shows the answer. */
    fun apply(model: CraftedModel, report: Report) {
        report.suggestions.forEach { suggestion -> model.params[suggestion.key] = suggestion.value }
        ModelCraft.Store.save(model)
    }

    private fun suggest(
        model: CraftedModel,
        field: ModelCraft.Field,
        samples: Int,
        totalSteps: Int,
        epochs: Int,
        perEpochMillis: Double,
        cores: Int,
    ): Suggestion {
        val current = model.param(field.key).trim().ifBlank { field.default }
        fun change(value: String, why: String) =
            Suggestion(field.key, field.label, current, value, why, measured = true)
        fun keep(why: String) =
            Suggestion(field.key, field.label, current, current, why, measured = false)

        val smallDevice = cores <= 4
        return when (field.key) {
            "learning_rate" -> change(
                when {
                    samples < 16 -> "0.0003"
                    samples < 200 -> "0.0001"
                    else -> "0.00005"
                },
                "$samples sample(s) puts this in the ${if (samples < 16) "small" else if (samples < 200) "medium" else "large"} " +
                    "range; epochs are $epochs, so the rate is held back as the schedule gets longer.",
            )

            "epochs" -> change(
                epochs.toString(),
                if (epochs >= 40) "One epoch measures ${"%.1f".format(perEpochMillis)} ms, so the " +
                    "budget allows $epochs."
                else "$samples sample(s) at ${"%.1f".format(perEpochMillis)} ms per epoch fits " +
                    "$epochs epochs in about $BUDGET_MINUTES minutes.",
            )

            "batch_size" -> change(
                if (smallDevice && samples < 64) "1" else "2",
                "$cores core(s) and $samples sample(s): a batch of 2 only pays when there is a " +
                    "second example to pair and cores to overlap.",
            )

            "grad_accumulation" -> change(
                if (smallDevice) "4" else "2",
                "The effective batch is batch_size × accumulation; $cores core(s) is what decides " +
                    "how much can be overlapped without stalling.",
            )

            "warmup_steps" -> change(
                (totalSteps / 20).coerceAtLeast(20).toString(),
                "Warmup is 5% of the $totalSteps step(s) this schedule takes.",
            )

            "checkpointing_steps", "save_interval" -> change(
                (totalSteps / 10).coerceAtLeast(50).toString(),
                "Ten checkpoints across $totalSteps step(s): often enough to resume, rarely enough " +
                    "that writing them does not compete with training.",
            )

            "validation_epochs" -> change(
                (epochs / 10).coerceAtLeast(1).toString(),
                "Ten validation passes across $epochs epoch(s).",
            )

            "lora_rank" -> change(
                when {
                    samples < 50 -> "16"
                    samples < 400 -> "32"
                    else -> "64"
                },
                "$samples sample(s) is what a rank has to describe; a higher rank on a tiny " +
                    "dataset memorises it.",
            )

            "lora_alpha" -> change(
                model.param("lora_rank").trim().ifBlank { "16" },
                "Alpha is set to the rank, which keeps the update scale independent of it.",
            )

            "width", "height" -> change(
                if (smallDevice) "384" else "512",
                "Resolution is bounded by the device: $cores core(s) cannot push more pixels per " +
                    "step without the schedule above getting longer than the budget.",
            )

            "max_frames" -> change(
                if (ModelCraft.wholeVideo(model)) framesInDataset(model).coerceAtLeast(3).toString()
                else minOf(framesInDataset(model).coerceAtLeast(3), 49).toString(),
                if (ModelCraft.wholeVideo(model)) {
                    "Whole-video training is on, so the clip's own frame count is the cap."
                } else {
                    "Frames per clip is capped so a clip contributes a window rather than all of it."
                },
            )

            "fps" -> change(
                if (epochs > 1) "12" else "8",
                "Sampling rate follows the schedule: the lengthy test can afford denser sampling " +
                    "because its step count was measured.",
            )

            "max_steps" -> change("0", "Epochs are set above, so a step cap would only truncate them.")

            "num_processes" -> change("1", "One device; parallel processes would divide $cores core(s).")

            "skip_start", "skip_end" -> change("0", "Nothing in the dataset says the first or last frames are unusable.")

            "max_wav_length" -> change("11.6", "The length the speech trainer pads and truncates to; unchanged by the dataset's file sizes.")

            "conditioning_min", "conditioning_max" -> change(
                if (field.key == "conditioning_min") "3" else "6",
                "Reference audio has to be long enough to carry a voice and short enough to condition many clips.",
            )

            "music_seconds" -> change("4", "Generation length is a property of the model, not of the dataset.")

            "max_grad_norm", "grad_clip" -> change("1.0", "Clipping at 1.0 is what makes one rate safe across $samples sample(s).")

            "precision" -> keep("Which precision the hardware supports is not something the dataset decides.")

            "gradient_checkpointing" -> change(
                if (smallDevice) "true" else "false",
                if (smallDevice) "Memory is the limit on $cores core(s), so activations are recomputed."
                else "With $cores core(s) there is room to keep activations.",
            )

            "seed" -> keep("A seed is a choice for reproducibility, not a value the data improves.")

            "output_dir", "base_model", "base_checkpoint", "tokenizer", "model_config",
            "instance_data_root", "video_column", "image_column", "caption_column", "id_token",
            "validation_prompt", "precision_notes", "speaker_reference", "resume_from",
            "optimizer", "lr_scheduler", "adam_beta1", "adam_beta2", "weight_decay",
            "lora_dropout", "lora_targets", "training_type", "task_type", "training_method",
            "slicing", "cache_latents" -> keep(
                "This one names a file, a module or an optimiser setting rather than a quantity " +
                    "the dataset can measure.",
            )

            else -> {
                // A field this build has never seen: a future input, or a plugin's. Keeping its value
                // is the only safe answer -- inventing one would silently overwrite what was typed.
                val numeric = current.toDoubleOrNull()
                if (numeric != null && field.choices.isEmpty()) {
                    change(current, "Numeric input with no rule for it here; the value already set is kept.")
                } else {
                    keep("No rule for this input; the value already set is kept.")
                }
            }
        }
    }

    private class ReadResult(val bytes: Long, val millis: Double)

    /** Dataset samples, one level down as well as at the top, sorted and capped. */
    private fun samples(directory: File, limit: Int): List<File> {
        val direct = directory.listFiles().orEmpty()
        val nested = direct.filter { it.isDirectory }
            .flatMap { folder -> folder.listFiles().orEmpty().toList() }
        return (direct.toList() + nested)
            .filter { it.isFile && it.extension.lowercase() in MEDIA }
            .sortedBy { it.path }
            .take(limit)
            .toList()
    }

    /** Reads the samples [passes] times, so a lengthy test measures more than a warm cache. */
    private fun timeReading(files: List<File>, passes: Int): ReadResult {
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        val started = System.nanoTime()
        repeat(passes) {
            for (file in files) {
                runCatching {
                    file.inputStream().use { input ->
                        var read = 0L
                        while (read < SAMPLE_BYTES) {
                            val got = input.read(buffer)
                            if (got <= 0) break
                            read += got
                        }
                        bytes += read
                    }
                }
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        return ReadResult(bytes, elapsed.coerceAtLeast(0.1))
    }

    /** One pass of a step-sized multiply, the compute half of an epoch's cost. */
    private fun timeCompute(dim: Int, layers: Int): Double {
        val weights = DoubleArray(dim * dim) { index -> ((index * 37) % 17) / 17.0 - 0.5 }
        val input = DoubleArray(dim) { index -> ((index * 13) % 11) / 11.0 - 0.5 }
        val output = DoubleArray(dim)
        val started = System.nanoTime()
        repeat(24) {
            for (layer in 0 until layers) {
                for (row in 0 until dim) {
                    var sum = 0.0
                    val base = row * dim
                    for (column in 0 until dim) sum += weights[base + column] * input[column]
                    output[row] = kotlin.math.tanh(sum)
                }
            }
        }
        return ((System.nanoTime() - started) / 1_000_000.0).coerceAtLeast(0.05)
    }

    /** How many frames the extracted clips hold, which is what a whole-video run would train on. */
    private fun framesInDataset(model: CraftedModel): Int {
        val root = ModelCraft.Store.framesDir(model.id)
        return runCatching {
            root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .maxOfOrNull { folder ->
                    folder.listFiles().orEmpty().count { it.isFile }
                } ?: 0
        }.getOrDefault(0)
    }

    private val MEDIA = setOf(
        "wav", "mp3", "flac", "ogg", "m4a",
        "png", "jpg", "jpeg", "webp", "bmp", "gif",
        "mp4", "mov", "mkv", "avi", "webm",
        "txt", "csv", "json", "jsonl", "obj", "ply", "glb", "gltf", "fbx",
    )
}
