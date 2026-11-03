package com.prism.launcher.helper

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletNetwork
import com.prism.launcher.wallet.WalletVault
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Dollars, carried as whole micro-units.
 *
 * ## Why USDC is the only amount in here
 *
 * A bill is denominated in whatever the biller chose and a payment is denominated in USDC, and the
 * two are not the same number. Everything downstream -- the progress bar, the ledger, the amount the
 * donor is asked to confirm -- is in USDC's smallest unit (six decimals), so conversion happens once,
 * at the moment the bill is read, and the currency the bill was written in is kept beside it purely
 * for display. Converting at the point of payment instead would mean every donor's screen computing
 * a slightly different figure from a rate that had moved since the request was posted.
 */
object HelpMoney {

    /** USDC has six decimals on Ethereum. */
    const val DECIMALS = 6

    private val SCALE = BigDecimal.TEN.pow(DECIMALS)

    fun micros(usd: BigDecimal): Long =
        usd.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).toLong()

    fun usd(micros: Long): BigDecimal =
        BigDecimal.valueOf(micros).divide(SCALE, DECIMALS, RoundingMode.DOWN)

    /** `$38.40`, always two decimals: a progress figure with a trimmed tail reads as an estimate. */
    fun format(micros: Long): String =
        "$" + usd(micros).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun parseUsd(text: String): Long? {
        val cleaned = text.trim().removePrefix("$").replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return micros(value)
    }

    /** A percentage of an amount, rounded to the nearest micro-unit. */
    fun percentOf(micros: Long, percent: Int): Long =
        BigDecimal.valueOf(micros)
            .multiply(BigDecimal.valueOf(percent.toLong()))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .toLong()

    /**
     * A bill's amount, converted to USDC.
     *
     * The rate comes from the wallet's own price source: `usdRateFor` answers "how many units of this
     * currency is one dollar", which is exactly the divisor. A currency with no known rate is treated
     * as dollars and the returned [Conversion.note] says so, because labelling a euro bill as dollars
     * silently would ask donors for the wrong amount with no way to notice.
     */
    fun toUsdc(amount: BigDecimal, currency: String): Conversion {
        val code = currency.uppercase().ifBlank { "USD" }
        val rate = runCatching { WalletNetwork.usdRateFor(code) }.getOrNull()
        val divisor = if (rate == null || rate <= 0.0) 1.0 else rate
        val dollars = amount.divide(BigDecimal.valueOf(divisor), DECIMALS, RoundingMode.HALF_UP)
        val note = when {
            code == "USD" -> ""
            divisor == 1.0 -> "No $code rate was available, so this is treated as dollars."
            else -> "Converted from $code at ${BigDecimal.valueOf(divisor).setScale(4, RoundingMode.HALF_UP)} per dollar."
        }
        return Conversion(micros(dollars), note)
    }

    data class Conversion(val micros: Long, val note: String)
}

/**
 * Who this device posts as.
 *
 * The name is the mesh name, not an account: there is no signup, no email and no password anywhere in
 * this feature, because the only two things a request needs are something to call the author and an
 * address to pay. The address is derived from the wallet's recovery phrase like every other coin
 * address in Prism, so it is recoverable and belongs to the user rather than to this feature.
 *
 * A NAME THAT CAN BE CHANGED, AND ONE THAT CANNOT. [id] is created once and never changes, because
 * the store and every remote peer key a request and a contribution on it; [name] is a label shown
 * beside those, so renaming yourself must not orphan posts you already made.
 */
object HelpIdentity {

    private const val PREFS = "prism_helper"
    private const val KEY_ID = "helper_id"
    private const val KEY_NAME = "helper_name"

