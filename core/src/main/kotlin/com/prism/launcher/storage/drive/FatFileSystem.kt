package com.prism.launcher.storage.drive

import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Calendar

/**
 * FAT12, FAT16 and FAT32, read-only, with long file names.
 *
 * The oldest and simplest of the lot: a table of cluster links and directories of 32-byte entries.
 * Long names are stored as extra entries before the short one, backwards, in UTF-16 -- assembled
 * here, and checked against the short name's checksum so an orphaned fragment is not glued onto the
 * wrong file.
 */
class FatFileSystem(private val device: BlockDevice) : DriveFileSystem {

    private val boot = device.read(0, 512)
    private val bytesPerSector = Bytes.u16(boot, 11)
    private val sectorsPerCluster = Bytes.u8(boot, 13)
    private val reservedSectors = Bytes.u16(boot, 14)
    private val fatCount = Bytes.u8(boot, 16)
    private val rootEntryCount = Bytes.u16(boot, 17)
    private val totalSectors = Bytes.u16(boot, 19).toLong().takeIf { it != 0L } ?: Bytes.u32(boot, 32)
    private val fatSectors = Bytes.u16(boot, 22).toLong().takeIf { it != 0L } ?: Bytes.u32(boot, 36)
    private val rootDirSectors = ((rootEntryCount * 32 + bytesPerSector - 1) / bytesPerSector).toLong()
    private val firstDataSector = reservedSectors + fatCount * fatSectors + rootDirSectors
    private val clusterCount = (totalSectors - firstDataSector) / sectorsPerCluster
    private val bits = when {
        Bytes.u16(boot, 22) == 0 -> 32
        clusterCount < 4085 -> 12
        else -> 16
    }
    private val clusterBytes = bytesPerSector * sectorsPerCluster
    private val rootCluster = if (bits == 32) Bytes.u32(boot, 44) else 0L

    override val typeId: String = "fat$bits"
    override val typeName: String = "FAT$bits"
    override val label: String
        get() = rootVolumeLabel() ?: Bytes.label(Bytes.ascii(boot, if (bits == 32) 0x47 else 0x2B, 11))
            .takeUnless { it == "NO NAME" }.orEmpty()

    /** The whole first FAT, read once: at most a few megabytes even on a large stick. */
    private val fat: ByteArray by lazy {
        val size = (fatSectors * bytesPerSector).coerceAtMost(64L * 1024 * 1024).toInt()
        device.read(reservedSectors.toLong() * bytesPerSector, size)
    }

    private fun next(cluster: Long): Long = when (bits) {
        12 -> {
            val at = (cluster + cluster / 2).toInt()
            val pair = Bytes.u16(fat, at)
            (if (cluster % 2 == 0L) pair and 0xFFF else pair shr 4).toLong()
        }
        16 -> Bytes.u16(fat, (cluster * 2).toInt()).toLong()
        else -> Bytes.u32(fat, (cluster * 4).toInt()) and 0x0FFFFFFF
    }

    private fun isEnd(cluster: Long): Boolean = when (bits) {
        12 -> cluster >= 0xFF8
        16 -> cluster >= 0xFFF8
        else -> cluster >= 0x0FFFFFF8
    } || cluster < 2

    private fun chain(start: Long): List<Long> {
        val out = mutableListOf<Long>()
        var cluster = start
        while (!isEnd(cluster) && out.size <= clusterCount) {
            out += cluster
            cluster = next(cluster)
        }
        return out
    }

    private fun clusterOffset(cluster: Long): Long =
        (firstDataSector + (cluster - 2) * sectorsPerCluster) * bytesPerSector

    private fun readChain(start: Long, limit: Long = Long.MAX_VALUE): ByteArray {
        val clusters = chain(start)
        val total = minOf(clusters.size.toLong() * clusterBytes, limit).toInt()
        val out = ByteArray(total)
        var at = 0
        for (cluster in clusters) {
            if (at >= total) break
            val count = minOf(clusterBytes, total - at)
            System.arraycopy(device.read(clusterOffset(cluster), count), 0, out, at, count)
            at += count
        }
        return out
    }

    private fun directoryBytes(cluster: Long): ByteArray =
        if (cluster == 0L && bits != 32) {
            device.read((reservedSectors + fatCount * fatSectors) * bytesPerSector, rootEntryCount * 32)
        } else {
            readChain(if (cluster == 0L) rootCluster else cluster)
        }

    private data class Raw(val entry: DriveEntry, val firstCluster: Long)

