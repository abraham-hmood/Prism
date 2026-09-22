package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The endless world, the grudges that drive it, and the civilian economy under it.
 *
 * The property that matters most here cannot be seen on a screen: that zooming out and scrolling
 * across reveal the SAME world. Both are the same function of the same seed, and this is where
 * that is actually checked.
 */
class EndlessWorldTest {

    private val seed = 987_654L

    // -- The world is a function, not a list ----------------------------------

    @Test
    fun `the same chunk always holds the same country`() {
        repeat(20) { i ->
            val a = WorldChunks.countryAt(seed, playerLevel = 50, chunkX = i, chunkY = -i)
            val b = WorldChunks.countryAt(seed, playerLevel = 50, chunkX = i, chunkY = -i)
            assertEquals(a, b, "chunk ($i, ${-i}) changed between calls")
        }
    }

    @Test
    fun `a different seed is a different world`() {
        val a = (0..10).mapNotNull { WorldChunks.countryAt(1, 50, it, 0)?.name }
        val b = (0..10).mapNotNull { WorldChunks.countryAt(2, 50, it, 0)?.name }
        assertTrue(a != b, "two seeds produced the same countries")
    }

    @Test
    fun `zooming out reveals exactly what scrolling there would`() {
        // The design's requirement, stated as a test: a country found by widening the view is the
        // same country found by moving the view to it. Both go through countriesIn, so if this ever
        // fails the two have been allowed to diverge.
        val wide = WorldChunks.countriesIn(
            seed, 50, WorldChunks.View(-700.0, -700.0, 900.0, 900.0), limit = 8000,
        ).associateBy { it.id }

        listOf(-400.0, -120.0, 0.0, 260.0, 520.0).forEach { ox ->
            listOf(-380.0, 40.0, 410.0).forEach { oy ->
                val near = WorldChunks.countriesIn(
                    seed, 50, WorldChunks.View(ox, oy, ox + 120, oy + 120),
                )
                near.forEach { country ->
                    val fromWide = wide[country.id]
                    assertNotNull(fromWide, "${country.name} appears when scrolled to but not when zoomed out")
                    assertEquals(fromWide, country, "${country.name} differs between the two views")
                }
            }
        }
    }

    @Test
    fun `the world has no edge`() {
        // Ten thousand units out in every direction there is still a world.
        listOf(10_000.0, -10_000.0, 250_000.0, -250_000.0).forEach { far ->
            val found = WorldChunks.countriesIn(
                seed, 50, WorldChunks.View(far, far, far + 300, far + 300),
            )
            assertTrue(found.isNotEmpty(), "nothing exists at $far")
        }
    }

    @Test
    fun `countries do not sit on top of each other`() {
        val found = WorldChunks.countriesIn(
            seed, 80, WorldChunks.View(-400.0, -400.0, 400.0, 400.0), limit = 4000,
        )
        assertTrue(found.size > 50, "only ${found.size} countries in a large view")
        found.forEachIndexed { i, a ->
            found.drop(i + 1).forEach { b ->
                val d = kotlin.math.hypot(a.centreX - b.centreX, a.centreY - b.centreY)
                assertTrue(
                    d > WorldChunks.COUNTRY_RADIUS * 1.5,
                    "${a.name} and ${b.name} are only $d apart",
                )
            }
        }
    }

    @Test
    fun `zooming out further never costs unbounded work`() {
        // The whole point of the cull. A view a million units across must still return a bounded
        // number of countries and must not take a noticeable time doing it.
        val started = System.nanoTime()
        val found = WorldChunks.countriesIn(
            seed, 50, WorldChunks.View(-500_000.0, -500_000.0, 500_000.0, 500_000.0),
        )
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(found.size <= 400, "returned ${found.size} countries")
        assertTrue(millis < 2_000, "took ${millis}ms to look at a million units of world")
    }

    @Test
    fun `the player's own chunk holds nobody else`() {
        assertEquals(null, WorldChunks.countryAt(seed, 50, 0, 0))
    }

