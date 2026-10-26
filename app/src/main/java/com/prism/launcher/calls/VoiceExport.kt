package com.prism.launcher.calls

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns selected voicemails into files for Android's save picker.
 *
 * ## MP3 when the device can make one
 *
 * Prism does not ship an MP3 encoder. Android does not require one either, so the encoder is
 * discovered at export time; phones that have one produce a real `.mp3`, and phones that do not
 * produce the WAV Prism already stores rather than a file with a lying extension. Naming PCM audio
 * `.mp3` would fail to open everywhere the user took it, which is worse than a format they did not
 * ask for but can hear.
 *
 * Nothing here runs on the UI thread: encoding a few minutes of audio through MediaCodec is not
 * instant.
 */
object VoiceExport {

    data class Item(val name: String, val bytes: ByteArray)

    data class Prepared(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
        val mp3: Boolean,
    )

    fun prepare(items: List<Item>, label: String): Prepared? {
        if (items.isEmpty()) return null
        val encoded = items.map { item ->
            val mp3 = Mp3Encoder.encode(item.bytes)
            if (mp3 != null) {
                Prepared(nameFor(item.name, "mp3"), "audio/mpeg", mp3, true)
            } else {
                Prepared(nameFor(item.name, "wav"), "audio/wav", item.bytes, false)
            }
        }
        if (encoded.size == 1) return encoded.first()

        val archive = PrismPhonePlan.Export.zip(encoded.map { it.name to it.bytes }) ?: return null
        return Prepared(
            name = "$label-${encoded.size}.zip",
            mime = "application/zip",
            bytes = archive,
            mp3 = encoded.all { it.mp3 },
        )
    }

    private fun nameFor(name: String, extension: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$base.$extension"
    }

    /**
     * The platform's MP3 encoder, if it has one.
     *
     * MediaCodec's MP3 encoder is optional hardware/software. Asking the codec list first is what
     * keeps this honest: a missing encoder is a normal result, not an exception the export path has
     * to guess about.
     */
    private object Mp3Encoder {

        private const val MIME = "audio/mpeg"
        private const val BIT_RATE = 64_000

        fun encode(wav: ByteArray): ByteArray? {
            val pcm = readWav(wav) ?: return null
            val encoderName = runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { info ->
                        info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
                    }?.name
            }.getOrNull() ?: return null

            return runCatching {
                val codec = MediaCodec.createByCodecName(encoderName)
                try {
                    val format = MediaFormat.createAudioFormat(
                        MIME, pcm.sampleRate, pcm.channels
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    }
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()

                    val output = ByteArrayOutputStream()
                    val info = MediaCodec.BufferInfo()
                    val bytesPerFrame = pcm.channels * 2
                    var offset = 0
                    var presentationUs = 0L
                    var inputDone = false
                    var attempts = 0

                    while (attempts < 10_000) {
                        attempts += 1
                        if (!inputDone) {
                            val inputIndex = codec.dequeueInputBuffer(5_000)
                            if (inputIndex >= 0) {
                                val buffer = codec.getInputBuffer(inputIndex) ?: break
                                buffer.clear()
                                val size = minOf(buffer.capacity(), pcm.pcm.size - offset)
                                if (size <= 0) {
                                    codec.queueInputBuffer(
                                        inputIndex, 0, 0, presentationUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    buffer.put(pcm.pcm, offset, size)
                                    codec.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                                    offset += size
                                    presentationUs += size.toLong() * 1_000_000L /
                                        (pcm.sampleRate.toLong() * bytesPerFrame)
                                }
                            }
                        }

                        when (val outputIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            else -> if (outputIndex >= 0) {
                                if (info.size > 0 &&
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    val buffer = codec.getOutputBuffer(outputIndex)
                                    if (buffer != null) {
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        val chunk = ByteArray(info.size)
                                        buffer.get(chunk)
                                        output.write(chunk)
                                    }
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    break
                                }
                            }
                        }
                    }

                    output.toByteArray().takeIf { it.isNotEmpty() }
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }.getOrNull()
        }

        private data class Pcm(
            val sampleRate: Int,
            val channels: Int,
            val pcm: ByteArray,
        )

        /**
         * Reads a PCM WAV without trusting its declared chunk order. `fmt ` carries the format,
         * `data` the samples; everything else is skipped.
         */
        private fun readWav(wav: ByteArray): Pcm? {
            if (wav.size < 44) return null
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != 0x46464952) return null // "RIFF"
            buffer.int
            if (buffer.int != 0x45564157) return null // "WAVE"

            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1
            var dataSize = 0
            while (buffer.remaining() >= 8) {
                val id = buffer.int
                val size = buffer.int
                val start = buffer.position()
                when (id) {
                    0x20746d66 -> { // "fmt "
                        if (buffer.remaining() >= 16) {
                            val format = buffer.short.toInt()
                            channels = buffer.short.toInt()
                            sampleRate = buffer.int
                            buffer.int
                            buffer.short
                            bits = buffer.short.toInt()
                            if (format != 1) return null
                        }
                    }
                    0x61746164 -> { // "data"
                        dataOffset = start
                        dataSize = minOf(size, buffer.remaining())
                    }
                }
                val next = start + size + (size and 1)
                if (next <= start || next > wav.size) break
                buffer.position(next)
                if (dataOffset >= 0 && sampleRate > 0) break
            }
            if (sampleRate <= 0 || channels <= 0 || bits != 16 || dataOffset < 0) return null
            return Pcm(sampleRate, channels, wav.copyOfRange(dataOffset, dataOffset + dataSize))
        }
    }
}
