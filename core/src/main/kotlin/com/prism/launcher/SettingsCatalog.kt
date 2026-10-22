package com.prism.launcher

/**
 * Every Prism setting, declared once, in the order the phone shows them.
 *
 * ## Why a catalog instead of building the screen twice
 *
 * The Android settings screen is 1,723 lines of `buildItems()` that constructs a flat list of rows. It
 * works, it shipped, and it is entirely Android-shaped: each row carries a lambda that opens an Activity,
 * shows an AlertDialog or inflates a View. The desktop had eight settings against the phone's 127, and
 * the reason was never that the settings were Android-specific -- all 151 accessors they read and write
 * are in `PrismSettings`, in `:core`, portable, and already compiled into the desktop build.
 *
 * What was missing was a description of the screen that was not also an implementation of it. That is
 * this: a title, a subtitle, how to read the value, how to write it, and when the row is even relevant.
 * Nothing here knows what a row looks like.
 *
 * ## Why Android does not use it yet
 *
 * Because rewriting a working 1,723-line screen on the shipped product, in the same pass that first
 * consumes the catalog, would mean two untested things at once -- and if the result were wrong the
 * symptom would be somebody's phone settings silently reading or writing the wrong key. The desktop
 * renders this catalog today; Android keeps `buildItems()`. THE DUPLICATION IS DELIBERATE AND TEMPORARY,
 * and it is stated here rather than left for somebody to discover: a setting added to one and not the
 * other is a bug, and this file is the one to add it to.
 *
 * ## What [Action] is, and why it is a name rather than a lambda
 *
 * Some rows do not edit a value -- they open a screen, clear a cache, copy something. A lambda would drag
 * the platform back in, so a row names a [Target] and each host decides what that means. A host that does
 * not recognise one shows the row and says so, which is the honest outcome: "this exists and is not here
 * yet" beats a settings screen that silently has fewer rows than the phone's.
 */
object SettingsCatalog {

    // ── The model ──────────────────────────────────────────────────────────

    /** Where a row's effect actually lands, so a host can say so rather than implying otherwise. */
    enum class Reach {
        /** Works the same on every platform. */
        EVERYWHERE,

        /**
         * Stored here, acted on by the phone.
         *
         * NOT hidden on the desktop, and that is a decision. These are shared preferences: a profile
         * carries them between devices and a trusted pairing syncs them, so setting a keyboard theme on
         * a PC and having the phone pick it up is a real workflow. Hiding them would make the desktop's
         * settings a different, smaller product.
         */
        PHONE_ACTS,

        /** Only meaningful on a desktop. */
        DESKTOP_ONLY,
    }

    sealed interface Item {
        val title: String
        val subtitle: String
        val reach: Reach

        /** Whether the row applies right now, given the other settings. */
        val relevant: () -> Boolean
    }

    /** A boolean. */
    data class Switch(
        override val title: String,
        override val subtitle: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        override val reach: Reach = Reach.EVERYWHERE,
        override val relevant: () -> Boolean = { true },
    ) : Item

    /**
     * One of a fixed set.
     *
     * BY VALUE RATHER THAN BY INDEX, unlike the Android rows this was taken from. Those map an index to a
     * stored string in a `when` at each call site, which means the order of the labels is load-bearing:
     * inserting an option in the middle silently repoints every choice after it. Here the stored value
     * sits beside its label and the order is cosmetic.
     */
    data class Choice(
        override val title: String,
        override val subtitle: String,
        val labels: List<String>,
        val values: List<String>,
        val get: () -> String,
        val set: (String) -> Unit,
        /**
         * Maps whatever is stored onto one of [values], for a setting that is a RANGE underneath.
         *
         * Two of these exist: the keyboard's background dim and its key vibration are stored as 0-100
         * and 0-60, and the phone shows four bands. Writing a band writes one representative number, but
         * the stored value can be anything -- a default of 35, or a figure written by an older build --
         * and a row that compared it literally would highlight no chip at all. The default is identity,
         * which is right for every setting that really is one of a set.
         */
        val normalise: (String) -> String = { it },
        override val reach: Reach = Reach.EVERYWHERE,
        override val relevant: () -> Boolean = { true },
    ) : Item {
        init {
            require(labels.size == values.size) {
                "the labels and values of '" + title + "' must correspond one to one"
            }
        }

        /** Which option is active, after normalising. */
        fun selected(): String = runCatching { normalise(get()) }.getOrDefault(values.first())
    }

    /** Free text, optionally numeric. */
    data class Text(
        override val title: String,
        override val subtitle: String,
        val get: () -> String,
        val set: (String) -> Unit,
        val numeric: Boolean = false,
        val multiline: Boolean = false,
        /** True for passwords and keys, so a host can mask them. */
        val secret: Boolean = false,
        override val reach: Reach = Reach.EVERYWHERE,
        override val relevant: () -> Boolean = { true },
    ) : Item

    /** A row that does something rather than holding a value. */
    data class Action(
        override val title: String,
        override val subtitle: String,
        val target: Target,
        override val reach: Reach = Reach.EVERYWHERE,
        override val relevant: () -> Boolean = { true },
    ) : Item

    /** Read-only, for something worth showing that nobody edits here. */
    data class Info(
        override val title: String,
        override val subtitle: String,
        val value: () -> String,
        override val reach: Reach = Reach.EVERYWHERE,
        override val relevant: () -> Boolean = { true },
    ) : Item

