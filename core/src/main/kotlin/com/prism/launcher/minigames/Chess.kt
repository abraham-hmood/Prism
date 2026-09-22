package com.prism.launcher.minigames

/**
 * Chess: the whole game, rules included.
 *
 * ## Why all of it
 *
 * A chess implementation that skips en passant, or under-promotion, or the fifty-move rule, is a
 * chess implementation that loses a game somebody was winning. Those rules are rare exactly often
 * enough to matter: a player who castles out of check once will never trust the board again. So
 * this has castling with all four of its conditions, en passant with its one-move window,
 * promotion to any of four pieces, stalemate, the fifty-move rule and threefold repetition.
 *
 * ## Board representation
 *
 * A flat `IntArray(64)`, index `rank * 8 + file`, with a1 at index 0. Squares hold a signed piece
 * code: positive is white, negative is black, zero is empty. Bitboards would be faster and are not
 * remotely necessary — the search below looks at a few hundred thousand positions at most, on a
 * phone, for a game of chess against a person who is also playing pong.
 *
 * ## The opponent
 *
 * Negamax with alpha-beta, material and piece-square evaluation, and a quiescence search over
 * captures so it stops hanging its queen at the horizon. Strength is a search depth, exposed as
 * [Difficulty], because a depth is a thing that can honestly be described: "looks three moves
 * ahead" is true, and "1200 Elo" would not be.
 */
object Chess {

    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    enum class Colour { WHITE, BLACK;
        val other: Colour get() = if (this == WHITE) BLACK else WHITE
        val sign: Int get() = if (this == WHITE) 1 else -1
    }

    enum class Difficulty(val label: String, val depth: Int, val blunderChance: Double) {
        GENTLE("Gentle", depth = 2, blunderChance = 0.22),
        STEADY("Steady", depth = 3, blunderChance = 0.06),
        SHARP("Sharp", depth = 4, blunderChance = 0.0),
        RUTHLESS("Ruthless", depth = 5, blunderChance = 0.0),
    }

    /**
     * One move.
     *
     * [promotion] is the piece a pawn becomes, or [EMPTY]. The three flags are carried rather than
     * recomputed because a move has to be applicable to the position it was generated for without
     * re-deriving why it is legal — that is where en passant bugs live.
     */
    data class Move(
        val from: Int,
        val to: Int,
        val promotion: Int = EMPTY,
        val isCastle: Boolean = false,
        val isEnPassant: Boolean = false,
    ) {
        override fun toString(): String = square(from) + square(to) +
            when (promotion) {
                QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; KNIGHT -> "n"; else -> ""
            }
    }

    /**
     * A position, and everything needed to know what is legal in it.
     *
     * Immutable: [apply] returns a new one. The search below makes and unmakes a lot of moves, and
     * a copy of a 64-int array plus eight fields is cheap enough that the correctness is worth far
     * more than the allocation — an unmake that forgets to restore the en passant square is a bug
     * that shows up as one illegal move in a thousand games.
     */
    data class Position(
        val squares: IntArray,
        val sideToMove: Colour = Colour.WHITE,
        val whiteCanCastleKing: Boolean = true,
        val whiteCanCastleQueen: Boolean = true,
        val blackCanCastleKing: Boolean = true,
        val blackCanCastleQueen: Boolean = true,
        /** The square a pawn may be captured on this move, or -1. */
        val enPassant: Int = -1,
        val halfmoveClock: Int = 0,
        val fullmove: Int = 1,
        /** Zobrist-ish history for threefold. Positions, not moves. */
        val history: List<Long> = emptyList(),
    ) {
        fun pieceAt(index: Int): Int = squares[index]

        fun colourAt(index: Int): Colour? = when {
            squares[index] > 0 -> Colour.WHITE
            squares[index] < 0 -> Colour.BLACK
            else -> null
        }

        /** A cheap position key. Not cryptographic; it only has to distinguish repetitions. */
        fun key(): Long {
            var h = -0x61c8864680b583ebL
            for (i in 0 until 64) {
                h = h * 0x100000001b3L + (squares[i] + 7)
            }
            h = h * 31 + sideToMove.ordinal
            h = h * 31 + (if (whiteCanCastleKing) 1 else 0)
            h = h * 31 + (if (whiteCanCastleQueen) 2 else 0)
            h = h * 31 + (if (blackCanCastleKing) 4 else 0)
            h = h * 31 + (if (blackCanCastleQueen) 8 else 0)
            h = h * 31 + enPassant
            return h
        }

        override fun equals(other: Any?): Boolean =
            other is Position && key() == other.key()

        override fun hashCode(): Int = key().hashCode()
    }

