package com.prism.launcher

import android.content.Context
import android.content.pm.PackageManager

/**
 * One page Prism can show, as the drawer sees it.
 *
 * A page is a [SlotAssignment] plus the name and mark the drawer draws. The mark is a glyph
 * rather than a bitmap on purpose: every page already carries its own colour and symbol here, so
 * adding a page does not mean adding a drawable to the APK, and plugin pages get a distinct mark
 * without the plugin shipping Android resources.
 */
data class PrismPageEntry(
    val assignment: SlotAssignment,
    val label: String,
    val glyph: String,
    val tint: Int,
    val builtIn: Boolean = true,
) {
    val key: String get() = assignment.serialize()
}

/**
 * The pages this device has, and the ones the user has actually put in a slot.
 *
 * Built-ins are listed even when no slot holds them: that is what makes the drawer a place to
 * discover a page rather than only a second copy of the pager. Plugin pages are discovered from
 * the same contract the page picker uses, so a plugin that can be assigned can also be found here.
 */
object PageCatalog {

    private val builtIns: List<PrismPageEntry> = listOf(
        PrismPageEntry(SlotAssignment.Default, "Desktop", "\uD83D\uDDA5", 0xFF5B8DEF.toInt()),
        PrismPageEntry(SlotAssignment.DesktopGrid, "Desktop grid", "\uD83D\uDDC2", 0xFF5B8DEF.toInt()),
        PrismPageEntry(SlotAssignment.Browser, "Browser", "\uD83C\uDF10", 0xFF2CB9C4.toInt()),
        PrismPageEntry(SlotAssignment.AppDrawer, "Apps", "\u25A6", 0xFF8E7BEF.toInt()),
        PrismPageEntry(SlotAssignment.Messaging, "Messages", "\uD83D\uDCAC", 0xFF34B27B.toInt()),
        PrismPageEntry(SlotAssignment.KineticHalo, "Kinetic Halo", "\u25CE", 0xFFB07BEF.toInt()),
        PrismPageEntry(SlotAssignment.FileExplorer, "Files", "\uD83D\uDCC1", 0xFFE0A03C.toInt()),
        PrismPageEntry(SlotAssignment.NebulaSocial, "Nebula", "\uD83C\uDF0C", 0xFF6C6CEF.toInt()),
        PrismPageEntry(SlotAssignment.VirtualizationOs, "Virtualization", "\uD83D\uDCBB", 0xFF7A8CA8.toInt()),
        PrismPageEntry(SlotAssignment.Models, "Models", "\uD83E\uDDE0", 0xFFE06C8B.toInt()),
        PrismPageEntry(SlotAssignment.ModelStore, "Model Store", "\uD83D\uDECD", 0xFFE08A3C.toInt()),
        PrismPageEntry(SlotAssignment.AgenticTools, "Agentic Tools", "\uD83D\uDEE0", 0xFF8A9BB0.toInt()),
        PrismPageEntry(SlotAssignment.Wallet, "Wallet", "\uD83D\uDCB0", 0xFFD4A017.toInt()),
        PrismPageEntry(SlotAssignment.Editor, "Editor", "\u2328", 0xFF5E9BD6.toInt()),
        PrismPageEntry(SlotAssignment.Science, "Science", "\uD83D\uDD2C", 0xFF43B5A0.toInt()),
        PrismPageEntry(SlotAssignment.Notifications, "Notifications", "\uD83D\uDD14", 0xFFE0B03C.toInt()),
        PrismPageEntry(SlotAssignment.Language, "Language", "\uD83D\uDDE3", 0xFF58B6D8.toInt()),
        PrismPageEntry(SlotAssignment.Minigames, "Minigames", "\uD83C\uDFAE", 0xFFE069B0.toInt()),
        PrismPageEntry(SlotAssignment.Cloud, "Cloud", "\u2601", 0xFF6FA8EF.toInt()),
        PrismPageEntry(SlotAssignment.Meshvoice, "Meshvoice", "\uD83D\uDCDE", 0xFF4FB08A.toInt()),
        PrismPageEntry(SlotAssignment.Maps, "Maps", "\uD83D\uDDFA", 0xFF58B66E.toInt()),
        PrismPageEntry(SlotAssignment.Mouse, "Mouse", "\uD83D\uDDB1", 0xFF9A9AB0.toInt()),
        PrismPageEntry(SlotAssignment.Photos, "Photos", "\uD83D\uDDBC", 0xFFE07878.toInt()),
        PrismPageEntry(SlotAssignment.PhotoPix, "PhotoPix", "\uD83C\uDFA8", 0xFFC77BEF.toInt()),
        PrismPageEntry(SlotAssignment.VideoCraft, "VideoCraft", "\uD83C\uDFAC", 0xFFE06C6C.toInt()),
        PrismPageEntry(SlotAssignment.ImageCraft, "ImageCraft", "\uD83D\uDD8C", 0xFF6CA8E0.toInt()),
        PrismPageEntry(SlotAssignment.AudioCraft, "AudioCraft", "\uD83C\uDFB5", 0xFF58B69B.toInt()),
        PrismPageEntry(SlotAssignment.ModelCraft, "ModelCraft", "\uD83E\uDDEC", 0xFFB08AEF.toInt()),
        PrismPageEntry(SlotAssignment.DatasetCraft, "DatasetCraft", "\uD83D\uDDC2", 0xFF8AB66C.toInt()),
        PrismPageEntry(SlotAssignment.AssetCraft, "AssetCraft", "\uD83E\uDDCA", 0xFF5CC2C9.toInt()),
        PrismPageEntry(SlotAssignment.GameCraft, "GameCraft", "\uD83C\uDFAE", 0xFFB06CE0.toInt()),
        PrismPageEntry(SlotAssignment.EmailServer, "Email server", "\uD83D\uDCEC", 0xFF5B8DEF.toInt()),
        PrismPageEntry(SlotAssignment.EmailClient, "Email", "\u2709", 0xFF5B8DEF.toInt()),
        PrismPageEntry(SlotAssignment.TimeMachine, "Time Machine", "\u23F1", 0xFF8A9BB0.toInt()),
        PrismPageEntry(SlotAssignment.Journal, "Journal", "\uD83D\uDCD3", 0xFFC98A5B.toInt()),
        PrismPageEntry(SlotAssignment.Podcasts, "Podcasts", "🎙", 0xFF9D4EDD.toInt()),
        PrismPageEntry(SlotAssignment.AppStore, "App Store", "\uD83D\uDED2", 0xFF4FB08A.toInt()),
        PrismPageEntry(SlotAssignment.Helper, "Helper", "🤝", 0xFFE07B6C.toInt()),
        PrismPageEntry(SlotAssignment.Vpn, "VPN", "🛡", 0xFF5B8DEF.toInt()),
        PrismPageEntry(SlotAssignment.TaskManager, "Task Manager", "\u2699", 0xFF9A9AB0.toInt()),
    )

