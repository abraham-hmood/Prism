package com.prism.launcher.appstore

/**
 * The App Store's catalogue: real applications, where they come from, and how to get them.
 *
 * ## Curated, not scraped at startup
 *
 * Prism has no server that could index four stores on the user's behalf, and scraping F-Droid,
 * Debian, OpenStore and Komi every time the page opens would be slow, rate-limited and impossible
 * offline. So the shipped catalogue is a hand-picked set of well-known packages, each carrying the
 * official listing and, where the platform has one, the exact install command. It is deliberately
 * smaller than any of those stores: an App Store that lists everything is a browser, and the point
 * here is a useful starting shelf.
 *
 * ## What "verified" means
 *
 * It means the entry was checked to exist in the source it names when the catalogue was written.
 * It does not mean Prism audited the binary. The UI says which source an app comes from and links
 * to that source's own page; the trust decision belongs to the store that built and signed it.
 */
object AppStoreCatalog {

    enum class Category(val label: String, val subtitle: String) {
        APPS("Apps", "Everyday applications for Windows, Linux and Android"),
        GAMES("Games", "Curated games that run on a phone, a desktop or both"),
        TOOLS("Tools", "Calculators, browsers, editors and utilities"),
        PUBLISHER("Publisher", "Publish your own app to this meshnet"),
    }

    enum class Platform(val label: String) {
        ANDROID("Android"),
        WINDOWS("Windows"),
        LINUX("Linux"),
    }

    enum class Source(val label: String, val note: String) {
        F_DROID("F-Droid", "Free and open-source Android apps, built from source"),
        APT("APT", "Debian and Ubuntu packages"),
        KOMI("Komi Store", "Open-source releases published on GitHub and other forges"),
        OPEN_STORE("OpenStore", "Ubuntu Touch applications"),
    }

    data class Entry(
        val id: String,
        val name: String,
        val tagline: String,
        val category: Category,
        val platform: Platform,
        val source: Source,
        val listingUrl: String,
        val installHint: String = "",
        val packageName: String = "",
        val verified: Boolean = true,
    ) {
        fun haystack(): String =
            "$name $tagline ${source.label} ${platform.label} ${category.label} $packageName".lowercase()
    }

    private fun app(
        id: String,
        name: String,
        tagline: String,
        platform: Platform,
        source: Source,
        url: String,
        hint: String = "",
        packageName: String = "",
    ) = Entry(id, name, tagline, Category.APPS, platform, source, url, hint, packageName)

    private fun game(
        id: String,
        name: String,
        tagline: String,
        platform: Platform,
        source: Source,
        url: String,
        hint: String = "",
        packageName: String = "",
    ) = Entry(id, name, tagline, Category.GAMES, platform, source, url, hint, packageName)

    private fun tool(
        id: String,
        name: String,
        tagline: String,
        platform: Platform,
        source: Source,
        url: String,
        hint: String = "",
        packageName: String = "",
    ) = Entry(id, name, tagline, Category.TOOLS, platform, source, url, hint, packageName)

    private fun fdroid(
        id: String,
        name: String,
        tagline: String,
        category: Category,
        packageName: String,
    ): Entry {
        val base = Entry(
            id = id,
            name = name,
            tagline = tagline,
            category = category,
            platform = Platform.ANDROID,
            source = Source.F_DROID,
            listingUrl = "https://f-droid.org/packages/$packageName/",
            installHint = "Open the F-Droid listing, or add F-Droid and install it from there.",
            packageName = packageName,
        )
        return base
    }

    private fun apt(
        id: String,
        name: String,
        tagline: String,
        category: Category,
        packageName: String,
    ) = Entry(
        id = id,
        name = name,
        tagline = tagline,
        category = category,
        platform = Platform.LINUX,
        source = Source.APT,
        listingUrl = "https://packages.ubuntu.com/search?keywords=$packageName",
        installHint = "sudo apt install $packageName",
        packageName = packageName,
    )

    private fun winget(
        id: String,
        name: String,
        tagline: String,
        category: Category,
        packageId: String,
        website: String,
    ) = Entry(
        id = id,
        name = name,
        tagline = tagline,
        category = category,
        platform = Platform.WINDOWS,
        source = Source.KOMI,
        listingUrl = website,
        installHint = "winget install --id $packageId -e",
        packageName = packageId,
    )

