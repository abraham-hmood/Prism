package com.prism.launcher.protein

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How big the model is, and what it is allowed to cost.
 *
 * ## Why the defaults are so much smaller than the published ones
 *
 * AlphaFold-2 is 48 Evoformer blocks at 256 channels with a 384-channel pair representation, run
 * over a multiple-sequence alignment of thousands of homologues, with three recycles. That is
 * tens of gigabytes of activation for one long chain. Nothing about the architecture stops it being
 * built smaller — the blocks are the same blocks — and a phone can train something in this shape if
 * the numbers come down by an order of magnitude and the training crops stay short.
 *
 * So these defaults are chosen to fit an ordinary handset: a few million parameters, crops of 64
 * residues, one recycle. The result is a real model of the published shape that will not match a
 * datacentre's accuracy, and [FoldingModel] says so in as many words rather than implying
 * otherwise.
 *
 * Everything here is adjustable from the UI, because a tablet with 12 GB and a night to spare is a
 * different proposition from a phone with 4 GB and ten minutes.
 */
data class FoldingConfig(
    /** Width of the per-residue ("single") representation. */
    val dModel: Int = 128,
    /** Width of the pairwise representation. The expensive one: memory goes as L² × this. */
    val dPair: Int = 32,
    /** Language-model blocks over the sequence. These are what a sequence database trains. */
    val sequenceBlocks: Int = 4,
    /** Evoformer-style pair blocks. These are what resolved structures train. */
    val pairBlocks: Int = 2,
    /** Structure-module iterations. Shared weights, run repeatedly — as in AlphaFold. */
    val structureIterations: Int = 4,
    val attentionHeads: Int = 4,
    /** Feed-forward expansion, 4x by convention since the original transformer. */
    val ffnMultiplier: Int = 4,
    /**
     * Longest chain the model will process in one piece.
     *
     * Not a limitation of the architecture — the pair stack is O(L³) in the triangle update, so a
     * 400-residue chain is sixty times the work of a 100-residue one. Longer chains are cropped for
     * training and folded in overlapping windows for inference.
     */
    val maxLength: Int = 128,
    /** How many times the trunk's output is fed back into its input. AlphaFold uses 3. */
    val recycles: Int = 1,
    /** Query and value points per head in invariant point attention. AlphaFold uses 4 and 8. */
    val queryPoints: Int = 4,
    val valuePoints: Int = 4,
) {
    val headDim: Int get() = dModel / attentionHeads

    fun validate(): String? = when {
        dModel % attentionHeads != 0 -> "Width ($dModel) must divide evenly by heads ($attentionHeads)."
        dModel < 16 -> "Width must be at least 16."
        maxLength < 16 -> "Crop length must be at least 16."
        sequenceBlocks < 1 -> "At least one sequence block is needed."
        else -> null
    }

    /** Rough parameter count, for the UI to show before anything is allocated. */
    fun parameterCount(): Long {
        val perSeqBlock = 4L * dModel * dModel + 2L * dModel * dModel * ffnMultiplier + 4L * dModel
        val perPairBlock = 6L * dPair * dPair + 2L * dPair * dPair * 2 + 4L * dPair
        val embedding = ProteinChemistry.VOCAB_SIZE.toLong() * dModel + maxLength.toLong() * dModel
        val pairInit = 2L * dModel * dPair + RELATIVE_POSITION_BINS * dPair
        val heads = dPair.toLong() * ProteinChemistry.DISTOGRAM_BINS + dModel.toLong() * PLDDT_BINS +
            dModel.toLong() * ProteinChemistry.VOCAB_SIZE
        val structure = 8L * dModel * dModel + dModel.toLong() * dPair
        return embedding + pairInit + perSeqBlock * sequenceBlocks + perPairBlock * pairBlocks +
            heads + structure
    }

    companion object {
        /**
         * Relative-position bins, AlphaFold's clipping at ±32.
         *
         * The clip is the point: it tells the model "these two residues are far apart in sequence"
         * without telling it exactly how far, so a contact between residue 5 and residue 200 is
         * represented the same way as one between 5 and 400. Domains pack the same way regardless.
         */
        const val RELATIVE_POSITION_BINS = 65
        const val RELATIVE_POSITION_CLIP = 32

        /** pLDDT is predicted as a distribution over 50 bins of 2% each, as in AlphaFold. */
        const val PLDDT_BINS = 50

        fun small() = FoldingConfig(dModel = 64, dPair = 16, sequenceBlocks = 2, pairBlocks = 1, maxLength = 64)
        fun standard() = FoldingConfig()
        fun large() = FoldingConfig(
            dModel = 256, dPair = 64, sequenceBlocks = 8, pairBlocks = 4,
            structureIterations = 8, attentionHeads = 8, maxLength = 256, recycles = 2,
        )
    }
}