    /** The colour and glyph a plugin page gets. Distinct from every built-in mark. */
    private const val PLUGIN_TINT = 0xFFB0B0C0.toInt()

    fun builtInPages(): List<PrismPageEntry> = builtIns

    /** Every page this device can show, built-in and plugin. */
    fun all(context: Context): List<PrismPageEntry> =
        builtIns + plugins(context.packageManager)

    private fun plugins(pm: PackageManager): List<PrismPageEntry> =
        runCatching {
            PluginPageDiscovery.discover(pm).map { info ->
                PrismPageEntry(
                    assignment = SlotAssignment.Custom(info.packageName, info.viewClassName),
                    label = info.label.ifBlank { "Plugin page" },
                    glyph = "\uD83D\uDD0C",
                    tint = PLUGIN_TINT,
                    builtIn = false,
                )
            }
        }.getOrDefault(emptyList())

    /**
     * The pages currently assigned to slots, in pager order, paired with their slot.
     *
     * A custom assignment that no longer resolves to an installed plugin still appears, labelled
     * from its package: the slot is the user's, and hiding it would make the drawer disagree with
     * the pager about what is on screen.
     */
    fun configured(context: Context): List<Pair<PrismPageEntry, Int>> {
        val pluginsByKey = plugins(context.packageManager).associateBy { it.key }
        return SlotPreferences().getAssignments().mapIndexedNotNull { slot, assignment ->
            val known = pluginsByKey[assignment.serialize()]
                ?: builtIns.firstOrNull { it.assignment.serialize() == assignment.serialize() }
                ?: if (assignment is SlotAssignment.Custom) {
                    PrismPageEntry(
                        assignment = assignment,
                        label = assignment.packageName.substringAfterLast('.'),
                        glyph = "\uD83D\uDD0C",
                        tint = PLUGIN_TINT,
                        builtIn = false,
                    )
                } else {
                    null
                }
            known?.let { it to slot }
        }
    }

    /** The label a slot shows, for a badge or a title. */
    fun label(assignment: SlotAssignment, context: Context): String =
        all(context).firstOrNull { it.key == assignment.serialize() }?.label
            ?: when (assignment) {
                is SlotAssignment.Custom -> assignment.packageName.substringAfterLast('.')
                else -> "Page"
            }
}
