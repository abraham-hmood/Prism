package com.prism.launcher.minigames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The result burst that plays over a finished game.
 *
 * It is a separate, non-interactive view rather than part of each game's renderer for one reason:
 * every cabinet already knows how to say "you lost"; none of them should also have to draw
 * confetti. The page watches [MinigameFeedback.outcome] and calls [play] once per result.
 *
 * The animation is a function of elapsed time, so nothing accumulates: no particle list to leak, no
 * allocation per frame beyond the paint lookups. It fades out on its own and then stops scheduling
 * frames.
 */
class MinigameOutcomeView(context: Context) : View(context) {

    private val colors = MinigameTheme.of(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var startedAt = 0L
    private var won = false

    init {
        // A result is scenery: it must never eat a tap meant for RESTART.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun play(won: Boolean) {
        this.won = won
        startedAt = System.nanoTime()
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        startedAt = 0L
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat()
        val duration = 2.2f
        if (elapsed >= duration) {
            startedAt = 0L
            visibility = GONE
            return
        }
        val progress = (elapsed / 1.6f).coerceIn(0f, 1f)
        val fade = if (elapsed > 1.6f) 1f - (elapsed - 1.6f) / (duration - 1.6f) else 1f
        val accent = if (won) colors.success else colors.danger
        val centreX = width / 2f
        val centreY = height * 0.42f
        val radius = minOf(width, height) * 0.34f

        repeat(3) { ring ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = alpha(accent, ((1f - progress) * 150).toInt())
            canvas.drawCircle(centreX, centreY, radius * (0.35f + progress * (1f + ring * 0.18f)), paint)
        }

        val random = Random(if (won) 0xC0FFEEL else 0xDEADL)
        val sparks = colors.sprites()
        repeat(34) { index ->
            val angle = random.nextFloat() * 6.2832f
            val reach = radius * (0.5f + random.nextFloat() * 1.4f) * progress
            val x = centreX + cos(angle) * reach
            val y = centreY + sin(angle) * reach
            paint.style = Paint.Style.FILL
            paint.color = alpha(if (won) sparks[index % 7] else accent, (220 * fade).toInt())
            canvas.drawCircle(x, y, dp(3f) * (1f - progress * 0.45f), paint)
        }

        text.color = alpha(accent, (255 * fade).toInt())
        text.textSize = dp(if (won) 46f else 40f)
        canvas.drawText(if (won) "VICTORY" else "DEFEAT", centreX, centreY + dp(16f), text)
        text.color = alpha(colors.ink, (200 * fade).toInt())
        text.textSize = dp(13f)
        canvas.drawText(
            if (won) "run complete" else "the arcade wins this one",
            centreX,
            centreY + dp(44f),
            text,
        )

        if (elapsed < duration) postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun alpha(colour: Int, value: Int): Int =
        (colour and 0x00FFFFFF) or (value.coerceIn(0, 255) shl 24)
}
