package com.prism.launcher.podcasts

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.search.ArticleExtractor
import com.prism.launcher.search.PrismCrawler
import com.prism.launcher.search.WebSearch
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/**
 * What an episode is about this week: news, blog posts and social media, found fresh each time.
 *
 * ## Three kinds of source, on purpose
 *
 * News says what happened, blogs say what people who think about it for a living make of it, and
 * social media says how everyone else reacted. An episode built from only one of them sounds like a
 * press release, a lecture or a comment section; the hosts need all three to report the facts AND
 * have something to argue about.
 *
 *  - **News** comes from the search engine selected in Settings, asked for recent coverage, plus
 *    Google News' RSS feed, which is the one news index that dates every item.
 *  - **Blogs** come from the same engine, asked for analysis and opinion rather than reporting.
 *  - **Social** comes from the public JSON APIs of Reddit, Hacker News and Bluesky. Those sites
 *    forbid crawlers in robots.txt, and the crawler honours that, so their own APIs are the
 *    polite route to what people are saying.
 *
 * The news and blog pages are then fetched through [PrismCrawler], which honours robots.txt and
 * spaces its requests per host, and cut down to the paragraphs that mention the topic.
 */
object PodcastResearch {

    private const val TAG = "Podcasts"
    private const val USER_AGENT = "Mozilla/5.0 (compatible; PrismPodcasts/1.0; +https://prism.p2p)"

    data class Finding(
        val title: String,
        val url: String,
        /** "news", "blog" or "social". */
        val kind: String,
        /** The part of the page that is about the topic, already trimmed to a useful length. */
        val text: String,
    )

    /**
     * Everything worth talking about for [podcast]'s next episode.
     *
     * [avoid] are URLs earlier episodes already covered, so a weekly show does not report the same
     * article twice.
     */
    fun gather(
        podcast: Podcast,
        avoid: Set<String> = emptySet(),
        progress: (String) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        /**
         * A topic the user asked for. When given, IT is what is looked up -- not the show's keywords
         * and category -- and a source only counts if it is about it.
         */
        topic: String? = null,
    ): List<Finding> {
        val asked = topic?.trim()?.takeIf { it.isNotBlank() }
        val terms = asked ?: podcast.searchTerms()
        val keywords = if (asked != null) keywordsOfText(asked) else keywordsOf(podcast)
        val window = podcast.choice(PodcastCatalog.RECENCY)
        val findings = ArrayList<Finding>()

        if (podcast.toggle("news") && shouldContinue()) {
            progress("Looking up news about ${podcast.category}…")
            val hits = (bingNews(terms, window) + search("$terms news ${recencyWords(window)}", 8))
                .filter { it.url !in avoid && !isAggregator(it.url) }.distinctBy { it.url }
            findings += readPages(hits, "news", keywords, want = 6, progress = progress, shouldContinue = shouldContinue)
        }
        if (podcast.toggle("blogs") && shouldContinue()) {
            progress("Looking up blog posts…")
            val hits = (search("$terms blog", 8) + search("$terms analysis opinion", 6))
                .filter { it.url !in avoid && !isAggregator(it.url) }.distinctBy { it.url }
            findings += readPages(hits, "blog", keywords, want = 4, progress = progress, shouldContinue = shouldContinue)
        }
        if (podcast.toggle("social") && shouldContinue()) {
            progress("Reading what people are posting…")
            // Keywords alone: posts are short, and a query that also names the category ("Space &
            // Astronomy") matches almost nothing on a social site.
            val social = asked ?: podcast.keywords.take(3).joinToString(" ").ifBlank { podcast.category }
            findings += listOfNotNull(
                runCatching { reddit(social, window, shouldContinue) }.getOrNull(),
                runCatching { hackerNews(social, window, keywords, shouldContinue) }.getOrNull(),
                runCatching { mastodon(if (asked != null) listOf(asked) else podcast.keywords + podcast.category) }.getOrNull(),
                runCatching { bluesky(social) }.getOrNull(),
            )
        }
        // A show with all three switched off still needs something to say: fall back to the engine.
        if (findings.isEmpty() && shouldContinue()) {
            progress("Searching the web…")
            findings += readPages(search(terms, 8), "news", keywords, want = 5, progress = progress, shouldContinue = shouldContinue)
        }
        return findings
            .filter { it.text.isNotBlank() && it.url !in avoid }
            .distinctBy { it.url }
            .sortedByDescending { relevance(it, keywords) }
            .take(12)
    }

