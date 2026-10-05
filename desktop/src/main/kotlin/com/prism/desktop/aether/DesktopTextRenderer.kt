package com.prism.desktop.aether

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Text as pixels, for Aether to look at. The one genuinely platform-specific piece of PHASE 76.
 *
 * ## Why Aether reads a picture rather than a string
 *
 * Aether is a spiking network with a retina. It has no token embeddings and no vocabulary lookup — a
 * prompt reaches it the same way anything else does, as light. So a prompt has to be DRAWN, and the
 * drawing is the only part of Aether that cannot be shared between platforms: Android has Canvas and
 * Paint, a desktop has Graphics2D.
 *
 * ## Why this matches the Android renderer exactly
 *
 * Same canvas size, same black background, same bold sans-serif, same text sizes, same planar R/G/B
 * output in raw 0..255. A CONNECTOME TRAINED ON ONE MUST READ ON THE OTHER: the weights are shared
 * through the knowledge-sync, and a brain that learned letters at one size and stroke weight would see
 * noise if the other platform drew them differently. The numbers here are copied from
 * `AetherTextRenderer` for that reason and should not be "improved" independently.
 */
object DesktopTextRenderer {

    /**
     * The retina's edge, in pixels.
     *
     * A CONSTANT, AND THE SAME ONE THE ANDROID RENDERER USES. It is not read from the geometry because
     * the geometry is resizable and the RENDERING is not: a connectome trained on letters drawn at 128
     * pixels has learned those stroke widths, and drawing them at another size would make a shared
     * connectome read noise. See AetherTextRenderer, which holds the same number for the same reason.
     */
    private const val size: Int = 128

    /**
     * A prompt on a wide canvas, for saccadic reading.
     *
     * WIDE ON PURPOSE: reading is saccadic because the fovea jumps between slots and fixates inside
     * them, so the retina changes exactly when the expected output character changes. A square canvas
     * with the whole prompt shrunk into it would give the eye nothing to move across.
     */
    fun renderWide(text: String, canvasWidth: Int): FloatArray {
        val edge = size
        val width = maxOf(canvasWidth, edge)
        val image = BufferedImage(width, edge, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, edge)
        graphics.color = Color.WHITE

        val shown = text.ifBlank { "?" }
        var pointSize = edge * 0.5f
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, pointSize.toInt().coerceAtLeast(1))

        // Shrunk to fit rather than run off the end: a prompt whose tail is never drawn can never be
        // read, however the eye moves.
        val measured = graphics.fontMetrics.stringWidth(shown)
        if (measured > width - 8) {
            pointSize *= (width - 8f) / measured
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, pointSize.toInt().coerceAtLeast(1))
        }

        val metrics = graphics.fontMetrics
        // Centred on the ascent rather than on the font's full height, so the visible glyphs sit in
        // the middle of the retina -- which is where the Android renderer puts them.
        val baseline = edge / 2 + (metrics.ascent - metrics.descent) / 2
        graphics.drawString(shown, 4, baseline)
        graphics.dispose()

        return planar(image, width, edge)
    }

    /** A prompt centred on the square retina. */
    fun render(text: String): FloatArray {
        val edge = size
        val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, edge, edge)
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, (edge * 0.22f).toInt().coerceAtLeast(1))

        val shown = text.ifBlank { "?" }
        val metrics = graphics.fontMetrics
        val x = (edge - metrics.stringWidth(shown)) / 2
        val baseline = edge / 2 + (metrics.ascent - metrics.descent) / 2
        graphics.drawString(shown, x, baseline)
        graphics.dispose()

        return planar(image, edge, edge)
    }

    /**
     * Planar R, then G, then B, raw 0..255.
     *
     * PLANAR RATHER THAN INTERLEAVED because that is what the retina expects: each colour channel
     * feeds its own bank of photoreceptors, so the three planes are read as three images rather than
     * as one image of triples.
     */
    private fun planar(image: BufferedImage, width: Int, height: Int): FloatArray {
        val plane = width * height
        val pixels = IntArray(plane)
        image.getRGB(0, 0, width, height, pixels, 0, width)

        val out = FloatArray(plane * 3)
        for (index in 0 until plane) {
            val pixel = pixels[index]
            out[index] = ((pixel shr 16) and 0xFF).toFloat()
            out[plane + index] = ((pixel shr 8) and 0xFF).toFloat()
            out[2 * plane + index] = (pixel and 0xFF).toFloat()
        }
        return out
    }
}
