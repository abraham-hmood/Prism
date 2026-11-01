package com.prism.launcher.stremio

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * A debrid account, as a way to play a torrent stream without a torrent client.
 *
 * ## What a debrid service actually does
 *
 * It runs the torrent for you. You hand it a magnet, it downloads the file on its own servers --
 * or, for anything popular, already has it -- and returns an ordinary HTTPS URL that streams like
 * any other video. That is why this is a few HTTP calls rather than a second downloader, and why
 * it is the better option on a phone: no pieces, no peers, no battery, and a seek is a range
 * request against a CDN.
 *
 * ## Why three providers
 *
 * Real-Debrid, Premiumize and TorBox are the ones Stremio add-ons are built around. Their APIs
 * look alike from a distance and disagree on every detail: how a torrent is added, whether the
 * file list arrives before or after selection, and what a "ready" status is called. Each flow
 * below is written against its own documentation rather than pretending one shape fits all.
 *
 * ## The key is the user's
 *
 * Prism ships no credentials and no provider. The token is stored in the app's private preferences,
 * is only ever sent to the provider it belongs to, and is treated as a secret in the settings UI.
 */
object DebridClient {

    sealed interface Result {
        /** A direct URL, ready for the player. */
        data class Ready(val url: String, val provider: String) : Result

        /** Tried and failed; the message is for the user. */
        data class Failed(val message: String) : Result

        /** No provider or no key, which is not an error -- the torrent client may handle it. */
        data object NotConfigured : Result
    }

    private const val TAG = "PrismDebrid"

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Resolves [infoHash] to a playable URL, or explains why it could not.
     *
     * Blocking. Callers run it off the UI thread. The status callback exists because a cold torrent
     * can spend a minute in the provider's queue, and a screen that says nothing for a minute reads
     * as broken.
     */
    fun resolve(
        infoHash: ByteArray,
        fileIdx: Int?,
        filename: String?,
        trackers: List<String>,
        onStatus: (String) -> Unit = {},
    ): Result {
        val provider = StremioPlaybackSettings.provider()
        val token = StremioPlaybackSettings.token()
        if (provider == StremioPlaybackSettings.NONE || token.isBlank()) return Result.NotConfigured

        onStatus("Asking " + StremioPlaybackSettings.label(provider) + "…")
        val magnet = MagnetLink(infoHash, baseName(filename), trackers).toMagnet()

        return try {
            when (provider) {
                StremioPlaybackSettings.REAL_DEBRID -> realDebrid(magnet, infoHash, filename, token, onStatus)
                StremioPlaybackSettings.PREMIUMIZE -> premiumize(magnet, filename, token)
                StremioPlaybackSettings.TORBOX -> torBox(magnet, filename, token, onStatus)
                else -> Result.NotConfigured
            }
        } catch (error: Exception) {
            PrismPlatform.log.warn(TAG, "Resolution failed: " + error.message)
            Result.Failed(StremioPlaybackSettings.label(provider) + ": " + (error.message ?: "request failed"))
        }
    }

    /**
     * Real-Debrid: add, select the file, wait, unrestrict.
     *
     * The selection step is the one that cannot be skipped. Without it Real-Debrid sits at
     * `waiting_files_selection` forever and the status looks like a hang. A single-file torrent
     * still needs a selection, because "all" is a selection.
     */
    private fun realDebrid(
        magnet: String,
        infoHash: ByteArray,
        filename: String?,
        token: String,
        onStatus: (String) -> Unit,
    ): Result {
        val base = "https://api.real-debrid.com/rest/1.0"

        val added = call("POST", "$base/torrents/addMagnet", mapOf("magnet" to magnet), token)
        val id = added.body?.optString("id").orEmpty()
        if (id.isBlank()) return Result.Failed("Real-Debrid: " + messageOf(added))

        var info: JSONObject? = null
        var selected = false
        val deadline = System.currentTimeMillis() + 60_000

        while (System.currentTimeMillis() < deadline) {
            info = call("GET", "$base/torrents/info/$id", null, token).body
            when (info?.optString("status").orEmpty()) {
                "downloaded" -> break
                "waiting_files_selection" -> {
                    if (!selected) {
                        val files = info?.optJSONArray("files")
                        val pick = pickLargest(files, filename)
                        val arg = pick?.optString("id").orEmpty().ifBlank { "all" }
                        call("POST", "$base/torrents/selectFiles/$id", mapOf("files" to arg), token)
                        selected = true
                    }
                    onStatus("Real-Debrid is preparing the file…")
                    Thread.sleep(1_000)
                }
                "error", "magnet_error" -> return Result.Failed("Real-Debrid could not add that torrent.")
                else -> {
                    onStatus("Real-Debrid is downloading it…")
                    Thread.sleep(1_200)
                }
            }
        }

        if (info?.optString("status") != "downloaded") {
            return Result.Failed("Real-Debrid is still preparing this torrent. Try again in a minute.")
        }

        val link = info.optJSONArray("links")?.optString(0).orEmpty()
        if (link.isBlank()) return Result.Failed("Real-Debrid returned no download link.")

        val unrestricted = call("POST", "$base/unrestrict/link", mapOf("link" to link), token)
        val url = unrestricted.body?.optString("download").orEmpty()
        if (url.isBlank()) return Result.Failed("Real-Debrid: " + messageOf(unrestricted))

        PrismPlatform.log.success(TAG, "Real-Debrid resolved " + infoHash.size + "-byte hash")
        return Result.Ready(url, StremioPlaybackSettings.label(StremioPlaybackSettings.REAL_DEBRID))
    }

