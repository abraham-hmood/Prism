package com.prism.launcher.browser

import android.app.DownloadManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.ItemDownloadEntryBinding

/**
 * The browser's Downloads list: files the user downloaded, and sites downloaded for the mesh.
 *
 * A REAL LIST RATHER THAN A DIALOG OF STRINGS. This used to be `AlertDialog.setItems`, which can
 * only report a tap -- there is nowhere to put a per-row button and no long-press to hook. Removing
 * a single download needs both, so the list is its own adapter over a real row layout.
 *
 * REMOVAL IS DELIBERATELY THREE STEPS: long-press to reveal the X, tap the X, confirm. Deleting a
 * file off the device and pulling a site off the mesh cannot be undone, and the rest of this list
 * is tapped casually to open things -- an always-visible delete button sitting next to that is a
 * mis-tap waiting to happen.
 */
object PrismDownloadsSheet {

    private const val TAG = "PrismDownloads"

    /**
     * A file and a mesh site are both "something the user downloaded" but they are not the same
     * object -- one lives in the device's Downloads folder, the other is served to peers -- so
     * opening and removing them mean different things and the list keeps them distinguishable.
     */
    private sealed class Entry {
        data class FileItem(val file: PrismSettings.DownloadedFile) : Entry()
        data class SiteItem(val site: PrismSettings.P2pMirroredSite) : Entry()

        /**
         * A page kept automatically by [PrismWebCache], which is a third thing again: the user did
         * not ask for this one by name, it may or may not be shared with peers, and removing it
         * deletes a cache rather than un-publishing something deliberate.
         */
        data class CacheItem(val site: PrismWebCache.CachedSite) : Entry()
    }

