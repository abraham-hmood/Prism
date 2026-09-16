package com.prism.launcher.nora

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Inferotemporal cortex: view-tolerant object identity in a sparse distributed code, laid out
 * on a topographic sheet from which category-selective patches emerge.
 *
 *  1. SPARSE DISTRIBUTED CODE. IT does not use grandmother cells and it does not use dense
 *     codes. Rolls & Tovee (1995) measured population sparseness around 0.1-0.3, and Young &
 *     Yamane (1992) showed identity is carried by a distributed pattern. Nora enforces this
 *     with a k-winners-take-all threshold implementing fast pooled inhibition -- roughly 6% of
 *     the sheet active at once.
 *
 *  2. VIEW TOLERANCE. IT responses survive substantial changes in position, size, and pose
 *     (Logothetis & Sheinberg 1996; DiCarlo & Cox 2007 frame it as untangling identity
 *     manifolds). Nora gets tolerance from two sources: the log-polar front end makes scale
 *     and in-plane rotation into translations, and IT's receptive fields pool over most of the
 *     V4 sheet so position is largely discarded by the time the code is formed.
 *
 *  3. CATEGORY PATCHES. Faces, bodies, scenes, and objects occupy reproducible, spatially
 *     clustered patches of IT (Kanwisher et al. 1997; Tsao et al. 2006; Epstein & Kanwisher
 *     1998). Nora produces clustering by a topographic self-organizing update: the winning
 *     unit and its cortical neighbours learn together, so similar inputs come to occupy
 *     adjacent territory. Patches emerge; they are not assigned.
 *
 * HONESTY FLAG: a Kohonen SOM is a topographic-clustering ALGORITHM, not a neural mechanism.
 * The biological story for clustered category selectivity involves proto-organization present
 * in infancy that experience elaborates (Arcaro & Livingstone 2017; Livingstone et al. 2019),
 * shaped by retinotopic biases and connectivity, not by a winner-take-all learning rule with a
 * shrinking neighbourhood. Nora reproduces the OUTCOME (clustered selectivity on a sheet) by a
 * different route. This is the clearest analogy-rather-than-mechanism point in the ventral
 * stream, and it is deliberate: the alternative is a developmental simulation Nora does not
 * attempt.
 */
class It {

    private val h = NoraConfig.IT_H
    private val w = NoraConfig.IT_W
    private val c = NoraConfig.IT_CH

    /** SOM neighbourhood radius in cortical units. Shrinks over training, as SOMs require. */
    var neighbourhoodRadius = 2.0f
        private set

    /** Per-column preferred pattern, used only for the topographic update. */
    private val columnPrototype = Array(h * w) { FloatArray(c) }

    private var trainingSteps = 0L

    /**
     * Sparsifies an IT representation into a biologically plausible population code.
     * Called after predictive-coding inference has settled the representation.
     */
    fun sparsify(it: Tensor3) {
        val driveBefore = it.maxAbs()
        Normalization.acrossChannels(it)

        // Order matters, and the original order was wrong.
        //
        // k-WTA models fast pooled inhibition, and pooled inhibition is precisely what sets a
        // cortical layer's firing threshold RELATIVE to how active the population currently is.
        // So it has to run first and define the threshold. An absolute metabolic tax applied
        // beforehand was doing the same job badly: divisive normalization squashes weak drive
        // quadratically, so on an untrained connectome every value fell below the fixed 0.02
        // tax and the entire layer went to zero -- and an all-zero IT means bind() has nothing
        // to associate a caption with, so the semantic hub never learned a single word.
        //
        // Taxing a fraction of the k-WTA threshold instead keeps the metabolic argument (firing
        // costs ATP, weak activity should not survive) while making it structurally impossible
        // for the tax to empty the layer: every survivor sits at or above the threshold, so
        // subtracting a quarter of it leaves them all positive.
        val threshold = Normalization.kWinnersTakeAll(it, NoraConfig.IT_SPARSITY)
        if (threshold > 0f) Normalization.metabolicTax(it, threshold * 0.25f)
        it.rectify()

        // An all-zero IT is the silent failure that cost two training runs. Zero output from
        // zero input is legitimate -- an untrained hub genuinely has no concept to reinstate --
        // so only a layer that had real drive and lost all of it counts as a fault.
        if (driveBefore > 1e-6f && it.maxAbs() <= 0f) {
            NoraHealth.report("IT collapsed to zero despite non-zero drive")
        }
    }

