package com.prism.launcher.vpn

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.PayLaterPolicy
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP hole punching through Prism directory servers, for a server that cannot be dialled.
 *
 * ## The problem
 *
 * A phone on mobile data sits behind its carrier's NAT. Carriers do not forward ports, so a phone in
 * the Server role there has no address anyone can reach, and announcing it to a directory used to be
 * skipped outright ([ServerDirectory.checkPublicPort]).
 *
 * ## How punching gets around it
 *
 *  1. The phone REGISTERs with each directory that offers punching, every [KEEPALIVE_MS], FROM ITS
 *     WIREGUARD PORT. The directory sees the public address and port the carrier mapped that socket
 *     to, and the steady keep-alive is what keeps the carrier from forgetting the mapping.
 *  2. A client that wants the phone sends CONNECT from the UDP port its own tunnel will use. The
 *     directory answers with the phone's mapped address (PEER) and tells the phone the client's
 *     (INTRODUCE).
 *  3. Both sides send a few packets at each other ([PING]). Each NAT sees traffic leaving towards
 *     the other side, so it lets the other side's packets in, and the WireGuard handshake then
 *     goes straight between the two devices.
 *
 * The directory only introduces; the tunnel itself never passes through it. Encryption is
 * WireGuard's own.
 *
 * ## When it is used
 *
 * Only by a phone whose ONLY network is mobile data and whose role is Server ([shouldPunch]). A
 * client, bridge or relay dials out and needs no punching, and a server on Wi-Fi or Ethernet uses
 * port forwarding as before.
 *
 * ## Paying for it
 *
 * The rendezvous costs the directory's operator bandwidth, around a hundred bytes every two seconds
 * per phone. Each REGISTERED answer names the directory's Prism Coin payout address and its price
 * per megabyte, and the phone charges itself through the pay-later ledger ([ComputeDebtLedger])
 * as the megabytes add up: paid at once when the balance allows, recorded and worked off by mining
 * when it does not.
 *
 * ## The honest limit
 *
 * Punching works through most home routers and many carriers. Some carrier NATs give every
 * destination a different port (symmetric NAT); between two of those no punch can land, and the
 * client is told so rather than left waiting.
 */
object HolePunch {

    private const val TAG = "PrismHolePunch"

    const val PREFIX = "PRISM_PUNCH"
    const val DEFAULT_PORT = 4748
    private const val PORT_ATTEMPTS = 10

    /** How often a punching server re-registers: what keeps its carrier mapping open. */
    const val KEEPALIVE_MS = 2_000L

    /** A registration not refreshed for this long is gone. */
    const val REGISTRATION_TTL_MS = 15_000L

    /** What a client config carries to say "punch to this server first". */
    const val CONFIG_MARKER = "# Prism-Punch:"

    /** Rendezvous price when an operator has not set one: minor PSC units per megabyte. */
    const val DEFAULT_PRICE_PER_MB = 1_000L

    private const val MAX_PACKET = 1_400

    /** This device's registrar while it is punching, so the directory announcement can say so. */
    @Volatile
    var active: Registrar? = null

    /** Whether this device should rely on punching: only mobile data, and only as a Server. */
    fun shouldPunch(cellularOnly: Boolean, role: String): Boolean =
        cellularOnly && role == PrismSettings.PRISM_ROLE_SERVER

