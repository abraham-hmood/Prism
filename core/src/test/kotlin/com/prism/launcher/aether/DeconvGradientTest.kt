package com.prism.launcher.aether

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Finite-difference check on [DeconvLIFCortexLayer]'s gradient.
 *
 * This layer's `backward` used to return a zero sequence, which silently froze the whole visual
 * motor strip -- not just its own weights, but everything upstream of it, since a zero gradient
 * blocks the chain. Replacing that with real math is only an improvement if the math is right: a
 * subtly wrong gradient does not fail loudly, it quietly trains the network in a slightly wrong
 * direction, which is exactly the class of bug that is hardest to find later. So it is checked
 * against numerical differentiation rather than assumed.
 *
 * The spike nonlinearity is a step function, so the analytic gradient is a SURROGATE and can only
 * agree with a finite difference where the surrogate is what the network actually uses. These
 * tests therefore probe the differentiable part of the path -- weights -> transposed convolution
 * -> membrane -- by scoring the pre-spike membrane directly, which is the quantity `backward`
 * chains through.
 */
class DeconvGradientTest {

    private fun makeLayer(seed: Long = 7L) = DeconvLIFCortexLayer(
        inC = 2, inH = 3, inW = 3, filters = 2, kernel = 3, stride = 2,
        noiseStd = 0f,          // deterministic: a jittered membrane cannot be finite-differenced
        initStddev = 0.35f, rng = Random(seed)
    )

    /** Sum of the membrane the transposed conv produces for one step -- the scalar we differentiate. */
    private fun membraneSum(layer: DeconvLIFCortexLayer, input: SpatialFrame): Float {
        val out = AetherConv.conv2dTransposeSame(
            input, layer.weights, layer.biases, layer.kernel, layer.filters,
            layer.stride, layer.outH, layer.outW
        )
        var s = 0f
        for (v in out.data) s += v
        return s
    }

    private fun randomInput(rng: Random, c: Int, h: Int, w: Int): SpatialFrame {
        val f = SpatialFrame(c, h, w)
        for (i in f.data.indices) f.data[i] = rng.nextFloat()
        return f
    }

    @Test
    fun weightGradientMatchesFiniteDifference() {
        val layer = makeLayer()
        val rng = Random(11L)
        val input = randomInput(rng, layer.inC, layer.inH, layer.inW)

        // dLoss/dOutput = 1 everywhere, so the analytic weight gradient of sum(output).
        val dOut = SpatialFrame(layer.filters, layer.outH, layer.outW).apply { fill(1f) }
        val dW = FloatArray(layer.weights.size)
        val dB = FloatArray(layer.filters)
        AetherConv.conv2dTransposeWeightGrad(input, dOut, dW, dB, layer.kernel, layer.stride)

        val eps = 1e-2f
        var checked = 0
        var worst = 0f
        for (i in layer.weights.indices) {
            val original = layer.weights[i]
            layer.weights[i] = original + eps
            val plus = membraneSum(layer, input)
            layer.weights[i] = original - eps
            val minus = membraneSum(layer, input)
            layer.weights[i] = original

            val numeric = (plus - minus) / (2f * eps)
            val err = abs(numeric - dW[i]) / maxOf(1f, abs(numeric))
            if (err > worst) worst = err
            checked++
        }
        assertTrue(checked > 0, "no weights were checked")
        assertTrue(worst < 1e-2f, "worst relative weight-gradient error $worst over $checked weights")
    }

    @Test
    fun inputGradientMatchesFiniteDifference() {
        val layer = makeLayer(seed = 23L)
        val rng = Random(29L)
        val input = randomInput(rng, layer.inC, layer.inH, layer.inW)

        val dOut = SpatialFrame(layer.filters, layer.outH, layer.outW).apply { fill(1f) }
        val dIn = AetherConv.conv2dTransposeInputGrad(
            dOut, layer.weights, layer.kernel, layer.inC, layer.stride, layer.inH, layer.inW
        )

        val eps = 1e-2f
        var worst = 0f
        for (i in input.data.indices) {
            val original = input.data[i]
            input.data[i] = original + eps
            val plus = membraneSum(layer, input)
            input.data[i] = original - eps
            val minus = membraneSum(layer, input)
            input.data[i] = original

            val numeric = (plus - minus) / (2f * eps)
            val err = abs(numeric - dIn.data[i]) / maxOf(1f, abs(numeric))
            if (err > worst) worst = err
        }
        assertTrue(worst < 1e-2f, "worst relative input-gradient error $worst")
    }

    /**
     * The regression this whole change is about: `backward` must return something non-zero, and
     * must populate the layer's own weight gradient. Before, both were identically zero and the
     * motor strip could never learn.
     */
    @Test
    fun backwardProducesNonZeroGradients() {
        val layer = makeLayer(seed = 31L)
        val rng = Random(37L)
        val steps = 2
        val inputs = SpikeSequence(steps, layer.inC, layer.inH, layer.inW)
        for (t in 0 until steps) for (i in inputs[t].data.indices) inputs[t].data[i] = rng.nextFloat()

        layer.resetState()
        layer.zeroGrad()
        val out = layer.forward(inputs, habituationGain = 0f)

        val dOut = SpikeSequence(steps, layer.filters, layer.outH, layer.outW)
        for (t in 0 until steps) dOut[t].fill(1f)
        val dIn = layer.backward(dOut)

        assertTrue(dIn.steps == steps, "backward returned the wrong number of steps")
        var wSum = 0f
        for (v in layer.weightGrad) wSum += abs(v)
        assertTrue(wSum > 0f, "weight gradient is identically zero -- the layer is still frozen")

        // The membrane must have actually crossed threshold somewhere, otherwise the surrogate
        // is being evaluated far from the step and this test proves nothing about live training.
        var spikes = 0f
        for (t in 0 until steps) for (v in out[t].data) spikes += v
        assertTrue(spikes > 0f, "no spikes: raise initStddev so the test exercises a live regime")
    }

    /** Adam must actually move the weights once a gradient exists. */
    @Test
    fun applyGradientsUpdatesWeights() {
        val layer = makeLayer(seed = 41L)
        val before = layer.weights.copyOf()
        layer.zeroGrad()
        for (i in layer.weightGrad.indices) layer.weightGrad[i] = 0.1f
        layer.applyGradients(0.01f)
        var moved = 0
        for (i in before.indices) if (before[i] != layer.weights[i]) moved++
        assertTrue(moved == before.size, "only $moved of ${before.size} weights moved")
    }
}
