package com.prism.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Network identity for the mesh.
 *
 * Moved to :core unchanged, because it was already platform-independent -- every method is
 * java.net.NetworkInterface. The `Context` parameter [getLocalMeshIp] used to take was never
 * read; it was there because everything in the Android module took one. Nothing needed a
 * capability interface, which is worth recording: not every Android-module file is Android code.
 */
object MeshUtils {

    /**
     * Finds the primary IP address to use for P2P Mesh identification.
     * Priority:
     * 1. WireGuard interface (tun0 or similar mesh interface)
     * 2. LAN IPv4 (Wifi/Ethernet)
     * 3. Loopback (fallback)
     */
    fun getLocalMeshIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val interfaceList = interfaces.toList()

            // 1. Try to find a WireGuard/VPN interface first (Commonly tun0 or contains 'wg')
            val meshInterface = interfaceList.find { it.name.contains("tun") || it.name.contains("wg") }
            meshInterface?.inetAddresses?.toList()?.find { it is Inet4Address && !it.isLoopbackAddress }?.let {
                return it.hostAddress ?: ""
            }

            // 2. Fallback to LAN IP
            interfaceList.filter { !it.isLoopback && it.isUp }.forEach { intf ->
                intf.inetAddresses.toList().find { it is Inet4Address && !it.isLoopbackAddress }?.let {
                    return it.hostAddress ?: ""
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }

    /** Interface names that are, or look like, a cable or Wi-Fi link to a real network. */
    private val LAN_PREFIXES = listOf("wlan", "wlp", "wl", "eth", "en", "swlan")

    /** Interfaces that carry a tunnel or a hypervisor's private switch, never the user's LAN. */
    private val VIRTUAL_PREFIXES = listOf(
        "rmnet", "ccmni", "tun", "tap", "wg", "vethernet", "vmnet", "vboxnet",
        "docker", "br-", "virbr", "wsl", "dummy", "ip6tnl", "sit", "clat",
    )

    /**
     * Every IPv4 address this device holds, with a real LAN link ahead of a virtual one.
     *
     * [getLocalMeshIp] answers a different question: ONE address, and it prefers a VPN tunnel
     * because the mesh wants the tunnel. LAN discovery needs the opposite -- every network the
     * device is actually on, Wi-Fi or Ethernet first, because that is where the other machines are.
     * A phone with mobile data up enumerates `rmnet` as readily as `wlan0`, and Windows adds Hyper-V
     * (vEthernet), WSL and VirtualBox adapters; taking whichever interface came first swept a subnet
     * with nothing on it, which is how a phone failed to see an Ollama server on the same Wi-Fi.
     */
    fun lanIpv4Addresses(): List<String> {
        val ranked = mutableListOf<Pair<Int, String>>()
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val rank = when {
                    LAN_PREFIXES.any { name.startsWith(it) } -> 0
                    VIRTUAL_PREFIXES.any { name.startsWith(it) } -> 2
                    else -> 1
                }
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ranked += rank to it }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        // sortedBy is stable, so within one rank the enumeration order is kept.
        return ranked.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Returns all IPv4 addresses assigned to this device across all interfaces.
     */
    fun getAllLocalIps(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "0.0.0.0")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isUp) {
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address) {
                            ips.add(addr.hostAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return ips
    }

    /**
     * Returns a list of all broadcast addresses for active, non-loopback interfaces.
     */
    fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        broadcastList.add(broadcast)
                    }
                }
            }
            // Always include global broadcast as fallback
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return broadcastList.distinct()
    }

    /**
     * A dotted-quad IPv4 literal, and nothing else. Never resolves anything.
     */
    fun isIpv4Literal(value: String): Boolean {
        val parts = value.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p.toIntOrNull() ?: return false) in 0..255
        }
    }

    /**
     * An IPv4 or IPv6 LITERAL, never a name, so no caller can be talked into a DNS lookup.
     */
    fun isIpLiteral(value: String): Boolean {
        val text = value.trim().substringBefore('%')
        if (text.isEmpty()) return false
        if (isIpv4Literal(text)) return true
        if (!text.contains(':')) return false
        return text.length <= 45 &&
            text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    /**
     * Finds a port that Android allows and that is currently free.
     * Randomly generates a port between 0 and 65535.
     * Recursively calls itself if the port is taken or reserved.
     */
    fun findAvailablePort(): Int {
        val reservedPorts = setOf(8080, 8081, 51820, 500, 4500, 1701)

        // Bounded loop rather than the unbounded recursion this used to be. Every failed attempt
        // was a stack frame, so a congested machine -- or one where the firewall refuses most
        // binds, which is far more likely on Windows than on Android -- could recurse until it
        // overflowed. A loop cannot, and asking the OS for an ephemeral port is a correct answer
        // rather than a fallback.
        repeat(64) {
            val candidate = (1024..65535).random()
            if (candidate in reservedPorts) return@repeat
            try {
                java.net.ServerSocket(candidate).use { return candidate }
            } catch (e: Exception) {
                // Taken or refused; try another.
            }
        }

        // Let the OS choose. Port 0 means "any free port", which is exactly the question being
        // asked and never fails on a machine that can bind at all.
        return try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/mesh", "Could not find a free port", e)
            0
        }
    }
}
