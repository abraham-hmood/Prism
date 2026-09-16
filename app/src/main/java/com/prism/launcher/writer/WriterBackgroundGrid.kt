package com.prism.launcher.writer

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.writer.KeyboardLayout

/**
 * The keyboard-background picker: a grid of previews, plus a tile for adding more.
 *
 * Each tile draws the picture WITH A MINIATURE KEYBOARD ON TOP, because the question a user is
 * actually asking is "what will my keyboard look like", and a bare thumbnail does not answer it —
 * a picture that looks fine alone can swallow the key labels entirely.
 */
class WriterBackgroundGrid(context: Context) : GridLayout(context) {

    /** Asked to open a picker. The host activity owns the file-choosing contract. */
    var onAddRequested: (() -> Unit)? = null

    /** Fired whenever the active background changes, so colour options can be enabled or disabled. */
    var onActiveChanged: ((String) -> Unit)? = null

    init {
        columnCount = 3
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        refresh()
    }

    fun refresh() {
        removeAllViews()
        val active = PrismSettings.getWriterBackgroundImage()

        for (uri in PrismSettings.getWriterBackgroundLibrary()) {
            addView(tileFor(uri, uri == active))
        }
        addView(addTile())
    }

    // ── Tiles ──────────────────────────────────────────────────────────────

    private fun tileFor(uri: String, isActive: Boolean): View {
        val tile = PreviewTile(context, uri, isActive)
        tile.onTapped = {
            // TAPPING THE ACTIVE ONE TURNS IT OFF. One control, both directions: an image that is
            // already the background has no other meaning for a tap, and a separate "none" tile
            // would be a second thing to find.
            val next = if (isActive) "" else uri
            PrismSettings.setWriterBackgroundImage(next)
            onActiveChanged?.invoke(next)
            refresh()
        }
        tile.onDeleted = {
            PrismSettings.removeWriterBackground(uri)
            onActiveChanged?.invoke(PrismSettings.getWriterBackgroundImage())
            refresh()
        }
        return tile.apply { layoutParams = cellParams() }
    }

    private fun addTile(): View {
        val frame = FrameLayout(context).apply {
            layoutParams = cellParams()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(IosUi.fill(context))
            }
            setOnClickListener { onAddRequested?.invoke() }
        }
        frame.addView(
            TextView(context).apply {
                text = "+"
                textSize = 30f
                setTextColor(IosUi.secondaryLabel(context))
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ),
        )
        return frame
    }

    private fun cellParams(): LayoutParams = LayoutParams().apply {
        width = 0
        height = dp(96)
        columnSpec = spec(UNDEFINED, 1f)
        setMargins(dp(5), dp(5), dp(5), dp(5))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ── One preview ────────────────────────────────────────────────────────

    /**
     * A single background, drawn as it will appear behind the keys, with hold-to-delete.
     */
    private class PreviewTile(
        context: Context,
        private val uri: String,
        private val isActive: Boolean,
    ) : View(context) {

        var onTapped: (() -> Unit)? = null
        var onDeleted: (() -> Unit)? = null

        private val layout = KeyboardLayout.qwerty()
        private var image: Drawable? = null

        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dimPaint = Paint()
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path()

        /** 0..1 of the way through the hold. Drives the red fill and the deletion at the end. */
        private var deleteProgress = 0f
        private var holding = false
        private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
        private var tick: Runnable? = null

        init {
            image = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri)).use { probe ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(probe, null, bounds)
                    // A tile is ~100dp; decoding a full photo per tile would exhaust memory on a
                    // grid of a dozen.
                    val sample = maxOf(1, bounds.outHeight / 240)
                    context.contentResolver.openInputStream(Uri.parse(uri)).use { full ->
                        BitmapFactory.decodeStream(
                            full, null, BitmapFactory.Options().apply { inSampleSize = sample }
                        )?.let { BitmapDrawable(context.resources, it) }
                    }
                }
            }.getOrNull()
        }

        override fun onDraw(canvas: Canvas) {
            val radius = dp(14f)
            clip.reset()
            clip.addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
                android.graphics.Path.Direction.CW,
            )
            canvas.save()
            canvas.clipPath(clip)

            image?.let {
                it.setBounds(0, 0, width, height)
                it.draw(canvas)
            } ?: canvas.drawColor(Color.DKGRAY)

            val dim = PrismSettings.getWriterBackgroundDim()
            if (dim > 0) {
                dimPaint.color = Color.argb(dim * 255 / 100, 0, 0, 0)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            }

            // The miniature keyboard: enough of it to judge legibility, not a working preview.
            keyPaint.color = Color.argb(210, 255, 255, 255)
            val keyRadius = dp(2f)
            for (key in layout.keys) {
                if (key.action != Key.Action.CHARACTER) continue
                canvas.drawRoundRect(
                    RectF(
                        (key.centerX - key.width / 2) * width,
                        (key.centerY - key.height / 2) * height,
                        (key.centerX + key.width / 2) * width,
                        (key.centerY + key.height / 2) * height,
                    ),
                    keyRadius, keyRadius, keyPaint,
                )
            }

            if (deleteProgress > 0f) {
                // FILLS FROM THE BOTTOM so the progress is legible as "how much longer".
                deletePaint.color = Color.argb(190, 255, 59, 48)
                canvas.drawRect(
                    0f, height * (1f - deleteProgress), width.toFloat(), height.toFloat(), deletePaint
                )
            }
            canvas.restore()

            if (isActive) {
                ringPaint.color = 0xFF007AFF.toInt()
                ringPaint.strokeWidth = dp(3f)
                val inset = ringPaint.strokeWidth / 2
                canvas.drawRoundRect(
                    RectF(inset, inset, width - inset, height - inset), radius, radius, ringPaint
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holding = true
                    startHold()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    stopHold()
                    // A hold that reached the end already deleted; a short press is a tap.
                    if (deleteProgress < 1f) onTapped?.invoke()
                    deleteProgress = 0f
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopHold()
                    deleteProgress = 0f
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun startHold() {
            val started = System.currentTimeMillis()
            val runnable = object : Runnable {
                override fun run() {
                    if (!holding) return
                    val elapsed = System.currentTimeMillis() - started
                    deleteProgress = (elapsed.toFloat() / HOLD_TO_DELETE_MS).coerceIn(0f, 1f)
                    invalidate()

                    if (deleteProgress >= 1f) {
                        // DELETES ONLY IF THE FINGER IS STILL DOWN, which is what makes the fill a
                        // warning rather than a countdown you cannot stop: lifting early aborts.
                        holding = false
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onDeleted?.invoke()
                        return
                    }
                    ticker.postDelayed(this, 16)
                }
            }
            tick = runnable
            ticker.post(runnable)
        }

        private fun stopHold() {
            holding = false
            tick?.let { ticker.removeCallbacks(it) }
            tick = null
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density

        private companion object {
            const val HOLD_TO_DELETE_MS = 1200f
        }
    }
}
