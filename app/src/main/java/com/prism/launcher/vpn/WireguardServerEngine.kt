package com.prism.launcher.vpn

import android.content.Context
import android.util.Log
import com.prism.core.wg.WireGuardNoise
import com.prism.launcher.browser.PrivateDnsVpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * A userspace WireGuard server.
 *
 * ## What this used to be
 *
 * It answered any handshake initiation with ninety-two near-zero bytes and then injected every
 * datagram whose first byte was 4 straight into the VPN. No client could connect -- the response
 * carries no usable keys -- and any device that could reach UDP 51820 could put arbitrary IP
 * packets inside the tunnel with no key at all. It was an open door wearing a server's clothes.
 *
 * ## What it is now
 *
 * A real responder: Noise_IKpsk2 over Curve25519 with ChaCha20-Poly1305 and BLAKE2s, implemented in
 * `:core` (`WireGuardNoise`) so the same code is unit-tested on the JVM. This class is the part that
 * touches the network and the operating system:
 *
 *  - verifies MAC1 before doing any expensive work, so junk aimed at a server that is not there
 *    costs one BLAKE2s and nothing else;
 *  - authenticates the initiation, which is what actually decides who may join -- only devices
 *    holding the server's public key AND a private key whose static public key the handshake
 *    proves can complete it;
 *  - allocates each accepted peer its own address in 10.8.0.0/24 instead of handing everybody
 *    10.8.0.2;
 *  - rejects replayed, truncated and forged transport packets (the counter must increase, and the
 *    Poly1305 tag must verify) rather than injecting whatever arrives;
 *  - keeps sessions keyed by the receiver index the wire names, which is how WireGuard addresses
 *    peers -- there is no peer IP in a transport header to trust.
 *
 * ## Deliberately not here
 *
 * Cookie replies (they need the MAC2/cookie path and are an anti-DoS optimisation, not a
 * correctness one), persistent keepalives, and rekeying driven by timers: [WireGuardNoise.Session]
 * exposes its age and counter so a caller can decide to re-handshake, and until it does the session
 * simply expires after three minutes of not being used to send.
 *
 * ## The honest limit
 *
 * The handshake and transport are real, but a client is only routed if this device also has the
 * peer's address in its route table. On a phone that is the VPN interface's /24 plus whatever the
 * tunnel engine injects; a peer that completes the handshake can send, and traffic this device
 * sends to its assigned address is sealed and sent back. General L3 forwarding between peers is the
 * tunnel engine's job, not this class's.
 */
class WireguardServerEngine(private val context: Context) {

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val byIndex = ConcurrentHashMap<Int, PeerSession>()
    private var socket: DatagramSocket? = null

    /** One connected peer: where to send, which address it was given, and its transport keys. */
    class PeerSession(
        val publicKeyHex: String,
        val endpoint: InetAddress,
        val port: Int,
        val virtualIp: String,
        val session: WireGuardNoise.Session,
    ) {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        val localIndex: Int get() = session.localIndex
        val remoteIndex: Int get() = session.remoteIndex
    }

    // ── Server identity ────────────────────────────────────────────────────

    private val prefs by lazy { context.getSharedPreferences("prism_wg_server", Context.MODE_PRIVATE) }

    /**
     * The server's static key pair, created once and kept.
     *
     * Derived rather than stored as two values so they cannot disagree: the public key is always
     * X25519 of the stored private key. A client's configuration has to contain this public key,
     * which is why [publicKeyBase64] is what the settings screen shows and copies.
     */
    private fun loadPrivateKey(): ByteArray {
        val stored = prefs.getString(KEY_PRIVATE, null)
        val decoded = stored?.let { WireGuardNoise.decodeKey(it) }
        if (decoded != null) return decoded
        return WireGuardNoise.generatePrivateKey().also {
            prefs.edit().putString(KEY_PRIVATE, WireGuardNoise.encodeKey(it)).apply()
        }
    }

    fun publicKeyBase64(): String = WireGuardNoise.encodeKey(keys().second)

    fun listenPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setListenPort(port: Int) {
        prefs.edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
    }

    /** Rotating the key disconnects everybody, which is the point of a rotate. */
    fun rotateKeys() {
        prefs.edit()
            .putString(KEY_PRIVATE, WireGuardNoise.encodeKey(WireGuardNoise.generatePrivateKey()))
            .apply()
        sessions.clear()
        byIndex.clear()
        identity = null
    }

    /**
     * The key pair, cached.
     *
     * Cached rather than read per packet because an initiation arrives on the public port and
     * reading preferences and deriving a public key for each one is work an attacker chooses for
     * this device. Cleared by [rotateKeys], which is the only thing that can change it.
     */
    private var identity: Pair<ByteArray, ByteArray>? = null

    @Synchronized
    private fun keys(): Pair<ByteArray, ByteArray> {
        identity?.let { return it }
        val priv = loadPrivateKey()
        val pair = priv to WireGuardNoise.publicKey(priv)
        identity = pair
        return pair
    }

    // ── Packet handling ────────────────────────────────────────────────────

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        if (data.isEmpty()) return
        this.socket = socket

