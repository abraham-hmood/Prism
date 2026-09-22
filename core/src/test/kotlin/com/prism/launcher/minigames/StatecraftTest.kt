package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Government, civilians, conquest and fast-forward.
 *
 * These four systems all write to the same XP number from different directions — a tax pays in, a
 * law costs, a rush costs, a conquest pays in — so the thing worth pinning is that each one moves
 * it in the direction it claims to and that none of them can be turned into a loop that prints
 * XP for free.
 */
class StatecraftTest {

    private fun withUniversity(level: Int, xp: Long): PaperBase {
        val uni = BuildingCatalog.byId("studium_generale") ?: BuildingCatalog.byId("university")!!
        return PaperBase(
            "Test", xp = xp, peakXp = maxOf(xp, Era.xpForLevel(level)),
            buildings = listOf(
                PlacedBuilding("uni", uni.id, 4, 4, uni.hitPoints, uni.hitPoints, 0, 0, complete = true)
            ),
        )
    }

    // -- Laws -----------------------------------------------------------------

    @Test
    fun `every law belongs to a group that exists, and every group has options`() {
        Ideology.ALL_LAWS.forEach {
            assertNotNull(Ideology.groupOf(it.groupId), "${it.name} names a group that does not exist")
        }
        Ideology.GROUPS.forEach {
            assertTrue(it.laws.size >= 3, "${it.name} has only ${it.laws.size} options")
        }
    }

    @Test
    fun `law ids are unique`() {
        val duplicates = Ideology.ALL_LAWS.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate law ids: $duplicates")
    }

    @Test
    fun `every group starts with a law available from level one`() {
        // Otherwise a new country has no law at all in that group and `lawIn` has nothing to fall
        // back on, which would silently drop the group's effects entirely.
        Ideology.GROUPS.forEach {
            assertEquals(1, it.default.minLevel, "${it.name} has no starting law")
        }
    }

    @Test
    fun `an ideology only names laws that exist`() {
        val known = Ideology.ALL_LAWS.map { it.id }.toSet()
        Ideology.IDEOLOGIES.forEach { stance ->
            (stance.loves + stance.hates).forEach {
                assertTrue(it in known, "${stance.name} refers to the unknown law '$it'")
            }
        }
    }

    @Test
    fun `an ideology never both loves and hates the same law`() {
        Ideology.IDEOLOGIES.forEach {
            val both = it.loves intersect it.hates
            assertTrue(both.isEmpty(), "${it.name} is confused about $both")
        }
    }

    @Test
    fun `a country with no laws is unformed, and enacting gives it a character`() {
        assertEquals("Unformed", Ideology.describeCountry(emptySet()))
        val liberal = setOf("protected_speech", "right_of_assembly", "parliamentary", "census_suffrage")
        assertTrue(
            Ideology.describeCountry(liberal).contains("Liberal"),
            "got ${Ideology.describeCountry(liberal)}",
        )
    }

    @Test
    fun `law effects multiply together rather than replacing each other`() {
        val one = Ideology.effectsOf(setOf("graduated_tax"))
        val two = Ideology.effectsOf(setOf("graduated_tax", "cooperative_ownership"))
        assertTrue(two.taxRate > one.taxRate, "two tax-raising laws should compound")
    }

    @Test
    fun `law research needs a university`() {
        val law = Ideology.ALL_LAWS.first { it.minLevel == 1 && it.groupId == "taxation" }
        val without = PaperBase("Test", xp = 1_000_000, peakXp = 1_000_000)
        assertFalse(without.hasUniversity)
        assertFalse(without.canEnact(law))
        assertEquals(without, without.beginLaw(law, 0), "no university, no law")

        val with = withUniversity(level = 60, xp = 1_000_000)
        assertTrue(with.hasUniversity)
        assertTrue(with.canEnact(law))
    }

