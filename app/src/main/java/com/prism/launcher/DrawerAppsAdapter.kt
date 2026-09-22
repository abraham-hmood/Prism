package com.prism.launcher

import android.content.ClipData
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemDrawerAppBinding
import com.prism.launcher.databinding.ItemDrawerGroupBinding
import com.prism.launcher.databinding.ItemDrawerNotificationBinding
import com.prism.launcher.databinding.ItemDrawerWidgetBinding
import com.prism.launcher.databinding.ItemDrawerSectionBinding
import com.prism.launcher.notifications.NotificationHistory

data class DrawerAppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Drawable?,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED
)

sealed class DrawerItem {
    data class Group(val title: String, val apps: List<DrawerAppEntry>) : DrawerItem()
    data class App(val entry: DrawerAppEntry) : DrawerItem()

    /** A heading between kinds of result, so a notification list below the apps is labelled. */
    data class Section(val title: String) : DrawerItem()

    /**
     * A notification the search matched.
     *
     * Search reaches notification history as well as apps because the two questions are the same
     * question: someone typing "monzo" is looking for the bank, and whether what they want is the
     * app or the message it sent an hour ago is not something the search box can know. Showing both
     * costs one section heading and answers both.
     */
    data class Notification(val record: NotificationHistory.Record) : DrawerItem()

    /**
     * A widget the search matched, draggable onto a desktop page.
     *
     * Found by the same box that finds apps, because a user looking for their clock does not think
     * of "the Clock app" and "the Clock widget" as two searches. [label] is the widget's own label
     * and [appLabel] the app it came from, so both are searchable -- "google" should find the search
     * bar widget even though the widget is called "Search".
     */
    data class Widget(
        val provider: String,
        val label: String,
        val appLabel: String,
        val previewImage: Int,
        val icon: Int,
    ) : DrawerItem()
}

private object DrawerDiff : DiffUtil.ItemCallback<DrawerItem>() {
    override fun areItemsTheSame(old: DrawerItem, new: DrawerItem): Boolean {
        if (old is DrawerItem.Group && new is DrawerItem.Group) return old.title == new.title
        if (old is DrawerItem.App && new is DrawerItem.App) return old.entry.component == new.entry.component
        if (old is DrawerItem.Section && new is DrawerItem.Section) return old.title == new.title
        if (old is DrawerItem.Widget && new is DrawerItem.Widget) return old.provider == new.provider
        if (old is DrawerItem.Notification && new is DrawerItem.Notification) {
            // Identity is the app plus when it arrived. The stored key would be better but is empty
            // for anything recorded before a key was available, and two notifications from one app in
            // the same millisecond is not a case worth carrying a field for.
            return old.record.packageName == new.record.packageName && old.record.at == new.record.at
        }
        return false
    }

    override fun areContentsTheSame(old: DrawerItem, new: DrawerItem) = old == new
}

