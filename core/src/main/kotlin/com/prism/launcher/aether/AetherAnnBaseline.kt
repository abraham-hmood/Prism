package com.prism.launcher.aether

import com.prism.launcher.messaging.GgufInferenceService
import kotlin.math.ln
import kotlin.random.Random

/**
 * Experimental "ANN baseline conversion" -- Kotlin mirror of AetherCortex's brain/ann_baseline.py
 * (see that file's module docstring for the full design). Turns an imported .gguf text-generation
 * model (Sam's active local text model -- see AetherSettingsActivity's gate on this feature's
 * settings checkbox) into a smarter starting point for a FRESH, never-trained Aether connectome,
 * via a single, non-iterative calibration pass. Not a weight transplant: a transformer's layer
 * shapes have no correspondence to AetherGeometry's regions, and the existing AKEF-style importer
 * ([AetherAkef.importNamed]) requires an exact shape match anyway, so a literal transplant would
 * be a near-total no-op even if attempted.
 *
 * Every signal below is optional except attention, and every one of them folds into the SAME
 * per-ASCII-character gain table already threaded through training (see
 * [AetherTokenizer.processTextAsAudio]) -- see brain/ann_baseline.py's module docstring for the
 * full description of each: ATTENTION (always on), SOFT-TARGET DISTILLATION (automatic whenever
 * a local text model is active -- classical knowledge distillation, not attention), SURPRISAL
 * WEIGHTING (optional, reads the same soft-target data a different way), and ATTENTION
 * CO-OCCURRENCE STRUCTURAL PRIOR (optional, a genuinely pairwise/directional prior on Broca's
 * area's initial weights -- see [AetherBrain.applyCooccurrencePrior]).
 *
 * Calls the SAME native `gguf_bridge` library already linked into this app for Sam's own GGUF
 * chat inference ([GgufInferenceService]), through the calibration-specific JNI exports added
 * alongside the chat ones (`nativeLoadCalibrationModel`/`nativeRunCalibrationPass`/
 * `nativeFreeCalibrationModel`) -- see `gguf_bridge.cpp`'s header comment for exactly how
 * attention (and, when requested, soft targets) are extracted, with flash-attention forced off so
 * the softmax weights are actually inspectable.
 */
object AetherAnnBaseline {

    class CalibrationResult(
        val weightScale: Float,
        val charGain: FloatArray,
        /** (charA, charB) -> 0..1 strength, charA < charB. Empty unless [useCooccurrencePrior]. */
        val cooccurrence: Map<Pair<Int, Int>, Float> = emptyMap()
    )

    fun isAvailable(): Boolean = GgufInferenceService.isAvailable()

    /**
     * Plain random sample, capped at [maxSamples] -- NOT weighted by word mastery. That dict
     * ([AetherTrainer.vocabMastery]) is necessarily empty at this point (the calibration pass
     * runs before any training happens, by definition, so nothing has been learned yet to weight
     * by) -- see [AetherTokenizer.processTextAsAudio] for where word mastery actually becomes
     * relevant to this feature instead: fading the calibrated gain back to neutral, per word, as
     * training makes it genuinely unnecessary.
     */
    private fun sampleCaptions(captions: List<String>, maxSamples: Int): List<String> {
        if (captions.isEmpty()) return emptyList()
        if (captions.size <= maxSamples) return captions
        return captions.shuffled(Random(System.nanoTime())).take(maxSamples)
    }

    /** Shared by every per-character signal: averages each character's accumulated
     * observations, then normalizes into a 0.5..1.5 band around the neutral 1.0 -- a gain, not a
     * mask. A character never observed stays at 1.0 (neutral), not 0.0, matching
     * brain/ann_baseline.py's `_normalize_band`. */
    private fun normalizeBand(sum: DoubleArray, count: IntArray): FloatArray {
        val values = FloatArray(256) { b -> if (count[b] > 0) (sum[b] / count[b]).toFloat() else 0f }
        val observed = values.filter { it > 0f }
        if (observed.isEmpty()) return FloatArray(256) { 1.0f }
        val vMin = observed.min()
        val vMax = observed.max()
        val spread = vMax - vMin
        return FloatArray(256) { b ->
            when {
                values[b] <= 0f -> 1.0f
                spread <= 1e-6f -> 1.0f
                else -> 0.5f + (values[b] - vMin) / spread
            }
        }
    }

