package com.prism.launcher.email

import com.prism.core.MeshDns
import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The DNS records mail delivery depends on, without a DNS library.
 *
 * MX, TXT, PTR and DNSBL lookups are all one question to one server, and the answers are small.
 * A full resolver would bring caching, DNSSEC and TCP fallback -- none of which this needs, and
 * all of which would be another dependency in a module that has to build for Android. What is
 * needed is a UDP packet with a name in it, and that is what this is.
 *
 * MESH NAMES NEVER GO TO THE PUBLIC RESOLVER. A Prism mail domain is registered in [MeshDns], and
 * asking 1.1.1.1 for it would return NXDOMAIN even though the device is reachable. The mesh
 * registry is consulted first, and its answer is used as an MX with preference 10.
 */
object EmailDns {

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_MX = 15
    private const val TYPE_TXT = 16

    private val random = SecureRandom()
    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Cached(val at: Long, val records: List<Record>)

    data class Record(val name: String, val type: Int, val ttl: Long, val value: String, val preference: Int = 0)

    data class Mx(val host: String, val preference: Int)

    /** Public resolvers, tried in order. A mesh-only network will fail all of them; see [resolveMx]. */
    private val RESOLVERS = listOf("1.1.1.1", "8.8.8.8")

    /**
     * MX hosts for [domain], cheapest preference first.
     *
     * A mesh domain resolves to the device that registered it, because there is no mail exchanger
     * in the public DNS and never will be. The address is used directly: SMTP permits an address
     * literal in a greeting, and the server accepts its own domain as local.
     */
    fun resolveMx(domain: String, preferMesh: Boolean = true): List<Mx> {
        val name = domain.lowercase().trim().trimEnd('.')
        if (name.isBlank()) return emptyList()
        if (preferMesh) {
            val mesh = MeshDns.resolve(name, onlyP2p = false)
            if (mesh != null) return listOf(Mx(mesh, 10))
        }
        if (name.endsWith(MeshDns.SUFFIX)) return emptyList()
        return query(name, TYPE_MX).mapNotNull { record ->
            val host = record.value.trim().trimEnd('.')
            if (host.isBlank()) null else Mx(host, record.preference)
        }.sortedBy { it.preference }
    }

    fun lookupTxt(name: String): List<String> =
        query(name, TYPE_TXT).map { it.value }

    fun lookupPtr(ip: String): String? =
        query(reverseName(ip), TYPE_PTR).firstOrNull()?.value?.trimEnd('.')

    /**
     * The name this connection can honestly use in EHLO on a public MX.
     *
     * Receivers score the greeting. `prism.local` has no A record and no PTR, so it reads as a
     * forged identity; the network's PTR is the one name this address actually owns. Use it when
     * it is a real hostname, and fall back to the literal greeting otherwise rather than inventing
     * a name that does not resolve back to this address.
     */
    fun heloName(ip: String): String {
        val ptr = runCatching { lookupPtr(ip) }.getOrNull().orEmpty().trim().trimEnd('.')
        val usable = ptr.contains('.') && !ptr.contains(' ') && ptr.length in 4..253 &&
            !ptr.startsWith("-") && !ptr.endsWith("-")
        return if (usable) ptr else "prism.local"
    }

    fun lookupA(name: String): List<String> =
        query(name, TYPE_A).map { it.value }

    /**
     * DNSBL status for an address.
     *
     * Spamhaus returns 127.0.0.x for a listing and NXDOMAIN for a clean address. The exact code
     * matters: 127.0.0.2 and .3 are the policy lists, and those are the ones a residential
     * connection usually lands on. A timeout is reported as unknown rather than clean -- telling
     * a user their IP is not blocklisted when the question never got an answer is the one wrong
     * answer that wastes their afternoon.
     */
    fun blocklistStatus(ip: String): BlocklistStatus {
        if (ip.isBlank()) return BlocklistStatus(false, "No public address to check yet.")
        val answer = query("${reverseName(ip)}.zen.spamhaus.org", TYPE_A)
        if (answer.isEmpty()) return BlocklistStatus(false, "Not listed on Spamhaus ZEN.")
        val code = answer.first().value
        val reason = when (code) {
            "127.0.0.2" -> "Spamhaus SBL: known spam source."
            "127.0.0.3" -> "Spamhaus CSS: unsolicited bulk mail."
            "127.0.0.4", "127.0.0.5", "127.0.0.6", "127.0.0.7" ->
                "Spamhaus XBL: exploited or infected host."
            "127.0.0.9" -> "Spamhaus DROP: hijacked network range."
            "127.0.0.10", "127.0.0.11" -> "Spamhaus PBL: residential address, use a relay."
            else -> "Listed by Spamhaus ($code)."
        }
        return BlocklistStatus(true, reason)
    }

