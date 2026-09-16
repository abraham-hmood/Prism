package com.prism.launcher.nora

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Ascending neuromodulatory systems: acetylcholine, noradrenaline, dopamine.
 *
 * The organizing idea is Yu & Dayan's (2005) distinction between two kinds of uncertainty and
 * the two transmitters that signal them:
 *
 *   ACETYLCHOLINE  — EXPECTED uncertainty. The world is noisy in a way the model already knows
 *                    about. ACh raises the gain on prediction-error units, so bottom-up
 *                    evidence is weighted more heavily relative to top-down expectation.
 *                    Physiologically this matches ACh's suppression of intracortical feedback
 *                    while sparing thalamocortical drive (Hasselmo & McGaughy 2004).
 *
 *   NORADRENALINE  — UNEXPECTED uncertainty. Something has changed that the model has no
 *                    account of. NE drives a network RESET (Bouret & Sara 2005): the current
 *                    hypothesis is abandoned rather than incrementally corrected. In Nora this
 *                    is a partial wipe of the representation, forcing inference to restart from
 *                    the sensory evidence instead of grinding on a hypothesis that has already
 *                    failed. This is the mechanism that stops generation from getting stuck.
 *
 *   DOPAMINE       — reward prediction error (Schultz, Dayan & Montague 1997). Nora uses it as
 *                    a learning-rate modulator, not as a reinforcement signal in the RL sense:
 *                    examples that reduce error more than expected are learned from harder.
 *
 * HONESTY FLAG: the mapping from "one scalar per transmitter" to real neuromodulation is
 * coarse to the point of caricature. These systems have topographically differentiated
 * projections, multiple receptor subtypes with opposite effects, and timescales spanning
 * milliseconds to hours. Yu & Dayan's framework is a computational-level theory, and Nora
 * implements the computational level only. Nothing here should be read as a claim about basal
 * forebrain or locus coeruleus physiology.
 */
class Neuromodulators {

    /** Gain applied to prediction-error units. 1.0 = baseline. */
    var acetylcholine = 1.0f
        private set

    /** Reset pressure, 0..1. Above [resetThreshold] a network reset fires. */
    var noradrenaline = 0.0f
        private set

    /** Learning-rate multiplier. Tonic level plus the phasic reward prediction error. */
    var dopamine = 1.0f
        private set

    /**
     * Tonic dopamine: the baseline the phasic signal rides on.
     *
     * Split out because it was being silently destroyed. The trainer sets a raised level for a
     * focused curriculum item, then calls perceive() -- which runs settle(), which calls
     * [update], which recomputed dopamine from scratch as `1 + phasic`. By the time learn()
     * read it, the curriculum boost was gone. The focus feature therefore did nothing at all,
     * and because permanence only accrues above baseline, nothing in an unfocused run was ever
     * protected from sleep-phase pruning either.
     */
    private var tonicDopamine = 1.0f

    /** Running expectation of prediction-error magnitude, for surprise detection. */
    private var expectedError = -1f
    private var errorVariance = 0.05f

    private val resetThreshold = 0.75f

    fun reset() {
        acetylcholine = 1.0f
        noradrenaline = 0.0f
        tonicDopamine = 1.0f
        dopamine = 1.0f
        expectedError = -1f
        errorVariance = 0.05f
    }

