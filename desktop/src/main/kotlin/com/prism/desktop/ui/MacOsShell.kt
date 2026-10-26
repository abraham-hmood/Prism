package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowSize
import androidx.compose.ui.window.rememberWindowState
import com.prism.core.AppEntry
import com.prism.core.defaultAppCatalog
import com.prism.launcher.AppSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * xOS on a desktop: the macOS shell.
 *
 * NO SIDEBAR. The workbench rail lists every page as a labelled row down the left edge, which is
 * the one thing about this application that does not look like macOS and, at ~50 entries, the
 * densest part of it. Under xOS the window is the page and the pages are icons in the taskbar
 * along the bottom -- the dock -- which is where a Mac puts the things you launch.
 *
 * THE TASKBAR IS ONE STRIP, NOT TWO. Prism's desktop page already had a taskbar of pinned and
 * predicted apps, and dropping a macOS dock on top of it would have stacked two bars at the
 * bottom of the desktop. So the dock carries that strip too: the leading edge is Launchpad and
 * the key pages, then the pins and predictions, with the same drag-to-pin drop target. A dock
 * that could not pin would be a worse taskbar than the one it replaced.
 *
 * THE DOCK HOLDS A DOZEN PAGES, NOT FIFTY. An icon-only bar of every page is a fifty-icon strip
 * of unlabelled glyphs, which is the rail's density with worse labels. [DOCK_PAGES] is the set
 * somebody reaches for while working; [LaunchpadWindow] -- one click away, at the start of the
 * strip -- is every page, named and grouped.
 *
 * HOVER SHOWS THE NAME. macOS labels a dock icon on hover and so does this, because an icon-only
 * bar of a dozen glyphs is otherwise a guessing game. See [DockIcon] for where the label sits
 * and for the pages whose embedded canvas paints over it.
 */
@Composable
fun MacOsShell(
    selected: PageId,
    onSelect: (PageId) -> Unit,
    launcherMode: Boolean,
    onLauncherModeChange: (Boolean) -> Unit,
    onNavigate: (PageId) -> Unit,
) {
    val colors = LocalPrismColors.current
    var launchpad by remember { mutableStateOf(false) }

    // Told to every page in the shell: the taskbar below already carries the desktop page's pins,
    // so that page must not draw a second strip of its own.
    CompositionLocalProvider(LocalShellTaskbar provides true) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().padding(bottom = XOS_TASKBAR_HEIGHT)) {
                PageBody(
                    page = selected,
                    launcherMode = launcherMode,
                    onLauncherModeChange = onLauncherModeChange,
                    onNavigate = onNavigate,
                )
            }

            MacOsDock(
                selected = selected,
                onSelect = onSelect,
                launchpadOpen = launchpad,
                onToggleLaunchpad = { launchpad = !launchpad },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (launchpad) {
                LaunchpadWindow(
                    selected = selected,
                    onSelect = { onSelect(it); launchpad = false },
                    onClose = { launchpad = false },
                )
            }
        }
    }
}

/**
 * The pages the dock carries. Ordered the way the work flows rather than the way the enum is
 * declared: the assistant first, then what you do with it, then the things you configure once.
 */
private val DOCK_PAGES = listOf(
    PageId.NORA_CHAT,
    PageId.NORA_TRAIN,
    PageId.DESKTOP,
    PageId.BROWSER,
    PageId.MESSAGES_PAGE,
    PageId.FILES,
    PageId.MODELS,
    PageId.NEBULA,
    PageId.AGENTIC,
    PageId.WALLET,
    PageId.MAPS,
    PageId.EDITOR,
    PageId.SETTINGS,
)

/** The dock's own metrics, in one place because the strip height and the label offset derive from them. */
private val DOCK_ICON: Dp = 44.dp
private val DOCK_GLYPH: Dp = 28.dp
private val DOCK_PADDING: Dp = 9.dp

/**
 * The strip's height: a hairline, the icons and their padding above and below.
 *
 * Fixed rather than measured because the page above it has to reserve exactly this much: a page
 * laid out under the dock would have its last row of content permanently hidden behind it.
 */
private val XOS_TASKBAR_HEIGHT: Dp = 1.dp + DOCK_ICON + DOCK_PADDING * 2

/** The dock at the bottom of the window: Launchpad, the pages, then the app strip. */
@Composable
private fun MacOsDock(
    selected: PageId,
    onSelect: (PageId) -> Unit,
    launchpadOpen: Boolean,
    onToggleLaunchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val iconCache = LocalIconCache.current
    val catalog = remember { defaultAppCatalog() }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }
    }
    val byId = remember(apps) { apps.associateBy { it.id } }

    Column(modifier.fillMaxWidth()) {
        // The rule that separates chrome from content, as every macOS window has.
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        TaskbarApps(
            byId = byId,
            icons = iconCache,
            onLaunch = { entry ->
                scope.launch(Dispatchers.IO) {
                    catalog.launch(entry)
                    AppSync.recordLaunch(entry.id)
                }
            },
            baseColor = colors.surface,
            iconSize = DOCK_ICON,
            glyphSize = DOCK_GLYPH,
            verticalPadding = DOCK_PADDING,
            showPinHint = false,
            leading = {
                DockIcon(
                    icon = Icons.Filled.Apps,
                    label = "Launchpad",
                    active = launchpadOpen,
                    onClick = onToggleLaunchpad,
                )
                DockDivider()
                DOCK_PAGES.forEach { page ->
                    DockIcon(
                        icon = page.icon,
                        label = page.label,
                        active = page == selected,
                        onClick = { onSelect(page) },
                    )
                }
                DockDivider()
            },
        )
    }
}

