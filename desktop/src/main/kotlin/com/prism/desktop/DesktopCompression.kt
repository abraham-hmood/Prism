package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.storage.compress.AutoCompression
import com.prism.launcher.storage.compress.CompressionJobs
import com.prism.launcher.storage.compress.DriveCompression
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Drive compression on a computer: the file system's own, so every program keeps seeing ordinary
 * files.
 *
 * ## Windows
 *
 * Exactly what Windows does, through `compact.exe`, the tool behind the drive's "Compress this
 * drive to save disk space" box:
 *
 *  - LZNT1 is NTFS compression: the compressed attribute on each file AND each folder, so NTFS
 *    compresses anything saved into them from then on, itself.
 *  - XPRESS 4K/8K/16K and LZX are Windows Overlay Filter compression (what CompactOS uses): better
 *    ratios and faster reads, but per file -- a file that is rewritten comes back uncompressed and
 *    folders have no attribute, so Prism's periodic pass ([AutoCompression]) picks new files up.
 *
 * Only NTFS can do either; a FAT32 or exFAT stick cannot be compressed, as in Windows itself.
 *
 * ## Linux
 *
 * btrfs compresses transparently: `btrfs filesystem defragment -c` rewrites existing files
 * compressed and the `compression` property makes a folder compress what is written into it.
 * ext4, XFS and the rest have no such feature, and a drive on them is not offered.
 */
object DesktopCompression {

    private const val TAG = "PrismDesktopCompress"
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val windows = os.contains("win")
    private val linux = os.contains("linux")

    fun install() {
        DriveCompression.methods = ::methodsFor
        DriveCompression.excluded = ::isExcluded
        CompressionJobs.addListener { job ->
            if (!job.visible) return@addListener
            val notifier = PrismPlatform.notifier as? DesktopNotifier
            if (job.active) {
                notifier?.status(job.headline() + " · " + (job.fraction * 100).toInt() + "%")
            } else {
                notifier?.status(null)
                PrismPlatform.notifier.notify("drive_compression", job.key.hashCode(), job.headline(), job.detail())
            }
        }
        AutoCompression.start()
    }

    private fun fileSystemOf(root: File): String = runCatching { Files.getFileStore(root.toPath()).type() }.getOrDefault("")

    private fun methodsFor(root: File): List<DriveCompression.Method> {
        val type = fileSystemOf(root)
        return when {
            windows && type.equals("NTFS", ignoreCase = true) -> listOf(
                Compact("lznt1", "LZNT1 -- NTFS compression (what Windows' own checkbox does)", null),
                Compact("xpress4k", "XPRESS 4K -- fastest (Windows Overlay Filter)", "xpress4k"),
                Compact("xpress8k", "XPRESS 8K -- balanced (Windows Overlay Filter)", "xpress8k"),
                Compact("xpress16k", "XPRESS 16K -- smaller (Windows Overlay Filter)", "xpress16k"),
                Compact("lzx", "LZX -- smallest, slowest (Windows Overlay Filter)", "lzx"),
            )
            windows -> listOf(
                Unsupported(
                    "${root.path} is ${type.ifBlank { "not NTFS" }}. Windows can only compress NTFS drives; " +
                        "compressing this one in Prism's own format would leave files no other program could open.",
                ),
            )
            linux && type.equals("btrfs", ignoreCase = true) -> listOf(
                Btrfs("zstd", "Zstandard -- btrfs (balanced, the usual choice)"),
                Btrfs("lzo", "LZO -- btrfs (fastest)"),
                Btrfs("zlib", "zlib -- btrfs (smallest)"),
            )
            else -> listOf(
                Unsupported(
                    "${root.path} is ${type.ifBlank { "a file system" }} without built-in compression. Only btrfs " +
                        "compresses transparently on Linux, and Prism's own format would leave files other programs cannot open.",
                ),
            )
        }
    }

    /** The operating system's own files, page file and recycle bin: never touched. */
    private fun isExcluded(root: File, file: File): Boolean {
        val relative = runCatching { file.relativeTo(root).path }.getOrDefault("").replace('\\', '/')
        if (relative.isEmpty()) return false
        val top = relative.substringBefore('/').lowercase()
        if (windows) {
            if (top in setOf("system volume information", "\$recycle.bin", "recovery", "\$winreagent", "\$windows.~bt", "\$windows.~ws")) return true
            if (relative.lowercase() in setOf("pagefile.sys", "hiberfil.sys", "swapfile.sys", "dumpstack.log.tmp")) return true
            val systemDrive = System.getenv("SystemDrive").orEmpty().ifBlank { "C:" }
            if (root.path.take(2).equals(systemDrive, ignoreCase = true) && (top == "windows" || relative.lowercase() == "bootmgr")) return true
            return false
        }
        if (root.path == "/") {
            if (top in setOf("proc", "sys", "dev", "run", "tmp", "boot", "snap", "lost+found", "swapfile", "swap.img")) return true
            if (relative.startsWith("var/lib/docker") || relative.startsWith("var/lib/libvirt/images")) return true
        }
        return false
    }

