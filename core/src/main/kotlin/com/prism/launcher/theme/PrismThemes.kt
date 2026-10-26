package com.prism.launcher.theme

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.UUID

/**
 * Prism's theme model and store.
 *
 * ## Why the theme is data, not a compiled resource
 *
 * Android resources are compiled into the APK: a layout or a `<style>` cannot be replaced while the
 * process is running. Prism needs themes that can be created, imported and switched without a
 * reinstall, so a theme is a value object here -- palette, type, shape, density and a set of XML
 * layout recipes -- and every host renders it through one small runtime. Android's renderer
 * interprets the XML with real `View` objects; desktop's renderer maps the same names to Compose.
 *
 * ## The two appearances are part of one theme
 *
 * A theme is not "a dark theme" or "a light theme". Every theme carries both, and the host picks
 * the one that matches the current `PrismSettings` mode. That is what lets a user design a warm
 * light mode and a black dark mode in the same editor and have the system switch between them.
 *
 * ## Built-ins are read-only
 *
 * Editing one saves a user copy. This is deliberate: the built-in list is the fallback when a
 * stored theme is damaged, and a user should never be able to leave Prism without a working xOS.
 */
data class ThemePalette(
    val background: Long,
    val surface: Long,
    val raised: Long,
    val card: Long,
    val text: Long,
    val muted: Long,
    val faint: Long,
    val divider: Long,
    val accent: Long,
    val accentAlt: Long,
    val onAccent: Long,
    val destructive: Long,
    val overlay: Long,
) {
    fun value(name: String): Long? = when (name.removePrefix("@")) {
        "background" -> background
        "surface" -> surface
        "raised" -> raised
        "card" -> card
        "text" -> text
        "muted" -> muted
        "faint" -> faint
        "divider" -> divider
        "accent" -> accent
        "accentAlt" -> accentAlt
        "onAccent" -> onAccent
        "destructive" -> destructive
        "overlay" -> overlay
        else -> null
    }
}

data class ThemeTypography(
    /** sans | serif | mono | rounded | condensed */
    val family: String = "sans",
    val titleWeight: Int = 700,
    val scale: Float = 1f,
    val letterSpacing: Float = 0f,
    val uppercaseHeaders: Boolean = false,
)

data class ThemeShape(
    val card: Int = 12,
    val button: Int = 12,
    val field: Int = 9,
    val border: Int = 0,
    val elevation: Int = 0,
    val pill: Boolean = false,
)

data class ThemeDensity(
    val scale: Float = 1f,
    val gap: Int = 10,
    val page: Int = 16,
    val row: Int = 12,
    val icons: Float = 1f,
)

data class ThemeModeSpec(
    val palette: ThemePalette,
    val typography: ThemeTypography = ThemeTypography(),
    val shape: ThemeShape = ThemeShape(),
    val density: ThemeDensity = ThemeDensity(),
    /** Layout name -> XML. Names are stable so a theme can omit a layout and inherit the default. */
    val layouts: Map<String, String> = emptyMap(),
) {
    fun color(value: String, fallback: Long = palette.text): Long {
        if (value.startsWith("#")) return parseHex(value) ?: fallback
        return palette.value(value) ?: fallback
    }

    fun number(value: String?, fallback: Int): Int {
        if (value == null) return fallback
        if (value.startsWith("@")) {
            return when (value.removePrefix("@")) {
                "cardRadius" -> shape.card
                "buttonRadius" -> shape.button
                "fieldRadius" -> shape.field
                "border" -> shape.border
                "elevation" -> shape.elevation
                "gap" -> density.gap
                "page" -> density.page
                "row" -> density.row
                else -> fallback
            }
        }
        return value.toFloatOrNull()?.toInt() ?: fallback
    }

    fun size(value: String?, fallback: Float): Float {
        if (value == null) return fallback
        if (value.startsWith("@")) {
            return when (value.removePrefix("@")) {
                "gap" -> density.gap.toFloat()
                "page" -> density.page.toFloat()
                "row" -> density.row.toFloat()
                else -> fallback
            }
        }
        return value.toFloatOrNull()?.let { it * density.scale } ?: fallback
    }
}

data class PrismThemeSpec(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val builtIn: Boolean,
    val light: ThemeModeSpec,
    val dark: ThemeModeSpec,
    /**
     * Whether the desktop pages draw the user's wallpaper behind them.
     *
     * True unless a theme says otherwise: a launcher that replaces a photograph with a flat
     * colour by default is the surprising choice. A theme built for a strong background of its
     * own can turn it off, and the desktop pages then use that theme's background instead.
     */
    val showWallpaper: Boolean = true,
    val updatedAt: Long = 0L,
    /**
     * What a themed edge is made of: [EDGE_GLOW] for a soft halo, [EDGE_ELECTRIC] for a live
     * current, [EDGE_NONE] for a hairline that never lights up.
     *
     * A trait of the THEME rather than of every renderer, because "does this theme glow?" was
     * being inferred from `shape.elevation > 0` in three different places. Spark needs the
     * opposite of a glow at the same elevation, so the question needed a real answer.
     */
    val edgeEffect: String = EDGE_GLOW,
    /**
     * A procedural ground the theme paints itself, when the wallpaper is not showing.
     *
     * [BACKDROP_NEBULA] is Fusion's deep-space wash. A renderer that does not know a value must
     * fall back to the palette's background rather than fail, which is why this is a string and
     * not a bitmap.
     */
    val backdrop: String = BACKDROP_NONE,
)

/** A soft halo: the glass and neon themes. */
const val EDGE_GLOW = "glow"
/** A live current along the edge, with arcs that break away from it. */
const val EDGE_ELECTRIC = "electric"
/** A plain rule that does not light up. */
const val EDGE_NONE = "none"
/** The palette's background colour and nothing else. */
const val BACKDROP_NONE = "none"
/** A deep-space wash of violet, cyan and stars, drawn procedurally. */
const val BACKDROP_NEBULA = "nebula"

private val EDGE_EFFECTS = setOf(EDGE_GLOW, EDGE_ELECTRIC, EDGE_NONE)
private val BACKDROPS = setOf(BACKDROP_NONE, BACKDROP_NEBULA)

/** The ten themes that ship with Prism. */
object BuiltInThemes {

    fun all(): List<PrismThemeSpec> = listOf(
        xos(), metro(), symbie(), aurora(), terminal(), paper(), neongrid(), clay(), fusion(), spark(),
    )

    /**
     * The built-ins as a desktop application shows them.
     *
     * ONE BUILT-IN IS INTERPRETED, NOT SHARED. xOS is Prism's name for its macOS-flavoured         * interface, and on a desktop that name has to mean the thing it says: pages in a taskbar
         * along the bottom, a blue selection,
     * a blue selection, 10pt grouped cards and control-sized corners. The Android build keeps the
     * iOS-derived spec it has always used -- the two platforms genuinely disagree about what the
     * same theme should look like, which is why this is a separate list rather than a palette
     * change everyone gets.
     *
     * Every other built-in is passed through untouched, and a user theme is never overridden.
     */
    fun desktop(): List<PrismThemeSpec> = all().map { spec ->
        if (spec.id == "xos") xosDesktop() else spec
    }

