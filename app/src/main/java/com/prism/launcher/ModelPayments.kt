package com.prism.launcher

import android.content.Context
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import java.math.BigInteger

/**
 * Paying for a model, and the reason payment happens later than the purchase.
 *
 * ## Signed at settlement, not at checkout
 *
 * The obvious design is to sign the transaction when the buyer taps Buy and hold the signed bytes
 * until the listing clears its first scan. That breaks on the nonce. PrismCoin is account-and-nonce
 * in the Ethereum style: each account's transactions must arrive in strict sequence, and a held
 * transaction is never broadcast, so `nonceOf` does not advance while it waits. Two purchases held
 * at once would both sign nonce N and only one of them could ever be accepted -- and a purchase
 * that got cancelled would burn its nonce and strand every later one behind the gap.
 *
 * So a purchase records INTENT, and the transaction is built, signed and broadcast at settlement,
 * when the current nonce is known and correct. Nothing depended on signing early: the signed bytes
 * were never handed to the seller, precisely so they could not be broadcast ahead of the scan.
 *
 * ## What the buyer is protected from, and what they are not
 *
 * No coins move until [ModelListingScanner] clears the listing, so a model that turns out to be a
 * repackaged public download costs the buyer nothing -- there is no refund to process because there
 * was no payment. The seller carries the delivery risk instead. That asymmetry is deliberate and is
 * the most a chain without scripts can actually enforce; see [ModelPurchaseLedger].
 */
object ModelPayments {

    /**
     * Fee attached to a settlement.
     *
     * Zero, because PrismCoin's mempool does not price-sort and a fee here would be a cost with no
     * corresponding benefit. Worth revisiting if the chain ever gets congested enough for miners to
     * choose between transactions.
     */
    private val FEE: BigInteger = BigInteger.ZERO

    sealed class Result {
        object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    /** The buyer's PrismCoin address, or null when this device has no wallet. */
    fun address(): String? = runCatching {
        CoinRegistry.bySymbol("PSC")?.let { WalletVault.addressFor(it) }
    }.getOrNull()

    fun balance(): BigInteger {
        val addr = address() ?: return BigInteger.ZERO
        return runCatching { PrismCoinNode.chain.balanceOf(addr) }.getOrDefault(BigInteger.ZERO)
    }

    /**
     * Balance minus everything already committed to purchases still waiting on a scan.
     *
     * Without this a buyer could commit the same coins to several models: none of those payments
     * has been broadcast, so the chain still reports the full balance behind all of them.
     */
    fun spendable(context: Context): BigInteger =
        balance().subtract(ModelPurchaseLedger.heldTotal(context)).max(BigInteger.ZERO)

    /** Records the purchase. Deliberately moves no money -- see the class comment. */
    fun commit(context: Context, listing: P2pModelListings.Listing): Result {
        if (address() == null) {
            return Result.Failed(
                "This device has no PrismCoin wallet. PrismCoin runs only where the wallet page " +
                    "is on the desktop."
            )
        }
        if (listing.sellerAddress.isBlank()) {
            return Result.Failed("That listing has no seller address, so it cannot be paid.")
        }
        if (listing.priceMinor.signum() <= 0) {
            return Result.Failed("Free listings are not allowed.")
        }
        if (ModelPurchaseLedger.all(context).any {
                it.listingId == listing.id && it.state != ModelPurchaseLedger.State.CANCELLED
            }
        ) {
            return Result.Failed("You have already bought this model.")
        }
        if (spendable(context) < listing.priceMinor) {
            return Result.Failed(
                "Not enough spendable PrismCoin. Coins committed to purchases still awaiting " +
                    "their first check do not count towards this."
            )
        }

        ModelPurchaseLedger.record(
            context,
            ModelPurchaseLedger.Purchase(
                listingId = listing.id,
                sellerAddress = listing.sellerAddress,
                modelName = listing.name,
                amountMinor = listing.priceMinor,
                committedAt = System.currentTimeMillis(),
                state = ModelPurchaseLedger.State.HELD,
                signedTx = ""
            )
        )
        return Result.Ok
    }

    /**
     * Builds, signs and broadcasts the payment for a purchase whose listing has cleared.
     *
     * Returns false without changing anything when it cannot complete -- an unavailable seed, an
     * insufficient balance, a rejecting chain -- so the purchase stays held and is retried on the
     * next scan rather than being silently marked paid.
     */
    fun settle(context: Context, purchase: ModelPurchaseLedger.Purchase): Boolean {
        val coin = CoinRegistry.bySymbol("PSC") ?: return false
        val seed = WalletVault.seed() ?: return false          // locked or uninitialised wallet
        val account = runCatching { WalletVault.account(coin, seed) }.getOrNull() ?: return false

        val chain = PrismCoinNode.chain
        if (chain.balanceOf(account.address) < purchase.amountMinor.add(FEE)) return false

        val tx = PscTransaction(
            from = account.address,
            to = purchase.sellerAddress,
            amount = purchase.amountMinor,
            fee = FEE,
            // Read at settlement, which is the entire reason signing waits until now.
            nonce = chain.nonceOf(account.address),
            publicKey = WalletCrypto.compressedPublicKey(account.privateKey),
            note = "Model: ${purchase.modelName}"
        ).sign(account.privateKey)

        val result = chain.submit(tx)
        if (result is PscChain.TxResult.Rejected) {
            PrismLogger.logWarning(
                "ModelShop", "Settlement rejected for ${purchase.modelName}: ${result.reason}"
            )
            return false
        }
        PrismCoinNode.broadcastTransaction(tx)
        PrismCoinNode.save(context)
        return true
    }
}
