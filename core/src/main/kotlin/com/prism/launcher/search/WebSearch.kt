package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Turns the drawer's search box into actual web results.
 *
 * THE DRAWER IS NOT A BROWSER. It needs a handful of title/URL/summary rows it can draw natively,
 * not a page to render, so every engine is reduced to the same small result shape here and the UI
 * never learns which engine answered. The engine still matters to the user, though, so the query
 * really goes to the one selected in Settings > Browser rather than to a hardcoded provider.
 *
 * TWO ENGINES NEED A DIFFERENT FRONT DOOR:
 *
 *   - DuckDuckGo's normal endpoint is a JavaScript shell. `html.duckduckgo.com` serves the same
 *     results server-rendered, which is why the URL differs while the engine does not.
 *   - Google now answers a plain HTTP GET with a "enable JavaScript" interstitial instead of
 *     results. Startpage proxies Google's index and still serves real result markup, so that is
 *     the address used when Google is selected. The result rows are Google's.
 *
 * Prism's own engine has no HTTP round trip at all: the index lives in this process.
 *
 * Lives in :core so the desktop shell can use the same parsers if it grows a drawer search. Tests
 * exercise the parsers directly against captured markup, with no network involved.
 */
object WebSearch {

    data class Result(
        val title: String,
        val url: String,
        val snippet: String = "",
    )

    /** How many rows the drawer shows. It is a launcher search, not a results page. */
    const val DEFAULT_LIMIT = 5

    private const val TAG = "WebSearch"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val DDG_HTML = "https://html.duckduckgo.com/html/"
    private const val DDG_LITE = "https://lite.duckduckgo.com/lite/?q="
    private const val STARTPAGE = "https://www.startpage.com/sp/search?query="
    private const val BING_RSS = "https://www.bing.com/search?format=rss&q="

    /** Human-readable name of the engine selected in Settings, for the section heading. */
    fun engineLabel(): String = when (PrismSettings.getSearchEngine()) {
        "prism" -> "Prism"
        "google" -> "Google"
        "bing" -> "Bing"
        "custom" -> customEngineHost() ?: "Custom"
        else -> "DuckDuckGo"
    }

    /**
     * Results for [query] from the engine currently selected in Settings.
     *
     * Never throws: a failed or blocked engine produces an empty list. The drawer still has app,
     * widget and notification matches to show, and a network error must not turn those into no
     * results at all.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Result> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                when (PrismSettings.getSearchEngine()) {
                    "prism" -> PrismSearchIndex.search(trimmed, limit = limit).map { r ->
                        Result(
                            title = r.title.ifBlank { hostOf(r.url) ?: r.url },
                            url = r.url,
                            snippet = r.snippet,
                        )
                    }

                    "google" -> parseStartpage(fetch(STARTPAGE + encode(trimmed)), limit)

                    "bing" -> parseBingRss(fetch(BING_RSS + encode(trimmed), RSS_ACCEPT), limit)

                    "custom" -> {
                        val url = PrismSettings.buildSearchUrl(trimmed)
                        if (!isHttpUrl(url)) emptyList()
                        else parseGeneric(
                            fetch(url),
                            limit,
                            baseUrl = url,
                            skipHost = hostOf(url),
                        )
                    }

                    else -> duckDuckGoResults(trimmed, limit)
                }
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Search failed for '${trimmed.take(80)}': ${it.message}")
            }.getOrDefault(emptyList())
        }
    }

    private const val RSS_ACCEPT = "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8"
    private const val HTML_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Shared HTTP client. OkHttp rather than HttpURLConnection because the engines' bot filters
     * care about the request as a whole, and the platform stack's default headers and connection
     * handling are not something we can shape usefully. It is already a dependency of :core for
     * the wallet, so this adds no artifact.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun fetch(
        url: String,
        accept: String = HTML_ACCEPT,
        formBody: String? = null,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply {
                if (formBody != null) {
                    // DuckDuckGo answers a GET from a client it does not recognise with a 202
                    // bot-challenge page. The same search submitted as a form gets real results.
                    post(
                        formBody.toRequestBody(
                            "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                        )
                    )
                } else {
                    get()
                }
            }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /**
     * DuckDuckGo, with the form-encoded endpoint first and its text-only view as a fallback.
     *
     * Both are the same engine and the same index. The fallback exists because the anti-bot
     * challenge is served as a 202 with a perfectly valid HTML body, so a status-code check alone
     * cannot tell a challenge from an empty result set; parsing is what decides whether to try the
     * other front door.
     */
    private fun duckDuckGoResults(query: String, limit: Int): List<Result> {
        val primary = runCatching {
            fetch(DDG_HTML, formBody = "q=" + encode(query))
        }.getOrNull()
        val parsed = primary?.let { parseDuckDuckGo(it, limit) }.orEmpty()
        if (parsed.isNotEmpty()) return parsed
        return parseDuckDuckGo(fetch(DDG_LITE + encode(query)), limit)
    }

