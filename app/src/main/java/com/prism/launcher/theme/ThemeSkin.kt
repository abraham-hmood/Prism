package com.prism.launcher.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi

/**
 * Applies the active theme to views that were built before it existed.
 *
 * ## Why a walk rather than another design system
 *
 * Prism has hundreds of screens and thousands of views, many of them constructed in code. The
 * ones built through [IosUi] already resolve their colours from [PrismThemes], but a layout
 * inflated from XML, a stock `Button`, or a screen written three years ago does not. Replacing
 * every call site would be a rewrite; walking the tree once per screen is a runtime that reaches
 * all of them, including screens written tomorrow.
 *
 * ## Every aspect of a view is themeable
 *
 * Themes are not only palettes. A theme can make controls rectangular or pills, thicken or remove
 * borders, raise or flatten everything, switch between families, uppercase small labels and turn
 * glow off entirely. Those traits live in [ThemeShape] and [ThemeTypography], and this walk applies
 * them to the controls that carry them. A theme that removes an aspect (Metro's square fields and
 * absent glow) simply produces a view with that aspect removed, not a crash.
 *
 * ## What it deliberately does not touch
 *
 * Images, charts and canvas-drawn surfaces keep their own pixels -- recolouring a photograph is
 * not theming. Coloured status dots, badges and accent-filled buttons keep their hue because
 * their colour is far from neutral; only near-grey text is remapped to the theme's text ramp.
 * Screens whose whole purpose is a fixed appearance (a terminal, a video picture-in-picture
 * window) opt out by name in [isThemeable].
 */
object ThemeSkin {

    /**
     * Screens that must not be repainted.
     *
     * A terminal keeps its phosphor colours, a mirrored display keeps its black, and a
     * picture-in-picture video is the user's own pixels. Theming those would remove the thing
     * they exist to show. Everything else in the manifest is fair game.
     */
    private val UNTHEMED = setOf(
        "VirtualAppActivity",
        "LykePipActivity",
        "CapturePermissionActivity",
        "DummyLauncherActivity",
        "DisplayReceiverActivity",
        "PdbPhraseActivity",
        "PdbTerminalActivity",
    )

    fun isThemeable(activity: Activity): Boolean =
        activity.javaClass.simpleName !in UNTHEMED

    /**
     * The theme's glow for a control, or null when this theme does not glow.
     *
     * Glow is not a universal Prism decoration: it is part of what a glassy or neon theme looks
     * like, and its absence is equally part of what Metro or Paper looks like. Every screen asks
     * here instead of constructing a [com.prism.launcher.NeonGlowDrawable] unconditionally, so a
     * theme without elevation loses the glow instead of merely changing the colour it glows in.
     */
    fun glowDrawable(context: Context, cornerRadiusDp: Float, strokeWidthDp: Float = 3f): android.graphics.drawable.Drawable? {
        val mode = PrismThemes.modeFor(IosUi.isDark(context))
        val radius = IosUi.dp(context, cornerRadiusDp).toFloat()
        // SPARK'S ANSWER TO THE SAME QUESTION. Every element that used to ask for a halo gets a
        // current instead, because that is the trade the theme makes: no soft elevation, a live
        // edge. Doing it here -- rather than in each caller -- is what makes it every bordered
        // control on the phone and not a special case in one screen.
        if (PrismThemes.active().edgeEffect == EDGE_ELECTRIC) {
            return ElectricEdgeDrawable(context, mode.palette.accent.toInt(), radius)
        }
        if (mode.shape.elevation <= 0) return null
        return com.prism.launcher.NeonGlowDrawable(
            color = mode.palette.accent.toInt(),
            cornerRadius = radius,
            strokeWidth = IosUi.dp(context, strokeWidthDp).toFloat(),
        )
    }

    /** Applies the active theme to one freshly created view and anything it already contains. */
    fun apply(view: View) {
        val dark = IosUi.isDark(view.context)
        applyToTree(view, PrismThemes.modeFor(dark), dark)
    }

    fun apply(activity: Activity) {
        if (!isThemeable(activity)) return
        val dark = IosUi.isDark(activity)
        val mode = PrismThemes.modeFor(dark)
        val root = activity.window?.decorView ?: return
        applyWindow(activity, mode)
        applyToTree(root, mode, dark)
    }

