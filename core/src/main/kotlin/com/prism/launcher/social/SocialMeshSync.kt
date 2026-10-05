package com.prism.launcher.social

import com.prism.core.MeshCore
import com.prism.core.MeshMembership
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Nebula posts and Lyke videos, shared across the meshnet in both directions.
 *
 * ## What was there before, and why it was not sharing
 *
 * `NebulaMeshSync` on Android announced a post COUNT -- a presence signal, so a device could see that a
 * peer had forty posts. It never exchanged a single post. Lyke had the storage for it (`LykeStore` has
 * `knowsVideo`, `rememberRemoteVideo` and `hostsFor`, all shaped for remote content) and nothing to fill
 * it. And `SocialDao.insertPostIfNew` was written with IGNORE rather than REPLACE, with a comment saying
 * "a federated pull must never overwrite a local row" -- the groundwork was laid and the pull was missing.
 *
 * Worse, Nebula's announce opcode was 0x0E, which `P2pCoinOffers` already owned; on Android the
 * dispatcher's `when` reached the coin-offer branch first, so even the count never arrived. That is fixed
 * separately -- Nebula is 0x31 now -- and this is what there is to arrive.
 *
 * ## Have-lists rather than pushing content
 *
 * A device announces WHAT IT HAS, as ids. A peer compares that against its own store and asks for what it
 * lacks. Nobody pushes content at anybody.
 *
 * That matters for three reasons. A broadcast carrying posts would send every post to every peer whether
 * they had it or not, and mesh gossip is UDP on a shared network. A peer that already has a post should
 * cost nothing. And ids are small: a thousand of them fit in a datagram where one post with an image URL
 * might not.
 *
 * ## Video FILES are not sent here, and that is deliberate
 *
 * A Lyke video is megabytes. What crosses the mesh is its metadata plus the address of a device that
 * holds it, which is exactly what [LykeStore.rememberRemoteVideo] stores; the bytes are pulled on demand
 * over PRISM_CONNECT when somebody actually watches it. Sending video in gossip would fill the network to
 * deliver files most peers will never open.
 *
 * ## Why it only runs on the overlay
 *
 * [MeshMembership] gates it. Sharing a user's social content with whatever else is on a café Wi-Fi is the
 * same mistake as pairing with it -- and this content includes their own posts and videos. On the mesh it
 * is sharing with devices that joined a network the user set up; off it, it would be broadcasting.
 */
object SocialMeshSync {

    private const val TAG = "PrismSocialMesh"

    /** Free opcodes, above the block the mesh market uses. */
    const val OPCODE_ANNOUNCE: Byte = 0x32
    const val OPCODE_REQUEST: Byte = 0x33
    const val OPCODE_DELIVER: Byte = 0x34

    /**
     * How many ids go in one announcement.
     *
     * A datagram is 1500 bytes at best and mesh payloads are UTF-8 JSON. 120 ids of 36 characters is
     * already more than fits comfortably, so the newest are announced and older content spreads as it is
     * asked for rather than all at once.
     */
    private const val ANNOUNCE_LIMIT = 60

    /** How many records one delivery carries, for the same reason. */
    private const val DELIVER_LIMIT = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when this device has published something, so the next announcement goes out promptly. */
    @Volatile
    private var dirty = true

    var received = 0L
        private set

    var shared = 0L
        private set

    @Volatile
    var lastStatus: String = "idle"
        private set

    // ── Wiring ─────────────────────────────────────────────────────────────

    @Volatile
    private var installed = false

    /**
     * Registers the three opcodes with [MeshCore].
     *
     * Android does not call this -- its own service dispatches to the same functions, for the reason
     * `PrismMeshService` documents: two sockets on UDP 8081 in one process fight.
     */
    fun install(): Boolean {
        if (installed) return false
        installed = true

        MeshCore.register(OPCODE_ANNOUNCE) { peerIp, payload -> onAnnounce(peerIp, payload); true }
        MeshCore.register(OPCODE_REQUEST) { peerIp, payload -> onRequest(peerIp, payload); true }
        MeshCore.register(OPCODE_DELIVER) { peerIp, payload -> onDeliver(peerIp, payload); true }

        PrismPlatform.log.info(TAG, "Nebula and Lyke sharing is on the wire.")
        return true
    }

    /** Called when this device publishes, so the change is announced without waiting for the timer. */
    fun markChanged() {
        dirty = true
    }

    // ── Announcing ─────────────────────────────────────────────────────────

