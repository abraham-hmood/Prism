package com.prism.launcher.display

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import android.util.Log
import androidx.appcompat.view.ContextThemeWrapper
import com.prism.launcher.R
import com.prism.launcher.DesktopGridPage
import com.prism.launcher.DrawerPageView
import com.prism.launcher.KineticHaloPageView
import com.prism.launcher.SlotAssignment
import com.prism.launcher.browser.BrowserPageView
import com.prism.launcher.cloud.CloudPageView
import com.prism.launcher.editor.EditorPageView
import com.prism.launcher.files.FileExplorerPageView
import com.prism.launcher.language.LanguagePageView
import com.prism.launcher.maps.MapsPageView
import com.prism.launcher.messaging.MessagingPageView
import com.prism.launcher.minigames.MinigamesPageView
import com.prism.launcher.mouse.MousePageView
import com.prism.launcher.notifications.NotificationsPageView
import com.prism.launcher.science.SciencePageView
import com.prism.launcher.virtualization.VirtualizationPageView
import com.prism.launcher.wallet.WalletPageView

/**
 * Builds the page an extending sender asked a display to render.
 *
 * This is deliberately not the launcher's pager adapter: a display has no pager and no
 * LauncherActivity, so a page here is a single view placed in the receiver window. Pages that
 * need the launcher's own host still work because their constructors take a plain Context; the
 * few that cast their context are listed with the reason rather than being built and crashing.
 *
 * Taps are inert. Extend puts a PAGE on the other screen, not control of this device: launching
 * the display's own copy of an app because somebody touched the sender's icon is the same class
 * of bug as showing the receiver's Files page in mimic mode.
 */
object DisplayPageFactory {

    fun build(context: Context, show: WirelessDisplay.Show): android.view.View {
        val assignment = SlotAssignment.deserialize(show.slot)
        val themed = themedContext(context)
        return try {
            page(themed, assignment, show.index)
        } catch (failure: Exception) {
            // The outer message from a LayoutInflater is "Binary XML file ... Error inflating
            // class <unknown>"; the reason why is the nested cause. It belongs in the log, not
            // on a television: the user asked to see a page, not a stack trace.
            val detail = generateSequence(failure as Throwable) { it.cause }
                .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString("\n")
            Log.w("PrismDisplay", "Could not build extended page " + show.slot + ": " + detail)
            message(themed, "This page could not be drawn for the display.")
        }
    }

    /**
     * The page for one slot, built for somewhere other than the pager -- a display, or a wall in
     * the AR home screen. Never throws: a page that cannot be built becomes a message.
     */
    fun buildPage(context: Context, assignment: SlotAssignment, index: Int): android.view.View {
        val themed = themedContext(context)
        return try {
            page(themed, assignment, index)
        } catch (failure: Exception) {
            Log.w("PrismDisplay", "Could not build page " + assignment.serialize() + ": " + failure.message)
            message(themed, "This page could not be drawn here.")
        }
    }

    /**
     * A context that can inflate a Material page.
     *
     * A page built while Prism is in the background used to inherit the Application theme, which
     * has none of the Material attributes `page_drawer_root.xml` asks for. That is the whole
     * "Binary XML file ... Error inflating class <unknown>" failure: not a missing layout, a
     * missing theme. An Activity already carries one; an Application does not, so it gets one.
     */
    private fun themedContext(context: Context): Context =
        if (context is android.app.Activity) context
        else ContextThemeWrapper(context, R.style.Theme_PrismLauncher)

