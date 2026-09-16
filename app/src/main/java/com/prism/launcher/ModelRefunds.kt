package com.prism.launcher

import android.content.Context
import com.prism.core.json.JSONObject
import com.prism.launcher.mesh.P2pModelTransfer
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import java.math.BigInteger

/**
 * Returning a model, within a window, and getting the coins back.
 *
 * ## Two genuinely different refunds
 *
 * A purchase that is still HELD has sent nothing -- the transaction is signed and sitting in the
 * ledger waiting for the listing's first clean scan. Refunding it is purely local: cancel it, and
 * the committed coins are spendable again. This always works and cannot fail.
 *
 * A purchase that is PAID has moved coins to the seller, and no amount of buyer-side code brings
 * them back. There is no escrow to release and no chain rule that lets a payer reverse a payment;
 * that is what "already paid" means. So this asks the SELLER's device to send them back, and the
 * seller's device decides. The UI says so rather than showing a button that implies a guarantee
 * nobody can honour.
 *
 * ## Why the seller's side can be automatic without being exploitable
 *
 * A refund request carries no proof and is believed about nothing. The seller verifies the payment
 * against its own copy of the chain -- a transaction from that buyer, to itself, for that amount,
 * for that model, inside the window -- and refuses otherwise. It also refuses to refund the same
 * purchase twice by looking for its own outgoing refund. Every fact it acts on is one it can see
 * for itself, so the worst a forged request can do is make a device read its own chain.
 */
object ModelRefunds {

    /** Buyer to seller: I am returning this model, please send the coins back. */
    const val OPCODE_REFUND_REQUEST: Byte = 0x14

    /** How long after purchase a refund may be asked for. */
    const val WINDOW_MS = 3L * 60L * 60L * 1000L

    /** Marks a refund so the seller can recognise its own, and refuse to pay it twice. */
    private const val REFUND_NOTE_PREFIX = "Refund: "

    /**
     * How many blocks back to look for the payment.
     *
     * The window is three hours and blocks target two minutes, so ninety blocks covers it. Four
     * times that absorbs a stretch of slow blocks without ever being an unbounded scan.
     */
    private const val SCAN_DEPTH = 360

    sealed class Outcome {
        /** The coins never left; the purchase is cancelled and the file is gone. */
        data object Cancelled : Outcome()

        /** The seller has been asked. Whether it pays is up to the seller's device. */
        data object Requested : Outcome()

        data class Failed(val reason: String) : Outcome()
    }

    fun windowRemainingMs(purchase: ModelPurchaseLedger.Purchase): Long =
        (purchase.committedAt + WINDOW_MS) - System.currentTimeMillis()

    fun isRefundable(purchase: ModelPurchaseLedger.Purchase): Boolean =
        windowRemainingMs(purchase) > 0 &&
            (purchase.state == ModelPurchaseLedger.State.HELD ||
                purchase.state == ModelPurchaseLedger.State.PAID)

    /** "2h 14m left", for a button that has to show the window closing. */
    fun describeRemaining(purchase: ModelPurchaseLedger.Purchase): String {
        val ms = windowRemainingMs(purchase)
        if (ms <= 0) return "expired"
        val minutes = ms / 60_000L
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
    }

    /**
     * Refunds a purchase and removes the model from this device.
     *
     * THE FILE GOES EITHER WAY, including when the seller may never pay. Keeping a model that has
     * been handed back is the one outcome nobody should be able to choose: it would make the refund
     * button a way to acquire models for nothing, which is exactly the abuse that would get the
     * whole mechanism removed. Asking for the refund is the decision to give the model up.
     */
    fun refund(context: Context, purchase: ModelPurchaseLedger.Purchase): Outcome {
        if (windowRemainingMs(purchase) <= 0) {
            return Outcome.Failed("The three-hour refund window for this model has closed.")
        }

        return when (purchase.state) {
            ModelPurchaseLedger.State.HELD -> {
                ModelPurchaseLedger.update(
                    context, purchase.listingId, ModelPurchaseLedger.State.CANCELLED
                )
                deleteModel(context, purchase.modelName)
                PrismLogger.logInfo(
                    "ModelShop",
                    "Refunded '${purchase.modelName}' before settlement; no coins had been sent."
                )
                Outcome.Cancelled
            }

            ModelPurchaseLedger.State.PAID -> {
                val buyer = ModelPayments.address()
                    ?: return Outcome.Failed("No PrismCoin address on this device to refund to.")
                val peer = sellerPeerFor(purchase)
                    ?: return Outcome.Failed(
                        "The seller is not reachable on the mesh right now. Try again while they " +
                            "are online -- the window is still open until " +
                            describeRemaining(purchase) + "."
                    )

                runCatching {
                    PrismMeshService.sendToPeer(
                        peer, OPCODE_REFUND_REQUEST,
                        JSONObject().apply {
                            put("listing", purchase.listingId)
                            put("model", purchase.modelName)
                            put("buyer", buyer)
                            put("amount", purchase.amountMinor.toString())
                        }.toString()
                    )
                }.onFailure { return Outcome.Failed("Could not reach the seller: ${it.message}") }

                ModelPurchaseLedger.update(
                    context, purchase.listingId, ModelPurchaseLedger.State.REFUND_REQUESTED
                )
                deleteModel(context, purchase.modelName)
                Outcome.Requested
            }

            else -> Outcome.Failed("This purchase is not in a state that can be refunded.")
        }
    }

