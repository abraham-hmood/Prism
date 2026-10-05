package com.prism.launcher.protein

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gradient checks.
 *
 * Every backward in this package is hand-derived, and a hand-derived gradient that is subtly wrong
 * does not crash — it trains slowly, or to the wrong place, and looks like a hyperparameter problem
 * for a week. Central finite differences catch that in a second, so every op gets checked here
 * rather than trusted.
 *
 * The tolerance is relative and fairly loose (2%) because the parameters are `Float`: a central
 * difference at h=1e-2 on single precision is itself only good to a few decimal places, and
 * tightening the tolerance would test the arithmetic of the check rather than the correctness of
 * the gradient.
 */
class FoldTensorTest {

    private val rng = Random(7)

    /**
     * Compares the analytic gradient of [build] with respect to [input] against a central
     * finite difference.
     */
    private fun checkGradient(
        input: Tensor,
        tolerance: Float = 0.02f,
        step: Float = 1e-2f,
        build: (Tensor) -> Tensor,
    ) {
        input.withGrad()
        input.zeroGrad()
        val loss = build(input)
        loss.backward()
        val analytic = input.grad!!.copyOf()

        var worst = 0f
        var worstAt = -1
        for (i in 0 until input.size) {
            val original = input.data[i]

            input.data[i] = original + step
            val up = build(input).data[0]
            input.data[i] = original - step
            val down = build(input).data[0]
            input.data[i] = original

            val numeric = (up - down) / (2f * step)
            val scale = maxOf(abs(numeric), abs(analytic[i]), 1f)
            val error = abs(numeric - analytic[i]) / scale
            if (error > worst) {
                worst = error
                worstAt = i
            }
        }
        assertTrue(
            worst < tolerance,
            "gradient mismatch of ${"%.4f".format(worst)} at index $worstAt " +
                "(analytic ${analytic.getOrNull(worstAt)})",
        )
    }

    /** Sums a tensor to a scalar, so any op can be checked through a single root. */
    private fun sumAll(t: Tensor): Tensor {
        val out = Tensor(1, 1)
        if (t.requiresGrad) out.withGrad()
        out.parents = listOf(t)
        var acc = 0f
        for (x in t.data) acc += x
        out.data[0] = acc
        out.backwardFn = {
            val g = out.grad!![0]
            t.grad?.let { gt -> for (i in gt.indices) gt[i] += g }
        }
        return out
    }

    @Test
    fun `matmul gradient matches finite differences`() {
        val a = Tensor.randn(4, 5, rng)
        val b = Tensor.randn(5, 3, rng)
        checkGradient(a) { x -> sumAll(Ops.matmul(x, b)) }
        checkGradient(b) { x -> sumAll(Ops.matmul(a, x)) }
    }

    @Test
    fun `softmax gradient matches finite differences`() {
        val a = Tensor.randn(3, 6, rng)
        // Weighted so the check is not against a constant: plain softmax rows sum to one, and the
        // gradient of a constant is zero everywhere, which any broken implementation also produces.
        val weights = Tensor.randn(6, 1, rng)
        checkGradient(a) { x -> sumAll(Ops.matmul(Ops.softmaxRows(x), weights)) }
    }

    @Test
    fun `layer norm gradient matches finite differences`() {
        val a = Tensor.randn(4, 8, rng)
        val gain = Tensor(1, 8, FloatArray(8) { 1f + 0.1f * it }).withGrad()
        val bias = Tensor(1, 8, FloatArray(8) { 0.05f * it }).withGrad()
        val weights = Tensor.randn(8, 1, rng)
        checkGradient(a) { x -> sumAll(Ops.matmul(Ops.layerNorm(x, gain, bias), weights)) }
        checkGradient(gain) { g -> sumAll(Ops.matmul(Ops.layerNorm(a, g, bias), weights)) }
    }

    @Test
    fun `gelu and sigmoid gradients match finite differences`() {
        val a = Tensor.randn(3, 7, rng)
        checkGradient(a) { x -> sumAll(Ops.gelu(x)) }
        checkGradient(a) { x -> sumAll(Ops.sigmoid(x)) }
    }

    @Test
    fun `cross entropy gradient matches finite differences`() {
        val logits = Tensor.randn(5, 9, rng)
        val targets = intArrayOf(3, 0, 8, -1, 5)
        checkGradient(logits) { x -> Ops.crossEntropyRows(x, targets) }
    }

    @Test
    fun `a target of minus one contributes nothing`() {
        val logits = Tensor.randn(4, 6, rng).also { it.zeroGrad() }
        val loss = Ops.crossEntropyRows(logits, intArrayOf(-1, -1, -1, -1))
        loss.backward()
        assertTrue(loss.data[0] == 0f, "an entirely unscored batch should have zero loss")
        assertTrue(logits.grad!!.all { it == 0f }, "unscored rows must not receive gradient")
    }

    @Test
    fun `concat and slice round-trip gradients`() {
        val a = Tensor.randn(4, 3, rng)
        val b = Tensor.randn(4, 5, rng)
        val weights = Tensor.randn(8, 1, rng)
        checkGradient(a) { x -> sumAll(Ops.matmul(Ops.concatCols(listOf(x, b)), weights)) }
        checkGradient(b) { x -> sumAll(Ops.matmul(Ops.concatCols(listOf(a, x)), weights)) }

        val wide = Tensor.randn(3, 9, rng)
        val slim = Tensor.randn(4, 1, rng)
        checkGradient(wide) { x -> sumAll(Ops.matmul(Ops.colSlice(x, 2, 4), slim)) }
    }

    @Test
    fun `embedding accumulates gradient for a repeated index`() {
        val table = Tensor.randn(6, 3, rng).also { it.zeroGrad() }
        val loss = sumAll(Ops.embed(table, intArrayOf(2, 2, 4)))
        loss.backward()
        val g = table.grad!!
        // Row 2 was used twice and row 4 once; rows never used get nothing.
        assertTrue(abs(g[2 * 3] - 2f) < 1e-4f, "repeated index should accumulate, got ${g[2 * 3]}")
        assertTrue(abs(g[4 * 3] - 1f) < 1e-4f)
        assertTrue(g[0] == 0f && g[1 * 3] == 0f)
    }

    @Test
    fun `AdamW moves a parameter toward its target`() {
        // The smallest possible end-to-end check: one parameter, one quadratic, does it descend.
        val p = Tensor(1, 1, floatArrayOf(5f)).withGrad()
        val optimiser = AdamW(listOf(p), learningRate = 0.5f, weightDecay = 0f)
        repeat(200) {
            optimiser.zeroGrad()
            // loss = (p - 2)^2, so the gradient is 2(p - 2).
            val diff = Ops.add(p, Tensor(1, 1, floatArrayOf(-2f)))
            val loss = Ops.mul(diff, diff)
            loss.backward()
            optimiser.step()
        }
        assertTrue(abs(p.data[0] - 2f) < 0.05f, "expected to converge to 2, got ${p.data[0]}")
    }

    @Test
    fun `gradient clipping bounds the update`() {
        val p = Tensor(1, 2, floatArrayOf(0f, 0f)).withGrad()
        p.grad!![0] = 1000f
        p.grad!![1] = 1000f
        val optimiser = AdamW(listOf(p), learningRate = 1f, weightDecay = 0f)
        optimiser.step(clip = 1f)
        // Adam normalises by its own second moment, so the magnitude lands near the learning rate
        // rather than near the gradient; the point of the check is that it is bounded at all.
        assertTrue(abs(p.data[0]) < 2f, "clipped step should be small, got ${p.data[0]}")
    }
}
