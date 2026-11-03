package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.launcher.PrismSettings
import com.prism.launcher.vpn.ServerDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Prism server directories this device publishes to or asks, on the desktop.
 *
 * ## What a directory is, and why this list exists separately
 *
 * A directory is a server that answers one question: which Prism servers do you know about? A server
 * role posts its own public address and port to each of these; a client, bridge or relay pulls their
 * lists into the fleet. It is additive to typing an address in and to the LAN scan -- nothing here
 * removes a server, and nothing here changes which server is active.
 *
 * ## Tap tests, right-click edits
 *
 * Tapping a row runs the real announcement test, port-forwarding probe included, because the only
 * useful thing to know about a row is whether this device can actually be found through it. Edit and
 * delete sit behind a right-click, the same gesture the servers dialog uses, so a row stays one
 * target for the action that matters.
 *
 * ## The built-in row
 *
 * It is prepended by [ServerDirectory.allDirectories] and never stored, so it cannot be edited or
 * deleted here: the shipped directory is what makes a fresh install work and must not be removable
 * by accident.
 */
@Composable
fun PrismDirectoriesDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var directories by remember { mutableStateOf(ServerDirectory.allDirectories()) }
    var editing by remember { mutableStateOf<PrismSettings.PrismDirectory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf("") }
    var outcomeOk by remember { mutableStateOf(false) }
    val serverMode = !PrismSettings.isPrismClientRole()

    fun reload() {
        directories = ServerDirectory.allDirectories()
    }

    fun test(directory: PrismSettings.PrismDirectory) {
        if (testingId != null) return
        testingId = directory.id
        outcome = ""
        scope.launch {
            val port = PrismSettings.getPrismVpnPort().toIntOrNull() ?: 0
            val announcement = withContext(Dispatchers.IO) {
                runCatching { ServerDirectory.testAnnouncement(directory, port) }.getOrNull()
            }
            testingId = null
            outcome = announcement?.message ?: "The test could not run."
            outcomeOk = announcement?.reachable == true && announcement.reports.all { it.ok }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).widthIn(max = 600.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prism server directories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { adding = true }) { Text("+ Add directory", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (serverMode) {
                        "This machine is a server, so it publishes its public address and port to " +
                            "each directory here once the port answers from the internet. Tap a row " +
                            "to test that, forwarding included."
                    } else {
                        "This machine joins through a server, so it asks each directory which Prism " +
                            "servers they know about and adds the new ones to the fleet. Tap a row " +
                            "to test announcing through it."
                    },
                    fontSize = 12.sp,
                    color = colors.faint,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))

                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    directories.forEach { directory ->
                        val builtIn = ServerDirectory.isBuiltIn(directory.id)
                        Box {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .rightClickable { if (!builtIn) menuFor = directory.id }
                                    .clickableRow { test(directory) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = if (directory.isEnabled) colors.accent else Color(0xFF34343E),
                                    shape = CircleShape,
                                    modifier = Modifier.size(9.dp),
                                ) {}
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        directory.name.ifBlank {
                                            directory.url.ifBlank { directory.address }
                                        },
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        (if (directory.url.isNotBlank()) directory.url
                                        else directory.address + ":" + directory.port) +
                                            if (directory.username.isNotBlank()) {
                                                "  ·  " + directory.username
                                            } else {
                                                ""
                                            },
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.faint,
                                    )
                                }
                                when {
                                    testingId == directory.id ->
                                        CircularProgressIndicator(
                                            color = colors.accent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp),
                                        )

                                    builtIn -> Text("built in", fontSize = 11.sp, color = colors.accent)
                                    !directory.isEnabled -> Text("off", fontSize = 11.sp, color = colors.faint)
                                    else -> Text("test", fontSize = 11.sp, color = colors.faint)
                                }
                            }

                            DropdownMenu(
                                expanded = menuFor == directory.id,
                                onDismissRequest = { menuFor = null },
                                offset = DpOffset(24.dp, 0.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit", fontSize = 13.sp) },
                                    onClick = { menuFor = null; editing = directory },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Delete", fontSize = 13.sp, color = Color(0xFFFF6B6B))
                                    },
                                    onClick = {
                                        menuFor = null
                                        // NO CONFIRMATION: the menu item said what it does, and the
                                        // servers this directory added stay in the fleet.
                                        PrismSettings.setDirectoryServers(
                                            PrismSettings.getDirectoryServers()
                                                .filterNot { it.id == directory.id },
                                        )
                                        reload()
                                        outcome = "Removed."
                                        outcomeOk = true
                                    },
                                )
                            }
                        }
                        Hairline()
                    }
                }

                if (outcome.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = if (outcomeOk) Color(0xFF14301F) else Color(0xFF331A1A),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            outcome,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = if (outcomeOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "The built-in directory cannot be removed. Nothing here replaces the " +
                            "addresses you type in or the LAN scan.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
                }
            }
        }
    }

    if (adding) {
        DirectoryEditor(
            directory = null,
            onDismiss = { adding = false },
            onSave = { saved ->
                PrismSettings.setDirectoryServers(PrismSettings.getDirectoryServers() + saved)
                adding = false
                reload()
                outcome = "Added. Tap it to test announcing through it."
                outcomeOk = true
            },
        )
    }

    editing?.let { target ->
        DirectoryEditor(
            directory = target,
            onDismiss = { editing = null },
            onSave = { saved ->
                PrismSettings.setDirectoryServers(
                    PrismSettings.getDirectoryServers().map { row ->
                        if (row.id == target.id) {
                            // The id is preserved so an edit replaces the row rather than adding a
                            // second one, and the row is enabled: no gesture here disables a
                            // directory, so a saved-off row could never be turned back on.
                            saved.copy(id = target.id, isEnabled = true)
                        } else {
                            row
                        }
                    },
                )
                editing = null
                reload()
                outcome = "Saved."
                outcomeOk = true
            },
        )
    }
}

