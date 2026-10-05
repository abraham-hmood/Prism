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
import com.prism.core.MeshCore
import com.prism.core.MeshDns
import com.prism.core.MeshTransport
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.ComputePricing
import com.prism.launcher.mesh.DeviceProbe
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.MeshInference
import com.prism.launcher.mesh.MeshMarket
import com.prism.launcher.mesh.P2pCoinOffers
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.mesh.PayLaterPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The meshnet, seen from the desktop. PHASES 48, 50, 98.
 *
 * ## Why this page took until now
 *
 * The transport has worked on the desktop since Phase 48: `MeshCore` speaks the identical wire protocol
 * the phone's service does, so a desktop already appeared in a phone's peer list and answered its
 * gossip. What was missing was everything ABOVE the transport. The compute market, the model listings
 * and the coin offers all lived in `:app`, not because they were Android-specific -- between them they
 * had eleven Android imports, ten of which were `Context` for a data directory -- but because they
 * called `PrismMeshService` directly, and that class is in `:app`. A feature could be portable and still
 * be stuck.
 *
 * `MeshTransport` is the seam that freed them, and this page is what they were freed for.
 *
 * ## What each section is actually showing
 *
 * NOT A SUMMARY OF ONE THING. The mesh is four separate conversations that happen over one socket, and
 * collapsing them into a single "connected" light would hide the case that actually matters: a device
 * that is on the mesh, gossiping happily, and in nobody's compute market because it never announced.
 *
 * ## Why the market can be read but debts may not be settleable
 *
 * Settling a debt needs the Prism Coin node, which is still in `:app`. A desktop can therefore run
 * somebody else's inference, be paid for it, see what it is owed and see what it owes -- and not be able
 * to pay. That is stated on the page rather than hidden, because a debt that cannot be settled is a real
 * obligation and a device pretending otherwise would be lying to its peers.
 */
