package com.prism.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Whether a device is on the Prism meshnet, as opposed to merely on the same Wi-Fi.
 *
 * ## What went wrong before
 *
 * Membership used to require a 10.8.0.x address on both ends. That is the subnet the WireGuard tunnel
 * uses, and it is a real overlay when that tunnel is the transport -- but it is not what Prism's mesh
 * actually is. A phone serving the mesh is discovered and reached at its LAN address; its own VPN
 * interface is 10.7.0.2/30, and a desktop client that joined the phone has no 10.8.0.x address at all.
 * Both devices were therefore told they were "not on a meshnet" while they were sitting on a working
 * one, and the trusted-device picker listed nobody.
 *
 * ## The rule
 *
 * A device is on the meshnet when its mesh transport is running, or when it holds an address inside
 * [SUBNET]. A peer is on the meshnet when it is one of the peers that transport can currently reach,
 * or when it is at an overlay address.
 *
 * That is deliberately the same question the rest of Prism answers with [MeshTransport.isOnMesh]. The
 * mesh is unauthenticated and this is a feature for a LAN the user controls -- the README says so, and
 * pairing still requires the short code shown on the other device before anything is shared. The old
 * rule did not add authentication; it only made the feature unable to see the devices it was for.
 *
 * The WireGuard subnet remains a sufficient condition because a device on that overlay is reachable
 * even before a heartbeat has been exchanged.
 */
object MeshMembership {

    /** The mesh overlay subnet, as its first three octets. 10.8.0.0/24. */
    val SUBNET = Triple(10, 8, 0)

    /**
     * Overridden only by tests.
     *
     * A var rather than a parameter on every function because the callers are deep inside the pairing
     * handshake, and threading a test hook through six of them would put test scaffolding into the
     * security path.
     */
    var addressesOverride: (() -> List<String>)? = null

    /** Whether the mesh transport is live. Cheap: both platforms answer from a field. */
    fun isMeshRunning(): Boolean = runCatching { MeshTransport.isOnMesh() }.getOrDefault(false)

    /** Every IPv4 address this device holds, from every interface that is up. */
    private fun localAddresses(): List<String> {
        addressesOverride?.let { return runCatching { it() }.getOrDefault(emptyList()) }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { intf -> intf.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    /** Whether an address is inside the WireGuard overlay. */
    fun isOverlayAddress(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        val c = parts[2].toIntOrNull() ?: return false
        // The fourth octet is not checked beyond being a number: .0 and .255 are not valid hosts, but a
        // packet claiming one is malformed rather than off-mesh, and the caller drops it either way.
        if (parts[3].toIntOrNull() == null) return false
        return a == SUBNET.first && b == SUBNET.second && c == SUBNET.third
    }

    /** This device's overlay address, or null when it holds none. */
    fun overlayAddress(): String? = localAddresses().firstOrNull { isOverlayAddress(it) }

    /** Whether this device is on the meshnet at all. */
    fun isOnMeshnet(): Boolean = isMeshRunning() || overlayAddress() != null

    /**
     * Whether [peerIp] is a device the user could pair with.
     *
     * The peer must be on this meshnet and be reachable through it: either a live mesh peer, or an
     * address inside the overlay. A device that merely shares the Wi-Fi but runs no Prism mesh is
     * neither, which is the distinction that matters.
     */
    fun isMeshPeer(peerIp: String): Boolean {
        if (!isOnMeshnet()) return false
        if (isOverlayAddress(peerIp)) return true
        val live = runCatching { MeshTransport.peers() }.getOrDefault(emptyList())
        return live.any { it == peerIp }
    }

    /**
     * Whether pairing with [peerIp] is allowed.
     *
     * BOTH ENDS MUST BE ON THE MESHNET. The heartbeat gate in [isMeshPeer] is what makes the peer's
     * side true; a peer that is not answering is not reachable to send an offer to.
     */
    fun mayPairWith(peerIp: String): Boolean = isOnMeshnet() && isMeshPeer(peerIp)

    /**
     * Why pairing is unavailable, in a sentence a user can act on, or empty when it is available.
     *
     * Separate from [mayPairWith] because a boolean cannot distinguish "you are not on the meshnet"
     * from "that device is not", and those have completely different fixes.
     */
    fun explain(peerIp: String? = null): String {
        if (!isOnMeshnet()) {
            return "This device is not on the Prism meshnet. Pairing carries messages, history, " +
                "clipboards and possibly a wallet phrase, so it is only offered over the mesh. Turn " +
                "the mesh on, or start the VPN server, and the picker will fill."
        }
        if (peerIp != null && !isMeshPeer(peerIp)) {
            return peerIp + " is on this network but is not on your meshnet, so it cannot be paired " +
                "with. It has to run Prism with the mesh enabled on the same network."
        }
        return ""
    }

    /** One line for a diagnostics page. */
    fun describe(): String {
        val address = overlayAddress()
        return when {
            address != null && isMeshRunning() -> "on the meshnet; overlay address " + address
            address != null -> "on the overlay as " + address + "; mesh transport not running"
            isMeshRunning() -> "on the meshnet at " +
                runCatching { MeshTransport.localIp() }.getOrDefault("an unknown address")
            else -> "not on the meshnet (" +
                localAddresses().joinToString(", ").ifBlank { "no addresses" } + ")"
        }
    }

    /**
     * Filters a discovered peer list down to the devices on this meshnet.
     *
     * Empty when this device is not on a meshnet: a device whose transport is off has no business
     * offering a pairing, and the picker says why rather than showing an unexplained empty list.
     */
    fun meshPeers(discovered: Collection<String>): List<String> =
        discovered.filter { isMeshPeer(it) }.distinct()
}
