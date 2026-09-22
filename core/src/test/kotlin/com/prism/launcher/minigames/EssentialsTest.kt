package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A base must never be left in a state it cannot build its way out of. */
class EssentialsTest {

    private fun placed(type: BuildingCatalog.BuildingType, x: Int, y: Int, tag: String) =
        PlacedBuilding(
            id = "$tag#$x#$y", typeId = type.id, x = x, y = y,
            hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
            startedAt = 0, finishesAt = 0, complete = true,
        )

    @Test
    fun `a base with no camps gets camps and keeps everything it had`() {
        val mine = placed(BuildingCatalog.TOWN_HALL, 15, 15, "mine")
        val base = PaperBase(name = "T", xp = 1, peakXp = Era.xpForLevel(500), buildings = listOf(mine))
        assertEquals(0, base.armyCapacity, "the test needs a base that cannot train")

        val fixed = WorldMap.addMissingEssentials(base, 500)

        assertTrue(fixed.armyCapacity > 0, "still cannot train a single soldier")
        assertTrue(fixed.builders >= 1, "still cannot build anything")
        assertTrue(mine in fixed.buildings, "a repair must never remove what the player placed")
    }

    @Test
    fun `nothing is added to a base that is already playable`() {
        val base = WorldMap.layoutFor("T", 300, "recovered:test")
        assertTrue(base.armyCapacity > 0 && base.builders > 0)

        assertEquals(base, WorldMap.addMissingEssentials(base, 300), "a healthy base must be left alone")
    }

    @Test
    fun `added buildings do not land on top of existing ones`() {
        val mine = (0 until 6).map { placed(BuildingCatalog.TOWN_HALL, 2 + it * 4, 3, "mine$it") }
        val base = PaperBase(name = "T", xp = 1, peakXp = Era.xpForLevel(200), buildings = mine)

        val fixed = WorldMap.addMissingEssentials(base, 200)

        fixed.buildings.forEachIndexed { i, a ->
            val sa = a.type?.footprint ?: 1
            fixed.buildings.drop(i + 1).forEach { b ->
                val sb = b.type?.footprint ?: 1
                val hit = a.x < b.x + sb && b.x < a.x + sa && a.y < b.y + sb && b.y < a.y + sa
                assertTrue(!hit, "${a.id} overlaps ${b.id}")
            }
        }
    }
}
