package com.prism.launcher.photos

import com.prism.core.MeshCore
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Photos and videos on the mesh, encrypted with the wallet's photo key.
 *
 * ## Why this is not [com.prism.launcher.cloud.CloudStorage]
 *
 * The mesh cloud places chunks on whichever peers are selling storage and bills for it. A photo
 * backup is a different promise: it goes to the devices the user has VOUCHED for, it is readable
 * only by the wallet that made it, and it is not paid for. Reusing the market would have meant a
 * backup that fails when nobody is selling space and that strangers hold a copy of.
 *
 * ## The key is the wallet, the same as calls
 *
 * [CloudVault.Purpose.PHOTOS] is derived from the recovery phrase, exactly like call audio and
 * messages. A trusted device already shares that phrase -- that is what trusting it means here -- so
 * it can open the backup, and a device that merely sits on the same Wi-Fi cannot. There is no new
 * secret to hand over and no key exchange to get wrong.
 *
 * ## The local copy is real
 *
 * Every backup is also kept in this device's own vault before it is sent. That is what makes the page
 * honest when a peer is offline: "backed up" means at least this copy exists, and "sent to" is a
 * separate, per-device fact. It also means the backup survives the source being deleted.
 *
 * ## Chunks are sealed individually
 *
 * The transport is 16 KB UDP datagrams. A photo is split into [CHUNK_BYTES] pieces, each sealed with
 * a fresh nonce and bound to its index as associated data, so a peer holding pieces 3 and 7 cannot
 * swap them. A missing chunk is visible in the index rather than producing a half-decoded image.
 */
object PhotoBackup {

    private const val TAG = "PrismPhotos"

    /** Listing request/response. */
    const val OPCODE_LIST: Byte = 0x59

    /** One sealed chunk on its way to a peer. */
    const val OPCODE_PUT: Byte = 0x5A

    /** Acknowledgement of a stored chunk. */
    const val OPCODE_ACK: Byte = 0x5B

    /** A request for one chunk back. */
    const val OPCODE_GET: Byte = 0x5C

    /** One sealed chunk coming back. */
    const val OPCODE_DATA: Byte = 0x5D

    /**
     * Eight kilobytes before sealing.
     *
     * Sealed adds a 12-byte nonce and a 16-byte tag, and base64 adds a third, so a full chunk is
     * about 11 KB of JSON -- comfortably inside the mesh's 16 KB datagram even with the envelope.
     */
    const val CHUNK_BYTES = 8 * 1024

    enum class Kind { IMAGE, VIDEO }

    enum class Origin {
        /** On this device, from the system media store. */
        LOCAL,

        /** In this device's backup vault. */
        BACKED_UP,

        /** Listed by another trusted device; the bytes live there. */
        REMOTE,
    }

    /**
     * One photo, from any of the three places it can be.
     *
     * The same photo appears up to three times -- once as a local file, once as a backup of it, and
     * once in each peer's listing. The page merges on [id], which is derived from the file's own
     * identity, so one photo is one tile.
     */
    data class Entry(
        val id: String,
        val name: String,
        val mime: String,
        val kind: Kind,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val takenAt: Long,
        val bytes: Long,
        val chunks: Int,
        val origin: Origin,
        /** Which device holds these bytes, when [origin] is REMOTE. */
        val ownerIp: String = "",
        val ownerName: String = "",
        val ownerFingerprint: String = "",
        /** The media store URI, when [origin] is LOCAL. */
        val localUri: String = "",
        /** A small JPEG of the frame, base64, for a tile that is not on this device. */
        val thumb: String? = null,
        /** Whether this device has a copy in its own vault. */
        val backedUp: Boolean = false,
        /** The peers that acknowledged a copy. */
        val sentTo: List<String> = emptyList(),
    ) {
        fun withOrigin(origin: Origin, ownerIp: String = this.ownerIp, ownerName: String = this.ownerName): Entry =
            copy(origin = origin, ownerIp = ownerIp, ownerName = ownerName)

        /** A stable string key for a merged list: identity does not change with where it lives. */
        val key: String get() = id
    }

