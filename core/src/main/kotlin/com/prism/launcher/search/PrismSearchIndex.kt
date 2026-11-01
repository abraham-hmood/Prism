package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Prism search index and its ranking model.
 *
 * ## Why this is not TF-IDF times PageRank any more
 *
 * The first version scored a page by summing `(1 + ln tf) * idf` and multiplying by authority.
 * That is a reasonable first pass and it is what the index shipped with, but it has four known
 * failure modes that show up immediately on a small crawl:
 *
 *  1. **Length is ignored.** A long page that mentions a term once beats a short page that is
 *     entirely about it, because nothing penalises length.
 *  2. **Term frequency never saturates.** Repeating a word twenty times keeps raising the score
 *     linearly, which is exactly what keyword-stuffed pages do.
 *  3. **No phrase or proximity.** Searching `port forwarding router` treats the three words as an
 *     unordered bag, so a page mentioning "router" and "port" in different sections ranks alongside
 *     one that is about the phrase.
 *  4. **No query understanding.** A typo (`recieve`) matches nothing at all, and `fix` does not
 *     find a page that says `repair`.
 *
 * Google's published description of its ranking is a multi-stage system: cheap lexical retrieval,
 * then a heavier ranker over the candidates, with meaning, relevance, quality, usability and
 * context as the signal families. The implementable version of that for a few hundred pages is:
 *
 *  - **BM25F** over four fields -- title, description, anchor text pointing at the page, body --
 *    with k1 = 1.2 and b = 0.75 (the Elasticsearch/Lucene defaults, and the values the IR
 *    literature recommends as a starting point). BM25 gives term-frequency saturation and length
 *    normalisation, which fixes 1 and 2.
 *  - **Phrase and proximity bonuses** computed over token positions in the title and body, which
 *    fixes 3.
 *  - **Spelling correction** with a deletion index and Damerau-Levenshtein verification, plus
 *    **query expansion** over a small synonym map and suffix variants, which fixes 4.
 *  - **Semantic candidate ranking** using a token-set/trigram similarity over title, description
 *    and the first part of the body, fused with the lexical and authority rankings by Reciprocal
 *    Rank Fusion (k = 60). RRF uses ranks rather than raw scores, so BM25's unknown scale and a
 *    cosine similarity never have to be calibrated against each other.
 *  - **Authority** from the link graph's PageRank, and **freshness** as a small, query-dependent
 *    multiplier: a page crawled this week should edge out an identical one from two years ago, but
 *    freshness must never outrank relevance.
 *  - **Host diversity**, so one site cannot fill the result list -- the same cap Google applies at
 *    roughly two results per site.
 *
 * ## What this is deliberately not
 *
 * It is not a trained ranker. There are no click logs to learn from, and a model trained on a
 * four-hundred-page personal crawl would be overfitted noise. Every signal here is explainable and
 * testable, which matters more on a device with no network and no labelled data.
 */
object PrismSearchIndex {

    /**
     * One crawled document. [outLinks] are absolute URLs, and are what the link graph is built from.
     *
     * [anchors] maps an outbound URL to the text the link was wrapped in. Anchor text is one of the
     * strongest relevance signals a crawl can collect: a page linked as "port forwarding guide" is
     * about port forwarding even if it never uses that exact heading, because that is what other
     * people called it.
     */
    data class Page(
        val url: String,
        val title: String,
        val text: String,
        val outLinks: List<String>,
        val crawledAt: Long = System.currentTimeMillis(),
        /** The page's own `<meta name="description">` (or OpenGraph equivalent), if it has one. */
        val description: String = "",
        val anchors: Map<String, String> = emptyMap(),
    )

    data class Result(
        val url: String,
        val title: String,
        val snippet: String,
        val score: Double,
        val pageRank: Double,
    )

    /**
     * A whole response, including the things a results page needs to explain itself.
     *
     * [corrected] is the query actually searched when the spelling was fixed; [expanded] lists the
     * terms that matched through a synonym or suffix rather than literally, which the page shows
     * the way a "including results for" note does.
     */
    data class Outcome(
        val query: String,
        val corrected: String?,
        val results: List<Result>,
        val expanded: List<String>,
        val tookMs: Long,
    )

