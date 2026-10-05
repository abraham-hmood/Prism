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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletNetwork
import com.prism.launcher.wallet.WalletVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The wallet, on desktop. PHASE 82.
 *
 * ## Nothing cryptographic happens here
 *
 * Every key, address, signature and transaction comes from `:core`, which is the same code the phone
 * runs and is covered by tests against published BIP-39/BIP-32/BIP-84/EIP-155 vectors. This file is a
 * view over it. That is worth saying because a wallet UI is exactly where somebody would be tempted to
 * re-derive an address "just for display" and get it subtly wrong.
 *
 * ## Two conventions kept from Android on purpose
 *
 * A SEGMENTED CONTROL rather than a tab strip, because that is what the phone uses and the two builds
 * should not drift into looking like different products.
 *
 * THE EXPORT-PRIVATE-KEY BUTTON IS NOT OPTIONAL. Prism's default phrase is 30 words, which no other
 * wallet software will import — so exporting a single coin's key is the ONLY route out of Prism for
 * somebody's money. A wallet you cannot leave is not a wallet, it is a trap.
 *
 * ## Why balances load per coin rather than all at once
 *
 * Each is a separate request to a public explorer, several are rate limited, and one that is slow or
 * down should not stop the others appearing. The list draws immediately with addresses and fills in.
 */
@Composable
fun WalletPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var initialised by remember { mutableStateOf(WalletVault.isInitialized()) }
    var openCoin by remember { mutableStateOf<CoinSpec?>(null) }
    var showPhrase by remember { mutableStateOf<List<String>?>(null) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    if (!initialised) {
        WalletSetup(
            onCreated = { phrase ->
                showPhrase = phrase
                initialised = true
            },
            onImport = { importing = true },
            status = status,
        )
        if (importing) {
            ImportDialog(
                onDismiss = { importing = false },
                onImport = { phrase ->
                    when (val outcome = WalletVault.import(phrase)) {
                        is WalletVault.Outcome.Created -> {
                            initialised = true
                            importing = false
                            status = ""
                        }

                        is WalletVault.Outcome.Failed -> status = outcome.reason
                    }
                },
            )
        }
        showPhrase?.let { PhraseDialog(it) { showPhrase = null } }
        return
    }

    val coin = openCoin
    if (coin != null) {
        CoinDetail(coin) { openCoin = null }
        return
    }

    WalletList(
        onOpenCoin = { openCoin = it },
        onShowPhrase = { showPhrase = WalletVault.phrase() },
        scope = scope,
    )
    showPhrase?.let { PhraseDialog(it) { showPhrase = null } }
}

// ── Setup ──────────────────────────────────────────────────────────────────

@Composable
private fun WalletSetup(onCreated: (List<String>) -> Unit, onImport: () -> Unit, status: String) {
    val colors = LocalPrismColors.current
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    PageScaffold("Wallet", "One phrase, every coin") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Card {
                Column(Modifier.padding(18.dp)) {
                    Text("No wallet on this machine yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A Prism wallet is one recovery phrase that every coin is derived from, so " +
                            "adding a coin years from now needs no new backup. The phrase is stored " +
                            "here behind " + keyStoreName() + ".",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Button(
                            enabled = !working,
                            onClick = {
                                working = true
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { WalletVault.create() }
                                    working = false
                                    when (outcome) {
                                        is WalletVault.Outcome.Created -> onCreated(outcome.phrase)
                                        is WalletVault.Outcome.Failed -> error = outcome.reason
                                    }
                                }
                            },
                        ) { Text("Create a wallet") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = onImport) { Text("I have a phrase") }
                    }
                    if (error.isNotBlank() || status.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            error.ifBlank { status },
                            fontSize = 12.sp,
                            color = Color(0xFFFF6B6B),
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            SectionFooter(
                "If one of your other devices already has a wallet, do not create a second one here — " +
                    "pair with it under Trusted devices instead and it will send you the same phrase. " +
                    "Two different phrases means two different identities, and neither can read what " +
                    "the other shares."
            )
        }
    }
}

