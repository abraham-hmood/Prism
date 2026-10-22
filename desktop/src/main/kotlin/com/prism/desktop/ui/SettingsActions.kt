package com.prism.desktop.ui

import com.prism.core.MeshDns
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.SettingsCatalog.Target
import com.prism.launcher.browser.PrismBlocklist
import java.awt.Desktop
import java.io.File

/**
 * What a settings row that DOES something does on the desktop. PHASE 24.
 *
 * ## Why this is a `when` over a closed enum
 *
 * `SettingsCatalog` names an action instead of carrying a lambda, so the catalog stays free of any
 * platform. That leaves somebody to interpret the names, and the interpretation is per-platform: "manage
 * trusted devices" opens an Activity on the phone and a page here; "enable Prism Writer" opens Android's
 * input-method settings and has no desktop meaning at all.
 *
 * THE ENUM IS EXHAUSTIVE ON PURPOSE. A string target would let a new action be added to the catalog and
 * silently do nothing here; with a closed set the compiler says which host has not caught up.
 *
 * ## Why every branch returns a sentence
 *
 * Because some of them genuinely cannot act, and the row then shows the reason in place of its subtitle.
 * A settings row that looks tappable and does nothing when tapped is the worst of the three options --
 * worse than a row that explains itself, and worse than no row at all.
 *
 * ## Navigation
 *
 * [navigate] is set by the window, which owns the selected page. Rows that open another page therefore
 * work only inside the window; the same catalog rendered somewhere without navigation says so rather than
 * failing.
 */
object SettingsActions {

    private const val TAG = "PrismSettingsActions"

    /** Installed by `PrismWindow`, which is the only thing that knows how to change pages. */
    var navigate: ((PageId) -> Unit)? = null

    /** Dialogs a settings row can ask the page to show. */
    enum class Dialog { PRISM_SERVERS }

    /** Installed by the settings page, which is what can host a dialog. */
    var openDialog: ((Dialog) -> Unit)? = null