    /**
     * Repaints a dialog's window and content with the active theme.
     *
     * A dialog is its own window, so walking the activity's decor never reaches it. Callers that
     * create dialogs centrally use this after `show()`, when that window exists.
     */
    fun apply(dialog: android.app.Dialog) {
        val context = dialog.context
        val dark = IosUi.isDark(context)
        val mode = PrismThemes.modeFor(dark)
        val window = dialog.window ?: return
        val radius = IosUi.dp(context, mode.shape.card.toFloat()).toFloat()
        // A dialog is one of the few surfaces Prism WAITS FOR before showing, so a theme whose
        // edge is electrical can replace the whole background rather than only its stroke. That is
        // what makes Spark's dialogs arc instead of merely being outlined.
        val card: android.graphics.drawable.Drawable =
            if (PrismThemes.active().edgeEffect == EDGE_ELECTRIC) {
                ElectricEdgeDrawable(
                    context = context,
                    color = mode.palette.accent.toInt(),
                    cornerRadius = radius,
                    fill = mode.palette.card.toInt(),
                )
            } else {
                GradientDrawable().apply {
                    setColor(mode.palette.card.toInt())
                    cornerRadius = radius
                    if (mode.shape.border > 0) {
                        setStroke(
                            IosUi.dp(context, mode.shape.border.toFloat()),
                            mode.palette.divider.toInt(),
                        )
                    }
                }
            }
        // The stock alert theme floats its card clear of the screen edges. Replacing its
        // background must preserve that inset, or a phone-width dialog becomes edge-to-edge.
        window.setBackgroundDrawable(
            android.graphics.drawable.InsetDrawable(card, IosUi.dp(context, 24f)),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarColor = mode.palette.background.toInt()
        }
        applyToTree(window.decorView, mode, dark)
    }

    /**
     * Paints the window behind the content, but only when it is not showing a wallpaper. The
     * launcher's window exists to show the user's photograph; a theme only replaces that backdrop
     * when it explicitly turns the wallpaper off.
     */
    private fun applyWindow(activity: Activity, mode: ThemeModeSpec) {
        val window = activity.window ?: return
        // A translucent window is a dialog or a prompt floating over another screen. Painting it
        // would make it opaque and hide whatever it was meant to sit on top of.
        if (isTranslucent(activity)) return
        val background = mode.palette.background.toInt()
        val theme = PrismThemes.active()
        val wallpaper = showsWallpaper(activity) && theme.showWallpaper
        if (!wallpaper) {
            // A THEME WITH A GROUND PAINTS IT. Fusion's nebula is procedural rather than an
            // asset (see NebulaDrawable), and every other theme keeps the flat colour it always
            // had -- including a user theme whose backdrop this build has never heard of.
            window.setBackgroundDrawable(
                if (theme.backdrop == BACKDROP_NEBULA) {
                    NebulaDrawable(
                        background,
                        dark = ColorUtils.calculateLuminance(background) < 0.5,
                    )
                } else {
                    ColorDrawable(background)
                },
            )
            runCatching {
                @Suppress("DEPRECATION")
                window.statusBarColor = background
                @Suppress("DEPRECATION")
                window.navigationBarColor = background
            }
            WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = ColorUtils.calculateLuminance(background) > 0.55
        }
    }

    private fun showsWallpaper(activity: Activity): Boolean {
        val value = android.util.TypedValue()
        return activity.theme.resolveAttribute(android.R.attr.windowShowWallpaper, value, true) &&
            value.data != 0
    }

