package com.prism.core

/**
 * Reading and writing the IPv4 packets that come off a TUN adapter. PHASES 58, 59, 63.
 *
 * ## Why this exists rather than a library
 *
 * A TUN adapter hands over raw layer-3 frames, and the JDK has no type for one. The alternatives are
 * a packet library -- pcap4j and friends, all of which want a native capture backend Prism does not
 * ship -- or the twenty bytes of arithmetic below. Prism needs to look at four fields and build one
 * kind of reply, so the arithmetic wins.
 *
 * ## What is deliberately NOT here
 *
 * IPv6, fragment reassembly and TCP state. Not laziness in any of the three cases:
 *
 *  - IPV6 IS NOT ROUTED INTO THE TUNNEL AT ALL, so a v6 packet arriving would be a bug elsewhere.
 *    Prism's mesh addresses are v4 and its DNS answers are A records; adding half-working v6 here
 *    would mean v6 traffic that enters the tunnel and silently goes nowhere, which is worse for the
 *    user than v6 that never enters it and keeps working over the ordinary connection.
 *  - FRAGMENTS ARE DROPPED rather than reassembled. The only thing this tunnel inspects is DNS over
 *    UDP, and a fragmented DNS query is so far outside normal operation that treating it as hostile
 *    is more defensible than buffering attacker-controlled fragments in memory.
 *  - TCP IS NOT TERMINATED. Nothing here needs to; see TunnelRouter for what happens to a TCP packet
 *    and why that is the whole story.
 *
 * ## The checksum, and the one that is missing
 *
 * The IPv4 header checksum is computed because a kernel will discard a packet without one. The UDP
 * checksum is written as ZERO, which is not a shortcut: over IPv4 a zero UDP checksum means "not
 * computed" and is legal, every stack accepts it, and the frame never leaves this machine -- it goes
 * from Prism into the local TUN and straight back up the local stack, with no link in between that
 * could corrupt it.
 */
object IpPackets {

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    /** The fields Prism actually looks at. */
    data class Ipv4(
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val source: ByteArray,
        val destination: ByteArray,
        /** Source port for TCP and UDP, or -1. */
        val sourcePort: Int,
        /** Destination port for TCP and UDP, or -1. */
        val destinationPort: Int,
        /** Where the payload after the transport header begins, or -1 when there is no transport. */
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        val isUdp: Boolean get() = protocol == PROTOCOL_UDP
        val isTcp: Boolean get() = protocol == PROTOCOL_TCP

        fun sourceText(): String = text(source)
        fun destinationText(): String = text(destination)

        /** The destination as one comparable int, which is cheaper than a string per packet. */
        fun destinationPacked(): Int = packed(destination)

        /** True when the destination is inside the /24 whose first three octets are given. */
        fun destinationIn24(a: Int, b: Int, c: Int): Boolean =
            (destination[0].toInt() and 0xFF) == a &&
                (destination[1].toInt() and 0xFF) == b &&
                (destination[2].toInt() and 0xFF) == c
    }

    /**
     * Parses a frame, or returns null when it is not something this tunnel handles.
     *
     * Null covers every rejection -- not IPv4, truncated, a fragment, a header that claims a length
     * the buffer does not have. One return value for all of them because the caller does the same
     * thing with each: drop it and count it. Distinguishing them would mean a caller that logs a
     * reason per packet, and a log line per packet on a busy tunnel is a denial of service against
     * the person running it.
     */
    fun parse(packet: ByteArray, length: Int = packet.size): Ipv4? {
        if (length < 20 || length > packet.size) return null
        if ((packet[0].toInt() ushr 4) != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || headerLength > length) return null

        val totalLength = u16(packet, 2)
        if (totalLength < headerLength || totalLength > length) return null

        // A fragment: either the more-fragments bit, or a non-zero offset. See the class comment.
        val fragment = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        val moreFragments = (packet[6].toInt() and 0x20) != 0
        if (fragment != 0 || moreFragments) return null

        val protocol = packet[9].toInt() and 0xFF
        val source = packet.copyOfRange(12, 16)
        val destination = packet.copyOfRange(16, 20)

        var sourcePort = -1
        var destinationPort = -1
        var payloadOffset = -1
        var payloadLength = 0

        when (protocol) {
            PROTOCOL_UDP -> {
                if (headerLength + 8 > totalLength) return null
                sourcePort = u16(packet, headerLength)
                destinationPort = u16(packet, headerLength + 2)
                // The UDP length field covers the header and the payload. Trusted only as far as the
                // IP total length allows, because it is the sender's claim about a buffer we hold.
                val udpLength = u16(packet, headerLength + 4).coerceAtMost(totalLength - headerLength)
                payloadOffset = headerLength + 8
                payloadLength = (udpLength - 8).coerceAtLeast(0)
            }

            PROTOCOL_TCP -> {
                if (headerLength + 20 > totalLength) return null
                sourcePort = u16(packet, headerLength)
                destinationPort = u16(packet, headerLength + 2)
                val dataOffset = ((packet[headerLength + 12].toInt() and 0xF0) ushr 4) * 4
                if (dataOffset < 20 || headerLength + dataOffset > totalLength) return null
                payloadOffset = headerLength + dataOffset
                payloadLength = totalLength - payloadOffset
            }
        }

        return Ipv4(
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = protocol,
            source = source,
            destination = destination,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
        )
    }

