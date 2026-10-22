package com.prism.launcher.speech

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/**
 * Pocket TTS (Kyutai): text to speech DIRECTLY in the recorded speaker's voice.
 *
 * This is the first engine in Prism that is not a voice converter. The converter family runs
 * Kokoro and then re-voices its output, so prosody and phrasing arrive from Kokoro and only the
 * voice identity from the reference. [PocketTtsEngine] synthesises the sentence itself,
 * conditioned on the reference recording, which is what makes pitch contours, pacing, phrasing
 * and affect follow the person in the reference rather than the base voice Kokoro speaks
 * through -- the aspects of a human voice that a converter architecture cannot reach.
 *
 * ## The pipeline (mirrors the KevinAHM/pocket-tts-onnx export, bundle english_2026-04)
 *
 * ```
 * text -> [PocketTokenizer] -> ids
 * ids -> text_conditioner -> (1, T, 1024) text embeddings
 * reference (22.05k) -> resample 24k -> mimi_encoder -> (1, F, 1024) voice frames [+ 1 BOS frame]
 * voice frames -> flow_lm_main (empty sequence) -> per-voice state (transformer KV caches)
 * then per generated frame:
 *   flow_lm_main(sequence=latent, text=empty, state clone) -> conditioning + eos + new state
 *   x ~ N(0, temperature)                       (0.7 by default; 0 for deterministic parity)
 *   flow_lm_flow(c, s, t, x) -> x + flow * dt   [LSD_STEPS outer Euler sub-steps per frame]
 *   latent (1,1,32) -> next frame's sequence
 * latents in chunks of <= 15 -> mimi_decoder (own 56-tensor state) -> 24 kHz audio
 * ```
 *
 * 12.5 frames per second, 1920 samples per frame, latent dim 32, conditioning dim 1024. EOS
 * raises the model's eos logit above -4; a few more frames later the utterance is over.
 *
 * ## Where the state lives
 *
 * Conditioning a voice produces the flow LM's KV caches -- about 49 MiB -- so ONE voice is kept
 * prepared at a time, keyed by voice id and reference length. Each generated text chunk clones
 * that prepared state and evolves its own copy, exactly like the wrapper clones per chunk; the
 * prepared state itself never advances. The mimi decoder state is per chunk, rebuilt fresh from
 * the manifest.
 *
 * ## Output tensor ownership
 *
 * Session results are deliberately kept open rather than closed after extraction: the state
 * tensors that carry from run to run ARE entries of those results, and java ORT closes every
 * value a [OrtSession.Result] holds when it closes. A [Scrap] tracks what is held open per
 * phase (per generated chunk, per prepared voice) and closes it all on phase end -- the one
 * place everything gets released, instead of one too-early close here or a leak there. Big
 * tensors that adoption REPLACES are closed one-at-a-time inside [adopt], keeping the resident
 * set at one state's worth rather than one per frame.
 */
object PocketTtsEngine : CloneEngine, CloneEngine.ReferenceSpeechEngine {

    override val id: String = "pocket"
    override val label: String = "Pocket TTS"
    override val summary: String =
        "Speaks text directly in the recorded voice's style · flow LM · CC BY 4.0 · 24 kHz"
    override val preservesPitch: Boolean = true
    override val attribution: String? =
        "Pocket TTS (Kyutai, CC BY 4.0) · ONNX export: KevinAHM/pocket-tts-onnx"

    private const val TAG = "PrismSpeech"

    private const val TEMPERATURE = 0.7f
    private const val LSD_STEPS = 1
    private const val EOS_THRESHOLD = -4.0f
    private const val TOKENS_PER_SECOND_ESTIMATE = 3.0f
    private const val GEN_SECONDS_PADDING = 2.0f
    private const val DECODE_CHUNK_FRAMES = 15
    private const val MAX_TOKEN_PER_CHUNK = 50
    private const val FRAME_RATE = 12.5f

    /** Parity-harness hook: a value >= 0 overrides [TEMPERATURE] for the next generation. */
    @Volatile
    var temperatureForTest: Float = -1f

    private const val LATENT_DIM = 32
    private const val CONDITIONING_DIM = 1_024
    private const val SAMPLE_RATE = 24_000
    /** VoiceCloner samples reference recordings at this rate; the model wants [SAMPLE_RATE]. */
    private const val WORK_RATE = 22_050

