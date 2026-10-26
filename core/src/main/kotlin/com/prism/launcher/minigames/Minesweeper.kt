package com.prism.launcher.minigames

/**
 * Minesweeper, as a board with no opinions about pixels.
 *
 * ## Why the board is generated lazily
 *
 * The classic first-click loss is not a difficulty setting, it is a bug that everybody has played
 * through. Mines are therefore placed AFTER the first reveal, with the clicked cell and its
 * neighbours excluded, so the opening move is always a zero cell and always opens some board.
 *
 * That choice has a second consequence worth stating: the mine positions are part of the state, not
 * a seed the UI holds separately. A board that has not been seeded yet contains no mines, and
 * [reveal] seeds it on the first call. Nothing else can observe a half-built board.
 *
 * ## Determinism
 *
 * Placement is driven by [Rng] from a caller-supplied seed, the same generator the other games use.
 * Two devices that start from the same seed and the same clicks get the same board -- which is what
 * a future mesh game needs, and what the tests assert today.
 */
object Minesweeper {

    /** What a cell is hiding. [FLAG] and [QUESTION] are marks, not truth. */
    enum class Mark { NONE, FLAG, QUESTION }

    data class Cell(
        val mine: Boolean = false,
        val adjacent: Int = 0,
        val revealed: Boolean = false,
        val mark: Mark = Mark.NONE,
        /** Set only on the cell that ended the game. */
        val exploded: Boolean = false,
    )

    enum class Status { PLAYING, WON, LOST }

