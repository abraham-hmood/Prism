package com.prism.launcher.photopix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.prism.core.PrismImage
import com.prism.launcher.platform.AndroidImageCodec
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws type with Android's own font stack.
 *
 * The core editor is a plain JVM module and has no fonts, so text placement is a hook it calls.
 * The glyphs are drawn onto a transparent bitmap and the bitmap is copied into a [PrismImage]
 * immediately: nothing above this file ever sees a `Bitmap`, the same rule the image codec follows.
 */
object AndroidPixText : PixTextRasterizer {

    override fun render(text: String, sizePx: Int, color: Int, bold: Boolean, italic: Boolean): PrismImage? {
        if (text.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = sizePx.toFloat().coerceIn(4f, 1024f)
            this.color = color
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }
        val metrics = paint.fontMetrics
        // Two pixels of padding on each side so antialiased edges are not clipped by the bitmap.
        val width = max(1, ceil(paint.measureText(text)).toInt() + 4)
        val height = max(1, ceil(metrics.descent - metrics.ascent).toInt() + 4)
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawText(text, 2f, 2f - metrics.ascent, paint)
                AndroidImageCodec.fromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
