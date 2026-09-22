package com.prism.launcher.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.DesktopItem
import com.prism.launcher.DesktopShortcutStore

/**
 * Draws the widgets on one desktop page, on top of the icon grid.
 *
 * ## Why a layer over the grid rather than widgets inside it
 *
 * The grid is a RecyclerView with a GridLayoutManager, and a GridLayoutManager can make an item
 * wider but not taller -- `spanSizeLookup` returns a column count and nothing else. A 2x2 widget,
 * and a resize handle dragged downwards, cannot be expressed in it at all. The alternative was to
 * replace the grid with a custom cell layout, which is what a launcher normally does, but that grid
 * also carries drag-to-reorder, drops from the app drawer and the delete zone, and rewriting it
 * would have put all three at risk for a feature that does not need them rewritten.
 *
 * So the grid keeps owning WHERE THINGS ARE and this layer owns WHAT A WIDGET LOOKS LIKE. The cells
 * a widget covers are real entries in the same 24-cell list as every icon ([DesktopItem.Widget] and
 * [DesktopItem.Occupied]), so "is this cell free" has exactly one answer and every existing
 * behaviour keeps working unchanged.
 *
 * ## Geometry comes from the grid's own children
 *
 * Cell rectangles are read from the RecyclerView's laid-out child views rather than computed from
 * width/4 and height/6. The grid has padding, its rows are sized by their content, and a computed
 * guess drifts from where the icons actually are -- which shows up as a widget sitting a few pixels
 * out of alignment with everything around it.
 */
