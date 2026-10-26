package com.prism.launcher.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PagePickerSwipe
import com.prism.launcher.nora.IosUi
import kotlin.math.abs

/**
 * The three-swipe capture screen, used by onboarding and by Settings.
 *
 * It is a view rather than an activity so the first-run tour can put it inline, where the user is
 * already being told about pages. The Settings entry wraps the same view in a small activity, so
 * there is one implementation of "capture a swipe" instead of two that could disagree about what
 * counts.
 */
class SwipeTriggerSetupView(context: Context) : LinearLayout(context) {

    /** Called once, with the trigger the three swipes agreed on. */
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
            text = "Swipe three times, the same way each time."
        }
        addView(status)

        progress.apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 10f))
            text = "0 of 3"
        }
        addView(progress)

        pad.onSwipe = { dx, dy -> capture(dx, dy) }
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 220f)))

        addView(TextView(context).apply {
            text = "Horizontal swipes only work from the screen edge. Vertical swipes work wherever " +
                "the page cannot scroll further in that direction."
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 10f), 0, 0)
        })
    }

    /** Clears the capture, for the Settings screen's "Try again". */
    fun reset() {
        samples.clear()
        pad.lastDirection = null
        status.text = "Swipe three times, the same way each time."
        status.setTextColor(IosUi.label(context))
        progress.text = "0 of 3"
        pad.invalidate()
    }

    /** Shows an already-chosen trigger, for a Settings screen that is reopening. */
    fun prefill(trigger: PagePickerSwipe) {
        status.text = "Current trigger: ${trigger.direction.label.lowercase()} " +
            "(${trigger.distanceDp} dp or more). Swipe three times to replace it."
        status.setTextColor(IosUi.secondaryLabel(context))
    }

    private fun capture(dx: Float, dy: Float) {
        // A completed capture leaves three samples behind. Without this, a fourth swipe would be
        // counted as "4 of 3" and quietly redefine the trigger with four samples; starting over
        // makes a re-swipe behave the way the screen reads.
        if (samples.size >= 3) {
            samples.clear()
            pad.lastDirection = null
        }

        val sample = PagePickerSwipe.SwipeSample(dx, dy)
        val direction = sample.direction()
        if (direction == null) {
            status.text = "That was too short to count. Try again."
            status.setTextColor(IosUi.secondaryLabel(context))
            return
        }

        val first = samples.firstOrNull()?.direction()
        if (first != null && direction != first) {
            samples.clear()
            samples.add(sample)
            status.text = "That was a different direction, so the count restarted."
            status.setTextColor(IosUi.secondaryLabel(context))
        } else {
            samples.add(sample)
        }

        pad.lastDirection = direction
        pad.invalidate()
        progress.text = "${samples.size} of 3"

        if (samples.size < 3) {
            status.text = "Good — do it ${3 - samples.size} more time" +
                (if (samples.size == 2) "." else "s.")
            return
        }

        val trigger = PagePickerSwipe.fromSamples(samples, resources.displayMetrics.density)
        if (trigger == null) {
            status.text = "Those swipes did not match closely enough. Start again."
            status.setTextColor(IosUi.secondaryLabel(context))
            samples.clear()
            progress.text = "0 of 3"
            pad.lastDirection = null
            pad.invalidate()
            return
        }

        status.text = "✓ ${trigger.direction.label.lowercase().replaceFirstChar { it.uppercase() }}" +
            " — this is your page-picker gesture. Swipe three more times to change it."
        status.setTextColor(IosUi.accent(context))
        progress.text = "3 of 3"
        onCaptured?.invoke(trigger)
    }

    /**
     * The swipeable area.
     *
     * It disallows parent interception on touch down. Without that, the tour's ScrollView claims
     * the vertical drag and the pad never sees an upward swipe -- the exact gesture most people
     * will choose.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class SwipePad(context: Context) : View(context) {

        var onSwipe: ((Float, Float) -> Unit)? = null
        var lastDirection: PagePickerSwipe.Direction? = null

        private var startX = 0f
        private var startY = 0f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0F7C9EFF
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x337C9EFF
            style = Paint.Style.STROKE
            strokeWidth = IosUi.dp(this@SwipeTriggerSetupView.context, 1f).toFloat()
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8A8E.toInt()
            textSize = IosUi.dp(this@SwipeTriggerSetupView.context, 15f).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0A84FF.toInt()
            textSize = IosUi.dp(this@SwipeTriggerSetupView.context, 46f).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = IosUi.dp(context, 2f).toFloat()
            val rect = RectF(inset, inset, width - inset, height - inset)
            val radius = IosUi.dp(context, 18f).toFloat()
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)

            val label = when (lastDirection) {
                PagePickerSwipe.Direction.UP -> "↑"
                PagePickerSwipe.Direction.DOWN -> "↓"
                PagePickerSwipe.Direction.LEFT -> "←"
                PagePickerSwipe.Direction.RIGHT -> "→"
                null -> "↑↓←→"
            }
            canvas.drawText(label, width / 2f, height / 2f + arrow.textSize / 3f, arrow)
            canvas.drawText(
                "Swipe across this area",
                width / 2f,
                height / 2f + arrow.textSize / 3f + IosUi.dp(context, 42f),
                text,
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startX = event.x
                    startY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dx) > IosUi.dp(context, 16f) || abs(dy) > IosUi.dp(context, 16f)) {
                        onSwipe?.invoke(dx, dy)
                    }
                }
                MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
    }
}