    @Test
    fun `a law costs XP, takes time, and only then takes effect`() {
        val law = Ideology.law("graduated_tax")!!
        val base = withUniversity(level = law.minLevel + 5, xp = 5_000_000)
        val started = base.beginLaw(law, now = 1_000)

        assertEquals(law.id, started.lawInProgress)
        assertTrue(started.xp < base.xp, "enacting costs XP up front")
        assertFalse(law.id in started.enactedLaws, "it is not in force while it is being debated")

        val tooEarly = started.advanceLaw(1_000)
        assertEquals(law.id, tooEarly.lawInProgress)

        val passed = started.advanceLaw(started.lawFinishesAt)
        assertTrue(law.id in passed.enactedLaws)
        assertEquals(null, passed.lawInProgress)
    }

    @Test
    fun `only one law can be debated at a time`() {
        val first = Ideology.ALL_LAWS.first { it.minLevel == 1 && it.groupId == "taxation" }
        val second = Ideology.ALL_LAWS.first { it.minLevel == 1 && it.groupId == "trade" }
        val base = withUniversity(level = 50, xp = 5_000_000).beginLaw(first, 0)
        assertFalse(base.canEnact(second))
        assertEquals(base, base.beginLaw(second, 0))
    }

    @Test
    fun `education law changes what weapons research costs`() {
        val weapon = WeaponCatalog.ALL.first { it.unlockLevel in 3..12 }
        val plain = PaperBase("Test", xp = 1_000)
        val schooled = PaperBase("Test", xp = 1_000, enactedLaws = setOf("public_schools"))
        assertTrue(
            schooled.researchCost(weapon) < plain.researchCost(weapon),
            "public schools should make research cheaper",
        )
    }

    // -- Civilians ------------------------------------------------------------

    @Test
    fun `civilians move in toward the housing capacity and never past it`() {
        val hut = BuildingCatalog.byId("civilian_hut")!!
        val base = PaperBase(
            "Test", xp = 0, lastTaxAt = 0,
            buildings = (0 until 3).map {
                PlacedBuilding("h$it", hut.id, it, 0, hut.hitPoints, hut.hitPoints, 0, 0, complete = true)
            },
        ).copy(lastTaxAt = 1_000)

        assertTrue(base.civilianCapacity > 0)
        val later = base.collectTaxes(1_000 + 60 * 60_000L)
        assertTrue(later.civilians > 0, "nobody moved in over an hour")
        assertTrue(
            later.civilians <= later.civilianCapacity,
            "${later.civilians} civilians in housing for ${later.civilianCapacity}",
        )
    }

    @Test
    fun `civilians pay XP over time and an empty country pays nothing`() {
        val populated = PaperBase("Test", xp = 100, civilians = 200, lastTaxAt = 1_000)
        val paid = populated.collectTaxes(1_000 + 10 * 60_000L)
        assertTrue(paid.xp > populated.xp, "two hundred civilians paid nothing in ten minutes")

        val empty = PaperBase("Test", xp = 100, civilians = 0, lastTaxAt = 1_000)
        assertEquals(100, empty.collectTaxes(1_000 + 10 * 60_000L).xp)
    }

    @Test
    fun `an absence pays what it is owed and no more than a day of it`() {
        val base = PaperBase("Test", xp = 0, civilians = 100, lastTaxAt = 0).copy(lastTaxAt = 1)
        val week = base.collectTaxes(1 + 7L * 24 * 60 * 60_000)
        val day = base.collectTaxes(1 + 24L * 60 * 60_000)
        assertEquals(day.xp, week.xp, "a week away should pay the same as a day: the cap")
    }

    @Test
    fun `taxation law changes what civilians pay`() {
        val plain = PaperBase("Test", civilians = 100)
        val taxed = PaperBase("Test", civilians = 100, enactedLaws = setOf("graduated_tax"))
        assertTrue(taxed.civilianTaxPerMinute() > plain.civilianTaxPerMinute())
    }

    // -- Fast forward ---------------------------------------------------------

