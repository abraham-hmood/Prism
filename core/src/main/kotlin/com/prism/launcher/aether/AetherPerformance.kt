package com.prism.launcher.aether

import com.prism.core.PrismPlatform

/**
 * Aether's performance switches: where her large structures live, not what she computes.
 *
 * SEPARATE FROM [AetherTuning] ON PURPOSE, same reasoning as `NoraPerformance` vs `NoraTuning`:
 * everything in AetherTuning changes what Aether computes (a different learning rate produces a
 * different connectome); everything here changes only HOW the same computation is carried out.
 * Scoped down from Nora's equivalent -- no episode store or sparse hub exist in Aether's
 * architecture, so this only covers the two placement switches [offHeapEnabled]/[swapEnabled]
 * that [AetherSwap] and (once callers are routed through it, see [AetherSwap]'s doc comment)
 * off-heap tensor allocation actually consult.
 */
object AetherPerformance {

    /** Put large structures in direct byte buffers -- off the managed heap, still in RAM. */
    var offHeapEnabled = false

    /** Put large structures in a memory-mapped file. Overrides [offHeapEnabled] when both are on. */
    var swapEnabled = false

    /** Bytes the swap file may occupy. Set by a settings slider, bounded by free storage. */
    var swapBytes = 256L shl 20

    class Flag(
        val key: String,
        val label: String,
        val detail: String,
        val read: () -> Boolean,
        val write: (Boolean) -> Unit
    )

    val FLAGS: List<Flag> = listOf(
        Flag(
            "off_heap", "Off-heap storage",
            "Puts large structures in direct buffers: outside the ART heap cap and invisible " +
                "to the garbage collector, still entirely in RAM.",
            { offHeapEnabled }, { offHeapEnabled = it }
        ),
        Flag(
            "swap", "Swap file",
            "Memory-maps a file for the largest structures. Hot pages stay in RAM at nearly " +
                "full speed; cold ones cost a flash read. The way past the heap ceiling.",
            { swapEnabled }, { swapEnabled = it }
        )
    )

    private const val PREFS = "aether_performance"
    private const val KEY_SWAP_BYTES = "swap_bytes"

    fun load() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (flag in FLAGS) if (p.contains(flag.key)) flag.write(p.getBoolean(flag.key, flag.read()))
        swapBytes = p.getLong(KEY_SWAP_BYTES, swapBytes).coerceAtLeast(AetherSwap.MIN_BYTES)
    }

    fun save() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (flag in FLAGS) p.putBoolean(flag.key, flag.read())
        p.putLong(KEY_SWAP_BYTES, swapBytes)
        p.flush()
    }

    fun resetToDefaults() {
        PrismPlatform.host.prefs(PREFS).clear()
        for ((i, flag) in FLAGS.withIndex()) flag.write(FLAG_DEFAULTS[i])
        swapBytes = DEFAULT_SWAP_BYTES
    }

    fun changedCount(): Int {
        var n = 0
        for ((i, flag) in FLAGS.withIndex()) if (flag.read() != FLAG_DEFAULTS[i]) n++
        if (swapBytes != DEFAULT_SWAP_BYTES) n++
        return n
    }

    /** One line describing where things ended up. */
    fun describe(): String = buildString {
        append("placement: ")
        append(
            when {
                swapEnabled && AetherSwap.isOpen() -> "swap ${AetherGeometry.formatBytes(AetherSwap.capacityBytes())}"
                offHeapEnabled -> "off-heap RAM"
                else -> "heap"
            }
        )
    }

    private val FLAG_DEFAULTS: BooleanArray = BooleanArray(FLAGS.size) { FLAGS[it].read() }
    private val DEFAULT_SWAP_BYTES: Long = swapBytes
}
