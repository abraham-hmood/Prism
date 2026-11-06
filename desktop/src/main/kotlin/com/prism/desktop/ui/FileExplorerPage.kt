package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.DesktopItem
import com.prism.launcher.FileOps
import com.prism.launcher.NetworkStorage
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * The file explorer, at parity with the Android page.
 *
 * PHASE 3 SHIPPED A VIEWER; this is the manager. Cut, copy, paste, multi-select, rename, delete,
 * new folder, four sort modes with direction, drag to the desktop, and network locations listed
 * as roots.
 *
 * THE OPERATIONS THEMSELVES LIVE IN :core, in [FileOps], and are tested there. That split is
 * deliberate: they are the part that can destroy a user's data -- pasting a folder into itself,
 * a rename that overwrites, a half-completed cut -- and none of those failures are visible from
 * the UI. This file is selection state and layout.
 *
 * SELECTION IS BY ABSOLUTE PATH, not by [File] identity or list index. A refresh rebuilds the
 * list, so index-based selection silently selects the wrong files after any change, and File
 * equality is path equality anyway -- being explicit about it stops someone "optimizing" it into
 * a bug.
 */
@Composable
fun FileExplorerPage() {
    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current

    val roots = remember { FileOps.roots() }
    var current by remember { mutableStateOf(roots.firstOrNull() ?: File(".")) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sort by remember { mutableStateOf(FileOps.Sort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    // Ctrl/Cmd+V belongs to this page while it is open, the same way it belongs to a file manager.
    // Compose routes keys through focus, so the page has to take it to receive it.
    val keyFocus = remember { FocusRequester() }
    var status by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    // A drive right-clicked in the sidebar, whose compression dialog is open.
    var compressTarget by remember { mutableStateOf<DriveCompressionUi.Target?>(null) }

    val networks = remember { PrismSettings.getNetworkStorages() }

    // USB drives, re-read every few seconds while the page is open: a stick plugged in (or pulled)
    // shows up without leaving the page. See DesktopDrives for how each OS mounts them.
    var drives by remember { mutableStateOf<List<com.prism.desktop.DesktopDrives.Drive>>(emptyList()) }
    var driveBusy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            drives = withContext(Dispatchers.IO) { com.prism.desktop.DesktopDrives.list() }
            kotlinx.coroutines.delay(4_000)
        }
    }

    // Remote browsing state. Null means the local filesystem is showing; when set, the listing
    // pane shows the remote share instead. Kept separate from `current` rather than pretending a
    // remote path is a File -- java.io.File cannot represent one, and faking it with a URI-shaped
    // path is how you end up accidentally deleting something local.
    var remote by remember { mutableStateOf<PrismSettings.NetworkStorage?>(null) }
    var remotePath by remember { mutableStateOf("") }
    var remoteResult by remember { mutableStateOf<NetworkStorage.Result?>(null) }
    var remoteBusy by remember { mutableStateOf(false) }

    // A trusted device's drive the OS could not mount, browsed directly instead. A mounted one is
    // just a folder (a drive letter, or a GVfs path) and opens in the ordinary listing.
    var remoteDrive by remember { mutableStateOf<com.prism.desktop.DesktopRemoteDrives.Mounted?>(null) }
    var remoteDrivePath by remember { mutableStateOf("/") }
    var remoteDriveItems by remember { mutableStateOf<Result<List<com.prism.launcher.storage.remote.DriveItem>>?>(null) }
    LaunchedEffect(remoteDrive, remoteDrivePath, refreshToken) {
        val drive = remoteDrive ?: return@LaunchedEffect
        remoteDriveItems = null
        remoteDriveItems = withContext(Dispatchers.IO) {
            runCatching {
                val access = com.prism.desktop.DesktopRemoteDrives.access(drive) ?: error("${drive.device} is offline")
                access.list(remoteDrivePath).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.prism.launcher.storage.remote.TrustedDrives.query() } }

    LaunchedEffect(remote, remotePath) {
        val storage = remote ?: return@LaunchedEffect
        remoteBusy = true
        remoteResult = withContext(Dispatchers.IO) { NetworkStorage.browse(storage, remotePath) }
        remoteBusy = false
    }

    LaunchedEffect(current, sort, descending, showHidden, refreshToken) {
        entries = withContext(Dispatchers.IO) {
            val all = (current.listFiles() ?: emptyArray()).toList()
                .filter { showHidden || !it.isHidden }
            FileOps.sorted(all, sort, descending)
        }
        // Selections that no longer exist are dropped rather than silently acting on ghosts.
        val present = entries.map { it.absolutePath }.toSet()
        selected = selected intersect present
    }

    fun refresh() { refreshToken++ }

    val selectedFiles = remember(selected, entries) {
        entries.filter { it.absolutePath in selected }
    }

    /**
     * The paste action, shared by the toolbar and Ctrl/Cmd+V.
     *
     * ONE IMPLEMENTATION because the shortcut and the button must do exactly the same thing --
     * including fetching a file a trusted device shared, which is the case a second copy would
     * most easily get wrong.
     */
    fun pasteClipboard() {
        scope.launch {
            val shared = com.prism.launcher.clipboard.ClipboardHistory.active()
            if (shared?.kind == com.prism.launcher.clipboard.ClipboardHistory.Kind.FILE) {
                if (shared.remote) {
                    status = "Downloading ${shared.name}…"
                    val received = withContext(Dispatchers.IO) {
                        com.prism.launcher.clipboard.TrustedFileTransfer.fetch(shared, current)
                    }
                    if (received.isNullOrEmpty()) {
                        status = "Could not fetch ${shared.name}; the other device may be offline"
                        refresh()
                        return@launch
                    }
                } else {
                    val file = File(shared.path)
                    if (!file.exists()) {
                        status = "${shared.name} is no longer on this device"
                        com.prism.launcher.clipboard.ClipboardHistory.remove(shared.id)
                        refresh()
                        return@launch
                    }
                    // Loaded into FileOps without re-sharing: this is choosing an item that is
                    // already here, not a new copy.
                    when (shared.mode) {
                        com.prism.launcher.clipboard.ClipboardHistory.Mode.CUT -> FileOps.cut(listOf(file))
                        com.prism.launcher.clipboard.ClipboardHistory.Mode.COPY -> FileOps.copy(listOf(file))
                    }
                }
            }
            val result = withContext(Dispatchers.IO) { FileOps.paste(current) }
            status = if (result.ok) "Pasted ${result.pasted.size}"
            else result.skipped.joinToString("; ")
            refresh()
        }
    }

    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(keyFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val paste = event.type == KeyEventType.KeyDown &&
                    (event.isCtrlPressed || event.isMetaPressed) &&
                    event.key == Key.V
                if (paste) pasteClipboard()
                paste
            }
    ) {
        ExplorerToolbar(
            current = current,
            selectionCount = selectedFiles.size,
            clipboardCount = FileOps.clipboard()?.files?.size
                ?: if (com.prism.launcher.clipboard.ClipboardHistory.active()?.kind ==
                    com.prism.launcher.clipboard.ClipboardHistory.Kind.FILE) 1 else 0,
            sort = sort,
            descending = descending,
            showHidden = showHidden,
            onUp = { current.parentFile?.let { current = it; selected = emptySet() } },
            onSort = { s -> if (s == sort) descending = !descending else { sort = s; descending = false } },
            onToggleHidden = { showHidden = !showHidden },
            onCopy = {
                FileOps.copy(selectedFiles)
                // Every copy is recorded and shared. The history is what makes the same item
                // available in the explorer on another device, and the share is what puts it
                // there; doing it here covers the toolbar and every other caller.
                selectedFiles.forEach {
                    com.prism.launcher.trusted.TrustedSharing.publishFile(it, cut = false)
                }
                status = "${selectedFiles.size} copied"
            },
            onCut = {
                FileOps.cut(selectedFiles)
                selectedFiles.forEach {
                    com.prism.launcher.trusted.TrustedSharing.publishFile(it, cut = true)
                }
                status = "${selectedFiles.size} cut"
            },
            onPaste = { pasteClipboard() },
            onRename = { selectedFiles.singleOrNull()?.let { renaming = it } },
            onDelete = { if (selectedFiles.isNotEmpty()) confirmDelete = selectedFiles },
            onNewFolder = { creatingFolder = true },
            onSelectAll = {
                selected = if (selected.size == entries.size) emptySet()
                else entries.map { it.absolutePath }.toSet()
            },
        )

        status?.let {
            Text(
                it, fontSize = 11.sp, color = colors.faint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
            )
        }

        Row(Modifier.weight(1f)) {
            RootsSidebar(
                roots = roots,
                networks = networks,
                current = current,
                remote = remote,
                onPick = { current = it; selected = emptySet(); remote = null; remoteDrive = null },
                drives = drives,
                busyDrive = driveBusy,
                onPickDrive = { drive ->
                    val open = drive.mountPoint
                    if (drive.isMounted && open != null) {
                        current = open; selected = emptySet(); remote = null
                    } else {
                        driveBusy = drive.id
                        status = "Mounting ${drive.title}…" +
                            if (drive.needsWsl) " Windows will ask for administrator rights to attach it to WSL." else ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { com.prism.desktop.DesktopDrives.mount(drive) }
                            driveBusy = null
                            result.onSuccess { folder ->
                                current = folder; selected = emptySet(); remote = null
                                status = "${drive.title} is open"
                            }.onFailure { status = "Could not mount ${drive.title}: ${it.message}" }
                            drives = withContext(Dispatchers.IO) { com.prism.desktop.DesktopDrives.list() }
                        }
                    }
                },
                onEjectDrive = { drive ->
                    driveBusy = drive.id
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { com.prism.desktop.DesktopDrives.eject(drive) }
                        driveBusy = null
                        status = result.fold({ "${drive.title} can be removed" }, { "Could not eject ${drive.title}: ${it.message}" })
                        if (drive.mountPoint != null && current.absolutePath.startsWith(drive.mountPoint.absolutePath)) {
                            current = roots.firstOrNull() ?: File(".")
                        }
                        drives = withContext(Dispatchers.IO) { com.prism.desktop.DesktopDrives.list() }
                    }
                },
                onPickRemote = { storage ->
                    remote = storage
                    remotePath = ""
                    selected = emptySet()
                    remoteDrive = null
                },
                onCompress = { compressTarget = it },
                onStatus = { status = it },
                remoteDrives = com.prism.desktop.DesktopRemoteDrives.mounts.values
                    .sortedWith(compareBy({ it.device.lowercase() }, { it.drive.name.lowercase() })),
                activeRemoteDrive = remoteDrive?.key,
                onPickRemoteDrive = { mounted ->
                    val folder = mounted.folder
                    if (folder != null && folder.isDirectory) {
                        current = folder; selected = emptySet(); remote = null; remoteDrive = null
                    } else {
                        remote = null
                        remoteDrive = mounted
                        remoteDrivePath = "/"
                        status = mounted.problem?.let { "Not in the system file explorer: $it" }
                    }
                },
            )

            if (remoteDrive != null) {
                DriveListing(
                    mounted = remoteDrive!!,
                    path = remoteDrivePath,
                    items = remoteDriveItems,
                    onEnter = { remoteDrivePath = it },
                    onUp = { remoteDrivePath = remoteDrivePath.substringBeforeLast('/').ifEmpty { "/" } },
                    onDownload = { item ->
                        val drive = remoteDrive
                        if (drive != null) scope.launch {
                            status = "Downloading ${item.name}..."
                            val target = File(current, item.name)
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val access = com.prism.desktop.DesktopRemoteDrives.access(drive) ?: error("offline")
                                    access.open(item.path).use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
                                }.isSuccess
                            }
                            status = if (ok) "Downloaded ${item.name} to ${current.name}" else "Could not download ${item.name}"
                            refresh()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else if (remote != null) {
                RemoteListing(
                    storage = remote!!,
                    path = remotePath,
                    result = remoteResult,
                    busy = remoteBusy,
                    onEnter = { remotePath = it },
                    onUp = {
                        remotePath = remotePath.trimEnd('/').substringBeforeLast('/', "")
                    },
                    onDownload = { entry ->
                        scope.launch {
                            val target = File(current, entry.name)
                            val ok = withContext(Dispatchers.IO) {
                                NetworkStorage.download(entry.url, target)
                            }
                            status = if (ok) "Downloaded ${entry.name} to ${current.name}"
                            else "Could not download ${entry.name}"
                            refresh()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries, key = { it.absolutePath }) { file ->
                        FileRow(
                            file = file,
                            selected = file.absolutePath in selected,
                            onOpen = {
                                if (file.isDirectory) {
                                    current = file
                                    selected = emptySet()
                                } else openWithShell(file)
                            },
                            onToggle = {
                                selected = if (file.absolutePath in selected) {
                                    selected - file.absolutePath
                                } else {
                                    selected + file.absolutePath
                                }
                            },
                        )
                    }
                }
            }
        }

        SectionFooter(
            "Press and hold any item to drag it onto the desktop. Click the checkbox to select " +
                "several, then cut, copy or delete them together. Right-click a drive for its menu."
        )
    }

    compressTarget?.let { target ->
        DriveCompressionDialog(target, onDismiss = { compressTarget = null }, onStarted = { status = it })
    }

    renaming?.let { file ->
        TextPromptDialog(
            title = "Rename",
            initial = file.name,
            confirmLabel = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                val result = FileOps.rename(file, newName)
                status = if (result != null) "Renamed to ${result.name}"
                else "Could not rename -- a file called \"$newName\" already exists"
                renaming = null
                refresh()
            },
        )
    }

    if (creatingFolder) {
        TextPromptDialog(
            title = "New folder",
            initial = "New folder",
            confirmLabel = "Create",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                val made = FileOps.newFolder(current, name)
                status = if (made != null) "Created ${made.name}" else "Could not create the folder"
                creatingFolder = false
                refresh()
            },
        )
    }

    if (confirmDelete.isNotEmpty()) {
        val targets = confirmDelete
        AlertDialog(
            onDismissRequest = { confirmDelete = emptyList() },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { FileOps.delete(targets) }
                        status = if (result.ok) "Deleted ${result.pasted.size}"
                        else "Could not delete: ${result.skipped.joinToString(", ")}"
                        confirmDelete = emptyList()
                        selected = emptySet()
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = emptyList() }) { Text("Cancel") }
            },
            title = { Text("Delete ${targets.size} item${if (targets.size == 1) "" else "s"}?") },
            text = {
                Column(Modifier.width(380.dp)) {
                    // Named explicitly, and folders flagged as recursive. "Delete 3 items?" with
                    // no list is how people delete the wrong thing.
                    targets.take(8).forEach {
                        Text(
                            if (it.isDirectory) "${it.name}  (folder and everything in it)" else it.name,
                            fontSize = 12.sp,
                        )
                    }
                    if (targets.size > 8) {
                        Text("…and ${targets.size - 8} more", fontSize = 12.sp, color = colors.faint)
                    }
                    Text(
                        "This cannot be undone.",
                        fontSize = 11.sp, color = Color(0xFFE57373),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun ExplorerToolbar(
    current: File,
    selectionCount: Int,
    clipboardCount: Int,
    sort: FileOps.Sort,
    descending: Boolean,
    showHidden: Boolean,
    onUp: () -> Unit,
    onSort: (FileOps.Sort) -> Unit,
    onToggleHidden: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewFolder: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(Color(0xFF16161A)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowUpward, "Up", Modifier.size(17.dp)) }
            Text(
                current.absolutePath,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = colors.muted, modifier = Modifier.weight(1f),
            )
            Text(
                if (selectionCount > 0) "$selectionCount selected" else "",
                fontSize = 11.sp, color = colors.accent,
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Filled.ContentCopy, "Copy", selectionCount > 0, onCopy)
            ToolButton(Icons.Filled.ContentCut, "Cut", selectionCount > 0, onCut)
            ToolButton(Icons.Filled.ContentPaste, "Paste ($clipboardCount)", clipboardCount > 0, onPaste)
            ToolButton(Icons.Filled.DriveFileRenameOutline, "Rename", selectionCount == 1, onRename)
            ToolButton(Icons.Filled.Delete, "Delete", selectionCount > 0, onDelete)
            ToolButton(Icons.Filled.CreateNewFolder, "New folder", true, onNewFolder)
            ToolButton(Icons.Filled.SelectAll, "Select all", true, onSelectAll)

            Spacer(Modifier.weight(1f))

            ToolButton(
                if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                "Hidden", true, onToggleHidden,
            )

            Box {
                ToolButton(Icons.AutoMirrored.Filled.Sort, "${sort.label}${if (descending) " ↓" else " ↑"}", true) {
                    sortMenu = true
                }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    FileOps.Sort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.label + when {
                                        option != sort -> ""
                                        descending -> "  ↓"
                                        else -> "  ↑"
                                    }
                                )
                            },
                            onClick = { sortMenu = false; onSort(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Disabled, not hidden -- a toolbar whose buttons appear and vanish with the selection is
    // impossible to learn.
    val tint = if (enabled) Color(0xFFC9C9D2) else Color(0xFF4A4A54)
    Row(
        Modifier
            .then(if (enabled) Modifier.clickableRow(onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

@Composable
private fun RootsSidebar(
    roots: List<File>,
    networks: List<PrismSettings.NetworkStorage>,
    current: File,
    remote: PrismSettings.NetworkStorage?,
    onPick: (File) -> Unit,
    drives: List<com.prism.desktop.DesktopDrives.Drive>,
    busyDrive: String?,
    onPickDrive: (com.prism.desktop.DesktopDrives.Drive) -> Unit,
    onEjectDrive: (com.prism.desktop.DesktopDrives.Drive) -> Unit,
    onPickRemote: (PrismSettings.NetworkStorage) -> Unit,
    onCompress: (DriveCompressionUi.Target) -> Unit,
    onStatus: (String) -> Unit,
    remoteDrives: List<com.prism.desktop.DesktopRemoteDrives.Mounted>,
    activeRemoteDrive: String?,
    onPickRemoteDrive: (com.prism.desktop.DesktopRemoteDrives.Mounted) -> Unit,
) {
    val colors = LocalPrismColors.current
    // Which drive's right-click menu is open: the phone's long-press popup, on a desktop.
    var menuFor by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF121215)).padding(vertical = 8.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Text(
            "THIS MACHINE", fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.faint,
            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
        )
        roots.forEach { root ->
            val active = remote == null && root.absolutePath == current.absolutePath
            Row(
                Modifier.fillMaxWidth().clickableRow { onPick(root) }
                    .onRightClick { menuFor = "root:" + root.absolutePath }
                    .background(if (active) Color(0xFF23232B) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (root.absolutePath == System.getProperty("user.home")) Icons.Filled.Home
                    else Icons.Filled.Storage,
                    null,
                    tint = if (active) colors.accent else colors.muted,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    root.name.ifBlank { root.absolutePath },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) Color.White else colors.muted,
                )
                DriveMenu(
                    expanded = menuFor == "root:" + root.absolutePath,
                    target = DriveCompressionUi.Target.Local(root, root.name.ifBlank { root.absolutePath }),
                    onDismiss = { menuFor = null },
                    onOpen = { onPick(root) },
                    onCompress = onCompress,
                    onStatus = onStatus,
                )
            }
        }

        if (drives.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "USB DRIVES", fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.faint,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
            drives.forEach { drive ->
                val active = remote == null && drive.mountPoint != null &&
                    current.absolutePath.startsWith(drive.mountPoint.absolutePath)
                Row(
                    Modifier.fillMaxWidth()
                        .clickableRow { if (busyDrive == null) onPickDrive(drive) }
                        .onRightClick { menuFor = "usb:" + drive.id }
                        .background(if (active) Color(0xFF23232B) else Color.Transparent)
                        .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Usb, null,
                        tint = if (drive.isMounted) colors.accent else colors.faint,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(drive.title, fontSize = 12.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(
                                drive.fileSystem.ifBlank { "unknown" },
                                com.prism.launcher.storage.formatBytes(drive.sizeBytes).takeIf { drive.sizeBytes > 0 },
                                when {
                                    busyDrive == drive.id -> "working…"
                                    drive.isMounted -> null
                                    else -> "click to mount"
                                },
                            ).filterNotNull().joinToString(" · "),
                            fontSize = 9.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (drive.isMounted) {
                        Icon(
                            Icons.Filled.Eject, "Eject",
                            tint = colors.faint,
                            modifier = Modifier.size(22.dp).clickableRow { if (busyDrive == null) onEjectDrive(drive) }.padding(3.dp),
                        )
                    }
                    DriveMenu(
                        expanded = menuFor == "usb:" + drive.id,
                        // An unmounted stick has no folder to compress yet: Open mounts it first.
                        target = drive.mountPoint?.takeIf { drive.isMounted }?.let { DriveCompressionUi.Target.Local(it, drive.title) },
                        onDismiss = { menuFor = null },
                        onOpen = { if (busyDrive == null) onPickDrive(drive) },
                        onCompress = onCompress,
                        onStatus = onStatus,
                        extra = if (drive.isMounted) listOf("Eject" to { if (busyDrive == null) onEjectDrive(drive) }) else emptyList(),
                    )
                }
            }
        }

        // Every drive of every trusted device: a phone's internal storage, SD card and USB drives,
        // another computer's disks. Grouped by device; mounted ones are also in the OS's explorer.
        if (remoteDrives.isNotEmpty()) {
            remoteDrives.groupBy { it.device }.forEach { (device, driveList) ->
                Spacer(Modifier.height(10.dp))
                Text(
                    device.uppercase(), fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
                )
                driveList.forEach { mounted ->
                    val folder = mounted.folder
                    val active = activeRemoteDrive == mounted.key ||
                        (remote == null && activeRemoteDrive == null && folder != null &&
                            current.absolutePath.startsWith(folder.absolutePath))
                    Row(
                        Modifier.fillMaxWidth().clickableRow { onPickRemoteDrive(mounted) }
                            .onRightClick { menuFor = "remote:" + mounted.key }
                            .background(if (active) Color(0xFF23232B) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (mounted.drive.kind) {
                                com.prism.launcher.storage.remote.DriveKind.USB -> Icons.Filled.Usb
                                com.prism.launcher.storage.remote.DriveKind.SD_CARD -> Icons.Filled.SdCard
                                com.prism.launcher.storage.remote.DriveKind.PHONE -> Icons.Filled.PhoneAndroid
                                else -> Icons.Filled.Storage
                            },
                            null,
                            tint = if (active) colors.accent else colors.muted,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mounted.drive.name, fontSize = 12.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    mounted.drive.kind.title,
                                    com.prism.launcher.storage.formatBytes(mounted.drive.freeBytes)
                                        .takeIf { mounted.drive.freeBytes >= 0 }?.let { "$it free" },
                                    folder?.path?.takeIf { it.length <= 3 },
                                ).joinToString(" · "),
                                fontSize = 9.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DriveMenu(
                            expanded = menuFor == "remote:" + mounted.key,
                            target = DriveCompressionUi.Target.Remote(
                                mounted.fingerprint, mounted.device, mounted.drive.id, mounted.drive.name, mounted.drive.compression,
                            ),
                            onDismiss = { menuFor = null },
                            onOpen = { onPickRemoteDrive(mounted) },
                            onCompress = onCompress,
                            onStatus = onStatus,
                        )
                    }
                }
            }
        }

        // PHASE 26. Network locations are roots alongside the local drives, and FTP shares
        // actually browse -- the JDK's URL handler does FTP, so listing and downloading need no
        // dependency at all. WebDAV and .p2p report why they cannot, rather than opening an
        // empty folder that looks like a broken share.
        if (networks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "NETWORK", fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.faint,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
            networks.forEach { storage ->
                val active = remote?.id == storage.id
                Row(
                    Modifier.fillMaxWidth()
                        .clickableRow { onPickRemote(storage) }
                        .background(if (active) Color(0xFF23232B) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Cloud, null,
                        tint = if (active) colors.accent else colors.faint,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(storage.name, fontSize = 12.sp, color = colors.muted, maxLines = 1)
                        Text(
                            "${storage.protocol}://${storage.host}:${storage.port}",
                            fontSize = 9.sp, color = colors.faint, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The remote listing pane.
 *
 * Deliberately NOT the same composable as the local one. A remote entry is a name and a URL, not
 * a [File]: it has no canonical path, cannot be renamed in place, and must never be handed to a
 * local delete. Sharing the row would invite exactly that, so the two are separate and the remote
 * one offers only what actually works -- enter a directory, or download a file.
 */
@Composable
private fun RemoteListing(
    storage: PrismSettings.NetworkStorage,
    path: String,
    result: NetworkStorage.Result?,
    busy: Boolean,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onDownload: (NetworkStorage.RemoteEntry) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalPrismColors.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1A20)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.isNotEmpty()) {
                Icon(
                    Icons.Filled.ArrowUpward, "Up",
                    tint = colors.muted,
                    modifier = Modifier.size(15.dp).clickableRow { onUp() },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "${storage.protocol}://${storage.host}/${path}",
                fontSize = 11.sp, color = colors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
        }

        when (val r = result) {
            null -> Unit
            is NetworkStorage.Result.Unsupported -> RemoteNotice(
                Icons.Filled.Info, "Not supported yet", r.reason,
            )
            is NetworkStorage.Result.Failed -> RemoteNotice(
                Icons.Filled.ErrorOutline, "Could not reach ${storage.host}", r.reason,
            )
            is NetworkStorage.Result.Listing -> {
                if (r.entries.isEmpty()) {
                    RemoteNotice(Icons.Filled.FolderOpen, "Empty", "Nothing in this directory.")
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(r.entries, key = { it.url }) { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickableRow {
                                    if (entry.isDirectory) {
                                        onEnter(if (path.isBlank()) entry.name else "$path/${entry.name}")
                                    } else onDownload(entry)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.CloudDownload,
                                null,
                                tint = if (entry.isDirectory) colors.accent else colors.faint,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(11.dp))
                            Text(
                                entry.name, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (!entry.isDirectory) {
                                Text(
                                    FileOps.humanSize(entry.size),
                                    fontSize = 11.sp, color = colors.faint,
                                )
                            }
                        }
                    }
                }
                SectionFooter("Click a file to download it into the current local folder.")
            }
        }
    }
}

/**
 * A trusted device's drive, listed directly. Used only when the OS could not mount it; a mounted
 * drive is a folder and gets the full file manager instead.
 */
@Composable
private fun DriveListing(
    mounted: com.prism.desktop.DesktopRemoteDrives.Mounted,
    path: String,
    items: Result<List<com.prism.launcher.storage.remote.DriveItem>>?,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onDownload: (com.prism.launcher.storage.remote.DriveItem) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalPrismColors.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1A20)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path != "/") {
                Icon(Icons.Filled.ArrowUpward, "Up", tint = colors.muted, modifier = Modifier.size(15.dp).clickableRow { onUp() })
                Spacer(Modifier.width(10.dp))
            }
            Text(
                mounted.device + " / " + mounted.drive.name + path.takeIf { it != "/" }.orEmpty(),
                fontSize = 11.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (items == null) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
        }
        items?.onFailure {
            RemoteNotice(Icons.Filled.ErrorOutline, "Could not reach ${mounted.device}", it.message ?: "")
        }
        items?.onSuccess { list ->
            if (list.isEmpty()) RemoteNotice(Icons.Filled.FolderOpen, "Empty", "Nothing in this folder.")
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.path }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickableRow { if (item.isDirectory) onEnter(item.path) else onDownload(item) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.CloudDownload, null,
                            tint = if (item.isDirectory) colors.accent else colors.faint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(item.name, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.isDirectory) Text(FileOps.humanSize(item.size), fontSize = 11.sp, color = colors.faint)
                    }
                }
            }
            SectionFooter("Click a file to download it into the current local folder.")
        }
    }
}

@Composable
private fun RemoteNotice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    val colors = LocalPrismColors.current
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = colors.faint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 14.sp)
        Text(
            detail, fontSize = 11.sp, color = colors.faint,
            modifier = Modifier.padding(top = 5.dp).widthIn(max = 380.dp),
        )
    }
}

@Composable
private fun FileRow(file: File, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0x225C6CFF) else Color.Transparent)
            .clickableRow(onOpen)
            // Files and folders drag onto the desktop the same way apps do. A directory becomes a
            // DirectoryRef, which shows live contents rather than a copy.
            .dragSource(file.absolutePath, payload = {
                DragPayload(
                    item = if (file.isDirectory) {
                        DesktopItem.DirectoryRef(file.absolutePath, file.name)
                    } else {
                        DesktopItem.FileRef(file.absolutePath)
                    },
                    label = file.name,
                )
            })
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Icon(
            if (file.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            null,
            tint = if (file.isDirectory) colors.accent else colors.faint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            file.name, fontSize = 13.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!file.isDirectory) {
            Text(FileOps.humanSize(file.length()), fontSize = 11.sp, color = colors.faint)
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { CommittingField(value, modifier = Modifier.width(320.dp)) { value = it } },
    )
}

private fun openWithShell(file: File) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        PrismPlatform.log.error("Prism/files", "Could not open ${file.path}", e)
    }
}
