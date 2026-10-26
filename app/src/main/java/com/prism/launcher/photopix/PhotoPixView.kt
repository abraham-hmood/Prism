package com.prism.launcher.photopix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.prism.core.PrismImage
import com.prism.launcher.nora.IosUi
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The PhotoPix editor.
 *
 * This is the same view the PhotoPix ACTIVITY and the PhotoPix DESKTOP PAGE show. That was the
 * request and it is also the only way the two stay equal: a page that re-implemented the editor
 * would diverge on the first fix.
 *
 * ## The rail is the tool list, collapsed by default
 *
 * The left edge is a column of glyphs matching Photoshop's toolbar groups, with a chevron that
 * expands it to names. Collapsed is the resting state because the canvas wants every pixel; the
 * expanded state exists because a glyph alone is not a label for a tool nobody has used before.
 *
 * ## Strokes preview, then commit
 *
 * Dragging does not run the brush engine per motion event. It draws a polyline preview, and the
 * stroke is committed on release. Running the engine on every MOVE would recomposite the whole
 * document dozens of times a second on the UI thread, which is how an editor gets a reputation for
 * being unusable on a phone. The pattern for the other tools is the same: preview while dragging,
 * commit on up.
 */
class PhotoPixView(
    context: Context,
    image: PrismImage,
    initialMode: String = PhotoPixActivity.MODE_EDIT,
    private val sourceId: String = "",
) : FrameLayout(context) {

    /** The tools, in the order the toolbar draws them. */
    enum class Tool(val glyph: String, val label: String, val paint: Boolean) {
        MOVE("✥", "Move", false),
        MARQUEE("▭", "Marquee", false),
        ELLIPSE_SELECT("◯", "Ellipse select", false),
        LASSO("◌", "Lasso", false),
        WAND("✦", "Magic wand", false),
        CROP("⛶", "Crop", false),
        FRAME("▣", "Frame", false),
        EYEDROPPER("⌖", "Eyedropper", false),
        HEAL("✚", "Healing", true),
        BRUSH("🖌", "Brush", true),
        CLONE("⧉", "Clone stamp", true),
        ERASER("▱", "Eraser", true),
        GRADIENT("▤", "Gradient", false),
        BLUR("◍", "Blur", true),
        DODGE("◐", "Dodge", true),
        BURN("◑", "Burn", true),
        PEN("✎", "Pen", false),
        TYPE("T", "Type", false),
        PATH_SELECT("⬚", "Path select", false),
        LINE("╱", "Line", false),
        HAND("✋", "Hand", false),
        ZOOM("⌕", "Zoom", false),
        MORE("⋯", "More tools", false),
    }

    private enum class SelectMode { REPLACE, ADD, SUBTRACT, INTERSECT }

    private val document = PixDocument.fromImage(image, title = "Photo")
    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private val accent = palette.accent.toInt()
    private val textColor = palette.text.toInt()
    private val mutedColor = palette.muted.toInt()

    private val canvas = PixCanvas(context)
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: HorizontalScrollView
    private lateinit var options: LinearLayout
    private lateinit var historyLabel: TextView

    private var tool = when (initialMode) {
        PhotoPixActivity.MODE_CROP -> Tool.CROP
        PhotoPixActivity.MODE_MARK -> Tool.BRUSH
        PhotoPixActivity.MODE_TEXT -> Tool.TYPE
        else -> Tool.MOVE
    }
    private var railExpanded = false

    private var brush = PixBrush(size = 48.0, hardness = 0.7, opacity = 1.0)
    private var foregroundColor = 0xFFFF3B30.toInt()
    private var backgroundColor = 0xFFFFFFFF.toInt()
    private var paintMode = PaintMode.PAINT
    private var cloneSource: Pair<Double, Double>? = null
    private var selectionMode = SelectMode.REPLACE
    private var tolerance = 32
    private var feather = 0

    private var pendingText: Triple<String, Double, Double>? = null

    /** Set when the document has unsaved edits, so the activity can warn on the way out. */
    var dirty = false
        private set

    init {
        setBackgroundColor(palette.background.toInt())
        PixDocument.textRasterizer = AndroidPixText
        build()
        applyInitialMode(initialMode)
        refreshOptions()
        refreshHistoryLabel()
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Top bar: undo/redo, title, and the panels. Its height is deliberately one row.
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(size(8), size(10), size(8), size(6))
        }
        top.addView(glyphButton("↶", "Undo", enabled = true) { undo() })
        top.addView(glyphButton("↷", "Redo", enabled = true) { redo() })
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(10), 0, 0, 0)
        }
        titles.addView(TextView(context).apply {
            text = "PhotoPix"
            textSize = 16f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        historyLabel = TextView(context).apply {
            textSize = 10f
            setTextColor(mutedColor)
        }
        titles.addView(historyLabel)
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(glyphButton("▤", "Layers") { PhotoPixPanels.layers(this) })
        top.addView(glyphButton("◑", "Adjust") { PhotoPixPanels.adjustments(this) })
        top.addView(glyphButton("◍", "Filters") { PhotoPixPanels.filters(this) })
        top.addView(glyphButton("⛶", "Image") { PhotoPixPanels.image(this) })
        top.addView(glyphButton("⇪", "Export") { PhotoPixPanels.export(this) })
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Middle: rail + canvas.
        val middle = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        railScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.card.toInt())
        }
        rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(3), size(6), size(3), size(6))
        }
        railScroll.addView(rail)
        middle.addView(railScroll, LinearLayout.LayoutParams(size(44), ViewGroup.LayoutParams.MATCH_PARENT))

        val canvasHolder = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.surface.toInt())
            }
        }
        canvasHolder.addView(canvas, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        middle.addView(canvasHolder, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
        ))

        root.addView(middle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // Options bar. Horizontally scrollable because a brush row with three sliders plus a
        // swatch does not fit a phone, and forcing it to fit would make every control too small
        // to hit.
        val optionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.card.toInt())
        }
        options = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(size(8), size(6), size(8), size(6))
        }
        optionsScroll.addView(options)
        root.addView(optionsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildRail()
    }

    private fun buildRail() {
        rail.removeAllViews()
        val collapse = TextView(context).apply {
            text = if (railExpanded) "«" else "»"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(0, size(4), 0, size(8))
            contentDescription = if (railExpanded) "Collapse the toolbar" else "Expand the toolbar"
            setOnClickListener {
                railExpanded = !railExpanded
                buildRail()
            }
        }
        rail.addView(collapse, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        Tool.entries.forEach { entry ->
            val chosen = entry == tool
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = entry.label
                setPadding(size(2), size(6), size(2), size(6))
                if (chosen) {
                    background = GradientDrawable().apply {
                        cornerRadius = size(6).toFloat()
                        setColor(palette.raised.toInt())
                    }
                }
                setOnClickListener {
                    if (entry == Tool.MORE) {
                        showExtraTools()
                        return@setOnClickListener
                    }
                    tool = entry
                    paintMode = modeFor(entry)
                    cloneSource = null
                    canvas.preview.clear()
                    canvas.invalidate()
                    buildRail()
                    refreshOptions()
                }
                addView(TextView(context).apply {
                    text = entry.glyph
                    textSize = if (entry.glyph == "T") 17f else 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (chosen) accent else textColor)
                    minWidth = size(30)
                })
                if (railExpanded) {
                    addView(TextView(context).apply {
                        text = entry.label
                        textSize = 11f
                        setTextColor(if (chosen) accent else mutedColor)
                        setPadding(size(6), 0, size(10), 0)
                        maxLines = 1
                    })
                }
            }
            rail.addView(row, LinearLayout.LayoutParams(
                if (railExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = size(2) })
        }

        railScroll.layoutParams = LinearLayout.LayoutParams(
            if (railExpanded) size(150) else size(44),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun modeFor(entry: Tool): PaintMode = when (entry) {
        Tool.BRUSH, Tool.PEN, Tool.LINE, Tool.FRAME, Tool.GRADIENT, Tool.TYPE -> PaintMode.PAINT
        Tool.ERASER -> PaintMode.ERASE
        Tool.BLUR -> PaintMode.BLUR
        Tool.HEAL -> PaintMode.HEAL
        Tool.CLONE -> PaintMode.CLONE
        Tool.DODGE -> PaintMode.DODGE
        Tool.BURN -> PaintMode.BURN
        else -> PaintMode.PAINT
    }

    private fun refreshOptions() {
        options.removeAllViews()
        options.addView(colorSwatch())
        options.addView(glyphButton("⇄", "Swap colours") {
            val previous = foregroundColor
            foregroundColor = backgroundColor
            backgroundColor = previous
            refreshOptions()
        })

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.BLUR, Tool.DODGE, Tool.BURN, Tool.HEAL, Tool.CLONE -> {
                options.addView(slider("Size", 1, 300, brush.size.toInt()) { value ->
                    brush = brush.copy(size = value.toDouble())
                })
                options.addView(slider("Hard", 0, 100, (brush.hardness * 100).toInt()) { value ->
                    brush = brush.copy(hardness = value / 100.0)
                })
                options.addView(slider("Opacity", 5, 100, (brush.opacity * 100).toInt()) { value ->
                    brush = brush.copy(opacity = value / 100.0)
                })
                if (tool == Tool.CLONE || tool == Tool.HEAL) {
                    options.addView(hint(
                        if (cloneSource == null) "Tap the photo to set the sample"
                        else "Sampling from that point — drag to paint",
                    ))
                }
            }
            Tool.MARQUEE, Tool.ELLIPSE_SELECT, Tool.LASSO, Tool.WAND, Tool.PATH_SELECT -> {
                options.addView(chip("New") { setSelectMode(SelectMode.REPLACE) })
                options.addView(chip("Add") { setSelectMode(SelectMode.ADD) })
                options.addView(chip("Subtract") { setSelectMode(SelectMode.SUBTRACT) })
                options.addView(chip("Intersect") { setSelectMode(SelectMode.INTERSECT) })
                if (tool == Tool.WAND) {
                    options.addView(slider("Tolerance", 0, 120, tolerance) { tolerance = it })
                }
                options.addView(slider("Feather", 0, 40, feather) { feather = it; featherSelection(it) })
                options.addView(chip("Select all") { selectAll() })
                options.addView(chip("Deselect") { deselect() })
                options.addView(chip("Invert") { invertSelection() })
            }
            Tool.CROP -> {
                options.addView(chip("Apply crop") { applyCrop() })
                options.addView(chip("Reset") { canvas.cropRect = null; canvas.invalidate() })
                options.addView(hint("Drag on the photo"))
            }
            Tool.TYPE -> {
                options.addView(chip("Add text") { promptText() })
                options.addView(slider("Size", 8, 240, 48) { canvas.textSize = it })
            }
            Tool.GRADIENT -> {
                options.addView(chip("Linear") { canvas.radialGradient = false })
                options.addView(chip("Radial") { canvas.radialGradient = true })
            }
            Tool.ZOOM -> {
                options.addView(chip("Fit") { canvas.fit() })
                options.addView(chip("100%") { canvas.zoomTo(1f) })
                options.addView(chip("+") { canvas.zoomBy(1.4f) })
                options.addView(chip("−") { canvas.zoomBy(1 / 1.4f) })
            }
            Tool.HAND -> options.addView(hint("Drag to pan · pinch to zoom"))
            Tool.MOVE -> {
                options.addView(hint("Drag to move the active layer"))
                options.addView(chip("Centre") { centreLayer() })
            }
            else -> options.addView(hint("Drag on the photo"))
        }
    }

    private fun setSelectMode(mode: SelectMode) {
        selectionMode = mode
        refreshOptions()
    }

    // ── Controls ──────────────────────────────────────────────────────────

    private fun glyphButton(glyph: String, description: String, enabled: Boolean = true, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (enabled) textColor else mutedColor)
            contentDescription = description
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setPadding(size(9), size(7), size(9), size(7))
            setOnClickListener { onClick() }
        }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(textColor)
            setPadding(size(10), size(6), size(10), size(6))
            background = GradientDrawable().apply {
                cornerRadius = size(9).toFloat()
                setColor(palette.raised.toInt())
            }
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.rightMargin = size(6)
            layoutParams = params
        }

    private fun hint(label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(size(8), 0, size(8), 0)
        }

    private fun colorSwatch(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(foregroundColor)
            setStroke(size(2), textColor)
        }
        contentDescription = "Foreground colour"
        layoutParams = LinearLayout.LayoutParams(size(30), size(30)).apply { rightMargin = size(6) }
        setOnClickListener { pickColor() }
    }

    private fun slider(label: String, from: Int, to: Int, value: Int, onChange: (Int) -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(6), 0, size(6), 0)
            addView(TextView(context).apply {
                text = "$label $value"
                textSize = 10f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
            })
            addView(SeekBar(context).apply {
                max = (to - from).coerceAtLeast(1)
                progress = (value - from).coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val actual = from + progress
                        (bar.parent as? LinearLayout)?.getChildAt(0)?.let { labelView ->
                            (labelView as? TextView)?.text = "$label $actual"
                        }
                        onChange(actual)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }, LinearLayout.LayoutParams(size(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    // ── Editing operations ────────────────────────────────────────────────

    private fun snapshot(label: String) {
        document.history.push(label, document)
        dirty = true
        refreshHistoryLabel()
    }

    private fun undo() {
        if (document.history.undo(document)) {
            afterDocumentChange()
        }
    }

    private fun redo() {
        if (document.history.redo(document)) {
            afterDocumentChange()
        }
    }

    private fun afterDocumentChange() {
        dirty = true
        canvas.invalidateDocument()
        refreshHistoryLabel()
        refreshOptions()
    }

    private fun refreshHistoryLabel() {
        val undo = document.history.undoLabel
        val redo = document.history.redoLabel
        historyLabel.text = when {
            undo != null && redo != null -> "undo $undo · redo $redo"
            undo != null -> "undo $undo"
            redo != null -> "redo $redo"
            else -> "no changes yet"
        }
    }

    private fun applyCrop() {
        val rect = canvas.cropRect ?: return
        val x = rect.left.toInt().coerceIn(0, document.width - 1)
        val y = rect.top.toInt().coerceIn(0, document.height - 1)
        val w = rect.width().toInt().coerceIn(1, document.width - x)
        val h = rect.height().toInt().coerceIn(1, document.height - y)
        snapshot("Crop")
        document.crop(x, y, w, h)
        canvas.cropRect = null
        canvas.invalidateDocument()
        canvas.fit()
    }

    private fun selectAll() {
        document.selection = PixSelection.all(document.width, document.height)
        canvas.invalidate()
    }

    private fun deselect() {
        document.selection = null
        canvas.invalidate()
    }

    private fun invertSelection() {
        document.selection = document.selectionOrAll().inverted()
        canvas.invalidate()
    }

    private fun featherSelection(radius: Int) {
        val current = document.selection ?: return
        document.selection = current.feathered(radius)
        canvas.invalidate()
    }

    private fun centreLayer() {
        val layer = document.activeLayer
        layer.offsetX = (document.width - layer.image.width) / 2
        layer.offsetY = (document.height - layer.image.height) / 2
        afterDocumentChange()
    }

    private fun promptText() {
        val input = android.widget.EditText(context).apply {
            hint = "Text"
            setTextColor(textColor)
            setHintTextColor(mutedColor)
        }
        PrismDialogs.Builder(context)
            .setTitle("Add text")
            .setView(input)
            .setPositiveButton("Place") { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    snapshot("Text")
                    val placed = document.placeText(
                        text,
                        (document.width / 2 - 100).coerceAtLeast(0),
                        (document.height / 2).coerceAtLeast(0),
                        canvas.textSize,
                        foregroundColor,
                    )
                    if (!placed) {
                        android.widget.Toast.makeText(context, "Text could not be drawn", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    canvas.invalidateDocument()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickColor() {
        val mode = PrismThemes.modeFor(IosUi.isDark(context))
        val dialog = android.app.Dialog(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(16), size(16), size(16), size(8))
        }
        column.addView(TextView(context).apply {
            text = "#${"%06X".format(foregroundColor and 0xFFFFFF)}"
            textSize = 16f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        val red = IntArray(1) { (foregroundColor shr 16) and 0xFF }
        val green = IntArray(1) { (foregroundColor shr 8) and 0xFF }
        val blue = IntArray(1) { foregroundColor and 0xFF }
        val swatch = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = size(8).toFloat()
                setColor(foregroundColor)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size(64))
        }
        column.addView(swatch)
        fun update() {
            foregroundColor = (0xFF shl 24) or (red[0] shl 16) or (green[0] shl 8) or blue[0]
            (swatch.background as GradientDrawable).setColor(foregroundColor)
            (column.getChildAt(0) as TextView).text = "#${"%06X".format(foregroundColor and 0xFFFFFF)}"
            refreshOptions()
        }
        column.addView(slider("R", 0, 255, red[0]) { red[0] = it; update() })
        column.addView(slider("G", 0, 255, green[0]) { green[0] = it; update() })
        column.addView(slider("B", 0, 255, blue[0]) { blue[0] = it; update() })
        // The palette's own accent and the two most useful neutrals, one tap away.
        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(accent, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt()).forEach { color ->
            val swatchButton = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(size(1), mode.palette.divider.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(size(32), size(32)).apply { rightMargin = size(8) }
                setOnClickListener {
                    foregroundColor = color
                    red[0] = (color shr 16) and 0xFF
                    green[0] = (color shr 8) and 0xFF
                    blue[0] = color and 0xFF
                    update()
                }
            }
            presets.addView(swatchButton)
        }
        column.addView(presets)
        dialog.setContentView(column)
        dialog.show()
    }

    private fun showExtraTools() {
        val extras = arrayOf("Pencil", "Smudge", "Sharpen", "Sponge", "Paint bucket", "Set sample size 5×5")
        PrismDialogs.Builder(context)
            .setTitle("More tools")
            .setItems(extras) { _, which ->
                when (which) {
                    0 -> { tool = Tool.BRUSH; paintMode = PaintMode.PAINT; brush = brush.copy(hardness = 1.0) }
                    1 -> { tool = Tool.BRUSH; paintMode = PaintMode.SMUDGE }
                    2 -> { tool = Tool.BLUR; paintMode = PaintMode.SHARPEN }
                    3 -> { tool = Tool.BURN; paintMode = PaintMode.SPONGE }
                    4 -> fillWithForeground()
                    5 -> tolerance = 8
                }
                buildRail()
                refreshOptions()
            }
            .show()
    }

    private fun fillWithForeground() {
        snapshot("Fill")
        document.fill(foregroundColor)
        afterDocumentChange()
    }

    // ── Canvas callbacks ──────────────────────────────────────────────────

    /**
     * Commits a finished gesture.
     *
     * One entry point for every tool so history, cache invalidation and the options bar cannot get
     * out of step -- every path that changes pixels goes through here or [snapshot] directly.
     */
    internal fun commitStroke(points: List<Pair<Double, Double>>, gesture: Gesture) {
        when (gesture) {
            Gesture.PAINT -> {
                snapshot(tool.label)
                PixBrushEngine.stroke(
                    document, points, paintMode, brush, foregroundColor,
                    cloneSource = cloneSource,
                )
            }
            Gesture.MARQUEE -> {
                val rect = rectOf(points)
                document.selection = combine(PixSelection.rect(
                    document.width, document.height,
                    rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt(),
                ))
            }
            Gesture.ELLIPSE -> {
                val rect = rectOf(points)
                document.selection = combine(PixSelection.ellipse(
                    document.width, document.height,
                    rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt(),
                ))
            }
            Gesture.LASSO -> {
                if (points.size > 2) {
                    document.selection = combine(PixSelection.polygon(document.width, document.height, points))
                }
            }
            Gesture.GRADIENT -> {
                if (points.size >= 2) {
                    snapshot("Gradient")
                    document.gradient(
                        points.first().first.toInt(), points.first().second.toInt(),
                        points.last().first.toInt(), points.last().second.toInt(),
                        foregroundColor, backgroundColor, canvas.radialGradient,
                    )
                }
            }
            // The shape tools rasterise straight into the layer's own pixels, so their points are
            // converted from document space here. The brush engine does that conversion itself,
            // which is why only this branch needs it.
            Gesture.LINE -> {
                if (points.size >= 2) {
                    snapshot("Line")
                    val layer = document.activeLayer
                    PixShapes.line(
                        layer.image, local(points.first(), layer), local(points.last(), layer),
                        foregroundColor, brush.size * 0.35, document.selectionFor(layer),
                    )
                }
            }
            Gesture.FRAME -> {
                if (points.size >= 2) {
                    snapshot("Frame")
                    val layer = document.activeLayer
                    PixShapes.rectangle(
                        layer.image, local(points.first(), layer), local(points.last(), layer),
                        foregroundColor, brush.size * 0.35, filled = false, selection = document.selectionFor(layer),
                    )
                }
            }
            Gesture.PATH -> {
                if (points.size > 2) {
                    snapshot("Path")
                    val layer = document.activeLayer
                    PixShapes.polygon(
                        layer.image, points.map { local(it, layer) }, foregroundColor, brush.size * 0.3,
                        filled = false, selection = document.selectionFor(layer),
                    )
                }
            }
            Gesture.CROP -> Unit // The crop applies from the options bar, so it can be adjusted.
        }
        dirty = true
        canvas.invalidateDocument()
        refreshHistoryLabel()
    }

    internal fun tapped(x: Double, y: Double) {
        when (tool) {
            Tool.WAND -> {
                val image = document.flatten()
                val ix = x.toInt().coerceIn(0, image.width - 1)
                val iy = y.toInt().coerceIn(0, image.height - 1)
                document.selection = combine(
                    PixSelection.wand(image, ix, iy, tolerance, contiguous = true),
                )
                canvas.invalidate()
            }
            Tool.EYEDROPPER -> {
                val image = document.flatten()
                val ix = x.toInt().coerceIn(0, image.width - 1)
                val iy = y.toInt().coerceIn(0, image.height - 1)
                foregroundColor = PixFilters.averageColor(image, ix, iy, 2)
                refreshOptions()
            }
            Tool.CLONE, Tool.HEAL -> {
                cloneSource = x to y
                refreshOptions()
            }
            Tool.TYPE -> promptText()
            Tool.ZOOM -> canvas.zoomBy(2f)
            else -> Unit
        }
    }

    private fun combine(next: PixSelection): PixSelection? {
        val current = document.selection
        val mode = when (selectionMode) {
            SelectMode.REPLACE -> PixSelection.Mode.REPLACE
            SelectMode.ADD -> PixSelection.Mode.ADD
            SelectMode.SUBTRACT -> PixSelection.Mode.SUBTRACT
            SelectMode.INTERSECT -> PixSelection.Mode.INTERSECT
        }
        if (current == null || mode == PixSelection.Mode.REPLACE) return next
        return current.combine(next, mode)
    }

    private fun local(point: Pair<Double, Double>, layer: PixLayer): Pair<Double, Double> =
        (point.first - layer.offsetX) to (point.second - layer.offsetY)

    private fun rectOf(points: List<Pair<Double, Double>>): RectF {
        if (points.isEmpty()) return RectF()
        var left = points[0].first
        var top = points[0].second
        var right = left
        var bottom = top
        for ((x, y) in points) {
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    internal fun currentTool(): Tool = tool

    private fun applyInitialMode(mode: String) {
        when (mode) {
            PhotoPixActivity.MODE_ADJUST -> post { PhotoPixPanels.adjustments(this) }
            PhotoPixActivity.MODE_FILTER -> post { PhotoPixPanels.filters(this) }
            PhotoPixActivity.MODE_CROP -> Unit
            PhotoPixActivity.MODE_MARK -> {
                foregroundColor = 0xFFFF3B30.toInt()
                brush = brush.copy(size = 26.0, hardness = 0.9)
            }
            PhotoPixActivity.MODE_ROTATE -> post { PhotoPixPanels.image(this) }
        }
        refreshOptions()
    }

    /** The document, for the panels. */
    internal fun doc(): PixDocument = document

    /** Repaints the canvas after a panel changed pixels it did not go through [edit] for. */
    internal fun canvasRefresh() {
        canvas.invalidateDocument()
    }

    /** Re-fits the canvas, for the image operations that change its dimensions. */
    internal fun canvasFit() {
        canvas.fit()
        canvas.invalidate()
    }

    internal fun markDirty() {
        dirty = true
    }

    /** Wraps a panel's edit so it lands in history and repaints the canvas. */
    internal fun edit(label: String, block: (PixDocument) -> Unit) {
        snapshot(label)
        block(document)
        afterDocumentChange()
    }

    internal fun paletteOf() = palette

    internal fun canvasTextSize(): Int = canvas.textSize

    internal fun currentForeground(): Int = foregroundColor

    /** Called by the activity's back handling; true when the editor consumed it. */
    fun onBack(): Boolean {
        if (canvas.cropRect != null) {
            canvas.cropRect = null
            canvas.invalidate()
            return true
        }
        if (document.selection != null) {
            document.selection = null
            canvas.invalidate()
            return true
        }
        return false
    }

    /** Completes a text placement the panel asked for. */
    fun placeTextNow(text: String, x: Double, y: Double, size: Int) {
        if (text.isBlank()) return
        edit("Text") { doc ->
            doc.placeText(text, x.toInt(), y.toInt(), size, foregroundColor)
        }
    }

    // ── Canvas ────────────────────────────────────────────────────────────

    internal enum class Gesture { PAINT, MARQUEE, ELLIPSE, LASSO, GRADIENT, LINE, FRAME, PATH, CROP }

    private inner class PixCanvas(context: Context) : View(context) {

        private var bitmap: Bitmap? = null
        private var bitmapDirty = true
        private val matrix = Matrix()
        private val inverse = Matrix()
        var textSize: Int = 48
        var radialGradient = false
        var cropRect: RectF? = null

        val preview = ArrayList<Pair<Double, Double>>()
        private var startX = 0.0
        private var startY = 0.0
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var pinchStart = 0f
        private var pinching = false
        private var scale = 1f
        private var translateX = 0f
        private var translateY = 0f

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        private val cropFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val cropStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        init {
            isClickable = true
            isFocusable = true
            setBackgroundColor(palette.surface.toInt())
        }

        fun invalidateDocument() {
            bitmapDirty = true
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (oldw == 0 && oldh == 0) fit()
        }

        fun fit() {
            if (width == 0 || height == 0) return
            val s = min(width.toDouble() / document.width, height.toDouble() / document.height).toFloat()
            scale = s.coerceAtLeast(0.05f)
            translateX = (width - document.width * scale) / 2f
            translateY = (height - document.height * scale) / 2f
            invalidate()
        }

        fun zoomBy(factor: Float) {
            val cx = width / 2f
            val cy = height / 2f
            val before = imagePoint(cx, cy)
            scale = (scale * factor).coerceIn(0.05f, 8f)
            val after = imagePoint(cx, cy)
            translateX += (after.first - before.first).toFloat() * scale
            translateY += (after.second - before.second).toFloat() * scale
            invalidate()
        }

        fun zoomTo(value: Float) {
            scale = value.coerceIn(0.05f, 8f)
            fitIfSmaller()
            invalidate()
        }

        private fun fitIfSmaller() {
            val current = imagePoint(width / 2f, height / 2f)
            translateX = width / 2f - current.first.toFloat() * scale
            translateY = height / 2f - current.second.toFloat() * scale
        }

        private fun rebuildMatrix() {
            matrix.setScale(scale, scale)
            matrix.postTranslate(translateX, translateY)
            matrix.invert(inverse)
        }

        private fun imagePoint(screenX: Float, screenY: Float): Pair<Double, Double> {
            rebuildMatrix()
            val point = FloatArray(2)
            inverse.mapPoints(point, 0, floatArrayOf(screenX, screenY), 0, 1)
            return point[0].toDouble() to point[1].toDouble()
        }

        override fun onDraw(canvas: Canvas) {
            if (bitmapDirty || bitmap == null) {
                val flat = document.flatten()
                bitmap?.recycle()
                bitmap = AndroidImageCodec.toBitmap(flat)
                bitmapDirty = false
            }
            rebuildMatrix()
            // A checkerboard behind the image so transparency is visible rather than black.
            val checker = Paint()
            checker.color = 0xFF2A2A2E.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checker)
            checker.color = 0xFF232327.toInt()
            val step = 24
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    if (((x / step) + (y / step)) % 2 == 0) canvas.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), (y + step).toFloat(), checker)
                    x += step
                }
                y += step
            }

            bitmap?.let { canvas.drawBitmap(it, matrix, bitmapPaint) }

            // Selection: the bounds, because a per-pixel ants animation on a phone would be a scan
            // of the mask at 60 Hz. The bounds are what tells the user it is armed.
            document.selection?.bounds()?.let { bounds ->
                val rect = RectF(
                    translateX + bounds[0] * scale,
                    translateY + bounds[1] * scale,
                    translateX + (bounds[0] + bounds[2]) * scale,
                    translateY + (bounds[1] + bounds[3]) * scale,
                )
                selectionPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(rect, selectionPaint)
            }

            // Live gesture preview.
            if (preview.size >= 2) {
                val path = Path()
                val first = preview.first()
                path.moveTo(translateX + first.first.toFloat() * scale, translateY + first.second.toFloat() * scale)
                preview.drop(1).forEach { point ->
                    path.lineTo(translateX + point.first.toFloat() * scale, translateY + point.second.toFloat() * scale)
                }
                previewPaint.color = when (tool) {
                    Tool.MARQUEE, Tool.ELLIPSE_SELECT, Tool.LASSO, Tool.CROP, Tool.PATH_SELECT -> accent
                    else -> foregroundColor
                }
                if (tool == Tool.CROP || tool == Tool.MARQUEE || tool == Tool.ELLIPSE_SELECT) {
                    val rect = rectOf(preview)
                    val screen = RectF(
                        translateX + rect.left * scale, translateY + rect.top * scale,
                        translateX + rect.right * scale, translateY + rect.bottom * scale,
                    )
                    if (tool == Tool.CROP) canvas.drawRect(screen, cropFill)
                    canvas.drawRect(screen, previewPaint)
                } else {
                    canvas.drawPath(path, previewPaint)
                }
            }

            cropRect?.let { rect ->
                val screen = RectF(
                    translateX + rect.left * scale, translateY + rect.top * scale,
                    translateX + rect.right * scale, translateY + rect.bottom * scale,
                )
                val outside = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                    addRect(screen, Path.Direction.CW)
                }
                canvas.drawPath(outside, cropFill)
                cropStroke.color = accent
                canvas.drawRect(screen, cropStroke)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    lastX = event.x; lastY = event.y
                    val (ix, iy) = imagePoint(event.x, event.y)
                    startX = ix; startY = iy
                    preview.clear()
                    preview.add(ix to iy)
                    pinching = false
                    // Snapshotted BEFORE the drag: an undo step recorded afterwards would restore
                    // the moved document, which is a button that does nothing.
                    if (tool == Tool.MOVE) document.history.push("Move layer", document)
                    if (tool == Tool.HAND) {
                        // Hand pans; nothing else starts a preview.
                        preview.clear()
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        pinching = true
                        preview.clear()
                        pinchStart = distanceBetween(event)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && event.pointerCount >= 2) {
                        val current = distanceBetween(event)
                        if (pinchStart > 0) {
                            val factor = current / pinchStart
                            zoomBy(factor)
                            pinchStart = current
                        }
                        return true
                    }
                    val (ix, iy) = imagePoint(event.x, event.y)
                    when (tool) {
                        Tool.HAND -> {
                            translateX += event.x - lastX
                            translateY += event.y - lastY
                        }
                        Tool.MOVE -> {
                            val layer = document.activeLayer
                            layer.offsetX += (ix - startX).toInt()
                            layer.offsetY += (iy - startY).toInt()
                            startX = ix; startY = iy
                            invalidateDocument()
                        }
                        Tool.CROP -> {
                            preview.clear()
                            preview.add(startX to startY)
                            preview.add(ix to iy)
                        }
                        else -> preview.add(ix to iy)
                    }
                    lastX = event.x; lastY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val (ix, iy) = imagePoint(event.x, event.y)
                    val moved = abs(event.x - downX) > size(6) || abs(event.y - downY) > size(6)
                    when {
                        tool == Tool.HAND -> Unit
                        tool == Tool.MOVE -> {
                            dirty = true
                            refreshHistoryLabel()
                        }
                        tool == Tool.CROP -> {
                            val rect = rectOf(preview)
                            if (rect.width() > 8 && rect.height() > 8) cropRect = rect
                            preview.clear()
                        }
                        !moved -> tapped(ix, iy)
                        tool == Tool.MARQUEE || tool == Tool.ELLIPSE_SELECT -> {
                            val rect = rectOf(preview)
                            commitStroke(
                                listOf(rect.left.toDouble() to rect.top.toDouble(), rect.right.toDouble() to rect.bottom.toDouble()),
                                if (tool == Tool.MARQUEE) Gesture.MARQUEE else Gesture.ELLIPSE,
                            )
                            preview.clear()
                        }
                        tool == Tool.LASSO -> {
                            commitStroke(preview.toList(), Gesture.LASSO)
                            preview.clear()
                        }
                        tool == Tool.PEN || tool == Tool.PATH_SELECT -> {
                            commitStroke(preview.toList(), Gesture.PATH)
                            preview.clear()
                        }
                        tool == Tool.GRADIENT -> {
                            commitStroke(listOf(preview.first(), preview.last()), Gesture.GRADIENT)
                            preview.clear()
                        }
                        tool == Tool.LINE -> {
                            commitStroke(listOf(preview.first(), preview.last()), Gesture.LINE)
                            preview.clear()
                        }
                        tool == Tool.FRAME -> {
                            commitStroke(listOf(preview.first(), preview.last()), Gesture.FRAME)
                            preview.clear()
                        }
                        tool == Tool.ZOOM -> zoomBy(2f)
                        else -> {
                            commitStroke(preview.toList(), Gesture.PAINT)
                            preview.clear()
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    preview.clear()
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun distanceBetween(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.hypot(dx, dy)
        }
    }

    private fun size(dp: Int): Int = IosUi.dp(context, dp.toFloat())
}
