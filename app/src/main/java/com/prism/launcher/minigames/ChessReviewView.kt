package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The post-game review: every move, graded, and explained in words.
 *
 * ## What "press Review Match" actually runs
 *
 * [ChessReview.review] re-searches the whole game, once per ply, which is real work -- on the
 * order of a search per move at [ChessReview.REVIEW_DEPTH], the same depth "Sharp" plays at. Done
 * on the UI thread that is a frozen screen for several seconds; done here, off it, the screen shows
 * "Reviewing…" and fills in when it is ready, which is the same pattern [ChessBoardView] already
 * uses for the computer's own move.
 *
 * [onDone] fires once, when the player leaves this screen, carrying the finished review so the host
 * can fold it into [MinigameStore.chessStyle] — see [MinigamesPageView.openChessReview] for why that
 * happens on the way OUT rather than the moment the search finishes: a review that has not been
 * looked at yet has not taught anybody anything.
 */
@SuppressLint("ViewConstructor")
class ChessReviewView(
    context: Context,
    private val moves: List<Chess.Move>,
    private val onDone: (List<ChessReview.MoveReview>) -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val body: LinearLayout
    private var reviews: List<ChessReview.MoveReview> = emptyList()

    init {
        setBackgroundColor(PencilStyle.paper(PaperUi.isDark(context)))

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
        }
        top.addView(
            PaperUi.GlyphButton(context, PaperUi.Glyph.BACK).apply { setOnClickListener { leave() } },
            LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
        )
        top.addView(
            PaperUi.heading(context, "Review").apply { setPadding(PaperUi.dp(context, 8f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(PaperUi.divider(context))

        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = PaperUi.dp(context, 16f)
            setPadding(p, p, p, p)
        }
        body.addView(PaperUi.note(context, "Reviewing every move…"))

        val scroller = ScrollView(context)
        scroller.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        runReview()
    }

    private fun leave() = onDone(reviews)

    private fun runReview() {
        val snapshot = moves
        Thread({
            val computed = ChessReview.review(snapshot)
            handler.post {
                reviews = computed
                render(computed)
            }
        }, "chess-review").start()
    }

    private fun render(reviews: List<ChessReview.MoveReview>) {
        body.removeAllViews()
        if (reviews.isEmpty()) {
            body.addView(PaperUi.body(context, "There is nothing to review yet — play a game first."))
            return
        }

        // The headline first: how the whole game went, in the same words the summary line under
        // "Review last match" uses, so a player sees the same language in both places.
        val blunders = reviews.count { it.verdict == ChessReview.Verdict.BLUNDER }
        val mistakes = reviews.count { it.verdict == ChessReview.Verdict.MISTAKE }
        val best = reviews.count { it.verdict == ChessReview.Verdict.BEST }
        body.addView(
            PaperUi.body(
                context,
                "$best of ${reviews.size} moves matched the engine's own choice" +
                    if (blunders + mistakes > 0) ", with $mistakes mistake${if (mistakes == 1) "" else "s"} and " +
                        "$blunders blunder${if (blunders == 1) "" else "s"}."
                    else "."
            )
        )
        body.addView(PaperUi.spacer(context, 12f))

        reviews.forEach { review -> body.addView(moveRow(review)) }
    }

    private fun moveRow(review: ChessReview.MoveReview): View {
        val colour = when (review.verdict) {
            ChessReview.Verdict.BEST, ChessReview.Verdict.EXCELLENT -> PencilStyle.GREEN_PENCIL
            ChessReview.Verdict.GOOD -> PencilStyle.graphite(PaperUi.isDark(context))
            ChessReview.Verdict.INACCURACY -> PencilStyle.BLUE_PENCIL
            ChessReview.Verdict.MISTAKE, ChessReview.Verdict.BLUNDER -> PencilStyle.RED_PENCIL
        }
        val moveNumber = review.ply / 2 + 1
        val side = if (review.mover == Chess.Colour.WHITE) "$moveNumber." else "$moveNumber…"

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, PaperUi.dp(context, 8f), 0, PaperUi.dp(context, 8f))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(PaperUi.body(context, "$side ${review.san}", colour).apply {
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    })
                    addView(
                        PaperUi.note(context, "  ${review.verdict.label}" + if (review.centipawnLoss > 0) " (-${review.centipawnLoss})" else "").apply {
                            setPadding(PaperUi.dp(context, 6f), 0, 0, 0)
                        },
                    )
                },
            )
            addView(PaperUi.note(context, review.note))
        }
    }
}
