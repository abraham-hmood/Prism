package com.prism.core

/**
 * The one way a feature reaches the mesh, whichever platform it is running on.
 *
 * ## The problem this solves
 *
 * There are two implementations of the mesh transport and that is deliberate: `PrismMeshService` on
 * Android and [MeshCore] on the desktop, speaking the identical wire protocol -- `"PRISM"` + an opcode
 * byte + a UTF-8 payload on UDP 8081 -- so they interoperate without sharing code. The comment in
 * `PrismMeshService` explains why the working Android side was never refactored to delegate: the wire
 * format is the contract, and rewriting the component every mesh feature depends on, with no second
 * device to test against, would risk the working side to tidy the new one.
 *
 * That decision has a consequence nobody had to pay until now: A FEATURE CANNOT LIVE IN `:core` AND
 * TALK TO THE MESH. `PrismMeshService` is in `:app`, so anything in `:core` that wanted to broadcast had
 * to stay in `:app` too -- which is why the compute market, the model listings and the coin offers were
 * Android-only long after the transport underneath them was portable on both sides.
 *
 * This is the seam that fixes it. A feature calls [broadcast], [sendToPeer] and [peers]; the platform
 * decides what those mean, once, at startup. Nothing about the wire protocol changes, and neither
 * transport learns about the other.
 *
 * ## Why the default is MeshCore and not an error
 *
 * Because the desktop, the tests and any future host get a working mesh with no wiring at all, and
 * Android is the one platform that has to opt out. A default that threw would mean every unit test of
 * a mesh feature needed a transport installed before it could construct anything.
 *
 * ## What a feature must not assume
 *
 * THAT A BROADCAST ARRIVES. This is UDP on a local network: [broadcast] returns Unit, and the only
 * honest reading of it is "the datagram was handed to the socket". Every mesh feature in Prism is
 * built to re-announce on a timer rather than to trust one send, and anything that needs delivery
 * confirmed asks for a reply opcode.
 */
object MeshTransport {

    data class Node(
        val id: String,
        val name: String,
        val address: String,
        val meshPort: Int,
        val lastSeen: Long,
        val latency: Long,
        val serverPort: Int?,
        val serverProtocol: String?,
        val serverPublicKey: String?,
        val wireGuardPort: Int?,
    )

    /**
     * Whether this device is on the mesh at all.
     *
     * Features gate on this before doing expensive preparation, so it must be cheap and must never
     * block -- both implementations answer from a field.
     */
    var isOnMesh: () -> Boolean = { MeshCore.isOnMesh() }

    /** How many peers are visible right now. */
    var peerCount: () -> Int = { MeshCore.peerCount() }

    /** The peers, by IP. */
    var peers: () -> List<String> = { MeshCore.activePeerIps() }

    /** Live mesh nodes with the identity metadata they have announced. */
    var nodes: () -> List<Node> = { MeshCore.nodes() }

    /** Broadcasts a fresh local-network discovery request. */
    var discover: () -> Unit = { MeshCore.discover() }

    /** Evicts a live peer without persisting a deny rule. */
    var kickNode: (String) -> Boolean = { MeshCore.kickNode(it) }

    /** Persists a ban for the peer's Prism app identity and evicts it. */
    var banNode: (String) -> Boolean = { MeshCore.banNode(it) }

    /** Sends to one peer. */
    var sendToPeer: (peerIp: String, opcode: Byte, payload: String) -> Unit =
        { peerIp, opcode, payload -> MeshCore.sendToPeer(peerIp, opcode, payload) }

    /**
     * Sends to every peer except [sourceIp].
     *
     * THE EXCLUSION IS WHAT STOPS A GOSSIP STORM: a device that re-broadcast something back to the peer
     * it came from would have two devices trading the same payload until one of them stopped.
     */
    var broadcast: (opcode: Byte, payload: String, sourceIp: String?) -> Unit =
        { opcode, payload, sourceIp -> MeshCore.broadcastToOthers(opcode, payload, sourceIp) }

    /** This device's address on the mesh. */
    var localIp: () -> String = { MeshUtils.getLocalMeshIp() }

    /** Convenience for the common case of announcing to everybody. */
    fun announce(opcode: Byte, payload: String) = broadcast(opcode, payload, null)

    /**
     * Points every function at `PrismMeshService`, or at anything else shaped like it.
     *
     * Called once from `PrismApp`. Grouped into a single call rather than six assignments so a platform
     * cannot install half a transport -- which would be a feature that broadcasts through one
     * implementation and counts peers through another.
     */
    fun install(
        isOnMesh: () -> Boolean,
        peerCount: () -> Int,
        peers: () -> List<String>,
        sendToPeer: (String, Byte, String) -> Unit,
        broadcast: (Byte, String, String?) -> Unit,
        localIp: () -> String = { MeshUtils.getLocalMeshIp() },
        nodes: () -> List<Node> = { emptyList() },
        discover: () -> Unit = {},
        kickNode: (String) -> Boolean = { false },
        banNode: (String) -> Boolean = { false },
    ) {
        this.isOnMesh = isOnMesh
        this.peerCount = peerCount
        this.peers = peers
        this.sendToPeer = sendToPeer
        this.broadcast = broadcast
        this.localIp = localIp
        this.nodes = nodes
        this.discover = discover
        this.kickNode = kickNode
        this.banNode = banNode
        PrismPlatform.log.info(TAG, "The mesh transport is installed by the host platform.")
    }

    /** Which transport is in use, for a diagnostics page. */
    fun describe(): String =
        (if (isOnMesh()) "on the mesh" else "off the mesh") + ", " + peerCount() + " peer(s)"

    private const val TAG = "PrismMeshTransport"
}