    // ── Search ──────────────────────────────────────────────────────────

    private fun search(query: String, limit: Int): List<WebSearch.Result> =
        runCatching { runBlocking { WebSearch.search(query, limit) } }.getOrDefault(emptyList())

    /**
     * Bing News' RSS: dated, filterable by time, and -- unlike Google News, whose links are redirect
     * pages that only a browser running JavaScript can follow -- carrying the real article address.
     */
    private fun bingNews(terms: String, window: String): List<WebSearch.Result> {
        val interval = when (window) {
            "Past day" -> "&qft=interval%3d%227%22"
            "Past week" -> "&qft=interval%3d%228%22"
            "Past month" -> "&qft=interval%3d%229%22"
            else -> ""
        }
        val xml = http("https://www.bing.com/news/search?format=rss&count=12&q=" + encode(terms) + interval, "application/rss+xml,application/xml;q=0.9")
            ?: return emptyList()
        return Regex("(?is)<item>(.*?)</item>").findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = tag(item, "title")
            val link = articleUrl(tag(item, "link"))
            if (title.isBlank() || !link.startsWith("http")) null
            else WebSearch.Result(title, link, WebSearch.cleanText(tag(item, "description")))
        }.take(10).toList()
    }

    /** The publisher's own URL out of a news aggregator's click-through link. */
    internal fun articleUrl(link: String): String {
        val query = link.substringAfter('?', "")
        val target = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=")
        return target?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: link
    }

    /** Pages that are lists of other pages, not stories: reading them yields headlines, nothing else. */
    private fun isAggregator(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return true
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        return host.startsWith("news.google.") || host.contains("youtube.") || host.contains("youtu.be") ||
            path.trimEnd('/').isEmpty() ||
            Regex("/(tag|tags|topic|topics|category|search|section)/").containsMatchIn(path)
    }

    /**
     * Visits each result and keeps the article itself -- its paragraphs, not its headline, menus or
     * sidebar -- through [ArticleExtractor]. Pages are read four at a time; a result with no readable
     * story (a video, a paywall, an index page) is skipped and the next one tried, until [want]
     * stories about the topic have been read.
     */
    private fun readPages(
        hits: List<WebSearch.Result>,
        kind: String,
        keywords: List<String>,
        want: Int,
        progress: (String) -> Unit,
        shouldContinue: () -> Boolean,
    ): List<Finding> {
        if (hits.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = hits.map { hit ->
                pool.submit<Finding?> {
                    if (!shouldContinue()) return@submit null
                    val article = ArticleExtractor.fetch(hit.url) ?: return@submit null
                    // A paywall teaser or a one-paragraph brief is a headline with a sentence, not a story.
                    if (article.text.length < 400) return@submit null
                    val lower = (article.title + " " + article.text).lowercase()
                    // A story that never mentions the show's subject is not research for it.
                    if (keywords.isNotEmpty() && keywords.none { it in lower }) return@submit null
                    Finding(
                        title = article.title.ifBlank { hit.title },
                        url = article.url,
                        kind = kind,
                        text = article.text,
                    )
                }
            }
            val out = ArrayList<Finding>()
            for ((i, job) in jobs.withIndex()) {
                if (out.size >= want || !shouldContinue()) break
                val finding = runCatching { job.get(45, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: continue
                out += finding
                progress("Read ${out.size} ${if (kind == "news") "articles" else "blog posts"} (${i + 1} of ${hits.size} pages visited)…")
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** Reddit's discussion of the topic: each post's own text and its top comments, as one source. */
    private fun reddit(terms: String, window: String, shouldContinue: () -> Boolean): Finding? {
        val t = when (window) { "Past day" -> "day"; "Past week" -> "week"; "Past month" -> "month"; "Past year" -> "year"; else -> "all" }
        val json = http("https://www.reddit.com/search.json?sort=relevance&limit=8&t=$t&q=" + encode(terms), "application/json")
            ?: return null
        val children = JSONObject(json).optJSONObject("data")?.optJSONArray("children") ?: return null
        val posts = (0 until children.length()).mapNotNull { children.optJSONObject(it)?.optJSONObject("data") }
            .filter { it.optString("title").isNotBlank() && !it.optBoolean("over_18") }
            .take(5)
        if (posts.isEmpty()) return null
        val text = posts.joinToString("\n\n") { post ->
            buildString {
                append("Post in r/").append(post.optString("subreddit")).append(" (")
                    .append(post.optInt("score")).append(" upvotes): ").append(post.optString("title"))
                post.optString("selftext").trim().takeIf { it.isNotBlank() }?.let { append("\n").append(it.take(1500)) }
                if (shouldContinue()) {
                    val comments = redditComments(post.optString("id"))
                    if (comments.isNotEmpty()) append("\nTop replies:\n").append(comments.joinToString("\n") { "- $it" })
                }
            }
        }
        return Finding("Reddit discussion", "https://www.reddit.com/search/?q=" + encode(terms), "social", text)
    }

    private fun redditComments(id: String): List<String> {
        if (id.isBlank()) return emptyList()
        val json = http("https://www.reddit.com/comments/$id.json?sort=top&limit=6&depth=1", "application/json") ?: return emptyList()
        return runCatching {
            val listing = com.prism.core.json.JSONArray(json).optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children")
                ?: return emptyList()
            (0 until listing.length()).mapNotNull { listing.optJSONObject(it)?.optJSONObject("data")?.optString("body") }
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length > 30 && it != "[deleted]" && it != "[removed]" }
                .take(4).map { it.take(500) }
        }.getOrDefault(emptyList())
    }

    /** Hacker News: each story's comments, and the linked article itself read in full. */
    private fun hackerNews(terms: String, window: String, keywords: List<String>, shouldContinue: () -> Boolean): Finding? {
        val seconds = when (window) { "Past day" -> 86_400L; "Past week" -> 604_800L; "Past month" -> 2_592_000L; "Past year" -> 31_536_000L; else -> 0L }
        val filter = if (seconds > 0) "&numericFilters=created_at_i>" + (System.currentTimeMillis() / 1000 - seconds) else ""
        val json = http("https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=4&query=" + encode(terms) + filter, "application/json")
            ?: return null
        val hits = JSONObject(json).optJSONArray("hits") ?: return null
        val stories = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.filter { it.optString("title").isNotBlank() }.take(3)
        if (stories.isEmpty()) return null
        val text = stories.joinToString("\n\n") { story ->
            buildString {
                append("Hacker News story (").append(story.optInt("points")).append(" points): ").append(story.optString("title"))
                WebSearch.cleanText(story.optString("story_text")).takeIf { it.isNotBlank() }?.let { append("\n").append(it.take(1200)) }
                if (shouldContinue()) {
                    val item = http("https://hn.algolia.com/api/v1/items/" + story.optString("objectID"), "application/json")
                    val kids = item?.let { runCatching { JSONObject(it).optJSONArray("children") }.getOrNull() }
                    if (kids != null) {
                        val comments = (0 until kids.length()).mapNotNull { kids.optJSONObject(it)?.optString("text") }
                            .map { WebSearch.cleanText(it) }.filter { it.length > 40 }.take(4)
                        if (comments.isNotEmpty()) append("\nTop comments:\n").append(comments.joinToString("\n") { "- " + it.take(500) })
                    }
                    val link = story.optString("url")
                    if (link.startsWith("http") && shouldContinue()) {
                        ArticleExtractor.fetch(link, 4_000)?.let { article ->
                            if (keywords.isEmpty() || keywords.any { it in article.text.lowercase() }) {
                                append("\nThe linked article says:\n").append(article.text)
                            }
                        }
                    }
                }
            }
        }
        return Finding("Hacker News discussion", "https://hn.algolia.com/?q=" + encode(terms), "social", text)
    }

    /** Public Mastodon posts under the show's keywords as hashtags. No account needed. */
    private fun mastodon(subjects: List<String>): Finding? {
        val tags = subjects.flatMap { listOf(it) + it.split(Regex("\\s+")) }.map { it.replace(Regex("[^\\p{L}0-9]"), "") }
            .filter { it.length >= 3 }.distinct().take(3)
        val posts = ArrayList<Pair<Int, String>>()
        for (tagName in tags) {
            val json = http("https://mastodon.social/api/v1/timelines/tag/" + encode(tagName) + "?limit=20", "application/json") ?: continue
            val array = runCatching { com.prism.core.json.JSONArray(json) }.getOrNull() ?: continue
            for (i in 0 until array.length()) {
                val post = array.optJSONObject(i) ?: continue
                if (post.optBoolean("sensitive")) continue
                val body = WebSearch.cleanText(post.optString("content")).replace(Regex("\\s+"), " ").trim()
                if (body.length < 60) continue
                val score = post.optInt("favourites_count") + 2 * post.optInt("reblogs_count")
                posts += score to "@" + post.optJSONObject("account")?.optString("acct").orEmpty() + ": " + body.take(600)
            }
        }
        if (posts.isEmpty()) return null
        val text = posts.sortedByDescending { it.first }.map { it.second }.distinct().take(8).joinToString("\n")
        return Finding("Posts on Mastodon", "https://mastodon.social/tags/" + encode(tags.first()), "social", text)
    }

    private fun bluesky(terms: String): Finding? {
        val json = http("https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?limit=10&sort=top&q=" + encode(terms), "application/json")
            ?: return null
        val posts = JSONObject(json).optJSONArray("posts") ?: return null
        val lines = (0 until posts.length()).mapNotNull { i ->
            val post = posts.optJSONObject(i) ?: return@mapNotNull null
            val text = post.optJSONObject("record")?.optString("text").orEmpty().replace(Regex("\\s+"), " ").trim()
            if (text.length < 40) null
            else "@" + post.optJSONObject("author")?.optString("handle").orEmpty() + " (" + post.optInt("likeCount") + " likes): " + text.take(600)
        }
        if (lines.isEmpty()) return null
        return Finding("Posts on Bluesky", "https://bsky.app/search?q=" + encode(terms), "social", lines.joinToString("\n"))
    }

    // ── Cover art ───────────────────────────────────────────────────────

    /** A picture found on a page, and how well it seems to fit. */
    private data class Candidate(val url: String, val score: Int, val page: String)

    /**
     * A cover for [podcast] from the web: search, crawl what the search found and what those pages
     * link to, rank every image on the way, and take the best one that decodes into something
     * cover-shaped.
     *
     * Returns the square picture and the page it came from, or null when nothing usable turned up.
     */
    fun findCover(podcast: Podcast, progress: (String) -> Unit = {}, shouldContinue: () -> Boolean = { true }): Pair<PrismImage, String>? {
        val keywords = keywordsOf(podcast)
        val queries = listOf(
            podcast.keywords.take(4).joinToString(" ") + " " + podcast.category + " podcast cover art",
            podcast.keywords.take(4).joinToString(" ") + " " + podcast.category,
        )
        progress("Searching the web for cover art…")
        val seeds = queries.flatMap { search(it, 6) }.map { it.url }.distinct().take(10)
        if (seeds.isEmpty()) return null

        val candidates = ArrayList<Candidate>()
        progress("Crawling ${seeds.size} sites for artwork…")
        runCatching {
            PrismCrawler.crawl(
                PrismCrawler.Config(seeds = seeds, maxPages = 16, maxPerHost = 3, perHostDelayMs = 400),
                shouldContinue = shouldContinue,
                onHtml = { url, html -> synchronized(candidates) { candidates += imagesIn(html, url, keywords) } },
            )
        }

        val ranked = candidates.sortedByDescending { it.score }.distinctBy { it.url }.take(14)
        for ((index, candidate) in ranked.withIndex()) {
            if (!shouldContinue()) return null
            progress("Checking image ${index + 1} of ${ranked.size}…")
            val bytes = download(candidate.url, 8L * 1024 * 1024) ?: continue
            val image = runCatching { PrismPlatform.images.decode(bytes, 2048) }.getOrNull() ?: continue
            val shortSide = minOf(image.width, image.height)
            val aspect = image.width.toFloat() / image.height
            if (shortSide < 280 || aspect < 0.55f || aspect > 1.9f) continue
            return squareCover(image) to candidate.page
        }
        return null
    }

    /** Every plausible picture on a page, scored. Social-card images score highest: they are chosen to represent the page. */
    private fun imagesIn(html: String, pageUrl: String, keywords: List<String>): List<Candidate> {
        val out = ArrayList<Candidate>()
        val pageTitle = PrismCrawler.extractTitle(html).lowercase()
        val pageBonus = keywords.count { it in pageTitle }
        fun add(raw: String?, base: Int, alt: String = "") {
            val href = raw?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return
            val url = PrismCrawler.absolutize(WebSearch.unescapeHtml(href), pageUrl) ?: return
            if (!url.startsWith("http")) return
            val lower = url.lowercase()
            if (BAD_IMAGE.any { it in lower } || lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".ico")) return
            val text = (alt + " " + lower).lowercase()
            out += Candidate(url, base + pageBonus + 2 * keywords.count { it in text }, pageUrl)
        }
        META_IMAGE.findAll(html).forEach { m ->
            val tagText = m.value
            val content = Regex("(?is)content\\s*=\\s*[\"']([^\"']+)[\"']").find(tagText)?.groupValues?.get(1)
            add(content, if ("og:image" in tagText.lowercase()) 6 else 5)
        }
        IMG.findAll(html).take(60).forEach { m ->
            val attrs = m.groupValues[1]
            val src = attr(attrs, "data-src") ?: attr(attrs, "src") ?: attr(attrs, "srcset")?.substringBefore(',')
            val width = attr(attrs, "width")?.toIntOrNull() ?: 0
            val height = attr(attrs, "height")?.toIntOrNull() ?: 0
            if ((width in 1..199) || (height in 1..199)) return@forEach
            add(src, if (width >= 400 || height >= 400) 3 else 1, attr(attrs, "alt").orEmpty())
        }
        return out
    }

    /** Centre-cropped to a square and scaled to 1024, the size podcast apps expect artwork at. */
    fun squareCover(image: PrismImage, size: Int = 1024): PrismImage {
        val side = minOf(image.width, image.height)
        val x0 = (image.width - side) / 2
        val y0 = (image.height - side) / 2
        val crop = IntArray(side * side)
        for (y in 0 until side) System.arraycopy(image.pixels, (y0 + y) * image.width + x0, crop, y * side, side)
        return PrismImage(side, side, crop).scaledTo(size, size)
    }

    private val META_IMAGE = Regex("(?is)<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|og:image:url|twitter:image|twitter:image:src)[\"'][^>]*>")
    private val IMG = Regex("(?is)<img\\s([^>]+)>")
    private val BAD_IMAGE = listOf("logo", "icon", "sprite", "avatar", "pixel", "tracking", "spacer", "blank", "favicon", "badge", "ads.", "/ads/", "doubleclick", "emoji", "button", "banner-ad")

    private fun attr(attrs: String, name: String): String? =
        Regex("(?is)(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").find(attrs)?.groupValues?.get(1)

    // ── Text ────────────────────────────────────────────────────────────

    /** The lowercase words a source has to mention to count as being about this show. */
    fun keywordsOf(podcast: Podcast): List<String> =
        keywordsOfText((podcast.keywords + podcast.category).joinToString(" "))

    /** The same, for free text such as an episode topic the user typed. */
    fun keywordsOfText(text: String): List<String> =
        text.split(Regex("[\\s&/,()-]+"))
            .map { it.lowercase().trim(',', '.', '#', '"', '\'', '?', '!') }
            .filter { it.length >= 3 && it !in STOP }
            .distinct()

    /** The paragraphs of [text] that mention the topic, most relevant first, about 1,400 characters of them. */
    internal fun excerpt(text: String, keywords: List<String>, limit: Int = 1400): String {
        val paragraphs = text.split('\n').map { it.trim() }.filter { it.length > 60 }
        if (paragraphs.isEmpty()) return ""
        val scored = paragraphs.mapIndexed { index, p ->
            val lower = p.lowercase()
            Triple(index, p, keywords.count { it in lower })
        }.filter { it.third > 0 }
        val chosen = (scored.ifEmpty { paragraphs.take(3).mapIndexed { i, p -> Triple(i, p, 0) } })
            .sortedByDescending { it.third }
            .fold(ArrayList<Triple<Int, String, Int>>()) { acc, item ->
                if (acc.sumOf { it.second.length } < limit) acc += item
                acc
            }
            .sortedBy { it.first }
        return chosen.joinToString(" ") { it.second }.take(limit)
    }

    private fun relevance(finding: Finding, keywords: List<String>): Int {
        val lower = (finding.title + " " + finding.text).lowercase()
        return keywords.count { it in lower } * 2 + (if (finding.kind == "news") 1 else 0)
    }

    private fun recencyWords(window: String): String = when (window) {
        "Past day" -> "today"
        "Past week" -> "this week"
        "Past month" -> "this month"
        "Past year" -> Calendar.getInstance().get(Calendar.YEAR).toString()
        else -> ""
    }

    private val STOP = setOf("the", "and", "for", "with", "news", "podcast", "about", "from", "this", "that", "your", "into")

    // ── HTTP ────────────────────────────────────────────────────────────

    internal fun http(url: String, accept: String = "text/html,application/xml;q=0.9,*/*;q=0.8"): String? =
        download(url, 2L * 1024 * 1024, accept)?.toString(Charsets.UTF_8)

    private fun download(url: String, max: Long, accept: String = "image/*,*/*;q=0.8"): ByteArray? {
        var current = url
        repeat(4) {
            var conn: HttpURLConnection? = null
            try {
                conn = (com.prism.core.Urls.of(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    current = PrismCrawler.absolutize(conn.getHeaderField("Location") ?: return null, current) ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > max) return null
                    }
                }
                return out.toByteArray()
            } catch (e: Exception) {
                PrismPlatform.log.info(TAG, "Fetch failed for ${current.take(120)}: ${e.message}")
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun encode(value: String) = URLEncoder.encode(value.trim(), "UTF-8")

    private fun tag(block: String, name: String): String =
        Regex("(?is)<$name[^>]*>(.*?)</$name>").find(block)?.groupValues?.get(1)
            ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim().orEmpty()
            .let { WebSearch.unescapeHtml(it) }

    private fun sameUrl(a: String, b: String): Boolean =
        a.trimEnd('/').substringAfter("://").lowercase(Locale.ROOT) == b.trimEnd('/').substringAfter("://").lowercase(Locale.ROOT)
}
