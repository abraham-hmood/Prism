package com.prism.launcher.aether

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ported from `brain/scoring.py` -- model quality score (1-100, higher is better) attached to
 * every trained connectome shared over the network. See that file's module docstring for the
 * full reasoning; kept here verbatim rather than re-derived, since the two platforms need to rank
 * peers consistently with each other. HEURISTIC, DOCUMENTED AS SUCH -- see the source file.
 */
object AetherScoring {

    private const val WEIGHT_VALIDATION_LOSS = 0.65f
    private const val WEIGHT_VOCAB_MASTERY = 0.20f
    private const val WEIGHT_DATASET_SIZE = 0.15f

    private const val VOCAB_BREADTH_SATURATION = 150f
    private const val DATASET_SIZE_SATURATION = 500f

    private const val SCORE_FLOOR = 1
    private const val SCORE_CEILING = 100

    private fun saturating(value: Float, saturation: Float): Float {
        if (value <= 0f || saturation <= 0f) return 0f
        return min(1f, ln(1f + value) / ln(1f + saturation))
    }

    /**
     * [vocabMastery]: [AetherTrainer.vocabMastery] at save time. [datasetSize]: number of training
     * images used. [validationLoss]: the last held-out validation/test average loss, if any (see
     * [AetherTrainer.runHeldOutEvaluation]'s return value).
     */
    fun computeScore(vocabMastery: Map<String, Float>, datasetSize: Int, validationLoss: Float?): Int {
        val vocabValues = vocabMastery.values
        val vocabBreadth = saturating(vocabValues.size.toFloat(), VOCAB_BREADTH_SATURATION)
        val vocabAvg = if (vocabValues.isNotEmpty()) vocabValues.sum() / vocabValues.size else 0f
        val vocabComponent = (vocabBreadth + vocabAvg.coerceIn(0f, 1f)) / 2f

        val datasetComponent = saturating(max(0, datasetSize).toFloat(), DATASET_SIZE_SATURATION)

        val raw = if (validationLoss != null && validationLoss >= 0f) {
            val accuracyComponent = exp(-validationLoss)
            WEIGHT_VALIDATION_LOSS * accuracyComponent +
                WEIGHT_VOCAB_MASTERY * vocabComponent +
                WEIGHT_DATASET_SIZE * datasetComponent
        } else {
            // No validation signal -- redistribute its weight across the two always-available
            // proxies rather than scoring every un-validated model low regardless of how it's
            // actually doing.
            val remaining = WEIGHT_VOCAB_MASTERY + WEIGHT_DATASET_SIZE
            (WEIGHT_VOCAB_MASTERY / remaining) * vocabComponent + (WEIGHT_DATASET_SIZE / remaining) * datasetComponent
        }

        val score = SCORE_FLOOR + raw * (SCORE_CEILING - SCORE_FLOOR)
        return score.coerceIn(SCORE_FLOOR.toFloat(), SCORE_CEILING.toFloat()).roundToInt()
    }
}
