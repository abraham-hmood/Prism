package com.prism.launcher.stremio

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stremio add-ons, as Lyke's content sources.
 *
 * ## What a Stremio add-on actually is
 *
 * An HTTP server and nothing else. It serves `/manifest.json` describing what it can do, and
 * answers `/{resource}/{type}/{id}.json` for the resources it declared -- `catalog`, `meta`,
 * `stream`, `subtitles`. There is no plugin, no code download, and nothing executes on the device:
 * installing an add-on means remembering a URL. That is the whole protocol, and it is why this is a
 * few hundred lines rather than a sandbox.
 *
 * ## No account, by design
 *
 * There is no sign-in here. An add-on IS its manifest URL -- the protocol has no registry, no
 * account and no gatekeeper -- so an account was only ever a way to copy a list between devices.
 * Add-ons arrive by pasted URL, by `stremio://` link, or from a repository, and the public
 * community catalogue needs no credentials at all.
 *
 * ## The part that is easy to get wrong
 *
 * **Metadata and streams come from different add-ons.** Cinemeta and Anime Kitsu describe films and
 * series and provide no streams whatsoever; other add-ons provide streams and describe nothing. So
 * a stream request has to go to EVERY installed add-on that declares `stream` for that type and
 * whose `idPrefixes` match the id -- not to the add-on the item was found in, which usually cannot
 * answer. Getting this wrong makes every catalogue add-on look broken.
 *
 * ## Legality
 *
 * The protocol carries catalogues and stream URLs; what is on the other end is entirely the
 * add-on's business. Prism ships no add-ons and no repositories -- every URL here is one the user
 * chose.
 */
object StremioStore {

    private const val TAG = "PrismStremio"

    private const val REPOSITORIES = "stremio/repositories.json"
    private const val ADDONS = "stremio/addons.json"

    /**
     * Stremio's public list of community add-ons.
     *
     * NO ACCOUNT, NO AUTHENTICATION -- a plain JSON file, already in the exact
     * `[{transportUrl, manifest}]` shape [parseRepository] reads.
     */
    const val COMMUNITY_CATALOG = "https://api.strem.io/addonscollection.json"

    private const val TIMEOUT_MS = 12_000

    // ── Types ──────────────────────────────────────────────────────────────

    data class Repository(val url: String, val name: String, val addedAt: Long)

    /**
     * One resource an add-on declares, with the filters attached to it.
     *
     * The filters are the whole reason this is a type rather than a string. A manifest may say it
     * handles `stream` only for `series`, and only for ids beginning `kitsu:` -- asking it about an
     * IMDb film wastes a request and, worse, lets a wrong answer into the results.
     */
    data class ResourceSpec(
        val name: String,
        val types: List<String>,
        val idPrefixes: List<String>,
    )

    /** One catalogue an add-on offers. */
    data class Catalog(
        val type: String,
        val id: String,
        val name: String,
        val searchable: Boolean,
        /**
         * Extras this catalogue cannot be called without.
         *
         * A catalogue requiring `genre` returns nothing useful when asked plainly, so browsing
         * skips it rather than showing an empty shelf the user cannot explain.
         */
        val requiredExtras: List<String>,
    ) {
        val browsable: Boolean get() = requiredExtras.none { it != "skip" }
    }

    data class Addon(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val logo: String,
        /** The `/manifest.json` URL. Everything else is derived from it. */
        val transportUrl: String,
        val types: List<String>,
        val catalogs: List<Catalog>,
        val resources: List<ResourceSpec>,
    ) {
        /** Everything before `/manifest.json`. Resource requests hang off this. */
        val baseUrl: String get() = transportUrl.removeSuffix("/manifest.json").trimEnd('/')

        fun declares(resource: String): Boolean = resources.any { it.name == resource }

        /**
         * Whether it is worth asking this add-on for [resource] about [id] of [type].
         *
         * A resource that names no types falls back to the add-on's own `types`, which is what the
         * specification says and what most manifests rely on. A resource that names no idPrefixes
         * accepts any id -- absence means "no restriction", not "nothing matches".
         */
        fun supports(resource: String, type: String, id: String? = null): Boolean {
            val spec = resources.firstOrNull { it.name == resource } ?: return false

            val allowedTypes = spec.types.ifEmpty { types }
            if (allowedTypes.isNotEmpty() && type !in allowedTypes) return false

            if (id != null && spec.idPrefixes.isNotEmpty() &&
                spec.idPrefixes.none { id.startsWith(it) }
            ) return false

            return true
        }

        val canSearch: Boolean get() = declares("catalog") && catalogs.any { it.searchable }
        val canBrowse: Boolean get() = declares("catalog") && catalogs.any { it.browsable }
        val canStream: Boolean get() = declares("stream")
        val canMeta: Boolean get() = declares("meta")
    }