    private const val RELEASE =
        "https://github.com/ibrahim-hmood/Prism/releases/download/voice-engines-v1"

    val MAIN = VoiceCloningModels.Model(
        id = "pocket-main",
        label = "Pocket TTS flow LM",
        file = "flow_lm_main_int8.onnx",
        sizeBytes = 76_341_079L,
        url = "$RELEASE/flow_lm_main_int8.onnx",
    )
    val FLOW = VoiceCloningModels.Model(
        id = "pocket-flow",
        label = "Pocket TTS flow net",
        file = "flow_lm_flow_int8.onnx",
        sizeBytes = 9_962_530L,
        url = "$RELEASE/flow_lm_flow_int8.onnx",
    )
    val DECODER = VoiceCloningModels.Model(
        id = "pocket-decoder",
        label = "Pocket TTS vocoder",
        file = "mimi_decoder_int8.onnx",
        sizeBytes = 22_684_077L,
        url = "$RELEASE/mimi_decoder_int8.onnx",
    )
    val ENCODER = VoiceCloningModels.Model(
        id = "pocket-encoder",
        label = "Pocket TTS alignment",
        file = "mimi_encoder.onnx",
        sizeBytes = 39_768_446L,
        url = "$RELEASE/mimi_encoder.onnx",
    )
    val TEXT = VoiceCloningModels.Model(
        id = "pocket-text",
        label = "Pocket TTS text",
        file = "text_conditioner.onnx",
        sizeBytes = 16_388_344L,
        url = "$RELEASE/text_conditioner.onnx",
    )
    val VOCAB = VoiceCloningModels.Model(
        id = "pocket-vocab",
        label = "Pocket TTS tokenizer",
        file = "pocket_vocab.tsv",
        sizeBytes = 111_626L,
        url = "$RELEASE/pocket_vocab.tsv",
    )
    val BOS = VoiceCloningModels.Model(
        id = "pocket-bos",
        label = "Pocket TTS prefix",
        file = "pocket_bos.f32",
        sizeBytes = 4_096L,
        url = "$RELEASE/pocket_bos.f32",
    )

    override val models: List<VoiceCloningModels.Model> =
        listOf(MAIN, FLOW, DECODER, ENCODER, TEXT, VOCAB, BOS)

    // -- Constants carried from the bundle manifest ---------------------------

    /** Six KV caches of (2, 1, 1000, 16, 64), six empty markers, six step counters. */
    private const val FLOW_STATES = 18
    private const val CACHE_ELEMENTS = (2L * 1000 * 16 * 64).toInt()
    private val CACHE_SHAPE = longArrayOf(2, 1, 1000, 16, 64)
    private val EMPTY_SHAPE = longArrayOf(0)
    private val SEQUENCE_SHAPE = longArrayOf(1, 0, LATENT_DIM.toLong())
    private val EMPTY_TEXT_SHAPE = longArrayOf(1, 0, CONDITIONING_DIM.toLong())

