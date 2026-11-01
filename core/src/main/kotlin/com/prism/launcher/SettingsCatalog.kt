package com.prism.launcher

import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.AiBackend
import com.prism.launcher.mouse.MouseGlow
import com.prism.launcher.stremio.StremioPlaybackSettings

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

        /**
         * Whether the row can be used right now. Distinct from [relevant]: a hidden row has no
         * meaning at all, while a disabled row has a meaning that needs something else first. The
         * inference-mode rows are the first of the second kind -- they exist without a local model,
         * and say so, rather than disappearing from a settings screen the user is searching.
         */
        val enabled: () -> Boolean get() = { true }
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
        override val enabled: () -> Boolean = { true },
        /**
         * A confirmation the host must show before storing [values] entry. Returning null means
         * the value is safe to store immediately. The phone uses this for forced diffusion; the
         * desktop renders it as a modal, so the two hosts cannot disagree about the warning.
         */
        val warning: ((String) -> String?)? = null,
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
        override val enabled: () -> Boolean = { true },
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
        VIEW_LOCAL_ADDRESS, START_VPN_SERVER, STOP_VPN_SERVER, MANAGE_PRISM_NODES, MANAGE_PRISM_SERVERS,
        EXTERNAL_VPN_PROFILE, APP_WHITELIST, COPY_WIREGUARD_CONFIG, OPEN_TRUSTED_DEVICES,
        OPEN_ACCESS_POINT, MANAGE_DNS_RECORDS, MANAGE_WEB_HOSTING, OPEN_DIAGNOSTICS,
        OPEN_MODELS, OPEN_CLOUD_MODELS, RESCAN_OLLAMA, OPEN_LOCAL_MODEL, OPEN_SPEECH_MODEL, OPEN_KOKORO,
        OPEN_IMAGE_MODELS, OPEN_NORA, OPEN_AETHER, OPEN_CAKECHAT, AUTO_CAPTION_FOLDER,
        OPEN_PRISM_SWAP, OPEN_DATASETS, OPEN_WALLET, OPEN_MINING, OPEN_WRITER, WRITER_DICTIONARY,
        SELECT_CUSTOM_FONT, SUMMARY_STATUS, SELECT_ISO, OPEN_TOUR, OPEN_LOCK_SCREEN,
        EMERGENCY_CONTACTS, STREMIO_REPOSITORIES, STREMIO_ADDONS, WIRELESS_DISPLAYS,
        OPEN_MOUSE_COLOR_STYLE_EDITOR, OPEN_MOUSE_FLOW_EDITOR, OPEN_PDB,
        MOVE_PRISM_FOLDER, SELECT_ECHO_ASSETS, RESET_ECHO_ASSETS,
        SELECT_ZOMBIES_ASSETS, RESET_ZOMBIES_ASSETS,
        OPEN_EMAIL_SERVER, OPEN_EMAIL_CLIENT, EMAIL_CHECK_PORTS, EMAIL_BACKUP,
        EMAIL_CLEAR_LOG, EMAIL_REPUTATION,
        UPDATE_REPOSITORIES, CHECK_FOR_UPDATES, AUTO_SELECT_INFERENCE_MODE,
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
                "Visual Intelligence (Diffusion)", "AI Images", "Nora (Brain-Based Generation)",
                "Aether (Second Brain-Based AI)", "Response Behavior",
            ),
        ),
        Group("Wallet & Mining", "Mining, selling models and node hosting", listOf("Wallet Mining", "Selling Models")),
        Group("Virtualization", "Run a guest OS inside Prism", listOf("OS Virtualization")),
        Group("Keyboard", "Prism Writer", listOf("Prism Writer (Keyboard)")),
        // A group of its own rather than a row in Launcher Aesthetic: the Mouse page is a control
        // surface, not a launcher pattern, and its colour editor and script editor are full screens.
        Group("Mouse", "RGB glow styles, flows and custom effects", listOf("Mouse")),
        Group("Personal", "Lock screen, medical card and getting started", listOf("Medical", "Getting started")),
        Group("Media", "Stremio", listOf("Stremio")),
        Group(
            "Email",
            "The mail server this device serves, and the client that reads it",
            listOf("Email"),
        ),
        Group(
            "Storage",
            "Where Prism keeps imported models, datasets, editor projects and generated files",
            listOf("Storage"),
        ),
        Group(
            "Minigames",
            "Cabinet defaults and the optional Echo Chamber asset overrides",
            listOf("Minigames"),
        ),
        // AN EXPLICIT "Other" RATHER THAN LEAVING IT TO THE FALLBACK. A section named in no group
        // lands there anyway, but then nothing can SAY that it belongs there on purpose -- and a
        // display is a local-network feature that is neither a launcher preference nor a privacy
        // switch, so "Other" is the honest place for it rather than a group that half fits.
        Group("Other", "Displays and anything that does not fit the other groups", listOf("Display")),
        // A group of its own rather than another row under Trusted devices: the bridge needs
        // trust first, but it is a shell and not a sharing permission. Keeping it separate is
        // also what makes it discoverable when something has gone wrong.
        Group("System Update", "Update repositories and installing a newer Prism build", listOf("System Update")),
        Group("Developer options", "Prism Debug Bridge and developer tooling", listOf("Developer options")),
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
        diffusion(), aiImages(), nora(), aether(), responseBehavior(), trustedDevices(), blocklist(),
        nameSystem(), typography(), writer(), mining(), sellingModels(), virtualization(),
        gettingStarted(), medical(), stremio(), email(), systemUpdate(), storage(), display(), mouse(), minigames(), developer(),
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

    /**
     * The Mouse page's light and the editors for it.
     *
     * THE CHOICES ARE DERIVED, NOT HARD-CODED. A custom style or flow the user creates appears
     * here as soon as the list is next built, without a second registration step. `selectedStyle()`
     * falls back to a shipped entry if the stored id was deleted, so this row can never render with
     * nothing highlighted.
     */
    private fun mouse(): Section {
        val styles = MouseGlow.styles()
        val flows = MouseGlow.flows()
        return Section(
            "Mouse",
            listOf(
                Switch(
                    "Touchpad view",
                    "Use a glowing touchpad instead of the mouse body",
                    get = { PrismSettings.getMouseTouchpad() },
                    set = { PrismSettings.setMouseTouchpad(it) },
                ),
                Choice(
                    "Color style",
                    "The palette of light that flows around the mouse, its buttons and its wheel",
                    labels = styles.map { it.name },
                    values = styles.map { it.id },
                    get = { MouseGlow.selectedStyle().id },
                    set = { PrismSettings.setMouseColorStyle(it) },
                    reach = Reach.PHONE_ACTS,
                ),
                Choice(
                    "Color flow",
                    "How the palette moves: liquid, pop, lava-lamp bubbles or a flow you scripted",
                    labels = flows.map { it.name },
                    values = flows.map { it.id },
                    get = { MouseGlow.selectedFlow().id },
                    set = { PrismSettings.setMouseColorFlow(it) },
                    reach = Reach.PHONE_ACTS,
                ),
                Action(
                    "Create a color style",
                    "Choose any number of colours and name the palette",
                    Target.OPEN_MOUSE_COLOR_STYLE_EDITOR,
                    reach = Reach.PHONE_ACTS,
                ),
                Action(
                    "Create a color flow",
                    "Write a Prism flow script to move the light your own way",
                    Target.OPEN_MOUSE_FLOW_EDITOR,
                    reach = Reach.PHONE_ACTS,
                ),
            ),
        )
    }

    /** Named by no group, so it lands under Other -- the same place the phone puts it. */
    private fun display() = Section(
        "Display",
        listOf(
            // The Other section's one interface-wide choice. It is a display question before it
            // is a launcher question -- how much of the screen a page is allowed to use -- and it
            // is the same switch on both builds.
            Switch(
                "Windowed pages",
                "Draw each page as a titled window with the desktop around it, on both the phone " +
                    "and the desktop",
                get = { PrismSettings.getWindowedPages() },
                set = { PrismSettings.setWindowedPages(it) },
            ),
            // THE PHOTO BACKUP SWITCH. Under Other because it is neither a launcher preference
            // nor a network one: it copies the camera roll, encrypted with the wallet's photo key,
            // to this device's vault and to every trusted device on the mesh. Off by default --
            // nothing about this should happen because an app was installed.
            Switch(
                "Back up photos & videos",
                "Keep a compressed, encrypted copy of every photo and video on this device, and " +
                    "send it to every trusted device on your meshnet. Sealed with your wallet's " +
                    "photo key, so only your devices can open it.",
                get = { PrismSettings.getPhotoBackup() },
                set = { PrismSettings.setPhotoBackup(it) },
            ),
            Action(
                "Wireless displays",
                "Mirror this screen to another Prism device, or to a TV or monitor this system " +
                    "can present to",
                Target.WIRELESS_DISPLAYS,
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
            Action(
                "Start VPN server",
                "Starts the Prism VPN server, or restarts it if it is already running",
                Target.START_VPN_SERVER,
                relevant = prismVpn,
            ),
            Action(
                "Stop VPN server",
                "Stops the Prism VPN server",
                Target.STOP_VPN_SERVER,
                relevant = prismVpn,
            ),
            Choice(
                "Prism VPN role",
                "Serve, connect, bridge several fleet servers, or relay for the mesh",
                labels = listOf("Client", "Server", "Bridge", "Relay"),
                values = listOf(
                    PrismSettings.PRISM_ROLE_CLIENT,
                    PrismSettings.PRISM_ROLE_SERVER,
                    PrismSettings.PRISM_ROLE_BRIDGE,
                    PrismSettings.PRISM_ROLE_RELAY,
                ),
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
                "Manage Prism nodes", "Connected nodes on this Prism server",
                Target.MANAGE_PRISM_NODES,
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
            // FIRST, deliberately: this changes how every local answer is produced, and a user
            // looking for it should not have to scroll past the model picker to find it.
            Choice(
                "Inference mode",
                "How local generation spends model passes. Forced diffusion is experimental; the " +
                    "parallel modes trade sampling variety for speed.",
                labels = com.prism.launcher.messaging.InferenceMode.selectable.map { it.label },
                values = com.prism.launcher.messaging.InferenceMode.selectable.map { it.id },
                get = { PrismSettings.getInferenceMode() },
                set = { PrismSettings.setInferenceMode(it) },
                enabled = { PrismSettings.hasActiveLocalModel() },
                warning = { value ->
                    if (value == com.prism.launcher.messaging.InferenceMode.FORCED_DIFFUSION.id) {
                        "Forced diffusion applies diffusion-style parallel denoising to a model " +
                            "that was not trained for it. Replies are greedy, can be less coherent, " +
                            "and can repeat themselves.\n\nEnable it anyway?"
                    } else {
                        null
                    }
                },
            ),
            Action(
                "Select the fastest inference mode",
                "Runs a short smoke test on the active local model and keeps whichever mode " +
                    "measured the highest tokens per second.",
                Target.AUTO_SELECT_INFERENCE_MODE,
                enabled = { PrismSettings.hasActiveLocalModel() },
            ),
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
            // The phone's Kokoro row, labels and order. Greyed out on the phone while an imported
            // speech model is doing the talking; the desktop says why instead, because a catalog
            // action answers with a sentence rather than a disabled click.
            Action("Kokoro-82M Voice & Speech", "Voice, cloning, speed, the model", Target.OPEN_KOKORO),
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

    /**
     * The AI Images section: what ImageCraft draws at.
     *
     * A SECTION OF ITS OWN rather than a row under Diffusion, because the two answer different
     * questions: Diffusion is where an image model comes FROM, and this is how the page that uses one
     * behaves. The row is a list of sizes rather than a pair of number fields because the useful range
     * is narrow -- a phone renders hundreds of pixels, not thousands -- and a slider between 64 and
     * 4096 would spend most of its travel on sizes no checkpoint was trained for.
     */
    private fun aiImages() = Section(
        "AI Images",
        listOf(
            Choice(
                "Image resolution",
                "What ImageCraft asks the image model for. A checkpoint trained at 512 is clamped to " +
                    "what it can actually draw, and says so.",
                labels = com.prism.launcher.messaging.ImageSize.CHOICES.map { it.label },
                values = com.prism.launcher.messaging.ImageSize.CHOICES.map { it.stored },
                get = { PrismSettings.getImageCraftSize().stored },
                set = {
                    PrismSettings.setImageCraftSize(
                        com.prism.launcher.messaging.ImageSize.parse(it),
                    )
                },
            ),
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
                "AI backend",
                // THE ACCELERATOR QUESTION, ANSWERED WITH THE DEVICE IN FRONT OF THE USER. The row's
                // description ends with what this machine's NPU actually is and which formats it can
                // take, so choosing NPU is an informed act rather than a third unexplained option.
                "Which processor runs the model. " + AiBackend.describe(),
                labels = listOf("CPU", "GPU", "NPU"),
                values = listOf("0", "1", "2"),
                get = { PrismSettings.getAiBackend().toString() },
                set = { PrismSettings.setAiBackend(it.toIntOrNull() ?: 0) },
            ),
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

    /**
     * Developer options.
     *
     * The bridge is the first thing here, and deliberately the only thing for now: a screen full
     * of switches somebody has to trust before it has a working terminal is worse than one honest
     * action that opens the device list.
     */
    private fun developer() = Section(
        "Developer options",
        listOf(
            Action(
                "Prism Debug Bridge",
                "Run a shell on a trusted device over the mesh. No USB or wireless debugging needed.",
                Target.OPEN_PDB,
            ),
            // PRISM SWAP LIVES HERE NOW. It is not a preference a normal user should be tuning:
            // it is the file the launcher pages offload into and the models page out of, and its
            // size slider is the kind of thing that turns a working device into a slow one.
            Action(
                "Prism Swap",
                "One shared swap file for models, brains, frame scratch and offloaded pages. On by default.",
                Target.OPEN_PRISM_SWAP,
            ),
            Switch(
                "Offload inactive pages",
                "Pages that are not current and have no background work are written to Prism " +
                    "Swap and released from memory. Costs a short reload when you swipe back.",
                get = { PrismSettings.getPageOffloadEnabled() },
                set = { PrismSettings.setPageOffloadEnabled(it) },
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

    /**
     * The email stack. One section for both halves, because they are one account: the client logs
     * in to the server this device serves with the same Lyke name and password.
     *
     * PORTS ARE NOT A FREE CHOICE ON THE INTERNET, so the fixed ones are the defaults and the
     * mesh-only fields are labelled as such. A user who changes the internet port to 2525 will
     * find no other mail server talking to it, which the status row says when the bind succeeds
     * but nothing arrives.
     */
    private fun email(): Section {
        fun config() = runCatching { com.prism.launcher.email.EmailSettings.load() }
            .getOrElse { com.prism.launcher.email.EmailConfig() }
        fun edit(block: (com.prism.launcher.email.EmailConfig) -> com.prism.launcher.email.EmailConfig) {
            val next = block(config())
            next.save()
        }
        return Section(
            "Email",
            listOf(
                Switch(
                    "Email server",
                    "Serve inbound mail, submission and IMAP from this device",
                    get = { config().enabled },
                    set = { on -> edit { it.copy(enabled = on) } },
                ),
                Choice(
                    "Network",
                    "Auto follows the mesh; internet always uses 25, 587 and 993",
                    labels = listOf("Auto", "Internet only", "Meshnet only"),
                    values = com.prism.launcher.email.EmailConfig.Mode.entries.map { it.name },
                    get = { config().mode.name },
                    set = { edit { c -> c.copy(mode = com.prism.launcher.email.EmailConfig.Mode.valueOf(it)) } },
                ),
                Text(
                    "Mesh domain",
                    "The mail name this device claims in the P2P DNS, e.g. prism.com",
                    get = { config().domain },
                    set = { raw ->
                        val next = config().copy(domain = raw.lowercase().trim().trimEnd('.'))
                        next.save()
                        if (next.enabled) com.prism.launcher.email.EmailSettings.publishMeshDomain(next.domain)
                    },
                ),
                Text(
                    "Inbound SMTP port", "Internet mail always arrives on 25",
                    get = { config().inboundPort.toString() },
                    set = { edit { c -> c.copy(inboundPort = it.toIntOrNull() ?: c.inboundPort) } },
                    numeric = true,
                ),
                Text(
                    "Submission port", "Internet submission always uses 587",
                    get = { config().submissionPort.toString() },
                    set = { edit { c -> c.copy(submissionPort = it.toIntOrNull() ?: c.submissionPort) } },
                    numeric = true,
                ),
                Text(
                    "IMAP port", "TLS-only; internet IMAP always uses 993",
                    get = { config().imapPort.toString() },
                    set = { edit { c -> c.copy(imapPort = it.toIntOrNull() ?: c.imapPort) } },
                    numeric = true,
                ),
                Text(
                    "Mesh inbound port", "What the server listens on when the meshnet is in use",
                    get = { config().meshInboundPort.toString() },
                    set = { edit { c -> c.copy(meshInboundPort = it.toIntOrNull() ?: c.meshInboundPort) } },
                    numeric = true,
                ),
                Text(
                    "Mesh submission port", "Mesh-only override for 587",
                    get = { config().meshSubmissionPort.toString() },
                    set = { edit { c -> c.copy(meshSubmissionPort = it.toIntOrNull() ?: c.meshSubmissionPort) } },
                    numeric = true,
                ),
                Text(
                    "Mesh IMAP port", "Mesh-only override for 993",
                    get = { config().meshImapPort.toString() },
                    set = { edit { c -> c.copy(meshImapPort = it.toIntOrNull() ?: c.meshImapPort) } },
                    numeric = true,
                ),
                Switch(
                    "Require TLS", "Refuse to authenticate or send over a plaintext session",
                    get = { config().requireTls },
                    set = { on -> edit { it.copy(requireTls = on) } },
                ),
                Switch(
                    "Accept external mail", "Take mail from servers outside this meshnet",
                    get = { config().acceptExternalMail },
                    set = { on -> edit { it.copy(acceptExternalMail = on) } },
                ),
                Switch(
                    "Relay outgoing mail", "Hand submission to a smart host instead of delivering directly",
                    get = { config().allowRelay },
                    set = { on -> edit { it.copy(allowRelay = on) } },
                ),
                Text(
                    "Relay host", "The smart host that sends on your behalf",
                    get = { config().relayHost },
                    set = { edit { c -> c.copy(relayHost = it.trim()) } },
                    relevant = { config().allowRelay },
                ),
                Text(
                    "Relay port", "Usually 587 with STARTTLS",
                    get = { config().relayPort.toString() },
                    set = { edit { c -> c.copy(relayPort = it.toIntOrNull() ?: c.relayPort) } },
                    numeric = true,
                    relevant = { config().allowRelay },
                ),
                Text(
                    "Relay username", "The smart host account",
                    get = { config().relayUsername },
                    set = { edit { c -> c.copy(relayUsername = it.trim()) } },
                    relevant = { config().allowRelay },
                ),
                Text(
                    "Relay password", "Stored with the rest of the mail settings",
                    get = { config().relayPassword },
                    set = { edit { c -> c.copy(relayPassword = it) } },
                    secret = true,
                    relevant = { config().allowRelay },
                ),
                Switch(
                    "Spam filtering", "Score every incoming message and sort what scores too high",
                    get = { config().spamFilter },
                    set = { on -> edit { it.copy(spamFilter = on) } },
                ),
                Text(
                    "Spam threshold", "Higher lets more through",
                    get = { config().spamThreshold.toString() },
                    set = { edit { c -> c.copy(spamThreshold = it.toIntOrNull() ?: c.spamThreshold) } },
                    numeric = true,
                    relevant = { config().spamFilter },
                ),
                Switch(
                    "Malware filtering", "Quarantine executables and dangerous attachments",
                    get = { config().malwareFilter },
                    set = { on -> edit { it.copy(malwareFilter = on) } },
                ),
                Switch(
                    "Blocklist recovery", "Check sending IPs against blocklists and explain how to delist",
                    get = { config().blocklistRecovery },
                    set = { on -> edit { it.copy(blocklistRecovery = on) } },
                ),
                Switch(
                    "Backups", "Keep a copy of the mailbox on a schedule",
                    get = { config().backups },
                    set = { on -> edit { it.copy(backups = on) } },
                ),
                Text(
                    "Backup interval", "Hours between backups",
                    get = { config().backupEveryHours.toString() },
                    set = { edit { c -> c.copy(backupEveryHours = it.toIntOrNull() ?: c.backupEveryHours) } },
                    numeric = true,
                    relevant = { config().backups },
                ),
                Text(
                    "Retention", "Days a message is kept before pruning",
                    get = { config().retentionDays.toString() },
                    set = { edit { c -> c.copy(retentionDays = it.toIntOrNull() ?: c.retentionDays) } },
                    numeric = true,
                ),
                Text(
                    "Largest message", "Megabytes; bigger mail is refused before it is read",
                    get = { (config().maxMessageBytes / (1024 * 1024)).toString() },
                    set = { raw ->
                        val mb = raw.toLongOrNull()?.coerceIn(1, 200) ?: 25
                        edit { it.copy(maxMessageBytes = mb * 1024 * 1024) }
                    },
                    numeric = true,
                ),
                Text(
                    "Abuse contact", "The mailbox complaints are sent to",
                    get = { config().abuseContact },
                    set = { edit { c -> c.copy(abuseContact = it.trim()) } },
                ),
                Text(
                    "Privacy contact", "The mailbox data-protection requests are sent to",
                    get = { config().privacyContact },
                    set = { edit { c -> c.copy(privacyContact = it.trim()) } },
                ),
                Choice(
                    "DMARC policy", "What receivers do with mail that fails both SPF and DKIM",
                    labels = listOf("None (monitor)", "Quarantine", "Reject"),
                    values = listOf("none", "quarantine", "reject"),
                    get = { config().dmarcPolicy },
                    set = { edit { c -> c.copy(dmarcPolicy = it) } },
                ),
                Text(
                    "DKIM selector", "The name the public key is published under",
                    get = { config().dkimSelector },
                    set = { edit { c -> c.copy(dkimSelector = it.trim().ifBlank { "prism" }) } },
                ),
                Action("Email server dashboard", "Services, log, certificates and DNS records", Target.OPEN_EMAIL_SERVER),
                Action("Email client", "Inbox, folders, filters, accounts and text alerts", Target.OPEN_EMAIL_CLIENT),
                Action("Check mail ports", "Whether 25, 587 and 993 can be bound on this network", Target.EMAIL_CHECK_PORTS),
                Action("Back up the mailbox now", "Write a copy into the email backups folder", Target.EMAIL_BACKUP),
                Action("Refresh IP reputation", "Ask the blocklists about this public address", Target.EMAIL_REPUTATION),
                Action("Clear the server log", "Remove what the dashboard is showing", Target.EMAIL_CLEAR_LOG),
            ),
        )
    }

    /**
     * Echo Chamber's knobs.
     *
     * A section rather than rows on the Games page because they are defaults for every match, and
     * because the asset folder is the kind of thing somebody configures once and expects to find in
     * Settings rather than in a game's start screen.
     */
    private fun minigames() = Section(
        "Minigames",
        listOf(
            Switch(
                "Use custom Echo Chamber assets",
                "Replace the generated 1024² textures and 3D meshes with the folder below",
                get = { PrismSettings.getEchoChamberUseCustomAssets() },
                set = { PrismSettings.setEchoChamberUseCustomAssets(it) },
            ),
            Action(
                "Choose Echo Chamber assets folder",
                "wall/floor/ceiling/enemy/exit .png and enemy/exit .obj; any file may be left out",
                Target.SELECT_ECHO_ASSETS,
            ),
            Text(
                "Echo Chamber assets folder",
                "Where the optional custom textures and models live",
                get = { PrismSettings.getEchoChamberAssetPath() },
                set = { PrismSettings.setEchoChamberAssetPath(it.trim()) },
            ),
            Action(
                "Reset Echo Chamber assets",
                "Forget the custom folder and use the generated pack",
                Target.RESET_ECHO_ASSETS,
            ),
            Choice(
                "Echo Chamber difficulty",
                "How fast and how persistent the hunters are",
                labels = listOf("Shadow", "Hunter", "Nightmare"),
                values = listOf("0", "1", "2"),
                get = { PrismSettings.getEchoChamberDifficulty().toString() },
                set = { PrismSettings.setEchoChamberDifficulty(it.toIntOrNull() ?: 1) },
            ),
            Text(
                "Echo Chamber hunters",
                "How many enemies share the maze (1–8)",
                get = { PrismSettings.getEchoChamberEnemies().toString() },
                set = { PrismSettings.setEchoChamberEnemies(it.toIntOrNull() ?: 3) },
                numeric = true,
            ),
            // ── Zombies ──────────────────────────────────────────────────────
            // A mesh host supplies the whole lobby's art. The folder mirrors the generated
            // pack: maps/, models/, textures/ and sounds/. Any missing file falls back to the
            // built-in generated one, so a host can replace one gun texture without shipping a
            // complete game.
            Switch(
                "Use custom Zombies assets",
                "Replace the generated maps, meshes, 1024² textures and sounds with the folder below",
                get = { PrismSettings.getZombiesUseCustomAssets() },
                set = { PrismSettings.setZombiesUseCustomAssets(it) },
            ),
            Action(
                "Choose Zombies assets folder",
                "maps/, models/, textures/, sounds/ — any file may be left out",
                Target.SELECT_ZOMBIES_ASSETS,
            ),
            Text(
                "Zombies assets folder",
                "Where the optional baseline maps, meshes, textures and sounds live",
                get = { PrismSettings.getZombiesAssetPath() },
                set = { PrismSettings.setZombiesAssetPath(it.trim()) },
            ),
            Action(
                "Reset Zombies assets",
                "Forget the custom folder and use the generated baseline pack",
                Target.RESET_ZOMBIES_ASSETS,
            ),
            Choice(
                "Echo Chamber microphone sensitivity",
                "How loud a sound has to be before it becomes a shout",
                labels = listOf("Whisper friendly", "Normal", "Loud room"),
                values = listOf("0.028", "0.055", "0.10"),
                get = {
                    val current = PrismSettings.getEchoChamberMicSensitivity()
                    when {
                        current < 0.04f -> "0.028"
                        current > 0.08f -> "0.10"
                        else -> "0.055"
                    }
                },
                set = { PrismSettings.setEchoChamberMicSensitivity(it.toFloatOrNull() ?: 0.055f) },
            ),
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

    /**
     * Prism's own update channels.
     *
     * A section of its own rather than rows under About: a failed update is a security event, and
     * the repository list is the user's own trust boundary. Burying it under an informational screen
     * would make it look like a build detail rather than something that decides what gets installed.
     */
    private fun systemUpdate() = Section(
        "System Update",
        listOf(
            Action(
                "Update repositories",
                "The GitHub repositories Prism may read releases from, including forks",
                Target.UPDATE_REPOSITORIES,
            ),
            Action(
                "Check for updates",
                "Read every repository, quarantine the release, then install it only if it passes",
                Target.CHECK_FOR_UPDATES,
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
            Choice(
                "Debrid account",
                "A service that downloads a torrent for you and serves it as a direct link",
                labels = StremioPlaybackSettings.PROVIDERS.map { StremioPlaybackSettings.label(it) },
                values = StremioPlaybackSettings.PROVIDERS,
                get = { StremioPlaybackSettings.provider() },
                set = { StremioPlaybackSettings.setProvider(it) },
            ),
            Text(
                "Debrid API key",
                "Sent only to the provider above; it is the key from your account's dashboard",
                get = { StremioPlaybackSettings.token() },
                set = { StremioPlaybackSettings.setToken(it) },
                secret = true,
            ),
            Switch(
                "Play torrents",
                "Use Prism's own torrent client when no debrid account is configured",
                get = { StremioPlaybackSettings.torrentsEnabled() },
                set = { StremioPlaybackSettings.setTorrentsEnabled(it) },
            ),
        ),
    )

    /**
     * The one folder every feature writes user data into.
     *
     * A folder, not a path box. A typed path can point at a directory that does not exist, is not
     * writable, or is inside the current one; a picker can only return a directory that exists, and
     * the move itself refuses the nested and non-empty cases with an explanation.
     */
    private fun storage() = Section(
        "Storage",
        listOf(
            Info(
                "Prism data folder",
                "Imported models, datasets, editor projects, generated video and audio",
                value = { PrismPlatform.host.documentsDir().absolutePath },
            ),
            Action(
                "Move Prism data folder",
                "Copy everything to another folder, switch to it, then remove the old copy",
                Target.MOVE_PRISM_FOLDER,
            ),
        ),
    )
}
