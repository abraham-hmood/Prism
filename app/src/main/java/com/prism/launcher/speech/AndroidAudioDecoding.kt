package com.prism.launcher.speech

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * The Android half of [AudioDecoding]: anything the platform's decoders can open.
 *
 * `MediaExtractor` finds the first audio track and hands its compressed samples to `MediaCodec`; the
 * decoder returns PCM, which is averaged to mono exactly as the shared WAV reader does. That covers
 * MP3, FLAC, Ogg/Opus, M4A/AAC and the rest of the formats the phone already plays, without shipping a
 * second decoder or a native binary.
 *
 * WAV is deliberately left to the built-in reader, which streams the `data` chunk and needs no codec
 * instance. Returning null means "not mine"; [AudioDecoding.read] falls back.
 *
 * The decoder runs synchronously with short timeouts. A corrupt file can leave a codec waiting for
 * input it will never get, so the loop also has a wall-clock ceiling and gives up rather than keeping a
 * background thread alive forever.
 */
object AndroidAudioDecoding {

    /** Same ceiling as the built-in reader: a dataset clip longer than ten minutes is not a clip. */
    private const val MAX_SECONDS = 600

    /** A decoder that never reaches end-of-stream gives up here rather than looping for ever. */
    private const val MAX_DECODE_MS = 120_000L

    /** Installs the decoder once, from the application's `onCreate`. */
    fun install() {
        AudioDecoding.decoder = ::read
    }

    /** The hook itself. Null when this is a WAV or the platform has no decoder for it. */
    fun read(file: File): SpeechClip? {
        if (!file.isFile) return null
        if (file.extension.lowercase() in AudioDecoding.WAV_EXTENSIONS) return null
        return runCatching { decode(file) }
            .onFailure {
                PrismPlatform.log.warn("Prism/speech", "Could not decode ${file.name}: ${it.message}")
            }
            .getOrNull()
    }

    private fun decode(file: File): SpeechClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = index
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            var samples = FloatArray(1 shl 16)
            var frames = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            val deadline = System.currentTimeMillis() + MAX_DECODE_MS

            while (!outputDone) {
                // The decoder may keep accepting input for a while before its first output buffer.
                // Only a decoder that has been handed the end of the stream AND then produced
                // nothing for several seconds is stuck; the wall-clock deadline covers the rest.
                if (inputDone && ++idle > 400) {
                    PrismPlatform.log.warn(
                        "Prism/speech",
                        "Decoder for ${file.name} stopped producing audio; using what was read",
                    )
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    PrismPlatform.log.warn(
                        "Prism/speech",
                        "Decoding ${file.name} took longer than ${MAX_DECODE_MS / 1000}s; using what was read",
                    )
                    break
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        idle = 0
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, size, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idle = 0
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        idle = 0
                        if (info.size > 0 && channels > 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val added = readFrames(
                                    buffer,
                                    sampleRate,
                                    channels,
                                    encoding,
                                    samples,
                                    frames,
                                )
                                frames = added.second
                                samples = added.first
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (frames == 0) return null
            val rate = sampleRate.takeIf { it > 0 } ?: return null
            return SpeechClip(if (frames == samples.size) samples else samples.copyOf(frames), rate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Appends one output buffer to [samples], growing it when needed.
     *
     * @return the (possibly larger) array and the new frame count. A frame is one sample per channel;
     *         channels are averaged, because every speech metric and trainer here is mono.
     */
    private fun readFrames(
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        encoding: Int,
        samples: FloatArray,
        frames: Int,
    ): Pair<FloatArray, Int> {
        val maxFrames = sampleRate.toLong() * MAX_SECONDS
        val available = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.remaining() / 4
            else -> buffer.remaining() / 2
        }
        val complete = min(available / channels, (maxFrames - frames).coerceAtLeast(0).toInt())
        if (complete <= 0) return samples to frames
        var out = samples
        if (frames + complete > out.size) {
            val wanted = min(maxFrames, max(out.size.toLong() * 2, (frames + complete).toLong()))
            out = out.copyOf(wanted.toInt())
        }
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            for (frame in 0 until complete) {
                var sum = 0f
                repeat(channels) { sum += floats.get() }
                out[frames + frame] = sum / channels
            }
        } else {
            val shorts = buffer.asShortBuffer()
            for (frame in 0 until complete) {
                var sum = 0
                repeat(channels) { sum += shorts.get().toInt() }
                out[frames + frame] = sum.toFloat() / (32768f * channels)
            }
        }
        return out to frames + complete
    }
}
