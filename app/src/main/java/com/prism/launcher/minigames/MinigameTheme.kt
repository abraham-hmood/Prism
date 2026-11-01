package com.prism.launcher.minigames

import android.content.Context
import com.prism.launcher.theme.PrismThemes

/**
 * The colours a minigame draws with, taken from the user's theme.
 *
 * xOS keeps its own arcade palette because that IS the theme's look: cyan, magenta and a near-black
 * cabinet. Every other theme hands its palette down here instead of the arcade ignoring it, so a
 * light theme gets a light floor and its own accent rather than a black slab with neon on top.
 */
data class MinigameColors(
    val background: Int,
    val board: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val accentAlt: Int,
    val danger: Int,
    val success: Int,
    /** True for xOS, where the palette is deliberately fixed neon. */
    val neon: Boolean,
) {
    /** Index order the arcade scenes use: 0 cyan, 1 magenta, 2 yellow, 3 green, 4 orange, 5 blue, 6 red, 7 white, 8 dim. */
    fun sprites(): IntArray = if (neon) intArrayOf(
        0xFF39E7FF.toInt(), 0xFFFF4FD8.toInt(), 0xFFFFE36E.toInt(), 0xFF5CFF9D.toInt(),
        0xFFFF9C42.toInt(), 0xFF6E8BFF.toInt(), 0xFFFF5B6E.toInt(), 0xFFFFFFFF.toInt(),
        0xFF20203A.toInt(),
    ) else intArrayOf(
        accent,
        accentAlt,
        blend(accent, ink, 0.35f),
        success,
        blend(accentAlt, accent, 0.5f),
        blend(accent, background, 0.35f),
        danger,
        ink,
        board,
    )

    companion object {
        fun blend(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

object MinigameTheme {

    fun of(context: Context): MinigameColors {
        val theme = PrismThemes.active()
        if (theme.id == "xos") {
            return MinigameColors(
                background = 0xFF030108.toInt(),
                board = 0xFF0B0620.toInt(),
                ink = 0xFFCDEBFF.toInt(),
                muted = 0xFF8A8AB0.toInt(),
                accent = 0xFF39E7FF.toInt(),
                accentAlt = 0xFFFF4FD8.toInt(),
                danger = 0xFFFF5B6E.toInt(),
                success = 0xFF5CFF9D.toInt(),
                neon = true,
            )
        }
        val palette = PrismThemes.modeFor(PaperUi.isDark(context)).palette
        val accent = palette.accent.toInt()
        val alt = palette.accentAlt.toInt()
        return MinigameColors(
            background = palette.background.toInt(),
            board = palette.surface.toInt(),
            ink = palette.text.toInt(),
            muted = palette.muted.toInt(),
            accent = accent,
            accentAlt = alt,
            danger = palette.destructive.toInt(),
            success = if (PaperUi.isDark(context)) 0xFF7CFF6B.toInt() else 0xFF1F7A44.toInt(),
            neon = false,
        )
    }

    fun blend(from: Int, to: Int, amount: Float): Int = MinigameColors.blend(from, to, amount)
}