    @Test
    fun `countries get harder the further out they are`() {
        fun meanLevel(ring: Int): Double {
            val levels = (-ring..ring).flatMap { x ->
                (-ring..ring).mapNotNull { y ->
                    if (kotlin.math.abs(x) != ring && kotlin.math.abs(y) != ring) null
                    else WorldChunks.countryAt(seed, 40, x, y)?.level
                }
            }
            return if (levels.isEmpty()) 0.0 else levels.average()
        }
        assertTrue(meanLevel(6) > meanLevel(1), "the far reaches should be no safer than home")
        assertTrue(meanLevel(20) > meanLevel(6))
    }

    // -- Grudges --------------------------------------------------------------

    @Test
    fun `failing an invasion makes a country more likely to come back`() {
        val country = WorldChunks.countryAt(seed, 50, 2, 1)!!
        val calm = WorldMap.aggressionToward(country, failedInvasions = 0, playerLevel = 50)
        val provoked = WorldMap.aggressionToward(country, failedInvasions = 3, playerLevel = 50)
        assertTrue(provoked > calm * 2, "three failed invasions barely registered")
    }

    @Test
    fun `an ally never attacks`() {
        val country = WorldChunks.countryAt(seed, 50, 2, 1)!!
            .copy(relation = WorldMap.Relation.ALLIED)
        assertEquals(0.0, WorldMap.aggressionToward(country, failedInvasions = 9, playerLevel = 50))
        assertEquals(
            null,
            WorldMap.pickRaider(listOf(country), mapOf(country.id to 9), 50, seed),
            "an ally was chosen as a raider",
        )
    }

    @Test
    fun `the country the player provoked is the one that comes`() {
        val world = (1..6).mapNotNull { WorldChunks.countryAt(seed, 50, it, 0) }
        assertTrue(world.size >= 3)
        val provoked = world.first()

        // Over many draws the provoked country should dominate, without being certain.
        val picks = (0 until 300).mapNotNull {
            WorldMap.pickRaider(world, mapOf(provoked.id to 6), 50, seed + it)?.id
        }
        val share = picks.count { it == provoked.id }.toDouble() / picks.size
        assertTrue(share > 0.4, "the provoked country was picked only ${(share * 100).toInt()}% of the time")
        assertTrue(share < 0.95, "nobody else ever got a turn")
    }

    // -- Revolts --------------------------------------------------------------

    @Test
    fun `the first invasion cannot cause a revolt and the second is one percent`() {
        assertEquals(0.0, WorldMap.revoltChance(0))
        assertEquals(0.01, WorldMap.revoltChance(1), 1e-9)
        assertTrue(WorldMap.revoltChance(5) > WorldMap.revoltChance(2))
        assertTrue(WorldMap.revoltChance(50) <= 0.75, "a revolt must never be certain")
    }

    @Test
    fun `a revolt roll is stable for the same invasion count`() {
        val first = WorldMap.rollRevolt("ai:1:1", 4, seed)
        repeat(5) { assertEquals(first, WorldMap.rollRevolt("ai:1:1", 4, seed)) }
    }

    // -- Civilians ------------------------------------------------------------

    private fun farmBase(farms: Int, houses: Int, level: Int): PaperBase {
        val farm = BuildingCatalog.byId("wheat_farm")!!
        val hut = BuildingCatalog.byId("civilian_hut")!!
        val buildings = (0 until farms).map {
            PlacedBuilding("f$it", farm.id, it * 3, 0, farm.hitPoints, farm.hitPoints, 0, 0, true)
        } + (0 until houses).map {
            PlacedBuilding("h$it", hut.id, it * 2, 6, hut.hitPoints, hut.hitPoints, 0, 0, true)
        }
        val xp = Era.xpForLevel(level) + 10
        return PaperBase("Test", xp = xp, peakXp = xp, buildings = buildings, lastTaxAt = 1)
    }

