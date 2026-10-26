package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.FloatBuffer

/**
 * kNN-VC: rebuilds Kokoro's speech from the speaker's own recorded frames.
 *
 * WavLM (layer 6 of the first seven layers, 50 Hz) encodes both the source and the clone's
 * reference into sequences of 1024-value vectors. Each source frame is replaced by the
 * unweighted mean of its k=4 nearest reference frames (cosine), and HiFi-GAN vocodes the
 * matched sequence to 16 kHz audio. Matching IS the pitch transfer here -- the output follows
 * the reference's pitch and rhythm -- which is why this engine declares [preservesPitch] false
 * and [VoicePitch.match] leaves its input alone.
 *
 * The per-voice artifact is the reference's feature matrix (`<id>.knnfeat`: int32 frame count,
 * then frame-major LE floats), computed at [prepare] and reused for every utterance.
 *
 * ## Why these particular files
 *
 * The upstream export's int8 files are QOperator style -- `ConvInteger`/`MatMulInteger`, which
 * the shipped Android runtime (ORT 1.20) has no kernel for. These are weight-only int8 in QDQ
 * form (DequantizeLinear + float kernels, which it does have) re-quantized from the MIT fp32
 * export; verified on the production runtime: feature cosine 0.9994 vs fp32, end-to-end
 * spectral correlation 0.91, identical median F0.
 */
object KnnVcEngine : CloneEngine {

    override val id: String = "knnvc"
    override val label: String = "kNN-VC"
    override val summary: String = "Rebuilds speech from your frames · MIT · 16 kHz"
    override val preservesPitch: Boolean = false
    override val attribution: String? = "kNN-VC & WavLM (MIT) · weights from bshall/knn-vc"

    /** Fixed by the trained models: WavLM input and HiFi-GAN output are both 16 kHz. */
    const val RATE = 16_000

    private const val DIM = 1024
    private const val K = 4
    private const val CACHE = "knnfeat"
    /** Cap on the matching pool (and cache): 1000 frames = 20 s of reference at 50 Hz. */
    private const val MAX_FRAMES = 1000
    private const val MIN_SAMPLES = 1_600
    private const val TAG = "PrismSpeech"

    private const val RELEASE =
        "https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1"

    val FEATURIZER = VoiceCloningModels.Model(
        id = "knn-wavlm",
        label = "WavLM features",
        file = "wavlm_layer6_int8q.onnx",
        sizeBytes = 102_362_972L,
        url = "$RELEASE/wavlm_layer6_int8q.onnx",
        sha256 = "7ca5a3f44713a98c96621c1a707abd13d9563a7ee54d4a6047a00f5cedc924ae",
    )
    val VOCODER = VoiceCloningModels.Model(
        id = "knn-vocoder",
        label = "HiFi-GAN vocoder",
        file = "hifigan_knnvc_int8q.onnx",
        sizeBytes = 16_666_507L,
        url = "$RELEASE/hifigan_knnvc_int8q.onnx",
        sha256 = "60e33119548038b1862df7644f160a2c41af656cead0468c33c26e5cfb0567e0",
    )

    override val models: List<VoiceCloningModels.Model> = listOf(FEATURIZER, VOCODER)

    // -- Sessions -------------------------------------------------------------

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var featurizer: OrtSession? = null
    private var vocoder: OrtSession? = null

    override fun close() {
        synchronized(lock) {
            runCatching { featurizer?.close() }
            runCatching { vocoder?.close() }
            featurizer = null
            vocoder = null
            // The environment itself is a process-wide singleton and is deliberately kept.
        }
    }

    private fun openFeaturizer(): OrtSession = synchronized(lock) {
        featurizer?.let { return it }
        val session = open(VoiceCloningModels.fileFor(FEATURIZER))
        featurizer = session
        session
    }

    private fun openVocoder(): OrtSession = synchronized(lock) {
        vocoder?.let { return it }
        val session = open(VoiceCloningModels.fileFor(VOCODER))
        vocoder = session
        session
    }

