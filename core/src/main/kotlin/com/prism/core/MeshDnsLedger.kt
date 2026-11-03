package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The chain that decides who may change a mesh name.
 *
 * ## The problem this solves
 *
 * The registry used to be plain gossip: whoever announced a name last won. That is fine for
 * `notes.p2p` and catastrophic for `bank.com`, because one device on the Wi-Fi could announce an
 * ordinary domain and redirect it on every peer that trusted the mesh. Blocking ordinary names
 * fixed the hijack by refusing the feature people actually wanted -- the mesh could not serve a
 * domain anybody already knew.
 *
 * ## What a block is
 *
 * Each name has a chain of blocks. A block says "at height N, name X is at address Y, and the
 * previous block was hash P". It is signed with the key that first claimed the name, and the
 * signature covers the whole block *including* the previous hash. So:
 *
 *   - THE FIRST BLOCK FOR A NAME FIXES ITS OWNER. Nobody else can produce block N+1, because
 *     nobody else holds the private key the chain started with.
 *   - A LATER BLOCK IS ONLY VALID ON TOP OF THE CURRENT ONE. Height must advance by exactly one
 *     and [Block.prev] must equal the hash this device already holds, so a fork has to be signed
 *     by the same owner and is rejected as stale rather than silently replacing the live record.
 *   - THE NAME IS BOUND TO THE BLOCK. The signature covers the domain, so a block for
 *     `shop.p2p` cannot be replayed as a block for `bank.com`.
 *
 * This is a permissioned hash chain, not proof-of-work: there is no mining, no difficulty, and no
 * global consensus, because the only parties who need to agree are the devices on one mesh. The
 * property it buys is the one that matters -- a name can only be repointed by whoever claimed it,
 * and every peer can check that claim without trusting the sender.
 *
 * ## Keys
 *
 * One P-256 key per device, generated on first use and kept beside the registry. EC rather than
 * Ed25519 because Android has supported EC keys since long before its Ed25519 provider existed,
 * and the mesh has to include phones old enough to matter. Losing the key file means losing the
 * ability to update this device's own names; it does not let anyone else claim them, because the
 * first block still names the old public key.
 */
object MeshDnsLedger {

    private const val TAG = "PrismDnsLedger"

    /** Bumped only if the canonical form below ever changes; an old block then fails to verify. */
    private const val VERSION = "prism-dns-block-1"

    /** How far ahead of this clock a block may claim to be from, to stop a far-future timestamp. */
    private const val CLOCK_SKEW_MS = 60L * 60 * 1000

    /** A name not updated for this long may still be read, but a block older than it is stale. */
    const val MAX_AGE_MS = 365L * 24 * 60 * 60 * 1000

    /** A signed, chained statement about one name. */
    data class Block(
        val domain: String,
        val ip: String,
        val alternates: Set<String>,
        val height: Int,
        val prev: String,
        val owner: String,
        val timestamp: Long,
        val hash: String,
        val signature: String,
    )

    @Volatile
    private var directory: File? = null

    private val keys = ConcurrentHashMap<String, KeyPair>()

    /** Called from [MeshDns.install], before any record is loaded. */
    fun install(directory: File) {
        this.directory = directory.apply { mkdirs() }
    }

    // ── Identity ───────────────────────────────────────────────────────────

    /**
     * This device's public key, base64url, which is also its owner id on the chain.
     *
     * Lazily generated so the first launch of a fresh install does not pay for key generation on
     * a path that may never register a name.
     */
    fun owner(): String = encode((keys.computeIfAbsent("self") { loadOrCreate() }).public.encoded)

    /** A fresh block for [domain], chained onto [previous] when there is one, signed by this device. */
    fun sign(
        domain: String,
        ip: String,
        alternates: Set<String>,
        previous: MeshDns.Record?,
        timestamp: Long = System.currentTimeMillis(),
    ): Block? {
        val key = keys.computeIfAbsent("self") { loadOrCreate() }
        val height = (previous?.height ?: 0) + 1
        val prev = previous?.let { hashOf(it) }.orEmpty()
        val owner = encode(key.public.encoded)
        val hash = hashOf(domain, ip, alternates, height, prev, owner, timestamp)
        val signature = runCatching {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(key.private)
            signer.update(hash.toByteArray(Charsets.UTF_8))
            encode(signer.sign())
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not sign a DNS block: ${it.message}")
        }.getOrNull() ?: return null
        return Block(
            domain = domain,
            ip = ip,
            alternates = alternates,
            height = height,
            prev = prev,
            owner = owner,
            timestamp = timestamp,
            hash = hash,
            signature = signature,
        )
    }

    /**
     * Checks a peer's block on its own terms: the fields are sane for a DNS record, the hash is
     * the hash of what the block says, and the signature verifies against the key it names.
     *
     * Chain POSITION is not checked here -- that needs the record this device already holds, and
     * it is [MeshDns]'s job because it owns the index.
     */
    fun verify(block: Block): Boolean {
        if (block.domain.isBlank() || block.domain.length > 253) return false
        if (!MeshDns.isValidName(block.domain)) return false
        if (!MeshUtils.isIpLiteral(block.ip)) return false
        if (block.alternates.any { !MeshUtils.isIpLiteral(it) }) return false
        if (block.height < 1) return false
        if (block.timestamp <= 0) return false
        if (block.timestamp > System.currentTimeMillis() + CLOCK_SKEW_MS) return false
        if (block.timestamp < System.currentTimeMillis() - MAX_AGE_MS) return false
        if (block.owner.isBlank() || block.signature.isBlank()) return false

        val expected = hashOf(
            block.domain, block.ip, block.alternates,
            block.height, block.prev, block.owner, block.timestamp,
        )
        if (expected != block.hash) return false

        return runCatching {
            val public = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(decode(block.owner)),
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(public)
            verifier.update(block.hash.toByteArray(Charsets.UTF_8))
            verifier.verify(decode(block.signature))
        }.getOrDefault(false)
    }

