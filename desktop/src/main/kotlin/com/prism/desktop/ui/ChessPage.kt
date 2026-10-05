package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.minigames.Chess
import com.prism.launcher.minigames.ChessReview
import com.prism.launcher.minigames.MinigameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chess, with the review. PHASE 95.
 *
 * ## What this file is and is not
 *
 * It is not a chess program. `Chess` in `:core` is the rules, the move generator, the evaluation and an
 * alpha-beta search; `ChessReview` grades a finished game ply by ply against the engine's own choice.
 * Both are tested there. This is a board that takes two clicks and a panel that lists what came back.
 *
 * ## Click-click, not drag
 *
 * A drag needs a piece rendered under the cursor, a drop target, and a decision about what a drop on
 * an illegal square means. Click-to-select then click-to-move needs none of that and is what every
 * board on the web settles on for the same reason. Selecting a piece highlights ITS legal moves, taken
 * from `Chess.legalMoves` and filtered -- so an illegal move is not refused, it is not offered.
 *
 * ## Promotion asks rather than assuming a queen
 *
 * Assuming a queen is right almost always and catastrophically wrong in the endgame where a queen is
 * stalemate and a knight is mate. `Chess.Move` carries the promotion piece, so the four choices are
 * shown when a pawn reaches the last rank.
 *
 * ## The review runs off the UI thread and says how long it will take
 *
 * Seventy-odd searches at depth four. On a desktop that is a second or two rather than the phone's
 * several, but it is still long enough that a frozen window would be the wrong way to spend it.
 */
