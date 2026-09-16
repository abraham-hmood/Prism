package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.math.BigDecimal

/**
 * A peer-to-peer order book for swapping coins, carried on mesh gossip.
 *
 * ## Why this exists at all
 *
 * Prism's wallet holds real coins on real chains but has no exchange behind it, so there is no
 * route from one coin to another -- and PrismCoin has no listing anywhere, so nothing in the world
 * will give you USDC for it. A Convert button that moved numbers in a local database would show
 * people a balance they could not spend, which is the one outcome worth refusing outright. So
 * conversion is what it honestly is: an offer to another human, matched over the mesh.
 *
 * ## It is NOT an atomic swap, and says so
 *
 * Settling a swap without trust needs hash-timelocked contracts on BOTH chains. PrismCoin has no
 * script system at all, so that is not available here and cannot be added without changing
 * consensus. What this does is discovery and quoting: it advertises who wants what, at what rate,
 * and carries the addresses each side must pay. One party still has to send first, and the UI is
 * required to say so rather than implying the trade is escrowed.
 *
 * ## PrismCoin's pair restriction
 *
 * PSC may only be quoted against USDC. That is a product rule rather than a technical one: PSC is
 * pegged, by its issuance schedule, to an hour of average global labour, and quoting it against a
 * volatile asset would make that peg meaningless the moment the other side moved.
 */
object P2pCoinOffers {

    const val OPCODE_OFFERS: Byte = 0x0E

    private const val STALE_MS = 15 * 60 * 1000L

    /** The only asset PrismCoin may be exchanged against. See the class comment. */
    const val PSC_PAIRED_WITH = "USDC"

    data class Offer(
        val peerIp: String,
        val id: String,
        /** What the maker is giving away. */
        val fromSymbol: String,
        /** What the maker wants in return. */
        val toSymbol: String,
        val fromAmount: BigDecimal,
        val toAmount: BigDecimal,
        /** Where the taker sends the [toSymbol] side. */
        val makerReceiveAddress: String,
        val timestamp: Long
    ) {
        /** How much of [toSymbol] one unit of [fromSymbol] is being asked for. */
        fun rate(): BigDecimal =
            if (fromAmount.signum() == 0) BigDecimal.ZERO
            else toAmount.divide(fromAmount, java.math.MathContext.DECIMAL64)
    }

    private val byPeer = mutableMapOf<String, List<Offer>>()
    private var mine: List<Offer> = emptyList()

    /** Whether a pair may be quoted. Rejects free-floating PSC in either direction. */
    fun isPairAllowed(from: String, to: String): Boolean {
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return false
        if (f == "PSC") return t == PSC_PAIRED_WITH
        if (t == "PSC") return f == PSC_PAIRED_WITH
        return true
    }

    fun post(context: Context, offer: Offer) {
        val myIp = MeshUtils.getLocalMeshIp()
        mine = mine.filterNot { it.id == offer.id } +
            offer.copy(peerIp = myIp, timestamp = System.currentTimeMillis())
        byPeer[myIp] = mine
        broadcast()
    }

    fun cancel(context: Context, offerId: String) {
        mine = mine.filterNot { it.id == offerId }
        byPeer[MeshUtils.getLocalMeshIp()] = mine
        broadcast()
    }

    private fun broadcast() {
        val array = JSONArray()
        for (o in mine) {
            array.put(JSONObject().apply {
                put("id", o.id)
                put("from", o.fromSymbol)
                put("to", o.toSymbol)
                put("fromAmount", o.fromAmount.toPlainString())
                put("toAmount", o.toAmount.toPlainString())
                put("addr", o.makerReceiveAddress)
            })
        }
        PrismMeshService.broadcastToOthers(
            OPCODE_OFFERS, JSONObject().apply { put("offers", array) }.toString()
        )
    }

    /** Called by [PrismMeshService] when a peer's offer packet arrives. */
    fun ingestFromPeer(peerIp: String, payload: String) {
        val parsed = runCatching {
            val array = JSONObject(payload).optJSONArray("offers") ?: JSONArray()
            val now = System.currentTimeMillis()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val from = o.optString("from").uppercase()
                val to = o.optString("to").uppercase()
                // Enforced on arrival as well as on posting, so a modified client cannot put an
                // unpegged PSC quote in front of everyone else.
                if (!isPairAllowed(from, to)) return@mapNotNull null
                val fromAmount = o.optString("fromAmount").toBigDecimalOrNull()
                    ?: return@mapNotNull null
                val toAmount = o.optString("toAmount").toBigDecimalOrNull()
                    ?: return@mapNotNull null
                if (fromAmount.signum() <= 0 || toAmount.signum() <= 0) return@mapNotNull null
                val addr = o.optString("addr")
                if (addr.isBlank()) return@mapNotNull null
                Offer(peerIp, o.optString("id", "$from-$to-$i"), from, to,
                    fromAmount, toAmount, addr, now)
            }
        }.getOrDefault(emptyList())

        if (parsed.isEmpty()) byPeer.remove(peerIp) else byPeer[peerIp] = parsed
    }

    fun getAll(): List<Offer> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val stale = byPeer.filterValues { list -> list.all { it.timestamp < cutoff } }.keys
        if (stale.isNotEmpty()) byPeer.keys.removeAll(stale)
        return byPeer.values.flatten()
    }

    /** Offers that would give the caller [want] in exchange for [give], best rate first. */
    fun matching(give: String, want: String): List<Offer> =
        getAll().filter {
            it.fromSymbol.equals(want, true) && it.toSymbol.equals(give, true)
        }.sortedByDescending { it.rate() }
}
