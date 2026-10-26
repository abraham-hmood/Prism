package com.prism.launcher.mesh

import android.util.Log
import com.prism.core.MeshUtils
import com.prism.core.MeshTransport
import com.prism.core.PrismNodeWire
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The Control Plane for the Prism Mesh.
 * Handles UDP-based discovery, heartbeats, and DNS record propagation.
 */
object PrismMeshService {
    private const val TAG = "PrismMesh"
    private const val DEFAULT_MESH_PORT = 8081
    private const val PROTOCOL_HEADER = "PRISM"
    /** UDP payload budget under the 16 KB receive buffer, with room for the 6-byte header. */
    private const val MAX_DNS_PAYLOAD = 15_000

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
    private val kickedUntil = ConcurrentHashMap<String, Long>()
    
    // Tracking RTT for "Best Connection" selection
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    data class PeerInfo(
        var lastSeen: Long,
        var port: Int = DEFAULT_MESH_PORT,
        var latency: Long = 9999L,
        var nodeId: String = "",
        var name: String = "",
        var serverPort: Int? = null,
        var serverProtocol: String? = null,
        var serverPublicKey: String? = null,
        var wireGuardPort: Int? = null,
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
        PrismSettings.getMeshEnabled() &&
            !controlPlaneBroken &&
            socket?.isClosed == false &&
            (listenerJob?.isActive == true || gossipJob?.isActive == true)

    /**
     * True when the control-plane socket is not usable, even though the jobs are still alive.
     *
     * THE SILENT FAILURE THIS REPLACES: under the VPN, an unprotected UDP socket has its packets
     * routed into the TUN interface and dropped. The listener and gossip coroutines stay active,
     * `isOnMesh()` answered true, and every feature built on the mesh behaved as if the network
     * were simply quiet. A state that says "running" while nothing can leave is worse than an
     * error, so this flag is consulted by [isOnMesh] and reported by [listenerHealth].
     */
    @Volatile
    var controlPlaneBroken: Boolean = false
        private set

    /** Mesh IPs of peers currently considered alive. */
    fun activePeerIps(): List<String> {
        val cutoff = System.currentTimeMillis() - com.prism.core.MeshCore.PEER_LEASE_MS
        return activePeers.filterValues { it.lastSeen > cutoff }.keys.toList()
    }

    /** Last unexpected listener failure, or null if the listener has never failed. */
    @Volatile
    var lastListenerError: String? = null
        private set

    /**
     * Datagrams that could not be sent, and why.
     *
     * A SILENT catch here is worse than a crash for this feature: "the mesh is up but nothing
     * arrives" has a dozen causes, and a send that fails because the address family is wrong, the
     * socket was closed, or the interface has no route to the peer looks exactly like a peer that
     * chose not to answer. The counter is what Diagnostics shows.
     */
    @Volatile
    var sendFailureCount: Long = 0L
        private set

    /** The last send failure, or null if none has happened since the process started. */
    @Volatile
    var lastSendError: String? = null
        private set

    /** Human-readable listener health, for diagnostics. */
    fun listenerHealth(): String = when {
        controlPlaneBroken ->
            "DEGRADED: the socket could not be excluded from the VPN, so packets are being routed " +
                "into the tunnel and dropped. Turn the VPN off, or fix the protect() failure, then restart the mesh."
        listenerJob?.isActive == true -> "listening on ${PrismSettings.getMeshBootstrapPort()}"
        lastListenerError != null -> "stopped after error: $lastListenerError"
        PrismSettings.getMeshEnabled() -> "not running (start() never ran, or the port was taken)"
        else -> "disabled in settings"
    }

    fun start(forceForDiscovery: Boolean = false) {
        if (!PrismSettings.getMeshEnabled() && !forceForDiscovery) {
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

                // Bypass the VPN for the mesh control plane. THIS RESULT IS NOT IGNORED any more:
                // `protect` returns false whenever it cannot exclude the socket, and under an
                // active tunnel that means every mesh packet is swallowed. The old empty catch
                // made that indistinguishable from a quiet network.
                val vpnRunning = runCatching {
                    com.prism.launcher.browser.PrivateDnsVpnService.isServiceRunning()
                }.getOrDefault(false)
                val protected = runCatching {
                    com.prism.launcher.browser.PrivateDnsVpnService.protectSocket(socket!!)
                }.getOrElse { error ->
                    PrismLogger.logWarning(TAG, "protect() threw: ${error.message}")
                    false
                }
                if (!protected && vpnRunning) {
                    controlPlaneBroken = true
                    lastListenerError =
                        "protect() refused the mesh socket while the VPN is up; mesh traffic will be dropped"
                    PrismLogger.logError(
                        TAG,
                        "Mesh control plane cannot bypass the VPN: protect() returned false",
                        null,
                    )
                } else {
                    controlPlaneBroken = false
                }
                
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
                // Evict stale peers. The lease is the same one MeshCore uses, so a phone and a
                // desktop agree on when a device has gone: this used to be five minutes here and
                // ninety seconds there, and the phone's five is why a closed Prism still read as
                // online in the trusted list.
                activePeers.entries.removeIf {
                    it.value.lastSeen < now - com.prism.core.MeshCore.PEER_LEASE_MS
                }
                
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
                    runCatching { MeshComputeRegistry.announce() }
                }

                // Speech capabilities (Kokoro and the voice-cloning encoders). Called on every
                // gossip round and throttled inside KokoroMeshShare to once a minute, because
                // this loop runs every 5-15 seconds. A device that has neither model says
                // nothing, and one that has either starts its HTTP listener on the first round.
                runCatching { com.prism.launcher.speech.KokoroMeshShare.announce() }

                delay(if (activePeers.isEmpty()) 5000 else 15000)
            }
        }
    }

    /** Starts an on-demand Wi-Fi scan; a temporary listener is stopped when the scan window ends. */
    fun discover() {
        val temporary = listenerJob?.isActive != true && !PrismSettings.getMeshEnabled()
        if (listenerJob?.isActive != true) start(forceForDiscovery = true)
        val port = try { PrismSettings.getMeshBootstrapPort().toInt() } catch (_: Exception) {
            DEFAULT_MESH_PORT
        }
        sendDiscoveryBroadcast(port)
        if (temporary) {
            Thread({
                try { Thread.sleep(8_000L) } catch (_: InterruptedException) { return@Thread }
                if (!PrismSettings.getMeshEnabled()) stop()
            }, "prism-mesh-discovery-window").apply { isDaemon = true }.start()
        }
    }

    fun nodes(): List<MeshTransport.Node> = activePeers.map { (ip, info) ->
        MeshTransport.Node(
            id = info.nodeId,
            name = info.name.ifBlank { ip },
            address = ip,
            meshPort = info.port,
            lastSeen = info.lastSeen,
            latency = info.latency,
            serverPort = info.serverPort,
            serverProtocol = info.serverProtocol,
            serverPublicKey = info.serverPublicKey,
            wireGuardPort = info.wireGuardPort,
        )
    }.sortedBy { it.name.lowercase() }

    fun kickNode(nodeId: String): Boolean = evictNode(nodeId, permanent = false)

    /**
     * Whether the peer at [ip] is banned. See MeshCore.isPeerBanned for why the gossip gate alone
     * did not stop a banned device from reaching the model/AI hosts over TCP.
     */
    fun isPeerBanned(ip: String): Boolean {
        val nodeId = activePeers[ip]?.nodeId?.takeIf { it.isNotBlank() } ?: return false
        return nodeId in PrismSettings.getBannedPrismNodeIds()
    }

    fun banNode(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        PrismSettings.setBannedPrismNodeIds(PrismSettings.getBannedPrismNodeIds() + nodeId)
        evictNode(nodeId, permanent = true)
        return true
    }

    private fun evictNode(nodeId: String, permanent: Boolean): Boolean {
        if (nodeId.isBlank()) return false
        val entry = activePeers.entries.firstOrNull { it.value.nodeId == nodeId } ?: return false
        if (!permanent) {
            kickedUntil[nodeId] = System.currentTimeMillis() + 5 * 60 * 1000L
        }
        activePeers.remove(entry.key, entry.value)
        return true
    }

    /** When this device last put its capacity on the market. */
    private var lastComputeAnnounce = 0L
    private val COMPUTE_ANNOUNCE_INTERVAL_MS = 5 * 60 * 1000L

    /** Stops the listener/gossip loops and releases the socket, so toggling the setting off takes effect immediately rather than only on next process start. */
    fun stop() {
        // Say goodbye while the socket is still usable. A clean shutdown removing itself at once
        // is what makes closing Prism look like closing Prism rather than like a device that
        // silently went offline five minutes ago.
        //
        // SENT SYNCHRONOUSLY, not through sendPacket: that queues onto the scope, and the socket
        // is closed a few lines later -- so the goodbye could reach the top of the queue after
        // the socket was gone. This is the one message whose whole point is being early.
        runCatching {
            val sock = socket
            if (sock != null && !sock.isClosed) {
                val myPort = PrismSettings.getMeshBootstrapPort().toIntOrNull() ?: DEFAULT_MESH_PORT
                val payload = localAnnouncement(myPort).toByteArray()
                val data = ByteArray(6 + payload.size)
                PROTOCOL_HEADER.toByteArray().copyInto(data)
                data[5] = 0x07.toByte()
                payload.copyInto(data, 6)
                activePeers.forEach { (ip, info) ->
                    runCatching {
                        sock.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), info.port))
                    }
                }
            }
        }
        listenerJob?.cancel()
        gossipJob?.cancel()
        listenerJob = null
        gossipJob = null
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        activePeers.clear()
        probedAt.clear()
        pendingRequests.clear()
        // The speech HTTP port is part of what the mesh exposes. Leaving it listening after the
        // mesh is switched off would keep offering this machine's models over the plain LAN,
        // which is not what "leave the mesh" means.
        runCatching { com.prism.launcher.speech.SpeechMeshServer.stop() }
    }

    private fun sendDiscoveryBroadcast(port: Int) {
        val broadcasts = MeshUtils.getBroadcastAddresses()
        broadcasts.forEach { addr ->
            // getHostAddress is nullable: an address with no literal form has none, and passing that
            // through would have broadcast to the string "null".
            val host = addr.hostAddress ?: return@forEach
            sendPacket(host, port, 0x0A.toByte(), localAnnouncement(port))
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val rawIp = packet.address.hostAddress ?: return
        val peerIp = if (rawIp.startsWith("::ffff:")) rawIp.substring(7) else rawIp
        
        val data = packet.data.sliceArray(0 until packet.length)
        if (data.size < 6) return
        
        val header = String(data, 0, 5)
        if (header != PROTOCOL_HEADER) return
        
        if (localIps().contains(peerIp)) return

        val now = System.currentTimeMillis()
        val command = data[5]
        val payload = String(data, 6, data.size - 6)

        val announcement = PrismNodeWire.parse(payload)
        val knownNodeId = announcement?.nodeId ?: activePeers[peerIp]?.nodeId.orEmpty()
        if (kickedUntil[knownNodeId]?.let { it > System.currentTimeMillis() } == true) return
        kickedUntil.remove(knownNodeId)
        if (knownNodeId in PrismSettings.getBannedPrismNodeIds()) return

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
        announcement?.let {
            info.nodeId = it.nodeId
            info.name = it.name
            info.serverPort = it.serverPort
            info.serverProtocol = it.serverProtocol
            info.serverPublicKey = it.serverPublicKey
            info.wireGuardPort = it.wireGuardPort
            info.port = it.meshPort
        }
        
        if (isNew) {
            PrismLogger.logInfo(TAG, "Peer discovered: $peerIp")
        }

        when (command) {
            0x01.toByte() -> handleHeartbeat(peerIp, info.port)
            0x02.toByte() -> handlePeerListReq(peerIp, info.port)
            0x03.toByte() -> handlePeerListResp(payload)
            0x05.toByte() -> handleDnsWrite(payload, peerIp)
            0x07.toByte() -> handleLeave(peerIp)
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
                Thread({
                    com.prism.launcher.ModelRefunds.onRefundRequest(payload)
                }, "model-refund-request").start()
            }

            // The seller's verdict coming back. Off-thread: the buyer re-runs its own lookups
            // before paying, which is two blocking HTTP calls.
            com.prism.launcher.ModelListingScanner.OPCODE_SALE_APPROVED -> {
                Thread({
                    com.prism.launcher.ModelListingScanner.onSaleApproved(payload)
                }, "model-sale-approved").start()
            }

            com.prism.launcher.ModelListingScanner.OPCODE_VERIFY_NOW -> {
                Thread({
                    com.prism.launcher.ModelListingScanner.onVerifyRequest(payload, peerIp)
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
                Thread({ MeshInference.onRpcRequest(peerIp) }, "compute-rpc-request").start()
            }

            MeshComputeRegistry.OPCODE_RPC_READY -> MeshComputeRegistry.ingestRpcReady(peerIp, payload)

            MeshComputeRegistry.OPCODE_DEBT_ANNOUNCE -> ComputeDebtLedger.ingestDebtAnnouncement(payload)

            // Relayed texts (PHASE 39). A phone SENDS these; another phone receiving one stores it too,
            // because a user with two handsets wants the relay both ways. Off the dispatch thread: it
            // decrypts and writes a file, and this thread is the one every other peer's packets queue
            // behind.
            // Trusted devices (the mutual-consent pairing). Off the dispatch thread: onOffer and
            // onShare decrypt and write a file, and this thread is the one every other peer's packets
            // queue behind.
            //
            // THE TRANSPORT ABOVE IS DELIBERATELY UNCHANGED. PHASE 48 put a portable copy of it in
            // :core (MeshCore) so a desktop can be a peer, and the temptation was to make this file
            // delegate to it. It does not, because the wire protocol is the contract -- two
            // implementations that both speak "PRISM" + opcode + payload on UDP 8081 interoperate
            // whether or not they share code -- and refactoring the component every mesh feature depends
            // on, without a device to test it against, would risk the working side to tidy the new one.
            com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_OFFER -> {
                Thread({
                    com.prism.launcher.trusted.TrustedDevices.onOffer(peerIp, payload)
                }, "trust-offer").start()
            }

            com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_REPLY -> {
                Thread({
                    com.prism.launcher.trusted.TrustedDevices.onReply(peerIp, payload)
                }, "trust-reply").start()
            }

            com.prism.launcher.trusted.TrustedDevices.OPCODE_SHARE -> {
                Thread({
                    com.prism.launcher.trusted.TrustedDevices.onShare(peerIp, payload)
                }, "trust-share").start()
            }

            // The wallet handoff, which is its own opcode because it is sealed with the pairing-code
            // key rather than a wallet-derived one -- see TrustedDevices.OPCODE_TRUST_WALLET.
            com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_WALLET -> {
                Thread({
                    com.prism.launcher.trusted.TrustedDevices.onWallet(peerIp, payload)
                }, "trust-wallet").start()
            }

            // A trusted peer saying where it is now. Cheap and frequent, so it runs inline: this
            // only rewrites an existing record's address, and doing it on a thread per packet
            // would cost more than the work.
            com.prism.launcher.trusted.TrustedDevices.OPCODE_PRESENCE -> {
                com.prism.launcher.trusted.TrustedDevices.onPresence(peerIp, payload)
            }

            com.prism.launcher.messaging.SmsRelay.OPCODE_SMS -> {
                // THE RECEIVE SIDE OF THE SETTING. The preference existed and was read by the
                // sender's receiver, but nothing wrote it and nothing checked it here, so a
                // relayed text was ingested whether or not the user had asked for relaying.
                if (!PrismSettings.getSmsRelayEnabled()) {
                    PrismLogger.logInfo("PrismSmsRelay", "Ignored a relayed text: relaying is off")
                    return
                }
                val ctx = com.prism.launcher.PrismApp.instance
                Thread({
                    val sealed = com.prism.launcher.messaging.SmsRelay.fromPayloadString(payload)
                    if (sealed != null) {
                        val messages = com.prism.launcher.messaging.SmsRelay.open(sealed)
                        if (messages != null) {
                            com.prism.launcher.messaging.SmsRelay.store(
                                java.io.File(ctx.filesDir, "relay"), messages,
                            )
                        }
                    }
                }, "sms-relay-ingest").start()
            }

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

            // The Minigames page. All of it is small and none of it touches disk on this thread:
            // a chess move is five characters, a pong paddle is one number, and a battle is a seed.
            com.prism.launcher.minigames.MinigameMesh.OPCODE_SEEK ->
                com.prism.launcher.minigames.MinigameMesh.onSeek(peerIp, payload)
            com.prism.launcher.minigames.MinigameMesh.OPCODE_ACCEPT ->
                com.prism.launcher.minigames.MinigameMesh.onAccept(peerIp, payload)
            com.prism.launcher.minigames.MinigameMesh.OPCODE_MOVE ->
                com.prism.launcher.minigames.MinigameMesh.onMove(peerIp, payload)
            com.prism.launcher.minigames.MinigameMesh.OPCODE_COUNTRY ->
                com.prism.launcher.minigames.MinigameMesh.onCountry(peerIp, payload)
            com.prism.launcher.minigames.MinigameMesh.OPCODE_BATTLE ->
                com.prism.launcher.minigames.MinigameMesh.onBattle(peerIp, payload)

            // Writes to preferences, so off the dispatch thread like the others that do.
            com.prism.launcher.minigames.MinigameMesh.OPCODE_STRIKE -> {
                Thread({
                    com.prism.launcher.minigames.MinigameMesh.onStrike(peerIp, payload) { id, strike ->
                        com.prism.launcher.minigames.MinigameStore.recordStrike(id, strike)
                    }
                }, "minigame-strike").start()
            }

            // These two write to preferences, so they go off the dispatch thread like every other
            // opcode here that does.
            com.prism.launcher.minigames.MinigameMesh.OPCODE_ALLIANCE -> {
                Thread({
                    com.prism.launcher.minigames.MinigameMesh.onAllianceRequest(peerIp, payload)
                }, "minigame-alliance").start()
            }
            com.prism.launcher.minigames.MinigameMesh.OPCODE_ALLIANCE_REPLY -> {
                Thread({
                    com.prism.launcher.minigames.MinigameMesh.onAllianceReply(peerIp, payload)
                }, "minigame-alliance-reply").start()
            }

            com.prism.launcher.aether.AetherMeshSync.OPCODE_AETHER_ANNOUNCE -> handleAetherAnnounce(payload, peerIp)
            com.prism.launcher.social.NebulaMeshSync.OPCODE_NEBULA_ANNOUNCE -> handleNebulaAnnounce(payload, peerIp)

            // A desktop or phone offering its Kokoro / voice-cloning models. The announcement is
            // the only speech traffic on this socket; the models themselves are reached over the
            // HTTP listener SpeechMeshServer runs on the port in that announcement.
            com.prism.launcher.speech.KokoroMeshShare.OPCODE_KOKORO_ANNOUNCE ->
                com.prism.launcher.speech.KokoroMeshShare.ingestFromPeer(peerIp, payload)

            // Nebula posts and Lyke videos, both directions. The same three functions the desktop
            // registers with MeshCore -- this build dispatches them itself, because two sockets on
            // UDP 8081 in one process fight over the port.
            com.prism.launcher.social.SocialMeshSync.OPCODE_ANNOUNCE ->
                com.prism.launcher.social.SocialMeshSync.onAnnounce(peerIp, payload)

            com.prism.launcher.social.SocialMeshSync.OPCODE_REQUEST ->
                com.prism.launcher.social.SocialMeshSync.onRequest(peerIp, payload)

            com.prism.launcher.social.SocialMeshSync.OPCODE_DELIVER ->
                com.prism.launcher.social.SocialMeshSync.onDeliver(peerIp, payload)

            // MESH CALLS AND MESHNET VOICEMAIL. Signals are rare and parse JSON, so they go
            // off the dispatch thread; audio is one frame every 20 ms and must not queue behind a
            // thread that is starting a call, so it is handled inline. The engine's own receive
            // functions are shared with the desktop's MeshCore path, which is what keeps one set of
            // encryption checks on both platforms.
            com.prism.launcher.calls.MeshCalls.OPCODE_SIGNAL -> {
                Thread({
                    com.prism.launcher.calls.MeshCalls.receiveSignal(peerIp, payload)
                }, "mesh-call-signal").start()
            }

            com.prism.launcher.calls.MeshCalls.OPCODE_AUDIO ->
                com.prism.launcher.calls.MeshCalls.receiveAudio(peerIp, payload)

            com.prism.launcher.calls.MeshCalls.OPCODE_VOICE -> {
                Thread({
                    com.prism.launcher.calls.MeshCalls.MeshVoice.accept(peerIp, payload)
                }, "meshvoice-ingest").start()
            }

            // Trusted-device positions and find requests. Both decrypt a sealed payload and write a
            // file, so both are off the dispatch thread for the same reason.
            com.prism.launcher.trusted.TrustedLocations.OPCODE_LOCATION -> {
                Thread({
                    com.prism.launcher.trusted.TrustedLocations.receiveLocation(peerIp, payload)
                }, "location-receive").start()
            }

            com.prism.launcher.trusted.TrustedLocations.OPCODE_FIND -> {
                Thread({
                    com.prism.launcher.trusted.TrustedLocations.receiveFind(peerIp, payload)
                }, "location-find").start()
            }

            // PrismCoin blocks and transactions. The node itself decides whether this device
            // participates -- having the Wallet page on a desktop slot is the opt-in -- so a peer
            // that has not opted in simply drops these rather than relaying them.
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_BLOCK,
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_TX,
            com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_HEAD ->
                com.prism.launcher.wallet.psc.PrismCoinNode.onMeshMessage(command, payload)
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
        sendPacket(peerIp, port, 0x0B.toByte(), localAnnouncement(myPort))
    }

    /**
     * A peer said goodbye.
     *
     * The lease would have removed it in a minute or so; this makes a deliberate shutdown immediate.
     * It matters most on the trusted-devices screen, where "online" is the thing being read.
     */
    private fun handleLeave(peerIp: String) {
        activePeers.remove(peerIp)
        pendingRequests.keys.removeIf { it.startsWith("$peerIp:") }
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
            sendPacket(peerIp, peerPort, 0x0B.toByte(), localAnnouncement(myPort))
            requestSync(peerIp)
        } catch (e: Exception) {}
    }

    private fun localAnnouncement(meshPort: Int): String {
        val advertisesProxy = PrismSettings.getVpnTunnelingEnabled() &&
            PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM &&
            PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER &&
            runCatching { PrismApp.instance.tunnelEngine.isPrismServerRunning() }.getOrDefault(false) &&
            (PrismSettings.getVpnProtocolMode() == PrismSettings.VPN_PROTOCOL_AUTO ||
                PrismSettings.getVpnProtocolMode() == PrismSettings.VPN_PROTOCOL_PROXY)
        val serverPort = if (advertisesProxy) {
            PrismSettings.getPrismVpnPort().toIntOrNull()
        } else {
            null
        }
        return PrismNodeWire.announcement(
            meshPort = meshPort,
            serverPort = serverPort,
            serverProtocol = if (serverPort == null) null else "proxy",
        )
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
        val now = System.currentTimeMillis()
        payload.split(",").filter { it.isNotBlank() }.forEach { entry ->
            try {
                val parts = entry.split(":")
                val ip = parts[0]
                val port = parts.getOrNull(1)?.toInt() ?: DEFAULT_MESH_PORT
                // HEARSAY IS NOT PRESENCE. This used to insert every listed address into the peer
                // table with a fresh timestamp, which is what kept a device whose Prism was closed
                // showing as online as long as any third device still mentioned it. A listed
                // address is only a hint to probe; it becomes a peer when it answers itself.
                if (myIps.contains(ip) || isPeer(ip)) return@forEach
                val lastProbe = probedAt[ip] ?: 0L
                if (now - lastProbe < PROBE_INTERVAL_MS) return@forEach
                probedAt[ip] = now
                sendPacket(ip, port, 0x01.toByte(), "{}")
            } catch (e: Exception) {}
        }
    }

    /** The last time an address heard through a peer list was probed, to cap the traffic. */
    private val probedAt = ConcurrentHashMap<String, Long>()
    private const val PROBE_INTERVAL_MS = 30_000L

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
            // THE SIGNATURE IS THE AUTHORITY when there is one; the sender's address is the
            // fallback for builds that cannot sign. updateRecord checks both, including that a
            // name already bound to an identity cannot be moved by a different one.
            val altArray = json.optJSONArray("alts")
            val alts = mutableSetOf<String>()
            if (altArray != null) {
                for (i in 0 until altArray.length()) alts.add(altArray.getString(i))
            }
            val accepted = P2pDnsManager.updateRecord(
                PrismApp.instance,
                domain,
                ip,
                isVerified = true,
                fromMesh = true,
                sourceIp = sourceIp,
                timestamp = json.optLong("ts", System.currentTimeMillis()),
                alternates = alts,
                publicKey = json.optString("pub"),
                signature = json.optString("sig"),
            )
            if (accepted) broadcastToOthers(0x05.toByte(), payload, sourceIp)
            else PrismLogger.logWarning(TAG, "Ignored unauthorized DNS write from $sourceIp for $domain")
        } catch (e: Exception) {}
    }

    private fun handleDnsSyncReq(peerIp: String, port: Int, payload: String) {
        PrismLogger.logDebug(TAG, "Sync requested by $peerIp")
        try {
            val json = JSONObject(decodeDnsPayload(payload))
            if (json.has("dns")) {
                ingestDnsJson(json.getJSONObject("dns"))
            }
        } catch (e: Exception) {}

        val response = JSONObject().apply {
            put("dns", getLocalDnsJson())
            put("peers", getPeerListString())
        }
        sendPacket(peerIp, port, 0x09.toByte(), encodeDnsPayload(response.toString()))
    }

    private fun handleDnsSyncResp(peerIp: String, payload: String) {
        try {
            val json = JSONObject(decodeDnsPayload(payload))
            if (json.has("dns")) ingestDnsJson(json.getJSONObject("dns"))
            if (json.has("peers")) handlePeerListResp(json.getString("peers"))
            PrismLogger.logDebug(TAG, "Sync received from $peerIp")
        } catch (e: Exception) {}
    }

    /**
     * Compresses a DNS sync body before it goes into one UDP datagram.
     *
     * The receive buffer is 16 KB, and an uncompressed ledger of a few hundred domains reaches
     * that easily -- at which point the datagram is silently truncated by the transport and the
     * receiver parses a fragment. Gzip + Base64 typically cuts a JSON ledger by an order of
     * magnitude; if the result still does not fit, the ledger is trimmed to the most recent
     * records and the loss is logged rather than being presented as a successful sync.
     */
    private fun encodeDnsPayload(json: String): String {
        val raw = json.toByteArray()
        if (raw.size < 2048) return json
        val compressed = try {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(raw) }
            out.toByteArray()
        } catch (_: Exception) {
            return json
        }
        if (compressed.size + 8 > MAX_DNS_PAYLOAD) {
            PrismLogger.logWarning(
                TAG,
                "DNS sync is ${compressed.size} bytes compressed; dropping it rather than " +
                    "sending a datagram the peer cannot receive whole",
            )
            return JSONObject().put("dns", JSONObject()).toString()
        }
        return "GZ:" + Base64.getEncoder().encodeToString(compressed)
    }

    private fun decodeDnsPayload(payload: String): String {
        if (!payload.startsWith("GZ:")) return payload
        val bytes = Base64.getDecoder().decode(payload.removePrefix("GZ:"))
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
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
                    source,
                    obj.optString("pub"),
                    obj.optString("sig"),
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
            if (record.publicKey.isNotBlank()) put("pub", record.publicKey)
            if (record.signature.isNotBlank()) put("sig", record.signature)
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
                if (record.publicKey.isNotBlank()) put("pub", record.publicKey)
                if (record.signature.isNotBlank()) put("sig", record.signature)

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
        sendPacket(targetIp, info.port, 0x08.toByte(), encodeDnsPayload(payload.toString()))
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
                val target = socket
                if (target == null) {
                    noteSendFailure("socket is not open (mesh stopped or never started)")
                    return@launch
                }
                target.send(packet)
            } catch (e: Exception) {
                noteSendFailure(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun noteSendFailure(reason: String) {
        sendFailureCount += 1
        // Log the first failure and then every fiftieth: a peer that is gone produces one failure
        // per gossip round forever, and logging all of them would bury everything else.
        if (sendFailureCount == 1L || sendFailureCount % 50L == 0L) {
            lastSendError = reason
            PrismLogger.logWarning(
                TAG,
                "UDP send failed ($sendFailureCount so far): $reason",
            )
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
    
    fun getPeerCount(): Int {
        val cutoff = System.currentTimeMillis() - com.prism.core.MeshCore.PEER_LEASE_MS
        return activePeers.count { it.value.lastSeen > cutoff }
    }
    
    /**
     * This device's own addresses, refreshed at most every 1.5 seconds.
     *
     * The listener used to enumerate every network interface for every incoming packet. At
     * pointer rates -- a hundred small datagrams a second -- that work sits directly between the
     * finger and the cursor on the far side.
     */
    @Volatile private var localIpsAt = 0L
    @Volatile private var localIpsCache: Set<String> = emptySet()

    private fun localIps(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - localIpsAt < 1_500L) return localIpsCache
        val fresh = runCatching { MeshUtils.getAllLocalIps() }.getOrDefault(emptySet())
        localIpsCache = fresh
        localIpsAt = now
        return fresh
    }

    fun isPeer(ip: String): Boolean {
        val cleanIp = if (ip.startsWith("::ffff:")) ip.substring(7) else ip
        val info = activePeers[cleanIp] ?: return false
        return System.currentTimeMillis() - info.lastSeen <= com.prism.core.MeshCore.PEER_LEASE_MS
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
