package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chess, held to the rules.
 *
 * Perft — counting the leaves of the move tree to a given depth from a known position — is the only
 * test that actually proves a move generator. Every one of the awkward rules (en passant discovered
 * check, castling through an attacked square, promotion to each of four pieces) shows up as a wrong
 * number here, and nowhere else until a player loses a game to it.
 */
class ChessRulesTest {

    private fun perft(position: Chess.Position, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = Chess.legalMoves(position)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(Chess.apply(position, it), depth - 1) }
    }

    @Test
    fun `perft from the starting position matches the published counts`() {
        val start = Chess.startingPosition()
        assertEquals(20L, perft(start, 1))
        assertEquals(400L, perft(start, 2))
        assertEquals(8_902L, perft(start, 3))
        assertEquals(197_281L, perft(start, 4))
    }

    /** Kiwipete: the standard test position, chosen because it exercises every special rule at once. */
    @Test
    fun `perft from Kiwipete matches the published counts`() {
        val p = position(
            "r...k..r" +
                "p.ppqpb." +
                "bn..pnp." +
                "...PN..." +
                ".p..P..." +
                "..N..Q.p" +
                "PPPBBPPP" +
                "R...K..R"
        ).copy(sideToMove = Chess.Colour.WHITE)
        assertEquals(48L, perft(p, 1))
        assertEquals(2_039L, perft(p, 2))
        assertEquals(97_862L, perft(p, 3))
    }

    /**
     * Builds a position from eight rows written rank 8 first, the way a board is drawn.
     * Uppercase is white, lowercase black, '.' empty.
     */
    private fun position(rows: String): Chess.Position {
        require(rows.length == 64) { "need 64 characters, got ${rows.length}" }
        val squares = IntArray(64)
        rows.forEachIndexed { i, ch ->
            val rank = 7 - i / 8
            val file = i % 8
            val kind = when (ch.lowercaseChar()) {
                'p' -> Chess.PAWN; 'n' -> Chess.KNIGHT; 'b' -> Chess.BISHOP
                'r' -> Chess.ROOK; 'q' -> Chess.QUEEN; 'k' -> Chess.KING
                else -> Chess.EMPTY
            }
            if (kind != Chess.EMPTY) {
                squares[Chess.index(file, rank)] = if (ch.isUpperCase()) kind else -kind
            }
        }
        val p = Chess.Position(squares)
        return p.copy(history = listOf(p.key()))
    }

    @Test
    fun `a king may not castle through an attacked square`() {
        // White king on e1, rook h1, black rook on f8 covering f1.
        val p = position(
            ".....r.." +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "....K..R"
        )
        val castles = Chess.legalMoves(p).filter { it.isCastle }
        assertTrue(castles.isEmpty(), "f1 is attacked, so O-O is illegal: $castles")
    }

    @Test
    fun `a king may not castle out of check`() {
        val p = position(
            "....r..." +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "....K..R"
        )
        assertTrue(Chess.legalMoves(p).none { it.isCastle })
    }

    @Test
    fun `castling is legal when nothing is in the way`() {
        val p = position(
            "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "....K..R"
        )
        val castle = Chess.legalMoves(p).firstOrNull { it.isCastle }
        assertNotNull(castle)
        val after = Chess.apply(p, castle)
        assertEquals(Chess.KING, after.pieceAt(Chess.parseSquare("g1")!!))
        assertEquals(Chess.ROOK, after.pieceAt(Chess.parseSquare("f1")!!))
    }

    @Test
    fun `en passant captures the pawn that is not on the target square`() {
        val p = position(
            "........" +
                "........" +
                "........" +
                "...pP..." +
                "........" +
                "........" +
                "........" +
                "........"
        ).copy(enPassant = Chess.parseSquare("d6")!!)

        val ep = Chess.legalMoves(p).firstOrNull { it.isEnPassant }
        assertNotNull(ep, "e5xd6 e.p. should be available")
        val after = Chess.apply(p, ep)
        assertEquals(Chess.EMPTY, after.pieceAt(Chess.parseSquare("d5")!!), "the black pawn is gone")
        assertEquals(Chess.PAWN, after.pieceAt(Chess.parseSquare("d6")!!))
    }

    @Test
    fun `a double pawn push sets the en passant square and the next move clears it`() {
        val start = Chess.startingPosition()
        val push = Chess.legalMoves(start).first { Chess.square(it.from) == "e2" && Chess.square(it.to) == "e4" }
        val after = Chess.apply(start, push)
        assertEquals(Chess.parseSquare("e3"), after.enPassant)

        val reply = Chess.legalMoves(after).first { Chess.square(it.from) == "b8" }
        assertEquals(-1, Chess.apply(after, reply).enPassant)
    }

    @Test
    fun `a pawn reaching the last rank may become any of four pieces`() {
        val p = position(
            "........" +
                "....P..." +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "....K..k"
        )
        val promotions = Chess.legalMoves(p)
            .filter { it.promotion != Chess.EMPTY }
            .map { it.promotion }
            .toSet()
        assertEquals(setOf(Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT), promotions)
    }

    @Test
    fun `fools mate is recognised as checkmate`() {
        var p = Chess.startingPosition()
        listOf("f2f3", "e7e5", "g2g4", "d8h4").forEach { text ->
            val move = Chess.decode(p, text)
            assertNotNull(move, "$text should be legal")
            p = Chess.apply(p, move)
        }
        assertEquals(Chess.Outcome.Checkmate(Chess.Colour.BLACK), Chess.outcome(p))
    }

    @Test
    fun `a king with no moves and no check is stalemate`() {
        val p = position(
            "k......." +
                "........" +
                ".Q......" +
                "........" +
                "........" +
                "........" +
                "........" +
                ".......K"
        ).copy(sideToMove = Chess.Colour.BLACK)
        assertEquals(Chess.Outcome.Stalemate, Chess.outcome(p))
    }

    @Test
    fun `king against king is a draw by material`() {
        val p = position(
            "k......." +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                ".......K"
        )
        assertEquals(Chess.Outcome.InsufficientMaterial, Chess.outcome(p))
    }

    @Test
    fun `the engine takes a free queen`() {
        val p = position(
            "....k..." +
                "........" +
                "........" +
                "...q...." +
                "....B..." +
                "........" +
                "........" +
                "....K..."
        ).copy(sideToMove = Chess.Colour.WHITE)
        val move = Chess.bestMove(p, Chess.Difficulty.SHARP, seed = 1)
        assertNotNull(move)
        assertEquals("d5", Chess.square(move.to), "Bxd5 wins a queen for nothing")
    }

    @Test
    fun `the engine finds mate in one`() {
        val p = position(
            "k......." +
                "......R." +
                ".....R.." +
                "........" +
                "........" +
                "........" +
                "........" +
                ".......K"
        )
        val move = Chess.bestMove(p, Chess.Difficulty.SHARP, seed = 7)
        assertNotNull(move)
        assertTrue(
            Chess.outcome(Chess.apply(p, move)) is Chess.Outcome.Checkmate,
            "Rf8# or Rg8# mates; engine played ${Chess.describe(p, move)}",
        )
    }

    @Test
    fun `the engine is deterministic for a seed`() {
        val p = Chess.startingPosition()
        val first = Chess.bestMove(p, Chess.Difficulty.STEADY, seed = 99)
        repeat(3) { assertEquals(first, Chess.bestMove(p, Chess.Difficulty.STEADY, seed = 99)) }
    }

    @Test
    fun `moves survive a round trip through the wire format`() {
        var p = Chess.startingPosition()
        repeat(12) {
            val move = Chess.bestMove(p, Chess.Difficulty.GENTLE, seed = it.toLong()) ?: return
            val decoded = Chess.decode(p, Chess.encode(move))
            assertEquals(move, decoded, "move ${Chess.encode(move)} did not survive encoding")
            p = Chess.apply(p, move)
        }
    }

    @Test
    fun `the fifty move rule is counted and reset by pawns and captures`() {
        val start = Chess.startingPosition()
        assertEquals(0, start.halfmoveClock)
        val knight = Chess.decode(start, "g1f3")!!
        assertEquals(1, Chess.apply(start, knight).halfmoveClock)
        val pawn = Chess.decode(start, "e2e4")!!
        assertEquals(0, Chess.apply(start, pawn).halfmoveClock)
    }

    @Test
    fun `a rook captured on its home square ends that castling right`() {
        val p = position(
            "....k..." +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "........" +
                "R...K..r"
        ).copy(sideToMove = Chess.Colour.BLACK)
        // Black's rook on h1 has already taken white's; white must not be able to castle short.
        assertFalse(Chess.apply(p, Chess.legalMoves(p).first()).whiteCanCastleKing)
    }
}
