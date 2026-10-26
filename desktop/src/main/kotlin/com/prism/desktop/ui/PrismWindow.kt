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
import com.prism.launcher.SlotAssignment
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
 * THREE SHELLS OVER THE SAME PAGES.
 *
 *   WORKBENCH (default)  a persistent rail. Every page reachable in one click, which is what a
 *                        mouse is good at and what a desktop user expects.
 *
 *   MACOS (xOS)         [MacOsShell] -- no rail. The pages are icons in a taskbar along the
 *                        bottom, the app pins and predictions ride in the same strip, and
 *                        Launchpad carries the pages the dock has no room for. See that file for
 *                        why this is the xOS interpretation rather than a setting of its own.
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
    // THE PHONE-STYLE NAVIGATION SWITCH IS GONE, and that is deliberate: it now replaces the
    // desktop shell instead of choosing a phone-shaped window. The workbench and the xOS dock are
    // the two presentations left, and which one shows is decided by the theme.
    val launcherMode = false

    // A display follows the workbench too, not only the launcher pager. In this shell the pages
    // are the rail, so "the next page" is the next entry.
    LaunchedEffect(selected) {
        val pages = PageId.entries.filter { it.ported }
        val slots = pages.map { railSlot(it).serialize() }
        val index = pages.indexOf(selected).coerceAtLeast(0)
        // The rail page, not only its slot. Several rail pages share the Default slot, so a
        // display told only "Default" drew the desktop grid for whichever page came next. The
        // whole list travels because extend names the page AFTER the current one.
        com.prism.launcher.display.WirelessDisplay.notePage(
            slots,
            index,
            pages = pages.map { it.name },
        )
    }

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

            // THE FIRST-RUN TOUR (PHASE 109), window-wide for the same reason the watcher is: it has to cover
            // whatever page Prism opened on, and it has to appear exactly once regardless of navigation.
            var tourDone by remember { mutableStateOf(com.prism.launcher.onboarding.OnboardingTour.seen()) }
            if (!tourDone) {
                OnboardingOverlay(onDone = { tourDone = true })
            }

            Surface(color = MaterialTheme.colorScheme.background) {
                // THE THIRD SHELL. xOS on a desktop is macOS, and macOS does not put a list of
                // everything down the left edge: it puts the things you launch in a taskbar at
                // the bottom. [MacOsShell] is that -- the pages as dock icons, the full list one
                // click away in Launchpad -- and it replaces the rail only for xOS, so every
                // other theme keeps the workbench it was designed around.
                val macOs = LocalMacOsLook.current
                val onLauncherModeChange: (Boolean) -> Unit = {}
                when {
                    macOs -> MacOsShell(
                        selected = selected,
                        onSelect = { selected = it },
                        launcherMode = launcherMode,
                        onLauncherModeChange = onLauncherModeChange,
                        onNavigate = { selected = it },
                    )
                    else -> Row(Modifier.fillMaxSize()) {
                        NavigationRail(selected = selected, onSelect = { selected = it })
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            PageBody(
                                page = selected,
                                launcherMode = launcherMode,
                                onLauncherModeChange = onLauncherModeChange,
                                // Lets a page move the window. Only the thread list uses it so far, and
                                // it is a callback rather than a shared navigation object because one
                                // consumer does not justify a router.
                                onNavigate = { selected = it },
                            )
                        }
                    }
                }
            }

            // AN INCOMING MESH CALL, above every page and below the lock. It has to be here rather
            // than on the dialer page because the call arrives while the user is somewhere else --
            // and a call nobody can answer is a call nobody could see. See DesktopIncomingCalls.
            com.prism.desktop.IncomingCallBanner()

            // THE LOCK (PHASE 101), mounted LAST so it draws over everything including the trust
            // watcher and the tour. A lock that a consent dialog could appear on top of would be a
            // lock somebody could be talked past.
            //
            // IT USED TO BE FIRST, which put it UNDER the full-window Surface: the window painted
            // over the lock and only a locked-looking flash on startup revealed it existed. Compose
            // stacks siblings in the order they are emitted, so "last" has to mean last in source
            // order, not first with a comment claiming otherwise.
            //
            // It locks Prism and not the computer, and the overlay says so in those words. See
            // PrismLockScope for the decision and its caveats.
            LockOverlay()
        }
    }
}

/** The page itself, identical in both shells -- only the chrome around it differs. */
@Composable
internal fun PageBody(
    page: PageId,
    launcherMode: Boolean,
    onLauncherModeChange: (Boolean) -> Unit,
    onNavigate: (PageId) -> Unit = {},
) {
    // Re-read on navigation rather than only at start-up: the switch lives on the Settings page,
    // and the page somebody goes to after turning it on should already be windowed. Keyed by the
    // page because that is the event -- a settings change is observed the next time a page opens.
    val windowed = remember(page) { PrismSettings.getWindowedPages() }
    if (windowed) {
        PageWindow(page) {
            PageContent(page, launcherMode, onLauncherModeChange, onNavigate)
        }
    } else {
        PageContent(page, launcherMode, onLauncherModeChange, onNavigate)
    }
}

