package com.prism.launcher.editor

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.nora.IosUi
import java.io.File

/**
 * The folder tree, on the right.
 *
 * ## Flattened, not nested
 *
 * The tree is held as a flat list of visible rows, each carrying its depth, rather than as nested
 * views. A RecyclerView over a flat list recycles rows and stays smooth on a folder with thousands
 * of files; nested layouts inflate every descendant whether or not it is on screen, and a source
 * tree is exactly the shape that punishes.
 *
 * ## Directories are read when they are opened, not before
 *
 * Expanding a folder lists it at that moment. Walking the whole tree up front means a project with a
 * `node_modules` in it takes seconds to show anything, and almost all of what was read is never
 * looked at.
 */
class FileTreePanel(context: Context) : LinearLayout(context) {

    /** A visible row. [depth] drives the indent; [expanded] only means anything for directories. */
    private data class Node(
        val file: File,
        val depth: Int,
        var expanded: Boolean = false,
    )

    private val rows = ArrayList<Node>()
    private val listView = RecyclerView(context)
    private val titleView = TextView(context)
    private val adapter = TreeAdapter()

    var onFileChosen: ((File) -> Unit)? = null

    /** The folder being shown, or null when none is open. */
    var root: File? = null
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.cardBackground(context))

        titleView.apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            val pad = IosUi.dp(context, 12f)
            setPadding(pad, pad, pad, IosUi.dp(context, 8f))
            text = "NO FOLDER OPEN"
            letterSpacing = 0.05f
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        addView(listView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Shows [folder], replacing whatever was open. */
    fun open(folder: File?) {
        root = folder
        rows.clear()
        if (folder != null && folder.isDirectory) {
            titleView.text = folder.name.uppercase()
            childrenOf(folder).forEach { rows.add(Node(it, 0)) }
        } else {
            titleView.text = "NO FOLDER OPEN"
        }
        adapter.notifyDataSetChanged()
    }

    /** Re-reads the open folder, keeping expansion state where the paths still exist. */
    fun refresh() {
        val expandedPaths = rows.filter { it.expanded }.map { it.file.absolutePath }.toSet()
        val folder = root ?: return
        rows.clear()
        childrenOf(folder).forEach { rows.add(Node(it, 0)) }
        // Re-expand from the top down, since expanding inserts rows the next pass needs to see.
        var index = 0
        while (index < rows.size) {
            val node = rows[index]
            if (node.file.isDirectory && node.file.absolutePath in expandedPaths) {
                expand(index, notify = false)
            }
            index++
        }
        adapter.notifyDataSetChanged()
    }

    /**
     * Directories first, then files, each alphabetically -- the order every file tree uses, and the
     * one that makes a folder scannable. Hidden entries are included: `.gitignore` and `.github` are
     * things people edit.
     */
    private fun childrenOf(folder: File): List<File> =
        folder.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    private fun expand(position: Int, notify: Boolean = true) {
        val node = rows.getOrNull(position) ?: return
        if (!node.file.isDirectory || node.expanded) return
        node.expanded = true
        val children = childrenOf(node.file).map { Node(it, node.depth + 1) }
        rows.addAll(position + 1, children)
        if (notify) {
            adapter.notifyItemChanged(position)
            adapter.notifyItemRangeInserted(position + 1, children.size)
        }
    }

    private fun collapse(position: Int) {
        val node = rows.getOrNull(position) ?: return
        if (!node.file.isDirectory || !node.expanded) return
        node.expanded = false
        // Everything deeper than this node, until the next sibling, belongs to it.
        var end = position + 1
        while (end < rows.size && rows[end].depth > node.depth) end++
        val removed = end - (position + 1)
        if (removed > 0) {
            repeat(removed) { rows.removeAt(position + 1) }
            adapter.notifyItemChanged(position)
            adapter.notifyItemRangeRemoved(position + 1, removed)
        }
    }

    private inner class TreeAdapter : RecyclerView.Adapter<TreeAdapter.VH>() {

        inner class VH(val row: LinearLayout, val label: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                isClickable = true
            }
            val label = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(IosUi.label(parent.context))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            row.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return VH(row, label)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val node = rows[position]
            val isDirectory = node.file.isDirectory

            val marker = when {
                isDirectory && node.expanded -> "▾ "     // ▾
                isDirectory -> "▸ "                      // ▸
                else -> "   "
            }
            holder.label.text = marker + node.file.name
            holder.label.setTextColor(if (isDirectory) IosUi.label(context) else IosUi.secondaryLabel(context))

            val indent = IosUi.dp(context, 12f) + node.depth * IosUi.dp(context, 14f)
            holder.row.setPadding(indent, IosUi.dp(context, 7f), IosUi.dp(context, 8f), IosUi.dp(context, 7f))
            holder.row.setBackgroundColor(Color.TRANSPARENT)

            holder.row.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at == RecyclerView.NO_POSITION) return@setOnClickListener
                val target = rows[at]
                if (target.file.isDirectory) {
                    if (target.expanded) collapse(at) else expand(at)
                } else {
                    onFileChosen?.invoke(target.file)
                }
            }
        }
    }
}
