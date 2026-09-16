package com.prism.launcher.social

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Lyke: short-form video carried over the mesh.
 *
 * ## Why this is a local store and not a client
 *
 * There is no server. A video lives on the device that recorded it and on every device that has
 * mirrored it, and the feed is assembled from whatever peers are reachable — the same shape as the
 * rest of Nebula. So this holds what THIS device knows: its own videos, the ones it has mirrored,
 * and the likes, comments and follows it has seen. Mesh sync merges other devices' copies in.
 *
 * ## Identity
 *
 * A user is a username plus a keypair-backed id, generated on first use. The default credentials
 * exist so the feature works before anyone visits settings — an empty identity would mean a video
 * that cannot be attributed and a follow that cannot be addressed.
 */
object LykeStore {

    data class Video(
        val id: String,
        val authorId: String,
        val authorName: String,
        /** Where the file is on THIS device. Empty when only the metadata has been synced. */
        val localPath: String,
        val caption: String,
        val createdAt: Long,
        /** Devices hosting this video, so a player can find a copy when the local one is absent. */
        val hosts: List<String> = emptyList(),
    )

    data class Comment(
        val id: String,
        val videoId: String,
        val authorId: String,
        val authorName: String,
        val text: String,
        val createdAt: Long,
    )

    // ── Identity ───────────────────────────────────────────────────────────

    /**
     * This device's user id, created once.
     *
     * Derived from a random value rather than from the username, because a username can change and
     * every like, follow and video already recorded has to keep pointing at the same person.
     */
    fun userId(): String {
        val existing = PrismSettings.getLykeUserId()
        if (existing.isNotBlank()) return existing
        val fresh = "lyke-" + java.util.UUID.randomUUID().toString().take(12)
        PrismSettings.setLykeUserId(fresh)
        return fresh
    }

    fun userName(): String {
        val existing = PrismSettings.getLykeUserName()
        if (existing.isNotBlank()) return existing
        // A DEFAULT RATHER THAN A PROMPT AT STARTUP. Somebody who opens Lyke to watch should not be
        // stopped by a form; the name is theirs to change when they first post.
        val generated = "prism" + userId().takeLast(6)
        PrismSettings.setLykeUserName(generated)
        return generated
    }

    fun isFirstRun(): Boolean = !PrismSettings.getLykeProfileConfirmed()

    fun confirmProfile(name: String, avatarUri: String) {
        if (name.isNotBlank()) PrismSettings.setLykeUserName(name.trim())
        PrismSettings.setLykeAvatar(avatarUri)
        PrismSettings.setLykeProfileConfirmed(true)
    }

    // ── Videos ─────────────────────────────────────────────────────────────

    fun videos(): List<Video> = readVideos().sortedByDescending { it.createdAt }

    fun addVideo(localPath: String, caption: String): Video {
        val video = Video(
            id = "vid-" + java.util.UUID.randomUUID().toString().take(12),
            authorId = userId(),
            authorName = userName(),
            localPath = localPath,
            caption = caption,
            createdAt = System.currentTimeMillis(),
            hosts = listOf(userId()),
        )
        writeVideos(readVideos() + video)
        return video
    }

    /**
     * Records that this device now hosts [video] — the mirror action.
     *
     * The FILE is copied by the caller; this only records the claim, because a host list that
     * included devices without the bytes would send other people's players to a dead end.
     */
    fun mirror(video: Video, localPath: String) {
        val all = readVideos().toMutableList()
        val index = all.indexOfFirst { it.id == video.id }
        val hosted = video.copy(
            localPath = localPath,
            hosts = (video.hosts + userId()).distinct(),
        )
        if (index >= 0) all[index] = hosted else all.add(hosted)
        writeVideos(all)
    }

    fun knowsVideo(id: String): Boolean = readVideos().any { it.id == id }

