package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.AppEntry
import com.prism.core.PrismPlatform
import com.prism.core.SystemWallpaper
import com.prism.core.defaultAppCatalog
import com.prism.desktop.SystemIcons
import com.prism.launcher.AppSync
import com.prism.launcher.DesktopFolders
import com.prism.launcher.DesktopItem
import com.prism.launcher.DesktopShortcutStore
import com.prism.launcher.HotseatPredictor
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import kotlin.math.roundToInt

/**
 * The desktop page: wallpaper, icons on it, and the taskbar.
 *
 * NO VISIBLE GRID AND NO "ADD" BUTTON. The phone's desktop page is icons sitting on a wallpaper;
 * empty cells are invisible, because they are empty space, not slots. Things arrive by being
 * DRAGGED there -- an app from the drawer, a file or directory from the file explorer -- which is
 * the only way they arrive. An earlier version of this page drew every empty cell as a bordered
 * box and offered a picker dialog to fill one. Both were inventions; neither exists on the phone.
 *
 * The 6-column layout still exists as the arrangement icons snap to, but it is geometry rather
 * than decoration: nothing is drawn for a cell that holds nothing.
 *
 * DROPS COME FROM OTHER PAGES, which is why each cell registers itself with the shell's
 * [DragController] rather than handling its own drop. See [DragController] for why a pager makes
 * that necessary.
 */
@Composable
fun DesktopGridPage(pageIndex: Int = 1) {
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultAppCatalog() }
    val store = remember(pageIndex) { DesktopShortcutStore(pageIndex) }
    val controller = LocalDragController.current
    val colors = LocalPrismColors.current

    var cells by remember(pageIndex) {
        mutableStateOf<List<DesktopItem?>>(store.readGrid(DesktopShortcutStore.GRID_SIZE))
    }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var openFolder by remember { mutableStateOf<DesktopItem.Folder?>(null) }
    var renaming by remember { mutableStateOf<Pair<Int, DesktopItem.Folder>?>(null) }
    val icons = LocalIconCache.current

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }
        wallpaper = withContext(Dispatchers.IO) {
            SystemWallpaper().current()?.let { PrismPlatform.images.decode(it, 2560) }
        }?.toComposeBitmap()
    }

    val byId = remember(apps) { apps.associateBy { it.id } }

    fun save(next: List<DesktopItem?>) {
        cells = next
        scope.launch(Dispatchers.IO) { store.writeGrid(next) }
    }

    /** Puts a dropped item in a cell, or merges it into the folder already there. */
    fun accept(index: Int, payload: DragPayload) {
        val next = cells.toMutableList()
        val existing = next.getOrNull(index)
        when {
            existing == null -> next[index] = payload.item
            // Dropping onto a folder files it INTO the folder, as on the phone.
            existing is DesktopItem.Folder -> {
                fileInto(existing, payload.item)
                openFolder = null
            }
            // Dropping onto an occupied cell makes a folder of the pair.
            else -> {
                val id = DesktopFolders.create("Folder")
                fileInto(DesktopItem.Folder("Folder", id), existing)
                fileInto(DesktopItem.Folder("Folder", id), payload.item)
                next[index] = DesktopItem.Folder("Folder", id)
            }
        }
        save(next)
    }

    fun launchItem(item: DesktopItem?, index: Int) {
        when (item) {
            null -> Unit
            is DesktopItem.App -> byId[item.appId]?.let { e ->
                scope.launch(Dispatchers.IO) {
                    catalog.launch(e)
                    AppSync.recordLaunch(e.id)
                }
            }
            is DesktopItem.FileRef -> open(File(item.absolutePath))
            is DesktopItem.DirectoryRef -> open(File(item.absolutePath))
            is DesktopItem.Folder -> openFolder = item
            is DesktopItem.NetworkedFolder -> PrismPlatform.log.info(
                "Prism/grid", "Network shares open in the Files page, which is not wired up yet"
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ── Wallpaper ────────────────────────────────────────────────────────────────────────
        wallpaper?.let {
            Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                colors.background.copy(
                    alpha = if (wallpaper == null) 1f else PrismSettings.getWallpaperDim() / 100f
                )
            )
        )

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                DesktopCells(
                    cells = cells,
                    byId = byId,
                    icons = icons,
                    pageIndex = pageIndex,
                    onAccept = ::accept,
                    onClick = { i -> launchItem(cells.getOrNull(i), i) },
                    onPickUp = { i ->
                        // Removed optimistically so the space reads as empty while dragging, and
                        // restored by onCancelled if the drop goes nowhere.
                        val taken = cells[i] ?: return@DesktopCells null
                        val without = cells.toMutableList().also { it[i] = null }
                        save(without)
                        DragPayload(
                            item = taken,
                            label = labelOf(taken, byId),
                            icon = (taken as? DesktopItem.App)?.let { a -> icons[a.appId] },
                            onCancelled = { save(cells.toMutableList().also { it[i] = taken }) },
                        )
                    },
                    onRename = { i, folder -> renaming = i to folder },
                    onRemove = { i -> save(cells.toMutableList().also { it[i] = null }) },
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = controller.payload != null,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    DeleteZone()
                }
            }

            Taskbar(
                byId = byId,
                icons = icons,
                onLaunch = { e ->
                    scope.launch(Dispatchers.IO) {
                        catalog.launch(e)
                        AppSync.recordLaunch(e.id)
                    }
                },
            )
        }
    }

    openFolder?.let { folder ->
        FolderDialog(folder = folder, onDismiss = { openFolder = null }, onOpen = { open(it) })
    }

    renaming?.let { (index, folder) ->
        RenameDialog(
            initial = folder.name,
            onDismiss = { renaming = null },
            onRename = { newName ->
                val newId = DesktopFolders.rename(folder.folderId, newName) ?: folder.folderId
                save(cells.toMutableList().also { it[index] = DesktopItem.Folder(newName, newId) })
                renaming = null
            },
        )
    }
}

