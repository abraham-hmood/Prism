package com.prism.launcher.calls

import com.prism.core.MeshCore
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.trusted.TrustedDevices
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice calls over the Prism mesh, and the voicemail they leave.
 *
 * ## Trusted and untrusted calls are different protocols, not a flag
 *
 * A call to a paired device is sealed with the wallet-derived [CloudVault.Purpose.CALLS] key, one
 * AES-GCM envelope per audio frame. A call to a device that is merely on the mesh cannot be: there
 * is no shared secret, and inventing one would mean either sending the wallet key to a stranger or
 * using a key the stranger chose. So that path is explicitly unencrypted, the UI warns once before
 * the first one, and [Call.secure] is false so every screen that shows a live call can say so.
 *
 * The point of the one-time warning is consent, not decoration: a mesh is an unauthenticated LAN,
 * and anybody on it can capture a plaintext audio stream. A user who chooses that for a call to a
 * friend's phone has made an informed choice; a user who was never told has not.
 *
 * ## Why frames and not a stream
 *
 * The transport is UDP gossip with a 16 KB datagram budget. A 20 ms frame at 16 kHz mono is 640
 * bytes, which fits with room for the envelope. Losing a frame loses 20 ms of audio, which is the
 * correct failure for a live call -- retransmitting it would add latency to everything after it.
 *
 * ## Voicemail is a file, not a mailbox server
 *
 * A call to an offline trusted device records locally, seals the WAV with the same key and sends it
 * in chunks. The receiver reassembles it into a file under its own meshvoice directory. There is no
 * server and no third device holding a copy: the CALLER holds it, in `meshvoice/outbox`, and retries
 * whenever the recipient answers. A receipt from the receiver is what releases the held copy, so a
 * voicemail that arrives does not keep arriving every few seconds.
 *
 * The outbox is addressed by fingerprint rather than by address. A waiting message can outlive a
 * DHCP lease, and sending it to yesterday's address would hand it to whichever device inherited
 * that address; the address is looked up again on every attempt.
 */
object MeshCalls {

    private const val TAG = "PrismCalls"

    /** Invite, answer, decline, end. JSON, sealed when the peer is trusted. */
    const val OPCODE_SIGNAL: Byte = 0x30

    /** One encrypted or plaintext audio frame, base64. */
    const val OPCODE_AUDIO: Byte = 0x31

    /** A meshnet voicemail chunk. */
    const val OPCODE_VOICE: Byte = 0x32

    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2

    enum class State { IDLE, DIALING, RINGING, ACTIVE, RECORDING, ENDED }

    /** One side of a call. */
    data class Call(
        val peerIp: String,
        val peerName: String,
        val trusted: Boolean,
        var state: State = State.DIALING,
        var startedAt: Long = 0L,
        var encryptedFrames: Long = 0L,
        var plainFrames: Long = 0L,
    ) {
        val secure: Boolean get() = trusted
    }

    /**
     * The platform's audio path.
     *
     * Capture is push-based: the implementation reads a [FRAME_BYTES] frame and hands it over.
     * Playback is pull-free: frames arrive from the network and are written to a buffer the OS
     * drains. Neither blocks the caller for longer than one frame.
     */
    interface Audio {
        fun startCapture(onFrame: (ByteArray) -> Unit): Boolean
        /** Starts playback at [SAMPLE_RATE], mono, 16-bit little endian. */
        fun startPlayback(): Boolean
        fun write(frame: ByteArray)
        fun stopPlayback()
        fun stopCapture()
    }

    @Volatile
    var audio: Audio? = null

    /** Platform hook: whether this device has a phone plan that can place a PSTN call. */
    @Volatile
    var phonePlan: (() -> Boolean)? = null

    /** Platform hook: opens the device's own call surface for a number. */
    @Volatile
    var pstnDialer: ((number: String) -> Unit)? = null

    /** Notified on every state change, on a background thread. */
    @Volatile
    var onState: ((Call?) -> Unit)? = null

    /** An invite arrived. The UI must answer with [accept] or [decline]. */
    @Volatile
    var onIncoming: ((Call) -> Unit)? = null

    @Volatile
    var active: Call? = null
        private set

    /** Trusted-device call keys are derived per call, and cleared when it ends. */
    @Volatile
    private var callKey: ByteArray? = null

    @Volatile
    private var inCall = false

    @Volatile
    private var pendingInvite: Call? = null

