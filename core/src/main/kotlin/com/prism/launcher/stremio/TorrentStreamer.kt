package com.prism.launcher.stremio

import com.prism.core.PrismPlatform
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * The built-in torrent client, as a stream source.
 *
 * ## One job
 *
 * Take an info hash, find peers, fetch the metadata, download the one file somebody asked for in
 * order, and serve it on loopback as HTTP so a video player can play it. It does not seed, upload,
 * keep a swarm, or store a library. Those are reasonable things for a torrent client to do and all
 * of them are the wrong shape for something embedded in a launcher.
 *
 * ## Why it is allowed to be this small
 *
 * Sequential playback does not need the hard parts of a torrent client. The rarest-first policy
 * exists to complete a whole torrent fairly; here the whole torrent is usually one episode and
 * everybody wants the beginning first. The pieces are still verified against the torrent's own
 * SHA-1 list, because a corrupt episode is worse than a slow one.
 *
 * ## What it refuses
 *
 * Encrypted-only peers, IPv6-only peers and alsorts of NAT traversal. A stream that needs those
 * says no peers answered, which is accurate, rather than sitting at 0% with no explanation.
 */
internal class TorrentSession(
    private val request: TorrentStreamer.Request,
    private val onStatus: (String) -> Unit,
) : Closeable {

    private val clientId: ByteArray = buildClientId()
    private val token: String = buildToken()
    private val queueLock = Object()
    private val queued = ArrayDeque<InetSocketAddress>()
    private val seen = HashSet<String>()
    private val failures = HashMap<String, Int>()
    private val openPeers = java.util.Collections.synchronizedSet(HashSet<TorrentPeer>())
    private val workers = mutableListOf<Thread>()

    private lateinit var info: TorrentInfo
    private lateinit var file: TorrentFile
    private lateinit var storage: TorrentStorage
    private lateinit var pieces: TorrentPieces
    private var server: StreamServer? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var lastTrackerAt = 0L
    private var lastDhtAt = 0L

    @Volatile
    private var lastReportAt = 0L

    @Volatile
    var error: String? = null
        private set

    lateinit var url: String
        private set

    /**
     * Gets as far as a URL, then returns.
     *
     * Metadata is the only part that must finish before the player is handed anything: without it
     * there is no file length and no piece hashes. The download itself starts in the background and
     * the HTTP server blocks on the pieces the player asks for, which is what makes playback start
     * after the first megabyte rather than after the whole episode.
     */
    fun start(): Boolean {
        if (request.infoHash.size != 20) {
            error = "That stream's info hash is malformed."
            return false
        }

        onStatus("Finding peers…")
        discover()

        onStatus("Reading the torrent…")
        val metadata = fetchMetadata() ?: run {
            error = "No peer answered with this torrent's metadata. It may have no seeders right now."
            return false
        }

        val parsed = TorrentInfo.fromMetadata(metadata, request.infoHash, request.trackers) ?: run {
            error = "A peer sent metadata that did not match the stream's hash."
            return false
        }
        info = parsed

        val selected = info.selectFile(request.fileIdx, request.filename) ?: run {
            error = "That torrent has no files."
            return false
        }
        file = selected

        val root = File(PrismPlatform.host.cacheDir(), "torrent-streams/" + info.hexHash()).apply { mkdirs() }
        storage = TorrentStorage(File(root, safeName(selected.name)), info, selected)
        pieces = TorrentPieces(info, selected)

        val source = object : StreamSource {
            override val length: Long get() = file.length
            override fun contentType(): String = contentTypeOf(file.name)

            override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, max: Int, timeoutMs: Long): Int {
                pieces.urgent(file.offset + offset)
                return storage.read(offset, buffer, bufferOffset, max, timeoutMs)
            }
        }
        server = StreamServer(source, token, selected.name)
        url = server!!.url

        onStatus("Buffering…")
        startWorkers()
        startWatchdog()
        return true
    }

    // ── Peers ───────────────────────────────────────────────────────────────

    /**
     * Finds peers through trackers first and the DHT second.
     *
     * The DHT is what makes an info-hash-only stream playable, but it costs seconds of UDP
     * round trips. Trackers are asked every time and the DHT only when they did not produce enough
     * peers to be worth trying, because a torrent with a healthy tracker list does not need it.
     */
    private fun discover() {
        val now = System.currentTimeMillis()
        // Retried faster while the queue is empty: a stream with no peers shows a spinner, and one
        // tracker round trip every four seconds is a fair price for shortening that.
        val trackerGap = if (queuedCount() == 0) 4_000L else 15_000L
        val runTrackers = synchronized(queueLock) {
            if (now - lastTrackerAt < trackerGap) false else { lastTrackerAt = now; true }
        }
        if (runTrackers && request.trackers.isNotEmpty()) {
            addPeers(runCatching { Trackers.announce(request.trackers, request.infoHash, clientId) }.getOrDefault(emptyList()))
        }

        // ONLY WHEN THE TRACKERS WERE THIN. The DHT costs seconds of UDP round trips, and a torrent
        // whose trackers already returned a dozen peers does not need to pay for it before the
        // first frame. With no tracker list at all -- the usual Stremio shape -- this is the only
        // discovery there is.
        val runDht = synchronized(queueLock) {
            if (queuedCount() >= 3 || now - lastDhtAt < 20_000) false else { lastDhtAt = now; true }
        }
        if (runDht) {
            addPeers(runCatching { Dht.findPeers(request.infoHash, 9_000) }.getOrDefault(emptyList()))
        }
    }

    private fun addPeers(peers: Collection<InetSocketAddress>) = synchronized(queueLock) {
        peers.forEach { address ->
            if (address.port <= 0) return@forEach
            val key = address.hostString + ":" + address.port
            if (seen.add(key)) queued.addLast(address)
        }
    }

    private fun queuedCount(): Int = synchronized(queueLock) { queued.size }

    private fun takePeer(): InetSocketAddress? = synchronized(queueLock) {
        while (queued.isNotEmpty()) {
            val address = queued.removeFirst()
            val key = address.hostString + ":" + address.port
            if ((failures[key] ?: 0) < 2) return address
        }
        null
    }

    private fun fail(address: InetSocketAddress) = synchronized(queueLock) {
        val key = address.hostString + ":" + address.port
        failures[key] = (failures[key] ?: 0) + 1
    }

    /**
     * Asks peers for the info dictionary until one has it.
     *
     * The deadline is generous because a torrent with few seeders can take a while to find a peer
     * that supports the metadata extension at all. Every failed candidate is retried at most twice
     * across the session, so a list of dead addresses cannot be looped over forever.
     */
    private fun fetchMetadata(): ByteArray? {
        val deadline = System.currentTimeMillis() + 40_000
        var attempts = 0

        while (!stopped && System.currentTimeMillis() < deadline && attempts < 24) {
            val address = takePeer()
            if (address == null) {
                discover()
                if (queuedCount() == 0) Thread.sleep(600)
                continue
            }
            attempts++

            val peer = TorrentPeer(address, request.infoHash, clientId)
            try {
                if (!peer.connect()) {
                    fail(address)
                    continue
                }
                peer.fetchMetadata()?.let { return it }
                fail(address)
            } catch (_: Exception) {
                fail(address)
            } finally {
                peer.close()
            }
        }
        return null
    }

    // ── Downloading ─────────────────────────────────────────────────────────

    private fun startWorkers() {
        repeat(WORKERS) {
            val thread = Thread({ worker() }, "prism-torrent-worker")
            thread.isDaemon = true
            thread.start()
            workers.add(thread)
        }
    }

    /**
     * One worker: hold a peer connection and take pieces until it fails or the file is complete.
     *
     * A connection is reused across pieces on purpose. Connecting per piece would pay a handshake
     * and an unchoke wait every two megabytes, which on a high-latency peer is most of the time.
     * A failed piece closes the connection and the next loop iteration tries a different peer: a
     * peer that choked mid-piece is not necessarily bad, but it is not worth blocking on either.
     */
    private fun worker() {
        while (!stopped && !pieces.isDone()) {
            val address = takePeer()
            if (address == null) {
                if (pieces.isDone()) break
                discover()
                if (queuedCount() == 0) Thread.sleep(1_500)
                continue
            }

            val peer = TorrentPeer(address, info.infoHash, clientId)
            openPeers.add(peer)
            try {
                if (!peer.connect()) {
                    fail(address)
                    continue
                }
                while (!stopped && !pieces.isDone()) {
                    val index = pieces.takeBlocking(1_000) ?: continue
                    val stored = try {
                        peer.downloadPiece(index, info, storage)
                    } catch (_: Exception) {
                        false
                    }
                    if (stored) {
                        pieces.complete(index)
                        reportProgress()
                    } else {
                        pieces.release(index)
                        fail(address)
                        break
                    }
                }
            } catch (_: Exception) {
                fail(address)
            } finally {
                openPeers.remove(peer)
                peer.close()
            }
        }
    }

    /**
     * Fails the stream when nobody is sending anything, instead of leaving the player on a spinner.
     *
     * Ninety seconds is long enough for a slow tracker announce and short enough that somebody
     * waiting has not concluded the app is broken.
     */
    private fun startWatchdog() {
        val thread = Thread({
            var lastHave = 0
            var lastChange = System.currentTimeMillis()
            while (!stopped && !pieces.isDone()) {
                Thread.sleep(3_000)
                val have = pieces.snapshot().havePieces
                if (have != lastHave) {
                    lastHave = have
                    lastChange = System.currentTimeMillis()
                    continue
                }
                if (System.currentTimeMillis() - lastChange > 90_000 && openPeers.isEmpty() && queuedCount() == 0) {
                    storage.fail("No peers are sending data for this torrent.")
                    onStatus("Stalled — no peers are sending data")
                    return@Thread
                }
            }
        }, "prism-torrent-watchdog")
        thread.isDaemon = true
        thread.start()
    }

    private fun reportProgress() {
        val now = System.currentTimeMillis()
        if (now - lastReportAt < 1_000) return
        lastReportAt = now
        val snapshot = pieces.snapshot()
        onStatus(
            if (snapshot.done) "Ready"
            else "Buffering — " + (snapshot.fraction * 100).toInt() + "% of the file"
        )
    }

    override fun close() {
        if (stopped) return
        stopped = true
        runCatching { server?.close() }
        runCatching { if (::pieces.isInitialized) pieces.close() }
        // DISCARD, NOT JUST CLOSE: the file is a cache of one stream, and leaving it behind would
        // accumulate a copy of every episode tried for as long as the app is installed.
        runCatching { if (::storage.isInitialized) storage.discard() }
        openPeers.toList().forEach { runCatching { it.close() } }
        workers.forEach { it.interrupt() }
    }

    companion object {
        private const val WORKERS = 4

        private fun buildClientId(): ByteArray {
            val suffix = (1..12).map { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"[Random.nextInt(62)] }
            return ("-PR0001-" + suffix.joinToString("")).toByteArray(Charsets.US_ASCII).copyOf(20)
        }

        private fun buildToken(): String =
            (1..24).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

        private fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "stream" }

        private fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "srt" -> "application/x-subrip"
            else -> "application/octet-stream"
        }
    }
}

