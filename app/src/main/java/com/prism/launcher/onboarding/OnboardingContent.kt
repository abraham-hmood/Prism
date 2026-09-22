package com.prism.launcher.onboarding

/**
 * Everything Prism does, written down once.
 *
 * ## Why this is data and not a sequence of screens
 *
 * Prism has grown to roughly twenty subsystems across two hundred source files, and a tour built as
 * hand-written screens would be out of date within a week of anyone adding a page. Keeping it as a
 * list means a new feature is one entry, and the overlay, the progress dots, the skip logic and the
 * per-section replay all read from the same place.
 *
 * ## The rule for writing an entry
 *
 * Say what it does and what it costs. A tour that only lists capabilities teaches someone to expect
 * things that will not happen -- that add-ons play anything, that the lock screen replaces Android's,
 * that a phone can run a 70B model. Every limitation stated here is one the user would otherwise
 * discover as a bug report.
 */
object OnboardingContent {

    /** One screen of the tour. */
    data class Step(
        val title: String,
        val body: String,
        /** Shown large above the text. One or two characters. */
        val glyph: String,
        /**
         * Where this lives, so the tour can say "Settings > Network" rather than leaving the user
         * to hunt. Empty when the feature is the page itself.
         */
        val whereToFind: String = "",
    )

    data class Section(
        val name: String,
        val steps: List<Step>,
    )