    /**
     * xOS as macOS.
     *
     * The values are Apple's: systemBlue `#007AFF` / `#0A84FF`, the `#1E1E1E` window against a
     * `#2C2C2E` elevated surface, `#6E6E73` / `#98989D` secondary labels, 10pt card corners and
     * 6pt controls. Sentence-case headers and tighter row spacing are part of the look as much as
     * the colours are -- macOS does not shout its section titles.
     *
     * The font is the system font rather than SF Pro: SF is not redistributable, and shipping a
     * near-copy would be both a licence problem and visibly wrong. Everything the user can see
     * that Prism controls -- spacing, colour, corners, hierarchy -- is the macOS value.
     */
    fun xosDesktop(): PrismThemeSpec = theme(
        "xos", "xOS",
        "The macOS interface: a taskbar dock, blue selection and measured, desktop-scale type.",
        Flavor.CARDS,
        ThemeModeSpec(
            palette = palette(
                0xFFF5F5F7L, surface = 0xFFECECECL, raised = 0x1F000000L,
                card = 0xFFFFFFFFL, text = 0xFF1D1D1FL, muted = 0xFF6E6E73L,
                faint = 0xFF8E8E93L, divider = 0xFFD6D6D6L, accent = 0xFF007AFFL,
                accentAlt = 0xFF5856D6L, onAccent = 0xFFFFFFFFL,
                destructive = 0xFFFF3B30L, overlay = 0x66000000L,
            ),
            typography = ThemeTypography(family = "sans", titleWeight = 600, uppercaseHeaders = false),
            shape = ThemeShape(card = 10, button = 6, field = 6, border = 1, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 8, page = 20, row = 8),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF1E1E1EL, surface = 0xFF2A2A2CL, raised = 0x26FFFFFFL,
                card = 0xFF2C2C2EL, text = 0xFFF5F5F7L, muted = 0xFF98989DL,
                faint = 0xFF6E6E73L, divider = 0xFF3A3A3CL, accent = 0xFF0A84FFL,
                accentAlt = 0xFF5E5CE6L, onAccent = 0xFFFFFFFFL,
                destructive = 0xFFFF453AL, overlay = 0x99000000L,
            ),
            typography = ThemeTypography(family = "sans", titleWeight = 600, uppercaseHeaders = false),
            shape = ThemeShape(card = 10, button = 6, field = 6, border = 1, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 8, page = 20, row = 8),
        ),
    )

    private fun theme(
        id: String,
        name: String,
        description: String,
        flavor: Flavor,
        light: ThemeModeSpec,
        dark: ThemeModeSpec,
        edgeEffect: String = EDGE_GLOW,
        backdrop: String = BACKDROP_NONE,
    ): PrismThemeSpec {
        val lightLayouts = ThemeLayouts.forTheme(light, flavor, light = true)
        val darkLayouts = ThemeLayouts.forTheme(dark, flavor, light = false)
        return PrismThemeSpec(
            id = id,
            name = name,
            description = description,
            author = "Prism",
            builtIn = true,
            light = light.copy(layouts = lightLayouts),
            dark = dark.copy(layouts = darkLayouts),
            // A theme whose preview is a flat canvas gets a flat canvas in the launcher too.
            // The glass themes keep the wallpaper: their translucency is the point.
            // GLASS THEMES KEEP THE WALLPAPER TOO. Fusion's nebula and Spark's current used to
            // turn it off so their own ground owned the screen, which made them the two themes
            // whose desktop page had no photograph on it at all. Their effects are painted OVER
            // the wallpaper now; what a theme may remove is the picture, not the user's choice
            // of one.
            showWallpaper = id !in setOf("metro", "terminal", "paper", "neongrid"),
            edgeEffect = edgeEffect,
            backdrop = backdrop,
        )
    }

    private fun palette(
        background: Long,
        surface: Long,
        raised: Long,
        card: Long,
        text: Long,
        muted: Long,
        faint: Long,
        divider: Long,
        accent: Long,
        accentAlt: Long,
        onAccent: Long = 0xFFFFFFFFL,
        destructive: Long,
        overlay: Long,
    ) = ThemePalette(
        background, surface, raised, card, text, muted, faint, divider,
        accent, accentAlt, onAccent, destructive, overlay,
    )

    /**
     * The interface Prism had before themes existed, as a theme.
     *
     * EVERY value here is the one the old hardcoded design system used -- the two-tier grouped
     * background, the 12 pt card and button radius, the 9 pt field, 16/14 card padding, uppercase
     * section headers, and the translucent grey fills whose alpha is part of the look. It is not
     * an interpretation of that style; selecting xOS has to leave every screen looking exactly as
     * it did before a theme could be selected at all.
     */
    private fun xos(): PrismThemeSpec = theme(
        "xos", "xOS",
        "The original Prism interface: grouped cards, a calm blue accent and generous spacing.",
        Flavor.CARDS,
        ThemeModeSpec(
            palette = palette(
                background = 0xFFF2F2F7L, surface = 0xFFFFFFFFL, raised = 0x1F767680L,
                card = 0xFFFFFFFFL, text = 0xFF000000L, muted = 0x993C3C43L,
                faint = 0x4D3C3C43L, divider = 0x243C3C43L, accent = 0xFF007AFFL,
                accentAlt = 0xFF5856D6L, onAccent = 0xFFFFFFFFL,
                destructive = 0xFFFF3B30L, overlay = 0x66000000L,
            ),
            typography = ThemeTypography(family = "sans", uppercaseHeaders = true),
            shape = ThemeShape(card = 12, button = 12, field = 9, border = 0, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 10, page = 16, row = 14),
        ),
        ThemeModeSpec(
            palette = palette(
                background = 0xFF000000L, surface = 0xFF1C1C1EL, raised = 0x3D767680L,
                card = 0xFF1C1C1EL, text = 0xFFFFFFFFL, muted = 0x99EBEBF5L,
                faint = 0x4DEBEBF5L, divider = 0x40545458L, accent = 0xFF0A84FFL,
                accentAlt = 0xFF5E5CE6L, onAccent = 0xFFFFFFFFL,
                destructive = 0xFFFF453AL, overlay = 0x99000000L,
            ),
            typography = ThemeTypography(family = "sans", uppercaseHeaders = true),
            shape = ThemeShape(card = 12, button = 12, field = 9, border = 0, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 10, page = 16, row = 14),
        ),
    )

    private fun metro(): PrismThemeSpec = theme(
        "metro", "Metro UI",
        "Flat tiles, sharp corners, uppercase headers and a live-tile rhythm.",
        Flavor.TILES,
        ThemeModeSpec(
            palette = palette(
                0xFFF3F3F3, 0xFFFFFFFF, 0xFFE9E9E9, 0xFFFFFFFF, 0xFF111111, 0xFF5D5D5D,
                0xFF8A8A8A, 0xFFD6D6D6, 0xFF0078D7, 0xFFE3008C,
                destructive = 0xFFE81123, overlay = 0x66000000,
            ),
            typography = ThemeTypography(family = "condensed", titleWeight = 600, uppercaseHeaders = true),
            shape = ThemeShape(card = 0, button = 0, field = 0, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.94f, gap = 8, page = 12, row = 10, icons = 0.94f),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF1F1F1F, 0xFF2B2B2B, 0xFF383838, 0xFF2B2B2B, 0xFFFFFFFF, 0xFFC7C7C7,
                0xFF9B9B9B, 0xFF454545, 0xFF4CC2FF, 0xFFFF4FA3,
                destructive = 0xFFFF6B6B, overlay = 0x99000000,
            ),
            typography = ThemeTypography(family = "condensed", titleWeight = 600, uppercaseHeaders = true),
            shape = ThemeShape(card = 0, button = 0, field = 0, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.94f, gap = 8, page = 12, row = 10, icons = 0.94f),
        ),
    )

    private fun symbie(): PrismThemeSpec = theme(
        "symbie", "Symbie",
        "A sleek futuristic shell: deep teal glass, glowing edges and pill controls.",
        Flavor.GLASS,
        ThemeModeSpec(
            palette = palette(
                0xFFE8FBFF, 0xFFF7FFFF, 0xFFDDF6FA, 0xFFF4FFFF, 0xFF07313A, 0xFF3D6E75,
                0xFF648E94, 0xFFBFE8ED, 0xFF007C74, 0xFF00A8C5,
                onAccent = 0xFFFFFFFF, destructive = 0xFFD64545, overlay = 0x66003333,
            ),
            typography = ThemeTypography(family = "rounded", titleWeight = 700, letterSpacing = 0.01f),
            shape = ThemeShape(card = 22, button = 28, field = 18, border = 1, elevation = 3, pill = true),
            density = ThemeDensity(scale = 1.02f, gap = 12, page = 16, row = 13),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF041016, 0xFF0A1D26, 0xFF0F2A35, 0xFF0A1D26, 0xFFD9F7FF, 0xFF8DB8C2,
                0xFF5C8C98, 0xFF17414C, 0xFF39F5D0, 0xFF35C8FF,
                onAccent = 0xFF002B28, destructive = 0xFFFF7A7A, overlay = 0xAA00141B,
            ),
            typography = ThemeTypography(family = "rounded", titleWeight = 700, letterSpacing = 0.01f),
            shape = ThemeShape(card = 22, button = 28, field = 18, border = 1, elevation = 6, pill = true),
            density = ThemeDensity(scale = 1.02f, gap = 12, page = 16, row = 13),
        ),
    )

    private fun aurora(): PrismThemeSpec = theme(
        "aurora", "Aurora",
        "Luminous violet-to-teal glass with deep, cinematic contrast.",
        Flavor.GLASS,
        ThemeModeSpec(
            palette = palette(
                0xFFF7F4FF, 0xFFFFFFFF, 0xFFF0EAFF, 0xFFFFFFFF, 0xFF241B3A, 0xFF6D6285,
                0xFF958AA8, 0xFFE3DBF5, 0xFF7C4DFF, 0xFF00B8A9,
                destructive = 0xFFE5484D, overlay = 0x660E0A20,
            ),
            typography = ThemeTypography(family = "sans", titleWeight = 700, letterSpacing = 0.01f),
            shape = ThemeShape(card = 20, button = 20, field = 14, border = 0, elevation = 2),
            density = ThemeDensity(scale = 1f, gap = 12, page = 18, row = 13),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF0B0A14, 0xFF151226, 0xFF1D1933, 0xFF151226, 0xFFF1ECFF, 0xFFB3A8D6,
                0xFF8A7FB0, 0xFF302850, 0xFFB69CFF, 0xFF4DE8D1,
                onAccent = 0xFF160F2E, destructive = 0xFFFF7A9A, overlay = 0xAA060510,
            ),
            typography = ThemeTypography(family = "sans", titleWeight = 700, letterSpacing = 0.01f),
            shape = ThemeShape(card = 20, button = 20, field = 14, border = 0, elevation = 8),
            density = ThemeDensity(scale = 1f, gap = 12, page = 18, row = 13),
        ),
    )

    private fun terminal(): PrismThemeSpec = theme(
        "terminal", "Terminal",
        "Monospace rows, hard edges and a phosphor-green command line.",
        Flavor.TERMINAL,
        ThemeModeSpec(
            palette = palette(
                0xFFF4F8F3, 0xFFFFFFFF, 0xFFEAF2E8, 0xFFFFFFFF, 0xFF102414, 0xFF4A6650,
                0xFF7C9481, 0xFFD5E3D3, 0xFF008A3A, 0xFF006B8F,
                onAccent = 0xFFFFFFFF, destructive = 0xFFB3261E, overlay = 0x66001808,
            ),
            typography = ThemeTypography(family = "mono", titleWeight = 700, uppercaseHeaders = true, letterSpacing = 0.02f),
            shape = ThemeShape(card = 2, button = 2, field = 2, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.96f, gap = 7, page = 14, row = 9),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF050805, 0xFF0A120A, 0xFF101B10, 0xFF0A120A, 0xFFC9FFD2, 0xFF79A880,
                0xFF507A57, 0xFF1B2C1B, 0xFF00FF66, 0xFFFFD166,
                onAccent = 0xFF001A08, destructive = 0xFFFF6B6B, overlay = 0xBB000000,
            ),
            typography = ThemeTypography(family = "mono", titleWeight = 700, uppercaseHeaders = true, letterSpacing = 0.02f),
            shape = ThemeShape(card = 2, button = 2, field = 2, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.96f, gap = 7, page = 14, row = 9),
        ),
    )

    private fun paper(): PrismThemeSpec = theme(
        "paper", "Paper",
        "Warm stock, ink typography and quiet rules. A theme that gets out of the way.",
        Flavor.PAPER,
        ThemeModeSpec(
            palette = palette(
                0xFFF7F1E3, 0xFFFFFDF7, 0xFFF0E8D6, 0xFFFFFDF7, 0xFF2B241B, 0xFF6F6250,
                0xFF9A8B74, 0xFFE3D8C2, 0xFFA0522D, 0xFF557A5E,
                destructive = 0xFF9E2A2B, overlay = 0x662B241B,
            ),
            typography = ThemeTypography(family = "serif", titleWeight = 600, letterSpacing = 0f),
            shape = ThemeShape(card = 6, button = 6, field = 4, border = 1, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 11, page = 18, row = 13),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF1B1813, 0xFF242019, 0xFF2E2920, 0xFF242019, 0xFFEADFC8, 0xFFB3A58C,
                0xFF8A7B64, 0xFF3D3629, 0xFFD9A066, 0xFF8FB59A,
                destructive = 0xFFFF8A80, overlay = 0xAA0B0906,
            ),
            typography = ThemeTypography(family = "serif", titleWeight = 600, letterSpacing = 0f),
            shape = ThemeShape(card = 6, button = 6, field = 4, border = 1, elevation = 0),
            density = ThemeDensity(scale = 1f, gap = 11, page = 18, row = 13),
        ),
    )

    private fun neongrid(): PrismThemeSpec = theme(
        "neongrid", "Neon Grid",
        "Black glass, electric cyan and magenta, with a faint grid under everything.",
        Flavor.GRID,
        ThemeModeSpec(
            palette = palette(
                0xFFF5F7FF, 0xFFFFFFFF, 0xFFEDF0FF, 0xFFFFFFFF, 0xFF12122A, 0xFF5D5D85,
                0xFF8989A8, 0xFFD7DBF5, 0xFFE00086, 0xFF0096C7,
                destructive = 0xFFD00036, overlay = 0x66000022,
            ),
            typography = ThemeTypography(family = "sans", titleWeight = 700, letterSpacing = 0.02f, uppercaseHeaders = true),
            shape = ThemeShape(card = 4, button = 4, field = 4, border = 1, elevation = 2),
            density = ThemeDensity(scale = 0.98f, gap = 9, page = 14, row = 10),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF05050A, 0xFF0C0C18, 0xFF141428, 0xFF0C0C18, 0xFFE8F9FF, 0xFF8C8CB3,
                0xFF66668A, 0xFF25254A, 0xFF00F0FF, 0xFFFF2BD6,
                onAccent = 0xFF001418, destructive = 0xFFFF4D6D, overlay = 0xBB000000,
            ),
            typography = ThemeTypography(family = "mono", titleWeight = 700, letterSpacing = 0.02f, uppercaseHeaders = true),
            shape = ThemeShape(card = 4, button = 4, field = 4, border = 1, elevation = 6),
            density = ThemeDensity(scale = 0.98f, gap = 9, page = 14, row = 10),
        ),
    )

    private fun clay(): PrismThemeSpec = theme(
        "clay", "Clay",
        "Soft pastel clay, deep rounding and gentle shadows. Tactile rather than loud.",
        Flavor.CARDS,
        ThemeModeSpec(
            palette = palette(
                0xFFFAEEE7, 0xFFFFF9F5, 0xFFF5E3DA, 0xFFFFF9F5, 0xFF33272A, 0xFF7C6469,
                0xFFA68C91, 0xFFEAD7CE, 0xFFD96C8A, 0xFF6B8E7A,
                destructive = 0xFFB23A48, overlay = 0x662B1F22,
            ),
            typography = ThemeTypography(family = "rounded", titleWeight = 700, letterSpacing = 0f),
            shape = ThemeShape(card = 24, button = 24, field = 18, border = 0, elevation = 2),
            density = ThemeDensity(scale = 1.04f, gap = 12, page = 18, row = 14, icons = 1.04f),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF241C1C, 0xFF332727, 0xFF403131, 0xFF332727, 0xFFFAEEE7, 0xFFC9B4AE,
                0xFF9C8781, 0xFF4A3A3A, 0xFFFFA8BA, 0xFF9BC3A8,
                onAccent = 0xFF3A1E24, destructive = 0xFFFF8A80, overlay = 0xAA140D0F,
            ),
            typography = ThemeTypography(family = "rounded", titleWeight = 700, letterSpacing = 0f),
            shape = ThemeShape(card = 24, button = 24, field = 18, border = 0, elevation = 8),
            density = ThemeDensity(scale = 1.04f, gap = 12, page = 18, row = 14, icons = 1.04f),
        ),
    )

    /**
     * Fusion: the nebula interface.
     *
     * ## What the reference is made of
     *
     * A deep-space ground, floating glass nodes with no card chrome, a luminous electric cyan that
     * reads as light rather than paint, warm amber as the counter-accent, and violet and emerald as
     * tertiary tints. Connector filaments and a radial core are part of the look, which is why this
     * theme carries [BACKDROP_NEBULA] and glows at high elevation: the depth is painted, not
     * shaded, and a flat surface would lose the whole point.
     *
     * It is NOT the reference image as a wallpaper. The wash is procedural, so it scales to any
     * screen, cannot be missing from a build, and does not put a photograph of somebody else's
     * phone behind the user's app icons.
     */
    private fun fusion(): PrismThemeSpec = theme(
        "fusion", "Fusion",
        "A nebula interface: deep space, floating glass and electric cyan light with amber warmth.",
        Flavor.GLASS,
        ThemeModeSpec(
            palette = palette(
                0xFFF2F5FF, 0xFFFFFFFF, 0xFFE4EAFF, 0xFFFFFFFF, 0xFF10152B, 0xFF5A6488,
                0xFF8B92B4, 0xFFD7DEF5, 0xFF0087C4, 0xFFB26A00,
                onAccent = 0xFFFFFFFF, destructive = 0xFFD23B63, overlay = 0x66080C1A,
            ),
            typography = ThemeTypography(
                family = "sans", titleWeight = 700, letterSpacing = 0.02f, uppercaseHeaders = true,
            ),
            shape = ThemeShape(card = 18, button = 18, field = 14, border = 0, elevation = 6),
            density = ThemeDensity(scale = 1f, gap = 12, page = 18, row = 13),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF05060F, 0xFF0B1024, 0xFF12203F, 0xFF0A1024, 0xFFEAF6FF, 0xFF93A7CC,
                0xFF6C7FA6, 0xFF1E2C52, 0xFF3DE1FF, 0xFFF0A63C,
                onAccent = 0xFF031018, destructive = 0xFFFF5C8A, overlay = 0xB3050712,
            ),
            typography = ThemeTypography(
                family = "sans", titleWeight = 700, letterSpacing = 0.02f, uppercaseHeaders = true,
            ),
            shape = ThemeShape(card = 18, button = 18, field = 14, border = 0, elevation = 10),
            density = ThemeDensity(scale = 1f, gap = 12, page = 18, row = 13),
        ),
        edgeEffect = EDGE_GLOW,
        backdrop = BACKDROP_NEBULA,
    )

    /**
     * Spark: electricity instead of glow.
     *
     * ## Why the border is the feature
     *
     * Every other dark theme in Prism signals depth with a soft halo. Spark signals it with
     * CURRENT: a bright core line along each edge, short arcs leaving it, and a discharge that
     * travels the perimeter. That is [EDGE_ELECTRIC], and it is a trait of the theme so every
     * renderer asks the same question instead of each one deciding what "glow" means.
     *
     * The desktop pages go further and arc between app icons, weighted by launch counts -- see
     * `DesktopSparks`. The palette is the cold half of the reference: storm blue-black, white-blue
     * arcs and a warning amber for anything that must not be mistaken for calm.
     */
    private fun spark(): PrismThemeSpec = theme(
        "spark", "Spark",
        "Live current on every edge: storm-dark glass, white-blue arcs and a warning amber.",
        Flavor.GLASS,
        ThemeModeSpec(
            palette = palette(
                0xFFF2FBFF, 0xFFFFFFFF, 0xFFE1F6FF, 0xFFFFFFFF, 0xFF061A24, 0xFF4A6E7C,
                0xFF7A9AA6, 0xFFCBE8F3, 0xFF007FA8, 0xFFB26A00,
                onAccent = 0xFFFFFFFF, destructive = 0xFFD02A44, overlay = 0x6600131F,
            ),
            typography = ThemeTypography(
                family = "sans", titleWeight = 700, letterSpacing = 0.03f, uppercaseHeaders = true,
            ),
            shape = ThemeShape(card = 10, button = 10, field = 8, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.98f, gap = 9, page = 16, row = 11),
        ),
        ThemeModeSpec(
            palette = palette(
                0xFF03060B, 0xFF071018, 0xFF0C1B26, 0xFF071018, 0xFFE6FBFF, 0xFF87A9B8,
                0xFF5B7D8C, 0xFF123141, 0xFF4DF0FF, 0xFFFFC857,
                onAccent = 0xFF001318, destructive = 0xFFFF5D6C, overlay = 0xB3000308,
            ),
            typography = ThemeTypography(
                family = "sans", titleWeight = 700, letterSpacing = 0.03f, uppercaseHeaders = true,
            ),
            shape = ThemeShape(card = 10, button = 10, field = 8, border = 1, elevation = 0),
            density = ThemeDensity(scale = 0.98f, gap = 9, page = 16, row = 11),
        ),
        edgeEffect = EDGE_ELECTRIC,
    )
}

