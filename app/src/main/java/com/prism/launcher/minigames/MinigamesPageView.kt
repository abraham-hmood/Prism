package com.prism.launcher.minigames

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Minigames page.
 *
 * ## The list is collapsed by default, and that is the design
 *
 * A right-facing arrow on the left edge expands a list of every game; the arrow flips to
 * left-facing while it is open, and picking a game collapses it again. Collapsed is the resting
 * state because the games want the whole screen — a Pong court and a base at level 400 both use
 * every pixel there is, and a permanent sidebar would cost a fifth of the page to a control that is
 * touched once a session.
 *
 * ## Games are views, not activities
 *
 * A launcher page cannot start an activity without leaving the launcher, and leaving the launcher
 * to play Pong and coming back to a rebuilt home screen is a bad trade. So each game is a View this
 * swaps in, and each one is responsible for stopping its own loop in `onDetachedFromWindow` —
 * which is why they all do.
 */
/**
 * A game that draws its own chrome under the page's header.
 *
 * The page's title bar floats over the game so the paper can run edge to edge behind it, which
 * looks right and means a game that centres its content needs to know nothing. A game that puts its
 * own heading at the top of the screen, though, lands underneath the page's -- "Paper Empire" and
 * "Your Country - level 1" were drawn on top of each other. This is how the page tells it not to.
 *
 * Measured rather than assumed: the header is two lines of text whose height depends on the
 * device's font scale, and a hard-coded inset would be wrong on any phone whose owner had changed
 * it.
 */
interface TopInsetAware {
    fun applyTopInset(pixels: Int)
}

class MinigamesPageView(context: Context) : FrameLayout(context) {

    private data class Game(val id: String, val title: String, val blurb: String)

    private val games = listOf(
        Game(GAME_PAPER, "Paper Empire", "Build, research, invade. Five hundred levels of it."),
        Game(GAME_CHESS, "Chess", "Full rules, four strengths, and the mesh."),
        Game(GAME_PONG, "Pong", "The original. Still undefeated."),
    )

    private var listOpen = false
    private var currentGameId: String = MinigameStore.lastGame().ifBlank { GAME_PAPER }
    private var current: View? = null