    /**
     * Why the last attempt to place a call did not start.
     *
     * A refused call used to return a Call in [State.ENDED] with nothing to display, so a UI that
     * only reacts to state changes showed precisely nothing -- which is what "clicking a trusted
     * device does nothing" looked like from the outside. Null after a successful start, and cleared
     * on every attempt.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * When [active] was last assigned.
     *
     * A call that is never answered and never hung up -- the caller's phone was closed, the page
     * that owned the invitation was disposed -- used to leave [active] set forever, and every later
     * invite was declined as "busy". That is the shape of "mesh calls never reach this machine":
     * the engine was not deaf, it was permanently on the phone to somebody who had gone.
     */
    @Volatile
    private var activeSince: Long = 0L

    /**
     * Whether this side's microphone is off.
     *
     * Muting STOPS CAPTURE rather than sending silence. A muted call that still streams frames is a
     * call whose microphone is still open, and on a platform where the capture loop is the only
     * thing reading the device, stopping it is both the honest implementation and the one the
     * privacy indicator agrees with.
     */
    @Volatile
    private var muted = false

    private val voicemailBuffer = ByteArrayOutputStream()

    fun hasPhonePlan(): Boolean = runCatching { phonePlan?.invoke() == true }.getOrDefault(false)

    /** Whether mesh calling is even possible: serving or connected to a mesh with at least one peer. */
    fun meshReady(): Boolean = runCatching { MeshTransport.isOnMesh() && MeshTransport.peerCount() > 0 }
        .getOrDefault(false)

    fun install() {
        MeshCore.register(OPCODE_SIGNAL) { peerIp, payload ->
            onSignal(peerIp, payload)
            true
        }
        MeshCore.register(OPCODE_AUDIO) { peerIp, payload ->
            onAudio(peerIp, payload)
            true
        }
        MeshCore.register(OPCODE_VOICE) { peerIp, payload ->
            MeshVoice.accept(peerIp, payload)
            true
        }
        // Resume anything the previous process was still holding. Desktop reaches this from
        // Main; Android calls MeshVoice.startOutbox from its own main-process startup.
        MeshVoice.startOutbox()
        PrismPlatform.log.info(TAG, "Mesh call opcodes registered")
    }

    // ── Placing a call ─────────────────────────────────────────────────────

    /**
     * Starts a call to [peerIp].
     *
     * [trusted] is not a hint: it decides whether the audio is encrypted at all. The caller (the UI)
     * must have shown the one-time warning for an untrusted call before reaching here.
     */
    fun call(peerIp: String, peerName: String, trusted: Boolean): Call {
        end(notify = false)
        lastError = null
        if (peerIp.isBlank()) {
            lastError = "${peerName.ifBlank { "That device" }} has no known address on the mesh yet."
            onState?.invoke(null)
            return Call(peerIp, peerName, trusted, state = State.ENDED)
        }
        if (!meshReady() && !MeshTransport.isOnMesh()) {
            lastError = "Not on a Prism meshnet, so there is nowhere for the call to go."
            onState?.invoke(null)
            return Call(peerIp, peerName, trusted, state = State.ENDED)
        }
        val call = Call(peerIp = peerIp, peerName = peerName, trusted = trusted)
        active = call
        activeSince = System.currentTimeMillis()
        pendingInvite = null
        callKey = if (trusted) CloudVault.keyFor(CloudVault.Purpose.CALLS) else null
        if (trusted && callKey == null) {
            // Trusted encryption without a wallet is impossible, and silently falling back to
            // plaintext would be the worst possible answer. Refuse and say why.
            lastError = "A call to a trusted device is sealed with your wallet key, and the wallet " +
                "is locked. Unlock it on the Wallet page and try again."
            call.state = State.ENDED
            active = null
            audio?.stopCapture()
            audio?.stopPlayback()
            onState?.invoke(null)
            return call
        }

        signal(call, JSONObject().apply {
            put("t", "invite")
            put("from", TrustedDevices.localFingerprint())
            put("name", localName())
            put("secure", trusted)
        })
        notify(call)
        // A trusted device that is not answering is treated as offline by the UI, which offers
        // voicemail instead. The invite itself is harmless: it is sealed and ignored by a device
        // that is not there.
        return call
    }

    /** Records and sends a meshnet voicemail instead of calling. */
    fun callVoicemail(peerIp: String, peerName: String, trusted: Boolean, seconds: Int = 20): Boolean {
        lastError = null
        if (!trusted) {
            lastError = "Voicemail is only offered for a trusted device; an untrusted one has no key " +
                "to seal the recording with."
            return false
        }
        if (peerIp.isBlank()) {
            lastError = "${peerName.ifBlank { "That device" }} has no known address on the mesh yet."
            return false
        }
        end(notify = false)
        val call = Call(peerIp, peerName, true, state = State.RECORDING, startedAt = System.currentTimeMillis())
        active = call
        activeSince = System.currentTimeMillis()
        callKey = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: run {
            active = null
            onState?.invoke(null)
            return false
        }
        voicemailBuffer.reset()
        val started = audio?.startCapture { frame ->
            synchronized(voicemailBuffer) { voicemailBuffer.write(frame) }
        } ?: false
        if (!started) {
            active = null
            onState?.invoke(null)
            return false
        }
        notify(call)
        // Bounded by a timer rather than unbounded: a voicemail is a message, not a recording of
        // whatever happens in the room until somebody remembers to press stop.
        Thread({
            runCatching { Thread.sleep(seconds * 1000L) }
            finishVoicemail()
        }, "meshvoice-timeout").apply { isDaemon = true; start() }
        return true
    }