    // ------------------------------------------------------------------ DuckDuckGo

    /**
     * DuckDuckGo's server-rendered results.
     *
     * Titles are `result__a` anchors whose href points at DuckDuckGo's `/l/?uddg=...` redirector,
     * so the real destination has to be pulled back out of the `uddg` parameter. Snippets come
     * from `result__snippet` anchors in the same order; a missing snippet is harmless.
     */
    internal fun parseDuckDuckGo(html: String, limit: Int = DEFAULT_LIMIT): List<Result> {
        val anchors = ANCHOR.findAll(html).toList()
        val anchorSnippets = anchors
            .filter {
                val attrs = it.groupValues[1]
                attrs.contains("result__snippet") || attrs.contains("result-snippet")
            }
            .map { cleanText(it.groupValues[2]) }
        // The lite view puts the snippet in a table cell rather than an anchor.
        val cellSnippets = SNIPPET_CELL.findAll(html).map { cleanText(it.groupValues[1]) }.toList()
        val snippets = anchorSnippets.ifEmpty { cellSnippets }
        val results = ArrayList<Result>(limit)
        val seen = HashSet<String>()

        for (anchor in anchors) {
            if (results.size >= limit) break
            val attrs = anchor.groupValues[1]
            // "result__a" on the HTML endpoint, "result-link" on the text-only lite view.
            if (!attrs.contains("result__a") && !attrs.contains("result-link")) continue
            val href = attrValue(attrs, "href") ?: continue
            val url = unwrapDuckDuckGo(href)
            if (!isHttpUrl(url)) continue
            val title = cleanText(anchor.groupValues[2])
            if (!isUsefulTitle(title)) continue
            val normalized = normalize(url)
            if (!seen.add(normalized)) continue
            results.add(
                Result(
                    title = title,
                    url = url,
                    snippet = snippets.getOrNull(results.size).orEmpty(),
                )
            )
        }
        return results
    }

    private fun unwrapDuckDuckGo(href: String): String {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        return queryParam(absolute, "uddg") ?: absolute
    }

    // ----------------------------------------------------------------------- Bing

    /**
     * Bing's RSS view. It is the same result set as the HTML page with none of the layout churn,
     * so it survives cosmetic changes to bing.com far better than scraping `<li class="b_algo">`.
     */
    internal fun parseBingRss(xml: String, limit: Int = DEFAULT_LIMIT): List<Result> {
        val results = ArrayList<Result>(limit)
        val seen = HashSet<String>()
        for (item in ITEM.findAll(xml)) {
            if (results.size >= limit) break
            val block = item.groupValues[1]
            val title = cleanText(xmlValue(block, "title"))
            val url = unescapeHtml(xmlValue(block, "link")).trim()
            if (!isUsefulTitle(title) || !isHttpUrl(url)) continue
            if (!seen.add(normalize(url))) continue
            results.add(
                Result(
                    title = title,
                    url = url,
                    snippet = cleanText(xmlValue(block, "description")),
                )
            )
        }
        return results
    }

    // ------------------------------------------------------------------- Google

