package com.prism.launcher.mesh

import android.util.Log
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
    private var listenerJob: Job? = null
    private var gossipJob: Job? = null

    // List of active peers (IP -> PeerInfo)
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    
    // Tracking RTT for "Best Connection" selection
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    data class PeerInfo(
        var lastSeen: Long,
        var port: Int = DEFAULT_MESH_PORT,
        var latency: Long = 9999L
    )

    /**
     * Whether this device is actually ON a meshnet right now -- the setting is enabled AND the
     * control-plane loops are live.
     *
     * Deliberately agnostic about client vs. server: a device that started the mesh is on it
     * whether anyone else has shown up yet or not, and a device that joined someone else's is on
     * it too. [MeshUtils.getLocalMeshIp] cannot answer this on its own, because it falls back to
     * the plain LAN address when no tunnel interface exists, so a non-empty result there says
     * nothing about mesh membership.
     */
    fun isOnMesh(): Boolean =
        PrismSettings.getMeshEnabled() && (listenerJob?.isActive == true || gossipJob?.isActive == true)

    /** Mesh IPs of peers currently considered alive. */
    fun activePeerIps(): List<String> = activePeers.keys.toList()

    /** Last unexpected listener failure, or null if the listener has never failed. */
    @Volatile
    var lastListenerError: String? = null
        private set

    /** Human-readable listener health, for diagnostics. */
    fun listenerHealth(): String = when {
        listenerJob?.isActive == true -> "listening on ${PrismSettings.getMeshBootstrapPort()}"
        lastListenerError != null -> "stopped after error: $lastListenerError"
        PrismSettings.getMeshEnabled() -> "not running (start() never ran, or the port was taken)"
        else -> "disabled in settings"
    }

    fun start() {
        if (!PrismSettings.getMeshEnabled()) {
            Log.d(TAG, "Mesh disabled in settings; not starting the listener/gossip loops.")
            return
        }
        if (listenerJob?.isActive == true || gossipJob?.isActive == true) return

        val context = PrismApp.instance
        val meshPort = try { PrismSettings.getMeshBootstrapPort().toInt() } catch(e: Exception) { DEFAULT_MESH_PORT }

        listenerJob = meshScope.launch {
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
                // stop() closes the socket out from under a blocked receive(), which throws
                // SocketException("Socket closed"). That is the ordinary shutdown path, not a
                // fault, and reporting it as an error sent people hunting for a mesh failure that
                // never happened. A close while we are still meant to be running IS a real fault.
                val deliberate = !isActive || socket == null || socket?.isClosed == true
                if (deliberate) {
                    PrismLogger.logInfo(TAG, "Mesh listener stopped")
                } else {
                    lastListenerError = "${e.javaClass.simpleName}: ${e.message}"
                    PrismLogger.logError(TAG, "Mesh listener failed", e)
                }
            }
        }
        
        // Peer Discovery & Gossip Task
        gossipJob = meshScope.launch {
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
                
                // Compute-market capacity, re-announced periodically.
                //
                // Announcing once would be enough for peers already listening and useless for
                // every peer that joins afterwards -- and MeshComputeRegistry expires an entry it
                // has not heard from in fifteen minutes, so a device that announced at startup and
                // went quiet would silently vanish from everyone's market. Five minutes is well
                // inside that window and is a few hundred bytes.
                if (PrismSettings.getComputeHostEnabled() &&
                    now - lastComputeAnnounce > COMPUTE_ANNOUNCE_INTERVAL_MS
                ) {
                    lastComputeAnnounce = now
                    runCatching { MeshComputeRegistry.announce(com.prism.launcher.PrismApp.instance) }
                }

                delay(if (activePeers.isEmpty()) 5000 else 15000)
            }
        }
    }

    /** When this device last put its capacity on the market. */
    private var lastComputeAnnounce = 0L
    private val COMPUTE_ANNOUNCE_INTERVAL_MS = 5 * 60 * 1000L

    /** Stops the listener/gossip loops and releases the socket, so toggling the setting off takes effect immediately rather than only on next process start. */
    fun stop() {
        listenerJob?.cancel()
        gossipJob?.cancel()
        listenerJob = null
        gossipJob = null
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        activePeers.clear()
        pendingRequests.clear()
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
            PrismLogger.logInfo(TAG, "Peer discovered: $peerIp")
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
            P2pModelListings.OPCODE_LISTINGS -> P2pModelListings.ingestFromPeer(peerIp, payload)
            P2pCoinOffers.OPCODE_OFFERS -> P2pCoinOffers.ingestFromPeer(peerIp, payload)

            // A buyer asking this device to re-check a model it is selling. Handled off the
            // dispatch thread because it makes two blocking HTTP calls, and this thread is the one
            // every other peer's packets are waiting on.
            // Mesh pool mining. Work goes out from a coordinator, shares come back; both are
            // ignored outright by a device not running the mode, so a peer that never opted in
            // costs nothing but a dropped packet.
            com.prism.launcher.wallet.MeshPool.OPCODE_WORK ->
                com.prism.launcher.wallet.MeshPool.onWork(peerIp, payload)
            com.prism.launcher.wallet.MeshPool.OPCODE_SHARE ->
                com.prism.launcher.wallet.MeshPool.onShare(
                    com.prism.launcher.PrismApp.instance, peerIp, payload
                )

            // A buyer handing a model back. Off-thread: it scans the chain and signs.
            com.prism.launcher.ModelRefunds.OPCODE_REFUND_REQUEST -> {
                val ctx = com.prism.launcher.PrismApp.instance
                Thread({
                    com.prism.launcher.ModelRefunds.onRefundRequest(ctx, payload)
                }, "model-refund-request").start()
            }

            // The seller's verdict coming back. Off-thread: the buyer re-runs its own lookups
            // before paying, which is two blocking HTTP calls.
            com.prism.launcher.ModelListingScanner.OPCODE_SALE_APPROVED -> {
                val ctx = com.prism.launcher.PrismApp.instance
                Thread({
                    com.prism.launcher.ModelListingScanner.onSaleApproved(ctx, payload)
                }, "model-sale-approved").start()
            }

            com.prism.launcher.ModelListingScanner.OPCODE_VERIFY_NOW -> {
                val ctx = com.prism.launcher.PrismApp.instance
                Thread({
                    com.prism.launcher.ModelListingScanner.onVerifyRequest(ctx, payload, peerIp)
                }, "model-verify-request").start()
            }
            // The compute market. Capability gossip is cheap and frequent; the RPC handshake is
            // point-to-point and only happens when somebody is about to run a job.
            MeshComputeRegistry.OPCODE_COMPUTE_ANNOUNCE ->
                MeshComputeRegistry.ingestFromPeer(peerIp, payload)

            // Starting an RPC server allocates threads and begins listening, so it goes off the
            // dispatch thread like every other opcode that does real work here.
            MeshComputeRegistry.OPCODE_RPC_REQUEST -> {
                val ctx = com.prism.launcher.PrismApp.instance
                Thread({ MeshInference.onRpcRequest(ctx, peerIp) }, "compute-rpc-request").start()
            }

            MeshComputeRegistry.OPCODE_RPC_READY -> MeshComputeRegistry.ingestRpcReady(peerIp, payload)

            MeshComputeRegistry.OPCODE_DEBT_ANNOUNCE -> ComputeDebtLedger.ingestDebtAnnouncement(payload)

            // The Science page. Cosmic-ray hits are high-rate and must not block the
            // dispatch thread; the rest are rare enough to handle inline.
            com.prism.launcher.science.MeshScience.OPCODE_COSMIC_HIT ->
                com.prism.launcher.science.MeshScience.ingestHit(peerIp, payload)

            // Witnessing signs with the wallet key, which derives an account -- too slow
            // for the thread every other peer's packets queue behind.
            com.prism.launcher.science.MeshScience.OPCODE_WITNESS_REQUEST -> {
                Thread({
                    com.prism.launcher.science.MeshScience.onWitnessRequest(peerIp, payload)
                }, "science-witness").start()
            }

            com.prism.launcher.science.MeshScience.OPCODE_WITNESS_REPLY ->
                com.prism.launcher.science.MeshScience.onWitnessReply(peerIp, payload)

            com.prism.launcher.science.MeshScience.OPCODE_RF_SAMPLE ->
                com.prism.launcher.science.MeshScience.ingestSample(peerIp, payload)

            com.prism.launcher.aether.AetherMeshSync.OPCODE_AETHER_ANNOUNCE -> handleAetherAnnounce(payload, peerIp)
            com.prism.launcher.social.NebulaMeshSync.OPCODE_NEBULA_ANNOUNCE -> handleNebulaAnnounce(payload, peerIp)

            // PrismCoin blocks and transactions. The node itself decides whether this device
            // participates -- having the Wallet page on a desktop slot is the opt-in -- so a peer
            // that has not opted in simply drops these rather than relaying them.
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_BLOCK,
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_TX,
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_HEAD ->
                com.prism.launcher.wallet.psc.PrismCoinNode.onMeshMessage(
                    com.prism.launcher.PrismApp.instance, command, payload
                )
        }
    }

    private fun handleAetherAnnounce(payload: String, peerIp: String) {
        try {
            val json = JSONObject(payload)
            com.prism.launcher.aether.AetherMeshSync.ingestFromPeer(
                peerIp = peerIp,
                hasModel = json.optBoolean("has_model", false),
                geometrySignature = json.optString("geometry_signature"),
                score = json.optInt("score", com.prism.launcher.aether.AetherKnowledgeSync.DEFAULT_SCORE)
            )
        } catch (e: Exception) {}
    }

    /** A peer announcing it is serving a Nebula feed at NebulaMeshSync.NEBULA_HOST_DOMAIN. */
    private fun handleNebulaAnnounce(payload: String, peerIp: String) {
        try {
            val obj = com.prism.core.json.JSONObject(payload)
            com.prism.launcher.social.NebulaMeshSync.ingestFromPeer(peerIp, obj.optInt("posts", 0))
        } catch (e: Exception) {
            Log.w(TAG, "Bad Nebula announce from $peerIp: ${e.message}")
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
        PrismLogger.logInfo(TAG, "Client connected: $peerIp")
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
        PrismLogger.logDebug(TAG, "Sync requested by $peerIp")
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
            PrismLogger.logDebug(TAG, "Sync received from $peerIp")
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

    /**
     * Sends to one known peer, looking its port up from the active table.
     *
     * The broadcast helper is wrong for anything addressed: a request meant for the peer selling a
     * particular model would otherwise reach every device on the mesh, each of which would do the
     * work of deciding the message was not for it.
     *
     * Silently does nothing for a peer that is not currently active, which is the honest outcome --
     * there is no route to it, and callers of this are advisory rather than load-bearing.
     */
    fun sendToPeer(targetIp: String, command: Byte, payload: String) {
        val info = activePeers[targetIp] ?: return
        sendPacket(targetIp, info.port, command, payload)
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

    /**
     * Tag for routine mesh gossip, which goes to diagnostics and nowhere else.
     *
     * THESE USED TO BE TOASTS, and they were unusable as one. Peer discovery, client connections
     * and DNS syncs fire continuously for as long as the mesh is up -- the gossip loop wakes every
     * 5 to 15 seconds and every peer on the network is a source -- so the launcher spent its time
     * covered in notices about events the user had taken no action to cause and could take no
     * action about. A toast interrupts to report something the person just did; none of this is
     * that. It is exactly the background telemetry a diagnostics log exists to hold, and it is
     * still there in full when somebody is actually debugging the mesh.
     *
     * Logged under the existing [TAG] rather than a tag of their own, so filtering diagnostics for
     * the mesh returns all of it rather than half.
     */
}
