package com.prism.launcher.aether

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN-direct knowledge sharing for Aether -- wire-compatible with AetherCortex's own
 * `network/knowledge_sync.py`, so a phone and a Python instance on the same Wi-Fi find and merge
 * with each other, not just phone-to-phone. Same UDP discovery packet shape (`AETHERCORTEX1`
 * magic, JSON `discover`/`announce` messages on port 47821) and the same AKEF format for the
 * actual transfer (see [AetherAkef]).
 *
 * STANDALONE `ServerSocket`, NOT ROUTED THROUGH THE MESH TUNNEL. LAN peers (a laptop running
 * AetherCortex, another phone not on the mesh) are not inside Prism's mesh VPN, so this listens
 * directly on a plain socket -- mirrors the code shape of `PrismWebHost.kt`'s raw-socket request
 * parsing, but as its own accept loop rather than dispatched through `PrismProxyServer`. Mesh
 * peers get a SEPARATE path entirely -- see `AetherMeshSync.kt`/`AetherConnectomeHost.kt` -- since
 * those go through the mesh's own TCP tunnel and reserved-domain dispatch, not a raw LAN socket.
 *
 * SAFETY POINT FOR APPLYING A MERGE. Like the Python receiver, this thread only ever computes a
 * merge (plain `FloatArray` arithmetic, via [AetherAkef.mergeNamed]) and stages it; the actual
 * in-place write onto the live connectome's arrays happens on [applyPending], called by
 * `AetherStudio` from the same thread that owns the connectome, at a point already known to be
 * safe (after training completes, or right after a fresh brain is built).
 */
object AetherKnowledgeSync {

    private const val MAGIC = "AETHERCORTEX1"
    const val DISCOVERY_PORT = 47821
    const val DEFAULT_HTTP_PORT = 47822
    private const val POLL_INTERVAL_MS = 30_000L

    /** Neutral baseline for a snapshot saved before scoring existed, or unreadable -- mirrors
     * network/knowledge_sync.py's DEFAULT_SCORE exactly (both sides must agree on this fallback
     * so an old peer isn't unfairly treated as worthless). */
    const val DEFAULT_SCORE = 50

    /** Distinct from AetherService's own ongoing training notification ID (4712) -- this one is
     *  a one-shot "something happened" toast, never the ongoing progress notification. */
    private const val NOTIFY_CHANNEL = "aether_knowledge"
    private const val NOTIFY_ID = 4713

    val instanceId: String = UUID.randomUUID().toString().replace("-", "")

    data class PeerInfo(
        val instanceId: String,
        val address: String,
        val httpPort: Int,
        val hasModel: Boolean,
        val geometrySignature: String,
        val lastSeenMs: Long,
        val transport: String = "lan",
        val score: Int = DEFAULT_SCORE
    )

    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers

    private val running = AtomicBoolean(false)
    private var shareEnabled = false
    private var receiveEnabled = false
    private var httpPort = DEFAULT_HTTP_PORT

    private var discoverySocket: DatagramSocket? = null
    private var httpServer: ServerSocket? = null
    private var discoveryThread: Thread? = null
    private var httpThread: Thread? = null
    private var receiverThread: Thread? = null

    private val pendingLock = Any()

    /**
     * A staged merge is held ON DISK, not in a field.
     *
     * `AetherService` is declared `android:process=":aether"` (see AndroidManifest), and Android
     * gives every process its OWN copy of a Kotlin `object`'s state. Staging happens in the MAIN
     * process -- `AetherSettingsActivity`'s "tap to receive", `AetherMeshSync`, and the background
     * poll loop all run there -- while `applyPending` runs in `:aether`, because only that process
     * may touch the resident connectome. A staged merge written to a field in one process is
     * therefore invisible to the other, and `applyPending` found `pendingTensors == null` every
     * single time: "Nothing staged to apply", no matter how successful the transfer was.
     *
     * Disk is the one thing both processes share. This is also exactly how AetherCortex itself
     * does it -- `network/daemon.py` is a standalone process that only ever writes an AKEF
     * snapshot, which sits there until a connectome is next built and picks it up -- so the two
     * implementations now stage the same way as well as transfer the same way. It survives process
     * death for free, which the field never did.
     */
    private fun stagingDir(): File =
        File(PrismPlatform.host.dataDir(), "aether_pending").apply { mkdirs() }

