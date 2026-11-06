package com.prism.launcher.storage.drive

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The file system readers against real images (see resources/drive/README.md for where each came
 * from). Every reader is checked the same way a user would use it: list the root, walk into a
 * folder, open a file and compare its bytes.
 */
class DriveFileSystemTest {

    private fun image(name: String): ByteArray =
        GZIPInputStream(javaClass.getResourceAsStream("/drive/$name")!!).use { it.readBytes() }

    private fun open(name: String): DriveFileSystem =
        assertIs<DriveFileSystems.Opened.Ready>(DriveFileSystems.open(ByteArrayBlockDevice(image(name)))).fileSystem

    private fun DriveFileSystem.text(path: String) = open(path).use { String(it.readBytes()) }

    private val pattern = ByteArray(300_000) { ((it * 7 + 3) % 251).toByte() }

    private fun checkFat(name: String, type: String, label: String) {
        val fs = open(name)
        assertEquals(type, fs.typeId)
        assertEquals(label, fs.label)
        val root = fs.list("/").associateBy { it.name }
        assertTrue(root.keys.containsAll(listOf("hello.txt", "MixedCase.Txt", "Documents", "many")), "root: ${root.keys}")
        assertEquals("hello fat\n", fs.text("/hello.txt"))
        assertEquals("long name\n", fs.text("/Documents/A long file name with spaces.txt"))
        assertContentEquals(pattern, fs.open("/Documents/Deep Folder/pattern.bin").use { it.readBytes() })
        assertEquals(300_000, fs.stat("/Documents/Deep Folder/pattern.bin")?.size)
        assertEquals(40, fs.list("/many").size)
        assertEquals("17", fs.text("/many/file-017.txt"))
        // Reading from the middle of a cluster must land on the right byte.
        fs.open("/Documents/Deep Folder/pattern.bin").use { stream ->
            stream.skip(4097)
            assertEquals(pattern[4097], stream.read().toByte())
        }
    }

    @Test fun `FAT12 reads`() = checkFat("fat12.img.gz", "fat12", "PRISMF12")
    @Test fun `FAT16 reads`() = checkFat("fat16.img.gz", "fat16", "PRISMF16")
    @Test fun `FAT32 reads`() = checkFat("fat32.img.gz", "fat32", "PRISMF32")

    @Test
    fun `ext4 reads, including hashed directories, holes and symlinks`() {
        val fs = open("ext4-view-disk1.img.gz")
        assertEquals("ext4", fs.typeId)
        val root = fs.list("/").associateBy { it.name }
        assertTrue(root.keys.containsAll(listOf("empty_file", "empty_dir", "small_file", "dir1", "medium_dir", "big_dir", "holes")), "root: ${root.keys}")
        assertEquals("hello, world!", fs.text("/small_file"))
        assertEquals(0, fs.open("/empty_file").use { it.readBytes() }.size)
        assertTrue(fs.list("/empty_dir").isEmpty())
        // htree depth 0 and depth 1.
        val medium = fs.list("/medium_dir")
        assertEquals(1_000, medium.size)
        assertEquals("517", fs.text("/medium_dir/517"))
        val big = fs.list("/big_dir")
        assertEquals(10_000, big.size)
        assertEquals("9999", fs.text("/big_dir/9999"))
        // Holes read as zeros between the data blocks.
        val holes = fs.open("/holes").use { it.readBytes() }
        val expected = ByteArray(1024 * 10).also { data ->
            data.fill(0xa1.toByte(), 2048, 3072); data.fill(0xa2.toByte(), 3072, 4096)
            data.fill(0xa3.toByte(), 6144, 7168); data.fill(0xa4.toByte(), 7168, 8192)
        }
        assertContentEquals(expected, holes)
        assertTrue(root.getValue("sym_simple").isSymlink)
        assertEquals("small_file", fs.text("/sym_simple"))
        assertTrue(fs.list("/dir1").any { it.name == "dir2" && it.isDirectory })
    }

    @Test
    fun `NTFS reads, including resident, sparse and indexed directories`() {
        val fs = open("ntfs-testfs1.img.gz")
        assertEquals("ntfs", fs.typeId)
        assertEquals("mylabel", fs.label)
        val root = fs.list("/").associateBy { it.name }
        assertTrue(root.keys.containsAll(listOf("empty-file", "file-with-12345", "1000-bytes-file", "sparse-file", "many_subdirs")), "root: ${root.keys}")
        assertTrue(root.keys.none { it.startsWith("$") }, "system files must be hidden: ${root.keys}")
        assertEquals("12345", fs.text("/file-with-12345"))
        assertEquals("12345".repeat(200), fs.text("/1000-bytes-file"))
        assertEquals(0, fs.open("/empty-file").use { it.readBytes() }.size)
        val sparse = fs.open("/sparse-file").use { it.readBytes() }
        assertEquals(500_005, sparse.size)
        assertEquals("12345", String(sparse, 0, 5))
        assertEquals("11111", String(sparse, 500_000, 5))
        assertTrue(sparse.copyOfRange(5, 500_000).all { it == 0.toByte() })
        // 512 subdirectories need $INDEX_ALLOCATION, so this walks the B-tree.
        val many = fs.list("/many_subdirs")
        assertEquals(512, many.size)
        assertEquals((1..512).map { it.toString() }.toSet(), many.map { it.name }.toSet())
        assertTrue(many.all { it.isDirectory })
        // 2021-01-01 13:37 in the image's local time; any day-accurate answer proves the conversion.
        val modified = root.getValue("empty-file").modified
        assertTrue(modified in 1_609_400_000_000L..1_609_600_000_000L, "modified $modified")
    }

