package com.prism.launcher.podcasts

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** One voice in a show: who they are, how they think, and how they sound. */
data class CastMember(
    /** The Messages character this came from, when it came from one. */
    val characterId: String?,
    val name: String,
    /** Their persona: what the writer is told about who they are and what they believe. */
    val persona: String,
    /** A Kokoro or cloned voice id; blank for the default. */
    val voice: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("character", characterId.orEmpty())
        put("name", name)
        put("persona", persona)
        put("voice", voice)
    }

    companion object {
        fun from(o: JSONObject) = CastMember(
            characterId = o.optString("character").ifBlank { null },
            name = o.optString("name"),
            persona = o.optString("persona"),
            voice = o.optString("voice"),
        )
    }
}

/** A source an episode drew on, kept so listeners can follow it up. */
data class PodcastSource(val title: String, val url: String, val kind: String) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("url", url).put("kind", kind)

    companion object {
        fun from(o: JSONObject) = PodcastSource(o.optString("title"), o.optString("url"), o.optString("kind"))
    }
}

/**
 * One spoken line of an episode. [emotion] is the feeling the writer marked it with (an
 * `EmotionSteering` id), or blank; it is spoken with that feeling when steering is on, and kept out
 * of [text] so the transcript reads cleanly.
 */
data class ScriptLine(val speaker: String, val text: String, val startMs: Long = 0, val emotion: String = "") {
    fun toJson(): JSONObject = JSONObject().put("speaker", speaker).put("text", text).put("at", startMs).put("emotion", emotion)

    companion object {
        fun from(o: JSONObject) = ScriptLine(o.optString("speaker"), o.optString("text"), o.optLong("at", 0), o.optString("emotion"))
    }
}

data class Episode(
    val id: String,
    val number: Int,
    val title: String,
    val summary: String,
    val createdAt: Long,
    val durationMs: Long,
    /** Which device made it, for the "from Pixel" line on a shared episode. */
    val madeBy: String = "",
    /** The topic the user asked for with the episode button, or blank for the show's own subject. */
    val topic: String = "",
    /** True when the user named the episode, so recreating it keeps the name. */
    val titledByUser: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("n", number); put("title", title); put("summary", summary)
        put("created", createdAt); put("duration", durationMs); put("madeBy", madeBy)
        put("topic", topic); put("titled", titledByUser)
    }

    companion object {
        fun from(o: JSONObject) = Episode(
            id = o.optString("id"),
            number = o.optInt("n", 1),
            title = o.optString("title"),
            summary = o.optString("summary"),
            createdAt = o.optLong("created", 0),
            durationMs = o.optLong("duration", 0),
            madeBy = o.optString("madeBy"),
            topic = o.optString("topic"),
            titledByUser = o.optBoolean("titled"),
        )
    }
}