    /** Ends a voicemail recording early and sends what was captured. */
    fun finishVoicemail() {
        val call = active ?: return
        if (call.state != State.RECORDING) return
        audio?.stopCapture()
        val pcm = synchronized(voicemailBuffer) { voicemailBuffer.toByteArray() }
        voicemailBuffer.reset()
        active = null
        call.state = State.ENDED
        notify(call)
        if (pcm.isEmpty()) return
        MeshVoice.send(call.peerIp, wav(pcm), localName())
    }

    // ── Answering ──────────────────────────────────────────────────────────

    /**
     * Hands one signal packet from the platform's transport to the call engine.
     *
     * Android dispatches opcodes itself (`PrismMeshService`) rather than through [MeshCore], so the
     * handler [install] registers is not the only way in. Both call this, so there is one parser and
     * one set of security checks regardless of which transport delivered the packet.
     */
    fun receiveSignal(peerIp: String, payload: String) = onSignal(peerIp, payload)

    /** Hands one audio frame from the platform's transport to the call engine. */
    fun receiveAudio(peerIp: String, payload: String) = onAudio(peerIp, payload)

    private fun onSignal(peerIp: String, payload: String) {
        // A CONFIRMED PEER MUST ENCRYPT. A plaintext signal from a trusted device's address is
        // either a downgrade attempt or a device with a different wallet, and accepting it would
        // let an attacker turn a sealed call into an audible one. Untrusted peers have no shared
        // key, so their signals are plain by construction.
        var trustedPeer = TrustedDevices.byIp(peerIp)?.confirmed == true
        val key = CloudVault.keyFor(CloudVault.Purpose.CALLS)
        if (trustedPeer && key == null) {
            // SILENT UNTIL NOW. A desktop whose wallet was locked or never imported dropped every
            // sealed invitation without a log line, so the caller rang and the receiver showed
            // nothing at all -- which reads as "this machine cannot receive calls". The packet
            // genuinely cannot be opened, so the only honest fix is to say why and surface it.
            lastError = "An encrypted call arrived from ${TrustedDevices.byIp(peerIp)?.name ?: peerIp}, " +
                "but this device's wallet is locked. Unlock it on the Wallet page."
            PrismPlatform.log.warn(TAG, lastError!!)
            onState?.invoke(active)
            return
        }

        // A SEALED PACKET FROM AN ADDRESS WE DO NOT HAVE ON FILE IS STILL A TRUSTED CALLER.
        //
        // This is the case that made calls from a phone to a desktop simply never arrive. Trust is
        // stored against the fingerprint, but the packet arrives from an address, and a device that
        // took a new DHCP lease -- or that the desktop had only ever seen on a different network --
        // was no longer recognised. Its invite is sealed with the wallet-derived calls key, so the
        // honest test is not "is this address in the list" but "can this device produce a packet".
        // Opening it proves the shared wallet; the fingerprint inside says which trusted device it
        // is, and the new address is recorded so the reply goes back the right way.
        var json: JSONObject?
        if (trustedPeer) {
            json = openSignal(payload, key, requireSealed = true)
        } else if (!payload.trim().startsWith("{")) {
            val opened = openSignal(payload, key, requireSealed = true)
            val from = opened?.optString("from").orEmpty()
            val known = if (from.isNotBlank()) TrustedDevices.byFingerprint(from) else null
            if (opened != null && known != null) {
                trustedPeer = true
                TrustedDevices.noteAddress(from, peerIp)
                PrismPlatform.log.info(
                    TAG,
                    "Accepted a sealed call packet from ${known.name} at a new address ($peerIp)",
                )
            }
            json = opened
        } else {
            // Plain JSON is only ever an untrusted peer's invite. A confirmed device sending it is
            // a downgrade attempt and is refused below by the fingerprint check on the reply path.
            json = openSignal(payload, null, requireSealed = false)
        }
        if (json == null) {
            // A sealed packet this device cannot open. The likeliest cause is a locked wallet --
            // the key is derived from the seed -- and without this line that case looked exactly
            // like a call that never arrived.
            if (key == null && !payload.trim().startsWith("{")) {
                lastError = "A call arrived from $peerIp but its wallet key is unavailable on this " +
                    "device. Unlock the wallet to receive encrypted calls."
                PrismPlatform.log.warn(TAG, lastError!!)
            }
            return
        }
        when (json.optString("t")) {
            "invite" -> {
                val current = active
                if (current != null) {
                    val age = System.currentTimeMillis() - activeSince
                    // A live conversation or a recording is busy. An un-answered call older than
                    // forty seconds is not a conversation, it is a leak, and refusing the new call
                    // because of it is how this machine became unreachable.
                    val live = current.state == State.ACTIVE || current.state == State.RECORDING
                    if (live || age < 40_000L) {
                        key?.let { sendSignal(peerIp, JSONObject().apply { put("t", "busy") }, it) }
                        return
                    }
                    PrismPlatform.log.warn(
                        TAG,
                        "Dropping a stale ${current.state} call to ${current.peerName} to take a new one",
                    )
                    end(notify = false)
                }
                // NOT re-derived from the address here: a device at a new lease has already been
                // recognised above by its signature, and asking the address table again would
                // downgrade the very call this branch was reached to accept.
                val trusted = trustedPeer
                val call = Call(
                    peerIp = peerIp,
                    peerName = json.optString("name").ifBlank { peerIp },
                    trusted = trusted,
                    state = State.RINGING,
                )
                pendingInvite = call
                active = call
                activeSince = System.currentTimeMillis()
                callKey = key
                onIncoming?.invoke(call)
                notify(call)
                // Ringing has to expire on its own. A machine whose page was not open has nobody to
                // answer, and without this the invitation stayed pending and blocked the line.
                Thread({
                    runCatching { Thread.sleep(60_000L) }
                    if (pendingInvite === call) decline()
                }, "meshcall-ring-timeout").apply { isDaemon = true; start() }
            }
            "answer" -> {
                val call = active ?: return
                if (call.peerIp != peerIp) return
                startAudio(call)
            }
            "decline", "busy", "end" -> {
                val call = active ?: return
                if (call.peerIp == peerIp) end(notify = true)
            }
        }
    }

