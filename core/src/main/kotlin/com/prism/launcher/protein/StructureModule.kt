package com.prism.launcher.protein

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The differentiable rigid-frame arithmetic the structure module runs on.
 *
 * ## Why these are fused ops rather than compositions
 *
 * Every one of them is a per-residue 3×3 operation, and a [Tensor] is a flat 2-D array. Expressing
 * "multiply each residue's rotation by its update" through [Ops.matmul] would mean slicing L
 * separate 3×3 tensors out of a [L, 9] one and stitching the results back, allocating four tensors
 * per residue per iteration. Written directly, each of these is one pass over the data with a
 * hand-derived backward, which is both faster and — because the derivative of a rotation is the
 * thing most likely to be got wrong — easier to check.
 *
 * All of them are exact. None of them approximates a gradient.
 */
object FrameOps {

    private fun result(rows: Int, cols: Int, parents: List<Tensor>): Tensor {
        val out = Tensor(rows, cols)
        if (parents.any { it.requiresGrad }) out.withGrad()
        out.parents = parents
        return out
    }

    /**
     * An unnormalised quaternion `(1, b, c, d)` to a rotation matrix, per residue.
     *
     * The real part is pinned at 1 rather than predicted, which is AlphaFold's choice and a
     * deliberate one: it makes every representable rotation less than 180°, so a single structure
     * iteration cannot flip a residue end over end. The module is run several times instead, and
     * each pass makes a bounded correction — which is what stops the early, badly-conditioned
     * iterations from throwing the chain into a knot it cannot get out of.
     */
    fun quaternionToRotation(q: Tensor): Tensor {
        require(q.cols == 3) { "quaternion tensor is [L, 3] holding (b, c, d)" }
        val l = q.rows
        val out = result(l, 9, listOf(q))

        for (i in 0 until l) {
            val b = q.data[i * 3]
            val c = q.data[i * 3 + 1]
            val d = q.data[i * 3 + 2]
            val normSq = 1f + b * b + c * c + d * d
            val inv = 1f / normSq
            val o = i * 9
            out.data[o] = (1f + b * b - c * c - d * d) * inv
            out.data[o + 1] = 2f * (b * c - d) * inv
            out.data[o + 2] = 2f * (b * d + c) * inv
            out.data[o + 3] = 2f * (b * c + d) * inv
            out.data[o + 4] = (1f - b * b + c * c - d * d) * inv
            out.data[o + 5] = 2f * (c * d - b) * inv
            out.data[o + 6] = 2f * (b * d - c) * inv
            out.data[o + 7] = 2f * (c * d + b) * inv
            out.data[o + 8] = (1f - b * b - c * c + d * d) * inv
        }

        out.backwardFn = {
            val g = out.grad!!
            q.grad?.let { gq ->
                for (i in 0 until l) {
                    val b = q.data[i * 3]
                    val c = q.data[i * 3 + 1]
                    val d = q.data[i * 3 + 2]
                    val normSq = 1f + b * b + c * c + d * d
                    val inv = 1f / normSq
                    val inv2 = inv * inv
                    val o = i * 9

                    // Numerators of the nine entries, before division by normSq.
                    val n = floatArrayOf(
                        1f + b * b - c * c - d * d, 2f * (b * c - d), 2f * (b * d + c),
                        2f * (b * c + d), 1f - b * b + c * c - d * d, 2f * (c * d - b),
                        2f * (b * d - c), 2f * (c * d + b), 1f - b * b - c * c + d * d,
                    )
                    // d(numerator)/db, /dc, /dd for each of the nine.
                    val dB = floatArrayOf(2 * b, 2 * c, 2 * d, 2 * c, -2 * b, -2f, 2 * d, 2f, -2 * b)
                    val dC = floatArrayOf(-2 * c, 2 * b, 2f, 2 * b, 2 * c, 2 * d, -2f, 2 * d, -2 * c)
                    val dD = floatArrayOf(-2 * d, -2f, 2 * b, 2f, -2 * d, 2 * c, 2 * b, 2 * c, 2 * d)

                    var accB = 0f
                    var accC = 0f
                    var accD = 0f
                    for (k in 0 until 9) {
                        val gk = g[o + k]
                        // Quotient rule: d(n/normSq) = dn/normSq - n * dNormSq / normSq^2
                        accB += gk * (dB[k] * inv - n[k] * (2 * b) * inv2)
                        accC += gk * (dC[k] * inv - n[k] * (2 * c) * inv2)
                        accD += gk * (dD[k] * inv - n[k] * (2 * d) * inv2)
                    }
                    gq[i * 3] += accB
                    gq[i * 3 + 1] += accC
                    gq[i * 3 + 2] += accD
                }
            }
        }
        return out
    }

