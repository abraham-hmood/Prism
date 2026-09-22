package com.prism.launcher.minigames

/**
 * A country that has been conquered and kept.
 *
 * ## Why a record and not a second base
 *
 * Because a conquered country is somewhere else. Merging its buildings onto the winner's plot
 * would mean a level-40 player suddenly has a level-200 country's walls standing in their own
 * fields, which is neither drawable nor sensible. What actually transfers is what the design asks
 * for — its army, its defences, its people, its treasury and the taxes those people pay — and all
 * of that is five numbers and a loyalty.
 *
 * ## Loyalty
 *
 * A freshly taken country hands over very little and gets more generous as it settles. That single
 * number is what stops conquest from being a step change: taking a country twice your size is
 * worth doing and does not immediately double you, and a player who conquers constantly ends up
 * with a wide empire of sullen provinces rather than an instant win.
 */
data class AnnexedCountry(
    val id: String,
    val name: String,
    val level: Int,
    /** Soldiers it can field. Contributed in proportion to [loyalty]. */
    val soldiers: Int,
    /** How much its defences are worth when its new owner is raided. */
    val defences: Double,
    val civilians: Int,
    /** XP seized at the moment of conquest. Recorded so the report can name it. */
    val treasury: Long,
    /** 0 at conquest, rising to 1. Scales everything the province contributes. */
    val loyalty: Double = 0.2,
    val takenAt: Long = 0,
    /**
     * The province's own base: its buildings, army, research, everything.
     *
     * A conquered country used to be nothing but the five numbers above, and a player who took one
     * could not build there, train there, or research there -- it was a source of tax and nothing
     * else. It is now a real, playable [PaperBase] of its own, seeded at conquest from what was
     * actually standing there ([WorldMap.aiBase]), and it develops independently of the capital
     * from then on: its own builders, its own army, its own weapons research.
     *
     * The numeric fields above are UNCHANGED by this and keep meaning what they always meant --
     * what the province contributes to the capital's tax and defence, settling with [loyalty] -- so
     * every existing calculation about a province's worth to its owner still holds. [base] is a
     * second, separate economy layered on top: what the province is worth to develop in its own
     * right, the same way the capital is.
     *
     * Nullable so a province taken before this existed, and never looked at with a "manage" button,
     * survives a reload without needing to be backfilled — see [PaperBase] decoding.
     */
    val base: PaperBase? = null,
) {
    val age: Era.Age get() = Era.ageOf(level)

    /** Settles the province for [minutes] of ownership. Full loyalty takes about a day. */
    fun settle(minutes: Double): AnnexedCountry {
        if (loyalty >= 1.0) return this
        return copy(loyalty = (loyalty + minutes / (60.0 * 20)).coerceAtMost(1.0))
    }

    /** What it pays its owner each minute, after loyalty. */
    fun taxPerMinute(): Double = civilians * PaperBase.BASE_TAX_PER_CIVILIAN * loyalty

    /** Whether this province can be built in, trained and researched as its own place. */
    val isManageable: Boolean get() = base != null

    /** Replaces the province's own base -- called after building, training or researching there. */
    fun withBase(next: PaperBase): AnnexedCountry = copy(base = next)

    fun describeLoyalty(): String = when {
        loyalty >= 0.95 -> "settled"
        loyalty >= 0.6 -> "settling"
        loyalty >= 0.3 -> "restive"
        else -> "newly taken"
    }
}
