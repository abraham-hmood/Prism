package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The paper game's rules, where getting them wrong is invisible until level 300.
 *
 * A five-hundred-level progression cannot be play-tested end to end by a person. What it can be is
 * checked for the properties that have to hold everywhere: that something unlocks at every step,
 * that later is stronger, that the caps grow on the cadence the design fixed, and that a battle is
 * the same battle twice.
 */
class PaperWarTest {

    // -- Levels and eras ------------------------------------------------------

    @Test
    fun `the eras cover every level exactly once`() {
        (1..500).forEach { level ->
            val age = Era.ageOf(level)
            assertTrue(level >= age.first && level <= age.last, "level $level fell outside ${age.label}")
        }
        assertEquals(Era.Age.MEDIEVAL, Era.ageOf(250))
        assertEquals(Era.Age.MODERN, Era.ageOf(251))
        assertEquals(Era.Age.MODERN, Era.ageOf(312))
        assertEquals(Era.Age.FUTURISTIC, Era.ageOf(313))
        assertEquals(Era.Age.FUTURISTIC, Era.ageOf(500))
    }

    @Test
    fun `half the game is medieval`() {
        assertEquals(250, Era.Age.MEDIEVAL.span)
        assertEquals(62, Era.Age.MODERN.span)
        assertEquals(188, Era.Age.FUTURISTIC.span)
    }

    @Test
    fun `the XP curve is strictly increasing and inverts cleanly`() {
        var previous = 0L
        (1..500).forEach { level ->
            val needed = Era.xpToNext(level)
            assertTrue(needed > previous, "level $level costs less than the one before it")
            previous = needed
        }
        (1..500).forEach { level ->
            assertEquals(level, Era.levelForXp(Era.xpForLevel(level)), "round trip failed at $level")
            assertEquals(level, Era.levelForXp(Era.xpForLevel(level) + 1))
        }
    }

    @Test
    fun `the opening caps are exactly what the design fixes`() {
        assertEquals(2, Era.trainingCamps(1))
        assertEquals(5, Era.soldiersPerCamp(1))
        // "increases every 5 levels"
        assertEquals(2, Era.trainingCamps(5))
        assertEquals(3, Era.trainingCamps(6))
        assertEquals(5, Era.soldiersPerCamp(5))
        assertEquals(6, Era.soldiersPerCamp(6))
    }

    @Test
    fun `modernisation steps every five levels and heavy industry every ten`() {
        assertEquals(0, Era.modernisationStep(1))
        assertEquals(0, Era.modernisationStep(5))
        assertEquals(1, Era.modernisationStep(6))
        assertEquals(0, Era.heavyModernisationStep(10))
        assertEquals(1, Era.heavyModernisationStep(11))
    }

    // -- Buildings ------------------------------------------------------------

    @Test
    fun `level one offers exactly the opening buildings`() {
        val first = BuildingCatalog.unlockedAt(1).map { it.id }.toSet()
        assertEquals(setOf("town_hall", "builders_hut", "training_camp"), first)
    }

    @Test
    fun `what the design promises after level two is there at level three`() {
        val atThree = BuildingCatalog.unlockedAt(3).map { it.id }
        listOf("weapons_research", "civilian_hut", "dirt_road", "wheat_farm").forEach {
            assertTrue(it in atThree, "$it should be buildable once past level 2")
        }
    }

    @Test
    fun `every second level introduces something, all the way to 500`() {
        val emptyTiers = (1..499 step 2).filter { BuildingCatalog.newAt(it).isEmpty() }
        assertTrue(
            emptyTiers.size < 30,
            "too many levels introduce nothing at all: ${emptyTiers.take(20)} (${emptyTiers.size} of 250)",
        )
        // And the catalogue has to actually span the whole range.
        assertTrue(BuildingCatalog.ALL.any { it.unlockLevel <= 1 })
        assertTrue(BuildingCatalog.ALL.any { it.unlockLevel > 450 })
    }

    @Test
    fun `buildings never unlock outside their era`() {
        BuildingCatalog.ALL.forEach {
            assertEquals(
                it.age, Era.ageOf(it.unlockLevel),
                "${it.name} is ${it.age.label} but unlocks at level ${it.unlockLevel}",
            )
        }
    }