    /**
     * Premiumize: one call resolves a direct download from a magnet.
     *
     * Premiumize returns the file list inline, so there is no polling and no selection step --
     * which is the whole reason this flow is a quarter the length of Real-Debrid's. A magnet that
     * has never been cached may come back with `status: success` and no content; that is reported
     * rather than retried in a loop, because Premiumize is already fetching it and the next attempt
     * will find it cached.
     */
    private fun premiumize(magnet: String, filename: String?, token: String): Result {
        val url = "https://www.premiumize.me/api/transfer/directdl?apikey=" + encode(token)
        val reply = call("POST", url, mapOf("src" to magnet), null)
        val body = reply.body ?: return Result.Failed("Premiumize: " + messageOf(reply))
        if (body.optString("status") != "success") {
            return Result.Failed("Premiumize: " + body.optString("message").ifBlank { "could not resolve that magnet" })
        }

        val content = body.optJSONArray("content")
        val chosen = pickContent(content, filename)
            ?: return Result.Failed("Premiumize is still fetching this torrent. Try again shortly.")
        val link = chosen.optString("link").ifBlank { chosen.optString("stream_link") }
        if (link.isBlank()) return Result.Failed("Premiumize returned no download link.")

        return Result.Ready(link, StremioPlaybackSettings.label(StremioPlaybackSettings.PREMIUMIZE))
    }

    /**
     * TorBox: create, poll the list, request a download link.
     *
     * `requestdl` needs the API token twice -- once as a bearer header and once as a query
     * parameter. That is not a mistake in this code; it is what the API documents, and omitting
     * the query copy returns a 401 that reads like a bad key.
     */
    private fun torBox(
        magnet: String,
        filename: String?,
        token: String,
        onStatus: (String) -> Unit,
    ): Result {
        val base = "https://api.torbox.app/v1/api"
        val created = call("POST", "$base/torrents/createtorrent", mapOf("magnet" to magnet), token)
        val data = created.body?.optJSONObject("data")
        val torrentId = data?.optLong("torrent_id", 0L) ?: 0L
        if (torrentId <= 0L) return Result.Failed("TorBox: " + messageOf(created))

        var entry: JSONObject? = null
        var fileId = -1L
        var fileName = ""
        val deadline = System.currentTimeMillis() + 60_000

        while (System.currentTimeMillis() < deadline) {
            val listed = call("GET", "$base/torrents/mylist?id=$torrentId", null, token)
            val list = listed.body?.opt("data")
            entry = when (list) {
                is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { it.optLong("id", -1L) == torrentId }

                is JSONObject -> list
                else -> null
            }

            val state = entry?.optString("download_state").orEmpty()
            if (entry != null && (state == "completed" || entry.optBoolean("download_finished"))) {
                val files = entry.optJSONArray("files")
                val chosen = pickLargest(files, filename)
                fileId = chosen?.optLong("id", -1L) ?: -1L
                fileName = chosen?.optString("name").orEmpty()
                break
            }
            onStatus("TorBox is downloading it" + if (state.isNotBlank()) " ($state)…" else "…")
            Thread.sleep(1_500)
        }

        if (fileId <= 0L) {
            return Result.Failed("TorBox is still preparing this torrent. Try again in a minute.")
        }

        val request = "$base/torrents/requestdl?token=" + encode(token) +
            "&torrent_id=$torrentId&file_id=$fileId&zip_link=false"
        val reply = call("GET", request, null, token)
        val link = when (val value = reply.body?.opt("data")) {
            is String -> value
            is JSONObject -> value.optString("url")
            else -> ""
        }
        if (link.isBlank()) return Result.Failed("TorBox: " + messageOf(reply) + fileName.let { if (it.isBlank()) "" else " ($it)" })
        return Result.Ready(link, StremioPlaybackSettings.label(StremioPlaybackSettings.TORBOX))
    }

