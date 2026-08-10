package com.prism.launcher

import com.prism.core.PrismPlatform
import java.io.File

/**
 * File manager operations: clipboard, rename, delete, new folder, sorting.
 *
 * Ports `FileExplorerClipboard` and the operation half of `FileExplorerPageView`. All of it was
 * already `java.io.File` work on Android -- the Android-specific parts were the UI and the
 * storage-root enumeration, not these.
 *
 * THE DESTRUCTIVE OPERATIONS ARE THE POINT OF PUTTING THIS IN :core WITH TESTS. A file manager
 * that moves a directory into itself, silently overwrites a file the user meant to keep, or
 * deletes the wrong thing does damage that no undo recovers. Every one of those cases is guarded
 * below and pinned by a test rather than reasoned about.
 */
object FileOps {

    /** What a paste should do with the source. */
    enum class Mode { COPY, CUT }

    data class Clipboard(val files: List<File>, val mode: Mode)

    @Volatile
    private var clipboard: Clipboard? = null

    fun clipboard(): Clipboard? = clipboard

    fun copy(files: List<File>) {
        clipboard = if (files.isEmpty()) null else Clipboard(files.toList(), Mode.COPY)
    }

    fun cut(files: List<File>) {
        clipboard = if (files.isEmpty()) null else Clipboard(files.toList(), Mode.CUT)
    }

    fun clearClipboard() {
        clipboard = null
    }

    /** Outcome of a paste, so the UI can report what actually happened. */
    data class PasteResult(val pasted: List<File>, val skipped: List<String>) {
        val ok: Boolean get() = skipped.isEmpty()
    }

    /**
     * Pastes the clipboard into [target], clearing it afterwards if it was a cut.
     *
     * REFUSES TO PASTE A DIRECTORY INTO ITSELF OR ITS OWN DESCENDANT. Copying `/a` into `/a/b`
     * recurses forever, filling the disk; MOVING it there detaches the subtree from the
     * filesystem. Neither fails loudly on its own, which is why the check is here and not left to
     * the caller.
     */
    fun paste(target: File): PasteResult {
        val cb = clipboard ?: return PasteResult(emptyList(), listOf("Nothing to paste"))
        if (!target.isDirectory) return PasteResult(emptyList(), listOf("Not a directory"))

        val pasted = mutableListOf<File>()
        val skipped = mutableListOf<String>()

        for (source in cb.files) {
            if (!source.exists()) {
                skipped.add("${source.name}: no longer exists")
                continue
            }
            if (source.isDirectory && isInside(target, source)) {
                skipped.add("${source.name}: cannot paste a folder into itself")
                continue
            }
            val destination = uniqueName(target, source.name)
            try {
                if (cb.mode == Mode.CUT) {
                    // renameTo is atomic and instant WITHIN a filesystem and simply fails across
                    // one, which is why the copy-then-delete fallback exists rather than being
                    // the only path -- moving a large folder inside one drive should not rewrite
                    // every byte.
                    if (!source.renameTo(destination)) {
                        source.copyRecursively(destination, overwrite = false)
                        if (!source.deleteRecursively()) {
                            skipped.add("${source.name}: copied but the original could not be removed")
                        }
                    }
                } else {
                    if (source.isDirectory) {
                        source.copyRecursively(destination, overwrite = false)
                    } else {
                        source.copyTo(destination, overwrite = false)
                    }
                }
                pasted.add(destination)
            } catch (e: Exception) {
                skipped.add("${source.name}: ${e.message}")
            }
        }

        if (cb.mode == Mode.CUT) clipboard = null
        return PasteResult(pasted, skipped)
    }

    /** True when [candidate] is [ancestor] or lives underneath it. */
    fun isInside(candidate: File, ancestor: File): Boolean {
        val a = try { ancestor.canonicalFile } catch (e: Exception) { ancestor.absoluteFile }
        var c: File? = try { candidate.canonicalFile } catch (e: Exception) { candidate.absoluteFile }
        while (c != null) {
            if (c == a) return true
            c = c.parentFile
        }
        return false
    }

    /**
     * Renames in place.
     *
     * Refuses a name that already exists rather than overwriting -- a rename collision that
     * silently replaces the other file is data loss disguised as success.
     */
    fun rename(file: File, newName: String): File? {
        val clean = sanitize(newName)
        if (clean.isBlank()) return null
        val target = File(file.parentFile, clean)
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun delete(files: List<File>): PasteResult {
        val done = mutableListOf<File>()
        val failed = mutableListOf<String>()
        for (f in files) {
            val removed = try {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (e: Exception) {
                false
            }
            if (removed) done.add(f) else failed.add(f.name)
        }
        return PasteResult(done, failed)
    }

    fun newFolder(parent: File, name: String): File? {
        val clean = sanitize(name)
        if (clean.isBlank() || !parent.isDirectory) return null
        val target = uniqueName(parent, clean)
        return if (target.mkdirs()) target else null
    }

    // ── Sorting ──────────────────────────────────────────────────────────────────────────────

    enum class Sort(val label: String) {
        NAME("Name"),
        SIZE("Size"),
        MODIFIED("Date modified"),
        TYPE("Type"),
    }

    /**
     * Directories always first, then the chosen key.
     *
     * Directories-first regardless of sort because that is what every file manager on both
     * platforms does; a size sort that interleaves folders (whose reported size is meaningless)
     * among files reads as broken.
     */
    fun sorted(files: List<File>, sort: Sort, descending: Boolean = false): List<File> {
        val comparator: Comparator<File> = when (sort) {
            Sort.NAME -> compareBy { it.name.lowercase() }
            Sort.SIZE -> compareBy { it.length() }
            Sort.MODIFIED -> compareBy { it.lastModified() }
            Sort.TYPE -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
        }
        val directed = if (descending) comparator.reversed() else comparator
        return files.sortedWith(compareBy<File> { !it.isDirectory }.then(directed))
    }

    /** `report.pdf` -> `report (2).pdf` when taken. */
    fun uniqueName(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($n)$ext")
            n++
        }
        return candidate
    }

    /** The union of Windows' and Linux's illegal characters, so a name is legal on both. */
    fun sanitize(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").take(255)

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * The roots to show in the explorer.
     *
     * Android enumerated external storage and `StorageManager` volumes. The desktop equivalent is
     * `File.listRoots()` -- drive letters on Windows, `/` on Linux -- plus the user's home, which
     * is where anything they care about actually is.
     */
    fun roots(): List<File> {
        val out = LinkedHashSet<File>()
        System.getProperty("user.home")?.let { File(it).takeIf(File::isDirectory)?.let(out::add) }
        try {
            File.listRoots()?.filter { it.isDirectory }?.forEach(out::add)
        } catch (e: Exception) {
            PrismPlatform.log.warn("Prism/files", "Could not list roots: ${e.message}")
        }
        return out.toList()
    }
}
