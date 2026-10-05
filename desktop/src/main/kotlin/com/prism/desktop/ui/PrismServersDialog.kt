package com.prism.desktop.ui

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
import com.prism.launcher.vpn.PrismServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Prism meshnet servers this device knows about. PHASE 24.
 *
 * ## Why clicking a server connects rather than selecting it
 *
 * Because "active" is a claim about reachability, and a list where clicking only moved a dot would let
 * somebody mark a dead server active and then wonder why the mesh was empty. [PrismServerClient.connect]
 * does a real exchange -- TCP, a CONNECT, a status line -- and sets the flag only on a 200. A failure
 * leaves the previously active server exactly as it was, because somebody trying a server that turns out
 * to be down should not end up disconnected from the one that worked.
 *
 * ## Why 407 is reported differently from everything else
 *
 * A wrong password and an unreachable host are the same to a boolean and completely different to a
 * person: 407 means the address and port are RIGHT. Saying so turns "it does not work" into "fix the
 * password", which is the difference between a useful error and a shrug.
 *
 * ## Why the right-click menu and not a row of buttons
 *
 * Edit and delete on every row is four controls per server competing with the one thing a row is for.
 * A context menu is where a desktop user looks for per-item actions, and it keeps the row itself a single
 * target for the action that matters.
 */
