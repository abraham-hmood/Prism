package com.prism.launcher.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.prism.launcher.PrismDialogFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.prism.launcher.LauncherActivity
import com.prism.launcher.DesktopShortcutStore
import com.prism.launcher.DesktopItem
import com.prism.launcher.PrismSettings

sealed class ExplorerPath {
    data object Root : ExplorerPath()
    data class Local(val dir: File) : ExplorerPath()
    data class Network(val storage: PrismSettings.NetworkStorage) : ExplorerPath()
}

sealed class FileEntry {
    data class Local(val file: File) : FileEntry()
    data class Network(val storage: PrismSettings.NetworkStorage) : FileEntry()
    data object InternalStorageLink : FileEntry()
    data object AppStorageLink : FileEntry()
    data class ExternalStorageLink(val label: String, val root: File) : FileEntry()

    val name: String get() = when(this) {
        is Local -> file.name
        is Network -> storage.name
        is InternalStorageLink -> "Internal Storage"
        is AppStorageLink -> "App Storage"
        is ExternalStorageLink -> label
    }
}

class FileExplorerPageView(context: Context) : FrameLayout(context) {

    private var currentPath: ExplorerPath = ExplorerPath.Root
    private val adapter: FileExplorerAdapter
    private val pathText: TextView
    private val searchBar: android.widget.EditText
    private var allEntries: List<FileEntry> = emptyList()
    private var externalVolumeRoots: List<FileEntry.ExternalStorageLink> = emptyList()

    internal fun resolveAttr(context: Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.page_file_explorer, this, true)
        
        pathText = root.findViewById(R.id.fileExplorerPath)
        val backBtn = root.findViewById<ImageView>(R.id.fileExplorerBackBtn)
        val recycler = root.findViewById<RecyclerView>(R.id.fileExplorerList)
        searchBar = root.findViewById(R.id.fileSearchBar)

        adapter = FileExplorerAdapter(
            onEntryClick = { entry ->
                when (entry) {
                    is FileEntry.Local -> {
                        if (entry.file.isDirectory) navigateTo(ExplorerPath.Local(entry.file))
                        else openFile(entry.file)
                    }
                    is FileEntry.Network -> navigateTo(ExplorerPath.Network(entry.storage))
                    is FileEntry.InternalStorageLink -> navigateTo(ExplorerPath.Local(Environment.getExternalStorageDirectory()))
                    is FileEntry.AppStorageLink -> navigateTo(ExplorerPath.Local(context.filesDir))
                    is FileEntry.ExternalStorageLink -> navigateTo(ExplorerPath.Local(entry.root))
                }
            },
            onDragStarted = {
                (context as? com.prism.launcher.LauncherActivity)?.apply {
                    setBirdsEyeZoom(true)
                    switchToDesktop(400) // Tiny delay as requested
                }
            },
            onDragEnded = {
                (context as? com.prism.launcher.LauncherActivity)?.setBirdsEyeZoom(false)
            },
            showOptions = { showOptions(it) }
        )

        // Apply Glow to Search Bar
        val glowContainer = root.findViewById<View>(R.id.fileSearchGlowContainer)
        glowContainer.background = com.prism.launcher.NeonGlowDrawable(
            color = com.prism.launcher.PrismSettings.getGlowColor(),
            cornerRadius = 24f * resources.displayMetrics.density,
            strokeWidth = 3f * resources.displayMetrics.density
        )

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterEntries(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        (context as? com.prism.launcher.LauncherActivity)?.attachFileExplorerPage(this)

        // 4-column balanced grid
        recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 4)
        recycler.adapter = adapter

        // Background Long Click
        root.setOnLongClickListener {
            showGlobalMenu()
            true
        }
        recycler.setOnLongClickListener {
            showGlobalMenu()
            true
        }

        backBtn.setOnClickListener { handleBack() }