internal enum class Flavor { CARDS, TILES, GLASS, TERMINAL, PAPER, GRID }

/** Generates the layout recipes a built-in theme ships with. */
internal object ThemeLayouts {

    /**
     * Every screen the creator can restyle.
     *
     * ONE ENTRY PER SCREEN TYPE, not per activity. The creator edits layouts that the app then
     * renders; two activities that look the same are one layout, and adding a second entry would
     * just mean styling the same screen twice. What was here covered only the first few screens --
     * the desktop, the drawer, messages, the browser, files -- so the rest of Prism could not be
     * themed at all and did not appear in the creator.
     */
    val names = listOf(
        "settings.root",
        "launcher.desktop",
        "launcher.drawer",
        "messages.list",
        "browser.chrome",
        "files.grid",
        "dialogs.alert",
        "widgets.card",
        "calls.dialer",
        "calls.inCall",
        "maps.page",
        "wallet.page",
        "models.page",
        "editor.page",
        "lock.screen",
        "themes.gallery",
        "virtualization.page",
        "agentic.page",
        "nora.chat",
        "aether.page",
        "health.page",
        "speech.page",
        // The Writer is an IME rather than an activity, so it never appeared here and could not
        // be themed at all. These four are the screens added with the Photos and PhotoPix work;
        // a layout that is not in this list cannot be edited and is not shown in the creator.
        "writer.page",
        "photos.grid",
        "photo.viewer",
        "photopix.editor",
    )