    @Test
    fun `exFAT reads`() {
        val fs = open("go-exfat-test.exfat.gz")
        assertEquals("exfat", fs.typeId)
        val root = fs.list("/")
        assertTrue(root.isNotEmpty(), "the exFAT root should list something")
        assertEquals("testvolumel", fs.label) // exFAT keeps 11 characters of "testvolumelabel".
        val names = root.associateBy { it.name }
        assertTrue(names.keys.containsAll(listOf("testdirectory", "testdirectory2", "testdirectory3")), "root: ${names.keys}")
        val photo = fs.open("/2-delahaye-type-165-cabriolet-dsc_8025.jpg").use { it.readBytes() }
        assertEquals(313_299, photo.size)
        // A JPEG read back intact starts with SOI and ends with EOI.
        assertEquals(listOf(0xFF, 0xD8), photo.take(2).map { it.toInt() and 0xFF })
        assertEquals(listOf(0xFF, 0xD9), photo.takeLast(2).map { it.toInt() and 0xFF })
        // Every file in the tree opens and yields exactly its recorded size.
        fun walk(path: String, depth: Int) {
            for (entry in fs.list(path)) {
                if (entry.isDirectory) {
                    if (depth < 6) walk(entry.path, depth + 1)
                } else {
                    val bytes = fs.open(entry.path).use { it.readBytes() }
                    assertEquals(entry.size, bytes.size.toLong(), entry.path)
                }
            }
        }
        walk("/", 0)
    }

    @Test
    fun `partition tables are read, and each partition is detected`() {
        val fat = image("fat12.img.gz")
        val ntfs = image("ntfs-testfs1.img.gz")
        val sector = 512
        // MBR: FAT12 at 1 MiB, NTFS straight after it.
        val first = 2048L
        val second = first + fat.size / sector
        val disk = ByteArray(((second + ntfs.size / sector + 2048) * sector).toInt())
        fun entry(slot: Int, type: Int, start: Long, count: Long) {
            val at = 446 + slot * 16
            disk[at + 4] = type.toByte()
            for (i in 0 until 4) {
                disk[at + 8 + i] = (start shr (8 * i)).toByte()
                disk[at + 12 + i] = (count shr (8 * i)).toByte()
            }
        }
        entry(0, 0x01, first, fat.size / sector.toLong())
        entry(1, 0x07, second, ntfs.size / sector.toLong())
        disk[510] = 0x55; disk[511] = 0xAA.toByte()
        System.arraycopy(fat, 0, disk, (first * sector).toInt(), fat.size)
        System.arraycopy(ntfs, 0, disk, (second * sector).toInt(), ntfs.size)

        val device = ByteArrayBlockDevice(disk)
        val partitions = PartitionTable.read(device)
        assertEquals(2, partitions.size)
        assertEquals(listOf("MBR", "MBR"), partitions.map { it.scheme })
        val found = partitions.map { FileSystemProbe.identify(SliceBlockDevice(device, it.offset, it.size))?.id }
        assertEquals(listOf("fat12", "ntfs"), found)
        val ntfsFs = assertIs<DriveFileSystems.Opened.Ready>(
            DriveFileSystems.open(SliceBlockDevice(device, partitions[1].offset, partitions[1].size)),
        ).fileSystem
        assertEquals("12345", ntfsFs.open("/file-with-12345").use { String(it.readBytes()) })

        // A stick with no table at all: the file system starts at byte 0.
        val bare = PartitionTable.read(ByteArrayBlockDevice(fat))
        assertEquals("none", bare.single().scheme)
    }

    @Test
    fun `every file system Prism formats is recognised by its signature`() {
        fun probe(bytes: ByteArray) = FileSystemProbe.identify(ByteArrayBlockDevice(bytes))?.id
        val blank = { ByteArray(128 * 1024) }
        assertEquals("xfs", probe(blank().also { "XFSB".toByteArray().copyInto(it, 0) }))
        assertEquals("btrfs", probe(blank().also { "_BHRfS_M".toByteArray().copyInto(it, 0x10040) }))
        assertEquals("f2fs", probe(blank().also { byteArrayOf(0x10, 0x20, 0xF5.toByte(), 0xF2.toByte()).copyInto(it, 1024) }))
        assertEquals("hfsplus", probe(blank().also { "H+".toByteArray().copyInto(it, 1024) }))
        assertEquals("udf", probe(blank().also { "BEA01".toByteArray().copyInto(it, 32769); "NSR02".toByteArray().copyInto(it, 34817) }))
        assertEquals("iso9660", probe(blank().also { "CD001".toByteArray().copyInto(it, 32769) }))
        assertEquals("exfat", probe(blank().also { "EXFAT   ".toByteArray().copyInto(it, 3) }))
        assertNotNull(FileSystemProbe.identify(ByteArrayBlockDevice(image("ext4-view-disk1.img.gz"))))
        assertEquals(null, probe(blank()))
    }
}
