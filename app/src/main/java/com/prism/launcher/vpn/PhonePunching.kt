package com.prism.launcher.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.prism.launcher.PrismSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Hole punching for a phone that serves a mesh from mobile data.
 *
 * ON ONLY WHEN BOTH HOLD: the phone's only network is mobile data (no Wi-Fi, no Ethernet), and its
 * Prism role is Server. A client, bridge or relay dials out and never needs punching, and a server
 * on Wi-Fi uses its router's port forwarding as before -- so nothing here runs for them.
 *
 * While on, [HolePunch.Registrar] registers with every punching directory every two seconds FROM
 * THE WIREGUARD SOCKET ([socket], set by [VpnMultiplexer]); that keep-alive is what holds the
 * carrier's NAT mapping open for clients to punch through. See [HolePunch].
 */
object PhonePunching {

    private const val TAG = "PrismPunch"

    /** The WireGuard server's UDP socket, while the multiplexer has it bound. */
    @Volatile
    var socket: DatagramSocket? = null
        set(value) {
            field = value
            update()
        }

    @Volatile
    private var cellularOnly = false

    @Volatile
    private var context: Context? = null

    private val registrar by lazy {
        HolePunch.Registrar(
            token = PrismSettings.getOrCreateDirectoryToken(),
            name = { PrismSettings.getPrismNodeName().ifBlank { android.os.Build.MODEL.orEmpty() } },
            targets = { HolePunch.targets() },
            send = { bytes, to ->
                val target = socket
                if (target != null && !target.isClosed) target.send(DatagramPacket(bytes, bytes.size, to))
            },
        ).also { HolePunch.active = it }
    }

    /** Watches the networks and the role; safe to call more than once. */
    fun install(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recheck(connectivity)
            override fun onLost(network: Network) = recheck(connectivity)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = recheck(connectivity)
        }
        runCatching { connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), callback) }
            .onFailure { Log.w(TAG, "Could not watch the networks: ${it.message}") }
        recheck(connectivity)
        // The role can change without any network event; a slow look keeps the two in step.
        Thread({
            while (true) {
                runCatching { Thread.sleep(15_000) }.onFailure { return@Thread }
                runCatching { update() }
            }
        }, "prism-punch-role").apply { isDaemon = true; start() }
    }

    /**
     * Mobile data and nothing else. A VPN interface is not a network of its own for this purpose --
     * Prism's own tunnel is a VPN -- so it is skipped rather than counted.
     */
    private fun recheck(connectivity: ConnectivityManager) {
        val capabilities = runCatching {
            @Suppress("DEPRECATION") // allNetworks is the one call that lists every network on API 26+.
            connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
        }.getOrDefault(emptyList()).filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val cellular = capabilities.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val local = capabilities.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        val now = cellular && !local
        if (now != cellularOnly) {
            cellularOnly = now
            Log.i(TAG, if (now) "Mobile data only" else "Not on mobile data only")
        }
        update()
    }

    /** Starts or stops registering to match the network, the role and the WireGuard socket. */
    fun update() {
        val wanted = HolePunch.shouldPunch(cellularOnly, PrismSettings.getPrismVpnRole()) &&
            PrismSettings.getMeshEnabled() && socket?.isClosed == false
        if (wanted) registrar.start() else if (HolePunch.active != null) registrar.stop()
    }

    /** Consumes punch packets arriving on the WireGuard port; false for WireGuard's own. */
    fun onPacket(data: ByteArray, length: Int, from: InetSocketAddress): Boolean {
        if (!HolePunch.isPunchPacket(data, length)) return false
        if (HolePunch.active == null) return true
        return registrar.onPacket(data, length, from)
    }

    /** One line for Settings: whether punching is on, and the line client configs need. */
    fun status(): String {
        val active = HolePunch.active?.takeIf { it.isRunning() }
        return when {
            PrismSettings.getPrismVpnRole() != PrismSettings.PRISM_ROLE_SERVER ->
                "Off: only used when Prism's role is Server."
            !cellularOnly -> "Off: this phone has Wi-Fi or Ethernet, so port forwarding is used instead."
            active == null -> "Waiting for the WireGuard server to start."
            active.lastProblem.isNotBlank() -> active.lastProblem
            active.publicEndpoint.isBlank() -> "Registering with the punching directories..."
            else -> "Reachable by hole punching (mapped to ${active.publicEndpoint}). Keep-alive every two seconds."
        }
    }

    /** The marker line to add to a client's WireGuard config, or null when punching is off. */
    fun clientConfigLine(): String? {
        val active = HolePunch.active?.takeIf { it.isRunning() } ?: return null
        val target = HolePunch.targets().firstOrNull() ?: return null
        return HolePunch.configLine(target, active.id)
    }
}
