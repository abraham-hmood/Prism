package com.prism.launcher.storage.drive

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * ext2, ext3 and ext4, read-only.
 *
 * What a reader needs from ext4 is smaller than the format: the superblock, the group descriptors,
 * inodes, and two ways of mapping a file's blocks -- extent trees (ext4) and the classic
 * direct/indirect block map (ext2/3, and ext4 files created without extents). Directories are read
 * as linear lists of entries; hashed (htree) directories store their index inside entries a linear
 * reader skips, which is exactly why the format was designed that way. The journal is ignored, which
 * is right for a cleanly unmounted drive; one pulled out mid-write may show its last few changes
 * missing. Encrypted directories are listed by their encrypted names and cannot be opened.
 */
class Ext4FileSystem(private val device: BlockDevice) : DriveFileSystem {

    private val sb = device.read(1024, 1024)
    private val blockSize = 1024 shl Bytes.u32(sb, 24).toInt()
    private val blocksPerGroup = Bytes.u32(sb, 32)
    private val inodesPerGroup = Bytes.u32(sb, 40)
    private val revision = Bytes.u32(sb, 76)
    private val inodeSize = if (revision >= 1) Bytes.u16(sb, 88) else 128
    private val incompat = Bytes.u32(sb, 96)
    private val is64 = incompat and INCOMPAT_64BIT != 0L
    private val descSize = if (is64) Bytes.u16(sb, 254).coerceAtLeast(32) else 32
    private val firstDataBlock = Bytes.u32(sb, 20)
    private val blocksCount = Bytes.u32(sb, 4) or (if (is64) Bytes.u32(sb, 0x150) shl 32 else 0L)
    private val groupCount = ((blocksCount - firstDataBlock + blocksPerGroup - 1) / blocksPerGroup).toInt()

    init {
        if (Bytes.u16(sb, 56) != 0xEF53) throw IOException("Not an ext file system")
    }

    override val typeId: String = when {
        incompat and (0x40L or 0x80L or 0x200L) != 0L -> "ext4"
        Bytes.u32(sb, 92) and 0x4L != 0L -> "ext3"
        else -> "ext2"
    }
    override val typeName: String = typeId
    override val label: String = Bytes.label(Bytes.ascii(sb, 120, 16))

    private val descriptors: ByteArray by lazy {
        val table = (firstDataBlock + 1) * blockSize
        device.read(table, groupCount * descSize)
    }

    private fun inodeTable(group: Int): Long {
        val at = group * descSize
        val low = Bytes.u32(descriptors, at + 8)
        val high = if (is64 && descSize >= 64) Bytes.u32(descriptors, at + 0x28) else 0L
        return low or (high shl 32)
    }

    private class Inode(val raw: ByteArray) {
        val mode get() = Bytes.u16(raw, 0)
        val size get() = Bytes.u32(raw, 4) or (Bytes.u32(raw, 108) shl 32)
        val mtime get() = Bytes.u32(raw, 16) * 1000L
        val flags get() = Bytes.u32(raw, 32)
        val isDirectory get() = mode and 0xF000 == 0x4000
        val isSymlink get() = mode and 0xF000 == 0xA000
        fun blockArea(): ByteArray = raw.copyOfRange(40, 100)
    }

    private fun inode(number: Long): Inode {
        val group = ((number - 1) / inodesPerGroup).toInt()
        val index = (number - 1) % inodesPerGroup
        val offset = inodeTable(group) * blockSize + index * inodeSize
        return Inode(device.read(offset, inodeSize.coerceAtLeast(128)))
    }

    // ── Mapping a file's logical blocks to disk ───────────────────────────

    /** Logical block -> (physical block, run length); gaps are holes. */
    private data class Run(val logical: Long, val physical: Long, val length: Long, val zero: Boolean)

    private fun runs(inode: Inode): List<Run> =
        if (inode.flags and FLAG_EXTENTS != 0L) {
            val out = mutableListOf<Run>()
            extents(inode.blockArea(), out, 0)
            out.sortedBy { it.logical }
        } else {
            blockMap(inode)
        }

    private fun extents(node: ByteArray, out: MutableList<Run>, depthGuard: Int) {
        if (depthGuard > 8 || Bytes.u16(node, 0) != 0xF30A) return
        val entries = Bytes.u16(node, 2)
        val depth = Bytes.u16(node, 6)
        for (i in 0 until entries) {
            val at = 12 + i * 12
            if (depth == 0) {
                val logical = Bytes.u32(node, at)
                var length = Bytes.u16(node, at + 4).toLong()
                // Above 32768 the extent is allocated but unwritten: it reads as zeros.
                val unwritten = length > 32768
                if (unwritten) length -= 32768
                val physical = (Bytes.u16(node, at + 6).toLong() shl 32) or Bytes.u32(node, at + 8)
                out += Run(logical, physical, length, unwritten)
            } else {
                val child = Bytes.u32(node, at + 4) or (Bytes.u16(node, at + 8).toLong() shl 32)
                extents(device.read(child * blockSize, blockSize), out, depthGuard + 1)
            }
        }
    }

