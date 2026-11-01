package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.max
import kotlin.math.min

/**
 * The desktop half of [AudioDecoding]: MP3, and anything else a Java Sound SPI can open.
 *
 * ## Why a Java Sound SPI and not a hand-written MP3 decoder
 *
 * MP3 is not a container a few lines can unpack -- Huffman tables, a bit reservoir, the MDCT and
 * three different window sequences. `mp3spi` is a small, pure-Java implementation of exactly that
 * interface, backed by JLayer, and registering it with Java Sound means the rest of this file is the
 * same format conversion and PCM read a WAV takes. It has no native component, so the packaged desktop
 * build does not grow a per-platform binary.
 *
 * ## What this returns, and what it deliberately does not
 *
 * A mono [SpeechClip] in [-1, 1] at the file's own sample rate, capped at [MAX_SECONDS] like the
 * built-in WAV reader. WAV is left to the built-in reader: it already streams the `data` chunk without
 * decoding the whole file, and this hook is for the formats that reader cannot open. Returning null is
 * how the hook says "not mine"; [AudioDecoding.read] then falls back.
 */
object DesktopAudioDecoding {

    /** Same ceiling as the built-in reader: a dataset clip longer than ten minutes is not a clip. */
    private const val MAX_SECONDS = 600

    /** Installs the decoder once, from `main`. */
    fun install() {
        AudioDecoding.decoder = ::read
    }

    /** The hook itself. Null when this is a WAV or no reader on this machine can open the file. */
    fun read(file: File): SpeechClip? {
        if (!file.isFile) return null
        if (file.extension.lowercase() in AudioDecoding.WAV_EXTENSIONS) return null
        // A video row's transcript comes from its audio track. Java Sound cannot open an MP4, so
        // the container is handed to the JCodec/ffmpeg path before the audio-SPI one is tried.
        if (file.extension.lowercase() in DesktopVideoAudio.VIDEO_EXTENSIONS) {
            DesktopVideoAudio.read(file)?.let { return it }
        }
        return runCatching { decode(file) }
            .onFailure {
                PrismPlatform.log.warn("Prism/speech", "Could not decode ${file.name}: ${it.message}")
            }
            .getOrNull()
    }

    private fun decode(file: File): SpeechClip? {
        AudioSystem.getAudioInputStream(file).use { source ->
            val base = source.format
            val rate = base.sampleRate.toInt().takeIf { it > 0 } ?: return null
            val channels = base.channels.takeIf { it > 0 } ?: return null
            // PCM signed 16-bit little-endian is the one shape every mixer and the shared audio
            // pipeline agree on. The conversion is done by Java Sound's own converters, not by
            // guessing at the source encoding.
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                rate.toFloat(),
                16,
                channels,
                channels * 2,
                rate.toFloat(),
                false,
            )
            val alreadyPcm = base.encoding == AudioFormat.Encoding.PCM_SIGNED &&
                base.sampleSizeInBits == 16 &&
                !base.isBigEndian &&
                base.channels == channels &&
                base.sampleRate == target.sampleRate
            val converted = if (alreadyPcm) source else AudioSystem.getAudioInputStream(target, source)
            return try {
                readPcm(converted, rate, channels)
            } finally {
                // Closing the wrapper closes the source chain; closing a stream twice is harmless.
                if (converted !== source) runCatching { converted.close() }
            }
        }
    }

    /**
     * Reads 16-bit PCM in whole frames and averages the channels.
     *
     * It streams rather than loading the file: an hour-long MP3 decoded into a byte array and then a
     * float array is two live copies of something no metric here wants. The output grows as it reads
     * and is trimmed once at the end.
     */
    private fun readPcm(stream: AudioInputStream, rate: Int, channels: Int): SpeechClip? {
        val frameSize = channels * 2
        if (frameSize <= 0) return null
        val maxFrames = rate.toLong() * MAX_SECONDS
        val blockFrames = max(1, 64 * 1024 / frameSize)
        val block = ByteArray(blockFrames * frameSize)
        var samples = FloatArray(min(maxFrames, 1L shl 16).toInt().coerceAtLeast(blockFrames))
        var frames = 0
        while (true) {
            val read = stream.read(block)
            if (read <= 0) break
            val complete = read / frameSize
            if (complete <= 0) continue
            val allowed = min(complete.toLong(), maxFrames - frames).toInt()
            if (allowed <= 0) break
            if (frames + allowed > samples.size) {
                val wanted = min(maxFrames, max(samples.size.toLong() * 2, frames + allowed.toLong()))
                samples = samples.copyOf(wanted.toInt())
            }
            val buffer = ByteBuffer.wrap(block, 0, allowed * frameSize).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until allowed) {
                var sum = 0
                repeat(channels) { sum += buffer.short.toInt() }
                samples[frames++] = sum.toFloat() / (32768f * channels)
            }
            if (frames.toLong() >= maxFrames) {
                PrismPlatform.log.warn(
                    "Prism/speech",
                    "A ${fileLabel(stream)} clip is longer than ${MAX_SECONDS}s; only the first " +
                        "${MAX_SECONDS}s are decoded",
                )
                break
            }
        }
        if (frames == 0) return null
        return SpeechClip(if (frames == samples.size) samples else samples.copyOf(frames), rate)
    }

    /** The stream has no name; the warning above only needs to say it was a long file. */
    private fun fileLabel(stream: AudioInputStream): String =
        stream.javaClass.simpleName.ifBlank { "audio" }
}
