package com.prism.core

import com.prism.launcher.FileOps
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the destructive file operations.
 *
 * These are tested rather than reasoned about because their failure modes are silent and
 * unrecoverable: a folder pasted into itself recurses until the disk fills, a cut that fails
 * halfway loses the original, a rename that overwrites destroys the other file. None of those
 * throw; they all look like success.
 */
class FileOpsTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "prism-files-${System.nanoTime()}")
        dir.mkdirs()
        FileOps.clearClipboard()
    }

    @AfterTest
    fun tearDown() {
        FileOps.clearClipboard()
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String = "x"): File =
        File(dir, name).apply { parentFile.mkdirs(); writeText(content) }

    private fun folder(name: String): File = File(dir, name).apply { mkdirs() }

    @Test
    fun `copy leaves the original and duplicates content`() {
        val src = file("a.txt", "hello")
        val dest = folder("dest")

        FileOps.copy(listOf(src))
        val result = FileOps.paste(dest)

        assertTrue(result.ok, result.skipped.toString())
        assertTrue(src.exists(), "copy must not remove the source")
        assertEquals("hello", File(dest, "a.txt").readText())
    }

    @Test
    fun `cut removes the original and clears the clipboard`() {
        val src = file("a.txt", "hello")
        val dest = folder("dest")

        FileOps.cut(listOf(src))
        val result = FileOps.paste(dest)

        assertTrue(result.ok, result.skipped.toString())
        assertFalse(src.exists(), "cut must remove the source")
        assertEquals("hello", File(dest, "a.txt").readText())
        assertNull(FileOps.clipboard(), "a cut is consumed by its paste")
    }

    /** A copy stays on the clipboard, so it can be pasted into several places. */
    @Test
    fun `copy survives its paste`() {
        FileOps.copy(listOf(file("a.txt")))
        FileOps.paste(folder("one"))
        assertTrue(FileOps.clipboard() != null)
        FileOps.paste(folder("two"))
        assertTrue(File(dir, "one/a.txt").exists())
        assertTrue(File(dir, "two/a.txt").exists())
    }

    /**
     * The catastrophic case: pasting a folder inside itself.
     *
     * A recursive copy of /a into /a/b never terminates; a move detaches the subtree.
     */
    @Test
    fun `pasting a folder into itself is refused`() {
        val outer = folder("outer")
        val inner = File(outer, "inner").apply { mkdirs() }
        File(outer, "keep.txt").writeText("keep")

        FileOps.copy(listOf(outer))
        val result = FileOps.paste(inner)

        assertFalse(result.ok, "must refuse")
        assertTrue(result.skipped.single().contains("into itself"))
        assertEquals(listOf("inner", "keep.txt"), outer.listFiles()!!.map { it.name }.sorted())
    }

    /** Same guard, one level deeper -- a descendant is still inside. */
    @Test
    fun `pasting into a deep descendant is refused`() {
        val outer = folder("outer")
        val deep = File(outer, "a/b/c").apply { mkdirs() }
        FileOps.cut(listOf(outer))
        assertFalse(FileOps.paste(deep).ok)
        assertTrue(outer.exists(), "the source must survive a refused move")
    }

    /** Pasting a folder next to itself is fine, and must not be confused with the above. */
    @Test
    fun `pasting a sibling folder is allowed`() {
        val src = folder("src")
        File(src, "f.txt").writeText("v")
        val dest = folder("dest")

        FileOps.copy(listOf(src))
        assertTrue(FileOps.paste(dest).ok)
        assertEquals("v", File(dest, "src/f.txt").readText())
    }

    /** Colliding names are disambiguated rather than overwritten. */
    @Test
    fun `paste does not overwrite`() {
        val src = file("a.txt", "new")
        val dest = folder("dest")
        File(dest, "a.txt").writeText("original")

        FileOps.copy(listOf(src))
        FileOps.paste(dest)

        assertEquals("original", File(dest, "a.txt").readText(), "the existing file is untouched")
        assertEquals("new", File(dest, "a (2).txt").readText())
    }

    @Test
    fun `rename refuses an existing name`() {
        val a = file("a.txt", "a")
        file("b.txt", "b")
        assertNull(FileOps.rename(a, "b.txt"))
        assertEquals("a", a.readText(), "the source must be untouched")
        assertEquals("b", File(dir, "b.txt").readText(), "the other file must be untouched")
    }

    @Test
    fun `rename moves in place`() {
        val a = file("a.txt", "a")
        val renamed = FileOps.rename(a, "c.txt")
        assertEquals("c.txt", renamed?.name)
        assertFalse(a.exists())
        assertEquals("a", renamed?.readText())
    }

    @Test
    fun `delete removes files and folders and reports failures`() {
        val f = file("gone.txt")
        val d = folder("gonedir").also { File(it, "child.txt").writeText("c") }
        val missing = File(dir, "never-existed.txt")

        val result = FileOps.delete(listOf(f, d, missing))
        assertFalse(f.exists())
        assertFalse(d.exists())
        assertEquals(listOf("never-existed.txt"), result.skipped)
    }

    @Test
    fun `new folder disambiguates and sanitizes`() {
        val first = FileOps.newFolder(dir, "Docs")
        val second = FileOps.newFolder(dir, "Docs")
        assertEquals("Docs", first?.name)
        assertEquals("Docs (2)", second?.name)

        val odd = FileOps.newFolder(dir, "a/b:c")
        assertTrue(odd!!.name.none { it in "/:" }, odd.name)
    }

    /** Directories first, whatever the sort key -- a folder's length() is meaningless. */
    @Test
    fun `sorting puts directories first`() {
        val big = file("big.txt", "x".repeat(500))
        val small = file("small.txt", "x")
        val d = folder("zzz")

        val bySize = FileOps.sorted(listOf(big, small, d), FileOps.Sort.SIZE)
        assertEquals("zzz", bySize.first().name)
        assertEquals(listOf("small.txt", "big.txt"), bySize.drop(1).map { it.name })

        val byName = FileOps.sorted(listOf(big, small, d), FileOps.Sort.NAME)
        assertEquals(listOf("zzz", "big.txt", "small.txt"), byName.map { it.name })
    }

    @Test
    fun `descending reverses only within the group`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val d = folder("d")
        val out = FileOps.sorted(listOf(a, b, d), FileOps.Sort.NAME, descending = true)
        assertEquals("d", out.first().name, "directories stay first even reversed")
        assertEquals(listOf("b.txt", "a.txt"), out.drop(1).map { it.name })
    }

    @Test
    fun `sorting by type groups extensions`() {
        val out = FileOps.sorted(
            listOf(file("b.txt"), file("a.zip"), file("c.txt")),
            FileOps.Sort.TYPE,
        )
        assertEquals(listOf("b.txt", "c.txt", "a.zip"), out.map { it.name })
    }

    @Test
    fun `pasting a stale clipboard entry reports it`() {
        val src = file("a.txt")
        FileOps.copy(listOf(src))
        src.delete()
        val result = FileOps.paste(folder("dest"))
        assertFalse(result.ok)
        assertTrue(result.skipped.single().contains("no longer exists"))
    }

    @Test
    fun `human sizes are readable`() {
        assertEquals("512 B", FileOps.humanSize(512))
        assertEquals("1.0 KB", FileOps.humanSize(1024))
        assertEquals("1.0 MB", FileOps.humanSize(1024L * 1024))
        assertEquals("1.00 GB", FileOps.humanSize(1024L * 1024 * 1024))
    }

    /** The home directory must be a root, or the explorer opens somewhere useless. */
    @Test
    fun `roots include home`() {
        val roots = FileOps.roots()
        assertTrue(roots.isNotEmpty())
        assertEquals(File(System.getProperty("user.home")), roots.first())
    }
}
