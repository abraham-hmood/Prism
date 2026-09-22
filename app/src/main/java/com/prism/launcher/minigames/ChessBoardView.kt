package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A chess board drawn on the page, with the pieces written in.
 *
 * ## Why the pieces are glyphs and not drawings
 *
 * Everything else on this page is drawn in pencil, and the first version of this drew the pieces
 * that way too. It was unreadable: a hand-drawn knight and a hand-drawn bishop are the same
 * silhouette at 30dp, and a chess player has to identify six pieces at a glance or the game is
 * unplayable. The Unicode chess figures are the one place where legibility beats the aesthetic, so
 * they are drawn in graphite on a hatched board and the board itself carries the style.
 *
 * ## Interaction
 *
 * Tap a piece, tap a destination. Not drag: a finger dragging a piece covers the square it is
 * moving to, and on a phone board the destination is the thing you most need to see. Legal
 * destinations for the selected piece are marked, because a board that lets you try illegal moves
 * and silently refuses them is a board that feels broken.
 */
@SuppressLint("ViewConstructor")
class ChessBoardView(
    context: Context,
    private val difficulty: Chess.Difficulty,
    private val match: MinigameMesh.Match?,
    private val onStatus: (String) -> Unit,
    private val onFinished: (won: Boolean?) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Offline the player is white; online the match decides. */
    private val mySide: Chess.Colour =
        if (match == null || match.youAreFirst) Chess.Colour.WHITE else Chess.Colour.BLACK

    private var position = Chess.startingPosition()
    private var selected: Int? = null
    private var legalFromSelected: List<Chess.Move> = emptyList()
    private var lastMove: Chess.Move? = null
    private var thinking = false
    private var finished = false
    private val moveList = mutableListOf<String>()

    /** The move this device last sent, repeated until the opponent answers. UDP drops packets. */
    private var unacknowledged: String? = null
    private var lastResend = 0L

    init {
        isClickable = true
        if (match != null) {
            MinigameMesh.listenForMoves { peerIp, payload ->
                if (peerIp != match.peerIp) return@listenForMoves
                post { receiveRemoteMove(payload) }
            }
        }
        announce()
        maybeMoveComputer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MinigameMesh.stopListening()
    }

    // -- Turn handling --------------------------------------------------------

    private fun announce() {
        val outcome = Chess.outcome(position)
        onStatus(
            when {
                outcome is Chess.Outcome.Checkmate ->
                    if (outcome.winner == mySide) "Checkmate. You win." else "Checkmate. You lose."
                outcome is Chess.Outcome.Stalemate -> "Stalemate — a draw."
                outcome is Chess.Outcome.FiftyMove -> "Draw: fifty moves without a pawn or a capture."
                outcome is Chess.Outcome.Threefold -> "Draw by repetition."
                outcome is Chess.Outcome.InsufficientMaterial -> "Draw: not enough material to mate."
                thinking -> "Thinking…"
                position.sideToMove == mySide ->
                    if (Chess.isInCheck(position, mySide)) "Your move — you are in check." else "Your move."
                match != null -> "Waiting for your opponent…"
                else -> "${difficulty.label} is thinking…"
            }
        )

        if (outcome.isOver && !finished) {
            finished = true
            val won = when (outcome) {
                is Chess.Outcome.Checkmate -> outcome.winner == mySide
                else -> null
            }
            won?.let { MinigameStore.recordChess(it) }
            postDelayed({ onFinished(won) }, 1_200)
        }
    }

    private fun play(move: Chess.Move) {
        moveList.add(Chess.describe(position, move))
        position = Chess.apply(position, move)
        lastMove = move
        selected = null
        legalFromSelected = emptyList()
        invalidate()
        announce()
        if (!finished) maybeMoveComputer()
    }

    /**
     * Lets the computer answer, off the UI thread.
     *
     * A depth-5 search is tens of thousands of positions and takes long enough at the top of the
     * game to drop frames. It runs on a background thread and posts the move back, which also
     * means the board stays responsive enough to show "thinking" rather than freezing.
     */
    private fun maybeMoveComputer() {
        if (match != null || finished) return
        if (position.sideToMove == mySide) return

        thinking = true
        announce()
        val snapshot = position
        val seed = snapshot.key()
        Thread({
            val move = Chess.bestMove(snapshot, difficulty, seed)
            post {
                thinking = false
                if (move != null && position == snapshot) play(move) else announce()
            }
        }, "chess-search").start()
    }

    private fun receiveRemoteMove(payload: String) {
        if (payload == "ack") {
            unacknowledged = null
            return
        }
        val move = Chess.decode(position, payload) ?: return
        unacknowledged = null
        match?.let { MinigameMesh.sendMove(it.peerIp, "ack") }
        play(move)
    }

    /** Resends the last move if the opponent has not answered. Called from the draw loop. */
    private fun resendIfNeeded() {
        val pending = unacknowledged ?: return
        val peer = match?.peerIp ?: return
        val now = System.currentTimeMillis()
        if (now - lastResend < 1_200) return
        lastResend = now
        MinigameMesh.sendMove(peer, pending)
    }

    // -- Input ----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (finished || thinking) return true
        if (position.sideToMove != mySide) return true

        val square = squareAt(event.x, event.y) ?: return true

        val chosen = legalFromSelected.filter { it.to == square }
        if (chosen.isNotEmpty()) {
            val move = if (chosen.size > 1) {
                // Several moves to the same square means a promotion. Offering a dialog for the
                // one-in-fifty case where a player wants a knight is worth it: under-promotion is
                // occasionally the only move that wins, and an engine that always queens is wrong.
                askPromotion(chosen)
                return true
            } else {
                chosen.first()
            }
            sendAndPlay(move)
            return true
        }

        val piece = position.pieceAt(square)
        if (piece != 0 && (piece > 0) == (mySide == Chess.Colour.WHITE)) {
            selected = square
            legalFromSelected = Chess.legalMoves(position).filter { it.from == square }
        } else {
            selected = null
            legalFromSelected = emptyList()
        }
        invalidate()
        return true
    }

    private fun sendAndPlay(move: Chess.Move) {
        match?.let {
            val wire = Chess.encode(move)
            unacknowledged = wire
            lastResend = System.currentTimeMillis()
            MinigameMesh.sendMove(it.peerIp, wire)
        }
        play(move)
    }

    private fun askPromotion(options: List<Chess.Move>) {
        val labels = options.map {
            when (it.promotion) {
                Chess.QUEEN -> "Queen"; Chess.ROOK -> "Rook"
                Chess.BISHOP -> "Bishop"; else -> "Knight"
            }
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Promote to")
            .setItems(labels) { _, which -> sendAndPlay(options[which]) }
            .setCancelable(true)
            .show()
    }

    // -- Geometry -------------------------------------------------------------

    private fun boardRect(): RectF {
        val pad = 16f * density
        val size = minOf(width - pad * 2, height - pad * 2 - 30f * density)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        return RectF(left, top, left + size, top + size)
    }

    private fun squareAt(x: Float, y: Float): Int? {
        val board = boardRect()
        if (!board.contains(x, y)) return null
        val cell = board.width() / 8f
        val col = ((x - board.left) / cell).toInt().coerceIn(0, 7)
        val row = ((y - board.top) / cell).toInt().coerceIn(0, 7)
        // Black sees the board from the other side, which is not a nicety — playing chess upside
        // down is genuinely hard and everybody gets to look at their own pieces from the front.
        val file = if (mySide == Chess.Colour.WHITE) col else 7 - col
        val rank = if (mySide == Chess.Colour.WHITE) 7 - row else row
        return Chess.index(file, rank)
    }

    private fun cellRect(index: Int, board: RectF): RectF {
        val cell = board.width() / 8f
        val file = Chess.fileOf(index)
        val rank = Chess.rankOf(index)
        val col = if (mySide == Chess.Colour.WHITE) file else 7 - file
        val row = if (mySide == Chess.Colour.WHITE) 7 - rank else rank
        return RectF(
            board.left + col * cell, board.top + row * cell,
            board.left + (col + 1) * cell, board.top + (row + 1) * cell,
        )
    }

    // -- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        resendIfNeeded()

        PencilStyle.drawPaper(canvas, width, height, dark, density)
        val board = boardRect()
        val cell = board.width() / 8f
        val ink = PencilStyle.pencil(2f * density, PencilStyle.graphite(dark))

        PencilStyle.rect(canvas, board, ink, seed = 1, amount = 1.5f)

        // The dark squares are hatched rather than filled — the whole point of the style.
        val hatchPaint = PencilStyle.pencil(1.1f * density, PencilStyle.graphiteLight(dark), alpha = 150)
        for (index in 0 until 64) {
            val file = Chess.fileOf(index)
            val rank = Chess.rankOf(index)
            if ((file + rank) % 2 != 0) continue
            PencilStyle.hatch(canvas, cellRect(index, board), hatchPaint, seed = index.toLong(), spacing = 5.5f * density)
        }

        // The grid.
        for (i in 1 until 8) {
            PencilStyle.line(canvas, board.left + i * cell, board.top, board.left + i * cell, board.bottom, ink, 10L + i, 1f)
            PencilStyle.line(canvas, board.left, board.top + i * cell, board.right, board.top + i * cell, ink, 30L + i, 1f)
        }

        // What just happened, circled in red.
        lastMove?.let { move ->
            val red = PencilStyle.pencil(2f * density, PencilStyle.RED_PENCIL, alpha = 180)
            PencilStyle.circleAround(canvas, cellRect(move.from, board), red, seed = 61)
            PencilStyle.circleAround(canvas, cellRect(move.to, board), red, seed = 62)
        }

        // Where the selected piece can go.
        selected?.let { from ->
            val blue = PencilStyle.pencil(2.4f * density, PencilStyle.BLUE_PENCIL)
            PencilStyle.rect(canvas, cellRect(from, board), blue, seed = 70, amount = 1.2f)
            legalFromSelected.map { it.to }.distinct().forEach { to ->
                val r = cellRect(to, board)
                if (position.pieceAt(to) != 0 || legalFromSelected.any { it.to == to && it.isEnPassant }) {
                    // A capture is a ring round the square; a quiet move is a dot in it. The
                    // distinction is what tells a player whether a square is occupied without
                    // squinting at the piece under the marker.
                    PencilStyle.circleAround(canvas, r, blue, seed = 80 + to.toLong())
                } else {
                    canvas.drawCircle(
                        r.centerX(), r.centerY(), cell * 0.13f,
                        PencilStyle.shading(PencilStyle.BLUE_PENCIL, alpha = 110),
                    )
                }
            }
        }

        // The pieces.
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cell * 0.74f
            textAlign = Paint.Align.CENTER
            color = PencilStyle.graphite(dark)
        }
        for (index in 0 until 64) {
            val piece = position.pieceAt(index)
            if (piece == 0) continue
            val r = cellRect(index, board)
            // Black pieces are the solid glyphs, white the outlined ones — on paper, that reads
            // correctly whichever way round the theme is.
            glyphPaint.color = if (piece > 0) PencilStyle.graphite(dark) else PencilStyle.graphite(dark)
            canvas.drawText(
                Chess.glyph(piece), r.centerX(), r.centerY() + cell * 0.26f, glyphPaint,
            )
        }

        // Coordinates down the side, small, in the margin style.
        val coord = PencilStyle.textPaint(10f * density, dark, PencilStyle.graphiteLight(dark))
        for (i in 0 until 8) {
            val rank = if (mySide == Chess.Colour.WHITE) 8 - i else i + 1
            canvas.drawText("$rank", board.left - 11f * density, board.top + i * cell + cell * 0.6f, coord)
            val fileChar = if (mySide == Chess.Colour.WHITE) 'a' + i else 'h' - i
            canvas.drawText("$fileChar", board.left + i * cell + cell * 0.5f, board.bottom + 15f * density, coord)
        }

        if (unacknowledged != null) {
            val note = PencilStyle.textPaint(11f * density, dark, PencilStyle.RED_PENCIL)
            canvas.drawText("sending your move…", board.left, board.top - 8f * density, note)
        }

        if (match != null || thinking) postInvalidateDelayed(400)
    }

    /** The move list, for the panel beside the board. */
    fun moves(): List<String> = moveList.toList()

    fun resign() {
        if (finished) return
        finished = true
        MinigameStore.recordChess(false)
        onStatus("You resigned.")
        onFinished(false)
    }
}
