package com.prism.core

import com.prism.launcher.modelcraft.PixelPatchAttention
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pixel attention body's backward pass, checked against the forward pass.
 *
 * This is the one part of the sliding-window change that cannot be verified by reading a summary:
 * a wrong softmax derivative still trains, just not toward the objective it claims. The check is
 * the usual central difference on a few weights, with the loss taken as half the squared norm of
 * the context vector.
 */
class PixelPatchAttentionTest {

    private fun attention(seed: Long = 3L): PixelPatchAttention = PixelPatchAttention(
        side = 8,
        frames = 1,
        hidden = 4,
        window = 3,
        fullEvery = 2,
        random = java.util.Random(seed),
    )

    private fun image(side: Int = 8, seed: Long = 9L): DoubleArray {
        val random = java.util.Random(seed)
        return DoubleArray(3 * side * side) { random.nextDouble() * 2.0 - 1.0 }
    }

    private fun loss(attention: PixelPatchAttention, x: DoubleArray): Double {
        val context = attention.Forward(attention.tokensOf(x)).context
        return 0.5 * context.sumOf { it * it }
    }

    @Test
    fun `the analytic gradient of the context loss matches a central difference`() {
        val attention = attention()
        val x = image()
        val forward = attention.Forward(attention.tokensOf(x))
        attention.backward(forward, forward.context.copyOf())

        // Two different tensors and entries, so the check covers the input projection and the
        // attention output projection rather than one lucky path.
        val checks = listOf(
            attention.inProj to 7,
            attention.contextProj to 3,
            attention.parameters().first { it.name == "attn_q_1" } to 5,
        )
        for ((param, index) in checks) {
            val original = param.data[index]
            val epsilon = 1e-5
            param.data[index] = original + epsilon
            val plus = loss(attention, x)
            param.data[index] = original - epsilon
            val minus = loss(attention, x)
            param.data[index] = original
            val numeric = (plus - minus) / (2 * epsilon)
            val analytic = param.grad[index]
            val tolerance = 2e-4 * maxOf(1.0, abs(numeric))
            assertTrue(
                abs(numeric - analytic) <= tolerance,
                "${param.name}[$index]: numeric $numeric, analytic $analytic",
            )
        }
    }
}