    /**
     * What an [Action] row asks the host to do.
     *
     * A CLOSED SET RATHER THAN A STRING, so a host that handles them with a `when` is told by the
     * compiler when a new one appears instead of quietly falling through to "not supported here".
     */
    enum class Target {
        ADD_SEED, VIEW_SEEDS, VIEW_DISCOVERED_SEEDS, REBUILD_INDEX, DIAGNOSE_SEARCH,
        OPEN_BLOCKLIST, CLEAR_HISTORY, VIEW_CACHED_SITES,
        VIEW_LOCAL_ADDRESS, MANAGE_PRISM_SERVERS, EXTERNAL_VPN_PROFILE, APP_WHITELIST,
        COPY_WIREGUARD_CONFIG, OPEN_TRUSTED_DEVICES,
        OPEN_ACCESS_POINT, MANAGE_DNS_RECORDS, MANAGE_WEB_HOSTING, OPEN_DIAGNOSTICS,
        OPEN_MODELS, OPEN_CLOUD_MODELS, RESCAN_OLLAMA, OPEN_LOCAL_MODEL, OPEN_SPEECH_MODEL,
        OPEN_IMAGE_MODELS, OPEN_NORA, OPEN_AETHER, OPEN_CAKECHAT, AUTO_CAPTION_FOLDER,
        OPEN_PRISM_SWAP, OPEN_DATASETS, OPEN_WALLET, OPEN_MINING, OPEN_WRITER, WRITER_DICTIONARY,
        SELECT_CUSTOM_FONT, SUMMARY_STATUS, SELECT_ISO, OPEN_TOUR, OPEN_LOCK_SCREEN,
        EMERGENCY_CONTACTS, STREMIO_REPOSITORIES, STREMIO_ADDONS,
    }

    data class Section(val title: String, val items: List<Item>)

    /**
     * How the sections are grouped on the root screen, matching the phone's own grouping.
     *
     * A section named by no group falls into "Other", exactly as `groupOf` does on Android -- so a
     * section added here without being grouped still appears rather than vanishing.
     */
    data class Group(val title: String, val summary: String, val sections: List<String>)

    val GROUPS: List<Group> = listOf(
        Group(
            "Launcher & Appearance",
            "Default page, gestures, layout, theme and fonts",
            listOf("Launcher", "Launcher Aesthetic", "Typography"),
        ),
        Group(
            "Browser & Content",
            "Search engine, browsing behaviour and blocked domains",
            listOf("Browser", "Blocklist"),
        ),
        Group(
            "Network, VPN & Mesh",
            "Tunnelling, hotspot gateway, WireGuard and decentralized DNS",
            listOf(
                "Privacy & VPN", "Privacy & History", "Mesh Bootstrap Server",
                "Access Points (Hotspot Gateway)", "Native VPN Server (WireGuard)",
                "Decentralized Name System", "Trusted devices",
            ),
        ),
        Group(
            "Intelligence & Messaging",
            "AI engine, models, image generation, Nora, Aether and response behaviour",
            listOf(
                "Intelligence & Messaging", "Speech", "Available LLM Models",
                "Visual Intelligence (Diffusion)", "Nora (Brain-Based Generation)",
                "Aether (Second Brain-Based AI)", "Response Behavior",
            ),
        ),
        Group("Wallet & Mining", "Mining, selling models and node hosting", listOf("Wallet Mining", "Selling Models")),
        Group("Virtualization", "Run a guest OS inside Prism", listOf("OS Virtualization")),
        Group("Keyboard", "Prism Writer", listOf("Prism Writer (Keyboard)")),
        Group("Personal", "Lock screen, medical card and getting started", listOf("Medical", "Getting started")),
        Group("Media", "Stremio", listOf("Stremio")),
    )

    fun groupOf(section: String): String =
        GROUPS.firstOrNull { section in it.sections }?.title ?: "Other"

    // ── The sections ───────────────────────────────────────────────────────

    /**
     * Built fresh on every call, not held in a val.
     *
     * Each row closes over `PrismSettings`, so a cached list would still read correctly -- but
     * [Item.relevant] is evaluated by the host to decide whether to show a row at all, and a list built
     * once at class-init would capture the relevance lambdas against settings as they were when the
     * process started. Cheap enough to rebuild: it is a few hundred object allocations.
     */
    fun sections(): List<Section> = listOf(
        launcher(), launcherAesthetic(), browser(), privacyAndVpn(), meshBootstrap(),
        privacyAndHistory(), accessPoints(), wireGuard(), intelligence(), speech(), llmModels(),
        diffusion(), nora(), aether(), responseBehavior(), trustedDevices(), blocklist(),
        nameSystem(), typography(), writer(), mining(), sellingModels(), virtualization(),
        gettingStarted(), medical(), stremio(),
    )

    /** Every item, flattened, for a search box. */
    fun all(): List<Pair<String, Item>> =
        sections().flatMap { section -> section.items.map { section.title to it } }

    /** Cyan, magenta, lime, gold, electric blue -- the phone's own five, as ARGB. */
    private val GLOW_COLOURS = listOf(
        0xFF7C9EFF, 0xFFFF00FF, 0xFF00FF00, 0xFFFFD700, 0xFF2222FF,
    ).map { it.toInt() }

    private val tunnelling = { PrismSettings.getVpnTunnelingEnabled() }
    private val prismVpn = { tunnelling() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM }
    private val prismServer = { prismVpn() && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER }

    private fun launcher() = Section(
        "Launcher",
        listOf(
            Choice(
                "Default page", "Which page shows when Prism opens",
                labels = listOf("Left (Browser)", "Center (Desktop)", "Right (App drawer)"),
                values = listOf("0", "1", "2"),
                get = { PrismSettings.getDefaultPage().toString() },
                set = { PrismSettings.setDefaultPage(it.toIntOrNull() ?: 1) },
            ),
        ),
    )

