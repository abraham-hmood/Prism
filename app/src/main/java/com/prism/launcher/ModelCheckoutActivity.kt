package com.prism.launcher

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletNetwork
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PrismCoinValuation
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Buying one model from one peer.
 *
 * Shows what is being bought, what it costs in PrismCoin, and what that is worth in real money --
 * the last of which is [PrismCoinValuation]'s TARGET value, one hour of average global labour. That
 * number is what PSC is *meant* to be worth, not what anyone has paid for one; the wallet page
 * makes the same distinction and for the same reason.
 */
class ModelCheckoutActivity : PrismBaseActivity() {

    companion object {
        const val EXTRA_ID = "listing_id"
        const val EXTRA_PEER = "listing_peer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        val listing = P2pModelListings.getAll()
            .firstOrNull { it.id == id && it.peerIp == peer }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@ModelCheckoutActivity))
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        if (listing == null) {
            // Listings are gossiped and expire; one can vanish between tapping and arriving here.
            root.addView(secondary(15f).apply {
                text = "That listing is no longer being offered.\n\n" +
                    "Listings live only as long as the seller keeps announcing them, so one can " +
                    "disappear if the peer goes offline."
            })
            setContentView(scrollHost(root))
            return
        }

        root.addView(balanceCard())
        root.addView(spacer(18))

        root.addView(label(22f).apply { text = listing.name })
        root.addView(TextView(this).apply {
            text = buildString {
                append(listing.kind.replaceFirstChar { it.uppercase() }).append(" model")
                if (listing.parameters.isNotBlank()) append(" · ").append(listing.parameters)
                if (listing.sizeBytes > 0) {
                    append(" · %.1f GB".format(listing.sizeBytes / (1024.0 * 1024.0 * 1024.0)))
                }
            }
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@ModelCheckoutActivity))
        })
        root.addView(spacer(16))

        val priceText = formatPsc(listing.priceMinor)
        root.addView(label(28f).apply { text = "$priceText PSC" })
        root.addView(secondary(13f).apply { text = indicativeFiat(listing.priceMinor) })
        root.addView(spacer(24))

        val status = secondary(12f).apply {
            text = "Your coins stay in your wallet until this listing passes its first GitHub " +
                "and Hugging Face check. If it fails, nothing is sent — there is no refund to " +
                "wait for because no payment was made."
        }

        // THE STATE IS THE SETTING, not a per-purchase toggle: whatever it is left on here is what
        // the next model opens with. Somebody who does not want to wait hours for a verdict does
        // not want to re-tick a box for every model they buy.
        val verifyNow = android.widget.CheckBox(this).apply {
            text = "Check GitHub and Hugging Face now"
            textSize = 14f
            setTextColor(IosUi.label(this@ModelCheckoutActivity))
            isChecked = ModelListingScanner.verifyOnPurchase(this@ModelCheckoutActivity)
            setOnCheckedChangeListener { _, checked ->
                ModelListingScanner.setVerifyOnPurchase(this@ModelCheckoutActivity, checked)
            }
        }
        root.addView(verifyNow)
        root.addView(TextView(this).apply {
            text = "Runs the check on both devices the moment you buy, instead of waiting up to " +
                ModelListingScanner.intervalHours(this@ModelCheckoutActivity) +
                " hours for the next sweep. Your side decides your payment; the seller's side is " +
                "asked to re-check its own listing, which it does against the real sources rather " +
                "than taking your word for it."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@ModelCheckoutActivity))
        })
        root.addView(spacer(12))

        // ALREADY OWNED MEANS DOWNLOAD, NOT BUY. A model bought on this device can be fetched
        // again for nothing -- the transfer can fail, the file can be deleted, the phone can be
        // replaced -- and a checkout that only ever offers to buy would charge for the same model
        // twice. The ledger is the record of ownership, not the presence of the file.
        val existing = ModelPurchaseLedger.find(this, listing.id)
        val owned = ModelPurchaseLedger.owns(this, listing.id)

        // A destructive-tinted iOS button: handing a model back is not the happy path, and it
        // should not look like the same affordance as buying.
        val refundButton = IosUi.tintedButton(this, "Refund", IosUi.destructive(this))

        val action = IosUi.filledButton(this, if (owned) "Download" else "Buy").apply {
            setOnClickListener {
                if (ModelPurchaseLedger.owns(this@ModelCheckoutActivity, listing.id)) {
                    isEnabled = false
                    status.text = "Downloading — you already own this model, so nothing is charged."
                    beginDownload(listing)
                    return@setOnClickListener
                }
                when (val result = ModelPayments.commit(this@ModelCheckoutActivity, listing)) {
                    is ModelPayments.Result.Ok -> {
                        isEnabled = false
                        text = "Purchased"
                        if (verifyNow.isChecked) {
                            status.text = "Committed " + formatPsc(listing.priceMinor) +
                                " PSC. Checking GitHub and Hugging Face now…"
                            runImmediateCheck(listing, status)
                        } else {
                            status.text = "Committed " + formatPsc(listing.priceMinor) + " PSC. " +
                                "Your coins stay put until the next check, which runs every " +
                                ModelListingScanner.intervalHours(this@ModelCheckoutActivity) +
                                " hours; if the listing fails, nothing is sent."
                        }
                        beginDownload(listing)
                        // ALWAYS TELL THE SELLER, whatever this buyer ticked. The seller has its
                        // own "check on every sale" setting, and it cannot act on a sale it never
                        // hears about -- so the notification is part of buying, not part of the
                        // buyer's preference. It is one small packet, and a seller not using the
                        // setting simply checks and says nothing.
                        Thread({
                            ModelListingScanner.requestSellerCheck(listing)
                        }, "notify-seller").start()
                        // The window opens at purchase, so the button has to appear now rather
                        // than on the next visit to this screen.
                        showRefund(refundButton, status, listing)
                    }
                    is ModelPayments.Result.Failed ->
                        status.text = result.reason
                }
            }
        }
        root.addView(action)
        root.addView(refundButton)
        root.addView(status)

        if (owned) {
            verifyNow.isVisible = false
            status.text = "You own this model. Downloading it again is free."
        }
        if (existing != null) showRefund(refundButton, status, listing)
        transferStatus = secondary(12f)
        root.addView(transferStatus)

        setContentView(scrollHost(root))
    }

    private fun scrollHost(content: LinearLayout) = ScrollView(this).apply {
        setBackgroundColor(IosUi.groupedBackground(this@ModelCheckoutActivity))
        addView(content)
    }

    /**
     * Primary text, in the system label colour.
     *
     * Set explicitly rather than inherited. Every other iOS-styled screen in Prism paints its own
     * label colours, and a screen relying on the theme default sits at a visibly different weight
     * beside them -- most obviously in dark mode, where the two are not the same grey.
     */
    private fun label(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(IosUi.label(this@ModelCheckoutActivity))
    }

    /** Secondary text. Replaces alpha = 0.7f, which is a different colour from iOS's. */
    private fun secondary(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(IosUi.secondaryLabel(this@ModelCheckoutActivity))
    }

    /** Your PrismCoin, and a way to top it up. */
    private fun balanceCard(): LinearLayout {
        val card = IosUi.card(this)
        // The wallet owns key derivation; the address comes from there rather than being stored
        // separately, so a checkout can never quote a balance for an address the wallet would not
        // actually spend from.
        val address = runCatching {
            CoinRegistry.bySymbol("PSC")?.let { WalletVault.addressFor(it) }
        }.getOrNull()
        // Spendable, not raw: coins already committed to purchases awaiting their first check
        // are not available again, and showing the gross figure would invite double-committing.
        val balance = ModelPayments.spendable(this)

        card.addView(secondary(12f).apply { text = "Your PrismCoin" })
        card.addView(label(20f).apply { text = formatPsc(balance) + " PSC" })
        card.addView(secondary(12f).apply { text = indicativeFiat(balance) })
        card.addView(IosUi.tintedButton(this, "Receive").apply {
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@ModelCheckoutActivity, WalletReceiveShim::class.java)
                )
            }
        })
        if (address.isNullOrBlank()) {
            card.addView(secondary(11f).apply {
                text = "No PrismCoin address yet — PrismCoin only runs on devices with the " +
                    "wallet page on their desktop."
            })
        }
        return card
    }

    /**
     * The peg, shown in the user's own currency.
     *
     * Labelled indicative on purpose. Consensus pegs what it costs to MINT a PSC, not what one
     * trades for, and PSC has no exchange -- so this is what the coin is meant to be worth, not a
     * quote anybody has honoured.
     */
    private fun indicativeFiat(minor: BigInteger): String {
        val currency = runCatching {
            java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
        }.getOrDefault("USD")
        val targetUsd = PrismCoinValuation.targetValueUsd()
        val rate = runCatching { WalletNetwork.usdRateFor(currency) }.getOrNull()
        val shown = if (rate != null) currency else "USD"
        val coins = BigDecimal(minor).divide(BigDecimal(PrismCoinConsensus.ONE_PSC))
        val fiat = coins.toDouble() * targetUsd * (rate ?: 1.0)
        return "About " + PrismCoinValuation.formatMoney(fiat, shown) +
            " at the target rate of one hour's average global wage per PSC"
    }

    /**
     * Records that the transfer should begin.
     *
     * Starts on PURCHASE rather than on settlement. The buyer's coins stay put until the listing
     * clears its first check, so the seller is the party extending trust -- and making the buyer
     * wait hours for a file they have already committed to would be the worse trade.
     */
    private var transferStatus: TextView? = null

    /**
     * Runs the listing's check on both devices straight away.
     *
     * OFF THE MAIN THREAD, because both halves make blocking HTTP calls -- two lookups here and a
     * mesh packet to the seller -- and doing that on the UI thread would freeze the checkout at
     * exactly the moment the user is watching it hardest.
     *
     * The two halves are independent on purpose. This device's verdict comes from its OWN lookups
     * and settles or cancels its own payment; the seller is merely asked to re-check, and its
     * answer is never waited on. A buyer whose payment depended on the seller's self-report would
     * be trusting the one party with a reason to lie.
     */
    /**
     * Shows the refund button while the three-hour window is open.
     *
     * HIDDEN RATHER THAN DISABLED once the window closes, because a permanently greyed button
     * invites people to keep pressing it and says nothing about why. The countdown is on the label
     * for the same reason -- a refund window nobody can see the end of is one people discover has
     * closed only when they need it.
     */
    private fun showRefund(
        // TextView, not Button: IosUi's buttons are styled TextViews, which is what keeps them
        // looking like iOS controls rather than Material ones.
        button: TextView,
        status: TextView,
        listing: com.prism.launcher.mesh.P2pModelListings.Listing,
    ) {
        val purchase = ModelPurchaseLedger.find(this, listing.id)
        if (purchase == null || !ModelRefunds.isRefundable(purchase)) {
            button.isVisible = false
            return
        }

        button.isVisible = true
        button.text = "Refund (" + ModelRefunds.describeRemaining(purchase) + ")"
        button.setOnClickListener {
            PrismDialogFactory.show(
                this,
                "Refund this model?",
                "The model will be deleted from this device. " +
                    if (purchase.state == ModelPurchaseLedger.State.HELD) {
                        "Your coins have not been sent yet, so this simply releases them."
                    } else {
                        "Your coins have already gone to the seller, so this asks their device " +
                            "to send them back. Prism cannot reverse a payment on its own — the " +
                            "seller's device checks the sale against the chain and refunds it, " +
                            "which needs them online."
                    },
                positiveText = "Refund",
                onPositive = {
                    button.isEnabled = false
                    Thread({
                        val outcome = ModelRefunds.refund(applicationContext, purchase)
                        runOnUiThread {
                            when (outcome) {
                                is ModelRefunds.Outcome.Cancelled -> {
                                    status.text = "Refunded. The coins were never sent, so your " +
                                        "balance is already back to what it was, and the model " +
                                        "has been deleted."
                                    button.isVisible = false
                                }
                                is ModelRefunds.Outcome.Requested -> {
                                    status.text = "The model has been deleted and the seller has " +
                                        "been asked to return " +
                                        formatPsc(purchase.amountMinor) + " PSC. It arrives when " +
                                        "their device verifies the sale; it cannot be forced."
                                    button.isVisible = false
                                }
                                is ModelRefunds.Outcome.Failed -> {
                                    status.text = outcome.reason
                                    button.isEnabled = true
                                }
                            }
                        }
                    }, "model-refund").start()
                },
            )
        }
    }

    private fun runImmediateCheck(
        listing: com.prism.launcher.mesh.P2pModelListings.Listing,
        status: TextView,
    ) {
        Thread({
            val purchase = ModelPurchaseLedger.held(applicationContext)
                .firstOrNull { it.listingId == listing.id }
            val outcome = if (purchase == null) {
                // Committed but not held means it already settled or was cancelled elsewhere.
                ModelListingScanner.Immediate.Undecided(
                    "This purchase is no longer awaiting a check."
                )
            } else {
                ModelListingScanner.verifyPurchaseNow(applicationContext, purchase)
            }

            runOnUiThread {
                status.text = when (outcome) {
                    is ModelListingScanner.Immediate.Settled ->
                        "Checked: this listing is not a repackaged public download, so " +
                            formatPsc(listing.priceMinor) + " PSC has been sent."
                    is ModelListingScanner.Immediate.Cancelled ->
                        "Checked: this model is freely available on GitHub or Hugging Face. The " +
                            "purchase was cancelled and no coins were sent."
                    is ModelListingScanner.Immediate.Undecided -> outcome.reason
                }
            }
        }, "model-verify-now").start()
    }

    private fun beginDownload(listing: com.prism.launcher.mesh.P2pModelListings.Listing) {
        // Transfer rides the same peer channel model hosting already uses; the shop only says
        // which peer and which model.
        Thread({
            val file = com.prism.launcher.mesh.P2pModelTransfer.download(
                applicationContext, listing
            ) { done, total ->
                // The notification is the real progress display -- a transfer runs for minutes and
                // nobody keeps this screen open for it. The label here is only for whoever stayed.
                ModelDownloadNotifier.onProgress(applicationContext, listing.name, done, total)
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                runOnUiThread { transferStatus?.text = "Downloading ${listing.name}… $pct%" }
            }
            val failure = "The seller may be offline. The purchase stays committed and no coins " +
                "have been sent."
            if (file != null) {
                ModelDownloadNotifier.onComplete(applicationContext, listing.name)
            } else {
                ModelDownloadNotifier.onFailed(applicationContext, listing.name, failure)
            }
            runOnUiThread {
                transferStatus?.text = if (file != null) {
                    "Downloaded ${listing.name} (${file.length() / (1024 * 1024)} MB)."
                } else {
                    "Download failed. $failure"
                }
            }
        }, "model-transfer").start()
    }

    private fun formatPsc(minor: BigInteger): String =
        BigDecimal(minor)
            .divide(BigDecimal(PrismCoinConsensus.ONE_PSC))
            .stripTrailingZeros()
            .toPlainString()

    private fun spacer(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/** Placeholder target for the Receive button until it points at the wallet's own receive sheet. */
class WalletReceiveShim : PrismBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Open the Wallet page to receive PrismCoin."
            textSize = 16f
            gravity = Gravity.CENTER
        })
    }
}
