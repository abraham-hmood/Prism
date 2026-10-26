package com.prism.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.core.PrismPlatform
import com.prism.launcher.FontEngine
import com.prism.launcher.PrismSettings
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeModeSpec
import java.io.File

/**
 * Prism's look, driven by the same settings the Android build reads.
 *
 * PHASE 5.5 IS THREE SEPARATE THINGS AND ONLY ONE OF THEM IS A PORT.
 *
 *   NEON GLOW is a port. `NeonGlowEngine` on Android is thin -- it sets a text colour plus a
 *   shadow layer, a card stroke plus a coloured elevation shadow. Those are View-level calls with
 *   direct Compose equivalents, so [neonGlow] and [PrismColors.accent] reproduce the effect from
 *   the same `glow_color` key. What does not survive is the API-gated parts (`outlineSpotShadowColor`
 *   is API 28+, the RenderEffect branch was never implemented); Compose draws the halo the same
 *   way everywhere, so desktop gets the effect unconditionally.
 *
 *   FONTS are a port with a caveat. `font_style` selects default, Nasalization, or a user file.
 *   Loading a user-supplied .ttf works identically here. The bundled Nasalization face is an
 *   Android asset that is not on the desktop classpath yet, so choosing it currently falls back to
 *   the default rather than silently drawing something else -- packaging the font is Phase 11.
 *
 *   ICON PACKS ARE NOT A PORT AND CANNOT BE. Android icon packs are APKs whose drawables are read
 *   through PackageManager; there is no APK on a desktop and no PackageManager to read one with.
 *   The genuine desktop equivalent is the XDG icon theme spec, which `XdgAppCatalog` already
 *   resolves when it finds an application's icon -- so the feature exists, via a different
 *   mechanism, and there is deliberately no icon-pack setting here pretending otherwise.
 */
data class PrismColors(
    /** From the active theme, falling back to the old glow accent. */
    val accent: Color,
    val background: Color = Color(0xFF0D0D10),
    val surface: Color = Color(0xFF16161A),
    val surfaceRaised: Color = Color(0xFF1E1E24),
    val onSurface: Color = Color(0xFFE6E6EE),
    val muted: Color = Color(0xFFB9B9C4),
    val faint: Color = Color(0xFF6C6C78),
    val card: Color = Color(0xFF16161A),
    val divider: Color = Color(0xFF26262E),
    val destructive: Color = Color(0xFFFF6B6B),
    val cardRadius: Dp = 12.dp,
    val buttonRadius: Dp = 12.dp,
    val fieldRadius: Dp = 9.dp,
    /** Border width in dp; 0 means the theme draws no chrome rule. */
    val border: Int = 0,
    /** Shadow elevation in dp; 0 means the theme does not float or glow. */
    val elevation: Int = 0,
    val pill: Boolean = false,
    val pagePadding: Dp = 16.dp,
    val gap: Dp = 10.dp,
    /** EDGE_GLOW, EDGE_ELECTRIC or EDGE_NONE, from the theme. */
    val edgeEffect: String = com.prism.launcher.theme.EDGE_GLOW,
    /** A ground the theme paints itself; see BACKDROP_* in PrismThemes. */
    val backdrop: String = com.prism.launcher.theme.BACKDROP_NONE,
)

val LocalPrismColors = compositionLocalOf { PrismColors(accent = Color(0xFF7C6CFF)) }

/** The whole active appearance, for screens that need more than the colour ramp. */
val LocalPrismTheme = compositionLocalOf<ThemeModeSpec> { PrismThemes.modeFor(true) }

/**
 * Whether the desktop shell should draw itself the way macOS does.
 *
 * TRUE FOR xOS ON A DESKTOP, where the built-in is deliberately the macOS interpretation (see
 * `BuiltInThemes.xosDesktop`). The palette and shape already come through [LocalPrismColors]; this
 * flag is for the handful of layout decisions a palette cannot express -- the sidebar's own tint,
 * an accent-coloured selection row, sentence-case section headings and a toolbar-height page
 * header. Android never sees it, and neither does a user theme.
 */