    /** The listing this purchase came from is the only record of which peer sold it. */
    private fun sellerPeerFor(purchase: ModelPurchaseLedger.Purchase): String? =
        com.prism.launcher.mesh.P2pModelListings.getAll()
            .firstOrNull { it.id == purchase.listingId }?.peerIp

    private fun deleteModel(context: Context, modelName: String) {
        runCatching {
            val file = P2pModelTransfer.destinationFor(context, modelName)
            if (file.exists() && !file.delete()) {
                PrismLogger.logWarning(
                    "ModelShop", "Refunded '$modelName' but could not delete ${file.absolutePath}"
                )
            }
        }
    }

    // ── Seller side ────────────────────────────────────────────────────────

    /**
     * A buyer is returning a model. Verify the sale on the chain, then send the coins back.
     *
     * Blocking; the mesh service calls it off its own thread.
     */
    fun onRefundRequest(context: Context, payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val model = json.optString("model")
        val buyer = json.optString("buyer")
        val amount = runCatching { BigInteger(json.optString("amount", "0")) }
            .getOrDefault(BigInteger.ZERO)
        if (model.isBlank() || buyer.isBlank() || amount.signum() <= 0) return

        val coin = CoinRegistry.bySymbol("PSC") ?: return
        val seed = WalletVault.seed() ?: return
        val account = runCatching { WalletVault.account(coin, seed) }.getOrNull() ?: return
        val chain = PrismCoinNode.chain

        val cutoff = (System.currentTimeMillis() - WINDOW_MS) / 1000L
        var paid = false
        var alreadyRefunded = false
        for (block in chain.chainFromTip(SCAN_DEPTH)) {
            if (block.timestamp < cutoff) break
            for (tx in block.transactions) {
                if (tx.from == buyer && tx.to == account.address &&
                    tx.amount == amount && tx.note == "Model: $model"
                ) paid = true
                // Its own refund going the other way. Without this a buyer could ask repeatedly
                // and be paid every time, because the original purchase stays on the chain.
                if (tx.from == account.address && tx.to == buyer &&
                    tx.note == REFUND_NOTE_PREFIX + model
                ) alreadyRefunded = true
            }
        }

        if (!paid) {
            PrismLogger.logWarning(
                "ModelShop",
                "Refund request from $buyer for '$model' does not match any payment on this " +
                    "chain within the window; ignoring it."
            )
            return
        }
        if (alreadyRefunded) {
            PrismLogger.logWarning(
                "ModelShop", "'$model' has already been refunded to $buyer; ignoring the repeat."
            )
            return
        }
        if (chain.balanceOf(account.address) < amount) {
            PrismLogger.logWarning(
                "ModelShop", "Cannot refund '$model' to $buyer: balance is below the sale amount."
            )
            return
        }

        val tx = PscTransaction(
            from = account.address,
            to = buyer,
            amount = amount,
            fee = BigInteger.ZERO,
            nonce = chain.nonceOf(account.address),
            publicKey = WalletCrypto.compressedPublicKey(account.privateKey),
            note = REFUND_NOTE_PREFIX + model
        ).sign(account.privateKey)

        val result = chain.submit(tx)
        if (result is PscChain.TxResult.Rejected) {
            PrismLogger.logWarning("ModelShop", "Refund for '$model' rejected: ${result.reason}")
            return
        }
        PrismCoinNode.broadcastTransaction(tx)
        PrismCoinNode.save(context)
        PrismLogger.logSuccess("ModelShop", "Refunded '$model' to $buyer")
    }
}