    /**
     * What the platform hands over to be backed up.
     *
     * [data] is ALREADY compressed: a JPEG re-encode for a photo, the platform's transcoded stream
     * for a video, or -- when the device cannot transcode -- the original bytes, which are already
     * compressed because every video format a phone records in is. The core does not care which; it
     * seals whatever it is given and records [compressed] so the page can say what happened.
     */
    data class Payload(
        val id: String,
        val name: String,
        val mime: String,
        val kind: Kind,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val takenAt: Long,
        val data: ByteArray,
        val thumb: ByteArray? = null,
        val compressed: Boolean = true,
        val localUri: String = "",
    ) {
        // ByteArray's generated equals compares identity, which is the wrong question for a data
        // holder. These two are defined so a Payload in a set or a comparison behaves sanely.
        override fun equals(other: Any?): Boolean =
            other is Payload && other.id == id && other.data.contentEquals(data)

        override fun hashCode(): Int = id.hashCode() * 31 + data.contentHashCode()
    }

    // ── Readiness ─────────────────────────────────────────────────────────

    fun enabled(): Boolean = PrismSettings.getPhotoBackup()

    /** Why a backup cannot run, or an empty string. */
    fun unavailableReason(): String = when {
        !enabled() -> "Photo backup is off. Turn it on in Prism Settings, under Other."
        !CloudVault.isReady() -> CloudVault.unavailableReason()
        !MeshTransport.isOnMesh() -> "Not on a Prism meshnet. Backups are kept here and sent when one comes up."
        else -> ""
    }

    private fun key(): ByteArray? = CloudVault.keyFor(CloudVault.Purpose.PHOTOS)

    // ── Storage ───────────────────────────────────────────────────────────

    fun root(): File = File(PrismPlatform.host.dataDir(), "photos/backups").apply { mkdirs() }

    private fun indexFile(): File = File(PrismPlatform.host.dataDir(), "photos/index.json")

    private fun chunkFile(id: String, index: Int): File = File(File(root(), id), "$index.bin")

    /** True when this device holds a complete copy of [id]. */
    fun hasLocalCopy(id: String, chunks: Int): Boolean =
        (0 until chunks).all { chunkFile(id, it).isFile }

