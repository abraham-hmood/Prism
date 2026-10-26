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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.prism.launcher.LauncherActivity
import com.prism.launcher.DesktopShortcutStore
import com.prism.launcher.DesktopItem
import com.prism.launcher.FileOps
import com.prism.launcher.PrismSettings
import com.prism.launcher.clipboard.ClipboardHistory
import com.prism.launcher.clipboard.TrustedFileTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * Work that belongs to this view while it is on screen.
     *
     * A VIEW HAS NO lifecycleScope, which is why this was GlobalScope. The listing is read off the main
     * thread and applied back on it, so a coroutine still running after the view is gone would touch a
     * detached hierarchy. Cancelled in onDetachedFromWindow, which is the view's equivalent of the
     * Activity being destroyed.
     */
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.coroutineContext.cancelChildren()
    }

    private var currentPath: ExplorerPath = ExplorerPath.Root
    private val adapter: FileExplorerAdapter
    private val pathText: TextView
    private val searchBar: android.widget.EditText
    private var allEntries: List<FileEntry> = emptyList()
    private var externalVolumeRoots: List<FileEntry.ExternalStorageLink> = emptyList()
    private lateinit var pasteButton: ImageButton

    internal fun resolveAttr(context: Context, attr: Int): Int {
        com.prism.launcher.theme.ThemeSkin.resolveAttr(context, attr)?.let { return it }
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
        pasteButton = root.findViewById(R.id.fileExplorerPasteBtn)
        pasteButton.setOnClickListener { performPaste() }

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
            showOptions = { entry, onDismiss -> showOptions(entry, onDismiss) }
        )

        // Apply the theme's glow to the search field, if it has one. A flat theme leaves the
        // container bare and lets the themed TextInputLayout draw its own rectangular field.
        val glowContainer = root.findViewById<View>(R.id.fileSearchGlowContainer)
        glowContainer.background = com.prism.launcher.theme.ThemeSkin.glowDrawable(
            context,
            com.prism.launcher.nora.IosUi.mode(context).shape.field.toFloat(),
            3f,
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
            updatePasteButton()
        }
    }

    /**
     * The paste button exists while either clipboard holds something.
     *
     * TWO SOURCES, because a file copied here lives in [FileOps], and a file copied on another
     * device lives in the history as a remote entry with no local path yet. They are the same
     * thing to the user, so the button has to know about both or a shared cut item would arrive
     * with nowhere to paste it.
     */
    private fun updatePasteButton() {
        val active = ClipboardHistory.active()
        val sharedFile = active?.kind == ClipboardHistory.Kind.FILE
        pasteButton.visibility =
            if (FileExplorerClipboard.hasContent() || sharedFile) View.VISIBLE else View.GONE
    }

    private fun showGlobalMenu() {
        PrismDialogFactory.show(
            context,
            "Explorer Actions",
            "Global file system operations:",
            onPositive = {},
            customView = android.widget.ListView(context).apply {
                val actions = mutableListOf("Paste", "Connect to Network Storage")
                if (!FileExplorerClipboard.hasContent() &&
                    ClipboardHistory.active()?.kind != ClipboardHistory.Kind.FILE
                ) actions.remove("Paste")
                
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

    /**
     * Pastes the clipboard into the directory on screen.
     *
     * THE OPERATION IS [FileOps.paste], which owns the guards and is tested: a folder cannot be
     * pasted into itself, an existing name is disambiguated rather than overwritten, and a cut
     * across volumes falls back to copy-then-delete. This function only chooses the folder and
     * reports the outcome -- and it reports the specific reason on failure, which "Paste failed"
     * did not.
     */
    private fun performPaste() {
        val targetPath = currentPath
        val targetDir = (targetPath as? ExplorerPath.Local)?.dir
        if (targetDir == null) {
            Toast.makeText(
                context,
                "Open a folder before pasting" + if (targetPath is ExplorerPath.Network)
                    " -- remote upload is not supported yet" else "",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // A shared file has no local path until it is fetched. Doing it here, on paste, is what
        // makes the clipboard entry cheap: a device does not pull a gigabyte the moment somebody
        // copies it, only when the copy is actually used.
        val active = ClipboardHistory.active()
        if (active?.kind == ClipboardHistory.Kind.FILE) {
            if (active.remote) {
                downloadAndPaste(active, targetDir)
                return
            }
            val file = File(active.path)
            if (!file.exists()) {
                ClipboardHistory.remove(active.id)
                Toast.makeText(context, "That item is no longer on this device", Toast.LENGTH_SHORT).show()
                updatePasteButton()
                return
            }
            // Loaded into FileOps WITHOUT publishing it: this tap chooses an entry already on
            // this device, and re-sending it to every trusted device on each paste would turn a
            // paste into a broadcast.
            when (active.mode) {
                ClipboardHistory.Mode.CUT -> FileOps.cut(listOf(file))
                ClipboardHistory.Mode.COPY -> FileOps.copy(listOf(file))
            }
        } else if (!FileExplorerClipboard.hasContent()) {
            return
        }

        viewScope.launch {
            val result = withContext(Dispatchers.IO) { FileOps.paste(targetDir) }
            updatePasteButton()
            if (result.ok) {
                Toast.makeText(
                    context,
                    "Pasted ${result.pasted.firstOrNull()?.name.orEmpty()}",
                    Toast.LENGTH_SHORT,
                ).show()
                navigateTo(currentPath)
            } else {
                Toast.makeText(context, result.skipped.joinToString("\n"), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Downloads a file a trusted device shared, then shows it in this folder. */
    private fun downloadAndPaste(entry: ClipboardHistory.Entry, targetDir: File) {
        Toast.makeText(
            context,
            "Downloading ${entry.name} from ${entry.sourceName.ifBlank { "a trusted device" }}…",
            Toast.LENGTH_SHORT,
        ).show()
        viewScope.launch {
            val files = withContext(Dispatchers.IO) { TrustedFileTransfer.fetch(entry, targetDir) }
            updatePasteButton()
            if (files.isNullOrEmpty()) {
                Toast.makeText(
                    context,
                    "Could not fetch ${entry.name}. The other device may be offline, or the item " +
                        "may have already been moved.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "Received ${files.first().name}" +
                        (if (files.size > 1) " and ${files.size - 1} more" else ""),
                    Toast.LENGTH_SHORT,
                ).show()
                navigateTo(currentPath)
            }
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
                // listFiles()+sort ran synchronously on whatever thread called navigateTo,
                // which is a real block for large directories (Downloads, DCIM, ...). Listed
                // off-thread and applied back on Main, guarded against a stale result landing
                // after the user has already navigated elsewhere while this was still running.
                viewScope.launch {
                    val sorted = withContext(Dispatchers.IO) {
                        val files = dir.listFiles()?.toList() ?: emptyList()
                        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                            .map { FileEntry.Local(it) }
                    }
                    if (currentPath == path) {
                        allEntries = sorted
                        filterEntries(searchBar.text.toString())
                    }
                }
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
        // Name and path both go in the searchable text: people look for a file by either, and the
        // path is often the only thing they remember ("it was in that downloads folder").
        com.prism.launcher.history.PrismHistory.record(
            kind = com.prism.launcher.history.PrismHistory.Kind.FILE,
            title = file.name,
            uri = file.absolutePath,
            text = file.parent.orEmpty(),
            source = "Files",
        )
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

    private fun showOptions(entry: FileEntry, onDismiss: () -> Unit = {}) {
        val options = mutableListOf("Open", "Add to Desktop")
        if (entry is FileEntry.Local) {
            options.addAll(listOf("Move to Trash", "Cut", "Copy", "Rename", "Host as Website"))
        }

        val dialog = PrismDialogFactory.show(
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
                            FileExplorerClipboard.put(entry.file, FileExplorerClipboard.Mode.CUT)
                            updatePasteButton()
                            Toast.makeText(
                                context,
                                "${if (entry.file.isDirectory) "Folder" else "File"} cut -- " +
                                    "use the paste button in the top right",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        "Copy" -> if (entry is FileEntry.Local) {
                            FileExplorerClipboard.put(entry.file, FileExplorerClipboard.Mode.COPY)
                            updatePasteButton()
                            Toast.makeText(
                                context,
                                "${if (entry.file.isDirectory) "Folder" else "File"} copied -- " +
                                    "use the paste button in the top right",
                                Toast.LENGTH_SHORT,
                            ).show()
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
        // The row must not start opening files again until this menu is actually gone.
        dialog.setOnDismissListener { onDismiss() }
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

private object FileEntryDiff : DiffUtil.ItemCallback<FileEntry>() {
    fun key(e: FileEntry): String = when (e) {
        is FileEntry.Local -> "local:${e.file.absolutePath}"
        is FileEntry.Network -> "network:${e.storage.name}"
        is FileEntry.InternalStorageLink -> "internal"
        is FileEntry.AppStorageLink -> "appstorage"
        is FileEntry.ExternalStorageLink -> "external:${e.root.absolutePath}"
    }

    override fun areItemsTheSame(old: FileEntry, new: FileEntry) = key(old) == key(new)
    override fun areContentsTheSame(old: FileEntry, new: FileEntry) = old == new
}

class FileExplorerAdapter(
    private val onEntryClick: (FileEntry) -> Unit,
    private val onDragStarted: () -> Unit,
    private val onDragEnded: () -> Unit,
    /** Opens the row's menu; the second argument runs when that menu is dismissed. */
    private val showOptions: (FileEntry, () -> Unit) -> Unit
) : ListAdapter<FileEntry, FileExplorerAdapter.VH>(FileEntryDiff) {

    private val items: List<FileEntry> get() = currentList

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The last entry whose context menu was opened, and when.
     *
     * THE FLAG THAT STOPS A LONG PRESS FROM ALSO OPENING THE FILE IS NOT PER-BIND. The touch
     * listener is re-installed every time a row is rebound, and a dialog taking window focus can
     * rebuild or rebind the list underneath the finger -- which dropped a local `menuShown` back
     * to false and let the following ACTION_UP open the file under the menu. Held here, the
     * suppression survives a rebind and cannot be reset by one.
     */
    private var menuOpenedFor: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_grid, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.fileName.text = entry.name
        val ctx = holder.itemView.context

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
        
        holder.glassView.background = com.prism.launcher.theme.ThemeSkin.glowDrawable(
            ctx,
            com.prism.launcher.nora.IosUi.mode(ctx).shape.card.toFloat(),
            2f,
        )

        var isDragging = false
        var menuShown = false
        var startX = 0f
        var startY = 0f
        val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        val entryKey = FileEntryDiff.key(entry)

        // A row is rebound while this one's menu is still pending? Cancel the old timeout, or it
        // fires against an entry the row no longer shows.
        holder.pendingMenu?.let { handler.removeCallbacks(it) }

        val showMenuRunnable = Runnable {
            if (!isDragging) {
                menuShown = true
                menuOpenedFor = entryKey
                showOptions(entry) {
                    if (menuOpenedFor == entryKey) menuOpenedFor = null
                }
            }
        }
        holder.pendingMenu = showMenuRunnable

        holder.itemView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    menuShown = false
                    startX = event.rawX; startY = event.rawY
                    handler.removeCallbacks(showMenuRunnable)
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
                    if (!isDragging && !menuShown && event.action == android.view.MotionEvent.ACTION_UP) {
                        // The same rule stated where it cannot be lost to a rebind: this entry's
                        // menu is open, so this release is not a tap on the file underneath it.
                        if (menuOpenedFor != entryKey) onEntryClick(entry)
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

    override fun onViewRecycled(holder: VH) {
        // A recycled row must not leave a pending long-press that opens the menu for whatever the
        // holder is reassigned to.
        holder.pendingMenu?.let { handler.removeCallbacks(it) }
        holder.pendingMenu = null
        super.onViewRecycled(holder)
    }

    private fun isImage(ext: String) = ext in listOf("jpg", "jpeg", "png", "webp", "gif")
    private fun isVideo(ext: String) = ext in listOf("mp4", "mkv", "webm", "avi")
    private fun isAudio(ext: String) = ext in listOf("mp3", "wav", "ogg", "flac")

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val glassView: View = view.findViewById(R.id.glassBackground)
        /** Reused across rebinds of this holder instead of allocated fresh every bind. */
        var glowDrawable: com.prism.launcher.NeonGlowDrawable? = null
        /** The long-press timeout scheduled by the current bind, so a rebind or recycle cancels it. */
        var pendingMenu: Runnable? = null

        init {
            // NeonGlowDrawable's BlurMaskFilter isn't supported by hardware-accelerated Canvas.
            glassView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
}