    /** One episode of a series, from its meta's `videos`. */
    data class Episode(
        val id: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val released: String,
    ) {
        /**
         * What the episode picker shows.
         *
         * Cinemeta returns an explicit null title for most episodes, so a naive "SxEy · title"
         * would read "S1E1 · Episode 1" -- the same fact twice. With no title the numbering is
         * spelled out instead, which is what a person scanning a list of sixty-seven episodes
         * actually needs.
         */
        val label: String get() = when {
            title.isNotBlank() && season > 0 -> "S${season}E${episode}  ·  $title"
            title.isNotBlank() -> title
            season > 0 -> "Season $season, episode $episode"
            episode > 0 -> "Episode $episode"
            else -> id
        }
    }

    /** One item from a catalogue -- a film, a series, a channel. */
    data class Meta(
        val addonId: String,
        val id: String,
        val type: String,
        val name: String,
        val poster: String,
        val description: String,
        /** Populated only by [meta]; a catalogue preview never carries episodes. */
        val episodes: List<Episode> = emptyList(),
    ) {
        val isSeries: Boolean get() = type == "series" || type == "anime" || episodes.isNotEmpty()

        /**
         * The identity Lyke uses for this item.
         *
         * Prefixed so likes and comments key off something that cannot collide with a Lyke video's
         * id, and so the player can tell at a glance that mirroring does not apply.
         */
        val lykeId: String get() = "$ID_PREFIX$addonId|$type|$id"
    }

    data class Stream(
        val url: String,
        val name: String,
        val title: String,
        val externalUrl: String,
        val infoHash: String,
        val ytId: String,
        /**
         * Headers the source requires.
         *
         * From `behaviorHints.proxyHeaders.request`. Some add-ons serve only with a particular
         * Referer or User-Agent, and dropping these turns a working stream into a 403 that looks
         * like a dead link.
         */
        val headers: Map<String, String>,
        /** Which add-on answered, so a picker can say where a stream came from. */
        val addonName: String,
    ) {
        /**
         * Whether Prism can actually play this.
         *
         * `infoHash` is BitTorrent and needs a torrent client Prism does not have; `externalUrl`
         * means "open this elsewhere", which is not playback; `ytId` needs a YouTube player. Only a
         * direct URL plays, and saying so up front beats handing the player something that fails
         * silently.
         */
        val playable: Boolean get() = url.startsWith("http", ignoreCase = true)

        val label: String get() = buildString {
            append(name.ifBlank { title }.ifBlank { "Stream" })
            if (title.isNotBlank() && title != name) append("\n").append(title)
        }.take(200)

        val unplayableReason: String? get() = when {
            playable -> null
            infoHash.isNotBlank() -> "BitTorrent — Prism has no torrent client"
            ytId.isNotBlank() -> "YouTube — opens outside Prism"
            externalUrl.isNotBlank() -> "A link to open elsewhere, not a stream"
            else -> "No playable address"
        }
    }

    const val ID_PREFIX = "stremio:"

    fun isStremioId(id: String): Boolean = id.startsWith(ID_PREFIX)

    // ── Repositories ───────────────────────────────────────────────────────

