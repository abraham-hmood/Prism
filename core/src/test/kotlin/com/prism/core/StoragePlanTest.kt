package com.prism.core

import com.prism.launcher.storage.FileSystems
import com.prism.launcher.storage.FormatSpec
import com.prism.launcher.storage.PartitionPlan
import com.prism.launcher.storage.PlannedPartition
import com.prism.launcher.storage.formatBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The partition arithmetic the Format dialog drives.
 *
 * These are the assertions that matter before a destructive command runs: a plan whose sizes do not
 * add up, or that a drag can push below the legal minimum, must be impossible to construct rather
 * than caught by parted halfway through rewriting a card.
 */
class StoragePlanTest {

    private val total = 64L * 1024 * 1024 * 1024 // 64 GB stick

    @Test
    fun wholeVolumeFillsEverythingAfterTheTable() {
        val plan = PartitionPlan.wholeVolume(total, FormatSpec(fileSystem = FileSystems.EXFAT.id, label = "STICK"))
        assertEquals(1, plan.partitions.size)
        assertEquals(total - PartitionPlan.ALIGNMENT_BYTES, plan.partitions.first().sizeBytes)
        assertEquals(0, plan.freeBytes(total))
        assertNull(plan.problem(total))

        val geometry = plan.geometry(total)
        assertEquals(PartitionPlan.ALIGNMENT_BYTES, geometry.segments.first().startBytes)
        assertEquals(total, geometry.segments.first().endBytes)
        assertEquals(0, geometry.freeBytes)
    }

    @Test
    fun resizeNeverPushesAPartitionBelowTheMinimum() {
        val plan = PartitionPlan(
            listOf(
                PlannedPartition(32L * 1024 * 1024 * 1024, FileSystems.EXFAT.id),
                PlannedPartition(32L * 1024 * 1024 * 1024 - PartitionPlan.ALIGNMENT_BYTES, FileSystems.FAT32.id),
            ),
        )
        val shrunk = plan.resize(0, 1, total)
        assertEquals(PartitionPlan.MINIMUM_PARTITION_BYTES, shrunk.partitions[0].sizeBytes)
        // And the neighbour absorbed exactly the difference: the plan still fills the volume.
        assertEquals(plan.usedBytes, shrunk.usedBytes)
    }

    @Test
    fun growingTheLastPartitionTakesFromFreeSpace() {
        val plan = PartitionPlan(
            listOf(PlannedPartition(16L * 1024 * 1024 * 1024, FileSystems.FAT32.id)),
        )
        val free = plan.freeBytes(total)
        assertTrue(free > 0)
        val grown = plan.resize(0, plan.partitions.first().sizeBytes + free / 2, total)
        assertEquals(plan.partitions.first().sizeBytes + free / 2, grown.partitions.first().sizeBytes)
        assertEquals(free - free / 2, grown.freeBytes(total))
    }

    @Test
    fun removingAPartitionGivesItsSpaceToTheNextOne() {
        val plan = PartitionPlan(
            listOf(
                PlannedPartition(8L * 1024 * 1024 * 1024, FileSystems.EXFAT.id, "A"),
                PlannedPartition(8L * 1024 * 1024 * 1024, FileSystems.EXFAT.id, "B"),
            ),
        )
        val removed = plan.remove(0)
        assertEquals(1, removed.partitions.size)
        assertEquals("B", removed.partitions.first().label)
        assertEquals(16L * 1024 * 1024 * 1024, removed.partitions.first().sizeBytes)
    }

    @Test
    fun theLastPartitionCannotBeRemoved() {
        val plan = PartitionPlan(listOf(PlannedPartition(1024L * 1024 * 1024, FileSystems.FAT32.id)))
        assertEquals(1, plan.remove(0).partitions.size)
    }

    @Test
    fun addingSplitsTheFreeTail() {
        val plan = PartitionPlan(listOf(PlannedPartition(4L * 1024 * 1024 * 1024, FileSystems.FAT32.id)))
        val free = plan.freeBytes(total)
        val added = plan.addFromFreeSpace(FormatSpec(fileSystem = FileSystems.EXT4.id, label = "data"), total)
        assertEquals(2, added.partitions.size)
        assertEquals(free / 2, added.partitions.last().sizeBytes)
        assertEquals(FileSystems.EXT4.id, added.partitions.last().fileSystem)
        assertNull(added.problem(total))
    }

    @Test
    fun addingWithNoRoomChangesNothing() {
        val plan = PartitionPlan.wholeVolume(total, FormatSpec())
        assertEquals(1, plan.addFromFreeSpace(FormatSpec(), total).partitions.size)
    }

    @Test
    fun aPlanLargerThanTheVolumeIsRejectedWithTheNumbers() {
        val plan = PartitionPlan(
            listOf(PlannedPartition(total, FileSystems.FAT32.id)),
        )
        val problem = plan.problem(total)
        assertNotNull(problem)
        assertTrue(problem.contains("only"))
    }

    @Test
    fun anEmptyOrTinyPlanIsRejected() {
        assertNotNull(PartitionPlan(emptyList()).problem(total))
        assertNotNull(
            PartitionPlan(listOf(PlannedPartition(1024L * 1024, FileSystems.FAT32.id))).problem(total),
        )
        assertNotNull(PartitionPlan(emptyList()).problem(1024))
    }

    @Test
    fun reformatKeepsTheSizeAndSwapsTheFileSystem() {
        val plan = PartitionPlan(listOf(PlannedPartition(1024L * 1024 * 1024, FileSystems.FAT32.id, "OLD")))
        val changed = plan.reformat(0, FileSystems.NTFS.id, "NEW")
        assertEquals(1024L * 1024 * 1024, changed.partitions.first().sizeBytes)
        assertEquals(FileSystems.NTFS.id, changed.partitions.first().fileSystem)
        assertEquals("NEW", changed.partitions.first().label)
    }

    @Test
    fun thereAreAtLeastTenFileSystemsToChooseFrom() {
        assertTrue(FileSystems.all.size >= 10, "the format dropdown needs at least ten entries")
        assertEquals(FileSystems.all.size, FileSystems.all.map { it.id }.distinct().size)
        // Every entry must name a tool, or the dialog would offer something it can never create.
        assertTrue(FileSystems.all.all { it.mkfsBinary != null })
    }

    @Test
    fun fatLabelsAreUppercasedAndStripped() {
        val label = FileSystems.sanitizeLabel(FileSystems.FAT32, "my*stick!")
        assertEquals("MY STICK", label)
        assertEquals(11, FileSystems.sanitizeLabel(FileSystems.FAT32, "A VERY LONG LABEL INDEED").length)
    }

    @Test
    fun sizesReadTheWayAPersonWouldSayThem() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
