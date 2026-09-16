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
