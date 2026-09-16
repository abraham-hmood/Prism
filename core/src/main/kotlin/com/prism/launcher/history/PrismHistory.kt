package com.prism.launcher.history

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.search.PrismSearchIndex
import java.io.File

/**
 * Everything this device has shown its owner, in one searchable place.
 *
 * Pages read, videos watched, messages exchanged, files opened, searches run -- recorded as they
 * happen and queryable afterwards by words, kind and date. The point is the question no service can
 * answer for you today: "what was that article about quantisation I read last month", asked of a
 * machine that actually saw you read it.
 *
 * ## Why it is not in the Room database
 *
 * `AppDatabase` is configured with `fallbackToDestructiveMigration`, so adding a table means bumping
 * its version, and a version bump silently deletes the user's app statistics, agentic tools and
 * entire Nebula feed -- its own doc comment says so. [PrismSearchIndex] reached the same conclusion
 * and keeps its own files; this follows it. A history is also rebuildable-ish and disposable by
 * nature, so it has no business sharing a lifetime with data that is not.
 *
 * ## Append-only on disk, collapsed in memory
 *
 * Every event appends one JSON line. Writes are therefore O(1) and a crash costs at most the line
 * being written, rather than the whole file the way a rewrite-on-every-event design would. Repeat
 * visits are collapsed when the file is read: the same page seen forty times is one entry with a
 * visit count and the most recent timestamp, which is both what the user means by "a thing I read"
 * and what keeps a search result list worth looking at.
 *
 * ## This is the most sensitive data on the device
 *
 * It is a record of what somebody read, watched and said. Three things follow, and they are
 * structural rather than promises: it is never written while [PrismSettings.getHistoryEnabled] is
 * off, private browsing is never offered to it in the first place (the browser decides that, not
 * this), and [clear] genuinely deletes the file rather than marking rows dead.
 */
object PrismHistory {

    private const val FILE_NAME = "history.jsonl"

    /**
     * Entries held in memory and on disk. Old ones are dropped from the front when the file is
     * compacted -- a history that grows without limit eventually costs more to load than it is
     * worth, and the oldest entries are the ones least likely to be searched for.
     */
    private const val MAX_ENTRIES = 50_000

    /** Re-recording the same thing inside this window is treated as still-the-same-visit. */
    private const val DEDUPE_WINDOW_MS = 10 * 60 * 1000L

    /** What kind of thing was seen. Queries can filter on these. */
    enum class Kind {
        PAGE,
        VIDEO,
        MESSAGE,
        FILE,
        SEARCH,
        APP,
        ;