    data class BlocklistStatus(val listed: Boolean, val detail: String)

    /** The records the user has to create at their registrar for public mail. */
    data class PublicRecords(
        val mx: String,
        val spf: String,
        val dkim: String,
        val dmarc: String,
        val ptr: String,
        val ptrName: String,
    )

    fun publicRecords(config: EmailConfig, publicIp: String): PublicRecords {
        val domain = config.domain.ifBlank { EmailConfig.DEFAULT_DOMAIN }.trimEnd('.')
        val mxHost = "mx.$domain"
        val key = EmailDkim.ensureKeys(config)
        val ptrName = reverseName(publicIp) + ".in-addr.arpa"
        return PublicRecords(
            mx = "$domain. IN MX 10 $mxHost.",
            spf = "$domain. IN TXT \"v=spf1 mx a ip4:$publicIp -all\"",
            dkim = "${config.dkimSelector}._domainkey.$domain. IN TXT \"v=DKIM1; k=rsa; p=${key.publicKey}\"",
            dmarc = "_dmarc.$domain. IN TXT \"v=DMARC1; p=${config.dmarcPolicy}; " +
                "rua=mailto:${config.abuseContact.ifBlank { "abuse@$domain" }}; adkim=s; aspf=s\"",
            ptr = "$ptrName IN PTR $mxHost.",
            ptrName = ptrName,
        )
    }

    // ── Packet plumbing ───────────────────────────────────────────────────

