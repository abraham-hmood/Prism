package com.prism.launcher.aether

import com.prism.launcher.messaging.PrismSwap
import com.prism.launcher.platform.FloatStore

/**
 * Aether's view of Prism Swap.
 *
 * Same relationship to [PrismSwap] as `NoraSwap`: one shared mapping, one arena per consumer, a
 * private bump cursor so Aether's reset cannot disturb Nora's tensors, Sam's weights or a
 * VideoCraft frame. The file (`prism/swap.bin`) and the switch are Prism Swap's now; this object is
 * the name Aether's code reads it by.
 *
 * NOT YET WIRED INTO ALL OF AETHER'S ALLOCATION SITES -- see the note this object used to carry:
 * `AetherConnectome`'s layers and `Matrix`/`SpatialFrame` still allocate plain `FloatArray`s, and
 * routing them through here means auditing every `.indices`/`arraycopy`/`.fill` call site first.
 * [AetherKnowledgeSync] does use it for connectome transfer staging, so the path is exercised.
 */
object AetherSwap {

    const val MIN_BYTES = PrismSwap.MIN_BYTES

    private val arena get() = PrismSwap.arena(PrismSwap.Slice.Aether)

    fun capacityBytes(): Long = arena.capacityBytes()
    fun usedBytes(): Long = arena.usedBytes()
    fun isOpen(): Boolean = PrismSwap.isOpen()
    fun swapFile() = PrismSwap.swapFile()
    fun freeStorageBytes(): Long = PrismSwap.freeStorageBytes()
    fun open(bytes: Long): Boolean = PrismSwap.open(bytes)
    fun allocate(elements: Int): FloatStore? = arena.allocate(elements)

    /** Swap first if enabled, then off-heap RAM, then the managed heap. */
    fun allocateBest(elements: Int): FloatStore {
        if (AetherPerformance.swapEnabled) {
            if (!PrismSwap.isOpen()) PrismSwap.open()
            arena.allocate(elements)?.let { return it }
        }
        if (AetherPerformance.offHeapEnabled) {
            FloatStore.direct(elements)?.let { return it }
        }
        return FloatStore.heap(elements)
    }

    /** Drops Aether's allocations; the shared mapping stays open for everyone else. */
    fun reset() = arena.reset()

    fun close() = PrismSwap.close()
    fun delete() = PrismSwap.delete()
}
