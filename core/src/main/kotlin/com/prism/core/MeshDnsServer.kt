package com.prism.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * A DNS server that answers mesh names and forwards everything else. PHASE 60.
 *
 * ## Why a DNS server rather than a hosts file
 *
 * Because mesh names come and go. A peer announces `notes.p2p` when it wakes and stops when it sleeps,
 * and the set changes while the machine is running — a hosts file would have to be rewritten on every
 * gossip round, would need administrator rights each time, and would leave stale entries behind if Prism
 * crashed. A resolver answers from the live registry.
 *
 * ## Why every other name is forwarded rather than refused
 *
 * Because this becomes the machine's resolver, and a resolver that only answered for mesh names would
 * break every other name on the system. Anything Prism does not know is passed to the upstream server
 * unchanged and the answer handed back, so pointing a machine at Prism costs nothing for ordinary
 * browsing. THE MESH IS AN ADDITION TO DNS, NOT A REPLACEMENT FOR IT.
 *
 * ## What it does not do
 *
 * No DNSSEC, no EDNS negotiation, no TCP fallback, no cache of its own. A forwarded query is copied
 * through and the reply copied back, which preserves whatever the upstream said including its own
 * failures. A name this device answers for is a single A record. Anything larger than a datagram — a
 * zone transfer, a response that sets the truncation bit — is the upstream's business, and a resolver
 * that tried to be clever about it would break cases that currently work by being passed through.
 *
 * ## Why port 53 is not assumed
 *
 * On Windows a user process can bind port 53; on Linux and macOS anything under 1024 needs privileges.
 * So the port is a parameter and [start] reports what it managed rather than failing: a resolver on
 * 5353 that the user points something at deliberately is far more useful than a refusal.
 */
object MeshDnsServer {

    private const val TAG = "PrismDnsServer"

    /** The standard port, tried first. */
    const val DEFAULT_PORT = 53

    /** Where it lands when 53 is taken or not permitted. */
    const val FALLBACK_PORT = 5353

    /** Where an unknown name goes. Cloudflare, because it answers fast and does not log by default. */
    @Volatile
    var upstream: String = "1.1.1.1"

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false

    /** The port it is actually on, or 0. */
    @Volatile
    var port: Int = 0
        private set

    /** Answered from the mesh since startup, and passed upstream. For a diagnostics page. */
    @Volatile var answeredLocally: Int = 0; private set
    @Volatile var forwarded: Int = 0; private set

    val isRunning: Boolean get() = running

    /**
     * Starts listening. Returns the port, or 0 if neither could be bound.
     *
     * Binds to loopback only. A resolver reachable from the whole network is an open resolver, which is
     * a thing people scan for and use to amplify attacks; this one exists to serve the machine it runs
     * on.
     */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (running) return port

        val bound = bind(preferredPort) ?: bind(FALLBACK_PORT)
        if (bound == null) {
            PrismPlatform.log.warn(TAG, "Could not bind a DNS port; mesh names will not resolve system-wide")
            return 0
        }

        socket = bound
        port = bound.localPort
        running = true

