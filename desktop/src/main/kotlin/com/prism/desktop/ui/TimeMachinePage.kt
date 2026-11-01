package com.prism.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.timemachine.TimeMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time Machine on the desktop: this machine ADBing into itself, plus Prism's own data and APKs.
 *
 * A desktop is where `adb backup` can genuinely work, because platform-tools may be installed and
 * a local adb server can see the same device. The page therefore shows the ADB line of every
 * snapshot instead of a generic "backup complete": on a phone that line usually explains why other
 * apps' data is absent, and on a desktop it is the proof that the bridge ran.
 */
@Composable
fun TimeMachinePage() {
    val scope = rememberCoroutineScope()
    var snapshots by remember { mutableStateOf(runCatching { TimeMachine.snapshots() }.getOrDefault(emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var includeApks by remember { mutableStateOf(true) }
    var includePrism by remember { mutableStateOf(true) }
    var includeAdb by remember { mutableStateOf(true) }
    var restoreTarget by remember { mutableStateOf<TimeMachine.Snapshot?>(null) }

    fun refresh() {
        snapshots = runCatching { TimeMachine.snapshots() }.getOrDefault(emptyList())
    }

    PageScaffold("Time Machine", "This device, backed up to itself") {
        Row(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.weight(1f)) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("New snapshot", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "One ZIP containing installed APKs, Prism's settings and data, and the " +
                                "ADB attempt. The manifest records what could not be read.",
                            fontSize = 12.sp,
                            color = LocalPrismColors.current.muted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeApks, onCheckedChange = { includeApks = it })
                            Text("Applications", fontSize = 13.sp)
                            Spacer(Modifier.width(14.dp))
                            Checkbox(checked = includePrism, onCheckedChange = { includePrism = it })
                            Text("Prism data", fontSize = 13.sp)
                            Spacer(Modifier.width(14.dp))
                            Checkbox(checked = includeAdb, onCheckedChange = { includeAdb = it })
                            Text("Self-ADB", fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    status = "Scanning…"
                                    scope.launch {
                                        val outcome = withContext(Dispatchers.IO) {
                                            runCatching {
                                                TimeMachine.create(
                                                    name = name.ifBlank { "snapshot" },
                                                    includeApks = includeApks,
                                                    includePrismData = includePrism,
                                                    includeAdb = includeAdb,
                                                ) { stage, done, total ->
                                                    status = if (total > 1) "$stage $done/$total" else stage
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            busy = false
                                            outcome.onSuccess {
                                                status = "Saved ${it.file.name} (${formatBytes(it.bytes)})"
                                                refresh()
                                            }.onFailure {
                                                status = "Snapshot failed: ${it.message ?: it.javaClass.simpleName}"
                                            }
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text(if (busy) "Working…" else "Take snapshot") }
                            if (status.isNotBlank()) {
                                Text(status, fontSize = 12.sp, color = LocalPrismColors.current.muted, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }

                if (snapshots.isEmpty()) {
                    Text(
                        "No snapshots yet.",
                        fontSize = 13.sp,
                        color = LocalPrismColors.current.muted,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
                for (snapshot in snapshots) {
                    SnapshotCard(
                        snapshot = snapshot,
                        onRestore = { restoreTarget = snapshot },
                        onDelete = {
                            TimeMachine.delete(snapshot)
                            refresh()
                        },
                    )
                }
            }
        }
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore Prism data?") },
            text = {
                Text(
                    "This overwrites Prism's current settings, page layout and databases with the " +
                        "snapshot from ${dateOf(target.createdAt)}. Restart Prism afterwards. " +
                        "Installed apps are not reinstalled by this step.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        val message = withContext(Dispatchers.IO) {
                            runCatching { TimeMachine.restorePrism(target) }
                                .getOrElse { "Restore failed: ${it.message}" }
                        }
                        status = message
                        busy = false
                    }
                    restoreTarget = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SnapshotCard(
    snapshot: TimeMachine.Snapshot,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Card(Modifier.padding(top = 10.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(dateOf(snapshot.createdAt), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatBytes(snapshot.bytes)} · ${snapshot.appCount} apps · ${snapshot.prismFiles} Prism files",
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (snapshot.adbStatus.isNotBlank()) {
                Text(
                    "ADB: ${snapshot.adbStatus}",
                    fontSize = 11.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onRestore) { Text("Restore Prism data") }
            }
        }
    }
}

private fun dateOf(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