@Composable
fun MeshPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var onMesh by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf(listOf<String>()) }
    var market by remember { mutableStateOf(listOf<MeshComputeRegistry.Peer>()) }
    var listings by remember { mutableStateOf(listOf<P2pModelListings.Listing>()) }
    var offers by remember { mutableStateOf(listOf<P2pCoinOffers.Offer>()) }
    var records by remember { mutableStateOf(0) }
    var capacity by remember { mutableStateOf<DeviceProbe.Capacity?>(null) }
    var owed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        capacity = withContext(Dispatchers.IO) { runCatching { DeviceProbe.measure() }.getOrNull() }
        while (true) {
            onMesh = MeshTransport.isOnMesh()
            peers = MeshTransport.peers()
            market = MeshComputeRegistry.all()
            listings = P2pModelListings.getAll()
            offers = P2pCoinOffers.getAll()
            records = MeshDns.all().size
            owed = withContext(Dispatchers.IO) {
                runCatching { ComputePricing.format(ComputeDebtLedger.totalOwed()) }.getOrDefault("")
            }
            delay(2000)
        }
    }

    PageScaffold("Mesh", "This device as a peer, and what the mesh is offering") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("this device")
            Card {
                InfoRow("On the mesh", if (onMesh) "yes" else "no")
                Hairline()
                InfoRow("Address", MeshTransport.localIp(), mono = true)
                Hairline()
                InfoRow("Peers", peers.size.toString())
                Hairline()
                InfoRow("Listener", MeshCore.listenerHealth())
                Hairline()
                InfoRow("Port", PrismSettings.getMeshBootstrapPort())
                Hairline()
                // The registry is what makes an unhandled opcode legible. A feature that silently does
                // nothing looks identical to a feature that is absent without this.
                InfoRow(
                    "Opcodes handled",
                    MeshCore.registeredOpcodes().joinToString(" ") { "0x%02X".format(it) },
                    mono = true,
                )
            }
            SectionFooter(
                "The transport here is MeshCore, which speaks the same protocol as the phone's mesh " +
                    "service -- five bytes of \"PRISM\", one opcode, then UTF-8 on UDP. Two " +
                    "implementations of one format interoperate whether or not they share code, which " +
                    "is why the phone's was never rewritten to use this one."
            )

            SectionHeader("peers")
            Card {
                if (peers.isEmpty()) {
                    Text(
                        if (onMesh) {
                            "Nobody else is here yet. Peers are found by broadcast on the local " +
                                "network, so the other device has to be on the same one."
                        } else {
                            "The mesh is not running."
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    peers.forEachIndexed { index, ip ->
                        if (index > 0) Hairline()
                        val info = MeshCore.peers()[ip]
                        InfoRow(
                            ip,
                            buildString {
                                append("port ")
                                append(info?.port ?: 0)
                                info?.latency?.takeIf { it > 0 }?.let {
                                    append(" · ")
                                    append(it)
                                    append("ms")
                                }
                            },
                            mono = true,
                        )
                    }
                }
            }

            SectionHeader("compute market")
            Card {
                InfoRow("Registered", MeshMarket.describe())
                Hairline()
                InfoRow("Hosting compute", if (MeshInference.isHosting) "yes" else "no")
                Hairline()
                InfoRow("Tokens served", MeshInference.tokensServedSoFar().toString())
                capacity?.let {
                    Hairline()
                    InfoRow("This device offers", describeCapacity(it))
                    Hairline()
                    // The same argument order the registry uses for a peer, so this device's own
                    // price is computed exactly as every other peer computes it.
                    InfoRow(
                        "Which prices at",
                        ComputePricing.format(ComputePricing.perRequest(
                            ComputePricing.score(
                                it.ramTotalBytes, it.vramBytes, it.swapRamBytes, it.swapVramBytes,
                                it.cpuIndex, it.hasNpu, it.npuIndex,
                            )
                        )) + " per request",
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MeshComputeRegistry.announce() }
                        message = "Announced this device's capacity to the mesh."
                    }
                }) { Text("Announce capacity") }

                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MeshComputeRegistry.withdraw() }
                        message = "Withdrawn. Peers will drop this device when their copy expires."
                    }
                }) { Text("Withdraw", fontSize = 13.sp) }
            }

            if (market.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Card {
                    market.forEachIndexed { index, peer ->
                        if (index > 0) Hairline()
                        InfoRow(
                            peer.deviceName.ifBlank { peer.peerIp },
                            buildString {
                                append(peer.cpuCores)
                                append(" cores · ")
                                append(peer.ramTotalBytes / (1024 * 1024 * 1024))
                                append(" GB")
                                if (peer.hasNpu) append(" · NPU")
                                append(" · ")
                                append(ComputePricing.format(peer.pricePerRequest))
                                append("/req")
                            },
                        )
                    }
                }
            }
            SectionFooter(
                "NOBODY NAMES THEIR OWN PRICE. Every peer computes every other peer's price from the " +
                    "capability figures that peer gossiped, with the same function, so the list is " +
                    "comparable. A price a device set for itself would be a claim, and a market of " +
                    "claims needs reputation and escrow before it means anything."
            )

            SectionHeader("what is owed")
            Card {
                InfoRow("This device owes", owed.ifBlank { "nothing" })
                Hairline()
                InfoRow("Pay-later", if (PrismSettings.getPayLaterEnabled()) "on" else "off")
                Hairline()
                InfoRow("Status", PayLaterPolicy.status().ifBlank { "nothing outstanding" })
                Hairline()
                InfoRow("Settlement", com.prism.launcher.mesh.MeshBridges.describe())
            }
            SectionFooter(
                "Settling a debt needs the Prism Coin node, which is still part of the Android build. " +
                    "So this device can do a peer's work, be owed for it, and see exactly what it owes " +
                    "-- and not be able to pay yet. The debt is shown rather than hidden, because an " +
                    "obligation a device cannot meet is still an obligation."
            )

            SectionHeader("models on the mesh")
            Card {
                if (listings.isEmpty()) {
                    Text("No peer is offering a model.", fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                } else {
                    listings.forEachIndexed { index, listing ->
                        if (index > 0) Hairline()
                        InfoRow(listing.name, listing.peerIp, mono = true)
                    }
                }
            }

            SectionHeader("coin offers")
            Card {
                if (offers.isEmpty()) {
                    Text("No peer is offering a swap.", fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                } else {
                    offers.forEachIndexed { index, offer ->
                        if (index > 0) Hairline()
                        InfoRow(offer.fromSymbol + " for " + offer.toSymbol, offer.peerIp, mono = true)
                    }
                }
            }

            SectionHeader("names")
            Card {
                InfoRow("Records", records.toString())
                Hairline()
                InfoRow("Registered here", MeshDns.localRecords().size.toString())
                MeshDns.localRecords().take(8).forEach {
                    Hairline()
                    InfoRow("  " + it.domain, it.ip, mono = true)
                }
            }
            SectionFooter(
                "A name is a mesh name because this device holds a record for it, not because of its " +
                    "suffix. .p2p, .com, .gov -- the registry decides, which is what lets Prism shadow " +
                    "a public name on the mesh without asking anybody's permission."
            )

            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Text(message, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** One line for what a device can contribute. */
private fun describeCapacity(capacity: DeviceProbe.Capacity): String = buildString {
    append(capacity.cpuCores)
    append(" cores · ")
    append(capacity.ramTotalBytes / (1024 * 1024 * 1024))
    append(" GB RAM")
    if (capacity.vramBytes > 0) {
        append(" · ")
        append(capacity.vramBytes / (1024 * 1024 * 1024))
        append(" GB VRAM")
    }
    if (capacity.hasNpu) append(" · NPU")
}