    private fun parse(dir: ByteArray, parentPath: String): List<Raw> {
        val out = mutableListOf<Raw>()
        val longName = StringBuilder()
        var longChecksum = -1
        var at = 0
        while (at + 32 <= dir.size) {
            val first = Bytes.u8(dir, at)
            if (first == 0x00) break
            val attributes = Bytes.u8(dir, at + 11)
            if (first == 0xE5) {
                longName.clear(); at += 32; continue
            }
            if (attributes == 0x0F) {
                // A long-name fragment: 13 UTF-16 characters, highest sequence number first.
                val sequence = first and 0x3F
                if (first and 0x40 != 0) longName.clear()
                longChecksum = Bytes.u8(dir, at + 13)
                val part = StringBuilder()
                for (offset in listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
                    val ch = Bytes.u16(dir, at + offset)
                    if (ch == 0 || ch == 0xFFFF) break
                    part.append(ch.toChar())
                }
                if (sequence >= 1) longName.insert(0, part)
                at += 32
                continue
            }
            if (attributes and 0x08 != 0) { // Volume label.
                longName.clear(); at += 32; continue
            }
            val short = shortName(dir, at)
            val name = if (longName.isNotEmpty() && longChecksum == checksum(dir, at)) longName.toString() else short
            longName.clear()
            if (name == "." || name == "..") { at += 32; continue }
            val high = if (bits == 32) Bytes.u16(dir, at + 20).toLong() shl 16 else 0L
            val cluster = high or Bytes.u16(dir, at + 26).toLong()
            val isDir = attributes and 0x10 != 0
            out += Raw(
                DriveEntry(
                    name = name,
                    path = DriveFileSystem.child(parentPath, name),
                    isDirectory = isDir,
                    size = if (isDir) 0 else Bytes.u32(dir, at + 28),
                    modified = dosTime(Bytes.u16(dir, at + 24), Bytes.u16(dir, at + 22)),
                ),
                cluster,
            )
            at += 32
        }
        return out
    }

    private fun shortName(dir: ByteArray, at: Int): String {
        val flags = Bytes.u8(dir, at + 12)
        var base = Bytes.ascii(dir, at, 8).trimEnd()
        var ext = Bytes.ascii(dir, at + 8, 3).trimEnd()
        if (base.startsWith('\u0005')) base = "å" + base.drop(1)
        // Windows NT stores "all lower case" for the base and extension as two flag bits.
        if (flags and 0x08 != 0) base = base.lowercase()
        if (flags and 0x10 != 0) ext = ext.lowercase()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun checksum(dir: ByteArray, at: Int): Int {
        var sum = 0
        for (i in 0 until 11) sum = (((sum and 1) shl 7) + (sum shr 1) + Bytes.u8(dir, at + i)) and 0xFF
        return sum
    }

    private fun dosTime(date: Int, time: Int): Long {
        if (date == 0) return 0
        return Calendar.getInstance().apply {
            clear()
            set(1980 + (date shr 9), ((date shr 5) and 0x0F) - 1, date and 0x1F,
                time shr 11, (time shr 5) and 0x3F, (time and 0x1F) * 2)
        }.timeInMillis
    }

    private fun rootVolumeLabel(): String? = runCatching {
        val dir = directoryBytes(0)
        var at = 0
        while (at + 32 <= dir.size && Bytes.u8(dir, at) != 0) {
            if (Bytes.u8(dir, at + 11) == 0x08 && Bytes.u8(dir, at) != 0xE5) {
                return@runCatching Bytes.ascii(dir, at, 11).trim().takeIf { it.isNotEmpty() }
            }
            at += 32
        }
        null
    }.getOrNull()

    /** Directory cluster for a path, walking from the root. */
    private fun resolve(path: String): Raw? {
        val clean = DriveFileSystem.normalise(path)
        if (clean == "/") return null
        var cluster = 0L
        var current: Raw? = null
        var parentPath = "/"
        for (part in clean.removePrefix("/").split('/')) {
            val entries = parse(directoryBytes(cluster), parentPath)
            current = entries.firstOrNull { it.entry.name == part }
                ?: entries.firstOrNull { it.entry.name.equals(part, ignoreCase = true) }
                ?: throw FileNotFoundException(clean)
            cluster = current.firstCluster
            parentPath = current.entry.path
        }
        return current
    }

    override fun list(path: String): List<DriveEntry> {
        val clean = DriveFileSystem.normalise(path)
        val target = resolve(clean)
        if (target != null && !target.entry.isDirectory) throw FileNotFoundException("$clean is a file")
        return parse(directoryBytes(target?.firstCluster ?: 0L), clean).map { it.entry }
    }

    override fun open(path: String): InputStream {
        val target = resolve(path) ?: throw FileNotFoundException("/ is a directory")
        if (target.entry.isDirectory) throw FileNotFoundException("${target.entry.path} is a directory")
        val clusters = chain(target.firstCluster)
        val size = target.entry.size
        return ChunkedInputStream(size) { offset ->
            val index = (offset / clusterBytes).toInt()
            val within = (offset % clusterBytes).toInt()
            val cluster = clusters.getOrNull(index) ?: return@ChunkedInputStream ByteArray(0)
            // Contiguous clusters are read together, which is most of a file on a fresh stick.
            var run = 1
            while (index + run < clusters.size && run < 256 && clusters[index + run] == cluster + run) run++
            device.read(clusterOffset(cluster) + within, run * clusterBytes - within)
        }
    }
}
