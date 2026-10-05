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

    /**
     * Whether this machine's core layout could be read at all.
     *
     * False on desktops -- there is no `/sys/devices/system/cpu` on Windows or macOS, and it does not
     * describe SMT siblings on Linux either. That distinction matters: when the layout IS readable,
     * [performanceCores] is a measurement, and when it is not, it is `availableProcessors()` with no idea
     * whether half of those are hyperthreads.
     */
    val topologyKnown: Boolean by lazy {
        val cores = coreCount()
        (0 until cores).count { cpu ->
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").canRead()
        } == cores
    }

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
    fun inferenceThreads(): Int {
        threadOverride?.let { return it.coerceIn(1, coreCount()) }
        // A machine whose layout could be read has already been measured; one that could not has to be
        // assumed to be counting SMT siblings, and using those is not neutral -- see the note below.
        return if (topologyKnown) performanceCores else (coreCount() / 2).coerceAtLeast(1)
    }

    /**
     * MEASURED ON THE DESKTOP, and the reason the unreadable case halves the count.
     *
     * SmolLM2-135M-Instruct Q4_K_M on a Ryzen 5 2600 (6 cores, 12 logical), same model, same machine,
     * token generation only:
     *
     * | threads | tok/s |
     * |---|---|
     * | 1 | 52 |
     * | 2 | 60 |
     * | 4 | 55-70 |
     * | 5 | 40 |
     * | 6 | 28 |
     * | 12 | 0.10 |
     *
     * TWELVE THREADS IS FIVE HUNDRED TIMES SLOWER THAN FOUR. That is not a gentle diminishing return, it
     * is a collapse, and it is the same barrier argument as the big.LITTLE case in a worse form: ggml
     * synchronises every thread at every graph node, an SMT sibling has no execution units of its own, and
     * a 135M model's graph nodes are small enough that the synchronisation cost dominates the arithmetic.
     * Decode is memory-bandwidth-bound as well, so the extra threads are not buying throughput they could
     * trade against that cost.
     *
     * The peak here is BELOW the physical core count, which halving does not reach -- hence
     * [threadOverride] and the `inference_threads` setting. Halving is what can be justified on any
     * machine; four is what this machine measured.
     *
     * (An OpenMP build of ggml is the other candidate fix and is untested here. The host CMakeLists turns
     * OpenMP off to match Android; whether its runtime handles the oversubscription better on Windows is
     * worth measuring, but a thread count that is right does not need it.)
     */
    /**
     * Forces a thread count, ignoring the detection above.
     *
     * Exists because the detection cannot see everything that matters. On Windows there is no sysfs to
     * read, so every logical processor looks like a performance core -- including the SMT siblings, which
     * share execution units and therefore make a barrier-synchronised graph wait on a thread that has half
     * a core. Measuring is the only way to know where the best number is on a given machine, so there has
     * to be a way to set it.
     */
    @Volatile
    var threadOverride: Int? = null

    /** Applies the user's `inference_threads` setting, if they have set one. Called at startup. */
    fun applySettings(configured: Int) {
        threadOverride = configured.takeIf { it > 0 }
    }

    /** A one-line description, for logs and the diagnostics page. */
    fun describe(): String = "${coreCount()} cores, $performanceCores at or near peak clock"
}
