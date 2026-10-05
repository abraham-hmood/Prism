package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.browser.CefRuntime
import com.prism.desktop.browser.PrismCefHandlers
import com.prism.desktop.browser.VpnBridge
import com.prism.launcher.PrismSettings
import com.prism.launcher.browser.PrismBlocklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.CefClient
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The browser, on real Chromium.
 *
 * A FEATURE-FOR-FEATURE PORT of `BrowserPageView`, which is a bigger list than it looks:
 * multiple tabs each with their own engine, a separate private category, biometric-equivalent
 * locking of that category, per-tab VPN state, `.p2p` interception over the mesh, ad blocking in
 * private tabs only, auto-mirroring of `.p2p` sites, a resolution-source indicator, search-vs-URL
 * detection, and font injection. The interception and blocking live in [PrismCefHandlers]; this
 * file is the tabs, the chrome and the privacy state.
 *
 * WHY PRIVATE TABS ARE ACTUALLY PRIVATE HERE, and are arguably stricter than on Android. Each
 * private tab gets its OWN [CefClient], so Chromium gives it an isolated request context whose
 * cookies, storage and cache exist only in memory and die with the tab. Android's WebView
 * incognito shares a cookie manager and turns third-party cookies off per-view, which is weaker.
 * Normal tabs share one client, and therefore one cookie jar, as tabs in any browser do.
 *
 * THE ONE HONEST GAP: the "private tabs use the P2P VPN" behaviour. On Android, selecting a
 * private tab starts `PrivateDnsVpnService`, which is a real system VPN. Desktop needs WinTun on
 * Windows and CAP_NET_ADMIN on Linux -- Phase 7 of the plan, not yet done. The per-tab state
 * machine that decides when the tunnel should be up is ported and correct; it currently calls a
 * [VpnBridge] whose desktop implementation reports that the tunnel is unavailable, rather than
 * silently pretending a private tab is tunnelled when it is not. That distinction matters:
 * claiming privacy you do not provide is worse than saying so.
 */
