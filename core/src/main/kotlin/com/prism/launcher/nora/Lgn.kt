package com.prism.launcher.nora

import kotlin.math.max

/**
 * Lateral geniculate nucleus + pulvinar.
 *
 * The LGN is emphatically not a wire. Only ~5-10% of its synaptic input comes from the retina;
 * the overwhelming majority is cortical feedback from V1 layer 6, plus brainstem modulation
 * (Sherman & Guillery 2002). That ratio is the anatomical case for treating the thalamus as
 * a precision/gain controller on the feedforward stream rather than a relay, which is exactly
 * the role it plays in predictive coding accounts (Kanai et al. 2015).
 *
 * Layers are kept anatomically honest: magno in laminae 1-2, parvo in 3-6, konio in the
 * interlaminar zones -- which in Nora means the M/P/K channels get separate gain pools rather
 * than being normalized against each other.
 */
class Lgn {

    /** Per-channel gain, written by cortical feedback and by acetylcholine. Attention lives here. */
    val gain = FloatArray(NoraConfig.RETINA_CH) { 1f }

    /**
     * Spatial precision map -- where in the visual field the system currently believes its
     * predictions are reliable. This is attention as precision weighting (Feldman & Friston
     * 2010), and it is also what selects the next saccade target during generation.
     */
    val precision = Tensor3(1, NoraConfig.RINGS, NoraConfig.WEDGES).also {
        java.util.Arrays.fill(it.data, 1f)
    }

    private val scratch = FloatArray(NoraConfig.RINGS * NoraConfig.WEDGES)

    fun resetGain() {
        java.util.Arrays.fill(gain, 1f)
        java.util.Arrays.fill(precision.data, 1f)
    }

    /**
     * Relays a retinal image into cortex. Applies the M/P/K-separated normalization pools and
     * the current gain/precision state.
     */
    fun relay(retinal: Tensor3): Tensor3 {
        val out = retinal.clone()

        normalizePool(out, intArrayOf(NoraConfig.RET_M_ON, NoraConfig.RET_M_OFF))
        normalizePool(
            out,
            intArrayOf(
                NoraConfig.RET_P_ON, NoraConfig.RET_P_OFF,
                NoraConfig.RET_RG_ON, NoraConfig.RET_RG_OFF
            )
        )
        normalizePool(out, intArrayOf(NoraConfig.RET_K_ON, NoraConfig.RET_K_OFF))

        for (ci in 0 until out.c) {
            val g = gain[ci]
            val base = ci * out.plane
            for (i in 0 until out.plane) {
                out.data[base + i] *= g * precision.data[i]
            }
        }
        return out
    }

    /** Divisive normalization within one anatomical lamina group only. */
    private fun normalizePool(t: Tensor3, channels: IntArray) {
        val sig2 = NoraConfig.NORM_SIGMA * NoraConfig.NORM_SIGMA
        for (i in 0 until t.plane) {
            var pool = 0f
            for (ci in channels) {
                val v = max(0f, t.data[ci * t.plane + i])
                pool += v * v
            }
            val denom = sig2 + pool
            for (ci in channels) {
                val v = max(0f, t.data[ci * t.plane + i])
                t.data[ci * t.plane + i] = v * v / denom
            }
        }
    }

    /**
     * Pulvinar-style routing: rewrites the spatial precision map from the current prediction
     * error, so that regions the model is getting wrong are boosted on the next pass.
     *
     * HONESTY FLAG: the pulvinar's actual role is genuinely unsettled. It is reciprocally
     * connected with essentially all of visual cortex and has been argued to do attentional
     * selection, cortico-cortical routing, and confidence signalling (Saalmann & Kastner 2011;
     * Zhou, Schafer & Desimone 2016). What is implemented here -- error-driven spatial
     * precision -- is one computational reading of that literature, not a settled mechanism.
     */
    fun updatePrecisionFromError(error: Tensor3, rate: Float = 0.35f) {
        val mag = Tensor3(1, error.h, error.w)
        for (i in 0 until error.plane) {
            var e = 0f
            for (ci in 0 until error.c) {
                val v = error.data[ci * error.plane + i]
                e += v * v
            }
            mag.data[i] = kotlin.math.sqrt(e)
        }
        NoraMath.blurChannel(mag, 0, 2.0f, scratch)

        var peak = 0f
        for (v in mag.data) if (v > peak) peak = v
        if (peak < 1e-6f) return

        for (i in precision.data.indices) {
            val target = 0.5f + 1.5f * (mag.data[i] / peak)
            precision.data[i] += rate * (target - precision.data[i])
        }
    }

    /** Returns the (ring, wedge) of highest precision-weighted error -- the next fixation target. */
    fun peakPrecisionLocation(): Pair<Int, Int> {
        var best = -1f
        var bi = 0
        var bj = 0
        for (i in 0 until NoraConfig.RINGS) {
            for (j in 0 until NoraConfig.WEDGES) {
                val v = precision[0, i, j]
                if (v > best) {
                    best = v
                    bi = i
                    bj = j
                }
            }
        }
        return bi to bj
    }
}