// ── Cells ────────────────────────────────────────────────────────────────────────────────────

/**
 * The icon field.
 *
 * A fixed Column/Row rather than LazyVerticalGrid: every cell must be composed and must have
 * known bounds so a drop landing anywhere can be resolved, and a lazy grid deliberately skips
 * what is off screen. With 24 cells laziness buys nothing and costs correctness.
 */
@Composable
private fun DesktopCells(
    cells: List<DesktopItem?>,
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    pageIndex: Int,
    onAccept: (Int, DragPayload) -> Unit,
    onClick: (Int) -> Unit,
    onPickUp: (Int) -> DragPayload?,
    onRename: (Int, DesktopItem.Folder) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val columns = 6
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cells.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEachIndexed { colIndex, item ->
                    val index = rowIndex * columns + colIndex
                    DesktopCell(
                        key = "grid-$pageIndex-$index",
                        index = index,
                        item = item,
                        byId = byId,
                        icons = icons,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onAccept = onAccept,
                        onClick = onClick,
                        onPickUp = onPickUp,
                        onRename = onRename,
                        onRemove = onRemove,
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DesktopCell(
    key: String,
    index: Int,
    item: DesktopItem?,
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    modifier: Modifier,
    onAccept: (Int, DragPayload) -> Unit,
    onClick: (Int) -> Unit,
    onPickUp: (Int) -> DragPayload?,
    onRename: (Int, DesktopItem.Folder) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val controller = LocalDragController.current
    var menuOpen by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Highlighted only while something is actually over it. An empty cell is otherwise invisible.
    val hovered = controller.payload != null && bounds.contains(controller.position)

    Box(
        modifier = modifier
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                origin = Offset(bounds.left, bounds.top)
            }
            .dropTarget(key) { payload, _ -> onAccept(index, payload) }
            .background(
                if (hovered) Color(0x335C6CFF) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
    ) {
        if (item == null) return@Box

        Box(
            Modifier
                .fillMaxSize()
                .clickableRow { onClick(index) }
                .pointerInput(key, item) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            onPickUp(index)?.let { controller.start(it, origin + local) }
                        },
                        onDrag = { change, _ ->
                            val p = origin + change.position
                            controller.move(
                                p,
                                flipZone(p.x, size.width.toFloat().coerceAtLeast(1f) * 6f, 90f),
                            )
                        },
                        onDragEnd = { controller.end() },
                        onDragCancel = { controller.cancel() },
                    )
                }
        ) {
            CellFace(item, byId, icons)

            Icon(
                Icons.Filled.MoreVert, "Options",
                tint = Color(0xAA9A9AA6),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(14.dp)
                    .clickableRow { menuOpen = true },
            )
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                if (item is DesktopItem.Folder) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename(index, item) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove from desktop") },
                    onClick = { menuOpen = false; onRemove(index) },
                )
            }
        }
    }
}