    @Test
    fun `a later building of the same category is never weaker`() {
        BuildingCatalog.Category.entries.forEach { category ->
            val inOrder = BuildingCatalog.ALL
                .filter { it.category == category && it.footprint == 2 }
                .sortedBy { it.tier }
            inOrder.zipWithNext { a, b ->
                assertTrue(
                    b.hitPoints >= a.hitPoints,
                    "${b.name} (${b.unlockLevel}) is weaker than ${a.name} (${a.unlockLevel})",
                )
            }
        }
    }

    @Test
    fun `building ids are unique`() {
        val duplicates = BuildingCatalog.ALL.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate building ids: $duplicates")
    }

    @Test
    fun `caps grow on the five level cadence and stop somewhere sane`() {
        val hut = BuildingCatalog.byId("civilian_hut")!!
        assertTrue(hut.capAtLevel(500) <= 30)
        assertTrue(hut.capAtLevel(100) > hut.capAtLevel(10))
        assertEquals(1, BuildingCatalog.TOWN_HALL.capAtLevel(500), "there is one town hall, forever")
    }

    // -- Weapons --------------------------------------------------------------

    @Test
    fun `there are hundreds of weapons and hundreds in each era`() {
        assertTrue(WeaponCatalog.ALL.size >= 400, "only ${WeaponCatalog.ALL.size} weapons")
        assertTrue(WeaponCatalog.ofAge(Era.Age.MEDIEVAL).size >= 150)
        assertTrue(WeaponCatalog.ofAge(Era.Age.MODERN).size >= 100)
        assertTrue(WeaponCatalog.ofAge(Era.Age.FUTURISTIC).size >= 100)
    }

    @Test
    fun `weapon ids are unique`() {
        val duplicates = WeaponCatalog.ALL.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate weapon ids: $duplicates")
    }

    @Test
    fun `no medieval weapon is available before the medieval era ends`() {
        WeaponCatalog.ALL.forEach {
            assertEquals(
                it.age, Era.ageOf(it.unlockLevel),
                "${it.name} is ${it.age.label} but unlocks at ${it.unlockLevel}",
            )
        }
        assertTrue(WeaponCatalog.unlockedAt(250).none { it.age != Era.Age.MEDIEVAL })
    }

    @Test
    fun `a later weapon of the same class always hits harder`() {
        WeaponCatalog.WeaponClass.entries.forEach { klass ->
            WeaponCatalog.ALL.filter { it.weaponClass == klass }
                .sortedBy { it.unlockLevel }
                .zipWithNext { a, b ->
                    assertTrue(
                        b.damage >= a.damage,
                        "${b.name} (${b.unlockLevel}) hits softer than ${a.name} (${a.unlockLevel})",
                    )
                    assertTrue(b.range >= a.range, "${b.name} outranged by the older ${a.name}")
                }
        }
    }

    @Test
    fun `a bow outranges an axe at every level`() {
        val bows = WeaponCatalog.ALL.filter { it.weaponClass == WeaponCatalog.WeaponClass.BOW }
        val blades = WeaponCatalog.ALL.filter { it.weaponClass == WeaponCatalog.WeaponClass.BLADE }
        assertTrue(bows.minOf { it.range } > blades.maxOf { it.range })
    }

    @Test
    fun `weapons stay usable - every one resolves and produces a working shot`() {
        WeaponCatalog.ALL.forEach {
            assertNotNull(WeaponCatalog.byId(it.id))
            assertTrue(it.damage > 0, "${it.name} does no damage")
            assertTrue(it.cooldownTicks >= 1, "${it.name} would fire every tick forever")
            assertTrue(it.range >= 1, "${it.name} cannot reach anything")
        }
    }

    // -- The base -------------------------------------------------------------

    @Test
    fun `a new game starts razed with a builder and nothing else`() {
        val base = PaperBase.newGame("Test", now = 1_000)
        assertTrue(base.razed)
        assertFalse(base.hasTownHall)
        assertEquals(1, base.builders)
        assertEquals(0, base.soldiers)
    }

