package com.prism.launcher.nora

import com.prism.core.PrismPlatform

/**
 * The performance switches: where Nora's memory lives and what runs her arithmetic.
 *
 * SEPARATE FROM [NoraTuning] ON PURPOSE. Everything in NoraTuning changes what Nora computes --
 * a different learning rate produces a different connectome. Everything here changes only HOW
 * the same computation is carried out. That distinction is worth keeping visible, because it
 * determines what a change can be blamed for: a bad image after editing a tuning parameter is
 * expected, and a bad image after enabling a storage backend is a bug in this file.
 *
 * EVERY FEATURE IS OFF BY DEFAULT. Each one trades something real:
 *
 *   Episode spill    trades replay latency for ~19% of the memory budget.
 *   Sparse hub       trades a little bookkeeping for most of the hub's cost. Nearly free.
 *   Off-heap RAM     trades slightly slower element access for freedom from the ART heap cap.
 *   Swap file        trades tail latency -- badly, when a page misses -- for capacity beyond RAM.
 *   Native conv      trades a JNI boundary and a second implementation for SIMD throughput.
 *
 * None of them changes a single arithmetic result, and that is a requirement rather than an
 * aspiration: a sparse row and an all-zero dense row contribute identically, a float read from
 * a mapped page is the same float, and the native kernel accumulates in the same order as the
 * Kotlin one. Anything that would alter results belongs in NoraTuning, where it can be reasoned
 * about as a modelling decision.
 */
object NoraPerformance {

    // ── Feature switches ────────────────────────────────────────────────────

    /**
     * Keep episode payloads in a record file instead of on the heap.
     *
     * The episode store is ~19% of the memory budget at a mid-sized geometry and is the single
     * best out-of-core candidate in the system, because of a clean split in its access pattern:
     * the metadata that gets scanned constantly (caption, reward -- read by every eviction,
     * every retag and every replay draw) is tiny, and the payloads that are enormous are read
     * only for the handful of episodes a replay batch actually samples. Metadata stays resident;
     * payloads go to disk and are faulted back one episode at a time.
     */
    var episodeSpill = false

    /**
     * Allocate semantic hub rows only when something is written to them.
     *
     * The hub is the largest structure in the model -- over half the memory budget -- and most
     * of it is exactly zero. A row of `toVision` is only ever written when its IT unit was
     * active during a bind, and IT is k-winners-take-all at ~6%, so the great majority of rows
     * are never touched at all. An untouched row contributes precisely nothing to the output,
     * so skipping it is not an approximation: it is the same sum with the zero terms omitted.
     * That makes this the rare option that is faster AND smaller AND exact.
     */
    var hubSparse = false

    /** Put large structures in direct byte buffers -- off the managed heap, still in RAM. */
    var offHeap = false

    /** Put large structures in a memory-mapped file. Overrides [offHeap] when both are on. */
    var swapEnabled = false

    /** Use the NEON/SIMD kernels for the 5x5 predictive-coding convolutions. */
    var nativeConv = false

    // ── Parameters ──────────────────────────────────────────────────────────

    /** How many episode payloads stay cached in RAM when spilling. */
    var episodeCacheSize = 32

    /** Episodes held below this count are kept in RAM regardless -- spilling them saves nothing. */
    var episodeSpillFloor = 64

    /**
     * Hub rows whose peak magnitude falls below this are released at sleep.
     *
     * Complements the switch: sparsity only helps if rows can go back to being absent. A row
     * that was written once, weakly, and then decayed toward zero costs a full row of memory
     * forever otherwise. 0 disables release, which keeps behaviour identical to dense storage.
     */
    var hubRowRelease = 0.0f

    /** Below this many rows, gather serially -- the pool handoff costs more than it saves. */
    var hubParallelFloor = 512

    /** Bytes the swap file may occupy. Set by the slider, bounded by free storage. */
    var swapBytes = 256L shl 20

    /**
     * Structures smaller than this stay in RAM even when swap is on.
     *
     * Paging is only worth its latency for something large. A small, hot array behind a page
     * fault is strictly worse than the same array on the heap, so there is a floor.
     */
    var swapMinRegionKb = 1024