/**
 * The torrent client, as the rest of Prism sees it.
 *
 * A small registry rather than a single session, because a person browses Stream sources and tries
 * another one when one is silent -- and the previous server must stay up long enough for the
 * player to be torn down properly. At most three sessions are kept; the oldest is stopped and its
 * file deleted when a fourth is asked for, which bounds both sockets and disk.
 */
object TorrentStreamer {

    data class Request(
        val infoHash: ByteArray,
        val trackers: List<String>,
        val fileIdx: Int?,
        val filename: String?,
    )

    data class OpenResult(val url: String?, val error: String?)

    private const val MAX_SESSIONS = 3

    private val sessions = LinkedHashMap<String, TorrentSession>()

    /**
     * Starts (or reuses) a session and returns its loopback URL.
     *
     * Blocking until metadata is in hand, which is seconds on a healthy torrent and up to the
     * metadata deadline on a dead one. Callers are background threads; the player shows
     * [TorrentSession]'s status while it waits.
     */
    @Synchronized
    fun open(request: Request, onStatus: (String) -> Unit): OpenResult {
        val key = request.infoHash.joinToString("") { "%02x".format(it) } +
            "|" + (request.fileIdx ?: -1) +
            "|" + (request.filename ?: "")

        sessions[key]?.let { existing -> return OpenResult(existing.url, null) }

        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.firstOrNull() ?: break
            sessions.remove(oldest.key)
            oldest.value.close()
        }

        val session = TorrentSession(request, onStatus)
        if (!session.start()) {
            session.close()
            return OpenResult(null, session.error)
        }
        sessions[key] = session
        return OpenResult(session.url, null)
    }

    /** Stops every session. Called when the player leaves Stremio content. */
    @Synchronized
    fun stopAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
