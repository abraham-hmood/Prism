package com.prism.launcher.storage.drive

/**
 * The partitions on a drive: MBR (with logical partitions inside an extended one), GPT, or none at
 * all -- a "superfloppy", where the file system starts at byte 0, which is how many sticks and most
 * SD cards formatted by cameras arrive.
 */
object PartitionTable {

    data class Partition(
        /** 1-based, in table order, as a user would number them. */
        val index: Int,
        val offset: Long,
        val size: Long,
        /** "MBR", "GPT" or "none". */
        val scheme: String,
        /** What the table says it is, in words ("Linux file system", "Microsoft basic data"). */
        val typeName: String,
        /** GPT partition name, when there is one. */
        val name: String = "",
    )

    fun read(device: BlockDevice): List<Partition> {
        val sector0 = device.read(0, 512)

        // A file system at byte 0 means there is no table, even though a FAT boot sector also ends
        // in 55 AA and its boot code would otherwise be read as four nonsense MBR entries.
        val atZero = FileSystemProbe.identify(device)
        if (atZero != null && atZero.id in BOOT_SECTOR_FILE_SYSTEMS) {
            return listOf(Partition(1, 0, device.sizeBytes, "none", atZero.displayName))
        }

        if (Bytes.u16(sector0, 510) == 0xAA55) {
            val entries = mbrEntries(sector0)
            if (entries.any { it.type == 0xEE }) {
                gpt(device)?.let { return it }
            }
            val partitions = mbr(device, entries)
            if (partitions.isNotEmpty()) return partitions
        }

        // No usable table: a file system that does not use a boot sector (ext4, XFS, Btrfs...) on
        // the whole device, or nothing recognisable at all.
        return listOf(Partition(1, 0, device.sizeBytes, "none", atZero?.displayName ?: "Whole drive"))
    }

    private data class MbrEntry(val status: Int, val type: Int, val start: Long, val count: Long)

    private fun mbrEntries(sector: ByteArray): List<MbrEntry> = (0 until 4).map { i ->
        val at = 446 + i * 16
        MbrEntry(Bytes.u8(sector, at), Bytes.u8(sector, at + 4), Bytes.u32(sector, at + 8), Bytes.u32(sector, at + 12))
    }

    private fun mbr(device: BlockDevice, entries: List<MbrEntry>): List<Partition> {
        val sector = device.sectorSize.toLong()
        val out = mutableListOf<Partition>()
        var index = 1
        for (entry in entries) {
            if (entry.type == 0 || entry.count == 0L) continue
            if (entry.status != 0 && entry.status != 0x80) return emptyList() // Not really a table.
            if (entry.type in EXTENDED) {
                // Each EBR holds one logical partition (relative to itself) and a link to the next
                // EBR (relative to the start of the extended partition).
                val extendedStart = entry.start
                var ebr = extendedStart
                var guard = 0
                while (guard++ < 128) {
                    val block = device.read(ebr * sector, 512)
                    if (Bytes.u16(block, 510) != 0xAA55) break
                    val logical = mbrEntries(block)
                    val first = logical[0]
                    if (first.type != 0 && first.count != 0L) {
                        out += Partition(
                            index = 5 + (out.count { it.index >= 5 }),
                            offset = (ebr + first.start) * sector,
                            size = first.count * sector,
                            scheme = "MBR",
                            typeName = mbrTypeName(first.type),
                        )
                    }
                    val next = logical[1]
                    if (next.type == 0 || next.start == 0L) break
                    ebr = extendedStart + next.start
                }
                index++
                continue
            }
            val offset = entry.start * sector
            if (offset >= device.sizeBytes) continue
            out += Partition(index++, offset, minOf(entry.count * sector, device.sizeBytes - offset), "MBR", mbrTypeName(entry.type))
        }
        return out.sortedBy { it.offset }
    }

    private fun gpt(device: BlockDevice): List<Partition>? {
        // 512-byte sectors are the norm; large USB drives may use 4 KiB, so both are tried.
        for (sector in listOf(512L, 4096L)) {
            val header = device.read(sector, 92)
            if (Bytes.ascii(header, 0, 8) != "EFI PART") continue
            val entriesLba = Bytes.u64(header, 72)
            val count = Bytes.u32(header, 80).toInt().coerceIn(0, 1024)
            val entrySize = Bytes.u32(header, 84).toInt().coerceIn(128, 4096)
            val table = device.read(entriesLba * sector, count * entrySize)
            val out = mutableListOf<Partition>()
            for (i in 0 until count) {
                val at = i * entrySize
                val type = guid(table, at)
                if (type == EMPTY_GUID) continue
                val first = Bytes.u64(table, at + 32)
                val last = Bytes.u64(table, at + 40)
                if (last < first) continue
                val name = Bytes.label(Bytes.utf16(table, at + 56, 36))
                out += Partition(
                    index = i + 1,
                    offset = first * sector,
                    size = (last - first + 1) * sector,
                    scheme = "GPT",
                    typeName = GPT_TYPES[type] ?: "Partition",
                    name = name,
                )
            }
            return out
        }
        return null
    }

    /** A GUID in its usual text form; the first three groups are stored little-endian. */
    private fun guid(b: ByteArray, at: Int): String {
        fun hex(range: IntProgression) = range.joinToString("") { "%02x".format(b[at + it]) }
        return hex(3 downTo 0) + "-" + hex(5 downTo 4) + "-" + hex(7 downTo 6) + "-" +
            hex(8..9) + "-" + hex(10..15)
    }

    private const val EMPTY_GUID = "00000000-0000-0000-0000-000000000000"

    private val EXTENDED = setOf(0x05, 0x0F, 0x85)

    /** File systems that start with a boot sector, so their presence at byte 0 rules out a table. */
    private val BOOT_SECTOR_FILE_SYSTEMS = setOf("fat12", "fat16", "fat32", "exfat", "ntfs")

    private fun mbrTypeName(type: Int): String = when (type) {
        0x01 -> "FAT12"
        0x04, 0x06, 0x0E -> "FAT16"
        0x0B, 0x0C -> "FAT32"
        0x07 -> "NTFS / exFAT"
        0x82 -> "Linux swap"
        0x83 -> "Linux file system"
        0x8E -> "Linux LVM"
        0xA5, 0xA6, 0xA9 -> "BSD"
        0xAF -> "HFS / HFS+"
        0xEF -> "EFI system"
        0xFD -> "Linux RAID"
        else -> "Type 0x%02X".format(type)
    }

    private val GPT_TYPES = mapOf(
        "ebd0a0a2-b9e5-4433-87c0-68b6b72699c7" to "Microsoft basic data",
        "e3c9e316-0b5c-4db8-817d-f92df00215ae" to "Microsoft reserved",
        "de94bba4-06d1-4d40-a16a-bfd50179d6ac" to "Windows recovery",
        "c12a7328-f81f-11d2-ba4b-00a0c93ec93b" to "EFI system",
        "0fc63daf-8483-4772-8e79-3d69d8477de4" to "Linux file system",
        "0657fd6d-a4ab-43c4-84e5-0933c84b4f4f" to "Linux swap",
        "e6d6d379-f507-44c2-a23c-238f2a3df928" to "Linux LVM",
        "48465300-0000-11aa-aa11-00306543ecac" to "Apple HFS+",
        "7c3457ef-0000-11aa-aa11-00306543ecac" to "Apple APFS",
        "21686148-6449-6e6f-744e-656564454649" to "BIOS boot",
    )
}
