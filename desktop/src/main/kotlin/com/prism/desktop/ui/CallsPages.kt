package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.MeshTransport
import com.prism.desktop.DesktopCallAudio
import com.prism.desktop.DesktopLocations
import com.prism.launcher.calls.MeshCallConsent
import com.prism.launcher.calls.MeshCalls
import com.prism.launcher.calls.PrismPhonePlan
import com.prism.launcher.maps.LatLon
import com.prism.launcher.maps.MapGeo
import com.prism.launcher.maps.MapPlace
import com.prism.launcher.maps.MapServices
import com.prism.launcher.maps.MapStore
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * The desktop dialer, meshvoice and map.
 *
 * ## Why these are desktop pages and not phone screens mirrored
 *
 * The request was explicit: the dialer that opens from Messages is also a page of its own, and
 * Meshvoice and Maps are pages too. On a PC there is no carrier plan, so the dialer here is the mesh
 * half -- placing a call to a trusted device, or leaving a meshnet voicemail on one that is offline
 * -- plus the same keypad, disabled and labelled, because pretending a desktop can dial a phone
 * number would be the kind of lie this project avoids elsewhere.
 *
 * ## Audio
 *
 * [DesktopCallAudio] is the platform's microphone and speaker at the engine's frame size. A machine
 * with no capture device still connects the call and says that it cannot be heard, rather than
 * silently sending nothing.
 */

// ── Dialer ─────────────────────────────────────────────────────────────────

