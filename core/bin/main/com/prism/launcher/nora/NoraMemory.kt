package com.prism.launcher.nora

import com.prism.launcher.platform.FloatStore
import com.prism.launcher.platform.SwapRegion

/**
 * Nora's swap file: an app-managed paging region for structures too large for the heap.
 *
 * A thin, Nora-named wrapper around a single private [SwapRegion] -- the mmap/bump-allocator
 * logic itself lives there now (shared with Aether's own `AetherSwap`), extracted out of what
 * used to be this object's body. Public API and on-disk file path (`nora/swap.bin`) are
 * unchanged, so no call site elsewhere in Nora needed to change.
 */
object NoraSwap {

    private const val DIR = "nora"
    private const val FILE = "swap.bin"
    private const val LOG_TAG = "Nora/memory"

    /** Below this a swap file cannot hold anything worth spilling. Floor of the settings slider. */
    const val MIN_BYTES = SwapRegion.MIN_BYTES

    private val region = SwapRegion(
        subDir = DIR,
        fileName = FILE,
        logTag = LOG_TAG,
        swapEnabled = { NoraPerformance.swapEnabled },
        swapBytes = { NoraPerformance.swapBytes }
    )

    fun capacityBytes(): Long = region.capacityBytes()
    fun usedBytes(): Long = region.usedBytes()
    fun isOpen(): Boolean = region.isOpen()
    fun swapFile() = region.swapFile()
    fun freeStorageBytes(): Long = region.freeStorageBytes()
    fun open(bytes: Long): Boolean = region.open(bytes)
    fun allocate(elements: Int): FloatStore? = region.allocate(elements)

    /**
     * Chooses a backing for one structure, honouring the user's switches in priority order:
     * swap first if enabled and it fits, then off-heap RAM, then the managed heap.
     */
    fun allocateBest(elements: Int): FloatStore =
        region.allocateBest(elements) { NoraPerformance.offHeap }

    fun close() = region.close()
    fun delete() = region.delete()
    fun reset() = region.reset()
}