    /**
     * The mimi decoder state, in manifest index order: (type, shape). EMPTY entries are shapes
     * whose last dimension is zero -- exactly the zero-sized tensors the wrapper feeds ORT.
     */
    private val MIMI_MANIFEST: List<Pair<Int, LongArray>> = listOf(
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.0/first
        TYPE_FLOAT to longArrayOf(1, 512, 6),              // decoder.model.0/previous
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.11/first
        TYPE_FLOAT to longArrayOf(1, 64, 2),               // decoder.model.11/previous
        TYPE_FLOAT to longArrayOf(1, 256, 6),              // decoder.model.2/partial
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.3.block.1/first
        TYPE_FLOAT to longArrayOf(1, 256, 2),              // decoder.model.3.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.3.block.3/first
        TYPE_EMPTY to longArrayOf(1, 128, 0),              // decoder.model.3.block.3/previous
        TYPE_FLOAT to longArrayOf(1, 128, 5),              // decoder.model.5/partial
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.6.block.1/first
        TYPE_FLOAT to longArrayOf(1, 128, 2),              // decoder.model.6.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.6.block.3/first
        TYPE_EMPTY to longArrayOf(1, 64, 0),               // decoder.model.6.block.3/previous
        TYPE_FLOAT to longArrayOf(1, 64, 4),               // decoder.model.8/partial
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.9.block.1/first
        TYPE_FLOAT to longArrayOf(1, 64, 2),               // decoder.model.9.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // decoder.model.9.block.3/first
        TYPE_EMPTY to longArrayOf(1, 32, 0),               // decoder.model.9.block.3/previous
        TYPE_FLOAT to longArrayOf(2, 1, 8, 1000, 64),      // decoder transformer cache 0
        TYPE_INT64 to longArrayOf(1),                      // decoder transformer end_offset 0
        TYPE_INT64 to longArrayOf(1),                      // decoder transformer offset 0
        TYPE_FLOAT to longArrayOf(2, 1, 8, 1000, 64),      // decoder transformer cache 1
        TYPE_INT64 to longArrayOf(1),                      // decoder transformer end_offset 1
        TYPE_INT64 to longArrayOf(1),                      // decoder transformer offset 1
        TYPE_BOOL to longArrayOf(1),                       // downsample.conv/first
        TYPE_FLOAT to longArrayOf(1, 512, 16),             // downsample.conv/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.0/first
        TYPE_FLOAT to longArrayOf(1, 1, 6),                // encoder.model.0/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.1.block.1/first
        TYPE_FLOAT to longArrayOf(1, 64, 2),               // encoder.model.1.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.1.block.3/first
        TYPE_EMPTY to longArrayOf(1, 32, 0),               // encoder.model.1.block.3/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.11/first
        TYPE_FLOAT to longArrayOf(1, 512, 2),              // encoder.model.11/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.3/first
        TYPE_FLOAT to longArrayOf(1, 64, 4),               // encoder.model.3/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.4.block.1/first
        TYPE_FLOAT to longArrayOf(1, 128, 2),              // encoder.model.4.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.4.block.3/first
        TYPE_EMPTY to longArrayOf(1, 64, 0),               // encoder.model.4.block.3/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.6/first
        TYPE_FLOAT to longArrayOf(1, 128, 5),              // encoder.model.6/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.7.block.1/first
        TYPE_FLOAT to longArrayOf(1, 256, 2),              // encoder.model.7.block.1/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.7.block.3/first
        TYPE_EMPTY to longArrayOf(1, 128, 0),              // encoder.model.7.block.3/previous
        TYPE_BOOL to longArrayOf(1),                       // encoder.model.9/first
        TYPE_FLOAT to longArrayOf(1, 256, 6),              // encoder.model.9/previous
        TYPE_FLOAT to longArrayOf(2, 1, 8, 1000, 64),      // encoder transformer cache 0
        TYPE_INT64 to longArrayOf(1),                      // encoder transformer end_offset 0
        TYPE_INT64 to longArrayOf(1),                      // encoder transformer offset 0
        TYPE_FLOAT to longArrayOf(2, 1, 8, 1000, 64),      // encoder transformer cache 1
        TYPE_INT64 to longArrayOf(1),                      // encoder transformer end_offset 1
        TYPE_INT64 to longArrayOf(1),                      // encoder transformer offset 1
        TYPE_FLOAT to longArrayOf(1, 512, 16),             // upsample.convtr/partial
    )

    private const val TYPE_BOOL = 1
    private const val TYPE_FLOAT = 2
    private const val TYPE_INT64 = 3
    private const val TYPE_EMPTY = 4

    private fun shapeElements(shape: LongArray): Int {
        var product = 1L
        for (dim in shape) product *= dim
        return product.toInt()
    }

    // -- Sessions and lifecycle -----------------------------------------------

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private val sessions = mutableMapOf<String, OrtSession>()

