package com.prism.launcher

import com.prism.launcher.accesspoint.AccessPointStore
import com.prism.core.MeshUtils
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.prism.launcher.databinding.ActivitySettingsBinding
import com.prism.launcher.databinding.ItemSettingHeaderBinding
import com.prism.launcher.databinding.ItemSettingNavBinding
import com.prism.launcher.databinding.ItemSettingToggleBinding
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import com.prism.launcher.browser.P2pDnsManager
import com.prism.launcher.stremio.StremioStore

class SettingsActivity : PrismBaseActivity() {

    /**
     * How the flat setting list is carved into top-level screens.
     *
     * Grouping is derived from the `SettingItem.Header` rows that already existed rather than
     * from a second, parallel description of the settings. That is deliberate and it is the
     * whole reason this refactor is safe: [buildItems] is untouched, so a setting cannot be
     * dropped by being forgotten in a new list. Anything whose header is not claimed below
     * still appears, under "Other" — the partition is total by construction.
     */
    private data class Group(val title: String, val summary: String, val headers: List<String>)

    private companion object {
        const val EXTRA_GROUP = "settings_group"

        val GROUPS = listOf(
            Group(
                "Launcher & Appearance",
                "Default page, gestures, layout, theme and fonts",
                listOf("Launcher", "Launcher Aesthetic", "Typography")
            ),
            Group(
                "Browser & Content",
                "Search engine, browsing behaviour and blocked domains",
                listOf("Browser", "Blocklist")
            ),
            Group(
                "Network, VPN & Mesh",
                "Tunnelling, hotspot gateway, WireGuard and decentralized DNS",
                listOf(
                    "Privacy & VPN",
                    // Claimed here rather than left to fall through to "Other": history sits with
                    // the other privacy controls, which is where someone looking for it will go.
                    "Privacy & History",
                    "Mesh Bootstrap Server",
                    "Access Points (Hotspot Gateway)",
                    "Native VPN Server (WireGuard)",
                    "Decentralized Name System"
                )
            ),
            Group(
                "Intelligence & Messaging",
                "AI engine, models, image generation, Nora, Aether and response behaviour",
                listOf(
                    "Intelligence & Messaging",
                    "Available LLM Models",
                    "Visual Intelligence (Diffusion)",
                    "Nora (Brain-Based Generation)",
                    "Aether (Second Brain-Based AI)",
                    "Response Behavior"
                )
            ),
            Group(
                "Virtualization",
                "Run a guest OS inside Prism",
                listOf("OS Virtualization")
            )
        )
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: SettingsAdapter

    /** Null on the root screen; otherwise the group whose settings are being shown. */
    private var activeGroup: String? = null

    private var searchQuery: String = ""

    /** One header's worth of settings, in the order [buildItems] produced them. */
    private class Block(val header: String, val items: List<SettingItem>)

    /**
     * Splits the flat list at its Header rows.
     *
     * Items before the first header (there are none today, but nothing enforces that) are kept
     * under an empty header so they cannot silently vanish.
     */
    private fun blocks(): List<Block> {
        val out = ArrayList<Block>()
        var header = ""
        var current = ArrayList<SettingItem>()
        for (item in buildItems()) {
            if (item is SettingItem.Header) {
                if (current.isNotEmpty() || header.isNotEmpty()) out.add(Block(header, current))
                header = item.title
                current = ArrayList()
            } else {
                current.add(item)
            }
        }
        if (current.isNotEmpty() || header.isNotEmpty()) out.add(Block(header, current))
        return out
    }

    /**
     * The Prism search engine's current address, pinned to the top of the root settings page and
     * copied to the clipboard on tap.
     *
     * The address is not a fixed string: on a mesh server node the engine is published at the
     * reserved domain so every peer can reach it, and anywhere else it is a loopback server for
     * this device only. Which of those is live depends on mesh state that can change while
     * Settings is open, so this reads it fresh on every refresh rather than caching it.
     */
    private fun searchAddressRow(): SettingItem {
        val address = com.prism.launcher.search.PrismSearchServer.address()
        val where = if (PrismSettings.isPrismSearchOnMesh())
            "Published on the mesh · tap to copy"
        else
            "Running locally on this device · tap to copy"
        return SettingItem.Nav("Prism Search · $address", where, {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prism Search", address))
            android.widget.Toast.makeText(this, "Copied $address", android.widget.Toast.LENGTH_SHORT).show()
        })
    }

    /** Which group a header belongs to, falling back so nothing is orphaned. */
    private fun groupOf(header: String): String =
        GROUPS.firstOrNull { header in it.headers }?.title ?: "Other"

    /** What the list should currently show: root, one group, or search results. */
    private fun displayItems(): List<SettingItem> {
        val all = blocks()

        if (searchQuery.isNotBlank()) return searchResults(all, searchQuery)

        val group = activeGroup
        if (group != null) {
            return all.filter { groupOf(it.header) == group }
                .flatMap { listOf(SettingItem.Header(it.header)) + it.items }
        }

        // Root: the search-engine address, then one row per group, plus "Other" if anything fell
        // outside the map.
        val present = all.groupBy { groupOf(it.header) }
        val ordered = GROUPS.map { it.title } + listOf("Other")
        return listOf(searchAddressRow()) + ordered.mapNotNull { title ->
            val members = present[title] ?: return@mapNotNull null
            if (members.all { it.items.isEmpty() }) return@mapNotNull null
            val summary = GROUPS.firstOrNull { it.title == title }?.summary
                ?: members.joinToString(", ") { it.header }
            SettingItem.Nav(title, summary, {
                startActivity(
                    Intent(this, SettingsActivity::class.java).putExtra(EXTRA_GROUP, title)
                )
            })
        }
    }

    /**
     * Flat, cross-group search.
     *
     * Every query token has to appear somewhere in the setting's title or its description, so
     * "wire guard port" finds the WireGuard port field and "dark" finds the theme control.
     * Matching includes the description because that is usually where the word someone
     * remembers actually lives; the result row then replaces that description with the path to
     * the setting, which is the thing you need in order to act on it — the same trade Android's
     * settings search makes.
     */
    private fun searchResults(all: List<Block>, query: String): List<SettingItem> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val results = ArrayList<SettingItem>()

        for (block in all) {
            val path = "${groupOf(block.header)} › ${block.header}"
            for (item in block.items) {
                val haystack = (titleOf(item) + " " + subtitleOf(item) + " " + block.header).lowercase()
                if (tokens.all { haystack.contains(it) }) results.add(withBreadcrumb(item, path))
            }
        }

        if (results.isEmpty()) {
            return listOf(SettingItem.Nav("No results", "Nothing matches \"$query\"", {}, isEnabled = false))
        }
        return results
    }

    private fun titleOf(item: SettingItem): String = when (item) {
        is SettingItem.Header -> item.title
        is SettingItem.Toggle -> item.title
        is SettingItem.Picker -> item.title
        is SettingItem.TextInput -> item.title
        is SettingItem.Nav -> item.title
        is SettingItem.Custom -> ""
    }

    private fun subtitleOf(item: SettingItem): String = when (item) {
        is SettingItem.Header -> ""
        is SettingItem.Toggle -> item.subtitle
        is SettingItem.Picker -> item.subtitle
        is SettingItem.TextInput -> item.subtitle
        is SettingItem.Nav -> item.subtitle
        is SettingItem.Custom -> ""
    }

    /** Same setting, same behaviour, with its description replaced by where it lives. */
    private fun withBreadcrumb(item: SettingItem, path: String): SettingItem = when (item) {
        is SettingItem.Toggle -> item.copy(subtitle = path)
        is SettingItem.Picker -> item.copy(subtitle = path)
        is SettingItem.TextInput -> item.copy(subtitle = path)
        is SettingItem.Nav -> item.copy(subtitle = path)
        is SettingItem.Header -> item
        is SettingItem.Custom -> item
    }

    /** Rebuilds whatever the screen is currently showing. */
    /**
     * Brings the Prism tunnel up, asking for VPN consent first when Android has not granted it.
     *
     * Android refuses to let an app open a VPN interface without an explicit one-time
     * confirmation, so a settings toggle cannot simply start one. Without this the switch would
     * flip and nothing would happen.
     */
    /** "3 sites · 12.4 MB", or "1 site · 0.4 MB". Shared by every row that reports cache size. */
    private fun webCacheSummary(): String {
        val sites = com.prism.launcher.browser.PrismWebCache.sites()
        val mb = sites.sumOf { it.bytes } / (1024.0 * 1024.0)
        return String.format(
            java.util.Locale.US, "%d site%s · %.1f MB",
            sites.size, if (sites.size == 1) "" else "s", mb
        )
    }

    /**
     * Turns mesh sharing of the web cache on or off.
     *
     * ASKS FIRST, BUT ONLY WHEN THERE IS SOMETHING TO HAND OVER. Switching this on publishes pages
     * the user has already visited, which is a fact about their browsing rather than a file they
     * chose -- so when a cache already exists the dialog says how much of it is about to leave the
     * device. With an empty cache there is nothing to disclose and nothing to confirm, so the
     * switch just takes effect: the checkbox is the decision, and a modal about zero sites would be
     * noise.
     *
     * The preference is written only once the user has agreed, so a cancelled dialog leaves nothing
     * half-applied; [refresh] then puts the switch back where it was.
     */
    private fun onWebCacheSharingChanged(enabled: Boolean) {
        if (!enabled) {
            PrismSettings.setWebCacheMeshSharing(false)
            com.prism.launcher.browser.PrismWebCache.unpublishAll(this)
            refresh()
            return
        }

        val cached = com.prism.launcher.browser.PrismWebCache.sites()
        if (cached.isEmpty()) {
            PrismSettings.setWebCacheMeshSharing(true)
            refresh()
            return
        }

        PrismDialogFactory.show(
            this,
            "Share your cached pages?",
            "${webCacheSummary()} will be served to every peer on the mesh, under " +
                "<site>${com.prism.launcher.browser.PrismWebCache.MESH_SUFFIX}.\n\n" +
                "These are pages you visited, so treat this as publishing part of your browsing " +
                "history. You can stop at any time, and peers keep no copy unless they mirror one.",
            positiveText = "Share",
            negativeText = "Cancel",
            onPositive = {
                PrismSettings.setWebCacheMeshSharing(true)
                com.prism.launcher.browser.PrismWebCache.publishAll(this)
                refresh()
            },
            onNegative = { refresh() }
        )
    }

