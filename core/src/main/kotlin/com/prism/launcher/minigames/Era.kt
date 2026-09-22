package com.prism.launcher.minigames

/**
 * The three ages of a paper empire, and the arithmetic that governs all of them.
 *
 * ## Why the level curve is here and not in the base
 *
 * Everything in the game is a function of the town hall's level: which buildings exist, how many of
 * each you may place, which weapons your soldiers carry, what an enemy raid looks like. Scattering
 * `if (level >= 250)` through a dozen files is how a game ends up with a building that unlocks in
 * one era and a weapon that assumes another. One file owns the curve; everything else asks it.
 *
 * ## The eras
 *
 * Five hundred levels, split the way the design asks:
 *
 * - **Medieval, 1–250.** Half the game. Timber, stone, muscle and edged steel.
 * - **Modern, 251–312.** A quarter of what remains. Concrete, engines, explosives, radio.
 * - **Futuristic, 313–500.** Everything after. Fusion, rails, drones, orbital anything.
 *
 * Inside an era things modernise in steps rather than all at once: every 5 levels for ordinary
 * buildings, every 10 for the heavy ones (factories, weapons research), which is what
 * [modernisationStep] and [heavyModernisationStep] count.
 */
object Era {

    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 500

    /** Last medieval level. Half of 500, as designed. */
    const val MEDIEVAL_END = 250

    /** Last modern level. A quarter of the second half: 251..312 is 62 levels. */
    const val MODERN_END = 312

    enum class Age(val label: String, val first: Int, val last: Int) {
        MEDIEVAL("Medieval", MIN_LEVEL, MEDIEVAL_END),
        MODERN("Modern", MEDIEVAL_END + 1, MODERN_END),
        FUTURISTIC("Futuristic", MODERN_END + 1, MAX_LEVEL);

        val span: Int get() = last - first + 1

        /** 0.0 at the first level of the age, 1.0 at the last. Drives art and stat scaling. */
        fun progress(level: Int): Double {
            if (span <= 1) return 0.0
            return ((level.coerceIn(first, last) - first).toDouble() / (span - 1)).coerceIn(0.0, 1.0)
        }
    }

    fun ageOf(level: Int): Age = when {
        level <= MEDIEVAL_END -> Age.MEDIEVAL
        level <= MODERN_END -> Age.MODERN
        else -> Age.FUTURISTIC
    }

    fun clampLevel(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /**
     * How many times ordinary construction has modernised by this level.
     *
     * "Everything starts medieval and modernises slowly every 5 levels" — this is that counter. It
     * is what the renderer reads to decide whether a mill is a waterwheel, a steam mill or a
     * gravitic press, without the renderer knowing anything about levels.
     */
    fun modernisationStep(level: Int): Int = (clampLevel(level) - 1) / 5

    /** The same, for the heavy industry that the design says advances every 10 levels instead. */
    fun heavyModernisationStep(level: Int): Int = (clampLevel(level) - 1) / 10

    // -- The unlock cadence ---------------------------------------------------

    /**
     * New building types arrive every two levels.
     *
     * Level 1 is the exception and deliberately so: the opening position is a razed town hall, a
     * builder and somewhere to train soldiers, and nothing else. Anything more on the first screen
     * would bury the one decision a new player can actually make.
     */
    fun unlockTierOf(level: Int): Int = (clampLevel(level) - 1) / 2

    // -- Caps -----------------------------------------------------------------

    /**
     * The standard cap curve: a base count that rises by one every five levels.
     *
     * The design fixes two points on it — two training camps at level 1, five soldiers per camp at
     * level 1, both "increasing every 5 levels" — and everything else follows the same shape so a
     * player can predict it. [ceiling] exists because an uncapped linear count means a level-500
     * base with 101 town halls' worth of buildings, which is not a base, it is a spreadsheet.
     */
    fun capAt(level: Int, base: Int, step: Int = 5, ceiling: Int = 25): Int {
        if (base <= 0) return 0
        val grown = base + (clampLevel(level) - 1) / step
        return grown.coerceAtMost(ceiling)
    }

    /** Soldiers one training camp holds. Five at level 1, one more every five levels. */
    fun soldiersPerCamp(level: Int): Int = capAt(level, base = 5, step = 5, ceiling = 40)

    /** Training camps allowed. Two at level 1, one more every five levels. */
    fun trainingCamps(level: Int): Int = capAt(level, base = 2, step = 5, ceiling = 20)

    /** The whole army a base can field. */
    fun armyCapacity(level: Int): Int = soldiersPerCamp(level) * trainingCamps(level)

    // -- Territory --------------------------------------------------------------

    /** How many cells one boundary expansion adds to a side of the plot. */
    const val PLOT_EXPANSION_STEP = 3

    /**
     * How many expansions a country's LEVEL allows, capped well short of the top of the game.
     *
     * One becomes available every ten levels, which is what puts the "Expand Boundaries" button
     * in the build sheet on the same cadence as the rest of the level-gated content. The cap exists
     * for the same reason [capAt]'s ceiling does: an uncapped linear count means a field that keeps
     * growing for five hundred levels, which stops being a country's land and starts being a
     * spreadsheet. Twenty expansions is sixty extra cells a side — the field roughly triples — and
     * is reached by level 200, leaving the second half of the game to actually fill it rather than
     * to keep expanding it.
     */
    fun plotExpansionsUnlockedAt(level: Int): Int = (clampLevel(level) / 10).coerceAtMost(20)

    /** The size, in cells, of a plot that has bought [expansions] boundary expansions. */
    fun plotSizeFor(expansions: Int): Int =
        Battle.FIELD + expansions.coerceAtLeast(0) * PLOT_EXPANSION_STEP

    /**
     * What an AI country's plot has grown to at [level].
     *
     * An AI country never presses a button, so it is simply given every expansion its level allows
     * — which is also what makes a high-level AI country visibly larger on the world map than a
     * low-level one, the same as a player's would be.
     */
    fun aiPlotSizeAt(level: Int): Int = plotSizeFor(plotExpansionsUnlockedAt(level))

    // -- XP -------------------------------------------------------------------

    /**
     * XP needed to go from [level] to the next one.
     *
     * Quadratic-ish rather than exponential. Five hundred levels of exponential growth reaches
     * numbers that do not fit in a long and, far worse, reaches a point where a day's play moves
     * the bar by no visible amount. This curve keeps the last levels expensive and the bar visibly
     * moving: level 1→2 costs 120, level 250→251 costs about 95,000, level 499→500 about 377,000.
     */
    fun xpToNext(level: Int): Long {
        val l = clampLevel(level).toLong()
        return 100L + 30L * l + (3L * l * l) / 2L
    }

    /** Total XP a player has spent reaching [level] from level 1. */
    fun xpForLevel(level: Int): Long {
        var total = 0L
        for (l in MIN_LEVEL until clampLevel(level)) total += xpToNext(l)
        return total
    }

    /** The level a given lifetime XP total corresponds to. Inverse of [xpForLevel]. */
    fun levelForXp(xp: Long): Int {
        var level = MIN_LEVEL
        var spent = 0L
        while (level < MAX_LEVEL) {
            val next = spent + xpToNext(level)
            if (next > xp) break
            spent = next
            level++
        }
        return level
    }
}
