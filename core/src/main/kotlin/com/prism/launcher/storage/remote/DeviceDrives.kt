package com.prism.launcher.storage.remote

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.storage.compress.CompressedFile
import com.prism.launcher.storage.compress.DriveCompression
import com.prism.launcher.storage.drive.DriveFileSystem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** What a drive physically is, which decides its icon and how it is described. */
enum class DriveKind(val id: String, val title: String) {
    /** A computer's own disk: C:\, D:\, or / on Linux. */
    INTERNAL("internal", "Internal drive"),
    /** A phone's shared storage (/storage/emulated/0). */
    PHONE("phone", "Internal storage"),
    SD_CARD("sd", "SD card"),
    USB("usb", "USB drive"),
    /** A hard disk or SSD in an external enclosure, or any other removable disk. */
    EXTERNAL("external", "External drive"),
    ;

    companion object {
        fun byId(id: String): DriveKind = entries.firstOrNull { it.id == id } ?: EXTERNAL
    }
}

/**
 * One drive a device offers to its trusted devices.
 *
 * [id] is stable for as long as the drive is attached and is URL-safe, because it is a path segment
 * of the drive server ("/token/id/...").
 */
data class DeviceDrive(
    val id: String,
    val name: String,
    val kind: DriveKind,
    val totalBytes: Long,
    val freeBytes: Long,
    val readOnly: Boolean,
    /** How the drive can be compressed and how it is now; null from a device that does not say. */
    val compression: CompressionInfo? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.id)
        put("total", totalBytes)
        put("free", freeBytes)
        put("ro", readOnly)
        compression?.let { put("cmp", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): DeviceDrive? {
            val id = safeId(json.optString("id"))
            if (id.isBlank()) return null
            return DeviceDrive(
                id = id,
                name = json.optString("name").ifBlank { id },
                kind = DriveKind.byId(json.optString("kind")),
                totalBytes = json.optLong("total", -1),
                freeBytes = json.optLong("free", -1),
                readOnly = json.optBoolean("ro", false),
                compression = json.optJSONObject("cmp")?.let(CompressionInfo::fromJson),
            )
        }

        /** Letters, digits, '-', '_' and '.', which survive every URL and UNC path unescaped. */
        fun safeId(raw: String): String =
            raw.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.') it else '-' }
                .joinToString("").trim('-', '.').take(64)
    }
}

/**
 * What a drive's owner says about compressing it, so another device can offer the same choices: the
 * owner decides, because only it can use its own file system's compression.
 */
data class CompressionInfo(
    val methods: List<Choice>,
    /** The method it is compressed with, or null when it is not. */
    val methodId: String?,
    /** Whether new files are compressed as they arrive. */
    val auto: Boolean,
    /** True when the file system does it (NTFS, btrfs), so every program sees ordinary files. */
    val native: Boolean,
    /** Why the drive cannot be compressed at all, or null. */
    val unavailable: String?,
) {
    data class Choice(val id: String, val label: String, val problem: String?)

    fun toJson(): JSONObject = JSONObject().apply {
        put("m", JSONArray().also { array ->
            methods.forEach { array.put(JSONObject().put("id", it.id).put("l", it.label).apply { it.problem?.let { p -> put("p", p) } }) }
        })
        methodId?.let { put("s", it) }
        put("a", auto)
        put("n", native)
        unavailable?.let { put("u", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): CompressionInfo {
            val array = json.optJSONArray("m") ?: JSONArray()
            return CompressionInfo(
                methods = (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { Choice(it.optString("id"), it.optString("l"), it.optString("p").ifBlank { null }) }
                }.filter { it.id.isNotBlank() },
                methodId = json.optString("s").ifBlank { null },
                auto = json.optBoolean("a"),
                native = json.optBoolean("n"),
                unavailable = json.optString("u").ifBlank { null },
            )
        }
    }
}

/** A file or folder on a drive, local or remote. [path] is '/'-separated and starts with '/'. */
data class DriveItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("path", path)
        put("dir", isDirectory)
        put("size", size)
        put("mod", modified)
    }

    companion object {
        fun fromJson(json: JSONObject): DriveItem = DriveItem(
            name = json.optString("name"),
            path = json.optString("path"),
            isDirectory = json.optBoolean("dir"),
            size = json.optLong("size"),
            modified = json.optLong("mod"),
        )
    }
}

