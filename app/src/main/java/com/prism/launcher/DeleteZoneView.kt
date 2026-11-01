package com.prism.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * An animated delete zone that appears when the user starts dragging a desktop item.
 * Shows a red-glowing rounded rect with a trashcan icon.
 * When an item hovers over it, a red fill animates outward from the hover point.
 */
class DeleteZoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cornerRadius = 24f * resources.displayMetrics.density
    private val strokeWidth = 3f * resources.displayMetrics.density

    // Background fill
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Glowing border
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@DeleteZoneView.strokeWidth
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val trashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var hoverX = 0f
    private var hoverY = 0f
    private var isHovering = false
    private var fillRadius = 0f
    private var fillAnimator: ValueAnimator? = null

    // Glow color cycle animator
    private val glowColors = intArrayOf(
        Color.parseColor("#FF2222"),
        Color.parseColor("#FF6666"),
        Color.parseColor("#FF0000"),
        Color.parseColor("#CC0000"),
        Color.parseColor("#FF2222"),
    )
    private var currentGlowColor = glowColors[0]
    private var glowAnimator: ValueAnimator? = null

    // Normal idle state
    private val idleBgColor = Color.parseColor("#1AFFFFFF")
    private val idleBorderColor = Color.parseColor("#40FFFFFF")

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowAnimator?.cancel()
        glowAnimator = null
        fillAnimator?.cancel()
    }

    private fun startGlowCycle() {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofInt(0, glowColors.size - 1).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            setEvaluator(object : android.animation.TypeEvaluator<Int> {
                val eval = ArgbEvaluator()
                override fun evaluate(fraction: Float, startValue: Int, endValue: Int): Int {
                    val idx = (fraction * (glowColors.size - 1)).toInt().coerceIn(0, glowColors.size - 2)
                    val localFrac = (fraction * (glowColors.size - 1)) - idx
                    return eval.evaluate(localFrac, glowColors[idx], glowColors[idx + 1]) as Int
                }
            })
            addUpdateListener { anim ->
                currentGlowColor = anim.animatedValue as Int
                if (isHovering) invalidate()
            }
            start()
        }
    }

    private var hasAnimatedFill = false

    fun reset() {
        hasAnimatedFill = false
        isHovering = false
        fillRadius = 0f
        fillAnimator?.cancel()
        glowAnimator?.cancel()
        glowAnimator = null
        currentGlowColor = glowColors[0]
        invalidate()
    }

    fun onDragEntered(x: Float, y: Float) {
        isHovering = true
        // ANIMATE ONLY WHILE THE ZONE IS ACTUALLY A TARGET. This used to start an infinite
        // 60 fps animator whenever the view was attached, which the launcher does once and keeps
        // for its whole life -- a permanent wakeup for a control nobody was dragging to.
        if (glowAnimator == null) startGlowCycle()
        if (hasAnimatedFill) {
            invalidate()
            return
        }
        
        hasAnimatedFill = true
        hoverX = x
        hoverY = y
        val maxRadius = Math.sqrt((width * width + height * height).toDouble()).toFloat()
        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, maxRadius).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                fillRadius = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun onDragExited() {
        isHovering = false
        glowAnimator?.cancel()
        glowAnimator = null
        currentGlowColor = glowColors[0]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(strokeWidth, strokeWidth, width - strokeWidth, height - strokeWidth)

        // Background
        bgPaint.color = idleBgColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        // Red fill radiating from hover point
        if (fillRadius > 0f) {
            val fillColor = Color.argb(
                (180 * (fillRadius / Math.sqrt((width * width + height * height).toDouble())).toFloat()).toInt().coerceIn(0, 180),
                220, 30, 30
            )
            val sc = canvas.save()
            val path = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }
            canvas.clipPath(path)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fillColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(hoverX, hoverY, fillRadius, fillPaint)
            canvas.restoreToCount(sc)
        }

        // Glowing border
        borderPaint.color = if (isHovering) currentGlowColor else idleBorderColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Trashcan icon (drawn manually)
        drawTrashcan(canvas)
    }

    private fun drawTrashcan(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val d = resources.displayMetrics.density
        val iconH = minOf(height * 0.6f, 36f * d)
        val iconW = iconH * 0.65f

        // Lid
        canvas.drawLine(cx - iconW * 0.6f, cy - iconH * 0.4f, cx + iconW * 0.6f, cy - iconH * 0.4f, trashPaint)
        // Lid handle
        canvas.drawLine(cx - iconW * 0.2f, cy - iconH * 0.4f, cx - iconW * 0.2f, cy - iconH * 0.55f, trashPaint)
        canvas.drawLine(cx + iconW * 0.2f, cy - iconH * 0.4f, cx + iconW * 0.2f, cy - iconH * 0.55f, trashPaint)
        canvas.drawLine(cx - iconW * 0.2f, cy - iconH * 0.55f, cx + iconW * 0.2f, cy - iconH * 0.55f, trashPaint)
        // Body
        val bodyRect = RectF(cx - iconW * 0.45f, cy - iconH * 0.38f, cx + iconW * 0.45f, cy + iconH * 0.45f)
        canvas.drawRoundRect(bodyRect, 4f * d, 4f * d, trashPaint)
        // Stripes inside
        canvas.drawLine(cx - iconW * 0.15f, cy - iconH * 0.2f, cx - iconW * 0.15f, cy + iconH * 0.3f, trashPaint)
        canvas.drawLine(cx, cy - iconH * 0.2f, cx, cy + iconH * 0.3f, trashPaint)
        canvas.drawLine(cx + iconW * 0.15f, cy - iconH * 0.2f, cx + iconW * 0.15f, cy + iconH * 0.3f, trashPaint)
    }
}
