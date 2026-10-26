package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.prism.core.json.JSONObject
import com.prism.core.json.JSONArray
import com.prism.launcher.PrismSettings
import com.prism.core.DnsSignature
import com.prism.core.MeshUtils
import com.prism.launcher.PrismApp
import kotlinx.coroutines.*
import java.io.File

/**
 * The core manager for Prism's Decentralized Name System.
 * Stores domain-to-IP mappings and synchronizes them across the mesh.
 */
object P2pDnsManager {

    /**
     * The resolver's own scope.
     *
     * NOT GlobalScope, which is delicate for two reasons that both apply here: nothing can cancel it,
     * and it has no supervisor -- so a single probe that threw would cancel every other coroutine
     * sharing it. This is process-lifetime by intent, which is exactly what makes the supervisor
     * matter rather than the lifetime.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val STORAGE_FILE = "p2p_dns_index.json"

    /** How long a peer-discovered record may sit unused before it is treated as stale. */
    private const val P2P_TTL_MS = 24L * 60 * 60 * 1000
    private const val GLOBAL_TTL_MS = 6L * 60 * 60 * 1000

    /** Concurrent: resolver threads, mesh callbacks and UI all touch this map. */
    private val dnsIndex = java.util.concurrent.ConcurrentHashMap<String, DnsRecord>()

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
        val source: ResolutionSource = ResolutionSource.P2P,
        /** The wallet identity that announced this name, hex. Empty on legacy/global records. */
        val publicKey: String = "",
        /** Signature over [DnsSignature.canonical] for this record. Empty on legacy records. */
        val signature: String = "",
    )

    enum class ResolutionSource {
        P2P, GLOBAL, UNKNOWN
    }

    fun getRecords(): Map<String, DnsRecord> = dnsIndex.toMap()

    /**
     * Applies a record from a peer, refusing changes that would hijack an existing name.
     *
     * The mesh has no accounts and no shared secret for DNS, so "any peer may write any record"
     * was a domain-hijack primitive. A peer can still announce a new name pointing at itself --
     * that is what hosting is -- but it cannot redirect or delete a name another device is
     * currently serving unless the record is stale and the new address is the sender's own.
     *
     * @return true when the record was accepted.
     */
    fun updateRecord(
        context: Context,
        domain: String,
        ip: String,
        isVerified: Boolean = true,
        fromMesh: Boolean = false,
        sourceIp: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        alternates: Set<String> = emptySet(),
        publicKey: String = "",
        signature: String = "",
    ): Boolean {
        val existing = dnsIndex[domain]

        // A SIGNED RECORD IS CHECKED BY ITS SIGNATURE, not by where it arrived from. The address
        // rules below stay for a peer that has no wallet and therefore cannot sign -- refusing
        // those outright would cut off every build older than this one -- but a signature is a
        // stronger statement than "the packet came from that address" and it is one that survives
        // relaying, which the address does not.
        if (fromMesh && signature.isNotBlank()) {
            if (publicKey.isBlank() ||
                !DnsSignature.verify(publicKey, signature, domain, ip, timestamp, alternates)
            ) {
                Log.w(STORAGE_TAG, DnsSignature.explainRejection(domain, publicKey))
                return false
            }
            // A name that already belongs to an identity cannot be claimed by another one while it
            // is alive, even from the same address. This is the rule that makes a name mean
            // something: without it, whoever announced last would win.
            if (existing != null && existing.publicKey.isNotBlank() &&
                existing.publicKey != publicKey && !isExpired(domain, existing)
            ) {
                Log.w(STORAGE_TAG, "Rejected DNS identity change of $domain from $sourceIp")
                return false
            }
        }

        if (fromMesh && sourceIp != null) {
            // A peer cannot claim a name that is alive on a different address.
            if (existing != null && existing.ip != ip && existing.ip != "127.0.0.1" &&
                !isExpired(domain, existing)
            ) {
                Log.w(STORAGE_TAG, "Rejected DNS takeover of $domain: $sourceIp -> $ip")
                return false
            }
            // Nor may it claim an address that is not its own, unless it signed the record: a
            // signed record may legitimately be relayed, and the signature is what it stands on.
            if (signature.isBlank() && ip != sourceIp && ip != "127.0.0.1") {
                Log.w(STORAGE_TAG, "Rejected DNS write for $domain: $sourceIp claimed $ip")
                return false
            }
        }

        val alts = existing?.alternates?.toMutableSet() ?: mutableSetOf()
        alts.addAll(alternates)

        val primaryIp = if (existing == null) {
            ip
        } else {
            if (existing.ip != ip) alts.add(ip)
            existing.ip
        }

        // LOCAL WRITES ARE SIGNED HERE. What is signed is what a peer will SEE, so a loopback
        // record signs the mesh address that is broadcast in its place -- otherwise every peer
        // would be checking a signature over 127.0.0.1, which no peer ever receives.
        val wireIp = if (primaryIp == "127.0.0.1") MeshUtils.getLocalMeshIp() else primaryIp
        val publicAlts = alts.filter { it != "127.0.0.1" }
        val pub = if (!fromMesh) DnsSignature.publicKeyHex().orEmpty() else publicKey
        val sig = when {
            !fromMesh && pub.isNotBlank() ->
                DnsSignature.sign(domain, wireIp, timestamp, publicAlts).orEmpty()
            else -> signature
        }

        val record = DnsRecord(
            primaryIp,
            alts,
            timestamp,
            isVerified,
            ResolutionSource.P2P,
            pub,
            sig,
        )
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.P2P)
        
        if (!fromMesh) {
            val payload = JSONObject().apply {
                put("domain", domain)
                put("ip", wireIp)
                put("ts", record.timestamp)
                if (publicAlts.isNotEmpty()) {
                    put("alts", com.prism.core.json.JSONArray(publicAlts))
                }
                if (pub.isNotBlank()) put("pub", pub)
                if (sig.isNotBlank()) put("sig", sig)
            }.toString()
            com.prism.launcher.mesh.PrismMeshService.broadcastToOthers(0x05.toByte(), payload)
        }
        return true
    }

    /**
     * Deletes a record, optionally on behalf of a peer.
     *
     * A peer may only remove a record whose address is its own; otherwise deleting a name would
     * be as damaging as redirecting it, and just as unauthenticated.
     */
    fun deleteRecord(context: Context, domain: String, sourceIp: String? = null): Boolean {
        val existing = dnsIndex[domain]
        if (sourceIp != null && existing != null && existing.ip != sourceIp &&
            existing.ip != "127.0.0.1" && !isExpired(domain, existing)
        ) {
            Log.w(STORAGE_TAG, "Rejected DNS delete of $domain by $sourceIp")
            return false
        }
        dnsIndex.remove(domain)
        save(context)
        val current = _resolutionState.value.toMutableMap()
        current.remove(domain)
        _resolutionState.value = current
        return true
    }

    /**
     * Loads the on-disk DNS ledger. Called from `PrismApp.onCreate()`, so the file read and JSON
     * parse run off the calling thread -- otherwise every cold start blocks the launcher UI on
     * disk I/O before it's even up.
     */
    fun init(context: Context) {
        scope.launch { initBlocking(context) }
    }

    private fun initBlocking(context: Context) {
        // INTERNAL STORAGE, NOT /Prism. The ledger names every domain this device serves and
        // resolves, and it used to be world-readable on shared storage. Internal files are
        // readable only by Prism; the old file is migrated once and then deleted.
        val internalFile = File(context.filesDir, STORAGE_FILE)
        val legacyDir = File(android.os.Environment.getExternalStorageDirectory(), "Prism")
        val legacyFile = File(legacyDir, STORAGE_FILE)

        if (!internalFile.exists() && legacyFile.exists()) {
            runCatching {
                internalFile.writeText(legacyFile.readText())
                legacyFile.delete()
                Log.i(STORAGE_TAG, "Migrated the P2P DNS ledger out of shared storage")
            }
        }
        val targetFile = internalFile

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
                        source,
                        obj.optString("pub"),
                        obj.optString("sig"),
                    )
                    if (!isExpired(domain, record)) {
                        dnsIndex[domain] = record
                        updateResolutionState(domain, source)
                    }
                }
            } catch (e: Exception) {}
        }
        pruneExpired()
        _records.value = dnsIndex.toMap()
    }

    /**
     * Records only mean something while the thing they point at is still there.
     *
     * A record for a hosted local site is refreshed whenever it is saved, so TTL does not apply
     * to it -- the user did not stop hosting by leaving the phone alone overnight.
     */
    private fun isExpired(domain: String, record: DnsRecord): Boolean {
        if (record.ip == "127.0.0.1") return false
        if (PrismSettings.getP2pHostedSites().any { it.domain.equals(domain, ignoreCase = true) }) return false
        val ttl = if (record.source == ResolutionSource.P2P) P2P_TTL_MS else GLOBAL_TTL_MS
        return System.currentTimeMillis() - record.timestamp > ttl
    }

    private fun pruneExpired() {
        dnsIndex.entries.filter { isExpired(it.key, it.value) }
            .map { it.key }
            .forEach { dnsIndex.remove(it) }
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
        if (isExpired(effectiveDomain, record)) {
            dnsIndex.remove(effectiveDomain)
            return null
        }
        
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
        scope.launch {
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
            // A SYNC BODY IS UNSIGNED TOO. Each record carries its own signature, and one that
            // does not verify is dropped before it can introduce or repoint anything -- the
            // envelope being authenticated never covered the entries inside it.
            if (record.signature.isNotBlank() &&
                (record.publicKey.isBlank() || !DnsSignature.verify(
                    record.publicKey,
                    record.signature,
                    domain,
                    record.ip,
                    record.timestamp,
                    record.alternates,
                ))
            ) {
                Log.w(STORAGE_TAG, DnsSignature.explainRejection(domain, record.publicKey))
                return@forEach
            }

            val existing = dnsIndex[domain]
            if (existing != null && existing.publicKey.isNotBlank() &&
                record.publicKey.isNotBlank() && existing.publicKey != record.publicKey &&
                !isExpired(domain, existing)
            ) {
                Log.w(STORAGE_TAG, "Ignored a sync that changed $domain's identity")
                return@forEach
            }
            // A sync response is unauthenticated. It may introduce a name, but it must not
            // repoint a live one: that was the same hijack as the write handler, just through a
            // different opcode.
            if (existing != null && existing.ip != record.ip && !isExpired(domain, existing)) {
                return@forEach
            }
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
                    dnsIndex[domain] = DnsRecord(
                        record.ip, newAlts, record.timestamp, record.isVerified,
                        ResolutionSource.P2P, record.publicKey, record.signature,
                    )
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
        pruneExpired()
        _records.value = dnsIndex.toMap()
        try {
            val json = JSONObject()
            dnsIndex.forEach { (domain, record) ->
                val obj = JSONObject().apply {
                    put("ip", record.ip)
                    put("ts", record.timestamp)
                    put("v", record.isVerified)
                    put("src", record.source.name)
                    if (record.publicKey.isNotBlank()) put("pub", record.publicKey)
                    if (record.signature.isNotBlank()) put("sig", record.signature)
                    if (record.alternates.isNotEmpty()) put("alts", com.prism.core.json.JSONArray(record.alternates.toList()))
                }
                json.put(domain, obj)
            }
            File(context.filesDir, STORAGE_FILE).writeText(json.toString())
        } catch (e: Exception) {}
    }

    private const val STORAGE_TAG = "P2pDns"
}