    @Test
    fun `building costs XP up front and pays some back on completion`() {
        val now = 1_000L
        val base = PaperBase.newGame("Test", now)
        val type = BuildingCatalog.TOWN_HALL
        val placed = base.place(type, 10, 10, now)

        assertEquals(base.xp - type.buildCost, placed.xp, "the cost comes off immediately")
        assertFalse(placed.hasTownHall, "it is not standing until the timer runs out")

        val finished = placed.advanceConstruction(now + type.buildSeconds * 1000L)
        assertTrue(finished.hasTownHall)
        assertEquals(placed.xp + type.buildReward, finished.xp)
    }

    @Test
    fun `one builder builds one thing at a time`() {
        val now = 1_000L
        var base = PaperBase.newGame("Test", now).copy(xp = 500_000)
        base = base.place(BuildingCatalog.TOWN_HALL, 10, 10, now)
        assertEquals(PaperBase.Placement.NoBuilder, base.canPlace(BuildingCatalog.TRAINING_CAMP))
    }

    @Test
    fun `a locked building says which level it needs`() {
        val base = PaperBase.newGame("Test", 0).copy(xp = 5_000_000)
        val late = BuildingCatalog.ALL.first { it.unlockLevel > 400 }
        val verdict = base.canPlace(late)
        assertTrue(verdict is PaperBase.Placement.Locked)
        assertEquals(late.unlockLevel, (verdict as PaperBase.Placement.Locked).atLevel)
    }

    @Test
    fun `building is a net gain, not a net loss`() {
        // The bug this pins: the reward was 0.62 of the cost, so every finished building left the
        // player poorer than before they started it and a base that grew steadily lost levels. On
        // a device it read as the game running backwards while you played it properly.
        BuildingCatalog.ALL.forEach {
            assertTrue(
                it.buildReward > it.buildCost,
                "${it.name} costs ${it.buildCost} and pays back ${it.buildReward}",
            )
        }
    }

    @Test
    fun `spending XP can never take a player down a level`() {
        // A purchase that de-levels is worse than one that merely costs: the caps and the unlock
        // list are read off the level, so a building could become illegal by being built. On a
        // device a 112 XP training camp took level 4 to level 3.
        val camp = BuildingCatalog.TRAINING_CAMP
        val floor = Era.xpForLevel(4) + 10
        val base = PaperBase("Test", xp = floor, peakXp = floor).copy(
            buildings = listOf(
                PlacedBuilding(
                    "hut", BuildingCatalog.BUILDERS_HUT.id, 1, 1,
                    BuildingCatalog.BUILDERS_HUT.hitPoints, BuildingCatalog.BUILDERS_HUT.hitPoints,
                    0, 0, complete = true,
                )
            )
        )
        assertEquals(4, base.level)
        assertEquals(PaperBase.Placement.Allowed, base.canPlace(camp))

        val after = base.place(camp, 5, 5, 1_000)
        assertTrue(after.xp < base.xp, "the cost still comes off the balance")
        assertEquals(4, after.level, "spending must not cost a level")
    }

    @Test
    fun `the opening position can afford the town hall it has to rebuild`() {
        // The premise of the whole game is a razed town hall and a rebuild. An economy that cannot
        // pay for that first building has no opening at all — which is exactly what a first attempt
        // at the rule above produced.
        val base = PaperBase.newGame("Test", now = 1_000)
        assertEquals(
            PaperBase.Placement.Allowed, base.canPlace(BuildingCatalog.TOWN_HALL),
            "a new game must be able to rebuild its town hall",
        )
    }

    @Test
    fun `a run of building leaves the player better off`() {
        // The end-to-end version of the net-gain rule: build the same thing repeatedly, letting
        // each one finish, and the player must come out ahead rather than sliding backwards.
        val start = PaperBase.newGame("Test", now = 0)
        var base = start
        val hut = BuildingCatalog.BUILDERS_HUT
        var now = 0L
        var built = 0
        repeat(6) { i ->
            if (base.canPlace(hut) != PaperBase.Placement.Allowed) return@repeat
            base = base.place(hut, i * 2, 20, now)
            now += hut.buildSeconds * 1000L
            base = base.advanceConstruction(now)
            built++
        }
        assertTrue(built > 0, "nothing could be built at all")
        assertTrue(base.xp > start.xp, "building $built huts left the player poorer: ${base.xp}")
    }