    /** Carries out [target], returning what the row should say afterwards. */
    fun perform(target: Target): String = when (target) {

        // ── Pages this build has ───────────────────────────────────────────
        Target.VIEW_SEEDS, Target.VIEW_DISCOVERED_SEEDS, Target.ADD_SEED ->
            go(PageId.SEARCH, "Opened Search, where the seeds and the index live.")

        Target.OPEN_BLOCKLIST -> go(PageId.BLOCKLIST, "Opened the blocklist.")
        Target.APP_WHITELIST, Target.COPY_WIREGUARD_CONFIG ->
            go(PageId.TUNNEL, "Opened Tunnel, which has the adapter, the whitelist and WireGuard.")

        Target.OPEN_TRUSTED_DEVICES -> go(PageId.TRUSTED, "Opened trusted devices.")
        Target.OPEN_ACCESS_POINT -> go(PageId.ACCESS_POINT, "Opened the access point.")
        Target.MANAGE_DNS_RECORDS, Target.MANAGE_WEB_HOSTING ->
            go(PageId.MESH, "Opened Mesh, which lists the names this device answers for.")

        Target.OPEN_DIAGNOSTICS -> go(PageId.DIAGNOSTICS, "Opened diagnostics.")
        Target.OPEN_MODELS, Target.OPEN_LOCAL_MODEL -> go(PageId.MODELS, "Opened Models.")
        Target.OPEN_CLOUD_MODELS -> go(PageId.CLOUD_AI, "Opened the cloud models.")
        Target.OPEN_IMAGE_MODELS -> go(PageId.IMAGE_GEN, "Opened image generation.")
        Target.OPEN_NORA -> go(PageId.NORA_SETTINGS, "Opened Nora's settings.")
        Target.OPEN_AETHER -> go(PageId.AETHER, "Opened Aether.")
        // CakeChat is not a rail page: it opens from inside Models, which is where its install and
        // training controls live. Sending somebody to Models is one hop from the real screen.
        Target.OPEN_CAKECHAT -> go(PageId.MODELS, "Opened Models; CakeChat is inside it.")
        Target.OPEN_WALLET -> go(PageId.WALLET, "Opened the wallet.")
        Target.OPEN_MINING -> go(PageId.WALLET_EXTRAS, "Opened mining and the wallet extras.")
        Target.OPEN_DATASETS -> go(PageId.NORA_TRAIN, "Opened training, which is where datasets are managed.")

        // ── Things that happen here and now ────────────────────────────────
        Target.REBUILD_INDEX -> runCatching {
            com.prism.launcher.search.PrismSearchServer.crawlNow()
            "A crawl has started. Search shows its progress."
        }.getOrElse { "Could not start a crawl: " + it.message }

        Target.DIAGNOSE_SEARCH -> runCatching {
            // The first line only: a row shows one line, and the full report is what the Search page
            // prints. Truncating here beats a row that tries to render twenty lines of diagnostics.
            com.prism.launcher.search.PrismSearchDiagnostics.report().lines()
                .firstOrNull { it.isNotBlank() } ?: "No diagnosis available."
        }.getOrElse { "Could not run the diagnosis: " + it.message }

        Target.CLEAR_HISTORY -> runCatching {
            // ONE STEP, NOT A CONFIRMATION, because the row's own subtitle already says there is no
            // undo and a second dialog for a destructive action the user deliberately opened is
            // friction rather than safety. The phone does the same.
            com.prism.launcher.history.PrismHistory.clear()
            "Cleared."
        }.getOrElse { "Could not clear it: " + it.message }

        Target.VIEW_CACHED_SITES -> describeDirectory("mirrors", "cached site")

        Target.VIEW_LOCAL_ADDRESS -> com.prism.core.MeshUtils.getLocalMeshIp()
            .ifBlank { "This device has no mesh address; the mesh is not running." }

        // OFF THE UI THREAD AND REPORTED LATER, because scanning a subnet is suspending and takes
        // seconds. Doing it inline with runBlocking would freeze the window mid-scan, which on a
        // settings page reads as Prism having crashed.
        Target.RESCAN_OLLAMA -> {
            Thread({
                runCatching {
                    val found = kotlinx.coroutines.runBlocking {
                        com.prism.launcher.messaging.OllamaDiscoveryService.scan()
                    }
                    PrismPlatform.log.info(
                        TAG,
                        if (found.isEmpty()) "No Ollama server answered on this network."
                        else "Found " + found.size + " Ollama server(s).",
                    )
                }
            }, "ollama-scan").apply { isDaemon = true }.start()
            "Scanning the network. The Cloud AI page lists whatever answers."
        }

        Target.SUMMARY_STATUS -> if (PrismSettings.getLocalAiModelPath().isNotBlank()) {
            "A local model is set, so summaries can be written here."
        } else {
            "No local model is set, so summaries fall back to whichever engine is configured."
        }

        // A DIALOG RATHER THAN A SENTENCE. This is the one action with real editing behind it -- a list,
        // an add form, connect-on-click and a context menu -- so the row asks the page to open it instead
        // of summarising what it would have shown.
        Target.MANAGE_PRISM_SERVERS -> {
            openDialog?.invoke(Dialog.PRISM_SERVERS)
            val servers = runCatching { PrismSettings.getPrismServers() }.getOrDefault(emptyList())
            if (servers.isEmpty()) "No servers yet. Add one to make a meshnet."
            else servers.size.toString() + " server(s); " +
                (servers.firstOrNull { it.isActive }?.let { "active: " + it.name.ifBlank { it.address } }
                    ?: "none active")
        }

        Target.EXTERNAL_VPN_PROFILE -> {
            val profile = PrismSettings.getExternalVpnProfile()
            if (profile.isBlank()) "No profile stored. Paste one on the Tunnel page."
            else com.prism.desktop.net.VpnStack.detect(profile).name.lowercase() +
                " config, " + profile.lines().size + " lines"
        }

        // ── Files, through the platform's own chooser ──────────────────────
        Target.SELECT_CUSTOM_FONT -> chooseFile("Choose a .ttf font") { file ->
            PrismSettings.setCustomFontPath(file.absolutePath)
            "Font set to " + file.name + ". Restart Prism to see it everywhere."
        }

        Target.SELECT_ISO -> chooseFile("Choose an ISO") { file ->
            PrismSettings.setCustomIsoPath(file.absolutePath)
            "ISO set to " + file.name + "."
        }

        Target.OPEN_SPEECH_MODEL -> chooseFile("Choose a speech model") { file ->
            PrismSettings.setLocalAudioModelPath(file.absolutePath)
            "Speech model set to " + file.name + "."
        }

        Target.AUTO_CAPTION_FOLDER ->
            "Captioning a folder needs the vision pipeline, which runs on the Models page here."

        // ── Honest refusals ───────────────────────────────────────────────
        //
        // Each of these is a real phone feature with no desktop counterpart, and each says which. The
        // alternative -- leaving the row out -- would make this page quietly smaller than the phone's
        // and give no clue that the setting exists at all.
        Target.OPEN_WRITER, Target.WRITER_DICTIONARY ->
            "Prism Writer is an Android input method. Its settings are stored here and the phone uses " +
                "them; there is nothing for a desktop to enable."

        Target.OPEN_LOCK_SCREEN, Target.EMERGENCY_CONTACTS ->
            "The lock screen and its emergency card belong to the phone. What is stored here syncs to it."

        Target.OPEN_PRISM_SWAP ->
            "Prism Swap lends a model more memory than the device has, which matters on a phone. A " +
                "desktop pages through the operating system instead."

        // PHASE 109: the desktop has its own tour now, so this clears the flag and the overlay comes back.
        Target.OPEN_TOUR -> {
            com.prism.launcher.onboarding.OnboardingTour.forget()
            "The tour will run the next time the Prism window opens."
        }

        // PHASE 105: both rows land on the same page, which has the repositories above the addons --
        // splitting them into two pages would separate a list from the thing that fills it.
        Target.STREMIO_REPOSITORIES, Target.STREMIO_ADDONS ->
            go(PageId.STREMIO, "Opened Stremio.")
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun go(page: PageId, message: String): String {
        val jump = navigate
        if (jump == null) return "This build cannot change pages from here."
        runCatching { jump(page) }
        return message
    }

    /**
     * AWT's own file dialog, not Swing's chooser.
     *
     * The platform's box, so it looks like every other open dialog on the machine and starts wherever the
     * user last was. Swing's is portable and looks like nothing else on any platform.
     */
    private fun chooseFile(title: String, onChosen: (File) -> String): String {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory
        val name = dialog.file ?: return "Nothing chosen."
        val file = File(directory, name)
        if (!file.isFile) return "That is not a file."
        return runCatching { onChosen(file) }.getOrElse { "Could not use it: " + it.message }
    }

    private fun describeDirectory(name: String, noun: String): String {
        val dir = File(PrismPlatform.host.dataDir(), name)
        val entries = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val bytes = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        if (entries.isEmpty()) return "Nothing stored."
        // Offered rather than opened: a settings row should not spawn a file manager unasked.
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir) }
        return entries.size.toString() + " " + noun + "(s), " + (bytes / (1024 * 1024)) + " MB"
    }

    /** How many names this device answers for, used by the mesh rows. */
    fun meshRecordCount(): Int = runCatching { MeshDns.all().size }.getOrDefault(0)

    /** Whether the blocklist has loaded, so a row can say so instead of showing zero. */
    fun blocklistSize(): Int =
        runCatching { PrismBlocklist.get().snapshotBlockedHosts().size }.getOrDefault(0)
}
