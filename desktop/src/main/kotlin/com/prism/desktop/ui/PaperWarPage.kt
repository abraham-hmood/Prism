package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.minigames.AnnexedCountry
import com.prism.launcher.minigames.Battle
import com.prism.launcher.minigames.BuildingCatalog
import com.prism.launcher.minigames.Era
import com.prism.launcher.minigames.Ideology
import com.prism.launcher.minigames.MinigameStore
import com.prism.launcher.minigames.PaperBase
import com.prism.launcher.minigames.PlacedBuilding
import com.prism.launcher.minigames.WeaponCatalog
import com.prism.launcher.minigames.WorldMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paper War. PHASE 95.
 *
 * ## What is engine and what is this file
 *
 * `PaperBase` is the economy -- costs, caps, build timers, taxes, population, laws. `WorldMap`
 * generates the AI countries and decides diplomacy and spoils. `Battle.simulate` runs a whole raid
 * and hands back every frame. `BuildingCatalog`, `WeaponCatalog`, `Era` and `Ideology` are the
 * content. All of that is in `:core`, tested there, and shared with the phone -- a save written here
 * loads there and the reverse, because `MinigameStore` is the same serialiser reading the same keys.
 *
 * This file is four views over it: the base, the world, a battle playing back, and the provinces.
 *
 * ## The pencil aesthetic, re-expressed rather than ported
 *
 * Android's `PencilStyle` is 1,220 lines of `Paint` and `Path` work that makes everything look
 * hand-drawn on graph paper. Reproducing it line for line in Compose would be a week and would not
 * look better than the thing it imitates; what carries the look is the three decisions underneath it
 * -- a paper-coloured ground with a faint grid, everything outlined in graphite rather than filled,
 * and strokes that wobble instead of being exactly straight. Those are here. [sketchRect] is where
 * the wobble lives, and it is seeded off the thing being drawn so a building does not shimmer every
 * frame, which is the mistake that makes hand-drawn rendering look like noise.
 *
 * ## Construction advances on a clock, not on a visit
 *
 * `advanceConstruction` and `collectTaxes` are both time-based, and the phone calls them on a ticker.
 * So does this: a 1 Hz loop while the page is open, plus one call on open to settle whatever happened
 * while Prism was closed. A game that only advanced while you watched it would be a different game.
 */
