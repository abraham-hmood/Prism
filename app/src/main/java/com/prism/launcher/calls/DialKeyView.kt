package com.prism.launcher.calls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import kotlin.math.min

/**
 * One key of the Prism keypad, drawn the way the iOS phone keypad draws it.
 *
 * A TextView with an oval background was the previous approach, and it had two problems: the
 * background is stretched to the view's rectangle (so on a tall row the keys became ovals and
 * overlapped their neighbours), and the letter row had to be a second TextView with its own
 * baseline. This view owns the circle and both lines of text, so the key stays a circle at every
 * screen size and the digit/letters block stays optically centred.
 */
class DialKeyView(
    context: Context,
    private val digit: String,
    private val letters: String,
) : View(context) {

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 28, 30)
    }
    private val pressedCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 58, 60)
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // A bundled face, not the system's: a themed device font (Samsung lets a user replace
        // every sans-serif glyph) would otherwise turn the keypad digits into a display face.
        typeface = ResourcesCompat.getFont(context, R.font.roboto_light)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 142, 147)
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.roboto_regular)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private var pressed = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = if (letters.isBlank()) digit else "$digit, $letters"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - IosUi.dp(context, 5f)
        canvas.drawCircle(cx, cy, radius, if (pressed) pressedCircle else circle)

        val digitSize = radius * 0.92f
        digitPaint.textSize = digitSize
        if (letters.isBlank()) {
            val baseline = cy - (digitPaint.ascent() + digitPaint.descent()) / 2f
            canvas.drawText(digit, cx, baseline, digitPaint)
            return
        }

        val letterSize = radius * 0.30f
        letterPaint.textSize = letterSize
        val gap = radius * 0.04f
        val blockHeight = digitSize + gap + letterSize
        val top = cy - blockHeight / 2f
        canvas.drawText(digit, cx, top - digitPaint.ascent(), digitPaint)
        canvas.drawText(letters, cx, top + digitSize + gap - letterPaint.ascent(), letterPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressed) {
                    pressed = false
                    invalidate()
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
