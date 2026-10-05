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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshUtils
import com.prism.core.TunnelRouter
import com.prism.desktop.net.ProcessPorts
import com.prism.desktop.net.TunDevice
import com.prism.desktop.net.TunnelRuntime
import com.prism.desktop.net.VpnStack
import com.prism.desktop.net.WireGuardInterface
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tunnel, split tunnelling, and being a WireGuard server. PHASES 58, 59, 61, 63.
 *
 * ## Why these three are one page
 *
 * They are one mechanism seen from three sides. The adapter carries packets; the whitelist decides which
 * packets it is allowed to touch; the WireGuard interface is how another device gets onto the same
 * subnet the adapter routes. Separate pages would mean a user turning on a tunnel in one place, finding
 * a whitelist in another, and never discovering that the second explains what the first is doing.
 *
 * ## What this page refuses to pretend
 *
 * A tunnel that is up but unrouted is reported as up but unrouted, in those words, because an adapter
 * without routes carries nothing and a green light over it would be a lie of exactly the kind that
 * matters. Likewise a WireGuard interface with peers configured and no handshake is reported as nobody
 * connected -- a configured peer is an invitation, not a connection.
 *
 * ## Why the buttons can be pressed on a machine that cannot use them
 *
 * They are not disabled when the adapter is unavailable; pressing one produces the sentence explaining
 * what is missing. A greyed-out button with no explanation is the least helpful thing a privacy feature
 * can do, and what is missing here -- wintun.dll, or administrator rights -- is something the user can
 * go and fix.
 */
