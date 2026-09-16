package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.prism.core.json.JSONObject
import com.prism.core.json.JSONArray
import com.prism.launcher.PrismSettings
import com.prism.core.MeshUtils
import com.prism.launcher.PrismApp
import kotlinx.coroutines.*
import java.io.File

/**
 * The core manager for Prism's Decentralized Name System.
 * Stores domain-to-IP mappings and synchronizes them across the mesh.
 */
object P2pDnsManager {

    private const val STORAGE_FILE = "p2p_dns_index.json"
    private val dnsIndex = mutableMapOf<String, DnsRecord>()

    private val _records = MutableStateFlow<Map<String, DnsRecord>>(emptyMap())
    val records: StateFlow<Map<String, DnsRecord>> = _records

    private val latencyMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastProbeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PROBE_EXPIRY = 300000L // 5 minutes

    private val _resolutionState = MutableStateFlow<Map<String, ResolutionSource>>(emptyMap())
    val resolutionState: StateFlow<Map<String, ResolutionSource>> = _resolutionState

    data class DnsRecord(
        val ip: String, // Primary/First discovered IP
        val alternates: Set<String> = emptySet(), // Additional peer IPs for this domain
        val timestamp: Long,
        val isVerified: Boolean = false,
        val source: ResolutionSource = ResolutionSource.P2P
    )

    enum class ResolutionSource {
        P2P, GLOBAL, UNKNOWN
    }

    fun getRecords(): Map<String, DnsRecord> = dnsIndex.toMap()

    fun updateRecord(context: Context, domain: String, ip: String, isVerified: Boolean = true, fromMesh: Boolean = false) {
        val existing = dnsIndex[domain]
        val alts = existing?.alternates?.toMutableSet() ?: mutableSetOf()
        
        val primaryIp = if (existing == null) {
            ip
        } else {
            if (existing.ip != ip) alts.add(ip)
            existing.ip
        }

        val record = DnsRecord(primaryIp, alts, System.currentTimeMillis(), isVerified, ResolutionSource.P2P)
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.P2P)
        
