package com.prism.launcher.social

import com.prism.core.MeshUtils
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismLogger
import com.prism.launcher.social.LykeStore
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.vpn.PrismSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Federates the Nebula feed across a Prism meshnet.
 *
 * Every device that is on a mesh automatically hosts its feed at [NEBULA_HOST_DOMAIN] over the
 * mesh's existing site-hosting path -- the same reserved-domain dispatch `AetherConnectomeHost`
 * and `PrismAiHost` use, so this needs no new transport, no new port, and no new discovery
 * mechanism. `PrismProxyServer` hands a connection whose PRISM_CONNECT handshake names this domain
 * straight to [serveHttp].
 *
 * PULL-ONLY, NO WRITE ENDPOINTS. A node publishes what its own database holds and never accepts a
 * write from anyone; to get your comment onto someone else's device, you store it locally and they
 * pull it. That removes every authentication and spam question a `POST /comment` endpoint would
 * have raised -- a peer can only ever offer you content, never place it -- and it is why a comment
 * on a remote post works at all: the comment is just a local row that happens to reference a
 * postId that originated elsewhere, and postIds are UUIDs, so it needs nothing from the author's
 * device to be valid.
 *
 * WHY EVERY NODE REPUBLISHES EVERYTHING IT HAS, not only what it authored. Two devices on a mesh
 * are not guaranteed to see each other directly. Republishing makes propagation transitive: A's
 * post reaches C through B even when A and C never connect. Duplicates are the price, and they
 * cost nothing here because every post and comment already carries a UUID primary identity
 * ([SocialPostEntity.postId] / [SocialCommentEntity.commentId]), so a repeat is recognised and
 * dropped rather than inserted twice. The feed converges regardless of mesh topology.
 *
 * NOTHING IS HOSTED WHEN OFF-MESH. [isActive] gates both the server and the poller on
 * [PrismMeshService.isOnMesh], which is true for a device that joined someone else's mesh and for
 * one hosting its own with no peers yet -- being on a meshnet is the requirement, not which end
 * of it you are.
 */
object NebulaMeshSync {

    private const val TAG = "NebulaMeshSync"

    /**
     * Reserved PRISM_CONNECT domain for the Nebula feed. Resolved by `PrismProxyServer`'s
     * dispatch, never by DNS -- the same arrangement as `AetherMeshSync.AETHER_HOST_DOMAIN`.
     */
    const val NEBULA_HOST_DOMAIN = "nebulasocial.com"

    /** Next free mesh opcode after 0x0C (model announce) and 0x0D (Aether announce). */
    const val OPCODE_NEBULA_ANNOUNCE: Byte = 0x0E

    private const val STALE_MS = 10 * 60 * 1000L

    /**
     * Caps on one federation response. Bounded rather than watermarked by timestamp: a peer can
     * learn an OLD item from a third device long after we last synced with them, and a
     * "give me everything since T" query would skip exactly those. Re-sending a bounded recent
     * window every time is a few hundred rows of JSON and is correct under any propagation order.
     */
    private const val MAX_POSTS = 200
    private const val MAX_COMMENTS = 600

    data class NebulaPeerInfo(val peerIp: String, val postCount: Int, val timestamp: Long)

    private val peers = mutableMapOf<String, NebulaPeerInfo>()
    private val _peers = MutableStateFlow<Map<String, NebulaPeerInfo>>(emptyMap())
    val peersFlow: StateFlow<Map<String, NebulaPeerInfo>> = _peers

    private val syncing = AtomicBoolean(false)

    /** Set after a sync that actually inserted something, so the feed UI knows to reload. */
    private val _lastChangeAt = MutableStateFlow(0L)
    val lastChangeAt: StateFlow<Long> = _lastChangeAt

    var lastStatus: String = "idle"
        private set

    /** Hosting and syncing are both gated on genuinely being on a mesh -- see the class doc. */
    fun isActive(): Boolean = PrismMeshService.isOnMesh()

