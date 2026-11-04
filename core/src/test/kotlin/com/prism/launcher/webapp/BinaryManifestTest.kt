package com.prism.launcher.webapp

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manifest rewrite behind "Install as app".
 *
 * Run against the REAL compiled manifest of the :webapp-shell template when it has been built,
 * because the encoding AAPT2 chooses (UTF-8 or UTF-16, sorted or not) is exactly what a hand-made
 * fixture would get wrong. A small UTF-16 and UTF-8 fixture covers both encodings regardless.
 */
class BinaryManifestTest {

    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("no project root")
    }

    private fun templateManifest(): ByteArray? {
        val apk = File(projectRoot(), "webapp-shell/build/outputs/apk/release/webapp-shell-release-unsigned.apk")
        if (!apk.isFile) return null
        return ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes() }
    }

    @Test
    fun `the template's package and label are rewritten and nothing else moves`() {
        val original = templateManifest() ?: return // not built in this checkout; the fixtures still run
        val before = BinaryManifest.strings(original)
        assertTrue("com.prism.webshell.template" in before, "template package not found in $before")
        assertTrue("Prism Web App" in before, "template label not found in $before")

        val rewritten = BinaryManifest.rewrite(original) {
            when (it) {
                "com.prism.webshell.template" -> "com.prism.webapp.w0123456789abcdef"
                "Prism Web App" -> "Ünïcode — a much longer site title than the template had"
                else -> it
            }
        }
        val after = BinaryManifest.strings(rewritten)
        assertEquals(before.size, after.size)
        assertEquals("com.prism.webapp.w0123456789abcdef", after[before.indexOf("com.prism.webshell.template")])
        assertEquals(
            "Ünïcode — a much longer site title than the template had",
            after[before.indexOf("Prism Web App")],
        )
        // The activity's class name must survive: the package changes, the code does not.
        assertTrue("com.prism.webshell.MainActivity" in after)
        // The document's own size field has to match the new length, or Android rejects it.
        val size = java.nio.ByteBuffer.wrap(rewritten).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4)
        assertEquals(rewritten.size, size)
        // Everything after the string pool is byte-for-byte the same.
        val poolEndBefore = 8 + java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        val poolEndAfter = 8 + java.nio.ByteBuffer.wrap(rewritten).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        assertContentEquals(
            original.copyOfRange(poolEndBefore, original.size),
            rewritten.copyOfRange(poolEndAfter, rewritten.size),
        )
    }

    @Test
    fun `an unchanged rewrite is the same bytes`() {
        val doc = fixture(listOf("manifest", "package", "com.example"), utf8 = false)
        assertContentEquals(doc, BinaryManifest.rewrite(doc) { it })
    }

    @Test
    fun `both encodings round-trip`() {
        for (utf8 in listOf(false, true)) {
            val doc = fixture(listOf("manifest", "com.example", "Label"), utf8)
            val out = BinaryManifest.rewrite(doc) { if (it == "Label") "Längere Bezeichnung" else it }
            assertEquals(listOf("manifest", "com.example", "Längere Bezeichnung"), BinaryManifest.strings(out))
            // The pool is no longer promised to be sorted.
            val flags = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8 + 16)
            assertFalse(flags and 1 != 0)
        }
    }

    /** A binary XML document holding only a string pool and one trailing chunk, for both encodings. */
    private fun fixture(strings: List<String>, utf8: Boolean): ByteArray {
        val encoded = strings.map { s ->
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                byteArrayOf(s.length.toByte(), b.size.toByte()) + b + 0
            } else {
                val out = java.io.ByteArrayOutputStream()
                out.write(s.length and 0xff); out.write(s.length shr 8)
                s.forEach { out.write(it.code and 0xff); out.write(it.code shr 8) }
                out.write(0); out.write(0)
                out.toByteArray()
            }
        }
        var data = encoded.fold(ByteArray(0)) { a, b -> a + b }
        while (data.size % 4 != 0) data += 0
        val header = 28
        val stringsStart = header + 4 * strings.size
        val poolSize = stringsStart + data.size
        val tail = byteArrayOf(0x80.toByte(), 0x01, 8, 0, 8, 0, 0, 0) // an empty resource-map chunk
        val total = 8 + poolSize + tail.size
        val buf = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(3); buf.putShort(8); buf.putInt(total)
        buf.putShort(1); buf.putShort(header.toShort()); buf.putInt(poolSize)
        buf.putInt(strings.size); buf.putInt(0); buf.putInt(if (utf8) 0x101 else 1)
        buf.putInt(stringsStart); buf.putInt(0)
        var offset = 0
        encoded.forEach { buf.putInt(offset); offset += it.size }
        buf.put(data)
        buf.put(tail)
        return buf.array()
    }
}
