package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.prism.launcher.storage.compress.CompressionJobs
import com.prism.launcher.storage.remote.CompressionInfo
import com.prism.launcher.storage.remote.TrustedDrives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.SwingUtilities

/**
 * The desktop's drive compression: the dialog a drive's right-click opens, and the always-on-top
 * progress window that stands in for Android's persistent notification while a drive is being
 * compressed. A tray balloon cannot be updated in place (see DesktopNotifier), so the live numbers
 * get a small window of their own in the corner of the screen, which goes away when the work does.
 */
object DriveCompressionUi {

    /** What the dialog was opened for. */
    sealed class Target(val title: String) {
        /** A drive of this computer, by the folder it is mounted at. */
        class Local(val root: File, title: String) : Target(title)

        /** A trusted device's drive, compressed by that device. */
        class Remote(val owner: String, val device: String, val driveId: String, title: String, val info: CompressionInfo?) : Target(title)
    }

    /** The drive this computer offers whose folder is [root], or null when [root] is just a folder. */
    fun resolve(root: File): com.prism.launcher.storage.remote.LocalDrives.Offered? {
        fun key(file: File) = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).trimEnd('\\', '/')
        val wanted = key(root)
        fun find(list: List<com.prism.launcher.storage.remote.LocalDrives.Offered>) = list.firstOrNull { offered -> offered.root?.let(::key) == wanted }
        return find(TrustedDrives.offered()) ?: find(TrustedDrives.offered(fresh = true))
    }

    /**
     * Whether [target] is compressed, so its menu offers to decompress it: compressed from here (or
     * from a trusted device that asked), or carrying Prism's marker; for a trusted device's drive,
     * what its owner last said. Blocking -- call off the UI thread.
     */
    fun isCompressed(target: Target): Boolean = runCatching {
        when (target) {
            is Target.Local -> {
                val offered = resolve(target.root) ?: return false
                com.prism.launcher.storage.compress.DriveCompression.setting(offered.drive.id).compressed ||
                    com.prism.launcher.storage.compress.DriveCompression.markerCodec(target.root) != null
            }
            is Target.Remote -> (
                TrustedDrives.devices().firstOrNull { it.fingerprint == target.owner }
                    ?.drives?.firstOrNull { it.id == target.driveId }?.compression ?: target.info
                )?.methodId != null
        }
    }.getOrDefault(false)

    /** Starts decompressing [target] in the background; [onResult] gets what to tell the user. */
    fun decompress(target: Target, onResult: (String) -> Unit) {
        Thread({
            val refused = runCatching {
                when (target) {
                    is Target.Local -> resolve(target.root)?.let { CompressionJobs.start(it.drive.id, null, compressing = false, auto = false) }
                        ?: "${target.title} is a folder, not a drive."
                    is Target.Remote -> CompressionJobs.startRemote(target.owner, target.device, target.driveId, target.title, null, compressing = false, auto = false)
                }
            }.getOrElse { it.message ?: "Could not start." }
            SwingUtilities.invokeLater { onResult(refused ?: "Decompressing ${target.title} in the background") }
        }, "prism-decompress-start").apply { isDaemon = true; start() }
    }

    /** Every job, for Compose. Updated on the UI thread from [CompressionJobs]. */
    val jobs = mutableStateListOf<CompressionJobs.Job>()

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        CompressionJobs.addListener { job ->
            SwingUtilities.invokeLater {
                val index = jobs.indexOfFirst { it.key == job.key }
                if (index >= 0) jobs[index] = job else jobs += job
            }
        }
    }
}

/** Opens [onRightClick] on a secondary click: a drive row's way to its compression dialog. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(onRightClick: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isSecondaryPressed) onRightClick() }

/**
 * A drive's right-click menu: the desktop's counterpart of the phone's long-press popup. Open,
 * "Compress drive…", "Decompress drive" when the drive is compressed, and whatever else the row
 * adds ([extra], such as Eject). Anchored to the row it is placed in.
 */
