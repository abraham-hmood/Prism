package com.prism.launcher.minigames

/**
 * A base drawn on lined paper: what is standing, what is being built, and what it all cost.
 *
 * ## Immutable, and why that is worth the copying
 *
 * Every change returns a new [PaperBase]. A battle needs to run against the base as it was when the
 * raid started, the map needs to show a snapshot while a fight is in progress, and an undo of a
 * misplaced building has to be exact. All three are free when the state is a value and all three
 * are bug farms when it is not. A base is a few hundred small objects; copying one is nothing next
 * to drawing it.
 *
 * ## XP is the only currency
 *
 * There is no gold, no elixir, no second resource to balance against the first. The design says
 * levels come from XP and that building, researching, attacking and defending all move it — so XP
 * is it. Building spends it, finishing a building pays some back, winning a battle pays well, and
 * losing costs. That makes every decision comparable in one unit, which is the thing a player can
 * actually reason about at level 400 with sixty building types available.
 */
data class PaperBase(
    val name: String,
    /** The spendable balance. Building, researching and losing a battle all take from it. */
    val xp: Long = 0,
    /**
     * The highest [xp] has ever been, and the number the level is actually read from.
     *
     * ## Why the level is not read from the balance
     *
     * Because spending would then cost levels, and a level is not a currency — it is what the
     * building caps and the unlock list are keyed on. A player who spent 112 XP on a training camp
     * went from level 4 to level 3, which re-locked buildings and lowered caps they had already
     * built against: the act of placing a building could make that building illegal. It was
     * reachable in about a minute of play.
     *
     * A high-water mark fixes it at the root and is what the genre does anyway — no builder game
     * takes your town hall level back when you spend. The spec's "losing a battle costs XP" still
     * bites, and bites in the right place: a loss costs the balance and the progress toward the
     * next level, and leaves what you have already earned alone.
     *
     * Defaults to zero and is raised to [xp] on load, so a save written before this existed keeps
     * the level it had.
     */
    val peakXp: Long = 0,
    val buildings: List<PlacedBuilding> = emptyList(),
    val researched: Set<String> = emptySet(),
    /** Soldiers alive and at home. Capped by the training camps that are standing. */
    val soldiers: Int = 0,
    /** Set once the opening raid has flattened the first town hall. */
    val razed: Boolean = false,
    val battlesWon: Int = 0,
    val battlesLost: Int = 0,
    val raidsRepelled: Int = 0,
    val raidsLost: Int = 0,
    /**
     * Laws enacted, by law id. Everything not named here is that group's starting law.
     *
     * A set rather than a map of group to law because a law belongs to exactly one group and can
     * name it — and because storing the group as well means two sources of truth for which law is
     * in force, which is one more than there should be.
     */
    val enactedLaws: Set<String> = emptySet(),
    /** A law being deliberated: its id and when the deliberation ends. */
    val lawInProgress: String? = null,
    val lawFinishesAt: Long = 0,
    /** Countries conquered and kept. They pay in soldiers, taxes and people. */
    val annexed: List<AnnexedCountry> = emptyList(),
    /**
     * Civilians living here beyond those the housing accounts for.
     *
     * Housing sets the CAPACITY; this is how many people are actually in it, and it grows toward
     * that capacity over time rather than appearing the moment a roof does. It is what the tax is
     * levied on, so a player who builds a tower block does not get its taxes the same second.
     */
    /**
     * What the soldiers carry, when the player has chosen rather than left it to the game.
     *
     * Null means "the best thing researched", which is the sensible default and what the game did
     * before there was a choice. Naming one explicitly is how a player fields crossbows against a
     * base full of light troops, or keeps siege weapons for the walls -- the catalogue is a
     * thousand weapons deep and "strongest" is not always "right".
     */
    val infantryWeaponId: String? = null,
    val garrisonWeaponId: String? = null,
    /**
     * The crewed detachments that go out with the army: tanks, aircraft, guns, launchers.
     *
     * Null means "whatever is best", which is what an army does by default and what every save
     * written before detachments existed gets. An empty list means the player has deliberately sent
     * infantry alone, which is a different thing and has to survive a reload.
     */
    val supportWeaponIds: List<String>? = null,
    val civilians: Int = 0,
    /**
     * Households, not individuals.
     *
     * Civilians arrive as families and it is families that have children, so growth is driven by
     * this rather than by the head count: a hundred people in twenty households grow differently
     * from a hundred in ninety, and modelling only the total made the difference invisible. A
     * family is four people by default; [familySize] is what the schools and the food change.
     */
    val families: Int = 0,
    /** When the civilian tax was last collected, so an absence pays what it is owed. */
    val lastTaxAt: Long = 0,
    /**
     * The fractions of an XP and of a person that have not been banked yet.
     *
     * Without these the whole economy silently rounds to nothing. The tick runs once a second, so
     * one pass is a sixtieth of a minute: a country earning 26 XP a minute earns 0.43 XP per tick
     * and grows by 0.015 of a person, and `toLong()` turns both into zero — for ever. On a device
     * that looked like a country with forty-six houses and no inhabitants and no income at all.
     *
     * Carrying the remainder means the rate is what it says it is, however often the clock ticks.
     */
    val xpCarry: Double = 0.0,
    val growthCarry: Double = 0.0,
    /**
     * How many boundary expansions this country has bought.
     *
     * Zero for every country until it pays for one, which is why it defaults to zero rather than
     * to whatever the level would allow: an expansion is a decision, at a cost, not an automatic
     * grant. [PaperBase.plotSize] is the field size this actually produces; [WorldMap.aiBase] and
     * [WorldMap.layoutFor] set it directly for AI and recovered countries, which are never asked.
     */
    val plotExpansions: Int = 0,
) {

    val level: Int get() = Era.levelForXp(maxOf(xp, peakXp))

    /**
     * How big this country's own field is, in cells.
     *
     * The tactical field every battle over this country is fought on, and the bound every building
     * placement on it is checked against. See [Era.plotSizeFor] and, for how a battle picks it up,
     * [Battle.simulate].
     */
    val plotSize: Int get() = Era.plotSizeFor(plotExpansions)

    /** Whether the level has unlocked another boundary expansion beyond what has been bought. */
    fun canExpandPlot(): Boolean = plotExpansions < Era.plotExpansionsUnlockedAt(level)

    /**
     * What the next expansion costs.
     *
     * Scales with both the level (a later expansion is a bigger undertaking) and with how many have
     * already been bought (the country is already larger, so the next ring of land is a bigger
     * project than the first was) — the same two-factor shape [BuildingCatalog] costs use.
     */
    val expandPlotCost: Long
        get() = (600L + level * 40L) * (1 + plotExpansions)

    /** Buys one boundary expansion. The caller is expected to have checked [canExpandPlot]. */
    fun expandPlot(): PaperBase =
        if (xp < expandPlotCost) this
        else copy(xp = xp - expandPlotCost, plotExpansions = plotExpansions + 1)

    val age: Era.Age get() = Era.ageOf(level)

    /** Progress through the current level, 0..1, for the bar at the top of the page. */
    val levelProgress: Double
        get() {
            val floor = Era.xpForLevel(level)
            val cost = Era.xpToNext(level)
            if (cost <= 0) return 1.0
            // Measured against the high-water mark, like the level itself: a bar that fell back
            // when the player bought something would be describing a level that had not moved.
            return ((maxOf(xp, peakXp) - floor).toDouble() / cost).coerceIn(0.0, 1.0)
        }

    val standing: List<PlacedBuilding> get() = buildings.filter { it.complete && it.hitPoints > 0 }

    val townHall: PlacedBuilding? get() = standing.firstOrNull { it.typeId == BuildingCatalog.TOWN_HALL.id }

    val hasTownHall: Boolean get() = townHall != null

    /** Builders decide how many things can go up at once. One hut, one thing at a time. */
    val builders: Int
        get() = (countStanding(BuildingCatalog.BUILDERS_HUT.id) + lawEffects.builders)
            .coerceAtLeast(1)

    val underConstruction: List<PlacedBuilding> get() = buildings.filter { !it.complete }

    val freeBuilders: Int get() = (builders - underConstruction.size).coerceAtLeast(0)

    val armyCapacity: Int
        get() = (
            countStanding(BuildingCatalog.TRAINING_CAMP.id) * Era.soldiersPerCamp(level) *
                lawEffects.armySize
            ).toInt()

    /** Kept as the capacity for compatibility; [civilians] is who is actually here. */
    val population: Int get() = civilianCapacity

    fun countStanding(typeId: String): Int = standing.count { it.typeId == typeId }

    fun countOwned(typeId: String): Int = buildings.count { it.typeId == typeId && it.hitPoints > 0 }

    /**
     * Whether another of [type] may be placed right now.
     *
     * Three separate gates, and they fail for different reasons the player needs told apart: the
     * level has not unlocked it, they already have as many as the level allows, or every builder is
     * busy. A single boolean would make all three look like the same wall.
     */
    fun canPlace(type: BuildingCatalog.BuildingType): Placement = when {
        level < type.unlockLevel -> Placement.Locked(type.unlockLevel)
        countOwned(type.id) >= type.capAtLevel(level) -> Placement.AtCap(type.capAtLevel(level))
        freeBuilders <= 0 -> Placement.NoBuilder
        xp < effectiveBuildCost(type) -> Placement.NotEnoughXp(effectiveBuildCost(type) - xp)
        else -> Placement.Allowed
    }

    sealed interface Placement {
        data object Allowed : Placement
        data class Locked(val atLevel: Int) : Placement
        data class AtCap(val cap: Int) : Placement
        data object NoBuilder : Placement
        data class NotEnoughXp(val short: Long) : Placement
    }

    /**
     * Starts a building.
     *
     * The XP is spent immediately and the reward arrives on completion, which is the arrangement
     * that makes a half-finished base a real state rather than a free option — a player who queues
     * six things and walks away has committed to them.
     */
    /**
     * Whether [type] is a road, and so gets laid between two buildings rather than put on a square.
     *
     * Detected by name because "road" is a shape of thing rather than a category: the catalogue has
     * dirt tracks, cobbles, motorways, maglev lines and gravitic roadways, and they are all the
     * same idea. Bridges and viaducts are deliberately NOT roads here — they are structures that
     * sit on a square, and treating them as lines would put a viaduct between two farms.
     */
    fun isRoadType(type: BuildingCatalog.BuildingType): Boolean =
        type.category == BuildingCatalog.Category.INFRASTRUCTURE &&
            ROAD_WORDS.any { type.name.contains(it, ignoreCase = true) }

    /**
     * The two buildings a road placed at [x],[y] should join.
     *
     * The nearest building to the point, and then the nearest OTHER building to that one — which is
     * what a person laying a road actually means by putting it down there. Null when there is
     * nothing to connect, and a road to nowhere is refused rather than drawn.
     */
    fun roadEndsNear(x: Int, y: Int): Pair<PlacedBuilding, PlacedBuilding>? {
        val candidates = buildings.filter { !it.isRoad }
        if (candidates.size < 2) return null
        val first = candidates.minByOrNull {
            val dx = it.centreX() - x
            val dy = it.centreY() - y
            dx * dx + dy * dy
        } ?: return null
        // The second nearest to the TAP, not the nearest to the first building. Measuring from
        // the first end means a road laid between two farms can run off to whatever happens to
        // stand behind one of them, which is not what putting it down there meant.
        val second = candidates.filter { it.id != first.id }.minByOrNull {
            val dx = it.centreX() - x
            val dy = it.centreY() - y
            dx * dx + dy * dy
        } ?: return null
        return first to second
    }

    fun place(type: BuildingCatalog.BuildingType, x: Int, y: Int, now: Long): PaperBase {
        if (canPlace(type) != Placement.Allowed) return this

        val cost = effectiveBuildCost(type)
        val millis = (effectiveBuildSeconds(type) * 1000L).toLong()

        if (isRoadType(type)) {
            val (from, to) = roadEndsNear(x, y) ?: return this
            val road = PlacedBuilding(
                id = "${type.id}#${buildings.size}#$now",
                typeId = type.id,
                // Parked at the midpoint so it still has a position for anything that needs one.
                x = ((from.centreX() + to.centreX()) / 2).toInt(),
                y = ((from.centreY() + to.centreY()) / 2).toInt(),
                hitPoints = type.hitPoints,
                maxHitPoints = type.hitPoints,
                startedAt = now,
                finishesAt = now + millis,
                connectsFrom = from.id,
                connectsTo = to.id,
            )
            return copy(xp = xp - cost, buildings = buildings + road)
        }

        val placed = PlacedBuilding(
            id = "${type.id}#${buildings.size}#$now",
            typeId = type.id,
            x = x,
            y = y,
            hitPoints = type.hitPoints,
            maxHitPoints = type.hitPoints,
            startedAt = now,
            finishesAt = now + millis,
        )
        return copy(xp = xp - cost, buildings = buildings + placed)
    }

    /** Anything whose timer has run out becomes real, and pays its completion XP. */
    fun advanceConstruction(now: Long): PaperBase {
        val finished = buildings.filter { !it.complete && now >= it.finishesAt }
        if (finished.isEmpty()) return this
        val reward = finished.sumOf { it.type?.buildReward ?: 0L }
        val next = xp + reward
        return copy(
            xp = next,
            peakXp = maxOf(peakXp, xp, next),
            buildings = buildings.map { if (it in finished) it.copy(complete = true) else it },
        )
    }

    // -- Government -----------------------------------------------------------

    /** Everything the country's laws do to it, multiplied together. */
    val lawEffects: Ideology.Effects get() = Ideology.effectsOf(enactedLaws)

    /** What the country's politics amount to, in two words. */
    val ideology: String get() = Ideology.describeCountry(enactedLaws)

    /**
     * Whether law research is open at all.
     *
     * A standing university. Without one the whole political game stays shut, which is the point:
     * a hamlet debating universal suffrage is a joke, and a country that has built a university has
     * demonstrably got somewhere.
     */
    val hasUniversity: Boolean
        get() = standing.any { it.typeId in UNIVERSITY_IDS }

    fun canEnact(law: Ideology.Law): Boolean =
        hasUniversity &&
            lawInProgress == null &&
            law.id !in enactedLaws &&
            level >= law.minLevel &&
            xp >= lawCost(law)

    /** What a law costs here, after the country's existing laws have had their say. */
    fun lawCost(law: Ideology.Law): Long =
        (law.cost * lawEffects.researchCost).toLong().coerceAtLeast(1)

    /**
     * Begins deliberating a law. It takes effect when the timer runs out.
     *
     * How long that is depends on how much of a legislature the country actually has: CIVIC is
     * guildhalls, courthouses, town halls and the rest of the catalogue's governance buildings, and
     * more of them is a bigger, faster-moving assembly. See [civicLawSpeed].
     */
    fun beginLaw(law: Ideology.Law, now: Long): PaperBase {
        if (!canEnact(law)) return this
        return copy(
            xp = xp - lawCost(law),
            lawInProgress = law.id,
            lawFinishesAt = now + (law.seconds * civicLawSpeed * 1000L).toLong(),
        )
    }

    /** Puts a finished law into force. */
    fun advanceLaw(now: Long): PaperBase {
        val pending = lawInProgress ?: return this
        if (now < lawFinishesAt) return this
        return copy(
            enactedLaws = enactedLaws + pending,
            lawInProgress = null,
            lawFinishesAt = 0,
        )
    }

    // -- Civilians and what they pay ------------------------------------------

    /**
     * How many people the country can hold.
     *
     * Housing sets the floor and the town hall's level multiplies it, a step every five levels —
     * the same cadence everything else in the game grows on. A level-1 hut holds a family; the same
     * hut at level 100 is a street, because the country around it has grown up.
     */
    val civilianCapacity: Int
        get() {
            val housed = standing.sumOf { it.type?.residents ?: 0 }
            return (housed * densityMultiplier()).toInt()
        }

    /** The per-five-levels multiplier on how many people fit in the same housing. */
    fun densityMultiplier(): Double = 1.0 + ((level - 1) / 5) * 0.22

    /** Food the farms produce each minute. Agriculture is the only category that makes any. */
    val foodProduction: Double
        get() = standing.sumOf {
            val type = it.type ?: return@sumOf 0.0
            if (type.category != BuildingCatalog.Category.AGRICULTURE) 0.0
            else (2.0 + type.tier * 0.35) * type.footprint
        }

    /** Food the population eats each minute. */
    val foodDemand: Double get() = civilians * FOOD_PER_CIVILIAN

    /**
     * How well fed the country is: 1.0 is exactly enough, above that is a surplus.
     *
     * A surplus is what makes families have children, which is the design's rule — "the more food
     * produced by farms, the more the civilians reproduce". A deficit does the opposite and people
     * start leaving, which is why the number is allowed below one rather than clamped.
     */
    val foodRatio: Double
        get() = if (foodDemand <= 0.0) (if (foodProduction > 0) 2.0 else 1.0)
        else foodProduction / foodDemand

    // -- What the rest of the catalogue is FOR --------------------------------
    //
    // AGRICULTURE feeds people and HOUSING shelters them, and until now that was where the
    // catalogue's usefulness stopped: RESOURCE, INDUSTRY, TRADE and CIVIC together are getting on
    // for half of every building the game has ever offered, and none of the four did anything a
    // player could point to. A country with three hundred mines and workshops played identically to
    // one with none, which is exactly the complaint "these are just there" describes.
    //
    // Each gets ONE clear, catalogue-wide lever rather than an individual effect per building --
    // the same shape [foodProduction] already uses for AGRICULTURE, and for the same reason: nine
    // hundred bespoke numbers would be unmaintainable and, worse, arbitrary, where one formula per
    // category is a rule a player can learn and rely on. Together the five economic categories cover
    // five different things a country can be good at, and none of them overlap:
    //
    //   RESOURCE        -> building is CHEAPER        (raw material, see [resourceBuildDiscount])
    //   INDUSTRY        -> building is FASTER          (manufacturing, [industryBuildSpeed])
    //   INFRASTRUCTURE  -> research is CHEAPER          (logistics, [infrastructureResearchDiscount])
    //   TRADE           -> tax income is HIGHER         (commerce, [tradeIncomeBonus])
    //   CIVIC           -> laws are decided FASTER      (governance, [civicLawSpeed])
    //
    // Every one is diminishing and floored (or capped) well short of the extreme, the same guard
    // [densityMultiplier] and [schooling] already use, so a country cannot mine, build or legislate
    // its way to something free.

    /** How much cheaper a resource-rich country builds. Mines and quarries supply the materials. */
    val resourceBuildDiscount: Double
        get() {
            val count = standing.count { it.type?.category == BuildingCatalog.Category.RESOURCE }
            return (1.0 - count * 0.012).coerceIn(0.55, 1.0)
        }

    /** How much faster an industrial country builds. Mills, forges and workshops are throughput. */
    val industryBuildSpeed: Double
        get() {
            val count = standing.count { it.type?.category == BuildingCatalog.Category.INDUSTRY }
            return (1.0 - count * 0.012).coerceIn(0.5, 1.0)
        }

    /**
     * How much cheaper research is, for a country with the logistics to move specialists,
     * instruments and results around it. Bridges, aqueducts and canals, not the roads -- a road's
     * job is joining two particular buildings, and it already does that job; this is everything
     * else INFRASTRUCTURE builds.
     */
    val infrastructureResearchDiscount: Double
        get() {
            val count = standing.count {
                it.type?.category == BuildingCatalog.Category.INFRASTRUCTURE && !it.isRoad
            }
            return (1.0 - count * 0.01).coerceIn(0.6, 1.0)
        }

    /** How much more a trading country's civilians are worth in tax. Markets sell their labour. */
    val tradeIncomeBonus: Double
        get() {
            val count = standing.count { it.type?.category == BuildingCatalog.Category.TRADE }
            return 1.0 + count * 0.02
        }

    /** How much faster a civic-minded country makes up its mind. Guildhalls are the legislature. */
    val civicLawSpeed: Double
        get() {
            val count = standing.count { it.type?.category == BuildingCatalog.Category.CIVIC }
            return (1.0 - count * 0.015).coerceIn(0.4, 1.0)
        }

    /**
     * How much stronger this base's own army fights, from its MILITARY buildings that are not the
     * training camp itself -- barracks, stables, archery and drill grounds, drone hangars, and the
     * rest of the catalogue's military infrastructure that used to do nothing. The training camp
     * already answers HOW MANY soldiers a base can field ([armyCapacity]); this is how good the
     * ones it fields actually are, applied to the army's hit points when it goes out to attack.
     */
    val militaryBonus: Double
        get() {
            val count = standing.count {
                it.type?.category == BuildingCatalog.Category.MILITARY &&
                    it.typeId != BuildingCatalog.TRAINING_CAMP.id
            }
            return (1.0 + count * 0.012).coerceAtMost(2.5)
        }

    /**
     * A second, smaller discount on weapon research, from the RESEARCH buildings that are not
     * [SCHOOL_IDS] or the weapons research hall itself -- observatories, libraries, engineers'
     * halls, printing presses, the rest of the catalogue that only ever unlocked something by being
     * built, never by doing anything afterward.
     *
     * Stacks with [infrastructureResearchDiscount] rather than replacing it: a country can be
     * research-strong for two different reasons -- good logistics (INFRASTRUCTURE) and a deep
     * research tradition (RESEARCH itself) -- and both are true at once for a country that has both.
     */
    val researchInstituteDiscount: Double
        get() {
            val count = standing.count {
                it.type?.category == BuildingCatalog.Category.RESEARCH &&
                    it.typeId !in SCHOOL_IDS &&
                    it.typeId != "weapons_research"
            }
            return (1.0 - count * 0.008).coerceIn(0.65, 1.0)
        }

    /** What placing [type] actually costs here, resource discount included. */
    fun effectiveBuildCost(type: BuildingCatalog.BuildingType): Long =
        (type.buildCost * resourceBuildDiscount).toLong().coerceAtLeast(1)

    /** How long placing [type] actually takes here, the build-speed law and industry included. */
    fun effectiveBuildSeconds(type: BuildingCatalog.BuildingType): Double =
        (type.buildSeconds * lawEffects.buildSpeed * industryBuildSpeed).coerceAtLeast(1.0)

    /** Schooling, from the schools that are standing. Raises what a family is worth. */
    val schooling: Double
        get() {
            val schools = standing.count { it.typeId in SCHOOL_IDS }
            return 1.0 + schools * 0.14
        }

    /** People per household. Schooling makes for smaller, better-off families. */
    fun familySize(): Double = (4.0 / schooling).coerceAtLeast(2.0)

    /** What one civilian pays, before the tax law. Schooling and trade both make them worth more. */
    fun taxPerCivilian(): Double = BASE_TAX_PER_CIVILIAN * schooling * tradeIncomeBonus

    /**
     * XP the civilians pay each minute.
     *
     * The tax is the reason housing is worth building and the reason conquest is worth doing: it
     * is the only income that arrives without the player doing anything, and it scales with the
     * thing a country accumulates rather than with the thing a player clicks.
     */
    fun civilianTaxPerMinute(): Double {
        val own = civilians * taxPerCivilian()
        val tribute = annexed.sumOf { it.civilians * BASE_TAX_PER_CIVILIAN * it.loyalty }
        return (own + tribute) * lawEffects.taxRate
    }

    /**
     * Moves people in and collects what they owe, for however long has passed.
     *
     * Both in one step because they are the same clock. A player who closes the page for an hour
     * should come back to an hour of growth and an hour of taxes, not to a paused country — and
     * computing that from two timestamps is simpler and more honest than ticking in the background.
     */
    fun collectTaxes(now: Long): PaperBase {
        if (lastTaxAt <= 0) return copy(lastTaxAt = now)
        val minutes = ((now - lastTaxAt).coerceAtLeast(0)) / 60_000.0
        if (minutes < 0.01) return this

        // Capped at a day, so a phone left in a drawer for a month does not hand back a fortune.
        val paid = minutes.coerceAtMost(60.0 * 24) * civilianTaxPerMinute() + xpCarry
        val paidWhole = paid.toLong()
        val nextXpCarry = paid - paidWhole

        val room = (civilianCapacity - civilians).coerceAtLeast(0)

        // Two sources of people, and they behave differently. Newcomers arrive because there is
        // housing standing empty; children are born because families are fed. A country with
        // plenty of room and no farms fills slowly and then stops; one with farms keeps growing
        // until it runs out of roofs.
        val surplus = (foodRatio - 1.0).coerceIn(-0.5, 1.5)
        val births = minutes * (families * BIRTHS_PER_FAMILY_PER_MINUTE) * (1.0 + surplus)
        // Proportional to the housing standing empty, not a flat trickle. A flat rate meant a
        // country with fifty empty homes took an hour to fill one of them, which on a phone reads
        // as "civilians do not work" -- and it is wrong besides: people move to where there is
        // somewhere to live, and the emptier a place is the faster it fills. Because the rate falls
        // as the room does, the population approaches capacity rather than slamming into it.
        val newcomers = minutes * (1.0 + room * ARRIVALS_PER_EMPTY_HOME) *
            (if (foodRatio >= 0.9) 1.0 else 0.2)

        val grown = (births + newcomers) * lawEffects.populationGrowth + growthCarry
        val growth = grown.toInt()
        val nextGrowthCarry = grown - growth
        // A famine drives people out rather than killing them, which is both kinder and the thing
        // that actually happens: a country that cannot feed its people loses them.
        val leaving = if (foodRatio < 0.75) (minutes * civilians * 0.004).toInt() else 0

        val nextCivilians = (civilians + growth.coerceAtMost(room) - leaving).coerceAtLeast(0)
        val nextFamilies = (nextCivilians / familySize()).toInt().coerceAtLeast(if (nextCivilians > 0) 1 else 0)

        val next = xp + paidWhole
        return copy(
            xp = next,
            peakXp = maxOf(peakXp, xp, next),
            civilians = nextCivilians,
            families = nextFamilies,
            lastTaxAt = now,
            xpCarry = nextXpCarry,
            // The carry is dropped once the housing is full: holding a fraction of a person against
            // a wall that is not going to move is just a number that never gets used again.
            growthCarry = if (room <= 0) 0.0 else nextGrowthCarry,
            annexed = annexed.map { it.settle(minutes) },
        )
    }

    // -- Conquest -------------------------------------------------------------

    /**
     * Takes a defeated country.
     *
     * Everything the design asks for arrives together because it all belongs to the same thing: a
     * country under your flag brings its soldiers, its defences, its people and the taxes those
     * people pay. It is held as a record rather than as buildings on your own paper — a conquered
     * country is somewhere else, and pretending its walls have moved onto your plot would be a
     * strange way to model an empire.
     */
    fun annex(country: AnnexedCountry): PaperBase {
        if (annexed.any { it.id == country.id }) return this
        val seized = (country.treasury * lawEffects.annexYield).toLong()
        val next = xp + seized
        return copy(
            xp = next,
            peakXp = maxOf(peakXp, xp, next),
            annexed = annexed + country,
        )
    }

    fun releaseAnnexed(id: String): PaperBase = copy(annexed = annexed.filterNot { it.id == id })

    /** Soldiers from conquered countries, which fight alongside your own. */
    val tributarySoldiers: Int
        get() = annexed.sumOf { (it.soldiers * it.loyalty).toInt() }

    /** Defensive strength conquered countries lend when this base is raided. */
    val tributaryDefence: Double
        get() = annexed.sumOf { it.defences * it.loyalty } * lawEffects.defence

    val totalPopulation: Int get() = civilians + annexed.sumOf { it.civilians }

    /** Rubs one out. Refunds nothing: a decision that costs nothing to reverse is not a decision. */
    fun demolish(buildingId: String): PaperBase =
        copy(buildings = buildings.filterNot { it.id == buildingId })

    // -- Research -------------------------------------------------------------

    fun canResearch(weapon: WeaponCatalog.Weapon): Boolean =
        weapon.id !in researched &&
            level >= weapon.unlockLevel &&
            xp >= researchCost(weapon) &&
            countStanding("weapons_research") > 0

    /**
     * What studying [weapon] costs here: the education law, the logistics INFRASTRUCTURE gives it,
     * and a country's own research institutes on top of both.
     */
    fun researchCost(weapon: WeaponCatalog.Weapon): Long =
        (
            weapon.researchCost * lawEffects.researchCost *
                infrastructureResearchDiscount * researchInstituteDiscount
            ).toLong().coerceAtLeast(1)

    fun research(weapon: WeaponCatalog.Weapon): PaperBase {
        if (!canResearch(weapon)) return this
        return copy(xp = xp - researchCost(weapon), researched = researched + weapon.id)
    }

    /** What an ordinary soldier of this base carries into a fight. */
    fun infantryWeapon(): WeaponCatalog.Weapon = chosen(infantryWeaponId)
        ?: WeaponCatalog.bestResearched(researched, preferRanged = false)

    /** What a soldier standing on a wall carries. */
    fun garrisonWeapon(): WeaponCatalog.Weapon = chosen(garrisonWeaponId)
        ?: WeaponCatalog.bestResearched(researched, preferRanged = true)

    /**
     * A chosen weapon, if it is still a legal choice.
     *
     * Re-checked against the researched set every time rather than trusted, because a save can
     * outlive a catalogue edit and a loadout pointing at a weapon that no longer exists would arm
     * the whole army with nothing.
     */
    private fun chosen(id: String?): WeaponCatalog.Weapon? =
        id?.takeIf { it in researched }?.let { WeaponCatalog.byId(it) }

    /** Everything the player may arm their soldiers with. */
    fun armoury(): List<WeaponCatalog.Weapon> =
        researched.mapNotNull { WeaponCatalog.byId(it) }
            .filter { it.weaponClass != WeaponCatalog.WeaponClass.SUPPORT }
            .sortedByDescending { it.threat }

    /**
     * The crewed detachments this base sends out, in the order they are fielded.
     *
     * ## Why an army is not one weapon
     *
     * Every soldier used to carry [infantryWeapon], which meant an army was five hundred copies of
     * one thing — and because the drawing follows the weapon, a modern assault was five hundred
     * identical howitzers walking in a line. There were sixty-eight tanks and fifty-three aircraft
     * in the catalogue and no way for any of them to appear on the paper, because nothing could put
     * a tank and a rifleman in the same army.
     *
     * A detachment fixes that at the root: the simulation has always held a weapon PER FIGHTER, so
     * it costs nothing to hand it a mixed list. What was missing was anything that built one.
     */
    fun supportWeapons(): List<WeaponCatalog.Weapon> {
        val picked = supportWeaponIds ?: return autoDetachments()
        return picked.mapNotNull { chosen(it) }
    }

    /**
     * What an army takes when the player has not said: the best of each kind they have researched.
     *
     * Deliberately one of each kind rather than four of the strongest, because the point is that an
     * army looks like an army — armour, air and guns together — and four of the best would collapse
     * back into one repeated unit, which is the thing being fixed.
     */
    private fun autoDetachments(): List<WeaponCatalog.Weapon> {
        val armoury = armoury()
        fun best(predicate: (WeaponCatalog.Weapon) -> Boolean) =
            armoury.filter(predicate).maxByOrNull { it.threat }
        return listOfNotNull(
            best { it.weaponClass == WeaponCatalog.WeaponClass.ARMOUR },
            best { it.isRotaryWing },
            best { it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT && !it.isRotaryWing },
            best { it.weaponClass == WeaponCatalog.WeaponClass.ARTILLERY },
            best { it.weaponClass == WeaponCatalog.WeaponClass.MISSILE },
        ).take(MAX_DETACHMENTS)
    }

    /** Everything that could be fielded as a detachment: the crewed half of the armoury. */
    fun crewedArmoury(): List<WeaponCatalog.Weapon> = armoury().filter { it.weaponClass.isCrewed }

    fun withSupportWeapons(ids: List<String>?): PaperBase =
        copy(supportWeaponIds = ids?.distinct()?.take(MAX_DETACHMENTS))

    fun withInfantryWeapon(id: String?): PaperBase = copy(infantryWeaponId = id)

    fun withGarrisonWeapon(id: String?): PaperBase = copy(garrisonWeaponId = id)

    // -- Army -----------------------------------------------------------------

    fun trainSoldiers(count: Int): PaperBase {
        val room = (armyCapacity - soldiers).coerceAtLeast(0)
        val taken = count.coerceAtMost(room)
        if (taken <= 0) return this
        // Training costs a little: an army that appears for free is an army nobody counts.
        val cost = taken * (6L + level)
        if (xp < cost) return this
        return copy(xp = xp - cost, soldiers = soldiers + taken)
    }

    fun loseSoldiers(count: Int): PaperBase = copy(soldiers = (soldiers - count).coerceAtLeast(0))

    // -- Fast forward ---------------------------------------------------------

    /**
     * What it costs to finish something early.
     *
     * Proportional to the time being bought, at a rate that makes it a real decision: a few
     * seconds is nearly free, and skipping a ten-minute build costs about what the building did.
     * Charging a flat fee would make it correct to rush everything large and nothing small, which
     * is the opposite of a choice.
     */
    fun rushCost(millisRemaining: Long): Long {
        if (millisRemaining <= 0) return 0
        val seconds = millisRemaining / 1000.0
        return (seconds * RUSH_XP_PER_SECOND).toLong().coerceAtLeast(1)
    }

    /** What finishing [building] right now would cost, or zero if it is already done. */
    fun rushCostOf(building: PlacedBuilding, now: Long): Long =
        if (building.complete) 0 else rushCost(building.finishesAt - now)

    /**
     * Pays to finish a building immediately.
     *
     * The timer is moved rather than the building completed outright, so the ordinary completion
     * path still runs and still pays the build reward — one place that knows what finishing means.
     */
    fun rushBuilding(buildingId: String, now: Long): PaperBase {
        val building = buildings.firstOrNull { it.id == buildingId && !it.complete } ?: return this
        val cost = rushCostOf(building, now)
        if (cost <= 0 || xp < cost) return this
        return copy(
            xp = xp - cost,
            buildings = buildings.map { if (it.id == buildingId) it.copy(finishesAt = now) else it },
        ).advanceConstruction(now)
    }

    /** The same for a law under deliberation. */
    fun rushLawCost(now: Long): Long =
        if (lawInProgress == null) 0 else rushCost(lawFinishesAt - now)

    fun rushLaw(now: Long): PaperBase {
        if (lawInProgress == null) return this
        val cost = rushLawCost(now)
        if (cost <= 0 || xp < cost) return this
        return copy(xp = xp - cost, lawFinishesAt = now).advanceLaw(now)
    }

    // -- XP -------------------------------------------------------------------

    /**
     * Moves the balance.
     *
     * A gain raises the high-water mark with it; a loss takes only the balance. That is what makes
     * a lost raid hurt — it costs XP that would have gone toward the next level — without ever
     * demolishing progress the player has already banked.
     */
    fun addXp(delta: Long): PaperBase {
        val next = (xp + delta).coerceAtLeast(0)
        // The CURRENT balance counts toward the mark, not just the new one. Without that, a base
        // constructed with an xp and no explicit peak — which every test and every older save is —
        // has a mark of zero, and the first loss takes the level with it. Reading the mark as
        // "the highest this has ever been, including right now" makes the field an optimisation
        // rather than something callers have to remember to maintain.
        return copy(xp = next, peakXp = maxOf(peakXp, xp, next))
    }

    fun recordAttack(won: Boolean): PaperBase =
        if (won) copy(battlesWon = battlesWon + 1) else copy(battlesLost = battlesLost + 1)

    fun recordDefence(held: Boolean): PaperBase =
        if (held) copy(raidsRepelled = raidsRepelled + 1) else copy(raidsLost = raidsLost + 1)

    /** Whether this country has bound its army by the rules of war. See [Ideology.RULES_OF_WAR]. */
    val sparesCivilians: Boolean get() = Ideology.RULES_OF_WAR in enactedLaws

    /**
     * The people killed when this base is fought over, and the base without them.
     *
     * ## Whose law applies
     *
     * Both countries'. The attacker's law is what binds the attacker's soldiers, and the defender's
     * is what binds its own — a country that has not written anything down loses people to its own
     * garrison fighting in its streets as well as to the army in them. Taking the worse of the two
     * would let a lawful invader be blamed for a massacre it did not commit; taking the better
     * would let a lawless one hide behind its victim's legislation. Each is answerable for its own.
     *
     * [share] is how much of the base was actually fought over, so a raid that is beaten off at the
     * wall does not empty the town.
     */
    fun civilianCasualties(attackerLaws: Set<String>, share: Double): Int {
        if (civilians <= 0 || share <= 0.0) return 0
        val rate = Ideology.civilianDeathRate(attackerLaws) + Ideology.civilianDeathRate(enactedLaws) * 0.5
        if (rate <= 0.0) return 0
        return (civilians * rate * share.coerceIn(0.0, 1.0)).toInt().coerceIn(0, civilians)
    }

    /** Removes [count] civilians, and the families that go with them. */
    fun loseCivilians(count: Int): PaperBase {
        if (count <= 0) return this
        val left = (civilians - count).coerceAtLeast(0)
        val size = familySize().coerceAtLeast(1.0)
        return copy(civilians = left, families = (left / size).toInt().coerceAtLeast(0))
    }

    /**
     * Applies battle damage to the drawing.
     *
     * ## Nothing is ever deleted
     *
     * This used to drop a building that reached zero, which is the obvious reading of "rubbed out"
     * and was catastrophically wrong. It is only ever called on the PLAYER'S base, when an AI raid
     * has come in while they were away, and a raid that flattens everything therefore deleted the
     * whole base permanently — every building, at any level, with no way back. A level-500 player
     * came back to a blank page with no town hall, no training camps (so a soldier cap of zero, so
     * no army ever again) and no housing, holding two hundred million XP and twenty-eight provinces
     * they could do nothing with. That is not a defeat, it is a destroyed save.
     *
     * A raid is now what it is in every game of this shape: buildings are knocked down to a shell
     * and [repair] brings them back over the following minutes. The loss is real while it lasts —
     * the defences are flat, production stops, and an attacker who comes again straight away walks
     * in — but it is a setback rather than an ending.
     *
     * [demolish] is still how a building actually goes away, and that is the player's own decision.
     */
    fun applyDamage(damage: Map<String, Int>): PaperBase = copy(
        buildings = buildings.map { building ->
            val taken = damage[building.id] ?: return@map building
            building.copy(hitPoints = (building.hitPoints - taken).coerceAtLeast(1))
        }
    )

    /** Repairs everything, slowly, between raids. A base that never heals is a base abandoned. */
    fun repair(fraction: Double): PaperBase = copy(
        buildings = buildings.map {
            val max = it.maxHitPoints
            it.copy(hitPoints = (it.hitPoints + (max * fraction).toInt()).coerceAtMost(max))
        }
    )

    /**
     * Every builder drops what they were doing and starts fixing the damage.
     *
     * Called the moment a battle over this base ends. The passive trickle from [repair] (a
     * fraction of a percent every fifteen seconds) is the right pace for a base nobody is thinking
     * about, but a base that has just been fought over is exactly the moment a player IS thinking
     * about it, and coming back to find the wreckage sitting there untouched for minutes reads as
     * indifference rather than as a country picking itself back up.
     *
     * More builders means more gets fixed at once -- a base with two builders' huts recovers a
     * smaller share of the damage than one with a dozen, which is the same logic the rest of the
     * game uses for what a builder is worth. Capped well short of instant, so a real fight is still
     * a real setback and not a cosmetic pause.
     */
    fun postBattleRepair(): PaperBase {
        if (builders <= 0 || buildings.none { it.complete && it.hitPoints < it.maxHitPoints }) return this
        val fraction = (builders * 0.10).coerceIn(0.0, 0.75)
        return repair(fraction)
    }

    /**
     * A WMD strike: [fraction] of every finished building's hit points are gone, all at once.
     *
     * ## Why this is not [applyDamage]
     *
     * [applyDamage] is ordinary battle damage and never lets a building fall below one hit point,
     * because that method runs on the player's own persisted base and the bug that once erased an
     * entire save by doing exactly that is the reason it never will again (see the history on
     * [WorldMap.addMissingEssentials]). A nuke or an antimatter drop is a different kind of event:
     * [WeaponCatalog.Wmd.destruction] is total, and a strike that quietly stopped one point short of
     * actually flattening the target would not be doing what a player who chose to use one expects.
     *
     * So this DOES take a building to zero. That is still not deletion — the entry stays in
     * [buildings], excluded from [standing] the same way any wrecked building already is, and
     * [repair] already handles bringing a building back from zero exactly as well as it handles
     * bringing one back from half; nothing about "the building is gone" needs the game to remember
     * what it used to be, because it is still sitting right there in the list waiting to be rebuilt.
     * Nothing under this roof is ever actually forgotten.
     *
     * A building site (not yet [PlacedBuilding.complete]) is left alone: there is no standing
     * structure there yet for a warhead to flatten.
     */
    fun devastate(fraction: Double): PaperBase {
        if (fraction <= 0.0) return this
        return copy(
            buildings = buildings.map { building ->
                if (!building.complete) return@map building
                val left = (building.hitPoints * (1.0 - fraction.coerceIn(0.0, 1.0))).toInt().coerceAtLeast(0)
                building.copy(hitPoints = left)
            }
        )
    }

    /** The number the world map shows next to a country's name. */
    fun strength(): Long {
        val fromBuildings = standing.sumOf { (it.type?.hitPoints ?: 0).toLong() }
        val fromArmy = soldiers.toLong() * infantryWeapon().threat
        return fromBuildings + fromArmy
    }

    companion object {

        /** XP one civilian pays per minute before any law touches it. */
        const val BASE_TAX_PER_CIVILIAN = 0.45

        /** People who move into empty housing each minute, per empty place. */
        const val ARRIVALS_PER_EMPTY_HOME = 0.16

        /** The floor under that, so even a full country still sees the occasional newcomer. */
        const val CIVILIANS_PER_MINUTE = 1.0

        /** What makes an infrastructure building a road rather than a structure. */
        val ROAD_WORDS = listOf("road", "street", "highway", "way", "track", "lane", "motorway", "maglev", "roadway")

        /** Food one civilian eats a minute. The farms have to beat this or the country shrinks. */
        const val FOOD_PER_CIVILIAN = 0.08

        /** Children per family per minute at exactly enough food. Doubles at a full surplus. */
        const val BIRTHS_PER_FAMILY_PER_MINUTE = 0.05

        /**
         * Buildings that teach.
         *
         * Named rather than detected by category, because RESEARCH also holds laboratories and
         * proving grounds — a ballistics laboratory is not a school and should not make families
         * smaller or civilians richer.
         */
        val SCHOOL_IDS = setOf(
            "song_school", "elementary_song_school", "petty_school", "writing_school",
            "abacus_school", "grammar_school", "chantry_school", "cathedral_school",
            "monastic_school", "guild_apprentice_hall", "studium_generale", "university",
            "school", "nursery", "primary_school_public", "primary_school_private",
            "secondary_school_public", "secondary_school_private", "grammar_school_modern",
            "college_modern", "polytechnic", "teacher_college", "night_school",
            "technical_college", "university_modern",
            "creche_pod", "primary_lattice", "secondary_lattice", "neural_college",
            "open_university", "apprentice_sim", "polymath_institute", "uplift_academy",
            "neural_academy", "education_ring",
        )

        /**
         * How many crewed detachments may go out at once.
         *
         * Five, so armour, both kinds of aircraft, guns and launchers can all be present without a
         * formation becoming a parade of one of everything in the catalogue.
         */
        const val MAX_DETACHMENTS = 5

        /**
         * What share of the army each detachment takes, as a fraction.
         *
         * Small, because a vehicle stands in for a soldier rather than being added to them: an army
         * of five hundred that fields armour is not five hundred soldiers plus tanks, it is soldiers
         * who have been given tanks instead. Six per cent each keeps the bulk of any formation on
         * foot, which is both right and what makes the vehicles read as special when they appear.
         */
        const val DETACHMENT_SHARE = 0.06

        /** XP per second of construction or deliberation skipped. */
        const val RUSH_XP_PER_SECOND = 1.6

        /**
         * Buildings that count as a university for law research.
         *
         * Named rather than detected by category, because plenty of RESEARCH buildings are a shed
         * with a book in it and a country should not get to rewrite its constitution because it
         * built a herbal scriptorium.
         */
        val UNIVERSITY_IDS = setOf("university", "studium_generale", "university_modern", "uplift_academy")

        /**
         * The opening position: a town hall that has just been flattened.
         *
         * The design opens with an enemy raid destroying your level-1 town hall, so the first thing
         * a player ever sees is a ruin and a builder. That is a deliberately strong opening — it
         * gives the first tap ("rebuild") a reason, and it introduces the enemy before it
         * introduces the menu.
         */
        fun newGame(name: String, now: Long): PaperBase = PaperBase(
            name = name,
            xp = 260,
            peakXp = 260,
            razed = true,
            lastTaxAt = now,
            families = 0,
            soldiers = 0,
            buildings = listOf(
                PlacedBuilding(
                    id = "builders_hut#0#$now",
                    typeId = BuildingCatalog.BUILDERS_HUT.id,
                    x = 3, y = 9,
                    hitPoints = BuildingCatalog.BUILDERS_HUT.hitPoints,
                    maxHitPoints = BuildingCatalog.BUILDERS_HUT.hitPoints,
                    startedAt = now, finishesAt = now, complete = true,
                ),
            ),
        )
    }
}

