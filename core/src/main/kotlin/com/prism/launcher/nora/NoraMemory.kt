package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Where Nora's large float arrays actually live.
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
 *   SWAP    a region of a memory-mapped file. Identical access path to DIRECT -- both end up as
 *           `FloatBuffer` absolute get/put -- but the pages are backed by storage rather than by
 *           anonymous memory. Hot pages sit in the kernel page cache at RAM speed; cold ones
 *           fault in from flash at roughly a thousand times the latency. That is the whole
 *           trade: capacity beyond physical RAM, paid for in tail latency.
 *
 * ABSOLUTE INDEXING IS LOAD-BEARING. Every access below uses `get(index)`/`put(index, v)`, never
 * the relative forms. The relative forms mutate the buffer's own position field, which makes
 * concurrent access from [Par]'s workers a data race even when the workers touch disjoint
 * indices. The absolute forms carry no shared mutable state, so a parallel loop over distinct
 * indices is safe -- which is the only reason a swap-backed structure can be read in parallel
 * at all.
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
                NoraLog.warn(NoraLog.Area.MEMORY, "Direct allocation of $size floats failed: ${t.message}")
                null
            }
        }

        fun mapped(size: Int, buffer: FloatBuffer) =
            FloatStore(size, Backing.SWAP, null, buffer)
    }
}

/**
 * The swap file: an app-managed paging region for structures too large for the heap.
 *
 * WHY A MAPPED FILE RATHER THAN seek() AND read(). Manual seeking means writing a cache, an
 * eviction policy and a write-back scheme by hand, and then being wrong about all three. The
 * kernel already has those, tuned, at 4 KB granularity, in the page cache. `mmap` opts into
 * them: a hot page is a plain memory read after its first touch, a cold one traps into the
 * kernel and comes back. No copy, no cache of our own, no write-back logic.
 *
 * THE PART THAT MAKES THIS WORTH DOING AT ALL. Mapped pages live in the page cache, which is
 * physical RAM that is *not* charged to the ART heap. On a device with memory to spare a mapped
 * structure stays resident and runs at very nearly heap speed; it degrades to flash latency only
 * when real memory pressure forces eviction. So this is simultaneously the way past the heap cap
 * and the way to fail gracefully instead of with an OutOfMemoryError.
 *
 * ALLOCATION IS A BUMP POINTER AND THERE IS NO FREE. Regions are handed out in order and
 * reclaimed only by [reset], which happens when the brain is rebuilt. Nothing here allocates
 * during training or generation, so a general allocator would be machinery for a problem that
 * does not occur.
 */
object NoraSwap {

    private const val DIR = "nora"
    private const val FILE = "swap.bin"

    /** Below this a swap file cannot hold anything worth spilling. Floor of the settings slider. */
    const val MIN_BYTES = 16L shl 20

    private var file: File? = null
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var mapped: ByteBuffer? = null

    private var capacity = 0L
    private var used = 0L
    private var failed = false

    fun capacityBytes(): Long = capacity
    fun usedBytes(): Long = used
    fun isOpen(): Boolean = mapped != null

    fun swapFile(): File =
        File(File(PrismPlatform.host.dataDir(), DIR).apply { mkdirs() }, FILE)

    /**
     * Free space on the volume holding the swap file. The ceiling of the settings slider.
     *
     * Reports what is free *now*, so the slider's top does not promise space that a photo library
     * has already taken.
     */
    fun freeStorageBytes(): Long =
        PrismPlatform.host.freeStorageBytes(PrismPlatform.host.dataDir())

