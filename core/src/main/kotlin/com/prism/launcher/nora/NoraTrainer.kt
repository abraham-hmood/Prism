package com.prism.launcher.nora

import com.prism.core.PrismImage
import com.prism.launcher.aether.AetherParquet
import com.prism.launcher.training.DatasetSplits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

/**
 * Training: a wake phase that learns from images, and a sleep phase that consolidates.
 *
 * The two-phase structure is not decoration. Complementary Learning Systems (McClelland,
 * McNaughton & O'Reilly 1995) makes a specific claim: a system that learns quickly from single
 * experiences and a system that learns slowly from their statistics have to be SEPARATE,
 * because a single network that does both suffers catastrophic interference. The hippocampus
 * takes the fast path, cortex takes the slow one, and offline replay is the bridge.
 *
 * So Nora's wake phase does two different things at once -- it applies the local predictive-
 * coding rule to cortical links (slow, small steps) AND imprints the whole episode into the
 * hippocampus (fast, one shot). The sleep phase then replays hippocampal episodes interleaved,
 * training cortex on old material alongside new, and finishes with synaptic downscaling.
 *
 * DATASET CONVENTION: drop images into Prism/Nora/dataset/. The FILENAME is the caption --
 * "a red apple on a table.png" teaches exactly that. This matches what you already built for
 * AetherCortex, so existing datasets carry over unchanged.
 *
 * COMPUTE, HONESTLY: this is a phone running plain Kotlin with no GPU and no autodiff. Expect
 * roughly 1-3 seconds per training image per epoch on a Snapdragon 8 Gen 1. A hundred images
 * for twenty epochs is therefore an hour or two, best run plugged in. That is slow by the
 * standards of a GPU training run and fast by the standards of "impossible", which is what
 * training a diffusion model on this hardware would be.
 */