@Composable
fun DriveMenu(
    expanded: Boolean,
    target: DriveCompressionUi.Target?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCompress: (DriveCompressionUi.Target) -> Unit,
    onStatus: (String) -> Unit,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = LocalPrismColors.current
    var compressed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(expanded, target) {
        if (expanded && target != null) compressed = withContext(Dispatchers.IO) { DriveCompressionUi.isCompressed(target) }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(colors.surfaceRaised)) {
        @Composable
        fun item(label: String, hint: String? = null, action: () -> Unit) = DropdownMenuItem(
            text = {
                Column {
                    Text(label, color = colors.onSurface, fontSize = 13.sp)
                    hint?.let { Text(it, color = colors.faint, fontSize = 10.sp) }
                }
            },
            onClick = { onDismiss(); action() },
        )
        item("Open", null, onOpen)
        if (target != null) {
            item("Compress drive…", "Save space: choose an algorithm, or keep it compressed automatically") { onCompress(target) }
            if (compressed) {
                item("Decompress drive", "Restore every file to its full size, in the background") {
                    DriveCompressionUi.decompress(target, onStatus)
                }
            }
        }
        extra.forEach { (label, action) -> item(label, null, action) }
    }
}

private class Loaded(val driveId: String?, val info: CompressionInfo?, val problem: String?)

@Composable
fun DriveCompressionDialog(target: DriveCompressionUi.Target, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var loaded by remember(target) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(target) {
        loaded = when (target) {
            is DriveCompressionUi.Target.Remote -> Loaded(target.driveId, target.info, if (target.info == null) {
                "${target.device} has not said how its drives can be compressed. Update Prism there."
            } else null)
            is DriveCompressionUi.Target.Local -> withContext(Dispatchers.IO) {
                val offered = DriveCompressionUi.resolve(target.root)
                if (offered == null) Loaded(null, null, "${target.title} is a folder, not a drive. Right-click a drive to compress it.")
                else Loaded(offered.drive.id, TrustedDrives.compressionInfo(offered), null)
            }
        }
    }

    val info = loaded?.info
    val choices = info?.methods.orEmpty()
    var choice by remember(info) { mutableStateOf(choices.indexOfFirst { it.id == info?.methodId }.coerceAtLeast(0)) }
    var auto by remember(info) { mutableStateOf(info?.auto ?: true) }
    var menu by remember { mutableStateOf(false) }
    var problem by remember(target) { mutableStateOf<String?>(null) }

    fun start(compressing: Boolean) {
        val current = loaded ?: return
        val driveId = current.driveId ?: return
        val method = choices.getOrNull(choice)?.id
        Thread({
            val result = when (target) {
                is DriveCompressionUi.Target.Local -> CompressionJobs.start(driveId, method, compressing, auto)
                is DriveCompressionUi.Target.Remote ->
                    CompressionJobs.startRemote(target.owner, target.device, driveId, target.title, method, compressing, auto)
            }
            SwingUtilities.invokeLater {
                if (result == null) {
                    onStarted("${if (compressing) "Compressing" else "Decompressing"} ${target.title} in the background")
                    onDismiss()
                } else problem = result
            }
        }, "prism-compress-start").apply { isDaemon = true; start() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        shape = RoundedCornerShape(colors.cardRadius),
        title = { Text("Compress ${target.title}", color = colors.onSurface, fontSize = 17.sp) },
        text = {
            Column(Modifier.width(440.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val state = loaded
                when {
                    state == null -> Text("Reading the drive…", color = colors.muted, fontSize = 13.sp)
                    state.problem != null -> Text(state.problem, color = colors.muted, fontSize = 13.sp)
                    info?.unavailable != null -> Text(info.unavailable.orEmpty(), color = colors.muted, fontSize = 13.sp)
                    else -> {
                        val current = info?.methodId?.let { id -> choices.firstOrNull { it.id == id }?.label ?: id }
                        Text(
                            if (current != null) "Compressed with $current." else "Not compressed.",
                            color = colors.onSurface, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (info?.native == true) {
                                "Files stay where they are under their own names and open as before -- the file system " +
                                    "decompresses them as they are read, for every program, as Windows' own drive compression does."
                            } else {
                                "Files stay where they are under their own names. Prism decompresses them as they are read, " +
                                    "in 64 KB units like NTFS; only what Prism and trusted devices read sees them that way."
                            },
                            color = colors.faint, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("ALGORITHM", color = colors.faint, fontSize = 9.sp, letterSpacing = 1.1.sp)
                        Spacer(Modifier.height(4.dp))
                        Box {
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(colors.surface, RoundedCornerShape(colors.fieldRadius))
                                    .border(1.dp, colors.divider, RoundedCornerShape(colors.fieldRadius))
                                    .clickableRow { menu = true }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    choices.getOrNull(choice)?.label ?: "—",
                                    color = colors.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Filled.ArrowDropDown, null, tint = colors.muted)
                            }
                            DropdownMenu(
                                expanded = menu,
                                onDismissRequest = { menu = false },
                                modifier = Modifier.background(colors.surfaceRaised).heightIn(max = 340.dp),
                            ) {
                                choices.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(option.label, color = if (option.problem == null) colors.onSurface else colors.faint, fontSize = 13.sp)
                                                option.problem?.let { Text(it, color = colors.faint, fontSize = 10.sp, maxLines = 2) }
                                            }
                                        },
                                        onClick = { choice = index; menu = false },
                                    )
                                }
                            }
                        }
                        choices.getOrNull(choice)?.problem?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = colors.destructive, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableRow { auto = !auto }) {
                            Checkbox(
                                checked = auto, onCheckedChange = { auto = it },
                                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted),
                            )
                            Text("Compress new files on this drive automatically", color = colors.onSurface, fontSize = 13.sp)
                        }
                        Text(
                            "Runs in the background; a progress window shows how much is compressed and how much is left.",
                            color = colors.faint, fontSize = 11.sp,
                        )
                    }
                }
                problem?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = colors.destructive, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            val ready = loaded?.problem == null && loaded?.driveId != null && info?.unavailable == null && choices.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ready && info?.methodId != null) {
                    TextButton(onClick = { start(compressing = false) }) { Text("Decompress", color = colors.muted) }
                }
                Button(
                    onClick = { start(compressing = true) },
                    enabled = ready && choices.getOrNull(choice)?.problem == null,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(colors.buttonRadius),
                ) { Text("Compress") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.muted) } },
    )
}

