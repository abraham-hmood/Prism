package com.prism.launcher.storage.compress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * A file compressed in place, the way NTFS does it: same name, same folder, same modification
 * time, and the same bytes for anyone who reads it through Prism.
 *
 * ## Layout
 *
 * NTFS cuts a file into compression units of 16 clusters (64 KB) and stores each one in whichever
 * of three forms is smallest: all zeros (nothing stored at all), compressed, or as it was when
 * compressing would not free at least one 4 KB cluster. This is that, in a file:
 *
 * ```
 * "PRSMCMP1"  version  codec id  unit size  length  unit count  CRC32
 * per unit: stored size (low 30 bits) | form (top 2 bits)          CRC32
 * the units, back to back
 * ```
 *
 * Each unit decompresses on its own, so reading from the middle of a large file costs one unit,
 * not the whole file (NTFS keeps units aligned for the same reason). Both CRCs must match before a
 * file counts as compressed, so an ordinary file that happens to start with the same eight bytes
 * is never mistaken for one.
 *
 * ## What Prism cannot do that NTFS does
 *
 * NTFS decompresses inside the file system, for every program. On a phone an app has no way into
 * the file system's read path, so the transparency stops at Prism: its Files page, everything a
 * trusted device reads, and every computer that mounts the drive. That is why [DriveCompression]
 * leaves alone the files other apps read straight from storage (photos, music, app data).
 */
object CompressedFile {

    const val DEFAULT_UNIT = 64 * 1024
    private const val CLUSTER = 4096
    private val MAGIC = "PRSMCMP1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1

    private const val FORM_RAW = 0
    private const val FORM_COMPRESSED = 1
    private const val FORM_ZERO = 2

    /** What a compressed file's header says. */
    class Header(
        val codecId: String,
        val unitSize: Int,
        val length: Long,
        /** Stored size and form of each unit. */
        val units: IntArray,
        /** Where the first unit's data starts. */
        val dataStart: Long,
    ) {
        fun storedSize(unit: Int): Int = units[unit] and 0x3FFFFFFF
        fun form(unit: Int): Int = units[unit] ushr 30
        fun unitLength(unit: Int): Int = minOf(unitSize.toLong(), length - unit.toLong() * unitSize).toInt()
    }

    private class Cached(val modified: Long, val size: Long, val header: Header?)

    /** Headers by path, while the file's time and size are unchanged. Listing a folder reads many. */
    private val cache = ConcurrentHashMap<String, Cached>()

    /** The header of [file], or null when it is an ordinary file. */
    fun header(file: File): Header? {
        val size = file.length()
        if (size < MAGIC.size + 32 || !file.isFile) return null
        val modified = file.lastModified()
        cache[file.path]?.let { if (it.modified == modified && it.size == size) return it.header }
        val header = runCatching { file.inputStream().buffered(8 * 1024).use { readHeader(it) } }.getOrNull()
        if (cache.size > 20_000) cache.clear()
        cache[file.path] = Cached(modified, size, header)
        return header
    }

    fun isCompressed(file: File): Boolean = header(file) != null

    /** What [file] holds: its real length, compressed or not. */
    fun logicalLength(file: File): Long = header(file)?.length ?: file.length()

