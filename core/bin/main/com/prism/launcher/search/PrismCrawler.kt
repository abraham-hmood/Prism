package com.prism.launcher.search

import java.net.HttpURLConnection
import java.net.URL

/**
 * Breadth-first web crawler feeding [PrismSearchIndex].
 *
 * BREADTH-FIRST, NOT DEPTH-FIRST, and that is a ranking decision rather than a style one: a
 * depth-first crawl burrows into one site and returns a link graph that is mostly one host linking
 * to itself, which PageRank then reads as enormous authority for whatever that host happens to be.
 * Breadth-first with a per-host cap gives a graph with real cross-site links in it, which is the
 * only kind PageRank can say anything meaningful about.
 *
 * POLITE BY CONSTRUCTION. It identifies itself honestly in the User-Agent, honours robots.txt
 * disallow rules, caps how many pages it will take from any single host, and leaves a delay
 * between requests to the same host. A crawler that ignores those is indistinguishable from a
 * denial-of-service tool, and would get the user's IP blocked long before the index was useful.
 */
object PrismCrawler {

    const val USER_AGENT = "PrismSearch/1.0 (+https://prism.p2p; respects robots.txt)"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_REDIRECTS = 4

    data class Config(
        val seeds: List<String>,
        val maxPages: Int = 400,
        val maxPerHost: Int = 40,
        /** Courtesy delay between two requests to the SAME host, in milliseconds. */
        val perHostDelayMs: Long = 800L
    )

    /** Robots rules for one host: the disallowed path prefixes that apply to us. */
    private class Robots(val disallow: List<String>) {
        fun allows(path: String): Boolean = disallow.none { it.isNotEmpty() && path.startsWith(it) }
        companion object {
            val PERMISSIVE = Robots(emptyList())
        }
    }

    /**
     * What one crawl produced: the pages to index, and the origins of hosts it met along the way
     * that were not among its seeds.
     *
     * [newOrigins] is what lets the index widen over time. A crawl bounded by [Config.maxPages]
     * always stops with a frontier still queued, and most of that frontier is on hosts the seeds
     * never named; throwing it away means every crawl re-explores the same neighbourhood. Handing
     * it back lets the caller seed the NEXT crawl with it.
     */
    data class Crawled(
        val pages: List<PrismSearchIndex.Page>,
        val newOrigins: List<String>
    )

    /**
     * Crawls from [Config.seeds]. Pure: it does not touch the index or the settings, so a failed
     * or cancelled crawl cannot leave a half-replaced index or a polluted seed list behind -- the
     * caller swaps the result in atomically via [PrismSearchIndex.replaceAll] and decides for
     * itself what to do with [Crawled.newOrigins].
     *
     * [shouldContinue] is polled between fetches so a long crawl can be cancelled promptly.
     */
    fun crawl(
        config: Config,
        shouldContinue: () -> Boolean = { true },
        onProgress: (fetched: Int, queued: Int, url: String) -> Unit = { _, _, _ -> }
    ): Crawled {
        val queue = ArrayDeque<String>()
        val seen = HashSet<String>()
        val perHost = HashMap<String, Int>()
        val lastHit = HashMap<String, Long>()
        val robotsByHost = HashMap<String, Robots>()
        val pages = ArrayList<PrismSearchIndex.Page>()

        // Hosts the seeds already cover. Anything outside this set that we meet is a discovery.
        val seededHosts = HashSet<String>()
        val discovered = LinkedHashMap<String, String>()   // host -> origin, first sighting wins

        for (s in config.seeds) {
            val n = PrismSearchIndex.normalize(s)
            if (n.isNotBlank() && seen.add(n)) queue.addLast(n)
            hostOf(n)?.let { seededHosts.add(it) }
        }

        while (queue.isNotEmpty() && pages.size < config.maxPages && shouldContinue()) {
            val url = queue.removeFirst()
            val host = hostOf(url) ?: continue

            if ((perHost[host] ?: 0) >= config.maxPerHost) continue

            val robots = robotsByHost.getOrPut(host) { fetchRobots(url) }
            if (!robots.allows(pathOf(url))) continue

            // Courtesy delay, per host rather than global, so a broad frontier still moves.
            val since = System.currentTimeMillis() - (lastHit[host] ?: 0L)
            if (since < config.perHostDelayMs) {
                try { Thread.sleep(config.perHostDelayMs - since) } catch (e: InterruptedException) { break }
            }
            lastHit[host] = System.currentTimeMillis()

            val html = fetch(url) ?: continue
            perHost[host] = (perHost[host] ?: 0) + 1

            val title = extractTitle(html).ifBlank { url }
            val text = htmlToText(html)
            val links = extractLinks(html, url)

            pages.add(PrismSearchIndex.Page(url, title, text, links, description = extractDescription(html)))
            onProgress(pages.size, queue.size, url)

            for (link in links) {
                val n = PrismSearchIndex.normalize(link)
                if (n.isBlank() || !n.startsWith("http")) continue

                // Record the host even when the link itself never gets fetched -- a new host is
                // worth remembering precisely BECAUSE this crawl ran out of budget before
                // reaching it. Recorded as an origin (scheme://host) rather than the deep link
                // that happened to reveal it, so a future crawl starts at that site's front door.
                val linkHost = hostOf(n)
                if (linkHost != null && linkHost !in seededHosts && !discovered.containsKey(linkHost)) {
                    originOf(n)?.let { discovered[linkHost] = it }
                }

                if (seen.size > config.maxPages * 12) break   // frontier bound
                if (seen.add(n)) queue.addLast(n)
            }
        }
        return Crawled(pages, discovered.values.toList())
    }

