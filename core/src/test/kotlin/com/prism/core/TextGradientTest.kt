package com.prism.core

import com.prism.launcher.modelcraft.TextTrainer
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The transformer writes its own backward pass, so a mistake in the normalisation or the residual
 * addition does not throw: it trains slowly and looks like a bad dataset. This compares the
 * analytic gradients against a central difference on the real network, parameter by parameter.
 */
class TextGradientTest {

    private val config = TextTrainer.Config(
        positions = 8,
        layers = 2,
        dim = 8,
        heads = 2,
        hidden = 16,
        epochs = 1,
        learningRate = 0.002,
        gradientClip = 1.0,
        weightDecay = 0.0,
        warmupRatio = 0.0,
        scheduler = "constant",
        seed = 11L,
        window = 4,
        fullEvery = 2,
        batchSize = 2,
    )

    @Test
    fun `the analytic gradient matches a central difference`() {
        val net = TextTrainer.Net(config, Random(7L), masked = false, vocabSize = 14)
        val tokens = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val step = net.Step(tokens, Random(3L))
        step.loss()
        step.backward()

        val epsilon = 1e-5
        var checked = 0
        var worst = 0.0
        val failures = mutableListOf<String>()
        for (parameter in net.parameters()) {
            val indices = (0 until parameter.data.size).filter { it % 7 == 0 }.take(3)
            for (index in indices) {
                val analytic = parameter.grad[index]
                val original = parameter.data[index]

                parameter.data[index] = original + epsilon
                val plus = net.Step(tokens, Random(3L)).loss()
                parameter.data[index] = original - epsilon
                val minus = net.Step(tokens, Random(3L)).loss()
                parameter.data[index] = original

                val numeric = (plus - minus) / (2 * epsilon)
                val error = abs(numeric - analytic) / maxOf(1.0, abs(numeric), abs(analytic))
                worst = maxOf(worst, error)
                checked++
                if (error >= 2e-3) failures += "${parameter.name}[$index]: a=$analytic n=$numeric"
            }
        }
        assertTrue(checked > 30, "too few gradients were checked: $checked")
        assertTrue(
            worst < 2e-3,
            "worst relative error was $worst, ${failures.size} failing: ${failures.joinToString(" | ")}",
        )
    }
}
