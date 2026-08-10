package com.prism.launcher

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Desktop folders, which are real directories rather than a list in preferences.
 *
 * THIS IS ANDROID'S DESIGN, KEPT. `FolderPopupView` resolves a folder to
 * `filesDir/Desktop/Folders/<folderId>` and lists it, or treats the id as an absolute path when
 * it starts with `/`. Both branches are preserved here because both are load-bearing: the first
 * is a folder Prism created, the second is a real directory the user dropped onto the desktop,
 * and a user who dropped `~/Documents` onto their home screen expects to see what is in
 * `~/Documents` right now, not a snapshot of it.
 *
 * Keeping directories rather than switching to a stored list has a property worth naming: folder
 * contents cannot desynchronize from the filesystem, because they ARE the filesystem. Nested
 * navigation and rename are then just `listFiles` and `File.renameTo`, which is why this file is
 * short.
 */
object DesktopFolders {

    /** Where Prism-created folders live. Mirrors Android's `filesDir/Desktop/Folders`. */
    fun root(): File = File(PrismPlatform.host.dataDir(), "Desktop/Folders")

    /**
     * Resolves a folder id to its directory.
     *
     * An id starting with a path separator -- or, on Windows, looking like `C:\...` -- is an
     * absolute path to somewhere on the real filesystem. Anything else names a folder Prism owns.
     */
    fun resolve(folderId: String): File =
        if (isAbsolute(folderId)) File(folderId) else File(root(), folderId)

    private fun isAbsolute(id: String): Boolean =
        id.startsWith("/") || id.startsWith("\\") || (id.length > 2 && id[1] == ':')

    /** Creates a new empty folder and returns its id. */
    fun create(name: String): String {
        val id = sanitize(name).ifBlank { "folder" } + "-" + System.currentTimeMillis().toString(36)
        resolve(id).mkdirs()
        return id
    }

    /**
     * What is in a folder, directories first then files, each group alphabetical and
     * case-insensitive.
     *
     * Directories first because that is what every file manager on both platforms does, and
     * case-insensitive because otherwise `Zebra` sorts before `apple` on Linux and users read
     * that as a bug.
     */
    fun list(folderId: String): List<File> {
        val dir = resolve(folderId)
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filterNot { it.isHidden }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Renames a folder.
     *
     * Only Prism-owned folders can be renamed: renaming by absolute path would rename the user's
     * actual directory somewhere else on their disk, which is not what "rename this icon" means
     * on a home screen. Returns the new id, or null if the rename was refused or failed.
     */
    fun rename(folderId: String, newName: String): String? {
        if (isAbsolute(folderId)) return null
        val from = resolve(folderId)
        if (!from.isDirectory) return null

        val newId = sanitize(newName).ifBlank { return null }
        if (newId == folderId) return folderId

        val to = File(root(), newId)
        if (to.exists()) return null
        return if (from.renameTo(to)) newId else null
    }

    /** Deletes a Prism-owned folder and everything in it. Refuses absolute paths, as [rename] does. */
    fun delete(folderId: String): Boolean {
        if (isAbsolute(folderId)) return false
        return resolve(folderId).deleteRecursively()
    }

    /**
     * Copies a file into a folder, returning the destination.
     *
     * Copy rather than move: the source is somewhere in the user's real filesystem, and a home
     * screen that silently relocates files out of Downloads when you drag them onto a folder is
     * a home screen that loses people's data.
     */
    fun addFile(folderId: String, source: File): File? {
        val dir = resolve(folderId)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return try {
            val target = uniqueName(dir, source.name)
            source.copyTo(target, overwrite = false)
            target
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/folders", "Could not copy ${source.name}", e)
            null
        }
    }

    /** `report.pdf` -> `report (2).pdf` when taken, as both platforms' file managers do. */
    private fun uniqueName(dir: File, name: String): File {
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

    /**
     * Strips what neither Windows nor Linux allows in a file name.
     *
     * The union of both platforms' rules rather than the current one's, so a folder created on
     * Linux and synced to Windows still has a legal name.
     */
    private fun sanitize(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").take(120)
}