    data class Board(
        val rows: Int,
        val cols: Int,
        val mines: Int,
        val seed: Long,
        val cells: List<Cell>,
        val status: Status = Status.PLAYING,
        /** False until the first reveal has placed the mines. */
        val seeded: Boolean = false,
        val reveals: Int = 0,
        val flagsPlaced: Int = 0,
    ) {
        fun cell(x: Int, y: Int): Cell = cells[y * cols + x]

        fun inBounds(x: Int, y: Int): Boolean = x in 0 until cols && y in 0 until rows

        val safeCells: Int get() = rows * cols - mines

        /** Revealed non-mine cells. The win condition. */
        val opened: Int get() = reveals

        fun neighbours(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (inBounds(nx, ny)) add(nx to ny)
            }
        }
    }

    /**
     * The three sizes the phone and the desktop both offer.
     *
     * Numbers chosen so each fits a phone screen at a tappable cell size without scrolling: sixty
     * columns of three-pixel squares is a puzzle nobody can play with a thumb.
     */
    enum class Difficulty(val label: String, val rows: Int, val cols: Int, val mines: Int) {
        BEGINNER("Beginner", rows = 9, cols = 9, mines = 10),
        INTERMEDIATE("Intermediate", rows = 13, cols = 13, mines = 32),
        EXPERT("Expert", rows = 16, cols = 20, mines = 58),
    }

    fun newGame(difficulty: Difficulty, seed: Long): Board =
        newGame(difficulty.rows, difficulty.cols, difficulty.mines, seed)

    fun newGame(rows: Int, cols: Int, mines: Int, seed: Long): Board {
        require(rows > 0 && cols > 0) { "board must have cells" }
        // A board with as many mines as cells has no first click that can be safe. Clamping rather
        // than throwing keeps a settings mistake from taking a screen down.
        val safeMines = mines.coerceIn(0, (rows * cols - 9).coerceAtLeast(1))
        return Board(
            rows = rows,
            cols = cols,
            mines = safeMines,
            seed = seed,
            cells = List(rows * cols) { Cell() },
        )
    }

    /**
     * Places mines away from the first click.
     *
     * The exclusion is the clicked cell plus its neighbours, which guarantees the first reveal is a
     * zero and therefore floods. A board too small to give that exclusion room falls back to
     * excluding only the cell itself, and finally to anywhere.
     */
    private fun seed(board: Board, avoidX: Int, avoidY: Int): Board {
        val rng = Rng(board.seed)
        val forbidden = board.neighbours(avoidX, avoidY).toMutableSet()
        forbidden.add(avoidX to avoidY)

        val all = ArrayList<Pair<Int, Int>>(board.rows * board.cols)
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if ((x to y) !in forbidden) all.add(x to y)
        }
        if (all.size < board.mines) {
            all.clear()
            for (y in 0 until board.rows) for (x in 0 until board.cols) {
                if (x != avoidX || y != avoidY) all.add(x to y)
            }
        }

        val chosen = rng.shuffled(all).take(board.mines).toHashSet()
        val cells = board.cells.toMutableList()
        chosen.forEach { (x, y) -> cells[y * board.cols + x] = cells[y * board.cols + x].copy(mine = true) }

        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            val index = y * board.cols + x
            if (cells[index].mine) continue
            val adjacent = board.neighbours(x, y).count { (nx, ny) -> cells[ny * board.cols + nx].mine }
            cells[index] = cells[index].copy(adjacent = adjacent)
        }
        return board.copy(cells = cells, seeded = true)
    }

    /**
     * Opens a cell, flooding through zeros.
     *
     * Returns the board unchanged when the cell is flagged or already open: a tap on a flag must not
     * lose the game, which is the other classic way a minesweeper implementation annoys people.
     */
    fun reveal(board: Board, x: Int, y: Int): Board {
        if (board.status != Status.PLAYING || !board.inBounds(x, y)) return board
        val start = board.cell(x, y)
        if (start.mark == Mark.FLAG || start.revealed) return board

        var next = if (board.seeded) board else seed(board, x, y)

        if (next.cell(x, y).mine) {
            val cells = next.cells.toMutableList()
            val index = y * next.cols + x
            cells[index] = cells[index].copy(revealed = true, exploded = true)
            // Every other mine is shown too: a lost board that hides the answer is unreadable.
            for (i in cells.indices) if (cells[i].mine) cells[i] = cells[i].copy(revealed = true)
            return next.copy(cells = cells, status = Status.LOST)
        }

        next = flood(next, x, y)
        return if (next.opened >= next.safeCells) next.copy(status = Status.WON) else next
    }

    private fun flood(board: Board, x: Int, y: Int): Board {
        val cells = board.cells.toMutableList()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(x to y)
        val seen = HashSet<Int>()
        var opened = 0

        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val index = cy * board.cols + cx
            if (!seen.add(index)) continue
            val cell = cells[index]
            if (cell.revealed || cell.mine || cell.mark == Mark.FLAG) continue

            cells[index] = cell.copy(revealed = true)
            opened++
            // Zeros cascade; a numbered cell is a border and stops.
            if (cell.adjacent == 0) {
                board.neighbours(cx, cy).forEach { queue.add(it) }
            }
        }
        return board.copy(cells = cells, reveals = board.reveals + opened)
    }

    /**
     * Cycles a hidden cell's mark: none → flag → question → none.
     *
     * `flagsOnly` keeps question marks out of the cycle for players who find them noise; the phone
     * and the desktop both offer that preference, so the board has to support it.
     */
    fun mark(board: Board, x: Int, y: Int, flagsOnly: Boolean = false): Board {
        if (board.status != Status.PLAYING || !board.inBounds(x, y)) return board
        val index = y * board.cols + x
        val cell = board.cells[index]
        if (cell.revealed) return board

        val nextMark = when (cell.mark) {
            Mark.NONE -> Mark.FLAG
            Mark.FLAG -> if (flagsOnly) Mark.NONE else Mark.QUESTION
            Mark.QUESTION -> Mark.NONE
        }
        val cells = board.cells.toMutableList()
        cells[index] = cell.copy(mark = nextMark)
        val flags = cells.count { it.mark == Mark.FLAG }
        return board.copy(cells = cells, flagsPlaced = flags)
    }

    /**
     * Reveals every hidden neighbour of a satisfied number.
     *
     * The gesture that makes a board playable: once a "2" has two flags, tapping it opens the rest.
     * It is NOT safe by construction -- if the flags are wrong it loses, exactly as it should.
     */
    fun chord(board: Board, x: Int, y: Int): Board {
        if (board.status != Status.PLAYING || !board.inBounds(x, y)) return board
        val cell = board.cell(x, y)
        if (!cell.revealed || cell.adjacent == 0) return board
        val flags = board.neighbours(x, y).count { board.cell(it.first, it.second).mark == Mark.FLAG }
        if (flags != cell.adjacent) return board

        var next = board
        for ((nx, ny) in board.neighbours(x, y)) {
            val target = next.cell(nx, ny)
            if (!target.revealed && target.mark != Mark.FLAG) {
                next = reveal(next, nx, ny)
                if (next.status == Status.LOST) return next
            }
        }
        return next
    }

    /** Mines minus flags, floored at zero. What the counter at the top shows. */
    fun remaining(board: Board): Int = (board.mines - board.flagsPlaced).coerceAtLeast(0)

    /**
     * Tears the roof off: marks every mine, for the "you lost, here is why" state.
     *
     * Deliberately does not change [Board.status]; a surrender is not a win, and the UI decides how
     * to present it.
     */
    fun revealAll(board: Board): Board {
        val cells = board.cells.map { if (it.mine) it.copy(revealed = true) else it }.toMutableList()
        return board.copy(cells = cells)
    }
}
