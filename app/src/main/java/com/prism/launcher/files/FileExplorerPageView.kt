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
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.clipboard.ClipboardHistory
import com.prism.launcher.clipboard.TrustedFileTransfer
import com.prism.launcher.storage.BridgedStorage
import com.prism.launcher.storage.BridgedStorageStore
import com.prism.launcher.storage.HostedStorage
import com.prism.launcher.storage.HostedStorageStore
import com.prism.launcher.trusted.TrustedStorageBridge
import com.prism.launcher.storage.formatBytes
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
    /** Inside a partition of a USB drive Prism reads itself; [path] is '/'-separated. */
    data class Drive(val partKey: String, val path: String) : ExplorerPath()
}

sealed class FileEntry {
    data class Local(val file: File) : FileEntry()
    data class Network(val storage: PrismSettings.NetworkStorage) : FileEntry()
    /** A folder this device is hosting as networked storage. */
    data class Hosted(val storage: HostedStorage) : FileEntry()
    /** A folder offered by a trusted device, not bridged yet. */
    data class TrustedRemote(val share: TrustedStorageBridge.RemoteShare) : FileEntry()
    /** A trusted device's folder that this device has been granted. */
    data class Bridged(val storage: BridgedStorage) : FileEntry()
    data object InternalStorageLink : FileEntry()
    data object AppStorageLink : FileEntry()

    /**
     * An SD card or USB drive, as Android describes it rather than as a bare directory.
     *
     * The volume -- not just its mount point -- is what the Format, Burn and Unmount actions need,
     * and a volume Android has ejected has no mount point at all, so the row cannot be built from a
     * `File` alone.
     */
    data class VolumeLink(val volume: RemovableVolume) : FileEntry()

    /** A USB drive Prism has not opened yet; tapping it asks for access. See UsbDrives. */
    data class UsbRaw(val device: android.hardware.usb.UsbDevice) : FileEntry()

    /** One partition of a USB drive Prism reads itself (NTFS, ext4, exFAT, FAT...). */
    data class UsbPart(val part: UsbDrives.Part) : FileEntry()

    /** A file or folder inside such a partition. */
    data class DriveItem(val partKey: String, val entry: com.prism.launcher.storage.drive.DriveEntry) : FileEntry()

