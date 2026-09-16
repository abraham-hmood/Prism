package com.prism.core

import com.prism.launcher.DesktopFolders
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the folder model, which is directory-backed rather than a stored list.
 *
 * The dangerous operations here touch the user's real filesystem: a folder id that starts with a
 * path separator is an absolute path to somewhere on their disk, and rename and delete must
 * refuse those. Getting that wrong renames or deletes a real directory because someone dragged
 * their Documents folder onto the desktop, which is why it is tested rather than reasoned about.
 */
class FolderTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-folders-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `created folders are real directories under the data dir`() {
        val id = DesktopFolders.create("Games")
        val folder = DesktopFolders.resolve(id)
        assertTrue(folder.isDirectory, "folder must exist on disk")
        assertTrue(folder.absolutePath.startsWith(DesktopFolders.root().absolutePath))
        assertTrue(id.startsWith("Games-"), "id keeps the name for legibility: $id")
    }

    /** Two folders with the same name must not collide. */
    @Test
    fun `folder ids are unique`() {
        val a = DesktopFolders.create("Work")
        Thread.sleep(2)
        val b = DesktopFolders.create("Work")
        assertTrue(a != b, "ids must differ: $a vs $b")
    }

    /** Directories first, then files, each alphabetical and case-insensitive. */
    @Test
    fun `listing sorts directories first then case-insensitively`() {
        val id = DesktopFolders.create("Mixed")
        val root = DesktopFolders.resolve(id)
        File(root, "zebra.txt").writeText("z")
        File(root, "Apple.txt").writeText("a")
        File(root, "beta").mkdirs()
        File(root, "Alpha").mkdirs()

        assertEquals(
            listOf("Alpha", "beta", "Apple.txt", "zebra.txt"),
            DesktopFolders.list(id).map { it.name },
        )
    }

    @Test
    fun `listing a missing folder is empty rather than an error`() {
        assertEquals(emptyList(), DesktopFolders.list("does-not-exist"))
    }

    @Test
    fun `rename moves the directory and returns the new id`() {
        val id = DesktopFolders.create("Old")
        File(DesktopFolders.resolve(id), "keep.txt").writeText("x")

        val newId = DesktopFolders.rename(id, "New")
        assertEquals("New", newId)
        assertFalse(DesktopFolders.resolve(id).exists(), "the old directory must be gone")
        assertTrue(DesktopFolders.resolve("New").isDirectory)
        // Contents come along, which is the whole point of a rename rather than a recreate.
        assertEquals(listOf("keep.txt"), DesktopFolders.list("New").map { it.name })
    }

    @Test
    fun `rename refuses to clobber an existing folder`() {
        DesktopFolders.resolve("taken").mkdirs()
        val id = DesktopFolders.create("Source")
        assertNull(DesktopFolders.rename(id, "taken"))
        assertTrue(DesktopFolders.resolve(id).isDirectory, "the source must survive a refused rename")
    }

    /**
     * The load-bearing safety property.
     *
     * An absolute-path id points at a directory the user actually has -- their Documents, say --
     * and "rename this desktop icon" must never mean "rename that directory on disk".
     */
    @Test
    fun `rename and delete refuse absolute paths`() {
        val real = File(dir, "RealUserDirectory")
        real.mkdirs()
        File(real, "important.txt").writeText("do not lose me")

        val id = real.absolutePath
        assertNull(DesktopFolders.rename(id, "Renamed"), "must refuse to rename a real directory")
        assertFalse(DesktopFolders.delete(id), "must refuse to delete a real directory")

        assertTrue(real.isDirectory, "the user's directory must be untouched")
        assertEquals("do not lose me", File(real, "important.txt").readText())
    }

    /** An absolute-path folder still LISTS, because that is the feature. */
    @Test
    fun `absolute path folders list their real contents`() {
        val real = File(dir, "Elsewhere")
        real.mkdirs()
        File(real, "a.txt").writeText("a")

        assertEquals(listOf("a.txt"), DesktopFolders.list(real.absolutePath).map { it.name })
    }

    /** Copy, not move -- a home screen must not relocate files out of Downloads. */
    @Test
    fun `adding a file copies rather than moves it`() {
        val id = DesktopFolders.create("Docs")
        val source = File(dir, "report.pdf")
        source.writeText("content")

        val copied = DesktopFolders.addFile(id, source)
        assertEquals("report.pdf", copied?.name)
        assertTrue(source.exists(), "the source must still be where the user left it")
        assertEquals("content", copied?.readText())
    }

    /** A second file of the same name gets " (2)", as both platforms' file managers do. */
    @Test
    fun `duplicate names are disambiguated`() {
        val id = DesktopFolders.create("Docs")
        val a = File(dir, "report.pdf").apply { writeText("first") }
        val b = File(dir, "sub").apply { mkdirs() }.let { File(it, "report.pdf").apply { writeText("second") } }

        DesktopFolders.addFile(id, a)
        val second = DesktopFolders.addFile(id, b)

        assertEquals("report (2).pdf", second?.name)
        assertEquals("second", second?.readText())
        assertEquals(2, DesktopFolders.list(id).size)
    }

    /** Names illegal on either platform are sanitized, so a folder syncs across both. */
    @Test
    fun `names are sanitized for both platforms`() {
        val id = DesktopFolders.create("a/b:c*d?e")
        assertFalse(id.contains('/'), id)
        assertFalse(id.contains(':'), id)
        assertFalse(id.contains('*'), id)
        assertTrue(DesktopFolders.resolve(id).isDirectory)
    }

    @Test
    fun `delete removes a prism-owned folder and its contents`() {
        val id = DesktopFolders.create("Temp")
        File(DesktopFolders.resolve(id), "junk.txt").writeText("x")
        assertTrue(DesktopFolders.delete(id))
        assertFalse(DesktopFolders.resolve(id).exists())
    }
}
