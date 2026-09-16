package com.prism.launcher.nora

import com.prism.core.PrismImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Retina + optic nerve: log-polar foveated sampling, ON/OFF centre-surround, the
 * magno/parvo/konio split, and contrast gain control.
 *
 * The log-polar geometry is the single most consequential design decision in Nora. Cortical
 * magnification in primate V1 goes roughly as M(e) = A/(e + e2) (Daniel & Whitteridge 1961;
 * Schwartz 1977, 1980), which means a logarithmic spacing of eccentricity samples maps the
 * visual field onto a UNIFORM cortical sheet. Two things fall out of that for free:
 *
 *   1. A fixed-size filter applied on the log-polar sheet is an eccentricity-scaled filter in
 *      the visual field -- small receptive fields at the fovea, large ones in the periphery,
 *      exactly as measured, with no per-eccentricity filter bank needed.
 *   2. Rotation and scaling about the fixation point become translations on the sheet, which
 *      is where V1's much-remarked rotation/scale tolerance actually comes from.
 *
 * And engineering-wise it is the whole reason this fits on a phone: 32x48 = 1536 sampled
 * locations covers a 128x128 canvas with foveal detail, versus 16384 for a dense grid.
 */
class Retina {

    /** Eccentricity of each ring, in units of canvas half-width. Log-spaced = cortical magnification. */
    private val eccentricity = FloatArray(NoraConfig.RINGS) { i ->
        val t = if (NoraConfig.RINGS <= 1) 0f else i.toFloat() / (NoraConfig.RINGS - 1)
        NoraConfig.FOVEA_MIN_ECC *
            (NoraConfig.FOVEA_MAX_ECC / NoraConfig.FOVEA_MIN_ECC).pow(t)
    }

    private val cosTheta = FloatArray(NoraConfig.WEDGES) { j ->
        cos(2.0 * Math.PI * j / NoraConfig.WEDGES).toFloat()
    }
    private val sinTheta = FloatArray(NoraConfig.WEDGES) { j ->
        sin(2.0 * Math.PI * j / NoraConfig.WEDGES).toFloat()
    }

    /** Previous frame's luminance in log-polar space, for the magno transient response. */
    private var prevLuminance: FloatArray? = null

    /** Raw opponent planes: 0=luminance (L+M), 1=red-green (L-M), 2=blue-yellow (S-(L+M)). */
    private val opponent = Tensor3(3, NoraConfig.RINGS, NoraConfig.WEDGES)
    private val scratch = FloatArray(NoraConfig.RINGS * NoraConfig.WEDGES)

    fun eccentricityOf(ring: Int): Float = eccentricity[ring.coerceIn(0, NoraConfig.RINGS - 1)]

    fun resetTemporal() {
        prevLuminance = null
    }

    /**
     * Samples [bitmap] through the log-polar array centred on ([fx], [fy]) given in normalized
     * canvas coordinates (0..1), and returns the full retinal output.
     */
    fun sample(bitmap: PrismImage, fx: Float, fy: Float): Tensor3 {
        val out = Tensor3(NoraConfig.RETINA_CH, NoraConfig.RINGS, NoraConfig.WEDGES)
        sampleInto(bitmap, fx, fy, out)
        return out
    }

    fun sampleInto(bitmap: PrismImage, fx: Float, fy: Float, out: Tensor3) {
        val bw = bitmap.width
        val bh = bitmap.height
        val half = min(bw, bh) * 0.5f
        val cx = fx * bw
        val cy = fy * bh

        // ── Photoreceptors -> cone-opponent channels ────────────────────────
        // Derrington, Krauskopf & Lennie (1984) cardinal directions of colour space. Working
        // from sRGB rather than true cone fundamentals is an approximation, noted in NORA.md.
        Par.forRange(NoraConfig.RINGS) { i ->
            val e = eccentricity[i] * half
            for (j in 0 until NoraConfig.WEDGES) {
                val sx = cx + e * cosTheta[j]
                val sy = cy + e * sinTheta[j]
                val px = sampleBilinear(bitmap, sx, sy)
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f

                opponent[0, i, j] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                opponent[1, i, j] = r - g
                opponent[2, i, j] = b - 0.5f * (r + g)

                out[NoraConfig.RET_S_R, i, j] = r
                out[NoraConfig.RET_S_G, i, j] = g
                out[NoraConfig.RET_S_B, i, j] = b
            }
        }

        buildContrastChannels(out)
    }

