package com.prism.launcher.mesh

import com.prism.core.MeshCore
import com.prism.core.PrismPlatform

/**
 * Puts the mesh market on the wire for any platform that uses [MeshCore]. PHASE 98.
 *
 * ## Why registration is separate from the features
 *
 * Android does not use this. `PrismMeshService` has its own `when (command)` dispatch and calls the very
 * same objects, so registering them with [MeshCore] as well would mean a second UDP socket on 8081 in
 * one process -- two listeners fighting over the same port, which is the exact failure the mesh service
 * comment warns about. So the features know nothing about how they are reached, and each platform wires
 * the transport it owns.
 *
 * ## The six opcodes, and which are worth doing off the dispatch thread
 *
 * Three are cheap: parsing a gossiped capability list, a port number or a debt announcement is a JSON
 * decode into a map. Three are not, and are handed to a thread:
 *
 *  - AN RPC REQUEST starts a server, allocates buffers and begins listening.
 *  - Anything that touches the coin node reads or writes a chain.
 *
 * The dispatch thread is the one every other peer's packets queue behind, so work on it is not slowness,
 * it is a device that stops answering heartbeats and drops off its own mesh.
 *
 * ## Why every handler returns true
 *
 * [MeshCore.OpcodeHandler] returns a Boolean so an UNCLAIMED opcode can be counted separately from a
 * handler that threw. All six of these claim their opcode unconditionally: a payload that fails to parse
 * is the feature's own business and is logged where it happens, and reporting it as unclaimed would make
 * the diagnostics say this device does not support a feature it does support.
 */
object MeshMarket {

    private const val TAG = "PrismMeshMarket"

    @Volatile
    private var installed = false

    /**
     * Registers every market opcode with [MeshCore].
     *
     * Idempotent, because a host that called it twice would otherwise replace live handlers mid-flight.
     */
    fun install(): Boolean {
        if (installed) return false
        installed = true

        // Models another device is selling.
        MeshCore.register(P2pModelListings.OPCODE_LISTINGS) { peerIp, payload ->
            P2pModelListings.ingestFromPeer(peerIp, payload)
            true
        }

        // Coin swaps another device is offering.
        MeshCore.register(P2pCoinOffers.OPCODE_OFFERS) { peerIp, payload ->
            P2pCoinOffers.ingestFromPeer(peerIp, payload)
            true
        }

        // What a peer can contribute to the compute pool, and what it charges.
        MeshCore.register(MeshComputeRegistry.OPCODE_COMPUTE_ANNOUNCE) { peerIp, payload ->
            MeshComputeRegistry.ingestFromPeer(peerIp, payload)
            true
        }

        // A peer asking this device to lend it compute. Off-thread: it starts a server.
        MeshCore.register(MeshComputeRegistry.OPCODE_RPC_REQUEST) { peerIp, _ ->
            Thread({ MeshInference.onRpcRequest(peerIp) }, "compute-rpc-request").start()
            true
        }

        // A peer saying its RPC server is up, and on which port.
        MeshCore.register(MeshComputeRegistry.OPCODE_RPC_READY) { peerIp, payload ->
            MeshComputeRegistry.ingestRpcReady(peerIp, payload)
            true
        }

        // Somebody announcing that they owe somebody else. Recorded even when it is not about this
        // device: a market where debts are public is one where a peer can decline work for a device
        // that already owes three others.
        // ── The paid market (PHASE 108) ────────────────────────────────────
        //
        // These four were Android-only until the store, the ledger, the payments and the refunds
        // moved to :core -- every one of them had a `Context` that was a preferences handle and
        // nothing else. Registered here rather than in a desktop-only file because the handlers are
        // the shared ones: a desktop and a phone answer a refund request with the same code.

        MeshCore.register(com.prism.launcher.ModelListingScanner.OPCODE_SALE_APPROVED) { _, payload ->
            // Off the socket thread: the buyer re-runs its own public-availability lookups before
            // paying, which is two blocking HTTP calls, and the mesh receive loop must not wait on
            // the network to answer the next datagram.
            Thread(
                { runCatching { com.prism.launcher.ModelListingScanner.onSaleApproved(payload) } },
                "model-sale-approved",
            ).apply { isDaemon = true }.start()
            true
        }

        MeshCore.register(com.prism.launcher.ModelListingScanner.OPCODE_VERIFY_NOW) { peerIp, payload ->
            Thread(
                {
                    runCatching {
                        com.prism.launcher.ModelListingScanner.onVerifyRequest(payload, peerIp)
                    }
                },
                "model-verify-request",
            ).apply { isDaemon = true }.start()
            true
        }

        MeshCore.register(com.prism.launcher.ModelRefunds.OPCODE_REFUND_REQUEST) { _, payload ->
            Thread(
                { runCatching { com.prism.launcher.ModelRefunds.onRefundRequest(payload) } },
                "model-refund-request",
            ).apply { isDaemon = true }.start()
            true
        }

        // ── The chain (PHASE 87) ───────────────────────────────────────────
        //
        // PrismCoin's own gossip. ON THE SAME SOCKET as everything else, which is the point of the
        // opcode byte: a second chain-specific listener would be a second UDP port to open, a second
        // thing to firewall, and a second place for the peer list to be wrong.
        MeshCore.register(com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_HEAD) { _, payload ->
            runCatching {
                com.prism.launcher.wallet.psc.PrismCoinNode.onMeshMessage(
                    com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_HEAD, payload,
                )
            }.getOrDefault(false)
        }
        MeshCore.register(com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_BLOCK) { _, payload ->
            runCatching {
                com.prism.launcher.wallet.psc.PrismCoinNode.onMeshMessage(
                    com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_BLOCK, payload,
                )
            }.getOrDefault(false)
        }
        MeshCore.register(com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_TX) { _, payload ->
            runCatching {
                com.prism.launcher.wallet.psc.PrismCoinNode.onMeshMessage(
                    com.prism.launcher.wallet.psc.PrismCoinNode.OPCODE_TX, payload,
                )
            }.getOrDefault(false)
        }

        MeshCore.register(MeshComputeRegistry.OPCODE_DEBT_ANNOUNCE) { _, payload ->
            ComputeDebtLedger.ingestDebtAnnouncement(payload)
            true
        }

        PrismPlatform.log.info(
            TAG,
            "The mesh market is on the wire: listings, coin offers, compute capability, RPC and debts.",
        )
        return true
    }

    /**
     * Announces what this device offers, if anything.
     *
     * CALLED ON A TIMER BY THE HOST, not once at startup, and that is not belt-and-braces. Mesh state is
     * gossip with an expiry: a peer that has not heard from this device in ten minutes drops it from the
     * market, so a device that announced only at boot disappears from everyone else's list while still
     * believing it is listed.
     */
    fun announce() {
        if (!com.prism.core.MeshTransport.isOnMesh()) return
        runCatching { MeshComputeRegistry.announce() }
            .onFailure { PrismPlatform.log.warn(TAG, "Could not announce compute capacity: " + it.message) }
    }

    /** What is on the wire and what the market can see, for a diagnostics page. */
    fun describe(): String = buildString {
        append(if (installed) "registered" else "not registered")
        append(" · ")
        append(MeshComputeRegistry.all().size)
        append(" compute peer(s), ")
        append(P2pModelListings.getAll().size)
        append(" model listing(s), ")
        append(P2pCoinOffers.getAll().size)
        append(" coin offer(s)")
    }
}