        when (data[0]) {
            WireGuardNoise.TYPE_INITIATION -> handleInitiation(data, address, port, socket)
            WireGuardNoise.TYPE_TRANSPORT -> handleTransport(data, address, port)
            else -> Log.d(TAG, "Ignoring WireGuard message type ${data[0]} from $address:$port")
        }
    }

    private fun handleInitiation(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        val (private, public) = keys()
        val accepted = WireGuardNoise.accept(data, private, public)
        if (accepted == null) {
            // One line, not a stack trace: this is the public port, and a scanner produces these
            // continuously. Whoever is legitimately trying to connect gets the reason from their
            // own client, which is where it belongs.
            Log.d(TAG, "Rejected a handshake from $address:$port (bad MAC1, key or timestamp)")
            return
        }

        val peerKey = accepted.remoteStatic.joinToString("") { "%02x".format(it) }

        // Same key again: keep its address, replace its keys. A client that restarts re-handshakes
        // and would otherwise accumulate one address per attempt.
        val existing = sessions.values.firstOrNull { it.publicKeyHex == peerKey }
        val assigned = existing?.virtualIp ?: nextFreeAddress()
        if (assigned == null) {
            Log.w(TAG, "Refusing ${address.hostAddress}: the 10.8.0.0/24 pool is full")
            return
        }

        val peer = PeerSession(peerKey, address, port, assigned, accepted.session)
        // The old session's index must go, or a transport packet addressed to it would still be
        // accepted after the peer re-handshook -- which is exactly the replay window the counter
        // check exists to prevent.
        existing?.let {
            sessions.remove(it.virtualIp)
            byIndex.remove(it.localIndex)
        }
        sessions[assigned] = peer
        byIndex[peer.localIndex] = peer

        val packet = DatagramPacket(accepted.response, accepted.response.size, address, port)
        runCatching { socket.send(packet) }
            .onSuccess {
                Log.i(TAG, "WireGuard peer $peerKey joined as $assigned from $address:$port")
                registerInVpn(assigned)
            }
            .onFailure { Log.e(TAG, "Could not send handshake response to $address", it) }
    }

    private fun handleTransport(data: ByteArray, address: InetAddress, port: Int) {
        val index = WireGuardNoise.indexOf(data) ?: return
        val peer = byIndex[index]
        if (peer == null) {
            Log.d(TAG, "Dropping transport packet for unknown session 0x${index.toString(16)} from $address")
            return
        }
        // ENDPOINT ROAMING IS ALLOWED, SOURCE SPOOFING IS NOT. WireGuard lets a peer's packets
        // arrive from a new address after a NAT rebind; the tag is what proves it. A packet that
        // fails to open is dropped and the endpoint is left alone, so a forged packet cannot move
        // the session to an attacker's address.
        val plain = peer.session.open(data) ?: return
        peer.lastSeen = System.currentTimeMillis()
        if (peer.endpoint != address || peer.port != port) {
            Log.i(TAG, "WireGuard peer ${peer.virtualIp} roamed to $address:$port")
            val moved = PeerSession(peer.publicKeyHex, address, port, peer.virtualIp, peer.session).also {
                it.lastSeen = peer.lastSeen
            }
            sessions[peer.virtualIp] = moved
            byIndex[peer.localIndex] = moved
        }
        if (plain.isNotEmpty()) PrivateDnsVpnService.injectPacket(plain)
    }

    /** Wraps and sends one IP packet to the peer that owns [peerIp]. */
    fun encapsulateAndSend(packet: ByteArray, peerIp: String) {
        val peer = sessions[peerIp] ?: sessions.values.firstOrNull { it.virtualIp == peerIp } ?: return
        val sealed = peer.session.seal(packet)
        val target = socket
        val datagram = DatagramPacket(sealed, sealed.size, peer.endpoint, peer.port)
        if (target != null && !target.isClosed) {
            runCatching { target.send(datagram) }.onFailure {
                Log.e(TAG, "Failed to send to ${peer.virtualIp} at ${peer.endpoint}", it)
            }
            return
        }
        // No socket to reuse (the multiplexer has not received anything yet): a short-lived one,
        // protected from our own tunnel so it can actually leave the device.
        runCatching {
            DatagramSocket().apply {
                PrivateDnsVpnService.protectSocket(this)
                send(datagram)
                close()
            }
        }.onFailure { Log.e(TAG, "Failed to send to ${peer.virtualIp}", it) }
    }

    fun peers(): List<PeerSession> = sessions.values.sortedBy { it.virtualIp }

    fun forget(peerIp: String) {
        val peer = sessions.remove(peerIp) ?: return
        byIndex.remove(peer.localIndex)
    }

    fun stop() {
        sessions.clear()
        byIndex.clear()
        socket = null
    }

    // ── Addressing ─────────────────────────────────────────────────────────

    /**
     * The next free host address in 10.8.0.0/24.
     *
     * .1 is this server, so allocation starts at .2 and skips it. Returns null when every host is
     * taken rather than reusing an address that is in use: two peers with one address is a routing
     * loop that presents as "the tunnel is up and nothing loads".
     */
    private fun nextFreeAddress(): String? {
        for (host in 2..254) {
            val candidate = "10.8.0.$host"
            if (!sessions.containsKey(candidate)) return candidate
        }
        return null
    }

    private fun registerInVpn(virtualIp: String) {
        // The tunnel already routes the whole /24, so there is nothing to add per peer. The call is
        // kept as the one place to put it if that ever stops being true.
        if (!virtualIp.startsWith("10.8.0.")) {
            Log.w(TAG, "Peer address $virtualIp is outside the tunnel subnet")
        }
    }

    private companion object {
        const val TAG = "PrismWG"
        const val KEY_PRIVATE = "server_private_key"
        const val KEY_PORT = "listen_port"
        const val DEFAULT_PORT = 51820
    }
}
