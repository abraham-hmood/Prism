package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A conquered country has to be a real, playable place, not just a row of tax numbers. */
class ProvinceTest {

    private fun someCountry(level: Int = 60) = WorldMap.Country(
        id = "ai:3:4", name = "Test Province", owner = WorldMap.Owner.AI, level = level,
        outline = emptyList(), centreX = 0.0, centreY = 0.0,
        soldiers = 40, buildingCount = 20, population = 200,
        xp = Era.xpForLevel(level) + 5_000,
    )

    @Test
    fun `spoilsOf hands over a real, buildable base`() {
        val province = WorldMap.spoilsOf(someCountry(), takenAt = 0L)

        assertTrue(province.isManageable, "a freshly conquered province must be manageable")
        val base = requireNotNull(province.base)
        assertTrue(base.hasTownHall, "a province with no town hall cannot be built in")
        assertTrue(base.builders >= 1, "a province with no builders cannot be built in")
        assertTrue(base.buildings.isNotEmpty())
    }

    @Test
    fun `killed civilians are reflected in the province's own base too`() {
        val country = someCountry().let { it.copy(population = 200) }
        val province = WorldMap.spoilsOf(country, takenAt = 0L, killed = 150)

        assertEquals(50, province.civilians)
        assertEquals(50, province.base?.civilians)
    }

    @Test
    fun `withBase replaces only the base, leaving loyalty and tax untouched`() {
        val province = WorldMap.spoilsOf(someCountry(), takenAt = 0L)
        val grown = requireNotNull(province.base).trainSoldiers(5)

        val updated = province.withBase(grown)

        assertEquals(province.loyalty, updated.loyalty)
        assertEquals(province.civilians, updated.civilians)
        assertEquals(grown, updated.base)
    }

    @Test
    fun `an annexed province's base can be built in like any other`() {
        val province = WorldMap.spoilsOf(someCountry(level = 80), takenAt = 0L)
        val base = requireNotNull(province.base)

        val buildable = BuildingCatalog.unlockedAt(base.level).firstOrNull { type ->
            base.canPlace(type) == PaperBase.Placement.Allowed
        }
        assertTrue(buildable != null, "a level ${base.level} province should have something placeable")
    }
}
