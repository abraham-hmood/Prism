package com.prism.launcher.mesh

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform

import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.CoinRegistry
import java.math.BigInteger

/**
 * Using other people's devices before you can pay for them.
 *
 * ## What the checkbox actually commits you to
 *
 * Distributed work on the mesh is not free and it is not abstract: a stranger's phone runs at full
 * tilt, gets hot, and loses battery, on the understanding that it will be paid. [ComputeDebtLedger]
 * already lets a job go ahead when the wallet is empty by recording a debt — but a debt that nothing
 * is ever going to fund is not credit, it is a device being used for nothing.
 *
 * So the pay-later checkbox is bound to mining. Tick it and Prism mines Prism Coin, and keeps mining,
 * for as long as anything is owed. That is the only thing a phone can do to produce the coin it owes,
 * and it is what makes the promise to the peers real rather than rhetorical. Untick it and the miner
 * is left alone — it is not stopped, because the user may well have started it themselves for their
 * own reasons, and a policy that switched off somebody's mining because they changed an unrelated
 * setting would be taking a decision that is not its to take.
 *
 * ## Everything distributed goes through here
 *
 * The same check guards LLM generation, LLM training, protein folding, protein training, and every
 * cloud feature. Not because they are similar — they are not — but because a user who agreed to pay
 * later agreed once, and a second class of work that quietly did not honour the arrangement would be
 * the same unpaid peer with a different excuse.
 *
 * ## Why this does not pay eagerly
 *
 * Settlement is attempted when work starts and when the wallet is likely to have changed, not on a
 * timer. Each attempt walks the debt list and submits transactions, and doing that every few seconds
 * against an empty wallet would be a loop that costs battery to discover nothing new. The ledger is
 * durable, so a settlement that happens later settles exactly as much.
 */
object PayLaterPolicy {

    private const val TAG = "PrismPayLater"

    /** What kind of work asked. Only used for the log line, which is where this gets diagnosed. */
    enum class Work {
        LLM_INFERENCE,
        LLM_TRAINING,
        PROTEIN_FOLDING,
        PROTEIN_TRAINING,
        CLOUD_COMPUTE,
        CLOUD_STORAGE,
        CLOUD_GAMING,
        ;

        fun describe(): String = name.lowercase().replace('_', ' ')
    }

    // ── The gate ───────────────────────────────────────────────────────────

    /**
     * Whether distributed work of this kind may start, and what to say if not.
     *
     * Returns a reason rather than a bare false so every caller shows the same explanation — there
     * are six of them and they were never going to word it the same way twice.
     */
    sealed class Verdict {
        /** Cleared to run. [paying] is false when this will be on credit. */
        data class Allowed(val paying: Boolean, val note: String) : Verdict()
        data class Blocked(val reason: String) : Verdict()
    }

    fun check(work: Work): Verdict {
        val training = work == Work.LLM_TRAINING || work == Work.PROTEIN_TRAINING
        val switch = if (training) {
            PrismSettings.getDistributedTraining()
        } else {
            PrismSettings.getDistributedInference()
        }
        if (!switch) {
            return Verdict.Blocked(
                if (training) {
                    "Distributed training is off. Turn it on to train across the meshnet."
                } else {
                    "Distributed inference is off. Turn it on to use other devices on the meshnet."
                }
            )
        }

        if (!MeshTransport.isOnMesh()) {
            return Verdict.Blocked(
                "Not on a Prism meshnet. Join one or let this device serve one, then try again."
            )
        }

        val peers = MeshInference.selectedPeers()
        if (peers.isEmpty()) {
            return Verdict.Blocked(
                "No peer on the mesh is selling compute right now. This will run on this device."
            )
        }

        if (!WalletVault.isInitialized()) {
            return Verdict.Blocked(
                "Paying for someone else's compute needs a wallet. Create one on the Wallet page " +
                    "first — the peers are paid in Prism Coin, and there is nothing to pay from " +
                    "until there is a wallet."
            )
        }

        val funded = hasFunds(peers)
        if (funded) {
            return Verdict.Allowed(
                paying = true,
                note = "${peers.size} peer(s) · paid per request from your Prism Coin balance",
            )
        }

        if (!PrismSettings.getPayLaterEnabled()) {
            return Verdict.Blocked(
                "Your Prism Coin balance will not cover this. Tick \"pay later\" to run it on " +
                    "credit — Prism will then mine Prism Coin until the debt is paid, so the " +
                    "devices doing your work actually get paid."
            )
        }

        // Committed to credit, so the miner had better be running.
        ensureMining(work)
        return Verdict.Allowed(
            paying = false,
            note = "${peers.size} peer(s) · on credit, mining Prism Coin to settle",
        )
    }

    /** True when the wallet can cover one request on every peer chosen, which is the honest floor. */
    private fun hasFunds(peers: List<MeshComputeRegistry.Peer>): Boolean {
        val needed = peers.fold(BigInteger.ZERO) { sum, peer -> sum.add(peer.pricePerRequest) }
        if (needed.signum() <= 0) return true
        val balance = runCatching { balanceMinor() }.getOrDefault(BigInteger.ZERO)
        // Existing debts count against the balance. Otherwise the same coin is promised twice: once
        // to the peer who is already owed it and once to the peer about to start work.
        val owed = runCatching { ComputeDebtLedger.totalOwed() }.getOrDefault(BigInteger.ZERO)
        return balance.subtract(owed) >= needed
    }

