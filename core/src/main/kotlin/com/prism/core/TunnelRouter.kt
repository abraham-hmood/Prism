package com.prism.core

/**
 * What happens to each packet that enters the Prism tunnel. PHASES 58, 59, 61, 63.
 *
 * ## Why the decision lives here and not next to the adapter
 *
 * Creating a TUN adapter needs administrator rights, a signed driver on Windows and CAP_NET_ADMIN on
 * Linux. A decision that lived inside that code could only ever be tested by someone with all three,
 * on a machine willing to have its routing table edited -- which means in practice it would not be
 * tested at all, and the routing rules of a privacy tunnel are the last thing that should be taken on
 * trust. Here it is a pure function from a byte array to a [Verdict], the adapter is somebody else's
 * problem, and the test suite can assert that a blocked name is refused and a whitelisted process is
 * let past without any of it.
 *
 * ## What actually enters the tunnel
 *
 * Very little, on purpose, and this is the single most important thing to understand about the design.
 * Prism does NOT claim the default route. The adapter is given three routes -- the two DNS addresses,
 * and the mesh subnet 10.8.0.0/24 -- exactly as `PrivateDnsVpnService` does on Android. Everything
 * else on the machine keeps using the ordinary connection and never comes near this code.
 *
 * That has three consequences worth stating plainly rather than discovering later:
 *
 *  1. A DEFAULT-ROUTE TUNNEL WOULD BE A LIE HERE. Carrying every packet would mean Prism performing
 *     NAT, TCP reassembly and connection tracking in the JVM for the whole machine. It would be slow
 *     and, much worse, a bug in it would break every application at once rather than just DNS.
 *  2. WHAT IS PROTECTED IS RESOLUTION AND THE MESH, which is what private tabs are for: mesh names
 *     resolve, blocked names are refused before a connection is ever attempted, and mesh addresses
 *     reach the mesh. The browser's own traffic to ordinary sites is protected by the browser -- an
 *     off-the-record context, no third-party cookies, DNT and Sec-GPC -- not by a tunnel.
 *  3. SPLIT TUNNELLING IS ALREADY TRUE FOR EVERYTHING NOT LISTED ABOVE. The whitelist therefore only
 *     has a decision to make about the packets that do arrive, which is why [Verdict.Bypass] means
 *     what it means below.
 *
 * ## The order of the checks, which is a security property
 *
 * Bypass is tested BEFORE the blocklist. A whitelisted application is one the user has said Prism
 * should keep its hands off; filtering its DNS anyway would make the whitelist a lie in exactly the
 * case somebody added an entry to fix. The sink list is tested before bypass for packets that are not
 * DNS, because a route to a blocked address exists only because this tunnel put it there.
 */
object TunnelRouter {

    /** What to do with one packet. */
    sealed interface Verdict {
        /** Write these bytes back into the adapter. */
        data class Reply(val packet: ByteArray, val reason: String) : Verdict

        /** Nothing goes back. The sender sees a timeout, which is what a blocked name should look like. */
        data class Drop(val reason: String) : Verdict

        /**
         * A whitelisted process: the tunnel must not touch this.
         *
         * For DNS this is carried out as an upstream forward with no mesh lookup and no filtering --
         * the same answer the machine would have got with Prism not running, which is the whole point
         * of a whitelist. For anything else it is a drop with a log line, because a packet addressed
         * into the mesh subnet has nowhere else to go: that subnet exists only inside the tunnel the
         * user asked to bypass.
         */
        data class Bypass(val executable: String, val reason: String) : Verdict

        /** Send it on over the mesh. Only mesh-subnet traffic reaches this. */
        data class Mesh(val packet: ByteArray, val destination: String) : Verdict
    }

    // ── What the caller plugs in ───────────────────────────────────────────

    /**
     * Whether a name should be refused. Defaults to allowing everything.
     *
     * A hook rather than a direct call into HostBlocklist because the blocklist needs a preferences
     * store and this needs to be testable with two lines.
     */
    var blockedHost: (String) -> Boolean = { false }

    /**
     * The executable that owns a local port, when it is whitelisted, or null.
     *
     * The platform answers this: on Windows through the extended TCP/UDP tables, on Linux through
     * /proc. Null means "not whitelisted, or not known" -- and those two collapsing into one answer is
     * deliberate. A lookup that fails must mean the packet goes through the tunnel, never that it
     * bypasses it, because a bypass granted by accident is a privacy hole and a tunnel applied by
     * accident is an inconvenience.
     */
    var bypassOwner: (Int) -> String? = { null }

    /** Addresses routed into the adapter purely to be swallowed. Packed form, see [IpPackets.packed]. */
    var sinkAddresses: Set<Int> = emptySet()

    /** Forwards a DNS query upstream verbatim, for whitelisted callers. */
    var forwardUpstream: (ByteArray) -> ByteArray? = { MeshDnsServer.forward(it) }

    /** Answers a DNS query the mesh way: a mesh record if there is one, otherwise upstream. */
    var resolve: (ByteArray) -> ByteArray? = { MeshDnsServer.answer(it) }

    /** Hands a mesh-subnet packet to whatever carries it. Null when nothing does. */
    var meshSender: ((ByteArray, String) -> Boolean)? = null

    /** The subnet carried over the mesh, as three octets. 10.8.0.0/24, as on Android. */
    var meshSubnet = Triple(10, 8, 0)

    // ── Counters, for the status line ──────────────────────────────────────

    var seen = 0L; private set
    var malformed = 0L; private set
    var answeredMesh = 0L; private set
    var refused = 0L; private set
    var bypassed = 0L; private set
    var toMesh = 0L; private set
    var dropped = 0L; private set

