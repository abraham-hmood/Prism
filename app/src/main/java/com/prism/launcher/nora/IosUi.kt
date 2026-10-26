package com.prism.launcher.nora

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeModeSpec
import com.prism.launcher.theme.ThemePalette

/**
 * A small iOS-flavoured design system, built in code.
 *
 * Values are taken from Apple's Human Interface Guidelines semantic colours and standard
 * metrics -- 10pt corner radius on grouped cards, 16pt margins, the systemBlue/systemRed
 * accents, and the two-tier grouped background that gives iOS settings its characteristic
 * card-on-tinted-backdrop look. Both appearances are defined because Prism follows the system
 * theme.
 */
object IosUi {

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * The active theme's values for the appearance currently on screen.
     *
     * Every helper below reads this instead of a constant. That is the whole mechanism by which
     * changing a theme reaches the hundred screens that already use this design system: they keep
     * calling the same semantic names, and the names resolve to the selected theme.
     */
    fun mode(ctx: Context): ThemeModeSpec =
        previewTheme.get() ?: PrismThemes.mode(light = !isDark(ctx))

    fun palette(ctx: Context): ThemePalette = mode(ctx).palette

    /** Renders one tree with [theme] without changing the selected theme. Used by previews. */
    fun <T> withTheme(theme: ThemeModeSpec, block: () -> T): T {
        val previous = previewTheme.get()
        previewTheme.set(theme)
        return try { block() } finally { previewTheme.set(previous) }
    }

    private val previewTheme = ThreadLocal<ThemeModeSpec?>()

    // ── Semantic colours ────────────────────────────────────────────────────

    fun groupedBackground(ctx: Context) = palette(ctx).background.toInt()
    fun cardBackground(ctx: Context) = palette(ctx).card.toInt()
    fun label(ctx: Context) = palette(ctx).text.toInt()
    fun secondaryLabel(ctx: Context) = palette(ctx).muted.toInt()
    fun tertiaryLabel(ctx: Context) = palette(ctx).faint.toInt()
    fun separator(ctx: Context) = palette(ctx).divider.toInt()
    fun accent(ctx: Context) = palette(ctx).accent.toInt()
    fun destructive(ctx: Context) = palette(ctx).destructive.toInt()
    fun fill(ctx: Context) = palette(ctx).raised.toInt()

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()

    // ── Building blocks ─────────────────────────────────────────────────────

    /** The grouped-inset card that everything sits on in modern iOS settings screens. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val theme = mode(ctx)
        background = GradientDrawable().apply {
            setColor(cardBackground(ctx))
            cornerRadius = dp(ctx, theme.shape.card.toFloat()).toFloat()
            if (theme.shape.border > 0) {
                setStroke(dp(ctx, theme.shape.border.toFloat()), separator(ctx))
            }
        }
        val p = dp(ctx, theme.density.page.toFloat())
        setPadding(p, dp(ctx, theme.density.row.toFloat()), p, dp(ctx, theme.density.row.toFloat()))
    }

    /** Section header: uppercase when the theme asks for it, small, secondary. */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        val theme = mode(ctx)
        this.text = if (theme.typography.uppercaseHeaders) text.uppercase() else text
        textSize = 13f
        letterSpacing = 0.06f + theme.typography.letterSpacing
        setTextColor(secondaryLabel(ctx))
        setPadding(dp(ctx, theme.density.page.toFloat() + 4f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 7f))
    }

    /** Footer text under a card, explaining what the section does. */
    fun sectionFooter(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(secondaryLabel(ctx))
        setPadding(dp(ctx, 20f), dp(ctx, 7f), dp(ctx, 20f), dp(ctx, 4f))
    }

    fun hairline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f))
        ).apply { topMargin = dp(ctx, 10f); bottomMargin = dp(ctx, 10f) }
        setBackgroundColor(separator(ctx))
    }

    /** Filled capsule button — iOS's prominent action style. */
    fun filledButton(ctx: Context, text: String, tint: Int = accent(ctx)): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(palette(ctx).onAccent.toInt())
            // Fake bold rather than a bold Typeface: PrismFontEngine reassigns typeface after
            // layout is built, and the single-argument setter would drop a style set here.
            paint.isFakeBoldText = true
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 14f))
            background = filledBackground(ctx, tint)
            isClickable = true
            isFocusable = true
        }

    /** The filled button's background, exposed so a tint change keeps its ripple. */
    fun filledBackground(ctx: Context, tint: Int): Drawable {
        val theme = mode(ctx)
        return rippleOf(ctx, GradientDrawable().apply {
            setColor(tint)
            cornerRadius = dp(ctx, if (theme.shape.pill) 999f else theme.shape.button.toFloat()).toFloat()
        })
    }

    /** Tinted (low-emphasis) button — iOS's secondary action style. */
    fun tintedButton(ctx: Context, text: String, tint: Int = accent(ctx)): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(tint)
            setPadding(0, dp(ctx, 13f), 0, dp(ctx, 13f))
            val theme = mode(ctx)
            background = rippleOf(ctx, GradientDrawable().apply {
                setColor((tint and 0x00FFFFFF) or 0x1F000000)
                cornerRadius = dp(ctx, if (theme.shape.pill) 999f else theme.shape.button.toFloat()).toFloat()
            })
            isClickable = true
            isFocusable = true
        }

    private fun rippleOf(ctx: Context, content: Drawable): Drawable =
        RippleDrawable(
            ColorStateList.valueOf((palette(ctx).text.toInt() and 0x00FFFFFF) or 0x22000000),
            content, null
        )

    /** Thin rounded progress track, iOS-style rather than Material's square bar. */
    fun progressDrawable(ctx: Context): Drawable {
        val radius = dp(ctx, 3f).toFloat()
        val track = GradientDrawable().apply {
            setColor(fill(ctx))
            cornerRadius = radius
        }
        val bar = GradientDrawable().apply {
            setColor(accent(ctx))
            cornerRadius = radius
        }
        return LayerDrawable(
            arrayOf(track, ClipDrawable(bar, Gravity.START, ClipDrawable.HORIZONTAL))
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** Text field styled as an inset field rather than an underlined Material one. */
    fun fieldBackground(ctx: Context): Drawable = GradientDrawable().apply {
        val theme = mode(ctx)
        setColor(fill(ctx))
        cornerRadius = dp(ctx, if (theme.shape.pill) 999f else theme.shape.field.toFloat()).toFloat()
        if (theme.shape.border > 0) {
            setStroke(dp(ctx, theme.shape.border.toFloat()), separator(ctx))
        }
    }
}
