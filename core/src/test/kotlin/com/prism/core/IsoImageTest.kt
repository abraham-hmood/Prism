package com.prism.core

import com.prism.launcher.storage.IsoImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ISO9660 reading, against a hand-built image.
 *
 * The unrooted burn path copies files out of the image, so a parser that walks the wrong extent
 * writes the wrong bytes onto a stick and reports success. The fixture here is a real (if tiny)
 * ISO9660 volume: a primary volume descriptor, a root directory, a subdirectory and one file with
 * known contents, so both the tree and the bytes are checked.
 */
class IsoImageTest {

    @Test
    fun theTreeAndTheBytesInsideItAreRead() {
        val file = File.createTempFile("prism-iso-test", ".iso")
        try {
            file.writeBytes(fixtureIso())
            val image = assertNotNull(IsoImage.open(file))
            val entries = image.entries()

            assertTrue(entries.any { it.path == "HELLO.TXT" && !it.isDirectory })
            assertTrue(entries.any { it.path == "SUB" && it.isDirectory })
            assertTrue(entries.any { it.path == "SUB/NOTES.TXT" })
            assertFalse(entries.any { it.path.contains("..") })

            val hello = entries.first { it.path == "HELLO.TXT" }
            assertEquals(12, hello.sizeBytes)
            val sink = ByteArrayOutputStream()
            image.copyTo(hello) { chunk, length -> sink.write(chunk, 0, length) }
            assertEquals("Hello, ISO!\n", sink.toString("UTF-8"))

            val notes = entries.first { it.path == "SUB/NOTES.TXT" }
            val noteBytes = ByteArrayOutputStream()
            image.copyTo(notes) { chunk, length -> noteBytes.write(chunk, 0, length) }
            assertEquals("notes\n", noteBytes.toString("UTF-8"))

            assertEquals(12L + 6L, image.totalBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun aFileThatIsNotAnIsoIsRefusedRatherThanWalked() {
        val file = File.createTempFile("prism-not-iso", ".iso")
        try {
            file.writeBytes(ByteArray(4096) { 0x41 })
            assertNull(IsoImage.open(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aTruncatedImageReturnsNoEntriesInsteadOfThrowing() {
        val file = File.createTempFile("prism-cut-iso", ".iso")
        try {
            val full = fixtureIso()
            // Everything up to and including the descriptor set, but no directory sectors.
            file.writeBytes(full.copyOf(18 * 2048))
            val image = IsoImage.open(file)
            // The descriptor itself survived, so the image opens; the tree is simply empty.
            assertTrue(image == null || image.entries().isEmpty())
        } finally {
            file.delete()
        }
    }

    // ── Fixture ────────────────────────────────────────────────────────────

    private val sector = 2048
    private val sectors = 24
    private val rootLba = 20L
    private val helloLba = 21L
    private val subLba = 22L
    private val notesLba = 23L

    private fun fixtureIso(): ByteArray {
        val image = ByteArray(sectors * sector)
        // Primary volume descriptor at sector 16.
        val pvd = ByteArray(sector)
        pvd[0] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(pvd, 1)
        pvd[6] = 1
        le32(pvd, 80, sectors.toLong())
        be32(pvd, 84, sectors.toLong())
        record(byteArrayOf(0), rootLba, sector.toLong(), directory = true).copyInto(pvd, 190)
        pvd.copyInto(image, 16 * sector)

        // Terminator at sector 17.
        val terminator = ByteArray(sector)
        terminator[0] = 0xFF.toByte()
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(terminator, 1)
        terminator.copyInto(image, 17 * sector)

        // Root directory: itself, its parent, one file, one subdirectory.
        val root = ByteArray(sector)
        var cursor = 0
        cursor = write(root, cursor, record(byteArrayOf(0), rootLba, sector.toLong(), true))
        cursor = write(root, cursor, record(byteArrayOf(1), rootLba, sector.toLong(), true))
        cursor = write(
            root, cursor,
            record("HELLO.TXT;1".toByteArray(Charsets.US_ASCII), helloLba, 12, false),
        )
        write(root, cursor, record("SUB".toByteArray(Charsets.US_ASCII), subLba, sector.toLong(), true))
        root.copyInto(image, (rootLba * sector).toInt())

        "Hello, ISO!\n".toByteArray(Charsets.US_ASCII).copyInto(image, (helloLba * sector).toInt())

        val sub = ByteArray(sector)
        var subCursor = 0
        subCursor = write(sub, subCursor, record(byteArrayOf(0), subLba, sector.toLong(), true))
        subCursor = write(sub, subCursor, record(byteArrayOf(1), rootLba, sector.toLong(), true))
        write(
            sub, subCursor,
            record("NOTES.TXT;1".toByteArray(Charsets.US_ASCII), notesLba, 6, false),
        )
        sub.copyInto(image, (subLba * sector).toInt())

        "notes\n".toByteArray(Charsets.US_ASCII).copyInto(image, (notesLba * sector).toInt())
        return image
    }

    private fun write(target: ByteArray, offset: Int, bytes: ByteArray): Int {
        bytes.copyInto(target, offset)
        return offset + bytes.size
    }

    /**
     * A directory record, padded to an even length exactly as ISO9660 requires.
     *
     * Both byte orders are written for each 32-bit field because ISO9660 stores them twice, little
     * endian then big endian; a reader that only understands one of them fails on half the images in
     * the world.
     */
    private fun record(name: ByteArray, extent: Long, size: Long, directory: Boolean): ByteArray {
        val unpadded = 33 + name.size
        val length = if (unpadded % 2 == 0) unpadded else unpadded + 1
        val record = ByteArray(length)
        record[0] = length.toByte()
        le32(record, 2, extent)
        be32(record, 6, extent)
        le32(record, 10, size)
        be32(record, 14, size)
        record[25] = if (directory) 0x02 else 0x00
        record[32] = name.size.toByte()
        name.copyInto(record, 33)
        return record
    }

    private fun le32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun be32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