    /**
     * Reads the local index.
     *
     * Rebuilt from the vault rather than trusted blindly: a file deleted by the user's own file
     * manager must not keep appearing as backed up.
     */
    fun localEntries(): List<Entry> {
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val entry = decodeEntry(item, Origin.BACKED_UP) ?: return@mapNotNull null
                if (hasLocalCopy(entry.id, entry.chunks)) entry else null
            }
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not read the photo index: ${it.message}")
            emptyList()
        }
    }

    private fun writeIndex(entries: List<Entry>) = synchronized(this) {
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(encodeEntry(it)) }
            val file = indexFile()
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }.onFailure { PrismPlatform.log.warn(TAG, "Could not write the photo index: ${it.message}") }
    }

    /**
     * Stores [payload] in the local vault and returns its entry.
     *
     * The chunks are sealed with the photo key before they touch disk, so the vault is not a plain
     * copy of the camera roll that any app with file access can read.
     */
    fun storeLocally(payload: Payload): Entry? {
        val encryption = key() ?: return null
        val chunks = (payload.data.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        if (chunks == 0) return null
        val directory = File(root(), payload.id).apply { mkdirs() }

        for (index in 0 until chunks) {
            val from = index * CHUNK_BYTES
            val to = minOf(payload.data.size, from + CHUNK_BYTES)
            val plain = payload.data.copyOfRange(from, to)
            val sealed = CloudVault.seal(plain, encryption, aad(payload.id, index))
            runCatching { chunkFile(payload.id, index).writeBytes(sealed) }
                .onFailure {
                    PrismPlatform.log.warn(TAG, "Could not store chunk $index of ${payload.name}: ${it.message}")
                    return null
                }
        }

        val entry = Entry(
            id = payload.id,
            name = payload.name,
            mime = payload.mime,
            kind = payload.kind,
            width = payload.width,
            height = payload.height,
            durationMs = payload.durationMs,
            takenAt = payload.takenAt,
            bytes = payload.data.size.toLong(),
            chunks = chunks,
            origin = Origin.BACKED_UP,
            localUri = payload.localUri,
            thumb = payload.thumb?.let { Base64.getEncoder().encodeToString(it) },
            backedUp = true,
        )
        val existing = localEntries().filterNot { it.id == entry.id } + entry
        writeIndex(existing)
        return entry
    }

    /**
     * Reads one chunk of a locally held backup, decrypted.
     *
     * Null covers both "not here" and "will not decrypt", deliberately: a page cannot do anything
     * different about either, and conflating them avoids leaking which ids exist to a caller without
     * the key.
     */
    fun readLocalChunk(id: String, index: Int): ByteArray? {
        val encryption = key() ?: return null
        val file = chunkFile(id, index)
        if (!file.isFile) return null
        return runCatching {
            CloudVault.open(file.readBytes(), encryption, aad(id, index))
        }.getOrNull()
    }

    /** The whole decrypted file, or null if any chunk is missing. */
    fun readLocal(id: String, chunks: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        for (index in 0 until chunks) {
            val chunk = readLocalChunk(id, index) ?: return null
            out.write(chunk)
        }
        return out.toByteArray()
    }

    /** Removes a backup from this device. The source photo is untouched. */
    fun forget(id: String) {
        runCatching { File(root(), id).deleteRecursively() }
        writeIndex(localEntries().filterNot { it.id == id })
    }

    private fun aad(id: String, index: Int): ByteArray = "$id:$index".toByteArray()

    // ── Sending ───────────────────────────────────────────────────────────

    /**
     * Copies [entry] to every peer in [peers] that does not already have it.
     *
     * Blocking and sequential per chunk: the mesh is UDP and a phone sending eight kilobytes at a
     * time is already using the link. Returns the names of the peers that acknowledged every chunk.
     */
    fun send(entry: Entry, peers: List<Pair<String, String>>, onProgress: (Double) -> Unit = {}): List<String> {
        val encryption = key() ?: return emptyList()
        if (!MeshTransport.isOnMesh()) return emptyList()
        val accepted = ArrayList<String>()
        for ((ip, name) in peers) {
            var all = true
            for (index in 0 until entry.chunks) {
                val sealed = readLocalChunk(entry.id, index)
                if (sealed == null) {
                    all = false
                    break
                }
                val body = JSONObject().apply {
                    put("t", "put")
                    put("id", entry.id)
                    put("chunk", index)
                    put("total", entry.chunks)
                    put("meta", encodeEntry(entry))
                    put("from", TrustedDevices.localFingerprint())
                    put("data", Base64.getEncoder().encodeToString(sealed))
                }
                val packet = CloudVault.seal(body.toString().toByteArray(), encryption)
                MeshTransport.sendToPeer(ip, OPCODE_PUT, Base64.getEncoder().encodeToString(packet))
                onProgress((index + 1).toDouble() / entry.chunks)
            }
            if (all) {
                accepted += name.ifBlank { ip }
                rememberSent(entry.id, name.ifBlank { ip })
            }
        }
        return accepted
    }

    /**
     * "Sent to" is remembered per photo.
     *
     * A peer that acknowledged is not asked to acknowledge forever: the page shows which devices
     * hold a copy, and re-sending is the user's choice. Re-sending automatically on every launch
     * would saturate a mesh with data that is already there.
     */
    private val sentTo = ConcurrentHashMap<String, MutableSet<String>>()

    private fun rememberSent(id: String, peer: String) {
        sentTo.getOrPut(id) { java.util.Collections.synchronizedSet(mutableSetOf()) }.add(peer)
    }

    fun sentTo(id: String): List<String> = sentTo[id]?.toList() ?: emptyList()

    // ── Asking peers ──────────────────────────────────────────────────────

    private val pendingLists = ConcurrentHashMap<String, (List<Entry>) -> Unit>()
    private val pendingChunks = ConcurrentHashMap<String, (ByteArray?) -> Unit>()

    /**
     * Registers the mesh handlers.
     *
     * Safe to call twice: [MeshCore.register] logs a replacement, and the handler is the same
     * object, so the second call is quiet.
     */
    fun install() {
        MeshCore.register(OPCODE_LIST) { peerIp, payload -> onList(peerIp, payload); true }
        MeshCore.register(OPCODE_PUT) { peerIp, payload -> onPut(peerIp, payload); true }
        MeshCore.register(OPCODE_ACK) { _, _ -> true }
        MeshCore.register(OPCODE_GET) { peerIp, payload -> onGet(peerIp, payload); true }
        MeshCore.register(OPCODE_DATA) { peerIp, payload -> onData(peerIp, payload); true }
        PrismPlatform.log.info(TAG, "Photo backup opcodes registered")
    }

    /**
     * Opens one of our own sealed packets from an unknown address.
     *
     * This is how a peer whose IP changed is still recognised. The photo key is derived from the
     * wallet, so being able to open the packet IS the proof that the sender shares the wallet; the
     * fingerprint inside then says which trusted device it is. Without this, a phone that moved to a
     * new lease silently stopped backing up and stopped being seen, which is exactly the shape of
     * the bug the call engine has.
     */
    private fun openOwn(payload: String): Pair<JSONObject, String>? {
        val encryption = key() ?: return null
        val sealed = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        val plain = CloudVault.open(sealed, encryption) ?: return null
        val json = runCatching { JSONObject(String(plain)) }.getOrNull() ?: return null
        return json to json.optString("from")
    }

    private fun onList(peerIp: String, payload: String) {
        val (json, from) = openOwn(payload) ?: return
        if (json.optString("t") != "list") return
        val requestId = json.optString("rid")

        // A device that answered with entries: this is the response half, not a request. Routed
        // here rather than in a sixth opcode because a listing and its answer are the same packet
        // shape and separating them would be a distinction the wire does not need.
        if (json.optJSONArray("entries") != null) {
            TrustedDevices.byFingerprint(from)?.let { TrustedDevices.noteAddress(from, peerIp) }
            acceptListing(json, peerIp)
            return
        }
        val entries = localEntries()
        // Paginated so a large library does not exceed one datagram. Each page is its own packet.
        val pageSize = 8
        val pages = if (entries.isEmpty()) 1 else (entries.size + pageSize - 1) / pageSize
        val encryption = key() ?: return
        for (page in 0 until pages) {
            val array = JSONArray()
            entries.drop(page * pageSize).take(pageSize).forEach { array.put(encodeEntry(it)) }
            val body = JSONObject().apply {
                put("t", "list")
                put("rid", requestId)
                put("page", page)
                put("pages", pages)
                put("device", runCatching { PrismPlatform.host.deviceName() }.getOrDefault("Prism"))
                put("from", TrustedDevices.localFingerprint())
                put("entries", array)
            }
            val sealed = CloudVault.seal(body.toString().toByteArray(), encryption)
            MeshTransport.sendToPeer(peerIp, OPCODE_LIST, Base64.getEncoder().encodeToString(sealed))
        }
    }

    private fun onPut(peerIp: String, payload: String) {
        val (json, from) = openOwn(payload) ?: return
        if (json.optString("t") != "put") return
        // A device that shares the wallet is trusted by construction, but the record is what names
        // it on the page. Anything else is refused rather than silently stored.
        val device = TrustedDevices.byFingerprint(from)
        if (device == null) {
            PrismPlatform.log.warn(TAG, "Refused a photo chunk from an unknown device at $peerIp")
            return
        }
        TrustedDevices.noteAddress(from, peerIp)

        val id = json.optString("id")
        val index = json.optInt("chunk", -1)
        val total = json.optInt("total", 0)
        if (id.isBlank() || index < 0 || total <= 0) return
        val sealed = runCatching { Base64.getDecoder().decode(json.optString("data")) }.getOrNull() ?: return
        val encryption = key() ?: return
        // Prove it opens before storing: an unopenable chunk is not a backup, it is garbage that
        // would make `hasLocalCopy` lie.
        val plain = CloudVault.open(sealed, encryption, aad(id, index)) ?: return
        runCatching {
            File(root(), id).mkdirs()
            chunkFile(id, index).writeBytes(sealed)
        }.onFailure { return }

        val existing = localEntries()
        val entry = existing.firstOrNull { it.id == id } ?: run {
            val meta = json.optJSONObject("meta")?.let { decodeEntry(it, Origin.BACKED_UP) }
            (meta ?: Entry(
                id = id, name = id, mime = "application/octet-stream", kind = Kind.IMAGE,
                width = 0, height = 0, durationMs = 0, takenAt = System.currentTimeMillis(),
                bytes = 0, chunks = total, origin = Origin.BACKED_UP, backedUp = true,
            )).copy(chunks = total, backedUp = true)
        }
        val updated = existing.filterNot { it.id == id } + entry
        writeIndex(updated)
        PrismPlatform.log.info(TAG, "Stored photo chunk $index/$total of $id from ${device.name} (${plain.size} bytes)")

        val ack = JSONObject().apply {
            put("t", "ack")
            put("id", id)
            put("chunk", index)
            put("from", TrustedDevices.localFingerprint())
        }
        val sealedAck = CloudVault.seal(ack.toString().toByteArray(), encryption)
        MeshTransport.sendToPeer(peerIp, OPCODE_ACK, Base64.getEncoder().encodeToString(sealedAck))
    }

    private fun onGet(peerIp: String, payload: String) {
        val (json, from) = openOwn(payload) ?: return
        if (json.optString("t") != "get") return
        if (TrustedDevices.byFingerprint(from) == null) return
        val id = json.optString("id")
        val index = json.optInt("chunk", -1)
        if (id.isBlank() || index < 0) return
        val data = readLocalChunk(id, index) ?: return
        val encryption = key() ?: return
        val body = JSONObject().apply {
            put("t", "data")
            put("id", id)
            put("chunk", index)
            put("rid", json.optString("rid"))
            put("from", TrustedDevices.localFingerprint())
            put("data", Base64.getEncoder().encodeToString(data))
        }
        val sealed = CloudVault.seal(body.toString().toByteArray(), encryption, aad(id, index))
        MeshTransport.sendToPeer(peerIp, OPCODE_DATA, Base64.getEncoder().encodeToString(sealed))
    }

    private fun onData(peerIp: String, payload: String) {
        val (json, _) = openOwn(payload) ?: return
        if (json.optString("t") != "data") return
        val id = json.optString("id")
        val index = json.optInt("chunk", -1)
        val seals = runCatching { Base64.getDecoder().decode(json.optString("data")) }.getOrNull() ?: return
        val encryption = key() ?: return
        val plain = CloudVault.open(seals, encryption, aad(id, index)) ?: return
        pendingChunks.remove("$id:$index")?.invoke(plain)
        pendingChunks.remove("$id:any")?.invoke(plain)
    }

    /**
     * Asks [peerIp] for its whole listing.
     *
     * Fire-and-forget with a callback rather than a blocking call: the Photos page is composed, and
     * blocking the UI thread for a UDP round trip is how a page freezes on a lossy network.
     */
    fun requestList(peerIp: String, timeoutMs: Long = 2500, onResult: (List<Entry>) -> Unit) {
        val encryption = key()
        if (encryption == null) {
            onResult(emptyList())
            return
        }
        val requestId = java.util.UUID.randomUUID().toString()
        val collected = java.util.Collections.synchronizedList(mutableListOf<Entry>())
        pendingLists[requestId] = { entries ->
            collected.addAll(entries)
            onResult(collected.toList())
        }
        val body = JSONObject().apply {
            put("t", "list")
            put("rid", requestId)
            put("from", TrustedDevices.localFingerprint())
        }
        val sealed = CloudVault.seal(body.toString().toByteArray(), encryption)
        MeshTransport.sendToPeer(peerIp, OPCODE_LIST, Base64.getEncoder().encodeToString(sealed))

        // The timeout callback is deliberately silent when entries arrived late: partial is better
        // than nothing, and the page can refresh.
        Thread({
            runCatching { Thread.sleep(timeoutMs) }
            val callback = pendingLists.remove(requestId) ?: return@Thread
            // Whatever arrived before the deadline, not an empty list: a partial listing is useful
            // and the page merges it, while an empty one would erase tiles already on screen.
            runCatching { callback(collected.toList()) }
        }, "photo-list-timeout").apply { isDaemon = true; start() }
    }

    /** Called by [onList]'s receive path for responses. Kept separate for clarity. */
    fun acceptListing(payload: JSONObject, peerIp: String) {
        val requestId = payload.optString("rid")
        val array = payload.optJSONArray("entries") ?: return
        val device = payload.optString("device").ifBlank { peerIp }
        val entries = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            decodeEntry(item, Origin.REMOTE)?.copy(ownerIp = peerIp, ownerName = device)
        }
        pendingLists[requestId]?.invoke(entries)
    }

    /**
     * Fetches one chunk from [peerIp] and waits for it.
     *
     * Blocking with a short timeout because its only caller is the image loader, which is already on
     * a background thread. The DONE latch is per request id so two images loading at once do not
     * consume each other's packets.
     */
    fun fetchChunk(peerIp: String, id: String, index: Int, timeoutMs: Long = 4_000): ByteArray? {
        // A local copy wins: no reason to ask the network for something already here.
        readLocalChunk(id, index)?.let { return it }
        val encryption = key() ?: return null
        val latch = CountDownLatch(1)
        var result: ByteArray? = null
        pendingChunks["$id:$index"] = { bytes ->
            result = bytes
            latch.countDown()
        }
        val body = JSONObject().apply {
            put("t", "get")
            put("id", id)
            put("chunk", index)
            put("rid", "$id:$index")
            put("from", TrustedDevices.localFingerprint())
        }
        val sealed = CloudVault.seal(body.toString().toByteArray(), encryption)
        MeshTransport.sendToPeer(peerIp, OPCODE_GET, Base64.getEncoder().encodeToString(sealed))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingChunks.remove("$id:$index")
        return result
    }

    // ── Wire encoding ─────────────────────────────────────────────────────

    private fun encodeEntry(entry: Entry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("name", entry.name)
        put("mime", entry.mime)
        put("kind", entry.kind.name.lowercase())
        put("w", entry.width)
        put("h", entry.height)
        put("ms", entry.durationMs)
        put("at", entry.takenAt)
        put("bytes", entry.bytes)
        put("chunks", entry.chunks)
        entry.thumb?.let { put("thumb", it) }
    }

    private fun decodeEntry(json: JSONObject, origin: Origin): Entry? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        val kind = if (json.optString("kind").equals("video", ignoreCase = true)) Kind.VIDEO else Kind.IMAGE
        return Entry(
            id = id,
            name = json.optString("name"),
            mime = json.optString("mime"),
            kind = kind,
            width = json.optInt("w", 0),
            height = json.optInt("h", 0),
            durationMs = json.optLong("ms", 0L),
            takenAt = json.optLong("at", 0L),
            bytes = json.optLong("bytes", 0L),
            chunks = json.optInt("chunks", 0),
            origin = origin,
            thumb = json.optString("thumb").takeIf { it.isNotBlank() },
            backedUp = origin == Origin.BACKED_UP,
        )
    }

    // ── Merging ───────────────────────────────────────────────────────────

    /**
     * One tile per photo, no matter where it was found.
     *
     * The precedence is deliberate: a local copy wins because it is the original, then this device's
     * backup, then a peer's. The winner also records that the others exist, which is what the tile's
     * badge shows.
     */
    fun merge(local: List<Entry>, backedUp: List<Entry>, remote: List<Entry>): List<Entry> {
        val byKey = LinkedHashMap<String, Entry>()
        for (entry in local.sortedByDescending { it.takenAt }) {
            byKey[entry.key] = entry.copy(backedUp = backedUp.any { it.id == entry.id })
        }
        for (entry in backedUp) {
            val existing = byKey[entry.id]
            byKey[entry.id] = if (existing == null) entry else existing.copy(backedUp = true, thumb = existing.thumb ?: entry.thumb)
        }
        for (entry in remote) {
            val existing = byKey[entry.id]
            byKey[entry.id] = if (existing == null) entry else existing
        }
        return byKey.values.sortedByDescending { it.takenAt }
    }

    /** A stable id for a media-store item, so the same file keeps the same id across scans. */
    fun idFor(uri: String, name: String, size: Long, modified: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$uri|$name|$size|$modified".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