    fun repositories(context: Context): List<Repository> = runCatching {
        val file = File(context.filesDir, REPOSITORIES)
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Repository(item.optString("url"), item.optString("name"), item.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun addRepository(context: Context, url: String, name: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http", ignoreCase = true)) {
            return "A repository is a web address — it has to start with http:// or https://"
        }
        if (repositories(context).any { it.url.equals(trimmed, ignoreCase = true) }) {
            return "That repository is already in the list"
        }
        writeRepositories(
            context,
            repositories(context) + Repository(trimmed, name.ifBlank { hostOf(trimmed) }, System.currentTimeMillis())
        )
        return null
    }

    fun addCommunityCatalog(context: Context): String? =
        addRepository(context, COMMUNITY_CATALOG, "Stremio community add-ons")

    fun hasCommunityCatalog(context: Context): Boolean =
        repositories(context).any { it.url == COMMUNITY_CATALOG }

    fun removeRepository(context: Context, url: String) {
        writeRepositories(context, repositories(context).filterNot { it.url == url })
    }

    private fun writeRepositories(context: Context, list: List<Repository>) {
        runCatching {
            val array = JSONArray()
            list.forEach {
                array.put(JSONObject().apply {
                    put("url", it.url); put("name", it.name); put("at", it.addedAt)
                })
            }
            File(context.filesDir, REPOSITORIES).apply { parentFile?.mkdirs() }.writeText(array.toString())
        }
    }

    /**
     * Everything the configured repositories offer, minus what is already installed.
     *
     * Blocking; callers run it off the main thread. One unreachable repository does not sink the
     * rest -- a list from three of four sources is useful, and an error that hides the three is not.
     */
    fun available(context: Context): List<Addon> {
        val installedIds = installed(context).map { it.id }.toSet()
        val found = LinkedHashMap<String, Addon>()

        repositories(context).forEach { repository ->
            val body = fetch(repository.url) ?: run {
                PrismLogger.logWarning(TAG, "Repository unreachable: ${repository.url}")
                return@forEach
            }
            parseRepository(body).forEach { addon ->
                if (addon.id !in installedIds) found.putIfAbsent(addon.id, addon)
            }
        }
        return found.values.toList()
    }

    /**
     * Reads the three repository shapes that exist in the wild.
     *
     * An entry carrying its own `manifest` is used as-is rather than re-fetched: the list has
     * already done that work, and fetching thirty manifests to render a list the user may scroll
     * past is thirty requests for nothing.
     */
    private fun parseRepository(body: String): List<Addon> {
        val entries = runCatching {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).optJSONArray("addons") ?: JSONArray()
            }
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<Addon>()
        for (index in 0 until entries.length()) {
            when (val raw = entries.opt(index)) {
                is String -> fetchManifest(raw)?.let(out::add)
                is JSONObject -> {
                    val transport = raw.optString("transportUrl").ifBlank { raw.optString("url") }
                    val manifest = raw.optJSONObject("manifest")
                    when {
                        manifest != null && transport.isNotBlank() ->
                            parseManifest(manifest, transport)?.let(out::add)
                        transport.isNotBlank() -> fetchManifest(transport)?.let(out::add)
                    }
                }
            }
        }
        return out
    }

    // ── Installed add-ons ──────────────────────────────────────────────────

