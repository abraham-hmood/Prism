package com.prism.launcher.speech

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Gossip registry for Kokoro and voice-cloning availability across the mesh.
 *
 * ## What a machine announces
 *
 *  - `has_kokoro`: Kokoro-82M is installed and can render audio here.
 *  - `has_voice_cloner`: at least one clone engine's ONNX files are installed.
 *  - `name`: what to call this machine in somebody else's list.
 *  - `kokoro_voices`: how many voice tensors are on disk (cosmetic; the caller can still ask for
 *    one this machine does not have, and the answer says so).
 *
 * Nothing more expensive than a file-existence check travels on the wire, and nothing here is
 * persisted: a "this desktop has Kokoro" entry that outlived the desktop would put a machine in
 * everybody's list that cannot answer.
 *
 * ## Announcing is the opt-in, and the mesh is the boundary
 *
 * A device appears in another node's list only after [announce] has run on it, and [announce]
 * does nothing unless the mesh transport is actually up. So a desktop that is merely running
 * Prism -- or one whose mesh is switched off -- is invisible. A desktop that has joined OR is
 * serving a meshnet announces on the same terms: the transport does not distinguish client from
 * server, and neither does this. That is exactly the requested behaviour: either role makes the
 * machine's models available to the others.
 *
 * ## Consuming a remote model
 *
 * The heavy work does not travel over the gossip socket. The serving machine listens on
 * [KOKORO_HTTP_PORT] ([SpeechMeshServer]) and:
 *
 *  - `POST /synthesize` accepts `{text, voice, speed}` and returns WAV. [synthesizeOn] is the
 *    client for that, and [MeshSpeechEngine] wraps it back into the ordinary [TtsEngine] shape so
 *    Sam, Nora, Aether and the language lessons can all speak through a desktop without knowing
 *    it.
 *  - `POST /clone` accepts float32 samples and returns the active clone engine's prepared
 *    artifacts, base64-ed. [prepareCloneOn] plus [ClonedVoices.importPrepared] is the client for
 *    that: a phone that cannot run the ONNX encoder still ends up with a usable cloned voice.
 *
 * ## Opcode
 *
 * 0x58. The scaffold that preceded this file picked 0x30, which is NOT free -- 0x30 is
 * `AetherMeshSync.OPCODE_AETHER_ANNOUNCE`, 0x31 is Nebula's. A speech announcement under 0x30
 * would have been delivered to Aether's parser and silently ignored, which is the worst possible
 * failure for a discovery feature. 0x58 is above every current allocation (the minigame block ends
 * at 0x57) and collides with nothing.
 */
object KokoroMeshShare {

    private const val TAG = "PrismSpeechMesh"

    /** One opcode: everything else is HTTP. See the class comment for why 0x58. */
    const val OPCODE_KOKORO_ANNOUNCE: Byte = 0x58

    /** How long a peer's announcement is trusted before it is considered gone. */
    private const val STALE_MS = 15 * 60 * 1000L

    /** Re-announce at most this often, so a gossip loop cannot turn into a broadcast storm. */
    private const val ANNOUNCE_EVERY_MS = 60 * 1000L

    /**
     * Port this device serves Kokoro synthesis and voice cloning HTTP on, when it is serving.
     *
     * Fixed, because peers have to be told it anyway and a port that moves would need a second
     * discovery protocol to find. Same reasoning as [com.prism.launcher.mesh.MeshInference.RPC_PORT].
     */
    const val KOKORO_HTTP_PORT = 47812

    /** One peer as the speech list sees it. */
    data class SpeechPeer(
        val peerIp: String,
        val deviceName: String,
        val hasKokoro: Boolean,
        val hasVoiceCloner: Boolean,
        val kokoroVoiceCount: Int,
        val timestamp: Long,
    ) {
        /** True once this announcement is older than [STALE_MS]. */
        val isStale: Boolean get() = System.currentTimeMillis() - timestamp > STALE_MS

        /** Where this peer's [SpeechMeshServer] answers. */
        val httpEndpoint: String get() = "http://$peerIp:$KOKORO_HTTP_PORT"
    }

    private val peers = ConcurrentHashMap<String, SpeechPeer>()

    private val _kokoroPeers = MutableStateFlow<List<SpeechPeer>>(emptyList())

    /** Every currently-fresh peer that can render Kokoro. */
    val kokoroPeers: StateFlow<List<SpeechPeer>> = _kokoroPeers

