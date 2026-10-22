package com.prism.desktop.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

/**
 * Turns a recording or an uploaded file into mono float samples for [VoiceCloner].
 *
 * The WAV parser below is the phone's `VoiceCloneAudio.decodeWav`, carried over rather than
 * reworked: the same chunk walk, the same odd-size padding, the same restrictions on encoding,
 * so a file Prism accepts on one build it accepts on the other. What cannot be shared is
 * `MediaExtractor`, which is Android-only -- here Java Sound takes its place, with its own
 * honest limits stated on failure: MP3 and friends decode only when a matching Java Sound SPI
 * is installed, and the error says as much instead of dying inside a codec.
 */
object DesktopVoiceAudio {

    data class Decoded(val samples: FloatArray, val rate: Int)

    fun decodeFile(file: File): Decoded {
        val bytes = file.readBytes()
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val wav = runCatching { decodeWav(bytes) }
            if (wav.isSuccess) return wav.getOrThrow()
        }
        return decodeWithJavaSound(file)
    }

    fun decodeWav(bytes: ByteArray): Decoded {
        if (bytes.size < 44) throw IllegalStateException("That WAV file is too short")
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        fun le16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        if (tag(0) != "RIFF" || tag(8) != "WAVE") throw IllegalStateException("That is not a WAV file")

        var offset = 12
        var rate = 0
        var bits = 0
        var channels = 0
        var pcm: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = tag(offset)
            val size = le32(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    channels = le16(body + 2)
                    rate = le32(body + 4)
                    bits = le16(body + 14)
                }
                "data" -> {
                    val end = minOf(bytes.size, body + size)
                    pcm = bytes.copyOfRange(body, end)
                }
            }
            offset = body + size + (size and 1)
        }
        val data = pcm ?: throw IllegalStateException("No audio data in that WAV")
        if (rate <= 0) throw IllegalStateException("Could not read the sample rate")

        val samples = when (bits) {
            16 -> {
                val count = data.size / 2
                val shorts = ShortArray(count)
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                if (channels <= 1) shortsToFloat(shorts, count) else downmix(shorts, count, channels)
            }
            32 -> {
                val count = data.size / 4
                val floats = FloatArray(count)
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                if (channels <= 1) floats else downmixFloat(floats, count, channels)
            }
            else -> throw IllegalStateException("Only 16-bit or 32-bit WAV is supported")
        }
        val peak = samples.maxOf { abs(it) }
        if (peak < 1e-5f) throw IllegalStateException("That file is silent")
        return Decoded(samples, rate)
    }

    private fun decodeWithJavaSound(file: File): Decoded {
        val source = runCatching { AudioSystem.getAudioInputStream(file) }.getOrElse {
            throw IllegalStateException(
                "Desktop Prism reads WAV, AIFF and AU audio; for anything else install the " +
                    "matching Java Sound plugin and try again",
            )
        }
        try {
            val sourceFormat = source.format
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.sampleRate,
                16,
                1,
                2,
                sourceFormat.sampleRate,
                false,
            )
            val converted =
                if (AudioSystem.isConversionSupported(target, sourceFormat)) {
                    AudioSystem.getAudioInputStream(target, source)
                } else {
                    if (sourceFormat.channels > 1 || sourceFormat.encoding != AudioFormat.Encoding.PCM_SIGNED) {
                        throw IllegalStateException("That file's format cannot be read here")
                    }
                    if (sourceFormat.sampleSizeInBits == 16 && sourceFormat.isBigEndian) {
                        // Plain PCM_SIGNED big-endian: Java Sound cannot convert it here
                        throw IllegalStateException("That file's format cannot be read here")
                    }
                    source
                }
            try {
                val raw = converted.readBytes()
                if (raw.isEmpty()) throw IllegalStateException("No audio came out of that file")
                val count = raw.size / 2
                val shorts = ShortArray(count)
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val floats = shortsToFloat(shorts, count)
                val peak = floats.maxOf { abs(it) }
                if (peak < 1e-5f) throw IllegalStateException("That file is silent")
                return Decoded(floats, target.sampleRate.toInt())
            } finally {
                runCatching { converted.close() }
            }
        } finally {
            runCatching { source.close() }
        }
    }

    fun shortsToFloat(input: ShortArray, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = input[i] / 32768f
        return out
    }

    private fun downmix(input: ShortArray, count: Int, channels: Int): FloatArray {
        val frames = count / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += input[f * channels + c]
            out[f] = (sum / channels) / 32768f
        }
        return out
    }

    private fun downmixFloat(input: FloatArray, count: Int, channels: Int): FloatArray {
        val frames = count / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += input[f * channels + c]
            out[f] = sum / channels
        }
        return out
    }
}

