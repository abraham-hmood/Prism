package com.prism.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the Prism tunnel does to each packet. PHASES 58, 63.
 *
 * ## Why this is worth a test more than most things are
 *
 * Every rule here is a security property, and every one of them fails SILENTLY when it is wrong. A
 * blocklist that stops refusing answers the name instead, and the page loads -- which looks like
 * success. A whitelist check in the wrong place filters an application the user exempted, and the only
 * symptom is a program that mysteriously cannot resolve one name. A reply built with the ends the wrong
 * way round is ignored by the local stack without a word, and DNS just appears to hang.
 *
 * None of it can be tested through the adapter: that needs administrator rights and a signed driver,
 * so a test that needed one would be a test nobody runs. Which is exactly why the decision lives in
 * [TunnelRouter] as a pure function and the adapter does not know about any of it.
 */
class TunnelRoutingTest {

    private val client = "10.7.0.2"
    private val resolver = "10.7.0.1"

    @BeforeTest
    fun reset() {
        TunnelRouter.resetCounters()
        TunnelRouter.blockedHost = { false }
        TunnelRouter.bypassOwner = { null }
        TunnelRouter.sinkAddresses = emptySet()
        TunnelRouter.meshSender = null
        // Neither hook may touch the network in a test: one answers from a table, the other records.
        TunnelRouter.resolve = { query -> answerWith(query, "93.184.216.34") }
        TunnelRouter.forwardUpstream = { query -> answerWith(query, "1.2.3.4") }
    }

    @AfterTest
    fun restore() {
        TunnelRouter.resolve = { MeshDnsServer.answer(it) }
        TunnelRouter.forwardUpstream = { MeshDnsServer.forward(it) }
        TunnelRouter.bypassOwner = { null }
        TunnelRouter.blockedHost = { false }
    }

    // ── The packet codec ───────────────────────────────────────────────────

    @Test
    fun `a built udp packet parses back to what went in`() {
        val packet = IpPackets.udp(
            source = IpPackets.bytes(client)!!,
            destination = IpPackets.bytes(resolver)!!,
            sourcePort = 51234,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3, 4),
        )