/**
 * One building, somewhere on the page.
 *
 * Coordinates are grid cells, not pixels: the paper is ruled, the drawing snaps to the ruling, and
 * a battle needs integer distances to be worth simulating.
 */
data class PlacedBuilding(
    val id: String,
    val typeId: String,
    val x: Int,
    val y: Int,
    val hitPoints: Int,
    val maxHitPoints: Int,
    val startedAt: Long,
    val finishesAt: Long,
    val complete: Boolean = false,
    /**
     * For a road: the two buildings it runs between.
     *
     * A road is the one thing in the catalogue that is not a building on a square — it is a line
     * between two places, and drawing it as a one-cell box was why roads looked like sheds. The
     * ends are stored rather than recomputed so a road does not re-route itself when something is
     * demolished; it just stops going anywhere useful, which is what happens to real roads.
     */
    val connectsFrom: String? = null,
    val connectsTo: String? = null,
) {
    val isRoad: Boolean get() = type?.category == BuildingCatalog.Category.INFRASTRUCTURE &&
        connectsFrom != null && connectsTo != null
    val type: BuildingCatalog.BuildingType? get() = BuildingCatalog.byId(typeId)

    val footprint: Int get() = type?.footprint ?: 1

    val damaged: Boolean get() = hitPoints < maxHitPoints

    /** 0..1 through its build timer, for the pencil-sketch scaffolding overlay. */
    fun buildProgress(now: Long): Double {
        if (complete) return 1.0
        val span = (finishesAt - startedAt).coerceAtLeast(1L)
        return ((now - startedAt).toDouble() / span).coerceIn(0.0, 1.0)
    }

    fun centreX(): Double = x + footprint / 2.0
    fun centreY(): Double = y + footprint / 2.0
}
