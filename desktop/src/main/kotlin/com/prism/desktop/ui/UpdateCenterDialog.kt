package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.PrismPlatform
import com.prism.launcher.update.UpdateCenter
import com.prism.launcher.update.UpdateSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * The desktop face of System Update.
 *
 * There is no silent install here and no attempt to run an untrusted EXE. The check downloads the
 * release, runs the same quarantine, and leaves a passing artefact in Prism's cache for the user to
 * launch themselves. That is deliberate: an application that executes a downloaded binary to find
 * out whether it is malicious has already lost the argument.
 */
@Composable
fun UpdateCenterDialog(startOnRepositories: Boolean, onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var repositories by remember { mutableStateOf(UpdateCenter.Repositories.all()) }
    var tab by remember { mutableStateOf(if (startOnRepositories) 0 else 1) }
    var newRepository by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCenter.CheckResult?>(null) }
    var progress by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var report by remember { mutableStateOf<Pair<String, File>?>(null) }

    fun addRepository() {
        val repository = UpdateCenter.Repository.parse(newRepository)
        if (repository == null) {
            status = "Use owner/repository or a GitHub URL."
            return
        }
        if (UpdateCenter.Repositories.add(repository)) {
            repositories = UpdateCenter.Repositories.all()
            newRepository = ""
            status = "Added ${repository.fullName}."
        } else {
            status = "${repository.fullName} is already on the list."
        }
    }

    fun discover() {
        if (busy) return
        busy = true
        status = "Looking for forks…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var added = 0
                val errors = mutableListOf<String>()
                // Only upstream, so one tap cannot fan out into a hundred rate-limited calls.
                repositories.filter { it.upstream }.forEach { repository ->
                    val found = UpdateCenter.Repositories.discoverForks(repository)
                    added += found.added
                    if (!found.ok) errors += found.message
                }
                repositories = UpdateCenter.Repositories.all()
                when {
                    added > 0 -> "Added $added fork${if (added == 1) "" else "s"}."
                    errors.isNotEmpty() -> errors.first()
                    else -> "No new forks were found."
                }
            }
            status = outcome
            busy = false
        }
    }

    fun check() {
        if (busy) return
        busy = true
        status = "Reading releases from ${repositories.size} repositories…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                UpdateCenter.checkForUpdates(
                    repositories = repositories,
                    platform = UpdateCenter.platformHere(),
                )
            }
            result = outcome
            status = if (outcome.candidates.isEmpty()) {
                "Up to date at ${outcome.currentVersion}."
            } else {
                "${outcome.candidates.size} newer build(s) found. Installed: ${outcome.currentVersion}."
            }
            busy = false
        }
    }

    fun download(candidate: UpdateCenter.Candidate) {
        if (busy) return
        busy = true
        progress = progress + (candidate.release.repository.fullName to "Downloading…")
        scope.launch {
            val destination = File(PrismPlatform.host.cacheDir(), "updates/${candidate.asset.name}")
            val outcome = withContext(Dispatchers.IO) {
                val download = UpdateCenter.download(candidate.asset, destination) { bytes, total ->
                    progress = progress + (
                        candidate.release.repository.fullName to
                            if (total > 0) "Downloading ${bytes / 1024} / ${total / 1024} KB"
                            else "Downloading ${bytes / 1024} KB"
                        )
                }
                if (!download.ok) return@withContext download.message to null
                val scan = UpdateSandbox.inspect(destination)
                val text = buildString {
                    appendLine(scan.summary)
                    appendLine("File: ${destination.absolutePath}")
                    appendLine("SHA-256: ${scan.fileSha256}")
                    if (scan.risks.isNotEmpty()) {
                        appendLine()
                        scan.risks.forEach { risk ->
                            appendLine((if (risk.level == UpdateSandbox.Risk.Level.HIGH) "BLOCK " else "NOTE  ") +
                                risk.title + ": " + risk.detail)
                        }
                    }
                    appendLine()
                    append("This desktop build does not execute the downloaded installer. A passing " +
                        "quarantine is a static and structural check, not proof that the binary is safe.")
                }
                text to destination
            }
            if (outcome.second != null) {
                progress = progress + (candidate.release.repository.fullName to "Quarantine complete")
                report = outcome.first to outcome.second!!
            } else {
                progress = progress + (candidate.release.repository.fullName to outcome.first)
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            modifier = Modifier.width(760.dp).height(620.dp),
        ) {
            Column(Modifier.fillMaxHeight().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("System Update", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                }
                Row(Modifier.padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UpdateTab("Repositories", tab == 0) { tab = 0 }
                    UpdateTab("Check for updates", tab == 1) { tab = 1 }
                }
                if (status.isNotBlank()) {
                    Text(status, fontSize = 12.sp, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newRepository,
                            onValueChange = { newRepository = it },
                            singleLine = true,
                            label = { Text("owner/repository or GitHub URL") },
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { addRepository() }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Add")
                        }
                        Button(onClick = { discover() }, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Find forks")
                        }
                    }
                    LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(repositories, key = { it.fullName }) { repository ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(colors.card, RoundedCornerShape(colors.cardRadius))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        repository.displayName + if (repository.upstream) "  ·  UPSTREAM" else "",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(repository.url, fontSize = 11.sp, color = colors.faint)
                                }
                                TextButton(onClick = {
                                    runCatching { Desktop.getDesktop().browse(java.net.URI(repository.url)) }
                                }) { Text("Open") }
                                if (!repository.upstream) {
                                    TextButton(onClick = {
                                        UpdateCenter.Repositories.remove(repository.fullName)
                                        repositories = UpdateCenter.Repositories.all()
                                    }) { Text("Remove", color = colors.destructive) }
                                }
                            }
                        }
                    }
                    Text(
                        "A fork is a source, not a trust decision. An Android update is installable " +
                            "only if it is signed by the Prism already on the device.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { check() }, enabled = !busy) { Text("Check now") }
                        if (result != null) {
                            Text(
                                "  ${result!!.failures.size} repository note(s)",
                                fontSize = 12.sp,
                                color = colors.faint,
                            )
                        }
                    }
                    val candidates = result?.candidates.orEmpty()
                    if (candidates.isEmpty()) {
                        Text(
                            result?.let { "No newer release with an asset for this platform." }
                                ?: "Press Check now to read every repository.",
                            fontSize = 13.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    } else {
                        LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(candidates, key = { it.release.repository.fullName + it.release.tag }) { candidate ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(colors.card, RoundedCornerShape(colors.cardRadius))
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        "${candidate.release.title} · ${candidate.release.version}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${candidate.release.repository.fullName} · ${candidate.asset.name}",
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                    if (candidate.release.notes.isNotBlank()) {
                                        Text(
                                            candidate.release.notes.trim().take(400),
                                            fontSize = 12.sp,
                                            color = colors.muted,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { download(candidate) }, enabled = !busy) {
                                            Text("Download and quarantine")
                                        }
                                        Text(
                                            "  " + progress[candidate.release.repository.fullName].orEmpty(),
                                            fontSize = 11.sp,
                                            color = colors.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    report?.let { (text, file) ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Update quarantine") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 12.sp, lineHeight = 17.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { Desktop.getDesktop().open(file.parentFile) }
                    report = null
                }) { Text("Open folder") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun UpdateTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) colors.accent else colors.muted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