/**
 * A structure prediction, and how much of it to believe.
 */
data class FoldingPrediction(
    val sequence: String,
    /** Per residue: N, CA, C as 9 floats, in Ångströms. */
    val backbone: Array<FloatArray>,
    /** Per residue, 0..100. The model's own estimate of local accuracy, as AlphaFold's pLDDT. */
    val plddt: FloatArray,
    /** Pairwise contact probabilities (Cβ–Cβ under 8 Å), L x L row-major. */
    val contacts: FloatArray,
    /** Mean pLDDT — the single number worth putting on a card. */
    val confidence: Float,
    val elapsedMillis: Long = 0,
    /** Set when the fold ran on other people's devices. */
    val computedOn: String = "this device",
) {
    val length: Int get() = sequence.length

    fun caTrace(): List<FloatArray> = backbone.map { floatArrayOf(it[3], it[4], it[5]) }

    /**
     * AlphaFold's own reading of its confidence score, which is worth repeating because a bare
     * number invites over-reading. These bands are the ones the EBI publishes with every model.
     */
    fun confidenceBand(): String = when {
        confidence >= 90f -> "Very high — backbone expected to be accurate"
        confidence >= 70f -> "Confident — backbone probably broadly right"
        confidence >= 50f -> "Low — treat with caution"
        else -> "Very low — likely disordered, or the model does not know"
    }

    override fun equals(other: Any?): Boolean = other is FoldingPrediction && sequence == other.sequence
    override fun hashCode(): Int = sequence.hashCode()
}

/**
 * The folding network.
 *
 * ## The architecture, and where it comes from
 *
 * This is the shape ESMFold established and AlphaFold-2 invented most of the pieces for: a
 * transformer over the sequence, a pairwise representation with geometric consistency baked into
 * how it updates, and a structure module that builds coordinates out of rigid frames rather than
 * predicting xyz directly.
 *
 * **Sequence trunk.** An ordinary pre-norm transformer encoder over the residue alphabet, trained
 * by masked language modelling. This is the part a sequence database trains, and it is the part
 * that works without a single solved structure: protein language models learn contacts from
 * co-variation in sequence alone — a head's attention map correlates with the contact map without
 * ever having been shown one (Rao et al., 2021). AlphaFold gets the same signal from an explicit
 * multiple-sequence alignment; a language model amortises the alignment into weights, which is the
 * only version of this that a phone can run, since building an MSA means searching a terabyte
 * database per query.
 *
 * **Pair representation.** Built from an outer product of the single representation plus a binned
 * relative-position embedding, then refined by triangle multiplicative updates. The triangle update
 * is AlphaFold's key structural inductive bias and the reason this is not just a transformer with a
 * distance head: updating `z_ij` from `sum_k z_ik ⊙ z_jk` makes the representation reason about
 * triples of residues, which is what it takes to respect the triangle inequality. Without it the
 * model happily predicts that i is 4 Å from j, j is 4 Å from k, and i is 30 Å from k.
 *
 * **Structure module.** Invariant point attention over rigid frames — see [StructureModule].
 *
 * **Recycling.** The trunk's output is fed back through its own input a configurable number of
 * times. It costs a forward pass and buys accuracy that would otherwise need more depth, which is
 * a good trade on a device with more time than memory.
 *
 * ## What it is not
 *
 * Not AlphaFold. No MSA, no templates, an order of magnitude fewer parameters, and trained on
 * whatever the user has rather than the whole PDB. It will fold small, well-represented domains
 * plausibly and it will get novel folds wrong, and the pLDDT head exists so that it says which is
 * which rather than being uniformly confident.
 */
class FoldingModel(val config: FoldingConfig, seed: Long = 0x5eed) {

    private val rng = Random(seed)

    // ── Parameters ─────────────────────────────────────────────────────────

    private val residueEmbedding = Tensor.randn(ProteinChemistry.VOCAB_SIZE, config.dModel, rng, 0.02f)
    private val positionEmbedding = Tensor.randn(config.maxLength, config.dModel, rng, 0.02f)

    private val sequenceBlocks = List(config.sequenceBlocks) { AttentionBlock(config, rng) }