val LocalMacOsLook = compositionLocalOf { false }

/**
 * Whether the shell is drawing the taskbar, so a page must not draw its own.
 *
 * Under xOS the window's taskbar carries the pages AND the desktop page's pin/prediction strip;
 * the desktop page therefore gives up its strip or the window would show two of them stacked.
 * False everywhere else, including launcher mode, where the desktop page still owns its taskbar.
 */
val LocalShellTaskbar = compositionLocalOf { false }

fun Long.toComposeColor(): Color = Color(toInt())

/**
 * Wraps content in Prism's theme, reading the live settings.
 *
 * Read once per composition rather than observed: these are changed from Prism's own settings
 * page, which recomposes the whole window when it writes, so there is nothing that could change
 * them behind this.
 */
@Composable
fun PrismTheme(content: @Composable () -> Unit) {
    // Keyed on the revision so a theme change actually repaints. Without this the accent colour
    // and font are read once at startup and a change does nothing until the next launch, which
    // is the "live re-theming" gap Phase 19 and 20 both left open.
    val revision = ThemeRevision.value
    val spec = remember(revision, PrismThemes.revision) { PrismThemes.modeFor(true) }
    // The SPEC, not the mode: the edge effect and the backdrop describe the theme as a whole, the
    // way its name and layouts do. Reading them off ThemeModeSpec is how the first pass compiled
    // against fields that did not exist there.
    val activeSpec = remember(revision, PrismThemes.revision) { PrismThemes.active() }
    val palette = spec.palette
    val colors = remember(revision, spec, activeSpec) {
        PrismColors(
            accent = palette.accent.toComposeColor(),
            background = palette.background.toComposeColor(),
            surface = palette.surface.toComposeColor(),
            surfaceRaised = palette.raised.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            muted = palette.muted.toComposeColor(),
            faint = palette.faint.toComposeColor(),
            card = palette.card.toComposeColor(),
            divider = palette.divider.toComposeColor(),
            destructive = palette.destructive.toComposeColor(),
            cardRadius = spec.shape.card.dp,
            buttonRadius = spec.shape.button.dp,
            fieldRadius = spec.shape.field.dp,
            border = spec.shape.border,
            elevation = spec.shape.elevation,
            pill = spec.shape.pill,
            pagePadding = spec.density.page.dp,
            gap = spec.density.gap.dp,
            edgeEffect = activeSpec.edgeEffect,
            backdrop = activeSpec.backdrop,
        )
    }
    val fonts = remember(revision) { prismFontFamily() }
    // Read through the store so a theme SELECTION changes the shell too, not only the palette.
    val macOs = remember(revision, PrismThemes.revision) { PrismThemes.active().id == "xos" }

    CompositionLocalProvider(
        LocalPrismColors provides colors,
        LocalPrismTheme provides spec,
        LocalMacOsLook provides macOs,
    ) {
        MaterialTheme(
            // 6pt controls and 10pt cards are half of what makes macOS look like macOS; Material's
            // default 20pt pill for every button is the other half of why a theme alone is not
            // enough. Left untouched for every other theme.
            shapes = if (macOs) macOsShapes(spec) else Shapes(),
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = Color.White,
                background = colors.background,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.muted,
            ),
            typography = fonts?.let { family ->
                Typography().run {
                    copy(
                        bodyLarge = bodyLarge.copy(fontFamily = family),
                        bodyMedium = bodyMedium.copy(fontFamily = family),
                        titleLarge = titleLarge.copy(fontFamily = family),
                        titleMedium = titleMedium.copy(fontFamily = family),
                        labelLarge = labelLarge.copy(fontFamily = family),
                    )
                }
            } ?: Typography(),
            content = content,
        )
    }
}

