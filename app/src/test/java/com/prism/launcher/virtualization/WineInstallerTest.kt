package com.prism.launcher.virtualization

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the two pieces of [WineInstaller] that are pure logic and easy to get subtly wrong.
 *
 * The tar reader exists because the Ubuntu root filesystem is the first thing unpacked and there is
 * nothing available to unpack it with yet. It is hand-written, it runs over thirty thousand entries,
 * and a mistake in it does not fail loudly -- it produces a root filesystem that is missing a
 * symlink somewhere and fails much later inside Wine. The version comparison decides which build of
 * Wine gets downloaded, where a string comparison would quietly pick 9.0 over 10.0 forever.
 */
class WineInstallerTest {

    // ------------------------------------------------------------------ tar

    @Test
    fun `unpacks files directories and modes`() {
        val archive = tar(
            entry("rootfs/etc/", type = '5'),
            entry("rootfs/etc/hostname", body = "prism\n"),
            // Deliberately not a multiple of 512, so the next header is only found if the padding
            // after the body is skipped correctly.
            entry("rootfs/usr/bin/box64", body = "x".repeat(700), mode = 493L),
            entry("rootfs/usr/share/doc/readme", body = "y".repeat(1024)),
        )

        val target = tempDir()
        val count = WineInstaller.untar(ByteArrayInputStream(archive), target)

        assertEquals(3, count, "three files, the directory entry is not counted")
        assertEquals("prism\n", File(target, "rootfs/etc/hostname").readText())
        assertEquals(700, File(target, "rootfs/usr/bin/box64").length().toInt())
        assertEquals(1024, File(target, "rootfs/usr/share/doc/readme").length().toInt())
        assertTrue(File(target, "rootfs/usr/share/doc").isDirectory, "parents are created implicitly")
        assertTrue(File(target, "rootfs/usr/bin/box64").canExecute(), "0755 keeps its executable bit")
    }

    @Test
    fun `restores symlinks with the target the archive gave`() {
        val archive = tar(
            entry("lib/libfoo.so.1", body = "elf"),
            entry("lib/libfoo.so", type = '2', link = "libfoo.so.1"),
            // Absolute targets are stored as-is: they dangle seen from Android and resolve correctly
            // from inside PRoot, which is the only place they are ever followed.
            entry("usr/bin/awk", type = '2', link = "/usr/bin/mawk"),
        )

        val target = tempDir()
        WineInstaller.untar(ByteArrayInputStream(archive), target)

        val relative = File(target, "lib/libfoo.so").toPath()
        val absolute = File(target, "usr/bin/awk").toPath()

        // Windows refuses symlinks to an unprivileged process, and the installer treats that as
        // non-fatal rather than failing the whole unpack -- so on a host that cannot make them the
        // assertion to make is that nothing threw, which getting here already establishes.
        if (Files.isSymbolicLink(relative)) {
            assertEquals("libfoo.so.1", Files.readSymbolicLink(relative).toString())
            assertEquals(
                "/usr/bin/mawk",
                Files.readSymbolicLink(absolute).toString().replace('\\', '/'),
            )
        }
    }

    @Test
    fun `hard links share the content`() {
        val archive = tar(
            entry("bin/gzip", body = "the real binary"),
            entry("bin/gunzip", type = '1', link = "bin/gzip"),
        )

        val target = tempDir()
        WineInstaller.untar(ByteArrayInputStream(archive), target)

        // Whether it became a link or a copy, the content has to be there -- the installer falls
        // back to copying on a filesystem that refuses the link.
        assertEquals("the real binary", File(target, "bin/gunzip").readText())
    }

    @Test
    fun `reads GNU long names`() {
        // Over the 100 bytes the header field holds, which is why GNU writes the name as an entry of
        // its own. Ubuntu's rootfs has these, so getting it wrong loses real files.
        val long = "usr/lib/aarch64-linux-gnu/" + "deeply/".repeat(12) + "libsomething.so.1.2.3"
        assertTrue(long.length > 100, "the fixture has to actually be too long")

        val archive = tar(
            entry("././@LongLink", body = long + "\u0000", type = 'L'),
            entry(long.take(100), body = "content"),
        )

        val target = tempDir()
        val count = WineInstaller.untar(ByteArrayInputStream(archive), target)

        assertEquals(1, count)
        assertEquals("content", File(target, long).readText())
    }

    @Test
    fun `reads POSIX pax long names`() {
        // The same problem as a GNU long name, solved the other way. Skipping the header is not a
        // missing feature but silent corruption: the entry after it still has a name, truncated,
        // so the file would land at a plausible-looking wrong path.
        val long = "opt/wine-stable/lib/wine/" + "nested/".repeat(12) + "winspool.drv"
        assertTrue(long.length > 100)

        val archive = tar(
            entry("PaxHeaders/0/x", body = paxRecord("path", long), type = 'x'),
            entry(long.take(100), body = "pe"),
        )

        val target = tempDir()
        val count = WineInstaller.untar(ByteArrayInputStream(archive), target)

        assertEquals(1, count)
        assertEquals("pe", File(target, long).readText())
    }

    @Test
    fun `reads a pax long link target`() {
        val long = "../" + "up/".repeat(40) + "libreal.so.1"
        assertTrue(long.length > 100)

        val archive = tar(
            entry("PaxHeaders/0/x", body = paxRecord("linkpath", long), type = 'x'),
            entry("usr/lib/libalias.so", type = '2', link = long.take(100)),
        )

        val target = tempDir()
        WineInstaller.untar(ByteArrayInputStream(archive), target)

        val link = File(target, "usr/lib/libalias.so").toPath()
        if (Files.isSymbolicLink(link)) {
            assertEquals(long, Files.readSymbolicLink(link).toString().replace('\\', '/'))
        }
    }

