package com.prism.launcher.nora

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Divisive normalization -- the canonical cortical computation (Carandini & Heeger, Nat Rev
 * Neurosci 2012). Applied at EVERY stage, not just V1, because that is the empirical claim:
 * the same normalization motif shows up in retina, LGN, V1, MT, IT, and beyond.
 *
 *      R_i  =  g * x_i^n / (sigma^n + sum_j w_ij * x_j^n)
 *
 * The normalization pool `sum_j` is what varies by area: cross-orientation within a column in
 * V1, cross-direction in MT, cross-feature in IT. Each call site passes the pool it means.
 */
object Normalization {

    /**
     * Normalizes each unit by the pooled activity of all channels at the same cortical location.
     * This is the cross-feature / cross-orientation pool: the classic V1 form, where a unit's
     * gain is divided down by how much everything else at that point on the sheet is doing.
     */
    fun acrossChannels(
        t: Tensor3,
        sigma: Float = NoraConfig.NORM_SIGMA,
        exponent: Float = NoraConfig.NORM_EXPONENT,
        gain: Float = 1f
    ) {
        val n = exponent.toDouble()
        val sigN = sigma.toDouble().pow(n).toFloat()
        Par.forRange(t.h) { y ->
            val pow = FloatArray(t.c)
            for (x in 0 until t.w) {
                var pool = 0f
                for (ci in 0 until t.c) {
                    val v = max(0f, t[ci, y, x])
                    val p = v.toDouble().pow(n).toFloat()
                    pow[ci] = p
                    pool += p
                }
                val denom = sigN + pool
                if (denom > 1e-9f) {
                    for (ci in 0 until t.c) t[ci, y, x] = gain * pow[ci] / denom
                } else {
                    for (ci in 0 until t.c) t[ci, y, x] = 0f
                }
            }
        }
    }

    /**
     * Normalizes by pooled activity in a spatial neighbourhood of the SAME channel.
     * This is the surround-suppression form: a unit is suppressed by vigorous activity in
     * neighbouring cortical territory even when that activity shares its feature preference.
     */
    fun acrossSpace(
        t: Tensor3,
        radius: Int,
        sigma: Float = NoraConfig.NORM_SIGMA,
        exponent: Float = NoraConfig.NORM_EXPONENT
    ) {
        val n = exponent.toDouble()
        val sigN = sigma.toDouble().pow(n).toFloat()
        val src = t.data.copyOf()
        Par.forRange(t.c) { ci ->
            val base = ci * t.plane
            for (y in 0 until t.h) {
                for (x in 0 until t.w) {
                    var pool = 0f
                    var count = 0
                    for (dy in -radius..radius) {
                        val yy = y + dy
                        if (yy < 0 || yy >= t.h) continue
                        for (dx in -radius..radius) {
                            var xx = (x + dx) % t.w
                            if (xx < 0) xx += t.w
                            val v = max(0f, src[base + yy * t.w + xx])
                            pool += v.toDouble().pow(n).toFloat()
                            count++
                        }
                    }
                    if (count > 0) pool /= count
                    val self = max(0f, src[base + y * t.w + x]).toDouble().pow(n).toFloat()
                    t.data[base + y * t.w + x] = self / (sigN + pool)
                }
            }
        }
    }

    /**
     * GABAergic lateral inhibition: each active unit subtracts from its immediate cortical
     * neighbours within its own channel. Distinct from divisive normalization -- this one is
     * subtractive and local, and it is what sharpens boundaries and prevents the smeared
     * "colour bleed" that an unconstrained generative sheet produces.
     */
    fun lateralInhibition(t: Tensor3, strength: Float = NoraConfig.LATERAL_INHIBITION) {
        if (strength <= 0f) return
        val src = t.data.copyOf()
        Par.forRange(t.c) { ci ->
            val base = ci * t.plane
            for (y in 0 until t.h) {
                for (x in 0 until t.w) {
                    var surround = 0f
                    var count = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= t.h) continue
                        for (dx in -1..1) {
                            if (dy == 0 && dx == 0) continue
                            var xx = (x + dx) % t.w
                            if (xx < 0) xx += t.w
                            surround += max(0f, src[base + yy * t.w + xx])
                            count++
                        }
                    }
                    if (count > 0) surround /= count
                    val v = src[base + y * t.w + x] - strength * surround
                    t.data[base + y * t.w + x] = v
                }
            }
        }
    }

    /**
     * k-winners-take-all across the whole sheet. The mechanistic story is a fast pool of
     * inhibitory interneurons setting a global threshold; the computational consequence is a
     * sparse distributed code (Foldiak 1990; Olshausen & Field 1996).
     */
    /** @return the threshold that was applied, so callers can scale later steps against it. */
    fun kWinnersTakeAll(t: Tensor3, sparsity: Float): Float {
        val k = max(1, (t.data.size * sparsity).toInt())
        if (k >= t.data.size) return 0f
        val sorted = t.data.copyOf()
        java.util.Arrays.sort(sorted)
        // Arrays.sort puts NaN last, so a NaN-poisoned layer would silently pick a NaN
        // threshold and pass everything through. Catch it here instead.
        val threshold = sorted[sorted.size - k]
        if (!threshold.isFinite()) {
            NoraHealth.report("k-WTA threshold was non-finite")
            return 0f
        }
        for (i in t.data.indices) {
            if (t.data[i] < threshold) t.data[i] = 0f
        }
        return threshold
    }

    /** Same idea for a flat population vector (semantic hub, DG, CA3). */
    fun kWinnersTakeAll(v: FloatArray, sparsity: Float) {
        val k = max(1, (v.size * sparsity).toInt())
        if (k >= v.size) return
        val sorted = v.copyOf()
        java.util.Arrays.sort(sorted)
        val threshold = sorted[sorted.size - k]
        for (i in v.indices) if (v[i] < threshold) v[i] = 0f
    }

    /**
     * Metabolic cost: firing is expensive, so an active unit pays for itself. Applied as a
     * uniform subtractive tax before rectification. Attwell & Laughlin (2001) put ~75% of the
     * brain's ATP budget on signalling, and sparse coding is the direct consequence.
     *
     * [tax] should be expressed RELATIVE to the layer's current activity (see It.sparsify),
     * never as an absolute constant. An absolute tax applied after divisive normalization can
     * exceed every value in the layer and zero it entirely, which is what happened in the first
     * version of this project -- and because every NaN comparison is false, a NaN also fell
     * through to the zero branch, converting a diverged layer into a clean-looking silent one.
     * Both failure modes are now caught rather than absorbed.
     */
    fun metabolicTax(t: Tensor3, tax: Float) {
        if (tax <= 0f) return
        for (i in t.data.indices) {
            val v = t.data[i]
            if (!v.isFinite()) {
                NoraHealth.report("non-finite activity entering the metabolic tax")
                t.data[i] = 0f
                continue
            }
            t.data[i] = if (v > tax) v - tax else if (v < -tax) v + tax else 0f
        }
    }
}
