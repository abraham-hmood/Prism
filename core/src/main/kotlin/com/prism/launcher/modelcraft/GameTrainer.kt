package com.prism.launcher.modelcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.assetcraft.GameMesh
import com.prism.launcher.assetcraft.MeshDetail
import com.prism.launcher.assetcraft.MeshGenerator
import com.prism.launcher.assetcraft.MeshIO
import com.prism.launcher.datasetcraft.DatasetReferences
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The on-device trainer for game asset models.
 *
 * ## What is actually learned
 *
 * Every mesh a game dataset holds is reduced to sixteen numbers: its bounding proportions, how busy
 * its vertex and triangle counts are, a four-bin angular silhouette, a four-bin vertical profile, and
 * a roughness measure. That vector is a real, compact description of a game asset -- enough to build
 * a recognisable rock, tree, creature or building back out of it, and small enough to train on a
 * phone in seconds.
 *
 * The architecture chosen on the form decides how those numbers are modelled:
 *
 *  - **Any diffuser family** trains a flow-matching denoiser over the shape vectors. Sampling
 *    integrates the learned velocity field from noise back to a shape.
 *  - **Every other family** trains a token predictor over the same vectors quantised into twelve
 *    bins per dimension. It is the part of a transformer / byte-latent / encoder model this build can
 *    train honestly on a phone: a sequence model over shape tokens. The metadata names the objective
 *    so nobody mistakes it for the full architecture the family implies.
 *
 * Captions from the dataset's CSV are hashed into an eight-number conditioning vector, so a prompt
 * that resembles a caption pulls the sampled shape toward that caption's examples. With no captions
 * the conditioning is zero and the prompt still chooses a starting seed and a built category.
 *
 * ## The base-model switch
 *
 * A recipe with `from_scratch` off and a readable game checkpoint as its base initialises the
 * weights from that checkpoint instead of from noise. The checkpoint is the same
 * `game_model.weights` this trainer writes, which is what makes a ModelCraft game model reusable as
 * another one's starting point. An unreadable or incompatible base is logged and treated as a
 * from-scratch run rather than failing a long dataset preparation.
 */
object GameTrainer {

    const val META_FILE = "game_model.json"
    const val WEIGHTS_FILE = "game_model.weights"
    const val MAGIC = "PRISM-GAME-1"

    /** How many numbers describe one shape. Mirrors [MeshGenerator.SHAPE_DIM]. */
    const val SHAPE_DIM = MeshGenerator.SHAPE_DIM

    /** The polygon floor every sampled game asset is refined to. */
    const val TARGET_TRIANGLES = MeshDetail.DEFAULT_MIN_TRIANGLES

    /** The texture side GameCraft renders for a sampled asset. */
    const val TEXTURE_SIZE = MeshDetail.TEXTURE_SIZE

    /** Bins per dimension for the token architectures. */
    const val TOKEN_BINS = 12

    private const val HIDDEN = 64
    private const val CONDITION = 8
    private const val POSITION_DIM = 6
    private const val MAX_EXAMPLES = 600
    private const val MAX_SIDE = 32

    private val MESH_EXTENSIONS = setOf("obj", "ply", "glb")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tga", "dds", "exr")

    /** One training example: a normalised shape vector and the caption's conditioning vector. */
    private class Example(val shape: DoubleArray, val condition: DoubleArray, val source: String)

    /** A trained game model, ready to sample meshes. */
    class Trained internal constructor(
        private val head: Head,
        private val mean: DoubleArray,
        private val std: DoubleArray,
        val architecture: String,
        val family: String,
        val diffusion: Boolean,
        val meshExamples: Int,
        val imageExamples: Int,
        val slidingWindow: Boolean,
        val window: Int,
        val fullAttentionEveryNthLayer: Int,
        val trainingSteps: Int,
        val lastLoss: Double,
        val validationLoss: Double,
        val metadata: JSONObject,
    ) {
        fun sample(prompt: String, seed: Long): GameMesh {
            val random = Random(seed)
            val condition = conditionOf(prompt)
            val normalised = head.sample(random, condition)
            val shape = DoubleArray(SHAPE_DIM) { index ->
                ((normalised[index] * std[index]) + mean[index]).coerceIn(-1.0, 1.0)
            }
            val mesh = MeshGenerator.fromShape(shape, prompt, seed)
            // THE DESCRIPTOR IS THE SHAPE; THE DETAIL IS THE DELIVERY. A category builder gives
            // the silhouette, then this raises it to the polygon budget a game-ready asset needs.
            // Kept out of the predictor because a sixteen-number latent cannot honestly carry
            // eighty thousand vertices -- pretending otherwise would be the kind of claim this
            // trainer's own notes exist to avoid.
            val detailed = MeshDetail.refine(mesh, TARGET_TRIANGLES, seed)
            return GameMesh(
                name = detailed.name,
                vertices = detailed.vertices,
                triangles = detailed.triangles,
                color = detailed.color,
                faceColors = detailed.faceColors,
                category = detailed.category,
                animation = detailed.animation,
            )
        }

        fun parameterCount(): Int = head.parameterCount()

        fun objective(): String = if (diffusion) {
            "flow-matching denoiser over shape descriptors"
        } else {
            "autoregressive prediction over quantised shape tokens"
        }

        fun summary(): String = buildString {
            append(parameterCount())
            append(" parameters · ")
            append(if (diffusion) "flow matching" else "$TOKEN_BINS-bin shape tokens")
            append(" · ")
            append(meshExamples)
            append(" mesh")
            if (meshExamples != 1) append("es")
            if (imageExamples > 0) {
                append(" and ")
                append(imageExamples)
                append(" texture")
                if (imageExamples != 1) append("s")
            }
            append(" learned from")
            if (slidingWindow) append(" · sliding window of $window tokens")
        }
    }