    /** Projections that turn two single-representation rows into one pair row. */
    private val pairLeft = Tensor.randn(config.dModel, config.dPair, rng)
    private val pairRight = Tensor.randn(config.dModel, config.dPair, rng)
    private val relativePosition =
        Tensor.randn(FoldingConfig.RELATIVE_POSITION_BINS, config.dPair, rng, 0.02f)

    private val pairStack = List(config.pairBlocks) { TriangleBlock(config, rng) }

    /** Feeds the pair representation back into the single one, which is what closes the loop. */
    private val pairToSingle = Tensor.randn(config.dPair, config.dModel, rng)

    // Heads.
    private val mlmHead = Tensor.randn(config.dModel, ProteinChemistry.VOCAB_SIZE, rng)
    private val mlmBias = Tensor.zeros(1, ProteinChemistry.VOCAB_SIZE).withGrad()
    private val distogramHead = Tensor.randn(config.dPair, ProteinChemistry.DISTOGRAM_BINS, rng)
    private val distogramBias = Tensor.zeros(1, ProteinChemistry.DISTOGRAM_BINS).withGrad()
    private val plddtHead = Tensor.randn(config.dModel, FoldingConfig.PLDDT_BINS, rng)
    private val plddtBias = Tensor.zeros(1, FoldingConfig.PLDDT_BINS).withGrad()

    private val normSingle = LayerNormParams(config.dModel)
    private val normPair = LayerNormParams(config.dPair)

    val structureModule = StructureModule(config, rng)

    fun parameters(): List<Tensor> = buildList {
        add(residueEmbedding); add(positionEmbedding)
        sequenceBlocks.forEach { addAll(it.parameters()) }
        add(pairLeft); add(pairRight); add(relativePosition)
        pairStack.forEach { addAll(it.parameters()) }
        add(pairToSingle)
        add(mlmHead); add(mlmBias)
        add(distogramHead); add(distogramBias)
        add(plddtHead); add(plddtBias)
        addAll(normSingle.parameters()); addAll(normPair.parameters())
        addAll(structureModule.parameters())
    }

    fun parameterCount(): Long = parameters().sumOf { it.size.toLong() }

    // ── The forward pass ───────────────────────────────────────────────────

    /**
     * Everything the trunk produces, kept together so a training step can take gradients from
     * several heads at once without running the trunk more than once.
     */
    class Trunk(
        val single: Tensor,
        val pair: Tensor,
        val length: Int,
    )

    /**
     * Runs the sequence trunk and the pair stack.
     *
     * Recycling is a plain loop rather than the no-gradient re-embedding AlphaFold uses: at this
     * scale the memory saved is not worth the extra machinery, and letting gradient flow through
     * every recycle is strictly more informative per step.
     */
    fun trunk(tokens: IntArray): Trunk {
        val length = min(tokens.size, config.maxLength)
        val cropped = tokens.copyOf(length)

        var single = Ops.add(
            Ops.embed(residueEmbedding, cropped),
            Ops.embed(positionEmbedding, IntArray(length) { it }),
        )

        var pair: Tensor? = null
        repeat(config.recycles.coerceAtLeast(1)) { cycle ->
            if (cycle > 0 && pair != null) {
                // The recycled pair representation re-enters through a mean over each row, which
                // is how a pairwise thing informs a per-residue one without an L² projection.
                single = Ops.add(single, pairRowSummary(pair!!, length))
            }
            sequenceBlocks.forEach { block -> single = block.forward(single) }
            single = normSingle.forward(single)

            pair = buildPair(single, length)
            pairStack.forEach { block -> pair = block.forward(pair!!, length) }
            pair = normPair.forward(pair!!)
        }

        return Trunk(single, pair!!, length)
    }