class DrawerAppsAdapter(
    private val onLaunch: (ComponentName) -> Unit,
    private val allowDragToDesktop: () -> Boolean,
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DrawerDiff) {

    companion object {
        const val VIEW_TYPE_APP = 1
        const val VIEW_TYPE_GROUP = 2
        const val VIEW_TYPE_SECTION = 3
        const val VIEW_TYPE_NOTIFICATION = 4
        const val VIEW_TYPE_WIDGET = 5
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DrawerItem.App -> VIEW_TYPE_APP
            is DrawerItem.Group -> VIEW_TYPE_GROUP
            is DrawerItem.Section -> VIEW_TYPE_SECTION
            is DrawerItem.Notification -> VIEW_TYPE_NOTIFICATION
            is DrawerItem.Widget -> VIEW_TYPE_WIDGET
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_GROUP -> GroupVH(ItemDrawerGroupBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SECTION ->
                SectionVH(ItemDrawerSectionBinding.inflate(inflater, parent, false))
            VIEW_TYPE_NOTIFICATION ->
                NotificationVH(ItemDrawerNotificationBinding.inflate(inflater, parent, false))
            VIEW_TYPE_WIDGET ->
                WidgetVH(ItemDrawerWidgetBinding.inflate(inflater, parent, false))
            else -> AppVH(ItemDrawerAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is SectionVH && item is DrawerItem.Section) {
            holder.binding.sectionTitle.text = item.title
            return
        }
        if (holder is NotificationVH && item is DrawerItem.Notification) {
            bindNotification(holder, item.record)
            return
        }
        if (holder is WidgetVH && item is DrawerItem.Widget) {
            bindWidget(holder, item)
            return
        }
        if (holder is AppVH && item is DrawerItem.App) {
            bindApp(holder, item.entry)
        } else if (holder is GroupVH && item is DrawerItem.Group) {
            holder.binding.groupTitle.text = item.title
            val innerAdapter = InnerGroupAdapter(onLaunch, allowDragToDesktop)
            holder.binding.groupRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                holder.itemView.context, RecyclerView.HORIZONTAL, false
            )
            holder.binding.groupRecycler.adapter = innerAdapter
            innerAdapter.submitList(item.apps)
        }
    }

    private fun bindWidget(holder: WidgetVH, item: DrawerItem.Widget) {
        val context = holder.itemView.context
        holder.binding.widgetLabel.text = item.label
        holder.binding.widgetApp.text = item.appLabel

        // The provider's preview if it published one, its icon if not. A widget with neither is
        // shown with the generic icon rather than an empty box, which at least says "widget".
        val preview = runCatching {
            val resources = context.packageManager.getResourcesForApplication(
                android.content.ComponentName.unflattenFromString(item.provider)!!.packageName
            )
            when {
                item.previewImage != 0 -> resources.getDrawable(item.previewImage, null)
                item.icon != 0 -> resources.getDrawable(item.icon, null)
                else -> null
            }
        }.getOrNull()
        if (preview != null) {
            holder.binding.widgetPreview.setImageDrawable(preview)
        } else {
            holder.binding.widgetPreview.setImageResource(android.R.drawable.ic_menu_add)
        }

        // Tapping places it on the first desktop page; see LauncherActivity.placeWidgetOnDesktop for
        // why that exists next to the drag.
        holder.itemView.setOnClickListener {
            (context as? com.prism.launcher.LauncherActivity)?.placeWidgetOnDesktop(item.provider)
        }

        // Dragged exactly the way an app is, so the desktop's existing drop handling is what
        // receives it -- see WidgetPlacement for what happens on the other side.
        holder.itemView.setOnLongClickListener {
            if (!allowDragToDesktop()) return@setOnLongClickListener false
            val clip = ClipData.newPlainText(
                com.prism.launcher.widgets.WidgetPlacement.DRAG_LABEL, item.provider
            )
            val shadow = View.DragShadowBuilder(holder.itemView)
            holder.itemView.startDragAndDrop(clip, shadow, null, View.DRAG_FLAG_GLOBAL)
            true
        }
    }

    private fun bindNotification(holder: NotificationVH, record: NotificationHistory.Record) {
        val context = holder.itemView.context
        holder.binding.notificationApp.text = record.appLabel
        holder.binding.notificationTitle.text = record.title
        holder.binding.notificationTitle.visibility =
            if (record.title.isBlank()) ViewGroup.GONE else ViewGroup.VISIBLE
        holder.binding.notificationText.text = record.text
        holder.binding.notificationText.visibility =
            if (record.text.isBlank()) ViewGroup.GONE else ViewGroup.VISIBLE
        holder.binding.notificationWhen.text =
            android.text.format.DateUtils.getRelativeTimeSpanString(
                record.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
        holder.binding.notificationIcon.setImageDrawable(
            runCatching { context.packageManager.getApplicationIcon(record.packageName) }.getOrNull()
        )
        holder.itemView.setOnClickListener {
            runCatching {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(record.packageName) ?: return@runCatching
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private fun bindApp(holder: AppVH, e: DrawerAppEntry) {
        holder.binding.drawerLabel.text = e.label
        val context = holder.itemView.context
        val iconPack = PrismSettings.getIconPackPackage()
        
        val customIcon = if (iconPack.isNotEmpty()) {
            IconPackEngine.getIconPackDrawable(context, e.component, iconPack)
        } else null

        if (customIcon != null) {
            holder.binding.drawerIcon.setImageDrawable(customIcon)
            holder.binding.iconWrapper.background = null
        } else {
            holder.binding.drawerIcon.setImageDrawable(e.icon)
            // Reused across rebinds of this ViewHolder rather than allocated fresh every bind --
            // it's never shared between two Views at once (bounds are reset by the layout pass
            // each time this same View is measured), so this is safe, just not-per-bind.
            val glow = holder.glowDrawable ?: NeonGlowDrawable(
                color = PrismSettings.getGlowColor(),
                cornerRadius = 24f * context.resources.displayMetrics.density,
                strokeWidth = 3f * context.resources.displayMetrics.density
            ).also { holder.glowDrawable = it }
            glow.color = PrismSettings.getGlowColor()
            holder.binding.iconWrapper.background = glow
        }
        holder.itemView.setTag(R.id.tag_prism_launcher_app_target, true)
        holder.binding.drawerIcon.setTag(R.id.tag_prism_launcher_app_target, true)
        holder.itemView.setOnClickListener { onLaunch(e.component) }
        holder.itemView.setOnLongClickListener {
            if (!allowDragToDesktop()) return@setOnLongClickListener false
            val clip = ClipData.newPlainText("prism_app", e.component.flattenToString())
            val shadow = View.DragShadowBuilder(holder.binding.drawerIcon)
            val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                holder.itemView.startDragAndDrop(clip, shadow, e.component, View.DRAG_FLAG_GLOBAL)
            } else {
                @Suppress("DEPRECATION")
                holder.itemView.startDrag(clip, shadow, e.component, 0)
            }
            started
        }
    }

    class AppVH(val binding: ItemDrawerAppBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Reused across rebinds of this holder -- see [bindApp] and [InnerGroupAdapter.onBindViewHolder]. */
        var glowDrawable: NeonGlowDrawable? = null

        init {
            // NeonGlowDrawable's BlurMaskFilter isn't supported by hardware-accelerated Canvas.
            // Set once here, not per-bind -- same reasoning as IosSegmentedControl's init block.
            binding.iconWrapper.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
    class GroupVH(val binding: ItemDrawerGroupBinding) : RecyclerView.ViewHolder(binding.root)

    class SectionVH(val binding: ItemDrawerSectionBinding) : RecyclerView.ViewHolder(binding.root)

    class NotificationVH(val binding: ItemDrawerNotificationBinding) :
        RecyclerView.ViewHolder(binding.root)

    class WidgetVH(val binding: ItemDrawerWidgetBinding) : RecyclerView.ViewHolder(binding.root)

    class InnerGroupAdapter(
        private val onLaunch: (ComponentName) -> Unit,
        private val allowDragToDesktop: () -> Boolean,
    ) : ListAdapter<DrawerAppEntry, AppVH>(object : DiffUtil.ItemCallback<DrawerAppEntry>() {
        override fun areItemsTheSame(old: DrawerAppEntry, new: DrawerAppEntry) = old.component == new.component
        override fun areContentsTheSame(old: DrawerAppEntry, new: DrawerAppEntry) = old.label == new.label && old.icon === new.icon
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
            return AppVH(ItemDrawerAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: AppVH, position: Int) {
            val e = getItem(position)
            holder.binding.drawerLabel.text = e.label
            val context = holder.itemView.context
            // Simple generic rendering for inner group nodes
            holder.binding.drawerIcon.setImageDrawable(e.icon)
            val glow = holder.glowDrawable ?: NeonGlowDrawable(
                color = PrismSettings.getGlowColor(),
                cornerRadius = 16f * context.resources.displayMetrics.density,
                strokeWidth = 2f * context.resources.displayMetrics.density
            ).also { holder.glowDrawable = it }
            glow.color = PrismSettings.getGlowColor()
            holder.binding.iconWrapper.background = glow
            holder.itemView.setOnClickListener { onLaunch(e.component) }
            holder.itemView.setOnLongClickListener {
                if (!allowDragToDesktop()) return@setOnLongClickListener false
                val clip = ClipData.newPlainText("prism_app", e.component.flattenToString())
                val shadow = View.DragShadowBuilder(holder.binding.drawerIcon)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    holder.itemView.startDragAndDrop(clip, shadow, e.component, View.DRAG_FLAG_GLOBAL)
                } else {
                    @Suppress("DEPRECATION")
                    holder.itemView.startDrag(clip, shadow, e.component, 0)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// PackageManager helpers
// ---------------------------------------------------------------------------

fun loadLauncherApps(pm: PackageManager): List<DrawerAppEntry> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }
    val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return list
        .sortedBy { it.loadLabel(pm).toString().lowercase() }
        .map { it.toEntry(pm) }
}

private fun ResolveInfo.toEntry(pm: PackageManager): DrawerAppEntry {
    val cn = ComponentName(activityInfo.packageName, activityInfo.name)
    val cat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        activityInfo.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
    } else {
        ApplicationInfo.CATEGORY_UNDEFINED
    }
    return DrawerAppEntry(
        component = cn,
        label = loadLabel(pm).toString(),
        icon = try { loadIcon(pm) } catch (_: Throwable) { null },
        category = cat
    )
}

fun groupDrawerApps(apps: List<DrawerAppEntry>): List<DrawerItem> {
    val result = mutableListOf<DrawerItem>()
    val remainingApps = apps.toMutableList()

    // 1. Group by Explicit Category if mapped >= 2 instances
    val categories = remainingApps.groupBy { it.category }
    for ((cat, catApps) in categories) {
        if (cat != ApplicationInfo.CATEGORY_UNDEFINED && catApps.size >= 2) {
            val title = when (cat) {
                ApplicationInfo.CATEGORY_GAME -> "Games"
                ApplicationInfo.CATEGORY_AUDIO -> "Audio & Music"
                ApplicationInfo.CATEGORY_VIDEO -> "Video"
                ApplicationInfo.CATEGORY_IMAGE -> "Photography"
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_NEWS -> "News"
                ApplicationInfo.CATEGORY_MAPS -> "Maps & Navigation"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                else -> "Applications"
            }
            result.add(DrawerItem.Group(title, catApps))
            remainingApps.removeAll(catApps)
        }
    }

    // 2. Group by Developer (first two namespaces: e.g. com.google, com.microsoft)
    val devGroups = remainingApps.groupBy {
        val parts = it.component.packageName.split(".")
        if (parts.size >= 2) "${parts[0]}.${parts[1]}" else it.component.packageName
    }
    for ((dev, devApps) in devGroups) {
        if (devApps.size >= 3) {
            val friendlyName = dev.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Developer"
            result.add(DrawerItem.Group("$friendlyName Collection", devApps))
            remainingApps.removeAll(devApps)
        }
    }

    // 3. Add remaining apps generically
    result.addAll(remainingApps.map { DrawerItem.App(it) })
    return result
}