    // -- Board helpers --------------------------------------------------------

    fun index(file: Int, rank: Int): Int = rank * 8 + file
    fun fileOf(index: Int): Int = index % 8
    fun rankOf(index: Int): Int = index / 8

    fun square(index: Int): String = "${'a' + fileOf(index)}${rankOf(index) + 1}"

    fun parseSquare(text: String): Int? {
        if (text.length != 2) return null
        val f = text[0] - 'a'
        val r = text[1] - '1'
        if (f !in 0..7 || r !in 0..7) return null
        return index(f, r)
    }

    fun startingPosition(): Position {
        val s = IntArray(64)
        val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        for (f in 0 until 8) {
            s[index(f, 0)] = back[f]
            s[index(f, 1)] = PAWN
            s[index(f, 6)] = -PAWN
            s[index(f, 7)] = -back[f]
        }
        val p = Position(s)
        return p.copy(history = listOf(p.key()))
    }

    // -- Move generation ------------------------------------------------------

    private val KNIGHT_JUMPS = intArrayOf(-17, -15, -10, -6, 6, 10, 15, 17)
    private val KING_STEPS = intArrayOf(-9, -8, -7, -1, 1, 7, 8, 9)
    private val ROOK_DIRS = intArrayOf(-8, -1, 1, 8)
    private val BISHOP_DIRS = intArrayOf(-9, -7, 7, 9)

    /** Moves that are legal: generated pseudo-legally, then filtered by whether they leave a check. */
    fun legalMoves(position: Position): List<Move> =
        pseudoLegal(position).filter { move ->
            val after = applyUnchecked(position, move)
            !isInCheck(after, position.sideToMove)
        }

    /**
     * Everything the pieces could do, ignoring whether it exposes the king.
     *
     * Split from [legalMoves] because the search wants both: legality filtering costs a board copy
     * per move, and a node that is going to fail high does not need it.
     */
    fun pseudoLegal(position: Position): List<Move> {
        val moves = ArrayList<Move>(48)
        val me = position.sideToMove
        val sign = me.sign

        for (from in 0 until 64) {
            val piece = position.squares[from]
            if (piece == EMPTY || (piece > 0) != (me == Colour.WHITE)) continue
            when (kotlin.math.abs(piece)) {
                PAWN -> pawnMoves(position, from, sign, moves)
                KNIGHT -> KNIGHT_JUMPS.forEach { step -> jump(position, from, step, me, moves) }
                BISHOP -> BISHOP_DIRS.forEach { d -> slide(position, from, d, me, moves) }
                ROOK -> ROOK_DIRS.forEach { d -> slide(position, from, d, me, moves) }
                QUEEN -> {
                    ROOK_DIRS.forEach { d -> slide(position, from, d, me, moves) }
                    BISHOP_DIRS.forEach { d -> slide(position, from, d, me, moves) }
                }
                KING -> {
                    KING_STEPS.forEach { step -> jump(position, from, step, me, moves) }
                    castles(position, from, me, moves)
                }
            }
        }
        return moves
    }

    /** True when [step] from [from] stays on the board — catches the file wrap that kills naive 0x88-less code. */
    private fun onBoard(from: Int, to: Int): Boolean {
        if (to !in 0..63) return false
        val fileJump = kotlin.math.abs(fileOf(to) - fileOf(from))
        return fileJump <= 2
    }

    private fun jump(position: Position, from: Int, step: Int, me: Colour, out: MutableList<Move>) {
        val to = from + step
        if (!onBoard(from, to)) return
        if (position.colourAt(to) == me) return
        out.add(Move(from, to))
    }

    private fun slide(position: Position, from: Int, dir: Int, me: Colour, out: MutableList<Move>) {
        var prev = from
        var to = from + dir
        while (onBoard(prev, to)) {
            val occupant = position.colourAt(to)
            if (occupant == me) return
            out.add(Move(from, to))
            if (occupant != null) return
            prev = to
            to += dir
        }
    }