/**
 * Microphone capture -- desktop's answer to the phone's AudioRecord loop.
 *
 * The phone records on press-and-hold; that is a touch idiom, and with a mouse it means a cramped
 * hold-and-release whose release timing mangles the take. Here the button TOGGLES, and the shortness
 * rule that protected the phone's flow (400 ms, then the profile's own checks) is unchanged.
 *
 * Rates are probed in order because a desktop mixer is NOT guaranteed to open at [preferredRate];
 * falling back to another rate is fine, since [VoiceCloner.profile] resamples whatever arrives.
 */
class DesktopVoiceRecorder {

    /** What [stop] hands back when the take is analysable. */
    class Take(val samples: FloatArray, val rate: Int)

    private val chunks = ArrayList<Byte>(256 * 1024)

    @Volatile
    private var recording = false

    private var line: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var startedAt = 0L
    private var rate = 0

    val isRecording: Boolean get() = recording

    /**
     * Opens a line and captures until [stop]. Returns an error message, or null when recording has
     * actually begun. Threaded, so the UI thread never waits for a mixer to spin up.
     */
    @Synchronized
    fun start(): String? {
        if (recording) return null
        val format = openFormat() ?: return "The microphone could not be opened"
        val opened = runCatching {
            val l = AudioSystem.getTargetDataLine(format)
            l.open(format, BUFFER_BYTES)
            l.start()
            l
        }.getOrElse { return "The microphone could not be opened" }
        synchronized(chunks) { chunks.clear() }
        startedAt = System.currentTimeMillis()
        rate = format.sampleRate.toInt()
        line = opened
        recording = true
        captureThread = Thread({ captureLoop(opened) }, "voice-clone-rec-mic").apply {
            isDaemon = true
            start()
        }
        return null
    }

    private fun captureLoop(l: TargetDataLine) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (recording) {
            val read = l.read(buffer, 0, buffer.size)
            if (read <= 0) break
            synchronized(chunks) {
                for (i in 0 until read) chunks.add(buffer[i])
            }
        }
    }

    @Synchronized
    fun stop(): Take? {
        if (!recording) return null
        recording = false
        val worker = captureThread
        captureThread = null
        val active = line
        line = null
        worker?.join(2000)
        runCatching { active?.stop(); active?.close() }
        val elapsed = System.currentTimeMillis() - startedAt
        val copy: ByteArray = synchronized(chunks) {
            val c = chunks.toByteArray()
            chunks.clear()
            c
        }
        if (elapsed < 400) return null
        val byteCount = copy.size / 2
        if (byteCount < rate * 0.4) return null
        val shorts = ShortArray(byteCount)
        ByteBuffer.wrap(copy, 0, byteCount * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Take(DesktopVoiceAudio.shortsToFloat(shorts, byteCount), rate)
    }

    companion object {
        private const val BUFFER_BYTES = 4096

        private fun openFormat(): AudioFormat? {
            for (rate in intArrayOf(16000, 22050, 44100, 48000)) {
                val format = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, 1, 2, rate.toFloat(), false,
                )
                if (AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))) {
                    return format
                }
            }
            return null
        }
    }
}
