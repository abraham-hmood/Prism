package com.prism.launcher.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * A three-by-three unlock pattern.
 *
 * ## Why it is written rather than borrowed
 *
 * Android's own pattern view is internal to the system UI and has never been public API. Every app
 * that offers pattern unlock draws its own.
 *
 * ## What a pattern is, once entered
 *
 * A string of digits -- the dot indices in the order they were touched. That is all it can be:
 * [LockStore] hashes a string, and the pattern has to reduce to one. It means a pattern and a PIN
 * of the same digits are the same secret, which is harmless because the mechanism is fixed at setup
 * and only one is ever offered.
 *
 * ## The intermediate-dot rule
 *
 * Dragging from the top-left dot to the top-right one passes over the top-middle, and every pattern
 * lock in existence counts that middle dot as touched. Users draw patterns by shape, not by careful
 * sequence, so a view that ignored the pass-over would reject patterns people believe they drew.
 */
class PatternView(context: Context) : View(context) {

    /** Fired when the finger lifts, with the dot indices in order. */
    var onPattern: ((String) -> Unit)? = null

    /** Turns the trace red without clearing it, for a rejected attempt. */
    var errorState = false
        set(value) { field = value; invalidate() }

    private val chosen = mutableListOf<Int>()
    private var currentX = 0f
    private var currentY = 0f
    private var tracking = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * The theme's accent, or red for a rejected attempt.
     *
     * The lock screen is themed by the activity around it; a pattern grid that stayed iOS blue in
     * every theme was the one control that ignored it.
     */
    private fun accent(): Int = if (errorState) {
        com.prism.launcher.theme.PrismThemes.modeFor(isDarkTheme()).palette.destructive.toInt()
    } else {
        com.prism.launcher.theme.PrismThemes.modeFor(isDarkTheme()).palette.accent.toInt()
    }

    private fun isDarkTheme(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The theme's text colour at low alpha, for the untouched dots. */
    private fun idleDot(): Int {
        val text = com.prism.launcher.theme.PrismThemes.modeFor(isDarkTheme()).palette.text.toInt()
        return (0x66 shl 24) or (text and 0x00FFFFFF)
    }

    private fun cellSize(): Float = minOf(width, height) / 3f

    private fun centreOf(index: Int): Pair<Float, Float> {
        val cell = cellSize()
        val offsetX = (width - cell * 3) / 2f
        val offsetY = (height - cell * 3) / 2f
        val column = index % 3
        val row = index / 3
        return (offsetX + cell * column + cell / 2f) to (offsetY + cell * row + cell / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = cellSize() * 0.09f

        // The trace first, so the dots sit on top of it rather than being crossed out by it.
        if (chosen.isNotEmpty()) {
            linePaint.color = accent()
            for (i in 0 until chosen.size - 1) {
                val (x1, y1) = centreOf(chosen[i])
                val (x2, y2) = centreOf(chosen[i + 1])
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
            if (tracking) {
                val (x, y) = centreOf(chosen.last())
                canvas.drawLine(x, y, currentX, currentY, linePaint)
            }
        }

        for (index in 0 until 9) {
            val (x, y) = centreOf(index)
            val touched = index in chosen
            dotPaint.color = if (touched) accent() else idleDot()
            dotPaint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, if (touched) radius * 1.5f else radius, dotPaint)

            if (touched) {
                dotPaint.style = Paint.Style.STROKE
                dotPaint.strokeWidth = 4f
                dotPaint.color = accent() and 0x55FFFFFF
                canvas.drawCircle(x, y, radius * 3f, dotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                chosen.clear()
                errorState = false
                tracking = true
                capture(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                capture(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                tracking = false
                invalidate()
                if (chosen.isNotEmpty()) onPattern?.invoke(chosen.joinToString(""))
            }
            MotionEvent.ACTION_CANCEL -> {
                // A cancelled gesture is not an attempt. Submitting here counted a notification
                // shade pull or a system gesture as a wrong pattern and, after five of them,
                // started the 30-second lockout.
                tracking = false
                chosen.clear()
                errorState = false
                invalidate()
            }
            else -> return false
        }
        return true
    }

    /** Adds whatever dot is under the finger, plus any dot the stroke just passed over. */
    private fun capture(x: Float, y: Float) {
        val index = dotAt(x, y) ?: return
        if (index in chosen) return

        chosen.lastOrNull()?.let { previous ->
            between(previous, index)?.let { middle ->
                if (middle !in chosen) chosen.add(middle)
            }
        }
        chosen.add(index)
        invalidate()
    }

    private fun dotAt(x: Float, y: Float): Int? {
        val threshold = cellSize() * 0.3f
        for (index in 0 until 9) {
            val (cx, cy) = centreOf(index)
            if (hypot(x - cx, y - cy) < threshold) return index
        }
        return null
    }

    /** The dot a straight line between two dots passes through, if there is one. */
    private fun between(from: Int, to: Int): Int? {
        val fromRow = from / 3; val fromColumn = from % 3
        val toRow = to / 3; val toColumn = to % 3
        val rowGap = toRow - fromRow
        val columnGap = toColumn - fromColumn
        // Only a gap of exactly two in a straight or diagonal line skips a dot.
        if (rowGap % 2 != 0 || columnGap % 2 != 0) return null
        if (rowGap == 0 && columnGap == 0) return null
        return (fromRow + rowGap / 2) * 3 + (fromColumn + columnGap / 2)
    }

    fun reset() {
        chosen.clear()
        errorState = false
        tracking = false
        invalidate()
    }
}
