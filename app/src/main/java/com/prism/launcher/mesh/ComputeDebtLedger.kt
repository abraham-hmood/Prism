package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.ModelPayments
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import java.io.File
import java.math.BigInteger

/**
 * Work that has been done but not yet paid for.
 *
 * ## Why the work happens before the money
 *
 * The alternative is refusing to run anything for a user whose balance is short, which would make
 * the compute market useless to exactly the people it exists for: someone whose phone cannot run a
 * model is not, as a rule, someone sitting on a pile of coin. So a job runs, and if the balance
 * cannot cover it the amount is recorded here and settled the moment it can be -- from mining, from
 * selling a model, from selling compute back to the mesh.
 *
 * ## What stops this being free money
 *
 * The debt is announced mesh-wide, not kept privately by the debtor. Every peer that has heard the
 * announcement knows the address owes, which is what lets a host decline further work for a device
 * that is already deep in debt -- see [owedTo]. There is no enforcement beyond that, and there
 * cannot be on a chain without scripts: the honest description is that this is a reputation
 * system, not an escrow, and the amounts involved are small by design (see [ComputePricing]).
 *
 * ## Settlement
 *
 * Signed at settlement, never in advance, for the same nonce reason [ModelPayments] documents:
 * PrismCoin is account-and-nonce, a held transaction never advances the nonce, and two debts signed
 * ahead of time would both claim the same one.
 */
object ComputeDebtLedger {

    private const val TAG = "MeshCompute"
    private const val FILE = "compute_debts.json"

    private val FEE: BigInteger = BigInteger.ZERO

    /** One unpaid job. [creditorAddress] is where the money goes when it exists. */
    data class Debt(
        val id: String,
        val creditorAddress: String,
        val creditorIp: String,
        val deviceName: String,
        val amountMinor: BigInteger,
        val tokens: Int,
        val createdAt: Long,
        val settled: Boolean,
        val settledAt: Long,
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    // ── Reading ────────────────────────────────────────────────────────────

    fun all(context: Context): List<Debt> = runCatching {
        val source = file(context)
        if (!source.isFile) return emptyList()
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Debt(
                id = item.optString("id"),
                creditorAddress = item.optString("to"),
                creditorIp = item.optString("ip"),
                deviceName = item.optString("device"),
                amountMinor = BigInteger(item.optString("amount").ifBlank { "0" }),
                tokens = item.optInt("tokens"),
                createdAt = item.optLong("at"),
                settled = item.optBoolean("settled"),
                settledAt = item.optLong("settled_at"),
            )
        }
    }.getOrDefault(emptyList())

    fun outstanding(context: Context): List<Debt> = all(context).filter { !it.settled }

    fun totalOwed(context: Context): BigInteger =
        outstanding(context).fold(BigInteger.ZERO) { sum, debt -> sum.add(debt.amountMinor) }

    /** What this device owes one particular peer, for a host deciding whether to take more work. */
    fun owedTo(context: Context, creditorAddress: String): BigInteger =
        outstanding(context).filter { it.creditorAddress == creditorAddress }
            .fold(BigInteger.ZERO) { sum, debt -> sum.add(debt.amountMinor) }

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Charges for one completed job: pays it outright if the balance is there, records it if not.
     *
     * Returns true when the money actually moved. The caller does not branch on it -- the work is
     * already done either way -- but it is what the market UI shows as "paid" versus "owed".
     */
    fun charge(
        context: Context,
        peer: MeshComputeRegistry.Peer,
        tokens: Int,
    ): Boolean {
        val amount = ComputePricing.costOf(peer.score, tokens)
        if (amount.signum() <= 0) return true
        if (peer.payoutAddress.isBlank()) {
            PrismLogger.logWarning(TAG, "${peer.deviceName} has no payout address; nothing to pay")
            return false
        }

        if (pay(context, peer.payoutAddress, amount, peer.deviceName)) return true

        record(
            context,
            Debt(
                id = "${System.currentTimeMillis()}-${peer.peerIp}",
                creditorAddress = peer.payoutAddress,
                creditorIp = peer.peerIp,
                deviceName = peer.deviceName,
                amountMinor = amount,
                tokens = tokens,
                createdAt = System.currentTimeMillis(),
                settled = false,
                settledAt = 0L,
            )
        )
        announce(context, peer.payoutAddress, amount)
        PrismLogger.logInfo(
            TAG,
            "Owe ${ComputePricing.format(amount)} to ${peer.deviceName}; will settle when funded"
        )
        return false
    }

