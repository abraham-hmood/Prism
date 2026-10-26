package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/**
 * Seed-VC: diffusion re-voicing of Kokoro's speech toward the recorded speaker.
 *
 * Six graphs run in sequence per utterance. The mel helper measures frame counts and reads the
 * reference's prompt mel; XLS-R (50 Hz content, 16 kHz in) encodes source and reference; CAMPPlus
 * folds the reference into one speaker vector; the length regulator stretches both content
 * sequences to mel length; a DiT estimator denoises a mel-shaped noise tensor in 8 Euler steps
 * with classifier-free guidance against a zeroed conditioning row; HiFT vocodes the generated
 * frames straight to 22.05 kHz audio. There is no per-voice artifact -- [prepare] does nothing
 * and [convert] reads the reference recording itself, like FreeVC.
 *
 * ## Pitch comes from the reference
 *
 * Sources of 127/140/175 Hz all converge on the reference's own pitch, so this engine rebuilds
 * pitch rather than carrying the source's: [preservesPitch] is false and [VoicePitch.match]
 * leaves its input alone, exactly like kNN-VC. Moving Kokoro's base pitch to the measured speaker
 * pitch would fight the model instead of helping it.
 *
 * ## Stochastic, but reproducibly so
 *
 * The prior samples noise and the vocoder wants phase offsets and excitation noise; the euler
 * loop is host code (never exported) precisely so none of that is baked into a graph. The three
 * draws come from one [Random] seeded by voice and length in the reference pipeline's order --
 * same voice, same sentence, same result.
 *
 * ## Why these particular files
 *
 * The upstream (Plachtaa/seed-vc) is GPL-3.0: the row carries [attribution] rather than hiding
 * it. The five conversion graphs are weight-only int8 in QDQ form (DequantizeLinear + float
 * kernels, which the shipped ORT 1.20 has) re-quantized from the fp32 export; the mel helper
 * stays fp32 -- its DFT and filterbank matrices are constants whose quantizing only costs
 * accuracy for no size that matters. Verified on the production runtime: end-to-end mel-envelope
 * correlation 0.99 and F0 within 5 Hz of the fp32 pipeline.
 */
object SeedVcEngine : CloneEngine {

    override val id: String = "seedvc"
    override val label: String = "Seed-VC"
    override val summary: String = "Speaker-matched conversion · diffusion · GPL-3.0 · 22 kHz"
    override val preservesPitch: Boolean = false
    override val attribution: String? = "Seed-VC (GPL-3.0) · weights from Plachtaa/seed-vc"

    /** Fixed by the trained models: mel frames, content staging and vocoder output are 22.05 kHz. */
    const val RATE = 22_050

    /** Mel hop: n samples measure floor(n / [HOP]) frames, verified against the torch pipeline. */
    private const val HOP = 256
    private const val MELS = 80
    private const val CONTENT_DIM = 1024
    private const val STYLE_DIM = 192
    private const val HIDDEN = 384
    /** Vocoder excitation channels: 9 harmonics of the source signal. */
    private const val HARMONICS = 9
    private const val N_STEPS = 8
    private const val CFG_RATE = 0.7f
    /** The reference window the export was validated on: 15 s at [RATE]. */
    private const val REF_MAX = 330_750
    /** Below ~70 ms there are too few mel/content frames for the pipeline to start. */
    private const val MIN_SAMPLES = 1_600
    private const val TAG = "PrismSpeech"

    private const val RELEASE =
        "https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1"

