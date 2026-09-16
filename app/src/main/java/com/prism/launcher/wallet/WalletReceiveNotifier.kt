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
    fun onBalanceObserved(
        context: Context,
        coin: CoinSpec,
        balance: BigInteger,
        note: String = "",
    ) {
        val previous = PrismSettings.getLastSeenBalance(coin.symbol)
        PrismSettings.setLastSeenBalance(coin.symbol, balance)

        // A first sighting is not a receipt. Without this, adding a coin that already holds funds
        // -- or reinstalling -- would announce the entire balance as though it had just arrived.
        if (previous == null) return
        if (balance <= previous) return

        notifyReceived(context, coin, balance.subtract(previous), note)
    }

    /**
     * Announces a specific amount, for chains where the arrival is seen directly rather than
     * inferred. PrismCoin uses this, since it validates the transaction itself and has the note.
     */
    fun notifyReceived(context: Context, coin: CoinSpec, amount: BigInteger, note: String = "") {
        if (amount.signum() <= 0) return
        ensureChannel(context)

        val formatted = "${coin.format(amount)} ${coin.symbol}"
        val trimmedNote = note.trim()

        val title = "Received $formatted"
        val body = trimmedNote.ifBlank { coin.name }

        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.prism.launcher.LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            // The note can be longer than one line, and a payment reference truncated to "rent, M…"
            // is worse than useless.
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (trimmedNote.isBlank()) "$formatted arrived in your ${coin.name} wallet."
                    else "$trimmedNote\n\n$formatted arrived in your ${coin.name} wallet."
                )
            )
            .setAutoCancel(true)
            .build()

        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(idFor(coin.symbol), notification)
        }.onFailure {
            PrismLogger.logError(TAG, "Receipt notification suppressed for ${coin.symbol}", it)
        }
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