    private lateinit var stage: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var arrow: PaperUi.GlyphButton
    private lateinit var title: TextView
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())

    init {
        setBackgroundColor(PencilStyle.paper(PaperUi.isDark(context)))
        buildChrome()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current == null) openGame(currentGameId)
    }

    // -- Chrome ---------------------------------------------------------------

    private fun buildChrome() {
        stage = FrameLayout(context)
        addView(stage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The header sits above the game and is deliberately thin: two lines of pencil.
        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PaperUi.dp(context, 6f), PaperUi.dp(context, 6f), PaperUi.dp(context, 12f), 0)
        }

        arrow = PaperUi.GlyphButton(context, PaperUi.Glyph.ARROW_RIGHT).apply {
            setOnClickListener { toggleList() }
            contentDescription = "Show the list of games"
        }
        header.addView(arrow, LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)))

        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PaperUi.dp(context, 4f), 0, 0, 0)
        }
        title = PaperUi.heading(context, "")
        status = PaperUi.note(context, "")
        titles.addView(title)
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The drawer itself: a sheet of paper that slides out from the left edge.
        drawer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = PaperUi.SheetDrawable(context, seed = 7, torn = false)
            val p = PaperUi.dp(context, 14f)
            setPadding(p, p, p, p)
            visibility = View.GONE
            isClickable = true
        }
        addView(
            drawer,
            LayoutParams(
                PaperUi.dp(context, 238f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP,
            ).apply { topMargin = PaperUi.dp(context, 56f) },
        )
    }

    private fun toggleList() {
        listOpen = !listOpen
        if (listOpen) fillDrawer()
        drawer.visibility = if (listOpen) View.VISIBLE else View.GONE

        // The arrow flips, which is the whole affordance: the direction it points is where the
        // list will go if you tap it. The button is rebuilt rather than invalidated because the
        // glyph is a constructor argument — it is drawn, not a drawable that can be swapped.
        header.removeView(arrow)
        arrow = PaperUi.GlyphButton(
            context,
            if (listOpen) PaperUi.Glyph.ARROW_LEFT else PaperUi.Glyph.ARROW_RIGHT,
        ).apply {
            setOnClickListener { toggleList() }
            contentDescription = if (listOpen) "Hide the list of games" else "Show the list of games"
        }
        header.addView(arrow, 0, LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)))
    }

    private fun fillDrawer() {
        drawer.removeAllViews()
        drawer.addView(PaperUi.heading(context, "Games"))
        drawer.addView(PaperUi.divider(context))

        games.forEach { game ->
            val chosen = game.id == currentGameId
            drawer.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(PaperUi.dp(context, 4f), PaperUi.dp(context, 10f), PaperUi.dp(context, 4f), PaperUi.dp(context, 10f))
                    isClickable = true
                    if (chosen) background = PaperUi.PencilButtonDrawable(context, game.id.hashCode().toLong(), filled = true)
                    setOnClickListener {
                        toggleList()
                        openGame(game.id)
                    }
                    addView(PaperUi.body(context, game.title).apply {
                        typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.SERIF,
                            if (chosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                        )
                    })
                    addView(PaperUi.note(context, game.blurb))
                    addView(PaperUi.note(context, recordFor(game.id)))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        drawer.addView(PaperUi.divider(context))
        drawer.addView(
            PaperUi.note(
                context,
                when {
                    !MinigameMesh.isAvailable() -> "Not on a Prism Meshnet — one-player only."
                    MinigameMesh.isAlone() -> "On the mesh. Nobody else is here yet."
                    else -> "${MinigameMesh.peerCountries().size + 1} on the mesh."
                }
            )
        )
    }

    private fun recordFor(id: String): String = when (id) {
        GAME_CHESS -> MinigameStore.chessRecord().let { "${it.first} won, ${it.second} lost" }
        GAME_PONG -> MinigameStore.pongRecord().let { "${it.first} won, ${it.second} lost" }
        GAME_PAPER -> {
            val base = MinigameStore.loadBase()
            "level ${base.level} · ${PaperUi.shortNumber(base.xp)} XP"
        }
        else -> ""
    }

    // -- Games ----------------------------------------------------------------

    private fun openGame(id: String) {
        currentGameId = id
        MinigameStore.setLastGame(id)
        stage.removeAllViews()
        current = null

        val game = games.firstOrNull { it.id == id } ?: games.first()
        title.text = game.title
        status.text = game.blurb

        when (id) {
            GAME_PAPER -> openPaperEmpire()
            GAME_CHESS -> openChessMenu()
            GAME_PONG -> openPongMenu()
        }
    }

    private fun show(view: View) {
        stage.removeAllViews()
        stage.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        current = view

        // After a layout pass, not now: the header has no height until it has been measured, and
        // handing a game an inset of zero is the same as not telling it at all.
        (view as? TopInsetAware)?.let { aware ->
            header.post { aware.applyTopInset(header.height) }
        }
    }

    private fun openPaperEmpire() {
        val view = PaperWarView(context)
        show(view)
        // Any raid that landed while the page was closed gets reported once the view is up, rather
        // than silently changing the numbers in the header.
        handler.postDelayed({ view.showPendingRaidReport() }, 600)
    }

    // -- Chess ----------------------------------------------------------------

    /**
     * The menu before a game.
     *
     * The mesh button only appears when there is a mesh to play on, which is the design's rule and
     * also the only honest presentation: a "play online" button that always says "nobody is here"
     * is worse than no button.
     */
    private fun openChessMenu() {
        val menu = menuSheet("Chess") { body ->
            body.addView(PaperUi.body(context, "Full rules: castling, en passant, promotion, the fifty-move rule, threefold repetition."))
            body.addView(PaperUi.spacer(context, 10f))
            body.addView(PaperUi.body(context, "Strength").apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            })

            val chosen = MinigameStore.chessDifficulty()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Chess.Difficulty.entries.forEach { difficulty ->
                row.addView(
                    PaperUi.button(
                        context, difficulty.label,
                        filled = difficulty == chosen,
                        seed = difficulty.ordinal.toLong() + 300,
                    ) {
                        MinigameStore.setChessDifficulty(difficulty)
                        openChessMenu()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = PaperUi.dp(context, 4f)
                    },
                )
            }
            body.addView(row)
            body.addView(PaperUi.note(context, "${chosen.label} looks ${chosen.depth} moves ahead."))
            body.addView(PaperUi.spacer(context, 10f))

            // Training: the engine's own move shown on the player's own turn, against the computer
            // only. Checking it disables the mesh button rather than hiding it, so it stays visible
            // that a hinted game and an honest one against a real opponent are different things.
            val training = MinigameStore.chessTrainingMode()
            body.addView(
                PaperUi.button(
                    context,
                    if (training) "✓ Training" else "Training",
                    filled = training,
                    colour = PencilStyle.GREEN_PENCIL,
                    seed = 350,
                ) {
                    MinigameStore.setChessTrainingMode(!training)
                    openChessMenu()
                }
            )
            body.addView(
                PaperUi.note(
                    context,
                    if (training) {
                        "The best move is suggested on your turn, and every game is folded into how the app reads your play."
                    } else {
                        "Shows the engine's own suggestion on your turn, and learns from how you play against it."
                    },
                )
            )
            body.addView(PaperUi.spacer(context, 12f))

            body.addView(PaperUi.button(context, "Play the computer", filled = true) {
                startChess(null, training = training)
            })
            body.addView(PaperUi.spacer(context, 8f))
            val meshButton = PaperUi.button(context, "Play someone on the mesh", colour = PencilStyle.BLUE_PENCIL) {
                findOpponent(MinigameMesh.GAME_CHESS) { match -> startChess(match, training = false) }
            }
            // Disabled, not hidden -- see the doc comment on the Training button above.
            meshButton.isEnabled = MinigameMesh.isAvailable() && !training
            meshButton.alpha = if (meshButton.isEnabled) 1f else 0.4f
            if (MinigameMesh.isAvailable()) body.addView(meshButton)

            if (MinigameStore.lastChessGame() != null) {
                body.addView(PaperUi.spacer(context, 12f))
                body.addView(PaperUi.divider(context))
                body.addView(
                    PaperUi.button(context, "Review last match", colour = PencilStyle.RED_PENCIL) {
                        openChessReview()
                    }
                )
                MinigameStore.chessStyle()?.let { style ->
                    body.addView(PaperUi.spacer(context, 6f))
                    body.addView(PaperUi.note(context, style.summary()))
                }
            }
        }
        show(menu)
    }

    private fun startChess(match: MinigameMesh.Match?, training: Boolean) {
        val container = FrameLayout(context)
        lateinit var board: ChessBoardView
        board = ChessBoardView(
            context,
            difficulty = MinigameStore.chessDifficulty(),
            match = match,
            training = training,
            onStatus = { status.text = it },
            onFinished = {
                // Kept the moment the game ends, so "Review last match" always has THIS game to
                // open, whether or not training mode is what asked for the learning below.
                MinigameStore.setLastChessGame(board.playedMoves())
                if (training) reviewInBackground(board.playedMoves())
                openChessMenu()
            },
        )
        container.addView(board, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        container.addView(
            PaperUi.button(context, "Resign", colour = PencilStyle.RED_PENCIL) { board.resign() },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, PaperUi.dp(context, 14f), PaperUi.dp(context, 14f)) },
        )
        show(container)
    }

    /**
     * Folds a finished game into [MinigameStore.chessStyle] without the player having to press
     * "Review last match" first -- the "automatically learns" half of training mode. Runs the same
     * search [ChessReviewView] does, off the UI thread, and simply does not show its working.
     */
    private fun reviewInBackground(moves: List<Chess.Move>) {
        if (moves.isEmpty()) return
        Thread({
            val reviews = ChessReview.review(moves)
            MinigameStore.accumulateChessStyleOnce(moves, reviews)
        }, "chess-style").start()
    }

    private fun openChessReview() {
        val moves = MinigameStore.lastChessGame() ?: return
        show(
            ChessReviewView(context, moves) { reviews ->
                MinigameStore.accumulateChessStyleOnce(moves, reviews)
                openChessMenu()
            }
        )
    }

    // -- Pong -----------------------------------------------------------------

    private fun openPongMenu() {
        val menu = menuSheet("Pong") { body ->
            body.addView(PaperUi.body(context, "Drag anywhere to move your bat. First to ${Pong.WINNING_SCORE}, win by two."))
            body.addView(PaperUi.spacer(context, 10f))
            body.addView(PaperUi.body(context, "Opponent").apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            })

            val chosen = MinigameStore.pongDifficulty()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Pong.Difficulty.entries.forEach { difficulty ->
                row.addView(
                    PaperUi.button(
                        context, difficulty.label,
                        filled = difficulty == chosen,
                        seed = difficulty.ordinal.toLong() + 400,
                    ) {
                        MinigameStore.setPongDifficulty(difficulty)
                        openPongMenu()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = PaperUi.dp(context, 4f)
                    },
                )
            }
            body.addView(row)
            body.addView(PaperUi.spacer(context, 12f))

            body.addView(PaperUi.button(context, "Play the computer", filled = true) {
                startPong(null)
            })
            if (MinigameMesh.isAvailable()) {
                body.addView(PaperUi.spacer(context, 8f))
                body.addView(
                    PaperUi.button(context, "Play someone on the mesh", colour = PencilStyle.BLUE_PENCIL) {
                        findOpponent(MinigameMesh.GAME_PONG) { match -> startPong(match) }
                    }
                )
            }
        }
        show(menu)
    }

    private fun startPong(match: MinigameMesh.Match?) {
        show(
            PongView(
                context,
                difficulty = MinigameStore.pongDifficulty(),
                match = match,
                onFinished = { openPongMenu() },
            )
        )
    }

    // -- Matchmaking ----------------------------------------------------------

    /**
     * Looks for somebody on the mesh who wants the same game.
     *
     * Re-announces every two seconds while the sheet is open, because the mesh is UDP gossip with
     * no retention: a peer who opens their own search a moment after this one started would never
     * hear a single announcement otherwise.
     */
    private fun findOpponent(gameId: String, onMatch: (MinigameMesh.Match) -> Unit) {
        var sheet: View? = null
        var searching = true

        val reannounce = object : Runnable {
            override fun run() {
                if (!searching) return
                MinigameMesh.reannounce()
                handler.postDelayed(this, 2_000)
            }
        }

        MinigameMesh.seek(gameId) { match ->
            handler.post {
                if (!searching) return@post
                searching = false
                MinigameMesh.stopSeeking()
                handler.removeCallbacks(reannounce)
                PaperUi.dismiss(this, sheet)
                onMatch(match)
            }
        }
        handler.post(reannounce)

        sheet = PaperUi.sheet(this, "Looking for a player") { body ->
            body.addView(
                PaperUi.body(
                    context,
                    if (MinigameMesh.isAlone()) {
                        "You are the only one on this mesh right now. Leave this open — it keeps looking."
                    } else {
                        "Waiting for somebody else on the mesh to want a game of " +
                            (if (gameId == MinigameMesh.GAME_CHESS) "chess." else "pong.")
                    }
                )
            )
            body.addView(PaperUi.spacer(context, 8f))
            body.addView(PaperUi.note(context, "Only players looking for the same game will find you."))
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Stop looking") {
                        searching = false
                        MinigameMesh.stopSeeking()
                        handler.removeCallbacks(reannounce)
                        PaperUi.dismiss(this, sheet)
                    },
                )
            )
        }
    }

    // -- Helpers --------------------------------------------------------------

    private fun menuSheet(heading: String, build: (LinearLayout) -> Unit): View {
        val root = FrameLayout(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = PaperUi.SheetDrawable(context, heading.hashCode().toLong())
            val p = PaperUi.dp(context, 20f)
            setPadding(p + PaperUi.dp(context, 6f), p, p, p)
        }
        body.addView(PaperUi.heading(context, heading))
        body.addView(PaperUi.divider(context))
        build(body)

        root.addView(
            android.widget.ScrollView(context).apply { addView(body) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ).apply {
                setMargins(
                    PaperUi.dp(context, 18f), PaperUi.dp(context, 70f),
                    PaperUi.dp(context, 18f), PaperUi.dp(context, 24f),
                )
            },
        )
        return root
    }

    /** The launcher's back gesture, handed down. True means this page consumed it. */
    fun onBack(): Boolean {
        if (listOpen) {
            toggleList()
            return true
        }
        (current as? PaperWarView)?.let { if (it.onBack()) return true }
        return false
    }

    companion object {
        const val GAME_PAPER = "paper"
        const val GAME_CHESS = "chess"
        const val GAME_PONG = "pong"
    }
}
