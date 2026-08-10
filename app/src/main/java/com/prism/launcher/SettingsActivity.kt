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
                    "Mesh Bootstrap Server",
                    "Access Points (Hotspot Gateway)",
                    "Native VPN Server (WireGuard)",
                    "Decentralized Name System"
                )
            ),
            Group(
                "Intelligence & Messaging",
                "AI engine, models, image generation, Nora and response behaviour",
                listOf(
                    "Intelligence & Messaging",
                    "Available LLM Models",
                    "Visual Intelligence (Diffusion)",
                    "Nora (Brain-Based Generation)",
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

        // Root: one row per group, plus "Other" if anything fell outside the map.
        val present = all.groupBy { groupOf(it.header) }
        val ordered = GROUPS.map { it.title } + listOf("Other")
        return ordered.mapNotNull { title ->
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
    }

    private fun subtitleOf(item: SettingItem): String = when (item) {
        is SettingItem.Header -> ""
        is SettingItem.Toggle -> item.subtitle
        is SettingItem.Picker -> item.subtitle
        is SettingItem.TextInput -> item.subtitle
        is SettingItem.Nav -> item.subtitle
    }

    /** Same setting, same behaviour, with its description replaced by where it lives. */
    private fun withBreadcrumb(item: SettingItem, path: String): SettingItem = when (item) {
        is SettingItem.Toggle -> item.copy(subtitle = path)
        is SettingItem.Picker -> item.copy(subtitle = path)
        is SettingItem.TextInput -> item.copy(subtitle = path)
        is SettingItem.Nav -> item.copy(subtitle = path)
        is SettingItem.Header -> item
    }

    /** Rebuilds whatever the screen is currently showing. */
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

    private val fontPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            copyFontToInternal(uri)
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri)
            com.prism.launcher.messaging.ModelDownloadManager.copyUriToInternal(this, uri, fileName, isPickingImageModel) { success, error ->
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
        val currentMode = PrismSettings.getThemeMode()
        binding.themeToggle.setImageResource(
            if (currentMode == PrismSettings.THEME_LIGHT) R.drawable.ic_theme_moon 
            else R.drawable.ic_theme_sun
        )
        binding.themeToggle.setOnClickListener {
            val nextMode = if (currentMode == PrismSettings.THEME_LIGHT) PrismSettings.THEME_DARK else PrismSettings.THEME_LIGHT
            PrismSettings.setThemeMode(nextMode)
            
            // Restart with fade
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            startActivity(intent)
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
                listOf("DuckDuckGo", "Google", "Bing", "Custom"),
                when (PrismSettings.getSearchEngine()) {
                    "google" -> 1
                    "bing" -> 2
                    "custom" -> 3
                    else -> 0
                },
                { idx ->
                    val engine = when (idx) {
                        1 -> "google"
                        2 -> "bing"
                        3 -> "custom"
                        else -> "ddg"
                    }
                    PrismSettings.setSearchEngine(engine)
                    if (engine == "custom") promptCustomSearchUrl()
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
                    refresh()
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled()
            ),
            SettingItem.Toggle(
                "Persistent VPN Server",
                "Keep Prism Server running even outside of private browsing (Backbone mode)",
                PrismSettings.getVpnServerAlwaysOn(),
                { 
                    PrismSettings.setVpnServerAlwaysOn(it) 
                    refresh()
                    // Start or let service re-evaluate
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
            SettingItem.Toggle(
                "Locked private tabs",
                "Require biometric unlock to access private tabs",
                PrismSettings.getPrivateTabsLocked(),
                { PrismSettings.setPrivateTabsLocked(it) }
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
                "Falcon-1B RefinedWeb",
                "Fast & efficient (1B params, ~600MB)",
                { downloadModel("Falcon-1B", PrismSettings.MODEL_FALCON_1B) },
                isEnabled = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "Qwen2.5-1.5B (Expert)",
                "User-preferred high performance task bundle",
                { downloadModel("Qwen-1.5B", PrismSettings.MODEL_QWEN_1_5) },
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

            // ── OS Virtualization ────────────────────────────────────────────
            SettingItem.Header("OS Virtualization"),
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

    private fun onItemClick(item: SettingItem, position: Int) {
        when (item) {
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
}

class SettingsAdapter(
    private var items: List<SettingItem>,
    private val onItemClick: (SettingItem, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun setItems(newItems: List<SettingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingItem.Header -> 0
        is SettingItem.Toggle -> 1
        else -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            1 -> ToggleVH(ItemSettingToggleBinding.inflate(inflater, parent, false))
            else -> NavVH(ItemSettingNavBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        
        holder.itemView.alpha = if (item.isEnabled) 1.0f else 0.4f
        holder.itemView.setOnClickListener { if (item.isEnabled) onItemClick(item, position) }

        applyCardBackground(holder, position)

        when (item) {
            is SettingItem.Header -> (holder as HeaderVH).binding.headerTitle.text = item.title
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
