package com.prism.launcher.calls

import com.prism.launcher.PrismLogger
import com.prism.launcher.messaging.Dictation
import java.io.ByteArrayOutputStream

/**
 * Live transcription of a Prism mesh call.
 *
 * The call's capture is the only microphone Android will let this process have while the call is
 * live, so the transcriber reads the call's own frames -- the same tap the recorder uses. Each
 * direction is transcribed separately: this side's microphone on the right, the decrypted peer
 * stream on the left, which is why the overlay can put them on opposite sides without guessing.
 *
 * Transcription is local and on a worker thread. A chunk is roughly four seconds; that is long
 * enough for whisper to have words to work with and short enough that the transcript follows the
 * conversation rather than arriving after it.
 */
class CallTranscriber(
    private val onLine: (fromUser: Boolean, text: String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val outgoing = ByteArrayOutputStream()
    private val incoming = ByteArrayOutputStream()

    @Volatile private var running = false
    private var worker: Thread? = null

    private val tap: (ByteArray, Boolean) -> Unit = { frame, fromUser ->
        if (running) {
            val buffer = if (fromUser) outgoing else incoming
            synchronized(buffer) { buffer.write(frame) }
        }
    }

    fun start() {
        if (running) return
        runCatching { Dictation.preferred() }.getOrNull() ?: run {
            val reason = runCatching { Dictation.unavailableReason() }.getOrNull()
            onStatus(reason?.takeIf { it.isNotBlank() } ?: "No on-device transcription model is installed.")
            return
        }
        running = true
        MeshCalls.addAudioTap(tap)
        worker = Thread({ loop() }, "prism-call-transcriber").apply { isDaemon = true; start() }
        onStatus("Listening on this device…")
    }

    fun stop() {
        running = false
        MeshCalls.removeAudioTap(tap)
        runCatching { worker?.join(800) }
        worker = null
        synchronized(outgoing) { outgoing.reset() }
        synchronized(incoming) { incoming.reset() }
    }

    private fun loop() {
        while (running) {
            runCatching { Thread.sleep(CHUNK_MS) }
            if (!running) break
            val mine = drain(outgoing)
            val theirs = drain(incoming)
            if (mine == null && theirs == null) {
                onStatus("Listening…")
                continue
            }
            val engine = runCatching { Dictation.preferred() }.getOrNull()
            if (engine == null) {
                onStatus("The transcription model became unavailable.")
                break
            }
            onStatus("Transcribing…")
            mine?.let { pcm ->
                runCatching { engine.transcribe(pcm, SAMPLE_RATE, null) }
                    .onSuccess { text -> if (!text.isNullOrBlank()) onLine(true, text.trim()) }
                    .onFailure { PrismLogger.logWarning(TAG, "This side failed to transcribe: ${it.message}") }
            }
            theirs?.let { pcm ->
                runCatching { engine.transcribe(pcm, SAMPLE_RATE, null) }
                    .onSuccess { text -> if (!text.isNullOrBlank()) onLine(false, text.trim()) }
                    .onFailure { PrismLogger.logWarning(TAG, "Peer audio failed to transcribe: ${it.message}") }
            }
            if (running) onStatus("Listening…")
        }
    }

    /** Returns null until there is at least a second of new audio; always an even byte count. */
    private fun drain(buffer: ByteArrayOutputStream): ByteArray? {
        synchronized(buffer) {
            if (buffer.size() < MIN_BYTES) return null
            val bytes = buffer.toByteArray()
            buffer.reset()
            val even = bytes.size - (bytes.size % 2)
            if (even <= 0) return null
            return bytes.copyOf(even)
        }
    }

    private companion object {
        const val TAG = "PrismCallTranscript"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MS = 4_000L
        const val MIN_BYTES = SAMPLE_RATE * 2
    }
}
