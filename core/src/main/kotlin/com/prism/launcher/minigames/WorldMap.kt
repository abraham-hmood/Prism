package com.prism.launcher.minigames

/**
 * The pencil-drawn world: who owns what, who will talk to you, and who is about to be a problem.
 *
 * ## AI countries are generated, not stored
 *
 * Every AI country is a pure function of its seed. Its name, its outline on the paper, its level,
 * its army and its whole base come out of [Rng] seeded by the country's id, which means the world
 * is the same every time it is drawn without a single byte of it being saved, and two devices on a
 * mesh see the same AI world without exchanging anything. Only what CHANGES — who you allied with,
 * who you beat, what they did back — is persisted, and that is a handful of ids.
 *
 * ## Player countries come from the mesh
 *
 * A player country is a peer that has announced itself. With no mesh, or a mesh with nobody on it,
 * the map is AI only — which the design asks for explicitly, and which is also the honest thing to
 * show rather than inventing fake opponents and calling them people.
 */
object WorldMap {

    /** The paper map is 100x60 units; countries are blobs on it. */
    const val MAP_WIDTH = 100
    const val MAP_HEIGHT = 60

    enum class Owner { PLAYER, AI, PEER }

    enum class Relation {
        /** No history. The default, and what an invade button is for. */
        NEUTRAL,

        /** They said yes, and their army fights alongside yours. */
        ALLIED,

        /** You asked and they refused. Asking again is allowed, and less likely to work. */
        REFUSED,

        /** Somebody invaded somebody. Alliance is off the table for a while. */
        HOSTILE,
    }

    /**
     * One country on the map.
     *
     * @param id stable. For an AI country it is what seeds everything; for a peer it is the mesh IP.
     * @param outline the blob, as map-space points, drawn as a wobbly pencil line.
     */
    data class Country(
        val id: String,
        val name: String,
        val owner: Owner,
        val level: Int,
        val outline: List<Pair<Int, Int>>,
        /**
         * Where the country sits in world space.
         *
         * Doubles and unbounded, because the world has no edges any more: a country may sit at
         * -8,400 as legitimately as at 30, and an integer grid would quantise the far reaches into
         * a visible lattice. See [WorldChunks].
         */
        val centreX: Double,
        val centreY: Double,
        val relation: Relation = Relation.NEUTRAL,
        val soldiers: Int = 0,
        val buildingCount: Int = 0,
        val population: Int = 0,
        val xp: Long = 0,
        val battlesWon: Int = 0,
        val battlesLost: Int = 0,
        /** Set while a battle involving this country is running, so the map can offer to watch. */
        val battleInProgress: BattleTicket? = null,
    ) {
        val age: Era.Age get() = Era.ageOf(level)

        /** A rough single number for "how hard is this", shown on the popup. */
        val strength: Long get() = xp / 4 + soldiers.toLong() * level + buildingCount.toLong() * 30

        fun canBeAsked(): Boolean = relation == Relation.NEUTRAL || relation == Relation.REFUSED
    }

    /** A battle somebody can watch, named so both ends can recompute the same one. */
    data class BattleTicket(
        val id: String,
        val attackerId: String,
        val defenderId: String,
        val seed: Long,
        val startedAt: Long,
        val attackerLevel: Int,
        val attackerSoldiers: Int,
        val attackerWeaponId: String,
        /**
         * The crewed detachments going in with them. Part of the ticket rather than looked up at
         * the far end, because a ticket has to describe the same battle to everyone who replays it
         * -- the attacker, the defender watching it happen, and a mesh spectator.
         */
        val attackerSupportIds: List<String> = emptyList(),
        /** The attacker's own [PaperBase.militaryBonus], carried the same way for the same reason. */
        val attackerMilitaryBonus: Double = 1.0,
    )

    /**
     * What an AI country of this level brings besides infantry.
     *
     * Derived rather than stored, like everything else about an AI country, and it means a modern
     * or futuristic enemy turns up with armour and air support instead of five hundred identical
     * riflemen -- which matters most when the player is watching a raid on their own base.
     */
    fun aiDetachments(level: Int): List<String> {
        val unlocked = WeaponCatalog.unlockedAt(level)
        fun best(predicate: (WeaponCatalog.Weapon) -> Boolean) =
            unlocked.filter(predicate).maxByOrNull { it.threat }?.id
        return listOfNotNull(
            best { it.weaponClass == WeaponCatalog.WeaponClass.ARMOUR },
            best { it.isRotaryWing },
            best { it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT && !it.isRotaryWing },
            best { it.weaponClass == WeaponCatalog.WeaponClass.ARTILLERY },
            best { it.weaponClass == WeaponCatalog.WeaponClass.MISSILE },
        ).take(PaperBase.MAX_DETACHMENTS)
    }