/**
 * One dock icon: glyph, a running-app dot for the selected page, and a hover label.
 *
 * The dot rather than a filled tile, because the dock is where pages you are NOT on live and an
 * accent tile for every one of them would say nothing about where you are.
 */
@Composable
private fun DockIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(DOCK_ICON)
            .hoverable(interaction)
            .clickableRow(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) colors.surfaceRaised else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (active) colors.accent else colors.onSurface,
                modifier = Modifier.size(DOCK_GLYPH - 6.dp),
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
            )
        }

        // THE LABEL IS THE ICON'S OWN CHILD, drawn outside its box. Nothing here clips, so it
        // floats above the strip and over the page -- which is the only place it can go, a dock
        // sitting at the very edge of the window with no room below. Opaque, since it can land on
        // the browser's white page as easily as on a dark one.
        //
        // WHERE IT DOES NOT SHOW: over a page with a live Chromium or editor canvas, which always
        // paints above in-window Compose content (see BrowserPage). Launchpad, which would be
        // pointless there, is a window of its own; a label that appeared for a second is not
        // worth a window per hover, so the chip is simply behind the canvas on those pages.
        if (hovered) {
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, colors.divider),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(LABEL_LIFT)),
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * How far the label clears the icon: its own box, the strip's padding above it, and an 8dp gap,
 * so the chip is entirely above the hairline instead of half-covered by the strip it annotates.
 */
private val LABEL_LIFT: Dp = DOCK_ICON + DOCK_PADDING + 8.dp

@Composable
private fun DockDivider() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .width(1.dp)
            .height(28.dp)
            .background(LocalPrismColors.current.divider)
    )
}

/**
 * Every page, named, on the one surface that can hold them.
 *
 * The dock is deliberately partial, so this is the complete map: grouped the way the rail grouped
 * them, each tile an icon AND its name, because a grid of unlabelled glyphs is the problem the
 * dock's size limit was meant to avoid, not a way around it. Escape (or a click on the backdrop)
 * closes it without navigating.
 *
 * ITS OWN WINDOW, AND THAT IS THE POINT. Chromium in the browser page renders into a heavyweight
 * AWT component, which always paints above Compose content in the same window -- the Browser page
 * collapses its own overlays to zero size for exactly this reason. A full-window Launchpad drawn
 * inside the window would be covered by a live page: the grid invisible, and clicks over it going
 * to the website. A separate undecorated window is above that component by construction, and a
 * full-screen Launchpad is what macOS shows anyway.
 */
@Composable
private fun LaunchpadWindow(
    selected: PageId,
    onSelect: (PageId) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val screen = remember { java.awt.Toolkit.getDefaultToolkit().screenSize }
    val width = with(density) { screen.width.toDp() }
    val height = with(density) { screen.height.toDp() }
    val state = rememberWindowState(
        position = WindowPosition(0.dp, 0.dp),
        size = WindowSize(width, height),
    )

    // A PLAIN WINDOW, NOT A DialogWindow. A dialog is owned by the Prism window, and on Windows
    // closing one minimises its owner -- choosing a page from Launchpad sent the whole application
    // to the taskbar. A second top-level window has no such relationship: it closes, and the
    // window underneath is exactly where it was.
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Launchpad",
        undecorated = true,
        resizable = false,
    ) {
        // A LAUNCHPAD BEHIND THE WINDOW IS A TRAP: the flag would still say open, and the dock's
        // Launchpad icon would then close something invisible before opening it again. So focus
        // leaving -- clicking another application -- dismisses it. Guarded by "has been focused
        // once", because a window is not focused yet on its first frame.
        val windowInfo = LocalWindowInfo.current
        val focused = windowInfo.isWindowFocused
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(focused) {
            if (focused) everFocused = true else if (everFocused) onClose()
        }

        // A window is its own composition, so the theme's locals do not cross into it.
        PrismTheme { LaunchpadGrid(selected, onSelect, onClose) }
    }
}

@Composable
private fun LaunchpadGrid(
    selected: PageId,
    onSelect: (PageId) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose(); true
                } else false
            }
            .clickableRow(onClose),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 36.dp)
                // Swallowed, so a click inside the panel is not a click on the backdrop.
                .clickableRow { }
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Launchpad", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("Esc to close", fontSize = 11.sp, color = colors.muted)
            }

            // The same split the rail used: Nora's own screens, then everything else. AETHER is
            // filed with System because it is a second assistant's settings, not a Nora page.
            SectionHeader("Nora")
            PageGrid(
                pages = PageId.entries.filter { it.ordinal <= PageId.NORA_SETTINGS.ordinal },
                selected = selected,
                onSelect = onSelect,
            )
            SectionHeader("System")
            PageGrid(
                pages = PageId.entries.filter { it.ordinal > PageId.NORA_SETTINGS.ordinal },
                selected = selected,
                onSelect = onSelect,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Tiles wrap to the window: as many fixed-width columns as fit, at least three. */
@Composable
private fun PageGrid(pages: List<PageId>, selected: PageId, onSelect: (PageId) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 108.dp).toInt().coerceIn(3, 12)
        Column {
            pages.chunked(columns).forEach { row ->
                Row {
                    row.forEach { page ->
                        PageTile(page, page == selected) { onSelect(page) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTile(page: PageId, current: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .width(108.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) colors.surfaceRaised else Color.Transparent)
            .hoverable(interaction)
            .clickableRow(onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (current) colors.accent else colors.surface,
            shape = RoundedCornerShape(12.dp),
            border = if (colors.border > 0 && !current) BorderStroke(1.dp, colors.divider) else null,
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    page.icon, null,
                    tint = if (current) Color.White else colors.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            page.label,
            fontSize = 11.sp,
            color = if (current) colors.accent else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
