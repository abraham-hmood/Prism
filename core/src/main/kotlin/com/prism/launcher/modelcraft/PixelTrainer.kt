package com.prism.launcher.modelcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The on-device trainers for the three PIXEL kinds: an image model, a frame interpolator and a video
 * model.
 *
 * ## Why one file
 *
 * The three are the same job pointed at different data: pixels in, pixels out, mean-squared error in
 * between. What differs is what the input IS -- a noisy image and a timestep, two frames and the gap
 * between them, or the frames before and the one that comes next -- and each of those is a few lines
 * of assembly around the same network. Three copies of a tanh MLP and its backward pass would be three
 * times the code with three times the places to be wrong.
 *
 * ## What the models are, precisely
 *
 *  - **Image, diffusion families** (latent diffusion, DiT, MM-DiT, rectified flow, DDPM): a
 *    flow-matching denoiser over PIXELS. It is trained on `x_t = (1-t)·x + t·noise` to predict
 *    `noise - x`, the velocity that carries an image to noise; sampling integrates that field from
 *    noise back to an image. The recipe may name a base checkpoint -- there is no VAE and no CLIP
 *    beside this trainer, so the pixels are the space and the caption is not read, and the metadata
 *    says exactly that.
 *  - **Image, the other families** (GAN, VAE, VQ-VAE, PixelCNN, autoregressive, VAR): a masked-patch
 *    autoencoder. A square of the image is hidden and predicted from the rest. That is the part of
 *    those architectures this build can train honestly: an adversarial loss needs a discriminator, a
 *    discrete codebook needs a codec, and neither is here -- what is here reconstructs and denoises
 *    real pixels over real data, and the metadata names the objective so nobody mistakes it for a GAN.
 *  - **Frame interpolator**: the middle frame predicted from the frames on either side of it. Every
 *    family in that tab is a way of doing exactly this; the ones that estimate optical flow have a
 *    flow estimator this build does not, and the metadata says the reconstruction is direct.
 *  - **Video**: the next frame predicted from the frames before it, which is the temporal core every
 *    video architecture wraps in conditioning and cascades.
 *
 * ## Determinism
 *
 * Seed from the recipe, examples in file order, the flow-matching timestep and the masked patch drawn
 * from that one seeded generator, so the same dataset and recipe produce the same weights.
 */
object PixelTrainer {

    /**
     * Which of the three pixel jobs a run is.
     *
     * It decides the artifact names, the weights magic, and the stem the chosen container's files
     * are written under (`image_model.safetensors`, `video_model.gguf`).
     */
    enum class Task(
        val label: String,
        val metadataFile: String,
        val weightsFile: String,
        val magic: String,
        val baseName: String,
    ) {
        IMAGE(
            "image", "image_model.json", "image_model.weights", "PRISM-IMAGE-1",
            ModelCraft.fileStem(ModelCraft.Kind.IMAGE),
        ),
        INTERPOLATION(
            "frame", "frame_model.json", "frame_model.weights", "PRISM-FRAME-1",
            ModelCraft.fileStem(ModelCraft.Kind.INTERPOLATION),
        ),
        VIDEO(
            "video", "video_model.json", "video_model.weights", "PRISM-VIDEO-1",
            ModelCraft.fileStem(ModelCraft.Kind.VIDEO),
        ),
    }

    /** Width of the hidden layers an interpolator or video model gets. */
    const val HIDDEN = 64

    /** Width an image model gets: wide enough to keep a face from collapsing into a grey oval. */
    const val IMAGE_HIDDEN = 256

    /**
     * The caption vector's width.
     *
     * Captions are hashed into this many signed buckets rather than a learned vocabulary. A learned
     * vocabulary would have to be carried beside the weights and would meet a word it never saw as
     * an unknown token; hashing meets every word, and a collision costs one dimension rather than a
     * sentence. Forty-eight is small enough to train on a phone and wide enough that ten classes do
     * not collide at all.
     */
    const val CAPTION_DIM = 48

    /** The square every pixel model works at. The recipe may ask for more; this is the cap. */
    const val MAX_SIDE = 32

    /** Corners of a clip are enough to learn a first frame relation from; the rest is not read. */
    private const val MAX_FRAMES_PER_CLIP = 48

    /** Examples are bounded so a large dataset does not make one run's memory the whole dataset. */
    private const val MAX_EXAMPLES = 3_072

    /** Held-out examples are read whole but capped: a validation pass must not outlast the epoch. */
    private const val MAX_HELD_OUT = 512

    /** How strongly the prompt pulls the sample away from the unconditioned one. */
    const val GUIDANCE = 3.0

    /** Captions are dropped this often while training, so guidance has an unconditioned model. */
    private const val CAPTION_DROPOUT = 0.12

    private const val MIN_LEARNING_RATE = 3e-4
    private const val MAX_LEARNING_RATE = 3e-3
    private const val DEFAULT_LEARNING_RATE = 1e-3

    /** How many frames a video model reads before the one it predicts. */
    const val PREVIOUS_FRAMES = 2

    /**
     * Flow-matching sampling steps, the number [Trained.generate] uses when none is given.
     *
     * Eight steps was the first default and it is too few: Euler's method at eight steps leaves a
     * coarse, speckled field that reads as a broken image even when the model has learned the shape.
     * Forty costs half a second on a phone and is the difference between a recognisable subject and
     * a cloud of pixels, so the in-app test uses forty unless a caller asks for another number.
     */
    const val SAMPLE_STEPS = 40

    /** Dimensions of the sinusoidal timestep embedding a diffusion-family image model reads. */
    const val TIME_DIM = 16

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    fun trainImage(model: CraftedModel, recipe: File): String? =
        trainResult(Task.IMAGE, model, recipe)?.summary

    fun trainInterpolation(model: CraftedModel, recipe: File): String? =
        trainResult(Task.INTERPOLATION, model, recipe)?.summary

    fun trainVideo(model: CraftedModel, recipe: File): String? =
        trainResult(Task.VIDEO, model, recipe)?.summary

    /** A finished run: the trained network in memory, and the line describing it. */
    class Result(val trained: Trained, val summary: String)

