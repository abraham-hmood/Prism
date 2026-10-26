package com.prism.launcher.mouse

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the mouse glow's two halves: the palette store and the little flow language.
 *
 * The failure these guard against is quiet. A flow that compiles but evaluates to a constant
 * draws a still border that looks like a broken animation; a saved style that does not survive a
 * reload simply disappears from the dropdown, which reads as the editor having thrown the work
 * away. Both are asserted here rather than discovered on a phone.
 */
class MouseGlowTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-mouse-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
        MouseGlow.invalidate()
    }

    @AfterTest
    fun restore() {
        MouseGlow.invalidate()
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `the four requested palettes ship`() {
        val ids = MouseGlow.styles().map { it.id }
        assertTrue("rainbow" in ids)
        assertTrue("raven" in ids)
        assertTrue("dark_forest" in ids)
        assertTrue("girlypop" in ids)
    }

    @Test
    fun `a built-in flow actually moves over time`() {
        val style = MouseGlow.style(MouseGlow.STYLE_RAINBOW)
        val flow = MouseGlow.flow(MouseGlow.FLOW_LIQUID)
        val first = MouseGlow.sample(style, flow, 0.3, 0.0)
        val later = MouseGlow.sample(style, flow, 0.3, 1.7)
        assertNotEquals(first.color, later.color)
    }

    @Test
    fun `a custom style survives a reload and can be removed`() {
        val saved = MouseGlow.saveCustomStyle("Synthwave", listOf(0xFFFF5FA2.toInt(), 0xFF8ECBFF.toInt()))
        assertTrue(saved.custom)
        assertEquals(2, saved.colors.size)

        MouseGlow.invalidate()
        val reloaded = MouseGlow.resolveStyle("synthwave")
        assertEquals(saved.id, reloaded?.id)
        assertEquals("Synthwave", reloaded?.name)

        assertTrue(MouseGlow.removeCustomStyle(saved.id))
        MouseGlow.invalidate()
        assertEquals(null, MouseGlow.resolveStyle("Synthwave"))
    }

    @Test
    fun `a custom flow is compiled, stored and selected`() {
        val flow = MouseGlow.saveCustomFlow(
            "Circuit crawl",
            "speed 0.7\noffset u * 2 + sin(t * 0.5) * 0.2\nbrightness 0.8\nbubbles 0",
        )
        assertEquals(flow.id, MouseGlow.selectedFlow().id)
        MouseGlow.invalidate()
        assertEquals(flow.id, MouseGlow.selectedFlow().id)

        val sample = MouseGlow.sample(MouseGlow.style(MouseGlow.STYLE_RAVEN), flow, 0.4, 2.0)
        assertTrue(sample.color != 0)
    }

    @Test
    fun `the script language refuses nonsense with the line number`() {
        val error = assertFailsWith<PrismFlowScript.ScriptException> {
            PrismFlowScript.compile("speed 0.8\nnonsense 4")
        }
        assertEquals(2, error.line)

        assertFailsWith<PrismFlowScript.ScriptException> {
            PrismFlowScript.compile("offset sin(")
        }
        assertFailsWith<PrismFlowScript.ScriptException> {
            PrismFlowScript.compile("# only a comment")
        }
    }

    @Test
    fun `bubbles stay bounded for the lava lamp flow`() {
        val flow = MouseGlow.flow(MouseGlow.FLOW_LAVA_LAMP)
        val style = MouseGlow.style(MouseGlow.STYLE_DARK_FOREST)
        for (step in 0..60) {
            val sample = MouseGlow.sample(style, flow, step / 60.0, step * 0.1)
            assertTrue(sample.bubbles in 0.0..1.0)
            assertTrue(sample.bubbleSize in PrismFlowScript.MIN_BUBBLE_SIZE..PrismFlowScript.MAX_BUBBLE_SIZE)
            assertTrue(sample.bubbleSpeed in PrismFlowScript.MIN_BUBBLE_SPEED..PrismFlowScript.MAX_BUBBLE_SPEED)
        }
    }
}
