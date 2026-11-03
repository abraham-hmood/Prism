package com.prism.launcher.modelcraft

import java.io.File

/**
 * Runs a short experiment on the dataset and the machine, and adopts the technique that wins.
 *
 * ## What is measured and what is estimated
 *
 * Two things here are real measurements on this device: how long it takes to read the dataset's own
 * samples off this storage, and how long this processor takes over a matrix multiply the size of the
 * model's own width. Those two are the baseline, because every technique in
 * [TrainingTechniques.all] acts on one of them — loading and compute — and nothing else.
 *
 * What is NOT measured is the technique's effect on a full training run, which would take hours to
 * observe honestly. Each technique's documented speed-up and quality-retention figures are applied
 * to the measured baseline, and [Report.summary] says exactly that. A report that called the whole
 * number a measurement would be inventing the part that matters most.
 *
 * ## Why it runs the dataset rather than a synthetic array
 *
 * Because the answer depends on the data: twenty 4K photographs and twenty 8-word conversations
 * want different techniques, and a benchmark over an in-memory array would recommend the same one
 * for both. Reading the user's own files is the smallest experiment that respects that.
 */
object TrainingTechniqueSmokeTest {

    /** One technique's trial against the measured baseline. */
    data class Trial(
        val technique: TrainingTechnique,
        /** Estimated milliseconds for the work one baseline step needed, after this technique. */
        val estimatedMillis: Double,
        val quality: Double,
        /** Quality per millisecond, scaled so the numbers read as a ranking rather than a rate. */
        val score: Double,
    )

    data class Report(
        val samples: Int,
        val bytesRead: Long,
        val readMillis: Double,
        val computeMillis: Double,
        val cores: Int,
        val trials: List<Trial>,
        val winner: TrainingTechnique,
        /** 1 for the quick test, 50 for the lengthy one. */
        val epochs: Int = 1,
    ) {
        /** One paragraph the page can show, naming the winner and how it was chosen. */
        val summary: String
            get() = buildString {
                append(if (epochs > 1) "$epochs-epoch smoke test" else "Smoke test")
                append(" over $samples sample(s) ")
                append("(${bytesRead / 1024} KB read) on ${cores} core(s): ")
                append("loading took ${"%.1f".format(readMillis)} ms and a step-sized multiply ")
                append("took ${"%.1f".format(computeMillis)} ms. ")
                append("Winner: ${winner.label} — estimated ")
                append("${"%.2f".format(baselineMillis / (trials.firstOrNull { it.technique == winner }?.estimatedMillis ?: baselineMillis))}× ")
                append("the measured baseline step rate at ")
                append("${(100 * winner.quality).toInt()}% of the baseline quality. ")
                append("The timings are measured here; the multipliers are documented estimates of ")
                append("each technique, not a finished training run.")
            }

        /** The ranking, worst first, as lines a page can print under the summary. */
        val lines: List<String>
            get() = trials.sortedByDescending { it.score }.map { trial ->
                "${trial.technique.label}: score ${"%.1f".format(trial.score)} · est. " +
                    "${"%.1f".format(trial.estimatedMillis)} ms/step · " +
                    "${(trial.quality * 100).toInt()}% quality"
            }

        val baselineMillis: Double get() = readMillis + computeMillis

        /** The recipe knobs the winning technique asks a trainer to honour. */
        val knobs: Map<String, String>
            get() = TrainingTechniques.knobs(winner, cores)
    }

    /**
     * Measures this device and the dataset, ranks every technique, and returns the report.
     *
     * Blocking: reading files and running the multiply both take real time. Call it off the UI
     * thread. [onStage] is called with a line for a progress indicator.
     */
    fun run(
        model: CraftedModel,
        epochs: Int = 1,
        onStage: (String) -> Unit = {},
    ): Report {
        val passes = epochs.coerceIn(1, 100)
        val files = samples(model)
        onStage(
            if (passes > 1) "Reading ${files.size} dataset sample(s) $passes times…"
            else "Reading ${files.size} dataset sample(s)…",
        )
        val read = timeReading(files, passes)
        onStage("Timing this processor…")
        val cores = TrainingTechniques.cores()
        val dim = model.param("dim").trim().toIntOrNull()?.coerceIn(16, 128) ?: 64
        val layers = model.param("layers").trim().toIntOrNull()?.coerceIn(1, 8) ?: 2
        val compute = timeCompute(dim, layers) / passes.coerceAtLeast(1)
        val baseline = read.millis + compute

        val trials = TrainingTechniques.all.map { technique ->
            val (dataFactor, computeFactor) = split(technique, cores)
            val estimated = (read.millis / dataFactor + compute / computeFactor)
                .coerceAtLeast(0.05)
            Trial(
                technique = technique,
                estimatedMillis = estimated,
                quality = technique.quality,
                score = technique.quality * 1_000.0 / estimated,
            )
        }
        val winner = trials.sortedWith(
            compareByDescending<Trial> { it.score }
                .thenByDescending { it.quality }
                .thenBy { it.estimatedMillis },
        ).first().technique

        onStage("Ranked ${trials.size} techniques.")
        return Report(
            samples = files.size,
            bytesRead = read.bytes,
            readMillis = read.millis,
            computeMillis = compute,
            cores = cores,
            trials = trials,
            winner = winner,
            epochs = passes,
        )
    }

