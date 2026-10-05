package com.prism.launcher.cloud

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.mesh.P2pModelRegistry
import com.prism.launcher.vpn.PrismSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress

/**
 * The mesh cloud's routes, and the one place that talks to a peer.
 *
 * ## Routes
 *
 * - `POST /cloud-put` — `chunkId ‖ \n ‖ ciphertext`. The peer stores it if it has room.
 * - `POST /cloud-get` — `chunkId`. Returns the ciphertext, or 404.
 * - `POST /cloud-drop` — `chunkId`. The peer forgets it.
 * - `POST /cloud` — JSON control: quota, gaming sessions, and what a peer is offering.
 *
 * Chunk transfer is a separate raw route from the JSON control channel for the same reason the protein
 * weights are: a chunk is a megabyte of random bytes, and putting it inside JSON would mean Base64,
 * which is a third more bytes for no benefit to anyone.
 *
 * ## The chunk id is not trusted as a filename
 *
 * It arrives from the network and is used to name a file, so it is validated as 64 hex characters
 * before it is used for anything. Without that, `../../shared_prefs/wallet` is a chunk id — and the
 * route that stores chunks would write outside its own directory, and the route that serves them would
 * read outside it. The check is in [validId] and every route calls it.
 */
object CloudWire {

    private const val TAG = "PrismCloudWire"

    const val PATH_PUT = "/cloud-put"
    const val PATH_GET = "/cloud-get"
    const val PATH_DROP = "/cloud-drop"
    const val PATH_CONTROL = "/cloud"

    const val OP_OFFER = "offer"
    const val OP_GAME_START = "game-start"
    const val OP_GAME_STOP = "game-stop"
    const val OP_GAME_LIST = "game-list"

    private const val PEER_PORT = 8080

    /** A chunk transfer is a megabyte over Wi-Fi; a minute is generous and bounded. */
    private const val TRANSFER_TIMEOUT_MS = 60_000

    private const val CONTROL_TIMEOUT_MS = 20_000

    /**
     * Anything longer than a chunk plus its framing is not a chunk.
     *
     * Bounded because the reply length is not known in advance and a peer that streams forever would
     * otherwise fill the heap.
     */
    private const val MAX_CHUNK_RESPONSE = (CloudVault.CHUNK_BYTES * 2).toLong()

    /** 64 lowercase hex characters and nothing else. See the class comment. */
    fun validId(id: String): Boolean = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }

    // ── Client ─────────────────────────────────────────────────────────────

    fun putChunk(peerIp: String, chunkId: String, sealed: ByteArray): Boolean {
        if (!validId(chunkId)) return false
        val body = ByteArrayOutputStream(sealed.size + 80).apply {
            write(chunkId.toByteArray())
            write('\n'.code)
            write(sealed)
        }.toByteArray()
        val reply = post(peerIp, PATH_PUT, body, TRANSFER_TIMEOUT_MS) ?: return false
        return runCatching { JSONObject(String(reply)).optBoolean("ok") }.getOrDefault(false)
    }

    fun getChunk(peerIp: String, chunkId: String): ByteArray? {
        if (!validId(chunkId)) return null
        val reply = post(peerIp, PATH_GET, chunkId.toByteArray(), TRANSFER_TIMEOUT_MS) ?: return null
        // A refusal comes back as small JSON; a hit comes back as ciphertext. Distinguished by size
        // and shape rather than by a status code, because the body is read after the headers are
        // skipped and re-plumbing the status through would buy nothing here.
        if (reply.size < 64 && String(reply).contains("\"ok\":false")) return null
        return reply.takeIf { it.isNotEmpty() }
    }

    fun dropChunk(peerIp: String, chunkId: String): Boolean {
        if (!validId(chunkId)) return false
        val reply = post(peerIp, PATH_DROP, chunkId.toByteArray(), CONTROL_TIMEOUT_MS) ?: return false
        return runCatching { JSONObject(String(reply)).optBoolean("ok") }.getOrDefault(false)
    }

    /** What a peer says it is offering: storage room, and whether it will host a game. */
    data class Offer(
        val storageFreeBytes: Long,
        val sellsStorage: Boolean,
        val hostsGames: Boolean,
        val games: List<String>,
    )

    fun offer(peerIp: String): Offer? {
        val reply = post(
            peerIp,
            PATH_CONTROL,
            JSONObject().apply { put("op", OP_OFFER) }.toString().toByteArray(),
            CONTROL_TIMEOUT_MS,
        ) ?: return null
        return runCatching {
            val json = JSONObject(String(reply))
            val gamesArray = json.optJSONArray("games")
            val games = ArrayList<String>()
            for (i in 0 until (gamesArray?.length() ?: 0)) {
                gamesArray?.optString(i)?.takeIf { it.isNotBlank() }?.let { games.add(it) }
            }
            Offer(
                storageFreeBytes = json.optLong("free"),
                sellsStorage = json.optBoolean("storage"),
                hostsGames = json.optBoolean("gaming"),
                games = games,
            )
        }.getOrNull()
    }

    /** Asks a peer to start a game and return the display port to connect to. */
    fun startGame(peerIp: String, title: String, width: Int, height: Int): Int? {
        val reply = post(
            peerIp,
            PATH_CONTROL,
            JSONObject().apply {
                put("op", OP_GAME_START)
                put("title", title)
                put("w", width)
                put("h", height)
            }.toString().toByteArray(),
            CONTROL_TIMEOUT_MS,
        ) ?: return null
        return runCatching {
            val json = JSONObject(String(reply))
            if (!json.optBoolean("ok")) null else json.optInt("port").takeIf { it in 1..65535 }
        }.getOrNull()
    }

    fun stopGame(peerIp: String): Boolean {
        val reply = post(
            peerIp,
            PATH_CONTROL,
            JSONObject().apply { put("op", OP_GAME_STOP) }.toString().toByteArray(),
            CONTROL_TIMEOUT_MS,
        ) ?: return false
        return runCatching { JSONObject(String(reply)).optBoolean("ok") }.getOrDefault(false)
    }

    // ── Transport ──────────────────────────────────────────────────────────

    /**
     * One POST over the mesh tunnel, same path as peer inference and protein work.
     *
     * The timeout is set after connect, not before: [PrismSocket.connect] runs the PRISM_CONNECT
     * handshake under its own much shorter timeout and used to leave that on the socket, which failed
     * every request that took longer than a handshake.
     */
    private fun post(peerIp: String, path: String, body: ByteArray, timeoutMs: Int): ByteArray? {
        var socket: PrismSocket? = null
        return try {
            socket = PrismSocket()
            socket.setHostHint(P2pModelRegistry.MODEL_HOST_DOMAIN)
            socket.connect(InetSocketAddress(peerIp, PEER_PORT), 15_000)
            socket.soTimeout = timeoutMs

            val output = socket.getOutputStream()
            output.write(
                ("POST $path HTTP/1.1\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            output.write(body)
            output.flush()

            val input = socket.getInputStream()
            skipHeaders(input)
            readAll(input)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "POST $path to $peerIp failed", e)
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun skipHeaders(input: InputStream) {
        var matched = 0
        while (matched < 4) {
            val c = input.read()
            if (c == -1) return
            matched = when {
                matched == 0 && c == '\r'.code -> 1
                matched == 1 && c == '\n'.code -> 2
                matched == 2 && c == '\r'.code -> 3
                matched == 3 && c == '\n'.code -> 4
                c == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            total += n
            if (total > MAX_CHUNK_RESPONSE) break
        }
        return out.toByteArray()
    }
}