@Composable
fun PaperWarPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var base by remember { mutableStateOf<PaperBase?>(null) }
    var view by remember { mutableStateOf(WarView.BASE) }
    var countries by remember { mutableStateOf<List<WorldMap.Country>>(emptyList()) }
    // THE DEFENDER IS KEPT ALONGSIDE THE PLAYBACK, not read out of it. `Battle.Cast` carries the
    // building IDS but not their positions -- deliberately, since the simulation does not need them
    // after it has placed the emplacements, and a frame holds hit points index-parallel to the id
    // list. Drawing the battle does need positions, so the base that was attacked is kept.
    var raid by remember { mutableStateOf<Raid?>(null) }
    var selectedType by remember { mutableStateOf<BuildingCatalog.BuildingType?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun persist(next: PaperBase) {
        base = next
        MinigameStore.saveBase(next)
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            // Both settles, in this order, before anything is drawn. Taxes depend on the population
            // a finished building houses, so advancing construction second would pay a day's tax at
            // yesterday's population and then quietly raise the cap.
            var it = MinigameStore.loadBase()
            it = it.advanceConstruction(System.currentTimeMillis())
            it = it.advanceLaw(System.currentTimeMillis())
            it = it.collectTaxes(System.currentTimeMillis())
            MinigameStore.saveBase(it)
            it
        }
        base = loaded
        countries = withContext(Dispatchers.Default) {
            runCatching {
                WorldMap.generateAi(MinigameStore.worldSeed(), loaded.level)
                    .map { country ->
                        // The stored relation wins over the generated default: diplomacy is the
                        // player's history with that country, not a property of the world seed.
                        country.copy(
                            relation = MinigameStore.relations()[country.id] ?: country.relation,
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }

    // The clock. 1 Hz is enough: build timers are tens of seconds and tax is per minute, so a faster
    // tick would recompute the same numbers and a slower one would make a finished building look stuck.
    LaunchedEffect(base != null) {
        while (isActive && base != null) {
            delay(1000)
            val current = base ?: return@LaunchedEffect
            val now = System.currentTimeMillis()
            var next = current.advanceConstruction(now)
            next = next.advanceLaw(now)
            next = next.collectTaxes(now)
            if (next != current) {
                base = next
                // Written every tick rather than on leaving the page: a desktop window is closed by
                // the window manager, which gives no chance to save, and losing a minute of taxes to
                // that is exactly the kind of thing that makes a game feel untrustworthy.
                MinigameStore.saveBase(next)
                MinigameStore.setLastTick(now)
            }
        }
    }

    val current = base
    PageScaffold(
        "Paper War",
        current?.let {
            it.name + " — level " + it.level + " · " + it.age.label + " · " + it.xp + " XP"
        } ?: "Loading the campaign",
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WarView.entries.forEach { tab ->
                    OutlinedButton(
                        onClick = { view = tab },
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            tab.label,
                            fontSize = 12.sp,
                            color = if (tab == view) colors.accent else colors.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (current == null) {
                Text("Reading the save…", fontSize = 13.sp, color = colors.muted)
                return@Column
            }

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when (view) {
                WarView.BASE -> BaseView(
                    base = current,
                    selectedType = selectedType,
                    onSelectType = { selectedType = it },
                    onPlace = { type, x, y ->
                        val placed = current.place(type, x, y, System.currentTimeMillis())
                        if (placed === current) {
                            note = describeRefusal(current, type)
                        } else {
                            note = "Started " + type.name + "."
                            persist(placed)
                        }
                    },
                    onExpand = {
                        if (!current.canExpandPlot()) {
                            note = "The plot can be expanded again at level " +
                                ((current.plotExpansions + 1) * 10) + "."
                        } else if (current.xp < current.expandPlotCost) {
                            note = "That costs " + current.expandPlotCost + " XP."
                        } else {
                            persist(current.expandPlot())
                            note = "The plot is bigger."
                        }
                    },
                )

                WarView.WORLD -> WorldView(
                    base = current,
                    countries = countries,
                    busy = busy,
                    onAttack = { country ->
                        busy = true
                        note = "Marching on " + country.name + "…"
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                runCatching { fightRaid(current, country) }.getOrNull()
                            }
                            busy = false
                            if (result == null) {
                                note = "The raid could not be simulated."
                                return@launch
                            }
                            raid = result
                            view = WarView.BATTLE

                            // Recorded straight away rather than when the playback ends. Closing the
                            // window mid-battle must not undo a battle that was already fought --
                            // the simulation is deterministic and already finished.
                            MinigameStore.recordInvasion(country.id, result.playback.result.won)
                            var next = current.copy(
                                xp = current.xp + result.playback.result.xpForAttacker,
                                peakXp = maxOf(
                                    current.peakXp,
                                    current.xp + result.playback.result.xpForAttacker,
                                ),
                                soldiers = (current.soldiers - result.playback.result.attackersLost)
                                    .coerceAtLeast(0),
                                battlesWon = current.battlesWon + (if (result.playback.result.won) 1 else 0),
                                battlesLost = current.battlesLost + (if (result.playback.result.won) 0 else 1),
                            )
                            if (result.playback.result.won) {
                                next = next.copy(
                                    annexed = next.annexed + WorldMap.spoilsOf(
                                        country,
                                        System.currentTimeMillis(),
                                        killed = result.playback.result.attackersLost,
                                    ),
                                )
                            }
                            // HOSTILE EITHER WAY. There is no ANNEXED relation and there should not
                            // be: annexation is recorded by the province appearing in
                            // PaperBase.annexed, and Relation is the diplomatic state, which after
                            // an invasion is hostile whether it succeeded or not. Two places storing
                            // "I own this" is one place too many.
                            MinigameStore.setRelation(country.id, WorldMap.Relation.HOSTILE)
                            countries = countries.map {
                                if (it.id == country.id) {
                                    it.copy(relation = WorldMap.Relation.HOSTILE)
                                } else it
                            }
                            persist(next)
                        }
                    },
                    onAsk = { country ->
                        val attempts = MinigameStore.allianceAttempts(country.id)
                        MinigameStore.recordAllianceAttempt(country.id)
                        val agreed = WorldMap.askAi(country, attempts)
                        val relation =
                            if (agreed) WorldMap.Relation.ALLIED else WorldMap.Relation.REFUSED
                        MinigameStore.setRelation(country.id, relation)
                        countries = countries.map {
                            if (it.id == country.id) it.copy(relation = relation) else it
                        }
                        note = if (agreed) {
                            country.name + " accepts the alliance."
                        } else {
                            country.name + " refuses. Asking again gets harder each time."
                        }
                    },
                )

                WarView.BATTLE -> BattleView(
                    raid = raid,
                    onBack = { view = WarView.WORLD },
                )

                WarView.PROVINCES -> ProvincesView(current)

                WarView.LAWS -> LawsView(
                    base = current,
                    onEnact = { law ->
                        if (!current.canEnact(law)) {
                            note = "That law is not available yet."
                        } else if (current.xp < current.lawCost(law)) {
                            note = "Enacting that costs " + current.lawCost(law) + " XP."
                        } else {
                            persist(current.beginLaw(law, System.currentTimeMillis()))
                            note = "Drafting " + law.name + "."
                        }
                    },
                )
            }
        }
    }
}

private enum class WarView(val label: String) {
    BASE("Base"), WORLD("World"), BATTLE("Battle"), PROVINCES("Provinces"), LAWS("Laws")
}

/** Why a placement was refused, read off the engine's own answer rather than guessed. */
private fun describeRefusal(base: PaperBase, type: BuildingCatalog.BuildingType): String =
    when (val verdict = base.canPlace(type)) {
        is PaperBase.Placement.Locked -> type.name + " unlocks at level " + verdict.atLevel + "."
        is PaperBase.Placement.AtCap ->
            "You already have the most " + type.name + " this level allows (" + verdict.cap + ")."
        is PaperBase.Placement.NotEnoughXp ->
            "That costs " + verdict.short + " XP more than you have."
        else ->
            "Nothing fits there — a " + type.footprint + "x" + type.footprint +
                " building needs that much clear ground inside the plot."
    }

/**
 * Runs the raid.
 *
 * The army is read off the base rather than chosen here: soldiers alive at home, the researched
 * infantry weapon, the support detachments and the military bonus from the standing barracks. That
 * is the same army the phone sends, which is what makes a battle recomputed from a ticket on another
 * device come out identical.
 */
private fun fightRaid(base: PaperBase, country: WorldMap.Country): Raid {
    val defender = WorldMap.aiBase(country)
    val weapon = base.infantryWeaponId
        ?: WeaponCatalog.bestResearched(base.researched, preferRanged = true).id
    return Raid(
        title = base.name + " raids " + country.name,
        defender = defender,
        playback = Battle.simulate(
            defender = defender,
            attackerLevel = base.level,
            attackerSoldiers = base.soldiers.coerceAtLeast(1),
            attackerWeaponId = weapon,
            orders = emptyList(),
            seed = System.nanoTime(),
            attackerSupportIds = base.supportWeaponIds.orEmpty(),
            attackerMilitaryBonus = base.militaryBonus,
        ),
    )
}

/** A fought battle and the base it was fought over. See the note where this is held. */
private data class Raid(
    val title: String,
    val defender: PaperBase,
    val playback: Battle.Playback,
)

// ── The base ────────────────────────────────────────────────────────────────

@Composable
private fun BaseView(
    base: PaperBase,
    selectedType: BuildingCatalog.BuildingType?,
    onSelectType: (BuildingCatalog.BuildingType?) -> Unit,
    onPlace: (BuildingCatalog.BuildingType, Int, Int) -> Unit,
    onExpand: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val now = System.currentTimeMillis()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {

            // ── The plot ───────────────────────────────────────────────────
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .pointerInput(base.plotSize, selectedType) {
                            detectTapsOnPlot(base.plotSize) { x, y ->
                                selectedType?.let { onPlace(it, x, y) }
                            }
                        },
                ) {
                    drawPlot(base, now, colors.accent)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    selectedType?.let {
                        "Click the plot to place a " + it.name + " (" +
                            base.effectiveBuildCost(it) + " XP, " +
                            it.footprint + "x" + it.footprint + ")"
                    } ?: "Pick a building on the right, then click the plot.",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }

            Spacer(Modifier.width(14.dp))

            // ── State and the build palette ────────────────────────────────
            Column(Modifier.width(320.dp)) {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Level " + base.level + " — " + base.age.label,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { base.levelProgress.toFloat().coerceIn(0f, 1f) },
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        StatLine("XP", base.xp.toString() + " (peak " + base.peakXp + ")")
                        StatLine(
                            "Soldiers",
                            base.soldiers.toString() + " of " + base.armyCapacity,
                        )
                        StatLine(
                            "Civilians",
                            base.civilians.toString() + " of " + base.civilianCapacity +
                                " · " + base.families + " families",
                        )
                        StatLine(
                            "Food",
                            String.format("%.0f", base.foodProduction) + " of " +
                                String.format("%.0f", base.foodDemand) + " needed",
                        )
                        StatLine(
                            "Builders",
                            base.freeBuilders.toString() + " free of " + base.builders,
                        )
                        StatLine(
                            "Tax",
                            String.format("%.1f", base.civilianTaxPerMinute()) + " XP a minute",
                        )
                        StatLine(
                            "Record",
                            base.battlesWon.toString() + "W / " + base.battlesLost + "L · " +
                                base.raidsRepelled + " raids repelled",
                        )
                        StatLine("Ideology", base.ideology)
                        if (!base.hasTownHall) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No town hall is standing. Rebuild it — everything else is " +
                                    "capped against it.",
                                fontSize = 11.sp,
                                color = Color(0xFFFFB4B4),
                                lineHeight = 16.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onExpand) {
                            Text(
                                "Expand the plot (" + base.expandPlotCost + " XP)",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (base.underConstruction.isNotEmpty()) {
                    SectionHeader("under construction")
                    Card {
                        Column {
                            base.underConstruction.forEachIndexed { i, b ->
                                if (i > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().padding(11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(b.type?.name ?: b.typeId, fontSize = 12.sp)
                                        LinearProgressIndicator(
                                            progress = {
                                                b.buildProgress(now).toFloat().coerceIn(0f, 1f)
                                            },
                                            color = colors.accent,
                                            modifier = Modifier.fillMaxWidth().height(2.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        (((b.finishesAt - now) / 1000).coerceAtLeast(0)).toString() + "s",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.faint,
                                    )
                                }
                            }
                        }
                    }
                }

                SectionHeader("build")
                Card {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        val unlocked = remember(base.level) {
                            BuildingCatalog.unlockedAt(base.level)
                        }
                        BuildingCatalog.Category.entries.forEach { category ->
                            val types = unlocked.filter { it.category == category }
                            if (types.isEmpty()) return@forEach
                            Text(
                                category.label.lowercase(),
                                fontSize = 10.sp,
                                color = colors.accent,
                                modifier = Modifier.padding(start = 12.dp, top = 9.dp, bottom = 2.dp),
                            )
                            types.forEach { type ->
                                val owned = base.countOwned(type.id)
                                val cap = type.capAtLevel(base.level)
                                val affordable = base.xp >= base.effectiveBuildCost(type)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickableRow {
                                            onSelectType(if (selectedType == type) null else type)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        type.name,
                                        fontSize = 12.sp,
                                        color = when {
                                            selectedType == type -> colors.accent
                                            !affordable || owned >= cap -> colors.faint
                                            else -> colors.onSurface
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        owned.toString() + "/" + cap,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.faint,
                                        modifier = Modifier.width(38.dp),
                                    )
                                    Text(
                                        base.effectiveBuildCost(type).toString(),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (affordable) colors.muted else Color(0xFF8A5A5A),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionFooter(
            "The economy, the caps, the build timers and the battle are all :core and all tested " +
                "there — this view places buildings and draws them. The save is the same format " +
                "the phone writes, so a campaign moves between the two."
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = colors.faint, modifier = Modifier.width(78.dp))
        Text(value, fontSize = 11.sp, color = colors.muted, lineHeight = 15.sp)
    }
}

/** Maps a click to a plot cell, which is the only geometry the base view needs from the pointer. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapsOnPlot(
    plotSize: Int,
    onCell: (Int, Int) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            val change = down.changes.firstOrNull { it.pressed && it.previousPressed.not() }
                ?: continue
            val cell = size.width.toFloat() / plotSize
            val x = (change.position.x / cell).toInt().coerceIn(0, plotSize - 1)
            val y = (change.position.y / cell).toInt().coerceIn(0, plotSize - 1)
            onCell(x, y)
        }
    }
}

private fun DrawScope.drawPlot(base: PaperBase, now: Long, accent: Color) {
    val plot = base.plotSize
    val cell = size.width / plot

    // Paper, then the grid. The paper colour is what makes the graphite read as graphite -- the same
    // outlines on a dark ground look like neon.
    drawRect(color = PAPER, size = size)
    for (i in 0..plot) {
        val p = i * cell
        drawLine(GRID, Offset(p, 0f), Offset(p, size.height), strokeWidth = 1f)
        drawLine(GRID, Offset(0f, p), Offset(size.width, p), strokeWidth = 1f)
    }

    // Roads first, because they run between buildings and have to sit under them.
    base.buildings.filter { it.isRoad }.forEach { road ->
        val from = base.buildings.firstOrNull { it.id == road.connectsFrom }
        val to = base.buildings.firstOrNull { it.id == road.connectsTo }
        if (from == null || to == null) return@forEach
        drawLine(
            color = GRAPHITE.copy(alpha = 0.35f),
            // centreX/centreY are Doubles -- a building's centre is genuinely fractional for an
            // even footprint -- so each is converted before it meets a Float cell size.
            start = Offset((from.centreX() * cell).toFloat(), (from.centreY() * cell).toFloat()),
            end = Offset((to.centreX() * cell).toFloat(), (to.centreY() * cell).toFloat()),
            strokeWidth = cell * 0.22f,
            cap = StrokeCap.Round,
        )
    }

    base.buildings.filterNot { it.isRoad }.forEach { building ->
        val footprint = building.footprint
        val left = building.x * cell
        val top = building.y * cell
        val span = footprint * cell

        val progress = building.buildProgress(now)
        val fill = when {
            !building.complete -> SCAFFOLD
            building.hitPoints <= 0 -> RUBBLE
            else -> fillFor(building)
        }

        drawRect(color = fill, topLeft = Offset(left + 1f, top + 1f), size = Size(span - 2f, span - 2f))
        sketchRect(left, top, span, span, building.id)

        if (!building.complete) {
            // A dashed outline and a fill bar: scaffolding, which is what the phone draws too, and
            // it is immediately distinguishable from a finished building at a glance.
            drawRect(
                color = GRAPHITE,
                topLeft = Offset(left + 2f, top + 2f),
                size = Size(span - 4f, span - 4f),
                style = Stroke(
                    width = 1.4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                ),
            )
            drawRect(
                color = accent.copy(alpha = 0.55f),
                topLeft = Offset(left + 3f, top + span - 7f),
                size = Size(((span - 6f) * progress).toFloat().coerceAtLeast(0f), 4f),
            )
        } else if (building.damaged) {
            // Damage as a hatch rather than a tint: a tinted building at thumbnail scale just looks
            // like a different building.
            val ratio = building.hitPoints.toFloat() / building.maxHitPoints
            var y = top + span * ratio
            while (y < top + span - 2f) {
                drawLine(
                    color = Color(0x66B03030),
                    start = Offset(left + 2f, y),
                    end = Offset(left + span - 2f, y),
                    strokeWidth = 1f,
                )
                y += 4f
            }
        }

        if (building.typeId == BuildingCatalog.TOWN_HALL.id) {
            // The one building the player must be able to find instantly, because everything is
            // capped against it and a raid aims at it.
            drawCircle(
                color = accent,
                radius = cell * 0.16f,
                center = Offset(left + span / 2f, top + span / 2f),
            )
        }
    }
}

/**
 * A rectangle drawn as four slightly-wrong lines.
 *
 * SEEDED OFF [key], not off the frame. A wobble recomputed every frame makes the whole base shimmer,
 * which reads as a rendering fault rather than as a drawing; seeding it off the building's own id
 * means it wobbles the same way for as long as it stands, which is what a pencil line does.
 */
private fun DrawScope.sketchRect(left: Float, top: Float, w: Float, h: Float, key: String) {
    val seed = key.hashCode()
    fun jitter(n: Int): Float {
        val x = (seed * 31 + n * 2654435761L.toInt())
        return ((x shr 8) and 0xFF) / 255f * 2.2f - 1.1f
    }
    val corners = listOf(
        Offset(left + jitter(0), top + jitter(1)),
        Offset(left + w + jitter(2), top + jitter(3)),
        Offset(left + w + jitter(4), top + h + jitter(5)),
        Offset(left + jitter(6), top + h + jitter(7)),
    )
    for (i in corners.indices) {
        drawLine(
            color = GRAPHITE,
            start = corners[i],
            end = corners[(i + 1) % corners.size],
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
    }
}

private fun fillFor(building: PlacedBuilding): Color = when (building.type?.category) {
    BuildingCatalog.Category.MILITARY -> Color(0x33A04040)
    BuildingCatalog.Category.HOUSING -> Color(0x3340A060)
    BuildingCatalog.Category.RESOURCE -> Color(0x33A08040)
    BuildingCatalog.Category.INDUSTRY -> Color(0x33806080)
    BuildingCatalog.Category.TRADE -> Color(0x334080A0)
    BuildingCatalog.Category.CIVIC -> Color(0x336080A0)
    else -> Color(0x22000000)
}

private val PAPER = Color(0xFFF2EEE2)
private val GRID = Color(0x22506070)
private val GRAPHITE = Color(0xDD303038)
private val SCAFFOLD = Color(0x22808080)
private val RUBBLE = Color(0x55603030)

// ── The world ───────────────────────────────────────────────────────────────

@Composable
private fun WorldView(
    base: PaperBase,
    countries: List<WorldMap.Country>,
    busy: Boolean,
    onAttack: (WorldMap.Country) -> Unit,
    onAsk: (WorldMap.Country) -> Unit,
) {
    val colors = LocalPrismColors.current
    var selected by remember { mutableStateOf<WorldMap.Country?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Card {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Your army: " + base.soldiers + " soldiers, " +
                        (base.infantryWeaponId?.let { WeaponCatalog.byId(it)?.name } ?: "no weapon researched") +
                        (if (base.supportWeaponIds.isNullOrEmpty()) "" else
                            ", with " + base.supportWeaponIds!!.size + " detachment(s)"),
                    fontSize = 12.sp,
                )
                Text(
                    "Training bonus from standing military buildings: " +
                        String.format("%.2f", base.militaryBonus) + "x",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
        }

        SectionHeader("the world")
        Card {
            Column {
                if (countries.isEmpty()) {
                    Text(
                        "No countries were generated. The world is derived from a seed, so this " +
                            "means the seed could not be read.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                countries.forEachIndexed { i, country ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableRow { selected = if (selected == country) null else country }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = relationColour(country.relation),
                            shape = CircleShape,
                            modifier = Modifier.size(9.dp),
                        ) {}
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(country.name, fontSize = 13.sp)
                            Text(
                                "level " + country.level + " · " + country.age.label + " · " +
                                    country.soldiers + " soldiers · " + country.buildingCount +
                                    " buildings · strength " + country.strength,
                                fontSize = 10.sp,
                                color = colors.faint,
                            )
                            val invasions = MinigameStore.invasionsOf(country.id)
                            val failed = MinigameStore.failedInvasionsOf(country.id)
                            if (invasions > 0 || failed > 0) {
                                Text(
                                    invasions.toString() + " invasion(s), " + failed + " repelled",
                                    fontSize = 10.sp,
                                    color = Color(0xFF6E6E7A),
                                )
                            }
                        }
                        Text(
                            if (base.annexed.any { it.id == country.id }) "yours"
                            else country.relation.name.lowercase(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = relationColour(country.relation),
                        )
                    }

                    if (selected == country) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 12.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val owned = base.annexed.any { it.id == country.id }
                            Button(
                                enabled = !busy && base.soldiers > 0 && !owned,
                                onClick = { onAttack(country) },
                            ) {
                                Text(
                                    when {
                                        owned -> "Yours already"
                                        base.soldiers <= 0 -> "No soldiers"
                                        else -> "Invade"
                                    },
                                    fontSize = 12.sp,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = !busy && country.canBeAsked(),
                                onClick = { onAsk(country) },
                            ) {
                                Text(
                                    "Ask for an alliance (" +
                                        (WorldMap.allianceChance(country) * 100).toInt() + "%)",
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionFooter(
            "Countries, their armies and their diplomacy are derived from the world seed, so the " +
                "same seed is the same world on every device. Relations are stored per country " +
                "because they are your history with it, not a property of the seed."
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun relationColour(relation: WorldMap.Relation): Color = when (relation) {
    WorldMap.Relation.ALLIED -> Color(0xFF4FC978)
    WorldMap.Relation.REFUSED -> Color(0xFFE0C060)
    WorldMap.Relation.HOSTILE -> Color(0xFFE06060)
    WorldMap.Relation.NEUTRAL -> Color(0xFF6E6E7A)
}

// ── A battle, playing back ──────────────────────────────────────────────────

/**
 * The raid, frame by frame.
 *
 * ## Real time, from the playback's own rate
 *
 * `Playback.frameMillis` already accounts for decimation -- above a few hundred fighters the
 * simulation keeps every second or third tick, and showing those at one-per-frame plays a large
 * assault back at double speed. So the loop sleeps for whatever the playback says a frame is worth,
 * which is the whole reason that field exists.
 */
@Composable
private fun BattleView(raid: Raid?, onBack: () -> Unit) {
    val colors = LocalPrismColors.current
    val playback = raid?.playback
    var frameIndex by remember(playback) { mutableStateOf(0) }
    var playing by remember(playback) { mutableStateOf(true) }

    LaunchedEffect(playback, playing) {
        val current = playback ?: return@LaunchedEffect
        while (isActive && playing && frameIndex < current.frames.size - 1) {
            delay(current.frameMillis.coerceAtLeast(16))
            frameIndex++
        }
        if (frameIndex >= current.frames.size - 1) playing = false
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "← back to the world",
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.clickableRow(onBack).padding(4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(raid?.title.orEmpty(), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))

        if (raid == null || playback == null) {
            Text(
                "No battle has been fought yet. Invade a country on the World tab.",
                fontSize = 13.sp,
                color = colors.muted,
            )
            return@Column
        }

        val frame = playback.frames.getOrNull(frameIndex) ?: playback.frames.last()

        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawBattle(raid.defender, playback, frame, colors.accent)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { playing = !playing }) {
                Text(if (playing) "Pause" else "Play", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { frameIndex = 0; playing = true }) {
                Text("Replay", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { frameIndex = playback.frames.size - 1; playing = false }) {
                Text("Skip to the end", fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                String.format("%.1f", Battle.secondsOf(frame.tick)) + " s · " +
                    frame.aliveCount() + " attackers alive · " +
                    frame.buildingHp.count { it <= 0 } + " buildings down",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
            )
        }

        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (frameIndex + 1).toFloat() / playback.frames.size },
            color = colors.accent,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )

        SectionHeader("result")
        Card {
            Column(Modifier.padding(14.dp)) {
                val r = playback.result
                Text(
                    if (r.won) "Won — " + r.stars + " star(s)" else "Lost",
                    fontSize = 14.sp,
                    color = if (r.won) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                )
                Spacer(Modifier.height(6.dp))
                StatLine("Destruction", r.destructionPercent.toString() + "%")
                StatLine("Town hall", if (r.townHallDown) "down" else "standing")
                StatLine("Losses", r.attackersLost.toString() + " of " + r.attackersSent)
                StatLine("Duration", String.format("%.1f", Battle.secondsOf(r.ticks)) + " s")
                StatLine("XP", "+" + r.xpForAttacker + " to you, +" + r.xpForDefender + " to them")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun DrawScope.drawBattle(
    defender: PaperBase,
    playback: Battle.Playback,
    frame: Battle.Frame,
    accent: Color,
) {
    val cast = playback.cast
    // The field is the defender's own plot -- an expanded base is fought over on that bigger field,
    // which is what the simulation spawns attackers at the edges of.
    val field = defender.plotSize.toFloat().coerceAtLeast(1f)
    val scale = size.width / field

    drawRect(color = PAPER, size = size)
    val step = size.width / 20f
    var g = 0f
    while (g <= size.width) {
        drawLine(GRID, Offset(g, 0f), Offset(g, size.height), strokeWidth = 1f)
        drawLine(GRID, Offset(0f, g), Offset(size.width, g), strokeWidth = 1f)
        g += step
    }

    // Buildings: outlined while standing, crossed out when destroyed. Removing them instead would
    // make a flattened base look empty rather than flattened.
    //
    // Looked up BY ID from the defender, in the Cast's order. That order is the contract --
    // Frame.buildingHp is index-parallel to Cast.buildingIds -- so zipping the two by position
    // would silently mis-attribute damage the moment the base's own list order differed.
    val byId = defender.buildings.associateBy { it.id }
    cast.buildingIds.forEachIndexed { index, id ->
        val building = byId[id] ?: return@forEachIndexed
        val span = (building.footprint.coerceAtLeast(1)) * scale
        val left = building.x * scale
        val top = building.y * scale
        val standing = frame.standing(index)
        drawRect(
            color = if (standing) Color(0x22304050) else Color(0x33603030),
            topLeft = Offset(left, top),
            size = Size(span, span),
        )
        drawRect(
            color = if (standing) GRAPHITE else Color(0x99803030),
            topLeft = Offset(left, top),
            size = Size(span, span),
            style = Stroke(width = 1.4f),
        )
        if (!standing) {
            drawLine(Color(0xAA803030), Offset(left, top), Offset(left + span, top + span), 1.6f)
            drawLine(Color(0xAA803030), Offset(left + span, top), Offset(left, top + span), 1.6f)
        }
    }

    // Tracers before the fighters, so a shot does not cover the shooter.
    frame.shots.forEach { shot ->
        // Each coordinate is converted on its own: the shot's are Doubles and the scale is a Float,
        // and mixing them inside the Offset call is ambiguous to overload resolution.
        val fromX = (shot.fromX * scale).toFloat()
        val fromY = (shot.fromY * scale).toFloat()
        val toX = (shot.toX * scale).toFloat()
        val toY = (shot.toY * scale).toFloat()
        drawLine(
            color = if (shot.fromAttacker) accent.copy(alpha = 0.7f) else Color(0xAAB04040),
            start = Offset(fromX, fromY),
            end = Offset(toX, toY),
            strokeWidth = if (shot.splash > 0) 2.2f else 1f,
        )
        if (shot.splash > 0) {
            drawCircle(
                color = Color(0x44D08030),
                radius = shot.splash * scale,
                center = Offset(toX, toY),
            )
        }
    }

    // EVERY FIGHTER IN THE CAST IS AN ATTACKER. The defence is emplacements -- turrets and the
    // garrison inside buildings -- which live in the building arrays, not the fighter ones. There is
    // no attacker/defender split to colour by, and inventing one would have drawn half the invading
    // army in the defender's colour.
    for (i in frame.fighterHp.indices) {
        if (!frame.alive(i)) continue
        drawCircle(
            color = accent,
            radius = (scale * 0.9f).coerceIn(1.4f, 4f),
            center = Offset(frame.x(i) * scale, frame.y(i) * scale),
        )
    }
}

// ── Provinces ───────────────────────────────────────────────────────────────

@Composable
private fun ProvincesView(base: PaperBase) {
    val colors = LocalPrismColors.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (base.annexed.isEmpty()) {
            Card {
                Text(
                    "No provinces. A country you invade and beat becomes one, and from then on it " +
                        "is a real base of its own — its own buildings, army and research — that " +
                        "pays tribute in proportion to how loyal it has become.",
                    fontSize = 13.sp,
                    color = colors.muted,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        Card {
            Column {
                base.annexed.forEachIndexed { i, province ->
                    if (i > 0) Hairline()
                    ProvinceRow(province)
                }
            }
        }
        SectionFooter(
            "Loyalty rises from 0.2 at conquest and scales everything a province contributes: its " +
                "tribute, its soldiers and its defences when you are raided."
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProvinceRow(province: AnnexedCountry) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(province.name, fontSize = 13.sp)
            Text(
                "level " + province.level + " · " + province.soldiers + " soldiers · " +
                    province.civilians + " civilians · seized " + province.treasury + " XP",
                fontSize = 10.sp,
                color = colors.faint,
            )
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { province.loyalty.toFloat().coerceIn(0f, 1f) },
                color = colors.accent,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (province.loyalty * 100).toInt().toString() + "% loyal",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.muted,
        )
    }
}

// ── Laws ────────────────────────────────────────────────────────────────────

@Composable
private fun LawsView(base: PaperBase, onEnact: (Ideology.Law) -> Unit) {
    val colors = LocalPrismColors.current
    val now = System.currentTimeMillis()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Card {
            Column(Modifier.padding(14.dp)) {
                Text(base.ideology, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                base.lawEffects.describe().forEach {
                    Text(it, fontSize = 11.sp, color = colors.muted, lineHeight = 16.sp)
                }
                base.lawInProgress?.let { pending ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Drafting " + (Ideology.law(pending)?.name ?: pending) + " — " +
                            (((base.lawFinishesAt - now) / 1000).coerceAtLeast(0)) + "s",
                        fontSize = 11.sp,
                        color = colors.accent,
                    )
                }
                if (!base.hasUniversity) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A university is what lets you draft law. Build one on the Base tab.",
                        fontSize = 11.sp,
                        color = Color(0xFFE0C060),
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        val available = remember(base.enactedLaws, base.level) {
            Ideology.available(base.enactedLaws, base.level)
        }
        SectionHeader("available")
        Card {
            Column {
                if (available.isEmpty()) {
                    Text(
                        "Nothing new to enact at this level.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                available.forEachIndexed { i, law ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableRow { onEnact(law) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(law.name, fontSize = 13.sp)
                            Text(
                                (Ideology.groupOf(law.groupId)?.name ?: law.groupId) + " — " +
                                    law.summary,
                                fontSize = 10.sp,
                                color = colors.faint,
                                lineHeight = 15.sp,
                            )
                        }
                        Text(
                            base.lawCost(law).toString() + " XP",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (base.xp >= base.lawCost(law)) colors.muted else Color(0xFF8A5A5A),
                        )
                    }
                }
            }
        }

        SectionHeader("alignment")
        Card {
            Column(Modifier.padding(14.dp)) {
                Ideology.rank(base.enactedLaws).take(5).forEach { (stance, score) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(stance.name, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(
                            (score * 100).toInt().toString() + "%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.faint,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