    /**
     * Records a video a peer has, without its bytes.
     *
     * [peerIp] goes into the host list so a player knows where to fetch it. A remote entry has an
     * empty localPath, which is what everything else keys off to tell "known about" from "held".
     */
    fun rememberRemoteVideo(
        id: String,
        authorId: String,
        authorName: String,
        caption: String,
        createdAt: Long,
        peerIp: String,
    ) {
        if (id.isBlank() || knowsVideo(id)) return
        writeVideos(
            readVideos() + Video(
                id = id,
                authorId = authorId,
                authorName = authorName,
                localPath = "",
                caption = caption,
                createdAt = createdAt,
                hosts = listOf(peerIp),
            )
        )
    }

    /** Where a video can be fetched from, newest claim first. */
    fun hostsFor(videoId: String): List<String> =
        readVideos().firstOrNull { it.id == videoId }?.hosts.orEmpty()

    /** Notes that a fetched file is now held locally. */
    fun attachLocalFile(videoId: String, localPath: String) {
        val all = readVideos().toMutableList()
        val index = all.indexOfFirst { it.id == videoId }
        if (index < 0) return
        all[index] = all[index].copy(localPath = localPath)
        writeVideos(all)
    }

    // ── Likes ──────────────────────────────────────────────────────────────

    fun likeCount(videoId: String): Int = likes()[videoId]?.size ?: 0

    fun hasLiked(videoId: String): Boolean = likes()[videoId]?.contains(userId()) == true

    /** Toggles, and returns the new state. */
    fun toggleLike(videoId: String): Boolean {
        val current = likes().toMutableMap()
        val forVideo = current[videoId]?.toMutableSet() ?: mutableSetOf()
        val nowLiked = if (forVideo.contains(userId())) {
            forVideo.remove(userId()); false
        } else {
            forVideo.add(userId()); true
        }
        current[videoId] = forVideo
        writeLikes(current)
        return nowLiked
    }

    /** Every account that has liked a video, for sharing with peers. */
    fun likerIds(videoId: String): List<String> = likes()[videoId]?.toList().orEmpty()

    /**
     * Unions a peer's likes into ours.
     *
     * A UNION, NEVER A REPLACEMENT. Two devices each hold a partial picture, and taking the peer's
     * list wholesale would delete likes they simply had not heard about yet — including the user's
     * own, which would then reappear on the next sync and look like a bug.
     */
    fun mergeLikes(videoId: String, ids: List<String>) {
        if (videoId.isBlank() || ids.isEmpty()) return
        val current = likes().toMutableMap()
        current[videoId] = (current[videoId].orEmpty() + ids).toSet()
        writeLikes(current)
    }

    // ── Comments ───────────────────────────────────────────────────────────

    fun comments(videoId: String): List<Comment> =
        readComments().filter { it.videoId == videoId }.sortedBy { it.createdAt }

    fun commentCount(videoId: String): Int = readComments().count { it.videoId == videoId }

