package com.prism.launcher.minigames

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The floor of the arcade.
 *
 * Paper Empire owns the pencil-and-paper look. Every other game stands on this instead: near-black
 * with a faint perspective grid and one coloured glow, tinted per game so Chess, Pong, Pinball and
 * Minesweeper are distinguishable before a single piece is drawn.
 *
 * It is static art -- no animation, no per-frame allocation -- because it is painted behind games
 * that already redraw at 60 Hz.
 */
object ArcadeStyle {

    /**
     * The user's palette, installed by the Minigames page before a game is shown.
     *
     * A file-static rather than a constructor argument because these views are built by a dozen
     * call sites and all of them already ask the same question -- "what does the arcade look like
     * right now?". xOS keeps its neon constants; every other theme is answered from here.
     */
    @Volatile
    private var themed: MinigameColors? = null

    private const val NEON_INK = 0xFFCDEBFF.toInt()
    private const val NEON_MUTED = 0xFF8A8AB0.toInt()

    fun applyTheme(colors: MinigameColors?) {
        themed = if (colors != null && !colors.neon) colors else null
    }

    /** A visible-but-not-glaring ink for outlines on the current surface. */
    val INK: Int get() = themed?.ink ?: NEON_INK

    /** Secondary text on the current surface. */
    val MUTED: Int get() = themed?.muted ?: NEON_MUTED

    /** Chess is violet, Pong cyan, Pinball magenta, Minesweeper green. */
    const val CHESS = 0xFFB56CFF.toInt()
    const val PONG = 0xFF39E7FF.toInt()
    const val PINBALL = 0xFFFF2BD6.toInt()
    const val MINESWEEPER = 0xFF7CFF6B.toInt()

    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawBackdrop(canvas: Canvas, width: Int, height: Int, density: Float, accent: Int) {
        if (width <= 0 || height <= 0) return
        val palette = themed
        if (palette != null) {
            // The theme's own floor. A light theme gets a light gradient and light vignette; a
            // dark theme gets its surfaces. The game's accent still tints the horizon, so the
            // cabinet is recognisably the same game on every theme.
            val top = MinigameColors.blend(palette.background, palette.ink, 0.06f)
            background.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                top, palette.background, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

            glow.shader = RadialGradient(
                width / 2f, height * 0.58f, maxOf(width, height) * 0.72f,
                (accent and 0x00FFFFFF) or 0x1F000000, 0x00000000, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

            val horizon = height * 0.62f
            grid.strokeWidth = 1f * density
            grid.color = (accent and 0x00FFFFFF) or 0x22000000
            canvas.drawLine(0f, horizon, width.toFloat(), horizon, grid)
            var x = -width.toFloat()
            while (x < width * 2f) {
                canvas.drawLine(width / 2f, horizon - height * 0.18f, x, height.toFloat(), grid)
                x += width * 0.22f
            }

            val red = (palette.background shr 16) and 0xFF
            val green = (palette.background shr 8) and 0xFF
            val blue = palette.background and 0xFF
            val dark = (red * 299 + green * 587 + blue * 114) / 1000 < 140
            if (dark) {
                vignette.shader = RadialGradient(
                    width / 2f, height / 2f, maxOf(width, height) * 0.78f,
                    0x00000000, 0x77000000, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
                vignette.shader = null
            }
            background.shader = null
            glow.shader = null
            return
        }

        background.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            0xFF070311.toInt(), 0xFF02010A.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        glow.shader = RadialGradient(
            width / 2f, height * 0.58f, maxOf(width, height) * 0.72f,
            (accent and 0x00FFFFFF) or 0x24000000, 0x00000000, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

        // A horizon and receding verticals: enough perspective to read as a room, faint enough
        // that it never competes with a board.
        val horizon = height * 0.62f
        grid.strokeWidth = 1f * density
        grid.color = (accent and 0x00FFFFFF) or 0x1A000000
        canvas.drawLine(0f, horizon, width.toFloat(), horizon, grid)
        var x = -width.toFloat()
        while (x < width * 2f) {
            canvas.drawLine(width / 2f, horizon - height * 0.18f, x, height.toFloat(), grid)
            x += width * 0.22f
        }

        vignette.shader = RadialGradient(
            width / 2f, height / 2f, maxOf(width, height) * 0.78f,
            0x00000000, 0x99000000, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
        background.shader = null
        glow.shader = null
        vignette.shader = null
    }
}
