package com.prism.launcher.protein

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * What one training step did, so the UI has something honest to show.
 */
data class TrainingStep(
    val step: Int,
    val objective: String,
    val loss: Float,
    val maskedLmLoss: Float = 0f,
    val distogramLoss: Float = 0f,
    val fapeLoss: Float = 0f,
    val plddtLoss: Float = 0f,
    val gradientNorm: Float = 0f,
    val learningRate: Float = 0f,
    val chainId: String = "",
    val residues: Int = 0,
    val elapsedMillis: Long = 0,
) {
    /** True when the numbers have gone somewhere training cannot come back from. */
    val diverged: Boolean get() = loss.isNaN() || loss.isInfinite() || loss > 1e6f
}

/** How a run is configured. Every one of these is something the user can reasonably want to change. */
data class TrainingPlan(
    val steps: Int = 200,
    val learningRate: Float = 3e-4f,
    /** Linear warmup, as a fraction of [steps]. Transformers need one; this is not optional. */
    val warmupFraction: Float = 0.06f,
    val weightDecay: Float = 1e-2f,
    val gradientClip: Float = 1f,
    /** Chains per step. Kept at one by default: a phone has no memory for a real batch. */
    val batchSize: Int = 1,
    /** Fraction of residues hidden during masked language modelling. BERT's 15%, and ESM's. */
    val maskRate: Float = 0.15f,
    /** Relative weights on the structural losses. AlphaFold's ratios, roughly. */
    val distogramWeight: Float = 0.3f,
    val fapeWeight: Float = 1.0f,
    val plddtWeight: Float = 0.01f,
    val checkpointEvery: Int = 50,
)

/**
 * Trains a [FoldingModel] on whatever the user has.
 *
 * ## Two objectives, two kinds of data
 *
 * **Sequence databases** train the trunk by masked language modelling: hide 15% of the residues,
 * predict them back. No structures needed, and it is the only objective that can use the hundreds
 * of millions of sequences in UniRef when the PDB has a few hundred thousand structures. What it
 * teaches is co-variation — which pairs of positions mutate together across a protein family — and
 * co-variation is a contact signal, which is why a language model trained on sequence alone can
 * produce a usable contact map (Rao et al. 2021; Lin et al., ESM-2, 2023).
 *
 * **Resolved structures** train the rest: a distogram head against binned Cβ distances, the
 * structure module against FAPE, and the confidence head against the lDDT the prediction actually
 * achieved. This is the part that turns a contact map into coordinates.
 *
 * Both can run in the same session, interleaved, and normally should: the trunk is shared, and a
 * trunk that has seen a million sequences folds better than one that has seen only the structures.
 *
 * ## Why the losses are weighted the way they are
 *
 * FAPE at 1.0 because it is the objective that actually places atoms. The distogram at 0.3 because
 * it is a useful auxiliary that converges far faster than FAPE does and would otherwise dominate
 * the early gradient. pLDDT at 0.01 because it is predicting a *property of the prediction*: it
 * must not be allowed to improve its own score by making the structure worse in a way it can
 * anticipate, and keeping its weight near zero is the cheap way to ensure that.
 */