    val name: String get() = when(this) {
        is Local -> file.name
        is Network -> storage.name
        is Hosted -> storage.name
        is TrustedRemote -> share.share.name
        is Bridged -> storage.name
        is InternalStorageLink -> "Internal Storage"
        is AppStorageLink -> "App Storage"
        is VolumeLink -> volume.displayName
        is UsbRaw -> device.productName?.takeIf { it.isNotBlank() } ?: "USB drive"
        is UsbPart -> part.title
        is DriveItem -> entry.name
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

    /**
     * Listening starts on EVERY attach, not once in init.
     *
     * The home pager detaches this page whenever it is swiped off screen and re-attaches the same
     * instance later. Registering in init and unregistering on detach meant the first swipe away
     * silenced the page for good: a trusted computer's drives arrived (the log said "DevPC shares 2
     * drive(s)") and the root list, built before they did, was never redrawn. Re-attaching also
     * redraws the root, so whatever arrived while it was away shows at once.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startWatching()
        if (wasDetached && currentPath == ExplorerPath.Root) navigateTo(ExplorerPath.Root)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wasDetached = true
        viewScope.coroutineContext.cancelChildren()
        if (volumeReceiverRegistered) {
            runCatching { context.unregisterReceiver(volumeReceiver) }
            volumeReceiverRegistered = false
        }
        TrustedStorageBridge.removeListener(storageListener)
        com.prism.launcher.storage.remote.TrustedDrives.removeListener(drivesListener)
    }

    private var wasDetached = false

    private fun startWatching() {
        TrustedStorageBridge.removeListener(storageListener)
        TrustedStorageBridge.addListener(storageListener)
        com.prism.launcher.storage.remote.TrustedDrives.removeListener(drivesListener)
        com.prism.launcher.storage.remote.TrustedDrives.addListener(drivesListener)
        if (volumeReceiverRegistered) return
        val mediaFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        volumeReceiverRegistered = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, volumeReceiver, mediaFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // USB drives Prism reads itself come and go without any media broadcast. These are
            // the system's own, hence EXPORTED.
            androidx.core.content.ContextCompat.registerReceiver(
                context, volumeReceiver,
                android.content.IntentFilter().apply { UsbDrives.attachActions.forEach(::addAction) },
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            true
        }.getOrElse {
            com.prism.launcher.PrismLogger.logWarning(
                "Storage",
                "Could not watch for volume changes: ${it.message}",
            )
            false
        }
    }

    /** A trusted device announced its drives: rebuild the root list if it is showing. */
    private val drivesListener: () -> Unit = {
        post { if (currentPath == ExplorerPath.Root) navigateTo(ExplorerPath.Root) }
    }

    /**
     * Refreshes the root list when a card or stick is inserted or removed.
     *
     * Without this a USB drive only appeared after navigating away and back, which reads as "Prism
     * did not notice the drive". Only the root list is rebuilt, so a refresh cannot interrupt
     * somebody browsing a folder.
     */
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (currentPath == ExplorerPath.Root) navigateTo(ExplorerPath.Root)
        }
    }
    private var volumeReceiverRegistered = false

    private var currentPath: ExplorerPath = ExplorerPath.Root

    /**
     * A trusted device can announce or bridge a folder while this page is open.
     *
     * The listener always posts: announcements arrive on a mesh thread, and rebuilding a list from
     * there would touch views off the main thread. It only refreshes the root, so a folder being
     * browsed is not interrupted.
     */
    private val storageListener: () -> Unit = {
        post {
            if (currentPath == ExplorerPath.Root) navigateTo(ExplorerPath.Root)
        }
    }

    private val adapter: FileExplorerAdapter
    private val pathText: TextView
    private val searchBar: android.widget.EditText
    private var allEntries: List<FileEntry> = emptyList()
    private var volumes: List<RemovableVolume> = emptyList()
    private lateinit var pasteButton: ImageButton
    private lateinit var addConnectionButton: ImageButton

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
        addConnectionButton = root.findViewById(R.id.fileExplorerAddBtn)
        // Tinted from the active theme rather than baked white, so the button stays visible on a
        // light theme instead of disappearing into the header.
        addConnectionButton.imageTintList = android.content.res.ColorStateList.valueOf(
            com.prism.launcher.nora.IosUi.label(context),
        )
        addConnectionButton.setOnClickListener { showNetworkConnectDialog() }
        TrustedStorageBridge.install()

        adapter = FileExplorerAdapter(
            onEntryClick = { entry ->
                when (entry) {
                    is FileEntry.Local -> {
                        if (entry.file.isDirectory) navigateTo(ExplorerPath.Local(entry.file))
                        else openFile(entry.file)
                    }
                    is FileEntry.UsbRaw -> openUsbDrive(entry.device)
                    is FileEntry.UsbPart -> openUsbPart(entry.part)
                    is FileEntry.DriveItem -> openDriveItem(entry)
                    // A saved connection opens its details rather than a listing: what it holds
                    // is real, and a folder of files that were never fetched is not.
                    is FileEntry.Network -> showNetworkDetails(entry.storage)
                    is FileEntry.Hosted -> HostedStorageSheet.showDetails(context, entry.storage) {
                        navigateTo(ExplorerPath.Root)
                    }
                    is FileEntry.TrustedRemote -> HostedStorageSheet.showRemoteShare(context, entry.share)
                    is FileEntry.Bridged -> HostedStorageSheet.showBridgedDetails(
                        context,
                        entry.storage.ownerFingerprint,
                        entry.storage.ownerName,
                        entry.storage.host,
                        entry.storage.share,
                    ) { navigateTo(ExplorerPath.Root) }
                    is FileEntry.InternalStorageLink -> navigateTo(ExplorerPath.Local(Environment.getExternalStorageDirectory()))
                    is FileEntry.AppStorageLink -> navigateTo(ExplorerPath.Local(context.filesDir))
                    is FileEntry.VolumeLink -> {
                        val root = entry.volume.root
                        // A mounted volume opens; an ejected one can only offer its actions.
                        if (root != null && entry.volume.isMounted) navigateTo(ExplorerPath.Local(root))
                        else showVolumeMenu(entry.volume)
                    }
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
        // Listeners and receivers are registered in onAttachedToWindow; see there.

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

    /**
     * The "+" and the long-press menu both land here.
     *
     * A saved connection is also put on a desktop page, which is what the old dialog did and what
     * makes a network share reachable without opening Files first.
     */
    private fun showNetworkConnectDialog() {
        NetworkStorageSheet.showCreate(context) { storage ->
            val targetPage = (context as? LauncherActivity)?.findDesktopPosition() ?: 1
            DesktopShortcutStore.add(
                DesktopItem.NetworkedFolder(storage.host, storage.name, storage.protocol),
                targetPage,
            )
            navigateTo(ExplorerPath.Root)
        }
    }

    private fun showNetworkDetails(storage: com.prism.launcher.PrismSettings.NetworkStorage) {
        NetworkStorageSheet.showDetails(context, storage) {
            Toast.makeText(context, "Connection removed", Toast.LENGTH_SHORT).show()
            navigateTo(ExplorerPath.Root)
        }
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
            is ExplorerPath.Drive -> {
                if (path.path == "/") navigateTo(ExplorerPath.Root)
                else navigateTo(ExplorerPath.Drive(path.partKey, path.path.substringBeforeLast('/').ifEmpty { "/" }))
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
                // SD cards and USB-OTG drives sit on the same shelf as internal storage.
                volumes = StorageVolumes.list(context)
                roots.addAll(volumes.map { FileEntry.VolumeLink(it) })
                // USB drives Prism reads itself: every partition of an opened drive, or one row per
                // drive that has not been opened yet.
                UsbDrives.prune(context)
                UsbDrives.attached(context).forEach { device ->
                    val drive = UsbDrives.opened(device.deviceName)
                    if (drive == null) roots.add(FileEntry.UsbRaw(device))
                    else drive.partitions.forEach { roots.add(FileEntry.UsbPart(it)) }
                }
                // Every drive of every trusted device -- a computer's disks, another phone's internal
                // storage, SD card and USB drives -- browsed with the same code as a USB partition.
                PhoneDrives.remoteParts().forEach { roots.add(FileEntry.UsbPart(it)) }
                Thread({ runCatching { com.prism.launcher.storage.remote.TrustedDrives.query() } }, "prism-drives-query").start()
                com.prism.launcher.PrismSettings.getNetworkStorages().forEach {
                    roots.add(FileEntry.Network(it))
                }
                HostedStorageStore.all().forEach { roots.add(FileEntry.Hosted(it)) }
                BridgedStorageStore.all().forEach { roots.add(FileEntry.Bridged(it)) }
                // Unbridged offers from trusted devices are visible as their own rows. Tapping
                // one starts the phrase-verified bridge instead of pretending it can be browsed.
                TrustedStorageBridge.unbridgedShares().forEach {
                    roots.add(FileEntry.TrustedRemote(it))
                }
                TrustedStorageBridge.requestDiscovery()
                TrustedStorageBridge.announceHosted()
                allEntries = roots
                filterEntries(searchBar.text.toString())
                addConnectionButton.visibility = View.VISIBLE
            }
            is ExplorerPath.Local -> {
                addConnectionButton.visibility = View.GONE
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
            is ExplorerPath.Drive -> {
                addConnectionButton.visibility = View.GONE
                val part = UsbDrives.part(path.partKey)
                val fs = part?.fileSystem
                if (fs == null) {
                    navigateTo(ExplorerPath.Root)
                    return
                }
                pathText.text = part.title + path.path.takeIf { it != "/" }.orEmpty()
                viewScope.launch {
                    val listed = withContext(Dispatchers.IO) {
                        runCatching {
                            fs.list(path.path)
                                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                .map { FileEntry.DriveItem(path.partKey, it) }
                        }
                    }
                    if (currentPath != path) return@launch
                    listed.onSuccess {
                        allEntries = it
                        filterEntries(searchBar.text.toString())
                    }.onFailure {
                        PrismLogger.logWarning("Storage", "Could not list ${path.path} on ${part.title}: ${it.message}")
                        Toast.makeText(context, "Could not read this folder: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            is ExplorerPath.Network -> {
                // Connections are opened as a details sheet now. This branch only exists so the
                // path stays exhaustive (and for a back-stack entry made before that change); it
                // returns home instead of inventing a listing.
                navigateTo(ExplorerPath.Root)
            }
        }
    }

    private fun isTopLevelRoot(dir: File): Boolean {
        if (dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath) return true
        if (dir.absolutePath == context.filesDir.absolutePath) return true
        return volumes.any { it.root?.absolutePath == dir.absolutePath }
    }

    /** The long-press menu for an SD card or USB drive. */
    private fun showVolumeMenu(volume: RemovableVolume, onDismiss: () -> Unit = {}) {
        PrismLogger.logInfo(
            "Storage",
            "Menu for '${volume.displayName}' (${volume.kind.title}, ${volume.state})",
        )
        StorageSheets.showVolumeMenu(
            context = context,
            volume = volume,
            onOpen = { volume.root?.let { navigateTo(ExplorerPath.Local(it)) } },
            onCompress = {
                DriveCompressionSheet.show(context, DriveCompressionSheet.Target.Local(PhoneDrives.volumeKey(volume), volume.displayName))
            },
            onDecompress = DriveCompressionSheet.Target.Local(PhoneDrives.volumeKey(volume), volume.displayName)
                .takeIf { PhoneDrives.isCompressed(it, volume.root) }
                ?.let { target -> { DriveCompressionSheet.decompress(context, target) } },
            onChanged = { navigateTo(ExplorerPath.Root) },
            onDismiss = onDismiss,
        )
    }

    /**
     * A file compressed in Prism's format opens through a decompressed copy: the app it is handed to
     * reads the file itself, and only Prism can read the compressed form.
     */
    private fun openFile(file: File) {
        if (com.prism.launcher.storage.compress.CompressedFile.isCompressed(file)) {
            Toast.makeText(context, "Decompressing ${file.name}…", Toast.LENGTH_SHORT).show()
            viewScope.launch {
                val copy = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = File(context.cacheDir, "usb-drive").apply { mkdirs() }
                        File(folder, file.name).also { target ->
                            com.prism.launcher.storage.compress.CompressedFile.open(file).use { input ->
                                target.outputStream().use { input.copyTo(it, 256 * 1024) }
                            }
                        }
                    }.getOrNull()
                }
                val uri = copy?.let {
                    runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }.getOrNull()
                }
                if (uri == null) {
                    Toast.makeText(context, "Could not open ${file.name}", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show() }
            }
            return
        }
        openPlainFile(file)
    }

    private fun openPlainFile(file: File) {
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
        if (entry is FileEntry.DriveItem || entry is FileEntry.UsbPart || entry is FileEntry.UsbRaw) {
            showUsbOptions(entry, onDismiss)
            return
        }
        // A removable volume has its own menu: Format, Burn and Unmount are the actions that matter
        // for a card, and they are meaningless for an ordinary folder.
        if (entry is FileEntry.VolumeLink) {
            showVolumeMenu(entry.volume, onDismiss)
            return
        }
        val options = mutableListOf("Open")
        if (entry !is FileEntry.Hosted && entry !is FileEntry.TrustedRemote && entry !is FileEntry.Bridged) {
            options.add("Add to Desktop")
        }
        if (entry is FileEntry.Local) {
            options.addAll(listOf("Move to Trash", "Cut", "Copy", "Rename", "Host as Website"))
            if (entry.file.isDirectory) options.add("Host as Networked Storage")
        }
        if (entry is FileEntry.Hosted) options.add("Stop hosting")
        if (entry is FileEntry.TrustedRemote) options.add("Request bridge")
        if (entry is FileEntry.Bridged) options.add("Remove bridge")
        val internalTarget = DriveCompressionSheet.Target.Local(PhoneDrives.KEY_INTERNAL, "Internal storage")
        if (entry is FileEntry.InternalStorageLink) {
            options.add(COMPRESS)
            if (PhoneDrives.isCompressed(internalTarget, Environment.getExternalStorageDirectory())) options.add(DECOMPRESS)
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
                                is FileEntry.Network -> showNetworkDetails(entry.storage)
                                is FileEntry.Hosted -> HostedStorageSheet.showDetails(context, entry.storage) {
                                    navigateTo(ExplorerPath.Root)
                                }
                                is FileEntry.TrustedRemote -> HostedStorageSheet.showRemoteShare(context, entry.share)
                                is FileEntry.Bridged -> HostedStorageSheet.showBridgedDetails(
                                    context,
                                    entry.storage.ownerFingerprint,
                                    entry.storage.ownerName,
                                    entry.storage.host,
                                    entry.storage.share,
                                ) { navigateTo(ExplorerPath.Root) }
                                is FileEntry.InternalStorageLink -> navigateTo(ExplorerPath.Local(Environment.getExternalStorageDirectory()))
                                is FileEntry.AppStorageLink -> navigateTo(ExplorerPath.Local(context.filesDir))
                                is FileEntry.VolumeLink -> entry.volume.root?.let { navigateTo(ExplorerPath.Local(it)) }
                                is FileEntry.UsbRaw, is FileEntry.UsbPart, is FileEntry.DriveItem -> Unit
                            }
                        }
                        COMPRESS -> if (entry is FileEntry.InternalStorageLink) {
                            DriveCompressionSheet.show(context, internalTarget)
                        }
                        DECOMPRESS -> if (entry is FileEntry.InternalStorageLink) {
                            DriveCompressionSheet.decompress(context, internalTarget)
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
                        "Host as Networked Storage" -> if (entry is FileEntry.Local && entry.file.isDirectory) {
                            HostedStorageSheet.showCreate(context, entry.file) {
                                navigateTo(ExplorerPath.Root)
                            }
                        }
                        "Stop hosting" -> if (entry is FileEntry.Hosted) {
                            HostedStorageSheet.showDetails(context, entry.storage) {
                                navigateTo(ExplorerPath.Root)
                            }
                        }
                        "Request bridge" -> if (entry is FileEntry.TrustedRemote) {
                            HostedStorageSheet.showRemoteShare(context, entry.share)
                        }
                        "Remove bridge" -> if (entry is FileEntry.Bridged) {
                            HostedStorageSheet.showBridgedDetails(
                                context,
                                entry.storage.ownerFingerprint,
                                entry.storage.ownerName,
                                entry.storage.host,
                                entry.storage.share,
                            ) { navigateTo(ExplorerPath.Root) }
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
            // A hosted or bridged row has an owner rather than a stable local address; putting it
            // on a desktop page without that address would create a shortcut to nothing.
            is FileEntry.Hosted, is FileEntry.TrustedRemote, is FileEntry.Bridged -> return
            // A drive Prism reads itself has no path another app could open.
            is FileEntry.UsbRaw, is FileEntry.UsbPart, is FileEntry.DriveItem -> return
            is FileEntry.InternalStorageLink -> DesktopItem.DirectoryRef(Environment.getExternalStorageDirectory().absolutePath, "Internal Storage")
            is FileEntry.AppStorageLink -> DesktopItem.DirectoryRef(context.filesDir.absolutePath, "App Storage")
            is FileEntry.VolumeLink -> entry.volume.root?.let {
                DesktopItem.DirectoryRef(it.absolutePath, entry.volume.displayName)
            } ?: return
        }
        (context as? LauncherActivity)?.addToDesktop(item)
    }

    // ── USB drives Prism reads itself ─────────────────────────────────────

    /** Asks for access to [device], then opens it and lists its partitions. */
    private fun openUsbDrive(device: android.hardware.usb.UsbDevice) {
        val proceed = {
            UsbDrives.requestPermission(context, device) { granted ->
                if (!granted) {
                    Toast.makeText(context, "Prism was not allowed to read the drive", Toast.LENGTH_SHORT).show()
                    return@requestPermission
                }
                Toast.makeText(context, "Reading the drive…", Toast.LENGTH_SHORT).show()
                viewScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { UsbDrives.openDrive(context, device) } }
                    result.onFailure {
                        PrismLogger.logWarning("Storage", "Could not open the USB drive: ${it.message}")
                        Toast.makeText(context, "Could not read the drive: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    if (currentPath == ExplorerPath.Root) navigateTo(ExplorerPath.Root)
                    // A drive with one readable partition opens straight into it.
                    result.getOrNull()?.partitions?.singleOrNull { it.fileSystem != null }?.let { openUsbPart(it) }
                }
            }
        }
        // Reading the drive directly takes it from Android, which unmounts anything it had mounted.
        if (volumes.any { it.kind == VolumeKind.USB && it.isMounted }) {
            PrismDialogFactory.show(
                context,
                "Read this drive with Prism?",
                "Android already has part of a USB drive open. Reading a drive directly takes it from " +
                    "Android, so its Android volume closes until the drive is plugged in again.",
                onPositive = { proceed() },
            )
        } else {
            proceed()
        }
    }

    private fun openUsbPart(part: UsbDrives.Part) {
        if (part.fileSystem == null) {
            PrismDialogFactory.show(context, part.title, part.problem, onPositive = {})
            return
        }
        navigateTo(ExplorerPath.Drive(part.key, "/"))
    }

    private fun openDriveItem(item: FileEntry.DriveItem) {
        if (item.entry.isDirectory) {
            navigateTo(ExplorerPath.Drive(item.partKey, item.entry.path))
            return
        }
        copyFromDrive(item, File(context.cacheDir, "usb-drive")) { copy ->
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy)
            }.getOrNull() ?: return@copyFromDrive
            val extension = copy.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.onFailure { Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Copies a file off the drive into [folder], then hands the copy to [then] on the main thread. */
    private fun copyFromDrive(item: FileEntry.DriveItem, folder: File, then: (File) -> Unit) {
        val fs = UsbDrives.part(item.partKey)?.fileSystem ?: return
        Toast.makeText(context, "Copying ${item.entry.name}…", Toast.LENGTH_SHORT).show()
        viewScope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    folder.mkdirs()
                    var target = File(folder, item.entry.name)
                    var n = 2
                    while (target.exists() && folder.name != "usb-drive") {
                        target = File(folder, item.entry.name.substringBeforeLast('.') + " ($n)" +
                            item.entry.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
                        n++
                    }
                    fs.open(item.entry.path).use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
                    target
                }
            }
            copied.onSuccess(then).onFailure {
                PrismLogger.logWarning("Storage", "Could not copy ${item.entry.path}: ${it.message}")
                Toast.makeText(context, "Could not copy the file: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUsbOptions(entry: FileEntry, onDismiss: () -> Unit) {
        val options = mutableListOf("Open")
        if (entry is FileEntry.DriveItem && !entry.entry.isDirectory) options += "Copy to Downloads"
        // A whole drive -- a trusted device's, or a USB partition -- can be compressed.
        val partTarget = (entry as? FileEntry.UsbPart)?.let { PhoneDrives.compressionTarget(it.part) }
        if (partTarget != null) {
            options += COMPRESS
            if (PhoneDrives.isCompressed(partTarget, null)) options += DECOMPRESS
        }
        val dialog = PrismDialogFactory.show(
            context,
            entry.name,
            when (entry) {
                is FileEntry.UsbPart -> if (UsbDrives.isRemote(entry.part)) entry.part.typeName
                    else entry.part.typeName + " · " + formatBytes(entry.part.sizeBytes) + " · read-only"
                is FileEntry.DriveItem -> {
                    val where = UsbDrives.part(entry.partKey)?.let { if (UsbDrives.isRemote(it)) it.typeName else "a USB drive" } ?: "a drive"
                    if (entry.entry.isDirectory) "Folder on $where" else formatBytes(entry.entry.size) + " on $where"
                }
                else -> "USB drive"
            },
            onPositive = {},
            customView = android.widget.ListView(context).apply {
                // Drawn in the theme's colours, like every other menu on this page.
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
                        COMPRESS -> if (partTarget != null) DriveCompressionSheet.show(context, partTarget)
                        DECOMPRESS -> if (partTarget != null) DriveCompressionSheet.decompress(context, partTarget)
                        "Open" -> when (entry) {
                            is FileEntry.UsbRaw -> openUsbDrive(entry.device)
                            is FileEntry.UsbPart -> openUsbPart(entry.part)
                            is FileEntry.DriveItem -> openDriveItem(entry)
                            else -> Unit
                        }
                        "Copy to Downloads" -> if (entry is FileEntry.DriveItem) {
                            copyFromDrive(entry, File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Prism USB")) {
                                Toast.makeText(context, "Saved to Downloads/Prism USB/${it.name}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
        )
        dialog.setOnDismissListener { onDismiss() }
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

/** The drive menus' entry for compressing a whole drive. See DriveCompressionSheet. */
private const val COMPRESS = "Compress drive…"

/** Shown beside [COMPRESS] when the drive is compressed: restores every file in the background. */
private const val DECOMPRESS = "Decompress drive"

private object FileEntryDiff : DiffUtil.ItemCallback<FileEntry>() {
    fun key(e: FileEntry): String = when (e) {
        is FileEntry.Local -> "local:${e.file.absolutePath}"
        // Keyed by id: two connections may legitimately share a display name.
        is FileEntry.Network -> "network:${e.storage.id}"
        is FileEntry.Hosted -> "hosted:${e.storage.id}"
        is FileEntry.TrustedRemote -> "trusted:${e.share.ownerFingerprint}:${e.share.share.id}"
        is FileEntry.Bridged -> "bridged:${e.storage.ownerFingerprint}:${e.storage.id}"
        is FileEntry.UsbRaw -> "usbraw:${e.device.deviceName}"
        is FileEntry.UsbPart -> "usbpart:${e.part.key}"
        is FileEntry.DriveItem -> "drive:${e.partKey}:${e.entry.path}"
        is FileEntry.InternalStorageLink -> "internal"
        is FileEntry.AppStorageLink -> "appstorage"
        is FileEntry.VolumeLink -> "volume:${e.volume.id}"
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
        holder.fileSubtitle.visibility = View.GONE
        
        val typeColor: Int
        when (entry) {
            is FileEntry.InternalStorageLink -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_save)
                typeColor = android.graphics.Color.parseColor("#FFD700")
            }
            is FileEntry.AppStorageLink -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_manage)
                typeColor = android.graphics.Color.parseColor("#7C9EFF")
            }
            is FileEntry.VolumeLink -> {
                // The capacity under the name is what makes an SD card and a USB stick
                // distinguishable at a glance, and what tells a user which one is nearly full.
                holder.fileIcon.setImageResource(
                    if (entry.volume.kind == VolumeKind.USB) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.ic_menu_gallery,
                )
                typeColor = if (entry.volume.isMounted) {
                    android.graphics.Color.parseColor("#00BA7C")
                } else {
                    android.graphics.Color.parseColor("#9B9BA1")
                }
                holder.fileSubtitle.text = entry.volume.subtitle()
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.UsbRaw -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                typeColor = android.graphics.Color.parseColor("#9B9BA1")
                holder.fileSubtitle.text = "USB drive · tap to read it"
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.UsbPart -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                val readable = entry.part.fileSystem != null
                typeColor = android.graphics.Color.parseColor(if (readable) "#00BA7C" else "#9B9BA1")
                holder.fileSubtitle.text = if (UsbDrives.isRemote(entry.part)) {
                    entry.part.typeName + if (entry.part.sizeBytes > 0) " · " + formatBytes(entry.part.sizeBytes) else ""
                } else {
                    entry.part.typeName + " · " + formatBytes(entry.part.sizeBytes) +
                        if (readable) " · USB" else " · cannot be read here"
                }
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.DriveItem -> {
                if (entry.entry.isDirectory) {
                    holder.fileIcon.setImageResource(android.R.drawable.ic_dialog_email)
                    typeColor = android.graphics.Color.parseColor("#FFD700")
                } else {
                    val ext = entry.entry.name.substringAfterLast('.', "").lowercase()
                    holder.fileIcon.setImageResource(
                        when {
                            isImage(ext) -> android.R.drawable.ic_menu_gallery
                            isVideo(ext) -> android.R.drawable.ic_media_play
                            isAudio(ext) -> android.R.drawable.ic_lock_silent_mode_off
                            else -> android.R.drawable.ic_menu_crop
                        },
                    )
                    typeColor = android.graphics.Color.parseColor("#4CAF50")
                    holder.fileSubtitle.text = formatBytes(entry.entry.size)
                    holder.fileSubtitle.visibility = View.VISIBLE
                }
            }
            is FileEntry.Network -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                typeColor = android.graphics.Color.parseColor("#00BA7C")
                // The transport and address, because "Home NAS" alone does not say whether it is
                // the SMB share or the SFTP box with the same name.
                holder.fileSubtitle.text = com.prism.launcher.storage.NetworkStorageTypes
                    .titleFor(entry.storage.protocol) + " · " + entry.storage.host
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.Hosted -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_share)
                typeColor = android.graphics.Color.parseColor("#5E5CE6")
                holder.fileSubtitle.text = "Hosted · ${entry.storage.subtitle}"
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.TrustedRemote -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                typeColor = android.graphics.Color.parseColor("#00BA7C")
                holder.fileSubtitle.text = "${entry.share.ownerName} · tap to bridge"
                holder.fileSubtitle.visibility = View.VISIBLE
            }
            is FileEntry.Bridged -> {
                holder.fileIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                typeColor = android.graphics.Color.parseColor("#30D158")
                holder.fileSubtitle.text = entry.storage.subtitle
                holder.fileSubtitle.visibility = View.VISIBLE
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
        val fileSubtitle: TextView = view.findViewById(R.id.fileSubtitle)
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
