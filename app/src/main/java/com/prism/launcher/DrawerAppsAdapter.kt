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

data class DrawerAppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Drawable?,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED
)

sealed class DrawerItem {
    data class Group(val title: String, val apps: List<DrawerAppEntry>) : DrawerItem()
    data class App(val entry: DrawerAppEntry) : DrawerItem()
}

private object DrawerDiff : DiffUtil.ItemCallback<DrawerItem>() {
    override fun areItemsTheSame(old: DrawerItem, new: DrawerItem): Boolean {
        if (old is DrawerItem.Group && new is DrawerItem.Group) return old.title == new.title
        if (old is DrawerItem.App && new is DrawerItem.App) return old.entry.component == new.entry.component
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
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DrawerItem.App -> VIEW_TYPE_APP
            is DrawerItem.Group -> VIEW_TYPE_GROUP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GROUP) {
            GroupVH(ItemDrawerGroupBinding.inflate(inflater, parent, false))
        } else {
            AppVH(ItemDrawerAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
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
