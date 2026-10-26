package com.prism.launcher

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemDesktopCellBinding

class DesktopGridAdapter(
    private val context: Context,
    private val store: DesktopShortcutStore,
    private val cellCount: Int,
    private val onDataChanged: () -> Unit,
    private val onLaunchApp: (ComponentName) -> Unit,
    private val onLaunchFile: (String) -> Unit,
    private val onLaunchFolder: (DesktopItem) -> Unit,
    private val onDragStarted: () -> Unit = {},
    private val onDragEnded: () -> Unit = {},
    /** Opens the same app menu as the drawer. The third argument is the old long-press drag. */
    private val onShowMenu: ((DrawerAppEntry, View, () -> Boolean) -> Boolean)? = null,
) : RecyclerView.Adapter<DesktopGridAdapter.VH>() {

    private val packageManager: PackageManager = context.packageManager

    var cells: Array<DesktopItem?> = store.readGrid(cellCount).toTypedArray()
    @Suppress("UNUSED_PARAMETER") // Keep signature same for now if needed, but unused
        private set

    /** Set by the adapter when a long-press drag starts; consumed once by the drop handler. */
    var dragSourceIndex: Int = -1
        private set

    fun refreshFromStore() {
        val previous = cells
        val next = store.readGrid(cellCount).toTypedArray()
        cells = next
        // Diffed rather than a blanket notifyDataSetChanged(): this is called on every desktop
        // page rebuild (including ViewPager2 recycling an offscreen page), and a full rebind
        // re-resolves every icon and reallocates every folder-preview bitmap even when nothing
        // on that page actually changed.
        for (i in 0 until minOf(previous.size, next.size)) {
            if (previous[i] != next[i]) notifyItemChanged(i)
        }
    }

    /**
     * Swaps two cells.
     *
     * REFUSES any swap involving a widget or a cell the widget covers. A widget is not one cell:
     * its [DesktopItem.Occupied] neighbours point back at its top-left index, and swapping it with
     * an icon left that index pointing at the wrong cell -- so the widget's own occupancy now said
     * some other icon was a widget, and the grid disagreed with itself. Better a move that does
     * nothing than a grid that lies about which cells are taken.
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in cells.indices || to !in cells.indices) return
        val a = cells[from] ?: return
        val b = cells[to]
        if (a.ownsCells() || b.ownsCells()) return
        val tmp = cells[from]; cells[from] = cells[to]; cells[to] = tmp
        persist(); notifyItemMoved(from, to)
    }

    private fun DesktopItem?.ownsCells(): Boolean =
        this is DesktopItem.Widget || this is DesktopItem.PluginWidget || this is DesktopItem.Occupied

    fun placeAt(index: Int, item: DesktopItem?) {
        if (index !in cells.indices) return
        cells[index] = item
        persist(); notifyItemChanged(index)
    }

    fun getItemAt(index: Int): DesktopItem? {
        if (index !in cells.indices) return null
        return cells[index]
    }

    // ── Remove helpers (for delete zone) ────────────────────────────────
    fun removeByComponentName(cn: ComponentName) {
        val i = cells.indexOfFirst { it is DesktopItem.App && it.component == cn }
        if (i != -1) { cells[i] = null; persist(); notifyItemChanged(i) }
    }
    fun removeByFilePath(path: String) {
        val i = cells.indexOfFirst {
            (it is DesktopItem.FileRef && it.absolutePath == path) ||
            (it is DesktopItem.DirectoryRef && it.absolutePath == path)
        }
        if (i != -1) { cells[i] = null; persist(); notifyItemChanged(i) }
    }
    fun removeByFolderId(folderId: String) {
        val i = cells.indexOfFirst { it is DesktopItem.Folder && it.folderId == folderId }
        if (i != -1) { cells[i] = null; persist(); notifyItemChanged(i) }
    }
    fun clearCellAt(index: Int) {
        if (index !in cells.indices) return
        cells[index] = null; persist(); notifyItemChanged(index)
    }

    fun persist() {
        store.writeGrid(cells.toList())
        onDataChanged()
    }

    // ────────────────────────────────────────────────────────────────────
    override fun getItemCount(): Int = cellCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDesktopCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = cells[position]
        holder.binding.icon.isVisible = item != null
        holder.binding.label.isVisible = item != null
        holder.binding.icon.colorFilter = null

        if (item != null) {
            when (item) {
                // A widget's own view is drawn by DesktopWidgetLayer, on top of the grid, because it
                // spans several cells and a RecyclerView cell cannot. The cell underneath it stays
                // blank and takes no clicks -- the widget above receives them.
                is DesktopItem.Widget, is DesktopItem.PluginWidget, is DesktopItem.Occupied -> {
                    holder.binding.icon.isVisible = false
                    holder.binding.label.isVisible = false
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.isClickable = false
                }
                is DesktopItem.App -> {
                    holder.binding.label.text = resolveLabel(item.component)
                    holder.binding.icon.setImageDrawable(resolveIcon(item.component))
                    holder.itemView.setOnClickListener { onLaunchApp(item.component) }
                }
                is DesktopItem.FileRef -> {
                    holder.binding.label.text = java.io.File(item.absolutePath).name
                    holder.binding.icon.setImageResource(android.R.drawable.ic_menu_crop)
                    holder.itemView.setOnClickListener { onLaunchFile(item.absolutePath) }
                }
                is DesktopItem.DirectoryRef -> {
                    holder.binding.label.text = item.name
                    holder.binding.icon.setImageResource(android.R.drawable.ic_dialog_email)
                    holder.binding.icon.setColorFilter(android.graphics.Color.parseColor("#FFD700"))
                    holder.itemView.setOnClickListener { onLaunchFolder(item) }
                }
                is DesktopItem.Folder -> {
                    holder.binding.label.text = item.name
                    drawFolderPreview(holder.binding.icon, item)
                    holder.itemView.setOnClickListener { onLaunchFolder(item) }
                }
                is DesktopItem.NetworkedFolder -> {
                    holder.binding.label.text = item.name
                    holder.binding.icon.setImageResource(android.R.drawable.stat_sys_download_done)
                    holder.binding.icon.setColorFilter(android.graphics.Color.parseColor("#00BA7C"))
                    holder.itemView.setOnClickListener { onLaunchFolder(item) }
                }
            }
            holder.itemView.setTag(R.id.tag_prism_launcher_app_target, true)

            val startDrag = { startItemDrag(holder, item) }
            // The menu hands the gesture back through this listener, on the cell itself: with a
            // menu open the grid is told not to intercept, so a listener on the list never sees
            // the move that should become the drag. See [MenuDragHandoff].
            val handoff = (context as? android.app.Activity)?.let { activity ->
                MenuDragHandoff(activity, startDrag).also { holder.handoff = it }
            }
            holder.itemView.setOnTouchListener(handoff)
            holder.itemView.setOnLongClickListener {
                if (item is DesktopItem.App && onShowMenu != null) {
                    // The menu appears on hold. Moving the finger dismisses it and starts the
                    // same drag this cell has always used, which is what keeps the desktop's
                    // reorder, folder and delete-zone mechanics intact.
                    val entry = DrawerAppEntry(
                        component = item.component,
                        label = resolveLabel(item.component),
                        icon = resolveIcon(item.component),
                    )
                    if (onShowMenu.invoke(entry, holder.itemView, startDrag)) {
                        handoff?.arm()
                        holder.itemView.parent?.requestDisallowInterceptTouchEvent(true)
                        return@setOnLongClickListener true
                    }
                    startDrag()
                } else {
                    startDrag()
                }
            }
        } else {
            holder.binding.icon.setImageDrawable(null)
            holder.binding.label.text = ""
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.setTag(R.id.tag_prism_launcher_app_target, null)
        }
    }

    /**
     * Starts the desktop's long-press drag for one cell.
     *
     * Kept as one method because the menu handoff and the menu-less path (folders, files, and a
     * desktop without a menu) must produce exactly the same clip, shadow and source marker.
     */
    private fun startItemDrag(holder: VH, item: DesktopItem): Boolean {
        dragSourceIndex = holder.bindingAdapterPosition
        onDragStarted()
        val clip = ClipData.newPlainText("prism_desktop_item", item.serialize())
        val shadow = View.DragShadowBuilder(holder.binding.icon)
        val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            holder.itemView.startDragAndDrop(clip, shadow, dragSourceIndex, View.DRAG_FLAG_GLOBAL)
        } else {
            @Suppress("DEPRECATION")
            holder.itemView.startDrag(clip, shadow, dragSourceIndex, 0)
        }
        if (started) {
            holder.itemView.setOnDragListener { _, ev ->
                if (ev.action == android.view.DragEvent.ACTION_DRAG_ENDED) {
                    dragSourceIndex = -1
                    onDragEnded()
                }
                false
            }
        }
        return started
    }

    // ── Folder icon preview ──────────────────────────────────────────────
    private fun drawFolderPreview(imageView: android.widget.ImageView, folder: DesktopItem.Folder) {
        val ctx = imageView.context
        val dir = if (folder.folderId.startsWith("/")) java.io.File(folder.folderId)
                  else java.io.File(ctx.filesDir, "Desktop/Folders/${folder.folderId}")

        val links = if (folder.folderId.startsWith("/"))
            dir.listFiles()?.filter { !it.name.startsWith(".") }?.take(4) ?: emptyList()
        else
            dir.listFiles()?.filter { it.name.endsWith(".link") }?.take(4) ?: emptyList()

        if (links.isEmpty()) {
            imageView.setImageResource(android.R.drawable.ic_dialog_email)
            imageView.setColorFilter(android.graphics.Color.parseColor("#FFD700"))
            return
        }

        val size = (64 * ctx.resources.displayMetrics.density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val pm = ctx.packageManager

        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = android.graphics.Color.parseColor("#40FFD700")
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 16f, 16f, bgPaint)

        val itemSz = size / 2
        links.forEachIndexed { idx, file ->
            val isVirtual = !folder.folderId.startsWith("/")
            val di = if (isVirtual) try { DesktopItem.deserialize(file.readText()) } catch (_: Exception) { null } else null

            val icon: Drawable? = when {
                !isVirtual -> if (file.isDirectory) ctx.getDrawable(android.R.drawable.ic_dialog_email)
                              else ctx.getDrawable(android.R.drawable.ic_menu_crop)
                di is DesktopItem.App -> try { pm.getActivityIcon(di.component) }
                    catch (_: Exception) { try { pm.getApplicationIcon(di.component.packageName) } catch (_: Exception) { null } }
                di is DesktopItem.FileRef -> ctx.getDrawable(android.R.drawable.ic_menu_crop)
                di is DesktopItem.DirectoryRef -> ctx.getDrawable(android.R.drawable.ic_dialog_email)
                di is DesktopItem.NetworkedFolder -> ctx.getDrawable(android.R.drawable.stat_sys_download_done)
                else -> null
            }

            icon?.let {
                // Tint only non-app icons white
                if (di !is DesktopItem.App) it.setTint(android.graphics.Color.WHITE)
                val l = (idx % 2) * itemSz + (itemSz * 0.1f).toInt()
                val t = (idx / 2) * itemSz + (itemSz * 0.1f).toInt()
                it.setBounds(l, t, l + (itemSz * 0.8f).toInt(), t + (itemSz * 0.8f).toInt())
                it.draw(canvas)
            }
        }
        imageView.setImageBitmap(bitmap)
        imageView.colorFilter = null
    }

    // Icon decode is a PackageManager resource lookup, not a field read -- worth caching since
    // onBindViewHolder previously called it fresh on every bind, including every rebind from a
    // ViewPager2 page recycle. Labels aren't cached: they're cheap string lookups, not decodes.

    private fun resolveLabel(cn: ComponentName): String = try {
        packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
    } catch (_: Throwable) { cn.packageName.split(".").last() }

    /**
     * An app's icon, through the user's icon pack when one is active.
     *
     * The pack is part of the cache key, not just the component: switching packs in Settings used
     * to leave every cached icon in place, so the desktop and hotseat kept the old artwork until
     * the process restarted.
     */
    private fun resolveIcon(cn: ComponentName): Drawable? {
        val pack = PrismSettings.getIconPackPackage()
        val key = "$pack|${cn.flattenToString()}"
        return synchronized(iconCache) {
            iconCache.getOrPut(key) {
                (if (pack.isNotEmpty()) IconPackEngine.getIconPackDrawable(context, cn, pack) else null)
                    ?: try { packageManager.getActivityIcon(cn) }
                    catch (_: Throwable) {
                        try { packageManager.getApplicationIcon(cn.packageName) } catch (_: Throwable) { null }
                    }
            }
        }
    }

    class VH(val binding: ItemDesktopCellBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Turns an open menu's held cell into a drag; see [MenuDragHandoff]. */
        var handoff: MenuDragHandoff? = null
    }

    companion object {
        /**
         * SHARED ACROSS ADAPTERS AND BOUNDED, both of which matter.
         *
         * Each entry is a decoded Drawable -- an adaptive icon is two full layers, and at launcher
         * density one runs to a couple of hundred kilobytes. This used to be a per-adapter field with
         * no limit, which cost twice over: every desktop page in the pager built its own copy of the
         * same icons, and each copy grew to hold every app ever scrolled past for the life of the
         * process. On a phone with a few hundred apps installed that is tens of megabytes of Java
         * heap, for icons that are not on screen.
         *
         * Instances are handed out shared, exactly as the per-adapter cache already did -- this
         * changes how many copies exist, not whether they are shared.
         *
         * 96 covers several screens of a grid, which is all a cache in front of a PackageManager
         * lookup needs to do.
         */
        private val iconCache = object : LinkedHashMap<String, Drawable?>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable?>?) =
                size > 96
        }

        /** Drops every cached icon. Called under memory pressure; they re-decode on next bind. */
        fun trimIconCache() {
            synchronized(iconCache) { iconCache.clear() }
        }
    }
}