    /** The bytes of a parsed packet's transport payload. */
    fun payload(packet: ByteArray, header: Ipv4): ByteArray {
        if (header.payloadOffset < 0 || header.payloadLength <= 0) return ByteArray(0)
        val end = (header.payloadOffset + header.payloadLength).coerceAtMost(packet.size)
        if (end <= header.payloadOffset) return ByteArray(0)
        return packet.copyOfRange(header.payloadOffset, end)
    }

    /**
     * Builds a UDP datagram to be written back into the TUN.
     *
     * NOTE THE ARGUMENT ORDER AT THE CALL SITE. A reply swaps both ends: the source is the address
     * the query was sent TO and the destination is where it came FROM. Getting this backwards
     * produces a packet the local stack ignores without complaint, which is a miserable thing to
     * debug, so [udpReply] exists to do the swap for the caller.
     */
    fun udp(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45                       // version 4, 20-byte header
        packet[1] = 0                          // no differentiated services
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, 0)                 // identification: unused, this is never fragmented
        packet[6] = 0x40                       // the do-not-fragment bit
        packet[7] = 0
        packet[8] = 64                         // time to live
        packet[9] = PROTOCOL_UDP.toByte()
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)

        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, destinationPort)
        writeU16(packet, 24, udpLength)
        writeU16(packet, 26, 0)                // checksum: see the class comment
        payload.copyInto(packet, 28)

        writeU16(packet, 10, headerChecksum(packet))
        return packet
    }

    /** A UDP reply to a parsed query, with both ends swapped for you. */
    fun udpReply(query: Ipv4, payload: ByteArray): ByteArray = udp(
        source = query.destination,
        destination = query.source,
        sourcePort = query.destinationPort,
        destinationPort = query.sourcePort,
        payload = payload,
    )

    /**
     * The ones-complement checksum of a 20-byte IPv4 header.
     *
     * The field itself is skipped rather than zeroed, so the packet can be checksummed in place
     * without a copy and without the caller having to remember to clear it first.
     */
    fun headerChecksum(packet: ByteArray): Int {
        var sum = 0
        var index = 0
        while (index < 20) {
            if (index == 10) { index += 2; continue }
            sum += ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
            index += 2
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    /** Whether a header's own checksum is correct. Used by the tests, not by the hot path. */
    fun checksumValid(packet: ByteArray): Boolean =
        packet.size >= 20 && u16(packet, 10) == headerChecksum(packet)

    fun packed(address: ByteArray): Int =
        ((address[0].toInt() and 0xFF) shl 24) or
            ((address[1].toInt() and 0xFF) shl 16) or
            ((address[2].toInt() and 0xFF) shl 8) or
            (address[3].toInt() and 0xFF)

    fun packed(address: String): Int? {
        val parts = address.trim().split(".")
        if (parts.size != 4) return null
        var value = 0
        parts.forEach { part ->
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    fun bytes(address: String): ByteArray? {
        val value = packed(address) ?: return null
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    fun text(address: ByteArray): String = buildString {
        address.forEachIndexed { index, byte ->
            if (index > 0) append('.')
            append(byte.toInt() and 0xFF)
        }
    }

    fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