    private fun readHeader(input: InputStream): Header? {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val crc = CRC32()
        crc.update(magic)
        val version = data.readUnsignedByte()
        if (version != VERSION) return null
        val idLength = data.readUnsignedByte()
        val id = ByteArray(idLength).also { data.readFully(it) }
        val unitSize = data.readInt()
        val length = data.readLong()
        val count = data.readInt()
        crc.update(version)
        crc.update(idLength)
        crc.update(id)
        crc.update(intBytes(unitSize))
        crc.update(longBytes(length))
        crc.update(intBytes(count))
        if (data.readInt() != crc.value.toInt()) return null
        if (unitSize <= 0 || length < 0 || count < 0 || count.toLong() != (length + unitSize - 1) / unitSize) return null
        val units = IntArray(count) { data.readInt() }
        val tableCrc = CRC32().also { table -> units.forEach { table.update(intBytes(it)) } }
        if (data.readInt() != tableCrc.value.toInt()) return null
        val dataStart = MAGIC.size + 1L + 1 + idLength + 4 + 8 + 4 + 4 + count * 4L + 4
        return Header(String(id, Charsets.UTF_8), unitSize, length, units, dataStart)
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun longBytes(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }

    /**
     * Compresses [file] in place with [codec]. Returns the bytes freed, or 0 when the file was left
     * as it was: already compressed, too small, changed while being read, or not worth it.
     */
    fun compress(file: File, codec: Codec, cancelled: () -> Boolean = { false }): Long {
        val before = file.length()
        val modified = file.lastModified()
        if (before < CLUSTER || !file.isFile || isCompressed(file)) return 0
        val unitSize = codec.unitSize
        val count = ((before + unitSize - 1) / unitSize).toInt()
        val idBytes = codec.id.toByteArray(Charsets.UTF_8)
        require(idBytes.size < 256) { "Codec id too long" }
        val temp = File(file.parentFile, "." + file.name + ".prism-compressing")
        try {
            RandomAccessFile(temp, "rw").use { out ->
                out.setLength(0)
                val headCrc = CRC32()
                val head = java.io.ByteArrayOutputStream()
                DataOutputStream(head).apply {
                    write(MAGIC)
                    writeByte(VERSION)
                    writeByte(idBytes.size)
                    write(idBytes)
                    writeInt(unitSize)
                    writeLong(before)
                    writeInt(count)
                }
                headCrc.update(head.toByteArray())
                DataOutputStream(head).writeInt(headCrc.value.toInt())
                out.write(head.toByteArray())
                val tableAt = out.filePointer
                out.write(ByteArray(count * 4 + 4))
                val units = IntArray(count)
                val buffer = ByteArray(unitSize)
                file.inputStream().use { input ->
                    for (unit in 0 until count) {
                        if (cancelled()) return 0
                        val length = minOf(unitSize.toLong(), before - unit.toLong() * unitSize).toInt()
                        var read = 0
                        while (read < length) {
                            val n = input.read(buffer, read, length - read)
                            if (n < 0) return 0 // shorter than it was: it is being written
                            read += n
                        }
                        if (isZero(buffer, length)) {
                            units[unit] = FORM_ZERO shl 30
                            continue
                        }
                        val packed = runCatching { codec.encode(buffer, 0, length) }.getOrNull()
                        // NTFS's rule: a unit is kept compressed only if that frees a whole cluster.
                        if (packed != null && packed.size <= length - CLUSTER && verifies(codec, packed, buffer, length)) {
                            out.write(packed)
                            units[unit] = (FORM_COMPRESSED shl 30) or packed.size
                        } else {
                            out.write(buffer, 0, length)
                            units[unit] = (FORM_RAW shl 30) or length
                        }
                    }
                }
                val table = java.io.ByteArrayOutputStream(count * 4 + 4)
                val tableCrc = CRC32()
                DataOutputStream(table).apply {
                    units.forEach { writeInt(it); tableCrc.update(intBytes(it)) }
                    writeInt(tableCrc.value.toInt())
                }
                out.seek(tableAt)
                out.write(table.toByteArray())
            }
            val after = temp.length()
            // Not worth a file that only Prism can read: at least one cluster must be freed.
            if (after > before - CLUSTER) return 0
            if (file.length() != before || file.lastModified() != modified) return 0
            replace(temp, file, modified)
            return before - after
        } finally {
            temp.delete()
        }
    }

    /**
     * A codec is only trusted with a unit it gives back exactly. Cheap next to compressing it, and
     * it is the difference between a bug costing some space and a bug costing somebody's file.
     */
    private fun verifies(codec: Codec, packed: ByteArray, original: ByteArray, length: Int): Boolean = runCatching {
        val back = codec.decode(packed, length)
        back.size == length && (0 until length).all { back[it] == original[it] }
    }.getOrDefault(false)

    private fun isZero(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i].toInt() != 0) return false
        return true
    }

    private fun replace(temp: File, file: File, modified: Long) {
        temp.setLastModified(modified)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        file.setLastModified(modified)
        cache.remove(file.path)
    }

    /** Restores [file] to an ordinary file. True when it was compressed and now is not. */
    fun decompress(file: File, cancelled: () -> Boolean = { false }): Boolean {
        val header = header(file) ?: return false
        val modified = file.lastModified()
        val temp = File(file.parentFile, "." + file.name + ".prism-decompressing")
        try {
            open(file, 0, header).use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) return false
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (temp.length() != header.length) throw IOException("${file.name} decompressed to the wrong size")
            replace(temp, file, modified)
            return true
        } finally {
            temp.delete()
        }
    }

    /** [file]'s real bytes from [offset] on, whether it is compressed or not. */
    fun open(file: File, offset: Long = 0, known: Header? = null): InputStream {
        val header = known ?: header(file)
        if (header == null) {
            return file.inputStream().also { stream ->
                var left = offset
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) break
                    left -= skipped
                }
            }
        }
        return UnitStream(RandomAccessFile(file, "r"), header, offset)
    }

    /** Reads a compressed file unit by unit, decompressing each one only when it is reached. */
    private class UnitStream(private val raf: RandomAccessFile, private val header: Header, offset: Long) : InputStream() {
        private val codec = BlockCodecs.byId(header.codecId)
        private val starts = LongArray(header.units.size + 1).also { starts ->
            starts[0] = header.dataStart
            for (i in header.units.indices) starts[i + 1] = starts[i] + header.storedSize(i)
        }
        private var unit = (offset.coerceIn(0, header.length) / header.unitSize).toInt()
        private var current: ByteArray = ByteArray(0)
        private var at = 0
        private var skipInUnit = (offset.coerceIn(0, header.length) - unit.toLong() * header.unitSize).toInt()

        private fun fill(): Boolean {
            while (at >= current.size) {
                if (unit >= header.units.size) return false
                val length = header.unitLength(unit)
                current = when (header.form(unit)) {
                    FORM_ZERO -> ByteArray(length)
                    FORM_RAW -> ByteArray(length).also { raf.seek(starts[unit]); raf.readFully(it) }
                    else -> {
                        val stored = ByteArray(header.storedSize(unit)).also { raf.seek(starts[unit]); raf.readFully(it) }
                        val c = codec ?: throw IOException(
                            "This file was compressed with \"${header.codecId}\", which is not on this device",
                        )
                        c.decode(stored, length)
                    }
                }
                unit++
                at = skipInUnit.also { skipInUnit = 0 }
            }
            return true
        }

        override fun read(): Int = if (!fill()) -1 else current[at++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - at)
            System.arraycopy(current, at, b, off, n)
            at += n
            return n
        }

        override fun close() = raf.close()
    }
}