class DesktopWidgetLayer(
    context: Context,
    private val grid: RecyclerView,
    private val store: DesktopShortcutStore,
    private val cellCount: Int,
    private val onChanged: () -> Unit,
) : FrameLayout(context) {

    private val columns = DesktopShortcutStore.COLUMNS
    private val rows = cellCount / columns

    /**
     * The grid as this layer last read it.
     *
     * Held rather than re-read because onLayout runs on every pass and readGrid touches a file.
     * Updated by [refresh] and by anything here that writes.
     */
    private var cells: List<DesktopItem?> = emptyList()

    /**
     * The pixel size each widget was last told it had.
     *
     * The reason this exists is a feedback loop that took the whole launcher down: onLayout called
     * updateAppWidgetOptions on every pass, that made the provider push a new RemoteViews, that
     * caused another layout, and so on -- five thousand widget updates and a main thread with no
     * time left for anything else. Telling a widget its size only when the size has actually changed
     * breaks the cycle.
     */
    private val appliedSizes = HashMap<Int, Pair<Int, Int>>()

    /** The widget being resized, as a cell index, or -1. */
    private var resizingIndex = -1
    private var resizeRect = Rect()

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    private val handleRadius = 9f * context.resources.displayMetrics.density
    private val touchSlop = 24f * context.resources.displayMetrics.density

    init {
        // Transparent to touches except where a widget or a resize handle is: the icon grid is
        // underneath and has to keep receiving everything else.
        setWillNotDraw(false)
        isClickable = false
    }

    /** Rebuilds every widget view from the stored grid. */
    fun refresh() {
        removeAllViews()
        appliedSizes.clear()
        val cells = store.readGrid(cellCount)
        this.cells = cells

        for (index in cells.indices) {
            val widget = cells[index] as? DesktopItem.Widget ?: continue
            val info = PrismWidgetHost.providerInfo(context, widget.appWidgetId)
            if (info == null) {
                // The provider is gone -- the app was uninstalled while the widget sat here. Clear
                // the cells rather than leaving a hole nothing can fill or explain.
                val mutable = cells.toMutableList()
                DesktopShortcutStore.clearWidget(mutable, index)
                store.writeGrid(mutable)
                this.cells = mutable
                PrismWidgetHost.releaseId(context, widget.appWidgetId)
                onChanged()
                continue
            }

            val host = runCatching {
                PrismWidgetHost.createView(context, widget.appWidgetId, info)
            }.getOrNull() ?: continue

            host.setAppWidget(widget.appWidgetId, info)
            addView(host, LayoutParams(1, 1))
            host.tag = index

            // On the host view rather than on this layer: a widget's own content has to keep
            // receiving taps, and a long press is the one gesture RemoteViews content almost never
            // claims, so it is the one that can be borrowed without breaking the widget.
            host.setOnLongClickListener {
                showMenu(index)
                true
            }
        }
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (child in children()) {
            val index = child.tag as? Int ?: continue
            val widget = cells.getOrNull(index) as? DesktopItem.Widget ?: continue
            val rect = cellRect(index, widget.spanX, widget.spanY) ?: continue

            // MEASURED BEFORE IT IS LAID OUT. layout() sets a view's bounds but not its measured
            // size, and an AppWidgetHostView lays its RemoteViews content out against the measured
            // size -- so without this the widget sat at exactly the right position, at the right
            // size, drawing a 1x1 pixel version of itself, which looks identical to a widget that
            // silently failed to load.
            child.measure(
                MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY),
            )
            child.layout(rect.left, rect.top, rect.right, rect.bottom)

            // Only when it changed. See [appliedSizes] for the loop this avoids.
            val size = rect.width() to rect.height()
            if (appliedSizes[widget.appWidgetId] != size) {
                appliedSizes[widget.appWidgetId] = size
                val density = resources.displayMetrics.density
                PrismWidgetHost.applySize(
                    context, child as android.appwidget.AppWidgetHostView, widget.appWidgetId,
                    (rect.width() / density).toInt(), (rect.height() / density).toInt(),
                )
            }
        }
    }

    private fun children(): List<View> = (0 until childCount).map { getChildAt(it) }

    /**
     * The pixel rectangle covering [spanX] x [spanY] cells from [index].
     *
     * Null when the grid has not laid out that cell yet, which happens on the first pass and
     * whenever a cell is scrolled out of view -- the caller skips rather than guessing.
     */
    private fun cellRect(index: Int, spanX: Int, spanY: Int): Rect? {
        val manager = grid.layoutManager ?: return null
        val first = manager.findViewByPosition(index) ?: return null

        val lastColumn = index % columns + spanX - 1
        val lastRow = index / columns + spanY - 1
        val lastIndex = lastRow * columns + lastColumn

        val last = manager.findViewByPosition(lastIndex)
        val leftEdge = first.left + grid.left
        val topEdge = first.top + grid.top
        return if (last != null) {
            Rect(leftEdge, topEdge, last.right + grid.left, last.bottom + grid.top)
        } else {
            // The far corner is not laid out (a tall widget near the bottom). Extrapolate from the
            // first cell, which is exact as long as cells are uniform -- and in this grid they are.
            Rect(
                leftEdge, topEdge,
                leftEdge + first.width * spanX,
                topEdge + first.height * spanY,
            )
        }
    }

    // ── Resizing ───────────────────────────────────────────────────────────

    /** Puts one widget into resize mode, showing its frame and corner handle. */
    fun beginResize(index: Int) {
        resizingIndex = index
        val widget = cells.getOrNull(index) as? DesktopItem.Widget ?: return
        resizeRect = cellRect(index, widget.spanX, widget.spanY) ?: return
        invalidate()
    }

    fun endResize() {
        resizingIndex = -1
        invalidate()
    }

    fun isResizing(): Boolean = resizingIndex >= 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (resizingIndex < 0) return
        canvas.drawRect(resizeRect, framePaint)
        // One handle, at the bottom-right. A widget's top-left is its anchor cell, so dragging any
        // other corner would mean moving the widget and resizing it at the same time -- two
        // gestures in one, and the move is already available by dragging the widget itself.
        canvas.drawCircle(resizeRect.right.toFloat(), resizeRect.bottom.toFloat(), handleRadius, handlePaint)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        resizingIndex >= 0 && near(event.x, event.y, resizeRect.right, resizeRect.bottom)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (resizingIndex < 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                return near(event.x, event.y, resizeRect.right, resizeRect.bottom)

            MotionEvent.ACTION_MOVE -> {
                applyResize(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endResize()
                return true
            }
        }
        return false
    }

    private fun near(x: Float, y: Float, targetX: Int, targetY: Int): Boolean =
        kotlin.math.abs(x - targetX) < touchSlop && kotlin.math.abs(y - targetY) < touchSlop

    /** Turns a drag position into new spans, refusing any that would not fit. */
    private fun applyResize(x: Float, y: Float) {
        val index = resizingIndex
        val widget = cells.getOrNull(index) as? DesktopItem.Widget ?: return
        val unit = cellRect(index, 1, 1) ?: return

        val spanX = (((x - unit.left) / unit.width()).toInt() + 1).coerceIn(1, columns)
        val spanY = (((y - unit.top) / unit.height()).toInt() + 1).coerceIn(1, rows)
        if (spanX == widget.spanX && spanY == widget.spanY) return

        val mutable = cells.toMutableList()
        DesktopShortcutStore.clearWidget(mutable, index)
        if (!DesktopShortcutStore.fits(mutable, index, spanX, spanY)) {
            // Put it back at its old size and stop growing. Refusing rather than pushing the
            // neighbours aside: a resize that rearranges other icons is a surprise, and the user
            // can always move them first.
            DesktopShortcutStore.placeWidget(mutable, index, widget)
            return
        }

        val resized = widget.copy(spanX = spanX, spanY = spanY)
        DesktopShortcutStore.placeWidget(mutable, index, resized)
        store.writeGrid(mutable)
        cells = mutable
        resizeRect = cellRect(index, spanX, spanY) ?: resizeRect
        onChanged()
        requestLayout()
        invalidate()
    }

    /**
     * The menu a long press opens: resize or remove.
     *
     * Two entries because they are the only two things that can be done to a widget in place.
     * Moving one is deliberately absent: it would mean dragging a live view across a grid that the
     * RecyclerView underneath is simultaneously reordering, and the honest way to move a widget is
     * to remove it and drop a new one where it belongs.
     */
    private fun showMenu(index: Int) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Widget")
            .setItems(arrayOf("Resize", "Remove")) { _, which ->
                if (which == 0) beginResize(index) else remove(index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Removes a widget and releases its id. */
    fun remove(index: Int) {
        val mutable = store.readGrid(cellCount).toMutableList()
        val widget = mutable.getOrNull(index) as? DesktopItem.Widget ?: return
        DesktopShortcutStore.clearWidget(mutable, index)
        store.writeGrid(mutable)
        cells = mutable
        PrismWidgetHost.releaseId(context, widget.appWidgetId)
        endResize()
        onChanged()
        refresh()
    }

    /** Which widget, if any, covers a point in this layer's coordinates. */
    fun widgetIndexAt(x: Float, y: Float): Int {
        for (child in children()) {
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return child.tag as? Int ?: -1
            }
        }
        return -1
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
