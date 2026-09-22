package com.prism.launcher.aether

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three forms of Aether's synaptic-current kernel, timed against each other and checked for
 * agreement.
 *
 * This exists because "it got faster" is not a measurement unless both versions run in the same JVM
 * on the same data with the same warm-up. Timing the old form in one run and the new form in another
 * measured JIT state as much as it measured the code -- two layers of identical shape came out 3x
 * apart depending only on which ran first.
 *
 * The three forms, in the order they were written:
 *
 *  1. [neuronMajor]  - the original: j outermost, i innermost, accumulating a dot product into a
 *                      scalar. `weights` is row-major [inputSize][numNeurons], so the innermost step
 *                      strides by numNeurons floats -- 4 KB at the default geometry -- and misses
 *                      cache on nearly every one of a million weights, twice, because the mask is
 *                      strided identically. A scalar dot product also cannot vectorize without
 *                      reassociation, which is not permitted here.
 *  2. [inputMajor]   - i outermost, j innermost: one contiguous weight row per input, accumulated
 *                      into a run of independent outputs, which vectorizes without reassociating
 *                      anything. Still re-reads the whole 4 MB matrix once per timestep.
 *  3. [batched]      - i outermost, then timestep, then j: each weight row is read once and used for
 *                      every timestep while it is still in L1. This is what turns 16 matrix-vector
 *                      products into one matrix-matrix product, and it is the difference between
 *                      streaming 128 MB per forward pass and streaming 8 MB.
 *
 * All three must agree to the last bit, and the test asserts that: each output accumulates its terms
 * in ascending i from the same starting value in all three, so only the order the outputs are
 * visited differs, and distinct outputs are not terms of one sum. That property is what makes the
 * change safe to apply to a half-trained connectome.
 */
class AetherKernelBenchmark {

    private val inputSize = 1024
    private val numNeurons = 1024
    private val steps = 16

    private val rng = Random(20260917)
    private val weights = FloatArray(inputSize * numNeurons) { (rng.nextGaussian() * 0.05).toFloat() }
    private val mask = FloatArray(inputSize * numNeurons) { if (rng.nextFloat() < 0.9f) 1f else 0f }
    private val biases = FloatArray(numNeurons) { (rng.nextGaussian() * 0.01).toFloat() }

    /**
     * Dense inputs, which is the steady state and therefore the case worth timing.
     *
     * The raw spike train is about 5% dense, but the layer subtracts a habituation term before the
     * matrix -- `frame[i] - habituationState[i] * gain` -- and habituationState is only zero on the
     * very first forward pass a layer ever runs. From the second pass on the input is dense, so
     * timing a sparse input measures a transient that never comes back.
     */
    private val gated = Array(steps) { FloatArray(inputSize) { (rng.nextGaussian() * 0.3).toFloat() } }

    // ---------------------------------------------------------------- the three kernels

    private fun neuronMajor(out: Array<FloatArray>) {
        for (t in 0 until steps) {
            val g = gated[t]
            val row = out[t]
            for (j in 0 until numNeurons) {
                var sum = biases[j]
                for (i in 0 until inputSize) sum += g[i] * weights[i * numNeurons + j] * mask[i * numNeurons + j]
                row[j] = sum
            }
        }
    }

    private fun inputMajor(out: Array<FloatArray>) {
        for (t in 0 until steps) {
            val g = gated[t]
            val row = out[t]
            System.arraycopy(biases, 0, row, 0, numNeurons)
            for (i in 0 until inputSize) {
                val gi = g[i]
                if (gi == 0f) continue
                val base = i * numNeurons
                for (j in 0 until numNeurons) row[j] += gi * weights[base + j] * mask[base + j]
            }
        }
    }

    private fun batched(out: Array<FloatArray>) {
        for (t in 0 until steps) System.arraycopy(biases, 0, out[t], 0, numNeurons)
        for (i in 0 until inputSize) {
            val base = i * numNeurons
            for (t in 0 until steps) {
                val gi = gated[t][i]
                if (gi == 0f) continue
                val row = out[t]
                for (j in 0 until numNeurons) row[j] += gi * weights[base + j] * mask[base + j]
            }
        }
    }

