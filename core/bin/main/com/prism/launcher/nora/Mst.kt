package com.prism.launcher.nora

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MST: optic-flow templates and self-motion.
 *
 * Neurons in dorsal MST are tuned to whole-field flow PATTERNS -- expansion, contraction,
 * rotation, and their spiral combinations -- rather than to local translation (Saito et al.
 * 1986; Duffy & Wurtz 1991; Graziano, Andersen & Snowden 1994, who showed a continuum of
 * spiral tuning). Expansion tuning in particular signals forward self-motion, and MSTd
 * responses track the focus of expansion, i.e. heading.
 *
 * For a generative system this is the camera model. Rather than a video generator that has to
 * learn frame-to-frame consistency from data, Nora reads a global motion parameterization out
 * of a small fixed template bank, and then uses those parameters to transport content forward
 * coherently. A rotating camera produces a rotation-template response, and the same rotation is
 * then applied to the whole scene representation -- so the scene stays the same scene.
 *
 * The templates are defined over the LOG-POLAR sheet, which makes them unusually cheap:
 * on a log-polar map, expansion about the fixation point is a pure translation along the
 * eccentricity axis, and rotation about it is a pure translation along the polar-angle axis.
 * That is a genuine mathematical property of the mapping, and it is one of the strongest
 * arguments for the log-polar front end.
 */
class Mst {

    private val h = NoraConfig.MT_H
    private val w = NoraConfig.MT_W

    /** Fixed flow templates over the sheet: [template][location] -> (vx, vy). */
    private val templateX = Array(NoraConfig.MST_TEMPLATES) { FloatArray(h * w) }
    private val templateY = Array(NoraConfig.MST_TEMPLATES) { FloatArray(h * w) }

    /** Most recent template responses. Index order matches [templateNames]. */
    val response = FloatArray(NoraConfig.MST_TEMPLATES)

    val templateNames get() = TEMPLATE_NAMES

    companion object {
        /** Static so the chat layer can list valid motions without building a template bank. */
        val TEMPLATE_NAMES = arrayOf(
            "expansion", "contraction", "rotate-cw", "rotate-ccw",
            "translate-E", "translate-NE", "translate-N", "translate-NW",
            "translate-W", "translate-SW", "translate-S", "translate-SE"
        )
    }

    init {
        val cy = h * 0.5f
        val cx = w * 0.5f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // On the log-polar sheet, the eccentricity axis is y and the angle axis is x.
                // Expansion = uniform motion outward = +y. Rotation = uniform motion in x.
                templateX[0][i] = 0f; templateY[0][i] = 1f      // expansion
                templateX[1][i] = 0f; templateY[1][i] = -1f     // contraction
                templateX[2][i] = 1f; templateY[2][i] = 0f      // rotate cw
                templateX[3][i] = -1f; templateY[3][i] = 0f     // rotate ccw

                // Translations are NOT uniform on the log-polar sheet -- a rigid shift of the
                // visual field induces a position-dependent flow here. Computing it properly
                // is what makes translation separable from expansion at all.
                for (t in 0 until 8) {
                    val theta = 2f * PI.toFloat() * t / 8f
                    val tx = cos(theta)
                    val ty = sin(theta)
                    // Cartesian position this sheet location corresponds to.
                    val ecc = (y + 1).toFloat() / h
                    val ang = 2f * PI.toFloat() * x / w
                    val px = ecc * cos(ang)
                    val py = ecc * sin(ang)
                    // d(ecc)/dt and d(angle)/dt for a rigid Cartesian translation (tx, ty).
                    val r = sqrt(px * px + py * py).coerceAtLeast(1e-4f)
                    val dEcc = (px * tx + py * ty) / r
                    val dAng = (px * ty - py * tx) / (r * r)
                    templateY[4 + t][i] = dEcc * h
                    templateX[4 + t][i] = dAng * w / (2f * PI.toFloat())
                }
            }
        }
        for (t in 0 until NoraConfig.MST_TEMPLATES) {
            normalize(templateX[t], templateY[t])
        }
    }

    private fun normalize(vx: FloatArray, vy: FloatArray) {
        var n = 0f
        for (i in vx.indices) n += vx[i] * vx[i] + vy[i] * vy[i]
        n = sqrt(n).coerceAtLeast(1e-6f)
        for (i in vx.indices) {
            vx[i] /= n
            vy[i] /= n
        }
    }

    /**
     * Projects an MT flow field onto the template bank. Returns the index of the winning
     * template. Responses are rectified and normalized, so they read as a probability-like
     * distribution over self-motion hypotheses.
     */
    fun analyse(flowX: FloatArray, flowY: FloatArray): Int {
        var norm = 0f
        for (i in flowX.indices) norm += flowX[i] * flowX[i] + flowY[i] * flowY[i]
        norm = sqrt(norm)
        if (norm < 1e-5f) {
            java.util.Arrays.fill(response, 0f)
            return -1
        }

        var best = 0f
        var bestIdx = -1
        var total = 0f
        for (t in 0 until NoraConfig.MST_TEMPLATES) {
            var dot = 0f
            for (i in flowX.indices) {
                dot += flowX[i] * templateX[t][i] + flowY[i] * templateY[t][i]
            }
            val r = (dot / norm).coerceAtLeast(0f)
            response[t] = r
            total += r
            if (r > best) {
                best = r
                bestIdx = t
            }
        }
        if (total > 1e-6f) for (t in response.indices) response[t] /= total
        return bestIdx
    }

    /**
     * Synthesizes a coherent global flow field from a chosen self-motion, for use during
     * generation. This is the camera: the caller says "dolly in" or "orbit left" and MST
     * hands back the sheet-space flow that means, which MT then applies as a warp.
     */
    fun synthesizeFlow(template: Int, magnitude: Float, flowX: FloatArray, flowY: FloatArray) {
        java.util.Arrays.fill(flowX, 0f)
        java.util.Arrays.fill(flowY, 0f)
        if (template < 0 || template >= NoraConfig.MST_TEMPLATES) return

        // Templates are unit-L2-normalized over the whole sheet, so their per-element values
        // are tiny and vary between templates. Rescale by RMS so that `magnitude` means what
        // the caller expects it to mean: sheet cells of displacement per frame, the same
        // number for every template.
        val tx = templateX[template]
        val ty = templateY[template]
        var rms = 0f
        for (i in tx.indices) rms += tx[i] * tx[i] + ty[i] * ty[i]
        rms = sqrt(rms / tx.size.coerceAtLeast(1))
        if (rms < 1e-9f) return
        val k = magnitude / rms
        for (i in flowX.indices) {
            flowX[i] = tx[i] * k
            flowY[i] = ty[i] * k
        }
    }

    fun templateIndex(name: String): Int = templateNames.indexOfFirst { it.equals(name, true) }

    /** Human-readable summary of what self-motion MST currently believes is happening. */
    fun describe(): String {
        var best = 0f
        var bi = -1
        for (t in response.indices) if (response[t] > best) {
            best = response[t]
            bi = t
        }
        return if (bi < 0 || best < 0.15f) "static" else templateNames[bi]
    }
}
