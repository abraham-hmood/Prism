package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A nuke or an antimatter drop has to actually flatten what it hits. */
class DevastationTest {

    private fun someBase(): PaperBase {
        val types = listOf(
            BuildingCatalog.TOWN_HALL, BuildingCatalog.BUILDERS_HUT, BuildingCatalog.TRAINING_CAMP,
        )
        val placed = types.mapIndexed { i, type ->
            PlacedBuilding(
                id = "b$i", typeId = type.id, x = 2 + i * 4, y = 4,
                hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
                startedAt = 0, finishesAt = 0, complete = true,
            )
        }
        return PaperBase(name = "Test", xp = 50_000, peakXp = 50_000, buildings = placed)
    }

    @Test
    fun `both warheads are total, not partial`() {
        assertEquals(1.0, WeaponCatalog.Wmd.NUCLEAR.destruction)
        assertEquals(1.0, WeaponCatalog.Wmd.ANTIMATTER.destruction)
    }

    @Test
    fun `devastate at full fraction flattens every finished building`() {
        val base = someBase()
        val hit = base.devastate(1.0)

        assertTrue(hit.buildings.all { it.hitPoints == 0 }, "something was left standing")
        assertEquals(base.buildings.size, hit.buildings.size, "devastation must not delete anything")
        assertTrue(hit.standing.isEmpty(), "a flattened base has nothing standing")
        assertTrue(!hit.hasTownHall, "the town hall itself has to go down too")
    }

    @Test
    fun `devastate never removes a building from the list`() {
        val base = someBase()
        val hit = base.devastate(1.0)
        assertEquals(base.buildings.map { it.id }.toSet(), hit.buildings.map { it.id }.toSet())
    }

    @Test
    fun `a building site is not devastated -- there is nothing standing there yet`() {
        val site = PlacedBuilding(
            id = "site", typeId = BuildingCatalog.BUILDERS_HUT.id, x = 10, y = 10,
            hitPoints = BuildingCatalog.BUILDERS_HUT.hitPoints, maxHitPoints = BuildingCatalog.BUILDERS_HUT.hitPoints,
            startedAt = 0, finishesAt = Long.MAX_VALUE, complete = false,
        )
        val base = PaperBase(name = "Test", xp = 0, peakXp = 0, buildings = listOf(site)).devastate(1.0)
        assertEquals(BuildingCatalog.BUILDERS_HUT.hitPoints, base.buildings.first().hitPoints)
    }

    @Test
    fun `builders repair a flattened base back up from zero`() {
        val base = someBase()
            .copy(xp = 1_000_000, peakXp = 1_000_000)
            .devastate(1.0)
        assertTrue(base.builders >= 1, "the test needs at least one builder")

        var repaired = base
        repeat(20) { repaired = repaired.postBattleRepair() }

        assertTrue(
            repaired.buildings.sumOf { it.hitPoints } > 0,
            "twenty rounds of repair should have brought something back",
        )
    }

    @Test
    fun `a fraction less than one leaves a shell rather than a ruin`() {
        val base = someBase()
        val hit = base.devastate(0.5)
        hit.buildings.forEach { b ->
            assertTrue(b.hitPoints in 0..b.maxHitPoints)
        }
        // At 50% every one of these small, equal-health buildings should have SOME health left.
        assertTrue(hit.buildings.any { it.hitPoints > 0 })
    }
}
