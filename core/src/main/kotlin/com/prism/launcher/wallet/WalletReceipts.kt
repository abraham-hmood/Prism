package com.prism.launcher.wallet

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.math.BigInteger

/**
 * Telling the user that money arrived. PHASE 88.
 *
 * ## The two rules that are easy to get wrong
 *
 * A BALANCE INCREASE IS DETECTED AGAINST A STORED BASELINE, not against what the app saw while it was
 * running. That is what makes a payment that landed overnight still get announced: the comparison is
 * against the last figure ever written down, and the app being closed in between changes nothing.
 *
 * A FIRST SIGHTING IS NOT A RECEIPT. Adding a coin that already holds funds, or reinstalling and
 * restoring a wallet, would otherwise announce the entire balance as though it had just arrived --
 * which is both wrong and alarming. A null baseline records the figure and says nothing.
 *
 * Both rules came from the Android implementation and are preserved exactly; this file exists because
 * they were the only part that was Android-specific, and they were not.
 *
 * ## Why the presentation is a hook
 *
 * `PrismPlatform.notifier` can show a title and a body, which is all a desktop tray balloon has. Android
 * can do better and already did: its notification carries a tap target that opens Prism, and a big-text
 * style so a long payment reference is readable rather than truncated to "rent, Ma…". Moving this to
 * `:core` must not cost the phone that, so [presenter] defaults to the portable notifier and Android
 * installs a richer one.
 *
 * ## PrismCoin versus everything else
 *
 * PrismCoin arrivals come through [notifyReceived] directly, from the node, because Prism validates its
 * own chain and therefore SEES the transaction -- including its note, which no balance comparison could
 * recover. Every other chain is polled, and a poll can only report the amount.
 */
object WalletReceipts {

    private const val TAG = "PrismWalletReceipts"
    const val CHANNEL = "prism_wallet_receipts"

    private const val ID_BASE = 95_000

    /** A stable notification id per coin, so a second receipt replaces the first rather than stacking. */
    fun idFor(symbol: String): Int = ID_BASE + (symbol.hashCode() and 0xFFF)

    /**
     * How a receipt is shown. Replaced by Android with a version that can be tapped.
     *
     * @param amountText the formatted amount with its symbol, already assembled, so a platform that wants
     *   to lay it out differently is not re-deriving it from a BigInteger.
     */
    var presenter: (coin: CoinSpec, amountText: String, note: String) -> Unit =
        { coin, amountText, note ->
            val body = if (note.isBlank()) {
                amountText + " arrived in your " + coin.name + " wallet."
            } else {
                note + "\n\n" + amountText + " arrived in your " + coin.name + " wallet."
            }
            PrismPlatform.notifier.notify(
                channel = CHANNEL,
                id = idFor(coin.symbol),
                title = "Received " + amountText,
                body = body,
            )
        }

    /** What has been announced, for a diagnostics line. */
    var announced = 0
        private set

    /**
     * Compares a coin's balance against the last one seen and announces an increase.
     *
     * Call this from wherever a balance is fetched -- it is cheap, it writes the baseline every time, and
     * it decides for itself whether anything is worth saying.
     */
    fun onBalanceObserved(coin: CoinSpec, balance: BigInteger, note: String = "") {
        val previous = PrismSettings.getLastSeenBalance(coin.symbol)
        PrismSettings.setLastSeenBalance(coin.symbol, balance)

        // See the class comment: a first sighting records and stays quiet.
        if (previous == null) return
        if (balance <= previous) return

        notifyReceived(coin, balance.subtract(previous), note)
    }

    /** Announces a specific amount, for a chain where the arrival is witnessed rather than inferred. */
    fun notifyReceived(coin: CoinSpec, amount: BigInteger, note: String = "") {
        if (amount.signum() <= 0) return
        val amountText = coin.format(amount) + " " + coin.symbol
        runCatching { presenter(coin, amountText, note.trim()) }
            .onFailure { PrismPlatform.log.error(TAG, "Receipt suppressed for " + coin.symbol, it) }
            .onSuccess {
                announced++
                PrismPlatform.log.info(TAG, "Announced receipt of " + amountText)
            }
    }

    /**
     * Forgets every baseline.
     *
     * For a wallet being wiped: leaving the baselines behind would mean the NEXT wallet's first balance
     * read was compared against somebody else's figures, and a restored wallet with less in it than the
     * old one would silently never announce anything again.
     */
    fun forgetBaselines() {
        runCatching {
            CoinRegistry.all().forEach { PrismSettings.clearLastSeenBalance(it.symbol) }
        }
    }

    fun describe(): String = announced.toString() + " receipt(s) announced this session"
}
