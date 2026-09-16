package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * The Prism search index: crawled pages, the link graph between them, and the PageRank computed
 * over that graph.
 *
 * STORED IN ITS OWN FILES, NOT IN AppDatabase. Adding tables to the shared Room database means
 * bumping its version, and that database is configured with `fallbackToDestructiveMigration` --
 * a bump silently deletes the user's app statistics, agentic tools and entire Nebula feed (see
 * AppDatabase's own doc comment). An index is also rebuildable from scratch by definition, so it
 * has no business sharing a lifetime with data that is not.
 *
 * RANKING IS RELEVANCE x PAGERANK, which is what makes it unbiased in the sense that matters
 * here: nothing about who published a page, paid for placement, or was hand-listed as a seed
 * affects where it lands. A page's authority is computed purely from which other pages chose to
 * link to it, and its position is that authority multiplied by how well it actually matches what
 * was typed. Both halves are needed -- PageRank alone would return the same handful of famous
 * pages for every query, and relevance alone would rank a spam page stuffed with the query terms
 * above the canonical source of the answer.
 */
object PrismSearchIndex {

    /** One crawled document. [outLinks] are absolute URLs, and are what the link graph is built from. */
    data class Page(
        val url: String,
        val title: String,
        val text: String,
        val outLinks: List<String>,
        val crawledAt: Long = System.currentTimeMillis(),
        /** The page's own `<meta name="description">` (or OpenGraph equivalent), if it has one.
         * This is what the author wrote to summarise the page, so it beats any excerpt we could
         * cut out of the body. Blank when the page declares none. */
        val description: String = ""
    )

    data class Result(
        val url: String,
        val title: String,
        val snippet: String,
        val score: Double,
        val pageRank: Double
    )

    /** Damping factor: the standard 0.85 from the original PageRank paper -- the probability that
     * the random surfer follows a link rather than jumping to a random page. */
    private const val DAMPING = 0.85
    private const val MAX_ITERATIONS = 60
    private const val CONVERGENCE = 1e-6

    private const val MAX_SNIPPET = 240

    private val lock = Any()
    private val pages = LinkedHashMap<String, Page>()
    /** term -> (url -> term frequency in that page) */
    private val inverted = HashMap<String, HashMap<String, Int>>()
    private val pageRank = HashMap<String, Double>()

    @Volatile
    var lastCrawlAt: Long = 0L
        private set

    fun documentCount(): Int = synchronized(lock) { pages.size }

    fun indexDir(): File =
        File(PrismPlatform.host.dataDir(), "prism_search").apply { mkdirs() }

    private fun indexFile() = File(indexDir(), "index.json")

    // -- Building -------------------------------------------------------------------------