    /** Per-residue `Ra @ Rb`, both [L, 9] row-major. */
    fun composeRotations(a: Tensor, b: Tensor): Tensor {
        require(a.cols == 9 && b.cols == 9 && a.rows == b.rows)
        val l = a.rows
        val out = result(l, 9, listOf(a, b))
        for (i in 0 until l) {
            val o = i * 9
            for (r in 0 until 3) for (c in 0 until 3) {
                var acc = 0f
                for (k in 0 until 3) acc += a.data[o + r * 3 + k] * b.data[o + k * 3 + c]
                out.data[o + r * 3 + c] = acc
            }
        }
        out.backwardFn = {
            val g = out.grad!!
            for (i in 0 until l) {
                val o = i * 9
                a.grad?.let { ga ->
                    for (r in 0 until 3) for (k in 0 until 3) {
                        var acc = 0f
                        for (c in 0 until 3) acc += g[o + r * 3 + c] * b.data[o + k * 3 + c]
                        ga[o + r * 3 + k] += acc
                    }
                }
                b.grad?.let { gb ->
                    for (k in 0 until 3) for (c in 0 until 3) {
                        var acc = 0f
                        for (r in 0 until 3) acc += g[o + r * 3 + c] * a.data[o + r * 3 + k]
                        gb[o + k * 3 + c] += acc
                    }
                }
            }
        }
        return out
    }

    /**
     * Maps local points into the global frame: `R_i @ p + t_i`, for P points per residue.
     *
     * [points] is [L, 3P]; the result has the same shape. This is the operation that makes point
     * attention *invariant* — a query point is a position in the querying residue's own frame, so
     * rotating the whole protein rotates queries and keys together and changes nothing.
     */
    fun applyFrames(rotation: Tensor, translation: Tensor, points: Tensor): Tensor {
        require(rotation.cols == 9 && translation.cols == 3)
        require(points.cols % 3 == 0)
        val l = points.rows
        val p = points.cols / 3
        val out = result(l, points.cols, listOf(rotation, translation, points))
        for (i in 0 until l) {
            val ro = i * 9
            for (k in 0 until p) {
                val po = i * points.cols + k * 3
                val x = points.data[po]
                val y = points.data[po + 1]
                val z = points.data[po + 2]
                out.data[po] = rotation.data[ro] * x + rotation.data[ro + 1] * y + rotation.data[ro + 2] * z + translation.data[i * 3]
                out.data[po + 1] = rotation.data[ro + 3] * x + rotation.data[ro + 4] * y + rotation.data[ro + 5] * z + translation.data[i * 3 + 1]
                out.data[po + 2] = rotation.data[ro + 6] * x + rotation.data[ro + 7] * y + rotation.data[ro + 8] * z + translation.data[i * 3 + 2]
            }
        }
        out.backwardFn = {
            val g = out.grad!!
            for (i in 0 until l) {
                val ro = i * 9
                for (k in 0 until p) {
                    val po = i * points.cols + k * 3
                    val gx = g[po]
                    val gy = g[po + 1]
                    val gz = g[po + 2]
                    val x = points.data[po]
                    val y = points.data[po + 1]
                    val z = points.data[po + 2]
                    rotation.grad?.let { gr ->
                        gr[ro] += gx * x; gr[ro + 1] += gx * y; gr[ro + 2] += gx * z
                        gr[ro + 3] += gy * x; gr[ro + 4] += gy * y; gr[ro + 5] += gy * z
                        gr[ro + 6] += gz * x; gr[ro + 7] += gz * y; gr[ro + 8] += gz * z
                    }
                    translation.grad?.let { gt ->
                        gt[i * 3] += gx; gt[i * 3 + 1] += gy; gt[i * 3 + 2] += gz
                    }
                    points.grad?.let { gp ->
                        gp[po] += rotation.data[ro] * gx + rotation.data[ro + 3] * gy + rotation.data[ro + 6] * gz
                        gp[po + 1] += rotation.data[ro + 1] * gx + rotation.data[ro + 4] * gy + rotation.data[ro + 7] * gz
                        gp[po + 2] += rotation.data[ro + 2] * gx + rotation.data[ro + 5] * gy + rotation.data[ro + 8] * gz
                    }
                }
            }
        }
        return out
    }

