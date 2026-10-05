package com.prism.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Whether a device is actually ON the meshnet, as opposed to merely on the same Wi-Fi.
 *
 * ## The distinction this exists to make, and why it was missing
 *
 * Prism's mesh transport finds peers by UDP broadcast on the local network. That is the right way to
 * BOOTSTRAP -- it is how two devices discover each other with no server and no configuration -- but it
 * means "a peer Prism can see" and "a peer on the meshnet" were the same set, and several things that
 * should have required the second were satisfied by the first.
 *
 * Trusted-device pairing was one of them, and it is the one that matters. Pairing hands over text
 * messages, browsing history, clipboards and, with the wallet kind, a recovery phrase. Any laptop on a
 * café network could appear in the picker and send an offer. The pairing code is what stopped that offer
 * from succeeding, and a six-character code is a reasonable second line of defence -- it should never
 * have been the first.
 *
 * ## What counts as the overlay
 *
 * ONE RULE: an address inside [SUBNET]. Not a guess and not a new convention -- it is the subnet the rest
 * of Prism already treats as the mesh:
 *
 *  - `WireGuardInterface` gives itself 10.8.0.1/24 and hands peers 10.8.0.2 upwards.
 *  - [TunnelRouter] routes exactly that /24 into the adapter and nothing else.
 *  - Android's `PrismTunnelEngine.routeOutboundPacket` matches 10.8.0.x to decide what goes to the mesh.
 *
 * So the overlay already had a definition in three places. This is the fourth, and the one the security
 * decisions read.
 *
 * ## Why not "is the tunnel up"
 *
 * Because a tunnel being up says nothing about who is on the other side of it, and because both devices
 * have to be on the SAME overlay for pairing to mean anything. An address in the mesh subnet is evidence
 * about the peer; a flag on this device is evidence about this device. Pairing needs the former.
 *
 * ## The honest cost
 *
 * PAIRING NOW REQUIRES A TUNNEL, where before it worked on bare Wi-Fi. That is the point of the change
 * and it is a real loss of convenience: two phones on a home network can no longer pair without one of
 * them serving the mesh. [explain] exists so the UI can say exactly that rather than showing an empty
 * list, because "no devices found" for a user who can see the other device in the room is the worst
 * possible way to communicate a policy.
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

    /** Whether an address is inside the overlay. */
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

    /** This device's overlay address, or null when it is not on the overlay. */
    fun overlayAddress(): String? = localAddresses().firstOrNull { isOverlayAddress(it) }

    /** Whether this device is on the overlay at all. */
    fun isOnOverlay(): Boolean = overlayAddress() != null

    /**
     * Whether pairing with [peerIp] is allowed.
     *
     * BOTH ENDS MUST BE ON THE OVERLAY. Checking only the peer would let a device that is not itself on
     * the mesh accept an offer from one that is, which is the same hole from the other side.
     */
    fun mayPairWith(peerIp: String): Boolean = isOnOverlay() && isOverlayAddress(peerIp)

    /**
     * Why pairing is unavailable, in a sentence a user can act on, or empty when it is available.
     *
     * Separate from [mayPairWith] because a boolean cannot distinguish "you are not on the mesh" from
     * "that device is not", and those have completely different fixes.
     */
    fun explain(peerIp: String? = null): String {
        if (!isOnOverlay()) {
            return "This device is not on the Prism meshnet. Pairing carries messages, history, " +
                "clipboards and possibly a wallet phrase, so it is only offered over the mesh -- not " +
                "to whatever else happens to be on this Wi-Fi. Connect to a Prism server, or serve the " +
                "mesh from this device, and the picker will fill."
        }
        if (peerIp != null && !isOverlayAddress(peerIp)) {
            return peerIp + " is on this network but not on the meshnet, so it cannot be paired with. " +
                "It has to join the mesh first."
        }
        return ""
    }

    /** One line for a diagnostics page. */
    fun describe(): String {
        val address = overlayAddress()
        return if (address == null) {
            "not on the overlay (" + localAddresses().joinToString(", ").ifBlank { "no addresses" } + ")"
        } else {
            "on the overlay as " + address
        }
    }

    /** Filters a discovered peer list down to those actually on the overlay. */
    fun overlayPeers(peers: Collection<String>): List<String> = peers.filter { isOverlayAddress(it) }
}