    @Test
    fun `fast forward costs XP and finishes the building`() {
        val now = 10_000L
        val camp = BuildingCatalog.TRAINING_CAMP
        val base = PaperBase("Test", xp = 500_000, peakXp = 500_000).copy(
            buildings = listOf(
                PlacedBuilding(
                    "hut", BuildingCatalog.BUILDERS_HUT.id, 0, 0,
                    BuildingCatalog.BUILDERS_HUT.hitPoints, BuildingCatalog.BUILDERS_HUT.hitPoints,
                    0, 0, complete = true,
                )
            )
        ).place(camp, 5, 5, now)

        val pending = base.buildings.first { !it.complete }
        val cost = base.rushCostOf(pending, now)
        assertTrue(cost > 0, "a building with time left should cost something to rush")

        val rushed = base.rushBuilding(pending.id, now)
        assertTrue(rushed.buildings.first { it.id == pending.id }.complete, "it did not finish")
        // Paid the rush and received the build reward, so the net is the reward less the rush.
        assertEquals(base.xp - cost + (camp.buildReward), rushed.xp)
    }

    @Test
    fun `fast forward is refused without the XP and does nothing`() {
        val now = 10_000L
        val camp = BuildingCatalog.TRAINING_CAMP
        val base = PaperBase("Test", xp = 500_000, peakXp = 500_000).copy(
            buildings = listOf(
                PlacedBuilding(
                    "hut", BuildingCatalog.BUILDERS_HUT.id, 0, 0, 10, 10, 0, 0, complete = true,
                )
            )
        ).place(camp, 5, 5, now)
        val pending = base.buildings.first { !it.complete }

        val broke = base.copy(xp = 0)
        assertEquals(broke, broke.rushBuilding(pending.id, now), "it should refuse, not part-finish")
    }

    @Test
    fun `rushing is never cheaper than waiting for nothing`() {
        val base = PaperBase("Test")
        assertEquals(0, base.rushCost(0))
        assertTrue(base.rushCost(60_000) > base.rushCost(10_000), "more time should cost more")
    }

    // -- Conquest -------------------------------------------------------------

    @Test
    fun `conquering a country takes its army, people, treasury and defences`() {
        val country = WorldMap.generateAi(worldSeed = 9, playerLevel = 120, count = 1).first()
        val spoils = WorldMap.spoilsOf(country, takenAt = 1_000)

        assertEquals(country.id, spoils.id)
        assertEquals(country.soldiers, spoils.soldiers)
        assertEquals(country.population, spoils.civilians)
        assertTrue(spoils.treasury > 0, "a level-${country.level} country had nothing worth taking")
        assertTrue(spoils.defences > 0, "its defences counted for nothing")

        val before = PaperBase("Test", xp = 1_000, peakXp = 1_000)
        val after = before.annex(spoils)
        assertEquals(1, after.annexed.size)
        assertTrue(after.xp > before.xp, "the treasury was not seized")
        assertTrue(after.tributarySoldiers > 0, "the province sent nobody")
        assertTrue(after.tributaryDefence > 0, "the province defends nothing")
    }

    @Test
    fun `the same country cannot be annexed twice`() {
        val country = WorldMap.generateAi(worldSeed = 9, playerLevel = 60, count = 1).first()
        val spoils = WorldMap.spoilsOf(country, 0)
        val once = PaperBase("Test").annex(spoils)
        assertEquals(once, once.annex(spoils), "annexing twice would print XP")
    }

    @Test
    fun `a new province is worth little and settles over time`() {
        val country = WorldMap.generateAi(worldSeed = 11, playerLevel = 100, count = 1).first()
        val base = PaperBase("Test").annex(WorldMap.spoilsOf(country, 0))
        val fresh = base.annexed.first()
        assertTrue(fresh.loyalty < 0.5, "a country taken by force is not immediately loyal")

        val settled = fresh.settle(60.0 * 24)
        assertTrue(settled.loyalty > fresh.loyalty)
        assertTrue(settled.loyalty <= 1.0, "loyalty must not run past one")
        assertTrue(settled.taxPerMinute() > fresh.taxPerMinute())
    }

