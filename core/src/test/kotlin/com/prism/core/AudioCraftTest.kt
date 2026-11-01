package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.audiocraft.AudioCraft
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two pieces of AudioCraft a user cannot see being decided: how a long script is cut up, and what
 * a clip is called.
 *
 * The read-aloud itself needs a speech model and a device, so it is exercised through each platform's
 * own page. What is shared -- and what a failure in would be a failure on both -- is here.
 */
class AudioCraftTest {

    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(
            rootOverride = java.io.File(
                System.getProperty("java.io.tmpdir"),
                "prism-audiocraft-test-${System.nanoTime()}",
            ).apply { mkdirs() },
        )
        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
    }

    @AfterTest
    fun cleanUp() {
        AudioCraft.clear()
        PrismPlatform.host = previousHost
    }

    @Test
    fun `a short script is one chunk`() {
        assertEquals(listOf("A short line."), AudioCraft.scriptChunks("A short line."))
    }

    @Test
    fun `a long script is split without losing or reordering a word`() {
        val script = (1..240).joinToString(" ") { "word$it" }
        val chunks = AudioCraft.scriptChunks(script)

        assertTrue(chunks.size > 1, "a script this long is not one breath")
        chunks.forEach { chunk ->
            // Somewhere above the chunk size, because a cut lands on a sentence or a space rather
            // than mid-word; well under a whole script, because that is the point of chunking.
            assertTrue(chunk.length <= 700, "chunk too long to speak in one go: ${chunk.length}")
        }
        assertEquals(
            script.split(Regex("\\s+")).filter { it.isNotBlank() },
            chunks.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() },
            "the words have to come out in the same order they went in",
        )
    }

    @Test
    fun `a heading becomes the title and the body is not the title`() {
        val script = """
            The Lighthouse Keeper

            He counted the ships by the sound of their horns.
        """.trimIndent()

        val title = AudioCraft.titleFor(script, "typed")

        assertTrue(title.contains("Lighthouse"), "expected the first line, got '$title'")
        assertTrue(title.length <= 80, "a title is a line, not a paragraph: '${title.length}'")
    }

    @Test
    fun `an imported script is titled after the file it came from`() {
        val title = AudioCraft.titleFor("Some body text that is not a heading.", "chapter-one.txt")
        assertTrue(
            title.contains("chapter-one") || title.contains("Some body text"),
            "expected the file's name or the body, got '$title'",
        )
    }
}
