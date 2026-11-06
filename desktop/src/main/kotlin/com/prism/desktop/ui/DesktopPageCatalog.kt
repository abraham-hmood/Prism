package com.prism.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences

/**
 * One page the desktop drawer can show.
 *
 * It carries both [assignment] and [pageId] on purpose: the launcher shell navigates by slot, while
 * the workbench is a rail of pages and navigates by [PageId]. Keeping the pair together means the
 * drawer does not have to guess which kind of navigation it is inside -- the shell provides
 * [LocalSlotOpener], and without one the entry falls back to its rail page.
 */
data class DesktopPageEntry(
    val assignment: SlotAssignment,
    val label: String,
    val icon: ImageVector,
    /** The workbench page this slot renders, when there is one. */
    val pageId: PageId?,
) {
    val key: String get() = assignment.serialize()
}

/**
 * Every page the desktop build can put in a slot.
 *
 * This mirrors the Android page set rather than the ported rail set: a page can be placeable on a
 * slot before it has its own rail entry (Kinetic Halo, for instance). The drawer says what the
 * pager can show, because that is what tapping an entry does.
 */
object DesktopPageCatalog {

    private val builtIns: List<DesktopPageEntry> = listOf(
        DesktopPageEntry(SlotAssignment.Default, "Desktop", Icons.Filled.GridView, PageId.DESKTOP),
        DesktopPageEntry(SlotAssignment.DesktopGrid, "Desktop grid", Icons.Filled.GridView, PageId.DESKTOP),
        DesktopPageEntry(SlotAssignment.Browser, "Browser", Icons.Filled.Public, PageId.BROWSER),
        DesktopPageEntry(SlotAssignment.AppDrawer, "Apps", Icons.Filled.Apps, PageId.APPS),
        DesktopPageEntry(SlotAssignment.Messaging, "Messages", Icons.Filled.Forum, PageId.MESSAGES_PAGE),
        DesktopPageEntry(SlotAssignment.KineticHalo, "Kinetic Halo", Icons.Filled.TouchApp, null),
        DesktopPageEntry(SlotAssignment.FileExplorer, "Files", Icons.Filled.Folder, PageId.FILES),
        DesktopPageEntry(SlotAssignment.NebulaSocial, "Nebula", Icons.Filled.Groups, PageId.NEBULA),
        DesktopPageEntry(SlotAssignment.VirtualizationOs, "Virtualization", Icons.Filled.Computer, PageId.VIRTUALIZATION),
        DesktopPageEntry(SlotAssignment.Models, "Models", Icons.Filled.Inventory2, PageId.MODELS),
        DesktopPageEntry(SlotAssignment.ModelStore, "Model store", Icons.Filled.Storefront, PageId.MODEL_SHOP),
        DesktopPageEntry(SlotAssignment.AgenticTools, "Agentic tools", Icons.Filled.Build, PageId.AGENTIC),
        DesktopPageEntry(SlotAssignment.Wallet, "Wallet", Icons.Filled.AccountBalanceWallet, PageId.WALLET),
        DesktopPageEntry(SlotAssignment.Editor, "Editor", Icons.Filled.Code, PageId.EDITOR),
        DesktopPageEntry(SlotAssignment.Science, "Science", Icons.Filled.Biotech, PageId.SCIENCE),
        DesktopPageEntry(SlotAssignment.Notifications, "Notifications", Icons.Filled.Notifications, PageId.NOTIFICATIONS),
        DesktopPageEntry(SlotAssignment.Language, "Language", Icons.Filled.Translate, PageId.LANGUAGE),
        DesktopPageEntry(SlotAssignment.Minigames, "Games", Icons.Filled.SportsEsports, PageId.GAMES),
        DesktopPageEntry(SlotAssignment.Cloud, "Cloud", Icons.Filled.Cloud, PageId.CLOUD_AI),
        DesktopPageEntry(SlotAssignment.Meshvoice, "Meshvoice", Icons.Filled.Voicemail, PageId.MESHVOICE),
        DesktopPageEntry(SlotAssignment.Maps, "Maps", Icons.Filled.Map, PageId.MAPS),
        DesktopPageEntry(SlotAssignment.Mouse, "Mouse", Icons.Filled.TouchApp, PageId.MOUSE),
        DesktopPageEntry(SlotAssignment.Photos, "Photos", Icons.Filled.PhotoLibrary, PageId.PHOTOS),
        DesktopPageEntry(SlotAssignment.PhotoPix, "PhotoPix", Icons.Filled.Brush, PageId.PHOTOPIX),
        DesktopPageEntry(SlotAssignment.VideoCraft, "VideoCraft", Icons.Filled.Movie, PageId.VIDEOCRAFT),
        DesktopPageEntry(SlotAssignment.ImageCraft, "ImageCraft", Icons.Filled.AutoFixHigh, PageId.IMAGECRAFT),
        DesktopPageEntry(SlotAssignment.AudioCraft, "AudioCraft", Icons.Filled.LibraryMusic, PageId.AUDIOCRAFT),
        DesktopPageEntry(SlotAssignment.ModelCraft, "ModelCraft", Icons.Filled.Memory, PageId.MODELCRAFT),
        DesktopPageEntry(SlotAssignment.DatasetCraft, "DatasetCraft", Icons.Filled.Storage, PageId.DATASETCRAFT),
        DesktopPageEntry(SlotAssignment.AssetCraft, "AssetCraft", Icons.Filled.SportsEsports, PageId.ASSETCRAFT),
        DesktopPageEntry(SlotAssignment.GameCraft, "GameCraft", Icons.Filled.SportsEsports, PageId.GAMECRAFT),
        DesktopPageEntry(SlotAssignment.EmailServer, "Email server", Icons.Filled.Dns, PageId.EMAIL_SERVER),
        DesktopPageEntry(SlotAssignment.EmailClient, "Email", Icons.Filled.Mail, PageId.EMAIL_CLIENT),
        DesktopPageEntry(SlotAssignment.TimeMachine, "Time Machine", Icons.Filled.History, PageId.TIME_MACHINE),
        DesktopPageEntry(SlotAssignment.Journal, "Journal", Icons.Filled.MenuBook, PageId.JOURNAL),
        DesktopPageEntry(SlotAssignment.Podcasts, "Podcasts", Icons.Filled.Podcasts, PageId.PODCASTS),
        DesktopPageEntry(SlotAssignment.AppStore, "App Store", Icons.Filled.Storefront, PageId.APP_STORE),
        DesktopPageEntry(SlotAssignment.TaskManager, "Task Manager", Icons.Filled.Build, PageId.TASK_MANAGER),
    )