    /**
     * Google results through Startpage.
     *
     * Startpage's markup puts the destination in the `aria-label` of the display-URL anchor, while
     * the title anchor itself is a `/clev` tracker. The two lists are in the same order, so they
     * are paired by index. Inline emotion CSS before an `<h2>` is stripped along with the other
     * tags by [cleanText].
     */
    internal fun parseStartpage(html: String, limit: Int = DEFAULT_LIMIT): List<Result> {
        val anchors = ANCHOR.findAll(html).toList()
        val destinations: List<String?> = anchors.mapNotNull { anchor ->
            val attrs = anchor.groupValues[1]
            if (!attrs.contains("display-url")) return@mapNotNull null
            attrValue(attrs, "aria-label")
        }.map { url ->
            // Startpage shortens long destinations in this label. A result that would send the
            // browser to a literal "..." is worse than one fewer row. Null keeps the title and
            // destination lists index-aligned instead of shifting later results onto wrong URLs.
            if (isHttpUrl(url) && !url.endsWith("...") && !url.endsWith("\u2026")) url else null
        }

        val titleAnchors = anchors.filter { it.groupValues[1].contains("result-title") }
        val results = ArrayList<Result>(limit)
        val seen = HashSet<String>()

        titleAnchors.forEachIndexed { index, anchor ->
            if (results.size >= limit) return@forEachIndexed
            val url = destinations.getOrNull(index) ?: return@forEachIndexed
            if (!seen.add(normalize(url))) return@forEachIndexed

            val content = anchor.groupValues[2]
            val heading = Regex("(?is)<h2[^>]*>(.*?)</h2>").find(content)?.groupValues?.get(1)
            val title = cleanText(heading ?: content)
            if (!isUsefulTitle(title)) return@forEachIndexed

            // The snippet is the first paragraph after the title, before the next result title.
            val nextTitle = titleAnchors.getOrNull(index + 1)?.range?.first
            val window = if (nextTitle != null && nextTitle > anchor.range.last) {
                html.substring(anchor.range.last + 1, nextTitle)
            } else {
                html.substring(anchor.range.last + 1).take(1_500)
            }
            val snippet = Regex("(?is)<p[^>]*>(.*?)</p>")
                .find(window)?.groupValues?.get(1)
                ?.let(::cleanText)
                .orEmpty()

            results.add(Result(title = title, url = url, snippet = snippet))
        }
        return results
    }

    // ------------------------------------------------------------------- Custom

    /**
     * Anything else the user pointed Settings at.
     *
     * No two custom engines share markup, so this takes the shape nearly all of them do: a link
     * wrapped around a heading, with a paragraph nearby. Engine chrome is skipped by rejecting
     * the search site's own host and common navigation labels.
     */
    internal fun parseGeneric(
        html: String,
        limit: Int = DEFAULT_LIMIT,
        baseUrl: String? = null,
        skipHost: String? = null,
    ): List<Result> {
        val results = ArrayList<Result>(limit)
        val seen = HashSet<String>()
        for (anchor in ANCHOR.findAll(html)) {
            if (results.size >= limit) break
            val attrs = anchor.groupValues[1]
            val href = attrValue(attrs, "href") ?: continue
            val url = absolutize(href, baseUrl) ?: continue
            if (!isHttpUrl(url)) continue

            val content = anchor.groupValues[2]
            val heading = Regex("(?is)<h[23][^>]*>(.*?)</h[23]>")
                .find(content)?.groupValues?.get(1)
            // A heading-wrapped link is a result even if it happens to share the search site's
            // host (some engines route every destination through their own domain). Chrome links
            // have no heading, which is what this host check is here to reject.
            val host = hostOf(url)
            if (heading == null && skipHost != null && host != null &&
                (host == skipHost || host.endsWith(".$skipHost"))
            ) continue
            val title = cleanText(heading ?: content)
            if (!isUsefulTitle(title) || isNavigationLabel(title)) continue
            val normalized = normalize(url)
            if (!seen.add(normalized)) continue

            val tail = html.substring(anchor.range.last + 1).take(600)
            val snippet = Regex("(?is)<p[^>]*>(.*?)</p>")
                .find(tail)?.groupValues?.get(1)
                ?.let(::cleanText)
                .orEmpty()

            results.add(Result(title = title, url = url, snippet = snippet))
        }
        return results
    }

