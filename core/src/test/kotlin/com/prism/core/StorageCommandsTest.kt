package com.prism.core

import com.prism.launcher.storage.FileSystems
import com.prism.launcher.storage.FormatSpec
import com.prism.launcher.storage.PartitionPlan
import com.prism.launcher.storage.PlannedPartition
import com.prism.launcher.storage.StorageCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exact text that goes to a root shell, and the exact text that comes back.
 *
 * Formatting cannot be retried against a real card, so the command for every file system and the
 * device-name arithmetic that decides *which* partition is formatted are pinned here.
 */
class StorageCommandsTest {

    @Test
    fun fat32CommandCarriesLabelAndClusterSize() {
        val spec = FormatSpec(fileSystem = FileSystems.FAT32.id, label = "prism", allocationUnit = 4096)
        assertEquals(
            "mkfs.vfat -F 32 -n 'PRISM' -S 512 -s 8 /dev/block/sdd1",
            StorageCommands.formatCommand(FileSystems.FAT32, "/dev/block/sdd1", spec),
        )
    }

    @Test
    fun ext4QuickAndThoroughFormatsDiffer() {
        val quick = FormatSpec(fileSystem = FileSystems.EXT4.id, label = "DATA", quick = true)
        val thorough = quick.copy(quick = false)
        val quickCommand = StorageCommands.formatCommand(FileSystems.EXT4, "/dev/block/sdd1", quick)!!
        val slowCommand = StorageCommands.formatCommand(FileSystems.EXT4, "/dev/block/sdd1", thorough)!!
        assertTrue(quickCommand.contains("lazy_itable_init=1,nodiscard"))
        assertFalse(slowCommand.contains("lazy_itable_init"))
        assertTrue(slowCommand.contains("-t ext4"))
    }

    @Test
    fun ntfsQuietFlagFollowsTheQuickChoice() {
        val spec = FormatSpec(fileSystem = FileSystems.NTFS.id, label = "WIN", quick = true)
        assertTrue(StorageCommands.formatCommand(FileSystems.NTFS, "/dev/x", spec)!!.contains(" -Q "))
        assertFalse(
            StorageCommands.formatCommand(FileSystems.NTFS, "/dev/x", spec.copy(quick = false))!!
                .contains(" -Q "),
        )
    }

    @Test
    fun everyFileSystemProducesACommand() {
        for (fileSystem in FileSystems.all) {
            val command = StorageCommands.formatCommand(
                fileSystem, "/dev/block/sdd1", FormatSpec(fileSystem = fileSystem.id, label = "PRISM"),
            )
            assertTrue(command != null && command.contains("/dev/block/sdd1"), fileSystem.id)
        }
    }

    @Test
    fun labelsAreQuotedSoASpaceIsNotTwoArguments() {
        val command = StorageCommands.formatCommand(
            FileSystems.EXFAT,
            "/dev/block/sdd1",
            FormatSpec(fileSystem = FileSystems.EXFAT.id, label = "my stick"),
        )!!
        assertTrue(command.contains("-n 'MY STICK'"), command)
    }

    @Test
    fun partitionNodesFollowTheDeviceNamingRule() {
        assertEquals("/dev/block/sdd1", StorageCommands.partitionDevice("/dev/block/sdd", 1))
        assertEquals("/dev/block/sdd12", StorageCommands.partitionDevice("/dev/block/sdd", 12))
        assertEquals("/dev/block/mmcblk0p1", StorageCommands.partitionDevice("/dev/block/mmcblk0", 1))
        assertEquals("/dev/block/nvme0n1p3", StorageCommands.partitionDevice("/dev/block/nvme0n1", 3))
    }

    @Test
    fun theParentDiskIsDerivedNotGuessed() {
        assertEquals("/dev/block/sdd", StorageCommands.diskDeviceOf("/dev/block/sdd1"))
        assertEquals("/dev/block/mmcblk0", StorageCommands.diskDeviceOf("/dev/block/mmcblk0p1"))
        assertEquals("/dev/block/nvme0n1", StorageCommands.diskDeviceOf("/dev/block/nvme0n1p2"))
        assertNull(StorageCommands.diskDeviceOf("/dev/block/loop0"))
        assertNull(StorageCommands.diskDeviceOf("/dev/block/dm-5"))
        assertNull(StorageCommands.diskDeviceOf("/dev/block/sdd"))
    }

    @Test
    fun partitionScriptUnmountsWipesLabelsCreatesFormatsAndSyncsInThatOrder() {
        val plan = PartitionPlan(
            listOf(
                PlannedPartition(8L * 1024 * 1024 * 1024, FileSystems.FAT32.id, "BOOT"),
                PlannedPartition(8L * 1024 * 1024 * 1024, FileSystems.EXT4.id, "ROOT"),
            ),
        )
        val total = 32L * 1024 * 1024 * 1024
        val script = StorageCommands.partitionScript(
            disk = "/dev/block/sdd",
            plan = plan,
            totalBytes = total,
            mountPoint = "/mnt/media_rw/1234",
        )
        assertEquals("umount '/mnt/media_rw/1234' 2>/dev/null", script.first())
        val joined = script.joinToString("\n")
        assertTrue(joined.contains("dd if=/dev/zero of='/dev/block/sdd'"))
        assertTrue(joined.contains("parted -s '/dev/block/sdd' mklabel msdos"))
        // 1 MiB of table offset plus 8 GiB is where the first partition ends, not 8 GiB.
        assertTrue(joined.contains("parted -s '/dev/block/sdd' mkpart primary fat32 1MiB 8193MiB"), joined)
        assertTrue(joined.contains("parted -s '/dev/block/sdd' set 1 boot on"))
        assertTrue(joined.contains("mkfs.vfat -F 32 -n 'BOOT'"))
        assertTrue(joined.contains("mke2fs -t ext4 -F -L 'ROOT'"))
        assertTrue(joined.contains("PRISM-FAIL:mkfs2"))
        assertEquals("sync", script.last())
    }

