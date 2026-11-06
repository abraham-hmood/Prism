package com.prism.launcher.podcasts

import com.prism.core.MeshConnect
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.messaging.MeshModels
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Podcasts between trusted devices: every show and every episode, made on either, plays on both.
 *
 * ## Details as shares, files over the tunnel
 *
 * A share is one sealed datagram, so it carries what fits: a show's details (`podcast`) or one
 * episode's (`episode`). The cover, each episode's audio and its transcript are files of up to
 * hundreds of megabytes, so the receiving device fetches them from `GET /trusted-podcast/...` on the
 * AI domain, the way a copied file or an app travels. The route is in :core and answered by both the
 * Android host and the desktop host.
 *
 * ## Catching up
 *
 * A device that was asleep when an episode was shared would never hear of it. So each device also
 * shares a small `index` -- every show's id, last change and episode count -- when keys are
 * exchanged and every ten minutes. A receiver missing anything answers `want`, and gets the full
 * details of those shows back. An episode whose audio failed to arrive is fetched again on the next
 * pass rather than left silent.
 *
 * ## Consent both ways
 *
 * Sending requires that this device agreed to send podcasts to that one; the route serves files only
 * to a device this one sends podcasts to; and [TrustedDevices.onShare] drops a share the receiver
 * did not accept. Nothing here widens what pairing allowed.
 */
object PodcastSync {

    private const val TAG = "Podcasts"
    const val PATH = "/trusted-podcast/"