data class Podcast(
    val id: String,
    val title: String,
    val description: String,
    val keywords: List<String>,
    val category: String,
    /** Every dropdown on the creation page, by [PodcastCatalog.Choice.key]. */
    val choices: Map<String, String>,
    /** Every switch on the creation page, by [PodcastCatalog.Toggle.key]. */
    val toggles: Map<String, Boolean>,
    val minutes: Int,
    val autoCover: Boolean,
    val cast: List<CastMember>,
    val createdAt: Long,
    val updatedAt: Long,
    val episodes: List<Episode> = emptyList(),
    /** Null for a podcast made here; the device it came from for one shared by a trusted device. */
    val originFingerprint: String? = null,
    val originName: String? = null,
    /** Where the cover came from, for the detail page: "generated", "web: <url>" or "". */
    val coverCredit: String = "",
) {
    val shared: Boolean get() = originFingerprint != null

    fun choice(c: PodcastCatalog.Choice): String = choices[c.key] ?: c.default
    fun toggle(key: String): Boolean =
        toggles[key] ?: PodcastCatalog.TOGGLES.firstOrNull { it.key == key }?.default ?: false

    /** The words every search for this show starts from. */
    fun searchTerms(): String = (keywords.take(5) + category).joinToString(" ")

    fun toJson(withEpisodes: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("description", description)
        put("keywords", JSONArray().also { a -> keywords.forEach { a.put(it) } })
        put("category", category)
        put("choices", JSONObject().also { o -> choices.forEach { (k, v) -> o.put(k, v) } })
        put("toggles", JSONObject().also { o -> toggles.forEach { (k, v) -> o.put(k, v) } })
        put("minutes", minutes); put("autoCover", autoCover)
        put("cast", JSONArray().also { a -> cast.forEach { a.put(it.toJson()) } })
        put("created", createdAt); put("updated", updatedAt)
        put("coverCredit", coverCredit)
        if (originFingerprint != null) put("origin", originFingerprint)
        if (originName != null) put("originName", originName)
        if (withEpisodes) put("episodes", JSONArray().also { a -> episodes.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun from(o: JSONObject): Podcast {
            val keywords = o.optJSONArray("keywords")
            val choices = o.optJSONObject("choices")
            val toggles = o.optJSONObject("toggles")
            val cast = o.optJSONArray("cast")
            val episodes = o.optJSONArray("episodes")
            return Podcast(
                id = o.optString("id"),
                title = o.optString("title"),
                description = o.optString("description"),
                keywords = if (keywords == null) emptyList() else (0 until keywords.length()).map { keywords.optString(it) }.filter { it.isNotBlank() },
                category = o.optString("category"),
                choices = choices?.keys()?.asSequence()?.associateWith { choices.optString(it) } ?: emptyMap(),
                toggles = toggles?.keys()?.asSequence()?.associateWith { toggles.optBoolean(it) } ?: emptyMap(),
                minutes = o.optInt("minutes", PodcastCatalog.DEFAULT_MINUTES)
                    .coerceIn(PodcastCatalog.MIN_MINUTES, PodcastCatalog.MAX_MINUTES),
                autoCover = o.optBoolean("autoCover", true),
                cast = if (cast == null) emptyList() else (0 until cast.length()).mapNotNull { cast.optJSONObject(it)?.let(CastMember::from) },
                createdAt = o.optLong("created", 0),
                updatedAt = o.optLong("updated", 0),
                episodes = if (episodes == null) emptyList() else (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it)?.let(Episode::from) },
                originFingerprint = o.optString("origin").ifBlank { null },
                originName = o.optString("originName").ifBlank { null },
                coverCredit = o.optString("coverCredit"),
            )
        }
    }
}

/**
 * Every podcast on this device, made here or shared by a trusted device.
 *
 * ## One folder per show
 *
 * `podcasts/<id>/podcast.json` holds the show and its episode list; `cover.png` its artwork;
 * `episodes/<episode>.wav` and `episodes/<episode>.json` each episode's audio and its transcript
 * with sources. Files rather than a database because the same paths are what a trusted device asks
 * for over the wire (see [PodcastSync]), and a folder is one thing to delete.
 *
 * ## Shared podcasts merge, they do not overwrite
 *
 * A show shared by another device has the same id on both, and either device can make its next
 * episode. So two copies meet by UNION: the newer details win, and the episode lists are combined.
 * Neither device can lose an episode the other made.
 */
object PodcastStore {

    private const val TAG = "Podcasts"

    @Volatile private var rootOverride: File? = null

    /** For tests: keeps the store out of the real data directory. */
    fun useRoot(dir: File?) {
        rootOverride = dir
        cache = null
    }

    fun root(): File = (rootOverride ?: File(PrismPlatform.host.dataDir(), "podcasts")).apply { mkdirs() }

    fun dirOf(id: String): File = File(root(), safe(id))
    fun coverOf(id: String): File = File(dirOf(id), "cover.png")
    fun audioOf(podcastId: String, episodeId: String): File = File(File(dirOf(podcastId), "episodes"), safe(episodeId) + ".wav")
    fun transcriptOf(podcastId: String, episodeId: String): File = File(File(dirOf(podcastId), "episodes"), safe(episodeId) + ".json")

    private fun metaOf(id: String) = File(dirOf(id), "podcast.json")
    private fun tombstones() = File(root(), "removed.txt")

    /** Called with nothing whenever anything changes, on whatever thread changed it. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.forEach { runCatching { it() } }

    @Volatile private var cache: List<Podcast>? = null
    private val lock = Any()

    fun newId(): String = "pod-" + UUID.randomUUID().toString().take(12)
    fun newEpisodeId(): String = "ep-" + UUID.randomUUID().toString().take(12)

    /** Newest activity first: a show with a fresh episode rises to the top, as in a podcast app. */
    fun all(): List<Podcast> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val list = root().listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
                val meta = File(dir, "podcast.json")
                if (!meta.isFile) return@mapNotNull null
                runCatching { Podcast.from(JSONObject(meta.readText())) }
                    .onFailure { PrismPlatform.log.warn(TAG, "Unreadable podcast in ${dir.name}: ${it.message}") }
                    .getOrNull()
            }.orEmpty().sortedByDescending { p -> maxOf(p.updatedAt, p.episodes.maxOfOrNull { it.createdAt } ?: 0) }
            cache = list
            return list
        }
    }

    fun find(id: String): Podcast? = all().firstOrNull { it.id == id }

    fun save(podcast: Podcast) {
        synchronized(lock) {
            dirOf(podcast.id).mkdirs()
            val sorted = podcast.copy(episodes = podcast.episodes.sortedByDescending { it.number })
            val target = metaOf(podcast.id)
            val temp = File(target.parentFile, "podcast.json.tmp")
            temp.writeText(sorted.toJson().toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            cache = null
        }
        changed()
    }

    fun addEpisode(podcastId: String, episode: Episode, transcript: List<ScriptLine>, sources: List<PodcastSource>) {
        writeTranscript(podcastId, episode.id, transcript, sources)
        synchronized(lock) {
            val current = find(podcastId) ?: return
            val episodes = current.episodes.filterNot { it.id == episode.id } + episode
            save(current.copy(episodes = episodes, updatedAt = System.currentTimeMillis()))
        }
    }

    fun writeTranscript(podcastId: String, episodeId: String, lines: List<ScriptLine>, sources: List<PodcastSource>) {
        val file = transcriptOf(podcastId, episodeId)
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().apply {
            put("lines", JSONArray().also { a -> lines.forEach { a.put(it.toJson()) } })
            put("sources", JSONArray().also { a -> sources.forEach { a.put(it.toJson()) } })
        }.toString())
    }

    /** An episode's transcript and sources, or empty lists when they have not arrived yet. */
    fun transcript(podcastId: String, episodeId: String): Pair<List<ScriptLine>, List<PodcastSource>> = runCatching {
        val o = JSONObject(transcriptOf(podcastId, episodeId).readText())
        val lines = o.optJSONArray("lines")
        val sources = o.optJSONArray("sources")
        Pair(
            if (lines == null) emptyList() else (0 until lines.length()).mapNotNull { lines.optJSONObject(it)?.let(ScriptLine::from) },
            if (sources == null) emptyList() else (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.let(PodcastSource::from) },
        )
    }.getOrDefault(Pair(emptyList(), emptyList()))

    fun hasAudio(podcastId: String, episodeId: String): Boolean =
        audioOf(podcastId, episodeId).let { it.isFile && it.length() > 44 }

    /**
     * Removes a show from this device.
     *
     * Remembered as removed, so a trusted device announcing it again does not bring it back: deleting
     * something and watching it reappear a minute later is the worst way sync can behave.
     */
    fun delete(id: String) {
        synchronized(lock) {
            dirOf(id).deleteRecursively()
            runCatching { tombstones().appendText(id + "\n") }
            cache = null
        }
        changed()
    }

    /**
     * Puts [episode] in place of [oldId]: the old audio and transcript go, the new one takes its
     * number. The old id is remembered as removed so a trusted device cannot bring it back.
     */
    fun replaceEpisode(podcastId: String, oldId: String, episode: Episode, transcript: List<ScriptLine>, sources: List<PodcastSource>) {
        deleteEpisode(podcastId, oldId)
        addEpisode(podcastId, episode, transcript, sources)
    }

    fun deleteEpisode(podcastId: String, episodeId: String) {
        synchronized(lock) {
            val current = find(podcastId) ?: return
            audioOf(podcastId, episodeId).delete()
            transcriptOf(podcastId, episodeId).delete()
            runCatching { tombstones().appendText("$podcastId/$episodeId\n") }
            save(current.copy(episodes = current.episodes.filterNot { it.id == episodeId }))
        }
    }

    fun isRemoved(id: String): Boolean = runCatching {
        tombstones().takeIf { it.isFile }?.readLines()?.any { it.trim() == id } == true
    }.getOrDefault(false)

    /**
     * Folds a copy of [incoming] from another device into this one.
     *
     * @return the episodes this device did not have, so the caller can fetch their audio.
     */
    fun merge(incoming: Podcast): List<Episode> {
        if (isRemoved(incoming.id)) return emptyList()
        synchronized(lock) {
            val current = find(incoming.id)
            if (current == null) {
                val fresh = incoming.episodes.filterNot { isRemoved(incoming.id + "/" + it.id) }
                save(incoming.copy(episodes = fresh))
                return fresh
            }
            val known = current.episodes.map { it.id }.toSet()
            val added = incoming.episodes.filter { it.id !in known && !isRemoved(incoming.id + "/" + it.id) }
            val details = if (incoming.updatedAt > current.updatedAt) {
                incoming.copy(
                    // The origin is a fact about where the show came from; a later edit does not change it.
                    originFingerprint = current.originFingerprint,
                    originName = current.originName,
                )
            } else current
            save(details.copy(episodes = current.episodes + added))
            return added
        }
    }

    /** The number the next episode gets, counting episodes made anywhere. */
    fun nextNumber(podcast: Podcast): Int = (podcast.episodes.maxOfOrNull { it.number } ?: 0) + 1

    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
}
