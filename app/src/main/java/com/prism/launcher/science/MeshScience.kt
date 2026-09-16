package com.prism.launcher.science

import com.prism.core.MeshUtils
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.mesh.PrismMeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The mesh side of the Science page.
 *
 * ## Why these instruments need other phones at all
 *
 * Three of the four are not single-device instruments and cannot be made into one:
 *
 * - **Cosmic rays.** One phone seeing a bright pixel cluster has seen sensor noise. Two phones
 *   seeing one in the same millisecond have seen an air shower. Coincidence is not a refinement on
 *   top of single-device detection -- it is the entire difference between physics and noise, and it
 *   is how every real detector array works.
 * - **Witnessing a notebook.** A record you signed yourself proves only that you had the key. A
 *   record countersigned by devices that are not yours is the part that means something to anyone
 *   else.
 * - **RF surveying.** One person walking a building takes an hour and the radio environment changes
 *   underneath them. Three people walking it at once takes twenty minutes and the samples are
 *   contemporaneous, which is the difference between a map and an anecdote.
 *
 * ## The gate
 *
 * Everything here is inert unless [PrismMeshService.isOnMesh] is true. That check is deliberately
 * agnostic about how the mesh exists -- this device serving it over Wi-Fi, this device being the
 * hotspot, or this device having joined someone else's -- because the instruments do not care. What
 * they need is peers that can be reached, and that is exactly what the flag reports.
 */
object MeshScience {

    private const val TAG = "PrismScience"

    /** A particle hit, with the time it happened. Coincidence is decided from these. */
    const val OPCODE_COSMIC_HIT: Byte = 0x24

    /** "Please countersign this notebook entry hash." */
    const val OPCODE_WITNESS_REQUEST: Byte = 0x25

    /** A countersignature coming back. */
    const val OPCODE_WITNESS_REPLY: Byte = 0x26

    /** One radio measurement, taken at a marked point. */
    const val OPCODE_RF_SAMPLE: Byte = 0x27

    /**
     * How far apart two hits may be and still count as the same shower.
     *
     * An extensive air shower's particle front is metres thick and crosses two phones in well under
     * a microsecond, so physically this window should be tiny. It is 50 ms because that is the
     * honest bound on how well two Android phones know the time: the clocks are NTP-disciplined at
     * best, and a window narrower than the clock error would reject every real coincidence. This is
     * the single biggest source of accidental coincidences here, and the reason the panel reports an
     * expected accidental rate alongside the observed one -- see [CosmicRayPanel].
     */
    const val COINCIDENCE_WINDOW_MS = 50L

    // ── Whether any of this can work ───────────────────────────────────────

    /** True when this device is on a Prism mesh, as server, hotspot, or client. */
    fun isOnMesh(): Boolean = runCatching { PrismMeshService.isOnMesh() }.getOrDefault(false)

    fun peerCount(): Int = runCatching { PrismMeshService.activePeerIps().size }.getOrDefault(0)

    /** One sentence for a panel to show in place of its controls when the mesh is down. */
    fun unavailableReason(): String = when {
        !isOnMesh() ->
            "This instrument needs the Prism meshnet. Turn the mesh on in Settings and either " +
                "join one or let this device serve one -- over Wi-Fi or its own hotspot, either works."
        peerCount() == 0 ->
            "The mesh is up but no other device has appeared yet. This instrument needs at least " +
                "one peer to be meaningful."
        else -> ""
    }

    fun isUsable(): Boolean = isOnMesh() && peerCount() > 0

    // ── Cosmic-ray coincidence ─────────────────────────────────────────────

    data class RemoteHit(val peerIp: String, val atMs: Long, val brightness: Int, val pixels: Int)

    private val recentHits = ArrayDeque<RemoteHit>()

    private val _coincidences = MutableStateFlow(0)
    val coincidences: StateFlow<Int> = _coincidences

    /** Announces a local hit so peers can match it against their own. */
    fun broadcastHit(atMs: Long, brightness: Int, pixels: Int) {
        if (!isOnMesh()) return
        PrismMeshService.broadcastToOthers(
            OPCODE_COSMIC_HIT,
            JSONObject().apply {
                put("at", atMs)
                put("bright", brightness)
                put("px", pixels)
            }.toString()
        )
    }