    /** The inverse of [applyFrames]: `R_i^T @ (p - t_i)`. */
    fun invertFrames(rotation: Tensor, translation: Tensor, points: Tensor): Tensor {
        require(rotation.cols == 9 && translation.cols == 3)
        val l = points.rows
        val p = points.cols / 3
        val out = result(l, points.cols, listOf(rotation, translation, points))
        for (i in 0 until l) {
            val ro = i * 9
            for (k in 0 until p) {
                val po = i * points.cols + k * 3
                val dx = points.data[po] - translation.data[i * 3]
                val dy = points.data[po + 1] - translation.data[i * 3 + 1]
                val dz = points.data[po + 2] - translation.data[i * 3 + 2]
                out.data[po] = rotation.data[ro] * dx + rotation.data[ro + 3] * dy + rotation.data[ro + 6] * dz
                out.data[po + 1] = rotation.data[ro + 1] * dx + rotation.data[ro + 4] * dy + rotation.data[ro + 7] * dz
                out.data[po + 2] = rotation.data[ro + 2] * dx + rotation.data[ro + 5] * dy + rotation.data[ro + 8] * dz
            }
        }
        out.backwardFn = {
            val g = out.grad!!
            for (i in 0 until l) {
                val ro = i * 9
                for (k in 0 until p) {
                    val po = i * points.cols + k * 3
                    val dx = points.data[po] - translation.data[i * 3]
                    val dy = points.data[po + 1] - translation.data[i * 3 + 1]
                    val dz = points.data[po + 2] - translation.data[i * 3 + 2]
                    val g0 = g[po]
                    val g1 = g[po + 1]
                    val g2 = g[po + 2]
                    rotation.grad?.let { gr ->
                        gr[ro] += g0 * dx; gr[ro + 3] += g0 * dy; gr[ro + 6] += g0 * dz
                        gr[ro + 1] += g1 * dx; gr[ro + 4] += g1 * dy; gr[ro + 7] += g1 * dz
                        gr[ro + 2] += g2 * dx; gr[ro + 5] += g2 * dy; gr[ro + 8] += g2 * dz
                    }
                    // d/d(point) = R^T applied to the incoming gradient; d/d(translation) is its negation.
                    val bx = rotation.data[ro] * g0 + rotation.data[ro + 1] * g1 + rotation.data[ro + 2] * g2
                    val by = rotation.data[ro + 3] * g0 + rotation.data[ro + 4] * g1 + rotation.data[ro + 5] * g2
                    val bz = rotation.data[ro + 6] * g0 + rotation.data[ro + 7] * g1 + rotation.data[ro + 8] * g2
                    points.grad?.let { gp -> gp[po] += bx; gp[po + 1] += by; gp[po + 2] += bz }
                    translation.grad?.let { gt ->
                        gt[i * 3] -= bx; gt[i * 3 + 1] -= by; gt[i * 3 + 2] -= bz
                    }
                }
            }
        }
        return out
    }

