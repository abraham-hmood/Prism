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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CustomChain
import com.prism.launcher.wallet.CustomChainFactory
import com.prism.launcher.wallet.WalletArchive
import com.prism.launcher.wallet.WalletVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coins the user adds, chains they invent, the backup archive, and mining. PHASES 83, 84 and 85.
 *
 * ## Why these are one page rather than three
 *
 * They are the things somebody does ONCE, or rarely: add an unlisted coin, invent a chain, take a
 * backup, point mining at a pool. Putting each on its own rail entry would give the wallet four
 * entries, three of which are empty most of the time, and bury the one that shows money.
 *
 * ## The chain generator is not a toy, and the honest part of it
 *
 * `CustomChainFactory` derives genesis parameters, magic bytes, address version, ports and a reward
 * schedule DETERMINISTICALLY FROM THE NAME. Two people who type the same name get the same chain, and
 * that is the point: it means a chain can be recreated from one word rather than from a config file
 * somebody has to keep. What it does NOT do is run the chain — that needs a node, and the natural
 * home for one is a PC left on, which is what this build is.
 */
@Composable
fun WalletExtrasPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var addingCoin by remember { mutableStateOf(false) }
    var creatingChain by remember { mutableStateOf(false) }
    var chain by remember { mutableStateOf<CustomChain?>(null) }

    val hasWallet = remember(revision) { WalletVault.isInitialized() }
    val custom = remember(revision) { CoinRegistry.customCoins() }

    PageScaffold("Wallet extras", "Coins, chains, backup and mining") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!hasWallet) {
                Card {
                    Text(
                        "There is no wallet on this machine yet. Everything here needs one — a backup " +
                            "has nothing to back up, and a coin has no address to derive.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                return@Column
            }

            // ── PHASE 83 ───────────────────────────────────────────────────
            SectionHeader("coins you added")
            Card {
                if (custom.isEmpty()) {
                    Text(
                        "None. Prism knows the common coins already; this is for one it does not.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    custom.forEachIndexed { index, coin ->
                        if (index > 0) Hairline()
                        InfoRow(coin.name + " (" + coin.symbol + ")", "type " + coin.coinType)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedButton(onClick = { addingCoin = true }) { Text("Add a coin", fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { creatingChain = true }) {
                    Text("Create a chain", fontSize = 13.sp)
                }
            }
            SectionFooter(
                "An unlisted coin needs its SLIP-44 coin type and address scheme — get those wrong and " +
                    "the address derives to somebody else's coin, which is why Prism asks rather than " +
                    "guessing. A created chain derives everything from its name instead."
            )

            // ── PHASE 84 ───────────────────────────────────────────────────
            SectionHeader("backup")
            Card {
                NavRow(
                    "Write a backup archive",
                    "Phrase, coins, custom chains, mining settings and share counts, sealed with the " +
                        "phrase itself.",
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) { writeArchive() }
                            revision++
                        }
                    },
                )
                Hairline()
                NavRow(
                    "Restore from an archive",
                    "Reads a file written by this or by the phone. It asks for nothing else: the " +
                        "phrase inside is what opens it.",
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) { readArchive() }
                            revision++
                        }
                    },
                )
            }
            SectionFooter(
                "THE ARCHIVE IS SEALED WITH THE PHRASE IT CONTAINS, which sounds circular and is not: " +
                    "restoring asks you to type the phrase, and the file only opens if it matches. A " +
                    "copy of the file is therefore worth nothing on its own."
            )

            // ── PHASE 85 ───────────────────────────────────────────────────
            SectionHeader("mining")
            MiningSection(revision) { revision++ }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (addingCoin) {
        AddCoinDialog(
            onDismiss = { addingCoin = false },
            onAdd = { spec ->
                if (CoinRegistry.bySymbol(spec.symbol) != null) {
                    note = spec.symbol + " is already known."
                } else {
                    CoinRegistry.addCustom(spec)
                    note = spec.symbol + " added. Its address derives at " + spec.derivationPath() + "."
                }
                addingCoin = false
                revision++
            },
        )
    }

    if (creatingChain) {
        CreateChainDialog(
            onDismiss = { creatingChain = false },
            onCreate = { name ->
                chain = CustomChainFactory.create(name)
                creatingChain = false
            },
        )
    }

    chain?.let { created ->
        AlertDialog(
            onDismissRequest = { chain = null },
            title = { Text(created.name + " (" + created.symbol + ")", fontSize = 17.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Every parameter below was derived from the name alone, so anybody who types " +
                            "the same name gets the same chain. That is what lets a chain be recreated " +
                            "from one word instead of from a config file somebody has to keep.",
                        fontSize = 11.sp,
                        color = Color(0xFF83838F),
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        created.toConfig(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CoinRegistry.addCustom(created.toCoinSpec(CustomChainFactory.coinTypeFor(created.name)))
                    note = created.symbol + " added. Its node wants a machine that stays on — this one."
                    chain = null
                    revision++
                }) { Text("Add it to my coins") }
            },
            dismissButton = { TextButton(onClick = { chain = null }) { Text("Close") } },
        )
    }
}

