package com.prism.launcher.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * A miniature of a real Prism screen drawn from [theme].
 *
 * ## Why this is drawn rather than screenshotted
 *
 * A screenshot of the current settings page would show the CURRENT theme, not the one on the card,
 * and taking one per card would need a hidden window and a frame of waiting per theme. Drawing the
 * same scenes with the candidate's palette, type, radius and density is immediate, works while the
 * card is in a list, and stays legible at thumbnail size.
 *
 * The scenes are the shapes of the real screens: the launcher grid and dock, grouped settings
 * rows, the message list, and the browser's chrome. Each card rotates through them so a user sees
 * more than one surface before choosing.
 */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme: PrismThemeSpec? = null
        set(value) { field = value; invalidate() }

    var dark: Boolean = false
        set(value) { field = value; invalidate() }

    var scene: Int = 0
        set(value) { field = value; invalidate() }

    /** Restricts this preview to one layout recipe, for the fan carousel. */
    var layoutName: String? = null
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private val designWidth = 200f
    private val designHeight = 260f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = theme ?: return
        val mode = if (dark) spec.dark else spec.light
        val palette = mode.palette
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.save()
        canvas.scale(width / designWidth, height / designHeight)
        path.reset()
        path.addRoundRect(
            RectF(0f, 0f, designWidth, designHeight),
            mode.shape.card.coerceAtLeast(2).toFloat(),
            mode.shape.card.coerceAtLeast(2).toFloat(),
            Path.Direction.CW,
        )
        canvas.clipPath(path)
        canvas.drawColor(palette.background.toInt())

        configure(mode, palette, height = designHeight)
        when {
            layoutName != null -> drawLayout(canvas, layoutName!!, mode)
            scene % 4 == 0 -> drawLauncher(canvas, mode)
            scene % 4 == 1 -> drawSettings(canvas, mode)
            scene % 4 == 2 -> drawMessages(canvas, mode)
            else -> drawBrowser(canvas, mode)
        }

        // A hairline frame keeps a light theme from disappearing into a white list background.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = palette.divider.toInt()
        canvas.drawRoundRect(
            RectF(0.5f, 0.5f, designWidth - 0.5f, designHeight - 0.5f),
            mode.shape.card.toFloat(),
            mode.shape.card.toFloat(),
            paint,
        )
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun configure(mode: ThemeModeSpec, palette: ThemePalette, height: Float) {
        text.typeface = typeface(mode.typography.family)
        text.isFakeBoldText = false
        text.letterSpacing = mode.typography.letterSpacing
        paint.color = palette.card.toInt()
        paint.style = Paint.Style.FILL
        text.color = palette.text.toInt()
        paint.strokeWidth = mode.shape.border.toFloat() * 1.5f
    }

    private fun drawLauncher(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        label(canvas, "Prism", 14f, 27f, 16f, palette.text, bold = true)
        text.textSize = 7f
        text.color = palette.muted.toInt()
        canvas.drawText("12:40", 164f, 16f, text)

        val columns = if (mode.shape.card <= 2) 3 else 4
        val tileWidth = if (columns == 3) 54f else 36f
        val gap = 9f
        val startX = 14f
        val startY = 42f
        val labels = listOf("Phone", "Messages", "Browser", "Files", "Settings", "Camera", "Themes", "Maps")
        val colors = listOf(palette.accent, palette.accentAlt, palette.accent, palette.muted,
            palette.faint, palette.accentAlt, palette.accent, palette.muted)
        for (index in 0 until 8) {
            val column = index % columns
            val row = index / columns
            val x = startX + column * (tileWidth + gap)
            val y = startY + row * (tileWidth + 12f)
            paint.color = colors[index % colors.size].toInt()
            if (mode.shape.card <= 2) {
                canvas.drawRect(x, y, x + tileWidth, y + tileWidth * 0.62f, paint)
            } else {
                rect.set(x, y, x + tileWidth, y + tileWidth)
                canvas.drawRoundRect(rect, mode.shape.card / 2.2f, mode.shape.card / 2.2f, paint)
            }
            label(
                canvas,
                labels[index],
                x + tileWidth / 2f,
                y + tileWidth + 7f,
                6f,
                palette.muted,
                center = true,
            )
        }

        val dockY = 218f
        rect.set(12f, dockY, 188f, dockY + 31f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        for (index in 0 until 4) {
            val x = 32f + index * 46f
            paint.color = colors[index % colors.size].toInt()
            canvas.drawCircle(x, dockY + 15f, 9f, paint)
        }
    }

    private fun drawSettings(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        label(canvas, "Settings", 14f, 28f, 17f, palette.text, bold = true)
        text.textSize = 7f
        text.color = palette.muted.toInt()
        canvas.drawText("Personalize Prism", 14f, 39f, text)

        val rows = listOf(
            "Themes" to "xOS · built in",
            "Display" to "Brightness, refresh rate",
            "Privacy" to "Blocklist and DNS",
            "Storage" to "42.6 GB free",
        )
        var y = 50f
        for ((index, pair) in rows.withIndex()) {
            if (index == 0 || index == 3) {
                val cardTop = y
                val cardHeight = if (index == 0) 62f else 44f
                rect.set(11f, cardTop, 189f, cardTop + cardHeight)
                paint.color = palette.card.toInt()
                canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
                if (mode.shape.border > 0) {
                    paint.style = Paint.Style.STROKE
                    paint.color = palette.divider.toInt()
                    canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
                    paint.style = Paint.Style.FILL
                }
                y += 8f
            }
            paint.color = palette.accent.toInt()
            canvas.drawCircle(22f, y + 8f, 5f, paint)
            label(canvas, pair.first, 33f, y + 12f, 9f, palette.text, bold = true)
            text.textSize = 6.5f
            text.color = palette.muted.toInt()
            canvas.drawText(pair.second, 33f, y + 21f, text)
            y += if (index == 0) 23f else 21f
            if (index < 2) {
                paint.color = palette.divider.toInt()
                canvas.drawRect(33f, y - 6f, 180f, y - 5f, paint)
            }
        }

        label(canvas, "SYSTEM", 14f, 196f, 7f, palette.faint, bold = true)
        rect.set(11f, 202f, 189f, 238f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "About Prism", 24f, 214f, 8f, palette.text)
        label(canvas, "Version 1.0", 24f, 224f, 6.5f, palette.muted)
    }

    private fun drawMessages(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        label(canvas, "Messages", 14f, 29f, 18f, palette.text, bold = true)
        rect.set(13f, 38f, 187f, 52f)
        paint.color = palette.raised.toInt()
        canvas.drawRoundRect(rect, mode.shape.field.toFloat(), mode.shape.field.toFloat(), paint)
        label(canvas, "Search", 20f, 47f, 7f, palette.faint)

        val names = listOf("Sam" to "How can I help you?", "Nora" to "red", "Aether" to "Opening training…")
        var y = 62f
        for ((index, pair) in names.withIndex()) {
            paint.color = if (index == 1) palette.accentAlt.toInt() else palette.accent.toInt()
            canvas.drawCircle(22f, y + 12f, 11f, paint)
            label(canvas, pair.first.take(1), 22f, y + 16f, 10f, palette.onAccent, center = true, bold = true)
            label(canvas, pair.first, 39f, y + 10f, 9f, palette.text, bold = true)
            label(canvas, pair.second, 39f, y + 22f, 7f, palette.muted)
            paint.color = palette.divider.toInt()
            canvas.drawRect(39f, y + 34f, 186f, y + 35f, paint)
            y += 44f
        }
        rect.set(13f, 204f, 187f, 248f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "New conversation", 22f, 220f, 8f, palette.text, bold = true)
        label(canvas, "Talk to Sam, Nora or Aether", 22f, 231f, 6.5f, palette.muted)
    }

    private fun drawBrowser(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        rect.set(12f, 12f, 152f, 30f)
        paint.color = palette.raised.toInt()
        canvas.drawRoundRect(rect, mode.shape.field.toFloat(), mode.shape.field.toFloat(), paint)
        label(canvas, "Search or type a URL", 19f, 24f, 7f, palette.faint)
        rect.set(156f, 12f, 188f, 30f)
        paint.color = palette.accent.toInt()
        canvas.drawRoundRect(rect, mode.shape.button.toFloat(), mode.shape.button.toFloat(), paint)
        label(canvas, "Go", 172f, 24f, 7f, palette.onAccent, center = true)

        rect.set(12f, 44f, 188f, 92f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "prism://home", 22f, 61f, 9f, palette.text, bold = true)
        label(canvas, "Fast, private, yours.", 22f, 73f, 6.5f, palette.muted)

        rect.set(12f, 102f, 188f, 182f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "A calm place to start", 22f, 120f, 8f, palette.text, bold = true)
        for (index in 0 until 3) {
            val top = 128f + index * 17f
            paint.color = palette.divider.toInt()
            canvas.drawRect(22f, top, 176f, top + 1f, paint)
            label(canvas, "Bookmark " + (index + 1), 22f, top + 9f, 6f, palette.muted)
        }
        label(canvas, "PRISM BROWSER", 14f, 205f, 6.5f, palette.faint, bold = true)
    }

    private fun drawLayout(canvas: Canvas, name: String, mode: ThemeModeSpec) {
        when {
            name.startsWith("launcher") -> if (name.contains("drawer")) drawDrawer(canvas, mode) else drawLauncher(canvas, mode)
            name == "settings.root" -> drawSettings(canvas, mode)
            name == "messages.list" -> drawMessages(canvas, mode)
            name == "browser.chrome" -> drawBrowser(canvas, mode)
            name == "files.grid" -> drawFiles(canvas, mode)
            name == "dialogs.alert" -> drawDialog(canvas, mode)
            else -> drawWidget(canvas, mode)
        }
    }

    private fun drawDrawer(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        label(canvas, "Apps", 14f, 24f, 17f, palette.text, bold = true)
        rect.set(12f, 34f, 188f, 50f)
        paint.color = palette.raised.toInt()
        canvas.drawRoundRect(rect, mode.shape.field.toFloat(), mode.shape.field.toFloat(), paint)
        label(canvas, "Search apps", 20f, 45f, 7f, palette.faint)
        val labels = listOf("Browser", "Files", "Messages", "Themes", "Camera", "Maps", "Music", "Wallet")
        for (index in labels.indices) {
            val column = index % 2
            val row = index / 2
            val x = 20f + column * 80f
            val y = 66f + row * 43f
            paint.color = if (index % 2 == 0) palette.accent.toInt() else palette.accentAlt.toInt()
            rect.set(x, y, x + 18f, y + 18f)
            canvas.drawRoundRect(rect, 5f, 5f, paint)
            label(canvas, labels[index], x + 25f, y + 12f, 7f, palette.text)
        }
    }

    private fun drawFiles(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        label(canvas, "Files", 14f, 27f, 18f, palette.text, bold = true)
        label(canvas, "Internal storage", 188f, 18f, 6f, palette.muted, center = true)
        val names = listOf("Downloads", "Pictures", "Music", "Documents")
        for (index in names.indices) {
            val column = index % 2
            val row = index / 2
            val x = 12f + column * 90f
            val y = 40f + row * 54f
            rect.set(x, y, x + 82f, y + 44f)
            paint.color = palette.card.toInt()
            canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
            paint.color = palette.accent.toInt()
            canvas.drawRoundRect(RectF(x + 8f, y + 12f, x + 26f, y + 28f), 4f, 4f, paint)
            label(canvas, names[index], x + 32f, y + 24f, 7f, palette.text)
        }
    }

    private fun drawDialog(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        canvas.drawColor(palette.overlay.toInt())
        rect.set(24f, 70f, 176f, 190f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "Apply theme?", 100f, 98f, 11f, palette.text, center = true, bold = true)
        label(canvas, "Every screen updates", 100f, 118f, 7f, palette.muted, center = true)
        label(canvas, "immediately.", 100f, 128f, 7f, palette.muted, center = true)
        label(canvas, "Cancel", 66f, 168f, 8f, palette.accent, center = true)
        label(canvas, "Apply", 133f, 168f, 8f, palette.accent, center = true, bold = true)
    }

    private fun drawWidget(canvas: Canvas, mode: ThemeModeSpec) {
        val palette = mode.palette
        rect.set(14f, 38f, 186f, 118f)
        paint.color = palette.card.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "NOW", 28f, 58f, 7f, palette.faint, bold = true)
        label(canvas, "A calm, focused Prism.", 28f, 78f, 11f, palette.text, bold = true)
        label(canvas, "Themes · 8 built in", 28f, 94f, 7f, palette.accent)
        rect.set(14f, 128f, 186f, 168f)
        paint.color = palette.raised.toInt()
        canvas.drawRoundRect(rect, mode.shape.card.toFloat(), mode.shape.card.toFloat(), paint)
        label(canvas, "Today", 28f, 148f, 7f, palette.muted)
        label(canvas, "3 new messages", 28f, 159f, 6f, palette.faint)
    }

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Long,
        center: Boolean = false,
        bold: Boolean = false,
    ) {
        text.textSize = size
        text.color = color.toInt()
        text.isFakeBoldText = bold
        if (center) text.textAlign = Paint.Align.CENTER else text.textAlign = Paint.Align.LEFT
        canvas.drawText(value, x, y, text)
    }

    private fun typeface(family: String): Typeface = when (family) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        "rounded" -> Typeface.create("sans-serif", Typeface.NORMAL)
        else -> Typeface.SANS_SERIF
    }
}
