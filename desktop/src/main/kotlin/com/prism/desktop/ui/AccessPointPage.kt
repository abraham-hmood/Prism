package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshCore
import com.prism.core.MeshDnsServer
import com.prism.desktop.net.Hotspot
import com.prism.desktop.net.NetworkControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The access point, the DNS it hands out, and who is allowed through it. PHASE 67, over 64/65/66.
 *
 * ## One page for three phases, on purpose
 *
 * On Android these are four activities — the portal, create, connected devices, stats — because a phone
 * navigates by screen. A desktop has room to show the whole thing at once, and the three questions
 * ("is it on", "what do clients resolve with", "who is blocked") are asked together in practice.
 *
 * ## What it refuses to pretend
 *
 * Every action here is a system command that can be refused for reasons the JVM cannot see: no
 * elevation, a group policy, a driver that dropped the feature. So each one reports what happened in
 * words, and the capability line at the top says up front whether this machine can do it at all.
 */
@Composable
fun AccessPointPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var capability by remember { mutableStateOf<Hotspot.Capability?>(null) }
    var state by remember { mutableStateOf<Hotspot.State?>(null) }
    var ssid by remember { mutableStateOf("Prism") }
    var password by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var blockAddress by remember { mutableStateOf("") }

    val blocked = remember(revision) { NetworkControl.blocked() }
    val interfaces = remember(revision) { NetworkControl.interfaces() }
    var dnsInterface by remember { mutableStateOf("") }

    LaunchedEffect(revision) {
        capability = withContext(Dispatchers.IO) { Hotspot.capability() }
        while (true) {
            state = withContext(Dispatchers.IO) { Hotspot.state() }
            delay(4_000)
        }
    }

    PageScaffold("Access point", "Share this machine's connection, and the mesh with it") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("this machine")
            Card {
                InfoRow("Mechanism", capability?.mechanism ?: "checking…")
                Hairline()
                InfoRow("Can start one", if (capability?.supported == true) "yes" else "no")
                Hairline()
                InfoRow("Running", if (state?.running == true) "yes" else "no")
                if (state?.running == true) {
                    Hairline()
                    InfoRow("Network name", state?.ssid.orEmpty())
                    Hairline()
                    InfoRow("Devices joined", state?.clients?.toString() ?: "0")
                }
            }
            capability?.detail?.let { SectionFooter(it) }

            // ── PHASE 64 ───────────────────────────────────────────────────
            SectionHeader("start one")
            Card {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        singleLine = true,
                        placeholder = { Text("Network name", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        placeholder = { Text("Password, at least 8 characters", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            enabled = capability?.supported == true && password.length >= 8,
                            onClick = {
                                scope.launch {
                                    note = withContext(Dispatchers.IO) {
                                        Hotspot.start(ssid.trim(), password) ?: "Started as " + ssid
                                    }
                                    revision++
                                }
                            },
                        ) { Text("Start") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = {
                            scope.launch {
                                note = withContext(Dispatchers.IO) { Hotspot.stop() ?: "Stopped." }
                                revision++
                            }
                        }) { Text("Stop") }
                    }
                }
            }
            SectionFooter(
                "Prism never asks for administrator rights on its own. If Windows or Linux refuses, " +
                    "the message says which command to run from an elevated prompt — a launcher that " +
                    "silently elevated itself would be a thing to be suspicious of."
            )

            // ── PHASE 65 ───────────────────────────────────────────────────
            SectionHeader("what clients resolve with")
            Card {
                InfoRow(
                    "Prism's resolver",
                    if (MeshDnsServer.isRunning) "127.0.0.1:" + MeshDnsServer.port else "not running",
                )
                Hairline()
                InfoRow("Answered from the mesh", MeshDnsServer.answeredLocally.toString())
                Hairline()
                InfoRow("Passed upstream", MeshDnsServer.forwarded.toString())
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dnsInterface,
                    onValueChange = { dnsInterface = it },
                    singleLine = true,
                    placeholder = { Text("Interface name", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    enabled = dnsInterface.isNotBlank(),
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) {
                                NetworkControl.setDns(dnsInterface.trim(), "127.0.0.1")
                                    ?: (dnsInterface + " now resolves through Prism.")
                            }
                        }
                    },
                ) { Text("Point at Prism", fontSize = 13.sp) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    enabled = dnsInterface.isNotBlank(),
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) {
                                NetworkControl.restoreDns(dnsInterface.trim())
                                    ?: (dnsInterface + " is back to automatic.")
                            }
                        }
                    },
                ) { Text("Undo", fontSize = 13.sp) }
            }
            if (interfaces.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card {
                    interfaces.take(8).forEachIndexed { index, item ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickableRow { dnsInterface = item.name }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(item.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(item.description, fontSize = 11.sp, color = colors.faint)
                        }
                    }
                }
            }
            SectionFooter(
                "Pointing an interface at 127.0.0.1 makes everything on it resolve mesh names as well " +
                    "as ordinary ones — Prism forwards anything it does not know, so nothing else " +
                    "breaks. Undo puts the interface back to whatever the network hands out."
            )

            // ── PHASE 66 ───────────────────────────────────────────────────
            SectionHeader("who is blocked")
            Card {
                if (blocked.isEmpty()) {
                    Text(
                        "Nobody. Blocking adds a firewall rule in both directions for that address.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    blocked.forEachIndexed { index, address ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                address,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "allow",
                                fontSize = 11.sp,
                                color = colors.accent,
                                modifier = Modifier.clickableRow {
                                    scope.launch {
                                        note = withContext(Dispatchers.IO) {
                                            NetworkControl.unblock(address) ?: (address + " allowed again.")
                                        }
                                        revision++
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = blockAddress,
                    onValueChange = { blockAddress = it },
                    singleLine = true,
                    placeholder = { Text("Address to block", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    enabled = blockAddress.isNotBlank(),
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) {
                                NetworkControl.block(blockAddress.trim())
                                    ?: (blockAddress + " blocked.")
                            }
                            blockAddress = ""
                            revision++
                        }
                    },
                ) { Text("Block", fontSize = 13.sp) }
            }

            // Peers, so an address can be picked rather than typed.
            val peers = remember(revision) { MeshCore.peers().keys.toList() }
            if (peers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card {
                    peers.forEachIndexed { index, ip ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickableRow { blockAddress = ip }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(
                                ip,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (ip in blocked) "blocked" else "on the mesh",
                                fontSize = 11.sp,
                                color = colors.faint,
                            )
                        }
                    }
                }
            }
            SectionFooter(
                "Bandwidth limiting is deliberately absent: both platforms can do it and neither does " +
                    "it the same way, and a limit that silently throttles the wrong traffic is worse " +
                    "than none. " + NetworkControl.limitBandwidth("", 0)
            )

            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