@Composable
fun PrismServersDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(PrismSettings.getPrismServers()) }
    var editing by remember { mutableStateOf<PrismSettings.PrismServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf("") }
    var outcomeOk by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PrismSettings.PrismServer?>(null) }

    fun reload() {
        servers = PrismSettings.getPrismServers()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Prism meshnet servers", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Click one to connect and make it active. Right-click for edit and delete.",
                    fontSize = 12.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(14.dp))

                if (servers.isEmpty()) {
                    Text(
                        "No servers yet. A meshnet server is what turns devices on the same network " +
                            "into a mesh -- and since trusted-device pairing only happens over the " +
                            "mesh, this is what makes pairing possible.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                    )
                } else {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        servers.forEach { server ->
                            Box {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .rightClickable { menuFor = server.id }
                                        .clickableRow {
                                            if (busyId != null) return@clickableRow
                                            busyId = server.id
                                            outcome = ""
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    PrismServerClient.connect(server)
                                                }
                                                outcome = result.message
                                                outcomeOk =
                                                    result is PrismServerClient.Result.Connected
                                                busyId = null
                                                reload()
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // A filled dot for the active server. One dot, because exactly one
                                    // server can be active and the write that sets it clears the others.
                                    Surface(
                                        color = if (server.isActive) colors.accent else Color(0xFF34343E),
                                        shape = CircleShape,
                                        modifier = Modifier.size(9.dp),
                                    ) {}
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            server.name.ifBlank { server.address },
                                            fontSize = 14.sp,
                                        )
                                        Text(
                                            server.address + ":" + server.port +
                                                (if (server.username.isNotBlank()) {
                                                    "  ·  " + server.username
                                                } else {
                                                    ""
                                                }),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.faint,
                                        )
                                    }
                                    if (busyId == server.id) {
                                        CircularProgressIndicator(
                                            color = colors.accent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    } else if (server.isActive) {
                                        Text("active", fontSize = 11.sp, color = colors.accent)
                                    }
                                }

                                DropdownMenu(
                                    expanded = menuFor == server.id,
                                    onDismissRequest = { menuFor = null },
                                    offset = DpOffset(24.dp, 0.dp),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit", fontSize = 13.sp) },
                                        onClick = { menuFor = null; editing = server },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Test connection", fontSize = 13.sp) },
                                        onClick = {
                                            menuFor = null
                                            busyId = server.id
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    PrismServerClient.probe(server)
                                                }
                                                outcome = result.message
                                                outcomeOk =
                                                    result is PrismServerClient.Result.Connected
                                                busyId = null
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Delete", fontSize = 13.sp, color = Color(0xFFFF6B6B))
                                        },
                                        onClick = { menuFor = null; confirmDelete = server },
                                    )
                                }
                            }
                            Hairline()
                        }
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
                    Button(onClick = { adding = true }) { Text("Add a server") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
                }
            }
        }
    }

    if (adding) {
        PrismServerEditor(
            server = null,
            onDismiss = { adding = false },
            onSave = { saved ->
                PrismServerClient.save(saved)
                adding = false
                reload()
                outcome = "Added " + saved.name.ifBlank { saved.address } +
                    ". Click it to connect."
                outcomeOk = true
            },
        )
    }

    editing?.let { target ->
        PrismServerEditor(
            server = target,
            onDismiss = { editing = null },
            onSave = { saved ->
                PrismServerClient.save(saved)
                editing = null
                reload()
                // NOT RECONNECTED AUTOMATICALLY, even when the edited server is the active one. Editing a
                // password and being silently disconnected on a failed reconnect is worse than staying on
                // the old connection until the user asks.
                outcome = "Saved. Click it to connect with the new details."
                outcomeOk = true
            },
        )
    }

    confirmDelete?.let { target ->
        Dialog(onDismissRequest = { confirmDelete = null }) {
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(20.dp).widthIn(max = 400.dp)) {
                    Text("Delete this server?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        target.name.ifBlank { target.address } + " will be forgotten, including its " +
                            "username and password." +
                            if (target.isActive) {
                                " It is the active server, and nothing will be active afterwards -- " +
                                    "another one is not promoted, because that would connect this " +
                                    "device somewhere it was not asked to go."
                            } else {
                                ""
                            },
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { confirmDelete = null }) {
                            Text("Keep it", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            PrismServerClient.delete(target.id)
                            confirmDelete = null
                            reload()
                            outcome = "Deleted."
                            outcomeOk = true
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

/**
 * The add and edit form. The five things a server is, and nothing else.
 *
 * ONE COMPOSABLE FOR BOTH, because an add form and an edit form that drift apart is how a field ends up
 * settable only when creating. A null [server] means adding, which is the only difference.
 */
@Composable
private fun PrismServerEditor(
    server: PrismSettings.PrismServer?,
    onDismiss: () -> Unit,
    onSave: (PrismSettings.PrismServer) -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(server?.name.orEmpty()) }
    var address by remember { mutableStateOf(server?.address.orEmpty()) }
    var port by remember { mutableStateOf((server?.port ?: 8080).toString()) }
    var username by remember { mutableStateOf(server?.username.orEmpty()) }
    var password by remember { mutableStateOf(server?.password.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf("") }

    val portNumber = port.trim().toIntOrNull()
    val valid = address.isNotBlank() && portNumber != null && portNumber in 1..65535

    fun build(): PrismSettings.PrismServer = PrismSettings.PrismServer(
        id = server?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(),
        address = address.trim(),
        port = portNumber ?: 8080,
        username = username.trim(),
        password = password,
        isActive = server?.isActive ?: false,
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).widthIn(max = 460.dp)) {
                Text(
                    if (server == null) "Add a meshnet server" else "Edit this server",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))

                Field("Name", "What to call it in this list") {
                    CommittingField(value = name, modifier = Modifier.fillMaxWidth()) { name = it }
                }
                Field("Address", "An IP address or a hostname") {
                    CommittingField(value = address, modifier = Modifier.fillMaxWidth()) { address = it }
                }
                Field("Port", "The port the server listens on") {
                    CommittingField(value = port, modifier = Modifier.fillMaxWidth()) { port = it }
                }
                Field("Username", "Leave both blank for a server with no authentication") {
                    CommittingField(value = username, modifier = Modifier.fillMaxWidth()) { username = it }
                }
                Field("Password", "Stored in this device's settings") {
                    CommittingField(value = password, modifier = Modifier.fillMaxWidth()) { password = it }
                }

                if (tested.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(tested, fontSize = 12.sp, color = colors.muted, lineHeight = 18.sp)
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Testing before saving, because the details being wrong is the common case and
                    // finding out without committing them is worth a button.
                    OutlinedButton(
                        enabled = valid && !testing,
                        onClick = {
                            testing = true
                            tested = ""
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    PrismServerClient.probe(build())
                                }
                                tested = result.message
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test", fontSize = 13.sp) }

                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = valid, onClick = { onSave(build()) }) {
                        Text(if (server == null) "Add" else "Save")
                    }
                }

                if (!valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "An address and a port between 1 and 65535 are required.",
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, hint: String, content: @Composable () -> Unit) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 13.sp)
        Text(hint, fontSize = 11.sp, color = colors.faint)
        Spacer(Modifier.height(5.dp))
        content()
    }
}
