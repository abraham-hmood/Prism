package com.prism.launcher.theme

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.nora.IosUi
import kotlin.math.abs

/**
 * The visual half of the drag-and-drop editor.
 *
 * A layout tree is flow-based, not a free canvas, so dragging an element means moving it to a new
 * parent and position rather than storing an arbitrary X/Y. The canvas draws the same primitives
 * the runtime renders, with a selected element outlined and a live ghost under the finger. The
 * delete target is supplied by the editor, which is why a drop over it can remove the element and
 * its whole subtree.
 */
class ThemeDragCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var mode: ThemeModeSpec = PrismThemes.modeFor(IosUi.isDark(context))
        set(value) { field = value; invalidate() }

    var root: ThemeNode? = null
        private set

    var selected: ThemeNode? = null
        private set

    var onSelectionChanged: ((ThemeNode?) -> Unit)? = null
    var onTreeChanged: (() -> Unit)? = null
    var onDragStateChanged: ((ThemeNode, Float, Float, Boolean) -> Boolean)? = null

    private val boxes = mutableListOf<NodeBox>()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dragNode: ThemeNode? = null
    private var dragParent: ThemeNode? = null
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var dropTarget: ThemeNode? = null

    private class NodeBox(
        val node: ThemeNode,
        val parent: ThemeNode?,
        val rect: RectF,
    )

    fun setTree(node: ThemeNode) {
        root = node
        selected = null
        boxes.clear()
        requestLayout()
        invalidate()
    }

    fun xml(): String = root?.let { ThemeXml.toXml(it) } ?: ""

    /** Adds [tag] under the current selection if it can hold children, otherwise at the root. */
    fun add(tag: String, rawX: Float? = null, rawY: Float? = null): ThemeNode? {
        val rootNode = root ?: return null
        val child = newNode(tag)
        val at = if (rawX != null && rawY != null) nodeAt(rawX, rawY) else selected
        val container = when {
            at == null -> rootNode
            canContain(at) -> at
            else -> parentOf(at) ?: rootNode
        }
        container.children.add(child)
        selected = child
        boxes.clear()
        onSelectionChanged?.invoke(child)
        onTreeChanged?.invoke()
        requestLayout()
        invalidate()
        return child
    }

    fun removeNode(node: ThemeNode): Boolean {
        if (node === root) return false
        val parent = parentOf(node) ?: return false
        parent.children.remove(node)
        if (selected === node) selected = null
        boxes.clear()
        onTreeChanged?.invoke()
        requestLayout()
        invalidate()
        return true
    }

    fun deleteSelected(): Boolean {
        val node = selected ?: return false
        if (node === root) return false
        val parent = parentOf(node) ?: return false
        parent.children.remove(node)
        selected = null
        boxes.clear()
        onSelectionChanged?.invoke(null)
        onTreeChanged?.invoke()
        requestLayout()
        invalidate()
        return true
    }

    fun replaceSelected(tag: String): Boolean {
        val node = selected ?: return false
        val parent = parentOf(node) ?: return false
        val index = parent.children.indexOf(node)
        if (index < 0) return false
        val replacement = newNode(tag)
        parent.children[index] = replacement
        selected = replacement
        boxes.clear()
        onSelectionChanged?.invoke(replacement)
        onTreeChanged?.invoke()
        requestLayout()
        invalidate()
        return true
    }

    fun selectionParent(): ThemeNode? = selected?.let { parentOf(it) }

    private fun newNode(tag: String): ThemeNode = when (tag) {
        "text" -> ThemeNode("text", linkedMapOf("text" to "Text", "size" to "13", "color" to "@text"))
        "title" -> ThemeNode("title", linkedMapOf("text" to "Title", "size" to "22", "color" to "@text"))
        "label" -> ThemeNode("label", linkedMapOf("text" to "LABEL", "size" to "11", "color" to "@muted"))
        "button" -> ThemeNode("button", linkedMapOf("text" to "Button"))
        "field" -> ThemeNode("field", linkedMapOf("hint" to "Search"))
        "card" -> ThemeNode("card", linkedMapOf("background" to "@card", "radius" to "@cardRadius"))
        "row" -> ThemeNode("row", linkedMapOf("gap" to "@gap"))
        "column" -> ThemeNode("column", linkedMapOf("gap" to "@gap"))
        "grid" -> ThemeNode("grid", linkedMapOf("columns" to "2", "gap" to "@gap"))
        "app" -> ThemeNode("app", linkedMapOf("label" to "App", "color" to "@accent"))
        "icon" -> ThemeNode("icon", linkedMapOf("name" to "palette", "color" to "@accent"))
        "avatar" -> ThemeNode("avatar", linkedMapOf("text" to "A", "color" to "@accent"))
        "divider" -> ThemeNode("divider", linkedMapOf("color" to "@divider"))
        "spacer" -> ThemeNode("spacer", linkedMapOf("size" to "10"))
        "toggle" -> ThemeNode("toggle", linkedMapOf("checked" to "true"))
        "slider" -> ThemeNode("slider", linkedMapOf("value" to "50"))
        "progress" -> ThemeNode("progress", linkedMapOf("value" to "50"))
        "chip" -> ThemeNode("chip", linkedMapOf("text" to "Chip"))
        else -> ThemeNode(tag)
    }

    private fun canContain(node: ThemeNode): Boolean =
        node.tag in setOf("layout", "column", "row", "card", "grid", "stack", "canvas", "scroll", "list", "recycler")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = maxOf(MeasureSpec.getSize(heightMeasureSpec), IosUi.dp(context, 420f))
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tree = root ?: return
        val measured = layout(tree, 0f, 0f, width.toFloat())
        if (measured <= 0f) return
        boxes.clear()
        place(tree, 0f, 0f, width.toFloat())

        background.color = mode.palette.background.toInt()
        canvas.drawColor(mode.palette.background.toInt())
        boxes.forEach { box ->
            drawBox(canvas, box)
        }
        selected?.let { node ->
            boxes.firstOrNull { it.node === node }?.let { box ->
                stroke.color = mode.palette.accent.toInt()
                stroke.strokeWidth = dp(2f)
                canvas.drawRoundRect(box.rect, dp(6f), dp(6f), stroke)
            }
        }
        dropTarget?.let { node ->
            boxes.firstOrNull { it.node === node }?.let { box ->
                stroke.color = mode.palette.accentAlt.toInt()
                stroke.strokeWidth = dp(3f)
                canvas.drawRoundRect(box.rect, dp(7f), dp(7f), stroke)
            }
        }
        dragNode?.let { node ->
            stroke.color = mode.palette.accent.toInt()
            stroke.strokeWidth = dp(2f)
            val ghost = RectF(
                dragX - dp(38f),
                dragY - dp(18f),
                dragX + dp(38f),
                dragY + dp(18f),
            )
            background.color = (mode.palette.accent.toInt() and 0x00FFFFFF) or 0x55000000
            canvas.drawRoundRect(ghost, dp(10f), dp(10f), background)
            text.color = mode.palette.onAccent.toInt()
            text.textSize = sp(11f)
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(node.tag, ghost.centerX(), ghost.centerY() + sp(4f), text)
        }
    }

    private fun layout(node: ThemeNode, x: Float, y: Float, width: Float): Float {
        val pad = padding(node)
        val innerWidth = (width - pad * 2).coerceAtLeast(dp(20f))
        val tag = node.tag.lowercase()
        val gap = gap(node)
        val children = node.children
        return when {
            children.isEmpty() -> leafHeight(node) + pad * 2
            tag == "row" || tag == "toolbar" || tag == "tabbar" -> {
                var tallest = 0f
                children.forEach { child ->
                    val childWidth = if (child.attr("weight")?.isNotBlank() == true) {
                        (innerWidth - gap * (children.size - 1)) / children.size
                    } else (innerWidth - gap * (children.size - 1)) / children.size
                    tallest = maxOf(tallest, layout(child, x + pad, y + pad, childWidth))
                }
                tallest + pad * 2
            }
            tag == "grid" -> {
                val columns = (node.attr("columns", "2").toIntOrNull() ?: 2).coerceIn(1, 6)
                val rows = children.chunked(columns)
                var total = 0f
                rows.forEach { row ->
                    val cellWidth = (innerWidth - gap * (columns - 1)) / columns
                    val rowHeight = row.maxOfOrNull { layout(it, x + pad, y + pad + total, cellWidth) } ?: 0f
                    total += rowHeight + gap
                }
                if (rows.isNotEmpty()) total - gap else 0f
                total + pad * 2
            }
            else -> {
                var total = 0f
                children.forEachIndexed { index, child ->
                    total += layout(child, x + pad, y + pad + total, innerWidth)
                    if (index < children.size - 1) total += gap
                }
                total + pad * 2
            }
        }
    }

    private fun place(node: ThemeNode, x: Float, y: Float, width: Float): Float {
        val height = layout(node, x, y, width)
        boxes.add(NodeBox(node, parentOf(node), RectF(x, y, x + width, y + height)))
        val pad = padding(node)
        val innerWidth = (width - pad * 2).coerceAtLeast(dp(20f))
        val tag = node.tag.lowercase()
        val gap = gap(node)
        val children = node.children
        if (children.isEmpty()) return height
        when {
            tag == "row" || tag == "toolbar" || tag == "tabbar" -> {
                val childWidth = (innerWidth - gap * (children.size - 1)) / children.size
                children.forEachIndexed { index, child ->
                    place(child, x + pad + index * (childWidth + gap), y + pad, childWidth)
                }
            }
            tag == "grid" -> {
                val columns = (node.attr("columns", "2").toIntOrNull() ?: 2).coerceIn(1, 6)
                val cellWidth = (innerWidth - gap * (columns - 1)) / columns
                var top = y + pad
                children.chunked(columns).forEach { row ->
                    val rowHeight = row.maxOfOrNull { layout(it, x + pad, top, cellWidth) } ?: 0f
                    row.forEachIndexed { index, child ->
                        place(child, x + pad + index * (cellWidth + gap), top, cellWidth)
                    }
                    top += rowHeight + gap
                }
            }
            else -> {
                var top = y + pad
                children.forEachIndexed { index, child ->
                    val childHeight = place(child, x + pad, top, innerWidth)
                    top += childHeight
                    if (index < children.size - 1) top += gap
                }
            }
        }
        return height
    }

    private fun drawBox(canvas: Canvas, box: NodeBox) {
        val node = box.node
        val rect = box.rect
        val tag = node.tag.lowercase()
        val radius = dp(mode.number(node.attr("radius"), mode.shape.card).toFloat())
        val bg = node.attr("background")
        when (tag) {
            "layout" -> {
                background.color = mode.color(node.attr("background", "@background"), mode.palette.background).toInt()
                canvas.drawRect(rect, background)
            }
            "card" -> {
                background.color = mode.color(bg ?: "@card", mode.palette.card).toInt()
                canvas.drawRoundRect(rect, radius, radius, background)
                if (mode.number(node.attr("border"), mode.shape.border) > 0) {
                    stroke.color = mode.palette.divider.toInt()
                    stroke.strokeWidth = dp(mode.shape.border.toFloat())
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }
            }
            "button" -> {
                background.color = mode.color(bg ?: "@accent", mode.palette.accent).toInt()
                canvas.drawRoundRect(rect, dp(mode.shape.button.toFloat()), dp(mode.shape.button.toFloat()), background)
                drawText(canvas, node.attr("text", "Button"), rect, mode.color("@onAccent", mode.palette.onAccent), 14f, true)
            }
            "field" -> {
                background.color = mode.color(bg ?: "@raised", mode.palette.raised).toInt()
                canvas.drawRoundRect(rect, dp(mode.shape.field.toFloat()), dp(mode.shape.field.toFloat()), background)
                drawText(canvas, node.attr("hint", "Field"), rect, mode.palette.faint, 12f, false)
            }
            "divider" -> {
                background.color = mode.color(node.attr("color", "@divider"), mode.palette.divider).toInt()
                canvas.drawRect(rect.left, rect.centerY(), rect.right, rect.centerY() + dp(1f), background)
            }
            "text", "title", "label" -> {
                val size = mode.size(node.attr("size"), if (tag == "title") 22f else 13f)
                drawText(canvas, node.attr("text", ""), rect, mode.color(node.attr("color", "@text"), mode.palette.text), size, tag == "title")
            }
            "app" -> {
                background.color = mode.color(node.attr("color", "@accent"), mode.palette.accent).toInt()
                val side = minOf(rect.width(), dp(40f))
                val icon = RectF(rect.centerX() - side / 2f, rect.top + dp(4f), rect.centerX() + side / 2f, rect.top + dp(4f) + side)
                canvas.drawRoundRect(icon, dp(9f), dp(9f), background)
                drawTextAt(canvas, "•", icon.centerX(), icon.centerY() + dp(5f), mode.palette.onAccent, 16f, Paint.Align.CENTER)
                drawTextAt(canvas, node.attr("label", ""), rect.centerX(), rect.bottom - dp(3f), mode.palette.muted, 8f, Paint.Align.CENTER)
            }
            "avatar" -> {
                background.color = mode.color(node.attr("color", "@accent"), mode.palette.accent).toInt()
                val side = minOf(rect.width(), rect.height(), dp(28f))
                canvas.drawCircle(rect.left + side / 2f, rect.centerY(), side / 2f, background)
                drawTextAt(canvas, node.attr("text", "A").take(1), rect.left + side / 2f, rect.centerY() + dp(4f), mode.palette.onAccent, 12f, Paint.Align.CENTER)
            }
            "icon" -> {
                drawText(canvas, "•", rect, mode.color(node.attr("color", "@accent"), mode.palette.accent), 20f, false)
            }
            "toggle" -> {
                background.color = mode.palette.accent.toInt()
                val w = dp(38f)
                val h = dp(20f)
                val pill = RectF(rect.left, rect.centerY() - h / 2f, rect.left + w, rect.centerY() + h / 2f)
                canvas.drawRoundRect(pill, h / 2f, h / 2f, background)
                background.color = mode.palette.onAccent.toInt()
                canvas.drawCircle(pill.right - h / 2f, pill.centerY(), h * 0.36f, background)
            }
            "slider", "progress" -> {
                background.color = mode.palette.raised.toInt()
                val bar = RectF(rect.left, rect.centerY() - dp(3f), rect.right, rect.centerY() + dp(3f))
                canvas.drawRoundRect(bar, dp(3f), dp(3f), background)
                background.color = mode.palette.accent.toInt()
                bar.right = bar.left + bar.width() * 0.55f
                canvas.drawRoundRect(bar, dp(3f), dp(3f), background)
            }
            "chip" -> {
                background.color = (mode.palette.accent.toInt() and 0x00FFFFFF) or 0x22000000
                canvas.drawRoundRect(rect, dp(14f), dp(14f), background)
                drawText(canvas, node.attr("text", "Chip"), rect, mode.palette.accent, 11f, false)
            }
            "image", "webview" -> {
                background.color = mode.palette.raised.toInt()
                canvas.drawRoundRect(rect, radius, radius, background)
                drawText(canvas, if (tag == "webview") "page" else "image", rect, mode.palette.faint, 10f, false)
            }
            "column", "row", "grid", "stack", "canvas", "scroll", "list", "recycler" -> {
                if (bg != null) {
                    background.color = mode.color(bg, mode.palette.raised).toInt()
                    canvas.drawRoundRect(rect, radius, radius, background)
                }
            }
            else -> {
                background.color = mode.palette.raised.toInt()
                canvas.drawRoundRect(rect, dp(6f), dp(6f), background)
                drawText(canvas, tag, rect, mode.palette.faint, 10f, false)
            }
        }
        // A small type label, so a container is still identifiable when it has children.
        if (canContain(node) && tag !in setOf("layout", "card")) {
            drawTextAt(canvas, tag, rect.left + dp(4f), rect.top + dp(9f), mode.palette.faint, 7f, Paint.Align.LEFT)
        }
    }

    private fun drawText(canvas: Canvas, value: String, rect: RectF, color: Long, size: Float, bold: Boolean) {
        text.color = color.toInt()
        text.textSize = sp(size)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        text.textAlign = Paint.Align.LEFT
        val max = rect.width() - dp(8f)
        val fitted = fit(value, max)
        canvas.drawText(fitted, rect.left + dp(4f), rect.top + sp(size) + dp(5f), text)
    }

    private fun drawTextAt(canvas: Canvas, value: String, x: Float, y: Float, color: Long, size: Float, align: Paint.Align) {
        text.color = color.toInt()
        text.textSize = sp(size)
        text.textAlign = align
        canvas.drawText(value, x, y, text)
    }

    private fun fit(value: String, max: Float): String {
        if (text.measureText(value) <= max) return value
        var end = value.length
        while (end > 1 && text.measureText(value.substring(0, end) + "…") > max) end--
        return value.substring(0, end) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val node = nodeAt(event.x, event.y)
                downX = event.x
                downY = event.y
                dragX = event.x
                dragY = event.y
                dragging = false
                dragNode = node
                dragParent = node?.let { parentOf(it) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = event.x
                dragY = event.y
                if (!dragging && dragNode != null &&
                    (abs(event.x - downX) > dp(6f) || abs(event.y - downY) > dp(6f))
                ) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    dropTarget = nodeAt(event.x, event.y)?.takeIf { it !== dragNode && !isDescendant(dragNode!!, it) }
                    dragNode?.let { onDragStateChanged?.invoke(it, event.rawX, event.rawY, true) }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val node = dragNode
                if (dragging && node != null) {
                    val consume = onDragStateChanged?.invoke(node, event.rawX, event.rawY, false) ?: false
                    if (consume) {
                        // The editor placed the node in its delete target.
                    } else {
                        val target = nodeAt(event.x, event.y)
                            ?.takeIf { it !== node && !isDescendant(node, it) }
                        if (target != null) {
                            val parent = parentOf(node)
                            if (parent != null) {
                                parent.children.remove(node)
                                if (canContain(target)) target.children.add(node)
                                else {
                                    val targetParent = parentOf(target)
                                    val index = targetParent?.children?.indexOf(target) ?: -1
                                    if (targetParent != null && index >= 0) targetParent.children.add(index, node)
                                    else parent.children.add(node)
                                }
                                onTreeChanged?.invoke()
                            }
                        }
                    }
                    selected = if (consume) null else node
                    onSelectionChanged?.invoke(selected)
                    dragNode = null
                    dragParent = null
                    dropTarget = null
                    dragging = false
                    boxes.clear()
                    requestLayout()
                    invalidate()
                    return true
                }
                val tapped = nodeAt(event.x, event.y)
                selected = tapped
                onSelectionChanged?.invoke(tapped)
                dragNode = null
                dragging = false
                dropTarget = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return event.clipDescription != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                dropTarget = nodeAt(event.x, event.y)
                invalidate()
                return true
            }
            DragEvent.ACTION_DROP -> {
                val tag = event.clipData?.getItemAt(0)?.text?.toString() ?: return false
                add(tag, event.x, event.y)
                dropTarget = null
                invalidate()
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dropTarget = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun nodeAt(x: Float, y: Float): ThemeNode? =
        boxes.lastOrNull { it.rect.contains(x, y) }?.node

    private fun parentOf(node: ThemeNode): ThemeNode? {
        val tree = root ?: return null
        if (node === tree) return null
        fun search(parent: ThemeNode): ThemeNode? {
            parent.children.forEach { child ->
                if (child === node) return parent
                search(child)?.let { return it }
            }
            return null
        }
        return search(tree)
    }

    private fun isDescendant(ancestor: ThemeNode, node: ThemeNode): Boolean =
        ancestor.children.any { child -> child === node || isDescendant(child, node) }

    private fun padding(node: ThemeNode): Float {
        val raw = node.attr("padding") ?: return 0f
        val value = when {
            raw.startsWith("@") -> when (raw.removePrefix("@")) {
                "page" -> mode.density.page
                "gap" -> mode.density.gap
                "row" -> mode.density.row
                else -> 0
            }
            else -> raw.toFloatOrNull()?.toInt() ?: 0
        }
        return dp(value.toFloat())
    }

    private fun gap(node: ThemeNode): Float {
        val raw = node.attr("gap") ?: return 0f
        val value = when {
            raw.startsWith("@") -> when (raw.removePrefix("@")) {
                "page" -> mode.density.page
                "gap" -> mode.density.gap
                "row" -> mode.density.row
                else -> 0
            }
            else -> raw.toFloatOrNull()?.toInt() ?: 0
        }
        return dp(value.toFloat())
    }

    private fun leafHeight(node: ThemeNode): Float = when (node.tag) {
        "text", "title", "label" -> sp(mode.size(node.attr("size"), if (node.tag == "title") 22f else 13f)) + dp(12f)
        "button" -> sp(14f) + dp(24f)
        "field" -> sp(13f) + dp(24f)
        "divider" -> dp(2f)
        "spacer" -> dp(node.attr("size", "8").toFloatOrNull() ?: 8f)
        "avatar", "icon" -> dp(30f)
        "app" -> dp(54f)
        "toggle" -> dp(28f)
        "slider", "progress" -> dp(26f)
        "image", "webview" -> dp(node.attr("height", "80").toFloatOrNull() ?: 80f)
        "chip" -> dp(26f)
        else -> dp(34f)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value * mode.typography.scale,
        resources.displayMetrics,
    )
}
