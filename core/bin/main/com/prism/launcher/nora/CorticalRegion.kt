package com.prism.launcher.nora

/**
 * One cortical area, with its laminar microcircuit made explicit.
 *
 * The canonical microcircuit (Douglas & Martin 1991, 2004; Bastos et al. 2012) assigns
 * distinct roles to distinct layers, and predictive coding maps onto it as follows:
 *
 *   L4     — granular input layer. Receives the driving feedforward signal from the area below.
 *            Does NOT receive feedback: L4 is conspicuously avoided by top-down projections,
 *            which is one of the anatomical facts the feedforward/feedback asymmetry rests on
 *            (Felleman & Van Essen 1991's laminar criteria for hierarchy are built on it).
 *
 *   L2/3   — superficial layers. Hold the PREDICTION ERROR (the mismatch between what arrived
 *            at L4 and what was predicted), plus lateral connections implementing normalization
 *            and competition. Project FORWARD to L4 of the next area up.
 *
 *   L5/6   — deep layers. Hold this area's REPRESENTATION -- its current best hypothesis about
 *            its part of the world. Project BACKWARD to L1 of the area below, where that
 *            hypothesis becomes a prediction. L6 additionally projects to thalamus, which is
 *            how the gain/precision loop closes.
 *
 * HONESTY FLAG — THIS IS THE MOST CONTESTED PART OF NORA. Predictive coding is a well-developed
 * theory with real laminar and spectral support (Bastos et al. 2015; Michalareas et al. 2016),
 * but it is a theory, and the criticism is substantive: it is not established that cortex
 * computes explicit subtractive prediction errors, dedicated error units have not been
 * unambiguously identified, and alternative accounts of the same laminar data exist (see
 * Kogo & Trengove 2015; Heilbron & Chait 2018; Walsh et al. 2020 for careful critiques).
 * Nora commits to the strong version because it is implementable and because it makes
 * generation and perception the same machinery. That commitment is a bet, not a fact.
 */
class CorticalRegion(
    val name: String,
    val c: Int,
    val h: Int,
    val w: Int
) {

    /** L4: driving feedforward input. */
    val l4 = Tensor3(c, h, w)

    /** L2/3: prediction error. Ascends. */
    val error = Tensor3(c, h, w)

    /** L5/6: the representation. Descends as prediction. */
    val representation = Tensor3(c, h, w)

    /** The prediction this area received from above, held in L1. */
    val topDown = Tensor3(c, h, w)

    /** Scratch for the prediction this area makes OF the area below. */
    var predictionOfBelow: Tensor3? = null

    /** Scratch for ascending error driving this area's representation. */
    val ascending = Tensor3(c, h, w)

    /**
     * Per-channel precision: the inverse variance the model assigns to this area's prediction
     * errors. Attention IS precision weighting (Feldman & Friston 2010) -- attending to
     * something means trusting its prediction errors more, which increases their influence on
     * inference. Acetylcholine sets this (Yu & Dayan 2005).
     */
    val precision = FloatArray(c) { 1f }

    /** Whether the representation is externally pinned (clamped) this pass. */
    var clamped = false

    fun reset() {
        l4.zero()
        error.zero()
        representation.zero()
        topDown.zero()
        ascending.zero()
        clamped = false
    }

    /**
     * Computes L2/3 error: what arrived at L4 minus what was predicted, scaled by precision.
     * [prediction] is the top-down prediction of THIS area, made by the area above.
     */
    fun computeError(prediction: Tensor3?, achGain: Float) {
        if (prediction == null) {
            // Top of the hierarchy: no area above, so the prior is the (empty) default state
            // and the "error" is just the representation's departure from silence. This is
            // what implements the sparse prior at the apex.
            for (i in error.data.indices) error.data[i] = 0f
            return
        }
        for (ci in 0 until c) {
            val g = precision[ci] * achGain
            val base = ci * error.plane
            for (i in 0 until error.plane) {
                error.data[base + i] = (l4.data[base + i] - prediction.data[base + i]) * g
            }
        }
    }

    /**
     * Rao & Ballard's representation update (1999, eq. 6): move toward reducing the error in
     * the area BELOW (which this area is responsible for predicting) while staying close to
     * what the area ABOVE predicts of this one.
     *
     * @param fromBelow ascending, error-driven drive; null for the lowest area
     * @param rate      integration rate
     */
    fun updateRepresentation(
        fromBelow: Tensor3?,
        rate: Float,
        priorStrength: Float = 0.5f
    ) {
        if (clamped) return
        for (i in representation.data.indices) {
            var d = 0f
            if (fromBelow != null) d += fromBelow.data[i]
            // Pull toward the prior from above. This term is what makes the hierarchy
            // generative rather than merely a stack of feature detectors -- and during imagery
            // it is the ONLY thing keeping the clamped concept in play, so the caller raises
            // [priorStrength] there. Raising it is also unconditionally stabilizing: it is a
            // contraction toward topDown, so a larger value shrinks the update's spectral
            // radius rather than growing it.
            d -= (representation.data[i] - topDown.data[i]) * priorStrength
            // Leak: the standing prior that most cortical units are silent most of the time.
            d -= representation.data[i] * NoraConfig.PC_DECAY
            representation.data[i] += rate * d
        }
        Normalization.lateralInhibition(representation)
        representation.rectify()
        // Firing-rate ceiling. Without it the representation is an unbounded quantity inside a
        // recurrent loop whose stability depends on a weight spectrum that learning is actively
        // growing -- which is precisely how this diverged.
        representation.saturate(NoraConfig.MAX_FIRING_RATE)
    }

    /** Total squared prediction error -- the free-energy readout for this area. */
    fun errorEnergy(): Float {
        var s = 0.0
        for (v in error.data) s += v.toDouble() * v
        return s.toFloat()
    }

    /**
     * Per-unit RMS prediction error.
     *
     * This, not [errorEnergy], is what should be reported and what the neuromodulators should
     * consume. A raw sum of squares over ~70,000 units reads in the thousands even when every
     * individual unit is off by 0.2, so it cannot distinguish healthy from diverging -- and
     * squaring a number in the thousands to track its variance overflows float on the first
     * real spike, which is how a NaN got into the dopamine signal and from there into every
     * learning rate in the network.
     */
    fun errorRms(): Float {
        if (error.data.isEmpty()) return 0f
        var s = 0.0
        for (v in error.data) s += v.toDouble() * v
        return kotlin.math.sqrt(s / error.data.size).toFloat()
    }

    fun activity(): Float = representation.activeFraction()
}
