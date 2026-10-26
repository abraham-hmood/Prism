package com.prism.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.prism.launcher.theme.PrismThemes
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
    var topApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var openFolder by remember { mutableStateOf<DesktopItem.Folder?>(null) }
    var renaming by remember { mutableStateOf<Pair<Int, DesktopItem.Folder>?>(null) }
    val icons = LocalIconCache.current

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }
        // Which icons the user actually launches, so Spark's current can flow from the ones that
        // matter. Read once: a spark whose strength changed mid-animation would flicker.
        topApps = withContext(Dispatchers.IO) { AppSync.topLaunched(32) }
        // A theme that turns the wallpaper off means it: Fusion and Spark carry their own ground,
        // and loading a photograph underneath one is what made showWallpaper a phone-only promise.
        wallpaper = if (PrismThemes.active().showWallpaper) {
            withContext(Dispatchers.IO) {
                SystemWallpaper().current()?.let { PrismPlatform.images.decode(it, 2560) }
            }?.toComposeBitmap()
        } else {
            null
        }
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

            // Widgets are Android AppWidgets -- remote views hosted out of another app's process,
            // which has no desktop equivalent at all. A saved layout containing one still has to
            // LOAD here, though, or a user with widgets on their phone cannot open their desktop
            // at all. So the cells are inert rather than absent. See PHASE 106.
            is DesktopItem.Widget -> PrismPlatform.log.info(
                "Prism/grid", "Android AppWidgets are Android-only; this cell is a placeholder"
            )

            // A PLUGIN WIDGET IS NOT LAUNCHED, IT IS DRAWN. Clicking one does nothing here on purpose:
            // its buttons are its own and are handled by the renderer, so a click that reached this
            // function is a click on the widget's background. PHASE 106.
            is DesktopItem.PluginWidget -> Unit

            // A cell covered by a widget whose top-left is elsewhere. Never launchable on any
            // platform -- the owning Widget entry is what holds the interaction.
            is DesktopItem.Occupied -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ── Wallpaper ────────────────────────────────────────────────────────────────────────
        wallpaper?.let {
            Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // A theme that paints its own ground gets the page to itself; otherwise the flat colour
        // sits over the wallpaper at the user's dim setting. Fusion is the first case.
        val nebula = colors.backdrop == com.prism.launcher.theme.BACKDROP_NEBULA && wallpaper == null
        Box(
            Modifier.fillMaxSize()
                .then(if (nebula) Modifier.nebulaBackdrop(dark = true) else Modifier)
                .background(
                    colors.background.copy(
                        alpha = when {
                            nebula -> 0f
                            wallpaper == null -> 1f
                            else -> PrismSettings.getWallpaperDim() / 100f
                        }
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
                    topApps = topApps,
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

            // Under xOS the shell's taskbar carries these instead; see MacOsShell.
            if (!LocalShellTaskbar.current) {
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
    topApps: List<String>,
    onAccept: (Int, DragPayload) -> Unit,
    onClick: (Int) -> Unit,
    onPickUp: (Int) -> DragPayload?,
    onRename: (Int, DesktopItem.Folder) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val columns = 6
    val colors = LocalPrismColors.current
    val electric = colors.edgeEffect == com.prism.launcher.theme.EDGE_ELECTRIC
    // Where each cell actually is, in root coordinates. Spark's arcs are drawn between these,
    // which is only possible because the grid composes every cell (see the note on DesktopCells).
    val centres = remember { mutableStateMapOf<Int, Offset>() }
    val weights = remember(topApps, cells) { sparkWeights(cells, topApps) }

    Box(Modifier.fillMaxSize()) {
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
                            onCentre = { cellIndex, point -> centres[cellIndex] = point },
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (electric && weights.isNotEmpty()) {
            var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
            val transition = rememberInfiniteTransition(label = "sparks")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
                label = "spark-phase",
            )
            Canvas(
                Modifier.fillMaxSize().onGloballyPositioned {
                    overlayOrigin = it.positionInRoot()
                }
            ) {
                val local = centres.mapValues { (_, point) -> point - overlayOrigin }
                drawSparks(local, weights, phase, colors.accent)
            }
        }
    }
}

/**
 * How strongly each cell should spark, from the launch counts.
 *
 * The most-used app is 1.0 and the least-used is still 0.25, so every app that is actually used
 * lights up but the favourite clearly throws the longest arcs. An app that has never been launched
 * is absent rather than dim, because a desktop where every icon crackles is wallpaper, not
 * electricity.
 */
internal fun sparkWeights(cells: List<DesktopItem?>, topApps: List<String>): Map<Int, Float> {
    if (topApps.isEmpty()) return emptyMap()
    val rank = topApps.withIndex().associate { (index, appId) -> appId to index }
    val last = (topApps.size - 1).coerceAtLeast(1).toFloat()
    return cells.mapIndexedNotNull { index, item ->
        val appId = (item as? DesktopItem.App)?.appId ?: return@mapIndexedNotNull null
        val at = rank[appId] ?: return@mapIndexedNotNull null
        index to (1f - (at / last) * 0.75f).coerceIn(0.25f, 1f)
    }.toMap()
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
    onCentre: (Int, Offset) -> Unit = { _, _ -> },
) {
    val colors = LocalPrismColors.current
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
                onCentre(index, bounds.center)
            }
            .dropTarget(key) { payload, _ -> onAccept(index, payload) }
            .background(
                if (hovered) colors.accent.copy(alpha = 0.20f) else Color.Transparent,
                RoundedCornerShape(colors.cardRadius),
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
                tint = colors.faint,
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
    // A PLUGIN WIDGET IS NOT AN ICON. PHASE 106. Everything else in a cell is a picture with a caption
    // under it; a widget is content, so it takes the whole cell and draws its own tree. Handled before the
    // icon path rather than inside it, because there is no icon to load and no label to centre.
    if (item is DesktopItem.PluginWidget) {
        PluginWidgetFace(item)
        return
    }

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
            textAlign = TextAlign.Center, color = LocalPrismColors.current.onSurface, lineHeight = 12.sp,
        )
    }
}

/**
 * A plugin's widget, drawn in the cells it was given. PHASE 106.
 *
 * ## Why the plugin is loaded here and not by the grid
 *
 * The grid holds a CLASS NAME, not an instance -- that is all that survives being written to the slot
 * store and read back. So the widget resolves its own plugin on first composition and keeps it for as long
 * as it is on screen. A grid that loaded plugins would have to know which of its cells were plugins and
 * when to unload them; this way a widget scrolled off screen simply stops existing.
 *
 * ## Failure is drawn, not logged
 *
 * A widget whose JAR has been deleted, or whose class no longer implements the contract, shows a short
 * message in its own cells. The alternative is an empty rectangle on somebody's desktop with the
 * explanation in a log file they have no reason to open.
 */
@Composable
private fun PluginWidgetFace(item: DesktopItem.PluginWidget) {
    val colors = LocalPrismColors.current
    var plugin by remember(item.className) { mutableStateOf<com.prism.launcher.plugins.PrismPlugin?>(null) }
    var tree by remember(item.className) { mutableStateOf<com.prism.launcher.plugins.PluginNode?>(null) }
    var failure by remember(item.className) { mutableStateOf("") }

    val context = remember(item.className) {
        com.prism.launcher.plugins.DefaultPluginContext(item.className, item.className.substringAfterLast('.'))
    }

    LaunchedEffect(item.className) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val page = com.prism.launcher.PluginPages.discover()
                    .firstOrNull { it.className == item.className }
                    ?: error("no JAR in the plugins folder provides " + item.className)
                val cls = com.prism.launcher.PluginPages.load(page)
                    ?: error("loading is off, or the class is missing")
                cls.getDeclaredConstructor().newInstance() as? com.prism.launcher.plugins.PrismPlugin
                    ?: error("it does not implement PrismPlugin")
            }.onFailure { failure = it.message ?: it::class.java.simpleName }.getOrNull()
        }
        plugin = loaded

        // The widget's own interval, not the page's: a clock widget wants a second where its page might
        // want a minute, and PluginWidget carries a separate figure for exactly that.
        val seconds = runCatching { loaded?.widget?.refreshSeconds ?: 0 }.getOrDefault(0)
        while (loaded != null) {
            tree = withContext(Dispatchers.IO) {
                runCatching { loaded.content(context) }
                    .onFailure { failure = "it threw while drawing: " + (it.message ?: "") }
                    .getOrNull()
            } ?: tree
            if (seconds <= 0) break
            kotlinx.coroutines.delay(seconds.coerceAtLeast(1) * 1000L)
        }
    }

    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(colors.cardRadius),
        modifier = Modifier.fillMaxSize().padding(3.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            val current = tree
            when {
                failure.isNotEmpty() && current == null -> Text(
                    item.className.substringAfterLast('.') + ": " + failure,
                    fontSize = 9.sp,
                    color = colors.accent.copy(alpha = 0.9f),
                    lineHeight = 12.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )

                current == null -> Text("Loading…", fontSize = 10.sp, color = colors.faint)

                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    // The same renderer the full page uses, so a widget cannot look like a different
                    // product from the page it came with.
                    PluginNodeView(current, context.storage()) { _, _ ->
                        // Deliberately inert. A widget's job is to show; anything that acts belongs on the
                        // page, where a mis-click is not one cell away from a drag that rearranges the
                        // desktop.
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteZone() {
    val controller = LocalDragController.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val armed = controller.payload != null && bounds.contains(controller.position)
    val colors = LocalPrismColors.current

    Surface(
        color = if (armed) colors.destructive else colors.surfaceRaised,
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
 *
 * THIS PAGE NO LONGER OWNS THE STRIP UNDER xOS. The macOS interpretation puts the pages
 * themselves -- and this same pin/prediction strip -- in the window's taskbar, so the shell
 * draws it ([MacOsShell]) and the page leaves the space to the grid. [LocalShellTaskbar] is how
 * the page knows; see [TaskbarApps] for the part both shells share.
 */
@Composable
private fun Taskbar(
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    onLaunch: (AppEntry) -> Unit,
) {
    val colors = LocalPrismColors.current
    TaskbarApps(
        byId = byId,
        icons = icons,
        onLaunch = onLaunch,
        baseColor = colors.surface,
        leading = {
            Icon(Icons.Filled.Hexagon, null, tint = colors.accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
        },
    )
}

/**
 * Pins, then predictions, plus the drop target that pins a dragged app.
 *
 * Extracted from the desktop page because xOS's taskbar is the same strip: a macOS dock that
 * could not pin an app dragged onto it would be a worse taskbar than the one it replaced. The
 * caller supplies what sits at the leading edge -- the Prism mark, or the dock's Launchpad and
 * page icons -- and the rest is identical in both shells.
 */
@Composable
internal fun TaskbarApps(
    byId: Map<String, AppEntry>,
    icons: MutableMap<String, ImageBitmap?>,
    onLaunch: (AppEntry) -> Unit,
    baseColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 38.dp,
    glyphSize: Dp = 26.dp,
    verticalPadding: Dp = 6.dp,
    // The dock is not a place to advertise a feature: a sentence sitting between the page icons
    // and the pins reads as a broken icon. Only the workbench taskbar, which is empty when the
    // user has pinned nothing, explains itself.
    showPinHint: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {},
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
        modifier
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
            .background(if (hovered) colors.surfaceRaised else baseColor)
            .padding(horizontal = 10.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()

        if (showPinHint && pinned.isEmpty() && !hovered) {
            Text("Drag an app here to pin it", fontSize = 10.sp, color = colors.faint)
        }
        if (hovered) {
            Text("Release to pin", fontSize = 11.sp, color = colors.accent)
        }

        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(pinned) { entry ->
                TaskbarIcon(entry, icons, onLaunch, iconSize, glyphSize, onUnpin = {
                    pins = pins - entry.id
                    PrismSettings.setTaskbarPins(pins)
                })
            }
        }

        // THE OTHER HALF OF BEING A SHELL. When Prism replaced Explorer there is no other
        // taskbar, so pins and predictions alone would leave the user's open programs invisible.
        RunningAppsStrip(iconSize, glyphSize)

        Spacer(Modifier.weight(1f))

        if (suggestions.isNotEmpty()) {
            Text(
                "PREDICTED", fontSize = 8.sp, letterSpacing = 1.1.sp,
                color = colors.faint, modifier = Modifier.padding(end = 8.dp),
            )
            LazyRow(verticalAlignment = Alignment.CenterVertically) {
                items(suggestions) { entry ->
                    TaskbarIcon(entry, icons, onLaunch, iconSize, glyphSize, pinAction = {
                        pins = pins + entry.id
                        PrismSettings.setTaskbarPins(pins)
                    })
                }
            }
        }
    }
}

/**
 * The open windows, as taskbar buttons.
 *
 * POLLED, not pushed. Windows has no portable notification for "a top-level window appeared",
 * and an event hook is a much larger thing than a strip that refreshes every few seconds. The
 * poll is off the UI thread and the list is only replaced when it actually differs, so an idle
 * desktop does not recompose this row over and over.
 *
 * A CLICK ACTIVATES THE PROGRAM. It does not close anything, and it does not switch Prism away
 * from the page -- the point of a shell is that the other program comes forward while Prism stays
 * behind it, exactly as Explorer behaves.
 */
@Composable
private fun RunningAppsStrip(iconSize: Dp, glyphSize: Dp) {
    if (!com.prism.desktop.RunningApps.supported) return
    val colors = LocalPrismColors.current
    var running by remember { mutableStateOf<List<com.prism.desktop.RunningApps.Entry>>(emptyList()) }

    LaunchedEffect(Unit) {
        // ONLY WHILE PRISM IS THE SHELL. The OS taskbar already lists these windows when Explorer
        // or a desktop panel is running, and a second copy on Prism's own strip would make every
        // ordinary desktop session look busier than the shell it replaced.
        val active = withContext(Dispatchers.IO) {
            com.prism.desktop.ShellReplacement.state().active
        }
        if (!active) return@LaunchedEffect
        while (true) {
            val next = withContext(Dispatchers.IO) { com.prism.desktop.RunningApps.list() }
            if (next != running) running = next
            kotlinx.coroutines.delay(4000L)
        }
    }

    if (running.isEmpty()) return

    // Bounded, so a dozen open windows scroll inside the strip instead of pushing the pins and
    // predictions off the end of the taskbar.
    LazyRow(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = 440.dp),
    ) {
        items(running, key = { it.key }) { entry ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(iconSize)
                    .clickableRow { com.prism.desktop.RunningApps.activate(entry) },
                contentAlignment = Alignment.Center,
            ) {
                val icon = entry.icon
                if (icon != null) {
                    Image(icon, null, Modifier.size(glyphSize), contentScale = ContentScale.Fit)
                } else {
                    Box(
                        Modifier
                            .size(glyphSize * 0.8f)
                            .background(colors.surfaceRaised, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            entry.title.take(1).uppercase(),
                            fontSize = 11.sp,
                            color = colors.onSurface,
                        )
                    }
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
    iconSize: Dp,
    glyphSize: Dp,
    onUnpin: (() -> Unit)? = null,
    pinAction: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    IconLoader(entry, icons)

    Box {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(iconSize)
                .clickableRow { onLaunch(entry) }
                .pointerInput(entry.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { menu = true },
                        onDrag = { _, _ -> },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            IconOrFallback(icons[entry.id], Icons.Filled.Android, glyphSize)
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
    val colors = LocalPrismColors.current
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
                    fontSize = 10.sp, color = colors.faint,
                )
            }
        },
        text = {
            Column(Modifier.width(460.dp).heightIn(max = 420.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        "Empty. Drag items onto this folder to fill it.",
                        fontSize = 12.sp, color = colors.muted,
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
                                if (file.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                null, tint = colors.muted, modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(file.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (file.isDirectory) {
                                Icon(
                                    Icons.Filled.ChevronRight, null,
                                    tint = colors.faint, modifier = Modifier.size(15.dp),
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
        Icon(fallback, null, tint = LocalPrismColors.current.faint, modifier = Modifier.size(size))
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
    is DesktopItem.Widget -> "Widget"
    // The plugin's own label would be better and needs the plugin loaded to know it; the class's simple
    // name is what is available without loading code to draw a caption.
    is DesktopItem.PluginWidget -> item.className.substringAfterLast('.')
    // Labelled as nothing on purpose: this cell is covered by a neighbour, and drawing a caption
    // under it would put text in the middle of the widget it belongs to.
    is DesktopItem.Occupied -> ""
}

fun fallbackIcon(item: DesktopItem): androidx.compose.ui.graphics.vector.ImageVector = when (item) {
    is DesktopItem.App -> Icons.Filled.Android
    is DesktopItem.FileRef -> Icons.AutoMirrored.Filled.InsertDriveFile
    is DesktopItem.DirectoryRef -> Icons.Filled.Folder
    is DesktopItem.Folder -> Icons.Filled.FolderSpecial
    is DesktopItem.NetworkedFolder -> Icons.Filled.Cloud
    is DesktopItem.Widget -> Icons.Filled.Widgets
    is DesktopItem.PluginWidget -> Icons.Filled.Extension
    is DesktopItem.Occupied -> Icons.Filled.Widgets
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
