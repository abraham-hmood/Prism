package com.prism.launcher.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import java.util.Random
import kotlin.math.hypot
import kotlin.math.min

/**
 * Fusion's ground, painted instead of shipped.
 *
 * ## Why procedural
 *
 * The reference is a nebula photograph with a device in front of it. A theme cannot ship that
 * photograph: it would have to exist at several densities, it would band on a large screen, and it
 * is somebody else's phone rather than the user's interface. Four radial gradients and a fixed
 * star field reproduce the LIGHT -- the violet upper field, the cyan right-hand glow, the magenta
 * centre and the amber lower corner with a dark ground plane -- at any resolution, for the cost of
 * one shader set per size.
 *
 * The stars are seeded, not random per frame: a static page must not boil.
 */
class NebulaDrawable(
    private val ground: Int,
    private val dark: Boolean,
) : Drawable() {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var glows: List<Pair<Shader, PointF>> = emptyList()
    private var stars: List<Triple<Float, Float, Float>> = emptyList()
    private var bottomEdge = 0f

    override fun onBoundsChange(bounds: Rect) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        val span = maxOf(width, height)
        bottomEdge = bounds.top + height * 0.62f

        val violet = tint(0x6B3FA0, if (dark) 0.55f else 0.30f)
        val cyan = tint(0x1FA9D9, if (dark) 0.34f else 0.22f)
        val magenta = tint(0x8A3FA8, if (dark) 0.30f else 0.18f)
        val amber = tint(0xB06B2A, if (dark) 0.20f else 0.16f)
        glows = listOf(
            radial(bounds.left + width * 0.22f, bounds.top + height * 0.10f, span * 0.62f, violet) to
                PointF(bounds.left + width * 0.22f, bounds.top + height * 0.10f),
            radial(bounds.left + width * 0.84f, bounds.top + height * 0.24f, span * 0.55f, cyan) to
                PointF(bounds.left + width * 0.84f, bounds.top + height * 0.24f),
            radial(bounds.left + width * 0.60f, bounds.top + height * 0.46f, span * 0.50f, magenta) to
                PointF(bounds.left + width * 0.60f, bounds.top + height * 0.46f),
            radial(bounds.left + width * 0.12f, bounds.top + height * 0.86f, span * 0.38f, amber) to
                PointF(bounds.left + width * 0.12f, bounds.top + height * 0.86f),
        )

        // A fixed seed: the same sky every time this drawable is created, so switching screens
        // does not rearrange the stars.
        val random = Random(0x5EED)
        val count = if (dark) 110 else 0
        stars = List(count) {
            Triple(
                bounds.left + random.nextFloat() * width,
                bounds.top + random.nextFloat() * height,
                0.5f + random.nextFloat() * 1.3f,
            )
        }

        vignettePaint.shader = LinearGradient(
            0f, bottomEdge, 0f, bounds.top + height,
            Color.TRANSPARENT,
            if (dark) 0xFF02030A.toInt() else Color.argb(150, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        groundPaint.color = ground
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, groundPaint)
        glows.forEach { (shader, centre) ->
            glowPaint.shader = shader
            canvas.drawCircle(centre.x, centre.y, maxOf(bounds.width(), bounds.height()) * 0.7f, glowPaint)
        }
        val starColor = if (dark) 0xFFEAF6FF.toInt() else Color.WHITE
        stars.forEach { (x, y, radius) ->
            starPaint.color = starColor
            starPaint.alpha = 90 + (radius * 40).toInt()
            canvas.drawCircle(x, y, radius, starPaint)
        }
        canvas.drawRect(
            bounds.left.toFloat(), bottomEdge, bounds.right.toFloat(), bounds.bottom.toFloat(),
            vignettePaint,
        )
    }

    override fun setAlpha(alpha: Int) {
        groundPaint.alpha = alpha
        glowPaint.alpha = alpha
        starPaint.alpha = alpha
        vignettePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        groundPaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        starPaint.colorFilter = colorFilter
        vignettePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable contract; the colours are opaque by construction")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

    private fun radial(x: Float, y: Float, radius: Float, color: Int): Shader =
        RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)

    private fun tint(rgb: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        (rgb shr 16) and 0xFF,
        (rgb shr 8) and 0xFF,
        rgb and 0xFF,
    )
}

