package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.core.json.JSONObject

/** Wire metadata and operator commands shared by both mesh transports. */
object PrismNodeWire {
    data class ServerEndpoint(
        val port: Int,
        val protocol: String,
        val publicKey: String? = null,
        val wireGuardPort: Int? = null,
    )

    data class Announcement(
        val nodeId: String,
        val name: String,
        val meshPort: Int,
        val serverPort: Int?,
        val serverProtocol: String?,
        val serverPublicKey: String?,
        val wireGuardPort: Int?,
        /** Two-letter country the device declares, or empty. Used by the VPN page. */
        val country: String = "",
        /** Where this device wants Prism Coin sent for traffic it carries. Empty when it has none. */
        val payout: String = "",
    )

    fun announcement(
        meshPort: Int,
        serverPort: Int? = null,
        serverProtocol: String? = null,
        serverPublicKey: String? = null,
        wireGuardPort: Int? = null,
        country: String? = null,
        payout: String? = null,
    ): String = JSONObject()
        .put("nodeId", PrismSettings.getPrismNodeId())
        .put("nodeName", PrismSettings.getPrismNodeName())
        .put("port", meshPort)
        .apply {
            if (serverPort != null) put("serverPort", serverPort)
            if (!serverProtocol.isNullOrBlank()) put("serverProtocol", serverProtocol)
            if (!serverPublicKey.isNullOrBlank()) put("serverPublicKey", serverPublicKey)
            if (wireGuardPort != null) put("wireGuardPort", wireGuardPort)
            // Both are optional on the wire: an older peer ignores them, and a peer that has not set
            // a country is simply not offered as a VPN exit rather than being guessed at.
            if (!country.isNullOrBlank()) put("country", country.trim().uppercase())
            if (!payout.isNullOrBlank()) put("payout", payout.trim())
        }
        .toString()

    fun parse(payload: String): Announcement? = runCatching {
        val json = JSONObject(payload)
        val nodeId = json.optString("nodeId").trim()
        if (nodeId.isEmpty()) return null
        Announcement(
            nodeId = nodeId,
            name = json.optString("nodeName").trim(),
            meshPort = json.optInt("port", MeshCore.DEFAULT_PORT),
            serverPort = json.optInt("serverPort", 0).takeIf { it in 1..65535 },
            serverProtocol = json.optString("serverProtocol").trim().ifBlank { null },
            serverPublicKey = json.optString("serverPublicKey").trim().ifBlank { null },
            wireGuardPort = json.optInt("wireGuardPort", 0).takeIf { it in 1..65535 },
            country = json.optString("country").trim().uppercase(),
            payout = json.optString("payout").trim(),
        )
    }.getOrNull()
}