    private fun launcherAesthetic() = Section(
        "Launcher Aesthetic",
        listOf(
            // The pack LIST is Android's -- it comes from installed packages -- so the desktop shows the
            // stored value and lets it be cleared rather than inventing a chooser for packs it cannot see.
            Text(
                "Icon pack", "The Android package whose icons Prism uses. Blank means system default.",
                get = { PrismSettings.getIconPackPackage() },
                set = { PrismSettings.setIconPackPackage(it.trim()) },
                reach = Reach.PHONE_ACTS,
            ),
            Switch(
                "Show drawer labels", "Display app names below icons in the drawer",
                get = { PrismSettings.getShowDrawerLabels() },
                set = { PrismSettings.setShowDrawerLabels(it) },
            ),
            Choice(
                "Glow accent", "The glow colour for borders and navigation",
                labels = listOf("Cyan", "Magenta", "Lime", "Gold", "Electric Blue"),
                // FROM HEX, LET THE COMPILER DO THE CONVERSION. Writing the signed decimals by hand
                // got two of the five wrong, which renders as a chip row with nothing selected -- the
                // stored colour simply is not in the list. These are the same five the phone offers.
                values = GLOW_COLOURS.map { it.toString() },
                get = { PrismSettings.getGlowColor().toString() },
                set = { PrismSettings.setGlowColor(it.toIntOrNull() ?: GLOW_COLOURS.first()) },
            ),
        ),
    )

    private fun browser() = Section(
        "Browser",
        listOf(
            Choice(
                "Search engine", "Default engine for the address bar",
                labels = listOf("Prism", "DuckDuckGo", "Google", "Bing", "Custom"),
                values = listOf("prism", "ddg", "google", "bing", "custom"),
                get = { PrismSettings.getSearchEngine() },
                set = { PrismSettings.setSearchEngine(it) },
            ),
            Choice(
                "Prism search crawl interval", "How often the crawler runs",
                labels = listOf("Off", "Every hour", "Every 2 hours", "Every 6 hours", "Every 12 hours", "Daily"),
                values = listOf("0", "1", "2", "6", "12", "24"),
                get = { PrismSettings.getSearchCrawlIntervalHours().toString() },
                set = { PrismSettings.setSearchCrawlIntervalHours(it.toIntOrNull() ?: 6) },
            ),
            Text(
                "Search seeds", "One address per line. The crawl starts from these.",
                get = { PrismSettings.getUserSearchSeedsRaw() },
                set = { PrismSettings.setSearchSeeds(it) },
                multiline = true,
            ),
            Action("View all seeds", "Yours, the crawler's and the built-in floor", Target.VIEW_SEEDS),
            Text(
                "Maximum discovered seeds", "How many addresses the crawler may add by itself",
                get = { PrismSettings.getMaxDiscoveredSeeds().toString() },
                set = { PrismSettings.setMaxDiscoveredSeeds(it.toIntOrNull() ?: 500) },
                numeric = true,
            ),
            Action("Add a seed", "Give the crawler somewhere new to start", Target.ADD_SEED),
            Action("Sites found by the crawler", "What it added on its own", Target.VIEW_DISCOVERED_SEEDS),
            Action("Diagnose search and browser", "What is running and what is not", Target.DIAGNOSE_SEARCH),
            Action("Rebuild the search index now", "Crawl from the seeds again", Target.REBUILD_INDEX),
            Switch(
                "Enable JavaScript", "Off breaks many sites and stops most tracking",
                get = { PrismSettings.getJsEnabled() },
                set = { PrismSettings.setJsEnabled(it) },
            ),
            Switch(
                "Private by default", "New tabs open private",
                get = { PrismSettings.getPrivateByDefault() },
                set = { PrismSettings.setPrivateByDefault(it) },
            ),
        ),
    )

