package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings
import com.prism.launcher.platform.SwapRegion
import java.nio.ByteBuffer

/**
 * Sam's own disk-backed swap -- own [SwapRegion] instance (own file, own capacity), same shape as
 * `NoraSwap` (`core/.../nora/NoraMemory.kt`), which this mirrors. Kept as a SEPARATE region rather
 * than sharing Nora's: the data is structurally unrelated (a GGUF model's raw bytes vs. Nora's
 * float tensors), only the underlying mmap/bump-allocator machinery is shared, via [SwapRegion]
 * itself. Both are user-facing as "Prism Swap" -- see `PrismSettings.kt`'s `KEY_PRISM_SWAP_*`.
 *
 * BYTE-LEVEL, NOT FLOAT-LEVEL. Nora/Aether allocate [com.prism.launcher.platform.FloatStore]s
 * (their tensors are `FloatArray`-shaped); a GGUF model's weight bytes, KV cache, and ggml compute
 * buffers are not. [SwapRegion.allocateBytes] (added alongside this) hands back a plain direct
 * [ByteBuffer] instead, which is also what a native (JNI) caller reads via
 * `GetDirectBufferAddress` -- the SAME mapped memory this object owns, no second mmap of the file
 * from native code.
 */
object PrismSwap {

    private const val DIR = "sam"
    private const val FILE = "swap.bin"
    private const val LOG_TAG = "Sam/swap"

    const val MIN_BYTES = SwapRegion.MIN_BYTES

    private val region = SwapRegion(
        subDir = DIR,
        fileName = FILE,
        logTag = LOG_TAG,
        swapEnabled = { PrismSettings.getPrismSwapEnabled() },
        swapBytes = { PrismSettings.getPrismSwapBytes() }
    )

    fun capacityBytes(): Long = region.capacityBytes()
    fun usedBytes(): Long = region.usedBytes()
    fun isOpen(): Boolean = region.isOpen()
    fun swapFile() = region.swapFile()
    fun freeStorageBytes(): Long = region.freeStorageBytes()

    /** Opens (or reopens at a new size) the swap file -- called explicitly rather than lazily on
     * first allocation, since native code needs a ready mapping *before* model load begins. */
    fun open(bytes: Long = PrismSettings.getPrismSwapBytes()): Boolean = region.open(bytes)

    /** Raw byte region for one buffer (repacked weight data under Tier 1, or any ggml buffer
     * under Tier 2 -- see `gguf_bridge.cpp`). Opens on first use if not already open. */
    fun allocateBytes(byteCount: Long): ByteBuffer? {
        if (!region.isOpen()) open()
        return region.allocateBytes(byteCount)
    }

    fun close() = region.close()
    fun delete() = region.delete()
    fun reset() = region.reset()
}
