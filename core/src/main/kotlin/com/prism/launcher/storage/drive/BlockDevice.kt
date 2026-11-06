package com.prism.launcher.storage.drive

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A drive, or part of one, as a run of bytes.
 *
 * ## Why Prism reads drives itself
 *
 * A phone only shows a USB stick when Android mounts it, and Android mounts FAT and exFAT (some
 * builds NTFS, read-only). An NTFS, ext4, Btrfs or XFS stick plugged into an unrooted phone simply
 * never appears. The way round that, without root, is the USB host API: Prism talks to the drive
 * directly ([UsbMassStorage][com.prism.launcher.storage.drive] on Android), reads its partition table
 * and reads the file system itself. Everything above the transport -- partitions, detection, the file
 * system readers -- is plain Kotlin here, so it is tested against real disk images on the JVM.
 */
interface BlockDevice : Closeable {

    /** Total size in bytes. */
    val sizeBytes: Long

    /** The device's logical sector size; reads need not be aligned to it. */
    val sectorSize: Int get() = 512

    /**
     * Reads [length] bytes at [offset]. Past the end reads as zeros rather than throwing: file system
     * code asks for whole blocks, and the last one may be short.
     */
    fun read(offset: Long, length: Int): ByteArray

    override fun close() {}
}

/** A disk image file, read-only. */
class FileBlockDevice(file: File) : BlockDevice {
    private val raf = RandomAccessFile(file, "r")
    override val sizeBytes: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        if (offset >= sizeBytes || length <= 0) return out
        raf.seek(offset)
        val wanted = minOf(length.toLong(), sizeBytes - offset).toInt()
        var done = 0
        while (done < wanted) {
            val got = raf.read(out, done, wanted - done)
            if (got <= 0) break
            done += got
        }
        return out
    }

    override fun close() = raf.close()
}

/** Bytes in memory, for tests and for small images. */
class ByteArrayBlockDevice(private val bytes: ByteArray) : BlockDevice {
    override val sizeBytes: Long = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        if (offset >= bytes.size || length <= 0) return out
        val count = minOf(length.toLong(), bytes.size - offset).toInt()
        System.arraycopy(bytes, offset.toInt(), out, 0, count)
        return out
    }
}

/** A window onto [parent]: a partition. Closing it does not close the drive. */
class SliceBlockDevice(
    private val parent: BlockDevice,
    private val start: Long,
    override val sizeBytes: Long,
) : BlockDevice {
    override val sectorSize: Int get() = parent.sectorSize

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= sizeBytes || length <= 0) return ByteArray(length.coerceAtLeast(0))
        val count = minOf(length.toLong(), sizeBytes - offset).toInt()
        val data = parent.read(start + offset, count)
        return if (count == length) data else data.copyOf(length)
    }
}

/**
 * Remembers recently read chunks. A USB transfer costs milliseconds whatever its size, and file
 * system code reads the same metadata blocks over and over, so reading 64 KiB at a time and keeping
 * the last few megabytes turns thousands of round trips into dozens.
 */
class CachedBlockDevice(
    private val inner: BlockDevice,
    private val chunkSize: Int = 64 * 1024,
    private val maxChunks: Int = 256,
) : BlockDevice {
    override val sizeBytes: Long get() = inner.sizeBytes
    override val sectorSize: Int get() = inner.sectorSize

    private val chunks = object : LinkedHashMap<Long, ByteArray>(maxChunks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxChunks
    }

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val position = offset + done
            val index = position / chunkSize
            val chunk = chunks.getOrPut(index) { inner.read(index * chunkSize, chunkSize) }
            val within = (position - index * chunkSize).toInt()
            val count = minOf(length - done, chunkSize - within)
            System.arraycopy(chunk, within, out, done, count)
            done += count
        }
        return out
    }

    override fun close() = inner.close()
}

/** Little- and big-endian reads over a byte array, which every on-disk format here needs. */
internal object Bytes {
    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    fun u16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
    fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
    fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)
    fun be16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
    fun be32(b: ByteArray, at: Int): Long = (be16(b, at).toLong() shl 16) or be16(b, at + 2).toLong()
    fun ascii(b: ByteArray, at: Int, length: Int): String =
        String(b, at, length, Charsets.ISO_8859_1)
    fun utf16(b: ByteArray, at: Int, chars: Int): String =
        String(b, at, chars * 2, Charsets.UTF_16LE)

    /** A NUL- or space-padded label, trimmed. */
    fun label(raw: String): String = raw.substringBefore('\u0000').trim()
}