    /**
     * Tells the mesh what this device holds.
     *
     * Runs on a timer as well as on change: gossip has no delivery guarantee and a peer that joined after
     * the last announcement would otherwise never learn about anything until this device happened to
     * publish again.
     */
    fun announce() {
        if (!MeshMembership.isOnOverlay()) {
            lastStatus = "off the overlay"
            return
        }
        scope.launch {
            runCatching {
                val payload = JSONObject().apply {
                    put("posts", JSONArray().also { array -> postIds().forEach { array.put(it) } })
                    put("videos", JSONArray().also { array -> videoIds().forEach { array.put(it) } })
                }.toString()
                MeshTransport.announce(OPCODE_ANNOUNCE, payload)
                dirty = false
                lastStatus = "announced"
            }.onFailure { lastStatus = "announce failed: " + it.message }
        }
    }

    private suspend fun postIds(): List<String> = runCatching {
        AppDatabase.get().socialDao().recentPosts(ANNOUNCE_LIMIT).map { it.postId }
    }.getOrDefault(emptyList())

    private fun videoIds(): List<String> = runCatching {
        LykeStore.videos().take(ANNOUNCE_LIMIT).map { it.id }
    }.getOrDefault(emptyList())

    // ── Receiving an announcement ──────────────────────────────────────────

    /**
     * A peer said what it has. Ask for whatever this device does not.
     *
     * THE COMPARISON HAPPENS HERE AND NOT ON THE SENDER, which is what makes this bidirectional without
     * any negotiation: both devices announce, both compare, both ask. Neither is the server.
     */
    fun onAnnounce(peerIp: String, payload: String) {
        if (!MeshMembership.isOverlayAddress(peerIp)) return
        scope.launch {
            runCatching {
                val json = JSONObject(payload)
                val wantedPosts = ids(json.optJSONArray("posts")).filterNot { knowsPost(it) }
                val wantedVideos = ids(json.optJSONArray("videos")).filterNot { LykeStore.knowsVideo(it) }
                if (wantedPosts.isEmpty() && wantedVideos.isEmpty()) return@runCatching

                val request = JSONObject().apply {
                    put("posts", JSONArray().also { a -> wantedPosts.take(DELIVER_LIMIT).forEach { a.put(it) } })
                    put("videos", JSONArray().also { a -> wantedVideos.take(DELIVER_LIMIT).forEach { a.put(it) } })
                }.toString()
                // Point to point: only the peer that has it can answer, and broadcasting the request
                // would have every other device parse something that is none of their business.
                MeshTransport.sendToPeer(peerIp, OPCODE_REQUEST, request)
                lastStatus = "asked " + peerIp + " for " +
                    (wantedPosts.size + wantedVideos.size) + " item(s)"
            }
        }
    }

    // ── Serving a request ──────────────────────────────────────────────────

    /** A peer asked for specific ids. Send what this device actually has, and nothing else. */
    fun onRequest(peerIp: String, payload: String) {
        if (!MeshMembership.isOverlayAddress(peerIp)) return
        scope.launch {
            runCatching {
                val json = JSONObject(payload)
                val dao = AppDatabase.get().socialDao()

                val posts = JSONArray()
                ids(json.optJSONArray("posts")).take(DELIVER_LIMIT).forEach { id ->
                    val post = runCatching { dao.getPostById(id) }.getOrNull() ?: return@forEach
                    posts.put(encodePost(post))
                }

                val videos = JSONArray()
                ids(json.optJSONArray("videos")).take(DELIVER_LIMIT).forEach { id ->
                    val video = LykeStore.videos().firstOrNull { it.id == id } ?: return@forEach
                    videos.put(encodeVideo(video))
                }

                if (posts.length() == 0 && videos.length() == 0) return@runCatching

                val reply = JSONObject().apply {
                    put("posts", posts)
                    put("videos", videos)
                }.toString()
                MeshTransport.sendToPeer(peerIp, OPCODE_DELIVER, reply)
                shared += (posts.length() + videos.length()).toLong()
                lastStatus = "sent " + (posts.length() + videos.length()) + " item(s) to " + peerIp
            }
        }
    }

    // ── Taking delivery ────────────────────────────────────────────────────

