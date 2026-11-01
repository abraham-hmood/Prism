package com.prism.launcher.storage

/**
 * Turns storage edits into shell commands, and turns Android's own output back into data.
 *
 * ## Why this is a pure object in :core
 *
 * Formatting a drive is destructive and cannot be tried twice. The command text is therefore built
 * by functions that take a plan and return strings, with no process, no device and no Android in
 * sight, so the exact `mkfs` line for every file system can be asserted in a JVM test. The parsers
 * are here for the same reason: `sm`, `/proc/mounts` and `parted` each have a format that is easy to
 * get subtly wrong, and each is fed a captured sample in `StorageCommandsTest`.
 *
 * ## What is deliberately absent
 *
 * No `sudo`, no `su` and no execution. This file only describes what should run; the Android side
 * decides whether it is allowed to run it and reports the outcome. Keeping that split means a
 * failing privilege check can never be mistaken for a failing command.
 */
object StorageCommands {

    /** Marker the app looks for when a probe needs to distinguish "binary missing" from "failed". */
    const val NOT_FOUND = "not found"

    // ── Formatting ──────────────────────────────────────────────────────────

    /**
     * The mkfs line for one device, or null when this file system has no tool.
     *
     * [FormatSpec.quick] maps onto each tool's own spelling rather than a Prism-invented one:
     * `-Q` for NTFS, `-E lazy_itable_init` for ext, and nothing extra for the tools that only ever
     * write metadata (`mkfs.vfat`, `mkfs.exfat`, `mkfs.btrfs`, `mkfs.xfs`, `mkfs.f2fs`). Asking for
     * a slow format on those would be a promise the tool cannot keep.
     */
    fun formatCommand(fileSystem: FileSystem, device: String, spec: FormatSpec): String? {
        val binary = fileSystem.mkfsBinary ?: return null
        val label = FileSystems.sanitizeLabel(fileSystem, spec.label)
        val unit = spec.allocationUnit
        return when (fileSystem.id) {
            FileSystems.FAT16.id -> buildString {
                append(binary).append(" -F 16 -n ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -S 512 -s ").append(unit / 512)
                append(" ").append(device)
            }
            FileSystems.FAT32.id -> buildString {
                append(binary).append(" -F 32 -n ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -S 512 -s ").append(unit / 512)
                append(" ").append(device)
            }
            FileSystems.EXFAT.id -> buildString {
                append(binary).append(" -n ").append(quote(label.ifBlank { "PRISM" }))
                // exfatprogs takes a cluster size with a unit suffix.
                if (unit > 0) append(" -c ").append(unit / 1024).append("K")
                append(" ").append(device)
            }
            FileSystems.NTFS.id -> buildString {
                append(binary).append(" -F -L ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -c ").append(unit)
                if (spec.quick) append(" -Q")
                append(" ").append(device)
            }
            FileSystems.EXT2.id, FileSystems.EXT3.id, FileSystems.EXT4.id -> buildString {
                val type = fileSystem.id
                append(binary).append(" -t ").append(type)
                append(" -F -L ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -b ").append(unit)
                if (spec.quick) append(" -E lazy_itable_init=1,nodiscard")
                append(" ").append(device)
            }
            FileSystems.F2FS.id -> buildString {
                append(binary).append(" -f -l ").append(quote(label.ifBlank { "PRISM" }))
                append(" ").append(device)
            }
            FileSystems.BTRFS.id -> buildString {
                append(binary).append(" -f -L ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -s ").append(unit).append(" -n ").append(unit)
                append(" ").append(device)
            }
            FileSystems.XFS.id -> buildString {
                append(binary).append(" -f -L ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -b size=").append(unit)
                append(" ").append(device)
            }
            FileSystems.HFSPLUS.id -> buildString {
                append(binary).append(" -v ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -b ").append(unit)
                append(" ").append(device)
            }
            FileSystems.UDF.id -> buildString {
                append(binary).append(" --media-type=hd --udfrev=0x0201")
                append(" -l ").append(quote(label.ifBlank { "PRISM" }))
                if (unit > 0) append(" -b ").append(unit)
                append(" ").append(device)
            }
            else -> null
        }
    }

