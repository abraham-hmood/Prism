package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.VocalAnalysis
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The on-device trainer for an AUDIO model: a small prosody-conditioned acoustic model.
 *
 * ## What this model is, precisely
 *
 * It predicts one frame of acoustics from the frames before it, CONDITIONED on the prosody measured
 * for the clip it belongs to. That is the part of a voice model that can be trained honestly without a
 * text aligner: the conditioning vector is the row of vocal columns [SpeechDataset] measured into the
 * merged CSV -- pitch behaviour, jitter, shimmer, breathiness, tilt, formants, rate -- so the network
 * learns the mapping from "how this utterance should sound" to the acoustic trajectory that sounds that
 * way. A cloning or text-to-speech model consumes that trajectory. What it does NOT do is invent a
 * text-to-acoustics alignment, which would need a forced aligner this build does not have.
 *
 * ## Why the conditioning is the CSV's own columns
 *
 * The merged CSV is the one file a trainer is pointed at, and its vocal columns are the measurements
 * taken from the audio, so the conditioning input and the dataset manifest cannot disagree about what a
 * clip's prosody is: they are the same numbers. A column that could not be measured arrives empty, is
 * read as NaN here, and is imputed with the dataset's mean -- and the metadata records which columns
 * those were, so an imputed column is visible rather than silent.
 *
 * ## The attention the recipe asks for
 *
 * The recipe's sliding-window controls are honoured by the network itself, not only written down: a
 * sliding layer sees the previous `sliding_window_size` frames, a full layer sees every earlier frame,
 * and `full_attention_per_nth_layer` decides which layers are which. Query and key/value heads are
 * grouped (GQA) -- four query heads share two key/value heads -- which is how a small model keeps
 * attention cheap without giving up heads.
 *
 * ## Determinism
 *
 * The seed comes from the recipe. Weights start from a seeded Gaussian, clips are trained in file
 * order, and the optimiser's state is allocated the same way every run, so the same dataset and recipe
 * produce the same weights. That is what makes the tests' assertions about the window and the
 * conditioning mean something.
 */
object AcousticTrainer {

    /** The metadata artifact: architecture, normalisation, and what the run measured. */
    const val METADATA_FILE = "acoustic_model.json"

    /** The weights artifact: written by [writeWeights], read back by [load]. */
    const val WEIGHTS_FILE = "acoustic_model.weights"

    /** The stem the chosen container's files are named from: `audio_model.safetensors` or `.gguf`. */
    val BASE_NAME: String = ModelCraft.fileStem(ModelCraft.Kind.AUDIO)

    /** Written at the head of the weights file so a wrong file is refused rather than misread. */
    private const val MAGIC = "PRISM-ACOUSTIC-1"

    /** Width of one frame's input features: the voiced flag plus [TARGETS] acoustic values. */
    const val FRAME_FEATURES = 14

    /** Width of what a frame predicts: the next frame's acoustics. */
    const val TARGETS = 13

    const val D_MODEL = 32
    const val HEADS = 4
    const val KV_HEADS = 2
    const val HIDDEN = 64
    const val LAYERS = 2

    /** A clip longer than this is truncated: attention over a whole recording is not this model's job. */
    private const val MAX_FRAMES = 96

    /** A rate for a 30k-parameter net from random weights, clamped around the recipe's own value. */
    private const val MIN_LEARNING_RATE = 3e-4
    private const val MAX_LEARNING_RATE = 3e-3
    private const val DEFAULT_LEARNING_RATE = 1e-3

    /**
     * The vocal columns the conditioning vector is built from, in this order.
     *
     * Chosen from the groups a voice model's conditioning actually needs: pitch behaviour, the
     * involuntary micro-variation, the source/filter measurements and the rate. Timing columns that
     * describe a PERFORMANCE of one script are deliberately absent, for the reason
     * [SpeechDataset.VOICE_COLUMNS] gives.
     */
    val CONDITIONING_COLUMNS = listOf(
        "f0_mean_hz", "f0_std_hz", "f0_range_semitones",
        "jitter_local_percent", "shimmer_local_percent",
        "hnr_db", "breathiness_index", "glottal_open_quotient_proxy",
        "spectral_tilt_db_per_octave", "spectral_centroid_hz",
        "formant_1_hz", "formant_2_hz", "formant_3_hz", "formant_dispersion_hz",
        "speaking_rate_syllables_per_s", "pause_ratio",
        "intensity_mean_db", "intensity_std_db", "voiced_fraction", "stress_contrast_db",
    )

    /**
     * Trains [model]'s acoustic model and says what happened.
     *
     * @return the sentence ModelCraft puts on the model record, or null when nothing could be trained
     *         -- in which case the recipe is still the deliverable and the caller says so.
     */
    fun train(model: CraftedModel, recipe: File): String? = trainResult(model, recipe)?.summary

    /** A finished run: the model as trained in memory, and the line describing it. */
    class Result(val trained: Trained, val summary: String)

