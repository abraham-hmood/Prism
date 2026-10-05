package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro-82M, actually speaking.
 *
 * ## What a voice is, and why they were all identical before this existed
 *
 * A Kokoro voice is not a setting. It is a **style tensor**: 510 x 256 floats, one 256-value row per
 * possible utterance length, shipped as its own half-megabyte file per voice. The model takes that
 * vector as an input alongside the phonemes, and it is the entire difference between Heart and
 * Fenrir -- pitch, timbre, accent, the lot.
 *
 * Until this class existed, [PrismSpeaker] fell through to Android's own engine for the Kokoro path,
 * and a Kokoro voice id could only be used for the one thing that engine understands: a locale. So
 * every American voice was the system's single American voice and every Japanese voice its single
 * Japanese one. The voices were downloaded, correct, and read by nothing.
 *
 * ## The pipeline
 *
 * ```
 * text -> KokoroPhonemizer -> IPA -> KokoroTokenizer -> ids
 *                                                        \
 *                                voice file -> style row --> ONNX -> 24 kHz mono float -> AudioSink
 * ```
 *
 * The style ROW is chosen by token count: Kokoro was trained with a different style vector per
 * length, which is why the file is 510 rows rather than one. Using row 0 for everything -- the
 * obvious shortcut -- produces audio that is recognisably the right voice and badly paced.
 *
 * ## PHASE 100: what moving this to :core cost, and what it did not
 *
 * Nothing above changed. The phonemiser, the tokeniser, the style-row indexing and the ONNX call are
 * the same code on a phone and on a PC, because `ai.onnxruntime` is the same API in the Android AAR
 * and the JVM jar -- the coordinate differs, the classes do not.
 *
 * What changed is the last step. This class used to drive `android.media.AudioTrack` directly, with
 * about sixty lines of buffer sizing, blocking writes and playback-head polling. All of that is
 * platform work, so it moved behind [com.prism.core.AudioSink], and the hard-won lesson that made it
 * work -- that you must follow the playback POSITION rather than the write position, or the track is
 * released while the audio is still queued -- is now stated in the sink's contract and honoured by
 * both implementations rather than living in one of them.
 */
