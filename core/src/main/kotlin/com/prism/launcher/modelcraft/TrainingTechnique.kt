package com.prism.launcher.modelcraft

/**
 * The nine processor-level techniques, and what each one becomes in a training run.
 *
 * ## Why this is a dropdown on every model type
 *
 * Nothing about "overlap the forward and backward pass" is specific to text, images or speech: the
 * training loop is the same shape in all six kinds, and so is the hardware it runs on. Putting the
 * choice on only some tabs would be an accident of who wrote which trainer first.
 *
 * ## What the coefficients are, and what they are not
 *
 * [speedup] and [quality] are documented estimates of what the technique buys on a run of this size,
 * not measurements. The smoke test in [TrainingTechniqueSmokeTest] measures this device's own data
 * and compute cost and then ranks the techniques against that measurement; the numbers here are the
 * part that cannot be measured without running a full training job, and the report says so in the
 * same sentence rather than presenting the whole figure as observed.
 *
 * [quality] is the fraction of the baseline run's quality the technique is expected to keep. Only
 * three of the nine are below 1.0, and all three are below it for the same reason: they trade exact
 * gradients for throughput.
 */
enum class TrainingTechnique(
    val label: String,
    val coreIdea: String,
    val analogue: String,
    val feasibility: String,
    val speedup: Double,
    val quality: Double,
) {
    PIPELINING(
        label = "Pipelining",
        coreIdea = "Overlap different stages of work.",
        analogue = "Overlap the forward and backward passes, overlap data loading with compute, " +
            "and run in micro-batches so a stage is never waiting on the whole.",
        feasibility = "High — the way every serious framework already trains",
        speedup = 1.45,
        quality = 1.00,
    ),
    SPECULATIVE(
        label = "Speculative execution",
        coreIdea = "Start work before you know it is needed.",
        analogue = "Speculative or approximate gradient steps, early-exit training, and " +
            "speculative-decoding ideas applied to the training step itself.",
        feasibility = "Medium — research exists, less mature",
        speedup = 1.30,
        quality = 0.97,
    ),
    OUT_OF_ORDER(
        label = "Out-of-order / async execution",
        coreIdea = "Do not wait for previous results if they are independent.",
        analogue = "Asynchronous SGD, Hogwild-style updates, local SGD and delayed gradients.",
        feasibility = "Medium-high for small models",
        speedup = 1.35,
        quality = 0.97,
    ),
    CACHING(
        label = "Caching & prefetching",
        coreIdea = "Keep likely-needed data close and fetch it early.",
        analogue = "Activation checkpointing versus recompute, aggressive prefetch of the next " +
            "batches, and selectively caching intermediate activations.",
        feasibility = "High",
        speedup = 1.40,
        quality = 1.00,
    ),
    SIMD(
        label = "SIMD / vectorization",
        coreIdea = "Do the same operation on many elements at once.",
        analogue = "Vectorised kernels and optimisers, and fused elementwise passes instead of " +
            "one traversal per operation.",
        feasibility = "Already partly there — NEON and the JIT do much of it",
        speedup = 1.20,
        quality = 1.00,
    ),
    MULTICORE(
        label = "Multi-core / parallelism",
        coreIdea = "Split work across execution units.",
        analogue = "Data parallelism, tensor or model parallelism, and expert parallelism for MoEs.",
        feasibility = "Low-medium on one device — a phone has few wide cores",
        speedup = 1.55,
        quality = 1.00,
    ),
    HIERARCHICAL_MEMORY(
        label = "Hierarchical memory",
        coreIdea = "Fast small memory plus slower large memory.",
        analogue = "Offload optimiser states, gradients or activations to flash or a swap region, " +
            "the same spirit as ZeRO and Prism Swap.",
        feasibility = "High potential",
        speedup = 1.25,
        quality = 1.00,
    ),
    BRANCH_PREDICTION(
        label = "Branch prediction / speculation",
        coreIdea = "Guess the future path.",
        analogue = "Adaptive computation with early exit, dynamic layer skipping, and " +
            "confidence-based training steps.",
        feasibility = "Medium",
        speedup = 1.28,
        quality = 0.95,
    ),
    SUPERSCALAR(
        label = "Superscalar / multiple issue",
        coreIdea = "Execute multiple independent operations per cycle.",
        analogue = "Operator fusion, concurrent forward and backward streams where they are " +
            "independent, and multi-stream execution.",
        feasibility = "Medium",
        speedup = 1.32,
        quality = 1.00,
    );

    /** Stable identifier written into the recipe; the form stores the label. */
    val id: String get() = name.lowercase()
}

