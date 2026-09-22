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
import android.widget.TextView

/**
 * A battle, played back a frame at a time, with the squad orders on the bottom.
 *
 * ## Why it re-simulates instead of replaying a recording
 *
 * Because orders arrive DURING the fight. A player tapping "send squad 2 to that corner" at second
 * nine changes everything after second nine, so the simulation has to be re-run from the start with
 * the new order folded in — which is cheap (a three-minute battle is a few milliseconds of maths)
 * and exact, since [Battle] is deterministic. Re-running rather than patching is also what keeps a
 * spectator's copy identical: they have the same seed and the same order list, so they compute the
 * same frames.
 *
 * ## Why a spectator sees the same thing
 *
 * A spectator has the ticket, which is the seed and the attacker's strength, and they recompute the
 * fight themselves. Nothing is streamed. That is the entire reason [Battle] refuses to touch the
 * system clock or the default random generator.
 */
@SuppressLint("ViewConstructor")
class BattlePlaybackView(
    context: Context,
    private val defender: PaperBase,
    private val ticket: WorldMap.BattleTicket,
    private val youCanGiveOrders: Boolean,
    private val title: String,
    private val onFinished: (Battle.Result) -> Unit,
    private val onExit: () -> Unit,
    /**
     * True when the player has no way to leave until the battle is over.
     *
     * Used for a live raid on the player's own base: the whole requirement is that they are made
     * to watch it happen rather than being offered a summary afterward, so the one thing that
     * would let them look away -- the back button in the top bar -- is not drawn at all. The
     * battle still finishes and reports itself on its own; there is simply nothing to press before
     * then.
     */
    val forced: Boolean = false,
) : FrameLayout(context), TopInsetAware {

    private val handler = Handler(Looper.getMainLooper())

    private var orders = mutableListOf<Battle.Order>()
    private var playback: Battle.Playback? = null
    private var simulating = false

    private val frames: List<Battle.Frame> get() = playback?.frames.orEmpty()
    private val result: Battle.Result? get() = playback?.result

    private var playhead = 0
    private var playing = true
    private var reported = false

    /** The squad the next tap on the paper will redirect. */
    private var selectedSquad = 0

    private val stage: PaperBaseView
    private val status: TextView
    private var topBar: LinearLayout? = null
    private val squadRow: LinearLayout

    private val stepper = object : Runnable {
        override fun run() {
            if (!playing) return
            if (frames.isEmpty()) {
                // The first simulation has not landed yet. Wait for it rather than calling the
                // battle finished, which is what an empty frame list would otherwise look like.
                handler.postDelayed(this, 60)
                return
            }
            if (playhead < frames.size - 1) {
                playhead++
                stage.battleFrame = frames[playhead]
                soundFor(frames[playhead])
                refreshStatus()
                // Matched to how many ticks each frame stands for; see Playback.ticksPerFrame.
                handler.postDelayed(this, playback?.frameMillis ?: (1000L / Battle.TICKS_PER_SECOND))
            } else {
                finish()
            }
        }
    }

    init {
        setBackgroundColor(PencilStyle.paper(PaperUi.isDark(context)))

        stage = PaperBaseView(
            context,
            onTapBuilding = { onTapPaper(it.x, it.y) },
            onTapEmpty = { x, y -> onTapPaper(x, y) },
        ).apply { base = defender }
        addView(stage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
        }.also { topBar = it }
        top.apply {
            if (!forced) {
                addView(
                    PaperUi.GlyphButton(context, PaperUi.Glyph.BACK).apply {
                        setOnClickListener { leave() }
                    },
                    LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
                )
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(PaperUi.dp(context, 8f), 0, 0, 0)
                    addView(PaperUi.heading(context, title))
                    status = PaperUi.note(context, "")
                    addView(status)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        squadRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val p = PaperUi.dp(context, 10f)
            setPadding(p, p, p, p)
        }
        buildControls()
        addView(squadRow, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        PaperAudio.stopAmbience()
        resimulate()
        handler.post(stepper)
    }

    /** Drops the battle's own heading below the page's title, like the rest of the game. */
    override fun applyTopInset(pixels: Int) {
        val p = PaperUi.dp(context, 12f)
        topBar?.setPadding(p, pixels + p, p, p)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playing = false
        handler.removeCallbacks(stepper)
        PaperAudio.stopAmbience()
    }

    private fun buildControls() {
        squadRow.removeAllViews()

        if (youCanGiveOrders) {
            // Four squads, because four is how many edges the paper has and how many buttons fit
            // across a phone. Each one is a coloured pencil, matching the figures on the page.
            listOf(
                0 to PencilStyle.RED_PENCIL,
                1 to PencilStyle.BLUE_PENCIL,
                2 to PencilStyle.GREEN_PENCIL,
                3 to PencilStyle.graphite(PaperUi.isDark(context)),
            ).forEach { (squad, colour) ->
                val button = PaperUi.button(
                    context,
                    "Squad ${squad + 1}",
                    colour = colour,
                    filled = squad == selectedSquad,
                    seed = 500L + squad,
                ) {
                    selectedSquad = squad
                    buildControls()
                }
                squadRow.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (squad > 0) leftMargin = PaperUi.dp(context, 5f)
                    },
                )
            }
        } else {
            squadRow.addView(
                PaperUi.button(context, if (playing) "Pause" else "Play") {
                    playing = !playing
                    if (playing) handler.post(stepper)
                    buildControls()
                }
            )
        }
    }

    /**
     * An order: send the selected squad to where the player tapped.
     *
     * Recorded at the CURRENT playhead, not at tick zero — the order takes effect from the moment
     * it was given, which is what makes giving one at the right moment a decision rather than a
     * pre-battle setting.
     */
    private fun onTapPaper(x: Int, y: Int) {
        if (!youCanGiveOrders || reported) return
        orders.removeAll { it.squad == selectedSquad && it.atTick >= playhead }
        orders.add(Battle.Order(atTick = playhead, squad = selectedSquad, targetX = x, targetY = y))
        status.text = "Squad ${selectedSquad + 1} moving to $x, $y"
        resimulate()
    }

    /**
     * Re-runs the whole fight with the current orders, off the UI thread.
     *
     * A level-500 raid is a hundred and twenty soldiers against a hundred and twenty buildings over
     * several hundred frames: tens of milliseconds on a desktop and a few hundred on a phone. Doing
     * that inline froze the screen for a third of a second every time the player gave an order,
     * which is exactly the moment the game most needs to feel responsive. The playhead keeps
     * running off the old frames while the new ones are computed and swaps when they arrive.
     */
    /**
     * How hard the ground being fought over is still burning.
     *
     * Set by whoever opens the battle, so an assault on a country that was bombed last night is
     * fought across a burning ruin rather than across a clean page. It is the same number the spy
     * screen uses, which is what the requirement "this must persist when I decide to invade it"
     * actually means.
     */
    var scorch: Float
        get() = stage.scorch
        set(value) { stage.scorch = value }

    private fun resimulate() {
        if (simulating) return
        simulating = true
        val snapshot = orders.sortedBy { it.atTick }

        Thread({
            val computed = Battle.simulate(
                defender = defender,
                attackerLevel = ticket.attackerLevel,
                attackerSoldiers = ticket.attackerSoldiers,
                attackerWeaponId = ticket.attackerWeaponId,
                orders = snapshot,
                seed = ticket.seed,
                attackerSupportIds = ticket.attackerSupportIds,
            )
            handler.post {
                simulating = false
                playback = computed
                stage.battleCast = computed.cast
                playhead = playhead.coerceIn(0, (computed.frames.size - 1).coerceAtLeast(0))
                computed.frames.getOrNull(playhead)?.let { stage.battleFrame = it }
                refreshStatus()
                // Orders given while this was running are not lost: they are folded in by a second
                // pass rather than silently dropped.
                if (orders.sortedBy { it.atTick } != snapshot) resimulate()
            }
        }, "battle-sim").start()
    }

    /**
     * Plays the shots in a frame.
     *
     * Capped at two a frame and thinned as the battle gets louder: a level-400 raid fires dozens of
     * weapons in the same tenth of a second, and playing all of them is not a battle, it is a
     * crackle. The loudest thing in the frame is chosen — the blast rather than the bow — which is
     * what an ear standing there would actually pick out.
     */
    private fun soundFor(frame: Battle.Frame) {
        if (frame.shots.isEmpty()) return
        val cast = playback?.cast ?: return

        val loudest = frame.shots
            .asSequence()
            .mapNotNull { shot ->
                val weapon = if (shot.fromAttacker) {
                    cast.weaponOf(0)
                } else {
                    WeaponCatalog.byId(defender.garrisonWeapon().id)
                } ?: return@mapNotNull null
                weapon to shot
            }
            .sortedByDescending { it.first.damage * (1 + it.second.splash) }
            .take(2)

        loudest.forEachIndexed { index, (weapon, _) ->
            PaperAudio.fire(weapon, volume = if (index == 0) 0.55f else 0.3f)
        }
    }

    private fun refreshStatus() {
        val frame = frames.getOrNull(playhead) ?: return
        val total = defender.standing.size.coerceAtLeast(1)
        val seconds = Battle.secondsOf(frame.tick)
        status.text = "%.0fs · %d soldiers standing · %d/%d buildings down"
            .format(seconds, frame.aliveCount(), frame.destroyedCount(), total)
    }

    private fun finish() {
        playing = false
        if (reported) return
        reported = true
        val outcome = result ?: return

        // The banner, then the caller's own report sheet. Two beats rather than one, because
        // "you won" and "here is the accounting" are different pieces of information and a player
        // wants the first one immediately.
        val banner = PaperUi.button(
            context,
            if (outcome.won) "★".repeat(outcome.stars) + "  taken" else "held against you",
            colour = if (outcome.won) PencilStyle.GREEN_PENCIL else PencilStyle.RED_PENCIL,
            filled = true,
            seed = 999,
        ) { }
        addView(
            banner,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ),
        )
        handler.postDelayed({ onFinished(outcome) }, 1_100)
    }

    private fun leave() {
        playing = false
        handler.removeCallbacks(stepper)
        // A battle left early still happened: the result is reported so the XP and damage land.
        if (!reported) {
            reported = true
            result?.let { onFinished(it) } ?: onExit()
        } else {
            onExit()
        }
    }
}