    /** The three fields' relative importance. Title is what a page says it is about. */
    private const val TITLE_WEIGHT = 6.0
    private const val DESC_WEIGHT = 3.0
    private const val ANCHOR_WEIGHT = 4.0
    private const val BODY_WEIGHT = 1.0

    /** BM25 defaults: Lucene/Elasticsearch's k1 and b, and the IR literature's starting point. */
    private const val K1 = 1.2
    private const val B = 0.75

    /** RRF's standard damping constant. */
    private const val RRF_K = 60.0

    /** How many results one host may contribute before the rest are pushed down. */
    private const val MAX_PER_HOST = 2

    private const val DAMPING = 0.85
    private const val MAX_ITERATIONS = 60
    private const val CONVERGENCE = 1e-6
    private const val MAX_SNIPPET = 260

    private val lock = Any()
    private val pages = LinkedHashMap<String, Page>()
    private val docs = HashMap<String, Doc>()
    /** term -> (url -> weighted term frequency across all fields) */
    private val inverted = HashMap<String, HashMap<String, Double>>()
    private val documentFrequency = HashMap<String, Int>()
    private val pageRank = HashMap<String, Double>()
    private var averageLength = 1.0

    /**
     * Spelling-candidate index: every one-character deletion of a vocabulary term maps to the terms
     * it could have been. SymSpell's idea, and the reason a typo lookup is a hash probe instead of
     * a scan over the whole vocabulary.
     */
    private val deletionIndex = HashMap<String, MutableList<String>>()

    private val trigramCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    @Volatile
    var lastCrawlAt: Long = 0L
        private set

    private data class Doc(
        val page: Page,
        val url: String,
        val host: String,
        val titleTerms: List<String>,
        val descTerms: List<String>,
        val bodyTerms: List<String>,
        val anchorTerms: List<String>,
        val weightedLength: Double,
        val termFreq: Map<String, Double>,
        val uniqueTerms: Set<String>,
        val trigrams: Set<String>,
    )

    /** One query concept: the typed term and every expansion that should count for it. */
    private data class Concept(val term: String, val variants: Map<String, Double>)

    private data class Correction(val tokens: List<String>, val changed: Boolean)

    fun documentCount(): Int = synchronized(lock) { pages.size }

    fun indexDir(): File =
        File(PrismPlatform.host.dataDir(), "prism_search").apply { mkdirs() }

    private fun indexFile() = File(indexDir(), "index.json")

    // ── Building ───────────────────────────────────────────────────────────

