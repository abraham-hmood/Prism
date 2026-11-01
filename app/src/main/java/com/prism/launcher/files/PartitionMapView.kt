package com.prism.launcher.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.prism.launcher.nora.IosUi
import com.prism.launcher.storage.PartitionPlan
import com.prism.launcher.storage.formatBytes

/**
 * The partition bar in the Format dialog: a picture of the disk that can be edited by hand.
 *
 * ## What it edits
 *
 * A tap selects a partition, or the free tail. Dragging a boundary resizes the partition on its
 * left, taking the difference out of its neighbour (or out of the free tail when it is the last
 * one). Every edit goes through [PartitionPlan.resize], which clamps both sides to the minimum
 * partition size -- so a drag can never produce a plan that mkfs would reject, and the view does not
 * need to carry a second copy of those rules.
 *
 * ## Why the free tail is the only free space
 *
 * Plans are packed from the start of the usable area. Allowing a gap between partitions would mean
 * modelling holes, and a hole the user cannot see is a hole they cannot format. One tail is enough
 * for every real layout and keeps the drawing honest: the hatched area is exactly the space that is
 * not in a partition.
 */
@SuppressLint("ViewConstructor")
class PartitionMapView(context: Context) : View(context) {

    var totalBytes: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    var plan: PartitionPlan = PartitionPlan(emptyList())
        set(value) {
            field = value
            if (selected > value.partitions.lastIndex) selected = value.partitions.lastIndex
            invalidate()
        }

    /** Index into [plan], or [FREE] when the free tail is selected. */
    var selected: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Called when a tap changes the selection, so the dialog can relabel its +/- buttons. */
    var onSelect: ((Int) -> Unit)? = null

    /** Called continuously while a boundary is dragged. */
    var onPlanChanged: ((PartitionPlan) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * resources.displayMetrics.scaledDensity
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bar = RectF()
    private var draggingBoundary = -1
    private var dragStartX = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Partition layout"
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = dp(96)
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = dp(6).toFloat()
        bar.set(pad, dp(20).toFloat(), w - pad, h - dp(26).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalBytes <= 0L) {
            textPaint.color = IosUi.secondaryLabel(context)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "The size of this drive could not be read",
                width / 2f, height / 2f, textPaint,
            )
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        val geometry = plan.geometry(totalBytes)
        val radius = dp(8).toFloat()

        // Track, so the free area reads as part of the same disk.
        fillPaint.color = IosUi.fill(context)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)

        val freeRect = RectF(
            xFor(geometry.freeStartBytes), bar.top, bar.right, bar.bottom,
        )
        if (geometry.freeBytes > 0) {
            fillPaint.color = ColorUtils.setAlphaComponent(IosUi.secondaryLabel(context), 26)
            canvas.drawRoundRect(freeRect, radius, radius, fillPaint)
            if (freeRect.width() > dp(58)) {
                smallPaint.color = IosUi.secondaryLabel(context)
                smallPaint.textAlign = Paint.Align.CENTER
                val cy = freeRect.centerY()
                canvas.drawText("Free", freeRect.centerX(), cy - dp(1), smallPaint)
                canvas.drawText(
                    formatBytes(geometry.freeBytes),
                    freeRect.centerX(), cy + dp(11), smallPaint,
                )
                smallPaint.textAlign = Paint.Align.LEFT
            }
            if (selected == FREE) {
                strokePaint.color = IosUi.accent(context)
                strokePaint.strokeWidth = dp(2).toFloat()
                canvas.drawRoundRect(freeRect, radius, radius, strokePaint)
                strokePaint.strokeWidth = dp(1).toFloat()
            }
        }