    fun addComment(videoId: String, text: String): Comment? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val comment = Comment(
            id = "cmt-" + java.util.UUID.randomUUID().toString().take(12),
            videoId = videoId,
            authorId = userId(),
            authorName = userName(),
            text = body,
            createdAt = System.currentTimeMillis(),
        )
        writeComments(readComments() + comment)
        return comment
    }

    /** Adds a peer's comment if it is new. Returns true when something was stored. */
    fun mergeComment(
        id: String,
        videoId: String,
        authorId: String,
        authorName: String,
        text: String,
        createdAt: Long,
    ): Boolean {
        if (id.isBlank() || videoId.isBlank() || text.isBlank()) return false
        val all = readComments()
        if (all.any { it.id == id }) return false
        writeComments(all + Comment(id, videoId, authorId, authorName, text, createdAt))
        return true
    }

    // ── Follows ────────────────────────────────────────────────────────────

    fun following(): Set<String> = PrismSettings.getLykeFollowing().toSet()

    fun isFollowing(authorId: String): Boolean = authorId in following()

    fun toggleFollow(authorId: String): Boolean {
        val current = following().toMutableSet()
        val nowFollowing = if (authorId in current) {
            current.remove(authorId); false
        } else {
            current.add(authorId); true
        }
        PrismSettings.setLykeFollowing(current.toList())
        return nowFollowing
    }

    /** Everyone who follows this device's user, as learned from mesh sync. */
    fun followers(): Set<String> = PrismSettings.getLykeFollowers().toSet()

    /** Records that someone follows this device's user, learned from their own follow list. */
    fun rememberFollower(authorId: String) {
        if (authorId.isBlank() || authorId == userId()) return
        val current = followers().toMutableSet()
        if (current.add(authorId)) PrismSettings.setLykeFollowers(current.toList())
    }

    // ── Feeds ──────────────────────────────────────────────────────────────

    enum class Feed { HOME, CHANNELS, FOLLOWERS }

    /**
     * The videos for a tab.
     *
     * CHANNELS and FOLLOWERS are deliberately different directions: Channels is people you follow,
     * Followers is people who follow you. They are easy to conflate and produce identical-looking
     * but wrong feeds when confused.
     */
    fun feed(kind: Feed): List<Video> = when (kind) {
        Feed.HOME -> videos()
        Feed.CHANNELS -> videos().filter { it.authorId in following() }
        Feed.FOLLOWERS -> videos().filter { it.authorId in followers() }
    }

    // ── Persistence ────────────────────────────────────────────────────────
    //
    // Flat JSON next to the rest of Prism's data. Small enough that rewriting the file per change
    // costs nothing, and a database would be a schema to migrate for three lists.

    private fun dir(): File = File(PrismPlatform.host.dataDir(), "lyke").apply { mkdirs() }

    private fun readVideos(): List<Video> = runCatching {
        val file = File(dir(), "videos.json")
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Video(
                id = o.optString("id"),
                authorId = o.optString("authorId"),
                authorName = o.optString("authorName"),
                localPath = o.optString("localPath"),
                caption = o.optString("caption"),
                createdAt = o.optLong("createdAt"),
                hosts = o.optJSONArray("hosts")?.let { h ->
                    (0 until h.length()).map { h.optString(it) }
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeVideos(all: List<Video>) {
        runCatching {
            val array = JSONArray()
            for (v in all) {
                array.put(
                    JSONObject()
                        .put("id", v.id)
                        .put("authorId", v.authorId)
                        .put("authorName", v.authorName)
                        .put("localPath", v.localPath)
                        .put("caption", v.caption)
                        .put("createdAt", v.createdAt)
                        .put("hosts", JSONArray().also { h -> v.hosts.forEach { h.put(it) } })
                )
            }
            File(dir(), "videos.json").writeText(array.toString())
        }
    }

    private fun likes(): Map<String, Set<String>> = runCatching {
        val file = File(dir(), "likes.json")
        if (!file.isFile) return emptyMap()
        val o = JSONObject(file.readText())
        val out = HashMap<String, Set<String>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = o.optJSONArray(key) ?: continue
            out[key] = (0 until array.length()).map { array.optString(it) }.toSet()
        }
        out
    }.getOrDefault(emptyMap())

    private fun writeLikes(all: Map<String, Set<String>>) {
        runCatching {
            val o = JSONObject()
            for ((videoId, users) in all) {
                o.put(videoId, JSONArray().also { a -> users.forEach { a.put(it) } })
            }
            File(dir(), "likes.json").writeText(o.toString())
        }
    }

    private fun readComments(): List<Comment> = runCatching {
        val file = File(dir(), "comments.json")
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Comment(
                id = o.optString("id"),
                videoId = o.optString("videoId"),
                authorId = o.optString("authorId"),
                authorName = o.optString("authorName"),
                text = o.optString("text"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeComments(all: List<Comment>) {
        runCatching {
            val array = JSONArray()
            for (c in all) {
                array.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("videoId", c.videoId)
                        .put("authorId", c.authorId)
                        .put("authorName", c.authorName)
                        .put("text", c.text)
                        .put("createdAt", c.createdAt)
                )
            }
            File(dir(), "comments.json").writeText(array.toString())
        }
    }
}
