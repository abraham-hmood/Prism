package com.prism.launcher.stremio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.random.Random

/**
 * Tracker announces, HTTP and UDP.
 *
 * ## Why both
 *
 * Trackers come in two transports and the ecosystem never settled on one. HTTP trackers answer a
 * query string with a bencoded list of peers; UDP trackers (BEP 15) answer a binary packet, chosen
 * because a UDP announce costs two packets and no TLS handshake. A client that speaks only one of
 * them loses whichever half of a torrent's tracker list uses the other, and for an unpopular
 * torrent that is the difference between finding a peer and not.
 *
 * ## Announce, not scrape
 *
 * Prism announces itself as a leecher with `left` set to the whole torrent and never handles
 * inbound connections. The port in the announce is therefore not a promise that anything is
 * listening there; it is required by the protocol and ignored by every tracker worth using.
 */
internal object Trackers {

    private const val TIMEOUT_MS = 4_000
    private const val ANNOUNCE_PORT = 6881

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /**
     * Asks every tracker that speaks a transport this understands.
     *
     * Trackers are tried in order and the first few are enough: a torrent with forty trackers in
     * its list does not need forty announces, and doing them all would make pressing play wait on
     * the tail of the list. The deadline bounds the worst case regardless.
     */
    fun announce(
        trackers: List<String>,
        infoHash: ByteArray,
        peerId: ByteArray,
        deadlineMs: Long = 10_000,
    ): List<InetSocketAddress> {
        if (infoHash.size != 20 || peerId.size != 20) return emptyList()
        val peers = LinkedHashSet<InetSocketAddress>()
        val deadline = System.currentTimeMillis() + deadlineMs

        val candidates = trackers
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("udp://", true) }
            .distinct()
            .take(8)

