package com.prism.launcher.storage.drive

import java.io.IOException
import java.io.InputStream

/** One file or directory on a drive Prism is reading itself. */
data class DriveEntry(
    val name: String,
    /** Absolute, '/'-separated, starting with '/'. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Epoch milliseconds, or 0 when the file system does not say. */
    val modified: Long,
    val isSymlink: Boolean = false,
)

/**
 * A file system Prism reads directly off a drive, read-only.
 *
 * Read-only on purpose: writing NTFS or ext4 from scratch is where file system drivers lose people's
 * data (journals, allocation bitmaps, NTFS's $LogFile), and the first job is getting at the files.
 */
interface DriveFileSystem {
    val typeId: String
    val typeName: String
    val label: String

    /** The entries of the directory at [path], without "." and "..". */
    fun list(path: String): List<DriveEntry>

    /** A file's contents. Throws for a directory or a missing path. */
    fun open(path: String): InputStream

    /** The entry at [path], or null. "/" is the root. */
    fun stat(path: String): DriveEntry? {
        val clean = normalise(path)
        if (clean == "/") return DriveEntry("", "/", true, 0, 0)
        val parent = clean.substringBeforeLast('/').ifEmpty { "/" }
        val name = clean.substringAfterLast('/')
        return list(parent).firstOrNull { it.name == name }
            ?: list(parent).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    companion object {
        fun normalise(path: String): String {
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            val stack = ArrayDeque<String>()
            parts.forEach { if (it == "..") stack.removeLastOrNull() else stack.addLast(it) }
            return "/" + stack.joinToString("/")
        }

        fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"
    }
}

/** Opens the right reader for a partition, or says why there is none. */
object DriveFileSystems {

    /** The file systems Prism can open directly; the rest are detected and named. */
    val readable = setOf("fat12", "fat16", "fat32", "exfat", "ntfs", "ext2", "ext3", "ext4")

    sealed interface Opened {
        data class Ready(val fileSystem: DriveFileSystem) : Opened
        data class Unsupported(val typeName: String, val reason: String) : Opened
        data class Unrecognised(val reason: String) : Opened
    }

    fun open(device: BlockDevice): Opened {
        val detected = FileSystemProbe.identify(device)
            ?: return Opened.Unrecognised("No file system Prism recognises is on this partition.")
        return runCatching {
            when (detected.id) {
                "fat12", "fat16", "fat32" -> Opened.Ready(FatFileSystem(device))
                "exfat" -> Opened.Ready(ExFatFileSystem(device))
                "ntfs" -> Opened.Ready(NtfsFileSystem(device))
                "ext2", "ext3", "ext4" -> Opened.Ready(Ext4FileSystem(device))
                else -> Opened.Unsupported(
                    detected.displayName,
                    "${detected.displayName} is detected but Prism cannot read it directly yet. On a " +
                        "computer, Prism mounts it through the operating system.",
                )
            }
        }.getOrElse {
            Opened.Unsupported(detected.displayName, "The ${detected.displayName} file system could not be read: ${it.message}")
        }
    }
}

/**
 * Streams [length] bytes produced chunk by chunk. [chunk] returns bytes starting EXACTLY at the
 * offset it is given (any length above zero).
 */
internal class ChunkedInputStream(
    private val length: Long,
    private val chunk: (offset: Long) -> ByteArray,
) : InputStream() {
    private var position = 0L
    private var buffer = ByteArray(0)
    private var bufferStart = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= length) return -1
        if (position < bufferStart || position >= bufferStart + buffer.size) {
            bufferStart = position
            buffer = chunk(position)
            if (buffer.isEmpty()) throw IOException("Read returned nothing at $position")
        }
        val within = (position - bufferStart).toInt()
        val count = minOf(len.toLong(), (buffer.size - within).toLong(), length - position).toInt()
        System.arraycopy(buffer, within, b, off, count)
        position += count
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = minOf(n, length - position).coerceAtLeast(0)
        position += skipped
        return skipped
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), length - position).toInt()
}