    private val installed = AtomicBoolean(false)
    private val downloads = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prism-podcast-fetch").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Which device told us about each episode, so its audio is asked for from the right place. */
    private val announcedBy = ConcurrentHashMap<String, String>()

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        TrustedDevices.consume(TrustedDevices.Kind.PODCASTS) { received -> onShare(received) }
        TrustedDevices.addKeyListener { device ->
            if (TrustedDevices.Kind.PODCASTS in device.outgoing) shareIndex(device.fingerprint)
        }
        Thread({
            runCatching { Thread.sleep(20_000) }
            while (true) {
                runCatching {
                    TrustedDevices.recipientsFor(TrustedDevices.Kind.PODCASTS).forEach { shareIndex(it.fingerprint) }
                    retryMissingAudio()
                }
                runCatching { Thread.sleep(10 * 60_000L) }
            }
        }, "prism-podcast-sync").apply { isDaemon = true; start() }
        PrismPlatform.log.info(TAG, "Podcast sync installed")
    }

    // ── Sending ─────────────────────────────────────────────────────────

    fun publishPodcast(podcast: Podcast) {
        runCatching { TrustedDevices.share(TrustedDevices.Kind.PODCASTS, podcastMessage(podcast)) }
    }

    fun publishEpisode(podcast: Podcast, episode: Episode) {
        runCatching { TrustedDevices.share(TrustedDevices.Kind.PODCASTS, episodeMessage(podcast.id, episode)) }
    }

    /**
     * Tells trusted devices an episode was REPLACED, so they drop the old version rather than keep
     * two episodes with one number. A plain delete is not sent: it removes the episode from this
     * device only, and the others keep theirs.
     */
    fun publishRemoval(podcastId: String, episodeId: String) {
        runCatching {
            TrustedDevices.share(TrustedDevices.Kind.PODCASTS, JSONObject().put("t", "replaced").put("pid", podcastId).put("eid", episodeId))
        }
    }

    private fun shareIndex(fingerprint: String) {
        val items = JSONArray()
        PodcastStore.all().forEach { p ->
            items.put(JSONObject().put("id", p.id).put("u", p.updatedAt).put("n", p.episodes.size))
        }
        TrustedDevices.shareWith(fingerprint, TrustedDevices.Kind.PODCASTS, JSONObject().put("t", "index").put("items", items))
    }

    /** Everything about [ids], one datagram per show and per episode, paced so none are dropped. */
    private fun sendAll(fingerprint: String, ids: List<String>) {
        Thread({
            ids.mapNotNull { PodcastStore.find(it) }.forEach { podcast ->
                TrustedDevices.shareWith(fingerprint, TrustedDevices.Kind.PODCASTS, podcastMessage(podcast))
                podcast.episodes.forEach { episode ->
                    runCatching { Thread.sleep(40) }
                    TrustedDevices.shareWith(fingerprint, TrustedDevices.Kind.PODCASTS, episodeMessage(podcast.id, episode))
                }
                runCatching { Thread.sleep(80) }
            }
        }, "prism-podcast-send").apply { isDaemon = true; start() }
    }

    /** A show's details, trimmed so the sealed datagram stays well inside its budget. */
    private fun podcastMessage(podcast: Podcast): JSONObject {
        val slim = podcast.copy(
            description = podcast.description.take(1500),
            cast = podcast.cast.take(6).map { it.copy(persona = it.persona.take(500)) },
            episodes = emptyList(),
        )
        return JSONObject().put("t", "podcast").put("p", slim.toJson(withEpisodes = false))
    }

    private fun episodeMessage(podcastId: String, episode: Episode): JSONObject =
        JSONObject().put("t", "episode").put("pid", podcastId)
            .put("e", episode.copy(summary = episode.summary.take(800)).toJson())

    // ── Receiving ───────────────────────────────────────────────────────

    private fun onShare(received: TrustedDevices.Received) {
        val body = received.body
        when (body.optString("t")) {
            "podcast" -> body.optJSONObject("p")?.let { ingestPodcast(it, received) }
            "episode" -> {
                val pid = body.optString("pid")
                val episode = body.optJSONObject("e")?.let(Episode::from) ?: return
                ingestEpisode(pid, episode, received)
            }
            "replaced" -> {
                val pid = body.optString("pid")
                val eid = body.optString("eid")
                if (pid.isNotBlank() && eid.isNotBlank() && PodcastStore.find(pid)?.episodes?.any { it.id == eid } == true) {
                    if (PodcastPlayer.isCurrent(pid, eid)) PodcastPlayer.stop()
                    PodcastStore.deleteEpisode(pid, eid)
                }
            }
            "index" -> {
                val items = body.optJSONArray("items") ?: return
                val wanted = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter { item ->
                    val id = item.optString("id")
                    if (id.isBlank() || PodcastStore.isRemoved(id)) return@filter false
                    val mine = PodcastStore.find(id) ?: return@filter true
                    item.optLong("u") > mine.updatedAt || item.optInt("n") > mine.episodes.size
                }.map { it.optString("id") }
                if (wanted.isNotEmpty()) {
                    TrustedDevices.shareWith(
                        received.fromFingerprint, TrustedDevices.Kind.PODCASTS,
                        JSONObject().put("t", "want").put("ids", JSONArray().also { a -> wanted.forEach { a.put(it) } }),
                    )
                }
            }
            "want" -> {
                val ids = body.optJSONArray("ids") ?: return
                sendAll(received.fromFingerprint, (0 until ids.length()).map { ids.optString(it) }.filter { it.isNotBlank() })
            }
        }
    }

    private fun ingestPodcast(json: JSONObject, received: TrustedDevices.Received) {
        val incoming = Podcast.from(json)
        if (incoming.id.isBlank()) return
        val me = runCatching { TrustedDevices.localFingerprint() }.getOrDefault("")
        // Where the show came from: the sender, unless the sender was itself passing on someone else's
        // -- or this device's own, coming home.
        val origin = when (incoming.originFingerprint) {
            null -> received.fromFingerprint to received.fromName
            me -> null to null
            else -> incoming.originFingerprint to incoming.originName
        }
        val before = PodcastStore.find(incoming.id)
        PodcastStore.merge(incoming.copy(originFingerprint = origin.first, originName = origin.second, episodes = emptyList()))
        val coverMissing = !PodcastStore.coverOf(incoming.id).isFile
        val coverChanged = before != null && incoming.updatedAt > before.updatedAt && incoming.coverCredit != before.coverCredit
        if (coverMissing || coverChanged) fetch(received.fromFingerprint, incoming.id, "cover.png", PodcastStore.coverOf(incoming.id))
    }

    private fun ingestEpisode(podcastId: String, episode: Episode, received: TrustedDevices.Received) {
        if (episode.id.isBlank() || PodcastStore.isRemoved("$podcastId/${episode.id}")) return
        val podcast = PodcastStore.find(podcastId)
        if (podcast == null) {
            // An episode of a show this device has not heard of yet: ask for the show itself.
            TrustedDevices.shareWith(
                received.fromFingerprint, TrustedDevices.Kind.PODCASTS,
                JSONObject().put("t", "want").put("ids", JSONArray().put(podcastId)),
            )
            return
        }
        PodcastStore.merge(podcast.copy(episodes = listOf(episode), updatedAt = podcast.updatedAt))
        announcedBy["$podcastId/${episode.id}"] = received.fromFingerprint
        fetchEpisodeFiles(received.fromFingerprint, podcastId, episode.id)
    }

    private fun fetchEpisodeFiles(fingerprint: String, podcastId: String, episodeId: String) {
        if (!PodcastStore.transcriptOf(podcastId, episodeId).isFile) {
            fetch(fingerprint, podcastId, "episodes/$episodeId.json", PodcastStore.transcriptOf(podcastId, episodeId))
        }
        if (!PodcastStore.hasAudio(podcastId, episodeId)) {
            fetch(fingerprint, podcastId, "episodes/$episodeId.wav", PodcastStore.audioOf(podcastId, episodeId))
        }
    }

    /** Episodes whose audio never arrived, asked for again from whoever announced them -- or anyone who might have them. */
    private fun retryMissingAudio() {
        val sources = TrustedDevices.all().filter { it.confirmed && TrustedDevices.Kind.PODCASTS in it.incoming }
        if (sources.isEmpty()) return
        PodcastStore.all().forEach { podcast ->
            podcast.episodes.filterNot { PodcastStore.hasAudio(podcast.id, it.id) }.forEach { episode ->
                val from = announcedBy["${podcast.id}/${episode.id}"]
                    ?: podcast.originFingerprint
                    ?: sources.first().fingerprint
                fetchEpisodeFiles(from, podcast.id, episode.id)
            }
        }
    }

    private fun fetch(fingerprint: String, podcastId: String, file: String, target: File) {
        val key = "$podcastId/$file"
        if (!pending.add(key)) return
        downloads.execute {
            try {
                val peer = TrustedDevices.byFingerprint(fingerprint)?.takeIf { it.lastIp.isNotBlank() } ?: return@execute
                target.parentFile?.mkdirs()
                val partial = File(target.parentFile, target.name + ".part")
                val ok = MeshConnect.get(
                    peer.lastIp, MeshModels.HOST_DOMAIN, PATH + podcastId + "/" + file,
                    into = partial, timeoutMs = 600_000, maxResponseBytes = 2L * 1024 * 1024 * 1024,
                ) != null && partial.isFile && partial.length() > 0
                if (ok) {
                    target.delete()
                    if (!partial.renameTo(target)) { partial.copyTo(target, overwrite = true); partial.delete() }
                    PrismPlatform.log.info(TAG, "Received $file of $podcastId from ${peer.name}")
                    PodcastStore.find(podcastId)?.let { PodcastStore.save(it) }
                } else {
                    partial.delete()
                    PrismPlatform.log.info(TAG, "Could not fetch $file of $podcastId from ${peer.name}; will retry")
                }
            } finally {
                pending.remove(key)
            }
        }
    }

    // ── Serving ─────────────────────────────────────────────────────────

    /**
     * Answers `GET /trusted-podcast/<podcast>/<file>` for a peer. Returns false for any other path so
     * the host can try its own routes.
     */
    fun serve(peerIp: String, requestPath: String, output: OutputStream): Boolean {
        if (!requestPath.startsWith(PATH)) return false
        val rest = requestPath.removePrefix(PATH).substringBefore('?').trim('/')
        val podcastId = rest.substringBefore('/')
        val name = rest.substringAfter('/', "")
        val allowed = name == "cover.png" ||
            Regex("episodes/[A-Za-z0-9_.-]+\\.(wav|json)").matches(name)
        if (podcastId.isBlank() || !allowed || ".." in rest) {
            respond(output, 400, "Name a podcast file.")
            return true
        }
        val address = TrustedDevices.normaliseAddress(peerIp)
        val authorised = TrustedDevices.recipientsFor(TrustedDevices.Kind.PODCASTS)
            .any { TrustedDevices.normaliseAddress(it.lastIp) == address }
        if (!authorised) {
            PrismPlatform.log.warn(TAG, "Refused a podcast request from $peerIp -- not a podcast recipient")
            respond(output, 403, "This device has not agreed to send you podcasts.")
            return true
        }
        val file = when {
            name == "cover.png" -> PodcastStore.coverOf(podcastId)
            name.endsWith(".wav") -> PodcastStore.audioOf(podcastId, name.removePrefix("episodes/").removeSuffix(".wav"))
            else -> PodcastStore.transcriptOf(podcastId, name.removePrefix("episodes/").removeSuffix(".json"))
        }
        if (!file.isFile) {
            respond(output, 404, "That file is not on this device.")
            return true
        }
        val type = when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".wav") -> "audio/wav"
            else -> "application/json"
        }
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            file.inputStream().use { it.copyTo(output, 64 * 1024) }
            output.flush()
        }
        return true
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        runCatching {
            output.write(("HTTP/1.1 $code Error\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(body)
            output.flush()
        }
    }
}