// ── PHASE 85: mining ───────────────────────────────────────────────────────

/**
 * Mining settings, and what this machine can actually hash with.
 *
 * REPORTS THE ALGORITHMS RATHER THAN CLAIMING THEM. RandomX needs a native library; SHA-256 and
 * Scrypt are pure Kotlin in :core and work anywhere. A settings screen that offered Monero on a
 * machine with no RandomX would produce a miner that submits nothing and never says why.
 */
@Composable
private fun MiningSection(revision: Int, onChanged: () -> Unit) {
    val colors = LocalPrismColors.current
    var threads by remember(revision) { mutableStateOf(PrismSettings.getMiningThreads()) }

    Card {
        InfoRow(
            "Algorithms here",
            com.prism.launcher.wallet.MiningAlgorithms.supported.joinToString(", "),
        )
        Hairline()
        InfoRow(
            "RandomX",
            if (com.prism.launcher.wallet.MiningAlgorithms.supportsRandomX()) {
                "available"
            } else {
                "no native library on this machine"
            },
        )
        Hairline()
        Column(Modifier.padding(16.dp)) {
            Text("Threads", fontSize = 14.sp)
            Text(
                if (threads == 0) {
                    "Automatic — " + com.prism.core.PrismCpu.inferenceThreads() + " on this machine"
                } else {
                    threads.toString()
                },
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row {
                listOf(0, 1, 2, 4, 6, 8).forEach { count ->
                    val active = count == threads
                    androidx.compose.material3.Surface(
                        color = if (active) colors.accent else Color(0xFF23232B),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            if (count == 0) "auto" else count.toString(),
                            fontSize = 13.sp,
                            color = if (active) Color.White else Color(0xFFB9B9C4),
                            modifier = Modifier
                                .clickableRow {
                                    PrismSettings.setMiningThreads(count)
                                    threads = count
                                    onChanged()
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
    SectionFooter(
        "A desktop is the right place for this: no doze, no background limits, and a processor that " +
            "is not also a phone. Mining runs through the same pool and solo code the phone uses — " +
            "see the wallet's own mining settings for the pool, which are shared between platforms."
    )
}

// ── Dialogs ────────────────────────────────────────────────────────────────

@Composable
private fun AddCoinDialog(
    onDismiss: () -> Unit,
    onAdd: (com.prism.launcher.wallet.CoinSpec) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var coinType by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("8") }
    var shape by remember { mutableStateOf(0) }
    var parameter by remember { mutableStateOf("") }

    // The three shapes that exist. AddressScheme is a sealed class rather than an enum because two of
    // them CARRY A VALUE -- the version byte, or the bech32 prefix -- and those are exactly the
    // values that have to be right or the address derives to somebody else's coin.
    val shapes = listOf(
        "Legacy (P2PKH)" to "Version byte, e.g. 0 for Bitcoin, 48 for Litecoin",
        "Native SegWit" to "Bech32 prefix, e.g. bc or ltc",
        "Ethereum" to "Nothing else needed",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a coin Prism does not know", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name, { name = it }, singleLine = true,
                    placeholder = { Text("Name", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    symbol, { symbol = it }, singleLine = true,
                    placeholder = { Text("Symbol", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    coinType, { coinType = it }, singleLine = true,
                    placeholder = { Text("SLIP-44 coin type, e.g. 145", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    decimals, { decimals = it }, singleLine = true,
                    placeholder = { Text("Decimals", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text("Address shape", fontSize = 12.sp)
                Row(Modifier.padding(top = 4.dp)) {
                    shapes.forEachIndexed { index, (label, _) ->
                        val active = index == shape
                        androidx.compose.material3.Surface(
                            color = if (active) Color(0xFF33333D) else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Text(
                                label,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clickableRow { shape = index; parameter = "" }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                if (shape != 2) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        parameter, { parameter = it }, singleLine = true,
                        placeholder = { Text(shapes[shape].second, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "The coin type and the shape decide where in the phrase this coin's key lives, and " +
                        "SegWit uses a different derivation purpose from legacy (84 against 44). Wrong " +
                        "values produce a valid-looking address that is not yours, which is why Prism " +
                        "asks instead of guessing.",
                    fontSize = 11.sp,
                    color = Color(0xFF83838F),
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            val scheme = when (shape) {
                0 -> parameter.trim().toIntOrNull()?.let {
                    com.prism.launcher.wallet.AddressScheme.P2PKH(it)
                }
                1 -> parameter.trim().takeIf { it.isNotBlank() }?.let {
                    com.prism.launcher.wallet.AddressScheme.SegWitV0(it)
                }
                else -> com.prism.launcher.wallet.AddressScheme.Ethereum
            }
            TextButton(
                enabled = name.isNotBlank() && symbol.isNotBlank() &&
                    coinType.trim().toLongOrNull() != null &&
                    decimals.trim().toIntOrNull() != null && scheme != null,
                onClick = {
                    onAdd(
                        com.prism.launcher.wallet.CoinSpec(
                            symbol = symbol.trim().uppercase(),
                            name = name.trim(),
                            coinType = coinType.trim().toLong(),
                            scheme = scheme!!,
                            decimals = decimals.trim().toInt(),
                            isCustom = true,
                        )
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateChainDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a chain", fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true,
                    placeholder = { Text("A name — that is the whole input", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Genesis parameters, magic bytes, the address version, the ports and the reward " +
                        "schedule are all derived from the name. Nothing is random, so the same name " +
                        "always produces the same chain — which is what makes it recreatable.",
                    fontSize = 11.sp,
                    color = Color(0xFF83838F),
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) {
                Text("Derive it")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── PHASE 84's file handling ───────────────────────────────────────────────

/**
 * Writes a backup where the user picks.
 *
 * AWT's FileDialog rather than Swing's JFileChooser: it is the platform's own dialog, so it looks and
 * behaves like every other save box on the machine, and it is what a person expects when an
 * application asks them where to put a file.
 */
private fun writeArchive(): String {
    val phrase = WalletVault.phrase() ?: return "No phrase to back up."
    val payload = WalletArchive.Payload(
        phrase = phrase,
        enabledCoins = WalletVault.enabledSymbols(),
        customCoins = CoinRegistry.customCoins(),
        fiatCurrency = WalletVault.fiatCurrency(),
        shares = emptyMap(),
        miningMode = PrismSettings.getMiningMode(),
        miningDiscoveryUrl = PrismSettings.getMiningDiscoveryUrl(),
        miningThreads = PrismSettings.getMiningThreads(),
        soloNodeUrl = PrismSettings.getSoloNodeUrl(),
        selfHostNode = PrismSettings.getSelfHostNode(),
    )

    val target = chooseFile(save = true, suggestion = "prism-wallet.backup")
        ?: return "Cancelled."
    return runCatching {
        target.writeBytes(WalletArchive.pack(payload, phrase))
        "Written to " + target.absolutePath + ". It only opens with the phrase inside it."
    }.getOrElse { "Could not write it: " + it.message }
}

private fun readArchive(): String {
    val phrase = WalletVault.phrase()
        ?: return "Import or create a wallet first — the phrase is what opens the archive."
    val source = chooseFile(save = false, suggestion = "prism-wallet.backup") ?: return "Cancelled."

    return runCatching {
        when (val restore = WalletArchive.unpack(source.readBytes(), phrase)) {
            is WalletArchive.Restore.Restored -> {
                val payload = restore.payload
                payload.customCoins.forEach { CoinRegistry.addCustom(it) }
                PrismSettings.setMiningThreads(payload.miningThreads)
                "Restored " + payload.enabledCoins.size + " coin(s) and " +
                    payload.customCoins.size + " custom coin(s)."
            }

            // NAMED SEPARATELY, because they mean different things to the person holding the file: one
            // is the wrong wallet, the other is a damaged file, and the fix differs.
            WalletArchive.Restore.WrongPhrase ->
                "That archive belongs to a different wallet — this machine's phrase does not open it."

            is WalletArchive.Restore.Corrupt -> "The archive is damaged: " + restore.reason
        }
    }.getOrElse { "Could not read it: " + it.message }
}

private fun chooseFile(save: Boolean, suggestion: String): File? {
    val dialog = java.awt.FileDialog(
        null as java.awt.Frame?,
        if (save) "Save the wallet backup" else "Open a wallet backup",
        if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD,
    )
    dialog.file = suggestion
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}
