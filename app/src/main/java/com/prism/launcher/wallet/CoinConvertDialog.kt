package com.prism.launcher.wallet

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.P2pCoinOffers
import java.math.BigDecimal

/**
 * Converting one coin into another.
 *
 * ## What "convert" means here
 *
 * There is no exchange behind Prism, so this does not sell anything to a venue. It posts an offer
 * to the mesh order book and shows any offer already there that would fill it. That is the honest
 * shape of conversion in a wallet whose coins live on real chains but which has no market maker:
 * somebody else has to want the other side.
 *
 * The alternative -- crediting the destination coin locally -- would show a balance that no chain
 * would honour, and is exactly the thing not to build.
 *
 * ## PrismCoin only trades against USDC
 *
 * Enforced here and again in [P2pCoinOffers] on arrival. PSC's issuance pegs it to an hour of
 * average global labour; quoting it against something volatile would erase that peg the moment the
 * other side moved.
 */
object CoinConvertDialog {

    fun show(context: Context) {
        val symbols = runCatching { WalletVault.enabledSymbols() }
            .getOrDefault(emptyList())
            .ifEmpty { listOf("PSC", "USDC") }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val from = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, symbols)
        }
        val to = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, symbols)
            if (symbols.size > 1) setSelection(1)
        }
        val amount = EditText(context).apply {
            hint = "Amount to convert"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = TextView(context).apply {
            textSize = 12f
            alpha = 0.75f
            text = "Conversion is an offer to another person on your mesh, not a sale to an " +
                "exchange — Prism has none. Your offer is advertised and settles when somebody " +
                "takes it. Neither side is escrowed: whoever sends first is trusting the other."
        }

        root.addView(label(context, "From"));  root.addView(from)
        root.addView(label(context, "To"));    root.addView(to)
        root.addView(label(context, "Amount")); root.addView(amount)
        root.addView(note)

        val quotes = TextView(context).apply { textSize = 12f }
        root.addView(quotes)

        fun refreshQuotes() {
            val f = from.selectedItem?.toString().orEmpty()
            val t = to.selectedItem?.toString().orEmpty()
            if (!P2pCoinOffers.isPairAllowed(f, t)) {
                quotes.text = if (f == "PSC" || t == "PSC") {
                    "PrismCoin can only be converted to and from ${P2pCoinOffers.PSC_PAIRED_WITH}."
                } else {
                    "Pick two different coins."
                }
                return
            }
            val open = P2pCoinOffers.matching(give = f, want = t)
            quotes.text = if (open.isEmpty()) {
                "No open offers for $f → $t. Posting yours puts it in front of your peers."
            } else {
                "Best open offer: " + open.first().let {
                    "${it.fromAmount.toPlainString()} ${it.fromSymbol} for " +
                        "${it.toAmount.toPlainString()} ${it.toSymbol}"
                }
            }
        }

        val listener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: android.widget.AdapterView<*>?, v: android.view.View?, i: Int, id: Long
            ) = refreshQuotes()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        from.onItemSelectedListener = listener
        to.onItemSelectedListener = listener
        refreshQuotes()

        AlertDialog.Builder(context)
            .setTitle("Convert")
            .setView(root)
            .setPositiveButton("Convert") { _, _ ->
                submit(
                    context,
                    from.selectedItem?.toString().orEmpty(),
                    to.selectedItem?.toString().orEmpty(),
                    amount.text.toString()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submit(context: Context, from: String, to: String, rawAmount: String) {
        if (!P2pCoinOffers.isPairAllowed(from, to)) {
            Toast.makeText(
                context,
                if (from == "PSC" || to == "PSC")
                    "PrismCoin only converts to and from ${P2pCoinOffers.PSC_PAIRED_WITH}."
                else "Choose two different coins.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val value = rawAmount.trim().toBigDecimalOrNull()
        if (value == null || value.signum() <= 0) {
            Toast.makeText(context, "Enter an amount above zero.", Toast.LENGTH_SHORT).show()
            return
        }
        val receiveSpec = CoinRegistry.bySymbol(to)
        val receiveAddress = receiveSpec?.let { WalletVault.addressFor(it) }
        if (receiveAddress.isNullOrBlank()) {
            Toast.makeText(
                context,
                "No $to address in this wallet, so there is nowhere for the other side to pay.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Priced off the open book when there is one, so an offer posted blind does not sit at a
        // rate nobody would take. With an empty book it is quoted one-for-one and the maker is
        // expected to edit it -- there is no oracle to do better, and inventing a rate would be
        // worse than showing an obvious placeholder.
        val open = P2pCoinOffers.matching(give = from, want = to).firstOrNull()
        val wanted = open?.let { value.multiply(it.rate()) } ?: value

        P2pCoinOffers.post(
            context,
            P2pCoinOffers.Offer(
                peerIp = "",
                id = "${from}-${to}-${System.currentTimeMillis()}",
                fromSymbol = from.uppercase(),
                toSymbol = to.uppercase(),
                fromAmount = value,
                toAmount = wanted,
                makerReceiveAddress = receiveAddress,
                timestamp = System.currentTimeMillis()
            )
        )
        Toast.makeText(
            context,
            "Offer posted: ${value.toPlainString()} $from for ${wanted.toPlainString()} $to. " +
                "It settles when a peer takes it.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        alpha = 0.7f
    }
}
