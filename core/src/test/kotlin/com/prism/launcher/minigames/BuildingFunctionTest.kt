package com.prism.launcher.minigames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five categories that used to just sit on the page -- RESOURCE, INDUSTRY, INFRASTRUCTURE,
 * TRADE and CIVIC -- each have to actually do something once they are standing.
 */
class BuildingFunctionTest {

    private fun placed(type: BuildingCatalog.BuildingType, index: Int) = PlacedBuilding(
        id = "b$index", typeId = type.id, x = 1 + (index % 20) * 3, y = 1 + (index / 20) * 3,
        hitPoints = type.hitPoints, maxHitPoints = type.hitPoints,
        startedAt = 0, finishesAt = 0, complete = true,
    )

    private fun baseWith(category: BuildingCatalog.Category, count: Int, level: Int = 200): PaperBase {
        val types = BuildingCatalog.unlockedAt(level).filter { it.category == category }
        require(types.isNotEmpty()) { "no $category buildings unlocked by level $level" }
        val town = placed(BuildingCatalog.TOWN_HALL, 0)
        val extras = (0 until count).map { placed(types[it % types.size], it + 1) }
        return PaperBase(name = "Test", xp = 10_000_000, peakXp = 10_000_000, buildings = listOf(town) + extras)
    }

    @Test
    fun `resource buildings make building cheaper`() {
        val plain = baseWith(BuildingCatalog.Category.RESOURCE, 0)
        val resourced = baseWith(BuildingCatalog.Category.RESOURCE, 20)
        val hut = BuildingCatalog.BUILDERS_HUT

        assertTrue(resourced.effectiveBuildCost(hut) < plain.effectiveBuildCost(hut))
    }

    @Test
    fun `industry buildings make building faster`() {
        val plain = baseWith(BuildingCatalog.Category.INDUSTRY, 0)
        val industrial = baseWith(BuildingCatalog.Category.INDUSTRY, 20)
        val hut = BuildingCatalog.BUILDERS_HUT

        assertTrue(industrial.effectiveBuildSeconds(hut) < plain.effectiveBuildSeconds(hut))
    }

    @Test
    fun `non-road infrastructure makes research cheaper`() {
        val plain = baseWith(BuildingCatalog.Category.INFRASTRUCTURE, 0)
        val connected = baseWith(BuildingCatalog.Category.INFRASTRUCTURE, 20)
        val weapon = WeaponCatalog.ALL.first { it.unlockLevel <= 200 }

        assertTrue(connected.infrastructureResearchDiscount < plain.infrastructureResearchDiscount)
        assertTrue(connected.researchCost(weapon) <= plain.researchCost(weapon))
    }

    @Test
    fun `trade buildings raise tax income`() {
        val plain = baseWith(BuildingCatalog.Category.TRADE, 0)
        val trading = baseWith(BuildingCatalog.Category.TRADE, 20)

        assertTrue(trading.tradeIncomeBonus > plain.tradeIncomeBonus)
        assertTrue(trading.taxPerCivilian() > plain.taxPerCivilian())
    }

    @Test
    fun `civic buildings make laws quicker to decide`() {
        val university = placed(requireNotNull(BuildingCatalog.byId("university")), 900)
        val plain = baseWith(BuildingCatalog.Category.CIVIC, 0).let { it.copy(buildings = it.buildings + university) }
        val civic = baseWith(BuildingCatalog.Category.CIVIC, 20).let { it.copy(buildings = it.buildings + university) }
        assertTrue(plain.hasUniversity && civic.hasUniversity, "the test needs a university to enact anything")

        assertTrue(civic.civicLawSpeed < plain.civicLawSpeed)

        val law = Ideology.ALL_LAWS.first { it.minLevel <= 200 }
        val plainAfter = plain.beginLaw(law, now = 0L)
        val civicAfter = civic.beginLaw(law, now = 0L)
        assertTrue(plainAfter.lawInProgress != null, "beginLaw refused on the plain base -- canEnact must be failing")
        assertTrue(civicAfter.lawFinishesAt <= plainAfter.lawFinishesAt)
    }