        companion object {
            fun parse(raw: String?): Kind? =
                Kind.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    /**
     * One thing the user saw.
     *
     * [text] is the searchable body -- a page's visible text, a message, a file's name and path. It
     * is capped on the way in: a history is an index of what you saw, not a second copy of it, and
     * the full article is already in the web cache for anyone who wants it.
     */
    data class Entry(
        val kind: Kind,
        val title: String,
        val uri: String,
        val text: String,
        val at: Long,
        /** Where it came from: an app name, a host, a contact. */
        val source: String = "",
        val visits: Int = 1,
    )

    private const val MAX_TEXT_CHARS = 2_000
    private const val MAX_TITLE_CHARS = 300

    private val lock = Any()

    /** Newest last. Null until the file has been read. Deliberately NOT named `entries`: that
     *  shadows the enum's own `entries` inside Kind's companion and resolves to this list. */
    private var records: MutableList<Entry>? = null

    /** kind|uri -> when it was last appended, so a reload loop does not write forty lines. */
    private val lastWrite = HashMap<String, Long>()

    private fun dir(): File = File(PrismPlatform.host.dataDir(), "prism_history").apply { mkdirs() }

    private fun file(): File = File(dir(), FILE_NAME)

    // ── Recording ──────────────────────────────────────────────────────────

    /**
     * Records one thing the user saw. Cheap enough to call from a UI callback: one appended line.
     *
     * Silently does nothing when history is switched off, which is the behaviour every caller wants
     * -- none of them should have to ask first, and one forgetting to would be a privacy bug rather
     * than a missing feature.
     */
    fun record(
        kind: Kind,
        title: String,
        uri: String,
        text: String = "",
        source: String = "",
        at: Long = System.currentTimeMillis(),
    ) {
        if (!PrismSettings.getHistoryEnabled()) return
        if (title.isBlank() && uri.isBlank()) return

        val key = "${kind.name}|$uri"
        synchronized(lock) {
            val previous = lastWrite[key]
            if (previous != null && at - previous < DEDUPE_WINDOW_MS) return
            lastWrite[key] = at
        }

        val entry = Entry(
            kind = kind,
            title = title.take(MAX_TITLE_CHARS).trim(),
            uri = uri.trim(),
            text = text.take(MAX_TEXT_CHARS).trim(),
            at = at,
            source = source.take(120).trim(),
        )

        synchronized(lock) {
            records?.add(entry)
            runCatching { file().appendText(encode(entry) + "\n") }
                .onFailure { PrismPlatform.log.warn("Prism/history", "Could not record: ${it.message}") }
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** Everything held, oldest first. Loads from disk on first use. */
    private fun all(): List<Entry> = synchronized(lock) {
        records ?: load().also { records = it.toMutableList() }
    }

    fun count(): Int = all().size

    /** The most recent entries, newest first, optionally restricted to certain kinds. */
    fun recent(limit: Int = 50, kinds: Set<Kind> = emptySet()): List<Entry> =
        all().asReversed()
            .asSequence()
            .filter { kinds.isEmpty() || it.kind in kinds }
            .take(limit.coerceIn(1, 500))
            .toList()

    /**
     * Searches the history.
     *
     * RANKED BY MATCH STRENGTH AND THEN RECENCY, because both matter and neither alone is enough. A
     * pure relevance sort buries this morning's page under a better-matching one from a year ago,
     * which is the opposite of what someone asking "what was that thing I read" wants; pure recency
     * is just the list they already have. Title matches count for more than body matches for the
     * same reason they do in [PrismSearchIndex]: a page whose title is the query is the answer.
     *
     * An empty query is a valid request -- it means "everything in this range", which is how the
     * time filters get used on their own.
     */
    fun search(
        query: String,
        kinds: Set<Kind> = emptySet(),
        since: Long? = null,
        until: Long? = null,
        limit: Int = 25,
    ): List<Entry> {
        val terms = PrismSearchIndex.tokenize(query).distinct()
        val now = System.currentTimeMillis()

        val candidates = all().asSequence()
            .filter { kinds.isEmpty() || it.kind in kinds }
            .filter { since == null || it.at >= since }
            .filter { until == null || it.at <= until }

        if (terms.isEmpty()) {
            return candidates.sortedByDescending { it.at }.take(limit.coerceIn(1, 200)).toList()
        }

        return candidates
            .map { entry -> entry to score(entry, terms, now) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 200))
            .map { it.first }
            .toList()
    }

    private fun score(entry: Entry, terms: List<String>, now: Long): Double {
        val titleTerms = PrismSearchIndex.tokenize(entry.title)
        val bodyTerms = PrismSearchIndex.tokenize(entry.text) + PrismSearchIndex.tokenize(entry.uri)

        var matched = 0
        var hits = 0.0
        for (term in terms) {
            val inTitle = titleTerms.count { it == term }
            val inBody = bodyTerms.count { it == term }
            if (inTitle + inBody == 0) continue
            matched++
            hits += inTitle * 3.0 + inBody
        }
        // Every term has to appear somewhere: a two-word query that matches one word is usually the
        // wrong entry, and letting it through fills the list with near-misses.
        if (matched < terms.size) return 0.0

        // Recency as a gentle multiplier rather than a tiebreak -- halving roughly every 90 days, so
        // a strong match from last year still outranks a weak one from yesterday.
        val ageDays = ((now - entry.at).coerceAtLeast(0L)) / 86_400_000.0
        val recency = 1.0 / (1.0 + ageDays / 90.0)
        return hits * (0.5 + recency) * (1.0 + kotlin.math.ln(1.0 + entry.visits))
    }

    // ── Storage ────────────────────────────────────────────────────────────

    /**
     * Reads the file, collapsing repeats and trimming to [MAX_ENTRIES].
     *
     * Collapsing happens here rather than at write time so that appending stays a single line with
     * no read-modify-write. The newest occurrence wins for the timestamp, and the count of how often
     * something was seen survives, which is what makes "the page I kept going back to" findable.
     */
    private fun load(): List<Entry> {
        val f = file()
        if (!f.isFile) return emptyList()

        val collapsed = LinkedHashMap<String, Entry>()
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val entry = decode(line) ?: return@forEachLine
                val key = "${entry.kind.name}|${entry.uri}|${entry.title}"
                val existing = collapsed[key]
                collapsed[key] = if (existing == null) {
                    entry
                } else {
                    // Keep whichever text is richer: an early visit may have been recorded before
                    // the page finished filling in.
                    existing.copy(
                        at = maxOf(existing.at, entry.at),
                        visits = existing.visits + entry.visits,
                        text = if (entry.text.length > existing.text.length) entry.text else existing.text,
                        title = entry.title.ifBlank { existing.title },
                    )
                }
            }
        }.onFailure { PrismPlatform.log.warn("Prism/history", "Could not read history: ${it.message}") }

        val ordered = collapsed.values.sortedBy { it.at }
        if (ordered.size <= MAX_ENTRIES) return ordered

        // Over the cap: keep the newest and rewrite, so the next load is cheap again.
        val trimmed = ordered.takeLast(MAX_ENTRIES)
        runCatching {
            f.writeText(trimmed.joinToString("\n", postfix = "\n") { encode(it) })
        }
        return trimmed
    }

    /** Forgets everything. The file is deleted, not emptied of rows. */
    fun clear() {
        synchronized(lock) {
            records = mutableListOf()
            lastWrite.clear()
            runCatching { file().delete() }
        }
    }

    /** Forgets everything of one kind -- "delete my browsing history" without losing the rest. */
    fun clear(kind: Kind) {
        synchronized(lock) {
            val kept = all().filterNot { it.kind == kind }
            records = kept.toMutableList()
            lastWrite.keys.removeAll { it.startsWith("${kind.name}|") }
            runCatching {
                file().writeText(kept.joinToString("\n", postfix = "\n") { encode(it) })
            }
        }
    }

    private fun encode(e: Entry): String = JSONObject().apply {
        put("k", e.kind.name)
        put("t", e.title)
        put("u", e.uri)
        put("x", e.text)
        put("at", e.at)
        put("s", e.source)
        put("v", e.visits)
    }.toString()

    private fun decode(line: String): Entry? = runCatching {
        val o = JSONObject(line)
        val kind = Kind.parse(o.optString("k")) ?: return null
        Entry(
            kind = kind,
            title = o.optString("t"),
            uri = o.optString("u"),
            text = o.optString("x"),
            at = o.optLong("at", 0L),
            source = o.optString("s"),
            visits = o.optInt("v", 1).coerceAtLeast(1),
        )
    }.getOrNull()
}
