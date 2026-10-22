package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenVoice v2 on the device: turns Kokoro's speech into the recorded speaker's voice.
 *
 * ## The pipeline this completes
 *
 * Kokoro cannot be cloned into -- no published operation maps audio to its style tensor -- but it
 * does not need to be. Kokoro speaks the words with a published voice at 24 kHz; [convert] then
 * re-voices that audio with a second model, carrying the target speaker's timbre across while the
 * words, pacing and prosody stay Kokoro's. The result is 22.05 kHz mono that sounds like whoever
 * was recorded, which is what "clone my voice" has to mean for this model.
 *
 * Two float32 ONNX graphs, both MIT, both fetched by [VoiceCloningModels]:
 *
 * 1. The tone encoder reads a magnitude spectrogram of the reference speaker -- one 256-value
 *    direction per utterance. The direction is what gets STORED at clone time; the reference audio
 *    itself is never needed again.
 * 2. The tone converter takes the source utterance's spectrogram plus the source's own tone
 *    direction and the target's, and emits the re-voiced waveform.
 *
 * ## The spectrogram, exactly
 *
 * The graphs were trained against VITS-style features and are intolerant of a mismatch: linear
 * magnitude, no log compression, Hann window 1024, hop 256, 384 samples of reflect padding on each
 * side (so the first frame is centred on sample 0, as `torch.stft(center=True)` would do). The
 * encoder wants frames-major `(1, T, 513)`; the converter wants channels-major `(1, 513, T)` of the
 * same values. Verified against the published exports: identity conversion (same tone direction in
 * and out) reconstructs the source, and two different speakers produce clearly different vectors.
 */
object VoiceConverter {

    /** The converter's -- and so this file's -- sample rate. Fixed by the trained model. */
    const val RATE = 22_050

    private const val N_FFT = 1024
    private const val HOP = 256
    private const val PAD = (N_FFT - HOP) / 2
    private const val BINS = N_FFT / 2 + 1

    private const val TAG = "PrismSpeech"

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var converter: OrtSession? = null

    fun isInstalled(): Boolean = OpenVoiceEngine.isInstalled()

    /** Closes both sessions so a re-downloaded model is picked up. Called after any install. */
    fun close() {
        synchronized(lock) {
            runCatching { encoder?.close() }
            runCatching { converter?.close() }
            encoder = null
            converter = null
            // The environment itself is a process-wide singleton and is deliberately kept.
        }
    }

    /**
     * The 256-value tone direction of [samples], resampled from [sampleRate] to [RATE] as needed.
     *
     * This is what a clone stores. Blocking ONNX work -- callers run it off the UI thread.
     */
    fun embedding(samples: FloatArray, sampleRate: Int): FloatArray {
        require(samples.isNotEmpty()) { "There is no audio to encode" }
        val mono = if (sampleRate == RATE) samples else resample(samples, sampleRate, RATE)
        val spec = spectrogram(mono)
        val session = openEncoder()

        val specTensor = OnnxTensor.createTensor(
            environment!!,
            FloatBuffer.wrap(spec),
            longArrayOf(1, spec.size.toLong() / BINS, BINS.toLong()),
        )
        return try {
            session.run(mapOf("spec" to specTensor)).use { output ->
                when (val value = output[0].value) {
                    is Array<*> -> {
                        value.filterIsInstance<FloatArray>().firstOrNull()
                            ?.copyOf(256)
                            ?: throw IllegalStateException("The speaker encoder returned no embedding")
                    }
                    is FloatArray -> value.copyOf(256)
                    else -> throw IllegalStateException("The speaker encoder returned ${value?.javaClass}")
                }
            }
        } finally {
            specTensor.close()
        }
    }