    private fun openStore(
        id: String,
        name: String,
        tagline: String,
        category: Category,
    ) = Entry(
        id = id,
        name = name,
        tagline = tagline,
        category = category,
        platform = Platform.LINUX,
        source = Source.OPEN_STORE,
        listingUrl = "https://open-store.io/app/$id",
        installHint = "Open the OpenStore listing on an Ubuntu Touch device.",
        packageName = id,
    )

    // ── Android ────────────────────────────────────────────────────────────

    private val androidApps = listOf(
        fdroid("fdroid", "F-Droid", "The free-software app store itself", Category.APPS, "org.fdroid.fdroid"),
        fdroid("newpipe", "NewPipe", "A lightweight YouTube and media front end", Category.APPS, "org.schabi.newpipe"),
        fdroid("organic-maps", "Organic Maps", "Offline maps and navigation, no account", Category.APPS, "app.organicmaps"),
        fdroid("antenna-pod", "AntennaPod", "Podcast player with no feed lock-in", Category.APPS, "de.danoeh.antennapod"),
        fdroid("thunderbird", "Thunderbird", "Email, calendars and contacts", Category.APPS, "net.thunderbird.android"),
        fdroid("element", "Element", "Matrix chat, encrypted by default", Category.APPS, "im.vector.app"),
        fdroid("conversations", "Conversations", "XMPP messaging with OMEMO", Category.APPS, "eu.siacs.conversations"),
        fdroid("syncthing", "Syncthing", "Peer-to-peer file sync, no cloud", Category.APPS, "com.nutomic.syncthingandroid"),
        fdroid("wikipedia", "Wikipedia", "The whole encyclopedia, offline too", Category.APPS, "org.wikipedia"),
        fdroid("osmand", "OsmAnd", "Maps with turn-by-turn navigation", Category.APPS, "net.osmand.plus"),
        fdroid("fossify-gallery", "Fossify Gallery", "A simple, private photo gallery", Category.APPS, "org.fossify.gallery"),
        fdroid("aegis", "Aegis Authenticator", "Encrypted two-factor codes", Category.APPS, "com.beemdevelopment.aegis"),
        fdroid("keepassdx", "KeePassDX", "A KeePass database in your pocket", Category.APPS, "com.kunzisoft.keepass.libre"),
        fdroid("bitwarden", "Bitwarden", "Open-source password manager", Category.APPS, "com.x8bit.bitwarden"),
        fdroid("shattered-pixel-dungeon", "Shattered Pixel Dungeon", "A deep roguelike, one thumb", Category.GAMES, "com.shatteredpixel.shatteredpixeldungeon"),
        fdroid("mindustry", "Mindustry", "Automate a factory, defend the core", Category.GAMES, "io.anuke.mindustry"),
        fdroid("unciv", "Unciv", "Civilization-style strategy, open source", Category.GAMES, "com.unciv.app"),
        fdroid("puzzles", "Simon Tatham's Puzzles", "Forty puzzles, no ads, no permissions", Category.GAMES, "name.boyle.chris.sgtpuzzles"),
        fdroid("andors-trail", "Andor's Trail", "A long, hand-built role-playing game", Category.GAMES, "com.gpl.rpg.AndorsTrail"),
        fdroid("termux", "Termux", "A terminal and a Linux userland", Category.TOOLS, "com.termux"),
        fdroid("material-files", "Material Files", "A clean file manager with root support", Category.TOOLS, "me.zhanghai.android.files"),
        fdroid("binary-eye", "Binary Eye", "Scan and generate barcodes", Category.TOOLS, "de.markusfisch.android.binaryeye"),
        fdroid("openkeychain", "OpenKeychain", "OpenPGP keys and encryption", Category.TOOLS, "org.sufficientlysecure.keychain"),
        fdroid("fossify-calculator", "Fossify Calculator", "A calculator that does one thing", Category.TOOLS, "org.fossify.maths"),
    )

    // ── Linux ──────────────────────────────────────────────────────────────

