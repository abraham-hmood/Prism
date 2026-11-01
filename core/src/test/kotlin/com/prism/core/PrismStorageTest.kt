package com.prism.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The user-visible Prism folder can move without taking the data with it.
 *
 * The failure this guards against is not a path string changing; it is half a library ending up in
 * each of two folders. The assertions therefore check the file contents, the old folder, and the
 * host's answer after the move, not only the setting.
 */
class PrismStorageTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-storage-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
    }

    @AfterTest
    fun cleanUp() {
        PrismStorage.clearRoot()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `moving copies the tree, switches the host and removes the old copy`() {
        val before = PrismPlatform.host.documentsDir()
        File(before, "Models").mkdirs()
        File(before, "Models/model.gguf").writeText("pretend weights")
        File(before, "Editor/project").mkdirs()
        File(before, "Editor/project/notes.txt").writeText("chapter one")
        File(before, "empty-folder").mkdirs()

        val after = File(scratch, "elsewhere/PrismData")
        val result = PrismStorage.relocate(after)

        assertTrue(result.ok, result.message)
        assertEquals("pretend weights", File(after, "Models/model.gguf").readText())
        assertEquals("chapter one", File(after, "Editor/project/notes.txt").readText())
        assertTrue(File(after, "empty-folder").isDirectory, "empty folders are part of the shape")
        assertEquals(
            after.canonicalFile,
            PrismPlatform.host.documentsDir().canonicalFile,
            "the host must resolve the new folder without a restart",
        )
        assertFalse(before.exists(), "the old copy must be gone once the new one is complete")
        assertEquals(after.canonicalFile, PrismStorage.configuredRoot()?.canonicalFile)
    }

    @Test
    fun `a destination with a different file of the same name is refused, not overwritten`() {
        val before = PrismPlatform.host.documentsDir()
        File(before, "Models").mkdirs()
        File(before, "Models/model.gguf").writeText("the real model")

        val after = File(scratch, "taken")
        File(after, "Models").mkdirs()
        File(after, "Models/model.gguf").writeText("somebody else's file")

        val result = PrismStorage.relocate(after)

        assertFalse(result.ok)
        assertTrue(result.message.contains("nothing was switched"), result.message)
        assertEquals("somebody else's file", File(after, "Models/model.gguf").readText())
        assertEquals("the real model", File(before, "Models/model.gguf").readText())
        assertEquals(
            before.canonicalFile,
            PrismPlatform.host.documentsDir().canonicalFile,
            "a refused move must not change where Prism reads",
        )
    }

    @Test
    fun `absolute paths in settings move with the folder, and a lookalike sibling does not`() {
        val before = PrismPlatform.host.documentsDir()
        File(before, "Models").mkdirs()
        File(before, "Models/model.gguf").writeText("weights")
        val sibling = File(before.parentFile, before.name + "2").absolutePath
        val pathKey = "imported_models"
        PrismPlatform.host.prefs(PrismStorage.APP_PREFS).putString(
            pathKey,
            before.absolutePath + "/Models/model.gguf|$sibling/keep.gguf",
        )

        val after = File(scratch, "relocated")
        assertTrue(PrismStorage.relocate(after).ok)

        val stored = PrismPlatform.host.prefs(PrismStorage.APP_PREFS).getString(pathKey, "").orEmpty()
        assertTrue(stored.startsWith(after.absolutePath), "the model path should follow the folder: $stored")
        assertTrue(
            stored.endsWith("$sibling/keep.gguf"),
            "a directory whose name only starts with the old one must not be rewritten: $stored",
        )
    }

    @Test
    fun `project files that carry their own absolute paths are updated in the new folder`() {
        val before = PrismPlatform.host.documentsDir()
        val project = File(before, "VideoCraft/pilot/project.json")
        project.parentFile.mkdirs()
        val escaped = before.absolutePath.replace("\\", "\\\\")
        project.writeText("""{"outputPath":"$escaped/out.mp4"}""")

        val after = File(scratch, "moved-projects")
        assertTrue(PrismStorage.relocate(after).ok)

        val written = File(after, "VideoCraft/pilot/project.json").readText()
        val expected = after.absolutePath.replace("\\", "\\\\")
        assertTrue(
            written.contains(expected + "/out.mp4"),
            "the project should point at the moved output, not the deleted folder: $written",
        )
    }

    @Test
    fun `a folder inside the current one is refused before anything is copied`() {
        val before = PrismPlatform.host.documentsDir()
        val nested = File(before, "next-home")
        val result = PrismStorage.relocate(nested)

        assertFalse(result.ok)
        assertFalse(nested.exists(), "the refused destination must not be created")
    }
}
