package com.prism.launcher.modelcraft

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Sliding-window self-attention over an image's patches, for a pixel model.
 *
 * A dense pixel field has no tokens to attend over, so the field is cut into 4×4 patches and each
 * patch becomes one token (48 values for three channels). A short transformer body mixes those
 * tokens: a sliding layer sees [window] neighbouring patches in raster order, a full layer sees the
 * whole field, and one layer in every [fullEvery] -- counted from the last -- is full. The final
 * tokens are averaged into one context vector and added to the hidden layer's pre-activation.
 *
 * The setting is not decoration: with a window the forward pass, the backward pass and the loaded
 * model all use the same masks. With no window the pixel trainer never instantiates this class and
 * the network is exactly the dense predictor it was before.
 *
 * The body is deliberately tiny (16 wide, four heads, at most four layers) because it runs beside a
 * phone-sized pixel network, not instead of one. Its metadata says how many tokens and layers it
 * actually built, so the recipe's nominal window is never quoted back as if this were a 7B model.
 */
internal class PixelPatchAttention(
    private val side: Int,
    private val frames: Int,
    private val hidden: Int,
    private val window: Int,
    private val fullEvery: Int,
    /** 1 for a greyscale image field, 3 for colour. */
    private val channels: Int = 3,
    random: java.util.Random,
) {

    /** Pixels along a patch edge. Four keeps the token count small on a phone-sized field. */
    val patch = 4

    /** Patches along one side; the last row and column are zero-padded when the side is odd. */
    val grid = (side + patch - 1) / patch

    /** Values in one patch: every colour channel of a 4×4 square. */
    val tokenDim = channels * patch * patch

    /** Width of the token space, small enough for the field and wide enough for four heads. */
    val dModel = D_MODEL

    /** Layers in this miniature body. At least two so a sliding and a full layer can differ. */
    val layers = maxOf(2, minOf(4, fullEvery.coerceAtLeast(1)))

    /** One token per patch per frame. */
    val tokenCount = grid * grid * frames

    /** Writes the built shape into a weights-file header, after the dense layers' four entries. */
    val shape: IntArray get() = intArrayOf(tokenDim, dModel, layers, frames, patch)

    val inProj = TrainerKit.gaussian("attn_in_projection", dModel, tokenDim, random)
    val contextProj = TrainerKit.gaussian("attn_context_projection", hidden, dModel, random)
    val contextBias = TrainerKit.Param("attn_context_bias", hidden, 1)

    private val queries = ArrayList<TrainerKit.Param>(layers)
    private val keys = ArrayList<TrainerKit.Param>(layers)
    private val values = ArrayList<TrainerKit.Param>(layers)
    private val outputs = ArrayList<TrainerKit.Param>(layers)

    init {
        for (layer in 0 until layers) {
            queries += TrainerKit.gaussian("attn_q_$layer", dModel, dModel, random)
            keys += TrainerKit.gaussian("attn_k_$layer", dModel, dModel, random)
            values += TrainerKit.gaussian("attn_v_$layer", dModel, dModel, random)
            outputs += TrainerKit.gaussian("attn_o_$layer", dModel, dModel, random)
        }
    }

    /** True when [layer] (0-based) attends over the whole field rather than its window. */
    fun isFull(layer: Int): Boolean = (layer + 1) % fullEvery.coerceAtLeast(1) == 0

    fun parameters(): List<TrainerKit.Param> = buildList {
        add(inProj)
        add(contextProj)
        add(contextBias)
        for (layer in 0 until layers) {
            add(queries[layer])
            add(keys[layer])
            add(values[layer])
            add(outputs[layer])
        }
    }

    /** The keys one token may attend to, for one layer. Raster order, symmetric window. */
    private fun allowed(layer: Int, index: Int): IntArray {
        if (isFull(layer)) return IntArray(tokenCount) { it }
        val left = window / 2
        val from = maxOf(0, index - left)
        val to = minOf(tokenCount - 1, index + (window - left - 1))
        return IntArray(to - from + 1) { from + it }
    }

    /** The field cut into patches, frame by frame and row by row. */
    fun tokensOf(x: DoubleArray): Array<DoubleArray> {
        val plane = side * side
        val frameStride = channels * plane
        return Array(tokenCount) { index ->
            val frame = index / (grid * grid)
            val cell = index % (grid * grid)
            val top = (cell / grid) * patch
            val left = (cell % grid) * patch
            val token = DoubleArray(tokenDim)
            var at = 0
            for (row in 0 until patch) {
                for (column in 0 until patch) {
                    val y = top + row
                    val columnPixel = left + column
                    for (channel in 0 until channels) {
                        token[at++] = if (y < side && columnPixel < side) {
                            x[frame * frameStride + channel * plane + y * side + columnPixel]
                        } else {
                            0.0
                        }
                    }
                }
            }
            token
        }
    }

    /** One forward pass's cached attention, kept for the backward pass. */
    inner class Forward(val tokens: Array<DoubleArray>) {
        val inputs = ArrayList<Array<DoubleArray>>(layers)
        val qs = ArrayList<Array<DoubleArray>>(layers)
        val ks = ArrayList<Array<DoubleArray>>(layers)
        val vs = ArrayList<Array<DoubleArray>>(layers)
        val rows = ArrayList<Array<Array<DoubleArray>>>(layers)
        val mixed = ArrayList<Array<DoubleArray>>(layers)
        val mean = DoubleArray(dModel)
        val context = DoubleArray(hidden)

        init {
            var e = Array(tokens.size) { TrainerKit.linear(tokens[it], inProj) }
            for (layer in 0 until layers) {
                inputs += e
                val q = Array(e.size) { TrainerKit.linear(e[it], queries[layer]) }
                val k = Array(e.size) { TrainerKit.linear(e[it], keys[layer]) }
                val v = Array(e.size) { TrainerKit.linear(e[it], values[layer]) }
                qs += q
                ks += k
                vs += v
                val weights = Array(e.size) { Array(HEADS) { DoubleArray(0) } }
                val attended = Array(e.size) { DoubleArray(dModel) }
                val next = Array(e.size) { e[it].copyOf() }
                for (index in e.indices) {
                    val use = allowed(layer, index)
                    for (head in 0 until HEADS) {
                        val scores = DoubleArray(use.size)
                        var maxScore = -Double.MAX_VALUE
                        for ((slot, other) in use.withIndex()) {
                            var dot = 0.0
                            for (dim in 0 until HEAD_DIM) {
                                dot += q[index][head * HEAD_DIM + dim] * k[other][head * HEAD_DIM + dim]
                            }
                            scores[slot] = dot / sqrt(HEAD_DIM.toDouble())
                            if (scores[slot] > maxScore) maxScore = scores[slot]
                        }
                        var sum = 0.0
                        for (slot in scores.indices) {
                            scores[slot] = exp(scores[slot] - maxScore)
                            sum += scores[slot]
                        }
                        for (slot in scores.indices) scores[slot] /= sum.coerceAtLeast(1e-12)
                        weights[index][head] = scores
                        for ((slot, other) in use.withIndex()) {
                            val weight = scores[slot]
                            for (dim in 0 until HEAD_DIM) {
                                attended[index][head * HEAD_DIM + dim] +=
                                    weight * v[other][head * HEAD_DIM + dim]
                            }
                        }
                    }
                    val projected = TrainerKit.linear(attended[index], outputs[layer])
                    for (dim in 0 until dModel) next[index][dim] += projected[dim]
                }
                rows += weights
                mixed += attended
                e = next
            }
            for (token in e) for (dim in 0 until dModel) mean[dim] += token[dim]
            for (dim in 0 until dModel) mean[dim] /= e.size.coerceAtLeast(1)
            val projected = TrainerKit.linear(mean, contextProj, contextBias)
            System.arraycopy(projected, 0, context, 0, hidden)
        }
    }

    /**
     * Backpropagates [dContext] (the hidden layer's gradient) into the attention parameters.
     *
     * The residual half of every layer means the gradient reaches the layer input unchanged; the
     * attention half needs the softmax Jacobian, exactly as the text model's backward pass does.
     */
    fun backward(cache: Forward, dContext: DoubleArray) {
        TrainerKit.accumulateLinear(cache.mean, dContext, contextProj, contextBias)
        val dMean = DoubleArray(dModel)
        for (row in 0 until hidden) {
            val gradient = dContext[row]
            if (gradient == 0.0) continue
            for (col in 0 until dModel) dMean[col] += gradient * contextProj.at(row, col)
        }
        val perToken = 1.0 / cache.tokens.size.coerceAtLeast(1)
        var dE = Array(cache.tokens.size) { DoubleArray(dModel) { dMean[it] * perToken } }
        for (layer in layers - 1 downTo 0) {
            val input = cache.inputs[layer]
            val q = cache.qs[layer]
            val k = cache.ks[layer]
            val v = cache.vs[layer]
            val rowWeights = cache.rows[layer]
            val attended = cache.mixed[layer]
            val dInput = Array(dE.size) { dE[it].copyOf() }
            val dAttended = Array(dE.size) { DoubleArray(dModel) }
            for (index in dE.indices) {
                // The layer output is input + Wo·attended, so the attended vector's gradient is
                // Wo transposed times the gradient of the output.
                for (row in 0 until dModel) {
                    var sum = 0.0
                    for (col in 0 until dModel) sum += outputs[layer].at(col, row) * dE[index][col]
                    dAttended[index][row] = sum
                }
                TrainerKit.accumulateLinear(attended[index], dE[index], outputs[layer], null)
            }
            val dQ = Array(dE.size) { DoubleArray(dModel) }
            val dK = Array(dE.size) { DoubleArray(dModel) }
            val dV = Array(dE.size) { DoubleArray(dModel) }
            val inverse = 1.0 / sqrt(HEAD_DIM.toDouble())
            for (index in dE.indices) {
                val use = allowed(layer, index)
                for (head in 0 until HEADS) {
                    val weights = rowWeights[index][head]
                    val dA = DoubleArray(use.size)
                    var dot = 0.0
                    for ((slot, other) in use.withIndex()) {
                        var sum = 0.0
                        for (dim in 0 until HEAD_DIM) {
                            sum += dAttended[index][head * HEAD_DIM + dim] *
                                v[other][head * HEAD_DIM + dim]
                        }
                        dA[slot] = sum
                        dot += weights[slot] * sum
                    }
                    for ((slot, other) in use.withIndex()) {
                        val weight = weights[slot]
                        if (weight == 0.0) continue
                        for (dim in 0 until HEAD_DIM) {
                            dV[other][head * HEAD_DIM + dim] +=
                                weight * dAttended[index][head * HEAD_DIM + dim]
                        }
                        val dScore = weight * (dA[slot] - dot)
                        if (dScore == 0.0) continue
                        for (dim in 0 until HEAD_DIM) {
                            dQ[index][head * HEAD_DIM + dim] +=
                                dScore * k[other][head * HEAD_DIM + dim] * inverse
                            dK[other][head * HEAD_DIM + dim] +=
                                dScore * q[index][head * HEAD_DIM + dim] * inverse
                        }
                    }
                }
            }
            for (index in dE.indices) {
                TrainerKit.accumulateLinear(input[index], dQ[index], queries[layer], null)
                TrainerKit.accumulateLinear(input[index], dK[index], keys[layer], null)
                TrainerKit.accumulateLinear(input[index], dV[index], values[layer], null)
                // The query, key and value projections read the layer's input too, so their
                // gradients have to travel back through it -- not only the residual path.
                for (dim in 0 until dModel) {
                    var sum = 0.0
                    for (other in 0 until dModel) {
                        sum += dQ[index][other] * queries[layer].at(other, dim)
                        sum += dK[index][other] * keys[layer].at(other, dim)
                        sum += dV[index][other] * values[layer].at(other, dim)
                    }
                    dInput[index][dim] += sum
                }
            }
            dE = dInput
        }
        for (index in dE.indices) {
            TrainerKit.accumulateLinear(cache.tokens[index], dE[index], inProj, null)
        }
    }

    private companion object {
        const val D_MODEL = 16
        const val HEADS = 4
        const val HEAD_DIM = D_MODEL / HEADS
    }
}
