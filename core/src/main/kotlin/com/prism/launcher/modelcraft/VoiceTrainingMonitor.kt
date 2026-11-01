package com.prism.launcher.modelcraft

import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.VocalFeatures

/**
 * The live picture of what an audio run is learning from.
 *
 * ## Why the graph reads the measurements, not the weights
 *
 * A single scalar loss says almost nothing about a voice: it can fall while the model learns to
 * average every speaker into one flat timbre. The columns [VocalFeatures] already writes for every
 * clip -- pitch movement, rhythm, jitter, breathiness, formants, timing, phonation -- are the
 * dimensions the engine is being asked to reproduce, so plotting those beside the run is an honest
 * view of the training data rather than a decorative animation.
 *
 * ## Why it is a separate object
 *
 * [ModelCraft] is the state machine; this is a read-only projection of the current run's dataset.
 * Keeping it out of the page means the desktop and the phone plot exactly the same numbers, and a
 * test can build a graph from a features CSV without a screen.
 *
 * ## Normalisation
 *
 * The seven group traces have different units, so each column is scaled between its own 5th and
 * 95th percentile across the dataset and the group's trace is the mean of its available columns.
 * Percentiles rather than min/max: one clipped syllable would otherwise flatten every other clip
 * into a straight line. A missing measurement is a gap, never a zero.
 */
object VoiceTrainingMonitor {

    /** One trace: the key into [VocalFeatures.GROUPS] plus the short label a graph can draw. */
    data class Group(val key: String, val label: String)

    /**
     * The seven groups, in the order the feature file lists them.
     *
     * A group is one line on the graph. The labels are short because they are drawn beside a lane
     * in a 200 dp panel, not in a report.
     */
    val GROUPS: List<Group> = listOf(
        Group("prosody", "PROSODY"),
        Group("micro_variation", "MICRO"),
        Group("expression", "EXPRESSION"),
        Group("source_filter", "SOURCE/FILTER"),
        Group("identity", "IDENTITY"),
        Group("timing", "TIMING"),
        Group("phonation", "PHONATION"),
    )

    /**
     * Immutable graph state, safe to hand to a view.
     *
     * [frames] is one row per measured clip and one value per [GROUPS] entry, already scaled to
     * 0..1. [cursor] is the clip the trainer is working on now. The epoch/step/loss fields are the
     * trainer's own report, copied here so the view can label the playhead without reading
     * [ModelCraft.state] from a drawing thread.
     */
    data class Snapshot(
        val modelId: String = "",
        val frames: List<FloatArray> = emptyList(),
        val files: List<String> = emptyList(),
        val cursor: Int = 0,
        val currentFile: String = "",
        val epoch: Int = 0,
        val epochs: Int = 0,
        val step: Int = 0,
        val steps: Int = 0,
        val loss: Float = Float.NaN,
        val phase: String = "",
    ) {
        val ready: Boolean get() = frames.isNotEmpty()
    }

    @Volatile
    private var snapshot: Snapshot = Snapshot()

    /**
     * Begins a graph for [model]'s current dataset.
     *
     * Called after the voice measurement pass, because that pass is what writes the CSV this reads.
     * Silence is the right answer for a non-audio model and for music: a music model's dataset is
     * not a voice, and running the voice columns over it would draw a graph of the wrong thing.
     */
    @Synchronized
    fun start(model: CraftedModel) {
        if (model.kind != ModelCraft.Kind.AUDIO || ModelCraft.isMusic(model)) {
            snapshot = Snapshot()
            return
        }
        val rows = runCatching {
            SpeechDataset.readFeatures(ModelCraft.Store.vocalFeaturesFile(model.id))
                .ifEmpty { SpeechDataset.readFeatures(ModelCraft.Store.mergedCsvFile(model.id)) }
        }.getOrDefault(emptyList())
        snapshot = if (rows.isEmpty()) {
            // A measured dataset is the normal case; this is the one where the run would have no
            // vocal columns to draw. Naming it is better than a spinner that never resolves.
            Snapshot(modelId = model.id, phase = "No vocal measurements were found for this dataset.")
        } else {
            Snapshot(
                modelId = model.id,
                frames = buildFrames(rows),
                files = rows.map { it.file },
                phase = "training",
            )
        }
    }