    // -- Presence -------------------------------------------------------------------------

    /** Gossips that this device is serving a Nebula feed. No-op when off-mesh. */
    suspend fun announce() {
        if (!isActive()) return
        try {
            val myIp = MeshUtils.getLocalMeshIp()
            val count = AppDatabase.get().socialDao().countPosts()
            peers[myIp] = NebulaPeerInfo(myIp, count, System.currentTimeMillis())
            _peers.value = peers.toMap()
            PrismMeshService.broadcastToOthers(
                OPCODE_NEBULA_ANNOUNCE,
                JSONObject().put("posts", count).toString()
            )
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "announce failed", e)
        }
    }

    /** Called by [PrismMeshService] when a peer's Nebula-announce packet arrives. */
    fun ingestFromPeer(peerIp: String, postCount: Int) {
        peers[peerIp] = NebulaPeerInfo(peerIp, postCount, System.currentTimeMillis())
        _peers.value = peers.toMap()
    }

    fun getAll(): List<NebulaPeerInfo> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val fresh = peers.filterValues { it.timestamp >= cutoff }
        if (fresh.size != peers.size) {
            peers.keys.retainAll(fresh.keys)
            _peers.value = peers.toMap()
        }
        return fresh.values.toList()
    }

    // -- Serving (this device's feed, to the mesh) -----------------------------------------

    /** [preReadHeader]: see PrismSearchServer.serveHttp -- the proxy's browser path consumes the
     * request line before dispatching, so reading the stream again finds nothing and every request
     * comes back 405. */
    suspend fun serveHttp(socket: Socket, preReadHeader: String? = null) {
        socket.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = preReadHeader
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: reader.readLine()
                ?: return
            if (preReadHeader == null) {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(s, 405, "text/plain", "Method Not Allowed".toByteArray())
                return
            }
            // A peer that has turned Nebula federation off should look absent, not empty.
            if (!isActive()) {
                respond(s, 503, "text/plain", "Not on a mesh".toByteArray())
                return
            }
            val path = parts[1].substringBefore('?')
            try {
                when (path) {
                    "/", "/index.html" -> respond(s, 200, "text/html; charset=utf-8", renderFeedPage().toByteArray())
                    "/api/feed" -> respond(s, 200, "application/json", feedJson().toByteArray())

                    // Lyke: the catalogue, then the files themselves. Split in two because the
                    // catalogue is a few kilobytes fetched from every peer on every sync, while a
                    // video is megabytes fetched once and only when someone actually watches it.
                    "/api/lyke" -> respond(s, 200, "application/json", lykeJson().toByteArray())

                    else -> if (path.startsWith("/api/lyke/video/")) {
                        serveLykeVideo(s, path.removePrefix("/api/lyke/video/"))
                    } else {
                        respond(s, 404, "text/plain", "Not Found".toByteArray())
                    }
                }
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Serving $path failed", e)
                respond(s, 500, "text/plain", "Internal error".toByteArray())
            }
        }
    }

    /**
     * This device's Lyke catalogue: videos it can serve, plus the likes, comments and follows it
     * knows about.
     *
     * METADATA ONLY. A peer merges this on every sync, so putting video bytes in here would mean
     * shipping every file to every peer whether or not anyone watches it — which is both the
     * battery and the bandwidth gone. The files are fetched individually, on demand.
     *
     * Only videos with a local file are listed. Announcing one this device cannot actually serve
     * sends other people's players to a dead end, which is indistinguishable from the feature
     * being broken.
     */
    private fun lykeJson(): String {
        val videos = JSONArray()
        for (video in LykeStore.videos()) {
            if (video.localPath.isBlank() || !java.io.File(video.localPath).isFile) continue
            videos.put(
                JSONObject()
                    .put("id", video.id)
                    .put("authorId", video.authorId)
                    .put("authorName", video.authorName)
                    .put("caption", video.caption)
                    .put("createdAt", video.createdAt)
                    .put("hosts", JSONArray().also { h -> video.hosts.forEach { h.put(it) } })
            )
        }

        val social = JSONArray()
        for (video in LykeStore.videos()) {
            val entry = JSONObject()
                .put("videoId", video.id)
                .put("likes", JSONArray().also { a -> LykeStore.likerIds(video.id).forEach { a.put(it) } })
            val comments = JSONArray()
            for (c in LykeStore.comments(video.id)) {
                comments.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("videoId", c.videoId)
                        .put("authorId", c.authorId)
                        .put("authorName", c.authorName)
                        .put("text", c.text)
                        .put("createdAt", c.createdAt)
                )
            }
            entry.put("comments", comments)
            social.put(entry)
        }

        return JSONObject()
            .put("userId", LykeStore.userId())
            .put("userName", LykeStore.userName())
            // Who this device follows, so the other end can work out who follows IT -- which is
            // the only way the Followers tab can ever be populated.
            .put("following", JSONArray().also { a -> LykeStore.following().forEach { a.put(it) } })
            .put("videos", videos)
            .put("social", social)
            .toString()
    }

    /**
     * Streams one video file.
     *
     * Streamed in chunks rather than read whole: a phone serving a 40 MB clip from a byte array
     * holds all of it in memory while the socket drains, and several peers watching at once is
     * exactly when that matters.
     */
    private fun serveLykeVideo(socket: Socket, videoId: String) {
        val video = LykeStore.videos().firstOrNull { it.id == videoId }
        val file = video?.localPath?.let { java.io.File(it) }
        if (file == null || !file.isFile) {
            respond(socket, 404, "text/plain", "No such video".toByteArray())
            return
        }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n" +
                "Content-Length: ${file.length()}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private suspend fun feedJson(): String {
        val dao = AppDatabase.get().socialDao()
        val posts = dao.recentPosts(MAX_POSTS)
        val comments = dao.recentComments(MAX_COMMENTS)
        val bots = dao.getAllBots()

        val postArr = JSONArray()
        posts.forEach { p ->
            postArr.put(JSONObject().apply {
                put("postId", p.postId); put("authorId", p.authorId)
                put("authorName", p.authorName); put("authorHandle", p.authorHandle)
                putOpt("authorAvatarUrl", p.authorAvatarUrl)
                put("content", p.content); putOpt("imageUrl", p.imageUrl)
                put("timestamp", p.timestamp)
                put("likesCount", p.likesCount); put("repostCount", p.repostCount)
            })
        }
        val commentArr = JSONArray()
        comments.forEach { c ->
            commentArr.put(JSONObject().apply {
                put("commentId", c.commentId); put("postId", c.postId)
                putOpt("parentCommentId", c.parentCommentId)
                put("authorId", c.authorId); put("authorName", c.authorName)
                put("authorHandle", c.authorHandle); putOpt("authorAvatarUrl", c.authorAvatarUrl)
                put("content", c.content); put("timestamp", c.timestamp)
            })
        }
        val botArr = JSONArray()
        bots.forEach { b ->
            botArr.put(JSONObject().apply {
                put("botId", b.botId); put("name", b.name); put("handle", b.handle)
                put("bio", b.bio); putOpt("avatarUrl", b.avatarUrl)
                put("personaType", b.personaType); put("personality", b.personality)
            })
        }
        return JSONObject()
            .put("origin", MeshUtils.getLocalMeshIp())
            .put("posts", postArr)
            .put("comments", commentArr)
            .put("bots", botArr)
            .toString()
    }

    // -- Pulling (peers' feeds, into this device) ------------------------------------------

    /**
     * Pulls every known mesh peer's feed and merges it locally. Safe to call often -- it
     * self-serialises on [syncing], and every insert is de-duplicated by UUID.
     *
     * Returns how many new rows landed, so a caller can decide whether to redraw.
     */
    suspend fun syncOnce(): Int {
        if (!isActive() || !syncing.compareAndSet(false, true)) return 0
        return try {
            // Peers that announced Nebula specifically, plus anyone the mesh knows about: a device
            // that just joined has not necessarily gossiped its announce yet, and asking a peer
            // that isn't serving Nebula simply gets a 404/503.
            val candidates = (getAll().map { it.peerIp } + PrismMeshService.activePeerIps())
                .distinct()
                .filter { it.isNotBlank() && it != MeshUtils.getLocalMeshIp() }

            var inserted = 0
            for (ip in candidates) {
                val body = requestOverMesh(ip, "/api/feed") ?: continue
                inserted += merge(String(body, Charsets.UTF_8))

                // Lyke rides the same pass. A peer that does not serve it simply 404s, which costs
                // one request and keeps this from needing its own discovery.
                requestOverMesh(ip, "/api/lyke")?.let { lyke ->
                    inserted += mergeLyke(ip, String(lyke, Charsets.UTF_8))
                }
            }
            lastStatus = if (candidates.isEmpty()) "no mesh peers to sync with"
                         else "synced ${candidates.size} peer(s), $inserted new item(s)"
            if (inserted > 0) _lastChangeAt.value = System.currentTimeMillis()
            inserted
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "syncOnce failed", e)
            lastStatus = "sync failed: ${e.message}"
            0
        } finally {
            syncing.set(false)
        }
    }

    /**
     * Merges a peer's Lyke catalogue.
     *
     * Videos arrive as metadata with [peerIp] recorded as somewhere they can be fetched from. The
     * FILE IS NOT PULLED HERE: syncing runs in the background on a timer, and downloading every
     * video every peer has would fill the device with clips nobody asked to watch. The player
     * fetches on demand and mirroring is what makes a copy permanent.
     */
    private fun mergeLyke(peerIp: String, json: String): Int {
        return runCatching {
            val root = JSONObject(json)
            var added = 0

            root.optJSONArray("videos")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    if (LykeStore.knowsVideo(o.optString("id"))) continue
                    LykeStore.rememberRemoteVideo(
                        id = o.optString("id"),
                        authorId = o.optString("authorId"),
                        authorName = o.optString("authorName"),
                        caption = o.optString("caption"),
                        createdAt = o.optLong("createdAt"),
                        peerIp = peerIp,
                    )
                    added++
                }
            }

            root.optJSONArray("social")?.let { array ->
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val videoId = entry.optString("videoId")

                    entry.optJSONArray("likes")?.let { likes ->
                        val ids = (0 until likes.length()).map { likes.optString(it) }
                        LykeStore.mergeLikes(videoId, ids)
                    }
                    entry.optJSONArray("comments")?.let { comments ->
                        for (c in 0 until comments.length()) {
                            val o = comments.optJSONObject(c) ?: continue
                            if (LykeStore.mergeComment(
                                    id = o.optString("id"),
                                    videoId = o.optString("videoId"),
                                    authorId = o.optString("authorId"),
                                    authorName = o.optString("authorName"),
                                    text = o.optString("text"),
                                    createdAt = o.optLong("createdAt"),
                                )
                            ) added++
                        }
                    }
                }
            }

            // If this peer follows us, they are one of our followers. Derived rather than sent,
            // because a device claiming to be followed by someone would be trivially forgeable.
            val theirId = root.optString("userId")
            root.optJSONArray("following")?.let { following ->
                val theyFollow = (0 until following.length()).map { following.optString(it) }
                if (LykeStore.userId() in theyFollow && theirId.isNotBlank()) {
                    LykeStore.rememberFollower(theirId)
                }
            }
            added
        }.onFailure {
            PrismLogger.logWarning(TAG, "Lyke merge from $peerIp failed: ${it.message}")
        }.getOrDefault(0)
    }

    /** Inserts anything in [json] this device doesn't already have. Dedupe is by UUID. */
    private suspend fun merge(json: String): Int {
        val dao = AppDatabase.get().socialDao()
        val root = JSONObject(json)
        var inserted = 0

        root.optJSONArray("bots")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("botId", "")
                if (id.isBlank() || dao.getBot(id) != null) continue
                dao.insertBotIfNew(
                    SocialBotEntity(
                        botId = id,
                        name = o.optString("name", "Someone"),
                        handle = o.optString("handle", "@someone"),
                        bio = o.optString("bio", ""),
                        avatarUrl = o.optStringOrNull("avatarUrl"),
                        personaType = o.optString("personaType", "vibe"),
                        // Carried across so a remote persona keeps ITS voice if this device ever
                        // generates a reply in its name, rather than reverting to a generic one.
                        personality = o.optString("personality", ""),
                        lastPostTime = o.optLong("lastPostTime", 0L)
                    )
                )
                inserted++
            }
        }

        root.optJSONArray("posts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("postId", "")
                if (id.isBlank() || dao.getPostById(id) != null) continue
                dao.insertPostIfNew(
                    SocialPostEntity(
                        postId = id,
                        authorId = o.optString("authorId", ""),
                        authorName = o.optString("authorName", "Someone"),
                        authorHandle = o.optString("authorHandle", "@someone"),
                        authorAvatarUrl = o.optStringOrNull("authorAvatarUrl"),
                        content = o.optString("content", ""),
                        imageUrl = o.optStringOrNull("imageUrl"),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        likesCount = o.optInt("likesCount", 0),
                        repostCount = o.optInt("repostCount", 0),
                        // Never inherited from the wire: this flag means "authored by the person
                        // holding THIS device", and a post that arrived from a peer never is.
                        isUserPost = false
                    )
                )
                inserted++
            }
        }

        root.optJSONArray("comments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("commentId", "")
                if (id.isBlank() || dao.getCommentById(id) != null) continue
                dao.insertCommentIfNew(
                    SocialCommentEntity(
                        commentId = id,
                        postId = o.optString("postId", ""),
                        parentCommentId = o.optStringOrNull("parentCommentId"),
                        authorId = o.optString("authorId", ""),
                        authorName = o.optString("authorName", "Someone"),
                        authorHandle = o.optString("authorHandle", "@someone"),
                        authorAvatarUrl = o.optStringOrNull("authorAvatarUrl"),
                        content = o.optString("content", ""),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis())
                    )
                )
                inserted++
            }
        }
        return inserted
    }

    /**
     * One request through the mesh tunnel. Mirrors `AetherMeshSync.requestOverMesh`: a mesh peer's
     * address lives inside the tunnel, so a plain HTTP connection to it would try to leave the
     * mesh entirely and usually fail (NAT, no route). [PrismSocket.setHostHint] is what makes the
     * far end's `PrismProxyServer` dispatch to [serveHttp] rather than to the ordinary web host.
     */
    /**
     * Downloads a video from a peer that has it, and records it locally.
     *
     * ON DEMAND, NOT DURING SYNC. The catalogue arrives on a timer; the bytes arrive when someone
     * actually watches. Pulling every file the mesh knows about would fill the device with clips
     * nobody asked for, which is the difference between a feed and a mirror.
     *
     * Blocking; the caller is expected to be off the main thread.
     */
    fun fetchLykeVideo(videoId: String, into: java.io.File): Boolean {
        if (!isActive()) return false

        // Every host claim, then anyone else on the mesh: a claim can be stale, and a peer that
        // mirrored the video since the last sync will serve it without having announced yet.
        val candidates = (LykeStore.hostsFor(videoId) + PrismMeshService.activePeerIps())
            .distinct()
            .filter { it.isNotBlank() && it != MeshUtils.getLocalMeshIp() && it.contains('.') }

        for (ip in candidates) {
            val body = runCatching { requestOverMesh(ip, "/api/lyke/video/$videoId") }.getOrNull()
            if (body == null || body.size < 1024) continue     // a 404 body is not a video
            return runCatching {
                into.parentFile?.mkdirs()
                into.writeBytes(body)
                LykeStore.attachLocalFile(videoId, into.absolutePath)
                PrismLogger.logInfo(TAG, "Fetched Lyke video $videoId from $ip (${body.size} bytes)")
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun requestOverMesh(peerIp: String, path: String): ByteArray? {
        val socket = PrismSocket()
        socket.setHostHint(NEBULA_HOST_DOMAIN)
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(peerIp), 8080), 8000)
            socket.soTimeout = 15000
            socket.getOutputStream().apply {
                write("GET $path\r\nHost: $NEBULA_HOST_DOMAIN\r\n\r\n".toByteArray())
                flush()
            }
            val (status, body) = readHttpResponse(socket.getInputStream())
            if (status != 200) null else body
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    // -- The hosted page -------------------------------------------------------------------

    /**
     * The feed as a plain page, for anyone who browses to http://nebulasocial.com from a mesh
     * peer. Deliberately self-contained (no external CSS/JS/fonts): a mesh browser may have no
     * route off the mesh at all, so anything fetched from the open internet would simply not load.
     */
    private suspend fun renderFeedPage(): String {
        val dao = AppDatabase.get().socialDao()
        val posts = dao.recentPosts(MAX_POSTS)
        val commentsByPost = dao.recentComments(MAX_COMMENTS).groupBy { it.postId }

        val body = StringBuilder()
        for (p in posts) {
            body.append("<article><header><b>").append(esc(p.authorName)).append("</b> <span class=h>")
                .append(esc(p.authorHandle)).append("</span></header><p>")
                .append(esc(p.content)).append("</p>")
            val replies = commentsByPost[p.postId].orEmpty().sortedBy { it.timestamp }
            if (replies.isNotEmpty()) {
                body.append("<ul>")
                for (c in replies) {
                    body.append("<li><b>").append(esc(c.authorName)).append("</b> <span class=h>")
                        .append(esc(c.authorHandle)).append("</span><br>")
                        .append(esc(c.content)).append("</li>")
                }
                body.append("</ul>")
            }
            body.append("</article>")
        }
        if (posts.isEmpty()) body.append("<p class=h>No posts yet.</p>")

        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nebula Social</title><style>
:root{color-scheme:dark light}
body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:16px;max-width:680px;margin-inline:auto;
background:#0f1115;color:#e6e8ee}
h1{font-size:20px;margin:0 0 16px}
article{border:1px solid #262a35;border-radius:12px;padding:12px 14px;margin-bottom:12px}
article p{margin:6px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}
.h{color:#8b93a7;font-weight:400}
ul{list-style:none;margin:10px 0 0;padding:10px 0 0;border-top:1px solid #262a35}
li{margin:0 0 10px;font-size:15px;overflow-wrap:anywhere}
@media(prefers-color-scheme:light){body{background:#fff;color:#14161c}
article{border-color:#e2e5ec}ul{border-color:#e2e5ec}.h{color:#606a80}}
</style></head><body>
<h1>Nebula Social <span class="h">· ${esc(MeshUtils.getLocalMeshIp())}</span></h1>
$body
</body></html>"""
    }

    /** Nullable-column accessor: [JSONObject.optString] requires a non-null fallback, and a blank
     * value and an absent one mean the same thing for every optional field federated here. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // -- HTTP plumbing ---------------------------------------------------------------------

    private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            503 -> "Service Unavailable"; else -> "Error"
        }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun readHttpResponse(input: InputStream): Pair<Int, ByteArray> {
        fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val status = readLine().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        var contentLength = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val h = line.split(":", limit = 2)
            if (h.size == 2 && h[0].trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = h[1].trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return status to body
    }
}
