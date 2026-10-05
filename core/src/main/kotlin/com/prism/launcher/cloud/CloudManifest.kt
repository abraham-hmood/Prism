package com.prism.launcher.cloud

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * The index of what is stored where, encrypted with its own key.
 *
 * ## Why the manifest is encrypted too
 *
 * Because it is the interesting part. The chunks are opaque, but a plaintext index says "this person
 * stores 400 files, these are their names, this one is 8 GB and was added last night" — which for most
 * purposes is more revealing than any single file. Filenames alone are usually enough to identify
 * somebody's work, their medical records or their legal situation.
 *
 * It gets its own derived key, [CloudVault.Purpose.MANIFEST], rather than reusing the storage key. That
 * way the key that peers' stored chunks are encrypted under is not the key that also unlocks the map of
 * everything — so a future mistake that exposed one does not automatically expose the other.
 *
 * ## Why it is also pushed to peers
 *
 * A manifest that exists only on this device makes the cloud only as durable as the phone. The files
 * would still be out there on the mesh, still paid for, and completely unfindable — the chunk addresses
 * would be gone and nothing would connect them to a filename or an order. So the manifest is replicated
 * like any other data, under the namespace derived from the seed, which is what makes the recovery
 * phrase sufficient to recover the whole cloud on a new device.
 *
 * ## Holders are a hint, not a fact
 *
 * [ChunkRef.holders] records which peers accepted a chunk at upload time. Peers leave, wipe caches and
 * run out of storage, so a holder list is what to *try first*, not a guarantee. Retrieval treats a
 * holder that no longer has the chunk as a miss and asks the next one; the only real guarantee is
 * having asked enough peers to store it in the first place, which is what the replica count is for.
 */
object CloudManifest {

    private const val VERSION = 1

    data class ChunkRef(
        /** SHA-256 of the ciphertext. See [CloudVault.Chunk]. */
        val id: String,
        val index: Int,
        val bytes: Int,
        /** Mesh addresses that accepted this chunk. Ordered by when they answered. */
        val holders: List<String>,
    )

    data class Entry(
        val id: String,
        val name: String,
        /** Plaintext size. What the user thinks the file is. */
        val size: Long,
        val createdAt: Long,
        val chunks: List<ChunkRef>,
        /** Free text the user attached, or the folder it came from. */
        val note: String = "",
    ) {
        val replicated: Int get() = chunks.minOfOrNull { it.holders.size } ?: 0

        /**
         * Whether every chunk is somewhere.
         *
         * The minimum across chunks, not the average: a file with one unreplicated chunk is a file
         * that cannot be read, and an average would report it as almost fine.
         */
        val complete: Boolean get() = chunks.isNotEmpty() && chunks.all { it.holders.isNotEmpty() }

        fun describe(): String = buildString {
            append(formatBytes(size))
            append(" · ")
            append(chunks.size)
            append(if (chunks.size == 1) " chunk" else " chunks")
            append(" · ")
            append(
                when {
                    chunks.isEmpty() -> "nothing stored"
                    !complete -> "INCOMPLETE — some chunks are on no peer"
                    else -> "×$replicated copies"
                }
            )
        }
    }

    data class Snapshot(val entries: List<Entry>, val updatedAt: Long) {
        val totalBytes: Long get() = entries.sumOf { it.size }

        fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }
    }

    // ── Browsing ───────────────────────────────────────────────────────────

    /**
     * One row in the file browser: a file, or a folder standing for everything under it.
     *
     * There are no folder RECORDS. An [Entry.name] is a full path — `holidays/2024/beach.jpg` — and a
     * folder is inferred from the paths that share a prefix. That is the same decision object stores
     * make, and for the same reason: a separate folder record can disagree with the files in it, and
     * then the browser shows a folder that is empty or files that live nowhere. A prefix cannot
     * disagree with itself.
     *
     * The cost is that an empty folder cannot exist. Since a folder only ever appears here because the
     * user put a file in it, that is not a state anything needs.
     */
    data class Node(
        /** The segment shown in the list — a leaf filename, or one folder name. */
        val name: String,
        /** Full path from the root, for navigating into or opening. */
        val path: String,
        val isFolder: Boolean,
        /** Set only for files. */
        val entry: Entry?,
        /** Files at or below this node. 1 for a file. */
        val fileCount: Int,
        val bytes: Long,
        /** True when every file at or below this node has all its chunks on a peer. */
        val complete: Boolean,
    ) {
        fun describe(): String = if (!isFolder) {
            entry?.describe().orEmpty()
        } else {
            buildString {
                append(fileCount)
                append(if (fileCount == 1) " file · " else " files · ")
                append(formatBytes(bytes))
                if (!complete) append(" · some chunks missing")
            }
        }
    }

    /**
     * What is directly inside [folder]: its immediate subfolders and its own files.
     *
     * [folder] is `""` for the root, otherwise a path with no leading or trailing slash. Folders sort
     * before files and both sort by name, which is what every file browser does and what makes a list
     * scannable — sorting by size or date would put the same folder in a different place every visit.
     */
    fun list(snapshot: Snapshot, folder: String): List<Node> {
        val prefix = if (folder.isEmpty()) "" else "${folder.trim('/')}/"
        val files = ArrayList<Node>()
        val folders = LinkedHashMap<String, MutableList<Entry>>()

        snapshot.entries.forEach { entry ->
            val path = normalise(entry.name)
            if (prefix.isNotEmpty() && !path.startsWith(prefix)) return@forEach
            val relative = path.removePrefix(prefix)
            if (relative.isEmpty()) return@forEach

            val cut = relative.indexOf('/')
            if (cut < 0) {
                files.add(
                    Node(
                        name = relative,
                        path = path,
                        isFolder = false,
                        entry = entry,
                        fileCount = 1,
                        bytes = entry.size,
                        complete = entry.complete,
                    )
                )
            } else {
                folders.getOrPut(relative.substring(0, cut)) { mutableListOf() }.add(entry)
            }
        }

        val folderNodes = folders.map { (name, contents) ->
            Node(
                name = name,
                path = prefix + name,
                isFolder = true,
                entry = null,
                fileCount = contents.size,
                bytes = contents.sumOf { it.size },
                // A folder is only as sound as its worst file. Reporting it complete because most of
                // its files are would hide the one that cannot be restored.
                complete = contents.all { it.complete },
            )
        }

        return folderNodes.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
    }

    /** Breadcrumb segments for [folder], root first. */
    fun trail(folder: String): List<Pair<String, String>> {
        if (folder.isBlank()) return emptyList()
        val parts = folder.trim('/').split('/').filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>(parts.size)
        var accumulated = ""
        parts.forEach { part ->
            accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
            out.add(part to accumulated)
        }
        return out
    }

    fun parentOf(folder: String): String =
        folder.trim('/').substringBeforeLast('/', missingDelimiterValue = "")

    /**
     * Cleans a path that came from a filename or a user.
     *
     * Backslashes become slashes, repeats collapse, and `.` and `..` segments are dropped. The `..`
     * part matters: an entry named `../../x` would otherwise sort above the root and be unreachable
     * from the browser, and a path that cannot be navigated to is a file the user cannot get back.
     */
    fun normalise(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    /** Joins a folder and a leaf name into a stored path. */
    fun join(folder: String, name: String): String {
        val cleanFolder = normalise(folder)
        val cleanName = normalise(name).ifBlank { "file" }
        return if (cleanFolder.isEmpty()) cleanName else "$cleanFolder/$cleanName"
    }

    // ── Local storage ──────────────────────────────────────────────────────

    fun file(root: File): File = File(root, "manifest.bin")

    /**
     * Reads and decrypts the manifest, or returns an empty one.
     *
     * An empty snapshot rather than null when the file is absent, because "no wallet" and "no files
     * yet" are different situations and only the caller knows which it is in — [CloudVault.isReady]
     * answers the first. Returning null for both would make every caller conflate them.
     */
    fun load(root: File): Snapshot {
        val source = file(root)
        if (!source.isFile) return Snapshot(emptyList(), 0L)
        val key = CloudVault.keyFor(CloudVault.Purpose.MANIFEST) ?: return Snapshot(emptyList(), 0L)
        val plain = CloudVault.open(source.readBytes(), key) ?: return Snapshot(emptyList(), 0L)
        return decode(String(plain))
    }

    fun save(root: File, snapshot: Snapshot): Boolean {
        val key = CloudVault.keyFor(CloudVault.Purpose.MANIFEST) ?: return false
        root.mkdirs()
        val sealed = CloudVault.seal(encode(snapshot).toByteArray(), key)
        // Written beside and moved into place. A manifest half-written when the process dies is a
        // manifest that decrypts to nothing, and that is every file the user has stored.
        val temporary = File(root, "manifest.bin.new")
        temporary.writeBytes(sealed)
        return temporary.renameTo(file(root)) || run {
            file(root).delete()
            temporary.renameTo(file(root))
        }
    }

    fun add(root: File, entry: Entry): Snapshot {
        val current = load(root)
        val updated = Snapshot(
            entries = current.entries.filterNot { it.id == entry.id } + entry,
            updatedAt = System.currentTimeMillis(),
        )
        save(root, updated)
        return updated
    }

    fun remove(root: File, id: String): Snapshot {
        val current = load(root)
        val updated = Snapshot(
            entries = current.entries.filterNot { it.id == id },
            updatedAt = System.currentTimeMillis(),
        )
        save(root, updated)
        return updated
    }

    /**
     * Records that a chunk gained or lost a holder.
     *
     * Called after a repair run, so the panel's replica counts reflect reality rather than what was
     * true at upload. Without this a file whose peers all left would keep claiming three copies.
     */
    fun updateHolders(root: File, fileId: String, chunkId: String, holders: List<String>): Snapshot {
        val current = load(root)
        val updated = Snapshot(
            entries = current.entries.map { entry ->
                if (entry.id != fileId) entry else entry.copy(
                    chunks = entry.chunks.map { chunk ->
                        if (chunk.id != chunkId) chunk else chunk.copy(holders = holders)
                    }
                )
            },
            updatedAt = System.currentTimeMillis(),
        )
        save(root, updated)
        return updated
    }

    // ── Serialisation ──────────────────────────────────────────────────────

    fun encode(snapshot: Snapshot): String = JSONObject().apply {
        put("version", VERSION)
        put("updated", snapshot.updatedAt)
        put("files", JSONArray().also { array ->
            snapshot.entries.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("size", entry.size)
                        put("created", entry.createdAt)
                        put("note", entry.note)
                        put("chunks", JSONArray().also { chunkArray ->
                            entry.chunks.forEach { chunk ->
                                chunkArray.put(
                                    JSONObject().apply {
                                        put("id", chunk.id)
                                        put("i", chunk.index)
                                        put("b", chunk.bytes)
                                        put("h", JSONArray().also { h ->
                                            chunk.holders.forEach { holder -> h.put(holder) }
                                        })
                                    }
                                )
                            }
                        })
                    }
                )
            }
        })
    }.toString()

    fun decode(text: String): Snapshot = runCatching {
        val json = JSONObject(text)
        val files = json.optJSONArray("files")
        val entries = ArrayList<Entry>()
        for (i in 0 until (files?.length() ?: 0)) {
            val item = files?.optJSONObject(i) ?: continue
            val chunkArray = item.optJSONArray("chunks")
            val chunks = ArrayList<ChunkRef>()
            for (c in 0 until (chunkArray?.length() ?: 0)) {
                val chunk = chunkArray?.optJSONObject(c) ?: continue
                val holdersArray = chunk.optJSONArray("h")
                val holders = ArrayList<String>()
                for (h in 0 until (holdersArray?.length() ?: 0)) {
                    holdersArray?.optString(h)?.takeIf { it.isNotBlank() }?.let { holders.add(it) }
                }
                chunks.add(
                    ChunkRef(
                        id = chunk.optString("id"),
                        index = chunk.optInt("i"),
                        bytes = chunk.optInt("b"),
                        holders = holders,
                    )
                )
            }
            entries.add(
                Entry(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    size = item.optLong("size"),
                    createdAt = item.optLong("created"),
                    chunks = chunks.sortedBy { it.index },
                    note = item.optString("note"),
                )
            )
        }
        Snapshot(entries, json.optLong("updated"))
    }.getOrDefault(Snapshot(emptyList(), 0L))

    fun formatBytes(value: Long): String = when {
        value >= 1L shl 40 -> "%.2f TB".format(value / (1L shl 40).toDouble())
        value >= 1L shl 30 -> "%.2f GB".format(value / (1L shl 30).toDouble())
        value >= 1L shl 20 -> "%.1f MB".format(value / (1L shl 20).toDouble())
        value >= 1L shl 10 -> "%.0f KB".format(value / (1L shl 10).toDouble())
        else -> "$value B"
    }
}
