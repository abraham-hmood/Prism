package com.prism.launcher

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import java.io.File

/**
 * Typed, centralized access to all user-configurable Prism settings.
 * Reads/writes to SharedPreferences("prism_settings") immediately on every call.
 * No "Save" button is needed anywhere in the UI.
 */
object PrismSettings {

    /**
     * Default glow accent, ARGB.
     *
     * Was `Color.parseColor("#FF7C9EFF")`. Written as a literal because parsing a constant string
     * at every read only ever existed to borrow an Android helper, and the value is the same
     * number on every platform.
     */
    const val DEFAULT_GLOW_COLOR: Int = 0xFF7C9EFF.toInt()


    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val PREFS = "prism_settings"

    // ── Launcher ────────────────────────────────────────────────────────────

    /** Which pager page to show on launch: 0=Left, 1=Center, 2=Right */
    fun getDefaultPage(): Int =
        prefs().getInt(KEY_DEFAULT_PAGE, 1)

    fun setDefaultPage(value: Int) =
        prefs().edit().putInt(KEY_DEFAULT_PAGE, value).apply()

    /** Which Icon Pack package is selected ("" for Default) */
    fun getIconPackPackage(): String =
        prefs().getString(KEY_ICON_PACK_PACKAGE, "") ?: ""

    fun setIconPackPackage(value: String) =
        prefs().edit().putString(KEY_ICON_PACK_PACKAGE, value).apply()

    /** Whether app names are shown below icons in the drawer */
    fun getShowDrawerLabels(): Boolean =
        prefs().getBoolean(KEY_SHOW_DRAWER_LABELS, true)

    fun setShowDrawerLabels(value: Boolean) =
        prefs().edit().putBoolean(KEY_SHOW_DRAWER_LABELS, value).apply()

    // ── Browser ─────────────────────────────────────────────────────────────

    /**
     * Search engine identifier: "ddg" | "google" | "bing" | "custom".
     * When "custom", [getCustomSearchUrl] is used.
     */
    fun getSearchEngine(): String =
        prefs().getString(KEY_SEARCH_ENGINE, "ddg") ?: "ddg"

    fun setSearchEngine(value: String) =
        prefs().edit().putString(KEY_SEARCH_ENGINE, value).apply()

    /** Custom search URL template. Use %s as query placeholder, e.g. "https://example.com/search?q=%s" */
    fun getCustomSearchUrl(): String =
        prefs().getString(KEY_CUSTOM_SEARCH_URL, "") ?: ""

    fun setCustomSearchUrl(value: String) =
        prefs().edit().putString(KEY_CUSTOM_SEARCH_URL, value).apply()

    /** Returns the full search URL for a given query, based on current engine setting */
    fun buildSearchUrl(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return when (getSearchEngine()) {
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing"   -> "https://www.bing.com/search?q=$encoded"
            "custom" -> getCustomSearchUrl().replace("%s", encoded)
            else     -> "https://duckduckgo.com/?q=$encoded"  // "ddg"
        }
    }

    /** Whether JavaScript is enabled in WebViews */
    fun getJsEnabled(): Boolean =
        prefs().getBoolean(KEY_JS_ENABLED, true)