    /** The same run as [trainImage] and its siblings, keeping the model without a reload. */
    fun trainResult(task: Task, model: CraftedModel, recipe: File): Result? {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val baseConfig = readRecipeConfig(recipe, task)
        val examples = when (task) {
            Task.IMAGE -> imageExamples(directory, baseConfig)
            Task.INTERPOLATION -> interpolationExamples(ModelCraft.Store.framesDir(model.id), baseConfig.side)
            Task.VIDEO -> videoExamples(ModelCraft.Store.framesDir(model.id), baseConfig.side)
        }
        if (examples.isEmpty()) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Nothing to train a ${task.label} model on in ${model.id}: " +
                    if (task == Task.IMAGE) {
                        "no image in the dataset could be decoded"
                    } else {
                        "the frames this trainer reads were never extracted from the dataset's clips"
                    },
            )
            return null
        }

        val diffusion = task == Task.IMAGE && diffusionObjective(model)
        // A dataset of grey pictures is modelled as one channel. Modelling MNIST as RGB gives the
        // sampler three independent noises to explain instead of one, which is most of the colour
        // speckle an undertrained model leaves behind; the channel is a property of the data, so it
        // is measured here rather than asked for on the form.
        val channels = if (task == Task.IMAGE && examples.all { it.grayscale }) 1 else 3
        val config = baseConfig.copy(channels = channels)
        val inputDim = inputWidth(task, config.side, diffusion, channels)
        val outputDim = outputWidth(task, config.side, channels)
        val net = Net(
            config, inputDim, outputDim, java.util.Random(config.seed),
            useLocalContext = task == Task.IMAGE,
        )
        val parameters = net.parameters()
        val adam = TrainerKit.Adam(config.learningRate)
        // The weights sampling uses are a moving average of the trained ones, not the last noisy
        // step. A diffusion model's last step is one minibatch's fit; averaging the last thousand
        // takes the variance out of the samples without touching the loss.
        val ema = parameters.map { it.data.copyOf() }
        val random = java.util.Random(config.seed xor 0x9E37)
        // THE THREE DATASETS THE RUN ACTUALLY HAS. A dataset that ships its own train/validation/test
        // folders is read as those; a flat one was split deterministically by [imageExamples]. The
        // frame kinds have no split of their own, so their validation is every fourth example and
        // there is no test set to report -- a number from the training data would be a lie.
        val trainingSet = examples.filter { it.split == Split.TRAIN }.ifEmpty { examples }
        // CAPPED, because a held-out pass runs after every epoch and a 6,000-image test split
        // would otherwise cost more than the epoch it is measuring. The cap is the constant above,
        // not a per-run number: the same dataset always reports the same held-out examples.
        val test = examples.filter { it.split == Split.TEST }.take(MAX_HELD_OUT)
        // A split the dataset does not ship is derived from the training data and removed from it;
        // a model validated on an example it trained on is reporting its own memory back.
        val validation = examples.filter { it.split == Split.VALIDATION }.take(MAX_HELD_OUT)
            .ifEmpty {
                trainingSet.filterIndexed { index, _ -> index % 4 == 3 }
                    .ifEmpty { listOf(trainingSet.last()) }
            }
        val training = trainingSet.filterNot { example -> validation.any { it === example } }
            .ifEmpty { trainingSet }
        // EXACTLY the user's schedule. One step is one example; a requested epoch cycles the training
        // split until it has taken that many, and 0 means one full pass. No step or wall-clock ceiling
        // truncates it -- the numbers on the form are the numbers this run takes.
        val stepsPerEpoch = if (config.stepsPerEpoch > 0) config.stepsPerEpoch else training.size
        val totalSteps = (stepsPerEpoch * config.epochs).coerceAtLeast(1)
        val minimumRate = config.learningRate * 0.1
        var steps = 0
        var epoch = 0
        val history = mutableListOf<Double>()
        var validationLoss = Double.NaN
        var testLoss = Double.NaN
        // A held-out pass over hundreds of examples after every epoch can cost more than the epoch
        // itself once the schedule is long. Five evenly spaced passes plus the last epoch give the
        // same reported number without spending the run's time measuring instead of learning.
        val validationEvery = max(1, config.epochs / 5)
        while (epoch < config.epochs) {
            epoch++
            var sum = 0.0
            // A fresh order per epoch. The same order every epoch would let the optimiser see the
            // same pair of neighbours on every pass, which is a schedule the data did not choose.
            val order = training.indices.shuffled(java.util.Random(config.seed + epoch * 7_919L))
            repeat(stepsPerEpoch) { offset ->
                val position = order[offset % order.size]
                val example = training[position]
                // The index is the example's position in the WHOLE dataset, so the masked-patch
                // family still hides the same square for the same picture across epochs.
                val index = examples.indexOf(example)
                val step = Step(task, config, net, example, index, random)
                val stepLoss = step.loss()
                sum += stepLoss
                step.backward()
                val progress = steps.toDouble() / totalSteps
                adam.setRate(
                    minimumRate + (config.learningRate - minimumRate) *
                        0.5 * (1.0 + cos(Math.PI * progress.coerceIn(0.0, 1.0))),
                )
                adam.step(parameters, config.gradientClip)
                // A warm-up decay: early on the average should be able to follow the weights, and
                // after a thousand steps it should be the steady 0.999 window.
                val decay = minOf(0.999, (1.0 + steps) / (10.0 + steps))
                for ((paramIndex, param) in parameters.withIndex()) {
                    val average = ema[paramIndex]
                    for (index in param.data.indices) {
                        average[index] = decay * average[index] + (1.0 - decay) * param.data[index]
                    }
                }
                steps++
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = epoch,
                        epochs = config.epochs,
                        step = offset + 1,
                        steps = stepsPerEpoch,
                        file = example.source.ifBlank { task.label.lowercase() },
                        loss = stepLoss,
                        accuracy = step.accuracy(),
                    ),
                )
            }
            val mean = sum / stepsPerEpoch.coerceAtLeast(1)
            history += mean
            if (epoch == config.epochs || epoch % validationEvery == 0) {
                validationLoss = validation.mapIndexed { index, example ->
                    Step(
                        task, config, net, example, examples.indexOf(example),
                        java.util.Random(config.seed + index * 131L), training = false,
                    ).loss()
                }.average()
            }
            PrismPlatform.log.info(
                "ModelCraft",
                "${task.label} epoch $epoch: train ${"%.5f".format(mean)} " +
                    "val ${"%.5f".format(validationLoss)}",
            )
        }
        if (test.isNotEmpty()) {
            testLoss = test.mapIndexed { index, example ->
                Step(
                    task, config, net, example, examples.indexOf(example),
                    java.util.Random(config.seed + 7_919L + index * 131L), training = false,
                ).loss()
            }.average()
            PrismPlatform.log.info(
                "ModelCraft",
                "${task.label} test: ${"%.5f".format(testLoss)}",
            )
        }
        val lastLoss = history.lastOrNull() ?: return null
        // Sampling uses the averaged weights, so the artifact is the average rather than the last
        // minibatch. The loss reported above is still the loss actually trained on.
        for ((param, average) in parameters.zip(ema)) {
            System.arraycopy(average, 0, param.data, 0, average.size)
        }

        val objective = objectiveOf(task, diffusion)
        // THE ENCODER AND DECODER, for the kind whose build includes them. Trained on the same
        // frames the model reads -- the examples' targets ARE frames -- and reported whether or not
        // it trained, so a video build that could not make one says so instead of implying it did.
        val coder = if (task == Task.VIDEO) {
            CoderTrainer.train(
                vectors = examples.mapNotNull { it.target },
                seed = config.seed xor 0xC0DE,
                epochs = config.epochs,
                stepsPerEpoch = config.stepsPerEpoch,
                learningRate = config.learningRate,
                gradientClip = config.gradientClip,
                label = "frame encoder/decoder",
            )
        } else {
            null
        }
        if (task == Task.VIDEO && coder == null) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "No frame encoder or decoder could be trained for ${model.id}: the examples hold no " +
                    "frame to reconstruct",
            )
        }
        val trained = Trained(
            task, net, config, objective, diffusion, examples.size, inputDim, outputDim, coder,
            trainingCount = training.size,
            validationCount = validation.size,
            testCount = test.size,
            testLoss = testLoss,
            conditional = examples.any { it.caption.isNotBlank() },
        )
        val metadata = trained.writeArtifacts(
            directory,
            model,
            history.first(),
            lastLoss,
            validationLoss,
            testLoss,
            steps,
        )
        PrismPlatform.log.info(
            "ModelCraft",
            "${task.label} model for ${model.id}: $steps step(s), ${"%.5f".format(lastLoss)} loss, " +
                "${net.parameterCount()} parameters",
        )
        val summary = buildString {
            append("${net.parameterCount()} parameters trained over $steps step(s)")
            append(" · loss ${"%.5f".format(history.first())} → ${"%.5f".format(lastLoss)}")
            if (!validationLoss.isNaN()) append(" · validation ${"%.5f".format(validationLoss)}")
            if (!testLoss.isNaN()) append(" · test ${"%.5f".format(testLoss)}")
            append(" · $objective")
            append(" at ${config.side}×${config.side}")
            append(" · ${metadata.name} written beside the dataset")
            if (trained.artifactFiles.isNotEmpty()) {
                append(" with ${trained.artifactFiles.joinToString(", ") { it.name }}")
            }
        }
        return Result(trained, summary)
    }

    /** True when this image model's family is trained by denoising a field rather than by masking. */
    fun diffusionObjective(model: CraftedModel): Boolean {
        val text = (model.architecture + " " + model.family).lowercase()
        return listOf(
            "diffus", "ddpm", "dit", "rectified", "flow matching", "latent diffusion", "u-net",
        ).any { text.contains(it) }
    }

    private fun objectiveOf(task: Task, diffusion: Boolean): String = when {
        task == Task.IMAGE && diffusion -> "flow-matching pixel denoiser"
        task == Task.IMAGE -> "masked-patch pixel reconstruction"
        task == Task.INTERPOLATION -> "middle-frame reconstruction from its neighbours"
        else -> "next-frame prediction from the frames before it"
    }

    private fun inputWidth(task: Task, side: Int, diffusion: Boolean, channels: Int): Int {
        val pixels = channels * side * side
        // Every task's input ends with the caption vector, zero-filled when the data has no
        // captions. One shape rule for all three pixel jobs means the conditioning path is trained
        // by every run rather than bolted onto one of them.
        return when (task) {
            Task.IMAGE -> (if (diffusion) pixels + TIME_DIM else pixels + side * side) + CAPTION_DIM
            Task.INTERPOLATION -> 3 * side * side * 2 + CAPTION_DIM
            Task.VIDEO -> 3 * side * side * PREVIOUS_FRAMES + CAPTION_DIM
        }
    }

    private fun outputWidth(task: Task, side: Int, channels: Int): Int = channels * side * side

    // ── Reading the dataset ─────────────────────────────────────────────────────────────────────

    /** Which of a run's three datasets an example belongs to. */
    enum class Split { TRAIN, VALIDATION, TEST }

    /** One example: the frames the model reads, the frame it is asked for, and where it came from. */
    class Example(
        val frames: List<DoubleArray>,
        val target: DoubleArray?,
        val source: String = "",
        /** What the image is, in words. Empty for kinds whose data carries no caption. */
        val caption: String = "",
        val split: Split = Split.TRAIN,
        /** True when every pixel of this picture has equal red, green and blue. */
        val grayscale: Boolean = false,
    )

    /**
     * Reads every image the dataset holds, split the way the dataset says.
     *
     * A dataset that ships `train/`, `validation/` and `test/` is read as those three, because that
     * is the split its publisher measured against and re-splitting it would make the numbers
     * incomparable to the paper they came from. A flat folder is split deterministically instead:
     * every tenth image is validation and the one after it test, so the same folder gives the same
     * three sets on every run and nothing is tested on that was trained on.
     *
     * Captions come from a CSV beside the images, never from the file name. The header chooses the
     * columns by name, so `file,caption`, `image,text` and `filename,prompt` all work; a `split`
     * column is honoured when the folders do not carry one. An image with no caption is still
     * trained on -- it is simply not conditioned, and a run whose whole dataset has no captions says
     * so in its metadata instead of pretending.
     */
    private fun imageExamples(directory: File, config: Config): List<Example> {
        val named = linkedMapOf(
            Split.TRAIN to splitFolder(directory, config.trainSplit, "train"),
            Split.VALIDATION to splitFolder(directory, config.validationSplit, "validation", "val", "valid"),
            Split.TEST to splitFolder(directory, config.testSplit, "test"),
        )
        val splitByFolder = named.values.any { it != null }
        val images = mutableListOf<Example>()
        if (splitByFolder) {
            for ((split, folder) in named) {
                if (folder == null) continue
                val captionFile = captionsByFile(folder, config.captionColumn)
                images += readImageFolder(folder, config.side, split, captionFile, MAX_EXAMPLES)
            }
        } else {
            val captionFile = captionsByFile(directory, config.captionColumn)
            val all = readImageFolder(directory, config.side, Split.TRAIN, captionFile, MAX_EXAMPLES)
            for ((index, example) in all.withIndex()) {
                val split = when {
                    example.split != Split.TRAIN -> example.split
                    index % 10 == 8 -> Split.VALIDATION
                    index % 10 == 9 -> Split.TEST
                    else -> Split.TRAIN
                }
                images += Example(example.frames, example.target, example.source, example.caption, split)
            }
        }
        if (images.isEmpty()) {
            PrismPlatform.log.warn("ModelCraft", "No image in ${directory.name} could be decoded")
        }
        return images
    }

    /** A captioned row: what the picture is and, when the CSV says so, which split it belongs to. */
    private class CaptionRow(val caption: String, val split: Split?)

    private fun splitFolder(directory: File, configured: String, vararg aliases: String): File? {
        val names = (listOf(configured) + aliases).filter { it.isNotBlank() }
            .map { it.trim().lowercase() }
            .toSet()
        return directory.listFiles()
            ?.filter { it.isDirectory && it.name.lowercase() in names }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    private fun readImageFolder(
        folder: File,
        side: Int,
        defaultSplit: Split,
        captions: Map<String, CaptionRow>,
        limit: Int,
    ): List<Example> {
        val files = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        val images = mutableListOf<Example>()
        for (file in files) {
            val image = PrismPlatform.images.decode(file, side)
            if (image == null) {
                PrismPlatform.log.warn("ModelCraft", "Could not decode ${file.name}; it is not trained on")
                continue
            }
            val row = captions[file.name.lowercase()]
            images += Example(
                listOf(pixelsOf(image, side)),
                null,
                file.name,
                row?.caption.orEmpty(),
                row?.split ?: defaultSplit,
                grayscale = image.pixels.all { pixel ->
                    PrismImage.red(pixel) == PrismImage.green(pixel) &&
                        PrismImage.green(pixel) == PrismImage.blue(pixel)
                },
            )
            if (images.size >= limit) break
        }
        return images
    }

    /**
     * The CSV's rows, keyed by lower-case file name.
     *
     * The caption column is the one the recipe names, then the first of the obvious names. Header
     * names are compared without case so a hand-written `Caption` works. Quoted fields are read the
     * way a CSV means them: a caption containing a comma is one field, not two.
     */
    private fun captionsByFile(folder: File, configuredColumn: String): Map<String, CaptionRow> {
        val csvs = folder.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val rows = HashMap<String, CaptionRow>()
        for (csv in csvs) {
            val lines = runCatching { csv.readLines() }.getOrNull().orEmpty()
            if (lines.isEmpty()) continue
            val header = parseCsvLine(lines.first()).map { it.trim().lowercase() }
            val fileColumn = header.indexOfFirst { it in FILE_COLUMNS }
            if (fileColumn < 0) continue
            val captionColumn = header.indexOfFirst {
                it == configuredColumn.trim().lowercase()
            }.takeIf { it >= 0 }
                ?: header.indexOfFirst { it in CAPTION_COLUMNS }
            val splitColumn = header.indexOfFirst { it == "split" || it == "subset" }
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val cells = parseCsvLine(line)
                val file = cells.getOrNull(fileColumn)?.trim() ?: continue
                if (file.isEmpty()) continue
                val caption = captionColumn.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim().orEmpty()
                val split = splitColumn.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.let { raw ->
                    when (raw.trim().lowercase()) {
                        "train", "training" -> Split.TRAIN
                        "val", "valid", "validation", "dev" -> Split.VALIDATION
                        "test", "testing" -> Split.TEST
                        else -> null
                    }
                }
                rows[file.substringAfterLast('/').substringAfterLast('\\').lowercase()] =
                    CaptionRow(caption, split)
            }
        }
        return rows
    }

    /** One CSV line, with RFC 4180 quoting: doubled quotes inside, commas outside quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    cells += cell.toString()
                    cell.setLength(0)
                }
                else -> cell.append(character)
            }
            index++
        }
        cells += cell.toString()
        return cells
    }

    private val FILE_COLUMNS = setOf("file", "filename", "file_name", "image", "img", "path", "media", "audio")
    private val CAPTION_COLUMNS = setOf(
        "caption", "text", "description", "prompt", "label", "transcript", "tags",
    )

    private fun interpolationExamples(framesDir: File, side: Int): List<Example> {
        val examples = mutableListOf<Example>()
        for (clip in loadFrameFolders(framesDir, side)) {
            val frames = clip.frames
            for (index in 1 until frames.size - 1) {
                examples += Example(
                    listOf(frames[index - 1], frames[index + 1]),
                    frames[index],
                    clip.name,
                )
                if (examples.size >= MAX_EXAMPLES) return examples
            }
        }
        return examples
    }

    private fun videoExamples(framesDir: File, side: Int): List<Example> {
        val examples = mutableListOf<Example>()
        for (clip in loadFrameFolders(framesDir, side)) {
            val frames = clip.frames
            for (index in PREVIOUS_FRAMES until frames.size) {
                examples += Example(
                    frames.subList(index - PREVIOUS_FRAMES, index).toList(),
                    frames[index],
                    clip.name,
                )
                if (examples.size >= MAX_EXAMPLES) return examples
            }
        }
        return examples
    }

    private class FrameFolder(val name: String, val frames: List<DoubleArray>)

    /** Every clip's frames, one list per clip folder, decoded at [side]. */
    private fun loadFrameFolders(framesDir: File, side: Int): List<FrameFolder> {
        val folders = framesDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        val clips = mutableListOf<FrameFolder>()
        for (folder in folders) {
            val files = folder.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                ?.sortedBy { it.name }
                .orEmpty()
            val frames = mutableListOf<DoubleArray>()
            for (file in files) {
                if (frames.size >= MAX_FRAMES_PER_CLIP) break
                val image = PrismPlatform.images.decode(file, side)
                    ?: continue
                frames += pixelsOf(image, side)
            }
            if (frames.size >= 3) clips += FrameFolder(folder.name, frames)
        }
        return clips
    }

    /** An image as three channelled planes of [-1, 1], scaled to [side] square. */
    private fun pixelsOf(image: PrismImage, side: Int): DoubleArray {
        val scaled = image.scaledTo(side, side)
        val plane = side * side
        val out = DoubleArray(3 * plane)
        for (index in 0 until plane) {
            val pixel = scaled.pixels[index]
            out[index] = PrismImage.red(pixel) / 127.5 - 1.0
            out[plane + index] = PrismImage.green(pixel) / 127.5 - 1.0
            out[2 * plane + index] = PrismImage.blue(pixel) / 127.5 - 1.0
        }
        return out
    }

    // ── What the recipe says about the run ───────────────────────────────────────────────────────

    data class Config(
        val side: Int,
        val epochs: Int,
        val learningRate: Double,
        val gradientClip: Double,
        val seed: Long,
        /** Sliding window in patch tokens; 0 means every layer attends to the whole field. */
        val window: Int = 0,
        /** One full-attention layer every N (counted from the last), when a window is set. */
        val fullEvery: Int = 0,
        /** Examples per epoch; 0 means every example in the training split. */
        val stepsPerEpoch: Int = 0,
        /** Hidden width. Image models are wider than frame models. */
        val hidden: Int = HIDDEN,
        /** Folder names for the three datasets, as the form spells them. */
        val trainSplit: String = "train",
        val validationSplit: String = "validation",
        val testSplit: String = "test",
        /** The CSV column that holds each image's caption. */
        val captionColumn: String = "text",
        /** Whether training mirrors an image horizontally for augmentation. */
        val flip: Boolean = false,
        /** 1 for a greyscale dataset, 3 for a colour one. Detected from the images, not the form. */
        val channels: Int = 3,
    )

    /**
     * The run's shape, read from the recipe and clamped to what a phone can train.
     *
     * Resolution and crop size come from the form; a 1024-pixel training run is what the recipe asks a
     * training box for and not what this trainer allocates, so the side used is written into the
     * metadata rather than silently substituted.
     */
    private fun readRecipeConfig(recipe: File, task: Task): Config {
        val json = runCatching { JSONObject(recipe.readText()) }.getOrNull()
        // The recipe keeps the from-scratch shape under `creation`, beside the sentence saying the
        // model is built from nothing. Reading only a top-level `initialisation` silently ignored the
        // resolution the user typed and trained every image model at 16 pixels.
        val initialisation = json?.optJSONObject("initialisation")
            ?: json?.optJSONObject("creation")?.optJSONObject("initialisation")
        val raw = when (task) {
            Task.IMAGE -> initialisation?.optInt("resolution", 0) ?: 0
            Task.INTERPOLATION -> initialisation?.optInt("resolution", 0) ?: 0
            Task.VIDEO -> {
                val width = initialisation?.optInt("width", 0) ?: 0
                val height = initialisation?.optInt("height", 0) ?: 0
                minOf(width.takeIf { it > 0 } ?: 16, height.takeIf { it > 0 } ?: 16)
            }
        }
        val epochs = (json?.optInt("epochs", 0) ?: 0).takeIf { it > 0 } ?: 12
        val learningRate = json?.optDouble("learning_rate", Double.NaN)
            ?.takeIf { it.isFinite() && it > 0.0 }
        val side = raw.takeIf { it > 0 }?.coerceIn(8, MAX_SIDE) ?: 16
        // Off means no window at all, even if the size boxes still hold the last value the user
        // typed. A stale number must not turn itself back on when the checkbox was cleared.
        val sliding = json?.optBoolean(ModelCraft.KEY_SLIDING_WINDOW, false) == true
        val configuredHidden = json?.optInt("hidden_size", 0) ?: 0
        return Config(
            side = side,
            hidden = configuredHidden.takeIf { it > 0 }?.coerceIn(32, 512)
                ?: if (task == Task.IMAGE) IMAGE_HIDDEN else HIDDEN,
            trainSplit = json?.optString("train_split", "train")?.ifBlank { "train" } ?: "train",
            validationSplit = json?.optString("validation_split", "validation")
                ?.ifBlank { "validation" } ?: "validation",
            testSplit = json?.optString("test_split", "test")?.ifBlank { "test" } ?: "test",
            captionColumn = json?.optString("caption_column", "text")?.ifBlank { "text" } ?: "text",
            flip = json?.optBoolean("random_flip", false) == true,
            window = if (sliding) {
                (json?.optInt(ModelCraft.KEY_SLIDING_WINDOW_SIZE, 0) ?: 0).coerceIn(1, 16 * 16 * 8)
            } else 0,
            fullEvery = if (sliding) {
                (json?.optInt(ModelCraft.KEY_FULL_ATTENTION_EVERY_NTH_LAYER, 1) ?: 1).coerceAtLeast(1)
            } else 0,
            epochs = epochs.coerceIn(1, ModelCraft.MAX_EPOCHS),
            stepsPerEpoch = (json?.optInt(ModelCraft.KEY_STEPS_PER_EPOCH, 0) ?: 0)
                .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH),
            learningRate = (learningRate ?: DEFAULT_LEARNING_RATE)
                .coerceIn(MIN_LEARNING_RATE, MAX_LEARNING_RATE),
            gradientClip = (
                json?.optDouble("max_grad_norm", Double.NaN)?.takeIf { it.isFinite() }
                    ?: json?.optDouble("grad_clip", 1.0)
                )?.coerceIn(0.1, 10.0) ?: 1.0,
            seed = json?.optLong("seed", 42L) ?: 42L,
        )
    }

    // ── The network ─────────────────────────────────────────────────────────────────────────────

    /**
     * Three dense layers with a tanh between them, conditioned on the caption and the timestep.
     *
     * This is what a pixel model looks like when it has to train on a phone: a dense field over the
     * whole image, no convolutions, no downsampling stack. It learns global structure and low
     * frequencies well and fine texture poorly, which is stated in the metadata rather than implied to
     * be a U-Net.
     *
     * The caption is not a seed here. It is concatenated to the field's own values and also drives a
     * per-unit scale and shift (FiLM) on the first hidden layer: the concatenated half lets a word
     * move the prediction, and the FiLM half lets it change how strongly each feature fires, which is
     * what makes "a sneaker" and "a sandal" different pictures rather than the same grey shape. The
     * caption is dropped at random while training so sampling can subtract the unconditioned model
     * and amplify what the words actually changed (classifier-free guidance).
     *
     * With a sliding window in the recipe, a [PixelPatchAttention] body runs over the field's
     * patches and adds its context to the first hidden pre-activation. The dense path is still there;
     * the window changes what the field can see, not what shape the network is.
     */
    /**
     * A 3×3 (or 1×1) convolution, written as patches and a shared dense layer.
     *
     * There is no autograd here, so the layer gathers the neighbourhood of each pixel into a vector,
     * applies one weight matrix to every position, and scatters the gradient back into the field on
     * the way home. That is the whole of a convolution at this scale, and it reuses the same
     * accumulate-linear primitive as every other layer rather than inventing a second backward pass.
     */
    private class ConvBlock(
        val name: String,
        val inChannels: Int,
        val outChannels: Int,
        val kernel: Int,
        val padding: Int,
        val activation: Boolean,
        random: java.util.Random,
        /** Spacing between kernel taps. 1 is a plain convolution; 2, 4 and 8 reach further cheaply. */
        val dilation: Int = 1,
    ) {
        val weight = TrainerKit.gaussian(
            "$name.weight", outChannels, inChannels * kernel * kernel, random,
        )
        val bias = TrainerKit.Param("$name.bias", outChannels, 1)

        private fun patchOf(input: DoubleArray, side: Int, x: Int, y: Int): DoubleArray {
            val plane = side * side
            val patch = DoubleArray(inChannels * kernel * kernel)
            var at = 0
            for (ky in 0 until kernel) {
                for (kx in 0 until kernel) {
                    val row = y + (ky - padding) * dilation
                    val column = x + (kx - padding) * dilation
                    for (channel in 0 until inChannels) {
                        patch[at++] = if (row in 0 until side && column in 0 until side) {
                            input[channel * plane + row * side + column]
                        } else {
                            0.0
                        }
                    }
                }
            }
            return patch
        }

        /** The convolved field, optionally plus one modulation value per output channel. */
        fun forward(
            input: DoubleArray,
            side: Int,
            modulation: DoubleArray? = null,
        ): DoubleArray {
            val plane = side * side
            val out = DoubleArray(outChannels * plane)
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val patch = patchOf(input, side, x, y)
                    for (channel in 0 until outChannels) {
                        var sum = bias.data[channel]
                        val offset = channel * patch.size
                        for (index in patch.indices) sum += patch[index] * weight.data[offset + index]
                        if (modulation != null) sum += modulation[channel]
                        out[channel * plane + y * side + x] = if (activation) kotlin.math.tanh(sum) else sum
                    }
                }
            }
            return out
        }

        /** Backpropagates into the weights and returns the field's gradient. */
        fun backward(
            input: DoubleArray,
            output: DoubleArray,
            gradient: DoubleArray,
            side: Int,
            modulationGradient: DoubleArray? = null,
        ): DoubleArray {
            val plane = side * side
            val dInput = DoubleArray(inChannels * plane)
            val dPre = DoubleArray(outChannels)
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val position = y * side + x
                    for (channel in 0 until outChannels) {
                        val value = output[channel * plane + position]
                        val derivative = if (activation) 1.0 - value * value else 1.0
                        val g = gradient[channel * plane + position] * derivative
                        dPre[channel] = g
                        modulationGradient?.let { it[channel] += g }
                    }
                    val patch = patchOf(input, side, x, y)
                    TrainerKit.accumulateLinear(patch, dPre, weight, bias)
                    for (channel in 0 until outChannels) {
                        val g = dPre[channel]
                        if (g == 0.0) continue
                        val offset = channel * patch.size
                        var at = 0
                        for (ky in 0 until kernel) {
                            for (kx in 0 until kernel) {
                                val row = y + (ky - padding) * dilation
                                val column = x + (kx - padding) * dilation
                                for (inChannel in 0 until inChannels) {
                                    val value = g * weight.data[offset + at]
                                    at++
                                    if (row in 0 until side && column in 0 until side) {
                                        dInput[inChannel * plane + row * side + column] += value
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return dInput
        }

        fun parameters(): List<TrainerKit.Param> = listOf(weight, bias)
    }

    /**
     * The local half of the denoiser: two 3×3 convolutions and a 1×1 head.
     *
     * The global path decides WHAT the picture is; this decides where its edges are. A fully
     * connected field predicts every pixel from one hidden vector with no notion of a neighbour, and
     * the result is the salt-and-pepper speckle a small diffusion model leaves behind. Five-by-five
     * of local context costs a fraction of the dense path and removes most of it.
     *
     * Its strength is modulated by the timestep and caption, so a noisy step can be smoothed harder
     * than a nearly-clean one instead of blurring the picture at every stage.
     */
    internal class LocalBranch(
        private val config: Config,
        channels: Int,
        random: java.util.Random,
    ) {
        private val hidden = 24
        private val conditionDim = TIME_DIM + CAPTION_DIM
        // DILATED CONVOLUTIONS, not a 5-pixel neighbourhood. A 32×32 field needs a receptive field
        // wide enough to hold the subject: three 3×3 convolutions saw 7 pixels and could only add
        // texture, so the shape was left to the dense path -- and the dense path predicts each
        // pixel from one hidden vector with no notion of a neighbour, which is where the speckle
        // and fragmented strokes came from. Spacing the taps 1, 2, 4 and 8 apart reaches 31 pixels
        // with four cheap convolutions, so the local half can carry the drawing itself.
        private val conv1 = ConvBlock("local_conv1", channels, hidden, 3, 1, true, random, 1)
        private val conv2 = ConvBlock("local_conv2", hidden, hidden, 3, 1, true, random, 2)
        private val conv3 = ConvBlock("local_conv3", hidden, hidden, 3, 1, true, random, 4)
        private val conv4 = ConvBlock("local_conv4", hidden, hidden, 3, 1, true, random, 8)
        private val head = ConvBlock("local_head", hidden, channels, 1, 0, false, random)
        private val mod1 = TrainerKit.gaussian("local_mod1.weight", hidden, conditionDim, random)
        private val mod1Bias = TrainerKit.Param("local_mod1.bias", hidden, 1)
        private val mod2 = TrainerKit.gaussian("local_mod2.weight", hidden, conditionDim, random)
        private val mod2Bias = TrainerKit.Param("local_mod2.bias", hidden, 1)
        private val mod3 = TrainerKit.gaussian("local_mod3.weight", hidden, conditionDim, random)
        private val mod3Bias = TrainerKit.Param("local_mod3.bias", hidden, 1)
        private val mod4 = TrainerKit.gaussian("local_mod4.weight", hidden, conditionDim, random)
        private val mod4Bias = TrainerKit.Param("local_mod4.bias", hidden, 1)

        fun parameters(): List<TrainerKit.Param> = buildList {
            addAll(conv1.parameters())
            addAll(conv2.parameters())
            addAll(conv3.parameters())
            addAll(conv4.parameters())
            addAll(head.parameters())
            add(mod1)
            add(mod1Bias)
            add(mod2)
            add(mod2Bias)
            add(mod3)
            add(mod3Bias)
            add(mod4)
            add(mod4Bias)
        }

        inner class Forward(
            val input: DoubleArray,
            val condition: DoubleArray,
            val first: DoubleArray,
            val second: DoubleArray,
            val third: DoubleArray,
            val fourth: DoubleArray,
            val output: DoubleArray,
            val modulation1: DoubleArray,
            val modulation2: DoubleArray,
            val modulation3: DoubleArray,
            val modulation4: DoubleArray,
        )

        fun forward(input: DoubleArray, condition: DoubleArray): Forward {
            val modulation1 = TrainerKit.linear(condition, mod1, mod1Bias)
            val first = conv1.forward(input, config.side, modulation1)
            val modulation2 = TrainerKit.linear(condition, mod2, mod2Bias)
            val second = conv2.forward(first, config.side, modulation2)
            val modulation3 = TrainerKit.linear(condition, mod3, mod3Bias)
            val third = conv3.forward(second, config.side, modulation3)
            val modulation4 = TrainerKit.linear(condition, mod4, mod4Bias)
            val fourth = conv4.forward(third, config.side, modulation4)
            val output = head.forward(fourth, config.side)
            return Forward(
                input, condition, first, second, third, fourth, output,
                modulation1, modulation2, modulation3, modulation4,
            )
        }

        fun backward(cache: Forward, gradient: DoubleArray) {
            val dFourth = head.backward(cache.fourth, cache.output, gradient, config.side)
            val dModulation4 = DoubleArray(hidden)
            val dThird = conv4.backward(
                cache.third, cache.fourth, dFourth, config.side, dModulation4,
            )
            TrainerKit.accumulateLinear(cache.condition, dModulation4, mod4, mod4Bias)
            val dModulation3 = DoubleArray(hidden)
            val dSecond = conv3.backward(
                cache.second, cache.third, dThird, config.side, dModulation3,
            )
            TrainerKit.accumulateLinear(cache.condition, dModulation3, mod3, mod3Bias)
            val dModulation2 = DoubleArray(hidden)
            val dFirst = conv2.backward(
                cache.first, cache.second, dSecond, config.side, dModulation2,
            )
            TrainerKit.accumulateLinear(cache.condition, dModulation2, mod2, mod2Bias)
            val dModulation1 = DoubleArray(hidden)
            conv1.backward(cache.input, cache.first, dFirst, config.side, dModulation1)
            TrainerKit.accumulateLinear(cache.condition, dModulation1, mod1, mod1Bias)
        }
    }

    class Net(
        val config: Config,
        val inputDim: Int,
        val outputDim: Int,
        random: java.util.Random,
        /** False for the frame kinds: their field is a stack of frames, not one picture. */
        useLocalContext: Boolean = true,
    ) {
        private val plane = 3 * config.side * config.side
        /** Channels this network predicts: 1 for a greyscale image dataset, 3 otherwise. */
        val channels = (outputDim / (config.side * config.side)).coerceIn(1, 3)
        val hidden = config.hidden.coerceIn(32, 512)
        private val frames = if (inputDim - CAPTION_DIM > plane &&
            (inputDim - CAPTION_DIM) % plane == 0
        ) {
            (inputDim - CAPTION_DIM) / plane
        } else {
            1
        }
        private val localContext = useLocalContext && config.channels == channels
        internal val localBranch: LocalBranch? = if (localContext) {
            LocalBranch(config, channels, random)
        } else {
            null
        }
        internal val attention: PixelPatchAttention? = if (config.window > 0) {
            PixelPatchAttention(
                side = config.side,
                frames = frames,
                hidden = hidden,
                window = config.window,
                fullEvery = config.fullEvery.coerceAtLeast(1),
                channels = channels,
                random = random,
            )
        } else {
            null
        }

        val w1 = TrainerKit.gaussian("hidden.weight", hidden, inputDim, random)
        val b1 = TrainerKit.Param("hidden.bias", hidden, 1)
        val w2 = TrainerKit.gaussian("middle.weight", hidden, hidden, random)
        val b2 = TrainerKit.Param("middle.bias", hidden, 1)
        val w3 = TrainerKit.gaussian("out.weight", outputDim, hidden, random)
        val b3 = TrainerKit.Param("out.bias", outputDim, 1)
        // FiLM starts as the identity: a zero scale and shift, so the first step is the unconditioned
        // network and the caption has to earn its effect rather than scramble a fresh one.
        val filmScale = TrainerKit.Param("film.scale", hidden, CAPTION_DIM)
        val filmScaleBias = TrainerKit.Param("film.scale_bias", hidden, 1)
        val filmShift = TrainerKit.Param("film.shift", hidden, CAPTION_DIM)
        val filmShiftBias = TrainerKit.Param("film.shift_bias", hidden, 1)

        fun parameters(): List<TrainerKit.Param> = buildList {
            add(w1)
            add(b1)
            add(w2)
            add(b2)
            add(w3)
            add(b3)
            add(filmScale)
            add(filmScaleBias)
            add(filmShift)
            add(filmShiftBias)
            localBranch?.parameters()?.let { addAll(it) }
            attention?.parameters()?.let { addAll(it) }
        }

        fun parameterCount(): Int = parameters().sumOf { it.data.size }

        fun zeroGradients() {
            parameters().forEach { it.grad.fill(0.0) }
        }

        /** The weights-file shape: the dense shape, then the attention shape when there is one. */
        fun shape(): IntArray = intArrayOf(config.side, inputDim, outputDim, hidden, 2) +
            (attention?.shape ?: intArrayOf())

        inner class Forward(val x: DoubleArray) {
            internal val patch = attention?.Forward(attention.tokensOf(x))
            internal val caption = x.copyOfRange(x.size - CAPTION_DIM, x.size)
            internal val raw = TrainerKit.linear(x, w1, b1)
            internal val scale = TrainerKit.linear(caption, filmScale, filmScaleBias)
            internal val shift = TrainerKit.linear(caption, filmShift, filmShiftBias)
            val hidden1: DoubleArray
            val hidden2: DoubleArray
            val global: DoubleArray
            internal val local: LocalBranch.Forward?
            val output: DoubleArray

            init {
                patch?.let { patch ->
                    for (unit in raw.indices) raw[unit] += patch.context[unit]
                }
                val pre1 = DoubleArray(hidden) { unit ->
                    raw[unit] * (1.0 + scale[unit]) + shift[unit]
                }
                hidden1 = DoubleArray(hidden) { kotlin.math.tanh(pre1[it]) }
                val pre2 = TrainerKit.linear(hidden1, w2, b2)
                hidden2 = DoubleArray(hidden) { kotlin.math.tanh(pre2[it]) }
                global = TrainerKit.linear(hidden2, w3, b3)
                val pixels = channels * config.side * config.side
                local = localBranch?.forward(
                    x.copyOfRange(0, pixels),
                    x.copyOfRange(pixels, x.size),
                )
                output = if (local == null) global else DoubleArray(global.size) { index ->
                    global[index] + local.output[index]
                }
            }
        }
    }

    /**
     * Words to a fixed-width vector, deterministically.
     *
     * Unigrams and bigrams are hashed into signed buckets and the vector is normalised, so two
     * captions that share "a photo of a" still differ where they say "sneaker" or "sandal". A
     * character four-gram channel gives a word the training captions never used a way to reach a
     * nearby word: "sneakers" lands next to "sneaker" instead of nowhere. This is a feature map, not
     * a learned encoder -- nothing here has to be loaded beside the weights or trained separately.
     */
    object CaptionEncoder {

        fun encode(text: String): DoubleArray {
            val vector = DoubleArray(CAPTION_DIM)
            val words = text.lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotBlank() }
            fun add(token: String, weight: Double) {
                val hash = hash(token)
                val slot = (hash and Int.MAX_VALUE) % CAPTION_DIM
                val sign = if (hash < 0) -1.0 else 1.0
                vector[slot] += sign * weight
            }
            for ((index, word) in words.withIndex()) {
                add("w:$word", 1.0)
                if (index + 1 < words.size) add("b:${word}_${words[index + 1]}", 0.65)
                for (start in 0..max(0, word.length - 4)) {
                    if (word.length >= 4) add("c:${word.substring(start, start + 4)}", 0.22)
                }
            }
            var norm = 0.0
            for (value in vector) norm += value * value
            norm = kotlin.math.sqrt(norm)
            if (norm > 1e-9) for (index in vector.indices) vector[index] /= norm
            return vector
        }

        /** FNV-1a over the token, the same hash the name generator uses. */
        private fun hash(value: String): Int {
            var result = 0x811C9DC5.toInt()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                result = result xor (byte.toInt() and 0xFF)
                result *= 0x01000193
            }
            return result
        }
    }

    /**
     * One step's input, target and loss.
     *
     * The assembly is where each task differs; the loss and the gradient are the same weighted mean
     * squared error, because a masked patch and a flow-matching velocity are both "these numbers should
     * have been these numbers".
     */
    class Step(
        task: Task,
        val config: Config,
        val net: Net,
        val example: Example,
        val index: Int,
        random: java.util.Random,
        /** False for a validation or test pass: no caption dropout and no augmentation. */
        val training: Boolean = true,
    ) {
        private val forward: Net.Forward
        private val target: DoubleArray
        private val weights: DoubleArray?
        private val count: Int

        init {
            val side = config.side
            val plane = side * side
            // Captions are dropped while training, never while validating or testing: a held-out
            // number measured with the words removed is not the model the page describes.
            val caption = if (training) {
                if (random.nextDouble() < CAPTION_DROPOUT) DoubleArray(CAPTION_DIM)
                else CaptionEncoder.encode(example.caption)
            } else {
                CaptionEncoder.encode(example.caption)
            }
            when (task) {
                Task.IMAGE -> {
                    val source = maybeFlip(example.frames.first(), side, random, training)
                    val channels = net.channels
                    val clean = if (channels == 1) {
                        // The greyscale plane is the first of the three stored planes; the decoder
                        // already made the three equal for a grey picture.
                        source.copyOfRange(0, plane)
                    } else {
                        source
                    }
                    if (net.inputDim == clean.size + TIME_DIM + CAPTION_DIM) {
                        // Flow matching: x_t = (1-t)·x + t·noise, trained to predict noise - x.
                        //
                        // The timestep is drawn with a bias toward the low-noise end. A uniform
                        // draw spends half its steps above t = 0.5, where x_t is almost pure noise
                        // and the target is a random vector no model can predict; those steps cost
                        // loss but teach shape nothing. Squaring the draw moves the mass to where
                        // the picture is still visible.
                        val noise = DoubleArray(clean.size) { random.nextGaussian() }
                        val t = Math.pow(random.nextDouble(), 1.5)
                        val input = DoubleArray(clean.size + TIME_DIM + CAPTION_DIM)
                        for (index in clean.indices) {
                            input[index] = (1.0 - t) * clean[index] + t * noise[index]
                        }
                        val embedding = timeEmbedding(t)
                        for (index in embedding.indices) input[clean.size + index] = embedding[index]
                        System.arraycopy(caption, 0, input, clean.size + TIME_DIM, CAPTION_DIM)
                        forward = net.Forward(input)
                        target = DoubleArray(clean.size) { index -> noise[index] - clean[index] }
                        weights = null
                        count = clean.size
                    } else {
                        // Masked patch: a square is hidden and predicted from the rest. WHICH square
                        // is fixed per example and varies across the dataset -- a run that hid a
                        // different one every step would be a run whose loss jumps for reasons that
                        // are not the model, and a reproducible run is worth more than the extra
                        // variance.
                        val patch = max(1, side / 4)
                        val picker = java.util.Random(config.seed * 31 + index)
                        val left = picker.nextInt(side - patch + 1)
                        val top = picker.nextInt(side - patch + 1)
                        val input = DoubleArray(clean.size + plane + CAPTION_DIM)
                        for (index in clean.indices) input[index] = clean[index]
                        for (row in top until top + patch) {
                            for (column in left until left + patch) {
                                val index = row * side + column
                                for (channel in 0 until channels) input[channel * plane + index] = 0.0
                                input[clean.size + index] = 1.0
                            }
                        }
                        System.arraycopy(caption, 0, input, clean.size + plane, CAPTION_DIM)
                        forward = net.Forward(input)
                        target = clean.copyOf()
                        val mask = DoubleArray(clean.size)
                        for (row in top until top + patch) {
                            for (column in left until left + patch) {
                                val index = row * side + column
                                for (channel in 0 until channels) mask[channel * plane + index] = 1.0
                            }
                        }
                        weights = mask
                        count = channels * patch * patch
                    }
                }
                Task.INTERPOLATION -> {
                    val left = maybeFlip(example.frames[0], side, random, training)
                    val right = maybeFlip(example.frames[1], side, random, training)
                    val input = DoubleArray(left.size + right.size + CAPTION_DIM)
                    System.arraycopy(left, 0, input, 0, left.size)
                    System.arraycopy(right, 0, input, left.size, right.size)
                    System.arraycopy(caption, 0, input, left.size + right.size, CAPTION_DIM)
                    forward = net.Forward(input)
                    target = example.target?.let { maybeFlip(it, side, random, training) } ?: right
                    weights = null
                    count = target.size
                }
                Task.VIDEO -> {
                    val pixels = 3 * plane * example.frames.size
                    val input = DoubleArray(pixels + CAPTION_DIM)
                    var offset = 0
                    for (frame in example.frames) {
                        System.arraycopy(frame, 0, input, offset, frame.size)
                        offset += frame.size
                    }
                    System.arraycopy(caption, 0, input, pixels, CAPTION_DIM)
                    forward = net.Forward(input)
                    target = example.target ?: example.frames.last()
                    weights = null
                    count = target.size
                }
            }
        }

        /** Mirrors a field horizontally, the same way every frame of an example. */
        private fun maybeFlip(field: DoubleArray, side: Int, random: java.util.Random, training: Boolean): DoubleArray {
            if (!config.flip || !training || !random.nextBoolean()) return field
            val plane = side * side
            val flipped = DoubleArray(field.size)
            for (channel in 0 until 3) {
                for (row in 0 until side) {
                    for (column in 0 until side) {
                        flipped[channel * plane + row * side + column] =
                            field[channel * plane + row * side + (side - 1 - column)]
                    }
                }
            }
            return flipped
        }

        /**
         * The share of predicted pixel channels within a tenth of the target.
         *
         * A denoiser is a regression, so this is a reconstruction accuracy rather than a label
         * accuracy: of the values it produced, how many are close to the image the clip actually
         * held. A tenth of this model's [-1, 1] range is about thirteen levels of an eight-bit
         * channel, which is a difference the eye does not see -- a tighter tolerance would report a
         * good reconstruction as inaccurate for rounding alone.
         */
        fun accuracy(): Double {
            var close = 0
            var total = 0
            for (index in target.indices) {
                val weight = weights?.get(index) ?: 1.0
                if (weight == 0.0) continue
                if (abs(forward.output[index] - target[index]) <= 0.10) close++
                total++
            }
            return if (total == 0) Double.NaN else close.toDouble() / total
        }

        /** Mean squared error over the outputs that count. */
        fun loss(): Double {
            var total = 0.0
            for (index in target.indices) {
                val weight = weights?.get(index) ?: 1.0
                if (weight == 0.0) continue
                val error = forward.output[index] - target[index]
                total += weight * error * error
            }
            return total / count.coerceAtLeast(1)
        }

        /** Backpropagates [loss] into the network's gradients. [zeroGradients] happens here. */
        fun backward() {
            net.zeroGradients()
            val hidden = net.hidden
            val dOutput = DoubleArray(target.size)
            for (index in target.indices) {
                val weight = weights?.get(index) ?: 1.0
                if (weight == 0.0) continue
                dOutput[index] = 2.0 * weight * (forward.output[index] - target[index]) / count.coerceAtLeast(1)
            }
            TrainerKit.accumulateLinear(forward.hidden2, dOutput, net.w3, net.b3)
            // The second tanh, then the second dense layer, then the first tanh.
            val dPre2 = DoubleArray(hidden)
            for (unit in 0 until hidden) {
                var sum = 0.0
                for (index in target.indices) sum += dOutput[index] * net.w3.at(index, unit)
                val value = forward.hidden2[unit]
                dPre2[unit] = sum * (1.0 - value * value)
            }
            TrainerKit.accumulateLinear(forward.hidden1, dPre2, net.w2, net.b2)
            val dPre1 = DoubleArray(hidden)
            for (unit in 0 until hidden) {
                var sum = 0.0
                for (other in 0 until hidden) sum += dPre2[other] * net.w2.at(other, unit)
                val value = forward.hidden1[unit]
                dPre1[unit] = sum * (1.0 - value * value)
            }
            // FiLM: pre = raw·(1+scale) + shift. The shift's gradient is dPre, the scale's is dPre
            // times the raw activation, and the dense path receives dPre scaled by (1+scale).
            TrainerKit.accumulateLinear(forward.caption, dPre1, net.filmShift, net.filmShiftBias)
            val dScale = DoubleArray(hidden) { dPre1[it] * forward.raw[it] }
            TrainerKit.accumulateLinear(forward.caption, dScale, net.filmScale, net.filmScaleBias)
            val dRaw = DoubleArray(hidden) { dPre1[it] * (1.0 + forward.scale[it]) }
            TrainerKit.accumulateLinear(forward.x, dRaw, net.w1, net.b1)
            val patch = forward.patch
            if (patch != null) net.attention?.backward(patch, dRaw)
            // The output is the global field plus the local refiner, so both receive the same
            // gradient and the split between them is learned rather than fixed.
            forward.local?.let { local -> net.localBranch?.backward(local, dOutput) }
        }

        /** The sinusoidal embedding of a flow-matching timestep, as the generated script uses too. */
        private fun timeEmbedding(t: Double): DoubleArray {
            val half = TIME_DIM / 2
            val out = DoubleArray(TIME_DIM)
            for (index in 0 until half) {
                val frequency = exp(-ln(10000.0) * index / max(1, half - 1).toDouble())
                out[index] = sin(t * frequency)
                out[half + index] = cos(t * frequency)
            }
            return out
        }
    }

    // ── The artifacts ───────────────────────────────────────────────────────────────────────────

    /** A trained pixel model: the network, how it was trained, and what it can be asked to do. */
    class Trained(
        val task: Task,
        val net: Net,
        val config: Config,
        val objective: String,
        val diffusion: Boolean,
        val examples: Int,
        val inputDim: Int,
        val outputDim: Int,
        /** The frame encoder/decoder pair a video build trains; null for the other two tasks. */
        val coder: CoderTrainer.Trained? = null,
        val trainingCount: Int = 0,
        val validationCount: Int = 0,
        val testCount: Int = 0,
        val testLoss: Double = Double.NaN,
        /** True when the dataset's rows carried captions, so the prompt actually conditions it. */
        val conditional: Boolean = false,
    ) {
        /** The container files the run wrote; empty until [writeArtifacts]. Read by the summary. */
        var artifactFiles: List<File> = emptyList()
            private set
        /** The network's output for one prepared input. */
        fun predict(input: DoubleArray): DoubleArray = net.Forward(input).output

        /**
         * Samples an image by integrating the velocity field from noise, for the diffusion families.
         *
         * Euler's method, step by step from t = 1 to t = 0: the model was trained to say which way an
         * image moves toward noise, so walking the other way pulls noise toward an image.
         */
        fun generate(random: java.util.Random, steps: Int = SAMPLE_STEPS): DoubleArray =
            generate("", random, steps, 1.0)

        /**
         * Samples the picture the prompt describes.
         *
         * The integration is Euler's method from noise at t = 1 to the image end at t = 0. When the
         * model was trained with captions, the prompt is evaluated twice per step -- once with the
         * words, once with an empty caption -- and the difference is amplified by [guidance]. That is
         * what makes a prompt a condition rather than a mood: the unconditioned half is what the
         * dataset always looks like, and the conditional half is what these particular words add.
         */
        fun generate(
            prompt: String,
            random: java.util.Random,
            steps: Int = SAMPLE_STEPS,
            guidance: Double = GUIDANCE,
        ): DoubleArray {
            val side = config.side
            val plane = side * side
            val size = net.channels * plane
            val caption = CaptionEncoder.encode(prompt)
            val empty = DoubleArray(CAPTION_DIM)
            val useGuidance = conditional && guidance > 1.0 && prompt.isNotBlank()
            var x = DoubleArray(size) { random.nextGaussian() }
            val count = steps.coerceAtLeast(1)
            for (step in 0 until count) {
                val t = 1.0 - step.toDouble() / count
                val velocity = velocity(x, t, caption)
                if (useGuidance) {
                    val unconditioned = velocity(x, t, empty)
                    for (index in velocity.indices) {
                        velocity[index] = unconditioned[index] +
                            guidance * (velocity[index] - unconditioned[index])
                    }
                }
                val delta = -1.0 / count
                for (index in 0 until size) {
                    x[index] = (x[index] + velocity[index] * delta).coerceIn(-1.6, 1.6)
                }
            }
            for (index in x.indices) x[index] = x[index].coerceIn(-1.0, 1.0)
            // The page draws RGB. A one-channel model's grey plane is repeated into three channels
            // here, so every caller gets a picture and no caller has to know how many channels the
            // dataset had.
            if (net.channels == 1) {
                val rgb = DoubleArray(3 * plane)
                System.arraycopy(x, 0, rgb, 0, plane)
                System.arraycopy(x, 0, rgb, plane, plane)
                System.arraycopy(x, 0, rgb, 2 * plane, plane)
                return rgb
            }
            return x
        }

        /** The predicted velocity at a timestep for one caption vector. */
        private fun velocity(x: DoubleArray, t: Double, caption: DoubleArray): DoubleArray {
            val size = x.size
            val input = DoubleArray(size + TIME_DIM + CAPTION_DIM)
            System.arraycopy(x, 0, input, 0, size)
            val half = TIME_DIM / 2
            for (index in 0 until TIME_DIM) {
                val dimension = index % half
                val frequency = exp(-ln(10000.0) * dimension / max(1, half - 1).toDouble())
                input[size + index] = if (index < half) sin(t * frequency) else cos(t * frequency)
            }
            System.arraycopy(caption, 0, input, size + TIME_DIM, CAPTION_DIM)
            return net.Forward(input).output
        }

        /** Adds the prompt's caption vector to a masked-patch input and predicts the patch. */
        fun predictWithCaption(input: DoubleArray, prompt: String): DoubleArray {
            val full = DoubleArray(input.size + CAPTION_DIM)
            System.arraycopy(input, 0, full, 0, input.size)
            System.arraycopy(CaptionEncoder.encode(prompt), 0, full, input.size, CAPTION_DIM)
            return net.Forward(full).output
        }

        /** The middle frame between [left] and [right], for an interpolator. */
        fun interpolate(left: DoubleArray, right: DoubleArray): DoubleArray =
            predict(withEmptyCaption(listOf(left, right)))

        /** The frame after the given ones, for a video model. */
        fun nextFrame(frames: List<DoubleArray>): DoubleArray =
            predict(withEmptyCaption(frames))

        /**
         * The frames followed by an empty caption vector when the network was built to read one.
         *
         * Both frame tasks train with the caption block on the end -- the same shape rule every
         * pixel job follows -- so a caller handing in only pixels would be one block short and the
         * first matrix multiply would read past the end of its input. The empty vector means "no
         * words", which is exactly the condition these helpers promise.
         */
        private fun withEmptyCaption(frames: List<DoubleArray>): DoubleArray {
            val pixels = frames.sumOf { it.size }
            val extra = (inputDim - pixels).coerceAtLeast(0)
            val input = DoubleArray(pixels + extra)
            var offset = 0
            for (frame in frames) {
                System.arraycopy(frame, 0, input, offset, frame.size)
                offset += frame.size
            }
            return input
        }

        fun writeArtifacts(
            directory: File,
            model: CraftedModel,
            firstLoss: Double,
            lastLoss: Double,
            validationLoss: Double,
            testLoss: Double,
            steps: Int,
        ): File {
            directory.mkdirs()
            TrainerKit.WeightFile.write(
                File(directory, task.weightsFile),
                task.magic,
                net.shape(),
                net.parameters(),
            )
            // The chosen container. A video build is three files when it is safetensors and one when
            // it is a GGUF, and the list is built from [ModelCraft.fileComponents]' own names so the
            // page, the recipe and the writers cannot disagree about how many there are.
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
            artifactFiles = ModelFiles.writeAll(model, directory, task.baseName, components)
            val metadata = JSONObject().apply {
                put("kind", "${task.label}-model")
                put("model", model.name)
                put("crafted_model_id", model.id)
                put("architecture", model.architecture)
                put("family", model.family)
                put("objective", objective)
                put("diffusion", diffusion)
                put(
                    "note",
                    when {
                        task == Task.IMAGE && diffusion ->
                            "A flow-matching denoiser over pixels, conditioned on the row's caption " +
                                "through a hashed caption vector and a per-unit scale and shift. There " +
                                "is no VAE and no pretrained text encoder beside it: the space is RGB " +
                                "pixels, the captions are learned from this dataset, and sampling " +
                                "amplifies what the prompt changes. It learned this dataset's images " +
                                "at the size written here."
                        task == Task.IMAGE ->
                            "A masked-patch autoencoder over pixels: a square is hidden and " +
                                "reconstructed from the rest. No discriminator and no codebook is in " +
                                "this build, so what was trained is reconstruction over this " +
                                "dataset's images, not an adversarial or discrete-coded model."
                        task == Task.INTERPOLATION ->
                            "Reconstructs the middle frame directly from the frames on either side. " +
                                "The flow-estimating families in that tab have a motion estimator " +
                                "this build does not have, so the reconstruction is direct."
                        else ->
                            "Predicts the next frame from the two before it. Conditioning and " +
                                "cascade are what the recipe describes for a full-scale trainer; " +
                                "this is the temporal predictor at its core."
                    },
                )
                // The recipe's attention settings, and what they actually built. A pixel field has
                // no tokens of its own, so the field is cut into 4×4 patches and a small attention
                // body runs over them; with the checkbox off that body is absent and these numbers
                // say so rather than implying a window that is not there.
                put("sliding_window_size", config.window)
                put("full_attention_per_nth_layer", config.fullEvery)
                val attention = net.attention
                put(
                    "attention",
                    JSONObject().apply {
                        put("applied", attention != null)
                        put("sliding_window", attention != null)
                        put("window", config.window)
                        put("full_attention_every_nth_layer", config.fullEvery)
                        if (attention != null) {
                            put("tokens", attention.tokenCount)
                            put("layers", attention.layers)
                            put("d_model", attention.dModel)
                            put("patch", attention.patch)
                            put(
                                "per_layer",
                                JSONArray((0 until attention.layers).map { layer ->
                                    if (attention.isFull(layer)) "full" else "sliding:${config.window}"
                                }),
                            )
                            put(
                                "note",
                                "The field is cut into ${attention.patch}×${attention.patch} patches " +
                                    "(${attention.tokenCount} tokens); sliding layers attend to " +
                                    "${config.window} neighbouring patches and every " +
                                    "${config.fullEvery}th layer attends to all of them. The same " +
                                    "masks are used in training and generation.",
                            )
                        } else {
                            put(
                                "note",
                                "Sliding attention was not enabled for this run, so the pixel model " +
                                    "is the dense predictor with no attention body.",
                            )
                        }
                    },
                )
                put("side", config.side)
                put("channels", net.channels)
                put("input_dim", inputDim)
                put("output_dim", outputDim)
                put("hidden", net.hidden)
                put("parameters", net.parameterCount().toLong())
                put("examples", examples)
                put("train_examples", trainingCount)
                put("validation_examples", validationCount)
                put("test_examples", testCount)
                put("conditional", conditional)
                put("caption_dim", CAPTION_DIM)
                put("guidance", GUIDANCE)
                put(
                    "conditioning",
                    if (conditional) {
                        "The caption is hashed into a ${CAPTION_DIM}-value vector, concatenated to the " +
                            "field and used as a per-unit scale and shift. Captions were dropped " +
                            "${(CAPTION_DROPOUT * 100).toInt()}% of training steps so guidance has an " +
                            "unconditioned branch."
                    } else {
                        "This dataset's rows carried no captions, so the conditioning path trained on " +
                            "an empty vector and the prompt only chooses the starting noise."
                    },
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
                if (!testLoss.isNaN()) put("loss_test", testLoss)
                put("weights_file", task.weightsFile)
                put("weights_format", "magic '${task.magic}', then the shape, then one tensor per entry")
                put(ModelCraft.KEY_FILE_FORMAT, ModelCraft.fileFormat(model).recipeValue)
                put("model_files", JSONArray(artifactFiles.map { it.name }))
                coder?.let { pair ->
                    put(
                        "encoder_decoder",
                        JSONObject().apply {
                            put("trained_on", "the dataset's own frames — the same targets the model predicts")
                            put("inputs", pair.inputs)
                            put("code", pair.code)
                            put("examples", pair.examples)
                            put("steps", pair.steps.toLong())
                            put("reconstruction_loss_first", pair.firstLoss)
                            put("reconstruction_loss_last", pair.lastLoss)
                            put(
                                "note",
                                "A bottleneck autoencoder: the encoder is one tanh layer that turns a " +
                                    "frame into a code of the width above, the decoder one linear layer " +
                                    "that turns the code back into a frame. Not a VAE — there is no " +
                                    "sampling and no codebook in this build.",
                            )
                        },
                    )
                }
                put(
                    "can",
                    JSONArray(
                        when (task) {
                            Task.IMAGE -> if (diffusion) listOf("generate(random, steps)") else listOf("predict(input)")
                            Task.INTERPOLATION -> listOf("interpolate(left, right)")
                            Task.VIDEO -> if (coder != null) {
                                listOf("nextFrame(frames)", "encode(frame)", "decode(code)")
                            } else {
                                listOf("nextFrame(frames)")
                            }
                        },
                    ),
                )
            }
            val file = File(directory, task.metadataFile)
            file.writeText(metadata.toString())
            return file
        }
    }

    /** Reads an artifact pair back, for a generator and for the tests. */
    fun load(directory: File, task: Task): Trained? {
        val metadata = runCatching { JSONObject(File(directory, task.metadataFile).readText()) }.getOrNull()
            ?: return null
        val weights = File(directory, task.weightsFile)
        if (!weights.isFile) return null
        val config = Config(
            side = metadata.optInt("side", 16).coerceIn(8, MAX_SIDE),
            epochs = metadata.optInt("epochs", 0),
            stepsPerEpoch = metadata.optInt("steps_per_epoch", 0),
            learningRate = metadata.optDouble("learning_rate_used", DEFAULT_LEARNING_RATE),
            gradientClip = metadata.optDouble("gradient_clip", 1.0),
            seed = metadata.optLong("seed", 42L),
            window = metadata.optInt("sliding_window_size", 0),
            fullEvery = metadata.optInt("full_attention_per_nth_layer", 0),
            hidden = metadata.optInt("hidden", HIDDEN),
            channels = metadata.optInt("channels", 3),
        )
        val inputDim = metadata.optInt("input_dim", 0)
        val outputDim = metadata.optInt("output_dim", 3 * config.side * config.side)
        if (inputDim <= 0 || outputDim <= 0) return null
        val net = Net(
            config, inputDim, outputDim, java.util.Random(config.seed),
            useLocalContext = task == Task.IMAGE,
        )
        val read = TrainerKit.WeightFile.read(
            weights,
            task.magic,
            net.shape(),
            net.parameters(),
        )
        if (!read) return null
        return Trained(
            task = task,
            net = net,
            config = config,
            objective = metadata.optString("objective", ""),
            diffusion = metadata.optBoolean("diffusion", false),
            examples = metadata.optInt("examples", 0),
            inputDim = inputDim,
            outputDim = outputDim,
            trainingCount = metadata.optInt("train_examples", 0),
            validationCount = metadata.optInt("validation_examples", 0),
            testCount = metadata.optInt("test_examples", 0),
            testLoss = metadata.optDouble("loss_test", Double.NaN),
            conditional = metadata.optBoolean("conditional", false),
        )
    }
}