/**
 * A page drawn as a window in the active theme's surfaces.
 *
 * The "desktop around it" is the shell's own background; this only has to provide the frame. Its
 * corners, card colour, hairline and accent dot all come from [LocalPrismColors], so a windowed
 * page does not become a fourth thing to theme.
 *
 * IT IS THE SAME PAGE. Nothing in the body is conditional on being framed -- every page already
 * lays itself out for whatever space it is given, and a page that needed a different layout inside
 * a window would be a second implementation with two sets of bugs.
 */
@Composable
private fun PageWindow(page: PageId, content: @Composable () -> Unit) {
    val colors = LocalPrismColors.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Surface(
            color = colors.card,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(colors.cardRadius),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(colors.accent, RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        page.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("window", fontSize = 10.sp, color = colors.faint)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** The page itself, laid out for the space the shell or the window gives it. */
@Composable
private fun PageContent(
    page: PageId,
    launcherMode: Boolean,
    onLauncherModeChange: (Boolean) -> Unit,
    onNavigate: (PageId) -> Unit,
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
        PageId.MESSAGES_PAGE -> MessagesPage(
            onOpenThread = { requested -> onNavigate(requested) },
            onOpenDialer = { onNavigate(PageId.DIALER) },
        )
        // Calling and location: pages of their own, not screens buried in Messages. Mesh calls
        // need no phone plan, and the map only ever draws positions a trusted device chose to
        // share with this one.
        PageId.DIALER -> DialerPage()
        PageId.MESHVOICE -> MeshvoicePage()
        PageId.MAPS -> MapsPage()
        PageId.MOUSE -> MousePage()
        PageId.PHOTOS -> PhotosPage()
        PageId.PHOTOPIX -> PhotoPixPage()
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
        PageId.KOKORO -> KokoroSettingsPage(onNavigate)
        PageId.VOICE_CLONING -> VoiceCloningPage(onNavigate)
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
        PageId.SETTINGS -> PrismSettingsPage()
        PageId.DIAGNOSTICS -> DiagnosticsPage()
        else -> NotYetPortedPage(page)
    }
}

/**
 * The slot a rail page corresponds to, so a display can render it.
 *
 * One-way deliberately: a slot knows which page to draw, a rail page is a navigation entry. Pages
 * with no slot equivalent (Nora's screens, diagnostics) fall back to the desktop grid rather than
 * inventing a tag the receiver would not understand.
 */
private fun railSlot(page: PageId): SlotAssignment = when (page) {
    PageId.BROWSER -> SlotAssignment.Browser
    PageId.DESKTOP -> SlotAssignment.DesktopGrid
    PageId.APPS -> SlotAssignment.AppDrawer
    PageId.FILES -> SlotAssignment.FileExplorer
    PageId.MESSAGES_PAGE, PageId.SAM -> SlotAssignment.Messaging
    PageId.NEBULA -> SlotAssignment.NebulaSocial
    PageId.MODELS -> SlotAssignment.Models
    PageId.MODEL_SHOP -> SlotAssignment.ModelStore
    PageId.AGENTIC -> SlotAssignment.AgenticTools
    PageId.WALLET, PageId.WALLET_EXTRAS -> SlotAssignment.Wallet
    PageId.EDITOR -> SlotAssignment.Editor
    PageId.SCIENCE, PageId.PROTEINS -> SlotAssignment.Science
    PageId.NOTIFICATIONS -> SlotAssignment.Notifications
    PageId.LANGUAGE -> SlotAssignment.Language
    PageId.GAMES, PageId.GAME_HOST -> SlotAssignment.Minigames
    PageId.CLOUD_AI -> SlotAssignment.Cloud
    PageId.MESHVOICE, PageId.DIALER -> SlotAssignment.Meshvoice
    PageId.MAPS -> SlotAssignment.Maps
    PageId.MOUSE -> SlotAssignment.Mouse
    PageId.PHOTOS -> SlotAssignment.Photos
    PageId.PHOTOPIX -> SlotAssignment.PhotoPix
    PageId.VIRTUALIZATION -> SlotAssignment.VirtualizationOs
    else -> SlotAssignment.Default
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
    DIALER("Dialer", Icons.Filled.Phone),
    MESHVOICE("Meshvoice", Icons.Filled.Voicemail),
    MAPS("Maps", Icons.Filled.Map),
    MOUSE("Mouse", Icons.Filled.TouchApp),
    PHOTOS("Photos", Icons.Filled.PhotoLibrary),
    PHOTOPIX("PhotoPix", Icons.Filled.Brush),
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

    MESH("Mesh", Icons.Filled.Hub),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    QUANTIZE("Quantize", Icons.Filled.Compress),
    PLUGINS("Plugins", Icons.Filled.Extension),
    CHARACTERS("Characters", Icons.Filled.Face),
    STREMIO("Stremio", Icons.Filled.Movie),
    SPEECH("Speech", Icons.Filled.RecordVoiceOver),
    // Kokoro's whole screen and the cloning studio it opens, the way the phone keeps them.
    KOKORO("Kokoro speech", Icons.Filled.Speaker),
    VOICE_CLONING("Clone a voice", Icons.Filled.Mic),
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
    val colors = LocalPrismColors.current
    // macOS keeps a sidebar that is its own tinted surface, not the window colour. That, and the
    // sentence-case headings, are the parts of the look a palette entry cannot express.
    val macOs = LocalMacOsLook.current
    Column(
        Modifier
            .width(if (macOs) 224.dp else 232.dp)
            .fillMaxHeight()
            .background(if (macOs) colors.surface else colors.background)
            // SCROLLABLE ON PURPOSE. The rail lists every page including the unported ones, which
            // is already ~750dp against ~808dp of usable height in the default 840dp window --
            // it fits, but only just, and a Column simply clips whatever does not. So any window
            // the user makes shorter, any display scaling above 100%, or any page added later
            // silently amputates the bottom entries with no scrollbar to suggest they exist.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        Row(
            Modifier.padding(
                start = if (macOs) 17.dp else 20.dp,
                end = 20.dp,
                top = if (macOs) 12.dp else 14.dp,
                bottom = if (macOs) 10.dp else 14.dp,
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Hexagon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (macOs) 23.dp else 26.dp)
            )
            Spacer(Modifier.width(if (macOs) 8.dp else 10.dp))
            Text("Prism", fontSize = if (macOs) 18.sp else 21.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(if (macOs) 4.dp else 6.dp))
        RailSection(if (macOs) "Nora" else "NORA")
        PageId.entries.filter { it.ported && it.ordinal <= PageId.NORA_SETTINGS.ordinal }
            .forEach { RailItem(it, selected, onSelect) }

        Spacer(Modifier.height(if (macOs) 6.dp else 10.dp))
        RailSection(if (macOs) "System" else "SYSTEM")
        PageId.entries.filter { it.ported && it.ordinal > PageId.NORA_SETTINGS.ordinal }
            .forEach { RailItem(it, selected, onSelect) }

        Spacer(Modifier.height(if (macOs) 6.dp else 10.dp))
        RailSection(if (macOs) "Not yet ported" else "NOT YET PORTED")
        PageId.entries.filter { !it.ported }.forEach { RailItem(it, selected, onSelect) }
    }
}

@Composable
private fun RailSection(title: String) {
    val macOs = LocalMacOsLook.current
    Text(
        title,
        fontSize = if (macOs) 11.sp else 10.sp,
        letterSpacing = if (macOs) 0.sp else 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalPrismColors.current.muted,
        modifier = Modifier.padding(
            start = if (macOs) 18.dp else 22.dp,
            top = if (macOs) 10.dp else 8.dp,
            bottom = if (macOs) 4.dp else 4.dp,
        )
    )
}

@Composable
private fun RailItem(page: PageId, selected: PageId, onSelect: (PageId) -> Unit) {
    val colors = LocalPrismColors.current
    val macOs = LocalMacOsLook.current
    val active = page == selected
    // macOS selects with a solid accent, not a raised grey. White on the accent is the system's
    // own pairing and stays legible at both of xOS's appearances.
    val tint = when {
        active -> if (macOs) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        macOs -> colors.onSurface
        page.ported -> colors.muted
        else -> colors.faint
    }
    Surface(
        color = when {
            active && macOs -> colors.accent
            active -> colors.surfaceRaised
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(if (macOs) 6.dp else 10.dp),
        modifier = Modifier
            .padding(
                horizontal = if (macOs) 8.dp else 10.dp,
                vertical = if (macOs) 0.dp else 1.dp,
            )
            .fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableRow { onSelect(page) }
                .padding(
                    horizontal = if (macOs) 9.dp else 12.dp,
                    vertical = if (macOs) 7.dp else 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(page.icon, null, tint = tint, modifier = Modifier.size(if (macOs) 16.dp else 19.dp))
            Spacer(Modifier.width(if (macOs) 8.dp else 12.dp))
            Text(page.label, fontSize = if (macOs) 13.sp else 14.sp, color = tint)
        }
    }
}

@Composable
private fun NotYetPortedPage(page: PageId) {
    val colors = LocalPrismColors.current
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            page.icon, null,
            tint = colors.divider,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(page.label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            page.blocker,
            fontSize = 14.sp,
            color = colors.muted,
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