        geometry.segments.forEach { segment ->
            val partition = plan.partitions.getOrNull(segment.index) ?: return@forEach
            val left = xFor(segment.startBytes)
            val right = xFor(segment.endBytes)
            if (right - left < 1f) return@forEach
            val rect = RectF(left, bar.top, right, bar.bottom)
            val isSelected = selected == segment.index
            val base = partitionColor(segment.index)
            fillPaint.color = if (isSelected) IosUi.accent(context) else base
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            if (isSelected) {
                strokePaint.color = ColorUtils.setAlphaComponent(IosUi.label(context), 90)
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }

            if (rect.width() > dp(58)) {
                val labelColor = if (isSelected) onAccent(context) else IosUi.label(context)
                textPaint.color = labelColor
                smallPaint.color = ColorUtils.setAlphaComponent(labelColor, 200)
                textPaint.textAlign = Paint.Align.CENTER
                smallPaint.textAlign = Paint.Align.CENTER
                val cy = rect.centerY()
                val name = partition.label.ifBlank { partition.fs.displayName }
                canvas.drawText(ellipsize(name, rect.width() - dp(12), textPaint), rect.centerX(), cy - dp(2), textPaint)
                canvas.drawText(
                    "${partition.fs.displayName} · ${formatBytes(segment.sizeBytes)}",
                    rect.centerX(), cy + dp(11), smallPaint,
                )
                textPaint.textAlign = Paint.Align.LEFT
                smallPaint.textAlign = Paint.Align.LEFT
            }
        }

        // Resize handles: one at every partition boundary that can move. The last partition's right
        // edge is a boundary too, so it gets a handle against the free tail.
        if (plan.partitions.size > 1 || geometry.freeBytes > 0) {
            geometry.segments.forEachIndexed { index, segment ->
                val x = xFor(segment.endBytes)
                val active = draggingBoundary == index
                handlePaint.color = if (active) IosUi.accent(context) else ColorUtils.setAlphaComponent(
                    IosUi.label(context), 70,
                )
                val halfWidth = dp(if (active) 3 else 2).toFloat()
                val inset = dp(6).toFloat()
                canvas.drawRoundRect(
                    RectF(x - halfWidth, bar.top + inset, x + halfWidth, bar.bottom - inset),
                    halfWidth, halfWidth, handlePaint,
                )
            }
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun xFor(bytes: Long): Float {
        if (totalBytes <= 0) return bar.left
        val fraction = (bytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        return bar.left + (bar.width() * fraction).toFloat()
    }

    private fun bytesFor(x: Float): Long {
        if (bar.width() <= 0f) return 0
        val fraction = ((x - bar.left) / bar.width()).coerceIn(0f, 1f)
        return (totalBytes * fraction).toLong()
    }

    /** Colours that stay distinguishable without leaving the theme's accent family. */
    private fun partitionColor(index: Int): Int {
        val accent = IosUi.accent(context)
        val alphas = intArrayOf(0x66, 0x88, 0xAA, 0x55, 0x99)
        return ColorUtils.setAlphaComponent(accent, alphas[index % alphas.size])
    }

    private fun onAccent(context: Context): Int =
        com.prism.launcher.theme.PrismThemes.mode(light = !IosUi.isDark(context)).palette.onAccent.toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (totalBytes <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                val geometry = plan.geometry(totalBytes)
                val boundary = nearestBoundary(event.x, geometry)
                if (boundary >= 0) {
                    draggingBoundary = boundary
                    selected = boundary
                    onSelect?.invoke(selected)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                val hit = geometry.segments.firstOrNull {
                    event.x >= xFor(it.startBytes) && event.x <= xFor(it.endBytes)
                }
                selected = hit?.index ?: FREE
                onSelect?.invoke(selected)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingBoundary >= 0) {
                    val newEnd = bytesFor(event.x)
                    val start = plan.geometry(totalBytes).segments.getOrNull(draggingBoundary)?.startBytes
                        ?: return true
                    val proposed = newEnd - start
                    val next = plan.resize(draggingBoundary, proposed, totalBytes)
                    if (next != plan) {
                        plan = next
                        onPlanChanged?.invoke(next)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingBoundary >= 0
                draggingBoundary = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!wasDragging && Math.abs(event.x - dragStartX) < dp(6)) performClick()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** The boundary under [x], or -1. A boundary is the right edge of partition [index]. */
    private fun nearestBoundary(x: Float, geometry: com.prism.launcher.storage.LayoutGeometry): Int {
        val tolerance = dp(18).toFloat()
        var best = -1
        var bestDistance = tolerance
        geometry.segments.forEachIndexed { index, segment ->
            val distance = Math.abs(xFor(segment.endBytes) - x)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    private fun dp(value: Int): Int = Math.round(value * density)

    companion object {
        /** Selection value meaning "the free tail", which only offers adding a partition. */
        const val FREE = -1
    }
}
