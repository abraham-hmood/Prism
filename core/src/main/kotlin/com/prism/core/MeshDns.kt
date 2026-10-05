package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The `.p2p` name registry. PHASE 49.
 *
 * ## What this is, and what it is deliberately not
 *
 * A flat map from a name to the mesh addresses serving it, gossiped between peers. That is all. It is not
 * DNS in the protocol sense — there are no zones, no TTL hierarchy, no authority, and no recursion — and
 * calling it DNS is a description of its JOB rather than its design.
 *
 * It does not need to be more than that. The whole namespace is "sites people on this mesh are hosting",
 * which is tens of entries on a home network, and every peer holds the entire thing. A real resolver's
 * machinery exists to avoid holding the whole internet, which is not the problem here.
 *
 * ## Why a name can have several addresses
 *
 * Because the same site can be served by more than one peer, and because a peer's address changes when it
 * reconnects. [Record.alternates] keeps every address a name has been seen at, and resolution tries them in
 * order — so a site whose host got a new DHCP lease is still reachable through the stale entry being one of
 * several rather than the only one.
 *
 * ## Why 127.0.0.1 is never gossiped
 *
 * A host registers its own site against loopback, because that is where it is serving from. Broadcasting
 * that would tell every peer on the mesh that the site is at THEIR loopback, which is nothing — and each
 * of them would then gossip it onward. The local address is substituted on the way out, which is the one
 * transformation this registry performs.
 */
object MeshDns {

    private const val TAG = "PrismDns"

    /** How the record was learned, which is what decides whether it may be trusted for TLS later. */
    enum class Source {
        /** Registered on this device by its owner. */
        LOCAL,

        /** Learned from a peer over the mesh. */
        MESH,
    }

    data class Record(
        val domain: String,
        /** The first address this name was seen at. Tried first. */
        val ip: String,
        val alternates: Set<String> = emptySet(),
        val timestamp: Long = System.currentTimeMillis(),
        val source: Source = Source.MESH,
    ) {
        /** Every address, primary first, for a resolver to try in order. */
        fun addresses(): List<String> = (listOf(ip) + alternates.filter { it != ip }).distinct()

        fun describe(): String = buildString {
            append(ip)
            if (alternates.size > 1) append(" (+${alternates.size - 1} more)")
            append(" · ")
            append(if (source == Source.LOCAL) "hosted here" else "from the mesh")
        }
    }

    private val index = ConcurrentHashMap<String, Record>()

    @Volatile private var storage: File? = null

    /** Called once at startup. Loads whatever was saved. */
    fun install(directory: File) {
        storage = directory.apply { mkdirs() }
        load()
    }

    fun all(): Map<String, Record> = index.toMap()

    fun localRecords(): List<Record> = index.values.filter { it.source == Source.LOCAL }

    /**
     * Resolves a name to an address, or null.
     *
     * [onlyP2p] exists because the caller is usually deciding whether to route a connection over the mesh
     * AT ALL. A resolver that answered for ordinary domains would hijack normal browsing; one that answered
     * only for names it actually knows is the difference between a mesh feature and a broken network stack.
     */
    fun resolve(domain: String, onlyP2p: Boolean = true): String? {
        val key = domain.lowercase().trim()
        if (key.isEmpty()) return null
        if (onlyP2p && !key.endsWith(SUFFIX) && !index.containsKey(key)) return null
        return index[key]?.ip
    }

    fun addressesFor(domain: String): List<String> =
        index[domain.lowercase().trim()]?.addresses().orEmpty()

    /**
     * Registers or updates a name.
     *
     * A new address for a known name is ADDED rather than replacing it: several peers can serve one site,
     * and a peer that reconnected has a new address while the old entry may still be valid for somebody
     * else. The primary stays put unless it was loopback, because a remembered working address is worth
     * more than the most recent one.
     */
    fun put(domain: String, ip: String, source: Source = Source.MESH): Record? {
        val key = domain.lowercase().trim()
        if (key.isEmpty() || ip.isBlank()) return null

        val existing = index[key]
        val alternates = (existing?.alternates.orEmpty() + ip + listOfNotNull(existing?.ip)).toSet()
        val primary = when {
            existing == null -> ip
            // Loopback is a placeholder for "here", so a real address always beats it.
            existing.ip == "127.0.0.1" && ip != "127.0.0.1" -> ip
            else -> existing.ip
        }

        val record = Record(
            domain = key,
            ip = primary,
            alternates = alternates,
            timestamp = System.currentTimeMillis(),
            // LOCAL is sticky: learning a mesh copy of a site hosted here must not downgrade it, or the
            // host would stop believing it is the host.
            source = if (existing?.source == Source.LOCAL || source == Source.LOCAL) Source.LOCAL else source,
        )
        index[key] = record
        save()
        return record
    }

    fun remove(domain: String) {
        index.remove(domain.lowercase().trim())
        save()
    }

