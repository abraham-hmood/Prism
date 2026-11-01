package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.videocraft.VideoCraftStyles
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The look list, and the knob that decides which keyframes survive scoring.
 *
 * Both are inputs to the image model, so both are checked here rather than through either UI: what
 * reaches the model is a phrase and a number, and neither platform changes them.
 */
class VideoCraftStylesTest {

    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(
            rootOverride = java.io.File(
                System.getProperty("java.io.tmpdir"),
                "prism-styles-test-${System.nanoTime()}",
            ).apply { mkdirs() },
        )
        PrismSettings.setVideoCraftVideoType("")
        PrismSettings.setVideoCraftClipThreshold(0.28f)
    }

    @AfterTest
    fun cleanUp() {
        PrismSettings.setVideoCraftVideoType("")
        PrismPlatform.host = previousHost
    }

    @Test
    fun `the catalogue is a hundred looks or more`() {
        assertTrue(
            VideoCraftStyles.types.size >= 100,
            "expected a long list of looks, found ${VideoCraftStyles.types.size}",
        )
    }

    @Test
    fun `the looks the creator asked for by name are in it`() {
        val labels = VideoCraftStyles.types.map { it.label.lowercase() }
        listOf("anime", "realistic", "cinematic", "noir", "watercolour", "stop-motion").forEach { wanted ->
            assertTrue(labels.any { it.contains(wanted) }, "no $wanted in the look list")
        }
    }

    @Test
    fun `none adds nothing, and every other look adds something`() {
        val first = VideoCraftStyles.types.first()
        assertEquals("None", first.label)
        assertEquals("", first.phrase, "the first entry has to be the no-op")
        VideoCraftStyles.types.drop(1).forEach { type ->
            assertTrue(type.phrase.isNotBlank(), "${type.label} has no phrase to send to the model")
        }
    }

    @Test
    fun `no two looks share a label, and an unknown one is silently none`() {
        val labels = VideoCraftStyles.types.map { it.label }
        assertEquals(labels.size, labels.distinct().size, "two looks share a label")
        // A project saved before this list existed names nothing, and one saved with a look this
        // build does not have must still render as itself rather than fail the run.
        assertEquals("", VideoCraftStyles.phraseFor(""))
        assertEquals("", VideoCraftStyles.phraseFor("A Look That Was Removed"))
        assertEquals(
            VideoCraftStyles.types.first { it.label == "Cinematic" }.phrase,
            VideoCraftStyles.phraseFor("Cinematic"),
        )
    }

    @Test
    fun `the chosen look is what a new project is given`() {
        PrismSettings.setVideoCraftVideoType("Cinematic")
        assertEquals("Cinematic", PrismSettings.getVideoCraftVideoType())
    }

    @Test
    fun `the clip threshold is the value the scorer reads, clamped to a score's range`() {
        assertEquals(0.28f, PrismSettings.getVideoCraftClipThreshold(), "the default it ships with")

        PrismSettings.setVideoCraftClipThreshold(0.42f)
        assertEquals(0.42f, PrismSettings.getVideoCraftClipThreshold())

        // A threshold outside 0..1 would either pass every keyframe or none of them; the setting
        // refuses rather than letting a typed 5 quietly stop a film from rendering.
        PrismSettings.setVideoCraftClipThreshold(5f)
        assertEquals(1f, PrismSettings.getVideoCraftClipThreshold())
        PrismSettings.setVideoCraftClipThreshold(-1f)
        assertEquals(0f, PrismSettings.getVideoCraftClipThreshold())
    }
}
