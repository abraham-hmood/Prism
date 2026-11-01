package com.prism.launcher.videocraft

import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.PrismSwap
import java.nio.ByteBuffer

/**
 * VideoCraft's slice of Prism's swap system, used for frame-sized scratch buffers.
 *
 * ## What it is for
 *
 * A 4K frame is 12.4 MB of NV12 and an encoder needs one on every frame. Allocating that on the
 * JVM heap 24 times a second is what turns a render into a garbage-collection slideshow, and on a
 * phone it is also the allocation most likely to fail outright: the heap is the scarce resource,
 * not the disk. This region hands out those buffers from a memory-mapped file instead, so the
 * pixel scratch lives outside the heap and is reused for the whole render.
 *
 * ## Why it is an arena rather than a file
 *
 * This used to be `videocraft/frames.bin`, its own mapping, because a render sharing a region with
 * llama.cpp's weights looked like a way to exhaust a model's space. With one Prism Swap mapping the
 * separation is a slice instead: `PrismSwap.Slice.VideoCraft` is bumped only by renders, and
 * [begin] resets that cursor without moving Sam's, Nora's or the page offloader's. The size cap
 * below still applies -- a render cannot grow into the rest of the file.
 *
 * ## It degrades, it never fails
 *
 * Every method may return null: swap is disabled, the file cannot be mapped, the request is larger
 * than the region, or the region is full. A sink that gets null allocates an ordinary buffer and
 * carries on. The render's correctness never depends on the swap being available; only its memory
 * profile does.
 */
object VideoCraftSwap {

    /**
     * The most this feature will map, regardless of the user's Prism Swap size.
     *
     * A model swap may reasonably be several gigabytes because weights are that large. Video
     * scratch is bounded by one frame per open sink plus a little headroom, so mapping the whole
     * user setting would reserve address space the render cannot use. The file stays sparse either
     * way, but a smaller cap also means a smaller chance of colliding with a nearly-full disk.
     */
    private const val MAX_BYTES = 512L shl 20

    private val arena get() = PrismSwap.arena(PrismSwap.Slice.VideoCraft)

    fun isEnabled(): Boolean = PrismSettings.getPrismSwapEnabled()

    /**
     * Starts a render's arena.
     *
     * Reopening resets the bump pointer. [SwapRegion] never frees individual allocations, so a
     * second render would otherwise start where the first stopped and eventually run out despite
     * every buffer from the first being dead.
     */
    fun begin() {
        if (!isEnabled()) return
        // Reopening keeps a capacity change (the setting moved) honest; the arena resolves its
        // offsets against whatever mapping is current, so this is safe to call every render.
        runCatching { PrismSwap.open(PrismSettings.getPrismSwapBytes()) }
        arena.reset()
    }

    /** One buffer of [bytes], or null when the caller should use the heap instead. */
    fun scratch(bytes: Long): ByteBuffer? {
        if (!isEnabled() || bytes <= 0L) return null
        if (!PrismSwap.isOpen()) begin()
        return runCatching { arena.allocateBytes(bytes.coerceAtMost(MAX_BYTES)) }.getOrNull()
    }

    /**
     * Ends a render's scratch. The mapping stays open: it is shared, and closing it here would
     * unmap Sam's weights or Nora's tensors out from under whoever is still running.
     */
    fun end() {
        arena.reset()
    }

    /** Diagnostic line for the project card and the docs. */
    fun describe(): String = when {
        !isEnabled() -> "Prism Swap is off — frame scratch uses the heap"
        arena.capacityBytes() > 0 ->
            "Frame scratch on Prism Swap: ${arena.usedBytes() / 1048576} MB used of " +
                "${arena.capacityBytes() / 1048576} MB"
        else -> "Frame scratch on Prism Swap (not mapped yet)"
    }
}
