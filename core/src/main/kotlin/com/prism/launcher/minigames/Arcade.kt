package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The arcade cabinet behind the newer Minigames entries.
 *
 * ## One engine, many games
 *
 * The six original games each own a bespoke renderer because each is a different kind of thing:
 * Paper Empire is a strategy map, Echo Chamber is a first-person audio maze. The thirteen games
 * here are all short, screen-sized arcade games, so they share a tiny retained-mode scene format
 * ([ArcadeScene]) and one renderer on each platform. A game only produces normalized sprites and
 * reacts to taps, swipes and five named actions; the phone and the desktop then draw exactly the
 * same scene in their own neon treatment. That is what makes adding a game a few dozen lines rather
 * than a new View and a new Compose page.
 *
 * ## Neon, not paper
 *
 * None of these are paper games. The scene carries palette INDICES rather than colours so each
 * platform can map them to the same neon palette regardless of whether the user's theme is light or
 * dark.
 */
enum class ArcadeAction { LEFT, RIGHT, UP, DOWN, ROTATE, DROP, ACTION, SWITCH, NEXT }

/** A press, a swipe, or a drag. All coordinates are 0..1 inside the board rectangle. */
data class ArcadeInput(
    val tapX: Float = -1f,
    val tapY: Float = -1f,
    val swipe: ArcadeAction? = null,
    val action: ArcadeAction? = null,
    val dragX: Float = -1f,
)

/** One thing to draw. `kind` 0 is a rounded rectangle, 1 a circle, 2 a ring, 3 a line. */
data class ArcadeSprite(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val colour: Int,
    val label: String? = null,
    val kind: Int = 0,
    val alpha: Float = 1f,
)

data class ArcadeButton(val label: String, val action: ArcadeAction)

/** A whole frame. Boards are addressed in 0..1 coordinates; `aspect` is width / height. */
data class ArcadeScene(
    val hud: String,
    val sprites: List<ArcadeSprite>,
    val gridW: Int = 0,
    val gridH: Int = 0,
    val aspect: Float = 1f,
    val buttons: List<ArcadeButton> = emptyList(),
    val message: String? = null,
)

interface ArcadeGame {
    val id: String
    val score: Int
    val over: Boolean
    val won: Boolean

    /**
     * How hot the run is, 0..1, for the soundtrack.
     *
     * A cabinet cannot know "tension" directly, so this is score pressure: a run that is going
     * well speeds up, and every game raises its own score at its own pace. The music uses it for
     * tempo, not for correctness, so an approximation is honest here.
     */
    val intensity: Float get() = 0f

    fun reset(seed: Long)
    fun update(dt: Float)
    fun input(input: ArcadeInput)
    fun scene(): ArcadeScene
}

/** Metadata used by both pickers. */
data class ArcadeInfo(
    val id: String,
    val title: String,
    val blurb: String,
    val controls: String,
)

object ArcadeGames {
    val ALL: List<ArcadeInfo> = listOf(
        // The three asked for by name.
        ArcadeInfo("tetris", "Tetris", "Seven shapes, ten columns, and gravity.", "swipe / arrows, rotate, drop"),
        ArcadeInfo("snake", "Snake", "Eat, grow, and do not bite yourself.", "swipe / d-pad"),
        ArcadeInfo("chutes", "Chutes & Ladders", "Roll, climb, slide. Two to four players, or the mesh.", "tap roll"),

        // Popular forms, rebuilt from scratch.
        ArcadeInfo("merge2048", "2048 Merge", "Slide the grid; equal tiles become one.", "swipe"),
        ArcadeInfo("brick", "Brick Breaker", "Clear the wall without losing the ball.", "drag / tap launch"),
        ArcadeInfo("flap", "Jetpack Flap", "Tap to rise, thread the gaps.", "tap / flap"),
        ArcadeInfo("match3", "Gem Cascade", "Swap neighbours, clear runs, keep the cascade going.", "tap two gems"),
        ArcadeInfo("tower", "Tower Defense", "Build towers beside the road. Stop the column.", "tap to build / upgrade"),

        // Five Prism originals.
        ArcadeInfo("split", "Prism Split", "Rotate mirrors and route the beam to the target.", "tap a mirror"),
        ArcadeInfo("gravity", "Gravity Well", "One button. Reverse orbit, collect light, dodge mines.", "tap / reverse"),
        ArcadeInfo("fuse", "Fuse Chain", "Light one bomb and let the chain do the work.", "tap a bomb"),
        ArcadeInfo("hive", "Hive Rush", "Swat the swarm before it reaches the hive.", "tap the bugs"),
        ArcadeInfo("storm", "Static Storm", "Match your shield to each orb. Switch fast.", "tap / switch"),

        // The Nokia 3310 set. Snake was already here; these are the rest.
        ArcadeInfo("spaceimpact", "Space Impact", "The 3310's shooter: move, fire, survive.", "tap / arrows"),
        ArcadeInfo("bantumi", "Bantumi", "Mancala, two rows of six, captures and extra turns.", "tap a pit"),
        ArcadeInfo("pairs", "Pairs II", "Sixteen cards, eight pairs, as few moves as you can.", "tap two cards"),
        ArcadeInfo("bumper", "Bumper", "The 3310 table: keep the ball alive, hit the bumpers.", "drag / tap"),

        // Five newer cabinets, picked for short sessions and one-thumb play.
        ArcadeInfo("stack", "Stack", "Drop each slab on the one below. Overhang is cut away.", "tap to drop"),
        ArcadeInfo("lightsout", "Lights Out", "Every tap flips a cross. Turn the whole grid off.", "tap a square"),
        ArcadeInfo("reaction", "Reaction", "Wait for the colour, then tap. Early is a loss.", "tap"),
        ArcadeInfo("runner", "Runner", "One button, rising obstacles, distance as score.", "tap to jump"),
        ArcadeInfo("meteor", "Meteor", "Tap the falling rocks before they land.", "tap a meteor"),
    )

    fun info(id: String): ArcadeInfo? = ALL.firstOrNull { it.id == id }

    /**
     * Builds a cabinet that is already at the start of a run.
     *
     * Constructing a game is not enough: most rules keep their state in fields that `resetGame`
     * creates, and a renderer that ticks a fresh object before calling `reset` is playing a board
     * that was never dealt. That is exactly how Snake met an empty body and took the launcher down
     * on the first frame. Because every caller wants a playable run, the factory deals it here once
     * and callers that want their own seed pass it in.
     */
    fun create(id: String, seed: Long = 1L): ArcadeGame? {
        val game = when (id) {
            "tetris" -> TetrisGame()
            "snake" -> SnakeGame()
            "chutes" -> ChutesGame()
            "merge2048" -> MergeGame()
            "brick" -> BrickGame()
            "flap" -> FlapGame()
            "match3" -> MatchGame()
            "tower" -> TowerGame()
            "split" -> SplitGame()
            "gravity" -> GravityGame()
            "fuse" -> FuseGame()
            "hive" -> HiveGame()
            "storm" -> StormGame()
            "spaceimpact" -> SpaceImpactGame()
            "bantumi" -> BantumiGame()
            "pairs" -> PairsGame()
            "bumper" -> BumperGame()
            "stack" -> StackGame()
            "lightsout" -> LightsOutGame()
            "reaction" -> ReactionGame()
            "runner" -> RunnerGame()
            "meteor" -> MeteorGame()
            else -> null
        }
        game?.reset(seed)
        return game
    }
}