    private fun pawnMoves(position: Position, from: Int, sign: Int, out: MutableList<Move>) {
        val forward = from + 8 * sign
        val startRank = if (sign > 0) 1 else 6
        val lastRank = if (sign > 0) 7 else 0

        if (forward in 0..63 && position.squares[forward] == EMPTY) {
            addPawnMove(from, forward, lastRank, out)
            val double = from + 16 * sign
            if (rankOf(from) == startRank && double in 0..63 && position.squares[double] == EMPTY) {
                out.add(Move(from, double))
            }
        }

        listOf(from + 7 * sign, from + 9 * sign).forEach { to ->
            if (!onBoard(from, to)) return@forEach
            if (kotlin.math.abs(fileOf(to) - fileOf(from)) != 1) return@forEach
            val victim = position.squares[to]
            val mine = sign > 0
            if (victim != EMPTY && (victim > 0) != mine) {
                addPawnMove(from, to, lastRank, out)
            } else if (to == position.enPassant && victim == EMPTY) {
                out.add(Move(from, to, isEnPassant = true))
            }
        }
    }

    private fun addPawnMove(from: Int, to: Int, lastRank: Int, out: MutableList<Move>) {
        if (rankOf(to) == lastRank) {
            // Under-promotion is legal and occasionally the only winning move.
            listOf(QUEEN, ROOK, BISHOP, KNIGHT).forEach { out.add(Move(from, to, promotion = it)) }
        } else {
            out.add(Move(from, to))
        }
    }

    /**
     * Castling, with all four conditions checked here rather than left to the legality filter.
     *
     * The filter catches "moves into check". It cannot catch "moves THROUGH check", because the
     * king never stands on the crossed square in the resulting position — which is precisely the
     * rule everybody's first chess engine gets wrong.
     */
    private fun castles(position: Position, from: Int, me: Colour, out: MutableList<Move>) {
        val home = if (me == Colour.WHITE) 4 else 60
        if (from != home) return
        if (isInCheck(position, me)) return

        val kingSide = if (me == Colour.WHITE) position.whiteCanCastleKing else position.blackCanCastleKing
        val queenSide = if (me == Colour.WHITE) position.whiteCanCastleQueen else position.blackCanCastleQueen
        val rookPiece = ROOK * me.sign

        if (kingSide &&
            position.squares[home + 1] == EMPTY && position.squares[home + 2] == EMPTY &&
            position.squares[home + 3] == rookPiece &&
            !isAttacked(position, home + 1, me.other)
        ) {
            out.add(Move(from, home + 2, isCastle = true))
        }
        if (queenSide &&
            position.squares[home - 1] == EMPTY && position.squares[home - 2] == EMPTY &&
            position.squares[home - 3] == EMPTY &&
            position.squares[home - 4] == rookPiece &&
            !isAttacked(position, home - 1, me.other)
        ) {
            out.add(Move(from, home - 2, isCastle = true))
        }
    }

    // -- Attack detection -----------------------------------------------------

    fun kingSquare(position: Position, colour: Colour): Int {
        val want = KING * colour.sign
        for (i in 0 until 64) if (position.squares[i] == want) return i
        return -1
    }

    fun isInCheck(position: Position, colour: Colour): Boolean {
        val king = kingSquare(position, colour)
        if (king < 0) return false
        return isAttacked(position, king, colour.other)
    }

    /** Whether [by] attacks [target]. Walks outward from the square rather than generating moves. */
    fun isAttacked(position: Position, target: Int, by: Colour): Boolean {
        val sign = by.sign

        // Pawns attack toward their own direction, so we look backwards from the target.
        listOf(target - 7 * sign, target - 9 * sign).forEach { from ->
            if (from in 0..63 && kotlin.math.abs(fileOf(from) - fileOf(target)) == 1 &&
                position.squares[from] == PAWN * sign
            ) return true
        }

        KNIGHT_JUMPS.forEach { step ->
            val from = target + step
            if (onBoard(target, from) && position.squares[from] == KNIGHT * sign) return true
        }

        KING_STEPS.forEach { step ->
            val from = target + step
            if (onBoard(target, from) && position.squares[from] == KING * sign) return true
        }

        ROOK_DIRS.forEach { dir ->
            if (raysHit(position, target, dir, sign, ROOK)) return true
        }
        BISHOP_DIRS.forEach { dir ->
            if (raysHit(position, target, dir, sign, BISHOP)) return true
        }
        return false
    }

