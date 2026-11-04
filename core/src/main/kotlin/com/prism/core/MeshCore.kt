package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The Prism meshnet, on any JVM. PHASE 48.
 *
 * ## What moved and what did not
 *
 * The TRANSPORT moved: the socket, the framing, the peer table, discovery, heartbeats, the peer-list
 * exchange and latency measurement. All of it was already plain `DatagramSocket` work — the Android
 * service's only platform dependencies were `android.util.Log` and a `Context` it used to read one
 * setting. [MeshUtils] was in :core from the start.
 *
 * The DISPATCH did not, and could not. The Android service's `when` over opcodes reaches into fifteen
 * subsystems — the compute market, the minigames, PrismCoin, Nebula, the science page — most of which are
 * Android-only or simply not ported yet. So opcodes are a REGISTRY here: each platform registers the
 * handlers it has, and an opcode nobody claimed is dropped rather than being a compile error in a module
 * that does not exist.
 *
 * That split is what makes a desktop peer possible without porting fifteen features first. A desktop that
 * registers three handlers is a real mesh peer for those three things and ignores the rest, which is
 * exactly how a mesh of differently-versioned devices has to behave anyway.
 *
 * ## The wire format, unchanged
 *
 * `"PRISM"` then one opcode byte then a UTF-8 payload, on UDP. Unchanged deliberately: a desktop peer has
 * to talk to phones running the shipped build, so this is the one part of the port where being identical
 * matters more than being better. The 16 KB receive buffer is the same too, and it is why the relay in
 * [com.prism.launcher.messaging.SmsRelay] base64s its payload rather than sending bytes.
 *
 * ## Why the opcodes below 0x0C are built in
 *
 * Discovery, heartbeat, the peer list and the DNS-sync request are how the mesh MAINTAINS ITSELF. A
 * platform that had to register those would be a platform that could get them wrong and produce a peer
 * that joins and never gossips. Everything above 0x0C is a feature and belongs to whoever implements it.
 */
object MeshCore {

    private const val TAG = "PrismMesh"

    const val PROTOCOL_HEADER = "PRISM"
    const val DEFAULT_PORT = 8081

    /**
     * How long a peer stays in the table after its last packet.
     *
     * THIS IS A PRESENCE LEASE, NOT A CACHE TTL. A device that is listed as online and is not there
     * is worse than one that is missing: the user presses it, nothing happens, and there is no way
     * to tell a sleeping laptop from a forgotten pairing. Gossip is every fifteen seconds, so this
     * allows two lost heartbeats before the peer is considered gone.
     *
     * IT WAS NINETY SECONDS, which was already generous; the Android service used five MINUTES,
     * which is why a phone with Prism closed still showed as online. Both now use this one figure.
     */
    const val PEER_LEASE_MS = 45_000L

    /** Built-in protocol. Everything from 0x0C up belongs to features. */
    private const val OP_HEARTBEAT: Byte = 0x01
    private const val OP_PEER_LIST_REQ: Byte = 0x02
    private const val OP_PEER_LIST_RESP: Byte = 0x03
    private const val OP_DNS_WRITE: Byte = 0x05
    private const val OP_DNS_SYNC_REQ: Byte = 0x08
    private const val OP_DNS_SYNC_RESP: Byte = 0x09
    private const val OP_DISCOVERY_REQ: Byte = 0x0A
    private const val OP_HEARTBEAT_RESP: Byte = 0x0B

    /**
     * One packet for somebody this device can reach but the sender cannot.
     *
     * Used by the Relay VPN role: a relay has a route into a part of the mesh (or another fleet
     * server's mesh) that a client does not. The payload is `{"to","op","data","ttl"}` and the
     * relay forwards the inner opcode unchanged, so the final receiver needs no new protocol. TTL
     * stops two relays from bouncing the same packet forever.
     */
    const val OP_RELAY: Byte = 0x0D

    /**
     * "I am going away."
     *
     * Sent once from [stop]. Without it a device that is shut down cleanly still lingers for the
     * whole lease, which is the delay users noticed after closing Prism. The lease remains the
     * authority for a device that was killed or lost its network; this only makes the ordinary
     * case immediate.
     *
     * 0x07 is one of the three codes below 0x0C that nothing else uses. 0x0C was the obvious pick
     * and is already the model-announce opcode, so this cannot simply follow the heartbeat run.
     */
    private const val OP_LEAVE: Byte = 0x07

    data class PeerInfo(
        @Volatile var lastSeen: Long,
        @Volatile var port: Int = DEFAULT_PORT,
        /** Round-trip milliseconds, or 9999 until one has been measured. */
        @Volatile var latency: Long = 9999L,
        /** What the peer calls itself, when it has said. Empty until a feature tells us. */
        @Volatile var name: String = "",
        @Volatile var nodeId: String = "",
        @Volatile var serverPort: Int? = null,
        @Volatile var serverProtocol: String? = null,
        @Volatile var serverPublicKey: String? = null,
        @Volatile var wireGuardPort: Int? = null,
        /** Declared country, empty when this device has not said. */
        @Volatile var country: String = "",
        /** Prism Coin address for traffic this device carries, empty when it has none. */
        @Volatile var payout: String = "",
    )

    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    private val kickedUntil = ConcurrentHashMap<String, Long>()

