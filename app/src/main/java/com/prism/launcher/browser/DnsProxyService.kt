package com.prism.launcher.browser

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Standalone DNS Proxy Service for Prism Access Points.
 * 
 * Listens on 0.0.0.0:53 (UDP) and forwards DNS queries to P2pDnsManager.
 * Responses are sent back with NXDOMAIN for unresolved global domains (full P2P isolation).
 * 
 * This allows non-Prism devices connecting to a Prism hotspot to use P2P DNS
 * by simply setting their DNS server to the hotspot's IP.
 */
class DnsProxyService : Service() {

    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PrismLogger.logInfo(TAG, "DNS Proxy Service starting...")
        startDnsProxy()
        return START_STICKY
    }

    private fun startDnsProxy() {
        if (isRunning) return
        isRunning = true

        listenerThread = thread(name = "prism-dns-proxy", isDaemon = false) {
            try {
                // Android non-root apps cannot bind to ports < 1024.
                // Try port 53 first (works on rooted devices), fall back to 5353.
                val boundSocket = tryBindDnsPort()
                socket = boundSocket

                PrismLogger.logSuccess(TAG, "DNS Proxy listening on 0.0.0.0:${boundSocket.localPort}")

                val buffer = ByteArray(512) // DNS queries rarely exceed 512 bytes

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        // Handle query asynchronously to avoid blocking the listener
                        thread(isDaemon = true) {
                            handleDnsQuery(packet, buffer.copyOf())
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            PrismLogger.logError(TAG, "Error receiving DNS packet", e)
                        }
                    }
                }
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "DNS Proxy failed to start", e)
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    private fun handleDnsQuery(incomingPacket: DatagramPacket, data: ByteArray) {
        try {
            // Parse the incoming DNS query
            val query = DnsPacketParser.parseQuery(data.copyOf(incomingPacket.length)) ?: run {
                PrismLogger.logWarning(TAG, "Failed to parse DNS query from ${incomingPacket.address}")
                return
            }

            // Process each question
            for (question in query.questions) {
                val domain = question.name.lowercase()
                
                // Only handle A queries (IPv4) for now
                if (question.type != 1) continue

                PrismLogger.logDebug(TAG, "DNS Query: $domain")

                // Query P2P DNS first
                val resolvedIp = P2pDnsManager.resolve(domain, onlyP2p = false)

                val responseData = if (resolvedIp != null) {
                    // Found in P2P DNS: return the IP
                    PrismLogger.logSuccess(TAG, "DNS Hit: $domain -> $resolvedIp")
                    DnsPacketBuilder.buildResponseA(query.transactionId, domain, resolvedIp)
                } else {
                    // Not found: return NXDOMAIN (enforces P2P-only isolation)
                    PrismLogger.logInfo(TAG, "DNS Miss (P2P Isolation): $domain")
                    DnsPacketBuilder.buildResponseNXDOMAIN(query.transactionId, domain)
                }

                // Send response back to client
                val responsePacket = DatagramPacket(
                    responseData,
                    responseData.size,
                    incomingPacket.address,
                    incomingPacket.port
                )

                socket?.send(responsePacket)
            }
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Error handling DNS query", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        socket?.close()
        socket = null
        listenerThread?.interrupt()
        PrismLogger.logInfo(TAG, "DNS Proxy Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tryBindDnsPort(): DatagramSocket {
        return try {
            DatagramSocket(53).apply { reuseAddress = true; soTimeout = 0 }
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "Port 53 unavailable (root required) — using 5353. Connected devices must set DNS port to 5353 manually.")
            DatagramSocket(5353).apply { reuseAddress = true; soTimeout = 0 }
        }
    }

    companion object {
        private const val TAG = "PrismDnsProxy"
    }
}