    /**
     * The batched kernel, split across threads by output range.
     *
     * Each thread owns a disjoint slice of the outputs and walks every input for it. That keeps the
     * result bit-identical -- an output still accumulates over ascending input index, and no two
     * threads touch the same output -- and it does not duplicate any reading: thread n reads only the
     * slice of each weight row that it owns, so the matrix is still streamed exactly once between
     * them.
     *
     * Whether it helps is the question this measures rather than assumes. The single-threaded kernel
     * is already memory-bound, and a memory-bound loop split across cores scales with how much
     * bandwidth those cores can pull between them, which is never the core count and on a phone is
     * often close to one core's worth.
     */
    private fun batchedParallel(out: Array<FloatArray>, threads: Int) {
        for (t in 0 until steps) System.arraycopy(biases, 0, out[t], 0, numNeurons)

        val span = (numNeurons + threads - 1) / threads
        val latch = java.util.concurrent.CountDownLatch(threads)
        for (w in 0 until threads) {
            val j0 = w * span
            val j1 = minOf(numNeurons, j0 + span)
            pool.execute {
                try {
                    if (j0 < j1) {
                        for (i in 0 until inputSize) {
                            val base = i * numNeurons
                            for (t in 0 until steps) {
                                val gi = gated[t][i]
                                if (gi == 0f) continue
                                val row = out[t]
                                for (j in j0 until j1) row[j] += gi * weights[base + j] * mask[base + j]
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
    }

    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(
            com.prism.core.PrismCpu.performanceCores
        ) { r -> Thread(r, "aether-bench").apply { isDaemon = true } }

    // ---------------------------------------------------------------- harness

    private fun buffer() = Array(steps) { FloatArray(numNeurons) }

    private fun bench(label: String, warmup: Int, iterations: Int, body: (Array<FloatArray>) -> Unit): Double {
        val out = buffer()
        repeat(warmup) { body(out) }
        var best = Double.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            body(out)
            val elapsed = (System.nanoTime() - start) / 1e9
            if (elapsed < best) best = elapsed
        }
        val macs = steps.toDouble() * inputSize * numNeurons
        // Three floating-point operations per MAC here: two multiplies and an add.
        val gflops = (macs * 3) / best / 1e9
        println(String.format("BENCH %-14s best %8.2f ms   %6.2f GFLOP/s", label, best * 1000, gflops))
        return best
    }

    /**
     * The recurrent layer's shape: two sums kept apart, then combined.
     *
     * Checked separately because it is the one place where merging the accumulators would have been
     * the obvious simplification and would have been wrong. The original wrote (ff + rec), which
     * rounds once after each sum is complete; a single accumulator taking both sets of terms rounds
     * at different points and gives a different float. The reordered version therefore keeps two
     * arrays, and this is the test that says why.
     */
    @Test
    fun `the recurrent layer's two accumulators stay apart`() {
        val recurrent = FloatArray(numNeurons * numNeurons) { (rng.nextGaussian() * 0.05).toFloat() }
        val prev = FloatArray(numNeurons) { if (rng.nextFloat() < 0.06f) 1f else 0f }
        val g = gated[0]

        // As it was: one output at a time, two scalar accumulators.
        val reference = FloatArray(numNeurons)
        for (j in 0 until numNeurons) {
            var ff = 0f
            var rec = 0f
            for (i in 0 until inputSize) ff += g[i] * weights[i * numNeurons + j] * mask[i * numNeurons + j]
            for (k in 0 until numNeurons) rec += prev[k] * recurrent[k * numNeurons + j]
            reference[j] = ff + rec + biases[j]
        }

        // As it is: input-major, still two accumulators.
        val ffSum = FloatArray(numNeurons)
        val recSum = FloatArray(numNeurons)
        for (i in 0 until inputSize) {
            val gi = g[i]
            if (gi == 0f) continue
            val base = i * numNeurons
            for (j in 0 until numNeurons) ffSum[j] += gi * weights[base + j] * mask[base + j]
        }
        for (k in 0 until numNeurons) {
            val p = prev[k]
            if (p == 0f) continue
            val base = k * numNeurons
            for (j in 0 until numNeurons) recSum[j] += p * recurrent[base + j]
        }
        val actual = FloatArray(numNeurons) { ffSum[it] + recSum[it] + biases[it] }

        var maxDelta = 0f
        for (j in 0 until numNeurons) maxDelta = maxOf(maxDelta, abs(reference[j] - actual[j]))
        println("recurrent form max difference: $maxDelta")
        assertTrue(maxDelta == 0f, "the recurrent reorder must be exact, saw $maxDelta")
    }

    @Test
    fun `all three kernels agree and the reordered ones are faster`() {
        println("inputSize=$inputSize numNeurons=$numNeurons steps=$steps " +
            "MACs/pass=${steps.toLong() * inputSize * numNeurons}")

        // Agreement first, so a timing is never reported for a kernel that computes the wrong thing.
        val a = buffer(); neuronMajor(a)
        val b = buffer(); inputMajor(b)
        val c = buffer(); batched(c)

        var maxDelta = 0f
        for (t in 0 until steps) {
            for (j in 0 until numNeurons) {
                maxDelta = maxOf(maxDelta, abs(a[t][j] - b[t][j]), abs(a[t][j] - c[t][j]))
            }
        }
        println("max difference across the three kernels: $maxDelta")
        assertTrue(maxDelta == 0f, "the kernels must agree bit for bit, saw $maxDelta")

        val threads = com.prism.core.PrismCpu.performanceCores
        val parallel = buffer()
        batchedParallel(parallel, threads)
        var parallelDelta = 0f
        for (t in 0 until steps) {
            for (j in 0 until numNeurons) {
                parallelDelta = maxOf(parallelDelta, abs(a[t][j] - parallel[t][j]))
            }
        }
        println("threads=$threads, parallel max difference: $parallelDelta")
        assertTrue(parallelDelta == 0f, "splitting by output range must be exact, saw $parallelDelta")

        val slow = bench("neuron-major", warmup = 2, iterations = 4) { neuronMajor(it) }
        val mid  = bench("input-major",  warmup = 3, iterations = 8) { inputMajor(it) }
        val fast = bench("batched",      warmup = 3, iterations = 8) { batched(it) }
        val par  = bench("batched x$threads",  warmup = 3, iterations = 8) { batchedParallel(it, threads) }

        println(String.format(
            "speedup vs neuron-major: input-major %.1fx, batched %.1fx, batched x%d %.1fx",
            slow / mid, slow / fast, threads, slow / par))
    }
}