    private class Unsupported(private val reason: String) : DriveCompression.Method("none", "Not available on this drive") {
        override val native: Boolean = true
        override fun unavailable(): String = reason
        override fun compress(files: List<File>, cancelled: () -> Boolean): Long = 0
        override fun decompress(files: List<File>, cancelled: () -> Boolean): Long = 0
    }

    // ── Windows ─────────────────────────────────────────────────────────────

    @Suppress("FunctionName") // Kernel32's own spelling: JNA binds by name.
    private interface Kernel32 : StdCallLibrary {
        fun GetCompressedFileSizeW(name: WString, high: IntByReference): Int
    }

    private val kernel32: Kernel32? by lazy {
        if (!windows) null else runCatching { Native.load("kernel32", Kernel32::class.java, W32APIOptions.UNICODE_OPTIONS) }.getOrNull()
    }

    /** What [file] takes on disk now: its compressed size when NTFS or WOF has compressed it. */
    private fun sizeOnDisk(file: File): Long {
        val api = kernel32 ?: return file.length()
        val high = IntByReference()
        val low = api.GetCompressedFileSizeW(WString(file.absolutePath), high)
        if (low == -1 && high.value == 0) return file.length()
        return (high.value.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
    }

    /** compact.exe over one batch at a time, kept under Windows' 32 KB command line. */
    private class Compact(id: String, label: String, private val wof: String?) : DriveCompression.Method(id, label) {
        override val native: Boolean = true
        override val batch: Int = 400

        override fun unavailable(): String? = if (!File(System.getenv("SystemRoot").orEmpty().ifBlank { "C:\\Windows" }, "System32\\compact.exe").isFile) {
            "compact.exe is missing from this copy of Windows."
        } else null

        override fun compress(files: List<File>, cancelled: () -> Boolean): Long {
            val before = files.associateWith(::sizeOnDisk)
            val flags = listOf("/c", "/i", "/q", "/a") + listOfNotNull(wof?.let { "/exe:$it" })
            runOver(files, flags, cancelled)
            return files.sumOf { (before.getValue(it) - sizeOnDisk(it)).coerceAtLeast(0) }
        }

        override fun decompress(files: List<File>, cancelled: () -> Boolean): Long {
            val before = files.sumOf(::sizeOnDisk)
            // Both kinds, whichever the drive was compressed with: the attribute, then WOF.
            runOver(files, listOf("/u", "/i", "/q", "/a"), cancelled)
            runOver(files, listOf("/u", "/exe", "/i", "/q", "/a"), cancelled)
            return before - files.sumOf(::sizeOnDisk)
        }

        override fun markFolders(folders: List<File>, on: Boolean) {
            // Only NTFS compression has a folder attribute; WOF is per file.
            if (on && wof != null) return
            runOver(folders, listOf(if (on) "/c" else "/u", "/i", "/q", "/a"), { false })
        }

        private fun runOver(paths: List<File>, flags: List<String>, cancelled: () -> Boolean) {
            val group = ArrayList<String>()
            var length = 0
            fun flush() {
                if (group.isEmpty()) return
                run(listOf("compact") + flags + group)
                group.clear()
                length = 0
            }
            paths.forEach { file ->
                if (cancelled()) return
                val path = file.absolutePath
                if (length + path.length + 3 > 28_000) flush()
                group += path
                length += path.length + 3
            }
            flush()
        }
    }

    // ── Linux ───────────────────────────────────────────────────────────────

    private class Btrfs(private val algorithm: String, label: String) : DriveCompression.Method("btrfs-$algorithm", label) {
        override val native: Boolean = true
        override val batch: Int = 200

        override fun unavailable(): String? =
            if (runCatching { run(listOf("btrfs", "--version")) }.getOrDefault("").isBlank()) "btrfs-progs is not installed." else null

        // btrfs does not say per file what it saved without compsize, which few machines have.
        override fun compress(files: List<File>, cancelled: () -> Boolean): Long {
            if (!cancelled()) run(listOf("btrfs", "filesystem", "defragment", "-c$algorithm") + files.map { it.absolutePath })
            return -1
        }

        override fun decompress(files: List<File>, cancelled: () -> Boolean): Long {
            // Rewriting with the folder's compression cleared stores the data plainly again.
            if (!cancelled()) run(listOf("btrfs", "filesystem", "defragment") + files.map { it.absolutePath })
            return -1
        }

        override fun markFolders(folders: List<File>, on: Boolean) {
            folders.forEach { folder ->
                runCatching { run(listOf("btrfs", "property", "set", folder.absolutePath, "compression", if (on) algorithm else "")) }
            }
        }
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            PrismPlatform.log.warn(TAG, "${command.first()} took too long and was stopped")
        }
        return output
    }
}