        for (url in candidates) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            val found = runCatching {
                if (url.startsWith("udp://", true)) udpAnnounce(url, infoHash, peerId, remaining)
                else httpAnnounce(url, infoHash, peerId, remaining)
            }.getOrDefault(emptyList())
            peers.addAll(found)
        }

        return peers.toList()
    }

    /**
     * BEP 3: a GET with the info hash percent-encoded BYTE BY BYTE.
     *
     * This is the detail that breaks naive clients. The hash is twenty arbitrary bytes, not text;
     * encoding it as UTF-8 or as a hex string produces a tracker that answers "no peers" forever.
     * Every byte that is not unreserved is escaped as `%XX`.
     */
    private fun httpAnnounce(
        url: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        timeoutMs: Long,
    ): List<InetSocketAddress> {
        val base = url.toHttpUrlOrNull() ?: return emptyList()
        val builder = base.newBuilder()
        builder.addEncodedQueryParameter("info_hash", percentEncode(infoHash))
        builder.addEncodedQueryParameter("peer_id", percentEncode(peerId))
        builder.addEncodedQueryParameter("port", ANNOUNCE_PORT.toString())
        builder.addEncodedQueryParameter("uploaded", "0")
        builder.addEncodedQueryParameter("downloaded", "0")
        builder.addEncodedQueryParameter("left", "0")
        builder.addEncodedQueryParameter("compact", "1")
        builder.addEncodedQueryParameter("numwant", "50")
        builder.addEncodedQueryParameter("event", "started")

        val request = okhttp3.Request.Builder()
            .url(builder.build())
            .header("User-Agent", "Prism/1.0")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        val call = client.newCall(request)
        // The announce's own budget, not the client's default: with eight trackers in a list, a
        // four-second timeout each would spend half a minute before the first frame.
        call.timeout().timeout(timeoutMs.coerceAtLeast(500L), java.util.concurrent.TimeUnit.MILLISECONDS)

        val bytes = call.execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.bytes() ?: return emptyList()
        }
        val root = runCatching { Bencode.decode(bytes) as? Map<*, *> }.getOrNull() ?: return emptyList()
        val out = ArrayList<InetSocketAddress>()
        (root["peers"] as? ByteArray)?.let { out.addAll(compactPeers(it)) }
        (root["peers6"] as? ByteArray)?.let { out.addAll(compactPeersV6(it)) }
        return out
    }

    /**
     * BEP 15: connect, then announce.
     *
     * The connect step exists to stop source-address spoofing: the tracker hands back a connection
     * id tied to this socket's address, and only that id can announce. The transaction ids are
     * checked on both replies because a stray packet from a previous query is otherwise
     * indistinguishable from the answer being waited for.
     */
    private fun udpAnnounce(
        url: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        timeoutMs: Long,
    ): List<InetSocketAddress> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host ?: return emptyList()
        val port = if (uri.port > 0) uri.port else 6969
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return emptyList()

        DatagramSocket().use { socket ->
            // Half the budget per packet: a connect and an announce are two round trips, and one
            // silent tracker must not consume the whole list's time.
            socket.soTimeout = (timeoutMs / 2).coerceIn(400L, TIMEOUT_MS.toLong()).toInt()

            val connectTransaction = Random.nextInt()
            val connect = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(0x41727101980L)
                putInt(0)                       // action: connect
                putInt(connectTransaction)
            }.array()
            socket.send(DatagramPacket(connect, connect.size, address, port))

            val connectReply = receive(socket, 32) ?: return emptyList()
            val connectBuffer = ByteBuffer.wrap(connectReply).order(ByteOrder.BIG_ENDIAN)
            if (connectBuffer.int != 0 || connectBuffer.int != connectTransaction) return emptyList()
            val connectionId = connectBuffer.long

            val announceTransaction = Random.nextInt()
            val announce = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1)                       // action: announce
                putInt(announceTransaction)
                put(infoHash)
                put(peerId)
                putLong(0L)                     // downloaded
                putLong(0L)                     // left -- unknown before metadata, and zero is accepted
                putLong(0L)                     // uploaded
                putInt(2)                       // event: started
                putInt(0)                       // ip: let the tracker use the packet's source
                putInt(Random.nextInt())
                putInt(-1)                      // numwant: as many as the tracker will give
                putShort(ANNOUNCE_PORT.toShort())
            }.array()
            socket.send(DatagramPacket(announce, announce.size, address, port))

            val reply = receive(socket, 2_048) ?: return emptyList()
            if (reply.size < 20) return emptyList()
            val buffer = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != 1 || buffer.int != announceTransaction) return emptyList()
            buffer.int                             // interval
            buffer.int                             // leechers
            buffer.int                             // seeders
            val peerBytes = ByteArray(reply.size - 20)
            buffer.get(peerBytes)
            return compactPeers(peerBytes)
        }
    }

    private fun receive(socket: DatagramSocket, max: Int): ByteArray? {
        val buffer = ByteArray(max)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            buffer.copyOfRange(0, packet.length)
        } catch (_: Exception) {
            null
        }
    }

    private fun compactPeers(bytes: ByteArray): List<InetSocketAddress> {
        if (bytes.isEmpty() || bytes.size % 6 != 0) return emptyList()
        return (0 until bytes.size / 6).mapNotNull { index ->
            val at = index * 6
            val address = runCatching {
                InetAddress.getByAddress(bytes.copyOfRange(at, at + 4))
            }.getOrNull() ?: return@mapNotNull null
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            InetSocketAddress(address, port)
        }
    }

    private fun compactPeersV6(bytes: ByteArray): List<InetSocketAddress> {
        if (bytes.isEmpty() || bytes.size % 18 != 0) return emptyList()
        return (0 until bytes.size / 18).mapNotNull { index ->
            val at = index * 18
            val address = runCatching {
                InetAddress.getByAddress(bytes.copyOfRange(at, at + 16))
            }.getOrNull() ?: return@mapNotNull null
            val port = ((bytes[at + 16].toInt() and 0xFF) shl 8) or (bytes[at + 17].toInt() and 0xFF)
            InetSocketAddress(address, port)
        }
    }

    private fun percentEncode(bytes: ByteArray): String = buildString {
        bytes.forEach { byte ->
            append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
