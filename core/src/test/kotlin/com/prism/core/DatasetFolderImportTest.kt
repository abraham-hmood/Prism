package com.prism.core

import com.prism.launcher.modelcraft.ModelCraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading a picked folder's CSV into the list of files to copy.
 *
 * The bug this covers: the phone staged a CSV on its own in a flat cache folder and resolved its rows
 * against that folder, so every `clips/...` path in it was "missing" even though the clips were right
 * there in the folder the user had picked. These tests exercise the listing-based resolution on the
 * layouts real manifests use, without a device or a storage picker.
 */
class DatasetFolderImportTest {

    @Test
    fun `rows resolve media under the CSV's own subfolders`() {
        val csv = "path,transcript\nclips/take 1.wav,hello\nclips/take-2.wav,goodbye\n"
        val entries = listOf(
            ModelCraft.PickedEntry("voice/metadata.csv", "metadata.csv"),
            ModelCraft.PickedEntry("voice/clips/take 1.wav", "take 1.wav"),
            ModelCraft.PickedEntry("voice/clips/take-2.wav", "take-2.wav"),
            ModelCraft.PickedEntry("voice/unused.wav", "unused.wav"),
        )

        val plan = ModelCraft.planFolderImport(entries) { csv }

        assertEquals(
            setOf("voice/metadata.csv", "voice/clips/take 1.wav", "voice/clips/take-2.wav"),
            plan.files.toSet(),
        )
        assertEquals(1, plan.csvs)
        assertEquals(2, plan.rows)
        assertEquals(0, plan.missing)
        assertTrue(
            "voice/unused.wav" !in plan.files,
            "a file the CSV did not name was imported",
        )
    }

    @Test
    fun `a path written on another machine matches its tail in the listing`() {
        val csv = "file,caption\nC:\\Users\\ada\\dataset\\clips\\a.png,a cat\n"
        val entries = listOf(
            ModelCraft.PickedEntry("photos/metadata.csv", "metadata.csv"),
            ModelCraft.PickedEntry("photos/clips/a.png", "a.png"),
        )

        val plan = ModelCraft.planFolderImport(entries) { csv }

        assertEquals(
            setOf("photos/metadata.csv", "photos/clips/a.png"),
            plan.files.toSet(),
        )
        assertEquals(0, plan.missing)
    }

    @Test
    fun `a bare file name is found in a nested folder`() {
        val csv = "path,caption\na.png,first\n"
        val entries = listOf(
            ModelCraft.PickedEntry("set/metadata.csv", "metadata.csv"),
            ModelCraft.PickedEntry("set/images/2024/a.png", "a.png"),
        )

        val plan = ModelCraft.planFolderImport(entries) { csv }

        assertTrue("set/images/2024/a.png" in plan.files)
        assertEquals(0, plan.missing)
    }

    @Test
    fun `referenced files that are not in the folder are counted, not invented`() {
        val csv = "path,text\nspeech/gone.wav,hello\n"
        val entries = listOf(
            ModelCraft.PickedEntry("metadata.csv", "metadata.csv"),
            ModelCraft.PickedEntry("speech/here.wav", "here.wav"),
        )

        val plan = ModelCraft.planFolderImport(entries) { csv }

        assertEquals(listOf("metadata.csv"), plan.files)
        assertEquals(1, plan.missing)
        assertTrue("speech/here.wav" !in plan.files)
    }

    @Test
    fun `a folder with no CSV is imported wholesale`() {
        val entries = listOf(
            ModelCraft.PickedEntry("a.png", "a.png"),
            ModelCraft.PickedEntry("nested/b.png", "b.png"),
            ModelCraft.PickedEntry(".hidden.png", ".hidden.png"),
        )

        val plan = ModelCraft.planFolderImport(entries) { null }

        assertEquals(0, plan.csvs)
        assertEquals(0, plan.rows)
        assertEquals(setOf("a.png", "nested/b.png"), plan.files.toSet())
    }

    @Test
    fun `a text corpus is imported whole even when a CSV sits beside it`() {
        val entries = listOf(
            ModelCraft.PickedEntry("corpus/chapter-1.txt", "chapter-1.txt"),
            ModelCraft.PickedEntry("corpus/chapter-2.txt", "chapter-2.txt"),
            ModelCraft.PickedEntry("corpus/prompts.csv", "prompts.csv"),
        )

        val plan = ModelCraft.planFolderImport(entries, csvIsManifest = false) { null }

        assertEquals(0, plan.csvs)
        assertEquals(entries.map { it.path }.toSet(), plan.files.toSet())
    }

    @Test
    fun `a CSV with a byte order mark and quoted commas is still read`() {
        val rows = ModelCraft.parseCsvText(
            "\uFEFFpath,caption\r\ncat.png,\"a cat, sitting\"\r\n",
        )

        val row = rows.single()
        assertEquals("cat.png", row["path"])
        assertEquals("a cat, sitting", row["caption"])
    }

    @Test
    fun `a semicolon CSV with a multiline description is read as named columns`() {
        val rows = ModelCraft.parseCsvText(
            "file;description\r\nclip1.wav;\"hello,\r\nthis is a described take\"\r\n",
        )

        val row = rows.single()
        assertEquals("clip1.wav", row["file"])
        assertEquals("hello,\r\nthis is a described take", row["description"])
    }
}