    /** Mutes or unmutes this side. No-op outside an active call. */
    fun setMuted(value: Boolean) {
        muted = value
        val current = active ?: return
        if (current.state != State.ACTIVE) return
        val engine = audio ?: return
        if (value) {
            engine.stopCapture()
        } else {
            engine.startCapture { frame -> sendAudio(current, frame) }
        }
    }

    fun isMuted(): Boolean = muted

    fun accept() {
        val invite = pendingInvite ?: return
        val key = callKey ?: return
        pendingInvite = null
        sendSignal(invite.peerIp, JSONObject().apply { put("t", "answer") }, key)
        startAudio(invite)
    }

    fun decline() {
        val invite = pendingInvite ?: return
        pendingInvite = null
        callKey?.let { key ->
            sendSignal(invite.peerIp, JSONObject().apply { put("t", "decline") }, key)
        }
        invite.state = State.ENDED
        active = null
        notify(invite)
    }

    private fun startAudio(call: Call) {
        val engine = audio ?: run {
            end(notify = true)
            return
        }
        call.state = State.ACTIVE
        call.startedAt = System.currentTimeMillis()
        inCall = true
        // A new call always starts unmuted, even if the previous one ended while muted.
        muted = false
        engine.startPlayback()
        engine.startCapture { frame -> sendAudio(call, frame) }
        notify(call)
    }

    private fun sendAudio(call: Call, frame: ByteArray) {
        if (!inCall) return
        val key = callKey
        val payload = if (call.trusted && key != null) {
            call.encryptedFrames += 1
            Base64.getEncoder().encodeToString(CloudVault.seal(frame, key))
        } else {
            call.plainFrames += 1
            Base64.getEncoder().encodeToString(frame)
        }
        MeshTransport.sendToPeer(call.peerIp, OPCODE_AUDIO, payload)
    }

    private fun onAudio(peerIp: String, payload: String) {
        val call = active ?: return
        if (call.peerIp != peerIp || call.state != State.ACTIVE) return
        val raw = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return
        val frame = if (call.trusted) {
            val key = callKey ?: return
            CloudVault.open(raw, key) ?: return
        } else raw
        if (frame.size > 4096) return
        audio?.write(frame)
    }

    fun end(notify: Boolean = true) {
        val call = active
        inCall = false
        muted = false
        audio?.stopCapture()
        audio?.stopPlayback()
        active = null
        activeSince = 0L
        pendingInvite = null
        if (call != null) {
            call.state = State.ENDED
            val end = JSONObject().apply { put("t", "end") }
            val key = callKey
            if (key != null) {
                runCatching { sendSignal(call.peerIp, end, key) }
            } else if (!call.trusted) {
                // Untrusted calls have no key to seal with; the end signal goes in clear so the
                // other side stops its audio rather than playing an empty room.
                runCatching { MeshTransport.sendToPeer(call.peerIp, OPCODE_SIGNAL, end.toString()) }
            }
        }
        callKey = null
        if (notify) onState?.invoke(call)
    }

