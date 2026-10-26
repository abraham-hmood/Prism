package com.prism.launcher.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import kotlin.math.abs
import kotlin.math.min

/**
 * The crop-style offline-map chooser.
 *
 * It sits above the actual map, so the selected region is the real map rather than a second drawing
 * that can disagree with it. Everything outside the selection is dimmed; inside it the map is
 * untouched. Two fingers scale the selection independently on each axis, which is what makes a
 * tall, thin download possible without also grabbing the rest of the city.
 */
class MapCropOverlay(
    context: Context,
    private val map: OsmMapView,
    private val onCancel: () -> Unit,
    private val onDownload: (MapBounds, Int, Int) -> Unit,
) : FrameLayout(context) {

    var onStopDownload: (() -> Unit)? = null

    private val selection = SelectionView(context)
    private val sizePill: TextView
    private val progress: ProgressBar
    private val download: ImageButton
    private val close: TextView
    private var downloading = false
    private var stopping = false

    init {
        isClickable = true
        isFocusable = true
        addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val hint = TextView(context).apply {
            text = "Pinch to choose an area"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = pillBackground(0x99000000.toInt(), 18)
            setPadding(d(14), d(8), d(14), d(8))
            isClickable = false
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = d(18)
        })

        close = TextView(context).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = pillBackground(0x99000000.toInt(), 22)
            isClickable = true
            isFocusable = true
            contentDescription = "Close area selection"
            setOnClickListener { stopOrCancel() }
        }
        addView(close, LayoutParams(d(44), d(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = d(16)
            rightMargin = d(16)
        })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), 0, d(16), d(18))
        }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = IosUi.progressDrawable(context)
            visibility = View.GONE
        }
        bottom.addView(progress, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, d(5)
        ).apply { bottomMargin = d(10) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizePill = TextView(context).apply {
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
            isSingleLine = true
            // A minimum width also protects the first frame: the estimate is filled in from the
            // selection's onSizeChanged, and a wrap-content TextView measured before that text
            // existed can stay at padding width until something else forces a remeasure.
            minWidth = d(210)
            background = pillBackground(0xB3000000.toInt(), 18)
            setPadding(d(14), d(10), d(14), d(10))
        }
        row.addView(sizePill, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = d(12) })

        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        download = ImageButton(context).apply {
            setImageResource(R.drawable.ic_map_download_24)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(context))
            }
            setPadding(d(18), d(18), d(18), d(18))
            elevation = d(8).toFloat()
            contentDescription = "Download map"
            isClickable = true
            isFocusable = true
            setOnClickListener { beginDownload() }
        }
        row.addView(download, LinearLayout.LayoutParams(d(64), d(64)))
        bottom.addView(row)
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        selection.onSelectionChanged = { rect -> updateEstimate(rect) }
        selection.onSelectionReady = { rect -> updateEstimate(rect) }
    }

    /**
     * Recomputes the visible size estimate after the map's zoom changed.
     *
     * The selection rectangle stays where it is on screen, but its geographic bounds -- and with
     * them the tile count and the download-size pill -- change with the map's zoom. Without this
     * the chooser keeps quoting the count from before the zoom.
     */
    fun refreshEstimate() {
        if (downloading) return
        updateEstimate(selection.currentRect())
    }

    /** Called by the page after starting a download. */
    fun beginProgress() {
        downloading = true
        stopping = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        download.isEnabled = false
        download.alpha = 0.45f
        setPillText("Preparing…")
        close.contentDescription = "Stop download"
    }

    fun updateProgress(done: Int, total: Int) {
        if (!downloading) return
        progress.progress = if (total <= 0) 0 else ((done.toFloat() / total.toFloat()) * 100f).toInt()
        setPillText(if (stopping) "Stopping…" else "Downloading $done / $total tiles")
    }

    fun finishProgress() {
        downloading = false
        progress.visibility = View.GONE
        download.isEnabled = true
        download.alpha = 1f
    }

    fun isDownloading(): Boolean = downloading

    private fun stopOrCancel() {
        if (downloading) {
            if (!stopping) {
                stopping = true
                sizePill.text = "Stopping…"
                close.contentDescription = "Stop download"
                onStopDownload?.invoke()
            }
            return
        }
        onCancel()
    }

    private fun beginDownload() {
        if (downloading) return
        val bounds = selection.mapBounds(map) ?: return
        val count = MapStore.countTiles(bounds, MIN_ZOOM, MAX_ZOOM, MAX_TILES)
        if (count > MAX_TILES) {
            setPillText("Too large · pinch in")
            return
        }
        beginProgress()
        onDownload(bounds, MIN_ZOOM, MAX_ZOOM)
    }

    private fun updateEstimate(rect: RectF) {
        if (downloading) return
        val bounds = selection.mapBounds(map) ?: run {
            setPillText("Move the map to choose an area")
            download.isEnabled = false
            return
        }
        val count = MapStore.countTiles(bounds, MIN_ZOOM, MAX_ZOOM, MAX_TILES)
        if (count > MAX_TILES) {
            setPillText("Too large · pinch in")
            download.isEnabled = false
            download.alpha = 0.45f
        } else {
            setPillText("≈ ${MapStore.sizeLabel(count * MapStore.TILE_BYTES)} · $count tiles")
            download.isEnabled = true
            download.alpha = 1f
        }
    }

    private fun setPillText(value: String) {
        sizePill.text = value
        // The first estimate arrives during the selection's initial layout. Posting the remeasure
        // is the one path that works whether the text is set before or after that pass.
        sizePill.post { sizePill.requestLayout() }
    }

    private fun pillBackground(colour: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(colour)
            cornerRadius = d(radiusDp).toFloat()
        }

    private fun d(value: Int): Int = IosUi.dp(context, value.toFloat())

    private class SelectionView(context: Context) : View(context) {

        var onSelectionChanged: ((RectF) -> Unit)? = null
        var onSelectionReady: ((RectF) -> Unit)? = null

        private val rect = RectF()
        private val tint = Paint().apply { color = 0xC9000000.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Color.WHITE
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.7f)
            color = 0x66FFFFFF
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = Color.WHITE
            strokeCap = Paint.Cap.SQUARE
        }

        private var startWidth = 0f
        private var startHeight = 0f
        private var startX = 1f
        private var startY = 1f
        private var pinching = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (rect.isEmpty) {
                val side = min(w, h) * 0.68f
                val cx = w / 2f
                val cy = h * 0.46f
                rect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
                clamp()
            }
            onSelectionReady?.invoke(RectF(rect))
            onSelectionChanged?.invoke(RectF(rect))
        }

        fun currentRect(): RectF = RectF(rect)

        fun mapBounds(map: OsmMapView): MapBounds? {
            if (rect.width() < dp(8f) || rect.height() < dp(8f)) return null
            val topLeft = map.screenToLatLon(rect.left, rect.top)
            val bottomRight = map.screenToLatLon(rect.right, rect.bottom)
            val bounds = MapBounds(
                south = bottomRight.lat,
                west = topLeft.lon,
                north = topLeft.lat,
                east = bottomRight.lon,
            )
            return bounds.takeIf { it.valid }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, rect.top, tint)
            canvas.drawRect(0f, rect.bottom, w, h, tint)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, tint)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, tint)

            canvas.drawRect(rect, border)
            // A crop grid, at thirds, is the visual language people already read as "this is a
            // selection" rather than as another map overlay.
            val thirdW = rect.width() / 3f
            val thirdH = rect.height() / 3f
            canvas.drawLine(rect.left + thirdW, rect.top, rect.left + thirdW, rect.bottom, grid)
            canvas.drawLine(rect.left + thirdW * 2f, rect.top, rect.left + thirdW * 2f, rect.bottom, grid)
            canvas.drawLine(rect.left, rect.top + thirdH, rect.right, rect.top + thirdH, grid)
            canvas.drawLine(rect.left, rect.top + thirdH * 2f, rect.right, rect.top + thirdH * 2f, grid)

            val length = dp(18f)
            drawCorner(canvas, rect.left, rect.top, 1f, 1f, length)
            drawCorner(canvas, rect.right, rect.top, -1f, 1f, length)
            drawCorner(canvas, rect.left, rect.bottom, 1f, -1f, length)
            drawCorner(canvas, rect.right, rect.bottom, -1f, -1f, length)
        }

        private fun drawCorner(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float, length: Float) {
            canvas.drawLine(x, y, x + length * sx, y, corner)
            canvas.drawLine(x, y, x, y + length * sy, corner)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinching = false
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        // Fingers usually start level with each other, which makes the vertical
                        // separation zero. Dividing by that zero-equivalent turned the first
                        // diagonal pixel of movement into a full-height jump, so a small floor
                        // keeps each axis' scale smooth when its starting spread is tiny.
                        val floor = dp(48f)
                        startX = abs(event.getX(0) - event.getX(1)).coerceAtLeast(floor)
                        startY = abs(event.getY(0) - event.getY(1)).coerceAtLeast(floor)
                        startWidth = rect.width()
                        startHeight = rect.height()
                        pinching = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && event.pointerCount >= 2) {
                        val dx = abs(event.getX(0) - event.getX(1)).coerceAtLeast(1f)
                        val dy = abs(event.getY(0) - event.getY(1)).coerceAtLeast(1f)
                        val targetWidth = (startWidth * (dx / startX)).coerceIn(dp(88f), width - dp(24f))
                        val targetHeight = (startHeight * (dy / startY)).coerceIn(dp(88f), height - dp(24f))
                        val cx = width / 2f
                        val cy = height * 0.46f
                        rect.set(
                            cx - targetWidth / 2f,
                            cy - targetHeight / 2f,
                            cx + targetWidth / 2f,
                            cy + targetHeight / 2f,
                        )
                        clamp()
                        invalidate()
                        onSelectionChanged?.invoke(RectF(rect))
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep the selection where the pinch left it. A remaining finger should not
                    // suddenly become a one-finger drag of the box.
                    pinching = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinching = false
            }
            return true
        }

        private fun clamp() {
            val cx = width / 2f
            val cy = height * 0.46f
            val halfW = min(rect.width() / 2f, width / 2f - dp(12f))
            val halfH = min(rect.height() / 2f, height / 2f - dp(12f))
            rect.set(
                (cx - halfW).coerceAtLeast(dp(12f)),
                (cy - halfH).coerceAtLeast(dp(12f)),
                (cx + halfW).coerceAtMost(width - dp(12f)),
                (cy + halfH).coerceAtMost(height - dp(12f)),
            )
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }

    private companion object {
        const val MIN_ZOOM = 12
        const val MAX_ZOOM = 16
        const val MAX_TILES = 4_000
    }
}