    /** Sent-at times for the opcodes that get a reply, so latency is measured and not guessed. */
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var boundPort = DEFAULT_PORT

    @Volatile var lastListenerError: String? = null
        private set

    /**
     * Why the last datagram failed to go out, and how many have failed.
     *
     * THE FAILURE USED TO BE INVISIBLE. `sendPacket` wrapped the send in `runCatching` and dropped
     * the result, so a machine with a firewall, a closed socket or an unroutable address reported
     * "mesh up" and an empty peer list with nothing to explain it. Android already counts these
     * (`PrismMeshService.noteSendFailure`); this is the same counter on the shared transport, and it
     * is what `MeshCore.listenerHealth()` and the diagnostics page read.
     */
    @Volatile var lastSendError: String? = null
        private set

    @Volatile var sendFailureCount: Long = 0L
        private set

    // ── Platform hooks ─────────────────────────────────────────────────────

    /**
     * Handles one opcode. Returns true when it took the packet.
     *
     * A BOOLEAN RATHER THAN Unit so an unclaimed opcode can be counted. A mesh where a peer silently
     * ignores a feature it does not have is correct behaviour, but being unable to tell that from a
     * handler that crashed would make debugging a cross-version mesh impossible.
     */
    fun interface OpcodeHandler {
        fun handle(peerIp: String, payload: String): Boolean
    }

    private val handlers = ConcurrentHashMap<Byte, OpcodeHandler>()

    /**
     * Registers a handler. Later registrations replace earlier ones for the same opcode.
     *
     * Opcodes below 0x0C are refused: those are how the mesh keeps itself alive, and a platform that
     * overrode the heartbeat would produce a peer that joins and then stops gossiping.
     *
     * A REPLACEMENT IS LOGGED, because it used to be silent. Two features that both claimed an opcode
     * is not a crash and not a compile error -- the second one simply took the first one's packets,
     * and the symptom was a feature that worked until something else was enabled. Re-registering the
     * SAME handler is how an install function that runs twice behaves, so that case stays quiet.
     */
    fun register(opcode: Byte, handler: OpcodeHandler): Boolean {
        if (opcode < 0x0C) {
            PrismPlatform.log.error(
                TAG,
                "Refusing to override built-in opcode 0x${opcode.toString(16)} — see MeshCore",
            )
            return false
        }
        val previous = handlers.put(opcode, handler)
        if (previous != null && previous !== handler) {
            PrismPlatform.log.warn(
                TAG,
                "Opcode 0x${opcode.toString(16)} changed hands; the previous handler will stop seeing it",
            )
        }
        return true
    }

    fun unregister(opcode: Byte) {
        handlers.remove(opcode)
    }

    fun registeredOpcodes(): List<Byte> = handlers.keys.sorted()

    /**
     * Lets a platform protect the socket from its own VPN.
     *
     * ANDROID NEEDS THIS AND NOTHING ELSE DOES. When Prism's own VPN is up, a socket opened inside the
     * app is routed INTO the tunnel — so the mesh would try to reach its LAN peers through the VPN and
     * reach nothing. `VpnService.protect` exempts it. On a desktop there is no such capture and the hook
     * stays null, which is why this is a hook rather than a `when` over platforms.
     */
    @Volatile
    var socketProtector: ((DatagramSocket) -> Unit)? = null

    /**
     * Where the DNS registry lives, when a platform has one.
     *
     * The DNS-sync opcodes are built in because they are part of keeping the mesh coherent, but the
     * REGISTRY is Phase 49 and is not in :core yet. Until it is, a platform supplies these and a platform
     * that supplies neither participates in everything except DNS.
     */
    @Volatile
    var dnsProvider: (() -> String)? = null

    @Volatile
    var dnsConsumer: ((peerIp: String, json: String) -> Unit)? = null

    /** Extra bootstrap addresses to try at startup, beyond broadcast discovery. */
    @Volatile
    var bootstrapNodes: () -> List<String> = { emptyList() }

    /** A platform's active Prism server endpoint, if it is actually listening. */
    @Volatile
    var serverEndpoint: () -> PrismNodeWire.ServerEndpoint? = { null }

    /** The port to bind. A hook so a platform can read it from its own settings. */
    @Volatile
    var portProvider: () -> Int = { DEFAULT_PORT }

    // ── State ──────────────────────────────────────────────────────────────

