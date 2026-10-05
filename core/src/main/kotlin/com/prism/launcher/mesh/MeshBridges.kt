package com.prism.launcher.mesh

import com.prism.core.PrismPlatform
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The few things the mesh market needs that are not portable. PHASE 98.
 *
 * ## Why these are hooks and not moved code
 *
 * The compute market, the model listings and the coin offers all moved into `:core` so the desktop gets
 * the same implementation the phone runs. Three things could not come with them, and each for a
 * different reason worth stating rather than lumping together as "platform stuff":
 *
 *  - THE PRISM COIN NODE is a full chain implementation that lives in `:app`. Moving it to settle a
 *    compute debt would mean moving the chain, its consensus and its storage -- a far larger port than
 *    this one, and one with its own phase. So the ledger asks for a payment and does not care who makes
 *    it.
 *  - THE MINER is an Android foreground service. There is no desktop equivalent yet and there may
 *    never be one shaped like it, because a desktop does not need a notification to keep running.
 *  - PRISM'S SOCKET resolves mesh names through the VPN's own resolver before connecting. On a desktop
 *    an ordinary socket reaches a peer by IP, because there is no per-app tunnel to route around.
 *
 * ## What happens when a hook is not installed
 *
 * The feature degrades and SAYS SO, rather than failing. A desktop with no coin node reports debts it
 * cannot settle instead of pretending they are paid; a device with no miner says pay-later is on and it
 * cannot mine. Both are honest states that the market UI can show. What none of them do is throw, which
 * would take a whole page down because one optional capability was absent.
 */
object MeshBridges {

    private const val TAG = "PrismMeshBridge"

    // ── Coin ───────────────────────────────────────────────────────────────

    /**
     * What the mesh market needs from a wallet.
     *
     * NOT the whole wallet: this is deliberately four calls, so that whatever implements it -- the
     * Android coin node today, something else later -- has a small and obvious contract. Anything
     * needing more than this belongs in the wallet package rather than here.
     */
    interface Coin {
        /** This device's payout address, or null when there is no wallet. */
        fun address(): String?

        /** What this device could spend right now, in the smallest unit. */
        fun spendable(): BigInteger

        /** The balance of any address, for deciding whether a peer can be trusted to pay. */
        fun balanceOf(address: String): BigInteger

        /**
         * Pays a creditor.
         *
         * Returns false rather than throwing on refusal, because "the chain rejected this" and "there
         * is no chain here" are both ordinary answers for a device settling a debt, and the ledger
         * records an unsettled debt either way.
         */
        fun pay(to: String, amountMinor: BigInteger, note: String): Boolean
    }

    /** Installed by the platform that has a coin node. Null means this device cannot pay. */
    var coin: Coin? = null

    /** The payout address, or empty when there is no wallet on this device. */
    fun payoutAddress(): String = runCatching { coin?.address() }.getOrNull().orEmpty()

    fun spendable(): BigInteger = runCatching { coin?.spendable() }.getOrNull() ?: BigInteger.ZERO

    fun balanceOf(address: String): BigInteger =
        runCatching { coin?.balanceOf(address) }.getOrNull() ?: BigInteger.ZERO

    fun pay(to: String, amountMinor: BigInteger, note: String): Boolean {
        val bridge = coin
        if (bridge == null) {
            PrismPlatform.log.warn(
                TAG,
                "Cannot settle " + amountMinor + " to " + to + ": this device has no coin node. " +
                    "The debt stays on the ledger.",
            )
            return false
        }
        return runCatching { bridge.pay(to, amountMinor, note) }
            .onFailure { PrismPlatform.log.error(TAG, "Payment failed", it) }
            .getOrDefault(false)
    }

    // ── Mining ─────────────────────────────────────────────────────────────

    /** What pay-later needs from a miner. */
    interface Mining {
        val running: Boolean

        /** Which coin is being mined, or empty. */
        val coin: String

        /**
         * Starts mining Prism Coin to [address].
         *
         * The coin is not a parameter: pay-later exists to settle debts denominated in PSC, and mining
         * anything else would pay this device in a coin its creditors were not promised.
         */
        fun startPrismCoin(address: String, threads: Int): Boolean
    }

    var mining: Mining? = null

    fun isMining(): Boolean = runCatching { mining?.running }.getOrNull() ?: false

    fun miningCoin(): String = runCatching { mining?.coin }.getOrNull().orEmpty()

    // ── Sockets ────────────────────────────────────────────────────────────

    /**
     * Opens a connection to a peer.
     *
     * THE HOST HINT IS THE WHOLE REASON THIS IS A HOOK. Android connects through `PrismSocket`, which
     * tells the VPN which mesh NAME this connection is for so the tunnel can route it; a desktop has no
     * per-application tunnel, so a plain socket to the peer's address is both correct and simpler. The
     * default below is that plain socket, which is also what the tests use.
     */
    var connect: (peerIp: String, port: Int, timeoutMs: Int, hostHint: String) -> Socket? =
        { peerIp, port, timeoutMs, _ ->
            runCatching {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(InetSocketAddress(peerIp, port), timeoutMs)
                }
            }.getOrNull()
        }

    // ── Inference on a peer ────────────────────────────────────────────────

    /**
     * Runs a prompt on a peer and streams the answer back.
     *
     * A hook because the client lives with the rest of the AI plumbing rather than with the market, and
     * the market does not need to know how a peer is asked -- only that it can be. Null means this
     * device cannot ask a peer, which is the state the market reports as "cannot offload".
     */
    var generateOnPeer: ((peerIp: String, prompt: String, onToken: (String) -> Unit) -> String?)? = null

    /** What is wired up here, for a diagnostics page. */
    fun describe(): String = buildString {
        append("coin ")
        append(if (coin == null) "absent -- debts cannot be settled here" else "present")
        append(", miner ")
        append(if (mining == null) "absent" else if (isMining()) "running " + miningCoin() else "idle")
        append(", peer inference ")
        append(if (generateOnPeer == null) "absent" else "available")
    }
}
