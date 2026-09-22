package com.prism.launcher.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.prism.launcher.PrismLogger
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
 *                                voice file -> style row --> ONNX -> 24 kHz mono float -> AudioTrack
 * ```
 *
 * The style ROW is chosen by token count: Kokoro was trained with a different style vector per
 * length, which is why the file is 510 rows rather than one. Using row 0 for everything -- the
 * obvious shortcut -- produces audio that is recognisably the right voice and badly paced.
 */
class KokoroEngine private constructor(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val tokenizer: KokoroTokenizer,
    private val phonemizer: KokoroPhonemizer,
    private val voicesDir: File,
) : TtsEngine {

    override val displayName: String get() = "Kokoro-82M"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var track: AudioTrack? = null

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
                PrismLogger.logError(TAG, "Kokoro could not speak", reason)
                main.post { onDone(reason?.message ?: "Kokoro could not produce audio") }
                return@Thread
            }

            play(audio, mine, onDone)
        }, "kokoro-speak").apply { isDaemon = true; start() }
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

    private fun play(audio: FloatArray, mine: Int, onDone: (String?) -> Unit) {
        stopTrack()
        if (mine != generation) return

        // A SMALL buffer, on purpose.
        //
        // Sizing it to hold the whole clip is the obvious thing and it silently breaks playback:
        // WRITE_BLOCKING then never blocks, because everything fits, so the write loop finishes in
        // milliseconds and the track is stopped and released while the audio is still sitting in the
        // buffer waiting to be played. The device reported "58200 frames delivered" and the speaker
        // stayed silent. A quarter-second buffer makes the writes pace against real playback.
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(SAMPLE_RATE / 4 * 4)

        val instance = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minimum)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = instance
        instance.play()

        // Blocking writes on this worker, so the completion fires when the audio has actually been
        // consumed rather than when it was queued -- call mode starts listening off this callback,
        // and starting while the speaker is still talking makes it hear itself.
        var written = 0
        while (written < audio.size && mine == generation) {
            val n = instance.write(audio, written, audio.size - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            written += n
        }

        // And then WAIT for it to actually come out of the speaker.
        //
        // Writing the last sample is not the same event as playing it: there is still up to a
        // buffer's worth queued. The playback head is the only honest measure of what has been
        // heard, so this follows it to the end rather than assuming.
        val deadline = System.currentTimeMillis() +
            (audio.size * 1000L / SAMPLE_RATE) + PLAYBACK_GRACE_MS
        while (mine == generation &&
            instance.playbackHeadPosition < written &&
            System.currentTimeMillis() < deadline
        ) {
            val remaining = written - instance.playbackHeadPosition
            runCatching { Thread.sleep((remaining * 1000L / SAMPLE_RATE).coerceIn(10, 150)) }
        }

        runCatching {
            if (mine == generation) {
                instance.stop()
                // The tail is still audible for a moment after stop(), and cutting straight to
                // listening clips the last word.
                Thread.sleep(TAIL_MS)
            }
        }
        stopTrack()

        if (mine == generation) main.post { onDone(null) }
    }

    private fun stopTrack() {
        val instance = track ?: return
        track = null
        runCatching { instance.pause() }
        runCatching { instance.flush() }
        runCatching { instance.release() }
    }

    override fun stop() {
        generation++
        stopTrack()
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

        private const val TAIL_MS = 120L

        /** Slack on the playback wait, so a stalled mixer cannot hang the call loop forever. */
        private const val PLAYBACK_GRACE_MS = 1_500L

        /**
         * Opens Kokoro, or returns null if any of its three pieces is missing.
         *
         * Null rather than an exception: "not downloaded yet" is an ordinary state on a fresh
         * install, and the caller's response to it is to use something else, not to fail.
         */
        fun openOrNull(context: Context): KokoroEngine? {
            if (!KokoroInstall.isModelInstalled(context)) return null

            val tokenizer = KokoroTokenizer.load(KokoroInstall.tokenizerFile(context)) ?: return null
            val phonemizer = KokoroPhonemizer.load(KokoroInstall.lexiconFile(context))
                ?: run {
                    PrismLogger.logWarning(TAG, "Kokoro is installed but its lexicon is not")
                    return null
                }

            return runCatching {
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(
                        Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    )
                }
                val session = environment.createSession(
                    KokoroInstall.modelFile(context).absolutePath, options,
                )
                PrismLogger.logInfo(
                    TAG,
                    "Kokoro loaded — inputs ${session.inputNames}, outputs ${session.outputNames}",
                )
                KokoroEngine(
                    session, environment, tokenizer, phonemizer, KokoroInstall.voicesDir(context),
                )
            }.getOrElse {
                PrismLogger.logError(TAG, "Could not open Kokoro", it)
                null
            }
        }
    }
}