    private fun pendingBlobFile() = File(stagingDir(), "pending.akef")
    private fun pendingMetaFile() = File(stagingDir(), "pending.json")
    private fun mergedDigestsFile() = File(stagingDir(), "merged_digests.txt")

    /** Persisted for the same cross-process reason: the applier records a digest, and the
     * main-process fetcher has to see it to know not to re-download the same snapshot forever. */
    private fun readMergedDigests(): MutableSet<String> = try {
        val f = mergedDigestsFile()
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
    } catch (e: Exception) {
        mutableSetOf()
    }

    private fun recordMergedDigest(digest: String) {
        try {
            synchronized(pendingLock) { mergedDigestsFile().appendText(digest + "\n") }
        } catch (e: Exception) {
            AetherLog.warn(AetherLog.Area.BRAIN, "Could not record merged digest: ${e.message}")
        }
    }

    var lastStatus: String = "idle"
        private set

    /** Lets `AetherMeshSync` (a different transport, same status line the settings UI reads) report its own outcomes through the one status field. */
    fun setLastStatus(s: String) { lastStatus = s }

    fun isRunning(): Boolean = running.get()
    fun isSharing(): Boolean = running.get() && shareEnabled

    fun start(share: Boolean, receive: Boolean, port: Int = DEFAULT_HTTP_PORT) {
        if (running.get()) return
        shareEnabled = share
        receiveEnabled = receive
        httpPort = port
        running.set(true)

        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(DISCOVERY_PORT))
            soTimeout = 1000
        }
        discoverySocket = sock

        discoveryThread = Thread({ discoveryLoop() }, "aether-discovery").apply { isDaemon = true; start() }

        if (share) {
            httpServer = ServerSocket(httpPort)
            httpThread = Thread({ httpAcceptLoop() }, "aether-share-http").apply { isDaemon = true; start() }
            AetherLog.info(AetherLog.Area.BRAIN, "Aether knowledge sharing on http://0.0.0.0:$httpPort/akef/*")
        }
        if (receive) {
            receiverThread = Thread({ receiverLoop() }, "aether-receiver").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running.set(false)
        try { discoverySocket?.close() } catch (_: Exception) {}
        try { httpServer?.close() } catch (_: Exception) {}
        discoverySocket = null
        httpServer = null
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    private fun discoveryLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            val sock = discoverySocket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val msg = try { JSONObject(text) } catch (e: Exception) { continue }
                if (msg.optString("magic") != MAGIC) continue
                when (msg.optString("type")) {
                    "discover" -> if (shareEnabled) replyAnnounce(packet.address, packet.port)
                    "announce" -> if (receiveEnabled) recordAnnounce(msg, packet.address)
                }
            } catch (e: SocketException) {
                break
            } catch (e: java.net.SocketTimeoutException) {
                // expected, lets the loop re-check `running`
            } catch (e: Exception) {
                // malformed packet from something that isn't a peer -- ignore and keep listening
            }
        }
    }

    fun broadcastDiscover() {
        val sock = discoverySocket ?: return
        try {
            val msg = JSONObject().put("magic", MAGIC).put("type", "discover").put("instance_id", instanceId)
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
            sock.send(packet)
        } catch (e: Exception) {
            AetherLog.warn(AetherLog.Area.BRAIN, "AetherKnowledgeSync: discovery broadcast failed: ${e.message}")
        }
    }

    private fun replyAnnounce(address: InetAddress, port: Int) {
        val sock = discoverySocket ?: return
        try {
            val geometry = AetherConfig.geometry
            val msg = JSONObject()
                .put("magic", MAGIC)
                .put("type", "announce")
                .put("instance_id", instanceId)
                .put("http_port", httpPort)
                .put("has_model", akefSnapshotFile(geometry).exists())
                .put("geometry_signature", geometry.signature())
                .put("score", readLocalScore())
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (e: Exception) {
            // a peer we can no longer reach -- not worth logging every occurrence
        }
    }

    private fun recordAnnounce(msg: JSONObject, address: InetAddress) {
        val peerId = msg.optString("instance_id")
        if (peerId.isEmpty() || peerId == instanceId) return // self-filter by instance ID, not IP
        val info = PeerInfo(
            instanceId = peerId,
            address = address.hostAddress ?: return,
            httpPort = msg.optInt("http_port", DEFAULT_HTTP_PORT),
            hasModel = msg.optBoolean("has_model", false),
            geometrySignature = msg.optString("geometry_signature"),
            lastSeenMs = System.currentTimeMillis(),
            score = msg.optInt("score", DEFAULT_SCORE)
        )
        _peers.value = _peers.value + (peerId to info)
    }

    /** Drops peers not heard from recently -- same stale-eviction shape as `P2pModelRegistry`. */
    fun evictStale(maxAgeMs: Long = 90_000L) {
        val now = System.currentTimeMillis()
        _peers.value = _peers.value.filterValues { now - it.lastSeenMs <= maxAgeMs }
    }

    // ── Share: HTTP server ──────────────────────────────────────────────────

    private fun akefSnapshotFile(geometry: AetherGeometry): File =
        File(AetherConfig.weightsDir(), "akef_${geometry.signature()}.safetensors")

    /** Call wherever the native connectome is already being saved, so the HTTP server always has something fresh to serve -- mirrors `network/knowledge_sync.export_snapshot` on the Python side. */
    /** Cheap: [AetherAkef.load] only reads the AKEF file's header, never the tensor body. Used by
     * both the LAN discovery-reply/manifest paths here and [AetherMeshSync]'s announce, so a peer
     * can compare quality before downloading anything. Falls back to [DEFAULT_SCORE] for an
     * untrained instance (no snapshot yet) or one saved before this feature existed. */
    fun readLocalScore(): Int {
        val file = akefSnapshotFile(AetherConfig.geometry)
        if (!file.exists()) return DEFAULT_SCORE
        return try {
            AetherAkef.load(file).second["score"]?.toIntOrNull() ?: DEFAULT_SCORE
        } catch (e: Exception) {
            DEFAULT_SCORE
        }
    }

    /** [extraMetadata] (e.g. `mapOf("score" to "87")` -- see [AetherScoring]) rides straight
     * through to [AetherAkef.saveConnectome]'s own hook. */
    fun exportSnapshot(connectome: AetherConnectome, extraMetadata: Map<String, String> = emptyMap()) {
        try {
            AetherAkef.saveConnectome(connectome, akefSnapshotFile(connectome.geometry), extraMetadata)
        } catch (e: Exception) {
            AetherLog.warn(AetherLog.Area.BRAIN, "AetherKnowledgeSync: snapshot export failed: ${e.message}")
        }
    }

    private fun httpAcceptLoop() {
        while (running.get()) {
            val server = httpServer ?: break
            try {
                val client = server.accept()
                Thread({ handleHttpClient(client) }, "aether-share-http-conn").apply { isDaemon = true; start() }
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                // one bad connection should not take the accept loop down
            }
        }
    }

    /**
     * Serves one `GET /akef/manifest` or `GET /akef/connectome` request on [socket] and closes
     * it -- the same handler the LAN accept loop uses, exposed so `AetherConnectomeHost` (the
     * mesh-tunnel path, app module) can reuse it for a socket that arrived via the mesh's TCP
     * tunnel instead of this object's own `ServerSocket`. One request-serving implementation,
     * two transports delivering a socket to it.
     */
    /** [preReadHeader]: see PrismSearchServer.serveHttp -- the proxy's browser path consumes the
     * request line before dispatching, leaving nothing for this to read. */
    fun serveHttp(socket: Socket, preReadHeader: String? = null) = handleHttpClient(socket, preReadHeader)

    private fun handleHttpClient(socket: Socket, preReadHeader: String? = null) {
        socket.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = preReadHeader
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: reader.readLine()
                ?: return
            // Drain headers; nothing here needs them, but the client will hang waiting for us to
            // read them if we don't. Only when we read the request line ourselves.
            if (preReadHeader == null) {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeSimpleResponse(s, 405, "text/plain", "Method Not Allowed".toByteArray())
                return
            }
            when (parts[1]) {
                "/akef/manifest" -> serveManifest(s)
                "/akef/connectome" -> serveConnectome(s)
                else -> writeSimpleResponse(s, 404, "text/plain", "Not Found".toByteArray())
            }
        }
    }

    private fun serveManifest(s: Socket) {
        val file = akefSnapshotFile(AetherConfig.geometry)
        if (!file.exists()) {
            writeSimpleResponse(s, 404, "text/plain", "No trained connectome yet".toByteArray())
            return
        }
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var buildTimestamp = ""
        var score = DEFAULT_SCORE
        try {
            val metadata = AetherAkef.load(file).second
            buildTimestamp = metadata["build_timestamp"] ?: ""
            score = metadata["score"]?.toIntOrNull() ?: DEFAULT_SCORE
        } catch (e: Exception) {
            // defaults above stand
        }
        val body = JSONObject()
            .put("instance_id", instanceId)
            .put("geometry_signature", AetherConfig.geometry.signature())
            .put("sha256", digest)
            .put("size_bytes", bytes.size)
            .put("build_timestamp", buildTimestamp)
            .put("score", score)
            .toString()
            .toByteArray(Charsets.UTF_8)
        writeSimpleResponse(s, 200, "application/json", body)
    }

    private fun serveConnectome(s: Socket) {
        val file = akefSnapshotFile(AetherConfig.geometry)
        if (!file.exists()) {
            writeSimpleResponse(s, 404, "text/plain", "No trained connectome yet".toByteArray())
            return
        }
        writeSimpleResponse(s, 200, "application/octet-stream", file.readBytes())
    }

    private fun writeSimpleResponse(s: Socket, code: Int, contentType: String, body: ByteArray) {
        val statusText = if (code == 200) "OK" else if (code == 404) "Not Found" else "Error"
        val header = "HTTP/1.1 $code $statusText\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
    }

    // ── Receive: background poll + stage ─────────────────────────────────────

    private fun receiverLoop() {
        Thread.sleep(1000)
        while (running.get()) {
            try {
                receiveCycle()
            } catch (e: Exception) {
                lastStatus = "receiver cycle failed: ${e.message}"
            }
            var waited = 0L
            while (running.get() && waited < POLL_INTERVAL_MS) {
                Thread.sleep(500); waited += 500
            }
        }
    }

    private fun receiveCycle() {
        evictStale()
        broadcastDiscover()
        Thread.sleep(2000)
        // Highest-scored trained peer, not just the first one seen -- score rides on the announce
        // packet itself (see recordAnnounce), so this never needs to fetch anything from a peer
        // it isn't going to pick.
        val candidate = _peers.value.values.filter { it.hasModel }.maxByOrNull { it.score }
        if (candidate == null) {
            lastStatus = "no trained peers found (${_peers.value.size} peer(s) seen)"
            return
        }
        fetchAndStage(candidate)
    }

    /** Downloads and stages a merge from a specific LAN peer -- both the background poll loop and the settings UI's "tap to receive" go through this same path. */
    fun fetchAndStage(peer: PeerInfo): Boolean {
        val label = "${peer.address}:${peer.httpPort} (${peer.instanceId.take(8)})"
        return try {
            val manifestJson = httpGetText("http://${peer.address}:${peer.httpPort}/akef/manifest")
            val manifestDigest = JSONObject(manifestJson).optString("sha256")
            if (isAlreadyMerged(manifestDigest)) {
                lastStatus = "$label unchanged since last merge"
                return false
            }
            val blob = httpGetBytes("http://${peer.address}:${peer.httpPort}/akef/connectome")
            stageBlob(blob, manifestDigest, label)
        } catch (e: Exception) {
            lastStatus = "fetch from $label failed: ${e.message}"
            false
        }
    }

    fun isAlreadyMerged(sha256: String): Boolean = sha256.isNotEmpty() && sha256 in readMergedDigests()

    /**
     * Stages a downloaded AKEF blob (from ANY transport -- LAN HTTP or, via `AetherMeshSync`, the
     * mesh tunnel) as the pending merge. Verifies [expectedSha256] if the caller has one from a
     * manifest. One staging path for every transport means [applyPending] only has to know how to
     * apply a merge, never where it came from.
     */
    fun stageBlob(blob: ByteArray, expectedSha256: String?, sourceLabel: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }
        if (!expectedSha256.isNullOrEmpty() && digest != expectedSha256) {
            lastStatus = "checksum mismatch from $sourceLabel, discarding"
            return false
        }
        if (isAlreadyMerged(digest)) {
            lastStatus = "$sourceLabel unchanged since last merge"
            return false
        }
        val tmp = File.createTempFile("aether_incoming_", ".safetensors")
        return try {
            tmp.writeBytes(blob)
            // Parsed here purely to reject a corrupt blob before it is staged; the bytes, not the
            // parsed tensors, are what gets handed across the process boundary.
            AetherAkef.load(tmp)
            synchronized(pendingLock) {
                val staged = File(stagingDir(), "pending.akef.part")
                staged.writeBytes(blob)
                // Rename last: the applier looks for pending.akef, so it must never observe a
                // half-written file. Both processes poke at this directory independently.
                if (!staged.renameTo(pendingBlobFile())) {
                    pendingBlobFile().writeBytes(blob)
                    staged.delete()
                }
                pendingMetaFile().writeText(
                    JSONObject().put("source", sourceLabel).put("digest", digest).toString())
            }
            lastStatus = "staged a merge from $sourceLabel"
            true
        } finally {
            tmp.delete()
        }
    }

    /**
     * Applies whatever merge is staged, IN PLACE onto [connectome]. Call from the thread that
     * owns [connectome] -- `AetherStudio` calls this after training completes and right after a
     * fresh brain is built, mirroring `train.py`'s sleep-consolidation point and `main.py`'s
     * pre-generation grace period respectively.
     */
    fun applyPending(connectome: AetherConnectome, isTrained: Boolean): Boolean {
        val (tensors, source, digest) = synchronized(pendingLock) {
            val blobFile = pendingBlobFile()
            val metaFile = pendingMetaFile()
            if (!blobFile.exists()) {
                Triple(null, null, null)
            } else {
                val meta = try {
                    if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
                } catch (e: Exception) {
                    JSONObject()
                }
                val parsed = try {
                    AetherAkef.load(blobFile).first
                } catch (e: Exception) {
                    AetherLog.warn(AetherLog.Area.BRAIN, "Staged merge is unreadable, discarding: ${e.message}")
                    null
                }
                // Consumed either way -- a blob that will not parse must not be retried forever.
                blobFile.delete(); metaFile.delete()
                Triple(parsed, meta.optString("source", "a peer"), meta.optString("digest", ""))
            }
        }
        if (tensors == null) return false

        if (isTrained) {
            val local = AetherAkef.exportNamed(connectome)
            val merged = AetherAkef.mergeNamed(local, tensors)
            val (applied, skipped) = AetherAkef.importNamed(connectome, merged)
            AetherLog.success(AetherLog.Area.BRAIN, "Merged knowledge from $source: ${applied.size} tensors blended, ${skipped.size} skipped.")
        } else {
            val (applied, skipped) = AetherAkef.importNamed(connectome, tensors)
            AetherLog.success(AetherLog.Area.BRAIN, "Adopted knowledge from $source (was untrained): ${applied.size} tensors applied, ${skipped.size} skipped.")
        }
        if (!digest.isNullOrEmpty()) recordMergedDigest(digest)

        // A one-shot, dismissible notification (NOT the ongoing training notification) --
        // PrismPlatform.notifier posts through the same cross-platform Notifier every other
        // "something happened while you weren't looking" event in the app uses, so this needs no
        // Android-specific code here despite AetherKnowledgeSync itself being a plain core-module
        // class. Same notify() call regardless of whether this was auto-merged in the background
        // or triggered by a manual "tap to receive."
        PrismPlatform.notifier.notify(
            NOTIFY_CHANNEL, NOTIFY_ID,
            "Aether grew a little 🧠😊",
            "Picked up new knowledge from $source."
        )
        return true
    }

    private fun httpGetText(url: String): String = String(httpGetBytes(url), Charsets.UTF_8)

    private fun httpGetBytes(urlStr: String): ByteArray {
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} from $urlStr")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