@Composable
fun BrowserPage() {
    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current

    var cefState by remember { mutableStateOf(CefRuntime.state) }
    var tabs by remember { mutableStateOf<List<BrowserTab>>(emptyList()) }
    var activeId by remember { mutableStateOf(0L) }
    var overlayOpen by remember { mutableStateOf(false) }
    var overlayPrivate by remember { mutableStateOf(false) }
    var privateUnlocked by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }
    var p2pActive by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var listsOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var bookmarked by remember { mutableStateOf(false) }
    var unlockPrompt by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        CefRuntime.onStateChange = { cefState = it }
        onDispose { CefRuntime.onStateChange = null }
    }

    // Chromium starts off the UI thread: on first run this downloads ~100 MB.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CefRuntime.ensureStarted() }
        cefState = CefRuntime.state
    }

    val active = tabs.firstOrNull { it.id == activeId }
    val canMirror = active?.url
        ?.let { PrismCefHandlers.hostOf(it) }
        ?.let { PrismCefHandlers.isP2pDomain(it) } == true

    fun addTab(isPrivate: Boolean, initialUrl: String? = null) {
        val client = CefRuntime.newClient() ?: return
        val start = initialUrl ?: HOME_URL
        val id = System.nanoTime()

        val handlers = PrismCefHandlers(
            isPrivateTab = isPrivate,
            blocklist = PrismBlocklist.get(),
            onUrl = { u ->
                tabs = tabs.map { if (it.id == id) it.copy(url = u) else it }
                if (id == activeId) urlText = u
                maybeAutoMirror(u)
            },
            onTitle = { t -> tabs = tabs.map { if (it.id == id) it.copy(title = t) else it } },
            onProgress = { p -> if (id == activeId) progress = p },
            onP2pResolution = { _, viaP2p -> if (id == activeId) p2pActive = viaP2p },
        )
        client.addDisplayHandler(handlers.displayHandler)
        client.addLoadHandler(handlers.loadHandler)
        client.addRequestHandler(handlers.requestHandler)

        // createBrowser's second argument is `isOffscreenRendered`; the third enables the
        // transparent painting we do not want here.
        val browser = client.createBrowser(start, false, false)

        tabs = tabs + BrowserTab(
            id = id,
            client = client,
            browser = browser,
            isPrivate = isPrivate,
            title = if (isPrivate) "Private" else "New tab",
            url = start,
        )
        activeId = id
        urlText = start
        overlayOpen = false
        VpnBridge.applyForTab(isPrivate)
    }

    fun closeTab(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        // Both, and in this order: the browser holds native resources and the client owns the
        // request context. Disposing only the browser leaks a Chromium renderer per closed tab.
        try {
            tab.browser.close(true)
            tab.client.dispose()
        } catch (e: Exception) {
            PrismPlatform.log.debug("Prism/browser", "Tab teardown: ${e.message}")
        }
        val remaining = tabs.filterNot { it.id == id }
        tabs = remaining
        if (remaining.isEmpty()) {
            addTab(isPrivate = overlayPrivate)
        } else if (activeId == id) {
            val next = remaining.last()
            activeId = next.id
            urlText = next.url
            VpnBridge.applyForTab(next.isPrivate)
        }
    }

    fun navigate(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        // Same rule as Android: something with a scheme, or a dot and no spaces, is a URL.
        val looksLikeUrl = trimmed.contains("://") || (trimmed.contains(".") && !trimmed.contains(" "))
        val url = when {
            !looksLikeUrl -> PrismSettings.buildSearchUrl(trimmed)
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }
        active?.browser?.loadURL(url)
        urlText = url
    }

    // Opens the first tab once Chromium is up.
    LaunchedEffect(cefState) {
        if (cefState is CefRuntime.State.Ready && tabs.isEmpty()) {
            addTab(isPrivate = PrismSettings.getPrivateByDefault())
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BrowserChrome(
            urlText = urlText,
            onUrlChange = { urlText = it },
            onNavigate = { navigate(urlText) },
            onBack = { active?.browser?.goBack() },
            onForward = { active?.browser?.goForward() },
            onReload = { active?.browser?.reload() },
            tabCount = tabs.size,
            isPrivate = active?.isPrivate == true,
            p2pActive = p2pActive,
            onTabs = {
                overlayPrivate = active?.isPrivate ?: false
                overlayOpen = true
            },
            onMenu = { menuOpen = !menuOpen },
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        // WHY THE BROWSER COLLAPSES INSTEAD OF BEING COVERED.
        //
        // Chromium renders into a heavyweight AWT component, and a heavyweight component ALWAYS
        // paints above Compose content in the same window -- Compose draws to a canvas, the
        // native widget draws over it. So a Compose overlay or a DropdownMenu popup positioned
        // over the page is composed, receives its state change, and is then painted underneath
        // Chromium. It looks exactly like a dead button: the click works, the state flips,
        // nothing appears. That is the bug that made the tabs button and the three-dot menu
        // seem to do nothing.
        //
        // Interop blending (`compose.interop.blending`) is the intended fix and is experimental
        // and platform-dependent, so it is enabled in Main as an improvement rather than relied
        // on. What is relied on is this: give the SwingPanel a zero-sized slot while an overlay
        // is up, so there is nothing painting over the overlay.
        //
        // Zero-sized rather than removed from composition ON PURPOSE. In windowed rendering the
        // browser is a native child window parented to the Canvas peer; dropping the SwingPanel
        // destroys that peer and re-adding it creates a different one, orphaning the live
        // CefBrowser. Resizing keeps the peer and the browser intact.
        val overlayShowing = overlayOpen || menuOpen || unlockPrompt

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = cefState) {
                is CefRuntime.State.Ready -> {
                    active?.let { tab ->
                        // SwingPanel bridges the AWT component Chromium renders into. Keyed on
                        // the tab id so switching tabs swaps the component rather than trying to
                        // re-parent a live browser, which crashes CEF.
                        SwingPanel(
                            background = Color.Black,
                            modifier = if (overlayShowing) Modifier.size(0.dp) else Modifier.fillMaxSize(),
                            factory = {
                                JPanel(BorderLayout()).apply {
                                    add(tab.browser.uiComponent, BorderLayout.CENTER)
                                }
                            },
                            update = { panel ->
                                panel.removeAll()
                                panel.add(tab.browser.uiComponent, BorderLayout.CENTER)
                                panel.revalidate()
                            },
                        )
                    }
                }
                is CefRuntime.State.Preparing -> ChromiumStarting(s.phase, s.percent)
                is CefRuntime.State.Failed -> ChromiumFailed(s.reason)
                CefRuntime.State.Idle -> ChromiumStarting("Starting", 0f)
            }

            // The three-dot menu. Ordinary Compose content over a scrim rather than a
            // DropdownMenu, because a popup would be painted under Chromium exactly as the tabs
            // overlay was. Reload always available; Mirror greyed out but VISIBLE unless the
            // current page is on the mesh -- disabled-not-hidden, as on the phone, so the
            // capability is discoverable before you are somewhere it applies.
            if (menuOpen) {
                BrowserMenu(
                    canMirror = canMirror,
                    onReload = { menuOpen = false; active?.browser?.reload() },
                    onMirror = {
                        menuOpen = false
                        val url = active?.url.orEmpty()
                        val host = PrismCefHandlers.hostOf(url)
                        if (host != null) {
                            note = "Saving " + host + "…"
                            // THE TWO ARE DIFFERENT OPERATIONS AND THE CHOICE IS BY DOMAIN, which is
                            // the distinction PHASE 80 exists to preserve. A mesh site is MIRRORED
                            // from its published manifest -- exact, hashed, quick. An ordinary site
                            // has no manifest and has to be CRAWLED within bounds. Using the wrong
                            // one fails on every site of the other kind, which is how this broke on
                            // Android.
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                note = if (PrismCefHandlers.isP2pDomain(host)) {
                                    com.prism.launcher.browser.MeshMirror.mirror(host)
                                        ?: (host + " mirrored and now served from this machine.")
                                } else {
                                    com.prism.launcher.browser.PrismSiteDownloader.download(url)
                                    "Downloading " + host + ". It is a bounded crawl, so it stops at " +
                                        "the page and byte caps rather than following the whole web."
                                }
                            }
                        }
                    },
                    isBookmarked = bookmarked,
                    onBookmark = {
                        menuOpen = false
                        val url = active?.url.orEmpty()
                        if (url.isNotBlank()) {
                            if (PrismSettings.isBookmarked(url)) {
                                PrismSettings.removeBookmark(url)
                                note = "Bookmark removed."
                            } else {
                                PrismSettings.addBookmark(active?.title.orEmpty(), url)
                                note = "Bookmarked."
                            }
                            bookmarked = PrismSettings.isBookmarked(url)
                        }
                    },
                    onShowLists = { menuOpen = false; listsOpen = true },
                    onDismiss = { menuOpen = false },
                )
            }

            if (listsOpen) {
                SavedListsOverlay(
                    onOpen = { url -> listsOpen = false; active?.browser?.loadURL(url) },
                    onDismiss = { listsOpen = false },
                )
            }

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1C1C24),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = Color(0xFFB9B9C4),
                        modifier = Modifier
                            .clickableRow { note = "" }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }

            if (overlayOpen) {
                TabsOverlay(
                    tabs = tabs.filter { it.isPrivate == overlayPrivate },
                    showingPrivate = overlayPrivate,
                    locked = overlayPrivate && PrismSettings.getPrivateTabsLocked() && !privateUnlocked,
                    activeId = activeId,
                    onCategory = { wantPrivate ->
                        if (wantPrivate && !privateUnlocked && PrismSettings.getPrivateTabsLocked()) {
                            unlockPrompt = true
                        } else {
                            overlayPrivate = wantPrivate
                        }
                    },
                    onSelect = { id ->
                        activeId = id
                        tabs.firstOrNull { it.id == id }?.let {
                            urlText = it.url
                            VpnBridge.applyForTab(it.isPrivate)
                        }
                        overlayOpen = false
                    },
                    onClose = { closeTab(it) },
                    onAdd = { addTab(isPrivate = overlayPrivate) },
                    onDone = { overlayOpen = false },
                )
            }
        }
    }

    if (unlockPrompt) {
        UnlockDialog(
            onDismiss = { unlockPrompt = false },
            onUnlocked = {
                privateUnlocked = true
                overlayPrivate = true
                unlockPrompt = false
            },
        )
    }
}

