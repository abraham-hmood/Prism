package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.minigames.Minesweeper.Board
import com.prism.launcher.minigames.Minesweeper.Difficulty
import com.prism.launcher.minigames.Minesweeper.Mark
import com.prism.launcher.minigames.Minesweeper.Status
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
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private var board: Board = Minesweeper.newGame(difficulty, System.currentTimeMillis())
    private var reported = false

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

    init {
        isClickable = true
        setBackgroundColor(PencilStyle.paper(dark))
    }

    // -- Geometry -------------------------------------------------------------

    private fun layoutGrid(): RectF {
        val header = 40f * density
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
        val before = board
        board = if (before.cell(at.first, at.second).revealed) {
            Minesweeper.chord(before, at.first, at.second)
        } else {
            Minesweeper.reveal(before, at.first, at.second)
        }
        if (board.status != Status.PLAYING) report(board.status == Status.WON)
        invalidate()
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
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(longPress)
    }

    // -- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