    private fun query(name: String, type: Int): List<Record> {
        val key = "$name|$type"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 5 * 60_000) return it.records }
        val records = runCatching { ask(name, type) }.getOrDefault(emptyList())
        cache[key] = Cached(System.currentTimeMillis(), records)
        return records
    }

    private fun ask(name: String, type: Int): List<Record> {
        val packet = buildQuery(name, type)
        for (resolver in RESOLVERS) {
            val answer = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 2500
                    socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(resolver, 53)))
                    val buffer = ByteArray(1500)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    parse(response.data.copyOf(response.length))
                }
            }.getOrNull()
            if (answer != null) return answer
        }
        PrismPlatform.log.info("PrismMail", "DNS $name type $type did not resolve")
        return emptyList()
    }

    private fun buildQuery(name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        val id = random.nextInt(0xFFFF)
        u16(id)
        u16(0x0100) // recursion desired
        u16(1); u16(0); u16(0); u16(0)
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0)
        u16(type)
        u16(1)
        return out.toByteArray()
    }

    private fun parse(data: ByteArray): List<Record> {
        if (data.size < 12) return emptyList()
        fun u8(offset: Int) = data[offset].toInt() and 0xFF
        fun u16(offset: Int) = (u8(offset) shl 8) or u8(offset + 1)
        val questions = u16(4)
        val answers = u16(6)
        var offset = 12
        repeat(questions) { offset = skipName(data, offset); offset += 4 }
        val result = mutableListOf<Record>()
        repeat(answers) {
            if (offset >= data.size) return result
            val name = readName(data, offset).first
            offset = readName(data, offset).second
            if (offset + 10 > data.size) return result
            val type = u16(offset)
            val ttl = ((u16(offset + 4).toLong() shl 16) or u16(offset + 6).toLong())
            val length = u16(offset + 8)
            offset += 10
            if (offset + length > data.size) return result
            val start = offset
            when (type) {
                TYPE_A -> if (length == 4) {
                    result.add(Record(name, type, ttl, "${u8(start)}.${u8(start + 1)}.${u8(start + 2)}.${u8(start + 3)}"))
                }
                TYPE_MX -> if (length >= 3) {
                    val preference = u16(start)
                    val host = readName(data, start + 2).first
                    result.add(Record(name, type, ttl, host, preference))
                }
                TYPE_TXT -> {
                    var at = start
                    val text = StringBuilder()
                    while (at < start + length) {
                        val size = u8(at)
                        at++
                        if (at + size > data.size) break
                        text.append(String(data, at, size, Charsets.UTF_8))
                        at += size
                    }
                    result.add(Record(name, type, ttl, text.toString()))
                }
                TYPE_PTR -> result.add(Record(name, type, ttl, readName(data, start).first))
            }
            offset = start + length
        }
        return result
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var at = start
        while (at < data.size) {
            val length = data[at].toInt() and 0xFF
            when {
                length == 0 -> return at + 1
                length and 0xC0 == 0xC0 -> return at + 2
                else -> at += length + 1
            }
        }
        return at
    }

    /** Name plus the offset after it, following compression pointers. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var at = start
        var after = start
        var jumps = 0
        while (at < data.size && jumps < 20) {
            val length = data[at].toInt() and 0xFF
            when {
                length == 0 -> {
                    at++
                    if (after < at) after = at
                    break
                }
                length and 0xC0 == 0xC0 -> {
                    val pointer = ((length and 0x3F) shl 8) or (data.getOrNull(at + 1)?.toInt()?.and(0xFF) ?: 0)
                    if (after == start) after = at + 2
                    at = pointer
                    jumps++
                }
                else -> {
                    if (at + 1 + length > data.size) break
                    labels.add(String(data, at + 1, length, Charsets.US_ASCII))
                    at += length + 1
                }
            }
        }
        return labels.joinToString(".") to after
    }

    private fun reverseName(ip: String): String {
        val parsed = ip.substringBefore('/').split('.')
        if (parsed.size == 4) return parsed.reversed().joinToString(".")
        return ip
    }

    // ── Network state ─────────────────────────────────────────────────────

    /**
     * Tries to bind a listening port. Used to tell the user a port is unusable before starting.
     *
     * Binding is not proof that the internet can reach the port: an ISP that blocks inbound 25
     * will still let the socket open. It does catch the two failures the user can fix here -- a
     * port already in use, and a privileged port this platform will not let an app bind.
     */
    fun canBind(port: Int): String {
        if (port !in 1..65535) return "Port $port is not a valid port."
        return runCatching {
            java.net.ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
            ""
        }.getOrElse {
            when {
                port < 1024 -> "Port $port is reserved: apps cannot open ports below 1024. " +
                    "Prism serves mail on a high port and the router forwards the standard port to it."
                else -> "Port $port is already in use: ${it.message}"
            }
        }
    }

    /**
     * Whether outbound port 25 reaches a mail server.
     *
     * Most ISPs block it for residential connections, and that block is the single most common
     * reason a self-hosted mail server "does not work" while every local test passes. The probe
     * is a TCP connect to a well-known MX and is deliberately only a hint: a failure can also be
     * a firewall, and a success can still be a server that rejects this sender later.
     */
    fun outbound25Reachable(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("gmail-smtp-in.l.google.com", 25), 3000)
            true
        }
    }.getOrDefault(false)

    /**
     * The device's public address, cached.
     *
     * A home connection has no static name to put in an email address, so the address is the
     * public IP. Asked over HTTPS because the alternative -- an external "what is my IP" page --
     * is the same request with more parsing. Empty when offline, which the UI shows as "not known
     * yet" rather than inventing an address.
     */
    @Volatile private var cachedPublicIp: Pair<Long, String>? = null

    fun publicIp(force: Boolean = false): String {
        val cached = cachedPublicIp
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000) {
            return cached.second
        }
        val value = runCatching {
            val connection = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull().orEmpty()
        if (value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            cachedPublicIp = System.currentTimeMillis() to value
            return value
        }
        val fallback = runCatching { MeshUtils.getLocalMeshIp() }.getOrDefault("")
        return cached?.second?.ifBlank { fallback } ?: fallback
    }

    fun localMeshIp(): String = runCatching { MeshUtils.getLocalMeshIp() }.getOrDefault("")

    fun isLocalAddress(host: String): Boolean =
        runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

    /**
     * Whether [ip] is an address the public internet can route back to.
     *
     * A CGNAT address (100.64/10), a private range or a carrier NAT address cannot be a mail
     * domain: a sender on the internet has no way to reach it. Saying that plainly on the
     * dashboard is more useful than letting the user configure a domain that can never receive
     * one message.
     */
    fun isPublicIp(ip: String): Boolean {
        val value = ip.trim().removeSurrounding("[", "]")
        if (!value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return value.contains(':') // IPv6: assume routed
        val parts = value.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return !(
            a == 10 || a == 127 || a == 0 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) ||
                a >= 224
            )
    }

    /** What a public-internet mail server needs to know about its own reachability. */
    data class InboundCheck(
        val publicIp: String,
        val routable: Boolean,
        val reachable: Boolean,
        val port: Int,
        val detail: String,
        /** The port the server actually opened here, which may differ from the public one. */
        val listenerPort: Int = 0,
    ) {
        /** Something the dashboard or a notification can say in one line. */
        val warning: String
            get() = when {
                publicIp.isBlank() ->
                    "The public address could not be found. Mail cannot be addressed to this device yet."
                !routable ->
                    "$publicIp is not a public address. This network is behind carrier-grade NAT, " +
                        "so inbound mail needs a relay or the meshnet."
                // A FAILED PROBE IS NOT PROOF OF A MISSING FORWARD. The check dials the device's own
                // public address from inside its own network; a router without NAT loopback refuses
                // that even when the forward is correct. Saying "set up a forward" flatly sent users
                // back to a router page they had already configured, so the message names the exact
                // mapping to check and admits the limit of an inside-out test.
                !reachable -> buildString {
                    append("Port $port could not be confirmed at $publicIp from this device.")
                    if (listenerPort in 1..65535 && listenerPort != port) {
                        append(" Prism is listening on $listenerPort here, so the router must forward ")
                        append("TCP $port to $listenerPort on this device.")
                    } else {
                        append(" Check that the router forwards TCP $port to this device and that ")
                        append("its firewall allows it.")
                    }
                    append(" Most routers cannot loop a device back to its own public address, so a ")
                    append("correct forward can still fail this check -- confirm from outside the ")
                    append("network. Many ISPs also block inbound port 25 outright, which no router ")
                    append("setting can change; Prism email still works on the meshnet.")
                }
                else -> ""
            }
    }

    /**
     * Checks whether [port] is actually reachable on the public address.
     *
     * THE PROBE IS A CONNECT, NOT A PROOF. It opens a TCP connection to the public address from
     * this device: a router that supports NAT loopback answers even when the forward exists, and
     * one that does not may refuse the connection even when the forward is correct. That is why a
     * failure is a warning with instructions rather than a refusal to start -- the server keeps
     * listening either way, and a user whose forward is fine loses nothing.
     */
    fun checkInbound(port: Int, listenerPort: Int = 0): InboundCheck {
        val ip = publicIp()
        if (ip.isBlank()) {
            return InboundCheck("", false, false, port, "No public address is known.", listenerPort)
        }
        if (!isPublicIp(ip)) {
            return InboundCheck(ip, false, false, port, "$ip is a private or carrier address.", listenerPort)
        }
        val reachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 2500)
                true
            }
        }.getOrDefault(false)
        return InboundCheck(
            publicIp = ip,
            routable = true,
            reachable = reachable,
            port = port,
            detail = if (reachable) "Port $port answers at $ip." else
                "No answer on $ip:$port (a router without NAT loopback cannot be checked this way)." +
                    if (listenerPort in 1..65535 && listenerPort != port) {
                        " The listener here is on $listenerPort."
                    } else "",
            listenerPort = listenerPort,
        )
    }

    /**
     * The local address the server listens on, for a client on this device.
     *
     * The Prism mailbox is served here. A client that dials the PUBLIC address depends on the
     * router forwarding the connection back to itself, which many home routers do not do, so the
     * local client would fail on a server that is running perfectly. Loopback always works on the
     * same device and is the correct address for it.
     */
    const val LOOPBACK: String = "127.0.0.1"
}