/** Material's shape ramp, resolved to the theme's own control sizes. */
private fun macOsShapes(spec: ThemeModeSpec): Shapes = Shapes(
    extraSmall = RoundedCornerShape(spec.shape.field.dp),
    small = RoundedCornerShape(spec.shape.button.dp),
    medium = RoundedCornerShape(spec.shape.card.dp),
    large = RoundedCornerShape((spec.shape.card + 4).dp),
    extraLarge = RoundedCornerShape((spec.shape.card + 8).dp),
)

/**
 * The font named by `font_style`, or null to use the platform default.
 *
 * A user-supplied file that fails to load returns null rather than throwing -- a corrupt or
 * deleted font should cost the user their font choice, not their launcher.
 */
private fun prismFontFamily(): FontFamily? {
    return try {
        // FontEngine handles BOTH options, including downloading Nasalization on first use --
        // the same mechanism the Android build uses, which is why this is a full port and not
        // the partial one an earlier pass recorded.
        val file = FontEngine.resolve() ?: return null
        FontFamily(androidx.compose.ui.text.platform.Font(file, FontWeight.Normal))
    } catch (e: Exception) {
        PrismPlatform.log.error("Prism/theme", "Could not load the selected font", e)
        null
    }
}

/**
 * The neon halo, as `NeonGlowEngine.applyNeonText`/`applyNeonCard` produce on Android.
 *
 * Drawn as a radial gradient behind the element rather than with a real blur: Compose's blur
 * modifier is GPU-backed and applies to the element's own pixels, which dims the icon instead of
 * surrounding it. A gradient behind it is both cheaper and closer to what the Android shadow
 * layer actually looks like.
 */
fun Modifier.neonGlow(color: Color, radius: Dp = 16.dp, intensity: Float = 0.55f): Modifier {
    // Glow is a theme trait, not a universal Prism decoration: a flat theme gets no halo.
    if (PrismThemes.modeFor(true).shape.elevation <= 0) return this
    return this.drawBehind {
        val r = radius.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = intensity), Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = (size.minDimension / 2f) + r,
            ),
            radius = (size.minDimension / 2f) + r,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
}

/** A raised surface with a coloured shadow -- the `applyNeonCard` equivalent. */
fun Modifier.neonCard(color: Color, elevation: Dp = 10.dp, corner: Dp = 12.dp): Modifier {
    val spec = PrismThemes.modeFor(true)
    if (spec.shape.elevation <= 0) return this
    return this.shadow(
        elevation = elevation,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(corner),
        ambientColor = color,
        spotColor = color,
    )
}

/** Text styled the way `applyNeonText` styles it: accent colour plus a soft halo. */
@Composable
fun neonTextStyle(base: TextStyle = MaterialTheme.typography.titleMedium): TextStyle {
    val colors = LocalPrismColors.current
    val glowing = LocalPrismTheme.current.shape.elevation > 0
    return base.copy(
        color = colors.accent,
        shadow = if (glowing) {
            androidx.compose.ui.graphics.Shadow(
                color = colors.accent.copy(alpha = 0.7f),
                offset = Offset.Zero,
                blurRadius = 12f,
            )
        } else {
            null
        },
    )
}

/** Convenience wrapper for an element that should sit inside a halo. */
@Composable
fun Glowing(
    color: Color = LocalPrismColors.current.accent,
    radius: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.neonGlow(color, radius)) { content() }
}


/**
 * Bumped whenever something that affects appearance changes.
 *
 * WHY A COUNTER RATHER THAN OBSERVING THE SETTINGS. `PrismSettings` is a plain key-value store
 * with no change notification -- deliberately, since it has to work identically on Android's
 * SharedPreferences and a desktop properties file. So the code that WRITES an appearance setting
 * announces it here, and everything that caches something derived from one keys on this.
 *
 * That covers the icon caches too: an icon-theme change has to drop decoded bitmaps, or the
 * drawer keeps drawing the previous theme's icons until restart.
 */
object ThemeRevision {
    var value by mutableStateOf(0)
        private set

    /** Call after writing any appearance setting: glow colour, font, icon theme. */
    fun bump() {
        value++
    }
}
