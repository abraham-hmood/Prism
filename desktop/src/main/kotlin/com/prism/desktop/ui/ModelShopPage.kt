package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.prism.launcher.ModelListingScanner
import com.prism.launcher.ModelListingStore
import com.prism.launcher.ModelPayments
import com.prism.launcher.ModelPurchaseLedger
import com.prism.launcher.ModelRefunds
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.messaging.ModelRegistry
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The peer-to-peer model market. PHASE 108.
 *
 * ## What this is NOT
 *
 * Not the model downloader. Fetching a GGUF from HuggingFace or GitHub is the Models page, and it is
 * free. This is the market with money in it: somebody sells a model they made, somebody else pays
 * PrismCoin for it, and both halves run on either platform.
 *
 * ## THE RULE THE WHOLE DESIGN HANGS ON
 *
 * You may only sell a model you made yourself. `ModelListingScanner` checks every listing against
 * GitHub and Hugging Face on an interval, and anything found publicly downloadable is WITHDRAWN and
 * the seller told why. That is enforced by the seller's own device, which sounds like it protects
 * nobody -- and the reason it works is that the BUYER checks too, before paying, and a listing that
 * fails the buyer's check is not bought. A seller who disabled their own scan would simply find that
 * nobody could complete a purchase.
 *
 * ## Coins are signed and HELD, not sent
 *
 * `ModelPayments.commit` signs a transaction and records it as HELD. It is broadcast only after the
 * listing passes a clean scan -- which is why the nonce is read at settlement rather than at signing,
 * and why a cancelled purchase costs the buyer nothing. Every number below comes from that ledger
 * rather than from a separate count, so a held purchase cannot be double-spent by the UI.
 *
 * ## Nothing here is desktop-specific
 *
 * The store, the ledger, the payments, the refunds and the scanner all moved to `:core` in this
 * phase -- every one of them had a `Context` that was a preferences handle and nothing else, and the
 * scanner had thirty lines of Android notification channel that became one `Notifier` call. So this
 * page is a renderer over the same objects the phone's shop view renders.
 */