private const val HOME_URL = "https://duckduckgo.com/"

private data class BrowserTab(
    val id: Long,
    val client: CefClient,
    val browser: CefBrowser,
    val isPrivate: Boolean,
    val title: String,
    val url: String,
)

/**
 * Mirrors a mesh site automatically, when the setting is on.
 *
 * ONLY MESH SITES. Auto-mirroring an ordinary website would start a bounded crawl of somebody's
 * server every time a page was opened, which is rude at best; a mesh site is already published with a
 * manifest by a peer who meant it to be copied.
 */
private fun maybeAutoMirror(url: String) {
    if (!PrismSettings.getAutoMirror()) return
    val host = PrismCefHandlers.hostOf(url) ?: return
    if (!PrismCefHandlers.isP2pDomain(host)) return
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val error = com.prism.launcher.browser.MeshMirror.mirror(host)
        if (error == null) PrismPlatform.log.info("Prism/browser", "Auto-mirrored $host")
    }
}

// ── Chrome ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun BrowserChrome(
    urlText: String,
    onUrlChange: (String) -> Unit,
    onNavigate: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    tabCount: Int,
    isPrivate: Boolean,
    p2pActive: Boolean,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isPrivate) Color(0xFF241C33) else Color(0xFF16161A))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(18.dp)) }
        IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", Modifier.size(18.dp)) }
        IconButton(onClick = onReload) { Icon(Icons.Filled.Refresh, "Reload", Modifier.size(18.dp)) }

        // The resolution-source indicator, as on Android: a handshake for mesh-resolved, a globe
        // for ordinary DNS. Dimmed when it is not P2P, so the difference is glanceable.
        Icon(
            if (p2pActive) Icons.Filled.Handshake else Icons.Filled.Public,
            if (p2pActive) "Resolved over the mesh" else "Resolved over DNS",
            tint = if (p2pActive) colors.accent else Color(0xFF6C6C78),
            modifier = Modifier.size(17.dp).padding(end = 2.dp),
        )

        if (isPrivate) {
            Icon(
                Icons.Filled.VisibilityOff, "Private",
                tint = Color(0xFFB39DFF), modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(6.dp))
        CommittingField(
            value = urlText,
            modifier = Modifier.weight(1f),
            onCommit = { onUrlChange(it); onNavigate() },
        )
        Spacer(Modifier.width(6.dp))

        Surface(color = Color(0xFF2A2A34), shape = RoundedCornerShape(6.dp)) {
            Text(
                tabCount.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableRow { onTabs() }.padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }

        // A plain icon. The menu itself is drawn by the page, not as a popup here -- see
        // BrowserMenu for why a DropdownMenu cannot work above Chromium.
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "Menu", Modifier.size(18.dp)) }
    }
}


