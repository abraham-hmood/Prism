package com.prism.launcher.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.prism.launcher.nora.IosUi

/**
 * Turns a theme's layout XML into real Android views.
 *
 * The XML is deliberately not an Android resource file. It names Primitives (`column`, `card`,
 * `text`, ...), and this renderer is the only place that knows what those mean here. Keeping the
 * mapping in one object is what lets a user edit a layout, see it in the creator, and have the
 * same file mean the same thing on a desktop.
 */
object AndroidThemeRenderer {

    fun render(context: Context, xml: String, mode: ThemeModeSpec): View {
        val root = ThemeXml.parse(xml).getOrElse { error ->
            return errorView(context, mode, error.message ?: "The layout could not be read.")
        }
        val view = build(context, root, mode)
        (view as? ViewGroup)?.let { group ->
            group.clipToPadding = false
            group.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return view
    }

    fun renderNode(context: Context, node: ThemeNode, mode: ThemeModeSpec): View =
        build(context, node, mode)

    private fun errorView(context: Context, mode: ThemeModeSpec, message: String): View {
        return TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(mode.palette.destructive.toInt())
            setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
            background = GradientDrawable().apply {
                setColor(mode.palette.raised.toInt())
                cornerRadius = dp(context, mode.shape.card.toFloat()).toFloat()
            }
        }
    }