    // -- Fetching -------------------------------------------------------------------------

    private fun fetch(urlStr: String): String? {
        var current = urlStr
        repeat(MAX_REDIRECTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    current = absolutize(location, current) ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = conn.contentType ?: ""
                // Only HTML is indexable here; downloading a PDF or a video to throw it away is
                // pure cost, and the crawler has a byte budget to respect.
                if (!type.contains("html", ignoreCase = true)) return null

                val buf = ByteArray(MAX_BYTES)
                var read = 0
                conn.inputStream.use { input ->
                    while (read < MAX_BYTES) {
                        val n = input.read(buf, read, MAX_BYTES - read)
                        if (n == -1) break
                        read += n
                    }
                }
                return String(buf, 0, read, Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /** Fetches and parses `/robots.txt`, taking the `*` group plus any group naming PrismSearch. */
    private fun fetchRobots(anyUrlOnHost: String): Robots {
        val root = try {
            val u = URL(anyUrlOnHost)
            "${u.protocol}://${u.authority}/robots.txt"
        } catch (e: Exception) {
            return Robots.PERMISSIVE
        }
        val body = fetchRaw(root) ?: return Robots.PERMISSIVE
        val disallow = ArrayList<String>()
        var applies = false
        for (raw in body.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "user-agent" -> applies = value == "*" || value.contains("prism", ignoreCase = true)
                "disallow" -> if (applies && value.isNotEmpty()) disallow.add(value)
            }
        }
        return Robots(disallow)
    }

    /** Like [fetch] but without the HTML content-type requirement (robots.txt is text/plain). */
    private fun fetchRaw(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // -- HTML handling --------------------------------------------------------------------

    fun extractTitle(html: String): String {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return ""
        return decodeEntities(m.groupValues[1]).trim().replace(Regex("\\s+"), " ").take(200)
    }

    /**
     * The page's own summary: `<meta name="description">`, falling back to OpenGraph's
     * `og:description`.
     *
     * Both attribute orders are matched (`name` before `content` and after), because plenty of
     * real pages write them either way and a regex that assumes one silently returns nothing for
     * the other -- which looks identical to a page having no description at all.
     */
    fun extractDescription(html: String): String {
        val patterns = listOf(
            Regex("(?is)<meta[^>]+name\\s*=\\s*[\"']description[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']"),
            Regex("(?is)<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*name\\s*=\\s*[\"']description[\"']"),
            Regex("(?is)<meta[^>]+property\\s*=\\s*[\"']og:description[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']"),
            Regex("(?is)<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*property\\s*=\\s*[\"']og:description[\"']")
        )
        for (re in patterns) {
            val v = re.find(html)?.groupValues?.getOrNull(1) ?: continue
            val cleaned = decodeEntities(v).replace(Regex("\\s+"), " ").trim()
            if (cleaned.isNotEmpty()) return cleaned.take(400)
        }
        return ""
    }

    /** Absolute http(s) hrefs found in [html], resolved against [baseUrl]. */
    fun extractLinks(html: String, baseUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']").findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("javascript:") || href.startsWith("mailto:")) continue
            val abs = absolutize(href, baseUrl) ?: continue
            if (abs.startsWith("http://") || abs.startsWith("https://")) out.add(abs)
        }
        return out.toList()
    }

    /** Same regex-based strip AgenticBuiltinTools uses -- no HTML parser dependency for one feature. */
    fun htmlToText(html: String): String {
        val stripped = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
        return decodeEntities(stripped)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(20_000)
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    fun absolutize(href: String, baseUrl: String): String? = try {
        URL(URL(baseUrl), href).toString()
    } catch (e: Exception) {
        null
    }

    private fun hostOf(url: String): String? = try {
        URL(url).host?.lowercase()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** `scheme://host` -- the front door of whatever site a link pointed into. */
    fun originOf(url: String): String? = try {
        val u = URL(url)
        if (u.host.isNullOrBlank()) null else "${u.protocol}://${u.authority}"
    } catch (e: Exception) {
        null
    }

    private fun pathOf(url: String): String = try {
        URL(url).path.ifEmpty { "/" }
    } catch (e: Exception) {
        "/"
    }
}
