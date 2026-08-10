package com.prism.launcher.nora

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The episode store's payloads, held in a record file instead of on the heap.
 *
 * WHY THIS STRUCTURE AND NOT ANOTHER. The episode store is the best out-of-core candidate in
 * Nora, and not because it is the largest -- the semantic hub is larger -- but because its
 * access pattern splits so cleanly:
 *
 *   HOT AND TINY   caption and reward. Read by every eviction (which scans for the
 *                  least-rewarded episode), every retag, and every draw of a replay batch.
 *                  A few dozen bytes each.
 *
 *   COLD AND HUGE  the semantic cue and the IT pattern. Read only for the handful of episodes a
 *                  replay batch actually samples. Hundreds of kilobytes each at a mid-sized
 *                  geometry, and ~19% of the whole memory budget in aggregate.
 *
 * So the metadata stays resident and the payloads go to disk. Nothing that runs frequently
 * touches the file at all: eviction, retagging and reward-weighted sampling all complete without
 * a single read, because none of them looks at a payload. Only the episodes actually chosen for
 * replay are faulted back, one at a time.
 *
 * FIXED-SIZE RECORDS, so a slot index is an offset and nothing has to be searched for. Slots
 * freed by eviction go on a free list and are reused, which keeps the file bounded by the
 * episode capacity rather than growing with total episodes ever stored.
 *
 * Lengths are stored per record rather than assumed. A payload larger than the slot is refused
 * (the caller keeps it in RAM) instead of being truncated, because a silently shortened IT
 * pattern would degrade replay in a way nothing downstream could detect.
 */
class EpisodeSpill private constructor(
    private val file: File,
    private val raf: RandomAccessFile,
    private val semCap: Int,
    private val itCap: Int
) {

    private val payloadFloats = semCap + itCap
    private val recordBytes = HEADER_BYTES + payloadFloats.toLong() * 4L

    /** Slots freed by eviction, reused before the file is extended. */
    private val free = ArrayDeque<Int>()
    private var highWater = 0

    /** Scratch buffers. Only ever touched under [lock], so one pair is enough. */
    private val scratch = ByteArray(recordBytes.toInt())
    private val view: ByteBuffer =
        ByteBuffer.wrap(scratch).order(ByteOrder.nativeOrder())

    private val lock = Any()

    fun capacityFor(semLen: Int, itLen: Int): Boolean = semLen <= semCap && itLen <= itCap

    /** Writes a payload and returns its slot, or -1 if it would not fit or the write failed. */
    fun write(sem: FloatArray, it: FloatArray): Int = synchronized(lock) {
        if (!capacityFor(sem.size, it.size)) return -1
        val slot = free.removeFirstOrNull() ?: highWater++
        return try {
            view.clear()
            view.putInt(sem.size)
            view.putInt(it.size)
            val f = view.asFloatBuffer()
            f.put(sem)
            f.position(semCap)
            f.put(it)
            raf.seek(slot * recordBytes)
            raf.write(scratch)
            slot
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.MEMORY, "Episode spill write failed", t)
            free.addFirst(slot)
            -1
        }
    }

    /** Reads a payload back. Null means the read failed and the caller must treat it as lost. */
    fun read(slot: Int): Pair<FloatArray, FloatArray>? = synchronized(lock) {
        if (slot < 0) return null
        return try {
            raf.seek(slot * recordBytes)
            raf.readFully(scratch)
            view.clear()
            val semLen = view.int
            val itLen = view.int
            if (semLen < 0 || semLen > semCap || itLen < 0 || itLen > itCap) {
                NoraLog.warn(NoraLog.Area.MEMORY, "Episode slot $slot has an implausible header")
                return null
            }
            val f = view.asFloatBuffer()
            val sem = FloatArray(semLen)
            f.get(sem)
            f.position(semCap)
            val itp = FloatArray(itLen)
            f.get(itp)
            sem to itp
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.MEMORY, "Episode spill read failed for slot $slot", t)
            null
        }
    }

    fun release(slot: Int) = synchronized(lock) {
        if (slot >= 0) free.addLast(slot)
    }

    fun sizeBytes(): Long = highWater.toLong() * recordBytes

    fun close() = synchronized(lock) {
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }

    /** Closes and removes the file. Called when the feature is turned off or the brain rebuilt. */
    fun destroy() {
        close()
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Two ints: the true length of each of the two payload arrays. */
        private const val HEADER_BYTES = 8L

        private const val NAME = "episodes.bin"

        /**
         * Opens the spill file, truncating anything left from a previous run.
         *
         * Truncating is correct rather than wasteful: slot indices live only in memory, so a
         * file surviving a process restart has no index that could interpret it. Keeping it
         * would be keeping bytes nothing can read.
         */
        fun open(semCap: Int, itCap: Int): EpisodeSpill? = try {
            val dir = File(com.prism.core.PrismPlatform.host.dataDir(), "nora").apply { mkdirs() }
            val f = File(dir, NAME)
            val raf = RandomAccessFile(f, "rw")
            raf.setLength(0L)
            NoraLog.info(
                NoraLog.Area.MEMORY,
                "Episode spill open at ${f.absolutePath}, slot = $semCap + $itCap floats"
            )
            EpisodeSpill(f, raf, semCap, itCap)
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.MEMORY, "Episode spill unavailable; keeping episodes in RAM", t)
            null
        }
    }
}