    /**
     * Builds the ON/OFF DoG channels from the opponent planes already loaded into [opponent].
     * Split out so imagery can drive it from a predicted surface rather than a real bitmap.
     */
    private fun buildContrastChannels(out: Tensor3) {
        val lum = FloatArray(opponent.plane)
        System.arraycopy(opponent.data, 0, lum, 0, opponent.plane)

        // ── Parvocellular: fine DoG, sustained, luminance + red-green ───────
        differenceOfGaussians(
            lum, out, NoraConfig.RET_P_ON, NoraConfig.RET_P_OFF,
            NoraConfig.PARVO_CENTER_SIGMA, NoraConfig.PARVO_SURROUND_SIGMA
        )
        val rg = FloatArray(opponent.plane)
        System.arraycopy(opponent.data, opponent.plane, rg, 0, opponent.plane)
        differenceOfGaussians(
            rg, out, NoraConfig.RET_RG_ON, NoraConfig.RET_RG_OFF,
            NoraConfig.PARVO_CENTER_SIGMA, NoraConfig.PARVO_SURROUND_SIGMA
        )

        // ── Koniocellular: S-(L+M), coarse, chromatic ───────────────────────
        val by = FloatArray(opponent.plane)
        System.arraycopy(opponent.data, 2 * opponent.plane, by, 0, opponent.plane)
        differenceOfGaussians(
            by, out, NoraConfig.RET_K_ON, NoraConfig.RET_K_OFF,
            NoraConfig.MAGNO_CENTER_SIGMA, NoraConfig.MAGNO_SURROUND_SIGMA
        )

        // ── Magnocellular: coarse DoG, TRANSIENT ────────────────────────────
        // Magno cells are phasic -- they respond to change. Approximated here as a first-order
        // temporal derivative of luminance, which is the standard reduction of the biphasic
        // magno impulse response. On the first frame there is no history, so magno sees the
        // static contrast instead (equivalent to the transient at stimulus onset).
        val transient = FloatArray(opponent.plane)
        val prev = prevLuminance
        if (prev != null) {
            for (i in transient.indices) transient[i] = lum[i] - prev[i]
        } else {
            System.arraycopy(lum, 0, transient, 0, transient.size)
        }
        differenceOfGaussians(
            transient, out, NoraConfig.RET_M_ON, NoraConfig.RET_M_OFF,
            NoraConfig.MAGNO_CENTER_SIGMA, NoraConfig.MAGNO_SURROUND_SIGMA
        )
        prevLuminance = lum

        contrastGainControl(out)
    }

    /**
     * Rodieck's (1965) difference-of-Gaussians receptive field, split into a rectified ON
     * channel and a rectified OFF channel -- the actual anatomy (Enroth-Cugell & Robson 1966;
     * Wassle 2004), not a signed value, because ganglion cells cannot fire negatively.
     */
    private fun differenceOfGaussians(
        src: FloatArray,
        out: Tensor3,
        onCh: Int,
        offCh: Int,
        centreSigma: Float,
        surroundSigma: Float
    ) {
        val centre = Tensor3(1, NoraConfig.RINGS, NoraConfig.WEDGES)
        System.arraycopy(src, 0, centre.data, 0, src.size)
        val surround = centre.clone()

        NoraMath.blurChannel(centre, 0, centreSigma, scratch)
        NoraMath.blurChannel(surround, 0, surroundSigma, scratch)

        val onBase = onCh * out.plane
        val offBase = offCh * out.plane
        for (i in 0 until out.plane) {
            val d = centre.data[i] - surround.data[i]
            out.data[onBase + i] = if (d > 0f) d else 0f
            out.data[offBase + i] = if (d < 0f) -d else 0f
        }
    }

    /**
     * Retinal contrast gain control (Shapley & Victor 1978): response is divided down by local
     * contrast energy, so the retina stays informative across ~9 log units of illumination
     * instead of saturating. Implemented as a Naka-Rushton on each contrast channel with a
     * semi-saturation set by the pooled local contrast.
     */
    private fun contrastGainControl(out: Tensor3) {
        val pooled = Tensor3(1, NoraConfig.RINGS, NoraConfig.WEDGES)
        for (i in 0 until out.plane) {
            var e = 0f
            for (ci in 0 until NoraConfig.RET_S_R) {
                val v = out.data[ci * out.plane + i]
                e += v * v
            }
            pooled.data[i] = sqrt(e)
        }
        NoraMath.blurChannel(pooled, 0, 3.0f, scratch)

        for (ci in 0 until NoraConfig.RET_S_R) {
            val base = ci * out.plane
            for (i in 0 until out.plane) {
                val v = out.data[base + i]
                out.data[base + i] = NoraMath.nakaRushton(v, 0.08f + 0.6f * pooled.data[i])
            }
        }
    }