    private fun raysHit(position: Position, from: Int, dir: Int, sign: Int, straightOrDiagonal: Int): Boolean {
        var prev = from
        var at = from + dir
        while (onBoard(prev, at)) {
            val piece = position.squares[at]
            if (piece != EMPTY) {
                val kind = kotlin.math.abs(piece)
                val sameSide = (piece > 0) == (sign > 0)
                return sameSide && (kind == straightOrDiagonal || kind == QUEEN)
            }
            prev = at
            at += dir
        }
        return false
    }

    // -- Applying a move ------------------------------------------------------

    fun apply(position: Position, move: Move): Position {
        val next = applyUnchecked(position, move)
        return next.copy(history = position.history + next.key())
    }

    private fun applyUnchecked(position: Position, move: Move): Position {
        val s = position.squares.copyOf()
        val piece = s[move.from]
        val kind = kotlin.math.abs(piece)
        val me = position.sideToMove
        val captured = s[move.to]

        s[move.from] = EMPTY
        s[move.to] = if (move.promotion != EMPTY) move.promotion * me.sign else piece

        if (move.isEnPassant) {
            s[move.to - 8 * me.sign] = EMPTY
        }

        if (move.isCastle) {
            val home = if (me == Colour.WHITE) 4 else 60
            if (move.to == home + 2) {
                s[home + 1] = s[home + 3]; s[home + 3] = EMPTY
            } else {
                s[home - 1] = s[home - 4]; s[home - 4] = EMPTY
            }
        }

        // Castling rights die when the king or a rook moves, and when a rook is captured on its
        // home square — the second one is the case that is easy to forget and produces a castle
        // with a rook that is no longer there.
        var wk = position.whiteCanCastleKing
        var wq = position.whiteCanCastleQueen
        var bk = position.blackCanCastleKing
        var bq = position.blackCanCastleQueen
        if (kind == KING) {
            if (me == Colour.WHITE) { wk = false; wq = false } else { bk = false; bq = false }
        }
        if (move.from == 0 || move.to == 0) wq = false
        if (move.from == 7 || move.to == 7) wk = false
        if (move.from == 56 || move.to == 56) bq = false
        if (move.from == 63 || move.to == 63) bk = false

        val enPassant =
            if (kind == PAWN && kotlin.math.abs(move.to - move.from) == 16) (move.from + move.to) / 2
            else -1

        val reset = kind == PAWN || captured != EMPTY || move.isEnPassant

        return position.copy(
            squares = s,
            sideToMove = me.other,
            whiteCanCastleKing = wk,
            whiteCanCastleQueen = wq,
            blackCanCastleKing = bk,
            blackCanCastleQueen = bq,
            enPassant = enPassant,
            halfmoveClock = if (reset) 0 else position.halfmoveClock + 1,
            fullmove = if (me == Colour.BLACK) position.fullmove + 1 else position.fullmove,
        )
    }

    // -- Outcome --------------------------------------------------------------

    sealed interface Outcome {
        data object InPlay : Outcome
        data class Checkmate(val winner: Colour) : Outcome
        data object Stalemate : Outcome
        data object FiftyMove : Outcome
        data object Threefold : Outcome
        data object InsufficientMaterial : Outcome

        val isOver: Boolean get() = this != InPlay
    }

    fun outcome(position: Position): Outcome {
        if (legalMoves(position).isEmpty()) {
            return if (isInCheck(position, position.sideToMove)) {
                Outcome.Checkmate(position.sideToMove.other)
            } else {
                Outcome.Stalemate
            }
        }
        if (position.halfmoveClock >= 100) return Outcome.FiftyMove
        val key = position.key()
        if (position.history.count { it == key } >= 3) return Outcome.Threefold
        if (insufficientMaterial(position)) return Outcome.InsufficientMaterial
        return Outcome.InPlay
    }

    /** King versus king, and king plus one minor piece. Anything else can, in principle, mate. */
    private fun insufficientMaterial(position: Position): Boolean {
        val pieces = position.squares.filter { it != EMPTY }.map { kotlin.math.abs(it) }
        if (pieces.any { it == PAWN || it == ROOK || it == QUEEN }) return false
        val minors = pieces.count { it == KNIGHT || it == BISHOP }
        return minors <= 1
    }

    // -- Evaluation and search ------------------------------------------------

    private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

