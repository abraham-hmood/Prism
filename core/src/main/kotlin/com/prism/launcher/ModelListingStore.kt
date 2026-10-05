package com.prism.launcher

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletVault
import java.math.BigInteger

/**
 * What this device is selling.
 *
 * ## Why listings are kept rather than only announced
 *
 * [P2pModelListings] holds what is being gossiped RIGHT NOW and deliberately forgets it on restart,
 * because a listing that outlives its host sends buyers to a checkout that can never complete. That
 * is right for other people's listings and wrong for your own: a seller who lists a model, closes
 * Prism and reopens it has not withdrawn anything, and should not have to re-list. So the seller's
 * side is persisted here and re-announced on launch.
 */
object ModelListingStore {

    private const val PREFS = "prism_model_listings"
    private const val KEY = "mine"

    private fun prefs() =
        PrismPlatform.host.prefs(PREFS)

    data class Listed(
        val id: String,
        val name: String,
        val kind: String,
        val parameters: String,
        val priceMinor: BigInteger,
        val sizeBytes: Long
    )

    fun all(): List<Listed> {
        val raw = prefs().getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Listed(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    kind = o.optString("kind", "text"),
                    parameters = o.optString("parameters", ""),
                    priceMinor = runCatching { BigInteger(o.optString("price", "0")) }
                        .getOrDefault(BigInteger.ZERO),
                    sizeBytes = o.optLong("size", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(listings: List<Listed>) {
        val array = JSONArray()
        for (l in listings) {
            array.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("kind", l.kind)
                put("parameters", l.parameters)
                put("price", l.priceMinor.toString())
                put("size", l.sizeBytes)
            })
        }
        prefs().edit().putString(KEY, array.toString()).apply()
    }

    fun add(listed: Listed) {
        save(all().filterNot { it.id == listed.id } + listed)
        announce()
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        announce()
    }

    /**
     * Puts every stored listing on the mesh, with the seller's PrismCoin address attached.
     *
     * The address is resolved here rather than stored, so a listing can never advertise an address
     * this wallet would not actually receive at -- if the wallet is replaced, the listings follow it
     * instead of pointing at coins nobody holds.
     */
    fun announce() {
        val listings = all()
        if (listings.isEmpty()) {
            P2pModelListings.revoke()
            return
        }
        val address = runCatching {
            CoinRegistry.bySymbol("PSC")?.let { WalletVault.addressFor(it) }
        }.getOrNull()
        if (address.isNullOrBlank()) {
            PrismPlatform.log.warn(
                "ModelShop",
                "Not announcing ${listings.size} listing(s): this device has no PrismCoin address, " +
                    "so a buyer would have nowhere to pay."
            )
            return
        }
        P2pModelListings.announce(listings.map {
                P2pModelListings.Listing(
                    peerIp = "",
                    id = it.id,
                    name = it.name,
                    kind = it.kind,
                    parameters = it.parameters,
                    priceMinor = it.priceMinor,
                    sellerAddress = address,
                    sizeBytes = it.sizeBytes,
                    timestamp = System.currentTimeMillis()
                )
            }
        )
    }
}
