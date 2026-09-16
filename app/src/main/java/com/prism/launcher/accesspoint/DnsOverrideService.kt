package com.prism.launcher.accesspoint

import android.content.Context
import android.util.Log
import com.prism.launcher.PrismLogger
import com.prism.launcher.browser.DnsPacketBuilder
import com.prism.launcher.browser.DnsPacketParser
import com.prism.launcher.browser.P2pDnsManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts DNS queries from connected access point devices.
 * Routes all DNS queries to P2P DNS manager instead of public resolvers.
 * Acts as a transparent DNS proxy at the gateway.
 */
class DnsOverrideService(private val context: Context) {
    
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            com.prism.launcher.PrismLogger.coroutineHandler("DnsOverride")
    )
    private var dnsServerSocket: DatagramSocket? = null
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    
    companion object {
        private const val TAG = "DnsOverride"
        private const val CACHE_TTL_MS = 300000L
        private const val MAX_CACHE_SIZE = 5000
        // Actual bound port — 53 on rooted devices, 5353 otherwise
        var boundPort: Int = 5353
            private set
    }

    data class CachedDnsEntry(
        val domain: String,
        val ips: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
        val ttl: Int = 300
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - timestamp > (ttl * 1000)
    }

    /**
     * Start the DNS intercept server.
     * Binds to 0.0.0.0 (all interfaces). Tries port 53 first; falls back to 5353
     * because Android rejects privileged ports for non-root apps.
     */
    suspend fun startDnsServer(gatewayIp: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            dnsServerSocket = tryBindDnsSocket()
            val port = dnsServerSocket!!.localPort
            boundPort = port

            PrismLogger.logSuccess(TAG, "DNS Override Server started on 0.0.0.0:$port")

            // Start listening for DNS queries
            scope.launch {
                listenForDnsQueries()
            }

            return@withContext Result.success("DNS server started on port $port")
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Failed to start DNS server", e)
            Result.failure(e)
        }
    }

    /**
     * Listen for incoming DNS queries and respond with P2P DNS results.
     */
    private suspend fun listenForDnsQueries() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(512)
        
        while (isActive && dnsServerSocket?.isClosed == false) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                dnsServerSocket?.receive(packet)

                // Process DNS query in background
                scope.launch {
                    handleDnsQuery(packet)
                }
            } catch (e: Exception) {
                if (isActive) {
                    PrismLogger.logError(TAG, "Error receiving DNS packet", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * Handle individual DNS query.
     */
    private suspend fun handleDnsQuery(packet: DatagramPacket) = withContext(Dispatchers.IO) {
        try {
            val queryData = packet.data.copyOfRange(0, packet.length)

            val parsedQuery = DnsPacketParser.parseQuery(queryData)
            if (parsedQuery == null || parsedQuery.questions.isEmpty()) {
                PrismLogger.logError(TAG, "Invalid DNS query", null)
                return@withContext
            }

            val queryDomain = parsedQuery.questions.first().name
            val transactionId = parsedQuery.transactionId

            if (queryDomain.isEmpty()) {
                PrismLogger.logError(TAG, "Empty DNS query domain", null)
                return@withContext
            }

            // Check cache first
            val cachedEntry = dnsCache[queryDomain.lowercase()]
            if (cachedEntry != null && !cachedEntry.isExpired) {
                PrismLogger.logInfo(TAG, "DNS cache hit for $queryDomain: ${cachedEntry.ips}")
                sendDnsResponse(packet, transactionId, cachedEntry.ips.first(), queryDomain)
                return@withContext
            }

            // Resolve via P2P DNS ledger
            val resolvedIp = P2pDnsManager.resolve(queryDomain, onlyP2p = true)

            if (resolvedIp != null) {
                cacheEntry(queryDomain, listOf(resolvedIp), 300)
                PrismLogger.logSuccess(TAG, "P2P DNS resolved $queryDomain: $resolvedIp")
                sendDnsResponse(packet, transactionId, resolvedIp, queryDomain)
            } else if (isBlocked(queryDomain)) {
                PrismLogger.logInfo(TAG, "DNS blocked: $queryDomain")
                sendDnsResponse(packet, transactionId, "127.0.0.1", queryDomain)
            } else {
                PrismLogger.logWarning(TAG, "Not in P2P ledger (NXDOMAIN): $queryDomain")
                sendNxdomainResponse(packet, transactionId, queryDomain)
            }
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Error handling DNS query", e)
        }
    }

    /**
     * Send A-record DNS response to client.
     */
    private fun sendDnsResponse(
        request: DatagramPacket,
        transactionId: Short,
        ipAddress: String,
        domain: String
    ) {
        try {
            val responseData = DnsPacketBuilder.buildResponseA(transactionId, domain, ipAddress)
            dnsServerSocket?.send(DatagramPacket(responseData, responseData.size, request.address, request.port))
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Error sending DNS response", e)
        }
    }

    /**
     * Send NXDOMAIN response (domain does not exist in P2P ledger).
     */
    private fun sendNxdomainResponse(
        request: DatagramPacket,
        transactionId: Short,
        domain: String
    ) {
        try {
            val responseData = DnsPacketBuilder.buildResponseNXDOMAIN(transactionId, domain)
            dnsServerSocket?.send(DatagramPacket(responseData, responseData.size, request.address, request.port))
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Error sending NXDOMAIN response", e)
        }
    }

    /**
     * Check if domain is in blocklist.
     */
    private fun isBlocked(domain: String): Boolean {
        // Check against P2P blocklist
        // Implementation would check against host blocklist
        return false // Default: allow if in P2P ledger
    }

    /**
     * Cache a DNS entry.
     */
    private fun cacheEntry(domain: String, ips: List<String>, ttl: Int) {
        if (dnsCache.size >= MAX_CACHE_SIZE) {
            // Remove oldest expired entries
            dnsCache.entries.removeAll { (_, entry) -> entry.isExpired }
        }

        dnsCache[domain.lowercase()] = CachedDnsEntry(domain, ips, ttl = ttl)
    }

    /**
     * Clear all cached DNS entries.
     */
    fun clearCache() {
        dnsCache.clear()
        PrismLogger.logInfo(TAG, "DNS cache cleared")
    }

    private fun tryBindDnsSocket(): DatagramSocket {
        return try {
            DatagramSocket(53).apply { reuseAddress = true; broadcast = true }
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "Port 53 unavailable (root required) — using 5353")
            DatagramSocket(5353).apply { reuseAddress = true; broadcast = true }
        }
    }

    /**
     * Stop the DNS server.
     */
    suspend fun stopDnsServer() = withContext(Dispatchers.IO) {
        try {
            dnsServerSocket?.close()
            dnsServerSocket = null
            scope.cancel()
            PrismLogger.logSuccess(TAG, "DNS Override Server stopped")
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Error stopping DNS server", e)
        }
    }

    /**
     * Get cache statistics.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "size" to dnsCache.size,
            "maxSize" to MAX_CACHE_SIZE,
            "entries" to dnsCache.keys.toList()
        )
    }
}