    val CONTENT = VoiceCloningModels.Model(
        id = "seed-content",
        label = "Seed-VC content",
        file = "xlsr_content_int8q.onnx",
        sizeBytes = 203_349_186L,
        url = "$RELEASE/xlsr_content_int8q.onnx",
        sha256 = "d0b02f1204115ed251bc47c4a813f21b7a575e18ed93d981df10f539bf00816a",
    )
    val STYLE = VoiceCloningModels.Model(
        id = "seed-style",
        label = "Seed-VC speaker",
        file = "campplus_style_int8q.onnx",
        sizeBytes = 9_553_353L,
        url = "$RELEASE/campplus_style_int8q.onnx",
        sha256 = "243fe765e0360f8d5b52c4171b8099c8185a53b9581d2edc28b887cb19432b3f",
    )
    val REGULATOR = VoiceCloningModels.Model(
        id = "seed-lenreg",
        label = "Seed-VC aligner",
        file = "lenreg_int8q.onnx",
        sizeBytes = 2_423_168L,
        url = "$RELEASE/lenreg_int8q.onnx",
        sha256 = "02b377108ad5ba56a7adbef00ea88f82dbd73e71343c817c895a4725de2f1aea",
    )
    val DIT = VoiceCloningModels.Model(
        id = "seed-dit",
        label = "Seed-VC diffusion",
        file = "dit_step_int8q.onnx",
        sizeBytes = 23_280_117L,
        url = "$RELEASE/dit_step_int8q.onnx",
        sha256 = "3eb4b99aaea5349218016e500d1df42dcb62302b0e39d307c0c764d3aba8a3b7",
    )
    val VOCODER = VoiceCloningModels.Model(
        id = "seed-hift",
        label = "Seed-VC vocoder",
        file = "hift_vocoder_int8q.onnx",
        sizeBytes = 20_796_087L,
        url = "$RELEASE/hift_vocoder_int8q.onnx",
        sha256 = "42e4eb8ff3e09de16172c3b0fb649fc045436fc83c1200283e59d421c7e2ac93",
    )
    val MEL = VoiceCloningModels.Model(
        id = "seed-mel",
        label = "Seed-VC mel helper",
        file = "mel_w.onnx",
        sizeBytes = 8_567_808L,
        url = "$RELEASE/mel_w.onnx",
        sha256 = "e0baa241ff6e9657499ad47b5a064e52a50b902c803e6c31226439c6f9f9e061",
    )

    override val models: List<VoiceCloningModels.Model> =
        listOf(CONTENT, STYLE, REGULATOR, DIT, VOCODER, MEL)

    // -- Sessions -------------------------------------------------------------

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private val sessions = mutableMapOf<String, OrtSession>()