    fun forTheme(mode: ThemeModeSpec, flavor: Flavor, light: Boolean): Map<String, String> {
        val radius = if (mode.shape.pill) "18" else mode.shape.card.toString()
        val settingsBody = when (flavor) {
            Flavor.TILES -> """
                <grid columns="2" gap="@gap">
                  <card radius="@cardRadius" background="@card" border="@border" padding="14">
                    <column gap="6"><text text="THEMES" size="11" weight="bold" color="@accent" /><text text="Aurora" size="18" weight="bold" color="@text" /><text text="Appearance" size="12" color="@muted" /></column>
                  </card>
                  <card radius="@cardRadius" background="@card" border="@border" padding="14">
                    <column gap="6"><text text="DISPLAY" size="11" weight="bold" color="@accent" /><text text="Brightness" size="18" weight="bold" color="@text" /><text text="Adaptive" size="12" color="@muted" /></column>
                  </card>
                  <card radius="@cardRadius" background="@card" border="@border" padding="14">
                    <column gap="6"><text text="SOUND" size="11" weight="bold" color="@accent" /><text text="Media volume" size="18" weight="bold" color="@text" /><text text="70 percent" size="12" color="@muted" /></column>
                  </card>
                  <card radius="@cardRadius" background="@card" border="@border" padding="14">
                    <column gap="6"><text text="PRIVACY" size="11" weight="bold" color="@accent" /><text text="Blocklist" size="18" weight="bold" color="@text" /><text text="1,204 rules" size="12" color="@muted" /></column>
                  </card>
                </grid>
            """.trimIndent()
            Flavor.TERMINAL -> """
                <column gap="0">
                  <row gap="10" padding="8"><text text="> theme" size="13" color="@accent" /><text text="xOS" size="13" color="@text" /></row>
                  <divider color="@divider" />
                  <row gap="10" padding="8"><text text="> display" size="13" color="@accent" /><text text="auto / 60Hz" size="13" color="@text" /></row>
                  <divider color="@divider" />
                  <row gap="10" padding="8"><text text="> privacy" size="13" color="@accent" /><text text="blocklist: 1204" size="13" color="@text" /></row>
                  <divider color="@divider" />
                  <text text="ready." size="12" color="@muted" padding="8" />
                </column>
            """.trimIndent()
            else -> """
                <card radius="@cardRadius" background="@card" border="@border" padding="14">
                  <column gap="9">
                    <row gap="10"><icon name="palette" size="26" color="@accent" /><column><text text="Themes" size="15" weight="semibold" color="@text" /><text text="xOS · built in" size="11" color="@muted" /></column><spacer weight="1" /><text text="Open" size="12" color="@accent" /></row>
                    <divider color="@divider" />
                    <row gap="10"><icon name="contrast" size="26" color="@accent" /><column><text text="Display" size="15" weight="semibold" color="@text" /><text text="Brightness, refresh rate" size="11" color="@muted" /></column></row>
                    <divider color="@divider" />
                    <row gap="10"><icon name="shield" size="26" color="@accent" /><column><text text="Privacy" size="15" weight="semibold" color="@text" /><text text="Blocklist and DNS" size="11" color="@muted" /></column></row>
                  </column>
                </card>
            """.trimIndent()
        }

        val settings = """
            <layout name="settings.root" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Prism Settings" size="28" weight="bold" color="@text" />
                <text text="The same settings as the phone, from the same store" size="12" color="@muted" />
                $settingsBody
                <text text="SYSTEM" size="11" weight="bold" color="@faint" padding="8" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14">
                  <column gap="8">
                    <row gap="10"><icon name="storage" size="24" color="@accent" /><text text="Storage" size="14" color="@text" /><spacer weight="1" /><text text="42.6 GB free" size="11" color="@muted" /></row>
                    <divider color="@divider" />
                    <row gap="10"><icon name="info" size="24" color="@accent" /><text text="About Prism" size="14" color="@text" /><spacer weight="1" /><text text="1.0" size="11" color="@muted" /></row>
                  </column>
                </card>
              </column>
            </layout>
        """.trimIndent()

        val launcher = if (flavor == Flavor.TILES) """
            <layout name="launcher.desktop" background="@background" padding="14">
              <column gap="@gap">
                <row><text text="Prism" size="24" weight="bold" color="@text" /><spacer weight="1" /><text text="12:40" size="12" color="@muted" /></row>
                <grid columns="3" gap="10">
                  <card radius="0" background="@accent" padding="16"><column gap="4"><text text="MESSAGES" size="11" weight="bold" color="@onAccent" /><text text="3 new" size="22" weight="bold" color="@onAccent" /></column></card>
                  <card radius="0" background="@accentAlt" padding="16"><column gap="4"><text text="WEATHER" size="11" weight="bold" color="@onAccent" /><text text="21°" size="26" weight="bold" color="@onAccent" /></column></card>
                  <card radius="0" background="@raised" padding="16"><column gap="4"><text text="BATTERY" size="11" weight="bold" color="@muted" /><text text="82%" size="22" weight="bold" color="@text" /></column></card>
                  <card radius="0" background="@raised" padding="16"><column gap="4"><text text="FILES" size="11" weight="bold" color="@muted" /><text text="42.6 GB" size="20" weight="bold" color="@text" /></column></card>
                </grid>
              </column>
            </layout>
        """.trimIndent() else """
            <layout name="launcher.desktop" background="@background" padding="14">
              <column gap="16">
                <row><text text="Prism" size="24" weight="bold" color="@text" /><spacer weight="1" /><text text="12:40" size="12" color="@muted" /></row>
                <grid columns="4" gap="14">
                  <app label="Phone" color="@accent" />
                  <app label="Messages" color="@accentAlt" />
                  <app label="Browser" color="@accent" />
                  <app label="Files" color="@muted" />
                  <app label="Settings" color="@faint" />
                  <app label="Themes" color="@accentAlt" />
                  <app label="Camera" color="@muted" />
                  <app label="Maps" color="@accent" />
                </grid>
                <spacer weight="1" />
                <card radius="@cardRadius" background="@card" border="@border" padding="10">
                  <row gap="14"><app label="" color="@accent" /><app label="" color="@accentAlt" /><app label="" color="@muted" /><app label="" color="@accent" /></row>
                </card>
              </column>
            </layout>
        """.trimIndent()

        val drawer = """
            <layout name="launcher.drawer" background="@background" padding="@page">
              <column gap="@gap">
                <field hint="Search apps" />
                <row gap="10"><avatar text="A" color="@accent" /><text text="Apps" size="16" weight="bold" color="@text" /><spacer weight="1" /><text text="A–Z" size="11" color="@faint" /></row>
                <grid columns="4" gap="14">
                  <app label="Browser" color="@accent" />
                  <app label="Files" color="@muted" />
                  <app label="Messages" color="@accentAlt" />
                  <app label="Themes" color="@accent" />
                  <app label="Camera" color="@faint" />
                  <app label="Maps" color="@accent" />
                </grid>
              </column>
            </layout>
        """.trimIndent()

        val messages = """
            <layout name="messages.list" background="@background" padding="@page">
              <column gap="8">
                <text text="Messages" size="28" weight="bold" color="@text" />
                <field hint="Search" />
                <row gap="10" padding="8"><avatar text="S" color="@accent" /><column><text text="Sam" size="15" weight="semibold" color="@text" /><text text="How can I help you?" size="12" color="@muted" /></column><spacer weight="1" /><text text="now" size="10" color="@faint" /></row>
                <divider color="@divider" />
                <row gap="10" padding="8"><avatar text="N" color="@accentAlt" /><column><text text="Nora" size="15" weight="semibold" color="@text" /><text text="red" size="12" color="@muted" /></column><spacer weight="1" /><text text="8:56" size="10" color="@faint" /></row>
                <row gap="10" padding="8"><avatar text="A" color="@muted" /><column><text text="Aether" size="15" weight="semibold" color="@text" /><text text="Opening my training page…" size="12" color="@muted" /></column></row>
              </column>
            </layout>
        """.trimIndent()

        val browser = """
            <layout name="browser.chrome" background="@background" padding="10">
              <column gap="8">
                <row gap="8"><field hint="Search or type a URL" /><button text="Go" background="@accent" color="@onAccent" /></row>
                <card radius="@cardRadius" background="@card" padding="14"><column gap="6"><text text="prism://home" size="16" weight="bold" color="@text" /><text text="Fast, private, yours." size="12" color="@muted" /></column></card>
              </column>
            </layout>
        """.trimIndent()

        val files = """
            <layout name="files.grid" background="@background" padding="@page">
              <column gap="10">
                <row><text text="Files" size="24" weight="bold" color="@text" /><spacer weight="1" /><text text="Internal storage" size="11" color="@muted" /></row>
                <grid columns="2" gap="10">
                  <card radius="@cardRadius" background="@card" padding="14"><row gap="10"><icon name="folder" size="28" color="@accent" /><text text="Downloads" size="14" color="@text" /></row></card>
                  <card radius="@cardRadius" background="@card" padding="14"><row gap="10"><icon name="folder" size="28" color="@accent" /><text text="Pictures" size="14" color="@text" /></row></card>
                  <card radius="@cardRadius" background="@card" padding="14"><row gap="10"><icon name="folder" size="28" color="@accent" /><text text="Music" size="14" color="@text" /></row></card>
                  <card radius="@cardRadius" background="@card" padding="14"><row gap="10"><icon name="folder" size="28" color="@accent" /><text text="Documents" size="14" color="@text" /></row></card>
                </grid>
              </column>
            </layout>
        """.trimIndent()

        val dialogs = """
            <layout name="dialogs.alert" background="@overlay" padding="20">
              <card radius="@cardRadius" background="@card" border="@border" padding="18">
                <column gap="10">
                  <text text="Apply theme?" size="17" weight="bold" color="@text" />
                  <text text="Every screen updates immediately. You can switch back at any time." size="13" color="@muted" />
                  <row gap="10"><spacer weight="1" /><text text="Cancel" size="14" color="@accent" /><text text="Apply" size="14" weight="bold" color="@accent" /></row>
                </column>
              </card>
            </layout>
        """.trimIndent()

        val widgets = """
            <layout name="widgets.card" background="@background" padding="12">
              <card radius="@cardRadius" background="@card" border="@border" padding="14">
                <column gap="6"><text text="Now" size="11" weight="bold" color="@muted" /><text text="A calm, focused Prism." size="16" weight="bold" color="@text" /><text text="Themes · 8 built in" size="12" color="@accent" /></column>
              </card>
            </layout>
        """.trimIndent()

        // The screens below were the rest of Prism: they had no layout in the creator at all, which
        // meant their look could not be changed and they were absent from the list. Each is kept
        // short on purpose -- the creator's preview shows the SHAPE of the screen (its title, its
        // list, its card), and a faithful mock of every row would be a second implementation of the
        // screen that drifts from the real one.
        val dialer = """
            <layout name="calls.dialer" background="@background" padding="@page">
              <column gap="@gap">
                <text text="1 234 567 8901" size="30" weight="bold" color="@text" />
                <text text="Add number" size="12" color="@accent" />
                <grid columns="3" gap="14">
                  <card radius="@cardRadius" background="@card" padding="16"><text text="1" size="22" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@card" padding="16"><text text="2" size="22" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@card" padding="16"><text text="3" size="22" weight="bold" color="@text" /></card>
                </grid>
                <row gap="10"><spacer weight="1" /><card radius="@cardRadius" background="@accent" padding="16"><text text="Call" size="16" weight="bold" color="@onAccent" /></card><spacer weight="1" /></row>
              </column>
            </layout>
        """.trimIndent()

        val inCall = """
            <layout name="calls.inCall" background="@background" padding="@page">
              <column gap="@gap">
                <spacer weight="1" />
                <avatar text="S" color="@accent" />
                <text text="Sam" size="26" weight="bold" color="@text" />
                <text text="00:42" size="13" color="@muted" />
                <spacer weight="1" />
                <row gap="14"><card radius="@cardRadius" background="@card" padding="16"><icon name="mic" size="24" color="@text" /></card><card radius="@cardRadius" background="@card" padding="16"><icon name="speaker" size="24" color="@text" /></card><card radius="@cardRadius" background="@danger" padding="16"><icon name="call_end" size="24" color="@onAccent" /></card></row>
              </column>
            </layout>
        """.trimIndent()

        val maps = """
            <layout name="maps.page" background="@background" padding="0">
              <stack>
                <canvas height="320" color="@card" />
                <column gap="8" padding="14">
                  <field hint="Search places" />
                  <card radius="@cardRadius" background="@card" border="@border" padding="12"><row gap="10"><icon name="pin" size="22" color="@accent" /><column><text text="Saved places" size="14" weight="semibold" color="@text" /><text text="Home · Work · 6 more" size="11" color="@muted" /></column></row></card>
                </column>
              </stack>
            </layout>
        """.trimIndent()

        val wallet = """
            <layout name="wallet.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Wallet" size="28" weight="bold" color="@text" />
                <card radius="@cardRadius" background="@accent" padding="18"><column gap="4"><text text="BALANCE" size="11" weight="bold" color="@onAccent" /><text text="12.408 PSC" size="28" weight="bold" color="@onAccent" /><text text="+2.1% today" size="12" color="@onAccent" /></column></card>
                <row gap="10"><button text="Send" background="@card" color="@text" /><button text="Receive" background="@card" color="@text" /><button text="Mine" background="@card" color="@text" /></row>
                <row gap="10" padding="10"><icon name="history" size="20" color="@muted" /><text text="Recent" size="13" color="@muted" /></row>
              </column>
            </layout>
        """.trimIndent()

        val models = """
            <layout name="models.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Models" size="28" weight="bold" color="@text" />
                <field hint="Search the mesh" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="cube" size="24" color="@accent" /><column><text text="Qwen 2.5 7B" size="15" weight="semibold" color="@text" /><text text="4.2 GB · Q4_K_M" size="11" color="@muted" /></column><spacer weight="1" /><text text="Get" size="12" color="@accent" /></row></card>
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="cube" size="24" color="@accentAlt" /><column><text text="Kokoro 82M" size="15" weight="semibold" color="@text" /><text text="340 MB · speech" size="11" color="@muted" /></column><spacer weight="1" /><text text="Installed" size="12" color="@muted" /></row></card>
              </column>
            </layout>
        """.trimIndent()

        val editor = """
            <layout name="editor.page" background="@background" padding="0">
              <column gap="0">
                <row gap="8" padding="10"><icon name="folder" size="18" color="@accent" /><text text="src/main.kt" size="12" color="@text" /><spacer weight="1" /><text text="Run" size="12" color="@accent" /></row>
                <divider color="@divider" />
                <card radius="0" background="@card" padding="12"><column gap="4"><text text="fun main() {" size="12" color="@accent" /><text text="    println(&apos;hello&apos;)" size="12" color="@text" /><text text="}" size="12" color="@accent" /></column></card>
                <row gap="8" padding="10"><text text="TERMINAL" size="10" weight="bold" color="@muted" /><spacer weight="1" /><text text="clear" size="11" color="@accent" /></row>
              </column>
            </layout>
        """.trimIndent()

        val lock = """
            <layout name="lock.screen" background="@background" padding="@page">
              <column gap="@gap">
                <spacer weight="1" />
                <text text="9:41" size="44" weight="bold" color="@text" />
                <text text="Sunday, 25 October" size="13" color="@muted" />
                <spacer weight="1" />
                <row gap="12"><spacer weight="1" /><card radius="@cardRadius" background="@card" border="@border" padding="12"><icon name="lock" size="20" color="@accent" /></card><card radius="@cardRadius" background="@card" border="@border" padding="12"><icon name="camera" size="20" color="@text" /></card><spacer weight="1" /></row>
                <text text="Swipe up to unlock" size="12" color="@faint" />
              </column>
            </layout>
        """.trimIndent()

        val themes = """
            <layout name="themes.gallery" background="@background" padding="@page">
              <column gap="@gap">
                <row><text text="Themes" size="28" weight="bold" color="@text" /><spacer weight="1" /><text text="Create" size="13" color="@accent" /></row>
                <grid columns="2" gap="12">
                  <card radius="@cardRadius" background="@accent" padding="14"><column gap="6"><text text="xOS" size="16" weight="bold" color="@onAccent" /><text text="built in" size="11" color="@onAccent" /></column></card>
                  <card radius="@cardRadius" background="@card" border="@border" padding="14"><column gap="6"><text text="Aurora" size="16" weight="bold" color="@text" /><text text="yours" size="11" color="@muted" /></column></card>
                </grid>
              </column>
            </layout>
        """.trimIndent()

        val virtualization = """
            <layout name="virtualization.page" background="@background" padding="0">
              <column gap="8">
                <canvas height="280" color="@card" />
                <row gap="10" padding="12"><icon name="power" size="20" color="@accent" /><text text="Virtualized app" size="13" color="@text" /><spacer weight="1" /><text text="Back" size="12" color="@accent" /></row>
                <text text="Running Android apps inside Prism. The guest OS is off while this is on." size="12" color="@muted" padding="14" />
              </column>
            </layout>
        """.trimIndent()

        val agentic = """
            <layout name="agentic.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Tools" size="28" weight="bold" color="@text" />
                <field hint="Ask for a tool" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="bolt" size="22" color="@accent" /><column><text text="run_shell" size="14" weight="semibold" color="@text" /><text text="enabled · 12 calls" size="11" color="@muted" /></column></row></card>
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="bolt" size="22" color="@accentAlt" /><column><text text="read_file" size="14" weight="semibold" color="@text" /><text text="enabled · 3 calls" size="11" color="@muted" /></column></row></card>
              </column>
            </layout>
        """.trimIndent()

        val nora = """
            <layout name="nora.chat" background="@background" padding="@page">
              <column gap="10">
                <row><avatar text="N" color="@accent" /><text text="Nora" size="18" weight="bold" color="@text" /><spacer weight="1" /><text text="neuromodulators" size="11" color="@muted" /></row>
                <card radius="@cardRadius" background="@card" padding="12"><text text="What are you working on?" size="14" color="@text" /></card>
                <card radius="@cardRadius" background="@accent" padding="12"><text text="The launcher. Want to see?" size="14" color="@onAccent" /></card>
                <row gap="8"><field hint="Message Nora" /><button text="Send" background="@accent" color="@onAccent" /></row>
              </column>
            </layout>
        """.trimIndent()

        val aether = """
            <layout name="aether.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Aether" size="28" weight="bold" color="@text" />
                <text text="training · 12.4B tokens" size="12" color="@muted" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><column gap="8"><row><text text="STEP 14,208" size="11" weight="bold" color="@accent" /><spacer weight="1" /><text text="loss 1.82" size="11" color="@muted" /></row><canvas height="80" color="@raised" /><row gap="10"><button text="Pause" background="@raised" color="@text" /><button text="Checkpoint" background="@raised" color="@text" /></row></column></card>
              </column>
            </layout>
        """.trimIndent()

        val health = """
            <layout name="health.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="System health" size="28" weight="bold" color="@text" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="check" size="22" color="@accent" /><column><text text="All services up" size="15" weight="semibold" color="@text" /><text text="mesh · vpn · audio" size="11" color="@muted" /></column></row></card>
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><row gap="10"><icon name="warning" size="22" color="@danger" /><column><text text="Battery optimisation" size="15" weight="semibold" color="@text" /><text text="restricts the mesh service" size="11" color="@muted" /></column></row></card>
              </column>
            </layout>
        """.trimIndent()

        val writerPage = """
            <layout name="writer.page" background="@card" padding="8">
              <column gap="6">
                <row gap="8" padding="4"><icon name="keyboard" size="16" color="@accent" /><text text="Prism Writer" size="11" weight="bold" color="@muted" /><spacer weight="1" /><text text="dictate" size="11" color="@accent" /></row>
                <card radius="@cardRadius" background="@raised" padding="8"><column gap="4"><text text="suggestion" size="13" color="@text" /><text text="suggestion" size="13" color="@muted" /></column></card>
                <grid columns="3" gap="5">
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="Q" size="16" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="W" size="16" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="E" size="16" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="A" size="16" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="S" size="16" weight="bold" color="@text" /></card>
                  <card radius="@cardRadius" background="@raised" padding="10"><text text="D" size="16" weight="bold" color="@text" /></card>
                </grid>
                <row gap="6"><card radius="@cardRadius" background="@raised" padding="8"><text text="123" size="12" color="@muted" /></card><card radius="@cardRadius" background="@card" padding="8"><text text="space" size="12" color="@muted" /></card><card radius="@cardRadius" background="@accent" padding="8"><text text="return" size="12" color="@onAccent" /></card></row>
              </column>
            </layout>
        """.trimIndent()

        val photosGrid = """
            <layout name="photos.grid" background="@background" padding="@page">
              <column gap="@gap">
                <row><text text="Photos" size="26" weight="bold" color="@text" /><spacer weight="1" /><text text="1,204 items" size="11" color="@muted" /></row>
                <field hint="Search photos and videos" />
                <row gap="8"><text text="On this device" size="12" color="@accent" /><text text="Backed up" size="12" color="@muted" /><text text="Other devices" size="12" color="@muted" /></row>
                <grid columns="3" gap="6">
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                  <card radius="@cardRadius" background="@card" padding="0"><canvas height="104" color="@raised" /></card>
                </grid>
              </column>
            </layout>
        """.trimIndent()

        val photoViewer = """
            <layout name="photo.viewer" background="@background" padding="0">
              <stack>
                <canvas height="420" color="@card" />
                <column gap="8" padding="12">
                  <spacer weight="1" />
                  <row gap="6"><card radius="@cardRadius" background="@overlay" padding="8"><icon name="crop" size="18" color="@text" /></card><card radius="@cardRadius" background="@overlay" padding="8"><icon name="pen" size="18" color="@text" /></card><spacer weight="1" /><card radius="@cardRadius" background="@accent" padding="10"><icon name="edit" size="22" color="@onAccent" /></card><spacer weight="1" /><card radius="@cardRadius" background="@overlay" padding="8"><icon name="share" size="18" color="@text" /></card><card radius="@cardRadius" background="@overlay" padding="8"><icon name="delete" size="18" color="@text" /></card></row>
                </column>
              </stack>
            </layout>
        """.trimIndent()

        val photoPix = """
            <layout name="photopix.editor" background="@background" padding="0">
              <row gap="6">
                <card radius="@cardRadius" background="@card" padding="6"><column gap="6"><icon name="move" size="18" color="@text" /><icon name="crop" size="18" color="@text" /><icon name="brush" size="18" color="@accent" /><icon name="eraser" size="18" color="@text" /><icon name="text" size="18" color="@text" /></column></card>
                <column gap="6">
                  <canvas height="330" color="@card" />
                  <row gap="6"><text text="Layers" size="11" color="@muted" /><spacer weight="1" /><text text="100%" size="11" color="@muted" /></row>
                </column>
              </row>
            </layout>
        """.trimIndent()

        val speech = """
            <layout name="speech.page" background="@background" padding="@page">
              <column gap="@gap">
                <text text="Speech" size="28" weight="bold" color="@text" />
                <card radius="@cardRadius" background="@card" border="@border" padding="14"><column gap="8"><row><icon name="speaker" size="22" color="@accent" /><text text="Kokoro" size="15" weight="semibold" color="@text" /><spacer weight="1" /><text text="ready" size="11" color="@muted" /></row><divider color="@divider" /><row gap="10"><button text="Speak" background="@raised" color="@text" /><button text="Clone voice" background="@raised" color="@text" /></row></column></card>
                <field hint="Say something" />
              </column>
            </layout>
        """.trimIndent()

        return linkedMapOf(
            "settings.root" to settings,
            "launcher.desktop" to launcher,
            "launcher.drawer" to drawer,
            "messages.list" to messages,
            "browser.chrome" to browser,
            "files.grid" to files,
            "dialogs.alert" to dialogs,
            "widgets.card" to widgets,
            "calls.dialer" to dialer,
            "calls.inCall" to inCall,
            "maps.page" to maps,
            "wallet.page" to wallet,
            "models.page" to models,
            "editor.page" to editor,
            "lock.screen" to lock,
            "themes.gallery" to themes,
            "virtualization.page" to virtualization,
            "agentic.page" to agentic,
            "nora.chat" to nora,
            "aether.page" to aether,
            "health.page" to health,
            "speech.page" to speech,
            "writer.page" to writerPage,
            "photos.grid" to photosGrid,
            "photo.viewer" to photoViewer,
            "photopix.editor" to photoPix,
        ).mapValues { (_, xml) ->
            xml.replace("@cardRadius", radius)
        }
    }
}