class FoldingTrainer(
    private val model: FoldingModel,
    private val plan: TrainingPlan = TrainingPlan(),
    seed: Long = 1234,
) {
    private val rng = Random(seed)
    private val optimiser = AdamW(
        model.parameters(),
        learningRate = plan.learningRate,
        weightDecay = plan.weightDecay,
    )

    var step: Int = 0
        private set

    /** A rolling mean, because a single step's loss on one chain is mostly noise. */
    private val recentLosses = ArrayDeque<Float>()

    fun averageLoss(): Float =
        if (recentLosses.isEmpty()) 0f else recentLosses.sum() / recentLosses.size

    private fun record(loss: Float) {
        recentLosses.addLast(loss)
        while (recentLosses.size > 50) recentLosses.removeFirst()
    }

    /** Linear warmup then cosine decay — the schedule every transformer paper ends up at. */
    private fun learningRateFor(current: Int): Float {
        val warmup = (plan.steps * plan.warmupFraction).toInt().coerceAtLeast(1)
        return if (current < warmup) {
            plan.learningRate * (current + 1) / warmup
        } else {
            val progress = (current - warmup).toFloat() / (plan.steps - warmup).coerceAtLeast(1)
            val cosine = 0.5f * (1f + kotlin.math.cos(Math.PI.toFloat() * progress.coerceIn(0f, 1f)))
            plan.learningRate * (0.1f + 0.9f * cosine)
        }
    }

    // ── Masked language modelling ──────────────────────────────────────────

    /**
     * One step of masked language modelling on a sequence.
     *
     * BERT's 80/10/10 corruption: of the positions chosen for masking, most are replaced by the
     * mask token, some by a random residue, and some left alone. The 10% left alone is what forces
     * the model to build a representation of *every* position rather than only of the ones that
     * are obviously missing, and the 10% randomised is what stops it assuming an unmasked residue
     * is necessarily correct.
     */
    fun trainOnSequence(chain: ProteinChain): TrainingStep {
        val started = System.currentTimeMillis()
        val tokens = ProteinChemistry.encode(chain.sequence)
        val length = min(tokens.size, model.config.maxLength)
        if (length < 8) {
            return TrainingStep(step, "sequence", 0f, chainId = chain.id, residues = length)
        }

        val start = if (tokens.size > length) rng.nextInt(tokens.size - length + 1) else 0
        val window = IntArray(length) { tokens[start + it] }
        val inputs = window.copyOf()
        val targets = IntArray(length) { -1 }

        var masked = 0
        for (i in 0 until length) {
            if (rng.nextFloat() >= plan.maskRate) continue
            targets[i] = window[i]
            masked++
            val roll = rng.nextFloat()
            inputs[i] = when {
                roll < 0.8f -> ProteinChemistry.MASK_INDEX
                roll < 0.9f -> rng.nextInt(ProteinChemistry.ALPHABET.length)
                else -> window[i]
            }
        }
        // A window with nothing masked teaches nothing; mask one position rather than waste the step.
        if (masked == 0) {
            val at = rng.nextInt(length)
            targets[at] = window[at]
            inputs[at] = ProteinChemistry.MASK_INDEX
        }

        optimiser.zeroGrad()
        val trunk = model.trunk(inputs)
        val loss = Ops.crossEntropyRows(model.maskedLmLogits(trunk), targets)
        loss.backward()

        val norm = optimiser.gradNorm()
        val lr = learningRateFor(step)
        optimiser.setLearningRate(lr)
        optimiser.step(plan.gradientClip)
        step++

        val value = loss.data[0]
        record(value)
        return TrainingStep(
            step = step,
            objective = "sequence",
            loss = value,
            maskedLmLoss = value,
            gradientNorm = norm,
            learningRate = lr,
            chainId = chain.id,
            residues = length,
            elapsedMillis = System.currentTimeMillis() - started,
        )
    }

    // ── Structure training ─────────────────────────────────────────────────

    /**
     * One step on a resolved structure: distogram, FAPE and confidence together.
     *
     * Cropped to [FoldingConfig.maxLength] at a random offset, which is how AlphaFold trains too.
     * A crop is a real protein fragment with real geometry; what it loses is long-range contacts
     * that span more than the crop, and that is the price of the pair stack being cubic in length.
     */
    fun trainOnStructure(chain: ProteinChain): TrainingStep {
        val started = System.currentTimeMillis()
        val backbone = chain.backbone
            ?: return TrainingStep(step, "structure", 0f, chainId = chain.id)

        val tokens = ProteinChemistry.encode(chain.sequence)
        val full = min(tokens.size, backbone.size)
        val length = min(full, model.config.maxLength)
        if (length < 16) {
            return TrainingStep(step, "structure", 0f, chainId = chain.id, residues = length)
        }

        // Prefer a crop that actually has coordinates in it: a window over a disordered tail
        // contributes nothing but wasted minutes.
        val start = bestCropStart(backbone, full, length)
        val window = IntArray(length) { tokens[start + it] }

        val trueFrames = ArrayList<RigidFrame?>(length)
        val trueCa = ArrayList<FloatArray?>(length)
        val trueCb = ArrayList<FloatArray?>(length)
        for (i in 0 until length) {
            val at = start + i
            trueFrames.add(chain.frameOf(at))
            trueCa.add(chain.caOf(at))
            trueCb.add(chain.cbOf(at))
        }

        optimiser.zeroGrad()
        val trunk = model.trunk(window)

        // Distogram: binned Cβ–Cβ distances, unresolved pairs excluded with a target of -1.
        val distogramTargets = IntArray(length * length) { -1 }
        for (i in 0 until length) {
            val a = trueCb[i] ?: continue
            for (j in 0 until length) {
                val b = trueCb[j] ?: continue
                distogramTargets[i * length + j] = ProteinChemistry.distogramBin(RigidFrame.distance(a, b))
            }
        }
        val distogram = model.distogramLogits(trunk)
        val distogramLoss = Ops.crossEntropyRows(distogram, distogramTargets)

        // FAPE on the structure module's frames.
        val frames = model.structureModule.forward(trunk.single, trunk.pair, length)
        val fape = FrameOps.fapeLoss(frames.rotation, frames.translation, trueFrames, trueCa)

        // Confidence: the lDDT this prediction actually achieved, as a distribution target.
        val predictedCa = (0 until length).map {
            floatArrayOf(
                frames.translation.data[it * 3],
                frames.translation.data[it * 3 + 1],
                frames.translation.data[it * 3 + 2],
            ) as FloatArray?
        }
        val achieved = StructureMetrics.perResidueLddt(predictedCa, trueCa)
        val plddtTargets = IntArray(length) { i ->
            if (trueCa[i] == null) -1
            else (achieved[i] * FoldingConfig.PLDDT_BINS).toInt().coerceIn(0, FoldingConfig.PLDDT_BINS - 1)
        }
        val plddtLoss = Ops.crossEntropyRows(model.plddtLogits(trunk), plddtTargets)

        val total = Ops.add(
            Ops.add(
                Ops.scale(distogramLoss, plan.distogramWeight),
                Ops.scale(fape, plan.fapeWeight),
            ),
            Ops.scale(plddtLoss, plan.plddtWeight),
        )
        total.backward()

        val norm = optimiser.gradNorm()
        val lr = learningRateFor(step)
        optimiser.setLearningRate(lr)
        optimiser.step(plan.gradientClip)
        step++

        val value = total.data[0]
        record(value)
        return TrainingStep(
            step = step,
            objective = "structure",
            loss = value,
            distogramLoss = distogramLoss.data[0],
            fapeLoss = fape.data[0],
            plddtLoss = plddtLoss.data[0],
            gradientNorm = norm,
            learningRate = lr,
            chainId = chain.id,
            residues = length,
            elapsedMillis = System.currentTimeMillis() - started,
        )
    }

    /** The window with the most resolved residues in it. Ties go to the earliest. */
    private fun bestCropStart(backbone: Array<FloatArray?>, full: Int, length: Int): Int {
        if (full <= length) return 0
        var best = 0
        var bestCount = -1
        var at = 0
        while (at + length <= full) {
            var count = 0
            for (i in at until at + length) if (backbone.getOrNull(i) != null) count++
            if (count > bestCount) {
                bestCount = count
                best = at
            }
            at += (length / 2).coerceAtLeast(1)
        }
        return best
    }

    /**
     * Evaluates without training: the numbers that say whether the model is any good.
     *
     * Reported on held-out chains, and all three of RMSD, TM-score and lDDT rather than one,
     * because they fail differently — see [StructureMetrics].
     */
    data class Evaluation(
        val chains: Int,
        val meanRmsd: Float,
        val meanTmScore: Float,
        val meanLddt: Float,
        val meanConfidence: Float,
    )

    fun evaluate(chains: List<ProteinChain>): Evaluation {
        var rmsd = 0f
        var tm = 0f
        var lddt = 0f
        var confidence = 0f
        var counted = 0

        chains.forEach { chain ->
            if (!chain.hasStructure) return@forEach
            val prediction = model.fold(chain.sequence)
            val length = min(prediction.length, chain.length)

            val predicted = ArrayList<FloatArray>()
            val native = ArrayList<FloatArray>()
            val predictedOpt = ArrayList<FloatArray?>()
            val nativeOpt = ArrayList<FloatArray?>()
            for (i in 0 until length) {
                val n = chain.caOf(i)
                val p = floatArrayOf(
                    prediction.backbone[i][3], prediction.backbone[i][4], prediction.backbone[i][5],
                )
                predictedOpt.add(if (n == null) null else p)
                nativeOpt.add(n)
                if (n != null) {
                    predicted.add(p)
                    native.add(n)
                }
            }
            if (predicted.size < 4) return@forEach

            rmsd += StructureMetrics.kabschRmsd(predicted, native)
            tm += StructureMetrics.tmScore(predicted, native)
            lddt += StructureMetrics.lddt(predictedOpt, nativeOpt)
            confidence += prediction.confidence
            counted++
        }

        val inv = 1f / counted.coerceAtLeast(1)
        return Evaluation(counted, rmsd * inv, tm * inv, lddt * inv, confidence * inv)
    }
}

