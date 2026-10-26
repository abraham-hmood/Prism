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
            SlotAssignment.ModelStore -> com.prism.launcher.ModelStorePageView(context)
            SlotAssignment.AgenticTools -> com.prism.launcher.agentic.AgenticToolsPageView(context)
            SlotAssignment.Wallet -> WalletPageView(context)

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
