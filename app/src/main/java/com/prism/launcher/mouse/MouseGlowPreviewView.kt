package com.prism.launcher.mouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.prism.launcher.nora.IosUi

/**
 * A small live sample of a style and a flow.
 *
 * The editors need to answer "what does this look like" without making the user save, go back to
 * the Mouse page and try again. This draws the same palette and the same evaluated flow the real
 * mouse does, as a rounded light bar. It animates only while attached, so leaving the editor stops
 * the frame loop.
 */
class MouseGlowPreviewView(context: Context) : View(context) {

    private var style: MouseGlow.Style = MouseGlow.selectedStyle()
    private var flow: MouseGlow.Flow = MouseGlow.selectedFlow()
    private val startedAt = SystemClock.elapsedRealtime()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (IosUi.isDark(context)) 0xFF141416.toInt() else 0xFFF2F2F7.toInt()
    }
    private val segment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var running = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !isShown) {
                running = false
                return
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun show(style: MouseGlow.Style, flow: MouseGlow.Flow) {
        this.style = style
        this.flow = flow
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) {
            running = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(6f).toFloat()
        val bounds = RectF(inset, inset, width - inset, height - inset)
        val radius = height / 2f
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        canvas.drawRoundRect(bounds, radius, radius, fill)

        val steps = 56
        val stroke = dp(3f).toFloat()
        segment.strokeWidth = stroke
        halo.strokeWidth = stroke * 3.2f
        for (index in 0 until steps) {
            val from = index.toDouble() / steps
            val to = (index + 1).toDouble() / steps
            val sample = MouseGlow.sample(style, flow, from, seconds)
            val color = sample.color
            segment.color = color
            halo.color = Color.argb((Color.alpha(color) * 0.22f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

            val a = point(bounds, radius, from)
            val b = point(bounds, radius, to)
            canvas.drawLine(a.first, a.second, b.first, b.second, halo)
            canvas.drawLine(a.first, a.second, b.first, b.second, segment)
        }
    }

    /** A point on the rounded rectangle's perimeter, by fraction of the total perimeter. */
    private fun point(bounds: RectF, radius: Float, fraction: Double): Pair<Float, Float> {
        val straightX = (bounds.width() - radius * 2f).coerceAtLeast(0f)
        val straightY = (bounds.height() - radius * 2f).coerceAtLeast(0f)
        val arc = (Math.PI * radius).toFloat()
        val perimeter = (straightX + straightY) * 2f + arc * 4f
        var distance = (fraction.coerceIn(0.0, 1.0) * perimeter).toFloat()

        // Top edge, left to right.
        if (distance <= straightX) return bounds.left + radius + distance to bounds.top
        distance -= straightX
        // Top-right corner.
        if (distance <= arc) {
            val angle = -Math.PI / 2 + (distance / radius)
            return (bounds.right - radius + Math.cos(angle) * radius).toFloat() to
                (bounds.top + radius + Math.sin(angle) * radius).toFloat()
        }
        distance -= arc
        // Right edge.
        if (distance <= straightY) return bounds.right to bounds.top + radius + distance
        distance -= straightY
        // Bottom-right corner.
        if (distance <= arc) {
            val angle = (distance / radius).toDouble()
            return (bounds.right - radius + Math.cos(angle) * radius).toFloat() to
                (bounds.bottom - radius + Math.sin(angle) * radius).toFloat()
        }
        distance -= arc
        // Bottom edge, right to left.
        if (distance <= straightX) return bounds.right - radius - distance to bounds.bottom
        distance -= straightX
        // Bottom-left corner.
        if (distance <= arc) {
            val angle = Math.PI / 2 + (distance / radius)
            return (bounds.left + radius + Math.cos(angle) * radius).toFloat() to
                (bounds.bottom - radius + Math.sin(angle) * radius).toFloat()
        }
        distance -= arc
        // Left edge.
        if (distance <= straightY) return bounds.left to bounds.bottom - radius - distance
        distance -= straightY
        // Top-left corner.
        val angle = Math.PI + (distance / radius)
        return (bounds.left + radius + Math.cos(angle) * radius).toFloat() to
            (bounds.top + radius + Math.sin(angle) * radius).toFloat()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
