package com.prism.launcher.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Turns a recording or a picked file into mono float samples for [VoiceCloner]. */
object VoiceCloneAudio {

    fun decodeUri(context: Context, uri: Uri): Pair<FloatArray, Int> {
        val type = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        val isWav = type.contains("wav") || name.endsWith(".wav")
        if (isWav) {
            val wavResult = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read that file")
                decodeWav(bytes)
            }
            if (wavResult.isSuccess) return wavResult.getOrThrow()
            return decodeWithExtractor(context, uri)
        }
        val extractorResult = runCatching { decodeWithExtractor(context, uri) }
        if (extractorResult.isSuccess) return extractorResult.getOrThrow()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null && bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            return decodeWav(bytes)
        }
        throw extractorResult.exceptionOrNull() ?: IllegalStateException("Could not decode audio file")
    }

    fun shortsToFloat(input: ShortArray, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = input[i] / 32768f
        return out
    }

    private fun decodeWav(bytes: ByteArray): Pair<FloatArray, Int> {
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
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                buffer.get(shorts)
                if (channels <= 1) shortsToFloat(shorts, count)
                else downmix(shorts, count, channels)
            }
            32 -> {
                val count = data.size / 4
                val floats = FloatArray(count)
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                if (channels <= 1) floats
                else downmixFloat(floats, count, channels)
            }
            else -> throw IllegalStateException("Only 16-bit or 32-bit WAV is supported")
        }
        return samples to rate
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

    private fun decodeWithExtractor(context: Context, uri: Uri): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        try {
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IllegalStateException("That file has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Unknown audio format")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val pcmChunks = ArrayList<Short>(sampleRate * 30)
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false

                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: break
                            val read = extractor.readSampleData(buffer, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex) ?: break
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ShortArray(bufferInfo.size / 2)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.asShortBuffer().get(chunk)
                            if (channels <= 1) {
                                pcmChunks.addAll(chunk.toList())
                            } else {
                                for (f in chunk.indices step channels) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += chunk.getOrElse(f + c) { 0 }
                                    pcmChunks.add((sum / channels).toShort())
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* rate stays from format */ }
                    }
                }

                if (pcmChunks.isEmpty()) throw IllegalStateException("No audio came out of that file")
                val floats = FloatArray(pcmChunks.size) { pcmChunks[it] / 32768f }
                val peak = floats.maxOf { abs(it) }
                if (peak < 1e-5f) throw IllegalStateException("That file is silent")
                return floats to sampleRate
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { pfd?.close() }
        }
    }
}
