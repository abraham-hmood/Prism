package com.prism.launcher.clipboard

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * Everything this device has copied, plus whatever trusted devices have shared.
 *
 * ## Why a history rather than a clipboard
 *
 * A phone's clipboard holds one thing, and it is gone the moment something else copies. That is
 * fine for pasting once and hopeless for the case this exists for: something was copied on the
 * phone and is wanted on the desktop ten minutes later, or a file was cut in the explorer and the
 * user has navigated somewhere else before pasting. A list also makes the share explicit -- the
 * user can see what crossed the mesh, which a silently-synced single item never showed them.
 *
 * ## Why text and files are the same list
 *
 * Because from the user's side "what I copied" is one idea. A file entry carries a path, whether
 * it is a folder, and how big it is; pasting it hands those to the file operations layer rather
 * than to the text clipboard. Keeping two stores would mean two screens and an "active" that
 * disagreed with itself.
 *
 * ## Remote entries
 *
 * An entry received from a trusted device has a [Entry.sourceFingerprint] and no local path. It is
 * not pasted locally until it is fetched -- see `TrustedFileTransfer` -- and a CUT entry is removed
 * from the SENDER only after that transfer succeeds. The ledger is deliberately not the authority
 * on either: it says what was shared, and the device that holds the file says whether it can still
 * hand it over.
 */
object ClipboardHistory {

    private const val TAG = "PrismClipboard"

    /** Enough to cover a working session; small enough that the JSON stays a few hundred KB. */
    const val CAPACITY = 200

    enum class Kind { TEXT, FILE }

    /** Whether the source still holds the item, which is what makes a paste a move. */
    enum class Mode { COPY, CUT }

    data class Entry(
        val id: String,
        val kind: Kind,
        val text: String = "",
        val name: String = "",
        /** Absolute path on the device that owns it. Empty for an entry that arrived over the mesh. */
        val path: String = "",
        val directory: Boolean = false,
        val bytes: Long = 0,
        val mode: Mode = Mode.COPY,
        /** Empty when this device produced the entry. */
        val sourceFingerprint: String = "",
        val sourceName: String = "",
        val at: Long = System.currentTimeMillis(),
    ) {
        /**
         * Arrived from another device and is not on this one yet.
         *
         * PATH AS WELL AS SOURCE, because a remote entry becomes local the moment it is fetched:
         * it keeps the name of the device it came from (that is useful), but has real bytes here
         * now, and a paste must not try to download it a second time.
         */
        val remote: Boolean get() = sourceFingerprint.isNotBlank() && path.isBlank()

        /** One line for a list row. */
        fun summary(): String = when (kind) {
            Kind.TEXT -> text.replace('\n', ' ').take(120)
            Kind.FILE ->
                (if (directory) "Folder" else "File") +
                    " · " + (if (remote) "from " + sourceName.ifBlank { "a trusted device" }
                    else "on this device") +
                    if (bytes > 0) " · " + sizeLabel(bytes) else ""
        }

        fun title(): String = when (kind) {
            Kind.TEXT -> text.replace('\n', ' ').take(60).ifBlank { "Copied text" }
            Kind.FILE -> name.ifBlank { "Copied item" }
        }

        private fun sizeLabel(value: Long): String = when {
            value >= 1L shl 30 -> (value / (1L shl 30)).toString() + " GB"
            value >= 1L shl 20 -> (value / (1L shl 20)).toString() + " MB"
            value >= 1L shl 10 -> (value / (1L shl 10)).toString() + " KB"
            else -> value.toString() + " B"
        }
    }

    @Volatile
    private var root: File? = null

    private val lock = Any()
    private val entries = ArrayList<Entry>()

    @Volatile
    private var activeId: String = ""

    /**
     * Called after any change, on whatever thread made it.
     *
     * Used by the UI to re-read the list and by the platform layer to refresh the paste button. A
     * single hook rather than an observer list, because everything that displays this is one screen.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun install(directory: File) {
        synchronized(lock) {
            root = directory.apply { mkdirs() }
            load()
        }
    }

    private fun file(): File? = root?.let { File(it, "clipboard-history.json") }

    private fun load() {
        entries.clear()
        activeId = ""
        val source = file() ?: return
        if (!source.isFile) return
        runCatching {
            val array = JSONArray(source.readText())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                fromJson(item)?.let { entries.add(it) }
            }
            // `active` is a boolean on the row, so the one flagged is the id that survives the
            // restart. Reading it as a string (the first version) always came back empty and the
            // active entry silently became "the newest", which looks the same until the user's
            // chosen item is not the newest one.
            activeId = (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .firstOrNull { it.optBoolean("active") }
                ?.optString("id")
                .orEmpty()
        }.onFailure { PrismPlatform.log.warn(TAG, "Could not read clipboard history: " + it.message) }
    }

    fun all(): List<Entry> = synchronized(lock) { entries.toList() }

    fun byId(id: String): Entry? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    fun active(): Entry? = synchronized(lock) {
        entries.firstOrNull { it.id == activeId } ?: entries.firstOrNull()
    }

    /** Makes an entry the one a paste uses. Tapping a row is the only caller. */
    fun setActive(id: String): Entry? = synchronized(lock) {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        activeId = id
        save()
        entry
    }