    /**
     * Trains a game model and writes [META_FILE] and [WEIGHTS_FILE] beside the dataset.
     *
     * @return the line ModelCraft shows on success, or null when this dataset held nothing the
     *         trainer could read. Null is not an error: the recipe still exists, and the run's note
     *         says which files it tried.
     */
    fun train(model: CraftedModel, recipe: File): String? {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val config = readConfig(recipe)
        val examples = examples(directory)
        if (examples.isEmpty()) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Nothing to train a game model on in ${model.id}: no .obj, .ply or .glb mesh and no " +
                    "readable texture could be described",
            )
            return null
        }
        val mean = DoubleArray(SHAPE_DIM) { index ->
            examples.sumOf { it.shape[index] } / examples.size
        }
        val std = DoubleArray(SHAPE_DIM) { index ->
            val variance = examples.sumOf { value ->
                val delta = value.shape[index] - mean[index]
                delta * delta
            } / examples.size
            sqrt(variance).coerceAtLeast(0.05)
        }
        val normalised = examples.map { example ->
            Example(
                DoubleArray(SHAPE_DIM) { index ->
                    ((example.shape[index] - mean[index]) / std[index]).coerceIn(-3.0, 3.0)
                },
                example.condition,
                example.source,
            )
        }
        val random = Random(config.seed)
        val head: Head = if (config.diffusion) {
            DiffusionHead(
                SHAPE_DIM, HIDDEN, CONDITION, random,
                window = if (config.slidingWindow) config.window else 0,
                fullEvery = config.fullAttentionEveryNthLayer,
            )
        } else {
            TokenHead(
                SHAPE_DIM, TOKEN_BINS, HIDDEN, CONDITION, random,
                window = if (config.slidingWindow) config.window else 0,
                fullEvery = config.fullAttentionEveryNthLayer,
            )
        }

        // A BASE IS WARM-STARTED, NOT LOADED AS A WHOLE MODEL. The net above is the architecture
        // this recipe asks for; a compatible base only supplies its first weights. A base whose
        // shape differs is named in the log and otherwise ignored, which is better than refusing a
        // run whose dataset is already prepared over a checkpoint mismatch.
        var basePath: String? = null
        if (!ModelCraft.fromScratch(model)) {
            val base = baseWeights(model, directory)
            if (base != null) {
                val read = TrainerKit.WeightFile.read(base, MAGIC, head.shape, head.parameters)
                if (read) {
                    basePath = base.absolutePath
                    PrismPlatform.log.info("ModelCraft", "Game model ${model.id} warm-started from $base")
                } else {
                    PrismPlatform.log.warn(
                        "ModelCraft",
                        "The game base $base does not match this architecture; training from random weights",
                    )
                }
            }
        }

        val adam = TrainerKit.Adam(config.learningRate)
        val total = config.epochs * (if (config.stepsPerEpoch > 0) config.stepsPerEpoch else normalised.size)
        val lossHistory = mutableListOf<Double>()
        var steps = 0
        var epoch = 0
        // Deterministic example order and noise, seeding from the recipe: the same dataset and
        // recipe make the same weights, the way every other trainer here does.
        val noise = Random(config.seed xor 0x6A3E2)
        var validationLoss = Double.NaN
        while (epoch < config.epochs) {
            epoch++
            var sum = 0.0
            val perEpoch = if (config.stepsPerEpoch > 0) config.stepsPerEpoch else normalised.size
            repeat(perEpoch) { offset ->
                val example = normalised[(steps + offset) % normalised.size]
                head.zero()
                val stepLoss = head.step(example.shape, example.condition, noise)
                sum += stepLoss
                adam.step(head.parameters, 1.0)
                steps++
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = epoch,
                        epochs = config.epochs,
                        step = offset + 1,
                        steps = perEpoch,
                        file = File(example.source).name,
                        loss = stepLoss,
                        accuracy = head.lastAccuracy,
                    ),
                )
            }
            val meanLoss = sum / perEpoch.coerceAtLeast(1)
            lossHistory += meanLoss
            validationLoss = normalised.take(12).map { example ->
                head.loss(example.shape, example.condition, Random(config.seed))
            }.average()
            PrismPlatform.log.info(
                "ModelCraft",
                "game epoch $epoch: train ${"%.5f".format(meanLoss)} val ${"%.5f".format(validationLoss)}",
            )
        }
        val lastLoss = lossHistory.lastOrNull() ?: 0.0
        val meshCount = examples.count { MESH_EXTENSIONS.contains(File(it.source).extension.lowercase(Locale.ROOT)) }
        val imageCount = examples.size - meshCount
        val meta = JSONObject().apply {
            put("kind", ModelCraft.Kind.GAME.name)
            put("model_type", ModelCraft.Kind.GAME.modelType)
            put("name", model.name)
            put("architecture", model.architecture)
            put("family", model.family)
            put("objective", if (config.diffusion) "flow-matching" else "shape-token-autoregression")
            put("shape_dim", SHAPE_DIM)
            put("token_bins", TOKEN_BINS)
            put("hidden", HIDDEN)
            put("condition_dim", CONDITION)
            put("parameters", head.parameterCount())
            put("target_triangles", TARGET_TRIANGLES)
            put("texture_resolution", TEXTURE_SIZE)
            put("examples", examples.size)
            put("mesh_examples", meshCount)
            put("image_examples", imageCount)
            put("epochs", config.epochs)
            put("steps_per_epoch", config.stepsPerEpoch)
            put("steps", steps)
            put("first_loss", lossHistory.firstOrNull() ?: 0.0)
            put("last_loss", lastLoss)
            put("validation_loss", validationLoss)
            put("seed", config.seed)
            // WHICH BASE THIS RUN STARTED FROM, recorded in the artifact rather than only in the
            // log. A warm-started model that cannot say what it was fine-tuned from is impossible
            // to reproduce later, and AssetCraft's provenance line is built from this metadata.
            put("base_model", basePath.orEmpty())
            put("warm_started", basePath != null)
            put("sliding_window", config.slidingWindow)
            put("window", if (config.slidingWindow) config.window else 0)
            put("full_attention_every_nth_layer", config.fullAttentionEveryNthLayer)
            put(
                "attention",
                JSONObject().apply {
                    val applied = config.slidingWindow
                    put("applied", applied)
                    put("sliding_window", config.slidingWindow)
                    put("window", config.window)
                    put("full_attention_every_nth_layer", config.fullAttentionEveryNthLayer)
                    if (applied) {
                        put("layers", maxOf(2, minOf(4, config.fullAttentionEveryNthLayer)))
                        put("d_model", 16)
                        put(
                            "note",
                            "Every shape coordinate is a token: a sliding layer sees the last " +
                                "${config.window} coordinates and a full layer sees the whole " +
                                "sixteen, one full layer every ${config.fullAttentionEveryNthLayer}. " +
                                "The same masks are used in training and sampling.",
                        )
                    } else {
                        put("note", "Sliding attention was not enabled for this run.")
                    }
                },
            )
            put("mean", JSONArray(mean.toList()))
            put("std", JSONArray(std.toList()))
            put(
                "note",
                if (config.diffusion) {
                    "A flow-matching shape denoiser trained on this dataset's own meshes. The " +
                        "sixteen-number descriptor is the model's latent; a mesh is built back out of it."
                } else {
                    "A shape-token sequence model trained on this dataset's own meshes. The " +
                        "sixteen-number descriptor is quantised into twelve bins per dimension; the " +
                        "family's full architecture is not built here and the objective says what ran."
                },
            )
            put("created_at", System.currentTimeMillis())
        }
        val weights = File(directory, WEIGHTS_FILE)
        runCatching {
            TrainerKit.WeightFile.write(weights, MAGIC, head.shape, head.parameters)
            File(directory, META_FILE).writeText(meta.toString())
        }.onFailure {
            PrismPlatform.log.warn("ModelCraft", "Could not write the game model: ${it.message}")
            return null
        }
        val trained = Trained(
            head, mean, std, model.architecture, model.family, config.diffusion,
            meshCount, imageCount, config.slidingWindow, config.window,
            config.fullAttentionEveryNthLayer, steps, lastLoss, validationLoss, meta,
        )
        PrismPlatform.log.info(
            "ModelCraft",
            "game model for ${model.id}: ${trained.parameterCount()} parameters, $steps steps, " +
                "${"%.5f".format(lastLoss)} loss",
        )
        return "Trained a ${if (config.diffusion) "flow-matching" else "shape-token"} game model on " +
            "${examples.size} example(s) · ${trained.parameterCount()} parameters · " +
            "${if (config.slidingWindow) "sliding window of ${config.window} tokens" else "full attention"}"
    }

    /** Loads the artifact a previous run wrote, or null when this model has none. */
    fun load(model: CraftedModel): Trained? {
        if (model.kind != ModelCraft.Kind.GAME) return null
        return loadFromDirectory(
            ModelCraft.Store.datasetDir(model.id),
            model.architecture,
            model.family,
        )
    }

    /**
     * Loads a game model from any folder that has [META_FILE] and [WEIGHTS_FILE].
     *
     * The imported-model path uses this: a ModelCraft export carries those two files, and a user
     * who copies one onto their phone should not have to recreate its model record first.
     */
    fun loadFromDirectory(
        directory: File,
        fallbackArchitecture: String = "Game asset model",
        fallbackFamily: String = "",
    ): Trained? {
        val metaFile = File(directory, META_FILE)
        val weightsFile = File(directory, WEIGHTS_FILE)
        if (!metaFile.isFile || !weightsFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        val mean = doubles(meta.optJSONArray("mean")) ?: return null
        val std = doubles(meta.optJSONArray("std")) ?: return null
        if (mean.size != SHAPE_DIM || std.size != SHAPE_DIM) return null
        val diffusion = meta.optString("objective", "").startsWith("flow")
        val head: Head = if (diffusion) {
            DiffusionHead(
                SHAPE_DIM, HIDDEN, CONDITION, Random(meta.optLong("seed", 42L)),
                window = meta.optInt("window", 0),
                fullEvery = meta.optInt("full_attention_every_nth_layer", 1),
            )
        } else {
            TokenHead(
                SHAPE_DIM, meta.optInt("token_bins", TOKEN_BINS), HIDDEN, CONDITION,
                Random(meta.optLong("seed", 42L)),
                window = meta.optInt("window", 0),
                fullEvery = meta.optInt("full_attention_every_nth_layer", 1),
            )
        }
        val read = TrainerKit.WeightFile.read(weightsFile, MAGIC, head.shape, head.parameters)
        if (!read) return null
        return Trained(
            head, mean, std,
            meta.optString("architecture", fallbackArchitecture),
            meta.optString("family", fallbackFamily),
            diffusion,
            meta.optInt("mesh_examples", 0),
            meta.optInt("image_examples", 0),
            meta.optBoolean("sliding_window", false),
            meta.optInt("window", 0),
            meta.optInt("full_attention_every_nth_layer", ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER),
            meta.optInt("steps", 0),
            meta.optDouble("last_loss", 0.0),
            meta.optDouble("validation_loss", 0.0),
            meta,
        )
    }

    // ── Reading the dataset ─────────────────────────────────────────────────────────────────────

    private fun examples(directory: File): List<Example> {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        val captions = captionIndex(directory)
        val out = ArrayList<Example>()
        for (file in files) {
            if (file.extension.lowercase(Locale.ROOT) in MESH_EXTENSIONS) {
                val mesh = runCatching { MeshIO.load(file) }.getOrNull() ?: continue
                val shape = meshFeatures(mesh)
                out += Example(shape, conditionOf(captions[file.name.lowercase(Locale.ROOT)] ?: file.nameWithoutExtension), file.name)
                if (out.size >= MAX_EXAMPLES) break
            }
        }
        if (out.isNotEmpty()) return out
        for (file in files) {
            if (file.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS) {
                val shape = imageFeatures(file) ?: continue
                out += Example(shape, conditionOf(captions[file.name.lowercase(Locale.ROOT)] ?: file.nameWithoutExtension), file.name)
                if (out.size >= MAX_EXAMPLES) break
            }
        }
        return out
    }

    /** file name -> a caption assembled from the CSV row that names it. */
    private fun captionIndex(directory: File): Map<String, String> {
        val index = HashMap<String, String>()
        directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) }
            ?.forEach { csv ->
                runCatching {
                    for (row in ModelCraft.parseCsv(csv)) {
                        val values = row.values.toList()
                        val path = values.firstOrNull { value ->
                            MESH_EXTENSIONS.any { value.lowercase(Locale.ROOT).endsWith(".$it") } ||
                                IMAGE_EXTENSIONS.any { value.lowercase(Locale.ROOT).endsWith(".$it") }
                        } ?: continue
                        val name = File(path.replace('\\', '/')).name.lowercase(Locale.ROOT)
                        val caption = values.filter { it != path && it.isNotBlank() }.joinToString(", ")
                        if (caption.isNotBlank()) index.putIfAbsent(name, caption)
                    }
                }.onFailure {
                    PrismPlatform.log.warn("ModelCraft", "Could not read caption rows from ${csv.name}: ${it.message}")
                }
            }
        return index
    }

    private fun meshFeatures(mesh: GameMesh): DoubleArray {
        val bounds = mesh.bounds()
        val largest = bounds.largest
        val vertices = mesh.vertices
        val maxY = bounds.height.coerceAtLeast(0.0001f)
        val radial = DoubleArray(4)
        val radialCount = IntArray(4)
        val vertical = DoubleArray(4)
        val verticalCount = IntArray(4)
        var radialSum = 0.0
        var radialSquares = 0.0
        var count = 0
        var index = 0
        while (index + 2 < vertices.size) {
            val x = vertices[index] - bounds.centerX
            val y = vertices[index + 1] - bounds.centerY
            val z = vertices[index + 2] - bounds.centerZ
            val radius = sqrt(x * x + z * z)
            val angle = atan2(z, x)
            val sector = (((angle + Math.PI) / (2 * Math.PI)) * 4).toInt().coerceIn(0, 3)
            radial[sector] += radius
            radialCount[sector]++
            val band = (((vertices[index + 1] - bounds.minY) / maxY) * 4).toInt().coerceIn(0, 3)
            vertical[band] += radius
            verticalCount[band]++
            radialSum += radius
            radialSquares += radius * radius
            count++
            index += 3
        }
        val maxRadius = (radial.indices.maxOfOrNull { if (radialCount[it] > 0) radial[it] / radialCount[it] else 0.0 } ?: 0.0)
            .coerceAtLeast(0.0001)
        val meanRadius = (radialSum / count.coerceAtLeast(1)).coerceAtLeast(0.0001)
        val roughness = sqrt(
            ((radialSquares / count.coerceAtLeast(1)) - meanRadius * meanRadius).coerceAtLeast(0.0),
        ) / meanRadius
        val maxVertical = (vertical.indices.maxOfOrNull { if (verticalCount[it] > 0) vertical[it] / verticalCount[it] else 0.0 } ?: 0.0)
            .coerceAtLeast(0.0001)
        fun ratio(value: Float): Double = ((value / largest).coerceIn(0f, 1f) * 2.0) - 1.0
        fun normalised(value: Double, top: Double): Double = ((value / top).coerceIn(0.0, 1.0) * 2.0) - 1.0
        return DoubleArray(SHAPE_DIM) { position ->
            when (position) {
                0 -> ratio(bounds.width)
                1 -> ratio(bounds.height)
                2 -> ratio(bounds.depth)
                3 -> (((Math.log10(mesh.vertexCount + 1.0) - 2.0) / 2.0).coerceIn(-1.0, 1.0))
                4 -> (((Math.log10(mesh.triangleCount + 1.0) - 2.0) / 2.0).coerceIn(-1.0, 1.0))
                5 -> (((mesh.vertexCount.toDouble() / (mesh.triangleCount.coerceAtLeast(1).toDouble() * 3.0)).coerceIn(0.0, 1.0) * 2.0) - 1.0)
                6 -> (((roughness).coerceIn(0.0, 1.0) * 2.0) - 1.0)
                7 -> (((mesh.triangleCount.toDouble() / mesh.vertexCount.coerceAtLeast(1).toDouble()).coerceIn(0.0, 2.0) / 2.0 * 2.0) - 1.0)
                in 8..11 -> {
                    val sector = position - 8
                    val average = if (radialCount[sector] > 0) radial[sector] / radialCount[sector] else 0.0
                    normalised(average, maxRadius)
                }
                in 12..14 -> {
                    val band = position - 12
                    val average = if (verticalCount[band] > 0) vertical[band] / verticalCount[band] else 0.0
                    normalised(average, maxVertical)
                }
                else -> (((roughness).coerceIn(0.0, 1.0) * 2.0) - 1.0)
            }
        }
    }

    /** A texture-only asset still describes a shape: its silhouette and its colour statistics. */
    private fun imageFeatures(file: File): DoubleArray? {
        val image = runCatching { PrismPlatform.images.decode(file, MAX_SIDE) }.getOrNull() ?: return null
        val side = min(MAX_SIDE, max(image.width, image.height)).coerceAtLeast(1)
        if (image.width <= 0 || image.height <= 0) return null
        val radial = DoubleArray(4)
        val radialCount = IntArray(4)
        val vertical = DoubleArray(4)
        val verticalCount = IntArray(4)
        var luminance = 0.0
        var luminanceSquares = 0.0
        var count = 0
        val stepX = max(1, image.width / side)
        val stepY = max(1, image.height / side)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val pixel = image.pixels[y * image.width + x]
                val luma = (PrismImage.red(pixel) * 0.299 + PrismImage.green(pixel) * 0.587 + PrismImage.blue(pixel) * 0.114) / 255.0
                val nx = (x.toDouble() / image.width) * 2.0 - 1.0
                val ny = (y.toDouble() / image.height) * 2.0 - 1.0
                val angle = atan2(ny, nx)
                val sector = (((angle + Math.PI) / (2 * Math.PI)) * 4).toInt().coerceIn(0, 3)
                radial[sector] += if (luma > 0.15) luma else 0.0
                radialCount[sector]++
                val band = ((y.toDouble() / image.height) * 4).toInt().coerceIn(0, 3)
                vertical[band] += luma
                verticalCount[band]++
                luminance += luma
                luminanceSquares += luma * luma
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return null
        val mean = luminance / count
        val roughness = sqrt((luminanceSquares / count) - mean * mean)
        fun normalised(value: Double, top: Double): Double = ((value / top).coerceIn(0.0, 1.0) * 2.0) - 1.0
        val maxRadial = (radial.indices.maxOfOrNull { if (radialCount[it] > 0) radial[it] / radialCount[it] else 0.0 } ?: 1.0).coerceAtLeast(0.0001)
        val maxVertical = (vertical.indices.maxOfOrNull { if (verticalCount[it] > 0) vertical[it] / verticalCount[it] else 0.0 } ?: 1.0).coerceAtLeast(0.0001)
        val aspect = image.width.toDouble() / image.height.coerceAtLeast(1)
        return DoubleArray(SHAPE_DIM) { position ->
            when (position) {
                0 -> ((aspect.coerceIn(0.25, 4.0) / 4.0) * 2.0 - 1.0)
                1 -> 0.15
                2 -> 0.0
                3 -> -0.7
                4 -> -0.7
                5 -> 0.2
                6 -> roughness * 2.0 - 1.0
                7 -> 0.0
                in 8..11 -> normalised(if (radialCount[position - 8] > 0) radial[position - 8] / radialCount[position - 8] else 0.0, maxRadial)
                in 12..14 -> normalised(if (verticalCount[position - 12] > 0) vertical[position - 12] / verticalCount[position - 12] else 0.0, maxVertical)
                else -> roughness * 2.0 - 1.0
            }
        }
    }

    /** Eight numbers in [-1, 1] from a caption or prompt, stable across runs and platforms. */
    private fun conditionOf(text: String): DoubleArray {
        val vector = DoubleArray(CONDITION)
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return vector
        for (word in words) {
            var hash = 0x811C9DC5.toInt()
            for (character in word) {
                hash = hash xor character.code
                hash *= 0x01000193
            }
            val bucket = (hash and Int.MAX_VALUE) % CONDITION
            vector[bucket] += if (hash and 0x10000 != 0) 1.0 else -1.0
        }
        val length = sqrt(vector.sumOf { it * it }).coerceAtLeast(1.0)
        for (index in vector.indices) vector[index] = (vector[index] / length).coerceIn(-1.0, 1.0)
        return vector
    }

    private fun baseWeights(model: CraftedModel, directory: File): File? {
        val raw = model.param("base_model").trim().trim('"', '\'')
        if (raw.isBlank()) return null
        val file = File(raw)
        val direct = if (file.isDirectory) File(file, WEIGHTS_FILE) else file
        if (direct.isFile) return direct
        val beside = File(directory, DatasetReferences.fileName(raw))
        if (beside.isFile) return beside
        val inDataset = File(directory, WEIGHTS_FILE)
        return if (raw == WEIGHTS_FILE && inDataset.isFile) inDataset else null
    }

    private fun doubles(array: JSONArray?): DoubleArray? {
        if (array == null) return null
        return DoubleArray(array.length()) { index -> array.optDouble(index) }
    }

    private data class Config(
        val epochs: Int,
        val stepsPerEpoch: Int,
        val learningRate: Double,
        val seed: Long,
        val diffusion: Boolean,
        val slidingWindow: Boolean,
        val window: Int,
        val fullAttentionEveryNthLayer: Int,
    )

    private fun readConfig(recipe: File): Config {
        val root = runCatching { JSONObject(recipe.readText()) }.getOrElse { JSONObject() }
        val schedule = root.optJSONObject("schedule")
        val attention = root.optJSONObject("attention")
        val architecture = root.optString("architecture", ModelCraft.architectures.first())
        val epochs = (root.optInt(ModelCraft.KEY_EPOCHS, schedule?.optInt("epochs", 8) ?: 8))
            .coerceIn(1, ModelCraft.MAX_EPOCHS)
        val steps = (root.optInt(ModelCraft.KEY_STEPS_PER_EPOCH, schedule?.optInt("steps_per_epoch", 0) ?: 0))
            .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH)
        val rate = root.optString("learning_rate", "0.001").toDoubleOrNull()?.coerceIn(1e-5, 1e-2) ?: 0.001
        val seed = root.optString("seed", root.optInt("seed", 42).toString()).toLongOrNull() ?: 42L
        return Config(
            epochs = epochs,
            stepsPerEpoch = steps,
            learningRate = rate,
            seed = seed,
            diffusion = architecture.contains("Diffuser", true) ||
                root.optString("family", "").contains("diffusion", true) ||
                root.optString("family", "").contains("flow", true) ||
                root.optString("family", "").contains("rectified", true) ||
                root.optString("family", "").contains("DDPM", true),
            slidingWindow = attention?.optBoolean("sliding_window", false) == true,
            window = attention?.optInt("window", ModelCraft.DEFAULT_WINDOW) ?: ModelCraft.DEFAULT_WINDOW,
            fullAttentionEveryNthLayer = attention?.optInt(
                "full_attention_every_nth_layer", ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER,
            ) ?: ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER,
        )
    }

    // ── The two heads ───────────────────────────────────────────────────────────────────────────

    internal interface Head {
        val parameters: List<TrainerKit.Param>
        val shape: IntArray
        /** The last step's token accuracy, or NaN for a head without labels. */
        val lastAccuracy: Double get() = Double.NaN
        fun zero()
        fun step(target: DoubleArray, condition: DoubleArray, random: Random): Double
        fun loss(target: DoubleArray, condition: DoubleArray, random: Random): Double
        fun sample(random: Random, condition: DoubleArray): DoubleArray
        fun parameterCount(): Int
    }

    /**
     * A flow-matching denoiser: `(x_t, t, condition) -> velocity`.
     *
     * Two tanh layers are enough for a sixteen-dimensional shape space; the training step is the
     * whole flow-matching objective, and sampling integrates the field from t = 1 (noise) to t = 0
     * (a shape).
     */
    private class DiffusionHead(
        private val dim: Int,
        private val hidden: Int,
        private val conditionDim: Int,
        random: Random,
        window: Int = 0,
        fullEvery: Int = 0,
    ) : Head {
        // Each shape coordinate is a token: [value, t, condition..., position]. A window then means
        // "this coordinate may look at these neighbours", and the periodic full layer sees all sixteen.
        private val coordInput = 2 + conditionDim + POSITION_DIM
        private val attention = if (window > 0) {
            TokenAttention(coordInput, window, fullEvery, random)
        } else {
            null
        }
        private val w1 = TrainerKit.gaussian("game_w1", hidden, dim + 1 + conditionDim, random)
        private val b1 = TrainerKit.gaussian("game_b1", hidden, 1, random)
        private val w2 = TrainerKit.gaussian("game_w2", hidden, hidden, random)
        private val b2 = TrainerKit.gaussian("game_b2", hidden, 1, random)
        private val w3 = TrainerKit.gaussian("game_w3", dim, hidden, random)
        private val b3 = TrainerKit.gaussian("game_b3", dim, 1, random)

        override val parameters = buildList {
            add(w1); add(b1); add(w2); add(b2); add(w3); add(b3)
            attention?.parameters()?.let { addAll(it) }
        }
        override val shape = intArrayOf(0, dim, hidden, conditionDim) +
            (attention?.shape ?: intArrayOf())

        override fun zero() {
            for (param in parameters) param.grad.fill(0.0)
        }

        override fun parameterCount(): Int = parameters.sumOf { it.data.size }

        override fun sample(random: Random, condition: DoubleArray): DoubleArray {
            val x = DoubleArray(dim) { random.nextGaussian().coerceIn(-3.0, 3.0) }
            val steps = 12
            for (step in 0 until steps) {
                val t = 1.0 - step.toDouble() / steps
                val contexts = attention?.Forward(coordInputs(x, t, condition))?.contexts
                val velocity = forward(x, t, condition, contexts)
                val dt = 1.0 / steps
                for (index in 0 until dim) x[index] = (x[index] - velocity[index] * dt).coerceIn(-4.0, 4.0)
            }
            return x
        }

        override fun loss(target: DoubleArray, condition: DoubleArray, random: Random): Double {
            val t = random.nextDouble()
            val noisy = DoubleArray(dim) { index ->
                (1.0 - t) * target[index] + t * random.nextGaussian()
            }
            val wanted = DoubleArray(dim) { index -> noisy[index] - target[index] }
            val contexts = attention?.Forward(coordInputs(noisy, t, condition))?.contexts
            val predicted = forward(noisy, t, condition, contexts)
            var sum = 0.0
            for (index in 0 until dim) {
                val delta = predicted[index] - wanted[index]
                sum += delta * delta
            }
            return sum / dim
        }

        override fun step(target: DoubleArray, condition: DoubleArray, random: Random): Double {
            val t = random.nextDouble()
            val noise = DoubleArray(dim) { random.nextGaussian() }
            val noisy = DoubleArray(dim) { index -> (1.0 - t) * target[index] + t * noise[index] }
            val cache = attention?.Forward(coordInputs(noisy, t, condition))
            val input = denoiseInput(noisy, t, condition, cache?.contexts)
            val h1 = tanh(TrainerKit.linear(input, w1, b1))
            val h2 = tanh(TrainerKit.linear(h1, w2, b2))
            val predicted = TrainerKit.linear(h2, w3, b3)
            val dOut = DoubleArray(dim) { index ->
                2.0 * (predicted[index] - (noise[index] - target[index])) / dim
            }
            TrainerKit.accumulateLinear(h2, dOut, w3, b3)
            val dh2 = DoubleArray(hidden)
            for (row in 0 until hidden) {
                var sum = 0.0
                for (column in 0 until dim) sum += w3.data[column * hidden + row] * dOut[column]
                dh2[row] = sum * (1.0 - h2[row] * h2[row])
            }
            TrainerKit.accumulateLinear(h1, dh2, w2, b2)
            val dh1 = DoubleArray(hidden)
            for (row in 0 until hidden) {
                var sum = 0.0
                for (column in 0 until hidden) sum += w2.data[column * hidden + row] * dh2[column]
                dh1[row] = sum * (1.0 - h1[row] * h1[row])
            }
            TrainerKit.accumulateLinear(input, dh1, w1, b1)
            if (attention != null && cache != null) {
                // Only the coordinate value is fed back into the denoiser; the other context
                // components are conditioning the attention already used.
                val dInputs = Array(dim) { position ->
                    DoubleArray(coordInput).apply {
                        var sum = 0.0
                        for (row in 0 until hidden) sum += w1.at(row, position) * dh1[row]
                        this[0] = sum
                    }
                }
                attention.backward(cache, dInputs)
            }
            return loss(target, condition, random)
        }

        private fun coordInputs(x: DoubleArray, t: Double, condition: DoubleArray): Array<DoubleArray> =
            Array(dim) { index ->
                DoubleArray(coordInput).apply {
                    this[0] = x[index]
                    this[1] = t
                    for (dim in 0 until conditionDim) this[2 + dim] = condition.getOrElse(dim) { 0.0 }
                    for (dim in 0 until POSITION_DIM) {
                        val frequency = 1.0 / Math.pow(10_000.0, dim.toDouble() / POSITION_DIM)
                        this[2 + conditionDim + dim] = if (dim % 2 == 0) sin(index * frequency) else cos(index * frequency)
                    }
                }
            }

        private fun denoiseInput(
            x: DoubleArray,
            t: Double,
            condition: DoubleArray,
            contexts: Array<DoubleArray>?,
        ): DoubleArray {
            val vector = DoubleArray(dim + 1 + conditionDim)
            for (index in 0 until dim) vector[index] = x[index] + (contexts?.get(index)?.get(0) ?: 0.0)
            vector[dim] = t
            for (index in 0 until conditionDim) vector[dim + 1 + index] = condition.getOrElse(index) { 0.0 }
            return vector
        }

        private fun forward(
            x: DoubleArray,
            t: Double,
            condition: DoubleArray,
            contexts: Array<DoubleArray>?,
        ): DoubleArray {
            val vector = denoiseInput(x, t, condition, contexts)
            val h1 = tanh(TrainerKit.linear(vector, w1, b1))
            val h2 = tanh(TrainerKit.linear(h1, w2, b2))
            return TrainerKit.linear(h2, w3, b3)
        }

        private fun tanh(values: DoubleArray): DoubleArray =
            DoubleArray(values.size) { index -> Math.tanh(values[index]) }
    }

    /**
     * An autoregressive shape-token model.
     *
     * The sequence is one token per shape dimension; each token is predicted from the two before it,
     * a normalised history of everything before it, its position, and the caption conditioning. It
     * is the sequence-modelling core the transformer-shaped families are built around, applied to a
     * sequence a phone can train.
     */
    private class TokenHead(
        private val dim: Int,
        private val bins: Int,
        private val hidden: Int,
        private val conditionDim: Int,
        random: Random,
        window: Int = 0,
        fullEvery: Int = 0,
    ) : Head {
        private val inputSize = 3 * (bins + 1) + 1 + conditionDim
        // Built before the MLP so the same seed gives the same weights whether the window is on.
        private val attention = if (window > 0) {
            TokenAttention(inputSize, window, fullEvery, random)
        } else {
            null
        }
        private val w1 = TrainerKit.gaussian("game_t_w1", hidden, inputSize, random)
        private val b1 = TrainerKit.gaussian("game_t_b1", hidden, 1, random)
        private val w2 = TrainerKit.gaussian("game_t_w2", hidden, hidden, random)
        private val b2 = TrainerKit.gaussian("game_t_b2", hidden, 1, random)
        private val w3 = TrainerKit.gaussian("game_t_w3", bins, hidden, random)
        private val b3 = TrainerKit.gaussian("game_t_b3", bins, 1, random)

        override var lastAccuracy = Double.NaN
            private set

        override val parameters = buildList {
            add(w1); add(b1); add(w2); add(b2); add(w3); add(b3)
            attention?.parameters()?.let { addAll(it) }
        }
        override val shape = intArrayOf(1, dim, bins, hidden, conditionDim) +
            (attention?.shape ?: intArrayOf())

        override fun zero() {
            for (param in parameters) param.grad.fill(0.0)
        }

        override fun parameterCount(): Int = parameters.sumOf { it.data.size }

        private fun quantise(target: DoubleArray): IntArray = IntArray(dim) { index ->
            (((target.getOrElse(index) { 0.0 }.coerceIn(-1.0, 1.0) + 1.0) / 2.0) * (bins - 1)).toInt()
                .coerceIn(0, bins - 1)
        }

        private fun inputFor(tokens: IntArray, position: Int, condition: DoubleArray): DoubleArray {
            val input = DoubleArray(inputSize)
            val start = bins
            fun oneHot(token: Int, offset: Int) {
                input[offset + token.coerceIn(0, bins)] += 1.0
            }
            oneHot(if (position >= 1) tokens[position - 1] else start, 0)
            oneHot(if (position >= 2) tokens[position - 2] else start, bins + 1)
            if (position > 0) {
                for (previous in 0 until position) {
                    input[2 * (bins + 1) + tokens[previous].coerceIn(0, bins)] += 1.0 / position
                }
            } else {
                input[2 * (bins + 1) + start] = 1.0
            }
            input[3 * (bins + 1)] = position.toDouble() / dim
            for (index in 0 until conditionDim) {
                input[3 * (bins + 1) + 1 + index] = condition.getOrElse(index) { 0.0 }
            }
            return input
        }

        private fun rawInputs(tokens: IntArray, condition: DoubleArray): Array<DoubleArray> =
            Array(dim) { position -> inputFor(tokens, position, condition) }

        private fun combine(raw: DoubleArray, context: DoubleArray?): DoubleArray {
            if (context == null) return raw
            return DoubleArray(raw.size) { index -> raw[index] + context[index] }
        }

        private fun forward(input: DoubleArray): DoubleArray {
            val h1 = tanh(TrainerKit.linear(input, w1, b1))
            val h2 = tanh(TrainerKit.linear(h1, w2, b2))
            return softmax(TrainerKit.linear(h2, w3, b3))
        }

        private fun tanh(values: DoubleArray): DoubleArray =
            DoubleArray(values.size) { index -> Math.tanh(values[index]) }

        private fun softmax(logits: DoubleArray): DoubleArray {
            val max = logits.max()
            var sum = 0.0
            val out = DoubleArray(logits.size)
            for (index in logits.indices) {
                out[index] = Math.exp((logits[index] - max).coerceIn(-40.0, 0.0))
                sum += out[index]
            }
            if (sum <= 0.0) return DoubleArray(logits.size) { 1.0 / logits.size }
            for (index in out.indices) out[index] /= sum
            return out
        }

        override fun loss(target: DoubleArray, condition: DoubleArray, random: Random): Double {
            val tokens = quantise(target)
            val raws = rawInputs(tokens, condition)
            val contexts = attention?.Forward(raws)?.contexts
            var sum = 0.0
            for (position in 0 until dim) {
                val probability = forward(combine(raws[position], contexts?.get(position)))[tokens[position]]
                sum += -ln(probability.coerceAtLeast(1e-9))
            }
            return sum / dim
        }

        override fun step(target: DoubleArray, condition: DoubleArray, random: Random): Double {
            val tokens = quantise(target)
            val raws = rawInputs(tokens, condition)
            val cache = attention?.Forward(raws)
            val dInputs = Array(dim) { DoubleArray(inputSize) }
            var correct = 0
            for (position in 0 until dim) {
                val input = combine(raws[position], cache?.contexts?.get(position))
                val h1 = tanh(TrainerKit.linear(input, w1, b1))
                val h2 = tanh(TrainerKit.linear(h1, w2, b2))
                val logits = TrainerKit.linear(h2, w3, b3)
                val probability = softmax(logits)
                var picked = 0
                for (index in 1 until bins) if (probability[index] > probability[picked]) picked = index
                if (picked == tokens[position]) correct++
                val dLogits = DoubleArray(bins) { index -> probability[index] }
                dLogits[tokens[position]] -= 1.0
                for (index in dLogits.indices) dLogits[index] /= dim
                TrainerKit.accumulateLinear(h2, dLogits, w3, b3)
                val dh2 = DoubleArray(hidden)
                for (row in 0 until hidden) {
                    var value = 0.0
                    for (column in 0 until bins) value += w3.data[column * hidden + row] * dLogits[column]
                    dh2[row] = value * (1.0 - h2[row] * h2[row])
                }
                TrainerKit.accumulateLinear(h1, dh2, w2, b2)
                val dh1 = DoubleArray(hidden)
                for (row in 0 until hidden) {
                    var value = 0.0
                    for (column in 0 until hidden) value += w2.data[column * hidden + row] * dh2[column]
                    dh1[row] = value * (1.0 - h1[row] * h1[row])
                }
                TrainerKit.accumulateLinear(input, dh1, w1, b1)
                dInputs[position] = dh1
            }
            if (attention != null && cache != null) attention.backward(cache, dInputs)
            lastAccuracy = correct.toDouble() / dim.coerceAtLeast(1)
            return loss(target, condition, random)
        }

        override fun sample(random: Random, condition: DoubleArray): DoubleArray {
            val tokens = IntArray(dim) { bins }
            for (position in 0 until dim) {
                // The attention is causal, so the prefix built so far is all it needs; recomputing
                // it for each position is what makes sampling use the same masks as training.
                val prefix = Array(position + 1) { index -> inputFor(tokens, index, condition) }
                val context = attention?.Forward(prefix)?.contexts?.get(position)
                val probability = forward(combine(prefix[position], context))
                var choice = random.nextDouble()
                var picked = bins - 1
                for (index in probability.indices) {
                    choice -= probability[index]
                    if (choice <= 0.0) {
                        picked = index
                        break
                    }
                }
                tokens[position] = picked
            }
            return DoubleArray(dim) { index ->
                ((tokens[index].toDouble() / (bins - 1).coerceAtLeast(1)) * 2.0 - 1.0).coerceIn(-1.0, 1.0)
            }
        }
    }
}
