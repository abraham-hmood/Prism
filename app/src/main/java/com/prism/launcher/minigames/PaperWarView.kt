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
 * The paper strategy game: base, world, diplomacy and battles, in one place.
 *
 * ## Why one class holds all four screens
 *
 * They share one piece of mutable state — the base — and every one of them can change it. A build
 * finishing, a raid landing, an alliance being accepted and a battle being won all write to the
 * same object, and splitting them across four activities would mean four copies of it and a
 * reconciliation problem. So this owns the base, and the screens are views it swaps between.
 *
 * ## The tick
 *
 * Once a second while the page is visible: finishes construction, trains soldiers, repairs damage,
 * and occasionally sends an AI raid. Time that passed while the page was closed is caught up in one
 * step on the way in, so a player who comes back the next day finds their buildings standing rather
 * than a timer that was paused out of politeness.
 */
class PaperWarView(context: Context) : FrameLayout(context), TopInsetAware {

    private val handler = Handler(Looper.getMainLooper())
    private val worldSeed = MinigameStore.worldSeed()

    /** How often a raid is even considered. See [maybeRaid]. */
    private val RAID_CHECK_MS = 90_000L
    /** How often the rest of the world is given a chance to fire something strategic. */
    private val STRIKE_CHECK_MS = 150_000L

    private var base: PaperBase = MinigameStore.loadBase()
    private var aiWorld: List<WorldMap.Country> = emptyList()

    private lateinit var baseView: PaperBaseView
    private lateinit var header: TextView
    private lateinit var subHeader: TextView
    private var mapView: WorldMapView? = null
    private var battleView: BattlePlaybackView? = null
    private var openSheet: View? = null

    private var headerBox: LinearLayout? = null
    private var topInset = 0