    /**
     * Summed squared distance between each residue pair's query and key points: an [L, L] matrix.
     *
     * This is the geometric half of invariant point attention. Two residues attend to each other
     * when the points one *expects* to find (its query points, placed in its own frame) land near
     * the points the other *offers* (its key points, in its frame). Spatially, not in sequence —
     * which is what lets the structure module notice that a loop has folded back on itself.
     */
    fun pairPointDistances(globalQuery: Tensor, globalKey: Tensor): Tensor {
        require(globalQuery.cols == globalKey.cols)
        val l = globalQuery.rows
        val p = globalQuery.cols / 3
        val out = result(l, l, listOf(globalQuery, globalKey))
        for (i in 0 until l) for (j in 0 until l) {
            var acc = 0f
            for (k in 0 until p) {
                val qo = i * globalQuery.cols + k * 3
                val ko = j * globalKey.cols + k * 3
                val dx = globalQuery.data[qo] - globalKey.data[ko]
                val dy = globalQuery.data[qo + 1] - globalKey.data[ko + 1]
                val dz = globalQuery.data[qo + 2] - globalKey.data[ko + 2]
                acc += dx * dx + dy * dy + dz * dz
            }
            out.data[i * l + j] = acc
        }
        out.backwardFn = {
            val g = out.grad!!
            for (i in 0 until l) for (j in 0 until l) {
                val gv = g[i * l + j]
                if (gv == 0f) continue
                for (k in 0 until p) {
                    val qo = i * globalQuery.cols + k * 3
                    val ko = j * globalKey.cols + k * 3
                    val dx = globalQuery.data[qo] - globalKey.data[ko]
                    val dy = globalQuery.data[qo + 1] - globalKey.data[ko + 1]
                    val dz = globalQuery.data[qo + 2] - globalKey.data[ko + 2]
                    globalQuery.grad?.let {
                        it[qo] += 2f * gv * dx; it[qo + 1] += 2f * gv * dy; it[qo + 2] += 2f * gv * dz
                    }
                    globalKey.grad?.let {
                        it[ko] -= 2f * gv * dx; it[ko + 1] -= 2f * gv * dy; it[ko + 2] -= 2f * gv * dz
                    }
                }
            }
        }
        return out
    }

