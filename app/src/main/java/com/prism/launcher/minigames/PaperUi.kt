package com.prism.launcher.minigames

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.theme.PrismThemes

/**
 * The widgets the paper game is made of: torn-out sheets, pencil buttons, drawn icons.
 *
 * ## Why not the rest of Prism's UI kit
 *
 * Because a rounded iOS card sitting on ruled paper looks like a bug. Everything on this page has to
 * be drawn with the same pencil, including the things that are ordinarily chrome — a dialog here is
 * a sheet of paper with a wobbly border, and a button is a word with a box drawn round it. That is a
 * deliberate exception to Prism's house style and it applies only inside the Minigames page.
 */
object PaperUi {

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Paper Empire's blacklight treatment, only while xOS is the active theme.
     *
     * The check lives here rather than in every drawing call because the game has four renderers
     * and they all have to agree about what page they are on. It is read once per view (the views
     * cache it), not once per frame.
     */
    fun isNeon(context: Context): Boolean = PrismThemes.active().id == "xos"

    // -- The pencils ----------------------------------------------------------

    /** The ink a paper widget draws with, already switched for the blacklight on xOS. */
    fun ink(context: Context, colour: Int? = null): Int = colour ?: if (isNeon(context)) {
        PencilStyle.NEON_CYAN
    } else {
        PencilStyle.graphite(isDark(context))
    }

    /** The quieter ink: notes, labels and the ruling that should not compete with the drawing. */
    fun mutedInk(context: Context, colour: Int? = null): Int = colour ?: if (isNeon(context)) {
        PencilStyle.NEON_BLUE
    } else {
        PencilStyle.graphiteLight(isDark(context))
    }

    /** The one colour that marks the selected or urgent thing. */
    fun accentInk(context: Context): Int =
        if (isNeon(context)) PencilStyle.NEON_MAGENTA else PencilStyle.RED_PENCIL

    /** A soft halo, only under the blacklight. Text keeps its shape; the light spreads. */
    fun <T : TextView> glow(view: T, colour: Int): T {
        if (isNeon(view.context)) view.setShadowLayer(9f, 0f, 0f, colour)
        return view
    }

    // -- Backgrounds ----------------------------------------------------------

