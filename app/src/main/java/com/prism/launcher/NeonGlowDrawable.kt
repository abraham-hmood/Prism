package com.prism.launcher

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A custom Drawable that renders a multi-layered neon glow effect.
 * It consists of:
 * 1. A sharp inner stroke ("the gas tube").
 * 2. A vibrant core glow.
 * 3. A soft ambient halo.
 */
class NeonGlowDrawable(
    var color: Int,
    private val cornerRadius: Float,
    private val strokeWidth: Float = 6f
) : Drawable() {

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@NeonGlowDrawable.strokeWidth
        color = this@NeonGlowDrawable.color
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@NeonGlowDrawable.strokeWidth * 1.5f
    }

    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@NeonGlowDrawable.strokeWidth * 2.5f
    }

    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val padding = 20f // Margin for the glow to breathe
        rectF.set(
            bounds.left + padding,
            bounds.top + padding,
            bounds.right - padding,
            bounds.bottom - padding
        )

        // 1. Ambient Halo (Wide and soft)
        ambientPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
        ambientPaint.maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.OUTER)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, ambientPaint)

        // 2. Core Glow (Intense)
        glowPaint.color = color
        glowPaint.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.OUTER)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, glowPaint)

        // 3. Sharp Inner Stroke
        corePaint.color = color
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, corePaint)
    }

    override fun setAlpha(alpha: Int) {
        corePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        corePaint.colorFilter = colorFilter
    }

    // Deprecated on Drawable, and still abstract on it: a Drawable subclass has to implement this
    // whether or not the framework asks for it any more.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