@Composable
fun ModelShopPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var noteOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var listings by remember { mutableStateOf<List<P2pModelListings.Listing>>(emptyList()) }
    var mine by remember { mutableStateOf<List<ModelListingStore.Listed>>(emptyList()) }
    var purchases by remember { mutableStateOf<List<ModelPurchaseLedger.Purchase>>(emptyList()) }
    var balance by remember { mutableStateOf(BigInteger.ZERO) }
    var spendable by remember { mutableStateOf(BigInteger.ZERO) }
    var address by remember { mutableStateOf<String?>(null) }

    // Selling
    var sellPath by remember { mutableStateOf<File?>(null) }
    var sellPrice by remember { mutableStateOf("") }
    var sellKind by remember { mutableStateOf("text") }
    var sellParams by remember { mutableStateOf("") }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            listings = runCatching { P2pModelListings.getAll() }.getOrDefault(emptyList())
            mine = runCatching { ModelListingStore.all() }.getOrDefault(emptyList())
            purchases = runCatching { ModelPurchaseLedger.all() }.getOrDefault(emptyList())
            balance = runCatching { ModelPayments.balance() }.getOrDefault(BigInteger.ZERO)
            spendable = runCatching { ModelPayments.spendable() }.getOrDefault(BigInteger.ZERO)
            address = runCatching { ModelPayments.address() }.getOrNull()
        }
    }

    fun say(message: String, ok: Boolean = true) {
        note = message
        noteOk = ok
    }

    PageScaffold("Model market", "Buying and selling models between peers for PrismCoin") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = if (noteOk) Color(0xFF14301F) else Color(0xFF331A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (noteOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── Wallet ─────────────────────────────────────────────────────
            SectionHeader("your wallet")
            Card {
                Column(Modifier.padding(14.dp)) {
                    if (address == null) {
                        Text(
                            "No PrismCoin wallet on this device, so nothing can be bought or sold. " +
                                "Create one on the Wallet page — selling needs an address for the " +
                                "money to arrive at, and buying needs one for it to leave from.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                        )
                    } else {
                        Text(
                            psc(balance) + " PSC",
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            psc(spendable) + " spendable · " +
                                psc(balance.subtract(spendable)) + " held against purchases " +
                                "waiting for a clean scan",
                            fontSize = 11.sp,
                            color = colors.faint,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            address.orEmpty(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF6E6E7A),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (MeshTransport.isOnMesh()) Color(0xFF3FBF6F)
                            else Color(0xFF55555F),
                            shape = CircleShape,
                            modifier = Modifier.size(7.dp),
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (MeshTransport.isOnMesh()) {
                                MeshTransport.peerCount().toString() +
                                    " peer(s) — listings arrive and are announced over the mesh"
                            } else {
                                "Off the mesh. There is no server: a listing only exists while the " +
                                    "device holding the model is reachable."
                            },
                            fontSize = 10.sp,
                            color = colors.faint,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            // ── The rule ───────────────────────────────────────────────────
            SectionHeader("what may be sold")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "ONLY MODELS YOU MADE. Every listing is checked against GitHub and Hugging " +
                            "Face on an interval; anything already downloadable for free is " +
                            "withdrawn and the seller is told which model and what it matched.",
                        fontSize = 12.sp,
                        color = Color(0xFFE0C060),
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The seller's own device runs that scan, which protects nobody on its own " +
                            "— so the BUYER checks too, before paying. A listing that fails the " +
                            "buyer's check is not bought, which is what makes a seller who " +
                            "disabled their scan simply unable to sell.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val on = !ModelListingScanner.verifyOnPurchase()
                            ModelListingScanner.setVerifyOnPurchase(on)
                            revision++
                        }) {
                            Text(
                                "Check before paying: " +
                                    (if (ModelListingScanner.verifyOnPurchase()) "on" else "off"),
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "scanned every " + ModelListingScanner.intervalHours() + " h",
                            fontSize = 10.sp,
                            color = colors.faint,
                        )
                    }
                }
            }

            // ── For sale on the mesh ────────────────────────────────────────
            SectionHeader("for sale on the mesh")
            Card {
                Column {
                    if (listings.isEmpty()) {
                        Text(
                            "Nothing offered. Listings are announced by the devices holding the " +
                                "models, so this fills as peers come online.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    listings.forEachIndexed { index, listing ->
                        if (index > 0) Hairline()
                        val owned = purchases.any { it.listingId == listing.id }
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(listing.name, fontSize = 13.sp)
                                Text(
                                    listing.kind + " · " + listing.parameters + " · " +
                                        (listing.sizeBytes shr 20) + " MB · from " + listing.peerIp,
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                )
                            }
                            Text(
                                psc(listing.priceMinor) + " PSC",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.muted,
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                enabled = !busy && !owned && address != null &&
                                    spendable >= listing.priceMinor,
                                onClick = {
                                    busy = true
                                    say("Checking the listing, then signing…")
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ModelPayments.commit(listing)
                                        }
                                        busy = false
                                        revision++
                                        when (result) {
                                            is ModelPayments.Result.Ok -> say(
                                                "Signed and held. The coins go out when the " +
                                                    "listing passes a clean scan — nothing has " +
                                                    "left your wallet yet.",
                                            )
                                            is ModelPayments.Result.Failed ->
                                                say(result.reason, ok = false)
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    when {
                                        owned -> "Bought"
                                        address == null -> "No wallet"
                                        spendable < listing.priceMinor -> "Not enough"
                                        else -> "Buy"
                                    },
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── Purchases ──────────────────────────────────────────────────
            if (purchases.isNotEmpty()) {
                SectionHeader("your purchases")
                Card {
                    Column {
                        purchases.forEachIndexed { index, purchase ->
                            if (index > 0) Hairline()
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = when (purchase.state) {
                                        ModelPurchaseLedger.State.PAID -> Color(0xFF3FBF6F)
                                        ModelPurchaseLedger.State.HELD -> Color(0xFFE0C060)
                                        ModelPurchaseLedger.State.CANCELLED -> Color(0xFF55555F)
                                        ModelPurchaseLedger.State.REFUND_REQUESTED -> Color(0xFF6090E0)
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.size(8.dp),
                                ) {}
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(purchase.modelName, fontSize = 13.sp)
                                    Text(
                                        purchase.state.name.lowercase().replace('_', ' ') + " · " +
                                            psc(purchase.amountMinor) + " PSC",
                                        fontSize = 10.sp,
                                        color = colors.faint,
                                    )
                                    val remaining = ModelRefunds.windowRemainingMs(purchase)
                                    if (remaining > 0 &&
                                        purchase.state != ModelPurchaseLedger.State.CANCELLED
                                    ) {
                                        Text(
                                            "refundable for another " + (remaining / 60_000) + " min",
                                            fontSize = 10.sp,
                                            color = Color(0xFF6E6E7A),
                                        )
                                    }
                                }
                                if (ModelRefunds.windowRemainingMs(purchase) > 0 &&
                                    purchase.state != ModelPurchaseLedger.State.CANCELLED &&
                                    purchase.state != ModelPurchaseLedger.State.REFUND_REQUESTED
                                ) {
                                    Text(
                                        "refund",
                                        fontSize = 11.sp,
                                        color = colors.accent,
                                        modifier = Modifier
                                            .clickableRow {
                                                scope.launch {
                                                    val outcome = withContext(Dispatchers.IO) {
                                                        ModelRefunds.refund(purchase)
                                                    }
                                                    revision++
                                                    when (outcome) {
                                                        ModelRefunds.Outcome.Cancelled -> say(
                                                            "Cancelled before the coins left. " +
                                                                "The model has been deleted.",
                                                        )
                                                        ModelRefunds.Outcome.Requested -> say(
                                                            "The seller has been asked. Whether " +
                                                                "it pays is up to their device — " +
                                                                "the coins did leave.",
                                                        )
                                                        is ModelRefunds.Outcome.Failed ->
                                                            say(outcome.reason, ok = false)
                                                    }
                                                }
                                            }
                                            .padding(5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                SectionFooter(
                    "A HELD purchase has been signed and not sent: the nonce is read at settlement " +
                        "rather than at signing, which is what lets a purchase be cancelled for " +
                        "nothing. REFUND REQUESTED means the coins did move and may or may not " +
                        "come back — collapsing that into CANCELLED would tell a buyer their " +
                        "money was never sent when it was."
                )
            }

            // ── Selling ────────────────────────────────────────────────────
            SectionHeader("sell a model")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { sellPath = chooseModel() }) {
                            Text("Choose a model file", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            sellPath?.let { it.name + " · " + (it.length() shr 20) + " MB" }
                                ?: "a .gguf, .onnx or .safetensors you made",
                            fontSize = 11.sp,
                            color = colors.faint,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("text", "image", "video", "audio").forEach { kind ->
                            OutlinedButton(
                                onClick = { sellKind = kind },
                                modifier = Modifier.padding(end = 5.dp),
                            ) {
                                Text(
                                    kind,
                                    fontSize = 11.sp,
                                    color = if (kind == sellKind) colors.accent else colors.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = sellParams,
                            onValueChange = { sellParams = it },
                            placeholder = { Text("Size, e.g. 7B (Q4_K_M)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.width(220.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = sellPrice,
                            onValueChange = { sellPrice = it.filter { c -> c.isDigit() || c == '.' } },
                            placeholder = { Text("Price in PSC", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.width(150.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = sellPath != null && sellPrice.toBigDecimalOrNull() != null &&
                            (sellPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO &&
                            address != null,
                        onClick = {
                            val file = sellPath ?: return@Button
                            val price = sellPrice.toBigDecimalOrNull() ?: return@Button
                            // A ZERO PRICE IS REFUSED, not accepted as a giveaway: a free model
                            // belongs on the Models page's own sharing, and a zero-price listing
                            // would bypass the only-your-own-work check for no benefit.
                            val minor = price.multiply(BigDecimal(PrismCoinConsensus.ONE_PSC)).toBigInteger()
                            if (minor <= BigInteger.ZERO) {
                                say("A listing must have a price.", ok = false)
                                return@Button
                            }
                            // Registered as an installed model first, so it is listed, servable and
                            // reconcilable the same way any other model on this device is.
                            runCatching { ModelRegistry.install(file) }
                            ModelListingStore.add(
                                ModelListingStore.Listed(
                                    id = file.name + ":" + file.length(),
                                    name = file.nameWithoutExtension,
                                    kind = sellKind,
                                    parameters = sellParams.trim().ifBlank { "unspecified" },
                                    priceMinor = minor,
                                    sizeBytes = file.length(),
                                ),
                            )
                            ModelListingStore.announce()
                            sellPath = null
                            sellPrice = ""
                            sellParams = ""
                            revision++
                            say("Listed and announced to the mesh.")
                        },
                    ) { Text("List it") }
                }
            }

            if (mine.isNotEmpty()) {
                SectionHeader("your listings")
                Card {
                    Column {
                        mine.forEachIndexed { index, listed ->
                            if (index > 0) Hairline()
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(listed.name, fontSize = 13.sp)
                                    Text(
                                        listed.kind + " · " + listed.parameters + " · " +
                                            (listed.sizeBytes shr 20) + " MB · " +
                                            psc(listed.priceMinor) + " PSC",
                                        fontSize = 10.sp,
                                        color = colors.faint,
                                    )
                                }
                                Text(
                                    "withdraw",
                                    fontSize = 11.sp,
                                    color = Color(0xFFE08080),
                                    modifier = Modifier
                                        .clickableRow {
                                            ModelListingStore.remove(listed.id)
                                            ModelListingStore.announce()
                                            revision++
                                            say("Withdrawn and re-announced without it.")
                                        }
                                        .padding(5.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            // CHECKED ONE BY ONE, because isPubliclyAvailable answers per model
                            // name and a null means NEITHER HOST ANSWERED -- which is not the same
                            // as "clean". Treating a failed lookup as clean would leave a listing
                            // up that the next successful scan withdraws, and treating it as dirty
                            // would withdraw somebody's own work because GitHub was down.
                            val verdicts = withContext(Dispatchers.IO) {
                                ModelListingStore.all().map { listed ->
                                    listed to runCatching {
                                        ModelListingScanner.isPubliclyAvailable(listed.name)
                                    }.getOrNull()
                                }
                            }
                            var withdrawn = 0
                            var unknown = 0
                            verdicts.forEach { (listed, public) ->
                                when (public) {
                                    true -> {
                                        ModelListingStore.remove(listed.id)
                                        withdrawn++
                                    }
                                    null -> unknown++
                                    false -> Unit
                                }
                            }
                            if (withdrawn > 0) ModelListingStore.announce()
                            busy = false
                            revision++
                            say(
                                buildString {
                                    if (withdrawn > 0) {
                                        append(withdrawn).append(" listing(s) withdrawn — found ")
                                        append("publicly downloadable. ")
                                    }
                                    if (unknown > 0) {
                                        append(unknown)
                                        append(" could not be checked (neither GitHub nor Hugging ")
                                        append("Face answered) and were left alone. ")
                                    }
                                    if (withdrawn == 0 && unknown == 0) {
                                        append("Every listing is still clean.")
                                    }
                                },
                                ok = withdrawn == 0,
                            )
                        }
                    }) { Text(if (busy) "Scanning…" else "Scan my listings now", fontSize = 11.sp) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = {
                        ModelListingStore.announce()
                        say("Re-announced.")
                    }) { Text("Re-announce", fontSize = 11.sp) }
                }
            }

            SectionFooter(
                "The store, the ledger, the payments, the refunds and the scanner are all :core " +
                    "and shared — so a desktop lists a model, a phone buys it, and the coin moves " +
                    "through the same signed transaction either way."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * PrismCoin's smallest units as a human figure.
 *
 * `BigDecimal` rather than a double: a balance is money, and a double cannot hold eight decimal
 * places of it exactly. Trailing zeros are stripped so a round number does not read as 1.00000000.
 */
private fun psc(minor: BigInteger): String =
    BigDecimal(minor).divide(BigDecimal(PrismCoinConsensus.ONE_PSC)).stripTrailingZeros().toPlainString()

private fun chooseModel(): File? = runCatching {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = "Choose a model to sell"
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(file: File): Boolean = file.isDirectory ||
                file.extension.lowercase() in setOf("gguf", "onnx", "safetensors", "bin", "task")
            override fun getDescription(): String = "Model files"
        }
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}.getOrNull()
