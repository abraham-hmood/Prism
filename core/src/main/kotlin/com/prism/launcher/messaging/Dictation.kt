package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech to text.
 *
 * ## Why the unit of work is a WAV and not a microphone
 *
 * Because capture is the only genuinely platform-specific part. Desktop records through
 * `javax.sound.sampled`, Android through `AudioRecord`, and neither exists on the other — but both
 * produce the same thing: 16-bit PCM at a known sample rate. So the capability takes audio and returns
 * text, each platform supplies the recorder, and everything about WHAT transcribes is shared.
 *
 * That boundary is also what makes the plan's claim in PHASE 36 true rather than aspirational.
 * Android's [android.speech.SpeechRecognizer] captures its own audio and hands back a transcript, so it
 * cannot be one of these engines — it is a whole vertical slice that happens to work on one platform.
 * Splitting at "audio in, text out" is what lets desktop and Android run the SAME transcriber.
 *
 * ## The engines
 *
 * - **Cloud** — an OpenAI-compatible `audio/transcriptions` endpoint (Whisper, server-side). Works on
 *   both platforms today, needs no model download, costs money and the audio leaves the device.
 *
 * - **whisper.cpp** — the local route the plan names. Same author and build system as llama.cpp, whose
 *   ggml is already vendored here, so it is a small native addition rather than a new toolchain. It
 *   reports what it needs until the library and a model are present, rather than being absent.
 *
 * Android's SpeechRecognizer stays where it is, in `VoiceInputController`, and is not replaced by this.
 * It is free, instant and offline on most devices, which no engine here can be — so it remains the
 * default on Android and these are what make dictation work on a desktop and what make the two
 * platforms able to agree when consistency matters more than latency.
 *
 * ## Why 16 kHz mono is the contract
 *
 * Because it is what Whisper wants, in both its hosted and its local form — the model was trained on
 * 16 kHz and whisper.cpp resamples anything else on the way in. Recording at 44.1 kHz stereo and
 * shipping it would mean three times the bytes for audio that gets downsampled on arrival.
 */
interface TranscriptionEngine {

    val id: String
    val label: String
    val description: String

    /** Reuses [ImageGenerator.Availability]; see the note on [VisionEngine.availability]. */
    fun availability(): ImageGenerator.Availability

    /**
     * Transcribes 16-bit little-endian PCM.
     *
     * Takes raw samples rather than a file so a recorder can hand over what it has without touching
     * disk — a dictation of a few seconds is a few hundred kilobytes, and writing it out only to read
     * it back would be the slowest part of the operation.
     *
     * Blocking. [language] is an ISO-639-1 hint, or null to let the engine detect it.
     */
    fun transcribe(
        pcm16: ByteArray,
        sampleRate: Int,
        language: String? = null,
        onStage: ((String) -> Unit)? = null,
    ): String?
}

object Dictation {

    private const val TAG = "PrismDictation"

    /** What every engine here expects, and what a recorder should capture at. */
    const val SAMPLE_RATE = 16_000

    private val engines = LinkedHashMap<String, TranscriptionEngine>()

    fun register(engine: TranscriptionEngine) {
        engines[engine.id] = engine
    }

    /** For tests: the registry is process-wide. */
    fun reset() = engines.clear()

    fun all(): List<TranscriptionEngine> = engines.values.toList()

    fun available(): List<TranscriptionEngine> =
        engines.values.filter { it.availability() is ImageGenerator.Availability.Ready }

    fun preferred(): TranscriptionEngine? {
        val chosen = engines[PrismSettings.getDictationEngineId()]
        if (chosen != null && chosen.availability() is ImageGenerator.Availability.Ready) return chosen
        return available().firstOrNull()
    }

    fun unavailableReason(): String {
        if (engines.isEmpty()) return "No transcription engine is registered in this build."
        val reasons = engines.values.mapNotNull { engine ->
            (engine.availability() as? ImageGenerator.Availability.Unavailable)
                ?.let { "${engine.label}: ${it.reason}" }
        }
        return if (reasons.isEmpty()) "" else reasons.joinToString("\n")
    }

    data class Result(val text: String?, val engine: String, val error: String?, val millis: Long)

    /** Never throws; a failing engine comes back named. */
    fun transcribe(
        pcm16: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        language: String? = null,
        engineId: String? = null,
        onStage: ((String) -> Unit)? = null,
    ): Result {
        val engine = engineId?.let { engines[it] } ?: preferred()
        if (engine == null) {
            return Result(null, "none", unavailableReason().ifBlank { "No engine available." }, 0)
        }
        val availability = engine.availability()
        if (availability is ImageGenerator.Availability.Unavailable) {
            return Result(null, engine.label, availability.reason, 0)
        }
        // A hundred milliseconds of silence is a tap, not speech. Refused before it is uploaded and
        // billed, because every engine here answers an empty clip with an empty string and the user
        // would see dictation "working" and producing nothing.
        if (pcm16.size < sampleRate / 5 * 2) {
            return Result(null, engine.label, "That recording is too short to transcribe.", 0)
        }

        val started = System.currentTimeMillis()
        return runCatching {
            val text = engine.transcribe(pcm16, sampleRate, language, onStage)?.trim()
            Result(
                text = text?.takeIf { it.isNotEmpty() },
                engine = engine.label,
                error = if (text.isNullOrEmpty()) "${engine.label} heard nothing." else null,
                millis = System.currentTimeMillis() - started,
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "${engine.label} failed", it)
            Result(null, engine.label, it.message ?: it::class.simpleName.orEmpty(), 0)
        }
    }

