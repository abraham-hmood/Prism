package com.prism.launcher.aether

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Android-side counterpart of `SensoryTokenizer.process_text_visually`'s `cv2.putText` call --
 * the "BIOMIMETIC READING" step (rendering text as an image so the visual cortex, not a token
 * embedding, is what reads it). Not in `:core`: needs `android.graphics`, which this module's
 * boundary deliberately keeps out of `:core` (the same rule Nora's build enforces).
 *
 * Output contract matches [com.prism.launcher.aether.SensoryTokenizer.processTextVisually]: a
 * flat R-plane/G-plane/B-plane array, raw 0..255 values -- the tokenizer does the /255
 * normalization, not this.
 */
object AetherTextRenderer {
    private const val SIZE = 128

    /**
     * Renders a line onto a canvas WIDER than the fovea, for
     * [com.prism.launcher.aether.SensoryTokenizer.processTextSaccadic] to scan across.
     *
     * [render] centres the whole line inside one 128px frame and the tokenizer then holds that
     * frame constant for every timestep, so the input carries no temporal structure at all while
     * the target expects a different character in each slot. Giving the eye somewhere to travel is
     * what makes reading saccadic: the fovea jumps at slot boundaries and fixates within them, so
     * the retina changes exactly when the expected output character changes.
     *
     * Same output contract as [render] -- planar R/G/B, raw 0..255 -- just [canvasW] wide.
     */
    fun renderWide(text: String, canvasW: Int): FloatArray {
        val width = maxOf(canvasW, SIZE)
        val bitmap = Bitmap.createBitmap(width, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = SIZE * 0.5f
            textAlign = Paint.Align.LEFT
        }
        val shown = text.ifBlank { "?" }
        // Shrink to fit the canvas rather than running off the end -- a prompt whose tail is
        // never rendered can never be read, no matter how the eye moves.
        val measured = paint.measureText(shown)
        if (measured > width - 8f) paint.textSize *= (width - 8f) / measured

        val bounds = Rect()
        paint.getTextBounds(shown, 0, shown.length, bounds)
        canvas.drawText(shown, 4f, SIZE / 2f - bounds.exactCenterY(), paint)

        val pixels = IntArray(width * SIZE)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, SIZE)
        bitmap.recycle()

        val plane = width * SIZE
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF).toFloat()
            out[plane + i] = ((p shr 8) and 0xFF).toFloat()
            out[2 * plane + i] = (p and 0xFF).toFloat()
        }
        return out
    }

    fun render(text: String): FloatArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = SIZE * 0.22f
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        val shown = text.ifBlank { "?" }
        paint.getTextBounds(shown, 0, shown.length, bounds)
        canvas.drawText(shown, SIZE / 2f, SIZE / 2f - bounds.exactCenterY(), paint)

        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()

        val plane = SIZE * SIZE
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF).toFloat()
            out[plane + i] = ((p shr 8) and 0xFF).toFloat()
            out[2 * plane + i] = (p and 0xFF).toFloat()
        }
        return out
    }
}