    private fun build(context: Context, node: ThemeNode, mode: ThemeModeSpec): View {
        val tag = node.tag.lowercase()
        return when (tag) {
            "layout" -> buildContainer(context, node, mode, vertical = true)
            "column" -> buildContainer(context, node, mode, vertical = true)
            "row", "toolbar", "tabbar", "navrail" -> buildContainer(context, node, mode, vertical = false)
            "scroll" -> ScrollView(context).apply {
                isFillViewport = false
                clipToPadding = false
                node.children.firstOrNull()?.let { addView(build(context, it, mode)) }
                applyPadding(this, node, context)
            }
            "grid" -> buildGrid(context, node, mode)
            "stack", "canvas" -> FrameLayout(context).apply {
                applyPadding(this, node, context)
                node.children.forEach { addView(build(context, it, mode)) }
            }
            "card" -> buildContainer(context, node, mode, vertical = true)
            "list", "recycler" -> buildContainer(context, node, mode, vertical = true)
            "text", "title", "label" -> buildText(context, node, mode)
            "button" -> buildButton(context, node, mode)
            "field" -> buildField(context, node, mode)
            "divider" -> View(context).apply {
                val height = dp(context, node.attr("height", "1").toFloatOrNull() ?: 1f)
                layoutParams = LinearLayout.LayoutParams(
                    if (node.attr("orientation") == "vertical") dp(context, 1f) else ViewGroup.LayoutParams.MATCH_PARENT,
                    if (node.attr("orientation") == "vertical") ViewGroup.LayoutParams.MATCH_PARENT else maxOf(1, height),
                )
                setBackgroundColor(mode.color(node.attr("color", "@divider"), mode.palette.divider).toInt())
            }
            "spacer" -> View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (node.attr("weight", "0") != "0") 0 else dp(context, node.attr("size", "8").toFloatOrNull() ?: 8f),
                )
                if (node.attr("weight", "0").toFloatOrNull()?.let { it > 0f } == true) {
                    (layoutParams as LinearLayout.LayoutParams).weight =
                        node.attr("weight", "1").toFloatOrNull() ?: 1f
                }
            }
            "image", "webview" -> buildImage(context, node, mode, tag == "webview")
            "icon" -> buildIcon(context, node, mode)
            "app" -> buildApp(context, node, mode)
            "avatar" -> buildAvatar(context, node, mode)
            "toggle" -> Switch(context).apply {
                isChecked = node.attr("checked", "false").toBoolean()
                isClickable = false
                setPadding(0, 0, 0, 0)
            }
            "slider" -> SeekBar(context).apply {
                max = 100
                progress = node.attr("value", "50").toIntOrNull() ?: 50
                progressTintList = android.content.res.ColorStateList.valueOf(
                    mode.color(node.attr("color", "@accent")).toInt()
                )
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    mode.color(node.attr("color", "@accent")).toInt()
                )
            }
            "progress" -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = node.attr("value", "50").toIntOrNull() ?: 50
                progressTintList = android.content.res.ColorStateList.valueOf(
                    mode.color(node.attr("color", "@accent")).toInt()
                )
            }
            "listItem" -> buildContainer(context, node, mode, vertical = false)
            "fab" -> buildFab(context, node, mode)
            "chip" -> buildChip(context, node, mode)
            "include", "view", "custom" -> buildPlaceholder(context, node, mode)
            else -> buildPlaceholder(context, node, mode)
        }
    }

    private fun buildContainer(
        context: Context,
        node: ThemeNode,
        mode: ThemeModeSpec,
        vertical: Boolean,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = when (node.attr("gravity", "start")) {
            "center" -> Gravity.CENTER
            "end" -> Gravity.END
            else -> Gravity.START
        }
        clipToPadding = false
        applyPadding(this, node, context)
        val backgroundName = node.attr("background")
        val radius = mode.number(node.attr("radius"), mode.shape.card)
        if (backgroundName != null || node.tag == "card" || node.tag == "layout") {
            background = GradientDrawable().apply {
                setColor(mode.color(backgroundName ?: "@card", mode.palette.card).toInt())
                cornerRadius = dp(context, radius.toFloat()).toFloat()
                if (mode.number(node.attr("border"), mode.shape.border) > 0) {
                    setStroke(
                        dp(context, mode.number(node.attr("border"), mode.shape.border).toFloat()),
                        mode.color(node.attr("borderColor", "@divider"), mode.palette.divider).toInt(),
                    )
                }
            }
        }
        val gap = mode.number(node.attr("gap"), mode.density.gap)
        node.children.forEachIndexed { index, child ->
            val view = build(context, child, mode)
            val weight = child.attr("weight", "0").toFloatOrNull() ?: 0f
            val params = LinearLayout.LayoutParams(
                if (!vertical) {
                    if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                } else ViewGroup.LayoutParams.MATCH_PARENT,
                if (vertical) {
                    if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                } else ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (weight > 0f) params.weight = weight
            if (index > 0) {
                if (vertical) params.topMargin = dp(context, gap.toFloat())
                else params.leftMargin = dp(context, gap.toFloat())
            }
            addView(view, params)
        }
    }

    private fun buildGrid(context: Context, node: ThemeNode, mode: ThemeModeSpec): LinearLayout {
        val columns = (node.attr("columns", "2").toIntOrNull() ?: 2).coerceIn(1, 8)
        val gap = mode.number(node.attr("gap"), mode.density.gap)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            applyPadding(this, node, context)
        }
        node.children.chunked(columns).forEachIndexed { rowIndex, rowChildren ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) setPadding(0, dp(context, gap.toFloat()), 0, 0)
            }
            rowChildren.forEachIndexed { index, child ->
                val view = build(context, child, mode)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) params.leftMargin = dp(context, gap.toFloat())
                row.addView(view, params)
            }
            column.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        return column
    }

    private fun buildText(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = node.attr("text", "")
            val isTitle = node.tag == "title"
            textSize = mode.size(node.attr("size"), if (isTitle) 22f else 13f)
            setTextColor(mode.color(node.attr("color", "@text"), mode.palette.text).toInt())
            val weight = node.attr("weight", "normal")
            typeface = if (weight == "bold" || weight == "semibold" || isTitle) {
                Typeface.create(family(mode.typography.family), Typeface.BOLD)
            } else {
                Typeface.create(family(mode.typography.family), Typeface.NORMAL)
            }
            letterSpacing = mode.typography.letterSpacing
            if (mode.typography.uppercaseHeaders && node.tag == "label") text = text.toString().uppercase()
            applyPadding(this, node, context)
        }

    private fun buildButton(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = node.attr("text", "Button")
            textSize = mode.size(node.attr("size"), 14f)
            gravity = Gravity.CENTER
            setTextColor(mode.color(node.attr("color", "@onAccent"), mode.palette.onAccent).toInt())
            val radius = mode.number(node.attr("radius"), mode.shape.button)
            background = GradientDrawable().apply {
                setColor(mode.color(node.attr("background", "@accent"), mode.palette.accent).toInt())
                cornerRadius = dp(context, radius.toFloat()).toFloat()
            }
            setPadding(
                dp(context, 14f),
                dp(context, 10f),
                dp(context, 14f),
                dp(context, 10f),
            )
        }

    private fun buildField(context: Context, node: ThemeNode, mode: ThemeModeSpec): EditText =
        EditText(context).apply {
            hint = node.attr("hint", node.attr("text", ""))
            textSize = mode.size(node.attr("size"), 13f)
            setTextColor(mode.color(node.attr("color", "@text"), mode.palette.text).toInt())
            setHintTextColor(mode.color("@faint", mode.palette.faint).toInt())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            val radius = mode.number(node.attr("radius"), mode.shape.field)
            background = GradientDrawable().apply {
                setColor(mode.color(node.attr("background", "@raised"), mode.palette.raised).toInt())
                cornerRadius = dp(context, radius.toFloat()).toFloat()
            }
            setPadding(dp(context, 12f), dp(context, 9f), dp(context, 12f), dp(context, 9f))
        }

    private fun buildImage(context: Context, node: ThemeNode, mode: ThemeModeSpec, web: Boolean): View {
        return FrameLayout(context).apply {
            val radius = mode.number(node.attr("radius"), mode.shape.card)
            background = GradientDrawable().apply {
                setColor(mode.color(node.attr("background", "@raised"), mode.palette.raised).toInt())
                cornerRadius = dp(context, radius.toFloat()).toFloat()
                if (mode.shape.border > 0) {
                    setStroke(dp(context, mode.shape.border.toFloat()), mode.palette.divider.toInt())
                }
            }
            val height = dp(context, node.attr("height", if (web) "120" else "80").toFloatOrNull() ?: 80f)
            addView(TextView(context).apply {
                text = if (web) "page" else "image"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(mode.palette.faint.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            ).apply { gravity = Gravity.CENTER })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            )
        }
    }

    private fun buildIcon(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = glyph(node.attr("name", "dot"))
            textSize = mode.size(node.attr("size"), 18f)
            gravity = Gravity.CENTER
            setTextColor(mode.color(node.attr("color", "@accent"), mode.palette.accent).toInt())
            val size = mode.size(node.attr("size"), 20f).toInt()
            layoutParams = LinearLayout.LayoutParams(dp(context, size.toFloat()), dp(context, size.toFloat()))
        }

    private fun buildApp(context: Context, node: ThemeNode, mode: ThemeModeSpec): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val icon = TextView(context).apply {
                text = glyph(node.attr("label", "app"))
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(mode.palette.onAccent.toInt())
                background = GradientDrawable().apply {
                    setColor(mode.color(node.attr("color", "@accent"), mode.palette.accent).toInt())
                    cornerRadius = dp(context, if (mode.shape.card <= 2) 3f else 10f).toFloat()
                }
            }
            addView(icon, LinearLayout.LayoutParams(dp(context, 36f), dp(context, 36f)))
            val label = node.attr("label", "")
            if (label.isNotBlank()) {
                addView(TextView(context).apply {
                    text = label
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(mode.palette.muted.toInt())
                    setPadding(0, dp(context, 4f), 0, 0)
                })
            }
        }

    private fun buildAvatar(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = node.attr("text", "A").take(1)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(mode.palette.onAccent.toInt())
            background = GradientDrawable().apply {
                setColor(mode.color(node.attr("color", "@accent"), mode.palette.accent).toInt())
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 28f), dp(context, 28f))
        }

    private fun buildFab(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = glyph(node.attr("icon", "add"))
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(mode.palette.onAccent.toInt())
            background = GradientDrawable().apply {
                setColor(mode.color(node.attr("background", "@accent"), mode.palette.accent).toInt())
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 46f), dp(context, 46f))
        }

    private fun buildChip(context: Context, node: ThemeNode, mode: ThemeModeSpec): TextView =
        TextView(context).apply {
            text = node.attr("text", "Chip")
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(mode.palette.accent.toInt())
            background = GradientDrawable().apply {
                setColor((mode.palette.accent.toInt() and 0x00FFFFFF) or 0x22000000)
                cornerRadius = dp(context, 14f).toFloat()
            }
            setPadding(dp(context, 10f), dp(context, 5f), dp(context, 10f), dp(context, 5f))
        }

    private fun buildPlaceholder(context: Context, node: ThemeNode, mode: ThemeModeSpec): View =
        TextView(context).apply {
            text = node.tag
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(mode.palette.faint.toInt())
            background = GradientDrawable().apply {
                setColor(mode.palette.raised.toInt())
                cornerRadius = dp(context, 6f).toFloat()
            }
            setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
        }

    private fun applyPadding(view: View, node: ThemeNode, context: Context) {
        val padding = node.attr("padding")?.let { modeNumber(node, it, 0) }
        val prefix = padding ?: 0
        val left = modeNumber(node, node.attr("paddingStart", prefix.toString()), prefix)
        val top = node.attr("paddingTop")?.let { modeNumber(node, it, prefix) } ?: prefix
        val right = modeNumber(node, node.attr("paddingEnd", prefix.toString()), prefix)
        val bottom = node.attr("paddingBottom")?.let { modeNumber(node, it, prefix) } ?: prefix
        view.setPadding(
            dp(context, left.toFloat()),
            dp(context, top.toFloat()),
            dp(context, right.toFloat()),
            dp(context, bottom.toFloat()),
        )
    }

    /** Numeric attributes are in dp; symbolic ones go through @page/@gap/@row. */
    private fun modeNumber(node: ThemeNode, value: String, fallback: Int): Int {
        if (value.startsWith("@")) {
            return when (value.removePrefix("@")) {
                "page" -> 16
                "gap" -> 10
                "row" -> 12
                "cardRadius" -> 12
                "buttonRadius" -> 12
                "fieldRadius" -> 9
                else -> fallback
            }
        }
        return value.toFloatOrNull()?.toInt() ?: fallback
    }

    private fun family(name: String): String = when (name) {
        "serif" -> "serif"
        "mono" -> "monospace"
        "condensed" -> "sans-serif-condensed"
        "rounded" -> "sans-serif"
        else -> "sans-serif"
    }

    private fun glyph(name: String): String = when (name.lowercase()) {
        "phone" -> "☎"
        "messages" -> "✉"
        "browser" -> "◎"
        "files", "folder" -> "▤"
        "settings" -> "⚙"
        "camera" -> "◉"
        "maps" -> "⌖"
        "themes", "palette" -> "◐"
        "search" -> "⌕"
        "add", "plus" -> "+"
        "close", "x" -> "×"
        "back" -> "‹"
        "forward" -> "›"
        "home" -> "⌂"
        "shield" -> "⛨"
        "storage" -> "▥"
        "info" -> "i"
        "contrast" -> "◑"
        "play" -> "▶"
        "pause" -> "❚❚"
        "mic" -> "●"
        // The rest are used by the layouts added with Writer, Photos and PhotoPix. An unknown
        // name draws a dot, which is fine for a missing glyph but made those previews look
        // unfinished, so the vocabulary is kept in step with the recipes.
        "speaker" -> "◤"
        "power" -> "⏻"
        "lock" -> "🔒"
        "bolt" -> "⚡"
        "check" -> "✓"
        "warning" -> "⚠"
        "pin" -> "⌖"
        "history" -> "↺"
        "cube" -> "▣"
        "call_end" -> "✆"
        "crop" -> "⛶"
        "pen" -> "✎"
        "edit" -> "✎"
        "share" -> "⇪"
        "delete" -> "⌫"
        "move" -> "✥"
        "brush" -> "🖌"
        "eraser" -> "▱"
        "text" -> "T"
        "keyboard" -> "⌨"
        else -> "•"
    }

    private fun dp(context: Context, value: Float): Int = IosUi.dp(context, value)
}