    /**
     * What this device could spend, in prismite.
     *
     * THE ADDRESS COMES FROM THE WALLET AND THE BALANCE FROM THE BRIDGE, which is not redundant: the
     * wallet derivation is portable and lives in `:core`, while the chain that knows what an address
     * holds is the Prism Coin node in `:app`. A device with a wallet and no node therefore has an
     * address and a balance of zero -- which is the right answer, because it cannot spend anything.
     */
    private fun balanceMinor(): BigInteger {
        val coin = CoinRegistry.bySymbol(PrismCoinConsensus.SYMBOL) ?: return BigInteger.ZERO
        val address = WalletVault.addressFor(coin) ?: return BigInteger.ZERO
        return MeshBridges.balanceOf(address)
    }

    // ── Forced mining ──────────────────────────────────────────────────────

    /**
     * Starts the miner if pay-later is on and it is not already running.
     *
     * Idempotent, and called from every distributed entry point rather than once at startup. A
     * foreground service on Android is not a thing you start and forget: the OEM power manager kills
     * it, the user swipes the notification, the process is reclaimed. Re-asserting it at the moment
     * work begins is what makes "always mining" true in practice rather than only at boot.
     *
     * Prism Coin only. Mining someone else's chain would pay this device in a coin the peers were
     * not promised, and converting it is not something Prism can do on the user's behalf.
     */
    fun ensureMining(work: Work? = null) {
        if (!PrismSettings.getPayLaterEnabled()) return

        if (MeshBridges.isMining() &&
            MeshBridges.miningCoin().equals(PrismCoinConsensus.SYMBOL, ignoreCase = true)
        ) {
            return
        }

        if (MeshBridges.isMining()) {
            // Already mining something else. Left alone: the user chose that coin, and it is still
            // hash power being spent on their behalf. Saying so in the log is enough, because the
            // debt does not get settled by this and the market UI shows it outstanding.
            PrismPlatform.log.info(
                TAG,
                "Pay-later is on but this device is mining " + MeshBridges.miningCoin() +
                    "; not switching it to PSC",
            )
            return
        }

        val coin = CoinRegistry.bySymbol(PrismCoinConsensus.SYMBOL)
        val address = coin?.let { WalletVault.addressFor(it) }
        if (address.isNullOrBlank()) {
            PrismPlatform.log.warn(TAG, "Pay-later is on but there is no PSC address to mine to")
            return
        }

        // THE COIN, THE POOL AND THE ALGORITHM ARE NO LONGER PASSED IN, and that is a narrowing
        // rather than a loss. Prism Coin is mined over the mesh rather than through a pool, so the
        // host and port were always empty and the algorithm was always SHA-256d; spelling them out
        // here invited a future caller to change one and produce a miner paying into a chain the
        // creditors were never promised. MeshBridges.Mining.startPrismCoin takes the two things that
        // genuinely vary -- where the reward goes, and how hard to work.
        val miner = MeshBridges.mining
        if (miner == null) {
            PrismPlatform.log.warn(
                TAG,
                "Pay-later is on but this device has no miner, so debts cannot be worked off here.",
            )
            return
        }
        runCatching {
            val started = miner.startPrismCoin(address, PrismSettings.getMiningThreads())
            if (started) {
                PrismPlatform.log.info(
                    TAG,
                    "Mining PSC to cover pay-later" + (work?.let { " (" + it.describe() + ")" } ?: ""),
                )
            } else {
                PrismPlatform.log.warn(TAG, "The miner refused to start for pay-later")
            }
        }.onFailure { PrismPlatform.log.error(TAG, "Could not start forced mining", it) }
    }

    /**
     * Called after work finishes: settle what can be settled, and keep mining if anything is left.
     *
     * The order matters. Settling first means the "is anything still owed" question is asked about
     * the state after payment, so a device that has just cleared its last debt stops being told to
     * mine for it.
     */
    fun afterWork() {
        runCatching { ComputeDebtLedger.settleAll() }
        if (!PrismSettings.getPayLaterEnabled()) return
        val owed = runCatching { ComputeDebtLedger.totalOwed() }.getOrDefault(BigInteger.ZERO)
        if (owed.signum() > 0) ensureMining()
    }

    /** One line for a panel to show under its distributed-compute switch. */
    fun status(): String {
        if (!PrismSettings.getPayLaterEnabled()) return ""
        val owed = runCatching { ComputeDebtLedger.totalOwed() }.getOrDefault(BigInteger.ZERO)
        val mining = MeshBridges.isMining()
        return when {
            owed.signum() > 0 && mining ->
                "Owing ${ComputePricing.format(owed)} · mining to settle it"
            owed.signum() > 0 ->
                "Owing ${ComputePricing.format(owed)} · mining will start with the next job"
            mining -> "Nothing owed · mining Prism Coin"
            else -> "Nothing owed"
        }
    }
}