    /** ext2/3 block map: 12 direct pointers, then single, double and triple indirection. */
    private fun blockMap(inode: Inode): List<Run> {
        val area = inode.blockArea()
        val pointers = blockSize / 4
        val total = (inode.size + blockSize - 1) / blockSize
        val out = mutableListOf<Run>()
        var logical = 0L
        fun add(physical: Long) {
            if (logical >= total) return
            if (physical != 0L) out += Run(logical, physical, 1, false)
            logical++
        }
        fun walk(block: Long, level: Int) {
            if (logical >= total) return
            if (block == 0L) {
                var skip = 1L
                repeat(level) { skip *= pointers }
                logical += skip
                return
            }
            val table = device.read(block * blockSize, blockSize)
            for (i in 0 until pointers) {
                val next = Bytes.u32(table, i * 4)
                if (level == 1) add(next) else walk(next, level - 1)
                if (logical >= total) return
            }
        }
        for (i in 0 until 12) add(Bytes.u32(area, i * 4))
        walk(Bytes.u32(area, 48), 1)
        walk(Bytes.u32(area, 52), 2)
        walk(Bytes.u32(area, 56), 3)
        // Merge consecutive blocks into runs, so a read covers a contiguous file in one go.
        val merged = mutableListOf<Run>()
        for (run in out) {
            val last = merged.lastOrNull()
            if (last != null && last.logical + last.length == run.logical && last.physical + last.length == run.physical) {
                merged[merged.size - 1] = last.copy(length = last.length + 1)
            } else {
                merged += run
            }
        }
        return merged
    }

    /** Bytes from [offset] of a file, at most [max]; holes read as zeros. */
    private fun readAt(inode: Inode, runs: List<Run>, offset: Long, max: Int): ByteArray {
        if (inode.flags and FLAG_INLINE != 0L) {
            // Inline data: the first 60 bytes live where the block map would be.
            val data = inode.blockArea().copyOf(minOf(60L, inode.size).toInt())
            return if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset.toInt(), data.size)
        }
        val block = offset / blockSize
        val within = (offset % blockSize).toInt()
        val remaining = inode.size - offset
        val run = runs.firstOrNull { block >= it.logical && block < it.logical + it.length }
        if (run == null || run.zero) {
            // A hole, up to the next mapped run.
            val next = runs.firstOrNull { it.logical > block }?.logical ?: Long.MAX_VALUE
            val holeBytes = minOf((next - block) * blockSize - within, remaining, max.toLong())
            return ByteArray(holeBytes.toInt().coerceAtLeast(1))
        }
        val blocksLeft = run.logical + run.length - block
        val count = minOf(blocksLeft * blockSize - within, remaining, max.toLong()).toInt()
        return device.read((run.physical + (block - run.logical)) * blockSize + within, count)
    }

    private fun readAll(inode: Inode): ByteArray {
        val runs = runs(inode)
        val size = inode.size.coerceAtMost(256L * 1024 * 1024).toInt()
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val chunk = readAt(inode, runs, at.toLong(), size - at)
            if (chunk.isEmpty()) break
            System.arraycopy(chunk, 0, out, at, minOf(chunk.size, size - at))
            at += chunk.size
        }
        return out
    }

    // ── Directories ───────────────────────────────────────────────────────

    private data class Raw(val entry: DriveEntry, val inode: Long)

    private fun entries(dirInode: Inode, path: String): List<Raw> {
        val data = readAll(dirInode)
        val out = mutableListOf<Raw>()
        var at = 0
        while (at + 8 <= data.size) {
            val number = Bytes.u32(data, at)
            val recordLength = Bytes.u16(data, at + 4)
            val nameLength = Bytes.u8(data, at + 6)
            if (recordLength < 8) break
            // Inode 0 is unused space, a checksum tail, or an htree node pretending to be empty.
            if (number != 0L && nameLength > 0 && at + 8 + nameLength <= data.size) {
                val name = String(data, at + 8, nameLength, Charsets.UTF_8)
                if (name != "." && name != "..") {
                    val child = inode(number)
                    out += Raw(
                        DriveEntry(
                            name = name,
                            path = DriveFileSystem.child(path, name),
                            isDirectory = child.isDirectory,
                            size = if (child.isDirectory) 0 else child.size,
                            modified = child.mtime,
                            isSymlink = child.isSymlink,
                        ),
                        number,
                    )
                }
            }
            at += recordLength
        }
        return out
    }

    private fun resolve(path: String): Pair<Inode, Long> {
        var current = inode(ROOT_INODE)
        var number = ROOT_INODE
        var currentPath = "/"
        val clean = DriveFileSystem.normalise(path)
        if (clean == "/") return current to number
        for (part in clean.removePrefix("/").split('/')) {
            if (!current.isDirectory) throw FileNotFoundException(clean)
            val match = entries(current, currentPath).firstOrNull { it.entry.name == part }
                ?: throw FileNotFoundException(clean)
            number = match.inode
            current = inode(number)
            currentPath = match.entry.path
        }
        return current to number
    }

    override fun list(path: String): List<DriveEntry> {
        val (inode, _) = resolve(path)
        if (!inode.isDirectory) throw FileNotFoundException("$path is a file")
        return entries(inode, DriveFileSystem.normalise(path)).map { it.entry }
    }

    override fun open(path: String): InputStream {
        val (inode, _) = resolve(path)
        if (inode.isDirectory) throw FileNotFoundException("$path is a directory")
        if (inode.isSymlink && inode.size < 60 && inode.flags and FLAG_EXTENTS == 0L) {
            // A fast symlink keeps its target in the inode itself.
            return inode.blockArea().copyOf(inode.size.toInt()).inputStream()
        }
        val runs = runs(inode)
        return ChunkedInputStream(inode.size) { offset -> readAt(inode, runs, offset, 1024 * 1024) }
    }

    private companion object {
        const val ROOT_INODE = 2L
        const val INCOMPAT_64BIT = 0x80L
        const val FLAG_EXTENTS = 0x80000L
        const val FLAG_INLINE = 0x10000000L
    }
}
