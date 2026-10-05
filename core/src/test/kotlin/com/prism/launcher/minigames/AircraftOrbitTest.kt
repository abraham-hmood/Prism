package com.prism.launcher.minigames

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/** A fighter or helicopter should circle what it is attacking, not stop dead in front of it. */
class AircraftOrbitTest {

    private fun baseWith(buildingCount: Int): PaperBase {
        val types = List(buildingCount) { BuildingCatalog.TOWN_HALL }
        val placed = types.mapIndexed { i, type ->
            PlacedBuilding(
                id = "b$i", typeId = type.id, x = 15, y = 15,
                hitPoints = type.hitPoints * 50, maxHitPoints = type.hitPoints * 50,
                startedAt = 0, finishesAt = 0, complete = true,
            )
        }
        return PaperBase(name = "Target", xp = 1, peakXp = 1, buildings = placed)
    }

    @Test
    fun `an aircraft's distance to its target settles rather than reaching exactly zero`() {
        val defender = baseWith(1)
        val jet = WeaponCatalog.ALL.first {
            it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT && !it.isRotaryWing
        }

        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 300,
            attackerSoldiers = 1,
            attackerWeaponId = jet.id,
            orders = emptyList(),
            seed = 7,
        )

        val targetX = defender.buildings.first().centreX()
        val targetY = defender.buildings.first().centreY()
        val distances = playback.frames
            .filter { it.alive(0) }
            .map { hypot(it.x(0) - targetX, it.y(0) - targetY) }

        assertTrue(distances.size > 5, "the jet died or the building did before there was anything to check")
        // Once it has closed in, it should never again reach (or very nearly reach) zero distance --
        // a ground unit does, because it stops in front of the building; an orbiting one should not.
        val afterClosingIn = distances.drop(distances.size / 2)
        assertTrue(
            afterClosingIn.all { it > 0.3 },
            "the aircraft sat on top of the building instead of circling it: $afterClosingIn",
        )
    }

    @Test
    fun `the same jet actually moves from frame to frame once it is in range`() {
        val defender = baseWith(1)
        val jet = WeaponCatalog.ALL.first { it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT }

        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 300,
            attackerSoldiers = 1,
            attackerWeaponId = jet.id,
            orders = emptyList(),
            seed = 11,
        )

        val positions = playback.frames.filter { it.alive(0) }.map { it.x(0) to it.y(0) }
        assertTrue(positions.size > 5)
        val distinctPositions = positions.toSet()
        assertTrue(
            distinctPositions.size > 2,
            "the aircraft only ever occupied ${distinctPositions.size} distinct spots -- it stopped",
        )
    }

    @Test
    fun `a ground unit, unlike an aircraft, does come to a stop once in range`() {
        val defender = baseWith(1)
        val infantry = WeaponCatalog.STARTER

        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 5,
            attackerSoldiers = 1,
            attackerWeaponId = infantry.id,
            orders = emptyList(),
            seed = 13,
        )

        val positions = playback.frames.filter { it.alive(0) }.map { it.x(0) to it.y(0) }
        assertTrue(positions.size > 3)
        val settled = positions.takeLast(positions.size / 2).toSet()
        assertTrue(settled.size <= 2, "a foot soldier should settle near one spot, not wander: $settled")
    }
}