private const val CYAN = 0
private const val MAGENTA = 1
private const val YELLOW = 2
private const val GREEN = 3
private const val ORANGE = 4
private const val BLUE = 5
private const val RED = 6
private const val WHITE = 7
private const val DIM = 8

abstract class BaseGame(override val id: String) : ArcadeGame {
    override var score: Int = 0
    override var over: Boolean = false
    override var won: Boolean = false
    protected var rng = Random(1)

    override val intensity: Float
        get() = if (over) 0f else (0.12f + (score % 600) / 700f).coerceIn(0f, 1f)

    final override fun reset(seed: Long) {
        rng = Random(seed)
        score = 0
        over = false
        won = false
        resetGame()
    }

    protected abstract fun resetGame()
    final override fun update(dt: Float) {
        if (!over) tick(dt.coerceAtMost(0.05f))
    }

    final override fun input(input: ArcadeInput) {
        if (!over) onInput(input)
    }

    protected abstract fun tick(dt: Float)
    protected abstract fun onInput(input: ArcadeInput)
}

private fun tile(
    x: Int, y: Int, w: Int, h: Int, colour: Int,
    label: String? = null, inset: Float = 0.06f, alpha: Float = 1f,
) = ArcadeSprite(
    x = x.toFloat() / w + inset / w,
    y = y.toFloat() / h + inset / h,
    w = 1f / w - 2f * inset / w,
    h = 1f / h - 2f * inset / h,
    colour = colour,
    label = label,
    alpha = alpha,
)

// ── Tetris ─────────────────────────────────────────────────────────────────

private class TetrisGame : BaseGame("tetris") {
    private val w = 10
    private val h = 20
    private val board = Array(h) { IntArray(w) }
    private var type = 0
    private var rotation = 0
    private var px = 3
    private var py = 0
    private var timer = 0f
    private var lines = 0

    private val shapes = arrayOf(
        intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
        intArrayOf(1, 1, 2, 1, 1, 2, 2, 2),
        intArrayOf(0, 1, 1, 1, 2, 1, 1, 2),
        intArrayOf(1, 1, 2, 1, 0, 2, 1, 2),
        intArrayOf(0, 1, 1, 1, 1, 2, 2, 2),
        intArrayOf(0, 1, 0, 2, 1, 2, 2, 2),
        intArrayOf(2, 1, 0, 2, 1, 2, 2, 2),
    )

    private fun cells(t: Int, r: Int): List<Pair<Int, Int>> {
        val base = shapes[t]
        var points = (0 until 4).map { base[it * 2] to base[it * 2 + 1] }
        repeat(r % 4) {
            points = points.map { (x, y) -> 3 - y to x }
        }
        val minX = points.minOf { it.first }
        val minY = points.minOf { it.second }
        return points.map { (x, y) -> x - minX to y - minY }
    }

    private fun collides(t: Int, r: Int, x: Int, y: Int): Boolean =
        cells(t, r).any { (cx, cy) ->
            val bx = x + cx
            val by = y + cy
            bx !in 0 until w || by >= h || (by >= 0 && board[by][bx] != 0)
        }

    private fun spawn() {
        type = rng.nextInt(shapes.size)
        rotation = 0
        px = 3
        py = 0
        if (collides(type, rotation, px, py)) {
            over = true
        }
    }

    override fun resetGame() {
        board.forEach { it.fill(0) }
        lines = 0
        timer = 0f
        spawn()
    }

    private fun lock() {
        cells(type, rotation).forEach { (cx, cy) ->
            val by = py + cy
            val bx = px + cx
            if (by in 0 until h && bx in 0 until w) board[by][bx] = type + 1
        }
        var cleared = 0
        for (y in h - 1 downTo 0) {
            if (board[y].all { it != 0 }) {
                cleared++
                for (yy in y downTo 1) board[yy] = board[yy - 1].copyOf()
                board[0] = IntArray(w)
            }
        }
        if (cleared > 0) {
            lines += cleared
            score += when (cleared) {
                1 -> 100
                2 -> 300
                3 -> 500
                else -> 800
            } * (1 + lines / 10)
        }
        spawn()
    }

    private fun move(dx: Int, dy: Int): Boolean {
        if (!collides(type, rotation, px + dx, py + dy)) {
            px += dx
            py += dy
            return true
        }
        if (dy > 0) lock()
        return false
    }

    private fun rotate() {
        val next = (rotation + 1) % 4
        if (!collides(type, next, px, py)) rotation = next
    }

    override fun tick(dt: Float) {
        timer += dt
        val speed = (0.62f - min(0.42f, lines * 0.012f)).coerceAtLeast(0.12f)
        while (timer >= speed) {
            timer -= speed
            move(0, 1)
            if (over) return
        }
    }

    override fun onInput(input: ArcadeInput) {
        when (input.action ?: input.swipe) {
            ArcadeAction.LEFT -> move(-1, 0)
            ArcadeAction.RIGHT -> move(1, 0)
            ArcadeAction.UP, ArcadeAction.ROTATE -> rotate()
            ArcadeAction.DOWN -> move(0, 1)
            ArcadeAction.DROP -> while (move(0, 1)) Unit
            else -> Unit
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(220)
        for (y in 0 until h) for (x in 0 until w) {
            if (board[y][x] != 0) sprites.add(tile(x, y, w, h, board[y][x] - 1))
        }
        if (!over) {
            cells(type, rotation).forEach { (cx, cy) ->
                val sx = px + cx
                val sy = py + cy
                if (sy >= 0) sprites.add(tile(sx, sy, w, h, type, alpha = 0.95f))
            }
        }
        return ArcadeScene(
            hud = "score $score · lines $lines",
            sprites = sprites,
            gridW = w,
            gridH = h,
            aspect = 0.5f,
            buttons = listOf(
                ArcadeButton("◀", ArcadeAction.LEFT),
                ArcadeButton("⟳", ArcadeAction.ROTATE),
                ArcadeButton("▶", ArcadeAction.RIGHT),
                ArcadeButton("⤓", ArcadeAction.DROP),
            ),
            message = if (over) "the stack reached the top" else null,
        )
    }
}

// ── Snake ──────────────────────────────────────────────────────────────────

private class SnakeGame : BaseGame("snake") {
    private val n = 20
    private val body = ArrayList<Pair<Int, Int>>()
    private var dx = 1
    private var dy = 0
    private var foodX = 10
    private var foodY = 10
    private var step = 0f
    private var interval = 0.13f

    override fun resetGame() {
        body.clear()
        body.add(9 to 10)
        body.add(8 to 10)
        body.add(7 to 10)
        dx = 1
        dy = 0
        step = 0f
        interval = 0.13f
        placeFood()
    }

