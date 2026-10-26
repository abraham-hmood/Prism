package com.prism.launcher.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * An L2TPv2 control-connection responder.
 *
 * ## What was wrong before
 *
 * The old code read the Message Type out of fixed offsets (18/19 for the type, 21 for the value)
 * and took the tunnel id from bytes 6-7. Both are off: L2TP control messages carry a 12-byte
 * header whose tunnel id is at bytes 4-5 (6-7 is the *session* id), and AVPs start at byte 12 with
 * a 6-byte header of their own -- flags/length, vendor id, then attribute type. Reading a fixed
 * offset happens to work only if the first AVP is exactly the one expected, and real clients put
 * the Message Type AVP first often enough that the bug looked like "it half works". Then the
 * response was also malformed: its length field said 40 while the AVPs ended at 20, and the AVP
 * length was written as 8 but with the value bytes in the wrong place.
 *
 * ## What this does now
 *
 * Parses the header and walks the AVPs properly, then answers the control sequence a Windows or
 * Android L2TP client actually sends: SCCRQ -> SCCRP, SCCCN, ICRQ -> ICRP, ICCN, StopCCN. It keeps
 * per-tunnel sequence numbers and assigned ids, so the client's state machine advances instead of
 * retransmitting into a void.
 *
 * ## What it does not do
 *
 * The data path. Once the tunnel is up, L2TP carries PPP frames inside the tunnel and needs LCP,
 * CHAP/MS-CHAPv2 and IPCP to negotiate a link. None of that is implemented, so a client will
 * connect the control connection and then fail PPP authentication with a clear reason on its side.
 * That is strictly better than a responder that cannot even agree a tunnel, and it is written down
 * rather than implied: the README lists L2TP as control-plane only.
 */
