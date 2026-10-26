package com.prism.launcher.aether

import com.prism.launcher.training.DatasetSplits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Ported from `execution/trainer.py::BrainTrainer` + `train.py`'s epoch loop. Shape mirrors
 * `NoraTrainer` (suspend fun on Dispatchers.Default, a `Progress` callback, `ensureActive()` as
 * the sole cancellation checkpoint, per-sample try/catch, periodic sleep/consolidation,
 * per-epoch checkpointing) since that shape already solves the same problem Aether's training
 * loop has.
 *
 * HONESTY NOTE ON BACKPROP SCOPE. `--biotrain` (STDP, [trainStdp]) updates the entire connectome,
 * faithfully. The legacy backprop path (`bioTrainMode = false`) in the source runs one
 * `GradientTape` across the ENTIRE heterogeneous forward pass -- including subcortical layers
 * (basal ganglia's competitive gating, hippocampus's fast-weights, cerebellum's smoothing,
 * amygdala's saliency) whose forward math this port implements as bespoke per-step loops rather
 * than through the same cached-for-backward path every other layer uses (see `AetherCortices.kt`
 * -- none of them populate the `AetherNeuron.kt` backward cache). Threading one consistent
 * gradient graph through all of that is a substantially larger undertaking than porting each
 * layer's own forward/backward pair, which is the part this pass actually completed. Backprop
 * mode here is real, not a stub -- it runs genuine backward passes and Adam updates -- but its
 * scope is Broca's area against the explicit text target, the one place in this architecture
 * with a well-defined supervised loss. Every other layer's weights are simply not touched by the
 * optimizer in this mode. `--biotrain` on (the default) is unaffected by this and updates
 * everything, as designed.
 */
class AetherTrainer(
    private val brain: AetherConnectome,
    private val tokenizer: SensoryTokenizer = SensoryTokenizer(brain.visualInputDim, brain.auditoryInputDim),
    private val loader: AetherMultimediaLoader = AetherMultimediaLoader(128, 128),
    /**
     * Renders a string to raw 0..255 RGB pixels for the "BIOMIMETIC READING" visual pathway a
     * [DatasetItem.TextItem] chunk is routed through (see [SensoryTokenizer.processTextVisually]).
     * `:core` has no `android.graphics` dependency (see [SensoryTokenizer]'s own doc comment), so
     * this is injected from `:app` -- [AetherStudio] passes `AetherTextRenderer::render`. Defaults
     * to a blank frame, which is only ever exercised outside the real app (e.g. a future :core-only
     * test), where no font renderer exists to inject.
     */
    private val textRenderer: (String) -> FloatArray = { FloatArray(0) },
    /**
     * Renders a string onto a canvas WIDER than the fovea, for saccadic reading
     * ([SensoryTokenizer.processTextSaccadic]). Injected from `:app` for the same reason as
     * [textRenderer]. When absent, reading falls back to [textRenderer]'s single constant frame --
     * which trains, but gives the retina no temporal structure to align with the output slots.
     */
    private val wideTextRenderer: ((String, Int) -> FloatArray)? = null
) {

    /** Canvas width for saccadic reading -- 3 fovea widths of line to scan across. */
    private val saccadeCanvasW = 384

    /** Reads [text] through the visual pathway, saccadically when a wide renderer is available. */
    private fun readVisually(text: String, timeSteps: Int): SpikeSequence {
        val wide = wideTextRenderer
        return if (wide != null) {
            tokenizer.processTextSaccadic(wide(text, saccadeCanvasW), saccadeCanvasW, 128, 128, timeSteps)
        } else {
            tokenizer.processTextVisually(textRenderer(text), timeSteps)
        }
    }
    /** Ported from `train.py::get_dataset_basenames`/`build_sensory_dataset` -- an image (filename
     * minus extension is the caption) or a chunk of a `.txt` file's sliding-window text (see
     * [TextItem]/[loadDataset]). */
    sealed class DatasetItem {
        /** What [Progress.caption] and error logging show for this item. */
        abstract val label: String

        data class ImageItem(val file: File, val caption: String) : DatasetItem() {
            override val label: String get() = caption
        }

        /** One sliding-window step of a `.txt` file: read [promptChunk] visually, produce
         * [targetChunk] as the next-characters speech target -- mirrors `train.py`'s
         * "CONVERSATIONAL TEXT SLIDING WINDOW" block exactly, including its chunk size. */
        data class TextItem(val promptChunk: String, val targetChunk: String, val sourceFile: String) : DatasetItem() {
            override val label: String get() = promptChunk
        }
    }
    data class Progress(
        val epoch: Int, val totalEpochs: Int,
        val sample: Int, val totalSamples: Int,
        val caption: String, val loss: Float, val phase: String
    )

    /**
     * File counts for a status line -- deliberately CHEAP.
     *
     * This exists because the old status call (`datasetSize()`) loaded and chunked every
     * text file just to print a number. On a real corpus that is hundreds of thousands of
     * String objects on the UI thread of the Settings screen, which is exactly the freeze
     * and out-of-memory crash tapping Aether used to cause. Counting files needs no read.
     */
    data class Summary(
        val trainFiles: Int,
        val validationFiles: Int,
        val testFiles: Int,
        val images: Int,
        val textFiles: Int,
        val parquetFiles: Int,
    ) {
        val totalFiles: Int get() = trainFiles + validationFiles + testFiles
    }

    var metabolicCost = 0.2f
    var posWeight = 30.0f
    var prevLoss = 1.0f
    private val backpropLr = 0.0003f

    /**
     * Ported from `execution/trainer.py::BrainTrainer.vocab_mastery` -- "Atomic Vocabulary
     * Mastery": word (uppercased) -> how permanent (myelinated) the synapses feeding the Broca's-
     * area neurons that fired for it have become, 0f..1f. A ratchet, never decays once a word's
     * score peaks, matching the source's own "don't decay mastery if it was once locked" comment.
     * In-memory only, same as the source (not persisted across process restarts).
     *
     * Consumed by the experimental ANN-baseline-conversion feature (see [AetherAnnBaseline] and
     * [AetherTokenizer.processTextAsAudio]) to fade its calibrated per-character gain back to
     * neutral exactly as this dictionary shows a word no longer needs the imported model's help.
     */
    val vocabMastery: MutableMap<String, Float> = mutableMapOf()

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp")
        private val TEXT_EXTENSIONS = setOf("txt")
        private val PARQUET_EXTENSIONS = setOf("parquet")
        private val DATASET_EXTENSIONS = IMAGE_EXTENSIONS + TEXT_EXTENSIONS + PARQUET_EXTENSIONS

        /** `train.py`'s `C.TEXT_CHUNK_SIZE` ("1 character every N steps", a slow-reading fix). */
        const val TEXT_CHUNK_SIZE = 6

        /**
         * The dataset's training items, from the TRAIN split only.
         *
         * Filename (minus extension) is the caption for images -- same convention as Nora's and
         * the source's own dataset folder's `*.png` files. Every `.txt` file's full text is also
         * expanded into sliding-window [DatasetItem.TextItem]s, and every `.parquet` file's
         * conversations become reading/answering items.
         *
         * Held-out files (`validation/`, `validation-*.parquet`, ...) are deliberately NOT here:
         * training on them would make the only honest measurement of the run meaningless.
         */
        fun loadDataset(datasetDir: File): List<DatasetItem> =
            loadItems(DatasetSplits.scan(datasetDir, DATASET_EXTENSIONS).train)

        /** Every split of a dataset. Loaded once by [train]; see [DatasetSplits] for the rules. */
        fun scanDataset(datasetDir: File): DatasetSplits.Files =
            DatasetSplits.scan(datasetDir, DATASET_EXTENSIONS)

        fun summarize(datasetDir: File): Summary {
            val splits = scanDataset(datasetDir)
            fun kind(entries: List<DatasetSplits.Entry>, ext: Set<String>) =
                entries.count { it.file.extension.lowercase() in ext }
            return Summary(
                trainFiles = splits.train.size,
                validationFiles = splits.validation.size,
                testFiles = splits.test.size,
                images = kind(splits.all, IMAGE_EXTENSIONS),
                textFiles = kind(splits.all, TEXT_EXTENSIONS),
                parquetFiles = kind(splits.all, PARQUET_EXTENSIONS),
            )
        }

        /** Turns already-classified files into training items. */
        fun loadItems(entries: List<DatasetSplits.Entry>): List<DatasetItem> {
            val items = mutableListOf<DatasetItem>()
            for (entry in entries) {
                val file = entry.file
                when (file.extension.lowercase()) {
                    in IMAGE_EXTENSIONS -> items += DatasetItem.ImageItem(file, file.nameWithoutExtension)
                    in TEXT_EXTENSIONS -> items += loadTextChunks(file)
                    in PARQUET_EXTENSIONS -> items += loadParquetItems(file, entry.relativePath)
                }
            }
            return items
        }

        /** Ported from `train.py::build_sensory_dataset`'s "CONVERSATIONAL TEXT SLIDING WINDOW"
         * block: every [TEXT_CHUNK_SIZE] characters becomes one prompt/target pair, the target
         * shifted one character ahead (space-padded if it runs past the end of the file). Files
         * of 10 characters or fewer are skipped, matching the source's own `len(full_text) > 10` gate. */
        private fun loadTextChunks(file: File): List<DatasetItem.TextItem> {
            val fullText = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { return emptyList() }
            return chunkText(fullText, file.name)
        }

        private fun chunkText(fullText: String, source: String): List<DatasetItem.TextItem> {
            if (fullText.length <= 10) return emptyList()

            val chunkSize = TEXT_CHUNK_SIZE
            val items = mutableListOf<DatasetItem.TextItem>()
            val limit = max(1, fullText.length - chunkSize)
            var i = 0
            while (i < limit) {
                val pStr = fullText.substring(i, min(i + chunkSize, fullText.length))
                var tStr = fullText.substring(min(i + 1, fullText.length), min(i + 1 + chunkSize, fullText.length))
                if (tStr.length < pStr.length) tStr += " ".repeat(pStr.length - tStr.length)
                items.add(DatasetItem.TextItem(pStr, tStr, source))
                i += chunkSize
            }
            return items
        }

        /**
         * A parquet file as reading items.
         *
         * Chat transcripts are used as EXCHANGES -- read the prompt, learn to produce the reply --
         * which is what a conversational column actually contains. A parquet of prose has no
         * `<|role|>` markers, so [AetherParquet.conversations] returns nothing for it; those rows
         * fall back to the same sliding-window text ingestion a `.txt` file gets, rather than
         * silently contributing zero samples.
         */
        private fun loadParquetItems(file: File, label: String): List<DatasetItem> {
            val conversations = try {
                AetherParquet.conversations(file, label)
            } catch (e: Exception) {
                AetherLog.warn(AetherLog.Area.TRAIN, "Parquet: could not read ${file.name}: ${e.message}")
                emptyList()
            }
            if (conversations.isNotEmpty()) {
                val items = mutableListOf<DatasetItem>()
                for (conversation in conversations) {
                    for ((prompt, reply) in conversation.exchanges()) {
                        items += DatasetItem.TextItem(prompt.text, reply.text, label)
                    }
                }
                if (items.isNotEmpty()) return items
            }

            return try {
                AetherParquet.strings(file).flatMap { chunkText(it, label) }
            } catch (e: Exception) {
                AetherLog.warn(AetherLog.Area.TRAIN, "Parquet: no usable text in ${file.name}: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun train(
        epochs: Int,
        bioTrainMode: Boolean = true,
        datasetOverride: List<DatasetItem>? = null,
        timeSteps: Int = AetherTextCoding.TIME_STEPS,
        validationIntervalEpochs: Int = 1,
        /** How many epochs pass between periodic connectome saves -- `train.py`'s own
         * `--checkpoint-interval` (`C.CHECKPOINT_INTERVAL`). Epoch 1 ("infancy" archiving, matching
         * `train.py`'s own `if epoch == 1: save_checkpoint()`) and the final epoch always save
         * regardless of this value, so a run can never end without a save on disk. */
        checkpointIntervalEpochs: Int = 1,
        /** Experimental ANN-baseline-conversion feature's calibrated per-character gain table
         * (see [AetherAnnBaseline]), or null (ordinary training, unaffected). Deliberately never
         * threaded into validation/testing targets below -- those exist to measure genuine
         * unaided performance. */
        charGain: FloatArray? = null,
        onProgress: (Progress) -> Unit = {}
    ): String = withContext(Dispatchers.Default) {
        // ONE SCAN FOR ALL THREE SETS. Split files can live in subdirectories (`validation/`)
        // or be named as shards (`repo__validation-00000.parquet`) in the dataset root, and a
        // dataset may have any number of files per split. DatasetSplits owns that rule so the
        // training set can never quietly include a held-out file.
        val splitFiles = scanDataset(AetherConfig.datasetDir())
        val dataset = datasetOverride ?: loadItems(splitFiles.train)
        if (dataset.isEmpty()) {
            return@withContext "No training images or text found in ${AetherConfig.datasetDir().absolutePath}"
        }
        val safeCheckpointInterval = max(1, checkpointIntervalEpochs)

        // --- VALIDATION / TESTING: genuinely optional, any number of files each ---
        // Neither set changes during a run, so both are loaded once, up front. A file named
        // `test-...` in the root and a file in `testing/` are both honoured.
        // An explicit override is a self-contained set; the user's held-out files would not
        // belong to it.
        val validationDataset = if (datasetOverride != null) emptyList() else loadItems(splitFiles.validation)
        val testingDataset = if (datasetOverride != null) emptyList() else loadItems(splitFiles.test)
        if (validationDataset.isNotEmpty()) {
            AetherLog.info(AetherLog.Area.TRAIN, "[Validation] ${validationDataset.size} held-out sample(s) from ${splitFiles.validation.size} file(s).")
        }
        if (testingDataset.isNotEmpty()) {
            AetherLog.info(AetherLog.Area.TRAIN, "[Testing] ${testingDataset.size} held-out sample(s) from ${splitFiles.test.size} file(s) -- evaluated once, after training concludes.")
        }

        val rng = java.util.Random()
        // NaN, not zero: an untouched 0.0 formats as a PERFECT loss, and the old summary did
        // exactly that when every sample threw. Losses are accumulated separately so the number
        // reported is the run's average rather than whichever sample happened to be last.
        var lastLoss = Float.NaN
        var lossSum = 0.0
        var lossCount = 0
        var successful = 0
        var lastValidationLoss: Float? = null // most recent held-out validation/test loss -- feeds AetherScoring

        for (epoch in 1..epochs) {
            val order = dataset.indices.shuffled(rng)
            for ((n, idx) in order.withIndex()) {
                coroutineContext.ensureActive()
                val item = dataset[idx]
                try {
                    val (visual, target, trainLabel) = when (item) {
                        is DatasetItem.ImageItem -> {
                            val pixels = loader.loadImage(item.file, bioTrainMode = false, epoch = epoch)
                            val v = tokenizer.processImage(pixels, timeSteps)
                            val t = tokenizer.processTextAsAudio(
                                item.caption, timeSteps,
                                charGain = charGain, vocabMastery = if (charGain != null) vocabMastery else null
                            )
                            Triple(v, t, item.caption)
                        }
                        is DatasetItem.TextItem -> {
                            val v = readVisually(item.promptChunk, timeSteps)
                            val t = tokenizer.processTextAsAudio(
                                item.targetChunk, timeSteps,
                                charGain = charGain, vocabMastery = if (charGain != null) vocabMastery else null
                            )
                            Triple(v, t, item.promptChunk)
                        }
                    }
                    val audio = SpikeSequence(timeSteps, brain.auditoryInputDim, 1, 1) // silence, matching train.py's blind_audio

                    // Forward pass: see it, say it. The strip reconstructs what the retina saw.
                    lastLoss = if (bioTrainMode) trainStdp(visual, audio, target, trainLabel, visual)
                               else trainBackprop(visual, audio, target, visual)

                    // REVERSE pass, flashcards only: read the label, imagine the picture. This is
                    // the pair that makes "green 22 24" -> a green image a learnable objective at
                    // all; without it the visual target is always the input, so the connectome is
                    // only ever asked to reconstruct whatever it is already looking at, and the
                    // best possible answer to reading those words is a picture of those words.
                    if (item is DatasetItem.ImageItem) {
                        val readBack = readVisually(item.caption, timeSteps)
                        lastLoss = if (bioTrainMode) trainStdp(readBack, audio, target, trainLabel, visual)
                                   else trainBackprop(readBack, audio, target, visual)
                    }
                    if (lastLoss.isFinite()) {
                        lossSum += lastLoss.toDouble()
                        lossCount++
                        successful++
                    } else {
                        AetherLog.error(
                            AetherLog.Area.TRAIN,
                            "Sample ${item.label} produced a non-finite loss and was discarded",
                        )
                    }
                } catch (e: OutOfMemoryError) {
                    AetherLog.fatal(AetherLog.Area.TRAIN, "Out of memory training on ${item.label}", e)
                    throw e
                } catch (e: Exception) {
                    AetherLog.error(AetherLog.Area.TRAIN, "Sample failed: ${item.label}: ${e.message}")
                }
                onProgress(Progress(epoch, epochs, n + 1, order.size, item.label, lastLoss, "wake"))
            }

            if (epoch % 3 == 0) {
                onProgress(Progress(epoch, epochs, order.size, order.size, "", lastLoss, "sleep"))
                sleepPhase()
            }

            // --- HELD-OUT VALIDATION (dataset/validation/, if present) ---
            // A real generalization check -- runHeldOutEvaluation only ever calls evaluateStep,
            // which never touches a synaptic weight.
            if (validationDataset.isNotEmpty() && epoch % validationIntervalEpochs == 0) {
                val (avgLoss, count) = runHeldOutEvaluation(validationDataset, timeSteps, "Validation")
                if (avgLoss != null) {
                    lastValidationLoss = avgLoss
                    onProgress(Progress(epoch, epochs, count, count, "validation avg loss %.4f".format(avgLoss), avgLoss, "validation"))
                }
            }

            // Periodic checkpoint, `train.py`'s own `CHECKPOINT_INTERVAL` gate: epoch 1 ("infancy"
            // archiving) and the final epoch always save, everything in between only every
            // [checkpointIntervalEpochs] epochs -- a training run can never end without a save on
            // disk, but doesn't pay a full connectome write on every single epoch either.
            // A CHECKPOINT IS A CLAIM THAT SOMETHING WAS LEARNED. If every sample in this epoch
            // failed, the connectome is exactly the one that was loaded; writing it (and telling
            // the mesh about a new score) would advertise a trained brain that had learned
            // nothing. Epoch 1 is gated the same way as every other checkpoint.
            if (successful > 0 &&
                (epoch == 1 || epoch % safeCheckpointInterval == 0 || epoch == epochs)
            ) {
                AetherConfig.weightsDir().mkdirs()
                brain.save(AetherConfig.weightsFile(brain.geometry))
                if (AetherKnowledgeSync.isSharing()) {
                    // Recomputed on every export since vocabMastery/lastValidationLoss keep
                    // changing as training progresses -- see AetherScoring's doc comment.
                    val score = AetherScoring.computeScore(vocabMastery, dataset.size, lastValidationLoss)
                    AetherKnowledgeSync.exportSnapshot(brain, mapOf("score" to score.toString()))
                }
            }
        }

        // --- FINAL TEST (dataset/testing/, if present) ---
        // Evaluated exactly once, here, never during training and never used to influence a
        // single training decision.
        if (testingDataset.isNotEmpty()) {
            val (avgLoss, count) = runHeldOutEvaluation(testingDataset, timeSteps, "Final Test")
            if (avgLoss != null) {
                lastValidationLoss = avgLoss
                onProgress(Progress(epochs, epochs, count, count, "final test avg loss %.4f".format(avgLoss), avgLoss, "test"))
                // The final test is the most trustworthy signal available for scoring -- re-export
                // so a shared score reflects it rather than the last periodic validation reading.
                if (AetherKnowledgeSync.isSharing()) {
                    val score = AetherScoring.computeScore(vocabMastery, dataset.size, lastValidationLoss)
                    AetherKnowledgeSync.exportSnapshot(brain, mapOf("score" to score.toString()))
                }
            }
        }

        if (successful == 0) {
            return@withContext "Training did NOT run: none of the ${dataset.size} sample(s) could be presented " +
                "successfully. Nothing was saved and no knowledge was shared.\n\n" +
                "The first failure is in the training log; an empty or unreadable dataset and a " +
                "geometry too small for the input are the usual causes."
        }
        val average = if (lossCount > 0) lossSum / lossCount else Double.NaN
        "Trained $successful of ${dataset.size} sample(s) over $epochs epochs " +
            "(${if (bioTrainMode) "biotrain" else "backprop"}). Average training loss %.4f."
                .format(average)
    }

    /**
     * Cross-entropy over a real 95-way choice per slot, scored on accumulated SPIKE COUNT.
     *
     * The old objective was `mean(weight * (spikes - target)^2)` -- mean squared error against a
     * BINARY spike train. Spikes carry no confidence, so "fired the wrong character hard" and
     * "came within a hair of the right one" scored identically and the gradient could not say
     * which way to move in character space. Worse, MSE treats the 95 characters as 95 independent
     * regressions rather than one mutually-exclusive choice, so nothing ever pushed probability
     * mass off the wrong characters and onto the right one.
     *
     * Logits are the summed spikes in each slot -- deliberately the SAME quantity
     * [AetherMotorDecoder.decodeToText] reads back and [SensoryTokenizer.processTextAsAudio]
     * writes its targets in. Scoring one quantity and reading another is what made the old
     * pipeline unmeasurable; here the loss, the target and the readout are all the slot rate.
     *
     * Returns the loss and its gradient with respect to the motor spike train: for slot `s`,
     * `(softmax - onehot) / slots`, spread across that slot's timesteps.
     */
    private fun characterLossAndDelta(brocaSpikes: SpikeSequence, target: SpikeSequence): Pair<Float, SpikeSequence> {
        val steps = brocaSpikes.steps
        val n = brocaSpikes.c
        val delta = SpikeSequence(steps, n, 1, 1)
        val lo = AetherTextCoding.ASCII_LOW
        val hi = min(AetherTextCoding.ASCII_HIGH, n)
        val slotSteps = max(1, AetherTextCoding.SLOT_STEPS)
        val slots = max(1, steps / slotSteps)
        if (lo >= hi) return 0f to delta

        var total = 0f
        val logits = FloatArray(hi - lo)
        val probs = FloatArray(hi - lo)

        for (s in 0 until slots) {
            val from = s * slotSteps
            val to = min(steps, from + slotSteps)

            var label = ' '.code - lo
            var bestTarget = 0f
            for (i in lo until hi) {
                var emitted = 0f
                var wanted = 0f
                for (t in from until to) { emitted += brocaSpikes[t].data[i]; wanted += target[t].data[i] }
                logits[i - lo] = emitted
                if (wanted > bestTarget) { bestTarget = wanted; label = i - lo }
            }
            // A slot past the end of a short label carries no target spikes at all, and is trained
            // toward space -- which is what should be emitted there.

            var maxLogit = -Float.MAX_VALUE
            for (v in logits) if (v > maxLogit) maxLogit = v
            var sumExp = 0f
            for (j in logits.indices) { val e = exp((logits[j] - maxLogit).toDouble()).toFloat(); probs[j] = e; sumExp += e }
            if (sumExp <= 0f) continue
            for (j in probs.indices) probs[j] /= sumExp

            total += -ln(max(probs[label], 1e-9f).toDouble()).toFloat()

            for (j in probs.indices) {
                val g = (probs[j] - if (j == label) 1f else 0f) / slots
                for (t in from until to) delta[t].data[lo + j] = g
            }
        }
        return (total / slots) to delta
    }

    /**
     * [visualTarget] is what the motor strip should IMAGINE, which is not necessarily what the
     * retina was shown. This used to compare the strip's output against the INPUT -- a pure
     * autoencoder, under which the best possible response to reading the words "green 22 24" is a
     * picture of those words. Green pixels were never in the objective at all, so text -> image was
     * unreachable by construction rather than merely untrained. Passing the input as the target
     * restores the old behaviour exactly, which is what the "see it, say it" pass still wants.
     */
    private fun visualLoss(visualSpikes: SpikeSequence, visualTarget: SpikeSequence): Float {
        val meanSpikes = FloatArray(visualSpikes.c * visualSpikes.h * visualSpikes.w)
        for (t in 0 until visualSpikes.steps) for (i in meanSpikes.indices) meanSpikes[i] += visualSpikes[t].data[i]
        for (i in meanSpikes.indices) meanSpikes[i] /= visualSpikes.steps

        val meanInput = FloatArray(visualTarget.c * visualTarget.h * visualTarget.w)
        for (t in 0 until visualTarget.steps) for (i in meanInput.indices) meanInput[i] += visualTarget[t].data[i]
        for (i in meanInput.indices) meanInput[i] /= visualTarget.steps

        val len = min(meanSpikes.size, meanInput.size)
        var s = 0f
        for (i in 0 until len) { val d = meanSpikes[i] - meanInput[i]; s += d * d }
        return s / max(1, len)
    }

    /** `--biotrain`: local Hebbian/STDP across the whole connectome, dopamine-modulated by loss improvement. */
    private fun trainStdp(visual: SpikeSequence, audio: SpikeSequence, target: SpikeSequence, caption: String, visualTarget: SpikeSequence): Float {
        brain.resetState()
        val result = brain.forward(visual, audio)
        val (textLoss, _) = characterLossAndDelta(result.responseBroca, target)
        val vLoss = visualLoss(result.imaginedVisual, visualTarget)
        val totalLoss = textLoss + vLoss + result.internalDensity * metabolicCost

        val accuracy = 1f / (textLoss + 1e-6f)
        val baseline = 1f / (prevLoss + 1e-6f)
        var dopamine = accuracy / (baseline + 1e-6f)
        prevLoss = textLoss
        dopamine = max(dopamine, 0.25f)
        brain.dopamineLevel = dopamine

        metabolicCost = (0.01f + totalLoss * 0.2f).coerceIn(0.01f, 2.0f)
        posWeight = (10.0f * dopamine).coerceIn(1.0f, 50.0f)

        brain.updateHebbianTraces()
        brain.applyHomeostaticRegulation(result.regionalActivity)
        brain.applyStdp(learningRate = 1e-4f, metabolicTax = metabolicCost)
        recordWordMastery(caption, result.responseBroca)

        return totalLoss
    }

    /**
     * Ported from `execution/trainer.py::BrainTrainer.record_word_mastery` -- see [vocabMastery]'s
     * doc comment for what this is for. Averages Broca's-area synaptic permanence over the input
     * axis for every neuron whose mean spike rate (over the whole forward pass) cleared
     * [AetherTuning.trainerWordMasteryActiveThreshold], then records that average as [word]'s
     * mastery score if it's the highest seen for that word so far.
     */
    private fun recordWordMastery(word: String, brocaSpikes: SpikeSequence) {
        val clean = word.trim().uppercase()
        if (clean.isEmpty()) return

        val numNeurons = brocaSpikes.c
        val meanSpikes = FloatArray(numNeurons)
        for (t in 0 until brocaSpikes.steps) for (i in 0 until numNeurons) meanSpikes[i] += brocaSpikes[t].data[i]
        for (i in 0 until numNeurons) meanSpikes[i] /= brocaSpikes.steps

        val threshold = AetherTuning.trainerWordMasteryActiveThreshold
        val permanence = (brain.frontalLanguage.brocasArea.layers[0] as LIFCortexLayer).permanence

        var weightedSum = 0f
        var activeCount = 0f
        for (j in 0 until numNeurons) {
            if (meanSpikes[j] <= threshold) continue
            var permSum = 0f
            for (i in 0 until permanence.rows) permSum += permanence[i, j]
            weightedSum += permSum / max(1, permanence.rows)
            activeCount += 1f
        }
        if (activeCount <= 0f) return

        val currentScore = weightedSum / activeCount
        vocabMastery[clean] = max(vocabMastery.getOrDefault(clean, 0f), currentScore)
    }

    /**
     * Held-out evaluation: a forward pass and loss computation with NO learning side effects --
     * no STDP, no hebbian trace update, no homeostatic regulation, no dopamine/posWeight/
     * metabolicCost mutation. Mirrors `execution/trainer.py::BrainTrainer.evaluate_step`
     * field-for-field, including the same subtlety: [AetherConnectome.forward] itself touches a
     * few pieces of dynamic runtime state that are not learning per se (dopamine level, the
     * habituation pupil, and the two reentrant-feedback traces) but DO persist across calls, so
     * without snapshot/restore a validation or test sample would leave a footprint on the
     * training trajectory that follows it. Used for `dataset/validation/` and `dataset/testing/`.
     */
    fun evaluateStep(visual: SpikeSequence, audio: SpikeSequence, target: SpikeSequence, visualTarget: SpikeSequence = visual): Float {
        val savedDopamine = brain.dopamineLevel
        val savedPupil = brain.habituationPupil
        val savedPfc = brain.prevPfcSpikes.copyOf()
        val savedBroca = brain.prevBrocaSpikes.copyOf()

        brain.resetState()
        val result = brain.forward(visual, audio)
        val (textLoss, _) = characterLossAndDelta(result.responseBroca, target)
        val vLoss = visualLoss(result.imaginedVisual, visualTarget)
        val totalLoss = textLoss + vLoss

        brain.dopamineLevel = savedDopamine
        brain.habituationPupil = savedPupil
        brain.prevPfcSpikes = savedPfc
        brain.prevBrocaSpikes = savedBroca
        brain.resetState()

        return totalLoss
    }

    /**
     * Runs [evaluateStep] over every item in [dataset] and returns `(averageLoss, count)`, or
     * `(null, 0)` if [dataset] is empty. Same function for validation (called periodically,
     * DURING [train]) and testing (called once, after it finishes) -- the two differ only in
     * when the caller invokes this and what it does with the result, never in how the pass
     * itself works.
     */
    fun runHeldOutEvaluation(dataset: List<DatasetItem>, timeSteps: Int, label: String): Pair<Float?, Int> {
        if (dataset.isEmpty()) return null to 0
        var total = 0f
        for (item in dataset) {
            val (visual, target) = when (item) {
                is DatasetItem.ImageItem -> {
                    val pixels = loader.loadImage(item.file, bioTrainMode = false, epoch = 1)
                    tokenizer.processImage(pixels, timeSteps) to tokenizer.processTextAsAudio(item.caption, timeSteps)
                }
                is DatasetItem.TextItem -> {
                    readVisually(item.promptChunk, timeSteps) to
                        tokenizer.processTextAsAudio(item.targetChunk, timeSteps)
                }
            }
            val audio = SpikeSequence(timeSteps, brain.auditoryInputDim, 1, 1)
            total += evaluateStep(visual, audio, target)
        }
        val avg = total / dataset.size
        AetherLog.info(AetherLog.Area.TRAIN, "[$label] ${dataset.size} sample(s) | avg loss %.4f".format(avg))
        return avg to dataset.size
    }

    /** Legacy backprop -- see class doc for the honesty note on its (real, but narrower) scope. */
    private fun trainBackprop(visual: SpikeSequence, audio: SpikeSequence, target: SpikeSequence, visualTarget: SpikeSequence): Float {
        brain.resetState()
        val result = brain.forward(visual, audio)
        val (textLoss, dBroca) = characterLossAndDelta(result.responseBroca, target)
        val vLoss = visualLoss(result.imaginedVisual, visualTarget)
        val totalLoss = textLoss + vLoss + result.internalDensity * metabolicCost

        brain.frontalLanguage.brocasArea.zeroGrad()
        brain.frontalLanguage.brocasArea.backward(dBroca)
        brain.frontalLanguage.brocasArea.applyGradients(backpropLr)

        brain.updateHebbianTraces()
        brain.applyHomeostaticRegulation(result.regionalActivity)
        return totalLoss
    }

    fun sleepPhase(): Pair<Int, Int> {
        brain.dopamineLevel = 1.0f
        val pruned = brain.prune(0.005f)
        val grown = brain.grow(0.1f)
        AetherLog.info(AetherLog.Area.TRAIN, "Sleep complete -- pruned $pruned, grown $grown")
        return pruned to grown
    }
}
