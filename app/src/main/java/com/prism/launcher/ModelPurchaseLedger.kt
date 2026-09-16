package com.prism.launcher

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.math.BigInteger

/**
 * Every model purchase this device has made, and whether the seller has been paid yet.
 *
 * ## Why the money is held here rather than on the chain
 *
 * The refund rule -- a model later found on GitHub or Hugging Face is taken down and its buyers
 * made whole -- cannot be met by paying the seller and reversing it afterwards. PrismCoin is proof
 * of work: once a transaction confirms, nobody can take it back, and no amount of app code changes
 * that. Real on-chain escrow would need a timelocked or multi-signature transaction type, and
 * PscTransaction has neither; adding one means changing the bytes a signature commits to, which
 * makes every existing peer reject the new type. That is a fork of a live chain, and not something
 * to do quietly.
 *
 * So the payment is authorised and signed at checkout and simply NOT BROADCAST until
 * [ModelListingScanner] has cleared the listing once. If the scan clears, the transaction goes out
 * and the seller is paid. If it does not, the transaction is discarded and the buyer keeps their
 * coins -- a refund that needs no clawback because no payment ever happened.
 *
 * ## What this costs
 *
 * Delivery risk moves to the seller: the buyer can download during the holding window and walk
 * away. That is the opposite of conventional escrow, and it is the deliberate choice -- the
 * requirement was buyer protection, and this is the side of it a chain without scripts can
 * actually enforce.
 */
object ModelPurchaseLedger {

    private const val PREFS = "prism_model_purchases"
    private const val KEY = "purchases"

    enum class State {
        /** Signed, not broadcast. Waiting for the listing's first clean scan. */
        HELD,

        /** The scan cleared and the transaction went out. */
        PAID,

        /** The listing failed its scan; nothing was sent. */
        CANCELLED,

        /**
         * Paid, then handed back inside the refund window.
         *
         * Distinct from CANCELLED because coins really did move and may or may not come back: the
         * seller's device decides. Collapsing the two would tell a buyer their money was never
         * sent when it was.
         */
        REFUND_REQUESTED
    }

    data class Purchase(
        val listingId: String,
        val sellerAddress: String,
        val modelName: String,
        val amountMinor: BigInteger,
        val committedAt: Long,
        val state: State,
        /** The signed-but-unsent transaction, hex encoded, while [state] is [State.HELD]. */
        val signedTx: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Purchase> {
        val raw = prefs(context).getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Purchase(
                    listingId = o.optString("listing"),
                    sellerAddress = o.optString("seller"),
                    modelName = o.optString("model"),
                    amountMinor = runCatching { BigInteger(o.optString("amount", "0")) }
                        .getOrDefault(BigInteger.ZERO),
                    committedAt = o.optLong("at"),
                    state = runCatching { State.valueOf(o.optString("state")) }
                        .getOrDefault(State.HELD),
                    signedTx = o.optString("tx")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, purchases: List<Purchase>) {
        val array = JSONArray()
        for (p in purchases) {
            array.put(JSONObject().apply {
                put("listing", p.listingId)
                put("seller", p.sellerAddress)
                put("model", p.modelName)
                put("amount", p.amountMinor.toString())
                put("at", p.committedAt)
                put("state", p.state.name)
                put("tx", p.signedTx)
            })
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun record(context: Context, purchase: Purchase) {
        val next = all(context).filterNot { it.listingId == purchase.listingId } + purchase
        save(context, next)
    }

    fun update(context: Context, listingId: String, state: State) {
        val next = all(context).map {
            if (it.listingId == listingId) it.copy(state = state) else it
        }
        save(context, next)
    }

    /** Coins committed but not yet sent -- spendable balance minus this is what is really free. */
    fun heldTotal(context: Context): BigInteger =
        all(context).filter { it.state == State.HELD }
            .fold(BigInteger.ZERO) { acc, p -> acc.add(p.amountMinor) }

    fun held(context: Context): List<Purchase> =
        all(context).filter { it.state == State.HELD }

    /** The purchase for one listing, if this device has bought it. */
    fun find(context: Context, listingId: String): Purchase? =
        all(context).firstOrNull { it.listingId == listingId }

    /**
     * Has this device bought this model and not handed it back?
     *
     * CANCELLED and REFUND_REQUESTED are deliberately excluded: a cancelled purchase sent nothing
     * and a refunded one has had its file deleted, so in both cases the model is not owned and the
     * checkout should offer to buy it again rather than to download something that is not there.
     */
    fun owns(context: Context, listingId: String): Boolean =
        find(context, listingId)?.state.let {
            it == State.HELD || it == State.PAID
        }
}