object PrismThemes {

    private const val TAG = "PrismThemes"
    private const val DEFAULT_ID = "xos"

    private var root: File? = null
    private val user = LinkedHashMap<String, PrismThemeSpec>()

    /** Bumped on every selection or save; hosts that cache derived values key on it. */
    @Volatile
    var revision: Int = 0
        private set

    /**
     * Screens listening for a live theme change.
     *
     * The revision tells a host that has not drawn yet that its cache is stale. It cannot tell a
     * screen that is already on the glass: a View bakes its colour, typeface and shape when it is
     * constructed, so the only thing that actually repaints an open screen is rebuilding it. The
     * Android host subscribes here and recreates whichever screen is visible, which is what makes
     * a theme switch apply without closing and reopening the app.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * The built-ins this process is using.
     *
     * Replaced once at startup by [installDesktopLook] on a desktop. A plain field rather than a
     * lazy value because the desktop installs its interpretation before the first window, and a
     * lazy would have frozen whichever list was asked for first.
     */
    private var builtIns: List<PrismThemeSpec> = BuiltInThemes.all()

    /**
     * Uses the desktop interpretation of the built-ins: xOS becomes macOS.
     *
     * Called once by the desktop entry point, before any window is composed. Android never calls
     * it, so the phone keeps the iOS-derived xOS it was designed around. If the active theme is
     * xOS the revision is bumped so an already-built screen rebuilds with the desktop spec.
     */
    fun installDesktopLook() {
        builtIns = BuiltInThemes.desktop()
        changed()
    }