    // ── Files ───────────────────────────────────────────────────────────────

    /** The file a stream names, or the largest one, which is the useful guess in a season pack. */
    private fun pickLargest(files: JSONArray?, filename: String?): JSONObject? {
        if (files == null || files.length() == 0) return null
        val wanted = baseName(filename).lowercase()
        if (wanted.isNotBlank()) {
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val path = file.optString("path").ifBlank { file.optString("name") }.lowercase()
                if (path.substringAfterLast('/') == wanted || path.endsWith(wanted)) return file
            }
        }
        var best: JSONObject? = null
        var bestSize = -1L
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val size = file.optLong("bytes", file.optLong("size", 0L))
            if (size > bestSize) {
                best = file
                bestSize = size
            }
        }
        return best
    }

    private fun pickContent(content: JSONArray?, filename: String?): JSONObject? {
        if (content == null || content.length() == 0) return null
        return pickLargest(content, filename) ?: content.optJSONObject(0)
    }

    private fun baseName(path: String?): String =
        path?.trim()?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "stream"

    // ── HTTP ────────────────────────────────────────────────────────────────

    private data class Reply(val code: Int, val body: JSONObject?)

    private fun call(method: String, url: String, form: Map<String, String>?, token: String?): Reply {
        val builder = okhttp3.Request.Builder().url(url).header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer " + token)

        val body = form?.let { fields ->
            okhttp3.FormBody.Builder().apply {
                fields.forEach { (key, value) -> add(key, value) }
            }.build()
        }

        val request = builder.method(method, body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { if (text.isBlank()) null else JSONObject(text) }.getOrNull()
            return Reply(response.code, parsed)
        }
    }

    private fun messageOf(reply: Reply): String {
        val body = reply.body ?: return "HTTP " + reply.code
        return body.optString("error")
            .ifBlank { body.optString("message") }
            .ifBlank { body.optString("detail") }
            .ifBlank { "HTTP " + reply.code }
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")
}

/**
 * The playback settings for Stremio: which debrid provider, its key, and whether torrents play.
 *
 * Deliberately in `PrismSettings`' shared preferences rather than a file of its own, so a profile
 * transfer carries the choice to another device. The token is excluded from nothing, which is a
 * known trade: it is a subscription key, not a wallet seed, and retyping it on every device would
 * make the feature unusable.
 */
object StremioPlaybackSettings {

    const val NONE = "none"
    const val REAL_DEBRID = "realdebrid"
    const val PREMIUMIZE = "premiumize"
    const val TORBOX = "torbox"

    /** In display order, for a picker. Index 0 is [NONE]. */
    val PROVIDERS = listOf(NONE, REAL_DEBRID, PREMIUMIZE, TORBOX)

    fun label(provider: String): String = when (provider) {
        REAL_DEBRID -> "Real-Debrid"
        PREMIUMIZE -> "Premiumize"
        TORBOX -> "TorBox"
        else -> "None"
    }

    fun provider(): String {
        val stored = prefs().getString(KEY_PROVIDER, NONE).orEmpty()
        return if (stored in PROVIDERS) stored else NONE
    }

    fun setProvider(value: String) {
        prefs().putString(KEY_PROVIDER, if (value in PROVIDERS) value else NONE)
    }

    fun token(): String = prefs().getString(KEY_TOKEN, "").orEmpty()

    fun setToken(value: String) {
        prefs().putString(KEY_TOKEN, value.trim())
    }

    /** Whether BitTorrent streams may be played by the built-in torrent client. */
    fun torrentsEnabled(): Boolean = prefs().getBoolean(KEY_TORRENTS, true)

    fun setTorrentsEnabled(value: Boolean) {
        prefs().putBoolean(KEY_TORRENTS, value)
    }

    /** What the settings row says under the provider's name. */
    fun summary(): String {
        val provider = provider()
        if (provider == NONE) {
            return if (torrentsEnabled()) {
                "No account — torrents play through the built-in client"
            } else {
                "No account, and torrents are off — direct links only"
            }
        }
        if (token().isBlank()) return label(provider) + " selected, no API key yet"
        return label(provider) + " · key saved"
    }

    /**
     * The MAIN settings file, not one of this feature's own.
     *
     * That is what makes a profile transfer carry the provider and the key to another device, and
     * what lets the shared settings catalog describe these rows without a second storage story.
     */
    private fun prefs() = PrismPlatform.host.prefs(com.prism.launcher.PrismSettings.PREFS)

    private const val KEY_PROVIDER = "stremio_debrid_provider"
    private const val KEY_TOKEN = "stremio_debrid_token"
    private const val KEY_TORRENTS = "stremio_torrents_enabled"
}