    /** Below this many multiply-accumulates, stay in Kotlin: the JNI transition dominates. */
    var nativeMinWork = 65536

    /** Worker threads for [Par]. 0 means one per core. */
    var workerThreads = 0

    // ── Descriptors ─────────────────────────────────────────────────────────

    class Flag(
        val key: String,
        val label: String,
        val detail: String,
        val read: () -> Boolean,
        val write: (Boolean) -> Unit
    )

    const val GROUP_EPISODE = "Episode store"
    const val GROUP_HUB = "Semantic hub"
    const val GROUP_MEMORY = "Memory placement"
    const val GROUP_NATIVE = "Native acceleration"
    const val GROUP_THREADS = "Threading"

    val FLAGS: List<Flag> = listOf(
        Flag(
            "episode_spill", "Spill episode store to disk",
            "Keeps episode payloads in a record file and faults them back for replay. Frees " +
                "roughly a fifth of the memory budget; costs a file read per replayed episode.",
            { episodeSpill }, { episodeSpill = it }
        ),
        Flag(
            "hub_sparse", "Sparse semantic hub",
            "Allocates hub rows only when written. Most rows are never touched, and an " +
                "untouched row contributes nothing, so this is exact — smaller and faster both.",
            { hubSparse }, { hubSparse = it }
        ),
        Flag(
            "off_heap", "Off-heap storage",
            "Puts large structures in direct buffers: outside the ART heap cap and invisible " +
                "to the garbage collector, still entirely in RAM.",
            { offHeap }, { offHeap = it }
        ),
        Flag(
            "swap", "Swap file",
            "Memory-maps a file for the largest structures. Hot pages stay in RAM at nearly " +
                "full speed; cold ones cost a flash read. The way past the heap ceiling.",
            { swapEnabled }, { swapEnabled = it }
        ),
        Flag(
            "native_conv", "Native convolution",
            "Runs the 5×5 predictive-coding kernels through NEON SIMD instead of JVM float " +
                "loops. Falls back to Kotlin automatically if the library will not load.",
            { nativeConv }, { nativeConv = it }
        )
    )

    val PARAMS: List<NoraTuning.Param> = listOf(
        NoraTuning.Param(
            "ep_cache", GROUP_EPISODE, "Resident episode cache",
            "Episode payloads kept in RAM. A replay batch that fits here costs no file reads.",
            1f, 512f, true, { episodeCacheSize.toFloat() }, { episodeCacheSize = it.toInt() },
            enabled = { episodeSpill }
        ),
        NoraTuning.Param(
            "ep_floor", GROUP_EPISODE, "Spill floor",
            "Below this many stored episodes nothing is spilled — the saving would not pay for " +
                "the indirection.",
            0f, 4096f, true, { episodeSpillFloor.toFloat() }, { episodeSpillFloor = it.toInt() },
            enabled = { episodeSpill }
        ),

        NoraTuning.Param(
            "hub_release", GROUP_HUB, "Row release threshold",
            "Rows whose peak weight falls below this are freed during sleep. 0 keeps every row " +
                "once written, which matches dense storage exactly.",
            0f, 0.01f, false, { hubRowRelease }, { hubRowRelease = it },
            enabled = { hubSparse }
        ),
        NoraTuning.Param(
            "swap_min_kb", GROUP_MEMORY, "Minimum swapped region (KB)",
            "Structures smaller than this stay in RAM even with swap on. A small hot array " +
                "behind a page fault is worse than the same array on the heap.",
            4f, 262144f, true, { swapMinRegionKb.toFloat() }, { swapMinRegionKb = it.toInt() },
            enabled = { swapEnabled }
        ),

        NoraTuning.Param(
            "native_min_work", GROUP_NATIVE, "Native work floor",
            "Multiply-accumulates below which the Kotlin path is used. The JNI boundary has a " +
                "fixed cost that small kernels cannot amortize.",
            0f, 4194304f, true, { nativeMinWork.toFloat() }, { nativeMinWork = it.toInt() },
            enabled = { nativeConv && NoraNative.available() }
        ),

        NoraTuning.Param(
            "worker_threads", GROUP_THREADS, "Worker threads",
            "Threads used for parallel loops. 0 means one per core. Lowering this leaves " +
                "headroom for the UI during a long run.",
            0f, 32f, true, { workerThreads.toFloat() }, { workerThreads = it.toInt() }
        ),
        // Ungated on purpose. It governs the hub gather in every storage mode, not only the
        // sparse one, so gating it behind the sparse switch would grey out a control that was
        // still taking effect -- the exact dishonesty the gating is supposed to prevent.
        NoraTuning.Param(
            "hub_par_floor", GROUP_THREADS, "Hub parallel gather floor",
            "Row count below which the semantic hub's gather runs on one thread. Handing small " +
                "work to the pool costs more than it saves.",
            1f, 65536f, true, { hubParallelFloor.toFloat() }, { hubParallelFloor = it.toInt() }
        )
    )