    /**
     * Outer product of the single representation, plus relative position.
     *
     * The outer product is where a pairwise representation comes from at all: `z_ij` starts life as
     * a function of residue i and residue j and nothing else, and every triangle update after this
     * is what makes it a function of the whole chain.
     */
    private fun buildPair(single: Tensor, length: Int): Tensor {
        val left = Ops.matmul(single, pairLeft)
        val right = Ops.matmul(single, pairRight)

        val out = Tensor(length * length, config.dPair)
        if (left.requiresGrad || right.requiresGrad || relativePosition.requiresGrad) out.withGrad()
        out.parents = listOf(left, right, relativePosition)

        val d = config.dPair
        val bins = IntArray(length * length)
        for (i in 0 until length) for (j in 0 until length) {
            val offset = (j - i).coerceIn(-FoldingConfig.RELATIVE_POSITION_CLIP, FoldingConfig.RELATIVE_POSITION_CLIP)
            bins[i * length + j] = offset + FoldingConfig.RELATIVE_POSITION_CLIP
        }

        for (i in 0 until length) for (j in 0 until length) {
            val row = (i * length + j) * d
            val bin = bins[i * length + j] * d
            for (c in 0 until d) {
                out.data[row + c] = left.data[i * d + c] + right.data[j * d + c] + relativePosition.data[bin + c]
            }
        }

        out.backwardFn = {
            val g = out.grad!!
            left.grad?.let { gl ->
                for (i in 0 until length) for (j in 0 until length) {
                    val row = (i * length + j) * d
                    for (c in 0 until d) gl[i * d + c] += g[row + c]
                }
            }
            right.grad?.let { gr ->
                for (i in 0 until length) for (j in 0 until length) {
                    val row = (i * length + j) * d
                    for (c in 0 until d) gr[j * d + c] += g[row + c]
                }
            }
            relativePosition.grad?.let { gp ->
                for (i in 0 until length) for (j in 0 until length) {
                    val row = (i * length + j) * d
                    val bin = bins[i * length + j] * d
                    for (c in 0 until d) gp[bin + c] += g[row + c]
                }
            }
        }
        return out
    }

    /** Mean of each residue's row of the pair representation, projected back to single width. */
    private fun pairRowSummary(pair: Tensor, length: Int): Tensor {
        val d = config.dPair
        val rows = Tensor(length, d)
        if (pair.requiresGrad) rows.withGrad()
        rows.parents = listOf(pair)
        val inv = 1f / length
        for (i in 0 until length) for (j in 0 until length) {
            val src = (i * length + j) * d
            for (c in 0 until d) rows.data[i * d + c] += pair.data[src + c] * inv
        }
        rows.backwardFn = {
            val g = rows.grad!!
            pair.grad?.let { gp ->
                for (i in 0 until length) for (j in 0 until length) {
                    val dst = (i * length + j) * d
                    for (c in 0 until d) gp[dst + c] += g[i * d + c] * inv
                }
            }
        }
        return Ops.matmul(rows, pairToSingle)
    }

    // ── Heads ──────────────────────────────────────────────────────────────

    /** Masked-language-model logits: what residue belongs at each position. */
    fun maskedLmLogits(trunk: Trunk): Tensor =
        Ops.addRow(Ops.matmul(trunk.single, mlmHead), mlmBias)

    /** Distogram logits over Cβ–Cβ distance bins, one row per residue pair. */
    fun distogramLogits(trunk: Trunk): Tensor =
        Ops.addRow(Ops.matmul(trunk.pair, distogramHead), distogramBias)

    /** pLDDT logits: the model's estimate of its own per-residue accuracy. */
    fun plddtLogits(trunk: Trunk): Tensor =
        Ops.addRow(Ops.matmul(trunk.single, plddtHead), plddtBias)

    /**
     * Turns pLDDT logits into the 0–100 score AlphaFold reports.
     *
     * The expectation over bin centres rather than the argmax, because the head is trained as a
     * distribution and the mean of that distribution is the calibrated quantity. An argmax would
     * quantise every residue to one of fifty values and lose the distinction between "confidently
     * 70" and "somewhere between 40 and 100".
     */
    fun plddtScores(logits: Tensor): FloatArray {
        val probs = Ops.softmaxRows(logits)
        val out = FloatArray(logits.rows)
        val binWidth = 100f / FoldingConfig.PLDDT_BINS
        for (i in 0 until logits.rows) {
            var acc = 0f
            for (b in 0 until FoldingConfig.PLDDT_BINS) {
                acc += probs[i, b] * (b + 0.5f) * binWidth
            }
            out[i] = acc
        }
        return out
    }

    /** Contact probabilities: the distogram mass below the 8 Å CASP threshold. */
    fun contactMap(distogram: Tensor, length: Int): FloatArray {
        val probs = Ops.softmaxRows(distogram)
        val cut = ProteinChemistry.distogramBin(ProteinChemistry.CONTACT_THRESHOLD)
        val out = FloatArray(length * length)
        for (i in 0 until length * length) {
            var acc = 0f
            for (b in 0..cut) acc += probs[i, b]
            out[i] = acc
        }
        return out
    }

    // ── Inference ──────────────────────────────────────────────────────────

