package com.prism.launcher.modelcraft

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.prism.launcher.theme.ThemePalette
import java.util.Locale

/**
 * The live voice-training graph.
 *
 * ## Why lanes instead of one pile of lines
 *
 * The seven measured groups have different units and different ranges. Drawing them on one axis
 * turned the quiet ones into flat lines at the bottom and the loud ones into a scribble. Each group
 * gets its own lane and its own 0..1 normalisation, so pitch movement, jitter, timbre and timing
 * stay comparable at a glance without pretending they share a scale.
 *
 * ## What the two brightnesses mean
 *
 * The faint trace is the whole dataset -- what the model is being asked to cover. The bright trace
 * is the part the run has reached, and the playhead names the clip being trained right now. That is
 * the difference between a decorative sparkline and a progress view.
 */
class VoiceTrainingGraphView(
    context: Context,
    private val palette: ThemePalette,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var snapshot = VoiceTrainingMonitor.Snapshot()
    private val laneColors: IntArray = laneColors()

    init {
        setWillNotDraw(false)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Hands the view a new frame. Safe to call as often as the trainer reports. */
    fun render(next: VoiceTrainingMonitor.Snapshot) {
        snapshot = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val outer = dp(9f)
        val header = dp(15f)
        val footer = dp(17f)
        val labelWidth = dp(84f)
        val left = outer + labelWidth
        val top = outer + header
        val right = width - outer
        val bottom = height - outer - footer
        val lanes = VoiceTrainingMonitor.GROUPS.size
        if (right - left < dp(40f) || bottom - top < lanes * dp(12f)) return
        val laneHeight = (bottom - top) / lanes

        text.style = Paint.Style.FILL
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(9f)
        text.color = palette.accent.toInt()
        canvas.drawText("VOICE TRAINING", outer, outer + text.textSize, text)

        text.textSize = sp(8.5f)
        text.color = palette.faint.toInt()
        text.textAlign = Paint.Align.RIGHT
        val headerRight = if (snapshot.epoch > 0) {
            "EPOCH ${snapshot.epoch}/${snapshot.epochs.coerceAtLeast(snapshot.epoch)}" +
                " · STEP ${snapshot.step}/${snapshot.steps}"
        } else {
            snapshot.phase.ifBlank { "MEASURING THE VOICE…" }.uppercase(Locale.US)
        }
        canvas.drawText(headerRight, right, outer + text.textSize, text)
        text.textAlign = Paint.Align.LEFT

        if (!snapshot.ready) {
            drawWaiting(canvas, outer, top, right, bottom)
            return
        }

        val frames = snapshot.frames
        val count = frames.size
        val lastIndex = (snapshot.cursor).coerceIn(0, count - 1)

        // Lanes first, so the traces sit on their own baselines.
        VoiceTrainingMonitor.GROUPS.forEachIndexed { index, group ->
            val laneTop = top + index * laneHeight
            if (index > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1f)
                paint.color = palette.divider.toInt()
                paint.alpha = 70
                canvas.drawLine(outer, laneTop, right, laneTop, paint)
            }
            val active = cursorOnLane(index, lastIndex)
            text.textSize = sp(7.5f)
            text.color = if (active) palette.text.toInt() else palette.faint.toInt()
            val baseline = laneTop + laneHeight / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(group.label, outer, baseline, text)
        }

        // The dataset as a whole, then the part the run has actually reached.
        VoiceTrainingMonitor.GROUPS.indices.forEach { index ->
            drawTrace(canvas, index, top, laneHeight, left, right, count, 0.22f, dp(1f))
        }
        VoiceTrainingMonitor.GROUPS.indices.forEach { index ->
            drawTrace(canvas, index, top, laneHeight, left, right, lastIndex + 1, 0.95f, dp(1.6f))
        }

        // The playhead: one vertical rule through every lane, and a dot per lane at the cursor.
        val cursorX = xAt(lastIndex, count, left, right)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.text.toInt()
        paint.alpha = 45
        canvas.drawLine(cursorX, top, cursorX, bottom, paint)
        VoiceTrainingMonitor.GROUPS.indices.forEach { index ->
            val value = frames[lastIndex][index]
            if (!value.isFinite()) return@forEach
            val y = laneY(index, top, laneHeight, value)
            paint.style = Paint.Style.FILL
            paint.color = laneColors[index]
            paint.alpha = 255
            canvas.drawCircle(cursorX, y, dp(2.4f), paint)
        }

        // Footer: the clip being trained and the loss, on one line.
        text.textSize = sp(8f)
        text.color = palette.muted.toInt()
        text.textAlign = Paint.Align.LEFT
        val file = snapshot.currentFile.ifBlank {
            if (lastIndex >= 0) baseName(snapshot.files.getOrNull(lastIndex).orEmpty()) else ""
        }
        val lossText = if (snapshot.loss.isFinite()) {
            String.format(Locale.US, "loss %.4f", snapshot.loss)
        } else {
            ""
        }
        val fileRoom = (right - outer - if (lossText.isBlank()) 0f else dp(74f))
            .coerceAtLeast(dp(20f))
        if (file.isNotBlank()) {
            val shown = TextUtils.ellipsize(file, text, fileRoom, TextUtils.TruncateAt.MIDDLE).toString()
            canvas.drawText(shown, outer, height - outer - dp(2f), text)
        }
        if (lossText.isNotBlank()) {
            text.textAlign = Paint.Align.RIGHT
            text.color = palette.faint.toInt()
            canvas.drawText(lossText, right, height - outer - dp(2f), text)
        }
    }

    private fun drawWaiting(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        text.textAlign = Paint.Align.CENTER
        text.color = palette.muted.toInt()
        val waiting = snapshot.phase.ifBlank { "MEASURING THE VOICE…" }.uppercase(Locale.US)
        text.textSize = if (waiting.length > 30) sp(9f) else sp(12f)
        canvas.drawText(waiting, centerX, centerY - sp(4f), text)
        text.color = palette.faint.toInt()
        text.textSize = sp(8.5f)
        canvas.drawText(
            "Pitch, rhythm, voice quality, timbre and timing appear here",
            centerX,
            centerY + sp(12f),
            text,
        )
        canvas.drawText(
            "as the training dataset is measured.",
            centerX,
            centerY + sp(23f),
            text,
        )
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawTrace(
        canvas: Canvas,
        group: Int,
        top: Float,
        laneHeight: Float,
        left: Float,
        right: Float,
        endExclusive: Int,
        alpha: Float,
        strokeWidth: Float,
    ) {
        val frames = snapshot.frames
        if (frames.isEmpty()) return
        val path = Path()
        var started = false
        val last = endExclusive.coerceIn(0, frames.size)
        // AT MOST A FEW POINTS PER PIXEL. A ten-thousand-clip corpus otherwise turned every
        // progress frame into a hundred thousand line segments, which is a chart that costs more
        // than the training step it is describing.
        val stride = (frames.size / 260).coerceAtLeast(1)
        var index = 0
        while (index < last) {
            val value = frames[index][group]
            if (!value.isFinite()) {
                started = false
            } else {
                val x = xAt(index, frames.size, left, right)
                val y = laneY(group, top, laneHeight, value)
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
            index += stride
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = laneColors[group]
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawPath(path, paint)
    }

    private fun laneY(group: Int, top: Float, laneHeight: Float, value: Float): Float {
        val pad = dp(3f)
        val usable = (laneHeight - pad * 2).coerceAtLeast(1f)
        return top + group * laneHeight + laneHeight - pad - value.coerceIn(0f, 1f) * usable
    }

    private fun xAt(index: Int, count: Int, left: Float, right: Float): Float {
        if (count <= 1) return (left + right) / 2f
        return left + (right - left) * (index.toFloat() / (count - 1))
    }

    /** Whether the cursor's clip is the one whose value this lane is drawing. */
    private fun cursorOnLane(group: Int, cursor: Int): Boolean {
        val frames = snapshot.frames
        if (cursor !in frames.indices) return false
        return frames[cursor][group].isFinite()
    }

    private fun laneColors(): IntArray {
        val dark = luminance(palette.background) < 150
        return intArrayOf(
            palette.accent.toInt(),
            palette.accentAlt.toInt(),
            if (dark) 0xFF4FC3F7.toInt() else 0xFF0277BD.toInt(),
            if (dark) 0xFFFFB74D.toInt() else 0xFFEF6C00.toInt(),
            if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt(),
            if (dark) 0xFFBA68C8.toInt() else 0xFF6A1B9A.toInt(),
            if (dark) 0xFFE57373.toInt() else 0xFFC62828.toInt(),
        )
    }

    private fun luminance(color: Long): Int {
        val value = color.toInt()
        val red = (value shr 16) and 0xFF
        val green = (value shr 8) and 0xFF
        val blue = value and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun baseName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
