package com.prism.launcher

import android.content.ComponentName
import android.content.Context
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemHotseatAppBinding
import com.prism.launcher.databinding.PageDesktopRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DesktopGridPage(
    context: Context,
    private val gridIndex: Int,
    private val onLaunch: (ComponentName) -> Unit,
    private val onDataChanged: () -> Unit,
    private val acceptDrawerDrops: () -> Boolean,
    private val onRequestPagePicker: (Int) -> Unit = {},
) : android.widget.LinearLayout(context) {

    private val store = DesktopShortcutStore(gridIndex)
    private val binding: PageDesktopRootBinding
    private val adapter: DesktopGridAdapter
    private val cellCount: Int = 24
    private val grid: RecyclerView
    private val deleteZone: DeleteZoneView

    /** Draws the widgets on this page. See [com.prism.launcher.widgets.DesktopWidgetLayer]. */
    private val widgetLayer: com.prism.launcher.widgets.DesktopWidgetLayer

    init {
        orientation = VERTICAL
        binding = PageDesktopRootBinding.inflate(LayoutInflater.from(context), this, true)
        grid = binding.desktopGrid
        deleteZone = binding.deleteZone

        adapter = DesktopGridAdapter(
            context,
            store,
            cellCount,
            onDataChanged,
            onLaunchApp = onLaunch,
            onLaunchFile = { openFile(it) },
            onLaunchFolder = { openFolder(it) },
            onDragStarted = { showDeleteZone() },
            onDragEnded   = { hideDeleteZone() },
            onShowMenu = { entry, anchor, _ ->
                val activity = context as? LauncherActivity
                if (activity == null) {
                    false
                } else {
                    // The cell's own handoff (see MenuDragHandoff) starts the drag when the finger
                    // moves; this only has to put the menu on screen.
                    activity.showAppContextMenu(entry, anchor)
                    true
                }
            },
        )
        grid.layoutManager = GridLayoutManager(context, 4)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        // The onboarding tour promises a long-press on an empty spot opens the page picker, and
        // only a hard vertical overscroll ever did. The empty area of the grid is the obvious
        // place to keep that promise; an icon keeps its own long-press for dragging.
        grid.setOnLongClickListener {
            onRequestPagePicker(gridIndex)
            true
        }

        widgetLayer = com.prism.launcher.widgets.DesktopWidgetLayer(
            context, grid, store, cellCount,
            onChanged = {
                adapter.refreshFromStore()
                onDataChanged()
            },
        )
        binding.widgetLayerHost.addView(
            widgetLayer,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // The widgets can only be positioned once the grid has laid its cells out, since the layer
        // takes their geometry from the grid's own children rather than computing it.
        //
        // A ONE-SHOT LISTENER, REMOVING ITSELF. An ordinary global-layout listener calling
        // requestLayout is an infinite loop by construction: the relayout it asks for fires the
        // listener again. That loop pinned the main thread and left the whole desktop blank.
        grid.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    widgetLayer.refresh()
                }
            },
        )

        // NO ItemTouchHelper. It started its own long-press drag from exactly the same view as the
        // adapter's startDragAndDrop below, so one long press activated two drag systems at once --
        // and only the ItemTouchHelper's knew nothing about folders, widgets or the delete zone.
        // The grid's own drop listener is the single drag path.

        // Delete zone drag listener
        deleteZone.setOnDragListener { v, event ->
            val dz = v as DeleteZoneView
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    dz.onDragEntered(event.x, event.y)
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    dz.onDragEntered(event.x, event.y)
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    dz.onDragExited()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    dz.onDragExited()
                    val payload = event.clipData?.getItemAt(0)?.text?.toString()
                    val label   = event.clipDescription?.label?.toString() ?: ""
                    deleteItem(label, payload)
                    // An item dragged out of a folder moves rather than copies, so deleting it
                    // here has to take its `.link` with it -- otherwise it reappears in the folder.
                    (event.localState as? FolderDragSource)?.consume()
                    hideDeleteZone()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    hideDeleteZone()
                    true
                }
                else -> true
            }
        }

        // Grid drag listener — handles drops from drawer, file explorer AND other desktop items
        grid.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DROP -> {
                    // Source position: -1 means external drop (drawer / file explorer / folder)
                    val sourcePos = event.localState as? Int ?: -1
                    val folderSource = event.localState as? FolderDragSource

                    // A widget dragged out of the app drawer. Handled before resolveDraggedItem
                    // because a widget is not a DesktopItem yet -- it has no id until the placement
                    // dance below allocates one, and that dance can need the user twice.
                    if (event.clipDescription?.label?.toString() ==
                        com.prism.launcher.widgets.WidgetPlacement.DRAG_LABEL
                    ) {
                        if (!acceptDrawerDrops()) return@setOnDragListener false
                        hideDeleteZone()
                        return@setOnDragListener dropWidget(event)
                    }

                    val draggedItem = resolveDraggedItem(event, sourcePos, folderSource)
                        ?: return@setOnDragListener false

                    // Gate cross-page drops only
                    val label = event.clipDescription?.label?.toString() ?: ""
                    val isExternalDrop = label == "prism_app" || label == "prism_file" || label == "prism_dir"
                    if (isExternalDrop && !acceptDrawerDrops()) return@setOnDragListener false

                    val child = grid.findChildViewUnder(event.x, event.y)
                    val targetPos = if (child != null) grid.getChildAdapterPosition(child) else RecyclerView.NO_POSITION
                    if (targetPos == RecyclerView.NO_POSITION || targetPos == sourcePos) return@setOnDragListener false

                    val target = adapter.getItemAt(targetPos)

                    if (target == null) {
                        // Empty cell — simple move (or place for external)
                        adapter.placeAt(targetPos, draggedItem)
                        if (sourcePos >= 0) adapter.clearCellAt(sourcePos)
                        folderSource?.consume()
                    } else if (applyDropMatrix(draggedItem, target, sourcePos, targetPos)) {
                        // Only consume the source when the target actually took it. Dropping onto a
                        // widget does nothing, and must not delete the item out of its folder.
                        folderSource?.consume()
                    }
                    hideDeleteZone()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    hideDeleteZone()
                    true
                }
                else -> true
            }
        }
    }

    /**
     * Places a widget dropped from the app drawer.
     *
     * The cell size is measured from a laid-out cell rather than assumed, because it is what decides
     * how many cells the widget asks for: a provider states its minimum size in dp, and turning that
     * into a span needs the real size of a cell on this screen.
     */
    private fun dropWidget(event: DragEvent): Boolean {
        val activity = context as? android.app.Activity ?: return false
        val flattened = event.clipData?.getItemAt(0)?.text?.toString() ?: return false
        val provider = com.prism.launcher.widgets.WidgetPlacement.providerFor(activity, flattened)
            ?: return false

        val child = grid.findChildViewUnder(event.x, event.y)
        val cellIndex = if (child != null) grid.getChildAdapterPosition(child) else 0
        if (cellIndex == RecyclerView.NO_POSITION) return false

        val density = resources.displayMetrics.density
        val sample = grid.layoutManager?.findViewByPosition(cellIndex)
        val cellWidthDp = ((sample?.width ?: (grid.width / 4)) / density).toInt().coerceAtLeast(1)
        val cellHeightDp = ((sample?.height ?: (grid.height / 6)) / density).toInt().coerceAtLeast(1)

        return com.prism.launcher.widgets.WidgetPlacement.begin(
            activity = activity,
            provider = provider,
            pageIndex = gridIndex,
            cellIndex = cellIndex,
            cellWidthDp = cellWidthDp,
            cellHeightDp = cellHeightDp,
            cellCount = cellCount,
        ) {
            adapter.refreshFromStore()
            widgetLayer.refresh()
            onDataChanged()
        }
    }

    /** One cell's width in dp, measured from a laid-out cell. Null when nothing is laid out yet. */
    fun cellWidthDp(): Int? {
        val view = grid.layoutManager?.findViewByPosition(0) ?: return null
        return (view.width / resources.displayMetrics.density).toInt().takeIf { it > 0 }
    }

    /** One cell's height in dp. See [cellWidthDp]. */
    fun cellHeightDp(): Int? {
        val view = grid.layoutManager?.findViewByPosition(0) ?: return null
        return (view.height / resources.displayMetrics.density).toInt().takeIf { it > 0 }
    }

    /** Rebuilds the widgets on this page. Called when a placement finishes elsewhere. */
    fun refreshWidgets() {
        widgetLayer.refresh()
    }

    // ── Delete Zone ──────────────────────────────────────────────────────────

    private fun showDeleteZone() {
        val displayMetrics = resources.displayMetrics
        val targetW = (displayMetrics.widthPixels * 0.70f).toInt()
        val targetH = (displayMetrics.heightPixels * 0.10f).toInt()
        deleteZone.layoutParams = deleteZone.layoutParams.also {
            it.width = targetW
            it.height = targetH
        }
        deleteZone.visibility = View.VISIBLE
        binding.deleteZoneContainer.visibility = View.VISIBLE
    }

    private fun hideDeleteZone() {
        // THE CONTAINER GOES GONE, not the zone INVISIBLE. An invisible child still contributes
        // the container's padding and the 10%-of-screen height set above, so the whole grid stayed
        // shifted after every drag. GONE takes the row out of the layout entirely.
        deleteZone.visibility = View.GONE
        binding.deleteZoneContainer.visibility = View.GONE
        deleteZone.reset()
    }

    private fun deleteItem(label: String, payload: String?) {
        if (payload == null) return
        when (label) {
            "prism_app" -> {
                // Remove from grid by component name
                val cn = ComponentName.unflattenFromString(payload) ?: return
                adapter.removeByComponentName(cn)
            }
            "prism_file" -> {
                adapter.removeByFilePath(payload)
            }
            "prism_dir" -> {
                adapter.removeByFilePath(payload)
            }
            "prism_folder" -> {
                val folderId = payload
                val dir = File(context.filesDir, "Desktop/Folders/$folderId")
                if (dir.exists()) dir.deleteRecursively()
                adapter.removeByFolderId(folderId)
            }
            "prism_desktop_item" -> {
                // Serialized DesktopItem dragged from the desktop itself or from folder popup
                val item = DesktopItem.deserialize(payload) ?: return
                when (item) {
                    // A widget is not moved between pages by this path: its host view lives in the
                    // overlay and its id belongs to the page that allocated it, so it is removed
                    // through DesktopWidgetLayer instead, which also releases the id.
                    is DesktopItem.Widget, is DesktopItem.PluginWidget, is DesktopItem.Occupied -> Unit
                    is DesktopItem.App -> adapter.removeByComponentName(item.component)
                    is DesktopItem.FileRef -> adapter.removeByFilePath(item.absolutePath)
                    is DesktopItem.DirectoryRef -> adapter.removeByFilePath(item.absolutePath)
                    is DesktopItem.Folder -> {
                        val dir = File(context.filesDir, "Desktop/Folders/${item.folderId}")
                        if (dir.exists()) dir.deleteRecursively()
                        adapter.removeByFolderId(item.folderId)
                    }
                    is DesktopItem.NetworkedFolder -> {
                        // For networked folders, just remove the persistent shortcut
                        adapter.removeByFilePath(item.url) 
                    }
                }
            }
        }
    }

    // ── Drag Helpers ─────────────────────────────────────────────────────────

    /**
     * For internal desktop drags (sourcePos >= 0), read live from adapter cells
     * so we work with the real object, not a re-serialized clone.
     */
    private fun resolveDraggedItem(
        event: DragEvent,
        sourcePos: Int,
        folderSource: FolderDragSource?,
    ): DesktopItem? {
        if (sourcePos >= 0) return adapter.getItemAt(sourcePos)
        if (folderSource != null) return folderSource.item

        val desc = event.clipDescription ?: return null
        val payload = event.clipData?.getItemAt(0)?.text?.toString() ?: return null
        if (!desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)) return null

        return when (desc.label?.toString()) {
            "prism_app"          -> ComponentName.unflattenFromString(payload)?.let { desktopApp(it) }
            "prism_file"         -> DesktopItem.FileRef(payload)
            "prism_dir"          -> DesktopItem.DirectoryRef(payload, File(payload).name)
            "prism_desktop_item" -> DesktopItem.deserialize(payload)
            else                 -> null
        }
    }

    // ── Folder Manipulation ───────────────────────────────────────────────────

    fun addItemToFolder(folder: DesktopItem.Folder, item: DesktopItem) {
        val dir = File(context.filesDir, "Desktop/Folders/${folder.folderId}")
        if (!dir.exists()) dir.mkdirs()

        val id = java.util.UUID.randomUUID().toString().substring(0, 4)
        val name = when (item) {
            // A widget cannot be put inside a folder -- it is a live view bound to a set of cells,
            // not a shortcut -- and a covered cell is not an item at all. Neither has a filename.
            is DesktopItem.Widget, is DesktopItem.PluginWidget, is DesktopItem.Occupied -> return
            is DesktopItem.App -> "app_${item.component.packageName}_$id.link"
            is DesktopItem.FileRef -> "file_$id.link"
            is DesktopItem.DirectoryRef -> "dir_$id.link"
            is DesktopItem.Folder -> "nest_$id.link"
            is DesktopItem.NetworkedFolder -> "net_$id.link"
        }
        val serialized = item.serialize()
        if (serialized.isNotEmpty()) {
            File(dir, name).writeText(serialized)
            android.util.Log.d("PrismFolder", "Created shortcut: $name in ${folder.folderId}")
        }
    }

    private fun combineIntoFolder(existing: DesktopItem, incoming: DesktopItem): DesktopItem.Folder {
        val folderId = java.util.UUID.randomUUID().toString().substring(0, 8)
        val folder = DesktopItem.Folder("New Folder", folderId)
        addItemToFolder(folder, existing)
        addItemToFolder(folder, incoming)
        return folder
    }

    /**
     * Write a .link file into a real storage directory so the item appears
     * in the DirectoryRef's folder popup. Only writes to accessible paths.
     */
    private fun addItemToStorageFolder(dir: DesktopItem.DirectoryRef, item: DesktopItem) {
        val storageDir = File(dir.absolutePath)
        if (!storageDir.exists() || !storageDir.canWrite()) {
            Toast.makeText(context, "Cannot write to ${dir.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val id = java.util.UUID.randomUUID().toString().substring(0, 4)
        val fileName = "prism_${id}.link"
        try {
            File(storageDir, fileName).writeText(item.serialize())
            android.util.Log.d("PrismFolder", "Wrote to storage folder: $fileName")
        } catch (e: Exception) {
            android.util.Log.e("PrismFolder", "Failed to write to storage folder", e)
        }
    }

    // ── Full Interaction Matrix ────────────────────────────────────────────────

    /**
     * What happens when [dragged], from [srcPos], is dropped on [target] at [dstPos].
     *
     * @return true when the target accepted the item. A widget (or a cell it covers) is not a drop
     *         target at all, and false is what tells the caller not to consume a folder source.
     */
    private fun applyDropMatrix(
        dragged: DesktopItem,
        target: DesktopItem,
        srcPos: Int,
        dstPos: Int,
    ): Boolean {
        // Widgets are not folder members and covered cells are not targets. Nothing a drag can do
        // here is meaningful, so it is refused rather than half-applied.
        if (target is DesktopItem.Widget || target is DesktopItem.PluginWidget ||
            target is DesktopItem.Occupied ||
            dragged is DesktopItem.Widget || dragged is DesktopItem.PluginWidget ||
            dragged is DesktopItem.Occupied
        ) return false

        val absorbed = when (target) {
            is DesktopItem.Folder -> {
                addItemToFolder(target, dragged)
                true
            }
            is DesktopItem.DirectoryRef -> {
                addItemToStorageFolder(target, dragged)
                true
            }
            is DesktopItem.App, is DesktopItem.FileRef, is DesktopItem.NetworkedFolder -> {
                adapter.placeAt(dstPos, combineIntoFolder(dragged, target))
                true
            }
            else -> false
        }

        if (absorbed) {
            if (srcPos >= 0) adapter.clearCellAt(srcPos)
        } else if (srcPos >= 0) {
            // Both ordinary items: a reorder. `move` refuses to disturb widget occupancy, so a
            // refused move leaves the grid exactly as it was.
            adapter.move(srcPos, dstPos)
        } else {
            adapter.placeAt(dstPos, dragged)
        }
        adapter.persist()
        return true
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun openFile(absolutePath: String) {
        (context as? LauncherActivity)?.openFile(absolutePath)
    }

    private fun openFolder(item: DesktopItem) {
        (context as? LauncherActivity)?.openFolder(item)
    }

    /** Refreshes only the grid cells — does NOT re-query the hotseat. */
    fun refreshFromStore() {
        adapter.refreshFromStore()
    }

    /** Refreshes both grid and hotseat — call only on window attach or after launch stats change. */
    fun refreshAll() {
        adapter.refreshFromStore()
        updateHotseat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateHotseat()
    }

    private fun updateHotseat() {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val predictions = HotseatPredictor.getPredictions()
            withContext(Dispatchers.Main) {
                binding.hotseatContainer.removeAllViews()
                val pm = context.packageManager
                for (cnStr in predictions) {
                    val cn = ComponentName.unflattenFromString(cnStr) ?: continue
                    val icon = resolveIcon(pm, cn) ?: continue
                    val itemBinding = ItemHotseatAppBinding.inflate(
                        LayoutInflater.from(context), binding.hotseatContainer, false
                    )
                    itemBinding.hotseatIcon.setImageDrawable(icon)
                    itemBinding.root.setOnClickListener { onLaunch(cn) }
                    binding.hotseatContainer.addView(itemBinding.root)
                }
            }
        }
    }

    private fun resolveIcon(
        pm: android.content.pm.PackageManager,
        cn: ComponentName,
    ): android.graphics.drawable.Drawable? {
        // The same icon pack the drawer uses, so the hotseat does not show stock artwork beside a
        // themed grid. Read per call because the setting can change while the page is alive.
        val pack = PrismSettings.getIconPackPackage()
        if (pack.isNotEmpty()) {
            IconPackEngine.getIconPackDrawable(context, cn, pack)?.let { return it }
        }
        return try { pm.getActivityIcon(cn) } catch (_: Throwable) {
            try { pm.getApplicationIcon(cn.packageName) } catch (_: Throwable) { null }
        }
    }
}
