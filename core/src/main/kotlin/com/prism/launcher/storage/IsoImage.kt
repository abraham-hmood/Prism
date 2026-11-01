package com.prism.launcher.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * A minimal ISO9660 reader, used to burn an image without root.
 *
 * ## Why this exists
 *
 * Writing an ISO to the raw device is the correct way to make a bootable stick, and it needs root.
 * On a phone that is not rooted it cannot be done at all, so a Burn button that only offered the
 * raw path would be a button that always fails. Copying the image's files onto a FAT32 or exFAT
 * volume instead is what most Linux install media and many recovery tools are designed to survive:
 * the ISO's directory tree contains the bootloader, the kernel and the payload, so a file-level copy
 * produces a stick the firmware can boot.
 *
 * This reader walks that tree. It understands the primary volume descriptor and Joliet, which is
 * everything an installer ISO uses for its names; Rock Ridge is ignored, because it changes names,
 * not the files, and a file-level burn does not need it.
 *
 * ## What it deliberately does not do
 *
 * No writing, no mounting, no UDF. A UDF image returns no descriptors and the caller reports that
 * the image needs the raw path. That is a real limitation stated up front rather than an image
 * copied into a folder that the firmware will not read.
 */
class IsoImage private constructor(private val source: SeekableSource) {

    /** One directory that the traversal has to read. */
    private data class Directory(
        val lba: Long,
        val sizeBytes: Long,
        /** Prefix, with a trailing slash, or "" for the volume root. */
        val prefix: String,
    )

    private var joliet = false
    private var root: Directory? = null

    private fun loadDescriptors(): DescriptorSet? {
        // The volume descriptor set starts at sector 16 and ends at a terminator, never more than
        // 64 sectors later on any real image.
        var sector = 16
        var primary: DescriptorSet? = null
        var supplementary: DescriptorSet? = null
        while (sector < 16 + 64) {
            val block = source.readAt(sector.toLong() * SECTOR, SECTOR)
            if (block.size < SECTOR) break
            if (block[0] == 0xFF.toByte() || block[0] == 0x00.toByte() && sector > 16) break
            if (String(block, 1, 5, Charsets.US_ASCII) != "CD001") break
            when (block[0].toInt()) {
                1 -> primary = descriptor(block, joliet = false)
                2 -> if (isJolietBlock(block)) supplementary = descriptor(block, joliet = true)
            }
            if (block[0].toInt() == 255) break
            sector++
        }
        // Joliet first: it carries real names. The primary descriptor is the fallback, and an image
        // with no primary descriptor at all is not an ISO this reader understands.
        return supplementary ?: primary
    }

    private fun isJolietBlock(block: ByteArray): Boolean {
        val escape = String(block, 88, 3, Charsets.US_ASCII)
        return escape == "%/@" || escape == "%/C" || escape == "%/E"
    }

    private fun descriptor(block: ByteArray, joliet: Boolean): DescriptorSet? {
        val rootRecord = block.copyOfRange(190, 190 + 34)
        val extent = le32(rootRecord, 2)
        val size = le32(rootRecord, 10)
        if (extent <= 0 || size <= 0) return null
        return DescriptorSet(joliet, extent, size)
    }

    private data class DescriptorSet(val joliet: Boolean, val rootLba: Long, val rootSize: Long)

    /**
     * Every file and folder in the image, root first, then depth-first.
     *
     * Bounded by [MAX_ENTRIES] and [MAX_DEPTH] rather than trusting the image: an installer ISO is
     * a user-supplied file, and a cyclic directory record would otherwise walk forever.
     */
    fun entries(): List<IsoEntry> {
        val descriptors = loadDescriptors() ?: return emptyList()
        joliet = descriptors.joliet
        val out = ArrayList<IsoEntry>()
        val queue = ArrayDeque<Directory>()
        queue.add(Directory(descriptors.rootLba, descriptors.rootSize, ""))
        val seen = HashSet<Long>()
        var depth = 0
        while (queue.isNotEmpty() && out.size < MAX_ENTRIES && depth < MAX_DEPTH * 32) {
            val directory = queue.removeFirst()
            depth++
            if (!seen.add(directory.lba)) continue
            forEachRecord(directory) { nameBytes, extent, size, isDirectory ->
                val name = decodeName(nameBytes) ?: return@forEachRecord
                val path = directory.prefix + name
                out.add(IsoEntry(path, isDirectory, extent, size))
                if (isDirectory) queue.add(Directory(extent, size, "$path/"))
            }
        }
        return out
    }

    /** Convenience for tests and for the burn dialog's summary. */
    fun totalBytes(): Long = entries().filter { !it.isDirectory }.sumOf { it.sizeBytes }