/**
 * Reading and writing one drive. Implemented over a local folder, over Prism's own file-system
 * readers (a USB drive on a phone), and over the network (another device's drive).
 *
 * Every path is relative to the drive's root; nothing an implementation does may reach outside it.
 */
interface DriveAccess {
    val readOnly: Boolean

    /** The folder's entries, or throws when [path] is not a readable folder. */
    fun list(path: String): List<DriveItem>

    /** The entry at [path], or null when there is none. "/" is the root. */
    fun stat(path: String): DriveItem?

    /** The file's bytes from [offset] on. */
    fun open(path: String, offset: Long = 0): InputStream

    /** Writes [input] as the whole content of the file at [path]. [length] is -1 when unknown. */
    fun write(path: String, input: InputStream, length: Long)

    fun mkdir(path: String)

    /** Deletes a file, or a folder and everything in it. */
    fun delete(path: String)

    fun move(from: String, to: String)

    /** Free space in bytes, or -1. */
    fun freeBytes(): Long = -1
}

/** A drive that is a folder on this device: C:\, /storage/emulated/0, a mounted USB stick. */
class FileDriveAccess(private val root: File, override val readOnly: Boolean = false) : DriveAccess {

    private val canonicalRoot: String = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)

    /**
     * Whether files here may be in Prism's compressed format, which this reads and writes
     * transparently -- the part of NTFS compression Prism can do. A drive compressed by its own file
     * system (NTFS, btrfs) needs nothing here: the OS hands over ordinary bytes.
     */
    private fun prismCompressed(): Boolean = DriveCompression.markerCodec(root) != null

    /**
     * The file at [path], refusing anything that resolves outside the root.
     *
     * Canonicalised, so ".." segments and symbolic links pointing elsewhere are both caught: a drive
     * share that a crafted path could escape would be a share of the whole device.
     */
    fun resolve(path: String): File {
        val clean = DriveFileSystem.normalise(path)
        val file = if (clean == "/") root else File(root, clean.removePrefix("/"))
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val inside = canonical == canonicalRoot ||
            canonical.startsWith(canonicalRoot.trimEnd(File.separatorChar) + File.separator)
        if (!inside) throw IOException("Outside the drive: $path")
        return file
    }

    private fun item(file: File, path: String): DriveItem = DriveItem(
        name = if (path == "/") "" else file.name,
        path = path,
        isDirectory = file.isDirectory,
        size = when {
            !file.isFile -> 0
            prismCompressed() -> CompressedFile.logicalLength(file)
            else -> file.length()
        },
        modified = file.lastModified(),
    )

    override fun list(path: String): List<DriveItem> {
        val clean = DriveFileSystem.normalise(path)
        val dir = resolve(clean)
        val children = dir.listFiles() ?: throw IOException("Not a readable folder: $clean")
        return children
            .filterNot { it.name == DriveCompression.MARKER || it.name.endsWith(".prism-compressing") || it.name.endsWith(".prism-decompressing") }
            .map { item(it, DriveFileSystem.child(clean, it.name)) }
    }

    override fun stat(path: String): DriveItem? {
        val clean = DriveFileSystem.normalise(path)
        val file = runCatching { resolve(clean) }.getOrNull() ?: return null
        return if (file.exists()) item(file, clean) else null
    }

    override fun open(path: String, offset: Long): InputStream {
        val file = resolve(path)
        if (!file.isFile) throw IOException("Not a file: $path")
        if (prismCompressed()) return CompressedFile.open(file, offset)
        return file.inputStream().also { stream -> if (offset > 0) skipFully(stream, offset) }
    }

    override fun write(path: String, input: InputStream, length: Long) {
        check(!readOnly) { "This drive is read-only." }
        val file = resolve(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output -> copyLimited(input, output, length) }
        // A file arriving on a drive set to compress new files is compressed, as NTFS does with a
        // compressed folder -- after the write, so the upload is not slowed down by it.
        DriveCompression.autoCodec(root)?.let { codec ->
            compressor.execute { runCatching { CompressedFile.compress(file, codec) } }
        }
    }

    override fun mkdir(path: String) {
        check(!readOnly) { "This drive is read-only." }
        val dir = resolve(path)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $path")
    }

    override fun delete(path: String) {
        check(!readOnly) { "This drive is read-only." }
        val target = resolve(path)
        if (DriveFileSystem.normalise(path) == "/") throw IOException("The drive itself cannot be deleted.")
        if (!target.exists()) return
        if (!target.deleteRecursively()) throw IOException("Could not delete $path")
    }

    override fun move(from: String, to: String) {
        check(!readOnly) { "This drive is read-only." }
        val source = resolve(from)
        val target = resolve(to)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }

    override fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(-1)

    private companion object {
        val compressor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "prism-compress-on-write").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}