    /**
     * The `parted` keyword for a partition of this file system.
     *
     * This only sets the type hint in the partition table; the actual file system comes from mkfs.
     * parted has no keyword for exFAT or F2FS, so those are written as a Linux data partition rather
     * than being refused -- the alternative would be to make two of the dropdown's entries
     * unpartitionable.
     */
    fun partedFileSystemKeyword(fileSystem: FileSystem): String = when (fileSystem.id) {
        FileSystems.FAT16.id -> "fat16"
        FileSystems.FAT32.id -> "fat32"
        FileSystems.NTFS.id -> "ntfs"
        FileSystems.EXT2.id -> "ext2"
        FileSystems.EXT3.id -> "ext3"
        FileSystems.EXT4.id -> "ext4"
        FileSystems.BTRFS.id -> "btrfs"
        FileSystems.XFS.id -> "xfs"
        FileSystems.HFSPLUS.id -> "hfs+"
        FileSystems.UDF.id -> "udf"
        else -> "ext4"
    }

    /**
     * A complete, ordered script that rewrites the partition table and formats every partition.
     *
     * ORDER MATTERS AND IS THE POINT. The volume is unmounted first (a mounted file system cannot be
     * formatted), the old table is wiped before the new one is written (parted will otherwise keep
     * stale entries), partitions are created in ascending order, and only then is each one formatted.
     * Every line ends in `|| echo PRISM-FAIL:<step>` so a caller can tell which step failed without
     * parsing tool-specific messages.
     */
    fun partitionScript(
        disk: String,
        plan: PartitionPlan,
        totalBytes: Long,
        mountPoint: String? = null,
        /** Applied to every partition, so the dialog's allocation-unit choice survives a repartition. */
        allocationUnit: Int = 0,
        quick: Boolean = true,
    ): List<String> {
        val lines = mutableListOf<String>()
        if (mountPoint != null) {
            lines += "umount ${quote(mountPoint)} 2>/dev/null"
        }
        // Two megabytes: enough to clear a GPT primary header and an MSDOS boot sector, nowhere
        // near enough to be a slow operation.
        lines += "dd if=/dev/zero of=${quote(disk)} bs=1M count=2 conv=fsync 2>/dev/null || echo PRISM-FAIL:wipe"
        lines += "parted -s ${quote(disk)} mklabel ${partitionTableFor(totalBytes)} || echo PRISM-FAIL:label"

        // EVERY BOUNDARY IS COMPUTED IN MEBIBYTES, CUMULATIVELY. Converting each partition's byte
        // range to MiB independently lets a rounded-up end overlap the next partition's rounded-down
        // start by a fraction of a megabyte, and parted then refuses the second mkpart -- on a card
        // that has already been wiped. Walking a single cursor means the ranges cannot overlap,
        // cannot leave a gap, and stay aligned.
        val mib = 1024L * 1024L
        var cursor = plan.startOffsetBytes / mib
        val available = totalBytes / mib - cursor
        val sizes = plan.partitions.map { (it.sizeBytes / mib).coerceAtLeast(16L) }.toMutableList()
        var excess = sizes.sum() - available
        for (index in sizes.lastIndex downTo 0) {
            if (excess <= 0) break
            val reducible = (sizes[index] - 16).coerceAtLeast(0)
            val taken = minOf(reducible, excess)
            sizes[index] -= taken
            excess -= taken
        }
        // A plan that fills the drive absorbs the sub-megabyte rounding into its last partition.
        if (excess < 0 && sizes.isNotEmpty()) sizes[sizes.lastIndex] -= excess

        sizes.forEachIndexed { index, sizeMiB ->
            val partition = plan.partitions[index]
            val keyword = partedFileSystemKeyword(partition.fs)
            val startMiB = cursor
            val endMiB = cursor + sizeMiB
            cursor = endMiB
            lines += "parted -s ${quote(disk)} mkpart primary $keyword ${startMiB}MiB ${endMiB}MiB " +
                "|| echo PRISM-FAIL:part${index + 1}"
        }
        // The boot flag on the first partition is what makes a BIOS look at it at all. It is a flag,
        // not a file system choice, so it is set for every plan rather than only FAT ones.
        if (plan.partitions.isNotEmpty()) {
            lines += "parted -s ${quote(disk)} set 1 boot on 2>/dev/null"
        }
        plan.partitions.forEachIndexed { index, partition ->
            val device = partitionDevice(disk, index + 1)
            val command = formatCommand(
                partition.fs,
                device,
                FormatSpec(
                    fileSystem = partition.fileSystem,
                    label = partition.label,
                    allocationUnit = allocationUnit,
                    quick = quick,
                ),
            )
            if (command != null) {
                lines += "$command || echo PRISM-FAIL:mkfs${index + 1}"
            }
        }
        lines += "sync"
        return lines
    }