    /**
     * A country that has been hit, and is still burning.
     *
     * Kept as a time and a kind rather than as a modified country, because an AI country is a
     * formula rather than a record — there is nothing stored to modify. The damage is therefore
     * expressed as a fraction that decays: [scorch] is 1.0 the moment it lands and 0.0 once the
     * country has rebuilt, and everything the map and the popups show is scaled by it.
     */
    data class Strike(
        val kind: WeaponCatalog.Wmd,
        val at: Long,
        /** Invasions leave a mark too, briefly: a country that was just sacked is visibly sacked. */
        val fromInvasion: Boolean = false,
        /**
         * Who fired it, so the map can draw the thing crossing the world.
         *
         * A strike that simply appears on a country is a status change; a strike you watch leave
         * somewhere and arrive somewhere else is an event, and the difference is most of what makes
         * a world map feel inhabited. Null for a sacking, which has no flight, and for anything
         * recorded before this was kept.
         */
        val fromId: String? = null,
    ) {
        fun scorch(now: Long): Double {
            // Nothing burns until the thing arrives. Without this a country is on fire while the
            // missile that does it is still visibly halfway there.
            if (flight(now) != null) return 0.0
            val hours = (now - at - if (fromId != null) FLIGHT_MS else 0L) / 3_600_000.0
            val over = if (fromInvasion) 2.0 else kind.burnHours
            if (hours >= over) return 0.0
            return (1.0 - hours / over).coerceIn(0.0, 1.0)
        }

        fun isBurning(now: Long): Boolean = scorch(now) > 0.0

        /**
         * How far along its flight the warhead is, 0 at launch and 1 on impact — or null if there
         * is nothing to draw, either because nobody fired it or because it landed a while ago.
         *
         * [FLIGHT_MS] is a stage convention, not a physics claim. A real intercontinental flight is
         * half an hour and nobody is going to sit and watch that; a few seconds is long enough to
         * see where it came from, which is the only information the animation carries.
         */
        fun flight(now: Long): Double? {
            if (fromId == null || fromInvasion) return null
            val elapsed = now - at
            if (elapsed < 0 || elapsed > FLIGHT_MS) return null
            return (elapsed.toDouble() / FLIGHT_MS).coerceIn(0.0, 1.0)
        }
    }

    /**
     * What a strike does to a country's numbers while it is still burning.
     *
     * Applied on the way out of generation, so every part of the game that asks about a country —
     * the map, the popup, the base that gets simulated when you invade it — agrees about how much
     * of it is currently rubble.
     */
    /** How long a warhead is shown crossing the map. See [Strike.flight]. */
    const val FLIGHT_MS = 4_500L

    fun scorched(country: Country, strike: Strike?, now: Long): Country {
        val scorch = strike?.scorch(now) ?: 0.0
        if (scorch <= 0.0) return country
        val kept = 1.0 - scorch * (strike?.kind?.destruction ?: 0.0)
        return country.copy(
            buildingCount = (country.buildingCount * kept).toInt().coerceAtLeast(1),
            soldiers = (country.soldiers * kept).toInt(),
            population = (country.population * kept).toInt(),
        )
    }

    /** One line in the inbox. */
    data class AllianceRequest(
        val id: String,
        val fromCountryId: String,
        val fromName: String,
        val fromLevel: Int,
        val sentAt: Long,
        val answered: Boolean = false,
        val accepted: Boolean = false,
    )

    // -- Generation -----------------------------------------------------------

    private val FIRST = listOf(
        "Graphite", "Margin", "Foolscap", "Quire", "Ledger", "Vellum", "Inkwell", "Ruled",
        "Blotter", "Folio", "Octavo", "Cartridge", "Tracing", "Manila", "Onion", "Parchment",
        "Copperplate", "Chalk", "Eraser", "Sharpener", "Notch", "Spiral", "Stapled", "Dogear",
        "Watermark", "Bleed", "Gutter", "Kerning", "Serif", "Ascender", "Descender", "Baseline",
    )

    private val SECOND = listOf(
        "Reach", "Hold", "March", "Vale", "Fen", "Spire", "Cross", "Ford", "Gate", "Wold",
        "Heath", "Drift", "Cairn", "Shoal", "Hollow", "Bight", "Combe", "Scarp", "Weald", "Thorpe",
    )