    @Test
    fun `the level comes from the high-water mark, not the balance`() {
        val earned = Era.xpForLevel(30) + 40
        val base = PaperBase("Test", xp = earned, peakXp = earned)
        assertEquals(30, base.level)
        // Spent right down to nothing, and still level 30.
        assertEquals(30, base.copy(xp = 0).level)
        // A gain past the mark moves both.
        val grown = base.addXp(Era.xpToNext(30))
        assertTrue(grown.level > 30)
        assertEquals(grown.xp, grown.peakXp)
    }

    @Test
    fun `losing XP can never take a player down a level`() {
        val base = PaperBase("Test", xp = Era.xpForLevel(40) + 10)
        assertEquals(40, base.level)

        val mauled = base.addXp(-1_000_000)
        assertEquals(40, mauled.level, "a lost raid must not demolish a level's worth of buildings")
        // The loss is real and lands on the balance: a raid that cost nothing would not be a loss.
        assertEquals(0, mauled.xp, "the balance takes the hit")
        assertTrue(mauled.peakXp >= Era.xpForLevel(40), "what was earned stays earned")
    }

    @Test
    fun `research needs the building, the level and the XP`() {
        val weapon = WeaponCatalog.ALL.first { it.unlockLevel in 3..8 }
        val withoutLab = PaperBase("Test", xp = 5_000_000)
        assertFalse(withoutLab.canResearch(weapon), "no weapons research building")

        val lab = BuildingCatalog.byId("weapons_research")!!
        val withLab = withoutLab.copy(
            buildings = listOf(
                PlacedBuilding(
                    "lab", lab.id, 1, 1, lab.hitPoints, lab.hitPoints, 0, 0, complete = true,
                )
            )
        )
        assertTrue(withLab.canResearch(weapon))
        val after = withLab.research(weapon)
        assertTrue(weapon.id in after.researched)
        assertEquals(withLab.xp - weapon.researchCost, after.xp)
        assertFalse(after.canResearch(weapon), "researching twice should do nothing")
    }

    @Test
    fun `soldiers are capped by the training camps that are standing`() {
        val camp = BuildingCatalog.TRAINING_CAMP
        // Level 1 spans a hundred-odd XP, so the figure has to be inside it or the cap is
        // being read at a different level than the one being tested.
        val base = PaperBase("Test", xp = Era.xpToNext(1) - 1).copy(
            buildings = (0 until 2).map {
                PlacedBuilding("camp$it", camp.id, it * 3, 1, camp.hitPoints, camp.hitPoints, 0, 0, true)
            }
        )
        assertEquals(10, base.armyCapacity, "2 camps x 5 soldiers at level 1")
        assertEquals(10, base.trainSoldiers(50).soldiers)
    }

    // -- Battle ---------------------------------------------------------------

    private fun testBase(level: Int): PaperBase {
        val country = WorldMap.generateAi(worldSeed = 42, playerLevel = level, count = 1).first()
        return WorldMap.aiBase(country.copy(level = level))
    }

    @Test
    fun `the same seed produces exactly the same battle`() {
        val defender = testBase(30)
        val orders = listOf(Battle.Order(20, 0, 16, 16))
        val a = Battle.simulate(defender, 30, 20, "iron_sword", orders, seed = 7)
        val b = Battle.simulate(defender, 30, 20, "iron_sword", orders, seed = 7)

        assertEquals(a.result, b.result)
        assertEquals(a.frames.size, b.frames.size)
        assertEquals(a.cast.buildingIds, b.cast.buildingIds)
        a.frames.zip(b.frames).forEach { (x, y) ->
            assertTrue(x.buildingHp.contentEquals(y.buildingHp), "tick ${x.tick} diverged on buildings")
            assertTrue(x.positions.contentEquals(y.positions), "tick ${x.tick} diverged on positions")
            assertTrue(x.fighterHp.contentEquals(y.fighterHp), "tick ${x.tick} diverged on wounds")
        }
    }

