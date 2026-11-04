package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.calls.MeshCalls
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * A mesh call's microphone and speaker, on a desktop.
 *
 * ## The same split as every other audio path here
 *
 * `javax.sound.sampled` does not exist on Android, so the platform-specific half lives in the
 * platform module and the call engine only sees [MeshCalls.Audio]. Recording is push-based at the
 * engine's frame size; playback is a streaming line that the network thread writes into.
 *
 * ## 16 kHz, like the phone
 *
 * The engine, the mesh voicemail files and the Android path all agree on 16 kHz mono 16-bit. Using
 * the mixer's preferred rate here would need a resampler on one side of every call, and a naive
 * resampler is audible as aliasing rather than as anything that looks like a bug.
 *
 * A machine with no capture device reports that through [startCapture]'s return value; the call
 * screen shows the reason instead of connecting a call nobody can hear.
 */
class DesktopCallAudio : MeshCalls.Audio {

    @Volatile private var input: DesktopAudioDevices.Input? = null
    @Volatile private var output: DesktopAudioDevices.Output? = null
    @Volatile private var capturing = false
    @Volatile private var captureThread: Thread? = null

    /**
     * Opens the microphone chosen in Settings → Phone and Meshnet Calls, or the best one that
     * opens. See [DesktopAudioDevices] for the order and why it exists.
     */
    override fun startCapture(onFrame: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        val opened = DesktopAudioDevices.openInput(
            com.prism.launcher.PrismSettings.getCallMicrophone(), MeshCalls.SAMPLE_RATE,
        ) ?: run {
            PrismPlatform.log.warn(TAG, "No microphone could be opened for the call")
            return false
        }
        input = opened
        capturing = true
        captureThread = Thread({
            val pending = java.io.ByteArrayOutputStream()
            while (capturing) {
                val chunk = opened.read(MeshCalls.FRAME_BYTES)
                if (chunk.isEmpty()) {
                    if (!capturing) break
                    continue
                }
                // Resampling does not land on frame boundaries, so whole frames are cut from a
                // running buffer: the engine and the far side expect 20 ms frames.
                pending.write(chunk)
                if (pending.size() >= MeshCalls.FRAME_BYTES) {
                    val all = pending.toByteArray()
                    var at = 0
                    while (all.size - at >= MeshCalls.FRAME_BYTES) {
                        onFrame(all.copyOfRange(at, at + MeshCalls.FRAME_BYTES))
                        at += MeshCalls.FRAME_BYTES
                    }
                    pending.reset()
                    pending.write(all, at, all.size - at)
                }
            }
        }, "prism-desktop-call-mic").apply { isDaemon = true; start() }
        return true
    }

    override fun stopCapture() {
        capturing = false
        val current = input
        input = null
        runCatching { current?.line?.stop() }
        runCatching { captureThread?.join(400) }
        current?.close()
        captureThread = null
    }

    /** Opens the speaker chosen in Settings → Phone and Meshnet Calls, or the best one that opens. */
    override fun startPlayback(): Boolean {
        if (output != null) return true
        val opened = DesktopAudioDevices.openOutput(
            com.prism.launcher.PrismSettings.getCallSpeaker(), MeshCalls.SAMPLE_RATE,
        ) ?: run {
            PrismPlatform.log.warn(TAG, "No speaker could be opened for the call")
            return false
        }
        output = opened
        return true
    }

    override fun write(frame: ByteArray) {
        val line = output ?: return
        if (frame.isEmpty()) return
        runCatching { line.write(frame) }
    }

    override fun stopPlayback() {
        val line = output ?: return
        output = null
        line.close()
    }

    companion object {
        private const val TAG = "PrismDesktopCalls"

        @Volatile private var clip: Clip? = null
        @Volatile private var chime: Thread? = null

        /**
         * Plays a meshvoice or downloaded WAV.
         *
         * `Clip` rather than a streaming line because the whole file is already in memory and a
         * Clip handles its own position, which is what the page's play/stop button needs. The
         * previous clip is stopped first: two voicemails at once is never what was meant.
         */
        fun playWav(bytes: ByteArray): Boolean {
            stopWav()
            return runCatching {
                val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
                val instance = AudioSystem.getClip()
                instance.open(stream)
                instance.start()
                clip = instance
                true
            }.getOrElse { error ->
                PrismPlatform.log.warn(TAG, "Could not play audio: ${error.message}")
                false
            }
        }

        fun stopWav() {
            val current = clip ?: return
            clip = null
            runCatching { current.stop() }
            runCatching { current.close() }
        }

        /**
         * Rings the machine so its owner can find it.
         *
         * A generated two-tone chime through a source line, not the system bell: the bell is
         * usually muted, and this has to be audible from another room. Sixty seconds are bounded
         * by the page, which calls [stopChime].
         */
        fun playFindChime(seconds: Int = 15) {
            stopChime()
            val thread = Thread({
                val sampleRate = 22_050f
                // Through the device helper, like calls: on Linux the first raw line may be HDMI.
                val line = DesktopAudioDevices.openOutput(
                    com.prism.launcher.PrismSettings.getCallSpeaker(), sampleRate.toInt(), bufferMillis = 250,
                ) ?: return@Thread
                val deadline = System.currentTimeMillis() + seconds * 1000L
                var phase = 0.0
                val chunk = ByteArray(4096)
                runCatching {
                    while (System.currentTimeMillis() < deadline && chime === Thread.currentThread()) {
                        for (i in 0 until chunk.size / 2) {
                            // Two alternating notes, 880 and 660 Hz, in half-second blocks: a tone
                            // that alternates is easier to locate than a steady one.
                            val t = phase / sampleRate
                            val note = if ((t.toInt() % 2) == 0) 880.0 else 660.0
                            val value = (kotlin.math.sin(2 * Math.PI * note * t) * 10_000).toInt()
                            chunk[i * 2] = (value and 0xFF).toByte()
                            chunk[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                            phase += 1
                        }
                        line.write(chunk)
                    }
                }
                line.close(drain = true)
            }, "prism-find-chime").apply { isDaemon = true }
            chime = thread
            thread.start()
        }

        fun stopChime() {
            chime = null
        }
    }
}