    /**
     * Content arrived. Store what is new, and never overwrite what is local.
     *
     * `insertPostIfNew` is IGNORE rather than REPLACE, so a peer's copy of a post this device already has
     * -- with their like count, their edits -- cannot clobber the local row. A video is remembered with
     * the peer as a HOST rather than as a file: the bytes are fetched when somebody watches it.
     */
    fun onDeliver(peerIp: String, payload: String) {
        if (!MeshMembership.isOverlayAddress(peerIp)) return
        scope.launch {
            runCatching {
                val json = JSONObject(payload)
                val dao = AppDatabase.get().socialDao()
                var stored = 0

                val posts = json.optJSONArray("posts")
                for (index in 0 until (posts?.length() ?: 0)) {
                    val item = posts?.optJSONObject(index) ?: continue
                    val post = decodePost(item) ?: continue
                    runCatching { dao.insertPostIfNew(post) }.onSuccess { stored++ }
                }

                val videos = json.optJSONArray("videos")
                for (index in 0 until (videos?.length() ?: 0)) {
                    val item = videos?.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank() || LykeStore.knowsVideo(id)) continue
                    runCatching {
                        LykeStore.rememberRemoteVideo(
                            id = id,
                            authorId = item.optString("authorId"),
                            authorName = item.optString("authorName"),
                            caption = item.optString("caption"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            peerIp = peerIp,
                        )
                        stored++
                    }
                }

                if (stored > 0) {
                    received += stored.toLong()
                    lastStatus = "stored " + stored + " item(s) from " + peerIp
                    PrismPlatform.log.info(TAG, lastStatus)
                    onChanged?.invoke()
                }
            }
        }
    }

    /** Called after new content lands, so a feed on screen can refresh. */
    var onChanged: (() -> Unit)? = null

    // ── The timer ──────────────────────────────────────────────────────────

    /**
     * Announces periodically, and sooner when something changed.
     *
     * A daemon thread rather than a coroutine on a shared dispatcher, so it cannot keep a JVM alive and
     * cannot be starved by whatever else is queued on IO.
     */
    fun startAnnouncing(intervalSeconds: Long = 90) {
        Thread({
            while (true) {
                runCatching {
                    // Sooner when dirty: publishing a post and waiting ninety seconds for anybody to
                    // hear about it makes the feature feel broken even though it works.
                    java.util.concurrent.TimeUnit.SECONDS.sleep(if (dirty) 5 else intervalSeconds)
                    announce()
                }
            }
        }, "social-mesh-announce").apply { isDaemon = true }.start()
    }

    // ── Encoding ───────────────────────────────────────────────────────────

    private fun ids(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private suspend fun knowsPost(id: String): Boolean = runCatching {
        AppDatabase.get().socialDao().getPostById(id) != null
    }.getOrDefault(false)

    /**
     * A post on the wire.
     *
     * THE LOCAL ROW ID IS NOT SENT. It is an autoincrement primary key and means nothing on another
     * device; sending it would invite a receiver to insert with it and collide with its own rows.
     * `isUserPost` is also not sent, and that is the important one: a post authored by the user of ONE
     * device is not authored by the user of another, and carrying the flag would make a peer's post
     * appear in this user's own-posts view.
     */
    private fun encodePost(post: SocialPostEntity): JSONObject = JSONObject().apply {
        put("postId", post.postId)
        put("authorId", post.authorId)
        put("authorName", post.authorName)
        put("authorHandle", post.authorHandle)
        put("authorAvatarUrl", post.authorAvatarUrl.orEmpty())
        put("content", post.content)
        put("imageUrl", post.imageUrl.orEmpty())
        put("timestamp", post.timestamp)
        put("likesCount", post.likesCount)
        put("repostCount", post.repostCount)
    }

    private fun decodePost(json: JSONObject): SocialPostEntity? {
        val postId = json.optString("postId")
        if (postId.isBlank()) return null
        val content = json.optString("content")
        if (content.isBlank() && json.optString("imageUrl").isBlank()) return null
        return SocialPostEntity(
            postId = postId,
            authorId = json.optString("authorId"),
            authorName = json.optString("authorName"),
            authorHandle = json.optString("authorHandle"),
            authorAvatarUrl = json.optString("authorAvatarUrl").takeIf { it.isNotBlank() },
            content = content,
            imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() },
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            likesCount = json.optInt("likesCount", 0),
            repostCount = json.optInt("repostCount", 0),
            // Never true for something that arrived from elsewhere. See encodePost.
            isUserPost = false,
        )
    }

    private fun encodeVideo(video: LykeStore.Video): JSONObject = JSONObject().apply {
        put("id", video.id)
        put("authorId", video.authorId)
        put("authorName", video.authorName)
        put("caption", video.caption)
        put("createdAt", video.createdAt)
        // No path and no bytes: the file is fetched from a host when it is watched.
    }

    /** One line for a diagnostics page. */
    fun describe(): String = buildString {
        append(if (installed) "on the wire" else "not registered")
        append(" · ")
        append(MeshMembership.describe())
        append(" · received ")
        append(received)
        append(", shared ")
        append(shared)
        append(" · ")
        append(lastStatus)
    }
}