    @Test
    fun `housing holds more people every five levels`() {
        val small = farmBase(0, 4, level = 1)
        val large = farmBase(0, 4, level = 60)
        assertTrue(
            large.civilianCapacity > small.civilianCapacity * 2,
            "${small.civilianCapacity} at level 1 and ${large.civilianCapacity} at level 60",
        )
        assertEquals(1.0, farmBase(0, 4, 1).densityMultiplier(), 1e-9)
    }

    @Test
    fun `farms feed people and a country with none stops growing`() {
        val fed = farmBase(farms = 8, houses = 6, level = 30).copy(civilians = 20, families = 5)
        val starving = farmBase(farms = 0, houses = 6, level = 30).copy(civilians = 20, families = 5)

        assertTrue(fed.foodRatio > 1.0, "eight farms did not feed twenty people")
        assertTrue(starving.foodRatio < 1.0)

        val hour = 1L + 60 * 60_000
        assertTrue(
            fed.collectTaxes(hour).civilians > starving.collectTaxes(hour).civilians,
            "a fed country did not out-grow a starving one",
        )
    }

    @Test
    fun `a famine drives people away`() {
        val starving = farmBase(farms = 0, houses = 10, level = 40).copy(civilians = 200, families = 50)
        assertTrue(starving.foodRatio < 0.75)
        val later = starving.collectTaxes(1L + 60 * 60_000)
        assertTrue(later.civilians < starving.civilians, "nobody left a country that could not feed them")
    }

    @Test
    fun `families form and drive births`() {
        val base = farmBase(farms = 10, houses = 10, level = 40).copy(civilians = 40, families = 10)
        val later = base.collectTaxes(1L + 30 * 60_000)
        assertTrue(later.families > 0, "no households formed")
        assertTrue(later.civilians > base.civilians, "well-fed families had no children")
    }

    @Test
    fun `schools make civilians worth more and families smaller`() {
        val plain = farmBase(2, 4, 60).copy(civilians = 50)
        val school = BuildingCatalog.byId("grammar_school")!!
        val taught = plain.copy(
            buildings = plain.buildings + PlacedBuilding(
                "s", school.id, 20, 20, school.hitPoints, school.hitPoints, 0, 0, true,
            )
        )
        assertTrue(taught.schooling > plain.schooling)
        assertTrue(taught.taxPerCivilian() > plain.taxPerCivilian())
        assertTrue(taught.familySize() < plain.familySize())
    }

    @Test
    fun `every named school exists in the catalogue`() {
        val missing = PaperBase.SCHOOL_IDS.filter { BuildingCatalog.byId(it) == null }
        assertTrue(missing.isEmpty(), "schools named but not built: $missing")
    }

    @Test
    fun `a second at a time grows and pays the same as an hour at once`() {
        // The bug this pins: the tick runs once a second, and a sixtieth of a minute of growth and
        // tax both truncated to zero. A country with forty-six houses sat at nought civilians and
        // nought income indefinitely, and nothing in the numbers on screen said why.
        val start = farmBase(farms = 12, houses = 12, level = 40).copy(civilians = 30, families = 8)

        var ticked = start
        var now = 1L
        repeat(3_600) {
            now += 1_000
            ticked = ticked.collectTaxes(now)
        }
        val atOnce = start.collectTaxes(1L + 3_600_000)

        assertTrue(ticked.civilians > start.civilians, "ticking once a second grew nobody")
        assertTrue(ticked.xp > start.xp, "ticking once a second earned nothing")

        // Not identical -- growth compounds differently when applied in small steps -- but the same
        // order of magnitude, which is the thing that was broken.
        assertTrue(
            ticked.xp >= atOnce.xp / 2,
            "a second at a time earned ${ticked.xp} where an hour at once earned ${atOnce.xp}",
        )
    }

    // -- Roads ----------------------------------------------------------------

