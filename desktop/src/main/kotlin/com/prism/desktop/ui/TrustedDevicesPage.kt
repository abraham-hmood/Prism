package com.prism.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.MeshCore
import com.prism.launcher.trusted.TrustedDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Trusted devices, on desktop.
 *
 * ## Why this page exists at all on a desktop
 *
 * Because the feature is symmetric and the request was explicit about it: the device that RECEIVES has to
 * agree, and it does not matter which kind of device that is. A phone that could offer to a desktop but
 * never be offered to by one would be half a feature, and the half that is missing is the half where the
 * user's own PC decides what it accepts.
 *
 * ## Why the picker lists peers rather than trusted devices
 *
 * Adding a device means picking one off the meshnet, so the list is [MeshCore.peers] — everything that has
 * answered a heartbeat — minus whatever is already paired. A device that is not on the mesh cannot be
 * offered to, because there is nothing to send the offer to; saying so is more useful than an empty list
 * with no explanation.
 *
 * ## Why the incoming prompt is a modal dialog
 *
 * It is a consent decision about what this machine will store. A toast can be missed and a tray balloon
 * can be swallowed by the OS; neither should be able to be MISTAKEN for an answer. Declining is one click
 * and recorded, so it is asked once rather than on every launch.
 */
@Composable
fun TrustedDevicesPage() {
    val colors = LocalPrismColors.current

    var devices by remember { mutableStateOf<List<TrustedDevices.Trust>>(emptyList()) }
    var offers by remember { mutableStateOf<List<TrustedDevices.PendingOffer>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }

    var picking by remember { mutableStateOf(false) }
    var chosenPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var answering by remember { mutableStateOf<TrustedDevices.PendingOffer?>(null) }
    var showingCode by remember { mutableStateOf<Pair<String, String>?>(null) }
    var note by remember { mutableStateOf("") }

    // Polled rather than observed. The trust store has no change stream -- it is written by mesh threads
    // that know nothing about a UI -- and a two-second poll of an in-memory map costs nothing next to
    // adding a listener mechanism that one page would use.
    LaunchedEffect(revision) {
        while (true) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { TrustedDevices.all() }.getOrDefault(emptyList())
            }
            devices = loaded
            offers = TrustedDevices.pendingOffers()
            delay(2_000)
        }
    }

    PageScaffold("Trusted devices", "Devices you share with automatically") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (offers.isNotEmpty()) {
                SectionHeader("waiting for you")
                Card {
                    offers.forEachIndexed { index, offer ->
                        if (index > 0) Hairline()
                        NavRow(
                            title = offer.name + " wants to send this machine data",
                            detail = offer.kinds.joinToString(", ") { it.label } + " · from " + offer.ip,
                            onClick = { answering = offer },
                        )
                    }
                }
            }

            SectionHeader("this machine")
            Card {
                InfoRow("Name", TrustedDevices.localName())
                Hairline()
                InfoRow("Identity", TrustedDevices.localFingerprint(), mono = true)
                Hairline()
                InfoRow("Platform", TrustedDevices.localPlatform)
            }
            SectionFooter(
                "The identity is derived from your recovery phrase and this machine's name, not from its " +
                    "address — an address is a DHCP lease and changes. It is what the far side records, " +
                    "so it is what you check against when a device asks to pair."
            )

            SectionHeader(if (devices.isEmpty()) "no trusted devices" else "trusted")
            if (devices.isEmpty()) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Nothing is shared yet. Add a device and the two of you exchange text " +
                                "messages, browser history, clipboard and the apps you can virtualize " +
                                "— both ways, and only what you both agree to.",
                            fontSize = 13.sp,
                            color = colors.faint,
                            lineHeight = 19.sp,
                        )
                    }
                }
            } else {
                Card {
                    devices.forEachIndexed { index, device ->
                        if (index > 0) Hairline()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, fontSize = 15.sp)
                                    Text(
                                        device.describe() +
                                            (if (device.online) " · on the mesh" else " · not reachable"),
                                        fontSize = 12.sp,
                                        color = colors.faint,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                TextButton(onClick = {
                                    TrustedDevices.revoke(device.fingerprint)
                                    note = "Stopped trusting " + device.name + "."
                                    revision++
                                }) {
                                    Text("Stop", fontSize = 13.sp, color = Color(0xFFFF6B6B))
                                }
                            }
                            Text(
                                device.fingerprint,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6C6C78),
                            )
                            if (device.outgoing.isNotEmpty()) {
                                Text(
                                    "Sending: " + device.outgoing.joinToString(", ") { it.label },
                                    fontSize = 12.sp,
                                    color = colors.muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (device.incoming.isNotEmpty()) {
                                Text(
                                    "Receiving: " + device.incoming.joinToString(", ") { it.label },
                                    fontSize = 12.sp,
                                    color = colors.muted,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.Devices, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add a device", fontSize = 13.sp)
                }
                if (note.isNotBlank()) {
                    Spacer(Modifier.width(14.dp))
                    Text(note, fontSize = 12.sp, color = colors.faint)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (picking) {
        PeerPicker(
            existing = devices,
            onDismiss = { picking = false },
            onPick = { ip, name ->
                picking = false
                chosenPeer = ip to name
            },
        )
    }

    chosenPeer?.let { (ip, name) ->
        KindChooser(
            title = "Share with " + name + "?",
            detail = "Whatever you tick is shared BOTH WAYS from now on: this machine sends it to that " +
                "device and accepts the same from it. That device has to agree first, and it can accept " +
                "less than you offered — you are choosing what to put on the table, not what they take.",
            confirmLabel = "Offer",
            onDismiss = { chosenPeer = null },
            onConfirm = { kinds, _ ->
                val code = TrustedDevices.offer(ip, name, "unknown", kinds)
                if (code == null) {
                    note = "Could not reach " + name + " at " + ip + "."
                } else {
                    // SHOWN, NOT SENT. The other device asks for it, and typing it correctly is what
                    // proves whoever is accepting can see this screen. See PairingCode.
                    showingCode = code to name
                    note = ""
                }
                chosenPeer = null
                revision++
            },
        )
    }

    showingCode?.let { (code, name) ->
        PairingCodeDialog(code, name) { showingCode = null }
    }

    answering?.let { offer ->
        KindChooser(
            title = "Receive data from " + offer.name + "?",
            detail = "Only accept this if you recognise the device and it is yours. Anything you accept " +
                "is shared BOTH WAYS from then on — it arrives here, and this machine sends the same " +
                "back — until you stop trusting it here. It is encrypted with your recovery phrase, so " +
                "it only opens on a device that holds the same phrase; the peers that carry the packets " +
                "cannot read them.",
            confirmLabel = "Accept",
            offered = offer.kinds,
            onDismiss = {
                // Answered with nothing accepted rather than left pending: an offer that stayed pending
                // would be asked again on every launch, which teaches people to click through it.
                TrustedDevices.respondToOffer(offer.fingerprint, emptySet())
                answering = null
                revision++
            },
            needsCode = offer.needsCode,
            onConfirm = { kinds, typed ->
                val ok = TrustedDevices.respondToOffer(offer.fingerprint, kinds, typed)
                note = when {
                    kinds.isEmpty() -> "Declined " + offer.name + "."
                    ok -> "Paired with " + offer.name + "."
                    else -> "That code was not accepted. " + offer.name + " is still waiting — the " +
                        "code is on its screen."
                }
                // Left open on a wrong code, so it can be typed again rather than hunted for.
                if (ok || kinds.isEmpty()) answering = null
                revision++
            },
        )
    }
}

/**
 * The prompt that appears wherever the user happens to be. Step 3 of the pairing.
 *
 * Mounted once by the window rather than by a page, because the request was specific about when it is
 * asked: while the app is running, or when it opens. An offer that only appeared on the trusted-devices
 * page would need the user to already be looking for it, which is exactly the situation where they have
 * no idea a pairing is waiting.
 *
 * One at a time. Two devices offering at once is rare, and stacked modal dialogs are worse than asking
 * twice -- the second is shown as soon as the first is answered.
 */
@Composable
fun TrustedDeviceOfferWatcher() {
    var offer by remember { mutableStateOf<TrustedDevices.PendingOffer?>(null) }
    // Fingerprints answered in this session. respondToOffer clears the pending entry, but a far side that
    // re-sends before its reply lands would otherwise pop the dialog straight back up.
    val answered = remember { mutableSetOf<String>() }
    var codeError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            if (offer == null) {
                offer = TrustedDevices.pendingOffers().firstOrNull { it.fingerprint !in answered }
            }
            delay(2_000)
        }
    }

    offer?.let { pendingOffer ->
        KindChooser(
            title = "Receive data from " + pendingOffer.name + "?",
            detail = "From " + pendingOffer.platform.ifBlank { "an unknown device" } + " at " +
                pendingOffer.ip + ". Only accept this if you recognise the device and it is yours. " +
                "Anything you accept is shared both ways from then on -- it arrives here, and this " +
                "machine sends the same back -- until you stop trusting it in Trusted devices. It is " +
                "encrypted with your recovery phrase, so it only opens on a device holding the same one.",
            confirmLabel = "Accept",
            offered = pendingOffer.kinds,
            needsCode = pendingOffer.needsCode,
            onDismiss = {
                TrustedDevices.respondToOffer(pendingOffer.fingerprint, emptySet())
                answered.add(pendingOffer.fingerprint)
                offer = null
            },
            onConfirm = { kinds, typed ->
                if (TrustedDevices.respondToOffer(pendingOffer.fingerprint, kinds, typed)) {
                    answered.add(pendingOffer.fingerprint)
                    offer = null
                } else {
                    // Wrong code. The dialog stays up and says so -- closing it would leave somebody
                    // holding a code with nowhere to type it.
                    codeError = true
                }
            },
        )
    }
}

/** The device list, as a centred sheet.
 *
 * The iOS-style presentation the phone uses is a bottom sheet; a desktop's equivalent of that gesture is a
 * modal in the middle of the window, so the SHAPE differs and the behaviour -- a short list, one tap to
 * choose, dismissable by clicking away -- does not.
 */
@Composable
private fun PeerPicker(
    existing: List<TrustedDevices.Trust>,
    onDismiss: () -> Unit,
    onPick: (ip: String, name: String) -> Unit,
) {
    val colors = LocalPrismColors.current
    val paired = existing.map { it.lastIp }.toSet()
    // THE OVERLAY, NOT THE LOCAL NETWORK. MeshCore.peers() is everything broadcast discovery found,
    // which on a shared Wi-Fi includes devices that have nothing to do with this user's mesh.
    val peers = remember {
        com.prism.core.MeshMembership.overlayPeers(MeshCore.peers().keys).filterNot { it in paired }
    }
    val obstacle = remember { com.prism.core.MeshMembership.explain() }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = colors.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Devices on your meshnet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Devices on the Prism overlay that have answered a heartbeat. Others on this " +
                        "Wi-Fi are deliberately not listed.",
                    fontSize = 12.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(14.dp))

                if (obstacle.isNotBlank()) {
                    // THE REASON, NOT AN EMPTY LIST. "No devices found" to somebody looking straight at
                    // the other device is the worst way to communicate a policy.
                    Text(obstacle, fontSize = 13.sp, color = colors.muted, lineHeight = 19.sp)
                } else if (peers.isEmpty()) {
                    Text(
                        "Nobody else is on the meshnet yet. A device on this Wi-Fi is not enough — it " +
                            "has to be connected to the same Prism server, or to this one if it is " +
                            "serving. Broadcast being blocked can also hide a peer: a guest network and " +
                            "AP client isolation both do that silently.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                    )
                } else {
                    peers.forEach { ip ->
                        // The name comes from whatever the mesh already knows. A peer that has only sent
                        // heartbeats has an address and nothing else, and showing the address is honest --
                        // the real name arrives with the offer reply.
                        val name = TrustedDevices.byIp(ip)?.name ?: ip
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow { onPick(ip, name) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(name, fontSize = 14.sp)
                            Text(ip, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.faint)
                        }
                        Hairline()
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The code this device is showing while it waits to be paired with.
 *
 * Large and monospaced, because it is read off this screen and typed on another one a few feet away, and
 * the alphabet contains characters that look alike at small sizes.
 */
@Composable
private fun PairingCodeDialog(code: String, deviceName: String, onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type this on " + deviceName, fontSize = 17.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Surface(
                    color = Color(0xFF23232B),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                ) {
                    Text(
                        code,
                        fontSize = 34.sp,
                        letterSpacing = 4.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Nothing is shared until that device asks for this code and somebody types it " +
                        "correctly. It was never sent over the network, so answering it proves whoever " +
                        "is accepting can see this screen — which is the part an offer alone cannot " +
                        "prove.",
                    fontSize = 11.sp,
                    color = colors.faint,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = colors.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** The per-kind consent dialog, used for both directions. */
@Composable
private fun KindChooser(
    title: String,
    detail: String,
    confirmLabel: String,
    offered: Set<TrustedDevices.Kind> = TrustedDevices.Kind.entries.toSet(),
    needsCode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Set<TrustedDevices.Kind>, String?) -> Unit,
) {
    val colors = LocalPrismColors.current
    // Pre-ticked: the common case is agreeing to what was asked, and unticking is one click. Nothing is
    // recorded until the button is pressed, so the decision is still made by the person.
    val ticked = remember { mutableStateOf(offered) }
    var typedCode by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column {
                offered.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = kind in ticked.value,
                            onCheckedChange = { on ->
                                ticked.value = if (on) ticked.value + kind else ticked.value - kind
                            },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(kind.label, fontSize = 14.sp)
                            Text(kind.detail, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
                        }
                    }
                }
                if (needsCode) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The device offering this is showing a short code. Type it here.",
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = typedCode,
                        onValueChange = { typedCode = it; wrong = false },
                        singleLine = true,
                        isError = wrong,
                        placeholder = { Text("code", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (wrong) {
                            "That code was refused. Check the other device's screen and try again."
                        } else {
                            "It is not sent over the network by either device — typing it correctly is " +
                                "what proves you can see that screen."
                        },
                        fontSize = 11.sp,
                        color = if (wrong) Color(0xFFFF6B6B) else colors.faint,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(detail, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = ticked.value.isNotEmpty() && (!needsCode || typedCode.isNotBlank()),
                onClick = {
                    wrong = true
                    onConfirm(ticked.value, typedCode.takeIf { it.isNotBlank() })
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } },
        containerColor = colors.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
    )
}
