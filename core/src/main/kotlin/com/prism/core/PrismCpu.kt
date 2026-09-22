package com.prism.core

import java.io.File

/**
 * What kind of cores this machine actually has.
 *
 * ## Why a core count is not a core count
 *
 * Every phone worth running a model on is big.LITTLE, and the clusters are not close in speed. A
 * Snapdragon 8 Gen 1, measured from its own sysfs:
 *
 * | cores | part | max clock | fraction of peak |
 * |---|---|---|---|
 * | 4 | Cortex-A510 | 1.79 GHz | 0.60 |
 * | 3 | Cortex-A710 | 2.50 GHz | 0.83 |
 * | 1 | Cortex-X2   | 3.00 GHz | 1.00 |
 *
 * `availableProcessors()` returns 8 and tells you nothing about that spread.
 *
 * ## Why the spread costs throughput rather than adding it
 *
 * The thread pools that matter here -- ggml's graph execution and Nora's parallel loops -- divide
 * work into equal pieces and then wait for all of them at a barrier. Under that arrangement the
 * slowest thread sets the pace of every step, so handing a piece of work to a core running at 0.60x
 * the clock does not add 60% of a core, it adds a stall to every barrier. Past the point where the
 * fast cores are busy, more threads make things slower, and the arithmetic is memory-bound anyway,
 * so the extra cores are not bringing much bandwidth either.
 *
 * MEASURED, on the Snapdragon 8 Gen 1 above with DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M: token
 * generation was 17.4 tok/s on 4 threads and 14.1 tok/s on 6. Prism asked for 6 -- `cores * 0.8`,
 * which on an 8-core phone lands squarely in the little cluster. (That measurement was taken on a
 * phone that was also playing a video, which biases against the higher thread counts; the ordering
 * matches what the barrier argument predicts regardless, and the barrier argument is why this is
 * keyed on the hardware rather than on that number.)
 */
object PrismCpu {

    /** Everything the OS will schedule on, little cores included. */
    fun coreCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * How many cores run at or near the fastest clock on this machine.
     *
     * The threshold is 70% of peak, which on every big.LITTLE phone in circulation falls in the gap
     * between the clusters rather than inside one: the mid cores sit around 0.8 of peak and the
     * little cores around 0.6. A uniform machine -- any desktop, and a phone with one cluster --
     * reports every core here, which is the right answer for it.
     */
    val performanceCores: Int by lazy { detectPerformanceCores() }

    private fun detectPerformanceCores(): Int {
        val cores = coreCount()
        val frequencies = (0 until cores).mapNotNull { cpu ->
            // cpuinfo_max_freq is the hardware ceiling. scaling_max_freq would be wrong: it moves
            // with thermal and governor policy, so a throttled phone would look like it had fewer
            // fast cores and the pool would shrink and never grow back.
            val file = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            runCatching { file.takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
        }

        // Not readable here: desktops mostly, and any Android that restricts sysfs. Every core is
        // then assumed equal, which for a uniform machine is simply true.
        if (frequencies.size < cores) return cores

        val peak = frequencies.max()
        if (peak <= 0) return cores
        val fast = frequencies.count { it * 100 >= peak * 70 }
        return fast.coerceIn(1, cores)
    }

    /**
     * Threads to use for a barrier-synchronised, memory-bound workload: inference, or a training
     * step.
     *
     * The performance cores, less nothing -- taking one away to "leave room for the UI" is the trade
     * this used to make and it cost more than it bought, because the work is bounded by memory
     * bandwidth long before it saturates the cores it is given. Callers that must stay responsive
     * should run off the main thread, which they already do, rather than run slower.
     */
    fun inferenceThreads(): Int = performanceCores

    /** A one-line description, for logs and the diagnostics page. */
    fun describe(): String = "${coreCount()} cores, $performanceCores at or near peak clock"
}
