package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.minigames.Minesweeper.Board
import com.prism.launcher.minigames.Minesweeper.Difficulty
import com.prism.launcher.minigames.Minesweeper.Mark
import com.prism.launcher.minigames.Minesweeper.Status
import com.prism.launcher.theme.PrismThemes
import kotlin.math.min

/**
 * Minesweeper, pencilled onto the page.
 *
 * ## Tap reveals, long press marks
 *
 * That is the convention every desktop Minesweeper has used for decades and it survives the move to
 * a thumb: a tap is the common action, so it gets the cheap gesture. The three-state mark the model
 * supports is cycled by repeated long presses -- flag, question, clear -- because a second button
 * does not exist on a phone.
 *
 * ## The board is sized to the view, not the other way round
 *
 * A cell is a square, so the grid is fitted to whichever dimension runs out first and centred. That
 * is what lets the same view serve Beginner on a phone and Expert on a tablet without a second
 * layout.
 */
@SuppressLint("ViewConstructor")
class MinesweeperView(
    context: Context,
    private val difficulty: Difficulty,
    private val onFinished: (won: Boolean) -> Unit,
) : View(context), TopInsetAware, MinigameFeedback {

    private val density = resources.displayMetrics.density
    private val dark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private var board: Board = Minesweeper.newGame(difficulty, System.currentTimeMillis())
    private var reported = false
    private var topInset = 0
    private var timerStartedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Pair<Int, Int>? = null
    private var longFired = false

    private val longPress = Runnable {
        val cell = pressed ?: return@Runnable
        longFired = true
        board = Minesweeper.mark(board, cell.first, cell.second)
        invalidate()
    }

    private val grid = RectF()
    private var cell = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val numbers = intArrayOf(
        0,
        0xFF3F6FA8.toInt(), // 1 blue pencil
        0xFF4E8A57.toInt(), // 2 green
        0xFFC0504D.toInt(), // 3 red
        0xFF6E4A8A.toInt(), // 4 violet
        0xFF9A5A2A.toInt(), // 5 brown
        0xFF2E7F7F.toInt(), // 6 teal
        0xFF3A3A40.toInt(), // 7 graphite
        0xFF7A7A85.toInt(), // 8 light graphite
    )

    private val colors = MinigameTheme.of(context)

    init {
        isClickable = true
        setBackgroundColor(colors.background)
    }

    /**
     * The arcade table, on every theme.
     *
     * The layout is the game's own -- a lit grid, not a notebook -- but the ink comes from the
     * active theme: xOS is its neon palette and every other theme supplies its accent, surface and
     * text. That is what "the theme works here" means; it does not mean every game becomes paper.
     */
    private fun isNeon(): Boolean = true

    override fun applyTopInset(pixels: Int) {
        topInset = pixels
        invalidate()
    }

    // -- Geometry -------------------------------------------------------------

    private fun layoutGrid(): RectF {
        val header = 40f * density + topInset
        val usableWidth = width - 20f * density
        val usableHeight = height - header - 20f * density
        val size = min(usableWidth / board.cols, usableHeight / board.rows)
        val w = size * board.cols
        val h = size * board.rows
        grid.set(
            (width - w) / 2f,
            header + (usableHeight - h) / 2f,
            (width + w) / 2f,
            header + (usableHeight + h) / 2f,
        )
        cell = size
        return grid
    }

    private fun at(x: Float, y: Float): Pair<Int, Int>? {
        if (cell <= 0f) layoutGrid()
        val col = ((x - grid.left) / cell).toInt()
        val row = ((y - grid.top) / cell).toInt()
        if (col !in 0 until board.cols || row !in 0 until board.rows) return null
        return col to row
    }

    // -- Input ----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = at(event.x, event.y)
                longFired = false
                pressed?.let { handler.postDelayed(longPress, 380L) }
                return pressed != null
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val cellAt = pressed
                val up = at(event.x, event.y)
                pressed = null
                if (!longFired && cellAt != null && up == cellAt) tap(cellAt)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                pressed = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun tap(at: Pair<Int, Int>) {
        if (board.status != Status.PLAYING) return
        if (timerStartedAt == 0L) timerStartedAt = System.currentTimeMillis()
        val before = board
        board = if (before.cell(at.first, at.second).revealed) {
            Minesweeper.chord(before, at.first, at.second)
        } else {
            Minesweeper.reveal(before, at.first, at.second)
        }
        if (board.status != Status.PLAYING) report(board.status == Status.WON)
        invalidate()
    }

    override fun outcome(): Boolean? = when (board.status) {
        Status.WON -> true
        Status.LOST -> false
        else -> null
    }

    override fun musicIntensity(): Float {
        if (board.status != Status.PLAYING) return 0f
        // Pressure is how much of the board is open against how long the sweep is taking: an
        // untouched board is calm, and a nearly-finished one has the clock against you.
        val elapsed = if (timerStartedAt == 0L) 0f else (System.currentTimeMillis() - timerStartedAt) / 1000f
        return (0.2f + (elapsed / 180f).coerceAtMost(0.5f)).coerceIn(0f, 0.9f)
    }

    private fun report(won: Boolean) {
        if (reported) return
        reported = true
        handler.postDelayed({ onFinished(won) }, 900L)
    }

    /** A fresh board of the same size, for the page's "New game" action. */
    fun restart() {
        board = Minesweeper.newGame(difficulty, System.currentTimeMillis())
        reported = false
        timerStartedAt = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(longPress)
    }

    // -- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isNeon()) {
            drawNeon(canvas)
            return
        }
        PencilStyle.drawPaper(canvas, width, height, dark, density)
        layoutGrid()

        val graphite = PencilStyle.graphite(dark)
        val light = PencilStyle.graphiteLight(dark)
        ink.color = graphite
        fill.color = PencilStyle.paper(dark)

        canvas.drawText(
            "${Minesweeper.remaining(board)} mines",
            14f * density, 22f * density,
            numberPaint.apply { color = graphite; textSize = 12f * density; textAlign = Paint.Align.LEFT },
        )
        canvas.drawText(
            when (board.status) {
                Status.PLAYING -> "${board.opened}/${board.safeCells} opened"
                Status.WON -> "Cleared in ${board.reveals} reveals"
                Status.LOST -> "Mine. Tap New game."
            },
            14f * density, 36f * density,
            glyphPaint.apply { color = light; textSize = 11f * density; textAlign = Paint.Align.LEFT },
        )

        for (row in 0 until board.rows) for (col in 0 until board.cols) {
            val c = board.cell(col, row)
            val left = grid.left + col * cell
            val top = grid.top + row * cell
            val r = RectF(left + 1f * density, top + 1f * density, left + cell - 1f * density, top + cell - 1f * density)

            if (c.revealed) {
                fill.color = PencilStyle.paper(dark)
                canvas.drawRect(r, fill)
                ink.color = light
                canvas.drawRect(r, ink)
                when {
                    c.mine -> drawMine(canvas, r, c.exploded)
                    c.adjacent > 0 -> {
                        numberPaint.color = numbers[c.adjacent.coerceIn(0, 8)]
                        numberPaint.textSize = cell * 0.58f
                        canvas.drawText(
                            c.adjacent.toString(),
                            r.centerX(),
                            r.centerY() + cell * 0.2f,
                            numberPaint,
                        )
                    }
                }
            } else {
                // An unrevealed cell is a slightly darker square: the page shows the grid as raised
                // paper rather than as another colour, which is what keeps it reading as pencil.
                val paper = PencilStyle.paper(dark)
                fill.color = (70 shl 24) or (paper and 0x00FFFFFF)
                canvas.drawRect(r, fill)
                ink.color = graphite
                canvas.drawRect(r, ink)
                when (c.mark) {
                    Mark.FLAG -> {
                        glyphPaint.color = PencilStyle.RED_PENCIL
                        glyphPaint.textSize = cell * 0.62f
                        canvas.drawText("⚑", r.centerX(), r.centerY() + cell * 0.22f, glyphPaint)
                    }
                    Mark.QUESTION -> {
                        glyphPaint.color = PencilStyle.BLUE_PENCIL
                        glyphPaint.textSize = cell * 0.55f
                        canvas.drawText("?", r.centerX(), r.centerY() + cell * 0.2f, glyphPaint)
                    }
                    Mark.NONE -> Unit
                }
            }
        }

        if (board.status == Status.LOST) {
            // The board is revealed by the model on a loss, so the loop above already drew every
            // mine. Nothing extra: a red overlay would be a second colour scheme in a pencil game.
            Unit
        }
    }

    /**
     * The xOS table: a dark grid inside a glowing frame.
     *
     * The rules are identical to the pencil board; only the material changes. Every colour is a
     * fixed value from the xOS palette rather than a theme lookup, because this is a specific arcade
     * skin and not a general "dark mode" treatment.
     */
    private fun drawNeon(canvas: Canvas) {
        val w = width
        val h = height
        val cyan = colors.accent
        val magenta = colors.accentAlt
        val lime = colors.success
        val amber = if (colors.neon) 0xFFFFB000.toInt() else MinigameTheme.blend(colors.accentAlt, colors.accent, 0.4f)
        val red = colors.danger
        val dim = colors.muted

        paint.fill(canvas, w, h)

        layoutGrid()
        val now = if (timerStartedAt == 0L) 0L else (System.currentTimeMillis() - timerStartedAt) / 1000L
        val header = topInset + 18f * density
        textStyle(18f * density, cyan, Paint.Align.LEFT, bold = true)
        numberPaint.setShadowLayer(10f, 0f, 0f, cyan)
        canvas.drawText("MINESWEEPER", 16f * density, header + 18f * density, numberPaint)
        numberPaint.clearShadowLayer()
        textStyle(10f * density, dim, Paint.Align.LEFT)
        canvas.drawText(
            "${difficulty.label.uppercase()}  //  ${Minesweeper.remaining(board)} MINES  //  " +
                "${board.opened}/${board.safeCells} OPEN  //  ${now}s",
            16f * density, header + 34f * density, numberPaint,
        )

        // Board frame. A double stroke reads as a lit panel rather than a flat rectangle.
        val frame = RectF(grid.left - 7f * density, grid.top - 7f * density, grid.right + 7f * density, grid.bottom + 7f * density)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = (cyan and 0x00FFFFFF) or 0x55000000
        if (colors.neon) paint.setShadowLayer(14f, 0f, 0f, cyan)
        canvas.drawRoundRect(frame, 9f * density, 9f * density, paint)
        paint.clearShadowLayer()
        paint.strokeWidth = 1f * density
        paint.color = (cyan and 0x00FFFFFF) or 0xAA000000.toInt()
        canvas.drawRoundRect(frame, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.FILL

        val gap = 1.8f * density
        for (row in 0 until board.rows) for (col in 0 until board.cols) {
            val c = board.cell(col, row)
            val left = grid.left + col * cell + gap
            val top = grid.top + row * cell + gap
            val cellRect = RectF(left, top, left + cell - gap * 2f, top + cell - gap * 2f)
            paint.style = Paint.Style.FILL
            when {
                c.revealed -> {
                    paint.color = if (c.mine) {
                        MinigameTheme.blend(colors.background, red, 0.16f)
                    } else {
                        MinigameTheme.blend(colors.background, colors.ink, 0.07f)
                    }
                    canvas.drawRoundRect(cellRect, 3f * density, 3f * density, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f * density
                    paint.color = if (c.mine && c.exploded) red else (cyan and 0x00FFFFFF) or 0x55000000
                    canvas.drawRoundRect(cellRect, 3f * density, 3f * density, paint)
                    paint.style = Paint.Style.FILL
                    if (c.mine) {
                        drawNeonMine(canvas, cellRect, c.exploded, red, cyan)
                    } else if (c.adjacent > 0) {
                        numberPaint.color = if (colors.neon) neonNumbers[c.adjacent.coerceIn(0, 8)]
                        else MinigameTheme.blend(colors.ink, colors.accent, 0.5f)
                        numberPaint.textSize = cell * 0.55f
                        numberPaint.textAlign = Paint.Align.CENTER
                        numberPaint.setShadowLayer(8f, 0f, 0f, numberPaint.color)
                        canvas.drawText(c.adjacent.toString(), cellRect.centerX(), cellRect.centerY() + cell * 0.18f, numberPaint)
                        numberPaint.clearShadowLayer()
                    }
                }
                else -> {
                    paint.color = MinigameTheme.blend(colors.board, colors.ink, 0.04f)
                    canvas.drawRoundRect(cellRect, 3f * density, 3f * density, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f * density
                    paint.color = (colors.accent and 0x00FFFFFF) or 0x33000000
                    canvas.drawRoundRect(cellRect, 3f * density, 3f * density, paint)
                    paint.style = Paint.Style.FILL
                    when (c.mark) {
                        Mark.FLAG -> drawNeonFlag(canvas, cellRect, magenta)
                        Mark.QUESTION -> {
                            numberPaint.color = cyan
                            numberPaint.textSize = cell * 0.52f
                            numberPaint.textAlign = Paint.Align.CENTER
                            canvas.drawText("?", cellRect.centerX(), cellRect.centerY() + cell * 0.18f, numberPaint)
                        }
                        Mark.NONE -> Unit
                    }
                }
            }
        }

        // The status line under the board, in the same monospace as the HUD.
        textStyle(10f * density, dim, Paint.Align.LEFT)
        val footer = grid.bottom + 22f * density
        if (footer < h - 4f * density) {
            canvas.drawText(
                when (board.status) {
                    Status.PLAYING -> "TAP OPEN  ·  HOLD MARK  ·  FIRST TAP SAFE"
                    Status.WON -> "GRID CLEARED"
                    Status.LOST -> "SIGNAL LOST — NEW BOARD"
                },
                16f * density, footer, numberPaint,
            )
        }
        if (board.status == Status.WON) {
            numberPaint.color = lime
            numberPaint.setShadowLayer(12f, 0f, 0f, lime)
            textStyle(16f * density, lime, Paint.Align.CENTER, bold = true)
            canvas.drawText("CLEARED", w / 2f, grid.centerY(), numberPaint)
            numberPaint.clearShadowLayer()
        } else if (board.status == Status.LOST) {
            textStyle(16f * density, red, Paint.Align.CENTER, bold = true)
            numberPaint.setShadowLayer(12f, 0f, 0f, red)
            canvas.drawText("DETONATED", w / 2f, grid.centerY(), numberPaint)
            numberPaint.clearShadowLayer()
        }
        numberPaint.textAlign = Paint.Align.LEFT
    }

    private fun Paint.fill(canvas: Canvas, w: Int, h: Int) {
        val mid = MinigameTheme.blend(colors.background, colors.ink, 0.05f)
        shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(colors.background, mid, colors.background),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), this)
        shader = null
        // A bloom in the top corner stops the surface reading as flat black.
        shader = RadialGradient(
            w * 0.82f, h * 0.12f, w * 0.7f,
            intArrayOf((colors.accentAlt and 0x00FFFFFF) or 0x33000000, 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), this)
        shader = null
    }

    private fun textStyle(size: Float, color: Int, align: Paint.Align, bold: Boolean = false) {
        numberPaint.textSize = size
        numberPaint.color = color
        numberPaint.textAlign = align
        if (bold) {
            numberPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        } else {
            numberPaint.typeface = Typeface.MONOSPACE
        }
    }

    private fun drawNeonMine(canvas: Canvas, r: RectF, exploded: Boolean, red: Int, ink: Int) {
        val color = if (exploded) red else ink
        val radius = r.width() * 0.22f
        paint.color = color
        paint.setShadowLayer(10f, 0f, 0f, color)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60).toDouble())
            val dx = (Math.cos(angle) * radius * 1.55).toFloat()
            val dy = (Math.sin(angle) * radius * 1.55).toFloat()
            canvas.drawLine(r.centerX() + dx, r.centerY() + dy, r.centerX() - dx, r.centerY() - dy, paint)
        }
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawNeonFlag(canvas: Canvas, r: RectF, color: Int) {
        paint.color = color
        paint.setShadowLayer(8f, 0f, 0f, color)
        val path = Path()
        path.moveTo(r.centerX() - r.width() * 0.15f, r.centerY() - r.height() * 0.22f)
        path.lineTo(r.centerX() + r.width() * 0.22f, r.centerY() - r.height() * 0.04f)
        path.lineTo(r.centerX() - r.width() * 0.15f, r.centerY() + r.height() * 0.10f)
        path.close()
        canvas.drawPath(path, paint)
        paint.strokeWidth = 1.6f * density
        paint.style = Paint.Style.STROKE
        canvas.drawLine(r.centerX() - r.width() * 0.15f, r.centerY() - r.height() * 0.22f, r.centerX() - r.width() * 0.15f, r.centerY() + r.height() * 0.24f, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private val neonNumbers = intArrayOf(
        0, 0xFF39E7FF.toInt(), 0xFF9DFF3C.toInt(), 0xFFFF3B5C.toInt(),
        0xFFFFB000.toInt(), 0xFFFF8FE3.toInt(), 0xFF9B4DFF.toInt(),
        0xFFE8E8FF.toInt(), 0xFFFFFFFF.toInt(),
    )

    private fun drawMine(canvas: Canvas, r: RectF, exploded: Boolean) {
        val radius = cell * 0.24f
        fill.color = if (exploded) PencilStyle.RED_PENCIL else PencilStyle.graphite(dark)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fill)
        ink.color = if (exploded) PencilStyle.RED_PENCIL else PencilStyle.graphite(dark)
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60).toDouble())
            val dx = (Math.cos(angle) * radius * 1.5).toFloat()
            val dy = (Math.sin(angle) * radius * 1.5).toFloat()
            canvas.drawLine(
                r.centerX() + dx, r.centerY() + dy,
                r.centerX() - dx, r.centerY() - dy,
                ink,
            )
        }
    }
}