    /**
     * Updates the modulator state from the current prediction error.
     *
     * [errorRms] must be a PER-UNIT root-mean-square error, not a summed energy. The original
     * version consumed a raw sum of squares over ~70,000 units -- a number in the thousands --
     * and tracked its variance by squaring the surprise. One genuine spike pushed that past
     * float range, `Infinity - Infinity` produced NaN, and since acetylcholine multiplies into
     * every region's error computation, a single NaN here poisoned the entire network in one
     * step and every learning rate with it. On an RMS scale the same statistics live around
     * 0.2 and cannot overflow.
     *
     * @return true when a noradrenergic network reset should fire this step.
     */
    fun update(errorRms: Float): Boolean {
        if (!errorRms.isFinite()) {
            // Do not propagate. Fall back to neutral gains so the rest of the pass stays
            // numerically usable and the fault is reported once, at its source.
            NoraHealth.report("prediction error reaching the neuromodulators was non-finite")
            neutralize()
            return false
        }

        if (expectedError < 0f) {
            expectedError = errorRms
            return false
        }

        val surprise = errorRms - expectedError
        val sd = kotlin.math.sqrt(max(0f, errorVariance))
        val normalizedSurprise = surprise / max(1e-5f, sd)

        // ACh tracks EXPECTED uncertainty. Keyed to the coefficient of variation rather than
        // raw variance, which makes it scale-free: what matters is how noisy the error is
        // relative to its own typical size, not its absolute magnitude in arbitrary units.
        val cv = sd / max(1e-5f, expectedError)
        val targetAch = 1f + 0.6f * (1f - exp(-cv * 4f))
        acetylcholine += 0.2f * (targetAch - acetylcholine)

        // NE tracks UNEXPECTED uncertainty: a single large, unanticipated jump in error.
        noradrenaline = if (normalizedSurprise > 2f) {
            (noradrenaline + 0.5f * (normalizedSurprise - 2f)).coerceAtMost(1f)
        } else {
            noradrenaline * 0.7f
        }

        // DA: better-than-expected error reduction is rewarding, and the resulting learning-rate
        // boost is what makes the trainer converge faster on examples it is actually making
        // progress on.
        // Phasic RPE rides on the TONIC level rather than replacing it, so a curriculum or
        // feedback signal set before this pass survives it.
        val improvement = -surprise / max(1e-5f, expectedError)
        dopamine = tonicDopamine + 1.2f * improvement.coerceIn(-0.5f, 1.0f)

        // Exponentially-weighted running statistics, on a scale that cannot overflow.
        expectedError += 0.15f * surprise
        errorVariance += 0.1f * (surprise * surprise - errorVariance)

        // Clamp AFTER every assignment, and treat a non-finite result as a fault rather than
        // letting it ride. coerceIn passes NaN straight through, because every comparison
        // against NaN is false -- which is exactly how the old code laundered one bad value
        // into a dead network.
        if (!sanitize()) return false

        val fire = noradrenaline > resetThreshold
        if (fire) noradrenaline = 0f
        return fire
    }

    /** Bounds every modulator and reports if any went non-finite. @return true if healthy. */
    private fun sanitize(): Boolean {
        var ok = true
        if (!acetylcholine.isFinite() || !noradrenaline.isFinite() || !dopamine.isFinite() ||
            !expectedError.isFinite() || !errorVariance.isFinite()
        ) {
            NoraHealth.report("neuromodulator state became non-finite")
            neutralize()
            ok = false
        } else {
            acetylcholine = acetylcholine.coerceIn(0.5f, 2.0f)
            noradrenaline = noradrenaline.coerceIn(0f, 1f)
            dopamine = dopamine.coerceIn(0.4f, 2.5f)
        }
        return ok
    }

    /** Neutral gains: the state the network behaves sanely in when statistics are unusable. */
    private fun neutralize() {
        acetylcholine = 1f
        noradrenaline = 0f
        dopamine = 1f
        expectedError = -1f
        errorVariance = 0.05f
    }

    /**
     * Direct dopamine control, used by the trainer for focused/rewarded curriculum items and by
     * the feedback path for a rating.
     *
     * Sets the TONIC level, so it survives the settle that follows rather than being recomputed
     * away by [update].
     */
    fun setDopamine(value: Float) {
        tonicDopamine = value.coerceIn(0.4f, 2.5f)
        dopamine = tonicDopamine
    }

    /**
     * Executes the reset: partially wipes representations so inference restarts from evidence.
     * Not a full wipe -- LC activation reorganizes rather than erases, and a total wipe would
     * throw away the top-level concept that generation depends on.
     */
    fun applyReset(regions: List<CorticalRegion>, strength: Float = 0.6f) {
        for (r in regions) {
            if (r.clamped) continue
            r.representation.scale(1f - strength)
        }
    }

    fun describe(): String =
        "ACh %.2f  NE %.2f  DA %.2f".format(acetylcholine, noradrenaline, dopamine)
}
