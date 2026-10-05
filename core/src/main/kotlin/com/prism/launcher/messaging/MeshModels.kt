package com.prism.launcher.messaging

import com.prism.core.MeshConnect
import com.prism.core.MeshCore
import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Models other devices on the mesh are hosting, and this device's own offer of one. PHASE 53.
 *
 * ## What "using a peer's model" actually means
 *
 * The weights stay where they are. A device with a 4 GB model announces that it has it; a device that
 * picks it sends prompts over the PRISM_CONNECT tunnel and gets tokens back. Nothing is downloaded and
 * nothing is copied — which is the point, because the phone that wants to use a desktop's 14B model is
 * exactly the device that cannot hold it.
 *
 * ## Why this is a separate registry rather than a DNS record
 *
 * A DNS record maps a name to an address and has nowhere to put "which model". The Android build already
 * made this decision and gossips model announcements on opcode 0x0C; this speaks the same opcode with the
 * same payload, so a desktop and a phone see each other's models with no change on either.
 *
 * ## Why nothing here is persisted
 *
 * An entry means "that device is up, on this network, and hosting that model right now". A claim
 * restored from disk a week later is a peer that may be off, on another network, or hosting something
 * else, and the user would pick it and get a timeout. Every host re-announces, so the cost of forgetting
 * is one gossip round.
 */
object MeshModels {

    private const val TAG = "PrismMeshModels"

    /** Matches P2pModelRegistry.OPCODE_MODEL_ANNOUNCE. Fixed by the shipped Android build. */
    const val OPCODE_ANNOUNCE: Byte = 0x0C

    /**
     * The reserved handshake domain for AI requests.
     *
     * Never resolved through DNS: a client connects straight to the peer's address and sends this as the
     * PRISM_CONNECT domain, which is how the far side knows to dispatch the connection to its AI host
     * rather than to a website.
     */
    const val HOST_DOMAIN = "ai-model.prism.p2p"

    /** Dropped after this long without hearing from the host. */
    private const val STALE_MS = 10 * 60 * 1000L

    data class Hosted(
        val peerIp: String,
        val modelName: String,
        val at: Long = System.currentTimeMillis(),
    ) {
        /** How PrismSettings stores a choice of this model. */
        val id: String get() = "$peerIp/$modelName"
    }

    private val hosted = ConcurrentHashMap<String, Hosted>()

    /** Called when the set changes, so a models page can refresh. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    // ── What other devices offer ───────────────────────────────────────────

    /**
     * A platform that keeps its own registry, merged into this one.
     *
     * Android's mesh service predates MeshCore and holds model announcements in P2pModelRegistry; it
     * receives the same opcode with the same payload, just somewhere else. Rather than have the Android
     * build run a second listener -- two sockets on one port fight -- the page asks here and this reaches
     * across. See MeshCore's note on why the Android service was deliberately not refactored.
     */
    @Volatile
    var extraSource: (() -> List<Hosted>)? = null

    /** Everything currently on offer, forgetting hosts that have gone quiet. */
    fun available(): List<Hosted> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val stale = hosted.filterValues { it.at < cutoff }.keys
        stale.forEach { hosted.remove(it) }

        val local = MeshUtils.getAllLocalIps()
        val extra = runCatching { extraSource?.invoke() }.getOrNull().orEmpty()
        return (hosted.values + extra)
            .filter { it.peerIp !in local && it.at >= cutoff }
            .distinctBy { it.id }
            .sortedBy { it.modelName.lowercase() }
    }

    fun byId(id: String): Hosted? = available().firstOrNull { it.id == id }

    /** Records a peer's announcement. An empty name means it stopped hosting. */
    fun ingest(peerIp: String, modelName: String) {
        if (modelName.isBlank()) {
            if (hosted.remove(peerIp) != null) onChanged?.invoke()
            return
        }
        val existing = hosted[peerIp]
        hosted[peerIp] = Hosted(peerIp, modelName)
        if (existing?.modelName != modelName) {
            PrismPlatform.log.info(TAG, "$peerIp is hosting $modelName")
            onChanged?.invoke()
        }
    }

    // ── What this device offers ────────────────────────────────────────────

    /**
     * Announces this device's active local model to the mesh, or withdraws the offer.
     *
     * Called when the model changes and whenever hosting is switched on or off. Broadcast rather than
     * sent to each peer, because the set of peers changes and an announcement nobody wanted costs one
     * datagram.
     */
    fun announce() {
        val name = if (PrismSettings.getP2pModelHostingEnabled()) {
            File(PrismSettings.getLocalAiModelPath()).takeIf { it.isFile }?.name.orEmpty()
        } else {
            ""
        }
        runCatching {
            MeshCore.broadcastToOthers(OPCODE_ANNOUNCE, JSONObject().apply { put("model", name) }.toString())
        }.onFailure { PrismPlatform.log.info(TAG, "Could not announce: " + it.message) }

        if (name.isNotBlank()) PrismPlatform.log.info(TAG, "Offering $name to the mesh")
    }

    /**
     * Wires the announcement opcode into the portable mesh.
     *
     * Android registers the same opcode in its own service, so this is the desktop's half. Both keep
     * their own registry and both speak the same payload.
     */
    fun registerWithMesh() {
        MeshCore.register(OPCODE_ANNOUNCE) { peerIp, payload ->
            val name = runCatching { JSONObject(payload).optString("model") }.getOrDefault("")
            ingest(peerIp, name)
            true
        }
    }

    // ── Using one ──────────────────────────────────────────────────────────

    /**
     * Runs a prompt on a peer's model. Blocking.
     *
     * POST /generate with the prompt as the raw body — no JSON envelope, because this is Prism talking
     * to Prism rather than a public API, and it is the shape the Android host already answers.
     *
     * Returns null when the peer cannot be reached, which the caller reports rather than silently
     * falling back to a different model: a user who chose a specific model and got an answer from
     * another one has been misled about what answered them.
     */
    fun generate(host: Hosted, prompt: String, timeoutMs: Int = 120_000): String? {
        val reply = MeshConnect.request(
            peerIp = host.peerIp,
            domain = HOST_DOMAIN,
            path = "/generate",
            body = prompt.toByteArray(),
            timeoutMs = timeoutMs,
        ) ?: return null
        return reply.toString(Charsets.UTF_8)
    }

    /** Whether a peer answers at all, for a page that wants to show a model as reachable. */
    fun reachable(host: Hosted): Boolean =
        MeshConnect.connect(host.peerIp, HOST_DOMAIN, timeoutMs = 4_000)?.also {
            runCatching { it.close() }
        } != null
}