class KokoroEngine private constructor(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val tokenizer: KokoroTokenizer,
    private val phonemizer: KokoroPhonemizer,
    private val voicesDir: File,
) : TtsEngine {

    override val displayName: String get() = "Kokoro-82M"

    @Volatile
    private var generation = 0

    /** Style tensors are half a megabyte each and get re-read every sentence otherwise. */
    private val styleCache = HashMap<String, FloatArray>()

    override fun isReady(): Boolean = true

    override fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        // Ignored: a Kokoro voice IS its language, so a locale could only contradict the voice.
        localeTag: String?,
        onDone: (String?) -> Unit,
    ) {
        val voice = voiceId ?: com.prism.launcher.PrismSettings.KOKORO_DEFAULT_VOICE
        val mine = ++generation

        Thread({
            val result = runCatching { synthesize(text, voice, speed) }
            if (mine != generation) return@Thread

            val audio = result.getOrNull()
            if (audio == null) {
                val reason = result.exceptionOrNull()
                PrismPlatform.log.error(TAG, "Kokoro could not speak", reason)
                PrismPlatform.main.post { onDone(explain(reason)) }
                return@Thread
            }

            play(audio, mine, onDone)
        }, "kokoro-speak").apply { isDaemon = true; start() }
    }

    /**
     * Turns an ONNX Runtime failure into something a user can act on.
     *
     * ONE CASE IS SINGLED OUT and it is worth the special case. A broken quantisation reports a
     * missing initializer inside a normalisation node -- a sentence about
     * `SkipLayerNormalization` and a weight name 90 characters long. Nothing about that tells the
     * reader that the fix is to pick a different download, which it is. See
     * [KokoroInstall.variantLabel] for which exports are affected.
     */
    private fun explain(reason: Throwable?): String {
        val message = reason?.message ?: return "Kokoro could not produce audio"
        if (message.contains("Missing Input") || message.contains("SkipLayerNormalization")) {
            return "This Kokoro export is incomplete -- the model references a weight it does not " +
                "contain, so it loads and then fails when run. Choose a different precision in " +
                "Speech; the quantised (92 MB) and full-precision exports are known good. " +
                "Original error: " + message.take(160)
        }
        return message
    }

    // -- Inference ------------------------------------------------------------

    private fun synthesize(text: String, voiceId: String, speed: Float): FloatArray {
        val phonemes = phonemizer.phonemize(text)
        val ids = tokenizer.encode(phonemes)
        if (ids.size <= 2) throw IllegalStateException("Nothing to say after phonemisation")

        // The row is indexed by the token count WITHOUT the two padding symbols, which is how the
        // reference implementation indexes it; off by those two and the pacing drifts.
        val style = styleFor(voiceId, ids.size - 2)

        val inputIds = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }),
            longArrayOf(1, ids.size.toLong()),
        )
        val styleTensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong()),
        )
        val speedTensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1),
        )

        return try {
            // Bound by NAME from the session rather than assumed, because the three inputs are not
            // in a fixed order across exports and feeding style where speed is expected produces
            // noise rather than an error.
            val names = session.inputNames.toList()
            val inputs = HashMap<String, OnnxTensor>()
            names.forEach { name ->
                when {
                    name.contains("input", true) || name.contains("token", true) ->
                        inputs[name] = inputIds
                    name.contains("style", true) || name.contains("ref", true) ->
                        inputs[name] = styleTensor
                    name.contains("speed", true) || name.contains("rate", true) ->
                        inputs[name] = speedTensor
                }
            }
            if (inputs.size < names.size) {
                throw IllegalStateException("Unrecognised Kokoro inputs: $names")
            }

            session.run(inputs).use { output ->
                val value = output[0].value
                flatten(value)
            }
        } finally {
            inputIds.close()
            styleTensor.close()
            speedTensor.close()
        }
    }

    /** The model returns either a flat waveform or one wrapped in a batch dimension. */
    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val rows = value.filterIsInstance<FloatArray>()
            if (rows.isEmpty()) throw IllegalStateException("Kokoro returned no audio")
            if (rows.size == 1) rows[0] else rows.reduce { a, b -> a + b }
        }
        else -> throw IllegalStateException("Kokoro returned ${value?.javaClass}")
    }

    /**
     * Reads one 256-value row out of a voice file.
     *
     * The file is 510 x 1 x 256 float32, little-endian, with no header -- so the row is a plain
     * offset. A length past the end is clamped rather than refused: a very long sentence is still
     * worth speaking in the voice that was asked for.
     */
    private fun styleFor(voiceId: String, tokenCount: Int): FloatArray {
        val key = "$voiceId:$tokenCount"
        styleCache[key]?.let { return it }

        val file = File(voicesDir, "$voiceId.bin")
        if (!file.isFile) throw IllegalStateException("The $voiceId voice has not been downloaded")

        val row = tokenCount.coerceIn(0, 509)
        val offset = row.toLong() * STYLE_WIDTH * 4

        val bytes = ByteArray(STYLE_WIDTH * 4)
        file.inputStream().use { input ->
            input.skip(offset)
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < bytes.size) throw IllegalStateException("The $voiceId voice file is truncated")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val style = FloatArray(STYLE_WIDTH) { buffer.get(it) }

        if (styleCache.size > 64) styleCache.clear()
        styleCache[key] = style
        return style
    }

    // -- Playback -------------------------------------------------------------

    /**
     * Hands the waveform to the platform's sink and waits for it to be HEARD.
     *
     * The sink blocks until playback has actually finished -- see [com.prism.core.AudioSink], where
     * that is part of the contract rather than an implementation detail. It matters here because
     * call mode starts listening off [onDone]: returning when the samples were queued rather than
     * when they were played makes Prism transcribe its own voice.
     *
     * [generation] is what a `stop()` or a newer sentence increments, and it is passed down as the
     * cancellation predicate so an interruption is noticed mid-buffer rather than after it.
     */
    private fun play(audio: FloatArray, mine: Int, onDone: (String?) -> Unit) {
        if (mine != generation) return

        val problem = PrismPlatform.audio.play(
            samples = audio,
            sampleRate = SAMPLE_RATE,
            cancelled = { mine != generation },
        )

        // A cancelled sentence is not a failure and must not report one: the caller's state machine
        // would treat it as "speech failed" and say something about it, when what happened is that
        // the user pressed stop.
        if (mine != generation) return

        if (problem != null) {
            PrismPlatform.log.warn(TAG, "Kokoro produced audio but it did not play: " + problem)
        }
        PrismPlatform.main.post { onDone(problem) }
    }

    override fun stop() {
        generation++
        runCatching { PrismPlatform.audio.stop() }
    }

    override fun release() {
        stop()
        runCatching { session.close() }
    }

    companion object {

        private const val TAG = "PrismSpeech"

        /** Kokoro's output rate. Not configurable: it is what the model was trained to produce. */
        private const val SAMPLE_RATE = 24_000

        private const val STYLE_WIDTH = 256

        /**
         * Opens Kokoro, or returns null if any of its three pieces is missing.
         *
         * Null rather than an exception: "not downloaded yet" is an ordinary state on a fresh
         * install, and the caller's response to it is to use something else, not to fail.
         */
        fun openOrNull(): KokoroEngine? {
            if (!KokoroInstall.isModelInstalled()) return null

            val tokenizer = KokoroTokenizer.load(KokoroInstall.tokenizerFile()) ?: return null
            val phonemizer = KokoroPhonemizer.load(KokoroInstall.lexiconFile())
                ?: run {
                    PrismPlatform.log.warn(TAG, "Kokoro is installed but its lexicon is not")
                    return null
                }

            return runCatching {
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(
                        // A DESKTOP CEILING THAT IS NOT FOUR. The old cap was written for a phone,
                        // where four big cores is the whole budget and more threads only fight the
                        // scheduler. Kokoro is ~200 ms of inference per sentence on a desktop and
                        // the machine has nothing else to do while it speaks.
                        Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
                    )
                    // NOT lowered from the default. Turning fusion down was tried, on the theory
                    // that the SkipLayerNormalization failure described in KokoroInstall was an
                    // optimiser fusion losing a tensor. It is not -- BASIC_OPT fails identically, so
                    // the node is in the exported graph rather than created by the optimiser, and
                    // giving up the fusions would have cost speed for nothing.
                }
                val session = environment.createSession(
                    KokoroInstall.modelFile().absolutePath, options,
                )
                PrismPlatform.log.info(
                    TAG,
                    "Kokoro loaded — inputs ${session.inputNames}, outputs ${session.outputNames}",
                )
                KokoroEngine(
                    session, environment, tokenizer, phonemizer, KokoroInstall.voicesDir(),
                )
            }.getOrElse {
                PrismPlatform.log.error(TAG, "Could not open Kokoro", it)
                null
            }
        }
    }
}