/**
 * Spark's border: a live current around a rounded rectangle.
 *
 * ## The same shape as the desktop
 *
 * `Electric.kt` on the desktop and this class are deliberately the same drawing: a faint core line
 * that is always there, plus short bolts that travel the perimeter, jitter away from it and fade
 * at the ends. The jitter is a hash of the strike number, not a fresh random each frame, so the
 * bolt looks like ONE discharge moving rather than television static.
 *
 * One animator per drawable, started only while it is visible. A theme must not keep the CPU awake
 * to draw a border nobody is looking at.
 */
class ElectricEdgeDrawable(
    context: Context,
    private val color: Int,
    cornerRadius: Float,
    private val fill: Int? = null,
    private val intensity: Float = 1f,
) : Drawable() {

    private val radius = cornerRadius
    private val density = context.resources.displayMetrics.density
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            // A DRAWABLE WITH NO VIEW IS A LEAK. Once the dialog or row it belongs to is gone
            // there is no callback to invalidate, and an infinite animator holding the drawable
            // alive would keep ticking for the life of the process. Stop instead.
            if (callback == null) {
                cancel()
                return@addUpdateListener
            }
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
        return super.setVisible(visible, restart)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (!animator.isStarted) animator.start()
        val strength = intensity.coerceIn(0.15f, 1.6f)
        val r = radius.coerceIn(0f, min(bounds.width(), bounds.height()) / 2f)

        fill?.let {
            body.color = it
            canvas.drawRoundRect(rect, r, r, body)
        }

        core.color = color
        core.alpha = (0.26f * strength * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, r, r, core)
        halo.color = color
        halo.alpha = (0.10f * strength * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, r, r, halo)

        val inset = 1f * density
        val left = rect.left + inset + r
        val right = rect.right - inset - r
        val top = rect.top + inset + r
        val bottom = rect.bottom - inset - r
        val edges = listOf(
            floatArrayOf(left, rect.top + inset, right, rect.top + inset),
            floatArrayOf(rect.right - inset, top, rect.right - inset, bottom),
            floatArrayOf(right, rect.bottom - inset, left, rect.bottom - inset),
            floatArrayOf(rect.left + inset, bottom, rect.left + inset, top),
        )
        val strikes = (2 + (strength * 3f).toInt()).coerceIn(2, 6)
        edges.forEachIndexed { edge, points ->
            for (strike in 0 until strikes) {
                val seed = edge * 977 + strike * 131 + 17
                val travel = ((phase + unit(seed)) % 1f)
                val span = 0.10f + 0.14f * unit(seed + 3)
                val start = (travel - span / 2f).coerceIn(0f, 1f)
                val end = (travel + span / 2f).coerceIn(0f, 1f)
                if (end - start < 0.02f) continue
                val fade = (1f - kotlin.math.abs(travel - 0.5f) * 2f).coerceIn(0f, 1f)
                if (fade <= 0.02f) continue
                bolt(
                    canvas,
                    points[0] + (points[2] - points[0]) * start,
                    points[1] + (points[3] - points[1]) * start,
                    points[0] + (points[2] - points[0]) * end,
                    points[1] + (points[3] - points[1]) * end,
                    seed,
                    fade * strength,
                )
            }
        }
    }

    private fun bolt(
        canvas: Canvas,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        seed: Int,
        strength: Float,
    ) {
        val length = hypot(toX - fromX, toY - fromY)
        if (length < 2f) return
        val normalX = -(toY - fromY) / length
        val normalY = (toX - fromX) / length
        val reach = (2.2f + min(5f, length * 0.045f)) * density * (0.6f + strength)
        val path = android.graphics.Path()
        val steps = 6
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            val offset = if (i == 0 || i == steps - 1) 0f else signed(seed + i * 31) * reach
            val x = fromX + (toX - fromX) * t + normalX * offset
            val y = fromY + (toY - fromY) * t + normalY * offset
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val alpha = (0.35f + 0.5f * strength).coerceIn(0f, 0.95f)
        halo.color = color
        halo.alpha = (alpha * 0.30f * 255).toInt()
        canvas.drawPath(path, halo)
        core.color = color
        core.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, core)
    }

    override fun setAlpha(alpha: Int) {
        core.alpha = alpha
        halo.alpha = alpha
        body.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        core.colorFilter = colorFilter
        halo.colorFilter = colorFilter
        body.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable contract; a translucent fill is honoured through setAlpha")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private fun unit(seed: Int): Float {
        var x = seed * 1664525 + 1013904223
        x = x xor (x ushr 13)
        x *= 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    private fun signed(seed: Int): Float = unit(seed) * 2f - 1f
}
