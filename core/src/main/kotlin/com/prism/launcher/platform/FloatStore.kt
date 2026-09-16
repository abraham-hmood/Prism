package com.prism.launcher.platform

import com.prism.core.PrismPlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Where a feature's large float arrays actually live.
 *
 * THREE PLACES, AND THE DIFFERENCE BETWEEN THEM IS NOT ACADEMIC:
 *
 *   HEAP    a Kotlin `FloatArray`. Fastest possible access -- the JIT emits a bounds-checked
 *           load and nothing else -- but it counts against the ART heap cap, which on an 8 GB
 *           phone is a few hundred megabytes, and every full GC has to walk it.
 *
 *   DIRECT  a direct `ByteBuffer`, allocated by malloc rather than by the collector. NOT on the
 *           managed heap, so it neither counts against the cap nor gets scanned. Access is a
 *           JNI-free intrinsic on modern ART but still slower than array indexing, because the
 *           bounds check cannot be hoisted as effectively.
 *
 *   SWAP    a region of a memory-mapped file (see [SwapRegion]). Identical access path to DIRECT
 *           -- both end up as `FloatBuffer` absolute get/put -- but the pages are backed by
 *           storage rather than by anonymous memory. Hot pages sit in the kernel page cache at
 *           RAM speed; cold ones fault in from flash at roughly a thousand times the latency.
 *           That is the whole trade: capacity beyond physical RAM, paid for in tail latency.
 *
 * ABSOLUTE INDEXING IS LOAD-BEARING. Every access below uses `get(index)`/`put(index, v)`, never
 * the relative forms. The relative forms mutate the buffer's own position field, which makes
 * concurrent access from parallel workers a data race even when the workers touch disjoint
 * indices. The absolute forms carry no shared mutable state, so a parallel loop over distinct
 * indices is safe -- which is the only reason a swap-backed structure can be read in parallel
 * at all.
 *
 * SHARED ACROSS FEATURES. Originally `com.prism.launcher.nora.FloatStore`, extracted here (pure
 * move, no logic change) so Aether can allocate through the same off-heap/swap machinery without
 * duplicating it -- see [SwapRegion]'s doc comment for the per-feature instance it now pairs
 * with, and `NoraSwap`/`AetherSwap` for the two current owners.
 */
class FloatStore private constructor(
    val size: Int,
    val backing: Backing,
    private val heap: FloatArray?,
    private val buf: FloatBuffer?
) {

    enum class Backing { HEAP, DIRECT, SWAP }

    operator fun get(i: Int): Float = if (heap != null) heap[i] else buf!!.get(i)

    operator fun set(i: Int, v: Float) {
        if (heap != null) heap[i] = v else buf!!.put(i, v)
    }

    fun add(i: Int, delta: Float) {
        if (heap != null) heap[i] += delta else buf!!.put(i, buf.get(i) + delta)
    }

    fun zero() {
        if (heap != null) {
            java.util.Arrays.fill(heap, 0f)
        } else {
            for (i in 0 until size) buf!!.put(i, 0f)
        }
    }

    /** Bulk read of a contiguous span. Used by save paths, which are not latency-critical. */
    fun copyRange(from: Int, out: FloatArray) {
        if (heap != null) {
            System.arraycopy(heap, from, out, 0, out.size)
        } else {
            for (i in out.indices) out[i] = buf!!.get(from + i)
        }
    }

    fun writeRange(from: Int, src: FloatArray) {
        if (heap != null) {
            System.arraycopy(src, 0, heap, from, src.size)
        } else {
            for (i in src.indices) buf!!.put(from + i, src[i])
        }
    }

    companion object {

        /**
         * A `ByteBuffer` is indexed by `Int`, so one buffer tops out at 2 GB and one
         * `FloatBuffer` view of it at a quarter of that many elements. Requests above this are
         * refused rather than silently truncated.
         */
        const val MAX_ELEMENTS = Int.MAX_VALUE / 4

        fun heap(size: Int) = FloatStore(size, Backing.HEAP, FloatArray(size), null)

        /**
         * Off-heap in RAM. Returns null rather than throwing if the allocation fails, so every
         * caller has to state what it does without it -- which is always "fall back to heap".
         */
        fun direct(size: Int): FloatStore? {
            if (size <= 0 || size > MAX_ELEMENTS) return null
            return try {
                val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
                FloatStore(size, Backing.DIRECT, null, bb.asFloatBuffer())
            } catch (t: Throwable) {
                PrismPlatform.log.warn("Prism/memory", "Direct allocation of $size floats failed: ${t.message}")
                null
            }
        }

        fun mapped(size: Int, buffer: FloatBuffer) =
            FloatStore(size, Backing.SWAP, null, buffer)
    }
}
