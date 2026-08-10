package com.prism.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DrawerPageView(
    context: Context,
    private val onLaunch: (ComponentName) -> Unit,
    private val allowDragToDesktop: () -> Boolean,
) : FrameLayout(context) {

    private val adapter = DrawerAppsAdapter({ onLaunch(it) }, { allowDragToDesktop() })
    private var allApps: List<DrawerAppEntry> = emptyList()
    private var filterJob: Job? = null
    private var observeJob: Job? = null
    private var isReloading = false
    
    // Design tokens
    private val glowColor = PrismSettings.getGlowColor()

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        val binding = PageDrawerRootBinding.inflate(LayoutInflater.from(context), this, true)
        
        val layoutManager = GridLayoutManager(context, 4)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == DrawerAppsAdapter.VIEW_TYPE_GROUP) 4 else 1
            }
        }
        binding.drawerList.layoutManager = layoutManager
        binding.drawerList.adapter = adapter
        
        // Background is now handled by LauncherActivity for system-level blur
        
        // 1. Search Bar Neon Glow (Multi-layer Halo)
        binding.searchGlowContainer.background = NeonGlowDrawable(glowColor, 20f * resources.displayMetrics.density)
        binding.searchContainer.setStartIconTintList(ColorStateList.valueOf(glowColor))
        
        // 2. Settings Button Glow
        binding.settingsBtnWrapper.background = NeonGlowDrawable(glowColor, 32f * resources.displayMetrics.density, 4f)
        binding.settingsBtn.imageTintList = ColorStateList.valueOf(glowColor)
        
        // 3. App Title Neon (Tube effect)
        NeonGlowEngine.applyNeonText(binding.drawerPageHandle, resolveAttr(R.attr.prismTextPrimary), 16f)
        
        // 4. Staccato Bubble Neon
        binding.staccatoBubble.background = NeonGlowDrawable(glowColor, 36f * resources.displayMetrics.density, 8f)
        
        binding.settingsBtn.setOnClickListener {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }

        // Reload Button Glow (same treatment as Settings)
        binding.reloadBtnWrapper.background = NeonGlowDrawable(glowColor, 32f * resources.displayMetrics.density, 4f)
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
                // Apply a faint base glow for "unlit" state
                setShadowLayer(4f, 0f, 0f, Color.argb(40, 0, 0, 0))
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
                    
                    letterViews.forEach { 
                        it.alpha = 0.6f
                        it.setShadowLayer(4f, 0f, 0f, Color.argb(40, 0, 0, 0))
                        it.setTextColor(resolveAttr(R.attr.prismTextPrimary))
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateStaccato(touchY: Float, height: Int, alphabet: CharArray, binding: PageDrawerRootBinding, letterViews: List<TextView>) {
        val index = ((touchY / height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
        val char = alphabet[index]
        binding.staccatoBubbleText.text = char.toString()
        // Text inside bubble glows white
        NeonGlowEngine.applyNeonText(binding.staccatoBubbleText, Color.WHITE, 24f)
        
        // Follow the finger (centered on the bubble)
        val bubbleHalfHeight = binding.staccatoBubble.height / 2f
        binding.staccatoBubble.translationY = (touchY - bubbleHalfHeight).coerceIn(0f, height.toFloat() - binding.staccatoBubble.height)
        
        // Highlight logic with Neon Glow
        letterViews.forEachIndexed { i, tv ->
            if (i == index) {
                tv.alpha = 1f
                NeonGlowEngine.applyNeonText(tv, glowColor, 18f)
            } else {
                tv.alpha = 0.6f
                tv.setShadowLayer(4f, 0f, 0f, Color.argb(40, 0, 0, 0))
                tv.setTextColor(resolveAttr(R.attr.prismTextPrimary))
            }
        }
        
        scrollToLetter(char)
    }

    private fun scrollToLetter(char: Char) {
        val index = allApps.indexOfFirst { 
            if (char == '#') !it.label.first().isLetter()
            else it.label.startsWith(char, ignoreCase = true)
        }
        if (index != -1) {
            val binding = PageDrawerRootBinding.bind(this)
            (binding.drawerList.layoutManager as GridLayoutManager)
                .scrollToPositionWithOffset(index, 0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observeJob?.isActive == true) return
        val lifecycleOwner = context as? LifecycleOwner ?: return
        // Live-observes the DB (rather than a one-shot fetch) so an app installed/removed
        // while this page is already attached -- or via AppPackageReceiver in the background --
        // shows up immediately, without needing to force-stop and relaunch the whole app.
        observeJob = lifecycleOwner.lifecycleScope.launch {
            AppDatabase.get().installedAppDao().observeAll().collectLatest { entities ->
                allApps = withContext(Dispatchers.IO) { resolveDrawerEntries(entities) }
                val grouped = groupDrawerApps(allApps)
                adapter.submitList(grouped)
            }
        }
    }

    override fun onDetachedFromWindow() {
        observeJob?.cancel()
        observeJob = null
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
                val dao = AppDatabase.get().installedAppDao()
                dao.clearAll()
                dao.insertAll(queryLauncherApps(context))
            }
            isReloading = false
            android.widget.Toast.makeText(context, "Apps reloaded", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterApps(query: String) {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        filterJob?.cancel()
        filterJob = lifecycleOwner.lifecycleScope.launch {
            if (query.isBlank()) {
                val grouped = groupDrawerApps(allApps)
                adapter.submitList(grouped)
                return@launch
            }
            delay(150)
            val filtered = allApps.filter {
                it.label.contains(query, ignoreCase = true)
            }
            val grouped = groupDrawerApps(filtered)
            adapter.submitList(grouped)
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves DB rows to [DrawerAppEntry] via PackageManager. Called on every emission of the
     * live [InstalledAppDao.observeAll] flow in [onAttachedToWindow].
     *
     * Falls back to a live PackageManager scan if the DB is still empty
     * (i.e. [AppSyncWorker] hasn't finished its first run yet).
     */
    private fun resolveDrawerEntries(entities: List<InstalledAppEntity>): List<DrawerAppEntry> {
        val pm = context.packageManager

        if (entities.isEmpty()) {
            // Fallback: DB not yet populated — do a live scan so the drawer isn't blank.
            return loadLauncherApps(pm)
        }

        return entities.mapNotNull { entity ->
            try {
                val cn = ComponentName(entity.packageName, entity.activityClass)
                val ai = pm.getActivityInfo(cn, 0)
                DrawerAppEntry(
                    component = cn,
                    label = ai.loadLabel(pm).toString(),
                    icon = try { ai.loadIcon(pm) } catch (_: Throwable) { null },
                )
            } catch (_: Throwable) {
                // Package was uninstalled but AppPackageReceiver hasn't fired yet — skip.
                null
            }
        }.sortedBy { it.label.lowercase() }
    }
}

