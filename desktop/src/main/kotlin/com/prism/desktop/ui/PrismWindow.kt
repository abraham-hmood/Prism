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
import androidx.compose.material.icons.automirrored.filled.Chat
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
fun PrismWindow(startOn: PageId = PageId.NORA_CHAT) {
    // The starting page is a parameter so a page can be opened directly, which is how a Compose page gets
    // checked without a human driving the rail -- `prism gui nebula` opens on it.
    var selected by remember { mutableStateOf(startOn) }

    // Settings rows that open another page need a way to change the selection, and this composable is
    // the only thing that owns it. Installed as a side effect rather than threaded through the page's
    // signature, because the rows are built by a catalog in :core that must not know about PageId.
    androidx.compose.runtime.SideEffect {
        SettingsActions.navigate = { page -> selected = page }
    }
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
            // Mounted for the whole window, outside either shell: a trust offer is a consent question
            // that has to be asked wherever the user happens to be, including on a page that knows
            // nothing about pairing. See TrustedDeviceOfferWatcher.
            TrustedDeviceOfferWatcher()

            // THE LOCK (PHASE 101), mounted LAST so it draws over everything including the trust
            // watcher and the tour. A lock that a consent dialog could appear on top of would be a
            // lock somebody could be talked past.
            //
            // It locks Prism and not the computer, and the overlay says so in those words. See
            // PrismLockScope for the decision and its caveats.
            LockOverlay()

        // THE FIRST-RUN TOUR (PHASE 109), window-wide for the same reason the watcher is: it has to cover
        // whatever page Prism opened on, and it has to appear exactly once regardless of navigation.
        var tourDone by remember { mutableStateOf(com.prism.launcher.onboarding.OnboardingTour.seen()) }
        if (!tourDone) {
            OnboardingOverlay(onDone = { tourDone = true })
        }

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
                            PageBody(
                                page = selected,
                                launcherMode = launcherMode,
                                onLauncherModeChange = {
                                    launcherMode = it
                                    PrismSettings.setDesktopMobileMode(it)
                                },
                                // Lets a page move the window. Only the thread list uses it so far, and
                                // it is a callback rather than a shared navigation object because one
                                // consumer does not justify a router.
                                onNavigate = { selected = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The page itself, identical in both shells -- only the chrome around it differs. */
@Composable
private fun PageBody(
    page: PageId,
    launcherMode: Boolean,
    onLauncherModeChange: (Boolean) -> Unit,
    onNavigate: (PageId) -> Unit = {},
) {
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
        PageId.IMAGE_GEN -> ImageGenPage()
        PageId.SAM -> ConversationPage()
        PageId.MESSAGES_PAGE -> MessagesPage(onOpenThread = { requested -> onNavigate(requested) })
        PageId.NORA_TOOLS -> NoraExtrasPage()
        PageId.NEBULA -> NebulaPage()
        PageId.TRUSTED -> TrustedDevicesPage()
        PageId.WALLET -> WalletPage()
        PageId.WALLET_EXTRAS -> WalletExtrasPage()
        PageId.SEARCH -> SearchPage()
        PageId.ACCESS_POINT -> AccessPointPage()
        PageId.TUNNEL -> TunnelPage()
        PageId.MESH -> MeshPage()
        PageId.NOTIFICATIONS -> NotificationsPage()
        PageId.QUANTIZE -> QuantizePage()
        PageId.PLUGINS -> PluginsPage()
        PageId.CHARACTERS -> CharactersPage()
        PageId.STREMIO -> StremioPage()
        PageId.SPEECH -> SpeechPage()
        PageId.LANGUAGE -> LanguagePage()
        PageId.GAMES -> MinigamesPage()
        PageId.EDITOR -> EditorPage()
        PageId.SCIENCE -> SciencePage()
        PageId.PROTEINS -> ProteinPage()
        PageId.LOCK -> LockPage()
        PageId.LYKE -> LykePage()
        PageId.WRITER -> WriterPage()
        PageId.MODEL_SHOP -> ModelShopPage()
        PageId.NODES -> NodesPage()
        PageId.GAME_HOST -> GameHostPage()
        PageId.VIRTUALIZATION -> VirtualizationPage()
        PageId.AETHER -> AetherPage()
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
    AETHER("Aether", Icons.Filled.Hub),

    DESKTOP("Desktop", Icons.Filled.GridView),
    BROWSER("Browser", Icons.Filled.Public),
    CLOUD_AI("Cloud AI", Icons.Filled.Cloud),
    BLOCKLIST("Blocked domains", Icons.Filled.Block),
    AGENTIC("Agentic Tools", Icons.Filled.Build),
    MODELS("Models", Icons.Filled.Inventory2),
    IMAGE_GEN("Image generation", Icons.Filled.Brush),
    SAM("Sam", Icons.AutoMirrored.Filled.Chat),
    MESSAGES_PAGE("Messages", Icons.Filled.Forum),
    NEBULA("Nebula", Icons.Filled.Groups),
    TRUSTED("Trusted devices", Icons.Filled.Devices),
    WALLET("Wallet", Icons.Filled.AccountBalanceWallet),
    WALLET_EXTRAS("Wallet extras", Icons.Filled.Savings),
    SEARCH("Search", Icons.Filled.Search),
    ACCESS_POINT("Access Point", Icons.Filled.Wifi),
    TUNNEL("Tunnel", Icons.Filled.VpnKey),
    VIRTUALIZATION("Virtualization", Icons.Filled.Computer),
    NORA_TOOLS("Nora tools", Icons.Filled.Psychology),
    APPS("Apps", Icons.Filled.Apps),
    FILES("Files", Icons.Filled.Folder),
    SETTINGS("Prism Settings", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Terminal),

    MESSAGES(
        "Messages", Icons.AutoMirrored.Filled.Chat, ported = false,
        blocker = "AI conversations port directly. SMS/MMS does not exist on desktop -- Windows " +
            "has no public API and Linux needs a cellular modem. Messages here will be AI-only."
    ),
    MESH("Mesh", Icons.Filled.Hub),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    QUANTIZE("Quantize", Icons.Filled.Compress),
    PLUGINS("Plugins", Icons.Filled.Extension),
    CHARACTERS("Characters", Icons.Filled.Face),
    STREMIO("Stremio", Icons.Filled.Movie),
    SPEECH("Speech", Icons.Filled.RecordVoiceOver),
    LANGUAGE("Language", Icons.Filled.Translate),
    GAMES("Games", Icons.Filled.SportsEsports),
    EDITOR("Editor", Icons.Filled.Code),
    SCIENCE("Science", Icons.Filled.Biotech),
    PROTEINS("Proteins", Icons.Filled.Hub),
    LOCK("Lock", Icons.Filled.Lock),
    LYKE("Lyke", Icons.Filled.Videocam),
    WRITER("Prism Writer", Icons.Filled.Keyboard),
    MODEL_SHOP("Model market", Icons.Filled.Storefront),
    NODES("Nodes", Icons.Filled.AccountTree),
    GAME_HOST("Game host", Icons.Filled.SportsEsports),
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