    /**
     * Adds an entry and makes it active.
     *
     * DEDUPLICATED, because copying the same text twice should move the row to the top rather than
     * fill the list with it -- and because a clipboard watcher sees the same content more than once
     * when an app re-copies what it already had.
     */
    fun record(entry: Entry): Entry = synchronized(lock) {
        val duplicate = entries.firstOrNull {
            it.id == entry.id || (it.kind == entry.kind &&
                it.sourceFingerprint == entry.sourceFingerprint &&
                when (entry.kind) {
                    Kind.TEXT -> it.text == entry.text
                    Kind.FILE -> it.path == entry.path && it.name == entry.name
                })
        }
        entries.remove(duplicate)
        entries.add(0, entry)
        while (entries.size > CAPACITY) entries.removeAt(entries.lastIndex)
        activeId = entry.id
        save()
        entry
    }

    fun recordText(text: String, sourceFingerprint: String = "", sourceName: String = ""): Entry? {
        if (text.isBlank()) return null
        return record(
            Entry(
                id = java.util.UUID.randomUUID().toString(),
                kind = Kind.TEXT,
                text = text,
                sourceFingerprint = sourceFingerprint,
                sourceName = sourceName,
            )
        )
    }

    fun recordFile(
        file: java.io.File,
        mode: Mode,
        sourceFingerprint: String = "",
        sourceName: String = "",
    ): Entry = record(
        Entry(
            id = java.util.UUID.randomUUID().toString(),
            kind = Kind.FILE,
            name = file.name,
            path = if (sourceFingerprint.isBlank()) file.absolutePath else "",
            directory = file.isDirectory,
            bytes = if (file.isFile) file.length() else 0,
            mode = mode,
            sourceFingerprint = sourceFingerprint,
            sourceName = sourceName,
        )
    )

    /** A file entry learned from a peer, which has no local path until it is fetched. */
    fun recordRemoteFile(
        name: String,
        directory: Boolean,
        bytes: Long,
        mode: Mode,
        fromFingerprint: String,
        fromName: String,
        /** The sender's entry id, which is what the fetch route is keyed on. */
        id: String = "",
    ): Entry? {
        if (name.isBlank()) return null
        return record(
            Entry(
                id = id.ifBlank { java.util.UUID.randomUUID().toString() },
                kind = Kind.FILE,
                name = name,
                directory = directory,
                bytes = bytes,
                mode = mode,
                sourceFingerprint = fromFingerprint,
                sourceName = fromName,
            )
        )
    }

    /** Replaces a row, used when a remote entry has been fetched and now has a local path. */
    fun replace(entry: Entry) {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) entries[index] = entry
            save()
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            entries.removeAll { it.id == id }
            if (activeId == id) activeId = entries.firstOrNull()?.id.orEmpty()
            save()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            activeId = ""
            save()
        }
    }

    private fun save() {
        val target = file() ?: return
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                toJson(entry).apply { put("active", entry.id == activeId) }
            )
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(array.toString())
        }.onFailure { PrismPlatform.log.warn(TAG, "Could not save clipboard history: " + it.message) }
        runCatching { onChanged?.invoke() }
    }

    // ── Wire format ────────────────────────────────────────────────────────

    fun toJson(entry: Entry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("kind", if (entry.kind == Kind.TEXT) "text" else "file")
        put("mode", if (entry.mode == Mode.CUT) "cut" else "copy")
        put("at", entry.at)
        if (entry.kind == Kind.TEXT) {
            put("text", entry.text)
        } else {
            put("name", entry.name)
            put("dir", entry.directory)
            put("bytes", entry.bytes)
            // The path is LOCAL and is deliberately never put on the wire: a peer has no use for
            // this device's filesystem layout, and a path is one of the more identifying things a
            // machine can reveal about its owner.
        }
    }

    fun fromJson(json: JSONObject): Entry? = runCatching {
        val kind = if (json.optString("kind") == "file") Kind.FILE else Kind.TEXT
        val text = json.optString("text")
        val name = json.optString("name")
        if (kind == Kind.TEXT && text.isBlank()) return null
        if (kind == Kind.FILE && name.isBlank()) return null
        Entry(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            kind = kind,
            text = text,
            name = name,
            path = json.optString("path"),
            directory = json.optBoolean("dir"),
            bytes = json.optLong("bytes", 0L),
            mode = if (json.optString("mode") == "cut") Mode.CUT else Mode.COPY,
            sourceFingerprint = json.optString("from"),
            sourceName = json.optString("name_from"),
            at = json.optLong("at", System.currentTimeMillis()),
        )
    }.getOrNull()
}
