package com.prism.launcher.vpn

import android.util.Log
import com.prism.core.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * P2P Signaling Engine (STUN-Lite Registry).
 * Facilitates NAT Hole Punching by allowing nodes to register their presence
 * and lookup peer addresses without a central cloud server.
 */
class DiscoveryEngine(private val context: android.content.Context, private val vpnSecret: String) {

    private val registry = ConcurrentHashMap<String, PeerInfo>()

    data class PeerInfo(
        val lastAddress: InetAddress,
        val lastPort: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        // Discovery Packet Format: [5b "PRISM"] [1b Type] [Var Payload]
        if (data.size < 6) return
        
        val type = data[5].toInt()
        val payload = data.copyOfRange(6, data.size)
        
        when (type) {
            0x01 -> handleRegistration(payload, address, port, socket)
            0x02 -> handleLookup(payload, address, port, socket)
            0x03 -> handleEcho(address, port, socket)
            0x04 -> handleDnsSync(payload, socket) 
            0x05 -> handleDnsWrite(payload, address, port, socket)
        }
    }

    private fun handleRegistration(payload: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        val raw = String(payload)
        val parts = raw.split("|") // [SelfID]|[Password]
        if (parts.size < 2) return
        
        val id = parts[0]
        val pass = parts[1]
        
        if (pass != vpnSecret) {
            Log.w("PrismDiscovery", "Rejected REG from $address: Invalid Secret")
            return
        }
        
        registry[id] = PeerInfo(address, port)
        Log.i("PrismDiscovery", "Node Registered: $id @ $address:$port")
        
        // Confirm Registration
        val resp = "PRISMAOK".toByteArray()
        socket.send(DatagramPacket(resp, resp.size, address, port))
    }

    private fun handleLookup(payload: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        val targetId = String(payload).trim()
        val info = registry[targetId]
        
        val respText = if (info != null) {
            "PRISMFND|${info.lastAddress.hostAddress}|${info.lastPort}"
        } else {
            "PRISM404"
        }
        
        val resp = respText.toByteArray()
        socket.send(DatagramPacket(resp, resp.size, address, port))
        Log.d("PrismDiscovery", "Lookup for $targetId -> ${if (info != null) "Found" else "Not Found"}")
    }

    private fun handleEcho(address: InetAddress, port: Int, socket: DatagramSocket) {
        // Simple STUN Echo
        val resp = "PRISMECO|${address.hostAddress}|$port".toByteArray()
        socket.send(DatagramPacket(resp, resp.size, address, port))
    }

    private fun handleDnsSync(payload: ByteArray, socket: DatagramSocket) {
        try {
            val json = JSONObject(String(payload))
            val records = mutableMapOf<String, com.prism.launcher.browser.P2pDnsManager.DnsRecord>()
            json.keys().forEach { domain ->
                val obj = json.getJSONObject(domain)
                records[domain] = com.prism.launcher.browser.P2pDnsManager.DnsRecord(
                    obj.getString("ip"),
                    emptySet(),
                    obj.getLong("ts"),
                    obj.optBoolean("v", false),
                    com.prism.launcher.browser.P2pDnsManager.ResolutionSource.P2P
                )
            }
            // Context is tricky here, might need to pass it or use a global one.
            // For now, we ingest without saving if context null, or assume P2pDnsManager handles its own persistence.
            // Note: In a real app, DiscoveryEngine would have a weakRef to context.
        } catch (e: Exception) {
            Log.e("PrismDiscovery", "Failed to parse DNS Sync", e)
        }
    }

    private fun handleDnsWrite(payload: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        try {
            val json = JSONObject(String(payload))
            val domain = json.getString("domain")
            // Was `optString("ip", null)`. The portable JSONObject types its fallback as
            // non-null so the other 31 optString call sites keep returning String rather than
            // String?; this spells out the same "absent means null" the null fallback meant.
            val ip = if (json.isNull("ip")) null else json.optString("ip")
            val action = json.getString("action")
            
            Log.i("PrismDiscovery", "Remote DNS Write from $address: $action $domain")
            
            if (action == "ADD" && ip != null) {
                com.prism.launcher.browser.P2pDnsManager.updateRecord(context, domain, ip)
            } else if (action == "DEL") {
                com.prism.launcher.browser.P2pDnsManager.deleteRecord(context, domain)
            }
            
            // Ack the write
            val resp = "PRISMDNS_OK".toByteArray()
            socket.send(DatagramPacket(resp, resp.size, address, port))
        } catch (e: Exception) {
            Log.e("PrismDiscovery", "Failed to process DNS Write Cmd", e)
        }
    }
}
