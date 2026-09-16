package com.prism.launcher.nora

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V2 / V3: the stage where local orientation measurements become CONTOURS with sides.
 *
 * Three computations, each a real and specific V2 finding:
 *
 *  1. BORDER OWNERSHIP. Zhou, Friedman & von der Heydt (2000) found that ~59% of V2 cells are
 *     selective not just for an edge's orientation but for which SIDE of it owns the surface --
 *     and they do it within ~25 ms of response onset, over distances far outside the classical
 *     receptive field. This is figure-ground assignment computed locally, and it is the single
 *     most important thing V2 does that V1 does not.
 *
 *  2. ILLUSORY CONTOURS. von der Heydt, Peterhans & Baumgartner (1984) showed V2 cells respond
 *     to the illusory edge of a Kanizsa figure at the correct orientation with no luminance
 *     edge present. Implemented here by collinear facilitation along an association field
 *     (Field, Hayes & Hess 1993): a unit is excited by co-oriented, co-axially aligned activity
 *     at a distance, so an interrupted contour completes itself.
 *
 *  3. TEXTURE STATISTICS. V2, unlike V1, is selective for naturalistic texture over
 *     spectrally-matched noise (Freeman, Ziemba, Heeger, Simoncelli & Movshon 2013;
 *     Ziemba et al. 2016). The discriminating statistics are exactly the higher-order
 *     correlations of the Portilla-Simoncelli (2000) texture model: cross-orientation and
 *     cross-scale correlations of filter magnitudes within a pooling region.
 *
 * HONESTY FLAG: border ownership here is a hand-designed asymmetric surround, and the real
 * circuit is not known. The leading accounts are recurrent feedback from higher areas carrying
 * a figure hypothesis (Craft et al. 2007's grouping cells) and fast horizontal propagation
 * within V2 -- these make different predictions and the question is open. Nora's version
 * produces the right selectivity by the wrong route.
 */
class V2 {

    private val srcH = NoraConfig.V1_H
    private val srcW = NoraConfig.V1_W
    private val h = NoraConfig.V2_H
    private val w = NoraConfig.V2_W

    private val nOri = NoraConfig.V1_ORIENTATIONS

    /**
     * Channel layout of the V2 sheet (32 channels total):
     *   0..7    contour energy per orientation (collinearity-enhanced)
     *   8..15   border ownership, "left" side owns, per orientation
     *   16..23  border ownership, "right" side owns, per orientation
     *   24..31  texture statistics (8 pooled higher-order correlation features)
     */
    val chContour = 0
    val chOwnLeft = nOri
    val chOwnRight = 2 * nOri
    val chTexture = 3 * nOri

    /** Elongated association-field kernels, one per orientation, in V1 sheet coordinates. */
    private val associationKernels: Array<FloatArray>
    private val assocRadius = 4

    init {
        val size = 2 * assocRadius + 1
        associationKernels = Array(nOri) { o ->
            val theta = PI.toFloat() * o / nOri
            val k = FloatArray(size * size)
            var sum = 0f
            for (dy in -assocRadius..assocRadius) {
                for (dx in -assocRadius..assocRadius) {
                    // Rotate into the contour's frame: along-axis vs across-axis.
                    val along = dx * cos(theta) + dy * sin(theta)
                    val across = -dx * sin(theta) + dy * cos(theta)
                    // Long and thin along the contour -- the association field's "bowtie".
                    val v = exp(-(along * along) / (2f * 3.0f * 3.0f)) *
                        exp(-(across * across) / (2f * 0.7f * 0.7f))
                    k[(dy + assocRadius) * size + (dx + assocRadius)] = v
                    sum += v
                }
            }
            for (i in k.indices) k[i] /= max(1e-6f, sum)
            k
        }
    }

    /**
     * @param v1 complex-cell energy, [V1_CH, V1_H, V1_W]
     * @param surfaceEnergy pooled local contrast used as the figure/ground cue for ownership
     */
    fun forward(v1: Tensor3, surfaceEnergy: Tensor3): Tensor3 {
        // ── Pool V1 scales down to one energy map per orientation ───────────
        val oriEnergy = Tensor3(nOri, srcH, srcW)
        for (o in 0 until nOri) {
            val base = o * oriEnergy.plane
            for (s in 0 until NoraConfig.V1_SCALES) {
                val ch = o * NoraConfig.V1_SCALES + s
                val vbase = ch * v1.plane
                for (i in 0 until oriEnergy.plane) {
                    // Max across scale, not sum: a contour is present if ANY scale sees it.
                    val v = v1.data[vbase + i]
                    if (v > oriEnergy.data[base + i]) oriEnergy.data[base + i] = v
                }
            }
        }

        // ── 1. Collinear facilitation -> illusory contours ──────────────────
        val enhanced = Tensor3(nOri, srcH, srcW)
        val size = 2 * assocRadius + 1
        Par.forRange(nOri) { o ->
            val kernel = associationKernels[o]
            val sbase = o * oriEnergy.plane
            val dbase = o * enhanced.plane
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    var support = 0f
                    for (dy in -assocRadius..assocRadius) {
                        val yy = (y + dy).coerceIn(0, srcH - 1)
                        for (dx in -assocRadius..assocRadius) {
                            if (dy == 0 && dx == 0) continue
                            var xx = (x + dx) % srcW
                            if (xx < 0) xx += srcW
                            support += oriEnergy.data[sbase + yy * srcW + xx] *
                                kernel[(dy + assocRadius) * size + (dx + assocRadius)]
                        }
                    }
                    val self = oriEnergy.data[sbase + y * srcW + x]
                    // Multiplicative-plus-additive: aligned flanking activity BOTH boosts a
                    // real edge and can create a response where the edge itself is missing,
                    // which is precisely the illusory-contour phenomenology.
                    enhanced.data[dbase + y * srcW + x] = self + 0.85f * support * (0.35f + self)
                }
            }
        }

        // ── 2. Border ownership ─────────────────────────────────────────────
        // For each orientation, compare pooled surface energy on the two sides of the edge.
        // The side with more surface evidence owns the border.
        val ownL = Tensor3(nOri, srcH, srcW)
        val ownR = Tensor3(nOri, srcH, srcW)
        val offset = 3.5f
        Par.forRange(nOri) { o ->
            val theta = PI.toFloat() * o / nOri
            // Normal to the contour.
            val nx = -sin(theta)
            val ny = cos(theta)
            val ebase = o * enhanced.plane
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    val edge = enhanced.data[ebase + y * srcW + x]
                    if (edge < 1e-4f) continue
                    val sideA = NoraMath.bilinearWrapClamp(
                        surfaceEnergy, 0, y + ny * offset, x + nx * offset
                    )
                    val sideB = NoraMath.bilinearWrapClamp(
                        surfaceEnergy, 0, y - ny * offset, x - nx * offset
                    )
                    val diff = sideA - sideB
                    val i = y * srcW + x
                    ownL.data[ebase + i] = edge * max(0f, diff)
                    ownR.data[ebase + i] = edge * max(0f, -diff)
                }
            }
        }

        // ── 3. Texture statistics ───────────────────────────────────────────
        val texture = textureStatistics(v1)

        // ── Downsample everything onto the V2 sheet ─────────────────────────
        val out = Tensor3(NoraConfig.V2_CH, h, w)
        poolInto(enhanced, out, chContour)
        poolInto(ownL, out, chOwnLeft)
        poolInto(ownR, out, chOwnRight)
        for (t in 0 until min(nOri, NoraConfig.V2_CH - chTexture)) {
            System.arraycopy(
                texture.data, t * texture.plane,
                out.data, (chTexture + t) * out.plane, out.plane
            )
        }

        Normalization.acrossChannels(out)
        Normalization.lateralInhibition(out)
        out.rectify()
        return out
    }

    /**
     * Reduced Portilla-Simoncelli statistics: cross-orientation and cross-scale magnitude
     * correlations, pooled over a V2-sized region.
     *
     * The full PS model carries ~700 parameters per texture. Eight pooled correlation features
     * is a deliberate truncation for a phone -- it captures the cross-orientation and
     * cross-scale structure that Freeman et al. (2013) identified as the V2-discriminating
     * component, and drops the marginal and phase statistics that mostly matter for synthesis
     * fidelity rather than for V2 selectivity.
     */
    private fun textureStatistics(v1: Tensor3): Tensor3 {
        val out = Tensor3(nOri, h, w)
        val ry = srcH / h
        val rx = srcW / w
        Par.forRange(h) { by ->
            for (bx in 0 until w) {
                // Mean magnitude per (orientation, scale) within this pooling region.
                val m = FloatArray(NoraConfig.V1_CH)
                var count = 0
                for (dy in 0 until ry) {
                    val y = by * ry + dy
                    if (y >= srcH) continue
                    for (dx in 0 until rx) {
                        val x = bx * rx + dx
                        if (x >= srcW) continue
                        for (ch in 0 until NoraConfig.V1_CH) {
                            m[ch] += v1.data[ch * v1.plane + y * srcW + x]
                        }
                        count++
                    }
                }
                if (count > 0) for (i in m.indices) m[i] /= count

                // Feature t: correlation between orientation t and orientation (t+1), summed
                // across scales, plus the cross-scale correlation within orientation t.
                for (t in 0 until nOri) {
                    var crossOri = 0f
                    var crossScale = 0f
                    val t2 = (t + 1) % nOri
                    for (s in 0 until NoraConfig.V1_SCALES) {
                        crossOri += m[t * NoraConfig.V1_SCALES + s] * m[t2 * NoraConfig.V1_SCALES + s]
                        if (s + 1 < NoraConfig.V1_SCALES) {
                            crossScale += m[t * NoraConfig.V1_SCALES + s] *
                                m[t * NoraConfig.V1_SCALES + s + 1]
                        }
                    }
                    out.data[t * out.plane + by * w + bx] = sqrt(max(0f, crossOri + crossScale))
                }
            }
        }
        return out
    }

    /** Average-pools a full-resolution orientation stack into a channel block of the V2 sheet. */
    private fun poolInto(src: Tensor3, dst: Tensor3, dstChannelOffset: Int) {
        val ry = srcH / h
        val rx = srcW / w
        for (o in 0 until src.c) {
            val dc = dstChannelOffset + o
            if (dc >= dst.c) break
            val sbase = o * src.plane
            val dbase = dc * dst.plane
            for (by in 0 until h) {
                for (bx in 0 until w) {
                    var acc = 0f
                    var count = 0
                    for (dy in 0 until ry) {
                        val y = by * ry + dy
                        if (y >= srcH) continue
                        for (dx in 0 until rx) {
                            val x = bx * rx + dx
                            if (x >= srcW) continue
                            acc += src.data[sbase + y * srcW + x]
                            count++
                        }
                    }
                    dst.data[dbase + by * w + bx] = if (count > 0) acc / count else 0f
                }
            }
        }
    }

    /** Pooled local contrast on the V1 sheet, used as the figure/ground cue for ownership. */
    fun surfaceEnergyOf(v1: Tensor3): Tensor3 {
        val e = Tensor3(1, srcH, srcW)
        for (i in 0 until e.plane) {
            var acc = 0f
            for (ch in 0 until v1.c) acc += v1.data[ch * v1.plane + i]
            e.data[i] = acc
        }
        NoraMath.blurChannel(e, 0, 2.5f, FloatArray(e.plane))
        return e
    }

    /**
     * Reads the dominant contour orientation and its strength at each V2 location, as a
     * (cos2t, sin2t, magnitude) triple. Consumed by V4 for curvature estimation and by the
     * imagery stage for flow-consistent warping.
     */
    fun contourField(v2: Tensor3): Array<FloatArray> {
        val cos2 = FloatArray(h * w)
        val sin2 = FloatArray(h * w)
        val mag = FloatArray(h * w)
        for (o in 0 until nOri) {
            val theta = PI.toFloat() * o / nOri
            val c = cos(2f * theta)
            val s = sin(2f * theta)
            val base = (chContour + o) * v2.plane
            for (i in 0 until v2.plane) {
                val v = v2.data[base + i]
                cos2[i] += v * c
                sin2[i] += v * s
                mag[i] += v
            }
        }
        return arrayOf(cos2, sin2, mag)
    }
}