    /**
     * Topographic self-organization. Finds the best-matching cortical column for the current
     * pattern and pulls it and its neighbours toward that pattern, so that nearby cortex comes
     * to prefer similar things.
     */
    fun organize(it: Tensor3, rate: Float = 0.02f) {
        val vec = FloatArray(c)
        var best = -1f
        var bestIdx = 0

        // Column activity vectors.
        for (idx in 0 until h * w) {
            var norm = 0f
            for (ci in 0 until c) {
                val v = it.data[ci * it.plane + idx]
                vec[ci] = v
                norm += v * v
            }
            if (norm > best) {
                best = norm
                bestIdx = idx
            }
        }
        if (best <= 1e-8f) return

        for (ci in 0 until c) vec[ci] = it.data[ci * it.plane + bestIdx]

        val by = bestIdx / w
        val bx = bestIdx % w
        val r2 = 2f * neighbourhoodRadius * neighbourhoodRadius
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dy = (y - by).toFloat()
                val dx = (x - bx).toFloat()
                val g = exp(-(dy * dy + dx * dx) / r2)
                if (g < 0.02f) continue
                val proto = columnPrototype[y * w + x]
                val eta = rate * g
                for (ci in 0 until c) proto[ci] += eta * (vec[ci] - proto[ci])
            }
        }

        trainingSteps++
        // Standard SOM annealing: broad topography first, fine tuning after.
        neighbourhoodRadius = max(0.6f, 2.0f * exp(-trainingSteps / 4000f))
    }

    /**
     * Applies the learned topography as a soft bias on the representation: a column responds
     * more readily to patterns resembling what its neighbourhood has come to prefer. This is
     * what makes the patches functional rather than merely descriptive -- they shape inference,
     * not just the readout.
     */
    fun applyTopographicBias(it: Tensor3, strength: Float = 0.25f) {
        if (strength <= 0f) return
        for (idx in 0 until h * w) {
            val proto = columnPrototype[idx]
            var protoNorm = 0f
            var actNorm = 0f
            var dot = 0f
            for (ci in 0 until c) {
                val a = it.data[ci * it.plane + idx]
                val p = proto[ci]
                dot += a * p
                protoNorm += p * p
                actNorm += a * a
            }
            if (protoNorm < 1e-8f || actNorm < 1e-8f) continue
            val match = dot / sqrt(protoNorm * actNorm)
            val g = 1f + strength * match
            for (ci in 0 until c) it.data[ci * it.plane + idx] *= g
        }
    }

    /**
     * Reports how the sheet is currently partitioned: for each column, the index of the
     * channel it most prefers. Adjacent columns sharing a preference is a patch.
     */
    fun patchMap(): IntArray = IntArray(h * w) { idx ->
        var best = -Float.MAX_VALUE
        var bi = 0
        val proto = columnPrototype[idx]
        for (ci in 0 until c) {
            if (proto[ci] > best) {
                best = proto[ci]
                bi = ci
            }
        }
        bi
    }

    fun saveState(out: java.io.DataOutputStream) {
        out.writeLong(trainingSteps)
        out.writeFloat(neighbourhoodRadius)
        for (proto in columnPrototype) for (v in proto) out.writeFloat(v)
    }

    fun loadState(input: java.io.DataInputStream) {
        trainingSteps = input.readLong()
        neighbourhoodRadius = input.readFloat()
        for (proto in columnPrototype) for (i in proto.indices) proto[i] = input.readFloat()
    }
}