    /**
     * Moves the playhead to the clip a trainer just reported.
     *
     * The file name is matched by base name because the trainer and the features pass describe the
     * same clip with different amounts of path. If it cannot be matched -- a validation clip the
     * features pass skipped, for instance -- the overall run fraction picks the nearest index, so
     * the graph keeps moving instead of sticking at the first clip for the whole run.
     */
    @Synchronized
    fun progress(progress: ModelCraft.Progress) {
        val current = snapshot
        if (current.modelId.isEmpty() || current.frames.isEmpty()) return
        val name = baseName(progress.file)
        var cursor = current.files.indexOfFirst { baseName(it).equals(name, ignoreCase = true) }
        if (cursor < 0) {
            val steps = progress.steps.coerceAtLeast(1)
            val total = progress.epochs.coerceAtLeast(1) * steps
            val done = ((progress.epoch - 1).coerceAtLeast(0) * steps + progress.step)
                .coerceIn(0, total)
            cursor = ((done.toFloat() / total) * current.frames.size).toInt()
        }
        snapshot = current.copy(
            cursor = cursor.coerceIn(0, current.frames.size - 1),
            currentFile = name,
            epoch = progress.epoch,
            epochs = progress.epochs,
            step = progress.step,
            steps = progress.steps,
            loss = progress.loss.toFloat(),
        )
    }

    /** Ends a graph for [modelId], leaving another model's run untouched. */
    @Synchronized
    fun finish(modelId: String) {
        if (snapshot.modelId == modelId) snapshot = Snapshot()
    }

    /** Forgets any graph. */
    @Synchronized
    fun clear() {
        snapshot = Snapshot()
    }

    /** The current graph, or an empty snapshot when no audio run is active. */
    @Synchronized
    fun current(): Snapshot = snapshot

    private fun baseName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun buildFrames(rows: List<VocalFeatures>): List<FloatArray> {
        val values = Array(GROUPS.size) { FloatArray(rows.size) { Float.NaN } }
        GROUPS.forEachIndexed { groupIndex, group ->
            val columns = VocalFeatures.GROUPS[group.key].orEmpty()
            if (columns.isEmpty()) return@forEachIndexed
            val scaled = Array(columns.size) { FloatArray(rows.size) { Float.NaN } }
            columns.forEachIndexed columnLoop@ { columnIndex, column ->
                val raw = DoubleArray(rows.size) { rows[it].value(column) }
                val finite = raw.filter { it.isFinite() }.sorted()
                if (finite.isEmpty()) return@columnLoop
                val low = percentile(finite, 0.05)
                val high = percentile(finite, 0.95)
                val span = (high - low).takeIf { it > 1e-6 }
                    ?: (finite.last() - finite.first()).takeIf { it > 1e-6 }
                    ?: 0.0
                raw.forEachIndexed rowLoop@ { rowIndex, value ->
                    if (!value.isFinite()) return@rowLoop
                    scaled[columnIndex][rowIndex] = if (span == 0.0) {
                        0.5f
                    } else {
                        ((value - low) / span).coerceIn(0.0, 1.0).toFloat()
                    }
                }
            }
            rows.indices.forEach { rowIndex ->
                val present = scaled.mapNotNull { column ->
                    column[rowIndex].takeIf { it.isFinite() }
                }
                if (present.isNotEmpty()) {
                    values[groupIndex][rowIndex] = present.average().toFloat()
                }
            }
        }
        return rows.indices.map { rowIndex ->
            FloatArray(GROUPS.size) { groupIndex -> values[groupIndex][rowIndex] }
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = ((sorted.size - 1) * fraction).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val mix = position - lower
        return sorted[lower] * (1 - mix) + sorted[upper] * mix
    }
}
