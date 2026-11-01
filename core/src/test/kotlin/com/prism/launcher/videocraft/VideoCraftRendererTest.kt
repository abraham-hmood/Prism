package com.prism.launcher.videocraft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The procedural renderer, which is the engine a film falls back to when nothing else can draw.
 *
 * ## Why the palette lengths are the test
 *
 * It reads four colours -- two sky tones, a mid tone and the ground -- and its guard used to be
 * `if (palette.size >= 3) palette else defaultPalette(seed)`. The story bible's schema asks a model
 * for three colours, so the guard passed exactly the palette that then threw
 * `IndexOutOfBoundsException` on `colors[3]`, and it threw inside the one renderer that exists so a
 * film still finishes when no model can answer. On a phone with no local checkpoint that killed the
 * render during its first reference image, under a stage line that says "Reference image 1/2", so
 * the failure looked like a stall.
 *
 * A renderer that must never fail is worth a test that calls it with every palette a model can
 * plausibly hand it: none, one, two, three, and enough.
 */
class VideoCraftRendererTest {

    private val palettes = listOf(
        "no palette" to emptyList(),
        "one colour" to listOf(0xFF203040.toInt()),
        "two colours" to listOf(0xFF203040.toInt(), 0xFFB06030.toInt()),
        // THREE is the case the schema asked for, and the one that used to throw.
        "three colours" to listOf(0xFF203040.toInt(), 0xFFB06030.toInt(), 0xFF60A0C0.toInt()),
        "four colours" to listOf(0xFF203040.toInt(), 0xFFB06030.toInt(), 0xFF60A0C0.toInt(), 0xFFE0D0B0.toInt()),
        "six colours" to (0 until 6).map { 0xFF102030.toInt() + it * 0x203040 },
    )

    @Test
    fun `renders every palette a model can hand it`() {
        palettes.forEach { (label, palette) ->
            val image = VideoCraftRenderer.render(
                prompt = "a traveller crosses a ridge at dawn",
                width = 48,
                height = 27,
                palette = palette,
                seed = 7L,
            )
            assertEquals(48, image.width, "$label: width")
            assertEquals(27, image.height, "$label: height")
            assertTrue(image.pixels.size == 48 * 27, "$label: the frame must be fully written")
            assertTrue(
                image.pixels.all { it != 0 },
                "$label: an untouched pixel means a band of the frame was never painted",
            )
        }
    }

    @Test
    fun `the prompt's subjects change the picture without a palette`() {
        // Night, water and a person each draw a different layer; none of them may depend on the
        // palette being a particular length.
        val night = VideoCraftRenderer.render("night over the sea", 32, 18, emptyList(), 3L)
        val day = VideoCraftRenderer.render("dawn over the sea", 32, 18, emptyList(), 3L)
        assertTrue(
            night.pixels.toList() != day.pixels.toList(),
            "two different prompts produced the same frame",
        )
    }
}
