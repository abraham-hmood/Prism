package com.prism.launcher.storage.drive

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Calendar

/**
 * exFAT, read-only.
 *
 * FAT's successor for large removable media. A file is a set of directory entries -- a File entry,
 * a Stream Extension with its size and first cluster, then File Name entries of 15 UTF-16 characters
 * each -- and its clusters are either a FAT chain or, when the "no FAT chain" flag is set, simply
 * contiguous, which is how exFAT avoids touching the FAT at all for most files.
 */
class ExFatFileSystem(private val device: BlockDevice) : DriveFileSystem {

    private val boot = device.read(0, 512)
    private val bytesPerSector = 1 shl Bytes.u8(boot, 108)
    private val clusterSize = bytesPerSector shl Bytes.u8(boot, 109)
    private val fatOffset = Bytes.u32(boot, 80) * bytesPerSector
    private val heapOffset = Bytes.u32(boot, 88) * bytesPerSector
    private val clusterCount = Bytes.u32(boot, 92)
    private val rootCluster = Bytes.u32(boot, 96)

    init {
        if (Bytes.ascii(boot, 3, 8) != "EXFAT   ") throw IOException("Not exFAT")
    }

    override val typeId = "exfat"
    override val typeName = "exFAT"
    override val label: String by lazy {
        runCatching {
            val dir = readChain(rootCluster, null, false)
            var at = 0
            var found = ""
            while (at + 32 <= dir.size) {
                val type = Bytes.u8(dir, at)
                if (type == 0) break
                if (type == 0x83) found = Bytes.utf16(dir, at + 2, Bytes.u8(dir, at + 1).coerceAtMost(11))
                at += 32
            }
            found
        }.getOrDefault("")
    }

    private fun next(cluster: Long): Long = Bytes.u32(device.read(fatOffset + cluster * 4, 4), 0)

    private fun clusters(first: Long, size: Long?, contiguous: Boolean): List<Long> {
        if (first < 2) return emptyList()
        if (contiguous && size != null) {
            val count = ((size + clusterSize - 1) / clusterSize).coerceAtLeast(1)
            return (0 until count).map { first + it }
        }
        val out = mutableListOf<Long>()
        var cluster = first
        while (cluster in 2..(clusterCount + 1) && out.size <= clusterCount) {
            out += cluster
            cluster = next(cluster)
        }
        return out
    }

    private fun clusterOffset(cluster: Long): Long = heapOffset + (cluster - 2) * clusterSize

    private fun readChain(first: Long, size: Long?, contiguous: Boolean): ByteArray {
        val list = clusters(first, size, contiguous)
        val total = (size ?: (list.size.toLong() * clusterSize)).coerceAtMost(64L * 1024 * 1024).toInt()
        val out = ByteArray(total)
        var at = 0
        for (cluster in list) {
            if (at >= total) break
            val count = minOf(clusterSize, total - at)
            System.arraycopy(device.read(clusterOffset(cluster), count), 0, out, at, count)
            at += count
        }
        return out
    }

    private data class Raw(val entry: DriveEntry, val firstCluster: Long, val contiguous: Boolean, val dataLength: Long) {
        /** A directory's own bytes: contiguous ones by their recorded length, others by FAT chain. */
        fun read(fs: ExFatFileSystem): ByteArray =
            fs.readChain(firstCluster, if (contiguous) dataLength else null, contiguous)
    }

    private fun parse(dir: ByteArray, path: String): List<Raw> {
        val out = mutableListOf<Raw>()
        var at = 0
        while (at + 32 <= dir.size) {
            val type = Bytes.u8(dir, at)
            if (type == 0) break
            if (type != 0x85) { at += 32; continue }
            val secondary = Bytes.u8(dir, at + 1)
            val attributes = Bytes.u16(dir, at + 4)
            val modified = timestamp(Bytes.u32(dir, at + 12))
            val stream = at + 32
            if (stream + 32 > dir.size || Bytes.u8(dir, stream) != 0xC0) { at += 32; continue }
            val flags = Bytes.u8(dir, stream + 1)
            val nameLength = Bytes.u8(dir, stream + 3)
            val first = Bytes.u32(dir, stream + 20)
            val size = Bytes.u64(dir, stream + 24)
            val name = StringBuilder()
            for (i in 2..secondary) {
                val part = at + i * 32
                if (part + 32 > dir.size || Bytes.u8(dir, part) != 0xC1) break
                name.append(Bytes.utf16(dir, part + 2, 15))
            }
            val text = name.toString().take(nameLength)
            val isDir = attributes and 0x10 != 0
            out += Raw(
                DriveEntry(text, DriveFileSystem.child(path, text), isDir, if (isDir) 0 else size, modified),
                first,
                flags and 0x02 != 0,
                size,
            )
            at += (secondary + 1) * 32
        }
        return out
    }

    private fun timestamp(raw: Long): Long {
        if (raw == 0L) return 0
        val time = (raw and 0xFFFF).toInt()
        val date = (raw shr 16).toInt()
        return Calendar.getInstance().apply {
            clear()
            set(1980 + (date shr 9), ((date shr 5) and 0x0F) - 1, date and 0x1F,
                time shr 11, (time shr 5) and 0x3F, (time and 0x1F) * 2)
        }.timeInMillis
    }

    private fun resolve(path: String): Raw? {
        val clean = DriveFileSystem.normalise(path)
        if (clean == "/") return null
        var dir = readChain(rootCluster, null, false)
        var current: Raw? = null
        var currentPath = "/"
        for (part in clean.removePrefix("/").split('/')) {
            val list = parse(dir, currentPath)
            current = list.firstOrNull { it.entry.name == part }
                ?: list.firstOrNull { it.entry.name.equals(part, ignoreCase = true) }
                ?: throw FileNotFoundException(clean)
            currentPath = current.entry.path
            if (current.entry.isDirectory) dir = current.read(this)
        }
        return current
    }

    override fun list(path: String): List<DriveEntry> {
        val clean = DriveFileSystem.normalise(path)
        val target = resolve(clean)
        if (target != null && !target.entry.isDirectory) throw FileNotFoundException("$clean is a file")
        val dir = target?.read(this) ?: readChain(rootCluster, null, false)
        return parse(dir, clean).map { it.entry }
    }

    override fun open(path: String): InputStream {
        val target = resolve(path) ?: throw FileNotFoundException("/ is a directory")
        if (target.entry.isDirectory) throw FileNotFoundException("$path is a directory")
        val list = clusters(target.firstCluster, target.entry.size, target.contiguous)
        return ChunkedInputStream(target.entry.size) { offset ->
            val index = (offset / clusterSize).toInt()
            val within = (offset % clusterSize).toInt()
            val cluster = list.getOrNull(index) ?: return@ChunkedInputStream ByteArray(0)
            var run = 1
            while (index + run < list.size && run < 64 && list[index + run] == cluster + run) run++
            device.read(clusterOffset(cluster) + within, run * clusterSize - within)
        }
    }
}