    /**
     * The AI world.
     *
     * [count] countries, spread over the paper without overlapping, all generated from [worldSeed]
     * so a player's world is theirs and stays put. Levels are scattered around the player's own so
     * the map always has somebody worth attacking and somebody worth avoiding — a world entirely of
     * level-3 neighbours at level 300 is a map with nothing on it.
     */
    /**
     * The player's near neighbours.
     *
     * A convenience over [WorldChunks] rather than a separate world: it walks outward from the
     * origin taking whatever chunks hold a country, so what it returns is exactly what the map
     * draws when it is looking at the same place. Used for picking a raider and for tests.
     */
    fun generateAi(worldSeed: Long, playerLevel: Int, count: Int = 14): List<Country> {
        val out = ArrayList<Country>(count)
        var ring = 1
        while (out.size < count && ring <= 24) {
            for (cx in -ring..ring) {
                for (cy in -ring..ring) {
                    // The shell of this ring only; the inside was covered by earlier rings.
                    if (kotlin.math.abs(cx) != ring && kotlin.math.abs(cy) != ring) continue
                    WorldChunks.countryAt(worldSeed, playerLevel, cx, cy)?.let { out.add(it) }
                    if (out.size >= count) return out
                }
            }
            ring++
        }
        return out
    }

    /**
     * A wobbly closed outline, drawn the way a hand draws a country: roughly round, never round.
     *
     * Deliberately low-resolution — twelve points — because the renderer jitters each segment
     * again when it draws, and jitter on top of jitter reads as scribble rather than as pencil.
     */
    private fun blob(rng: Rng, cx: Double, cy: Double, baseR: Double = 7.0 + rng.nextDouble() * 4.0): List<Pair<Int, Int>> {
        val points = 12
        return (0 until points).map { i ->
            val angle = 2.0 * Math.PI * i / points
            val r = baseR * (0.72 + rng.nextDouble() * 0.56)
            // No clamping: the world has no edges, so a country near the origin and one ten
            // thousand units away are described the same way.
            ((cx + r * Math.cos(angle)).toInt()) to ((cy + r * 0.72 * Math.sin(angle)).toInt())
        }
    }

    /**
     * The player's own country, placed at a spot that is theirs for the life of the save.
     *
     * Drawn from the base rather than generated: the stats on the popup for your own country have
     * to be the real ones, or the map is lying about the only country the player can check.
     */
    fun playerCountry(base: PaperBase, worldSeed: Long): Country {
        // The origin, always. An endless world needs a fixed point to measure distance from, and
        // "where the player is" is the only one that means anything.
        val (cx, cy) = WorldChunks.playerChunkCentre()
        return Country(
            id = PLAYER_ID,
            name = base.name,
            owner = Owner.PLAYER,
            level = base.level,
            // Sized from the base's OWN plot, not a formula: the player's boundary growth is a
            // decision they made (or have not made yet), and the map has the real number.
            outline = run {
                val shapeRng = Rng(Rng.seedOf("player-shape", worldSeed))
                val baseR = (7.0 + shapeRng.nextDouble() * 4.0) * (base.plotSize / Battle.FIELD.toDouble())
                blob(shapeRng, cx, cy, baseR)
            },
            centreX = cx,
            centreY = cy,
            soldiers = base.soldiers,
            buildingCount = base.standing.size,
            population = base.civilians,
            xp = base.xp,
            battlesWon = base.battlesWon,
            battlesLost = base.battlesLost,
        )
    }

    const val PLAYER_ID = "player"

    /**
     * Rebuilds an AI country's base so it can actually be attacked.
     *
     * Regenerated from the id rather than stored, for the same reason the country is: the base a
     * player raids has to be the same base every time they look at it, and storing fourteen bases
     * that the player may never visit is a lot of writing for a world that is a formula.
     */
    /**
     * A base laid out for [level], on the same plan the AI countries use.
     *
     * Exists so a save that the old [PaperBase.applyDamage] erased can be given its buildings back.
     * A player who reached level 500 earned the level; losing the drawing to a bug should not cost
     * them the game, and there is no undo to reach for because the layout was never kept anywhere.
     * What comes back is not what they had — that is gone — but it is a base of the right size,
     * which is the difference between a playable save and a dead one.
     *
     * ## Why it is not just [aiBase]
     *
     * Two things an AI base gets away with and the player's must not:
     *
     * 1. **It has to fit.** The field is thirty-two cells square, which is a thousand cells, and a
     *    level-500 country nominally runs to several hundred buildings of nine cells each. An AI
     *    base is never walked around, so overlapping placements there are invisible; the player's
     *    is the screen they live on. Anything that collides with what is already down is dropped,
     *    which lets the level decide how big the base wants to be and the paper decide how big it
     *    actually gets.
     * 2. **It has to be legal.** [BuildingCatalog.BuildingType.capAtLevel] limits how many of a
     *    thing a base may hold, and a recovered base over that limit would be a base the player
     *    could never add to.
     */
    /**
     * The prefix every recovered building's id carries, and the version of the recovery that made
     * it.
     *
     * Versioned because the first recovery was wrong: it passed no building count, so
     * [aiBase]'s floor of eight applied and a level-500 country came back as a town hall and eight
     * sheds. A marker in the id is what lets the next version tell "a base this code laid out
     * badly" — which it may safely replace — from "a base the player built and pruned", which it
     * must not touch.
     */
    const val RECOVERY_MARK = "recovered3:"

