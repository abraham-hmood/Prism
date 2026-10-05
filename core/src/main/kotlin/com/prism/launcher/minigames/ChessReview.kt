package com.prism.launcher.minigames

/**
 * Turning a finished game into words.
 *
 * ## What "review" means here
 *
 * The same thing it means on every serious chess site: replay the game, and at every move ask the
 * engine what it would have played instead. The gap between what the engine found and what the
 * played move achieves — both searched to the same depth, so the comparison is fair — is how much
 * the move cost. That gap is a number ([MoveReview.centipawnLoss]); [MoveReview.note] is that
 * number translated into a sentence, because a player reading "-180cp" has to already know what
 * this file knows, and the point of a review is to teach somebody who does not.
 *
 * ## Why this belongs in core
 *
 * It touches nothing Android: a finished game is a starting position and a list of [Chess.Move]s,
 * and [review] is a pure function of that list. Keeping it here is what makes it testable without a
 * device, the same reason the rest of the engine lives here.
 */
object ChessReview {

    /** How well a move measured up. Ordered worst to best isn't used; ordered as a player reads it. */
    enum class Verdict(val label: String) {
        BEST("Best move"),
        EXCELLENT("Excellent"),
        GOOD("Good"),
        INACCURACY("Inaccuracy"),
        MISTAKE("Mistake"),
        BLUNDER("Blunder"),
    }

    /**
     * What happened on one ply.
     *
     * @param centipawnLoss how much worse the played move was than the engine's own choice, from
     *   the mover's own point of view. Zero when they are the same move.
     * @param bestSan the engine's move, in algebraic notation, when it differs from what was played.
     *   Null when the player found it themselves — there is nothing to suggest instead.
     */
    data class MoveReview(
        val ply: Int,
        val mover: Chess.Colour,
        val san: String,
        val centipawnLoss: Int,
        val verdict: Verdict,
        val bestSan: String?,
        val note: String,
    )

    /**
     * How much the engine has to look at each ply to give an honest answer.
     *
     * Matched to [Chess.Difficulty.SHARP] — enough to see past the horizon on ordinary tactics, and
     * fast enough that a forty-move game (eighty searches) finishes in a few seconds on a phone,
     * which is what running it off the UI thread in [ChessReviewView] budgets for.
     */
    const val REVIEW_DEPTH = 4

    /** Centipawn-loss cutoffs a played move falls into, read the way every review site's do. */
    private fun verdictFor(loss: Int): Verdict = when {
        loss <= 0 -> Verdict.BEST
        loss < 25 -> Verdict.EXCELLENT
        loss < 75 -> Verdict.GOOD
        loss < 150 -> Verdict.INACCURACY
        loss < 350 -> Verdict.MISTAKE
        else -> Verdict.BLUNDER
    }

    /**
     * Replays [moves] from the start and grades every one of them.
     *
     * Runs the search twice at each ply — once for the engine's own choice, once for the move that
     * was actually played, unless they are the same move, in which case the second search is
     * skipped because the answer is already known to be zero. A forty-move game is on the order of
     * seventy searches at [REVIEW_DEPTH]; call this off the UI thread.
     */
    fun review(moves: List<Chess.Move>, depth: Int = REVIEW_DEPTH): List<MoveReview> {
        var position = Chess.startingPosition()
        val out = ArrayList<MoveReview>(moves.size)

        moves.forEachIndexed { index, played ->
            val mover = position.sideToMove
            val san = Chess.describe(position, played)
            val line = Chess.bestLine(position, depth)

            val review = if (line == null) {
                // No legal reply the engine can rate this against — the game was already decided.
                MoveReview(index, mover, san, 0, Verdict.BEST, null, "The game was already decided here.")
            } else {
                val (engineMove, engineScore) = line
                if (engineMove == played) {
                    MoveReview(index, mover, san, 0, Verdict.BEST, null, noteFor(Verdict.BEST, san, null, played, position))
                } else {
                    val playedScore = -Chess.searchEval(Chess.apply(position, played), depth - 1)
                    val loss = (engineScore - playedScore).coerceAtLeast(0)
                    val verdict = verdictFor(loss)
                    val bestSan = Chess.describe(position, engineMove)
                    MoveReview(index, mover, san, loss, verdict, bestSan, noteFor(verdict, san, bestSan, played, position))
                }
            }
            out.add(review)
            position = Chess.apply(position, played)
        }
        return out
    }

