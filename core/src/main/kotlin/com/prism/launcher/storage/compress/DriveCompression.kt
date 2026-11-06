package com.prism.launcher.storage.compress

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Compressing a drive the way Windows does: every file stays where it is, under its own name, and
 * opens exactly as before -- it just takes less space. Nothing becomes an archive.
 *
 * What Windows does when "Compress this drive to save disk space" is ticked, and what this copies:
 *
 *  - Each file is compressed IN PLACE, in 64 KB compression units, keeping a unit as it was when
 *    compressing would not free a whole 4 KB cluster (so an incompressible file costs nothing).
 *  - Reading decompresses on the fly, unit by unit, so reading part of a large file stays cheap.
 *  - The drive's root and every folder are MARKED, and a file that arrives later is compressed
 *    too. Unmarking decompresses everything back.
 *  - LZNT1 is the default algorithm.
 *
 * On Windows and Linux this is not an imitation at all: [methods] there are the file system's own
 * (NTFS's attribute and WOF through compact.exe, btrfs's per-file compression), so every program
 * sees ordinary files. Everywhere else -- a phone's storage, an SD card -- it is Prism's
 * [CompressedFile] with the [BlockCodecs] and ModelCraft networks as the algorithms.
 */
object DriveCompression {

    private const val TAG = "PrismCompress"
    private const val PREFS = "prism_drive_compression"

    /** The marker at the root of a drive compressed in Prism's format: NTFS's drive attribute. */
    const val MARKER = ".prism-compression"

    /** One way to compress the files of a drive. */
    abstract class Method(val id: String, val label: String) {
        /** True when the file system itself decompresses, for every program (NTFS, btrfs). */
        open val native: Boolean = false

        /** How many files [compress] and [decompress] take at once. */
        open val batch: Int = 1

        /** Why this method cannot be used right now, or null. */
        open fun unavailable(): String? = null

        /** Compresses [files]; returns the bytes freed, or -1 when that cannot be measured. */
        abstract fun compress(files: List<File>, cancelled: () -> Boolean): Long

        /** Restores [files] to ordinary files. Returns the bytes the drive gave back, or -1. */
        abstract fun decompress(files: List<File>, cancelled: () -> Boolean): Long

        /** Marks or unmarks [folders] so the file system compresses new files in them itself. */
        open fun markFolders(folders: List<File>, on: Boolean) = Unit
    }

    /** Prism's own format with one [Codec]. */
    class PrismMethod(val codec: Codec) : Method(codec.id, codec.label) {
        override fun unavailable(): String? = codec.unavailable()
        override fun compress(files: List<File>, cancelled: () -> Boolean): Long =
            files.sumOf { file -> runCatching { CompressedFile.compress(file, codec, cancelled) }.getOrElse { 0L } }

        override fun decompress(files: List<File>, cancelled: () -> Boolean): Long = files.sumOf { file ->
            val before = file.length()
            if (runCatching { CompressedFile.decompress(file, cancelled) }.getOrDefault(false)) before - file.length() else 0L
        }
    }

    /**
     * The methods a drive at [root] offers. Set by a computer to its file system's own; the default
     * -- a phone -- is Prism's format with every codec.
     */
    @Volatile
    var methods: (root: File) -> List<Method> = { _ -> BlockCodecs.all().map(::PrismMethod) }

    /**
     * Files and folders under [root] that are never touched. Set by the platform: a phone leaves
     * what other apps read straight from storage, a computer its operating system.
     */
    @Volatile
    var excluded: (root: File, file: File) -> Boolean = { _, _ -> false }

    // ── What each drive is set to ─────────────────────────────────────────

    /** What a drive is set to: compressed with [methodId] or not, and whether new files are too. */
    data class Setting(val methodId: String?, val auto: Boolean) {
        val compressed: Boolean get() = methodId != null
    }

    fun setting(driveId: String): Setting {
        val raw = PrismPlatform.host.prefs(PREFS).getString(driveId, null).orEmpty()
        if (raw.isBlank()) return Setting(null, false)
        return runCatching { JSONObject(raw) }.getOrNull()
            ?.let { Setting(it.optString("method").ifBlank { null }, it.optBoolean("auto")) }
            ?: Setting(null, false)
    }

    fun save(driveId: String, setting: Setting) {
        val value = if (setting.methodId == null) "" else JSONObject().put("method", setting.methodId).put("auto", setting.auto).toString()
        PrismPlatform.host.prefs(PREFS).edit().putString(driveId, value).apply()
    }

    /** The marker's codec at [root], when the drive is (or is being) compressed in Prism's format. */
    fun markerCodec(root: File): String? = markers.get(root)

    private object markers {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()
        fun get(root: File): String? {
            val key = root.path
            val now = System.currentTimeMillis()
            cache[key]?.let { (at, value) -> if (now - at < 5_000) return value }
            val value = runCatching {
                File(root, MARKER).takeIf { it.isFile }?.readText()?.let { JSONObject(it).optString("codec") }?.ifBlank { null }
            }.getOrNull()
            cache[key] = now to value
            return value
        }

        fun forget(root: File) = cache.remove(root.path)
    }

    private fun writeMarker(root: File, codecId: String, auto: Boolean) {
        runCatching { File(root, MARKER).writeText(JSONObject().put("codec", codecId).put("auto", auto).toString()) }
        markers.forget(root)
    }

