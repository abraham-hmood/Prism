package com.prism.launcher.stremio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One peer, spoken to directly.
 *
 * ## The protocol, reduced to what streaming needs
 *
 * A peer connection is a handshake and then length-prefixed messages. Prism needs four of them:
 * interested/unchoke, request/piece, and the extension protocol that carries a torrent's metadata
 * before the torrent itself is known. Everything else -- bitfields, have lists, cancel, port
 * messages -- is read and discarded, because this client is not seeding and is not maintaining a
 * swarm.
 *
 * ## No encryption
 *
 * The plain BitTorrent handshake only. A peer that insists on MSE/PE encryption is skipped rather
 * than emulated; adding it would mean a Diffie-Hellman exchange and RC4 per connection for a
 * feature most public peers do not require. When every peer is encrypted-only the session says it
 * found no peers that answered, which is truthful.
 */
internal class TorrentPeer(
    private val address: InetSocketAddress,
    private val infoHash: ByteArray,
    private val clientId: ByteArray,
) : Closeable {

    private val monitor = Object()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var closed = false

    /** What the peer called us for its extension messages. Needed to address metadata replies. */
    private var remoteMetadataId: Int = -1
    private var remoteMetadataSize: Int = -1

    /**
     * Whether the peer has left us unchoked.
     *
     * IT SENDS UNCHOKE ONCE, not once per piece. Waiting for a fresh one before every piece on the
     * same connection is a fifteen-second stall followed by a failure, on every piece after the
     * first -- which is why this is remembered rather than re-asked.
     */
    private var unchoked = false

    val endpoint: String get() = address.hostString + ":" + address.port

    val supportsMetaData: Boolean get() = remoteMetadataId > 0 && remoteMetadataSize > 0

    fun connect(timeoutMs: Int = 8_000): Boolean {
        val opened = Socket()
        opened.connect(address, timeoutMs)
        opened.soTimeout = SOCKET_TIMEOUT_MS
        opened.tcpNoDelay = true
        socket = opened
        input = DataInputStream(BufferedInputStream(opened.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(opened.getOutputStream()))
        return handshake()
    }

    private fun handshake(): Boolean {
        val out = output ?: return false
        val incoming = input ?: return false

        out.writeByte(19)
        out.write("BitTorrent protocol".toByteArray(Charsets.ISO_8859_1))
        val reserved = ByteArray(8)
        // BEP 10, bit 20 of the reserved area: the extension protocol. Metadata exchange needs it,
        // and so does knowing which pieces a peer has before its first bitfield arrives.
        reserved[5] = 0x10
        out.write(reserved)
        out.write(infoHash)
        out.write(clientId)
        out.flush()

        val protocolLength = incoming.readUnsignedByte()
        if (protocolLength != 19) return false
        val protocol = ByteArray(19)
        incoming.readFully(protocol)
        if (String(protocol, Charsets.ISO_8859_1) != "BitTorrent protocol") return false
        val remoteReserved = ByteArray(8)
        incoming.readFully(remoteReserved)
        val remoteHash = ByteArray(20)
        incoming.readFully(remoteHash)
        incoming.readFully(ByteArray(20))       // their peer id; we do not track peers between runs
        if (!remoteHash.contentEquals(infoHash)) return false

        // Every peer gets the extension handshake, because one of the first things asked for is
        // metadata -- and a peer that does not answer that is not useful whatever else it offers.
        sendExtended(0, Bencode.encode(mapOf("m" to mapOf("ut_metadata" to OUR_METADATA_ID.toLong()))))
        return true
    }

    /**
     * Fetches the info dictionary and answers with its bytes.
     *
     * Metadata is split into 16 KiB pieces of its own and requested one at a time. The assembled
     * bytes are hashed by the caller, so this method does not need to trust -- or even understand
     * -- what it received.
     */
    fun fetchMetadata(): ByteArray? {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (supportsMetaData) break
            val message = nextMessage() ?: return null
            absorb(message)
        }
        if (!supportsMetaData) return null

        val size = remoteMetadataSize
        // A PEER CHOOSES THIS NUMBER. Claiming a two-gigabyte info dictionary and then sending it
        // is a way to make the client allocate two gigabytes before verifying a byte of it.
        if (size <= 0 || size > MAX_METADATA) return null
        val count = (size + METADATA_PIECE - 1) / METADATA_PIECE
        val out = ByteArray(size)

        for (index in 0 until count) {
            val request = Bencode.encode(mapOf("msg_type" to 0L, "piece" to index.toLong()))
            sendExtended(remoteMetadataId, request)

            var received = 0
            val pieceDeadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
            while (received <= 0 && System.currentTimeMillis() < pieceDeadline) {
                // A CLOSED CONNECTION ENDS THE ATTEMPT. Looping on the null would spin the CPU for
                // the rest of the deadline, because a closed peer has nothing left to send.
                val message = nextMessage() ?: return null
                if (!absorb(message)) continue
                if (message.id != EXTENDED || message.payload.isEmpty()) continue
                if ((message.payload[0].toInt() and 0xFF) != OUR_METADATA_ID) continue

                val parsed = dictionaryWithLength(message.payload) ?: continue
                val (dictionary, bodyStart) = parsed
                when (Bencode.long(dictionary["msg_type"], -1L)) {
                    1L -> {
                        val body = message.payload.copyOfRange(bodyStart, message.payload.size)
                        val start = index * METADATA_PIECE
                        if (start >= size || body.isEmpty()) return null
                        val copy = minOf(body.size, size - start)
                        body.copyInto(out, start, 0, copy)
                        received = copy
                    }
                    2L -> return null
                }
            }
            if (received <= 0) return null
        }
        return out
    }

    /**
     * Downloads one piece and stores it.
     *
     * Blocks are 16 KiB because that is the protocol's convention, not this client's choice. A
     * window of them is kept in flight because a request/response round trip per 16 KiB would cap
     * throughput at the speed of light divided by a packet, which is not how torrents work.
     *
     * Returns false when the peer choked, timed out or sent a piece whose hash did not match --
     * all of which mean the caller should release the piece and try elsewhere.
     */
    fun downloadPiece(index: Int, info: TorrentInfo, storage: TorrentStorage): Boolean {
        val size = info.pieceSize(index)
        if (size <= 0 || index !in info.pieceHashes.indices) return false
        if (!ensureUnchoked()) return false

        val data = ByteArray(size)
        val blocks = (size + BLOCK - 1) / BLOCK
        val got = BooleanArray(blocks)
        var received = 0
        var nextBlock = 0
        val deadline = System.currentTimeMillis() + PIECE_TIMEOUT_MS

        while (received < blocks && System.currentTimeMillis() < deadline) {
            while (nextBlock < blocks && nextBlock < received + WINDOW) {
                val begin = nextBlock * BLOCK
                sendRequest(index, begin, minOf(BLOCK, size - begin))
                nextBlock++
            }

            val message = nextMessage() ?: return false
            when (message.id) {
                CHOKE -> { unchoked = false; return false }
                UNCHOKE -> unchoked = true
                PIECE_MESSAGE -> {
                    if (message.payload.size < 8) continue
                    val pieceIndex = readInt(message.payload, 0)
                    val begin = readInt(message.payload, 4)
                    if (pieceIndex != index || begin < 0 || begin % BLOCK != 0) continue
                    val block = begin / BLOCK
                    if (block !in got.indices || got[block]) continue
                    val length = message.payload.size - 8
                    if (length != minOf(BLOCK, size - begin)) continue

                    message.payload.copyInto(data, begin, 8, message.payload.size)
                    got[block] = true
                    received++
                }
            }
        }

        if (received < blocks) return false
        if (!TorrentInfo.sha1(data).contentEquals(info.pieceHashes[index])) return false
        storage.writePiece(index, data)
        return true
    }

    private fun ensureUnchoked(): Boolean {
        if (unchoked) return true
        sendInterested()
        val deadline = System.currentTimeMillis() + UNCHOKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val message = nextMessage() ?: return false
            absorb(message)
            if (message.id == UNCHOKE) {
                unchoked = true
                return true
            }
            if (message.id == CHOKE) unchoked = false
        }
        return false
    }

    fun sendInterested() = send(INTERESTED, ByteArray(0))

    fun sendRequest(index: Int, begin: Int, length: Int) {
        val payload = ByteArray(12)
        writeInt(payload, 0, index)
        writeInt(payload, 4, begin)
        writeInt(payload, 8, length)
        send(REQUEST, payload)
    }

    private fun sendExtended(extensionId: Int, body: ByteArray) {
        val payload = ByteArray(body.size + 1)
        payload[0] = extensionId.toByte()
        body.copyInto(payload, 1)
        send(EXTENDED, payload)
    }

    private fun send(id: Int, payload: ByteArray) {
        synchronized(monitor) {
            if (closed) return
            val out = output ?: return
            runCatching {
                out.writeInt(payload.size + 1)
                out.writeByte(id)
                out.write(payload)
                out.flush()
            }.onFailure { close() }
        }
    }

    /**
     * Reads one message. A keep-alive is a message with no id, and is reported as id [KEEP_ALIVE].
     *
     * Anything absurdly large closes the connection: the length prefix is thirty-two bits, so
     * without a ceiling a single peer can ask this process to allocate four gigabytes.
     */
    private fun nextMessage(): PeerMessage? {
        val incoming = input ?: return null
        if (synchronized(monitor) { closed }) return null
        val length = try {
            incoming.readInt()
        } catch (_: Exception) {
            close()
            return null
        }
        if (length < 0 || length > MAX_MESSAGE) {
            close()
            return null
        }
        if (length == 0) return PeerMessage(KEEP_ALIVE, ByteArray(0))
        val id = try {
            incoming.readUnsignedByte()
        } catch (_: Exception) {
            close()
            return null
        }
        val payload = ByteArray(length - 1)
        try {
            incoming.readFully(payload)
        } catch (_: Exception) {
            close()
            return null
        }
        return PeerMessage(id, payload)
    }

    /**
     * Records the extension handshake. Returns whether the message was one.
     *
     * Kept separate from the metadata loop because a peer's extension handshake can arrive before
     * the request is sent, between two pieces, or in the middle of an unchoke wait, and all three
     * have to update the same two fields.
     */
    private fun absorb(message: PeerMessage): Boolean {
        if (message.id != EXTENDED || message.payload.isEmpty()) return false
        if ((message.payload[0].toInt() and 0xFF) != 0) return false
        val parsed = dictionaryWithLength(message.payload) ?: return false
        val (dictionary, _) = parsed
        val `m` = dictionary["m"] as? Map<*, *>
        if (`m` != null) {
            val id = Bencode.long(`m`["ut_metadata"], -1L).toInt()
            if (id > 0) remoteMetadataId = id
        }
        val size = Bencode.long(dictionary["metadata_size"], -1L).toInt()
        if (size > 0) remoteMetadataSize = size
        return true
    }

    /** The dictionary at the start of an extended payload, plus the offset where its bytes end. */
    private fun dictionaryWithLength(payload: ByteArray): Pair<Map<*, *>, Int>? {
        if (payload.size < 2) return null
        val body = payload.copyOfRange(1, payload.size)
        val decoded = runCatching { Bencode.decodePrefix(body) }.getOrNull() ?: return null
        val dictionary = decoded.first as? Map<*, *> ?: return null
        return dictionary to (1 + decoded.second)
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }
    }

    private data class PeerMessage(val id: Int, val payload: ByteArray)

    companion object {
        private const val EXTENDED = 20
        private const val CHOKE = 0
        private const val UNCHOKE = 1
        private const val INTERESTED = 2
        private const val REQUEST = 6
        private const val PIECE_MESSAGE = 7
        private const val KEEP_ALIVE = -1

        private const val OUR_METADATA_ID = 1
        private const val METADATA_PIECE = 16 * 1024
        private const val BLOCK = 16 * 1024
        private const val WINDOW = 32
        private const val MAX_MESSAGE = 4 * 1024 * 1024
        private const val MAX_METADATA = 32 * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val METADATA_TIMEOUT_MS = 20_000L
        private const val UNCHOKE_TIMEOUT_MS = 15_000L
        private const val PIECE_TIMEOUT_MS = 60_000L

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
