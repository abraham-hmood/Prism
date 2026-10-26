package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MinesweeperTest {

    private fun board(seed: Long = 7L) =
        Minesweeper.newGame(Minesweeper.Difficulty.BEGINNER, seed)

    @Test
    fun `first click is never a mine and always opens a region`() {
        for (seed in 0L until 200L) {
            val start = board(seed)
            val opened = Minesweeper.reveal(start, 4, 4)
            assertNotEquals(Minesweeper.Status.LOST, opened.status, "seed $seed lost on the first click")
            assertTrue(opened.opened > 0, "seed $seed opened nothing")
            // A safe first click is a zero cell, so the flood must have spanned more than one cell.
            assertTrue(opened.opened >= 9, "seed $seed opened only ${opened.opened} cells")
            assertTrue(opened.seeded)
        }
    }

    @Test
    fun `the board has exactly the requested number of mines`() {
        val revealed = Minesweeper.reveal(board(), 4, 4)
        assertEquals(10, revealed.cells.count { it.mine })

        val expert = Minesweeper.newGame(Minesweeper.Difficulty.EXPERT, 3L).let { Minesweeper.reveal(it, 8, 8) }
        assertEquals(58, expert.cells.count { it.mine })
    }

    @Test
    fun `adjacency counts match the mines around each cell`() {
        val board = Minesweeper.reveal(board(), 0, 0)
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            val cell = board.cell(x, y)
            if (cell.mine) continue
            val counted = board.neighbours(x, y).count { (nx, ny) -> board.cell(nx, ny).mine }
            assertEquals(counted, cell.adjacent, "cell $x,$y")
        }
    }

    @Test
    fun `a flagged cell cannot be revealed`() {
        val start = board()
        val flagged = Minesweeper.mark(start, 0, 0)
        assertEquals(Minesweeper.Mark.FLAG, flagged.cell(0, 0).mark)
        assertEquals(1, flagged.flagsPlaced)

        val after = Minesweeper.reveal(flagged, 0, 0)
        assertFalse(after.cell(0, 0).revealed)
        assertNotEquals(Minesweeper.Status.LOST, after.status)
    }

    @Test
    fun `marks cycle through flag question and none`() {
        var board = board()
        board = Minesweeper.mark(board, 2, 2)
        assertEquals(Minesweeper.Mark.FLAG, board.cell(2, 2).mark)
        board = Minesweeper.mark(board, 2, 2)
        assertEquals(Minesweeper.Mark.QUESTION, board.cell(2, 2).mark)
        board = Minesweeper.mark(board, 2, 2)
        assertEquals(Minesweeper.Mark.NONE, board.cell(2, 2).mark)

        var flagsOnly = board()
        flagsOnly = Minesweeper.mark(flagsOnly, 2, 2, flagsOnly = true)
        flagsOnly = Minesweeper.mark(flagsOnly, 2, 2, flagsOnly = true)
        assertEquals(Minesweeper.Mark.NONE, flagsOnly.cell(2, 2).mark)
    }

    @Test
    fun `revealing a mine loses and shows every mine`() {
        val revealed = Minesweeper.reveal(board(11L), 4, 4)
        val mine = revealed.cells.indexOfFirst { it.mine }
        val mineX = mine % revealed.cols
        val mineY = mine / revealed.cols

        val lost = Minesweeper.reveal(revealed, mineX, mineY)
        assertEquals(Minesweeper.Status.LOST, lost.status)
        assertTrue(lost.cell(mineX, mineY).exploded)
        assertTrue(lost.cells.filter { it.mine }.all { it.revealed })
    }

    @Test
    fun `chording opens neighbours when the flags match the number`() {
        // Find a revealed numbered cell with at least one hidden neighbour by opening a whole board
        // without mines: the layout is then all zeros, so use a real board and chord a border cell.
        var board = Minesweeper.reveal(board(21L), 4, 4)
        val target = (0 until board.rows).flatMap { y ->
            (0 until board.cols).map { x -> x to y }
        }.first { (x, y) ->
            val c = board.cell(x, y)
            c.revealed && c.adjacent > 0 && board.neighbours(x, y).any { (nx, ny) -> !board.cell(nx, ny).revealed }
        }

        val before = board
        val unchanged = Minesweeper.chord(board, target.first, target.second)
        assertEquals(before.opened, unchanged.opened, "chord must not open anything with wrong flags")

        // Flag exactly the mines around the cell, then the chord opens the rest.
        val mines = before.neighbours(target.first, target.second)
            .filter { (nx, ny) -> before.cell(nx, ny).mine }
        mines.forEach { (nx, ny) -> board = Minesweeper.mark(board, nx, ny) }
        val after = Minesweeper.chord(board, target.first, target.second)
        assertTrue(after.opened > before.opened)
    }

    @Test
    fun `opening every safe cell wins`() {
        var board = Minesweeper.reveal(board(5L), 4, 4)
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if (!board.cell(x, y).mine) board = Minesweeper.reveal(board, x, y)
            if (board.status == Minesweeper.Status.WON) break
        }
        assertEquals(Minesweeper.Status.WON, board.status)
        assertEquals(board.safeCells, board.opened)
    }

    @Test
    fun `the same seed and clicks produce the same board`() {
        val a = Minesweeper.reveal(Minesweeper.newGame(9, 9, 10, 99L), 3, 3)
        val b = Minesweeper.reveal(Minesweeper.newGame(9, 9, 10, 99L), 3, 3)
        assertEquals(a.cells, b.cells)
        assertNotEquals(
            a.cells,
            Minesweeper.reveal(Minesweeper.newGame(9, 9, 10, 100L), 3, 3).cells,
        )
    }

    @Test
    fun `remaining counts mines minus flags and never goes negative`() {
        var board = board()
        assertEquals(10, Minesweeper.remaining(board))
        repeat(12) { i -> board = Minesweeper.mark(board, i % 9, i / 9) }
        assertEquals(0, Minesweeper.remaining(board))
    }
}
