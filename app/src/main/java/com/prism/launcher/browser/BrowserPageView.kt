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
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.LauncherActivity
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
                
                binding.dnsSourceIcon.setImageResource(
                    when (source) {
                        P2pDnsManager.ResolutionSource.P2P -> R.drawable.ic_handshake_24
                        else -> R.drawable.ic_globe_24
                    }
                )
                binding.dnsSourceIcon.alpha = if (source == P2pDnsManager.ResolutionSource.P2P) 1.0f else 0.6f
            }
        }
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

    private fun showBrowserMenu() {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val sheetBinding = com.prism.launcher.databinding.LayoutBrowserMenuSheetBinding.inflate(LayoutInflater.from(context))
        
        sheetBinding.menuReload.setOnClickListener {
            active.webView.reload()
            dialog.dismiss()
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
        } else if (!input.contains("://")) {
            // Default to https for standard browsing, but allow P2P resolution to handle the IP
            url = "https://$input"
        }

        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        active.lastUrl = url
        active.webView.loadUrl(url, privateHeaders(active.isPrivate))
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
