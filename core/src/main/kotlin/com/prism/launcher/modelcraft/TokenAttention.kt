package com.prism.launcher.modelcraft

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Causal self-attention over a game model's shape-token positions.
 *
 * The shape-token head is a sequence model: one position per shape dimension, each predicted from
 * the positions before it. This body gives those positions a learned context. A sliding layer sees
 * the last [window] positions, a full layer sees the whole history, and one layer in every
 * [fullEvery] -- counted from the last -- is full. The mask is causal in both cases, because these
 * tokens are generated left to right and a model that peeked at a later token would be trained on
 * an answer it will not have at sampling time.
 *
 * The projector is identity-shaped: [contextProjection] writes back into the same input space the
 * head's MLP reads, and its output is added to the position's input. That is not a hidden-state
 * shortcut; it is what allows a dense per-position head to consume attention without changing the
 * rest of its forward pass.
 */
internal class TokenAttention(
    private val inputSize: Int,
    private val window: Int,
    private val fullEvery: Int,
    random: java.util.Random,
) {

    val dModel = 16

    /** At least two layers, so a sliding and a full layer can differ; at most four on a phone. */
    val layers = maxOf(2, minOf(4, fullEvery.coerceAtLeast(1)))

    val shape: IntArray get() = intArrayOf(inputSize, dModel, layers)

    val inProj = TrainerKit.gaussian("game_t_attn_in", dModel, inputSize, random)
    val contextProj = TrainerKit.gaussian("game_t_attn_context", inputSize, dModel, random)
    val contextBias = TrainerKit.Param("game_t_attn_context_bias", inputSize, 1)

    private val queries = ArrayList<TrainerKit.Param>(layers)
    private val keys = ArrayList<TrainerKit.Param>(layers)
    private val values = ArrayList<TrainerKit.Param>(layers)
    private val outputs = ArrayList<TrainerKit.Param>(layers)

    init {
        for (layer in 0 until layers) {
            queries += TrainerKit.gaussian("game_t_attn_q_$layer", dModel, dModel, random)
            keys += TrainerKit.gaussian("game_t_attn_k_$layer", dModel, dModel, random)
            values += TrainerKit.gaussian("game_t_attn_v_$layer", dModel, dModel, random)
            outputs += TrainerKit.gaussian("game_t_attn_o_$layer", dModel, dModel, random)
        }
    }

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

    /** A causal window: everything up to [index], narrowed to the last [window] on a sliding layer. */
    private fun allowed(layer: Int, index: Int): IntArray {
        val from = if (isFull(layer)) 0 else maxOf(0, index - window + 1)
        return IntArray(index - from + 1) { from + it }
    }

    /** Per-position contexts, added to the head's raw input at each position. */
    inner class Forward(val inputs: Array<DoubleArray>) {
        val layerInputs = ArrayList<Array<DoubleArray>>(layers)
        val qs = ArrayList<Array<DoubleArray>>(layers)
        val ks = ArrayList<Array<DoubleArray>>(layers)
        val vs = ArrayList<Array<DoubleArray>>(layers)
        val rows = ArrayList<Array<Array<DoubleArray>>>(layers)
        val mixed = ArrayList<Array<DoubleArray>>(layers)
        lateinit var finalStates: Array<DoubleArray>
        val contexts = Array(inputs.size) { DoubleArray(inputSize) }

        init {
            var e = Array(inputs.size) { TrainerKit.linear(inputs[it], inProj) }
            for (layer in 0 until layers) {
                layerInputs += e
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
                        var best = -Double.MAX_VALUE
                        for ((slot, other) in use.withIndex()) {
                            var dot = 0.0
                            for (dim in 0 until HEAD_DIM) {
                                dot += q[index][head * HEAD_DIM + dim] * k[other][head * HEAD_DIM + dim]
                            }
                            scores[slot] = dot / sqrt(HEAD_DIM.toDouble())
                            if (scores[slot] > best) best = scores[slot]
                        }
                        var total = 0.0
                        for (slot in scores.indices) {
                            scores[slot] = exp(scores[slot] - best)
                            total += scores[slot]
                        }
                        for (slot in scores.indices) scores[slot] /= total.coerceAtLeast(1e-12)
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
            finalStates = e
            for (position in e.indices) {
                val projected = TrainerKit.linear(e[position], contextProj, contextBias)
                System.arraycopy(projected, 0, contexts[position], 0, inputSize)
            }
        }
    }

    /** Backpropagates the head's per-position input gradient into every attention parameter. */
    fun backward(cache: Forward, dContexts: Array<DoubleArray>) {
        var dE = Array(dContexts.size) { position -> DoubleArray(dModel) }
        for (position in dContexts.indices) {
            for (col in 0 until dModel) {
                var sum = 0.0
                for (row in 0 until inputSize) sum += dContexts[position][row] * contextProj.at(row, col)
                dE[position][col] = sum
            }
            TrainerKit.accumulateLinear(cache.finalStates[position], dContexts[position], contextProj, contextBias)
        }

        for (layer in layers - 1 downTo 0) {
            val input = cache.layerInputs[layer]
            val q = cache.qs[layer]
            val k = cache.ks[layer]
            val v = cache.vs[layer]
            val rowWeights = cache.rows[layer]
            val attended = cache.mixed[layer]
            val dInput = Array(dE.size) { dE[it].copyOf() }
            val dAttended = Array(dE.size) { DoubleArray(dModel) }
            for (position in dE.indices) {
                for (row in 0 until dModel) {
                    var sum = 0.0
                    for (col in 0 until dModel) sum += outputs[layer].at(col, row) * dE[position][col]
                    dAttended[position][row] = sum
                }
                TrainerKit.accumulateLinear(attended[position], dE[position], outputs[layer], null)
            }
            val dQ = Array(dE.size) { DoubleArray(dModel) }
            val dK = Array(dE.size) { DoubleArray(dModel) }
            val dV = Array(dE.size) { DoubleArray(dModel) }
            val inverse = 1.0 / sqrt(HEAD_DIM.toDouble())
            for (position in dE.indices) {
                val use = allowed(layer, position)
                for (head in 0 until HEADS) {
                    val weights = rowWeights[position][head]
                    val dA = DoubleArray(use.size)
                    var dot = 0.0
                    for ((slot, other) in use.withIndex()) {
                        var sum = 0.0
                        for (dim in 0 until HEAD_DIM) {
                            sum += dAttended[position][head * HEAD_DIM + dim] *
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
                                weight * dAttended[position][head * HEAD_DIM + dim]
                        }
                        val dScore = weight * (dA[slot] - dot)
                        if (dScore == 0.0) continue
                        for (dim in 0 until HEAD_DIM) {
                            dQ[position][head * HEAD_DIM + dim] +=
                                dScore * k[other][head * HEAD_DIM + dim] * inverse
                            dK[other][head * HEAD_DIM + dim] +=
                                dScore * q[position][head * HEAD_DIM + dim] * inverse
                        }
                    }
                }
            }
            for (position in dE.indices) {
                TrainerKit.accumulateLinear(input[position], dQ[position], queries[layer], null)
                TrainerKit.accumulateLinear(input[position], dK[position], keys[layer], null)
                TrainerKit.accumulateLinear(input[position], dV[position], values[layer], null)
                for (dim in 0 until dModel) {
                    var sum = 0.0
                    for (other in 0 until dModel) {
                        sum += dQ[position][other] * queries[layer].at(other, dim)
                        sum += dK[position][other] * keys[layer].at(other, dim)
                        sum += dV[position][other] * values[layer].at(other, dim)
                    }
                    dInput[position][dim] += sum
                }
            }
            dE = dInput
        }
        for (position in dE.indices) {
            TrainerKit.accumulateLinear(cache.inputs[position], dE[position], inProj, null)
        }
    }

    private companion object {
        const val HEADS = 4
        const val HEAD_DIM = 4
    }
}