    /**
     * Opens (creating and sizing if needed) the swap file and maps it.
     *
     * `setLength` on a fresh file produces a SPARSE file on ext4 and f2fs: the length is
     * reserved in the inode but blocks are not committed until a page is actually written. So
     * asking for a 4 GB swap does not immediately consume 4 GB, and a mostly-unused swap costs
     * almost nothing on disk. It also means an over-large setting fails late, on write, rather
     * than early -- which is why [allocate] checks remaining capacity itself.
     */
    @Synchronized
    fun open(bytes: Long): Boolean {
        if (mapped != null && capacity == bytes) return true
        close()
        failed = false
        val want = bytes.coerceAtLeast(MIN_BYTES)
        return try {
            val f = swapFile()
            val r = RandomAccessFile(f, "rw")
            r.setLength(want)
            val ch = r.channel
            // Mapped in one region. A FloatBuffer view is limited to 2 GB, so a larger request
            // is honoured on disk but only the first 2 GB is addressable; allocate() enforces
            // that rather than letting a region straddle the edge.
            val mapBytes = minOf(want, Int.MAX_VALUE.toLong())
            val mb = ch.map(FileChannel.MapMode.READ_WRITE, 0L, mapBytes)
            mb.order(ByteOrder.nativeOrder())
            file = f
            raf = r
            channel = ch
            mapped = mb
            capacity = mapBytes
            used = 0L
            NoraLog.info(
                NoraLog.Area.MEMORY,
                "Swap open: ${NoraGeometry.formatBytes(mapBytes)} at ${f.absolutePath}"
            )
            true
        } catch (t: Throwable) {
            failed = true
            NoraLog.error(NoraLog.Area.MEMORY, "Swap open failed: ${t.message}", t)
            close()
            false
        }
    }

    /**
     * Carves a region out of the mapped file.
     *
     * Returns null when swap is closed, exhausted, or the request is too large to address, and
     * every caller treats null as "use RAM instead". A swap file that fills up therefore
     * degrades to the previous behaviour rather than failing the run.
     */
    @Synchronized
    fun allocate(elements: Int): FloatStore? {
        val mb = mapped ?: return null
        if (elements <= 0 || elements > FloatStore.MAX_ELEMENTS) return null
        val bytes = elements.toLong() * 4L
        // Align to 8 so no float straddles the alignment the platform prefers for doubles.
        val start = (used + 7L) and 7L.inv()
        if (start + bytes > capacity) {
            NoraLog.warn(
                NoraLog.Area.MEMORY,
                "Swap exhausted: wanted ${NoraGeometry.formatBytes(bytes)}, " +
                    "${NoraGeometry.formatBytes(capacity - start)} left"
            )
            return null
        }
        return try {
            val slice = mb.duplicate()
            slice.order(ByteOrder.nativeOrder())
            slice.position(start.toInt())
            slice.limit((start + bytes).toInt())
            val view = slice.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
            used = start + bytes
            FloatStore.mapped(elements, view)
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.MEMORY, "Swap allocation failed: ${t.message}", t)
            null
        }
    }

    /**
     * Chooses a backing for one structure, honouring the user's switches in priority order:
     * swap first if enabled and it fits, then off-heap RAM, then the managed heap.
     */
    fun allocateBest(elements: Int): FloatStore {
        if (NoraPerformance.swapEnabled) {
            if (mapped == null && !failed) open(NoraPerformance.swapBytes)
            allocate(elements)?.let { return it }
        }
        if (NoraPerformance.offHeap) {
            FloatStore.direct(elements)?.let { return it }
        }
        return FloatStore.heap(elements)
    }

    /**
     * Releases the mapping.
     *
     * HONESTY FLAG: Java has no supported way to unmap a `MappedByteBuffer`. Dropping the
     * reference makes the mapping eligible for release only when the collector finally gets to
     * it, which may be much later. Closing the channel here releases the file descriptor and
     * the length, which is the part that matters for not leaking handles across a geometry
     * change; the address space is reclaimed when the process does.
     */
    @Synchronized
    fun close() {
        try {
            channel?.close()
            raf?.close()
        } catch (_: Throwable) {
        }
        mapped = null
        channel = null
        raf = null
        capacity = 0L
        used = 0L
    }

    /** Drops the mapping and deletes the file. Called when the user turns swap off. */
    @Synchronized
    fun delete() {
        close()
        try {
            swapFile().delete()
        } catch (_: Throwable) {
        }
    }

    /** Reopens empty, so a rebuilt brain does not inherit the old brain's regions. */
    @Synchronized
    fun reset() {
        if (!NoraPerformance.swapEnabled) {
            close()
            return
        }
        close()
        open(NoraPerformance.swapBytes)
    }
}
