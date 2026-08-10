package com.prism.launcher.nora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Primary visual cortex.
 *
 * Three mechanisms, each with a specific empirical warrant:
 *
 *  1. SIMPLE CELLS are 2-D Gabor functions. Not "edge detectors" loosely -- Marcelja (1980)
 *     and Daugman (1985) proposed the form, and Jones & Palmer (1987) measured cat V1 simple
 *     cell receptive fields directly and found Gabors fit them. Aspect ratio ~0.5-0.7 and
 *     ~1.4-octave bandwidth match macaque measurements (Ringach 2002; De Valois et al. 1982).
 *
 *  2. COMPLEX CELLS are built from a quadrature simple-cell pair by the ENERGY MODEL:
 *     C = sqrt(E_even^2 + E_odd^2). Adelson & Bergen (1985). This is what buys phase
 *     invariance -- a complex cell responds to an edge of its preferred orientation regardless
 *     of the edge's exact position or contrast polarity within its receptive field.
 *
 *  3. The bank is laid out on a real cortical sheet with pinwheel topography, and a unit's
 *     gain depends on how well it matches the local column. Columnar structure is a genuine
 *     2-D constraint on the representation here, not a metaphor: it means the sheet cannot
 *     represent all orientations equally well everywhere, which is a real limitation of
 *     cortex and one Nora inherits deliberately.
 *
 * Because V1 operates on the log-polar sheet, one fixed-size Gabor bank yields
 * eccentricity-scaled receptive fields in the visual field automatically. That is not a trick;
 * it is what the log-polar map means.
 */
class V1 {

    private val h = NoraConfig.V1_H
    private val w = NoraConfig.V1_W

    val orientationMap = OrientationMap(h, w)

    /** Quadrature Gabor pairs, indexed [orientation * scales + scale]. */
    private val evenKernels: Array<FloatArray>
    private val oddKernels: Array<FloatArray>
    private val kernelRadius: IntArray
    private val orientationOf = FloatArray(NoraConfig.V1_CH)

    /** Per-unit columnar gain, precomputed: [channel][y * w + x]. */
    private val columnarGain: Array<FloatArray>

