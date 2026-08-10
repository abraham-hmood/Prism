package com.prism.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.launch

/**
 * Prism's desktop shell.
 *
 * MIRRORS THE ANDROID PAGE-SLOT MODEL rather than inventing a desktop-native navigation scheme.
 * On Android, Prism is a horizontal pager whose slots the user assigns to pages -- Desktop,
 * Drawer, Browser, Messages, Files, Nebula, Nora, Models, Agentic Tools. The pages are the
 * product, and reshuffling them into a conventional desktop menu here would make the two builds
 * different applications that happen to share a name.
 *
 * TWO SHELLS OVER THE SAME PAGES.
 *
 *   WORKBENCH (default)  a persistent rail. Every page reachable in one click, which is what a
 *                        mouse is good at and what a desktop user expects.
 *
 *   LAUNCHER             [LauncherShell] -- the Android navigation model: a horizontal pager over
 *                        user-assignable slots read from the same `prism_slots` store the phone
 *                        writes, paged with the arrow keys. Full window width; this is the
 *                        interaction model ported, not a phone-shaped window.
 *
 * Neither is a lesser version of the other, which is why it is a setting rather than a default
 * someone has to work around.
 *
 * THE DISABLED RAIL ENTRIES ARE DELIBERATE. Pages that are not yet ported appear, greyed, with the
 * reason. A shell that silently omits its unported pages implies the port is further along than it
 * is; one that shows what is missing and why is a status report you can act on.
 */
