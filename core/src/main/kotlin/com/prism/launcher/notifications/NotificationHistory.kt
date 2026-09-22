package com.prism.launcher.notifications

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Every notification this device has shown, kept and searchable.
 *
 * Android's own notification shade forgets a notification the moment it is dismissed, which is the
 * single most common way people lose something they were shown -- a code, a delivery time, the name
 * of a person who messaged once. This keeps them.
 *
 * ## Why it is not in the Room database
 *
 * `AppDatabase` is built with `fallbackToDestructiveMigration(dropAllTables = true)`, so adding a
 * table means bumping its version and a version bump silently deletes the user's app statistics,
 * agentic tools and entire Nebula feed -- its own doc comment says so. [PrismHistory] and
 * `PrismSearchIndex` both reached that conclusion and keep their own files; this follows them.
 *
 * ## Append-only on disk, collapsed on read
 *
 * One JSON line per notification, so a write is O(1) and a crash costs at most the line being
 * written rather than the whole file. Collapsing happens when the file is read, and it matters more
 * here than it does for page history: a music player or a download rewrites the same notification
 * dozens of times a minute, and a list that shows each rewrite separately is unreadable. Repeats of
 * the same (app, title, body) collapse into one entry carrying a count and the latest timestamp.
 *
 * ## No time limit, which is what was asked for -- but a size bound
 *
 * Nothing is dropped for being old. [MAX_ENTRIES] is a bound on COUNT, not age, and it exists
 * because a file that grows without limit eventually costs more to load than it is worth. At the
 * cap the oldest entries go first. A notification is a few hundred bytes, so the cap is set high
 * enough that a heavy year of use fits inside it.
 *
 * ## This is among the most sensitive data on the device
 *
 * Notification bodies contain message text, two-factor codes, medical reminders and banking
 * amounts. So, structurally rather than as a promise: nothing is written while
 * [PrismSettings.getNotificationHistoryEnabled] is off, [clear] deletes the file rather than marking
 * rows dead, and the listener never records notifications from apps the user has excluded.
 */
object NotificationHistory {

    private const val FILE_NAME = "notifications.jsonl"

    /** A bound on how many entries are kept, not on how old they may be. See the class comment. */
    private const val MAX_ENTRIES = 100_000

    /** Rewrites of the same notification inside this window collapse instead of appending. */
    private const val DEDUPE_WINDOW_MS = 60 * 60 * 1000L

    /** How many recent entries a new notification is compared against. See [record]. */
    private const val DEDUPE_SCAN = 200

    private const val MAX_TITLE_CHARS = 300
    private const val MAX_TEXT_CHARS = 2_000

    /**
     * One notification, as it was shown.
     *
     * [key] is the platform's own notification key where there is one. It is stored rather than
     * derived because it is the only thing that reliably identifies "the same notification" across a
     * post, an update and a dismissal -- title and text both change while a notification lives.
     */
    data class Record(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val at: Long,
        val key: String = "",
        val ongoing: Boolean = false,
        /** How many times this same content was posted. See the collapsing note above. */
        val count: Int = 1,
    ) {
        /** What a search matches against: title, body and app name, which is what was asked for. */
        fun matches(needle: String): Boolean =
            title.contains(needle, ignoreCase = true) ||
                text.contains(needle, ignoreCase = true) ||
                appLabel.contains(needle, ignoreCase = true) ||
                packageName.contains(needle, ignoreCase = true)
    }

    private val lock = Any()

    /** Newest last. Null until the file has been read. */
    private var records: MutableList<Record>? = null

    private fun dir(): File =
        File(PrismPlatform.host.dataDir(), "prism_notifications").apply { mkdirs() }

    private fun file(): File = File(dir(), FILE_NAME)

    // ── Recording ──────────────────────────────────────────────────────────