    /**
     * Runs the calibration pass and returns the aggregated result, or null if the native
     * library/model/dataset couldn't be used -- never throws; the caller always falls back to
     * ordinary training on null.
     *
     * [useSoftTargetDistill] is automatic (passed true) whenever this function is even called --
     * see [AetherStudio.train]'s own gate ("only done when a local text model is loaded and
     * active"), which is the sole condition this whole function already runs under.
     * [useSurprisalWeighting]/[useCooccurrencePrior] are the two optional signals, each gated by
     * its own Settings checkbox.
     */
    fun runCalibration(
        modelPath: String,
        datasetCaptions: List<String>,
        maxSamples: Int = 24,
        targetThreshold: Float = 1.0f,
        useSoftTargetDistill: Boolean = true,
        useSurprisalWeighting: Boolean = false,
        useCooccurrencePrior: Boolean = false,
        /** Every progress line, on top of the [AetherLog] calls this already makes -- lets a
         * caller in `:app` (which can see [com.prism.launcher.aether.AetherTrainingState], unlike
         * this `:core` file) forward calibration progress into Aether's training Log tab. */
        onProgress: (String) -> Unit = {}
    ): CalibrationResult? {
        fun info(msg: String) { AetherLog.info(AetherLog.Area.TRAIN, msg); onProgress(msg) }
        fun warn(msg: String) { AetherLog.warn(AetherLog.Area.TRAIN, msg); onProgress(msg) }

        if (!isAvailable()) {
            warn("AnnBaseline: gguf_bridge native library not available -- ignoring the ANN-baseline-conversion setting.")
            return null
        }

        info("AnnBaseline: loading $modelPath for calibration...")
        val handle = GgufInferenceService.nativeLoadCalibrationModel(modelPath, 4)
        if (handle == 0L) {
            warn("AnnBaseline: could not load $modelPath for calibration.")
            return null
        }
        info("AnnBaseline: $modelPath loaded.")

        // Soft-target distillation and surprisal weighting both read the SAME native per-position
        // teacher-forced probabilities -- only one extra native flag/array is needed regardless of
        // which one (or both) is requested.
        val wantSoftTargets = useSoftTargetDistill || useSurprisalWeighting

        try {
            val captions = sampleCaptions(datasetCaptions, maxSamples)
            if (captions.isEmpty()) {
                warn("AnnBaseline: no dataset captions to calibrate against -- skipping.")
                return null
            }

            // Packed result format from nativeRunCalibrationPass: [ok, nLayersObserved,
            // logitAbsMean, logitAbsP99, charSaliency[0..textLen-1], charSoftTarget[0..textLen-1]
            // (only present when wantSoftTargets)] -- see GgufInferenceService's doc comment.
            val charGainSum = DoubleArray(256)
            val charGainCount = IntArray(256)
            val softSum = DoubleArray(256)
            val softCount = IntArray(256)
            val surprisalSum = DoubleArray(256)
            val surprisalCount = IntArray(256)
            val cooccurrenceCounts = HashMap<Pair<Int, Int>, Float>()
            val logitAbsP99s = ArrayList<Float>()
            var successful = 0

            val total = captions.size
            val modes = mutableListOf("attention")
            if (useSoftTargetDistill) modes.add("soft-target distillation")
            if (useSurprisalWeighting) modes.add("surprisal weighting")
            if (useCooccurrencePrior) modes.add("co-occurrence structural prior")
            info(
                "AnnBaseline: calibrating against $modelPath -- $total sampled caption(s), " +
                    "${modes.joinToString(", ")}, one non-iterative pass..."
            )
            val startNanos = System.nanoTime()
            for ((idx, caption) in captions.withIndex()) {
                val n = idx + 1
                val bytes = caption.toByteArray(Charsets.UTF_8)
                if (bytes.isEmpty()) continue
                val packed = GgufInferenceService.nativeRunCalibrationPass(handle, caption, wantSoftTargets)
                val ok = packed.size >= 4 && packed[0] >= 0.5f
                val elapsedS = (System.nanoTime() - startNanos) / 1_000_000_000.0
                val remainingS = (elapsedS / n) * (total - n)
                info(
                    "AnnBaseline: caption $n/$total ${if (ok) "ok" else "skipped (no attention observed)"} " +
                        "-- %.1fs elapsed, ~%.1fs remaining.".format(elapsedS, remainingS)
                )
                if (!ok) continue
                successful++
                logitAbsP99s.add(packed[3])

                val saliencyLen = minOf(bytes.size, packed.size - 4)
                val softOffset = 4 + bytes.size
                val softLen = if (wantSoftTargets) minOf(bytes.size, packed.size - softOffset) else 0

                for (i in 0 until saliencyLen) {
                    val b = bytes[i].toInt() and 0xFF
                    charGainSum[b] += packed[4 + i]
                    charGainCount[b]++
                    if (wantSoftTargets && i < softLen) {
                        val pCorrect = packed[softOffset + i].coerceIn(1e-6f, 1.0f)
                        if (useSoftTargetDistill) {
                            softSum[b] += pCorrect
                            softCount[b]++
                        }
                        if (useSurprisalWeighting) {
                            surprisalSum[b] += -ln(pCorrect.toDouble())
                            surprisalCount[b]++
                        }
                    }
                }

                if (useCooccurrencePrior) {
                    // Characters this caption's attention treated as jointly salient (above THIS
                    // caption's own mean saliency) are treated as related -- counted once per
                    // caption they co-occur in, matching brain/ann_baseline.py exactly.
                    val meanSal = if (saliencyLen > 0) (0 until saliencyLen).sumOf { packed[4 + it].toDouble() } / saliencyLen else 0.0
                    val salientBytes = (0 until saliencyLen)
                        .filter { packed[4 + it] > meanSal }
                        .map { bytes[it].toInt() and 0xFF }
                        .toSortedSet()
                        .toList()
                    for (ia in salientBytes.indices) {
                        for (ib in ia + 1 until salientBytes.size) {
                            val key = salientBytes[ia] to salientBytes[ib]
                            cooccurrenceCounts[key] = (cooccurrenceCounts[key] ?: 0f) + 1f
                        }
                    }
                }
            }

            if (successful == 0) {
                warn("AnnBaseline: every calibration caption failed against $modelPath -- skipping.")
                return null
            }

            // Rueckauer/Diehl-style threshold balancing -- see brain/ann_baseline.py's
            // run_calibration for the full reasoning; identical formula here.
            val p99 = if (logitAbsP99s.isEmpty()) 0f else logitAbsP99s.sum() / logitAbsP99s.size
            val weightScale = if (p99 <= 1e-6f) 1.0f else (targetThreshold / p99).coerceIn(0.25f, 4.0f)

            var charGain = normalizeBand(charGainSum, charGainCount)
            val blended = mutableListOf("attention")
            if (useSoftTargetDistill && softCount.any { it > 0 }) {
                val softGain = normalizeBand(softSum, softCount)
                charGain = FloatArray(256) { b -> (charGain[b] + softGain[b]) / 2f }
                blended.add("soft-target")
            }
            if (useSurprisalWeighting && surprisalCount.any { it > 0 }) {
                val surprisalGain = normalizeBand(surprisalSum, surprisalCount)
                charGain = FloatArray(256) { b -> (charGain[b] + surprisalGain[b]) / 2f }
                blended.add("surprisal")
            }

            var cooccurrence: Map<Pair<Int, Int>, Float> = emptyMap()
            if (useCooccurrencePrior && cooccurrenceCounts.isNotEmpty()) {
                val maxCount = cooccurrenceCounts.values.max()
                cooccurrence = cooccurrenceCounts.mapValues { (_, v) -> v / maxCount }
            }

            val summary = "AnnBaseline: calibrated from $successful/${captions.size} sampled caption(s) against " +
                "$modelPath -- weight scale %.3f, mean p99 logit %.3f, gain blended from [%s]%s".format(
                    weightScale, p99, blended.joinToString(", "),
                    if (cooccurrence.isNotEmpty()) ", ${cooccurrence.size} co-occurring character pair(s)." else "."
                )
            AetherLog.success(AetherLog.Area.TRAIN, summary)
            onProgress(summary)
            return CalibrationResult(weightScale, charGain, cooccurrence)
        } finally {
            GgufInferenceService.nativeFreeCalibrationModel(handle)
        }
    }
}
