package com.prism.launcher.videocraft

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A plan that repeats the schema back is not a plan.
 *
 * The reply below is what DeepSeek R1 Distill 1.5B actually wrote for a VideoCraft bible: valid JSON, so
 * it used to be accepted, and the film was drawn in the style "visual style, lens, lighting, colour
 * treatment" at a location called "short name: beach".
 */
class VideoCraftPlannerEchoTest {

    private lateinit var root: java.io.File

    @BeforeTest
    fun host() {
        root = Files.createTempDirectory("prism-planner-echo").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = JvmHost()
        root.deleteRecursively()
    }

    private val project = VideoCraftProject(prompt = "A woman waves hello to her friends on a sunny beach")

    @Test
    fun `an echoed style is not taken as the film's style`() {
        val echoed = """{"logline": "A woman waves hello to her friends on a sunny beach.",
            "style": "visual style, lens, lighting, colour treatment",
            "palette": ["#RRGGBB"],
            "characters": [{"name": "Lara", "description": "fixed visual description: age, build, face, hair, clothing, distinguishing features"}],
            "locations": [{"name": "short name: beach", "description": "white sand and bright sun"}]}"""
        val bible = VideoCraftPlanner.bible(project) { _, _, _, _, _ -> echoed }
        assertNotEquals("visual style, lens, lighting, colour treatment", bible.style)
        assertEquals(VideoCraftPlanner.fallbackBible(project).style, bible.style)
    }

    @Test
    fun `a placeholder written as a label is stripped and the value kept`() {
        val labelled = """{"logline": "A greeting on the sand.",
            "style": "bright handheld 35mm, warm afternoon light",
            "characters": [{"name": "Lara", "description": "a tall woman in a red swimsuit"}],
            "locations": [{"name": "short name: beach", "description": "white sand and bright sun"}]}"""
        val bible = VideoCraftPlanner.bible(project) { _, _, _, _, _ -> labelled }
        assertEquals("bright handheld 35mm, warm afternoon light", bible.style)
        assertEquals("beach", bible.locations.single().name)
    }
}