    /** The hash of a stored record, for the next block's `prev`. */
    fun hashOf(record: MeshDns.Record): String =
        hashOf(record.domain, record.ip, record.alternates, record.height, record.prev, record.owner, record.timestamp)

    /**
     * The canonical bytes a hash is taken over.
     *
     * Alternates are SORTED, so two devices that learned the same addresses in a different order
     * agree on the hash and neither chain is rejected as forged.
     */
    fun hashOf(
        domain: String,
        ip: String,
        alternates: Set<String>,
        height: Int,
        prev: String,
        owner: String,
        timestamp: Long,
    ): String {
        val canonical = buildString {
            append(VERSION).append('\n')
            append(domain.lowercase().trim().removeSuffix(".")).append('\n')
            append(ip).append('\n')
            append(alternates.sorted().joinToString(",")).append('\n')
            append(height).append('\n')
            append(prev).append('\n')
            append(owner).append('\n')
            append(timestamp)
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * The block a stored record was signed as, for export.
     *
     * Returns null when the record carries no proof, or when [address] is not the address the
     * signature covers -- a local record whose mesh address changed since it was registered. That
     * record is not exported as a stale proof; the host re-registers it, which starts the next block
     * and re-signs the new address. A block that claimed one address while carrying a signature for
     * another would be rejected by every peer anyway, so it is better not to send it.
     */
    fun fromRecord(record: MeshDns.Record, address: String): Block? {
        if (record.owner.isBlank() || record.height < 1) return null
        if (record.signature.isBlank()) return null
        if (address != record.advertised) return null
        val hash = hashOf(
            record.domain, address, record.alternates,
            record.height, record.prev, record.owner, record.timestamp,
        )
        return Block(
            record.domain, address, record.alternates, record.height, record.prev,
            record.owner, record.timestamp, hash, record.signature,
        )
    }

    // ── Wire form ──────────────────────────────────────────────────────────

    /** The block as the JSON a peer receives. */
    fun toJson(block: Block): JSONObject = JSONObject().apply {
        put("d", block.domain)
        put("ip", block.ip)
        put("alt", JSONArray().also { array -> block.alternates.forEach { array.put(it) } })
        put("h", block.height)
        put("p", block.prev)
        put("own", block.owner)
        put("ts", block.timestamp)
        put("hash", block.hash)
        put("sig", block.signature)
    }

    /** Reads a block from a peer's JSON, or null when a field is missing or the wrong shape. */
    fun fromJson(item: JSONObject): Block? {
        val domain = item.optString("d").lowercase().trim().removeSuffix(".")
        val ip = item.optString("ip").trim()
        val height = item.optInt("h", 0)
        val prev = item.optString("p").trim()
        val owner = item.optString("own").trim()
        val timestamp = item.optLong("ts", 0L)
        val hash = item.optString("hash").trim()
        val signature = item.optString("sig").trim()
        if (domain.isBlank() || ip.isBlank() || owner.isBlank() || hash.isBlank() || signature.isBlank()) {
            return null
        }
        val alternates = mutableSetOf<String>()
        val array = item.optJSONArray("alt")
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optString(index)?.trim()?.takeIf { it.isNotBlank() }?.let { alternates.add(it) }
        }
        return Block(domain, ip, alternates, height, prev, owner, timestamp, hash, signature)
    }

    // ── Keys on disk ───────────────────────────────────────────────────────

    private fun loadOrCreate(): KeyPair {
        val folder = directory ?: File(PrismPlatform.host.dataDir(), "mesh").apply { mkdirs() }
        val privateFile = File(folder, "dns-key.pk8")
        val publicFile = File(folder, "dns-key.pub")
        runCatching {
            if (privateFile.isFile && publicFile.isFile) {
                val factory = KeyFactory.getInstance("EC")
                val private = factory.generatePrivate(
                    PKCS8EncodedKeySpec(privateFile.readBytes()),
                )
                val public = factory.generatePublic(
                    X509EncodedKeySpec(publicFile.readBytes()),
                )
                return KeyPair(public, private)
            }
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Stored DNS key unreadable (${it.message}); generating a new one")
        }

        val generated = runCatching {
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        }.onFailure {
            PrismPlatform.log.warn(TAG, "This platform cannot generate an EC key: ${it.message}")
        }.getOrNull() ?: throw IllegalStateException("No EC key provider")

        // WRITTEN 0600 WHERE THE PLATFORM HONOURS IT. This key is the only thing that can repoint
        // this device's names; on Android the directory is already app-private, and on desktop the
        // permission bits are the extra that stops another local account reading it.
        runCatching {
            privateFile.writeBytes(generated.private.encoded)
            publicFile.writeBytes(generated.public.encoded)
            privateFile.setReadable(false, false)
            privateFile.setReadable(true, true)
            privateFile.setWritable(false, false)
            privateFile.setWritable(true, true)
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not persist the DNS key: ${it.message}")
        }
        return generated
    }

    // ── Encoding ───────────────────────────────────────────────────────────

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
