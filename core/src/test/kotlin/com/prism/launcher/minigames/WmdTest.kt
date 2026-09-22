package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WmdTest {

    @Test
    fun `both kinds of warhead exist and sit in the right eras`() {
        val nuclear = WeaponCatalog.ALL.filter { it.wmd == WeaponCatalog.Wmd.NUCLEAR }
        val antimatter = WeaponCatalog.ALL.filter { it.wmd == WeaponCatalog.Wmd.ANTIMATTER }

        assertTrue(nuclear.isNotEmpty(), "there are no nuclear weapons")
        assertTrue(antimatter.isNotEmpty(), "there are no antimatter weapons")
        assertTrue(
            nuclear.all { it.age == Era.Age.MODERN },
            "nuclear has to unlock in the modern era: " +
                nuclear.filter { it.age != Era.Age.MODERN }.map { it.name to it.age },
        )
        assertTrue(
            antimatter.all { it.age == Era.Age.FUTURISTIC },
            "antimatter is a futuristic weapon: " +
                antimatter.filter { it.age != Era.Age.FUTURISTIC }.map { it.name to it.age },
        )
    }

    @Test
    fun `a button only appears for what has actually been researched`() {
        assertTrue(WeaponCatalog.wmdsIn(emptySet()).isEmpty())

        val nuke = WeaponCatalog.ALL.first { it.wmd == WeaponCatalog.Wmd.NUCLEAR }
        val onlyNuclear = WeaponCatalog.wmdsIn(setOf(nuke.id))
        assertEquals(setOf(WeaponCatalog.Wmd.NUCLEAR), onlyNuclear.keys)
    }

    @Test
    fun `a strike burns out and the country comes back`() {
        val now = 1_000_000_000L
        val strike = WorldMap.Strike(WeaponCatalog.Wmd.NUCLEAR, now)

        assertEquals(1.0, strike.scorch(now), 0.001)
        assertTrue(strike.isBurning(now + 60_000))
        assertTrue(!strike.isBurning(now + (WeaponCatalog.Wmd.NUCLEAR.burnHours * 3_600_000).toLong() + 1))
    }

    @Test
    fun `a struck country is measurably smaller while it burns`() {
        val now = 2_000_000_000L
        val country = WorldMap.Country(
            id = "ai:1:1", name = "Test", owner = WorldMap.Owner.AI, level = 300,
            outline = emptyList(), centreX = 0.0, centreY = 0.0,
            soldiers = 400, buildingCount = 200, population = 900,
        )

        val hit = WorldMap.scorched(country, WorldMap.Strike(WeaponCatalog.Wmd.ANTIMATTER, now), now)

        assertTrue(hit.buildingCount < country.buildingCount, "an antimatter drop has to show")
        assertTrue(hit.soldiers < country.soldiers)
        assertEquals(country, WorldMap.scorched(country, null, now), "an untouched country is untouched")
    }
}