@Composable
fun DialerPage() {
    val colors = LocalPrismColors.current
    var typed by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf<List<TrustedDevices.Trust>>(emptyList()) }
    var nodes by remember { mutableStateOf<List<MeshTransport.Node>>(emptyList()) }
    var call by remember { mutableStateOf(MeshCalls.active) }
    // Ask the network once when the page opens, so "online" is not a value from the last time the
    // page was visited. Peer discovery is a broadcast and costs nothing.
    LaunchedEffect(Unit) { runCatching { MeshTransport.discover() } }
    var warning by remember { mutableStateOf<MeshTransport.Node?>(null) }
    var note by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val listener: (MeshCalls.Call?) -> Unit = { call = it }
        val invite: (MeshCalls.Call) -> Unit = { call = it }
        val previousState = MeshCalls.onState
        val previousInvite = MeshCalls.onIncoming
        MeshCalls.onState = listener
        // A page has to be able to RECEIVE while it is open; without this the invite sat in
        // pendingInvite with nothing on screen to answer it.
        MeshCalls.onIncoming = invite
        onDispose {
            if (MeshCalls.onState === listener) MeshCalls.onState = previousState
            if (MeshCalls.onIncoming === invite) MeshCalls.onIncoming = previousInvite
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            trusted = runCatching { TrustedDevices.all().filter { it.confirmed } }.getOrDefault(emptyList())
            nodes = runCatching { MeshTransport.nodes() }.getOrDefault(emptyList())
            delay(2_000)
        }
    }

    PageScaffold("Dialer", "Mesh calls from this machine") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val current = call
            if (current != null) {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Call, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.peerName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(describeState(current), fontSize = 12.sp, color = colors.muted)
                            Text(
                                if (current.trusted) "Encrypted with your wallet key"
                                else "Not encrypted — a device you have not trusted",
                                fontSize = 11.sp,
                                color = if (current.trusted) onlineColor() else offlineColor(),
                            )
                        }
                        if (current.state == MeshCalls.State.RINGING) {
                            OutlinedButton(onClick = {
                                MeshCalls.accept()
                                note = "Call answered"
                            }) { Text("Answer") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                MeshCalls.decline()
                                note = "Call declined"
                            }) { Text("Decline") }
                        } else {
                            OutlinedButton(onClick = {
                                if (current.state == MeshCalls.State.RECORDING) MeshCalls.finishVoicemail()
                                else MeshCalls.end()
                                note = "Call ended"
                            }) { Text(if (current.state == MeshCalls.State.RECORDING) "Send" else "End") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            SectionHeader("Keypad")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        typed.ifEmpty { " " },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                        listOf("*" to "", "0" to "+", "#" to ""),
                    ).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (digit, letters) ->
                                Surface(
                                    color = Color(0xFF22222A),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 4.dp)
                                        .clickable { typed = (typed + digit).take(32) },
                                ) {
                                    Column(
                                        Modifier.padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(digit, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                        if (letters.isNotEmpty()) {
                                            Text(letters, fontSize = 8.sp, color = colors.faint)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            typed = typed.dropLast(1)
                        }) { Text("Delete") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            note = if (PrismPhonePlan.isActive()) {
                                "Carrier dialing is a phone feature; this machine has no SIM."
                            } else {
                                "This machine has no phone plan. Use a mesh call below."
                            }
                        }) {
                            Icon(Icons.Filled.Phone, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call")
                        }
                    }
                    Text(
                        "A PC has no carrier plan, so Prism cannot place a phone call from here. " +
                            "Mesh calls below go over your own meshnet and need no plan.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            SectionHeader("Trusted devices")
            Card {
                if (trusted.isEmpty()) {
                    Text(
                        "No trusted devices yet. Pair one from Trusted devices; calls to them are " +
                            "sealed with your wallet key.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    trusted.forEachIndexed { index, device ->
                        if (index > 0) Hairline()
                        // A trusted device's LAST address is a hint, not the truth: a phone on DHCP
                        // has probably moved since it was recorded, and the desktop's own peer table
                        // is the only thing that knows where it is NOW. Looking it up by name before
                        // ringing is what stops a click from being refused with "no known address"
                        // while the phone is sitting right there on the mesh.
                        val live = nodes.firstOrNull { it.address == device.lastIp }
                            ?: nodes.firstOrNull { it.name.equals(device.name, ignoreCase = true) }
                        val address = live?.address?.takeIf { it.isNotBlank() }
                            ?: device.lastIp.takeIf { it.isNotBlank() }
                        DeviceRow(
                            name = device.name,
                            detail = when {
                                live != null -> "Online · encrypted mesh call"
                                address != null -> "Encrypted mesh call · address may be stale"
                                else -> "No address yet · will ring if it announces"
                            },
                            dot = if (live != null) onlineColor() else offlineColor(),
                            titleColor = if (live != null) onlineColor() else offlineColor(),
                        ) {
                            // RING FIRST, ALWAYS. This used to record a voicemail whenever the
                            // presence flag was false -- and that flag is false for a device whose
                            // address moved, which is most of them. The invite is harmless when the
                            // device really is away (it is sealed and dropped by a device that is
                            // not there), so the honest default is to try the call and say so.
                            if (address == null) {
                                MeshTransport.discover()
                                note = "${device.name} has not announced an address on this mesh yet. " +
                                    "It usually appears a moment after both devices join the same network."
                            } else {
                                if (live != null) device.lastIp.takeIf { it != address }?.let { stale ->
                                    TrustedDevices.noteAddress(device.fingerprint, address)
                                }
                                MeshCalls.call(address, device.name, trusted = true)
                                note = MeshCalls.lastError ?: "Ringing ${device.name} at $address."
                            }
                        }
                        // A secondary action for the case the call cannot connect. It is a text
                        // row rather than a long-press menu because a desktop has no long press
                        // convention and a hidden second action is one nobody finds.
                        if (address != null) {
                            Row(
                                Modifier.fillMaxWidth().clickableRow {
                                    val started = MeshCalls.callVoicemail(address, device.name, trusted = true)
                                    note = MeshCalls.lastError ?: if (started) {
                                        "Recording a meshnet voicemail for ${device.name}."
                                    } else {
                                        "The recording could not start on this machine."
                                    }
                                }.padding(start = 38.dp, end = 16.dp, bottom = 12.dp),
                            ) {
                                Text(
                                    "Leave a voicemail instead",
                                    fontSize = 11.sp,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader("On your meshnet")
            Card {
                val other = nodes.filter { node -> trusted.none { it.lastIp == node.address } }
                if (other.isEmpty()) {
                    Text(
                        "No other devices on this meshnet.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    other.forEachIndexed { index, node ->
                        if (index > 0) Hairline()
                        DeviceRow(
                            name = node.name.ifBlank { node.address },
                            detail = "On this meshnet · not encrypted",
                            dot = colors.faint,
                            titleColor = colors.muted,
                        ) {
                            if (MeshCallConsent.warned(node.address)) {
                                MeshCalls.call(node.address, node.name.ifBlank { node.address }, trusted = false)
                                note = MeshCalls.lastError ?: "Ringing ${node.name.ifBlank { node.address }}."
                            } else {
                                warning = node
                            }
                        }
                    }
                }
            }

            if (note.isNotBlank()) {
                SectionFooter(note)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    warning?.let { node ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warning = null },
            title = { Text("This call is not encrypted") },
            text = {
                Text(
                    "${node.name.ifBlank { node.address }} is on your meshnet but is not one of your " +
                        "trusted devices, so Prism has no shared key to seal the call with. Anyone " +
                        "on this network can hear it."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    MeshCallConsent.markWarned(node.address)
                    MeshCalls.call(node.address, node.name.ifBlank { node.address }, trusted = false)
                    warning = null
                }) { Text("Call unencrypted") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { warning = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    dot: Color,
    titleColor: Color,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = titleColor)
            Text(detail, fontSize = 11.sp, color = colors.muted)
        }
    }
}

private fun describeState(call: MeshCalls.Call): String = when (call.state) {
    MeshCalls.State.DIALING -> "Ringing…"
    MeshCalls.State.RINGING -> "Incoming"
    MeshCalls.State.ACTIVE -> "In call"
    MeshCalls.State.RECORDING -> "Recording a meshnet voicemail"
    MeshCalls.State.ENDED -> "Ended"
    MeshCalls.State.IDLE -> "Idle"
}

private fun onlineColor() = Color(0xFF30D158)
private fun offlineColor() = Color(0xFFFF6FB5)

// ── Meshvoice ──────────────────────────────────────────────────────────────

@Composable
fun MeshvoicePage() {
    val colors = LocalPrismColors.current
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var playing by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    val mesh = remember(revision) {
        runCatching { MeshCalls.MeshVoice.all() }.getOrDefault(emptyList())
    }
    val carrier = remember(revision) {
        runCatching { PrismPhonePlan.Voicemail.all() }.getOrDefault(emptyList())
    }
    // A desktop has no carrier plan, but the rule is the same one the phone follows and the page
    // must not draw a Voicemail tab that can never fill. Serving or joined both count; past
    // messages keep the tab usable when the machine is offline.
    val plan = PrismPhonePlan.isActive()
    val meshAvailable = MeshTransport.isOnMesh() || mesh.isNotEmpty()
    var tab by remember { mutableStateOf(if (plan) 0 else 1) }
    LaunchedEffect(plan, meshAvailable) {
        if (!plan) tab = 1
        else if (tab == 1 && !meshAvailable) tab = 0
    }

    PageScaffold("Meshvoice", "Voicemail from the carrier and from your meshnet") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan) {
                Tab("Voicemail", active = tab == 0) { tab = 0; selected = emptySet() }
            }
            Tab("Meshvoice", active = tab == 1, enabled = meshAvailable) {
                tab = 1; selected = emptySet()
            }
        }
        Spacer(Modifier.height(10.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                if (carrier.isEmpty()) {
                    Card {
                        Text(
                            "No carrier voicemails. A carrier mailbox is delivered through a phone " +
                                "plan, which this machine does not have; Prism stores and exports " +
                                "what a plan hands over and cannot read the mailbox itself.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    Card {
                        carrier.forEachIndexed { index, message ->
                            if (index > 0) Hairline()
                            VoiceRow(
                                title = message.fromName.ifBlank {
                                    message.fromNumber.ifBlank { "Unknown caller" }
                                },
                                detail = buildString {
                                    append(formatTime(message.receivedAt))
                                    val seconds = (message.durationMs / 1000).toInt()
                                    if (seconds > 0) {
                                        append(" · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                                    }
                                    if (!message.heard) append(" · new")
                                },
                                selected = message.id in selected,
                                playing = playing == message.id,
                                onPlay = {
                                    val bytes = PrismPhonePlan.Voicemail.audioOf(message)
                                    if (bytes == null) {
                                        note = "That voicemail could not be read."
                                    } else if (playing == message.id) {
                                        DesktopCallAudio.stopWav()
                                        playing = null
                                    } else {
                                        DesktopCallAudio.playWav(bytes)
                                        playing = message.id
                                        PrismPhonePlan.Voicemail.markHeard(message)
                                    }
                                },
                                onToggle = {
                                    selected = if (message.id in selected) selected - message.id
                                    else selected + message.id
                                },
                            )
                        }
                    }
                }
            } else {
                if (mesh.isEmpty()) {
                    Card {
                        Text(
                            if (meshAvailable) {
                                "No meshnet voicemails yet. A voicemail sent to an offline trusted " +
                                    "device lands here, sealed with the wallet key."
                            } else {
                                "Not on a meshnet on this machine, and no meshnet voicemails have " +
                                    "been received here. Past messages stay readable offline; join or " +
                                    "serve a mesh to receive new ones."
                            },
                            fontSize = 12.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    Card {
                        mesh.forEachIndexed { index, message ->
                            if (index > 0) Hairline()
                            VoiceRow(
                                title = message.fromName.ifBlank { "Trusted device" },
                                detail = buildString {
                                    append(formatTime(message.receivedAt))
                                    if (message.durationMs > 0) {
                                        val seconds = (message.durationMs / 1000).toInt()
                                        append(" · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                                    }
                                    if (message.outgoing) {
                                        append(
                                            if (MeshCalls.MeshVoice.isPending(message.id)) " · queued"
                                            else " · sent"
                                        )
                                    }
                                },
                                selected = message.id in selected,
                                playing = playing == message.id,
                                onPlay = {
                                    val bytes = MeshCalls.MeshVoice.audioOf(message)
                                    if (bytes == null) {
                                        note = "That voicemail cannot be opened with the current wallet."
                                    } else if (playing == message.id) {
                                        DesktopCallAudio.stopWav()
                                        playing = null
                                    } else {
                                        DesktopCallAudio.playWav(bytes)
                                        playing = message.id
                                    }
                                },
                                onToggle = {
                                    selected = if (message.id in selected) selected - message.id
                                    else selected + message.id
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    revision++
                }) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        if (selected.isEmpty()) {
                            note = "Select one or more voicemails first."
                        } else {
                            note = exportSelected(tab, mesh, carrier, selected)
                        }
                    },
                    enabled = selected.isNotEmpty(),
                ) { Text("Export ${selected.size.coerceAtLeast(0)} selected") }
            }
            if (note.isNotBlank()) SectionFooter(note)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onPlay).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            null,
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, color = colors.muted)
        }
        Box(
            Modifier
                .size(22.dp)
                .border(
                    width = 1.dp,
                    color = if (selected) colors.accent else colors.faint,
                    shape = CircleShape,
                )
                .background(if (selected) colors.accent else Color.Transparent, CircleShape)
                .clickableRow(onToggle),
        )
    }
}

@Composable
private fun Tab(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (active) colors.surfaceRaised else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.then(if (enabled) Modifier.clickableRow(onClick) else Modifier),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> colors.faint
                active -> colors.accent
                else -> colors.muted
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun formatTime(at: Long): String =
    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

/** Saves one voicemail as a WAV, or several as a zip. Returns what happened, for the footer. */
private fun exportSelected(
    tab: Int,
    mesh: List<MeshCalls.MeshVoice.Message>,
    carrier: List<PrismPhonePlan.Voicemail.Message>,
    selected: Set<String>,
): String {
    val items = if (tab == 0) {
        carrier.filter { it.id in selected }.mapNotNull { message ->
            PrismPhonePlan.Voicemail.audioOf(message)?.let { bytes ->
                PrismPhonePlan.Export.fileName(message) to bytes
            }
        }
    } else {
        mesh.filter { it.id in selected }.mapNotNull { message ->
            MeshCalls.MeshVoice.audioOf(message)?.let { bytes -> "meshvoice-${message.id.take(8)}.wav" to bytes }
        }
    }
    if (items.isEmpty()) return "Nothing in the selection could be read."

    val chooser = JFileChooser().apply {
        dialogTitle = if (items.size == 1) "Save voicemail" else "Save voicemail archive"
        if (items.size == 1) {
            fileFilter = FileNameExtensionFilter("WAV audio", "wav")
            selectedFile = File(items.first().first)
        } else {
            fileFilter = FileNameExtensionFilter("Zip archive", "zip")
            selectedFile = File("voicemail-${items.size}.zip")
        }
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return "Export cancelled."
    var target = chooser.selectedFile ?: return "Export cancelled."

    return runCatching {
        if (items.size == 1) {
            if (!target.name.contains('.')) target = File(target.parentFile, target.name + ".wav")
            target.writeBytes(items.first().second)
        } else {
            if (!target.name.contains('.')) target = File(target.parentFile, target.name + ".zip")
            val archive = PrismPhonePlan.Export.zip(items) ?: return "The archive could not be built."
            target.writeBytes(archive)
        }
        "Saved to ${target.absolutePath}"
    }.getOrElse { "Could not save: ${it.message}" }
}

// ── Maps ───────────────────────────────────────────────────────────────────

private const val TILE = MapGeo.TILE_SIZE

/**
 * The desktop map.
 *
 * Same services as the phone -- Nominatim for places, OSRM and Transitous for routes, OSM tiles
 * cached on disk -- because "maps work the same way" means the answers come from the same places,
 * not that a PC needs a second implementation. What differs is the input: a mouse drags the map and
 * the search box is typed into, rather than the phone's pinch and thumb.
 *
 * The trusted-device layer is unchanged. Positions arrive sealed from devices the user allowed, and
 * the blue dot is this machine's own chosen place, which is what it publishes when sharing is on.
 */
@Composable
fun MapsPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableStateOf(0) }
    var fixes by remember { mutableStateOf<List<TrustedLocations.Fix>>(emptyList()) }
    var trusted by remember { mutableStateOf<List<TrustedDevices.Trust>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var place by remember { mutableStateOf(DesktopLocations.position()) }

    var centre by remember { mutableStateOf(LatLon(20.0, 0.0)) }
    var zoom by remember { mutableStateOf(13) }
    var didCentre by remember { mutableStateOf(false) }
    var loadTiles by remember { mutableStateOf(true) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }

    var selectedPlace by remember { mutableStateOf<MapPlace?>(null) }
    var selectedFingerprint by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<MapServices.Route?>(null) }
    var routeMode by remember { mutableStateOf(MapServices.TravelMode.CAR) }
    var routeBusy by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var showLocationDialog by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            revision++
        }
    }

    LaunchedEffect(revision) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                runCatching { TrustedLocations.all() }.getOrDefault(emptyList()),
                runCatching { TrustedDevices.all().filter { it.confirmed } }.getOrDefault(emptyList()),
                runCatching { MapStore.favorites() }.getOrDefault(emptyList()),
            )
        }
        fixes = loaded.first
        trusted = loaded.second
        favorites = loaded.third
        place = DesktopLocations.position()
    }

    // The first position that exists decides where the map opens. After that the user's own
    // searching and panning owns the viewport; a poll must not yank it back every three seconds.
    LaunchedEffect(fixes, place) {
        if (didCentre) return@LaunchedEffect
        val initial = place?.let { LatLon(it.latitude, it.longitude) }
            ?: fixes.firstOrNull()?.let { LatLon(it.latitude, it.longitude) }
        if (initial != null) {
            centre = initial
            didCentre = true
        }
    }

    LaunchedEffect(query) {
        val cleaned = query.trim()
        if (cleaned.length < 2) {
            results = emptyList()
            searchError = ""
            searchBusy = false
            return@LaunchedEffect
        }
        searchBusy = true
        searchError = ""
        delay(450)
        val found = withContext(Dispatchers.IO) {
            runCatching { MapServices.search(cleaned) }.getOrDefault(emptyList())
        }
        searchBusy = false
        results = found
        if (found.isEmpty()) searchError = "No places matched. Try a town or a street address."
    }

    fun choosePlace(chosen: MapPlace) {
        selectedPlace = chosen
        selectedFingerprint = null
        route = null
        routeError = ""
        note = ""
        centre = chosen.point()
        zoom = 15
        query = ""
        results = emptyList()
    }

    fun startRoute(mode: MapServices.TravelMode) {
        val destination = selectedPlace ?: return
        val origin = place ?: run {
            note = "Set this machine's location before asking for a route."
            showLocationDialog = true
            return
        }
        routeMode = mode
        routeBusy = true
        routeError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                MapServices.route(
                    LatLon(origin.latitude, origin.longitude),
                    destination.point(),
                    mode,
                )
            }
            routeBusy = false
            result.onSuccess { loaded ->
                route = loaded
                val fit = fitRoute(loaded.points)
                if (fit != null) {
                    centre = fit.first
                    zoom = fit.second
                }
            }.onFailure {
                route = null
                routeError = it.message ?: "No route was returned."
            }
        }
    }

    fun toggleFavorite(target: MapPlace) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { MapStore.toggleFavorite(target) }.getOrDefault(false)
            }
            favorites = withContext(Dispatchers.IO) {
                runCatching { MapStore.favorites() }.getOrDefault(emptyList())
            }
            note = if (saved) "Saved to favorites." else "Removed from favorites."
        }
    }

    PageScaffold(
        "Maps",
        "Search places, get directions, and share where this machine is",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search places", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
            )
            if (searchBusy) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (query.isNotBlank()) {
                TextButton(onClick = {
                    query = ""
                    results = emptyList()
                    searchError = ""
                }) { Text("Clear") }
            }
        }

        if (results.isNotEmpty() || searchError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Card {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    results.forEach { found ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow { choosePlace(found) }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(found.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (found.address.isNotBlank() && found.address != found.name) {
                                Text(found.address, fontSize = 11.sp, color = colors.faint, maxLines = 2)
                            }
                        }
                    }
                    if (searchError.isNotBlank()) {
                        Text(searchError, fontSize = 12.sp, color = colors.faint,
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = {
                val current = place
                if (current == null) {
                    showLocationDialog = true
                } else {
                    centre = LatLon(current.latitude, current.longitude)
                    zoom = 15
                }
            }) {
                Icon(Icons.Filled.MyLocation, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("My location", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showFavorites = true }) {
                Icon(Icons.Filled.Star, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Favorites (${favorites.size})", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showOffline = true }) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Offline", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { loadTiles = !loadTiles }) {
                Text(if (loadTiles) "Tiles on" else "Tiles off", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { zoom = (zoom + 1).coerceAtMost(18) }) { Text("+") }
            OutlinedButton(onClick = { zoom = (zoom - 1).coerceAtLeast(3) }) { Text("−") }
            Spacer(Modifier.width(6.dp))
            Text(
                if (TrustedLocations.sharingEnabled()) {
                    // Detection runs on its own; until the first fix lands this says so rather
                    // than asking the user to set a place they no longer have to set.
                    "Sharing on · " + DesktopLocations.status()
                } else {
                    "Sharing off"
                },
                fontSize = 11.sp,
                color = colors.faint,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 320.dp)
        ) {
            MapCanvas(
                centre = centre,
                zoom = zoom,
                fixes = fixes,
                trusted = trusted,
                favorites = favorites,
                selectedPlace = selectedPlace,
                current = place?.let { LatLon(it.latitude, it.longitude) },
                route = route?.points.orEmpty(),
                loadTiles = loadTiles,
                onCentreChange = { centre = it },
                onPlaceTap = { chosen ->
                    selectedPlace = chosen
                    selectedFingerprint = null
                    route = null
                    routeError = ""
                    note = ""
                },
                onDeviceTap = { fingerprint ->
                    selectedFingerprint = fingerprint
                    selectedPlace = null
                    route = null
                    routeError = ""
                },
                onMapTap = {
                    selectedPlace = null
                    selectedFingerprint = null
                },
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val chosen = selectedPlace
                val currentFix = fixes.firstOrNull { it.fingerprint == selectedFingerprint }
                if (chosen != null) {
                    MapPlaceCard(
                        place = chosen,
                        route = route,
                        mode = routeMode,
                        busy = routeBusy,
                        error = routeError,
                        favorite = favorites.any { it.id == chosen.id },
                        note = note,
                        onDirections = { mode -> startRoute(mode) },
                        onClearRoute = { route = null; routeError = "" },
                        onToggleFavorite = { toggleFavorite(chosen) },
                        onClose = { selectedPlace = null; route = null; routeError = ""; note = "" },
                    )
                } else if (currentFix != null) {
                    MapDeviceCard(
                        fix = currentFix,
                        online = trusted.firstOrNull { it.answersTo(currentFix.fingerprint) }?.online == true,
                        note = note,
                        onFind = {
                            val requested = TrustedLocations.requestFind(currentFix.fingerprint)
                            note = if (requested) {
                                "Asked ${currentFix.name} to ring."
                            } else {
                                "The find request could not be sent; the device may be off the mesh."
                            }
                        },
                        onClose = { selectedFingerprint = null; note = "" },
                    )
                } else {
                    SectionFooter(
                        "Green is a device online now; pink is a device whose last position is " +
                            "still shown. Positions are sealed with the wallet key and arrive only " +
                            "from devices you allowed. Tiles are fetched from OpenStreetMap by tile " +
                            "coordinate only — no position is sent to it.",
                    )
                }
            }
        }
    }

    if (showLocationDialog) {
        DesktopLocationDialog(
            title = "This machine's location",
            onDismiss = { showLocationDialog = false },
            onChosen = { chosen ->
                DesktopLocations.set(
                    chosen.lat,
                    chosen.lon,
                    chosen.name.ifBlank { chosen.address },
                )
                place = DesktopLocations.position()
                centre = chosen.point()
                zoom = 15
                didCentre = true
                showLocationDialog = false
                note = "This machine's position is now ${chosen.name}."
            },
        )
    }

    if (showFavorites) {
        FavoritesDialog(
            favorites = favorites,
            onDismiss = { showFavorites = false },
            onPick = { chosen ->
                showFavorites = false
                choosePlace(chosen)
                didCentre = true
            },
        )
    }

    if (showOffline) {
        OfflineMapsDialog(
            centre = centre,
            label = selectedPlace?.name ?: place?.label.orEmpty().ifBlank { "Map centre" },
            onDismiss = { showOffline = false },
        )
    }
}

@Composable
private fun MapPlaceCard(
    place: MapPlace,
    route: MapServices.Route?,
    mode: MapServices.TravelMode,
    busy: Boolean,
    error: String,
    favorite: Boolean,
    note: String,
    onDirections: (MapServices.TravelMode) -> Unit,
    onClearRoute: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        place.address.ifBlank { "%.5f, %.5f".format(place.lat, place.lon) },
                        fontSize = 11.sp,
                        color = colors.faint,
                        maxLines = 2,
                    )
                }
                TextButton(onClick = onClose) { Text("Close", fontSize = 12.sp) }
            }

            if (route != null) {
                Spacer(Modifier.height(4.dp))
                Text(route.summary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(route.attribution, fontSize = 10.sp, color = colors.faint)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapServices.TravelMode.values().forEach { travel ->
                        OutlinedButton(onClick = { onDirections(travel) }) {
                            Text(
                                travel.label + if (travel == mode) " ✓" else "",
                                fontSize = 11.sp,
                            )
                        }
                    }
                    TextButton(onClick = onClearRoute) { Text("Clear route", fontSize = 11.sp) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onDirections(mode) }, enabled = !busy) {
                        Icon(
                            when (mode) {
                                MapServices.TravelMode.CAR -> Icons.Filled.DirectionsCar
                                MapServices.TravelMode.WALK -> Icons.Filled.DirectionsWalk
                                MapServices.TravelMode.TRANSIT -> Icons.Filled.DirectionsTransit
                            },
                            null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Directions", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onToggleFavorite) {
                        Icon(Icons.Filled.Star, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (favorite) "Remove favorite" else "Save favorite", fontSize = 12.sp)
                    }
                    MapServices.TravelMode.values().forEach { travel ->
                        if (travel != mode) {
                            OutlinedButton(onClick = { onDirections(travel) }, enabled = !busy) {
                                Text(travel.label, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (error.isNotBlank()) {
                    Text(error, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (note.isNotBlank()) {
                Text(note, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MapDeviceCard(
    fix: TrustedLocations.Fix,
    online: Boolean,
    note: String,
    onFind: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (online) onlineColor() else offlineColor(), CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(fix.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close", fontSize = 12.sp) }
            }
            Text(
                "%.5f, %.5f".format(fix.latitude, fix.longitude),
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text("Last position ${formatTime(fix.at)}", fontSize = 11.sp, color = colors.faint)
            if (online) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onFind) {
                    Icon(Icons.Filled.VolumeUp, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play a sound", fontSize = 12.sp)
                }
            }
            if (note.isNotBlank()) {
                Text(note, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MapCanvas(
    centre: LatLon,
    zoom: Int,
    fixes: List<TrustedLocations.Fix>,
    trusted: List<TrustedDevices.Trust>,
    favorites: List<MapPlace>,
    selectedPlace: MapPlace?,
    current: LatLon?,
    route: List<LatLon>,
    loadTiles: Boolean,
    onCentreChange: (LatLon) -> Unit,
    onPlaceTap: (MapPlace) -> Unit,
    onDeviceTap: (String?) -> Unit,
    onMapTap: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    var mapSize by remember { mutableStateOf(IntSize(1000, 700)) }

    val latestCentre by rememberUpdatedState(centre)
    val latestFixes by rememberUpdatedState(fixes)
    val latestFavorites by rememberUpdatedState(favorites)
    val latestPlace by rememberUpdatedState(selectedPlace)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101014), RoundedCornerShape(14.dp))
            .border(1.dp, colors.surfaceRaised, RoundedCornerShape(14.dp))
            .onSizeChanged { mapSize = it }
            .pointerInput(zoom) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = down.position
                    var total = Offset.Zero
                    var moved = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - last
                        last = change.position
                        total += delta
                        if (!moved && total.getDistance() > slop) moved = true
                        if (moved) {
                            onCentreChange(
                                panCentre(latestCentre, -delta.x, -delta.y, zoom)
                            )
                            change.consume()
                        }
                    }

                    if (!moved) {
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val centreNow = latestCentre
                        val x = down.position.x
                        val y = down.position.y
                        fun near(lat: Double, lon: Double, radius: Float): Boolean {
                            val point = project(lat, lon, centreNow, zoom, width, height)
                            val dx = point.x - x
                            val dy = point.y - y
                            return dx * dx + dy * dy <= radius * radius
                        }

                        val favorite = latestFavorites.firstOrNull { near(it.lat, it.lon, 30f) }
                        val place = latestPlace?.takeIf { near(it.lat, it.lon, 30f) }
                        val fix = latestFixes.firstOrNull { near(it.latitude, it.longitude, 30f) }
                        when {
                            favorite != null -> onPlaceTap(favorite)
                            place != null -> onPlaceTap(place)
                            fix != null -> onDeviceTap(fix.fingerprint)
                            else -> onMapTap()
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val scale = MapGeo.worldScale(zoom)
            val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
            val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0

            fun sx(lon: Double): Float = (MapGeo.lonToWorldX(lon, zoom) - left).toFloat()
            fun sy(lat: Double): Float = (MapGeo.latToWorldY(lat, zoom) - top).toFloat()

            if (loadTiles) {
                val count = 1 shl zoom
                val firstX = floor(left / TILE).toInt()
                val lastX = floor((left + width) / TILE).toInt()
                val firstY = floor(top / TILE).toInt()
                val lastY = floor((top + height) / TILE).toInt()
                for (x in firstX..lastX) {
                    for (y in firstY..lastY) {
                        if (x !in 0 until count || y !in 0 until count) continue
                        val key = "$zoom/$x/$y"
                        val tile = tiles[key]
                        val dx = (x * TILE - left).toFloat()
                        val dy = (y * TILE - top).toFloat()
                        if (tile != null) {
                            drawImage(
                                tile,
                                dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                                dstSize = IntSize(TILE.toInt(), TILE.toInt()),
                            )
                        } else {
                            drawRect(
                                Color(0xFF17171C),
                                topLeft = Offset(dx, dy),
                                size = androidx.compose.ui.geometry.Size(TILE.toFloat(), TILE.toFloat()),
                            )
                        }
                    }
                }
            } else {
                var gx = 0f
                while (gx < width) {
                    drawLine(Color(0xFF1E1E26), Offset(gx, 0f), Offset(gx, height))
                    gx += 64f
                }
                var gy = 0f
                while (gy < height) {
                    drawLine(Color(0xFF1E1E26), Offset(0f, gy), Offset(width, gy))
                    gy += 64f
                }
            }

            if (route.size > 1) {
                val path = Path()
                route.forEachIndexed { index, point ->
                    val x = sx(point.lon)
                    val y = sy(point.lat)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    Color(0xFF09090C),
                    style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path,
                    Color(0xFF0A84FF),
                    style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                route.firstOrNull()?.let { drawCircle(Color.White, 6f, Offset(sx(it.lon), sy(it.lat))) }
                route.lastOrNull()?.let { drawCircle(Color(0xFF30D158), 8f, Offset(sx(it.lon), sy(it.lat))) }
            }

            favorites.forEach { favorite ->
                val point = Offset(sx(favorite.lon), sy(favorite.lat))
                drawCircle(Color(0xFFFFD60A), 8f, point)
                drawCircle(Color(0xFF231C00), 3f, point)
            }

            selectedPlace?.let { chosen ->
                val point = Offset(sx(chosen.lon), sy(chosen.lat))
                drawCircle(Color(0xFFFF3B30), 10f, point)
                drawCircle(Color.White, 4f, point)
            }

            current?.let { point ->
                val screen = Offset(sx(point.lon), sy(point.lat))
                drawCircle(Color.White, 11f, screen)
                drawCircle(Color(0xFF0A84FF), 8f, screen)
            }

            fixes.forEach { fix ->
                val point = Offset(sx(fix.longitude), sy(fix.latitude))
                if (point.x < -24 || point.y < -24 || point.x > width + 24 || point.y > height + 24) {
                    return@forEach
                }
                val device = trusted.firstOrNull { it.answersTo(fix.fingerprint) }
                val colour = if (device?.online == true) onlineColor() else offlineColor()
                drawCircle(Color(0xFF0B0B0E), 9f, point)
                drawCircle(colour, 7f, point)
            }
        }

        LaunchedEffect(centre, zoom, loadTiles, mapSize) {
            if (!loadTiles || mapSize.width <= 0) return@LaunchedEffect
            val width = mapSize.width.toFloat()
            val height = mapSize.height.toFloat()
            val scale = MapGeo.worldScale(zoom)
            val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
            val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
            val count = 1 shl zoom
            for (x in floor(left / TILE).toInt()..floor((left + width) / TILE).toInt()) {
                for (y in floor(top / TILE).toInt()..floor((top + height) / TILE).toInt()) {
                    if (x !in 0 until count || y !in 0 until count) continue
                    val key = "$zoom/$x/$y"
                    if (tiles.containsKey(key)) continue
                    val tile = withContext(Dispatchers.IO) { loadTile(zoom, x, y) } ?: continue
                    tiles[key] = tile
                }
            }
        }
    }
}

/** Keeps the map centred on the screen while the user drags. */
private fun panCentre(centre: LatLon, dxPixels: Float, dyPixels: Float, zoom: Int): LatLon {
    val x = MapGeo.lonToWorldX(centre.lon, zoom) + dxPixels
    val y = MapGeo.latToWorldY(centre.lat, zoom) + dyPixels
    return LatLon(MapGeo.worldYToLat(y, zoom), MapGeo.worldXToLon(x, zoom))
}

/** A point's position within the map, given the current viewport. */
private fun project(
    lat: Double,
    lon: Double,
    centre: LatLon,
    zoom: Int,
    width: Float,
    height: Float,
): Offset {
    val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
    val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
    return Offset(
        (MapGeo.lonToWorldX(lon, zoom) - left).toFloat(),
        (MapGeo.latToWorldY(lat, zoom) - top).toFloat(),
    )
}

/** Chooses a centre and zoom that leave a whole route in view. */
private fun fitRoute(points: List<LatLon>): Pair<LatLon, Int>? {
    if (points.isEmpty()) return null
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val centre = LatLon((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    var chosen = 16
    while (chosen > 3) {
        val lonSpan = MapGeo.lonToWorldX(maxLon, chosen) - MapGeo.lonToWorldX(minLon, chosen)
        val latSpan = MapGeo.latToWorldY(maxLat, chosen) - MapGeo.latToWorldY(minLat, chosen)
        if (lonSpan < 850.0 && latSpan < 520.0) break
        chosen--
    }
    return centre to chosen
}

@Composable
private fun FavoritesDialog(
    favorites: List<MapPlace>,
    onDismiss: () -> Unit,
    onPick: (MapPlace) -> Unit,
) {
    val colors = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Favorite places", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (favorites.isEmpty()) {
                    Text(
                        "No favorites yet. Search for a place and use Save favorite.",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        favorites.forEach { favorite ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickableRow { onPick(favorite) }
                                    .padding(vertical = 9.dp)
                            ) {
                                Text(favorite.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    favorite.address,
                                    fontSize = 11.sp,
                                    color = colors.faint,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun OfflineMapsDialog(
    centre: LatLon,
    label: String,
    onDismiss: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var radius by remember { mutableStateOf(5) }
    var areas by remember { mutableStateOf<List<MapStore.OfflineArea>>(emptyList()) }
    var downloading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        areas = withContext(Dispatchers.IO) {
            runCatching { MapStore.offlineAreas() }.getOrDefault(emptyList())
        }
    }

    val estimate = remember(centre, radius) {
        MapStore.estimateTiles(centre.lat, centre.lon, radius, 12, 16)
    }

    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Offline maps", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Downloads the tiles around $label from zoom 12 to 16. Once they are on " +
                        "disk the map pans and zooms there without a connection; search and " +
                        "directions still need one.",
                    fontSize = 12.sp,
                    color = colors.faint,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 5, 10).forEach { choice ->
                        OutlinedButton(
                            onClick = { radius = choice },
                            enabled = !downloading,
                        ) {
                            Text(
                                "$choice km" + if (choice == radius) " ✓" else "",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$estimate tiles at the selected size" +
                        if (estimate > 2_500) " — too many for an offline download" else "",
                    fontSize = 12.sp,
                    color = if (estimate > 2_500) colors.faint else colors.muted,
                )
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (total == 0) 0f else done.toFloat() / total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("$done of $total tiles", fontSize = 11.sp, color = colors.faint)
                } else {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            error = ""
                            downloading = true
                            done = 0
                            total = 0
                            scope.launch {
                                val area = MapStore.OfflineArea(
                                    id = "offline-${System.currentTimeMillis()}",
                                    name = label,
                                    lat = centre.lat,
                                    lon = centre.lon,
                                    radiusKm = radius,
                                    minZoom = 12,
                                    maxZoom = 16,
                                    tileCount = 0,
                                    downloaded = 0,
                                    at = System.currentTimeMillis(),
                                )
                                val requested = withContext(Dispatchers.IO) {
                                    MapStore.tilesFor(area)
                                }
                                total = requested.size
                                var completed = 0
                                for (tile in requested) {
                                    val ok = withContext(Dispatchers.IO) {
                                        downloadTile(tile.first, tile.second, tile.third)
                                    }
                                    if (ok) completed++
                                    done = completed
                                }
                                val saved = area.copy(
                                    tileCount = requested.size,
                                    downloaded = completed,
                                )
                                withContext(Dispatchers.IO) {
                                    runCatching { MapStore.addOfflineArea(saved) }
                                }
                                areas = withContext(Dispatchers.IO) {
                                    runCatching { MapStore.offlineAreas() }.getOrDefault(emptyList())
                                }
                                downloading = false
                                if (completed < requested.size) {
                                    error = "Some tiles could not be downloaded. The rest are saved."
                                }
                            }
                        },
                        enabled = estimate in 1..2_500,
                    ) { Text("Download this area", fontSize = 12.sp) }
                }
                if (error.isNotBlank()) {
                    Text(error, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
                }

                if (areas.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Downloaded areas", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        areas.forEach { area ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(area.name, fontSize = 13.sp)
                                    Text(
                                        "%.4f, %.4f · ${area.radiusKm} km · " +
                                            "${area.downloaded}/${area.tileCount} tiles",
                                        fontSize = 10.sp,
                                        color = colors.faint,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                runCatching { MapStore.removeOfflineArea(area) }
                                            }
                                            areas = withContext(Dispatchers.IO) {
                                                runCatching { MapStore.offlineAreas() }
                                                    .getOrDefault(emptyList())
                                            }
                                        }
                                    },
                                    enabled = !downloading,
                                ) { Text("Remove", fontSize = 11.sp) }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !downloading) { Text("Close") }
                }
            }
        }
    }
}

/**
 * One OpenStreetMap tile, from the disk cache or the network.
 *
 * The User-Agent identifies Prism because the tile service requires it and blocks anonymous
 * clients. Nothing about the user goes with the request: a tile is addressed by zoom, column and
 * row, and the cache directory means a map that has been looked at once is not looked at again.
 */
private fun loadTile(z: Int, x: Int, y: Int): ImageBitmap? {
    if (!downloadTile(z, x, y)) return null
    val file = tileFile(z, x, y) ?: return null
    return runCatching { ImageIO.read(file).toComposeImageBitmap() }.getOrNull()
}

/** Fetches a tile into the shared cache. Returns true when the file is readable afterwards. */
private fun downloadTile(z: Int, x: Int, y: Int): Boolean {
    val file = tileFile(z, x, y) ?: return false
    if (file.isFile && file.length() > 0) return true
    return runCatching {
        val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "PrismDesktop/1.0 (mesh map; contact: prism@localhost)")
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return false
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)
}

private fun tileFile(z: Int, x: Int, y: Int): File? {
    val dir = runCatching {
        File(com.prism.core.PrismPlatform.host.dataDir(), "maptiles/$z/$x").apply { mkdirs() }
    }.getOrNull() ?: return null
    return File(dir, "$y.png")
}

// ── Projection ─────────────────────────────────────────────────────────────

private fun lonToWorldX(lon: Double, scale: Double): Double = (lon + 180.0) / 360.0 * scale

private fun latToWorldY(lat: Double, scale: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val rad = clamped * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * scale
}