    /**
     * Adds the buildings without which a base cannot be played, and nothing else.
     *
     * ## The case this exists for
     *
     * A base can end up in a state it cannot get out of. With no training camp the army capacity is
     * zero, so no soldier is ever trained and the army can only shrink; with no builders' hut
     * nothing can be put up, including a training camp. Either one on its own is recoverable by
     * building the other. Both at once, or a camp cap of zero with an army already spent, is a dead
     * end — and that is where a base landed after the raid bug erased it and an incomplete recovery
     * put it back.
     *
     * Strictly additive. Anything already on the paper is left exactly where it is, including
     * whatever the player has built since, because losing a player's own work to a repair would be
     * the same class of mistake as the bug this is repairing. It only fills empty squares, and it
     * only does anything at all when one of the two dead ends is actually present.
     */
    /**
     * The base of a country that has been hit, with its ruins still on the page.
     *
     * ## Why this is not [scorched] applied to [aiBase]
     *
     * The first version of this reduced the country's building COUNT and generated a smaller base
     * from it. That is arithmetically correct and completely wrong to look at: a nuked country came
     * out as a tidy, smaller, perfectly intact town. What a bombed country looks like is the same
     * town with holes in it, and the holes are the point.
     *
     * So the base is generated at full size and then wrecked in place. The destroyed share is
     * knocked to zero hit points rather than removed, which does three things at once: the view has
     * something to draw rubble and fire on, [PaperBase.standing] already excludes anything at zero
     * so nothing else in the game has to learn about ruins, and — because [Battle.simulate] builds
     * its targets from `standing` — invading a country you bombed last night is genuinely easier,
     * against exactly the buildings you can see are still up.
     *
     * Deterministic in the country's id, so the same ruin is the same ruin every time it is looked
     * at, whether that is through the spy screen or in the middle of an invasion.
     */
    fun scorchedBase(country: Country, strike: Strike?, now: Long): PaperBase {
        val whole = aiBase(country)
        val scorch = strike?.scorch(now) ?: 0.0
        if (scorch <= 0.0 || strike == null) return whole

        val destroyedShare = scorch * strike.kind.destruction
        val rng = Rng(Rng.seedOf("scorch", country.id, strike.at))

        // The town hall goes last, so a country is only headless when it has really been flattened.
        val order = whole.buildings
            .sortedWith(compareBy({ it.typeId == BuildingCatalog.TOWN_HALL.id }, { rng.nextDouble() }))
        val toDestroy = (order.size * destroyedShare).toInt()
        val doomed = order.take(toDestroy).map { it.id }.toSet()

        return whole.copy(
            buildings = whole.buildings.map { building ->
                when {
                    building.id in doomed -> building.copy(hitPoints = 0)
                    // Whatever is left standing did not come through it untouched either.
                    else -> building.copy(
                        hitPoints = (building.maxHitPoints * (1.0 - destroyedShare * 0.55))
                            .toInt().coerceAtLeast(1)
                    )
                }
            }
        )
    }

    fun addMissingEssentials(base: PaperBase, level: Int): PaperBase {
        val wantsCamps = base.armyCapacity <= 0
        val wantsBuilders = base.builders <= 0
        if (!wantsCamps && !wantsBuilders) return base

        // Whatever field this base actually has -- expanded or not. A base that had
        // bought boundary expansions must not have them thrown away by a repair pass
        // that only knew about the original field.
        val fieldSize = base.plotSize

        val taken = Array(fieldSize) { BooleanArray(fieldSize) }
        base.buildings.forEach { b ->
            val size = b.type?.footprint ?: 1
            for (dy in 0 until size) for (dx in 0 until size) {
                val y = b.y + dy
                val x = b.x + dx
                if (y in 0 until fieldSize && x in 0 until fieldSize) taken[y][x] = true
            }
        }

        fun free(x: Int, y: Int, size: Int): Boolean {
            if (x < 1 || y < 1 || x + size > fieldSize - 1 || y + size > fieldSize - 1) return false
            for (dy in 0 until size) for (dx in 0 until size) if (taken[y + dy][x + dx]) return false
            return true
        }

        val added = ArrayList<PlacedBuilding>()
        val now = System.currentTimeMillis()

        fun place(type: BuildingCatalog.BuildingType, count: Int) {
            repeat(count) {
                var placed = false
                outer@ for (y in 1 until fieldSize - 1) {
                    for (x in 1 until fieldSize - 1) {
                        if (!free(x, y, type.footprint)) continue
                        for (dy in 0 until type.footprint) for (dx in 0 until type.footprint) {
                            taken[y + dy][x + dx] = true
                        }
                        added.add(
                            PlacedBuilding(
                                id = "${type.id}#restored${added.size}#$now",
                                typeId = type.id, x = x, y = y,
                                hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
                                startedAt = now, finishesAt = now, complete = true,
                            )
                        )
                        placed = true
                        break@outer
                    }
                }
                if (!placed) return
            }
        }

        if (wantsBuilders) place(BuildingCatalog.BUILDERS_HUT, 1)
        if (wantsCamps) {
            val camp = BuildingCatalog.TRAINING_CAMP
            place(camp, camp.capAtLevel(level).coerceIn(1, 8))
        }

        return if (added.isEmpty()) base else base.copy(buildings = base.buildings + added)
    }

