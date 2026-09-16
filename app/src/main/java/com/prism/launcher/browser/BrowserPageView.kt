package com.prism.launcher.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.IncludeBrowserPageBinding

@SuppressLint("SetJavaScriptEnabled")
class BrowserPageView(context: Context) : FrameLayout(context) {

    private val host: LauncherActivity = context as LauncherActivity
    private val binding: IncludeBrowserPageBinding
    private val blocklist = PrismBlocklist.get()

    private data class Tab(
        val id: Long,
        val webView: WebView,
        val isPrivate: Boolean,
        var title: String,
        var lastUrl: String,
    )

    private val tabs = ArrayList<Tab>()
    private var activeTabId: Long = 0L
    private var activeCategoryIsPrivate: Boolean = false
    private var privateAuthenticated: Boolean = false
    private var lastVpnStateWants: Boolean? = null

    private val tabsAdapter = BrowserTabsAdapter(
        onSelect = { id -> selectTab(id) },
        onClose = { id -> closeTab(id) },
    )

    init {
        binding = IncludeBrowserPageBinding.inflate(LayoutInflater.from(context), this, true)
        binding.tabsRecycler.layoutManager = GridLayoutManager(context, 2)
        binding.tabsRecycler.adapter = tabsAdapter

        binding.tabsButton.setOnClickListener { openTabsOverlay() }
        binding.addStandardTabBtn.setOnClickListener { addTab(isPrivate = false) }
        binding.addPrivateTabBtn.setOnClickListener { addTab(isPrivate = true) }
        binding.goButton.setOnClickListener { navigate(binding.urlField) }
        binding.browserMenuButton.setOnClickListener { showBrowserMenu() }
        binding.bookmarkButton.setOnClickListener { toggleBookmark() }
        binding.downloadSiteButton.setOnClickListener { downloadSiteForMesh() }
        binding.tabsDone.setOnClickListener { closeTabsOverlay() }

        binding.tabCategoryGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val targetIsPrivate = checkedId == R.id.privateCategoryBtn
            if (targetIsPrivate && !privateAuthenticated && PrismSettings.getPrivateTabsLocked()) {
                requestBiometricUnlock {
                    activeCategoryIsPrivate = true
                    refreshTabsList()
                }
            } else {
                activeCategoryIsPrivate = targetIsPrivate
                refreshTabsList()
            }
        }

