package com.prism.launcher.datasetcraft

import java.io.File
import java.util.Locale

/**
 * How a dataset row names a file.
 *
 * A CSV is written on one machine and trained on another: the same manifest can say
 * `clips/take-1.wav`, `clips\take-1.wav`, an absolute Windows path, or just `take-1.wav`. All four
 * mean the file beside the CSV, and a dataset that resolves only the spelling its author's operating
 * system used is a dataset that "is missing" every row on the other platform.
 *
 * [resolve] therefore tries the path as written, then the path relative to the CSV, then its file
 * name in that folder, and finally an [Index] of the folder's files, which is also how a copy that
 * was renamed during an earlier import is still found.
 */
object DatasetReferences {

    /** How deep an [Index] looks for a referenced file below its root. */
    private const val MAX_SEARCH_DEPTH = 8

    /** Characters no file name can carry on at least one platform Prism runs on. */
    private val UNSAFE = Regex("[\\\\/:*?\"<>|]")

    /** What an earlier import replaced with underscores. Matched so old datasets keep working. */
    private val LEGACY_UNSAFE = Regex("[^A-Za-z0-9._-]")

    private val WINDOWS_RESERVED = buildSet {
        listOf("CON", "PRN", "AUX", "NUL").forEach { add(it) }
        (1..9).forEach {
            add("COM$it")
            add("LPT$it")
        }
    }

