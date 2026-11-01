package com.prism.launcher.offload

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.PrismSwap

/**
 * A page that must not be offloaded while it is working.
 *
 * The launcher cannot tell, from the outside, whether a page is mid-render, mid-download or
 * playing something: a `View` tree looks identical either way. So the pages that do hold live work
 * say so, and everything else is assumed idle. That default is the right way round -- the pages
 * that would break are a short, knowable list (a video that must keep decoding, a render that must
 * not lose its sink), while the pages that hold only decoded images and adapter state are the
 * majority and are exactly what this feature is for.
 */
interface BusyPage {
    fun hasBackgroundWork(): Boolean
}

/**
 * Inactive pages, offloaded to Prism Swap.
 *
 * ## What is actually offloaded, and what is not
 *
 * Not the page's Java object -- a `View` is a live tree of contexts, adapters and listeners, and
 * there is no honest way to serialise one and get the same page back. What is offloaded is the
 * expensive part, the part that is worth the memory: the view hierarchy with everything it holds
 * (decoded images, adapter rows, map tiles). The page's pixels survive as a small thumbnail in
 * Prism Swap, and the page is rebuilt from its slot assignment when it comes back -- which is
 * exactly what happens on a cold start, so the rebuild path is one the launcher already trusts.
 *
 * ## Why the thumbnail is worth writing at all
 *
 * Because the alternative is a blank rectangle sliding in behind the finger. The thumbnail is a
 * quarter-scale ARGB copy, a few hundred kilobytes, and it is only read when the slot is rebuilt;
 * the swap arena is sized for a handful of them (see `PrismSwap.Slice.Pages`).
 *
 * ## It degrades, it never fails
 *
 * If Prism Swap is off, unmappable or full, [stashView] returns false and the caller keeps the
 * page attached. Nothing here can make a page fail to draw.
 */
object PageOffload {

    /** Quarter scale: a 1080x2340 page becomes 270x585, about 630 KB, and reads fine blurred. */
    private const val SCALE = 4

    private data class Thumb(val width: Int, val height: Int, val bytes: ByteArray)

    /**
     * The stash, in RAM, for the *current process*.
     *
     * Deliberately not read back out of the mapped file: the arena is a bump allocator with no
     * index, so recovering the latest payload for a slot would mean walking an untyped region.
     * The file is what makes the memory free (the bytes are not on the ART heap and are pageable
     * out by the kernel); this map is what makes them findable. Keeping a few hundred kilobytes
     * of index here is the cheaper of the two honest designs.
     */
    private val stash = HashMap<Int, Thumb>()

    fun isEnabled(): Boolean =
        PrismSettings.getPageOffloadEnabled() && PrismSettings.getPrismSwapEnabled()

    /**
     * Captures [view] into Prism Swap and returns true when the caller may then destroy it.
     *
     * Returns false -- leaving the page alone -- when the toggle is off, when the view has not
     * been laid out yet (a zero-sized bitmap is not a thumbnail, it is a bug), or when the arena
     * cannot give out the bytes.
     */
    fun stashView(slot: Int, view: View): Boolean {
        if (!isEnabled()) return false
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return false
        val tw = (w / SCALE).coerceAtLeast(1)
        val th = (h / SCALE).coerceAtLeast(1)
        val bytes = runCatching {
            val bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                scale(1f / SCALE, 1f / SCALE)
                view.draw(this)
            }
            val out = ByteArray(tw * th * 4)
            val buffer = java.nio.ByteBuffer.wrap(out)
            bitmap.copyPixelsToBuffer(buffer)
            bitmap.recycle()
            out
        }.getOrNull() ?: return false

        // WRITTEN INTO THE SWAP ARENA, not just kept as a byte array: those bytes are then outside
        // the ART heap, which is the whole point on a device where the heap cap, not the disk, is
        // what kills a launcher with many pages.
        if (!PrismSwap.isOpen()) PrismSwap.open()
        val arena = PrismSwap.arena(PrismSwap.Slice.Pages)
        var mapped = arena.allocateBytes(bytes.size.toLong())
        if (mapped == null) {
            // A BUMP ARENA NEVER FREES. Swiping back and forth would fill the Pages slice with
            // thumbnails for pages that have since been rebuilt, and the feature would quietly
            // stop working after a few dozen swipes. When it is exhausted, throw the slice away
            // and take it from the top; nothing in it is load-bearing.
            stash.clear()
            arena.reset()
            mapped = arena.allocateBytes(bytes.size.toLong())
        }
        if (mapped != null) {
            mapped.clear()
            mapped.put(bytes)
            stash[slot] = Thumb(tw, th, bytes)
            return true
        }
        // The arena is full (or swap closed mid-flight). Keeping the thumbnail on the heap would
        // spend the memory this feature exists to save, so the page simply stays attached.
        return false
    }

    /** The thumbnail for a slot, or null when it was never offloaded. */
    fun thumbnail(slot: Int): Bitmap? {
        val thumb = stash[slot] ?: return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
            java.nio.ByteBuffer.wrap(thumb.bytes).let { bitmap.copyPixelsFromBuffer(it) }
            bitmap
        }.getOrNull()
    }

    /** Forgets a slot: it is being rebuilt, so the thumbnail has done its job. */
    fun forget(slot: Int) {
        stash.remove(slot)
    }

    fun isOffloaded(slot: Int): Boolean = stash.containsKey(slot)

    /**
     * The page inside a windowed frame, or [view] itself when pages are not drawn as windows.
     *
     * The windowed layout wraps every page in a titled card, and that card is what sits in the pager's
     * container. [MainDesktopPagerAdapter] tags the card with the page it wraps, so a lookup can undo
     * the wrapping without knowing the card's internal structure.
     */
    fun unwrapWindowFrame(view: View): View {
        val tagged = view.getTag(com.prism.launcher.R.id.tag_prism_page_content)
        return tagged as? View ?: view
    }

    /** How much thumbnail data is live, for the Developer options row. */
    fun usedBytes(): Long = stash.values.sumOf { it.bytes.size.toLong() }

    /** Drops everything. Called when the toggle is switched off. */
    fun clear() {
        stash.clear()
        PrismSwap.arena(PrismSwap.Slice.Pages).reset()
    }
}