@Composable
fun TunnelPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(TunnelRuntime.status()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf(TunDevice.availability()) }

    var whitelist by remember { mutableStateOf(VpnStack.whitelist()) }
    var newEntry by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(listOf<String>()) }

    var peers by remember { mutableStateOf(WireGuardInterface.peers()) }
    var peerName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var wireGuardStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        endpoint = withContext(Dispatchers.IO) { runCatching { MeshUtils.getLocalMeshIp() }.getOrDefault("") }
        running = withContext(Dispatchers.IO) { VpnStack.runningApplications() }
        while (true) {
            status = TunnelRuntime.status()
            availability = withContext(Dispatchers.IO) { TunDevice.availability() }
            delay(1500)
        }
    }

    PageScaffold("Tunnel", "The mesh tunnel, split tunnelling, and WireGuard") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("the tunnel")
            Card {
                InfoRow("State", if (TunnelRuntime.running) "up" else "down")
                Hairline()
                InfoRow("Adapter", availability.mechanism)
                Hairline()
                InfoRow("Usable here", if (availability.usable) "yes" else "no")
                Hairline()
                InfoRow("Administrator", if (TunDevice.elevated()) "yes" else "no")
                Hairline()
                InfoRow("Address", TunnelRuntime.ADDRESS + " · DNS " + TunnelRuntime.DNS_PRIMARY)
                if (availability.missing.isNotEmpty()) {
                    Hairline()
                    InfoRow("Missing", availability.missing.joinToString("; "))
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && !TunnelRuntime.running,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { TunnelRuntime.start() }
                            message = result.message
                            busy = false
                        }
                    },
                ) { Text("Bring it up") }

                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !busy && TunnelRuntime.running,
                    onClick = {
                        busy = true
                        scope.launch {
                            message = withContext(Dispatchers.IO) { TunnelRuntime.stop() }.message
                            busy = false
                        }
                    },
                ) { Text("Take it down", fontSize = 13.sp) }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card {
                    Text(message, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(16.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Card {
                Text(
                    status,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.faint,
                    modifier = Modifier.padding(16.dp),
                )
            }
            SectionFooter(
                "Three routes go into the adapter: this machine's DNS, and the mesh subnet 10.8.0.0/24. " +
                    "NOT the default route -- ordinary traffic keeps using the ordinary connection and " +
                    "never enters the tunnel, so bringing this up cannot take the machine offline. What " +
                    "it protects is resolution: mesh names resolve, blocked names are refused before a " +
                    "connection is attempted, and mesh addresses reach the mesh."
            )

            SectionHeader("private tabs")
            Card {
                InfoRow(
                    "When a private tab opens",
                    if (PrismSettings.getVpnAutoStart()) "the tunnel starts" else "the tunnel stays down",
                )
                Hairline()
                InfoRow(
                    "When the last one closes",
                    if (PrismSettings.getVpnServerAlwaysOn()) "the tunnel stays up" else "the tunnel stops",
                )
                Hairline()
                InfoRow("Now", com.prism.desktop.browser.VpnBridge.describe())
            }
            SectionFooter(
                "Both of those are settings, not this page's to change -- they live in Prism Settings " +
                    "and are the same two the phone reads. A private tab gets isolated cookies and " +
                    "storage, ad blocking and DNT headers whether or not the tunnel can start."
            )

            SectionHeader("split tunnelling")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (whitelist.isEmpty()) {
                            "Nothing is bypassing the tunnel."
                        } else {
                            "" + whitelist.size + " program(s) bypass the tunnel entirely."
                        },
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    whitelist.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "remove",
                                fontSize = 12.sp,
                                color = colors.accent,
                                modifier = Modifier
                                    .clickableRow {
                                        VpnStack.removeFromWhitelist(entry)
                                        whitelist = VpnStack.whitelist()
                                        ProcessPorts.refreshWhitelist(whitelist)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newEntry,
                            onValueChange = { newEntry = it },
                            placeholder = { Text("an executable name, such as steam.exe", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = newEntry.isNotBlank(),
                            onClick = {
                                VpnStack.addToWhitelist(newEntry)
                                newEntry = ""
                                whitelist = VpnStack.whitelist()
                                ProcessPorts.refreshWhitelist(whitelist)
                            },
                        ) { Text("Add", fontSize = 13.sp) }
                    }
                }
            }
            SectionFooter(
                "A whitelisted program is found by asking the operating system which process owns the " +
                    "packet's source port, and its DNS then goes straight upstream -- unfiltered and " +
                    "with no mesh lookup, which is the whole point of a whitelist. A lookup that fails " +
                    "means the packet is tunnelled, never that it bypasses: a bypass granted by accident " +
                    "is a privacy hole, while a tunnel applied by accident is an inconvenience."
            )

            if (running.isNotEmpty()) {
                SectionHeader("running now")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        running.take(20).forEach { process ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(process, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                if (process.lowercase() !in whitelist.map { it.lowercase() }) {
                                    Text(
                                        "bypass",
                                        fontSize = 12.sp,
                                        color = colors.accent,
                                        modifier = Modifier
                                            .clickableRow {
                                                VpnStack.addToWhitelist(process)
                                                whitelist = VpnStack.whitelist()
                                                ProcessPorts.refreshWhitelist(whitelist)
                                            }
                                            .padding(vertical = 4.dp, horizontal = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                SectionFooter(
                    "This machine's own processes only. The usage-ranked suggestions the phone shows are " +
                        "deliberately absent: the desktop equivalent of UsageStatsManager would be a " +
                        "monitor watching what somebody uses all day, which is a surveillance feature " +
                        "wearing a convenience hat."
                )
            }

            SectionHeader("wireguard")
            Card {
                InfoRow("Interface", WireGuardInterface.INTERFACE)
                Hairline()
                InfoRow("Listening on", PrismSettings.getWgServerPort().toString())
                Hairline()
                InfoRow("This device's public key", PrismSettings.getWgServerPublicKey(), mono = true)
                Hairline()
                InfoRow("Peers", peers.size.toString())
                peers.forEach { peer ->
                    Hairline()
                    InfoRow("  " + peer.name, peer.address, mono = true)
                }
            }

            Spacer(Modifier.height(10.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = peerName,
                            onValueChange = { peerName = it },
                            placeholder = { Text("a name for the device", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            placeholder = { Text("this machine's address", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = peerName.isNotBlank() && endpoint.isNotBlank(),
                            onClick = {
                                val (config, peer) = WireGuardInterface.invite(peerName.trim(), endpoint.trim())
                                invitation = config
                                peers = WireGuardInterface.peers()
                                peerName = ""
                                message = "Invited " + peer.name + " at " + peer.address +
                                    ". Give it the config below; its private key is in that file and " +
                                    "nowhere else."
                            },
                        ) { Text("Invite a device") }

                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { WireGuardInterface.up() }
                                    message = result.message
                                    wireGuardStatus = withContext(Dispatchers.IO) { WireGuardInterface.status() }
                                }
                            },
                        ) { Text("Start the interface", fontSize = 13.sp) }

                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { WireGuardInterface.down() }.message
                                    wireGuardStatus = ""
                                }
                            },
                        ) { Text("Stop", fontSize = 13.sp) }
                    }
                }
            }

            if (invitation.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Save this as a .conf file on the other device. It contains that device's " +
                                "private key -- this machine keeps only its public one.",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            invitation,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (wireGuardStatus.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card {
                    Text(
                        wireGuardStatus,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            SectionFooter(
                "Prism writes the configuration and hands it to wg-quick rather than speaking WireGuard " +
                    "itself. That is not a shortcut: the protocol is Noise_IK with a handshake state " +
                    "machine, cookie replies, a nonce window and rekey timers, and a hand-rolled version " +
                    "that almost works is the worst outcome there is for a VPN. A peer with no handshake " +
                    "is reported as not connected, because a configured peer is an invitation."
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}