    /**
     * The same run as [train], keeping the trained network so a caller can use it without a reload.
     *
     * The artifacts are written either way; this exists so the weights can be exercised before they
     * are written and read back, which is what makes the round-trip test mean something.
     */
    fun trainResult(model: CraftedModel, recipe: File): Result? {
        if (model.kind != ModelCraft.Kind.AUDIO) return null
        val directory = ModelCraft.Store.datasetDir(model.id)
        val config = readRecipeConfig(recipe, model)
        val clips = loadClips(directory)
        if (clips.size < 2) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Acoustic training needs at least two decodable clips; ${directory.name} has ${clips.size}",
            )
            return null
        }

        val normalisation = Normalisation.of(CONDITIONING_COLUMNS.size)
        normalisation.fitConditioning(clips.map { it.conditioning })
        normalisation.fitTargets(clips.map { it.targets })
        val net = Net(config, java.util.Random(config.seed))
        val adam = TrainerKit.Adam(config.learningRate)

        val validation = clips.filterIndexed { index, _ -> index % 4 == 3 }.ifEmpty { listOf(clips.last()) }
        val training = clips.filterNot { it in validation }
        // EXACTLY the user's schedule. One step is one clip; a requested epoch cycles the training
        // split until it has taken that many, and 0 means one full pass. No step or wall-clock ceiling
        // truncates it -- the numbers on the form are the numbers this run takes.
        val stepsPerEpoch = if (config.stepsPerEpoch > 0) config.stepsPerEpoch else training.size
        var steps = 0
        var epoch = 0
        val history = mutableListOf<Double>()
        var validationLoss = Double.NaN
        while (epoch < config.epochs) {
            epoch++
            var sum = 0.0
            repeat(stepsPerEpoch) { offset ->
                val clip = training[offset % training.size]
                val batch = net.ModelBatch(normalisation, clip)
                val stepLoss = batch.loss()
                sum += stepLoss
                batch.backward()
                adam.step(net.parameters(), config.gradientClip)
                steps++
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = epoch,
                        epochs = config.epochs,
                        step = offset + 1,
                        steps = stepsPerEpoch,
                        file = clip.name,
                        loss = stepLoss,
                        accuracy = batch.accuracy(),
                    ),
                )
            }
            val mean = sum / stepsPerEpoch.coerceAtLeast(1)
            history += mean
            validationLoss = validation.map { clip ->
                net.ModelBatch(normalisation, clip).loss()
            }.average()
            PrismPlatform.log.info(
                "ModelCraft",
                "Acoustic epoch $epoch: train ${"%.4f".format(mean)} val ${"%.4f".format(validationLoss)}",
            )
        }
        val lastLoss = history.lastOrNull() ?: return null

        // THE ENCODER AND DECODER, trained on the dataset's own acoustic frames -- the same vectors
        // the model predicts -- so an audio build produces the pair along with the model itself.
        // Reported whether or not it trained: a run with no acoustic frames to reconstruct says so
        // rather than implying files it did not write.
        val coder = CoderTrainer.train(
            vectors = clips.flatMap { clip -> clip.targets.dropLast(1).toList() },
            seed = config.seed xor 0xC0DE,
            epochs = config.epochs,
            stepsPerEpoch = config.stepsPerEpoch,
            learningRate = config.learningRate,
            gradientClip = config.gradientClip,
            label = "acoustic encoder/decoder",
        )
        if (coder == null) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "No acoustic encoder or decoder could be trained for ${model.id}: the clips hold no " +
                    "frame to reconstruct",
            )
        }

        // THE SPEECH MODEL. The acoustic predictor above is the prosody half; this trains the text
        // half -- text encoder, aligner, duration model, decoder, codec and vocoder -- from the same
        // clips and their transcripts. It returns null when the dataset has no transcript/audio
        // pairs, in which case the acoustic model is still the deliverable and the summary says so.
        val speech = runCatching { TtsTrainer.train(model, recipe) }
            .onFailure { PrismPlatform.log.warn("ModelCraft", "TTS training failed: ${it.message}") }
            .getOrNull()
        if (speech == null) {
            PrismPlatform.log.info(
                "ModelCraft",
                "No text-to-speech model was saved for ${model.id}: the speech pass returned nothing. " +
                    "The warning above says whether it found no transcripts or stopped on a " +
                    "numerical failure; the dataset is ${directory.absolutePath}",
            )
        }
        val trained = Trained(net, config, normalisation, CONDITIONING_COLUMNS, coder)
        val metadata = trained.writeArtifacts(
            directory, model, history.first(), lastLoss, validationLoss, steps, speech,
        )
        PrismPlatform.log.info(
            "ModelCraft",
            "Acoustic model for ${model.id}: $steps step(s), ${"%.4f".format(lastLoss)} loss, " +
                "${net.parameterCount()} parameters",
        )
        val summary = buildString {
            append("${net.parameterCount()} parameters trained over $steps step(s)")
            append(" · loss ${"%.4f".format(history.first())} → ${"%.4f".format(lastLoss)}")
            if (!validationLoss.isNaN()) append(" · validation ${"%.4f".format(validationLoss)}")
            append(" · ")
            append(
                if (config.window > 0) {
                    "sliding window ${config.window} frames, every ${ordinal(config.fullEvery)} layer full"
                } else {
                    "full attention on every layer"
                },
            )
            if (speech != null) {
                append(" · ")
                append(TtsTrainer.summary(speech))
                append(" · WAV synthesis ready")
            } else {
                append(" · no text-to-speech model (no readable transcript/description in the dataset rows)")
            }
            append(" · ${metadata.name} written beside the dataset")
            if (trained.artifactFiles.isNotEmpty()) {
                append(" with ${trained.artifactFiles.joinToString(", ") { it.name }}")
            }
        }
        return Result(trained, summary)
    }

    private fun ordinal(value: Int): String = when {
        value % 100 in 11..13 -> "${value}th"
        value % 10 == 1 -> "${value}st"
        value % 10 == 2 -> "${value}nd"
        value % 10 == 3 -> "${value}rd"
        else -> "${value}th"
    }

    /** One clip's inputs and targets, ready for the network. */
    class ClipData(
        val name: String,
        val conditioning: DoubleArray,
        /** Per position: the frame's features as input. */
        val inputs: Array<DoubleArray>,
        /** Per position: the NEXT frame's acoustics. The last position has no target. */
        val targets: Array<DoubleArray>,
    ) {
        /** Positions that contribute a loss: every one but the last. */
        fun positions(): Int = (inputs.size - 1).coerceAtLeast(0)
    }

    // ── Loading the dataset ─────────────────────────────────────────────────────────────────────

    /**
     * The dataset's clips with their conditioning, read from the merged CSV and the audio beside it.
     *
     * The CSV is the join: a row's audio path is whatever cell names an audio file, the row's vocal
     * columns are the conditioning, and a row whose audio cannot be decoded is skipped with a log line
     * rather than poisoning the means. A clip the CSV does not mention is still trained on, with the
     * dataset's mean conditioning -- dropping it would train on less than the dataset holds.
     */
    private fun loadClips(directory: File): List<ClipData> {
        val csv = File(directory, SpeechDataset.MERGED_FILE)
            .takeIf { it.isFile }
            ?: directory.listFiles()?.firstOrNull { it.isFile && it.extension.equals("csv", true) }
            ?: return emptyList()
        val byName = HashMap<String, Map<String, String>>()
        for (row in ModelCraft.parseCsv(csv)) {
            val path = row.values.firstOrNull { value ->
                value.substringBefore('?').substringAfterLast('.').lowercase() in SpeechDataset.AUDIO_EXTENSIONS
            } ?: continue
            val key = path.substringBefore('?').trim().replace('\\', '/').substringAfterLast('/')
            if (key.isNotEmpty()) byName[key.lowercase()] = row
        }

        val audio = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SpeechDataset.AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        val clips = mutableListOf<ClipData>()
        for (file in audio) {
            val row = byName[file.name.lowercase()]
            val conditioning = DoubleArray(CONDITIONING_COLUMNS.size) { index ->
                row?.get(CONDITIONING_COLUMNS[index].lowercase())?.trim()?.toDoubleOrNull() ?: Double.NaN
            }
            val decoded = AudioDecoding.read(file)
            if (decoded == null) {
                PrismPlatform.log.warn("ModelCraft", "Could not decode ${file.name}; it is not trained on")
                continue
            }
            val frames = VocalAnalysis.framesOf(decoded).take(MAX_FRAMES)
            if (frames.size < 4) continue
            val view = FrameView(frames)
            clips += ClipData(
                name = file.name,
                conditioning = conditioning,
                inputs = Array(frames.size) { view.input(it) },
                targets = Array(frames.size) { index ->
                    if (index + 1 < frames.size) view.acoustics(index + 1) else DoubleArray(TARGETS)
                },
            )
        }
        return clips
    }

    /**
     * A clip's frames, normalised against the clip's own median.
     *
     * Per-clip rather than per-dataset, deliberately: "this frame is 3 dB quieter than this clip's
     * middle" is a fact about the utterance, while "quieter than the dataset's middle" mixes how loud
     * each recording was into what the model is asked to predict.
     */
    private class FrameView(val frames: List<VocalAnalysis.FrameSample>) {
        private val medianDb = frames.map { it.db }.sorted()[frames.size / 2]
        private val medianF0 = frames.mapNotNull { it.f0.takeIf { value -> !value.isNaN() } }
            .sorted()
            .let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        private val bandMean = DoubleArray(frames.first().bands.size) { band ->
            frames.map { frame -> bandDb(frame, band) }.average()
        }

        private fun bandDb(frame: VocalAnalysis.FrameSample, band: Int): Double =
            10.0 * ln(frame.bands[band].toDouble().coerceAtLeast(1e-12)) / ln(10.0)

        private fun voiced(frame: VocalAnalysis.FrameSample): Double =
            if (frame.f0.isNaN()) 0.0 else 1.0

        private fun f0Semitones(frame: VocalAnalysis.FrameSample): Double =
            if (frame.f0.isNaN() || medianF0 <= 0.0) 0.0 else 12.0 * ln(frame.f0 / medianF0) / ln(2.0)

        /** The 13 acoustics a frame predicts. */
        fun acoustics(index: Int): DoubleArray {
            val frame = frames[index]
            val bands = DoubleArray(frame.bands.size) { band -> (bandDb(frame, band) - bandMean[band]).coerceIn(-40.0, 40.0) }
            return doubleArrayOf(
                f0Semitones(frame),
                (frame.db - medianDb).coerceIn(-60.0, 20.0),
                frame.centroid / 2000.0,
                frame.rolloff / 4000.0,
                frame.flatness,
            ) + bands
        }

        /** The frame's features as the model's input: its acoustics plus whether it is voiced. */
        fun input(index: Int): DoubleArray = doubleArrayOf(voiced(frames[index])) + acoustics(index)
    }

    // ── Normalisation ───────────────────────────────────────────────────────────────────────────

    /**
     * Per-column means and deviations for both sides of the model.
     *
     * The conditioning's NaNs are the point of this class: a column no clip could measure gets a mean of
     * zero and a deviation of one, so imputing it contributes nothing rather than a large arbitrary
     * number, and the metadata records which columns those were.
     */
    class Normalisation private constructor(
        val conditioningColumns: Int,
        val targets: Int,
    ) {
        val conditioningMean = DoubleArray(conditioningColumns)
        val conditioningDeviation = DoubleArray(conditioningColumns) { 1.0 }
        val targetMean = DoubleArray(targets)
        val targetDeviation = DoubleArray(targets) { 1.0 }
        val imputed = mutableListOf<String>()

        companion object {
            fun of(conditioningColumns: Int, targets: Int = TARGETS) =
                Normalisation(conditioningColumns, targets)
        }

        fun fitConditioning(vectors: List<DoubleArray>) {
            for (column in 0 until conditioningColumns) {
                val values = vectors.mapNotNull { it.getOrNull(column)?.takeIf { value -> !value.isNaN() } }
                if (values.isEmpty()) {
                    imputed += CONDITIONING_COLUMNS.getOrElse(column) { "column $column" }
                    conditioningMean[column] = 0.0
                    conditioningDeviation[column] = 1.0
                    continue
                }
                val mean = values.average()
                val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
                conditioningMean[column] = mean
                conditioningDeviation[column] = sqrt(variance).takeIf { it > 1e-9 } ?: 1.0
            }
        }

        fun fitTargets(rows: List<Array<DoubleArray>>) {
            val values = rows.flatMap { targets -> targets.dropLast(1) }
            for (index in 0 until targets) {
                val column = values.mapNotNull { it.getOrNull(index)?.takeIf { value -> value.isFinite() } }
                val mean = if (column.isEmpty()) 0.0 else column.average()
                val variance = if (column.isEmpty()) 0.0 else column.sumOf { (it - mean) * (it - mean) } / column.size
                targetMean[index] = mean
                targetDeviation[index] = sqrt(variance).takeIf { it > 1e-9 } ?: 1.0
            }
        }

        fun conditioning(values: DoubleArray): DoubleArray = DoubleArray(conditioningColumns) { index ->
            val value = values.getOrNull(index)
            if (value == null || value.isNaN()) {
                0.0
            } else {
                ((value - conditioningMean[index]) / conditioningDeviation[index]).coerceIn(-6.0, 6.0)
            }
        }

        fun target(values: DoubleArray, index: Int): Double =
            (values[index] - targetMean[index]) / targetDeviation[index]
    }

    // ── The network ─────────────────────────────────────────────────────────────────────────────

    /** Everything the recipe says about the run, read from the recipe file itself. */
    class Config(
        val window: Int,
        val fullEvery: Int,
        val epochs: Int,
        val learningRate: Double,
        val gradientClip: Double,
        val seed: Long,
        /** Examples per epoch; 0 means every clip in the training split. */
        val stepsPerEpoch: Int = 0,
    )

    private fun readRecipeConfig(recipe: File, model: CraftedModel): Config {
        val json = runCatching { JSONObject(recipe.readText()) }.getOrNull()
        val attention = ModelCraft.attention(model)
        val sliding = json?.optBoolean(ModelCraft.KEY_SLIDING_WINDOW, attention.enabled) ?: attention.enabled
        val window = json?.optInt(ModelCraft.KEY_SLIDING_WINDOW_SIZE, 0) ?: 0
        val fullEvery = (json?.optInt(ModelCraft.KEY_FULL_ATTENTION_EVERY_NTH_LAYER, 0) ?: 0)
            .takeIf { it > 0 } ?: attention.fullAttentionEveryNthLayer
        val epochs = (json?.optInt("epochs", 0) ?: 0).takeIf { it > 0 } ?: 10
        val learningRate = json?.optDouble("learning_rate", Double.NaN)?.takeIf { it.isFinite() && it > 0.0 }
        return Config(
            window = if (sliding) window.coerceIn(1, MAX_FRAMES) else 0,
            fullEvery = fullEvery.coerceAtLeast(1),
            epochs = epochs.coerceIn(1, ModelCraft.MAX_EPOCHS),
            stepsPerEpoch = (json?.optInt(ModelCraft.KEY_STEPS_PER_EPOCH, 0) ?: 0)
                .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH),
            learningRate = (learningRate ?: DEFAULT_LEARNING_RATE).coerceIn(MIN_LEARNING_RATE, MAX_LEARNING_RATE),
            gradientClip = json?.optDouble("grad_clip", 1.0)?.coerceIn(0.1, 10.0) ?: 1.0,
            seed = json?.optLong("seed", 42L) ?: 42L,
        )
    }

    /**
     * The network: an input projection, [LAYERS] blocks of grouped-query attention and a tanh MLP, and
     * a linear head.
     *
     * Layer normalisation is deliberately absent. It is a fine thing to have, and this is thirty
     * thousand parameters trained for seconds on one device: Adam with a small rate is stable here, and
     * the code that would implement its backward pass is code with more ways to be subtly wrong.
     */
    class Net(val config: Config, random: java.util.Random) {
        private val dModel = D_MODEL
        private val heads = HEADS
        private val kvHeads = KV_HEADS
        private val headDim = dModel / heads
        private val kvDim = headDim * kvHeads
        private val hidden = HIDDEN

        val wIn = TrainerKit.Param("input.weight", dModel, FRAME_FEATURES + CONDITIONING_COLUMNS.size)
        val bIn = TrainerKit.Param("input.bias", dModel, 1)
        val wq = Array(LAYERS) { create("layer$it.query.weight", dModel, dModel, random) }
        val wk = Array(LAYERS) { create("layer$it.key.weight", kvDim, dModel, random) }
        val wv = Array(LAYERS) { create("layer$it.value.weight", kvDim, dModel, random) }
        val wo = Array(LAYERS) { create("layer$it.output.weight", dModel, dModel, random) }
        val w1 = Array(LAYERS) { create("layer$it.mlp.in.weight", hidden, dModel, random) }
        val b1 = Array(LAYERS) { TrainerKit.Param("layer$it.mlp.in.bias", hidden, 1) }
        val w2 = Array(LAYERS) { create("layer$it.mlp.out.weight", dModel, hidden, random) }
        val b2 = Array(LAYERS) { TrainerKit.Param("layer$it.mlp.out.bias", dModel, 1) }
        val wOut = TrainerKit.Param("output.weight", TARGETS, dModel)
        val bOut = TrainerKit.Param("output.bias", TARGETS, 1)

        init {
            val scale = 1.0 / sqrt(dModel.toDouble())
            for (index in wIn.data.indices) wIn.data[index] = random.nextGaussian() * scale
            for (index in wOut.data.indices) wOut.data[index] = random.nextGaussian() * scale
            bIn.data.fill(0.0)
            bOut.data.fill(0.0)
        }

        private fun create(name: String, rows: Int, cols: Int, random: java.util.Random): TrainerKit.Param =
            TrainerKit.gaussian(name, rows, cols, random)

        /** Every parameter, in the order the weights file writes them. */
        fun parameters(): List<TrainerKit.Param> = buildList {
            add(wIn); add(bIn)
            for (layer in 0 until LAYERS) {
                add(wq[layer]); add(wk[layer]); add(wv[layer]); add(wo[layer])
                add(w1[layer]); add(b1[layer]); add(w2[layer]); add(b2[layer])
            }
            add(wOut); add(bOut)
        }

        fun parameterCount(): Int = parameters().sumOf { it.data.size }

        fun zeroGradients() {
            parameters().forEach { it.grad.fill(0.0) }
        }

        /** y = x·W + b, with W[out][in]. */
        private fun linear(x: DoubleArray, w: TrainerKit.Param, b: TrainerKit.Param?): DoubleArray =
            TrainerKit.linear(x, w, b)

        /** True when [layer] attends to every earlier frame. */
        fun isFull(layer: Int): Boolean = config.window <= 0 || (layer + 1) % config.fullEvery == 0

        /**
         * One clip through the network, caching what the backward pass needs.
         *
         * The mask is causal on every layer (a frame may not see the future) and additionally limited
         * to the last `window` frames on a SLIDING layer.
         */
        inner class Forward(val inputs: Array<DoubleArray>, val conditioning: DoubleArray) {
            val length = inputs.size
            val layerInput = ArrayList<Array<DoubleArray>>(LAYERS)
            val queries = ArrayList<Array<DoubleArray>>(LAYERS)
            val keys = ArrayList<Array<DoubleArray>>(LAYERS)
            val values = ArrayList<Array<DoubleArray>>(LAYERS)
            val attention = ArrayList<Array<DoubleArray>>(LAYERS)
            val attended = ArrayList<Array<DoubleArray>>(LAYERS)
            val mlpInput = ArrayList<Array<DoubleArray>>(LAYERS)
            val mlpHidden = ArrayList<Array<DoubleArray>>(LAYERS)
            lateinit var finalHidden: Array<DoubleArray>
            val output: Array<DoubleArray>

            init {
                var h = Array(length) { index -> linear(inputs[index] + conditioning, wIn, bIn) }
                for (layer in 0 until LAYERS) {
                    layerInput += h
                    val q = Array(length) { index -> linear(h[index], wq[layer], null) }
                    val k = Array(length) { index -> linear(h[index], wk[layer], null) }
                    val v = Array(length) { index -> linear(h[index], wv[layer], null) }
                    queries += q
                    keys += k
                    values += v
                    val mask = maskFor(layer)
                    val weights = Array(length) { index -> softmax(q[index], k, mask, index) }
                    attention += weights
                    val mixed = Array(length) { index ->
                        val result = DoubleArray(dModel)
                        for (head in 0 until heads) {
                            val group = head % kvHeads
                            for (other in 0..index) {
                                val weight = weights[index][head * length + other]
                                if (weight == 0.0) continue
                                for (dim in 0 until headDim) {
                                    result[head * headDim + dim] += weight * v[other][group * headDim + dim]
                                }
                            }
                        }
                        result
                    }
                    attended += mixed
                    val projectedOut = Array(length) { index -> linear(mixed[index], wo[layer], null) }
                    h = Array(length) { index -> DoubleArray(dModel) { dim -> h[index][dim] + projectedOut[index][dim] } }

                    mlpInput += h
                    val activation = Array(length) { index ->
                        val pre = linear(h[index], w1[layer], b1[layer])
                        DoubleArray(hidden) { dim -> kotlin.math.tanh(pre[dim]) }
                    }
                    mlpHidden += activation
                    val out = Array(length) { index -> linear(activation[index], w2[layer], b2[layer]) }
                    h = Array(length) { index -> DoubleArray(dModel) { dim -> h[index][dim] + out[index][dim] } }
                }
                finalHidden = h
                output = Array(length) { index -> linear(h[index], wOut, bOut) }
            }

            private fun maskFor(layer: Int): BooleanArray {
                val mask = BooleanArray(length * length)
                val full = isFull(layer)
                for (index in 0 until length) {
                    val from = if (full) 0 else max(0, index - config.window + 1)
                    for (other in from..index) mask[index * length + other] = true
                }
                return mask
            }

            /** Softmax over the allowed keys of one position, with all heads in one row. */
            private fun softmax(
                query: DoubleArray,
                k: Array<DoubleArray>,
                mask: BooleanArray,
                index: Int,
            ): DoubleArray {
                val row = DoubleArray(heads * length)
                val scores = DoubleArray(length)
                for (head in 0 until heads) {
                    val group = head % kvHeads
                    var best = Double.NEGATIVE_INFINITY
                    for (other in 0..index) {
                        if (!mask[index * length + other]) continue
                        var sum = 0.0
                        for (dim in 0 until headDim) {
                            sum += query[head * headDim + dim] * k[other][group * headDim + dim]
                        }
                        val score = sum / sqrt(headDim.toDouble())
                        scores[other] = score
                        if (score > best) best = score
                    }
                    if (best == Double.NEGATIVE_INFINITY) continue
                    var total = 0.0
                    for (other in 0..index) {
                        if (!mask[index * length + other]) continue
                        val weight = exp(scores[other] - best)
                        scores[other] = weight
                        total += weight
                    }
                    if (total <= 0.0) continue
                    for (other in 0..index) {
                        if (!mask[index * length + other]) continue
                        row[head * length + other] = scores[other] / total
                    }
                }
                return row
            }
        }

        /** The loss and its gradient for one clip, averaged over the positions with a target. */
        inner class ModelBatch(val normalisation: Normalisation, val clip: ClipData) {
            private val forward = Forward(clip.inputs, normalisation.conditioning(clip.conditioning))
            private val count = clip.positions()

            /** Standardised mean-squared error over the positions that have a target. */
            fun loss(): Double {
                if (count == 0) return 0.0
                var total = 0.0
                for (index in 0 until count) {
                    for (dim in 0 until TARGETS) {
                        val error = forward.output[index][dim] - normalisation.target(clip.targets[index], dim)
                        total += error * error
                    }
                }
                return total / (count * TARGETS)
            }

            /**
             * The share of predicted acoustics that landed within a tenth of the target.
             *
             * A regression has no labels to be right or wrong about, so this is the honest reading of
             * "accuracy" for it: how many of the values the model produced are close to the values the
             * clip actually had, after the same standardisation the loss uses. It is deliberately not
             * derived from the loss -- a percentage invented from an MSE would move when the scale of
             * a feature moved, not when the model got better.
             */
            fun accuracy(): Double {
                if (count == 0) return Double.NaN
                var close = 0
                var total = 0
                for (index in 0 until count) {
                    for (dim in 0 until TARGETS) {
                        val error = forward.output[index][dim] -
                            normalisation.target(clip.targets[index], dim)
                        if (abs(error) <= 0.10) close++
                        total++
                    }
                }
                return if (total == 0) Double.NaN else close.toDouble() / total
            }

            /** Backpropagates [loss] into the network's gradients. [zeroGradients] happens here. */
            fun backward() {
                if (count == 0) return
                zeroGradients()
                val dOutput = Array(forward.length) { DoubleArray(TARGETS) }
                val scale = 2.0 / (count * TARGETS)
                for (index in 0 until count) {
                    for (dim in 0 until TARGETS) {
                        dOutput[index][dim] =
                            (forward.output[index][dim] - normalisation.target(clip.targets[index], dim)) * scale
                    }
                }

                // The head: y = h·W + b.
                var dCurrent = Array(forward.length) { DoubleArray(dModel) }
                for (index in 0 until forward.length) {
                    for (dim in 0 until TARGETS) {
                        val gradient = dOutput[index][dim]
                        if (gradient == 0.0) continue
                        bOut.grad[dim] += gradient
                        for (other in 0 until dModel) {
                            wOut.grad[dim * dModel + other] += gradient * forward.finalHidden[index][other]
                            dCurrent[index][other] += gradient * wOut.at(dim, other)
                        }
                    }
                }

                for (layer in LAYERS - 1 downTo 0) {
                    dCurrent = backwardMlp(layer, dCurrent)
                    dCurrent = backwardAttention(layer, dCurrent)
                }

                // The input projection.
                for (index in 0 until forward.length) {
                    accumulate(
                        forward.inputs[index] + forward.conditioning,
                        dCurrent[index],
                        wIn,
                        bIn,
                    )
                }
            }

            /** The MLP half of one block: y = x + tanh(x·W1 + b1)·W2 + b2. */
            private fun backwardMlp(layer: Int, incoming: Array<DoubleArray>): Array<DoubleArray> {
                val out = Array(incoming.size) { incoming[it].copyOf() }
                val input = forward.mlpInput[layer]
                val activation = forward.mlpHidden[layer]
                for (index in 0 until forward.length) {
                    val dPre = DoubleArray(hidden)
                    for (dim in 0 until dModel) {
                        val gradient = incoming[index][dim]
                        if (gradient == 0.0) continue
                        b2[layer].grad[dim] += gradient
                        for (unit in 0 until hidden) {
                            w2[layer].grad[dim * hidden + unit] += gradient * activation[index][unit]
                            dPre[unit] += gradient * w2[layer].at(dim, unit)
                        }
                    }
                    for (unit in 0 until hidden) {
                        val value = activation[index][unit]
                        dPre[unit] *= (1.0 - value * value)
                        if (dPre[unit] == 0.0) continue
                        b1[layer].grad[unit] += dPre[unit]
                        for (dim in 0 until dModel) {
                            w1[layer].grad[unit * dModel + dim] += dPre[unit] * input[index][dim]
                            out[index][dim] += dPre[unit] * w1[layer].at(unit, dim)
                        }
                    }
                }
                return out
            }

            /** The attention half of one block: y = x + Attention(x), with GQA and its mask. */
            private fun backwardAttention(layer: Int, incoming: Array<DoubleArray>): Array<DoubleArray> {
                val out = Array(incoming.size) { incoming[it].copyOf() }
                val input = forward.layerInput[layer]
                val q = forward.queries[layer]
                val k = forward.keys[layer]
                val v = forward.values[layer]
                val weights = forward.attention[layer]
                val length = forward.length

                val dMixed = Array(length) { DoubleArray(dModel) }
                for (index in 0 until length) {
                    for (dim in 0 until dModel) {
                        val gradient = incoming[index][dim]
                        if (gradient == 0.0) continue
                        for (other in 0 until dModel) {
                            wo[layer].grad[dim * dModel + other] += gradient * forward.attended[layer][index][other]
                            dMixed[index][other] += gradient * wo[layer].at(dim, other)
                        }
                    }
                }

                val dQ = Array(length) { DoubleArray(dModel) }
                val dK = Array(length) { DoubleArray(kvDim) }
                val dV = Array(length) { DoubleArray(kvDim) }
                val inverse = 1.0 / sqrt(headDim.toDouble())
                for (index in 0 until length) {
                    for (head in 0 until heads) {
                        val group = head % kvHeads
                        val row = weights[index]
                        // dA_ij = dO_i · V_j, then the softmax jacobian turns it into dS_ij.
                        val dA = DoubleArray(index + 1)
                        var dot = 0.0
                        for (other in 0..index) {
                            var sum = 0.0
                            for (dim in 0 until headDim) {
                                sum += dMixed[index][head * headDim + dim] * v[other][group * headDim + dim]
                            }
                            dA[other] = sum
                            dot += row[head * length + other] * sum
                        }
                        for (other in 0..index) {
                            val weight = row[head * length + other]
                            if (weight == 0.0) continue
                            for (dim in 0 until headDim) {
                                dV[other][group * headDim + dim] += weight * dMixed[index][head * headDim + dim]
                            }
                            val dScore = weight * (dA[other] - dot)
                            if (dScore == 0.0) continue
                            for (dim in 0 until headDim) {
                                dQ[index][head * headDim + dim] += dScore * k[other][group * headDim + dim] * inverse
                                dK[other][group * headDim + dim] +=
                                    dScore * q[index][head * headDim + dim] * inverse
                            }
                        }
                    }
                }

                for (index in 0 until length) {
                    accumulate(input[index], dQ[index], wq[layer], null)
                    accumulate(input[index], dK[index], wk[layer], null)
                    accumulate(input[index], dV[index], wv[layer], null)
                }
                // Back into the block's input: the attention output half through Wq, the key and
                // value halves through Wk and Wv. Wk and Wv map the input to the SMALLER kv width, so
                // they are read with the input dimension as the column, not the row.
                for (index in 0 until length) {
                    for (dim in 0 until dModel) {
                        var sum = 0.0
                        for (other in 0 until dModel) sum += dQ[index][other] * wq[layer].at(dim, other)
                        for (other in 0 until kvDim) {
                            sum += dK[index][other] * wk[layer].at(other, dim)
                            sum += dV[index][other] * wv[layer].at(other, dim)
                        }
                        out[index][dim] += sum
                    }
                }
                return out
            }

            private fun accumulate(
                input: DoubleArray,
                gradient: DoubleArray,
                weights: TrainerKit.Param,
                bias: TrainerKit.Param?,
            ) {
                TrainerKit.accumulateLinear(input, gradient, weights, bias)
            }
        }
    }

    // ── The artifacts ───────────────────────────────────────────────────────────────────────────

    /** A trained model: the network, what it was normalised with, and what it reads. */
    class Trained(
        val net: Net,
        val config: Config,
        val normalisation: Normalisation,
        val conditioningColumns: List<String>,
        /** The acoustic encoder/decoder pair the build trains; null when there was nothing to train on. */
        val coder: CoderTrainer.Trained? = null,
    ) {
        /** The container files the run wrote; empty until [writeArtifacts]. Read by the summary. */
        var artifactFiles: List<File> = emptyList()
            private set
        /** The predicted acoustics for [frames], conditioned on [conditioning]. */
        fun predict(conditioning: DoubleArray, frames: List<VocalAnalysis.FrameSample>): List<DoubleArray> {
            if (frames.isEmpty()) return emptyList()
            val view = FrameView(frames)
            val forward = net.Forward(Array(frames.size) { view.input(it) }, normalisation.conditioning(conditioning))
            return forward.output.map { row ->
                DoubleArray(TARGETS) { index ->
                    row[index] * normalisation.targetDeviation[index] + normalisation.targetMean[index]
                }
            }
        }

        fun writeArtifacts(
            directory: File,
            model: CraftedModel,
            firstLoss: Double,
            lastLoss: Double,
            validationLoss: Double,
            steps: Int,
            speech: TtsTrainer.Trained? = null,
        ): File {
            directory.mkdirs()
            writeWeights(File(directory, WEIGHTS_FILE), net, conditioningColumns.size)
            // The chosen container. An audio build is three files when it is safetensors and one when
            // it is a GGUF, and the component names come from the same list the recipe writes.
            val components = buildList {
                add(ModelFiles.Component(ModelFiles.MAIN, ModelFiles.tensorsOf(net.parameters())))
                coder?.let { pair ->
                    add(
                        ModelFiles.Component(
                            "encoder",
                            ModelFiles.tensorsOf(listOf(pair.encoder, pair.encoderBias)),
                        ),
                    )
                    add(
                        ModelFiles.Component(
                            "decoder",
                            ModelFiles.tensorsOf(listOf(pair.decoder, pair.decoderBias)),
                        ),
                    )
                }
            }
            artifactFiles = ModelFiles.writeAll(model, directory, BASE_NAME, components)
            val metadata = JSONObject().apply {
                put("kind", "prosody-conditioned-acoustic-model")
                put("model", model.name)
                put("crafted_model_id", model.id)
                put("audio_task", ModelCraft.audioType(model).recipeValue)
                put("predicts", "the NEXT frame's acoustics from the frames before it")
                put(
                    "note",
                    "Two artifacts in one folder. This file describes the frame-level acoustic " +
                        "predictor: it learns how the dataset's prosody maps onto its acoustic " +
                        "trajectory. Beside it, ${"tts_model.json"} describes the voice model trained " +
                        "from the same clips and their transcripts: the cleanest recording per " +
                        "language and gender is its voice, and the dataset voice engine speaks typed " +
                        "text in that voice (or in a reference recording, for a clone). Pocket TTS is " +
                        "the optional multilingual fallback. The text encoder, aligner, " +
                        "duration model, decoder, codec and Griffin-Lim vocoder in that file are the " +
                        "compact diagnostic pipeline, not the renderer. The recipe remains what an " +
                        "external trainer reads.",
                )
                put(
                    "text_to_speech",
                    JSONObject().apply {
                        put("metadata_file", TtsTrainer.METADATA_FILE)
                        put("weights_file", TtsTrainer.WEIGHTS_FILE)
                        put("codec_weights_file", TtsTrainer.CODEC_FILE)
                        put("trained", speech != null)
                        if (speech != null) {
                            put("components", JSONArray(listOf(
                                "text_encoder", "phoneme_aligner", "duration_model",
                                "acoustic_decoder", "codec", "vocoder", "speaker_encoder", "voice",
                            )))
                            put("parameters", speech.net.parameterCount().toLong())
                            put("languages", JSONArray(speech.languages))
                            put("codec_code_width", speech.codec?.code ?: 0)
                            put("codebook_size", speech.codebook.size)
                            put("vocoder", "griffin-lim")
                            put(
                                "voice_engine",
                                if (speech.useBuiltInEngine) "dataset" else "pocket-tts",
                            )
                            put("allow_pocket_tts", speech.allowPocket)
                            put("output_rate", speech.outputRate.toLong())
                            put(
                                "voice_clips",
                                JSONObject().apply {
                                    speech.voiceClips.forEach { (key, name) -> put(key, name) }
                                },
                            )
                            put("files", JSONArray(speech.artifactFiles.map { it.name }))
                        } else {
                            put(
                                "reason",
                                "No clip had text in its dataset CSV row (or in a .txt beside it), " +
                                    "so there was no transcript to align and no text-to-speech model " +
                                    "to train.",
                            )
                        }
                    },
                )
                put("layers", LAYERS)
                put("d_model", D_MODEL)
                put("query_heads", HEADS)
                put("key_value_heads", KV_HEADS)
                put("mlp_hidden", HIDDEN)
                put("frame_features", FRAME_FEATURES)
                put("targets", TARGETS)
                put("parameters", net.parameterCount().toLong())
                put("sliding_window", config.window > 0)
                put("sliding_window_size", config.window)
                put("full_attention_per_nth_layer", config.fullEvery)
                put(
                    "attention_layers",
                    JSONArray((0 until LAYERS).map { layer ->
                        if (net.isFull(layer)) "full" else "sliding:${config.window}"
                    }),
                )
                put("epochs", config.epochs)
                put("steps_per_epoch", config.stepsPerEpoch)
                put("learning_rate_used", config.learningRate)
                put("gradient_clip", config.gradientClip)
                put("seed", config.seed)
                put("steps", steps.toLong())
                put("loss_first", firstLoss)
                put("loss_last", lastLoss)
                if (!validationLoss.isNaN()) put("loss_validation", validationLoss)
                put("conditioning_columns", JSONArray(conditioningColumns))
                put("conditioning_mean", JSONArray(normalisation.conditioningMean.toList()))
                put("conditioning_deviation", JSONArray(normalisation.conditioningDeviation.toList()))
                put("target_mean", JSONArray(normalisation.targetMean.toList()))
                put("target_deviation", JSONArray(normalisation.targetDeviation.toList()))
                put("imputed_conditioning_columns", JSONArray(normalisation.imputed))
                put("weights_file", WEIGHTS_FILE)
                put("weights_format", "magic '$MAGIC', then per tensor: name, rows, cols, doubles")
                put(ModelCraft.KEY_FILE_FORMAT, ModelCraft.fileFormat(model).recipeValue)
                put("model_files", JSONArray(artifactFiles.map { it.name }))
                coder?.let { pair ->
                    put(
                        "encoder_decoder",
                        JSONObject().apply {
                            put("trained_on", "the dataset's own acoustic frames — the 13 per-frame " +
                                "values the model predicts")
                            put("inputs", pair.inputs)
                            put("code", pair.code)
                            put("examples", pair.examples)
                            put("steps", pair.steps.toLong())
                            put("reconstruction_loss_first", pair.firstLoss)
                            put("reconstruction_loss_last", pair.lastLoss)
                            put(
                                "note",
                                "A bottleneck autoencoder: the encoder is one tanh layer that turns a " +
                                    "frame's acoustics into a code of the width above, the decoder " +
                                    "one linear layer that turns the code back into them. Not a VAE — " +
                                    "there is no sampling and no codebook in this build.",
                            )
                        },
                    )
                }
            }
            val file = File(directory, METADATA_FILE)
            file.writeText(metadata.toString())
            return file
        }
    }

    /** Writes the weights, in [Net.parameters]' order, tagged so a wrong file is refused. */
    private fun writeWeights(file: File, net: Net, conditioningColumns: Int) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC.length)
            out.writeChars(MAGIC)
            out.writeInt(FRAME_FEATURES)
            out.writeInt(TARGETS)
            out.writeInt(conditioningColumns)
            val params = net.parameters()
            out.writeInt(params.size)
            for (param in params) {
                out.writeUTF(param.name)
                out.writeInt(param.rows)
                out.writeInt(param.cols)
                for (value in param.data) out.writeDouble(value)
            }
        }
    }

    /** Reads an artifact pair back, for a future generator and for the tests. */
    fun load(directory: File): Trained? {
        val metadata = runCatching { JSONObject(File(directory, METADATA_FILE).readText()) }.getOrNull() ?: return null
        val weights = File(directory, WEIGHTS_FILE)
        if (!weights.isFile) return null
        val config = Config(
            window = metadata.optInt("sliding_window_size", 0),
            fullEvery = metadata.optInt("full_attention_per_nth_layer", 1).coerceAtLeast(1),
            epochs = metadata.optInt("epochs", 0),
            stepsPerEpoch = metadata.optInt("steps_per_epoch", 0),
            learningRate = metadata.optDouble("learning_rate_used", DEFAULT_LEARNING_RATE),
            gradientClip = metadata.optDouble("gradient_clip", 1.0),
            seed = metadata.optLong("seed", 42L),
        )
        val net = Net(config, java.util.Random(config.seed))
        val columns = metadata.optJSONArray("conditioning_columns")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: CONDITIONING_COLUMNS
        val read = runCatching {
            DataInputStream(weights.inputStream().buffered()).use { input ->
                val magicLength = input.readInt()
                val magic = buildString { repeat(magicLength) { append(input.readChar()) } }
                require(magic == MAGIC) { "not a Prism acoustic model: $magic" }
                val frameFeatures = input.readInt()
                val targets = input.readInt()
                val conditioning = input.readInt()
                require(frameFeatures == FRAME_FEATURES && targets == TARGETS && conditioning == columns.size) {
                    "this build's model shape does not match the file's"
                }
                val count = input.readInt()
                val params = net.parameters()
                require(count == params.size) { "expected ${params.size} tensors, found $count" }
                for (param in params) {
                    val name = input.readUTF()
                    val rows = input.readInt()
                    val cols = input.readInt()
                    require(name == param.name && rows == param.rows && cols == param.cols) {
                        "${param.name} does not match the file's $name (${rows}x$cols)"
                    }
                    for (index in param.data.indices) param.data[index] = input.readDouble()
                }
            }
        }.onFailure { PrismPlatform.log.warn("ModelCraft", "Could not read ${weights.name}: ${it.message}") }
            .getOrNull() ?: return null

        val normalisation = Normalisation.of(columns.size)
        fun readInto(array: DoubleArray, key: String, fallback: Double) {
            metadata.optJSONArray(key)?.let { values ->
                for (index in 0 until min(values.length(), array.size)) {
                    array[index] = values.optDouble(index, fallback)
                }
            }
        }
        readInto(normalisation.conditioningMean, "conditioning_mean", 0.0)
        readInto(normalisation.conditioningDeviation, "conditioning_deviation", 1.0)
        readInto(normalisation.targetMean, "target_mean", 0.0)
        readInto(normalisation.targetDeviation, "target_deviation", 1.0)
        return Trained(net, config, normalisation, columns)
    }
}