    /**
     * Extensions that make a cell a path rather than a sentence.
     *
     * Only used to decide WHICH cell a row names, so the list is deliberately generous. A file with
     * no extension and no directory is still resolvable when it exists; it simply cannot be guessed
     * from an arbitrary line of text.
     */
    private val FILE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff", "avif", "heic",
        "wav", "wave", "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "aiff", "aif", "wma", "amr",
        "mp4", "mov", "mkv", "webm", "avi", "m4v", "mpg", "mpeg", "wmv",
        "txt", "text", "md", "markdown", "json", "jsonl", "csv", "tsv", "parquet", "arrow",
        "log", "rst", "srt", "vtt",
        "obj", "ply", "glb", "gltf", "fbx", "dae", "stl", "usd", "usdz",
        "uasset", "umap", "unity", "tscn", "blend", "dds", "ktx2", "exr",
        "safetensors", "gguf", "onnx", "pt", "pth", "bin", "ckpt",
    )

    /** True when a cell names a web address rather than a file on this machine. */
    fun isUrl(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
    }

    /** A path as the CSV means it: quotes and backslashes removed, query strings dropped. */
    fun normalize(value: String): String {
        val trimmed = value.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return ""
        var path = (if (isUrl(trimmed)) trimmed.substringBefore('?').substringBefore('#') else trimmed)
            .replace('\\', '/')
        while (path.startsWith("./")) path = path.substring(2)
        return path
    }

    /** The file a path names, whichever platform wrote its separators. */
    fun fileName(value: String): String = normalize(value).substringAfterLast('/')

    /** True when a cell looks like it names a file rather than being a caption or a transcript. */
    fun looksLikeReference(value: String): Boolean {
        val path = normalize(value)
        if (path.isEmpty()) return false
        if (isUrl(path)) return true
        if (path.contains('/')) return true
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.isNotEmpty() && extension in FILE_EXTENSIONS
    }

    /**
     * The name a file is stored under in a dataset folder.
     *
     * Spaces and non-ASCII letters are preserved: a manifest's path has to keep naming the file after
     * the import, and turning `take 1.wav` into `take_1.wav` is exactly what makes it stop. Only the
     * characters Windows forbids are replaced, so a folder copied from either platform opens on the
     * other.
     */
    fun storedName(name: String): String {
        val leaf = name.replace('\\', '/').substringAfterLast('/')
        val safe = leaf.map { character ->
            when {
                character.code < 32 || character.code == 127 -> '_'
                UNSAFE.matches(character.toString()) -> '_'
                else -> character
            }
        }.joinToString("")
            .trim()
            .trimEnd('.', ' ')
            .take(160)
        val cleaned = safe.ifBlank { "file" }
        val stem = cleaned.substringBefore('.').uppercase(Locale.ROOT)
        return if (stem in WINDOWS_RESERVED) "_$cleaned" else cleaned
    }

    /**
     * The file [value] means, or null when it names nothing on this machine.
     *
     * [origin] is the folder the CSV came from; [fallback] is the dataset folder, where an earlier
     * import may already have put the file under this or another spelling.
     */
    fun resolve(
        value: String,
        origin: File?,
        fallback: File? = null,
        index: Index? = null,
    ): File? {
        val path = normalize(value)
        if (path.isEmpty()) return null
        if (isUrl(path)) return null

        // As written first, because an absolute path is the most exact answer there is.
        File(path).takeIf { it.isFile }?.let { return it }

        val roots = listOfNotNull(origin, fallback).distinctBy { canonical(it) }
        val leaf = fileName(path)
        val stored = storedName(leaf)
        for (root in roots) {
            File(root, path).takeIf { it.isFile }?.let { return it }
            File(root, leaf).takeIf { it.isFile }?.let { return it }
            if (stored != leaf) File(root, stored).takeIf { it.isFile }?.let { return it }
        }
        return index?.find(path)
    }

    /**
     * Every file below one or more dataset folders, keyed by relative path and by name.
     *
     * Built once per import or preparation rather than walking the folder for every row: a speech
     * manifest has thousands of rows, and a filesystem scan per row is a difference a user feels.
     */
    class Index(roots: List<File>) {
        private val byPath = HashMap<String, File>()
        private val byName = HashMap<String, File>()

        init {
            roots.filter { it.isDirectory }
                .distinctBy { canonical(it) }
                .forEach { root ->
                    root.walkTopDown()
                        .maxDepth(MAX_SEARCH_DEPTH)
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
                                .getOrDefault(file.name)
                            byPath.putIfAbsent(relative.lowercase(Locale.ROOT), file)
                            aliases(file.name).forEach { alias ->
                                byName.putIfAbsent(alias.lowercase(Locale.ROOT), file)
                            }
                        }
                }
        }

        /** The file [value] names below the indexed folders, or null. */
        fun find(value: String): File? {
            val path = normalize(value)
            if (path.isEmpty()) return null
            var relative = path
            while (relative.startsWith("/")) relative = relative.substring(1)
            relative = relative.lowercase(Locale.ROOT)
            // An absolute path from another machine can still be recognised by its tail:
            // C:/Users/ada/dataset/clips/a.wav -> users/ada/dataset/clips/a.wav -> ... -> clips/a.wav.
            var suffix = relative
            while (true) {
                byPath[suffix]?.let { return it }
                val slash = suffix.indexOf('/')
                if (slash < 0) break
                suffix = suffix.substring(slash + 1)
            }
            val leaf = fileName(path)
            return byName[leaf.lowercase(Locale.ROOT)]
                ?: byName[leaf.replace(LEGACY_UNSAFE, "_").lowercase(Locale.ROOT)]
        }
    }

    /**
     * Resolves cells against a folder that has been LISTED rather than opened.
     *
     * A folder chosen through Android's storage picker is not a `java.io.File`: it is a tree of
     * document URIs that has to be enumerated once, and its files do not exist on a path until they
     * are copied. The rules of [Index] still apply -- path, path suffix, then leaf name -- with a
     * relative path as the currency, so a CSV's `clips/take-1.wav` matches the listed
     * `clips/take-1.wav` (and a manifest written on another machine still matches by its tail).
     */
    class Listing(paths: List<String>) {
        private val byPath = HashMap<String, String>()
        private val byName = HashMap<String, String>()

        init {
            for (path in paths) {
                val normalized = normalize(path).trimStart('/')
                if (normalized.isEmpty()) continue
                byPath.putIfAbsent(normalized.lowercase(Locale.ROOT), path)
                aliases(normalized.substringAfterLast('/')).forEach { alias ->
                    byName.putIfAbsent(alias.lowercase(Locale.ROOT), path)
                }
            }
        }

        /** The listed path [value] names, in the listing's own spelling, or null. */
        fun find(value: String): String? {
            val path = normalize(value)
            if (path.isEmpty() || isUrl(path)) return null
            var suffix = path.trimStart('/').lowercase(Locale.ROOT)
            while (true) {
                byPath[suffix]?.let { return it }
                val slash = suffix.indexOf('/')
                if (slash < 0) break
                suffix = suffix.substring(slash + 1)
            }
            val leaf = fileName(path)
            return byName[leaf.lowercase(Locale.ROOT)]
                ?: byName[leaf.replace(LEGACY_UNSAFE, "_").lowercase(Locale.ROOT)]
        }
    }

    /** Every spelling a leaf name is indexed under: as written, as stored, and as an old import wrote it. */
    private fun aliases(name: String): List<String> = buildList {
        add(name)
        add(storedName(name))
        add(name.replace(LEGACY_UNSAFE, "_"))
    }.distinctBy { it.lowercase(Locale.ROOT) }

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
