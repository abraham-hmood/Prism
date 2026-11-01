package com.prism.launcher.videocraft

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

/**
 * Renders the music and ambience bed with a model, when one can render it.
 *
 * ## Why this needed a contract rather than a call
 *
 * Speech had an easy time of this: Kokoro is an ONNX file with a known interface, so "import a speech
 * model" has something concrete to import. Text-to-music has no such de-facto standard -- every
 * distribution exports a different graph with different inputs -- so the app used to say, correctly but
 * unhelpfully, that a selected audio model satisfied the gate and then synthesised the bed anyway with
 * [ScoreSynth]. That is the gap this closes, by defining the one interface Prism can actually feed and
 * using it when a model implements it:
 *
 *   - input  `input_ids`: int64 [1, N], **CLIP-vocabulary token ids**, so the tokenizer the CLIP tower
 *     already needs (`vocab.json` + `merges.txt` beside the model) is the same one used here;
 *   - input  `seconds` (optional): int64 [1], how much audio to produce;
 *   - output `audio`: float32 PCM at [VideoCraftAudio.SAMPLE_RATE], mono, in -1..1.
 *
 * A model whose graph does not match is reported, once, with the input names it actually has. That is
 * how a user finds out their export is a different family, instead of being told a model rendered a bed
 * that [ScoreSynth] wrote.
 *
 * ## The cloud route, which is the one most people will have
 *
 * A local music model is a large download; a cloud profile is a URL and a key, and the app already has
 * cloud profiles for text, images and interpolation. So the same `KIND_AUDIO` profile that used to only
 * satisfy the gate is now actually called, against the endpoint shape Prism documents and accepts either
 * a WAV or raw 16-bit PCM, base64 or binary. An endpoint that does not implement it is refused once and
 * the score falls back to the synth with the reason recorded.
 */
object VideoCraftAudioGen {

    /** What will render the bed, for the page's note. */
    fun describe(): String = when {
        cloudProfile() != null -> "cloud audio model (${cloudProfile()?.modelId})"
        localModel() != null -> localModel()!!.name + " (ONNX)"
        localSelected() -> "no audio model is installed · Prism Score"
        else -> "Prism Score (built-in)"
    }

    /**
     * [seconds] of music for [prompt], or null when nothing can render it.
     *
     * The caller ([VideoCraftAudioModel]) falls back to [ScoreSynth] on null, which is the honest
     * arrangement: a film always has a soundtrack, and a model that is selected but cannot answer is a
     * label, not a failure.
     */
    fun render(prompt: String, seconds: Float, seed: Long): FloatArray? {
        val samples = max(1, (seconds * VideoCraftAudio.SAMPLE_RATE).toInt())
        cloudProfile()?.let { profile ->
            cloudBed(profile, prompt, seconds, samples)?.let { return it }
        }
        localModel()?.let { file ->
            localBed(file, prompt, samples)?.let { return it }
        }
        return null
    }

    // ── Cloud ──────────────────────────────────────────────────────────────

    private fun cloudProfile() = PrismSettings.getActiveCloudModel(PrismSettings.KIND_AUDIO)

    @Volatile private var cloudRefused = false

    private fun cloudBed(
        profile: PrismSettings.CloudModelProfile,
        prompt: String,
        seconds: Float,
        samples: Int,
    ): FloatArray? {
        if (cloudRefused) return null
        val result = CloudAiService.fetchAudioBed(
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            prompt = prompt,
            modelId = profile.modelId,
            seconds = seconds,
            timeoutMs = 60_000,
        )
        val pcm = decodeAudio(result.pcm, result.wav, result.channels)
        if (pcm != null) {
            {
            }
            PrismPlatform.log.info(
                "VideoCraft",
                "Music bed rendered by the cloud audio model '${profile.modelId}'",
            )
            return fit(pcm, samples)
        }
        if (result.pcm != null || result.wav != null) {
            cloudRefused = true
            PrismPlatform.log.warn(
                "VideoCraft",
                "The cloud audio endpoint answered with something that is not PCM or WAV; " +
                    "Prism Score writes the rest of this film's soundtrack",
            )
            return null
        }
        if (!result.retryable) {
            // A 404, a rejected key, an endpoint that does not implement the route: none of those
            // change between two scenes, so the rest of the film does not keep asking.
            cloudRefused = true
            PrismPlatform.log.warn(
                "VideoCraft",
                "The cloud audio endpoint refused a music bed (${result.detail}); " +
                    "Prism Score writes the rest of this film's soundtrack",
            )
        } else {
            PrismPlatform.log.warn(
                "VideoCraft",
                "The cloud audio endpoint did not finish a music bed (${result.detail}); " +
                    "Prism Score writes this one",
            )
        }
        return null
    }

    /**
     * Raw or WAV audio as mono float samples.
     *
     * Two shapes are accepted because both are what an endpoint returns in practice: raw little-endian
     * 16-bit PCM, and a WAV file (RIFF) whose header has to be parsed anyway. A stereo bed is averaged
     * down, because a film's soundtrack is mono here -- the muxer writes one channel.
     */
    fun decodeAudio(pcm: ByteArray?, wav: ByteArray?, channels: Int): FloatArray? = runCatching {
        when {
            wav != null -> wav(wav)
            pcm != null -> toFloat(pcm, max(1, channels))
            else -> null
        }
    }.getOrNull()

