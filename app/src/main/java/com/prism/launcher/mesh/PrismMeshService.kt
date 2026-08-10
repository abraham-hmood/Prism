package com.prism.launcher.mesh

import android.util.Log
import android.widget.Toast
import com.prism.core.MeshUtils
import com.prism.launcher.PrismApp
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.browser.P2pDnsManager
import kotlinx.coroutines.*
import com.prism.core.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The Control Plane for the Prism Mesh.
 * Handles UDP-based discovery, heartbeats, and DNS record propagation.
 */
object PrismMeshService {
    private const val TAG = "PrismMesh"
    private const val DEFAULT_MESH_PORT = 8081
    private const val PROTOCOL_HEADER = "PRISM"

    // Without a handler, an unhandled failure inside a SupervisorJob scope can be dropped
    // without ever reaching the global uncaught-exception handler -- so a mesh service that
    // quietly stopped gossiping would leave nothing at all in diagnostics.
    private val meshScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            com.prism.launcher.PrismLogger.coroutineHandler("Mesh")
    )
    private var socket: DatagramSocket? = null
    
    // List of active peers (IP -> PeerInfo)
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    
    // Tracking RTT for "Best Connection" selection
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    data class PeerInfo(
        var lastSeen: Long,
        var port: Int = DEFAULT_MESH_PORT,
        var latency: Long = 9999L
    )

    fun start() {
        val context = PrismApp.instance
        val meshPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }

        meshScope.launch {
            try {
                socket = DatagramSocket(meshPort).apply {
                    reuseAddress = true
                    broadcast = true
                }
                
                // Try to bypass VPN for the mesh control plane
                try {
                    val vpnServiceClass = Class.forName("com.prism.launcher.browser.PrivateDnsVpnService")
                    val protectMethod = vpnServiceClass.getMethod("protectSocket", DatagramSocket::class.java)
                    protectMethod.invoke(null, socket)
                } catch (e: Exception) {}
                
                PrismLogger.logSuccess(TAG, "Decentralized Mesh active on port $meshPort")
                
                val buffer = ByteArray(16 * 1024)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    handlePacket(packet)
                }
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Mesh listener failed", e)
            }
        }
        
        // Peer Discovery & Gossip Task
        meshScope.launch {
            while (isActive) {
                sendDiscoveryBroadcast(meshPort)

                val nodes = PrismSettings.getAllMeshNodes()
                val myIps = MeshUtils.getAllLocalIps()
                nodes.forEach { addr ->
                    if (!myIps.contains(addr)) {
                        sendPacket(addr, meshPort, 0x01.toByte(), "{}")
                    }
                }

                val now = System.currentTimeMillis()
                // Evict stale peers
                activePeers.entries.removeIf { it.value.lastSeen < now - 300000L } 
                
                if (activePeers.isNotEmpty()) {
                    val randomPeerIp = activePeers.keys.random()
                    val peerInfo = activePeers[randomPeerIp]
                    if (peerInfo != null) {
                        // Periodic RTT check and Sync
                        sendPacket(randomPeerIp, peerInfo.port, 0x01.toByte(), "{}")
                        if (java.util.Random().nextInt(3) == 0) {
                            requestSync(randomPeerIp)
                        }
                    }
                    
                    activePeers.forEach { (ip, info) ->
                        if (now - info.lastSeen > 30000) {
                            sendPacket(ip, info.port, 0x01.toByte(), "{}")
                        }
                    }
                }
                
                delay(if (activePeers.isEmpty()) 5000 else 15000)
            }
        }
    }

    private fun sendDiscoveryBroadcast(port: Int) {
        val broadcasts = MeshUtils.getBroadcastAddresses()
        broadcasts.forEach { addr ->
            sendPacket(addr.hostAddress, port, 0x0A.toByte(), "{\"port\":$port}")
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val rawIp = packet.address.hostAddress ?: return
        val peerIp = if (rawIp.startsWith("::ffff:")) rawIp.substring(7) else rawIp
        
        val data = packet.data.sliceArray(0 until packet.length)
        if (data.size < 6) return
        
        val header = String(data, 0, 5)
        if (header != PROTOCOL_HEADER) return
        
        if (MeshUtils.getAllLocalIps().contains(peerIp)) return

        val now = System.currentTimeMillis()
        val command = data[5]
        val payload = String(data, 6, data.size - 6)

        // RTT Tracking: Map incoming response commands to their requests
        val responseTo = when (command) {
            0x0B.toByte() -> 0x01.toByte() // HeartbeatResp -> Heartbeat
            0x03.toByte() -> 0x02.toByte() // PeerListResp -> PeerListReq
            0x09.toByte() -> 0x08.toByte() // DnsSyncResp -> DnsSyncReq
            else -> null
        }

        if (responseTo != null) {
            val sentAt = pendingRequests.remove("$peerIp:$responseTo")
            if (sentAt != null) {
                val latency = now - sentAt
                activePeers[peerIp]?.let { it.latency = latency }
            }
        }

        val isNew = !activePeers.containsKey(peerIp)
        val info = activePeers.getOrPut(peerIp) { 
            PeerInfo(now, port = packet.port) 
        }
        info.lastSeen = now
        info.port = packet.port
        
        if (isNew) {
            showToast("Mesh: Peer discovered ($peerIp)")
        }

        when (command) {
            0x01.toByte() -> handleHeartbeat(peerIp, info.port)
            0x02.toByte() -> handlePeerListReq(peerIp, info.port)
            0x03.toByte() -> handlePeerListResp(payload)
            0x05.toByte() -> handleDnsWrite(payload, peerIp)
            0x08.toByte() -> handleDnsSyncReq(peerIp, info.port, payload)
            0x09.toByte() -> handleDnsSyncResp(peerIp, payload)
            0x0A.toByte() -> handleDiscoveryReq(peerIp, payload)
            0x0B.toByte() -> handleHeartbeatResp(peerIp, payload)
            P2pModelRegistry.OPCODE_MODEL_ANNOUNCE -> handleModelAnnounce(payload, peerIp)
        }
    }

    private fun handleHeartbeat(peerIp: String, port: Int) {
        val myPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }
        sendPacket(peerIp, port, 0x0B.toByte(), "{\"port\":$myPort}")
    }
    
    private fun handleHeartbeatResp(peerIp: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val peerPort = json.optInt("port", DEFAULT_MESH_PORT)
            activePeers[peerIp]?.port = peerPort
        } catch (e: Exception) {}
    }

    private fun handleDiscoveryReq(peerIp: String, payload: String) {
        showToast("Mesh: Client connected ($peerIp)")
        try {
            val json = JSONObject(payload)
            val peerPort = json.optInt("port", DEFAULT_MESH_PORT)
            activePeers[peerIp]?.port = peerPort
            val myPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }
            sendPacket(peerIp, peerPort, 0x0B.toByte(), "{\"port\":$myPort}")
            requestSync(peerIp)
        } catch (e: Exception) {}
    }

    private fun handlePeerListReq(peerIp: String, port: Int) {
        val myIp = MeshUtils.getLocalMeshIp()
        val myPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }
        val selfEntry = "$myIp:$myPort"
        val otherPeers = activePeers.entries.joinToString(",") { "${it.key}:${it.value.port}" }
        val fullList = if (otherPeers.isEmpty()) selfEntry else "$selfEntry,$otherPeers"
        sendPacket(peerIp, port, 0x03.toByte(), fullList)
    }

    private fun handlePeerListResp(payload: String) {
        val myIps = MeshUtils.getAllLocalIps()
        payload.split(",").filter { it.isNotBlank() }.forEach { entry ->
            try {
                val parts = entry.split(":")
                val ip = parts[0]
                val port = parts.getOrNull(1)?.toInt() ?: DEFAULT_MESH_PORT
                if (!myIps.contains(ip) && !activePeers.containsKey(ip)) {
                    activePeers[ip] = PeerInfo(System.currentTimeMillis(), port = port)
                    sendPacket(ip, port, 0x01.toByte(), "{}")
                    requestSync(ip)
                }
            } catch (e: Exception) {}
        }
    }

    private fun handleModelAnnounce(payload: String, peerIp: String) {
        try {
            val json = JSONObject(payload)
            val modelName = json.optString("model", "")
            P2pModelRegistry.ingestFromPeer(peerIp, modelName)
        } catch (e: Exception) {}
    }

    private fun handleDnsWrite(payload: String, sourceIp: String) {
        try {
            val json = JSONObject(payload)
            val domain = json.getString("domain")
            val ip = json.getString("ip")
            P2pDnsManager.updateRecord(PrismApp.instance, domain, ip, isVerified = true, fromMesh = true)
            broadcastToOthers(0x05.toByte(), payload, sourceIp)
        } catch (e: Exception) {}
    }

    private fun handleDnsSyncReq(peerIp: String, port: Int, payload: String) {
        showToast("Mesh: Sync requested by $peerIp")
        try {
            val json = JSONObject(payload)
            if (json.has("dns")) {
                ingestDnsJson(json.getJSONObject("dns"))
            }
        } catch (e: Exception) {}

        val response = JSONObject().apply {
            put("dns", getLocalDnsJson())
            put("peers", getPeerListString())
        }
        sendPacket(peerIp, port, 0x09.toByte(), response.toString())
    }

    private fun handleDnsSyncResp(peerIp: String, payload: String) {
        try {
            val json = JSONObject(payload)
            if (json.has("dns")) ingestDnsJson(json.getJSONObject("dns"))
            if (json.has("peers")) handlePeerListResp(json.getString("peers"))
            showToast("Mesh: Sync received from $peerIp")
        } catch (e: Exception) {}
    }

    private fun ingestDnsJson(dnsObj: JSONObject) {
        val peerRecords = mutableMapOf<String, P2pDnsManager.DnsRecord>()
        dnsObj.keys().forEach { domain ->
            try {
                val obj = dnsObj.getJSONObject(domain)
                val srcStr = obj.optString("src", P2pDnsManager.ResolutionSource.P2P.name)
                val source = try { P2pDnsManager.ResolutionSource.valueOf(srcStr) } catch (e: Exception) { P2pDnsManager.ResolutionSource.P2P }

                val altArray = obj.optJSONArray("alts")
                val alts = mutableSetOf<String>()
                if (altArray != null) {
                    for (i in 0 until altArray.length()) alts.add(altArray.getString(i))
                }

                peerRecords[domain] = P2pDnsManager.DnsRecord(
                    obj.getString("ip"),
                    alts,
                    obj.getLong("ts"),
                    obj.optBoolean("v", false),
                    source
                )
            } catch (e: Exception) {}
        }
        P2pDnsManager.ingestFromPeer(PrismApp.instance, peerRecords)
    }

    fun broadcastDnsUpdate(domain: String) {
        val records = P2pDnsManager.getRecords()
        val record = records[domain] ?: return
        
        val myIp = MeshUtils.getLocalMeshIp()
        val broadcastIp = if (record.ip == "127.0.0.1") myIp else record.ip

        val payload = JSONObject().apply {
            put("domain", domain)
            put("ip", broadcastIp)
            put("ts", record.timestamp)
            val publicAlts = record.alternates.filter { it != "127.0.0.1" }
            if (publicAlts.isNotEmpty()) put("alts", com.prism.core.json.JSONArray(publicAlts))
        }.toString()
        
        broadcastToOthers(0x05.toByte(), payload)
    }

    private fun getLocalDnsJson(): JSONObject {
        val dnsJson = JSONObject()
        val myIp = MeshUtils.getLocalMeshIp()
        P2pDnsManager.getRecords().forEach { (domain, record) ->
            dnsJson.put(domain, JSONObject().apply {
                val broadcastIp = if (record.ip == "127.0.0.1") myIp else record.ip
                put("ip", broadcastIp)
                put("ts", record.timestamp)
                put("v", record.isVerified)
                put("src", record.source.name)
                
                val publicAlts = record.alternates.filter { it != "127.0.0.1" }.toMutableSet()
                if (record.ip == "127.0.0.1" && broadcastIp != record.ip) {
                   // no-op, already primary
                }
                
                if (publicAlts.isNotEmpty()) {
                    put("alts", com.prism.core.json.JSONArray(publicAlts.toList()))
                }
            })
        }
        PrismSettings.getP2pHostedSites().forEach { site ->
            if (!dnsJson.has(site.domain)) {
                dnsJson.put(site.domain, JSONObject().apply {
                    put("ip", myIp)
                    put("ts", System.currentTimeMillis())
                    put("v", true)
                })
            }
        }
        return dnsJson
    }

    private fun getPeerListString(): String {
        val myIp = MeshUtils.getLocalMeshIp()
        val myPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }
        val self = "$myIp:$myPort"
        val others = activePeers.entries.joinToString(",") { "${it.key}:${it.value.port}" }
        return if (others.isEmpty()) self else "$self,$others"
    }

    fun requestSync(targetIp: String) {
        val info = activePeers[targetIp] ?: return
        val payload = JSONObject().apply { put("dns", getLocalDnsJson()) }
        sendPacket(targetIp, info.port, 0x08.toByte(), payload.toString())
    }

    fun sendPacket(targetIp: String, targetPort: Int, command: Byte, payload: String) {
        meshScope.launch {
            try {
                val payloadBytes = payload.toByteArray()
                val data = ByteArray(6 + payloadBytes.size)
                PROTOCOL_HEADER.toByteArray().copyInto(data)
                data[5] = command
                payloadBytes.copyInto(data, 6)
                
                // Track sending time for latency measurement
                if (command == 0x01.toByte() || command == 0x02.toByte() || command == 0x08.toByte()) {
                    pendingRequests["$targetIp:$command"] = System.currentTimeMillis()
                }
                
                val packet = DatagramPacket(data, data.size, InetAddress.getByName(targetIp), targetPort)
                socket?.send(packet)
            } catch (e: Exception) {}
        }
    }

    fun broadcastToOthers(command: Byte, payload: String, sourceIp: String? = null) {
        activePeers.forEach { (peerIp, info) ->
            if (peerIp != sourceIp) sendPacket(peerIp, info.port, command, payload)
        }
    }

    fun getBestNode(): String? {
        if (activePeers.isEmpty()) return null
        val now = System.currentTimeMillis()
        val healthyPeers = activePeers.entries.filter { now - it.value.lastSeen < 60000 }
        if (healthyPeers.isEmpty()) return activePeers.keys.firstOrNull()
        
        // Return node with lowest measured latency
        return healthyPeers.minByOrNull { it.value.latency }?.key
    }
    
    fun getPeerCount(): Int = activePeers.size
    
    fun isPeer(ip: String): Boolean {
        val cleanIp = if (ip.startsWith("::ffff:")) ip.substring(7) else ip
        return activePeers.containsKey(cleanIp)
    }

    private fun showToast(message: String) {
        meshScope.launch(Dispatchers.Main) {
            Toast.makeText(PrismApp.instance, message, Toast.LENGTH_SHORT).show()
        }
    }
}