    override fun close() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
            preparedRefs.clear()
            // The environment itself is a process-wide singleton and is deliberately kept.
        }
    }

    private fun logTiming(message: String) {
        PrismPlatform.log.info(TAG, "Seed-VC $message")
    }

    private fun sessionFor(model: VoiceCloningModels.Model): OrtSession = synchronized(lock) {
        sessions[model.id]?.let { return it }
        val file = VoiceCloningModels.fileFor(model)
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
        sessions[model.id] = opened
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
        val started = System.nanoTime()
        val mono = if (sourceRate == RATE) source else VoicePitch.resample(source, sourceRate, RATE)
        if (mono.size < MIN_SAMPLES) {
            throw IllegalStateException("That audio is too short to convert -- give it at least a second")
        }
        val ref = FloatArray(minOf(reference.size, REF_MAX))
        reference.copyInto(ref, 0, 0, ref.size)
        val tSrc = mono.size / HOP
        val tRef = ref.size / HOP
        if (tSrc < 1 || tRef < 1) {
            throw IllegalStateException("That audio is too short to convert -- give it at least a second")
        }
        val samples = run(mono, ref, tSrc, tRef, voiceId)
        logTiming(
            "converted $tSrc frames toward ${voiceId.take(12)} (${tRef}f ref) in " +
                "%.2fs".format((System.nanoTime() - started) / 1e9)
        )
        return CloneEngine.Converted(samples, RATE)
    }

    // -- The model pipeline ---------------------------------------------------

    /** What encodes once per voice and never again: reference mel, speaker vector, stretched content. */
    private class PreparedRef(
        val prompt: FloatArray,
        val style: FloatArray,
        val pcond: FloatArray,
    )

    /**
     * The reference side of the pipeline re-encoded ONCE per voice instead of once per utterance.
     *
     * A conversion re-reads the reference recording every time it runs; without the cache every
     * spoken sentence re-runs the mel helper, CAMPPlus, XLS-R on the reference and the length
     * regulator -- close to half the wall time of a conversion, over and over. Keyed by voice and
     * reference length (a re-record has to change the length), evicting beyond a handful of voices.
     * Synchronized rather than racy: chunked playback pipelines one utterance's render against
     * another's playback, so conversions run concurrently on one voice.
     */
    private val preparedRefs =
        LinkedHashMap<Pair<String, Int>, PreparedRef>(4, 0.75f, true)

    private fun preparedFor(
        env: OrtEnvironment,
        voiceId: String,
        ref: FloatArray,
        tRef: Int,
    ): PreparedRef = synchronized(lock) {
        preparedRefs[voiceId to ref.size]?.let { return it }
        val ref16 = VoicePitch.resample(ref, RATE, 16_000)
        val prompt = runFlat(
            sessionFor(MEL),
            "wave" to tensor(env, ref, 1, ref.size.toLong()),
        )
        if (prompt.size != MELS * tRef) {
            throw IllegalStateException("The mel helper measured ${prompt.size} values, expected ${MELS * tRef}")
        }
        val style = runFlat(
            sessionFor(STYLE),
            "wave" to tensor(env, ref16, 1, ref16.size.toLong()),
        )
        if (style.size != STYLE_DIM) {
            throw IllegalStateException("The speaker encoder returned ${style.size} values")
        }
        val pcond = regulate(env, content(env, ref16), tRef)
        if (preparedRefs.size > 4) preparedRefs.remove(preparedRefs.keys.first())
        preparedRefs[voiceId to ref.size] = PreparedRef(prompt, style, pcond)
        logTiming("prepared reference for ${voiceId.take(12)} (${tRef}f, cached)")
        PreparedRef(prompt, style, pcond)
    }

    private fun run(mono: FloatArray, ref: FloatArray, tSrc: Int, tRef: Int, voiceId: String): FloatArray {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val src16 = VoicePitch.resample(mono, RATE, 16_000)
        val prepared = preparedFor(env, voiceId, ref, tRef)

        val cond = regulate(env, content(env, src16), tSrc)

        // mu = the reference's stretched content in front of the source's, (T, HIDDEN) row-major.
        val mu = FloatArray((tRef + tSrc) * HIDDEN)
        prepared.pcond.copyInto(mu, 0)
        cond.copyInto(mu, prepared.pcond.size)

        // Drawn in the reference pipeline's order: prior noise, vocoder phase, excitation noise.
        val rng = Random(voiceId.hashCode().toLong() * 1_000_003L + (tRef + tSrc))
        val z = FloatArray(MELS * (tRef + tSrc)) { rng.nextGaussian().toFloat() }
        val phase = FloatArray(HARMONICS)
        for (i in 1 until HARMONICS) {
            phase[i] = rng.nextFloat() * (2f * Math.PI.toFloat()) - Math.PI.toFloat()
        }
        val noise = FloatArray(HARMONICS * tSrc * HOP) { rng.nextGaussian().toFloat() }

        val stageStart = System.nanoTime()
        val vc = diffuse(env, prepared.prompt, tRef, mu, prepared.style, z)
        logTiming("diffuse %.2fs".format((System.nanoTime() - stageStart) / 1e9))

        // The vocoder reads only the generated frames, never the zeroed prompt region.
        val gen = FloatArray(MELS * tSrc)
        for (m in 0 until MELS) {
            vc.copyInto(gen, m * tSrc, m * (tRef + tSrc) + tRef, (m + 1) * (tRef + tSrc))
        }
        val vocodeStart = System.nanoTime()
        val wave = hift(env, gen, tSrc, phase, noise)
        logTiming("vocode %.2fs".format((System.nanoTime() - vocodeStart) / 1e9))
        return wave
    }

    /** XLS-R content features of [wave16]: (1, frames, 1024) flattened, ~50 frames per second. */
    private fun content(env: OrtEnvironment, wave16: FloatArray): FloatArray {
        val flat = runFlat(
            sessionFor(CONTENT),
            "wave" to tensor(env, wave16, 1, wave16.size.toLong()),
        )
        if (flat.size % CONTENT_DIM != 0) {
            throw IllegalStateException("The content encoder returned ${flat.size} values")
        }
        return flat
    }

    /** The length regulator: stretch (frames, 1024) content to exactly [target] mel frames. */
    private fun regulate(env: OrtEnvironment, content: FloatArray, target: Int): FloatArray {
        val frames = content.size / CONTENT_DIM
        val flat = runFlat(
            sessionFor(REGULATOR),
            "S" to tensor(env, content, 1, frames.toLong(), CONTENT_DIM.toLong()),
            "ylens" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(target.toLong())), longArrayOf(1)),
        )
        if (flat.size != target * HIDDEN) {
            throw IllegalStateException("The aligner returned ${flat.size} values, expected ${target * HIDDEN}")
        }
        return flat
    }

    /**
     * 8 Euler steps of classifier-free guidance over [z]: the estimate for the real conditioning
     * (prompt, speaker, content) minus the one for a zeroed copy, weighted by [CFG_RATE]. The
     * prompt region of the mel is re-zeroed after every step -- it is the reference's own mel,
     * never generated.
     */
    private fun diffuse(
        env: OrtEnvironment,
        prompt: FloatArray,
        plen: Int,
        mu: FloatArray,
        style: FloatArray,
        z: FloatArray,
    ): FloatArray {
        val frames = mu.size / HIDDEN
        val mel = MELS * frames
        val x = z.copyOf()
        for (m in 0 until MELS) x.fill(0f, m * frames, m * frames + plen)

        val xBatch = FloatArray(2 * mel)
        // prompt is (MELS, plen) but the batch row is (MELS, frames) -- copy per mel row, or the
        // reference conditioning lands at the wrong offsets and the vocoder gets clicks.
        val pBatch = FloatArray(2 * mel)
        for (m in 0 until MELS) prompt.copyInto(pBatch, m * frames, m * plen, (m + 1) * plen)
        val muBatch = FloatArray(2 * mu.size).also { mu.copyInto(it) }
        val styleBatch = FloatArray(2 * STYLE_DIM).also { style.copyInto(it) }
        var time = 0f
        val dt = 1f / N_STEPS
        repeat(N_STEPS) {
            x.copyInto(xBatch, 0)
            x.copyInto(xBatch, mel)
            val dphi = runFlat(
                sessionFor(DIT),
                "x" to tensor(env, xBatch, 2, MELS.toLong(), frames.toLong()),
                "prompt_x" to tensor(env, pBatch, 2, MELS.toLong(), frames.toLong()),
                "x_lens" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)),
                "t" to tensor(env, floatArrayOf(time, time), 2),
                "style" to tensor(env, styleBatch, 2, STYLE_DIM.toLong()),
                "cond" to tensor(env, muBatch, 2, frames.toLong(), HIDDEN.toLong()),
            )
            if (dphi.size != 2 * mel) {
                throw IllegalStateException("The diffusion estimator returned ${dphi.size} values")
            }
            for (i in 0 until mel) {
                x[i] += dt * ((1f + CFG_RATE) * dphi[i] - CFG_RATE * dphi[mel + i])
            }
            time += dt
            for (m in 0 until MELS) x.fill(0f, m * frames, m * frames + plen)
        }
        return x
    }

    /** HiFT: (1, MELS, tSrc) generated mel plus phase and excitation noise to 22.05 kHz audio. */
    private fun hift(
        env: OrtEnvironment,
        gen: FloatArray,
        tSrc: Int,
        phase: FloatArray,
        noise: FloatArray,
    ): FloatArray {
        val wave = runFlat(
            sessionFor(VOCODER),
            "mel" to tensor(env, gen, 1, MELS.toLong(), tSrc.toLong()),
            "phase" to tensor(env, phase, 1, HARMONICS.toLong(), 1),
            "noise" to tensor(env, noise, 1, HARMONICS.toLong(), noise.size.toLong() / HARMONICS),
        )
        if (wave.isEmpty()) throw IllegalStateException("The vocoder produced no audio")
        return wave
    }

    // -- ONNX plumbing --------------------------------------------------------

    private fun tensor(env: OrtEnvironment, data: FloatArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    /**
     * Runs one graph, flattens its single output row-major -- (1, a, b) becomes a * b -- and
     * closes the [inputs] whatever happens: they are transient, never shared between runs.
     */
    private fun runFlat(session: OrtSession, vararg inputs: Pair<String, OnnxTensor>): FloatArray =
        try {
            session.run(inputs.toMap()).use { result -> flatten(result[0].value) }
        } finally {
            inputs.forEach { it.second.close() }
        }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is DoubleArray -> FloatArray(value.size) { i -> value[i].toFloat() }
        is Array<*> -> {
            val parts = value.map { flatten(it) }
            val out = FloatArray(parts.sumOf { it.size })
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            out
        }
        else -> throw IllegalStateException("Seed-VC returned ${value?.javaClass}")
    }
}