    /**
     * Re-voices [source] (at [sourceRate]) to sound like the speaker [targetTone] describes.
     *
     * [sourceTone] is the direction of the source audio itself -- the converter needs both ends of
     * the mapping. Returns audio at [RATE]; play it at that rate.
     */
    fun convert(
        source: FloatArray,
        sourceRate: Int,
        sourceTone: FloatArray,
        targetTone: FloatArray,
    ): FloatArray {
        require(sourceTone.size == 256 && targetTone.size == 256) { "A tone direction is 256 values" }
        val mono = if (sourceRate == RATE) source else resample(source, sourceRate, RATE)
        val frames = (mono.size + 2 * PAD - N_FFT) / HOP + 1
        require(frames > 0) { "That audio is too short to convert" }
        val spec = spectrogram(mono)
        val session = openConverter()

        val specTensor = OnnxTensor.createTensor(
            environment!!,
            FloatBuffer.wrap(transpose(spec, frames)),
            longArrayOf(1, BINS.toLong(), frames.toLong()),
        )
        val lengthsTensor = OnnxTensor.createTensor(
            environment!!, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1),
        )
        val sourceTensor = OnnxTensor.createTensor(
            environment!!, FloatBuffer.wrap(sourceTone), longArrayOf(1, 256, 1),
        )
        val targetTensor = OnnxTensor.createTensor(
            environment!!, FloatBuffer.wrap(targetTone), longArrayOf(1, 256, 1),
        )
        return try {
            session.run(
                mapOf(
                    "spec" to specTensor,
                    "spec_lengths" to lengthsTensor,
                    "src_g" to sourceTensor,
                    "tgt_g" to targetTensor,
                ),
            ).use { output ->
                when (val value = output[0].value) {
                    is Array<*> -> flattenBatch(value)
                    is FloatArray -> value
                    else -> throw IllegalStateException("The voice converter returned ${value?.javaClass}")
                }
            }
        } finally {
            specTensor.close()
            lengthsTensor.close()
            sourceTensor.close()
            targetTensor.close()
        }
    }

    // -- Sessions -------------------------------------------------------------

    private fun openEncoder(): OrtSession = synchronized(lock) {
        encoder?.let { return it }
        val session = open(VoiceCloningModels.fileFor(OpenVoiceEngine.ENCODER))
        encoder = session
        session
    }

    private fun openConverter(): OrtSession = synchronized(lock) {
        converter?.let { return it }
        val session = open(VoiceCloningModels.fileFor(OpenVoiceEngine.CONVERTER))
        converter = session
        session
    }

    private fun open(file: java.io.File): OrtSession {
        if (!file.isFile) {
            throw IllegalStateException(
                "The voice conversion models are not downloaded -- open voice cloning and " +
                    "download the ${OpenVoiceEngine.label} engine (${OpenVoiceEngine.totalSizeLabel()})",
            )
        }
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        return runCatching {
            env.createSession(
                file.absolutePath,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                },
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not open ${file.name}", it)
            throw IllegalStateException("The ${file.nameWithoutExtension} model would not open: ${it.message}")
        }
    }

    // -- Spectrogram ----------------------------------------------------------

    /**
     * Linear magnitude spectrogram of [samples]: frames-major, `frames * 513` floats.
     *
     * Reflect padding needs at least PAD + 1 samples; anything shorter is padded with what it has,
     * which only a sub-50 ms clip can reach and which no caller should produce.
     */
    private fun spectrogram(samples: FloatArray): FloatArray {
        val padded = FloatArray(samples.size + 2 * PAD)
        for (i in samples.indices) padded[PAD + i] = samples[i]
        for (i in 0 until PAD) {
            padded[PAD - 1 - i] = samples.getOrNull(i + 1) ?: samples.firstOrNull() ?: 0f
            padded[PAD + samples.size + i] = samples.getOrNull(samples.size - 2 - i) ?: samples.lastOrNull() ?: 0f
        }

        val frames = (padded.size - N_FFT) / HOP + 1
        val out = FloatArray(frames * BINS)
        val window = FloatArray(N_FFT) { n ->
            // Periodic Hann, matching torch.hann_window(N) -- not the symmetric one.
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / N_FFT)).toFloat()
        }
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)

        for (f in 0 until frames) {
            val at = f * HOP
            for (i in 0 until N_FFT) {
                re[i] = padded[at + i] * window[i]
                im[i] = 0f
            }
            fft(re, im)
            val row = f * BINS
            for (k in 0 until BINS) {
                out[row + k] = sqrt(re[k] * re[k] + im[k] * im[k] + 1e-6f)
            }
        }
        return out
    }

    /** Frames-major `(T, 513)` to channels-major `(513, T)`, which the converter reads. */
    private fun transpose(spec: FloatArray, frames: Int): FloatArray {
        val out = FloatArray(spec.size)
        for (f in 0 until frames) {
            for (k in 0 until BINS) out[k * frames + f] = spec[f * BINS + k]
        }
        return out
    }

    private fun flattenBatch(value: Array<*>): FloatArray {
        val rows = value.filterIsInstance<Array<*>>()
        val first = rows.firstOrNull() ?: value.filterIsInstance<FloatArray>().firstOrNull()
        return when (first) {
            is FloatArray -> first
            is Array<*> -> first.filterIsInstance<FloatArray>().firstOrNull()
                ?: throw IllegalStateException("The voice converter returned no audio")
            else -> throw IllegalStateException("The voice converter returned ${value.javaClass}")
        }
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from <= 0 || to <= 0 || from == to || input.isEmpty()) return input
        val length = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(length)
        val step = from.toDouble() / to
        for (i in 0 until length) {
            val position = i * step
            val at = position.toInt().coerceIn(0, input.lastIndex)
            val next = min(at + 1, input.lastIndex)
            val fraction = (position - at).toFloat()
            out[i] = input[at] * (1f - fraction) + input[next] * fraction
        }
        return out
    }

    /** In-place iterative radix-2 FFT over the real (cosine) half-spectrum. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var swap = re[i]; re[i] = re[j]; re[j] = swap
                swap = im[i]; im[i] = im[j]; im[j] = swap
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val stepR = cos(angle).toFloat()
            val stepI = kotlin.math.sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var currentR = 1f
                var currentI = 0f
                for (k in 0 until length / 2) {
                    val left = start + k
                    val right = left + length / 2
                    val productR = re[right] * currentR - im[right] * currentI
                    val productI = re[right] * currentI + im[right] * currentR
                    re[right] = re[left] - productR
                    im[right] = im[left] - productI
                    re[left] += productR
                    im[left] += productI
                    val nextR = currentR * stepR - currentI * stepI
                    currentI = currentR * stepI + currentI * stepR
                    currentR = nextR
                }
                start += length
            }
            length = length shl 1
        }
    }
}
