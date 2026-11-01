package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import org.jcodec.codecs.aac.AACDecoder
import org.jcodec.codecs.aac.AACUtils
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import java.io.File
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The audio track of a video, on the desktop.
 *
 * Android already gets this for free: `MediaExtractor` finds the first audio track in an MP4 and
 * `MediaCodec` decodes it, so a video row arrives at [com.prism.launcher.speech.AudioDecoding] as a
 * clip. The JVM has no such platform decoder. JCodec (already here for frame extraction and H.264
 * encoding) can demux MP4 and decode AAC, which is what almost every MP4/MOV speech dataset carries;
 * other containers fall back to an installed `ffmpeg`, which can open everything and is only invoked
 * when it exists.
 *
 * The returned clip is mono at the source rate, exactly the shape every other decoder returns. The
 * transcription pass resamples it to whisper's 16 kHz itself, so no rate conversion happens twice.
 */
object DesktopVideoAudio {

    /** Extensions [read] will try. A file with any other extension is not this hook's business. */
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi")

    /** MP4-family containers JCodec can demux. */
    private val MP4_EXTENSIONS = setOf("mp4", "m4v", "mov", "m4a")

    /** Same ceiling as the built-in reader: a clip longer than ten minutes is not a clip. */
    private const val MAX_SECONDS = 600

    /** A decoder that never reaches the end of a corrupt file gives up here. */
    private const val FFMPEG_TIMEOUT_SECONDS = 180L

    /** Null when the file has no audio this machine can decode. Never throws. */
    fun read(file: File): SpeechClip? {
        if (!file.isFile) return null
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in VIDEO_EXTENSIONS) return null
        if (extension in MP4_EXTENSIONS) {
            runCatching { readMp4(file) }
                .onFailure {
                    PrismPlatform.log.info(
                        "Prism/speech",
                        "JCodec could not read the audio of ${file.name}: ${it.message}",
                    )
                }
                .getOrNull()
                ?.let { return it }
        }
        return readWithFfmpeg(file)
    }

    /**
     * MP4/MOV through JCodec's demuxer and its JAAD-backed AAC decoder.
     *
     * The audio-specific-config bytes are the decoder's constructor argument; without them JAAD can
     * only decode ADTS streams, and an MP4 carries raw AAC frames. That is the one detail that makes
     * this route work at all, which is why it is read from the sample entry rather than guessed.
     */
    private fun readMp4(file: File): SpeechClip? {
        val channel = NIOUtils.readableChannel(file)
        var demuxer: MP4Demuxer? = null
        try {
            demuxer = MP4Demuxer.createMP4Demuxer(channel)
            val track = demuxer.audioTracks.firstOrNull() as? AbstractMP4DemuxerTrack ?: return null
            if (!track.getFourcc().equals("mp4a", true)) return null
            val entry = track.getSampleEntries().firstOrNull() ?: return null
            val config = AACUtils.getCodecPrivate(entry) ?: return null
            val decoder = AACDecoder(config)
            val pcm = ArrayList<Short>()
            var sampleRate = 0
            var channels = 1
            while (true) {
                val packet = track.nextFrame() ?: break
                val decoded = runCatching { decoder.decodeFrame(packet.data, null) }
                    .getOrNull() ?: break
                sampleRate = decoded.format.sampleRate
                channels = decoded.format.channels.coerceAtLeast(1)
                val bytes = decoded.data
                bytes.order(if (decoded.format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                while (bytes.remaining() >= channels * 2) {
                    var sum = 0
                    repeat(channels) { sum += bytes.short.toInt() }
                    pcm += (sum / channels).toShort()
                }
                if (pcm.size / sampleRate.coerceAtLeast(1) >= MAX_SECONDS) break
            }
            if (pcm.isEmpty() || sampleRate <= 0) return null
            val wanted = pcm.size.coerceAtMost(MAX_SECONDS * sampleRate)
            return SpeechClip(FloatArray(wanted) { pcm[it] / 32768f }, sampleRate)
        } finally {
            runCatching { demuxer?.close() }
            runCatching { channel.close() }
        }
    }

    /**
     * Anything `ffmpeg` on this machine can open, decoded to a temporary mono WAV.
     *
     * Checked last, and failure is normal: the desktop build does not ship ffmpeg, so a machine
     * without it reports an undecodable clip rather than pretending the video was silent.
     */
    private fun readWithFfmpeg(file: File): SpeechClip? {
        val temporary = runCatching {
            File.createTempFile("prism-video-audio-", ".wav")
        }.getOrNull() ?: return null
        return try {
            val process = runCatching {
                ProcessBuilder(
                    System.getenv("PRISM_FFMPEG").orEmpty().ifBlank { "ffmpeg" },
                    "-v", "error",
                    "-y",
                    "-i", file.absolutePath,
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "wav",
                    temporary.absolutePath,
                ).redirectErrorStream(true).start()
            }.getOrNull() ?: return null
            val finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                PrismPlatform.log.warn(
                    "Prism/speech",
                    "ffmpeg took longer than ${FFMPEG_TIMEOUT_SECONDS}s on ${file.name}",
                )
                return null
            }
            if (process.exitValue() != 0 || !temporary.isFile || temporary.length() <= 44) {
                return null
            }
            AudioDecoding.readWav(temporary)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { temporary.delete() }
        }
    }
}