        binding.goButton.setOnClickListener { navigate(binding.urlField) }
        binding.urlField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(v as EditText)
                true
            } else {
                false
            }
        }

        if (tabs.isEmpty()) {
            val defaultPrivate = PrismSettings.getPrivateByDefault()
            addTab(isPrivate = defaultPrivate, initialUrl = "https://duckduckgo.com/")
        }

        host.attachBrowserPage(this)

        // Observe P2P DNS Resolution State
        (context as? LifecycleOwner)?.lifecycleScope?.launchWhenStarted {
            P2pDnsManager.resolutionState.collect { states ->
                val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: return@collect
                val hostName = try { java.net.URL(activeTab.lastUrl).host } catch (e: Exception) { null }
                val source = states[hostName] ?: P2pDnsManager.ResolutionSource.UNKNOWN
                
                updateOriginIcon(activeTab.lastUrl, states[hostName])
            }
        }
    }

    /**
     * Mesh icon for anything Prism serves, globe for the open web.
     *
     * Driven by the URL rather than only by [P2pDnsManager.resolutionState], because that flow
     * emits when a DNS record changes -- not when the user navigates. Deciding on navigation is
     * what makes the icon track the page actually on screen.
     *
     * A domain counts as mesh if Prism resolved it over P2P, if it is a `.p2p` name, or if it is
     * one of the reserved domains the proxy dispatches internally. The reserved ones matter
     * separately: they are answered by `PrismProxyServer`'s dispatch rather than by a DNS lookup,
     * so a purely DNS-based test would show the globe for pages that never touch the internet.
     */
    private fun updateOriginIcon(url: String?, knownSource: P2pDnsManager.ResolutionSource? = null) {
        val host = try { java.net.URL(url ?: "").host?.lowercase() } catch (e: Exception) { null }
        val source = knownSource ?: host?.let { P2pDnsManager.resolutionState.value[it] }

        val onMesh = host != null && (
            source == P2pDnsManager.ResolutionSource.P2P ||
                host.endsWith(".p2p") ||
                host == PrismSettings.PRISM_SEARCH_DOMAIN ||
                host == com.prism.launcher.social.NebulaMeshSync.NEBULA_HOST_DOMAIN ||
                host == com.prism.launcher.aether.AetherMeshSync.AETHER_HOST_DOMAIN ||
                P2pDnsManager.isP2pDomain(host)
            )

        binding.dnsSourceIcon.setImageResource(
            if (onMesh) R.drawable.ic_handshake_24 else R.drawable.ic_globe_24
        )
        binding.dnsSourceIcon.alpha = if (onMesh) 1.0f else 0.6f
        binding.dnsSourceIcon.contentDescription =
            if (onMesh) "Served over the Prism mesh" else "Served over the internet"
    }

    override fun onDetachedFromWindow() {
        host.attachBrowserPage(null)
        super.onDetachedFromWindow()
    }

    fun resyncPrivateVpn() {
        val active = tabs.firstOrNull { it.id == activeTabId }
        applyVpnForTab(active)
    }

    fun handleBack(): Boolean {
        if (binding.tabsOverlay.isVisible) {
            closeTabsOverlay()
            return true
        }
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return false
        return if (active.webView.canGoBack()) {
            active.webView.goBack()
            true
        } else {
            false
        }
    }

    private fun addTab(isPrivate: Boolean, initialUrl: String? = null) {
        val wv = createWebView(isPrivate)
        val id = System.nanoTime()
        val startUrl = initialUrl ?: "https://duckduckgo.com/"
        val tab = Tab(
            id = id,
            webView = wv,
            isPrivate = isPrivate,
            title = if (isPrivate) context.getString(R.string.browser_private) else context.getString(R.string.browser_new_tab),
            lastUrl = startUrl,
        )
        tabs.add(tab)
        binding.webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        wv.visibility = View.GONE
        selectTab(id)
        wv.loadUrl(startUrl, privateHeaders(isPrivate))
    }

    private fun privateHeaders(isPrivate: Boolean): Map<String, String> {
        if (!isPrivate) return emptyMap()
        return mapOf(
            "DNT" to "1",
            "Sec-GPC" to "1",
        )
    }

    private fun createWebView(isPrivate: Boolean): WebView {
        val wv = WebView(context)
        // Every tab can start a download, private ones included.
        attachDownloadListener(wv)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, !isPrivate)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !isPrivate
            @Suppress("DEPRECATION")
            databaseEnabled = !isPrivate
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = if (isPrivate) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        wv.setBackgroundColor(Color.TRANSPARENT)

        val client = PrismWebViewClient(
            blocklist = blocklist,
            isPrivateTab = isPrivate,
            getEngine = { (context.applicationContext as? com.prism.launcher.PrismApp)?.tunnelEngine },
            onTitle = { t ->
                post {
                    val tab = tabs.firstOrNull { it.webView === wv } ?: return@post
                    tab.title = t
                    if (tab.id == activeTabId) {
                        // no-op
                    }
                }
            },
            onUrl = { u ->
                post {
                    val tab = tabs.firstOrNull { it.webView === wv } ?: return@post
                    tab.lastUrl = u
                    if (tab.id == activeTabId) {
                        binding.urlField.setText(u)
                        // Every navigation re-decides the origin icon; waiting on the DNS flow
                        // would leave the previous page's icon showing.
                        updateOriginIcon(u)
                        updateToolbarState(u)
                    }
                    // Auto-Mirror logic
                    if (PrismSettings.getAutoMirror()) {
                        val hostName = try { java.net.URL(u).host } catch (e: Exception) { null }
                        if (hostName != null && P2pDnsManager.isP2pDomain(hostName)) {
                            (context.applicationContext as? com.prism.launcher.PrismApp)?.tunnelEngine?.let { engine ->
                                PrismMirrorManager.mirrorSite(context, engine, hostName)
                            }
                        }
                    }
                }
            },
            // Caching decides for itself whether this page qualifies — the tab only has to say
            // whether it is private, which is something only it knows.
            onPageComplete = { finishedView, finishedUrl ->
                PrismWebCache.capture(context, finishedView, finishedUrl, isPrivate)
                // Recorded here rather than inside the cache, because the two answer different
                // questions: the cache keeps a copy of pages worth re-serving, the history keeps a
                // note of everything read. A page can be worth remembering having read without
                // being worth storing, and caching can be switched off entirely.
                //
                // Private tabs are never offered -- the decision is made here, where privateness is
                // known, rather than trusted to the recorder.
                if (!isPrivate) {
                    com.prism.launcher.history.PrismHistory.record(
                        kind = com.prism.launcher.history.PrismHistory.Kind.PAGE,
                        title = finishedView.title.orEmpty().ifBlank { finishedUrl },
                        uri = finishedUrl,
                        source = runCatching { java.net.URL(finishedUrl).host }.getOrNull().orEmpty(),
                    )
                }
            },
        )
        wv.webViewClient = client
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.loadProgress.isVisible = newProgress in 1..99 && view?.visibility == View.VISIBLE
                binding.loadProgress.progress = newProgress
            }
        }
        return wv
    }

    private fun selectTab(id: Long) {
        val tabToSelect = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        for (tab in tabs) {
            val show = tab.id == id
            tab.webView.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                binding.urlField.setText(tab.lastUrl)
            }
        }
        applyVpnForTab(tabToSelect)
        closeTabsOverlay()
    }

    private fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        binding.webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            addTab(isPrivate = activeCategoryIsPrivate)
            return
        }
        if (activeTabId == id) {
            val next = tabs[(idx.coerceAtMost(tabs.lastIndex)).coerceAtLeast(0)]
            selectTab(next.id)
        } else {
            val active = tabs.firstOrNull { it.id == activeTabId }
            applyVpnForTab(active)
        }
    }

    private fun openTabsOverlay() {
        // Automatically switch overlay category to match current tab type
        val active = tabs.firstOrNull { it.id == activeTabId }
        activeCategoryIsPrivate = active?.isPrivate ?: false
        binding.tabCategoryGroup.check(if (activeCategoryIsPrivate) R.id.privateCategoryBtn else R.id.publicCategoryBtn)
        
        refreshTabsList()
        binding.tabsOverlay.isVisible = true
    }

    private fun refreshTabsList() {
        val lockActive = PrismSettings.getPrivateTabsLocked() && !privateAuthenticated
        val displayed = tabs.filter { it.isPrivate == activeCategoryIsPrivate }
        
        val cards = displayed.map { tab ->
            TabCardUi(
                id = tab.id,
                title = tab.title,
                url = tab.lastUrl,
                isPrivate = tab.isPrivate,
                preview = if (tab.isPrivate && lockActive) null else captureWebPreview(tab.webView, 720, 900),
                isLocked = lockActive
            )
        }
        tabsAdapter.submitList(cards)

        // Visibility toggle for Add buttons based on category
        binding.addStandardTabBtn.isVisible = !activeCategoryIsPrivate
        binding.addPrivateTabBtn.isVisible = activeCategoryIsPrivate
    }

    /**
     * Ordinary file downloads, outside the mesh.
     *
     * Handed to Android's DownloadManager rather than streamed through the WebView: it survives
     * the page being closed, handles resume and notifications, and writes into the user's real
     * Downloads folder where every other app expects to find files. Prism only records that the
     * download happened, so the browser's own Downloads list has something to show.
     *
     * Attached per WebView -- a download can start from any tab, including a private one.
     */
    private fun attachDownloadListener(webView: android.webkit.WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setDescription("Downloading from Prism")
                    // Hidden in favour of Prism's own progress notification -- see
                    // PrismDownloadNotifications. The DOWNLOAD_WITHOUT_NOTIFICATION permission
                    // this needs is already declared in the manifest.
                    setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_HIDDEN
                    )
                    setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                    )
                }
                val manager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE)
                    as android.app.DownloadManager
                val downloadId = manager.enqueue(request)
                // Prism posts its own progress notification for files AND for mesh sites, so the
                // two look the same; DownloadManager's own is suppressed above to avoid a second,
                // duplicate notification for the very same download.
                PrismDownloadNotifications.trackFile(context, downloadId, fileName)

                val local = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    fileName
                )
                // The download id is kept so the Downloads list can later delete the file through
                // DownloadManager, which owns it -- see PrismDownloadsSheet.
                PrismSettings.recordDownloadedFile(
                    fileName, url, android.net.Uri.fromFile(local).toString(), downloadId
                )
                Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                PrismLogger.logError("Browser", "Download failed for $url", e)
                Toast.makeText(context, "Could not start download", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Star reflects whether THIS page is bookmarked; the mesh-download button only exists when
     * there is a mesh to host the result on.
     */
    private fun updateToolbarState(url: String?) {
        val bookmarked = url != null && PrismSettings.isBookmarked(url)
        binding.bookmarkButton.setIconResource(
            if (bookmarked) R.drawable.ic_star_filled_24 else R.drawable.ic_star_outline_24
        )
        binding.bookmarkButton.contentDescription =
            if (bookmarked) "Remove bookmark" else "Bookmark this page"

        val meshOn = com.prism.launcher.mesh.PrismMeshService.isOnMesh()
        binding.downloadSiteButton.isVisible = meshOn
    }

    /** Star toggles rather than only adds -- a star that cannot be un-starred is a trap. */
    private fun toggleBookmark() {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        val url = active.lastUrl
        if (url.isBlank()) return
        if (PrismSettings.isBookmarked(url)) {
            PrismSettings.removeBookmark(url)
            Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val title = active.webView.title?.takeIf { it.isNotBlank() } ?: url
            PrismSettings.addBookmark(title, url)
            Toast.makeText(context, "Bookmarked", Toast.LENGTH_SHORT).show()
        }
        updateToolbarState(url)
    }

    private fun showBookmarks() {
        val bookmarks = PrismSettings.getBookmarks().sortedByDescending { it.savedAt }
        if (bookmarks.isEmpty()) {
            Toast.makeText(context, "No bookmarks yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = bookmarks.map { it.title }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which -> openUrl(bookmarks[which].url) }
            // Long-press is not available on a simple list dialog, so removal is its own step
            // rather than a hidden gesture nobody would find.
            .setNeutralButton("Remove\u2026") { _, _ -> showRemoveBookmark(bookmarks) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRemoveBookmark(bookmarks: List<PrismSettings.Bookmark>) {
        val labels = bookmarks.map { it.title }.toTypedArray()
        val checked = BooleanArray(bookmarks.size)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Remove bookmarks")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Remove") { _, _ ->
                bookmarks.forEachIndexed { i, b -> if (checked[i]) PrismSettings.removeBookmark(b.url) }
                updateToolbarState(tabs.firstOrNull { it.id == activeTabId }?.lastUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Downloaded FILES and, on a mesh, downloaded SITES.
     *
     * The list itself lives in [PrismDownloadsSheet] because it needs per-row long-press and a
     * per-row remove button, neither of which a plain items-dialog can carry. This keeps only the
     * two things the browser knows how to do with a row: open a file, or navigate to a site.
     */
    private fun showDownloads() {
        PrismDownloadsSheet.show(
            context,
            onOpenFile = { file -> openDownloadedFile(file) },
            onOpenSite = { domain -> openUrl("http://$domain") }
        )
    }

    private fun openDownloadedFile(file: PrismSettings.DownloadedFile) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = android.net.Uri.parse(
                    if (file.localPath.isNotBlank()) file.localPath else file.url
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app can open ${file.fileName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        active.lastUrl = url
        binding.urlField.setText(url)
        active.webView.loadUrl(url, privateHeaders(active.isPrivate))
    }

    /**
     * Crawls the current site and stores it so mesh peers can be served it.
     *
     * Delegates to [PrismMirrorManager], which is the same machinery the menu's existing "mirror"
     * action uses -- one implementation of "fetch a whole site and host it", not two that could
     * disagree about where the files land.
     */
    private fun downloadSiteForMesh() {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        val hostName = try { java.net.URL(active.lastUrl).host } catch (e: Exception) { null }
        if (hostName.isNullOrBlank()) {
            Toast.makeText(context, "No site to download", Toast.LENGTH_SHORT).show()
            return
        }
        // TWO DIFFERENT OPERATIONS, and using the wrong one fails immediately.
        //
        // A site already hosted on the mesh publishes a manifest.json listing its files with
        // hashes, and mirroring replicates it exactly, integrity-checked. A site on the open web
        // publishes no such thing -- pointing the mirror path at duckduckgo.com asks for
        // `duckduckgo.com.remote/manifest.json`, which cannot exist, and the download fails before
        // fetching a single page. The open web has to be crawled instead.
        if (P2pDnsManager.isP2pDomain(hostName)) {
            val engine = (context.applicationContext as? com.prism.launcher.PrismApp)?.tunnelEngine
            if (engine == null) {
                Toast.makeText(context, "Mesh engine is not running", Toast.LENGTH_SHORT).show()
                return
            }
            PrismMirrorManager.mirrorSite(context, engine, hostName)
        } else {
            PrismSiteDownloader.download(context, active.lastUrl)
        }
        Toast.makeText(context, "Downloading $hostName for the mesh\u2026", Toast.LENGTH_SHORT).show()
    }

    private fun showBrowserMenu() {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val sheetBinding = com.prism.launcher.databinding.LayoutBrowserMenuSheetBinding.inflate(LayoutInflater.from(context))
        
        sheetBinding.menuReload.setOnClickListener {
            active.webView.reload()
            dialog.dismiss()
        }

        sheetBinding.menuBookmarks.setOnClickListener {
            dialog.dismiss()
            showBookmarks()
        }

        sheetBinding.menuDownloads.setOnClickListener {
            dialog.dismiss()
            showDownloads()
        }
        
        val hostName = try { java.net.URL(active.lastUrl).host } catch (e: Exception) { "" }
        val isP2p = P2pDnsManager.isP2pDomain(hostName)
        
        sheetBinding.menuMirror.isEnabled = isP2p
        sheetBinding.menuMirror.alpha = if (isP2p) 1.0f else 0.5f
        sheetBinding.menuMirror.setOnClickListener {
            (context.applicationContext as? com.prism.launcher.PrismApp)?.tunnelEngine?.let { engine ->
                PrismMirrorManager.mirrorSite(context, engine, hostName)
            }
            dialog.dismiss()
        }
        
        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun closeTabsOverlay() {
        binding.tabsOverlay.isVisible = false
    }

    private fun requestBiometricUnlock(onSuccess: () -> Unit) {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                privateAuthenticated = true
                onSuccess()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Tabs")
            .setSubtitle("Use your biometric credential to access private tabs")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info)
    }

    private fun navigate(field: EditText) {
        var input = field.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) return

        var url = input
        val looksLikeUrl = input.contains("://") || (input.contains(".") && !input.contains(" "))

        if (!looksLikeUrl) {
            url = PrismSettings.buildSearchUrl(input)
            // The query, not the results page. What someone searched for is usually a better memory
            // hook than whatever the engine returned -- and the results page gets recorded anyway
            // when it finishes loading.
            if (!activeCategoryIsPrivate) {
                com.prism.launcher.history.PrismHistory.record(
                    kind = com.prism.launcher.history.PrismHistory.Kind.SEARCH,
                    title = input,
                    uri = "search:" + input.lowercase(),
                    text = input,
                    source = "Browser",
                )
            }
        } else if (!input.contains("://")) {
            // Default to https for standard browsing -- EXCEPT for addresses that are only ever
            // served over plain HTTP. Prism's own search engine, mesh-hosted sites and loopback
            // services have no TLS certificate and no way to get one, so forcing https at them
            // produced a connection failure that looked exactly like the server being down.
            url = if (isPlainHttpHost(input)) "http://$input" else "https://$input"
        }

        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        active.lastUrl = url
        active.webView.loadUrl(url, privateHeaders(active.isPrivate))
    }

    /**
     * Hosts Prism serves itself, over plain HTTP by necessity.
     *
     * Loopback and private ranges are local services; `.p2p` and the reserved domains are answered
     * by `PrismProxyServer`'s dispatch inside the mesh tunnel, where a public CA could never issue
     * a certificate anyway. Everything else keeps the https default.
     */
    private fun isPlainHttpHost(input: String): Boolean {
        val host = input.substringBefore('/').substringBefore(':').lowercase()
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.endsWith(".p2p") ||
            host == com.prism.launcher.PrismSettings.PRISM_SEARCH_DOMAIN ||
            host == com.prism.launcher.social.NebulaMeshSync.NEBULA_HOST_DOMAIN
    }

    private fun applyVpnForTab(tab: Tab?) {
        val wantsPrivateTunnel = tab?.isPrivate == true
        if (wantsPrivateTunnel == lastVpnStateWants) return
        lastVpnStateWants = wantsPrivateTunnel

        if (wantsPrivateTunnel) {
            val autoStart = PrismSettings.getVpnAutoStart()
            if (!autoStart) return
            
            val prep = VpnService.prepare(host)
            if (prep != null) {
                host.requestVpnPermission(prep)
                return
            }
            // Establish Full Privacy Tunnel
            PrivateDnsVpnService.start(context, true)
        } else {
            // Downgrade to Backbone-Only Mode (Clears 'Key' icon and Ad-blocking)
            val alwaysOn = PrismSettings.getVpnServerAlwaysOn()
            if (alwaysOn) {
                PrivateDnsVpnService.start(context, true) // Maintain tunnel if persistent
            } else {
                PrivateDnsVpnService.stop(context) // Fully stop VPN if not persistent
            }
        }
    }
}