    private fun privacyAndVpn() = Section(
        "Privacy & VPN",
        listOf(
            Switch(
                "Enable VPN tunnelling", "The master switch for everything below",
                get = { PrismSettings.getVpnTunnelingEnabled() },
                set = { PrismSettings.setVpnTunnelingEnabled(it) },
            ),
            Switch(
                "VPN auto-start", "Bring the tunnel up when a private tab opens",
                get = { PrismSettings.getVpnAutoStart() },
                set = { PrismSettings.setVpnAutoStart(it) },
                relevant = tunnelling,
            ),
            Choice(
                "VPN mode", "Prism's own mesh tunnel, or a config from somebody else",
                labels = listOf("Prism VPN", "External VPN"),
                values = listOf(PrismSettings.VPN_MODE_PRISM, PrismSettings.VPN_MODE_EXTERNAL),
                get = { PrismSettings.getVpnMode() },
                set = { PrismSettings.setVpnMode(it) },
                relevant = tunnelling,
            ),
            Switch(
                "Persistent VPN server", "Keep the tunnel up after the last private tab closes",
                get = { PrismSettings.getVpnServerAlwaysOn() },
                set = { PrismSettings.setVpnServerAlwaysOn(it) },
                relevant = prismVpn,
            ),
            Choice(
                "Prism VPN role", "Serve the tunnel, or connect to somebody who does",
                labels = listOf("Client", "Server"),
                values = listOf(PrismSettings.PRISM_ROLE_CLIENT, PrismSettings.PRISM_ROLE_SERVER),
                get = { PrismSettings.getPrismVpnRole() },
                set = { PrismSettings.setPrismVpnRole(it) },
                relevant = prismVpn,
            ),
            Choice(
                "VPN protocol", "What the server speaks",
                labels = listOf("Automatic (detected)", "IKEv2", "L2TP", "Proxy only"),
                values = listOf(
                    PrismSettings.VPN_PROTOCOL_AUTO, PrismSettings.VPN_PROTOCOL_IKEV2,
                    PrismSettings.VPN_PROTOCOL_L2TP, PrismSettings.VPN_PROTOCOL_PROXY,
                ),
                get = { PrismSettings.getVpnProtocolMode() },
                set = { PrismSettings.setVpnProtocolMode(it) },
                relevant = prismServer,
            ),
            Action("This device's address", "What a peer connects to", Target.VIEW_LOCAL_ADDRESS, relevant = prismVpn),
            Text(
                "Server port", "The port the tunnel listens on",
                get = { PrismSettings.getPrismVpnPort() },
                set = { PrismSettings.setPrismVpnPort(it) },
                numeric = true,
                relevant = prismServer,
            ),
            Text(
                "Proxy username", "For a client connecting to this server",
                get = { PrismSettings.getPrismVpnUsername() },
                set = { PrismSettings.setPrismVpnUsername(it) },
                relevant = prismServer,
            ),
            Text(
                "Proxy password", "For a client connecting to this server",
                get = { PrismSettings.getPrismVpnPassword() },
                set = { PrismSettings.setPrismVpnPassword(it) },
                secret = true,
                relevant = prismServer,
            ),
            Action(
                "Manage Prism servers", "Addresses this device connects to",
                Target.MANAGE_PRISM_SERVERS, relevant = prismVpn,
            ),
        ),
    )

    private fun meshBootstrap() = Section(
        "Mesh Bootstrap Server",
        listOf(
            Switch(
                "Enable mesh", "Discovery, gossip and the peer list",
                get = { PrismSettings.getMeshEnabled() },
                set = { PrismSettings.setMeshEnabled(it) },
            ),
            Text(
                "Bootstrap address", "A peer to ask when broadcast finds nobody",
                get = { PrismSettings.getMeshBootstrapAddress() },
                set = { PrismSettings.setMeshBootstrapAddress(it) },
            ),
            Text(
                "Bootstrap port", "Default 8081",
                get = { PrismSettings.getMeshBootstrapPort() },
                set = { PrismSettings.setMeshBootstrapPort(it) },
                numeric = true,
            ),
            Action(
                "External VPN profile", "Paste a WireGuard, OpenVPN or strongSwan config",
                Target.EXTERNAL_VPN_PROFILE,
                relevant = { tunnelling() && PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_EXTERNAL },
            ),
            Action("App whitelist", "Programs that bypass the tunnel", Target.APP_WHITELIST, relevant = tunnelling),
        ),
    )

    private fun privacyAndHistory() = Section(
        "Privacy & History",
        listOf(
            Switch(
                "Keep a personal history", "Pages, messages and what Prism did",
                get = { PrismSettings.getHistoryEnabled() },
                set = { PrismSettings.setHistoryEnabled(it) },
            ),
            Switch(
                "Let the AI search your history", "Sam can look things up in it",
                get = { PrismSettings.getHistoryToolEnabled() },
                set = { PrismSettings.setHistoryToolEnabled(it) },
                relevant = { PrismSettings.getHistoryEnabled() },
            ),
            Action("Clear personal history", "Everything, with no undo", Target.CLEAR_HISTORY),
            Switch(
                "Locked private tabs", "A private tab asks for the passphrase before it shows anything",
                get = { PrismSettings.getPrivateTabsLocked() },
                set = { PrismSettings.setPrivateTabsLocked(it) },
            ),
            Switch(
                "Allow web caching", "Keep a copy of pages you visit",
                get = { PrismSettings.getWebCacheEnabled() },
                set = { PrismSettings.setWebCacheEnabled(it) },
            ),
            Switch(
                "Share cached sites on the mesh", "Peers can read your copy of a site",
                get = { PrismSettings.getWebCacheMeshSharing() },
                set = { PrismSettings.setWebCacheMeshSharing(it) },
                relevant = { PrismSettings.getWebCacheEnabled() },
            ),
            Switch(
                "Add cached videos to Lyke", "Video found while browsing appears in the feed",
                get = { PrismSettings.getWebCacheLykeUpload() },
                set = { PrismSettings.setWebCacheLykeUpload(it) },
                relevant = { PrismSettings.getWebCacheEnabled() },
            ),
            Action("Cached sites", "What is stored, and how much of it", Target.VIEW_CACHED_SITES),
        ),
    )

    private fun accessPoints() = Section(
        "Access Points (Hotspot Gateway)",
        listOf(
            Action("Manage access points", "Share this connection with other devices", Target.OPEN_ACCESS_POINT),
            Switch(
                "Enable DNS proxy", "Answer names for devices on the hotspot",
                get = { PrismSettings.getDnsProxyEnabled() },
                set = { PrismSettings.setDnsProxyEnabled(it) },
            ),
            Choice(
                "DNS proxy mode", "What to do with a name the mesh does not know",
                labels = listOf("P2P isolation (refuse it)", "Fall back to global DNS"),
                values = listOf("p2p_only", "fallback"),
                get = { PrismSettings.getDnsProxyMode() },
                set = { PrismSettings.setDnsProxyMode(it) },
                relevant = { PrismSettings.getDnsProxyEnabled() },
            ),
            Action("P2P DNS records", "Names this device answers for", Target.MANAGE_DNS_RECORDS),
            Action("P2P web hosting", "Folders served as websites", Target.MANAGE_WEB_HOSTING),
            Switch(
                "Host my active model", "Peers may run inference on this device's model",
                get = { PrismSettings.getP2pModelHostingEnabled() },
                set = { PrismSettings.setP2pModelHostingEnabled(it) },
            ),
            Text(
                "Primary DNS", "Where names go when the mesh does not know them",
                get = { PrismSettings.getPrimaryDns() },
                set = { PrismSettings.setPrimaryDns(it) },
            ),
            Text(
                "Secondary DNS", "The fallback resolver",
                get = { PrismSettings.getSecondaryDns() },
                set = { PrismSettings.setSecondaryDns(it) },
            ),
        ),
    )