        val header = assertNotNull(IpPackets.parse(packet), "a packet this code built must parse")
        assertEquals(IpPackets.PROTOCOL_UDP, header.protocol)
        assertEquals(client, header.sourceText())
        assertEquals(resolver, header.destinationText())
        assertEquals(51234, header.sourcePort)
        assertEquals(53, header.destinationPort)
        assertEquals(4, header.payloadLength)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(IpPackets.payload(packet, header)))
    }

    @Test
    fun `the header checksum is one a kernel would accept`() {
        val packet = IpPackets.udp(
            IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 1000, 53, ByteArray(12),
        )
        assertTrue(IpPackets.checksumValid(packet), "the checksum must verify against its own header")

        // And it must actually depend on the header: a flipped address has to invalidate it.
        packet[19] = (packet[19] + 1).toByte()
        assertTrue(!IpPackets.checksumValid(packet), "a changed address must break the checksum")
    }

    @Test
    fun `a reply swaps both ends`() {
        val query = IpPackets.udp(
            IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 44444, 53, ByteArray(4),
        )
        val header = assertNotNull(IpPackets.parse(query))
        val reply = assertNotNull(IpPackets.parse(IpPackets.udpReply(header, byteArrayOf(9))))

        // The whole point: what the client sent TO must come back FROM, on the port it asked from.
        assertEquals(resolver, reply.sourceText())
        assertEquals(client, reply.destinationText())
        assertEquals(53, reply.sourcePort)
        assertEquals(44444, reply.destinationPort)
    }

    @Test
    fun `rubbish is refused rather than interpreted`() {
        assertNull(IpPackets.parse(ByteArray(0)), "an empty buffer")
        assertNull(IpPackets.parse(ByteArray(19)), "shorter than a header")
        assertNull(IpPackets.parse(ByteArray(40) { 0x60.toByte() }), "an IPv6 version nibble")

        // A header claiming more than the buffer holds -- the shape of an attempt to read past the end.
        val lying = IpPackets.udp(
            IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 1, 53, ByteArray(4),
        )
        IpPackets.writeU16(lying, 2, 9000)
        assertNull(IpPackets.parse(lying), "a total length larger than the packet")
    }

    @Test
    fun `a fragment is dropped rather than reassembled`() {
        val packet = IpPackets.udp(
            IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 1, 53, ByteArray(8),
        )
        packet[6] = 0x20            // the more-fragments bit
        assertNull(IpPackets.parse(packet), "the more-fragments bit")

        val offset = IpPackets.udp(
            IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 1, 53, ByteArray(8),
        )
        offset[7] = 8               // a non-zero fragment offset
        assertNull(IpPackets.parse(offset), "a non-zero fragment offset")
    }

    // ── The routing rules ──────────────────────────────────────────────────

    @Test
    fun `a dns query is answered back into the tunnel`() {
        val verdict = TunnelRouter.route(dnsQuery("example.com"))

        val reply = assertIs<TunnelRouter.Verdict.Reply>(verdict)
        val header = assertNotNull(IpPackets.parse(reply.packet))
        assertEquals(client, header.destinationText(), "the answer must go back to whoever asked")
        assertEquals("93.184.216.34", addressIn(IpPackets.payload(reply.packet, header)))
        assertEquals(1, TunnelRouter.answeredMesh)
    }

    @Test
    fun `a blocked name is refused with no such name`() {
        TunnelRouter.blockedHost = { it == "ads.example.com" }

        val refusal = assertIs<TunnelRouter.Verdict.Reply>(TunnelRouter.route(dnsQuery("ads.example.com")))
        val header = assertNotNull(IpPackets.parse(refusal.packet))
        val answer = IpPackets.payload(refusal.packet, header)

        // NXDOMAIN: rcode 3 in the low nibble of the second flag byte, and no answer records. An
        // address record pointing at 0.0.0.0 would also "block" it, and would make every blocked
        // request wait for a connection to nowhere to time out first.
        assertEquals(3, answer[3].toInt() and 0x0F, "the reply must carry rcode 3")
        assertEquals(0, IpPackets.u16(answer, 6), "a refusal must have no answers")
        assertEquals(1, TunnelRouter.refused)
        assertEquals(0, TunnelRouter.answeredMesh, "a refused name must not also be resolved")
    }

    @Test
    fun `a name that is not blocked is not refused`() {
        TunnelRouter.blockedHost = { it == "ads.example.com" }
        assertIs<TunnelRouter.Verdict.Reply>(TunnelRouter.route(dnsQuery("example.com")))
        assertEquals(0, TunnelRouter.refused)
        assertEquals(1, TunnelRouter.answeredMesh)
    }

    @Test
    fun `a whitelisted program gets upstream unfiltered, which is the whole point of a whitelist`() {
        // The blocklist would refuse this name for anybody else.
        TunnelRouter.blockedHost = { true }
        TunnelRouter.bypassOwner = { port -> if (port == 40000) "steam.exe" else null }

        val reply = assertIs<TunnelRouter.Verdict.Reply>(
            TunnelRouter.route(dnsQuery("ads.example.com", sourcePort = 40000)),
        )
        val header = assertNotNull(IpPackets.parse(reply.packet))

        // 1.2.3.4 is what the upstream hook answers, so this proves the query went upstream rather
        // than through the mesh resolver, AND that the blocklist did not get to it first.
        assertEquals("1.2.3.4", addressIn(IpPackets.payload(reply.packet, header)))
        assertTrue(reply.reason.contains("steam.exe"), "the reason should name the program")
        assertEquals(1, TunnelRouter.bypassed)
        assertEquals(0, TunnelRouter.refused, "a whitelisted program must not be filtered")
    }

    @Test
    fun `a program that is not whitelisted is still filtered`() {
        TunnelRouter.blockedHost = { true }
        TunnelRouter.bypassOwner = { port -> if (port == 40000) "steam.exe" else null }

        assertIs<TunnelRouter.Verdict.Reply>(TunnelRouter.route(dnsQuery("ads.example.com", sourcePort = 55555)))
        assertEquals(1, TunnelRouter.refused)
        assertEquals(0, TunnelRouter.bypassed)
    }

    @Test
    fun `an owner lookup that throws tunnels the packet rather than bypassing it`() {
        // The safe direction, and the reason it is worth a test: the tempting way to write this is to
        // let the exception escape, and the second most tempting is to treat a failure as "no policy
        // applies", which grants a bypass to every packet the moment the lookup breaks.
        TunnelRouter.blockedHost = { true }
        TunnelRouter.bypassOwner = { throw IllegalStateException("the connection table is unreadable") }

        assertIs<TunnelRouter.Verdict.Reply>(TunnelRouter.route(dnsQuery("ads.example.com")))
        assertEquals(1, TunnelRouter.refused, "a broken lookup must not become a bypass")
        assertEquals(0, TunnelRouter.bypassed)
    }

    @Test
    fun `a packet to a blocked address goes nowhere`() {
        TunnelRouter.sinkAddresses = setOf(IpPackets.packed("203.0.113.9")!!)

        val verdict = TunnelRouter.route(
            IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes("203.0.113.9")!!, 1234, 443, ByteArray(4)),
        )
        assertIs<TunnelRouter.Verdict.Drop>(verdict)
        assertEquals(1, TunnelRouter.dropped)
    }

    @Test
    fun `mesh subnet traffic is handed to the mesh when something is carrying it`() {
        var carried: String? = null
        TunnelRouter.meshSender = { _, destination -> carried = destination; true }

        val verdict = TunnelRouter.route(
            IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes("10.8.0.7")!!, 1234, 8080, ByteArray(4)),
        )
        val mesh = assertIs<TunnelRouter.Verdict.Mesh>(verdict)
        assertEquals("10.8.0.7", mesh.destination)
        assertEquals(1, TunnelRouter.toMesh)

        // The verdict carries the packet; the runtime is what calls the sender. Asserting the hook is
        // reachable and that the destination is the parsed one, not the raw bytes.
        assertTrue(TunnelRouter.meshSender!!(mesh.packet, mesh.destination))
        assertEquals("10.8.0.7", carried)
    }

    @Test
    fun `mesh subnet traffic is dropped when nothing is carrying it`() {
        TunnelRouter.meshSender = null
        val verdict = TunnelRouter.route(
            IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes("10.8.0.7")!!, 1234, 8080, ByteArray(4)),
        )
        // Dropped, not silently passed to the ordinary connection: 10.8.0.7 is a mesh address and
        // sending it out of the physical interface would leak the fact that this device is on a mesh.
        assertIs<TunnelRouter.Verdict.Drop>(verdict)
    }

    @Test
    fun `traffic on a route this tunnel never claimed is dropped`() {
        // Nothing routes 8.8.8.8 into the adapter, so a packet for it arriving means the routing table
        // is not what Prism set. Dropping is the honest response; forwarding it would make this a
        // general-purpose router with no NAT behind it, and the reply would never come back.
        val verdict = TunnelRouter.route(
            IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes("8.8.8.8")!!, 1234, 443, ByteArray(4)),
        )
        assertIs<TunnelRouter.Verdict.Drop>(verdict)
    }

    @Test
    fun `a dns query too short to have a header is dropped, not answered`() {
        val verdict = TunnelRouter.route(
            IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes(resolver)!!, 1234, 53, byteArrayOf(1, 2)),
        )
        assertIs<TunnelRouter.Verdict.Drop>(verdict)
        assertEquals(1, TunnelRouter.malformed)
    }

    @Test
    fun `a resolver that answers nothing produces a drop rather than an empty reply`() {
        TunnelRouter.resolve = { null }
        // An empty UDP reply would tell the client "here is your answer, it is nothing", which some
        // resolvers cache. A drop is a timeout, which they retry.
        assertIs<TunnelRouter.Verdict.Drop>(TunnelRouter.route(dnsQuery("example.com")))
    }

    @Test
    fun `no data keeps the name alive while saying it has no address of that kind`() {
        val query = dnsPayload("mesh.p2p", type = 28)
        val answer = TunnelRouter.noData(query)

        assertEquals(0, answer[3].toInt() and 0x0F, "NODATA is a success, not an error")
        assertEquals(0, IpPackets.u16(answer, 6), "and has no answer records")
        assertEquals(IpPackets.u16(query, 0), IpPackets.u16(answer, 0), "the id must be echoed")
    }

    @Test
    fun `counters add up across a mixed run`() {
        TunnelRouter.blockedHost = { it.startsWith("ads.") }
        TunnelRouter.bypassOwner = { port -> if (port == 40000) "steam.exe" else null }
        TunnelRouter.meshSender = { _, _ -> true }

        TunnelRouter.route(dnsQuery("example.com"))
        TunnelRouter.route(dnsQuery("ads.example.com"))
        TunnelRouter.route(dnsQuery("anything.com", sourcePort = 40000))
        TunnelRouter.route(IpPackets.udp(IpPackets.bytes(client)!!, IpPackets.bytes("10.8.0.2")!!, 1, 80, ByteArray(2)))
        TunnelRouter.route(ByteArray(8))

        assertEquals(5, TunnelRouter.seen)
        assertEquals(1, TunnelRouter.answeredMesh)
        assertEquals(1, TunnelRouter.refused)
        assertEquals(1, TunnelRouter.bypassed)
        assertEquals(1, TunnelRouter.toMesh)
        assertEquals(1, TunnelRouter.malformed)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** A whole IPv4/UDP DNS query for [name], as it would arrive off the adapter. */
    private fun dnsQuery(name: String, sourcePort: Int = 51234): ByteArray = IpPackets.udp(
        source = IpPackets.bytes(client)!!,
        destination = IpPackets.bytes(resolver)!!,
        sourcePort = sourcePort,
        destinationPort = 53,
        payload = dnsPayload(name),
    )

    /** The DNS message alone: a header, then one question. */
    private fun dnsPayload(name: String, type: Int = 1): ByteArray {
        val labels = name.split(".")
        val body = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        IpPackets.writeU16(body, 0, 0x1234)      // transaction id
        body[2] = 0x01                            // standard query, recursion desired
        IpPackets.writeU16(body, 4, 1)            // one question
        var at = 12
        labels.forEach { label ->
            body[at++] = label.length.toByte()
            label.forEach { character -> body[at++] = character.code.toByte() }
        }
        body[at++] = 0
        IpPackets.writeU16(body, at, type); at += 2
        IpPackets.writeU16(body, at, 1)           // class IN
        return body
    }

    /** An A-record answer for whatever was asked, built the way a resolver would. */
    private fun answerWith(query: ByteArray, address: String): ByteArray {
        var index = 12
        while (index < query.size && query[index] != 0.toByte()) index += 1 + (query[index].toInt() and 0xFF)
        val questionEnd = index + 5
        val reply = ByteArray(questionEnd + 16)
        query.copyInto(reply, 0, 0, questionEnd)
        reply[2] = 0x81.toByte(); reply[3] = 0x80.toByte()
        IpPackets.writeU16(reply, 6, 1)
        var out = questionEnd
        reply[out++] = 0xC0.toByte(); reply[out++] = 0x0C
        IpPackets.writeU16(reply, out, 1); out += 2       // type A
        IpPackets.writeU16(reply, out, 1); out += 2       // class IN
        out += 4                                          // a zero TTL is legal and means do not cache
        IpPackets.writeU16(reply, out, 4); out += 2
        IpPackets.bytes(address)!!.copyInto(reply, out)
        return reply
    }

    /** The address in an A-record answer, for asserting WHICH resolver produced it. */
    private fun addressIn(answer: ByteArray): String? {
        if (answer.size < 12 || IpPackets.u16(answer, 6) == 0) return null
        var index = 12
        while (index < answer.size && answer[index] != 0.toByte()) index += 1 + (answer[index].toInt() and 0xFF)
        var at = index + 5                                // the question's terminator, type and class
        at += 2 + 2 + 2 + 4                               // the answer's name pointer, type, class, TTL
        val length = IpPackets.u16(answer, at); at += 2
        if (length != 4 || at + 4 > answer.size) return null
        return IpPackets.text(answer.copyOfRange(at, at + 4))
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected " + T::class.simpleName + " but got " + value)
        return value as T
    }
}