    override fun close() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
            releasePrepared()
        }
    }

    private fun releasePrepared() {
        prepared?.let { runCatching { it.garbage.close() } }
        prepared = null
    }

    private fun logTiming(message: String) {
        PrismPlatform.log.info(TAG, "Pocket $message")
    }

    /**
     * Everything held open for one phase, closed at its end. [hold]ed run results stay open so
     * their adopted state tensors keep working as later inputs; closing here is the release
     * point. Individual tensors that [adopt] replace are closed on the spot (also run-catcher
     * covered here if the same tensor is adopted twice), so close is idempotent by convention.
     */
    private class Scrap : AutoCloseable {
        private val held = ArrayList<AutoCloseable>()

        fun hold(value: AutoCloseable) {
            held.add(value)
        }

        override fun close() {
            for (value in held) runCatching { value.close() }
            held.clear()
        }
    }

    private fun env(): OrtEnvironment =
        environment ?: OrtEnvironment.getEnvironment().also { environment = it }

    private fun openSession(model: VoiceCloningModels.Model): OrtSession =
        synchronized(lock) {
            sessions[model.id]?.let { return it }
            val file = VoiceCloningModels.fileFor(model)
            if (!file.isFile) {
                throw IllegalStateException(
                    "The Pocket TTS models are not downloaded -- open voice cloning and download " +
                        "the $label engine (${totalSizeLabel()})",
                )
            }
            val opened = runCatching {
                env().createSession(
                    file.absolutePath,
                    OrtSession.SessionOptions().apply {
                        // The wrapper's tuned thread count: the AR loop runs small sequential
                        // matmuls, and more threads than four only fight the scheduler.
                        setIntraOpNumThreads(
                            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                        )
                        setInterOpNumThreads(1)
                    },
                )
            }.getOrElse {
                PrismPlatform.log.error(TAG, "Could not open ${file.name}", it)
                throw IllegalStateException(
                    "The ${file.nameWithoutExtension} model would not open: ${it.message}"
                )
            }
            sessions[model.id] = opened
            opened
        }

    // -- Tensor helpers -------------------------------------------------------

    private fun floatT(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env(), FloatBuffer.wrap(data), shape)

    private fun longT(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env(), LongBuffer.wrap(data), shape)

    private fun boolT(data: BooleanArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(
            env(),
            ByteBuffer.wrap(ByteArray(data.size) { i -> (if (data[i]) 1 else 0).toByte() }),
            shape,
            OnnxJavaType.BOOL,
        )

    /** Flattens an ORT java value (nested arrays, doubles, bools...) into floats. */
    private fun flatten(value: Any?): FloatArray {
        if (value is FloatArray) return value
        if (value is DoubleArray) return FloatArray(value.size) { i -> value[i].toFloat() }
        if (value is BooleanArray) return FloatArray(value.size) { i -> if (value[i]) 1f else 0f }
        if (value is LongArray) return FloatArray(value.size) { i -> value[i].toFloat() }
        if (value is Array<*>) {
            val parts = value.map { flatten(it) }
            val out = FloatArray(parts.sumOf { it.size })
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }
        throw IllegalStateException("Pocket returned ${value?.javaClass}")
    }

    /** Reads one output as a float array without keeping the result alive. */
    private fun pull(result: OrtSession.Result, index: Int) = flatten(result.get(index).value)

    /**
     * Moves a stateful run's fresh state tensors into [state], closing what they replace. The
     * [result] stays open (it owns the fresh tensors); the caller's [Scrap] closes it.
     */
    private fun adopt(result: OrtSession.Result, state: Array<OnnxTensor?>, offset: Int) {
        for (i in state.indices) {
            val fresh = result.get(offset + i) as OnnxTensor
            val old = state[i]
            state[i] = fresh
            if (old !== null) runCatching { old.close() }
        }
    }

    // -- Per-voice prepared state ---------------------------------------------

    private class VoiceEntry(
        val key: Pair<String, Int>,
        val state: Array<OnnxTensor?>,
        /**
         * The run whose outputs the [state] tensors were adopted from -- held open FOR AS LONG
         * AS THIS ENTRY LIVES. Closing this is exactly dropping the prepared voice, and must
         * happen only in [releasePrepared] or when [preparedFor] replaces it with another voice:
         * tie it to a generation call's scrap and the SECOND sentence on the same voice reads
         * tensors that the first call's cleanup already closed ("trying to use closed onnx value").
         */
        val garbage: Scrap,
    )

    @Volatile
    private var prepared: VoiceEntry? = null

    @Volatile
    private var bosFrame: FloatArray? = null

    private fun readBos(): FloatArray {
        bosFrame?.let { return it }
        val file = VoiceCloningModels.fileFor(BOS)
        if (!file.isFile) {
            throw IllegalStateException(
                "The Pocket TTS models are not downloaded -- open voice cloning and download " +
                    "the $label engine (${totalSizeLabel()})"
            )
        }
        val bytes = file.readBytes()
        if (bytes.size != CONDITIONING_DIM * 4) {
            throw IllegalStateException(
                "The Pocket TTS prefix frame is ${bytes.size} bytes, expected ${CONDITIONING_DIM * 4}"
            )
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frame = FloatArray(CONDITIONING_DIM) { bb.getFloat(it * 4) }
        bosFrame = frame
        return frame
    }

    /** Flow-LM state input values, in manifest order: name -> current tensor. */
    private fun flowInputs(state: Array<OnnxTensor?>): List<Pair<String, OnnxTensor>> {
        val inputs = ArrayList<Pair<String, OnnxTensor>>(FLOW_STATES)
        for (i in 0 until FLOW_STATES) {
            inputs.add("state_$i" to (state[i] ?: error("Flow state $i missing")))
        }
        return inputs
    }

    /** A fresh flow state per the manifest: NaN caches, empty markers, zeroed step counters. */
    private fun freshFlowState(scrap: Scrap): Array<OnnxTensor?> {
        val state = arrayOfNulls<OnnxTensor>(FLOW_STATES)
        for (i in 0 until FLOW_STATES) {
            state[i] = when (i % 3) {
                0 -> floatT(FloatArray(CACHE_ELEMENTS) { Float.NaN }, CACHE_SHAPE)
                1 -> floatT(FloatArray(0), EMPTY_SHAPE)
                else -> longT(longArrayOf(0), longArrayOf(1))
            }
        }
        return state
    }

    /** Copies a flow state tensor by value, whatever slot type it is. */
    private fun cloneFlowState(scrap: Scrap, state: Array<OnnxTensor?>): Array<OnnxTensor?> {
        val copy = arrayOfNulls<OnnxTensor>(state.size)
        for (i in state.indices) {
            val source = state[i] ?: continue
            val fresh = when (i % 3) {
                0 -> {
                    val buffer = source.floatBuffer
                    val data = FloatArray(CACHE_ELEMENTS).also { buffer.get(it, 0, CACHE_ELEMENTS) }
                    floatT(data, CACHE_SHAPE)
                }
                1 -> floatT(FloatArray(0), EMPTY_SHAPE)
                else -> {
                    val buffer = source.longBuffer
                    val data = LongArray(1).also { buffer.get(it, 0, 1) }
                    longT(data, longArrayOf(1))
                }
            }
            copy[i] = fresh
        }
        return copy
    }

    /**
     * Reference audio (float at [WORK_RATE]) to the conditioned voice state.
     *
     * Mirrors the wrapper: resample to 24 kHz, run mimi encoder, prepend the bundle's one BOS
     * frame, then evolve flow_lm_main once over an empty sequence. The produced state tensors
     * are the voice's identity for every generation until the reference changes.
     */
    @Synchronized
    private fun preparedFor(voiceId: String, reference: FloatArray): VoiceEntry {
        val key = voiceId to reference.size
        prepared?.let { if (it.key == key) return it }

        // A different voice replaces the entry: at most one keeps its ~49 MiB resident.
        prepared?.let { runCatching { it.garbage.close() } }
        prepared = null

        // NOT held in the calling generation's scrap -- the entry owns it until the voice is
        // replaced or the engine closes. See VoiceEntry.garbage for the exact bug this avoids.
        val voiceScrap = Scrap()
        val state = freshFlowState(voiceScrap)

        val audio24 = VoicePitch.resample(reference, WORK_RATE, SAMPLE_RATE)
        val encoder = openSession(ENCODER)
        val embeddings = encoder.run(mapOf(
            "audio" to floatT(audio24, longArrayOf(1, 1, audio24.size.toLong())),
        )).use { result -> flatten(result.get(0).value) }

        val withBos = FloatArray(CONDITIONING_DIM + embeddings.size)
        readBos().copyInto(withBos, 0)
        embeddings.copyInto(withBos, CONDITIONING_DIM)
        val frames = withBos.size / CONDITIONING_DIM

        val main = openSession(MAIN)
        val inputs = ArrayList<Pair<String, OnnxTensor>>(2 + FLOW_STATES)
        inputs.add("sequence" to floatT(FloatArray(0), SEQUENCE_SHAPE))
        inputs.add("text_embeddings" to floatT(
            withBos, longArrayOf(1, frames.toLong(), CONDITIONING_DIM.toLong()),
        ))
        for (pair in flowInputs(state)) inputs.add(pair)
        val run = main.run(inputs.toMap())
        adopt(run, state, 2)
        voiceScrap.hold(run)

        val entry = VoiceEntry(key, state, voiceScrap)
        prepared = entry
        logTiming("voice ${voiceId.take(12)} conditioned ($frames voice frames)")
        return entry
    }

    // -- Speaking -------------------------------------------------------------

    override fun speakReference(
        text: String,
        voiceId: String,
        reference: FloatArray,
    ): CloneEngine.Converted {
        val overall = System.nanoTime()
        val scrap = Scrap()
        try {
            val voice = preparedFor(voiceId, reference)
            val tokenizer = tokenizer()
            val audio = ArrayList<FloatArray>()
            var framesTotal = 0

            for (chunk in splitBestChunks(text, tokenizer)) {
                val preparedText = prepareTextPrompt(chunk)
                val ids = tokenizer.encode(preparedText)
                if (ids.size <= 1) continue
                val words = preparedText.trim().split(' ').count { it.isNotEmpty() }
                val framesAfterEos = (if (words <= 4) 3 else 1) + 2
                val limit = estimateMaxGenFrames(ids.size)
                val chunkStarted = System.nanoTime()

                val frames = generateFrames(scrap, voice, ids, limit, framesAfterEos, voiceId)
                framesTotal += frames.size
                if (frames.isNotEmpty()) audio.add(decodeFrames(scrap, frames))

                logTiming(
                    "chunk -> ${frames.size} frames in " +
                        "%.2fs".format((System.nanoTime() - chunkStarted) / 1e9)
                )
            }

            var flat = FloatArray(0)
            if (audio.isNotEmpty()) {
                flat = FloatArray(audio.sumOf { it.size })
                var at = 0
                for (part in audio) {
                    part.copyInto(flat, at)
                    at += part.size
                }
            }
            if (framesTotal == 0) {
                throw IllegalStateException(
                    "Pocket TTS produced no audio for that text -- try a fuller sentence",
                )
            }
            logTiming("spoke $framesTotal frames in %.2fs".format((System.nanoTime() - overall) / 1e9))
            return CloneEngine.Converted(flat, SAMPLE_RATE)
        } finally {
            runCatching { scrap.close() }
        }
    }

    /**
     * One generated text chunk's latents.
     *
     * The prepared voice's state is CLONED per chunk (the wrapper clones it too); the clone then
     * advances through the per-frame runs, and its tensors are retired per frame by adoption.
     * Sequence and text tensors are small and recreated per run; only the empty shapes are hoisted.
     */
    private fun generateFrames(
        scrap: Scrap,
        voice: VoiceEntry,
        ids: IntArray,
        limit: Int,
        framesAfterEos: Int,
        voiceId: String,
    ): List<FloatArray> {
        val main = openSession(MAIN)
        val flow = openSession(FLOW)
        val rng = Random(voiceId.hashCode().toLong() * 1_000_003L + ids.size)
        val temperature = if (temperatureForTest >= 0f) temperatureForTest else TEMPERATURE
        val noiseScale = kotlin.math.sqrt(temperature).toFloat()

        // The voice's frozen state becomes this chunk's live state.
        val chunkState = cloneFlowState(scrap, voice.state)

        // Conditioning once per chunk: text ids -> embeddings -> one main run over an EMPTY
        // prime sequence (the wrapper's _run_flow_lm_chunk first run).
        val textCond = openSession(TEXT)
        val embeddings = textCond.run(mapOf(
            "token_ids" to longT(LongArray(ids.size) { ids[it].toLong() },
                longArrayOf(1, ids.size.toLong())),
        )).use { result -> flatten(result.get(0).value) }
        if (embeddings.size % CONDITIONING_DIM != 0) {
            throw IllegalStateException("The text conditioner returned ${embeddings.size} values")
        }

        val emptyText = floatT(FloatArray(0), EMPTY_TEXT_SHAPE)
        val inputs = ArrayList<Pair<String, OnnxTensor>>(2 + FLOW_STATES)
        inputs.add("sequence" to floatT(FloatArray(0), SEQUENCE_SHAPE))
        inputs.add("text_embeddings" to floatT(
            embeddings, longArrayOf(1, (embeddings.size / CONDITIONING_DIM).toLong(), CONDITIONING_DIM.toLong()),
        ))
        for (pair in flowInputs(chunkState)) inputs.add(pair)
        val prime = main.run(inputs.toMap())
        adopt(prime, chunkState, 2)
        scrap.hold(prime)

        val frames = ArrayList<FloatArray>(limit)
        var eosStep = -1
        val frameLimit = limit
        var current = floatT(FloatArray(LATENT_DIM) { Float.NaN },
            longArrayOf(1, 1, LATENT_DIM.toLong()))

        for (step in 0 until frameLimit) {
            val frameInputs = ArrayList<Pair<String, OnnxTensor>>(2 + FLOW_STATES)
            frameInputs.add("sequence" to current)
            frameInputs.add("text_embeddings" to emptyText)
            for (pair in flowInputs(chunkState)) frameInputs.add(pair)
            val result = main.run(frameInputs.toMap())
            scrap.hold(result)
            val conditioning = pull(result, 0)
            val eosLogit = pull(result, 1)
            adopt(result, chunkState, 2)
            runCatching { current.close() }

            if (eosStep < 0 && eosLogit[0] > EOS_THRESHOLD) eosStep = step
            if (eosStep >= 0 && step >= eosStep + framesAfterEos) break

            val x = FloatArray(LATENT_DIM)
            if (temperature > 0f) {
                for (i in x.indices) x[i] = (rng.nextGaussian() * noiseScale).toFloat()
            }

            val dt = 1f / LSD_STEPS
            var s = 0f
            repeat(LSD_STEPS) {
                val t = s + dt
                val velocity = flow.run(mapOf(
                    "c" to floatT(conditioning, longArrayOf(1, CONDITIONING_DIM.toLong())),
                    "s" to floatT(floatArrayOf(s), longArrayOf(1, 1)),
                    "t" to floatT(floatArrayOf(t), longArrayOf(1, 1)),
                    "x" to floatT(x, longArrayOf(1, LATENT_DIM.toLong())),
                )).use { r -> pull(r, 0) }
                for (i in x.indices) x[i] += velocity[i] * dt
                s = t
            }

            current = floatT(x, longArrayOf(1, 1, LATENT_DIM.toLong()))
            frames.add(x.copyOf())
        }
        for (tensor in chunkState) runCatching { tensor?.close() }
        runCatching { emptyText.close() }
        return frames
    }

    // -- Decoding -------------------------------------------------------------

    /** Mimi-decodes frames at 24 kHz; the decoder state is fresh per generated text chunk. */
    private fun decodeFrames(scrap: Scrap, frames: List<FloatArray>): FloatArray {
        val decoder = openSession(DECODER)
        val state = arrayOfNulls<OnnxTensor>(MIMI_MANIFEST.size)
        for (i in state.indices) {
            val (type, shape) = MIMI_MANIFEST[i]
            state[i] = when (type) {
                TYPE_BOOL -> boolT(booleanArrayOf(true), shape)
                TYPE_INT64 -> longT(longArrayOf(0), shape)
                // EMPTY shapes are zero-sized tensors exactly as the wrapper feeds ORT.
                TYPE_EMPTY -> floatT(FloatArray(0), shape)
                else -> floatT(FloatArray(shapeElements(shape)), shape)
            }
        }

        val audio = ArrayList<FloatArray>()
        var start = 0
        while (start < frames.size) {
            val end = minOf(start + DECODE_CHUNK_FRAMES, frames.size)
            val latent = FloatArray((end - start) * LATENT_DIM)
            for (f in start until end) {
                frames[f].copyInto(latent, (f - start) * LATENT_DIM)
            }
            val inputs = ArrayList<Pair<String, OnnxTensor>>(1 + state.size)
            inputs.add("latent" to floatT(latent, longArrayOf(1, (end - start).toLong(), LATENT_DIM.toLong())))
            for (i in state.indices) inputs.add("state_$i" to (state[i] ?: error("mimi state $i missing")))
            val result = decoder.run(inputs.toMap())
            scrap.hold(result)
            audio.add(pull(result, 0))
            adopt(result, state, 1)
            start = end
        }
        for (tensor in state) runCatching { tensor?.close() }

        val flat = FloatArray(audio.sumOf { it.size })
        var at = 0
        for (part in audio) {
            part.copyInto(flat, at)
            at += part.size
        }
        return flat
    }

    // -- Text pipeline --------------------------------------------------------

    /**
     * The wrapper's prompt normalisation: strip, newlines to spaces, collapse runs of exactly
     * two spaces, capitalise, terminate with a period. (Semicolon replacement and short-input
     * padding are off in this bundle's metadata and stay off.)
     */
    private fun prepareTextPrompt(text: String): String {
        var prepared = text.trim()
        if (prepared.isEmpty()) return ""
        prepared = prepared
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("  ", " ")
        if (!prepared[0].isUpperCase()) {
            prepared = prepared[0].uppercase() + prepared.substring(1)
        }
        if (prepared.last().isLetterOrDigit()) prepared += "."
        return prepared
    }

    private fun tokenizer(): PocketTokenizer {
        tokenizerCache?.let { return it }
        synchronized(lock) {
            tokenizerCache?.let { return it }
            val loaded = PocketTokenizer.load(VoiceCloningModels.fileFor(VOCAB))
                ?: throw IllegalStateException(
                    "The Pocket TTS tokenizer is missing or damaged -- re-download the $label engine"
                )
            tokenizerCache = loaded
            return loaded
        }
    }

    @Volatile
    private var tokenizerCache: PocketTokenizer? = null

    /**
     * The wrapper's chunk splitter: punctuation boundaries at the id level (decoded back to
     * text), comma-class refinement inside over-length segments, then greedy packing of segments
     * up to [MAX_TOKEN_PER_CHUNK]. Returns chunk TEXT; ids are re-encoded per final chunk, so
     * chunking and generation read the same prepared text twice, as the wrapper does.
     */
    private fun splitBestChunks(text: String, tokenizer: PocketTokenizer): List<String> {
        val prepared = prepareTextPrompt(text).trim()
        if (prepared.isEmpty()) return emptyList()
        val tokens = tokenizer.encode(prepared)
        if (tokens.isEmpty()) return emptyList()

        val eosSet = tokenizer.encode(".!...?").drop(1).toHashSet()
        val segments = segmentsFromBoundaries(tokens, boundaryIndices(tokens, eosSet), tokenizer)

        val refined = ArrayList<Pair<Int, String>>()
        val commaSet = tokenizer.encode(",;:").drop(1).toHashSet()
        for ((count, segmentText) in segments) {
            if (count <= MAX_TOKEN_PER_CHUNK) {
                refined.add(count to segmentText)
            } else {
                val sub = tokenizer.encode(segmentText.trim())
                val subSegments =
                    segmentsFromBoundaries(sub, boundaryIndices(sub, commaSet), tokenizer)
                if (subSegments.size > 1) refined.addAll(subSegments) else refined.add(count to segmentText)
            }
        }

        val chunks = ArrayList<String>()
        var current = ""
        var currentCount = 0
        for ((count, segmentText) in refined) {
            if (current.isEmpty()) {
                current = segmentText
                currentCount = count
                continue
            }
            if (currentCount + count > MAX_TOKEN_PER_CHUNK) {
                chunks.add(current.trim())
                current = segmentText
                currentCount = count
            } else {
                current = current + " " + segmentText
                currentCount += count
            }
        }
        if (current.isNotEmpty()) chunks.add(current.trim())
        return chunks
    }

    private fun boundaryIndices(tokens: IntArray, boundary: Set<Int>): List<Int> {
        val indices = ArrayList<Int>(tokens.size / 8 + 2)
        indices.add(0)
        var previousWasBoundary = false
        for (index in tokens.indices) {
            if (boundary.contains(tokens[index])) {
                previousWasBoundary = true
            } else {
                if (previousWasBoundary) indices.add(index)
                previousWasBoundary = false
            }
        }
        indices.add(tokens.size)
        return indices
    }

    private fun segmentsFromBoundaries(
        tokens: IntArray,
        boundaries: List<Int>,
        tokenizer: PocketTokenizer,
    ): List<Pair<Int, String>> {
        val segments = ArrayList<Pair<Int, String>>()
        for (i in 0 until boundaries.size - 1) {
            val start = boundaries[i]
            val end = boundaries[i + 1]
            segments.add((end - start) to tokenizer.decode(tokens.copyOfRange(start, end)))
        }
        return segments
    }

    private fun estimateMaxGenFrames(tokenCount: Int): Int {
        val seconds = tokenCount / TOKENS_PER_SECOND_ESTIMATE + GEN_SECONDS_PADDING
        return kotlin.math.ceil(seconds * FRAME_RATE).toInt()
    }

    /**
     * A speaks-the-text engine has no audio-to-audio conversion: refuse paths that route Kokoro
     * audio through it, with a message that says where the text needs to go instead.
     */
    override fun convert(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray?,
    ): CloneEngine.Converted {
        throw IllegalStateException(
            "Pocket TTS speaks text directly in the reference voice and does not re-voice Kokoro " +
                "audio -- use it as the cloning engine and Prism routes text to it itself.",
        )
    }
}