    private fun wireGuard() = Section(
        "Native VPN Server (WireGuard)",
        listOf(
            Text(
                "WireGuard listen port", "Default 51820",
                get = { PrismSettings.getWgServerPort().toString() },
                set = { PrismSettings.setWgServerPort(it.toIntOrNull() ?: 51820) },
                numeric = true,
            ),
            Text(
                "Allowed IPs", "What a connecting peer may route through this device",
                get = { PrismSettings.getWgAllowedIps() },
                set = { PrismSettings.setWgAllowedIps(it) },
            ),
            Action("Client config", "Generate a config for another device", Target.COPY_WIREGUARD_CONFIG),
            Action("Establish mesh trust", "Pair with a device you own", Target.OPEN_TRUSTED_DEVICES),
        ),
    )

    private fun intelligence() = Section(
        "Intelligence & Messaging",
        listOf(
            Choice(
                "Prism AI engine", "Where answers come from",
                labels = listOf("Local AI", "Cloud API", "Local cloud (Ollama)"),
                values = listOf(
                    PrismSettings.AI_MODE_LOCAL, PrismSettings.AI_MODE_CLOUD,
                    PrismSettings.AI_MODE_LOCAL_CLOUD,
                ),
                get = { PrismSettings.getAiMode() },
                set = { PrismSettings.setAiMode(it) },
            ),
            Action("Local AI model", "Which GGUF file Prism loads", Target.OPEN_LOCAL_MODEL),
            Action("Manage cloud models", "Keys and endpoints", Target.OPEN_CLOUD_MODELS),
            Action("Rescan the network for Ollama", "Find a server on the LAN", Target.RESCAN_OLLAMA),
            Action("Auto-caption a folder", "Describe every picture in it", Target.AUTO_CAPTION_FOLDER),
        ),
    )

    private fun speech() = Section(
        "Speech",
        listOf(
            Text(
                "Kokoro voice model", "The path to the speech model",
                get = { PrismSettings.getLocalAudioModelPath() },
                set = { PrismSettings.setLocalAudioModelPath(it) },
            ),
            Action("Speech model", "Download or choose one", Target.OPEN_SPEECH_MODEL),
        ),
    )

    private fun llmModels() = Section(
        "Available LLM Models",
        listOf(
            Action("Models", "Everything installed, hosted or offered on the mesh", Target.OPEN_MODELS),
            Switch(
                "Answer with CakeChat", "A conversational model rather than an instruct one",
                get = { PrismSettings.getUseCakeChat() },
                set = { PrismSettings.setUseCakeChat(it) },
            ),
            Action("CakeChat", "Install it, train it, or check on it", Target.OPEN_CAKECHAT),
        ),
    )

    private fun diffusion() = Section(
        "Visual Intelligence (Diffusion)",
        listOf(
            Action("Image models", "Search for and install a diffusion model", Target.OPEN_IMAGE_MODELS),
        ),
    )

    private fun nora() = Section(
        "Nora (Brain-Based Generation)",
        listOf(Action("Nora", "A predictive-coding brain rather than a model", Target.OPEN_NORA)),
    )

    private fun aether() = Section(
        "Aether (Second Brain-Based AI)",
        listOf(Action("Aether", "A spiking network that reads with an eye", Target.OPEN_AETHER)),
    )

    private fun responseBehavior() = Section(
        "Response Behavior",
        listOf(
            Switch(
                "Stream responses", "Show tokens as they arrive",
                get = { PrismSettings.getStreamingEnabled() },
                set = { PrismSettings.setStreamingEnabled(it) },
            ),
            Text(
                "Maximum tokens", "How long an answer may be",
                get = { PrismSettings.getMaxTokens().toString() },
                set = { PrismSettings.setMaxTokens(it.toIntOrNull() ?: 512) },
                numeric = true,
            ),
            Choice(
                "KV cache compression", "Trades a little quality for a lot of memory",
                labels = listOf("Off (full precision)", "Light (Q8, about half)", "Maximum (Q4, about a quarter)"),
                values = listOf(PrismSettings.KV_CACHE_F16, PrismSettings.KV_CACHE_Q8_0, PrismSettings.KV_CACHE_Q4_0),
                get = { PrismSettings.getKvCacheQuant() },
                set = { PrismSettings.setKvCacheQuant(it) },
            ),
            Choice(
                "AI backend", "Which processor runs the model",
                labels = listOf("CPU", "GPU", "NPU"),
                values = listOf("0", "1", "2"),
                get = { PrismSettings.getAiBackend().toString() },
                set = { PrismSettings.setAiBackend(it.toIntOrNull() ?: 0) },
            ),
            Action("Prism Swap", "Lend a model more memory than the device has", Target.OPEN_PRISM_SWAP),
            Action("Dataset downloads", "What Prism may fetch to train on", Target.OPEN_DATASETS),
            Choice(
                "Nebula post interval", "How often the personas post",
                // THE PHONE'S OWN SIX, and it has no "off": the default is 2 hours, which an invented
                // option list of 0/1/3/6/12/24 did not contain -- so the row rendered with nothing
                // selected on a fresh install. Guessing a plausible set is how that happens.
                labels = listOf("Hourly", "Every 2 hours", "Every 4 hours", "Every 6 hours", "Every 12 hours", "Daily"),
                values = listOf("1", "2", "4", "6", "12", "24"),
                get = { PrismSettings.getNebulaGenerationIntervalHours().toString() },
                set = { PrismSettings.setNebulaGenerationIntervalHours(it.toIntOrNull() ?: 2) },
            ),
        ),
    )