    fun isPunchPacket(data: ByteArray, length: Int = data.size): Boolean {
        val prefix = PREFIX.toByteArray(Charsets.US_ASCII)
        if (length < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    /**
     * The public name for a punching server in a directory.
     *
     * A hash of its directory token, never the token itself: the token is the bearer credential for
     * its row, and handing it to every client would let any of them register in its place.
     */
    fun punchId(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(("prism-punch-id|" + token).toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    /** A directory to punch through: its host, its rendezvous port, and this device's credential for it. */
    data class Target(val host: String, val port: Int, val credential: String, val label: String)

    /** The configured directories that can punch: Prism-hosted ones (an HTTP directory cannot do UDP). */
    fun targets(): List<Target> = ServerDirectory.allDirectories()
        .filter { it.isEnabled && it.url.isBlank() && it.address.isNotBlank() }
        .map { Target(it.address, DEFAULT_PORT, ServerDirectory.credential(it), it.name.ifBlank { it.address }) }

    private fun line(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun text(packet: DatagramPacket): String =
        String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()

    // ── The directory's side ──────────────────────────────────────────────

    /**
     * The rendezvous a directory server runs.
     *
     * [handle] is the whole protocol and is pure apart from [send], so it is tested without
     * sockets; [start] binds a UDP port on every interface -- unlike the directory's TCP listener it
     * must be reachable from the internet, because the phones that need it are not on the mesh yet
     * -- and every request is still authenticated with the directory's credential.
     */
    class Rendezvous(
        private val authorized: (String) -> Boolean,
        private val payout: () -> String,
        private val pricePerMb: () -> Long = { DEFAULT_PRICE_PER_MB },
    ) {
        data class Registration(val id: String, val name: String, val endpoint: InetSocketAddress, val at: Long)

        private val table = ConcurrentHashMap<String, Registration>()
        @Volatile private var socket: DatagramSocket? = null
        val port: Int get() = socket?.localPort ?: 0
        val isRunning: Boolean get() = socket?.isClosed == false

        fun registrations(now: Long = System.currentTimeMillis()): List<Registration> {
            table.entries.removeAll { now - it.value.at > REGISTRATION_TTL_MS }
            return table.values.sortedBy { it.name.lowercase() }
        }

        fun handle(request: String, from: InetSocketAddress, send: (String, InetSocketAddress) -> Unit) {
            val parts = request.trim().split(' ')
            if (parts.size < 2 || parts[0] != PREFIX) return
            when (parts[1]) {
                "REGISTER" -> {
                    val credential = parts.getOrNull(2).orEmpty()
                    val token = parts.getOrNull(3).orEmpty()
                    if (!authorized(credential) || token.length < 16) {
                        send("$PREFIX DENIED", from)
                        return
                    }
                    val name = runCatching {
                        String(Base64.getDecoder().decode(parts.getOrNull(4).orEmpty()), Charsets.UTF_8)
                    }.getOrDefault("").take(64).ifBlank { "Prism server" }
                    val id = punchId(token)
                    val previous = table.put(id, Registration(id, name, from, System.currentTimeMillis()))
                    if (previous == null || previous.endpoint != from) {
                        PrismPlatform.log.info(TAG, "$name is reachable by punching at ${from.address.hostAddress}:${from.port}")
                    }
                    send(
                        "$PREFIX REGISTERED ${from.address.hostAddress} ${from.port} $id " +
                            "${payout().ifBlank { "-" }} ${pricePerMb()}",
                        from,
                    )
                }

                "CONNECT" -> {
                    if (!authorized(parts.getOrNull(2).orEmpty())) {
                        send("$PREFIX DENIED", from)
                        return
                    }
                    val id = parts.getOrNull(3).orEmpty()
                    val registration = table[id]?.takeIf { System.currentTimeMillis() - it.at <= REGISTRATION_TTL_MS }
                    if (registration == null) {
                        send("$PREFIX UNKNOWN $id", from)
                        return
                    }
                    send("$PREFIX PEER ${registration.endpoint.address.hostAddress} ${registration.endpoint.port} $id", from)
                    send("$PREFIX INTRODUCE ${from.address.hostAddress} ${from.port}", registration.endpoint)
                }

                "LIST" -> {
                    if (!authorized(parts.getOrNull(2).orEmpty())) {
                        send("$PREFIX DENIED", from)
                        return
                    }
                    val rows = registrations().take(30).joinToString(";") { registration ->
                        registration.id + "," +
                            Base64.getEncoder().encodeToString(registration.name.toByteArray(Charsets.UTF_8))
                    }
                    send("$PREFIX SERVERS $rows", from)
                }
                // PING and anything else: punch traffic or noise, never answered.
            }
        }

        /** Binds the rendezvous port; returns it, or 0. */
        @Synchronized
        fun start(preferred: Int = DEFAULT_PORT): Int {
            socket?.takeIf { !it.isClosed }?.let { return it.localPort }
            var bound: DatagramSocket? = null
            for (offset in 0 until PORT_ATTEMPTS) {
                bound = runCatching { DatagramSocket(InetSocketAddress(preferred + offset)) }.getOrNull()
                if (bound != null) break
            }
            val listener = bound ?: run {
                PrismPlatform.log.warn(TAG, "Could not bind a hole punching port")
                return 0
            }
            socket = listener
            Thread({ loop(listener) }, "prism-punch-rendezvous").apply { isDaemon = true; start() }
            PrismPlatform.log.info(TAG, "Hole punching rendezvous on UDP ${listener.localPort}")
            return listener.localPort
        }

        @Synchronized
        fun stop() {
            runCatching { socket?.close() }
            socket = null
            table.clear()
        }

        private val budget = ConcurrentHashMap<String, Pair<Long, Int>>()

        /** At most 20 requests a second from one address: one phone needs one every two seconds. */
        private fun allowed(address: InetAddress): Boolean {
            val key = address.hostAddress ?: return false
            val second = System.currentTimeMillis() / 1_000
            val (at, count) = budget[key] ?: (second to 0)
            val next = if (at == second) count + 1 else 1
            budget[key] = second to next
            if (budget.size > 10_000) budget.clear()
            return next <= 20
        }

        private fun loop(listener: DatagramSocket) {
            val buffer = ByteArray(MAX_PACKET)
            while (!listener.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    listener.receive(packet)
                } catch (_: Exception) {
                    if (listener.isClosed) return
                    continue
                }
                if (packet.length <= 0 || !allowed(packet.address)) continue
                val from = InetSocketAddress(packet.address, packet.port)
                runCatching {
                    handle(text(packet), from) { reply, to ->
                        val bytes = line(reply)
                        listener.send(DatagramPacket(bytes, bytes.size, to))
                    }
                }
            }
        }
    }

    // ── The punching server's side ────────────────────────────────────────

    /**
     * Keeps a server registered with every punching directory, and answers introductions.
     *
     * [send] MUST go out through the socket the server's WireGuard port is bound to -- the whole
     * method rests on the directory, and the clients after it, seeing the same carrier mapping.
     * Feed every received punch packet to [onPacket] before handing the rest to WireGuard.
     */
    class Registrar(
        private val token: String,
        private val name: () -> String,
        private val targets: () -> List<Target>,
        private val send: (ByteArray, InetSocketAddress) -> Unit,
    ) {
        private val running = AtomicBoolean(false)
        @Volatile var publicEndpoint: String = ""
            private set
        @Volatile var lastAnswerAt: Long = 0L
            private set
        @Volatile var lastProblem: String = ""
            private set

        /** Per directory host: payout address, price, and bytes not yet charged. */
        private class Account(var payout: String = "", var pricePerMb: Long = DEFAULT_PRICE_PER_MB, var bytes: Long = 0L, var label: String = "")

        private val accounts = ConcurrentHashMap<String, Account>()

        val id: String get() = punchId(token)

        fun isRunning(): Boolean = running.get()

        fun start() {
            if (!running.compareAndSet(false, true)) return
            PrismPlatform.log.info(TAG, "Mobile data only, Server role: registering with punching directories")
            Thread({
                while (running.get()) {
                    runCatching { registerOnce() }.onFailure { lastProblem = it.message ?: "Registration failed" }
                    runCatching { Thread.sleep(KEEPALIVE_MS) }.onFailure { return@Thread }
                }
            }, "prism-punch-keepalive").apply { isDaemon = true; start() }
        }

        fun stop() {
            if (running.compareAndSet(true, false)) {
                PrismPlatform.log.info(TAG, "Stopped registering for hole punching")
                publicEndpoint = ""
            }
        }

        private val resolved = ConcurrentHashMap<String, InetSocketAddress>()

        private fun registerOnce() {
            val list = targets()
            if (list.isEmpty()) {
                lastProblem = "No Prism-hosted directory is configured, so nothing can introduce clients to this phone."
                return
            }
            val label = Base64.getEncoder().encodeToString(name().toByteArray(Charsets.UTF_8))
            list.forEach { target ->
                val address = resolved.getOrPut("${target.host}:${target.port}") {
                    InetSocketAddress(InetAddress.getByName(target.host), target.port)
                }
                val bytes = line("$PREFIX REGISTER ${target.credential} $token $label")
                send(bytes, address)
                meter(address, target.label, bytes.size)
            }
        }

        /** Consumes a punch packet; false for anything else, which belongs to WireGuard. */
        fun onPacket(data: ByteArray, length: Int, from: InetSocketAddress): Boolean {
            if (!isPunchPacket(data, length)) return false
            val parts = String(data, 0, length, Charsets.UTF_8).trim().split(' ')
            meter(from, "", length)
            when (parts.getOrNull(1)) {
                "REGISTERED" -> {
                    publicEndpoint = "${parts.getOrNull(2).orEmpty()}:${parts.getOrNull(3).orEmpty()}"
                    lastAnswerAt = System.currentTimeMillis()
                    lastProblem = ""
                    accountFor(from).apply {
                        payout = parts.getOrNull(5)?.takeIf { it != "-" }.orEmpty()
                        pricePerMb = parts.getOrNull(6)?.toLongOrNull()?.coerceIn(0L, 1_000_000L) ?: DEFAULT_PRICE_PER_MB
                    }
                }
                "DENIED" -> lastProblem = "A directory refused this phone's credentials."
                "INTRODUCE" -> {
                    val ip = parts.getOrNull(2) ?: return true
                    val port = parts.getOrNull(3)?.toIntOrNull() ?: return true
                    val client = InetSocketAddress(ip, port)
                    PrismPlatform.log.info(TAG, "Punching towards a client at $ip:$port")
                    Thread({ punch(client, send) }, "prism-punch-out").apply { isDaemon = true; start() }
                }
            }
            return true
        }

        private fun accountFor(address: InetSocketAddress): Account =
            accounts.getOrPut(address.address.hostAddress ?: address.hostString) { Account() }

        /**
         * Counts rendezvous bytes per directory and charges for each whole megabyte through the
         * pay-later ledger, at the price the directory announced.
         */
        private fun meter(address: InetSocketAddress, label: String, bytes: Int) {
            val account = accountFor(address)
            if (label.isNotBlank()) account.label = label
            val megabytes: Long
            synchronized(account) {
                account.bytes += bytes
                megabytes = account.bytes / 1_000_000L
                if (megabytes == 0L) return
                account.bytes -= megabytes * 1_000_000L
            }
            if (account.payout.isBlank() || account.pricePerMb <= 0L) return
            val amount = BigInteger.valueOf(account.pricePerMb).multiply(BigInteger.valueOf(megabytes))
            runCatching {
                ComputeDebtLedger.chargeAmount(
                    creditorAddress = account.payout,
                    creditorIp = address.address.hostAddress.orEmpty(),
                    deviceName = account.label.ifBlank { "Directory ${address.hostString}" },
                    amount = amount,
                    units = megabytes.toInt(),
                )
                PayLaterPolicy.ensureMining(PayLaterPolicy.Work.MESH_RENDEZVOUS)
            }
        }
    }

    /** A handful of packets towards [to], so this side's NAT lets the other side's traffic in. */
    private fun punch(to: InetSocketAddress, send: (ByteArray, InetSocketAddress) -> Unit) {
        val ping = line("$PREFIX PING")
        repeat(6) {
            runCatching { send(ping, to) }
            runCatching { Thread.sleep(150) }
        }
    }

    // ── The client's side ─────────────────────────────────────────────────

    /** Where a punched server can be reached, or why it cannot. */
    sealed class Connected {
        data class Peer(val address: InetSocketAddress) : Connected()
        data class Failed(val reason: String) : Connected()
    }

    /**
     * Asks [target] to introduce this device to the server [id], then punches towards it, all from
     * [socket] -- which must be bound to the port the tunnel will then use.
     */
    fun connect(socket: DatagramSocket, target: Target, id: String, timeoutMs: Int = 4_000): Connected {
        val directory = runCatching { InetSocketAddress(InetAddress.getByName(target.host), target.port) }
            .getOrElse { return Connected.Failed("${target.label} could not be resolved") }
        val request = line("$PREFIX CONNECT ${target.credential} $id")
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(MAX_PACKET)
        var nextSend = 0L
        while (System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= nextSend) {
                socket.send(DatagramPacket(request, request.size, directory))
                nextSend = System.currentTimeMillis() + 800
            }
            socket.soTimeout = 400
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val parts = text(packet).split(' ')
            if (parts.getOrNull(0) != PREFIX) continue
            when (parts.getOrNull(1)) {
                "PEER" -> {
                    val peer = InetSocketAddress(parts.getOrNull(2) ?: continue, parts.getOrNull(3)?.toIntOrNull() ?: continue)
                    punch(peer) { bytes, to -> socket.send(DatagramPacket(bytes, bytes.size, to)) }
                    return Connected.Peer(peer)
                }
                "UNKNOWN" -> return Connected.Failed(
                    "${target.label} has not heard from that server in the last few seconds -- it is offline, " +
                        "or no longer on mobile data",
                )
                "DENIED" -> return Connected.Failed("${target.label} refused this device's directory credentials")
            }
        }
        return Connected.Failed("${target.label} did not answer on UDP ${target.port}")
    }

    /** The line a client config carries so a Prism client punches before bringing the tunnel up. */
    fun configLine(target: Target, id: String): String = "$CONFIG_MARKER ${target.host}:${target.port} $id"

    /**
     * Prepares a WireGuard client config that names a punched server.
     *
     * Without the [CONFIG_MARKER] line the config is returned unchanged. With it: a fixed ListenPort
     * is chosen (the punch has to come from the port the tunnel uses), the directory introduces this
     * device, the Endpoint is rewritten to where the server's carrier mapped it, and
     * PersistentKeepalive is set to two seconds so neither NAT forgets the path.
     */
    fun prepareClientConfig(config: String): String {
        val marker = config.lineSequence().firstOrNull { it.trim().startsWith(CONFIG_MARKER) } ?: return config
        val fields = marker.trim().removePrefix(CONFIG_MARKER).trim().split(' ')
        val hostPort = fields.getOrNull(0) ?: return config
        val id = fields.getOrNull(1) ?: return config
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT
        val target = targets().firstOrNull { it.host.equals(host, ignoreCase = true) }?.copy(port = port)
            ?: Target(host, port, "", host)

        val lines = config.lines().toMutableList()
        val listenLine = lines.indexOfFirst { it.trim().startsWith("ListenPort", ignoreCase = true) }
        val listenPort = if (listenLine >= 0) {
            lines[listenLine].substringAfter('=').trim().toIntOrNull()
        } else {
            null
        } ?: (40_000 + java.security.SecureRandom().nextInt(20_000))

        val result = runCatching {
            DatagramSocket(InetSocketAddress(listenPort)).use { socket -> connect(socket, target, id) }
        }.getOrElse { Connected.Failed(it.message ?: "Could not open UDP $listenPort") }
        if (result is Connected.Failed) {
            PrismPlatform.log.warn(TAG, "Could not punch to that server: ${result.reason}")
            return config
        }
        val peer = (result as Connected.Peer).address

        if (listenLine < 0) {
            val interfaceAt = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            lines.add(if (interfaceAt >= 0) interfaceAt + 1 else 0, "ListenPort = $listenPort")
        }
        val endpointAt = lines.indexOfFirst { it.trim().startsWith("Endpoint", ignoreCase = true) }
        val endpoint = "Endpoint = ${peer.address.hostAddress}:${peer.port}"
        if (endpointAt >= 0) lines[endpointAt] = endpoint else lines += endpoint
        val keepaliveAt = lines.indexOfFirst { it.trim().startsWith("PersistentKeepalive", ignoreCase = true) }
        if (keepaliveAt >= 0) lines[keepaliveAt] = "PersistentKeepalive = 2" else lines += "PersistentKeepalive = 2"
        PrismPlatform.log.info(TAG, "Punched to ${peer.address.hostAddress}:${peer.port} from UDP $listenPort")
        return lines.joinToString("\n")
    }
}