    private fun absolutize(href: String, baseUrl: String?): String? {
        val unescaped = unescapeHtml(href).trim()
        if (unescaped.isEmpty() || unescaped.startsWith("#") ||
            unescaped.startsWith("javascript:", ignoreCase = true)
        ) return null
        if (unescaped.startsWith("//")) return "https:$unescaped"
        if (isHttpUrl(unescaped)) return unescaped
        if (!unescaped.startsWith("/")) return null
        return runCatching {
            val base = URI(baseUrl).let { URI(it.scheme, it.authority, null, null, null) }
            base.resolve(unescaped).toString()
        }.getOrNull()
    }

    // -------------------------------------------------------------------- helpers

    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
    private val ITEM = Regex("(?is)<item\\b[^>]*>(.*?)</item>")
    private val SNIPPET_CELL = Regex(
        "(?is)<td\\b[^>]*class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>(.*?)</td>"
    )

    private fun attrValue(attrs: String, name: String): String? {
        val doubleQuoted = Regex("(?i)\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"")
            .find(attrs)?.groupValues?.get(1)
        if (doubleQuoted != null) return unescapeHtml(doubleQuoted)
        return Regex("(?i)\\b" + Regex.escape(name) + "\\s*=\\s*'([^']*)'")
            .find(attrs)?.groupValues?.get(1)
            ?.let(::unescapeHtml)
    }

    private fun xmlValue(block: String, tag: String): String {
        val raw = Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag>")
            .find(block)?.groupValues?.get(1)
            .orEmpty()
        return raw.removeSurrounding("<![CDATA[", "]]>")
    }

    private fun queryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (pair.substring(0, separator) != key) continue
            return runCatching {
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }.getOrNull()
        }
        return null
    }

    private fun normalize(url: String): String {
        val withoutFragment = url.substringBefore('#')
        return if (withoutFragment.endsWith("/")) withoutFragment.dropLast(1) else withoutFragment
    }

    private fun isHttpUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.removePrefix("www.")?.lowercase()
    }.getOrNull()

    private fun isUsefulTitle(title: String): Boolean =
        title.length in 3..180 && !isNavigationLabel(title)

    private val NAVIGATION_LABELS = setOf(
        "home", "about", "about us", "privacy", "privacy policy", "terms", "terms of service",
        "login", "log in", "sign in", "sign up", "settings", "help", "contact", "next",
        "previous", "more", "images", "videos", "news", "maps", "search", "feedback",
    )

    private fun isNavigationLabel(title: String): Boolean =
        title.lowercase().trim() in NAVIGATION_LABELS

    private fun customEngineHost(): String? = runCatching {
        hostOf(PrismSettings.getCustomSearchUrl())
    }.getOrNull()

    /**
     * Turns markup into readable text without pulling in an HTML parser. Quote entities are
     * decoded after tags are removed, so a title that literally contains `<` survives intact.
     */
    fun cleanText(html: String): String {
        val stripped = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
        return unescapeHtml(stripped).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Decodes HTML/XML character references in one pass.
     *
     * One pass matters: replacing `&amp;` first would turn `&amp;lt;` into `&lt;` and then into
     * `<`, silently changing escaped text that the source page meant to display literally.
     */
    fun unescapeHtml(input: String): String {
        if (!input.contains('&')) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = input.indexOf(';', startIndex = i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = input.substring(i + 1, end)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity == "nbsp" -> " "
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).toIntOrNull(16)?.let(::codePointString)
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let(::codePointString)
                else -> null
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun codePointString(codePoint: Int): String? {
        if (!Character.isValidCodePoint(codePoint)) return null
        return if (codePoint == 0xA0) " " else String(Character.toChars(codePoint))
    }
}
