package com.prism.desktop.ui

import com.prism.core.PrismImage
import com.prism.launcher.photopix.PixTextRasterizer
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max

/**
 * Type for PhotoPix on the desktop, drawn with Java2D.
 *
 * The editor itself is in `:core` and has no font stack, so text placement is a hook. Android
 * fills it with `Canvas.drawText`; this is the same job through `Graphics2D`. The glyphs land on a
 * transparent `BufferedImage` which is copied into a [PrismImage] immediately, so nothing above
 * this file ever sees an AWT image -- the same boundary the image codec keeps.
 */
object DesktopPixText : PixTextRasterizer {

    /** The fonts this machine actually has, best first, for a serif-ish editor default. */
    private val family: String by lazy {
        val available = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        listOf("Georgia", "Times New Roman", "DejaVu Serif", "Liberation Serif", "Dialog")
            .firstOrNull { it == "Dialog" || available.contains(it) }
            ?: "Dialog"
    }

    override fun render(text: String, sizePx: Int, color: Int, bold: Boolean, italic: Boolean): PrismImage? {
        if (text.isBlank()) return null
        return try {
            val style = (if (bold) Font.BOLD else Font.PLAIN) or (if (italic) Font.ITALIC else Font.PLAIN)
            val font = Font(family, style, sizePx.coerceIn(4, 1024))
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val measure = probe.createGraphics()
            val metrics = try {
                measure.font = font
                measure.fontMetrics
            } finally {
                measure.dispose()
            }
            val width = max(1, ceil(metrics.stringWidth(text).toDouble()).toInt() + 4)
            val height = max(1, metrics.height + 4)

            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.font = font
                g.color = java.awt.Color(color, true)
                g.drawString(text, 2, 2 + metrics.ascent)
            } finally {
                g.dispose()
            }

            val pixels = IntArray(width * height)
            canvas.getRGB(0, 0, width, height, pixels, 0, width)
            PrismImage(width, height, pixels)
        } catch (t: Throwable) {
            null
        }
    }
}