    /** Reads one file's bytes in chunks, so a 4 GB installer does not need 4 GB of heap. */
    fun copyTo(entry: IsoEntry, sink: (ByteArray, Int) -> Unit) {
        var remaining = entry.sizeBytes
        var offset = entry.extentLba * SECTOR
        while (remaining > 0) {
            val chunk = minOf(remaining, 1024L * 1024L).toInt()
            val bytes = source.readAt(offset, chunk)
            if (bytes.isEmpty()) return
            sink(bytes, bytes.size)
            offset += bytes.size
            remaining -= bytes.size
        }
    }

    private fun forEachRecord(
        directory: Directory,
        action: (nameBytes: ByteArray, extent: Long, size: Long, isDirectory: Boolean) -> Unit,
    ) {
        val blockSize = 8192
        var consumed = 0L
        while (consumed < directory.sizeBytes) {
            // The read is capped at the directory's own extent. Without that cap the tail of the
            // block reaches whatever sector follows the directory, and file contents get parsed as
            // directory records -- which produced entries with the wrong sizes and names made of
            // the first bytes of a neighbour's data.
            val wanted = minOf(blockSize.toLong(), directory.sizeBytes - consumed).toInt()
            val block = source.readAt(directory.lba * SECTOR + consumed, wanted)
            if (block.isEmpty()) return
            var position = 0
            while (position < block.size) {
                val length = block[position].toInt() and 0xFF
                if (length == 0) {
                    // Records never straddle a sector; a zero length pads to the next one.
                    position = ((position / SECTOR) + 1) * SECTOR
                    continue
                }
                if (position + length > block.size || length < 34) break
                val nameLength = block[position + 32].toInt() and 0xFF
                if (position + 33 + nameLength > block.size) break
                val nameBytes = block.copyOfRange(position + 33, position + 33 + nameLength)
                val special = nameLength == 1 && (nameBytes[0].toInt() == 0 || nameBytes[0].toInt() == 1)
                if (!special) {
                    val extent = le32(block, position + 2)
                    val size = le32(block, position + 10)
                    val isDirectory = (block[position + 25].toInt() and 0x02) != 0
                    action(nameBytes, extent, size, isDirectory)
                }
                position += length
            }
            consumed += wanted
        }
    }

    /**
     * Decodes a file identifier, or null for one that cannot be represented.
     *
     * Joliet is UCS-2 big-endian. The primary descriptor appends `;1` version suffixes and uses
     * uppercase names; stripping the suffix is what turns `README.TXT;1` back into a usable file
     * name. A name containing a path separator is rejected rather than allowed to escape the target
     * directory.
     */
    private fun decodeName(nameBytes: ByteArray): String? {
        val decoded = if (joliet) {
            String(nameBytes, Charsets.UTF_16BE)
        } else {
            String(nameBytes, Charsets.US_ASCII)
        }
        val trimmed = decoded.substringBeforeLast(';').trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) return null
        return trimmed
    }

    companion object {
        const val SECTOR = 2048
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DEPTH = 64

        /** Opens an ISO file, or returns null when it is not one. */
        fun open(file: File): IsoImage? {
            val source = FileSource.open(file) ?: return null
            val image = IsoImage(source)
            if (image.loadDescriptors() == null) {
                source.close()
                return null
            }
            return image
        }
    }

    private fun le32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}

/** One entry in an ISO's directory tree; [path] is relative to the volume root. */
data class IsoEntry(
    val path: String,
    val isDirectory: Boolean,
    val extentLba: Long,
    val sizeBytes: Long,
)

/** Random access to a file's bytes, which is what ISO's sector offsets need. */
interface SeekableSource {
    val size: Long

    /** Reads up to [length] bytes at [offset]; a short result means end of file. */
    fun readAt(offset: Long, length: Int): ByteArray

    fun close()
}

/** A [SeekableSource] over a file, via `RandomAccessFile`'s positioned reads. */
class FileSource private constructor(private val handle: RandomAccessFile) : SeekableSource {

    override val size: Long get() = handle.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0 || offset >= size) return ByteArray(0)
        val capped = minOf(length.toLong(), size - offset).toInt()
        val out = ByteArray(capped)
        handle.seek(offset)
        var read = 0
        while (read < capped) {
            val count = handle.read(out, read, capped - read)
            if (count <= 0) break
            read += count
        }
        return if (read == capped) out else out.copyOf(read)
    }

    override fun close() = runCatching { handle.close() }.let { }

    companion object {
        fun open(file: File): FileSource? = runCatching {
            FileSource(RandomAccessFile(file, "r"))
        }.getOrNull()
    }
}