    /** The normal controls, and the bar that replaces them while a building is being aimed. */
    private var bottomBar: LinearLayout? = null
    private var placementBar: LinearLayout? = null
    private var placementLabel: TextView? = null

    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        setBackgroundColor(PencilStyle.paper(PaperUi.isDark(context)))
        buildBaseScreen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        PaperAudio.start(context)
        // Peacetime, until something starts. The map and a battle both stop it.
        PaperAudio.startAmbience()
        catchUp()
        handler.post(ticker)
        if (!MinigameStore.hasSeenOpeningRaid()) showOpeningRaid() else showRecoveryNoticeIfAny()
        MinigameMesh.announceCountry(base, worldSeed)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        handler.removeCallbacks(ticker)
        PaperAudio.stopAmbience()
        MinigameStore.saveBase(base)
        MinigameStore.setLastTick(System.currentTimeMillis())
    }

    // -- State ----------------------------------------------------------------

    private fun commit(next: PaperBase) {
        base = next
        MinigameStore.saveBase(base)
        baseView.base = base
        refreshHeader()
    }

    /**
     * Brings the world forward over however long the page was closed.
     *
     * Construction is finished for real, because the timers are absolute. Raids are NOT simulated
     * for every hour that passed — a player who was away a week would come back to a hundred
     * battles and a razed base, which is a punishment for not playing. At most one raid is owed,
     * and only if it has been a while.
     */
    private fun catchUp() {
        val now = System.currentTimeMillis()
        val away = now - MinigameStore.lastTick()
        var next = base.advanceConstruction(now)

        if (away > 10 * 60_000L) {
            // Repairs happen while you are away, in proportion to how long, up to a full mend.
            next = next.repair((away / (60 * 60_000.0)).coerceAtMost(1.0) * 0.5)
        }
        // Taxes and law deliberation are owed for the whole absence, not from the moment the page
        // was reopened.
        next = next.collectTaxes(now).advanceLaw(now)

        commit(next)
        MinigameStore.setLastTick(now)
        rebuildWorld()

        // The raid that happened while you were away is simulated OFF this thread. It is the same
        // few hundred milliseconds of maths as a live battle, and running it inline would stall the
        // pager for a third of a second on the swipe that opens the page -- which is the worst
        // possible moment to drop frames.
        if (away > 60 * 60_000L && next.hasTownHall) {
            val before = next
            Thread({
                val after = runAiRaid(before, seedOffset = now)
                handler.post {
                    if (!running || base != before) return@post
                    commit(after)
                    // Say so. This used to commit in silence, so a raid that happened while the
                    // player was away simply changed their base with no explanation -- they opened
                    // the game, found things missing, and had no way to know they had been
                    // attacked, let alone to watch it. An invasion you cannot see is
                    // indistinguishable from a bug, which is exactly how it was reported.
                    showPendingRaidReport()
                }
            }, "paper-catchup-raid").start()
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val before = base.underConstruction.size
        var next = base.advanceConstruction(now)

        // Training happens by itself, slowly, up to the camps' capacity. Making the player tap a
        // button once a minute to refill an army is busywork, not a decision.
        if (next.soldiers < next.armyCapacity && now % 5_000 < 1_000) {
            next = next.trainSoldiers(1)
        }
        if (now % 15_000 < 1_000) next = next.repair(0.02)

        // The civilian economy and the legislature both run on the clock rather than on the
        // player, which is the point of them: a country should be doing something while nobody is
        // looking at it.
        next = next.collectTaxes(now).advanceLaw(now)

        if (next != base) commit(next)
        if (before != next.underConstruction.size) {
            PaperAudio.chime()
            refreshHeader()
        }

        // Somebody may come for you while you are standing here. Rare per tick and weighted by the
        // grudges the player has earned -- see WorldMap.pickRaider.
        if (now - lastRaidCheck > RAID_CHECK_MS) {
            lastRaidCheck = now
            maybeRaid(now)
        }
        maybeAiStrike(now)
        baseView.invalidate()

        if (now % 30_000 < 1_000) MinigameMesh.announceCountry(base, worldSeed)
    }

    private var lastStrikeCheck = 0L

    /**
     * The rest of the world uses these too.
     *
     * A map where only the player ever launches anything is a map where the player is the only
     * thing happening. Modern and futuristic AI countries fire on each other and, if they have a
     * grudge, on the player — rarely, because the interesting thing about a strategic weapon is
     * that it is rare.
     */
    private fun maybeAiStrike(now: Long) {
        if (now - lastStrikeCheck < STRIKE_CHECK_MS) return
        lastStrikeCheck = now

        val world = aiWorld.filter { it.level >= Era.Age.MODERN.first }
        if (world.size < 2) return

        val rng = Rng(Rng.seedOf("aistrike", worldSeed, now / STRIKE_CHECK_MS))
        if (!rng.chance(0.14)) return

        val attacker = world[rng.nextInt(world.size)]
        val kind = if (attacker.level >= Era.Age.FUTURISTIC.first && rng.chance(0.4)) {
            WeaponCatalog.Wmd.ANTIMATTER
        } else {
            WeaponCatalog.Wmd.NUCLEAR
        }

        // Who it goes for: the player if it has a reason, otherwise a neighbour.
        val relations = MinigameStore.relations()
        val hatesPlayer = relations[attacker.id] == WorldMap.Relation.HOSTILE
        val targetId = if (hatesPlayer && rng.chance(0.35)) {
            WorldMap.PLAYER_ID
        } else {
            world.filter { it.id != attacker.id }.randomOrNull(rng)?.id ?: return
        }
        if (targetId == attacker.id) return
        // Allies do not do this to you.
        if (targetId == WorldMap.PLAYER_ID && relations[attacker.id] == WorldMap.Relation.ALLIED) return

        MinigameStore.recordStrike(targetId, WorldMap.Strike(kind, now, fromId = attacker.id))
        mapView?.strikes = MinigameStore.strikes()
        mapView?.invalidate()

        if (targetId == WorldMap.PLAYER_ID) {
            MinigameStore.setRelation(attacker.id, WorldMap.Relation.HOSTILE)
            handler.postDelayed({
                if (running) toast("${attacker.name} has launched a ${kind.label.lowercase()} strike at you.")
            }, WorldMap.FLIGHT_MS)
        }
    }

    private var lastRaidCheck = 0L

    /**
     * Rolls for a raid while the player is actually watching.
     *
     * Raids used to happen only on the way back from an absence, which meant a player sitting on
     * the page was completely safe — and made every invasion a one-way transaction. Now any
     * non-allied country can come for you, and the ones you attacked and failed against come for
     * you far more often.
     *
     * Deliberately rare. A raid every couple of minutes would be noise; this works out at roughly
     * one every twenty minutes of play, and only once there is something worth raiding.
     */
    private fun maybeRaid(now: Long) {
        if (!base.hasTownHall || base.standing.size < 4) return
        // Never interrupts something already on screen -- a raid landing mid-invasion or on top of
        // a sheet the player has open would be its own kind of unfair.
        if (overlay != null || openSheet != null) return
        if (Rng(Rng.seedOf("raidroll", worldSeed, now / RAID_CHECK_MS)).chance(0.08).not()) return

        // Who comes is not random: a country the player attacked and failed against is far more
        // likely to return the visit, and an ally never does. See WorldMap.aggressionToward. This
        // is cheap enough (no simulation, just picking a name) to do on the main thread, and it has
        // to be -- the whole point is that watching starts the instant the raid does, with nothing
        // in between for the player to wait through or skip.
        val world = WorldMap.generateAi(worldSeed, base.level, count = 24).map { country ->
            country.copy(relation = MinigameStore.relations()[country.id] ?: WorldMap.Relation.NEUTRAL)
        }
        val attacker = WorldMap.pickRaider(
            candidates = world,
            failedInvasions = MinigameStore.failedInvasions(),
            playerLevel = base.level,
            seed = Rng.seedOf("raider", worldSeed, now),
        ) ?: return

        val ticket = WorldMap.BattleTicket(
            id = "raid-$now",
            attackerId = attacker.id,
            defenderId = WorldMap.PLAYER_ID,
            seed = Rng.seedOf("raid", attacker.id, now),
            startedAt = now,
            attackerLevel = attacker.level,
            attackerSoldiers = (attacker.soldiers * 0.6).toInt().coerceAtLeast(1),
            attackerWeaponId = WeaponCatalog.unlockedAt(attacker.level).maxByOrNull { it.threat }?.id
                ?: WeaponCatalog.STARTER.id,
            attackerSupportIds = WorldMap.aiDetachments(attacker.level),
        )
        // A country that has come for you is hostile whether it wins or not.
        MinigameStore.setRelation(attacker.id, WorldMap.Relation.HOSTILE)

        showForcedInvasion(attacker, ticket)
    }

    /**
     * A live raid, forced onto the screen the moment it starts.
     *
     * This is the SAME [BattlePlaybackView] every invasion uses -- the same paper, the same figures,
     * the same simulation -- opened over the main view exactly the way a player-initiated invasion
     * is, except [BattlePlaybackView.forced] leaves off the back button, so there is nothing to tap
     * to make it stop. The player cannot give orders (this is their own defence, run by whatever is
     * already garrisoned) and cannot look away; they can only watch it happen and then see what it
     * cost.
     */
    private fun showForcedInvasion(attacker: WorldMap.Country, ticket: WorldMap.BattleTicket) {
        overlay?.let { removeView(it) }
        PaperAudio.stopAmbience()

        val playback = BattlePlaybackView(
            context = context,
            defender = base,
            ticket = ticket,
            youCanGiveOrders = false,
            title = "${attacker.name} is invading you",
            onFinished = { result -> onLiveRaidResolved(attacker, result) },
            onExit = { closeOverlay() },
            forced = true,
        ).also {
            it.applyTopInset(topInset)
            it.scorch = scorchOf(WorldMap.PLAYER_ID)
        }
        battleView = playback
        overlay = playback
        addView(playback, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** What a live, forced raid does to the base once the player has watched it happen. */
    private fun onLiveRaidResolved(attacker: WorldMap.Country, result: Battle.Result) {
        val share = result.destructionPercent / 100.0
        val killed = base.civilianCasualties(WorldMap.aiWarConduct(attacker), share)

        val after = base
            .applyDamage(result.buildingDamage)
            .loseCivilians(killed)
            .recordDefence(!result.won)
            .addXp(result.xpForDefender)
            // Every builder starts fixing the damage the moment the attackers are gone.
            .postBattleRepair()
        commit(after)
        closeOverlay()
        showBattleResult(attacker.name, result, attacking = false)
        if (killed > 0) {
            handler.postDelayed({
                if (running) {
                    toast(
                        if (base.sparesCivilians) "$killed civilians killed. They did not respect the rules of war."
                        else "$killed civilians killed. You have not enacted the Rules of War."
                    )
                }
            }, 900)
        }
    }

    private fun rebuildWorld() {
        aiWorld = WorldMap.generateAi(worldSeed, base.level).map { country ->
            country.copy(relation = MinigameStore.relations()[country.id] ?: WorldMap.Relation.NEUTRAL)
        }
    }

    /**
     * The countries that are NOT generated: this player's own, and any peers on the mesh.
     *
     * Everything else in the world comes out of [WorldChunks] as the map scrolls over it, so this
     * list is two or three entries rather than the whole world.
     */
    /**
     * Every country the player holds, mapped to the player.
     *
     * Taken straight from the annexation list, which is the record of what was actually conquered.
     * The world map uses it to stop drawing those countries as independent states.
     */
    private fun conqueredByPlayer(): Map<String, String> =
        base.annexed.associate { it.id to WorldMap.PLAYER_ID }

    private fun fixedCountries(): List<WorldMap.Country> {
        val relations = MinigameStore.relations()
        val peers = MinigameMesh.peerCountries().map { peer ->
            peer.copy(relation = relations[peer.id] ?: WorldMap.Relation.NEUTRAL)
        }
        return listOf(WorldMap.playerCountry(base, worldSeed)) + peers
    }

    /**
     * The player's near neighbours plus anything fixed. For the things that need a LIST of
     * countries rather than a view of the world — picking a raider, resolving an id.
     */
    private fun allWorld(): List<WorldMap.Country> {
        val relations = MinigameStore.relations()
        return fixedCountries() + aiWorld.map {
            it.copy(relation = relations[it.id] ?: WorldMap.Relation.NEUTRAL)
        }
    }

    // -- The base screen ------------------------------------------------------

    private fun buildBaseScreen() {
        removeAllViews()

        baseView = PaperBaseView(
            context,
            onTapBuilding = { showBuildingSheet(it) },
            onTapEmpty = { x, y -> onTapEmpty(x, y) },
        ).also { it.base = base }
        addView(baseView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: level, XP bar, builders, army. Written on the page rather than in a bar.
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        header = PaperUi.heading(context, "")
        subHeader = PaperUi.note(context, "")
        box.addView(header)
        box.addView(subHeader)
        headerBox = box
        applyHeaderPadding()
        addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The bottom row: build, army, research on the left; inbox and map on the right, as asked.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
        }
        // The actions scroll; the map and the inbox do not. Adding a fourth action pushed both of
        // those off the right edge of the screen, and a button that is simply not there is a worse
        // failure than a row the player has to swipe.
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(glyph: PaperUi.Glyph, label: String, onTap: () -> Unit) {
            actions.addView(
                PaperUi.GlyphButton(context, glyph, label = label).apply {
                    setOnClickListener { onTap() }
                },
                LinearLayout.LayoutParams(PaperUi.dp(context, 58f), PaperUi.dp(context, 56f)),
            )
        }
        action(PaperUi.Glyph.BUILD, "Build") { showBuildMenu() }
        action(PaperUi.Glyph.ARMY, "Army") { showArmySheet() }
        action(PaperUi.Glyph.ARMS, "Research") { showResearchSheet() }
        action(PaperUi.Glyph.STATE, "State") { showGovernmentSheet() }

        bottom.addView(actions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val inbox = PaperUi.GlyphButton(
            context, PaperUi.Glyph.INBOX, badge = { MinigameStore.unansweredCount() }, label = "Inbox",
        ).apply { setOnClickListener { showInbox() } }
        bottom.addView(inbox, LinearLayout.LayoutParams(PaperUi.dp(context, 56f), PaperUi.dp(context, 56f)))

        val map = PaperUi.GlyphButton(context, PaperUi.Glyph.MAP, label = "World").apply {
            setOnClickListener { showWorldMap() }
        }
        bottom.addView(map, LinearLayout.LayoutParams(PaperUi.dp(context, 56f), PaperUi.dp(context, 56f)).apply {
            leftMargin = PaperUi.dp(context, 4f)
        })

        addView(bottom, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
        bottomBar = bottom

        buildPlacementBar()
        refreshHeader()
    }

    /**
     * The bar that replaces the normal controls while something is being placed.
     *
     * It exists because aiming and committing had been the same gesture, which meant they were
     * really only one — a tap moved the ghost and nothing ever put it down. Separating them needs
     * somewhere to say "here", and a button is the discoverable half of that; the double tap is the
     * quick half, for players who have found it.
     *
     * It also carries the thing being placed and whether the square is free, because the ghost's
     * colour alone answers that question only while the finger is not on top of it.
     */
    private fun buildPlacementBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }

        placementLabel = PaperUi.body(context, "")
        bar.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(placementLabel)
                addView(PaperUi.note(context, "Tap to aim · double tap to place"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        bar.addView(
            PaperUi.button(context, "Cancel", seed = 8801) { cancelPlacement() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        bar.addView(
            PaperUi.button(
                context, "Place", colour = PencilStyle.GREEN_PENCIL, filled = true, seed = 8802,
            ) { baseView.confirmPlacement() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = PaperUi.dp(context, 8f) },
        )

        placementBar = bar
        addView(bar, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
    }

    /** Starts aiming [type], and swaps the controls for the placement bar. */
    private fun beginPlacement(type: BuildingCatalog.BuildingType) {
        baseView.placing = type
        // Aim it at the middle of the paper rather than wherever the last one was left: the ghost
        // has to be somewhere the player can see before they have tapped anything.
        baseView.placingX = (base.plotSize - type.footprint) / 2
        baseView.placingY = (base.plotSize - type.footprint) / 2
        baseView.invalidate()

        placementLabel?.text = "Placing ${type.name}"
        placementBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.GONE
    }

    private fun cancelPlacement() {
        baseView.placing = null
        endPlacement()
    }

    private fun endPlacement() {
        placementBar?.visibility = View.GONE
        bottomBar?.visibility = View.VISIBLE
    }

    /**
     * Drops this view's own heading below the page's.
     *
     * Only the text moves; the paper still runs the full height of the page behind the title bar,
     * because a drawing that started below the chrome would leave a band of blank colour with no
     * ruling on it and stop looking like a page from a book.
     */
    override fun applyTopInset(pixels: Int) {
        if (pixels == topInset) return
        topInset = pixels
        applyHeaderPadding()
        (overlay as? TopInsetAware)?.applyTopInset(pixels)
    }

    private fun applyHeaderPadding() {
        headerBox?.setPadding(
            PaperUi.dp(context, 56f),
            topInset + PaperUi.dp(context, 6f),
            PaperUi.dp(context, 14f),
            0,
        )
    }

    private fun marginStart() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = PaperUi.dp(context, 8f) }

    private fun refreshHeader() {
        val level = base.level
        header.text = "${base.name} — level $level"
        val toNext = Era.xpToNext(level)
        val into = (base.xp - Era.xpForLevel(level)).coerceAtLeast(0)
        subHeader.text = buildString {
            append(Era.ageOf(level).label)
            append(" · ")
            append(PaperUi.shortNumber(base.xp))
            append(" XP (")
            append(PaperUi.shortNumber(into))
            append("/")
            append(PaperUi.shortNumber(toNext))
            append(") · ")
            append("${base.soldiers}/${base.armyCapacity} soldiers")
            if (base.tributarySoldiers > 0) append(" (+${base.tributarySoldiers})")
            append(" · ")
            append("${base.freeBuilders}/${base.builders} builders free")
            append(" · ")
            append("${base.civilians}/${base.civilianCapacity} civilians")
            val tax = base.civilianTaxPerMinute()
            if (tax > 0.05) append(" · %.1f XP/min".format(tax))
            if (base.annexed.isNotEmpty()) {
                append(" · ${base.annexed.size} province")
                if (base.annexed.size != 1) append("s")
            }
            if (base.underConstruction.isNotEmpty()) {
                append(" · building ${base.underConstruction.size}")
            }
            if (base.lawInProgress != null) append(" · debating")
        }
    }

    private fun onTapEmpty(x: Int, y: Int) {
        val type = baseView.placing ?: return
        if (!baseView.canPlaceAt(x, y, type.footprint)) {
            PaperAudio.refuse()
            toast("Something is already drawn there.")
            return
        }
        val verdict = base.canPlace(type)
        if (verdict != PaperBase.Placement.Allowed) {
            PaperAudio.refuse()
            toast(explain(type, verdict))
            return
        }
        commit(base.place(type, x, y, System.currentTimeMillis()))
        baseView.placing = null
        endPlacement()
        PaperAudio.scratch()
        toast("${type.name} started — ${PaperUi.shortDuration(type.buildSeconds * 1000L)}")
    }

    private fun explain(type: BuildingCatalog.BuildingType, verdict: PaperBase.Placement): String = when (verdict) {
        is PaperBase.Placement.Locked -> "${type.name} unlocks at level ${verdict.atLevel}."
        is PaperBase.Placement.AtCap -> "You already have ${verdict.cap}, which is the limit at level ${base.level}."
        PaperBase.Placement.NoBuilder -> "Every builder is busy. Build another builders' hut."
        is PaperBase.Placement.NotEnoughXp -> "Needs ${PaperUi.shortNumber(verdict.short)} more XP."
        PaperBase.Placement.Allowed -> ""
    }

    // -- Sheets ---------------------------------------------------------------

    private fun closeSheet() {
        PaperUi.dismiss(this, openSheet)
        openSheet = null
    }

    /**
     * Explains a base that came back a different shape. See [MinigameStore.recoverIfErased].
     */
    private fun showRecoveryNoticeIfAny() {
        if (!MinigameStore.baseWasRecovered) return
        MinigameStore.clearRecoveryNotice()
        openSheet = PaperUi.sheet(this, "Your country was redrawn") { body ->
            body.addView(
                PaperUi.body(
                    context,
                    "A raid while you were away had rubbed out every building on the page — a fault in " +
                        "how raid damage was applied, not something you did. Your level, XP, army, " +
                        "civilians, research and provinces were never touched.\n\n" +
                        "The page has been redrawn with a base fit for level ${base.level}. It is not the " +
                        "layout you had; that was not saved anywhere and could not be recovered. Move " +
                        "things where you want them.\n\n" +
                        "Raids no longer delete anything: buildings are knocked down and repair themselves.",
                )
            )
            body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Right") { closeSheet() }))
        }
    }

    private fun showOpeningRaid() {
        MinigameStore.markOpeningRaidSeen()
        openSheet = PaperUi.sheet(this, "They burned it down") { body ->
            Battle.openingRaidNarrative().forEach {
                body.addView(PaperUi.body(context, it))
                body.addView(PaperUi.spacer(context, 6f))
            }
            body.addView(PaperUi.divider(context))
            body.addView(PaperUi.note(context, "Build a town hall. Everything else follows from it."))
            body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Start drawing") { closeSheet() }))
        }
    }

    /**
     * The build menu, grouped by category and marking what is out of reach.
     *
     * Locked entries are SHOWN rather than hidden, greyed with the level they need. A player at
     * level 40 looking at a list that ends abruptly has no idea whether there are six more buildings
     * or six hundred; seeing "Railgun Battery — level 317" is most of what makes a long progression
     * feel like one.
     */
    /**
     * Which age's catalogue the build sheet is showing, and which category is open in it.
     *
     * Kept on the view rather than in the sheet so re-opening the sheet lands where you left it —
     * placing three walls in a row should not mean finding the wall list three times.
     */
    private var buildAge: Era.Age? = null
    private var buildCategory: BuildingCatalog.Category? = null

    /**
     * The ages whose catalogues are open to the player, newest first.
     *
     * Newest first because that is what the player is usually looking for, and the older ones are
     * still there because nothing ever expires: a modern nation can still put up a wheat farm, and
     * the weapons research hut and the university remain buildable for the rest of the game. That
     * was always true of the rules — [PaperBase.canPlace] only ever checked the unlock level and the
     * cap — but the old flat list showed the last fourteen entries per category, so once the modern
     * era filled those fourteen slots the medieval buildings simply vanished from the sheet and
     * looked as though they had been taken away.
     */
    private fun openAges(level: Int): List<Era.Age> =
        Era.Age.entries.filter { it.first <= level }.reversed()

    private fun showBuildMenu() {
        val level = base.level
        val ages = openAges(level)
        val age = buildAge?.takeIf { it in ages } ?: ages.first()
        buildAge = age

        val inAge = BuildingCatalog.ALL.filter { it.age == age }
        val unlocked = inAge.filter { it.unlockLevel <= level }
        val isCurrentAge = age == ages.first()

        // A category is worth a header if there is something in it now, or something coming soon in
        // the age the player is actually living in.
        val categories = BuildingCatalog.Category.entries.filter { category ->
            unlocked.any { it.category == category } ||
                (isCurrentAge && inAge.any { it.category == category && it.unlockLevel <= level + 40 })
        }
        val category = buildCategory?.takeIf { it in categories } ?: categories.firstOrNull()
        buildCategory = category

        openSheet = PaperUi.sheet(this, "Build") { body ->
            body.addView(PaperUi.note(context, "${base.freeBuilders} of ${base.builders} builders free · ${PaperUi.shortNumber(base.xp)} XP"))

            // The boundary expansion, when the level has unlocked one that has not been bought.
            // Lives at the top of the sheet rather than in a category, because it is not a
            // building: it does not sit anywhere on the paper, it makes the paper itself bigger.
            if (base.canExpandPlot()) {
                body.addView(PaperUi.spacer(context, 6f))
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, "Expand Boundaries"))
                                addView(
                                    PaperUi.note(
                                        context,
                                        "Grow the field by ${Era.PLOT_EXPANSION_STEP} cells a side " +
                                            "(${base.plotSize} → ${base.plotSize + Era.PLOT_EXPANSION_STEP}) · " +
                                            "${PaperUi.shortNumber(base.expandPlotCost)} XP",
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            PaperUi.button(context, "Expand", colour = PencilStyle.GREEN_PENCIL, seed = 9001) {
                                if (base.xp < base.expandPlotCost) {
                                    toast("Not enough XP to expand the boundaries yet.")
                                } else {
                                    commit(base.expandPlot())
                                    closeSheet()
                                    baseView.recentre()
                                    toast("The boundaries have grown.")
                                    showBuildMenu()
                                }
                            },
                        )
                    }
                )
                body.addView(PaperUi.divider(context))
            }

            if (ages.size > 1) {
                body.addView(PaperUi.spacer(context, 8f))
                body.addView(
                    PaperUi.tabStrip(context, ages.map { it.label }, ages.indexOf(age)) { picked ->
                        buildAge = ages[picked]
                        // The category is per-age: "Fortification" in the modern list is a different
                        // set of things, and keeping a category that the new age has nothing in
                        // would open the sheet on an empty section.
                        buildCategory = null
                        closeSheet()
                        showBuildMenu()
                    },
                )
            }
            body.addView(PaperUi.divider(context))

            if (categories.isEmpty() || category == null) {
                body.addView(PaperUi.note(context, "Nothing from this age is available yet."))
                return@sheet
            }

            // Categories as a second strip rather than as twelve expanded lists. The medieval
            // catalogue alone is several hundred buildings by level 250, and inflating all of them
            // into one scrolling column is both unreadable and slow — a sheet should not take a
            // second to open.
            body.addView(
                PaperUi.tabStrip(context, categories.map { it.label }, categories.indexOf(category)) { picked ->
                    buildCategory = categories[picked]
                    closeSheet()
                    showBuildMenu()
                },
            )
            body.addView(PaperUi.spacer(context, 4f))

            val available = unlocked.filter { it.category == category }
            val upcoming = if (isCurrentAge) {
                inAge.filter { it.category == category && it.unlockLevel > level }.take(4)
            } else {
                emptyList()
            }

            if (available.isEmpty() && upcoming.isEmpty()) {
                body.addView(PaperUi.note(context, "Nothing here yet."))
                return@sheet
            }

            // Newest first inside the category, so the best version of a thing is at the top and the
            // early ones are below it rather than the other way round.
            available.asReversed().forEach { type -> body.addView(buildRow(type, level)) }
            if (upcoming.isNotEmpty()) {
                body.addView(PaperUi.spacer(context, 6f))
                body.addView(PaperUi.note(context, "Coming up"))
                upcoming.forEach { type -> body.addView(buildRow(type, level)) }
            }
        }
    }

    private fun buildRow(type: BuildingCatalog.BuildingType, level: Int): View {
        val locked = type.unlockLevel > level
        val owned = base.countOwned(type.id)
        val cap = type.capAtLevel(level)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
            isClickable = !locked
            alpha = if (locked) 0.45f else 1f

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(PaperUi.body(context, type.name))
                    addView(
                        PaperUi.note(
                            context,
                            if (locked) "unlocks at level ${type.unlockLevel}"
                            else "$owned/$cap · ${PaperUi.shortNumber(type.buildCost)} XP · " +
                                PaperUi.shortDuration(type.buildSeconds * 1000L) +
                                " · ${type.footprint}x${type.footprint}",
                        )
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )

            if (!locked) {
                addView(
                    PaperUi.button(context, "Place", seed = type.id.hashCode().toLong()) {
                        val verdict = base.canPlace(type)
                        if (verdict != PaperBase.Placement.Allowed) {
                            toast(explain(type, verdict))
                        } else {
                            closeSheet()
                            beginPlacement(type)
                        }
                    },
                )
            }
        }
    }

    private fun showBuildingSheet(building: PlacedBuilding) {
        val type = building.type ?: return
        openSheet = PaperUi.sheet(this, type.name) { body ->
            body.addView(PaperUi.statLine(context, "Category", type.category.label))
            body.addView(PaperUi.statLine(context, "Era", type.age.label))
            body.addView(PaperUi.statLine(context, "Condition", "${building.hitPoints} / ${building.maxHitPoints}"))
            if (!building.complete) {
                val now = System.currentTimeMillis()
                val left = building.finishesAt - now
                val rush = base.rushCostOf(building, now)
                body.addView(PaperUi.statLine(context, "Finishes in", PaperUi.shortDuration(left)))
                body.addView(PaperUi.statLine(context, "Finish now", "${PaperUi.shortNumber(rush)} XP"))
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        PaperUi.button(context, "Fast forward", colour = PencilStyle.BLUE_PENCIL) {
                            if (base.xp < rush) {
                                toast("That needs ${PaperUi.shortNumber(rush - base.xp)} more XP.")
                            } else {
                                commit(base.rushBuilding(building.id, System.currentTimeMillis()))
                                closeSheet()
                                toast("${type.name} finished.")
                            }
                        },
                    )
                )
            }
            if (type.residents > 0) body.addView(PaperUi.statLine(context, "Residents", type.residents.toString()))
            if (type.isDefensive) body.addView(PaperUi.note(context, "This one shoots back."))

            body.addView(PaperUi.divider(context))
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Close") { closeSheet() },
                    PaperUi.button(context, "Rub out", colour = PencilStyle.RED_PENCIL) {
                        commit(base.demolish(building.id))
                        closeSheet()
                        toast("Rubbed out. No refund — that was the deal.")
                    },
                )
            )
        }
    }

    private fun showArmySheet() {
        openSheet = PaperUi.sheet(this, "Army") { body ->
            val weapon = base.infantryWeapon()
            body.addView(PaperUi.statLine(context, "Soldiers", "${base.soldiers} / ${base.armyCapacity}"))
            body.addView(PaperUi.statLine(context, "Camps", base.countStanding(BuildingCatalog.TRAINING_CAMP.id).toString()))
            body.addView(PaperUi.statLine(context, "Per camp", Era.soldiersPerCamp(base.level).toString()))
            body.addView(PaperUi.statLine(context, "Carrying", weapon.name))
            body.addView(PaperUi.statLine(context, "On the walls", base.garrisonWeapon().name))
            body.addView(PaperUi.note(context, "Soldiers train by themselves, up to the camps' capacity. More camps every five levels."))

            body.addView(PaperUi.divider(context))
            body.addView(PaperUi.statLine(context, "Raids won", base.battlesWon.toString()))
            body.addView(PaperUi.statLine(context, "Raids lost", base.battlesLost.toString()))
            body.addView(PaperUi.statLine(context, "Held at home", base.raidsRepelled.toString()))
            body.addView(PaperUi.statLine(context, "Lost at home", base.raidsLost.toString()))

            body.addView(PaperUi.divider(context))
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Arm soldiers") { showLoadout(forGarrison = false) },
                    PaperUi.button(context, "Arm the walls") { showLoadout(forGarrison = true) },
                )
            )
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Detachments") { showDetachments() },
                )
            )

            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Train 5") {
                        val before = base.soldiers
                        commit(base.trainSoldiers(5))
                        toast(if (base.soldiers > before) "Trained ${base.soldiers - before}." else "No room, or not enough XP.")
                    },
                    PaperUi.button(context, "Close") { closeSheet() },
                )
            )
        }
    }

    /**
     * Choosing what the army carries.
     *
     * Only researched weapons, and "whatever is best" stays available as an explicit choice rather
     * than being the silent default with no way back to it. The list is ordered by threat because
     * that is the order a player thinks in, and every row says what the weapon actually does — a
     * name alone is not a decision when the catalogue is eleven hundred deep.
     */
    /**
     * Which age and which class the weapon sheets are showing.
     *
     * Shared between research and the loadout, because they are two views of the same eleven
     * hundred weapons and a player who went looking for armour in one has not changed their mind by
     * the time they open the other.
     */
    private var weaponAge: Era.Age? = null
    private var weaponClass: WeaponCatalog.WeaponClass? = null

    /**
     * The ages of weapon open to the player, newest first.
     *
     * Every one of them stays open forever. That was already true of the rules and is worth saying
     * plainly: a futuristic country can still study and issue a crossbow, and more to the point the
     * medieval and modern catalogues are where most of the siege and artillery pieces live.
     */
    private fun weaponAges(level: Int): List<Era.Age> =
        Era.Age.entries.filter { it.first <= level }.reversed()

    /**
     * Narrows [pool] to the selected age and class, laying out the two tab strips that do it.
     *
     * Returns null when there is nothing to show, having already said so.
     *
     * ## Why this had to stop being a flat list
     *
     * Research showed "the best twenty available", sorted by raw threat, out of eleven hundred and
     * forty weapons. That reads as reasonable and is not: threat is dominated by the late artillery
     * and missile classes, so the top twenty at a high level is twenty artillery pieces and nothing
     * else. Every tank and every aircraft in the game sat below the cut and could not be found,
     * which is why a level-500 army had no armour in it -- not because armour was unavailable, but
     * because nothing in the interface would show it.
     */
    private fun weaponPicker(
        body: LinearLayout,
        pool: List<WeaponCatalog.Weapon>,
        level: Int,
        reopen: () -> Unit,
    ): List<WeaponCatalog.Weapon>? {
        val ages = weaponAges(level)
        val age = weaponAge?.takeIf { it in ages } ?: ages.first()
        weaponAge = age

        if (ages.size > 1) {
            body.addView(
                PaperUi.tabStrip(context, ages.map { it.label }, ages.indexOf(age)) { picked ->
                    weaponAge = ages[picked]
                    weaponClass = null
                    closeSheet()
                    reopen()
                },
            )
            body.addView(PaperUi.spacer(context, 4f))
        }

        val inAge = pool.filter { it.age == age }
        val classes = WeaponCatalog.WeaponClass.entries.filter { c -> inAge.any { it.weaponClass == c } }
        if (classes.isEmpty()) {
            body.addView(PaperUi.note(context, "Nothing from this age here."))
            return null
        }
        val weaponClassNow = weaponClass?.takeIf { it in classes } ?: classes.first()
        weaponClass = weaponClassNow

        body.addView(
            PaperUi.tabStrip(context, classes.map { it.label }, classes.indexOf(weaponClassNow)) { picked ->
                weaponClass = classes[picked]
                closeSheet()
                reopen()
            },
        )
        body.addView(PaperUi.spacer(context, 6f))
        return inAge.filter { it.weaponClass == weaponClassNow }.sortedByDescending { it.threat }
    }

    private fun showLoadout(forGarrison: Boolean) {
        closeSheet()
        val armoury = base.armoury()
        val current = if (forGarrison) base.garrisonWeaponId else base.infantryWeaponId
        val inUse = if (forGarrison) base.garrisonWeapon() else base.infantryWeapon()

        openSheet = PaperUi.sheet(
            this,
            if (forGarrison) "Arm the walls" else "Arm the soldiers",
            seed = if (forGarrison) 4242 else 4243,
        ) { body ->
            body.addView(
                PaperUi.note(
                    context,
                    if (forGarrison) "What defenders and garrisons fire when this base is raided."
                    else "What your soldiers carry when they go out.",
                )
            )
            body.addView(PaperUi.statLine(context, "Currently", inUse.name))
            body.addView(PaperUi.divider(context))

            if (armoury.isEmpty()) {
                body.addView(PaperUi.body(context, "Nothing researched yet."))
                body.addView(PaperUi.note(context, "Research a weapon and it can be issued here."))
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            fun row(label: String, detail: String, chosen: Boolean, onPick: () -> Unit) {
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, label).apply {
                                    typeface = android.graphics.Typeface.create(
                                        android.graphics.Typeface.SERIF,
                                        if (chosen) android.graphics.Typeface.BOLD
                                        else android.graphics.Typeface.NORMAL,
                                    )
                                })
                                addView(PaperUi.note(context, detail))
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        if (chosen) {
                            addView(
                                PaperUi.GlyphButton(context, PaperUi.Glyph.TICK, PencilStyle.GREEN_PENCIL),
                                LinearLayout.LayoutParams(PaperUi.dp(context, 36f), PaperUi.dp(context, 36f)),
                            )
                        } else {
                            addView(PaperUi.button(context, "Issue", seed = label.hashCode().toLong()) { onPick() })
                        }
                    }
                )
            }

            row("Whatever is best", "Let the quartermaster decide, as before.", current == null) {
                commit(
                    if (forGarrison) base.withGarrisonWeapon(null) else base.withInfantryWeapon(null)
                )
                closeSheet()
                PaperAudio.chime()
                toast("Back to the quartermaster's choice.")
            }
            body.addView(PaperUi.divider(context))

            val shown = weaponPicker(body, armoury, base.level) { showLoadout(forGarrison) } ?: return@sheet
            shown.forEach { weapon ->
                row(
                    weapon.name,
                    "${weapon.damage} dmg · range ${weapon.range}" +
                        (if (weapon.splash > 0) " · blast ${weapon.splash}" else ""),
                    weapon.id == current,
                ) {
                    commit(
                        if (forGarrison) base.withGarrisonWeapon(weapon.id)
                        else base.withInfantryWeapon(weapon.id)
                    )
                    closeSheet()
                    PaperAudio.fire(weapon, volume = 0.5f)
                    toast("Issued ${weapon.name}.")
                }
            }
        }
    }

    /**
     * The detachments: what goes out with the infantry.
     *
     * Toggles rather than a single choice, because an army is a mix — that is the whole point of
     * the screen. Each one fields [PaperBase.DETACHMENT_SHARE] of the army as that vehicle, in
     * place of the foot soldiers it replaces.
     */
    private fun showDetachments() {
        closeSheet()
        val crewed = base.crewedArmoury()
        val current = base.supportWeapons().map { it.id }
        val automatic = base.supportWeaponIds == null

        openSheet = PaperUi.sheet(this, "Detachments", seed = 4244) { body ->
            body.addView(
                PaperUi.note(
                    context,
                    "Tanks, aircraft, guns and launchers that go out with the army. Each takes " +
                        "${(PaperBase.DETACHMENT_SHARE * 100).toInt()}% of your soldiers, up to " +
                        "${PaperBase.MAX_DETACHMENTS} at once.",
                )
            )
            if (crewed.isEmpty()) {
                body.addView(PaperUi.divider(context))
                body.addView(PaperUi.body(context, "Nothing crewed has been researched yet."))
                body.addView(
                    PaperUi.note(
                        context,
                        "Armour, aircraft, artillery, missiles and drones all field as their own " +
                            "units. Look under those headings in research.",
                    )
                )
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            body.addView(
                PaperUi.statLine(
                    context,
                    if (automatic) "Fielding (chosen for you)" else "Fielding",
                    if (current.isEmpty()) "infantry only"
                    else base.supportWeapons().joinToString(", ") { it.name },
                )
            )
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Choose for me") {
                        commit(base.withSupportWeapons(null))
                        closeSheet()
                        showDetachments()
                    },
                    PaperUi.button(context, "Infantry only") {
                        commit(base.withSupportWeapons(emptyList()))
                        closeSheet()
                        showDetachments()
                    },
                )
            )
            body.addView(PaperUi.divider(context))

            val shown = weaponPicker(body, crewed, base.level) { showDetachments() } ?: return@sheet
            shown.forEach { weapon ->
                val on = weapon.id in current
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, weapon.name))
                                addView(
                                    PaperUi.note(
                                        context,
                                        "${weapon.damage} dmg · range ${weapon.range}" +
                                            (if (weapon.splash > 0) " · blast ${weapon.splash}" else "") +
                                            " · ${String.format("%.1f", weapon.crewSurvivability)}x tougher",
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            PaperUi.button(
                                context,
                                if (on) "Stand down" else "Field",
                                colour = if (on) PencilStyle.RED_PENCIL else null,
                                seed = weapon.id.hashCode().toLong(),
                            ) {
                                val next = if (on) current - weapon.id else current + weapon.id
                                if (next.size > PaperBase.MAX_DETACHMENTS) {
                                    toast("That is as many kinds as one army can take.")
                                } else {
                                    commit(base.withSupportWeapons(next))
                                    closeSheet()
                                    showDetachments()
                                }
                            },
                        )
                    }
                )
            }
        }
    }

    /**
     * Research: the list of weapons, and what they would cost.
     *
     * Tabbed by age and then by class. See [weaponPicker] for why a flat "best twenty" list was
     * actively hiding most of the catalogue.
     */
    private fun showResearchSheet() {
        val hasLab = base.countStanding("weapons_research") > 0
        openSheet = PaperUi.sheet(this, "Weapons research") { body ->
            if (!hasLab) {
                body.addView(PaperUi.body(context, "You need a Weapons Research building before anything can be studied."))
                body.addView(PaperUi.note(context, "It unlocks at level 3, and stays available at every level after it."))
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            body.addView(PaperUi.note(context, "${base.researched.size} of ${WeaponCatalog.ALL.size} studied · ${PaperUi.shortNumber(base.xp)} XP"))
            body.addView(PaperUi.divider(context))

            // Everything, studied or not: a list that hides what you already own gives no sense of
            // how far through a class you are, and the studied ones are what the loadout draws on.
            val pool = WeaponCatalog.ALL.filter { it.unlockLevel <= base.level + 40 }
            val shown = weaponPicker(body, pool, base.level) { showResearchSheet() } ?: return@sheet

            shown.forEach { weapon ->
                val locked = weapon.unlockLevel > base.level
                val known = weapon.id in base.researched
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
                        alpha = if (locked) 0.45f else 1f
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, weapon.name))
                                addView(
                                    PaperUi.note(
                                        context,
                                        if (locked) "unlocks at level ${weapon.unlockLevel}"
                                        else "${weapon.damage} dmg · range ${weapon.range}" +
                                            (if (weapon.splash > 0) " · blast ${weapon.splash}" else "") +
                                            (if (weapon.weaponClass.isCrewed) " · crewed" else "") +
                                            (if (known) "" else " · ${PaperUi.shortNumber(base.researchCost(weapon))} XP"),
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        if (known) {
                            addView(
                                PaperUi.GlyphButton(context, PaperUi.Glyph.TICK, PencilStyle.GREEN_PENCIL),
                                LinearLayout.LayoutParams(PaperUi.dp(context, 36f), PaperUi.dp(context, 36f)),
                            )
                        } else if (!locked) {
                            addView(
                                PaperUi.button(context, "Study", seed = weapon.id.hashCode().toLong()) {
                                    if (!base.canResearch(weapon)) {
                                        toast("Not enough XP for that yet.")
                                    } else {
                                        commit(base.research(weapon))
                                        closeSheet()
                                        showResearchSheet()
                                        toast("${weapon.name} researched.")
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * The State: what the country is, what it may become, and what it has conquered.
     *
     * Gated on a university and says so plainly when it is not available, because "nothing happens
     * when I tap this" is the worst possible answer to a locked feature.
     */
    private fun showGovernmentSheet() {
        openSheet = PaperUi.sheet(this, "The state") { body ->
            body.addView(PaperUi.statLine(context, "Character", base.ideology))
            body.addView(PaperUi.statLine(context, "Laws enacted", "${base.enactedLaws.size} of ${Ideology.ALL_LAWS.size}"))
            body.addView(PaperUi.statLine(context, "Civilians", "${base.civilians} of ${base.civilianCapacity}"))
            body.addView(PaperUi.statLine(context, "Taxes", "%.1f XP/min".format(base.civilianTaxPerMinute())))
            if (base.annexed.isNotEmpty()) {
                body.addView(PaperUi.statLine(context, "Provinces", base.annexed.size.toString()))
                body.addView(PaperUi.statLine(context, "Tributary soldiers", base.tributarySoldiers.toString()))
            }

            val effects = base.lawEffects.describe()
            if (effects.isNotEmpty()) {
                body.addView(PaperUi.divider(context))
                body.addView(PaperUi.body(context, "In force").apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                })
                effects.forEach { body.addView(PaperUi.note(context, it)) }
            }

            val leanings = Ideology.rank(base.enactedLaws).take(3)
            if (leanings.isNotEmpty()) {
                body.addView(PaperUi.divider(context))
                leanings.forEach { (stance, score) ->
                    body.addView(
                        PaperUi.statLine(context, stance.name, "${(score * 100).toInt()}%")
                    )
                }
                body.addView(PaperUi.note(context, leanings.first().first.summary))
            }

            body.addView(PaperUi.divider(context))

            if (!base.hasUniversity) {
                body.addView(
                    PaperUi.body(
                        context,
                        "Law needs a university. Build one and the country can start deciding " +
                            "what kind of country it is.",
                    )
                )
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            base.lawInProgress?.let { pending ->
                val law = Ideology.law(pending)
                val now = System.currentTimeMillis()
                val rush = base.rushLawCost(now)
                body.addView(PaperUi.body(context, "Debating: ${law?.name ?: pending}"))
                body.addView(PaperUi.note(context, "Passes in ${PaperUi.shortDuration(base.lawFinishesAt - now)}"))
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        PaperUi.button(context, "Force it through", colour = PencilStyle.BLUE_PENCIL) {
                            if (base.xp < rush) toast("That needs ${PaperUi.shortNumber(rush - base.xp)} more XP.")
                            else {
                                commit(base.rushLaw(System.currentTimeMillis()))
                                closeSheet()
                                toast("${law?.name ?: "The law"} is in force.")
                            }
                        },
                        PaperUi.button(context, "Close") { closeSheet() },
                    )
                )
                return@sheet
            }

            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Laws", filled = true) { showLawBranches() },
                    PaperUi.button(context, "Provinces") { showProvinces() },
                )
            )
        }
    }

    private fun showLawBranches() {
        closeSheet()
        openSheet = PaperUi.sheet(this, "Laws") { body ->
            Ideology.Branch.entries.forEach { branch ->
                body.addView(PaperUi.body(context, branch.label).apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    setPadding(0, PaperUi.dp(context, 10f), 0, PaperUi.dp(context, 4f))
                })
                Ideology.GROUPS.filter { it.branch == branch }.forEach { group ->
                    val current = Ideology.lawIn(base.enactedLaws, group.id)
                    body.addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
                            addView(
                                LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(PaperUi.body(context, group.name))
                                    addView(PaperUi.note(context, current?.name ?: "—"))
                                },
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                            )
                            addView(
                                PaperUi.button(context, "Change", seed = group.id.hashCode().toLong()) {
                                    showLawGroup(group)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun showLawGroup(group: Ideology.Group) {
        closeSheet()
        openSheet = PaperUi.sheet(this, group.name, seed = group.id.hashCode().toLong()) { body ->
            body.addView(PaperUi.note(context, group.description))
            body.addView(PaperUi.divider(context))

            val current = Ideology.lawIn(base.enactedLaws, group.id)
            group.laws.forEach { law ->
                val inForce = law.id == current?.id
                val locked = base.level < law.minLevel
                val cost = base.lawCost(law)

                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, PaperUi.dp(context, 8f), 0, PaperUi.dp(context, 8f))
                        alpha = if (locked) 0.45f else 1f
                        if (inForce) {
                            background = PaperUi.PencilButtonDrawable(
                                context, law.id.hashCode().toLong(), PencilStyle.GREEN_PENCIL, filled = true,
                            )
                        }
                        addView(PaperUi.body(context, law.name).apply {
                            typeface = android.graphics.Typeface.create(
                                android.graphics.Typeface.SERIF,
                                if (inForce) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                            )
                        })
                        addView(PaperUi.note(context, law.summary))
                        law.effects.describe().forEach {
                            addView(PaperUi.note(context, "· $it"))
                        }
                        addView(
                            PaperUi.note(
                                context,
                                when {
                                    inForce -> "In force"
                                    locked -> "Needs level ${law.minLevel}"
                                    else -> "${PaperUi.shortNumber(cost)} XP · ${PaperUi.shortDuration(law.seconds * 1000L)}"
                                },
                            )
                        )
                        if (!inForce && !locked) {
                            addView(
                                PaperUi.button(context, "Enact", seed = law.id.hashCode().toLong()) {
                                    if (!base.canEnact(law)) {
                                        toast(
                                            if (base.lawInProgress != null) "One debate at a time."
                                            else "Needs ${PaperUi.shortNumber(cost - base.xp)} more XP."
                                        )
                                    } else {
                                        commit(base.beginLaw(law, System.currentTimeMillis()))
                                        closeSheet()
                                        toast("The house is debating ${law.name}.")
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Opens a province to be built in, trained and researched -- see [ProvinceManagerView].
     *
     * A province taken before this existed has no base of its own yet ([AnnexedCountry.base] is
     * null on it); one is generated for it here, the same way [WorldMap.spoilsOf] generates one for
     * a fresh conquest, so an old save is never left with provinces it cannot manage.
     */
    private fun manageProvince(provinceId: String) {
        val province = base.annexed.firstOrNull { it.id == provinceId } ?: return
        val province0 = province.base?.let { province } ?: province.withBase(
            WorldMap.aiBase(
                WorldMap.Country(
                    id = province.id, name = province.name, owner = WorldMap.Owner.AI,
                    level = province.level, outline = emptyList(), centreX = 0.0, centreY = 0.0,
                    soldiers = province.soldiers, population = province.civilians,
                    xp = Era.xpForLevel(province.level),
                )
            ).copy(civilians = province.civilians, families = (province.civilians / 4).coerceAtLeast(0)),
        )
        if (province0 !== province) {
            commit(base.copy(annexed = base.annexed.map { if (it.id == provinceId) province0 else it }))
        }

        overlay?.let { removeView(it) }
        PaperAudio.stopAmbience()
        val manager = ProvinceManagerView(
            context = context,
            provinceId = provinceId,
            initialBase = requireNotNull(province0.base),
            provinceName = province0.name,
            onChanged = { updated ->
                commit(base.copy(annexed = base.annexed.map { if (it.id == provinceId) it.withBase(updated) else it }))
            },
            onExit = { closeOverlay() },
        ).also { it.applyTopInset(topInset) }
        overlay = manager
        addView(manager, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The provinces: what each was, and what it is worth now. */
    private fun showProvinces() {
        closeSheet()
        openSheet = PaperUi.sheet(this, "Provinces") { body ->
            if (base.annexed.isEmpty()) {
                body.addView(PaperUi.body(context, "None yet."))
                body.addView(
                    PaperUi.note(
                        context,
                        "Invade a country and win, and it becomes a province — its army, its " +
                            "defences, its people and their taxes all come with it.",
                    )
                )
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            base.annexed.forEach { province ->
                body.addView(PaperUi.body(context, province.name).apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    setPadding(0, PaperUi.dp(context, 10f), 0, 0)
                })
                body.addView(PaperUi.statLine(context, "Level", province.level.toString()))
                body.addView(PaperUi.statLine(context, "Soldiers", "${(province.soldiers * province.loyalty).toInt()} of ${province.soldiers}"))
                body.addView(PaperUi.statLine(context, "Civilians", province.civilians.toString()))
                body.addView(PaperUi.statLine(context, "Pays", "%.1f XP/min".format(province.taxPerMinute())))
                body.addView(PaperUi.statLine(context, "Loyalty", "${(province.loyalty * 100).toInt()}% · ${province.describeLoyalty()}"))
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        PaperUi.button(context, "Manage", colour = PencilStyle.GREEN_PENCIL, seed = province.id.hashCode().toLong() + 1) {
                            closeSheet()
                            manageProvince(province.id)
                        },
                        PaperUi.button(context, "Release", colour = PencilStyle.RED_PENCIL, seed = province.id.hashCode().toLong()) {
                            commit(base.releaseAnnexed(province.id))
                            MinigameStore.setRelation(province.id, WorldMap.Relation.HOSTILE)
                            closeSheet()
                            toast("${province.name} is free, and will remember it.")
                        },
                    )
                )
            }
        }
    }

    // -- The world map --------------------------------------------------------

    private fun showWorldMap() {
        rebuildWorld()
        closeSheet()
        PaperAudio.stopAmbience()

        val map = WorldMapView(
            context,
            onOpenCountry = { showCountrySheet(it) },
            onZoomInto = { zoomInto(it) },
        ).apply {
            worldSeed = this@PaperWarView.worldSeed
            playerLevel = base.level
            // Only the player and any peers are handed over. Every other country on the map is
            // generated from the seed as the view scrolls -- see WorldChunks.
            fixedCountries = fixedCountries()
            relations = MinigameStore.relations()
            conquered = conqueredByPlayer()
            strikes = MinigameStore.strikes()
            launchSites = mapOf(
                WorldMap.PLAYER_ID to WorldMap.playerCountry(base, worldSeed).let { it.centreX to it.centreY },
            )
            capitals = mapOf(
                WorldMap.PLAYER_ID to (
                    WorldMap.playerCountry(base, worldSeed).let { it.centreX to it.centreY }
                    ),
            )
            caption = when {
                !MinigameMesh.isAvailable() ->
                    "Not on a Prism Meshnet — these are the AI countries only."
                MinigameMesh.isAlone() ->
                    "On the mesh, but nobody else is. AI countries only, for now."
                else ->
                    "${MinigameMesh.peerCountries().size} player countries on the mesh. Double-tap a country to look inside."
            }
        }
        mapView = map

        val screen = FrameLayout(context)
        screen.addView(map, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            // Under the page's own title, like every other heading this view draws.
            setPadding(p, topInset + p, p, p)
            addView(
                PaperUi.GlyphButton(context, PaperUi.Glyph.BACK).apply {
                    setOnClickListener { closeOverlay() }
                },
                LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
            )
            addView(PaperUi.heading(context, "The world").apply {
                setPadding(PaperUi.dp(context, 8f), 0, 0, 0)
            })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(
                PaperUi.GlyphButton(context, PaperUi.Glyph.INBOX, badge = { MinigameStore.unansweredCount() }).apply {
                    setOnClickListener { showInbox() }
                },
                LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
            )
        }
        screen.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        overlay = screen
        addView(screen, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private var overlay: View? = null

    private fun closeOverlay() {
        overlay?.let { removeView(it) }
        overlay = null
        // Back to the base, and back to birds.
        if (running) PaperAudio.startAmbience()
        mapView = null
        battleView = null
        baseView.battleFrame = null
        baseView.base = base
        refreshHeader()
    }

    /**
     * A country's sheet: what it is, and the two things you can do about it.
     *
     * The stats are the ones the design names — XP, buildings, level, soldiers, population — plus
     * the relation, because whether somebody is already an ally is the single most useful thing on
     * this sheet and the thing a player most often forgets.
     */
    private fun showCountrySheet(country: WorldMap.Country) {
        // A country you have taken is a province of yours, not a foreign state. Treating it as one
        // is what stops the map offering to ally with, or invade, land you already hold.
        val province = base.annexed.firstOrNull { it.id == country.id }
        val isMine = country.owner == WorldMap.Owner.PLAYER || province != null
        openSheet = PaperUi.sheet(this, country.name, seed = country.id.hashCode().toLong()) { body ->
            body.addView(
                PaperUi.note(
                    context,
                    when {
                        province != null ->
                            "A province of yours. Taken from " +
                                "${province.name}; loyalty ${(province.loyalty * 100).toInt()}%."
                        country.owner == WorldMap.Owner.PLAYER -> "This one is yours."
                        country.owner == WorldMap.Owner.PEER -> "A player, on the mesh."
                        else -> "Run by the computer."
                    }
                )
            )
            body.addView(PaperUi.divider(context))
            body.addView(PaperUi.statLine(context, "Level", country.level.toString()))
            body.addView(PaperUi.statLine(context, "Era", country.age.label))
            body.addView(PaperUi.statLine(context, "XP", PaperUi.shortNumber(country.xp)))
            body.addView(PaperUi.statLine(context, "Buildings", country.buildingCount.toString()))
            body.addView(PaperUi.statLine(context, "Soldiers", country.soldiers.toString()))
            body.addView(PaperUi.statLine(context, "Civilians", country.population.toString()))
            body.addView(PaperUi.statLine(context, "Strength", PaperUi.shortNumber(country.strength)))
            body.addView(PaperUi.statLine(context, "Record", "${country.battlesWon} won, ${country.battlesLost} lost"))
            body.addView(
                PaperUi.statLine(
                    context, "Relation",
                    when (country.relation) {
                        WorldMap.Relation.ALLIED -> "allied"
                        WorldMap.Relation.REFUSED -> "refused you"
                        WorldMap.Relation.HOSTILE -> "hostile"
                        WorldMap.Relation.NEUTRAL -> "neutral"
                    },
                )
            )

            MinigameMesh.battleFor(country.id)?.let { ticket ->
                body.addView(PaperUi.divider(context))
                body.addView(PaperUi.body(context, "A battle is happening here."))
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        PaperUi.button(context, "Watch it", colour = PencilStyle.RED_PENCIL) {
                            closeSheet()
                            watchBattle(ticket)
                        },
                    )
                )
            }

            // Burning, if it has been hit. Said in the popup as well as drawn on the map, because
            // the numbers above are the reduced ones and that needs explaining.
            val strike = MinigameStore.strikes()[country.id]
            if (strike != null) {
                val scorch = strike.scorch(System.currentTimeMillis())
                body.addView(PaperUi.divider(context))
                body.addView(
                    PaperUi.body(
                        context,
                        if (strike.fromInvasion) "Sacked and still burning."
                        else "Hit with ${strike.kind.label.lowercase()}. Still burning.",
                        colour = PencilStyle.RED_PENCIL,
                    )
                )
                body.addView(
                    PaperUi.note(
                        context,
                        "${(scorch * 100).toInt()}% of it is rubble. It rebuilds as the fires go out.",
                    )
                )
            }

            body.addView(PaperUi.divider(context))
            // Looking at a country is always allowed, including your own and your provinces: the
            // map is a drawing of somewhere, and being able to go and look at it is the whole point.
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Spy") {
                        closeSheet()
                        zoomInto(country)
                    },
                )
            )

            if (isMine) {
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            val ask = PaperUi.button(
                context,
                if (country.relation == WorldMap.Relation.ALLIED) "Already allied" else "Ask for allyship",
                colour = PencilStyle.GREEN_PENCIL,
            ) { askForAllyship(country) }
            ask.isEnabled = country.canBeAsked()
            ask.alpha = if (country.canBeAsked()) 1f else 0.4f

            body.addView(
                PaperUi.buttonRow(
                    context,
                    ask,
                    PaperUi.button(context, "Invade", colour = PencilStyle.RED_PENCIL) {
                        closeSheet()
                        planInvasion(country)
                    },
                )
            )

            // One button per kind of warhead the player has actually researched. Nothing appears
            // until the research does, which is what makes finding it in the catalogue worth
            // something.
            val warheads = WeaponCatalog.wmdsIn(base.researched)
            if (warheads.isNotEmpty()) {
                body.addView(PaperUi.spacer(context, 6f))
                body.addView(PaperUi.note(context, "Strategic"))
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        *warheads.entries.sortedBy { it.key.ordinal }.map { (kind, weapon) ->
                            PaperUi.button(context, kind.verb, colour = PencilStyle.RED_PENCIL) {
                                confirmStrike(country, kind, weapon)
                            }
                        }.toTypedArray(),
                    )
                )
            }
            body.addView(PaperUi.spacer(context, 6f))
            body.addView(
                PaperUi.note(
                    context,
                    if (country.owner == WorldMap.Owner.AI) {
                        "A computer country accepts about one request in four. Asking again after a " +
                            "refusal is allowed, and works less often."
                    } else {
                        "The request goes to their inbox. They decide."
                    }
                )
            )
        }
    }

    private fun askForAllyship(country: WorldMap.Country) {
        closeSheet()
        if (country.owner == WorldMap.Owner.PEER) {
            MinigameMesh.sendAllianceRequest(country.id, base.name, base.level)
            toast("Request sent to ${country.name}.")
            return
        }

        val attempt = MinigameStore.allianceAttempts(country.id)
        MinigameStore.recordAllianceAttempt(country.id)
        val accepted = WorldMap.askAi(country, attempt)
        MinigameStore.setRelation(
            country.id,
            if (accepted) WorldMap.Relation.ALLIED else WorldMap.Relation.REFUSED,
        )
        rebuildWorld()
        mapView?.relations = MinigameStore.relations()
        mapView?.conquered = conqueredByPlayer()
        mapView?.strikes = MinigameStore.strikes()
        mapView?.invalidate()
        toast(
            if (accepted) "${country.name} accepts. Their army fights with yours."
            else "${country.name} refuses."
        )
    }

    private fun allies(): List<WorldMap.Country> =
        allWorld().filter { it.relation == WorldMap.Relation.ALLIED }

    /**
     * The pre-battle sheet: what you are sending, and what they have.
     *
     * An allied country's soldiers are added here rather than in the battle, so the number the
     * player agrees to attack with is the number that actually walks onto the paper.
     */
    private fun planInvasion(country: WorldMap.Country) {
        val allied = allies()
        val extra = WorldMap.alliedSoldiers(allied)
        // Conquered provinces march too, in proportion to how settled they are. That is most of
        // what conquest is FOR: each one taken makes the next one easier.
        val tributary = base.tributarySoldiers
        val total = base.soldiers + extra + tributary
        val weapon = listOfNotNull(base.infantryWeapon(), WorldMap.alliedWeapon(allied))
            .maxByOrNull { it.threat } ?: WeaponCatalog.STARTER

        openSheet = PaperUi.sheet(this, "Invade ${country.name}") { body ->
            body.addView(PaperUi.statLine(context, "Your soldiers", base.soldiers.toString()))
            if (extra > 0) {
                body.addView(PaperUi.statLine(context, "Allied soldiers", "+$extra"))
                body.addView(PaperUi.note(context, allied.joinToString(", ") { it.name }))
            }
            if (tributary > 0) {
                body.addView(PaperUi.statLine(context, "Province levies", "+$tributary"))
                body.addView(PaperUi.note(context, base.annexed.joinToString(", ") { it.name }))
            }
            body.addView(PaperUi.statLine(context, "Going in with", total.toString()))
            body.addView(PaperUi.statLine(context, "Carrying", weapon.name))
            body.addView(PaperUi.divider(context))
            body.addView(PaperUi.statLine(context, "Their level", country.level.toString()))
            body.addView(PaperUi.statLine(context, "Their soldiers", country.soldiers.toString()))
            body.addView(PaperUi.statLine(context, "Their buildings", country.buildingCount.toString()))

            if (total <= 0) {
                body.addView(PaperUi.body(context, "You have nobody to send."))
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            val previous = MinigameStore.invasionsOf(country.id)
            if (previous > 0) {
                val odds = WorldMap.revoltChance(previous)
                body.addView(PaperUi.divider(context))
                body.addView(
                    PaperUi.statLine(context, "Invaded before", "$previous time${if (previous == 1) "" else "s"}")
                )
                body.addView(
                    PaperUi.statLine(context, "Chance they revolt", "${(odds * 100).toInt()}%")
                )
                body.addView(
                    PaperUi.note(
                        context,
                        "Going back again risks the population rising. A revolt loses you the " +
                            "province and makes an enemy for good.",
                    )
                )
            }

            body.addView(PaperUi.spacer(context, 4f))
            body.addView(PaperUi.note(context, "You can order your squads around while it runs. Losing costs XP."))
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Go", colour = PencilStyle.RED_PENCIL) {
                        closeSheet()
                        launchInvasion(country, total, weapon)
                    },
                    PaperUi.button(context, "Not yet") { closeSheet() },
                )
            )
        }
    }

    /**
     * How badly [countryId] is still burning, 0 to 1.
     *
     * One reading, used by every screen that shows the place: the spy view, the invasion, and
     * watching somebody else's battle there. The user's requirement was that a bombed country still
     * looks bombed when you go in, and the only way to be sure of that is for there to be exactly
     * one answer to the question.
     */
    private fun scorchOf(countryId: String): Float =
        (MinigameStore.strikes()[countryId]?.scorch(System.currentTimeMillis()) ?: 0.0).toFloat()

    /** The base of [country] as it stands today, ruins and all. See [WorldMap.scorchedBase]. */
    private fun currentBaseOf(country: WorldMap.Country, idOverride: String? = null): PaperBase =
        WorldMap.scorchedBase(
            if (idOverride != null) country.copy(id = idOverride) else country,
            MinigameStore.strikes()[country.id],
            System.currentTimeMillis(),
        )

    private fun launchInvasion(
        country: WorldMap.Country,
        soldiers: Int,
        weapon: WeaponCatalog.Weapon,
    ) {
        // The wreck, not the country it was. Buildings you flattened are still flattened, which is
        // both what it has to look like and what it has to fight like: Battle.simulate takes its
        // targets from PaperBase.standing, and a ruin is not standing.
        val defender = if (country.owner == WorldMap.Owner.AI) {
            currentBaseOf(country)
        } else {
            // A peer's base is not on this device. It is reconstructed from what they announced,
            // which is a fair approximation and — crucially — the same approximation on both ends,
            // so the battle they watch is the battle you fought.
            currentBaseOf(country, idOverride = "peer:${country.id}")
        }

        val ticket = WorldMap.BattleTicket(
            id = "b-${System.currentTimeMillis()}",
            attackerId = WorldMap.PLAYER_ID,
            defenderId = country.id,
            seed = Rng.seedOf(country.id, System.currentTimeMillis()),
            startedAt = System.currentTimeMillis(),
            attackerLevel = base.level,
            attackerSoldiers = soldiers,
            attackerWeaponId = weapon.id,
            // The tanks, aircraft and guns that go in with them. See PaperBase.supportWeapons.
            attackerSupportIds = base.supportWeapons().map { it.id },
        )
        MinigameMesh.announceBattle(ticket)
        MinigameStore.setRelation(country.id, WorldMap.Relation.HOSTILE)
        // A country you have just gone through is visibly gone through, for a couple of hours.
        MinigameStore.recordStrike(
            country.id,
            WorldMap.Strike(WeaponCatalog.Wmd.NUCLEAR, System.currentTimeMillis(), fromInvasion = true),
        )

        showBattle(
            defender = defender,
            ticket = ticket,
            title = "Invading ${country.name}",
            youAreAttacking = true,
        ) { result ->
            var next = base.recordAttack(result.won).addXp(result.xpForAttacker)
            // Only your own soldiers can be lost here: an allied or tributary contingent is not
            // part of the standing army this base trains back, so charging its casualties to your
            // own count would quietly delete soldiers you never had.
            val ownShare = if (soldiers > 0) base.soldiers.toDouble() / soldiers else 1.0
            next = next.loseSoldiers((result.attackersLost * ownShare).toInt().coerceAtMost(base.soldiers))

            // Taking the town hall takes the country. Two stars is the threshold rather than one,
            // because a raid that burned half the farms and went home has not conquered anything —
            // and the design's list of spoils, an army and a population included, only makes sense
            // for something that was actually taken.
            val previous = MinigameStore.invasionsOf(country.id)
            MinigameStore.recordInvasion(country.id, result.won)

            // Going back to a country you have already invaded risks its people rising. One percent
            // the second time, and steeply worse after that -- so a neighbour is a resource that
            // can be exhausted rather than an infinite one.
            val revolted = WorldMap.rollRevolt(country.id, previous, worldSeed)

            val conquered = result.won && result.townHallDown && !revolted
            if (conquered && country.owner == WorldMap.Owner.AI) {
                // The same rule in the other direction: an army that has not been bound by law
                // kills the people it is conquering, and the province arrives with fewer of them
                // and pays less tax forever after.
                val theirRate = Ideology.civilianDeathRate(WorldMap.aiWarConduct(country))
                val ourRate = Ideology.civilianDeathRate(base.enactedLaws)
                val share = result.destructionPercent / 100.0
                val killed = (country.population * (ourRate + theirRate * 0.5) * share)
                    .toInt().coerceIn(0, country.population)
                if (killed > 0) toast("$killed civilians died in the taking of ${country.name}.")
                next = next.annex(WorldMap.spoilsOf(country, System.currentTimeMillis(), killed))
            }
            if (revolted) {
                // The province is lost and the country is an enemy for good.
                next = next.releaseAnnexed(country.id)
                MinigameStore.setRelation(country.id, WorldMap.Relation.HOSTILE)
                MinigameStore.clearGrudge(country.id)
            }
            commit(next)
            showBattleResult(
                country.name, result, attacking = true,
                conquered = conquered, revolted = revolted,
                revoltChance = WorldMap.revoltChance(previous),
            )
        }
    }

    /** Somebody else's battle, recomputed from the ticket rather than streamed. */
    private fun watchBattle(ticket: WorldMap.BattleTicket) {
        // The player's own country is not an AI country and must not be regenerated as one:
        // watching a raid on your own base has to show YOUR base, with your buildings in it.
        val defender = if (ticket.defenderId == WorldMap.PLAYER_ID) {
            base
        } else {
            allWorld().firstOrNull { it.id == ticket.defenderId }?.let { currentBaseOf(it) }
        } ?: run {
            toast("That country is not on your map any more.")
            return
        }

        val title = if (ticket.defenderId == WorldMap.PLAYER_ID) {
            val attacker = allWorld().firstOrNull { it.id == ticket.attackerId }
            "${attacker?.name ?: "Somebody"} is attacking you"
        } else {
            "Watching"
        }
        showBattle(defender, ticket, title, youAreAttacking = false) { }
    }

    /**
     * An AI country raiding the player.
     *
     * Simulated headlessly: the frames are thrown away and only the result is kept. A raid that
     * happens while you are not looking has to leave evidence — damage, a line in the record, an
     * XP change — and does not have to be watchable, because nobody was there.
     */
    private fun runAiRaid(target: PaperBase, seedOffset: Long): PaperBase {
        val world = WorldMap.generateAi(worldSeed, target.level, count = 24).map { country ->
            country.copy(relation = MinigameStore.relations()[country.id] ?: WorldMap.Relation.NEUTRAL)
        }
        // Who comes is not random: a country the player attacked and failed against is far more
        // likely to return the visit, and an ally never does. See WorldMap.aggressionToward.
        val attacker = WorldMap.pickRaider(
            candidates = world,
            failedInvasions = MinigameStore.failedInvasions(),
            playerLevel = target.level,
            seed = Rng.seedOf("raider", worldSeed, seedOffset),
        ) ?: return target

        val (_, result) = Battle.simulate(
            defender = target,
            attackerLevel = attacker.level,
            attackerSoldiers = (attacker.soldiers * 0.6).toInt().coerceAtLeast(1),
            attackerWeaponId = WeaponCatalog.unlockedAt(attacker.level).maxByOrNull { it.threat }?.id
                ?: WeaponCatalog.STARTER.id,
            orders = emptyList(),
            seed = Rng.seedOf("raid", attacker.id, seedOffset),
            attackerSupportIds = WorldMap.aiDetachments(attacker.level),
        ).let { it.frames to it.result }

        pendingRaidReport = attacker.name to result
        // Kept so the player can watch the raid on THEIR base rather than a replay of something
        // else. The ticket names the player as the defender, which is what tells every other part
        // of this view to simulate against the real base instead of regenerating an AI one.
        pendingRaidTicket = WorldMap.BattleTicket(
            id = "raid-${seedOffset}",
            attackerId = attacker.id,
            defenderId = WorldMap.PLAYER_ID,
            seed = Rng.seedOf("raid", attacker.id, seedOffset),
            startedAt = System.currentTimeMillis(),
            attackerLevel = attacker.level,
            attackerSoldiers = (attacker.soldiers * 0.6).toInt().coerceAtLeast(1),
            attackerWeaponId = WeaponCatalog.unlockedAt(attacker.level).maxByOrNull { it.threat }?.id
                ?: WeaponCatalog.STARTER.id,
            attackerSupportIds = WorldMap.aiDetachments(attacker.level),
        )
        // A country that has come for you is hostile whether it won or not.
        MinigameStore.setRelation(attacker.id, WorldMap.Relation.HOSTILE)

        // The people who did not get out. How many depends on what the attacker has legislated
        // about the conduct of war and on what the player has — a country that has written nothing
        // down loses people to its own garrison fighting in its own streets. See
        // PaperBase.civilianCasualties.
        val share = result.destructionPercent / 100.0
        val killed = target.civilianCasualties(WorldMap.aiWarConduct(attacker), share)
        if (killed > 0) pendingCivilianLoss = killed

        return target
            .applyDamage(result.buildingDamage)
            .loseCivilians(killed)
            .recordDefence(!result.won)
            .addXp(result.xpForDefender)
            // Every builder starts fixing the damage the moment the attackers are gone.
            .postBattleRepair()
    }

    /** Civilians killed in the raid the player has not been told about yet. */
    private var pendingCivilianLoss: Int = 0

    private var pendingRaidReport: Pair<String, Battle.Result>? = null

    /** The raid the player may watch, against their own base. See [runAiRaid]. */
    private var pendingRaidTicket: WorldMap.BattleTicket? = null

    private fun <T> List<T>.randomOrNull(rng: Rng): T? = if (isEmpty()) null else this[rng.nextInt(size)]

    // -- Battle screen --------------------------------------------------------

    private fun showBattle(
        defender: PaperBase,
        ticket: WorldMap.BattleTicket,
        title: String,
        youAreAttacking: Boolean,
        onDone: (Battle.Result) -> Unit,
    ) {
        overlay?.let { removeView(it) }

        val playback = BattlePlaybackView(
            context = context,
            defender = defender,
            ticket = ticket,
            youCanGiveOrders = youAreAttacking,
            onFinished = { result ->
                onDone(result)
            },
            onExit = { closeOverlay() },
            title = title,
        ).also {
            it.applyTopInset(topInset)
            // The ground is still burning if it was bombed. Same reading as the spy screen.
            it.scorch = scorchOf(ticket.defenderId)
        }
        battleView = playback
        overlay = playback
        addView(playback, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showBattleResult(
        name: String,
        result: Battle.Result,
        attacking: Boolean,
        conquered: Boolean = false,
        revolted: Boolean = false,
        revoltChance: Double = 0.0,
        /** Set when this was a raid on the player, so it can be watched back. */
        raidTicket: WorldMap.BattleTicket? = null,
    ) {
        openSheet = PaperUi.sheet(this, if (result.won) "You took it" else "They held") { body ->
            body.addView(PaperUi.statLine(context, "Stars", "★".repeat(result.stars).ifEmpty { "none" }))
            body.addView(PaperUi.statLine(context, "Destroyed", "${result.destructionPercent}%"))
            body.addView(PaperUi.statLine(context, "Town hall", if (result.townHallDown) "down" else "standing"))
            body.addView(PaperUi.statLine(context, "Lost", "${result.attackersLost} of ${result.attackersSent}"))
            val xp = if (attacking) result.xpForAttacker else result.xpForDefender
            body.addView(PaperUi.statLine(context, "XP", if (xp >= 0) "+${PaperUi.shortNumber(xp)}" else "−${PaperUi.shortNumber(-xp)}"))
            body.addView(PaperUi.divider(context))
            if (revolted) {
                body.addView(PaperUi.body(context, "$name has risen against you."))
                body.addView(
                    PaperUi.note(
                        context,
                        "You had invaded it before, and its people had had enough. It is lost, and " +
                            "it will not forget.",
                    )
                )
            } else if (conquered) {
                val province = base.annexed.lastOrNull()
                body.addView(PaperUi.body(context, "$name is yours."))
                if (province != null) {
                    body.addView(PaperUi.statLine(context, "Soldiers gained", province.soldiers.toString()))
                    body.addView(PaperUi.statLine(context, "Civilians gained", province.civilians.toString()))
                    body.addView(PaperUi.statLine(context, "Treasury seized", PaperUi.shortNumber(province.treasury)))
                    body.addView(PaperUi.statLine(context, "Defences gained", province.defences.toInt().toString()))
                    body.addView(
                        PaperUi.note(
                            context,
                            "Newly taken, so it hands over little at first. It settles over about " +
                                "a day and then pays in full.",
                        )
                    )
                }
            } else {
                body.addView(PaperUi.note(context, "Against $name."))
            }
            if (raidTicket != null) {
                body.addView(
                    PaperUi.buttonRow(
                        context,
                        PaperUi.button(context, "Watch it", colour = PencilStyle.RED_PENCIL) {
                            closeSheet()
                            watchBattle(raidTicket)
                        },
                        PaperUi.button(context, "Back to the base") {
                            closeSheet()
                            closeOverlay()
                        },
                    )
                )
            } else {
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Back to the base") {
                    closeSheet()
                    closeOverlay()
                }))
            }
        }
    }

    // -- Inbox ----------------------------------------------------------------

    /**
     * The inbox: alliance requests, each with a tick and a cross.
     *
     * Drawn buttons rather than checkboxes, as the design asks — a checkbox implies a form you
     * submit, and these take effect the moment they are tapped.
     */
    private fun showInbox() {
        closeSheet()
        val requests = MinigameStore.inbox().reversed()
        openSheet = PaperUi.sheet(this, "Inbox") { body ->
            if (requests.isEmpty()) {
                body.addView(PaperUi.body(context, "Nothing yet."))
                body.addView(
                    PaperUi.note(
                        context,
                        if (MinigameMesh.isAvailable()) "Alliance requests from players on the mesh arrive here."
                        else "Join a Prism Meshnet and other players can write to you here.",
                    )
                )
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }

            requests.forEach { request ->
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, PaperUi.dp(context, 8f), 0, PaperUi.dp(context, 8f))
                        alpha = if (request.answered) 0.5f else 1f

                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, "${request.fromName} asks for an alliance"))
                                addView(
                                    PaperUi.note(
                                        context,
                                        "level ${request.fromLevel}" +
                                            if (request.answered) {
                                                if (request.accepted) " · you accepted" else " · you refused"
                                            } else "",
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )

                        if (!request.answered) {
                            addView(
                                PaperUi.GlyphButton(context, PaperUi.Glyph.TICK, PencilStyle.GREEN_PENCIL).apply {
                                    setOnClickListener { answer(request, accepted = true) }
                                },
                                LinearLayout.LayoutParams(PaperUi.dp(context, 42f), PaperUi.dp(context, 42f)),
                            )
                            addView(
                                PaperUi.GlyphButton(context, PaperUi.Glyph.CROSS, PencilStyle.RED_PENCIL).apply {
                                    setOnClickListener { answer(request, accepted = false) }
                                },
                                LinearLayout.LayoutParams(PaperUi.dp(context, 42f), PaperUi.dp(context, 42f)).apply {
                                    leftMargin = PaperUi.dp(context, 4f)
                                },
                            )
                        }
                    }
                )
            }
            body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
        }
    }

    private fun answer(request: WorldMap.AllianceRequest, accepted: Boolean) {
        val updated = MinigameStore.inbox().map {
            if (it.id == request.id) it.copy(answered = true, accepted = accepted) else it
        }
        MinigameStore.saveInbox(updated)
        MinigameStore.setRelation(
            request.fromCountryId,
            if (accepted) WorldMap.Relation.ALLIED else WorldMap.Relation.REFUSED,
        )
        MinigameMesh.replyToAlliance(request.fromCountryId, accepted)
        rebuildWorld()
        mapView?.relations = MinigameStore.relations()
        mapView?.conquered = conqueredByPlayer()
        mapView?.strikes = MinigameStore.strikes()
        mapView?.invalidate()
        showInbox()
    }

    // -- Zooming into a country ----------------------------------------------

    /** Double-tapping a country on the map shows its base, drawn the same way yours is. */
    /**
     * Asks before firing, because this is not a move that can be taken back.
     *
     * A strike is not an invasion: nothing is gained. The country is not annexed, no army is
     * absorbed and no XP is taken -- it is simply reduced, and it will be back. What it buys is an
     * easier invasion afterwards, and a permanently hostile neighbour.
     */
    private fun confirmStrike(
        country: WorldMap.Country,
        kind: WeaponCatalog.Wmd,
        weapon: WeaponCatalog.Weapon,
    ) {
        val cost = (country.xp * kind.xpCostFactor).toLong().coerceAtLeast(500L)
        closeSheet()
        openSheet = PaperUi.sheet(this, "${kind.verb} ${country.name}?", seed = 5150) { body ->
            body.addView(PaperUi.body(context, "Delivered by ${weapon.name}."))
            body.addView(
                PaperUi.note(
                    context,
                    "It takes about ${(kind.destruction * 100).toInt()}% of the country off the map " +
                        "and leaves it burning for ${kind.burnHours.toInt()} hours. You gain nothing " +
                        "from it — no land, no army, no XP. It will rebuild, and it will not forget.",
                )
            )
            body.addView(PaperUi.divider(context))
            body.addView(PaperUi.statLine(context, "Cost", "${PaperUi.shortNumber(cost)} XP"))
            body.addView(PaperUi.statLine(context, "You have", PaperUi.shortNumber(base.xp)))

            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Fire", colour = PencilStyle.RED_PENCIL, filled = true) {
                        if (base.xp < cost) {
                            toast("Not enough XP to build and deliver one.")
                        } else {
                            closeSheet()
                            fireStrike(country, kind, cost)
                        }
                    },
                    PaperUi.button(context, "Stand down") { closeSheet() },
                )
            )
        }
    }

    private fun fireStrike(country: WorldMap.Country, kind: WeaponCatalog.Wmd, cost: Long) {
        commit(base.addXp(-cost))
        MinigameStore.recordStrike(
            country.id,
            WorldMap.Strike(kind, System.currentTimeMillis(), fromId = WorldMap.PLAYER_ID),
        )
        MinigameMesh.announceStrike(country.id, kind, WorldMap.PLAYER_ID)
        // Nobody forgives this.
        MinigameStore.setRelation(country.id, WorldMap.Relation.HOSTILE)
        // The heaviest thing in the armoury, played at full volume. There is no separate WMD
        // sound; the biggest warhead the player owns already sounds like one.
        WeaponCatalog.wmdsIn(base.researched)[kind]?.let { PaperAudio.fire(it, volume = 1f) }
        mapView?.strikes = MinigameStore.strikes()
        mapView?.relations = MinigameStore.relations()
        mapView?.invalidate()
        refreshHeader()
        toast("${kind.label} strike on ${country.name}.")
    }

    private fun zoomInto(country: WorldMap.Country) {
        MinigameMesh.battleFor(country.id)?.let {
            watchBattle(it)
            return
        }

        // Going to look at a place you hit shows you what you did to it: the same ruins, the same
        // fires, the same smoke you will be walking into if you invade.
        val theirBase = if (country.owner == WorldMap.Owner.PLAYER) base else currentBaseOf(country)
        overlay?.let { removeView(it) }

        val screen = FrameLayout(context)
        val view = PaperBaseView(
            context,
            onTapBuilding = { building ->
                building.type?.let { toast("${it.name} — ${building.hitPoints} hp") }
            },
            onTapEmpty = { _, _ -> },
        ).apply {
            base = theirBase
            scorch = scorchOf(country.id)
        }
        screen.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, topInset + p, p, p)
            addView(
                PaperUi.GlyphButton(context, PaperUi.Glyph.BACK).apply { setOnClickListener { closeOverlay(); showWorldMap() } },
                LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
            )
            addView(PaperUi.heading(context, country.name).apply { setPadding(PaperUi.dp(context, 8f), 0, 0, 0) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (country.owner != WorldMap.Owner.PLAYER) {
                addView(PaperUi.button(context, "Invade", colour = PencilStyle.RED_PENCIL) { planInvasion(country) })
            }
        }
        screen.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        overlay = screen
        addView(screen, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // -- Odds and ends --------------------------------------------------------

    /** Shows the report for a raid that happened while the player was away, once. */
    fun showPendingRaidReport() {
        val (name, result) = pendingRaidReport ?: return
        pendingRaidReport = null
        val killed = pendingCivilianLoss
        pendingCivilianLoss = 0
        if (killed > 0) {
            handler.postDelayed({
                if (running) {
                    toast(
                        if (base.sparesCivilians) "$killed civilians killed. They did not respect the rules of war."
                        else "$killed civilians killed. You have not enacted the Rules of War."
                    )
                }
            }, 1_200)
        }
        val ticket = pendingRaidTicket
        showBattleResult(name, result, attacking = false, raidTicket = ticket)
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Lets the page's back gesture close whatever is on top. Returns true if it consumed it. */
    fun onBack(): Boolean {
        if (openSheet != null) {
            closeSheet()
            return true
        }
        if (overlay != null) {
            // A forced live raid has no way out until it is over -- see BattlePlaybackView.forced.
            if ((battleView as? BattlePlaybackView)?.forced == true) return true
            closeOverlay()
            return true
        }
        if (baseView.placing != null) {
            cancelPlacement()
            return true
        }
        return false
    }
}
