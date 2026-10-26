package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.calls.MeshCalls
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

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

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        MeshCalls.SAMPLE_RATE.toFloat(),
        16,
        1,
        2,
        MeshCalls.SAMPLE_RATE.toFloat(),
        false,
    )

    @Volatile private var recordLine: TargetDataLine? = null
    @Volatile private var playLine: SourceDataLine? = null
    @Volatile private var capturing = false
    @Volatile private var captureThread: Thread? = null

    override fun startCapture(onFrame: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!runCatching { AudioSystem.isLineSupported(info) }.getOrDefault(false)) {
            PrismPlatform.log.warn(TAG, "No microphone accepts 16 kHz mono PCM")
            return false
        }
        val line = runCatching {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format, MeshCalls.FRAME_BYTES * 8)
                start()
            }
        }.getOrElse { error ->
            PrismPlatform.log.warn(TAG, "Could not open the microphone: ${error.message}")
            return false
        }
        recordLine = line
        capturing = true
        captureThread = Thread({
            val frame = ByteArray(MeshCalls.FRAME_BYTES)
            while (capturing) {
                val read = runCatching { line.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read <= 0) {
                    if (!capturing) break
                    continue
                }
                onFrame(if (read == frame.size) frame.copyOf() else frame.copyOf(read))
            }
        }, "prism-desktop-call-mic").apply { isDaemon = true; start() }
        return true
    }

    override fun stopCapture() {
        capturing = false
        runCatching { recordLine?.stop() }
        runCatching { captureThread?.join(400) }
        runCatching { recordLine?.close() }
        recordLine = null
        captureThread = null
    }

    override fun startPlayback(): Boolean {
        if (playLine != null) return true
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!runCatching { AudioSystem.isLineSupported(info) }.getOrDefault(false)) {
            PrismPlatform.log.warn(TAG, "No output line accepts 16 kHz mono PCM")
            return false
        }
        val line = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format, MeshCalls.FRAME_BYTES * 8)
                start()
            }
        }.getOrElse { error ->
            PrismPlatform.log.warn(TAG, "Could not open the call output: ${error.message}")
            return false
        }
        playLine = line
        return true
    }

    override fun write(frame: ByteArray) {
        val line = playLine ?: return
        if (frame.isEmpty()) return
        runCatching { line.write(frame, 0, frame.size) }
    }

    override fun stopPlayback() {
        val line = playLine ?: return
        playLine = null
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
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
                val shape = AudioFormat(sampleRate, 16, 1, true, false)
                val line = runCatching {
                    (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, shape)) as SourceDataLine)
                        .apply { open(shape); start() }
                }.getOrNull() ?: return@Thread
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
                        line.write(chunk, 0, chunk.size)
                    }
                }
                runCatching { line.drain() }
                runCatching { line.close() }
            }, "prism-find-chime").apply { isDaemon = true }
            chime = thread
            thread.start()
        }

        fun stopChime() {
            chime = null
        }
    }
}