class L2tpEngine(
    private val psk: String,
    private val user: String,
    private val pass: String,
) {

    private data class Tunnel(
        val remoteTunnelId: Int,
        val localTunnelId: Int,
        var nextSendSequence: Int = 0,
        var lastReceivedSequence: Int = -1,
        var established: Boolean = false,
    )

    private val tunnels = ConcurrentHashMap<Int, Tunnel>()

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        if (data.size < 12) return
        val control = (data[0].toInt() and 0x80) != 0
        if (!control) return // data messages need PPP, which is not implemented

        val length = readU16(data, 2)
        if (length < 12 || length > data.size) return

        val tunnelId = readU16(data, 4)
        val sessionId = readU16(data, 6)
        val ns = readU16(data, 8)
        val nr = readU16(data, 10)

        // A zero-length body is a ZLB acknowledgement: it carries no AVPs and must not be answered,
        // or two devices acknowledge each other's acknowledgements forever.
        val avps = parseAvps(data, 12, length)
        if (avps.isEmpty()) return

        val messageType = avps.firstOrNull { it.vendor == 0 && it.type == AVP_MESSAGE_TYPE }
            ?.let { readU16(it.value, 0) } ?: return

        when (messageType) {
            MSG_SCCRQ -> handleSccrq(tunnelId, ns, address, port, socket, avps)
            MSG_SCCCN -> tunnels[tunnelId]?.let {
                it.established = true
                it.lastReceivedSequence = ns
                android.util.Log.i(TAG, "L2TP tunnel $tunnelId established for ${address.hostAddress}")
            }
            MSG_ICRQ -> handleIcrq(tunnelId, sessionId, ns, address, port, socket)
            MSG_ICCN -> {
                tunnels[tunnelId]?.lastReceivedSequence = ns
                android.util.Log.i(TAG, "L2TP session $sessionId up inside tunnel $tunnelId")
            }
            MSG_STOPCCN -> {
                tunnels.remove(tunnelId)
                android.util.Log.i(TAG, "L2TP tunnel $tunnelId closed by ${address.hostAddress}")
            }
            MSG_HELLO -> tunnels[tunnelId]?.lastReceivedSequence = ns
            else -> android.util.Log.d(TAG, "L2TP message $messageType for tunnel $tunnelId ignored (nr=$nr)")
        }
    }

    private fun handleSccrq(
        remoteTunnelId: Int,
        ns: Int,
        address: InetAddress,
        port: Int,
        socket: DatagramSocket,
        avps: List<Avp>,
    ) {
        val hostName = avps.firstOrNull { it.vendor == 0 && it.type == AVP_HOST_NAME }
            ?.let { String(it.value, Charsets.UTF_8) }
            .orEmpty()
        android.util.Log.i(TAG, "L2TP SCCRQ from $address (${hostName.ifBlank { "unnamed" }})")

        val localId = nextTunnelId()
        val tunnel = Tunnel(remoteTunnelId = remoteTunnelId, localTunnelId = localId)
        // Keyed by OUR id, because that is what the peer puts in the header of everything it sends
        // after this: a tunnel id on the wire names the RECEIVER's tunnel, not the sender's.
        tunnels[localId] = tunnel

        val body = buildList {
            add(avp(AVP_MESSAGE_TYPE, u16(MSG_SCCRP)))
            add(avp(AVP_PROTOCOL_VERSION, u16(0x0100))) // L2TPv2, revision 0
            add(avp(AVP_HOST_NAME, "Prism".toByteArray()))
            add(avp(AVP_FRAMING_CAPABILITIES, u32(FRAMING_CAPABILITY_SYNC)))
        }.reduce { acc, bytes -> acc + bytes }

        sendControl(socket, address, port, remoteTunnelId, 0, tunnel, MSG_SCCRP, body, ns)
    }

    private fun handleIcrq(
        tunnelId: Int,
        remoteSessionId: Int,
        ns: Int,
        address: InetAddress,
        port: Int,
        socket: DatagramSocket,
    ) {
        val tunnel = tunnels[tunnelId] ?: return
        val localSession = nextSessionId()
        val body = buildList {
            add(avp(AVP_MESSAGE_TYPE, u16(MSG_ICRP)))
            add(avp(AVP_ASSIGNED_SESSION_ID, u16(localSession)))
        }.reduce { acc, bytes -> acc + bytes }
        sendControl(socket, address, port, tunnel.remoteTunnelId, localSession, tunnel, MSG_ICRP, body, ns)
        android.util.Log.i(TAG, "L2TP session $localSession requested for peer session $remoteSessionId")
    }

    /**
     * Writes a control message.
     *
     * The length field counts the header and every AVP, which is what the previous version got
     * wrong: it declared 40 bytes while writing 20, so a strict client discarded every reply.
     */
    private fun sendControl(
        socket: DatagramSocket,
        address: InetAddress,
        port: Int,
        tunnelId: Int,
        sessionId: Int,
        tunnel: Tunnel,
        messageType: Int,
        body: ByteArray,
        receivedSequence: Int,
    ) {
        val total = 12 + body.size
        val packet = ByteArray(total)
        packet[0] = 0xC8.toByte()   // T + L set, version 2
        packet[1] = 0x02
        writeU16(packet, 2, total)
        writeU16(packet, 4, tunnelId)
        writeU16(packet, 6, sessionId)
        writeU16(packet, 8, tunnel.nextSendSequence++)
        writeU16(packet, 10, receivedSequence + 1)
        body.copyInto(packet, 12)
        tunnel.lastReceivedSequence = receivedSequence

        runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }
            .onFailure { android.util.Log.e(TAG, "Could not send L2TP response to $address:$port", it) }
    }

    // ── AVP plumbing ───────────────────────────────────────────────────────

    private class Avp(val vendor: Int, val type: Int, val value: ByteArray)

    /**
     * Walks the AVP list.
     *
     * A length of zero or a length that runs past the message ends the walk rather than looping:
     * the old fixed offsets had no way to notice a malformed packet, and neither would a naive
     * `while (offset < end)`.
     */
    private fun parseAvps(data: ByteArray, start: Int, end: Int): List<Avp> {
        val out = ArrayList<Avp>()
        var offset = start
        while (offset + 6 <= end) {
            val flagsAndLength = readU16(data, offset)
            val length = flagsAndLength and 0x03FF
            if (length < 6 || offset + length > end) break
            val vendor = readU16(data, offset + 2)
            val type = readU16(data, offset + 4)
            out.add(Avp(vendor, type, data.copyOfRange(offset + 6, offset + length)))
            offset += length
        }
        return out
    }

    private fun avp(type: Int, value: ByteArray): ByteArray {
        val length = 6 + value.size
        val out = ByteArray(length)
        // Mandatory bit set, no hidden/vendor bits, length in the low ten bits.
        writeU16(out, 0, 0x8000 or length)
        writeU16(out, 2, 0)
        writeU16(out, 4, type)
        value.copyInto(out, 6)
        return out
    }

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun u32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private var tunnelCounter = 1
    private var sessionCounter = 1

    private fun nextTunnelId(): Int = tunnelCounter++
    private fun nextSessionId(): Int = sessionCounter++

    private companion object {
        const val TAG = "PrismL2tp"

        const val AVP_MESSAGE_TYPE = 0
        const val AVP_PROTOCOL_VERSION = 2
        const val AVP_HOST_NAME = 7
        const val AVP_FRAMING_CAPABILITIES = 3
        const val AVP_ASSIGNED_SESSION_ID = 14

        const val FRAMING_CAPABILITY_SYNC = 1

        const val MSG_SCCRQ = 1
        const val MSG_SCCRP = 2
        const val MSG_SCCCN = 3
        const val MSG_STOPCCN = 4
        const val MSG_HELLO = 6
        const val MSG_ICRQ = 10
        const val MSG_ICRP = 11
        const val MSG_ICCN = 12
    }
}