    private val linuxApps = listOf(
        apt("firefox", "Firefox", "Mozilla's browser", Category.APPS, "firefox"),
        apt("thunderbird", "Thunderbird", "Email and calendar for the desktop", Category.APPS, "thunderbird"),
        apt("libreoffice", "LibreOffice", "Documents, sheets and slides", Category.APPS, "libreoffice"),
        apt("vlc", "VLC", "Plays nearly every format", Category.APPS, "vlc"),
        apt("gimp", "GIMP", "Raster image editing", Category.APPS, "gimp"),
        apt("inkscape", "Inkscape", "Vector drawing and SVG", Category.APPS, "inkscape"),
        apt("krita", "Krita", "Digital painting and illustration", Category.APPS, "krita"),
        apt("obs-studio", "OBS Studio", "Recording and live streaming", Category.APPS, "obs-studio"),
        apt("audacity", "Audacity", "Multi-track audio editing", Category.APPS, "audacity"),
        apt("transmission", "Transmission", "A small BitTorrent client", Category.APPS, "transmission-gtk"),
        apt("gparted", "GParted", "Partition editing with a GUI", Category.APPS, "gparted"),
        apt("mpv", "mpv", "A fast, minimal video player", Category.APPS, "mpv"),
        apt("0ad", "0 A.D.", "A free, historical real-time strategy game", Category.GAMES, "0ad"),
        apt("supertuxkart", "SuperTuxKart", "Kart racing with a penguin", Category.GAMES, "supertuxkart"),
        apt("wesnoth", "Battle for Wesnoth", "Turn-based fantasy strategy", Category.GAMES, "wesnoth"),
        apt("openra", "OpenRA", "Classic real-time strategy, rebuilt", Category.GAMES, "openra"),
        apt("xonotic", "Xonotic", "A fast arena first-person shooter", Category.GAMES, "xonotic"),
        apt("endless-sky", "Endless Sky", "Trade, explore and fight in space", Category.GAMES, "endless-sky"),
        apt("minetest", "Luanti (Minetest)", "A voxel sandbox with mods", Category.GAMES, "minetest"),
        apt("htop", "htop", "An interactive process viewer", Category.TOOLS, "htop"),
        apt("neovim", "Neovim", "A modern Vim", Category.TOOLS, "neovim"),
        apt("p7zip", "7-Zip", "Archives in every common format", Category.TOOLS, "p7zip-full"),
        apt("keepassxc", "KeePassXC", "A local password database", Category.TOOLS, "keepassxc"),
        openStore("dekko2", "Dekko 2", "Email for Ubuntu Touch", Category.APPS),
        openStore("teleports", "TELEports", "Telegram for Ubuntu Touch", Category.APPS),
        openStore("fluffychat", "FluffyChat", "Matrix chat for Ubuntu Touch", Category.APPS),
        openStore("cinny", "Cinny", "A Matrix client with a calm interface", Category.APPS),
        openStore("morph-browser", "Morph Browser", "The Ubuntu Touch web browser", Category.TOOLS),
        openStore("openstore", "OpenStore", "The Ubuntu Touch app store", Category.TOOLS),
        openStore("terminal", "Terminal", "A shell for Ubuntu Touch", Category.TOOLS),
        openStore("filemanager", "File Manager", "Browse Ubuntu Touch storage", Category.TOOLS),
        openStore("weather", "Weather", "Forecasts for Ubuntu Touch", Category.APPS),
        openStore("music", "Music", "Play local audio on Ubuntu Touch", Category.APPS),
    )

    // ── Windows ────────────────────────────────────────────────────────────

