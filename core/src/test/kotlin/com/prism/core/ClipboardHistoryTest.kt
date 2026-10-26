package com.prism.core

import com.prism.launcher.clipboard.ClipboardHistory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardHistoryTest {

    private fun scope(): File {
        val dir = createTempDirectory("clipboard").toFile()
        ClipboardHistory.install(dir)
        ClipboardHistory.clear()
        return dir
    }

    @Test
    fun `copying the same text twice keeps one row`() {
        scope()

        ClipboardHistory.recordText("hello")
        ClipboardHistory.recordText("world")
        ClipboardHistory.recordText("hello")

        val entries = ClipboardHistory.all()
        assertEquals(2, entries.size)
        assertEquals("hello", entries.first().text)
        assertEquals("hello", ClipboardHistory.active()?.text)
    }

    @Test
    fun `a file entry never puts its local path on the wire`() {
        scope()
        val dir = createTempDirectory("copied").toFile()
        val file = File(dir, "notes.txt").apply { writeText("private") }

        val entry = ClipboardHistory.recordFile(file, ClipboardHistory.Mode.CUT)
        val json = ClipboardHistory.toJson(entry)

        assertEquals("notes.txt", json.optString("name"))
        assertTrue(json.optBoolean("dir").not())
        assertEquals("cut", json.optString("mode"))
        // The path is the one field a peer has no business knowing.
        assertEquals("", json.optString("path"))
        assertFalse(json.toString().contains(dir.absolutePath))
    }

    @Test
    fun `a remote entry is marked as remote and keeps its direction`() {
        scope()

        val entry = ClipboardHistory.recordRemoteFile(
            name = "photos",
            directory = true,
            bytes = 0,
            mode = ClipboardHistory.Mode.CUT,
            fromFingerprint = "abc123",
            fromName = "Laptop",
        )
        requireNotNull(entry)

        assertTrue(entry.remote)
        assertEquals("Laptop", entry.sourceName)
        assertTrue(ClipboardHistory.active()?.remote == true)
    }

    @Test
    fun `the history is capped and persists across a reinstall`() {
        val dir = scope()
        repeat(ClipboardHistory.CAPACITY + 25) { index ->
            ClipboardHistory.recordText("entry-$index")
        }
        assertEquals(ClipboardHistory.CAPACITY, ClipboardHistory.all().size)
        assertEquals("entry-224", ClipboardHistory.all().first().text)

        // Deliberately an OLD entry, so the restart cannot pass by falling back to "newest".
        val chosen = ClipboardHistory.all().first { it.text == "entry-100" }
        ClipboardHistory.setActive(chosen.id)

        // A second install over the same directory is what a process restart does.
        ClipboardHistory.install(createTempDirectory("unused").toFile())
        ClipboardHistory.install(dir)

        assertEquals(ClipboardHistory.CAPACITY, ClipboardHistory.all().size)
        assertEquals("entry-224", ClipboardHistory.all().first().text)
        assertEquals("entry-100", ClipboardHistory.active()?.text)
    }

    @Test
    fun `removing the active entry falls back to the newest remaining one`() {
        scope()
        ClipboardHistory.recordText("first")
        val second = requireNotNull(ClipboardHistory.recordText("second"))
        ClipboardHistory.setActive(second.id)

        ClipboardHistory.remove(second.id)

        assertEquals("first", ClipboardHistory.active()?.text)
        assertEquals(1, ClipboardHistory.all().size)
    }
}