/**
 * The persistent progress window: bottom-right, above everything, for as long as a compression
 * runs and a few seconds after, with a Stop button for each. Call from the application's scope.
 */
@Composable
fun CompressionProgressWindow() {
    val jobs = DriveCompressionUi.jobs
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // Finished ones stay up for a little while, so the result can be read.
    val shown = jobs.filter { it.visible && (it.active || now - it.updatedAt < 8_000) }
    if (shown.isEmpty()) return
    val bounds = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val height = (32 + shown.size * 96).coerceAtMost(420)
    val state = rememberWindowState(
        size = DpSize(380.dp, height.dp),
        position = WindowPosition.Absolute((bounds.x + bounds.width - 396).dp, (bounds.y + bounds.height - height - 16).dp),
    )
    state.size = DpSize(380.dp, height.dp)
    state.position = WindowPosition.Absolute((bounds.x + bounds.width - 396).dp, (bounds.y + bounds.height - height - 16).dp)
    Window(
        onCloseRequest = { shown.filter { !it.active }.forEach { CompressionJobs.dismiss(it.key); DriveCompressionUi.jobs.remove(it) } },
        state = state,
        title = "Prism · drive compression",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        PrismTheme {
            val colors = LocalPrismColors.current
            Column(
                Modifier.fillMaxSize().background(colors.surfaceRaised).border(1.dp, colors.divider)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Compress, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DRIVE COMPRESSION", color = colors.faint, fontSize = 9.sp, letterSpacing = 1.1.sp)
                }
                shown.forEach { job ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            job.headline(), color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (job.active) {
                            Text(
                                "Stop", color = colors.accent, fontSize = 12.sp,
                                modifier = Modifier.clickableRow { CompressionJobs.stop(job.key) }.padding(start = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    if (job.active && job.state != CompressionJobs.State.RUNNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.divider)
                    } else {
                        LinearProgressIndicator(
                            progress = { if (job.active) job.fraction else 1f },
                            modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.divider,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(job.detail(), color = colors.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (job.active && job.current.isNotBlank()) {
                        Text(job.current, color = colors.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
