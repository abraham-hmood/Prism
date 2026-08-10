package com.prism.launcher.nora

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V4: shape parts, colour constancy, and surface binding.
 *
 *  1. CURVATURE AND SHAPE-PART TUNING. Pasupathy & Connor (1999, 2001, 2002) showed V4 neurons
 *     are tuned jointly to boundary CURVATURE and to the ANGULAR POSITION of that curvature
 *     relative to the object's centre of mass -- not to whole shapes, and not to simple
 *     orientation. A moderately-curved convexity at the upper right is a different stimulus to
 *     a V4 cell than the same convexity at the lower left. Nora implements exactly that basis:
 *     a (curvature x angular-position) grid, referenced to a computed object centroid.
 *
 *  2. COLOUR CONSTANCY. V4 was originally characterized by Zeki (1983) as responding to
 *     perceived rather than measured colour. Implemented as von Kries (1902) adaptation --
 *     each opponent channel divided by its own spatial mean, which discounts the illuminant.
 *     Simple, but it is the mechanism the perceptual literature keeps returning to
 *     (Foster 2011 reviews why).
 *
 *  3. SURFACE / FIGURE BINDING. Brightness and colour spread across a region and stop at its
 *     borders (Paradiso & Nakayama 1991; Komatsu 2006). Implemented as anisotropic diffusion
 *     whose conductance is gated by V2 border-ownership signals: colour flows freely inside a
 *     figure and is blocked at an owned boundary. This is what turns a sparse set of contour
 *     responses back into filled regions, and it is the step that makes generated images look
 *     like objects rather than wireframes.
 *
 * HONESTY FLAG: the curvature basis is fitted, not learned. Pasupathy & Connor derived their
 * basis by fitting responses to a stimulus set; Nora installs the resulting tuning functions
 * directly. Whether V4 arrives at this basis through experience or through the statistics of
 * its V2 input is not settled.
 */
class V4 {

    private val srcH = NoraConfig.V2_H
    private val srcW = NoraConfig.V2_W
    private val h = NoraConfig.V4_H
    private val w = NoraConfig.V4_W

    /** Curvature bins: strongly concave -> flat -> strongly convex (Pasupathy & Connor's axis). */
    private val curvatureBins = floatArrayOf(-0.7f, -0.3f, 0.0f, 0.3f, 0.7f, 1.0f)
    /** Angular-position bins around the object centroid. */
    private val angularBins = 8

    /**
     * Channel layout of the V4 sheet (48 channels):
     *   0..47   curvature x angular-position (6 x 8) shape-part units
     * Colour and surface information rides in the separate surface tensor returned alongside.
     */
    val shapeChannels = curvatureBins.size * angularBins   // 48

