package com.prism.launcher

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
) : RecyclerView.Adapter<MainDesktopPagerAdapter.PageHolder>() {

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
        holder.container.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
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
            )
            SlotAssignment.AppDrawer -> DrawerPageView(
                ctx,
                onLaunch,
                allowDragToDesktop = allowDrawerDrag,
            )

            is SlotAssignment.Custom -> {
                val v = DynamicPageLoader.inflateView(
                    ctx,
                    assignment.packageName,
                    assignment.viewClassName,
                )
                v ?: errorView(ctx)
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

            SlotAssignment.ModelStore -> ModelStorePageView(activity)

            SlotAssignment.AgenticTools -> com.prism.launcher.agentic.AgenticToolsPageView(activity)

            SlotAssignment.Wallet -> com.prism.launcher.wallet.WalletPageView(activity)

            SlotAssignment.Default -> DesktopGridPage(
                ctx,
                position,
                onLaunch = onLaunch,
                onDataChanged = onDesktopChanged,
                acceptDrawerDrops = acceptDesktopDrawerDrops,
            )
        }
    }

    private fun errorView(ctx: android.content.Context): View {
        return TextView(ctx).apply {
            text = ctx.getString(R.string.failed_load_page)
            setTextColor(ContextCompat.getColor(ctx, R.color.prism_text_muted))
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
    }

    private fun cacheKey(position: Int, assignment: SlotAssignment): String =
        "$position:${assignment.serialize()}"

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var boundKey: String? = null
    }
}