    fun resetCounters() {
        seen = 0; malformed = 0; answeredMesh = 0; refused = 0; bypassed = 0; toMesh = 0; dropped = 0
    }

    fun counters(): String =
        "" + seen + " seen, " + answeredMesh + " resolved, " + refused + " refused, " +
            bypassed + " bypassed, " + toMesh + " to the mesh, " + dropped + " dropped, " +
            malformed + " malformed"

    // ── The decision ───────────────────────────────────────────────────────

    /**
     * Routes one frame.
     *
     * @param length how much of [packet] was filled, so a reused read buffer does not have to be
     *   copied before it can be examined.
     */
    fun route(packet: ByteArray, length: Int = packet.size): Verdict {
        seen++

        val header = IpPackets.parse(packet, length)
        if (header == null) {
            malformed++
            return Verdict.Drop("not an IPv4 packet this tunnel handles")
        }

        // DNS, which is the reason the tunnel exists.
        if (header.isUdp && header.destinationPort == DNS_PORT) {
            return dns(packet, header)
        }

        // An address routed here only so that it goes nowhere.
        if (header.destinationPacked() in sinkAddresses) {
            dropped++
            return Verdict.Drop("blocked address " + header.destinationText())
        }

        val (a, b, c) = meshSubnet
        if (header.destinationIn24(a, b, c)) {
            // The whitelist is checked here too, so a whitelisted process is told the truth -- that
            // the mesh subnet is unreachable to it -- rather than quietly having its packet carried.
            owner(header)?.let {
                bypassed++
                return Verdict.Bypass(
                    it,
                    "whitelisted, and " + header.destinationText() + " only exists inside the tunnel",
                )
            }
            val sender = meshSender
            if (sender == null) {
                dropped++
                return Verdict.Drop("nothing is carrying the mesh subnet on this device")
            }
            toMesh++
            return Verdict.Mesh(packet.copyOf(header.totalLength), header.destinationText())
        }

        // Anything else should not have been routed here at all. Dropped rather than forwarded,
        // because forwarding a packet whose route Prism did not create would make this tunnel a
        // general-purpose router, which the class comment explains it deliberately is not.
        dropped++
        return Verdict.Drop("not a route this tunnel claims: " + header.destinationText())
    }

    private fun dns(packet: ByteArray, header: IpPackets.Ipv4): Verdict {
        val query = IpPackets.payload(packet, header)
        if (query.size < 12) {
            malformed++
            return Verdict.Drop("a DNS query too short to have a header")
        }

        // The whitelist first. See the class comment on why this order matters.
        owner(header)?.let { executable ->
            val answer = forwardUpstream(query)
            bypassed++
            return if (answer == null) {
                Verdict.Bypass(executable, "whitelisted, and upstream did not answer")
            } else {
                Verdict.Reply(
                    IpPackets.udpReply(header, answer),
                    "whitelisted: " + executable + ", answered upstream unfiltered",
                )
            }
        }

        val name = MeshDnsServer.questionName(query)

        if (name != null && blockedHost(name)) {
            refused++
            return Verdict.Reply(IpPackets.udpReply(header, nxDomain(query)), "refused " + name)
        }

        val answer = resolve(query)
        if (answer == null) {
            dropped++
            return Verdict.Drop("no answer for " + (name ?: "an unreadable query"))
        }
        answeredMesh++
        return Verdict.Reply(IpPackets.udpReply(header, answer), "resolved " + (name ?: "a query"))
    }

    /** The whitelisted owner of this packet's source port, if any. */
    private fun owner(header: IpPackets.Ipv4): String? {
        if (header.sourcePort <= 0) return null
        return runCatching { bypassOwner(header.sourcePort) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ── DNS replies this tunnel makes up itself ────────────────────────────

    /**
     * A refusal.
     *
     * NXDOMAIN RATHER THAN 0.0.0.0, which is the same choice the Android blocklist makes and for the
     * same reason: an address record pointing at nowhere makes a client open a connection and wait for
     * it to fail, while "no such name" fails instantly and shows a sensible message.
     */
    fun nxDomain(query: ByteArray): ByteArray {
        val end = questionEnd(query) ?: return query
        val reply = ByteArray(end)
        query.copyInto(reply, 0, 0, end)
        reply[2] = 0x81.toByte()
        reply[3] = 0x83.toByte()          // response, recursion, name error
        reply[6] = 0; reply[7] = 0        // no answers
        reply[8] = 0; reply[9] = 0
        reply[10] = 0; reply[11] = 0
        return reply
    }

    /**
     * The name exists but has no record of this type.
     *
     * Needed for AAAA queries about mesh names: a mesh address is IPv4, and answering NXDOMAIN to the
     * AAAA would tell the client the name does not exist at all, which some resolvers then cache for
     * the A query too.
     */
    fun noData(query: ByteArray): ByteArray {
        val end = questionEnd(query) ?: return query
        val reply = ByteArray(end)
        query.copyInto(reply, 0, 0, end)
        reply[2] = 0x81.toByte()
        reply[3] = 0x80.toByte()          // response, recursion, no error
        reply[6] = 0; reply[7] = 0        // no answers, which is what makes it NODATA
        reply[8] = 0; reply[9] = 0
        reply[10] = 0; reply[11] = 0
        return reply
    }

    /** Where the question section ends, or null when the query is malformed. */
    private fun questionEnd(query: ByteArray): Int? {
        if (query.size < 13) return null
        var index = 12
        while (index < query.size) {
            val length = query[index].toInt() and 0xFF
            if (length == 0) { index++; break }
            if (length and 0xC0 != 0) return null     // a pointer in a question is malformed
            index += 1 + length
        }
        val end = index + 4
        return if (end > query.size) null else end
    }

    const val DNS_PORT = 53
}
