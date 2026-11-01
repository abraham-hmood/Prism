package com.prism.core

import com.prism.launcher.modelcraft.PixelTrainer
import com.prism.launcher.modelcraft.TrainerKit
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The conditioned pixel denoiser's gradients, against finite differences.
 *
 * A two-hidden-layer network with FiLM and a caption vector has several places to be transposed: the
 * second dense layer, the scale/shift path, and the caption half of the input. This samples a weight
 * from each of those and asks the analytic backward pass to agree with the loss actually changing.
 * The tolerance is loose because the difference quotient is; a wrong transpose is off by orders of
 * magnitude, not by a percent.
 */
class PixelGradientTest {

    private val config = PixelTrainer.Config(
        side = 8,
        epochs = 1,
        learningRate = 0.001,
        gradientClip = 1.0,
        seed = 11L,
        window = 0,
        fullEvery = 0,
        hidden = 32,
    )

    private fun example(): PixelTrainer.Example {
        val side = config.side
        val pixels = DoubleArray(3 * side * side) { index ->
            val plane = side * side
            val x = (index % side) / side.toDouble()
            val y = ((index % plane) / side) / side.toDouble()
            when (index / plane) {
                0 -> x * 2 - 1
                1 -> y * 2 - 1
                else -> 0.2
            }
        }
        return PixelTrainer.Example(listOf(pixels), null, "gradient.png", "a bright square")
    }

    @Test
    fun `the analytic gradient matches a central difference`() {
        val inputDim = 3 * config.side * config.side + PixelTrainer.TIME_DIM + PixelTrainer.CAPTION_DIM
        val outputDim = 3 * config.side * config.side
        val net = PixelTrainer.Net(config, inputDim, outputDim, Random(5))
        val sample = example()
        val step = PixelTrainer.Step(
            PixelTrainer.Task.IMAGE, config, net, sample, 0, Random(99),
        )
        step.backward()

        fun lossAt(): Double = PixelTrainer.Step(
            PixelTrainer.Task.IMAGE, config, net, sample, 0, Random(99),
        ).loss()

        val picks = listOf(
            net.w1 to 3,
            net.w2 to 7,
            net.w3 to 5,
            net.filmScale to 9,
            net.filmShift to 2,
            net.b1 to 1,
        )
        var checked = 0
        for ((param, index) in picks) {
            val analytic = param.grad[index]
            val original = param.data[index]
            val epsilon = 1e-5
            param.data[index] = original + epsilon
            val up = lossAt()
            param.data[index] = original - epsilon
            val down = lossAt()
            param.data[index] = original
            val numeric = (up - down) / (2 * epsilon)
            val scale = maxOf(1.0, abs(numeric), abs(analytic))
            val error = abs(analytic - numeric) / scale
            assertTrue(
                error < 2e-3,
                "${param.name}[$index]: analytic $analytic vs numeric $numeric (error $error)",
            )
            checked++
        }
        assertTrue(checked == picks.size)
        assertTrue(net.parameterCount() > 0)
        assertTrue(net.parameters().all { it.data.all(Double::isFinite) })
    }

    @Test
    fun `caption conditioning changes the prediction`() {
        val inputDim = 3 * config.side * config.side + PixelTrainer.TIME_DIM + PixelTrainer.CAPTION_DIM
        val outputDim = 3 * config.side * config.side
        val net = PixelTrainer.Net(config, inputDim, outputDim, Random(7))
        // Make the FiLM path non-identity, as training would.
        for (index in net.filmScale.data.indices) {
            net.filmScale.data[index] = 0.05 * ((index % 7) - 3)
            net.filmShift.data[index] = 0.02 * ((index % 5) - 2)
        }
        val side = config.side
        val pixels = 3 * side * side
        val input = DoubleArray(inputDim)
        for (index in 0 until pixels) input[index] = 0.3
        val embedding = PixelTrainer.CaptionEncoder.encode("a sneaker")
        System.arraycopy(embedding, 0, input, pixels + PixelTrainer.TIME_DIM, PixelTrainer.CAPTION_DIM)
        val withCaption = net.Forward(input).output
        System.arraycopy(
            DoubleArray(PixelTrainer.CAPTION_DIM), 0, input,
            pixels + PixelTrainer.TIME_DIM, PixelTrainer.CAPTION_DIM,
        )
        val without = net.Forward(input).output
        val difference = withCaption.indices.sumOf { abs(withCaption[it] - without[it]) }
        assertTrue(difference > 1e-6, "the caption path is not connected to the output")
    }
}