@Composable
fun PrismWindow() {
    var selected by remember { mutableStateOf(PageId.NORA_CHAT) }
    // Read once into state rather than polled: the setting is changed from inside this window, so
    // the toggle updates it directly and there is nothing else that could change it underneath.
    var launcherMode by remember { mutableStateOf(PrismSettings.getDesktopMobileMode()) }

    // One drag controller and one icon cache for the whole window. Both have to outlive any
    // single page: a drag starts in the drawer and ends on the desktop, and the same
    // applications are drawn by three different pages.
    val dragController = remember { DragController() }
    val iconCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    // PrismTheme, not a hardcoded scheme: it reads `glow_color` and `font_style` from the same
    // settings the Android build uses, so a user's accent colour and chosen font carry across.
    PrismTheme {
        CompositionLocalProvider(
            LocalDragController provides dragController,
            LocalIconCache provides iconCache,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                if (launcherMode) {
                    LauncherShell(onExit = {
                        launcherMode = false
                        PrismSettings.setDesktopMobileMode(false)
                    })
                } else {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(selected = selected, onSelect = { selected = it })
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            PageBody(selected, launcherMode) {
                                launcherMode = it
                                PrismSettings.setDesktopMobileMode(it)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page itself, identical in both shells -- only the chrome around it differs. */
@Composable
private fun PageBody(page: PageId, launcherMode: Boolean, onLauncherModeChange: (Boolean) -> Unit) {
    when (page) {
        PageId.NORA_CHAT -> NoraChatPage()
        PageId.NORA_TRAIN -> NoraTrainingPage()
        PageId.NORA_SETTINGS -> NoraSettingsPage()
        PageId.MODEL_TEST -> ModelTestPage()
        // The same grid and the same predicted hotseat the launcher shell shows, so the feature
        // is not conditional on which shell you prefer.
        PageId.DESKTOP -> DesktopGridPage(pageIndex = 1)
        PageId.BROWSER -> BrowserPage()
        PageId.CLOUD_AI -> CloudAiPage()
        PageId.BLOCKLIST -> BlocklistPage()
        PageId.AGENTIC -> AgenticToolsPage()
        PageId.MODELS -> ModelsPage()
        PageId.APPS -> AppDrawerPage()
        PageId.FILES -> FileExplorerPage()
        PageId.SETTINGS -> PrismSettingsPage(launcherMode, onLauncherModeChange)
        PageId.DIAGNOSTICS -> DiagnosticsPage()
        else -> NotYetPortedPage(page)
    }
}

enum class PageId(
    val label: String,
    val icon: ImageVector,
    val ported: Boolean = true,
    /** Why it is not here yet. Shown on the placeholder, so the gap is legible. */
    val blocker: String = ""
) {
    NORA_CHAT("Nora", Icons.Filled.AutoAwesome),
    NORA_TRAIN("Training", Icons.Filled.School),
    MODEL_TEST("Model Test", Icons.Filled.Science),
    NORA_SETTINGS("Nora Settings", Icons.Filled.Tune),

    DESKTOP("Desktop", Icons.Filled.GridView),
    BROWSER("Browser", Icons.Filled.Public),
    CLOUD_AI("Cloud AI", Icons.Filled.Cloud),
    BLOCKLIST("Blocked domains", Icons.Filled.Block),
    AGENTIC("Agentic Tools", Icons.Filled.Build),
    MODELS("Models", Icons.Filled.Inventory2),
    APPS("Apps", Icons.Filled.Apps),
    FILES("Files", Icons.Filled.Folder),
    SETTINGS("Prism Settings", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Terminal),

    MESSAGES(
        "Messages", Icons.Filled.Chat, ported = false,
        blocker = "AI conversations port directly. SMS/MMS does not exist on desktop -- Windows " +
            "has no public API and Linux needs a cellular modem. Messages here will be AI-only."
    ),
    NEBULA(
        "Nebula", Icons.Filled.Groups, ported = false,
        blocker = "Storage is done -- the Room schema runs on desktop, so the feed, bots, " +
            "comments and DMs all read and write here. What is left is the UI: " +
            "NebulaSocialPageView is 675 lines of imperative Android views."
    ),
    MESH(
        "Mesh", Icons.Filled.Hub, ported = false,
        blocker = "The gossip protocol is plain UDP and its JSON is portable now; the VPN tunnel " +
            "needs WinTun on Windows and CAP_NET_ADMIN on Linux."
    ),
    ACCESS_POINT(
        "Access Point", Icons.Filled.Wifi, ported = false,
        blocker = "LocalOnlyHotspot has no direct equivalent. Windows uses netsh wlan " +
            "hostednetwork, Linux uses nmcli or hostapd -- different implementations, same feature."
    ),
    VIRTUALIZATION(
        "Virtualization", Icons.Filled.Computer, ported = false,
        blocker = "Android Virtualization Framework is Android-only. Desktop would use QEMU or " +
            "libvirt directly, which is a reimplementation rather than a port."
    );
}

@Composable
private fun NavigationRail(selected: PageId, onSelect: (PageId) -> Unit) {
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(Color(0xFF121215))
            // SCROLLABLE ON PURPOSE. The rail lists every page including the unported ones, which
            // is already ~750dp against ~808dp of usable height in the default 840dp window --
            // it fits, but only just, and a Column simply clips whatever does not. So any window
            // the user makes shorter, any display scaling above 100%, or any page added later
            // silently amputates the bottom entries with no scrollbar to suggest they exist.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Hexagon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("Prism", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(6.dp))
        RailSection("NORA")
        PageId.entries.filter { it.ported && it.ordinal <= PageId.NORA_SETTINGS.ordinal }
            .forEach { RailItem(it, selected, onSelect) }

        Spacer(Modifier.height(10.dp))
        RailSection("SYSTEM")
        PageId.entries.filter { it.ported && it.ordinal > PageId.NORA_SETTINGS.ordinal }
            .forEach { RailItem(it, selected, onSelect) }

        Spacer(Modifier.height(10.dp))
        RailSection("NOT YET PORTED")
        PageId.entries.filter { !it.ported }.forEach { RailItem(it, selected, onSelect) }
    }
}

@Composable
private fun RailSection(title: String) {
    Text(
        title,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF6C6C78),
        modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun RailItem(page: PageId, selected: PageId, onSelect: (PageId) -> Unit) {
    val active = page == selected
    val tint = when {
        active -> MaterialTheme.colorScheme.primary
        page.ported -> Color(0xFFB9B9C4)
        else -> Color(0xFF55555F)
    }
    Surface(
        color = if (active) Color(0xFF23232B) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableRow { onSelect(page) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(page.icon, null, tint = tint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Text(page.label, fontSize = 14.sp, color = tint)
        }
    }
}

@Composable
private fun NotYetPortedPage(page: PageId) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            page.icon, null,
            tint = Color(0xFF44444E),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(page.label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            page.blocker,
            fontSize = 14.sp,
            color = Color(0xFF9A9AA6),
            modifier = Modifier.widthIn(max = 520.dp)
        )
    }
}

fun prismDarkColors() = darkColorScheme(
    primary = Color(0xFF7C6CFF),
    onPrimary = Color.White,
    background = Color(0xFF0D0D10),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFFB9B9C4)
)