    fun layoutFor(name: String, level: Int, seedKey: String): PaperBase {
        val rng = Rng(Rng.seedOf(RECOVERY_MARK, seedKey, level))
        val available = BuildingCatalog.unlockedAt(level)
        val hall = BuildingCatalog.TOWN_HALL
        // The field this level would actually have grown to, expansions included -- a
        // recovered level-300 country is given the room a level-300 country has earned,
        // not the field it started on at level 1.
        val fieldSize = Era.aiPlotSizeAt(level)

        // ── What the base should contain ──────────────────────────────────
        //
        // Built as a shopping list first and placed second, rather than sprinkled onto rings the
        // way an AI base is. An AI base is never walked around, so its overlaps do not matter; the
        // player's is the screen they live on, and rings of sixty-four buildings on a circumference
        // of twenty cells overlap almost completely -- which is how the first attempt at this
        // produced twelve buildings out of three hundred and fifty.
        //
        // The list is weighted the way a country actually is, and crucially it guarantees the
        // things without which a base cannot be PLAYED: builders, so anything can be put up at all,
        // and training camps, so the army has a capacity above zero.
        fun newest(category: BuildingCatalog.Category, count: Int): List<BuildingCatalog.BuildingType> =
            available.filter { it.category == category }.takeLast(count.coerceAtLeast(0))

        val wishlist = ArrayList<BuildingCatalog.BuildingType>()
        wishlist.add(hall)
        repeat(BuildingCatalog.BUILDERS_HUT.capAtLevel(level).coerceIn(1, 6)) { wishlist.add(BuildingCatalog.BUILDERS_HUT) }
        repeat(Era.trainingCamps(level).coerceIn(1, 12)) { wishlist.add(BuildingCatalog.TRAINING_CAMP) }

        // Then the rest of the country, newest of each kind first, because a level-500 base should
        // look like a level-500 base rather than like a museum of everything it ever built.
        listOf(
            BuildingCatalog.Category.HOUSING to 10,
            BuildingCatalog.Category.AGRICULTURE to 8,
            BuildingCatalog.Category.DEFENCE to 10,
            BuildingCatalog.Category.FORTIFICATION to 8,
            BuildingCatalog.Category.RESEARCH to 6,
            BuildingCatalog.Category.INDUSTRY to 6,
            BuildingCatalog.Category.RESOURCE to 5,
            BuildingCatalog.Category.TRADE to 4,
            BuildingCatalog.Category.CIVIC to 4,
            BuildingCatalog.Category.MILITARY to 4,
            BuildingCatalog.Category.INFRASTRUCTURE to 2,
        ).forEach { (category, count) -> wishlist.addAll(newest(category, count)) }

        // ── Where it goes ─────────────────────────────────────────────────
        //
        // A plain occupancy grid over the field: walk outward from the middle and take the first
        // square that is free and big enough. Boring, and it fills the paper, which rings did not.
        val taken = Array(fieldSize) { BooleanArray(fieldSize) }
        val placed = ArrayList<PlacedBuilding>()
        val owned = HashMap<String, Int>()
        val centre = fieldSize / 2

        fun free(x: Int, y: Int, size: Int): Boolean {
            if (x < 1 || y < 1 || x + size > fieldSize - 1 || y + size > fieldSize - 1) return false
            for (dy in 0 until size) for (dx in 0 until size) if (taken[y + dy][x + dx]) return false
            return true
        }

        fun occupy(x: Int, y: Int, size: Int) {
            for (dy in 0 until size) for (dx in 0 until size) taken[y + dy][x + dx] = true
        }

        // Squares in order of distance from the middle, so the base grows outward from the hall.
        val order = ArrayList<Pair<Int, Int>>(fieldSize * fieldSize)
        for (y in 1 until fieldSize - 1) for (x in 1 until fieldSize - 1) order.add(x to y)
        order.sortBy { (x, y) ->
            val dx = (x - centre).toDouble()
            val dy = (y - centre).toDouble()
            dx * dx + dy * dy + rng.nextDouble() * 2.0
        }

        wishlist.forEach { type ->
            val already = owned[type.id] ?: 0
            val isHall = type.id == hall.id
            if (!isHall && already >= type.capAtLevel(level)) return@forEach

            val spot = order.firstOrNull { (x, y) -> free(x, y, type.footprint) } ?: return@forEach
            val (x, y) = spot
            occupy(x, y, type.footprint)
            owned[type.id] = already + 1
            placed.add(
                PlacedBuilding(
                    id = "$RECOVERY_MARK$seedKey:${type.id}:${placed.size}",
                    typeId = type.id, x = x, y = y,
                    hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
                    startedAt = 0, finishesAt = 0, complete = true,
                )
            )
        }

        val researched = WeaponCatalog.unlockedAt(level)
            .sortedByDescending { it.threat }
            .take(12)
            .map { it.id }
            .toSet()

        return PaperBase(
            name = name,
            xp = Era.xpForLevel(level),
            peakXp = Era.xpForLevel(level),
            buildings = placed,
            researched = researched,
            plotExpansions = Era.plotExpansionsUnlockedAt(level),
        )
    }

