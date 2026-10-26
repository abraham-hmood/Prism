package com.prism.launcher.photos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import com.prism.launcher.photos.PhotoBackup.Entry
import com.prism.launcher.photos.PhotoBackup.Origin
import com.prism.launcher.trusted.TrustedDevices
import java.util.concurrent.Executors

/**
 * The Photos page.
 *
 * ## Three places a photo can be, one tile
 *
 * On this device, in this device's encrypted vault, or on a trusted device that has a copy. The
 * same photograph can be in all three, and showing three tiles for it would be absurd, so the page
 * merges on the photo's id and the tile carries a small badge saying where else it exists. That is
 * also why the filter row has four entries rather than three sources listed twice.
 *
 * ## Scanning is off the main thread and thumbnails are cached
 *
 * A camera roll is thousands of rows and each thumbnail is a provider call. The scan runs on a
 * single background thread, thumbnails are loaded by a small pool into an LRU sized in bytes, and
 * the grid is re-bound as they arrive. Decoding originals to fill a grid is the mistake that makes
 * a photo page stutter and then get killed for memory.
 */
class PhotosPageView(context: Context) : FrameLayout(context) {

    private companion object {
        const val TAG = "PrismPhotos"
        const val COLUMNS = 3

        /** Enough for a few screens of tiles at 128dp, counted in bytes rather than entries. */
        const val THUMB_CACHE_BYTES = 48 * 1024 * 1024
    }

