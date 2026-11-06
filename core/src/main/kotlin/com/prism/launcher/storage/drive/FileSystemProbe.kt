package com.prism.launcher.storage.drive

/**
 * What file system is on a partition, read from its own signature rather than from the partition
 * table's type byte -- which says "Windows data" for NTFS, exFAT and FAT alike, and "Linux" for
 * ext4, Btrfs and XFS alike.
 *
 * Covers every file system Prism can format (see FileSystems: FAT16, FAT32, exFAT, NTFS, ext2/3/4,
 * F2FS, Btrfs, XFS, HFS+, UDF) plus the ones a stick commonly arrives with: FAT12, ISO 9660, APFS
 * and swap.
 */
object FileSystemProbe {

    /** A detected file system. [id] matches FileSystems ids where one exists. */
    data class Detected(val id: String, val displayName: String, val label: String = "")

    fun identify(device: BlockDevice): Detected? = runCatching { detect(device) }.getOrNull()

    private fun detect(device: BlockDevice): Detected? {
        val head = device.read(0, 4096)

        // Boot-sector file systems: an OEM name at byte 3 says which.
        val oem = Bytes.ascii(head, 3, 8)
        if (oem == "NTFS    ") return Detected("ntfs", "NTFS")
        if (oem == "EXFAT   ") return Detected("exfat", "exFAT", "")
        fat(head)?.let { return it }

        // XFS keeps its superblock at byte 0.
        if (Bytes.ascii(head, 0, 4) == "XFSB") {
            return Detected("xfs", "XFS", Bytes.label(Bytes.ascii(head, 108, 12)))
        }
        // APFS container superblock.
        if (Bytes.ascii(head, 32, 4) == "NXSB") return Detected("apfs", "APFS")
        // Linux swap ends its first page with the signature.
        val swap = Bytes.ascii(head, 4086, 10)
        if (swap == "SWAPSPACE2" || swap == "SWAP-SPACE") return Detected("swap", "Linux swap")

        // Superblocks at 1 KiB: ext, F2FS, HFS+.
        val at1k = device.read(1024, 1024)
        if (Bytes.u16(at1k, 56) == 0xEF53) return ext(at1k)
        if (Bytes.u32(at1k, 0) == 0xF2F52010L) return Detected("f2fs", "F2FS")
        when (Bytes.ascii(at1k, 0, 2)) {
            "H+", "HX" -> return Detected("hfsplus", "HFS+")
            "BD" -> return Detected("hfs", "HFS")
        }

        // Btrfs: superblock at 64 KiB.
        val btrfs = device.read(0x10000, 0x1000)
        if (Bytes.ascii(btrfs, 0x40, 8) == "_BHRfS_M") {
            return Detected("btrfs", "Btrfs", Bytes.label(String(btrfs, 0x12B, 256, Charsets.UTF_8)))
        }

        // Optical-style volumes: the volume recognition sequence from 32 KiB, in 2 KiB sectors.
        var iso = false
        for (sector in 16 until 24) {
            val descriptor = device.read(sector * 2048L, 8)
            when (Bytes.ascii(descriptor, 1, 5)) {
                "NSR02", "NSR03" -> return Detected("udf", "UDF")
                "CD001" -> iso = true
            }
        }
        if (iso) return Detected("iso9660", "ISO 9660")
        return null
    }

    private fun fat(b: ByteArray): Detected? {
        if (Bytes.u8(b, 0) != 0xEB && Bytes.u8(b, 0) != 0xE9) return null
        if (Bytes.u16(b, 510) != 0xAA55) return null
        val bytesPerSector = Bytes.u16(b, 11)
        val perCluster = Bytes.u8(b, 13)
        val reserved = Bytes.u16(b, 14)
        val fats = Bytes.u8(b, 16)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096) || perCluster == 0 ||
            perCluster and (perCluster - 1) != 0 || reserved == 0 || fats !in 1..2
        ) {
            return null
        }
        val rootEntries = Bytes.u16(b, 17)
        val total = Bytes.u16(b, 19).toLong().takeIf { it != 0L } ?: Bytes.u32(b, 32)
        val fatSize = Bytes.u16(b, 22).toLong().takeIf { it != 0L } ?: Bytes.u32(b, 36)
        val rootSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
        val dataSectors = total - reserved - fats * fatSize - rootSectors
        val clusters = dataSectors / perCluster
        return when {
            Bytes.u16(b, 22) == 0 -> Detected("fat32", "FAT32", Bytes.label(Bytes.ascii(b, 0x47, 11)).takeUnless { it == "NO NAME" }.orEmpty())
            clusters < 4085 -> Detected("fat12", "FAT12", Bytes.label(Bytes.ascii(b, 0x2B, 11)).takeUnless { it == "NO NAME" }.orEmpty())
            else -> Detected("fat16", "FAT16", Bytes.label(Bytes.ascii(b, 0x2B, 11)).takeUnless { it == "NO NAME" }.orEmpty())
        }
    }

    private fun ext(sb: ByteArray): Detected {
        val compat = Bytes.u32(sb, 92)
        val incompat = Bytes.u32(sb, 96)
        val label = Bytes.label(Bytes.ascii(sb, 120, 16))
        // Extents, 64-bit or flex_bg mean ext4; a journal alone means ext3.
        return when {
            incompat and (0x40L or 0x80L or 0x200L) != 0L -> Detected("ext4", "ext4", label)
            compat and 0x4L != 0L -> Detected("ext3", "ext3", label)
            else -> Detected("ext2", "ext2", label)
        }
    }
}
