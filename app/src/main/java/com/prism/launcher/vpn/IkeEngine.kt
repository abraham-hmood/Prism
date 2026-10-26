package com.prism.launcher.vpn

import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * IKE detection, and nothing else.
 *
 * ## Why the fake responder is gone
 *
 * This used to answer an IKE_SA_INIT with a hand-built packet whose Diffie-Hellman public value
 * was 256 random bytes. Real IKEv2 needs that value to be `g^secret mod p` for a group the client
 * offered, and the client then proves itself with an AUTH payload keyed by the resulting shared
 * secret. Random bytes cannot be a public value, so no client could ever finish the exchange --
 * but the responder still claimed to have answered, which cost debugging time on every device that
 * tried. Sending a well-formed-looking packet that cannot possibly work is worse than sending
 * nothing, because "the server replied" moves the search for the fault to the client.
 *
 * ## What a real one would need
 *
 * An IKEv2 stack: MODP/ECP groups and their primes, SA payload negotiation, SK (encrypted) payloads,
 * AUTH with PSK or EAP-MSCHAPv2 for a Windows client, CHILD_SA for the tunnel, and a userland ESP
 * or a kernel policy to carry the traffic. That is a large, security-sensitive subsystem, not a
 * patch, and half of it is exactly the kind of code that cannot be tested without real peers. Until
 * somebody ships it, Prism does not advertise IKEv2: the settings screen says so and this responder
 * says so in the log instead of pretending.
 *
 * The detection itself is still useful -- it is how the multiplexer tells IKE apart from L2TP on a
 * shared port and how the UI can say "that client is speaking IKEv2, which this build does not
 * serve".
 */
class IkeEngine(
    private val psk: String,
    private val user: String,
    private val pass: String,
) {

    private var warned = false

    fun handlePacket(
        data: ByteArray,
        address: InetAddress,
        port: Int,
        socket: DatagramSocket,
        receivingPort: Int,
        hasMarker: Boolean,
    ) {
        if (data.size < 28) return
        val version = data[17].toInt() and 0xF0
        val exchange = data[18].toInt() and 0xFF
        if (version != 0x20) {
            if (!warned) {
                warned = true
                Log.i(TAG, "IKEv1 traffic from $address; Prism does not serve IKEv1.")
            }
            return
        }
        if (exchange == 34 && !warned) {
            warned = true
            Log.w(
                TAG,
                "An IKEv2 client at $address:$port is doing an IKE_SA_INIT. Prism has no IKEv2 " +
                    "stack, so it cannot complete this exchange (no response is sent). Use the " +
                    "Prism proxy tunnel or WireGuard instead."
            )
        }
    }

    private companion object {
        const val TAG = "PrismIke"
    }
}
