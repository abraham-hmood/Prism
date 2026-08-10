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
) : android.widget.LinearLayout(context) {

    private val store = DesktopShortcutStore(gridIndex)
    private val binding: PageDesktopRootBinding
    private val adapter: DesktopGridAdapter
    private val cellCount: Int = 24
    private val grid: RecyclerView
    private val deleteZone: DeleteZoneView

    init {
        orientation = VERTICAL
        binding = PageDesktopRootBinding.inflate(LayoutInflater.from(context), this, true)
        grid = binding.desktopGrid
        deleteZone = binding.deleteZone

        adapter = DesktopGridAdapter(
            context.packageManager,
            store,
            cellCount,
            onDataChanged,
            onLaunchApp = onLaunch,
            onLaunchFile = { openFile(it) },
            onLaunchFolder = { openFolder(it) },
            onDragStarted = { showDeleteZone() },
            onDragEnded   = { hideDeleteZone() },
        )
        grid.layoutManager = GridLayoutManager(context, 4)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        // Standard touch-to-move helper (long-press drag within grid)
        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            },
        )
        touchHelper.attachToRecyclerView(grid)

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
                    // Source position: -1 means external drop (drawer / file explorer)
                    val sourcePos = event.localState as? Int ?: -1
                    val draggedItem = resolveDraggedItem(event, sourcePos) ?: return@setOnDragListener false

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
                    } else {
                        applyDropMatrix(draggedItem, target, sourcePos, targetPos)
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
    }

    private fun hideDeleteZone() {
        deleteZone.visibility = View.INVISIBLE
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
    private fun resolveDraggedItem(event: DragEvent, sourcePos: Int): DesktopItem? {
        if (sourcePos >= 0) return adapter.getItemAt(sourcePos)

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

    private fun applyDropMatrix(dragged: DesktopItem, target: DesktopItem, srcPos: Int, dstPos: Int) {
        when (dragged) {
            is DesktopItem.App -> when (target) {
                is DesktopItem.App, is DesktopItem.FileRef, is DesktopItem.NetworkedFolder -> {
                    val f = combineIntoFolder(dragged, target)
                    adapter.placeAt(dstPos, f)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.Folder -> {
                    addItemToFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.DirectoryRef -> {
                    addItemToStorageFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
            }

            is DesktopItem.Folder -> when (target) {
                is DesktopItem.Folder -> {
                    addItemToFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.DirectoryRef -> {
                    addItemToStorageFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                else -> {
                    if (target is DesktopItem.App || target is DesktopItem.FileRef || target is DesktopItem.NetworkedFolder) {
                        val f = combineIntoFolder(dragged, target)
                        adapter.placeAt(dstPos, f)
                    } else {
                        if (srcPos >= 0) adapter.move(srcPos, dstPos) else adapter.placeAt(dstPos, dragged)
                    }
                    if (srcPos >= 0 && target !is DesktopItem.Folder) adapter.clearCellAt(srcPos)
                }
            }

            is DesktopItem.DirectoryRef -> when (target) {
                is DesktopItem.App, is DesktopItem.FileRef, is DesktopItem.NetworkedFolder -> {
                    val f = combineIntoFolder(dragged, target)
                    adapter.placeAt(dstPos, f)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.Folder -> {
                    addItemToFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.DirectoryRef -> {
                    val f = combineIntoFolder(dragged, target)
                    adapter.placeAt(dstPos, f)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
            }

            is DesktopItem.FileRef -> when (target) {
                is DesktopItem.App, is DesktopItem.FileRef, is DesktopItem.NetworkedFolder -> {
                    val f = combineIntoFolder(dragged, target)
                    adapter.placeAt(dstPos, f)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.Folder -> {
                    addItemToFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                is DesktopItem.DirectoryRef -> {
                    addItemToStorageFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
            }

            is DesktopItem.NetworkedFolder -> when (target) {
                is DesktopItem.Folder -> {
                    addItemToFolder(target, dragged)
                    if (srcPos >= 0) adapter.clearCellAt(srcPos)
                }
                else -> {
                    if (target is DesktopItem.App || target is DesktopItem.FileRef || target is DesktopItem.NetworkedFolder) {
                        val f = combineIntoFolder(dragged, target)
                        adapter.placeAt(dstPos, f)
                    } else {
                        if (srcPos >= 0) adapter.move(srcPos, dstPos) else adapter.placeAt(dstPos, dragged)
                    }
                    if (srcPos >= 0 && target !is DesktopItem.Folder) adapter.clearCellAt(srcPos)
                }
            }
        }
        adapter.persist()
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
        return try { pm.getActivityIcon(cn) } catch (_: Throwable) {
            try { pm.getApplicationIcon(cn.packageName) } catch (_: Throwable) { null }
        }
    }
}
