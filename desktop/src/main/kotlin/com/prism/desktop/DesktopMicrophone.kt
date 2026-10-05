package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.Dictation
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The microphone, on a desktop.
 *
 * ## Why this is in the desktop module
 *
 * `javax.sound.sampled` does not exist on Android — the package is absent from the framework
 * entirely, so a :core file referencing it would compile and then fail to dex. Android captures with
 * `AudioRecord`. This is the whole platform-specific half of PHASE 36; everything about what happens to
 * the audio afterwards is shared, which is the point of [Dictation] taking samples rather than owning a
 * recorder.
 *
 * ## Why it records at 16 kHz rather than the device's preferred rate
 *
 * Because Whisper wants 16 kHz, hosted or local, and asking the mixer for it lets the OS do the
 * resampling with a proper filter. Capturing at 44.1 kHz and downsampling here would mean writing a
 * resampler — and a naive one that drops samples introduces aliasing that a speech model hears as
 * noise, which shows up as a worse transcript rather than as an error.
 *
 * A mixer that cannot supply 16 kHz mono is reported rather than worked around: [isAvailable] asks the
 * system before recording starts, so the UI can say "no microphone" instead of producing an empty clip.
 */
class DesktopMicrophone {

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        Dictation.SAMPLE_RATE.toFloat(),
        16,          // bits per sample, matching Dictation's contract
        1,           // mono
        2,           // frame size: one 16-bit mono sample
        Dictation.SAMPLE_RATE.toFloat(),
        false,       // little-endian, which is what Dictation.wrapAsWav writes
    )

    @Volatile private var line: TargetDataLine? = null
    @Volatile private var recording = false

    val isRecording: Boolean get() = recording

    fun isAvailable(): Boolean =
        runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format)) }
            .getOrDefault(false)

    fun unavailableReason(): String =
        if (isAvailable()) "" else
            "No microphone that can record 16 kHz mono. On Linux this usually means PulseAudio or " +
                "PipeWire is not exposing a capture device to the JVM."

    /**
     * Records until [stop], returning 16-bit little-endian PCM.
     *
     * Blocking, on the caller's thread — it is a read loop, and handing it a thread of its own would
     * mean this class owning a lifecycle it does not need. The caller records on a background thread
     * and calls [stop] from the UI one, which is safe: [TargetDataLine.stop] is specified to unblock a
     * pending read.
     *
     * [onLevel] is called a few times a second with the peak of the last buffer, so a recorder can show
     * that something is arriving. Without it, a muted input is indistinguishable from a silent room
     * until the transcript comes back empty.
     */
    fun record(onLevel: ((Float) -> Unit)? = null, maxSeconds: Int = 120): ByteArray {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) return ByteArray(0)

        val open = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format)
            start()
        }
        line = open
        recording = true

        val captured = ByteArrayOutputStream()
        // A fifth of a second per read: small enough that the level meter moves, large enough that the
        // loop is not spinning.
        val buffer = ByteArray(Dictation.SAMPLE_RATE / 5 * 2)
        val ceiling = maxSeconds * Dictation.SAMPLE_RATE * 2

        try {
            while (recording && captured.size() < ceiling) {
                val read = open.read(buffer, 0, buffer.size)
                if (read <= 0) break
                captured.write(buffer, 0, read)
                onLevel?.invoke(Dictation.peakLevel(buffer.copyOf(read)))
            }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/mic", "Capture failed", e)
        } finally {
            recording = false
            runCatching { open.stop() }
            runCatching { open.close() }
            line = null
        }
        return captured.toByteArray()
    }

    /**
     * Stops a recording in progress.
     *
     * Both the flag and the line are touched. The flag ends the loop, and stopping the line unblocks a
     * `read` that is already waiting for samples — without that second part, stopping would not take
     * effect until the current buffer filled, which on a silent input is a fifth of a second of the
     * button appearing not to work.
     */
    fun stop() {
        recording = false
        runCatching { line?.stop() }
    }

    /**
     * Records a fixed length, for a console test with no button to press.
     *
     * THE COUNTDOWN STARTS WHEN CAPTURE DOES, not when this is called. Opening and starting a
     * TargetDataLine takes a noticeable fraction of a second on Windows -- enough that a timer started
     * here expired while the line was still opening, and a request for three seconds captured 1.8. So
     * the stopper waits for [isRecording] before it begins counting.
     *
     * The wait is bounded. If the line never starts, an unbounded wait would leave a daemon thread
     * parked for the life of the process and the caller blocked in a read that is never going to
     * return.
     */
    fun recordFor(seconds: Int, onLevel: ((Float) -> Unit)? = null): ByteArray {
        val stopper = Thread {
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (!recording && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(20) }
            }
            if (!recording) return@Thread
            runCatching { Thread.sleep(seconds * 1000L) }
            stop()
        }.apply { isDaemon = true }
        stopper.start()
        // The ceiling covers the start delay as well, or a slow-opening line would be cut short by the
        // ceiling rather than by the timer.
        return record(onLevel, maxSeconds = seconds + 3)
    }

    private companion object {
        /** How long to wait for the line to start before giving up on the countdown. */
        const val START_TIMEOUT_MS = 5_000L
    }
}