@Composable
fun ChessPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var position by remember { mutableStateOf(Chess.startingPosition()) }
    var history by remember { mutableStateOf<List<Chess.Move>>(emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var promotionFrom by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var difficulty by remember { mutableStateOf(MinigameStore.chessDifficulty()) }
    var training by remember { mutableStateOf(MinigameStore.chessTrainingMode()) }
    var reviews by remember { mutableStateOf<List<ChessReview.MoveReview>>(emptyList()) }
    var reviewing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var recorded by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf(MinigameStore.chessRecord()) }

    val outcome = remember(position) { Chess.outcome(position) }
    val legal = remember(position) { Chess.legalMoves(position) }

    fun finishIfOver(next: Chess.Position, moves: List<Chess.Move>) {
        val result = Chess.outcome(next)
        if (!result.isOver || recorded) return
        recorded = true
        MinigameStore.setLastChessGame(moves)
        val won = (result as? Chess.Outcome.Checkmate)?.winner == Chess.Colour.WHITE
        MinigameStore.recordChess(won)
        record = MinigameStore.chessRecord()
        note = when (result) {
            is Chess.Outcome.Checkmate ->
                if (won) "Checkmate — you won." else "Checkmate — the engine won."
            Chess.Outcome.Stalemate -> "Stalemate."
            Chess.Outcome.FiftyMove -> "Draw by the fifty-move rule."
            Chess.Outcome.Threefold -> "Draw by threefold repetition."
            Chess.Outcome.InsufficientMaterial -> "Draw — insufficient material."
            Chess.Outcome.InPlay -> ""
        }
    }

    fun play(move: Chess.Move) {
        val afterMine = Chess.apply(position, move)
        val moves = history + move
        position = afterMine
        history = moves
        selected = null
        finishIfOver(afterMine, moves)
        if (Chess.outcome(afterMine).isOver) return

        thinking = true
        scope.launch {
            // Off the UI thread: the search is the whole point of the difficulty setting, and at
            // SHARP it is hundreds of milliseconds even here.
            val reply = withContext(Dispatchers.Default) {
                Chess.bestMove(afterMine, difficulty, System.nanoTime())
            }
            thinking = false
            if (reply == null) {
                finishIfOver(afterMine, moves)
                return@launch
            }
            val afterTheirs = Chess.apply(afterMine, reply)
            position = afterTheirs
            history = moves + reply
            finishIfOver(afterTheirs, moves + reply)
        }
    }

    PageScaffold("Chess", "The engine, the review and the training mode from the phone") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    position = Chess.startingPosition()
                    history = emptyList()
                    selected = null
                    reviews = emptyList()
                    recorded = false
                    note = ""
                }) { Text("New game") }
                Spacer(Modifier.width(10.dp))
                Chess.Difficulty.entries.forEach { level ->
                    OutlinedButton(
                        onClick = {
                            difficulty = level
                            MinigameStore.setChessDifficulty(level)
                        },
                        modifier = Modifier.padding(end = 5.dp),
                    ) {
                        Text(
                            level.label,
                            fontSize = 11.sp,
                            color = if (level == difficulty) colors.accent else colors.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    training = !training
                    MinigameStore.setChessTrainingMode(training)
                }) {
                    Text(
                        if (training) "Training: on" else "Training: off",
                        fontSize = 11.sp,
                        color = if (training) colors.accent else colors.onSurface,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    record.first.toString() + "W / " + record.second + "L",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.faint,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row {
                // ── The board ──────────────────────────────────────────────
                Box(Modifier.width(440.dp).aspectRatio(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        // Rank 8 at the top, which is how a board is drawn for white.
                        for (rank in 7 downTo 0) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                for (file in 0 until 8) {
                                    val index = Chess.index(file, rank)
                                    val piece = position.pieceAt(index)
                                    val isSelected = selected == index
                                    val target = selected?.let { from ->
                                        legal.any { it.from == from && it.to == index }
                                    } ?: false

                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                            .background(
                                                when {
                                                    isSelected -> colors.accent.copy(alpha = 0.55f)
                                                    target -> colors.accent.copy(alpha = 0.22f)
                                                    (file + rank) % 2 == 0 -> Color(0xFF2A2A31)
                                                    else -> Color(0xFF3C3C46)
                                                },
                                            )
                                            .clickableRow {
                                                if (thinking) return@clickableRow
                                                if (position.sideToMove != Chess.Colour.WHITE) return@clickableRow
                                                val from = selected
                                                if (from == null) {
                                                    if (position.colourAt(index) == Chess.Colour.WHITE) {
                                                        selected = index
                                                    }
                                                    return@clickableRow
                                                }
                                                val candidates = legal.filter {
                                                    it.from == from && it.to == index
                                                }
                                                when {
                                                    candidates.isEmpty() -> {
                                                        // Re-selecting rather than a no-op: clicking
                                                        // another of your own pieces obviously means
                                                        // "that one instead".
                                                        selected = if (
                                                            position.colourAt(index) == Chess.Colour.WHITE
                                                        ) index else null
                                                    }
                                                    // More than one candidate means a promotion, and
                                                    // that is the only case where it happens.
                                                    candidates.size > 1 -> promotionFrom = from to index
                                                    else -> play(candidates.first())
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (piece != Chess.EMPTY) {
                                            Text(Chess.glyph(piece), fontSize = 30.sp)
                                        }
                                        // A tiny coordinate in the corner, because algebraic notation
                                        // in the move list is unreadable without one.
                                        if (file == 0 || rank == 0) {
                                            Text(
                                                Chess.square(index),
                                                fontSize = 7.sp,
                                                color = Color(0x55FFFFFF),
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // ── Moves and state ───────────────────────────────────────
                Column(Modifier.width(300.dp)) {
                    if (thinking) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = colors.accent, strokeWidth = 2.dp,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Searching…", fontSize = 11.sp, color = colors.faint)
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (Chess.isInCheck(position, position.sideToMove) && !outcome.isOver) {
                        Text(
                            position.sideToMove.name.lowercase() + " is in check",
                            fontSize = 12.sp,
                            color = Color(0xFFFFB4B4),
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    if (note.isNotBlank()) {
                        Surface(
                            color = Color(0xFF1E1E26),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                note,
                                fontSize = 12.sp,
                                color = colors.muted,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (training && !outcome.isOver && position.sideToMove == Chess.Colour.WHITE) {
                        // TRAINING MODE IS THE ENGINE'S OWN CHOICE, SHOWN. Not a hint system with its
                        // own heuristics -- that would teach something the engine does not believe.
                        val best = remember(position) {
                            runCatching { Chess.bestLine(position, 3) }.getOrNull()
                        }
                        Surface(
                            color = Color(0xFF14301F),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                best?.let {
                                    "The engine would play " + Chess.describe(position, it.first) +
                                        " (" + (it.second / 100.0).let { v ->
                                            String.format("%+.2f", v)
                                        } + ")"
                                } ?: "No move to suggest.",
                                fontSize = 11.sp,
                                color = Color(0xFF9BE8B4),
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    SectionHeader("moves")
                    Card {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            if (history.isEmpty()) {
                                Text(
                                    "No moves yet. White to play.",
                                    fontSize = 12.sp,
                                    color = colors.faint,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                            // Replayed from the start so each move can be described in the position it
                            // was played in -- "Nf3" needs to know what else could reach f3.
                            var replay = Chess.startingPosition()
                            history.forEachIndexed { ply, move ->
                                val text = Chess.describe(replay, move)
                                replay = Chess.apply(replay, move)
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        (if (ply % 2 == 0) ((ply / 2) + 1).toString() + "." else "  "),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.faint,
                                        modifier = Modifier.width(26.dp),
                                    )
                                    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    if (outcome.isOver && history.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = !reviewing,
                            onClick = {
                                reviewing = true
                                scope.launch {
                                    val graded = withContext(Dispatchers.Default) {
                                        runCatching { ChessReview.review(history) }
                                            .getOrDefault(emptyList())
                                    }
                                    reviews = graded
                                    reviewing = false
                                    // Once per game, which is what the store's name says: accumulating
                                    // the same game twice would move the style profile on nothing.
                                    runCatching {
                                        MinigameStore.accumulateChessStyleOnce(history, graded)
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (reviewing) "Reviewing…"
                                else "Review the game (" + history.size + " plies)",
                            )
                        }
                    }
                }
            }

            if (reviews.isNotEmpty()) {
                SectionHeader("review")
                Card {
                    Column {
                        reviews.forEachIndexed { i, review ->
                            if (i > 0) Hairline()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = verdictColour(review.verdict),
                                    shape = CircleShape,
                                    modifier = Modifier.size(8.dp),
                                ) {}
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    review.san,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(72.dp),
                                )
                                Text(
                                    review.verdict.label,
                                    fontSize = 11.sp,
                                    color = verdictColour(review.verdict),
                                    modifier = Modifier.width(92.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(review.note, fontSize = 11.sp, color = colors.muted, lineHeight = 16.sp)
                                    review.bestSan?.let {
                                        Text(
                                            "engine: " + it + "  (−" + review.centipawnLoss + " cp)",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                MinigameStore.chessStyle()?.let { style ->
                    SectionFooter(style.summary())
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // ── Promotion ───────────────────────────────────────────────────────────
    promotionFrom?.let { (from, to) ->
        val options = legal.filter { it.from == from && it.to == to }
        Surface(
            color = Color(0xFF1A1A20),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Promote to", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    options.forEach { option ->
                        OutlinedButton(
                            onClick = {
                                promotionFrom = null
                                play(option)
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Text(Chess.glyph(option.promotion), fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Read the way every review site's colours are, because the scale is the same one. */
private fun verdictColour(verdict: ChessReview.Verdict): Color = when (verdict) {
    ChessReview.Verdict.BEST -> Color(0xFF4FC978)
    ChessReview.Verdict.EXCELLENT -> Color(0xFF7FCB8E)
    ChessReview.Verdict.GOOD -> Color(0xFFB9B9C4)
    ChessReview.Verdict.INACCURACY -> Color(0xFFE0C060)
    ChessReview.Verdict.MISTAKE -> Color(0xFFE08A50)
    ChessReview.Verdict.BLUNDER -> Color(0xFFE06060)
}