    fun all(): List<DesktopPageEntry> = builtIns

    /**
     * The pages on actual slots, in pager order, with the slot each one lives on.
     *
     * A plugin slot this build cannot render still appears, labelled by package: the slot is the
     * user's and hiding it would make the drawer disagree with the pager.
     */
    fun configured(prefs: SlotPreferences = SlotPreferences()): List<Pair<DesktopPageEntry, Int>> =
        prefs.getAssignments().mapIndexedNotNull { slot, assignment ->
            val entry = builtIns.firstOrNull { it.key == assignment.serialize() }
                ?: if (assignment is SlotAssignment.Custom) {
                    DesktopPageEntry(
                        assignment = assignment,
                        label = assignment.packageName.substringAfterLast('.'),
                        icon = Icons.Filled.Extension,
                        pageId = null,
                    )
                } else null
            entry?.let { it to slot }
        }
}

/**
 * How a page on the drawer reaches its slot.
 *
 * A composition local because the launcher shell owns the pager and the drawer is several layers
 * below it; passing a callback through every page body would make every page know about slots. Null
 * outside the launcher shell, where entries fall back to their rail page.
 *
 * It takes the assignment rather than a slot: whether the page is already placed, and which slot it
 * borrows when it is not, is the shell's business.
 */
val LocalSlotOpener = staticCompositionLocalOf<((SlotAssignment) -> Unit)?> { null }