/** A drive Prism reads itself (a USB partition on a phone). Read-only, as those readers are. */
class ReaderDriveAccess(private val fs: DriveFileSystem) : DriveAccess {
    override val readOnly: Boolean = true

    override fun list(path: String): List<DriveItem> = fs.list(DriveFileSystem.normalise(path)).map {
        DriveItem(it.name, it.path, it.isDirectory, it.size, it.modified)
    }

    override fun stat(path: String): DriveItem? = runCatching { fs.stat(path) }.getOrNull()?.let {
        DriveItem(it.name, DriveFileSystem.normalise(path), it.isDirectory, it.size, it.modified)
    }

    override fun open(path: String, offset: Long): InputStream =
        fs.open(DriveFileSystem.normalise(path)).also { if (offset > 0) skipFully(it, offset) }

    override fun write(path: String, input: InputStream, length: Long) = throw IOException("This drive is read-only.")
    override fun mkdir(path: String) = throw IOException("This drive is read-only.")
    override fun delete(path: String) = throw IOException("This drive is read-only.")
    override fun move(from: String, to: String) = throw IOException("This drive is read-only.")
}

/**
 * The drives THIS device offers, supplied by the platform: the phone lists internal storage, SD
 * cards and USB drives; a computer lists its disks and whatever is plugged in.
 */
object LocalDrives {

    /**
     * [root] is the drive's folder on this device, when the system mounted it; null for a drive
     * Prism reads itself, which can only be read and so cannot be compressed.
     */
    class Offered(val drive: DeviceDrive, val access: DriveAccess, val root: File? = null)

    /** Set by the platform at startup. Called off the main thread, and may be slow. */
    @Volatile
    var provider: () -> List<Offered> = { emptyList() }

    fun list(): List<Offered> = runCatching { provider() }.getOrDefault(emptyList())
}

internal fun skipFully(stream: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = stream.skip(left)
        if (skipped <= 0) {
            if (stream.read() < 0) return
            left--
        } else {
            left -= skipped
        }
    }
}

/** Copies [length] bytes, or everything when [length] is negative. */
internal fun copyLimited(input: InputStream, output: java.io.OutputStream, length: Long): Long {
    val buffer = ByteArray(256 * 1024)
    var total = 0L
    while (length < 0 || total < length) {
        val want = if (length < 0) buffer.size else minOf(buffer.size.toLong(), length - total).toInt()
        val read = input.read(buffer, 0, want)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }
    if (length >= 0 && total < length) throw IOException("The upload ended early ($total of $length bytes)")
    return total
}