    /**
     * Records one notification. Cheap enough to call straight from a listener callback.
     *
     * Does nothing when notification history is switched off, which is what every caller wants:
     * none of them should have to ask first, and one forgetting to ask would be a privacy bug rather
     * than a missing feature.
     *
     * @return true when something was written or collapsed into an existing entry.
     */
    fun record(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        at: Long = System.currentTimeMillis(),
        key: String = "",
        ongoing: Boolean = false,
    ): Boolean {
        if (!PrismSettings.getNotificationHistoryEnabled()) return false
        if (packageName.isBlank()) return false

        val cleanTitle = title.trim().take(MAX_TITLE_CHARS)
        val cleanText = text.trim().take(MAX_TEXT_CHARS)
        // A notification with neither a title nor a body is a placeholder -- a media session's
        // transport controls, a foreground-service stub. There is nothing to show or search for.
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return false

        synchronized(lock) {
            val list = loaded()

            // Collapse a rewrite of the same content rather than appending. Walked from the back
            // because the match, if there is one, is almost always among the most recent entries.
            //
            // BOUNDED BY COUNT, NOT BY TIMESTAMP. This used to stop as soon as it met an entry older
            // than the dedupe window, which assumed the list was in time order -- and it is not. The
            // backlog read at onListenerConnected arrives in whatever order the platform returns its
            // active notifications, so one old entry near the end ended the scan early and let
            // duplicates through. Scanning a fixed number of recent entries does not care what order
            // they are in, and the timestamp check moved inside the comparison where it belongs.
            val from = maxOf(0, list.size - DEDUPE_SCAN)
            for (index in list.indices.reversed()) {
                if (index < from) break
                val existing = list[index]
                if (kotlin.math.abs(at - existing.at) > DEDUPE_WINDOW_MS) continue
                if (existing.packageName == packageName &&
                    existing.title == cleanTitle &&
                    existing.text == cleanText
                ) {
                    list[index] = existing.copy(
                        at = maxOf(at, existing.at),
                        count = existing.count + 1,
                        ongoing = ongoing,
                    )
                    rewrite(list)
                    return true
                }
            }

            val record = Record(
                packageName = packageName,
                appLabel = appLabel.ifBlank { packageName },
                title = cleanTitle,
                text = cleanText,
                at = at,
                key = key,
                ongoing = ongoing,
            )
            // Inserted in time order rather than appended, so "newest first" on the way out needs no
            // sort. Almost always an append -- a notification that has just arrived is the newest
            // thing there is -- but the backlog at connect time is not ordered, and neither is a
            // notification whose postTime is older than when the listener saw it.
            val position = list.indexOfLast { it.at <= record.at } + 1
            if (position >= list.size) list.add(record) else list.add(position, record)

            if (list.size > MAX_ENTRIES) {
                // Oldest first, and the whole file is rewritten -- the cheap append is no longer
                // possible once the front has to move, and this happens once every hundred thousand
                // notifications rather than once per notification.
                while (list.size > MAX_ENTRIES) list.removeAt(0)
                rewrite(list)
            } else if (position >= list.size - 1) {
                runCatching { file().appendText(encode(record) + "\n") }
            } else {
                // It landed in the middle, so the file no longer matches the list. Rare: only for
                // out-of-order arrivals. The order is restored on load anyway, but leaving the file
                // disagreeing with memory is the kind of difference that only shows up much later.
                rewrite(list)
            }
            return true
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** Everything kept, newest first. */
    fun all(): List<Record> = synchronized(lock) { loaded().asReversed().toList() }

    /**
     * Notifications matching [query] in title, body or app name, newest first.
     *
     * A blank query returns the most recent entries rather than nothing, so the page has something
     * to show before anything is typed.
     */
    fun search(query: String, limit: Int = 200): List<Record> {
        val needle = query.trim()
        return synchronized(lock) {
            val list = loaded()
            if (needle.isEmpty()) {
                list.asReversed().take(limit).toList()
            } else {
                // Every term must match somewhere, so "slack alice" finds a message from Alice in
                // Slack rather than everything from either.
                val terms = needle.split(Regex("\\s+")).filter { it.isNotEmpty() }
                list.asReversed().asSequence()
                    .filter { record -> terms.all { record.matches(it) } }
                    .take(limit)
                    .toList()
            }
        }
    }

    /** How many notifications are kept. For the settings screen. */
    fun count(): Int = synchronized(lock) { loaded().size }

    /** Deletes the file. Not a flag on rows -- see the class comment. */
    fun clear() {
        synchronized(lock) {
            records = mutableListOf()
            runCatching { file().delete() }
        }
    }

    /** Forgets everything from one app, for the "never record this app" case. */
    fun clear(packageName: String) {
        synchronized(lock) {
            val list = loaded()
            if (list.removeAll { it.packageName == packageName }) rewrite(list)
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun loaded(): MutableList<Record> {
        records?.let { return it }
        val list = load()
        records = list
        return list
    }

    private fun load(): MutableList<Record> {
        val source = file()
        if (!source.isFile) return mutableListOf()
        val out = mutableListOf<Record>()
        runCatching {
            source.forEachLine { line ->
                if (line.isNotBlank()) decode(line)?.let { out.add(it) }
            }
        }
        // Oldest first. The file is append-ordered, which is nearly but not exactly time order --
        // the backlog captured when the listener connects is written in the platform's order, and a
        // page that groups by day renders that as Today, then Yesterday, then Today again.
        out.sortBy { it.at }
        return out
    }

    private fun rewrite(list: List<Record>) {
        runCatching {
            val temp = File(dir(), "$FILE_NAME.tmp")
            temp.bufferedWriter().use { writer ->
                for (record in list) {
                    writer.write(encode(record))
                    writer.write("\n")
                }
            }
            // Through a temporary file, so an interrupted compaction cannot leave a half-written
            // history where a complete one used to be.
            if (!temp.renameTo(file())) {
                file().writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun encode(record: Record): String =
        JSONObject().apply {
            put("p", record.packageName)
            put("a", record.appLabel)
            put("ti", record.title)
            put("tx", record.text)
            put("at", record.at)
            if (record.key.isNotEmpty()) put("k", record.key)
            if (record.ongoing) put("o", true)
            if (record.count != 1) put("c", record.count)
        }.toString()

    private fun decode(line: String): Record? = runCatching {
        val json = JSONObject(line)
        Record(
            packageName = json.optString("p", ""),
            appLabel = json.optString("a", ""),
            title = json.optString("ti", ""),
            text = json.optString("tx", ""),
            at = json.optLong("at", 0L),
            key = json.optString("k", ""),
            ongoing = json.optBoolean("o", false),
            count = json.optInt("c", 1),
        ).takeIf { it.packageName.isNotEmpty() }
    }.getOrNull()
}