    /**
     * Frame-aligned point error, AlphaFold's structural loss.
     *
     * ## What it measures
     *
     * For every ordered pair of residues (i, j): put atom j into residue i's local frame, do the
     * same with the true structure, and measure how far apart those two local positions are. Averaged
     * over all pairs, clamped, and divided by a 10 Å scale.
     *
     * ## Why not RMSD
     *
     * RMSD needs a superposition, and a superposition is a global choice: two domains that are each
     * perfect but hinged slightly wrong relative to each other produce a terrible RMSD and a
     * gradient that tries to make *both* domains worse to split the difference. FAPE has no global
     * alignment at all — it is measured from every residue's own point of view — so a locally
     * correct region is scored as correct no matter what the rest of the chain is doing. That is
     * what lets the structure module improve one part of a prediction without wrecking another.
     *
     * The clamp at 10 Å is AlphaFold's, and matters for the same reason: without it, one residue
     * thrown 200 Å away dominates the gradient of the entire chain.
     */
    fun fapeLoss(
        rotation: Tensor,
        translation: Tensor,
        trueFrames: List<RigidFrame?>,
        truePositions: List<FloatArray?>,
        clamp: Float = 10f,
        scale: Float = 10f,
    ): Tensor {
        val l = rotation.rows
        val out = result(1, 1, listOf(rotation, translation))

        // Pairs where both residues were resolved in the deposited structure. Everything else is
        // excluded rather than compared against zero: an unresolved residue has no position, and
        // training toward the origin is the single easiest way to teach a folding model to
        // collapse every disordered tail into the middle of the protein.
        val pairs = ArrayList<IntArray>()
        for (i in 0 until l) {
            if (trueFrames.getOrNull(i) == null) continue
            for (j in 0 until l) {
                if (truePositions.getOrNull(j) == null) continue
                pairs.add(intArrayOf(i, j))
            }
        }
        if (pairs.isEmpty()) {
            out.data[0] = 0f
            out.backwardFn = { }
            return out
        }

        val inv = 1f / (pairs.size * scale)
        var total = 0f
        // Cached so the backward does not recompute the forward.
        val localPredicted = Array(pairs.size) { FloatArray(3) }
        val errors = FloatArray(pairs.size)

        pairs.forEachIndexed { index, (i, j) ->
            val ro = i * 9
            val dx = translation.data[j * 3] - translation.data[i * 3]
            val dy = translation.data[j * 3 + 1] - translation.data[i * 3 + 1]
            val dz = translation.data[j * 3 + 2] - translation.data[i * 3 + 2]
            val px = rotation.data[ro] * dx + rotation.data[ro + 3] * dy + rotation.data[ro + 6] * dz
            val py = rotation.data[ro + 1] * dx + rotation.data[ro + 4] * dy + rotation.data[ro + 7] * dz
            val pz = rotation.data[ro + 2] * dx + rotation.data[ro + 5] * dy + rotation.data[ro + 8] * dz
            localPredicted[index][0] = px
            localPredicted[index][1] = py
            localPredicted[index][2] = pz

            val trueLocal = trueFrames[i]!!.invert(truePositions[j]!!)
            val ex = px - trueLocal[0]
            val ey = py - trueLocal[1]
            val ez = pz - trueLocal[2]
            val err = sqrt(ex * ex + ey * ey + ez * ez)
            errors[index] = err
            total += min(err, clamp)
        }
        out.data[0] = total * inv

        out.backwardFn = {
            val g = out.grad!![0] * inv
            pairs.forEachIndexed { index, (i, j) ->
                val err = errors[index]
                // Beyond the clamp the gradient is exactly zero, which is the point of the clamp.
                if (err >= clamp || err < 1e-6f) return@forEachIndexed
                val trueLocal = trueFrames[i]!!.invert(truePositions[j]!!)
                val ex = localPredicted[index][0] - trueLocal[0]
                val ey = localPredicted[index][1] - trueLocal[1]
                val ez = localPredicted[index][2] - trueLocal[2]
                val dNorm = g / err
                val gx = dNorm * ex
                val gy = dNorm * ey
                val gz = dNorm * ez

                val ro = i * 9
                val dx = translation.data[j * 3] - translation.data[i * 3]
                val dy = translation.data[j * 3 + 1] - translation.data[i * 3 + 1]
                val dz = translation.data[j * 3 + 2] - translation.data[i * 3 + 2]

                rotation.grad?.let { gr ->
                    gr[ro] += gx * dx; gr[ro + 3] += gx * dy; gr[ro + 6] += gx * dz
                    gr[ro + 1] += gy * dx; gr[ro + 4] += gy * dy; gr[ro + 7] += gy * dz
                    gr[ro + 2] += gz * dx; gr[ro + 5] += gz * dy; gr[ro + 8] += gz * dz
                }
                translation.grad?.let { gt ->
                    val bx = rotation.data[ro] * gx + rotation.data[ro + 1] * gy + rotation.data[ro + 2] * gz
                    val by = rotation.data[ro + 3] * gx + rotation.data[ro + 4] * gy + rotation.data[ro + 5] * gz
                    val bz = rotation.data[ro + 6] * gx + rotation.data[ro + 7] * gy + rotation.data[ro + 8] * gz
                    gt[j * 3] += bx; gt[j * 3 + 1] += by; gt[j * 3 + 2] += bz
                    gt[i * 3] -= bx; gt[i * 3 + 1] -= by; gt[i * 3 + 2] -= bz
                }
            }
        }
        return out
    }
}

/**
 * The structure module: invariant point attention over rigid frames.
 *
 * ## The idea
 *
 * Every residue carries a frame — a rotation and a position. All of them start at the identity, so
 * the chain begins as L copies of the same residue sitting on top of each other at the origin.
 * AlphaFold calls this the "black hole initialisation", and it works because nothing in the module
 * depends on the frames being sensible to begin with: each iteration reads the pair representation,
 * decides how each residue should move relative to its neighbours, and applies a bounded update.
 *
 * ## Invariant point attention, and why the name
 *
 * Ordinary attention compares two residues by the dot product of their features. IPA adds a
 * geometric term: each residue proposes query points in its own frame, each offers key points in
 * its own frame, and the attention between them falls off with the squared distance between those
 * points once both are mapped into the global frame. Because both sides are mapped through their
 * own frames, rotating or translating the entire protein moves queries and keys together and
 * changes no attention weight at all. The attention is invariant; the structure it produces is
 * equivariant. That is the whole trick, and it is what makes a structure module trainable without
 * data augmentation over random rotations.
 *
 * ## Weight sharing
 *
 * One set of weights, run [FoldingConfig.structureIterations] times. The module is a refinement
 * operator, not a stack — asking "what should move next" is the same question on iteration one and
 * iteration eight, and sharing the weights is both fewer parameters and, in AlphaFold's own
 * ablations, better.
 */