/** Icon and label only -- no cell chrome, because the phone has none. */
@Composable
fun CellFace(
    item: DesktopItem,
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    iconSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val entry = (item as? DesktopItem.App)?.let { byId[it.appId] }
    entry?.let { IconLoader(it, icons) }

    Column(
        Modifier.fillMaxSize().padding(4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconOrFallback(entry?.let { icons[it.id] }, fallbackIcon(item), iconSize)
        Spacer(Modifier.height(5.dp))
        Text(
            labelOf(item, byId),
            fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, color = Color(0xFFE8E8F0), lineHeight = 12.sp,
        )
    }
}

@Composable
private fun DeleteZone() {
    val controller = LocalDragController.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val armed = controller.payload != null && bounds.contains(controller.position)
    val colors = LocalPrismColors.current

    Surface(
        color = if (armed) Color(0xE6B3261E) else Color(0xCC2A2A34),
        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
        modifier = Modifier
            .padding(horizontal = 140.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            // Registered LAST so it wins over the cell beneath it when they overlap.
            .dropTarget("delete-zone") { _, _ -> /* dropping here simply discards */ },
    ) {
        Row(
            Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Delete, null,
                tint = if (armed) Color.White else colors.muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (armed) "Release to remove" else "Drag here to remove",
                fontSize = 12.sp,
                color = if (armed) Color.White else colors.muted,
            )
        }
    }
}

// ── Taskbar ──────────────────────────────────────────────────────────────────────────────────

/**
 * Pinned apps the user chose, then apps predicted for this hour.
 *
 * The prediction is the hotseat kept intact -- [HotseatPredictor]'s hour-of-day model over
 * `app_launch_stats`. Pins were added alongside it rather than replacing it; a taskbar of only
 * pins would discard the one thing this launcher does that an ordinary taskbar does not. Pins
 * come first and never move, because a pin whose position depends on a prediction is not a pin.
 *
 * The taskbar is also a drop target: dragging an app onto it pins it.
 */
@Composable
private fun Taskbar(
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    onLaunch: (AppEntry) -> Unit,
) {
    val colors = LocalPrismColors.current
    val controller = LocalDragController.current
    var pins by remember { mutableStateOf(PrismSettings.getTaskbarPins()) }
    var predicted by remember { mutableStateOf<List<String>>(emptyList()) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    LaunchedEffect(byId.size) {
        if (byId.isEmpty()) return@LaunchedEffect
        predicted = try {
            withContext(Dispatchers.IO) { HotseatPredictor.getPredictions(8) }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/taskbar", "Prediction failed", e)
            emptyList()
        }
    }

    val pinned = remember(pins, byId) { pins.mapNotNull { byId[it] } }
    val suggestions = remember(predicted, byId, pins) {
        predicted.filterNot { it in pins }.mapNotNull { byId[it] }
    }
    val hovered = controller.payload != null && bounds.contains(controller.position)

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .dropTarget("taskbar") { payload, _ ->
                (payload.item as? DesktopItem.App)?.let {
                    if (it.appId !in pins) {
                        pins = pins + it.appId
                        PrismSettings.setTaskbarPins(pins)
                    }
                }
            }
            .background(if (hovered) Color(0xF2202030) else Color(0xF2101014))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Hexagon, null, tint = colors.accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))

        if (pinned.isEmpty() && !hovered) {
            Text("Drag an app here to pin it", fontSize = 10.sp, color = Color(0xFF44444E))
        }
        if (hovered) {
            Text("Release to pin", fontSize = 11.sp, color = colors.accent)
        }

        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(pinned) { entry ->
                TaskbarIcon(entry, icons, onLaunch, onUnpin = {
                    pins = pins - entry.id
                    PrismSettings.setTaskbarPins(pins)
                })
            }
        }

        Spacer(Modifier.weight(1f))

        if (suggestions.isNotEmpty()) {
            Text(
                "PREDICTED", fontSize = 8.sp, letterSpacing = 1.1.sp,
                color = Color(0xFF4A4A54), modifier = Modifier.padding(end = 8.dp),
            )
            LazyRow(verticalAlignment = Alignment.CenterVertically) {
                items(suggestions) { entry ->
                    TaskbarIcon(entry, icons, onLaunch, pinAction = {
                        pins = pins + entry.id
                        PrismSettings.setTaskbarPins(pins)
                    })
                }
            }
        }
    }
}