    /** MBR below 2 TB, GPT above it, because MBR cannot address past that. */
    fun partitionTableFor(totalBytes: Long): String =
        if (totalBytes > 2L * 1024 * 1024 * 1024 * 1024) "gpt" else "msdos"

    /**
     * The device node for partition [number] of [disk].
     *
     * `mmcblk0` and `nvme0n1` put a `p` before the number (`mmcblk0p1`); SCSI-style names do not
     * (`sdd1`). Getting this wrong formats the whole card instead of one partition, so it is
     * derived from the name rather than guessed by trial.
     */
    fun partitionDevice(disk: String, number: Int): String =
        if (needsSeparator(disk)) "${disk}p$number" else "$disk$number"

    private fun needsSeparator(disk: String): Boolean {
        val name = disk.substringAfterLast('/')
        return name.startsWith("mmcblk") || name.startsWith("nvme") || name.startsWith("loop") ||
            name.startsWith("md") || name.startsWith("dm-")
    }

    /**
     * The whole disk a partition node belongs to.
     *
     * `sdd1` -> `sdd`, `mmcblk0p1` -> `mmcblk0`, `nvme0n1p2` -> `nvme0n1`. Returns null for a node
     * that is itself a whole disk or a mapper device, because inventing a parent for those would
     * point `parted` at something that is not the card.
     */
    fun diskDeviceOf(partitionDevice: String): String? {
        val trimmed = partitionDevice.trimEnd('/')
        val base = trimmed.substringAfterLast('/')
        // A loop or mapper node is itself a whole device; there is no card behind it to point
        // parted at.
        if (base.startsWith("loop") || base.startsWith("dm-") || base.startsWith("zram")) return null
        val candidate = when {
            base.startsWith("mmcblk") || base.startsWith("nvme") ->
                base.substringBeforeLast('p', "")
            base.matches(Regex(".*[a-zA-Z]\\d+")) -> base.replace(Regex("\\d+$"), "")
            else -> return null
        }
        if (candidate.isBlank() || candidate == base) return null
        return trimmed.substringBeforeLast('/') + "/" + candidate
    }

    // ── Burning ─────────────────────────────────────────────────────────────

    /**
     * Writes an image to the raw device.
     *
     * `conv=fsync` is not optional: without it a device removed the moment dd returns can be missing
     * whole megabytes of its tail, which is exactly how a "successful" burn produces an unbootable
     * stick. The block size of 4 MiB keeps the syscall count sane on a multi-gigabyte image.
     */
    fun rawBurnCommand(disk: String, imagePath: String): String =
        "dd if=${quote(imagePath)} of=${quote(disk)} bs=4M conv=fsync"

    /** Reads the first sector back, so a write can be verified rather than assumed. */
    fun readFirstSectorCommand(device: String): String =
        "dd if=${quote(device)} bs=512 count=1 2>/dev/null"

    /**
     * True when the first sector ends in the `0x55 0xAA` boot signature.
     *
     * Both MBR-partitioned images and ISO9660 volumes carry it, so it separates "the write reached
     * the device" from "dd returned 0 and wrote nothing", which is the failure a user cannot see.
     */
    fun hasBootSignature(firstSector: ByteArray): Boolean =
        firstSector.size >= 512 &&
            firstSector[510] == 0x55.toByte() &&
            firstSector[511] == 0xAA.toByte()

    // ── Unmounting / querying ───────────────────────────────────────────────

    /** `sm unmount <id>`; `sm` is Android's own volume daemon client and exists on every device. */
    fun unmountCommand(volumeId: String): String = "sm unmount ${quote(volumeId)}"

    /** Lists every volume `sm` knows about, mounted or not. */
    fun listVolumesCommand(): String = "sm list-volumes all"

    /** Lists the physical disks `sm` can partition. */
    fun listDisksCommand(): String = "sm list-disks"