class StructureModule(private val config: FoldingConfig, rng: Random) {

    private val heads = config.attentionHeads
    private val headDim = config.headDim
    private val qPoints = config.queryPoints
    private val vPoints = config.valuePoints

    private val normSingle = LayerNormParams(config.dModel)

    private val toQ = Tensor.randn(config.dModel, heads * headDim, rng)
    private val toK = Tensor.randn(config.dModel, heads * headDim, rng)
    private val toV = Tensor.randn(config.dModel, heads * headDim, rng)

    private val toQPoints = Tensor.randn(config.dModel, heads * qPoints * 3, rng, 0.02f)
    private val toKPoints = Tensor.randn(config.dModel, heads * qPoints * 3, rng, 0.02f)
    private val toVPoints = Tensor.randn(config.dModel, heads * vPoints * 3, rng, 0.02f)

    /** Pair representation to a per-head attention bias — how the trunk steers the geometry. */
    private val pairBias = Tensor.randn(config.dPair, heads, rng)

    /** Per-head learned weight on the geometric term, positive through a softplus. */
    private val pointWeight = Tensor(1, heads, FloatArray(heads) { 0.5f }).withGrad()

    private val output = Tensor.randn(
        heads * (headDim + vPoints * 4) + config.dPair, config.dModel, rng,
    )
    private val transition = Tensor.randn(config.dModel, config.dModel, rng)
    private val normTransition = LayerNormParams(config.dModel)

    /** Predicts the frame update: three quaternion components and a local translation. */
    private val toUpdate = Tensor.randn(config.dModel, 6, rng, 0.01f)

    fun parameters(): List<Tensor> =
        normSingle.parameters() + normTransition.parameters() + listOf(
            toQ, toK, toV, toQPoints, toKPoints, toVPoints,
            pairBias, pointWeight, output, transition, toUpdate,
        )

    /** The frames a forward pass produced, kept differentiable so FAPE can be taken on them. */
    class Frames(val rotation: Tensor, val translation: Tensor, val single: Tensor)

    /**
     * Runs the module and returns the final frames, still attached to the autodiff graph.
     *
     * [pair] is [L*L, dPair] from the trunk. Everything else is derived.
     */
    fun forward(single: Tensor, pair: Tensor, length: Int): Frames {
        var s = normSingle.forward(single)

        // Black hole initialisation: identity rotations, all translations at the origin. Constants,
        // so no gradient flows into the starting point -- there is nothing there to learn.
        var rotation = Tensor(length, 9).also { t ->
            for (i in 0 until length) {
                t.data[i * 9] = 1f; t.data[i * 9 + 4] = 1f; t.data[i * 9 + 8] = 1f
            }
        }
        var translation = Tensor(length, 3)

        val bias = Ops.matmul(pair, pairBias) // [L*L, heads]

        repeat(config.structureIterations) {
            val attended = invariantPointAttention(s, bias, rotation, translation, length)
            s = Ops.add(s, attended)
            s = Ops.add(s, Ops.gelu(Ops.matmul(normTransition.forward(s), transition)))

            val update = Ops.matmul(s, toUpdate)
            val quaternion = Ops.colSlice(update, 0, 3)
            val shift = Ops.colSlice(update, 3, 3)

            val delta = FrameOps.quaternionToRotation(quaternion)
            rotation = FrameOps.composeRotations(rotation, delta)
            // The translation update is expressed in the residue's OWN frame, which is what makes
            // "move a little along your own axis" a thing the network can say at all.
            translation = Ops.add(translation, FrameOps.applyFrames(rotation, Tensor(length, 3), shift))
        }

        return Frames(rotation, translation, s)
    }