    val GROUPS: List<String> = PARAMS.map { it.group }.distinct()

    // ── Placement policy ────────────────────────────────────────────────────

    /**
     * Whether a structure of this many floats is large enough to be worth moving off the heap.
     *
     * One rule, consulted by every caller, so "large" means the same thing everywhere and is
     * answerable from the settings screen rather than from a literal buried in a constructor.
     */
    fun shouldOffload(elements: Int): Boolean {
        if (!swapEnabled && !offHeap) return false
        if (swapEnabled) return elements.toLong() * 4L >= swapMinRegionKb.toLong() * 1024L
        return true
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private const val PREFS = "nora_performance"
    private const val KEY_SWAP_BYTES = "swap_bytes"

    fun load() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (flag in FLAGS) if (p.contains(flag.key)) flag.write(p.getBoolean(flag.key, flag.read()))
        for (param in PARAMS) {
            if (!p.contains(param.key)) continue
            param.write(p.getFloat(param.key, param.read()).coerceIn(param.min, param.max))
        }
        swapBytes = p.getLong(KEY_SWAP_BYTES, swapBytes).coerceAtLeast(NoraSwap.MIN_BYTES)
        Par.configure(workerThreads)
    }

    fun save() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (flag in FLAGS) p.putBoolean(flag.key, flag.read())
        for (param in PARAMS) p.putFloat(param.key, param.read())
        p.putLong(KEY_SWAP_BYTES, swapBytes)
        p.flush()
        Par.configure(workerThreads)
    }

    fun resetToDefaults() {
        PrismPlatform.host.prefs(PREFS).clear()
        for ((i, flag) in FLAGS.withIndex()) flag.write(FLAG_DEFAULTS[i])
        for ((i, param) in PARAMS.withIndex()) param.write(PARAM_DEFAULTS[i])
        swapBytes = DEFAULT_SWAP_BYTES
        Par.configure(workerThreads)
        NoraLog.info(NoraLog.Area.MEMORY, "Performance settings reset to defaults")
    }

    fun changedCount(): Int {
        var n = 0
        for ((i, flag) in FLAGS.withIndex()) if (flag.read() != FLAG_DEFAULTS[i]) n++
        for ((i, param) in PARAMS.withIndex()) if (param.read() != PARAM_DEFAULTS[i]) n++
        if (swapBytes != DEFAULT_SWAP_BYTES) n++
        return n
    }

    /** One line describing where things ended up, for the head of a training or test run. */
    fun describe(): String = buildString {
        append("placement: ")
        append(
            when {
                swapEnabled && NoraSwap.isOpen() ->
                    "swap ${NoraGeometry.formatBytes(NoraSwap.capacityBytes())}"
                offHeap -> "off-heap RAM"
                else -> "heap"
            }
        )
        if (hubSparse) append(", sparse hub")
        if (episodeSpill) append(", spilled episodes")
        append(", conv ")
        append(if (nativeConv && NoraNative.available()) "native" else "kotlin")
        append(", ${Par.threadCount()} threads")
    }

    private val FLAG_DEFAULTS: BooleanArray = BooleanArray(FLAGS.size) { FLAGS[it].read() }
    private val PARAM_DEFAULTS: FloatArray = FloatArray(PARAMS.size) { PARAMS[it].read() }
    private val DEFAULT_SWAP_BYTES: Long = swapBytes
}