    // ── Wire helpers ───────────────────────────────────────────────────────

    private fun keyFor(peerIp: String): ByteArray? {
        val device = TrustedDevices.byIp(peerIp) ?: return null
        if (!device.confirmed) return null
        return CloudVault.keyFor(CloudVault.Purpose.CALLS)
    }

    /**
     * Parses a signal.
     *
     * [requireSealed] is true for a confirmed peer: their packets must be encrypted. An untrusted
     * peer has no key, so their invite is JSON and is accepted only in that state.
     */
    private fun openSignal(payload: String, key: ByteArray?, requireSealed: Boolean): JSONObject? {
        val trimmed = payload.trim()
        if (!requireSealed && trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        if (key == null) return null
        val sealed = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        val plain = CloudVault.open(sealed, key) ?: return null
        return runCatching { JSONObject(String(plain)) }.getOrNull()
    }

    private fun signal(call: Call, body: JSONObject) {
        val key = callKey
        if (call.trusted && key != null) {
            sendSignal(call.peerIp, body, key)
        } else {
            // Untrusted: clear JSON. The UI has already warned; the payload contains only the
            // caller's display name and no key material or transcript.
            MeshTransport.sendToPeer(call.peerIp, OPCODE_SIGNAL, body.toString())
        }
    }

    private fun sendSignal(peerIp: String, body: JSONObject, key: ByteArray) {
        runCatching {
            val sealed = CloudVault.seal(body.toString().toByteArray(), key)
            MeshTransport.sendToPeer(
                peerIp, OPCODE_SIGNAL, Base64.getEncoder().encodeToString(sealed),
            )
        }.onFailure { PrismPlatform.log.warn(TAG, "Could not send a call signal: ${it.message}") }
    }

    private fun notify(call: Call?) {
        onState?.invoke(call)
    }

    private fun localName(): String = runCatching { PrismPlatform.host.deviceName() }.getOrDefault("Prism")

    /** Wraps PCM in a WAV header so every platform can play it without a codec. */
    fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        fun le32(value: Int) {
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff); out.write((value shr 24) and 0xff)
        }
        fun le16(value: Int) { out.write(value and 0xff); out.write((value shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(channels * bits / 8); le16(bits)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    // ── Meshnet voicemail ──────────────────────────────────────────────────

    /**
     * Meshnet voicemail: sealed WAV files, addressed to one trusted device.
     *
     * Stored in `PrismPlatform.host.dataDir()/meshvoice`, one file per message plus a small index.
     * The file is already sealed with the calls key on disk, so a stolen device does not hand over
     * recordings to somebody who cannot open the wallet.
     */
    object MeshVoice {

        data class Message(
            val id: String,
            val fromName: String,
            val fromFingerprint: String,
            val receivedAt: Long,
            val file: File,
            val durationMs: Long,
            val outgoing: Boolean,
        )

        private const val MAX_CHUNK = 8 * 1024

        /** A receipt for a voicemail. Same opcode as the audio, a different `t`. */
        private const val ACK = "voice_ack"

        private val incoming = ConcurrentHashMap<String, MutableMap<Int, String>>()
        private val totals = ConcurrentHashMap<String, Int>()
        private val names = ConcurrentHashMap<String, String>()

        private fun root(): File? = runCatching {
            File(PrismPlatform.host.dataDir(), "meshvoice").apply { mkdirs() }
        }.getOrNull()

        fun all(): List<Message> {
            val dir = root() ?: return emptyList()
            return dir.listFiles { file -> file.isFile && file.name.endsWith(".mv") }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { file ->
                    val wrapper = runCatching { JSONObject(String(openFile(file) ?: return@runCatching null)) }
                        .getOrNull() ?: return@mapNotNull null
                    val meta = wrapper.optJSONObject("meta") ?: return@mapNotNull null
                    Message(
                        id = meta.optString("id", file.name),
                        fromName = meta.optString("from"),
                        fromFingerprint = meta.optString("fp"),
                        receivedAt = meta.optLong("at", file.lastModified()),
                        file = file,
                        durationMs = meta.optLong("ms"),
                        outgoing = meta.optBoolean("out"),
                    )
                }.orEmpty()
        }

        fun send(peerIp: String, wav: ByteArray, fromName: String) {
            val key = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: return
            val id = java.util.UUID.randomUUID().toString()
            val sealed = CloudVault.seal(wav, key)
            val at = System.currentTimeMillis()

            // Stored locally first, marked outgoing: a voicemail the user spoke is a message they
            // sent, and a list that only shows received ones makes it look lost.
            save(id, wav, fromName, TrustedDevices.localFingerprint(), at, outgoing = true)

            // HELD BEFORE THE FIRST ATTEMPT, not after it fails. The peer may go offline between
            // the call being placed and the recording finishing, and a queue written only on a
            // detected failure would have no way to notice that -- UDP gives no failure to notice.
            val target = TrustedDevices.byIp(peerIp)
            if (target != null && target.confirmed) {
                hold(id, target.fingerprint, target.name, fromName, at, sealed)
            }

            sendChunks(peerIp, id, sealed, fromName, at)
            // If the target was reachable for the first attempt, give the receipt time to arrive
            // before the retry loop starts. If it was not, retry as soon as the worker wakes.
            startOutbox(if (target?.online == true) RETRY_MS else 0L)
        }

        fun accept(peerIp: String, payload: String) {
            val device = TrustedDevices.byIp(peerIp) ?: return
            if (!device.confirmed) return
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val type = json.optString("t")
            if (type == ACK) {
                // A receipt, not audio. Removing the held copy is what stops a voicemail being
                // re-sent forever to a device that has already stored it.
                val id = json.optString("id")
                if (id.isNotBlank() && json.optString("fp") == device.fingerprint) {
                    if (release(id, device.fingerprint)) {
                        onNew?.invoke()
                        PrismPlatform.log.info(TAG, "Meshvoice $id was delivered to ${device.name}")
                    }
                }
                return
            }
            if (type != "voice") return
            val id = json.optString("id")
            val index = json.optInt("i", -1)
            val total = json.optInt("n", 0)
            val data = json.optString("data")
            if (id.isBlank() || index < 0 || total <= 0 || data.isBlank()) return

            totals[id] = total
            names[id] = json.optString("from").ifBlank { device.name }
            val parts = incoming.getOrPut(id) { ConcurrentHashMap() }
            parts[index] = data
            // Bounded: a flood of partial messages must not be a memory leak.
            if (incoming.size > 32) {
                incoming.keys.firstOrNull { it != id }?.let { incoming.remove(it); totals.remove(it) }
            }
            if (parts.size < total) return

            val builder = StringBuilder()
            for (i in 0 until total) {
                val part = parts[i] ?: return
                builder.append(part)
            }
            val encoded = builder.toString()
            incoming.remove(id); totals.remove(id)
            val sealed = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return
            val key = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: return
            val wav = CloudVault.open(sealed, key) ?: run {
                PrismPlatform.log.warn(TAG, "A meshvoice from ${device.name} would not open")
                return
            }
            val stored = save(
                id, wav, names.remove(id) ?: device.name, device.fingerprint,
                json.optLong("at", System.currentTimeMillis()), outgoing = false,
            )
            // ONLY CONFIRM WHAT IS ACTUALLY ON DISK. A receipt after a failed write would tell the
            // sender to drop its held copy, turning a full disk into a silently lost voicemail.
            if (stored) sendAck(peerIp, id)
            onNew?.invoke()
        }

        // ── The outbox ─────────────────────────────────────────────────────

        /**
         * Deliveries that have been received but not yet confirmed by the far side.
         *
         * A voicemail is addressed to a device, not to an address: the address can change while
         * the message waits, and sending to yesterday's lease would hand it to whichever device
         * inherited that address. The fingerprint is the identity; the address is looked up again
         * on every attempt.
         */
        private data class Pending(
            val id: String,
            val fingerprint: String,
            val name: String,
            val fromName: String,
            val at: Long,
        )

        private const val OUTBOX = "outbox"
        private const val POLL_MS = 5_000L
        private const val RETRY_MS = 15_000L
        private const val MAX_IDLE_WAIT_MS = 10_000L

        private val outboxLock = Any()

        @Volatile
        private var outboxWorker: Thread? = null

        @Volatile
        private var outboxStopped = false

        private fun outboxDir(): File? = root()?.let { File(it, OUTBOX).apply { mkdirs() } }

        private fun metaFile(id: String): File? = outboxDir()?.let { File(it, "$id.json") }

        private fun audioFile(id: String): File? = outboxDir()?.let { File(it, "$id.bin") }

        /**
         * Writes a held copy to disk before the first send.
         *
         * The audio is kept SEALED, so retrying never needs the wallet to be unlocked at the
         * moment the device comes back. The metadata is what names the recipient; the audio file
         * is what goes back on the wire unchanged.
         */
        private fun hold(
            id: String,
            fingerprint: String,
            name: String,
            fromName: String,
            at: Long,
            sealed: ByteArray,
        ) {
            if (fingerprint.isBlank()) return
            val dir = outboxDir() ?: return
            runCatching {
                File(dir, "$id.json").writeBytes(
                    JSONObject()
                        .put("id", id)
                        .put("fp", fingerprint)
                        .put("name", name)
                        .put("from", fromName)
                        .put("at", at)
                        .toString()
                        .toByteArray(),
                )
                File(dir, "$id.bin").writeBytes(sealed)
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not hold a meshvoice for $name: ${it.message}")
            }
            // Let the Meshvoice page add the word "queued" to the row it just added.
            onNew?.invoke()
        }

        /** Reads what is still waiting. A record without its audio is dropped rather than retried. */
        private fun held(): List<Pending> {
            val dir = outboxDir() ?: return emptyList()
            return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.mapNotNull { file ->
                    val json = runCatching { JSONObject(file.readText()) }.getOrNull()
                        ?: return@mapNotNull null
                    val id = json.optString("id").ifBlank { file.name.removeSuffix(".json") }
                    val fp = json.optString("fp")
                    if (id.isBlank() || fp.isBlank()) return@mapNotNull null
                    if (audioFile(id)?.isFile != true) {
                        file.delete()
                        return@mapNotNull null
                    }
                    Pending(
                        id = id,
                        fingerprint = fp,
                        name = json.optString("name").ifBlank { fp },
                        fromName = json.optString("from"),
                        at = json.optLong("at", file.lastModified()),
                    )
                }
                ?.sortedBy { it.at }
                .orEmpty()
        }

        /** Whether [id] is still waiting for its recipient to come back. */
        fun isPending(id: String): Boolean = metaFile(id)?.isFile == true

        /**
         * Removes a held copy once the recipient has confirmed receipt.
         *
         * [fingerprint] is checked when given, so a receipt from a different trusted device cannot
         * clear somebody else's delivery just by knowing the id.
         */
        private fun release(id: String, fingerprint: String? = null): Boolean {
            synchronized(outboxLock) {
                val meta = metaFile(id) ?: return false
                if (!meta.isFile) return false
                if (fingerprint != null) {
                    val stored = runCatching { JSONObject(meta.readText()).optString("fp") }
                        .getOrNull()
                    if (stored != fingerprint) return false
                }
                val removed = meta.delete()
                audioFile(id)?.delete()
                return removed
            }
        }

        /**
         * Starts the retry loop if there is anything to retry.
         *
         * A plain daemon thread rather than a scheduler: the work is one address lookup and, while
         * the recipient is away, no I/O at all beyond reading a handful of small files. It exits as
         * soon as the outbox is empty, and both platforms start it at process start, so a message
         * survives a restart. The loop backs off to ten seconds when nobody is answering -- quick
         * enough that coming back online feels immediate, slow enough not to be a wake lock. Calling
         * it when a worker is already running is free.
         */
        fun startOutbox(initialDelayMs: Long = 0L) {
            synchronized(outboxLock) {
                outboxStopped = false
                if (outboxWorker?.isAlive == true) return
                if (held().isEmpty()) return
                outboxWorker = Thread({ outboxLoop(initialDelayMs) }, "meshvoice-outbox").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        /**
         * One retry pass.
         *
         * Exposed inside the module so the retry path is testable without sleeping through the
         * worker's schedule. Returning the number of recordings actually placed on the wire is also
         * what the loop uses to decide between a quick receipt wait and a longer away backoff.
         */
        internal fun deliverHeldNow(): Int {
            var reached = 0
            held().forEach { item -> if (deliver(item)) reached++ }
            return reached
        }

        private fun outboxLoop(initialDelayMs: Long) {
            var quietPolls = 0
            try {
                if (initialDelayMs > 0L) Thread.sleep(initialDelayMs)
                while (true) {
                    val pending = held()
                    if (pending.isEmpty()) return
                    val reachedAnyone = deliverHeldNow() > 0
                    val wait = if (reachedAnyone) {
                        quietPolls = 0
                        // Short: the recipient is up, so this is almost certainly just waiting for
                        // its receipt. A lost receipt costs one re-send, and the receiver writes
                        // the same id over the same file, so a duplicate is not a duplicate.
                        RETRY_MS
                    } else {
                        quietPolls++
                        minOf(MAX_IDLE_WAIT_MS, POLL_MS * (1L shl quietPolls.coerceAtMost(3)))
                    }
                    Thread.sleep(wait)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(outboxLock) { outboxWorker = null }
                // A voicemail can be held between the empty check and the worker clearing itself.
                // Restarting on that race is what keeps it from sitting in the outbox until the
                // next process launch.
                if (!outboxStopped) startOutbox()
            }
        }

        /** Stops the retry loop. Used by tests and by a host that is tearing the process down. */
        internal fun stopOutbox() {
            synchronized(outboxLock) {
                outboxStopped = true
                outboxWorker?.interrupt()
                outboxWorker = null
            }
        }

        /** One attempt. False means the recipient is not answering yet, not that it failed. */
        private fun deliver(item: Pending): Boolean {
            val device = TrustedDevices.byFingerprint(item.fingerprint)
            if (device == null || !device.confirmed) {
                // The pairing was removed while the message waited. Revocation has to mean that
                // what we were holding for that device stops existing, not that it sits on disk
                // waiting for a relationship the user ended.
                release(item.id)
                return false
            }
            if (!device.online) return false
            val sealed = runCatching { audioFile(item.id)?.readBytes() }.getOrNull()
            if (sealed == null || sealed.isEmpty()) {
                release(item.id)
                return false
            }
            sendChunks(device.lastIp, item.id, sealed, item.fromName, item.at)
            PrismPlatform.log.info(
                TAG,
                "Re-sending held meshvoice ${item.id} to ${device.name} now that it is online",
            )
            return true
        }

        /** Splits one sealed WAV into the UDP-sized chunks the wire format uses. */
        private fun sendChunks(
            peerIp: String,
            id: String,
            sealed: ByteArray,
            fromName: String,
            at: Long,
        ) {
            val encoded = Base64.getEncoder().encodeToString(sealed)
            val total = (encoded.length + MAX_CHUNK - 1) / MAX_CHUNK
            for (index in 0 until total) {
                val start = index * MAX_CHUNK
                val end = minOf(encoded.length, start + MAX_CHUNK)
                val packet = JSONObject().apply {
                    put("t", "voice")
                    put("id", id)
                    put("i", index)
                    put("n", total)
                    put("from", fromName)
                    put("fp", TrustedDevices.localFingerprint())
                    put("at", at)
                    put("data", encoded.substring(start, end))
                }
                MeshTransport.sendToPeer(peerIp, OPCODE_VOICE, packet.toString())
            }
        }

        private fun sendAck(peerIp: String, id: String) {
            val packet = JSONObject().apply {
                put("t", ACK)
                put("id", id)
                put("fp", TrustedDevices.localFingerprint())
            }
            runCatching {
                MeshTransport.sendToPeer(peerIp, OPCODE_VOICE, packet.toString())
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not confirm meshvoice $id: ${it.message}")
            }
        }

        private fun save(
            id: String,
            wav: ByteArray,
            fromName: String,
            fp: String,
            at: Long,
            outgoing: Boolean,
        ): Boolean {
            val dir = root() ?: return false
            val key = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: return false
            val meta = JSONObject().apply {
                put("id", id)
                put("from", fromName)
                put("fp", fp)
                put("at", at)
                put("ms", wavDurationMs(wav))
                put("out", outgoing)
            }
            val saved = runCatching {
                File(dir, "$id.mv").writeBytes(
                    CloudVault.seal(
                        JSONObject().apply {
                            put("meta", meta)
                            put("audio", Base64.getEncoder().encodeToString(wav))
                        }.toString().toByteArray(),
                        key,
                    )
                )
                true
            }.getOrElse {
                PrismPlatform.log.warn(TAG, "Could not save a meshvoice: ${it.message}")
                false
            }
            onNew?.invoke()
            return saved
        }

        /** The WAV bytes, or null when this wallet cannot open the file. */
        fun audioOf(message: Message): ByteArray? {
            val key = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: return null
            val plain = openFile(message.file) ?: return null
            val json = runCatching { JSONObject(String(plain)) }.getOrNull() ?: return null
            return runCatching { Base64.getDecoder().decode(json.optString("audio")) }.getOrNull()
        }

        private fun openFile(file: File): ByteArray? {
            val key = CloudVault.keyFor(CloudVault.Purpose.CALLS) ?: return null
            return runCatching { CloudVault.open(file.readBytes(), key) }.getOrNull()
        }

        private fun wavDurationMs(wav: ByteArray): Long {
            if (wav.size <= 44) return 0
            // Data chunk length / byte rate, read from the header rather than guessed: the voicemail
            // timer can end a frame early and the header is authoritative.
            val byteRate = ((wav[28].toInt() and 0xff) or ((wav[29].toInt() and 0xff) shl 8) or
                ((wav[30].toInt() and 0xff) shl 16) or ((wav[31].toInt() and 0xff) shl 24))
            if (byteRate <= 0) return 0
            val data = wav.size - 44
            return data.toLong() * 1000 / byteRate
        }

        /** Notified when a message is stored, received or sent. */
        @Volatile
        var onNew: (() -> Unit)? = null

        fun delete(message: Message): Boolean = runCatching { message.file.delete() }.getOrDefault(false)
    }
}
