package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.FloatBuffer
import java.util.Random

/**
 * FreeVC: the whole voice conversion in ONE graph.
 *
 * WavLM features of the source and a fixed 8-second slice of the reference drive a VITS-style
 * synthesizer (posterior encoder, normalising flow, HiFi-GAN decoder) straight to 16 kHz audio.
 * Unlike kNN-VC there is no per-voice artifact -- [prepare] does nothing and [convert] reads the
 * reference recording itself -- and the generative prior keeps the source's pitch contour, so
 * [preservesPitch] is true and [VoicePitch.match] runs for it like it does for OpenVoice.
 *
 * The prior samples z = m + noise * exp(logs) during synthesis. The exported graph takes that
 * noise as its own input (a gaussian drawn here with a fixed seed), because a baked-in random
 * source could not be reproduced and torch-vs-ORT parity could never be checked. Same seed,
 * same sentence: bit-identical output.
 *
 * ## Why these particular files
 *
 * Weight-only int8 in QDQ form (DequantizeLinear + float kernels, which the shipped ORT 1.20
 * has) re-quantized from the MIT fp32 export of OlaWod/FreeVC; verified on the production
 * runtime: spectral correlation 0.99 and identical median F0 against the fp32 graph.
 */
object FreeVcEngine : CloneEngine {

    override val id: String = "freevc"
    override val label: String = "FreeVC"
    override val summary: String = "Speaker-matched conversion · one pass · MIT · 16 kHz"
    override val preservesPitch: Boolean = true
    override val attribution: String? = "FreeVC & WavLM (MIT) · weights from OlaWod/FreeVC"

    /** Fixed by the trained models: WavLM input, flow prior and decoder output are 16 kHz. */
    const val RATE = 16_000

    /** The reference slice the traced graph expects: 8 s = 128000 samples = 400 mel frames. */
    const val REF_SAMPLES = 128_000

    private const val NOISE_DIM = 192
    /** Below ~1 s there are too few content frames for the flow to synthesise anything. */
    private const val MIN_SAMPLES = 1_600
    private const val TAG = "PrismSpeech"

    private const val RELEASE =
        "https://github.com/ibrahim-hmood/Prism/releases/download/voice-engines-v1"

    val MODEL = VoiceCloningModels.Model(
        id = "freevc-vc",
        label = "FreeVC converter",
        file = "freevc_vc_int8q.onnx",
        sizeBytes = 358_618_472L,
        url = "$RELEASE/freevc_vc_int8q.onnx",
    )

    override val models: List<VoiceCloningModels.Model> = listOf(MODEL)

    // -- Sessions -------------------------------------------------------------

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    override fun close() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            // The environment itself is a process-wide singleton and is deliberately kept.
        }
    }

    private fun openSession(): OrtSession = synchronized(lock) {
        session?.let { return it }
        val file = VoiceCloningModels.fileFor(MODEL)
        if (!file.isFile) {
            throw IllegalStateException(
                "The voice conversion models are not downloaded -- open voice cloning and " +
                    "download the $label engine (${totalSizeLabel()})",
            )
        }
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val opened = runCatching {
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
        session = opened
        opened
    }

    // -- Engine contract ------------------------------------------------------

    override fun convert(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray?,
    ): CloneEngine.Converted {
        if (reference == null) {
            throw IllegalStateException(
                "This voice has no reference recording to convert toward -- " +
                    "record it again in Voice Cloning.",
            )
        }
        val mono = if (sourceRate == RATE) source else VoicePitch.resample(source, sourceRate, RATE)
        if (mono.size < MIN_SAMPLES) {
            throw IllegalStateException("That audio is too short to convert -- give it at least a second")
        }
        val ref = fixedReference(reference)
        // WavLM's conv stack turns Ts samples into ((Ts - 400) / 320) + 1 frames (verified
        // 48000 -> 149, 32000 -> 99); the prior's noise must have exactly that many.
        val frames = (mono.size - 400) / 320 + 1
        val noise = gaussian(voiceId, frames)
        return CloneEngine.Converted(run(mono, ref, noise), RATE)
    }

    /**
     * The reference at 16 kHz, padded with silence or cut to exactly [REF_SAMPLES] -- the traced
     * speaker encoder baked in its 8 s window, so the input has to match it frame for frame.
     */
    private fun fixedReference(reference: FloatArray): FloatArray {
        val mono = VoicePitch.resample(reference, VoiceCloner.WORK_RATE, RATE)
        val out = FloatArray(REF_SAMPLES)
        mono.copyInto(out, 0, 0, minOf(mono.size, REF_SAMPLES))
        return out
    }

    /** Seeded gaussian for the prior's z-sample: same voice, same sentence -> same result. */
    private fun gaussian(voiceId: String, frames: Int): FloatArray {
        val rng = Random(voiceId.hashCode().toLong() * 1_000_003L + frames)
        val out = FloatArray(NOISE_DIM * frames)
        for (i in out.indices) out[i] = rng.nextGaussian().toFloat()
        return out
    }

    // -- The model pipeline ---------------------------------------------------

    private fun run(source: FloatArray, ref: FloatArray, noise: FloatArray): FloatArray {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val s = openSession()
        val a = OnnxTensor.createTensor(env, FloatBuffer.wrap(source), longArrayOf(1, source.size.toLong()))
        val b = OnnxTensor.createTensor(env, FloatBuffer.wrap(ref), longArrayOf(1, REF_SAMPLES.toLong()))
        val c = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(noise), longArrayOf(1, NOISE_DIM.toLong(), (noise.size / NOISE_DIM).toLong()),
        )
        return try {
            s.run(mapOf("source" to a, "ref" to b, "noise" to c)).use { output ->
                when (val value = output[0].value) {
                    is Array<*> -> flattenBatch(value)
                    is FloatArray -> value
                    else -> throw IllegalStateException("FreeVC returned ${value?.javaClass}")
                }
            }
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    private fun flattenBatch(value: Array<*>): FloatArray {
        var inner = value
        while (inner.size == 1 && inner[0] is Array<*>) {
            @Suppress("UNCHECKED_CAST")
            inner = inner[0] as Array<*>
        }
        return when (val last = inner.firstOrNull()) {
            is FloatArray -> {
                val out = FloatArray(inner.size * last.size)
                for (i in inner.indices) {
                    val part = inner[i] as FloatArray
                    part.copyInto(out, i * last.size)
                }
                out
            }
            is DoubleArray -> {
                val out = FloatArray(inner.size * last.size)
                for (i in inner.indices) {
                    val part = inner[i] as DoubleArray
                    for (j in part.indices) out[i * last.size + j] = part[j].toFloat()
                }
                out
            }
            else -> throw IllegalStateException("FreeVC returned ${last?.javaClass}")
        }
    }
}