    private fun open(file: File): OrtSession {
        if (!file.isFile) {
            throw IllegalStateException(
                "The voice conversion models are not downloaded -- open voice cloning and " +
                    "download the $label engine (${totalSizeLabel()})",
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

    // -- Engine contract ------------------------------------------------------

    override fun prepare(voiceId: String, reference: FloatArray, onStage: (String) -> Unit) {
        if (ClonedVoices.cachedFloats(voiceId, CACHE) != null) return
        onStage("Studying your voice…")
        val rows = runCatching { features(reference, VoiceCloner.WORK_RATE) }
            .getOrElse {
                PrismPlatform.log.error(TAG, "Could not analyse the voice sample", it)
                throw IllegalStateException("Could not read that voice: ${it.message}")
            }
        val kept = subsample(rows)
        val flat = FloatArray(kept.size * DIM)
        kept.forEachIndexed { i, row -> row.copyInto(flat, i * DIM) }
        ClonedVoices.writeCache(voiceId, CACHE, flat)
        PrismPlatform.log.info(TAG, "kNN-VC cached ${kept.size} reference frames for $voiceId")
    }

    override fun convert(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray?,
    ): CloneEngine.Converted {
        var pool = cachedPool(voiceId)
        if (pool == null) {
            if (reference == null) {
                throw IllegalStateException(
                    "This voice has no reference recording to convert toward -- " +
                        "record it again in Voice Cloning.",
                )
            }
            // Saved while another engine was active: compute and cache the features now, once.
            prepare(voiceId, reference)
            pool = cachedPool(voiceId)
                ?: throw IllegalStateException("The feature extractor produced no reference frames")
        }

        val src = features(source, sourceRate)
        val matched = meanTopK(src, pool)
        val audio = vocode(matched)
        return CloneEngine.Converted(audio, RATE)
    }

    private fun cachedPool(voiceId: String): Array<FloatArray>? {
        val flat = ClonedVoices.cachedFloats(voiceId, CACHE) ?: return null
        if (flat.isEmpty() || flat.size % DIM != 0) return null
        val frames = flat.size / DIM
        return Array(frames) { f -> flat.copyOfRange(f * DIM, (f + 1) * DIM) }
    }

    // -- The model pipeline ---------------------------------------------------

    /** WavLM features of [samples] (resampled to [RATE]): frames x 1024, 50 frames per second. */
    private fun features(samples: FloatArray, sampleRate: Int): Array<FloatArray> {
        if (samples.size < MIN_SAMPLES) {
            throw IllegalStateException("That audio is too short to analyse -- give it at least a second")
        }
        val mono = if (sampleRate == RATE) samples else VoicePitch.resample(samples, sampleRate, RATE)
        val session = openFeaturizer()
        val input = OnnxTensor.createTensor(
            environment!!,
            FloatBuffer.wrap(mono),
            longArrayOf(1, mono.size.toLong()),
        )
        return try {
            session.run(mapOf("input_values" to input)).use { output ->
                val value = output[0].value
                val batch = (value as? Array<*>)
                    ?: throw IllegalStateException("WavLM returned ${value?.javaClass}")
                val seq = batch.firstOrNull() as? Array<*>
                    ?: throw IllegalStateException("WavLM returned an empty result")
                Array(seq.size) { i ->
                    seq[i] as? FloatArray
                        ?: throw IllegalStateException("WavLM returned ${seq[i]?.javaClass}")
                }
            }
        } finally {
            input.close()
        }
    }

    /**
     * The kNN step: every source frame becomes the unweighted mean of its k nearest reference
     * frames. Similarity is cosine (unit-normalised dot); the averaged vectors themselves stay
     * raw, because the vocoder was trained on true feature values, not directions.
     */
    private fun meanTopK(src: Array<FloatArray>, pool: Array<FloatArray>): Array<FloatArray> {
        if (pool.isEmpty()) throw IllegalStateException("The reference produced no frames")
        val poolUnit = pool.map { unit(it) }
        return src.map { query ->
            val q = unit(query)
            // k best similarities, kept in a small insertion-ordered buffer.
            val bestSim = FloatArray(K) { -Float.MAX_VALUE }
            val bestIdx = IntArray(K) { -1 }
            for (j in poolUnit.indices) {
                var dot = 0f
                val pj = poolUnit[j]
                for (d in 0 until DIM) dot += q[d] * pj[d]
                if (dot <= bestSim[K - 1]) continue
                var slot = K - 1
                while (slot > 0 && dot > bestSim[slot - 1]) {
                    bestSim[slot] = bestSim[slot - 1]
                    bestIdx[slot] = bestIdx[slot - 1]
                    slot--
                }
                bestSim[slot] = dot
                bestIdx[slot] = j
            }
            val mean = FloatArray(DIM)
            for (slot in 0 until K) {
                val row = pool[bestIdx[slot]]
                for (d in 0 until DIM) mean[d] += row[d]
            }
            for (d in 0 until DIM) mean[d] /= K
            mean
        }.toTypedArray()
    }

    private fun unit(v: FloatArray): FloatArray {
        var sum = 0f
        for (d in 0 until DIM) sum += v[d] * v[d]
        val norm = kotlin.math.sqrt(sum)
        if (norm <= 1e-8f) return v
        val out = FloatArray(DIM)
        for (d in 0 until DIM) out[d] = v[d] / norm
        return out
    }

    /** HiFi-GAN: frames-major (T, 1024) in, 16 kHz mono out. */
    private fun vocode(frames: Array<FloatArray>): FloatArray {
        val t = frames.size
        // ONNX wants (1, 1024, T): dim-major, so transpose while flattening.
        val buffer = FloatArray(DIM * t)
        for (f in 0 until t) {
            val row = frames[f]
            for (d in 0 until DIM) buffer[d * t + f] = row[d]
        }
        val session = openVocoder()
        val input = OnnxTensor.createTensor(
            environment!!,
            FloatBuffer.wrap(buffer),
            longArrayOf(1, DIM.toLong(), t.toLong()),
        )
        return try {
            session.run(mapOf("features" to input)).use { output ->
                when (val value = output[0].value) {
                    is Array<*> -> flattenBatch(value)
                    is FloatArray -> value
                    else -> throw IllegalStateException("The vocoder returned ${value?.javaClass}")
                }
            }
        } finally {
            input.close()
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
            else -> throw IllegalStateException("The vocoder returned ${last?.javaClass}")
        }
    }

    /** Strides [rows] down to at most [MAX_FRAMES], keeping the sequence order. */
    private fun subsample(rows: Array<FloatArray>): Array<FloatArray> {
        if (rows.size <= MAX_FRAMES) return rows
        val step = (rows.size + MAX_FRAMES - 1) / MAX_FRAMES
        return rows.filterIndexed { i, _ -> i % step == 0 }.toTypedArray()
    }
}
