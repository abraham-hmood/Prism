package com.prism.launcher.storage.compress

import com.prism.launcher.storage.remote.FileDriveAccess
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveCompressionTest {

    private val root: File = Files.createTempDirectory("prism-drive-compress").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val text = buildString { repeat(6_000) { append("Prism drive compression line $it, the same words again\n") } }.toByteArray()

    private fun samples(): List<ByteArray> {
        val random = Random(7)
        return listOf(
            byteArrayOf(42),
            ByteArray(4095) { (it % 7).toByte() },
            ByteArray(4096) { 0 },
            ByteArray(4097) { (it / 300).toByte() },
            random.nextBytes(10_000),
            text.copyOf(70_000),
            ByteArray(65_536) { if (it % 1000 < 3) random.nextInt().toByte() else 'a'.code.toByte() },
        )
    }

    @Test
    fun `LZNT1 round-trips every shape of data and shrinks text`() {
        samples().forEach { data ->
            val packed = Lznt1.compress(data)
            assertContentEquals(data, Lznt1.decompress(packed, data.size), "${data.size} bytes")
        }
        assertTrue(Lznt1.compress(text).size < text.size / 3, "text should compress well")
    }

    @Test
    fun `at least twenty codecs, distinct, and every one round-trips a unit`() {
        val codecs = BlockCodecs.standard
        assertTrue(codecs.size >= 20, "${codecs.size} codecs")
        assertEquals(codecs.size, codecs.map { it.id }.distinct().size)
        assertEquals("lznt1", codecs.first().id, "LZNT1 is the default, as on NTFS")
        val unit = text.copyOf(CompressedFile.DEFAULT_UNIT)
        codecs.forEach { codec ->
            val packed = codec.encode(unit, 0, unit.size)
            assertContentEquals(unit, codec.decode(packed, unit.size), codec.label)
            // Run-length coding only helps flat data, which text is not.
            if (codec.id != "rle") assertTrue(packed.size < unit.size, "${codec.label} should shrink text")
        }
    }

    @Test
    fun `a file is compressed in place, keeps its name and time, and reads back from any offset`() {
        val file = File(root, "notes.txt").apply { writeBytes(text) }
        file.setLastModified(1_700_000_000_000L)
        val codec = BlockCodecs.byId("lznt1")!!
        val saved = CompressedFile.compress(file, codec)
        assertTrue(saved > 0)
        assertTrue(file.isFile, "still the same file")
        assertEquals(1, root.listFiles()!!.size, "nothing beside it -- not an archive")
        assertEquals(1_700_000_000_000L, file.lastModified())
        assertTrue(file.length() < text.size)
        assertEquals(text.size.toLong(), CompressedFile.logicalLength(file))
        CompressedFile.open(file).use { assertContentEquals(text, it.readBytes()) }
        listOf(0L, 1L, 65_535L, 65_536L, 100_000L, text.size - 1L).forEach { offset ->
            CompressedFile.open(file, offset).use {
                assertContentEquals(text.copyOfRange(offset.toInt(), text.size), it.readBytes(), "from $offset")
            }
        }
        assertTrue(CompressedFile.decompress(file))
        assertContentEquals(text, file.readBytes())
        assertEquals(1_700_000_000_000L, file.lastModified())
    }

    @Test
    fun `a file that will not shrink is left exactly as it was, and zeros cost nothing`() {
        val random = Random(3).nextBytes(200_000)
        val noise = File(root, "noise.bin").apply { writeBytes(random) }
        assertEquals(0L, CompressedFile.compress(noise, BlockCodecs.standard.first()))
        assertContentEquals(random, noise.readBytes())
        assertFalse(CompressedFile.isCompressed(noise))

        val zeros = File(root, "disk.img").apply { writeBytes(ByteArray(1_000_000)) }
        CompressedFile.compress(zeros, BlockCodecs.standard.first())
        assertTrue(zeros.length() < 1_000, "an empty unit is stored as nothing (${zeros.length()} bytes)")
        CompressedFile.open(zeros).use { assertContentEquals(ByteArray(1_000_000), it.readBytes()) }
    }

    @Test
    fun `compressing a drive is transparent to everything reading through Prism, and decompressing undoes it`() {
        val originals = mapOf(
            "Documents/report.txt" to text,
            "Documents/deep/nested/log.txt" to text.copyOf(150_000),
            "Download/data.csv" to buildString { repeat(20_000) { append("$it,${it * 2},${it % 13}\n") } }.toByteArray(),
            "Pictures/photo.raw" to text.copyOf(90_000),
            "small.txt" to "tiny".toByteArray(),
        )
        originals.forEach { (path, bytes) -> File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) } }
        val before = DriveCompression.excluded
        DriveCompression.excluded = { drive, file -> file.relativeTo(drive).path.startsWith("Pictures") }
        try {
            val method = DriveCompression.PrismMethod(BlockCodecs.byId("deflate-6")!!)
            val progress = ArrayList<DriveCompression.Progress>()
            val result = DriveCompression.run(root, method, compressing = true) { progress += it }
            assertFalse(result.stopped)
            assertTrue(result.savedBytes > 0)
            val last = progress.last()
            assertEquals(last.bytesTotal, last.bytesDone, "everything accounted for")
            assertNotNull(DriveCompression.markerCodec(root))

            assertTrue(CompressedFile.isCompressed(File(root, "Documents/report.txt")))
            assertFalse(CompressedFile.isCompressed(File(root, "Pictures/photo.raw")), "excluded folders are left alone")

            val access = FileDriveAccess(root)
            originals.forEach { (path, bytes) ->
                assertEquals(bytes.size.toLong(), access.stat("/$path")!!.size, "$path shows its real size")
                access.open("/$path").use { assertContentEquals(bytes, it.readBytes(), path) }
            }
            assertTrue(access.list("/").none { it.name == DriveCompression.MARKER }, "the marker is not a file anyone sees")

            DriveCompression.run(root, method, compressing = false)
            assertNull(DriveCompression.markerCodec(root))
            originals.forEach { (path, bytes) -> assertContentEquals(bytes, File(root, path).readBytes(), path) }
        } finally {
            DriveCompression.excluded = before
        }
    }

    @Test
    fun `a file written to a drive set to compress new files is compressed as it arrives`() {
        File(root, "seed.txt").writeBytes(text)
        DriveCompression.run(root, DriveCompression.PrismMethod(BlockCodecs.standard.first()), compressing = true, auto = true)
        val access = FileDriveAccess(root)
        access.write("/arrived.txt", text.inputStream(), text.size.toLong())
        val arrived = File(root, "arrived.txt")
        val deadline = System.currentTimeMillis() + 10_000
        while (!CompressedFile.isCompressed(arrived) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(CompressedFile.isCompressed(arrived))
        access.open("/arrived.txt").use { assertContentEquals(text, it.readBytes()) }
    }
}
