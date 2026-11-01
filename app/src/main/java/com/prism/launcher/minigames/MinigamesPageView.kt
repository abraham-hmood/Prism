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
import com.prism.launcher.PrismSettings
import com.prism.launcher.theme.PrismThemes

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
        Game(GAME_MINESWEEPER, "Minesweeper", "Tap to open, hold to mark. Beginner to Expert."),
        Game(GAME_ECHO, "Echo Chamber", "A pitch-black maze. Shout to see. Do not be heard."),
        Game(GAME_PINBALL, "Pinball", "Three balls, three bumpers, two flippers."),
        Game(GAME_DOOM, "DOOM", "A launcher should be able to run DOOM. Three floors of it."),
        Game(GAME_ZOMBIES, "Zombies", "Wave survival, four survivors, a mesh if you have one."),
        Game(GAME_GEOTAG, "GeoTag", "Tag real walls with art, using the camera and your location."),
    ) + ArcadeGames.ALL.map { Game(it.id, it.title, it.blurb) }

    private var listOpen = false
    private var gameQuery: String = ""
    private lateinit var drawerResults: LinearLayout
    private var currentGameId: String = MinigameStore.lastGame().ifBlank { GAME_PAPER }
    private var current: View? = null

    private lateinit var stage: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerScroller: android.widget.ScrollView
    private lateinit var header: LinearLayout
    private lateinit var arrow: PaperUi.GlyphButton
    private lateinit var title: TextView
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())

    /** Music and the result burst belong to the page, not to each of the nineteen games. */
    private var audio: MinigameAudio? = null
    private lateinit var outcomeOverlay: MinigameOutcomeView
    private var lastOutcome: Boolean? = null
    private val musicTick = object : Runnable {
        override fun run() {
            val source = current as? MinigameFeedback
            audio?.updateIntensity(source?.musicIntensity() ?: defaultIntensity(currentGameId))
            val outcome = source?.outcome()
            if (outcome != null && outcome != lastOutcome) {
                // A win and a loss get different colours and different copy; a restart clears
                // lastOutcome, so the next result plays again.
                outcomeOverlay.play(outcome)
                lastOutcome = outcome
            } else if (outcome == null && lastOutcome != null) {
                lastOutcome = null
                outcomeOverlay.clear()
            }
            handler.postDelayed(this, 250L)
        }
    }

    init {
        setBackgroundColor(
            if (isXos()) PencilStyle.neonPaper(PaperUi.isDark(context))
            else PencilStyle.paper(PaperUi.isDark(context)),
        )
        buildChrome()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current == null) openGame(currentGameId)
        startAudio()
        handler.removeCallbacks(musicTick)
        handler.post(musicTick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(musicTick)
        audio?.stop()
        audio = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // The launcher's page stays attached when the phone is locked or another app is in front.
        // Music that keeps playing there is the fastest way to make the feature unwanted.
        if (visibility == View.VISIBLE) {
            startAudio()
            handler.removeCallbacks(musicTick)
            handler.post(musicTick)
        } else {
            handler.removeCallbacks(musicTick)
            audio?.stop()
        }
    }

    private fun startAudio() {
        if (audio == null) audio = MinigameAudio()
        audio?.start(musicKey(currentGameId))
    }

    /** The key a game's soundtrack is played in; arcade ids double as music keys. */
    private fun musicKey(id: String): String = id

    private fun defaultIntensity(id: String): Float = when (id) {
        GAME_CHESS -> 0.2f
        GAME_ECHO -> 0.45f
        GAME_PINBALL -> 0.55f
        GAME_DOOM -> 0.48f
        GAME_ZOMBIES -> 0.44f
        GAME_MINESWEEPER -> 0.32f
        GAME_PONG -> 0.45f
        GAME_PAPER -> 0.4f
        else -> 0.35f
    }

    // -- Chrome ---------------------------------------------------------------

    private fun buildChrome() {
        stage = FrameLayout(context)
        addView(stage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        outcomeOverlay = MinigameOutcomeView(context).apply { visibility = View.GONE }
        addView(
            outcomeOverlay,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

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
            // The scroller, not this sheet, is what hides the list. Leaving this GONE made the
            // arrow flip and the scroller become visible around an empty hole.
            visibility = View.VISIBLE
            isClickable = true
        }
        // Nineteen cabinets do not fit on one screen, so the sheet itself scrolls. Keeping the
        // drawer as the content view means every existing add/remove and its paper background still
        // work; only the window around it is new.
        drawerScroller = android.widget.ScrollView(context).apply {
            visibility = View.GONE
            isFillViewport = false
            addView(drawer)
        }
        addView(
            drawerScroller,
            LayoutParams(
                PaperUi.dp(context, 238f),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.TOP,
            ).apply {
                topMargin = PaperUi.dp(context, 56f)
                bottomMargin = PaperUi.dp(context, 12f)
            },
        )
    }

    private fun toggleList() {
        if (EchoSessionLock.isActive()) {
            android.widget.Toast.makeText(
                context,
                "${EchoSessionLock.label()} is running. Finish the session to unlock the Minigames page.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        listOpen = !listOpen
        if (listOpen) fillDrawer()
        drawer.visibility = if (listOpen) View.VISIBLE else View.GONE
        drawerScroller.visibility = if (listOpen) View.VISIBLE else View.GONE

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

        // SEARCH, because the list is now long enough that scrolling to find a cabinet is the
        // slowest part of opening one. It filters on title, blurb and controls, so "arrow" finds
        // Bumper and "space" finds Space Impact without the player remembering either name.
        val search = android.widget.EditText(context).apply {
            hint = "Search games"
            setText(gameQuery)
            textSize = 13f
            isSingleLine = true
            setTextColor(PaperUi.ink(context))
            setHintTextColor((PaperUi.ink(context) and 0x00FFFFFF) or 0x99000000.toInt())
            setPadding(PaperUi.dp(context, 8f), PaperUi.dp(context, 6f), PaperUi.dp(context, 8f), PaperUi.dp(context, 6f))
            background = PaperUi.PencilButtonDrawable(context, 31L, filled = false)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    gameQuery = s?.toString().orEmpty()
                    fillGameResults()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        drawer.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = PaperUi.dp(context, 8f) })
        drawer.addView(PaperUi.divider(context))

        drawerResults = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        drawer.addView(drawerResults)
        fillGameResults()

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

    private fun fillGameResults() {
        if (!::drawerResults.isInitialized) return
        drawerResults.removeAllViews()
        val query = gameQuery.trim().lowercase()
        val visible = if (query.isEmpty()) games else games.filter { game ->
            game.title.lowercase().contains(query) ||
                game.blurb.lowercase().contains(query) ||
                (ArcadeGames.info(game.id)?.controls.orEmpty().lowercase().contains(query))
        }
        if (visible.isEmpty()) {
            drawerResults.addView(PaperUi.note(context, "No game matches \"$gameQuery\"."))
            return
        }
        visible.forEach { game ->
            val chosen = game.id == currentGameId
            drawerResults.addView(
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
    }

    private fun recordFor(id: String): String = when (id) {
        GAME_CHESS -> MinigameStore.chessRecord().let { "${it.first} won, ${it.second} lost" }
        GAME_PONG -> MinigameStore.pongRecord().let { "${it.first} won, ${it.second} lost" }
        GAME_MINESWEEPER -> MinigameStore.minesweeperRecord().let { "${it.first} cleared, ${it.second} lost" }
        GAME_ECHO -> MinigameStore.echoRecord().let { "${it.first} escaped, ${it.second} lost · best ${MinigameStore.echoBest()}" }
        GAME_PINBALL -> MinigameStore.pinballBest().let { if (it == 0) "No score yet" else "Best $it" }
        GAME_DOOM -> MinigameStore.arcadeBest(GAME_DOOM).let { if (it == 0) "No run yet" else "Best $it" }
        GAME_ZOMBIES -> MinigameStore.arcadeBest(GAME_ZOMBIES).let { if (it == 0) "No run yet" else "Best $it" }
        GAME_GEOTAG -> "${GeoTagStore.places().size} surfaces tagged · ${GeoTagStore.xp()} XP"
        GAME_CHUTES -> MinigameStore.arcadeBest(GAME_CHUTES).let {
            if (it == 0) "No wins yet" else "$it wins"
        }
        GAME_PAPER -> {
            val base = MinigameStore.loadBase()
            "level ${base.level} · ${PaperUi.shortNumber(base.xp)} XP"
        }
        else -> ArcadeGames.info(id)?.let { info ->
            val best = MinigameStore.arcadeBest(id)
            if (best == 0) info.controls else "best $best · ${info.controls}"
        } ?: ""
    }

    // -- Games ----------------------------------------------------------------

    private fun openGame(id: String) {
        if (EchoSessionLock.isActive() && id != GAME_ECHO) {
            android.widget.Toast.makeText(
                context,
                "${EchoSessionLock.label()} is running. Finish the session to unlock the Minigames page.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        currentGameId = id
        MinigameStore.setLastGame(id)
        stage.removeAllViews()
        current = null
        lastOutcome = null
        outcomeOverlay.clear()
        if (isAttachedToWindow) {
            audio?.start(musicKey(id))
            audio?.updateIntensity(defaultIntensity(id))
        }

        val game = games.firstOrNull { it.id == id } ?: games.first()
        title.text = game.title
        status.text = game.blurb
        applyChrome(id)
        // A finished Echo session leaves the arrow disabled; every other transition (including the
        // Echo menu itself) is unlocked, so this is the one place that restores it.
        arrow.isEnabled = true
        arrow.alpha = 1f

        when (id) {
            GAME_PAPER -> openPaperEmpire()
            GAME_CHESS -> openChessMenu()
            GAME_PONG -> openPongMenu()
            GAME_MINESWEEPER -> openMinesweeperMenu()
            GAME_ECHO -> openEchoMenu()
            GAME_PINBALL -> openPinballMenu()
            GAME_DOOM -> openDoomMenu()
            GAME_ZOMBIES -> openZombiesMenu()
            GAME_GEOTAG -> openGeoTag()
            GAME_CHUTES -> openChutesMenu()
            else -> if (ArcadeGames.info(id) != null) openArcade(id)
        }
    }

    private fun openArcade(id: String) {
        // A cabinet must not be able to take the launcher down with it. The rules are covered by
        // tests and the renderer is shared, so a failure here is a real bug -- but the person
        // holding the phone should get an explanation and their home screen back, not a crash
        // dialog for Prism itself. The throwable goes to diagnostics with the game id.
        val view = try {
            ArcadeGameView(context, id)
        } catch (t: Throwable) {
            com.prism.launcher.PrismLogger.logError(
                "Minigames",
                "Could not open the '$id' cabinet",
                t,
            )
            android.widget.Toast.makeText(
                context,
                "${ArcadeGames.info(id)?.title ?: id} could not start. The reason is in diagnostics.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        show(view)
    }

    private fun openChutesMenu() {
        var menu: View? = null
        menu = neonMenuSheet("CHUTES & LADDERS") { body ->
            body.addView(neonNote("Roll the die, climb the ladders, and try not to meet a chute near the top. Four seats against the house, or two over the mesh."))
            body.addView(neonButton("PLAY THE HOUSE", filled = true) {
                PaperUi.dismiss(this@MinigamesPageView, menu)
                show(ArcadeGameView(context, GAME_CHUTES))
            })
            body.addView(neonButton("PLAY ON THE MESH") {
                PaperUi.dismiss(this@MinigamesPageView, menu)
                findOpponent(MinigameMesh.GAME_CHUTES) { match ->
                    show(
                        ArcadeGameView(
                            context, GAME_CHUTES, match.peerIp,
                            if (match.youAreFirst) 0 else 1, match.seed,
                        )
                    )
                }
            })
            body.addView(neonNote("Mesh play needs another Prism device looking for the same game. Both sides keep the same board; each roll is one short message."))
        }
        show(menu!!)
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
        val menu = neonMenuSheet("CHESS") { body ->
            body.addView(neonNote("Full rules: castling, en passant, promotion, the fifty-move rule, threefold repetition."))
            body.addView(neonLabel("STRENGTH"))

            val chosen = MinigameStore.chessDifficulty()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Chess.Difficulty.entries.forEach { difficulty ->
                row.addView(
                    neonButton(difficulty.label.uppercase(), filled = difficulty == chosen) {
                        MinigameStore.setChessDifficulty(difficulty)
                        openChessMenu()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = PaperUi.dp(context, 4f)
                    },
                )
            }
            body.addView(row)
            body.addView(neonNote("${chosen.label} looks ${chosen.depth} moves ahead."))

            // Training: the engine's own move shown on the player's own turn, against the computer
            // only. Checking it disables the mesh button rather than hiding it, so it stays visible
            // that a hinted game and an honest one against a real opponent are different things.
            val training = MinigameStore.chessTrainingMode()
            body.addView(
                neonButton(
                    if (training) "✓ TRAINING" else "TRAINING",
                    filled = training,
                ) {
                    MinigameStore.setChessTrainingMode(!training)
                    openChessMenu()
                },
            )
            body.addView(
                neonNote(
                    if (training) {
                        "The best move is suggested on your turn, and every game is folded into how the app reads your play."
                    } else {
                        "Shows the engine's own suggestion on your turn, and learns from how you play against it."
                    },
                )
            )

            body.addView(neonButton("PLAY THE COMPUTER", filled = true) {
                startChess(null, training = training)
            })
            val meshButton = neonButton("PLAY SOMEONE ON THE MESH") {
                findOpponent(MinigameMesh.GAME_CHESS) { match -> startChess(match, training = false) }
            }
            // Disabled, not hidden -- see the doc comment on the Training button above.
            meshButton.isEnabled = MinigameMesh.isAvailable() && !training
            meshButton.alpha = if (meshButton.isEnabled) 1f else 0.4f
            if (MinigameMesh.isAvailable()) body.addView(meshButton)

            if (MinigameStore.lastChessGame() != null) {
                body.addView(neonButton("REVIEW LAST MATCH") { openChessReview() })
                MinigameStore.chessStyle()?.let { style ->
                    body.addView(neonNote(style.summary()))
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
            neonButton("RESIGN") { board.resign() },
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
        val menu = neonMenuSheet("PONG") { body ->
            body.addView(neonNote("Drag anywhere to move your bat. First to ${Pong.WINNING_SCORE}, win by two."))
            body.addView(neonLabel("OPPONENT"))

            val chosen = MinigameStore.pongDifficulty()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Pong.Difficulty.entries.forEach { difficulty ->
                row.addView(
                    neonButton(difficulty.label.uppercase(), filled = difficulty == chosen) {
                        MinigameStore.setPongDifficulty(difficulty)
                        openPongMenu()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = PaperUi.dp(context, 4f)
                    },
                )
            }
            body.addView(row)
            body.addView(neonNote("${Pong.WINNING_SCORE} points, and you have to be two clear."))

            body.addView(neonButton("PLAY THE COMPUTER", filled = true) { startPong(null) })
            if (MinigameMesh.isAvailable()) {
                body.addView(neonButton("PLAY SOMEONE ON THE MESH") {
                    findOpponent(MinigameMesh.GAME_PONG) { match -> startPong(match) }
                })
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

    // -- Minesweeper ----------------------------------------------------------

    private fun openMinesweeperMenu() {
        show(neonMenuSheet("MINESWEEPER") { body ->
            body.addView(neonNote("Tap a square to open it, hold it to place a flag. The first tap is always safe."))
            body.addView(neonLabel("GRID"))
            Minesweeper.Difficulty.entries.forEach { difficulty ->
                body.addView(neonButton(
                    "${difficulty.label.uppercase()} · ${difficulty.rows}×${difficulty.cols} · ${difficulty.mines} MINES",
                    filled = false,
                ) { startMinesweeper(difficulty) })
            }
            body.addView(neonNote("${MinigameStore.minesweeperRecord().first} cleared, ${MinigameStore.minesweeperRecord().second} lost"))
        })
    }

    private fun startMinesweeper(difficulty: Minesweeper.Difficulty) {
        val container = FrameLayout(context)
        lateinit var board: MinesweeperView
        board = MinesweeperView(
            context,
            difficulty,
            onFinished = { won ->
                MinigameStore.recordMinesweeper(won)
                openMinesweeperMenu()
            },
        )
        container.addView(board, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        container.addView(
            neonButton("NEW BOARD") { board.restart() },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, PaperUi.dp(context, 14f), PaperUi.dp(context, 14f)) },
        )
        show(container)
    }

    // -- Echo Chamber ---------------------------------------------------------

    private fun openEchoMenu() {
        val menu = neonMenuSheet("ECHO CHAMBER") { body ->
            body.addView(neonNote("A pitch-black maze. Only the patches of wall your sound touches are visible — and those same bright marks are breadcrumbs the hunters can follow. Find the exit or clear them out."))
            body.addView(neonLabel("DIFFICULTY"))
            Echo.DIFFICULTY_LABELS.forEachIndexed { index, label ->
                body.addView(neonButton(label, filled = PrismSettings.getEchoChamberDifficulty() == index) {
                    PrismSettings.setEchoChamberDifficulty(index)
                    openEchoMenu()
                })
            }
            body.addView(neonLabel("HUNTERS"))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            (1..5).forEach { count ->
                row.addView(neonButton(count.toString(), filled = PrismSettings.getEchoChamberEnemies() == count) {
                    PrismSettings.setEchoChamberEnemies(count)
                    openEchoMenu()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            body.addView(row)
            body.addView(neonNote("Your microphone is the shout. If it is unavailable, the centre button pulses instead."))
            body.addView(neonButton("ENTER THE DARK", filled = true) { startEcho(null) })
            if (MinigameMesh.isAvailable()) {
                body.addView(neonButton("PLAY ON THE MESH") { findOpponent(MinigameMesh.GAME_ECHO) { match -> startEcho(match) } })
            }
            body.addView(neonNote("Best ${MinigameStore.echoBest()} · ${MinigameStore.echoRecord().first} escaped, ${MinigameStore.echoRecord().second} lost"))
        }
        show(menu)
    }

    private fun startEcho(match: MinigameMesh.Match?) {
        applyChrome(GAME_ECHO)
        val options = Echo.StartOptions(
            difficulty = PrismSettings.getEchoChamberDifficulty(),
            enemies = PrismSettings.getEchoChamberEnemies(),
        )
        // The view sets the lock itself the moment it starts. These two lines only make the page
        // around it say why its arrow and its game list no longer answer.
        status.text = "Session locked — finish the run to unlock the Minigames page."
        arrow.isEnabled = false
        arrow.alpha = 0.35f
        show(EchoChamberView(context, match, options) { _, _ ->
            EchoSessionLock.end()
            openEchoMenu()
        })
        (current as? EchoChamberView)?.let { echo ->
            echo.post {
                listOpen = false
                drawer.visibility = View.GONE
                drawerScroller.visibility = View.GONE
                arrow.isEnabled = false
                arrow.alpha = 0.35f
                status.text = "Session locked — finish the run to unlock the Minigames page."
            }
        }
    }

    // -- Pinball -------------------------------------------------------------

    private fun openPinballMenu() {
        show(neonMenuSheet("PINBALL") { body ->
            body.addView(neonNote("Tap to pull the plunger. The bottom-left half of the screen is the left flipper, the right half is the right one."))
            body.addView(neonLabel("BEST"))
            body.addView(neonNote("${MinigameStore.pinballBest()}"))
            body.addView(neonButton("PLAY", filled = true) { startPinball() })
        })
    }

    private fun startPinball() {
        show(
            PinballView(context) { score ->
                MinigameStore.recordPinball(score)
                openPinballMenu()
            }
        )
    }

    // -- GeoTag ---------------------------------------------------------------

    /**
     * GeoTag is a camera application, so it is an activity rather than a view inside the cabinet.
     * The page keeps its own chrome for the list; the game takes the whole screen.
     */
    private fun openGeoTag() {
        context.startActivity(GeoTagActivity.intent(context))
    }

    // -- DOOM -----------------------------------------------------------------

    private fun openDoomMenu() {
        arrow.isEnabled = true
        arrow.alpha = 1f
        val menu = neonMenuSheet("DOOM") { body ->
            body.addView(neonNote("A launcher running DOOM is the oldest joke in software, so here it is. Hitscan weapons, imps that throw fire, and an exit that opens only when the floor is clear."))
            body.addView(neonLabel("CONTROLS"))
            body.addView(neonNote("Left stick moves. Right stick looks. FIRE shoots, RELOAD feeds the gun, SWAP changes weapon, USE opens the exit."))
            body.addView(neonButton("ENTER THE LAUNCHER", filled = true) { startDoom() })
            body.addView(neonNote("Three floors, one seed. Clear a floor to walk into the next."))
        }
        show(menu)
    }

    private fun startDoom() {
        applyChrome(GAME_DOOM)
        status.text = "Session locked — finish the run to unlock the Minigames page."
        arrow.isEnabled = false
        arrow.alpha = 0.35f
        show(
            DoomGameView(
                context = context,
                runSeed = System.currentTimeMillis(),
                floor = 1,
                difficulty = 1,
            ) { _, _ -> openGame(GAME_DOOM) }
        )
    }

    // -- Zombies -------------------------------------------------------------

    private var zombiesPackStarted = false

    /**
     * The baseline maps, 80k-triangle meshes, 1024² PBR sets and WAVs are generated once, off the
     * UI thread. The playable renderer does not need the files to start, but a host who wants to
     * replace one texture needs a folder whose layout it can mirror.
     */
    private fun startZombiesPack() {
        if (zombiesPackStarted || ZombiesAssets.lastPack() != null) return
        zombiesPackStarted = true
        Thread({
            runCatching { ZombiesAssets.build() }
                .onFailure { com.prism.launcher.PrismLogger.logError("Minigames", "Zombies pack failed", it) }
        }, "zombies-assets").start()
    }

    private fun openZombiesMenu() {
        startZombiesPack()
        arrow.isEnabled = true
        arrow.alpha = 1f
        val menu = neonMenuSheet("ZOMBIES") { body ->
            body.addView(neonNote("WORK IN PROGRESS — the maps, zombie models and textures are still being built. Expect them to look rough and to change."))
            body.addView(neonNote("A wave-survival game. Board the windows, buy a better gun from the crates, and keep the team on its feet."))
            body.addView(neonLabel("SURVIVORS"))
            body.addView(neonNote("Three AI survivors fill the team when nobody else is on the mesh. Speech appears as subtitles; if a speech model is active, the AI survivors use it."))
            body.addView(neonButton("SURVIVE ALONE", filled = true) {
                startZombies(ZombiesGameView.ROLE_SOLO, System.currentTimeMillis(), 0)
            })
            if (ZombieNet.available()) {
                body.addView(neonButton("HOST A MESH GAME") { hostZombies() })
                body.addView(neonButton("JOIN A MESH GAME") { joinZombies() })
            } else {
                body.addView(neonNote("Not on a Prism MeshNet — single-player only right now."))
            }
            body.addView(neonNote("Best ${MinigameStore.arcadeBest(GAME_ZOMBIES)}"))
        }
        show(menu)
    }

    private fun startZombies(role: Int, seed: Long, playerId: Int) {
        applyChrome(GAME_ZOMBIES)
        status.text = "Session locked — finish the run to unlock the Minigames page."
        arrow.isEnabled = false
        arrow.alpha = 0.35f
        show(
            ZombiesGameView(
                context = context,
                role = role,
                playerName = "SURVIVOR",
                seed = seed,
                playerId = playerId,
            ) { _, _ ->
                openGame(GAME_ZOMBIES)
            }
        )
    }

    private fun hostZombies() {
        if (!ZombieNet.host("SURVIVOR")) {
            android.widget.Toast.makeText(context, ZombieNet.lastError, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startZombies(ZombiesGameView.ROLE_HOST, ZombieNet.seed, 0)
    }

    private fun joinZombies() {
        if (!ZombieNet.join("SURVIVOR")) {
            android.widget.Toast.makeText(context, ZombieNet.lastError, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        var lobbyStatus: TextView? = null
        val lobby = neonMenuSheet("JOINING A MESH GAME") { body ->
            body.addView(neonNote("Looking for a Prism device hosting Zombies. The host owns the world; this phone sends its controls."))
            lobbyStatus = neonNote("Searching the mesh…")
            body.addView(lobbyStatus)
            body.addView(neonButton("CANCEL") {
                ZombieNet.stop()
                openZombiesMenu()
            })
        }
        show(lobby)
        val started = System.currentTimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (current !== lobby) return
                val welcome = ZombieNet.takeWelcome()
                if (welcome != null) {
                    handler.removeCallbacks(this)
                    startZombies(ZombiesGameView.ROLE_CLIENT, welcome.seed, welcome.id)
                    return
                }
                val waited = (System.currentTimeMillis() - started) / 1000L
                lobbyStatus?.text = if (waited > 6) {
                    "No host yet. Keep this open, or ask the host to start a mesh game. (${waited}s)"
                } else {
                    "Searching the mesh… (${waited}s)"
                }
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(check)
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
                            when (gameId) {
                                MinigameMesh.GAME_CHESS -> "chess."
                                MinigameMesh.GAME_ECHO -> "Echo Chamber."
                                MinigameMesh.GAME_CHUTES -> "Chutes & Ladders."
                                else -> "pong."
                            }
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

    private fun isXos(): Boolean = PrismThemes.active().id == "xos"

    /**
     * The page's own chrome, per game.
     *
     * Paper Empire is the paper game. Echo Chamber never uses the paper surface, and Minesweeper
     * does not on xOS — the page around a neon board has to be dark or the board reads as a
     * sticker on a notebook.
     */
    private fun applyChrome(id: String) {
        // Paper belongs to Paper Empire. Every other game gets the arcade's dark floor, so a
        // pencil sheet never shows up behind a neon board.
        val neon = id != GAME_PAPER
        val dark = PaperUi.isDark(context)
        val neonPaper = id == GAME_PAPER && isXos()
        // Every minigame view reads this before it draws, so the playfields and the page agree.
        val colors = MinigameTheme.of(context)
        ArcadeStyle.applyTheme(colors)
        if (neon) {
            setBackgroundColor(colors.background)
            title.setTextColor(colors.accent)
            status.setTextColor(colors.muted)
            drawer.background = neonSheetDrawable(colors.board, colors.accent)
        } else {
            setBackgroundColor(
                if (neonPaper) PencilStyle.neonPaper(dark) else PencilStyle.paper(dark),
            )
            title.setTextColor(
                if (neonPaper) PencilStyle.NEON_CYAN else PencilStyle.graphite(dark),
            )
            status.setTextColor(
                if (neonPaper) PencilStyle.NEON_MAGENTA else PencilStyle.graphiteLight(dark),
            )
            drawer.background = if (neonPaper) {
                neonSheetDrawable(PencilStyle.neonPaper(dark), PencilStyle.NEON_CYAN)
            } else {
                PaperUi.SheetDrawable(context, seed = 7, torn = false)
            }
        }
    }

    private fun neonSheetDrawable(fill: Int, stroke: Int): android.graphics.drawable.GradientDrawable {
        val colors = MinigameTheme.of(context)
        val end = if (colors.neon) 0xFF0B0620.toInt() else colors.background
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(fill, end),
        ).apply {
            cornerRadius = PaperUi.dp(context, 20f).toFloat()
            setStroke(PaperUi.dp(context, 1f), stroke)
        }
    }

    /** Black or white, whichever reads on [colour]. */
    private fun contrastOn(colour: Int): Int {
        val red = (colour shr 16) and 0xFF
        val green = (colour shr 8) and 0xFF
        val blue = colour and 0xFF
        return if ((red * 299 + green * 587 + blue * 114) / 1000 > 150) 0xFF101018.toInt()
        else 0xFFFFFFFF.toInt()
    }

    private fun neonMenuSheet(heading: String, build: (LinearLayout) -> Unit): View {
        val colors = MinigameTheme.of(context)
        val root = FrameLayout(context)
        root.setBackgroundColor(colors.background)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = neonSheetDrawable(colors.board, colors.accent)
            val p = PaperUi.dp(context, 20f)
            setPadding(p, p, p, p)
        }
        body.addView(TextView(context).apply {
            text = heading
            textSize = 26f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.16f
            setTextColor(colors.accent)
            if (colors.neon) setShadowLayer(12f, 0f, 0f, colors.accent)
        })
        body.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((colors.accent and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PaperUi.dp(context, 1f)).apply {
            topMargin = PaperUi.dp(context, 10f); bottomMargin = PaperUi.dp(context, 10f)
        })
        build(body)
        root.addView(
            android.widget.ScrollView(context).apply { addView(body) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ).apply {
                setMargins(PaperUi.dp(context, 18f), PaperUi.dp(context, 70f), PaperUi.dp(context, 18f), PaperUi.dp(context, 24f))
            },
        )
        return root
    }

    private fun neonLabel(label: String): TextView = TextView(context).apply {
        val colors = MinigameTheme.of(context)
        text = label
        textSize = 10f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.18f
        setTextColor(if (colors.neon) 0xFFFF8FE3.toInt() else colors.accentAlt)
        setPadding(PaperUi.dp(context, 2f), PaperUi.dp(context, 12f), 0, PaperUi.dp(context, 4f))
    }

    private fun neonNote(label: String): TextView = TextView(context).apply {
        val colors = MinigameTheme.of(context)
        text = label
        textSize = 12f
        setTextColor(colors.muted)
        setLineSpacing(0f, 1.25f)
        setPadding(PaperUi.dp(context, 2f), PaperUi.dp(context, 6f), PaperUi.dp(context, 2f), PaperUi.dp(context, 6f))
    }

    private fun neonButton(
        label: String,
        filled: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        val colors = MinigameTheme.of(context)
        text = label
        textSize = 13f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setPadding(PaperUi.dp(context, 10f), PaperUi.dp(context, 11f), PaperUi.dp(context, 10f), PaperUi.dp(context, 11f))
        setTextColor(if (filled) contrastOn(colors.accent) else colors.accent)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = PaperUi.dp(context, 10f).toFloat()
            if (filled) {
                setColor(colors.accent)
            } else {
                setColor((colors.accent and 0x00FFFFFF) or 0x1A000000)
                setStroke(PaperUi.dp(context, 1f), (colors.accent and 0x00FFFFFF) or 0xAA000000.toInt())
            }
        }
        setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = PaperUi.dp(context, 6f) }
        layoutParams = params
    }

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
        const val GAME_MINESWEEPER = "minesweeper"
        const val GAME_ECHO = "echo"
        const val GAME_PINBALL = "pinball"
        const val GAME_CHUTES = "chutes"
        const val GAME_DOOM = "doom"
        const val GAME_ZOMBIES = "zombies"
        const val GAME_GEOTAG = "geotag"
    }
}
