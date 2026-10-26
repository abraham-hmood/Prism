package com.prism.launcher


import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import com.prism.launcher.databinding.PageDrawerRootBinding
import com.prism.launcher.notifications.NotificationHistory
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.ThemeSkin
import com.prism.launcher.search.WebSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * The class name a trusted device's app carries in place of a real activity.
 *
 * It has to be something no real component could be, because the drawer stores a ComponentName and the
 * tap handler decides from it whether Android can start the app or whether Prism has to fetch it first.
 */
private const val TRUSTED_APP_CLASS = "com.prism.launcher.trusted.OfferedApp"

class DrawerPageView(
    context: Context,
    private val onLaunch: (ComponentName) -> Unit,
    private val allowDragToDesktop: () -> Boolean,
) : FrameLayout(context) {

    /** Opens the long-press menu. Null means this drawer has no menu and keeps drag long-press. */
    var onShowAppMenu: ((DrawerAppEntry, View) -> Unit)? = null

    private val adapter = DrawerAppsAdapter(
        { component -> launch(component) },
        { allowDragToDesktop() },
        onShowMenu = { entry, anchor, _ ->
            val handler = onShowAppMenu
            if (handler == null) {
                false
            } else {
                // The icon's own handoff (see MenuDragHandoff) starts the drag; this only has to
                // put the menu on screen.
                handler(entry, anchor)
                true
            }
        },
        onOpenWebResult = { result -> openWebResult(result.url) },
    )

    /** The inflated hierarchy. A field rather than an init-local because [scrollToLetter] needs it. */
    private val binding: PageDrawerRootBinding =
        PageDrawerRootBinding.inflate(LayoutInflater.from(context), this, true)

    /**
     * Opens an app, whichever device it lives on.
     *
     * A trusted device's app is not installed here, so there is no activity to start: it has to be
     * downloaded first and then virtualized. Intercepted at this one point rather than by teaching the
     * rest of the launcher about a second kind of app, because from the drawer's point of view it IS an
     * app -- it has a name, an icon and a tap.
     */
    private fun launch(component: android.content.ComponentName) {
        if (component.className != TRUSTED_APP_CLASS) {
            onLaunch(component)
            return
        }
        val app = com.prism.launcher.trusted.TrustedApps.all()
            .firstOrNull { it.pkg == component.packageName } ?: return
        val lifecycleOwner = context as? LifecycleOwner ?: return

        android.widget.Toast.makeText(
            context,
            "Getting " + app.label + " from " + app.deviceName + "…",
            android.widget.Toast.LENGTH_SHORT,
        ).show()

        lifecycleOwner.lifecycleScope.launch {
            // Hundreds of megabytes over the mesh, so off the main thread and with the result reported
            // rather than assumed -- a download that failed and one that finished look identical from
            // the drawer otherwise.
            val result = withContext(Dispatchers.IO) {
                com.prism.launcher.trusted.TrustedApps.open(app)
            }
            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private var allApps: List<DrawerAppEntry> = emptyList()
    private var filterJob: Job? = null
    private var webSearchJob: Job? = null
    private var observeJob: Job? = null
    private var isReloading = false

    /** Local matches for the current query, which web results are inserted into when they arrive. */
    private data class LocalSearchResults(
        val query: String,
        val apps: List<DrawerItem>,
        val widgets: List<DrawerItem.Widget>,
        val notifications: List<NotificationHistory.Record>,
    )

    private var localSearch: LocalSearchResults? = null
    private var webResultsQuery: String = ""
    private var webResults: List<WebSearch.Result> = emptyList()

    /**
     * Package changes while this page is attached, handled without going through the database.
     *
     * The manifest receiver covers the background case, but it has to write a row and wait for Room
     * to wake the flow before the drawer hears about a new app. A runtime receiver refreshes the
     * list straight from the package manager on the spot, so an app installed with the drawer open
     * appears as it finishes installing. Both paths are kept: this one dies with the page, the
     * other one survives a closed launcher.
     */
    private val packageWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            AppIconCache.invalidate(pkg)
            val owner = context as? LifecycleOwner ?: return
            owner.lifecycleScope.launch { refreshAppList() }
            // The package manager occasionally answers PACKAGE_ADDED a moment before the new
            // activity is queryable. One follow-up scan costs nothing and means the app appears
            // as it finishes installing instead of on the next unrelated refresh.
            postDelayed({
                if (isAttachedToWindow) owner.lifecycleScope.launch { refreshAppList() }
            }, 800L)
        }
    }
    
    // Design tokens. Glow resolves from the theme, not from a fixed accent: a theme without
    // elevation has no glow, and a themed glow takes that theme's accent.
    private val glowColor = IosUi.accent(context)

    private fun resolveAttr(attr: Int): Int {
        com.prism.launcher.theme.ThemeSkin.resolveAttr(context, attr)?.let { return it }
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        val layoutManager = GridLayoutManager(context, 4)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // Apps are grid cells; everything else is a full-width row. A section heading or a
                // notification left at one span is squeezed into a quarter of the width, which
                // wraps "NOTIFICATIONS" down the screen one letter at a time.
                return when (adapter.getItemViewType(position)) {
                    DrawerAppsAdapter.VIEW_TYPE_GROUP,
                    DrawerAppsAdapter.VIEW_TYPE_SECTION,
                    DrawerAppsAdapter.VIEW_TYPE_NOTIFICATION,
                    DrawerAppsAdapter.VIEW_TYPE_WEB_RESULT,
                    DrawerAppsAdapter.VIEW_TYPE_WIDGET -> 4
                    else -> 1
                }
            }
        }
        binding.drawerList.layoutManager = layoutManager
        binding.drawerList.adapter = adapter

        // Background is now handled by LauncherActivity for system-level blur
        
        // 1. Search Bar Neon Glow (Multi-layer Halo). Null under a theme without elevation: the
        // TextInputLayout then keeps its own themed field shape instead of wearing a halo.
        binding.searchGlowContainer.background = ThemeSkin.glowDrawable(context, 20f)
        binding.searchContainer.setStartIconTintList(ColorStateList.valueOf(glowColor))

        // 2. Settings Button Glow
        binding.settingsBtnWrapper.background = ThemeSkin.glowDrawable(context, 32f, 4f)
        binding.settingsBtn.imageTintList = ColorStateList.valueOf(glowColor)

        // 3. App Title Neon (Tube effect)
        if (IosUi.mode(context).shape.elevation > 0) {
            NeonGlowEngine.applyNeonText(binding.drawerPageHandle, glowColor, 16f)
        } else {
            NeonGlowEngine.clearNeonText(binding.drawerPageHandle, IosUi.label(context))
        }

        // 4. Staccato Bubble Neon
        binding.staccatoBubble.background = ThemeSkin.glowDrawable(context, 36f, 8f)
        binding.staccatoBubbleText.setTextColor(IosUi.label(context))
        
        binding.settingsBtn.setOnClickListener {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }

        // Reload Button Glow (same treatment as Settings)
        binding.reloadBtnWrapper.background = ThemeSkin.glowDrawable(context, 32f, 4f)
        binding.reloadBtn.imageTintList = ColorStateList.valueOf(glowColor)

        binding.reloadBtn.setOnClickListener {
            reloadAppsFromPackageManager(binding)
        }

        binding.searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        setupStaccato(binding)
    }

    private fun setupStaccato(binding: PageDrawerRootBinding) {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#".toCharArray()
        binding.staccatoBar.removeAllViews()
        
        val letterViews = mutableListOf<TextView>()
        
        alphabet.forEach { char ->
            val textView = TextView(context).apply {
                text = char.toString()
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(resolveAttr(R.attr.prismTextPrimary))
                alpha = 0.6f
                // A faint base glow for the unlit state, if this theme glows at all.
                if (IosUi.mode(context).shape.elevation > 0) {
                    setShadowLayer(4f, 0f, 0f, Color.argb(40, 0, 0, 0))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            binding.staccatoBar.addView(textView)
            letterViews.add(textView)
        }

        binding.staccatoBar.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.staccatoBubble.visibility = View.VISIBLE
                    binding.staccatoBubble.alpha = 0f
                    binding.staccatoBubble.scaleX = 0.4f
                    binding.staccatoBubble.scaleY = 0.4f
                    binding.staccatoBubble.animate()
                        .alpha(1f)
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .setDuration(150)
                        .withEndAction {
                            binding.staccatoBubble.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                        .start()
                    
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    updateStaccato(event.y, v.height, alphabet, binding, letterViews)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateStaccato(event.y, v.height, alphabet, binding, letterViews)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.staccatoBubble.animate()
                        .alpha(0f)
                        .scaleX(0.2f)
                        .scaleY(0.2f)
                        .setDuration(200)
                        .withEndAction { binding.staccatoBubble.visibility = View.GONE }
                        .start()
                    
                    letterViews.forEach { applyLetterAppearance(it, false) }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyLetterAppearance(tv: TextView, active: Boolean) {
        val mode = IosUi.mode(context)
        if (active) {
            tv.alpha = 1f
            if (mode.shape.elevation > 0) {
                NeonGlowEngine.applyNeonText(tv, glowColor, 18f)
            } else {
                NeonGlowEngine.clearNeonText(tv, glowColor)
            }
        } else {
            tv.alpha = 0.6f
            tv.setTextColor(resolveAttr(R.attr.prismTextPrimary))
            if (mode.shape.elevation > 0) {
                tv.setShadowLayer(4f, 0f, 0f, Color.argb(40, 0, 0, 0))
            } else {
                tv.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    private fun updateStaccato(touchY: Float, height: Int, alphabet: CharArray, binding: PageDrawerRootBinding, letterViews: List<TextView>) {
        val index = ((touchY / height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
        val char = alphabet[index]
        binding.staccatoBubbleText.text = char.toString()
        if (IosUi.mode(context).shape.elevation > 0) {
            NeonGlowEngine.applyNeonText(binding.staccatoBubbleText, glowColor, 24f)
        } else {
            NeonGlowEngine.clearNeonText(binding.staccatoBubbleText, IosUi.label(context))
        }
        
        // Follow the finger (centered on the bubble)
        val bubbleHalfHeight = binding.staccatoBubble.height / 2f
        binding.staccatoBubble.translationY = (touchY - bubbleHalfHeight).coerceIn(0f, height.toFloat() - binding.staccatoBubble.height)
        
        // Highlight logic: accent in the theme's accent, with a glow only if the theme has one.
        letterViews.forEachIndexed { i, tv -> applyLetterAppearance(tv, i == index) }
        
        scrollToLetter(char)
    }

    /**
     * Scrolls the drawer to the first row whose app starts with [char].
     *
     * THE INDEX HAS TO BE THE ADAPTER'S, not the flat [allApps] one. The adapter shows category and
     * developer groups as their own full-width rows before the remaining apps, so an index into the
     * flat, alphabetically sorted app list landed that many rows too early -- which is why the rail
     * scrolled to the wrong letter.
     */
    private fun scrollToLetter(char: Char) {
        fun matches(label: String): Boolean =
            if (char == '#') label.firstOrNull()?.isLetter() == false
            else label.startsWith(char, ignoreCase = true)

        val index = adapter.currentList.indexOfFirst { item ->
            when (item) {
                is DrawerItem.App -> matches(item.entry.label)
                is DrawerItem.Group -> item.apps.any { matches(it.label) }
                else -> false
            }
        }
        if (index != -1) {
            (binding.drawerList.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observeJob?.isActive == true) return
        val lifecycleOwner = context as? LifecycleOwner ?: return

        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                packageWatcher,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addDataScheme("package")
                },
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // TWO SOURCES, IN THIS ORDER: a live PackageManager scan that is shown immediately, then
        // the installed-apps table purely as a CHANGE NOTIFIER.
        //
        // The drawer used to render the rows of that table, which made a minute-old table a
        // minute-old drawer: an app whose broadcast arrived while the process was not running, or
        // whose row a scan had dropped, simply was not there until the next full sync. The table is
        // still written and still useful -- it is what notices a change -- but the package manager
        // is the authority on what is installed, and asking it costs one query now that labels are
        // cached and icons are loaded lazily.
        observeJob = lifecycleOwner.lifecycleScope.launch {
            refreshAppList()
            AppDatabase.get().installedAppDao().observeAll().collectLatest { refreshAppList() }
        }
    }

    /** Re-reads the installed list and shows it, off the main thread. */
    private suspend fun refreshAppList() {
        val entries = withContext(Dispatchers.IO) {
            loadLauncherApps(context.packageManager) + trustedEntries()
        }
        if (!isAttachedToWindow) return
        allApps = entries
        val query = binding.searchBar.text?.toString().orEmpty()
        if (query.isBlank()) {
            adapter.submitList(groupDrawerApps(entries))
        } else {
            // Keep whatever the user is searching for applied to the new list, rather than
            // silently replacing their results with everything.
            filterApps(query)
        }
    }

    /**
     * Apps offered by trusted devices, as drawer entries.
     *
     * The component name is synthetic -- there is no such activity on this phone -- and
     * [TRUSTED_APP_CLASS] is what marks it so a tap is routed to the download instead of to Android.
     * Blocking: it reads icons from disk and may fetch ones that have not arrived yet, which is why the
     * caller has it on the IO dispatcher.
     */
    private fun trustedEntries(): List<DrawerAppEntry> =
        runCatching {
            com.prism.launcher.trusted.TrustedApps.all().map { app ->
                val icon = com.prism.launcher.trusted.TrustedApps.ensureIcon(app)?.let { file ->
                    runCatching {
                        android.graphics.drawable.BitmapDrawable(
                            resources,
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath),
                        )
                    }.getOrNull()
                }
                DrawerAppEntry(
                    component = android.content.ComponentName(app.pkg, TRUSTED_APP_CLASS),
                    label = app.label,
                    icon = icon,
                )
            }
        }.getOrDefault(emptyList())

    override fun onDetachedFromWindow() {
        observeJob?.cancel()
        observeJob = null
        filterJob?.cancel()
        webSearchJob?.cancel()
        runCatching { context.unregisterReceiver(packageWatcher) }
        super.onDetachedFromWindow()
    }

    /**
     * Full PackageManager rescan triggered by the reload button: wipes and repopulates the
     * installed-apps table from scratch (same work [AppSyncWorker] does on first launch). The
     * drawer's own list updates automatically afterward via the live Flow in [onAttachedToWindow]
     * -- no manual re-submit needed here.
     */
    private fun reloadAppsFromPackageManager(binding: PageDrawerRootBinding) {
        if (isReloading) return
        val lifecycleOwner = context as? LifecycleOwner ?: return
        isReloading = true
        binding.reloadBtn.animate().rotationBy(360f).setDuration(500).start()
        lifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Labels can change with a language switch and icons with a pack, and neither is a
                // package broadcast, so a deliberate reload throws the cache away.
                AppIconCache.clear()
                val dao = AppDatabase.get().installedAppDao()
                dao.clearAll()
                dao.insertAll(queryLauncherApps(context))
            }
            refreshAppList()
            isReloading = false
            android.widget.Toast.makeText(context, "Apps reloaded", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterApps(query: String) {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        filterJob?.cancel()
        webSearchJob?.cancel()
        filterJob = lifecycleOwner.lifecycleScope.launch {
            if (query.isBlank()) {
                localSearch = null
                webResultsQuery = ""
                webResults = emptyList()
                val grouped = groupDrawerApps(allApps)
                adapter.submitList(grouped)
                return@launch
            }
            delay(150)
            val filtered = allApps.filter {
                it.label.contains(query, ignoreCase = true)
            }
            val grouped = groupDrawerApps(filtered)

            // Notifications, under the apps.
            //
            // Read off the main thread: the store reads its file the first time it is asked, and
            // that file can hold a hundred thousand entries. Apps come first because a launcher's
            // search box is still mostly used to launch something, and the section heading only
            // appears when there is something under it.
            val notifications = withContext(Dispatchers.IO) {
                NotificationHistory.search(query, limit = NOTIFICATION_RESULT_LIMIT)
            }

            // Widgets, matched on the widget's own label and on the app it came from -- "google"
            // should find the search bar even though the widget is called "Search".
            val widgets = withContext(Dispatchers.IO) {
                com.prism.launcher.widgets.PrismWidgetHost.installedProviders(context)
                    .mapNotNull { info ->
                        val label = runCatching { info.loadLabel(context.packageManager) }.getOrNull().orEmpty()
                        val appLabel = com.prism.launcher.notifications.PrismNotificationListener
                            .appLabel(context, info.provider?.packageName)
                        if (!label.contains(query, true) && !appLabel.contains(query, true)) return@mapNotNull null
                        DrawerItem.Widget(
                            provider = info.provider?.flattenToString() ?: return@mapNotNull null,
                            label = label.ifBlank { appLabel },
                            appLabel = appLabel,
                            previewImage = info.previewImage,
                            icon = info.icon,
                        )
                    }
                    .take(WIDGET_RESULT_LIMIT)
            }

            val queryChanged = localSearch?.query != query
            if (queryChanged) {
                // The web section belongs to this query; old results for the previous one must
                // not appear above the new widget matches while the request is in flight.
                webResultsQuery = ""
                webResults = emptyList()
            }
            localSearch = LocalSearchResults(
                query = query,
                apps = grouped,
                widgets = widgets,
                notifications = notifications,
            )
            submitSearchResults(query)
            // A package-list refresh can re-run the local filter for the same query; do not make
            // the user pay for the same network answer twice.
            if (webResultsQuery != query) startWebSearch(query)
        }
    }

    /**
     * Searches the web alongside the local matches.
     *
     * Separate from [filterApps] so the apps, widgets and notifications appear immediately. The
     * network answer is allowed to arrive a moment later and slot in above the widgets, rather
     * than making the whole drawer wait for the selected engine.
     */
    private fun startWebSearch(query: String) {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        webSearchJob?.cancel()
        webSearchJob = lifecycleOwner.lifecycleScope.launch {
            delay(WEB_SEARCH_DEBOUNCE_MS)
            val results = WebSearch.search(query, limit = WEB_RESULT_LIMIT)
            if (!isAttachedToWindow) return@launch
            if (binding.searchBar.text?.toString() != query) return@launch
            if (localSearch?.query != query) return@launch
            webResultsQuery = query
            webResults = results
            submitSearchResults(query)
        }
    }

    /** Applies the current local and web results for [query] to the adapter. */
    private fun submitSearchResults(query: String) {
        val local = localSearch ?: return
        if (local.query != query) return

        val results = buildList {
            addAll(local.apps)
            if (webResultsQuery == query && webResults.isNotEmpty()) {
                add(DrawerItem.Section("Web \u00b7 ${WebSearch.engineLabel()}"))
                webResults.forEach { add(DrawerItem.WebResult(it)) }
            }
            if (local.widgets.isNotEmpty()) {
                add(DrawerItem.Section("Widgets"))
                addAll(local.widgets)
            }
            if (local.notifications.isNotEmpty()) {
                add(DrawerItem.Section("Notifications"))
                local.notifications.forEach { add(DrawerItem.Notification(it)) }
            }
        }
        adapter.submitList(results)
    }

    /** Opens a web result, preferring the browser page over any other desktop page. */
    private fun openWebResult(url: String) {
        val activity = context as? LauncherActivity
        if (activity != null) {
            activity.openWebResult(url)
        } else {
            com.prism.launcher.browser.openInDefaultBrowser(context, url)
        }
    }

    private companion object {
        /**
         * How many notifications the drawer search shows.
         *
         * Short on purpose. This is a launcher's search box, not the notifications page -- someone
         * who wants to read through their history goes to that page, and burying the apps under
         * forty matched notifications would make the search worse at the thing it is mainly for.
         */
        const val NOTIFICATION_RESULT_LIMIT = 8

        /** Same reasoning as the notification limit: this is a launcher's search box. */
        const val WIDGET_RESULT_LIMIT = 6

        /** Same reasoning again: enough to be useful above the fold, not a results page. */
        const val WEB_RESULT_LIMIT = 5

        /**
         * Long enough that typing a word does not send one request per character, short enough
         * that the results are already there when the user pauses to read the app matches.
         */
        const val WEB_SEARCH_DEBOUNCE_MS = 450L
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

}

