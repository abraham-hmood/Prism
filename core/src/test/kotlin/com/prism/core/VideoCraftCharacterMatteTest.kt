package com.prism.launcher.videocraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The character matte, the placement lattice and the compositor.
 *
 * These are the parts of "characters appear in the film" that can be checked without a model: given a
 * synthetic reference sheet, does the matte find the figure, does the compositor put it where the
 * placement says, does the colour transfer move a studio-lit figure towards a night scene's colours,
 * and does the whole thing stay deterministic so a resumed render is not visibly different from an
 * uninterrupted one.
 */
class VideoCraftCharacterMatteTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-matte-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    /** A grey studio backdrop with an opaque red figure standing in the middle of it. */
    private fun reference(width: Int = 96, height: Int = 128): PrismImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inFigure = x in (width / 3) until (width * 2 / 3) && y in (height / 4) until height
                val inside = inFigure && !(x in (width / 2 - 3) until (width / 2 + 3) && y in (height / 2) until (height / 2 + 4))
                pixels[y * width + x] = if (inside) {
                    PrismImage.argb(200, 60, 40)
                } else if (inFigure) {
                    // A background-coloured patch fully enclosed by the figure: an eye, a button.
                    PrismImage.argb(128, 128, 128)
                } else {
                    PrismImage.argb(128, 128, 128)
                }
            }
        }
        return PrismImage(width, height, pixels)
    }

    private fun opaque(image: PrismImage, x: Int, y: Int): Int =
        (image.pixel(x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1)) ushr 24) and 0xFF

    /** How many pixels differ between [a] and [b] outside [box]. */
    private fun changedOutside(a: PrismImage, b: PrismImage, box: VideoCraftCharacterMatte.FigureBox): Int {
        var changed = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val inBox = x >= box.x && x < box.x + box.width && y >= box.y && y < box.y + box.height
                if (!inBox && a.pixel(x, y) != b.pixel(x, y)) changed++
            }
        }
        return changed
    }

    @Test
    fun `the matte keeps the figure and drops the backdrop`() {
        val cut = VideoCraftCharacterMatte.cutout(reference())
        assertEquals(255, opaque(cut, cut.width / 2, cut.height * 3 / 4), "the figure should be opaque")
        assertEquals(0, opaque(cut, 2, 2), "the backdrop should be transparent")
        assertEquals(0, opaque(cut, cut.width - 3, cut.height - 3), "the backdrop should be transparent")
    }

    @Test
    fun `enclosed background pixels inside the figure are filled`() {
        val cut = VideoCraftCharacterMatte.cutout(reference())
        // The patch at (width/3 .. width/2) inside the figure is background-coloured, and a hole there
        // would be a window onto the scene behind the character rather than a feature on them.
        assertEquals(
            255,
            opaque(cut, cut.width / 2, cut.height / 2 + 1),
            "a hole enclosed by the figure must be filled",
        )
    }

    @Test
    fun `a reference with no separable subject still produces a cutout`() {
        val flat = PrismImage(64, 64, IntArray(64 * 64) { PrismImage.argb(90, 90, 90) })
        val cut = VideoCraftCharacterMatte.cutout(flat)
        var covered = 0
        for (p in cut.pixels) if (((p ushr 24) and 0xFF) > 128) covered++
        val fraction = covered.toDouble() / cut.pixels.size
        // Not empty (the character would vanish) and not the whole frame (the frame would be covered
        // in a flat grey rectangle). The ellipse lands well inside those bounds.
        assertTrue(fraction > 0.1, "expected a figure-shaped keep, got $fraction")
        assertTrue(fraction < 0.9, "expected a figure-shaped keep, got $fraction")
    }

    @Test
    fun `placements are deterministic and inside the frame`() {
        val first = VideoCraftCharacterMatte.placements(3, seed = 42L)
        val again = VideoCraftCharacterMatte.placements(3, seed = 42L)
        assertEquals(first, again)
        for (placement in first) {
            assertTrue(placement.x in 0.0..1.0, "x out of frame: ${placement.x}")
            assertTrue(placement.heightFraction > 0.2, "figure too small: ${placement.heightFraction}")
        }
        // Three people are spread out rather than stacked on the centre line.
        assertTrue(first[0].x < first[1].x && first[1].x < first[2].x)
    }

    @Test
    fun `the compositor puts the figure where the placement says`() {
        val cut = VideoCraftCharacterMatte.cutout(reference())
        val frame = PrismImage(160, 96, IntArray(160 * 96) { PrismImage.argb(10, 20, 40) })
        val placement = VideoCraftCharacterMatte.Placement(x = 0.3, y = 0.6, heightFraction = 0.7)
        val box = VideoCraftCharacterMatte.boxFor(frame.width, frame.height, cut.width, cut.height, placement)
        val composed = VideoCraftCharacterMatte.composite(frame, cut, placement)

        var changed = 0
        for (y in box.y until box.y + box.height) {
            for (x in box.x until box.x + box.width) {
                if (composed.pixel(x, y) != frame.pixel(x, y)) changed++
            }
        }
        // The figure fills about a quarter of the box it is scaled into (a body, not a rectangle), so
        // this catches both failure modes: a matte that removed the figure entirely, and an alpha-blind
        // resize that painted the reference's studio backdrop over every pixel of the box.
        assertTrue(
            changed > box.width * box.height / 5,
            "expected the figure to change much of its box, changed $changed of ${box.width * box.height}",
        )
        // Outside the box the only thing that may change is the contact shadow, which is a thin ellipse
        // deliberately wider than the figure's feet. A blurred rectangle of painted backdrop would be
        // tens of thousands of pixels, so this bound separates the two.
        val outside = changedOutside(composed, frame, box)
        assertTrue(outside <= frame.width, "only the shadow may reach beyond the box, changed $outside")
        // Well away from the figure the frame is untouched.
        assertEquals(frame.pixel(150, 5), composed.pixel(150, 5))
    }

    @Test
    fun `the composited figure takes on the frame's colour cast`() {
        val cut = VideoCraftCharacterMatte.cutout(reference())
        val neutral = PrismImage(160, 96, IntArray(160 * 96) { PrismImage.argb(128, 128, 128) })
        val blue = PrismImage(160, 96, IntArray(160 * 96) { PrismImage.argb(40, 60, 140) })
        val placement = VideoCraftCharacterMatte.Placement(x = 0.5, y = 0.6, heightFraction = 0.7)

        /** Mean RGB of the composed figure's pixels over the region it was drawn into. */
        fun figureMean(frame: PrismImage): DoubleArray {
            val box = VideoCraftCharacterMatte.boxFor(
                frame.width, frame.height, cut.width, cut.height, placement,
            )
            val composed = VideoCraftCharacterMatte.composite(frame, cut, placement)
            val sums = DoubleArray(3)
            var n = 0
            for (y in box.y until box.y + box.height) {
                for (x in box.x until box.x + box.width) {
                    if (composed.pixel(x, y) == frame.pixel(x, y)) continue
                    val p = composed.pixel(x, y)
                    sums[0] += (p shr 16) and 0xFF
                    sums[1] += (p shr 8) and 0xFF
                    sums[2] += p and 0xFF
                    n++
                }
            }
            return DoubleArray(3) { if (n == 0) 0.0 else sums[it] / n }
        }

        val onNeutral = figureMean(neutral)
        val onBlue = figureMean(blue)
        // The same figure, on a blue-lit frame, must come out bluer and less red than it does on a grey
        // one. That is the difference between standing in a scene and being pasted into it.
        assertTrue(
            onBlue[2] > onNeutral[2] + 8,
            "expected the blue-cast frame to pull the figure bluer: ${onNeutral[2]} -> ${onBlue[2]}",
        )
        assertTrue(
            onBlue[0] < onNeutral[0] - 8,
            "expected the blue-cast frame to pull the figure's red down: ${onNeutral[0]} -> ${onBlue[0]}",
        )
    }

    @Test
    fun `the starter frame keeps the figure distinguishable from the backdrop`() {
        val cut = VideoCraftCharacterMatte.cutout(reference())
        val placement = VideoCraftCharacterMatte.Placement(x = 0.5, y = 0.6, heightFraction = 0.7)
        val palette = VideoCraftPlanner.fallbackPalette(3L)
        val starter = VideoCraftCharacterMatte.starterFrame(
            backdrop = PrismImage(160, 96, IntArray(160 * 96) { PrismImage.argb(20, 20, 20) }),
            cutouts = listOf(cut to placement),
            width = 160,
            height = 96,
            palette = palette,
            seed = 11L,
        )
        val box = VideoCraftCharacterMatte.boxFor(160, 96, cut.width, cut.height, placement)
        val backdropSource = PrismImage(160, 96, IntArray(160 * 96) { PrismImage.argb(20, 20, 20) })
        var changed = 0
        for (y in box.y until box.y + box.height) {
            for (x in box.x until box.x + box.width) {
                if (starter.pixel(x, y) != backdropSource.pixel(x, y)) changed++
            }
        }
        // The init the sampler is handed is a *suggestion*: the figure must be visible in it without
        // being so solid that a low-denoise pass hands the cutout straight back. Fading to just under
        // full opacity is the middle ground, so most of the figure's box differs from the backdrop.
        assertTrue(
            changed > box.width * box.height / 5,
            "expected the figure to be visible in the init frame, changed $changed",
        )
        assertTrue(VideoCraftCharacterMatte.looksCutOut(cut), "a cutout must read as a cutout")
    }
}