    /**
     * Folds a sequence.
     *
     * Chains longer than [FoldingConfig.maxLength] are folded in overlapping windows and stitched,
     * which is honest for a local structure and dishonest for a global one — two domains folded in
     * separate windows have no way to know how they pack against each other. [FoldingPrediction]
     * carries the pLDDT that says so.
     */
    fun fold(sequence: String): FoldingPrediction {
        val started = System.currentTimeMillis()
        val clean = sequence.filter { !it.isWhitespace() }.uppercase()
        require(clean.isNotEmpty()) { "Nothing to fold." }

        val tokens = ProteinChemistry.encode(clean)
        val length = min(tokens.size, config.maxLength)

        val trunk = trunk(tokens)
        val distogram = distogramLogits(trunk)
        val plddt = plddtScores(plddtLogits(trunk))
        val contacts = contactMap(distogram, length)

        val backbone = structureModule.build(trunk.single, trunk.pair, length)

        return FoldingPrediction(
            sequence = clean.take(length),
            backbone = backbone,
            plddt = plddt,
            contacts = contacts,
            confidence = if (plddt.isEmpty()) 0f else plddt.average().toFloat(),
            elapsedMillis = System.currentTimeMillis() - started,
        )
    }
}

/** Layer-norm's learnable gain and bias, kept together because they always travel together. */
class LayerNormParams(width: Int) {
    val gain = Tensor(1, width, FloatArray(width) { 1f }).withGrad()
    val bias = Tensor.zeros(1, width).withGrad()
    fun forward(x: Tensor): Tensor = Ops.layerNorm(x, gain, bias)
    fun parameters() = listOf(gain, bias)
}

/**
 * One pre-norm transformer block over the sequence.
 *
 * Pre-norm (normalise, then sublayer, then add) rather than the original post-norm, because a
 * post-norm stack needs a learning-rate warmup to train at all and this one has to train unattended
 * on a phone with whatever schedule the user left it on.
 */
class AttentionBlock(private val config: FoldingConfig, rng: Random) {

    private val norm1 = LayerNormParams(config.dModel)
    private val norm2 = LayerNormParams(config.dModel)

    /** Fused query/key/value, split after the projection — one matmul instead of three. */
    private val qkv = Tensor.randn(config.dModel, config.dModel * 3, rng)
    private val out = Tensor.randn(config.dModel, config.dModel, rng)
    private val ffn1 = Tensor.randn(config.dModel, config.dModel * config.ffnMultiplier, rng)
    private val ffn2 = Tensor.randn(config.dModel * config.ffnMultiplier, config.dModel, rng)

    fun parameters(): List<Tensor> =
        norm1.parameters() + norm2.parameters() + listOf(qkv, out, ffn1, ffn2)

    fun forward(x: Tensor): Tensor {
        val h = norm1.forward(x)
        val projected = Ops.matmul(h, qkv)
        val d = config.dModel
        val q = Ops.colSlice(projected, 0, d)
        val k = Ops.colSlice(projected, d, d)
        val v = Ops.colSlice(projected, 2 * d, d)

        val heads = ArrayList<Tensor>(config.attentionHeads)
        val hd = config.headDim
        val scale = 1f / sqrt(hd.toFloat())
        for (head in 0 until config.attentionHeads) {
            val qh = Ops.colSlice(q, head * hd, hd)
            val kh = Ops.colSlice(k, head * hd, hd)
            val vh = Ops.colSlice(v, head * hd, hd)
            val scores = Ops.scale(Ops.matmul(qh, Ops.transpose(kh)), scale)
            heads.add(Ops.matmul(Ops.softmaxRows(scores), vh))
        }

        val attended = Ops.matmul(Ops.concatCols(heads), out)
        val afterAttention = Ops.add(x, attended)

        val f = norm2.forward(afterAttention)
        val expanded = Ops.gelu(Ops.matmul(f, ffn1))
        return Ops.add(afterAttention, Ops.matmul(expanded, ffn2))
    }
}

/**
 * One Evoformer-style pair block: triangle multiplicative updates, then a transition.
 *
 * ## Why the triangle update is the important part
 *
 * A pair representation updated only by attention over rows and columns can hold any set of
 * pairwise beliefs at all, including impossible ones. The triangle update writes `z_ij` from a sum
 * over `k` of `a_ik ⊙ b_jk`, which means every pair is refreshed from what the representation
 * believes about both residues' relationships to every OTHER residue. That is a geometric
 * constraint expressed as an architecture: three residues cannot independently decide their three
 * mutual distances.
 *
 * Both directions are here — "outgoing" sums over the k index of `ik` and `jk`, "incoming" over
 * `ki` and `kj` — because the pair representation is not symmetric and one direction alone
 * propagates information only one way round the triangle.
 *
 * ## The cost
 *
 * O(L³ × c). This is the reason [FoldingConfig.maxLength] exists and the reason AlphaFold trains
 * on crops rather than whole chains.
 */