    /**
     * Piece-square tables, from white's point of view, a1 first.
     *
     * The standard simplified set. They are what stops the engine opening with a4 and h4 — material
     * alone has no opinion about where a knight belongs, and a knight on the rim is how a two-ply
     * search loses to a beginner.
     */
    private val PAWN_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, -20, -20, 10, 10, 5,
        5, -5, -10, 0, 0, -10, -5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, 5, 10, 25, 25, 10, 5, 5,
        10, 10, 20, 30, 30, 20, 10, 10,
        50, 50, 50, 50, 50, 50, 50, 50,
        0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val KNIGHT_TABLE = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50,
    )
    private val BISHOP_TABLE = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -20, -10, -10, -10, -10, -10, -10, -20,
    )
    private val ROOK_TABLE = intArrayOf(
        0, 0, 0, 5, 5, 0, 0, 0,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        5, 10, 10, 10, 10, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val QUEEN_TABLE = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -10, 5, 5, 5, 5, 5, 0, -10,
        0, 0, 5, 5, 5, 5, 0, -5,
        -5, 0, 5, 5, 5, 5, 0, -5,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20,
    )
    private val KING_TABLE = intArrayOf(
        20, 30, 10, 0, 0, 10, 30, 20,
        20, 20, 0, 0, 0, 0, 20, 20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
    )

    private fun tableFor(kind: Int): IntArray = when (kind) {
        PAWN -> PAWN_TABLE
        KNIGHT -> KNIGHT_TABLE
        BISHOP -> BISHOP_TABLE
        ROOK -> ROOK_TABLE
        QUEEN -> QUEEN_TABLE
        else -> KING_TABLE
    }

    /** Centipawns, from the side to move's point of view — negamax wants it that way. */
    fun evaluate(position: Position): Int {
        var score = 0
        for (i in 0 until 64) {
            val piece = position.squares[i]
            if (piece == EMPTY) continue
            val kind = kotlin.math.abs(piece)
            val white = piece > 0
            // The tables are written from white's side, so black reads them mirrored.
            val squareBonus = tableFor(kind)[if (white) i else (56 - (i / 8) * 8 + i % 8)]
            val value = VALUE[kind] + squareBonus
            score += if (white) value else -value
        }
        return score * position.sideToMove.sign
    }

    /**
     * Picks a move.
     *
     * [seed] makes the choice reproducible, which matters for a mesh game where both devices should
     * agree about what the computer did, and for the blunder roll at the gentler settings — a
     * "gentle" opponent that plays perfectly is not gentle, and one that blunders at random is not
     * reproducible.
     */
    fun bestMove(position: Position, difficulty: Difficulty, seed: Long): Move? {
        val moves = orderMoves(position, legalMoves(position))
        if (moves.isEmpty()) return null
        val rng = Rng(seed)

        if (difficulty.blunderChance > 0 && rng.chance(difficulty.blunderChance)) {
            // A real blunder: the worst-but-one move, not a random legal one. Random includes
            // hanging the king's rook for nothing, which reads as broken rather than as weak.
            val scored = moves.map { it to -negamax(apply(position, it), 1, -INF, INF) }
            return scored.sortedBy { it.second }.let { it[(it.size / 4).coerceAtMost(it.size - 1)] }.first
        }

        return bestLine(position, difficulty.depth)?.first
    }

    /**
     * The engine's own choice, full strength, and how good it thinks the position is for whoever
     * is about to move.
     *
     * This is [bestMove] without the blunder roll, and with the score it found along the way kept
     * rather than thrown away. That score is what a post-game review needs: "how good was the best
     * move here" is the yardstick every played move is measured against.
     */
    fun bestLine(position: Position, depth: Int): Pair<Move, Int>? {
        val moves = orderMoves(position, legalMoves(position))
        if (moves.isEmpty()) return null
        var best = moves.first()
        var bestScore = -INF
        var alpha = -INF
        moves.forEach { move ->
            val score = -negamax(apply(position, move), depth - 1, -INF, -alpha)
            if (score > bestScore) {
                bestScore = score
                best = move
            }
            if (score > alpha) alpha = score
        }
        return best to bestScore
    }

    /**
     * How good [position] is for the side to move, searched rather than read straight off the
     * board.
     *
     * [evaluate] is the leaf function every search already uses and is noisy on its own -- it does
     * not know a queen is hanging next move. This runs the same negamax [bestMove] does and hands
     * back what it found, which is what a review has to compare a played move's own resulting score
     * against to say how much it cost.
     */
    fun searchEval(position: Position, depth: Int): Int {
        if (legalMoves(position).isEmpty()) {
            return if (isInCheck(position, position.sideToMove)) -INF else 0
        }
        if (depth <= 0) return evaluate(position)
        return negamax(position, depth, -INF, INF)
    }

    private const val INF = 1_000_000

    private fun negamax(position: Position, depth: Int, alphaIn: Int, beta: Int): Int {
        var alpha = alphaIn
        if (depth <= 0) return quiescence(position, alpha, beta)

        val moves = orderMoves(position, legalMoves(position))
        if (moves.isEmpty()) {
            return if (isInCheck(position, position.sideToMove)) -INF + (10 - depth) else 0
        }
        if (position.halfmoveClock >= 100) return 0

        moves.forEach { move ->
            val score = -negamax(apply(position, move), depth - 1, -beta, -alpha)
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    /**
     * Captures only, until the position is quiet.
     *
     * Without this the engine happily plays a move that wins a queen at depth N and loses two at
     * N+1 — the horizon effect, and the single biggest difference between an engine that looks
     * three moves ahead and one that plays like it does.
     */
    private fun quiescence(position: Position, alphaIn: Int, beta: Int): Int {
        var alpha = alphaIn
        val stand = evaluate(position)
        if (stand >= beta) return beta
        if (stand > alpha) alpha = stand

        val captures = legalMoves(position)
            .filter { position.squares[it.to] != EMPTY || it.isEnPassant }
            .sortedByDescending { captureScore(position, it) }

        captures.forEach { move ->
            val score = -quiescence(apply(position, move), -beta, -alpha)
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    /** MVV-LVA: take the biggest thing with the smallest thing. */
    private fun captureScore(position: Position, move: Move): Int {
        val victim = kotlin.math.abs(position.squares[move.to])
        val attacker = kotlin.math.abs(position.squares[move.from])
        if (victim == EMPTY) return 0
        return VALUE[victim] * 10 - VALUE[attacker]
    }

    private fun orderMoves(position: Position, moves: List<Move>): List<Move> =
        moves.sortedByDescending {
            captureScore(position, it) +
                (if (it.promotion == QUEEN) 800 else 0) +
                (if (it.isCastle) 60 else 0)
        }

    // -- Notation -------------------------------------------------------------

    /** Enough algebraic notation to write a move list a player can read back. */
    fun describe(position: Position, move: Move): String {
        val piece = kotlin.math.abs(position.squares[move.from])
        val capture = position.squares[move.to] != EMPTY || move.isEnPassant
        if (move.isCastle) {
            return if (fileOf(move.to) == 6) "O-O" else "O-O-O"
        }
        val letter = when (piece) {
            PAWN -> ""
            KNIGHT -> "N"; BISHOP -> "B"; ROOK -> "R"; QUEEN -> "Q"; else -> "K"
        }
        val from = if (piece == PAWN && capture) "${'a' + fileOf(move.from)}" else ""
        val promo = when (move.promotion) {
            QUEEN -> "=Q"; ROOK -> "=R"; BISHOP -> "=B"; KNIGHT -> "=N"; else -> ""
        }
        val after = apply(position, move)
        val suffix = when {
            legalMoves(after).isEmpty() && isInCheck(after, after.sideToMove) -> "#"
            isInCheck(after, after.sideToMove) -> "+"
            else -> ""
        }
        return "$letter$from${if (capture) "x" else ""}${square(move.to)}$promo$suffix"
    }

    fun glyph(piece: Int): String = when (piece) {
        PAWN -> "♙"; KNIGHT -> "♘"; BISHOP -> "♗"; ROOK -> "♖"; QUEEN -> "♕"; KING -> "♔"
        -PAWN -> "♟"; -KNIGHT -> "♞"; -BISHOP -> "♝"; -ROOK -> "♜"; -QUEEN -> "♛"; -KING -> "♚"
        else -> ""
    }

    /** For the mesh: a move is six characters and a position is never sent. */
    fun encode(move: Move): String = move.toString()

    fun decode(position: Position, text: String): Move? {
        val from = parseSquare(text.take(2)) ?: return null
        val to = parseSquare(text.drop(2).take(2)) ?: return null
        val promotion = when (text.getOrNull(4)) {
            'q' -> QUEEN; 'r' -> ROOK; 'b' -> BISHOP; 'n' -> KNIGHT; else -> EMPTY
        }
        return legalMoves(position).firstOrNull {
            it.from == from && it.to == to && it.promotion == promotion
        }
    }
}