    fun aiBase(country: Country): PaperBase {
        val rng = Rng(Rng.seedOf("base", country.id))
        val available = BuildingCatalog.unlockedAt(country.level)
        val placements = ArrayList<PlacedBuilding>()
        // An AI country is handed every boundary expansion its level allows -- it never
        // presses a button, but the effect on the world map (a high-level country visibly
        // larger than a low-level one) is the same as the player's.
        val fieldSize = Era.aiPlotSizeAt(country.level)

        fun add(type: BuildingCatalog.BuildingType, x: Int, y: Int) {
            val px = x.coerceIn(1, fieldSize - type.footprint - 1)
            val py = y.coerceIn(1, fieldSize - type.footprint - 1)
            placements.add(
                PlacedBuilding(
                    id = "${country.id}:${type.id}:${placements.size}",
                    typeId = type.id, x = px, y = py,
                    hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
                    startedAt = 0, finishesAt = 0, complete = true,
                )
            )
        }

        val centre = fieldSize / 2.0
        // Every ring radius below was tuned against the original 32-cell field; scaled by
        // how much bigger this country's field has actually grown, so an expanded AI
        // country's buildings spread out to fill it rather than clustering in the old
        // field's worth of space in the middle of a much bigger empty plot.
        val spread = fieldSize / Battle.FIELD.toDouble()

        /** Places [count] of the given types on a ring, spaced evenly with a little jitter. */
        fun ring(types: List<BuildingCatalog.BuildingType>, count: Int, radius: Double) {
            if (types.isEmpty() || count <= 0) return
            val slots = count.coerceAtMost(64)
            for (i in 0 until slots) {
                val type = types[i % types.size]
                val angle = 2.0 * Math.PI * i / slots + rng.nextDouble() * 0.12
                val r = radius + (rng.nextDouble() - 0.5) * 1.4
                add(
                    type,
                    (centre + r * Math.cos(angle)).toInt(),
                    (centre + r * Math.sin(angle)).toInt(),
                )
            }
        }

        fun ofCategory(vararg categories: BuildingCatalog.Category): List<BuildingCatalog.BuildingType> =
            rng.shuffled(available.filter { it.category in categories })

        // The town hall is the middle of everything, and everything else is arranged around it by
        // what it is for. Concentric rather than random because a base is a defensive argument: the
        // thing you cannot afford to lose goes in the centre, and the things that shoot go where
        // the attacker arrives.
        add(BuildingCatalog.TOWN_HALL, (centre - 1).toInt(), (centre - 1).toInt())

        // A floor, because an early country generated from a short catalogue came out as a town
        // hall and one shed -- which is not a country, and is not worth attacking either.
        val budget = country.buildingCount.coerceAtLeast(8)

        /** Rounds rather than truncates: `(2 * 0.45).toInt()` is zero, and three of those in a row
         *  produced an empty base at low levels. */
        fun share(of: Int, fraction: Double): Int = Math.round(of * fraction).toInt()

        // How much of the base is defensive, and it rises with the level. A level-400 country has
        // had four hundred levels to think about being attacked; a level-10 one has a fence.
        val defenceShare = 0.18 + (country.level / Era.MAX_LEVEL.toDouble()) * 0.34
        val defenceBudget = (budget * defenceShare).toInt().coerceAtLeast(2)
        val economyBudget = (budget - defenceBudget).coerceAtLeast(2)

        // Inner: what the country is FOR. Research and military sit close to the hall, where an
        // attacker only reaches after getting through everything else.
        ring(ofCategory(
            BuildingCatalog.Category.RESEARCH,
            BuildingCatalog.Category.MILITARY,
            BuildingCatalog.Category.CORE,
        ), share(economyBudget, 0.22).coerceAtLeast(1), radius = 3.4 * spread)

        // Middle: the economy. Industry, trade and the people who work in both.
        ring(ofCategory(
            BuildingCatalog.Category.INDUSTRY,
            BuildingCatalog.Category.TRADE,
            BuildingCatalog.Category.CIVIC,
            BuildingCatalog.Category.HOUSING,
        ), share(economyBudget, 0.45).coerceAtLeast(1), radius = 6.2 * spread)

        // Outer economy: the land. Farms and mines are the things furthest from the hall in every
        // settlement that has ever existed, and here that also means they are what a raid meets.
        ring(ofCategory(
            BuildingCatalog.Category.AGRICULTURE,
            BuildingCatalog.Category.RESOURCE,
            BuildingCatalog.Category.INFRASTRUCTURE,
        ), share(economyBudget, 0.33).coerceAtLeast(1), radius = 9.0 * spread)

        // The defences, on two rings that between them cover the approach and the interior. Split
        // rather than all on the perimeter because a single ring is walked around; an attacker who
        // breaks one point should still be under fire from behind it.
        val defences = ofCategory(BuildingCatalog.Category.DEFENCE)
        val walls = ofCategory(BuildingCatalog.Category.FORTIFICATION)
        val perimeter = if (walls.isNotEmpty()) walls else defences
        val interior = if (defences.isNotEmpty()) defences else walls

        ring(perimeter, share(defenceBudget, 0.55).coerceAtLeast(1), radius = 11.4 * spread)
        ring(interior, share(defenceBudget, 0.45), radius = 7.3 * spread)

        // Researched up to its level, so an AI at 400 fights with railguns and not with clubs.
        val researched = WeaponCatalog.unlockedAt(country.level)
            .sortedByDescending { it.threat }
            .take(12)
            .map { it.id }
            .toSet()

        return PaperBase(
            name = country.name,
            xp = country.xp,
            peakXp = country.xp,
            soldiers = country.soldiers,
            buildings = placements,
            researched = researched,
            plotExpansions = Era.plotExpansionsUnlockedAt(country.level),
        )
    }