    private fun page(context: Context, assignment: SlotAssignment, index: Int): android.view.View =
        when (assignment) {
            SlotAssignment.Browser -> BrowserPageView(context)
            SlotAssignment.DesktopGrid -> grid(context, index)
            SlotAssignment.AppDrawer -> DrawerPageView(context, {}, { false })
            SlotAssignment.Messaging -> MessagingPageView(context)
            SlotAssignment.KineticHalo -> KineticHaloPageView(context) {}
            SlotAssignment.FileExplorer -> FileExplorerPageView(context)
            SlotAssignment.VirtualizationOs -> VirtualizationPageView(context)
            SlotAssignment.Models -> com.prism.launcher.ModelsPageView(context)
            SlotAssignment.Editor -> EditorPageView(context)
            SlotAssignment.Science -> SciencePageView(context)
            SlotAssignment.Language -> LanguagePageView(context)
            SlotAssignment.Minigames -> MinigamesPageView(context)
            SlotAssignment.Cloud -> CloudPageView(context)
            SlotAssignment.Notifications -> NotificationsPageView(context)
            SlotAssignment.Meshvoice -> com.prism.launcher.calls.MeshvoicePageView(context)
            SlotAssignment.Maps -> MapsPageView(context)
            SlotAssignment.Mouse -> MousePageView(context)
            SlotAssignment.Photos -> com.prism.launcher.photos.PhotosPageView(context)
            SlotAssignment.PhotoPix -> com.prism.launcher.photopix.PhotoPixPageView(context)
            SlotAssignment.VideoCraft -> com.prism.launcher.videocraft.VideoCraftPageView(context)
            // The ImageCraft page takes a plain Context, so it can be drawn on a television too: the
            // generation itself happens in a service, and "which app is on screen" is not part of
            // what it draws. Starting a render from a display still renders on this phone, which the
            // page's own progress line says.
            SlotAssignment.ImageCraft -> com.prism.launcher.imagecraft.ImageCraftPageView(context)
            // AudioCraft takes a plain Context too: a television can browse the library and play a
            // file, and the reading itself is done by whichever device started it.
            SlotAssignment.AudioCraft -> com.prism.launcher.audiocraft.AudioCraftPageView(context)
            SlotAssignment.ModelCraft -> com.prism.launcher.modelcraft.ModelCraftPageView(context)
            // DatasetCraft takes a plain Context: a television can build a dataset as well as a phone
            // can, and the files are copied on the device the page is drawn on.
            SlotAssignment.DatasetCraft -> com.prism.launcher.datasetcraft.DatasetCraftPageView(context)
            // AssetCraft takes a plain Context: a television can orbit the same scene and press
            // Generate; the work is done by the model on this device either way.
            SlotAssignment.AssetCraft -> com.prism.launcher.assetcraft.AssetCraftPageView(context)
            // GameCraft takes a plain Context: a display can browse saved games and run the maker
            // on this phone, with its model gates evaluated on this device.
            SlotAssignment.GameCraft -> com.prism.launcher.gamecraft.GameCraftPageView(context)
            SlotAssignment.ModelStore -> com.prism.launcher.ModelStorePageView(context)
            SlotAssignment.AgenticTools -> com.prism.launcher.agentic.AgenticToolsPageView(context)
            SlotAssignment.Wallet -> WalletPageView(context)

            // Mail on a television is read-only in practice: the server page reports state, and the
            // client can browse. Both take a plain Context, so a display gets the same view.
            SlotAssignment.EmailServer -> com.prism.launcher.email.EmailServerPageView(context)
            SlotAssignment.EmailClient -> com.prism.launcher.email.EmailClientPageView(context)

            // Both take a plain Context: a television can read the journal and list snapshots, and
            // a snapshot started from the television is still taken by this phone.
            SlotAssignment.TimeMachine -> com.prism.launcher.timemachine.TimeMachinePageView(context)
            SlotAssignment.Journal -> com.prism.launcher.journal.JournalPageView(context)
            SlotAssignment.Podcasts -> com.prism.launcher.podcasts.PodcastsPageView(context)
            // The store can be browsed from a television; publishing needs a file picker and is
            // therefore a phone or desktop action, which the page gates on its own.
            SlotAssignment.AppStore -> com.prism.launcher.appstore.AppStorePageView(context)
            // Helper takes a plain Context too: a display can watch a bill fill up and read
            // who paid. Uploading needs a PDF picker, which only a real window has, and the
            // page says so rather than failing when it is pressed.
            SlotAssignment.Helper -> com.prism.launcher.helper.HelperPageView(context)
            SlotAssignment.Vpn -> com.prism.launcher.vpn.VpnPageView(context)
            // A television can inspect the same process table and files as the phone; the kill
            // result is reported on the page either way.
            SlotAssignment.TaskManager -> com.prism.launcher.taskmanager.TaskManagerPageView(context)

            // The social surface is the one page whose constructor requires the launcher itself.
            SlotAssignment.NebulaSocial ->
                message(context, "The social page needs the Prism window that is sending it.")

            is SlotAssignment.Custom -> {
                val loaded = com.prism.launcher.DynamicPageLoader.load(
                    context,
                    assignment.packageName,
                    assignment.viewClassName,
                )
                loaded.view ?: message(context, loaded.error ?: "The page plugin could not load.")
            }

            SlotAssignment.Default -> grid(context, index)
        }

    private fun grid(context: Context, index: Int): android.view.View =
        DesktopGridPage(
            context,
            index,
            onLaunch = {},
            onDataChanged = {},
            acceptDrawerDrops = { false },
        )

    private fun message(context: Context, text: String): android.view.View =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#99FFFFFF"))
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }
}
