package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.taskmanager.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The desktop Task Manager: the same two sections as the phone, over this machine's own process
 * table and file tree.
 *
 * The task list refreshes on its own while it is on screen. It stops when the page is left because
 * the effect that drives it is scoped to the composition -- there is no timer left polling a page
 * nobody is looking at.
 */
@Composable
fun TaskManagerPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(TaskTab.TASKS) }
    var collapsed by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<TaskManager.Snapshot?>(null) }
    var notice by remember { mutableStateOf("") }
    var selectedPid by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var directory by remember {
        mutableStateOf(File.listRoots().firstOrNull() ?: File("/"))
    }
    var selectedResource by remember { mutableStateOf<File?>(null) }
    var holders by remember { mutableStateOf<TaskManager.HolderResult?>(null) }
    var inspecting by remember { mutableStateOf(false) }

    suspend fun loadTasks() {
        val next = withContext(Dispatchers.IO) { runCatching { TaskManager.snapshot(force = true) }.getOrNull() }
        if (next == null) notice = "The process table could not be read." else snapshot = next
    }

    LaunchedEffect(tab, refreshKey) {
        if (tab != TaskTab.TASKS) return@LaunchedEffect
        loadTasks()
        while (isActive) {
            delay(4_000)
            loadTasks()
        }
    }

    fun kill(task: TaskManager.Task) {
        if (working) return
        working = true
        notice = "Stopping ${task.name}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { TaskManager.kill(task) }
            working = false
            notice = result.message
            selectedPid = null
            refreshKey++
        }
    }

    fun inspect(file: File) {
        selectedResource = file
        holders = null
        inspecting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { TaskManager.holders(file) }.getOrElse {
                    TaskManager.HolderResult(emptyList(), "The holders could not be read: ${it.message}")
                }
            }
            inspecting = false
            holders = result
        }
    }

    PageScaffold("Task Manager", "Running tasks, memory, storage and the files they hold") {
        Row(Modifier.weight(1f)) {
            TaskRail(
                tab = tab,
                collapsed = collapsed,
                onToggle = { collapsed = !collapsed },
                onSelect = {
                    tab = it
                    notice = ""
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (tab == TaskTab.TASKS) "Tasks" else "Resources",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            if (tab == TaskTab.TASKS) {
                                "Every process the operating system reports, with its resident memory"
                            } else {
                                "Browse from a root and ask which task is holding a path open"
                            },
                            fontSize = 12.sp,
                            color = colors.faint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (working) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    TextButton(onClick = {
                        if (tab == TaskTab.TASKS) refreshKey++ else inspect(directory)
                    }) { Text("Refresh", fontSize = 12.sp) }
                }

                if (notice.isNotBlank()) {
                    Surface(
                        color = colors.surfaceRaised,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            notice,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                if (tab == TaskTab.TASKS) {
                    TaskList(
                        snapshot = snapshot,
                        selectedPid = selectedPid,
                        working = working,
                        onSelect = { selectedPid = if (selectedPid == it) null else it },
                        onKill = ::kill,
                    )
                } else {
                    ResourceBrowser(
                        directory = directory,
                        selected = selectedResource,
                        holders = holders,
                        inspecting = inspecting,
                        onNavigate = {
                            directory = it
                            selectedResource = null
                            holders = null
                        },
                        onInspect = ::inspect,
                        onKill = ::kill,
                    )
                }
            }
        }
    }
}

private enum class TaskTab(val label: String, val glyph: String) {
    TASKS("Tasks", "▤"),
    RESOURCES("Resources", "▣"),
}

@Composable
private fun TaskRail(
    tab: TaskTab,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onSelect: (TaskTab) -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(
        Modifier
            .width(if (collapsed) 58.dp else 206.dp)
            .fillMaxHeight()
            .background(colors.surface, RoundedCornerShape(colors.cardRadius))
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!collapsed) {
                Text(
                    "Task Manager",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "‹",
                fontSize = 22.sp,
                color = colors.muted,
                modifier = Modifier.clickableRow(onToggle).padding(6.dp),
            )
        }
        TaskTab.entries.forEach { entry ->
            val active = entry == tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickableRow { onSelect(entry) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.glyph, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                if (!collapsed) {
                    Text(entry.label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!collapsed) {
            Text(
                System.getProperty("os.name").orEmpty(),
                fontSize = 10.sp,
                color = colors.faint,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun TaskList(
    snapshot: TaskManager.Snapshot?,
    selectedPid: Int?,
    working: Boolean,
    onSelect: (Int) -> Unit,
    onKill: (TaskManager.Task) -> Unit,
) {
    val colors = LocalPrismColors.current
    if (snapshot == null) {
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Reading the process table…", fontSize = 12.sp, color = colors.faint)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
    Card {
        Column(Modifier.padding(14.dp)) {
            FactLine("CPU", snapshot.cpu)
            FactLine("GPU", snapshot.gpu)
            FactLine(
                "RAM",
                if (snapshot.ramTotalBytes <= 0) "Unavailable"
                else "${humanBytes(snapshot.ramTotalBytes - snapshot.ramAvailableBytes)} used · " +
                    "${humanBytes(snapshot.ramAvailableBytes)} free of ${humanBytes(snapshot.ramTotalBytes)}",
            )
            if (snapshot.volumes.isEmpty()) {
                FactLine("Storage", "Unavailable")
            } else {
                snapshot.volumes.forEach { volume ->
                    FactLine(
                        "Storage · ${volume.label}",
                        "${humanBytes(volume.freeBytes)} free of ${humanBytes(volume.totalBytes)}",
                    )
                }
            }
        }
    }

    Text(
        "${snapshot.tasks.size} tasks · sorted by memory",
        fontSize = 11.sp,
        color = colors.faint,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 4.dp),
    )

    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(snapshot.tasks, key = { it.pid }) { task ->
            TaskRow(
                task = task,
                expanded = selectedPid == task.pid,
                enabled = !working,
                onSelect = { onSelect(task.pid) },
                onKill = { onKill(task) },
            )
        }
        item {
            Text(
                snapshot.note,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
    }
}

@Composable
private fun TaskRow(
    task: TaskManager.Task,
    expanded: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onKill: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(colors.cardRadius))
            .clickableRow(onSelect)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.name + if (task.current) "  (Prism)" else "",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "pid ${task.pid}" + if (task.packageName.isNotBlank()) " · ${task.packageName}" else "",
                    fontSize = 10.sp,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(humanBytes(task.memoryBytes), fontSize = 12.sp, color = colors.muted)
        }
        if (expanded) {
            if (task.command.isNotBlank()) {
                Text(
                    task.command,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                enabled = enabled && !task.current,
                onClick = onKill,
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Kill task", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    val colors = LocalPrismColors.current
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 10.sp, color = colors.faint, modifier = Modifier.width(112.dp))
        Text(
            value,
            fontSize = 11.sp,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResourceBrowser(
    directory: File,
    selected: File?,
    holders: TaskManager.HolderResult?,
    inspecting: Boolean,
    onNavigate: (File) -> Unit,
    onInspect: (File) -> Unit,
    onKill: (TaskManager.Task) -> Unit,
) {
    val colors = LocalPrismColors.current
    val roots = remember { File.listRoots().toList() }
    val children = remember(directory) {
        directory.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) },
        )
    }

    Column(Modifier.fillMaxSize()) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = directory.parentFile != null,
            onClick = { directory.parentFile?.let(onNavigate) },
        ) { Text("‹ Up", fontSize = 12.sp) }
        Text(
            directory.absolutePath,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (roots.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            roots.forEach { root ->
                Text(
                    root.path,
                    fontSize = 11.sp,
                    color = if (root.absolutePath == directory.absolutePath) colors.background else colors.muted,
                    modifier = Modifier
                        .background(
                            if (root.absolutePath == directory.absolutePath) colors.accent else colors.surfaceRaised,
                            RoundedCornerShape(999.dp),
                        )
                        .clickableRow { onNavigate(root) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }

    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val entries = children
            if (entries == null) {
                Card {
                    Text(
                        "This process cannot list ${directory.absolutePath}. On Windows a protected " +
                            "folder such as System Volume Information is unreadable without elevation.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else if (entries.isEmpty()) {
                Text("This folder is empty.", fontSize = 12.sp, color = colors.faint, modifier = Modifier.padding(8.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.take(800), key = { it.absolutePath }) { file ->
                        ResourceRow(
                            file = file,
                            selected = selected?.absolutePath == file.absolutePath,
                            onClick = { if (file.isDirectory) onNavigate(file) else onInspect(file) },
                            onInspect = { onInspect(file) },
                        )
                    }
                }
            }
        }
        if (selected != null) {
            Column(Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            selected.name.ifBlank { selected.absolutePath },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            selected.absolutePath + if (selected.isDirectory) "  (folder)" else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.faint,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        when {
                            inspecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Looking for the holder…", fontSize = 11.sp, color = colors.faint)
                            }
                            holders == null -> Text(
                                "Tap the path in the list to look for the task holding it.",
                                fontSize = 11.sp,
                                color = colors.muted,
                            )
                            holders.holders.isEmpty() -> Text(
                                holders.note.ifBlank { "No task currently holds this path." },
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                color = colors.muted,
                            )
                            else -> holders.holders.forEach { task ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(task.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "pid ${task.pid} · ${humanBytes(task.memoryBytes)}",
                                            fontSize = 9.sp,
                                            color = colors.faint,
                                        )
                                    }
                                    Text(
                                        "Kill",
                                        fontSize = 11.sp,
                                        color = colors.destructive,
                                        modifier = Modifier.clickableRow { onKill(task) }.padding(6.dp),
                                    )
                                }
                                if (holders.note.isNotBlank()) {
                                    Text(holders.note, fontSize = 10.sp, color = colors.faint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ResourceRow(
    file: File,
    selected: Boolean,
    onClick: () -> Unit,
    onInspect: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickableRow(onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (file.isDirectory) "▸" else "·",
            fontSize = 14.sp,
            color = colors.accent,
            modifier = Modifier.width(20.dp),
        )
        Text(
            file.name.ifBlank { file.absolutePath },
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "holders",
            fontSize = 10.sp,
            color = colors.faint,
            modifier = Modifier.clickableRow(onInspect).padding(6.dp),
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
