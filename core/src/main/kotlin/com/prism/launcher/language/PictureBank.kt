package com.prism.launcher.language

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
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

    private fun dir(): File =
        File(PrismPlatform.host.dataDir(), DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(conceptId: String) = File(dir(), "$conceptId.jpg")

    /** True when this concept's picture is already on disk. */
    fun isCached(conceptId: String): Boolean =
        fileFor(conceptId).let { it.isFile && it.length() > 0 }

    /**
     * The picture for a concept, as whatever is available right now.
     *
     * Never does I/O beyond reading a cached file, and never blocks on the network — a lesson that
     * paused for a download would be a lesson that fails on a bad connection.
     */
    fun picture(item: LessonSpec.PictureItem): Picture {
        val glyph = item.emoji
        return when {
            glyph != null -> Picture.Emoji(glyph)
            isCached(item.conceptId) -> Picture.Photo(fileFor(item.conceptId))
            else -> Picture.None
        }
    }

    sealed interface Picture {
        data class Emoji(val glyph: String) : Picture
        data class Photo(val file: File) : Picture
        data object None : Picture
    }

    /**
     * The picture's bytes, for a platform to decode however it decodes pictures.
     *
     * BYTES RATHER THAN A BITMAP, which is the one signature that used to pin this file to Android.
     * The caller has a decoder -- `BitmapFactory` on the phone, `PrismPlatform.images` or Skia on a
     * desktop -- and handing back a decoded `android.graphics.Bitmap` meant this whole file, network
     * fetching and Commons API and all, could only exist on one platform.
     */
    fun bytes(file: File): ByteArray? = runCatching {
        file.takeIf { it.isFile && it.length() > 0 }?.readBytes()
    }.getOrNull()

    /**
     * Fetches whatever this lesson needs and is missing, on the caller's thread.
     *
     * Called from a background prefetch, never from the lesson itself. Returns how many were
     * actually fetched, for the log.
     */
    fun prefetch(items: List<LessonSpec.PictureItem>): Int {
        var fetched = 0
        items.forEach { item ->
            val query = item.imageQuery ?: return@forEach
            if (item.emoji != null) return@forEach
            if (isCached(item.conceptId)) return@forEach
            if (item.conceptId in missing) return@forEach

            if (download(item.conceptId, query)) fetched++ else missing.add(item.conceptId)
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
    fun prefetchAll(): Int {
        val items = Concepts.needingImages().map {
            LessonSpec.PictureItem(it.id, it.english, null, it.emoji, it.imageQuery)
        }
        val count = prefetch(items)
        PrismPlatform.log.info(TAG, "Picture prefetch: $count fetched, ${missing.size} unavailable")
        return count
    }

    /**
     * One image, from Commons.
     *
     * `gsrnamespace=6` restricts the search to file pages, and `iiurlwidth` asks Commons to render a
     * thumbnail rather than handing over an original that can be forty megabytes — the full-size
     * file would be a spectacularly bad thing to pull onto a phone for a 200dp picture.
     */
    private fun download(conceptId: String, query: String): Boolean = runCatching {
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

        // CHECKED BEFORE IT IS KEPT, because a file that is not an image would otherwise sit in the
        // cache forever and fail silently every time a lesson tried to show it. This used to be a
        // BitmapFactory decode; it is a magic-number check now, which is both portable and cheaper --
        // decoding a whole JPEG to find out whether it is a JPEG was never the point.
        if (!looksLikeAnImage(bytes)) return false

        fileFor(conceptId).writeBytes(bytes)
        true
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "Could not fetch a picture for $conceptId: ${it.message}")
        false
    }

    /**
     * Whether these bytes begin like a picture Commons would have served.
     *
     * JPEG, PNG, GIF and WebP, by magic number. Commons serves the thumbnail as JPEG or PNG in
     * practice, so the last two are only there because accepting a valid image is free and the cost of
     * a false negative is a lesson with no picture.
     */
    private fun looksLikeAnImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        // JPEG: FF D8 FF
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return true
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return true
        // GIF8
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38) return true
        // RIFF....WEBP
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        ) return true
        return false
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
    fun cacheSizeBytes(): Long =
        dir().listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        dir().listFiles()?.forEach { it.delete() }
        missing.clear()
    }
}