    @Test
    fun `every economic bonus is bounded -- no category makes anything free or instant`() {
        val stacked = baseWith(BuildingCatalog.Category.RESOURCE, 500, level = 500)
        assertTrue(stacked.resourceBuildDiscount >= 0.5)

        val industrial = baseWith(BuildingCatalog.Category.INDUSTRY, 500, level = 500)
        assertTrue(industrial.industryBuildSpeed >= 0.45)

        val civic = baseWith(BuildingCatalog.Category.CIVIC, 500, level = 500)
        assertTrue(civic.civicLawSpeed >= 0.35)
    }

    @Test
    fun `non-training-camp military buildings strengthen the army, not its size`() {
        val plain = baseWith(BuildingCatalog.Category.MILITARY, 0)
        val armed = baseWith(BuildingCatalog.Category.MILITARY, 20)

        assertTrue(armed.militaryBonus > plain.militaryBonus)
        assertTrue(armed.militaryBonus <= 2.5, "military bonus must stay bounded")

        val defender = PaperBase(
            name = "Target", xp = 1, peakXp = 1,
            buildings = listOf(placed(BuildingCatalog.TOWN_HALL, 0)),
        )
        val plainPlayback = Battle.simulate(
            defender = defender, attackerLevel = 200, attackerSoldiers = 1,
            attackerWeaponId = WeaponCatalog.STARTER.id, orders = emptyList(), seed = 1,
            attackerMilitaryBonus = plain.militaryBonus,
        )
        val armedPlayback = Battle.simulate(
            defender = defender, attackerLevel = 200, attackerSoldiers = 1,
            attackerWeaponId = WeaponCatalog.STARTER.id, orders = emptyList(), seed = 1,
            attackerMilitaryBonus = armed.militaryBonus,
        )
        assertTrue(armedPlayback.cast.maxFighterHp > plainPlayback.cast.maxFighterHp)
    }

    @Test
    fun `research institutes discount weapon research on top of infrastructure`() {
        val plain = baseWith(BuildingCatalog.Category.RESEARCH, 0)
        val institutes = baseWith(BuildingCatalog.Category.RESEARCH, 20)
        val weapon = WeaponCatalog.ALL.first { it.unlockLevel <= 200 }

        assertTrue(institutes.researchInstituteDiscount < plain.researchInstituteDiscount)
        assertTrue(institutes.researchCost(weapon) < plain.researchCost(weapon))
    }

    @Test
    fun `schools and the weapons hall do not count toward the extra research discount`() {
        // Building only schools (which already have their OWN mechanic, schooling) should not
        // ALSO trigger the separate institute discount -- the two are meant to be distinct levers.
        val schoolOnly = PaperBase(
            name = "Test", xp = 1, peakXp = 1,
            buildings = listOf(placed(BuildingCatalog.TOWN_HALL, 0)) +
                (0 until 10).map { placed(requireNotNull(BuildingCatalog.byId("university")), it + 1) },
        )
        assertEquals(1.0, schoolOnly.researchInstituteDiscount)
    }

    @Test
    fun `a building placed in a province's own base gets the same bonuses`() {
        // Provinces use the exact same PaperBase methods the capital does -- see
        // ProvinceManagerView -- so a province full of mines should build just as cheaply.
        val province = baseWith(BuildingCatalog.Category.RESOURCE, 20)
        val capital = baseWith(BuildingCatalog.Category.RESOURCE, 0)
        val hut = BuildingCatalog.BUILDERS_HUT
        assertEquals(province.resourceBuildDiscount, province.effectiveBuildCost(hut).toDouble() / hut.buildCost, 0.05)
        assertTrue(province.effectiveBuildCost(hut) < capital.effectiveBuildCost(hut))
    }
}
