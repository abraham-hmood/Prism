package com.prism.launcher.aether

import java.util.Random
import kotlin.test.Test

/**
 * Times one Aether cortical layer at the geometry Aether actually runs.
 *
 * NOT A CORRECTNESS TEST. It is here so that "training is slow" can be a number instead of an
 * impression, and so a change to the hot loop can be shown to have helped rather than asserted to
 * have helped. It prints and never asserts a timing: a machine-dependent threshold in CI fails for
 * reasons that have nothing to do with the code.
 *
 * The geometry is [AetherGeometry.DEFAULT]'s -- the parietal layer is 1024 inputs to 1024 neurons, a
 * million weights, and the sensory hub feeding it is v3Dim + temporalDim = 1024 wide. At 16 time
 * steps that is 16.8 million multiply-accumulates through this one layer per forward pass.
 *
 * ## Two things this has to get right, having got both wrong first
 *
 * **Warm-up, for the JIT.** The first timed case in a fresh JVM pays for class loading and for C2
 * not having compiled the loop yet. Measured cold, two layers of identical shape running identical
 * code came out 3x apart purely by which ran first.
 *
 * **Warm-up, for the habituation state.** This matters more and is specific to this layer. The
 * input to the matrix is `frame[i] - habituationState[i] * gain`, and habituationState starts at
 * zero -- so on the very first forward pass the input is the raw spike train, which is 5% dense, and
 * the zero-skip in the inner loop does 95% less work than it will ever do again. Once habituation
 * warms up the input is dense. Timing the first pass measures a transient that never recurs, so
 * every case here is run until that has settled before the clock starts.
 *
 * Run it with:
 *   gradlew :core:test --tests '*AetherLayerBenchmark*'
 */
class AetherLayerBenchmark {

    private val steps = 16
    private val warmup = 6
    private val iterations = 12

    private fun spikes(steps: Int, width: Int, density: Float, seed: Long): SpikeSequence {
        val rng = Random(seed)
        val sequence = SpikeSequence(steps, width, 1, 1)
        for (t in 0 until steps) {
            val frame = sequence[t]
            for (i in 0 until width) {
                frame[i] = if (rng.nextFloat() < density) 1f else 0f
            }
        }
        return sequence
    }

    private fun layer(inputSize: Int, numNeurons: Int, facilitation: Boolean = false) =
        LIFCortexLayer(
            inputSize = inputSize,
            numNeurons = numNeurons,
            noiseStd = 0f,                // the RNG is not what is being measured
            facilitation = facilitation,
            rng = Random(1234),
        )

    private fun bench(label: String, body: () -> Unit) {
        repeat(warmup) { body() }
        var best = Double.MAX_VALUE
        var total = 0.0
        repeat(iterations) {
            val start = System.nanoTime()
            body()
            val elapsed = (System.nanoTime() - start) / 1e9
            total += elapsed
            if (elapsed < best) best = elapsed
        }
        val mean = total / iterations
        // MACs through the matrix: steps x inputSize x numNeurons, reported as effective rate so the
        // number can be compared against what the machine is actually capable of.
        println(
            String.format(
                "BENCH %-30s mean %7.1f ms   best %7.1f ms",
                label, mean * 1000, best * 1000,
            )
        )
    }

    @Test
    fun `forward at default geometry`() {
        val geometry = AetherGeometry.DEFAULT
        val hub = geometry.v3Dim + geometry.temporalDim

        println("geometry: hub=$hub parietal=${geometry.parietalNeurons} steps=$steps")
        println("weights per layer: ${hub.toLong() * geometry.parietalNeurons}")
        println("MACs per forward:  ${steps.toLong() * hub * geometry.parietalNeurons}")

        val parietal = layer(hub, geometry.parietalNeurons)
        val parietalInput = spikes(steps, hub, density = 0.05f, seed = 99)
        bench("parietal forward") { parietal.forward(parietalInput) }

        val facilitating = layer(hub, geometry.parietalNeurons, facilitation = true)
        bench("parietal forward (facil)") { facilitating.forward(parietalInput) }

        val hippocampal = layer(geometry.parietalNeurons, geometry.hippocampalNeurons)
        val hippocampalInput = spikes(steps, geometry.parietalNeurons, density = 0.05f, seed = 7)
        bench("hippocampal forward") { hippocampal.forward(hippocampalInput) }
    }
}
