package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.math.BigInteger

/**
 * What each peer is SELLING, as opposed to what it is serving.
 *
 * ## Why this is not [P2pModelRegistry]
 *
 * That registry answers "who can run model X for me right now" -- it carries a single model name
 * per peer and exists so inference requests can find a host. A shop asks a different question:
 * what is for sale, what is it, and what does it cost. Those need a price, a kind, a parameter
 * count and the seller's PrismCoin address, and a seller may list several models at once where a
 * host serves one. Bolting that onto the hosting registry would have meant widening a packet that
 * every peer already parses, and conflating "is running" with "is for sale" -- a peer can do
 * either without the other.
 *
 * ## Announced, never persisted
 *
 * Same reasoning the hosting registry gives: a listing survives only as long as its seller keeps
 * saying it exists. A stale "peer X sells model Y" that outlives the peer would send buyers to a
 * checkout that can never complete, which is worse than an empty shop.
 */
object P2pModelListings {

    const val OPCODE_LISTINGS: Byte = 0x0D

    /** Listings from a peer we have not heard from in this long are dropped. */
    private const val STALE_MS = 10 * 60 * 1000L

    data class Listing(
        val peerIp: String,
        /** Stable within a seller, so a re-announce updates rather than duplicates. */
        val id: String,
        val name: String,
        /** "text", "image", "video", "audio" -- what the model produces. */
        val kind: String,
        /** Free text as the seller describes it, e.g. "7B" or "1.5B (Q4_K_M)". */
        val parameters: String,
        /** Price in PrismCoin's smallest units. Zero is not a valid listing -- see the shop. */
        val priceMinor: BigInteger,
        /** Where the buyer's PrismCoin goes. */
        val sellerAddress: String,
        val sizeBytes: Long,
        val timestamp: Long
    )

    private val byPeer = mutableMapOf<String, List<Listing>>()

    /** Everything this device is currently offering; re-announced whenever it changes. */
    private var mine: List<Listing> = emptyList()

    fun announce(context: Context, listings: List<Listing>) {
        val myIp = MeshUtils.getLocalMeshIp()
        mine = listings.map { it.copy(peerIp = myIp, timestamp = System.currentTimeMillis()) }
        byPeer[myIp] = mine
        broadcast()
    }

    fun revoke(context: Context) {
        val myIp = MeshUtils.getLocalMeshIp()
        mine = emptyList()
        byPeer.remove(myIp)
        broadcast()
    }

    private fun broadcast() {
        val array = JSONArray()
        for (l in mine) {
            array.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("kind", l.kind)
                put("parameters", l.parameters)
                put("price", l.priceMinor.toString())
                put("seller", l.sellerAddress)
                put("size", l.sizeBytes)
            })
        }
        PrismMeshService.broadcastToOthers(
            OPCODE_LISTINGS, JSONObject().apply { put("listings", array) }.toString()
        )
    }

    /** Called by [PrismMeshService] when a peer's listings packet arrives. */
    fun ingestFromPeer(peerIp: String, payload: String) {
        val parsed = runCatching {
            val array = JSONObject(payload).optJSONArray("listings") ?: JSONArray()
            val now = System.currentTimeMillis()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val price = runCatching { BigInteger(o.optString("price", "0")) }
                    .getOrDefault(BigInteger.ZERO)
                // A free listing is not a listing. Dropping it here means the rule holds even
                // against a peer running a modified client, rather than only in our own upload UI.
                if (price.signum() <= 0) return@mapNotNull null
                val name = o.optString("name", "")
                if (name.isBlank()) return@mapNotNull null
                Listing(
                    peerIp = peerIp,
                    id = o.optString("id", name),
                    name = name,
                    kind = o.optString("kind", "text"),
                    parameters = o.optString("parameters", ""),
                    priceMinor = price,
                    sellerAddress = o.optString("seller", ""),
                    sizeBytes = o.optLong("size", 0L),
                    timestamp = now
                )
            }
        }.getOrDefault(emptyList())

        if (parsed.isEmpty()) byPeer.remove(peerIp) else byPeer[peerIp] = parsed
    }

    /** Every listing we currently believe in, freshest peers only. */
    fun getAll(): List<Listing> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val stale = byPeer.filterValues { list -> list.all { it.timestamp < cutoff } }.keys
        if (stale.isNotEmpty()) byPeer.keys.removeAll(stale)
        return byPeer.values.flatten()
    }
}