class NoraTrainer(
    private val brain: NoraBrain,
    /**
     * Draws a line of text as an image for the retina, or null when the host has no font
     * renderer (`:core` must not depend on Android Canvas or AWT). Injected by `:app` and
     * `:desktop` with the SAME renderer Aether reads with, so the two brains learn the same
     * letter shapes. Text items are skipped with a log line when this is null.
     */
    private val textRenderer: ((String) -> PrismImage)? = null,
) {

    data class Progress(
        val epoch: Int,
        val totalEpochs: Int,
        val sample: Int,
        val totalSamples: Int,
        val caption: String,
        /** Mean per-unit RMS prediction error. Healthy values sit well under 1. */
        val errorRms: Float,
        val phase: String
    )

    /**
     * One training sample.
     *
     * An image's caption is its filename. A TEXT item (from `.txt` or `.parquet`) carries the
     * line in [text] and is drawn by the injected renderer at training time rather than being
     * read as a picture from [file]; that is how a vision brain can learn written language at
     * all. `file` stays non-null so every log line and error still names its source.
     */
    data class DatasetItem(val file: File, val caption: String, val text: String? = null)

    /**
     * File counts for a status line. Cheap: no file is opened.
     *
     * `loadDataset()` now expands text into items, which is fine off the main thread but
     * not for a screen that just wants "how much data is there".
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

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
        private val TEXT_EXTENSIONS = setOf("txt")
        private val PARQUET_EXTENSIONS = setOf("parquet")
        private val DATASET_EXTENSIONS = IMAGE_EXTENSIONS + TEXT_EXTENSIONS + PARQUET_EXTENSIONS

        /** Words per drawn text sample. Short lines stay legible at the 128px retina edge. */
        const val TEXT_WORDS_PER_ITEM = 8

        /** All three splits; see [DatasetSplits] for how files are classified. */
        fun scanDataset(): DatasetSplits.Files =
            DatasetSplits.scan(NoraConfig.datasetDir(), DATASET_EXTENSIONS)

        fun summarize(): Summary {
            val splits = scanDataset()
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

        /** The TRAIN split. Held-out files are excluded so they stay held out. */
        fun loadDataset(): List<DatasetItem> = loadItems(scanDataset().train)

        /** Images, `.txt` and `.parquet` files in already-classified entries. */
        fun loadItems(entries: List<DatasetSplits.Entry>): List<DatasetItem> {
            val items = mutableListOf<DatasetItem>()
            for (entry in entries) {
                val file = entry.file
                when (file.extension.lowercase()) {
                    in IMAGE_EXTENSIONS -> items += DatasetItem(
                        file,
                        file.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim(),
                    )
                    in TEXT_EXTENSIONS -> items += loadTextFile(file)
                    in PARQUET_EXTENSIONS -> items += loadParquet(file, entry.relativePath)
                }
            }
            return items
        }

        private fun loadTextFile(file: File): List<DatasetItem> {
            val text = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { return emptyList() }
            return textItems(text, file)
        }

        /**
         * A parquet file as training samples.
         *
         * Chat-shaped files use their TURNS, not the raw `<|user|>` markup -- drawing the tags
         * would teach letter shapes that occur in the template rather than in language. A
         * plain prose parquet has no turns, so its string column falls back to the same
         * line/word chunking a `.txt` gets. This is the same reader Aether uses for parquet,
         * including its honest refusal of codecs it cannot decompress.
         */
        private fun loadParquet(file: File, label: String): List<DatasetItem> {
            val conversations = try {
                AetherParquet.conversations(file, label)
            } catch (e: Exception) {
                NoraLog.warn(NoraLog.Area.TRAIN, "Parquet: could not read ${file.name}: ${e.message}")
                emptyList()
            }

            val items = mutableListOf<DatasetItem>()
            if (conversations.isNotEmpty()) {
                for (conversation in conversations) {
                    for (turn in conversation.turns) {
                        items += textItems(turn.text, file)
                    }
                }
                if (items.isNotEmpty()) return items
            }

            return try {
                AetherParquet.strings(file).flatMap { textItems(it, file) }
            } catch (e: Exception) {
                NoraLog.warn(NoraLog.Area.TRAIN, "Parquet: no usable text in ${file.name}: ${e.message}")
                emptyList()
            }
        }

        /** Lines, split into short word groups. Blank lines and mark-up-only lines are dropped. */
        private fun textItems(blob: String, file: File): List<DatasetItem> {
            val out = mutableListOf<DatasetItem>()
            for (line in blob.lines()) {
                val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty() || words.none { w -> w.any { it.isLetter() } }) continue
                var start = 0
                while (start < words.size) {
                    val end = minOf(start + TEXT_WORDS_PER_ITEM, words.size)
                    val caption = words.subList(start, end).joinToString(" ")
                    out += DatasetItem(file, caption, caption)
                    start = end
                }
            }
            return out
        }
    }

    /**
     * Runs a full training session.
     *
     * @param focus optional word to prioritize -- examples whose caption contains it get a
     *              dopamine boost, so their synapses potentiate harder and gain permanence
     *              faster. This is directed curriculum learning through the reward channel
     *              rather than through the loss function.
     */
    /**
     * @param sandbox when true, nothing outside this call is touched: no connectome is loaded or
     *        saved, no pending feedback is applied, and Nora is not marked as trained. The
     *        self-test runs this way so that measuring a geometry cannot overwrite the brain the
     *        user actually uses -- checkpointing a throwaway run over a real connectome would be
     *        a spectacularly annoying way to lose an overnight session.
     * @param datasetOverride samples to train on instead of the user's dataset directory.
     */
    suspend fun train(
        epochs: Int,
        focus: String? = null,
        sandbox: Boolean = false,
        datasetOverride: List<DatasetItem>? = null,
        /** Epochs between held-out validation passes -- same meaning as Aether's argument. */
        validationIntervalEpochs: Int = 1,
        onProgress: (Progress) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val splitFiles = scanDataset()
        val dataset = datasetOverride ?: loadItems(splitFiles.train)
        // An explicit override is a self-contained set (the geometry self-test's synthetic
        // samples); pulling the user's held-out files into that run would only slow it down.
        val validationDataset = if (datasetOverride != null) emptyList() else loadItems(splitFiles.validation)
        val testDataset = if (datasetOverride != null) emptyList() else loadItems(splitFiles.test)
        if (dataset.any { it.text != null } && textRenderer == null) {
            NoraLog.warn(
                NoraLog.Area.TRAIN,
                "Text samples are present but no text renderer was supplied -- they will be skipped.",
            )
        }
        if (validationDataset.isNotEmpty()) {
            NoraLog.info(NoraLog.Area.TRAIN, "[Validation] ${validationDataset.size} held-out sample(s) from ${splitFiles.validation.size} file(s).")
        }
        if (testDataset.isNotEmpty()) {
            NoraLog.info(NoraLog.Area.TRAIN, "[Testing] ${testDataset.size} held-out sample(s) from ${splitFiles.test.size} file(s) -- evaluated once at the end.")
        }
        NoraLog.info(
            NoraLog.Area.TRAIN,
            "Training start — $epochs epochs, ${dataset.size} samples" +
                (focus?.let { ", focus \"$it\"" } ?: "") +
                (if (sandbox) ", SANDBOX" else "") + ". " + NoraLog.describeGeometry()
        )
        if (dataset.isEmpty()) {
            NoraLog.warn(NoraLog.Area.TRAIN, "No training samples found — nothing to train on")
            return@withContext "No training samples found in ${NoraConfig.datasetDir().absolutePath}.\n\n" +
                "Drop images in there and name each file after what it shows -- " +
                "\"a red apple on a table.png\" teaches exactly that -- or add .txt/.parquet " +
                "files, whose lines she reads as drawn text."
        }

        if (!sandbox) {
            NoraPersistence.load(brain)
            NoraHealth.reset()

            // Ratings made while the brain was busy land here, before the epochs start. Applying
            // them first is not just tidiness: a thumbs-up retags its episode for replay, and the
            // sleep phases in this very run are what act on that tag.
            val pending = NoraFeedback.applyPending(brain)
            if (pending > 0) {
                onProgress(Progress(0, epochs, 0, 0, "applied $pending pending rating(s)", 0f, "feedback"))
            }
        } else {
            NoraHealth.reset()
        }
        val rng = Random(System.currentTimeMillis())
        val javaRng = java.util.Random()
        val denoising = NoraConfig.denoisingEnabled
        var lastValidationError: Float? = null
        var lastTestError: Float? = null
        // NaN MEANS "NO MEASUREMENT YET", not zero. Zero is a perfectly good prediction error
        // and is also what an untouched accumulator reads as, which is why the old divergence
        // guard (`!lastError.isFinite()`) could never fire: it compared a freshly-initialised
        // finite value against a test for non-finite. A session where every image failed to
        // decode therefore ran to completion, reported 0 presentations, and saved anyway.
        var lastError = Float.NaN
        var measured = 0
        var trained = 0
        var unbound = 0
        var skipped = 0

        for (epoch in 0 until epochs) {
            // ── WAKE ────────────────────────────────────────────────────────
            val order = dataset.indices.shuffled(rng)
            for ((n, idx) in order.withIndex()) {
                coroutineContext.ensureActive()
                val item = dataset[idx]
                val bitmap = bitmapFor(item)
                if (bitmap == null) {
                    skipped++
                    if (skipped <= 3) {
                        NoraLog.warn(
                            NoraLog.Area.TRAIN,
                            if (item.text != null)
                                "Skipped ${item.file.name}: the text renderer produced no image"
                            else "Skipped ${item.file.name}: it is not an image this device can decode",
                        )
                    }
                    continue
                }

                val focused = focus != null && item.caption.contains(focus, ignoreCase = true)
                // Focused items get a dopamine boost, which raises the learning rate AND makes
                // their synapses accumulate permanence, so they survive the sleep-phase
                // downscaling that erodes everything else.
                brain.neuromod.setDopamine(if (focused) 2.2f else 1.0f)

                try {
                    // Three fixations per presentation: cortex learns the same object from
                    // different retinal projections, which is where position tolerance comes
                    // from. A single centred fixation would teach a position-specific code.
                    for (f in 0 until 3) {
                        val fx = 0.5f + (rng.nextFloat() - 0.5f) * 0.3f
                        val fy = 0.5f + (rng.nextFloat() - 0.5f) * 0.3f
                        if (denoising) {
                            // Corruption strength is drawn fresh per fixation, not fixed. That
                            // is the property that makes denoising score matching so
                            // well-conditioned: the same content is seen at many degradation
                            // levels, so what gets learned is the shape of the landscape rather
                            // than one point on it.
                            val strength = NoraConfig.DENOISE_MIN +
                                javaRng.nextFloat() * (NoraConfig.DENOISE_MAX - NoraConfig.DENOISE_MIN)
                            brain.perceiveDenoising(bitmap, fx, fy, strength, javaRng)
                        } else {
                            brain.perceive(bitmap, fx, fy)
                        }
                        brain.learn()
                    }
                    if (!brain.bindCaption(item.caption, reward = if (focused) 2f else 1f)) {
                        unbound++
                    }
                    lastError = brain.lastErrorRms
                    measured++
                    trained++
                } catch (e: OutOfMemoryError) {
                    NoraLog.fatal(
                        NoraLog.Area.TRAIN,
                        "Out of memory on ${item.file.name} at ${NoraConfig.geometry.signature()}",
                        e
                    )
                    throw e
                } catch (e: Exception) {
                    NoraLog.error(NoraLog.Area.TRAIN, "Failed on ${item.file.name}", e)
                } finally {
                }

                // Abort on divergence rather than spending an hour writing NaN to disk. The
                // error readout going NaN used to be the only symptom, and it was easy to
                // mistake for a display quirk while every weight in the connectome rotted.
                val errorIsBad = measured > 0 && !lastError.isFinite()
                if (!NoraHealth.healthy || errorIsBad) {
                    if (errorIsBad) {
                        NoraHealth.report("prediction error became non-finite")
                    }
                    NoraLog.error(
                        NoraLog.Area.TRAIN,
                        "Diverged at epoch ${epoch + 1}, image ${n + 1}/${dataset.size} " +
                            "(\"${item.caption}\"). First fault: ${NoraHealth.firstFault}"
                    )
                    return@withContext NoraHealth.explain()
                }

                onProgress(
                    Progress(epoch + 1, epochs, n + 1, dataset.size, item.caption, lastError, "wake")
                )
            }

            // ── SLEEP ───────────────────────────────────────────────────────
            // Every third epoch, mirroring the fact that consolidation happens periodically
            // rather than continuously.
            if ((epoch + 1) % 3 == 0) {
                onProgress(Progress(epoch + 1, epochs, 0, dataset.size, "consolidating", lastError, "sleep"))
                sleepPhase(rng)
            }

            // ── HELD-OUT VALIDATION ─────────────────────────────────────────
            // Real measurement, not a training signal: the forward pass runs with the expert
            // router's learning disabled and nothing calls learn()/bindCaption().
            if (validationDataset.isNotEmpty() && epoch % validationIntervalEpochs.coerceAtLeast(1) == 0) {
                val (avgError, count) = evaluateHeldOut(validationDataset)
                if (avgError != null) {
                    lastValidationError = avgError
                    onProgress(
                        Progress(
                            epoch + 1, epochs, count, count,
                            "validation avg err %.4f".format(avgError), avgError, "validation",
                        )
                    )
                }
            }

            NoraLog.info(
                NoraLog.Area.TRAIN,
                "Epoch ${epoch + 1}/$epochs complete — err(rms) %.4f, ".format(lastError) +
                    "${brain.semanticHub.knownWords()} words, " +
                    "${brain.hippocampus.episodeCount()} episodes, " +
                    "V1->retina surface %.2f / contrast %.2f".format(
                        brain.surfacePathwayStrength(), brain.contrastPathwayStrength()
                    )
            )
            // SAVING IS A CLAIM THAT SOMETHING WAS LEARNED. If every presentation was unbound,
            // the connectome is byte-for-byte the one that was loaded, so checkpointing it and
            // calling markTrained() tells /status "trained" about a brain that learned nothing.
            if (!sandbox && trained > 0 && unbound < trained) {
                NoraPersistence.save(brain)
                if (NoraHealth.healthy) NoraPersistence.markTrained()
            }
        }

        // ── FINAL TEST ─────────────────────────────────────────────────────
        // Once, after every training decision has been made. Never fed back into learning.
        if (testDataset.isNotEmpty()) {
            val (avgError, count) = evaluateHeldOut(testDataset)
            if (avgError != null) {
                lastTestError = avgError
                onProgress(
                    Progress(
                        epochs, epochs, count, count,
                        "final test avg err %.4f".format(avgError), avgError, "test",
                    )
                )
            }
        }

        if (trained == 0) {
            NoraLog.error(
                NoraLog.Area.TRAIN,
                "Training produced no usable presentations ($skipped image(s) skipped)",
            )
            return@withContext buildString {
                append("Training did NOT run: none of the ${dataset.size} image(s) could be ")
                append("decoded and presented.\n\n")
                append("Nothing was saved and Nora was not marked as trained.\n")
                if (skipped > 0) {
                    append("$skipped file(s) were skipped as unreadable; check that they are ")
                    append("real PNG/JPEG images rather than renamed files.\n")
                }
            }
        }

        val learnedNothing = unbound == trained
        if (learnedNothing) {
            NoraLog.error(
                NoraLog.Area.TRAIN,
                "All $trained presentations ran but none produced an IT pattern",
            )
        }

        buildString {
            if (learnedNothing) {
                append("Training ran but taught nothing.\n\n")
            } else {
                append("Training complete.\n\n")
            }
            append("$trained presentations over $epochs epochs on ${dataset.size} training sample(s).")
            if (skipped > 0) append(" $skipped sample(s) skipped as unreadable.")
            if (validationDataset.isNotEmpty()) {
                append("\nHeld-out validation: ${validationDataset.size} sample(s) from ${splitFiles.validation.size} file(s)")
                if (lastValidationError != null) append(", final avg err %.4f".format(lastValidationError))
                append(".")
            }
            if (testDataset.isNotEmpty()) {
                append("\nHeld-out test: ${testDataset.size} sample(s) from ${splitFiles.test.size} file(s)")
                if (lastTestError != null) append(", avg err %.4f".format(lastTestError))
                append(".")
            }
            append("\n")
            append("Vocabulary: ${brain.semanticHub.knownWords()} words.\n")
            append("Episodes stored: ${brain.hippocampus.episodeCount()}.\n")
            if (measured > 0) {
                append("Final prediction error (rms): %.4f\n".format(lastError))
            } else {
                append("Final prediction error (rms): not measured\n")
            }
            if (learnedNothing) {
                append("\nEvery presentation failed to produce an IT pattern, so the connectome ")
                append("did not change. This was not saved as a trained brain.\n")
            }
            if (sandbox) {
                append("Sandbox run — nothing was saved.")
            } else {
                append("Connectome: ${NoraPersistence.sizeBytes() / 1024} KB on disk.")
            }
            if (unbound > 0) {
                NoraLog.warn(
                    NoraLog.Area.TRAIN,
                    "$unbound of $trained presentations produced no IT pattern and taught nothing"
                )
                // Surfaced rather than swallowed: a high count here means IT was not producing
                // a pattern to associate captions with, so those presentations taught nothing
                // even though they ran. This is what a silently-empty vocabulary looks like
                // from the outside.
                append("\n\nWarning: $unbound of $trained presentations produced no IT pattern ")
                append("and taught nothing. If that is most of them, generation will be blank.")
            }
        }
    }

    /**
     * Offline consolidation.
     *
     * Replays hippocampal episodes with no sensory input at all: the stored IT pattern is
     * clamped, the hierarchy generates from it, and the resulting internal prediction errors
     * drive the same local learning rule the wake phase used. Cortex is learning from the
     * hippocampus's record rather than from the world, which is the whole point of replay.
     *
     * Interleaving matters here -- episodes are sampled at random from the whole store, so a
     * batch of new images gets consolidated alongside old ones rather than on its own.
     */
    private suspend fun sleepPhase(rng: Random) {
        val episodes = brain.hippocampus.sampleForReplay(NoraTuning.replayBatch, rng)
        for (ep in episodes) {
            coroutineContext.ensureActive()
            brain.neuromod.setDopamine(0.8f + 0.4f * ep.reward.coerceIn(0f, 2f))
            try {
                brain.imagine(ep.itPattern, iterations = NoraTuning.replaySettleIterations)
                // The imagery pass leaves real prediction errors in the L2/3 buffers -- the
                // mismatch between what the analytic encoders extract from the generated image
                // and what the links predicted. Learning on those is what sharpens generation.
                brain.learn(rate = NoraConfig.LEARN_RATE * 0.6f)
                brain.semanticHub.bind(ep.semantic, ep.itPattern, rate = NoraTuning.replayBindRate)
            } catch (e: Exception) {
                NoraLog.error(NoraLog.Area.TRAIN, "Sleep-phase replay failed on \"${ep.caption}\"", e)
            }
        }
        // Synaptic downscaling closes the sleep cycle (Tononi & Cirelli 2014).
        brain.sleepCycle()
        brain.neuromod.setDopamine(1f)
    }

    /**
     * The image a sample presents to the retina: the file itself for an image, a drawing of
     * the line for a text sample.
     */
    private fun bitmapFor(item: DatasetItem): PrismImage? {
        val text = item.text
        if (text != null) {
            val renderer = textRenderer ?: return null
            return try {
                renderer(text)
            } catch (e: Exception) {
                NoraLog.error(NoraLog.Area.TRAIN, "Could not draw text from ${item.file.name}", e)
                null
            }
        }
        return decodeScaled(item.file)
    }

    /**
     * A forward-only pass over held-out samples.
     *
     * NOTHING HERE MAY LEARN. `perceive(learning = false)` stops the expert router from moving
     * its prototypes, and neither `learn()` nor `bindCaption()` is called, so the connectome
     * that walks out of this function is byte-for-byte the one that walked in. That is what
     * makes the number a generalisation measurement rather than another training step.
     */
    private suspend fun evaluateHeldOut(items: List<DatasetItem>): Pair<Float?, Int> {
        var total = 0.0
        var count = 0
        for (item in items) {
            coroutineContext.ensureActive()
            val bitmap = bitmapFor(item) ?: continue
            try {
                brain.evaluateHeldOut(bitmap)
                val error = brain.lastErrorRms
                if (error.isFinite()) {
                    total += error
                    count++
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                NoraLog.error(NoraLog.Area.TRAIN, "Held-out sample failed: ${item.file.name}", e)
            }
        }
        return if (count > 0) Pair((total / count).toFloat(), count) else Pair(null, 0)
    }

    /**
     * Decodes an image down to roughly canvas scale before it ever hits the retina.
     * A 12-megapixel photo decoded at full size would be most of the app's heap, and the
     * log-polar sampler cannot use the resolution anyway.
     */
    private fun decodeScaled(file: File): PrismImage? =
        com.prism.core.PrismPlatform.images.decode(file, NoraConfig.CANVAS * 4)
}