    fun show(
        context: Context,
        onOpenFile: (PrismSettings.DownloadedFile) -> Unit,
        onOpenSite: (String) -> Unit
    ) {
        val entries = collect()
        if (entries.isEmpty()) {
            Toast.makeText(context, "Nothing downloaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: AlertDialog
        lateinit var adapter: Adapter

        adapter = Adapter(
            entries.toMutableList(),
            onOpen = { entry ->
                dialog.dismiss()
                when (entry) {
                    is Entry.FileItem -> onOpenFile(entry.file)
                    is Entry.SiteItem -> onOpenSite(entry.site.domain)
                    // The `.cache.p2p` name, which the browser resolves off local disk when this
                    // device holds the copy and over the mesh when a peer does.
                    is Entry.CacheItem -> onOpenSite(entry.site.meshDomain)
                }
            },
            onRemove = { entry, position ->
                confirmRemoval(context, entry) {
                    adapter.removeAt(position)
                    // An empty list would leave the dialog showing nothing but its buttons.
                    if (adapter.itemCount == 0) dialog.dismiss()
                }
            }
        )

        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            // Capped so a long download history scrolls inside the dialog instead of pushing the
            // buttons off the bottom of the screen.
            val max = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max)
        }

        dialog = AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Downloads")
            .setMessage("Long-press an item to remove it")
            .setView(recycler)
            // Kept from the old list. It clears the HISTORY only and never touches files or mesh
            // sites -- deleting is what the per-row X is for, and it asks first.
            .setNeutralButton("Clear file list") { _, _ ->
                PrismSettings.clearDownloadedFiles()
                Toast.makeText(context, "Download list cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun collect(): List<Entry> {
        val out = ArrayList<Entry>()
        PrismSettings.getDownloadedFiles().forEach { out.add(Entry.FileItem(it)) }
        if (com.prism.launcher.mesh.PrismMeshService.isOnMesh()) {
            PrismSettings.getP2pMirroredSites().forEach { out.add(Entry.SiteItem(it)) }
        }
        // Listed whatever the mesh is doing, unlike mirrors: a cache that is not shared is still
        // the user's, and this list is the only place it can be opened or deleted from.
        PrismWebCache.sites().forEach { out.add(Entry.CacheItem(it)) }
        return out
    }

    /**
     * Spells out what removal actually does, because the two cases differ: a site stops being
     * served to every peer on the mesh, which is not obvious from an X next to a row.
     */
    private fun confirmRemoval(context: Context, entry: Entry, onRemoved: () -> Unit) {
        val title: String
        val message: String
        when (entry) {
            is Entry.FileItem -> {
                title = "Remove ${entry.file.fileName}?"
                message = "The file will be deleted from this device."
            }
            is Entry.SiteItem -> {
                title = "Remove ${entry.site.domain}?"
                message = "It will stop being hosted on the mesh and its downloaded files will be deleted."
            }
            is Entry.CacheItem -> {
                title = "Delete the cache of ${entry.site.host}?"
                message = if (PrismWebCache.meshSharingEnabled()) {
                    "The cached pages will be deleted and will stop being served to mesh peers."
                } else {
                    "The cached pages will be deleted from this device."
                }
            }
        }

        PrismDialogFactory.show(
            context, title, message,
            positiveText = "Remove",
            negativeText = "Cancel",
            onPositive = {
                val ok = when (entry) {
                    is Entry.FileItem -> removeFile(context, entry.file)
                    is Entry.SiteItem -> PrismMirrorManager.removeSite(context, entry.site.domain)
                    is Entry.CacheItem -> PrismWebCache.remove(context, entry.site.host)
                }
                // The row goes either way: the entry is gone from Prism's list even if the file
                // itself could not be deleted, and a row that stays put looks like a failed tap.
                onRemoved()
                if (!ok) {
                    Toast.makeText(
                        context, "Removed from the list, but the files could not be deleted",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /**
     * Deletes the file and drops it from the list.
     *
     * Prefers `DownloadManager.remove`, which deletes the file it created: under scoped storage a
     * plain `File.delete()` on the public Downloads folder is not guaranteed to be permitted, but
     * removing one's own download always is. Falls back to the recorded path for entries saved
     * before the download id was tracked.
     *
     * @return true if the bytes are actually gone.
     */
    private fun removeFile(context: Context, file: PrismSettings.DownloadedFile): Boolean {
        PrismSettings.removeDownloadedFile(file)

        if (file.downloadId >= 0) {
            val removed = runCatching {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.remove(file.downloadId)
            }.getOrDefault(0)
            if (removed > 0) return true
        }

        val path = file.localPath
        if (path.isBlank()) return false
        return runCatching {
            val onDisk = if (path.startsWith("file://")) {
                val decoded = android.net.Uri.parse(path).path
                if (decoded.isNullOrBlank()) return@runCatching false
                java.io.File(decoded)
            } else {
                java.io.File(path)
            }
            // Already gone counts as success -- the user's goal was for it not to be there.
            !onDisk.exists() || onDisk.delete()
        }.onFailure {
            PrismLogger.logError(TAG, "Could not delete ${file.fileName}", it)
        }.getOrDefault(false)
    }

    private class Adapter(
        private val items: MutableList<Entry>,
        private val onOpen: (Entry) -> Unit,
        private val onRemove: (Entry, Int) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        /**
         * Which row is showing its X, by position. One at a time: a long-press arms that row and
         * disarms whatever was armed before, so the list never sits covered in delete buttons and
         * the gesture always has a visible effect.
         */
        private var armed: Int = RecyclerView.NO_POSITION

        class VH(val binding: ItemDownloadEntryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemDownloadEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val b = holder.binding

            when (entry) {
                is Entry.FileItem -> {
                    b.entryIcon.text = "\uD83D\uDCC4"
                    b.entryTitle.text = entry.file.fileName
                    b.entrySubtitle.text = entry.file.url
                }
                is Entry.SiteItem -> {
                    b.entryIcon.text = "\uD83C\uDF10"
                    b.entryTitle.text = entry.site.domain
                    b.entrySubtitle.text = "Hosted on the mesh"
                }
                is Entry.CacheItem -> {
                    b.entryIcon.text = "\uD83D\uDDC3"
                    b.entryTitle.text = entry.site.host
                    // Says plainly whether peers can see it, because that is the one thing about a
                    // cache entry the user cannot work out by looking at it.
                    val pages = entry.site.pages
                    val mb = entry.site.bytes / (1024.0 * 1024.0)
                    val reach = if (PrismWebCache.meshSharingEnabled()) "shared on the mesh"
                                else "private to this device"
                    b.entrySubtitle.text = String.format(
                        java.util.Locale.US, "Cached \u00B7 %d page%s \u00B7 %.1f MB \u00B7 %s",
                        pages, if (pages == 1) "" else "s", mb, reach
                    )
                }
            }

            b.entryRemove.isVisible = position == armed

            b.root.setOnClickListener { onOpen(entry) }
            b.root.setOnLongClickListener {
                val at = holder.bindingAdapterPosition
                if (at == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val previous = armed
                armed = if (armed == at) RecyclerView.NO_POSITION else at
                if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
                if (armed != RecyclerView.NO_POSITION) notifyItemChanged(armed)
                true
            }
            b.entryRemove.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) onRemove(items[at], at)
            }
        }

        fun removeAt(position: Int) {
            if (position !in items.indices) return
            items.removeAt(position)
            armed = RecyclerView.NO_POSITION
            notifyItemRemoved(position)
            // Positions after the removal shifted, and `armed` is a position -- rebind the tail so
            // no stale row is left holding a visible X.
            notifyItemRangeChanged(position, items.size - position)
        }
    }
}
