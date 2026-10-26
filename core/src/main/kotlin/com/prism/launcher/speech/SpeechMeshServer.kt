package com.prism.launcher.speech

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * This machine's Kokoro and voice-cloning models, offered to the rest of the mesh.
 *
 * ## Why an HTTP server and not more UDP opcodes
 *
 * The mesh control plane carries gossip: small, lossy, re-sent on a timer. Synthesis is the
 * opposite -- one request, a few hundred kilobytes back, and a caller that must know whether it
 * arrived. UDP would need its own fragmentation, ordering and retry layer, which is a transport
 * implementation the mesh already has a working one of: TCP. So the announcement is the single
 * new opcode ([KokoroMeshShare.OPCODE_KOKORO_ANNOUNCE]) and the payloads go over HTTP on
 * [KokoroMeshShare.KOKORO_HTTP_PORT].
 *
 * ## What it serves
 *
 *  - `GET /health` — what this machine has, so a peer can refresh without waiting for gossip.
 *  - `POST /synthesize` — JSON `{text, voice, speed}` in, WAV out. This is Kokoro running here,
 *    which is the point when the caller is a phone that has not downloaded the 86 MB model, or
 *    a laptop that would rather use the desktop's CPU.
 *  - `POST /clone` — float32 samples in, the active clone engine's per-voice artifacts out. A
 *    weaker device records a sample, the desktop runs the encoder, and the phone stores a clone
 *    it could not have prepared itself.
 *
 * ## Who is allowed to call it
 *
 * A request is refused unless its source address is a machine already in the mesh peer table.
 * That is not authentication -- nothing in Prism's mesh is authenticated, and a device that can
 * broadcast on UDP 8081 can join it -- but it means the port answers the network Prism is on
 * rather than anything that can route to this host. The server also only starts when the mesh is
 * actually up, so a machine that never joined exposes nothing.
 *
 * ## Bound to every local address, deliberately
 *
 * MeshInference binds its RPC server to the mesh interface only, because an unauthenticated
 * RPC that can read memory is a different risk class. Speech is not that: the worst a caller can
 * do is make this machine talk. Binding to `0.0.0.0` is what makes the feature work when the
 * mesh address and the address a peer can route to are not the same one -- which is the common
 * case the moment a Prism VPN interface exists -- and the peer check above is the cost.
 */
object SpeechMeshServer {

    private const val TAG = "PrismSpeechMeshHost"

    /** Longest request body accepted: about two minutes of float32 audio at 22.05 kHz. */
    private const val MAX_BODY = 32 * 1024 * 1024

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptor: Thread? = null
    @Volatile private var running = false

    /** Last reason the server could not start, for the settings screen. */
    @Volatile var lastError: String? = null
        private set

    /** The Kokoro session used for remote synthesis. Opening one costs a model load, so it is kept. */
    private var kokoro: KokoroEngine? = null
    private val kokoroLock = Any()

    /** Synthesis is serialised: two concurrent ONNX runs on one model is a memory spike, not speed. */
    private val synthLock = Any()

    fun isRunning(): Boolean = running && server?.isClosed == false

    fun statusLine(): String = when {
        isRunning() -> "Serving on port ${KokoroMeshShare.KOKORO_HTTP_PORT}"
        lastError != null -> "Not serving: $lastError"
        else -> "Not serving"
    }