    fun id(): String {
        val prefs = PrismPlatform.host.prefs(PREFS)
        val existing = prefs.getString(KEY_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = "help-" + UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    fun name(): String {
        val prefs = PrismPlatform.host.prefs(PREFS)
        val existing = prefs.getString(KEY_NAME, null)
        if (!existing.isNullOrBlank()) return existing
        // A default rather than a prompt: somebody who opens Helper to give should not be stopped by a
        // form, and the name is theirs to change the first time they post.
        val generated = "prism" + id().takeLast(6)
        prefs.edit().putString(KEY_NAME, generated).apply()
        return generated
    }

    fun rename(value: String) {
        val clean = value.trim().take(32)
        if (clean.isNotEmpty()) PrismPlatform.host.prefs(PREFS).edit().putString(KEY_NAME, clean).apply()
    }

    /** The USDC address this device is paid at, or null when the wallet is not set up. */
    fun usdcAddress(): String? = runCatching {
        val coin = CoinRegistry.bySymbol("USDC") ?: return null
        val seed = WalletVault.seed() ?: return null
        WalletVault.account(coin, seed).address
    }.getOrNull()
}

/** One payment somebody made toward a request. */
data class HelpContribution(
    val id: String,
    val donorId: String,
    val donorName: String,
    val donorAddress: String,
    val amountMicros: Long,
    val txId: String,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("donor", donorId)
        put("donor_name", donorName)
        put("donor_address", donorAddress)
        put("amount", amountMicros.toString())
        put("tx", txId)
        put("at", at)
    }

    companion object {
        fun fromJson(item: JSONObject, fallbackAt: Long): HelpContribution? {
            val amount = item.optString("amount", "0").toLongOrNull() ?: return null
            if (amount <= 0L) return null
            return HelpContribution(
                id = item.optString("id", "").ifBlank { "c-" + (item.optString("tx", "") + amount) },
                donorId = item.optString("donor", ""),
                donorName = item.optString("donor_name", "").ifBlank { "someone" },
                donorAddress = item.optString("donor_address", ""),
                amountMicros = amount,
                txId = item.optString("tx", ""),
                at = item.optLong("at", fallbackAt),
            )
        }
    }
}

/**
 * A bill somebody has asked for help with.
 *
 * ## The target is a percentage of the bill, not the bill
 *
 * Asking for part of a bill is the difference between a request that can be filled and one that is a
 * standing appeal for a whole month's electricity. It also caps what a donation means: contributions
 * stop counting at [targetMicros], so a request cannot quietly raise more than it asked for and a
 * donor is never paying into an open-ended pot.
 *
 * ## Why contributions are inside the request
 *
 * Because the list has to be able to show progress without a second lookup per row, and because a
 * contribution is only meaningful in relation to the request it settles. Merging two copies of a
 * request unions contributions BY TX ID: the same payment can arrive from a peer's gossip after this
 * device already recorded it from its own send, and counting it twice would overstate the progress.
 */
data class HelpRequest(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorAddress: String,
    val reason: String,
    /** The whole bill, in USDC micro-units. */
    val billMicros: Long,
    /** What percentage of it the author is asking for. 1..100. */
    val percent: Int,
    /** What the bill was written in, for display next to the converted figure. */
    val currency: String,
    val billLabel: String,
    val dueLabel: String,
    /** One line describing how the bill was checked, shown on the detail view. */
    val verification: String,
    /** Local path to the uploaded PDF. Empty on requests learned from a peer. */
    val billPath: String,
    val createdAt: Long,
    val contributions: List<HelpContribution> = emptyList(),
) {

    val targetMicros: Long get() = HelpMoney.percentOf(billMicros, percent.coerceIn(1, 100))

    val raisedMicros: Long
        get() = contributions.fold(0L) { sum, item -> sum + item.amountMicros }
            .coerceAtMost(targetMicros)

    val remainingMicros: Long get() = (targetMicros - raisedMicros).coerceAtLeast(0L)

    val funded: Boolean get() = remainingMicros <= 0L

    val progressPercent: Int
        get() = if (targetMicros <= 0L) 0 else ((raisedMicros * 100) / targetMicros).toInt().coerceIn(0, 100)

    val isMine: Boolean get() = authorId == HelpIdentity.id()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("author", authorId)
        put("author_name", authorName)
        put("address", authorAddress)
        put("reason", reason)
        put("bill", billMicros.toString())
        put("percent", percent)
        put("currency", currency)
        put("label", billLabel)
        put("due", dueLabel)
        put("verification", verification)
        put("at", createdAt)
        val array = JSONArray()
        for (item in contributions) array.put(item.toJson())
        put("contributions", array)
    }

    /**
     * This request with [other]'s content and both sets of contributions.
     *
     * [other] wins on the author's own fields because a peer that re-announces its own request is the
     * authority on its reason and its percentage; contributions are unioned by tx id so a payment is
     * never counted twice or lost in a merge.
     */
    fun merged(other: HelpRequest): HelpRequest {
        val byKey = LinkedHashMap<String, HelpContribution>()
        for (item in contributions + other.contributions) {
            val key = item.txId.ifBlank { item.id }
            val previous = byKey[key]
            if (previous == null || item.at < previous.at) byKey[key] = item
        }
        return other.copy(
            billPath = other.billPath.ifBlank { billPath },
            createdAt = minOf(createdAt, other.createdAt),
            contributions = byKey.values.sortedBy { it.at },
        )
    }

    companion object {
        fun fromJson(item: JSONObject): HelpRequest? {
            val id = item.optString("id", "")
            val author = item.optString("author", "")
            val bill = item.optString("bill", "0").toLongOrNull() ?: 0L
            if (id.isBlank() || author.isBlank() || bill <= 0L) return null
            val percent = item.optInt("percent", 100).coerceIn(1, 100)
            val contributions = item.optJSONArray("contributions")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { HelpContribution.fromJson(it, item.optLong("at", 0L)) }
                }
            }.orEmpty()
            return HelpRequest(
                id = id,
                authorId = author,
                authorName = item.optString("author_name", "").ifBlank { "someone" },
                authorAddress = item.optString("address", ""),
                reason = item.optString("reason", ""),
                billMicros = bill,
                percent = percent,
                currency = item.optString("currency", "USD"),
                billLabel = item.optString("label", "Bill"),
                dueLabel = item.optString("due", ""),
                verification = item.optString("verification", ""),
                billPath = "",
                createdAt = item.optLong("at", 0L),
                contributions = contributions,
            )
        }

        fun newId(): String = "bill-" + UUID.randomUUID().toString().replace("-", "").take(12)

        fun newContributionId(): String = "give-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