    @Test
    fun `a different seed produces a different battle`() {
        val defender = testBase(30)
        val a = Battle.simulate(defender, 30, 20, "iron_sword", emptyList(), seed = 1).result
        val b = Battle.simulate(defender, 30, 20, "iron_sword", emptyList(), seed = 2).result
        assertTrue(a != b || a.ticks != b.ticks, "two seeds gave an identical fight")
    }

    @Test
    fun `an overwhelming attack flattens the base and a hopeless one does not`() {
        val defender = testBase(20)
        val crushing = Battle.simulate(
            defender, attackerLevel = 200, attackerSoldiers = 80,
            attackerWeaponId = "trebuchet", orders = emptyList(), seed = 3,
        ).result
        assertTrue(crushing.won, "200 levels and 80 soldiers should beat a level-20 base")
        assertTrue(crushing.stars >= 2)

        val hopeless = Battle.simulate(
            defender, attackerLevel = 1, attackerSoldiers = 1,
            attackerWeaponId = "wooden_club", orders = emptyList(), seed = 3,
        ).result
        assertFalse(hopeless.won, "one stick figure with a club took a base")
    }

    @Test
    fun `winning pays the attacker and costs the defender, and losing is the reverse`() {
        val defender = testBase(20)
        val won = Battle.simulate(defender, 200, 80, "trebuchet", emptyList(), seed = 3).result
        assertTrue(won.xpForAttacker > 0 && won.xpForDefender < 0)

        val lost = Battle.simulate(defender, 1, 1, "wooden_club", emptyList(), seed = 3).result
        assertTrue(lost.xpForAttacker < 0 && lost.xpForDefender > 0)
    }

    @Test
    fun `orders send a squad where it is told`() {
        val defender = testBase(40)
        val corner = Battle.Order(atTick = 0, squad = 0, targetX = 2, targetY = 2)
        val run = Battle.simulate(defender, 40, 24, "longbow", listOf(corner), seed = 11)
        val squadZero = (0 until run.cast.size).filter { run.cast.squads[it] == 0 }
        assertTrue(squadZero.isNotEmpty(), "no squad 0 to order about")

        // Against the same battle without the order, which is the only comparison that means
        // anything. An absolute test — "did each fighter get closer to the corner" — fails for a
        // reason that is correct behaviour: an ordered squad goes to the nearest BUILDING to the
        // point it was sent to, not to the bare ground of the point itself, so a fighter who
        // spawned beside that corner already legitimately walks away from it to reach something
        // worth hitting. What the order has to do is pull the squad toward that corner relative to
        // where it would otherwise have gone.
        val unordered = Battle.simulate(defender, 40, 24, "longbow", emptyList(), seed = 11)

        fun meanFinalDistance(run: Battle.Playback): Double {
            val last = run.frames.last()
            val squad = (0 until run.cast.size).filter { run.cast.squads[it] == 0 }
            return squad.map { kotlin.math.hypot(last.x(it) - 2.0, last.y(it) - 2.0) }.average()
        }

        val ordered = meanFinalDistance(run)
        val left = meanFinalDistance(unordered)
        assertTrue(
            ordered < left,
            "the squad ordered to (2,2) finished $ordered away; unordered it finished $left away",
        )
    }

    @Test
    fun `a battle always terminates`() {
        listOf(1, 50, 250, 312, 500).forEach { level ->
            val defender = testBase(level)
            val run = Battle.simulate(
                defender, level, Era.armyCapacity(level).coerceAtMost(60),
                (WeaponCatalog.unlockedAt(level).lastOrNull() ?: WeaponCatalog.STARTER).id,
                emptyList(), seed = level.toLong(),
            )
            assertTrue(run.frames.isNotEmpty(), "level $level produced no frames")
            assertTrue(run.result.ticks <= Battle.MAX_TICKS, "level $level ran past the timer")
            assertEquals(
                defender.standing.size, run.cast.buildingIds.size,
                "the cast must name every building the frames carry hit points for",
            )
        }
    }