    /**
     * Wraps PCM in a RIFF/WAVE header.
     *
     * Needed because the hosted endpoints identify the format from the container, not from a parameter
     * — raw PCM posted as `audio/wav` is rejected, and posted as `application/octet-stream` is
     * rejected differently. Forty-four bytes of header is the whole difference.
     *
     * Written by hand rather than through `javax.sound.sampled.AudioSystem`, which would be shorter
     * and does not exist on Android. This is the kind of thing that belongs in :core precisely because
     * both platforms need it and one of them has no library for it.
     */
    fun wrapAsWav(pcm16: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(pcm16.size + 44)
        val data = DataOutputStream(out)

        fun ascii(text: String) = data.write(text.toByteArray(Charsets.US_ASCII))
        // RIFF is little-endian and DataOutputStream is big-endian, so every multi-byte field is
        // written byte by byte. Using writeInt here produces a header that looks right in a hex dump
        // and is rejected by every decoder.
        fun le32(v: Int) {
            data.write(v and 0xFF); data.write((v ushr 8) and 0xFF)
            data.write((v ushr 16) and 0xFF); data.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            data.write(v and 0xFF); data.write((v ushr 8) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + pcm16.size)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                 // PCM fmt chunk size
        le16(1)                  // PCM, uncompressed
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(channels * bitsPerSample / 8)
        le16(bitsPerSample)
        ascii("data")
        le32(pcm16.size)
        data.write(pcm16)
        data.flush()
        return out.toByteArray()
    }

    /**
     * Peak amplitude, 0..1, for a level meter.
     *
     * A recorder that shows nothing while listening is indistinguishable from a broken microphone,
     * and a muted or unplugged input is the single most common cause of an empty transcript — so the
     * level is worth surfacing while recording rather than discovering afterwards.
     */
    fun peakLevel(pcm16: ByteArray): Float {
        if (pcm16.size < 2) return 0f
        var peak = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak / Short.MAX_VALUE.toFloat()
    }

    fun durationSeconds(pcm16: ByteArray, sampleRate: Int = SAMPLE_RATE): Float =
        pcm16.size / 2f / sampleRate
}

/**
 * Whisper on somebody else's machine.
 *
 * Posts a WAV to an OpenAI-compatible `audio/transcriptions` endpoint. Multipart, because that is what
 * the endpoint takes — this is the only request in Prism that is not JSON, which is why the body is
 * built by hand here rather than through [CloudAiService].
 */
class CloudTranscriptionEngine : TranscriptionEngine {

    override val id = "cloud"
    override val label = "Cloud (Whisper)"
    override val description =
        "Whisper on an OpenAI-compatible endpoint. Accurate, needs no download, works on every " +
            "platform — costs money, and the recording leaves this device."

    override fun availability(): ImageGenerator.Availability {
        val model = PrismSettings.getActiveCloudModel()
            ?: return ImageGenerator.Availability.Unavailable(
                "No cloud model is configured. Set one up on the Cloud AI page."
            )
        if (model.apiKey.isBlank()) {
            return ImageGenerator.Availability.Unavailable("That cloud model has no API key.")
        }
        return ImageGenerator.Availability.Ready
    }

    override fun transcribe(
        pcm16: ByteArray,
        sampleRate: Int,
        language: String?,
        onStage: ((String) -> Unit)?,
    ): String? {
        val profile = PrismSettings.getActiveCloudModel() ?: return null
        onStage?.invoke("Packing ${"%.1f".format(Dictation.durationSeconds(pcm16, sampleRate))}s of audio…")
        val wav = Dictation.wrapAsWav(pcm16, sampleRate)

        onStage?.invoke("Uploading…")
        val boundary = "----PrismDictation${System.nanoTime()}"
        val connection = (com.prism.core.Urls.of(profile.baseUrl.trimEnd('/') + "/audio/transcriptions").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            // Generous: a minute of audio on a slow uplink is a real case, and a timeout mid-upload
            // looks identical to the endpoint rejecting the request.
            connectTimeout = 20_000
            readTimeout = 180_000
        }

        return try {
            connection.outputStream.use { stream ->
                fun field(name: String, value: String) {
                    stream.write(
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                            "$value\r\n").toByteArray()
                    )
                }
                // The transcription model, not the chat model. A profile's modelId is a chat model and
                // sending it here is rejected, so the transcription model is named explicitly.
                field("model", TRANSCRIPTION_MODEL)
                language?.takeIf { it.isNotBlank() }?.let { field("language", it) }
                field("response_format", "json")

                stream.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                        "filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray()
                )
                stream.write(wav)
                stream.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                PrismPlatform.log.error("PrismDictation", "Transcription HTTP $code: $error")
                return null
            }
            com.prism.core.json.JSONObject(body).optString("text").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /**
         * Whisper's hosted name.
         *
         * Hardcoded rather than configurable because there is exactly one transcription model on an
         * OpenAI-compatible endpoint and a wrong value here fails with a model-not-found that reads as
         * a dictation bug. A provider that names theirs differently is a reason to make this a setting
         * then, not to guess now.
         */
        const val TRANSCRIPTION_MODEL = "whisper-1"
    }
}
