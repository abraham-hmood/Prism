package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.launcher.ModelPayments
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.MineableCoins
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.MiningService
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import com.prism.launcher.vpn.PrismSocket
import java.math.BigInteger
import java.net.InetSocketAddress

/**
 * Everything the mesh market needs that only Android can supply. PHASE 98.
 *
 * ## Why this file exists
 *
 * The compute market, the debt ledger, the model listings and the coin offers all moved into `:core`
 * so the desktop runs the same implementation the phone does. Four things could not move with them --
 * the Prism Coin node, the miner, Prism's own socket, and the transport itself -- and rather than
 * leaving the features in `:app` because of four dependencies, the features declare what they need and
 * this installs it.
 *
 * ## What is NOT delegated, and why that is deliberate
 *
 * THE TRANSPORT. [MeshTransport] is pointed at `PrismMeshService`, not at `MeshCore`. Android keeps its
 * own implementation of the wire protocol for the reason `PrismMeshService` documents at length: the
 * format is the contract, two implementations of it interoperate, and refactoring the component every
 * mesh feature depends on -- without a second device to test against -- would risk the working side to
 * tidy the new one. This file is what lets a `:core` feature reach that unchanged service.
 */
object AndroidMeshBridges {

    private const val TAG = "PrismMeshBridge"

    /** Called once, from `PrismApp.onCreate`. */
    fun install(context: Context) {
        installTransport()
        installCoin(context)
        installMining(context)
        installSocket()
        PrismPlatform.log.info(TAG, "Mesh bridges installed: " + MeshBridges.describe())
    }

    /**
     * Points the portable features at Android's own mesh service.
     *
     * ONE CALL RATHER THAN FIVE ASSIGNMENTS, so a platform cannot install half a transport -- which
     * would be a feature broadcasting through one implementation and counting peers through another.
     */
    private fun installTransport() {
        MeshTransport.install(
            isOnMesh = { PrismMeshService.isOnMesh() },
            peerCount = { PrismMeshService.getPeerCount() },
            peers = { PrismMeshService.activePeerIps() },
            sendToPeer = { ip, opcode, payload -> PrismMeshService.sendToPeer(ip, opcode, payload) },
            broadcast = { opcode, payload, source ->
                PrismMeshService.broadcastToOthers(opcode, payload, source)
            },
            // The same helper the service itself uses; there is no per-service address.
            localIp = { com.prism.core.MeshUtils.getLocalMeshIp() },
        )
    }

    /**
     * The coin half: what this device holds, what a peer holds, and how to pay.
     *
     * THE TRANSACTION IS BUILT AND SIGNED HERE, not in the ledger, and the nonce is why. A payment is
     * signed against the sender's CURRENT nonce, which only the local chain knows; a ledger that built
     * transactions somewhere else would sign against a stale nonce and produce rejections that look
     * exactly like insufficient funds. So the ledger decides WHAT to pay and this decides HOW.
     */
    private fun installCoin(context: Context) {
        MeshBridges.coin = object : MeshBridges.Coin {

            override fun address(): String? = runCatching { ModelPayments.address() }.getOrNull()

            override fun spendable(): BigInteger =
                runCatching { ModelPayments.spendable() }.getOrDefault(BigInteger.ZERO)

            override fun balanceOf(address: String): BigInteger =
                runCatching { PrismCoinNode.chain.balanceOf(address) }.getOrDefault(BigInteger.ZERO)

            override fun pay(to: String, amountMinor: BigInteger, note: String): Boolean {
                val coin = CoinRegistry.bySymbol(PrismCoinConsensus.SYMBOL) ?: return false
                val seed = WalletVault.seed() ?: return false
                val account = runCatching { WalletVault.account(coin, seed) }.getOrNull() ?: return false

                val chain = PrismCoinNode.chain
                if (chain.balanceOf(account.address) < amountMinor.add(FEE)) return false

                val tx = PscTransaction(
                    from = account.address,
                    to = to,
                    amount = amountMinor,
                    fee = FEE,
                    nonce = chain.nonceOf(account.address),
                    publicKey = WalletCrypto.compressedPublicKey(account.privateKey),
                    note = note,
                ).sign(account.privateKey)

                val result = chain.submit(tx)
                if (result is PscChain.TxResult.Rejected) {
                    PrismPlatform.log.warn(TAG, "Compute payment rejected: " + result.reason)
                    return false
                }
                PrismCoinNode.broadcastTransaction(tx)
                PrismCoinNode.save()
                return true
            }
        }
    }

    /**
     * The miner.
     *
     * PRISM COIN ONLY, AND THE SIGNATURE SAYS SO. The old code passed the coin, the pool host, the port
     * and the algorithm through from the caller -- all of which were always the same values, because
     * Prism Coin is mined over the mesh rather than through a pool. Spelling them out invited a future
     * caller to change one and produce a miner paying into a chain the creditors were never promised.
     */
    private fun installMining(context: Context) {
        MeshBridges.mining = object : MeshBridges.Mining {

            override val running: Boolean get() = MiningService.State.running

            override val coin: String get() = MiningService.State.coin

            override fun startPrismCoin(address: String, threads: Int): Boolean = runCatching {
                val spec = MineableCoins.all().firstOrNull {
                    it.symbol.equals(PrismCoinConsensus.SYMBOL, ignoreCase = true)
                }
                MiningService.start(
                    context = context,
                    coin = PrismCoinConsensus.SYMBOL,
                    // Empty by design rather than missing: Prism Coin has no pool.
                    host = spec?.poolHost.orEmpty(),
                    port = spec?.poolPort ?: 0,
                    address = address,
                    algorithm = spec?.algorithm ?: MiningAlgorithms.SHA256D,
                    threads = threads,
                )
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Prism's socket, which tells the VPN which mesh name a connection is for before it connects.
     *
     * THE HOST HINT IS THE WHOLE POINT. On Android a model download goes through the tunnel, and the
     * tunnel routes by name; a plain socket to the peer's address would be routed as ordinary traffic.
     * The desktop default in [MeshBridges] is that plain socket, which is correct there because there
     * is no per-application tunnel to route around.
     */
    private fun installSocket() {
        MeshBridges.connect = { peerIp, port, timeoutMs, hostHint ->
            runCatching {
                PrismSocket().apply {
                    setHostHint(hostHint)
                    connect(InetSocketAddress(peerIp, port), timeoutMs)
                }
            }.getOrNull()
        }
    }

    /**
     * The fee a compute payment pays.
     *
     * ZERO, WHICH IS WHAT THE LEDGER USED BEFORE IT MOVED, and is kept exactly rather than tidied into
     * a "sensible" non-zero number. Prism Coin's own consensus decides what a transaction costs; the
     * ledger adding a fee of its own would make a settlement that used to succeed start failing for
     * insufficient funds at the margin.
     */
    private val FEE: BigInteger = BigInteger.ZERO
}
