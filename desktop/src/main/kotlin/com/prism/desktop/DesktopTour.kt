package com.prism.desktop

import com.prism.launcher.onboarding.OnboardingTour.Section
import com.prism.launcher.onboarding.OnboardingTour.Step

/**
 * The desktop's own first-run tour. PHASE 109.
 *
 * ## Why this is written rather than ported
 *
 * The Android tour teaches swiping between pages, long-pressing an empty spot, and a row of pages you move
 * through with your thumb. None of that is how the desktop works: it has a rail, a window, a keyboard and a
 * right mouse button. Reusing that text would have produced a tour that was confidently wrong about the
 * product in front of the reader, which is worse than no tour at all -- so the mechanism is shared and this
 * is new.
 *
 * ## What it teaches, and what it deliberately does not
 *
 * The things a desktop user cannot guess: that the rail is a list of PAGES rather than a menu, that launcher
 * mode exists for anyone who uses Prism on a phone first, that pairing needs the meshnet rather than the
 * Wi-Fi, and that several subsystems are off until asked for.
 *
 * It does NOT enumerate every page. The Android tour is 486 lines because a phone has no rail to read; a
 * window with a labelled list down its left edge does not need to be told what is on it. A tour that
 * recited the rail would be teaching somebody to read.
 */
object DesktopTour {

    val SECTIONS: List<Section> = listOf(

        Section("Welcome", listOf(
            Step(
                glyph = "◧",
                title = "Prism is made of pages",
                body = "The list down the left is not a menu -- it is the set of pages Prism is made " +
                    "of, and they are the same pages the phone build has. Click one to go there.\n\n" +
                    "Most of what is on that list is off until you ask for it. A launcher that " +
                    "switched on twenty subsystems at first run would be unusable, so the pages are " +
                    "there and the machinery behind them is not running.",
            ),
            Step(
                glyph = "⌨",
                title = "There is a launcher mode",
                body = "If you use Prism on a phone, the rail will feel wrong. Launcher mode replaces " +
                    "it with the phone's navigation: a horizontal pager over the page slots you " +
                    "assigned, at full window width. Tab and Shift+Tab change page, the down arrow " +
                    "opens the switcher, and 1 to 9 jump straight to a slot.\n\n" +
                    "The slots come from the same store the phone writes, so an arrangement made " +
                    "there is the arrangement you get here.",
                whereToFind = "Prism Settings > Desktop > Launcher mode",
            ),
        )),

        Section("The mesh", listOf(
            Step(
                glyph = "◈",
                title = "This machine can be a peer",
                body = "Prism's meshnet is devices that found each other and agreed to share things: " +
                    "text messages, browsing history, clipboards, models, compute, and posts.\n\n" +
                    "A desktop is a better peer than a phone at almost all of it. It stays online, it " +
                    "is better connected, and it has the memory and the cores that the phones on the " +
                    "mesh do not.",
                whereToFind = "Mesh",
            ),
            Step(
                glyph = "⚿",
                title = "Pairing needs the meshnet, not the Wi-Fi",
                body = "Trusted-device pairing carries your messages, your history, your clipboard and " +
                    "-- if you allow it -- your wallet's recovery phrase. So Prism will only offer it " +
                    "to devices on the mesh overlay, never to whatever else happens to be on the " +
                    "same network.\n\n" +
                    "That means a server has to exist first. Add one under Prism Settings, or serve " +
                    "the mesh from this machine, and the device picker fills up.",
                whereToFind = "Trusted devices, and Prism Settings > Network > Manage Prism servers",
            ),
        )),

        Section("Intelligence", listOf(
            Step(
                glyph = "◑",
                title = "Three different kinds of AI, and they are not variants",
                body = "Sam runs whichever engine you configure: a local GGUF model, a cloud key, an " +
                    "Ollama server, or a model another device on the mesh is hosting.\n\n" +
                    "Nora and Aether are not that. They are networks Prism trains itself -- a " +
                    "predictive-coding hierarchy and a spiking brain that reads with an eye. They " +
                    "answer differently because they are built differently, and on a desktop they " +
                    "can actually be trained.",
                whereToFind = "Sam, Nora, Aether",
            ),
            Step(
                glyph = "⬇",
                title = "Quantising belongs on this machine",
                body = "Making a model smaller takes hours on a phone and about a minute here, and the " +
                    "result is an ordinary GGUF that loads on either. If you use Prism on both, this " +
                    "is where to do it and the mesh is how to move it.",
                whereToFind = "Quantize",
            ),
        )),

        Section("Privacy", listOf(
            Step(
                glyph = "◐",
                title = "Private tabs, and what they actually do",
                body = "A private tab gets its own Chromium context: its own cookies, storage and " +
                    "cache, all destroyed with the tab. Two private tabs cannot see each other.\n\n" +
                    "It will also bring up the mesh tunnel, which needs an adapter this machine may " +
                    "not have. When it cannot, Prism says so rather than implying your traffic is " +
                    "going somewhere it is not.",
                whereToFind = "Browser, and Tunnel",
            ),
            Step(
                glyph = "⌧",
                title = "Everything is on this machine",
                body = "The wallet phrase is sealed with a key the operating system holds and will not " +
                    "hand to another machine. The search index, the history, the blocklist and the " +
                    "models are all local files.\n\n" +
                    "Diagnostics shows every subsystem at once, including the ones that are failing " +
                    "and why. It is the first place to look when something is not working.",
                whereToFind = "Diagnostics",
            ),
        )),

        Section("Making it yours", listOf(
            Step(
                glyph = "✛",
                title = "The desktop page is a grid you fill",
                body = "Drag an app onto it from the Apps page. Right-click things -- a server, a " +
                    "character, a plugin -- for what you can do with them; the desktop has a right " +
                    "mouse button and Prism uses it.",
                whereToFind = "Desktop, Apps",
            ),
            Step(
                glyph = "⬢",
                title = "Plugins, if you write one",
                body = "A plugin is a JAR that describes a page as data, so the same one works here " +
                    "and on the phone. The Prism source ships a working sample to copy.\n\n" +
                    "Loading is off until you allow it: a plugin runs with Prism's own permissions, " +
                    "and a file appearing in a folder is not consent.",
                whereToFind = "Plugins",
            ),
        )),
    )
}