        thread(name = "mesh-dns", isDaemon = true) { loop(bound) }
        PrismPlatform.log.info(TAG, "Resolving on 127.0.0.1:" + port + ", forwarding to " + upstream)
        return port
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        port = 0
    }

    private fun bind(candidate: Int): DatagramSocket? = runCatching {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate))
    }.getOrNull()

    private fun loop(server: DatagramSocket) {
        val buffer = ByteArray(1500)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { server.receive(packet); true }.getOrDefault(false)
            if (!received) continue

            val query = packet.data.copyOf(packet.length)
            val from = packet.socketAddress

            // One thread per query: a forwarded lookup can take a second, and a resolver that handled
            // them one at a time would stall every other name on the machine behind the slowest one.
            thread(name = "mesh-dns-query", isDaemon = true) {
                val reply = answer(query)
                if (reply != null) {
                    runCatching { server.send(DatagramPacket(reply, reply.size, from)) }
                }
            }
        }
    }

    /** Builds the reply for one query: a mesh answer, or whatever upstream says. */
    internal fun answer(query: ByteArray): ByteArray? {
        val name = questionName(query)
        if (name != null) {
            val ip = MeshDns.resolve(name, onlyP2p = false)
            if (ip != null && ip != "0.0.0.0") {
                answeredLocally++
                val built = buildAnswer(query, ip)
                if (built != null) return built
            }
        }
        forwarded++
        return forward(query)
    }

    /**
     * The name being asked about, or null.
     *
     * Only the first question, and only when there is exactly one: a query with several is rare enough
     * that forwarding it whole is simpler than answering part of it and forwarding the rest, which the
     * format does not really allow anyway.
     */
    internal fun questionName(query: ByteArray): String? {
        if (query.size < 13) return null
        val questions = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (questions != 1) return null

        val labels = StringBuilder()
        var index = 12
        while (index < query.size) {
            val length = query[index].toInt() and 0xFF
            if (length == 0) break
            // A compression pointer in a QUESTION is malformed; refuse rather than chase it.
            if (length and 0xC0 != 0) return null
            if (index + 1 + length > query.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(query, index + 1, length, Charsets.US_ASCII))
            index += 1 + length
        }
        return labels.toString().lowercase().takeIf { it.isNotBlank() }
    }

    /**
     * An A-record reply pointing at [ip], echoing the question.
     *
     * Built by hand because the JDK has no DNS message API. The answer copies the question section
     * verbatim and points at it with a compression pointer (0xC00C), which is what every resolver
     * produces and what every client expects.
     */
    internal fun buildAnswer(query: ByteArray, ip: String): ByteArray? {
        val address = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return null
        if (address.size != 4) return null // A records only; a mesh address is IPv4.

        // Where the question ends: the name, then two bytes of type and two of class.
        var index = 12
        while (index < query.size) {
            val length = query[index].toInt() and 0xFF
            if (length == 0) { index++; break }
            index += 1 + length
        }
        val questionEnd = index + 4
        if (questionEnd > query.size) return null

        // Only A queries are answered here. Anything else -- AAAA, TXT, SRV -- goes upstream, which is
        // right: this device knows one fact about a mesh name and should not pretend the others are
        // absent by answering an empty record.
        val type = ((query[index].toInt() and 0xFF) shl 8) or (query[index + 1].toInt() and 0xFF)
        if (type != TYPE_A) return null

        val reply = ByteArray(questionEnd + 16)
        System.arraycopy(query, 0, reply, 0, questionEnd)

        // Flags: response, recursion desired echoed, recursion available.
        reply[2] = (0x81).toByte()
        reply[3] = (0x80).toByte()
        reply[6] = 0; reply[7] = 1        // one answer
        reply[8] = 0; reply[9] = 0        // no authority records
        reply[10] = 0; reply[11] = 0      // no additional records

        var out = questionEnd
        reply[out++] = 0xC0.toByte(); reply[out++] = 0x0C   // pointer to the question's name
        reply[out++] = 0; reply[out++] = TYPE_A.toByte()
        reply[out++] = 0; reply[out++] = 1                  // class IN
        // A SHORT TTL, on purpose: a mesh address changes when a peer reconnects, and a client that
        // cached it for an hour would keep trying an address nobody is on.
        reply[out++] = 0; reply[out++] = 0; reply[out++] = 0; reply[out++] = TTL_SECONDS.toByte()
        reply[out++] = 0; reply[out++] = 4                  // four bytes of address
        System.arraycopy(address, 0, reply, out, 4)
        return reply
    }

    /**
     * Passes a query to the upstream resolver and returns its reply verbatim.
     *
     * INTERNAL RATHER THAN PRIVATE because [TunnelRouter] needs exactly this and nothing else for a
     * whitelisted process: the answer the machine would have got with Prism not running, with no mesh
     * lookup and no filtering in front of it.
     */
    internal fun forward(query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { client ->
            client.soTimeout = UPSTREAM_TIMEOUT_MS
            client.send(
                DatagramPacket(query, query.size, InetAddress.getByName(upstream), DEFAULT_PORT)
            )
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)
            client.receive(packet)
            packet.data.copyOf(packet.length)
        }
    }.getOrNull()

    private const val TYPE_A = 1
    private const val TTL_SECONDS = 30
    private const val UPSTREAM_TIMEOUT_MS = 4_000

    /** What a user has to do to point this machine at Prism, per platform. */
    fun instructions(): String = when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") ->
            "Windows: Settings, Network, your adapter, Edit DNS, Manual, IPv4 on, Preferred DNS " +
                "127.0.0.1. Or from an elevated prompt: netsh interface ip set dns \"Wi-Fi\" static 127.0.0.1"

        System.getProperty("os.name").orEmpty().lowercase().contains("mac") ->
            "macOS: System Settings, Network, your connection, Details, DNS, add 127.0.0.1 at the top."

        else ->
            "Linux: point /etc/resolv.conf at 127.0.0.1, or set the DNS on the connection in " +
                "NetworkManager. With systemd-resolved, use `resolvectl dns <iface> 127.0.0.1`."
    }
}
