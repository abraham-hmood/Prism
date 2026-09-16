package com.prism.launcher.wallet.psc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The two numbers PrismCoin is judged by, computed separately and never conflated.
 *
 * ## Why there are two
 *
 * The consensus rules peg ISSUANCE: one PSC always costs one reference-hour of computation to mint.
 * Nothing in a consensus rule can peg PRICE. So PrismCoin has an aspiration and a reality, and
 * showing only one of them would be the dishonest choice in either direction:
 *
 *   TARGET VALUE -- what one PSC is meant to be worth if the labour-hour framing held: the global
 *   average hourly wage. Derived, not asserted; see [targetValue].
 *
 *   TRUE VALUE -- what one PSC is demonstrably worth right now. On a coin with no exchange, that is
 *   not a market price, because there is no market. It is the cost of producing one: the
 *   electricity a reference-hour of computation consumes. See [trueValue] for why that is a FLOOR
 *   rather than a price.
 *
 * The gap between them is the honest measure of how the experiment is going, which is exactly why
 * both belong on the wallet screen.
 */
object PrismCoinValuation {

    /**
     * Global mean annual WAGE for employed people, in USD.
     *
     * DELIBERATELY NOT INCOME PER CAPITA, which is what this used to be. The two differ by more
     * than they look: income per capita spreads national income across every person, including
     * everyone who is not working, and divides to about $6.69 an hour. The peg is stated as an
     * average hourly WAGE, which covers employed people only and lands near $9.50. Using the wrong
     * one does not merely shift a label -- it changes what every price in the model shop means.
     *
     * A DEFAULT THAT IS MEANT TO BE REFRESHED. Global wage figures are published less consistently
     * than income per capita (the ILO's are periodic and PPP-adjusted), so this is a hand-set
     * constant rather than something derived. It moves slowly enough that a stale number is a small
     * error and a missing one is a bigger error, so a default ships and a live figure overrides it
     * when one can be fetched.
     */
    const val DEFAULT_GLOBAL_ANNUAL_WAGE_USD = 19_760.0

    /** Full-time hours in a year: 40 hours across 52 weeks. */
    const val ANNUAL_WORKING_HOURS = 40.0 * 52.0

    /** Watts a reference device draws while hashing flat out. */
    const val REFERENCE_DEVICE_WATTS = 5.0

    /** USD per kWh, world average domestic electricity. Overridable for the same reason as income. */
    const val DEFAULT_ELECTRICITY_USD_PER_KWH = 0.16

    /**
     * What one PSC is *meant* to be worth: one hour of average global labour.
     *
     * Computed rather than asserted -- annual wage divided by annual working hours. The result is
     * in USD; [convert] moves it into the user's currency.
     *
     * THE FIGURE IS A MEAN, AND MEANS HIDE A LOT. Global average wages are dragged upward by a
     * minority of high-income economies, so this number is well above what a median worker earns.
     * It is the right input for the stated peg ("average hourly wage globally") and it is not what
     * a typical person is paid.
     */
    fun targetValueUsd(annualWageUsd: Double = DEFAULT_GLOBAL_ANNUAL_WAGE_USD): Double =
        if (annualWageUsd <= 0) 0.0 else annualWageUsd / ANNUAL_WORKING_HOURS

    /**
     * What one PSC demonstrably costs to make: the energy in one reference-hour of hashing.
     *
     * A PRODUCTION FLOOR, NOT A PRICE. Nobody rationally sells below what it cost to produce, so
     * this is a lower bound on what PSC could trade for -- but a coin with no buyers is worth
     * nothing regardless of what it cost, and plenty of coins trade below their production cost.
     * Presenting it as a market price would be a lie; presenting nothing would be useless.
     *
     * Uses the reference device by definition, not the user's actual phone: a PSC minted on
     * efficient hardware and one minted on a power-hungry one are the same coin, so the cost basis
     * has to be the same constant the peg is defined against.
     */
    fun trueValueUsd(
        electricityUsdPerKwh: Double = DEFAULT_ELECTRICITY_USD_PER_KWH,
        deviceWatts: Double = REFERENCE_DEVICE_WATTS,
    ): Double {
        val kwhPerHour = deviceWatts / 1000.0
        return kwhPerHour * electricityUsdPerKwh
    }

    /**
     * A market rate observed on the mesh, when peers have actually traded.
     *
     * Returns null when there are no trades, and callers must show that as "no market" rather than
     * substituting the production floor. The distinction between "worth this much" and "nobody has
     * ever bought one" is the single most important thing this screen communicates.
     */
    fun observedRateUsd(trades: List<Trade>): Double? {
        val recent = trades.filter { it.priceUsd > 0 }.takeLast(50)
        if (recent.isEmpty()) return null
        // Volume-weighted, so one tiny trade cannot set the headline rate.
        val volume = recent.sumOf { it.amountPsc }
        if (volume <= 0) return null
        return recent.sumOf { it.priceUsd * it.amountPsc } / volume
    }

    data class Trade(val amountPsc: Double, val priceUsd: Double, val timestamp: Long)

    /** Converts a USD figure into another currency given a rate. */
    fun convert(usd: Double, usdToLocalRate: Double): Double = usd * usdToLocalRate

    /**
     * How far the true value sits from the target, as a ratio.
     *
     * 1.0 would mean the labour-hour peg is holding. Anything far below means a PSC buys far less
     * than an hour of labour, which is the expected state for a young coin and worth showing
     * plainly rather than hiding.
     */
    fun pegRatio(trueValueUsd: Double, targetValueUsd: Double): Double =
        if (targetValueUsd <= 0) 0.0 else trueValueUsd / targetValueUsd

    /** Formats a money amount to 2 decimals, or more when it would otherwise read as zero. */
    fun formatMoney(value: Double, currency: String): String {
        val scaled = BigDecimal(value, MathContext.DECIMAL64)
        val decimals = when {
            value >= 1.0 -> 2
            value >= 0.01 -> 4
            else -> 6
        }
        return "${scaled.setScale(decimals, RoundingMode.HALF_UP).toPlainString()} $currency"
    }
}
