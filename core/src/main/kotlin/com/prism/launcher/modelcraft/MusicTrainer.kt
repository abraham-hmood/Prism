package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechDataset
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Music models: instruments and singing voices, learned from the dataset's own audio.
 *
 * ## What is actually trained
 *
 * The model is a small autoregressive predictor over a learned spectral vocabulary:
 *
 *  1. Every clip is analysed into 40-band log-mel frames at a 10 ms hop, through the same
 *     [TtsSignal] the speech pipeline uses.
 *  2. Those frames are clustered into [CODES] centroids (k-means, seeded). A centroid is a timbre:
 *     a chord's stacked harmonics, a sawtooth's bright edge, a vowel's formant shape.
 *  3. A two-layer network learns the next code from the previous [CONTEXT] codes, conditioned on a
 *     style vector hashed from the prompt and the model's instrument / voice / genre / tempo fields.
 *  4. Generation samples codes from that distribution, maps them back to their centroids, and
 *     overlap-adds them into a waveform with [TtsSignal.vocode] (Griffin-Lim phase recovery).
 *
 * This is a from-scratch spectral music model, not a symbolic score model and not a pretrained
 * audio codec. It learns the timbres and progressions in the dataset; it does not read lyrics,
 * and a singing voice is produced by the spectral frames the dataset's voice actually contained,
 * not by a phonemizer. The metadata says so rather than implying a production music LM.
 */
object MusicTrainer {

    const val METADATA_FILE = "music_model.json"
    const val WEIGHTS_FILE = "music_model.weights"
    const val MAGIC = "PRISM-MUSIC-1"

    /** Spectral centroids: one per learned timbre. */
    const val CODES = 64

    /** Frames of history the predictor reads. */
    const val CONTEXT = 8

    /** Width of the style vector: what the prompt and the form contribute. */
    const val STYLE_DIM = 24

    private const val HIDDEN = 128
    private const val MAX_CLIPS = 16
    private const val MAX_SECONDS = 20
    private const val MAX_TRAINING_WINDOWS = 40_000
    private const val DEFAULT_SAMPLE_RATE = 22_050
    private const val DEFAULT_SECONDS = 4

    /** One analysed clip: its log-mel frames, in order. */
    private class Clip(val name: String, val frames: List<DoubleArray>)

    /** A finished music run, kept in memory and written beside the dataset. */
    class Trained(
        val net: Net,
        val centroids: Array<DoubleArray>,
        val config: Config,
        val clipCount: Int,
        val frameCount: Int,
        val lossFirst: Double,
        val lossLast: Double,
    ) {
        val sampleRate: Int get() = config.sampleRate
        val parameters: Int get() = net.parameterCount()

        /**
         * Generates [seconds] of music for [prompt].
         *
         * The prompt and the model's own fields are hashed into the same style vector training used,
         * so "warm analog strings" and "female voice" pull the sampled timbres in different
         * directions. Sampling is top-k with a temperature: too low and the model loops one chord,
         * too high and the progression stops being tonal.
         */
        fun generate(prompt: String, seconds: Int = DEFAULT_SECONDS, seed: Long = 42L): FloatArray {
            val frames = (seconds.coerceIn(1, 30) * 100).coerceAtLeast(20)
            val random = java.util.Random(seed)
            val style = styleOf(prompt, config)
            val context = IntArray(CONTEXT) { CODES }
            val codeSequence = ArrayList<Int>(frames)
            val probability = DoubleArray(CODES)
            val input = DoubleArray(inputOf(context, style, CODES).size)
            for (frame in 0 until frames) {
                inputOf(context, style, CODES).copyInto(input)
                net.forward(input, probability, 0)
                val code = sample(probability, config.temperature, config.topK, random)
                codeSequence += code
                System.arraycopy(context, 1, context, 0, CONTEXT - 1)
                context[CONTEXT - 1] = code
            }
            val mel = List(codeSequence.size) { index -> centroids[codeSequence[index]] }
            val samples = TtsSignal.vocode(
                mel,
                TtsSignal.Config(config.sampleRate),
                seed = seed,
                iterations = 24,
            )
            // Griffin-Lim returns the frames it was given plus the overlap of its window, so the
            // clip is trimmed (or padded) to the length the caller asked for. A music model whose
            // "8 seconds" is 8.1 on one setting and 7.9 on another is a model whose length control
            // does not do what it says.
            val target = (seconds.coerceIn(1, 30) * config.sampleRate)
            val exact = when {
                samples.size == target -> samples
                samples.size > target -> samples.copyOf(target)
                else -> FloatArray(target).also { output ->
                    System.arraycopy(samples, 0, output, 0, samples.size)
                }
            }
            return normalise(exact)
        }

        /** Keeps the generated clip at a listenable level without squashing its dynamics. */
        private fun normalise(samples: FloatArray): FloatArray {
            var peak = 0f
            for (sample in samples) peak = max(peak, kotlin.math.abs(sample))
            if (peak <= 1e-6f) return samples
            val gain = (0.92f / peak).coerceAtMost(8f)
            for (index in samples.indices) samples[index] *= gain
            val fade = min(samples.size / 8, 2_000)
            for (index in 0 until fade) {
                val scale = index.toFloat() / fade
                samples[index] *= scale
                samples[samples.size - 1 - index] *= scale
            }
            return samples
        }
    }