    /**
     * Whether this device is a member of a mesh right now, not merely listening for one.
     *
     * `running` only says the discovery socket is bound, which every install does at startup. It is
     * the control plane being READY, not a connection to anything. Answering true from that alone made
     * email choose mesh ports and a mesh domain on a machine that was on no meshnet at all.
     *
     * Membership is a live peer, or this machine serving the mesh. The serving check is the same hook
     * the node announcement uses, so a desktop whose Prism proxy is bound counts even before a client
     * connects, and a client with an empty fleet does not.
     */
    fun isOnMesh(): Boolean {
        if (!running || socket?.isClosed != false) return false
        val cutoff = System.currentTimeMillis() - PEER_LEASE_MS
        if (activePeers.values.any { it.lastSeen > cutoff }) return true
        return runCatching { serverEndpoint() != null }.getOrDefault(false)
    }

    fun activePeerIps(): List<String> = activePeers.keys.toList()

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

    fun discover() {
        if (running) sendDiscoveryBroadcast(boundPort)
    }

    fun kickNode(nodeId: String): Boolean = evictNode(nodeId, permanent = false)

    fun banNode(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        PrismSettings.setBannedPrismNodeIds(PrismSettings.getBannedPrismNodeIds() + nodeId)
        evictNode(nodeId, permanent = true)
        return true
    }

    /**
     * Whether the peer at [ip] is banned.
     *
     * THE GOSSIP PATH IS NOT ENOUGH. Kick and ban stopped datagrams from a node, but every other
     * service this device exposes -- model download, generation, cloud -- is reached over an
     * ordinary TCP connection to the proxy port, which knows the peer's ADDRESS and nothing else.
     * A banned device could still open that connection and be served. This maps the address back to
     * the node id the ban list is keyed by, so those hosts can refuse it.
     */
    fun isPeerBanned(ip: String): Boolean {
        val nodeId = activePeers[ip]?.nodeId?.takeIf { it.isNotBlank() } ?: return false
        return nodeId in PrismSettings.getBannedPrismNodeIds()
    }

    private fun evictNode(nodeId: String, permanent: Boolean): Boolean {
        if (nodeId.isBlank()) return false
        val entry = activePeers.entries.firstOrNull { it.value.nodeId == nodeId } ?: return false
        if (!permanent) {
            kickedUntil[nodeId] = System.currentTimeMillis() + KICK_COOLDOWN_MS
        }
        activePeers.remove(entry.key, entry.value)
        return true
    }

    /** Peers whose lease has not run out, which is what every caller means by "a peer". */
    private fun livePeers(): Map<String, PeerInfo> {
        val cutoff = System.currentTimeMillis() - PEER_LEASE_MS
        return activePeers.filterValues { it.lastSeen > cutoff }
    }

    fun peers(): Map<String, PeerInfo> = livePeers()

    fun peerCount(): Int = livePeers().size

    /**
     * Whether [ip] is a peer that is currently answering.
     *
     * PRESENCE, NOT MEMBERSHIP. The table holds an entry until the sweep notices it has gone quiet,
     * and between those two moments a closed device would otherwise still read as active in the
     * trusted-devices list. Checking the lease here means the answer changes the instant the lease
     * expires rather than when the next gossip round happens to run.
     */
    fun isPeer(ip: String): Boolean {
        val info = activePeers[normalise(ip)] ?: return false
        return System.currentTimeMillis() - info.lastSeen <= PEER_LEASE_MS
    }

    fun peerPort(ip: String): Int? = activePeers[normalise(ip)]?.port

    /** The lowest-latency healthy peer, for a caller that just needs somebody to ask. */
    fun bestPeer(): String? =
        livePeers().minByOrNull { it.value.latency }?.key