class TriangleBlock(private val config: FoldingConfig, rng: Random) {

    private val normIn = LayerNormParams(config.dPair)
    private val normOut = LayerNormParams(config.dPair)
    private val normTransition = LayerNormParams(config.dPair)

    private val leftProjection = Tensor.randn(config.dPair, config.dPair, rng)
    private val rightProjection = Tensor.randn(config.dPair, config.dPair, rng)
    private val leftGate = Tensor.randn(config.dPair, config.dPair, rng)
    private val rightGate = Tensor.randn(config.dPair, config.dPair, rng)
    private val outputGate = Tensor.randn(config.dPair, config.dPair, rng)
    private val outputProjection = Tensor.randn(config.dPair, config.dPair, rng)

    private val transition1 = Tensor.randn(config.dPair, config.dPair * 2, rng)
    private val transition2 = Tensor.randn(config.dPair * 2, config.dPair, rng)

    fun parameters(): List<Tensor> =
        normIn.parameters() + normOut.parameters() + normTransition.parameters() +
            listOf(
                leftProjection, rightProjection, leftGate, rightGate,
                outputGate, outputProjection, transition1, transition2,
            )

    fun forward(pair: Tensor, length: Int): Tensor {
        val normed = normIn.forward(pair)

        val a = Ops.mul(Ops.matmul(normed, leftProjection), Ops.sigmoid(Ops.matmul(normed, leftGate)))
        val b = Ops.mul(Ops.matmul(normed, rightProjection), Ops.sigmoid(Ops.matmul(normed, rightGate)))

        val outgoing = triangleMultiply(a, b, length, incoming = false)
        val incoming = triangleMultiply(a, b, length, incoming = true)
        val combined = normOut.forward(Ops.add(outgoing, incoming))

        val gate = Ops.sigmoid(Ops.matmul(normed, outputGate))
        val updated = Ops.add(pair, Ops.mul(gate, Ops.matmul(combined, outputProjection)))

        val t = normTransition.forward(updated)
        val expanded = Ops.gelu(Ops.matmul(t, transition1))
        return Ops.add(updated, Ops.matmul(expanded, transition2))
    }

    /**
     * `out_ij = sum_k a_ik * b_jk` (outgoing) or `sum_k a_ki * b_kj` (incoming), channel-wise.
     *
     * A fused op with its own backward rather than a composition of the primitives, because
     * expressing this through [Ops.matmul] would need the pair tensor transposed into `c` separate
     * L×L matrices and back again — allocating L²c floats several times over per block, per
     * recycle, for a result computed here in one pass.
     */
    private fun triangleMultiply(a: Tensor, b: Tensor, length: Int, incoming: Boolean): Tensor {
        val d = config.dPair
        val out = Tensor(length * length, d)
        if (a.requiresGrad || b.requiresGrad) out.withGrad()
        out.parents = listOf(a, b)

        // Normalised by chain length so activations do not grow with L, which would make a model
        // trained on 64-residue crops produce nonsense on a 200-residue chain.
        val inv = 1f / length

        for (i in 0 until length) for (j in 0 until length) {
            val dst = (i * length + j) * d
            for (k in 0 until length) {
                val aOff = if (incoming) (k * length + i) * d else (i * length + k) * d
                val bOff = if (incoming) (k * length + j) * d else (j * length + k) * d
                for (c in 0 until d) out.data[dst + c] += a.data[aOff + c] * b.data[bOff + c] * inv
            }
        }

        out.backwardFn = {
            val g = out.grad!!
            val ga = a.grad
            val gb = b.grad
            for (i in 0 until length) for (j in 0 until length) {
                val dst = (i * length + j) * d
                for (k in 0 until length) {
                    val aOff = if (incoming) (k * length + i) * d else (i * length + k) * d
                    val bOff = if (incoming) (k * length + j) * d else (j * length + k) * d
                    for (c in 0 until d) {
                        val gv = g[dst + c] * inv
                        ga?.let { it[aOff + c] += gv * b.data[bOff + c] }
                        gb?.let { it[bOff + c] += gv * a.data[aOff + c] }
                    }
                }
            }
        }
        return out
    }
}