    private fun clearMarker(root: File) {
        File(root, MARKER).delete()
        markers.forget(root)
    }

    /** Whether new files at [root] are to be compressed as they arrive, and with what. */
    fun autoCodec(root: File): Codec? = runCatching {
        val marker = File(root, MARKER).takeIf { it.isFile } ?: return null
        val json = JSONObject(marker.readText())
        if (!json.optBoolean("auto")) return null
        BlockCodecs.byId(json.optString("codec"))
    }.getOrNull()

    // ── Running ───────────────────────────────────────────────────────────

    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val savedBytes: Long,
        val current: String,
        /** Still finding files: the totals are growing. */
        val scanning: Boolean,
    )

    data class Result(val files: Int, val savedBytes: Long, val stopped: Boolean)

    /** Formats nobody gains from compressing again: the work is spent and nothing is saved. */
    private val ALREADY_COMPRESSED = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp3", "aac", "m4a", "ogg", "opus",
        "flac", "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "zip", "gz", "tgz", "bz2", "xz", "7z",
        "rar", "zst", "lz4", "apk", "aab", "jar", "docx", "xlsx", "pptx", "odt", "epub", "pdf", "woff2",
        "cab", "msi", "vhdx", "vmdk", "iso", "prvc",
    )

    private class Found(val files: List<File>, val folders: List<File>, val bytes: Long)

    private fun find(
        root: File,
        compressing: Boolean,
        changedSince: Long,
        cancelled: () -> Boolean,
        onFound: (files: Int, bytes: Long) -> Unit,
    ): Found {
        val files = ArrayList<File>()
        val folders = ArrayList<File>()
        var bytes = 0L
        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelled()) return FileVisitResult.TERMINATE
                val folder = dir.toFile()
                if (dir != rootPath && (attrs.isSymbolicLink || attrs.isOther || excluded(root, folder))) return FileVisitResult.SKIP_SUBTREE
                folders += folder
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || attrs.size() <= 0) return FileVisitResult.CONTINUE
                val file = path.toFile()
                val name = file.name
                if (name == MARKER || name.endsWith(".prism-compressing") || name.endsWith(".prism-decompressing")) return FileVisitResult.CONTINUE
                if (excluded(root, file)) return FileVisitResult.CONTINUE
                if (compressing) {
                    if (name.substringAfterLast('.', "").lowercase() in ALREADY_COMPRESSED) return FileVisitResult.CONTINUE
                    if (changedSince > 0 && attrs.lastModifiedTime().toMillis() < changedSince) return FileVisitResult.CONTINUE
                }
                files += file
                bytes += attrs.size()
                if (files.size % 500 == 0) onFound(files.size, bytes)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return Found(files, folders, bytes)
    }

    /**
     * Compresses (or, with [compressing] false, decompresses) the drive at [root] with [method].
     *
     * [changedSince] limits a compression to files modified since then: the periodic pass that
     * keeps new files compressed. [cancelled] is polled between batches and inside large files;
     * stopping leaves every file whole, some compressed and some not, exactly like Windows.
     */
    fun run(
        root: File,
        method: Method,
        compressing: Boolean,
        auto: Boolean = true,
        changedSince: Long = 0,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        NeuralCompressors.newRun()
        onProgress(Progress(0, 0, 0, 0, 0, "", scanning = true))
        val found = find(root, compressing, changedSince, cancelled) { files, bytes ->
            onProgress(Progress(0, files, 0, bytes, 0, "", scanning = true))
        }
        if (cancelled()) return Result(0, 0, stopped = true)
        val prismFormat = method is PrismMethod
        // Marked first, as Windows sets the attribute before it walks: whatever arrives while this
        // runs is compressed as it is written.
        if (compressing) {
            if (prismFormat) writeMarker(root, method.id, auto) else runCatching { method.markFolders(found.folders, auto) }
        } else if (!prismFormat) {
            runCatching { method.markFolders(found.folders, false) }
        }
        var done = 0
        var bytesDone = 0L
        var saved = 0L
        onProgress(Progress(0, found.files.size, 0, found.bytes, 0, "", scanning = false))
        found.files.chunked(method.batch.coerceAtLeast(1)).forEach { batch ->
            if (cancelled()) return Result(done, saved, stopped = true)
            val batchBytes = batch.sumOf { it.length().coerceAtLeast(0) }
            val freed = runCatching {
                if (compressing) method.compress(batch, cancelled) else method.decompress(batch, cancelled)
            }.onFailure { PrismPlatform.log.warn(TAG, "A batch failed: ${it.message}") }.getOrDefault(0L)
            if (freed > 0) saved += freed
            done += batch.size
            bytesDone = (bytesDone + batchBytes).coerceAtMost(found.bytes)
            onProgress(Progress(done, found.files.size, bytesDone, found.bytes, saved, batch.last().name, scanning = false))
        }
        if (!compressing && prismFormat) clearMarker(root)
        return Result(done, saved, stopped = false)
    }

    fun human(bytes: Long): String = when {
        bytes < 0 -> "an unknown amount"
        bytes >= 1L shl 40 -> "%.1f TB".format(bytes / (1L shl 40).toDouble())
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
