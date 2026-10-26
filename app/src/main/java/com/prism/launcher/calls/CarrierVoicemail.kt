package com.prism.launcher.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.VoicemailContract
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Pulls traditional voicemail out of the phone's visual-voicemail provider.
 *
 * ## What this can and cannot do
 *
 * A carrier mailbox is not a public API. Android's [VoicemailContract] is the one place a voicemail
 * app may publish the audio it received, and reading it is the supported route -- there is no
 * hidden protocol to join, no carrier login to scrape, and no audio on the SIM itself. Some
 * carriers fill the provider, some fill it only for their own dialer, and some fill nothing at all.
 * When it is empty this says so instead of pretending the mailbox was read.
 *
 * The same limitation applies to an app that "does not allow" export: the provider either exposes
 * its content to a permitted reader or it does not. Prism does not bypass that, because bypassing
 * it means reading another app's private storage without its consent -- which is not a feature,
 * it is a break-in. What Prism does instead is take every legitimate route: the provider above,
 * and importing an audio file the user already has.
 *
 * ## Why the audio is converted to WAV
 *
 * Providers publish AMR, AAC or MP3. Prism's voicemail store, playback and export are built around
 * one PCM format, so the file is decoded once on import rather than needing a different player for
 * every carrier. The conversion uses the platform decoder, so no extra codec is shipped.
 */
object CarrierVoicemail {

    data class Result(
        val imported: Int = 0,
        val withoutAudio: Int = 0,
        val message: String = "",
    )

    /** Columns that older providers may not publish; queried by name rather than assumed. */
    private val PROJECTION = arrayOf(
        VoicemailContract.Voicemails._ID,
        VoicemailContract.Voicemails.NUMBER,
        VoicemailContract.Voicemails.DATE,
        VoicemailContract.Voicemails.DURATION,
        VoicemailContract.Voicemails.SOURCE_DATA,
        VoicemailContract.Voicemails.HAS_CONTENT,
        VoicemailContract.Voicemails.MIME_TYPE,
    )

    /**
     * Reads every voicemail the provider exposes, converting each with content into the local
     * store. Blocking: call it off the UI thread.
     */
    fun sync(context: Context): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result(message = "Prism needs phone access before it can read the voicemail provider.")
        }

        val resolver = context.contentResolver
        var imported = 0
        var withoutAudio = 0
        val rows = runCatching {
            resolver.query(
                VoicemailContract.Voicemails.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${VoicemailContract.Voicemails.DATE} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(VoicemailContract.Voicemails._ID)
                val numberIndex = cursor.getColumnIndex(VoicemailContract.Voicemails.NUMBER)
                val dateIndex = cursor.getColumnIndex(VoicemailContract.Voicemails.DATE)
                val sourceIndex = cursor.getColumnIndex(VoicemailContract.Voicemails.SOURCE_DATA)
                val hasContentIndex = cursor.getColumnIndex(VoicemailContract.Voicemails.HAS_CONTENT)
                if (idIndex < 0) return@use mutableListOf<Row>()
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val hasContent = hasContentIndex < 0 ||
                            cursor.getInt(hasContentIndex) != 0
                        add(
                            Row(
                                id = id,
                                number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else "",
                                at = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L,
                                source = if (sourceIndex >= 0) cursor.getString(sourceIndex).orEmpty() else "",
                                hasContent = hasContent,
                            )
                        )
                    }
                }
            }
        }.getOrElse { error ->
            val reason = if (error is SecurityException) {
                "The voicemail provider refused access. This carrier or dialer keeps its audio " +
                    "private; import the file instead."
            } else {
                "No visual-voicemail provider answered (${error.message ?: "not available"})."
            }
            return Result(message = reason)
        }.orEmpty()

        if (rows.isEmpty()) {
            return Result(message = "The voicemail provider has no messages to read.")
        }

        rows.forEach { row ->
            // HAS_CONTENT is a hint, not a guarantee: some providers leave it at zero while still
            // publishing SOURCE_DATA. Look when either route could have bytes.
            if (!row.hasContent && row.source.isBlank()) {
                withoutAudio += 1
                return@forEach
            }
            val bytes = audioOf(context, row)
            val wav = bytes?.let { toWav(context, it) }
            if (wav == null) {
                withoutAudio += 1
                return@forEach
            }
            // Visual-voicemail dates are milliseconds, but some providers publish seconds. A value
            // below ten digits is treated as a Unix second; a missing date becomes now.
            val at = when {
                row.at in 1..9_999_999_999L -> row.at * 1000
                row.at > 0 -> row.at
                else -> System.currentTimeMillis()
            }
            val stored = PrismPhonePlan.Voicemail.store(
                wav = wav,
                fromNumber = row.number,
                at = at,
            )
            if (stored != null) imported += 1 else withoutAudio += 1
        }

        return Result(
            imported = imported,
            withoutAudio = withoutAudio,
            message = when {
                imported == 0 -> "The provider listed voicemail but exposed no readable audio."
                withoutAudio > 0 -> "Imported $imported voicemail(s); $withoutAudio had no accessible audio."
                else -> "Imported $imported voicemail(s)."
            },
        )
    }

    private data class Row(
        val id: Long,
        val number: String,
        val at: Long,
        val source: String,
        val hasContent: Boolean,
    )

    private fun audioOf(context: Context, row: Row): ByteArray? {
        val content = ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, row.id)
        runCatching {
            context.contentResolver.openInputStream(content)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        if (row.source.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(row.source)
            val stream: InputStream? = when {
                uri.scheme == "content" -> context.contentResolver.openInputStream(uri)
                uri.scheme == "file" -> FileInputStream(File(uri.path ?: return null))
                uri.scheme.isNullOrBlank() -> FileInputStream(File(row.source))
                else -> null
            }
            stream?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Decodes any provider audio to 16-bit PCM WAV.
     *
     * MediaExtractor/MediaCodec are the platform's own decoder chain. A file that is already WAV
     * is passed through; a file the decoder cannot open is rejected rather than stored with a
     * misleading extension.
     */
    fun toWav(context: Context, source: ByteArray): ByteArray? {
        if (isWav(source)) return source
        val temp = runCatching {
            File(context.cacheDir, "voicemail-import.tmp").apply { writeBytes(source) }
        }.getOrNull() ?: return null

        return runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(temp.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false

            while (true) {
                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        codec.getOutputBuffer(outputIndex)?.let { buffer ->
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                if (sawInputEnd && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }

            val samples = pcm.toByteArray()
            if (samples.isEmpty()) null else wavOf(samples, sampleRate, channels)
        }.getOrNull()
    }

    private fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    private fun wavOf(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le32(value: Int) {
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff); out.write((value shr 24) and 0xff)
        }
        fun le16(value: Int) {
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(channels * bits / 8); le16(bits)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
