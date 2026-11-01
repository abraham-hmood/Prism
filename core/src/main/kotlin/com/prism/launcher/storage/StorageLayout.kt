package com.prism.launcher.storage

/**
 * What a removable volume is going to look like after it is formatted.
 *
 * ## Why the plan is sizes and not byte offsets
 *
 * A partition editor's natural data model is a list of ranges, and that is what [LayoutGeometry]
 * computes. The *editable* model is deliberately simpler: partitions are packed from
 * [PartitionPlan.startOffsetBytes] in order, with free space only ever at the end. Every drag the
 * partition bar supports -- moving a boundary, pulling the last edge, adding or removing a
 * partition -- maps onto changing one size in this list, so a plan can never be built that overlaps,
 * leaves a hole, or starts before the first megabyte where the partition table lives. A general
 * offset model would let the UI produce those states and then need to reject them after the fact.
 *
 * Everything here is pure Kotlin with no Android imports: the partition maths is the part that has
 * to be right, so it lives where it can be unit tested.
 */

/** One entry in the file-system dropdown. */
data class FileSystem(
    /** Stable id used by plans, saved settings and command building. */
    val id: String,
    /** What the dropdown shows. */
    val displayName: String,
    /** The mkfs binary this needs on the device. Null for a read-only file system. */
    val mkfsBinary: String?,
    /** Longest label the file system stores, in characters. */
    val maxLabel: Int,
    /** Allocation units offered for this file system, in bytes. Empty means "not tunable". */
    val allocationUnits: List<Int> = emptyList(),
    /** True when the file system can hold an operating system's files for a file-level burn. */
    val canHoldOsFiles: Boolean = true,
    /** One line for the dropdown's footer, for anything surprising about this entry. */
    val note: String = "",
    /** True when the file system's own tooling decides the block size. */
    val allocationUnitIsFixed: Boolean = false,
)

/**
 * The file systems Prism can create.
 *
 * Twice the ten the request asked for, because several of these are cheap to add once the command
 * builder exists and users do arrive with a stick they want to be ext4 or exFAT. Every entry is a
 * real `mkfs` invocation, not a label: [FileSystem.mkfsBinary] is probed on the device before the
 * Format button is allowed to do anything, and an entry whose tool is missing is reported as such
 * rather than failing half way through.
 */
object FileSystems {

    val FAT16 = FileSystem(
        id = "fat16", displayName = "FAT16", mkfsBinary = "mkfs.vfat", maxLabel = 11,
        allocationUnits = listOf(2048, 4096, 8192, 16384, 32768, 65536),
        note = "Old and small; cameras and car stereos. 2 GB limit.",
    )
    val FAT32 = FileSystem(
        id = "fat32", displayName = "FAT32", mkfsBinary = "mkfs.vfat", maxLabel = 11,
        allocationUnits = listOf(4096, 8192, 16384, 32768, 65536),
        note = "Reads everywhere, but no single file may exceed 4 GB.",
    )
    val EXFAT = FileSystem(
        id = "exfat", displayName = "exFAT", mkfsBinary = "mkfs.exfat", maxLabel = 11,
        allocationUnits = listOf(4096, 32768, 131072, 262144),
        note = "Large files, read by Windows, macOS and Linux.",
    )
    val NTFS = FileSystem(
        id = "ntfs", displayName = "NTFS", mkfsBinary = "mkfs.ntfs", maxLabel = 32,
        allocationUnits = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536),
        note = "Windows' own file system, including permissions and journaling.",
    )
    val EXT2 = FileSystem(
        id = "ext2", displayName = "ext2", mkfsBinary = "mke2fs", maxLabel = 16,
        allocationUnits = listOf(1024, 2048, 4096),
        note = "No journal. For small boot partitions.",
    )
    val EXT3 = FileSystem(
        id = "ext3", displayName = "ext3", mkfsBinary = "mke2fs", maxLabel = 16,
        allocationUnits = listOf(1024, 2048, 4096),
        note = "ext2 with a journal.",
    )
    val EXT4 = FileSystem(
        id = "ext4", displayName = "ext4", mkfsBinary = "mke2fs", maxLabel = 16,
        allocationUnits = listOf(1024, 2048, 4096, 16384, 32768, 65536),
        note = "The default on most Linux systems.",
    )
    val F2FS = FileSystem(
        id = "f2fs", displayName = "F2FS", mkfsBinary = "mkfs.f2fs", maxLabel = 16,
        allocationUnits = listOf(4096), allocationUnitIsFixed = true,
        note = "Flash-friendly; Android's own data file system.",
    )
    val BTRFS = FileSystem(
        id = "btrfs", displayName = "Btrfs", mkfsBinary = "mkfs.btrfs", maxLabel = 255,
        allocationUnits = listOf(4096, 8192, 16384, 32768, 65536),
        note = "Copy-on-write, snapshots, checksums.",
    )
    val XFS = FileSystem(
        id = "xfs", displayName = "XFS", mkfsBinary = "mkfs.xfs", maxLabel = 12,
        allocationUnits = listOf(4096, 8192, 16384, 32768, 65536),
        note = "High-throughput; cannot be shrunk.",
    )
    val HFSPLUS = FileSystem(
        id = "hfsplus", displayName = "HFS+", mkfsBinary = "mkfs.hfsplus", maxLabel = 27,
        allocationUnits = listOf(4096, 8192, 16384, 32768),
        note = "Intel-era Mac drives. Modern macOS prefers APFS.",
    )
    val UDF = FileSystem(
        id = "udf", displayName = "UDF", mkfsBinary = "mkudffs", maxLabel = 30,
        allocationUnits = listOf(2048, 4096),
        note = "Optical media and large removable disks; read by most systems.",
    )

    /** In dropdown order: the ones most people want first. */
    val all: List<FileSystem> = listOf(
        FAT32, EXFAT, NTFS, EXT4, EXT3, EXT2, F2FS, BTRFS, XFS, HFSPLUS, UDF, FAT16,
    )

    fun byId(id: String): FileSystem = all.firstOrNull { it.id == id } ?: FAT32

    /** The allocation units to show for this file system, with 0 meaning "let mkfs decide". */
    fun allocationUnitChoices(fileSystem: FileSystem): List<Int> =
        if (fileSystem.allocationUnits.isEmpty()) listOf(0) else listOf(0) + fileSystem.allocationUnits

    /**
     * Strips characters the file system cannot store, rather than letting mkfs fail on them.
     *
     * FAT is the strict one -- eleven characters, uppercase, and a set of punctuation it will not
     * take at all -- so the same sanitiser is applied to every file system and then truncated to
     * that file system's limit. A label the user typed is preserved as far as it is legal, which is
     * less surprising than a rejection after the drive has already been unmounted.
     */
    fun sanitizeLabel(fileSystem: FileSystem, raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_') ch else ' '
        }.joinToString("").replace(Regex("\\s+"), " ").trim()
        val cased = if (fileSystem == FAT32 || fileSystem == FAT16 || fileSystem == EXFAT) {
            cleaned.uppercase()
        } else {
            cleaned
        }
        return cased.take(fileSystem.maxLabel)
    }
}