/**
 * Reading and writing a model's weights.
 *
 * ## The format
 *
 * A magic number, a version, the [FoldingConfig] that produced it, then every parameter tensor in
 * [FoldingModel.parameters] order as raw little-endian floats. No names, because the order is
 * determined by the config and a file whose config does not match is not loadable anyway — so a
 * name would be a checksum with extra steps.
 *
 * The config is stored *in* the file rather than alongside it for the reason every model format
 * eventually learns: weights and the shape they belong to get separated, and a tensor of the wrong
 * shape loads silently and produces garbage.
 */
object FoldingCheckpoint {

    private const val MAGIC = 0x50524F54 // "PROT"
    private const val VERSION = 1

    fun save(model: FoldingModel, file: File, step: Int = 0) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out -> writeTo(out, model, step) }
    }

    /**
     * The format itself, separated from the file.
     *
     * Split out so a model sent to a peer over the mesh is written by exactly this code and read by
     * exactly [readFrom] — see [FoldingWire]. A second serialiser for the network would be a second
     * place for the tensor order to drift, and a drifted order does not fail: it loads every weight
     * into the wrong tensor and folds confident nonsense.
     */
    fun writeTo(out: DataOutputStream, model: FoldingModel, step: Int = 0) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(step)
        with(model.config) {
            out.writeInt(dModel); out.writeInt(dPair)
            out.writeInt(sequenceBlocks); out.writeInt(pairBlocks)
            out.writeInt(structureIterations); out.writeInt(attentionHeads)
            out.writeInt(ffnMultiplier); out.writeInt(maxLength)
            out.writeInt(recycles); out.writeInt(queryPoints); out.writeInt(valuePoints)
        }
        val params = model.parameters()
        out.writeInt(params.size)
        params.forEach { tensor ->
            out.writeInt(tensor.rows)
            out.writeInt(tensor.cols)
            tensor.data.forEach { out.writeFloat(it) }
        }
    }

    data class Loaded(val model: FoldingModel, val step: Int)

    fun load(file: File): Loaded? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input -> readFrom(input) }
        }.getOrNull()
    }

    /** Loads from raw bytes — a checkpoint that arrived over the mesh rather than off disk. */
    fun loadBytes(bytes: ByteArray): Loaded? = runCatching {
        DataInputStream(bytes.inputStream()).use { input -> readFrom(input) }
    }.getOrNull()

    fun readFrom(input: DataInputStream): Loaded {
        require(input.readInt() == MAGIC) { "Not a Prism folding model." }
        val version = input.readInt()
        require(version == VERSION) { "Model was written by a different version ($version)." }
        val step = input.readInt()
        val config = FoldingConfig(
            dModel = input.readInt(), dPair = input.readInt(),
            sequenceBlocks = input.readInt(), pairBlocks = input.readInt(),
            structureIterations = input.readInt(), attentionHeads = input.readInt(),
            ffnMultiplier = input.readInt(), maxLength = input.readInt(),
            recycles = input.readInt(), queryPoints = input.readInt(),
            valuePoints = input.readInt(),
        )
        val model = FoldingModel(config)
        val params = model.parameters()
        val count = input.readInt()
        require(count == params.size) {
            "Checkpoint has $count tensors, this model wants ${params.size}."
        }
        params.forEach { tensor ->
            val rows = input.readInt()
            val cols = input.readInt()
            require(rows == tensor.rows && cols == tensor.cols) {
                "Shape mismatch: file has ${rows}x$cols, model wants ${tensor.rows}x${tensor.cols}."
            }
            for (i in 0 until tensor.size) tensor.data[i] = input.readFloat()
        }
        return Loaded(model, step)
    }

    /** Bytes a checkpoint of this config will occupy, for a UI that has to warn about storage. */
    fun sizeOf(config: FoldingConfig): Long = config.parameterCount() * 4 + 128
}
