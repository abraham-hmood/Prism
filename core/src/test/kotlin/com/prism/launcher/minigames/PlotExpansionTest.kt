package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Boundary expansion: a country's field growing every ten levels. */
class PlotExpansionTest {

    @Test
    fun `a fresh base starts at the original field size`() {
        val base = PaperBase.newGame("Test", 0)
        assertEquals(Battle.FIELD, base.plotSize)
        assertEquals(0, base.plotExpansions)
    }

    @Test
    fun `expanding grows the field and costs XP`() {
        val base = PaperBase.newGame("Test", 0).copy(xp = 1_000_000, peakXp = 1_000_000)
        assertTrue(base.canExpandPlot(), "a level that has unlocked an expansion must allow it")

        val cost = base.expandPlotCost
        val expanded = base.expandPlot()

        assertEquals(base.plotExpansions + 1, expanded.plotExpansions)
        assertEquals(base.plotSize + Era.PLOT_EXPANSION_STEP, expanded.plotSize)
        assertEquals(base.xp - cost, expanded.xp)
    }

    @Test
    fun `expanding without enough XP does nothing`() {
        val base = PaperBase.newGame("Test", 0).copy(xp = 1, peakXp = 1)
        val after = base.expandPlot()
        assertEquals(base, after, "an unaffordable expansion must be a no-op, not a partial charge")
    }

    @Test
    fun `expansions unlock every ten levels and are capped`() {
        assertEquals(0, Era.plotExpansionsUnlockedAt(1))
        assertEquals(0, Era.plotExpansionsUnlockedAt(9))
        assertEquals(1, Era.plotExpansionsUnlockedAt(10))
        assertEquals(5, Era.plotExpansionsUnlockedAt(50))
        assertTrue(Era.plotExpansionsUnlockedAt(500) <= 20, "the cap must actually cap")
    }

    @Test
    fun `canExpandPlot refuses once the level's allowance is spent`() {
        val level10 = PaperBase.newGame("Test", 0).copy(xp = Era.xpForLevel(10), peakXp = Era.xpForLevel(10))
        val expanded = level10.expandPlot()
        assertFalse(expanded.canExpandPlot(), "only one expansion is unlocked by level 10")
    }

    @Test
    fun `an AI country's field grows with its level`() {
        val low = WorldMap.aiBase(
            WorldMap.Country(
                id = "ai:1:1", name = "Low", owner = WorldMap.Owner.AI, level = 5,
                outline = emptyList(), centreX = 0.0, centreY = 0.0,
            )
        )
        val high = WorldMap.aiBase(
            WorldMap.Country(
                id = "ai:2:2", name = "High", owner = WorldMap.Owner.AI, level = 400,
                outline = emptyList(), centreX = 0.0, centreY = 0.0,
            )
        )
        assertTrue(high.plotSize > low.plotSize, "a level-400 AI country must have a bigger field than a level-5 one")
        assertEquals(Battle.FIELD, low.plotSize, "nothing is unlocked yet at level 5")
    }

    @Test
    fun `a battle over an expanded base spawns attackers at the expanded edge, not the original one`() {
        val expanded = PaperBase.newGame("Test", 0)
            .copy(xp = 100_000_000, peakXp = 100_000_000)
            .let { var b = it; repeat(3) { b = b.expandPlot() }; b }
        assertTrue(expanded.plotSize > Battle.FIELD)

        val playback = Battle.simulate(
            defender = expanded,
            attackerLevel = 50,
            attackerSoldiers = 20,
            attackerWeaponId = WeaponCatalog.STARTER.id,
            orders = emptyList(),
            seed = 1,
        )
        val first = playback.frames.first()
        val maxCoord = (0 until playback.cast.size).maxOf { maxOf(first.x(it), first.y(it)) }
        assertTrue(
            maxCoord > Battle.FIELD - 1,
            "attackers should be spawning near the expanded field's edge (got max coord $maxCoord)",
        )
    }

    @Test
    fun `blobs never overlap however large a country's boundaries have grown`() {
        // High player level so every generated neighbour is deep into its own expansions, and a
        // wide net so the check covers plenty of adjacent pairs.
        val seed = 4242L
        val countries = (-6..6).flatMap { x -> (-6..6).mapNotNull { y -> WorldChunks.countryAt(seed, 480, x, y) } }
        assertTrue(countries.size > 20, "only ${countries.size} countries generated for the check")

        countries.forEachIndexed { i, a ->
            countries.drop(i + 1).forEach { b ->
                val d = kotlin.math.hypot(a.centreX - b.centreX, a.centreY - b.centreY)
                // A generous stand-in for "the two blobs' actual radii": both were clamped so their
                // edges never leave their own chunk, and two chunks are always at least CHUNK apart.
                assertTrue(
                    d > 5.0,
                    "${a.name} (lvl ${a.level}) and ${b.name} (lvl ${b.level}) are only $d apart",
                )
            }
        }
    }

    @Test
    fun `a high-level country's blob is visibly bigger than a low-level one's`() {
        val low = WorldChunks.countryAt(1, 5, 3, 2)
        val high = (1..40).firstNotNullOfOrNull { seed -> WorldChunks.countryAt(seed.toLong(), 480, 3, 2) }
        requireNotNull(low)
        requireNotNull(high)

        fun spread(country: WorldMap.Country): Double =
            country.outline.maxOf { (x, y) -> kotlin.math.hypot(x - country.centreX, y - country.centreY) }

        assertTrue(
            spread(high) > spread(low),
            "a level ${high.level} country should have a bigger blob than a level ${low.level} one",
        )
    }
}
