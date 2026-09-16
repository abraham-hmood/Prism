package com.prism.launcher.vpn

import android.util.Log
import com.prism.launcher.PrismApp
import com.prism.launcher.PrismLogger
import com.prism.launcher.browser.P2pDnsManager
import com.prism.launcher.browser.PrivateDnsVpnService
import com.prism.core.MeshUtils
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * A Mesh-aware Socket subclass that automatically handles port redirection (to 8080)
 * and the PRISM_CONNECT handshake for all P2P Mesh traffic.
 */
class PrismSocket : Socket() {

    private var targetHost: String? = null

    /**
     * Set the domain name hint for the handshake if known.
     */
    fun setHostHint(host: String) {
        this.targetHost = host
    }

    override fun connect(endpoint: SocketAddress?) {
        connect(endpoint, 8000)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (endpoint !is InetSocketAddress) {
            super.connect(endpoint, timeout)
            return
        }

        val ipAddr = endpoint.address?.hostAddress ?: ""
        // Priority: 1. Explicit host hint, 2. Endpoint hostname, 3. IP
        val originalHost = targetHost ?: if (!endpoint.hostName.isNullOrEmpty()) endpoint.hostName else ipAddr
        val originalPort = endpoint.port
        
        // 1. Resolve domain for mesh status - STRICTLY check if it's a P2P record
        val p2pIp = P2pDnsManager.resolve(originalHost, onlyP2p = true)
        
        // Strict Mesh Detection:
        // - Host ends with .p2p
        // - In P2P DNS list (verified P2P source)
        // - Host contains mesh signature "-s-"
        // - Target is explicitly port 8080
        val isMeshTarget = originalHost.endsWith(".p2p", ignoreCase = true) || 
                           p2pIp != null || 
                           originalHost.contains("-s-") ||
                           originalPort == 8080 ||
                           com.prism.launcher.mesh.PrismMeshService.isPeer(ipAddr)
        
        // If not a mesh target, bypass P2P logic and connect normally
        if (!isMeshTarget) {
            super.connect(endpoint, timeout)
            return
        }

        // 2. Perform Redirection & Handshake
        try {
            val meshIp = p2pIp ?: ipAddr
            val finalPort = 8080
            
            PrismLogger.logInfo("PrismSocket", "Mesh Redirection: $originalHost:$originalPort -> $meshIp:$finalPort")
            
            // Handle local loop: if target is self, connect to 127.0.0.1
            val localIps = MeshUtils.getAllLocalIps()
            val isLocal = localIps.contains(meshIp) || meshIp == "127.0.0.1" || meshIp == "localhost"
            val connectAddr = if (isLocal) "127.0.0.1" else meshIp

            PrismLogger.logInfo("PrismSocket", "Connecting to $originalHost via $connectAddr:$finalPort (Local: $isLocal)")
            
            // Protect the socket so it bypasses the VPN tunnel to reach the physical network.
            // IMPORTANT: Never protect a local loopback connection (127.0.0.1), as it 
            // will cause the OS to refuse the connection (ECONNREFUSED).
            if (!isLocal) {
                PrivateDnsVpnService.protectSocket(this)
            }
            
            // Use InetAddress directly to avoid re-resolving
            val targetAddress = InetAddress.getByName(connectAddr)
            super.connect(InetSocketAddress(targetAddress, finalPort), timeout)
            
            // 3. Perform Handshake (Send the domain name)
            performHandshake(originalHost)
            
            PrismLogger.logSuccess("PrismSocket", "Handshake successful for $originalHost")
            
        } catch (e: Exception) {
            PrismLogger.logError("PrismSocket", "Connection/Handshake failed for $originalHost", e)
            throw e
        }
    }

    /**
     * Exchanges PRISM_CONNECT/PRISM_ACK before the caller's own protocol starts.
     *
     * THE HANDSHAKE TIMEOUT IS RESTORED AFTERWARDS, and that restore is the whole point of the
     * finally block. Five seconds is right for an ack that either arrives at once or is never
     * coming -- and wrong for everything that follows, because soTimeout is a property of the
     * SOCKET, not of the read that set it. Leaving it in place capped every later read on the same
     * connection at five seconds, which is how model transfers died with
     * "SocketTimeoutException: Read timed out" inside readHeadersAndLength: the seller merely had
     * to take longer than that to locate and open a multi-gigabyte file, which on a phone is the
     * normal case rather than the exceptional one.
     */
    private fun performHandshake(hostName: String) {
        val out = getOutputStream()
        val inp = getInputStream()

        val callerTimeout = soTimeout
        soTimeout = 5000
        try {
            val handshake = "PRISM_CONNECT $hostName\n"
            out.write(handshake.toByteArray())
            out.flush()

            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }

            val ack = sb.toString().trim()
            if (ack != "PRISM_ACK") {
                throw java.io.IOException("Handshake mismatch: Expected PRISM_ACK, got '$ack'")
            }
        } finally {
            soTimeout = callerTimeout
        }
    }
}