    @Test
    fun roundedPartitionBoundariesStillTouchAndNeverOverlap() {
        val plan = PartitionPlan(
            listOf(
                PlannedPartition(1000L * 1024 * 1024 + 12345, FileSystems.FAT32.id),
                PlannedPartition(2000L * 1024 * 1024 + 999999, FileSystems.EXT4.id),
                PlannedPartition(3000L * 1024 * 1024 + 7, FileSystems.EXFAT.id),
            ),
        )
        val total = 16L * 1024 * 1024 * 1024
        val script = StorageCommands.partitionScript("/dev/block/sdd", plan, total)
        val ranges = script.mapNotNull { line ->
            Regex("mkpart primary \\S+ (\\d+)MiB (\\d+)MiB").find(line)?.let {
                it.groupValues[1].toLong() to it.groupValues[2].toLong()
            }
        }
        assertEquals(3, ranges.size)
        // The first partition starts after the 1 MiB table offset, not at zero.
        assertEquals(1L, ranges.first().first)
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.second, right.first, "a boundary was left as a gap or an overlap")
        }
        assertTrue(ranges.last().second <= total / (1024 * 1024))
    }

    @Test
    fun aDiskOverTwoTerabytesGetsGpt() {
        assertEquals("msdos", StorageCommands.partitionTableFor(64L * 1024 * 1024 * 1024))
        assertEquals("gpt", StorageCommands.partitionTableFor(3L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun aSingleFullVolumePartitionIsJustAFormat() {
        val plan = PartitionPlan.wholeVolume(16L * 1024 * 1024 * 1024, FormatSpec())
        assertTrue(StorageCommands.isSimpleFormat(plan, 16L * 1024 * 1024 * 1024))
        assertFalse(StorageCommands.isSimpleFormat(plan.copy(partitions = listOf(
            plan.partitions.first(),
            plan.partitions.first().copy(sizeBytes = 1024L * 1024 * 1024),
        )), 16L * 1024 * 1024 * 1024))
    }

    @Test
    fun smVolumesExposeIdStateAndNickname() {
        val parsed = StorageCommands.parseSmVolumes(
            """
            public:179,65 mounted 1234-5678
            public:179,66 unmounted
            emulated;0 mounted

            """.trimIndent(),
        )
        assertEquals(2, parsed.size)
        assertEquals("public:179,65", parsed[0].id)
        assertEquals("mounted", parsed[0].state)
        assertEquals("1234-5678", parsed[0].nickname)
        assertEquals("unmounted", parsed[1].state)
    }

    @Test
    fun mountPointsWithEscapedSpacesAreDecoded() {
        val mounts = StorageCommands.parseProcMounts(
            """
            /dev/block/vold/public:179,65 /storage/1234-5678 vfat rw,nosuid 0 0
            /dev/block/vold/public:179,65 /storage/My\040Stick vfat rw 0 0
            /dev/block/dm-5 /data ext4 rw 0 0
            """.trimIndent(),
        )
        assertEquals(3, mounts.size)
        assertEquals("/storage/My Stick", mounts[1].mountPoint)
        assertEquals("vfat", mounts[1].fileSystem)
    }

    @Test
    fun partedRowsGiveByteRangesAndFileSystem() {
        val raw = """
            Model: USB DISK (scsi)
            Disk /dev/block/sdd: 15931539456B
            Sector size (logical/physical): 512B/512B
            Partition Table: msdos
            Disk Flags:

            Number  Start       End            Size           Type     File system  Flags
             1      1048576B    15930482688B   15929434112B   primary  fat32        lba
        """.trimIndent()
        val partitions = StorageCommands.parsePartedBytes(raw)
        assertEquals(1, partitions.size)
        assertEquals(1, partitions.first().number)
        assertEquals(1048576, partitions.first().startBytes)
        assertEquals(15930482688, partitions.first().endBytes)
        assertEquals("fat32", partitions.first().fileSystem)
        assertEquals("", partitions.first().label)
        assertEquals(FileSystems.FAT32.id, StorageCommands.fileSystemIdForPartedName("fat32"))
    }

    @Test
    fun only512ByteSectorsWithTheSignatureCountAsWritten() {
        val sector = ByteArray(512)
        assertFalse(StorageCommands.hasBootSignature(sector))
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        assertTrue(StorageCommands.hasBootSignature(sector))
        assertFalse(StorageCommands.hasBootSignature(ByteArray(256)))
    }

    @Test
    fun pathsAndLabelsAreSingleQuoted() {
        assertEquals("'a b'", StorageCommands.quote("a b"))
        assertEquals("'it'\\''s'", StorageCommands.quote("it's"))
    }
}