    private fun trustedDevices() = Section(
        "Trusted devices",
        listOf(
            Action("Manage trusted devices", "Pair, share and revoke", Target.OPEN_TRUSTED_DEVICES),
            Choice(
                "Pairing code length", "Longer is harder to guess and harder to read out",
                labels = listOf("4", "6", "8", "10", "12", "16"),
                values = listOf("4", "6", "8", "10", "12", "16"),
                get = { PrismSettings.getPairingCodeLength().toString() },
                set = { PrismSettings.setPairingCodeLength(it.toIntOrNull() ?: 6) },
            ),
        ),
    )

    private fun blocklist() = Section(
        "Blocklist",
        listOf(Action("Manage the blocklist", "Domains Prism refuses", Target.OPEN_BLOCKLIST)),
    )

    private fun nameSystem() = Section(
        "Decentralized Name System",
        listOf(
            Action("Manage P2P DNS", "Names the mesh knows", Target.MANAGE_DNS_RECORDS),
            Action("P2P web hosting", "What this device serves", Target.MANAGE_WEB_HOSTING),
            Action("System diagnostics", "What is running", Target.OPEN_DIAGNOSTICS),
        ),
    )

    private fun typography() = Section(
        "Typography",
        listOf(
            Choice(
                "Font style", "The typeface Prism draws with",
                labels = listOf("System default", "Nasalization (modern)", "Custom file"),
                values = listOf(
                    PrismSettings.FONT_STYLE_DEFAULT, PrismSettings.FONT_STYLE_NASALIZATION,
                    PrismSettings.FONT_STYLE_CUSTOM,
                ),
                get = { PrismSettings.getFontStyle() },
                set = { PrismSettings.setFontStyle(it) },
            ),
            Action(
                "Select a custom font", "A .ttf file", Target.SELECT_CUSTOM_FONT,
                relevant = { PrismSettings.getFontStyle() == PrismSettings.FONT_STYLE_CUSTOM },
            ),
            Switch(
                "AI search summaries", "Sum up results rather than only listing them",
                get = { PrismSettings.getSearchAiSummary() },
                set = { PrismSettings.setSearchAiSummary(it) },
            ),
            Action(
                "Summary status", "Whether a model is available to write them", Target.SUMMARY_STATUS,
                relevant = { PrismSettings.getSearchAiSummary() },
            ),
        ),
    )