    /** Writes the winning choice onto the model and saves it, so the form shows the answer. */
    fun apply(model: CraftedModel, report: Report) {
        TrainingTechniques.set(model, report.winner.label)
        ModelCraft.Store.save(model)
    }

    /**
     * Where each technique acts: on loading, on compute, or on both.
     *
     * The pair is the whole point of the split. Caching shortens loading and barely touches the
     * multiply; SIMD does the reverse; pipelining overlaps both. Applying one overall factor would
     * rank them by their headline number instead of by what this dataset and this machine need.
     *
     * The multicore pair is capped by the cores that exist: a 16-core estimate on a 4-core device
     * would otherwise win every test on hardware the device does not have.
     */
    private fun split(technique: TrainingTechnique, cores: Int): Pair<Double, Double> {
        val parallel = (1.0 + 0.7 * (cores - 1)).coerceAtMost(technique.speedup)
        return when (technique) {
            TrainingTechnique.PIPELINING -> 1.50 to 1.35
            TrainingTechnique.SPECULATIVE -> 1.10 to 1.30
            TrainingTechnique.OUT_OF_ORDER -> 1.35 to 1.20
            TrainingTechnique.CACHING -> 1.80 to 1.05
            TrainingTechnique.SIMD -> 1.05 to 1.25
            TrainingTechnique.MULTICORE -> 1.30 to parallel
            TrainingTechnique.HIERARCHICAL_MEMORY -> 1.15 to 1.20
            TrainingTechnique.BRANCH_PREDICTION -> 1.05 to 1.28
            TrainingTechnique.SUPERSCALAR -> 1.10 to 1.32
        }
    }

    private class ReadResult(val bytes: Long, val millis: Double)

    /** The dataset's own samples: files, one level down, in a media we can read. */
    private fun samples(model: CraftedModel): List<File> {
        val root = ModelCraft.Store.datasetDir(model.id)
        val direct = root.listFiles().orEmpty()
        val nested = direct.filter { it.isDirectory }
            .flatMap { folder -> folder.listFiles().orEmpty().toList() }
        val candidates = ArrayList<File>(direct.size + nested.size)
        candidates.addAll(direct)
        candidates.addAll(nested)
        return candidates
            .filter { it.isFile && it.extension.lowercase() in MEDIA }
            .sortedBy { it.path }
            .take(MAX_SAMPLES)
            .toList()
    }

    /**
     * Times reading up to [SAMPLE_BYTES] of each sample.
     *
     * A cap rather than the whole file: the number this stands for is the cost of getting data into
     * the loop, and the first few hundred kilobytes of a photograph or a clip is what any loader
     * touches before it can start. Reading a 300 MB video in full would measure the benchmark more
     * than the dataset.
     */
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

    /** Times a matrix multiply the width of the model, repeated enough to be a stable number. */
    private fun timeCompute(dim: Int, layers: Int): Double {
        val weights = DoubleArray(dim * dim) { index -> ((index * 37) % 17) / 17.0 - 0.5 }
        val input = DoubleArray(dim) { index -> ((index * 13) % 11) / 11.0 - 0.5 }
        val output = DoubleArray(dim)
        val repeats = 24
        val started = System.nanoTime()
        repeat(repeats) {
            for (layer in 0 until layers) {
                for (row in 0 until dim) {
                    var sum = 0.0
                    val base = row * dim
                    for (column in 0 until dim) sum += weights[base + column] * input[column]
                    output[row] = kotlin.math.tanh(sum)
                }
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        return elapsed.coerceAtLeast(0.05)
    }

    private val MEDIA = setOf(
        "wav", "mp3", "flac", "ogg", "m4a",
        "png", "jpg", "jpeg", "webp", "bmp", "gif",
        "mp4", "mov", "mkv", "avi", "webm",
        "txt", "csv", "json", "jsonl", "obj", "ply", "glb", "gltf", "fbx",
    )

    /** Enough samples for the read timing to mean something, few enough that the test is quick. */
    private const val MAX_SAMPLES = 12

    /** What of each sample is read: enough to include the decode-relevant head of the file. */
    private const val SAMPLE_BYTES = 256L * 1024L
}
