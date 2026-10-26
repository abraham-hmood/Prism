package com.prism.launcher.nora

import com.prism.core.PrismImage
import com.prism.launcher.aether.AetherTextRenderer

/**
 * Draws a line of text as a [PrismImage] for Nora's retina.
 *
 * Nora has no text pathway -- she is a vision system end to end -- so the only honest way for
 * her to train on a `.txt` or `.parquet` file is to LOOK at it. This reuses Aether's renderer
 * rather than adding a second drawing implementation, which also means a letter has the same
 * shape, weight and size in both brains.
 */
object NoraTextRenderer {

    /** Wider than the fovea, like Aether's reading canvas, so a line keeps its letter shapes. */
    private const val WIDE = 384

    fun render(text: String): PrismImage {
        val width = WIDE
        val height = AetherTextRenderer.SIZE
        val plane = AetherTextRenderer.renderWide(text, width)
        val area = width * height
        val pixels = IntArray(area)
        for (i in 0 until area) {
            val r = plane[i].toInt().coerceIn(0, 255)
            val g = plane[area + i].toInt().coerceIn(0, 255)
            val b = plane[2 * area + i].toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PrismImage(width, height, pixels)
    }
}