/**
 * Reading, writing and applying a [TrainingTechnique].
 *
 * The knob sets are recipe keys rather than something this build enforces: a trainer that reads
 * `micro_batch` uses it, and one that does not is unaffected by its presence. That is deliberate.
 * A technique that silently rewrote `batch_size` behind the user's back would change what the run
 * means, not just how fast it is; these keys only ever ADD a hint beside the values the form owns,
 * and every one of them is named in the recipe under `technique`.
 */
object TrainingTechniques {

    /** The recipe key every kind declares as a field. */
    const val KEY = "training_technique"

    /** What an untouched form asks for: the highest-feasibility option that costs no quality. */
    val DEFAULT: TrainingTechnique = TrainingTechnique.PIPELINING

    val all: List<TrainingTechnique> = TrainingTechnique.entries

    /** The dropdown's rows, in the order the form shows them. */
    val choices: List<String> = all.map { it.label }

    /**
     * The technique named by [value], which may be the label, the enum name or the id.
     *
     * Null rather than a fallback, so a recipe naming something this build does not have can be
     * reported where somebody sees it instead of quietly training a different way.
     */
    fun of(value: String): TrainingTechnique? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return all.firstOrNull {
            it.label.equals(text, true) ||
                it.name.equals(text, true) ||
                it.id.equals(text.lowercase().replace(' ', '_'), true)
        }
    }

    /** The model's chosen technique, or [DEFAULT] when it has not chosen one. */
    fun of(model: CraftedModel): TrainingTechnique = of(model.param(KEY)) ?: DEFAULT

    /** Writes the choice in the form both platforms and the recipe read. */
    fun set(model: CraftedModel, value: String) {
        model.params[KEY] = (of(value) ?: DEFAULT).label
    }

    /**
     * The extra recipe keys a technique asks a trainer to honour.
     *
     * [cores] is the device's own count, because the one technique that cannot be estimated without
     * it is the one about using them.
     */
    fun knobs(technique: TrainingTechnique, cores: Int): Map<String, String> {
        val usable = cores.coerceIn(1, 64)
        return when (technique) {
            TrainingTechnique.PIPELINING -> mapOf(
                "pipeline_stages" to "2",
                "micro_batch" to "auto",
                "overlap_loading_and_compute" to "true",
                "overlap_forward_and_backward" to "true",
            )
            TrainingTechnique.SPECULATIVE -> mapOf(
                "early_exit" to "true",
                "early_exit_confidence" to "0.9",
                "speculative_steps" to "2",
            )
            TrainingTechnique.OUT_OF_ORDER -> mapOf(
                "async_updates" to "true",
                "gradient_staleness" to "3",
                "local_sgd_steps" to "2",
            )
            TrainingTechnique.CACHING -> mapOf(
                "prefetch_batches" to "4",
                "cache_latents" to "true",
                "activation_checkpointing" to "selective",
            )
            TrainingTechnique.SIMD -> mapOf(
                "vector_width" to "8",
                "fuse_kernels" to "true",
            )
            TrainingTechnique.MULTICORE -> mapOf(
                "dataloader_workers" to usable.toString(),
                "intra_op_threads" to usable.toString(),
                "tensor_parallel" to "1",
            )
            TrainingTechnique.HIERARCHICAL_MEMORY -> mapOf(
                "offload_optimizer" to "true",
                "activation_checkpointing" to "true",
                "swap_arena" to "true",
            )
            TrainingTechnique.BRANCH_PREDICTION -> mapOf(
                "adaptive_compute" to "true",
                "layer_skip_threshold" to "0.9",
            )
            TrainingTechnique.SUPERSCALAR -> mapOf(
                "operator_fusion" to "true",
                "concurrent_streams" to "2",
                "issue_width" to "2",
            )
        }
    }

    /** The one-line descriptions the form shows under the dropdown. */
    fun describe(technique: TrainingTechnique): String =
        "${technique.coreIdea} In training: ${technique.analogue} Feasibility: ${technique.feasibility}."

    /** How many processors this device has, for [knobs]. */
    fun cores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
}