    /** Replaces the whole index with a fresh crawl's results, then recomputes the link graph. */
    fun replaceAll(crawled: Collection<Page>, crawledAt: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            pages.clear()
            for (p in crawled) pages[normalize(p.url)] = p
            rebuild()
            lastCrawlAt = crawledAt
        }
    }

    /**
     * Merges pages into the index, keeping everything already there.
     *
     * Distinct from [replaceAll], and the difference matters: replaceAll is for a scheduled crawl
     * that re-walks the seed list and produces a complete picture, while a courtesy crawl of one
     * newly typed address must not replace hours of work.
     */
    fun addAll(crawled: Collection<Page>, crawledAt: Long = System.currentTimeMillis()) {
        if (crawled.isEmpty()) return
        synchronized(lock) {
            for (p in crawled) pages[normalize(p.url)] = p
            rebuild()
            lastCrawlAt = crawledAt
        }
    }

    /**
     * Rebuilds every derived structure: field statistics, the inverted index, the deletion index,
     * trigram sets and PageRank.
     *
     * INBOUND ANCHOR TEXT IS COLLECTED FIRST. A page's anchors come from OTHER pages' links, so
     * they cannot be computed while walking documents one at a time -- the linking page has not
     * been visited yet when the target is. The two-pass shape is what makes anchors possible.
     */
    private fun rebuild() {
        docs.clear()
        inverted.clear()
        documentFrequency.clear()
        deletionIndex.clear()

        val inbound = HashMap<String, StringBuilder>()
        for (page in pages.values) {
            for ((target, text) in page.anchors) {
                val key = normalize(target)
                if (key.isBlank() || text.isBlank()) continue
                inbound.getOrPut(key) { StringBuilder() }.append(' ').append(text)
            }
        }

        for ((url, page) in pages) {
            val titleTerms = tokenize(page.title)
            val descTerms = tokenize(page.description)
            val anchorTerms = tokenize(inbound[url]?.toString().orEmpty())
            val bodyTerms = tokenize(page.text)
            val weightedLength =
                TITLE_WEIGHT * titleTerms.size + DESC_WEIGHT * descTerms.size +
                    ANCHOR_WEIGHT * anchorTerms.size + BODY_WEIGHT * bodyTerms.size
            val tf = HashMap<String, Double>(titleTerms.size + bodyTerms.size)
            fun add(terms: List<String>, weight: Double) {
                for (term in terms) tf[term] = (tf[term] ?: 0.0) + weight
            }
            add(titleTerms, TITLE_WEIGHT)
            add(descTerms, DESC_WEIGHT)
            add(anchorTerms, ANCHOR_WEIGHT)
            add(bodyTerms, BODY_WEIGHT)
            val doc = Doc(
                page = page,
                url = url,
                host = hostOf(url),
                titleTerms = titleTerms,
                descTerms = descTerms,
                bodyTerms = bodyTerms,
                anchorTerms = anchorTerms,
                weightedLength = max(1.0, weightedLength),
                termFreq = tf,
                uniqueTerms = tf.keys,
                // Title and description are what the page claims to be about; the head of the body
                // is included because most pages state their subject before their first heading.
                trigrams = trigramsOf(titleTerms + descTerms + bodyTerms.take(300)),
            )
            docs[url] = doc
            for ((term, weight) in tf) {
                inverted.getOrPut(term) { HashMap() }[url] = weight
                documentFrequency.merge(term, 1, Int::plus)
            }
        }

        averageLength = if (docs.isEmpty()) 1.0 else
            docs.values.sumOf { it.weightedLength } / docs.size

        for ((term, df) in documentFrequency) {
            if (df < 1 || term.length !in 3..24) continue
            for (delete in deletes(term)) {
                deletionIndex.getOrPut(delete) { ArrayList(2) }.add(term)
            }
        }

        computePageRank()
    }

    /**
     * PageRank by power iteration, with dangling pages redistributed evenly.
     *
     * PR(p) = (1-d)/N + d * sum over inbound q of PR(q)/outdegree(q)
     *
     * Only links whose target is in the index count toward outdegree; counting links to uncrawled
     * pages would drain rank to documents that cannot receive it.
     */
    private fun computePageRank() {
        val urls = pages.keys.toList()
        val n = urls.size
        pageRank.clear()
        if (n == 0) return

        val index = HashMap<String, Int>(n)
        urls.forEachIndexed { i, u -> index[u] = i }
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

    // ── Querying ───────────────────────────────────────────────────────────

    /** Ranked results for [query]. Kept for callers that do not need the diagnostics. */
    fun search(query: String, limit: Int = 20): List<Result> = searchOutcome(query, limit).results

    /**
     * Ranked results plus what the engine had to do to get them.
     *
     * The pipeline mirrors the way a real engine is staged: cheap candidate generation by the
     * inverted index, then BM25F scoring, then rank fusion with semantics and authority, then a
     * diversity pass. Every stage is linear in the number of candidates, so a few hundred pages
     * answers in single-digit milliseconds.
     */
    fun searchOutcome(query: String, limit: Int = 20): Outcome = synchronized(lock) {
        val started = System.nanoTime()
        val rawTokens = tokenize(query).distinct()
        if (rawTokens.isEmpty() || docs.isEmpty() || limit <= 0) {
            return Outcome(query, null, emptyList(), emptyList(), elapsedMs(started))
        }

        val correction = correct(rawTokens)
        val tokens = correction.tokens
        val concepts = buildConcepts(tokens)
        if (concepts.isEmpty()) {
            return Outcome(query, null, emptyList(), emptyList(), elapsedMs(started))
        }

        // Per concept, per document: the best weighted term frequency over its variants. This is
        // what turns "running" in the query into a hit on a page that says "run".
        val conceptTf = Array(concepts.size) { HashMap<String, Double>() }
        val matches = HashMap<String, Int>()
        for ((index, concept) in concepts.withIndex()) {
            for ((variant, weight) in concept.variants) {
                val postings = inverted[variant] ?: continue
                for ((url, tf) in postings) {
                    conceptTf[index][url] = (conceptTf[index][url] ?: 0.0) + tf * weight
                }
            }
            for (url in conceptTf[index].keys) matches.merge(url, 1, Int::plus)
        }

        // AND over concepts is right for a small index, but a three-word natural-language query
        // should not return nothing because one word is absent. Two words must all match; longer
        // queries need 75% of their concepts.
        val required = if (concepts.size <= 2) concepts.size else ceil(concepts.size * 0.75).toInt()
        val candidates = matches.filterValues { it >= required }.keys
        if (candidates.isEmpty()) {
            return Outcome(query, if (correction.changed) tokens.joinToString(" ") else null,
                emptyList(), emptyList(), elapsedMs(started))
        }

        val n = docs.size.toDouble()
        val scored = HashMap<String, Double>(candidates.size)
        val semantic = HashMap<String, Double>(candidates.size)
        val phraseHit = HashSet<String>()

        for (url in candidates) {
            val doc = docs[url] ?: continue
            var lexical = 0.0
            for (index in concepts.indices) {
                val tf = conceptTf[index][url] ?: continue
                if (tf <= 0.0) continue
                val idf = idfOf(concepts[index])
                val norm = 1.0 - B + B * (doc.weightedLength / averageLength)
                lexical += idf * (tf * (K1 + 1.0)) / (tf + K1 * norm)
            }
            val phrase = phraseBonus(doc, tokens)
            if (phrase > 1.4) phraseHit.add(url)
            lexical *= phrase
            scored[url] = lexical
            semantic[url] = semanticScore(tokens, doc)
        }

        val lexicalRank = rankMap(scored)
        val semanticRank = rankMap(semantic)
        val authorityRank = rankMap(candidates.associateWith { pageRank[it] ?: 0.0 })
        val freshIntent = freshnessIntent(tokens)

        val fused = ArrayList<Pair<String, Double>>(scored.size)
        for ((url, lexical) in scored) {
            if (lexical <= 0.0) continue
            val doc = docs[url] ?: continue
            val pr = pageRank[url] ?: 0.0
            val prMax = pageRank.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
            var score =
                (0.70 / (RRF_K + (lexicalRank[url] ?: Int.MAX_VALUE))) +
                    (0.20 / (RRF_K + (semanticRank[url] ?: Int.MAX_VALUE))) +
                    (0.10 / (RRF_K + (authorityRank[url] ?: Int.MAX_VALUE)))
            // Multiplicative, never additive: BM25's absolute scale varies wildly by query, so an
            // added constant would mean 18x on one query and 1.2x on the next. This is the same
            // reason Elasticsearch's guidance is to boost by multiplication.
            score *= (1.0 + 0.35 * (pr / prMax))
            score *= freshnessFactor(doc.page.crawledAt, freshIntent)
            if (url in phraseHit) score *= 1.10
            fused.add(url to score)
        }
        fused.sortWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

        // Host diversity. A crawl of a site's own pages would otherwise fill the page with one
        // domain, which is the failure Google's site-diversity cap exists to prevent.
        val chosen = ArrayList<String>(min(limit, fused.size))
        val overflow = ArrayList<String>()
        val perHost = HashMap<String, Int>()
        for ((url, _) in fused) {
            val host = hostOf(url)
            val count = perHost[host] ?: 0
            if (count < MAX_PER_HOST) {
                chosen.add(url)
                perHost[host] = count + 1
                if (chosen.size >= limit) break
            } else {
                overflow.add(url)
            }
        }
        if (chosen.size < limit) {
            for (url in overflow) {
                chosen.add(url)
                if (chosen.size >= limit) break
            }
        }

        val best = fused.firstOrNull()?.second ?: 1.0
        val results = chosen.map { url ->
            val doc = docs[url]!!
            Result(
                url = doc.page.url,
                title = doc.page.title.ifBlank { url },
                snippet = snippet(doc, concepts),
                score = (fused.first { it.first == url }.second / best.coerceAtLeast(1e-9)),
                pageRank = pageRank[url] ?: 0.0,
            )
        }

        val expansions = concepts
            .filter { concept -> concept.variants.keys.any { it != concept.term && (documentFrequency[it] ?: 0) > 0 } }
            .map { it.term }
            .filter { term -> candidates.any { url -> docs[url]?.uniqueTerms?.contains(term) != true } }

        Outcome(
            query = query,
            corrected = if (correction.changed) tokens.joinToString(" ") else null,
            results = results,
            expanded = expansions,
            tookMs = elapsedMs(started),
        )
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun idfOf(concept: Concept): Double {
        val df = concept.variants.keys
            .mapNotNull { documentFrequency[it] }
            .minOrNull() ?: 0
        val n = docs.size.toDouble()
        return ln(1.0 + (n - df + 0.5) / (df + 0.5)).coerceAtLeast(0.0)
    }

    private fun rankMap(scores: Map<String, Double>): Map<String, Int> =
        scores.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to index }
            .toMap()

    /**
     * How much the query cares about newness.
     *
     * Google calls this "query deserves freshness": a query about a released film should prefer
     * new reviews, while a query for a dictionary definition should not. Here the intent is
     * detected from a handful of words rather than learned, because there is no click stream to
     * learn from -- and a wrong guess only changes a multiplier between 1.0 and 1.2.
     */
    private fun freshnessIntent(tokens: List<String>): Double {
        val cues = setOf(
            "news", "latest", "today", "now", "current", "recent", "update", "updates",
            "release", "released", "2026", "2025", "this week", "breaking",
        )
        return if (tokens.any { it in cues }) 0.55 else 0.12
    }

    /**
     * Exponential decay with a one-year half-life.
     *
     * A crawl timestamp is the only date the index has. It says when we saw the page, not when the
     * page was written, so freshness is a weak signal used at a small weight rather than a claim
     * about recency.
     */
    private fun freshnessFactor(crawledAt: Long, intent: Double): Double {
        if (crawledAt <= 0L) return 1.0
        val ageDays = ((System.currentTimeMillis() - crawledAt) / 86_400_000.0).coerceAtLeast(0.0)
        val decay = exp(-ageDays / 365.0)
        return 1.0 + intent * (decay - 0.5)
    }

    // ── Phrase and proximity ───────────────────────────────────────────────

    /**
     * A multiplier for how well the page matches the query as a PHRASE rather than a bag.
     *
     * Title and description are checked first because a page titled "Port forwarding on a router"
     * is a far better answer than one that happens to use all three words in a list. Proximity in
     * the body is a smaller bonus: terms within a short window are usually about the same thing,
     * terms in different sections usually are not.
     */
    private fun phraseBonus(doc: Doc, tokens: List<String>): Double {
        if (tokens.size < 2) return 1.0
        var bonus = 1.0
        if (containsSequence(doc.titleTerms, tokens)) bonus *= 2.2
        if (containsSequence(doc.descTerms, tokens)) bonus *= 1.55
        if (containsSequence(doc.bodyTerms, tokens)) bonus *= 1.30
        val span = minimumSpan(doc.bodyTerms, tokens)
        if (span in 1..12) {
            bonus *= 1.0 + 0.15 * (1.0 - (span - 1) / 12.0)
        }
        return bonus
    }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** The smallest window containing every query term once, or Int.MAX_VALUE if impossible. */
    private fun minimumSpan(tokens: List<String>, wanted: List<String>): Int {
        if (wanted.isEmpty() || tokens.isEmpty()) return Int.MAX_VALUE
        val need = wanted.toSet()
        val count = HashMap<String, Int>()
        var have = 0
        var best = Int.MAX_VALUE
        var left = 0
        for (right in tokens.indices) {
            val term = tokens[right]
            if (term in need) {
                val c = count.merge(term, 1, Int::plus)!!
                if (c == 1) have++
            }
            while (have == need.size && left <= right) {
                best = min(best, right - left + 1)
                val leftTerm = tokens[left]
                if (leftTerm in need) {
                    val c = count[leftTerm]!! - 1
                    count[leftTerm] = c
                    if (c == 0) have--
                }
                left++
            }
        }
        return best
    }

    // ── Semantics ──────────────────────────────────────────────────────────

    /**
     * A cheap semantic score: character trigrams of the query against the page's own.
     *
     * This is not an embedding. It is a stand-in for the "neural matching" half of a modern
     * engine that costs no model download and no network: it finds pages close in shape to the
     * query (plural forms, hyphenation, word order) and lets RRF lift them slightly above a pure
     * keyword rank. A real embedding index can replace this function without touching the rest of
     * the pipeline, which is the point of keeping it isolated.
     */
    private fun semanticScore(tokens: List<String>, doc: Doc): Double {
        if (tokens.isEmpty()) return 0.0
        val queryTrigrams = trigramsOf(tokens)
        if (queryTrigrams.isEmpty()) return 0.0
        val docTrigrams = doc.trigrams
        if (docTrigrams.isEmpty()) return 0.0
        val small = if (queryTrigrams.size <= docTrigrams.size) queryTrigrams else docTrigrams
        val large = if (small === queryTrigrams) docTrigrams else queryTrigrams
        var overlap = 0
        for (gram in small) if (gram in large) overlap++
        val jaccard = overlap.toDouble() / (queryTrigrams.size + docTrigrams.size - overlap)
        // Token overlap catches concepts whose characters differ (phone/mobile), trigrams catch
        // the same word written differently. Both are cheap, so both are used.
        val queryTerms = tokens.toSet()
        var termOverlap = 0
        for (term in queryTerms) if (term in doc.uniqueTerms) termOverlap++
        val cosine = termOverlap / sqrt(queryTerms.size.toDouble() * doc.uniqueTerms.size.coerceAtLeast(1).toDouble())
        return 0.65 * jaccard + 0.35 * cosine
    }

    private fun trigramsOf(tokens: List<String>): Set<String> {
        if (tokens.isEmpty()) return emptySet()
        val joined = " " + tokens.joinToString(" ") + " "
        val key = joined.take(600)
        return trigramCache.getOrPut(key) {
            val out = HashSet<String>()
            for (i in 0..key.length - 3) out.add(key.substring(i, i + 3))
            out
        }
    }

    // ── Snippets ───────────────────────────────────────────────────────────

    /**
     * A query-biased summary, not the page's own description.
     *
     * The old code preferred the meta description even when the query's terms were nowhere in it,
     * so half the results showed a generic tagline. Google's snippets are passages chosen because
     * they contain the query, and that is what this does: score sentence windows by the IDF of the
     * query terms they contain, prefer the window with the densest match, and fall back to the
     * declared description only when no window matches at all.
     */
    private fun snippet(doc: Doc, concepts: List<Concept>): String {
        val text = doc.page.text.trim()
        if (text.isEmpty()) return doc.page.description.take(MAX_SNIPPET)
        val weights = concepts.associate { concept ->
            concept.term to (documentFrequency[concept.term] ?: 1).let { df ->
                ln(1.0 + docs.size.toDouble() / df.coerceAtLeast(1).toDouble())
            }
        }
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length > 1 }
        if (sentences.isEmpty()) return text.take(MAX_SNIPPET)

        var bestIndex = -1
        var bestScore = 0.0
        for (i in sentences.indices) {
            val lower = sentences[i].lowercase()
            var score = 0.0
            for ((term, weight) in weights) if (lower.contains(term)) score += weight
            // Prefer a sentence that also shares a sentence with a second match: a one-line hit
            // surrounded by the answer is more useful than a lone mention.
            if (i > 0 && weights.keys.any { sentences[i - 1].lowercase().contains(it) }) score *= 1.15
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        if (bestIndex < 0 || bestScore <= 0.0) {
            val described = doc.page.description.trim()
            if (described.isNotEmpty()) return described.take(MAX_SNIPPET)
            return text.take(MAX_SNIPPET)
        }

        val body = sentences[bestIndex]
        val clipped = if (body.length > MAX_SNIPPET) body.take(MAX_SNIPPET).trimEnd() + "\u2026" else body
        val before = if (bestIndex > 0) "\u2026" else ""
        val after = if (bestIndex < sentences.lastIndex && body.length <= MAX_SNIPPET) "\u2026" else ""
        return before + clipped + after
    }

    // ── Query understanding ────────────────────────────────────────────────

    /**
     * The typed words plus everything that should count as the same concept.
     *
     * Suffix variants are weighted below the literal term, and synonyms below those, so an exact
     * match always beats an expansion. That ordering is the whole reason query expansion does not
     * turn a precise engine into a fuzzy one.
     */
    private fun buildConcepts(tokens: List<String>): List<Concept> {
        val out = ArrayList<Concept>(tokens.size)
        for (term in tokens) {
            val variants = LinkedHashMap<String, Double>()
            variants[term] = 1.0
            for (variant in suffixVariants(term)) variants.putIfAbsent(variant, 0.72)
            for (synonym in SYNONYMS[term].orEmpty()) {
                variants.putIfAbsent(synonym, 0.52)
                for (variant in suffixVariants(synonym)) variants.putIfAbsent(variant, 0.40)
            }
            out.add(Concept(term, variants))
        }
        return out
    }

    /**
     * Inflections of [term], both directions.
     *
     * The index does not stem, because a wrong stem makes a query unmatchable with no way for the
     * user to work around it. Expansion at query time has the same benefit without that failure:
     * the page keeps its literal words, and a miss simply falls back to the exact term.
     */
    private fun suffixVariants(term: String): Set<String> {
        if (term.length < 3) return emptySet()
        val out = LinkedHashSet<String>()
        fun add(value: String) {
            if (value.length >= 3 && value != term) out.add(value)
        }
        add(term + "s")
        add(term + "es")
        add(term + "ing")
        add(term + "ed")
        add(term + "er")
        add(term + "ly")
        when {
            term.endsWith("ies") && term.length > 4 -> add(term.dropLast(3) + "y")
            term.endsWith("ing") && term.length > 5 -> {
                add(term.dropLast(3))
                add(term.dropLast(3) + "e")
            }
            term.endsWith("ed") && term.length > 4 -> {
                add(term.dropLast(2))
                add(term.dropLast(1))
                if (term.length > 5 && term[term.length - 3] == term[term.length - 4]) {
                    add(term.dropLast(3))
                }
            }
            term.endsWith("es") && term.length > 4 -> {
                add(term.dropLast(2))
                add(term.dropLast(1))
            }
            term.endsWith("s") && !term.endsWith("ss") && term.length > 3 -> add(term.dropLast(1))
            term.endsWith("y") && term.length > 3 -> add(term.dropLast(1) + "ies")
        }
        return out
    }

    private fun correct(tokens: List<String>): Correction {
        if (tokens.isEmpty()) return Correction(tokens, false)
        var changed = false
        val out = ArrayList<String>(tokens.size)
        for (token in tokens) {
            val known = (documentFrequency[token] ?: 0) > 0
            if (known || token.length < 4 || token.all { it.isDigit() }) {
                out.add(token)
                continue
            }
            val candidate = bestCorrection(token)
            if (candidate != null) {
                out.add(candidate)
                changed = true
            } else {
                out.add(token)
            }
        }
        return Correction(out, changed)
    }

    /**
     * The most likely correction of an unknown term.
     *
     * Noisy-channel shape: candidate likelihood (how often the word appears in the crawl) divided
     * by an edit-distance penalty. Only engaged when the typed term is absent from the vocabulary,
     * so a rare but real word is never "corrected" into a common one.
     */
    private fun bestCorrection(token: String): String? {
        if (deletionIndex.isEmpty()) return null
        val candidates = LinkedHashSet<String>()
        deletionIndex[token]?.let { candidates.addAll(it) }
        for (one in deletes(token)) {
            deletionIndex[one]?.let { candidates.addAll(it) }
            if (candidates.size > 400) break
        }
        var best: String? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            if (candidate == token) continue
            if (abs(candidate.length - token.length) > 2) continue
            val distance = damerauLevenshtein(token, candidate)
            if (distance > 2) continue
            val df = documentFrequency[candidate] ?: continue
            // A tiny crawl has no statistics to speak of: requiring two documents before a
            // correction is allowed meant a ten-page index could never correct anything. Below
            // 500 terms, one sighting is enough evidence; above it, two keeps a single typo in
            // one page from becoming the dictionary.
            val minimum = if (documentFrequency.size < 500) 1 else 2
            if (df < minimum) continue
            val score = df.toDouble() / (1.0 + distance)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun deletes(term: String): List<String> {
        if (term.length < 3) return emptyList()
        val out = ArrayList<String>(term.length)
        for (i in term.indices) out.add(term.removeRange(i, i + 1))
        return out
    }

    /** Optimal-string-alignment distance, bounded so a pathological pair cannot spin. */
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > 2) return 3
        val rows = a.length + 1
        val cols = b.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) d[i][0] = i
        for (j in 0 until cols) d[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, d[i - 2][j - 2] + 1)
                }
                d[i][j] = value
            }
        }
        return d[a.length][b.length]
    }

    // ── Persistence ────────────────────────────────────────────────────────

    fun save() {
        synchronized(lock) {
            val arr = JSONArray()
            for (page in pages.values) {
                val links = JSONArray()
                page.outLinks.forEach { links.put(it) }
                val anchors = JSONObject()
                for ((target, text) in page.anchors) anchors.put(target, text)
                arr.put(
                    JSONObject()
                        .put("url", page.url)
                        .put("title", page.title)
                        .put("desc", page.description)
                        // Capped: the index is for ranking and snippets, not for archiving the web.
                        .put("text", page.text.take(4000))
                        .put("links", links)
                        .put("anchors", anchors)
                        .put("at", page.crawledAt)
                )
            }
            val root = JSONObject()
                .put("version", 3)
                .put("lastCrawlAt", lastCrawlAt)
                .put("pages", arr)
            val tmp = File(indexDir(), "index.json.part")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(indexFile())) {
                indexFile().writeText(root.toString())
                tmp.delete()
            }
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
                // Indexes written before anchors existed simply have none; the next crawl fills
                // them in, and nothing else about the page changes.
                val anchorObject = o.optJSONObject("anchors")
                val anchors = LinkedHashMap<String, String>()
                if (anchorObject != null) {
                    val keys = anchorObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        anchors[key] = anchorObject.optString(key, "")
                    }
                }
                loaded.add(
                    Page(
                        url = o.optString("url", ""),
                        title = o.optString("title", ""),
                        text = o.optString("text", ""),
                        outLinks = links,
                        crawledAt = o.optLong("at", 0L),
                        description = o.optString("desc", ""),
                        anchors = anchors,
                    )
                )
            }
            replaceAll(loaded.filter { it.url.isNotBlank() }, root.optLong("lastCrawlAt", 0L))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Text handling ──────────────────────────────────────────────────────

    /**
     * Lowercased alphanumeric runs, 2+ characters, with diacritics folded.
     *
     * Deliberately simple and language-agnostic: no stemming here, because a wrong stem silently
     * makes a query un-matchable. Query-time expansion handles morphology instead.
     */
    fun tokenize(s: String): List<String> {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in folded) {
            if (ch.isLetterOrDigit()) sb.append(ch)
            else {
                if (sb.length >= 2) out.add(sb.toString())
                sb.setLength(0)
            }
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

    private fun hostOf(url: String): String = try {
        com.prism.core.Urls.hostOf(url)?.lowercase().orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** Test/diagnostic access -- the rank assigned to a URL, or 0 if it is not indexed. */
    fun rankOf(url: String): Double = synchronized(lock) { pageRank[normalize(url)] ?: 0.0 }

    /** Terms the index knows, for a completion list. Sorted by document frequency. */
    fun suggestions(prefix: String, limit: Int = 8): List<String> = synchronized(lock) {
        val p = prefix.trim().lowercase()
        if (p.length < 2) return emptyList()
        documentFrequency.entries
            .asSequence()
            .filter { it.key.startsWith(p) && it.value > 0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toList()
    }

    /**
     * A small, general synonym map.
     *
     * Not a thesaurus: every pair here is one where a user would expect the other word to work.
     * Domain-specific expansion belongs in the crawler's corpus, not hardcoded in the engine.
     */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "fix" to listOf("repair", "solve"),
        "buy" to listOf("purchase", "shop"),
        "cheap" to listOf("affordable", "budget"),
        "fast" to listOf("quick", "speed"),
        "car" to listOf("automobile", "vehicle"),
        "phone" to listOf("mobile", "smartphone"),
        "email" to listOf("mail"),
        "doctor" to listOf("physician"),
        "movie" to listOf("film"),
        "recipe" to listOf("cooking", "cook"),
        "job" to listOf("career", "employment"),
        "error" to listOf("issue", "problem", "bug"),
        "start" to listOf("launch", "begin"),
        "delete" to listOf("remove"),
        "image" to listOf("picture", "photo"),
        "router" to listOf("gateway"),
        "wifi" to listOf("wireless"),
        "settings" to listOf("preferences", "options"),
        "search" to listOf("find", "lookup"),
        "backup" to listOf("archive", "snapshot"),
    )
}