        if (!fromMesh) {
            val publicIp = MeshUtils.getLocalMeshIp()
            val broadcastIp = if (ip == "127.0.0.1") publicIp else ip
            
            val payload = JSONObject().apply {
                put("domain", domain)
                put("ip", broadcastIp)
                put("ts", record.timestamp)
                if (alts.isNotEmpty()) {
                    val publicAlts = alts.filter { it != "127.0.0.1" }
                    if (publicAlts.isNotEmpty()) put("alts", com.prism.core.json.JSONArray(publicAlts))
                }
            }.toString()
            com.prism.launcher.mesh.PrismMeshService.broadcastToOthers(0x05.toByte(), payload)
        }
    }

    fun deleteRecord(context: Context, domain: String) {
        dnsIndex.remove(domain)
        save(context)
        val current = _resolutionState.value.toMutableMap()
        current.remove(domain)
        _resolutionState.value = current
    }

    /**
     * Loads the on-disk DNS ledger. Called from `PrismApp.onCreate()`, so the file read and JSON
     * parse run off the calling thread -- otherwise every cold start blocks the launcher UI on
     * disk I/O before it's even up.
     */
    fun init(context: Context) {
        GlobalScope.launch(Dispatchers.IO) { initBlocking(context) }
    }

    private fun initBlocking(context: Context) {
        val storage = android.os.Environment.getExternalStorageDirectory()
        val prismDir = File(storage, "Prism")
        if (!prismDir.exists()) prismDir.mkdirs()

        val file = File(prismDir, STORAGE_FILE)
        val internalFile = File(context.filesDir, STORAGE_FILE)
        val targetFile = if (file.exists()) file else if (internalFile.exists()) internalFile else file

        if (targetFile.exists()) {
            try {
                val hostedDomains = PrismSettings.getP2pHostedSites().map { it.domain.lowercase() }.toSet()
                val json = JSONObject(targetFile.readText())
                json.keys().forEach { domain ->
                    val obj = json.getJSONObject(domain)
                    val srcStr = obj.optString("src", "")
                    
                    val source = if (srcStr.isNotEmpty()) {
                        try { ResolutionSource.valueOf(srcStr) } catch (e: Exception) { ResolutionSource.P2P }
                    } else {
                        // Legacy record scrubbing heuristic:
                        // Only treat as P2P if it has the .p2p extension or is in our local hosting list.
                        // Everything else (like duckduckgo.com) defaults to GLOBAL.
                        if (domain.endsWith(".p2p", ignoreCase = true) || hostedDomains.contains(domain.lowercase())) {
                            ResolutionSource.P2P
                        } else {
                            ResolutionSource.GLOBAL
                        }
                    }
                    
                    val altArray = obj.optJSONArray("alts")
                    val alts = mutableSetOf<String>()
                    if (altArray != null) {
                        for (i in 0 until altArray.length()) alts.add(altArray.getString(i))
                    }

                    val record = DnsRecord(
                        obj.getString("ip"),
                        alts,
                        obj.getLong("ts"),
                        obj.optBoolean("v", false),
                        source
                    )
                    dnsIndex[domain] = record
                    
                    updateResolutionState(domain, source)
                }
            } catch (e: Exception) {}
        }
        _records.value = dnsIndex.toMap()
    }

    /**
     * Resolve a domain. Evaluates all known mesh peers and returns the IP
     * with the lowest latency. Always prioritizes local mirrors (127.0.0.1).
     */
    fun resolve(domain: String, onlyP2p: Boolean = false): String? {
        var effectiveDomain = domain
        var excludeLocal = false
        
        if (domain.endsWith(".remote", ignoreCase = true)) {
            effectiveDomain = domain.removeSuffix(".remote")
            excludeLocal = true
        }

        if (onlyP2p && !isP2pDomain(effectiveDomain)) return null
        val record = dnsIndex[effectiveDomain] ?: return null
        
        val candidates = mutableSetOf(record.ip)
        candidates.addAll(record.alternates)

        if (excludeLocal) {
            candidates.remove("127.0.0.1")
            candidates.remove(MeshUtils.getLocalMeshIp())
        } else if (candidates.contains("127.0.0.1")) {
            // 1. Zero Latency: Local hosting/mirroring always wins for non-sync requests
            return "127.0.0.1"
        }
        
        // 2. Latency-Based Selection: Choose the fastest known peer
        val best = candidates.minByOrNull { ip ->
            val latency = latencyMap[ip]
            val lastProbe = lastProbeMap[ip] ?: 0L
            
            // If we have no data or data is expired, trigger a background probe
            if (latency == null || (System.currentTimeMillis() - lastProbe) > PROBE_EXPIRY) {
                probeLatencyAsync(ip)
                Int.MAX_VALUE // Treat as slow until probed
            } else {
                latency
            }
        } ?: record.ip

        return best
    }

    private fun probeLatencyAsync(ip: String) {
        if (ip == "127.0.0.1") return
        val now = System.currentTimeMillis()
        if ((now - (lastProbeMap[ip] ?: 0L)) < 10000) return // Rate limit probes to 10s
        
        lastProbeMap[ip] = now
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                // Lightweight TCP probe on the mesh control port (defaulting to 8888 for heartbeats)
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, 8888), 1500)
                val rtt = (System.currentTimeMillis() - start).toInt()
                latencyMap[ip] = rtt
                socket.close()
                Log.d("P2pDns", "Probe $ip: ${rtt}ms")
            } catch (e: Exception) {
                latencyMap[ip] = 5000 // Penalty for timeout/failure
            }
        }
    }

    fun isP2pDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        if (domain.endsWith(".p2p", ignoreCase = true)) return true
        // Check both the record source and the in-memory state
        val record = dnsIndex[domain]
        if (record != null && record.source == ResolutionSource.P2P) return true
        return _resolutionState.value[domain] == ResolutionSource.P2P
    }

    fun seedFromGlobal(context: Context, domain: String, ip: String) {
        if (dnsIndex.containsKey(domain) && isP2pDomain(domain)) return
        val record = DnsRecord(ip, emptySet(), System.currentTimeMillis(), isVerified = true, source = ResolutionSource.GLOBAL)
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.GLOBAL)
    }

    fun ingestFromPeer(context: Context, peerRecords: Map<String, DnsRecord>) {
        var changed = false
        peerRecords.forEach { (domain, record) ->
            val existing = dnsIndex[domain]
            if (existing == null) {
                dnsIndex[domain] = record
                updateResolutionState(domain, record.source)
                changed = true
            } else if (record.source == ResolutionSource.P2P) {
                // For P2P records, we MERGE alternates to increase network durability
                val newAlts = existing.alternates.toMutableSet()
                newAlts.add(record.ip)
                newAlts.addAll(record.alternates)
                newAlts.remove(existing.ip) // Ensure primary IP isn't in alts
                newAlts.remove("127.0.0.1") // Local loopback is never shared as a public alternate
                
                // If the new record is newer, update primary IP but keep merged alternates
                if (record.timestamp > existing.timestamp) {
                    dnsIndex[domain] = DnsRecord(record.ip, newAlts, record.timestamp, record.isVerified, ResolutionSource.P2P)
                    changed = true
                } else if (newAlts.size > existing.alternates.size) {
                    // Even if older, if it provides NEW alternates, we keep them
                    dnsIndex[domain] = existing.copy(alternates = newAlts)
                    changed = true
                }
            } else if (record.timestamp > existing.timestamp) {
                // Standard overwrite for GLOBAL records
                dnsIndex[domain] = record
                updateResolutionState(domain, record.source)
                changed = true
            }
        }
        if (changed) {
            save(context)
        }
    }

    private fun updateResolutionState(domain: String, source: ResolutionSource) {
        val current = _resolutionState.value.toMutableMap()
        current[domain] = source
        _resolutionState.value = current
        _records.value = dnsIndex.toMap()
    }

    private fun save(context: Context) {
        _records.value = dnsIndex.toMap()
        try {
            val storage = android.os.Environment.getExternalStorageDirectory()
            val prismDir = File(storage, "Prism")
            if (!prismDir.exists()) prismDir.mkdirs()
            
            val json = JSONObject()
            dnsIndex.forEach { (domain, record) ->
                val obj = JSONObject().apply {
                    put("ip", record.ip)
                    put("ts", record.timestamp)
                    put("v", record.isVerified)
                    put("src", record.source.name)
                    if (record.alternates.isNotEmpty()) put("alts", com.prism.core.json.JSONArray(record.alternates.toList()))
                }
                json.put(domain, obj)
            }
            File(prismDir, STORAGE_FILE).writeText(json.toString())
        } catch (e: Exception) {}
    }
}