    private fun toFloat(pcm: ByteArray, channels: Int): FloatArray {
        val samples = pcm.size / 2 / channels
        val out = FloatArray(samples)
        var at = 0
        for (i in 0 until samples) {
            var sum = 0.0
            for (c in 0 until channels) {
                val lo = pcm[at++].toInt() and 0xFF
                val hi = pcm[at++].toInt()
                sum += ((hi shl 8) or lo).toShort() / 32768.0
            }
            out[i] = (sum / channels).toFloat()
        }
        return out
    }

    /** 16-bit PCM out of a WAV, whatever its channel count or sample rate. */
    private fun wav(bytes: ByteArray): FloatArray? {
        if (bytes.size < 44) return null
        var at = 12
        var channels = 1
        var sampleRate = VideoCraftAudio.SAMPLE_RATE
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = (bytes[at + 4].toInt() and 0xFF) or
                ((bytes[at + 5].toInt() and 0xFF) shl 8) or
                ((bytes[at + 6].toInt() and 0xFF) shl 16) or
                ((bytes[at + 7].toInt() and 0xFF) shl 24)
            val start = at + 8
            when (id) {
                "fmt " -> {
                    if (size >= 8) {
                        channels = ((bytes[start + 2].toInt() and 0xFF) or
                            ((bytes[start + 3].toInt() and 0xFF) shl 8)).coerceAtLeast(1)
                        sampleRate = (bytes[start + 4].toInt() and 0xFF) or
                            ((bytes[start + 5].toInt() and 0xFF) shl 8) or
                            ((bytes[start + 6].toInt() and 0xFF) shl 16) or
                            ((bytes[start + 7].toInt() and 0xFF) shl 24)
                    }
                }
                "data" -> {
                    val end = minOf(bytes.size, start + max(0, size))
                    val samples = toFloat(bytes.copyOfRange(start, end), channels)
                    return if (sampleRate == VideoCraftAudio.SAMPLE_RATE) {
                        samples
                    } else VideoCraftAudio.resample(samples, sampleRate, VideoCraftAudio.SAMPLE_RATE)
                }
            }
            at = start + size + (size and 1)
        }
        return null
    }

    private fun fit(samples: FloatArray, wanted: Int): FloatArray {
        if (samples.size == wanted) return samples
        if (samples.size > wanted) return samples.copyOf(wanted)
        val out = FloatArray(wanted)
        System.arraycopy(samples, 0, out, 0, samples.size)
        // The tail is not silence: a bed that stops early is looped once rather than left to thump.
        var at = samples.size
        var from = 0
        while (at < wanted && samples.isNotEmpty()) {
            out[at++] = samples[from++ % samples.size]
        }
        return out
    }

    // ── Local ONNX ─────────────────────────────────────────────────────────

    private fun localSelected(): Boolean = PrismSettings.getLocalAudioGenModelPath().isNotEmpty()

    private fun localModel(): File? {
        val path = PrismSettings.getLocalAudioGenModelPath()
        if (path.isEmpty() || path == PrismSettings.CLOUD_MODEL_SENTINEL) return null
        val file = File(path)
        return file.takeIf { it.isFile && it.extension.equals("onnx", true) }
    }

    @Volatile private var localReported = false

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private fun localBed(file: File, prompt: String, samples: Int): FloatArray? {
        val tokens = com.prism.launcher.videocraft.VideoCraftClipTower.locate()?.let { located ->
            runCatching {
                VideoCraftClipTower.ClipTokenizer(located.vocab, located.merges).encode(prompt)
            }.getOrNull()
        }
        if (tokens == null) {
            if (!localReported) {
                localReported = true
                PrismPlatform.log.warn(
                    "VideoCraft",
                    "${file.name} takes CLIP token ids, so it needs a vocab.json and merges.txt " +
                        "beside it; Prism Score writes this film's bed",
                )
            }
            return null
        }
        return runCatching {
            val session = environment.createSession(
                file.absolutePath,
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                },
            )
            session.use { active ->
                val names = active.inputNames.toList()
                val idName = names.firstOrNull { it.contains("input_ids", true) || it.contains("token", true) }
                if (idName == null) {
                    if (!localReported) {
                        localReported = true
                        PrismPlatform.log.warn(
                            "VideoCraft",
                            "${file.name} has no token-id input (it has ${names.joinToString()}), so it " +
                                "is not a text-to-music export Prism can call",
                        )
                    }
                    return@use null
                }
                val inputs = LinkedHashMap<String, OnnxTensor>()
                val seconds = samples.toDouble() / VideoCraftAudio.SAMPLE_RATE
                inputs[idName] = OnnxTensor.createTensor(
                    environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()),
                )
                names.firstOrNull { it.contains("seconds", true) || it.contains("length", true) }
                    ?.let { lengthName ->
                        inputs[lengthName] = OnnxTensor.createTensor(
                            environment, LongBuffer.wrap(longArrayOf(max(1, seconds.toLong()))),
                            longArrayOf(1),
                        )
                    }
                val result = active.run(inputs)
                inputs.values.forEach { runCatching { it.close() } }
                result.use { output ->
                    val tensor = output[0] as? OnnxTensor
                        ?: throw IllegalStateException("the model returned no audio tensor")
                    val buffer = tensor.floatBuffer
                    val pcm = FloatArray(buffer.remaining())
                    buffer.get(pcm)
                    fit(pcm, samples)
                }
            }
        }.getOrElse {
            PrismPlatform.log.warn(
                "VideoCraft",
                "Could not render the music bed with ${file.name}: ${it.message}",
            )
            null
        }
    }
}