    // ── Gossip ─────────────────────────────────────────────────────────────

    /**
     * This device's records, as the JSON the mesh carries.
     *
     * Loopback is rewritten to this device's mesh address on the way out — see the class comment. A record
     * with no usable address is omitted rather than sent as an empty string, which a receiver would store
     * and then fail to connect to.
     */
    fun exportJson(): String {
        val localIp = MeshUtils.getLocalMeshIp()
        val array = JSONArray()
        index.values.forEach { record ->
            val addresses = record.addresses()
                .map { if (it == "127.0.0.1" || it == "localhost") localIp else it }
                .filter { it.isNotBlank() }
                .distinct()
            if (addresses.isEmpty()) return@forEach
            array.put(
                JSONObject().apply {
                    put("d", record.domain)
                    put("ip", addresses.first())
                    put("alt", JSONArray().also { a -> addresses.drop(1).forEach { a.put(it) } })
                }
            )
        }
        return array.toString()
    }

    /**
     * Merges a peer's records.
     *
     * Everything learned here is [Source.MESH] regardless of what the sender called it. A peer claiming its
     * records are LOCAL would be claiming to be the authority for a name on THIS device, and source is what
     * a later TLS decision would rest on — so it is assigned by the receiver, not accepted from the wire.
     */
    fun importJson(peerIp: String, json: String): Int {
        var added = 0
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val domain = item.optString("d").lowercase().trim()
                if (domain.isEmpty()) continue

                val primary = item.optString("ip").ifBlank { peerIp }
                put(domain, primary, Source.MESH)

                val alternates = item.optJSONArray("alt")
                for (a in 0 until (alternates?.length() ?: 0)) {
                    alternates?.optString(a)?.takeIf { it.isNotBlank() }
                        ?.let { put(domain, it, Source.MESH) }
                }
                added++
            }
        }.onFailure { PrismPlatform.log.info(TAG, "Bad DNS payload from $peerIp") }
        return added
    }

    /**
     * Announces one record to the mesh immediately.
     *
     * Used when a name is registered, so it propagates without waiting for the next sync round. The
     * periodic exchange in [MeshCore] is what makes it eventually consistent; this is what makes it
     * feel immediate.
     */
    fun announce(domain: String) {
        val record = index[domain.lowercase().trim()] ?: return
        val localIp = MeshUtils.getLocalMeshIp()
        val ip = if (record.ip == "127.0.0.1") localIp else record.ip
        if (ip.isBlank()) return

        val payload = JSONArray().also { array ->
            array.put(JSONObject().apply {
                put("d", record.domain)
                put("ip", ip)
                put("alt", JSONArray())
            })
        }.toString()
        MeshCore.broadcastToOthers(OPCODE_DNS_WRITE, payload)
    }

    /**
     * Wires this registry into the mesh transport.
     *
     * The DNS-sync opcodes are built into [MeshCore] because they are part of keeping the mesh coherent;
     * this is what supplies the records they carry. A platform that never calls it participates in
     * everything except naming.
     */
    fun registerWithMesh() {
        MeshCore.dnsProvider = { exportJson() }
        MeshCore.dnsConsumer = { peerIp, json -> importJson(peerIp, json) }
        PrismPlatform.log.info(TAG, "DNS registry attached to the mesh")
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun file(): File? = storage?.let { File(it, "p2p-dns.json") }

    private fun load() {
        val source = file() ?: return
        if (!source.isFile) return
        runCatching {
            val array = JSONArray(source.readText())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val domain = item.optString("d")
                if (domain.isBlank()) continue
                val alternates = mutableSetOf<String>()
                val alt = item.optJSONArray("alt")
                for (a in 0 until (alt?.length() ?: 0)) {
                    alt?.optString(a)?.takeIf { it.isNotBlank() }?.let { alternates.add(it) }
                }
                index[domain] = Record(
                    domain = domain,
                    ip = item.optString("ip"),
                    alternates = alternates,
                    timestamp = item.optLong("at"),
                    source = if (item.optString("src") == "local") Source.LOCAL else Source.MESH,
                )
            }
        }
    }

    private fun save() {
        val target = file() ?: return
        val array = JSONArray()
        index.values.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("d", record.domain)
                    put("ip", record.ip)
                    put("at", record.timestamp)
                    put("src", if (record.source == Source.LOCAL) "local" else "mesh")
                    put("alt", JSONArray().also { a -> record.alternates.forEach { a.put(it) } })
                }
            )
        }
        runCatching { target.writeText(array.toString()) }
    }

    /** The reserved suffix. A name without it is only resolvable if somebody registered it explicitly. */
    const val SUFFIX = ".p2p"

    /**
     * The write opcode, matching the Android service's 0x05.
     *
     * Fixed rather than chosen: a desktop announcing a record has to use the number the shipped phone build
     * already listens on, or the announcement is dropped as an unknown opcode.
     */
    const val OPCODE_DNS_WRITE: Byte = 0x05
}