    fun listenerHealth(): String = when {
        !running -> "Not started."
        socket?.isClosed != false -> "Socket closed. ${lastListenerError.orEmpty()}"
        sendFailureCount == 0L -> "Listening on UDP $boundPort · ${activePeers.size} peer(s)"
        else -> (
            "Listening on UDP $boundPort · ${activePeers.size} peer(s) · " +
                "$sendFailureCount send failure(s); last: ${lastSendError.orEmpty()}"
            )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Binds the socket and starts the listener and gossip loops.
     *
     * Plain threads rather than coroutines. The listener is a blocking `receive` for the life of the
     * process, which is not what a coroutine dispatcher is for — it would occupy a pool thread
     * permanently and gain nothing. Daemon threads, so the mesh never keeps a process alive by itself.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true

        val port = runCatching { portProvider() }.getOrDefault(DEFAULT_PORT)
        val bound = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
                broadcast = true
            }
        }.getOrElse {
            lastListenerError = "Could not bind UDP $port: ${it.message}"
            PrismPlatform.log.error(TAG, lastListenerError!!, it)
            return false
        }

        runCatching { socketProtector?.invoke(bound) }

        socket = bound
        boundPort = port
        running = true
        lastListenerError = null
        PrismPlatform.log.info(TAG, "Mesh listening on UDP $port")

        thread(name = "mesh-listener", isDaemon = true) { listenLoop(bound) }
        thread(name = "mesh-gossip", isDaemon = true) { gossipLoop() }
        return true
    }

    @Synchronized
    fun stop() {
        // Tell the peers first, while the socket is still open. A datagram send is synchronous, so
        // the packets are on their way before the socket is closed underneath them.
        activePeers.forEach { (ip, info) ->
            sendPacket(ip, info.port, OP_LEAVE, localAnnouncement())
        }
        running = false
        runCatching { socket?.close() }
        socket = null
        activePeers.clear()
        lostPeers.clear()
        pendingRequests.clear()
        PrismPlatform.log.info(TAG, "Mesh stopped")
    }

    private fun listenLoop(bound: DatagramSocket) {
        // 16 KB, the same as the Android build. Changing it would silently truncate packets from peers
        // running the shipped version, and a truncated JSON payload fails to parse rather than reporting
        // that it was cut off.
        val buffer = ByteArray(16 * 1024)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                bound.receive(packet)
            } catch (e: Exception) {
                if (running) {
                    lastListenerError = e.message
                    PrismPlatform.log.error(TAG, "Mesh listener stopped", e)
                }
                break
            }
            runCatching { handlePacket(packet) }
                .onFailure { PrismPlatform.log.error(TAG, "Bad packet handling", it) }
        }
    }

    /**
     * Discovery at startup, then heartbeats and peer-list requests forever.
     *
     * ONE RANDOM PEER PER ROUND rather than all of them. Heartbeating every peer every interval is
     * O(peers) packets per device per round, which on a mesh of twenty devices is four hundred packets a
     * round for information that changes slowly. Gossiping to a random peer converges just as well and
     * costs one packet.
     */
    private fun gossipLoop() {
        sendDiscoveryBroadcast(boundPort)
        runCatching { bootstrapNodes() }.getOrDefault(emptyList()).forEach { node ->
            val parts = node.split(":")
            val ip = parts.firstOrNull()?.trim().orEmpty()
            if (ip.isNotEmpty()) {
                sendPacket(
                    ip,
                    parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT,
                    OP_DISCOVERY_REQ,
                    localAnnouncement(),
                )
            }
        }

        // A short first round. Discovery finds a peer immediately but latency is only known once a
        // heartbeat has been answered, so waiting a full interval means every freshly-found peer reads as
        // 9999 ms -- indistinguishable from one that is not responding.
        runCatching { Thread.sleep(FIRST_GOSSIP_DELAY_MS) }.onFailure { return }

        while (running) {
            if (!running) return

            val now = System.currentTimeMillis()
            // Expire before gossiping, so a dead peer is not chosen as the one to talk to. An
            // expired peer is remembered and probed by unicast for a while -- see [reconnect].
            activePeers.entries.removeAll { entry ->
                val expired = now - entry.value.lastSeen > PEER_LEASE_MS
                if (expired) lostPeers[entry.key] = entry.value.port to now
                expired
            }
            runCatching { reconnect(now) }
                .onFailure { PrismPlatform.log.warn(TAG, "Reconnect round failed: " + it.message) }
            // A request that was never answered is not worth remembering. These used to accumulate
            // for every address a forged peer list named.
            pendingRequests.filterValues { now - it > REQUEST_EXPIRY_MS }.keys
                .forEach { pendingRequests.remove(it) }

            if (activePeers.isEmpty()) {
                // Nothing known: broadcast again rather than sitting idle. A device that joined a network
                // after Prism started would otherwise never be found.
                //
                // THE SLEEP IS NOT OPTIONAL. `continue` skips the sleep at the bottom of this loop, so a
                // device with no peers -- a laptop on a network where nothing else runs Prism, which is
                // the common case -- used to broadcast as fast as the CPU could build packets. That is a
                // broadcast storm on the user's LAN and a core pinned at 100% for as long as the mesh was
                // on. Discovery still goes out every interval, which is all it was ever meant to do.
                sendDiscoveryBroadcast(boundPort)
                runCatching { Thread.sleep(GOSSIP_INTERVAL_MS) }.onFailure { return }
                continue
            }
            val target = activePeers.keys.randomOrNull() ?: continue
            val info = activePeers[target] ?: continue
            val identity = localAnnouncement()
            sendPacket(target, info.port, OP_HEARTBEAT, identity)
            sendPacket(target, info.port, OP_PEER_LIST_REQ, identity)
            // EVERY QUIET PEER, NOT ONLY THE RANDOM ONE. With two or three peers, picking one at
            // random each round regularly skips the same peer three rounds running -- past the
            // lease -- and it expired while it was perfectly reachable. The phone has always done
            // this; the desktop did not, which is a large part of why desktops dropped off.
            activePeers.forEach { (ip, peer) ->
                if (ip != target && now - peer.lastSeen > QUIET_PEER_MS) {
                    sendPacket(ip, peer.port, OP_HEARTBEAT, identity)
                }
            }
            // A broadcast every round as well, as the phone does: a device that joined the network
            // while this one already had peers would otherwise only be found by hearsay.
            sendDiscoveryBroadcast(boundPort)

            runCatching { Thread.sleep(GOSSIP_INTERVAL_MS) }.onFailure { return }
        }
    }

    /**
     * Addresses a feature wants kept in touch with even when they are not in the peer table.
     *
     * Trusted devices set this to their last-known addresses, so a paired laptop that dropped off is
     * found again by unicast rather than by waiting for a broadcast it may never hear.
     */
    @Volatile
    var probeTargets: () -> List<String> = { emptyList() }

    /** Peers whose lease ran out recently: address to (port, when it was lost). */
    private val lostPeers = ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * Gets back in touch with the devices this one should be talking to.
     *
     * THE MESH USED TO RECOVER ONLY BY BROADCAST. The meshnet server (the bootstrap address) was
     * contacted once, at startup, and a peer that expired was forgotten. A laptop whose Wi-Fi power
     * saving swallowed a minute of packets therefore lost the phone, and broadcasts are exactly what
     * a dozing Wi-Fi card drops -- so it stayed lost until the mesh was switched off and on, which
     * re-ran the startup contact. Every round now sends a unicast discovery request to the
     * bootstrap nodes, to peers lost in the last [LOST_PEER_PROBE_MS], and to [probeTargets]. A
     * discovery request is answered with a heartbeat, which puts both sides back in each other's
     * table.
     */
    private fun reconnect(now: Long) {
        lostPeers.entries.removeIf { now - it.value.second > LOST_PEER_PROBE_MS || activePeers.containsKey(it.key) }
        val targets = linkedMapOf<String, Int>()
        runCatching { bootstrapNodes() }.getOrDefault(emptyList()).forEach { node ->
            val parts = node.split(":")
            val ip = normalise(parts.firstOrNull()?.trim().orEmpty())
            if (ip.isNotEmpty()) targets[ip] = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        }
        lostPeers.forEach { (ip, value) -> targets.putIfAbsent(ip, value.first) }
        runCatching { probeTargets() }.getOrDefault(emptyList()).forEach { raw ->
            val ip = normalise(raw)
            if (ip.isNotEmpty()) targets.putIfAbsent(ip, DEFAULT_PORT)
        }
        if (targets.isEmpty()) return
        val mine = localIps()
        val announcement = localAnnouncement()
        targets.forEach { (ip, port) ->
            // A live peer is already heartbeated above; a name is never resolved from here.
            if (activePeers.containsKey(ip) || mine.contains(ip) || !MeshUtils.isIpv4Literal(ip)) return@forEach
            if (port !in 1..65535) return@forEach
            sendPacket(ip, port, OP_DISCOVERY_REQ, announcement)
        }
    }

    private fun sendDiscoveryBroadcast(port: Int) {
        // getBroadcastAddresses returns InetAddress; sendPacket takes a host string because every other
        // caller has one. Resolving back to text here rather than overloading sendPacket, so there is one
        // send path and one place latency is recorded.
        MeshUtils.getBroadcastAddresses().forEach { address ->
            val host = address.hostAddress ?: return@forEach
            sendPacket(host, port, OP_DISCOVERY_REQ, localAnnouncement())
        }
    }

    // ── Receiving ──────────────────────────────────────────────────────────

    private fun handlePacket(packet: DatagramPacket) {
        val peerIp = normalise(packet.address.hostAddress ?: return)
        val data = packet.data.sliceArray(0 until packet.length)
        if (data.size < 6) return
        if (String(data, 0, 5) != PROTOCOL_HEADER) return

        // Our own broadcast coming back. Dropped before it becomes a peer entry for ourselves, which
        // would make the device gossip with itself and count itself in its own peer list.
        if (localIps().contains(peerIp)) return

        val command = data[5]
        val payload = String(data, 6, data.size - 6)
        val announcement = PrismNodeWire.parse(payload)
        val knownNodeId = announcement?.nodeId ?: activePeers[peerIp]?.nodeId.orEmpty()
        if (kickedUntil[knownNodeId]?.let { it > System.currentTimeMillis() } == true) return
        kickedUntil.remove(knownNodeId)
        if (knownNodeId in PrismSettings.getBannedPrismNodeIds()) return
        val now = System.currentTimeMillis()

        // A SPOOFED-SOURCE FLOOD MUST NOT BECOME AN UNBOUNDED TABLE. At the cap the oldest entry goes,
        // not the newest, so a busy network still converges on the devices actually talking.
        if (!activePeers.containsKey(peerIp) && activePeers.size >= MAX_PEERS) {
            activePeers.minByOrNull { it.value.lastSeen }?.key?.let { activePeers.remove(it) }
        }
        val isNew = !activePeers.containsKey(peerIp)
        val info = activePeers.getOrPut(peerIp) { PeerInfo(now, packet.port) }
        info.lastSeen = now
        info.port = packet.port
        announcement?.let {
            info.nodeId = it.nodeId
            info.name = it.name
            info.serverPort = it.serverPort
            info.serverProtocol = it.serverProtocol
            info.serverPublicKey = it.serverPublicKey
            info.wireGuardPort = it.wireGuardPort
            info.country = it.country
            info.payout = it.payout
            info.port = it.meshPort
        }
        if (isNew) PrismPlatform.log.info(TAG, "Peer discovered: $peerIp")

        // Latency, from the request this is answering.
        val answering = when (command) {
            OP_HEARTBEAT_RESP -> OP_HEARTBEAT
            OP_PEER_LIST_RESP -> OP_PEER_LIST_REQ
            OP_DNS_SYNC_RESP -> OP_DNS_SYNC_REQ
            else -> null
        }
        if (answering != null) {
            pendingRequests.remove("$peerIp:$answering")?.let { sentAt ->
                info.latency = (now - sentAt).coerceAtLeast(1L)
            }
        }

        when (command) {
            OP_HEARTBEAT -> sendPacket(
                peerIp, info.port, OP_HEARTBEAT_RESP, localAnnouncement(),
            )

            OP_HEARTBEAT_RESP -> runCatching {
                info.port = JSONObject(payload).optInt("port", DEFAULT_PORT)
            }

            OP_DISCOVERY_REQ -> runCatching {
                val peerPort = JSONObject(payload).optInt("port", DEFAULT_PORT)
                info.port = peerPort
                sendPacket(
                    peerIp, peerPort, OP_HEARTBEAT_RESP, localAnnouncement(),
                )
                requestDnsSync(peerIp)
            }

            OP_PEER_LIST_REQ -> {
                val self = "${MeshUtils.getLocalMeshIp()}:$boundPort"
                val others = activePeers.entries.joinToString(",") { "${it.key}:${it.value.port}" }
                sendPacket(
                    peerIp, info.port, OP_PEER_LIST_RESP,
                    if (others.isEmpty()) self else "$self,$others",
                )
            }

            OP_PEER_LIST_RESP -> ingestPeerList(payload)

            // A clean shutdown, taken immediately rather than waiting out the lease.
            OP_LEAVE -> activePeers.remove(peerIp)

            OP_DNS_SYNC_REQ -> {
                runCatching { JSONObject(payload).optString("dns") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { dnsConsumer?.invoke(peerIp, it) }
                val mine = runCatching { dnsProvider?.invoke() }.getOrNull()
                if (mine != null) {
                    sendPacket(peerIp, info.port, OP_DNS_SYNC_RESP, """{"dns":$mine}""")
                }
            }

            OP_DNS_SYNC_RESP, OP_DNS_WRITE -> runCatching {
                val json = if (command == OP_DNS_WRITE) payload else JSONObject(payload).optString("dns")
                if (json.isNotBlank()) dnsConsumer?.invoke(peerIp, json)
            }

            OP_RELAY -> runCatching {
                // Only a device whose role is Relay forwards. Otherwise every phone in the mesh
                // would amplify every relayed packet, which is how a small mesh becomes a storm.
                if (!com.prism.launcher.PrismSettings.isPrismRelayRole()) return@runCatching
                val body = JSONObject(payload)
                val target = body.optString("to")
                val inner = body.optInt("op", -1)
                val data = body.optString("data")
                val ttl = body.optInt("ttl", 3)
                if (target.isBlank() || inner !in 0..255 || ttl <= 0) return@runCatching
                // ONLY FEATURE OPCODES ARE RELAYED. Forwarding anything let any peer make a relay
                // rewrite other devices' peer tables with the built-in control messages, or nest
                // OP_RELAY to fan one packet out across the mesh. Control state travels directly.
                if (inner < 0x0C || inner.toByte() == OP_RELAY) return@runCatching
                if (data.length > MAX_RELAY_PAYLOAD) return@runCatching
                if (isPeerBanned(normalise(target))) return@runCatching
                val hop = activePeers[normalise(target)] ?: return@runCatching
                // Only forward when the next hop is not the peer that sent it: echoing straight
                // back is the smallest possible loop.
                if (normalise(target) == normalise(peerIp)) return@runCatching
                val forwarded = JSONObject()
                    .put("to", target)
                    .put("op", inner)
                    .put("data", data)
                    .put("ttl", ttl - 1)
                    .toString()
                sendPacket(target, hop.port, inner.toByte(), data)
                if (ttl > 1) {
                    // Also offer it to the rest of the mesh; a client that could not reach the
                    // target directly may be one hop away from a relay that can.
                    broadcastToOthers(OP_RELAY, forwarded, peerIp)
                }
            }

            else -> {
                val handler = handlers[command]
                if (handler == null) {
                    // Expected on a mesh of differently-featured devices, so not an error. Logged at
                    // info once per opcode would still be noise; this is deliberately silent and
                    // [registeredOpcodes] is how a diagnostic answers "why is this being ignored".
                    return
                }
                runCatching { handler.handle(peerIp, payload) }
                    .onFailure {
                        PrismPlatform.log.error(
                            TAG, "Handler for 0x${command.toString(16)} threw", it,
                        )
                    }
            }
        }
    }

    /**
     * Probes peers a neighbour told us about.
     *
     * HEARSAY IS NOT PRESENCE. This used to insert each listed address straight into the peer table
     * with a fresh timestamp, which made a device that had closed Prism look online to everybody
     * that heard a third device mention it -- and, because each peer-list round re-stamped it, the
     * ghost could outlive the real entry it was copied from. A listed address is now only a hint: we
     * send it a heartbeat, and it becomes a peer when it answers for itself. The probe is rate
     * limited per address so a stale list cannot turn into a packet storm.
     */
    private val probedAt = ConcurrentHashMap<String, Long>()

    private fun ingestPeerList(payload: String) {
        val myIps = MeshUtils.getAllLocalIps()
        val now = System.currentTimeMillis()
        payload.split(",").filter { it.isNotBlank() }.forEach { entry ->
            runCatching {
                val parts = entry.split(":")
                val ip = normalise(parts[0].trim())
                // A MESH HINT IS AN ADDRESS, NEVER A NAME: this is a string that arrived in a packet,
                // and probing it used to mean resolving whatever it said.
                if (!MeshUtils.isIpv4Literal(ip)) return@runCatching
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
                if (port !in 1..65535) return@runCatching
                if (myIps.contains(ip) || isPeer(ip)) return@runCatching
                if (probedAt.size >= MAX_PROBED_ADDRESSES) {
                    val stale = probedAt.filterValues { now - it > PROBE_INTERVAL_MS }.keys
                    stale.forEach { probedAt.remove(it) }
                }
                if (probedAt.size >= MAX_PROBED_ADDRESSES) return@runCatching
                val lastProbe = probedAt[ip] ?: 0L
                if (now - lastProbe < PROBE_INTERVAL_MS) return@runCatching
                probedAt[ip] = now
                sendPacket(ip, port, OP_HEARTBEAT, "{}")
            }
        }
    }

    // ── Sending ────────────────────────────────────────────────────────────

    fun requestDnsSync(targetIp: String) {
        val info = activePeers[normalise(targetIp)] ?: return
        val mine = runCatching { dnsProvider?.invoke() }.getOrNull() ?: return
        sendPacket(targetIp, info.port, OP_DNS_SYNC_REQ, """{"dns":$mine}""")
    }

    /**
     * This machine's own addresses, refreshed at most every 1.5 seconds.
     *
     * EVERY packet used to enumerate the network interfaces to answer "is this one of mine?".
     * That is cheap once and expensive at pointer rates: a drag sends a hundred packets a second,
     * and the listener paid for an interface scan before it could inject any of them.
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

    /**
     * Sends to one known peer.
     *
     * Silently does nothing for a peer that is not active, which is the honest outcome: there is no route
     * to it. Callers of this are advisory rather than load-bearing — anything that must arrive uses the
     * TCP path in Phase 50.
     */
    /**
     * Sends one packet through the best peer, asking it to forward to [targetIp].
     *
     * The relay role is what makes this usable: a relay has a route to a part of the mesh this
     * device cannot reach directly. Returns false when there is no peer to hand the packet to, so a
     * caller can fall back to sending it itself rather than assuming delivery.
     */
    fun relayToPeer(targetIp: String, command: Byte, payload: String, ttl: Int = 3): Boolean {
        if (targetIp.isBlank() || ttl <= 0) return false
        val hop = bestPeer() ?: return false
        val port = activePeers[hop]?.port ?: return false
        val body = JSONObject()
            .put("to", targetIp)
            .put("op", command.toInt() and 0xff)
            .put("data", payload)
            .put("ttl", ttl)
            .toString()
        sendPacket(hop, port, OP_RELAY, body)
        return true
    }

    /**
     * Sends to one peer.
     *
     * A PEER MOMENTARILY OUT OF THE TABLE STILL GETS THE PACKET, at the port it last used or the
     * mesh port. This used to return silently, so a pairing offer or confirmation sent during the
     * minute a peer had dropped out went nowhere while the sending screen waited for an answer.
     * Callers always hold a concrete address, and only literals are sent to.
     */
    fun sendToPeer(targetIp: String, command: Byte, payload: String) {
        val ip = normalise(targetIp)
        if (!MeshUtils.isIpLiteral(ip)) return
        val port = activePeers[ip]?.port ?: lostPeers[ip]?.first ?: DEFAULT_PORT
        sendPacket(ip, port, command, payload)
    }

    fun sendPacket(targetIp: String, targetPort: Int, command: Byte, payload: String) {
        val live = socket
        if (live == null || live.isClosed) {
            noteSendFailure("socket is not open (mesh stopped or never started)")
            return
        }
        runCatching {
            val payloadBytes = payload.toByteArray()
            val data = ByteArray(6 + payloadBytes.size)
            PROTOCOL_HEADER.toByteArray().copyInto(data)
            data[5] = command
            payloadBytes.copyInto(data, 6)

            if ((command == OP_HEARTBEAT || command == OP_PEER_LIST_REQ || command == OP_DNS_SYNC_REQ) &&
                pendingRequests.size < MAX_PENDING_REQUESTS
            ) {
                pendingRequests["$targetIp:$command"] = System.currentTimeMillis()
            }
            live.send(DatagramPacket(data, data.size, InetAddress.getByName(targetIp), targetPort))
        }.onFailure { noteSendFailure(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Counts a failed send and logs it sparingly.
     *
     * The first failure and then every fiftieth: a peer that has gone away produces one failure per
     * gossip round for as long as it stays in the table, and logging each one would bury the first --
     * which is the one that says what is actually wrong.
     */
    private fun noteSendFailure(reason: String) {
        sendFailureCount += 1
        if (sendFailureCount == 1L || sendFailureCount % 50L == 0L) {
            lastSendError = reason
            PrismPlatform.log.warn(TAG, "UDP send failed ($sendFailureCount so far): $reason")
        }
    }

    fun broadcastToOthers(command: Byte, payload: String, sourceIp: String? = null) {
        val exclude = sourceIp?.let { normalise(it) }
        activePeers.forEach { (peerIp, info) ->
            if (peerIp != exclude) sendPacket(peerIp, info.port, command, payload)
        }
    }

    /** Where this device wants Prism Coin for traffic it carries; null when it has no wallet. */
    private fun payoutAddress(): String? = runCatching {
        val coin = com.prism.launcher.wallet.CoinRegistry.bySymbol("PSC") ?: return@runCatching null
        com.prism.launcher.wallet.WalletVault.addressFor(coin)
    }.getOrNull()

    private fun localAnnouncement(): String {
        val endpoint = runCatching { serverEndpoint() }.getOrNull()
        return if (endpoint != null) {
            PrismNodeWire.announcement(
                meshPort = boundPort,
                serverPort = endpoint.port,
                serverProtocol = endpoint.protocol,
                serverPublicKey = endpoint.publicKey,
                wireGuardPort = endpoint.wireGuardPort,
                country = PrismSettings.getMyCountryCode(),
                payout = payoutAddress(),
            )
        } else {
            PrismNodeWire.announcement(
                boundPort,
                country = PrismSettings.getMyCountryCode(),
                payout = payoutAddress(),
            )
        }
    }

    /**
     * Strips the IPv4-mapped IPv6 prefix.
     *
     * A dual-stack socket reports an IPv4 sender as `::ffff:192.168.1.5`, so the same peer arrives under
     * two spellings depending on which socket saw it — and the peer table would hold both, heartbeat both,
     * and report twice the peers that exist.
     */
    private fun normalise(ip: String): String {
        // The scope id first. A link-local address arrives as "fe80::1%7" or
        // "0:0:0:0:0:ffff:a9fe:6563%7", and the "%7" names the interface it came in on -- which differs
        // between the socket that received it and any local list it is compared against.
        val bare = ip.substringBefore('%')

        // Then the IPv4-mapped form, in BOTH spellings. Java writes it compressed as "::ffff:1.2.3.4"
        // from some paths and fully expanded as "0:0:0:0:0:ffff:0102:0304" from others, and the original
        // code here only handled the compressed one -- so a device saw its OWN link-local broadcast come
        // back in the expanded spelling, failed to recognise it, and entered itself in its own peer table.
        // It then gossiped with itself and reported one peer more than existed.
        // NEVER RESOLVED. This used to call getByName unconditionally, which looks a NAME up in DNS --
        // and the strings arriving here come from peer lists, relay bodies and packet payloads. A peer
        // list naming an attacker's hostname therefore made every receiver resolve it.
        if (!MeshUtils.isIpLiteral(bare)) return bare.trim().lowercase()
        return runCatching {
            val bytes = InetAddress.getByName(bare).address
            if (bytes.size == 16 && isV4Mapped(bytes)) {
                InetAddress.getByAddress(bytes.copyOfRange(12, 16)).hostAddress ?: bare
            } else {
                InetAddress.getByName(bare).hostAddress ?: bare
            }
        }.getOrDefault(bare)
    }

    /** The first ten bytes zero and the next two 0xFF is an IPv4 address wearing an IPv6 costume. */
    private fun isV4Mapped(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0 until 10) if (bytes[i] != 0.toByte()) return false
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    private const val GOSSIP_INTERVAL_MS = 15_000L

    /** A peer silent this long is heartbeated directly, well inside [PEER_LEASE_MS]. */
    private const val QUIET_PEER_MS = 20_000L

    /** How long a peer that went quiet keeps being probed by unicast. */
    private const val LOST_PEER_PROBE_MS = 15 * 60_000L
    private const val KICK_COOLDOWN_MS = 5 * 60 * 1000L

    /** How often an address heard through the peer list may be probed. */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** Limits that keep a flood of forged packets from growing the tables without bound. */
    private const val MAX_PEERS = 512
    private const val MAX_PROBED_ADDRESSES = 1_024
    private const val MAX_PENDING_REQUESTS = 1_024
    private const val REQUEST_EXPIRY_MS = 120_000L
    private const val MAX_RELAY_PAYLOAD = 8 * 1024

    /** Short enough that a newly discovered peer has a real latency figure almost immediately. */
    private const val FIRST_GOSSIP_DELAY_MS = 1_200L
}