private fun keyStoreName(): String = when {
    System.getProperty("os.name").orEmpty().lowercase().contains("win") ->
        "Windows' own DPAPI, which ties it to your sign-in"
    System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "the macOS login keychain"
    else -> "the system keyring"
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a recovery phrase", fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    placeholder = { Text("word word word …", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Standard 12, 15, 18, 21 and 24-word phrases are accepted, as well as Prism's own " +
                        "30-word one. The words are checked against the BIP-39 list and the checksum " +
                        "before anything is stored.",
                    fontSize = 11.sp,
                    color = Color(0xFF83838F),
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = phrase.isNotBlank(), onClick = { onImport(phrase) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PhraseDialog(phrase: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write these down", fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    "This is the only copy. Prism cannot recover it, and anybody who has it has your " +
                        "money.",
                    fontSize = 12.sp,
                    color = Color(0xFFFFB86B),
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                phrase.chunked(5).forEachIndexed { row, words ->
                    Text(
                        words.mapIndexed { index, word ->
                            "${row * 5 + index + 1}. $word"
                        }.joinToString("   "),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("I have written it down") } },
    )
}

// ── The coin list ──────────────────────────────────────────────────────────

@Composable
private fun WalletList(
    onOpenCoin: (CoinSpec) -> Unit,
    onShowPhrase: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = LocalPrismColors.current
    var tab by remember { mutableStateOf(0) }
    var balances by remember { mutableStateOf<Map<String, BigInteger>>(emptyMap()) }
    var prices by remember { mutableStateOf<Map<String, BigDecimal>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    val coins = remember(revision) {
        WalletVault.enabledSymbols().mapNotNull { CoinRegistry.bySymbol(it) }
    }
    val fiat = remember(revision) { WalletVault.fiatCurrency() }

    LaunchedEffect(revision) {
        loading = true
        // Prices in one request for every symbol; balances one coin at a time so a slow explorer
        // holds up only its own row.
        prices = withContext(Dispatchers.IO) {
            runCatching { WalletNetwork.prices(coins.map { it.symbol }, fiat) }.getOrDefault(emptyMap())
        }
        coins.forEach { coin ->
            val address = WalletVault.addressFor(coin) ?: return@forEach
            // observedBalance, not balance: this IS the user's wallet being refreshed, so an increase
            // here is a receipt worth announcing. PHASE 88. The other reader on this page -- the coin
            // detail's balanceInfo -- deliberately does not, or opening a coin would announce a payment
            // that the list had already reported.
            val amount = withContext(Dispatchers.IO) {
                runCatching { WalletNetwork.observedBalance(coin, address) }.getOrNull()
            }
            if (amount != null) balances = balances + (coin.symbol to amount)
        }
        loading = false
    }

    PageScaffold("Wallet", coins.size.toString() + " coins, one phrase") {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Coins", "Security"), tab) { tab = it }
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(
                    color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            IconButton(onClick = { balances = emptyMap(); revision++ }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                Card {
                    coins.forEachIndexed { index, coin ->
                        if (index > 0) Hairline()
                        CoinRow(
                            coin = coin,
                            amount = balances[coin.symbol],
                            price = prices[coin.symbol],
                            fiat = fiat,
                            onClick = { onOpenCoin(coin) },
                        )
                    }
                }
                SectionFooter(
                    "Balances come from public explorers, which means asking them about your address. " +
                        "That is how every light wallet works — the alternative is downloading each " +
                        "chain — but it is not nothing, and it is worth knowing."
                )
            } else {
                Card {
                    NavRow(
                        "Show the recovery phrase",
                        "The only copy. Anybody who reads it has your money.",
                        onClick = onShowPhrase,
                    )
                    Hairline()
                    InfoRow("Stored behind", keyStoreName())
                    Hairline()
                    InfoRow("Words", WalletVault.wordCount().toString())
                }
                SectionFooter(
                    "Prism's own phrase is 30 words rather than the usual 12 or 24. That is stronger, " +
                        "and it is also why the export-private-key button on each coin matters: no " +
                        "other wallet will import 30 words, so a single coin's key is the way out."
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CoinRow(
    coin: CoinSpec,
    amount: BigInteger?,
    price: BigDecimal?,
    fiat: String,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(coin.name + "  (" + coin.symbol + ")", fontSize = 14.sp)
            Text(
                WalletVault.addressFor(coin).orEmpty(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (amount == null) "…" else coin.format(amount),
                fontSize = 14.sp,
            )
            val value = amount?.let { WalletNetwork.toFiat(coin, it, price) }
            if (value != null) {
                Text(
                    fiat + " " + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
        }
    }
}

// ── One coin ───────────────────────────────────────────────────────────────

@Composable
private fun CoinDetail(coin: CoinSpec, onBack: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val address = remember(coin.symbol) { WalletVault.addressFor(coin).orEmpty() }

    var info by remember(coin.symbol) { mutableStateOf<WalletNetwork.BalanceInfo?>(null) }
    var toAddress by remember(coin.symbol) { mutableStateOf("") }
    var amountText by remember(coin.symbol) { mutableStateOf("") }
    var note by remember(coin.symbol) { mutableStateOf("") }
    var sending by remember(coin.symbol) { mutableStateOf(false) }
    var showKey by remember(coin.symbol) { mutableStateOf<String?>(null) }

    LaunchedEffect(coin.symbol) {
        info = withContext(Dispatchers.IO) {
            runCatching { WalletNetwork.balanceInfo(coin, address) }.getOrNull()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(coin.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    info?.let { coin.format(it.total) } ?: "…",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (info?.hasPending == true) {
                    Text(
                        coin.format(info!!.unconfirmed) + " still unconfirmed",
                        fontSize = 12.sp,
                        color = Color(0xFFFFB86B),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Your address", fontSize = 11.sp, color = colors.faint)
                Text(address, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(address), null,
                        )
                        note = "Address copied."
                    }
                }) { Text("Copy address", fontSize = 13.sp) }
            }
        }

        SectionHeader("send")
        Card {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = toAddress,
                    onValueChange = { toAddress = it; note = "" },
                    placeholder = { Text("Recipient " + coin.symbol + " address", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; note = "" },
                    placeholder = { Text("Amount in " + coin.symbol, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = !sending && toAddress.isNotBlank() && amountText.isNotBlank(),
                    onClick = {
                        val units = runCatching { coin.parseAmount(amountText.trim()) }.getOrNull()
                        if (units == null || units.signum() <= 0) {
                            note = "That is not an amount of " + coin.symbol + "."
                            return@Button
                        }
                        sending = true
                        note = "Signing and broadcasting…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val seed = WalletVault.seed()
                                val account = seed?.let { WalletVault.account(coin, it) }
                                if (account == null) {
                                    WalletNetwork.SendResult.Failed("No key for " + coin.symbol + ".")
                                } else {
                                    WalletNetwork.send(
                                        coin, account.address, account.privateKey,
                                        toAddress.trim(), units,
                                    )
                                }
                            }
                            sending = false
                            note = when (result) {
                                is WalletNetwork.SendResult.Broadcast ->
                                    "Broadcast. Transaction " + result.txId
                                is WalletNetwork.SendResult.Failed -> result.reason
                            }
                        }
                    },
                ) { Text(if (sending) "Sending…" else "Send") }

                if (note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(note, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
                }
            }
        }

        SectionHeader("getting out")
        Card {
            NavRow(
                "Export this coin's private key",
                "The only way to move " + coin.symbol + " into other wallet software, because " +
                    "nothing else imports a 30-word phrase.",
                onClick = {
                    showKey = runCatching {
                        WalletVault.seed()?.let { WalletVault.account(coin, it).exportPrivateKeyWif() }
                    }.getOrNull() ?: "Could not derive the key."
                },
            )
        }
        SectionFooter(
            "A wallet you cannot leave is not a wallet. The exported key controls this coin's " +
                "address and nothing else — the rest of the phrase stays here."
        )
        Spacer(Modifier.height(28.dp))
    }

    showKey?.let { key ->
        AlertDialog(
            onDismissRequest = { showKey = null },
            title = { Text(coin.symbol + " private key", fontSize = 17.sp) },
            text = {
                Column {
                    Text(
                        "Anybody with this controls this address. Paste it into the other wallet and " +
                            "then forget it.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFB86B),
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(key, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(key), null,
                        )
                    }
                    showKey = null
                }) { Text("Copy and close") }
            },
            dismissButton = { TextButton(onClick = { showKey = null }) { Text("Close") } },
        )
    }
}

/** The iOS-style segmented control the phone uses, so the two builds do not drift apart. */
@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalPrismColors.current
    Surface(color = Color(0xFF1E1E24), shape = RoundedCornerShape(9.dp)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Surface(
                    color = if (active) Color(0xFF33333D) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (active) colors.onSurface else colors.muted,
                        modifier = Modifier
                            .clickableRow { onSelect(index) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
