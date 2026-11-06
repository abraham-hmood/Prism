package com.prism.launcher

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.browser.BrowserPageView
import com.prism.launcher.files.FileExplorerPageView
import com.prism.launcher.messaging.MessagingPageView
import com.prism.launcher.virtualization.VirtualizationPageView

class MainDesktopPagerAdapter(
    private val activity: LauncherActivity,
    private val desktopShortcutStore: DesktopShortcutStore,
    private var assignments: List<SlotAssignment>,
    private val onLaunch: (ComponentName) -> Unit,
    private val onDesktopChanged: () -> Unit,
    private val allowDrawerDrag: () -> Boolean,
    private val acceptDesktopDrawerDrops: () -> Boolean,
    /** Long-press on an empty desktop cell asks for the page picker for that slot. */
    private val onRequestPagePicker: (Int) -> Unit,
) : RecyclerView.Adapter<MainDesktopPagerAdapter.PageHolder>() {

    /**
     * Whether this build draws pages as windows.
     *
     * A field rather than a call at bind time so [refreshWindowMode] can notice a change and
     * rebind, and part of the bind cache key so a mode switch actually rebuilds the page rather
     * than leaving the old chrome around a new one.
     */
    private var windowed = com.prism.launcher.PrismSettings.getWindowedPages()

    /** Re-reads the windowed setting; true when it changed and the pages were rebuilt. */
    fun refreshWindowMode(): Boolean {
        val next = com.prism.launcher.PrismSettings.getWindowedPages()
        if (next == windowed) return false
        windowed = next
        // Minimised slots belong to the frame that no longer exists in the other mode.
        if (!next) minimized.clear()
        notifyDataSetChanged()
        return true
    }

    fun updateAssignments(next: List<SlotAssignment>) {
        assignments = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = assignments.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return PageHolder(container)
    }

    /**
     * Attached holders by slot, for the page offloader.
     *
     * Only ATTACHED ones: a recycled holder has already dropped its view (see [onViewRecycled]), so
     * it holds nothing worth reclaiming and remembering it would keep the holder itself alive.
     */
    private val attached = HashMap<Int, PageHolder>()

    /** Windows the user put on the hotseat. Kept by slot so a rebind does not resurrect them. */
    private val minimized = HashSet<Int>()

    fun isWindowMinimized(slot: Int): Boolean = slot in minimized

    fun noteWindowMinimized(slot: Int) { minimized.add(slot) }

    /**
     * Puts a minimised window back.
     *
     * The holder may not be attached if the pager is on another page, in which case the slot is
     * removed from [minimized] and [onBindViewHolder] will show it when the pager arrives. Calling
     * this for an unattached slot is therefore useful rather than a no-op.
     */
    fun restoreWindow(slot: Int) {
        minimized.remove(slot)
        val wrapper = attached[slot]?.container?.getChildAt(0) as? PageWindowView ?: return
        wrapper.visibility = View.VISIBLE
        wrapper.restore()
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val assignment = assignments[position]
        val key = cacheKey(position, assignment)
        attached[position] = holder
        holder.slot = position
        if (holder.boundKey == key && holder.container.childCount > 0) {
            // A page that is being rebuilt after an offload must drop its thumbnail, or the stale
            // screenshot would sit under the new page for ever.
            com.prism.launcher.offload.PageOffload.forget(position)
            return
        }
        holder.boundKey = key
        holder.container.removeAllViews()
        com.prism.launcher.offload.PageOffload.forget(position)
        val page = buildPage(holder.container.context, position, assignment)
        val laidOut = if (windowed) {
            windowFrame(
                holder.container.context,
                pageLabel(assignment),
                position + 1,
                page,
                position,
            )
        } else {
            page
        }
        if (laidOut is PageWindowView && position in minimized) {
            laidOut.visibility = View.GONE
        }
        holder.container.addView(
            laidOut,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * A page as a window: inset, rounded, titled, in the active theme's own surfaces.
     *
     * THE CHROME IS THE THEME'S, not a neutral grey. A windowed xOS page is a macOS panel with
     * its 10 pt corners and hairline, a windowed Fusion page is a floating card with Fusion's
     * border -- otherwise the switch would turn every theme into the same application, which is
     * the opposite of what a theme switch is for. Every value here comes from the active spec.
     *
     * It is a plain frame rather than a floating-window system: pages are still pages, paged by
     * the same ViewPager2, just no longer edge to edge. A page that opened in its own movable OS
     * window would need a window manager and would stop being a page in the pager.
     */
    private fun windowFrame(
        ctx: android.content.Context,
        title: String,
        number: Int,
        content: View,
        slot: Int,
    ): View = PageWindowView(ctx, title, number, content) {
        minimized.add(slot)
        activity.onPageWindowMinimized(slot, title)
    }

    /** The name of what a slot shows, for the window's title bar. */
    private fun pageLabel(assignment: SlotAssignment): String = when (assignment) {
        SlotAssignment.Default, SlotAssignment.DesktopGrid -> "Desktop"
        SlotAssignment.Browser -> "Browser"
        SlotAssignment.AppDrawer -> "Apps"
        SlotAssignment.Messaging -> "Messages"
        SlotAssignment.KineticHalo -> "Kinetic Halo"
        SlotAssignment.FileExplorer -> "Files"
        SlotAssignment.NebulaSocial -> "Nebula"
        is SlotAssignment.Custom -> assignment.packageName.substringAfterLast('.')
        SlotAssignment.VirtualizationOs -> "Virtualization"
        SlotAssignment.Models -> "Models"
        SlotAssignment.ModelStore -> "Model store"
        SlotAssignment.AgenticTools -> "Agentic tools"
        SlotAssignment.Wallet -> "Wallet"
        SlotAssignment.Editor -> "Editor"
        SlotAssignment.Science -> "Science"
        SlotAssignment.Notifications -> "Notifications"
        SlotAssignment.Language -> "Language"
        SlotAssignment.Minigames -> "Games"
        SlotAssignment.Cloud -> "Cloud"
        SlotAssignment.Meshvoice -> "Meshvoice"
        SlotAssignment.Maps -> "Maps"
        SlotAssignment.Mouse -> "Mouse"
        SlotAssignment.Photos -> "Photos"
        SlotAssignment.PhotoPix -> "PhotoPix"
        SlotAssignment.VideoCraft -> "VideoCraft"
        SlotAssignment.ImageCraft -> "ImageCraft"
        SlotAssignment.AudioCraft -> "AudioCraft"
        SlotAssignment.ModelCraft -> "ModelCraft"
        SlotAssignment.DatasetCraft -> "DatasetCraft"
        SlotAssignment.AssetCraft -> "AssetCraft"
        SlotAssignment.GameCraft -> "GameCraft"
        SlotAssignment.EmailServer -> "Email server"
        SlotAssignment.EmailClient -> "Email"
        SlotAssignment.TimeMachine -> "Time Machine"
        SlotAssignment.Journal -> "Journal"
        SlotAssignment.Podcasts -> "Podcasts"
        SlotAssignment.AppStore -> "App Store"
        SlotAssignment.Helper -> "Helper"
        SlotAssignment.Vpn -> "VPN"
        SlotAssignment.TaskManager -> "Task Manager"
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.container.removeAllViews()
        holder.boundKey = null
        if (holder.slot >= 0) {
            attached.remove(holder.slot)
            com.prism.launcher.offload.PageOffload.forget(holder.slot)
        }
        holder.slot = -1
        super.onViewRecycled(holder)
    }

    /**
     * Releases a page that is not the current one, keeping only a thumbnail in Prism Swap.
     *
     * THE BUSY CHECK COMES FIRST. A page that reports background work -- a render, a download, a
     * video that is still decoding -- keeps its view no matter what the toggle says: the memory is
     * recoverable, the work is not.
     */
    fun offloadIfIdle(position: Int): Boolean {
        if (position == bindingSource()) return false
        val holder = attached[position] ?: return false
        if (com.prism.launcher.offload.PageOffload.isOffloaded(position)) return false
        val page = holder.container.getChildAt(0) ?: return false
        val busy = page as? com.prism.launcher.offload.BusyPage
        if (busy != null && busy.hasBackgroundWork()) return false
        if (!com.prism.launcher.offload.PageOffload.stashView(position, page)) return false
        holder.container.removeAllViews()
        holder.boundKey = null
        // The thumbnail goes in the container so a swipe toward the page shows the last thing it
        // looked like rather than a hole; the real view is rebuilt as it comes into range.
        com.prism.launcher.offload.PageOffload.thumbnail(position)?.let { thumb ->
            holder.container.addView(
                android.widget.ImageView(holder.container.context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    setImageBitmap(thumb)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return true
    }

    /** The page ViewPager2 is showing, which must never be offloaded. */
    var currentPosition: Int = 0

    private fun bindingSource(): Int = currentPosition

    /** Rebinds every offloaded slot near [current], so it is real again before it can be seen. */
    fun restoreAround(current: Int, radius: Int = 2) {
        for (slot in (current - radius)..(current + radius)) {
            if (slot < 0 || slot >= assignments.size) continue
            if (!com.prism.launcher.offload.PageOffload.isOffloaded(slot)) continue
            attached[slot]?.boundKey = null
            notifyItemChanged(slot)
        }
    }

    /** Offloads every attached page except the current one. Returns how many were released. */
    fun offloadInactive(): Int {
        var released = 0
        for (position in attached.keys.toList()) {
            if (position == currentPosition) continue
            if (offloadIfIdle(position)) released++
        }
        return released
    }

    private fun buildPage(ctx: android.content.Context, position: Int, assignment: SlotAssignment): View {
        return when (assignment) {
            SlotAssignment.Browser -> BrowserPageView(activity)
            SlotAssignment.DesktopGrid -> DesktopGridPage(
                ctx,
                position,
                onLaunch = onLaunch,
                onDataChanged = onDesktopChanged,
                acceptDrawerDrops = acceptDesktopDrawerDrops,
                onRequestPagePicker = onRequestPagePicker,
            )
            SlotAssignment.AppDrawer -> DrawerPageView(
                ctx,
                onLaunch,
                allowDragToDesktop = allowDrawerDrag,
            ).apply {
                onShowAppMenu = { entry, anchor -> activity.showAppContextMenu(entry, anchor) }
            }

            is SlotAssignment.Custom -> {
                val loaded = DynamicPageLoader.load(
                    ctx,
                    assignment.packageName,
                    assignment.viewClassName,
                )
                loaded.view ?: errorView(ctx, loaded.error)
            }

            SlotAssignment.Messaging -> MessagingPageView(activity)

            SlotAssignment.NebulaSocial -> com.prism.launcher.social.NebulaSocialPageView(activity)

            SlotAssignment.KineticHalo -> KineticHaloPageView(activity, onLaunch)
            
            SlotAssignment.FileExplorer -> FileExplorerPageView(ctx)

            SlotAssignment.VirtualizationOs ->
                com.prism.launcher.virtualization.VirtualizationPageView(activity)

            SlotAssignment.Models -> ModelsPageView(activity)
            SlotAssignment.Editor -> com.prism.launcher.editor.EditorPageView(activity)
            SlotAssignment.Science -> com.prism.launcher.science.SciencePageView(activity)
            SlotAssignment.Language -> com.prism.launcher.language.LanguagePageView(activity)
            SlotAssignment.Minigames -> com.prism.launcher.minigames.MinigamesPageView(activity)
            SlotAssignment.Cloud -> com.prism.launcher.cloud.CloudPageView(activity)
            SlotAssignment.Notifications ->
                com.prism.launcher.notifications.NotificationsPageView(activity)

            SlotAssignment.Meshvoice -> com.prism.launcher.calls.MeshvoicePageView(activity)
            SlotAssignment.Maps -> com.prism.launcher.maps.MapsPageView(activity)
            SlotAssignment.Mouse -> com.prism.launcher.mouse.MousePageView(activity)
            SlotAssignment.Photos -> com.prism.launcher.photos.PhotosPageView(activity)
            SlotAssignment.PhotoPix -> com.prism.launcher.photopix.PhotoPixPageView(activity)
            SlotAssignment.VideoCraft -> com.prism.launcher.videocraft.VideoCraftPageView(activity)
            SlotAssignment.ImageCraft -> com.prism.launcher.imagecraft.ImageCraftPageView(activity)
            SlotAssignment.AudioCraft -> com.prism.launcher.audiocraft.AudioCraftPageView(activity)
            SlotAssignment.ModelCraft -> com.prism.launcher.modelcraft.ModelCraftPageView(activity)
            SlotAssignment.DatasetCraft -> com.prism.launcher.datasetcraft.DatasetCraftPageView(activity)
            SlotAssignment.AssetCraft -> com.prism.launcher.assetcraft.AssetCraftPageView(activity)
            SlotAssignment.GameCraft -> com.prism.launcher.gamecraft.GameCraftPageView(activity)

            SlotAssignment.EmailServer -> com.prism.launcher.email.EmailServerPageView(activity)
            SlotAssignment.EmailClient -> com.prism.launcher.email.EmailClientPageView(activity)

            SlotAssignment.TimeMachine -> com.prism.launcher.timemachine.TimeMachinePageView(activity)
            SlotAssignment.Journal -> com.prism.launcher.journal.JournalPageView(activity)
            SlotAssignment.Podcasts -> com.prism.launcher.podcasts.PodcastsPageView(activity)
            SlotAssignment.AppStore -> com.prism.launcher.appstore.AppStorePageView(activity)
            SlotAssignment.Helper -> com.prism.launcher.helper.HelperPageView(activity)
            SlotAssignment.Vpn -> com.prism.launcher.vpn.VpnPageView(activity)
            SlotAssignment.TaskManager -> com.prism.launcher.taskmanager.TaskManagerPageView(activity)

            SlotAssignment.ModelStore -> ModelStorePageView(activity)

            SlotAssignment.AgenticTools -> com.prism.launcher.agentic.AgenticToolsPageView(activity)

            SlotAssignment.Wallet -> com.prism.launcher.wallet.WalletPageView(activity)

            SlotAssignment.Default -> DesktopGridPage(
                ctx,
                position,
                onLaunch = onLaunch,
                onDataChanged = onDesktopChanged,
                acceptDrawerDrops = acceptDesktopDrawerDrops,
                onRequestPagePicker = onRequestPagePicker,
            )
        }
    }

    /**
     * What a page shows when it cannot be built.
     *
     * The plugin's own failure text when there is one -- a blank slot told nobody whether the
     * plugin was missing, misconfigured, or simply not installed.
     */
    private fun errorView(ctx: android.content.Context, reason: String?): View {
        return TextView(ctx).apply {
            text = reason?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.failed_load_page)
            setTextColor(com.prism.launcher.nora.IosUi.secondaryLabel(ctx))
            textSize = 15f
            setPadding(48, 48, 48, 48)
        }
    }

    private fun cacheKey(position: Int, assignment: SlotAssignment): String =
        "$position:${assignment.serialize()}:$windowed"

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var boundKey: String? = null
        var slot: Int = -1
    }
}
