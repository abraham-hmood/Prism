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

    /**
     * A home-screen widget, held at its top-left cell.
     *
     * [appWidgetId] is the id the platform's AppWidgetHost allocated. It is the widget's whole
     * identity -- the provider, its configuration and its state all hang off it -- so losing it
     * orphans a widget that keeps running and can never be reached again. [deleteAppWidgetId] on
     * removal is what prevents that.
     *
     * The spans are in grid cells. A widget is the only item here that is bigger than one cell, and
     * the cells it covers are held by [Occupied] entries so that the grid stays a flat list of cells
     * with exactly one owner each.
     */
    data class Widget(val appWidgetId: Int, val spanX: Int, val spanY: Int) : DesktopItem()

    /**
     * A widget supplied by a Prism plugin. PHASE 106.
     *
     * SEPARATE FROM [Widget] BECAUSE IT IS A DIFFERENT THING WITH A DIFFERENT IDENTITY. An AppWidget is
     * identified by an integer the platform's AppWidgetHost allocated, and losing that integer orphans a
     * running widget forever. A plugin widget is identified by its CLASS NAME, because that is what the
     * plugin loader can find it by -- there is no host allocating ids, and there is nothing to orphan: the
     * plugin either loads or it does not.
     *
     * Merging the two into one item with a nullable id would have meant every site that touches a widget
     * asking which kind it was holding, and the AppWidget cleanup path -- deleteAppWidgetId on removal --
     * running against a plugin widget that has no id to delete.
     *
     * The spans are in grid cells, as for [Widget], and occupancy is held the same way through [Occupied].
     */
    data class PluginWidget(val className: String, val spanX: Int, val spanY: Int) : DesktopItem()

    /**
     * A cell covered by a widget whose top-left is elsewhere.
     *
     * WHY OCCUPANCY IS STORED RATHER THAN DERIVED. It could be recomputed by walking the grid and
     * expanding every widget's spans, and that was the first design. Storing it means the grid
     * remains what every other part of this screen already assumes it is: a flat array where cell
     * N is owned by exactly the thing at index N. Reordering, drag-and-drop, the delete zone and
     * "find the first empty cell" all keep working untouched, where a derived occupancy would have
     * needed each of them taught about widgets.
     *
     * [ownerIndex] points back at the widget so a covered cell can find what covers it.
     */
    data class Occupied(val ownerIndex: Int) : DesktopItem()

    fun serialize(): String {
        return when (this) {
            is App -> "app|$appId"
            is FileRef -> "file|$absolutePath"
            is DirectoryRef -> "dir|$absolutePath|$name"
            is Folder -> "folder|$name|$folderId"
            is NetworkedFolder -> "network|$url|$name|$type"
            is Widget -> "widget|$appWidgetId|$spanX|$spanY"
            // A distinct tag rather than reusing "widget": a class name is not an integer, and an old
            // build reading a new grid must fall through to "unrecognised" rather than parse a class name
            // as an id and place a widget that does not exist.
            is PluginWidget -> "pluginwidget|$className|$spanX|$spanY"
            is Occupied -> "occupied|$ownerIndex"
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
                "widget" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                    Widget(it, parts.getOrNull(2)?.toIntOrNull() ?: 1, parts.getOrNull(3)?.toIntOrNull() ?: 1)
                }
                "pluginwidget" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { name ->
                    PluginWidget(
                        className = name,
                        spanX = parts.getOrNull(2)?.toIntOrNull() ?: 2,
                        spanY = parts.getOrNull(3)?.toIntOrNull() ?: 2,
                    )
                }

                "occupied" -> Occupied(parts.getOrNull(1)?.toIntOrNull() ?: -1)
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

        /** How many cells across a desktop page is. Cell N is at (N % COLUMNS, N / COLUMNS). */
        const val COLUMNS = 4

        /**
         * Whether a [spanX] x [spanY] block fits at [index] with nothing already in the way.
         *
         * [ignoreOwner] lets a widget be tested against a move or a resize of itself: its own cells
         * are the ones it is vacating, so counting them as occupied would make every widget unable
         * to grow by a single cell.
         */
        fun fits(
            grid: List<DesktopItem?>,
            index: Int,
            spanX: Int,
            spanY: Int,
            ignoreOwner: Int = -1,
        ): Boolean {
            val column = index % COLUMNS
            val row = index / COLUMNS
            if (column + spanX > COLUMNS) return false
            if ((row + spanY) * COLUMNS > grid.size) return false

            for (y in 0 until spanY) {
                for (x in 0 until spanX) {
                    val cell = (row + y) * COLUMNS + (column + x)
                    if (cell >= grid.size) return false
                    when (val existing = grid[cell]) {
                        null -> Unit
                        is DesktopItem.Occupied -> if (existing.ownerIndex != ignoreOwner) return false
                        is DesktopItem.Widget -> if (cell != ignoreOwner) return false
                        // Same rule for a plugin widget: its own top-left cell does not block it.
                        is DesktopItem.PluginWidget -> if (cell != ignoreOwner) return false
                        else -> return false
                    }
                }
            }
            return true
        }

        /** Writes a widget at [index] and marks the cells it covers. Assumes [fits] already said yes. */
        fun placeWidget(grid: MutableList<DesktopItem?>, index: Int, widget: DesktopItem.Widget) {
            occupy(grid, index, widget, widget.spanX, widget.spanY)
        }

        /** The same, for a plugin's widget. PHASE 106. */
        fun placeWidget(grid: MutableList<DesktopItem?>, index: Int, widget: DesktopItem.PluginWidget) {
            occupy(grid, index, widget, widget.spanX, widget.spanY)
        }

        /**
         * Writes [owner] at [index] and fills the cells it covers with [DesktopItem.Occupied].
         *
         * SHARED BY BOTH WIDGET KINDS rather than copied, because the occupancy invariant -- every cell has
         * exactly one owner, and a covered cell points back at its owner's index -- is what the whole grid,
         * the drag-and-drop and the delete zone depend on. Two implementations of it is two places for it to
         * be got subtly wrong.
         */
        private fun occupy(
            grid: MutableList<DesktopItem?>,
            index: Int,
            owner: DesktopItem,
            spanX: Int,
            spanY: Int,
        ) {
            val column = index % COLUMNS
            val row = index / COLUMNS
            for (y in 0 until spanY) {
                for (x in 0 until spanX) {
                    val cell = (row + y) * COLUMNS + (column + x)
                    if (cell < grid.size) {
                        grid[cell] = if (cell == index) owner else DesktopItem.Occupied(index)
                    }
                }
            }
        }

        /** Clears a widget of either kind and every cell it covered. */
        fun clearWidget(grid: MutableList<DesktopItem?>, index: Int) {
            val spans = when (val item = grid.getOrNull(index)) {
                is DesktopItem.Widget -> item.spanX to item.spanY
                is DesktopItem.PluginWidget -> item.spanX to item.spanY
                else -> return
            }
            val column = index % COLUMNS
            val row = index / COLUMNS
            for (y in 0 until spans.second) {
                for (x in 0 until spans.first) {
                    val cell = (row + y) * COLUMNS + (column + x)
                    if (cell < grid.size) grid[cell] = null
                }
            }
        }

        /**
         * Finds room for a plugin's widget on any desktop page and places it. PHASE 106.
         *
         * SEARCHES EVERY PAGE, not just the first, and returns false rather than evicting anything. A
         * placement that displaced an icon to make room would be a widget silently rearranging somebody's
         * desktop, and the user has no way to know what used to be there.
         *
         * @return true when it was placed.
         */
        fun placePluginWidget(className: String, spanX: Int, spanY: Int): Boolean {
            val widget = DesktopItem.PluginWidget(className, spanX, spanY)
            // EVERY SLOT THE USER ACTUALLY HAS, from their own assignments, rather than a fixed page
            // count -- the number of desktop pages is whatever they arranged, and a constant here would
            // either miss pages or write grids for slots that are not desktops.
            val slots = runCatching { SlotPreferences().getAssignments().size }.getOrDefault(3)
            for (pageIndex in 0 until slots.coerceAtLeast(1)) {
                val store = DesktopShortcutStore(pageIndex)
                val grid = store.readGrid(GRID_SIZE)
                for (index in grid.indices) {
                    // The top-left cell has to be free and the run has to fit inside the row, which `fits`
                    // already checks -- including not wrapping onto the next row.
                    if (grid[index] != null) continue
                    if (!fits(grid, index, spanX, spanY)) continue
                    placeWidget(grid, index, widget)
                    store.writeGrid(grid)
                    return true
                }
            }
            return false
        }

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