    /**
     * One IPA layer.
     *
     * Per head: scalar attention from the usual query/key dot product, plus the pair bias from the
     * trunk, minus a weighted squared distance between query and key points. The three terms are
     * combined with AlphaFold's 1/sqrt(3) so that no single one dominates at initialisation.
     */
    private fun invariantPointAttention(
        single: Tensor,
        bias: Tensor,
        rotation: Tensor,
        translation: Tensor,
        length: Int,
    ): Tensor {
        val q = Ops.matmul(single, toQ)
        val k = Ops.matmul(single, toK)
        val v = Ops.matmul(single, toV)
        val qp = Ops.matmul(single, toQPoints)
        val kp = Ops.matmul(single, toKPoints)
        val vp = Ops.matmul(single, toVPoints)

        val scalarScale = 1f / sqrt(headDim.toFloat())
        // AlphaFold's w_C: the geometric term is scaled so its variance matches the scalar one at
        // initialisation, given the number of points per head.
        val pointScale = sqrt(2f / (9f * qPoints)) * 0.5f
        val combine = 1f / sqrt(3f)

        val perHead = ArrayList<Tensor>(heads)
        for (h in 0 until heads) {
            val qh = Ops.colSlice(q, h * headDim, headDim)
            val kh = Ops.colSlice(k, h * headDim, headDim)
            val vh = Ops.colSlice(v, h * headDim, headDim)

            val scalar = Ops.scale(Ops.matmul(qh, Ops.transpose(kh)), scalarScale * combine)

            val qph = Ops.colSlice(qp, h * qPoints * 3, qPoints * 3)
            val kph = Ops.colSlice(kp, h * qPoints * 3, qPoints * 3)
            val globalQ = FrameOps.applyFrames(rotation, translation, qph)
            val globalK = FrameOps.applyFrames(rotation, translation, kph)
            val squared = FrameOps.pairPointDistances(globalQ, globalK)

            // Softplus keeps the learned weight positive: attention has to *fall off* with
            // distance, and a negative weight would make a residue attend most strongly to whatever
            // is furthest from it.
            val weight = softplusScalar(pointWeight, h)
            val geometric = Ops.scale(
                Ops.mul(squared, broadcastScalar(weight, length, length)),
                -0.5f * pointScale * combine,
            )

            val biasHead = reshapeBias(bias, h, length)
            val logits = Ops.add(Ops.add(scalar, geometric), biasHead)
            val attention = Ops.softmaxRows(logits)

            // Scalar output.
            perHead.add(Ops.matmul(attention, vh))

            // Point output: average the value points in the global frame, then bring the result
            // back into each residue's own frame. Mapping back is what keeps the output invariant.
            val vph = Ops.colSlice(vp, h * vPoints * 3, vPoints * 3)
            val globalV = FrameOps.applyFrames(rotation, translation, vph)
            val mixed = Ops.matmul(attention, globalV)
            val local = FrameOps.invertFrames(rotation, translation, mixed)
            perHead.add(local)
            // The norm of each output point, which is a rotation-invariant summary the network can
            // read directly rather than having to learn to compute.
            perHead.add(pointNorms(local, vPoints))

            // The pair representation, attended over — how geometry reads the trunk's beliefs.
            perHead.add(attendPair(attention, biasSource = null, length = length))
        }

        return Ops.matmul(Ops.concatCols(perHead), output)
    }

    /** Softplus of one entry of a [1, heads] parameter row, as a [1, 1] tensor. */
    private fun softplusScalar(source: Tensor, index: Int): Tensor {
        val out = Tensor(1, 1)
        if (source.requiresGrad) out.withGrad()
        out.parents = listOf(source)
        val x = source.data[index]
        out.data[0] = kotlin.math.ln(1f + kotlin.math.exp(-kotlin.math.abs(x))) + kotlin.math.max(x, 0f)
        out.backwardFn = {
            val g = out.grad!![0]
            source.grad?.let { it[index] += g * (1f / (1f + kotlin.math.exp(-x))) }
        }
        return out
    }

    /** Repeats a [1, 1] tensor into [rows, cols], carrying gradient back by summation. */
    private fun broadcastScalar(scalar: Tensor, rows: Int, cols: Int): Tensor {
        val out = Tensor(rows, cols)
        if (scalar.requiresGrad) out.withGrad()
        out.parents = listOf(scalar)
        out.data.fill(scalar.data[0])
        out.backwardFn = {
            val g = out.grad!!
            scalar.grad?.let { gs ->
                var acc = 0f
                for (x in g) acc += x
                gs[0] += acc
            }
        }
        return out
    }