    @Test
    fun `provinces pay their owner, and releasing one stops it`() {
        val country = WorldMap.generateAi(worldSeed = 13, playerLevel = 150, count = 1).first()
        val held = PaperBase("Test", civilians = 0, lastTaxAt = 1)
            .annex(WorldMap.spoilsOf(country, 0))
        assertTrue(held.civilianTaxPerMinute() > 0, "a province with people pays nothing")

        val released = held.releaseAnnexed(country.id)
        assertTrue(released.annexed.isEmpty())
        assertEquals(0.0, released.civilianTaxPerMinute())
    }

    // -- Content --------------------------------------------------------------

    @Test
    fun `there are hundreds of buildings in every era`() {
        Era.Age.entries.forEach { age ->
            val count = BuildingCatalog.ALL.count { it.age == age }
            assertTrue(count >= 200, "${age.label} has only $count buildings")
        }
    }

    @Test
    fun `market towns, farms and fortifications all exist in every era`() {
        listOf(
            BuildingCatalog.Category.TRADE,
            BuildingCatalog.Category.AGRICULTURE,
            BuildingCatalog.Category.FORTIFICATION,
        ).forEach { category ->
            Era.Age.entries.forEach { age ->
                val count = BuildingCatalog.ALL.count { it.category == category && it.age == age }
                assertTrue(count >= 8, "${age.label} has only $count ${category.label} buildings")
            }
        }
    }

    @Test
    fun `there are hundreds of weapons in every era`() {
        Era.Age.entries.forEach { age ->
            val count = WeaponCatalog.ALL.count { it.age == age }
            assertTrue(count >= 300, "${age.label} has only $count weapons")
        }
    }

    @Test
    fun `fortifications defend`() {
        val walls = BuildingCatalog.ALL.filter { it.category == BuildingCatalog.Category.FORTIFICATION }
        assertTrue(walls.isNotEmpty())
        assertTrue(walls.all { it.isDefensive }, "a wall that does not fight is scenery")
    }

    // -- AI defences ----------------------------------------------------------

    @Test
    fun `an AI country's defences scale with its level`() {
        fun defensiveShare(level: Int): Double {
            val country = WorldMap.generateAi(worldSeed = 77, playerLevel = level, count = 1).first()
            val base = WorldMap.aiBase(country.copy(level = level))
            if (base.standing.isEmpty()) return 0.0
            return base.standing.count { it.type?.isDefensive == true }.toDouble() / base.standing.size
        }

        val early = defensiveShare(20)
        val middle = defensiveShare(150)
        val late = defensiveShare(420)
        assertTrue(middle > early, "a level-150 country should be better defended than a level-20 one")
        assertTrue(late > middle, "a level-420 country should be better defended than a level-150 one")
    }

    @Test
    fun `an AI country fights with weapons from its own era`() {
        mapOf(60 to Era.Age.MEDIEVAL, 280 to Era.Age.MODERN, 420 to Era.Age.FUTURISTIC)
            .forEach { (level, age) ->
                val country = WorldMap.generateAi(worldSeed = 5, playerLevel = level, count = 1).first()
                val base = WorldMap.aiBase(country.copy(level = level))
                assertEquals(
                    age, base.garrisonWeapon().age,
                    "a level-$level country garrisons with ${base.garrisonWeapon().name}",
                )
            }
    }

    @Test
    fun `an AI base is never empty, at any level`() {
        listOf(1, 5, 20, 90, 250, 313, 500).forEach { level ->
            val country = WorldMap.generateAi(worldSeed = 3, playerLevel = level, count = 1).first()
            val base = WorldMap.aiBase(country.copy(level = level))
            assertTrue(base.hasTownHall, "a level-$level country has no town hall")
            // Two is the floor at level 1, where the entire catalogue is a town hall, a builders'
            // hut and a training camp — there is nothing else in existence to place.
            val floor = if (level <= 2) 2 else 4
            assertTrue(
                base.standing.size >= floor,
                "a level-$level country has ${base.standing.size} buildings",
            )
        }
    }
}