    /**
     * True bilinear interpolation, not nearest-neighbour. It matters here: foveal ring spacing
     * is far finer than one source pixel, so nearest-neighbour would quantize the fovea's
     * extra resolution straight back away and defeat the point of the log-polar map.
     */
    private fun sampleBilinear(bmp: PrismImage, sx: Float, sy: Float): Int {
        val x = sx.coerceIn(0f, (bmp.width - 1).toFloat())
        val y = sy.coerceIn(0f, (bmp.height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(bmp.width - 1, x0 + 1)
        val y1 = min(bmp.height - 1, y0 + 1)
        val tx = x - x0
        val ty = y - y0

        val p00 = bmp.pixel(x0, y0)
        val p01 = bmp.pixel(x1, y0)
        val p10 = bmp.pixel(x0, y1)
        val p11 = bmp.pixel(x1, y1)

        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val c00 = (p00 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val a = c00 + (c01 - c00) * tx
            val b = c10 + (c11 - c10) * tx
            val v = (a + (b - a) * ty).toInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }

    // ── Inverse mapping: log-polar sheet back onto a Cartesian canvas ───────

    /**
     * Splats a predicted retinal surface back into a full-resolution canvas.
     *
     * This is the motor/readout side of the same log-polar transform, and it is where the
     * saccadic-refinement memory trick pays off: each fixation writes a small, high-confidence
     * foveal region and a low-confidence periphery into the accumulator, and repeated fixations
     * at different points build a canvas whose resolution far exceeds any single retinal image.
     * Peak working memory tracks the 32x48 sheet, not the 128x128 output.
     *
     * [accum] and [weight] are canvas-sized accumulators owned by the caller across saccades.
     */
    fun splatToCanvas(
        retinal: Tensor3,
        fx: Float,
        fy: Float,
        canvasSize: Int,
        accum: FloatArray,
        weight: FloatArray
    ) {
        val half = canvasSize * 0.5f
        val cx = fx * canvasSize
        val cy = fy * canvasSize

        for (i in 0 until NoraConfig.RINGS) {
            val e = eccentricity[i] * half
            // Angular spacing grows with eccentricity, so a sample's cortical "footprint" in
            // canvas pixels does too. Splat radius follows it -- this is cortical magnification
            // read backwards.
            val ringGap = if (i + 1 < NoraConfig.RINGS) (eccentricity[i + 1] - eccentricity[i]) * half else 1f
            val arcGap = (2f * Math.PI.toFloat() * e) / NoraConfig.WEDGES
            // Floor raised from 0.75 so that adjacent foveal samples always overlap. At 0.75
            // the innermost rings splat sub-pixel islands that never blend with their
            // neighbours, and because foveal confidence is near 1.0 each fixation stamped a
            // hard dot at its own centre -- the six dark spots that appeared in generated
            // images, one per saccade. Overlapping support removes them without blurring
            // anything the periphery contributes.
            val radius = max(1.4f, max(ringGap, arcGap) * 0.75f)
            // Foveal samples are trusted more than peripheral ones -- the confidence gradient
            // that makes multi-saccade integration converge instead of averaging to mush.
            val confidence = 1f / (1f + 3f * eccentricity[i])

            for (j in 0 until NoraConfig.WEDGES) {
                val px = cx + e * cosTheta[j]
                val py = cy + e * sinTheta[j]
                val r = retinal[NoraConfig.RET_S_R, i, j]
                val g = retinal[NoraConfig.RET_S_G, i, j]
                val b = retinal[NoraConfig.RET_S_B, i, j]

                val x0 = max(0, (px - radius).toInt())
                val x1 = min(canvasSize - 1, (px + radius).toInt() + 1)
                val y0 = max(0, (py - radius).toInt())
                val y1 = min(canvasSize - 1, (py + radius).toInt() + 1)
                val inv2r2 = 1f / (2f * radius * radius)

                for (yy in y0..y1) {
                    val dy = yy + 0.5f - py
                    for (xx in x0..x1) {
                        val dx = xx + 0.5f - px
                        val d2 = dx * dx + dy * dy
                        val wgt = confidence * kotlin.math.exp(-d2 * inv2r2)
                        if (wgt < 1e-4f) continue
                        val idx = yy * canvasSize + xx
                        accum[idx * 3] += r * wgt
                        accum[idx * 3 + 1] += g * wgt
                        accum[idx * 3 + 2] += b * wgt
                        weight[idx] += wgt
                    }
                }
            }
        }
    }

    companion object {
        /** Resolves an accumulator pair into a bitmap, filling any unwritten pixels by nearest support. */
        fun accumulatorToImage(canvasSize: Int, accum: FloatArray, weight: FloatArray): PrismImage {
            val pixels = IntArray(canvasSize * canvasSize)
            for (i in pixels.indices) {
                val w = weight[i]
                if (w > 1e-5f) {
                    val r = (accum[i * 3] / w).coerceIn(0f, 1f)
                    val g = (accum[i * 3 + 1] / w).coerceIn(0f, 1f)
                    val b = (accum[i * 3 + 2] / w).coerceIn(0f, 1f)
                    pixels[i] = (0xFF shl 24) or
                        ((r * 255).toInt() shl 16) or
                        ((g * 255).toInt() shl 8) or
                        (b * 255).toInt()
                } else {
                    pixels[i] = 0xFF000000.toInt()
                }
            }
            return PrismImage(canvasSize, canvasSize, pixels)
        }
    }
}