    fun setJsEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_JS_ENABLED, value).apply()

    /** Whether new tabs open in private mode by default */
    fun getPrivateByDefault(): Boolean =
        prefs().getBoolean(KEY_PRIVATE_BY_DEFAULT, false)

    fun setPrivateByDefault(value: Boolean) =
        prefs().edit().putBoolean(KEY_PRIVATE_BY_DEFAULT, value).apply()

    // ── VPN / Privacy ───────────────────────────────────────────────────────

    /** Whether the VPN starts automatically when a private tab is opened */
    fun getVpnAutoStart(): Boolean =
        prefs().getBoolean(KEY_VPN_AUTO_START, true)

    fun setVpnAutoStart(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_AUTO_START, value).apply()

    /** Whether private browsing tabs require biometric unlock */
    fun getPrivateTabsLocked(): Boolean =
        prefs().getBoolean(KEY_PRIVATE_TABS_LOCKED, false)

    fun setPrivateTabsLocked(value: Boolean) =
        prefs().edit().putBoolean(KEY_PRIVATE_TABS_LOCKED, value).apply()

    /**
     * The passphrase that unlocks private tabs where biometrics do not exist.
     *
     * Android uses `BiometricPrompt`, which has no portable desktop equivalent -- Windows Hello
     * needs WinRT and Linux has no common API at all. So desktop falls back to a passphrase,
     * which is a real lock rather than a fingerprint dialog that always says yes.
     *
     * Blank means no passphrase has been set, and the lock then admits anyone who asks. That is
     * deliberate: a user who turned the lock on but never set a passphrase should not be locked
     * out of their own tabs, and the settings page tells them to set one.
     */
    fun getPrivateTabsPassphrase(): String =
        prefs().getString(KEY_PRIVATE_TABS_PASSPHRASE, "") ?: ""

    fun setPrivateTabsPassphrase(value: String) =
        prefs().edit().putString(KEY_PRIVATE_TABS_PASSPHRASE, value).apply()

    /**
     * Whether third-party plugin JARs may be loaded.
     *
     * Off by default. A plugin runs unsandboxed with Prism's privileges; the Android equivalent
     * at least required installing a package the user consented to, and a JAR appearing in a
     * directory carries no such moment of consent.
     */
    fun getPluginPagesEnabled(): Boolean =
        prefs().getBoolean(KEY_PLUGIN_PAGES_ENABLED, false)

    fun setPluginPagesEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_PLUGIN_PAGES_ENABLED, value).apply()

    // ── Nora: mixture of experts (experimental) ─────────────────────────────

    /**
     * Whether IT uses expert weight banks.
     *
     * OFF BY DEFAULT AND LABELLED EXPERIMENTAL, for a reason worth stating here as well as in
     * NoraExperts: this does NOT make Nora faster. The bench puts 81% of a learning step in the
     * V1-to-retina link; IT-to-V4 is the cheapest link in the hierarchy. What MoE buys is IT
     * capacity at constant compute, and it may improve prompt differentiation -- possibly by
     * genuinely specializing, possibly by memorizing one mode per expert. Watch the routing
     * entropy to tell those apart.
     */
    fun getNoraMoeEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_MOE_ENABLED, false)

    fun setNoraMoeEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_MOE_ENABLED, value).apply()

    /**
     * How many expert banks IT gets.
     *
     * Each bank is a full copy of the IT-to-V4 weights. That link is small -- conv weights are
     * about 2% of Nora's footprint -- so eight experts costs a fraction of what the semantic hub
     * already uses. The ceiling is 16 because beyond that the conscience cannot keep the
     * distribution balanced on the dataset sizes Nora trains on.
     */
    fun getNoraMoeExperts(): Int =
        prefs().getInt(KEY_NORA_MOE_EXPERTS, 4).coerceIn(2, 16)

    fun setNoraMoeExperts(value: Int) =
        prefs().edit().putInt(KEY_NORA_MOE_EXPERTS, value.coerceIn(2, 16)).apply()

    /** Experts active per input. 1 is hard routing; 2 blends the two best. */
    fun getNoraMoeTopK(): Int =
        prefs().getInt(KEY_NORA_MOE_TOPK, 1).coerceIn(1, 4)

    fun setNoraMoeTopK(value: Int) =
        prefs().edit().putInt(KEY_NORA_MOE_TOPK, value.coerceIn(1, 4)).apply()

    /**
     * Strength of the load-balancing conscience.
     *
     * 0 turns balancing off, and expert collapse then becomes the likely outcome -- one expert
     * wins everything and the rest never train. Exposed anyway, because seeing the collapse is
     * the clearest way to understand why the mechanism is there.
     */
    fun getNoraMoeConscience(): Float =
        prefs().getFloat(KEY_NORA_MOE_CONSCIENCE, 10f).coerceIn(0f, 50f)

    fun setNoraMoeConscience(value: Float) =
        prefs().edit().putFloat(KEY_NORA_MOE_CONSCIENCE, value.coerceIn(0f, 50f)).apply()

    /** Primary DNS resolver address used by the VPN tunnel */
    fun getPrimaryDns(): String =
        prefs().getString(KEY_PRIMARY_DNS, DEFAULT_DNS_A) ?: DEFAULT_DNS_A

    fun setPrimaryDns(value: String) =
        prefs().edit().putString(KEY_PRIMARY_DNS, value.trim()).apply()

    /** Secondary DNS resolver address used by the VPN tunnel */
    fun getSecondaryDns(): String =
        prefs().getString(KEY_SECONDARY_DNS, DEFAULT_DNS_B) ?: DEFAULT_DNS_B

    fun setSecondaryDns(value: String) =
        prefs().edit().putString(KEY_SECONDARY_DNS, value.trim()).apply()

    // ── DNS Proxy (for Access Points) ───────────────────────────────────────

    /** Whether the DNS Proxy Service is enabled (listens on 0.0.0.0:53) */
    fun getDnsProxyEnabled(): Boolean =
        prefs().getBoolean(KEY_DNS_PROXY_ENABLED, false)

    fun setDnsProxyEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_DNS_PROXY_ENABLED, value).apply()

    /** DNS Proxy mode: "p2p_only" | "fallback" */
    fun getDnsProxyMode(): String =
        prefs().getString(KEY_DNS_PROXY_MODE, "p2p_only") ?: "p2p_only"

    fun setDnsProxyMode(value: String) =
        prefs().edit().putString(KEY_DNS_PROXY_MODE, value).apply()

    /** Primary accent color used for glowing borders and staccato highlights */
    fun getGlowColor(): Int =
        prefs().getInt(KEY_GLOW_COLOR, DEFAULT_GLOW_COLOR)

    fun setGlowColor(value: Int) =
        prefs().edit().putInt(KEY_GLOW_COLOR, value).apply()

    fun getThemeMode(): Int =
        prefs().getInt(KEY_THEME_MODE, THEME_AUTO)

    fun setThemeMode(value: Int) =
        prefs().edit().putInt(KEY_THEME_MODE, value).apply()

    // ── Desktop shell ───────────────────────────────────────────────────────

    /**
     * Makes the desktop build present itself as the phone build does: one page at a time in a
     * phone-proportioned frame, with the navigation rail replaced by keyboard paging.
     *
     * WHY THIS IS A SETTING AND NOT A DECISION. The desktop shell defaults to a persistent rail
     * because a mouse has no horizontal fling, and that is the right default for a mouse. But it
     * makes the two builds feel like different applications, and for anyone who uses Prism on a
     * phone first, muscle memory is worth more than pointer ergonomics. Neither answer is right
     * for everyone, which is what a setting is for.
     *
     * Lives in :core rather than a desktop-only preferences file so the value rides the same
     * store as everything else -- and so it can be synced across a user's machines later without
     * a second migration.
     */
    fun getDesktopMobileMode(): Boolean =
        prefs().getBoolean(KEY_DESKTOP_MOBILE_MODE, false)

    fun setDesktopMobileMode(value: Boolean) =
        prefs().edit().putBoolean(KEY_DESKTOP_MOBILE_MODE, value).apply()

    /**
     * An explicit wallpaper, overriding whatever the OS is using.
     *
     * Blank means "follow the desktop", which is the default and the behaviour that matches
     * Android, where the launcher shows the system wallpaper because it is literally behind it.
     */
    fun getWallpaperPath(): String =
        prefs().getString(KEY_WALLPAPER_PATH, "") ?: ""

    fun setWallpaperPath(value: String) =
        prefs().edit().putString(KEY_WALLPAPER_PATH, value).apply()

    /** How far to darken the wallpaper so icon labels stay legible over a bright image. 0..100. */
    fun getWallpaperDim(): Int =
        prefs().getInt(KEY_WALLPAPER_DIM, 35)

    fun setWallpaperDim(value: Int) =
        prefs().edit().putInt(KEY_WALLPAPER_DIM, value.coerceIn(0, 100)).apply()

    /**
     * Apps pinned to the taskbar, as `AppEntry.id` strings in display order.
     *
     * Ordered, so a List rather than the Set that [getAppWhitelist] uses -- a taskbar whose icons
     * reshuffle between launches is a taskbar nobody can build muscle memory against.
     */
    fun getTaskbarPins(): List<String> =
        prefs().getString(KEY_TASKBAR_PINS, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun setTaskbarPins(value: List<String>) =
        prefs().edit().putString(KEY_TASKBAR_PINS, value.joinToString("\n")).apply()

    // ── VPN Tunneling ───────────────────────────────────────────────────────

    const val VPN_MODE_PRISM = "prism"
    const val VPN_MODE_EXTERNAL = "external"
    const val PRISM_ROLE_SERVER = "server"
    const val PRISM_ROLE_CLIENT = "client"

    /** Whether VPN Tunneling is enabled */
    fun getVpnTunnelingEnabled(): Boolean =
        prefs().getBoolean(KEY_VPN_TUNNELING_ENABLED, false)

    fun setVpnTunnelingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_TUNNELING_ENABLED, value).apply()

    /** Which VPN Mode is selected: "prism" | "external" */
    fun getVpnMode(): String =
        prefs().getString(KEY_VPN_MODE, VPN_MODE_PRISM) ?: VPN_MODE_PRISM

    fun setVpnMode(value: String) =
        prefs().edit().putString(KEY_VPN_MODE, value).apply()

    /** Whether the Prism Server should stay active in the background without browsing */
    fun getVpnServerAlwaysOn(): Boolean =
        prefs().getBoolean(KEY_VPN_SERVER_ALWAYS_ON, false)

    fun setVpnServerAlwaysOn(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_SERVER_ALWAYS_ON, value).apply()

    /** Prism VPN Role: "server" | "client" */
    fun getPrismVpnRole(): String =
        prefs().getString(KEY_PRISM_VPN_ROLE, PRISM_ROLE_CLIENT) ?: PRISM_ROLE_CLIENT

    fun setPrismVpnRole(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_ROLE, value).apply()

    fun getPrismVpnPort(): String {
        val prefs = prefs()
        val storedPort = prefs.getString(KEY_PRISM_VPN_PORT, "")
        // Enforce exclusion of 8080 and other reserved ports for the Proxy
        if (storedPort.isNullOrBlank() || storedPort == "8080" || storedPort == "8081") {
            val port = MeshUtils.findAvailablePort().toString()
            setPrismVpnPort(port)
            return port
        }
        return storedPort
    }

    fun setPrismVpnPort(value: String) {
        val isReserved = value == "8080" || value == "8081"
        val finalValue = if (value.isBlank() || isReserved) MeshUtils.findAvailablePort().toString() else value
        prefs().edit().putString(KEY_PRISM_VPN_PORT, finalValue).apply()
    }

    const val VPN_PROTOCOL_AUTO = "auto"
    const val VPN_PROTOCOL_IKEV2 = "ikev2"
    const val VPN_PROTOCOL_L2TP = "l2tp"
    const val VPN_PROTOCOL_PROXY = "proxy"

    fun getVpnProtocolMode(): String =
        prefs().getString(KEY_VPN_PROTOCOL_MODE, VPN_PROTOCOL_AUTO) ?: VPN_PROTOCOL_AUTO

    fun setVpnProtocolMode(value: String) =
        prefs().edit().putString(KEY_VPN_PROTOCOL_MODE, value).apply()

    fun getPrismVpnTargetIp(): String =
        prefs().getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""

    fun setPrismVpnTargetIp(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_TARGET_IP, value.trim()).apply()

    /** Primary Bootstrap server for the Mesh Network */
    fun getMeshBootstrapAddress(): String {
        val addr = prefs().getString(KEY_MESH_BOOTSTRAP_ADDRESS, "") ?: ""
        if (addr.isEmpty()) {
            val legacy = getPrismVpnTargetIp()
            if (legacy.isNotEmpty()) {
                setMeshBootstrapAddress(legacy)
                return legacy
            }
        }
        return addr
    }

    fun setMeshBootstrapAddress(value: String) =
        prefs().edit().putString(KEY_MESH_BOOTSTRAP_ADDRESS, value.trim()).apply()

    fun getMeshBootstrapPort(): String =
        prefs().getString(KEY_MESH_BOOTSTRAP_PORT, "8081") ?: "8081"

    fun setMeshBootstrapPort(value: String) =
        prefs().edit().putString(KEY_MESH_BOOTSTRAP_PORT, value.trim()).apply()

    fun getExternalVpnProfile(): String =
        prefs().getString(KEY_EXTERNAL_VPN_PROFILE, "") ?: ""
        
    fun setExternalVpnProfile(value: String) =
        prefs().edit().putString(KEY_EXTERNAL_VPN_PROFILE, value).apply()

    fun getPrismVpnUsername(): String {
        val u = prefs().getString(KEY_PRISM_VPN_USERNAME, "") ?: ""
        if (u.isEmpty()) {
            val gen = "prism_user_" + (1000..9999).random()
            setPrismVpnUsername(gen)
            return gen
        }
        return u
    }

    fun setPrismVpnUsername(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_USERNAME, value).apply()

    fun getPrismVpnPassword(): String {
        val p = prefs().getString(KEY_PRISM_VPN_PASSWORD, "") ?: ""
        if (p.isEmpty()) {
            val gen = generatePass()
            setPrismVpnPassword(gen)
            return gen
        }
        return p
    }

    fun setPrismVpnPassword(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_PASSWORD, value).apply()

    fun getAppWhitelist(): Set<String> =
        prefs().getStringSet(KEY_APP_WHITELIST, emptySet()) ?: emptySet()

    fun setAppWhitelist(packages: Set<String>) =
        prefs().edit().putStringSet(KEY_APP_WHITELIST, packages).apply()

    private fun generatePass(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..30).map { chars.random() }.joinToString("")
    }

    // ── Prism Server Fleet ──────────────────────────────────────────────────

    data class PrismServer(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val address: String,
        val port: Int,
        val username: String,
        val password: String,
        var isActive: Boolean = false
    )

    fun getPrismServers(): List<PrismServer> {
        val raw = prefs().getString(KEY_PRISM_SERVER_LIST, "") ?: ""
        if (raw.isEmpty()) {
            // Migration: Create first server from legacy settings
            val legacyIp = prefs().getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""
            if (legacyIp.isEmpty()) return emptyList()
            
            val legacyServer = PrismServer(
                name = "Default Server",
                address = legacyIp,
                port = getPrismVpnPort().toIntOrNull() ?: 8888,
                username = getPrismVpnUsername(),
                password = getPrismVpnPassword(),
                isActive = true
            )
            val list = listOf(legacyServer)
            setPrismServers(list)
            return list
        }
        
        // De-serialize simple CSV for now to avoid bulky JSON libraries
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else PrismServer(p[0], p[1], p[2], p[3].toInt(), p[4], p[5], p[6] == "1")
        }
    }

    fun setPrismServers(servers: List<PrismServer>) {
        val encoded = servers.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.address}::${it.port}::${it.username}::${it.password}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_PRISM_SERVER_LIST, encoded).apply()
    }

    fun getP2pSelfId(): String {
        val id = prefs().getString(KEY_P2P_SELF_ID, "") ?: ""
        if (id.isEmpty()) {
            // Default to sanitized device model (e.g. "SM-S901U")
            val model = PrismPlatform.host.deviceName()
            prefs().edit().putString(KEY_P2P_SELF_ID, model).apply()
            return model
        }
        return id
    }

    fun getActiveServer(): PrismServer? {
        return getPrismServers().find { it.isActive }
    }

    /** Returns all known static mesh node addresses (Bootstrap + Fleet Servers) */
    fun getAllMeshNodes(): List<String> {
        val nodes = mutableSetOf<String>()
        val bootstrap = getMeshBootstrapAddress()
        if (bootstrap.isNotEmpty()) nodes.add(bootstrap)
        
        getPrismServers().forEach { 
            if (it.address.isNotEmpty()) nodes.add(it.address)
        }
        return nodes.toList()
    }

    // ── P2P Web Hosting ─────────────────────────────────────────────────────

    data class P2pHostedSite(
        val id: String = java.util.UUID.randomUUID().toString(),
        val domain: String,
        val localPath: String,
        var isActive: Boolean = true
    )

    fun getP2pHostedSites(): List<P2pHostedSite> {
        val raw = prefs().getString(KEY_P2P_HOSTED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else P2pHostedSite(p[0], p[1], p[2], p[3] == "1")
        }
    }

    fun setP2pHostedSites(sites: List<P2pHostedSite>) {
        val encoded = sites.joinToString(";;;") {
            "${it.id}::${it.domain}::${it.localPath}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_P2P_HOSTED_SITES, encoded).apply()
    }

    // ── P2P AI Model Hosting ────────────────────────────────────────────────

    /** The "Host My Active Model" checkbox — only meaningful when tunneling is on and role is server. */
    fun getP2pModelHostingEnabled(): Boolean =
        prefs().getBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, false)

    fun setP2pModelHostingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, value).apply()

    /** The peer + model a client picked from the discovered P2pModelRegistry entries. */
    data class SelectedP2pModel(val peerIp: String, val modelName: String)

    fun getSelectedP2pModel(): SelectedP2pModel? {
        val raw = prefs().getString(KEY_SELECTED_P2P_MODEL, null) ?: return null
        val p = raw.split("::", limit = 2)
        if (p.size < 2) return null
        return SelectedP2pModel(p[0], p[1])
    }

    fun setSelectedP2pModel(peerIp: String, modelName: String) {
        prefs().edit().putString(KEY_SELECTED_P2P_MODEL, "$peerIp::$modelName").apply()
    }

    fun clearSelectedP2pModel() {
        prefs().edit().remove(KEY_SELECTED_P2P_MODEL).apply()
    }

    // ── Mesh Mirroring (P2P CDN) ──────────────────────────────────────────

    data class P2pMirroredSite(
        val domain: String,
        val localPath: String,
        val originalHost: String,
        val lastSync: Long,
        var isActive: Boolean = true
    )

    fun getP2pMirroredSites(): List<P2pMirroredSite> {
        val raw = prefs().getString(KEY_P2P_MIRRORED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 5) null else P2pMirroredSite(p[0], p[1], p[2], p[3].toLong(), p[4] == "1")
        }
    }

    fun setP2pMirroredSites(sites: List<P2pMirroredSite>) {
        val encoded = sites.joinToString(";;;") { 
            "${it.domain}::${it.localPath}::${it.originalHost}::${it.lastSync}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_P2P_MIRRORED_SITES, encoded).apply()
    }

    fun getMirrorsDir(): java.io.File {
        val mirrors = java.io.File(PrismPlatform.host.documentsDir(), "Mirrors")
        if (!mirrors.exists()) mirrors.mkdirs()
        return mirrors
    }

    // ── Networked Storage ───────────────────────────────────────────────────

    data class NetworkStorage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val protocol: String, // ftp, p2p, webdav, etc.
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = ""
    )

    fun getNetworkStorages(): List<NetworkStorage> {
        val raw = prefs().getString(KEY_NETWORK_STORAGES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else NetworkStorage(p[0], p[1], p[2], p[3], p[4].toInt(), p[5], p[6])
        }
    }

    fun setNetworkStorages(list: List<NetworkStorage>) {
        val encoded = list.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.protocol}::${it.host}::${it.port}::${it.username}::${it.password}"
        }
        prefs().edit().putString(KEY_NETWORK_STORAGES, encoded).apply()
    }

    fun addNetworkStorage(item: NetworkStorage) {
        val current = getNetworkStorages().toMutableList()
        current.add(item)
        setNetworkStorages(current)
    }

    // ── Access Points ───────────────────────────────────────────────────────
    //
    // The accessors live in :app (AccessPointStore.kt) because AccessPointConfig is a Room
    // entity. The KEY stays here so there is one source of truth for the string, and so this
    // file still documents every value stored under "prism_settings".

    // ── AI & Intelligence ───────────────────────────────────────────────────

    const val AI_MODE_LOCAL = "local"
    const val AI_MODE_CLOUD = "cloud"
    const val AI_MODE_LOCAL_CLOUD = "local_cloud"

    /** The Ollama server + model a user picked after a LAN discovery scan (AI_MODE_LOCAL_CLOUD). */
    data class OllamaEndpoint(val host: String, val port: Int, val model: String)

    fun getSelectedOllamaEndpoint(): OllamaEndpoint? {
        val raw = prefs().getString(KEY_OLLAMA_ENDPOINT, null) ?: return null
        val p = raw.split("::")
        if (p.size < 3) return null
        val port = p[1].toIntOrNull() ?: return null
        return OllamaEndpoint(p[0], port, p[2])
    }

    fun setSelectedOllamaEndpoint(endpoint: OllamaEndpoint) {
        prefs().edit().putString(KEY_OLLAMA_ENDPOINT, "${endpoint.host}::${endpoint.port}::${endpoint.model}").apply()
    }

    private const val KEY_AUTO_MIRROR = "browser_auto_mirror"

    fun getAutoMirror(): Boolean =
        prefs().getBoolean(KEY_AUTO_MIRROR, false)

    fun setAutoMirror(value: Boolean) =
        prefs().edit().putBoolean(KEY_AUTO_MIRROR, value).apply()

    fun getAiMode(): String =
        prefs().getString(KEY_AI_MODE, AI_MODE_LOCAL) ?: AI_MODE_LOCAL

    fun setAiMode(value: String) =
        prefs().edit().putString(KEY_AI_MODE, value).apply()

    // ── Cloud Model Profiles ─────────────────────────────────────────────────
    // Replaces the old single api-key/base-url/model-id settings with a saved list the user
    // can switch between (see CloudModelsActivity). getCloudModels() lazily migrates whatever
    // was in the old single-profile fields into the first saved entry the first time it's
    // called after this update, so an existing Gemini/OpenAI key survives the upgrade.

    data class CloudModelProfile(
        val id: String,
        val apiKey: String,
        val baseUrl: String,
        val modelId: String
    )

    fun getCloudModels(): List<CloudModelProfile> {
        migrateLegacyCloudModelIfNeeded()
        val raw = prefs().getString(KEY_CLOUD_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else CloudModelProfile(p[0], p[1], p[2], p[3])
        }
    }

    fun setCloudModels(models: List<CloudModelProfile>) {
        val encoded = models.joinToString(";;;") {
            "${it.id}::${it.apiKey}::${it.baseUrl}::${it.modelId}"
        }
        prefs().edit().putString(KEY_CLOUD_MODELS, encoded).apply()
    }

    fun addCloudModel(model: CloudModelProfile) {
        val current = getCloudModels().filter { it.id != model.id }.toMutableList()
        current.add(model)
        setCloudModels(current)
    }

    fun removeCloudModel(id: String) {
        setCloudModels(getCloudModels().filter { it.id != id })
        if (getActiveCloudModelId() == id) setActiveCloudModelId(null)
    }

    fun getActiveCloudModelId(): String? =
        prefs().getString(KEY_ACTIVE_CLOUD_MODEL_ID, null)

    fun setActiveCloudModelId(id: String?) =
        prefs().edit().putString(KEY_ACTIVE_CLOUD_MODEL_ID, id).apply()

    fun getActiveCloudModel(): CloudModelProfile? {
        val id = getActiveCloudModelId() ?: return null
        return getCloudModels().find { it.id == id }
    }

    private fun migrateLegacyCloudModelIfNeeded() {
        if (prefs().getBoolean(KEY_CLOUD_MODELS_MIGRATED, false)) return
        prefs().edit().putBoolean(KEY_CLOUD_MODELS_MIGRATED, true).apply()

        val legacyKey = prefs().getString(KEY_CLOUD_AI_KEY, "") ?: ""
        if (legacyKey.isBlank()) return

        val legacyBaseUrl = prefs().getString(KEY_CLOUD_AI_BASE_URL, "https://api.openai.com/v1/")
            ?: "https://api.openai.com/v1/"
        val legacyModel = prefs().getString(KEY_CLOUD_AI_MODEL, "gpt-4o") ?: "gpt-4o"

        val profile = CloudModelProfile(
            id = java.util.UUID.randomUUID().toString(),
            apiKey = legacyKey,
            baseUrl = legacyBaseUrl,
            modelId = legacyModel
        )
        prefs().edit()
            .putString(KEY_CLOUD_MODELS, "${profile.id}::${profile.apiKey}::${profile.baseUrl}::${profile.modelId}")
            .putString(KEY_ACTIVE_CLOUD_MODEL_ID, profile.id)
            .apply()
    }

    fun getLocalAiModelPath(): String =
        prefs().getString(KEY_LOCAL_AI_MODEL_PATH, "") ?: ""

    fun setLocalAiModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_AI_MODEL_PATH, value).apply()

    fun getLocalImageModelPath(): String =
        prefs().getString(KEY_LOCAL_IMAGE_MODEL_PATH, "") ?: ""

    fun setLocalImageModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_IMAGE_MODEL_PATH, value).apply()

    fun getAiDownloadId(): Long =
        prefs().getLong(KEY_AI_DOWNLOAD_ID, -1L)

    fun setAiDownloadId(value: Long) =
        prefs().edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()

    /** Whether the in-flight download tracked by [getAiDownloadId] is an image (vs. text) model. */
    fun getAiDownloadIsImage(): Boolean =
        prefs().getBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, false)

    fun setAiDownloadIsImage(value: Boolean) =
        prefs().edit().putBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, value).apply()

    // ── OS Virtualization ────────────────────────────────────────────────────

    const val VIRT_MODE_PRISM_OS   = "prism_os"
    const val VIRT_MODE_CUSTOM_ISO = "custom_iso"

    fun getVirtualizationEnabled(): Boolean =
        prefs().getBoolean(KEY_VIRT_ENABLED, false)

    fun setVirtualizationEnabled(v: Boolean) =
        prefs().edit().putBoolean(KEY_VIRT_ENABLED, v).apply()

    fun getVirtualizationMode(): String =
        prefs().getString(KEY_VIRT_MODE, VIRT_MODE_PRISM_OS) ?: VIRT_MODE_PRISM_OS

    fun setVirtualizationMode(v: String) =
        prefs().edit().putString(KEY_VIRT_MODE, v).apply()

    fun getCustomIsoPath(): String =
        prefs().getString(KEY_VIRT_ISO_PATH, "") ?: ""

    fun setCustomIsoPath(v: String) =
        prefs().edit().putString(KEY_VIRT_ISO_PATH, v).apply()

    /** Whether AI responses stream in token-by-token instead of waiting for completion */
    fun getStreamingEnabled(): Boolean =
        prefs().getBoolean(KEY_STREAMING_ENABLED, true)

    fun setStreamingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_STREAMING_ENABLED, value).apply()

    /** Cap on generated tokens per response. -1 = unlimited (generate until the model stops) */
    fun getMaxTokens(): Int =
        prefs().getInt(KEY_MAX_TOKENS, -1)

    fun setMaxTokens(value: Int) =
        prefs().edit().putInt(KEY_MAX_TOKENS, value).apply()

    /** Returns true if a local image model exists in internal storage */
    fun isLocalImageModelImported(): Boolean {
        val path = getLocalImageModelPath()
        if (path.isEmpty()) return false
        val file = java.io.File(path)
        return file.exists() && file.absolutePath.startsWith(PrismPlatform.host.dataDir().absolutePath)
    }

    // ── Imported Model Registry ─────────────────────────────────────────────

    const val MODEL_TYPE_TEXT = "text"
    const val MODEL_TYPE_IMAGE = "image"

    data class ImportedModel(
        val path: String,
        val displayName: String,
        val type: String, // MODEL_TYPE_TEXT | MODEL_TYPE_IMAGE
        val importedAt: Long = System.currentTimeMillis()
    )

    fun getImportedModels(): List<ImportedModel> {
        val raw = prefs().getString(KEY_IMPORTED_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else try {
                ImportedModel(p[0], p[1], p[2], p[3].toLong())
            } catch (e: Exception) { null }
        }
    }

    fun setImportedModels(models: List<ImportedModel>) {
        val encoded = models.joinToString(";;;") {
            "${it.path}::${it.displayName}::${it.type}::${it.importedAt}"
        }
        prefs().edit().putString(KEY_IMPORTED_MODELS, encoded).apply()
    }

    fun addImportedModel(model: ImportedModel) {
        val current = getImportedModels().filter { it.path != model.path }.toMutableList()
        current.add(model)
        setImportedModels(current)
    }

    fun removeImportedModel(path: String) {
        setImportedModels(getImportedModels().filter { it.path != path })
    }

    // ── KV Cache Compression (GGUF / llama.cpp engine) ──────────────────────

    const val KV_CACHE_F16 = "f16"
    const val KV_CACHE_Q8_0 = "q8_0"
    const val KV_CACHE_Q4_0 = "q4_0"

    /** Default matches OGAM: Q8_0 KV cache whenever flash attention is active (the common case). */
    fun getKvCacheQuant(): String =
        prefs().getString(KEY_KV_CACHE_QUANT, KV_CACHE_Q8_0) ?: KV_CACHE_Q8_0

    fun setKvCacheQuant(value: String) =
        prefs().edit().putString(KEY_KV_CACHE_QUANT, value).apply()

    // ── AI Backend (forces CPU or GPU for both the GGUF/llama.cpp engine and the ────
    // ── MediaPipe/.task engine) ──────────────────────────────────────────────

    const val AI_BACKEND_CPU = 0
    const val AI_BACKEND_GPU = 1
    const val AI_BACKEND_NPU = 2

    /**
     * Defaults to GPU — safe even on devices without a GPU/OpenCL driver for GGUF models,
     * since model load automatically falls back to CPU on failure (see
     * GgufInferenceService/nativeLoadModel). For MediaPipe .task models, LocalAiService also
     * retries on CPU if GPU session init fails. Only actually accelerates Q4_0/Q8_0 GGUF quants;
     * K-quants (e.g. Q2_K) always run CPU. Force CPU here if generation is slow or unstable.
     */
    fun getAiBackend(): Int =
        prefs().getInt(KEY_AI_BACKEND, AI_BACKEND_GPU)

    fun setAiBackend(value: Int) =
        prefs().edit().putInt(KEY_AI_BACKEND, value).apply()

    // ── Agentic Tools ────────────────────────────────────────────────────────

    /** Master switch: whether AiManager attempts tool-calling at all. Off by default since it
     * changes prompt construction and adds a network/latency round-trip per tool call. */
    fun getAgenticToolsEnabled(): Boolean =
        prefs().getBoolean(KEY_AGENTIC_ENABLED, false)

    fun setAgenticToolsEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_AGENTIC_ENABLED, value).apply()

    /**
     * Which imported "syntax" (custom prompt-injection + tool-call extraction format) to use.
     * Null means "use the backend's native structured tool-calling" (Cloud/Ollama's own `tools`
     * request field) -- for local/GGUF models there is no native tool-calling API at all, so a
     * syntax is *required* for local tool use; if none is selected, AgenticEngine just skips
     * tool injection for local mode rather than failing.
     */
    fun getActiveAgenticSyntaxId(): String? =
        prefs().getString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, null)

    fun setActiveAgenticSyntaxId(id: String?) =
        prefs().edit().putString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, id).apply()

    // ── Nebula Social background generation ──────────────────────────────────

    /** How often (in hours) the background service generates new Nebula posts/personas. Default 2. */
    fun getNebulaGenerationIntervalHours(): Int =
        prefs().getInt(KEY_NEBULA_INTERVAL_HOURS, 2)

    fun setNebulaGenerationIntervalHours(hours: Int) =
        prefs().edit().putInt(KEY_NEBULA_INTERVAL_HOURS, hours).apply()

    // ────────────────────────────────────────────────────────────────────────

    // ── Nora ────────────────────────────────────────────────────────────────

    /**
     * Whether Nora's live connectome visualization runs.
     *
     * It renders on its own thread and reads telemetry through a lock-free snapshot, so it does
     * not block training -- but it is still real work on a device that is already saturated.
     * Turning it off is the right call on a hot or low-end phone.
     */
    fun getNoraVisualizerEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_VISUALIZER, true)

    fun setNoraVisualizerEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_VISUALIZER, value).apply()

    /**
     * Whether training corrupts its inputs and learns to reconstruct the clean original.
     *
     * On by default: it multiplies the supervision per training image several-fold at no
     * generation-time cost, which matters a great deal when the dataset is a few dozen photos.
     * Turn it off to compare against plain reconstruction.
     */
    fun getNoraDenoisingEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_DENOISING, true)

    fun setNoraDenoisingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_DENOISING, value).apply()

    /**
     * Whether Nora retrains herself on her own dataset periodically, unattended.
     *
     * Opt-in, and off by default. Training is not a background nicety -- it holds a wake lock,
     * saturates the CPU for as long as it runs, and writes to storage every epoch. Turning that
     * on without being asked would be a battery and thermal decision made on the user's behalf,
     * which is not a decision to take silently.
     */
    fun getNoraAutoTrainEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_AUTOTRAIN, false)

    fun setNoraAutoTrainEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_AUTOTRAIN, value).apply()

    /** Hours between unattended training runs. 1..24, default 10. */
    fun getNoraAutoTrainIntervalHours(): Int =
        prefs().getInt(KEY_NORA_AUTOTRAIN_HOURS, 10).coerceIn(1, 24)

    fun setNoraAutoTrainIntervalHours(hours: Int) =
        prefs().edit().putInt(KEY_NORA_AUTOTRAIN_HOURS, hours.coerceIn(1, 24)).apply()

    /**
     * Nora's brain size.
     *
     * Stored field by field rather than as a scale factor, because the fields are individually
     * editable and a scale could not represent "default everywhere except a wider IT". Always
     * normalized on the way out, so a value hand-edited into prefs cannot produce an
     * inconsistent hierarchy.
     */
    fun getNoraGeometry(): com.prism.launcher.nora.NoraGeometry {
        val p = prefs()
        val d = com.prism.launcher.nora.NoraGeometry.DEFAULT
        return com.prism.launcher.nora.NoraGeometry(
            rings = p.getInt(KEY_NORA_RINGS, d.rings),
            wedges = p.getInt(KEY_NORA_WEDGES, d.wedges),
            v1Orientations = p.getInt(KEY_NORA_V1_ORI, d.v1Orientations),
            v2Channels = p.getInt(KEY_NORA_V2_CH, d.v2Channels),
            v4Channels = p.getInt(KEY_NORA_V4_CH, d.v4Channels),
            itChannels = p.getInt(KEY_NORA_IT_CH, d.itChannels),
            mtDirections = p.getInt(KEY_NORA_MT_DIR, d.mtDirections),
            semanticUnits = p.getInt(KEY_NORA_SEM, d.semanticUnits),
            dgUnits = p.getInt(KEY_NORA_DG, d.dgUnits),
            ca3Units = p.getInt(KEY_NORA_CA3, d.ca3Units)
        ).normalized()
    }

    fun setNoraGeometry(g: com.prism.launcher.nora.NoraGeometry) {
        val n = g.normalized()
        prefs().edit()
            .putInt(KEY_NORA_RINGS, n.rings)
            .putInt(KEY_NORA_WEDGES, n.wedges)
            .putInt(KEY_NORA_V1_ORI, n.v1Orientations)
            .putInt(KEY_NORA_V2_CH, n.v2Channels)
            .putInt(KEY_NORA_V4_CH, n.v4Channels)
            .putInt(KEY_NORA_IT_CH, n.itChannels)
            .putInt(KEY_NORA_MT_DIR, n.mtDirections)
            .putInt(KEY_NORA_SEM, n.semanticUnits)
            .putInt(KEY_NORA_DG, n.dgUnits)
            .putInt(KEY_NORA_CA3, n.ca3Units)
            .apply()
    }

    private fun prefs() =
        PrismPlatform.host.prefs(PREFS)

    const val DEFAULT_DNS_A = "1.1.1.1"
    const val DEFAULT_DNS_B = "1.0.0.1"

    private const val KEY_ICON_PACK_PACKAGE  = "icon_pack_package"
    private const val KEY_DEFAULT_PAGE       = "default_page"
    private const val KEY_SHOW_DRAWER_LABELS = "show_drawer_labels"
    private const val KEY_SEARCH_ENGINE      = "search_engine"
    private const val KEY_CUSTOM_SEARCH_URL  = "custom_search_url"
    private const val KEY_JS_ENABLED         = "js_enabled"
    private const val KEY_PRIVATE_BY_DEFAULT = "private_by_default"
    private const val KEY_VPN_AUTO_START     = "vpn_auto_start"
    private const val KEY_PRIVATE_TABS_LOCKED = "private_tabs_locked"
    private const val KEY_PRIMARY_DNS        = "primary_dns"
    private const val KEY_SECONDARY_DNS      = "secondary_dns"
    private const val KEY_GLOW_COLOR         = "glow_color"
    private const val KEY_NORA_VISUALIZER    = "nora_visualizer"
    private const val KEY_NORA_DENOISING     = "nora_denoising"
    private const val KEY_NORA_AUTOTRAIN     = "nora_autotrain"
    private const val KEY_NORA_AUTOTRAIN_HOURS = "nora_autotrain_hours"
    private const val KEY_NORA_RINGS         = "nora_geom_rings"
    private const val KEY_NORA_WEDGES        = "nora_geom_wedges"
    private const val KEY_NORA_V1_ORI        = "nora_geom_v1_ori"
    private const val KEY_NORA_V2_CH         = "nora_geom_v2_ch"
    private const val KEY_NORA_V4_CH         = "nora_geom_v4_ch"
    private const val KEY_NORA_IT_CH         = "nora_geom_it_ch"
    private const val KEY_NORA_MT_DIR        = "nora_geom_mt_dir"
    private const val KEY_NORA_SEM           = "nora_geom_sem"
    private const val KEY_NORA_DG            = "nora_geom_dg"
    private const val KEY_NORA_CA3           = "nora_geom_ca3"

    private const val KEY_AI_MODE            = "ai_mode"
    private const val KEY_OLLAMA_ENDPOINT    = "ollama_endpoint"
    // These three are read-only-for-migration now -- CloudModelProfile replaced them as the
    // live storage, but a pre-update install's values still need to be read once to migrate.
    private const val KEY_CLOUD_AI_KEY       = "cloud_ai_key"
    private const val KEY_CLOUD_AI_BASE_URL  = "cloud_ai_base_url"
    private const val KEY_CLOUD_AI_MODEL     = "cloud_ai_model"
    private const val KEY_CLOUD_MODELS       = "cloud_models"
    private const val KEY_ACTIVE_CLOUD_MODEL_ID = "active_cloud_model_id"
    private const val KEY_CLOUD_MODELS_MIGRATED  = "cloud_models_migrated"
    private const val KEY_AI_DOWNLOAD_ID     = "ai_download_id"
    private const val KEY_AI_DOWNLOAD_IS_IMAGE = "ai_download_is_image"
    private const val KEY_AI_MODEL           = "ai_model"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"
    private const val KEY_LOCAL_IMAGE_MODEL_PATH = "local_image_model_path"
    private const val KEY_STREAMING_ENABLED    = "ai_streaming_enabled"
    private const val KEY_MAX_TOKENS           = "ai_max_tokens"
    private const val KEY_IMPORTED_MODELS      = "imported_models"
    private const val KEY_KV_CACHE_QUANT       = "kv_cache_quant"
    private const val KEY_AI_BACKEND           = "ai_backend_mode"
    private const val KEY_NEBULA_INTERVAL_HOURS = "nebula_generation_interval_hours"
    private const val KEY_AGENTIC_ENABLED       = "agentic_tools_enabled"
    private const val KEY_ACTIVE_AGENTIC_SYNTAX_ID = "active_agentic_syntax_id"
    
    private const val KEY_DNS_PROXY_ENABLED  = "dns_proxy_enabled"
    private const val KEY_DNS_PROXY_MODE     = "dns_proxy_mode"
    
    private const val KEY_VPN_TUNNELING_ENABLED = "vpn_tunneling_enabled"
    private const val KEY_VPN_MODE           = "vpn_mode"
    private const val KEY_PRISM_VPN_ROLE     = "prism_vpn_role"
    private const val KEY_PRISM_VPN_PORT     = "prism_vpn_port"
    private const val KEY_PRISM_VPN_TARGET_IP = "prism_vpn_target_ip"
    private const val KEY_EXTERNAL_VPN_PROFILE = "external_vpn_profile"
    private const val KEY_PRISM_VPN_USERNAME = "prism_vpn_username"
    private const val KEY_PRISM_VPN_PASSWORD  = "prism_vpn_password"
    private const val KEY_APP_WHITELIST       = "app_whitelist"
    private const val KEY_VPN_PROTOCOL_MODE   = "vpn_protocol_mode"
    private const val KEY_PRISM_SERVER_LIST   = "prism_server_list"
    private const val KEY_VPN_SERVER_ALWAYS_ON = "vpn_server_always_on"
    private const val KEY_P2P_SELF_ID         = "p2p_self_id"
    private const val KEY_WG_SERVER_PRIVATE_KEY = "wg_server_private_key"
    private const val KEY_WG_SERVER_PUBLIC_KEY = "wg_server_public_key"
    private const val KEY_WG_SERVER_PORT         = "wg_server_port"
    private const val KEY_WG_ALLOWED_IPS        = "wg_allowed_ips"
    private const val KEY_MESH_BOOTSTRAP_ADDRESS = "mesh_bootstrap_address"
    private const val KEY_MESH_BOOTSTRAP_PORT    = "mesh_bootstrap_port"
    private const val KEY_P2P_HOSTED_SITES       = "p2p_hosted_sites"
    private const val KEY_P2P_MODEL_HOSTING_ENABLED = "p2p_model_hosting_enabled"
    private const val KEY_SELECTED_P2P_MODEL     = "selected_p2p_model"
    private const val KEY_P2P_MIRRORED_SITES     = "p2p_mirrored_sites"
    private const val KEY_NETWORK_STORAGES       = "network_storages"
    const val KEY_ACCESS_POINTS                 = "access_points"
    private const val KEY_FONT_STYLE             = "font_style"
    private const val KEY_CUSTOM_FONT_PATH       = "custom_font_path"
    private const val KEY_VIRT_ENABLED           = "virt_enabled"
    private const val KEY_VIRT_MODE              = "virt_mode"
    private const val KEY_VIRT_ISO_PATH          = "virt_iso_path"
    private const val KEY_THEME_MODE             = "theme_mode"
    private const val KEY_DESKTOP_MOBILE_MODE    = "desktop_mobile_mode"
    private const val KEY_WALLPAPER_PATH         = "wallpaper_path"
    private const val KEY_WALLPAPER_DIM          = "wallpaper_dim"
    private const val KEY_TASKBAR_PINS           = "taskbar_pins"
    private const val KEY_PRIVATE_TABS_PASSPHRASE = "private_tabs_passphrase"
    private const val KEY_PLUGIN_PAGES_ENABLED   = "plugin_pages_enabled"
    private const val KEY_NORA_MOE_ENABLED      = "nora_moe_enabled"
    private const val KEY_NORA_MOE_EXPERTS      = "nora_moe_experts"
    private const val KEY_NORA_MOE_TOPK         = "nora_moe_topk"
    private const val KEY_NORA_MOE_CONSCIENCE   = "nora_moe_conscience"

    const val FONT_STYLE_DEFAULT = "default"
    const val FONT_STYLE_NASALIZATION = "nasalization"
    const val FONT_STYLE_CUSTOM = "custom"

    fun getFontStyle(): String =
        prefs().getString(KEY_FONT_STYLE, FONT_STYLE_DEFAULT) ?: FONT_STYLE_DEFAULT

    fun setFontStyle(value: String) =
        prefs().edit().putString(KEY_FONT_STYLE, value).apply()

    fun getCustomFontPath(): String =
        prefs().getString(KEY_CUSTOM_FONT_PATH, "") ?: ""

    fun setCustomFontPath(value: String) =
        prefs().edit().putString(KEY_CUSTOM_FONT_PATH, value).apply()

    fun getWgAllowedIps(): String =
        prefs().getString(KEY_WG_ALLOWED_IPS, "0.0.0.0/0") ?: "0.0.0.0/0"

    fun setWgAllowedIps(value: String) =
        prefs().edit().putString(KEY_WG_ALLOWED_IPS, value.trim()).apply()

    fun getWgServerPrivateKey(): String {
        var priv = prefs().getString(KEY_WG_SERVER_PRIVATE_KEY, "") ?: ""
        if (priv.isEmpty()) {
            val (generated, pub) = PrismPlatform.wireGuardKeys.generate()
            priv = generated
            prefs().edit()
                .putString(KEY_WG_SERVER_PRIVATE_KEY, priv)
                .putString(KEY_WG_SERVER_PUBLIC_KEY, pub)
                .apply()
        }
        return priv
    }

    fun getWgServerPublicKey(): String {
        getWgServerPrivateKey() // Ensure generated
        return prefs().getString(KEY_WG_SERVER_PUBLIC_KEY, "") ?: ""
    }

    fun getWgServerPort(): Int =
        prefs().getInt(KEY_WG_SERVER_PORT, 51820)

    fun setWgServerPort(value: Int) =
        prefs().edit().putInt(KEY_WG_SERVER_PORT, value).apply()

    fun generateWgClientConfig(): String {
        val serverIp = "YOUR_PHONE_IP_HERE"
        val serverPort = getWgServerPort()
        val serverPubKey = getWgServerPublicKey()
        val allowedIps = getWgAllowedIps()
        
        val (clientPriv, _) = PrismPlatform.wireGuardKeys.generate()
        
        return """
            [Interface]
            PrivateKey = $clientPriv
            Address = 10.8.0.2/24
            DNS = 10.8.0.1
 
            [Peer]
            PublicKey = $serverPubKey
            Endpoint = $serverIp:$serverPort
            AllowedIPs = $allowedIps
        """.trimIndent()
    }

    // Model Download URLs
    const val MODEL_FALCON_1B = "https://huggingface.co/vshymanskyy/falcon-1b-it-tflite/resolve/main/falcon-1b-it-cpu-int4.bin"
    const val MODEL_PHI_2 = "https://huggingface.co/vshymanskyy/phi-2-tflite/resolve/main/phi-2-cpu-int4.bin"
    const val MODEL_QWEN_1_5 = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val MODEL_MOBILEBERT = "https://huggingface.co/google/mobilebert/resolve/main/mobilebert.tflite"

    // Diffusion Models
    const val MODEL_SD_1_5_CPU = "https://huggingface.co/sayakpaul/sd-1.5-openvino-tflite/resolve/main/sd-v1-5-int8-bundle.task"
}
