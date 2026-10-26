package com.prism.launcher.speech

import com.prism.core.PrismPlatform

/**
 * Kokoro on somebody else's machine, wearing the ordinary [TtsEngine] interface.
 *
 * ## Why this is an engine and not a separate code path
 *
 * The rest of Prism asks one question -- "say this" -- and every caller (call mode, a spoken
 * reply, a voice preview, a language lesson) is written against [TtsEngine]. A phone whose own
 * Kokoro is not downloaded, or a laptop that has chosen a desktop's voice, should not need a
 * second implementation of call mode. So remote speech is an engine like any other, and
 * [PrismSpeaker] selects it the same way it selects the local one.
 *
 * ## The failure it has to get right
 *
 * A network round trip can fail in ways a local model cannot: the desktop sleeps, the mesh drops,
 * the port is closed. [speak] therefore reports a reason rather than going silent, and the
 * callback still fires exactly once on the main thread -- a call waiting forever for a callback
 * is the failure this interface exists to prevent.
 *
 * ## What is deliberately absent
 *
 * Streaming. [SpeechMeshWire] returns one WAV per request, so a long paragraph arrives as one
 * response and is played in one go. Local Kokoro already streams sentence by sentence; adding
 * that here would need a chunked protocol and a cancellation token on the wire, and the first
 * version would rather be simple and correct than fast and racy.
 */
class MeshSpeechEngine(private val peer: KokoroMeshShare.SpeechPeer) : TtsEngine {

    override val displayName: String get() = "Kokoro on ${peer.deviceName}"

    /** Bumped by [stop] so an in-flight response is discarded instead of played late. */
    @Volatile private var generation = 0

    /** The peer answers or it does not; there is no local readiness to wait for. */
    override fun isReady(): Boolean = true

    override fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        localeTag: String?,
        onDone: (error: String?) -> Unit,
    ) {
        if (text.isBlank()) {
            onDone(null)
            return
        }
        // A clone lives on the machine that saved it. Sending only the clone id made the peer
        // look up a voice it does not have and answer in the clone's published base voice -- a
        // different person -- with nothing on screen to say so. New clones therefore send their
        // reference recording and the peer converts toward it. A LEGACY clone (one saved before
        // references were stored) has only its OpenVoice tone tensor, which cannot travel this
        // way; that case still falls back to the base voice, and now says so in the log before
        // the first word rather than leaving the user to notice.
        val clone = voiceId?.let { ClonedVoices.find(it) }
        val reference = clone?.let { ClonedVoices.refFor(it.id) }
        val remoteVoice = if (clone == null) voiceId else clone.baseVoiceId
        if (clone != null && reference == null) {
            PrismPlatform.log.info(
                "PrismSpeech",
                "Legacy clone ${clone.id} has no reference recording, so ${peer.deviceName} " +
                    "speaks the base voice; re-record it to keep the timbre over the mesh",
            )
        }
        val mine = ++generation
        Thread({
            val clip = KokoroMeshShare.synthesizeOn(
                peer.peerIp, text, remoteVoice, speed, reference, clone?.pitchHz ?: 0f,
            )
            if (mine != generation) return@Thread
            if (clip == null) {
                val problem = KokoroMeshShare.lastProblem(peer.peerIp)
                    ?: "${peer.deviceName} did not answer"
                PrismPlatform.main.post { onDone(problem) }
                return@Thread
            }
            if (clip.samples.isEmpty()) {
                PrismPlatform.main.post { onDone(null) }
                return@Thread
            }
            val problem = PrismPlatform.audio.play(clip.samples, clip.sampleRate) { mine != generation }
            if (mine != generation) return@Thread
            PrismPlatform.main.post { onDone(problem) }
        }, "speech-mesh-${peer.peerIp}").apply { isDaemon = true; start() }
    }

    override fun stop() {
        generation++
        PrismPlatform.audio.stop()
    }

    override fun release() = stop()
}
