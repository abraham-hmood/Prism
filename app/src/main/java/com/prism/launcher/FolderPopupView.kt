package com.prism.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ViewFolderPopupBinding
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.ThemeSkin
import java.io.File

/**
 * One row inside a folder popup: the item itself, and the `.link` file that holds it when the
 * folder is a virtual Prism folder.
 *
 * The link file is what makes dragging an item out a MOVE. The drop target cannot know where the
 * source came from -- a folder listing, another folder, the desktop -- so the drag carries the
 * file to delete once the drop has succeeded.
 */
data class FolderEntry(val item: DesktopItem, val linkFile: File?)

/** The `localState` of a drag that started inside a folder popup. */
class FolderDragSource(val item: DesktopItem, private val linkFile: File?) {
    /** Removes the source shortcut after a successful drop, so the item moved rather than copied. */
    fun consume() {
        linkFile?.delete()
    }
}

class FolderPopupView(
    context: Context,
    private val rootFolder: DesktopItem,
    private val onLaunchApp: (android.content.ComponentName) -> Unit,
    private val onLaunchFile: (String) -> Unit,
    private val onLaunchFolder: (DesktopItem) -> Unit,
    private val onRename: (String) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val binding = ViewFolderPopupBinding.inflate(LayoutInflater.from(context), this, true)

    // Back-stack for nested folder navigation (list of (folder, title))
    private val backStack = mutableListOf<Pair<DesktopItem, String>>()

    init {
        binding.folderPopupRoot.setOnClickListener { onDismiss() }
        binding.folderCard.setOnClickListener { /* consume */ }

        // The card is a themed panel first; the glow is a foreground decoration, so a theme
        // without elevation still gets an opaque, readable folder instead of a bare outline.
        val mode = IosUi.mode(context)
        binding.folderCard.background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = IosUi.dp(context, mode.shape.card.toFloat()).toFloat()
            if (mode.shape.border > 0) {
                setStroke(
                    IosUi.dp(context, mode.shape.border.toFloat()),
                    IosUi.separator(context),
                )
            }
        }
        binding.folderCard.foreground = ThemeSkin.glowDrawable(context, mode.shape.card.toFloat(), 4f)
        binding.folderPopupRoot.setBackgroundColor(mode.palette.overlay.toInt())

        showFolder(rootFolder, isRoot = true)
    }

    private fun showFolder(item: DesktopItem, isRoot: Boolean) {
        // Title
        val name = when (item) {
            is DesktopItem.Folder -> item.name
            is DesktopItem.DirectoryRef -> item.name
            else -> "Folder"
        }
        binding.folderTitle.setText(name)
        binding.folderTitle.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Only rename the root folder
                if (isRoot) onRename(v.text.toString())
                v.clearFocus()
                true
            } else false
        }
        binding.folderTitle.isFocusable = isRoot
        binding.folderTitle.isFocusableInTouchMode = isRoot

        // Back arrow visibility
        val backBtn = binding.root.findViewWithTag<ImageButton>("backBtn")
        backBtn?.visibility = if (isRoot) View.GONE else View.VISIBLE

        // Grid
        val items = resolveFolderContents(context, item)
        binding.folderGrid.layoutManager = GridLayoutManager(context, 4)
        binding.folderGrid.adapter = FolderInternalAdapter(
            items,
            onLaunchApp = onLaunchApp,
            onLaunchFile = onLaunchFile,
            onLaunchFolder = { nested ->
                // Navigate into nested folder instead of opening a new popup
                backStack.add(item to name)
                showFolder(nested, isRoot = false)
                val bb = binding.root.findViewWithTag<ImageButton>("backBtn")
                bb?.visibility = View.VISIBLE
            },
            onDragStarted = {
                // Tell the activity the delete zone should appear
                (context as? LauncherActivity)?.let { activity ->
                    val dp = activity.findDesktopPosition()
                    if (dp != -1) {
                        val desktopPage = activity.findPageViewAt(dp) as? DesktopGridPage
                        desktopPage?.let { pg ->
                            // Expose show/hide via package-private extension
                        }
                    }
                }
            }
        )
    }

    fun handleBack(): Boolean {
        if (backStack.isEmpty()) return false
        val (parent, _) = backStack.removeLast()
        showFolder(parent, isRoot = backStack.isEmpty())
        if (backStack.isEmpty()) {
            val bb = binding.root.findViewWithTag<ImageButton>("backBtn")
            bb?.visibility = View.GONE
        }
        return true
    }

    private fun resolveFolderContents(ctx: Context, item: DesktopItem): List<FolderEntry> {
        val result = mutableListOf<FolderEntry>()
        android.util.Log.d("PrismFolder", "Opening Folder: $item")

        when (item) {
            is DesktopItem.Folder -> {
                val dir = if (item.folderId.startsWith("/")) File(item.folderId)
                          else File(ctx.filesDir, "Desktop/Folders/${item.folderId}")

                if (!dir.exists()) {
                    android.util.Log.e("PrismFolder", "Directory Missing: ${dir.absolutePath}")
                    return emptyList()
                }
                val files = dir.listFiles()
                android.util.Log.d("PrismFolder", "Files in ${item.folderId}: ${files?.size ?: "null"}")
                files?.forEach { file ->
                    if (file.name.endsWith(".link")) {
                        try {
                            DesktopItem.deserialize(file.readText())?.let {
                                result.add(FolderEntry(it, file))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PrismFolder", "Failed to read: ${file.name}", e)
                        }
                    }
                }
            }
            is DesktopItem.DirectoryRef -> {
                File(item.absolutePath).listFiles()?.forEach { file ->
                    val entry = if (file.isDirectory) {
                        DesktopItem.DirectoryRef(file.absolutePath, file.name)
                    } else {
                        DesktopItem.FileRef(file.absolutePath)
                    }
                    result.add(FolderEntry(entry, null))
                }
            }
            else -> {}
        }
        android.util.Log.i("PrismFolder", "Resolution Complete. Total items: ${result.size}")
        return result
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

class FolderInternalAdapter(
    private val items: List<FolderEntry>,
    private val onLaunchApp: (android.content.ComponentName) -> Unit,
    private val onLaunchFile: (String) -> Unit,
    private val onLaunchFolder: (DesktopItem) -> Unit,
    private val onDragStarted: () -> Unit = {},
) : RecyclerView.Adapter<FolderInternalAdapter.VH>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_desktop_cell, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val item = entry.item
        val b = com.prism.launcher.databinding.ItemDesktopCellBinding.bind(holder.itemView)
        val ctx = holder.itemView.context
        val pm = ctx.packageManager

        b.icon.visibility = View.VISIBLE
        b.label.visibility = View.VISIBLE
        b.icon.colorFilter = null

        b.root.setOnClickListener {
            when (item) {
                is DesktopItem.Widget, is DesktopItem.PluginWidget, is DesktopItem.Occupied -> Unit  // widgets are not folder members; covered cells are not targets
                is DesktopItem.App -> onLaunchApp(item.component)
                is DesktopItem.FileRef -> onLaunchFile(item.absolutePath)
                is DesktopItem.DirectoryRef -> onLaunchFolder(item)
                is DesktopItem.Folder -> onLaunchFolder(item)
                is DesktopItem.NetworkedFolder -> onLaunchFolder(item)
            }
        }

        when (item) {
            is DesktopItem.Widget, is DesktopItem.PluginWidget, is DesktopItem.Occupied -> Unit  // widgets are not folder members; covered cells are not targets
            is DesktopItem.App -> {
                b.label.text = try {
                    pm.getActivityInfo(item.component, 0).loadLabel(pm).toString()
                } catch (_: Exception) { item.component.packageName.split(".").last() }
                b.icon.setImageDrawable(
                    try { pm.getActivityIcon(item.component) }
                    catch (_: Exception) {
                        try { pm.getApplicationIcon(item.component.packageName) }
                        catch (_: Exception) { null }
                    }
                )
            }
            is DesktopItem.FileRef -> {
                b.label.text = File(item.absolutePath).name
                b.icon.setImageResource(android.R.drawable.ic_menu_crop)
                b.icon.setColorFilter(android.graphics.Color.parseColor("#FF4CAF50"))
            }
            is DesktopItem.DirectoryRef -> {
                b.label.text = item.name
                b.icon.setImageResource(android.R.drawable.ic_dialog_email)
                b.icon.setColorFilter(android.graphics.Color.parseColor("#FFD700"))
            }
            is DesktopItem.Folder -> {
                b.label.text = item.name
                b.icon.setImageResource(android.R.drawable.ic_dialog_email)
                b.icon.setColorFilter(android.graphics.Color.parseColor("#FFD700"))
            }
            is DesktopItem.NetworkedFolder -> {
                b.label.text = item.name
                b.icon.setImageResource(android.R.drawable.stat_sys_download_done)
                b.icon.setColorFilter(android.graphics.Color.parseColor("#00BA7C"))
            }
        }

        // Long-press to drag item out of the popup onto desktop or delete zone
        holder.itemView.setOnLongClickListener {
            val ctx2 = holder.itemView.context
            val activity = ctx2 as? LauncherActivity ?: return@setOnLongClickListener false
            if (activity.findDesktopPosition() == -1) return@setOnLongClickListener false

            onDragStarted()
            val clip = android.content.ClipData.newPlainText("prism_desktop_item", item.serialize())
            val shadow = android.view.View.DragShadowBuilder(b.icon)
            // The source marker travels with the drag so the drop target can delete the `.link`
            // once it has taken the item -- that is what turns "drag out" into a move rather
            // than a duplicate left behind in the folder.
            val source = FolderDragSource(item, entry.linkFile)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                holder.itemView.startDragAndDrop(clip, shadow, source, android.view.View.DRAG_FLAG_GLOBAL)
            } else {
                @Suppress("DEPRECATION")
                holder.itemView.startDrag(clip, shadow, source, 0)
            }
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)
}
