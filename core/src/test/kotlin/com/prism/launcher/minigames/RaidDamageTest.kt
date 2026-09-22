package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two faults that between them destroyed a save.
 *
 * A raid deleted buildings permanently, and an army was five hundred copies of one weapon. Both are
 * the kind of thing that is obvious once seen and invisible until somebody loses a level-500 base
 * to it, so both are pinned here.
 */
class RaidDamageTest {

    private fun baseWith(vararg types: BuildingCatalog.BuildingType): PaperBase {
        val placed = types.mapIndexed { index, type ->
            PlacedBuilding(
                id = "b$index",
                typeId = type.id,
                x = 2 + index * 3, y = 4,
                hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
                startedAt = 0, finishesAt = 0, complete = true,
            )
        }
        return PaperBase(name = "Test", xp = 10_000, peakXp = 10_000, buildings = placed)
    }

    @Test
    fun `a raid never removes a building, however hard it is hit`() {
        val base = baseWith(
            BuildingCatalog.TOWN_HALL,
            BuildingCatalog.BUILDERS_HUT,
            BuildingCatalog.TRAINING_CAMP,
        )
        // Far more damage than anything has hit points.
        val obliterating = base.buildings.associate { it.id to 9_999_999 }

        val after = base.applyDamage(obliterating)

        assertEquals(
            base.buildings.size, after.buildings.size,
            "A raid must damage the base, not delete it. Deleting is how a level-500 save was lost.",
        )
        assertTrue(after.buildings.all { it.hitPoints >= 1 }, "Nothing may be reduced below a shell")
        assertTrue(after.hasTownHall, "The town hall has to survive, or the base cannot be played")
    }

    @Test
    fun `a damaged base repairs itself back to full`() {
        val base = baseWith(BuildingCatalog.TOWN_HALL)
        val wrecked = base.applyDamage(base.buildings.associate { it.id to 9_999_999 })

        val mended = wrecked.repair(1.0)

        assertEquals(
            mended.buildings.first().maxHitPoints, mended.buildings.first().hitPoints,
            "A full repair has to undo a full flattening, or the setback is permanent after all",
        )
    }

    @Test
    fun `an army with detachments is not five hundred of the same thing`() {
        val defender = baseWith(BuildingCatalog.TOWN_HALL, BuildingCatalog.BUILDERS_HUT)
        val armour = WeaponCatalog.ALL.first { it.weaponClass == WeaponCatalog.WeaponClass.ARMOUR }
        val air = WeaponCatalog.ALL.first { it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT }
        val rifle = WeaponCatalog.STARTER

        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 300,
            attackerSoldiers = 200,
            attackerWeaponId = rifle.id,
            orders = emptyList(),
            seed = 99,
            attackerSupportIds = listOf(armour.id, air.id),
        )

        val carried = playback.cast.weaponIds.toSet()
        assertTrue(armour.id in carried, "The tanks have to actually be in the army")
        assertTrue(air.id in carried, "So do the aircraft")
        assertTrue(rifle.id in carried, "And the bulk of it is still infantry")

        val expectedEach = (200 * PaperBase.DETACHMENT_SHARE).toInt()
        assertEquals(expectedEach, playback.cast.weaponIds.count { it == armour.id })
        assertEquals(expectedEach, playback.cast.weaponIds.count { it == air.id })
    }

    @Test
    fun `detachments spread across every squad rather than massing in one`() {
        val defender = baseWith(BuildingCatalog.TOWN_HALL)
        val armour = WeaponCatalog.ALL.first { it.weaponClass == WeaponCatalog.WeaponClass.ARMOUR }

        val playback = Battle.simulate(
            defender = defender,
            attackerLevel = 300,
            attackerSoldiers = 400,
            attackerWeaponId = WeaponCatalog.STARTER.id,
            orders = emptyList(),
            seed = 7,
            attackerSupportIds = listOf(armour.id),
        )

        val squadsWithArmour = playback.cast.weaponIds
            .withIndex()
            .filter { it.value == armour.id }
            .map { playback.cast.squads[it.index] }
            .toSet()

        assertEquals(
            4, squadsWithArmour.size,
            "Every approach needs its own armour, or three sides of an attack go in unsupported",
        )
    }

    @Test
    fun `a helicopter is not filed as a jet`() {
        val helicopter = WeaponCatalog.ALL.first { it.isRotaryWing }
        assertEquals(WeaponCatalog.WeaponClass.AIRCRAFT, helicopter.weaponClass)
        assertTrue(
            WeaponCatalog.ALL.any { it.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT && !it.isRotaryWing },
            "There have to be fixed wings too, or the distinction is pointless",
        )
    }

    @Test
    fun `the default detachments bring one of each kind rather than four of the best`() {
        val everything = WeaponCatalog.unlockedAt(400).map { it.id }.toSet()
        val base = PaperBase(name = "Test", xp = 1, peakXp = 1, researched = everything)

        val fielded = base.supportWeapons()

        assertTrue(fielded.isNotEmpty(), "A modern army fields something crewed")
        assertEquals(
            fielded.size, fielded.map { it.weaponClass to it.isRotaryWing }.distinct().size,
            "One of each kind, so a formation reads as an army rather than as a repeated unit",
        )
        assertTrue(fielded.size <= PaperBase.MAX_DETACHMENTS)
    }

    @Test
    fun `builders repair damage on their own the moment a battle ends`() {
        val base = baseWith(BuildingCatalog.TOWN_HALL, BuildingCatalog.BUILDERS_HUT)
        val damaged = base.applyDamage(base.buildings.associate { it.id to 9_999_999 })
        assertTrue(damaged.buildings.any { it.hitPoints < it.maxHitPoints })

        val repaired = damaged.postBattleRepair()

        assertTrue(
            repaired.buildings.sumOf { it.hitPoints } > damaged.buildings.sumOf { it.hitPoints },
            "builders should have started fixing the damage immediately",
        )
    }

    @Test
    fun `postBattleRepair does nothing when nothing is damaged`() {
        val undamaged = baseWith(BuildingCatalog.TOWN_HALL, BuildingCatalog.BUILDERS_HUT)
        assertEquals(undamaged, undamaged.postBattleRepair())
    }
}