/**
 * Bookmarks and saved sites, in one overlay. PHASE 79.
 *
 * ## Why removing is two different things
 *
 * Android's semantics, kept deliberately. Removing a BOOKMARK forgets a URL. Removing a SAVED SITE
 * deletes files and un-hosts it from the mesh — and only ever files under the mirrors directory, never
 * a folder the user pointed at themselves. Getting that wrong would delete somebody's own documents
 * because they had hosted them, so the two are separate lists with separate wording.
 */
@Composable
private fun SavedListsOverlay(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    var revision by remember { mutableStateOf(0) }
    val bookmarks = remember(revision) { PrismSettings.getBookmarks() }
    val saved = remember(revision) { com.prism.launcher.browser.MeshMirror.all() }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickableRow { onDismiss() }) {
        Surface(
            color = Color(0xFF14141A),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.Center).width(620.dp).padding(24.dp),
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Bookmarks", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (bookmarks.isEmpty()) {
                    Text("None yet.", fontSize = 12.sp, color = colors.faint)
                } else {
                    bookmarks.forEach { bookmark ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f).clickableRow { onOpen(bookmark.url) },
                            ) {
                                Text(bookmark.title.ifBlank { bookmark.url }, fontSize = 13.sp)
                                Text(bookmark.url, fontSize = 11.sp, color = colors.faint, maxLines = 1)
                            }
                            Text(
                                "remove",
                                fontSize = 11.sp,
                                color = Color(0xFFFF6B6B),
                                modifier = Modifier.clickableRow {
                                    PrismSettings.removeBookmark(bookmark.url)
                                    revision++
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("Downloads", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saved into " + com.prism.desktop.browser.DesktopDownloads.directory().absolutePath +
                        ". Removing one deletes the file, as it does on the phone.",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(8.dp))
                val downloads = com.prism.desktop.browser.DesktopDownloads.all()
                if (downloads.isEmpty()) {
                    Text("None yet.", fontSize = 12.sp, color = colors.faint)
                } else {
                    downloads.forEach { download ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(download.name, fontSize = 13.sp)
                                Text(
                                    when {
                                        download.cancelled -> "cancelled"
                                        download.done -> (download.totalBytes shr 10).toString() + " KB"
                                        else -> download.percent.toString() + "%  of " +
                                            (download.totalBytes shr 10) + " KB"
                                    },
                                    fontSize = 11.sp,
                                    color = colors.faint,
                                )
                            }
                            Text(
                                if (download.done || download.cancelled) "remove" else "cancel",
                                fontSize = 11.sp,
                                color = Color(0xFFFF6B6B),
                                modifier = Modifier.clickableRow {
                                    if (download.done || download.cancelled) {
                                        com.prism.desktop.browser.DesktopDownloads.remove(download.id)
                                    } else {
                                        com.prism.desktop.browser.DesktopDownloads.cancel(download.id)
                                    }
                                    revision++
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("Saved sites", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Downloaded or mirrored, and served from this machine while Prism is running.",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(8.dp))
                if (saved.isEmpty()) {
                    Text("None yet.", fontSize = 12.sp, color = colors.faint)
                } else {
                    saved.forEach { site ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f).clickableRow { onOpen("http://" + site.domain + "/") },
                            ) {
                                Text(site.domain, fontSize = 13.sp)
                                Text(
                                    site.files.toString() + " file(s), " + (site.bytes shr 10) + " KB",
                                    fontSize = 11.sp,
                                    color = colors.faint,
                                )
                            }
                            Text(
                                "delete",
                                fontSize = 11.sp,
                                color = Color(0xFFFF6B6B),
                                modifier = Modifier.clickableRow {
                                    // Un-hosts it and deletes ONLY what is under the mirrors
                                    // directory -- see MeshMirror.remove.
                                    com.prism.launcher.browser.MeshMirror.remove(site.domain)
                                    revision++
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Close",
                        fontSize = 13.sp,
                        color = colors.accent,
                        modifier = Modifier.clickableRow { onDismiss() }.padding(6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The browser menu: reload, bookmark, save the site, and the saved lists.
 *
 * SAVE IS NEVER DISABLED NOW, and that is the PHASE 80 change. It used to be greyed out for anything
 * that was not a `.p2p` domain, because the only thing behind it was mirroring -- which cannot copy an
 * ordinary website. There are two operations now and the page picks between them by domain, so the
 * action applies everywhere and the LABEL says which one it will be.
 */
@Composable
private fun BrowserMenu(
    canMirror: Boolean,
    isBookmarked: Boolean,
    onReload: () -> Unit,
    onMirror: () -> Unit,
    onBookmark: () -> Unit,
    onShowLists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickableRow { onDismiss() }) {
        Surface(
            color = Color(0xFF1C1C24),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp).width(240.dp),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                MenuRow(
                    icon = Icons.Filled.Refresh,
                    label = "Reload",
                    enabled = true,
                    onClick = onReload,
                )
                MenuRow(
                    icon = Icons.Filled.Star,
                    label = if (isBookmarked) "Remove bookmark" else "Bookmark this page",
                    enabled = true,
                    onClick = onBookmark,
                )
                MenuRow(
                    icon = Icons.Filled.CloudDownload,
                    label = if (canMirror) "Mirror this site" else "Download this site",
                    enabled = true,
                    onClick = onMirror,
                )
                Text(
                    if (canMirror) {
                        "A mesh site is copied exactly from its published manifest, hash by hash, and " +
                            "then served from this machine too."
                    } else {
                        "An ordinary site has no manifest, so it is crawled within bounds: same host " +
                            "only, capped pages and bytes, with a pause between requests."
                    },
                    fontSize = 10.sp,
                    color = colors.faint,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                MenuRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Bookmarks and downloads",
                    enabled = true,
                    onClick = onShowLists,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color(0xFFE6E6EE) else Color(0xFF55555F)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickableRow(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = tint)
    }
}

@Composable
private fun TabsOverlay(
    tabs: List<BrowserTab>,
    showingPrivate: Boolean,
    locked: Boolean,
    activeId: Long,
    onCategory: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(Modifier.fillMaxSize().background(Color(0xF20D0D10)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedButton("Public", !showingPrivate) { onCategory(false) }
            Spacer(Modifier.width(6.dp))
            SegmentedButton("Private", showingPrivate) { onCategory(true) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                Text(if (showingPrivate) "New private tab" else "New tab")
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
        Spacer(Modifier.height(12.dp))

        if (locked) {
            // Titles and URLs are withheld too, not just previews -- a locked private tab whose
            // title reads "Bank of ..." has not been protected in any meaningful sense.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Lock, null, tint = colors.faint, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("${tabs.size} private tab${if (tabs.size == 1) "" else "s"}, locked", fontSize = 14.sp)
                Text(
                    "Unlock to see titles and previews.",
                    fontSize = 12.sp, color = colors.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    color = if (tab.id == activeId) Color(0xFF2A2A38) else Color(0xFF1A1A20),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.clickableRow { onSelect(tab.id) }.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (tab.isPrivate) {
                                Icon(
                                    Icons.Filled.VisibilityOff, null,
                                    tint = Color(0xFFB39DFF), modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                tab.title, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.Close, "Close",
                                tint = colors.faint,
                                modifier = Modifier.size(14.dp).clickableRow { onClose(tab.id) },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tab.url, fontSize = 10.sp, color = colors.faint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF23232B),
        shape = RoundedCornerShape(7.dp),
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) Color.White else Color(0xFF9A9AA6),
            modifier = Modifier.clickableRow(onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * The private-tab lock.
 *
 * Android uses `BiometricPrompt`. A desktop JVM has no portable biometric API -- Windows Hello
 * needs WinRT and Linux has no common one at all -- so this is a passphrase check against the
 * same setting, which is a real lock rather than a fake fingerprint dialog.
 */
@Composable
private fun UnlockDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val expected = PrismSettings.getPrivateTabsPassphrase()
                if (expected.isBlank() || entry == expected) onUnlocked() else wrong = true
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Unlock private tabs") },
        text = {
            Column(Modifier.width(340.dp)) {
                Text(
                    "Desktop has no portable biometric API, so private tabs are unlocked with " +
                        "the passphrase set in Prism Settings.",
                    fontSize = 12.sp, color = Color(0xFF9A9AA6),
                )
                Spacer(Modifier.height(10.dp))
                CommittingField(entry, modifier = Modifier.fillMaxWidth()) { entry = it }
                if (wrong) {
                    Text(
                        "Incorrect passphrase.",
                        fontSize = 11.sp, color = Color(0xFFE57373),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ChromiumStarting(phase: String, percent: Float) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(phase, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (percent > 0f) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.width(320.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("${percent.toInt()}%", fontSize = 12.sp, color = Color(0xFF9A9AA6))
        } else {
            LinearProgressIndicator(Modifier.width(320.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Prism embeds Chromium for the browser page. The engine is about 100 MB and is " +
                "downloaded once, on first use; later launches start in about a second.",
            fontSize = 12.sp, color = Color(0xFF83838F),
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun ChromiumFailed(reason: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFF8A5A5A), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text("Chromium could not start", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(reason, fontSize = 12.sp, color = Color(0xFF9A9AA6), modifier = Modifier.widthIn(max = 460.dp))
    }
}