    private fun isTranslucent(activity: Activity): Boolean {
        val value = android.util.TypedValue()
        return activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, value, true) &&
            value.data != 0
    }

    fun applyToTree(view: View, mode: ThemeModeSpec, dark: Boolean) {
        // A theme is a coat of paint. A library view, a drawable that is mid-rebuild, or a
        // non-standard ViewGroup should cost that one View its colours, never the screen.
        runCatching { applyToTreeSafely(view, mode, dark) }
    }

    private fun applyToTreeSafely(view: View, mode: ThemeModeSpec, dark: Boolean) {
        when (view) {
            is TextView -> runCatching { styleText(view, mode, dark) }
            else -> Unit
        }

        when (view) {
            is MaterialButton -> runCatching { styleMaterialButton(view, mode, dark) }
            is Button -> runCatching { styleButton(view, mode, dark) }
            is EditText -> runCatching {
                view.setHintTextColor(mode.palette.faint.toInt())
                view.setTextColor(mode.palette.text.toInt())
            }
            is Switch -> runCatching {
                view.thumbTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
                view.trackTintList = ColorStateList.valueOf(mode.palette.divider.toInt())
            }
            is CompoundButton -> runCatching {
                view.buttonTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
            }
            is SeekBar -> runCatching {
                view.progressTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
                view.thumbTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
            }
            is ProgressBar -> runCatching {
                view.progressTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
                view.indeterminateTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
            }
            is TextInputLayout -> runCatching { styleTextInput(view, mode) }
            is Chip -> runCatching { styleChip(view, mode, dark) }
            is TabLayout -> runCatching {
                view.setTabTextColors(mode.palette.muted.toInt(), mode.palette.accent.toInt())
                view.setSelectedTabIndicatorColor(mode.palette.accent.toInt())
                view.setBackgroundColor(mode.palette.surface.toInt())
            }
            is BottomNavigationView -> runCatching {
                view.itemIconTintList = selectedColors(mode)
                view.itemTextColor = selectedColors(mode)
                view.setBackgroundColor(mode.palette.surface.toInt())
            }
            is NavigationView -> runCatching {
                view.itemIconTintList = selectedColors(mode)
                view.itemTextColor = selectedColors(mode)
                view.setBackgroundColor(mode.palette.surface.toInt())
            }
            is FloatingActionButton -> runCatching {
                view.backgroundTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
                view.imageTintList = ColorStateList.valueOf(mode.palette.onAccent.toInt())
                if (mode.shape.elevation <= 0) view.elevation = 0f
            }
            is AppBarLayout -> runCatching { view.setBackgroundColor(mode.palette.surface.toInt()) }
            is MaterialCardView -> runCatching { styleMaterialCard(view, mode) }
            is CardView -> runCatching {
                if (!isTransparent(view.cardBackgroundColor)) {
                    val alpha = Color.alpha(view.cardBackgroundColor.defaultColor)
                    view.setCardBackgroundColor(withAlpha(mode.palette.card, alpha))
                }
                view.radius = dp(view, mode.shape.card.toFloat()).toFloat()
                view.cardElevation = dp(view, mode.shape.elevation.toFloat()).toFloat()
            }
            is Toolbar -> runCatching {
                view.setBackgroundColor(mode.palette.surface.toInt())
                view.setTitleTextColor(mode.palette.text.toInt())
                view.setSubtitleTextColor(mode.palette.muted.toInt())
            }
            is ImageView -> runCatching { styleImage(view, mode, dark) }
            is RecyclerView -> runCatching {
                // Items are created after the Activity's content is set. Without this, every
                // list -- the drawer's groups, notification history, model lists -- was built
                // with the compiled palette while the chrome around it used the theme.
                if (watchedLists.put(view, true) == null) {
                    view.addOnChildAttachStateChangeListener(
                        object : RecyclerView.OnChildAttachStateChangeListener {
                            override fun onChildViewAttachedToWindow(child: View) {
                                applyToTree(child, mode, dark)
                            }

                            override fun onChildViewDetachedFromWindow(child: View) = Unit
                        },
                    )
                }
            }
        }

        runCatching { restyleBackground(view, mode, dark) }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyToTree(view.getChildAt(index), mode, dark)
            }
        }
    }

    // ---- text --------------------------------------------------------------------------

    private fun styleText(view: TextView, mode: ThemeModeSpec, dark: Boolean) {
        if (view is Button) return // buttons are styled as controls, not as labels
        val current = view.currentTextColor
        if (current != 0) {
            val mapped = if (onAccentSurface(view, mode)) {
                withAlpha(mode.palette.onAccent, Color.alpha(current))
            } else {
                mapNeutral(current, mode, dark, view)
            }
            if (mapped != current) view.setTextColor(mapped)
        }
        applyTypeface(view, mode)
        if (mode.typography.scale > 0f && mode.typography.scale != 1f) {
            scaleText(view, mode.typography.scale)
        }
        if (mode.shape.elevation <= 0) {
            // A theme without elevation does not glow. Clearing the layer is what makes Metro's
            // absence of glow an actual absence rather than a colour request that never wins.
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
        // Header casing is applied by the builders that know a view is a header (IosUi's
        // sectionHeader, the theme XML renderer) rather than guessed from text size here: a
        // 10sp app label in the drawer is not a header, and uppercasing every app name would be
        // a theme changing content rather than chrome.
    }

    private fun applyTypeface(view: TextView, mode: ThemeModeSpec) {
        // A theme with a real family changes the shape of the letters; the platform default is
        // left alone so PrismFontEngine's user-selected face keeps winning.
        val family = when (mode.typography.family) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "rounded" -> Typeface.create("sans-serif", Typeface.NORMAL)
            else -> null
        } ?: return
        val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
        view.typeface = Typeface.create(family, style)
    }

    private fun scaleText(view: TextView, factor: Float) {
        val current = view.textSize
        val applied = appliedTextSizes[view]
        val base = if (applied != null && kotlin.math.abs(current - applied) < 0.5f) {
            baseTextSizes[view] ?: current
        } else {
            current
        }
        baseTextSizes[view] = base
        val target = (base * factor).coerceAtLeast(8f)
        appliedTextSizes[view] = target
        if (kotlin.math.abs(current - target) > 0.5f) {
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target)
        }
    }

    private val baseTextSizes = java.util.WeakHashMap<TextView, Float>()
    private val appliedTextSizes = java.util.WeakHashMap<TextView, Float>()

    // ---- controls ----------------------------------------------------------------------

    private fun styleButton(view: Button, mode: ThemeModeSpec, dark: Boolean) {
        val tint = view.backgroundTintList
        val tintColor = tint?.defaultColor
        if (tint != null) {
            if (Color.alpha(tintColor ?: Color.TRANSPARENT) == 0) {
                // A text button: no fill, accent label.
                view.setTextColor(mode.palette.accent.toInt())
            } else {
                val mapped = mapAction(tintColor ?: mode.palette.accent.toInt(), mode)
                if (mapped == mode.palette.accent.toInt()) {
                    view.backgroundTintList = ColorStateList.valueOf(mapped)
                    view.setTextColor(mode.palette.onAccent.toInt())
                }
            }
        } else if (view.background is android.graphics.drawable.StateListDrawable) {
            // Classic Button with the platform's state-list background: give it the theme's fill.
            // A custom drawable (a green answer call, a destructive red) keeps its own colour --
            // theming the chrome must not repaint a control whose colour communicates meaning.
            view.backgroundTintList = ColorStateList.valueOf(mode.palette.accent.toInt())
            view.setTextColor(mode.palette.onAccent.toInt())
        } else if (view.background == null) {
            view.setTextColor(mode.palette.accent.toInt())
        }
        applyTypeface(view, mode)
        val radius = when {
            mode.shape.pill -> 999f
            else -> mode.shape.button.toFloat()
        }
        (view.background as? GradientDrawable)?.cornerRadius = dp(view, radius).toFloat()
        if (mode.shape.elevation <= 0) {
            view.elevation = 0f
            view.translationZ = 0f
        }
        if (!mode.typography.uppercaseHeaders) view.isAllCaps = false
    }

    private fun styleMaterialButton(view: MaterialButton, mode: ThemeModeSpec, dark: Boolean) {
        val tint = view.backgroundTintList
        val tintColor = tint?.defaultColor
        if (tint != null && Color.alpha(tintColor ?: Color.TRANSPARENT) == 0) {
            view.setTextColor(mode.palette.accent.toInt())
            view.iconTint = ColorStateList.valueOf(mode.palette.accent.toInt())
        } else {
            val mapped = mapAction(tintColor ?: mode.palette.accent.toInt(), mode)
            val themedFill = mapped == mode.palette.accent.toInt()
            view.backgroundTintList = ColorStateList.valueOf(mapped)
            if (themedFill) {
                view.setTextColor(mode.palette.onAccent.toInt())
                if (view.icon != null) view.iconTint = ColorStateList.valueOf(mode.palette.onAccent.toInt())
            }
        }
        applyTypeface(view, mode)
        val radius = if (mode.shape.pill) 999f else mode.shape.button.toFloat()
        view.cornerRadius = dp(view, radius)
        view.strokeWidth = dp(view, mode.shape.border.toFloat())
        view.strokeColor = ColorStateList.valueOf(mode.palette.divider.toInt())
        if (mode.shape.elevation <= 0) {
            view.elevation = 0f
            view.translationZ = 0f
        }
    }

    private fun styleTextInput(view: TextInputLayout, mode: ThemeModeSpec) {
        val field = if (mode.shape.pill) 999f else mode.shape.field.toFloat()
        val corner = dp(view, field).toFloat()
        runCatching { view.setBoxCornerRadii(corner, corner, corner, corner) }
        // A borderless theme still needs to see where an outlined field is. One hairline in the
        // theme's divider colour keeps the control usable without giving it the thick outline a
        // bordered theme asks for.
        val stroke = dp(view, maxOf(mode.shape.border, 1).toFloat())
        runCatching { view.setBoxStrokeColor(mode.palette.divider.toInt()) }
        runCatching { view.setBoxStrokeWidth(stroke) }
        runCatching { view.setBoxStrokeWidthFocused(stroke) }
        runCatching { view.defaultHintTextColor = ColorStateList.valueOf(mode.palette.muted.toInt()) }
        if (view.boxBackgroundMode == TextInputLayout.BOX_BACKGROUND_FILLED) {
            runCatching { view.setBoxBackgroundColor(mode.palette.raised.toInt()) }
        }
        runCatching {
            view.setStartIconTintList(ColorStateList.valueOf(mode.palette.accent.toInt()))
            view.setEndIconTintList(ColorStateList.valueOf(mode.palette.accent.toInt()))
        }
    }

    private fun styleChip(view: Chip, mode: ThemeModeSpec, dark: Boolean) {
        val fill = mapSurface(mode.palette.raised.toInt(), mode, dark)
        runCatching { view.chipBackgroundColor = ColorStateList.valueOf(fill) }
        runCatching {
            view.chipStrokeWidth = dp(view, mode.shape.border.toFloat()).toFloat()
            view.chipStrokeColor = ColorStateList.valueOf(mode.palette.divider.toInt())
        }
        view.setTextColor(mapNeutral(view.currentTextColor, mode, dark, view))
        if (view.chipIcon != null) view.chipIconTint = ColorStateList.valueOf(mode.palette.accent.toInt())
    }

    private fun styleMaterialCard(view: MaterialCardView, mode: ThemeModeSpec) {
        // THE DESKTOP'S GRID CELLS ARE NOT PANELS, AND MUST NOT BE OUTLINED. Every cell on the
        // desktop page is a MaterialCardView, one per grid position; the theme's border used to
        // be applied to all of them, which drew the empty grid as 24 rectangles. A phone's
        // desktop has icons on a wallpaper, never a lattice, so these get no fill, no stroke and
        // no elevation -- only the corner radius an item would round against.
        if (view.id == R.id.cellCard) {
            view.radius = dp(view, mode.shape.card.toFloat()).toFloat()
            view.strokeWidth = 0
            view.cardElevation = 0f
            return
        }
        // A transparent card is a hit target or a grouping rectangle, not a panel: the desktop's
        // empty cells are transparent cards, and filling them turned every unused grid position
        // into a white tile sitting on the wallpaper. Its SHAPE is still chrome, though -- the
        // drawer's blurred card is transparent on purpose and must still be rectangular in Metro.
        if (!isTransparent(view.cardBackgroundColor)) {
            val alpha = Color.alpha(view.cardBackgroundColor.defaultColor)
            view.setCardBackgroundColor(withAlpha(mode.palette.card, alpha))
        }
        view.radius = dp(view, mode.shape.card.toFloat()).toFloat()
        view.strokeWidth = dp(view, mode.shape.border.toFloat())
        view.strokeColor = mode.palette.divider.toInt()
        view.cardElevation = dp(view, mode.shape.elevation.toFloat()).toFloat()
        if (mode.shape.elevation <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineAmbientShadowColor = Color.TRANSPARENT
            view.outlineSpotShadowColor = Color.TRANSPARENT
        }
    }

    private fun styleImage(view: ImageView, mode: ThemeModeSpec, dark: Boolean) {
        // Tinted icons are chrome; photographs are not. The former has a tint list and the latter
        // does not, which is the only distinction available without decoding pixels.
        val tint = view.imageTintList ?: return
        val current = tint.defaultColor
        val mapped = mapIcon(current, mode, dark, view)
        if (mapped != current) view.imageTintList = ColorStateList.valueOf(mapped)
    }

    private fun selectedColors(mode: ThemeModeSpec): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            mode.palette.accent.toInt(),
            mode.palette.accent.toInt(),
            mode.palette.muted.toInt(),
        ),
    )

    // ---- colour mapping ----------------------------------------------------------------

    private fun mapNeutral(color: Int, mode: ThemeModeSpec, dark: Boolean, view: View? = null): Int {
        if (isPaletteColor(color, mode)) return color
        legacyForeground(color, mode)?.let { return withAlpha(it, Color.alpha(color)) }
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] > 0.22f) return color
        // A permanently dark surface (Nebula's post cards, a terminal transcript) is not part of
        // the theme's light/dark axis. Inverting white labels on it for a light theme would make
        // them invisible, so neutral text over a dark backdrop keeps the colour it was given.
        if ((backgroundLuminance(view) ?: 1f) < 0.3f) return color
        val alpha = Color.alpha(color)
        val luminance = ColorUtils.calculateLuminance(color).toFloat()
        val target = when {
            dark -> when {
                luminance >= 0.62f -> mode.palette.text
                luminance >= 0.3f -> mode.palette.muted
                else -> mode.palette.faint
            }
            else -> when {
                luminance <= 0.36f -> mode.palette.text
                luminance <= 0.64f -> mode.palette.muted
                else -> mode.palette.faint
            }
        }
        return withAlpha(target, alpha)
    }

    private fun mapIcon(color: Int, mode: ThemeModeSpec, dark: Boolean, view: View? = null): Int {
        if (isPaletteColor(color, mode)) return color
        legacyForeground(color, mode)?.let { return withAlpha(it, Color.alpha(color)) }
        return mapNeutral(color, mode, dark, view)
    }

    /** Maps neutral panel fills without touching accent-coloured tiles and artwork. */
    private fun mapSurface(color: Int, mode: ThemeModeSpec, dark: Boolean): Int {
        if (Color.alpha(color) == 0) return color
        if (isPaletteColor(color, mode)) return color
        legacySurface(color, mode)?.let { return withAlpha(it, Color.alpha(color)) }
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] > 0.2f) return color
        val alpha = Color.alpha(color)
        val luminance = ColorUtils.calculateLuminance(color).toFloat()
        val target = when {
            alpha < 80 -> mode.palette.card
            alpha < 240 -> mode.palette.overlay
            luminance > 0.86f -> mode.palette.card
            luminance > 0.68f -> if (dark) mode.palette.raised else mode.palette.card
            luminance > 0.4f -> mode.palette.raised
            luminance > 0.12f -> mode.palette.surface
            else -> mode.palette.background
        }
        return withAlpha(target, alpha)
    }

    private fun mapAction(color: Int, mode: ThemeModeSpec): Int {
        if (isPaletteColor(color, mode)) return color
        legacyForeground(color, mode)?.let { return it.toInt() }
        // Saturated colours that are not one of Prism's compiled accents carry meaning (a green
        // answer, a red decline). Recolour neutral chrome, but never a semantic control.
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] > 0.25f) return color
        return mode.palette.accent.toInt()
    }

    /**
     * Recognises the compiled colours Prism was built with and returns the role the active theme
     * uses for it. Without this, a hard-coded blue stays blue under a pink theme.
     */
    private fun legacyForeground(color: Int, mode: ThemeModeSpec): Long? {
        val rgb = color and 0x00FFFFFF
        return when (rgb) {
            0x007AFF, 0x0A84FF, 0x7C9EFF, 0x2196F3, 0x1E88E5, 0x3F51B5, 0x5856D6,
            0x5E5CE6, 0xAF52DE, 0xBF5AF2, 0x673AB7, 0x7C4DFF,
            -> mode.palette.accent

            0xFF3B30, 0xFF453A, 0xE5484D, 0xD64545, 0xB3261E, 0xE81123, 0x9E2A2B,
            0xB23A48, 0xE53935, 0xF44336,
            -> mode.palette.destructive

            0x1D1D1F -> mode.palette.text
            0x86868B, 0x8E8E93, 0x9B9BA1, 0xC7C7C7 -> mode.palette.muted
            else -> null
        }
    }

    /**
     * Only colours that carry a role in a surface. Neutrals are deliberately absent: black is a
     * background here but primary text above, and only luminance can tell the difference.
     */
    private fun legacySurface(color: Int, mode: ThemeModeSpec): Long? {
        val rgb = color and 0x00FFFFFF
        return when (rgb) {
            0x007AFF, 0x0A84FF, 0x7C9EFF, 0x2196F3, 0x1E88E5, 0x3F51B5, 0x5856D6,
            0x5E5CE6, 0xAF52DE, 0xBF5AF2, 0x673AB7, 0x7C4DFF,
            -> mode.palette.accent

            0xFF3B30, 0xFF453A, 0xE5484D, 0xD64545, 0xB3261E, 0xE81123, 0x9E2A2B,
            0xB23A48, 0xE53935, 0xF44336,
            -> mode.palette.destructive

            else -> null
        }
    }

    private fun isPaletteColor(color: Int, mode: ThemeModeSpec): Boolean {
        val rgb = color and 0x00FFFFFF
        val palette = mode.palette
        return rgb == (palette.background.toInt() and 0x00FFFFFF) ||
            rgb == (palette.surface.toInt() and 0x00FFFFFF) ||
            rgb == (palette.raised.toInt() and 0x00FFFFFF) ||
            rgb == (palette.card.toInt() and 0x00FFFFFF) ||
            rgb == (palette.text.toInt() and 0x00FFFFFF) ||
            rgb == (palette.muted.toInt() and 0x00FFFFFF) ||
            rgb == (palette.faint.toInt() and 0x00FFFFFF) ||
            rgb == (palette.divider.toInt() and 0x00FFFFFF) ||
            rgb == (palette.accent.toInt() and 0x00FFFFFF) ||
            rgb == (palette.accentAlt.toInt() and 0x00FFFFFF) ||
            rgb == (palette.onAccent.toInt() and 0x00FFFFFF) ||
            rgb == (palette.destructive.toInt() and 0x00FFFFFF) ||
            rgb == (palette.overlay.toInt() and 0x00FFFFFF)
    }

    private fun withAlpha(color: Long, alpha: Int): Int =
        (color.toInt() and 0x00FFFFFF) or (alpha shl 24)

    private fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.55

    private fun onAccentSurface(view: View, mode: ThemeModeSpec): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            val surface = backgroundArgb(current)
            if (surface != null && Color.alpha(surface) > 32) {
                val rgb = surface and 0x00FFFFFF
                return rgb == (mode.palette.accent.toInt() and 0x00FFFFFF) ||
                    rgb == (mode.palette.accentAlt.toInt() and 0x00FFFFFF) ||
                    rgb == 0x007AFF || rgb == 0x0A84FF || rgb == 0x7C9EFF
            }
            current = current.parent as? View
            depth++
        }
        return false
    }

    /** The colour behind a label, nearest opaque ancestor wins. Null means wallpaper or unknown. */
    private fun backgroundLuminance(view: View?): Float? {
        var current = view
        var depth = 0
        while (current != null && depth < 12) {
            val surface = backgroundArgb(current)
            if (surface != null && Color.alpha(surface) > 32) {
                return ColorUtils.calculateLuminance(surface).toFloat()
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun backgroundArgb(view: View): Int? = when (view) {
        is MaterialCardView -> view.cardBackgroundColor.defaultColor
        else -> when (val background = view.background) {
            is ColorDrawable -> background.color
            is GradientDrawable -> if (Build.VERSION.SDK_INT >= 24) {
                runCatching { background.color?.defaultColor }.getOrNull()
            } else {
                null
            }
            else -> null
        }
    }

    // ---- backgrounds -------------------------------------------------------------------

    private fun restyleBackground(view: View, mode: ThemeModeSpec, dark: Boolean) {
        when (val background = view.background) {
            is ColorDrawable -> {
                val color = background.color
                if (color == Color.TRANSPARENT) return
                // A leaf painted opaque black is a scrim or a content overlay (the launcher's
                // dim layer, a video backing). Repainting it with a light theme's background
                // would turn a dim into a flash of white, so those keep their own black.
                if (isOpaqueBlack(color) && view !is ViewGroup) return
                val mapped = mapSurface(color, mode, dark)
                if (mapped != color) background.color = mapped
            }
            is GradientDrawable -> {
                if (Build.VERSION.SDK_INT >= 24) {
                    val current = runCatching { background.color?.defaultColor ?: 0 }.getOrDefault(0)
                    if (current != 0) {
                        if (isOpaqueBlack(current) && view !is ViewGroup) return
                        val mapped = mapSurface(current, mode, dark)
                        if (mapped != current) background.setColor(mapped)
                    }
                }
                val radius = controlRadius(view, mode)
                if (radius >= 0f) {
                    runCatching { background.cornerRadius = dp(view, radius).toFloat() }
                }
                if (mode.shape.border > 0 && radius >= 0f) {
                    runCatching {
                        background.setStroke(
                            dp(view, mode.shape.border.toFloat()),
                            mode.palette.divider.toInt(),
                        )
                    }
                }
            }
            else -> restyleDrawableLayer(background, mode, dark)
        }
    }

    /** The radius a theme assigns to a control, or -1 when the view keeps its own shape. */
    private fun controlRadius(view: View, mode: ThemeModeSpec): Float = when (view) {
        is MaterialButton, is Button -> if (mode.shape.pill) 999f else mode.shape.button.toFloat()
        is EditText -> if (mode.shape.pill) 999f else mode.shape.field.toFloat()
        else -> -1f
    }

    /** Reaches through the wrappers a themed drawable usually has: ripple, inset, selector, layer. */
    private fun restyleDrawableLayer(
        drawable: android.graphics.drawable.Drawable?,
        mode: ThemeModeSpec,
        dark: Boolean,
    ) {
        when (drawable) {
            null -> Unit
            is android.graphics.drawable.RippleDrawable -> {
                runCatching { drawable.getDrawable(0) }.getOrNull()
                    ?.let { restyleDrawableLayer(it, mode, dark) }
            }
            is android.graphics.drawable.LayerDrawable -> {
                // A layer drawable's count and its children can disagree while a progress layer is
                // being rebuilt. One bad layer must not take the launcher down with it.
                for (index in 0 until drawable.numberOfLayers) {
                    runCatching { drawable.getDrawable(index) }.getOrNull()
                        ?.let { restyleDrawableLayer(it, mode, dark) }
                }
            }
            is android.graphics.drawable.StateListDrawable -> {
                // Selectors are left alone. A single tint paints EVERY state the same colour,
                // which turned transparent click targets into raised coloured rectangles and
                // made a list row's pressed state indistinguishable from its resting one.
            }
            is android.graphics.drawable.InsetDrawable -> {
                restyleDrawableLayer(drawable.drawable, mode, dark)
            }
            is GradientDrawable -> {
                if (Build.VERSION.SDK_INT >= 24) {
                    val current = runCatching { drawable.color?.defaultColor ?: 0 }.getOrDefault(0)
                    if (current != 0) drawable.setColor(mapSurface(current, mode, dark))
                }
            }
        }
    }

    private fun isTransparent(colors: ColorStateList?): Boolean =
        colors == null || Color.alpha(colors.defaultColor) == 0

    private fun isOpaqueBlack(color: Int): Boolean =
        Color.alpha(color) == 255 && (color and 0x00FFFFFF) == 0

    private val watchedLists = java.util.WeakHashMap<RecyclerView, Boolean>()

    /** Resolves Prism's colour attributes from the active theme, not from the compiled style. */
    fun resolveAttr(activity: Activity, attr: Int): Int? = resolveAttr(activity as Context, attr)

    fun resolveAttr(context: Context, attr: Int): Int? = when (attr) {
        R.attr.prismBackground -> IosUi.groupedBackground(context)
        R.attr.prismSurface -> IosUi.cardBackground(context)
        R.attr.prismCardColor -> IosUi.cardBackground(context)
        R.attr.prismTextPrimary -> IosUi.label(context)
        R.attr.prismTextSecondary -> IosUi.secondaryLabel(context)
        R.attr.prismDivider -> IosUi.separator(context)
        R.attr.prismAccent -> IosUi.accent(context)
        R.attr.prismOverlay -> PrismThemes.modeFor(IosUi.isDark(context)).palette.overlay.toInt()
        else -> null
    }

    private fun dp(view: View, value: Float): Int = IosUi.dp(view.context, value)
}