    /**
     * Parses `sm list-volumes all`.
     *
     * Lines are `<id> <state> [nickname]`, for example `public:179,65 mounted 1234-5678`. The
     * nickname is absent for some states, so it is optional rather than a reason to drop the line.
     */
    fun parseSmVolumes(output: String): List<SmVolume> = output.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val id = parts[0]
            if (!id.contains(':')) return@mapNotNull null
            SmVolume(id = id, state = parts[1], nickname = parts.getOrNull(2).orEmpty())
        }
        .toList()

    /**
     * Parses `/proc/mounts`.
     *
     * Octal escapes (`\040` for a space) are decoded because removable volume labels really do
     * contain spaces, and a mount point that does not compare equal is a volume the app will claim
     * is not mounted.
     */
    fun parseProcMounts(text: String): List<MountEntry> = text.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            MountEntry(
                device = unescapeMountField(parts[0]),
                mountPoint = unescapeMountField(parts[1]),
                fileSystem = parts[2],
            )
        }
        .toList()

    private fun unescapeMountField(raw: String): String =
        raw.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")

    /**
     * Parses `parted -s <disk> unit B print`.
     *
     * Only the numbered rows are read, and only when the unit is bytes: a locale that prints
     * `1.00GB` would otherwise be silently treated as zero-sized. When the output is in another
     * unit the caller gets no partitions and falls back to the whole-volume plan, which is the safe
     * direction to be wrong in.
     */
    fun parsePartedBytes(output: String): List<RawPartition> {
        val row = Regex("^\\s*(\\d+)\\s+(\\d+)B\\s+(\\d+)B\\s+(\\d+)B\\s+(\\S+)\\s*(.*)$")
        return output.lineSequence().mapNotNull { line ->
            val match = row.find(line) ?: return@mapNotNull null
            val (number, start, end, size, type, tail) = match.destructured
            val tokens = tail.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // `primary fat32 lba` -- the file system is the token that is not a flag.
            val fileSystem = tokens.firstOrNull { it in KNOWN_FS_NAMES }.orEmpty()
            val label = tokens.drop(1)
                .firstOrNull { it !in KNOWN_FLAGS && it !in KNOWN_FS_NAMES }
                .orEmpty()
            RawPartition(
                number = number.toIntOrNull() ?: return@mapNotNull null,
                startBytes = start.toLongOrNull() ?: return@mapNotNull null,
                endBytes = end.toLongOrNull() ?: return@mapNotNull null,
                sizeBytes = size.toLongOrNull() ?: return@mapNotNull null,
                type = type,
                fileSystem = fileSystem,
                label = label,
            )
        }.toList()
    }

    private val KNOWN_FS_NAMES = setOf(
        "fat16", "fat32", "ntfs", "exfat", "ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs",
        "hfs", "hfs+", "udf", "iso9660", "linux-swap", "unknown", "apfs",
    )

    private val KNOWN_FLAGS = setOf(
        "boot", "lba", "legacy_boot", "esp", "hidden", "raid", "diag", "msftres", "bios_grub",
        "primary", "logical", "extended",
    )

    /** Maps a `parted` file-system name back onto a dropdown entry, or null when unknown. */
    fun fileSystemIdForPartedName(name: String): String? = when (name.lowercase()) {
        "fat16" -> FileSystems.FAT16.id
        "fat32" -> FileSystems.FAT32.id
        "ntfs" -> FileSystems.NTFS.id
        "exfat" -> FileSystems.EXFAT.id
        "ext2" -> FileSystems.EXT2.id
        "ext3" -> FileSystems.EXT3.id
        "ext4" -> FileSystems.EXT4.id
        "btrfs" -> FileSystems.BTRFS.id
        "xfs" -> FileSystems.XFS.id
        "f2fs" -> FileSystems.F2FS.id
        "hfs", "hfs+" -> FileSystems.HFSPLUS.id
        "udf" -> FileSystems.UDF.id
        else -> null
    }

    /** Single-quotes a path or label for the shell. Android paths do contain spaces. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * True when the plan is just "format the volume that is already there".
     *
     * The distinction matters because a single full-volume partition needs no `parted` at all --
     * writing mkfs straight to the existing partition node works with fewer privileges, cannot
     * destroy a neighbouring partition on the same card, and is what the Format button means to
     * almost everybody. Only a real repartition needs the full script.
     */
    fun isSimpleFormat(plan: PartitionPlan, totalBytes: Long): Boolean {
        if (plan.partitions.size != 1) return false
        val usable = totalBytes - plan.startOffsetBytes
        // A megabyte of slack: partition tables and `parted`'s own rounding mean the existing
        // partition is rarely byte-identical to the computed size.
        return plan.partitions.first().sizeBytes >= usable - 1024 * 1024
    }
}

/** One line of `sm list-volumes`. */
data class SmVolume(val id: String, val state: String, val nickname: String)

/** One line of `/proc/mounts`. */
data class MountEntry(val device: String, val mountPoint: String, val fileSystem: String)

/** One numbered row of `parted print`. */
data class RawPartition(
    val number: Int,
    val startBytes: Long,
    val endBytes: Long,
    val sizeBytes: Long,
    val type: String,
    val fileSystem: String,
    val label: String,
)
