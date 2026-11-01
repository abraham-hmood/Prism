package com.prism.core

import com.prism.launcher.modelcraft.TokenAttention
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game token attention body, checked against its own forward pass.
 *
 * Two things are easy to get wrong and both would still train: the softmax backward, and the causal
 * mask. The first is pinned with a central difference, the second by changing a future position and
 * requiring an earlier context not to move.
 */
class TokenAttentionTest {

    private fun attention(seed: Long = 5L): TokenAttention =
        TokenAttention(inputSize = 12, window = 2, fullEvery = 2, random = java.util.Random(seed))

    private fun inputs(length: Int = 5, seed: Long = 17L): Array<DoubleArray> {
        val random = java.util.Random(seed)
        return Array(length) { DoubleArray(12) { random.nextGaussian() } }
    }

    private fun loss(attention: TokenAttention, inputs: Array<DoubleArray>): Double {
        val contexts = attention.Forward(inputs).contexts
        return 0.5 * contexts.sumOf { row -> row.sumOf { it * it } }
    }

    @Test
    fun `the analytic gradient of the token context loss matches a central difference`() {
        val attention = attention()
        val inputs = inputs()
        val forward = attention.Forward(inputs)
        attention.backward(forward, forward.contexts.map { it.copyOf() }.toTypedArray())

        val checks = listOf(
            attention.inProj to 5,
            attention.contextProj to 8,
            attention.parameters().first { it.name == "game_t_attn_q_1" } to 3,
        )
        for ((param, index) in checks) {
            val original = param.data[index]
            val epsilon = 1e-5
            param.data[index] = original + epsilon
            val plus = loss(attention, inputs)
            param.data[index] = original - epsilon
            val minus = loss(attention, inputs)
            param.data[index] = original
            val numeric = (plus - minus) / (2 * epsilon)
            val analytic = param.grad[index]
            val tolerance = 3e-4 * maxOf(1.0, abs(numeric))
            assertTrue(
                abs(numeric - analytic) <= tolerance,
                "${param.name}[$index]: numeric $numeric, analytic $analytic",
            )
        }
    }

    @Test
    fun `a later position cannot change an earlier context`() {
        val attention = attention()
        val inputs = inputs()
        val before = attention.Forward(inputs).contexts[1].copyOf()

        inputs[4][3] += 2.0
        val after = attention.Forward(inputs).contexts[1]
        for (index in before.indices) {
            assertEquals(before[index], after[index], 1e-12, "position 1 saw position 4")
        }
    }
}
