package com.prism.launcher.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bytes that cross the mesh when one machine asks another to speak for it.
 *
 * ## Why audio needs its own codec here
 *
 * Everything else Prism puts on the mesh is JSON — a DNS record, a capability list, a chess move.
 * Audio is not: ten seconds of Kokoro output at 24 kHz is about half a megabyte of PCM, and
 * base64-ing it into a JSON string would grow it by a third and make the whole payload something
 * only a JSON parser could love. So the bulk of the traffic is a binary body, and the only JSON in
 * the exchange is the small envelope around it.
 *
 * ## The two formats, and why both exist
 *
 * **Speech in** is mono 16-bit PCM in a RIFF/WAVE wrapper. The wrapper is not decoration: a player
 * that is handed raw samples has to be told a rate out of band, and the desktop's audio stack
 * (`javax.sound.sampled`) genuinely needs a format, not just numbers. The 44-byte header is what
 * makes one payload playable on both platforms — see [com.prism.launcher.messaging.Dictation.wrapAsWav],
 * which already writes exactly this header for Whisper uploads and is reused rather than copied.
 *
 * **Cloning samples** are float32 little-endian with no header at all, because they are not meant
 * to be played: they are input to an ONNX encoder. The rate travels in the URL, where it is
 * readable in a log and cannot be forgotten by a decoder that does not exist.
 *
 * ## What this deliberately does not do
 *
 * No compression, no streaming, no chunked transfer. A request is one sentence to one paragraph,
 * the link is a LAN or a tunnel, and a socket that reads to `Content-Length` and closes is a
 * server that cannot be left half-written. Streaming audio would be a real improvement and would
 * also be a second protocol to get right on two platforms, which is not the trade this feature
 * needs.
 */
object SpeechMeshWire {

    /** Audio ready to be handed to an [com.prism.core.AudioSink]. */
    data class Clip(val samples: FloatArray, val sampleRate: Int)

    /** Mono float samples in [-1, 1] to clipped 16-bit little-endian PCM. */
    fun pcm16(samples: FloatArray): ByteArray {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) {
            bytes.putShort((value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return bytes.array()
    }

    /** A playable WAV around [samples]. */
    fun wav(samples: FloatArray, sampleRate: Int): ByteArray =
        com.prism.launcher.messaging.Dictation.wrapAsWav(pcm16(samples), sampleRate.coerceAtLeast(1))

    /** Raw float32 little-endian, the format cloning samples travel in. */
    fun floats(samples: FloatArray): ByteArray {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) bytes.putFloat(value)
        return bytes.array()
    }

    /** The inverse of [floats]; never throws for a short or odd-sized body. */
    fun readFloats(bytes: ByteArray): FloatArray {
        if (bytes.size < 4) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 4
        return FloatArray(count) { buffer.getFloat(it * 4) }
    }

    /**
     * Decodes a WAV this object (or any conforming writer) produced.
     *
     * Only what Prism itself emits is accepted: PCM, 16-bit, one or two channels. A general
     * decoder is a large thing and every branch in one is a way to be wrong on the audio path;
     * the sender here is always another Prism, which uses [wav].
     *
     * Returns null rather than throwing, because a corrupt body should be reported to the user as
     * "the other machine sent something unreadable", not crash the speaking thread.
     */
    fun decodeWav(bytes: ByteArray): Clip? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        var channels = 1
        var bits = 16
        var rate = 0
        var dataOffset = -1
        var dataLength = 0

        // Walk the chunk table rather than assuming the canonical 44-byte layout: some writers
        // put a LIST chunk between fmt and data, and a byte-offset parser silently reads the
        // middle of a comment as samples.
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = le32(bytes, at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (size >= 16 && body + 16 <= bytes.size) {
                        channels = le16(bytes, body + 2).coerceAtLeast(1)
                        rate = le32(bytes, body + 4)
                        bits = le16(bytes, body + 14).coerceAtLeast(8)
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size.coerceAtMost(bytes.size - body)
                }
            }
            if (size < 0) return null
            at = body + size + (size and 1) // chunks are word-aligned
        }

        if (dataOffset < 0 || rate <= 0) return null
        if (bits != 16) return null

        val frames = dataLength / (2 * channels)
        if (frames <= 0) return Clip(FloatArray(0), rate)
        val out = FloatArray(frames)
        var cursor = dataOffset
        for (i in 0 until frames) {
            var mixed = 0f
            for (c in 0 until channels) {
                val value = le16(bytes, cursor)
                // 16-bit two's complement, sign-extended.
                mixed += (if (value >= 0x8000) value - 0x10000 else value) / 32768f
                cursor += 2
            }
            out[i] = mixed / channels
        }
        return Clip(out, rate)
    }

    /** Concatenates clips, resampling any whose rate differs from the first. */
    fun join(clips: List<Clip>): Clip? {
        if (clips.isEmpty()) return null
        val rate = clips.first().sampleRate
        if (clips.size == 1) return clips.first()
        var total = 0
        val parts = clips.map { clip ->
            val part = if (clip.sampleRate == rate) clip.samples
            else VoicePitch.resample(clip.samples, clip.sampleRate, rate)
            total += part.size
            part
        }
        val out = FloatArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return Clip(out, rate)
    }

    /** Base64 for the JSON envelope around a clone's per-engine artifacts. */
    fun encode64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    fun decode64(text: String): ByteArray? =
        runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** Kept for callers that build a body in memory; avoids importing ByteArrayOutputStream twice. */
    internal fun emptyBody(): ByteArrayOutputStream = ByteArrayOutputStream()
}
