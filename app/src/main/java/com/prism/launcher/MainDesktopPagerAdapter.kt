package com.prism.launcher

import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val assignment = assignments[position]
        val key = cacheKey(position, assignment)
        if (holder.boundKey == key && holder.container.childCount > 0) return
        holder.boundKey = key
        holder.container.removeAllViews()
        val page = buildPage(holder.container.context, position, assignment)
        val laidOut = if (windowed) {
            windowFrame(
                holder.container.context,
                pageLabel(assignment),
                position + 1,
                page,
            )
        } else {
            page
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
    ): View {
        val theme = com.prism.launcher.theme.PrismThemes.active()
        val mode = if (com.prism.launcher.nora.IosUi.isDark(ctx)) theme.dark else theme.light
        val palette = mode.palette
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()

        val outer = FrameLayout(ctx)
        outer.setPadding(dp(14f), dp(10f), dp(14f), dp(18f))

        val window = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            elevation = dp(9f).toFloat()
            background = GradientDrawable().apply {
                // The card colour, not the page background: a window sits ON something.
                setColor(palette.card.toInt())
                cornerRadius = dp(mode.shape.card.toFloat()).toFloat()
                setStroke(dp(1f), palette.divider.toInt())
            }
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(10f), 0)
        }
        bar.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(palette.accent.toInt())
                cornerRadius = dp(3f).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(7f), dp(7f)))
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 11f
            paint.isFakeBoldText = true
            setTextColor(palette.text.toInt())
            setPadding(dp(9f), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(ctx).apply {
            // Which page of the pager this window is. Decoration would be a lie if it looked like
            // a control, so it is a count and nothing else.
            text = number.toString()
            textSize = 10f
            setTextColor(palette.faint.toInt())
        })
        window.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34f),
        ))
        window.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        outer.addView(window, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return outer
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
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.container.removeAllViews()
        holder.boundKey = null
        super.onViewRecycled(holder)
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
    }
}
