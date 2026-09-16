package com.prism.launcher.cakechat

/**
 * CakeChat's token sampler, reimplemented so it can run without Python.
 *
 * ## Why this is a faithful port rather than "something reasonable"
 *
 * This is the half of inference that fails QUIETLY. A model that will not load raises; a sampler
 * that penalises the wrong tokens, or normalises at the wrong moment, still returns fluent-looking
 * words -- just the wrong ones, and nothing anywhere reports a problem. So every step below mirrors
 * `TokenSampler.sample` in cakechat/dialog_model/inference/candidates/sampling.py exactly, in the
 * same order, including the parts that look redundant:
 *
 * ```python
 * repetition_penalize_coefficient = np.exp(np.log(coef) / temperature)
 * probabilities = np.copy(probabilities)
 * probabilities[self._banned_tokens_ids] = 0
 * probabilities[self._used_tokens_ids[sample_idx]] /= repetition_penalize_coefficient
 * probabilities /= np.sum(probabilities)
 * token_id = np.random.choice(probabilities.shape[0], replace=False, p=probabilities)
 * if token_id not in self._non_penalizable_tokens_ids:
 *     self._used_tokens_ids[sample_idx].append(token_id)
 * ```
 *
 * Two details there are easy to get wrong and neither announces itself:
 *
 *  * The penalty is applied ONCE PER DISTINCT TOKEN, not once per use. `used_tokens_ids` is a list
 *    that accumulates duplicates, but numpy's fancy-index division assigns rather than accumulates,
 *    so a token used five times is divided once, not five times. Penalising repeatedly would make
 *    the model progressively unable to reuse ordinary words like "the".
 *  * The coefficient is rescaled by temperature BEFORE use, so the strength of the penalty is
 *    invariant to temperature. Applying the raw coefficient makes low-temperature output collapse.
 *
 * ## Determinism
 *
 * [random] is injectable so a test can drive the same sequence Python's `np.random.choice` would,
 * and so greedy decoding can be compared against Python exactly. Nothing here reads a global clock
 * or a shared generator.
 */
class CakeChatSampler(
    private val bannedTokenIds: Set<Int>,
    private val nonPenalizableTokenIds: Set<Int>,
    private val repetitionPenalizeCoefficient: Double,
    private val random: () -> Double,
) {

    /** Distinct tokens already emitted in this response. Distinct, for the reason above. */
    private val used = LinkedHashSet<Int>()

    /** Forgets the tokens of the previous response; a sampler is per-response, like upstream's. */
    fun reset() = used.clear()

    /**
     * One token from a distribution.
     *
     * [probabilities] is not modified -- upstream copies it for the same reason, since the caller
     * reuses the model's output buffer.
     *
     * @param greedy takes the highest-probability token instead of sampling. Not a mode upstream
     *   has; it exists so the whole loop can be compared against Python's numbers without both
     *   sides having to draw identical random numbers. The filtering above it is unchanged, so a
     *   greedy run still exercises banning, penalisation and normalisation.
     */
    fun sample(probabilities: FloatArray, temperature: Double, greedy: Boolean = false): Int {
        require(temperature > 0.0) { "temperature must be positive" }

        val penalty = Math.exp(Math.log(repetitionPenalizeCoefficient) / temperature)
        val adjusted = DoubleArray(probabilities.size) { probabilities[it].toDouble() }

        for (id in bannedTokenIds) {
            if (id in adjusted.indices) adjusted[id] = 0.0
        }
        for (id in used) {
            if (id in adjusted.indices) adjusted[id] /= penalty
        }

        var total = 0.0
        for (value in adjusted) total += value
        if (total <= 0.0 || !total.isFinite()) {
            // Every candidate was banned or underflowed. Upstream would divide by zero here and
            // hand numpy a distribution of NaNs, which raises somewhere less informative; falling
            // back to the unfiltered argmax keeps the reply going and is reported by the caller.
            return probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        }
        for (i in adjusted.indices) adjusted[i] /= total

        val chosen = if (greedy) {
            adjusted.indices.maxByOrNull { adjusted[it] } ?: 0
        } else {
            // The inverse-CDF walk numpy performs. The final clamp matters: floating-point error
            // can leave the cumulative sum a hair under the drawn value, and without it a draw very
            // close to 1.0 would fall off the end of the array.
            val draw = random()
            var cumulative = 0.0
            var index = adjusted.size - 1
            for (i in adjusted.indices) {
                cumulative += adjusted[i]
                if (draw < cumulative) {
                    index = i
                    break
                }
            }
            index
        }

        if (chosen !in nonPenalizableTokenIds) used.add(chosen)
        return chosen
    }
}
