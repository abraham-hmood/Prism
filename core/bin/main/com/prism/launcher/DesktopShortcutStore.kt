package com.prism.launcher

import com.prism.core.PrismPlatform

/**
 * One thing sitting on a desktop page: an app, a file, a directory, a folder, or a remote share.
 *
 * THE SERIALIZED FORM IS FROZEN. Every branch of [serialize] produces exactly the bytes the
 * Android build has been writing into `desktop_prefs` since the feature shipped, and
 * [deserialize] still accepts all of them including the pre-versioning legacy form. A user's home
 * screen layout is stored in this format and there is no migration -- change a separator or a tag
 * and everyone's desktop comes back empty.
 *
 * WHY [App] HOLDS A STRING RATHER THAN A ComponentName. `ComponentName` is an Android class, so
 * it cannot cross into :core. It also was not carrying its weight: what actually gets stored is
 * `flattenToString()`'s output, `"package/fully.qualified.Class"`, and Windows and Linux have
 * their own equally string-shaped identity for an installed application -- a `.desktop` path, a
 * `.lnk` path, an AppUserModelID -- which is precisely what `AppEntry.id` already is. So [appId]
 * is the portable spelling of the same fact, and the on-disk bytes do not change at all.
 *
 * The Android build gets its `ComponentName` back through an extension property in :app, so the
 * ~15 call sites that hand one to PackageManager are untouched.
 */
sealed class DesktopItem {

    /** [appId] is `AppEntry.id`: a flattened ComponentName on Android, a path elsewhere. */
    data class App(val appId: String) : DesktopItem()
    data class FileRef(val absolutePath: String) : DesktopItem()
    data class DirectoryRef(val absolutePath: String, val name: String) : DesktopItem()
    data class Folder(val name: String, val folderId: String) : DesktopItem()
    data class NetworkedFolder(val url: String, val name: String, val type: String) : DesktopItem()

    fun serialize(): String {
        return when (this) {
            is App -> "app|$appId"
            is FileRef -> "file|$absolutePath"
            is DirectoryRef -> "dir|$absolutePath|$name"
            is Folder -> "folder|$name|$folderId"
            is NetworkedFolder -> "network|$url|$name|$type"
        }
    }

    companion object {
        fun deserialize(raw: String): DesktopItem? {
            val parts = raw.split("|")
            return when (parts.getOrNull(0)) {
                "app" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { App(it) }
                "file" -> parts.getOrNull(1)?.let { FileRef(it) }
                "dir" -> parts.getOrNull(1)?.let { DirectoryRef(it, parts.getOrNull(2) ?: "Folder") }
                "folder" -> parts.getOrNull(1)?.let { Folder(it, parts.getOrNull(2) ?: "unknown") }
                "network" -> parts.getOrNull(1)?.let {
                    NetworkedFolder(it, parts.getOrNull(2) ?: "Networked Folder", parts.getOrNull(3) ?: "ftp")
                }
                else -> {
                    // Legacy migration: before the tagged format, a cell was a bare flattened
                    // ComponentName. `ComponentName.unflattenFromString` used to do the
                    // validating; the portable equivalent is that it looks like "a/b", which is
                    // exactly what that function accepted and rejected.
                    val slash = raw.indexOf('/')
                    if (slash > 0 && slash < raw.length - 1) App(raw) else null
                }
            }
        }
    }
}

/**
 * The grid contents of one desktop page.
 *
 * Store name `desktop_prefs` and key `cells_page_N` are both part of the on-disk contract; see
 * [DesktopItem] for why that matters more here than it looks.
 */
class DesktopShortcutStore(private val pageIndex: Int = 1) {

    private val prefs = PrismPlatform.host.prefs(PREFS)

    fun readGrid(size: Int): MutableList<DesktopItem?> {
        val key = "cells_page_$pageIndex"
        val raw = prefs.getString(key, null) ?: return MutableList(size) { null }
        val items = raw.split(";;").map { if (it == "null") null else DesktopItem.deserialize(it) }
        return items.take(size).toMutableList()
    }

    fun writeGrid(grid: List<DesktopItem?>) {
        val key = "cells_page_$pageIndex"
        val raw = grid.joinToString(";;") { it?.serialize() ?: "null" }
        prefs.edit().putString(key, raw).apply()
    }

    companion object {
        const val PREFS = "desktop_prefs"

        /** How many cells a desktop page has. */
        const val GRID_SIZE = 24

        /** Adds an item specifically to the given desktop page index. */
        fun add(item: DesktopItem, pageIndex: Int) {
            val store = DesktopShortcutStore(pageIndex)
            val grid = store.readGrid(GRID_SIZE)
            val emptyIdx = grid.indexOfFirst { it == null }
            if (emptyIdx != -1) {
                grid[emptyIdx] = item
                store.writeGrid(grid)
            }
        }
    }
}
