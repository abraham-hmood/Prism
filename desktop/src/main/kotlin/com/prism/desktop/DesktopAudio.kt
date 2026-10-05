package com.prism.desktop

import com.prism.core.AudioSink
import com.prism.core.MainThread
import com.prism.core.PrismPlatform
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities

/**
 * Sound out of a PC. PHASE 100.
 *
 * ## 16-bit PCM, not float, and the reason is not preference
 *
 * `AudioTrack` on Android takes `ENCODING_PCM_FLOAT` directly, so the Android sink hands Kokoro's
 * output straight through. `javax.sound.sampled` predates that: `AudioFormat.Encoding.PCM_FLOAT`
 * exists in the API but almost no real mixer reports supporting it, and asking for one on a machine
 * that does not gets `LineUnavailableException` rather than a fallback. So the samples are converted
 * to signed 16-bit little-endian here, which every output device on both targets supports.
 *
 * The conversion clamps rather than wrapping. A neural vocoder does occasionally produce a sample
 * slightly outside [-1, 1], and `(1.02f * 32767).toInt().toShort()` wraps to a large NEGATIVE number
 * -- one sample of full-scale inverted noise, audible as a click on every sentence.
 *
 * ## Following the playback position, not the write position
 *
 * The same requirement [AudioSink] states and for the same reason. `SourceDataLine.write` blocks once
 * the internal buffer fills, so the writes pace themselves, but the last buffer's worth is still
 * queued when the final write returns. `drain()` is exactly the "wait until it has been heard" call
 * -- with the catch that it does NOT return early when the line is stopped from another thread, so a
 * cancellation has to `flush()` before draining or the stop blocks for the length of the buffer.
 */
class DesktopAudioSink : AudioSink {

    @Volatile
    private var line: SourceDataLine? = null

    override fun isAvailable(): Boolean = runCatching {
        AudioSystem.getMixerInfo().isNotEmpty() &&
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format(24_000)))
    }.getOrDefault(false)

    override fun unavailableReason(): String = when {
        runCatching { AudioSystem.getMixerInfo().isEmpty() }.getOrDefault(true) ->
            "This machine reports no audio mixer at all, which is usual for a headless server."
        else ->
            "No output line accepts 16-bit mono PCM, which every ordinary sound device does — the " +
                "device is most likely in use exclusively by something else."
    }

    private fun format(sampleRate: Int) = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRate.toFloat(),
        16,
        1,
        2,
        sampleRate.toFloat(),
        false, // little-endian, which is what the conversion below writes
    )

    override fun play(samples: FloatArray, sampleRate: Int, cancelled: () -> Boolean): String? {
        stop()
        if (cancelled()) return null
        if (samples.isEmpty()) return null

        val shape = format(sampleRate)
        val opened = runCatching {
            val instance = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, shape))
                as SourceDataLine
            // A quarter-second buffer, for the same reason the Android sink uses one: a buffer large
            // enough for the whole clip means the writes never block, so playback and the write loop
            // stop being related and the line is closed while audio is still queued.
            instance.open(shape, (sampleRate / 4) * 2)
            instance
        }.getOrElse {
            return it.message ?: "No audio output line would open"
        }

        line = opened
        opened.start()

        val bytes = ByteArray(CHUNK * 2)
        var index = 0
        while (index < samples.size && !cancelled()) {
            val count = minOf(CHUNK, samples.size - index)
            for (i in 0 until count) {
                // CLAMPED. See the class comment: one unclamped out-of-range sample wraps to
                // full-scale inverted noise and clicks.
                val clamped = samples[index + i].coerceIn(-1f, 1f)
                val value = (clamped * 32767f).toInt()
                bytes[i * 2] = (value and 0xFF).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            val written = runCatching { opened.write(bytes, 0, count * 2) }.getOrElse { 0 }
            if (written <= 0) break
            index += written / 2
        }

        if (cancelled()) {
            // flush() BEFORE drain(), or the drain waits out a buffer that is going to be discarded
            // anyway -- which is the difference between stop() being instant and taking 250 ms.
            runCatching { opened.flush() }
        } else {
            runCatching { opened.drain() }
        }

        stop()
        return null
    }

    override fun stop() {
        val instance = line ?: return
        line = null
        runCatching { instance.stop() }
        runCatching { instance.flush() }
        runCatching { instance.close() }
    }

    private companion object {
        /** Samples per write. Small enough that a cancellation is noticed promptly. */
        const val CHUNK = 2048
    }
}

/**
 * Compose Desktop's UI thread, which is AWT's event queue.
 *
 * `SwingUtilities.invokeLater` rather than a Compose `Dispatchers.Main`, because this has to work
 * before any window exists -- the console commands speak too, and they have no composition.
 */
object AwtMainThread : MainThread {
    override fun post(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}

/**
 * Plays a mono float buffer and reports what happened, for the console's `speak` command.
 *
 * Exists so a headless machine can be told apart from a broken voice without a window.
 */
fun describeAudioOutput(): String {
    val sink = PrismPlatform.audio
    if (!sink.isAvailable()) return sink.unavailableReason()
    val devices = runCatching {
        AudioSystem.getMixerInfo().filter { info ->
            runCatching {
                AudioSystem.getMixer(info).sourceLineInfo.any { it is DataLine.Info }
            }.getOrDefault(false)
        }.map { it.name }
    }.getOrDefault(emptyList())
    return if (devices.isEmpty()) {
        "An output line is available but no mixer names itself."
    } else {
        devices.size.toString() + " output device(s): " + devices.joinToString(", ")
    }
}
