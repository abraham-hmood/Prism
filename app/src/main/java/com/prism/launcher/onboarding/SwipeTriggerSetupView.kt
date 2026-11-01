package com.prism.launcher.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PagePickerSwipe
import com.prism.launcher.nora.IosUi
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The three-gesture capture screen, used by onboarding and by Settings.
 *
 * It is a view rather than an activity so the first-run tour can put it inline, where the user is
 * already being told about pages. The Settings entry wraps the same view in a small activity, so
 * there is one implementation of "capture a gesture" instead of two that could disagree about what
 * counts.
 *
 * ## What is captured
 *
 * The whole path, not only its first-to-last direction, plus the average pressure under the finger.
 * That is what lets a circle, a triangle or a diagonal be a trigger. The three attempts are
 * compared as shapes after normalisation, so drawing the same thing a little larger or in another
 * corner still works.
 */
class SwipeTriggerSetupView(context: Context) : LinearLayout(context) {

    /** Called once, with the trigger the three gestures agreed on. */
    var onCaptured: ((PagePickerSwipe) -> Unit)? = null

    private val status = TextView(context)
    private val progress = TextView(context)
    private val pad = SwipePad(context)
    private val samples = mutableListOf<PagePickerSwipe.SwipeSample>()

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)

        status.apply {
            textSize = 15f
            setTextColor(IosUi.label(context))
            text = "Draw the same gesture three times, pressing as you normally would."
        }
        addView(status)

        progress.apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 10f))
            text = "0 of 3"
        }
        addView(progress)

        pad.onGesture = { dx, dy, pressure, trace -> capture(dx, dy, pressure, trace) }
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 230f)))

        addView(TextView(context).apply {
            text = "Any shape works: a line, a diagonal, a loop, a triangle. Prism records the path " +
                "and the pressure you used, and both are needed to trigger the page switcher. " +
                "A straight horizontal swipe still has to start at the screen edge so normal " +
                "paging keeps working."
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 10f), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
    }

    /** Clears the capture, for the Settings screen's "Try again". */
    fun reset() {
        samples.clear()
        pad.clearTrail()
        status.text = "Draw the same gesture three times, pressing as you normally would."
        status.setTextColor(IosUi.label(context))
        progress.text = "0 of 3"
        pad.invalidate()
    }

    /** Shows an already-chosen trigger, for a Settings screen that is reopening. */
    fun prefill(trigger: PagePickerSwipe) {
        status.text = "Current trigger: ${trigger.label.lowercase()} (${trigger.detail}). " +
            "Draw three gestures to replace it."
        status.setTextColor(IosUi.secondaryLabel(context))
    }

    private fun capture(
        dx: Float,
        dy: Float,
        pressure: Float,
        trace: List<PagePickerSwipe.TracePoint>,
    ) {
        // A completed capture leaves three samples behind. Without this, a fourth gesture would be
        // counted as "4 of 3" and quietly redefine the trigger with four samples; starting over
        // makes a re-draw behave the way the screen reads.
        if (samples.size >= 3) {
            samples.clear()
            pad.clearTrail()
        }

        if (trace.size < 3 || hypot(dx, dy) < IosUi.dp(context, 24f)) {
            status.text = "That was too short to count. Draw a little further."
            status.setTextColor(IosUi.secondaryLabel(context))
            return
        }

        samples.add(
            PagePickerSwipe.SwipeSample(
                dx = dx,
                dy = dy,
                pressure = pressure,
                trace = trace,
            ),
        )
        progress.text = "${samples.size} of 3"

        if (samples.size < 3) {
            val pressureLine = if (pressure > 0.01f) {
                " Pressure ${(pressure * 100).roundToInt()}%."
            } else {
                ""
            }
            status.text = "Good — do it ${3 - samples.size} more time" +
                (if (samples.size == 2) "." else "s.") + pressureLine
            return
        }

        val trigger = PagePickerSwipe.fromTraceSamples(samples, resources.displayMetrics.density)
        if (trigger == null) {
            status.text = "Those gestures were not close enough to the same shape or length. " +
                "Start again and keep the path consistent."
            status.setTextColor(IosUi.secondaryLabel(context))
            samples.clear()
            progress.text = "0 of 3"
            pad.clearTrail()
            return
        }

        status.text = "✓ ${trigger.label} — ${trigger.detail}. This is your page-picker gesture; " +
            "draw three more times to change it."
        status.setTextColor(IosUi.accent(context))
        onCaptured?.invoke(trigger)
    }

    /**
     * The drawable area.
     *
     * It disallows parent interception on touch down. Without that, the tour's ScrollView claims
     * the drag and the pad never sees an upward gesture -- the exact gesture most people choose.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class SwipePad(context: Context) : View(context) {

        var onGesture: ((Float, Float, Float, List<PagePickerSwipe.TracePoint>) -> Unit)? = null

        private val points = mutableListOf<PagePickerSwipe.TracePoint>()
        private var startX = 0f
        private var startY = 0f
        private var pressureSum = 0f
        private var pressureCount = 0
        private var lastPressure = 0f
        private var lastTrace: List<PagePickerSwipe.TracePoint> = emptyList()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0F7C9EFF
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x337C9EFF
            style = Paint.Style.STROKE
            strokeWidth = IosUi.dp(this@SwipeTriggerSetupView.context, 1f).toFloat()
        }
        private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IosUi.accent(this@SwipeTriggerSetupView.context)
            style = Paint.Style.STROKE
            strokeWidth = IosUi.dp(this@SwipeTriggerSetupView.context, 4f).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val oldTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IosUi.accent(this@SwipeTriggerSetupView.context)
            alpha = 90
            style = Paint.Style.STROKE
            strokeWidth = IosUi.dp(this@SwipeTriggerSetupView.context, 2.5f).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IosUi.secondaryLabel(this@SwipeTriggerSetupView.context)
            textSize = IosUi.dp(this@SwipeTriggerSetupView.context, 14f).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val prompt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IosUi.tertiaryLabel(this@SwipeTriggerSetupView.context)
            textSize = IosUi.dp(this@SwipeTriggerSetupView.context, 34f).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val tracePath = Path()

        fun clearTrail() {
            points.clear()
            lastTrace = emptyList()
            lastPressure = 0f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = IosUi.dp(context, 2f).toFloat()
            val rect = RectF(inset, inset, width - inset, height - inset)
            val radius = IosUi.dp(context, 18f).toFloat()
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)

            val shown = if (points.size > 1) points else lastTrace
            if (shown.size > 1) {
                tracePath.reset()
                tracePath.moveTo(shown.first().x + startX, shown.first().y + startY)
                for (index in 1 until shown.size) {
                    tracePath.lineTo(shown[index].x + startX, shown[index].y + startY)
                }
                canvas.drawPath(tracePath, if (points.size > 1) tracePaint else oldTracePaint)
            } else {
                canvas.drawText("✎", width / 2f, height / 2f + prompt.textSize / 3f, prompt)
            }

            val caption = when {
                points.size > 1 -> "Path captured — release to use it"
                lastTrace.isNotEmpty() && lastPressure > 0f ->
                    "Last pressure: ${(lastPressure * 100).roundToInt()}%"
                else -> "Draw here"
            }
            canvas.drawText(
                caption,
                width / 2f,
                height - IosUi.dp(context, 18f).toFloat(),
                text,
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startX = event.x
                    startY = event.y
                    points.clear()
                    points.add(PagePickerSwipe.TracePoint(0f, 0f))
                    pressureSum = event.pressure.coerceIn(0f, 1f)
                    pressureCount = 1
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x - startX
                    val y = event.y - startY
                    val last = points.lastOrNull()
                    if (last == null || hypot(x - last.x, y - last.y) > IosUi.dp(context, 2f)) {
                        points.add(PagePickerSwipe.TracePoint(x, y))
                        pressureSum += event.pressure.coerceIn(0f, 1f)
                        pressureCount++
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val x = event.x - startX
                    val y = event.y - startY
                    val last = points.lastOrNull()
                    if (last == null || hypot(x - last.x, y - last.y) > 0.5f) {
                        points.add(PagePickerSwipe.TracePoint(x, y))
                    }
                    pressureSum += event.pressure.coerceIn(0f, 1f)
                    pressureCount++

                    val pressure = if (pressureCount == 0) 0f else (pressureSum / pressureCount)
                    lastPressure = pressure
                    lastTrace = points.toList()
                    if (lastTrace.size >= 3) {
                        onGesture?.invoke(
                            x,
                            y,
                            pressure,
                            lastTrace,
                        )
                    }
                    points.clear()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    points.clear()
                    invalidate()
                }
            }
            return true
        }
    }
}
