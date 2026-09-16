package com.prism.launcher.platform

import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * An app-managed paging region for structures too large for the heap, backed by a memory-mapped
 * file. Extracted from Nora's original `NoraSwap` singleton so a second feature (Aether) can have
 * its own swap file and capacity without duplicating the mmap/bump-allocator logic -- see
 * [FloatStore]'s doc comment for why mmap over manual seek/read, and why bump-allocate-only (no
 * free, no compaction) is enough here: nothing allocates during training or generation, only at
 * brain-(re)build time, when [reset] throws the whole region away anyway.
 *
 * ONE INSTANCE PER FEATURE. Each caller (`NoraSwap`, `AetherSwap`) owns a private `SwapRegion`
 * with its own subdirectory/filename/log tag, and re-exposes the handful of methods it needs
 * under its own object -- so this class changes nothing about either's public API or on-disk file
 * path, it only stops the mmap/bump-pointer logic from being copy-pasted a second time.
 *
 * [swapEnabled]/[swapBytes] are lambdas, not stored booleans/longs, so a settings change is
 * picked up on the next [allocateBest] call without the region needing to be told about it --
 * mirrors the reason `AetherTuning`'s constants are read live rather than captured at
 * construction (see `config/constants.py`'s docstring on the Python side of that same problem).
 */
class SwapRegion(
    private val subDir: String,
    private val fileName: String,
    private val logTag: String,
    private val swapEnabled: () -> Boolean,
    private val swapBytes: () -> Long
) {
    companion object {
        /** Below this a swap file cannot hold anything worth spilling. Floor of the settings slider. */
        const val MIN_BYTES = 16L shl 20
    }

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
        File(File(PrismPlatform.host.dataDir(), subDir).apply { mkdirs() }, fileName)

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
            PrismPlatform.log.info(logTag, "Swap open: ${formatBytes(mapBytes)} at ${f.absolutePath}")
            true
        } catch (t: Throwable) {
            failed = true
            PrismPlatform.log.error(logTag, "Swap open failed: ${t.message}", t)
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
            PrismPlatform.log.warn(
                logTag,
                "Swap exhausted: wanted ${formatBytes(bytes)}, ${formatBytes(capacity - start)} left"
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
            PrismPlatform.log.error(logTag, "Swap allocation failed: ${t.message}", t)
            null
        }
    }

    /**
     * Carves a RAW byte region out of the mapped file -- the same bump-allocation bookkeeping as
     * [allocate], but returned as a plain direct [ByteBuffer] rather than wrapped in a
     * [FloatStore]. Added for [com.prism.launcher.messaging.PrismSwap] (Sam's GGUF model data),
     * which isn't float-shaped the way Nora's/Aether's tensors are -- quantized weight bytes, a
     * KV cache, and ggml compute buffers are not `FloatArray`s. A direct [ByteBuffer] backed by
     * this mapping is also what a native caller reads via JNI's `GetDirectBufferAddress`, so the
     * SAME mapped memory this class owns can be handed to native (llama.cpp/ggml) code without a
     * second mmap of the same file.
     */
    @Synchronized
    fun allocateBytes(byteCount: Long): ByteBuffer? {
        val mb = mapped ?: return null
        if (byteCount <= 0 || byteCount > Int.MAX_VALUE) return null
        val start = (used + 7L) and 7L.inv()
        if (start + byteCount > capacity) {
            PrismPlatform.log.warn(
                logTag,
                "Swap exhausted: wanted ${formatBytes(byteCount)}, ${formatBytes(capacity - start)} left"
            )
            return null
        }
        return try {
            val slice = mb.duplicate()
            slice.order(ByteOrder.nativeOrder())
            slice.position(start.toInt())
            slice.limit((start + byteCount).toInt())
            val view = slice.slice()
            used = start + byteCount
            view
        } catch (t: Throwable) {
            PrismPlatform.log.error(logTag, "Swap byte allocation failed: ${t.message}", t)
            null
        }
    }

    /**
     * Chooses a backing for one structure: swap first if enabled and it fits, then off-heap RAM
     * if [offHeapEnabled] says so, then the managed heap.
     */
    fun allocateBest(elements: Int, offHeapEnabled: () -> Boolean): FloatStore {
        if (swapEnabled()) {
            if (mapped == null && !failed) open(swapBytes())
            allocate(elements)?.let { return it }
        }
        if (offHeapEnabled()) {
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
        if (!swapEnabled()) {
            close()
            return
        }
        close()
        open(swapBytes())
    }

    private fun formatBytes(v: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = v.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${v} ${units[0]}" else "%.2f %s".format(value, units[unit])
    }
}