    @Test
    fun `refuses an entry that climbs out of the rootfs`() {
        val archive = tar(entry("../../escaped", body = "hostile"))
        val target = tempDir()

        assertFailsWith<SecurityException> {
            WineInstaller.untar(ByteArrayInputStream(archive), target)
        }
        assertTrue(!File(target.parentFile, "escaped").exists())
    }

    @Test
    fun `ignores device nodes rather than failing on them`() {
        // An app has no privilege to call mknod, and does not need it: the container gets the host's
        // real /dev bound in over the top. What matters is that the entry after one is still read.
        val archive = tar(
            entry("dev/null", type = '3'),
            entry("etc/passwd", body = "root:x:0:0:"),
        )

        val target = tempDir()
        WineInstaller.untar(ByteArrayInputStream(archive), target)

        assertEquals("root:x:0:0:", File(target, "etc/passwd").readText())
    }

    // ------------------------------------------------------------------ versions

    @Test
    fun `orders versions numerically, not as strings`() {
        // The case this exists for: as strings, "9.0" sorts above "10.0".
        assertTrue(
            WineInstaller.compareVersions("11.0.0.0~jammy-1", "9.0.0.0~jammy-1") > 0,
            "Wine 11 is newer than Wine 9",
        )
        assertTrue(WineInstaller.compareVersions("10.0.0.0~jammy-1", "9.0.0.0~jammy-1") > 0)

        // box64 publishes dated nightlies, and picking the newest is the whole job.
        assertTrue(
            WineInstaller.compareVersions(
                "0.4.5+20260916.3ad88dc-1", "0.4.5+20260915.583b5b6-1",
            ) > 0,
        )
    }

    @Test
    fun `a tilde sorts below the release it precedes`() {
        assertTrue(WineInstaller.compareVersions("1.0~rc1", "1.0") < 0)
        assertTrue(WineInstaller.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, WineInstaller.compareVersions("9.0.0.0~jammy-1", "9.0.0.0~jammy-1"))
    }

    // ------------------------------------------------------------------ package index

    @Test
    fun `parses a Packages index and drops the fields it does not need`() {
        val index = """
            Package: box64
            Architecture: arm64
            Version: 0.4.5+20260916.3ad88dc-1
            Description: a very long description
             with a continuation line that is not a field
            Filename: ./box64_0.4.5+20260916.3ad88dc-1_arm64.deb

            Package: box64-rk3588
            Version: 0.4.5+20260916.3ad88dc-1
            Filename: ./box64-rk3588_0.4.5+20260916.3ad88dc-1_arm64.deb
        """.trimIndent()

        val stanzas = WineInstaller.parsePackages(index)

        assertEquals(2, stanzas.size)
        assertEquals(setOf("Package", "Version", "Filename"), stanzas[0].keys)
        assertEquals("box64", stanzas[0]["Package"])
        assertEquals("./box64_0.4.5+20260916.3ad88dc-1_arm64.deb", stanzas[0]["Filename"])
        assertEquals("box64-rk3588", stanzas[1]["Package"], "a stanza with no trailing blank line")
    }

    // ------------------------------------------------------------------ fixtures

    private class Entry(val header: ByteArray, val body: ByteArray)

    /** One tar entry. [mode] defaults to 0644, [type] to a regular file. */
    private fun entry(
        name: String,
        body: String = "",
        type: Char = '0',
        mode: Long = 420L,
        link: String = "",
    ): Entry {
        val bytes = body.toByteArray()
        val header = ByteArray(BLOCK)

        fun text(offset: Int, value: String, width: Int) {
            val encoded = value.toByteArray()
            System.arraycopy(encoded, 0, header, offset, minOf(encoded.size, width - 1))
        }

        fun octal(offset: Int, value: Long, width: Int) {
            text(offset, value.toString(8).padStart(width - 1, '0'), width)
        }

        text(0, name, 100)
        octal(100, mode, 8)
        octal(108, 0, 8)
        octal(116, 0, 8)
        octal(124, bytes.size.toLong(), 12)
        octal(136, 0, 12)
        header[156] = type.code.toByte()
        text(157, link, 100)
        text(257, "ustar", 6)
        text(263, "00", 3)

        // The checksum is computed with its own field read as spaces. The reader does not verify it,
        // but a fixture that real tar would reject is a fixture that proves less.
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xFF }
        text(148, sum.toString(8).padStart(6, '0'), 8)

        return Entry(header, bytes)
    }

    /**
     * One pax record: `<length> <key>=<value>` with a newline, where the length counts itself.
     *
     * Computed by the same fixed-point step real tar uses -- adding the digits of the length can
     * push the length into another digit.
     */
    private fun paxRecord(key: String, value: String): String {
        val body = " $key=$value\n"
        var length = body.length + 1
        while (length.toString().length + body.length != length) length = length.toString().length + body.length
        return "$length$body"
    }

    private fun tar(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in entries) {
            out.write(item.header)
            out.write(item.body)
            val padding = (BLOCK - item.body.size % BLOCK) % BLOCK
            out.write(ByteArray(padding))
        }
        // Two zero blocks are the end-of-archive marker.
        out.write(ByteArray(BLOCK * 2))
        return out.toByteArray()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("prism-untar").toFile().apply { deleteOnExit() }

    private companion object {
        const val BLOCK = 512
    }
}