    private enum class Filter { ALL, DEVICE, BACKED_UP, OTHER_DEVICE }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "photos-scan").apply { isDaemon = true } }
    private val thumbs = Executors.newFixedThreadPool(3) { r -> Thread(r, "photos-thumbs").apply { isDaemon = true } }

    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var local: List<Entry> = emptyList()
    private var backedUp: List<Entry> = emptyList()
    private var remote: List<Entry> = emptyList()
    private var merged: List<Entry> = emptyList()

    private var filter = Filter.ALL
    private var query = ""

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var adapter: TileAdapter
    private lateinit var empty: TextView
    private lateinit var backupButton: TextView

    private var scanning = false

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        build()
        PhotoRepository.start()
        refreshLocal()
        refreshBackups()
        askPeers()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A page is recreated on a theme change and when it scrolls out of the pager, so the
        // listings are re-read rather than trusted from the last instance.
        refreshBackups()
        askPeers()
    }

    // ── Chrome ────────────────────────────────────────────────────────────

    private fun build() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(context, 16f), IosUi.dp(context, 22f), IosUi.dp(context, 16f), 0)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(context).apply {
            text = "Photos"
            textSize = 28f
            setTextColor(IosUi.label(context))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        subtitle = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            text = "Reading the device…"
        }
        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        backupButton = IosUi.tintedButton(context, "Back up").apply {
            setOnClickListener { runBackup() }
            contentDescription = "Back up every photo and video now"
        }
        header.addView(backupButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(header)

        // Filters. One row rather than a menu: the counts are the point, and a menu would hide them.
        filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, IosUi.dp(context, 12f), 0, IosUi.dp(context, 6f))
        }
        column.addView(filterRow)

        empty = TextView(context).apply {
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(context))
            gravity = Gravity.CENTER
            setPadding(
                IosUi.dp(context, 20f), IosUi.dp(context, 40f),
                IosUi.dp(context, 20f), IosUi.dp(context, 20f),
            )
            visibility = View.GONE
            // Tapping the explanation asks for the permission it is explaining. A message with no
            // action is a dead end, and this one is the only way in on a fresh install.
            setOnClickListener { requestMediaPermission() }
        }
        column.addView(empty)

        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 24f))
        }
        adapter = TileAdapter()
        grid.adapter = adapter
        column.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rebuildFilters()
    }

    private fun rebuildFilters() {
        filterRow.removeAllViews()
        val counts = mapOf(
            Filter.ALL to merged.size,
            Filter.DEVICE to merged.count { it.origin == Origin.LOCAL },
            Filter.BACKED_UP to merged.count { it.backedUp },
            Filter.OTHER_DEVICE to merged.count { it.origin == Origin.REMOTE },
        )
        Filter.entries.forEach { option ->
            val chosen = option == filter
            val label = when (option) {
                Filter.ALL -> "All"
                Filter.DEVICE -> "Device"
                Filter.BACKED_UP -> "Backed up"
                Filter.OTHER_DEVICE -> "Other devices"
            }
            val chip = TextView(context).apply {
                text = "$label ${counts[option] ?: 0}"
                textSize = 12f
                setTextColor(if (chosen) IosUi.label(context) else IosUi.secondaryLabel(context))
                setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 7f), IosUi.dp(context, 12f), IosUi.dp(context, 7f))
                // The chip is drawn here rather than inflated: a pill is two lines of
                // GradientDrawable and the shape has to follow the theme's radius anyway.
                val mode = com.prism.launcher.theme.PrismThemes.modeFor(IosUi.isDark(context))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = IosUi.dp(
                        context,
                        (if (mode.shape.pill) 18 else mode.shape.card).toFloat(),
                    ).toFloat()
                    setColor(if (chosen) mode.palette.accent.toInt() else mode.palette.card.toInt())
                    if (!chosen) setStroke(1, mode.palette.divider.toInt())
                }
                if (chosen) {
                    setTextColor(mode.palette.onAccent.toInt())
                }
                setOnClickListener {
                    filter = option
                    rebuildFilters()
                    applyFilter()
                }
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = IosUi.dp(context, 6f) })
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private fun refreshLocal() {
        if (scanning) return
        if (!hasMediaPermission()) {
            subtitle.text = "Prism cannot read the camera roll yet"
            empty.text = "Photo access is needed to list this device's images and videos."
            empty.visibility = View.VISIBLE
            rebuildFilters()
            return
        }
        scanning = true
        subtitle.text = "Reading the device…"
        io.execute {
            val scanned = runCatching { PhotoRepository.scan(context) }.getOrElse {
                PrismLogger.logWarning(TAG, "Scan failed: ${it.message}")
                emptyList()
            }
            handler.post {
                scanning = false
                local = scanned
                mergeAndShow()
            }
        }
    }

    private fun refreshBackups() {
        io.execute {
            val stored = runCatching { PhotoBackup.localEntries() }.getOrDefault(emptyList())
            handler.post {
                backedUp = stored
                mergeAndShow()
            }
        }
    }

    /**
     * Asks every trusted device for its listing.
     *
     * Each answer replaces that device's entries rather than appending, so a photo deleted on the
     * peer stops being shown here. The callback can fire more than once (the listing is paginated),
     * which is why it is keyed by owner address.
     */
    private fun askPeers() {
        val peers = runCatching {
            TrustedDevices.all().filter { it.confirmed && it.lastIp.isNotBlank() }
        }.getOrDefault(emptyList())
        peers.forEach { device ->
            PhotoBackup.requestList(device.lastIp) { entries ->
                handler.post {
                    remote = remote.filterNot { it.ownerIp == device.lastIp } +
                        entries.map { it.copy(ownerIp = device.lastIp, ownerName = device.name.ifBlank { device.lastIp }) }
                    mergeAndShow()
                }
            }
        }
    }

    private fun mergeAndShow() {
        merged = PhotoBackup.merge(local, backedUp, remote)
        subtitle.text = buildString {
            append("${merged.size} item(s)")
            if (backedUp.isNotEmpty()) append(" · ${backedUp.size} backed up")
            val others = remote.map { it.ownerName }.distinct().filter { it.isNotBlank() }
            if (others.isNotEmpty()) append(" · from ${others.joinToString(", ")}")
        }
        rebuildFilters()
        applyFilter()
    }

    private fun applyFilter() {
        val needle = query.trim().lowercase()
        val visible = merged.filter { entry ->
            val kindOk = when (filter) {
                Filter.ALL -> true
                Filter.DEVICE -> entry.origin == Origin.LOCAL
                Filter.BACKED_UP -> entry.backedUp
                Filter.OTHER_DEVICE -> entry.origin == Origin.REMOTE
            }
            kindOk && (needle.isEmpty() || entry.name.lowercase().contains(needle))
        }
        adapter.submit(visible)
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            merged.isEmpty() && scanning -> "Reading the device…"
            merged.isEmpty() -> "No photos or videos yet."
            else -> "Nothing matches that filter."
        }
    }

    private fun runBackup() {
        if (backupButton.isEnabled.not()) return
        if (!PhotoBackup.enabled()) {
            subtitle.text = "Photo backup is off — turn it on in Prism Settings, under Other."
            return
        }
        backupButton.isEnabled = false
        backupButton.text = "Starting…"
        io.execute {
            val report = PhotoRepository.backupAll(context, local) { progress ->
                handler.post {
                    subtitle.text = "Backing up ${progress.done.coerceAtMost(progress.total)}/" +
                        "${progress.total} · ${progress.current}"
                }
            }
            handler.post {
                backupButton.isEnabled = true
                backupButton.text = "Back up"
                subtitle.text = report.describe()
                refreshBackups()
            }
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────

    private fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasMediaPermission(): Boolean = mediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Called by the host activity after a permission result. */
    fun onPermissionResult() {
        if (hasMediaPermission()) refreshLocal() else applyFilter()
    }

    private fun requestMediaPermission() {
        val activity = context as? Activity ?: return
        androidx.core.app.ActivityCompat.requestPermissions(activity, mediaPermissions(), 0x5048)
    }

    // ── Grid ──────────────────────────────────────────────────────────────

    private inner class TileAdapter : RecyclerView.Adapter<TileAdapter.Holder>() {

        private var items: List<Entry> = emptyList()

        fun submit(next: List<Entry>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val size = (resources.displayMetrics.widthPixels - IosUi.dp(context, 32f)) / COLUMNS
            val frame = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(size, size).apply {
                    val gap = IosUi.dp(context, 2f)
                    setMargins(gap, gap, gap, gap)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = IosUi.dp(context, 6f).toFloat()
                    setColor(com.prism.launcher.theme.PrismThemes.modeFor(IosUi.isDark(context)).palette.raised.toInt())
                }
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            frame.addView(image)
            return Holder(frame, image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.entry = entry
            holder.badge.text = when {
                entry.kind == PhotoBackup.Kind.VIDEO ->
                    if (entry.durationMs > 0) "▶ ${duration(entry.durationMs)}" else "▶"
                entry.origin == Origin.REMOTE -> "☁ ${entry.ownerName}"
                entry.backedUp -> "✓"
                else -> ""
            }
            holder.badge.visibility = if (holder.badge.text.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.badge.setTextColor(IosUi.label(context))
            holder.badge.setBackgroundColor(0x66000000)

            val key = "${entry.id}:$position"
            val cached = thumbnailCache.get(entry.id)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
            } else {
                holder.image.setImageDrawable(null)
                thumbs.execute {
                    val bitmap = runCatching { PhotoRepository.thumbnail(context, entry, 360) }.getOrNull()
                    if (bitmap != null) {
                        thumbnailCache.put(entry.id, bitmap)
                        handler.post {
                            if (holder.entry?.id == entry.id) holder.image.setImageBitmap(bitmap)
                        }
                    }
                }
            }

            holder.root.setOnClickListener { PhotoViewerActivity.start(context, entry) }
            holder.root.contentDescription = entry.name
        }

        inner class Holder(val root: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(root) {
            var entry: Entry? = null
            val badge = TextView(root.context).apply {
                textSize = 10f
                setPadding(
                    IosUi.dp(root.context, 6f), IosUi.dp(root.context, 2f),
                    IosUi.dp(root.context, 6f), IosUi.dp(root.context, 2f),
                )
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                )
            }

            init {
                root.addView(badge)
            }
        }
    }

    private fun duration(ms: Long): String {
        val total = ms / 1000
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')}" else "0:${seconds.toString().padStart(2, '0')}"
    }

    /** The launcher's back gesture, handed down. True when this page consumed it. */
    fun onBack(): Boolean {
        if (query.isNotEmpty()) {
            query = ""
            applyFilter()
            return true
        }
        if (filter != Filter.ALL) {
            filter = Filter.ALL
            rebuildFilters()
            applyFilter()
            return true
        }
        return false
    }
}