    /** Lists what has been cached, with one way out: delete all of it. */
    private fun showCachedSites() {
        val sites = com.prism.launcher.browser.PrismWebCache.sites()
        if (sites.isEmpty()) return

        val labels = sites.map { site ->
            val mb = site.bytes / (1024.0 * 1024.0)
            String.format(
                java.util.Locale.US, "%s — %d page%s, %.1f MB",
                site.host, site.pages, if (site.pages == 1) "" else "s", mb
            )
        }

        val list = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_list_item_1, labels
            )
        }

        PrismDialogFactory.show(
            this,
            "Cached sites",
            "Individual sites can be opened or removed from the browser's Downloads list.",
            positiveText = "Delete all",
            negativeText = "Close",
            onPositive = {
                com.prism.launcher.browser.PrismWebCache.clearAll(this)
                Toast.makeText(this, "Web cache deleted", Toast.LENGTH_SHORT).show()
                refresh()
            },
            customView = list
        )
    }

    /** Asks before wiping, and says plainly that it cannot be undone. */
    private fun confirmClearHistory() {
        PrismDialogFactory.show(
            this,
            "Clear personal history?",
            "${com.prism.launcher.history.PrismHistory.count()} recorded entries will be deleted " +
                "from this device. This cannot be undone, and the assistant will no longer be able " +
                "to answer questions about your past activity.",
            positiveText = "Delete",
            negativeText = "Cancel",
            onPositive = {
                com.prism.launcher.history.PrismHistory.clear()
                Toast.makeText(this, "Personal history cleared", Toast.LENGTH_SHORT).show()
                refresh()
            }
        )
    }

    /**
     * Turns Prism's .exe handler on or off in the package manager.
     *
     * The intent filter is declared disabled in the manifest and enabled here, so Prism only shows
     * up in "Open with" for Windows executables when the user has actually asked for that. A
     * launcher that volunteers for file types it cannot open is a nuisance to everyone who installed
     * it for the other twenty features.
     */
    private fun setExeHandlerEnabled(enabled: Boolean) {
        runCatching {
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(
                    this, com.prism.launcher.virtualization.ExeLaunchActivity::class.java
                ),
                if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }.onFailure {
            PrismLogger.logWarning("Prism", "Could not change the .exe handler: ${it.message}")
        }
    }

    private fun startPrismVpnTunnel() {
        val consent = android.net.VpnService.prepare(this)
        if (consent != null) {
            startActivity(consent)
            return
        }
        com.prism.launcher.browser.PrivateDnsVpnService.start(this)

        // The service comes up on its own thread, so rows gated on a LIVE tunnel are still reading
        // "not connected" when the caller's refresh() runs a microsecond from now. One delayed
        // re-read lets them enable themselves, instead of the user having to leave the screen and
        // come back to discover the state changed.
        if (::binding.isInitialized) {
            binding.root.postDelayed({ if (!isFinishing) refresh() }, 1500L)
        }
    }


    private fun refresh() {
        if (::adapter.isInitialized) adapter.setItems(displayItems())
    }
    
    /**
     * Picks a folder to auto-caption.
     *
     * OpenDocumentTree rather than a path field, because that is the only picker Android will show
     * for a directory. The tree URI it returns is not a filesystem path, so [resolveTreePath]
     * converts it -- Prism already holds All Files Access for the agentic file tools, so a real
     * java.io.File is usable once the path is known, and the caption service can then be the same
     * code on both platforms.
     */
    private val captionDirPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = resolveTreePath(uri)
            if (path == null) {
                Toast.makeText(
                    this,
                    "That folder is on storage Prism can't address directly (SD card or a cloud provider). " +
                        "Pick one on internal storage.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startCaptionPreview(path)
            }
        }
    }

    /**
     * Picks a keyboard background.
     *
     * OpenDocument rather than GetContent, because the keyboard reads this URI again every time it
     * is shown — possibly weeks later, from a different process. GetContent hands out a grant that
     * dies with the activity; only OpenDocument's can be persisted.
     */
    private val writerBackgroundPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            PrismSettings.addWriterBackground(uri.toString())
            writerBackgroundGrid?.refresh()
            refresh()
        }

    /** Held so the picker callback and the settings list share one instance, with its own state. */
    private var writerBackgroundGrid: com.prism.launcher.writer.WriterBackgroundGrid? = null

    private val fontPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            copyFontToInternal(uri)
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri)
            val progressDialog = com.prism.launcher.messaging.ModelLoadProgressDialog(this)
            progressDialog.show()
            com.prism.launcher.messaging.ModelDownloadManager.copyUriToInternal(
                this, uri, fileName, isPickingImageModel,
                onProgress = { copied, total ->
                    if (total > 0) {
                        val pct = ((copied * 100) / total).toInt()
                        progressDialog.update("Copying $fileName… %.1f/%.1f MB".format(
                            copied / (1024.0 * 1024.0), total / (1024.0 * 1024.0)
                        ), pct)
                    } else {
                        progressDialog.update("Copying $fileName…")
                    }
                },
                onStage = { stage -> progressDialog.update(stage) }
            ) { success, error ->
                progressDialog.dismiss()
                if (success) {
                    refresh()
                } else {
                    Toast.makeText(this, "Import failed: $error Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private var isPickingImageModel = false

    private var ollamaScanResults: List<com.prism.launcher.messaging.OllamaDiscoveryService.OllamaServer> = emptyList()
    private var ollamaScanning = false

    private fun rescanOllama() {
        ollamaScanning = true
        refresh()
        lifecycleScope.launch(Dispatchers.IO) {
            val results = com.prism.launcher.messaging.OllamaDiscoveryService.scan()
            withContext(Dispatchers.Main) {
                ollamaScanResults = results
                ollamaScanning = false
                refresh()
                if (results.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No Ollama servers found on this network", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyFontToInternal(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val file = java.io.File(filesDir, "custom_font.ttf")
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        PrismSettings.setCustomFontPath(file.absolutePath)
                        refresh()
                        Toast.makeText(this@SettingsActivity, "Custom Font Applied", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyIsoToInternal(uri)
    }

    // Nora's backup/import pickers moved to NoraSettingsActivity along with the rest of her
    // options; an ActivityResultLauncher has to be registered by the screen that uses it.

    /**
     * QEMU is a native process and can't resolve content:// URIs — it needs a real path on disk.
     * Mirrors [copyFontToInternal]: copy the picked ISO into internal storage once, then store
     * that absolute path, instead of persisting the content:// URI string directly (which is what
     * made "loading an ISO" silently do nothing — VmController was handing QEMU a URI it could
     * never open).
     */
    private fun copyIsoToInternal(uri: Uri) {
        Toast.makeText(this, "Importing ISO…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val displayName = getFileNameFromUri(uri).ifBlank { "custom.iso" }
                val destDir = java.io.File(filesDir, "prism_os").apply { mkdirs() }
                val file = java.io.File(destDir, displayName)
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.IOException("Could not open the selected ISO")

                withContext(Dispatchers.Main) {
                    PrismSettings.setCustomIsoPath(file.absolutePath)
                    refresh()
                    Toast.makeText(this@SettingsActivity, "ISO imported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Failed to import ISO: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val vpnProfilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val contents = stream.reader().readText()
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            PrismSettings.setExternalVpnProfile(contents)
                            refresh()
                            Toast.makeText(this@SettingsActivity, "External VPN Profile Loaded", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * CakeChat's entry point, which does something different depending on what is on disk.
     *
     * ABSENT downloads the repository. Anything else opens the trainer -- including TRAINED, since
     * retraining on a new corpus is the only thing left to do with it. There is deliberately no
     * "download weights" branch: the upstream bucket that served them died with the project, so a
     * fresh install genuinely has nothing to answer with until it has been trained.
     */
    private fun openCakeChat() {
        val install = com.prism.launcher.cakechat.CakeChatInstall
        if (install.state(this) != com.prism.launcher.cakechat.CakeChatInstall.State.ABSENT) {
            startActivity(
                android.content.Intent(
                    this, com.prism.launcher.cakechat.CakeChatTrainingActivity::class.java
                )
            )
            return
        }

        android.widget.Toast.makeText(
            this, "Downloading CakeChat from GitHub…", android.widget.Toast.LENGTH_SHORT
        ).show()
        Thread({
            val ok = install.install(applicationContext)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (ok) "CakeChat installed. Open it again to train."
                    else "CakeChat download failed — see diagnostics.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
        }, "cakechat-install").start()
    }

    private fun downloadModel(name: String, url: String, isImageModel: Boolean = false) {
        com.prism.launcher.messaging.ModelDownloadManager.download(this, name, url, isImageModel)
        refresh()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (name.isEmpty()) {
            name = uri.path?.substringAfterLast('/') ?: "external_model.task"
        }
        
        // Safety: Ensure it doesn't have reserved characters and a sensible default extension
        if (!name.contains(".") && !isPickingImageModel) {
            name += ".task"
        }
        
        return name
    }


    private fun pickLocalModel() {
        PrismDialogFactory.show(
            this,
            "Model Type",
            "What kind of intelligence are you importing?",
            onPositive = {
                isPickingImageModel = false
                modelPicker.launch("*/*")
            },
            positiveText = "Text AI",
            onNegative = {
                isPickingImageModel = true
                modelPicker.launch("*/*")
            },
            negativeText = "Image Gen"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // Use custom TextView
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        // Setup Theme Toggle
        //
        // THE MODE IS READ ON EVERY TAP, not captured once. It used to be read into a local before
        // the listener was built, so the closure kept the value the screen was created with and
        // every tap computed its target from that stale copy -- which is why switching the theme
        // took several presses before anything happened.
        //
        // The switch is also left to AppCompat now. Changing the default night mode already
        // recreates every started activity; the old code ALSO did finish() + startActivity() on top
        // of that, so two recreations raced and whichever lost re-read the setting at the wrong
        // moment.
        fun paintThemeToggle() {
            binding.themeToggle.setImageResource(
                if (PrismSettings.getThemeMode() == PrismSettings.THEME_LIGHT)
                    R.drawable.ic_theme_moon
                else
                    R.drawable.ic_theme_sun
            )
        }
        paintThemeToggle()
        binding.themeToggle.setOnClickListener {
            val nextMode =
                if (PrismSettings.getThemeMode() == PrismSettings.THEME_LIGHT)
                    PrismSettings.THEME_DARK
                else
                    PrismSettings.THEME_LIGHT
            PrismSettings.setThemeMode(nextMode)
            paintThemeToggle()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (nextMode == PrismSettings.THEME_LIGHT)
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                else
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        // Root screen or one group's screen — same activity either way, so every
        // ActivityResultLauncher and helper stays exactly where it was.
        activeGroup = intent.getStringExtra(EXTRA_GROUP)
        binding.settingsTitle.text = activeGroup ?: "Settings"

        // Search on the root only; inside a group the list is already short.
        binding.settingsSearch.visibility = if (activeGroup == null) View.VISIBLE else View.GONE
        binding.settingsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchQuery = s?.toString().orEmpty()
                refresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Setup RecyclerView
        adapter = SettingsAdapter(displayItems(), this::onItemClick)
        binding.settingsList.layoutManager = LinearLayoutManager(this)
        binding.settingsList.adapter = adapter
    }

    override fun onBackPressed() {
        // Backing out of a search returns to the group list rather than leaving Settings,
        // which is what a search field inside a screen is expected to do.
        if (searchQuery.isNotEmpty()) {
            binding.settingsSearch.setText("")
            binding.settingsSearch.clearFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // Cloud model profiles (and which one is active) can change in CloudModelsActivity,
        // or on the Models desktop page, while this screen sits in the background -- refresh
        // so the "Manage Cloud Models" subtitle and AI Engine mode stay accurate on return.
        if (::adapter.isInitialized) refresh()
    }

    private fun getLocalIpAddress(): String {
        return MeshUtils.getLocalMeshIp()
    }

    private fun buildItems(): List<SettingItem> {
        // MINIMUM_INTERVAL_HOURS is the floor the scanner enforces, so the shortest option here
        // matches it rather than offering something it would silently clamp.
        val scannerIntervals = listOf(
            ModelListingScanner.MINIMUM_INTERVAL_HOURS, 2, 4, 6, 12, 24
        ).distinct()

        return listOf(
            SettingItem.Header("Launcher"),
            SettingItem.Picker(
                "Default page",
                "Which page shows when Prism opens",
                listOf("Left (Browser)", "Center (Desktop)", "Right (App drawer)"),
                PrismSettings.getDefaultPage(),
                { PrismSettings.setDefaultPage(it) }
            ),

            SettingItem.Header("Launcher Aesthetic"),
            SettingItem.Picker(
                "Icon Pack",
                "Choose the appearance of app icons",
                listOf("System Default") + IconPackEngine.getAvailableIconPacks(this).map { it.first },
                run {
                    val currentPkg = PrismSettings.getIconPackPackage()
                    val packs = IconPackEngine.getAvailableIconPacks(this)
                    val idx = packs.indexOfFirst { it.second == currentPkg }
                    if (idx == -1) 0 else idx + 1
                },
                { idx ->
                    if (idx == 0) {
                        PrismSettings.setIconPackPackage("")
                    } else {
                        val packs = IconPackEngine.getAvailableIconPacks(this)
                        PrismSettings.setIconPackPackage(packs[idx - 1].second)
                    }
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Show drawer labels",
                "Display app names below icons in the drawer",
                PrismSettings.getShowDrawerLabels(),
                { PrismSettings.setShowDrawerLabels(it) }
            ),
            SettingItem.Picker(
                "Glow Accent",
                "Choose the glow color for borders and navigation",
                listOf("Cyan", "Magenta", "Lime", "Gold", "Electric Blue"),
                when (PrismSettings.getGlowColor()) {
                    android.graphics.Color.parseColor("#FFFF00FF") -> 1
                    android.graphics.Color.parseColor("#FF00FF00") -> 2
                    android.graphics.Color.parseColor("#FFFFD700") -> 3
                    android.graphics.Color.parseColor("#FF2222FF") -> 4
                    else -> 0
                },
                { idx ->
                    val color = when (idx) {
                        1 -> "#FFFF00FF" // Magenta
                        2 -> "#FF00FF00" // Lime
                        3 -> "#FFFFD700" // Gold
                        4 -> "#FF2222FF" // Electric Blue
                        else -> "#FF7C9EFF" // Cyan
                    }
                    PrismSettings.setGlowColor(android.graphics.Color.parseColor(color))
                    refresh() // Refresh to update neon borders if needed
                }
            ),

            SettingItem.Header("Browser"),
            SettingItem.Picker(
                "Search engine",
                "Default engine for the address bar",
                listOf("Prism", "DuckDuckGo", "Google", "Bing", "Custom"),
                when (PrismSettings.getSearchEngine()) {
                    "prism" -> 0
                    "google" -> 2
                    "bing" -> 3
                    "custom" -> 4
                    else -> 1
                },
                { idx ->
                    val engine = when (idx) {
                        0 -> "prism"
                        2 -> "google"
                        3 -> "bing"
                        4 -> "custom"
                        else -> "ddg"
                    }
                    PrismSettings.setSearchEngine(engine)
                    if (engine == "custom") promptCustomSearchUrl()
                    refresh()
                }
            ),
            SettingItem.Picker(
                "Prism search crawl interval",
                "How often the crawler rebuilds the index",
                listOf("Off", "Every hour", "Every 2 hours", "Every 6 hours", "Every 12 hours", "Daily"),
                when (PrismSettings.getSearchCrawlIntervalHours()) {
                    0 -> 0; 1 -> 1; 6 -> 3; 12 -> 4; 24 -> 5; else -> 2
                },
                { idx ->
                    PrismSettings.setSearchCrawlIntervalHours(
                        when (idx) { 0 -> 0; 1 -> 1; 3 -> 6; 4 -> 12; 5 -> 24; else -> 2 }
                    )
                }
            ),
            SettingItem.TextInput(
                "Search seeds",
                "Where crawls start. One URL per line",
                PrismSettings.getUserSearchSeedsRaw(),
                { PrismSettings.setSearchSeeds(it); refresh() }
            ),
            SettingItem.Nav(
                "View all seeds",
                run {
                    val u = PrismSettings.getUserSearchSeeds().size
                    val d = PrismSettings.getDiscoveredSearchSeeds().size
                    "$u yours + $d found + ${PrismSettings.DEFAULT_SEARCH_SEEDS.size} built-in"
                },
                { showSeedList() }
            ),
            SettingItem.TextInput(
                "Maximum discovered seeds",
                "How many found sites to keep. -1 for no limit",
                PrismSettings.getMaxDiscoveredSeeds().toString(),
                { raw ->
                    val parsed = raw.trim().toIntOrNull()
                    if (parsed == null) {
                        android.widget.Toast.makeText(this, "Enter a number, or -1 for no limit", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        PrismSettings.setMaxDiscoveredSeeds(parsed)
                    }
                    refresh()
                },
                isSingleLine = true
            ),
            SettingItem.Nav(
                "Add a seed",
                "Add one site to the crawl without editing the list",
                { promptAddSearchSeed() }
            ),
            SettingItem.Nav(
                "Sites found by the crawler",
                run {
                    val n = PrismSettings.getDiscoveredSearchSeeds().size
                    if (n == 0) "None yet - they appear here after a crawl"
                    else "$n site(s) discovered automatically - tap to clear"
                },
                {
                    if (PrismSettings.getDiscoveredSearchSeeds().isEmpty()) {
                        android.widget.Toast.makeText(
                            this, "Nothing discovered yet", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Clear discovered sites?")
                            .setMessage(
                                "The crawler will rediscover them on its next run. Your own seeds " +
                                    "are not affected."
                            )
                            .setPositiveButton("Clear") { _, _ ->
                                PrismSettings.clearDiscoveredSearchSeeds()
                                refresh()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            ),
            SettingItem.Nav(
                "Diagnose search & browser",
                "Runs connection checks and opens the log",
                {
                    startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "Rebuild search index now",
                if (com.prism.launcher.search.PrismSearchServer.isCrawling()) "Crawling…"
                else com.prism.launcher.search.PrismSearchServer.lastCrawlSummary,
                {
                    com.prism.launcher.search.PrismSearchServer.crawlNow()
                    android.widget.Toast.makeText(this, "Crawl started", android.widget.Toast.LENGTH_SHORT).show()
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Enable JavaScript",
                "Allow JS execution in standard tabs",
                PrismSettings.getJsEnabled(),
                { PrismSettings.setJsEnabled(it) }
            ),
            SettingItem.Toggle(
                "Private by default",
                "New tabs open in private mode",
                PrismSettings.getPrivateByDefault(),
                { PrismSettings.setPrivateByDefault(it) }
            ),

            SettingItem.Header("Privacy & VPN"),
            SettingItem.Toggle(
                "Enable VPN Tunneling",
                "Route traffic through Prism or an external VPN",
                PrismSettings.getVpnTunnelingEnabled(),
                {
                    PrismSettings.setVpnTunnelingEnabled(it)
                    // The mesh cannot outlive its transport. Without this the toggle above would
                    // grey out while the gossip loop kept running over a tunnel that no longer
                    // exists.
                    if (!it && PrismSettings.getMeshEnabled()) {
                        PrismSettings.setMeshEnabled(false)
                        com.prism.launcher.mesh.PrismMeshService.stop()
                    }
                    // TELL THE ENGINE, rather than only recording the preference. This used to do
                    // neither -- it wrote the setting and nothing brought the backbone up, so
                    // enabling tunnelling did nothing observable until the app was restarted, and
                    // every feature gated on a live tunnel stayed unavailable in the meantime.
                    // start() reads the setting itself and idles the backbone when it is off.
                    runCatching { (application as PrismApp).tunnelEngine.start() }
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "VPN auto-start",
                "Automatically connect VPN when a private tab opens",
                PrismSettings.getVpnAutoStart(),
                { PrismSettings.setVpnAutoStart(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Picker(
                "VPN Mode",
                "Choose Prism P2P VPN or an external provider",
                listOf("Prism VPN", "External VPN"),
                if (PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_EXTERNAL) 1 else 0,
                {
                    PrismSettings.setVpnMode(if (it == 1) PrismSettings.VPN_MODE_EXTERNAL else PrismSettings.VPN_MODE_PRISM)
                    // Reconfigures for the new mode; without this the old one kept running.
                    runCatching { (application as PrismApp).tunnelEngine.start() }
                    refresh()
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Toggle(
                "Persistent VPN Server",
                "Keep Prism Server running even outside of private browsing (Backbone mode)",
                PrismSettings.getVpnServerAlwaysOn(),
                { enabled ->
                    PrismSettings.setVpnServerAlwaysOn(enabled)
                    if (enabled) {
                        // Switching this on now BRINGS THE SERVER UP, rather than only recording a
                        // preference and hoping something else starts the service later. Turning on
                        // a thing called "Persistent VPN Server" and finding no server running is
                        // the wrong outcome, and it was the previous one whenever the service was
                        // not already alive: start() was called, but nothing put the device into
                        // the server role or established the tunnel.
                        PrismSettings.setPrismVpnRole(PrismSettings.PRISM_ROLE_SERVER)
                        startPrismVpnTunnel()
                    }
                    refresh()
                    com.prism.launcher.browser.PrivateDnsVpnService.start(this)
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM
            ),
            SettingItem.Picker(
                "Prism VPN Role",
                "Serve as a node or connect as a client",
                listOf("Client", "Server"),
                if (PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER) 1 else 0,
                {
                    PrismSettings.setPrismVpnRole(if (it == 1) PrismSettings.PRISM_ROLE_SERVER else PrismSettings.PRISM_ROLE_CLIENT)
                    // Swapping role swaps which listeners should be bound, so the engine has to be
                    // told; it tears the old role down before building the new one.
                    runCatching { (application as PrismApp).tunnelEngine.start() }
                    refresh()
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM
            ),
            SettingItem.Picker(
                "VPN Protocol",
                "Choose protocol or let Prism auto-detect",
                listOf("Automatic (Detected)", "IKEv2", "L2TP", "Proxy Only"),
                when (PrismSettings.getVpnProtocolMode()) {
                    PrismSettings.VPN_PROTOCOL_IKEV2 -> 1
                    PrismSettings.VPN_PROTOCOL_L2TP -> 2
                    PrismSettings.VPN_PROTOCOL_PROXY -> 3
                    else -> 0
                },
                { idx ->
                    val mode = when(idx) {
                        1 -> PrismSettings.VPN_PROTOCOL_IKEV2
                        2 -> PrismSettings.VPN_PROTOCOL_L2TP
                        3 -> PrismSettings.VPN_PROTOCOL_PROXY
                        else -> PrismSettings.VPN_PROTOCOL_AUTO
                    }
                    PrismSettings.setVpnProtocolMode(mode)
                    refresh()
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.Nav(
                "Device IP Address",
                getLocalIpAddress(),
                {},
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Server Port",
                "Port to accept P2P nodes (Default 8080)",
                PrismSettings.getPrismVpnPort(),
                { PrismSettings.setPrismVpnPort(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Proxy Auth Password",
                "(Optional) Set Password for incoming clients",
                PrismSettings.getPrismVpnPassword(),
                { PrismSettings.setPrismVpnPassword(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Proxy Auth Username",
                "(Optional) Set Username for incoming clients",
                PrismSettings.getPrismVpnUsername(),
                { PrismSettings.setPrismVpnUsername(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.Nav(
                "Manage Prism Servers",
                "${PrismSettings.getPrismServers().size} servers saved (Auto-failover active)",
                { showServerFleetManager() },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.Header("Mesh Bootstrap Server"),
            SettingItem.Toggle(
                "Enable Mesh",
                if (PrismSettings.getVpnTunnelingEnabled())
                    "Background discovery/gossip for P2P DNS, hosting and model sharing. Off stops the always-on listener and gossip loop."
                else
                    "Needs VPN tunnelling \u2014 the mesh runs over the Prism tunnel, so it cannot reach a single peer without it.",
                PrismSettings.getMeshEnabled() && PrismSettings.getVpnTunnelingEnabled(),
                { enabled ->
                    PrismSettings.setMeshEnabled(enabled)
                    if (enabled) {
                        com.prism.launcher.mesh.PrismMeshService.start()
                    } else {
                        com.prism.launcher.mesh.PrismMeshService.stop()
                    }
                    refresh()
                },
                // GATED ON THE TUNNEL because every mesh packet rides it. Left switchable, the mesh
                // could be turned on with no transport underneath, and the symptom -- a mesh that
                // finds nobody, a model shop that is always empty -- gives no hint that the missing
                // piece is the tunnel.
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.TextInput(
                "Bootstrap Address",
                "Primary entry point for P2P DNS & Mesh search",
                PrismSettings.getMeshBootstrapAddress(),
                { PrismSettings.setMeshBootstrapAddress(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.TextInput(
                "Bootstrap Port",
                "Port of the bootstrap node (Default 8081)",
                PrismSettings.getMeshBootstrapPort(),
                { PrismSettings.setMeshBootstrapPort(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.Nav(
                "Configure External VPN",
                if (PrismSettings.getExternalVpnProfile().isEmpty()) "Setup WireGuard profile (.conf)" else "WireGuard Profile Loaded",
                {
                    vpnProfilePicker.launch("*/*")
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_EXTERNAL
            ),
            SettingItem.Nav(
                "App Whitelists",
                "Select apps to bypass the VPN tunnel",
                {
                    startActivity(android.content.Intent(this@SettingsActivity, com.prism.launcher.vpn.WhitelistActivity::class.java))
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Header("Privacy & History"),
            SettingItem.Toggle(
                "Keep a personal history",
                if (PrismSettings.getHistoryEnabled())
                    "Recording the pages you read, videos you watch, messages you send and files " +
                        "you open — ${com.prism.launcher.history.PrismHistory.count()} entries, " +
                        "on this device only. Private tabs are never recorded."
                else
                    "Keep a searchable record of what you have read, watched and opened. Stays on " +
                        "this device; private tabs are never recorded.",
                PrismSettings.getHistoryEnabled(),
                { enabled ->
                    PrismSettings.setHistoryEnabled(enabled)
                    // Switching it off also revokes the AI's access: leaving that on would mean a
                    // model could still read everything recorded up to the moment the user said stop.
                    if (!enabled) PrismSettings.setHistoryToolEnabled(false)
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Let AI search your history",
                when {
                    !PrismSettings.getHistoryEnabled() ->
                        "Turn on personal history first — there is nothing to search."
                    PrismSettings.getHistoryToolEnabled() ->
                        "The assistant can answer questions about your own past using the " +
                            "search_personal_history tool."
                    else ->
                        "Gives the assistant a tool for questions like “that article I read " +
                            "last month”. Note that a cloud AI engine would receive whatever " +
                            "it searches."
                },
                PrismSettings.getHistoryToolEnabled() && PrismSettings.getHistoryEnabled(),
                { enabled -> PrismSettings.setHistoryToolEnabled(enabled); refresh() },
                isEnabled = PrismSettings.getHistoryEnabled()
            ),
            SettingItem.Nav(
                "Clear personal history",
                if (com.prism.launcher.history.PrismHistory.count() == 0)
                    "Nothing recorded yet"
                else
                    "${com.prism.launcher.history.PrismHistory.count()} entries · deletes the file, not just the list",
                { confirmClearHistory() },
                isEnabled = com.prism.launcher.history.PrismHistory.count() > 0
            ),

            SettingItem.Toggle(
                "Locked private tabs",
                "Require biometric unlock to access private tabs",
                PrismSettings.getPrivateTabsLocked(),
                { PrismSettings.setPrivateTabsLocked(it) }
            ),

            // ── Web cache ──────────────────────────────────────────────────
            //
            // Two rows, in dependency order, each greyed out until what it needs is true. The
            // gating conditions are read from PrismWebCache rather than restated here, so a row
            // that looks available cannot disagree with what the next page load actually does.
            SettingItem.Toggle(
                "Allow web caching",
                when {
                    // Says which condition is missing, rather than leaving a greyed row unexplained.
                    com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason() != null ->
                        com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason()!!
                    PrismSettings.getWebCacheEnabled() ->
                        "Keeping a copy of the pages you visit · ${webCacheSummary()}. " +
                            "Private to this device unless you share it below."
                    else ->
                        "Keep a copy of each page you visit so it can be re-opened later without " +
                            "the original site. Private tabs are never cached."
                },
                // ANDed with availability, the way "Enable Mesh" is: a preference left on from a
                // session when the tunnel was up must not read as active once it is down.
                PrismSettings.getWebCacheEnabled() &&
                    com.prism.launcher.browser.PrismWebCache.cachingAvailable(),
                { enabled ->
                    PrismSettings.setWebCacheEnabled(enabled)
                    if (!enabled) {
                        // Sharing cannot outlive caching. Left latently true, re-enabling the cache
                        // later would silently start publishing again -- a decision the user made
                        // about a cache that no longer existed.
                        PrismSettings.setWebCacheMeshSharing(false)
                        com.prism.launcher.browser.PrismWebCache.unpublishAll(this)
                    }
                    refresh()
                },
                isEnabled = com.prism.launcher.browser.PrismWebCache.cachingAvailable()
            ),
            SettingItem.Toggle(
                "Make cached sites available on the Meshnet",
                when {
                    com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason() != null ->
                        com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason()!!
                    !PrismSettings.getWebCacheEnabled() ->
                        "Turn on web caching first — there is nothing to share until pages " +
                            "are being kept."
                    PrismSettings.getWebCacheMeshSharing() ->
                        "Serving ${webCacheSummary()} to peers as " +
                            "<site>${com.prism.launcher.browser.PrismWebCache.MESH_SUFFIX}"
                    else ->
                        "Let mesh peers open your cached pages. Published under " +
                            "${com.prism.launcher.browser.PrismWebCache.MESH_SUFFIX} — never " +
                            "as the real domain, so the live site keeps resolving normally."
                },
                PrismSettings.getWebCacheMeshSharing() &&
                    com.prism.launcher.browser.PrismWebCache.meshSharingAvailable(),
                { enabled -> onWebCacheSharingChanged(enabled) },
                isEnabled = com.prism.launcher.browser.PrismWebCache.meshSharingAvailable()
            ),
            SettingItem.Toggle(
                "Add cached videos to Lyke",
                when {
                    com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason() != null ->
                        com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason()!!
                    !PrismSettings.getWebCacheEnabled() ->
                        "Turn on web caching first — there are no videos to post until pages " +
                            "are being kept."
                    PrismSettings.getWebCacheLykeUpload() ->
                        "Every video cached from a page is posted to your Lyke feed, captioned " +
                            "with where it came from."
                    else ->
                        "Post each cached video to your Lyke feed. These are other people's " +
                            "videos and a Lyke post syncs to peers, so it publishes under your name."
                },
                PrismSettings.getWebCacheLykeUpload() &&
                    com.prism.launcher.browser.PrismWebCache.meshSharingAvailable(),
                { enabled -> PrismSettings.setWebCacheLykeUpload(enabled); refresh() },
                // Same gate as sharing: a live tunnel and caching switched on. Not gated on mesh
                // sharing of the CACHE, which is a different question -- Lyke has its own feed.
                isEnabled = com.prism.launcher.browser.PrismWebCache.meshSharingAvailable()
            ),
            SettingItem.Nav(
                "Cached sites",
                if (com.prism.launcher.browser.PrismWebCache.sites().isEmpty())
                    "Nothing cached yet"
                else
                    "${webCacheSummary()} · tap to review or delete",
                { showCachedSites() },
                isEnabled = com.prism.launcher.browser.PrismWebCache.sites().isNotEmpty()
            ),

            SettingItem.Header("Access Points (Hotspot Gateway)"),
            SettingItem.Nav(
                "Manage Access Points",
                "${AccessPointStore.getAccessPoints().size} hotspot(s) configured",
                {
                    startActivity(android.content.Intent(this@SettingsActivity, com.prism.launcher.accesspoint.AccessPointPortalActivity::class.java))
                }
            ),
            SettingItem.Toggle(
                "Enable DNS Proxy",
                "Listen on 0.0.0.0:53 for P2P DNS queries from connected devices",
                PrismSettings.getDnsProxyEnabled(),
                { enabled ->
                    PrismSettings.setDnsProxyEnabled(enabled)
                    if (enabled) {
                        startService(android.content.Intent(this@SettingsActivity, com.prism.launcher.browser.DnsProxyService::class.java))
                        Toast.makeText(this@SettingsActivity, "DNS Proxy started on port 53", Toast.LENGTH_SHORT).show()
                    } else {
                        stopService(android.content.Intent(this@SettingsActivity, com.prism.launcher.browser.DnsProxyService::class.java))
                        Toast.makeText(this@SettingsActivity, "DNS Proxy stopped", Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            ),
            SettingItem.Picker(
                "DNS Proxy Mode",
                "Behavior when domain is not in P2P DNS",
                listOf("P2P Isolation (NXDOMAIN)", "Fallback to Global DNS"),
                if (PrismSettings.getDnsProxyMode() == "fallback") 1 else 0,
                { idx ->
                    val mode = if (idx == 1) "fallback" else "p2p_only"
                    PrismSettings.setDnsProxyMode(mode)
                },
                isEnabled = PrismSettings.getDnsProxyEnabled()
            ),
            SettingItem.Nav(
                "P2P DNS Records",
                "${P2pDnsManager.getRecords().size} domains in local DNS ledger",
                {
                    startActivity(android.content.Intent(this@SettingsActivity, com.prism.launcher.browser.P2pDnsActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "P2P Web Hosting",
                "Serve local websites on .p2p domains",
                {
                    startActivity(android.content.Intent(this@SettingsActivity, com.prism.launcher.browser.P2pHostingActivity::class.java))
                }
            ),
            SettingItem.Toggle(
                "Host My Active Model",
                "Let other Prism peers on your mesh use your currently active local AI model",
                PrismSettings.getP2pModelHostingEnabled(),
                { enabled ->
                    PrismSettings.setP2pModelHostingEnabled(enabled)
                    if (enabled) {
                        val modelPath = PrismSettings.getLocalAiModelPath()
                        val displayName = PrismSettings.getImportedModels().find { it.path == modelPath }?.displayName
                            ?: modelPath.substringAfterLast('/').ifBlank { "Local Model" }
                        com.prism.launcher.mesh.P2pModelRegistry.announce(this, displayName)
                    } else {
                        com.prism.launcher.mesh.P2pModelRegistry.revoke(this)
                    }
                    refresh()
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
            ),
            run {
                val hostedModels = com.prism.launcher.mesh.P2pModelRegistry.getAll()
                    .filter { it.peerIp != MeshUtils.getLocalMeshIp() }
                val selected = PrismSettings.getSelectedP2pModel()
                val options = listOf("None (use Local/Cloud AI)") + hostedModels.map { "${it.peerIp} • ${it.modelName}" }
                val currentIdx = if (selected == null) {
                    0
                } else {
                    (hostedModels.indexOfFirst { it.peerIp == selected.peerIp && it.modelName == selected.modelName } + 1).coerceAtLeast(0)
                }
                SettingItem.Picker(
                    "P2P Models",
                    if (hostedModels.isEmpty()) "No models currently hosted by peers on your mesh" else "Use an AI model hosted by another peer on your mesh",
                    options,
                    currentIdx,
                    { idx ->
                        if (idx == 0) {
                            PrismSettings.clearSelectedP2pModel()
                        } else {
                            val m = hostedModels[idx - 1]
                            PrismSettings.setSelectedP2pModel(m.peerIp, m.modelName)
                            // The other half of the same exclusivity: a peer model outranks every
                            // local engine, so leaving CakeChat marked active would show two
                            // different models as the chosen one in two different screens.
                            PrismSettings.setUseCakeChat(false)
                        }
                        refresh()
                    },
                    isEnabled = PrismSettings.getVpnTunnelingEnabled() && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_CLIENT
                )
            },
            SettingItem.TextInput(
                "Primary DNS",
                "Used by the private VPN tunnel",
                PrismSettings.getPrimaryDns(),
                { PrismSettings.setPrimaryDns(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.TextInput(
                "Secondary DNS",
                "Used by the private VPN tunnel",
                PrismSettings.getSecondaryDns(),
                { PrismSettings.setSecondaryDns(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),

            SettingItem.Header("Native VPN Server (WireGuard)"),
            SettingItem.TextInput(
                "WireGuard Listen Port",
                "Port for direct VPN connections (Default 51820)",
                PrismSettings.getWgServerPort().toString(),
                { 
                    val p = it.toIntOrNull() ?: 51820
                    PrismSettings.setWgServerPort(p) 
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.TextInput(
                "Allowed IPs",
                "Traffic to route through VPN (e.g. 0.0.0.0/0 for everything)",
                PrismSettings.getWgAllowedIps(),
                { PrismSettings.setWgAllowedIps(it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Nav(
                "Copy Client Config",
                "Generate .conf for Windows WireGuard app",
                {
                    val config = PrismSettings.generateWgClientConfig()
                        .replace("YOUR_PHONE_IP_HERE", getLocalIpAddress())
                    
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prism WireGuard", config))
                    
                    PrismDialogFactory.show(this, "Config Copied", "Paste this into a new tunnel in your Windows WireGuard app. \n\nNOTE: Replace 'CLIENT_PRIVATE_KEY_HERE' in the config with your own generated key.")
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Nav(
                "Establish Mesh Trust",
                "Export Root CA to Downloads to enable HTTPS 'Secure' lock",
                {
                    val path = com.prism.launcher.vpn.PrismCertificateManager.exportRootCA(this)
                    if (path != null) {
                        PrismDialogFactory.show(this, "Root CA Exported", "The certificate has been saved to your Downloads folder ($path).\n\nTo see secure green locks, go to Android Settings -> Security -> Install from Storage -> CA Certificate and select this file.")
                    } else {
                        Toast.makeText(this, "Failed to export Root CA", Toast.LENGTH_SHORT).show()
                    }
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),

            SettingItem.Header("Intelligence & Messaging"),
            SettingItem.Nav(
                "Auto-caption a folder",
                "Caption every image in a folder and rename each file to match. Shows a preview " +
                    "first -- nothing is renamed until you confirm.",
                { captionDirPicker.launch(null) }
            ),
            SettingItem.Picker(
                "Prism AI Engine",
                "Choose between local on-device AI, cloud LLM, or a Local Cloud (Ollama) server found on your WiFi network",
                listOf("Local AI", "Cloud API", "Local Cloud (Ollama)"),
                when (PrismSettings.getAiMode()) {
                    PrismSettings.AI_MODE_CLOUD -> 1
                    PrismSettings.AI_MODE_LOCAL_CLOUD -> 2
                    else -> 0
                },
                { idx ->
                    val newMode = when (idx) {
                        1 -> PrismSettings.AI_MODE_CLOUD
                        2 -> PrismSettings.AI_MODE_LOCAL_CLOUD
                        else -> PrismSettings.AI_MODE_LOCAL
                    }
                    PrismSettings.setAiMode(newMode)
                    if (newMode == PrismSettings.AI_MODE_LOCAL_CLOUD && ollamaScanResults.isEmpty() && !ollamaScanning) {
                        rescanOllama()
                    } else {
                        refresh()
                    }
                }
            ),
            SettingItem.Nav(
                "Rescan Network for Ollama",
                if (ollamaScanning) "Scanning your WiFi network…" else "Find Ollama servers hosting models on your network",
                { if (!ollamaScanning) rescanOllama() },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL_CLOUD && !ollamaScanning
            ),
            run {
                val entries = ollamaScanResults.flatMap { server -> server.models.map { m -> server to m } }
                val selected = PrismSettings.getSelectedOllamaEndpoint()
                val options = if (entries.isEmpty()) listOf("None found yet") else entries.map { (s, m) -> "${s.host} • $m" }
                val currentIdx = entries.indexOfFirst { (s, m) -> selected != null && s.host == selected.host && m == selected.model }
                SettingItem.Picker(
                    "Ollama Server & Model",
                    if (entries.isEmpty()) "Tap \"Rescan Network for Ollama\" above to search" else "Pick which discovered model Prism should use",
                    options,
                    currentIdx.coerceAtLeast(0),
                    { idx ->
                        if (entries.isNotEmpty()) {
                            val (server, modelName) = entries[idx]
                            PrismSettings.setSelectedOllamaEndpoint(PrismSettings.OllamaEndpoint(server.host, server.port, modelName))
                            refresh()
                        }
                    },
                    isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL_CLOUD && entries.isNotEmpty()
                )
            },

            run {
                val cloudModels = PrismSettings.getCloudModels()
                val active = PrismSettings.getActiveCloudModel()
                SettingItem.Nav(
                    "Manage Cloud Models",
                    when {
                        cloudModels.isEmpty() -> "No cloud models saved yet"
                        active != null -> "${cloudModels.size} saved • Active: ${active.modelId}"
                        else -> "${cloudModels.size} saved • None active"
                    },
                    { startActivity(android.content.Intent(this, CloudModelsActivity::class.java)) },
                    isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_CLOUD
                )
            },

            SettingItem.Nav(
                "Local AI Model",
                "Select a .task, .gguf, or .bin LLM from storage",
                { pickLocalModel() },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),

            SettingItem.Header("Available LLM Models"),
            SettingItem.Nav(
                "Falcon3-1B-Instruct",
                "Pick a quantisation — the dialog lists what the repo actually publishes",
                {
                    QuantPickerDialog.show(
                        this, "Falcon3-1B-Instruct", PrismSettings.MODEL_FALCON_1B_REPO
                    )
                },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "Qwen2.5-1.5B (Expert)",
                "User-preferred high performance task bundle",
                { downloadModel("Qwen-1.5B", PrismSettings.MODEL_QWEN_1_5) },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Toggle(
                "Answer With CakeChat",
                if (com.prism.launcher.cakechat.CakeChatInstall.state(this) ==
                    com.prism.launcher.cakechat.CakeChatInstall.State.TRAINED
                )
                    "Sam's local replies come from your trained CakeChat instead of the GGUF model"
                else
                    "Train CakeChat first — there are no pretrained weights to fall back on",
                PrismSettings.getUseCakeChat(),
                { PrismSettings.setUseCakeChat(it); refresh() },
                isEnabled = com.prism.launcher.cakechat.CakeChatInstall.state(this) ==
                    com.prism.launcher.cakechat.CakeChatInstall.State.TRAINED
            ),
            SettingItem.Nav(
                "CakeChat (Replika)",
                when (com.prism.launcher.cakechat.CakeChatInstall.state(this)) {
                    com.prism.launcher.cakechat.CakeChatInstall.State.ABSENT ->
                        "Download the source from GitHub — a conditioned seq2seq, trained on your own corpus"
                    com.prism.launcher.cakechat.CakeChatInstall.State.INSTALLED ->
                        "Installed but untrained — tap to supply a corpus and train it"
                    com.prism.launcher.cakechat.CakeChatInstall.State.TRAINED ->
                        "Trained and ready — tap to train again on a new corpus"
                },
                { openCakeChat() },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "Phi-2 (Microsoft)",
                "High Intellect (2.7B params, ~1.5GB RAM)",
                { downloadModel("Phi-2", PrismSettings.MODEL_PHI_2) },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),

            SettingItem.Header("Visual Intelligence (Diffusion)"),
            SettingItem.Nav(
                "Search for Models",
                "Browse, search, and download text and image models from the Model Store",
                { startActivity(android.content.Intent(this, ModelStoreActivity::class.java)) }
            ),
            SettingItem.Nav(
                "Stable Diffusion v1.5",
                "Generate realistic images locally (~2GB RAM needed)",
                { downloadModel("SD-1.5", PrismSettings.MODEL_SD_1_5_CPU, isImageModel = true) },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),

            SettingItem.Header("Nora (Brain-Based Generation)"),
            run {
                // Everything Nora lives on its own screen now. Brain size alone is nine coupled
                // numbers plus a cost readout, which does not belong interleaved with the
                // AI-model and messaging options it used to sit among.
                val g = com.prism.launcher.nora.NoraConfig.geometry
                SettingItem.Nav(
                    "Nora",
                    "Brain size, training, self-test, backup — " +
                        "${com.prism.launcher.nora.NoraGeometry.formatCount(g.totalNeurons)} neurons",
                    { startActivity(android.content.Intent(this, com.prism.launcher.nora.NoraSettingsActivity::class.java)) }
                )
            },

            SettingItem.Header("Aether (Second Brain-Based AI)"),
            SettingItem.Nav(
                "Aether",
                "Spiking neurons, not predictive coding — training, biotrain/biogen toggles",
                { startActivity(android.content.Intent(this, com.prism.launcher.aether.AetherSettingsActivity::class.java)) }
            ),

            SettingItem.Header("Response Behavior"),
            SettingItem.Toggle(
                "Stream Responses",
                "Show tokens as they're generated instead of waiting for the full reply",
                PrismSettings.getStreamingEnabled(),
                {
                    PrismSettings.setStreamingEnabled(it)
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Max Tokens",
                "Cap on generated tokens per response. -1 = unlimited (generate until the model stops)",
                PrismSettings.getMaxTokens().toString(),
                { PrismSettings.setMaxTokens(it.toIntOrNull() ?: -1) },
                isSingleLine = true
            ),
            SettingItem.Picker(
                "KV Cache Compression",
                "Compress conversation memory for GGUF models — trades a little accuracy for lower RAM use and longer context",
                listOf("Off (Full Precision)", "Light (Q8, ~2x smaller)", "Max (Q4, ~4x smaller)"),
                when (PrismSettings.getKvCacheQuant()) {
                    PrismSettings.KV_CACHE_Q8_0 -> 1
                    PrismSettings.KV_CACHE_Q4_0 -> 2
                    else -> 0
                },
                {
                    val value = when (it) {
                        1 -> PrismSettings.KV_CACHE_Q8_0
                        2 -> PrismSettings.KV_CACHE_Q4_0
                        else -> PrismSettings.KV_CACHE_F16
                    }
                    PrismSettings.setKvCacheQuant(value)
                    refresh()
                }
            ),
            SettingItem.Picker(
                "AI Backend",
                run {
                    val base = "Force which backend AI text generation uses, for both GGUF and .task models. GPU is faster when well-supported (falls back to CPU automatically if it fails); switch to CPU if generation is slow or unstable on your device. GPU only speeds up Q4_0/Q8_0 GGUF quants — K-quants (e.g. Q2_K) always run on CPU regardless."
                    if (com.prism.launcher.messaging.GgufInferenceService.hasHexagonSupport()) {
                        "$base NPU (Qualcomm Hexagon) offloads to your device's neural processor for GGUF models."
                    } else {
                        "$base NPU isn't available in this build — it requires a Qualcomm Hexagon SDK at build time, which isn't configured here."
                    }
                },
                run {
                    val options = if (com.prism.launcher.messaging.GgufInferenceService.hasHexagonSupport()) {
                        listOf("CPU", "GPU", "NPU")
                    } else {
                        listOf("CPU", "GPU")
                    }
                    options
                },
                PrismSettings.getAiBackend().coerceAtMost(
                    (if (com.prism.launcher.messaging.GgufInferenceService.hasHexagonSupport()) 2 else 1)
                ),
                { idx ->
                    PrismSettings.setAiBackend(idx)
                    refresh()
                }
            ),
            SettingItem.Nav(
                "Prism Swap",
                "Lets Sam's local .gguf models spill onto disk when they don't fit in free RAM -- swap file size and activation thresholds" +
                    if (PrismSettings.getPrismSwapEnabled()) " (on)" else " (off)",
                { startActivity(android.content.Intent(this, com.prism.launcher.messaging.PrismSwapSettingsActivity::class.java)) }
            ),
            SettingItem.Nav(
                "Dataset Downloads",
                "Find and download training datasets from Hugging Face/GitHub for Nora and Aether" +
                    if (PrismSettings.getDatasetAutoDownloadEnabled()) " (automatic downloads on)" else "",
                { startActivity(android.content.Intent(this, com.prism.launcher.messaging.DatasetDownloadSettingsActivity::class.java)) }
            ),
            run {
                val intervalHours = listOf(1, 2, 4, 6, 12, 24)
                val nebulaActive = SlotPreferences().getAssignments().any { it is SlotAssignment.NebulaSocial }
                SettingItem.Picker(
                    "Nebula Post Generation Interval",
                    if (nebulaActive)
                        "How often the background service invents new Nebula personas/posts using your AI model."
                    else
                        "Add the Nebula Social page to a desktop slot to enable background post generation.",
                    intervalHours.map { "$it hour${if (it == 1) "" else "s"}" },
                    intervalHours.indexOf(PrismSettings.getNebulaGenerationIntervalHours()).coerceAtLeast(0),
                    { idx ->
                        PrismSettings.setNebulaGenerationIntervalHours(intervalHours[idx])
                        com.prism.launcher.social.SocialBotWorker.schedule(this)
                        refresh()
                    },
                    isEnabled = nebulaActive
                )
            },

            SettingItem.Header("Blocklist"),
            SettingItem.Nav(
                "Manage blocklist",
                "Add, remove, or import custom blocked domains",
                {
                    val intent = android.content.Intent(this, BlocklistActivity::class.java)
                    startActivity(intent)
                }
            ),
            SettingItem.Header("Decentralized Name System"),
            SettingItem.Nav(
                "Manage P2P DNS",
                "View and edit domain mappings in the mesh ledger",
                {
                    startActivity(android.content.Intent(this, com.prism.launcher.browser.P2pDnsActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "P2P Web Hosting",
                "Host local folders as websites on the mesh",
                {
                    startActivity(android.content.Intent(this, com.prism.launcher.browser.P2pHostingActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "System Diagnostics",
                "Live terminal console and error logs",
                {
                    startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
                }
            ),

            SettingItem.Header("Typography"),
            SettingItem.Picker(
                "Font Style",
                "Choose the default app & browser font",
                listOf("System Default", "Nasalization (Modern)", "Custom File (.ttf)"),
                when(PrismSettings.getFontStyle()) {
                    PrismSettings.FONT_STYLE_NASALIZATION -> 1
                    PrismSettings.FONT_STYLE_CUSTOM -> 2
                    else -> 0
                },
                { idx ->
                    val style = when(idx) {
                        1 -> PrismSettings.FONT_STYLE_NASALIZATION
                        2 -> PrismSettings.FONT_STYLE_CUSTOM
                        else -> PrismSettings.FONT_STYLE_DEFAULT
                    }
                    PrismSettings.setFontStyle(style)
                    // If Custom is selected but no path exists, prompt to pick
                    if (style == PrismSettings.FONT_STYLE_CUSTOM && PrismSettings.getCustomFontPath().isEmpty()) {
                        fontPicker.launch("*/*")
                    } else {
                        Toast.makeText(this, "Restart app to fully apply fonts", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            SettingItem.Nav(
                "Select Custom Font",
                "Load a .ttf or .otf file from storage",
                { fontPicker.launch("*/*") },
                isEnabled = PrismSettings.getFontStyle() == PrismSettings.FONT_STYLE_CUSTOM
            ),

            SettingItem.Toggle(
                "AI Search Summaries",
                "Summarise Prism search results with a model you run yourself. Needs an imported " +
                    "local model or an Ollama server — cloud models are deliberately not used.",
                PrismSettings.getSearchAiSummary(),
                { PrismSettings.setSearchAiSummary(it); refresh() }
            ),
            SettingItem.Nav(
                "Summary Status",
                com.prism.launcher.search.PrismSearchSummary.unavailableReason(),
                { refresh() },
                isEnabled = PrismSettings.getSearchAiSummary()
            ),

            // ── Prism Writer ─────────────────────────────────────────────────
            SettingItem.Header("Prism Writer (Keyboard)"),
            SettingItem.Nav(
                "Enable Prism Writer",
                "Opens Android's keyboard settings — a keyboard can only be enabled by the system",
                {
                    runCatching {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                        )
                    }
                }
            ),
            SettingItem.Picker(
                "Keyboard Theme",
                "Follows the app you are typing in unless set explicitly",
                listOf("Follow system", "Always light", "Always dark"),
                when (PrismSettings.getWriterTheme()) {
                    PrismSettings.WRITER_THEME_LIGHT -> 1
                    PrismSettings.WRITER_THEME_DARK -> 2
                    else -> 0
                },
                { idx ->
                    PrismSettings.setWriterTheme(
                        when (idx) {
                            1 -> PrismSettings.WRITER_THEME_LIGHT
                            2 -> PrismSettings.WRITER_THEME_DARK
                            else -> PrismSettings.WRITER_THEME_SYSTEM
                        }
                    )
                    refresh()
                }
            ),
            SettingItem.Custom(
                (writerBackgroundGrid ?: com.prism.launcher.writer.WriterBackgroundGrid(this).also {
                    writerBackgroundGrid = it
                    it.onAddRequested = { writerBackgroundPicker.launch(arrayOf("image/*")) }
                    // Colour rows are enabled from this same flag, so the list has to be rebuilt
                    // when the active image changes -- otherwise they stay greyed out until the
                    // screen is reopened.
                    it.onActiveChanged = { _ -> refresh() }
                })
            ),
            SettingItem.Picker(
                "Keyboard Background Dim",
                "How much a background image is darkened so key labels stay readable",
                listOf("None", "Light", "Medium", "Heavy"),
                when (PrismSettings.getWriterBackgroundDim()) {
                    0 -> 0
                    in 1..25 -> 1
                    in 26..50 -> 2
                    else -> 3
                },
                { index ->
                    PrismSettings.setWriterBackgroundDim(
                        when (index) { 0 -> 0; 1 -> 20; 2 -> 40; else -> 65 }
                    )
                    writerBackgroundGrid?.refresh()
                    refresh()
                },
                isEnabled = PrismSettings.getWriterBackgroundImage().isNotBlank(),
            ),
            SettingItem.Nav(
                "Dictionary",
                "Your word lists and redefinitions",
                {
                    startActivity(
                        android.content.Intent(
                            this, com.prism.launcher.writer.WriterDictionaryActivity::class.java
                        )
                    )
                }
            ),
            SettingItem.Toggle(
                "Suggestion Strip",
                "Three candidates while you type, the likeliest in the middle",
                PrismSettings.getWriterSuggestions(),
                { PrismSettings.setWriterSuggestions(it); refresh() }
            ),
            hexColour(
                "Keyboard Colour",
                "The panel behind the keys. Blank follows the theme.",
                PrismSettings.getWriterPanelColor(),
                { PrismSettings.setWriterPanelColor(it) },
                enabled = PrismSettings.getWriterBackgroundImage().isBlank(),
            ),
            hexColour(
                "Key Colour",
                "The keys themselves. Blank follows the theme.",
                PrismSettings.getWriterKeyColor(),
                { PrismSettings.setWriterKeyColor(it) },
            ),
            hexColour(
                "Key Text Colour",
                "Letters and icons. Blank follows the theme.",
                PrismSettings.getWriterKeyTextColor(),
                { PrismSettings.setWriterKeyTextColor(it) },
            ),
            hexColour(
                "Accent Colour",
                "Return key, and the highlight on long-press alternates.",
                PrismSettings.getWriterAccentColor(),
                { PrismSettings.setWriterAccentColor(it) },
            ),
            hexColour(
                "Swipe Trail Colour",
                "The glide path. Blank uses the default red.",
                PrismSettings.getWriterTrailColor(),
                { PrismSettings.setWriterTrailColor(it) },
            ),
            SettingItem.Toggle(
                "Glowing Swipe Trail",
                "Draws the glide path with a soft glow",
                PrismSettings.getWriterTrailGlow(),
                { PrismSettings.setWriterTrailGlow(it); refresh() }
            ),
            SettingItem.Toggle(
                "Swipe Typing",
                "Glide across letters to write a whole word",
                PrismSettings.getWriterSwipeEnabled(),
                { PrismSettings.setWriterSwipeEnabled(it); refresh() }
            ),
            SettingItem.Toggle(
                "Autocorrect",
                "Fixes a word when it is finished, never while it is being typed",
                PrismSettings.getWriterAutocorrect(),
                { PrismSettings.setWriterAutocorrect(it); refresh() }
            ),
            SettingItem.Picker(
                "Key Vibration",
                "Strength of the tick under each key",
                listOf("Off", "Light", "Medium", "Strong"),
                when (PrismSettings.getWriterHapticsMs()) {
                    0 -> 0
                    in 1..10 -> 1
                    in 11..20 -> 2
                    else -> 3
                },
                { idx ->
                    PrismSettings.setWriterHapticsMs(listOf(0, 8, 14, 25)[idx])
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "AI Assisted Typing",
                "Needs an active model. Also enables the live-translate key on the keyboard.",
                PrismSettings.getWriterAiAssist(),
                { PrismSettings.setWriterAiAssist(it); refresh() }
            ),
            SettingItem.TextInput(
                "Translate Into",
                PrismSettings.getWriterTranslateTarget(),
                PrismSettings.getWriterTranslateTarget(),
                { PrismSettings.setWriterTranslateTarget(it); refresh() },
                isEnabled = PrismSettings.getWriterAiAssist(),
                isSingleLine = true
            ),
            SettingItem.Toggle(
                "Speak Translations",
                "Reads the translation aloud in the target language as well as typing it",
                PrismSettings.getWriterSpeakTranslation(),
                { PrismSettings.setWriterSpeakTranslation(it); refresh() },
                isEnabled = PrismSettings.getWriterAiAssist()
            ),

            // ── Mining ───────────────────────────────────────────────────────
            SettingItem.Header("Wallet Mining"),
            SettingItem.Picker(
                "Mining Mode",
                when (PrismSettings.getMiningMode()) {
                    PrismSettings.MINING_MODE_SOLO ->
                        "Solo: whole blocks only, and it needs your own full node"
                    PrismSettings.MINING_MODE_MESH ->
                        com.prism.launcher.wallet.MeshPool.unavailableReason()
                            ?: ("Mesh pool: this device holds the pool connection and shares the " +
                                "work with " + com.prism.launcher.wallet.MeshPool.memberCount() +
                                " peer(s)")
                    else ->
                        "Pool: paid per share, which is the only mode where anything happens on a phone"
                },
                // The mesh entry is listed whatever the mesh is doing, and refused on selection
                // instead of being hidden. A mode that vanishes gives a user nothing to act on;
                // one that explains what it needs tells them to turn the mesh on.
                listOf("Pool (Stratum)", "Solo (your own node)", "Mesh pool (share the connection)"),
                when (PrismSettings.getMiningMode()) {
                    PrismSettings.MINING_MODE_SOLO -> 1
                    PrismSettings.MINING_MODE_MESH -> 2
                    else -> 0
                },
                { idx ->
                    if (idx == 2 && !com.prism.launcher.wallet.MeshPool.isAvailable()) {
                        android.widget.Toast.makeText(
                            this,
                            com.prism.launcher.wallet.MeshPool.unavailableReason()
                                ?: "Mesh pooling is unavailable.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        PrismSettings.setMiningMode(
                            when (idx) {
                                1 -> PrismSettings.MINING_MODE_SOLO
                                2 -> PrismSettings.MINING_MODE_MESH
                                else -> PrismSettings.MINING_MODE_POOL
                            }
                        )
                        // A device in mesh mode answers other people's coordinators even when it
                        // is not mining itself; that is what makes it a pool rather than a list of
                        // devices that happen to be mining.
                        com.prism.launcher.wallet.MeshPool.setMemberEnabled(idx == 2)
                    }
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Host Prism's Own Node",
                "Run the node on this device instead of connecting to one you already have. " +
                    "Forces pruned mode on every chain that supports it; chains that cannot " +
                    "prune need a 1-2 TB USB-C drive.",
                PrismSettings.getSelfHostNode(),
                { PrismSettings.setSelfHostNode(it); refresh() },
                isEnabled = PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO
            ),
            SettingItem.TextInput(
                "Solo Node RPC URL",
                // Disabled rather than hidden when Prism hosts its own node: a field that vanishes
                // reads as a bug, whereas a greyed one with this subtitle explains itself.
                if (PrismSettings.getSelfHostNode())
                    "Not used — Prism is hosting its own node on 127.0.0.1"
                else PrismSettings.getSoloNodeUrl().ifBlank { "e.g. http://192.168.1.10:8332" },
                PrismSettings.getSoloNodeUrl(),
                { PrismSettings.setSoloNodeUrl(it); refresh() },
                isEnabled = PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO &&
                    !PrismSettings.getSelfHostNode(),
                isSingleLine = true
            ),
            // ── Selling models ───────────────────────────────────────────────
            SettingItem.Header("Selling Models"),
            SettingItem.Toggle(
                "Check On Every Sale",
                "Check GitHub and Hugging Face the moment somebody buys, instead of waiting for " +
                    "the periodic sweep. If the model is not publicly downloadable the sale is " +
                    "approved and the buyer's payment is taken immediately. If it is, the listing " +
                    "is removed and you are told why — Prism only allows selling models you made.",
                ModelListingScanner.verifyOnSale(this),
                { ModelListingScanner.setVerifyOnSale(this, it); refresh() }
            ),
            SettingItem.Picker(
                "Check Interval",
                // DISABLED RATHER THAN HIDDEN when checking on every sale. A control that
                // disappears reads as a bug and hides what the fallback cadence would be; a greyed
                // one with this subtitle says why it is not in use and what it would do if it were.
                if (ModelListingScanner.verifyOnSale(this))
                    "Not used — every sale is checked as it happens"
                else
                    "How often your listings are re-checked against GitHub and Hugging Face",
                scannerIntervals.map { "$it hour${if (it == 1) "" else "s"}" },
                scannerIntervals.indexOf(ModelListingScanner.intervalHours(this))
                    .coerceAtLeast(0),
                { idx -> ModelListingScanner.setIntervalHours(this, scannerIntervals[idx]); refresh() },
                isEnabled = !ModelListingScanner.verifyOnSale(this)
            ),

            SettingItem.TextInput(
                "Solo Node Credentials",
                // The same field means two different things depending on the toggle above, so the
                // subtitle has to say which one is in force.
                if (PrismSettings.getSelfHostNode())
                    "Credentials Prism's own node will be configured with (blank generates one)"
                else if (PrismSettings.getSoloNodeCredentials().isBlank()) "rpcuser:rpcpassword"
                else "Set",
                PrismSettings.getSoloNodeCredentials(),
                { PrismSettings.setSoloNodeCredentials(it); refresh() },
                isEnabled = PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO,
                isSingleLine = true
            ),
            SettingItem.Toggle(
                "Compile Missing Libraries On Device",
                "EXPERIMENTAL. Lets Prism build mining libraries it does not ship, then load them " +
                    "into its own process.",
                PrismSettings.getExperimentalCompiler(),
                { wanted ->
                    // Enabling ALWAYS warns, every single time -- this is the largest trust
                    // decision the app makes, and a toggle that goes quiet after the first
                    // acceptance is how people forget it is on.
                    if (wanted) {
                        PrismDialogFactory.show(
                            this@SettingsActivity,
                            "This is highly experimental",
                            "Prism will download source code, compile it on this device, and load " +
                                "the result into its own process. Compiled code runs with EVERY " +
                                "permission Prism has — storage, network, notifications, the " +
                                "wallet.\n\n" +
                                "Source archives are checked against a pinned hash before " +
                                "anything is compiled, but you are still choosing to run code " +
                                "that was not reviewed or signed by anyone.\n\n" +
                                "Builds take a long time, run the CPU at full load, and may fail " +
                                "for reasons that are not obvious.",
                            positiveText = "I understand, enable it",
                            negativeText = "Leave it off",
                            onPositive = { PrismSettings.setExperimentalCompiler(true); refresh() },
                            onNegative = { refresh() },
                        )
                    } else {
                        PrismSettings.setExperimentalCompiler(false)
                        refresh()
                    }
                }
            ),
            SettingItem.TextInput(
                "Toolchain Pack URL",
                PrismSettings.getToolchainUrl().ifBlank {
                    "Not set — no clang pack is bundled or hosted yet"
                },
                PrismSettings.getToolchainUrl(),
                { PrismSettings.setToolchainUrl(it); refresh() },
                isEnabled = PrismSettings.getExperimentalCompiler(),
                isSingleLine = true
            ),
            SettingItem.Picker(
                "Mining Threads",
                "0 lets Prism pick from the core count, leaving one core for everything else",
                listOf("Automatic", "1", "2", "3", "4", "6", "8"),
                listOf(0, 1, 2, 3, 4, 6, 8).indexOf(PrismSettings.getMiningThreads()).coerceAtLeast(0),
                { idx -> PrismSettings.setMiningThreads(listOf(0, 1, 2, 3, 4, 6, 8)[idx]); refresh() }
            ),

            // ── OS Virtualization ────────────────────────────────────────────
            SettingItem.Header("OS Virtualization"),
            SettingItem.Toggle(
                "Switch to running Windows executables",
                when {
                    !PrismSettings.getWindowsMode() ->
                        "Turns the Virtualization page into a Windows runtime (Wine + box64) and " +
                            "lets Prism open .exe files. Replaces the guest-OS view while on."
                    com.prism.launcher.virtualization.WineInstaller.isInstalled(this) ->
                        "Windows mode is on — the Virtualization page runs .exe files, and " +
                            "Prism appears in the chooser for them."
                    else ->
                        "Windows mode is on, but the compatibility layer is not installed yet. " +
                            "Open the Virtualization page to install it."
                },
                PrismSettings.getWindowsMode(),
                { enabled ->
                    PrismSettings.setWindowsMode(enabled)
                    // The .exe chooser entry is a manifest component, toggled at runtime: leaving
                    // Prism in the Open-with list for a mode the user switched off would offer to
                    // open files it would then refuse.
                    setExeHandlerEnabled(enabled)
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Windows layer source",
                PrismSettings.getWindowsLayerUrl().ifBlank {
                    "Not set — a URL to a Wine + box64 + rootfs archive"
                },
                PrismSettings.getWindowsLayerUrl(),
                { PrismSettings.setWindowsLayerUrl(it) },
                isEnabled = PrismSettings.getWindowsMode()
            ),
            SettingItem.Toggle(
                "Enable Virtualization",
                "Route app launches through the virtualization page",
                PrismSettings.getVirtualizationEnabled(),
                {
                    PrismSettings.setVirtualizationEnabled(it)
                    refresh()
                }
            ),
            SettingItem.Picker(
                "Virtualization Mode",
                "Select the OS to run in the virtualization page",
                listOf("PrismOS (lightweight AOSP)", "Custom ISO"),
                if (PrismSettings.getVirtualizationMode() == PrismSettings.VIRT_MODE_PRISM_OS) 0 else 1,
                { idx ->
                    PrismSettings.setVirtualizationMode(if (idx == 0) PrismSettings.VIRT_MODE_PRISM_OS else PrismSettings.VIRT_MODE_CUSTOM_ISO)
                    refresh()
                },
                isEnabled = PrismSettings.getVirtualizationEnabled()
            ),
            SettingItem.Nav(
                "Select ISO File",
                PrismSettings.getCustomIsoPath().ifBlank { "No file selected" },
                { isoPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                isEnabled = PrismSettings.getVirtualizationEnabled() &&
                    PrismSettings.getVirtualizationMode() == PrismSettings.VIRT_MODE_CUSTOM_ISO
            ),

            // ── Medical ──────────────────────────────────────────────────────
            //
            // Everything here is shown on the lock screen to whoever is holding the phone. That is
            // the point -- the reader is a stranger giving first aid -- but it means the section
            // says so before the first field rather than after.
            SettingItem.Header("Medical"),
            SettingItem.Nav(
                "Lock screen",
                when {
                    !com.prism.launcher.lock.LockStore.isConfigured(this) ->
                        "Off — set a PIN, password or pattern"
                    com.prism.launcher.lock.LockStore.hasDuress(this) ->
                        "On, with an emergency code set"
                    else -> "On — no emergency code yet"
                },
                { startActivity(Intent(this, com.prism.launcher.lock.LockSetupActivity::class.java)) }
            ),
            SettingItem.Toggle(
                "Show the medical card on the lock screen",
                "Readable without unlocking, which is the point — a paramedic cannot unlock it. " +
                    "Nothing is shown while every field below is empty.",
                PrismSettings.getMedicalOnLock(),
                { PrismSettings.setMedicalOnLock(it); refresh() },
                isEnabled = com.prism.launcher.lock.LockStore.isConfigured(this)
            ),
            SettingItem.Picker(
                "Blood type",
                com.prism.launcher.lock.MedicalRecord.get(this).bloodType.ifBlank { "Not set" },
                com.prism.launcher.lock.MedicalRecord.BLOOD_TYPES.map { it.ifBlank { "Not set" } },
                com.prism.launcher.lock.MedicalRecord.BLOOD_TYPES
                    .indexOf(com.prism.launcher.lock.MedicalRecord.get(this).bloodType)
                    .coerceAtLeast(0),
                { index ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(
                        this,
                        record.copy(bloodType = com.prism.launcher.lock.MedicalRecord.BLOOD_TYPES[index])
                    )
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Severe allergies",
                com.prism.launcher.lock.MedicalRecord.get(this).allergies
                    .ifBlank { "Penicillin, peanuts, latex…" },
                com.prism.launcher.lock.MedicalRecord.get(this).allergies,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(allergies = value))
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Conditions",
                com.prism.launcher.lock.MedicalRecord.get(this).conditions
                    .ifBlank { "Epilepsy, diabetes, anticoagulants…" },
                com.prism.launcher.lock.MedicalRecord.get(this).conditions,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(conditions = value))
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Medications",
                com.prism.launcher.lock.MedicalRecord.get(this).medications.ifBlank { "None recorded" },
                com.prism.launcher.lock.MedicalRecord.get(this).medications,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(medications = value))
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Do not resuscitate",
                "Shown on the lock card as your stated wish. A phone screen is not an advance " +
                    "directive and clinicians will treat it as information, not instruction.",
                com.prism.launcher.lock.MedicalRecord.get(this).dnr,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(dnr = value))
                    refresh()
                }
            ),
            SettingItem.Toggle(
                "Organ donor",
                "Shown alongside the rest of the card.",
                com.prism.launcher.lock.MedicalRecord.get(this).organDonor,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(organDonor = value))
                    refresh()
                }
            ),
            SettingItem.TextInput(
                "Notes for a first responder",
                com.prism.launcher.lock.MedicalRecord.get(this).notes.ifBlank { "Anything else that changes treatment" },
                com.prism.launcher.lock.MedicalRecord.get(this).notes,
                { value ->
                    val record = com.prism.launcher.lock.MedicalRecord.get(this)
                    com.prism.launcher.lock.MedicalRecord.save(this, record.copy(notes = value))
                    refresh()
                }
            ),
            SettingItem.Nav(
                "Emergency contacts",
                com.prism.launcher.lock.EmergencyContacts.all(this).let { list ->
                    when (list.size) {
                        0 -> "None — add them from Messaging > Contacts"
                        1 -> list.first().name
                        else -> "${list.size} people"
                    }
                },
                {
                    android.widget.Toast.makeText(
                        this,
                        "Open Messaging, switch to Contacts, and choose someone.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            ),

            // ── Stremio ──────────────────────────────────────────────────────
            //
            // Deliberately NOT in any group in GROUPS, so groupOf() drops it into "Other" -- which
            // is where it was asked for. Two rows rather than one screen with tabs: adding a
            // repository and installing an add-on are separate decisions, and a user who has not
            // added a repository has nothing to install.
            SettingItem.Header("Stremio"),
            SettingItem.Nav(
                "Stremio repositories",
                StremioStore.repositories(this).size.let { count ->
                    when (count) {
                        0 -> "None yet — a repository is a list of add-ons"
                        1 -> "1 repository"
                        else -> "$count repositories"
                    }
                },
                {
                    startActivity(
                        Intent(this, com.prism.launcher.stremio.StremioRepositoriesActivity::class.java)
                    )
                }
            ),
            SettingItem.Nav(
                "Stremio add-ons",
                StremioStore.installed(this).size.let { count ->
                    when (count) {
                        0 -> "None installed — their catalogs appear in Lyke's search"
                        1 -> "1 add-on installed"
                        else -> "$count add-ons installed"
                    }
                },
                {
                    startActivity(
                        Intent(this, com.prism.launcher.stremio.StremioAddonsActivity::class.java)
                    )
                }
            )
        )
    }

    private fun buildSlotPickers(): Array<SettingItem> {
        val prefs = SlotPreferences()
        val assignments = prefs.getAssignments()
        val options = listOf("Browser", "Desktop Grid", "App Drawer", "Messaging", "Nebula Social", "Kinetic Halo", "File Explorer", "Models", "Agentic Tools")

        return assignments.mapIndexed { index, current ->
            val currentIdx = when(current) {
                SlotAssignment.Browser -> 0
                SlotAssignment.DesktopGrid -> 1
                SlotAssignment.AppDrawer -> 2
                SlotAssignment.Messaging -> 3
                SlotAssignment.NebulaSocial -> 4
                SlotAssignment.KineticHalo -> 5
                SlotAssignment.FileExplorer -> 6
                SlotAssignment.Models -> 7
                SlotAssignment.AgenticTools -> 8
                else -> 1 // Default to Desktop Grid
            }

            SettingItem.Picker(
                "Page ${index + 1} Content",
                "Built-in page assigned to this slot",
                options,
                currentIdx,
                { idx ->
                    val assignment = when(idx) {
                        0 -> SlotAssignment.Browser
                        1 -> SlotAssignment.DesktopGrid
                        2 -> SlotAssignment.AppDrawer
                        3 -> SlotAssignment.Messaging
                        4 -> SlotAssignment.NebulaSocial
                        5 -> SlotAssignment.KineticHalo
                        6 -> SlotAssignment.FileExplorer
                        7 -> SlotAssignment.Models
                        8 -> SlotAssignment.AgenticTools
                        else -> SlotAssignment.Default
                    }
                    prefs.setAt(index, assignment)
                }
            )
        }.toTypedArray()
    }

    /**
     * A colour as a hex code.
     *
     * TEXT RATHER THAN A LIST OF PRESETS, because a preset list can only ever offer the handful of
     * colours somebody thought of. Blank means "unset", which is how every one of these falls back
     * to the light/dark palette — see PrismSettings, where 0 carries that meaning.
     *
     * Accepts `#RRGGBB`, `RRGGBB`, `#AARRGGBB`, with or without the hash. Anything unparseable is
     * REJECTED RATHER THAN GUESSED AT: silently keeping the old colour after the user typed a new
     * one looks like the setting is broken, so a bad code clears back to "follow the theme" and
     * says so in the subtitle.
     */
    private fun hexColour(
        title: String,
        subtitle: String,
        current: Int,
        onChanged: (Int) -> Unit,
        enabled: Boolean = true,
    ): SettingItem = SettingItem.TextInput(
        title,
        if (enabled) subtitle else "Turned off while a background image is active",
        if (current == 0) "" else String.format("#%06X", current and 0xFFFFFF),
        { typed ->
            val cleaned = typed.trim().removePrefix("#")
            val parsed = when {
                cleaned.isEmpty() -> 0
                cleaned.length == 6 || cleaned.length == 8 ->
                    cleaned.toLongOrNull(16)?.let { value ->
                        // A six-digit code has no alpha; opaque is the only sensible reading.
                        if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
                    } ?: 0
                else -> 0
            }
            onChanged(parsed)
            refresh()
        },
        isEnabled = enabled,
        isSingleLine = true,
    )

    private fun onItemClick(item: SettingItem, position: Int) {
        when (item) {
            // Hosted views handle their own touches; listed so the compiler keeps
            // checking that every other case is covered.
            is SettingItem.Custom -> Unit
            is SettingItem.Toggle -> {
                item.value = !item.value
                item.onChanged(item.value)
                refresh()
            }
            is SettingItem.Picker -> {
                PrismDialogFactory.show(
                    this,
                    item.title,
                    "Choose an option:",
                    onPositive = {},
                    customView = android.widget.ListView(this).apply {
                        adapter = object : android.widget.ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_list_item_single_choice, item.options) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val v = super.getView(position, convertView, parent)
                                (v as? TextView)?.setTextColor(this@SettingsActivity.resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
                                return v
                            }
                        }
                        choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
                        setItemChecked(item.currentSelection, true)
                        setOnItemClickListener { _, _, which, _ ->
                            item.currentSelection = which
                            item.onChanged(which)
                            this@SettingsActivity.refresh()
                        }
                    }
                )
            }
            is SettingItem.TextInput -> {
                val input = EditText(this).apply {
                    val p = (16 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                    setTextColor(resolveAttr(R.attr.prismTextPrimary))
                    setHintTextColor(resolveAttr(R.attr.prismTextSecondary))
                    
                    if (item.isSingleLine) {
                        isSingleLine = true
                        maxLines = 1
                    }
                    if (item.isEncoded) {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        inputType = InputType.TYPE_CLASS_TEXT
                    }
                    setText(item.value)
                }
                PrismDialogFactory.show(
                    this,
                    item.title,
                    item.subtitle,
                    onPositive = {
                        val newValue = input.text.toString().trim()
                        if (newValue.isNotEmpty()) {
                            item.value = newValue
                            item.onChanged(newValue)
                            this@SettingsActivity.refresh()
                        }
                    },
                    customView = FrameLayout(this).apply {
                        val pad = (24 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        addView(input)
                    }
                )
            }
            is SettingItem.Nav -> {
                item.onClick()
            }
            is SettingItem.Header -> {} 
        }
    }

    private fun showServerFleetManager() {
        val servers = PrismSettings.getPrismServers()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val list = android.widget.ListView(this).apply {
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount(): Int = servers.size
                override fun getItem(p0: Int) = servers[p0]
                override fun getItemId(p0: Int) = p0.toLong()
                override fun getView(idx: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val s = servers[idx]
                    val view = convertView ?: android.view.LayoutInflater.from(this@SettingsActivity).inflate(android.R.layout.simple_list_item_2, parent, false)
                    val t1 = view.findViewById<android.widget.TextView>(android.R.id.text1)
                    val t2 = view.findViewById<android.widget.TextView>(android.R.id.text2)
                    
                    t1.text = if (s.isActive) "● ${s.name} (ACTIVE)" else s.name
                    t1.setTextColor(if (s.isActive) PrismSettings.getGlowColor() else androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.prism_text_primary))
                    t2.text = "${s.address}:${s.port} | User: ${s.username}"
                    t2.setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.prism_text_muted))
                    
                    view.setOnClickListener {
                        servers.forEach { it.isActive = false }
                        s.isActive = true
                        PrismSettings.setPrismServers(servers)
                        this@SettingsActivity.refresh()
                        showServerFleetManager() // Refresh
                    }
                    
                    view.setOnLongClickListener {
                        PrismDialogFactory.show(this@SettingsActivity, "Delete Server?", "Remove ${s.name} from your fleet?", onPositive = {
                            val newList = servers.toMutableList()
                            newList.removeAt(idx)
                            PrismSettings.setPrismServers(newList)
                            this@SettingsActivity.refresh()
                            showServerFleetManager()
                        })
                        true
                    }
                    return view
                }
            }
        }
        
        container.addView(list, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 800))
        
        val addBtn = android.widget.Button(this).apply {
            text = "+ ADD PRISM SERVER"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(PrismSettings.getGlowColor())
            setOnClickListener { showAddServerDialog() }
        }
        container.addView(addBtn)

        PrismDialogFactory.show(this, "Prism Server Fleet", "Select active server or long-press to delete.", customView = container)
    }

    private fun showAddServerDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val nameInput = EditText(this).apply { hint = "Server Name (e.g. Home Lab)" }
        val ipInput = EditText(this).apply { hint = "Target IP/Hostname" }
        val portInput = EditText(this).apply { hint = "Port (Default 8888)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val userInput = EditText(this).apply { hint = "Username" }
        val passInput = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        
        layout.addView(nameInput)
        layout.addView(ipInput)
        layout.addView(portInput)
        layout.addView(userInput)
        layout.addView(passInput)

        PrismDialogFactory.show(this, "Add Prism Server", "Enter your server credentials below.", onPositive = {
            val name = nameInput.text.toString().trim()
            val ip = ipInput.text.toString().trim()
            if (name.isNotEmpty() && ip.isNotEmpty()) {
                val servers = PrismSettings.getPrismServers().toMutableList()
                servers.add(PrismSettings.PrismServer(
                    name = name,
                    address = ip,
                    port = portInput.text.toString().toIntOrNull() ?: 8888,
                    username = userInput.text.toString(),
                    password = passInput.text.toString()
                ))
                PrismSettings.setPrismServers(servers)
                refresh()
                showServerFleetManager()
            }
        }, customView = layout)
    }

    /**
     * Adds a single seed without making the user hand-edit the whole list.
     *
     * Prefixes a bare host with https:// rather than rejecting it: someone adding a seed types
     * "example.com", and refusing that on a technicality is the kind of thing that makes a
     * feature feel broken when it is merely pedantic.
     */
    /**
     * Shows every seed a crawl would start from, grouped by where it came from.
     *
     * Grouped rather than merged because the three sources behave differently: the user's are
     * permanent and hand-edited, the crawler's are automatic and subject to the retention cap, and
     * the built-ins are a floor that cannot be removed. A flat list would hide which of those an
     * entry belongs to, and therefore whether clearing discoveries would remove it.
     *
     * Scrollable by construction (AlertDialog scrolls its message), and capped at a readable
     * number per section with a count of the remainder -- an unlimited retention setting can make
     * this list tens of thousands of lines long.
     */
    private fun showSeedList() {
        val shown = 200
        fun section(title: String, items: List<String>): String {
            if (items.isEmpty()) return "$title\n  (none)\n\n"
            val head = items.take(shown).joinToString("\n") { "  $it" }
            val rest = items.size - shown
            return "$title (${items.size})\n" + head + (if (rest > 0) "\n  ...and $rest more" else "") + "\n\n"
        }

        val body = StringBuilder()
            .append(section("YOUR SEEDS", PrismSettings.getUserSearchSeeds()))
            .append(section("FOUND BY THE CRAWLER", PrismSettings.getDiscoveredSearchSeeds()))
            .append(section("BUILT IN", PrismSettings.DEFAULT_SEARCH_SEEDS))
            .toString()
            .trimEnd()

        val limit = PrismSettings.getMaxDiscoveredSeeds()
        val cap = if (limit < 0) "no limit" else "keeping up to $limit found sites"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Search seeds - $cap")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prism search seeds", body))
                android.widget.Toast.makeText(this, "Seed list copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun promptAddSearchSeed() {
        val input = android.widget.EditText(this).apply {
            hint = "https://example.com"
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add search seed")
            .setMessage("Crawls will start from this address as well.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                var value = input.text.toString().trim()
                if (value.isNotEmpty() && !value.startsWith("http")) value = "https://" + value
                val added = PrismSettings.addUserSearchSeed(value)
                android.widget.Toast.makeText(
                    this,
                    if (added) "Added $value" else "Not added - already listed, or not a URL",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomSearchUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(PrismSettings.getCustomSearchUrl())
        }
        PrismDialogFactory.show(
            this,
            "Custom Search Engine",
            "Enter search URL. Use %s for query placeholder.",
            onPositive = {
                PrismSettings.setCustomSearchUrl(input.text.toString().trim())
            },
            customView = input
        )
    }


    // ── Auto-caption ────────────────────────────────────────────────────────────────────────
    //
    // Ports prism-os/scripts/auto_caption.py. The script itself CANNOT run here: it needs CPython
    // plus torch, transformers, OpenCV and Pillow, and none of that has an Android build. So the
    // captions come from the vision model Prism already talks to, while the file operations --
    // slug rules, collision suffixes, refusal to overwrite -- are CaptionService, shared with the
    // desktop build so both platforms rename identically.

    /**
     * Turns a Storage Access Framework tree URI into a real path.
     *
     * Only the `primary:` volume is convertible; an SD card or a cloud document provider has no
     * filesystem path at all, and guessing one produces a File that silently refers to nothing.
     * Returning null there lets the caller say so instead.
     */
    private fun resolveTreePath(uri: android.net.Uri): java.io.File? {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        val parts = docId.split(":")
        if (parts.size < 2 || parts[0] != "primary") return null
        val relative = parts[1]
        val root = android.os.Environment.getExternalStorageDirectory()
        val file = if (relative.isEmpty()) root else java.io.File(root, relative)
        return file.takeIf { it.isDirectory }
    }

    /**
     * Captions the folder and shows the plan.
     *
     * THE PREVIEW IS NOT OPTIONAL, and that is inherited rather than invented: auto_caption.py
     * defaults to a dry run and only renames when given --apply, because renaming someone's own
     * content directory is hard to reverse. A one-tap "caption this folder" would have been less
     * code and would have thrown that away.
     */
    private fun startCaptionPreview(directory: java.io.File) {
        val captioner = com.prism.launcher.nora.VisionModelCaptioner()
        if (!captioner.isAvailable()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("No vision model configured")
                .setMessage(
                    "Auto-captioning needs a cloud model that accepts images. Set one up under " +
                        "Cloud Models, then try again.\n\n" +
                        "The desktop build can instead run prism-os/scripts/auto_caption.py " +
                        "directly with BLIP; that script needs Python and PyTorch, which do not " +
                        "exist on Android."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val total = com.prism.launcher.nora.CaptionService.countCaptionable(directory)
        if (total == 0) {
            Toast.makeText(this, "No images in ${directory.name}", Toast.LENGTH_LONG).show()
            return
        }

        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Captioning")
            setMessage("Preparing...")
            setCancelable(false)
            isIndeterminate = false
            max = total
            show()
        }

        lifecycleScope.launch {
            val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.prism.launcher.nora.CaptionService.plan(directory, captioner) { done, all ->
                    runOnUiThread {
                        progress.progress = done
                        progress.setMessage("$done of $all")
                    }
                }
            }
            progress.dismiss()
            showCaptionPlan(plan)
        }
    }

    /** The dry-run preview. Renaming happens only from the positive button here. */
    private fun showCaptionPlan(plan: com.prism.launcher.nora.CaptionService.Plan) {
        val body = StringBuilder()

        if (plan.renames.isEmpty()) {
            body.append("Nothing to rename.\n\n")
        } else {
            for (r in plan.renames.take(40)) {
                body.append(r.from.name).append("\n    -> ").append(r.to.name)
                if (r.unchanged) body.append("  (unchanged)")
                if (r.blocked) body.append("  (SKIPPED - name taken)")
                body.append("\n")
            }
            if (plan.renames.size > 40) {
                body.append("\n...and ").append(plan.renames.size - 40).append(" more.\n")
            }
        }

        if (plan.failures.isNotEmpty()) {
            body.append("\nCould not caption ").append(plan.failures.size).append(" file(s):\n")
            plan.failures.take(8).forEach { body.append("  ").append(it).append("\n") }
        }

        body.append("\nCaptions come from a general-purpose vision model, not one trained on ")
        body.append("your content -- expect generic or off-target descriptions on anything ")
        body.append("unusual. Review the list above before applying.")

        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("${plan.actionable} file(s) would be renamed")
            .setMessage(body.toString())
            .setNegativeButton("Cancel", null)

        if (plan.actionable > 0) {
            builder.setPositiveButton("Rename ${plan.actionable}") { _, _ ->
                lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.prism.launcher.nora.CaptionService.apply(plan)
                    }
                    val message = buildString {
                        append("Renamed ").append(result.renamed)
                        if (result.skipped > 0) append(", skipped ").append(result.skipped)
                        if (result.errors.isNotEmpty()) append(", ").append(result.errors.size).append(" failed")
                    }
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        builder.show()
    }
}

// ── Models & Adapter ────────────────────────────────────────────────────────

sealed class SettingItem(open val isEnabled: Boolean = true) {
    data class Header(val title: String) : SettingItem(true)
    data class Toggle(val title: String, val subtitle: String, var value: Boolean, val onChanged: (Boolean) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class Picker(val title: String, val subtitle: String, val options: List<String>, var currentSelection: Int, val onChanged: (Int) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class TextInput(val title: String, val subtitle: String, var value: String, val onChanged: (String) -> Unit, override val isEnabled: Boolean = true, val isSingleLine: Boolean = false, val isEncoded: Boolean = false) : SettingItem(isEnabled)
    data class Nav(val title: String, val subtitle: String, val onClick: () -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)

    /**
     * A row that hosts an arbitrary view.
     *
     * The list is otherwise made of titles and controls, which cannot express a grid of pictures.
     * The view is BUILT BY THE CALLER and supplied here rather than constructed by the adapter,
     * because it owns state -- which image is active, which one is mid-delete -- that a recycled
     * holder would lose.
     */
    data class Custom(val view: android.view.View) : SettingItem(true)
}

class SettingsAdapter(
    private var items: List<SettingItem>,
    private val onItemClick: (SettingItem, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun setItems(newItems: List<SettingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class CustomVH(val host: android.widget.FrameLayout) : RecyclerView.ViewHolder(host)

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingItem.Header -> 0
        is SettingItem.Toggle -> 1
        is SettingItem.Custom -> 2
        else -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            1 -> ToggleVH(ItemSettingToggleBinding.inflate(inflater, parent, false))
            2 -> CustomVH(android.widget.FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            })
            else -> NavVH(ItemSettingNavBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        
        holder.itemView.alpha = if (item.isEnabled) 1.0f else 0.4f
        holder.itemView.setOnClickListener { if (item.isEnabled) onItemClick(item, position) }

        applyCardBackground(holder, position)

        if (item is SettingItem.Custom && holder is CustomVH) {
            holder.host.removeAllViews()
            // Re-parented rather than copied: the view carries its own state (which image is
            // active, which tile is mid-delete) that a fresh instance would lose on every scroll.
            (item.view.parent as? android.view.ViewGroup)?.removeView(item.view)
            holder.host.addView(item.view)
            // Not clickable as a row: the hosted view handles its own touches, and a row click
            // would swallow taps meant for individual tiles.
            holder.itemView.setOnClickListener(null)
            return
        }

        when (item) {
            is SettingItem.Header -> (holder as HeaderVH).binding.headerTitle.text = item.title
            // Hosted views handle their own touches; listed so the compiler keeps
            // checking that every other case is covered.
            is SettingItem.Custom -> Unit
            is SettingItem.Toggle -> {
                val h = holder as ToggleVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemToggle.isChecked = item.value
                h.binding.itemToggle.isEnabled = item.isEnabled
            }
            is SettingItem.Picker -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = item.options.getOrNull(item.currentSelection) ?: ""
            }
            is SettingItem.TextInput -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = item.value
            }
            is SettingItem.Nav -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = ""
            }
        }
    }

    private fun applyCardBackground(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item is SettingItem.Header) {
            holder.itemView.background = null
            return
        }

        val context = holder.itemView.context
        val bg = android.graphics.drawable.GradientDrawable()
        
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(R.attr.prismCardColor, typedValue, true)
        val color = if (typedValue.type != android.util.TypedValue.TYPE_NULL) typedValue.data else android.graphics.Color.WHITE
        
        bg.setColor(color)

        val radius = 16f * context.resources.displayMetrics.density
        val isFirst = position == 0 || items[position - 1] is SettingItem.Header
        val isLast = position == items.size - 1 || items[position + 1] is SettingItem.Header

        when {
            isFirst && isLast -> bg.cornerRadius = radius
            isFirst -> bg.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> bg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> {}
        }

        holder.itemView.background = bg
        val params = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        params.setMargins(
            (16 * context.resources.displayMetrics.density).toInt(),
            if (isFirst) (8 * context.resources.displayMetrics.density).toInt() else 0,
            (16 * context.resources.displayMetrics.density).toInt(),
            if (isLast) (8 * context.resources.displayMetrics.density).toInt() else 0
        )
        holder.itemView.layoutParams = params
    }

    override fun getItemCount() = items.size

    class HeaderVH(val binding: ItemSettingHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ToggleVH(val binding: ItemSettingToggleBinding) : RecyclerView.ViewHolder(binding.root)
    class NavVH(val binding: ItemSettingNavBinding) : RecyclerView.ViewHolder(binding.root)


}
