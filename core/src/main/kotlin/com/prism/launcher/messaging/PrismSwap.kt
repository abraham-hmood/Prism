package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings
import com.prism.launcher.platform.SwapRegion
import java.nio.ByteBuffer

/**
 * Prism Swap: ONE file, one switch, one size, shared by everything that needs memory larger than
 * the heap.
 *
 * ## What changed and why
 *
 * Sam, Nora, Aether and VideoCraft each used to own a private [SwapRegion] -- four files
 * (`sam/swap.bin`, `nora/swap.bin`, `aether/swap.bin`, `videocraft/frames.bin`), four capacities,
 * and (for Sam and VideoCraft) two different on/off switches that a user could set
 * inconsistently. The page offloader now needs swap too, and a fifth file would have made that
 * worse: five sparse files to explain, five ways for one feature to run out while another sits
 * empty.
 *
 * So there is one mapping, [SWAP_FILE], carved into named [SwapRegion.Arena] slices. Each slice
 * keeps its own bump cursor, which is the property that made separate files safe in the first
 * place: a Nora brain rebuild resets *Nora's* cursor and cannot hand Sam's mapped weights back to
 * the allocator.
 *
 * ## The shares, and the trade-off they encode
 *
 * ```
 *   sam         0.00 .. 0.80   model weights, KV cache, ggml buffers (the big one)
 *   nora        0.80 .. 0.92   brain tensors
 *   aether      0.92 .. 0.94   staging buffers
 *   videocraft  0.94 .. 0.97   frame scratch
 *   pages       0.97 .. 1.00   inactive-page offload
 * ```
 *
 * SAM TAKES FOUR FIFTHS BECAUSE IT IS THE ONLY CONSUMER THAT ASKS FOR GIGABYTES. Everything else
 * allocates tens of megabytes, and everything else has a RAM fallback it already exercised before
 * swap existed. The cost is honest: a model that would have fitted in a 2 GB single-purpose region
 * now needs 2.5 GB of configured swap to get the same 2 GB, because a quarter of the file belongs
 * to features that are not running. That is the price of one file, and it is why the default
 * capacity went up rather than the shares going down.
 *
 * Every allocation may return null (swap off, file unmappable, slice full). Callers fall back to
 * RAM; nothing about correctness depends on swap being available.
 */
object PrismSwap {

    private const val DIR = "prism"
    private const val FILE = "swap.bin"
    private const val LOG_TAG = "Prism/swap"

    const val MIN_BYTES = SwapRegion.MIN_BYTES

    /** Named consumers of the one mapping. Fractions must not overlap or exceed 1.0. */
    enum class Slice(val start: Double, val end: Double) {
        Sam(0.00, 0.80),
        Nora(0.80, 0.92),
        Aether(0.92, 0.94),
        VideoCraft(0.94, 0.97),
        Pages(0.97, 1.00),
    }

    private val region = SwapRegion(
        subDir = DIR,
        fileName = FILE,
        logTag = LOG_TAG,
        swapEnabled = { PrismSettings.getPrismSwapEnabled() },
        swapBytes = { PrismSettings.getPrismSwapBytes() },
    )

    /**
     * The arena for [slice].
     *
     * Returns an arena even when swap is closed: the arena resolves its offsets against the
     * mapping at allocation time, so a consumer can hold one for the life of the process and
     * simply gets null back until the region is open.
     */
    fun arena(slice: Slice): SwapRegion.Arena =
        region.arena(slice.name, slice.start, slice.end)

    fun capacityBytes(): Long = region.capacityBytes()
    fun usedBytes(): Long = region.usedBytes()
    fun isOpen(): Boolean = region.isOpen()
    fun swapFile() = region.swapFile()
    fun freeStorageBytes(): Long = region.freeStorageBytes()

    /** Opens (or reopens at a new size) the swap file. */
    fun open(bytes: Long = PrismSettings.getPrismSwapBytes()): Boolean = region.open(bytes)

    /** Raw byte region for model weights, KV cache and native buffers (Sam). */
    fun allocateBytes(byteCount: Long): ByteBuffer? {
        if (!region.isOpen()) open()
        return arena(Slice.Sam).allocateBytes(byteCount)
    }

    fun close() = region.close()
    fun delete() = region.delete()

    /** Reopens the whole file empty. Only for a point where nothing holds an allocation. */
    fun reset() = region.reset()
}
