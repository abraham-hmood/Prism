package com.prism.launcher.writer

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where GIFs and stickers come from.
 *
 * ## Why this needs a key, and why one is not shipped
 *
 * Every general GIF library — Tenor, Giphy — requires a developer key, and their terms tie that key
 * to the application registering it. Embedding one taken from somewhere else would be someone
 * else's quota and someone else's terms of service, and it would stop working the moment it was
 * noticed. So the key is a setting: blank by default, and the panel says plainly what is missing
 * rather than showing an empty grid that looks broken.
 *
 * Tenor's v2 API is used because its free tier does not require attribution UI beyond the standard
 * "via Tenor" and its search response is small enough to parse without a JSON library.
 */
object WriterGifSource {

    data class Gif(
        /** A small looping preview, for the grid. */
        val previewUrl: String,
        /** The full-size file, which is what gets sent. */
        val url: String,
        val description: String,
    )

    enum class Kind { GIF, STICKER }

    fun hasKey(): Boolean = PrismSettings.getWriterGifApiKey().isNotBlank()

    /**
     * Searches, returning an empty list on any failure.
     *
     * BLOCKING, and documented as such: the caller is a keyboard and must not run this on the main
     * thread. Returning empty rather than throwing keeps a network blip from taking the IME down.
     */
    fun search(query: String, kind: Kind = Kind.GIF, limit: Int = 24): List<Gif> {
        val key = PrismSettings.getWriterGifApiKey()
        if (key.isBlank()) return emptyList()

        val term = URLEncoder.encode(query.ifBlank { "trending" }, "UTF-8")
        val filter = if (kind == Kind.STICKER) "&searchfilter=sticker" else ""
        val endpoint =
            "https://tenor.googleapis.com/v2/search?q=$term&key=$key&limit=$limit&media_filter=tinygif,gif$filter"

        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parse(body)
        }.onFailure {
            PrismPlatform.log.warn("PrismWriter", "GIF search failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Pulls the URLs out of Tenor's response.
     *
     * Hand-parsed rather than via a JSON library, because :core has none and the shape needed here
     * is two fields per result. Deliberately tolerant: an unexpected entry is skipped rather than
     * failing the whole search, since one malformed result should not empty the grid.
     */
    private fun parse(body: String): List<Gif> {
        val out = ArrayList<Gif>()
        var index = 0
        while (true) {
            val tiny = body.indexOf("\"tinygif\"", index)
            if (tiny < 0) break
            val full = body.indexOf("\"gif\"", tiny)

            val preview = urlAfter(body, tiny)
            val original = if (full > 0) urlAfter(body, full) else preview
            if (preview != null && original != null) {
                out.add(Gif(preview, original, "GIF"))
            }
            index = if (full > tiny) full + 5 else tiny + 9
        }
        return out
    }

    /** The first `"url": "…"` following [from]. */
    private fun urlAfter(body: String, from: Int): String? {
        val key = body.indexOf("\"url\"", from)
        if (key < 0) return null
        val open = body.indexOf('"', body.indexOf(':', key) + 1)
        if (open < 0) return null
        val close = body.indexOf('"', open + 1)
        if (close < 0) return null
        return body.substring(open + 1, close).replace("\\/", "/")
    }
}