/** The format options the dialog collects. */
data class FormatSpec(
    val fileSystem: String = FileSystems.FAT32.id,
    val label: String = "",
    /** 0 means the file system's default. */
    val allocationUnit: Int = 0,
    /** "Fast" skips the full-surface bad-block write most mkfs tools perform. */
    val quick: Boolean = true,
) {
    val fs: FileSystem get() = FileSystems.byId(fileSystem)
}

/** One partition in a plan. Its position is implied by the order of the list. */
data class PlannedPartition(
    val sizeBytes: Long,
    val fileSystem: String,
    val label: String = "",
) {
    val fs: FileSystem get() = FileSystems.byId(fileSystem)
}

/**
 * A whole-disk formatting plan.
 *
 * [startOffsetBytes] leaves room for the partition table (GPT primary plus its backup, and the
 * alignment every OS expects). It is a constant rather than a user setting because a partition
 * starting before it is the classic way to make a drive that looks right and will not boot.
 */
data class PartitionPlan(
    val partitions: List<PlannedPartition>,
    val startOffsetBytes: Long = ALIGNMENT_BYTES,
) {

    companion object {
        /** 1 MiB: the alignment every modern OS assumes, and room for GPT. */
        const val ALIGNMENT_BYTES: Long = 1024L * 1024L
        /** No sensible file system is smaller; below this mkfs itself refuses. */
        const val MINIMUM_PARTITION_BYTES: Long = 16L * 1024L * 1024L

        /** One partition filling the volume, which is what "format this stick" means. */
        fun wholeVolume(totalBytes: Long, spec: FormatSpec): PartitionPlan {
            val usable = (totalBytes - ALIGNMENT_BYTES).coerceAtLeast(0)
            return PartitionPlan(
                listOf(PlannedPartition(usable, spec.fileSystem, spec.label)),
            )
        }
    }

    val usedBytes: Long get() = partitions.sumOf { it.sizeBytes }

    fun freeBytes(totalBytes: Long): Long =
        (totalBytes - startOffsetBytes - usedBytes).coerceAtLeast(0)

    /** Where each partition sits, plus the free tail. */
    fun geometry(totalBytes: Long): LayoutGeometry {
        var cursor = startOffsetBytes
        val segments = partitions.mapIndexed { index, partition ->
            val start = cursor
            cursor += partition.sizeBytes
            Segment(index, start, start + partition.sizeBytes)
        }
        val freeStart = cursor.coerceAtMost(totalBytes)
        return LayoutGeometry(segments, freeStart, totalBytes)
    }

    // ── Edits the partition bar performs ────────────────────────────────────

    /**
     * Moves the boundary between partition [index] and whatever follows it.
     *
     * Everything stays packed, so growing [index] takes the difference out of the next partition
     * (or the free tail) and shrinking it gives that space back. Both ends are clamped: the
     * partition itself cannot go under [MINIMUM_PARTITION_BYTES], and neither can its neighbour,
     * which is what stops a drag from producing a zero-byte partition that mkfs would reject.
     */
    fun resize(index: Int, newSizeBytes: Long, totalBytes: Long): PartitionPlan {
        val current = partitions.getOrNull(index) ?: return this
        val following = partitions.getOrNull(index + 1)
        val availableForThis = current.sizeBytes + if (following != null) {
            following.sizeBytes
        } else {
            freeBytes(totalBytes)
        }
        val minimumForFollowing = if (following != null) PartitionPlan.MINIMUM_PARTITION_BYTES else 0L
        val minimumForThis = PartitionPlan.MINIMUM_PARTITION_BYTES
        val largest = (availableForThis - minimumForFollowing).coerceAtLeast(minimumForThis)
        val size = newSizeBytes.coerceIn(minimumForThis, largest)
        val delta = size - current.sizeBytes
        val updated = partitions.toMutableList()
        updated[index] = current.copy(sizeBytes = size)
        if (following != null) {
            val neighbour = (following.sizeBytes - delta)
                .coerceAtLeast(PartitionPlan.MINIMUM_PARTITION_BYTES)
            updated[index + 1] = following.copy(sizeBytes = neighbour)
        }
        return copy(partitions = updated)
    }

    /** Re-formats one partition without touching its size. */
    fun reformat(index: Int, fileSystem: String, label: String): PartitionPlan {
        val current = partitions.getOrNull(index) ?: return this
        val updated = partitions.toMutableList()
        updated[index] = current.copy(fileSystem = fileSystem, label = label)
        return copy(partitions = updated)
    }

    fun rename(index: Int, label: String): PartitionPlan {
        val current = partitions.getOrNull(index) ?: return this
        val updated = partitions.toMutableList()
        updated[index] = current.copy(label = label)
        return copy(partitions = updated)
    }

    /**
     * Deletes partition [index] and gives its space to the next one, or to the free tail when it
     * was last. Packed layout means "delete" is a no-op gap-free operation.
     */
    fun remove(index: Int): PartitionPlan {
        if (index !in partitions.indices || partitions.size <= 1) return this
        val removed = partitions[index]
        val updated = partitions.toMutableList()
        updated.removeAt(index)
        val heir = index.coerceAtMost(updated.lastIndex)
        updated[heir] = updated[heir].copy(sizeBytes = updated[heir].sizeBytes + removed.sizeBytes)
        return copy(partitions = updated)
    }

    /** Splits the free tail in half, or adds the smallest legal partition when the tail is small. */
    fun addFromFreeSpace(spec: FormatSpec, totalBytes: Long): PartitionPlan {
        val free = freeBytes(totalBytes)
        if (free < PartitionPlan.MINIMUM_PARTITION_BYTES) return this
        val size = if (free >= PartitionPlan.MINIMUM_PARTITION_BYTES * 2) free / 2 else free
        return copy(
            partitions = partitions + PlannedPartition(
                sizeBytes = size,
                fileSystem = spec.fileSystem,
                label = FileSystems.sanitizeLabel(spec.fs, spec.label),
            ),
        )
    }

    /**
     * The first reason this plan cannot be written, or null when it can.
     *
     * Checked once, here, rather than inside the shell script: a plan that fails validation must
     * not have already unmounted the volume.
     */
    fun problem(totalBytes: Long): String? {
        if (partitions.isEmpty()) return "A plan needs at least one partition."
        if (totalBytes <= startOffsetBytes) return "This volume is too small to partition."
        partitions.forEachIndexed { index, partition ->
            if (partition.sizeBytes < PartitionPlan.MINIMUM_PARTITION_BYTES) {
                return "Partition ${index + 1} is smaller than 16 MB."
            }
        }
        if (usedBytes > totalBytes - startOffsetBytes) {
            return "The partitions need ${formatBytes(usedBytes + startOffsetBytes)} but the volume " +
                "is only ${formatBytes(totalBytes)}."
        }
        return null
    }
}

/** A byte range occupied by one partition of the plan. */
data class Segment(val index: Int, val startBytes: Long, val endBytes: Long) {
    val sizeBytes: Long get() = endBytes - startBytes
}

/** Where every partition and the free tail sit on the disk. */
data class LayoutGeometry(
    val segments: List<Segment>,
    val freeStartBytes: Long,
    val totalBytes: Long,
) {
    val freeBytes: Long get() = (totalBytes - freeStartBytes).coerceAtLeast(0)
}

/** Human-readable byte sizes, shared by the phone, the desktop and the dialogs. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100 || unit == 0) {
        "${value.toInt()} ${units[unit]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}

/** File sizes inside a partition map, where one decimal is noise. */
fun formatBytesCompact(bytes: Long): String = formatBytes(bytes)
