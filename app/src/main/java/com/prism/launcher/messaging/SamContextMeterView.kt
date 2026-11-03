package com.prism.launcher.messaging

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.prism.launcher.R
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The small readout in the corner of Sam's thread: context used, tokens spent, and live speed.
 *
 * WHY A CUSTOM VIEW. It is three pieces of information inside one ring, redrawn several times a
 * second while a reply streams. A ProgressBar plus two TextViews would be three measure/layout
 * passes per tick and would still need a ring drawable that accepts the theme accent. One view,
 * one invalidate.
 *
 * The ring is the only number that needs colour: amber past 75% and red past 90%, because running
 * out of context is the failure the user can actually avoid by starting a new thread.
 */
class SamContextMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val accent = resolveAttr(R.attr.prismAccent, 0xFF7C9EFF.toInt())
    private val track = resolveAttr(R.attr.prismDivider, 0x33FFFFFF)
    private val surface = resolveAttr(R.attr.prismCardColor, 0xFF1B1B21.toInt())
    private val primary = resolveAttr(R.attr.prismTextPrimary, 0xFFEDEDF2.toInt())
    private val secondary = resolveAttr(R.attr.prismTextSecondary, 0xFF9A9AA6.toInt())

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tokensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9.5f)
    }

    private val ringRect = RectF()
    private val numberFormat = NumberFormat.getIntegerInstance()

    private var fraction = 0f
    private var tokens = 0
    private var rate = 0.0
    private var contextSize = 0

    /**
     * @param contextFraction 0..1 of the model's context window in use.
     * @param tokens tokens consumed by the conversation so far, prompt included.
     * @param tokensPerSecond the live decode rate; 0 while nothing is generating.
     */
    fun update(contextFraction: Float, tokens: Int, tokensPerSecond: Double, contextWindow: Int = 0) {
        val nextFraction = contextFraction.coerceIn(0f, 1f)
        val changed = nextFraction != fraction ||
            tokens != this.tokens ||
            tokensPerSecond != rate ||
            contextWindow != contextSize
        if (!changed) return
        fraction = nextFraction
        this.tokens = tokens
        rate = tokensPerSecond
        contextSize = contextWindow
        contentDescription = buildString {
            // Says what a tap does: a pill that reacts to touch but announces nothing is invisible
            // to anybody reading the screen through a screen reader.
            append("Tap to compact the conversation. ")
            append("${(fraction * 100).roundToInt()}% of context used")
            if (contextWindow > 0) append(", $tokens of $contextWindow tokens")
            if (rate > 0.0) append(", ${"%.1f".format(rate)} tokens per second")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(176f).roundToInt(), widthMeasureSpec)
        val height = resolveSize(dp(48f).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = height.toFloat()
        val w = width.toFloat()

        // Pill background, so the numbers stay legible over a wallpaper or a bright photo.
        pillPaint.color = surface
        pillPaint.alpha = 232
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, pillPaint)

        val diameter = dp(30f)
        val left = dp(9f)
        val top = (h - diameter) / 2f
        ringRect.set(left, top, left + diameter, top + diameter)

        ringPaint.color = track
        canvas.drawArc(ringRect, -90f, 360f, false, ringPaint)

        ringPaint.color = when {
            fraction >= 0.9f -> 0xFFFF5A5A.toInt()
            fraction >= 0.75f -> 0xFFFFC24D.toInt()
            else -> accent
        }
        if (fraction > 0f) canvas.drawArc(ringRect, -90f, 360f * fraction, false, ringPaint)

        percentPaint.color = primary
        val centreY = h / 2f - (percentPaint.descent() + percentPaint.ascent()) / 2f
        canvas.drawText("${(fraction * 100).roundToInt()}%", ringRect.centerX(), centreY, percentPaint)

        val textLeft = ringRect.right + dp(9f)
        tokensPaint.color = primary
        ratePaint.color = secondary

        val tokenText = "${numberFormat.format(tokens)} tok"
        val rateText = if (rate > 0.0) "${"%.1f".format(rate)} tok/s" else "— tok/s"

        val gap = dp(1.5f)
        val blockHeight = tokensPaint.textSize + gap + ratePaint.textSize
        val firstBaseline = (h - blockHeight) / 2f - tokensPaint.ascent()
        canvas.drawText(tokenText, textLeft, firstBaseline, tokensPaint)
        canvas.drawText(rateText, textLeft, firstBaseline + gap + ratePaint.textSize, ratePaint)
    }

    private fun resolveAttr(attr: Int, fallback: Int): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typed.getColor(0, fallback)
        } finally {
            typed.recycle()
        }
    }
}