    // -- Diplomacy ------------------------------------------------------------

    /**
     * The odds an AI country accepts an alliance: one in four, as the design specifies.
     *
     * Adjusted only downward, and only for a refusal that already happened — asking the same
     * country repeatedly should not be a way to farm a yes. Nothing raises it: a flat, known
     * probability is a rule a player can plan around, and a hidden modifier is not.
     */
    fun allianceChance(country: Country): Double = when (country.relation) {
        Relation.NEUTRAL -> 0.25
        Relation.REFUSED -> 0.10
        else -> 0.0
    }

    /** Rolls it. Seeded by the country and the attempt so the same tap cannot be retried for luck. */
    fun askAi(country: Country, attempt: Int): Boolean {
        if (!country.canBeAsked()) return false
        val rng = Rng(Rng.seedOf("alliance", country.id, attempt))
        return rng.chance(allianceChance(country))
    }

    /**
     * What a conquered country hands over.
     *
     * Everything the design names, taken from what the map already knows about the country plus
     * what its regenerated base actually contains — the defences are counted from the buildings
     * rather than guessed, so conquering a heavily fortified country is worth more than conquering
     * a sprawling undefended one of the same level.
     */
    /**
     * What an AI country has legislated about the conduct of its army.
     *
     * Derived from its level and its id, like everything else about an AI country. Deliberately not
     * universal even at high levels: a world where every developed country has signed the rules of
     * war removes the decision from the player's side too, because there would be nothing to be
     * unusual about. Roughly half of late-era countries have, a minority of mid-era ones have
     * customary restraint, and the early world has nothing at all — which is historically the
     * shape of it.
     */
    fun aiWarConduct(country: Country): Set<String> {
        val roll = Rng(Rng.seedOf("conduct", country.id)).nextDouble()
        return when {
            country.level >= 65 && roll < 0.45 -> setOf(Ideology.RULES_OF_WAR)
            country.level >= 40 && roll < 0.75 -> setOf("customary_restraint")
            else -> emptySet()
        }
    }