    private fun writer() = Section(
        "Prism Writer (Keyboard)",
        listOf(
            Action("Enable Prism Writer", "Turn the keyboard on in system settings", Target.OPEN_WRITER, Reach.PHONE_ACTS),
            Choice(
                "Keyboard theme", "Light, dark or whatever the system is doing",
                labels = listOf("Follow system", "Always light", "Always dark"),
                values = listOf(
                    PrismSettings.WRITER_THEME_SYSTEM, PrismSettings.WRITER_THEME_LIGHT,
                    PrismSettings.WRITER_THEME_DARK,
                ),
                get = { PrismSettings.getWriterTheme() },
                set = { PrismSettings.setWriterTheme(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Text(
                "Keyboard background", "A picture behind the keys",
                get = { PrismSettings.getWriterBackgroundImage() },
                set = { PrismSettings.setWriterBackgroundImage(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Choice(
                "Background dim", "How far the picture is faded so keys stay readable",
                labels = listOf("None", "Light", "Medium", "Heavy"),
                // The four numbers the phone writes, and the phone's own bands for reading any other
                // value back -- including the default of 35, which is none of them.
                values = listOf("0", "20", "40", "65"),
                get = { PrismSettings.getWriterBackgroundDim().toString() },
                set = { PrismSettings.setWriterBackgroundDim(it.toIntOrNull() ?: 20) },
                normalise = { stored ->
                    when (stored.toIntOrNull() ?: 0) {
                        0 -> "0"
                        in 1..25 -> "20"
                        in 26..50 -> "40"
                        else -> "65"
                    }
                },
                reach = Reach.PHONE_ACTS,
                relevant = { PrismSettings.getWriterBackgroundImage().isNotBlank() },
            ),
            Action("Dictionary", "Words you have taught it", Target.WRITER_DICTIONARY, Reach.PHONE_ACTS),
            Switch(
                "Suggestion strip", "Words above the keys",
                get = { PrismSettings.getWriterSuggestions() },
                set = { PrismSettings.setWriterSuggestions(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Switch(
                "Glowing swipe trail", "A trail behind your finger",
                get = { PrismSettings.getWriterTrailGlow() },
                set = { PrismSettings.setWriterTrailGlow(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Switch(
                "Swipe typing", "Draw through the letters instead of tapping",
                get = { PrismSettings.getWriterSwipeEnabled() },
                set = { PrismSettings.setWriterSwipeEnabled(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Switch(
                "Autocorrect", "Fix words as you finish them",
                get = { PrismSettings.getWriterAutocorrect() },
                set = { PrismSettings.setWriterAutocorrect(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Choice(
                "Key vibration", "How hard a key press buzzes",
                labels = listOf("Off", "Light", "Medium", "Strong"),
                values = listOf("0", "8", "14", "25"),
                get = { PrismSettings.getWriterHapticsMs().toString() },
                set = { PrismSettings.setWriterHapticsMs(it.toIntOrNull() ?: 8) },
                normalise = { stored ->
                    when (stored.toIntOrNull() ?: 0) {
                        0 -> "0"
                        in 1..10 -> "8"
                        in 11..20 -> "14"
                        else -> "25"
                    }
                },
                reach = Reach.PHONE_ACTS,
            ),
            Switch(
                "AI assisted typing", "Rewrite, translate and continue as you type",
                get = { PrismSettings.getWriterAiAssist() },
                set = { PrismSettings.setWriterAiAssist(it) },
                reach = Reach.PHONE_ACTS,
            ),
            Text(
                "Translate into", "The language the keyboard translates to",
                get = { PrismSettings.getWriterTranslateTarget() },
                set = { PrismSettings.setWriterTranslateTarget(it) },
                reach = Reach.PHONE_ACTS,
                relevant = { PrismSettings.getWriterAiAssist() },
            ),
            Switch(
                "Speak translations", "Read the translation aloud",
                get = { PrismSettings.getWriterSpeakTranslation() },
                set = { PrismSettings.setWriterSpeakTranslation(it) },
                reach = Reach.PHONE_ACTS,
                relevant = { PrismSettings.getWriterAiAssist() },
            ),
        ),
    )

    private fun mining() = Section(
        "Wallet Mining",
        listOf(
            Choice(
                "Mining mode", "Where the work goes",
                labels = listOf("Pool (Stratum)", "Solo (your own node)", "Mesh pool (share the connection)"),
                values = listOf(
                    PrismSettings.MINING_MODE_POOL, PrismSettings.MINING_MODE_SOLO,
                    PrismSettings.MINING_MODE_MESH,
                ),
                get = { PrismSettings.getMiningMode() },
                set = { PrismSettings.setMiningMode(it) },
            ),
            Choice(
                "Mining threads", "How much of the processor to spend",
                labels = listOf("Automatic", "1", "2", "3", "4", "6", "8"),
                values = listOf("0", "1", "2", "3", "4", "6", "8"),
                get = { PrismSettings.getMiningThreads().toString() },
                set = { PrismSettings.setMiningThreads(it.toIntOrNull() ?: 0) },
            ),
            Switch(
                "Host Prism's own node", "Run the chain here rather than trusting somebody else's",
                get = { PrismSettings.getSelfHostNode() },
                set = { PrismSettings.setSelfHostNode(it) },
            ),
            Text(
                "Solo node RPC URL", "Where the node answers",
                get = { PrismSettings.getSoloNodeUrl() },
                set = { PrismSettings.setSoloNodeUrl(it) },
                relevant = { PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO },
            ),
            Text(
                "Solo node credentials", "user:password for the RPC",
                get = { PrismSettings.getSoloNodeCredentials() },
                set = { PrismSettings.setSoloNodeCredentials(it) },
                secret = true,
                relevant = { PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO },
            ),
            Action("Wallet", "Coins, addresses and the recovery phrase", Target.OPEN_WALLET),
        ),
    )

    /**
     * Selling models.
     *
     * TWO OF THESE READ THE SCANNER'S OWN PREFERENCE FILE rather than `PrismSettings`, by name and by
     * key. `ModelListingScanner` keeps its settings in `prism_model_scanner`, and it is a large Android
     * class this pass did not move; reading the same store through the platform's key-value interface
     * gets the desktop the same two settings, on the same keys, without moving it. If the scanner is
     * ported later it will find the values already where it expects them.
     */
    private fun sellingModels(): Section {
        val scanner = com.prism.core.PrismPlatform.host.prefs("prism_model_scanner")
        return Section(
        "Selling Models",
        listOf(
            Switch(
                "Check on every sale",
                "Check GitHub and Hugging Face the moment somebody buys, instead of waiting for the " +
                    "periodic sweep. Prism only allows selling models you made.",
                get = { scanner.getBoolean("verify_on_sale", false) },
                set = { scanner.edit().putBoolean("verify_on_sale", it).apply() },
            ),
            Choice(
                "Check interval", "How often the sweep runs when it is not checking per sale",
                labels = listOf("Every 2 hours", "Every 6 hours", "Every 12 hours", "Daily"),
                values = listOf("2", "6", "12", "24"),
                get = { scanner.getInt("interval_hours", 2).coerceAtLeast(2).toString() },
                set = { scanner.edit().putInt("interval_hours", it.toIntOrNull() ?: 2).apply() },
                relevant = { !scanner.getBoolean("verify_on_sale", false) },
            ),
            Switch(
                "Compile missing libraries on device", "Build what is not shipped, rather than doing without",
                get = { PrismSettings.getExperimentalCompiler() },
                set = { PrismSettings.setExperimentalCompiler(it) },
            ),
            Text(
                "Toolchain pack URL", "Where the compiler comes from",
                get = { PrismSettings.getToolchainUrl() },
                set = { PrismSettings.setToolchainUrl(it) },
                relevant = { PrismSettings.getExperimentalCompiler() },
            ),
            Action("Mining and sales", "What this device is selling and earning", Target.OPEN_MINING),
        ),
        )
    }

    private fun virtualization() = Section(
        "OS Virtualization",
        listOf(
            Switch(
                "Virtualize Android apps", "Run an app inside Prism rather than beside it",
                get = { PrismSettings.getVirtualizeAndroidApps() },
                set = { PrismSettings.setVirtualizeAndroidApps(it) },
            ),
            Switch(
                "Run Windows executables", "Switch the drawer to Windows programs",
                get = { PrismSettings.getWindowsMode() },
                set = { PrismSettings.setWindowsMode(it) },
            ),
            Text(
                "Windows layer source", "Where the compatibility layer is fetched from",
                get = { PrismSettings.getWindowsLayerUrl() },
                set = { PrismSettings.setWindowsLayerUrl(it) },
                relevant = { PrismSettings.getWindowsMode() },
            ),
            Switch(
                "Enable virtualization", "Run a whole guest operating system",
                get = { PrismSettings.getVirtualizationEnabled() },
                set = { PrismSettings.setVirtualizationEnabled(it) },
            ),
            Choice(
                "Virtualization mode", "PrismOS, or an ISO of your own",
                labels = listOf("PrismOS (lightweight AOSP)", "Custom ISO"),
                values = listOf(PrismSettings.VIRT_MODE_PRISM_OS, PrismSettings.VIRT_MODE_CUSTOM_ISO),
                get = { PrismSettings.getVirtualizationMode() },
                set = { PrismSettings.setVirtualizationMode(it) },
                relevant = { PrismSettings.getVirtualizationEnabled() },
            ),
            Action(
                "Select an ISO", "The disc image to boot", Target.SELECT_ISO,
                relevant = {
                    PrismSettings.getVirtualizationEnabled() &&
                        PrismSettings.getVirtualizationMode() == PrismSettings.VIRT_MODE_CUSTOM_ISO
                },
            ),
        ),
    )

    private fun gettingStarted() = Section(
        "Getting started",
        listOf(Action("Take the tour again", "What Prism is and where things are", Target.OPEN_TOUR)),
    )

    /**
     * The medical card.
     *
     * NOT BACKED BY `PrismSettings`. It lives in its own JSON file through `MedicalRecord`, which moved
     * into `:core` for this -- the record is read by whatever draws the lock screen, and a lock screen is
     * the phone's, but the DATA is worth editing on a machine with a real keyboard. Somebody typing out
     * their conditions and medications is not doing it comfortably on a phone.
     *
     * Each row reads the whole record and writes it back with one field changed, which is how
     * `MedicalRecord` is shaped: it is a single immutable Record saved atomically, not a bag of keys.
     */
    private fun medical(): Section {
        fun record() = com.prism.launcher.lock.MedicalRecord.get()
        fun save(update: (com.prism.launcher.lock.MedicalRecord.Record) ->
            com.prism.launcher.lock.MedicalRecord.Record) {
            com.prism.launcher.lock.MedicalRecord.save(update(record()))
        }

        return Section(
            "Medical",
            listOf(
                Action("Lock screen", "What shows before you unlock", Target.OPEN_LOCK_SCREEN, Reach.PHONE_ACTS),
                Switch(
                    "Show the medical card on the lock screen",
                    "Visible without unlocking, which is the entire point of it",
                    get = { PrismSettings.getMedicalOnLock() },
                    set = { PrismSettings.setMedicalOnLock(it) },
                    reach = Reach.PHONE_ACTS,
                ),
                Choice(
                    "Blood type", "Shown to whoever finds the phone",
                    labels = com.prism.launcher.lock.MedicalRecord.BLOOD_TYPES.map { it.ifBlank { "Not set" } },
                    values = com.prism.launcher.lock.MedicalRecord.BLOOD_TYPES.map { it.ifBlank { "-" } },
                    get = { record().bloodType.ifBlank { "-" } },
                    set = { value -> save { it.copy(bloodType = if (value == "-") "" else value) } },
                ),
                Text(
                    "Severe allergies", "Anything that would change how you are treated",
                    get = { record().allergies },
                    set = { value -> save { it.copy(allergies = value) } },
                    multiline = true,
                ),
                Text(
                    "Conditions", "Diagnoses a responder should know about",
                    get = { record().conditions },
                    set = { value -> save { it.copy(conditions = value) } },
                    multiline = true,
                ),
                Text(
                    "Medications", "What you take, and how much",
                    get = { record().medications },
                    set = { value -> save { it.copy(medications = value) } },
                    multiline = true,
                ),
                Switch(
                    "Do not resuscitate", "Shown prominently on the card",
                    get = { record().dnr },
                    set = { value -> save { it.copy(dnr = value) } },
                ),
                Switch(
                    "Organ donor", "Also shown on the card",
                    get = { record().organDonor },
                    set = { value -> save { it.copy(organDonor = value) } },
                ),
                Text(
                    "Notes for a first responder", "Anything else that matters in the first minute",
                    get = { record().notes },
                    set = { value -> save { it.copy(notes = value) } },
                    multiline = true,
                ),
                Action("Emergency contacts", "Who to call", Target.EMERGENCY_CONTACTS, Reach.PHONE_ACTS),
            ),
        )
    }

    private fun stremio() = Section(
        "Stremio",
        listOf(
            Action("Stremio repositories", "Where add-ons come from", Target.STREMIO_REPOSITORIES),
            Action("Stremio add-ons", "What is installed", Target.STREMIO_ADDONS),
        ),
    )
}
