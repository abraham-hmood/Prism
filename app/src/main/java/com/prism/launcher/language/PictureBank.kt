package com.prism.launcher.language

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.prism.launcher.PrismLogger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The pictures A0 teaches with.
 *
 * ## Emoji first, and it is not a compromise
 *
 * A0 needs an unmistakable image of a concrete thing. For most of them an emoji is the best
 * possible answer, not a cheap one: it is instant, weightless, always available offline, drawn in
 * the device's own style so it matches everything else on screen, and — the part that matters
 * pedagogically — it is a *symbol* rather than a photograph, so nothing incidental in it can be
 * mistaken for the thing being named. A photo of an apple is also a photo of a table, a hand and a
 * kitchen. 🍎 is an apple.
 *
 * ## And a real photograph where no emoji exists
 *
 * "Table", "street", "wall", "sitting down" — Unicode has no symbol for these and the lesson still
 * has to show something. Those are fetched from Wikimedia Commons, which is the right source for
 * exactly one reason: everything on it is freely licensed, so a language app can ship what it
 * downloads without lying about where it came from.
 *
 * ## Why the search terms are written by hand
 *
 * Searching Commons for "orange" returns the colour, the fruit, a French commune and a telecoms
 * company. [Concepts.Concept.imageQuery] carries a curated phrase per concept for that reason, and
 * only the thirty-odd concepts that need one have one. An automatic query built from the English
 * word would be wrong often enough to teach the wrong thing, which is worse than no picture.
 *
 * ## Fetching is optional, always
 *
 * Every path here degrades to the emoji, then to the word alone. A learner on a plane with no cache
 * still gets the lesson — the tutor says the word and the word is on screen, which is most of what
 * A0 is. Nothing here ever blocks a lesson from starting.
 */
object PictureBank {

    private const val TAG = "PrismLanguage"
    private const val DIR = "language/pictures"

    /** Wikimedia asks for a real User-Agent and is entitled to; anonymous scrapers get blocked. */
    private const val USER_AGENT = "PrismLauncher/1.0 (on-device language learning; contact via app)"

    private const val THUMB_WIDTH = 480
    private const val TIMEOUT_MS = 12_000

    /** Concepts already known to have no usable image, so a failed fetch is not retried forever. */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, conceptId: String) = File(dir(context), "$conceptId.jpg")

    /** True when this concept's picture is already on disk. */
    fun isCached(context: Context, conceptId: String): Boolean =
        fileFor(context, conceptId).let { it.isFile && it.length() > 0 }

    /**
     * The picture for a concept, as whatever is available right now.
     *
     * Never does I/O beyond reading a cached file, and never blocks on the network — a lesson that
     * paused for a download would be a lesson that fails on a bad connection.
     */
    fun picture(context: Context, item: LessonSpec.PictureItem): Picture {
        val glyph = item.emoji
        return when {
            glyph != null -> Picture.Emoji(glyph)
            isCached(context, item.conceptId) -> Picture.Photo(fileFor(context, item.conceptId))
            else -> Picture.None
        }
    }

    sealed interface Picture {
        data class Emoji(val glyph: String) : Picture
        data class Photo(val file: File) : Picture
        data object None : Picture
    }

    fun bitmap(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    /**
     * Fetches whatever this lesson needs and is missing, on the caller's thread.
     *
     * Called from a background prefetch, never from the lesson itself. Returns how many were
     * actually fetched, for the log.
     */
    fun prefetch(context: Context, items: List<LessonSpec.PictureItem>): Int {
        var fetched = 0
        items.forEach { item ->
            val query = item.imageQuery ?: return@forEach
            if (item.emoji != null) return@forEach
            if (isCached(context, item.conceptId)) return@forEach
            if (item.conceptId in missing) return@forEach

            if (download(context, item.conceptId, query)) fetched++ else missing.add(item.conceptId)
        }
        return fetched
    }

    /**
     * Fetches every concept in the catalogue that needs a photograph.
     *
     * Roughly thirty images. Run once, in the background, after setup finishes — so that by the
     * time the learner reaches the lessons that need them, they are already on the device and work
     * offline like everything else.
     */
    fun prefetchAll(context: Context): Int {
        val items = Concepts.needingImages().map {
            LessonSpec.PictureItem(it.id, it.english, null, it.emoji, it.imageQuery)
        }
        val count = prefetch(context, items)
        PrismLogger.logInfo(TAG, "Picture prefetch: $count fetched, ${missing.size} unavailable")
        return count
    }

    /**
     * One image, from Commons.
     *
     * `gsrnamespace=6` restricts the search to file pages, and `iiurlwidth` asks Commons to render a
     * thumbnail rather than handing over an original that can be forty megabytes — the full-size
     * file would be a spectacularly bad thing to pull onto a phone for a 200dp picture.
     */
    private fun download(context: Context, conceptId: String, query: String): Boolean = runCatching {
        val url = buildString {
            append("https://commons.wikimedia.org/w/api.php")
            append("?action=query&format=json&formatversion=2")
            append("&generator=search&gsrnamespace=6&gsrlimit=1")
            append("&gsrsearch=").append(URLEncoder.encode(query, "UTF-8"))
            append("&prop=imageinfo&iiprop=url&iiurlwidth=").append(THUMB_WIDTH)
        }

        val json = fetchText(url) ?: return false
        val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return false
        if (pages.length() == 0) return false

        val info = pages.getJSONObject(0).optJSONArray("imageinfo") ?: return false
        if (info.length() == 0) return false
        val thumb = info.getJSONObject(0).optString("thumburl").takeIf { it.isNotBlank() } ?: return false

        val bytes = fetchBytes(thumb) ?: return false
        if (bytes.size < 1024) return false

        // Decoded before it is kept: a file that is not an image would otherwise sit in the cache
        // forever and fail silently every time a lesson tried to show it.
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false

        fileFor(context, conceptId).writeBytes(bytes)
        true
    }.getOrElse {
        PrismLogger.logWarning(TAG, "Could not fetch a picture for $conceptId: ${it.message}")
        false
    }

    private fun fetchText(url: String): String? = openConnection(url)?.use { it.readBytes().decodeToString() }

    private fun fetchBytes(url: String): ByteArray? = openConnection(url)?.use { it.readBytes() }

    private fun openConnection(url: String): java.io.InputStream? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        return if (connection.responseCode in 200..299) connection.inputStream else {
            connection.disconnect()
            null
        }
    }

    /** Everything fetched, for a settings screen that wants to say how much space this uses. */
    fun cacheSizeBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        missing.clear()
    }
}