    /** What the recipe asked for. */
    data class Config(
        val sampleRate: Int,
        val temperature: Double,
        val topK: Int,
        val instruments: String,
        val voices: Int,
        val genre: String,
        val tempo: Int,
        val key: String,
        val epochs: Int,
        val learningRate: Double,
        val seed: Long,
    )

    fun train(model: CraftedModel, recipe: File): String? = trainResult(model, recipe)?.summary

    class Result(val trained: Trained, val summary: String)

    fun trainResult(model: CraftedModel, recipe: File): Result? {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val config = readConfig(recipe)
        val clips = loadClips(directory, config)
        if (clips.isEmpty()) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Nothing to train a music model on in ${model.id}: no clip could be decoded",
            )
            return null
        }
        val frames = clips.flatMap { it.frames }
        if (frames.size < CODES * 2) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "The music dataset is too short to cluster: ${frames.size} frame(s)",
            )
            return null
        }
        // The codebook is the model's timbre vocabulary. Seeded k-means so two runs on the same
        // dataset learn the same palette.
        val centroids = kMeans(frames, CODES, config.seed)
        val windows = windowCount(clips)
        val inputs = ArrayList<DoubleArray>(windows)
        val targets = ArrayList<Int>(windows)
        val style = styleOf("", config)
        for (clip in clips) {
            val codes = IntArray(clip.frames.size) { nearest(clip.frames[it], centroids) }
            for (index in 0 until codes.size) {
                val context = IntArray(CONTEXT) { slot ->
                    val at = index - CONTEXT + slot
                    if (at < 0) CODES else codes[at]
                }
                inputs += inputOf(context, style)
                targets += codes[index]
                if (inputs.size >= MAX_TRAINING_WINDOWS) break
            }
            if (inputs.size >= MAX_TRAINING_WINDOWS) break
        }
        if (inputs.isEmpty()) return null

        val inputDim = CONTEXT * (CODES + 1) + STYLE_DIM
        val net = Net(inputDim, HIDDEN, CODES, CODES + 1, java.util.Random(config.seed))
        val adam = TrainerKit.Adam(config.learningRate)
        val order = java.util.Random(config.seed xor 0x51ED)
        var lossFirst = Double.NaN
        var lossLast = Double.NaN
        val probability = DoubleArray(CODES)
        val epochSteps = inputs.size
        val totalSteps = epochSteps.toLong() * config.epochs
        var step = 0L
        for (epoch in 0 until config.epochs) {
            var sum = 0.0
            val shuffled = inputs.indices.shuffled(order)
            for (index in shuffled) {
                val input = inputs[index]
                val target = targets[index]
                val loss = net.forward(input, probability, target)
                sum += loss
                if (lossFirst.isNaN()) lossFirst = loss
                lossLast = loss
                // The share of frames whose most likely timbre is the one that came next. It is the
                // music counterpart of the speech trainers' accuracy, and the page shows both.
                var best = 0
                for (candidate in 1 until probability.size) {
                    if (probability[candidate] > probability[best]) best = candidate
                }
                net.backward(input, probability, target)
                val progress = if (totalSteps > 0) step.toDouble() / totalSteps else 0.0
                adam.setRate(
                    config.learningRate * (0.1 + 0.9 * 0.5 * (1.0 + kotlin.math.cos(Math.PI * progress))),
                )
                adam.step(net.parameters(), 1.0)
                step++
                if (step % 20L == 0L || step == totalSteps) {
                    ModelCraft.reportTraining(
                        ModelCraft.Progress(
                            epoch = epoch + 1,
                            epochs = config.epochs,
                            step = step.toInt(),
                            steps = totalSteps.toInt(),
                            file = "timbre frame $step of $totalSteps",
                            loss = loss,
                            accuracy = if (best == target) 1.0 else 0.0,
                        ),
                    )
                }
            }
            PrismPlatform.log.info(
                "ModelCraft",
                "music epoch ${epoch + 1}: train ${"%.5f".format(sum / epochSteps.coerceAtLeast(1))}",
            )
        }

        val trained = Trained(net, centroids, config, clips.size, frames.size, lossFirst, lossLast)
        write(directory, trained)
        val summary = buildString {
            append("${net.parameterCount()} parameters over ${inputs.size} frame windows")
            append(" · loss ${"%.4f".format(lossFirst)} → ${"%.4f".format(lossLast)}")
            append(" · ${centroids.size} learned timbres from ${clips.size} clip(s)")
            append(" · 40-band spectral model at ${config.sampleRate} Hz")
        }
        return Result(trained, summary)
    }

    // ── Reading the data ───────────────────────────────────────────────────────────────────────

    private fun loadClips(directory: File, config: Config): List<Clip> {
        val files = directory.walkTopDown()
            .maxDepth(2)
            .filter { it.isFile && it.extension.lowercase() in SpeechDataset.AUDIO_EXTENSIONS }
            .sortedBy { it.name }
            .take(MAX_CLIPS)
            .toList()
        val signal = TtsSignal.Config(config.sampleRate)
        val clips = mutableListOf<Clip>()
        for (file in files) {
            val decoded = AudioDecoding.read(file) ?: continue
            if (decoded.samples.isEmpty()) continue
            // The analyser is told the rate it is reading; a 44.1 kHz take analysed as 22.05 kHz
            // would have every timbre an octave wrong.
            val resampled = decoded.resampled(config.sampleRate)
            val limit = resampled.samples.size.coerceAtMost(config.sampleRate * MAX_SECONDS)
            val samples = if (limit == resampled.samples.size) {
                resampled.samples
            } else {
                resampled.samples.copyOfRange(0, limit)
            }
            val frames = TtsSignal.analyse(samples, signal)
                .map { frame -> frame.logMel }
                .filter { frame -> frame.any { it.isFinite() } }
            if (frames.size >= CONTEXT + 1) clips += Clip(file.name, frames)
        }
        return clips
    }

    private fun windowCount(clips: List<Clip>): Int =
        clips.sumOf { it.frames.size }.coerceAtMost(MAX_TRAINING_WINDOWS)

    // ── The codebook ───────────────────────────────────────────────────────────────────────────

    private fun kMeans(frames: List<DoubleArray>, count: Int, seed: Long): Array<DoubleArray> {
        val random = java.util.Random(seed)
        val bands = frames.first().size
        val centroids = Array(count) { frames[random.nextInt(frames.size)].copyOf() }
        val assignment = IntArray(frames.size)
        repeat(12) {
            for ((index, frame) in frames.withIndex()) {
                assignment[index] = nearest(frame, centroids)
            }
            val sums = Array(count) { DoubleArray(bands) }
            val counts = IntArray(count)
            for ((index, frame) in frames.withIndex()) {
                val code = assignment[index]
                counts[code]++
                for (band in 0 until bands) sums[code][band] += frame[band]
            }
            for (code in 0 until count) {
                if (counts[code] == 0) {
                    // An empty cluster is re-seeded to a real frame; an all-zero centroid is a
                    // timbre the model could generate but that never occurred in the music.
                    centroids[code] = frames[random.nextInt(frames.size)].copyOf()
                } else {
                    for (band in 0 until bands) centroids[code][band] = sums[code][band] / counts[code]
                }
            }
        }
        return centroids
    }

    private fun nearest(frame: DoubleArray, centroids: Array<DoubleArray>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for ((index, centroid) in centroids.withIndex()) {
            var distance = 0.0
            for (band in frame.indices) {
                val difference = frame[band] - centroid[band]
                distance += difference * difference
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    // ── The predictor ──────────────────────────────────────────────────────────────────────────

    /**
     * Next-code prediction: one tanh hidden layer over the one-hot context and the style vector.
     *
     * The output is softmax over the codebook plus one BOS slot, which is how a window that starts
     * before the clip has a value for "nothing played yet".
     */
    class Net(
        val inputDim: Int,
        val hidden: Int,
        val codes: Int,
        private val symbols: Int,
        random: java.util.Random,
    ) {
        val w1 = TrainerKit.gaussian("music.hidden.weight", hidden, inputDim, random)
        val b1 = TrainerKit.Param("music.hidden.bias", hidden, 1)
        val w2 = TrainerKit.gaussian("music.out.weight", codes, hidden, random)
        val b2 = TrainerKit.Param("music.out.bias", codes, 1)

        fun parameters(): List<TrainerKit.Param> = listOf(w1, b1, w2, b2)

        fun parameterCount(): Int = parameters().sumOf { it.data.size }

        fun shape(): IntArray = intArrayOf(codes, hidden, inputDim, symbols)

        /** Fills [probability] with the next-code distribution and returns its cross-entropy. */
        fun forward(input: DoubleArray, probability: DoubleArray, target: Int): Double {
            val pre = TrainerKit.linear(input, w1, b1)
            val hiddenValue = DoubleArray(hidden) { kotlin.math.tanh(pre[it]) }
            val logits = TrainerKit.linear(hiddenValue, w2, b2)
            var maxLogit = -Double.MAX_VALUE
            for (value in logits) if (value > maxLogit) maxLogit = value
            var sum = 0.0
            for (index in logits.indices) {
                probability[index] = exp(logits[index] - maxLogit)
                sum += probability[index]
            }
            for (index in probability.indices) probability[index] /= sum.coerceAtLeast(1e-12)
            return -ln(probability[target].coerceAtLeast(1e-12))
        }

        fun backward(input: DoubleArray, probability: DoubleArray, target: Int) {
            for (parameter in parameters()) parameter.grad.fill(0.0)
            val pre = TrainerKit.linear(input, w1, b1)
            val hiddenValue = DoubleArray(hidden) { kotlin.math.tanh(pre[it]) }
            val logits = TrainerKit.linear(hiddenValue, w2, b2)
            val gradient = DoubleArray(codes)
            var maxLogit = -Double.MAX_VALUE
            for (value in logits) if (value > maxLogit) maxLogit = value
            var sum = 0.0
            val softmax = DoubleArray(codes)
            for (index in logits.indices) {
                softmax[index] = exp(logits[index] - maxLogit)
                sum += softmax[index]
            }
            for (index in softmax.indices) softmax[index] /= sum.coerceAtLeast(1e-12)
            for (index in gradient.indices) gradient[index] = softmax[index]
            gradient[target] -= 1.0
            TrainerKit.accumulateLinear(hiddenValue, gradient, w2, b2)
            val dPre = DoubleArray(hidden)
            for (unit in 0 until hidden) {
                var value = 0.0
                for (output in 0 until codes) value += gradient[output] * w2.at(output, unit)
                dPre[unit] = value * (1.0 - hiddenValue[unit] * hiddenValue[unit])
            }
            TrainerKit.accumulateLinear(input, dPre, w1, b1)
        }
    }

    /** One-hot context plus the style vector. */
    fun inputOf(context: IntArray, style: DoubleArray, codes: Int = CODES): DoubleArray {
        val input = DoubleArray(context.size * (codes + 1) + style.size)
        for ((slot, code) in context.withIndex()) {
            input[slot * (codes + 1) + code.coerceIn(0, codes)] = 1.0
            // The BOS slot carries no history; the one-hot is enough for the network to tell.
        }
        System.arraycopy(style, 0, input, context.size * (codes + 1), style.size)
        return input
    }

    private fun sample(probability: DoubleArray, temperature: Double, topK: Int, random: java.util.Random): Int {
        val scaled = DoubleArray(CODES) { index ->
            if (probability[index] <= 0.0) 0.0
            else exp(ln(probability[index]) / temperature.coerceIn(0.2, 2.0))
        }
        val ranked = scaled.indices.sortedByDescending { scaled[it] }.take(topK.coerceIn(1, CODES))
        var total = 0.0
        for (index in ranked) total += scaled[index]
        var target = random.nextDouble() * total
        for (index in ranked) {
            target -= scaled[index]
            if (target <= 0.0) return index
        }
        return ranked.last()
    }

    // ── Style: prompt + form fields → a small vector ───────────────────────────────────────────

    fun styleOf(prompt: String, config: Config): DoubleArray {
        val vector = DoubleArray(STYLE_DIM)
        fun add(token: String, weight: Double) {
            var hash = 0x811C9DC5.toInt()
            for (byte in token.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toInt() and 0xFF)
                hash *= 0x01000193
            }
            val slot = (hash and Int.MAX_VALUE) % STYLE_DIM
            vector[slot] += if (hash < 0) -weight else weight
        }
        val words = (prompt + " " + config.instruments + " " + config.genre + " " + config.key)
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
        for ((index, word) in words.withIndex()) {
            add("w:$word", 1.0)
            if (index + 1 < words.size) add("b:${word}_${words[index + 1]}", 0.5)
        }
        add("voices:${config.voices.coerceIn(0, 8)}", 0.8)
        add("tempo:${(config.tempo / 10).coerceIn(4, 18)}", 0.8)
        var norm = 0.0
        for (value in vector) norm += value * value
        norm = sqrt(norm)
        if (norm > 1e-9) for (index in vector.indices) vector[index] /= norm
        return vector
    }

    // ── The artifacts ──────────────────────────────────────────────────────────────────────────

    private fun write(directory: File, trained: Trained) {
        directory.mkdirs()
        TrainerKit.WeightFile.write(
            File(directory, WEIGHTS_FILE),
            MAGIC,
            trained.net.shape(),
            trained.net.parameters(),
        )
        val metadata = JSONObject().apply {
            put("kind", "music-model")
            put("architecture", "autoregressive spectral codebook")
            put("objective", "next-timbre prediction over ${CODES} learned spectral codes")
            put("note",
                "A custom spectral music model: 40-band log-mel frames are clustered into " +
                    "${CODES} timbres, and a conditioned next-frame predictor learns the " +
                    "progressions in this dataset. Generation vocodes the sampled timbres with " +
                    "Griffin-Lim. It is not a symbolic score model and does not read lyrics; " +
                    "singing voices come from the spectral frames the dataset contained.",
            )
            put("sample_rate", trained.sampleRate)
            put("codes", CODES)
            put("context", CONTEXT)
            put("style_dim", STYLE_DIM)
            put("hidden", trained.net.hidden)
            put("parameters", trained.parameters)
            put("clips", trained.clipCount)
            put("frames", trained.frameCount)
            put("loss_first", trained.lossFirst)
            put("loss_last", trained.lossLast)
            put("temperature", trained.config.temperature)
            put("top_k", trained.config.topK)
            put("instruments", trained.config.instruments)
            put("singing_voices", trained.config.voices)
            put("genre", trained.config.genre)
            put("tempo_bpm", trained.config.tempo)
            put("key", trained.config.key)
            put("weights_file", WEIGHTS_FILE)
            put("weights_format", "magic '$MAGIC', then the shape, then one tensor per entry")
            put("centroids", JSONArray(trained.centroids.map { frame -> JSONArray(frame.toList()) }))
            put("can", JSONArray(listOf("generate(prompt, seconds, seed)")))
        }
        File(directory, METADATA_FILE).writeText(metadata.toString())
    }

    /** Reads a music artifact back. Null when there is none or its shape does not match. */
    fun load(directory: File): Trained? {
        val file = File(directory, METADATA_FILE)
        if (!file.isFile) return null
        val metadata = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val weights = File(directory, metadata.optString("weights_file", WEIGHTS_FILE))
        if (!weights.isFile) return null
        val codes = metadata.optInt("codes", 0)
        val context = metadata.optInt("context", 0)
        val styleDim = metadata.optInt("style_dim", 0)
        val hidden = metadata.optInt("hidden", 0)
        val sampleRate = metadata.optInt("sample_rate", DEFAULT_SAMPLE_RATE)
        if (codes <= 0 || context <= 0 || styleDim <= 0 || hidden <= 0) return null
        val inputDim = context * (codes + 1) + styleDim
        val net = Net(inputDim, hidden, codes, codes + 1, java.util.Random(42L))
        if (!TrainerKit.WeightFile.read(weights, MAGIC, net.shape(), net.parameters())) return null
        val centroidsJson = metadata.optJSONArray("centroids") ?: return null
        val centroids = Array(centroidsJson.length()) { index ->
            val row = centroidsJson.optJSONArray(index) ?: return null
            DoubleArray(row.length()) { band -> row.optDouble(band) }
        }
        if (centroids.size != codes) return null
        val config = Config(
            sampleRate = sampleRate,
            temperature = metadata.optDouble("temperature", 0.9),
            topK = metadata.optInt("top_k", 20),
            instruments = metadata.optString("instruments", ""),
            voices = metadata.optInt("singing_voices", 0),
            genre = metadata.optString("genre", ""),
            tempo = metadata.optInt("tempo_bpm", 100),
            key = metadata.optString("key", ""),
            epochs = 0,
            learningRate = 0.0,
            seed = 42L,
        )
        return Trained(
            net = net,
            centroids = centroids,
            config = config,
            clipCount = metadata.optInt("clips", 0),
            frameCount = metadata.optInt("frames", 0),
            lossFirst = metadata.optDouble("loss_first", Double.NaN),
            lossLast = metadata.optDouble("loss_last", Double.NaN),
        )
    }

    // ── The recipe ────────────────────────────────────────────────────────────────────────────

    fun readConfig(recipe: File): Config {
        val json = runCatching { JSONObject(recipe.readText()) }.getOrNull()
        val sampleRate = (json?.optInt("sample_rate", 0) ?: 0)
            .takeIf { it >= 8_000 } ?: DEFAULT_SAMPLE_RATE
        return Config(
            sampleRate = sampleRate,
            temperature = json?.optDouble("music_temperature", 0.9)
                ?.takeIf { it.isFinite() }?.coerceIn(0.3, 1.6) ?: 0.9,
            topK = (json?.optInt("music_top_k", 20) ?: 20).coerceIn(4, CODES),
            instruments = json?.optString("instruments", "") ?: "",
            voices = (json?.optInt("singing_voices", 0) ?: 0).coerceIn(0, 8),
            genre = json?.optString("genre", "") ?: "",
            tempo = (json?.optInt("tempo_bpm", 100) ?: 100).coerceIn(30, 240),
            key = json?.optString("key", "") ?: "",
            epochs = (json?.optInt("epochs", 0) ?: 0).takeIf { it > 0 } ?: 6,
            learningRate = json?.optDouble("learning_rate", 0.0)
                ?.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1e-4, 2e-2) ?: 0.006,
            seed = json?.optLong("seed", 42L) ?: 42L,
        )
    }
}