/**
 * Add or edit a directory, as a URL or as a Prism server.
 *
 * TABBED FOR THE SAME REASON THE PHONE'S FORM IS: a URL and an address/port are two answers to one
 * question, and showing both at once makes every entry look like it needs both. A null [directory]
 * means adding, which is the only difference between the two modes.
 */
@Composable
private fun DirectoryEditor(
    directory: PrismSettings.PrismDirectory?,
    onDismiss: () -> Unit,
    onSave: (PrismSettings.PrismDirectory) -> Unit,
) {
    val colors = LocalPrismColors.current

    var useUrl by remember { mutableStateOf(directory?.url?.isNotBlank() ?: true) }
    var url by remember { mutableStateOf(directory?.url.orEmpty()) }
    var name by remember { mutableStateOf(directory?.name.orEmpty()) }
    var address by remember { mutableStateOf(directory?.address.orEmpty()) }
    var port by remember {
        mutableStateOf(
            directory?.port?.toString()
                ?: (PrismSettings.getPrismVpnPort().toIntOrNull() ?: 8080).toString(),
        )
    }
    var username by remember { mutableStateOf(directory?.username.orEmpty()) }
    var password by remember { mutableStateOf(directory?.password.orEmpty()) }

    val portNumber = port.trim().toIntOrNull()
    val urlValid = runCatching {
        val parsed = java.net.URI(url.trim())
        parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)
    val valid = if (useUrl) urlValid else address.isNotBlank() && portNumber in 1..65535

    fun build(): PrismSettings.PrismDirectory = PrismSettings.PrismDirectory(
        id = directory?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim().ifBlank { if (useUrl) url.trim() else address.trim() },
        address = if (useUrl) "" else address.trim(),
        port = if (useUrl) 0 else (portNumber ?: 0),
        username = username.trim(),
        password = password,
        url = if (useUrl) url.trim() else "",
        isEnabled = directory?.isEnabled ?: true,
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).widthIn(max = 460.dp)) {
                Text(
                    if (directory == null) "Add a directory" else "Edit directory",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth()) {
                    Tab("URL", selected = useUrl) { useUrl = true }
                    Spacer(Modifier.width(16.dp))
                    Tab("Prism server", selected = !useUrl) { useUrl = false }
                }
                Spacer(Modifier.height(12.dp))

                if (useUrl) {
                    DirField("Directory URL", "https://example.com/prism/servers") {
                        CommittingField(value = url, modifier = Modifier.fillMaxWidth()) { url = it }
                    }
                } else {
                    DirField("Name", "What to call it in this list") {
                        CommittingField(value = name, modifier = Modifier.fillMaxWidth()) { name = it }
                    }
                    DirField("Address", "An IP address or a hostname") {
                        CommittingField(value = address, modifier = Modifier.fillMaxWidth()) { address = it }
                    }
                    DirField("Port", "The port the directory listens on") {
                        CommittingField(value = port, modifier = Modifier.fillMaxWidth()) { port = it }
                    }
                }
                DirField("Username", "Leave both blank for a directory with no authentication") {
                    CommittingField(value = username, modifier = Modifier.fillMaxWidth()) { username = it }
                }
                DirField("Password", "The directory token goes here when the endpoint asks for one") {
                    CommittingField(value = password, modifier = Modifier.fillMaxWidth()) { password = it }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = valid, onClick = { onSave(build()) }) {
                        Text(if (directory == null) "Add" else "Save")
                    }
                }
                if (!valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (useUrl) {
                            "A URL has to start with http:// or https:// and name a host."
                        } else {
                            "An address and a port between 1 and 65535 are required."
                        },
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.accent else colors.faint,
        modifier = Modifier
            .background(if (selected) colors.surfaceRaised else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun DirField(label: String, hint: String, content: @Composable () -> Unit) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 13.sp)
        Text(hint, fontSize = 11.sp, color = colors.faint)
        Spacer(Modifier.height(5.dp))
        content()
    }
}