    fun spoilsOf(country: Country, takenAt: Long, killed: Int = 0): AnnexedCountry {
        val generated = aiBase(country)
        val defences = generated.standing
            .filter { it.type?.isDefensive == true }
            .sumOf { (it.type?.hitPoints ?: 0) / 100.0 }
        val survivingPopulation = (country.population - killed).coerceAtLeast(0)

        return AnnexedCountry(
            id = country.id,
            name = country.name,
            level = country.level,
            soldiers = country.soldiers,
            defences = defences,
            // The people who survived it. A country taken by an army that recognises no rules
            // arrives with fewer of them, and that is the cost of not having legislated: the
            // conquest is worth less, permanently, because the tax base was killed taking it.
            civilians = survivingPopulation,
            // A share of the treasury, not all of it: a country being overrun does not leave its
            // whole exchequer on the table, and taking everything would make one conquest the game.
            treasury = (country.xp * 0.35).toLong(),
            loyalty = 0.2,
            takenAt = takenAt,
            // The province's own base, so it can be built in, trained and researched from the day
            // it is taken rather than being a source of tax and nothing else. Its civilian count
            // starts in step with the number who actually survived the taking.
            base = generated.copy(civilians = survivingPopulation, families = (survivingPopulation / 4).coerceAtLeast(0)),
        )
    }

    /**
     * How likely a country is to come for you.
     *
     * Three things raise it and they are all the player's own doing. A failed invasion is the
     * biggest: attacking somebody and losing tells them exactly how weak you are, and a game where
     * that costs nothing makes attacking free. Being hostile at all matters, and so does being
     * rich enough to be worth the trip.
     *
     * Returned as a weight rather than a probability because the raider is chosen by drawing from
     * all the candidates at once — see [pickRaider].
     */
    fun aggressionToward(
        country: Country,
        failedInvasions: Int,
        playerLevel: Int,
    ): Double {
        if (country.relation == Relation.ALLIED) return 0.0

        var weight = when (country.relation) {
            Relation.HOSTILE -> 3.0
            Relation.REFUSED -> 1.4
            else -> 1.0
        }
        // Each failed invasion is a standing invitation. It compounds, but with a ceiling: a player
        // who has lost six attacks on one country should be frightened of it, not doomed by it.
        weight *= 1.0 + failedInvasions.coerceAtMost(6) * 0.85
        // Somebody far stronger has better things to do; somebody far weaker would not dare.
        val gap = country.level - playerLevel
        weight *= when {
            gap > 60 -> 0.35
            gap < -60 -> 0.25
            else -> 1.0
        }
        return weight
    }

    /**
     * Chooses who attacks the player next, or nobody.
     *
     * Weighted by [aggressionToward], so the countries the player has provoked come back and the
     * ones they have left alone mostly do not. Deterministic in [seed] so a raid that happened
     * while the player was away can be recomputed and shown to them.
     */
    fun pickRaider(
        candidates: List<Country>,
        failedInvasions: Map<String, Int>,
        playerLevel: Int,
        seed: Long,
    ): Country? {
        val weighted = candidates
            .map { it to aggressionToward(it, failedInvasions[it.id] ?: 0, playerLevel) }
            .filter { it.second > 0 }
        if (weighted.isEmpty()) return null

        val total = weighted.sumOf { it.second }
        var roll = Rng(seed).nextDouble() * total
        weighted.forEach { (country, weight) ->
            roll -= weight
            if (roll <= 0) return country
        }
        return weighted.last().first
    }

    /**
     * The chance a country's civilians rise against the player for invading it again.
     *
     * One percent for the second invasion, as the design asks, and climbing steeply from there:
     * a country invaded six times is a country in open revolt. A revolt costs the attacker the
     * province and turns the country hostile, which makes repeatedly farming the same neighbour a
     * strategy with an ending.
     */
    fun revoltChance(previousInvasions: Int): Double {
        if (previousInvasions <= 0) return 0.0
        return (0.01 * previousInvasions * previousInvasions).coerceAtMost(0.75)
    }

    fun rollRevolt(countryId: String, previousInvasions: Int, seed: Long): Boolean =
        Rng(Rng.seedOf("revolt", countryId, previousInvasions, seed))
            .chance(revoltChance(previousInvasions))

    /**
     * What an ally contributes.
     *
     * "Their soldiers and weapons are placed under your control" — so an alliance adds its army to
     * yours, attacking and defending both. It does NOT add their buildings: a country that lent you
     * its soldiers has not moved its walls to your page.
     */
    fun alliedSoldiers(allies: List<Country>): Int = allies.sumOf { it.soldiers }

    fun alliedWeapon(allies: List<Country>): WeaponCatalog.Weapon? =
        allies.maxByOrNull { it.level }?.let { ally ->
            WeaponCatalog.unlockedAt(ally.level).maxByOrNull { it.threat }
        }
}