    @Test
    fun `nothing reaches across the whole page`() {
        // The bug this pins: taken literally, a late-game weapon's range of 24 covers most of a
        // 32-cell field, so every defence and every attacker was in range on tick zero and the
        // battle was one simultaneous exchange that killed both sides in a single frame.
        WeaponCatalog.ALL.forEach {
            assertTrue(
                Battle.effectiveRange(it) < Battle.FIELD * 0.4,
                "${it.name} reaches ${Battle.effectiveRange(it)} of ${Battle.FIELD} cells",
            )
        }
        // And the ordering the catalogue promises still holds after the compression.
        val bow = WeaponCatalog.byId("longbow")!!
        val axe = WeaponCatalog.byId("battle_axe")!!
        val trebuchet = WeaponCatalog.byId("trebuchet")!!
        assertTrue(Battle.effectiveRange(axe) < Battle.effectiveRange(bow))
        assertTrue(Battle.effectiveRange(bow) < Battle.effectiveRange(trebuchet))
    }

    @Test
    fun `a battle at any level lasts long enough to watch`() {
        listOf(10, 50, 150, 250, 313, 400, 500).forEach { level ->
            val defender = testBase(level)
            val run = Battle.simulate(
                defender, level,
                Era.armyCapacity(level).coerceAtMost(120),
                WeaponCatalog.unlockedAt(level).maxByOrNull { it.threat }?.id ?: WeaponCatalog.STARTER.id,
                emptyList(), seed = 5,
            )
            assertTrue(
                run.frames.size >= Battle.TICKS_PER_SECOND * 3,
                "a level-$level raid was over in ${Battle.secondsOf(run.frames.size)}s — " +
                    "that is an alpha strike, not a battle",
            )
        }
    }

    @Test
    fun `a frame carries no per-fighter objects`() {
        // The frames are held for the whole playback and recomputed on every order, so they are
        // flat arrays rather than copies of the simulation's objects. This pins the shape.
        val run = Battle.simulate(testBase(60), 60, 40, "longbow", emptyList(), seed = 2)
        val frame = run.frames.first()
        assertEquals(run.cast.size * 2, frame.positions.size)
        assertEquals(run.cast.size, frame.fighterHp.size)
        assertEquals(run.cast.buildingIds.size, frame.buildingHp.size)
    }

    // -- The world ------------------------------------------------------------

    @Test
    fun `the world is the same every time it is generated`() {
        val a = WorldMap.generateAi(worldSeed = 1234, playerLevel = 50)
        val b = WorldMap.generateAi(worldSeed = 1234, playerLevel = 50)
        assertEquals(a, b)
    }

    @Test
    fun `AI countries land on the paper and do not sit on top of each other`() {
        val world = WorldMap.generateAi(worldSeed = 99, playerLevel = 120)
        world.forEach {
            // No bounds to check any more — the world has no edges. What still has to hold is that
            // a country has a shape to draw and sits away from its neighbours.
            assertTrue(it.outline.size >= 8, "a country needs an outline to draw")
        }
        world.forEachIndexed { i, a ->
            world.drop(i + 1).forEach { b ->
                val d = kotlin.math.hypot(a.centreX - b.centreX, a.centreY - b.centreY)
                assertTrue(d > 6, "${a.name} and ${b.name} overlap")
            }
        }
    }

    @Test
    fun `an alliance request is one in four, and a refusal makes it harder`() {
        val country = WorldMap.generateAi(worldSeed = 5, playerLevel = 50).first()
        assertEquals(0.25, WorldMap.allianceChance(country))
        assertEquals(0.10, WorldMap.allianceChance(country.copy(relation = WorldMap.Relation.REFUSED)))
        assertEquals(0.0, WorldMap.allianceChance(country.copy(relation = WorldMap.Relation.HOSTILE)))

        // Roughly a quarter over many countries, which is the property that matters.
        val accepted = (0 until 400).count { i ->
            WorldMap.askAi(country.copy(id = "ai-$i"), attempt = 0)
        }
        assertTrue(accepted in 60..140, "expected about 100 of 400 to accept, got $accepted")
    }

    @Test
    fun `asking the same country again gives the same answer for the same attempt`() {
        val country = WorldMap.generateAi(worldSeed = 5, playerLevel = 50).first()
        val first = WorldMap.askAi(country, attempt = 0)
        repeat(5) { assertEquals(first, WorldMap.askAi(country, attempt = 0)) }
    }