    private val windowsApps = listOf(
        Entry(
            "komi-store", "Komi Store", "An open-source store for open-source releases",
            Category.APPS, Platform.WINDOWS, Source.KOMI, "https://komistore.app/",
            "Download the installer from komistore.app.", "komi-store",
        ),
        winget("7zip", "7-Zip", "Archives, compression and extraction", Category.TOOLS, "7zip.7zip", "https://7-zip.org/"),
        winget("vlc-win", "VLC", "A player for every format", Category.APPS, "VideoLAN.VLC", "https://www.videolan.org/vlc/"),
        winget("firefox-win", "Firefox", "Mozilla's browser", Category.APPS, "Mozilla.Firefox", "https://www.mozilla.org/firefox/"),
        winget("thunderbird-win", "Thunderbird", "Email and calendar", Category.APPS, "Mozilla.Thunderbird", "https://www.thunderbird.net/"),
        winget("bitwarden-win", "Bitwarden", "Open-source password manager", Category.APPS, "Bitwarden.Bitwarden", "https://bitwarden.com/download/"),
        winget("keepassxc-win", "KeePassXC", "A local password database", Category.TOOLS, "KeePassXCTeam.KeePassXC", "https://keepassxc.org/"),
        winget("notepadpp", "Notepad++", "A text editor that stays out of the way", Category.TOOLS, "Notepad++.Notepad++", "https://notepad-plus-plus.org/"),
        winget("sharex", "ShareX", "Screenshots, recording and uploads", Category.TOOLS, "ShareX.ShareX", "https://getsharex.com/"),
        winget("powertoys", "PowerToys", "Microsoft's utilities for power users", Category.TOOLS, "Microsoft.PowerToys", "https://learn.microsoft.com/windows/powertoys/"),
        winget("signal-win", "Signal", "Private messaging and calls", Category.APPS, "OpenWhisperSystems.Signal", "https://signal.org/download/"),
        winget("obsidian", "Obsidian", "Markdown notes in a local vault", Category.APPS, "Obsidian.Obsidian", "https://obsidian.md/"),
        winget("obs-win", "OBS Studio", "Recording and live streaming", Category.APPS, "OBSProject.OBSStudio", "https://obsproject.com/"),
        winget("gimp-win", "GIMP", "Raster image editing", Category.APPS, "GIMP.GIMP", "https://www.gimp.org/"),
        winget("inkscape-win", "Inkscape", "Vector drawing", Category.APPS, "Inkscape.Inkscape", "https://inkscape.org/"),
        winget("krita-win", "Krita", "Digital painting", Category.APPS, "KDE.Krita", "https://krita.org/"),
        winget("supertuxkart-win", "SuperTuxKart", "Kart racing with a penguin", Category.GAMES, "SuperTuxKart.SuperTuxKart", "https://supertuxkart.net/"),
        winget("wesnoth-win", "Battle for Wesnoth", "Turn-based fantasy strategy", Category.GAMES, "Wesnoth.BattleForWesnoth", "https://www.wesnoth.org/"),
        winget("openra-win", "OpenRA", "Classic real-time strategy, rebuilt", Category.GAMES, "OpenRA.OpenRA", "https://www.openra.net/"),
        winget("xonotic-win", "Xonotic", "A fast arena shooter", Category.GAMES, "Xonotic.Xonotic", "https://xonotic.org/"),
    )

    val entries: List<Entry> = (androidApps + linuxApps + windowsApps)
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }

    fun byCategory(category: Category): List<Entry> =
        if (category == Category.PUBLISHER) emptyList() else entries.filter { it.category == category }

    /**
     * Searches every category, not only the selected one.
     *
     * The search field is above the side list for that reason: typing "terminal" from Apps should
     * find the Linux and Android tools rather than saying there is nothing to see.
     */
    fun search(query: String, platform: Platform? = null): List<Entry> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()
        return entries
            .asSequence()
            .filter { platform == null || it.platform == platform }
            .map { entry -> entry to rank(entry, trimmed) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first }
            .toList()
    }

    private fun rank(entry: Entry, query: String): Int {
        val name = entry.name.lowercase()
        val tagline = entry.tagline.lowercase()
        val source = entry.source.label.lowercase()
        val platform = entry.platform.label.lowercase()
        var score = 0
        if (name == query) score += 100
        if (name.startsWith(query)) score += 60
        if (name.contains(query)) score += 40
        if (tagline.contains(query)) score += 18
        if (entry.packageName.lowercase().contains(query)) score += 12
        if (source.contains(query)) score += 6
        if (platform.contains(query)) score += 6
        if (entry.category.label.lowercase().contains(query)) score += 4
        return score
    }

    fun counts(): Map<Category, Int> = Category.entries.associateWith { category ->
        if (category == Category.PUBLISHER) 0 else entries.count { it.category == category }
    }

    fun platformCounts(): Map<Platform, Int> = Platform.entries.associateWith { platform ->
        entries.count { it.platform == platform }
    }
}
