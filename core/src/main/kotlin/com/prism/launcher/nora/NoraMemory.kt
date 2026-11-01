package com.prism.launcher.nora

import com.prism.launcher.messaging.PrismSwap
import com.prism.launcher.platform.FloatStore

/**
 * Nora's view of Prism Swap.
 *
 * Nora used to own a file (`nora/swap.bin`) and a capacity of its own. That file and the ones Sam,
 * Aether and VideoCraft owned are now one mapping -- see [PrismSwap] -- carved into arenas, one per
 * consumer, each with its own bump cursor. That cursor is why [reset] is still per-feature: a brain
 * rebuild throws away Nora's regions and nothing else's.
 *
 * The public shape is unchanged, so every call site in Nora still compiles and still reads as
 * "Nora's memory", which is what it is -- a slice of the shared file.
 */
object NoraSwap {

    /** Below this a swap file cannot hold anything worth spilling. Floor of the settings slider. */
    const val MIN_BYTES = PrismSwap.MIN_BYTES

    private val arena get() = PrismSwap.arena(PrismSwap.Slice.Nora)

    fun capacityBytes(): Long = arena.capacityBytes()
    fun usedBytes(): Long = arena.usedBytes()
    fun isOpen(): Boolean = PrismSwap.isOpen()
    fun swapFile() = PrismSwap.swapFile()
    fun freeStorageBytes(): Long = PrismSwap.freeStorageBytes()
    fun open(bytes: Long): Boolean = PrismSwap.open(bytes)
    fun allocate(elements: Int): FloatStore? = arena.allocate(elements)

    /**
     * Chooses a backing for one structure, honouring the user's switches in priority order:
     * swap first if enabled, then off-heap RAM, then the managed heap.
     */
    fun allocateBest(elements: Int): FloatStore {
        if (NoraPerformance.swapEnabled) {
            if (!PrismSwap.isOpen()) PrismSwap.open()
            arena.allocate(elements)?.let { return it }
        }
        if (NoraPerformance.offHeap) {
            FloatStore.direct(elements)?.let { return it }
        }
        return FloatStore.heap(elements)
    }

    /** Drops Nora's allocations; the shared mapping and every other arena stay open. */
    fun reset() = arena.reset()

    fun close() = PrismSwap.close()
    fun delete() = PrismSwap.delete()
}
