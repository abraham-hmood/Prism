package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertTrue

/** A recovered base has to be playable: it must fit the paper and obey the caps. */
class RecoveryLayoutTest {

    @Test
    fun `a recovered base fits the field and keeps its town hall`() {
        listOf(5, 60, 250, 400, 500).forEach { level ->
            val laid = WorldMap.layoutFor("Your Country", level, "recovered:test")

            assertTrue(laid.hasTownHall, "level $level lost its town hall")
            assertTrue(laid.buildings.size > 1, "level $level came back with nothing")

            // The field itself grows with level -- see Era.aiPlotSizeAt -- so a recovered building
            // has to fit THIS base's own plot, not the field every country starts on.
            val field = laid.plotSize
            laid.buildings.forEach { b ->
                val size = b.type?.footprint ?: 1
                assertTrue(
                    b.x >= 0 && b.y >= 0 && b.x + size <= field && b.y + size <= field,
                    "level $level put ${b.typeId} off the ${field}x$field paper at ${b.x},${b.y}",
                )
            }
        }
    }

    @Test
    fun `nothing in a recovered base overlaps anything else`() {
        val laid = WorldMap.layoutFor("Your Country", 500, "recovered:test")
        val placed = laid.buildings.filterNot { it.typeId == BuildingCatalog.TOWN_HALL.id }

        placed.forEachIndexed { i, a ->
            val sa = a.type?.footprint ?: 1
            placed.drop(i + 1).forEach { b ->
                val sb = b.type?.footprint ?: 1
                val hit = a.x < b.x + sb && b.x < a.x + sa && a.y < b.y + sb && b.y < a.y + sa
                assertTrue(!hit, "${a.typeId} and ${b.typeId} are drawn on top of each other")
            }
        }
    }

    @Test
    fun `a recovered base never exceeds what the player could build`() {
        val level = 500
        val laid = WorldMap.layoutFor("Your Country", level, "recovered:test")

        laid.buildings.groupBy { it.typeId }.forEach { (typeId, held) ->
            if (typeId == BuildingCatalog.TOWN_HALL.id) return@forEach
            val cap = BuildingCatalog.byId(typeId)?.capAtLevel(level) ?: 0
            assertTrue(held.size <= cap, "$typeId: ${held.size} placed but only $cap allowed")
        }
    }

    @Test
    fun `a bigger country gets a bigger base`() {
        val small = WorldMap.layoutFor("A", 20, "recovered:test").buildings.size
        val large = WorldMap.layoutFor("A", 400, "recovered:test").buildings.size
        assertTrue(large > small, "level 400 came back with $large, level 20 with $small")
    }
}