    private val _voiceClonerPeers = MutableStateFlow<List<SpeechPeer>>(emptyList())

    /** Every currently-fresh peer that can prepare a cloned voice. */
    val voiceClonerPeers: StateFlow<List<SpeechPeer>> = _voiceClonerPeers

    private var lastAnnounce = 0L

    // ── Hooks installed by the platform ───────────────────────────────────

    /**
     * Whether this device has Kokoro installed. A hook because the answer is a file check and a
     * test may need to say yes without an 86 MB download.
     */
    @Volatile
    var localHasKokoro: () -> Boolean = { KokoroInstall.isModelInstalled() }

    /** Whether this device has at least one voice-cloning model on disk. */
    @Volatile
    var localHasVoiceCloner: () -> Boolean = { CloneEngines.hasAnyInstalled() }

    /** How many voice tensors are installed here. Cosmetic; gates nothing. */
    @Volatile
    var localVoiceCount: () -> Int = { KokoroInstall.installedVoices().size }

    /**
     * What this machine is called in other people's lists.
     *
     * The hostname by default; Android replaces it with the build model, because an Android
     * hostname is frequently "localhost" and a list of four "localhost"s helps nobody.
     */
    @Volatile
    var localDeviceName: () -> String = {
        runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrDefault("Prism device")
            .ifBlank { "Prism device" }
    }

    // ── Announcing ────────────────────────────────────────────────────────

    /**
     * Makes this machine's models reachable and tells the mesh about them.
     *
     * Safe to call from a gossip loop: the server start is idempotent, the broadcast is throttled
     * to once a minute, and a machine with neither model says nothing at all rather than
     * advertising an empty list.
     *
     * [force] skips the throttle. It exists for the desktop switch: a user who has just turned
     * sharing on should not then wait up to a minute (or three, on the desktop's announce timer)
     * to appear in anyone's list.
     */
    fun announce(force: Boolean = false) {
        // The user's preference wins over every other reason to serve. Turning it off stops the
        // listener on the next call even if the mesh is up, and the app's own announce timer then
        // keeps it stopped.
        if (!com.prism.launcher.PrismSettings.getMeshSpeechSharingEnabled()) {
            if (SpeechMeshServer.isRunning()) SpeechMeshServer.stop()
            return
        }
        if (!MeshTransport.isOnMesh()) return

        val hasKokoro = runCatching { localHasKokoro() }.getOrDefault(false)
        val hasCloner = runCatching { localHasVoiceCloner() }.getOrDefault(false)
        if (!hasKokoro && !hasCloner) return

        // Starting the HTTP listener before the announcement matters: a peer that reads the
        // announcement and calls within the same second would otherwise find a closed port and
        // mark this machine broken.
        //
        // AND DO NOT ANNOUNCE IF IT DID NOT START. A machine that advertises Kokoro while its
        // port is closed is worse than one that stays quiet: peers list it, choose it, and every
        // reply fails. The common cause is a firewall or another process holding the port, and the
        // honest response is to stay out of the list until it can actually answer.
        val serving = runCatching { SpeechMeshServer.startIfAvailable() }.getOrDefault(false)
        if (!serving) {
            PrismPlatform.log.warn(
                TAG,
                "Not announcing speech: the HTTP port could not be opened " +
                    "(${SpeechMeshServer.lastError ?: "mesh transport not ready"})",
            )
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastAnnounce < ANNOUNCE_EVERY_MS) return
        lastAnnounce = now

        val name = runCatching { localDeviceName() }.getOrDefault("Prism device")
        val payload = JSONObject()
            .put("name", name)
            .put("sharing", true)
            .put("has_kokoro", hasKokoro)
            .put("has_voice_cloner", hasCloner)
            .put("kokoro_voices", runCatching { localVoiceCount() }.getOrDefault(0))
            .toString()

        MeshTransport.announce(OPCODE_KOKORO_ANNOUNCE, payload)
        PrismPlatform.log.info(
            TAG,
            "Announced speech: name=$name kokoro=$hasKokoro cloner=$hasCloner",
        )
    }