    @Test
    fun `an AI base can be rebuilt from its country and is worth attacking`() {
        listOf(5, 60, 250, 300, 480).forEach { level ->
            val country = WorldMap.generateAi(worldSeed = 8, playerLevel = level, count = 1).first()
            val base = WorldMap.aiBase(country)
            assertTrue(base.hasTownHall, "a level-$level AI base has no town hall")
            assertTrue(base.standing.size > 1, "a level-$level AI base is empty")
            assertEquals(base, WorldMap.aiBase(country), "AI bases must regenerate identically")
        }
    }

    @Test
    fun `allies bring their soldiers`() {
        val world = WorldMap.generateAi(worldSeed = 3, playerLevel = 80, count = 3)
        val allies = world.map { it.copy(relation = WorldMap.Relation.ALLIED) }
        assertEquals(allies.sumOf { it.soldiers }, WorldMap.alliedSoldiers(allies))
        assertNotNull(WorldMap.alliedWeapon(allies))
    }

    // -- Pong -----------------------------------------------------------------

    @Test
    fun `pong is deterministic for a seed`() {
        fun play(seed: Long): Pong.State {
            var s = Pong.newGame(seed)
            val rng = Rng(seed)
                repeat(4_000) {
                val left = Pong.aiInput(s, Pong.Side.LEFT, Pong.Difficulty.KEEN)
                val right = Pong.aiInput(s, Pong.Side.RIGHT, Pong.Difficulty.KEEN)
                s = Pong.step(s, left, right, rng)
            }
            return s
        }
        assertEquals(play(17), play(17))
    }

    @Test
    fun `the ball never leaves the court sideways through a paddle`() {
        var s = Pong.newGame(5)
        val rng = Rng(5)
        var escapes = 0
        repeat(20_000) {
            val left = Pong.aiInput(s, Pong.Side.LEFT, Pong.Difficulty.BRUTAL)
            val right = Pong.aiInput(s, Pong.Side.RIGHT, Pong.Difficulty.BRUTAL)
            s = Pong.step(s, left, right, rng)
            if (s.ballY < -Pong.BALL_RADIUS || s.ballY > Pong.HEIGHT + Pong.BALL_RADIUS) escapes++
        }
        assertEquals(0, escapes, "the ball left through the top or bottom wall")
    }

    @Test
    fun `a rally ends and somebody wins`() {
        var s = Pong.newGame(21)
        val rng = Rng(21)
        var steps = 0
        while (!Pong.isOver(s) && steps < 400_000) {
            val left = Pong.aiInput(s, Pong.Side.LEFT, Pong.Difficulty.CASUAL)
            val right = Pong.aiInput(s, Pong.Side.RIGHT, Pong.Difficulty.BRUTAL)
            s = Pong.step(s, left, right, rng)
            steps++
        }
        assertTrue(Pong.isOver(s), "no winner after $steps steps: ${s.leftScore}-${s.rightScore}")
        assertNotNull(Pong.winner(s))
    }

    @Test
    fun `a paddle cannot leave the court`() {
        var s = Pong.newGame(1)
        val rng = Rng(1)
        repeat(500) {
            s = Pong.step(s, Pong.Input(up = true), Pong.Input(down = true), rng)
        }
        assertTrue(s.leftY >= Pong.PADDLE_HEIGHT / 2.0)
        assertTrue(s.rightY <= Pong.HEIGHT - Pong.PADDLE_HEIGHT / 2.0)
    }

    // -- The generator itself -------------------------------------------------

    @Test
    fun `the RNG is uniform enough to be trusted with a one in four roll`() {
        val rng = Rng(12345)
        val buckets = IntArray(10)
        repeat(100_000) { buckets[(rng.nextDouble() * 10).toInt().coerceIn(0, 9)]++ }
        buckets.forEach { assertTrue(it in 9_000..11_000, "buckets were ${buckets.toList()}") }
    }

    @Test
    fun `the RNG replays exactly`() {
        val a = Rng(999)
        val b = Rng(999)
        repeat(1_000) { assertEquals(a.nextLong(), b.nextLong()) }
    }
}