    val SECTIONS: List<Section> = listOf(

        // ── Getting oriented ────────────────────────────────────────────────
        Section("Welcome", listOf(
            Step(
                "Prism is a launcher made of pages",
                "Your home screen is a row of pages you choose. Swipe between them. Long-press an " +
                    "empty spot on any page to swap it for a different one, or to add another.\n\n" +
                    "Most of what follows is a page you can add. None of it is on by default, " +
                    "because a launcher that switched on twenty subsystems at first boot would be " +
                    "unusable and would flatten your battery.",
                "◧",
            ),
            Step(
                "The pages you can choose from",
                "Browser · App grid · App drawer · Messaging · Kinetic Halo · File Explorer · " +
                    "Nebula Social · Virtualization · Models · Model Store · Agentic Tools · " +
                    "Wallet · Editor · Science — plus any plugin page an installed app provides.",
                "▦",
                "Long-press an empty area of a page",
            ),
            Step(
                "Settings is grouped, and searchable",
                "Launcher & Appearance, Browser & Content, Network/VPN & Mesh, Intelligence & " +
                    "Messaging, Virtualization, and Other. There is a search box at the top that " +
                    "looks across all of them at once.",
                "⚙",
                "Long-press the home screen, or the drawer's menu",
            ),
        )),

        // ── The launcher itself ─────────────────────────────────────────────
        Section("Home screen", listOf(
            Step(
                "Grid, folders and the hotseat",
                "Drag icons to rearrange, drop one on another to make a folder, and drag to the " +
                    "top of the screen to remove. The hotseat row predicts what you are likely to " +
                    "open next from how you actually use the phone.",
                "⊞",
            ),
            Step(
                "Icon packs, glow and fonts",
                "Prism reads standard Android icon packs. The accent glow on borders and " +
                    "navigation is yours to choose, and so is the typeface — including a custom " +
                    "font file of your own.",
                "✦",
                "Settings > Launcher & Appearance",
            ),
            Step(
                "Kinetic Halo",
                "A page for navigating without looking: apps arranged as a physics-driven ring you " +
                    "flick through by feel. Built for one-handed and eyes-free use.",
                "◎",
                "Add it as a page",
            ),
            Step(
                "Shake to act",
                "A shake gesture is wired into the launcher for quick actions. Worth knowing it " +
                    "exists before it surprises you.",
                "⚡",
            ),
        )),

        // ── Browser ─────────────────────────────────────────────────────────
        Section("Browser", listOf(
            Step(
                "A browser with tabs, downloads and a blocklist",
                "Tabs, a download manager with notifications, bookmarks, and a domain blocklist " +
                    "you control.",
                "🌐",
                "The Browser page",
            ),
            Step(
                "Mirror a site, keep it forever",
                "Mirroring downloads a whole site to this device so it survives the original going " +
                    "away. Separately, web caching saves pages as you read them.",
                "⇩",
                "Browser menu, and Settings > Network, VPN & Mesh",
            ),
            Step(
                "Cached pages can be hosted on the meshnet",
                "With caching on and the mesh connected, pages you have read can be served to " +
                    "other Prism devices under a .cache.p2p name. They are never served under the " +
                    "real domain — a cached copy must not be able to impersonate the original.",
                "☍",
                "Settings > Network, VPN & Mesh",
            ),
            Step(
                "Prism Search",
                "Prism runs its own search engine — locally by default, and published to the mesh " +
                    "when this device is serving one, so every peer can reach it.",
                "🔍",
                "Settings, top row",
            ),
        )),

        // ── Mesh and network ────────────────────────────────────────────────
        Section("Meshnet and VPN", listOf(
            Step(
                "The Prism meshnet",
                "Prism devices find each other and form a peer-to-peer network. Serve one yourself " +
                    "or join somebody else's — over Wi-Fi, or with this phone as the hotspot. Most " +
                    "of the interesting features below need it.",
                "⛓",
                "Settings > Network, VPN & Mesh",
            ),
            Step(
                "Decentralized names",
                "The mesh has its own name system: sites hosted from a phone get a .p2p address " +
                    "that resolves for every peer, with no registrar and no DNS provider.",
                "⁂",
                "Settings > Network, VPN & Mesh",
            ),
            Step(
                "VPN, WireGuard and hotspot gateway",
                "A tunnelling VPN, a native WireGuard server you can run from the phone, an " +
                    "access-point mode with a captive portal, connected-device stats and network " +
                    "isolation, plus a private DNS service.",
                "🛡",
                "Settings > Network, VPN & Mesh",
            ),
        )),

        // ── AI ──────────────────────────────────────────────────────────────
        Section("Intelligence", listOf(
            Step(
                "Models that run on this phone",
                "Import GGUF models and run them locally through llama.cpp, with GPU offload via " +
                    "OpenCL and NPU offload on Qualcomm Hexagon where it exists.\n\n" +
                    "Be realistic about size: a phone runs models in the 1B–8B range comfortably. " +
                    "Anything larger needs the memory tricks below, or other phones.",
                "🧠",
                "The Models page",
            ),
            Step(
                "Prism Swap",
                "A memory-mapped arena that lets a model's weights, KV cache and scratch buffers " +
                    "live in a swap file instead of RAM. It is what makes a model load at all when " +
                    "the device is short — slower, but it runs.",
                "⇄",
                "Settings > Intelligence & Messaging",
            ),
            Step(
                "Quantize models yourself",
                "The Quant tools re-quantize a GGUF on the device, including Prism's own Q5_Q0 " +
                    "type, to trade quality for the memory you actually have.",
                "▤",
                "The Models page",
            ),
            Step(
                "Model Store, and selling your own",
                "Browse and download models, or list one for sale on the mesh paid in PrismCoin — " +
                    "with listing verification, a purchase ledger and refunds.",
                "🏷",
                "The Model Store page",
            ),
            Step(
                "Nora and Aether",
                "Two brain-based engines that are not transformers: Nora does predictive-coding " +
                    "generation, Aether is a second brain-based model with its own connectome that " +
                    "syncs over the mesh. Both have their own training screens and can be trained " +
                    "on this device.",
                "◉",
                "Settings > Intelligence & Messaging",
            ),
            Step(
                "CakeChat and Characters",
                "A separately trainable conversational engine, and a character system where you " +
                    "write a persona and talk to it.",
                "☺",
                "Messaging page menu",
            ),
            Step(
                "Agentic tools",
                "The local model can call tools — images, messaging, peer-to-peer requests, and a " +
                    "search over your own history. You can define the tool-calling syntax it uses.",
                "⚒",
                "The Agentic Tools page",
            ),
            Step(
                "Image generation and vision",
                "On-device diffusion for images, and a vision service for understanding pictures " +
                    "you send it.",
                "🖼",
                "Settings > Intelligence & Messaging",
            ),
            Step(
                "Your personal history",
                "Pages read, videos watched, messages, files — indexed on the device and " +
                    "searchable by the local model. It never leaves the phone, and it can be " +
                    "switched off or cleared.",
                "⏱",
                "Settings > Intelligence & Messaging",
            ),
        )),

        // ── Mesh compute ────────────────────────────────────────────────────
        Section("Borrowing other phones", listOf(
            Step(
                "One model across several phones",
                "A model too large for this device can run with its layers spread across peers on " +
                    "the mesh. The weights are uploaded to them at load time — a peer lends memory " +
                    "and arithmetic and never needs the model file.",
                "⊹",
                "Wallet page > Compute tab",
            ),
            Step(
                "The compute market",
                "Every device advertises its RAM, accelerator memory, swap, cores, clock and " +
                    "whether it has an NPU, and a price is derived from that — nobody sets their " +
                    "own. Tap one device to use it, long-press to pick several and split a model " +
                    "across them.",
                "⚖",
                "Wallet page > Compute tab",
            ),
            Step(
                "Paying later",
                "If your balance is short, the work still runs and the amount is recorded and " +
                    "announced to the mesh, then settled when you have the coin. It is a " +
                    "reputation system rather than escrow — a chain without scripts cannot enforce " +
                    "more than that.",
                "✍",
                "Wallet page > Compute tab",
            ),
        )),

        // ── Social ──────────────────────────────────────────────────────────
        Section("Nebula and Lyke", listOf(
            Step(
                "Nebula Social",
                "A social graph that runs on the device, with AI personas that post and reply, " +
                    "direct messages and a compose screen. It syncs across the mesh.",
                "✺",
                "The Nebula Social page",
            ),
            Step(
                "Lyke",
                "Short video inside Nebula. Record or upload, add a description, and it is shared " +
                    "to the mesh. Leaving Prism mid-video floats it into a picture-in-picture " +
                    "window you can move and resize.",
                "▶",
                "Nebula Social > section switcher",
            ),
            Step(
                "Search across Lyke and your add-ons",
                "The magnifier searches video descriptions on Lyke and the mesh, and titles across " +
                    "any Stremio add-ons you have installed, in one list.",
                "🔎",
                "Nebula Social > top bar",
            ),
        )),

        // ── Stremio ─────────────────────────────────────────────────────────
        Section("Stremio add-ons", listOf(
            Step(
                "Add-ons are just web addresses",
                "A Stremio add-on is an HTTP server with a manifest. Nothing is downloaded but " +
                    "JSON and nothing executes on your phone. There is no account and no sign-in.",
                "⊕",
                "Settings > Other > Stremio add-ons",
            ),
            Step(
                "Three ways to install one",
                "Paste a manifest URL, add Stremio's public community catalogue, or tap Install on " +
                    "any add-on's own website and choose Prism.",
                "＋",
                "Settings > Other",
            ),
            Step(
                "What plays and what does not",
                "Catalogue add-ons describe things; separate add-ons provide streams — you " +
                    "generally need both. Torrent-only and external-link sources cannot play here, " +
                    "and the picker tells you which is which rather than hiding them.",
                "⚠",
            ),
        )),

        // ── Editor ──────────────────────────────────────────────────────────
        Section("Editor", listOf(
            Step(
                "A real code editor",
                "Monaco — the editor from VS Code — running on the phone, with a collapsing menu " +
                    "rail: File, Edit, Selection, View, Go, Run, Terminal and Marketplace.",
                "⌨",
                "Add Editor as a page",
            ),
            Step(
                "VS Code extensions",
                "Install extensions from Open VSX. Web extensions run in a worker; ones needing " +
                    "Node run in a real Node.js runtime inside Prism, where available for your " +
                    "device.",
                "🧩",
                "Editor > Marketplace",
            ),
            Step(
                "Completion, files and a shell",
                "Built-in completion for Java, Kotlin and Python, a file tree, and a working shell " +
                    "opened at whatever you have open.",
                "❯",
                "Editor > View > Terminal",
            ),
        )),

        // ── Science ─────────────────────────────────────────────────────────
        Section("Science", listOf(
            Step(
                "Your camera as a particle detector",
                "Cover the lens, take long exposures, and the bright pixels left are cosmic rays " +
                    "or sensor noise. Two phones on the mesh detecting one in the same instant is " +
                    "an air shower — the panel reports how many coincidences chance alone would " +
                    "have produced, so you can tell whether you measured anything.",
                "☢",
                "Add Science as a page",
            ),
            Step(
                "A notebook that cannot be rewritten",
                "Hash-chained, signed entries that other devices countersign. Changing an old " +
                    "entry breaks every hash after it, and there is no privileged position from " +
                    "which that can be repaired.",
                "📓",
                "Science page",
            ),
            Step(
                "RF survey and clinical tests",
                "Map signal strength with several phones walking at once. Estimate lung function " +
                    "from a forced exhale, and run a proper Hughson-Westlake hearing test.\n\n" +
                    "Read the caveats on each: without a calibrated microphone or headphones, only " +
                    "the ratios and your own trends over time mean anything.",
                "📶",
                "Science page",
            ),
        )),

        // ── Wallet ──────────────────────────────────────────────────────────
        Section("Wallet", listOf(
            Step(
                "A multi-coin wallet",
                "Hierarchical-deterministic wallets for several chains, plus PrismCoin — Prism's " +
                    "own, which every device on the mesh helps run.",
                "◈",
                "Add Wallet as a page",
            ),
            Step(
                "Mining, including together",
                "RandomX and other algorithms on the device, solo or against a pool — and a mesh " +
                    "pool where Prism devices mine as one.",
                "⛏",
                "Wallet > Mining tab",
            ),
        )),

        // ── Virtualization ──────────────────────────────────────────────────
        Section("Other operating systems", listOf(
            Step(
                "Run a guest OS",
                "PrismOS or an ISO of your own, rendered to the page over VNC with keyboard and " +
                    "pointer passed through.",
                "🖥",
                "Add Virtualization as a page",
            ),
            Step(
                "Windows programs",
                "Switching the page to Windows mode turns it into a Wine runtime and lets Prism " +
                    "appear as an option for opening .exe files.\n\n" +
                    "Plenty of Windows software will not work. That is expected — it is a " +
                    "compatibility layer running a translated instruction set on a phone.",
                "⊞",
                "Settings > Virtualization",
            ),
        )),

        // ── Keyboard, files, messaging ──────────────────────────────────────
        Section("Everyday things", listOf(
            Step(
                "Prism Writer",
                "Prism's own keyboard: suggestions, a personal dictionary, emoji and GIF panels " +
                    "and a search bar built in.",
                "⌨",
                "Settings > Other > Prism Writer",
            ),
            Step(
                "Messaging and contacts",
                "SMS and MMS with conversation threads, and a Contacts tab where you choose who " +
                    "your emergency contacts are.",
                "✉",
                "Add Messaging as a page",
            ),
            Step(
                "File Explorer",
                "Local files with copy, move and the usual operations, plus network storage.",
                "🗂",
                "Add File Explorer as a page",
            ),
        )),

        // ── Safety ──────────────────────────────────────────────────────────
        Section("Lock screen and safety", listOf(
            Step(
                "Prism's own lock screen",
                "PIN, password or pattern, with fingerprint where the device has a sensor, over " +
                    "your existing lock wallpaper.\n\n" +
                    "Android has not allowed an app to replace the system lock since version 5. " +
                    "Until you set the system lock to None or Swipe, you will unlock twice.",
                "🔒",
                "Settings > Other > Medical > Lock screen",
            ),
            Step(
                "A second code, for when you are not safe",
                "Set an emergency code and it unlocks the phone exactly like your normal one — " +
                    "same screen, same speed, no warning to anyone watching. It quietly sends your " +
                    "location to your emergency contacts and opens a launcher with your messages, " +
                    "photos and social apps absent.\n\n" +
                    "A fingerprint cannot trigger it: that needs a second secret only you know.",
                "⚠",
                "Settings > Other > Medical",
            ),
            Step(
                "A medical card anyone can read",
                "Blood type, severe allergies, conditions, medications, resuscitation wishes and " +
                    "your emergency contacts, shown on the lock screen without unlocking — because " +
                    "the person who needs it is a stranger giving you first aid.\n\n" +
                    "That does mean anyone holding your phone can read it. Nothing is shown until " +
                    "you fill something in.",
                "✚",
                "Settings > Other > Medical",
            ),
        )),

        // ── Close ───────────────────────────────────────────────────────────
        Section("That is the tour", listOf(
            Step(
                "Nothing here is switched on for you",
                "Every subsystem above is opt-in. Add the pages you want, turn on what you need, " +
                    "and leave the rest alone — it costs nothing while it is off.",
                "✓",
            ),
            Step(
                "You can see this again",
                "The tour is in Settings whenever you want it, and you can jump straight to any " +
                    "section rather than sitting through the whole thing.",
                "↺",
                "Settings > Other > Take the tour again",
            ),
        )),
    )

    val TOTAL_STEPS: Int get() = SECTIONS.sumOf { it.steps.size }
}