    /**
     * Applies the desktop sharing switch immediately.
     *
     * Both directions reach the mesh at once: turning it on starts the listener and forces an
     * announcement, and turning it off closes the listener and sends a withdrawal so peers drop
     * this machine now rather than when the fifteen-minute expiry notices it is gone. The setting
     * itself is written first so a crash between the two leaves the safe state (off stays off).
     */
    fun setSharingEnabled(enabled: Boolean) {
        com.prism.launcher.PrismSettings.setMeshSpeechSharingEnabled(enabled)
        if (!enabled) {
            runCatching { SpeechMeshServer.stop() }
            // Best-effort: no transport, or no peers, and the expiry is the backstop.
            runCatching {
                if (MeshTransport.isOnMesh()) {
                    val name = runCatching { localDeviceName() }.getOrDefault("Prism device")
                    MeshTransport.announce(
                        OPCODE_KOKORO_ANNOUNCE,
                        JSONObject().put("name", name).put("sharing", false).toString(),
                    )
                }
            }
            PrismPlatform.log.info(TAG, "Speech sharing turned off")
            return
        }
        lastAnnounce = 0L
        announce(force = true)
    }

    // ── Receiving ────────────────────────────────────────────────────────

    /** Called by the platform dispatch loop when a speech-announce packet arrives. */
    fun ingestFromPeer(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            // A withdrawal: the machine's user turned the desktop switch off. Remove it now
            // rather than making everyone wait for the stale window to expire.
            if (!json.optBoolean("sharing", true)) {
                peers.remove(peerIp)
                publish()
                return
            }
            val name = json.optString("name").ifBlank { peerIp }
            peers[peerIp] = SpeechPeer(
                peerIp = peerIp,
                deviceName = name,
                hasKokoro = json.optBoolean("has_kokoro"),
                hasVoiceCloner = json.optBoolean("has_voice_cloner"),
                kokoroVoiceCount = json.optInt("kokoro_voices"),
                timestamp = System.currentTimeMillis(),
            )
            publish()
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Bad speech announce from $peerIp: ${it.message}")
        }
    }

    // ── Querying ─────────────────────────────────────────────────────────

    /** All fresh peers, whatever they offer. Expires peers as a side effect. */
    fun all(): List<SpeechPeer> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val fresh = peers.values.filter { it.timestamp >= cutoff }
        if (fresh.size != peers.size) {
            peers.keys.retainAll(fresh.map { it.peerIp }.toSet())
            publish()
        }
        return fresh
    }

    /** Peers that can render Kokoro, by name. */
    fun withKokoro(): List<SpeechPeer> = all().filter { it.hasKokoro }.sortedBy { it.deviceName }

    /** Peers that can prepare a clone, by name. */
    fun withVoiceCloner(): List<SpeechPeer> =
        all().filter { it.hasVoiceCloner }.sortedBy { it.deviceName }

    fun hasMeshKokoro(): Boolean = withKokoro().isNotEmpty()

    fun hasMeshVoiceCloner(): Boolean = withVoiceCloner().isNotEmpty()

    /** The peer at [peerIp], if it is fresh. */
    fun peer(peerIp: String): SpeechPeer? =
        all().firstOrNull { it.peerIp == peerIp }?.takeIf { !it.isStale }

    /** Whether [peerIp] is currently announcing and reachable. */
    fun isFresh(peerIp: String): Boolean = peer(peerIp) != null

    /**
     * Forgets every peer. Test-only: the registry is a process singleton, and a test that ingested
     * announcements would otherwise leak them into the next test in the same JVM.
     */
    internal fun clearPeersForTest() {
        peers.clear()
        lastProblems.clear()
        publish()
    }

    /** What this machine is sharing, for the settings screen. Null when it is not serving. */
    fun servingSummary(): String? {
        if (!SpeechMeshServer.isRunning()) return null
        val kokoro = runCatching { localHasKokoro() }.getOrDefault(false)
        val cloner = runCatching { localHasVoiceCloner() }.getOrDefault(false)
        val what = when {
            kokoro && cloner -> "Kokoro and voice cloning"
            kokoro -> "Kokoro"
            cloner -> "voice cloning"
            else -> return null
        }
        return "This machine is sharing $what with the mesh"
    }

    // ── Calling a peer ───────────────────────────────────────────────────

    /**
     * Asks [peerIp] to render [text], returning playable audio.
     *
     * Blocking: call it from a worker thread. Returns null when the peer cannot be reached or
     * refuses, and logs the reason -- the caller shows "the desktop did not answer" rather than
     * inventing an explanation.
     */
    fun synthesizeOn(
        peerIp: String,
        text: String,
        voiceId: String?,
        speed: Float,
        /**
         * The clone's reference recording, when [voiceId] is a clone.
         *
         * A clone lives on the device that saved it. Sending the id alone made the peer look up
         * a voice it does not have and answer in the clone's published base voice -- a different
         * person, with only a log line to say so. Sending the recording lets the peer run the
         * same conversion locally. Null for an ordinary voice.
         */
        reference: FloatArray? = null,
        pitchHz: Float = 0f,
        timeoutMs: Int = 180_000,
    ): SpeechMeshWire.Clip? {
        if (text.isBlank()) return SpeechMeshWire.Clip(FloatArray(0), 24_000)
        val payload = JSONObject()
            .put("text", text)
            .put("voice", voiceId ?: com.prism.launcher.PrismSettings.KOKORO_DEFAULT_VOICE)
            .put("speed", speed.toDouble())
        if (reference != null && reference.isNotEmpty()) {
            payload.put("reference", SpeechMeshWire.encode64(SpeechMeshWire.floats(reference)))
            payload.put("pitch", pitchHz.toDouble())
        }
        val body = payload.toString().toByteArray()
        val response = request(peerIp, "/synthesize", body, timeoutMs) ?: return null
        if (response.status != 200) {
            PrismPlatform.log.warn(TAG, "Synthesis on $peerIp failed (${response.status}): ${response.errorText()}")
            return null
        }
        return SpeechMeshWire.decodeWav(response.body) ?: run {
            PrismPlatform.log.warn(TAG, "$peerIp answered /synthesize with audio that will not decode")
            null
        }
    }

    /**
     * Runs the peer's clone encoder over [region] and returns the files it prepared.
     *
     * [region] is already trimmed and levelled by [VoiceCloner.profile] -- the caller did that
     * locally, because that analysis is plain Kotlin and needs no model. Only the model work is
     * remote.
     */
    fun prepareCloneOn(
        peerIp: String,
        region: FloatArray,
        timeoutMs: Int = 180_000,
    ): Map<String, ByteArray>? {
        if (region.isEmpty()) return null
        val response = request(
            peerIp,
            "/clone?rate=${VoiceCloner.WORK_RATE}",
            SpeechMeshWire.floats(region),
            timeoutMs,
        ) ?: return null
        if (response.status != 200) {
            PrismPlatform.log.warn(TAG, "Clone on $peerIp failed (${response.status}): ${response.errorText()}")
            return null
        }
        val json = runCatching { JSONObject(String(response.body, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        val artifacts = json.optJSONObject("artifacts") ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        artifacts.keys().forEach { suffix ->
            if (suffix.isBlank() || suffix.contains('/') || suffix.contains('\\')) return@forEach
            val decoded = SpeechMeshWire.decode64(artifacts.optString(suffix)) ?: return@forEach
            out[suffix] = decoded
        }
        return out
    }

    /** A one-line reason the last call to [peerIp] failed, when the peer sent one. */
    fun lastProblem(peerIp: String): String? = lastProblems[peerIp]

    private val lastProblems = ConcurrentHashMap<String, String>()

    // ── HTTP client ──────────────────────────────────────────────────────

    private class Response(val status: Int, val body: ByteArray) {
        fun errorText(): String =
            runCatching { JSONObject(String(body, Charsets.UTF_8)).optString("error") }
                .getOrNull()
                ?.ifBlank { null }
                ?: "HTTP $status"
    }

    private fun request(
        peerIp: String,
        path: String,
        body: ByteArray,
        timeoutMs: Int,
    ): Response? {
        val peer = peer(peerIp)
        if (peer == null) {
            lastProblems[peerIp] = "That machine is no longer on the mesh"
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${peer.httpEndpoint}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = timeoutMs
                doOutput = true
                // Fixed-length rather than a hand-set header: `Content-Length` is a restricted
                // header on HttpURLConnection, so writing it through setRequestProperty is
                // ignored on some JDKs and throws on others. The streaming mode is the supported
                // way to guarantee the server sees a length instead of a chunked body it does not
                // decode.
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
            if (status in 200..299) lastProblems.remove(peerIp)
            Response(status, bytes)
        } catch (e: Exception) {
            lastProblems[peerIp] = e.message ?: e::class.simpleName ?: "no answer"
            PrismPlatform.log.warn(TAG, "Could not reach $peerIp for $path: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun publish() {
        val all = peers.values.toList()
        _kokoroPeers.value = all.filter { it.hasKokoro }.sortedBy { it.deviceName }
        _voiceClonerPeers.value = all.filter { it.hasVoiceCloner }.sortedBy { it.deviceName }
    }
}
