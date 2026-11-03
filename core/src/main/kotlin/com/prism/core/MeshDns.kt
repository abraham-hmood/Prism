package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The mesh name registry. PHASE 49.
 *
 * ## What this is, and what it is deliberately not
 *
 * A flat map from a name to the mesh addresses serving it, gossiped between peers. That is all. It is not
 * DNS in the protocol sense — there are no zones, no TTL hierarchy, no authority, and no recursion — and
 * calling it DNS is a description of its JOB rather than its design.
 *
 * It does not need to be more than that. The whole namespace is "names people on this mesh are hosting",
 * which is tens of entries on a home network, and every peer holds the entire thing. A real resolver's
 * machinery exists to avoid holding the whole internet, which is not the problem here.
 *
 * ## Why any name, and not only `.p2p`
 *
 * A mesh that could only answer for its own suffix was safe and useless: the moment somebody wanted
 * `shop.example.com` served by a peer, the resolver had to either refuse the name or answer it from
 * unauthenticated gossip -- and answering gossip is how one device on the Wi-Fi hijacked a domain for
 * everyone else. The answer is not to refuse ordinary names; it is to make a name's ownership
 * checkable. [MeshDnsLedger] signs every record and chains each update to the one before it, so a name
 * is answered from the mesh only when the block carrying it verifies and extends the chain this device
 * already holds. An ordinary domain works; an unowned claim does not.
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
 * of them would then gossip it onward. [Record.advertised] is the address the chain signs and the mesh
 * carries: the host's real mesh address, substituted at registration time.
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
        /** The first address this name was seen at. Tried first, and what a local resolver dials. */
        val ip: String,
        val alternates: Set<String> = emptySet(),
        val timestamp: Long = System.currentTimeMillis(),
        val source: Source = Source.MESH,
        /**
         * The address peers are told, and the one the signature covers.
         *
         * The same as [ip] for a record learned from the mesh. For a site hosted here it is this
         * device's mesh address, because the loopback address the local server actually listens on
         * means "myself" to every peer that read it.
         */
        val advertised: String = ip,
        /** The public key that claimed this name, base64url. Empty for a record nobody signed. */
        val owner: String = "",
        /** Position in this name's chain. 0 means unsigned and therefore not authoritative. */
        val height: Int = 0,
        /** The hash of the block this one was built on. Empty at height 1. */
        val prev: String = "",
        /** The owner's signature over this block, base64url. */
        val signature: String = "",
    ) {
        /** Every address, primary first, for a resolver to try in order. */
        fun addresses(): List<String> = (listOf(ip) + alternates.filter { it != ip }).distinct()

        /**
         * Whether this record carries a proof a resolver may answer from.
         *
         * A local registration is authoritative because this device made it. A mesh record is
         * authoritative only when it carries a signed block -- the old gossip records loaded from
         * disk have no owner and are kept for display but never answered.
         */
        val verified: Boolean
            get() = source == Source.LOCAL || (owner.isNotBlank() && height >= 1 && signature.isNotBlank())

        fun describe(): String = buildString {
            append(ip)
            if (alternates.size > 1) append(" (+${alternates.size - 1} more)")
            append(" · ")
            append(
                when {
                    source == Source.LOCAL -> "hosted here"
                    verified -> "verified · block $height"
                    else -> "from the mesh · unverified"
                },
            )
        }
    }

    private val index = ConcurrentHashMap<String, Record>()

    @Volatile private var storage: File? = null

    /** Called once at startup. Loads whatever was saved. */
    fun install(directory: File) {
        storage = directory.apply { mkdirs() }
        MeshDnsLedger.install(directory)
        load()
    }

    fun all(): Map<String, Record> = index.toMap()

    fun localRecords(): List<Record> = index.values.filter { it.source == Source.LOCAL }

    /**
     * Resolves a name to an address, or null.
     *
     * [onlyP2p] exists because the caller is usually deciding whether to route a connection over the mesh
     * AT ALL. A caller that asks for only mesh names gets a mesh name; one that asks for anything this
     * registry knows gets it, whichever suffix it ends in.
     */
    fun resolve(domain: String, onlyP2p: Boolean = true): String? {
        val key = domain.lowercase().trim().removeSuffix(".")
        if (key.isEmpty()) return null
        if (onlyP2p && !key.endsWith(SUFFIX) && !index.containsKey(key)) return null
        return index[key]?.ip
    }

    /**
     * The address to answer a DNS query with, or null when the name may not be answered from here.
     *
     * THIS IS THE GATE THE RESOLVER USES. It is deliberately stricter than [resolve]: a name whose
     * block does not verify is still visible to callers that ask about it, but it is never handed
     * to a system resolver as the truth.
     */
    fun resolveAuthoritative(domain: String): String? {
        val key = domain.lowercase().trim().removeSuffix(".")
        val record = index[key] ?: return null
        if (!record.verified) return null
        val address = record.ip
        return address.takeIf { it.isNotBlank() && it != "0.0.0.0" }
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
    fun put(domain: String, ip: String, source: Source = Source.MESH): Record? =
        putRecord(domain, ip, source, persist = true)

    private fun putRecord(
        domain: String,
        ip: String,
        source: Source,
        persist: Boolean,
    ): Record? {
        val key = domain.lowercase().trim().removeSuffix(".")
        // THE NAME AND THE ADDRESS ARE NOT FREE TEXT. This registry is written from the network and
        // its names become certificate subjects and resolver answers, so a comma or newline is an
        // injection; an address that is not a literal is a hostname somebody would have to resolve.
        if (!isValidName(key)) return null
        val address = ip.trim()
        if (!MeshUtils.isIpLiteral(address)) return null

        val existing = index[key]
        // A NAME HOSTED HERE IS NOT A PEER'S TO EDIT: resolution falls back to the alternates, so a
        // peer allowed to attach an address to a local record could serve this device's site.
        if (existing != null && existing.source == Source.LOCAL && source != Source.LOCAL) return existing
        if (existing == null && index.size >= MAX_RECORDS) return null

        val local = existing?.source == Source.LOCAL || source == Source.LOCAL
        val alternates = (listOf(address) + existing?.alternates.orEmpty().toList() +
            listOfNotNull(existing?.ip)).distinct().take(MAX_ALTERNATES).toSet()
        val primary = when {
            existing == null -> address
            // Loopback is a placeholder for "here", so a real address always beats it.
            existing.ip == "127.0.0.1" && address != "127.0.0.1" -> address
            else -> existing.ip
        }
        val timestamp = System.currentTimeMillis()

        // A LOCAL RECORD IS SIGNED HERE AND NOW. Pushing the block back through the mesh would be
        // checked by every peer exactly as a stranger's would, so it is the same proof either way.
        val advertised = when {
            !local -> address
            address == "127.0.0.1" || address == "localhost" ->
                MeshUtils.getLocalMeshIp().ifBlank { address }
            else -> address
        }
        val signedAlternates = if (local) alternates.filterNot { it == "127.0.0.1" }.toSet() else alternates
        val block = if (local) {
            MeshDnsLedger.sign(key, advertised, signedAlternates, existing, timestamp)
        } else {
            null
        }

        val record = Record(
            domain = key,
            ip = primary,
            alternates = signedAlternates,
            timestamp = timestamp,
            // LOCAL is sticky: learning a mesh copy of a site hosted here must not downgrade it, or the
            // host would stop believing it is the host.
            source = if (local) Source.LOCAL else source,
            advertised = advertised,
            owner = block?.owner.orEmpty(),
            height = block?.height ?: 0,
            prev = block?.prev.orEmpty(),
            signature = block?.signature.orEmpty(),
        )
        index[key] = record
        if (persist) save()
        return record
    }

    /** Labels of letters, digits, hyphen and underscore; 63 each, 253 in total. */
    fun isValidName(domain: String): Boolean {
        val key = domain.lowercase().trim().removeSuffix(".")
        if (key.isEmpty() || key.length > 253) return false
        return key.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        }
    }

    /**
     * Whether this device is willing to answer [domain] from the mesh.
     *
     * That used to mean "`.p2p` names and names hosted here", which kept a peer from redirecting an
     * ordinary domain but also meant the mesh could never serve one. Now the test is the PROOF rather
     * than the suffix: a record hosted here, or a mesh record whose signed block verifies. The name's
     * TLD has nothing to do with it.
     */
    fun isMeshAnswer(domain: String): Boolean {
        val key = domain.lowercase().trim().removeSuffix(".")
        return index[key]?.verified == true
    }

    fun remove(domain: String) {
        index.remove(domain.lowercase().trim().removeSuffix("."))
        save()
    }

    // ── Gossip ─────────────────────────────────────────────────────────────

    /**
     * This device's records, as the signed blocks the mesh carries.
     *
     * A record whose advertised address is loopback is omitted: it has nothing to tell a peer, and a
     * receiver would store an address that means "yourself".
     */
    fun exportJson(): String {
        val array = JSONArray()
        index.values.forEach { record ->
            val address = record.advertised.ifBlank { record.ip }
            if (address.isBlank() || address == "127.0.0.1" || address == "localhost") return@forEach
            if (!record.verified) return@forEach
            val block = MeshDnsLedger.fromRecord(record, address) ?: return@forEach
            array.put(MeshDnsLedger.toJson(block))
        }
        return array.toString()
    }

    /**
     * Merges a peer's records.
     *
     * Everything learned here is [Source.MESH] regardless of what the sender called it. A peer claiming its
     * records are LOCAL would be claiming to be the authority for a name on THIS device, and source is what
     * a later TLS decision would rest on — so it is assigned by the receiver, not accepted from the wire.
     *
     * A record without a verifying block is now dropped rather than stored: it has no owner, so there is
     * no way to tell its first claim from its hundredth hijack.
     */
    fun importJson(peerIp: String, json: String): Int {
        // A datagram is 16 KB and this registry holds dozens of names: anything larger is a flood.
        if (json.length > MAX_IMPORT_BYTES) {
            PrismPlatform.log.info(TAG, "Ignoring an oversized DNS payload from $peerIp")
            return 0
        }
        if (!MeshUtils.isIpLiteral(peerIp)) return 0
        var added = 0
        runCatching {
            val array = JSONArray(json)
            val limit = minOf(array.length(), MAX_IMPORTED_PER_PAYLOAD)
            for (i in 0 until limit) {
                val item = array.optJSONObject(i) ?: continue
                val domain = item.optString("d").lowercase().trim()
                if (!isValidName(domain)) continue
                if (index[domain]?.source == Source.LOCAL) continue

                val block = MeshDnsLedger.fromJson(item) ?: continue
                if (block.domain != domain) continue
                if (!MeshDnsLedger.verify(block)) {
                    PrismPlatform.log.info(TAG, "Rejected a DNS block for $domain from $peerIp: bad proof")
                    continue
                }
                if (accept(block)) added++
            }
            // ONE WRITE PER PAYLOAD. It used to save once per record, so a payload naming a thousand
            // names was a thousand whole-file rewrites on the receiving device.
            if (added > 0) save()
        }.onFailure { PrismPlatform.log.info(TAG, "Bad DNS payload from $peerIp") }
        return added
    }

    /**
     * Applies a block that already verified, if it is the next link for its name.
     *
     * FIRST CLAIM WINS, and only the claimant may move it. An unknown name is accepted at height 1
     * from whoever signed it; a known name is accepted only from the same key, at exactly the next
     * height, on top of the block this device holds. A competing fork is refused rather than
     * overwriting a live record, which is the whole difference between a name and a squatted name.
     */
    private fun accept(block: MeshDnsLedger.Block): Boolean {
        val existing = index[block.domain]
        if (existing?.source == Source.LOCAL) return false
        if (existing != null) {
            if (existing.owner != block.owner) return false
            if (existing.height <= 0) return false
            if (block.height != existing.height + 1) return false
            if (block.prev != MeshDnsLedger.hashOf(existing)) return false
            if (block.timestamp < existing.timestamp) return false
        } else {
            // A DEVICE THAT JOINED LATE STILL HAS TO LEARN THE NAME. Requiring height 1 here meant
            // a peer could only ever accept a name at its first claim: every update past block one
            // was dropped as "not genesis", so a name that moved was invisible to every device
            // that was not already holding it. The first block this device sees is its trust-on-
            // first-use anchor -- the signature proves who owns the name, and every later update
            // must chain onto THIS block, so the worst a stale anchor can do is refuse a legitimate
            // move until the owner's next block, never accept somebody else's.
            if (block.height < 1) return false
            if (index.size >= MAX_RECORDS) return false
        }

        index[block.domain] = Record(
            domain = block.domain,
            ip = block.ip,
            alternates = block.alternates,
            timestamp = block.timestamp,
            source = Source.MESH,
            advertised = block.ip,
            owner = block.owner,
            height = block.height,
            prev = block.prev,
            signature = block.signature,
        )
        return true
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
        val address = record.advertised.ifBlank { record.ip }
        if (address.isBlank() || address == "127.0.0.1" || address == "localhost") return
        val block = MeshDnsLedger.fromRecord(record, address) ?: return

        MeshCore.broadcastToOthers(OPCODE_DNS_WRITE, JSONArray().also { it.put(MeshDnsLedger.toJson(block)) }.toString())
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
        // ONLY A LIVE PEER MAY WRITE THE REGISTRY. These opcodes arrive as UDP from any host on the
        // network, and what lands here becomes resolver answers and certificate subjects, so an
        // address that has never answered a heartbeat must not be able to claim a mesh name.
        MeshCore.dnsConsumer = { peerIp, json ->
            if (MeshCore.isPeer(peerIp)) {
                importJson(peerIp, json)
            } else {
                PrismPlatform.log.info(TAG, "Ignoring DNS records from $peerIp: not a live mesh peer")
            }
        }
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
                val local = item.optString("src") == "local"
                // An old record has no proof. It is kept so nothing disappears from the UI, but
                // [verified] is false and the resolver will not answer it.
                index[domain] = Record(
                    domain = domain,
                    ip = item.optString("ip"),
                    alternates = alternates,
                    timestamp = item.optLong("at"),
                    source = if (local) Source.LOCAL else Source.MESH,
                    advertised = item.optString("adv").ifBlank { item.optString("ip") },
                    owner = item.optString("own"),
                    height = item.optInt("h", 0),
                    prev = item.optString("p"),
                    signature = item.optString("sig"),
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
                    put("adv", record.advertised)
                    put("own", record.owner)
                    put("h", record.height)
                    put("p", record.prev)
                    put("sig", record.signature)
                    put("alt", JSONArray().also { a -> record.alternates.forEach { a.put(it) } })
                }
            )
        }
        runCatching { target.writeText(array.toString()) }
    }

    /** The reserved suffix. A name without it can still be registered explicitly, by anybody. */
    const val SUFFIX = ".p2p"

    /**
     * The write opcode, matching the Android service's 0x05.
     *
     * Fixed rather than chosen: a desktop announcing a record has to use the number the shipped phone build
     * already listens on, or the announcement is dropped as an unknown opcode.
     */
    const val OPCODE_DNS_WRITE: Byte = 0x05

    /** The registry is dozens of names on a home mesh; this is a flood limit, not a working set. */
    private const val MAX_RECORDS = 2_000
    private const val MAX_ALTERNATES = 16
    private const val MAX_IMPORTED_PER_PAYLOAD = 256
    private const val MAX_IMPORT_BYTES = 64 * 1024
}
