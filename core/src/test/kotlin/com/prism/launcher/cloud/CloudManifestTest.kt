package com.prism.launcher.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The folder browser's arithmetic.
 *
 * There are no folder records — a folder is a shared prefix across file paths — so every folder the
 * browser draws is derived, and a derivation that is subtly wrong shows the user a file system that is
 * not the one they have. The failures worth catching are silent ones: a file appearing in two folders,
 * a folder that swallows a sibling whose name merely starts the same way, and a path that escapes the
 * root and becomes unreachable.
 */
class CloudManifestTest {

    private fun entry(path: String, size: Long = 100, complete: Boolean = true) =
        CloudManifest.Entry(
            id = path,
            name = path,
            size = size,
            createdAt = 1L,
            chunks = listOf(
                CloudManifest.ChunkRef(
                    id = "c-$path",
                    index = 0,
                    bytes = size.toInt(),
                    holders = if (complete) listOf("10.0.0.2") else emptyList(),
                )
            ),
        )

    private fun snapshot(vararg paths: String) =
        CloudManifest.Snapshot(paths.map { entry(it) }, 1L)

    // ── Listing ────────────────────────────────────────────────────────────

    @Test
    fun `the root shows folders and loose files, and nothing nested`() {
        val snap = snapshot(
            "notes.txt",
            "photos/a.jpg",
            "photos/b.jpg",
            "photos/2024/c.jpg",
            "work/report.pdf",
        )
        val root = CloudManifest.list(snap, "")

        // Two folders and one loose file. The nested photos are NOT at the root.
        assertEquals(listOf("photos", "work", "notes.txt"), root.map { it.name })
        assertEquals(listOf(true, true, false), root.map { it.isFolder })

        val photos = root.first { it.name == "photos" }
        // Counts everything BELOW, including the deeper folder's file.
        assertEquals(3, photos.fileCount)
        assertEquals(300L, photos.bytes)
    }

    @Test
    fun `opening a folder shows its own contents only`() {
        val snap = snapshot("photos/a.jpg", "photos/2024/c.jpg", "work/report.pdf")
        val photos = CloudManifest.list(snap, "photos")

        assertEquals(listOf("2024", "a.jpg"), photos.map { it.name })
        // The path of a nested node carries the whole prefix, or navigating into it would lose it.
        assertEquals("photos/2024", photos.first { it.isFolder }.path)
        assertEquals("photos/a.jpg", photos.first { !it.isFolder }.path)
    }

    @Test
    fun `a folder does not swallow a sibling with the same prefix`() {
        // "photos" must not absorb "photos-old". A naive startsWith on the raw name does exactly that,
        // and the user loses a whole folder from the listing.
        val snap = snapshot("photos/a.jpg", "photos-old/b.jpg")
        val root = CloudManifest.list(snap, "")
        assertEquals(listOf("photos", "photos-old"), root.map { it.name })
        assertEquals(1, CloudManifest.list(snap, "photos").size)
        assertEquals("a.jpg", CloudManifest.list(snap, "photos").first().name)
    }

    @Test
    fun `folders sort before files and both sort case-insensitively`() {
        val snap = snapshot("Zebra.txt", "apple.txt", "Mango/x", "beta/y")
        val root = CloudManifest.list(snap, "")
        assertEquals(listOf("beta", "Mango", "apple.txt", "Zebra.txt"), root.map { it.name })
    }

    @Test
    fun `a folder is incomplete when any file under it is`() {
        val snap = CloudManifest.Snapshot(
            listOf(
                entry("docs/fine.txt"),
                entry("docs/deep/broken.txt", complete = false),
            ),
            1L,
        )
        val docs = CloudManifest.list(snap, "").first { it.name == "docs" }
        assertFalse(docs.complete, "a folder holding an unreadable file must not report itself sound")
        // And the sound sibling still reports itself sound.
        assertTrue(CloudManifest.list(snap, "docs").first { it.name == "fine.txt" }.complete)
    }

    @Test
    fun `an empty folder listing comes back empty rather than throwing`() {
        assertTrue(CloudManifest.list(snapshot("a/b.txt"), "nothing/here").isEmpty())
        assertTrue(CloudManifest.list(CloudManifest.Snapshot(emptyList(), 0L), "").isEmpty())
    }

    @Test
    fun `a file whose path has a trailing or doubled slash still lands in one place`() {
        // Paths come from filenames and from users, so they are not always tidy. What matters is that
        // normalisation is applied consistently -- the same file must not appear twice.
        val snap = CloudManifest.Snapshot(
            listOf(entry("photos//a.jpg"), entry("/photos/b.jpg")),
            1L,
        )
        val root = CloudManifest.list(snap, "")
        assertEquals(1, root.size, "both files belong to one folder")
        assertEquals("photos", root[0].name)
        assertEquals(2, root[0].fileCount)
        assertEquals(listOf("a.jpg", "b.jpg"), CloudManifest.list(snap, "photos").map { it.name })
    }

    // ── Paths ──────────────────────────────────────────────────────────────

    @Test
    fun `normalise strips traversal so nothing escapes the root`() {
        // A stored name with ".." would sort above the root and be unreachable from the browser, which
        // means a file the user cannot get back.
        assertEquals("etc/passwd", CloudManifest.normalise("../../etc/passwd"))
        assertEquals("a/b", CloudManifest.normalise("a/./b"))
        assertEquals("a/b", CloudManifest.normalise("a\\b"))
        assertEquals("a/b", CloudManifest.normalise("//a//b//"))
        assertEquals("", CloudManifest.normalise(".."))
        assertEquals("", CloudManifest.normalise("/"))
    }

    @Test
    fun `join puts a file in a folder and copes with an empty one`() {
        assertEquals("a.txt", CloudManifest.join("", "a.txt"))
        assertEquals("docs/a.txt", CloudManifest.join("docs", "a.txt"))
        assertEquals("docs/a.txt", CloudManifest.join("/docs/", "a.txt"))
        // A picked filename that itself contains a path must not escape the destination folder.
        assertEquals("docs/etc/passwd", CloudManifest.join("docs", "../../etc/passwd"))
        assertEquals("docs/file", CloudManifest.join("docs", ".."))
    }

    @Test
    fun `the trail names every ancestor in order`() {
        assertEquals(
            listOf("a" to "a", "b" to "a/b", "c" to "a/b/c"),
            CloudManifest.trail("a/b/c"),
        )
        assertTrue(CloudManifest.trail("").isEmpty())
    }

    @Test
    fun `parentOf walks up one level and stops at the root`() {
        assertEquals("a/b", CloudManifest.parentOf("a/b/c"))
        assertEquals("a", CloudManifest.parentOf("a/b"))
        assertEquals("", CloudManifest.parentOf("a"))
        assertEquals("", CloudManifest.parentOf(""))
    }

    // ── Serialisation ──────────────────────────────────────────────────────

    @Test
    fun `a path-named entry survives encode and decode`() {
        val snap = snapshot("holidays/2024/beach.jpg", "notes.txt")
        val back = CloudManifest.decode(CloudManifest.encode(snap))
        assertEquals(2, back.entries.size)
        assertEquals(
            setOf("holidays/2024/beach.jpg", "notes.txt"),
            back.entries.map { it.name }.toSet(),
        )
        // And it still browses the same way after the round trip.
        assertEquals(listOf("holidays", "notes.txt"), CloudManifest.list(back, "").map { it.name })
    }
}