    /**
     * Puts food on a free cell.
     *
     * The old version drew random cells until one was not part of the snake. That is fine on an
     * empty board and becomes an unbounded loop as the snake grows: at three hundred occupied cells
     * the odds of a hit are low enough that the UI thread spends seconds spinning, and with one free
     * cell it is effectively a hang. Enumerating the free cells makes the worst case one pass and
     * lets a full board end the run instead of freezing it.
     */
    private fun placeFood() {
        val free = ArrayList<Pair<Int, Int>>(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            if (!body.contains(x to y)) free.add(x to y)
        }
        if (free.isEmpty()) {
            over = true
            won = true
            return
        }
        val cell = free[rng.nextInt(free.size)]
        foodX = cell.first
        foodY = cell.second
    }

    private fun turn(ndx: Int, ndy: Int) {
        if (ndx == -dx && ndy == -dy) return
        dx = ndx
        dy = ndy
    }

    override fun tick(dt: Float) {
        step += dt
        while (step >= interval) {
            step -= interval
            // A direct SnakeGame has not been dealt yet. The factory always resets, but this guard
            // keeps a future caller that constructs the class directly from crashing the UI thread
            // on its first frame.
            val head = body.firstOrNull() ?: run {
                resetGame()
                return
            }
            val nx = head.first + dx
            val ny = head.second + dy
            if (nx !in 0 until n || ny !in 0 until n || body.drop(1).contains(nx to ny)) {
                over = true
                return
            }
            body.add(0, nx to ny)
            if (nx == foodX && ny == foodY) {
                score += 10
                interval = (interval - 0.002f).coerceAtLeast(0.065f)
                if (body.size >= n * n) {
                    over = true
                    won = true
                    return
                }
                placeFood()
            } else {
                body.removeAt(body.size - 1)
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        when (input.action ?: input.swipe) {
            ArcadeAction.LEFT -> turn(-1, 0)
            ArcadeAction.RIGHT -> turn(1, 0)
            ArcadeAction.UP -> turn(0, -1)
            ArcadeAction.DOWN -> turn(0, 1)
            else -> Unit
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(body.size + 2)
        body.forEachIndexed { index, (x, y) ->
            sprites.add(tile(x, y, n, n, if (index == 0) CYAN else GREEN, alpha = if (index == 0) 1f else 0.8f))
        }
        sprites.add(tile(foodX, foodY, n, n, MAGENTA, label = "●"))
        return ArcadeScene(
            hud = "score $score · length ${body.size}",
            sprites = sprites,
            gridW = n,
            gridH = n,
            buttons = listOf(
                ArcadeButton("◀", ArcadeAction.LEFT),
                ArcadeButton("▲", ArcadeAction.UP),
                ArcadeButton("▼", ArcadeAction.DOWN),
                ArcadeButton("▶", ArcadeAction.RIGHT),
            ),
            message = if (over) "you bit yourself" else null,
        )
    }
}

// ── Chutes & Ladders ───────────────────────────────────────────────────────

private val LADDERS = mapOf(4 to 14, 9 to 31, 21 to 42, 28 to 84, 51 to 67, 72 to 91, 80 to 99)
private val CHUTES = mapOf(17 to 7, 54 to 34, 62 to 19, 64 to 60, 87 to 24, 93 to 73, 95 to 75, 98 to 79)

/**
 * Chutes and Ladders.
 *
 * The core is deliberately turn-based and state-only: a mesh game sends the whole position array
 * after a roll rather than trying to keep two dice generators in step. With two players that is one
 * short string per turn, and it survives the mesh's fire-and-forget UDP because the next state
 * supersedes the last.
 */
class ChutesGame(
    private val players: Int = 4,
    private val autoAi: Boolean = true,
    seed: Long = 1,
) : BaseGame("chutes") {
    val positions = IntArray(players)
    var turn: Int = 0
        private set
    var lastRoll: Int = 0
        private set
    var winner: Int = -1
        private set
    private var aiTimer = 0f
    private var lastEvent = ""

    init {
        reset(seed)
    }

    override fun resetGame() {
        positions.fill(0)
        turn = 0
        lastRoll = 0
        winner = -1
        aiTimer = 0f
        lastEvent = ""
    }

    /** Rolls for whoever's turn it is. Returns the die, or zero if the game cannot roll now. */
    fun roll(): Int {
        if (over || winner >= 0) return 0
        val die = rng.nextInt(1, 7)
        lastRoll = die
        val from = positions[turn]
        var next = from + die
        if (next > 100) next = from
        val climbed = LADDERS[next]
        val slid = CHUTES[next]
        next = climbed ?: slid ?: next
        positions[turn] = next
        lastEvent = when {
            climbed != null -> "player ${turn + 1} climbs $climbed"
            slid != null -> "player ${turn + 1} slides to $slid"
            else -> ""
        }
        if (next >= 100) {
            winner = turn
            won = true
            score = 100
            over = true
        } else {
            turn = (turn + 1) % players
        }
        return die
    }

    /** Replaces the board from a peer's turn. */
    fun applyState(state: IntArray, nextTurn: Int, roll: Int, winnerSeat: Int, event: String) {
        if (state.size != positions.size) return
        state.copyInto(positions)
        turn = nextTurn.coerceIn(0, players - 1)
        lastRoll = roll
        winner = winnerSeat
        lastEvent = event
        if (winner >= 0) {
            this.winner = winner
            won = true
            over = true
        }
    }

    fun stateLine(): String =
        positions.joinToString(",") + "|" + turn + "|" + lastRoll + "|" + winner + "|" + lastEvent

    fun applyLine(line: String) {
        val parts = line.split("|")
        if (parts.size < 5) return
        val state = parts[0].split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
        applyState(
            state,
            parts[1].toIntOrNull() ?: 0,
            parts[2].toIntOrNull() ?: 0,
            parts[3].toIntOrNull() ?: -1,
            parts[4],
        )
    }

    override fun tick(dt: Float) {
        if (!autoAi || winner >= 0) return
        if (turn == 0) return
        aiTimer += dt
        if (aiTimer >= 0.55f) {
            aiTimer = 0f
            roll()
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action == ArcadeAction.ACTION || input.action == ArcadeAction.NEXT) {
            if (autoAi && turn != 0) return
            roll()
        }
    }

    private fun cell(n: Int): Pair<Float, Float> {
        val index = (n - 1).coerceIn(0, 99)
        val row = index / 10
        val rawCol = index % 10
        val col = if (row % 2 == 0) rawCol else 9 - rawCol
        return col.toFloat() / 10f to (9 - row).toFloat() / 10f
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(180)
        for (n in 1..100) {
            val (x, y) = cell(n)
            sprites.add(ArcadeSprite(x + 0.006f, y + 0.006f, 0.088f, 0.088f,
                if ((n + (n / 10)) % 2 == 0) BLUE else DIM, label = n.toString(), alpha = 0.55f))
        }
        // Ladders and chutes as dotted trails, which reads as a board without needing line art.
        fun trail(from: Int, to: Int, colour: Int) {
            val (ax, ay) = cell(from)
            val (bx, by) = cell(to)
            repeat(8) { i ->
                val t = (i + 1) / 9f
                sprites.add(ArcadeSprite(
                    ax + (bx - ax) * t + 0.035f,
                    ay + (by - ay) * t + 0.035f,
                    0.028f, 0.028f, colour, kind = 1, alpha = 0.75f,
                ))
            }
        }
        LADDERS.forEach { (a, b) -> trail(a, b, GREEN) }
        CHUTES.forEach { (a, b) -> trail(a, b, RED) }

        positions.forEachIndexed { index, n ->
            if (winner >= 0 && winner != index) return@forEachIndexed
            val (x, y) = if (n <= 0) 0f to 1.02f else cell(n)
            val offset = index * 0.018f
            sprites.add(ArcadeSprite(
                x + 0.012f + offset, y + 0.012f + offset, 0.064f, 0.064f,
                index % 4 + CYAN, label = "${index + 1}", kind = 1,
            ))
        }
        val winnerText = if (winner >= 0) "player ${winner + 1} wins" else null
        return ArcadeScene(
            hud = "turn ${turn + 1} · die ${if (lastRoll == 0) "—" else lastRoll} " +
                (if (lastEvent.isNotBlank()) "· $lastEvent" else ""),
            sprites = sprites,
            gridW = 10,
            gridH = 10,
            buttons = listOf(ArcadeButton("ROLL", ArcadeAction.ACTION)),
            message = winnerText,
        )
    }
}

// ── 2048 ───────────────────────────────────────────────────────────────────

private class MergeGame : BaseGame("merge2048") {
    private val grid = IntArray(16)
    private var started = false

    override fun resetGame() {
        grid.fill(0)
        started = false
        spawn()
        spawn()
    }

    private fun spawn() {
        val free = grid.indices.filter { grid[it] == 0 }
        if (free.isEmpty()) return
        grid[free[rng.nextInt(free.size)]] = if (rng.nextInt(10) == 0) 4 else 2
    }

    private fun slide(line: IntArray): Pair<IntArray, Int> {
        val values = line.filter { it != 0 }.toMutableList()
        var gained = 0
        var i = 0
        while (i < values.size - 1) {
            if (values[i] == values[i + 1]) {
                values[i] *= 2
                gained += values[i]
                values.removeAt(i + 1)
            }
            i++
        }
        while (values.size < 4) values.add(0)
        return values.toIntArray() to gained
    }

    private fun move(dir: ArcadeAction) {
        val before = grid.copyOf()
        when (dir) {
            ArcadeAction.LEFT, ArcadeAction.RIGHT -> {
                for (row in 0 until 4) {
                    val indexes = (0 until 4).map { row * 4 + it }
                    val line = indexes.map { grid[it] }.toIntArray()
                    val oriented = if (dir == ArcadeAction.LEFT) line else line.reversedArray()
                    val (merged, gained) = slide(oriented)
                    score += gained
                    val output = if (dir == ArcadeAction.LEFT) merged else merged.reversedArray()
                    indexes.forEachIndexed { i, index -> grid[index] = output[i] }
                }
            }
            ArcadeAction.UP, ArcadeAction.DOWN -> {
                for (col in 0 until 4) {
                    val indexes = (0 until 4).map { it * 4 + col }
                    val line = indexes.map { grid[it] }.toIntArray()
                    val oriented = if (dir == ArcadeAction.UP) line else line.reversedArray()
                    val (merged, gained) = slide(oriented)
                    score += gained
                    val output = if (dir == ArcadeAction.UP) merged else merged.reversedArray()
                    indexes.forEachIndexed { i, index -> grid[index] = output[i] }
                }
            }
            else -> return
        }
        if (!grid.contentEquals(before)) {
            started = true
            spawn()
            if (!canMove()) {
                over = true
                won = grid.any { it >= 2048 }
            }
        }
    }

    private fun canMove(): Boolean {
        if (grid.any { it == 0 }) return true
        for (y in 0 until 4) for (x in 0 until 4) {
            val v = grid[y * 4 + x]
            if (x < 3 && grid[y * 4 + x + 1] == v) return true
            if (y < 3 && grid[(y + 1) * 4 + x] == v) return true
        }
        return false
    }

    override fun tick(dt: Float) = Unit

    override fun onInput(input: ArcadeInput) {
        (input.swipe ?: input.action)?.let {
            if (it == ArcadeAction.LEFT || it == ArcadeAction.RIGHT ||
                it == ArcadeAction.UP || it == ArcadeAction.DOWN) move(it)
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = grid.indices.mapNotNull { index ->
            val value = grid[index]
            if (value == 0) return@mapNotNull null
            val x = index % 4
            val y = index / 4
            val colour = when {
                value <= 4 -> CYAN
                value <= 16 -> BLUE
                value <= 64 -> MAGENTA
                value <= 256 -> ORANGE
                value <= 1024 -> YELLOW
                else -> GREEN
            }
            tile(x, y, 4, 4, colour, label = value.toString(), inset = 0.09f)
        }
        return ArcadeScene(
            hud = "score $score · highest ${grid.maxOrNull() ?: 0}",
            sprites = sprites,
            gridW = 4,
            gridH = 4,
            message = when {
                over && won -> "2048 reached"
                over -> "no moves left"
                !started -> "swipe to begin"
                else -> null
            },
        )
    }
}

// ── Brick Breaker ──────────────────────────────────────────────────────────

private class BrickGame : BaseGame("brick") {
    private val cols = 8
    private val rows = 5
    private val bricks = BooleanArray(cols * rows) { true }
    private var paddle = 0.5f
    private var ballX = 0.5f
    private var ballY = 0.82f
    private var vx = 0.38f
    private var vy = -0.5f
    private var lives = 3
    private var launched = false

    override fun resetGame() {
        bricks.fill(true)
        paddle = 0.5f
        lives = 3
        launched = false
        resetBall()
    }

    private fun resetBall() {
        ballX = 0.5f
        ballY = 0.82f
        vx = 0.38f
        vy = -0.5f
        launched = false
    }

    private fun brickAt(x: Float, y: Float): Int {
        if (y < 0.08f || y > 0.08f + 0.42f) return -1
        val col = ((x - 0.04f) / 0.92f * cols).toInt()
        val row = ((y - 0.08f) / 0.42f * rows).toInt()
        if (col !in 0 until cols || row !in 0 until rows) return -1
        val index = row * cols + col
        return if (bricks[index]) index else -1
    }

    override fun tick(dt: Float) {
        if (!launched) return
        ballX += vx * dt
        ballY += vy * dt
        if (ballX < 0.03f) { ballX = 0.03f; vx = abs(vx) }
        if (ballX > 0.97f) { ballX = 0.97f; vx = -abs(vx) }
        if (ballY < 0.03f) { ballY = 0.03f; vy = abs(vy) }

        val hit = brickAt(ballX, ballY)
        if (hit >= 0) {
            bricks[hit] = false
            score += 25
            vy = -vy
            if (bricks.none { it }) {
                over = true
                won = true
                score += 250
            }
        }

        if (ballY > 0.9f && abs(ballX - paddle) < 0.11f && vy > 0f) {
            ballY = 0.9f
            vy = -abs(vy)
            vx = ((ballX - paddle) * 2.2f).coerceIn(-0.55f, 0.55f)
        }

        if (ballY > 1.05f) {
            lives--
            if (lives <= 0) over = true else resetBall()
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.dragX >= 0f) {
            paddle = input.dragX.coerceIn(0.08f, 0.92f)
        }
        if (input.action == ArcadeAction.ACTION || input.tapX >= 0f) {
            if (!launched && lives > 0) launched = true
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(80)
        for (row in 0 until rows) for (col in 0 until cols) {
            if (!bricks[row * cols + col]) continue
            val x = 0.04f + col * (0.92f / cols)
            val y = 0.08f + row * (0.42f / rows)
            sprites.add(ArcadeSprite(x + 0.006f, y + 0.006f, 0.92f / cols - 0.012f, 0.42f / rows - 0.012f,
                if (row % 2 == 0) CYAN else MAGENTA))
        }
        sprites.add(ArcadeSprite(paddle - 0.1f, 0.92f, 0.2f, 0.035f, GREEN, kind = 0))
        sprites.add(ArcadeSprite(ballX - 0.018f, ballY - 0.018f, 0.036f, 0.036f, WHITE, kind = 1))
        return ArcadeScene(
            hud = "score $score · lives $lives",
            sprites = sprites,
            aspect = 1.1f,
            buttons = listOf(ArcadeButton(if (launched) "LIVE" else "LAUNCH", ArcadeAction.ACTION)),
            message = when {
                over && won -> "wall cleared"
                over -> "out of lives"
                !launched -> "drag to move, tap to launch"
                else -> null
            },
        )
    }
}

// ── Jetpack Flap ───────────────────────────────────────────────────────────

private class FlapGame : BaseGame("flap") {
    private data class Pipe(val x: Float, val gap: Float, val scored: Boolean = false)
    private val pipes = ArrayList<Pipe>()
    private var birdY = 0.45f
    private var vy = 0f
    private var spawn = 0f
    private var started = false

    override fun resetGame() {
        pipes.clear()
        birdY = 0.45f
        vy = 0f
        spawn = 0.6f
        started = false
    }

    private fun flap() {
        started = true
        vy = -0.52f
    }

    override fun tick(dt: Float) {
        if (!started) return
        vy += 1.5f * dt
        birdY += vy * dt
        spawn -= dt
        if (spawn <= 0f) {
            spawn = (1.35f - min(0.6f, score * 0.006f)).coerceAtLeast(0.7f)
            pipes.add(Pipe(1.1f, rng.nextFloat() * 0.55f + 0.2f))
        }
        var scoreGained = 0
        val nextPipes = ArrayList<Pipe>(pipes.size)
        pipes.forEach { pipe ->
            val moved = pipe.copy(x = pipe.x - 0.32f * dt)
            if (moved.x > -0.35f) {
                val passed = moved.scored || moved.x + 0.18f < 0.2f
                if (passed && !moved.scored) scoreGained++
                nextPipes.add(if (passed) moved.copy(scored = true) else moved)
            }
        }
        score += scoreGained
        pipes.clear()
        pipes.addAll(nextPipes)
        if (birdY < 0.02f || birdY > 0.98f) {
            over = true
            return
        }
        for (pipe in pipes) {
            if (pipe.x < 0.28f && pipe.x + 0.18f > 0.12f) {
                val top = pipe.gap - 0.13f
                val bottom = pipe.gap + 0.13f
                if (birdY < top || birdY > bottom) {
                    over = true
                    return
                }
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX >= 0f || input.action == ArcadeAction.ACTION) flap()
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(20)
        pipes.forEach { pipe ->
            sprites.add(ArcadeSprite(pipe.x, 0f, 0.18f, (pipe.gap - 0.13f).coerceAtLeast(0f), GREEN))
            sprites.add(ArcadeSprite(pipe.x, pipe.gap + 0.13f, 0.18f, 1f - (pipe.gap + 0.13f), GREEN))
        }
        sprites.add(ArcadeSprite(0.2f - 0.025f, birdY - 0.025f, 0.05f, 0.05f, YELLOW, kind = 1))
        return ArcadeScene(
            hud = "gaps $score",
            sprites = sprites,
            aspect = 0.72f,
            buttons = listOf(ArcadeButton("FLAP", ArcadeAction.ACTION)),
            message = if (over) "down" else if (!started) "tap to start" else null,
        )
    }
}

// ── Gem Cascade (match-3) ──────────────────────────────────────────────────

private class MatchGame : BaseGame("match3") {
    private val n = 8
    private val board = Array(n) { IntArray(n) }
    private var selectedX = -1
    private var selectedY = -1
    private var timeLeft = 90f

    override fun resetGame() {
        timeLeft = 90f
        selectedX = -1
        selectedY = -1
        do {
            for (y in 0 until n) for (x in 0 until n) board[y][x] = rng.nextInt(5)
        } while (findMatches().isNotEmpty())
    }

    private fun findMatches(): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (y in 0 until n) {
            var run = 1
            for (x in 1..n) {
                if (x < n && board[y][x] == board[y][x - 1]) run++ else {
                    if (run >= 3) for (i in x - run until x) out.add(i to y)
                    run = 1
                }
            }
        }
        for (x in 0 until n) {
            var run = 1
            for (y in 1..n) {
                if (y < n && board[y][x] == board[y - 1][x]) run++ else {
                    if (run >= 3) for (i in y - run until y) out.add(x to i)
                    run = 1
                }
            }
        }
        return out
    }

    private fun resolve() {
        var chain = 0
        while (true) {
            val matches = findMatches()
            if (matches.isEmpty()) break
            chain++
            score += matches.size * 20 * chain
            matches.forEach { (x, y) -> board[y][x] = -1 }
            for (x in 0 until n) {
                var write = n - 1
                for (y in n - 1 downTo 0) {
                    if (board[y][x] >= 0) {
                        board[write][x] = board[y][x]
                        write--
                    }
                }
                while (write >= 0) {
                    board[write][x] = rng.nextInt(5)
                    write--
                }
            }
        }
    }

    private fun swap(a: Pair<Int, Int>, b: Pair<Int, Int>) {
        val tmp = board[a.second][a.first]
        board[a.second][a.first] = board[b.second][b.first]
        board[b.second][b.first] = tmp
    }

    override fun tick(dt: Float) {
        timeLeft -= dt
        if (timeLeft <= 0f) {
            timeLeft = 0f
            over = true
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f) return
        val x = (input.tapX * n).toInt().coerceIn(0, n - 1)
        val y = (input.tapY * n).toInt().coerceIn(0, n - 1)
        if (selectedX < 0) {
            selectedX = x
            selectedY = y
            return
        }
        val distance = abs(x - selectedX) + abs(y - selectedY)
        if (distance == 1) {
            swap(selectedX to selectedY, x to y)
            if (findMatches().isEmpty()) {
                swap(selectedX to selectedY, x to y)
            } else {
                resolve()
            }
        }
        selectedX = -1
        selectedY = -1
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(70)
        for (y in 0 until n) for (x in 0 until n) {
            val gem = board[y][x]
            val label = when (gem) { 0 -> "●"; 1 -> "◆"; 2 -> "▲"; 3 -> "■"; else -> "★" }
            sprites.add(tile(x, y, n, n, gem.coerceAtLeast(0), label = label, inset = 0.1f))
        }
        if (selectedX >= 0) {
            sprites.add(ArcadeSprite(
                selectedX.toFloat() / n + 0.01f,
                selectedY.toFloat() / n + 0.01f,
                1f / n - 0.02f, 1f / n - 0.02f, WHITE, kind = 2,
            ))
        }
        return ArcadeScene(
            hud = "score $score · ${timeLeft.toInt()}s",
            sprites = sprites,
            gridW = n,
            gridH = n,
            message = if (over) "time" else null,
        )
    }
}

// ── Tower Defense ──────────────────────────────────────────────────────────

private class TowerGame : BaseGame("tower") {
    private val n = 12
    private val path = ArrayList<Pair<Int, Int>>()
    private data class Enemy(var progress: Float, var hp: Float)
    private data class Tower(val x: Int, val y: Int, var level: Int, var cooldown: Float)
    private val enemies = ArrayList<Enemy>()
    private val towers = ArrayList<Tower>()
    private var gold = 120
    private var lives = 10
    private var wave = 0
    private var toSpawn = 0
    private var spawnTimer = 0f
    private var waveActive = false

    init {
        var x = 0
        var y = 5
        path.add(x to y)
        while (x < n - 1) { x++; path.add(x to y) }
        y++; path.add(x to y)
        while (x > 0) { x--; path.add(x to y) }
        y++; path.add(x to y)
        while (x < n - 1) { x++; path.add(x to y) }
        y++; path.add(x to y)
        while (x > 0) { x--; path.add(x to y) }
    }

    override fun resetGame() {
        enemies.clear()
        towers.clear()
        gold = 120
        lives = 10
        wave = 0
        toSpawn = 0
        spawnTimer = 0f
        waveActive = false
    }

    private fun startWave() {
        if (waveActive) return
        wave++
        toSpawn = 4 + wave * 2
        spawnTimer = 0f
        waveActive = true
        gold += 15
    }

    override fun tick(dt: Float) {
        if (over) return
        if (waveActive) {
            spawnTimer -= dt
            if (toSpawn > 0 && spawnTimer <= 0f) {
                spawnTimer = 0.55f
                toSpawn--
                enemies.add(Enemy(0f, (12f + wave * 6f)))
            }
            if (toSpawn == 0 && enemies.isEmpty()) {
                waveActive = false
                gold += 25 + wave * 5
                if (wave >= 12) {
                    over = true; won = true
                }
            }
        }

        val speed = 0.55f + wave * 0.02f
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.progress += speed * dt
            if (enemy.progress >= path.size - 1) {
                iterator.remove()
                lives--
                if (lives <= 0) {
                    over = true
                    return
                }
            }
        }

        towers.forEach { tower ->
            tower.cooldown -= dt
            if (tower.cooldown > 0f) return@forEach
            val target = enemies.minByOrNull { enemy ->
                val index = enemy.progress.toInt().coerceIn(0, path.size - 1)
                val p = path[index]
                abs(p.first - tower.x) + abs(p.second - tower.y)
            } ?: return@forEach
            val index = target.progress.toInt().coerceIn(0, path.size - 1)
            val p = path[index]
            val distance = hypot((p.first - tower.x).toFloat(), (p.second - tower.y).toFloat())
            if (distance <= 3.5f + tower.level * 0.6f) {
                target.hp -= 5f + tower.level * 4f
                tower.cooldown = (0.8f - tower.level * 0.05f).coerceAtLeast(0.25f)
                if (target.hp <= 0f) {
                    enemies.remove(target)
                    gold += 8 + wave
                    score += 15
                }
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action == ArcadeAction.NEXT || input.action == ArcadeAction.ACTION) {
            startWave()
            return
        }
        if (input.tapX < 0f) return
        val x = (input.tapX * n).toInt().coerceIn(0, n - 1)
        val y = (input.tapY * n).toInt().coerceIn(0, n - 1)
        if (path.contains(x to y)) return
        val existing = towers.firstOrNull { it.x == x && it.y == y }
        if (existing != null) {
            val cost = 60 + existing.level * 40
            if (gold >= cost && existing.level < 4) {
                gold -= cost
                existing.level++
                score += 20
            }
        } else if (gold >= 50) {
            gold -= 50
            towers.add(Tower(x, y, 1, 0f))
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(220)
        path.forEach { (x, y) -> sprites.add(tile(x, y, n, n, DIM, inset = 0.02f)) }
        towers.forEach { tower ->
            sprites.add(tile(tower.x, tower.y, n, n, CYAN, label = "T${tower.level}"))
        }
        enemies.forEach { enemy ->
            val index = enemy.progress.toInt().coerceIn(0, path.size - 1)
            val p = path[index]
            sprites.add(tile(p.first, p.second, n, n, MAGENTA, alpha = 0.9f))
        }
        return ArcadeScene(
            hud = "wave $wave · gold $gold · lives $lives · score $score",
            sprites = sprites,
            gridW = n,
            gridH = n,
            buttons = listOf(ArcadeButton(if (waveActive) "IN WAVE" else "NEXT WAVE", ArcadeAction.NEXT)),
            message = when {
                over && won -> "the road is held"
                over -> "the hive road fell"
                !waveActive && wave == 0 -> "tap open ground to build"
                else -> null
            },
        )
    }
}

// ── Prism Split ────────────────────────────────────────────────────────────

private class SplitGame : BaseGame("split") {
    private val n = 7
    private val mirrors = ArrayList<Pair<Int, Int>>()
    private val orientation = ArrayList<Int>()
    private var targetRow = 3
    private var solved = false
    private var solveTimer = 0f
    private var moves = 0

    override fun resetGame() {
        generate()
    }

    private fun generate() {
        // A LAYOUT, THEN A MISS. The old generator re-rolled by calling itself when it drew a board
        // that was already finished, which turns bad luck into a stack overflow -- and the player
        // reads either one as "the game does not start". Bounded retries plus a final guaranteed
        // miss keep every puzzle playable and every tap meaningful.
        var attempt = 0
        while (attempt < 32) {
            attempt++
            mirrors.clear()
            orientation.clear()
            val c1 = 1 + rng.nextInt(2)
            val c2 = 4 + rng.nextInt(2)
            val r2 = if (rng.nextBoolean()) 1 else 5
            val r3 = if (r2 == 1) 3 else 2
            targetRow = r3
            val points = listOf(c1 to 3, c1 to r2, c2 to r2, c2 to r3)
            points.forEach { p ->
                mirrors.add(p)
                orientation.add(rng.nextInt(2))
            }
            solved = false
            solveTimer = 0f
            moves = 0
            // Never hand the player a puzzle that is already finished. Flipping mirrors one at a
            // time until the beam misses is enough for every geometry that gets this far.
            var flips = 0
            while (beam().second && flips < 12) {
                orientation[flips % orientation.size] = 1 - orientation[flips % orientation.size]
                flips++
            }
            if (!beam().second) return
        }
        // The board never produced a miss, which the geometry says cannot happen while a mirror sits
        // on the entry row. Turn that first mirror away from the entry row rather than recurse; the
        // NEW PUZZLE button is still there if this board is awkward.
        if (mirrors.isNotEmpty()) orientation[0] = 1 - orientation[0]
        if (beam().second) targetRow = (targetRow + 1) % n
        solved = false
    }

    private fun beam(): Pair<Set<Pair<Int, Int>>, Boolean> {
        val visited = HashSet<Pair<Int, Int>>()
        var x = 0
        var y = 3
        var dx = 1
        var dy = 0
        var steps = 0
        while (x in 0 until n && y in 0 until n && steps++ < 120) {
            visited.add(x to y)
            if (x == n - 1 && y == targetRow) return visited to true
            val mirror = mirrors.indexOfFirst { it.first == x && it.second == y }
            if (mirror >= 0) {
                val slash = orientation[mirror] == 0
                val ndx: Int
                val ndy: Int
                if (slash) {
                    ndx = -dy
                    ndy = -dx
                } else {
                    ndx = dy
                    ndy = dx
                }
                dx = ndx
                dy = ndy
            }
            x += dx
            y += dy
        }
        return visited to false
    }

    override fun tick(dt: Float) {
        if (solved) {
            solveTimer += dt
            if (solveTimer > 0.75f) generate()
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action == ArcadeAction.NEXT) {
            generate()
            return
        }
        if (solved || input.tapX < 0f) return
        val x = (input.tapX * n).toInt().coerceIn(0, n - 1)
        val y = (input.tapY * n).toInt().coerceIn(0, n - 1)
        val index = mirrors.indexOfFirst { it.first == x && it.second == y }
        if (index < 0) return
        orientation[index] = 1 - orientation[index]
        moves++
        val (_, reached) = beam()
        if (reached) {
            solved = true
            score += 25
            solveTimer = 0f
        }
    }

    override fun scene(): ArcadeScene {
        val (lit, _) = beam()
        val sprites = ArrayList<ArcadeSprite>(90)
        lit.forEach { (x, y) ->
            sprites.add(ArcadeSprite(
                x.toFloat() / n + 0.42f / n, y.toFloat() / n + 0.42f / n,
                0.16f / n, 0.16f / n, YELLOW, kind = 1, alpha = 0.8f,
            ))
        }
        sprites.add(ArcadeSprite(0f, 3f / n + 0.02f, 0.07f, 1f / n - 0.04f, CYAN))
        sprites.add(ArcadeSprite((n - 1).toFloat() / n + 0.02f, targetRow.toFloat() / n + 0.02f,
            1f / n - 0.04f, 1f / n - 0.04f, GREEN, label = "◎"))
        mirrors.forEachIndexed { index, (x, y) ->
            sprites.add(ArcadeSprite(
                x.toFloat() / n + 0.06f / n, y.toFloat() / n + 0.06f / n,
                1f / n - 0.12f / n, 1f / n - 0.12f / n, MAGENTA,
                label = if (orientation[index] == 0) "╱" else "╲",
            ))
        }
        return ArcadeScene(
            hud = "solved $score · moves $moves",
            sprites = sprites,
            gridW = n,
            gridH = n,
            buttons = listOf(ArcadeButton("NEW PUZZLE", ArcadeAction.NEXT)),
            message = when {
                solved -> "beam connected"
                moves == 0 -> "tap a mirror to rotate it"
                else -> null
            },
        )
    }
}

// ── Gravity Well ───────────────────────────────────────────────────────────

private class GravityGame : BaseGame("gravity") {
    private data class Mote(var angle: Float, val radius: Float, val mine: Boolean, val spin: Float)
    private val motes = ArrayList<Mote>()
    private var angle = 0f
    private var direction = 1f
    private var speed = 1.45f
    private var lives = 3
    private var spawn = 0f
    private var runTime = 0f

    override fun resetGame() {
        motes.clear()
        angle = 0f
        direction = 1f
        speed = 1.45f
        lives = 3
        spawn = 0.2f
        runTime = 0f
        score = 0
    }

    private fun add(mine: Boolean) {
        motes.add(Mote(
            angle = rng.nextFloat() * 6.2832f,
            radius = 0.28f + rng.nextFloat() * 0.16f,
            mine = mine,
            spin = (if (rng.nextBoolean()) 1f else -1f) * (0.12f + rng.nextFloat() * 0.25f),
        ))
    }

    override fun tick(dt: Float) {
        runTime += dt
        speed = 1.45f + min(1.1f, runTime * 0.018f)
        angle = (angle + direction * speed * dt) % 6.2832f
        spawn -= dt
        if (spawn <= 0f) {
            spawn = 0.42f
            add(rng.nextInt(3) == 0)
        }
        val passed = ArrayList<Mote>(motes.size)
        motes.forEach { mote ->
            mote.angle = (mote.angle + mote.spin * dt + 6.2832f) % 6.2832f
            val d = abs(((mote.angle - angle + 3.1416f) % 6.2832f) - 3.1416f)
            if (d < 0.16f) {
                if (mote.mine) {
                    lives--
                    if (lives <= 0) {
                        over = true
                        return
                    }
                } else {
                    score += 10
                }
            } else {
                passed.add(mote)
            }
        }
        if (over) return
        motes.clear()
        motes.addAll(passed)
        if (motes.size > 24) motes.removeAt(0)
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX >= 0f || input.action == ArcadeAction.ACTION || input.action == ArcadeAction.SWITCH) {
            direction = -direction
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(30)
        sprites.add(ArcadeSprite(0.38f, 0.38f, 0.24f, 0.24f, BLUE, kind = 1))
        sprites.add(ArcadeSprite(0.43f, 0.43f, 0.14f, 0.14f, CYAN, kind = 2))
        motes.forEach { mote ->
            val x = 0.5f + cos(mote.angle) * mote.radius
            val y = 0.5f + sin(mote.angle) * mote.radius
            sprites.add(ArcadeSprite(x - 0.025f, y - 0.025f, 0.05f, 0.05f,
                if (mote.mine) RED else YELLOW, kind = 1))
        }
        val sx = 0.5f + cos(angle) * 0.34f
        val sy = 0.5f + sin(angle) * 0.34f
        sprites.add(ArcadeSprite(sx - 0.03f, sy - 0.03f, 0.06f, 0.06f, WHITE, kind = 1))
        return ArcadeScene(
            hud = "score $score · lives $lives",
            sprites = sprites,
            buttons = listOf(ArcadeButton("REVERSE", ArcadeAction.SWITCH)),
            message = if (over) "the well took you" else null,
        )
    }
}

// ── Fuse Chain ─────────────────────────────────────────────────────────────

private class FuseGame : BaseGame("fuse") {
    private val n = 8
    private val fuse = Array(n) { IntArray(n) }
    private val lit = Array(n) { FloatArray(n) }
    private var timeLeft = 22f
    private var level = 1

    override fun resetGame() {
        level = 1
        timeLeft = 22f
        fill()
    }

    private fun fill() {
        fuse.forEach { it.fill(0) }
        lit.forEach { it.fill(0f) }
        val count = 10 + level * 3
        var placed = 0
        while (placed < count) {
            val x = rng.nextInt(n)
            val y = rng.nextInt(n)
            if (fuse[y][x] == 0) {
                fuse[y][x] = 1 + rng.nextInt(3)
                placed++
            }
        }
    }

    override fun tick(dt: Float) {
        timeLeft -= dt
        if (timeLeft <= 0f) {
            over = true
            return
        }
        for (y in 0 until n) for (x in 0 until n) {
            if (lit[y][x] <= 0f) continue
            lit[y][x] -= dt
            if (lit[y][x] <= 0f) explode(x, y)
        }
        if (fuse.all { row -> row.all { it == 0 } }) {
            level++
            score += 100 * level
            timeLeft = (timeLeft + 12f).coerceAtMost(35f)
            fill()
        }
    }

    private fun explode(x: Int, y: Int) {
        if (fuse[y][x] == 0) return
        fuse[y][x] = 0
        score += 20
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until n && ny in 0 until n && fuse[ny][nx] > 0 && lit[ny][nx] <= 0f) {
                lit[ny][nx] = fuse[ny][nx] * 0.16f + 0.08f
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f) return
        val x = (input.tapX * n).toInt().coerceIn(0, n - 1)
        val y = (input.tapY * n).toInt().coerceIn(0, n - 1)
        if (fuse[y][x] > 0 && lit[y][x] <= 0f) lit[y][x] = fuse[y][x] * 0.16f + 0.08f
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(80)
        for (y in 0 until n) for (x in 0 until n) {
            if (fuse[y][x] == 0) continue
            sprites.add(tile(x, y, n, n,
                when (fuse[y][x]) { 1 -> GREEN; 2 -> YELLOW; else -> RED },
                label = fuse[y][x].toString(), inset = 0.12f))
        }
        for (y in 0 until n) for (x in 0 until n) {
            if (lit[y][x] > 0f) {
                sprites.add(tile(x, y, n, n, WHITE, label = "✦", inset = 0.04f))
            }
        }
        return ArcadeScene(
            hud = "level $level · score $score · ${timeLeft.toInt()}s",
            sprites = sprites,
            gridW = n,
            gridH = n,
            message = if (over) "the fuse burned out" else null,
        )
    }
}

// ── Hive Rush ──────────────────────────────────────────────────────────────

private class HiveGame : BaseGame("hive") {
    private data class Bug(var x: Float, var y: Float, val vx: Float, val vy: Float, val wing: Float)
    private val bugs = ArrayList<Bug>()
    private var lives = 5
    private var spawn = 0.7f
    private var elapsed = 0f
    private var heldFor = 0f

    override fun resetGame() {
        bugs.clear()
        lives = 5
        spawn = 0.7f
        elapsed = 0f
        heldFor = 0f
    }

    override fun tick(dt: Float) {
        elapsed += dt
        heldFor = elapsed
        if (elapsed >= 60f) {
            over = true
            won = true
            score += 100
            return
        }
        spawn -= dt
        if (spawn <= 0f) {
            spawn = (1.05f - min(0.7f, elapsed * 0.012f)).coerceAtLeast(0.22f)
            val edge = rng.nextInt(4)
            val x = if (edge == 0 || edge == 1) rng.nextFloat() else if (edge == 2) 0f else 1f
            val y = if (edge == 0) 0f else if (edge == 1) 1f else rng.nextFloat()
            val dx = 0.5f - x
            val dy = 0.5f - y
            val length = hypot(dx, dy).coerceAtLeast(0.01f)
            bugs.add(Bug(x, y, dx / length * 0.1f, dy / length * 0.1f, rng.nextFloat() * 6f))
        }
        val iterator = bugs.iterator()
        while (iterator.hasNext()) {
            val bug = iterator.next()
            bug.x += bug.vx * dt
            bug.y += bug.vy * dt
            if (hypot(bug.x - 0.5f, bug.y - 0.5f) < 0.09f) {
                iterator.remove()
                lives--
                if (lives <= 0) {
                    over = true
                    return
                }
            }
        }
        score += 0
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f) return
        var best: Bug? = null
        var bestDistance = 0.09f
        bugs.forEach { bug ->
            val d = hypot(bug.x - input.tapX, bug.y - input.tapY)
            if (d < bestDistance) {
                bestDistance = d
                best = bug
            }
        }
        best?.let {
            bugs.remove(it)
            score += 15
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(40)
        bugs.forEach { bug ->
            sprites.add(ArcadeSprite(bug.x - 0.025f, bug.y - 0.025f, 0.05f, 0.05f, MAGENTA, label = "✦", kind = 1))
        }
        sprites.add(ArcadeSprite(0.41f, 0.41f, 0.18f, 0.18f, YELLOW, label = "⬡", kind = 1))
        sprites.add(ArcadeSprite(0.45f, 0.45f, 0.1f, 0.1f, ORANGE, kind = 1))
        return ArcadeScene(
            hud = "score $score · hive $lives · ${(60 - heldFor).toInt().coerceAtLeast(0)}s",
            sprites = sprites,
            message = when {
                over && won -> "the hive holds"
                over -> "the hive is overrun"
                else -> null
            },
        )
    }
}

// ── Static Storm ───────────────────────────────────────────────────────────

private class StormGame : BaseGame("storm") {
    private data class Orb(var x: Float, var y: Float, val vx: Float, val vy: Float, val colour: Int)
    private val orbs = ArrayList<Orb>()
    private var shield = 0
    private var lives = 3
    private var spawn = 0.6f
    private var elapsed = 0f
    private var heldFor = 0f

    override fun resetGame() {
        orbs.clear()
        shield = 0
        lives = 3
        spawn = 0.6f
        elapsed = 0f
        heldFor = 0f
    }

    override fun tick(dt: Float) {
        elapsed += dt
        heldFor = elapsed
        if (elapsed >= 60f) {
            over = true
            won = true
            score += 150
            return
        }
        spawn -= dt
        if (spawn <= 0f) {
            spawn = (0.75f - min(0.45f, elapsed * 0.008f)).coerceAtLeast(0.26f)
            val angle = rng.nextFloat() * 6.2832f
            val radius = 0.66f
            val x = 0.5f + cos(angle) * radius
            val y = 0.5f + sin(angle) * radius
            val dx = 0.5f - x
            val dy = 0.5f - y
            val length = hypot(dx, dy).coerceAtLeast(0.01f)
            val speed = 0.13f + min(0.12f, elapsed * 0.002f)
            orbs.add(Orb(x, y, dx / length * speed, dy / length * speed, rng.nextInt(2)))
        }
        val iterator = orbs.iterator()
        while (iterator.hasNext()) {
            val orb = iterator.next()
            orb.x += orb.vx * dt
            orb.y += orb.vy * dt
            if (hypot(orb.x - 0.5f, orb.y - 0.5f) < 0.075f) {
                iterator.remove()
                if (orb.colour == shield) {
                    score += 12
                } else {
                    lives--
                    if (lives <= 0) {
                        over = true
                        return
                    }
                }
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX >= 0f || input.action == ArcadeAction.SWITCH || input.action == ArcadeAction.ACTION) {
            shield = 1 - shield
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>(40)
        orbs.forEach { orb ->
            sprites.add(ArcadeSprite(orb.x - 0.026f, orb.y - 0.026f, 0.052f, 0.052f, orb.colour, kind = 1))
        }
        sprites.add(ArcadeSprite(0.39f, 0.39f, 0.22f, 0.22f, shield, kind = 2))
        sprites.add(ArcadeSprite(0.46f, 0.46f, 0.08f, 0.08f, WHITE, kind = 1))
        return ArcadeScene(
            hud = "score $score · shield ${if (shield == 0) "CYAN" else "MAGENTA"} · ${lives} lives · ${(60 - heldFor).toInt().coerceAtLeast(0)}s",
            sprites = sprites,
            buttons = listOf(ArcadeButton("SWITCH", ArcadeAction.SWITCH)),
            message = when {
                over && won -> "you outlasted the storm"
                over -> "the storm grounded you"
                else -> null
            },
        )
    }
}
