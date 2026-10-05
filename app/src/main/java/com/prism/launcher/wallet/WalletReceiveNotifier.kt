package com.prism.launcher.wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletVault
import java.math.BigInteger

/**
 * Tells the user when money arrives.
 *
 * ## Detected by balance INCREASE, not by watching for transactions
 *
 * For every coin but PrismCoin, Prism is a light wallet -- it has no mempool and sees no
 * transactions, only what an explorer reports. So an arrival is inferred from the balance going up
 * since the last check. That is coarse but honest, and it has one useful property: it cannot miss a
 * payment that arrived while the app was closed, because the comparison is against a stored figure
 * rather than a stream of events.
 *
 * A DECREASE IS RECORDED SILENTLY. Spending is something the user just did, so it needs no
 * notification -- but the baseline still has to move, or the next receipt would be measured against
 * a stale, higher number and go unreported.
 *
 * ## Notes only exist where the chain carries them
 *
 * PrismCoin transactions have a signed [PscTransaction.note], so a PSC arrival can say what it was
 * for. Bitcoin, Litecoin and the rest carry no such field, so those notifications show the amount
 * alone. The format follows what was asked for: amount by itself when there is no note, and the
 * note alongside the amount when there is one.
 */
object WalletReceiveNotifier {

    private const val TAG = "PrismWallet"
    private const val CHANNEL_ID = "prism_wallet_receipts"
    private const val ID_BASE = 95_000

    private fun idFor(symbol: String): Int = ID_BASE + (symbol.hashCode() and 0xFFF)

    /**
     * Compares a coin's balance against the last one seen and notifies on an increase.
     *
     * @param note a message that came with the payment, where the chain supports one.
     */
    /**
     * Compares a coin's balance against the last one seen and notifies on an increase.
     *
     * THE RULES MOVED TO `WalletReceipts` IN :core (PHASE 88) so the desktop has them too -- the baseline
     * comparison and the "a first sighting is not a receipt" rule were never Android-specific. This
     * forwards; what stays here is the PRESENTATION, installed by [installPresenter], because Android can
     * do things a tray balloon cannot.
     */
    fun onBalanceObserved(
        context: Context,
        coin: CoinSpec,
        balance: BigInteger,
        note: String = "",
    ) {
        installPresenter(context)
        com.prism.launcher.wallet.WalletReceipts.onBalanceObserved(coin, balance, note)
    }

    /**
     * Points the shared receipt logic at an Android notification.
     *
     * WHAT WOULD HAVE BEEN LOST WITHOUT THIS: the tap target and the big-text style. `PrismPlatform.notifier`
     * shows a title and a body, which is everything a desktop tray has; a payment notification on a phone
     * that cannot be tapped to open the wallet, and that truncates a payment reference to one line, would
     * be a real regression from moving the logic to :core. So the logic is shared and this is not.
     *
     * Idempotent, and installed from every entry point rather than once at startup: the node can announce a
     * receipt before anything has touched the wallet UI.
     */
    fun installPresenter(context: Context) {
        val app = context.applicationContext
        com.prism.launcher.wallet.WalletReceipts.presenter = { coin, amountText, note ->
            ensureChannel(app)

            val tap = PendingIntent.getActivity(
                app, 0,
                Intent(app, com.prism.launcher.LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val long = if (note.isBlank()) {
                "$amountText arrived in your ${coin.name} wallet."
            } else {
                "$note\n\n$amountText arrived in your ${coin.name} wallet."
            }

            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Received $amountText")
                .setContentText(note.ifBlank { coin.name })
                .setContentIntent(tap)
                // The note can be longer than one line, and a payment reference truncated to
                // "rent, M..." is worse than useless.
                .setStyle(NotificationCompat.BigTextStyle().bigText(long))
                .setAutoCancel(true)
                .build()

            runCatching {
                (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(com.prism.launcher.wallet.WalletReceipts.idFor(coin.symbol), notification)
            }.onFailure {
                PrismLogger.logError(TAG, "Receipt notification suppressed for ${coin.symbol}", it)
            }
        }
    }

    /**
     * Announces a specific amount, for chains where the arrival is seen directly rather than
     * inferred. PrismCoin uses this, since it validates the transaction itself and has the note.
     */
    /**
     * Announces a specific amount, for chains where the arrival is seen directly rather than inferred.
     * PrismCoin uses this, since it validates the transaction itself and has the note.
     *
     * Forwards to `WalletReceipts`; the presentation is what this file still owns.
     */
    fun notifyReceived(context: Context, coin: CoinSpec, amount: BigInteger, note: String = "") {
        installPresenter(context)
        com.prism.launcher.wallet.WalletReceipts.notifyReceived(coin, amount, note)
    }

    /**
     * A PrismCoin block landed; announce anything in it addressed to this wallet.
     *
     * Called from the node rather than from a poll, because on our own chain the arrival is
     * witnessed directly -- including the note, which no balance comparison could recover.
     */
    fun onPrismCoinBlock(context: Context, transactions: List<com.prism.launcher.wallet.psc.PscTransaction>) {
        val psc = CoinRegistry.bySymbol("PSC") ?: return
        val mine = WalletVault.addressFor(psc) ?: return

        for (tx in transactions) {
            if (tx.to != mine) continue
            if (tx.from == mine) continue          // change from one's own spend is not a receipt
            notifyReceived(context, psc, tx.amount, tx.note)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Wallet receipts", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shown when coins arrive in a Prism wallet"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