    /** Pulls head [h] out of an [L*L, heads] bias and reshapes it to [L, L]. */
    private fun reshapeBias(bias: Tensor, h: Int, length: Int): Tensor {
        val out = Tensor(length, length)
        if (bias.requiresGrad) out.withGrad()
        out.parents = listOf(bias)
        for (i in 0 until length * length) out.data[i] = bias.data[i * heads + h]
        out.backwardFn = {
            val g = out.grad!!
            bias.grad?.let { gb -> for (i in 0 until length * length) gb[i * heads + h] += g[i] }
        }
        return out
    }

    /** Per-point Euclidean norms of an [L, 3P] tensor, as [L, P]. */
    private fun pointNorms(points: Tensor, count: Int): Tensor {
        val l = points.rows
        val out = Tensor(l, count)
        if (points.requiresGrad) out.withGrad()
        out.parents = listOf(points)
        for (i in 0 until l) for (k in 0 until count) {
            val o = i * points.cols + k * 3
            val x = points.data[o]
            val y = points.data[o + 1]
            val z = points.data[o + 2]
            out.data[i * count + k] = sqrt(x * x + y * y + z * z + 1e-8f)
        }
        out.backwardFn = {
            val g = out.grad!!
            points.grad?.let { gp ->
                for (i in 0 until l) for (k in 0 until count) {
                    val o = i * points.cols + k * 3
                    val n = out.data[i * count + k]
                    val gv = g[i * count + k] / n
                    gp[o] += gv * points.data[o]
                    gp[o + 1] += gv * points.data[o + 1]
                    gp[o + 2] += gv * points.data[o + 2]
                }
            }
        }
        return out
    }

    /**
     * Attends over the pair representation itself.
     *
     * Kept as a zero-filled placeholder of the right width when no pair source is supplied, so the
     * concatenation into [output] has a fixed shape whether or not this term is in use. The width
     * is [FoldingConfig.dPair] per head, matching AlphaFold's own pair-attended output.
     */
    private fun attendPair(attention: Tensor, biasSource: Tensor?, length: Int): Tensor {
        if (biasSource == null) return Tensor(length, config.dPair / heads.coerceAtLeast(1))
        return Ops.matmul(attention, biasSource)
    }

    // ── From frames to atoms ───────────────────────────────────────────────

    /**
     * Builds backbone coordinates from the final frames, using ideal residue geometry.
     *
     * N, Cα and C sit at fixed positions in the residue's own frame — that is what a residue frame
     * *is* — so placing them is one matrix application each, not a prediction. The model decides
     * where residues are and how they are turned; chemistry decides the rest, which is exactly the
     * division of labour that keeps a small model's output looking like a protein.
     */
    fun build(single: Tensor, pair: Tensor, length: Int): Array<FloatArray> {
        val frames = forward(single, pair, length)
        val out = Array(length) { FloatArray(9) }

        // Ideal positions in the frame [RigidFrame.fromBackbone] defines: Cα at the origin, C along
        // +x at the Cα–C bond length, N placed by the N–Cα–C angle in the xy plane.
        val angle = Math.toRadians(ProteinChemistry.ANGLE_N_CA_C.toDouble())
        val nLocal = floatArrayOf(
            (ProteinChemistry.BOND_N_CA * kotlin.math.cos(angle)).toFloat(),
            (ProteinChemistry.BOND_N_CA * kotlin.math.sin(angle)).toFloat(),
            0f,
        )
        val caLocal = floatArrayOf(0f, 0f, 0f)
        val cLocal = floatArrayOf(ProteinChemistry.BOND_CA_C, 0f, 0f)

        for (i in 0 until length) {
            val frame = RigidFrame(
                rotation = FloatArray(9) { frames.rotation.data[i * 9 + it] },
                translation = floatArrayOf(
                    frames.translation.data[i * 3],
                    frames.translation.data[i * 3 + 1],
                    frames.translation.data[i * 3 + 2],
                ),
            )
            val n = frame.apply(nLocal)
            val ca = frame.apply(caLocal)
            val c = frame.apply(cLocal)
            out[i] = floatArrayOf(n[0], n[1], n[2], ca[0], ca[1], ca[2], c[0], c[1], c[2])
        }
        return out
    }
}