    /**
     * The sentence a player actually reads.
     *
     * Grounded in what is CHEAPLY and HONESTLY knowable from the move itself — capture, check,
     * castling, which piece — rather than claiming to spot forks or pins the engine was not asked
     * to identify. Naming a tactic it did not verify is worse than not naming one at all.
     */
    private fun noteFor(
        verdict: Verdict,
        san: String,
        bestSan: String?,
        move: Chess.Move,
        before: Chess.Position,
    ): String {
        val givesCheck = san.endsWith("+") || san.endsWith("#")
        val captures = san.contains("x")
        val castles = move.isCastle

        return when (verdict) {
            Verdict.BEST -> when {
                san.endsWith("#") -> "$san — checkmate. The strongest move on the board, and the last one needed."
                givesCheck -> "$san finds the best move on the board, and it comes with check."
                captures -> "$san is exactly what the engine would have played — the best capture available."
                castles -> "$san is the right moment to castle: the engine agrees this is best."
                else -> "$san — the engine's own choice. Nothing on the board was stronger."
            }
            Verdict.EXCELLENT -> "$san is very close to the top choice" +
                (bestSan?.let { " ($it was a hair stronger)" } ?: "") + " — barely a difference in practice."
            Verdict.GOOD -> "$san is a sound, reasonable move" +
                (bestSan?.let { ", though $it kept a little more of the advantage" } ?: "") + "."
            Verdict.INACCURACY -> "$san loosens the position a little" +
                (bestSan?.let { " — $it was the more accurate choice here" } ?: "") + "."
            Verdict.MISTAKE -> "$san gives something away" +
                (bestSan?.let { ". $it was the move: it keeps hold of what $san let go of" } ?: "") + "."
            Verdict.BLUNDER -> "$san is a serious mistake" +
                (bestSan?.let { " — $it was needed instead, or the cost here is heavy" } ?: "") + "."
        }
    }

    // -- Learning how somebody plays -------------------------------------------

    /**
     * A running read on a player's chess, built from every reviewed game rather than from any one
     * of them.
     *
     * ## What this honestly is
     *
     * A statistical summary, not a model of taste or judgement — the engine has no idea WHY a
     * player prefers open positions or avoids trades, only how often their moves cost centipawns
     * and in what phase of the game. That is a real, useful signal (it is what "you are more
     * accurate in the middlegame than the endgame" is built from) and it is the honest limit of
     * what a search engine like this one can actually learn, so [summary] never claims more than
     * that.
     */
    data class PlayerStyle(
        val gamesReviewed: Int,
        val movesReviewed: Int,
        val totalCentipawnLoss: Long,
        val verdictCounts: Map<Verdict, Int>,
        /** Average centipawn loss in the first third of a game's moves, vs the last third. */
        val openingAvgLoss: Double,
        val endgameAvgLoss: Double,
    ) {
        val averageLoss: Double get() = if (movesReviewed == 0) 0.0 else totalCentipawnLoss.toDouble() / movesReviewed

        val blunderRate: Double
            get() = if (movesReviewed == 0) 0.0 else (verdictCounts[Verdict.BLUNDER] ?: 0).toDouble() / movesReviewed

        /** A short, honest coaching line — what the numbers actually show, nothing inferred beyond them. */
        fun summary(): String {
            if (movesReviewed < 6) return "Play a few more reviewed games and this will have something to say."
            val phase = when {
                endgameAvgLoss > openingAvgLoss * 1.4 -> "Accuracy drops off in the endgame — that's usually where the clock and the calculation both get harder."
                openingAvgLoss > endgameAvgLoss * 1.4 -> "The opening costs more than the endgame does — worth slowing down in the first several moves."
                else -> "Accuracy holds up fairly evenly across a game."
            }
            val overall = when {
                averageLoss < 20 -> "Overall accuracy is strong — most moves land at or near the engine's own choice."
                averageLoss < 60 -> "Overall accuracy is solid, with room in the tighter positions."
                averageLoss < 120 -> "There is a real, closeable gap to the engine's own play."
                else -> "Most games are giving up significant ground somewhere — the hints in training mode are worth reading."
            }
            return "$overall $phase"
        }
    }

    /** Folds one game's [review] into a running [PlayerStyle]. */
    fun accumulate(existing: PlayerStyle?, gameReviews: List<MoveReview>): PlayerStyle {
        val base = existing ?: PlayerStyle(0, 0, 0, emptyMap(), 0.0, 0.0)
        if (gameReviews.isEmpty()) return base

        val counts = HashMap(base.verdictCounts)
        gameReviews.forEach { counts[it.verdict] = (counts[it.verdict] ?: 0) + 1 }

        val third = (gameReviews.size / 3).coerceAtLeast(1)
        val opening = gameReviews.take(third)
        val ending = gameReviews.takeLast(third)
        val newOpeningTotal = base.openingAvgLoss * base.gamesReviewed + opening.map { it.centipawnLoss }.average()
        val newEndgameTotal = base.endgameAvgLoss * base.gamesReviewed + ending.map { it.centipawnLoss }.average()
        val games = base.gamesReviewed + 1

        return PlayerStyle(
            gamesReviewed = games,
            movesReviewed = base.movesReviewed + gameReviews.size,
            totalCentipawnLoss = base.totalCentipawnLoss + gameReviews.sumOf { it.centipawnLoss.toLong() },
            verdictCounts = counts,
            openingAvgLoss = newOpeningTotal / games,
            endgameAvgLoss = newEndgameTotal / games,
        )
    }
}