    /**
     * Pays whatever can be paid, oldest first.
     *
     * Oldest first rather than smallest first: paying the cheapest debts to make the list shorter
     * would leave the peer who waited longest waiting indefinitely, and the whole arrangement runs
     * on peers being willing to extend credit again.
     */
    fun settleAll(context: Context) {
        val pending = outstanding(context).sortedBy { it.createdAt }
        if (pending.isEmpty()) return

        var changed = false
        val updated = all(context).toMutableList()
        pending.forEach { debt ->
            if (ModelPayments.spendable(context) < debt.amountMinor) return@forEach
            if (!pay(context, debt.creditorAddress, debt.amountMinor, debt.deviceName)) return@forEach
            val index = updated.indexOfFirst { it.id == debt.id }
            if (index >= 0) {
                updated[index] = debt.copy(settled = true, settledAt = System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) write(context, updated)
    }

    private fun record(context: Context, debt: Debt) {
        write(context, all(context) + debt)
    }

    private fun write(context: Context, debts: List<Debt>) {
        runCatching {
            val array = JSONArray()
            // Settled debts are kept, not deleted: they are the record that the account was made
            // good, which is the only evidence a peer extending credit again has to go on.
            debts.takeLast(500).forEach { debt ->
                array.put(JSONObject().apply {
                    put("id", debt.id)
                    put("to", debt.creditorAddress)
                    put("ip", debt.creditorIp)
                    put("device", debt.deviceName)
                    put("amount", debt.amountMinor.toString())
                    put("tokens", debt.tokens)
                    put("at", debt.createdAt)
                    put("settled", debt.settled)
                    put("settled_at", debt.settledAt)
                })
            }
            file(context).writeText(array.toString())
        }.onFailure { PrismLogger.logError(TAG, "Could not write the debt ledger", it) }
    }

    // ── The chain ──────────────────────────────────────────────────────────

    /** Builds, signs and broadcasts a payment. False means nothing moved and nothing changed. */
    private fun pay(context: Context, to: String, amount: BigInteger, note: String): Boolean {
        val coin = CoinRegistry.bySymbol("PSC") ?: return false
        val seed = WalletVault.seed() ?: return false
        val account = runCatching { WalletVault.account(coin, seed) }.getOrNull() ?: return false

        val chain = PrismCoinNode.chain
        if (chain.balanceOf(account.address) < amount.add(FEE)) return false

        val tx = PscTransaction(
            from = account.address,
            to = to,
            amount = amount,
            fee = FEE,
            nonce = chain.nonceOf(account.address),
            publicKey = WalletCrypto.compressedPublicKey(account.privateKey),
            note = "Compute: $note"
        ).sign(account.privateKey)

        val result = chain.submit(tx)
        if (result is PscChain.TxResult.Rejected) {
            PrismLogger.logWarning(TAG, "Compute payment rejected: ${result.reason}")
            return false
        }
        PrismCoinNode.broadcastTransaction(tx)
        PrismCoinNode.save(context)
        return true
    }

    /** Tells the mesh about a debt, so the creditor -- and everyone else -- knows it exists. */
    private fun announce(context: Context, creditorAddress: String, amount: BigInteger) {
        val payload = JSONObject().apply {
            put("debtor", ModelPayments.address().orEmpty())
            put("creditor", creditorAddress)
            put("amount", amount.toString())
        }.toString()
        PrismMeshService.broadcastToOthers(MeshComputeRegistry.OPCODE_DEBT_ANNOUNCE, payload)
    }

    // ── The other side of the ledger ───────────────────────────────────────

    /** What peers have told us they owe this device, keyed by their address. */
    private val incoming = mutableMapOf<String, BigInteger>()

    fun ingestDebtAnnouncement(payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val creditor = json.optString("creditor")
            if (creditor != ModelPayments.address()) return   // somebody else's arrangement
            val debtor = json.optString("debtor")
            val amount = BigInteger(json.optString("amount").ifBlank { "0" })
            incoming[debtor] = (incoming[debtor] ?: BigInteger.ZERO).add(amount)
            PrismLogger.logInfo(TAG, "$debtor owes this device ${ComputePricing.format(amount)}")
        }
    }

    fun owedByOthers(): BigInteger =
        incoming.values.fold(BigInteger.ZERO) { sum, value -> sum.add(value) }
}
