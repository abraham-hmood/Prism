package com.prism.launcher.aether

import com.prism.launcher.platform.FloatStore
import com.prism.launcher.platform.SwapRegion

/**
 * Aether's swap file: an app-managed paging region for structures too large for the heap.
 *
 * Own file (`aether/swap.bin`), own capacity, own [SwapRegion] instance -- independent of Nora's
 * `NoraSwap`, which owns its own region under `nora/swap.bin`. Both wrap the same [SwapRegion]/
 * [FloatStore] machinery (see [SwapRegion]'s doc comment for why mmap, and why one region per
 * feature rather than one shared region), so this object is deliberately a near-duplicate of
 * `NoraSwap`'s shape, wired to [AetherPerformance] instead of `NoraPerformance` for its
 * enable/size switches.
 *
 * NOT YET WIRED INTO ALLOCATION CALL SITES. [AetherConnectome]'s layers (`AetherNeuron.kt`) and
 * [Matrix]/[SpatialFrame] (`AetherOps.kt`/`AetherTensor.kt`) currently allocate plain `FloatArray`s
 * directly, and `Matrix.data`/`SpatialFrame.data` are public `FloatArray`s read and written at
 * many call sites throughout the cortex/layer code as raw arrays (`.indices`, `System.arraycopy`,
 * `.fill`, direct `for (v in data)` iteration) -- not through a single accessor boundary the way
 * Nora's `HubMatrix` was deliberately designed from the start to allow swap-backing. Routing them
 * through this object safely means auditing and updating every one of those call sites (hundreds,
 * across ~1,100 lines of numerically load-bearing code) so none of them assume `FloatArray`-only
 * APIs on a type that might now be a [FloatStore]-backed view -- exactly the kind of large,
 * hard-to-verify-without-a-compiler change this pass's constraints (see the session's own
 * "don't build on faith" checkpoint) argue for doing as a separate, individually-verifiable step
 * rather than blind inside a larger push. This object exists and is ready (open/allocateBest/
 * reset all function today, exercised by [AetherKnowledgeSync] for connectome transfer staging
 * buffers), so that follow-up is "route callers through it," not "build it."
 */
object AetherSwap {

    private const val DIR = "aether"
    private const val FILE = "swap.bin"
    private const val LOG_TAG = "Aether/memory"

    const val MIN_BYTES = SwapRegion.MIN_BYTES

    private val region = SwapRegion(
        subDir = DIR,
        fileName = FILE,
        logTag = LOG_TAG,
        swapEnabled = { AetherPerformance.swapEnabled },
        swapBytes = { AetherPerformance.swapBytes }
    )

    fun capacityBytes(): Long = region.capacityBytes()
    fun usedBytes(): Long = region.usedBytes()
    fun isOpen(): Boolean = region.isOpen()
    fun swapFile() = region.swapFile()
    fun freeStorageBytes(): Long = region.freeStorageBytes()
    fun open(bytes: Long): Boolean = region.open(bytes)
    fun allocate(elements: Int): FloatStore? = region.allocate(elements)

    /** Swap first if enabled and it fits, then off-heap RAM, then the managed heap. */
    fun allocateBest(elements: Int): FloatStore =
        region.allocateBest(elements) { AetherPerformance.offHeapEnabled }

    fun close() = region.close()
    fun delete() = region.delete()
    fun reset() = region.reset()
}
