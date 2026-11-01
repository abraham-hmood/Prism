package com.prism.launcher.stremio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Just enough of BitTorrent's distributed hash table to answer "who has this torrent?".
 *
 * ## Why a DHT and not a tracker
 *
 * A Stremio stream with an `infoHash` and nothing else has no tracker to ask. The hash IS the
 * address: the DHT is a network of nodes that each hold a slice of the keyspace near their own id,
 * and asking the nodes closest to an info hash returns the peers that announced it. Without this,
 * every magnet-style stream from every add-on is unplayable -- the single most common shape of a
 * Stremio stream source.
 *
 * ## What this deliberately is not
 *
 * Not a routing table that lives beyond one request, not a server, and not a good citizen in the
 * seeding sense: it queries and never announces. A full DHT node maintains buckets, refreshes
 * them, and accepts inbound queries; that is a background service with an attack surface, and this
 * client needs a list of peers for a film somebody pressed play on. The search is iterative from
 * the public bootstrap routers and stops at a deadline, so it costs a handful of UDP packets and
 * no persistent state.
 */
internal object Dht {

    private val BOOTSTRAP = listOf(
        "router.bittorrent.com" to 6881,
        "dht.transmissionbt.com" to 6881,
        "router.utorrent.com" to 6881,
        "dht.libtorrent.org" to 25401,
    )

    private const val MAX_QUERIES = 160
    private const val MAX_NODES = 800
    private const val MAX_PEERS = 60

    /**
     * Finds peers for [infoHash].
     *
     * Blocking, bounded by [deadlineMs], and safe to call with no network: an unreachable bootstrap
     * router costs one UDP timeout and the rest of the list is still tried.
     */
    fun findPeers(infoHash: ByteArray, deadlineMs: Long = 12_000, target: Int = 40): List<InetSocketAddress> {
        if (infoHash.size != 20) return emptyList()

        val peers = LinkedHashSet<InetSocketAddress>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<InetSocketAddress>()
        val ourId = Random.nextBytes(20)
        val deadline = System.currentTimeMillis() + deadlineMs

        BOOTSTRAP.forEach { (host, port) ->
            runCatching {
                InetAddress.getAllByName(host).forEach { queue.add(InetSocketAddress(it, port)) }
            }
        }

        var queries = 0
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_500
            while (queue.isNotEmpty() && queries < MAX_QUERIES && peers.size < minOf(target, MAX_PEERS) &&
                System.currentTimeMillis() < deadline
            ) {
                val node = queue.removeFirst()
                val key = node.hostString + ":" + node.port
                if (!visited.add(key)) continue
                if (visited.size > MAX_NODES) break

                val query = mapOf("info_hash" to infoHash)
                val response = query(socket, node, "get_peers", query, ourId) ?: continue
                queries++

                (response["values"] as? List<*>)?.forEach { entry ->
                    if (entry is ByteArray) peers.addAll(parseCompactPeers(entry))
                }
                (response["nodes"] as? ByteArray)?.let { compact ->
                    parseCompactNodes(compact).forEach { candidate ->
                        val candidateKey = candidate.hostString + ":" + candidate.port
                        if (candidateKey !in visited) queue.add(candidate)
                    }
                }
                if (peers.size >= target) break
            }
        }

        return peers.toList()
    }

    /**
     * Sends one KRPC query and waits for its answer.
     *
     * The transaction id is how a reply is matched to a request: the socket receives responses
     * from every node being queried in parallel, and a reply to the previous question arriving late
     * must not be mistaken for one to this. Anything that does not match is dropped, which is also
     * why the buffer is a fixed 2 KiB: no legitimate DHT reply is larger, and a stranger cannot
     * make this allocate.
     */
    private fun query(
        socket: DatagramSocket,
        node: InetSocketAddress,
        method: String,
        arguments: Map<String, Any>,
        ourId: ByteArray,
    ): Map<*, *>? {
        val transaction = ByteArray(2) { Random.nextInt(0, 256).toByte() }
        val message = LinkedHashMap<String, Any>().apply {
            put("t", transaction)
            put("y", "q")
            put("q", method)
            put("a", LinkedHashMap<String, Any>().apply {
                put("id", ourId)
                putAll(arguments)
            })
        }

        val payload = runCatching { Bencode.encode(message) }.getOrNull() ?: return null
        try {
            socket.send(DatagramPacket(payload, payload.size, node))
        } catch (_: Exception) {
            return null
        }

        val deadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < deadline) {
            val buffer = ByteArray(2 * 1024)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return null
            }
            val parsed = runCatching {
                Bencode.decode(buffer.copyOfRange(0, packet.length)) as? Map<*, *>
            }.getOrNull() ?: continue
            val replyTransaction = parsed["t"] as? ByteArray ?: continue
            if (!replyTransaction.contentEquals(transaction)) continue
            if (Bencode.string(parsed["y"]) != "r") continue
            return parsed["r"] as? Map<*, *>
        }
        return null
    }

    /** Compact peer info is six bytes: IPv4 in network order, then a big-endian port. */
    private fun parseCompactPeers(bytes: ByteArray): List<InetSocketAddress> {
        if (bytes.size < 6 || bytes.size % 6 != 0) return emptyList()
        return (0 until bytes.size / 6).mapNotNull { index ->
            val at = index * 6
            val address = runCatching {
                InetAddress.getByAddress(bytes.copyOfRange(at, at + 4))
            }.getOrNull() ?: return@mapNotNull null
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            InetSocketAddress(address, port)
        }
    }

    /** Compact node info is twenty bytes of id followed by six bytes of address and port. */
    private fun parseCompactNodes(bytes: ByteArray): List<InetSocketAddress> {
        if (bytes.size < 26) return emptyList()
        return (0 until bytes.size / 26).mapNotNull { index ->
            val at = index * 26 + 20
            val address = runCatching {
                InetAddress.getByAddress(bytes.copyOfRange(at, at + 4))
            }.getOrNull() ?: return@mapNotNull null
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            InetSocketAddress(address, port)
        }
    }
}