    @Test
    fun `a road is laid between the two nearest buildings`() {
        val hut = BuildingCatalog.byId("civilian_hut")!!
        val road = BuildingCatalog.byId("dirt_road")!!
        val base = PaperBase(
            "Test", xp = 500_000, peakXp = 500_000,
            buildings = listOf(
                PlacedBuilding("a", hut.id, 2, 2, hut.hitPoints, hut.hitPoints, 0, 0, true),
                PlacedBuilding("b", hut.id, 9, 2, hut.hitPoints, hut.hitPoints, 0, 0, true),
                PlacedBuilding("far", hut.id, 28, 28, hut.hitPoints, hut.hitPoints, 0, 0, true),
                // Far from the tap, so it is not one of the two nearest.
                PlacedBuilding(
                    "hut", BuildingCatalog.BUILDERS_HUT.id, 30, 0,
                    BuildingCatalog.BUILDERS_HUT.hitPoints, BuildingCatalog.BUILDERS_HUT.hitPoints,
                    0, 0, true,
                ),
            ),
        )
        assertTrue(base.isRoadType(road), "a dirt road should be laid as a road")

        val laid = base.place(road, 6, 2, now = 1_000)
        val made = laid.buildings.last()
        assertTrue(made.isRoad, "the road did not record what it connects")
        assertTrue(
            setOf(made.connectsFrom, made.connectsTo) == setOf("a", "b"),
            "the road joined ${made.connectsFrom} to ${made.connectsTo}",
        )
    }

    @Test
    fun `a road with nothing to join is refused`() {
        val road = BuildingCatalog.byId("dirt_road")!!
        val lonely = PaperBase(
            "Test", xp = 500_000, peakXp = 500_000,
            buildings = listOf(
                PlacedBuilding(
                    "hut", BuildingCatalog.BUILDERS_HUT.id, 0, 0,
                    BuildingCatalog.BUILDERS_HUT.hitPoints, BuildingCatalog.BUILDERS_HUT.hitPoints,
                    0, 0, true,
                ),
            ),
        )
        assertEquals(lonely, lonely.place(road, 5, 5, 1_000), "a road to nowhere was built")
    }

    @Test
    fun `a bridge is a structure, not a road`() {
        val bridge = BuildingCatalog.byId("stone_bridge")
        if (bridge != null) {
            assertFalse(
                PaperBase("Test").isRoadType(bridge),
                "a bridge should sit on a square, not stretch between two farms",
            )
        }
    }

    // -- Loadout --------------------------------------------------------------

    @Test
    fun `the army carries what it is issued, and only what is researched`() {
        val bow = WeaponCatalog.byId("longbow")!!
        val sword = WeaponCatalog.byId("arming_sword")!!
        val base = PaperBase("Test", researched = setOf(bow.id, sword.id))

        assertEquals(bow.id, base.withInfantryWeapon(bow.id).infantryWeapon().id)
        assertEquals(sword.id, base.withGarrisonWeapon(sword.id).garrisonWeapon().id)

        // Issued something never researched: ignored, and the quartermaster's choice stands.
        val bogus = base.withInfantryWeapon("railgun")
        assertTrue(bogus.infantryWeapon().id in setOf(bow.id, sword.id))

        // And clearing it goes back to the automatic pick.
        assertEquals(
            base.infantryWeapon().id,
            base.withInfantryWeapon(bow.id).withInfantryWeapon(null).infantryWeapon().id,
        )
    }

    @Test
    fun `the armoury lists researched weapons, best first, without support gear`() {
        val ids = WeaponCatalog.ALL.take(60).map { it.id }.toSet()
        val armoury = PaperBase("Test", researched = ids).armoury()
        assertTrue(armoury.isNotEmpty())
        assertTrue(armoury.none { it.weaponClass == WeaponCatalog.WeaponClass.SUPPORT })
        armoury.zipWithNext { a, b -> assertTrue(a.threat >= b.threat, "the armoury is out of order") }
    }
}