    /**
     * Starts the server if the mesh is up and this machine has anything to offer.
     *
     * Idempotent and safe to call on every announcement: the audio host does not want a lifecycle
     * of its own, and a mesh that comes up after Prism did must still be served.
     */
    @Synchronized
    fun startIfAvailable(): Boolean {
        if (isRunning()) return true
        // Defense in depth: announce() already refuses when the preference is off, but this is
        // the only place a listening socket is opened, so the preference is checked here too.
        if (!PrismSettings.getMeshSpeechSharingEnabled()) return false
        if (!MeshTransport.isOnMesh()) return false

        val hasKokoro = runCatching { KokoroInstall.isModelInstalled() }.getOrDefault(false)
        val hasCloner = runCatching { CloneEngines.hasAnyInstalled() }.getOrDefault(false)
        if (!hasKokoro && !hasCloner) return false

        val bound = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(KokoroMeshShare.KOKORO_HTTP_PORT))
                soTimeout = 1_000
            }
        }.getOrElse {
            lastError = it.message ?: it::class.simpleName
            PrismPlatform.log.warn(TAG, "Could not open the speech port: $lastError")
            return false
        }

        server = bound
        running = true
        lastError = null
        acceptor = thread(name = "speech-mesh-http", isDaemon = true) { acceptLoop(bound) }
        PrismPlatform.log.info(
            TAG,
            "Serving speech on :${KokoroMeshShare.KOKORO_HTTP_PORT} (kokoro=$hasKokoro cloner=$hasCloner)",
        )
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptor = null
        // RELEASE WAITS FOR THE CURRENT LINE. The switch that turns sharing off can be flipped
        // while a peer's sentence is inside ONNX Runtime; closing the session underneath a running
        // inference is a native crash, not an exception. synthLock is the same lock synthesis
        // holds, so this is the difference between "stops serving" and "stops the process".
        synchronized(synthLock) {
            synchronized(kokoroLock) {
                runCatching { kokoro?.release() }
                kokoro = null
            }
        }
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (running) {
            val socket = try {
                bound.accept()
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) {
                    lastError = e.message ?: e::class.simpleName
                    PrismPlatform.log.warn(TAG, "Speech listener stopped: $lastError")
                }
                break
            }
            thread(name = "speech-mesh-request", isDaemon = true) {
                runCatching { serve(socket) }
                    .onFailure { PrismPlatform.log.warn(TAG, "Speech request failed: ${it.message}") }
                runCatching { socket.close() }
            }
        }
    }

    // ── One request ────────────────────────────────────────────────────────

    private fun serve(socket: Socket) {
        socket.soTimeout = 120_000
        val remote = normalise(socket.inetAddress?.hostAddress.orEmpty())

        if (!isMeshPeer(remote)) {
            reply(socket, 403, "application/json", """{"error":"not a mesh peer"}""".toByteArray())
            return
        }

        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            reply(socket, 400, "application/json", """{"error":"bad request line"}""".toByteArray())
            return
        }
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) {
            reply(socket, 413, "application/json", """{"error":"body too large"}""".toByteArray())
            return
        }
        val body = readFully(input, length)

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        when {
            method == "GET" && path == "/health" -> reply(
                socket, 200, "application/json", healthJson().toByteArray(),
            )

            method == "POST" && path == "/synthesize" -> synthesize(socket, body)

            method == "POST" && path == "/clone" -> clone(socket, body, query)

            else -> reply(socket, 404, "application/json", """{"error":"no such endpoint"}""".toByteArray())
        }
    }

    private fun healthJson(): String {
        val hasKokoro = runCatching { KokoroInstall.isModelInstalled() }.getOrDefault(false)
        return JSONObject()
            .put("name", runCatching { KokoroMeshShare.localDeviceName() }.getOrDefault("Prism device"))
            .put("kokoro", hasKokoro)
            .put("voice_cloner", runCatching { CloneEngines.hasAnyInstalled() }.getOrDefault(false))
            .put("kokoro_voices", runCatching { KokoroInstall.installedVoices().size }.getOrDefault(0))
            .put("port", KokoroMeshShare.KOKORO_HTTP_PORT)
            .toString()
    }

    private fun synthesize(socket: Socket, body: ByteArray) {
        val request = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        if (request == null) {
            reply(socket, 400, "application/json", """{"error":"body is not JSON"}""".toByteArray())
            return
        }
        val text = request.optString("text").trim()
        if (text.isEmpty()) {
            reply(socket, 400, "application/json", """{"error":"no text"}""".toByteArray())
            return
        }
        val voice = request.optString("voice").ifBlank { PrismSettings.KOKORO_DEFAULT_VOICE }
        val speed = request.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.0f)

        // A REMOTE CLONE ARRIVES AS ITS REFERENCE RECORDING. The requesting device owns the
        // clone; this machine has never seen that voice and has no artifact for it. Sending the
        // reference is what lets this machine do the same two-stage render a local clone gets,
        // instead of answering in the clone's published base voice (which is what used to happen,
        // and sounded like a different person).
        val reference = request.optString("reference").takeIf { it.isNotBlank() }?.let { encoded ->
            runCatching { SpeechMeshWire.readFloats(SpeechMeshWire.decode64(encoded)!!) }
                .getOrElse {
                    reply(
                        socket, 400, "application/json",
                        """{"error":"reference audio is not valid float32"}""".toByteArray(),
                    )
                    return
                }
        }
        val pitchHz = request.optDouble("pitch", 0.0).toFloat()

        if (reference != null) {
            if (!CloneEngines.hasAnyInstalled()) {
                reply(
                    socket, 409, "application/json",
                    JSONObject().put(
                        "error",
                        "this machine has no voice-cloning model installed, so it cannot speak a " +
                            "cloned voice; download one there or speak locally",
                    ).toString().toByteArray(),
                )
                return
            }
            val audio = synchronized(synthLock) {
                engineForClone()?.synthesizeWithReference(text, voice, speed, reference, pitchHz)
            }
            if (audio == null || audio.samples.isEmpty()) {
                reply(
                    socket, 500, "application/json",
                    """{"error":"this machine could not render that cloned line"}""".toByteArray(),
                )
                return
            }
            reply(socket, 200, "audio/wav", SpeechMeshWire.wav(audio.samples, audio.sampleRate))
            return
        }

        val engine = kokoroEngine()
        if (engine == null) {
            reply(
                socket, 503, "application/json",
                """{"error":"Kokoro is not installed on this machine"}""".toByteArray(),
            )
            return
        }

        val audio = synchronized(synthLock) { engine.synthesizeToPcm(text, voice, speed) }
        if (audio == null || audio.samples.isEmpty()) {
            reply(
                socket, 500, "application/json",
                """{"error":"this machine could not render that line"}""".toByteArray(),
            )
            return
        }
        reply(socket, 200, "audio/wav", SpeechMeshWire.wav(audio.samples, audio.sampleRate))
    }

    private fun clone(socket: Socket, body: ByteArray, query: Map<String, String>) {
        val samples = SpeechMeshWire.readFloats(body)
        if (samples.isEmpty()) {
            reply(socket, 400, "application/json", """{"error":"no audio"}""".toByteArray())
            return
        }
        val rate = query["rate"]?.toIntOrNull() ?: VoiceCloner.WORK_RATE

        val engine = CloneEngines.active().takeIf { it.isInstalled() }
            ?: CloneEngines.all().firstOrNull { it.isInstalled() }
        if (engine == null) {
            reply(
                socket, 409, "application/json",
                """{"error":"no voice-cloning model is installed on this machine"}""".toByteArray(),
            )
            return
        }

        // A synthesises-the-text engine (Pocket TTS) has NO encoder pass to offload: its whole
        // model is the speaker-conditioned decoder, which has to run on the machine that speaks.
        // Accepting the request would prepare nothing and return an empty artifact set, which the
        // client would store as a working clone that then sounds like the base voice.
        if (engine is CloneEngine.ReferenceSpeechEngine) {
            reply(
                socket, 409, "application/json",
                JSONObject().put(
                    "error",
                    "${engine.label} speaks from the reference itself and has no encoder pass to " +
                        "run remotely; save the clone on this machine or pick a converter engine.",
                ).toString().toByteArray(),
            )
            return
        }

        val reference = if (rate == VoiceCloner.WORK_RATE) samples
        else VoicePitch.resample(samples, rate, VoiceCloner.WORK_RATE)

        val artifacts = synchronized(synthLock) {
            runCatching { prepareClone(engine, reference) }
        }.getOrElse { failure ->
            PrismPlatform.log.warn(TAG, "Remote clone failed on ${engine.label}: ${failure.message}")
            reply(
                socket, 500, "application/json",
                JSONObject().put("error", failure.message ?: "clone preparation failed").toString()
                    .toByteArray(),
            )
            return
        }

        val envelope = JSONObject()
            .put("engine", engine.id)
            .put("rate", VoiceCloner.WORK_RATE)
            .put("artifacts", artifacts)
        reply(socket, 200, "application/json", envelope.toString().toByteArray())
    }

    /**
     * Runs [engine]'s per-voice preparation under a throwaway id and hands back what it wrote.
     *
     * The id is thrown away because the CLIENT owns the voice: it picked the name, the language
     * and the gender, and it is the machine that will store the clone. What the client cannot do
     * without the model is [CloneEngine.prepare]. So this runs it, reads the files the engine
     * produced, and deletes them -- leaving this machine's own voice registry untouched.
     */
    private fun prepareClone(engine: CloneEngine, reference: FloatArray): JSONObject {
        val id = "mesh_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = KokoroInstall.voicesDir()
        dir.mkdirs()
        ClonedVoices.writeRef(id, reference)
        try {
            engine.prepare(id, reference)
            val artifacts = JSONObject()
            dir.listFiles()
                ?.filter { it.name.startsWith("$id.") }
                ?.forEach { file ->
                    val suffix = file.name.removePrefix("$id.")
                    // `.ref` is the recording the CLIENT already has and already sent; sending
                    // it back would double the response for no gain.
                    if (suffix != "ref" && file.isFile) {
                        artifacts.put(suffix, SpeechMeshWire.encode64(file.readBytes()))
                    }
                }
            return artifacts
        } finally {
            dir.listFiles()
                ?.filter { it.name.startsWith("$id.") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    /** The Kokoro session for a clone render, opened the same way and reused. */
    private fun engineForClone(): KokoroEngine? = kokoroEngine()

    private fun kokoroEngine(): KokoroEngine? = synchronized(kokoroLock) {
        val current = kokoro
        if (current != null && current.isReady()) return current
        runCatching { kokoro?.release() }
        kokoro = null
        val opened = runCatching { KokoroEngine.openOrNull() }.getOrNull()
        if (opened == null && KokoroInstall.isModelInstalled()) {
            PrismPlatform.log.warn(TAG, "Kokoro is installed but would not open for the mesh host")
        }
        kokoro = opened
        opened
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────────

    private fun isMeshPeer(ip: String): Boolean {
        if (ip.isEmpty() || ip == "127.0.0.1" || ip == "0.0.0.0") return false
        // The mesh being off is the strongest reason to refuse. The peer table is cleared when
        // the transport stops, and without this check an empty table would read as "nothing to
        // compare against, so allow" -- leaving the port answering the whole LAN after the user
        // switched the mesh off.
        if (!MeshTransport.isOnMesh()) return false
        val known = runCatching { MeshTransport.peers() }.getOrDefault(emptyList())
        // No table yet means this machine has just come up; the mesh socket is the only route in,
        // and refusing here would make the feature fail for the first seconds after a restart.
        if (known.isEmpty()) return true
        return known.any { normalise(it) == ip }
    }

    private fun normalise(ip: String): String {
        val bare = ip.substringBefore('%')
        return if (bare.startsWith("::ffff:")) bare.substring(7) else bare
    }

    private fun reply(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(head)
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (byte == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            if (buffer.size() > 8 * 1024) return null
            buffer.write(byte)
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val read = input.read(body, at, length - at)
            if (read < 0) break
            at += read
        }
        return if (at == length) body else body.copyOf(at)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isBlank()) null
            else key to java.net.URLDecoder.decode(value, "UTF-8")
        }.toMap()
    }
}
