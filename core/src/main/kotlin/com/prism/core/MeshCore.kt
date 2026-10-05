package com.prism.core

import com.prism.core.json.JSONObject
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

    /** How long a peer is kept after its last packet. */
    private const val PEER_TIMEOUT_MS = 90_000L

    /** Built-in protocol. Everything from 0x0C up belongs to features. */
    private const val OP_HEARTBEAT: Byte = 0x01
    private const val OP_PEER_LIST_REQ: Byte = 0x02
    private const val OP_PEER_LIST_RESP: Byte = 0x03
    private const val OP_DNS_WRITE: Byte = 0x05
    private const val OP_DNS_SYNC_REQ: Byte = 0x08
    private const val OP_DNS_SYNC_RESP: Byte = 0x09
    private const val OP_DISCOVERY_REQ: Byte = 0x0A
    private const val OP_HEARTBEAT_RESP: Byte = 0x0B

    data class PeerInfo(
        @Volatile var lastSeen: Long,
        @Volatile var port: Int = DEFAULT_PORT,
        /** Round-trip milliseconds, or 9999 until one has been measured. */
        @Volatile var latency: Long = 9999L,
        /** What the peer calls itself, when it has said. Empty until a feature tells us. */
        @Volatile var name: String = "",
    )

    private val activePeers = ConcurrentHashMap<String, PeerInfo>()

    /** Sent-at times for the opcodes that get a reply, so latency is measured and not guessed. */
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var boundPort = DEFAULT_PORT

    @Volatile var lastListenerError: String? = null
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
     */
    fun register(opcode: Byte, handler: OpcodeHandler): Boolean {
        if (opcode < 0x0C) {
            PrismPlatform.log.error(
                TAG,
                "Refusing to override built-in opcode 0x${opcode.toString(16)} — see MeshCore",
            )
            return false
        }
        handlers[opcode] = handler
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

    /** The port to bind. A hook so a platform can read it from its own settings. */
    @Volatile
    var portProvider: () -> Int = { DEFAULT_PORT }

    // ── State ──────────────────────────────────────────────────────────────

    fun isOnMesh(): Boolean = running && socket?.isClosed == false

    fun activePeerIps(): List<String> = activePeers.keys.toList()

    fun peers(): Map<String, PeerInfo> = activePeers.toMap()

    fun peerCount(): Int = activePeers.size

    fun isPeer(ip: String): Boolean = activePeers.containsKey(normalise(ip))

    fun peerPort(ip: String): Int? = activePeers[normalise(ip)]?.port

    /** The lowest-latency healthy peer, for a caller that just needs somebody to ask. */
    fun bestPeer(): String? {
        if (activePeers.isEmpty()) return null
        val cutoff = System.currentTimeMillis() - 60_000
        val healthy = activePeers.entries.filter { it.value.lastSeen > cutoff }
        if (healthy.isEmpty()) return activePeers.keys.firstOrNull()
        return healthy.minByOrNull { it.value.latency }?.key
    }

    fun listenerHealth(): String = when {
        !running -> "Not started."
        socket?.isClosed != false -> "Socket closed. ${lastListenerError.orEmpty()}"
        else -> "Listening on UDP $boundPort · ${activePeers.size} peer(s)"
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
        running = false
        runCatching { socket?.close() }
        socket = null
        activePeers.clear()
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
                sendPacket(ip, parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT,
                    OP_DISCOVERY_REQ, """{"port":$boundPort}""")
            }
        }

        // A short first round. Discovery finds a peer immediately but latency is only known once a
        // heartbeat has been answered, so waiting a full interval means every freshly-found peer reads as
        // 9999 ms -- indistinguishable from one that is not responding.
        runCatching { Thread.sleep(FIRST_GOSSIP_DELAY_MS) }.onFailure { return }

        while (running) {
            if (!running) return

            val now = System.currentTimeMillis()
            // Expire before gossiping, so a dead peer is not chosen as the one to talk to.
            activePeers.entries.removeAll { now - it.value.lastSeen > PEER_TIMEOUT_MS }

            if (activePeers.isEmpty()) {
                // Nothing known: broadcast again rather than sitting idle. A device that joined a network
                // after Prism started would otherwise never be found.
                sendDiscoveryBroadcast(boundPort)
                continue
            }
            val target = activePeers.keys.randomOrNull() ?: continue
            val info = activePeers[target] ?: continue
            sendPacket(target, info.port, OP_HEARTBEAT, "{}")
            sendPacket(target, info.port, OP_PEER_LIST_REQ, "{}")

            runCatching { Thread.sleep(GOSSIP_INTERVAL_MS) }.onFailure { return }
        }
    }

    private fun sendDiscoveryBroadcast(port: Int) {
        // getBroadcastAddresses returns InetAddress; sendPacket takes a host string because every other
        // caller has one. Resolving back to text here rather than overloading sendPacket, so there is one
        // send path and one place latency is recorded.
        MeshUtils.getBroadcastAddresses().forEach { address ->
            val host = address.hostAddress ?: return@forEach
            sendPacket(host, port, OP_DISCOVERY_REQ, """{"port":$port}""")
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
        if (MeshUtils.getAllLocalIps().contains(peerIp)) return

        val command = data[5]
        val payload = String(data, 6, data.size - 6)
        val now = System.currentTimeMillis()

        val isNew = !activePeers.containsKey(peerIp)
        val info = activePeers.getOrPut(peerIp) { PeerInfo(now, packet.port) }
        info.lastSeen = now
        info.port = packet.port
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
            OP_HEARTBEAT -> sendPacket(peerIp, info.port, OP_HEARTBEAT_RESP, """{"port":$boundPort}""")

            OP_HEARTBEAT_RESP -> runCatching {
                info.port = JSONObject(payload).optInt("port", DEFAULT_PORT)
            }

            OP_DISCOVERY_REQ -> runCatching {
                val peerPort = JSONObject(payload).optInt("port", DEFAULT_PORT)
                info.port = peerPort
                sendPacket(peerIp, peerPort, OP_HEARTBEAT_RESP, """{"port":$boundPort}""")
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
     * Adds peers a neighbour told us about.
     *
     * Our own addresses are excluded, and so are peers already known — without the second check a
     * peer-list round would re-send a discovery packet to every peer every time, which is the O(peers²)
     * traffic the single-random-peer gossip exists to avoid.
     */
    private fun ingestPeerList(payload: String) {
        val myIps = MeshUtils.getAllLocalIps()
        payload.split(",").filter { it.isNotBlank() }.forEach { entry ->
            runCatching {
                val parts = entry.split(":")
                val ip = normalise(parts[0].trim())
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
                if (ip.isNotEmpty() && !myIps.contains(ip) && !activePeers.containsKey(ip)) {
                    activePeers[ip] = PeerInfo(System.currentTimeMillis(), port)
                    sendPacket(ip, port, OP_HEARTBEAT, "{}")
                    requestDnsSync(ip)
                }
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
     * Sends to one known peer.
     *
     * Silently does nothing for a peer that is not active, which is the honest outcome: there is no route
     * to it. Callers of this are advisory rather than load-bearing — anything that must arrive uses the
     * TCP path in Phase 50.
     */
    fun sendToPeer(targetIp: String, command: Byte, payload: String) {
        val info = activePeers[normalise(targetIp)] ?: return
        sendPacket(targetIp, info.port, command, payload)
    }

    fun sendPacket(targetIp: String, targetPort: Int, command: Byte, payload: String) {
        val live = socket ?: return
        runCatching {
            val payloadBytes = payload.toByteArray()
            val data = ByteArray(6 + payloadBytes.size)
            PROTOCOL_HEADER.toByteArray().copyInto(data)
            data[5] = command
            payloadBytes.copyInto(data, 6)

            if (command == OP_HEARTBEAT || command == OP_PEER_LIST_REQ || command == OP_DNS_SYNC_REQ) {
                pendingRequests["$targetIp:$command"] = System.currentTimeMillis()
            }
            live.send(DatagramPacket(data, data.size, InetAddress.getByName(targetIp), targetPort))
        }
    }

    fun broadcastToOthers(command: Byte, payload: String, sourceIp: String? = null) {
        val exclude = sourceIp?.let { normalise(it) }
        activePeers.forEach { (peerIp, info) ->
            if (peerIp != exclude) sendPacket(peerIp, info.port, command, payload)
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

    /** Short enough that a newly discovered peer has a real latency figure almost immediately. */
    private const val FIRST_GOSSIP_DELAY_MS = 1_200L
}
