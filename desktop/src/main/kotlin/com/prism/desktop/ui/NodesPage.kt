package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.prism.core.MeshTransport
import com.prism.desktop.wallet.DesktopNodeHost
import com.prism.launcher.wallet.NodeConfig
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PrismCoinNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Hosting chains. PHASES 86 and 87.
 *
 * ## Two different things on one page, and they belong together
 *
 * PHASE 86 is a real blockchain node -- `bitcoind` or a fork -- run as a subprocess, which a desktop
 * can do and a phone cannot. PHASE 87 is PrismCoin's own node, which is Prism's chain and runs
 * everywhere. They sit together because the question a user has is one question: what is this machine
 * hosting for the mesh?
 *
 * ## Why a desktop PrismCoin node matters disproportionately
 *
 * The phase says it and it is worth repeating where somebody will read it: PrismCoin's security is
 * proportional to total hash rate, and its worst structural problem is that a mesh of phones
 * partitions constantly and therefore REORGS constantly. Desktops are better connected and stay
 * online, so they are what makes the chain converge at all.
 *
 * ## THE ELIGIBILITY RULE IS KEPT
 *
 * Only a device with the wallet page on a desktop slot participates. The phase was explicit: "Do not
 * make every desktop install a node silently." A chain node is bandwidth, disk and CPU, and a user
 * who never opened the wallet did not ask for any of it.
 */
