package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertTrue

/** A recovered base has to be worth playing, not just legal. */
class RecoverySizeTest {

    @Test
    fun `a recovered base can train soldiers and build things`() {
        listOf(20, 120, 300, 500).forEach { level ->
            val laid = WorldMap.layoutFor("Your Country", level, "recovered:test")
            assertTrue(laid.hasTownHall, "level $level: no town hall")
            assertTrue(laid.builders >= 1, "level $level: no builders, so nothing can ever be built")
            assertTrue(
                laid.armyCapacity > 0,
                "level $level: army capacity is zero, so the army can never be rebuilt",
            )
        }
    }

    @Test
    fun `a high level base is substantial rather than a hamlet`() {
        val laid = WorldMap.layoutFor("Your Country", 500, "recovered:test")
        assertTrue(
            laid.buildings.size >= 40,
            "level 500 came back with only ${laid.buildings.size} buildings",
        )
    }
}