    /** A sheet of ruled paper with a hand-drawn edge, for popups and panels. */
    class SheetDrawable(
        private val context: Context,
        private val seed: Long,
        private val torn: Boolean = true,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density

        override fun draw(canvas: Canvas) {
            val dark = isDark(context)
            val neon = isNeon(context)
            val r = RectF(bounds)
            val inset = 3f * density
            r.inset(inset, inset)

            canvas.drawColor(0)
            val paper = if (neon) PencilStyle.neonPaper(dark) else PencilStyle.paper(dark)
            val fill = PencilStyle.shading(paper, alpha = 255)
            canvas.drawRect(r, fill)

            // The blacklight wash. The page is still paper; this is the light sitting on it.
            if (neon) {
                val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        r.left, r.top, r.right, r.bottom,
                        0x3300F0FF, 0x22FF3CF2, Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(r, wash)
            }

            // Ruling inside the sheet, so a popup is a page and not a panel.
            val rulePaint = PencilStyle.pencil(
                1f * density,
                if (neon) PencilStyle.NEON_BLUE else PencilStyle.rule(dark),
                alpha = if (neon) 210 else 150,
            )
            var y = r.top + PencilStyle.LINE_SPACING_DP * density
            while (y < r.bottom) {
                if (neon) {
                    canvas.drawLine(
                        r.left + 4f * density, y, r.right - 4f * density, y,
                        PencilStyle.pencil(3f * density, PencilStyle.NEON_CYAN, alpha = 32),
                    )
                }
                canvas.drawLine(r.left + 4f * density, y, r.right - 4f * density, y, rulePaint)
                y += PencilStyle.LINE_SPACING_DP * density
            }
            if (neon) {
                val marginX = r.left + 34f * density
                canvas.drawLine(
                    marginX, r.top, marginX, r.bottom,
                    PencilStyle.pencil(1.4f * density, PencilStyle.NEON_MAGENTA, alpha = 170),
                )
            }

            val ink = PencilStyle.pencil(
                2f * density,
                if (neon) PencilStyle.NEON_CYAN else PencilStyle.graphite(dark),
            )
            if (torn) {
                // A torn left edge: the sheet came out of a book.
                val rng = Rng(seed)
                var ty = r.top
                var prevX = r.left
                while (ty < r.bottom) {
                    val nextY = ty + 9f * density
                    val nextX = r.left + ((rng.nextDouble() - 0.5) * 5 * density).toFloat()
                    canvas.drawLine(prevX, ty, nextX, minOf(nextY, r.bottom), ink)
                    prevX = nextX
                    ty = nextY
                }
                PencilStyle.line(canvas, r.left, r.top, r.right, r.top, ink, seed + 1, 1.2f)
                PencilStyle.line(canvas, r.right, r.top, r.right, r.bottom, ink, seed + 2, 1.2f)
                PencilStyle.line(canvas, r.right, r.bottom, r.left, r.bottom, ink, seed + 3, 1.2f)
            } else {
                PencilStyle.rect(canvas, r, ink, seed, 1.3f)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** A box drawn round a word: what a button looks like on paper. */
    class PencilButtonDrawable(
        private val context: Context,
        private val seed: Long,
        private val colour: Int? = null,
        private val filled: Boolean = false,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density

        override fun draw(canvas: Canvas) {
            val dark = isDark(context)
            val neon = isNeon(context)
            val r = RectF(bounds)
            r.inset(2.5f * density, 2.5f * density)
            val base = ink(context, colour)
            val ink = PencilStyle.pencil(2f * density, base)
            if (filled) {
                PencilStyle.hatch(
                    canvas, r,
                    PencilStyle.pencil(
                        1.2f * density,
                        if (neon) PencilStyle.NEON_MAGENTA else base,
                        alpha = 90,
                    ),
                    seed + 5, 5f * density,
                )
            }
            if (neon) {
                // A second, wider stroke is what makes a line look lit rather than merely bright.
                PencilStyle.rect(
                    canvas, r,
                    PencilStyle.pencil(4.2f * density, base, alpha = 35),
                    seed, 1.1f,
                )
            }
            PencilStyle.rect(canvas, r, ink, seed, 1.1f)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    // -- Text -----------------------------------------------------------------

    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 19f
        setTextColor(ink(context))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        setPadding(0, dp(context, 2f), 0, dp(context, 6f))
        glow(this, ink(context))
    }

    fun body(context: Context, text: String, colour: Int? = null): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(ink(context, colour))
        typeface = android.graphics.Typeface.SERIF
        setLineSpacing(dp(context, 2f).toFloat(), 1f)
    }

    fun note(context: Context, text: String): TextView = body(context, text, mutedInk(context)).apply {
        textSize = 12f
    }

    /** A line of a stat block: "Soldiers ......... 42" with the dots drawn in. */
    fun statLine(context: Context, label: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 3f), 0, dp(context, 3f))
            addView(body(context, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                body(context, value).apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    fun button(
        context: Context,
        text: String,
        colour: Int? = null,
        filled: Boolean = false,
        seed: Long = text.hashCode().toLong(),
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.SERIF
        setTextColor(ink(context, colour))
        background = PencilButtonDrawable(context, seed, colour, filled)
        glow(this, ink(context, colour))
        setPadding(dp(context, 16f), dp(context, 10f), dp(context, 16f), dp(context, 10f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // -- Icon buttons ---------------------------------------------------------

    /** What a drawn icon button shows. */
    enum class Glyph { MAP, INBOX, ARROW_RIGHT, ARROW_LEFT, TICK, CROSS, PLUS, BACK, BUILD, ARMY, ARMS, STATE }

    /**
     * A transparent icon button whose icon is drawn rather than loaded.
     *
     * Transparent because the design asks for it — the map and inbox buttons sit on the drawing and
     * a filled chip would cover it — and drawn because there is no pencil-style icon font.
     */
    class GlyphButton(
        context: Context,
        private val glyph: Glyph,
        private val colour: Int? = null,
        private val badge: () -> Int = { 0 },
        /**
         * A word under the icon.
         *
         * Drawn rather than left to a tooltip, because a row of four hand-sketched icons is
         * charming and not self-explanatory: a hammer and an anvil are genuinely hard to tell apart
         * at a glance, and "which one was research" is not a puzzle worth setting.
         */
        private val label: String? = null,
    ) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val seed = glyph.ordinal.toLong() * 977

        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
        }

        override fun onDraw(canvas: Canvas) {
            val dark = isDark(context)
            val pad = 9f * density
            val labelRoom = if (label == null) 0f else 13f * density
            val r = RectF(pad, pad, width - pad, height - pad - labelRoom)
            val neon = isNeon(context)
            val base = ink(context, colour)
            val ink = PencilStyle.pencil(2.3f * density, base)

            when (glyph) {
                Glyph.MAP -> PencilStyle.mapGlyph(canvas, r, ink, seed)
                Glyph.INBOX -> PencilStyle.envelopeGlyph(canvas, r, ink, seed)
                Glyph.ARROW_RIGHT -> PencilStyle.arrow(canvas, r, ink, seed, pointingRight = true)
                Glyph.ARROW_LEFT, Glyph.BACK -> PencilStyle.arrow(canvas, r, ink, seed, pointingRight = false)
                Glyph.TICK -> PencilStyle.tick(canvas, r, ink, seed)
                Glyph.CROSS -> PencilStyle.cross(canvas, r, ink, seed)
                Glyph.PLUS -> {
                    PencilStyle.line(canvas, r.left, r.centerY(), r.right, r.centerY(), ink, seed, 0.8f)
                    PencilStyle.line(canvas, r.centerX(), r.top, r.centerX(), r.bottom, ink, seed + 1, 0.8f)
                }
                Glyph.BUILD -> PencilStyle.hammerGlyph(canvas, r, ink, seed)
                Glyph.ARMY -> PencilStyle.swordsGlyph(canvas, r, ink, seed)
                Glyph.ARMS -> PencilStyle.anvilGlyph(canvas, r, ink, seed)
                Glyph.STATE -> PencilStyle.scrollGlyph(canvas, r, ink, seed)
            }

            label?.let {
                val text = PencilStyle.textPaint(
                    10.5f * density, dark,
                    colour ?: mutedInk(context),
                ).apply { textAlign = Paint.Align.CENTER }
                // Shrunk to fit rather than clipped or abbreviated. The label is the only thing
                // saying what a hand-drawn anvil is for, so "Research" has to be allowed to say
                // "Research" — calling it "Arms" to save eight pixels made the research button
                // look like it had been removed.
                val room = width - 3f * density
                while (text.textSize > 7f * density && text.measureText(it) > room) {
                    text.textSize = text.textSize - 0.5f * density
                }
                canvas.drawText(it, width / 2f, height - 2f * density, text)
            }

            val count = badge()
            if (count > 0) {
                // A circled number in red pencil, in the corner, the way you mark something urgent.
                val bx = width - 9f * density
                val by = 10f * density
                val alert = accentInk(context)
                val red = PencilStyle.pencil(1.8f * density, alert)
                canvas.drawCircle(bx, by, 8.5f * density, red)
                val text = PencilStyle.textPaint(11f * density, dark, alert).apply {
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(count.coerceAtMost(9).toString(), bx, by + 4f * density, text)
            }
        }
    }

    // -- Popups ---------------------------------------------------------------

    /**
     * A popup that is a sheet of paper on top of the page.
     *
     * Added into the host [FrameLayout] rather than shown as a Dialog: a Dialog brings its own
     * window with its own background and its own rounded corners, and fighting all three to make it
     * look like paper is more work than drawing the sheet where it belongs. It also means the
     * drawing behind stays visible, which is the point of a popup on a map.
     */
    fun sheet(
        host: FrameLayout,
        title: String,
        seed: Long = title.hashCode().toLong(),
        widthFraction: Float = 0.9f,
        build: (LinearLayout) -> Unit,
    ): View {
        val context = host.context
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = SheetDrawable(context, seed)
            val p = dp(context, 18f)
            setPadding(p + dp(context, 6f), p, p, p)
        }
        body.addView(heading(context, title))
        build(body)

        val scroller = ScrollView(context).apply {
            isFillViewport = false
            addView(body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        scrim.addView(
            scroller,
            FrameLayout.LayoutParams(
                (host.width * widthFraction).toInt().coerceAtLeast(dp(context, 260f)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                topMargin = dp(context, 40f)
                bottomMargin = dp(context, 40f)
            },
        )

        scrim.setOnClickListener { host.removeView(scrim) }
        host.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return scrim
    }

    fun dismiss(host: FrameLayout, view: View?) {
        view?.let { host.removeView(it) }
    }

    /**
     * A strip of tabs across the top of a sheet.
     *
     * Scrolls sideways rather than wrapping, because the number of tabs depends on how far the
     * player has got and a wrapping row would change the height of the sheet as the game advances.
     * The selected tab is filled; the rest are outlines, which is the same language the buttons use.
     */
    fun tabStrip(
        context: Context,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                labels.forEachIndexed { index, label ->
                    addView(
                        button(
                            context,
                            label,
                            filled = index == selected,
                            seed = label.hashCode().toLong(),
                        ) { if (index != selected) onSelect(index) }.apply {
                            textSize = 14f
                            setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { if (index > 0) leftMargin = dp(context, 6f) },
                    )
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /** A row of buttons along the bottom of a sheet. */
    fun buttonRow(context: Context, vararg buttons: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(context, 12f), 0, 0)
        buttons.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(context, 8f)
            })
        }
    }

    fun spacer(context: Context, height: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height),
        )
    }

    /** A drawn horizontal rule, for separating sections of a sheet. */
    fun divider(context: Context): View = object : View(context) {
        private val density = context.resources.displayMetrics.density
        override fun onDraw(canvas: Canvas) {
            PencilStyle.line(
                canvas, 0f, height / 2f, width.toFloat(), height / 2f,
                PencilStyle.pencil(1.6f * density, mutedInk(context), alpha = 150),
                seed = 404, amount = 1.1f,
            )
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 14f),
        ).apply { topMargin = dp(context, 6f); bottomMargin = dp(context, 4f) }
    }

    /** Big numbers, shortened the way a person writes them. */
    fun shortNumber(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1fB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 10_000 -> "%.0fk".format(value / 1_000.0)
        value >= 1_000 -> "%.1fk".format(value / 1_000.0)
        else -> value.toString()
    }

    fun shortDuration(millis: Long): String {
        if (millis <= 0) return "done"
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