    /** Registers [listener] for selection and edit events; the returned lambda unregisters it. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() {
        revision++
        listeners.forEach { listener -> runCatching { listener() } }
    }

    fun install(directory: File) {
        root = directory.apply { mkdirs() }
        reload()
    }

    fun reload() {
        user.clear()
        val folder = root ?: return
        folder.listFiles { file -> file.isFile && file.extension == "json" }?.forEach { file ->
            val parsed = runCatching { specFromJson(JSONObject(file.readText()), file.nameWithoutExtension) }
                .getOrElse {
                    PrismPlatform.log.warn(TAG, "Could not read theme " + file.name + ": " + it.message)
                    null
                }
            if (parsed != null) user[parsed.id] = parsed
        }
        changed()
    }

    fun all(): List<PrismThemeSpec> =
        builtIns + user.values.sortedBy { it.name.lowercase() }

    fun find(id: String): PrismThemeSpec? =
        builtIns.firstOrNull { it.id == id } ?: user[id]

    fun active(): PrismThemeSpec =
        find(PrismSettings.getThemeId()) ?: builtIns.first()

    fun mode(light: Boolean): ThemeModeSpec {
        val theme = active()
        return if (light) theme.light else theme.dark
    }

    fun modeFor(dark: Boolean): ThemeModeSpec = mode(light = !dark)

    fun select(id: String): Boolean {
        val theme = find(id) ?: return false
        PrismSettings.setThemeId(theme.id)
        PrismSettings.bumpThemeRevision()
        changed()
        return true
    }

    /** Saves [theme], turning a built-in edit into a user copy. */
    fun save(theme: PrismThemeSpec): Result<PrismThemeSpec> {
        val cleanName = theme.name.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Give the theme a name."))
        }
        val id = if (theme.builtIn || theme.id.isBlank() || !theme.id.startsWith("user-")) {
            "user-" + UUID.randomUUID().toString().take(8)
        } else {
            sanitize(theme.id)
        }
        val stored = theme.copy(
            id = id,
            name = cleanName.take(80),
            description = theme.description.take(240),
            author = theme.author.ifBlank { "You" }.take(60),
            builtIn = false,
            updatedAt = System.currentTimeMillis(),
        )
        user[id] = stored
        persist(stored)
        changed()
        return Result.success(stored)
    }

    fun delete(id: String): Boolean {
        if (builtIns.any { it.id == id }) return false
        if (user.remove(id) == null) return false
        runCatching { root?.let { File(it, id + ".json").delete() } }
        if (PrismSettings.getThemeId() == id) {
            PrismSettings.setThemeId(DEFAULT_ID)
            PrismSettings.bumpThemeRevision()
        }
        changed()
        return true
    }

    fun export(id: String): String? = find(id)?.let { specToJson(it).toString(2) }

    fun importTheme(json: String): Result<PrismThemeSpec> {
        val parsed = runCatching { specFromJson(JSONObject(json), "user-import") }
            .getOrElse { return Result.failure(IllegalArgumentException("That is not a Prism theme.")) }
        val cleanName = parsed.name.trim()
        if (cleanName.isEmpty()) return Result.failure(IllegalArgumentException("The theme has no name."))
        val stored = parsed.copy(
            id = "user-" + UUID.randomUUID().toString().take(8),
            name = cleanName.take(80),
            builtIn = false,
            author = parsed.author.ifBlank { "Imported" },
            updatedAt = System.currentTimeMillis(),
        )
        user[stored.id] = stored
        persist(stored)
        changed()
        return Result.success(stored)
    }

    /** A blank theme seeded with the active palette, ready for the editor. */
    fun create(name: String = "My theme"): PrismThemeSpec {
        val base = active()
        val light = base.light.copy(
            layouts = base.light.layouts.ifEmpty { ThemeLayouts.forTheme(base.light, Flavor.CARDS, true) },
        )
        val dark = base.dark.copy(
            layouts = base.dark.layouts.ifEmpty { ThemeLayouts.forTheme(base.dark, Flavor.CARDS, false) },
        )
        return PrismThemeSpec(
            id = "",
            name = name,
            description = "A custom Prism theme.",
            author = "You",
            builtIn = false,
            light = light,
            dark = dark,
        )
    }

    fun duplicate(id: String, newName: String = "Copy"): PrismThemeSpec? {
        val source = find(id) ?: return null
        return source.copy(
            id = "",
            name = newName,
            builtIn = false,
            author = "You",
            updatedAt = 0L,
        )
    }

    fun layoutNames(): List<String> = ThemeLayouts.names

    fun defaultLayout(name: String, mode: ThemeModeSpec): String =
        mode.layouts[name] ?: ThemeLayouts.forTheme(mode, Flavor.CARDS, true)[name]
        ?: "<layout name=\"$name\" background=\"@background\"><column><text text=\"$name\" color=\"@text\" /></column></layout>"

    fun colorHex(value: Long): String = "#" + (value and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    fun parseColor(text: String): Long? {
        val value = text.trim()
        if (value.startsWith("@")) return null
        return parseHex(value)
    }

    private fun persist(theme: PrismThemeSpec) {
        val destination = root ?: return
        runCatching {
            destination.mkdirs()
            File(destination, theme.id + ".json").writeText(specToJson(theme).toString(2))
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not save theme " + theme.name + ": " + it.message)
        }
    }

    private fun sanitize(id: String): String =
        id.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank {
            "user-" + UUID.randomUUID().toString().take(8)
        }

    private fun specToJson(theme: PrismThemeSpec): JSONObject = JSONObject()
        .put("format", 1)
        .put("id", theme.id)
        .put("name", theme.name)
        .put("description", theme.description)
        .put("author", theme.author)
        .put("builtIn", theme.builtIn)
        .put("showWallpaper", theme.showWallpaper)
        .put("edgeEffect", theme.edgeEffect)
        .put("backdrop", theme.backdrop)
        .put("updatedAt", theme.updatedAt)
        .put("light", modeToJson(theme.light))
        .put("dark", modeToJson(theme.dark))

    private fun modeToJson(mode: ThemeModeSpec): JSONObject {
        val palette = JSONObject()
            .put("background", mode.palette.background)
            .put("surface", mode.palette.surface)
            .put("raised", mode.palette.raised)
            .put("card", mode.palette.card)
            .put("text", mode.palette.text)
            .put("muted", mode.palette.muted)
            .put("faint", mode.palette.faint)
            .put("divider", mode.palette.divider)
            .put("accent", mode.palette.accent)
            .put("accentAlt", mode.palette.accentAlt)
            .put("onAccent", mode.palette.onAccent)
            .put("destructive", mode.palette.destructive)
            .put("overlay", mode.palette.overlay)
        val layouts = JSONObject()
        mode.layouts.forEach { (name, xml) -> layouts.put(name, xml) }
        return JSONObject()
            .put("palette", palette)
            .put("typography", JSONObject()
                .put("family", mode.typography.family)
                .put("titleWeight", mode.typography.titleWeight)
                .put("scale", mode.typography.scale)
                .put("letterSpacing", mode.typography.letterSpacing)
                .put("uppercaseHeaders", mode.typography.uppercaseHeaders))
            .put("shape", JSONObject()
                .put("card", mode.shape.card)
                .put("button", mode.shape.button)
                .put("field", mode.shape.field)
                .put("border", mode.shape.border)
                .put("elevation", mode.shape.elevation)
                .put("pill", mode.shape.pill))
            .put("density", JSONObject()
                .put("scale", mode.density.scale)
                .put("gap", mode.density.gap)
                .put("page", mode.density.page)
                .put("row", mode.density.row)
                .put("icons", mode.density.icons))
            .put("layouts", layouts)
    }

    private fun specFromJson(json: JSONObject, fallbackId: String): PrismThemeSpec {
        val fallback = builtIns.first()
        val id = sanitize(json.optString("id", fallbackId))
        val name = json.optString("name").trim().ifBlank { "Untitled theme" }
        val light = json.optJSONObject("light")?.let { modeFromJson(it, false) } ?: fallback.light
        val dark = json.optJSONObject("dark")?.let { modeFromJson(it, true) } ?: fallback.dark
        return PrismThemeSpec(
            id = id,
            name = name.take(80),
            description = json.optString("description").take(240),
            author = json.optString("author", "You").ifBlank { "You" }.take(60),
            builtIn = false,
            light = light,
            dark = dark,
            showWallpaper = json.optBoolean("showWallpaper", true),
            // An unknown value from a newer build falls back rather than breaks: a theme that
            // asks for an effect this renderer has never heard of should still open.
            edgeEffect = json.optString("edgeEffect", EDGE_GLOW)
                .trim().lowercase().takeIf { it in EDGE_EFFECTS } ?: EDGE_GLOW,
            backdrop = json.optString("backdrop", BACKDROP_NONE)
                .trim().lowercase().takeIf { it in BACKDROPS } ?: BACKDROP_NONE,
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    private fun modeFromJson(json: JSONObject, dark: Boolean): ThemeModeSpec {
        val fallback = if (dark) builtIns.first().dark else builtIns.first().light
        val paletteJson = json.optJSONObject("palette")
        val p = paletteJson ?: JSONObject()
        val palette = ThemePalette(
            background = p.optLong("background", fallback.palette.background),
            surface = p.optLong("surface", fallback.palette.surface),
            raised = p.optLong("raised", fallback.palette.raised),
            card = p.optLong("card", fallback.palette.card),
            text = p.optLong("text", fallback.palette.text),
            muted = p.optLong("muted", fallback.palette.muted),
            faint = p.optLong("faint", fallback.palette.faint),
            divider = p.optLong("divider", fallback.palette.divider),
            accent = p.optLong("accent", fallback.palette.accent),
            accentAlt = p.optLong("accentAlt", fallback.palette.accentAlt),
            onAccent = p.optLong("onAccent", fallback.palette.onAccent),
            destructive = p.optLong("destructive", fallback.palette.destructive),
            overlay = p.optLong("overlay", fallback.palette.overlay),
        )
        val typographyJson = json.optJSONObject("typography")
        val t = typographyJson ?: JSONObject()
        val typography = ThemeTypography(
            family = t.optString("family", fallback.typography.family).lowercase().let { family ->
                if (family in setOf("sans", "serif", "mono", "rounded", "condensed")) family
                else "sans"
            },
            titleWeight = t.optInt("titleWeight", fallback.typography.titleWeight),
            scale = t.optDouble("scale", fallback.typography.scale.toDouble()).toFloat(),
            letterSpacing = t.optDouble("letterSpacing", fallback.typography.letterSpacing.toDouble()).toFloat(),
            uppercaseHeaders = t.optBoolean("uppercaseHeaders", fallback.typography.uppercaseHeaders),
        )
        val shapeJson = json.optJSONObject("shape")
        val s = shapeJson ?: JSONObject()
        val shape = ThemeShape(
            card = s.optInt("card", fallback.shape.card).coerceIn(0, 64),
            button = s.optInt("button", fallback.shape.button).coerceIn(0, 64),
            field = s.optInt("field", fallback.shape.field).coerceIn(0, 64),
            border = s.optInt("border", fallback.shape.border).coerceIn(0, 8),
            elevation = s.optInt("elevation", fallback.shape.elevation).coerceIn(0, 32),
            pill = s.optBoolean("pill", fallback.shape.pill),
        )
        val densityJson = json.optJSONObject("density")
        val d = densityJson ?: JSONObject()
        val density = ThemeDensity(
            scale = d.optDouble("scale", fallback.density.scale.toDouble()).toFloat().coerceIn(0.7f, 1.4f),
            gap = d.optInt("gap", fallback.density.gap).coerceIn(0, 64),
            page = d.optInt("page", fallback.density.page).coerceIn(0, 64),
            row = d.optInt("row", fallback.density.row).coerceIn(0, 64),
            icons = d.optDouble("icons", fallback.density.icons.toDouble()).toFloat().coerceIn(0.6f, 1.6f),
        )
        val layoutsJson = json.optJSONObject("layouts")
        val layouts = LinkedHashMap<String, String>()
        layoutsJson?.keys()?.forEach { key ->
            val xml = layoutsJson.optString(key)
            if (xml.isNotBlank() && key in ThemeLayouts.names) layouts[key] = xml
        }
        val merged = LinkedHashMap<String, String>()
        merged.putAll(ThemeLayouts.forTheme(ThemeModeSpec(palette, typography, shape, density), Flavor.CARDS, !dark))
        merged.putAll(layouts)
        return ThemeModeSpec(palette, typography, shape, density, merged)
    }
}

/** Parses `#RRGGBB` or `#AARRGGBB`; Prism stores ARGB longs everywhere. */
internal fun parseHex(text: String): Long? {
    val value = text.trim().removePrefix("#")
    if (value.isEmpty() || value.length > 8) return null
    val number = value.toLongOrNull(16) ?: return null
    return if (value.length <= 6) 0xFF000000L or number else number
}