    init {
        val n = NoraConfig.V1_CH
        evenKernels = Array(n) { FloatArray(0) }
        oddKernels = Array(n) { FloatArray(0) }
        kernelRadius = IntArray(n)

        for (o in 0 until NoraConfig.V1_ORIENTATIONS) {
            val theta = PI.toFloat() * o / NoraConfig.V1_ORIENTATIONS
            for (s in 0 until NoraConfig.V1_SCALES) {
                val ch = o * NoraConfig.V1_SCALES + s
                orientationOf[ch] = theta
                val lambda = NoraConfig.GABOR_WAVELENGTHS[s]
                val sigma = lambda * NoraConfig.GABOR_SIGMA_RATIO
                val radius = max(2, (sigma * 2.5f).toInt())
                kernelRadius[ch] = radius
                val size = 2 * radius + 1
                val even = FloatArray(size * size)
                val odd = FloatArray(size * size)

                var evenSum = 0f
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val xr = dx * cos(theta) + dy * sin(theta)
                        val yr = -dx * sin(theta) + dy * cos(theta)
                        val env = exp(
                            -(xr * xr + NoraConfig.GABOR_GAMMA * NoraConfig.GABOR_GAMMA * yr * yr) /
                                (2f * sigma * sigma)
                        )
                        val phase = 2f * PI.toFloat() * xr / lambda
                        val i = (dy + radius) * size + (dx + radius)
                        even[i] = env * cos(phase)
                        odd[i] = env * sin(phase)
                        evenSum += even[i]
                    }
                }
                // Remove the DC component from the even (cosine) phase so it responds to
                // contrast rather than mean luminance. The odd phase is already DC-free.
                val dc = evenSum / (size * size)
                var normE = 0f
                var normO = 0f
                for (i in even.indices) {
                    even[i] -= dc
                    normE += even[i] * even[i]
                    normO += odd[i] * odd[i]
                }
                normE = sqrt(normE).coerceAtLeast(1e-6f)
                normO = sqrt(normO).coerceAtLeast(1e-6f)
                for (i in even.indices) {
                    even[i] /= normE
                    odd[i] /= normO
                }
                evenKernels[ch] = even
                oddKernels[ch] = odd
            }
        }

        columnarGain = Array(n) { ch ->
            FloatArray(h * w) { idx ->
                orientationMap.gainFor(idx / w, idx % w, orientationOf[ch])
            }
        }
    }

    fun orientationOfChannel(ch: Int): Float = orientationOf[ch]

    /**
     * Drives V1 from an LGN image. Input channels are pooled into a single drive first --
     * V1 layer 4C receives convergent M and P input, and the ON/OFF split is recombined into
     * signed contrast at the simple-cell stage, which is what makes simple cells biphasic.
     */
    fun forward(lgnOut: Tensor3): Tensor3 {
        val drive = Tensor3(1, h, w)
        for (i in 0 until drive.plane) {
            val pOn = lgnOut.data[NoraConfig.RET_P_ON * lgnOut.plane + i]
            val pOff = lgnOut.data[NoraConfig.RET_P_OFF * lgnOut.plane + i]
            val mOn = lgnOut.data[NoraConfig.RET_M_ON * lgnOut.plane + i]
            val mOff = lgnOut.data[NoraConfig.RET_M_OFF * lgnOut.plane + i]
            val rgOn = lgnOut.data[NoraConfig.RET_RG_ON * lgnOut.plane + i]
            val rgOff = lgnOut.data[NoraConfig.RET_RG_OFF * lgnOut.plane + i]
            val kOn = lgnOut.data[NoraConfig.RET_K_ON * lgnOut.plane + i]
            val kOff = lgnOut.data[NoraConfig.RET_K_OFF * lgnOut.plane + i]
            // The konio (S-(L+M)) term was missing entirely, which made blue invisible to V1 --
            // and V1 is the only thing the surface channels are predicted from, so a blue
            // subject had no route to the rendered image at all. It is included at the same
            // weight as the R-G opponent term now.
            //
            // HONESTY FLAG, and it is a large one: collapsing eleven retinal channels into ONE
            // scalar drive before the Gabor bank means V1's code cannot represent hue at all,
            // only oriented contrast. Two subjects of different colours but similar contrast
            // produce near-identical V1 activity, so the V1->retina link can only learn their
            // average -- which renders grey. Weighting the chromatic terms up helps a little
            // and cannot fix it. Real V1 has double-opponent colour cells (Livingstone & Hubel
            // 1984; Johnson, Hawken & Shapley 2001); Nora does not, and that is the gap.
            drive.data[i] = (pOn - pOff) +
                NoraTuning.v1MagnoWeight * (mOn - mOff) +
                NoraTuning.v1ChromaticWeight * ((rgOn - rgOff) + (kOn - kOff))
        }
        return energy(drive)
    }

    /** Complex-cell energy for every channel of the bank. */
    fun energy(drive: Tensor3): Tensor3 {
        val out = Tensor3(NoraConfig.V1_CH, h, w)
        Par.forRange(NoraConfig.V1_CH) { ch ->
            val radius = kernelRadius[ch]
            val size = 2 * radius + 1
            val even = evenKernels[ch]
            val odd = oddKernels[ch]
            val gain = columnarGain[ch]
            val base = ch * out.plane
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var accE = 0f
                    var accO = 0f
                    for (dy in -radius..radius) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -radius..radius) {
                            var xx = (x + dx) % w
                            if (xx < 0) xx += w
                            val v = drive.data[yy * w + xx]
                            val ki = (dy + radius) * size + (dx + radius)
                            accE += v * even[ki]
                            accO += v * odd[ki]
                        }
                    }
                    // Adelson-Bergen energy: phase-invariant, contrast-polarity-invariant.
                    out.data[base + y * w + x] = sqrt(accE * accE + accO * accO) * gain[y * w + x]
                }
            }
        }

        // Cross-orientation normalization within the hypercolumn. This is the single most
        // replicated normalization result in V1 -- a mask at the orthogonal orientation
        // suppresses the response without ever driving the cell (Morrone, Burr & Maffei 1982;
        // Heeger 1992; Carandini, Heeger & Movshon 1997).
        Normalization.acrossChannels(out)
        Normalization.lateralInhibition(out)
        out.rectify()
        return out
    }

    // NOTE ON GENERATION: complex-cell energy is not invertible -- phase is discarded by
    // construction, which is exactly what a complex cell is for. So V1 does not project its
    // own activity back down during imagery. The generative path runs through the learned
    // V1->retina predictive link instead, which recovers phase because it was trained against
    // real retinal input rather than derived analytically from the energy. This is the honest
    // boundary of the energy model in the generative direction; see NORA.md.
}