        // Delay initial load to ensure everything is ready
        post {
            navigateTo(ExplorerPath.Root)
            checkPermissionsAndLoad()
        }
    }

    private fun showGlobalMenu() {
        PrismDialogFactory.show(
            context,
            "Explorer Actions",
            "Global file system operations:",
            onPositive = {},
            customView = android.widget.ListView(context).apply {
                val actions = mutableListOf("Paste", "Connect to Network Storage")
                if (FileExplorerClipboard.getCutFile() == null) actions.remove("Paste")
                
                adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, actions) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as? TextView)?.apply {
                            setTextColor(this@FileExplorerPageView.resolveAttr(context, com.prism.launcher.R.attr.prismTextPrimary))
                            setPadding(48, 48, 48, 48)
                        }
                        return view
                    }
                }
                setOnItemClickListener { _, _, position, _ ->
                    when (actions[position]) {
                        "Paste" -> performPaste()
                        "Connect to Network Storage" -> showNetworkConnectDialog()
                    }
                }
            }
        )
    }

    private fun performPaste() {
        val cutFile = FileExplorerClipboard.getCutFile() ?: return
        val targetPath = currentPath
        if (targetPath is ExplorerPath.Local) {
            val target = File(targetPath.dir, cutFile.name)
            if (cutFile.renameTo(target)) {
                FileExplorerClipboard.clear()
                navigateTo(targetPath)
            } else {
                Toast.makeText(context, "Paste failed", Toast.LENGTH_SHORT).show()
            }
        } else if (targetPath is ExplorerPath.Network) {
            // Simulated "Upload" via paste
            Toast.makeText(context, "Uploading ${cutFile.name} to ${targetPath.storage.name}...", Toast.LENGTH_LONG).show()
            FileExplorerClipboard.clear()
        } else {
            Toast.makeText(context, "Cannot paste in Root", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNetworkConnectDialog() {
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        
        val nameIn = android.widget.EditText(context).apply { hint = "Storage Name (e.g. My PC)" }
        val protIn = android.widget.EditText(context).apply { hint = "Protocol (ftp, p2p, webdav)" }
        val hostIn = android.widget.EditText(context).apply { hint = "Host / IP / Node ID" }
        val portIn = android.widget.EditText(context).apply { hint = "Port (e.g. 21)" }
        val userIn = android.widget.EditText(context).apply { hint = "Username (optional)" }
        val passIn = android.widget.EditText(context).apply { hint = "Password (optional)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }

        layout.addView(nameIn)
        layout.addView(protIn)
        layout.addView(hostIn)
        layout.addView(portIn)
        layout.addView(userIn)
        layout.addView(passIn)

        PrismDialogFactory.show(
            context,
            "Connect to Network",
            "Enter connection details:",
            onPositive = {
                val storage = com.prism.launcher.PrismSettings.NetworkStorage(
                    name = nameIn.text.toString().trim().ifEmpty { "Network Storage" },
                    protocol = protIn.text.toString().trim().lowercase().ifEmpty { "ftp" },
                    host = hostIn.text.toString().trim(),
                    port = portIn.text.toString().toIntOrNull() ?: 21,
                    username = userIn.text.toString(),
                    password = passIn.text.toString()
                )
                com.prism.launcher.PrismSettings.addNetworkStorage(storage)
                
                // Add to random desktop page as requested
                val targetPage = (context as? LauncherActivity)?.findDesktopPosition() ?: 1
                DesktopShortcutStore.add(DesktopItem.NetworkedFolder(storage.host, storage.name, storage.protocol), targetPage)
                
                Toast.makeText(context, "Connected and added to Desktop", Toast.LENGTH_SHORT).show()
                navigateTo(currentPath) // Refresh view
            },
            customView = layout
        )
    }

    private fun filterEntries(query: String?) {
        val q = query ?: ""
        if (q.isEmpty()) {
            adapter.submitList(allEntries)
        } else {
            val filtered = allEntries.filter { it.name.contains(q, ignoreCase = true) }
            adapter.submitList(filtered)
        }
    }

    private fun checkPermissionsAndLoad() {
        val prefs = context.getSharedPreferences("prism_explorer", Context.MODE_PRIVATE)
        val isFirstVisit = prefs.getBoolean("first_explorer_visit", true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                if (isFirstVisit) {
                    prefs.edit().putBoolean("first_explorer_visit", false).apply()
                    com.prism.launcher.PrismDialogFactory.show(
                        context,
                        "Storage Access Required",
                        "Prism needs 'All Files Access' to power the OS file system. Please enable it in the next screen.",
                        onPositive = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            } else {
                navigateTo(currentPath)
            }
        } else {
            navigateTo(currentPath)
        }
    }

    fun handleBack(): Boolean {
        return when (val path = currentPath) {
            is ExplorerPath.Root -> false
            is ExplorerPath.Local -> {
                if (isTopLevelRoot(path.dir)) {
                    navigateTo(ExplorerPath.Root)
                } else {
                    val parent = path.dir.parentFile
                    if (parent != null && parent.canRead()) {
                        navigateTo(ExplorerPath.Local(parent))
                    } else {
                        navigateTo(ExplorerPath.Root)
                    }
                }
                true
            }
            is ExplorerPath.Network -> {
                navigateTo(ExplorerPath.Root)
                true
            }
        }
    }

    private fun navigateTo(path: ExplorerPath) {
        currentPath = path
        when (path) {
            is ExplorerPath.Root -> {
                pathText.text = "Prism Home"
                val roots = mutableListOf<FileEntry>()
                roots.add(FileEntry.InternalStorageLink)
                roots.add(FileEntry.AppStorageLink)
                externalVolumeRoots = discoverExternalVolumes(context)
                roots.addAll(externalVolumeRoots)
                com.prism.launcher.PrismSettings.getNetworkStorages().forEach {
                    roots.add(FileEntry.Network(it))
                }
                allEntries = roots
                filterEntries(searchBar.text.toString())
            }
            is ExplorerPath.Local -> {
                val dir = path.dir
                if (!dir.canRead()) {
                    Toast.makeText(context, "Access Denied: ${dir.name}", Toast.LENGTH_SHORT).show()
                    navigateTo(ExplorerPath.Root)
                    return
                }
                pathText.text = dir.absolutePath
                val files = dir.listFiles()?.toList() ?: emptyList()
                allEntries = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { FileEntry.Local(it) }
                filterEntries(searchBar.text.toString())
            }
            is ExplorerPath.Network -> {
                pathText.text = "${path.storage.protocol.uppercase()} // ${path.storage.name}"
                // Simulate network listing
                val mockFiles = listOf(
                    FileEntry.Local(File("/mock/shared_data.pdf")),
                    FileEntry.Local(File("/mock/mesh_backup.task")),
                    FileEntry.Local(File("/mock/photos_p2p/"))
                )
                allEntries = mockFiles
                filterEntries(searchBar.text.toString())
            }
        }
    }

    /**
     * Every non-primary storage volume (SD card, USB-OTG drive) exposed to this app, with a
     * human-readable label. `getExternalFilesDirs()` only ever gives back an app-specific
     * subdirectory per volume (index 0 is always the primary/internal volume, already handled
     * separately) — the real volume root is derived by stripping the fixed
     * `/Android/data/<pkg>/files` suffix, the standard technique for reaching a removable
     * volume's true root pre-API-30. Readable once "All Files Access" is granted, same as
     * Internal Storage.
     */
    private fun discoverExternalVolumes(context: Context): List<FileEntry.ExternalStorageLink> {
        val appFilesDirs = context.getExternalFilesDirs(null)
        if (appFilesDirs.size <= 1) return emptyList()

        val descriptions = try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes
        } catch (e: Exception) {
            emptyList()
        }

        val result = mutableListOf<FileEntry.ExternalStorageLink>()
        for (i in 1 until appFilesDirs.size) {
            val appDir = appFilesDirs[i] ?: continue
            val root = appDir.parentFile?.parentFile?.parentFile?.parentFile ?: continue
            if (!root.exists() || !root.canRead()) continue
            val label = descriptions.getOrNull(i)?.getDescription(context) ?: "External Storage $i"
            result.add(FileEntry.ExternalStorageLink(label, root))
        }
        return result
    }

    private fun isTopLevelRoot(dir: File): Boolean {
        if (dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath) return true
        if (dir.absolutePath == context.filesDir.absolutePath) return true
        return externalVolumeRoots.any { it.root.absolutePath == dir.absolutePath }
    }

    private fun openFile(file: File) {
        try {
            val policy = StrictMode.VmPolicy.Builder().build()
            StrictMode.setVmPolicy(policy)

            val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath).lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(file), mimeType)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptUpload() {
        Toast.makeText(context, "Select a local file to upload...", Toast.LENGTH_SHORT).show()
        postDelayed({
            Toast.makeText(context, "File uploaded successfully!", Toast.LENGTH_SHORT).show()
        }, 1200)
    }

    private fun showOptions(entry: FileEntry) {
        val options = mutableListOf("Open", "Add to Desktop")
        if (entry is FileEntry.Local) {
            options.addAll(listOf("Move to Trash", "Cut", "Rename", "Host as Website"))
        }

        PrismDialogFactory.show(
            context,
            entry.name,
            "Choose an action:",
            onPositive = {},
            customView = android.widget.ListView(context).apply {
                divider = android.graphics.drawable.ColorDrawable(0xFFEEEEEE.toInt())
                dividerHeight = 1
                adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, options) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as? TextView)?.apply {
                            setTextColor(this@FileExplorerPageView.resolveAttr(context, com.prism.launcher.R.attr.prismTextPrimary))
                            setPadding(48, 48, 48, 48)
                        }
                        return view
                    }
                }
                setOnItemClickListener { _, _, position, _ ->
                    when (options[position]) {
                        "Open" -> {
                            when (entry) {
                                is FileEntry.Local -> if (entry.file.isDirectory) navigateTo(ExplorerPath.Local(entry.file)) else openFile(entry.file)
                                is FileEntry.Network -> navigateTo(ExplorerPath.Network(entry.storage))
                                is FileEntry.InternalStorageLink -> navigateTo(ExplorerPath.Local(Environment.getExternalStorageDirectory()))
                                is FileEntry.AppStorageLink -> navigateTo(ExplorerPath.Local(context.filesDir))
                                is FileEntry.ExternalStorageLink -> navigateTo(ExplorerPath.Local(entry.root))
                            }
                        }
                        "Move to Trash" -> if (entry is FileEntry.Local) moveToTrash(entry.file)
                        "Add to Desktop" -> addToDesktop(entry)
                        "Cut" -> if (entry is FileEntry.Local) {
                            FileExplorerClipboard.setCutFile(entry.file)
                            Toast.makeText(context, "File cut", Toast.LENGTH_SHORT).show()
                        }
                        "Rename" -> if (entry is FileEntry.Local) promptRename(entry.file)
                        "Host as Website" -> if (entry is FileEntry.Local) {
                            val intent = Intent(context, com.prism.launcher.browser.P2pHostingActivity::class.java)
                            intent.putExtra("PREFILL_PATH", entry.file.absolutePath)
                            context.startActivity(intent)
                        }
                    }
                }
            }
        )
    }

    private fun moveToTrash(file: File) {
        val trashDir = File(Environment.getExternalStorageDirectory(), ".prism_trash")
        if (!trashDir.exists()) trashDir.mkdirs()
        
        val target = File(trashDir, file.name)
        if (file.renameTo(target)) {
            navigateTo(currentPath)
            Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToDesktop(entry: FileEntry) {
        val item = when (entry) {
            is FileEntry.Local -> {
                if (entry.file.isDirectory) DesktopItem.DirectoryRef(entry.file.absolutePath, entry.file.name)
                else DesktopItem.FileRef(entry.file.absolutePath)
            }
            is FileEntry.Network -> {
                val s = entry.storage
                DesktopItem.NetworkedFolder(s.host, s.name, s.protocol)
            }
            is FileEntry.InternalStorageLink -> DesktopItem.DirectoryRef(Environment.getExternalStorageDirectory().absolutePath, "Internal Storage")
            is FileEntry.AppStorageLink -> DesktopItem.DirectoryRef(context.filesDir.absolutePath, "App Storage")
            is FileEntry.ExternalStorageLink -> DesktopItem.DirectoryRef(entry.root.absolutePath, entry.label)
        }
        (context as? LauncherActivity)?.addToDesktop(item)
    }

    private fun promptRename(file: File) {
        val input = android.widget.EditText(context).apply { setText(file.name) }
        PrismDialogFactory.show(
            context,
            "Rename",
            "Enter new name:",
            onPositive = {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val target = File(file.parentFile, newName)
                    if (file.renameTo(target)) {
                        navigateTo(currentPath)
                    } else {
                        Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            customView = input
        )
    }
}

class FileExplorerAdapter(
    private val onEntryClick: (FileEntry) -> Unit,
    private val onDragStarted: () -> Unit,
    private val onDragEnded: () -> Unit,
    private val showOptions: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileExplorerAdapter.VH>() {

    private var items: List<FileEntry> = emptyList()

    fun submitList(newItems: List<FileEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_grid, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.fileName.text = entry.name
        val ctx = holder.itemView.context
        val glowColor = com.prism.launcher.PrismSettings.getGlowColor()

        // Reset state
        holder.fileIcon.colorFilter = null
        
        val typeColor: Int
        when (entry) {
            is FileEntry.InternalStorageLink -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_dialog_email)
                typeColor = android.graphics.Color.parseColor("#FFD700")
            }
            is FileEntry.AppStorageLink -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_dialog_email)
                typeColor = android.graphics.Color.parseColor("#7C9EFF")
            }
            is FileEntry.ExternalStorageLink -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_dialog_email)
                typeColor = android.graphics.Color.parseColor("#00BA7C")
            }
            is FileEntry.Network -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                typeColor = android.graphics.Color.parseColor("#00BA7C")
            }
            is FileEntry.Local -> {
                val file = entry.file
                if (file.isDirectory) {
                    holder.fileIcon.setImageResource(android.R.drawable.ic_dialog_email)
                    typeColor = android.graphics.Color.parseColor("#FFD700")
                } else {
                    val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.absolutePath).lowercase()
                    when {
                        isImage(ext) -> {
                            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                            typeColor = android.graphics.Color.parseColor("#FFC107")
                        }
                        isVideo(ext) -> {
                            holder.fileIcon.setImageResource(android.R.drawable.ic_media_play)
                            typeColor = android.graphics.Color.parseColor("#FF9800")
                        }
                        isAudio(ext) -> {
                            holder.fileIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                            typeColor = android.graphics.Color.parseColor("#03A9F4")
                        }
                        else -> {
                            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_crop)
                            typeColor = android.graphics.Color.parseColor("#4CAF50")
                        }
                    }
                }
            }
        }

        holder.fileIcon.setColorFilter(typeColor)
        
        holder.glassView.background = com.prism.launcher.NeonGlowDrawable(
            color = glowColor,
            cornerRadius = 16f * ctx.resources.displayMetrics.density,
            strokeWidth = 2f * ctx.resources.displayMetrics.density
        )

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var isDragging = false
        var startX = 0f
        var startY = 0f
        val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        
        val showMenuRunnable = Runnable { if (!isDragging) showOptions(entry) }

        holder.itemView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    startX = event.rawX; startY = event.rawY
                    handler.postDelayed(showMenuRunnable, 500)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        if (!isDragging) {
                            isDragging = true
                            handler.removeCallbacks(showMenuRunnable)
                            if (entry is FileEntry.Local) {
                                val item = android.content.ClipData.Item(entry.file.absolutePath)
                                val dragData = android.content.ClipData("prism_file", arrayOf(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN), item)
                                v.startDragAndDrop(dragData, View.DragShadowBuilder(v), null, 0)
                                onDragStarted()
                            }
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(showMenuRunnable)
                    if (!isDragging && event.action == android.view.MotionEvent.ACTION_UP) {
                        onEntryClick(entry)
                    }
                }
            }
            true
        }

        holder.itemView.setOnDragListener { _, event ->
            if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) onDragEnded()
            true
        }
    }

    private fun isImage(ext: String) = ext in listOf("jpg", "jpeg", "png", "webp", "gif")
    private fun isVideo(ext: String) = ext in listOf("mp4", "mkv", "webm", "avi")
    private fun isAudio(ext: String) = ext in listOf("mp3", "wav", "ogg", "flac")

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val glassView: View = view.findViewById(R.id.glassBackground)
    }
}