    fun installed(context: Context): List<Addon> = runCatching {
        val file = File(context.filesDir, ADDONS)
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            parseManifest(item.optJSONObject("manifest") ?: return@mapNotNull null, item.optString("transportUrl"))
        }
    }.getOrDefault(emptyList())

    /**
     * Installs by URL: fetch the manifest, keep it.
     *
     * Nothing is downloaded but JSON, and nothing is executed. "Install" means "remember this
     * address and what it said it can do" -- the manifest is stored so the list renders offline and
     * so a search does not have to re-fetch every manifest first.
     */
    fun install(context: Context, transportUrl: String): String? {
        val url = normaliseTransport(transportUrl)
        val manifestBody = fetch(url) ?: return "Could not reach that add-on"
        val manifest = runCatching { JSONObject(manifestBody) }.getOrNull()
            ?: return "That address did not return an add-on manifest"
        val addon = parseManifest(manifest, url) ?: return "That manifest is missing an id or a name"

        if (installed(context).any { it.id == addon.id }) return "${addon.name} is already installed"

        val existing = rawInstalled(context)
        existing.put(JSONObject().apply {
            put("transportUrl", url)
            put("manifest", manifest)
        })
        File(context.filesDir, ADDONS).apply { parentFile?.mkdirs() }.writeText(existing.toString())
        PrismLogger.logSuccess(TAG, "Installed add-on ${addon.id}")
        return null
    }

    fun uninstall(context: Context, addonId: String) {
        val kept = JSONArray()
        val existing = rawInstalled(context)
        for (index in 0 until existing.length()) {
            val item = existing.optJSONObject(index) ?: continue
            if (item.optJSONObject("manifest")?.optString("id") != addonId) kept.put(item)
        }
        File(context.filesDir, ADDONS).apply { parentFile?.mkdirs() }.writeText(kept.toString())
    }

    private fun rawInstalled(context: Context): JSONArray = runCatching {
        val file = File(context.filesDir, ADDONS)
        if (file.isFile) JSONArray(file.readText()) else JSONArray()
    }.getOrDefault(JSONArray())

    // ── Browsing catalogues ────────────────────────────────────────────────

    /** An add-on paired with one of its catalogues, for a browsable list. */
    data class Shelf(val addon: Addon, val catalog: Catalog) {
        val title: String get() = "${catalog.name} · ${addon.name}"
    }

    /** Every catalogue that can be opened without extra parameters. */
    fun shelves(context: Context): List<Shelf> =
        installed(context).flatMap { addon ->
            addon.catalogs.filter { it.browsable }.map { Shelf(addon, it) }
        }

    /**
     * One page of a catalogue.
     *
     * `skip` is the protocol's own paging parameter and is passed only when non-zero -- a few
     * add-ons mishandle `skip=0` as a required-extra violation and answer with nothing.
     */
    fun catalogItems(shelf: Shelf, skip: Int = 0): List<Meta> {
        val suffix = if (skip > 0) "/skip=$skip.json" else ".json"
        val url = "${shelf.addon.baseUrl}/catalog/${shelf.catalog.type}/${shelf.catalog.id}$suffix"
        return metasFrom(fetch(url), shelf.addon.id, shelf.catalog.type)
    }

    // ── Searching ──────────────────────────────────────────────────────────

    /**
     * Asks every installed add-on that can search.
     *
     * Only catalogues that DECLARE search support are queried. One without it answers with its
     * unfiltered contents, so querying it fills the results with items unrelated to what was typed
     * -- which looks like a broken search rather than an add-on that does not search.
     *
     * Blocking; callers run it off the main thread.
     */
    fun search(context: Context, query: String, limitPerCatalog: Int = 12): List<Meta> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<Meta>()

        installed(context).filter { it.canSearch }.forEach { addon ->
            addon.catalogs.filter { it.searchable }.forEach { catalog ->
                val url = "${addon.baseUrl}/catalog/${catalog.type}/${catalog.id}/search=$encoded.json"
                out += metasFrom(fetch(url), addon.id, catalog.type).take(limitPerCatalog)
            }
        }
        return out
    }

    private fun metasFrom(body: String?, addonId: String, fallbackType: String): List<Meta> {
        if (body == null) return emptyList()
        return runCatching {
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            (0 until metas.length()).mapNotNull { index ->
                val item = metas.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Meta(
                    addonId = addonId,
                    id = id,
                    type = item.optString("type").ifBlank { fallbackType },
                    name = item.optString("name"),
                    poster = item.optString("poster"),
                    description = item.optString("description"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ── Detail, including episodes ─────────────────────────────────────────

    /**
     * Full metadata for one item, with a series' episode list.
     *
     * Asks the add-on the item came from first, then any other add-on that declares `meta` for the
     * type and id -- a catalogue add-on may list an item it cannot describe in detail, and the
     * series case cannot proceed without the episode ids.
     */
    fun meta(context: Context, type: String, id: String, preferredAddonId: String? = null): Meta? {
        val candidates = installed(context)
            .filter { it.canMeta && it.supports("meta", type, id) }
            .sortedByDescending { it.id == preferredAddonId }

        candidates.forEach { addon ->
            val url = "${addon.baseUrl}/meta/$type/${URLEncoder.encode(id, "UTF-8")}.json"
            val body = fetch(url) ?: return@forEach
            val parsed = runCatching {
                val item = JSONObject(body).optJSONObject("meta") ?: return@runCatching null
                Meta(
                    addonId = addon.id,
                    id = item.optString("id").ifBlank { id },
                    type = item.optString("type").ifBlank { type },
                    name = item.optString("name"),
                    poster = item.optString("poster"),
                    description = item.optString("description"),
                    episodes = episodesFrom(item),
                )
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun episodesFrom(meta: JSONObject): List<Episode> {
        val videos = meta.optJSONArray("videos") ?: return emptyList()
        return (0 until videos.length()).mapNotNull { index ->
            val item = videos.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Episode(
                id = id,
                title = item.optString("title").ifBlank { item.optString("name") },
                season = item.optInt("season", 0),
                episode = item.optInt("episode", item.optInt("number", 0)),
                released = item.optString("released"),
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ── Streams ────────────────────────────────────────────────────────────

    /**
     * Every stream every capable add-on can offer for this id.
     *
     * THIS ASKS ALL OF THEM, and that is the point. In Stremio, metadata and streams come from
     * different add-ons -- Cinemeta and Anime Kitsu describe things and serve no streams at all, and
     * the add-ons that serve streams describe nothing. Asking only the add-on an item was found in
     * would mean catalogue add-ons never play anything, which is how this looked before.
     *
     * The type and idPrefix filters in each manifest decide who is asked, so an add-on that handles
     * only `kitsu:` ids is not bothered about an IMDb film.
     *
     * Blocking; callers run it off the main thread.
     */
    fun streamsFor(context: Context, type: String, id: String): List<Stream> {
        val out = mutableListOf<Stream>()

        installed(context).filter { it.supports("stream", type, id) }.forEach { addon ->
            val url = "${addon.baseUrl}/stream/$type/${URLEncoder.encode(id, "UTF-8")}.json"
            val body = fetch(url) ?: return@forEach
            runCatching {
                val array = JSONObject(body).optJSONArray("streams") ?: return@runCatching
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    out.add(
                        Stream(
                            url = item.optString("url"),
                            name = item.optString("name"),
                            title = item.optString("title").ifBlank { item.optString("description") },
                            externalUrl = item.optString("externalUrl"),
                            infoHash = item.optString("infoHash"),
                            ytId = item.optString("ytId"),
                            headers = proxyHeaders(item),
                            addonName = addon.name,
                        )
                    )
                }
            }
        }

        // Playable first; everything else is kept so the picker can explain why it cannot be used,
        // which is more useful than a list that silently omits most of what the add-ons returned.
        return out.sortedByDescending { it.playable }
    }

    private fun proxyHeaders(stream: JSONObject): Map<String, String> {
        val request = stream.optJSONObject("behaviorHints")
            ?.optJSONObject("proxyHeaders")
            ?.optJSONObject("request")
            ?: return emptyMap()
        val out = HashMap<String, String>()
        val keys = request.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = request.optString(key)
        }
        return out
    }

    /**
     * Reads an add-on's manifest without installing it.
     *
     * So a link handler can name what it is about to install. Blocking.
     */
    fun preview(transportUrl: String): Addon? = fetchManifest(transportUrl)

    /** Decodes a [Meta.lykeId] back into enough to fetch streams again. */
    fun metaFromLykeId(id: String, name: String = ""): Meta? {
        if (!isStremioId(id)) return null
        val parts = id.removePrefix(ID_PREFIX).split("|")
        if (parts.size != 3) return null
        return Meta(addonId = parts[0], type = parts[1], id = parts[2], name = name, poster = "", description = "")
    }

    // ── Manifest parsing ───────────────────────────────────────────────────

    private fun fetchManifest(transportUrl: String): Addon? {
        val url = normaliseTransport(transportUrl)
        val body = fetch(url) ?: return null
        return runCatching { parseManifest(JSONObject(body), url) }.getOrNull()
    }

    private fun parseManifest(manifest: JSONObject, transportUrl: String): Addon? {
        val id = manifest.optString("id")
        val name = manifest.optString("name")
        if (id.isBlank() || name.isBlank()) return null

        val types = manifest.optJSONArray("types")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()

        val topPrefixes = manifest.optJSONArray("idPrefixes")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()

        // `resources` may be plain strings or objects carrying their own filters. Both forms are in
        // the specification and both are common; the object form is what makes correct routing of
        // stream requests possible at all.
        val resources = mutableListOf<ResourceSpec>()
        manifest.optJSONArray("resources")?.let { array ->
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> resources.add(ResourceSpec(item, emptyList(), topPrefixes))
                    is JSONObject -> {
                        val resourceName = item.optString("name")
                        if (resourceName.isBlank()) continue
                        resources.add(
                            ResourceSpec(
                                name = resourceName,
                                types = item.optJSONArray("types")?.let { t ->
                                    (0 until t.length()).map { t.optString(it) }
                                }.orEmpty(),
                                idPrefixes = item.optJSONArray("idPrefixes")?.let { p ->
                                    (0 until p.length()).map { p.optString(it) }
                                }.orEmpty().ifEmpty { topPrefixes },
                            )
                        )
                    }
                }
            }
        }

        val catalogs = manifest.optJSONArray("catalogs")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Catalog(
                    type = item.optString("type"),
                    id = item.optString("id"),
                    name = item.optString("name").ifBlank { item.optString("id") },
                    searchable = declaresExtra(item, "search"),
                    requiredExtras = requiredExtras(item),
                )
            }
        }.orEmpty()

        return Addon(
            id = id,
            name = name,
            description = manifest.optString("description"),
            version = manifest.optString("version"),
            logo = manifest.optString("logo").ifBlank { manifest.optString("icon") },
            transportUrl = transportUrl,
            types = types,
            catalogs = catalogs,
            resources = resources,
        )
    }

    /** An extra is declared either as an `extra` object or in the older `extraSupported` list. */
    private fun declaresExtra(catalog: JSONObject, wanted: String): Boolean {
        catalog.optJSONArray("extra")?.let { extra ->
            for (index in 0 until extra.length()) {
                if (extra.optJSONObject(index)?.optString("name") == wanted) return true
            }
        }
        catalog.optJSONArray("extraSupported")?.let { supported ->
            for (index in 0 until supported.length()) {
                if (supported.optString(index) == wanted) return true
            }
        }
        return false
    }

    private fun requiredExtras(catalog: JSONObject): List<String> {
        val out = mutableListOf<String>()
        catalog.optJSONArray("extra")?.let { extra ->
            for (index in 0 until extra.length()) {
                val item = extra.optJSONObject(index) ?: continue
                if (item.optBoolean("isRequired")) out.add(item.optString("name"))
            }
        }
        catalog.optJSONArray("extraRequired")?.let { required ->
            for (index in 0 until required.length()) out.add(required.optString(index))
        }
        return out.filter { it.isNotBlank() }
    }

    /**
     * Turns anything a user might paste into a manifest URL.
     *
     * `stremio://` is the scheme an add-on's own "Install" button uses, and it is a plain HTTPS URL
     * underneath. A bare host is completed with `/manifest.json`, because that is the one path the
     * protocol fixes, and pasting an add-on's home page is the obvious mistake to absorb rather
     * than reject.
     */
    fun normaliseTransport(url: String): String {
        val trimmed = url.trim()
            .removePrefix("stremio://")
            .let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        return if (trimmed.endsWith("/manifest.json")) trimmed
        else trimmed.trimEnd('/') + "/manifest.json"
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    // ── HTTP ───────────────────────────────────────────────────────────────

    private fun fetch(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Downloads a poster or logo. Small images only; callers are off the main thread. */
    fun fetchImage(url: String): android.graphics.Bitmap? = runCatching {
        if (url.isBlank()) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