    /** Replaces the whole index with a fresh crawl's results, then recomputes PageRank. */
    fun replaceAll(crawled: Collection<Page>, crawledAt: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            pages.clear()
            inverted.clear()
            for (p in crawled) pages[normalize(p.url)] = p
            rebuildInverted()
            computePageRank()
            lastCrawlAt = crawledAt
        }
    }

    /**
     * Merges pages into the index, keeping everything already there.
     *
     * DISTINCT FROM [replaceAll], AND THE DIFFERENCE MATTERS. replaceAll clears first, which is
     * right for a scheduled crawl that re-walks the whole seed list and produces a complete
     * picture. It is catastrophic for a small courtesy crawl of one newly typed address: twenty
     * pages would replace the entire index and silently destroy hours of crawling.
     *
     * Same-URL pages are overwritten, since the newer fetch is the better copy. The inverted index
     * and PageRank are rebuilt over the union, because both are global properties -- rank in
     * particular cannot be computed for new pages alone without giving them a rank that means
     * nothing relative to the rest.
     */
    fun addAll(crawled: Collection<Page>, crawledAt: Long = System.currentTimeMillis()) {
        if (crawled.isEmpty()) return
        synchronized(lock) {
            for (p in crawled) pages[normalize(p.url)] = p
            rebuildInverted()
            computePageRank()
            lastCrawlAt = crawledAt
        }
    }

    private fun rebuildInverted() {
        inverted.clear()
        for ((url, page) in pages) {
            // Title terms are counted twice: a page whose TITLE is "kotlin coroutines" is a better
            // answer for that query than one that merely mentions the phrase in paragraph nine.
            val terms = tokenize(page.title) + tokenize(page.title) + tokenize(page.text)
            for (term in terms) {
                inverted.getOrPut(term) { HashMap() }.merge(url, 1, Int::plus)
            }
        }
    }

    /**
     * PageRank by power iteration.
     *
     * PR(p) = (1-d)/N + d * sum over inbound q of PR(q)/outdegree(q)
     *
     * Two details that are easy to get wrong and change the answer materially. DANGLING PAGES --
     * ones with no outbound links inside the index -- would otherwise leak rank out of the system
     * every iteration, so their mass is redistributed evenly across all pages, which is equivalent
     * to treating them as linking to everything. And only links whose target is actually IN the
     * index count toward outdegree; counting outbound links to uncrawled pages would silently
     * drain rank to documents that cannot receive it.
     */
    private fun computePageRank() {
        val urls = pages.keys.toList()
        val n = urls.size
        pageRank.clear()
        if (n == 0) return

        val index = HashMap<String, Int>(n)
        urls.forEachIndexed { i, u -> index[u] = i }

        // Adjacency restricted to in-index targets, self-links dropped.
        val outbound = Array(n) { i ->
            val page = pages[urls[i]] ?: return@Array IntArray(0)
            page.outLinks.asSequence()
                .map { normalize(it) }
                .mapNotNull { index[it] }
                .filter { it != i }
                .distinct()
                .toList()
                .toIntArray()
        }

        var rank = DoubleArray(n) { 1.0 / n }
        val base = (1.0 - DAMPING) / n

        repeat(MAX_ITERATIONS) {
            val next = DoubleArray(n) { base }
            var dangling = 0.0
            for (i in 0 until n) {
                val links = outbound[i]
                if (links.isEmpty()) {
                    dangling += rank[i]
                    continue
                }
                val share = DAMPING * rank[i] / links.size
                for (j in links) next[j] += share
            }
            if (dangling > 0.0) {
                val spread = DAMPING * dangling / n
                for (j in 0 until n) next[j] += spread
            }

            var delta = 0.0
            for (i in 0 until n) delta += abs(next[i] - rank[i])
            rank = next
            if (delta < CONVERGENCE) return@repeat
        }

        urls.forEachIndexed { i, u -> pageRank[u] = rank[i] }
    }

    // -- Querying -------------------------------------------------------------------------

    /**
     * Ranked results for [query].
     *
     * Relevance is TF-IDF: a term that appears in nearly every page says almost nothing about
     * which page you want, so it is weighted down by its inverse document frequency, while a rare
     * term that matches strongly is what actually distinguishes a result. That relevance is then
     * scaled by the page's PageRank, so among pages that match equally well, the one the rest of
     * the web actually points at wins.
     *
     * Every query term must appear in a page (AND, not OR) -- an OR search over a small crawl
     * returns almost the whole index for any multi-word query, which reads as random.
     */
    fun search(query: String, limit: Int = 20): List<Result> = synchronized(lock) {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || pages.isEmpty()) return emptyList()

        val n = pages.size.toDouble()
        var candidates: MutableSet<String>? = null
        for (term in terms) {
            val postings = inverted[term]?.keys ?: return emptyList()
            if (candidates == null) candidates = postings.toMutableSet()
            else candidates.retainAll(postings)
            if (candidates.isEmpty()) return emptyList()
        }

        val scored = ArrayList<Result>()
        for (url in candidates ?: return emptyList()) {
            val page = pages[url] ?: continue
            var relevance = 0.0
            for (term in terms) {
                val postings = inverted[term] ?: continue
                val tf = postings[url]?.toDouble() ?: continue
                val idf = ln(1.0 + n / postings.size.toDouble())
                // Sub-linear term frequency: the tenth occurrence of a word says far less than
                // the second, and without this a page can rank by sheer repetition.
                relevance += (1.0 + ln(tf)) * idf
            }
            val pr = pageRank[url] ?: (1.0 / n)
            // PageRank values are tiny (they sum to 1 across the index), so they are used as a
            // multiplier against a floor rather than added: this keeps a well-matching page from
            // being buried by an authoritative but barely-relevant one, while still letting
            // authority decide between comparable matches.
            val score = relevance * (1.0 + pr * n)
            scored.add(Result(url, page.title.ifBlank { url }, snippet(page, terms), score, pr))
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }

    /**
     * What appears under a result.
     *
     * The site's own description wins when it has one: it was written to summarise the page,
     * whereas an excerpt is whatever text happened to sit near the first query match -- often a
     * navigation menu or a cookie notice. Falls back to a match-centred excerpt for the many pages
     * that declare no description at all.
     */
    private fun snippet(page: Page, terms: List<String>): String {
        val described = page.description.trim()
        if (described.isNotBlank()) {
            return if (described.length > MAX_SNIPPET) described.take(MAX_SNIPPET).trimEnd() + "\u2026"
                   else described
        }
        val text = page.text
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        val hit = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = maxOf(0, hit - 60)
        val end = min(text.length, start + MAX_SNIPPET)
        val body = text.substring(start, end).trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }

    // -- Persistence ----------------------------------------------------------------------

    fun save() {
        synchronized(lock) {
            val arr = JSONArray()
            for (page in pages.values) {
                val links = JSONArray()
                page.outLinks.forEach { links.put(it) }
                arr.put(
                    JSONObject()
                        .put("url", page.url)
                        .put("title", page.title)
                    .put("desc", page.description)
                        // Capped: the index is for ranking and snippets, not for archiving the web.
                        .put("text", page.text.take(4000))
                        .put("links", links)
                        .put("at", page.crawledAt)
                )
            }
            val root = JSONObject().put("lastCrawlAt", lastCrawlAt).put("pages", arr)
            val tmp = File(indexDir(), "index.json.part")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(indexFile())) { indexFile().writeText(root.toString()); tmp.delete() }
        }
    }

    fun load(): Boolean {
        val f = indexFile()
        if (!f.exists()) return false
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("pages") ?: JSONArray()
            val loaded = ArrayList<Page>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val linksArr = o.optJSONArray("links") ?: JSONArray()
                val links = ArrayList<String>(linksArr.length())
                for (j in 0 until linksArr.length()) links.add(linksArr.getString(j))
                loaded.add(
                    Page(
                        url = o.optString("url", ""),
                        title = o.optString("title", ""),
                        text = o.optString("text", ""),
                        outLinks = links,
                        crawledAt = o.optLong("at", 0L),
                        // Absent in indexes written before descriptions existed; those simply fall
                        // back to excerpts until the next crawl refreshes them.
                        description = o.optString("desc", "")
                    )
                )
            }
            replaceAll(loaded.filter { it.url.isNotBlank() }, root.optLong("lastCrawlAt", 0L))
            true
        } catch (e: Exception) {
            false
        }
    }

    // -- Text handling --------------------------------------------------------------------

    /** Lowercased alphanumeric runs, 2+ characters. Deliberately simple and language-agnostic --
     * no stemming, because a wrong stem silently makes a query un-matchable and there is no way
     * for a user to work around it. */
    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            else { if (sb.length >= 2) out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.length >= 2) out.add(sb.toString())
        return out
    }

    /**
     * Canonical form of a URL for identity purposes: scheme and host lowercased, default ports
     * and fragments dropped, trailing slash removed. Without this the same page reached as
     * `http://Example.com/a/` and `http://example.com/a#top` counts as two documents, splits its
     * inbound links between them, and halves the PageRank each copy deserves.
     */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return u
        val hash = u.indexOf('#')
        if (hash >= 0) u = u.substring(0, hash)
        val schemeEnd = u.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = u.substring(0, schemeEnd).lowercase()
            var rest = u.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            var host = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else ""
            host = host.lowercase()
            if (scheme == "http" && host.endsWith(":80")) host = host.dropLast(3)
            if (scheme == "https" && host.endsWith(":443")) host = host.dropLast(4)
            rest = host + path
            u = "$scheme://$rest"
        }
        if (u.endsWith("/") && u.count { it == '/' } > 3) u = u.dropLast(1)
        return u
    }

    /** Test/diagnostic access -- the rank assigned to a URL, or 0 if it is not indexed. */
    fun rankOf(url: String): Double = synchronized(lock) { pageRank[normalize(url)] ?: 0.0 }
}