    fun ingestHit(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val hit = RemoteHit(peerIp, json.optLong("at"), json.optInt("bright"), json.optInt("px"))
            synchronized(recentHits) {
                recentHits.addLast(hit)
                // Anything older than a second cannot coincide with anything still to arrive, and
                // an unbounded list of hits would grow for as long as the page is open.
                val cutoff = System.currentTimeMillis() - 1_000
                while (recentHits.isNotEmpty() && recentHits.first().atMs < cutoff) recentHits.removeFirst()
            }
        }
    }

    /**
     * Whether a local hit coincides with one from another device.
     *
     * Checked against hits from OTHER peers only. Two hits from the same phone in 50 ms are two
     * pieces of sensor noise, not a shower crossing two detectors.
     */
    fun matchCoincidence(localAtMs: Long): RemoteHit? {
        val match = synchronized(recentHits) {
            recentHits.firstOrNull { kotlin.math.abs(it.atMs - localAtMs) <= COINCIDENCE_WINDOW_MS }
        }
        if (match != null) {
            _coincidences.value = _coincidences.value + 1
            PrismLogger.logInfo(TAG, "Coincidence with ${match.peerIp} at ${match.atMs}")
        }
        return match
    }

    fun resetCoincidences() {
        _coincidences.value = 0
        synchronized(recentHits) { recentHits.clear() }
    }

    // ── Notebook witnessing ────────────────────────────────────────────────

    /** Countersignatures that have come back, keyed by the entry hash they attest to. */
    private val witnesses = mutableMapOf<String, MutableList<String>>()

    private val _witnessUpdates = MutableStateFlow(0L)
    val witnessUpdates: StateFlow<Long> = _witnessUpdates

    fun requestWitness(entryHash: String, summary: String) {
        if (!isOnMesh()) return
        PrismMeshService.broadcastToOthers(
            OPCODE_WITNESS_REQUEST,
            JSONObject().apply {
                put("hash", entryHash)
                put("summary", summary.take(200))
            }.toString()
        )
    }

    /**
     * Answers a peer's request to witness an entry.
     *
     * A witness attests to ONE thing: that this hash existed on the mesh at this moment. It does not
     * attest that the science is right, that the author is honest, or that the contents are what the
     * summary says -- the witness never sees the contents. Saying so precisely is what keeps the
     * signature meaningful; a witness statement that implied more would be worth less.
     */
    fun onWitnessRequest(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val hash = json.optString("hash")
            if (hash.isBlank()) return
            val seenAt = System.currentTimeMillis()
            val attestation = LabNotebook.signAsWitness(hash, seenAt) ?: return
            PrismMeshService.sendToPeer(
                peerIp,
                OPCODE_WITNESS_REPLY,
                JSONObject().apply {
                    put("hash", hash)
                    put("at", seenAt)
                    put("by", MeshUtils.getLocalMeshIp())
                    put("sig", attestation)
                }.toString()
            )
        }
    }

    fun onWitnessReply(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val hash = json.optString("hash")
            if (hash.isBlank()) return
            val line = "${json.optString("by").ifBlank { peerIp }}@${json.optLong("at")}:${json.optString("sig")}"
            synchronized(witnesses) {
                witnesses.getOrPut(hash) { mutableListOf() }.let { list ->
                    if (list.none { it.startsWith(line.substringBefore('@')) }) list.add(line)
                }
            }
            _witnessUpdates.value = System.currentTimeMillis()
        }
    }

    fun witnessesFor(hash: String): List<String> =
        synchronized(witnesses) { witnesses[hash]?.toList().orEmpty() }

    // ── RF survey sharing ──────────────────────────────────────────────────

    data class RemoteSample(
        val peerIp: String,
        val label: String,
        val ssid: String,
        val rssi: Int,
        val frequencyMhz: Int,
        val atMs: Long,
    )

    private val remoteSamples = mutableListOf<RemoteSample>()

    private val _surveyUpdates = MutableStateFlow(0L)
    val surveyUpdates: StateFlow<Long> = _surveyUpdates

    fun broadcastSample(label: String, ssid: String, rssi: Int, frequencyMhz: Int) {
        if (!isOnMesh()) return
        PrismMeshService.broadcastToOthers(
            OPCODE_RF_SAMPLE,
            JSONObject().apply {
                put("label", label)
                put("ssid", ssid)
                put("rssi", rssi)
                put("freq", frequencyMhz)
                put("at", System.currentTimeMillis())
            }.toString()
        )
    }

    fun ingestSample(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            synchronized(remoteSamples) {
                remoteSamples.add(
                    RemoteSample(
                        peerIp = peerIp,
                        label = json.optString("label"),
                        ssid = json.optString("ssid"),
                        rssi = json.optInt("rssi"),
                        frequencyMhz = json.optInt("freq"),
                        atMs = json.optLong("at"),
                    )
                )
                if (remoteSamples.size > 2000) remoteSamples.removeAt(0)
            }
            _surveyUpdates.value = System.currentTimeMillis()
        }
    }

    fun remoteSamples(): List<RemoteSample> = synchronized(remoteSamples) { remoteSamples.toList() }

    fun clearRemoteSamples() {
        synchronized(remoteSamples) { remoteSamples.clear() }
        _surveyUpdates.value = System.currentTimeMillis()
    }
}