@Composable
fun NodesPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var binaryDir by remember { mutableStateOf(DesktopNodeHost.searchDirectory()?.absolutePath ?: "") }

    var running by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tail by remember { mutableStateOf<List<String>>(emptyList()) }

    val available = remember(revision) { DesktopNodeHost.available() }

    // PrismCoin
    // Long, because a chain height is: PscChain.height() returns one and an Int would overflow
    // after about sixty-eight years of ten-second blocks. Cheap to get right now.
    var pscHeight by remember { mutableStateOf(0L) }
    var pscEligible by remember { mutableStateOf(false) }
    var pscDifficulty by remember { mutableStateOf(0.0) }
    var pscReward by remember { mutableStateOf(BigDecimal.ZERO) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            runCatching { PrismCoinNode.load() }
            pscEligible = runCatching { PrismCoinNode.isEligible() }.getOrDefault(false)
            pscHeight = runCatching { PrismCoinNode.chain.height() }.getOrDefault(0)
            runCatching {
                val target = PrismCoinNode.chain.targetForNext(PrismCoinNode.chain.tip)
                pscDifficulty = PrismCoinConsensus.difficultyOf(target)
                pscReward = BigDecimal(PrismCoinConsensus.blockReward(target))
                    .divide(BigDecimal(PrismCoinConsensus.ONE_PSC))
            }
        }
    }

    // A node syncing writes a line every few seconds for hours; 2 s is often enough to watch it
    // move and rare enough not to hammer the RPC.
    LaunchedEffect(revision) {
        while (isActive) {
            running = DesktopNodeHost.isRunning()
            info = if (running) withContext(Dispatchers.IO) { DesktopNodeHost.chainInfo() }
            else emptyMap()
            tail = DesktopNodeHost.log().takeLast(12)
            delay(2000)
        }
    }

    PageScaffold("Nodes", "A real blockchain node, and PrismCoin's own") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── What the phone cannot do ────────────────────────────────────
            SectionHeader("why this page exists on the desktop")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "On a phone this is blocked twice over: a node would have to be " +
                            "cross-compiled into the APK's library directory, and even then " +
                            "Android refuses to execute a file in an app's storage. The phone's " +
                            "node manager implements everything around a node and then reports " +
                            "that the binary is missing, because it is.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A PC has neither problem, so this machine can be the node the phones " +
                            "point at. Prism does not bundle the binaries — they are tens of " +
                            "megabytes per chain, they update on the chain's schedule, and the one " +
                            "you want is the one signed by the project rather than re-signed by us.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "PRUNING SAVES DISK, NOT TIME OR BANDWIDTH. A pruned node still downloads " +
                            "and validates the whole chain once and then discards the old blocks. " +
                            "For Bitcoin that is several hundred gigabytes and many hours before " +
                            "the few gigabytes on disk is all that is left.",
                        fontSize = 11.sp,
                        color = Color(0xFFE0C060),
                        lineHeight = 16.sp,
                    )
                }
            }

            // ── Where the binaries are ──────────────────────────────────────
            SectionHeader("node binaries")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = binaryDir,
                            onValueChange = { binaryDir = it },
                            placeholder = {
                                Text("An extra directory to look in", fontSize = 11.sp)
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            DesktopNodeHost.setSearchDirectory(binaryDir)
                            revision++
                            note = "Saved. PATH is searched first; this is for a node you built " +
                                "yourself somewhere PATH does not reach."
                        }) { Text("Save", fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (available.isEmpty()) {
                        Text(
                            "No node binary found. On Debian or Ubuntu that is " +
                                "`apt install bitcoind`; on Windows it is Bitcoin Core's own " +
                                "installer, whose bitcoind.exe lives in its daemon directory.",
                            fontSize = 12.sp,
                            color = Color(0xFFE0C060),
                            lineHeight = 18.sp,
                        )
                    } else {
                        available.forEach { (spec, binary) ->
                            val (enough, room) = remember(revision, spec.symbol) {
                                DesktopNodeHost.roomFor(spec.symbol)
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(spec.name + " (" + spec.symbol + ")", fontSize = 13.sp)
                                    Text(
                                        binary.absolutePath,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF6E6E7A),
                                    )
                                    Text(
                                        room + (if (spec.prunable) "" else
                                            " — this chain cannot prune, so it needs all of it"),
                                        fontSize = 10.sp,
                                        color = if (enough) colors.faint else Color(0xFFFFB4B4),
                                        lineHeight = 15.sp,
                                    )
                                }
                                Button(
                                    enabled = !busy && enough &&
                                        DesktopNodeHost.runningSymbol != spec.symbol,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            val problem = withContext(Dispatchers.IO) {
                                                DesktopNodeHost.start(spec.symbol)
                                            }
                                            busy = false
                                            revision++
                                            note = problem ?: (
                                                "Started. The initial sync is hours to days and " +
                                                    "pruning does not shorten it — leave it running."
                                                )
                                        }
                                    },
                                ) {
                                    Text(
                                        if (DesktopNodeHost.runningSymbol == spec.symbol) "Running"
                                        else "Start",
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── The running node ────────────────────────────────────────────
            if (running) {
                SectionHeader("syncing")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(DesktopNodeHost.describe(), fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        val blocks = info["blocks"]?.toIntOrNull() ?: 0
                        val headers = info["headers"]?.toIntOrNull() ?: 0
                        LinearProgressIndicator(
                            progress = {
                                if (headers <= 0) 0f else (blocks.toFloat() / headers).coerceIn(0f, 1f)
                            },
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(DesktopNodeHost.relayFor(), fontSize = 11.sp, color = colors.faint,
                            lineHeight = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                DesktopNodeHost.announce()
                                note = "Announced to " + MeshTransport.peerCount() + " peer(s)."
                            }) { Text("Announce to the mesh", fontSize = 11.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { DesktopNodeHost.stop() }
                                    busy = false
                                    revision++
                                    note = "Stopped. The chain on disk is kept."
                                }
                            }) { Text(if (busy) "Stopping…" else "Stop", fontSize = 11.sp) }
                        }
                    }
                }
                if (tail.isNotEmpty()) {
                    Card {
                        Column(Modifier.padding(12.dp).heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())) {
                            tail.forEach {
                                Text(
                                    it,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.faint,
                                    lineHeight = 13.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── PrismCoin ───────────────────────────────────────────────────
            SectionHeader("prismcoin")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (pscEligible) Color(0xFF3FBF6F) else Color(0xFF55555F),
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp),
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (pscEligible) {
                                "This device is a PrismCoin node — height " + pscHeight
                            } else {
                                "Not participating: the wallet page is not on a desktop slot"
                            },
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!pscEligible) {
                        Text(
                            "THE ELIGIBILITY RULE IS DELIBERATE. A chain node is bandwidth, disk " +
                                "and CPU, and a user who never opened the wallet did not ask for " +
                                "any of it. Put the wallet on a desktop slot and this machine " +
                                "joins — silently making every install a node would be the wrong " +
                                "default in the other direction.",
                            fontSize = 11.sp,
                            color = colors.faint,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            "difficulty " + String.format("%.4f", pscDifficulty) +
                                " · block reward " + pscReward.stripTrailingZeros().toPlainString() +
                                " PSC",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "A DESKTOP NODE MATTERS DISPROPORTIONATELY HERE. PrismCoin's security " +
                                "is proportional to total hash rate, and its worst structural " +
                                "problem is that a mesh of phones partitions constantly and " +
                                "therefore reorgs constantly. A machine that stays online and is " +
                                "well connected is what makes the chain converge at all.",
                            fontSize = 11.sp,
                            color = colors.faint,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { PrismCoinNode.announceHead() }
                                    }
                                    note = "Head announced. Peers behind this height will ask for " +
                                        "the blocks they are missing."
                                }
                            }) { Text("Announce the head", fontSize = 11.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { PrismCoinNode.save() } }
                                    revision++
                                    note = "Chain written to disk."
                                }
                            }) { Text("Save the chain", fontSize = 11.sp) }
                        }
                    }
                }
            }

            SectionFooter(
                "The node specification, the pruning decision, the config file and the credentials " +
                    "are all :core and shared with the phone — so the one thing that differs " +
                    "between the two platforms is whether the executable can be run at all, " +
                    "which is exactly what this phase was about."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