@Composable
private fun TaskbarIcon(
    entry: AppEntry,
    icons: MutableMap<String, ImageBitmap?>,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (() -> Unit)? = null,
    pinAction: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    IconLoader(entry, icons)

    Box {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(38.dp)
                .clickableRow { onLaunch(entry) }
                .pointerInput(entry.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { menu = true },
                        onDrag = { _, _ -> },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            IconOrFallback(icons[entry.id], Icons.Filled.Android, 26.dp)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            Text(
                entry.label, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            onUnpin?.let {
                DropdownMenuItem(text = { Text("Unpin") }, onClick = { menu = false; it() })
            }
            pinAction?.let {
                DropdownMenuItem(text = { Text("Pin to taskbar") }, onClick = { menu = false; it() })
            }
        }
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────────────────────

/**
 * A folder, with nested navigation.
 *
 * The stack is what makes this nested: entering a subdirectory pushes, Back pops. Contents are
 * the directory's real contents, so there is nothing to keep in sync.
 */
@Composable
private fun FolderDialog(folder: DesktopItem.Folder, onDismiss: () -> Unit, onOpen: (File) -> Unit) {
    val rootDir = remember(folder.folderId) { DesktopFolders.resolve(folder.folderId) }
    var stack by remember(folder.folderId) { mutableStateOf(listOf(rootDir)) }
    val current = stack.last()
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(current) {
        entries = withContext(Dispatchers.IO) {
            (current.listFiles() ?: emptyArray())
                .filterNot { it.isHidden }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (stack.size > 1) {
                TextButton(onClick = { stack = stack.dropLast(1) }) { Text("Back") }
            }
        },
        title = {
            Column {
                Text(folder.name)
                Text(
                    stack.drop(1).joinToString(" / ") { it.name }.ifBlank { "—" },
                    fontSize = 10.sp, color = Color(0xFF6C6C78),
                )
            }
        },
        text = {
            Column(Modifier.width(460.dp).heightIn(max = 420.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        "Empty. Drag items onto this folder to fill it.",
                        fontSize = 12.sp, color = Color(0xFF83838F),
                    )
                }
                androidx.compose.foundation.lazy.LazyColumn {
                    items(entries) { file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow {
                                    if (file.isDirectory) stack = stack + file else onOpen(file)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                null, tint = Color(0xFF9A9AA6), modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(file.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (file.isDirectory) {
                                Icon(
                                    Icons.Filled.ChevronRight, null,
                                    tint = Color(0xFF55555F), modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onRename(name) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename folder") },
        text = { CommittingField(name, modifier = Modifier.width(320.dp)) { name = it } },
    )
}

// ── Shared ───────────────────────────────────────────────────────────────────────────────────

/**
 * Icons decoded once per process, shared across every page.
 *
 * A CompositionLocal rather than per-page state because the drawer, the desktop and the taskbar
 * all show the same applications; three caches would mean decoding every icon three times.
 */
val LocalIconCache = compositionLocalOf<MutableMap<String, ImageBitmap?>> {
    error("No icon cache installed")
}

@Composable
fun IconOrFallback(
    icon: ImageBitmap?,
    fallback: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
) {
    if (icon != null) {
        Image(icon, null, Modifier.size(size))
    } else {
        Icon(fallback, null, tint = Color(0xFF9A9AA6), modifier = Modifier.size(size))
    }
}

/**
 * Decodes an entry's icon once and caches it by id.
 *
 * Two sources, in order: the file the catalog found, then the OS shell. The second is what makes
 * Windows work at all -- Start Menu entries are .lnk shortcuts whose icon lives inside the target
 * .exe as a resource, so there is no image file for the catalog to have found.
 */
@Composable
fun IconLoader(entry: AppEntry, icons: MutableMap<String, ImageBitmap?>) {
    LaunchedEffect(entry.id) {
        if (icons.containsKey(entry.id)) return@LaunchedEffect
        icons[entry.id] = withContext(Dispatchers.IO) {
            val fromFile = entry.iconPath?.let { PrismPlatform.images.decode(File(it), 128) }
            (fromFile ?: SystemIcons.forApp(entry))?.toComposeBitmap()
        }
    }
}

fun labelOf(item: DesktopItem, byId: Map<String, AppEntry>): String = when (item) {
    is DesktopItem.App -> byId[item.appId]?.label ?: item.appId.substringAfterLast('/')
    is DesktopItem.FileRef -> File(item.absolutePath).name
    is DesktopItem.DirectoryRef -> item.name
    is DesktopItem.Folder -> item.name
    is DesktopItem.NetworkedFolder -> item.name
}

fun fallbackIcon(item: DesktopItem): androidx.compose.ui.graphics.vector.ImageVector = when (item) {
    is DesktopItem.App -> Icons.Filled.Android
    is DesktopItem.FileRef -> Icons.Filled.InsertDriveFile
    is DesktopItem.DirectoryRef -> Icons.Filled.Folder
    is DesktopItem.Folder -> Icons.Filled.FolderSpecial
    is DesktopItem.NetworkedFolder -> Icons.Filled.Cloud
}

/** Files a dropped item into a folder, when it refers to something on disk. */
private fun fileInto(folder: DesktopItem.Folder, item: DesktopItem) {
    val source = when (item) {
        is DesktopItem.FileRef -> File(item.absolutePath)
        is DesktopItem.DirectoryRef -> File(item.absolutePath)
        else -> null
    } ?: return
    if (source.exists()) DesktopFolders.addFile(folder.folderId, source)
}

/** Opens a file or directory with whatever the OS associates with it. */
private fun open(file: File) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        PrismPlatform.log.error("Prism/grid", "Could not open ${file.path}", e)
    }
}
