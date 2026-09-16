package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * Makes a typed URL behave like a search that already worked.
 *
 * ## The problem this solves
 *
 * Prism Search only knows what it has crawled, so typing a perfectly good address for a site it has
 * never visited returns nothing at all. That reads as the engine being broken, when in fact the
 * user has just told it about a page it should have — an address typed into a search box is the
 * strongest possible signal that a site is worth indexing.
 *
 * So a URL that misses is answered immediately from the address itself, added to the seed list, and
 * crawled in the background. The next search for it comes from the real index.
 */
object PrismUrlQuery {

    /** How the query was answered, so a caller can say "crawling this now" rather than guess. */
    data class Outcome(
        val results: List<PrismSearchIndex.Result>,
        /** The URL recognised in the query, if there was one. */
        val url: String? = null,
        /** True when the URL was new: seeded and queued for crawling by this call. */
        val seeded: Boolean = false,
    )

    /**
     * A host label with no scheme, e.g. `example.com` or `sub.example.co.uk`.
     *
     * Requires a dot and a plausible TLD so that ordinary words are not mistaken for addresses:
     * "kotlin coroutines" is a search, "kotlinlang.org" is a site, and the difference has to be the
     * shape of the string because nothing else distinguishes them.
     */
    private val BARE_HOST = Regex(
        "^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,24}(?:/\\S*)?$"
    )

    /**
     * Extracts a URL from [query], or null if it is an ordinary search.
     *
     * Accepts `https://…`, `http://…`, `www.…` and a bare host. A missing scheme becomes https:
     * plain http would be a downgrade chosen for the user, and any site worth seeding in 2026
     * answers on TLS.
     */
    /**
     * Endings that are file extensions and NOT top-level domains.
     *
     * Without this, "report.pdf" parses as a perfectly good host and gets seeded, and the crawler
     * spends a request discovering that `https://report.pdf` does not resolve. The list is only the
     * unambiguous ones: `.sh`, `.io`, `.md`, `.zip` and `.mov` are all real TLDs as well as
     * familiar extensions, so those stay treated as addresses — a heuristic cannot resolve that
     * without knowing what the user meant, and the cost of guessing "site" is one failed fetch
     * while the cost of guessing "file" is a search that silently refuses to work.
     */
    private val NOT_A_TLD = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml",
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic",
        "mp3", "mp4", "wav", "flac", "avi", "mkv", "webm",
        "exe", "dll", "apk", "iso", "dmg", "tar", "gz", "rar",
    )

    fun urlIn(query: String): String? {
        val text = query.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        // Checked only for scheme-less input: someone who typed https://…/report.pdf means it.
        if (!text.startsWith("http", true)) {
            val ending = text.substringBefore('/').substringAfterLast('.').lowercase()
            if (ending in NOT_A_TLD) return null
        }

        return when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            text.startsWith("www.", true) -> "https://$text"
            BARE_HOST.matches(text) -> "https://$text"
            else -> null
        }
    }

    /**
     * Searches, and falls back to the address itself when the query is a URL nothing has indexed.
     *
     * [crawl] is injected rather than called directly so this stays testable and so the caller
     * decides what "in the background" means — a service has a scheduler, a test does not.
     */
    fun search(
        query: String,
        limit: Int = 25,
        crawl: (String) -> Unit = ::crawlInBackground,
    ): Outcome {
        val results = PrismSearchIndex.search(query, limit)
        val url = urlIn(query) ?: return Outcome(results)

        val normalized = PrismSearchIndex.normalize(url)
        val host = hostOf(normalized)

        // ALREADY KNOWN IS NOT ONLY AN EXACT MATCH. A crawl of example.com indexes its pages, not
        // necessarily its bare root, so comparing whole URLs would re-seed a site that is already
        // covered every time someone typed its address. The host is what "have we crawled this
        // site" actually means.
        val known = results.any { hostOf(it.url) == host } ||
            PrismSearchIndex.search(host.orEmpty(), 1).any { hostOf(it.url) == host }
        if (known) return Outcome(results, url = url)

        val seeded = runCatching { PrismSettings.addUserSearchSeed(normalized) }.getOrDefault(false)
        if (seeded) runCatching { crawl(normalized) }

        // Placed FIRST and marked as a direct hit. The user typed this address; anything the text
        // index dredged up for the same string is less relevant than the site itself.
        val direct = PrismSearchIndex.Result(
            url = normalized,
            title = host ?: normalized,
            snippet = if (seeded) {
                "Not indexed yet — added to your seeds and being crawled now."
            } else {
                "Not indexed yet."
            },
            // Above anything the index returned, without pretending to a real relevance score.
            score = Double.MAX_VALUE,
            pageRank = 0.0,
        )
        return Outcome(listOf(direct) + results, url = normalized, seeded = seeded)
    }

    /** Crawls one site, shallowly, off the caller's thread. */
    private fun crawlInBackground(url: String) {
        Thread({
            runCatching {
                // Small and shallow on purpose: this is a courtesy crawl triggered by one typed
                // address, not the scheduled full crawl. Grabbing hundreds of pages because someone
                // typed a domain would spend their battery and someone else's bandwidth.
                val crawled = PrismCrawler.crawl(
                    PrismCrawler.Config(seeds = listOf(url), maxPages = 25)
                )
                PrismSearchIndex.addAll(crawled.pages)
                PrismPlatform.log.info(
                    "PrismSearch", "Seeded $url and crawled ${crawled.pages.size} page(s)"
                )
            }.onFailure {
                PrismPlatform.log.warn("PrismSearch", "Background crawl of $url failed: ${it.message}")
            }
        }, "prism-seed-crawl").apply { isDaemon = true; start() }
    }

    private fun hostOf(url: String?): String? = runCatching {
        java.net.URI(url ?: return null).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()
}