    /**
     * @param v2 the V2 sheet
     * @param contour (cos2theta, sin2theta, magnitude) field from V2, on the V2 sheet
     */
    fun forward(v2: Tensor3, contour: Array<FloatArray>): Tensor3 {
        val out = Tensor3(NoraConfig.V4_CH, h, w)

        // ── Object centroid: the reference frame the shape-part code is relative to ──
        // Pasupathy & Connor's angular position is measured from the object's centre of mass,
        // so it has to be computed before any shape unit can be evaluated.
        var cxAcc = 0f
        var cyAcc = 0f
        var mAcc = 0f
        val mag = contour[2]
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val m = mag[y * srcW + x]
                cxAcc += x * m
                cyAcc += y * m
                mAcc += m
            }
        }
        val cx = if (mAcc > 1e-6f) cxAcc / mAcc else srcW * 0.5f
        val cy = if (mAcc > 1e-6f) cyAcc / mAcc else srcH * 0.5f

        // ── Curvature field: rate of change of contour orientation along the contour ──
        val curvature = FloatArray(srcH * srcW)
        val cos2 = contour[0]
        val sin2 = contour[1]
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val i = y * srcW + x
                val m = mag[i]
                if (m < 1e-5f) continue
                val theta = 0.5f * atan2(sin2[i], cos2[i])
                // Step along the contour tangent and measure how far the orientation turned.
                val tx = cos(theta)
                val ty = sin(theta)
                val ahead = orientationAt(cos2, sin2, mag, y + ty, x + tx)
                val behind = orientationAt(cos2, sin2, mag, y - ty, x - tx)
                var d = ahead - behind
                // Orientation is periodic over pi; unwrap to the shortest turn.
                while (d > PI.toFloat() / 2f) d -= PI.toFloat()
                while (d < -PI.toFloat() / 2f) d += PI.toFloat()
                curvature[i] = (d / (PI.toFloat() / 2f)).coerceIn(-1f, 1f)
            }
        }

        // ── Shape-part units ────────────────────────────────────────────────
        val ry = srcH / h
        val rx = srcW / w
        Par.forRange(h) { by ->
            for (bx in 0 until w) {
                val acc = FloatArray(shapeChannels)
                for (dy in 0 until ry) {
                    val y = by * ry + dy
                    if (y >= srcH) continue
                    for (dx in 0 until rx) {
                        val x = bx * rx + dx
                        if (x >= srcW) continue
                        val i = y * srcW + x
                        val m = mag[i]
                        if (m < 1e-5f) continue
                        val kappa = curvature[i]
                        var phi = atan2((y - cy), (x - cx))
                        if (phi < 0) phi += 2f * PI.toFloat()

                        for (kb in curvatureBins.indices) {
                            // Gaussian tuning on curvature, ~0.35 wide (Pasupathy & Connor
                            // report broad, unimodal curvature tuning).
                            val dk = kappa - curvatureBins[kb]
                            val kTune = exp(-(dk * dk) / (2f * 0.35f * 0.35f))
                            if (kTune < 0.02f) continue
                            for (ab in 0 until angularBins) {
                                val target = 2f * PI.toFloat() * ab / angularBins
                                var da = phi - target
                                while (da > PI.toFloat()) da -= 2f * PI.toFloat()
                                while (da < -PI.toFloat()) da += 2f * PI.toFloat()
                                val aTune = exp(-(da * da) / (2f * 0.6f * 0.6f))
                                acc[kb * angularBins + ab] += m * kTune * aTune
                            }
                        }
                    }
                }
                for (ch in 0 until shapeChannels) {
                    if (ch < out.c) out.data[ch * out.plane + by * w + bx] = acc[ch]
                }
            }
        }

        Normalization.acrossChannels(out)
        Normalization.lateralInhibition(out)
        out.rectify()
        return out
    }

    private fun orientationAt(
        cos2: FloatArray, sin2: FloatArray, mag: FloatArray, fy: Float, fx: Float
    ): Float {
        val y = fy.toInt().coerceIn(0, srcH - 1)
        var x = fx.toInt() % srcW
        if (x < 0) x += srcW
        val i = y * srcW + x
        if (mag[i] < 1e-6f) return 0f
        return 0.5f * atan2(sin2[i], cos2[i])
    }

    /**
     * von Kries colour constancy on the retinal surface channels.
     *
     * Each channel is divided by its own spatial mean, which discounts a multiplicative
     * illuminant. Applied in place on a retinal-shaped tensor.
     */
    fun colourConstancy(
        retinal: Tensor3,
        strength: Float = NoraTuning.colourConstancyStrength
    ) {
        if (strength <= 0f) return
        for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
            val base = ci * retinal.plane
            var sum = 0f
            for (i in 0 until retinal.plane) sum += retinal.data[base + i]
            val mean = sum / retinal.plane
            if (mean < 1e-4f) continue
            // Interpolating toward full von Kries rather than applying it outright: complete
            // discounting would erase all global colour, and human constancy is famously
            // partial (~60-80% by most estimates).
            val g = (1f - strength) + strength * (0.5f / mean)
            for (i in 0 until retinal.plane) {
                retinal.data[base + i] = (retinal.data[base + i] * g).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Boundary-gated surface fill-in on the retinal surface channels.
     *
     * Diffuses colour isotropically, except across locations where V2 signalled an owned
     * border. This is Grossberg & Mingolla's (1985) boundary-contour / feature-contour
     * separation, implemented directly: boundaries do not carry colour, they constrain where
     * colour can flow.
     *
     * @param boundary strength map on the retinal sheet, 0 = free flow, high = barrier
     */
    fun fillIn(retinal: Tensor3, boundary: FloatArray, iterations: Int = 8) {
        val hh = retinal.h
        val ww = retinal.w
        val conduct = FloatArray(hh * ww)
        for (i in conduct.indices) conduct[i] = exp(-3.0f * boundary[i])

        val buf = FloatArray(retinal.plane)
        for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
            val base = ci * retinal.plane
            for (it in 0 until iterations) {
                for (y in 0 until hh) {
                    for (x in 0 until ww) {
                        val i = y * ww + x
                        var acc = retinal.data[base + i]
                        var wsum = 1f
                        for (d in 0 until 4) {
                            val yy: Int
                            val xx: Int
                            when (d) {
                                0 -> { yy = y - 1; xx = x }
                                1 -> { yy = y + 1; xx = x }
                                2 -> { yy = y; xx = x - 1 }
                                else -> { yy = y; xx = x + 1 }
                            }
                            if (yy < 0 || yy >= hh) continue
                            var nx = xx % ww
                            if (nx < 0) nx += ww
                            val ni = yy * ww + nx
                            // Flow is gated by the WEAKER of the two conductances -- a barrier
                            // on either side stops the spread, which is what makes a closed
                            // contour actually contain its surface.
                            val g = 0.25f * kotlin.math.min(conduct[i], conduct[ni])
                            acc += g * retinal.data[base + ni]
                            wsum += g
                        }
                        buf[i] = acc / wsum
                    }
                }
                System.arraycopy(buf, 0, retinal.data, base, retinal.plane)
            }
        }
    }
}
