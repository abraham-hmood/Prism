package com.prism.launcher.messaging

/**
 * How Sam spends model passes on a local GGUF model.
 *
 * Every mode below is a different answer to the same constraint: on a phone, decoding one token
 * reads the whole active weight set once, so a sequential loop is bounded by memory bandwidth.
 * These modes either verify several proposed tokens in one sweep or refine a whole block of them
 * in parallel. None of them changes the model file.
 *
 * [nativeId] is the contract with `gguf_bridge.cpp`; the numbers must not be reordered without
 * changing that file too.
 */
enum class InferenceMode(
    val id: String,
    val label: String,
    val detail: String,
    val nativeId: Int,
    /** A warning is shown before this mode is selected. */
    val experimental: Boolean = false,
    /**
     * The loader has to run with a different device layout. Changing into or out of this mode
     * reloads the model, because ggml's device assignment is fixed at load time.
     */
    val needsReload: Boolean = false,
) {
    // ORDER IS THE DROPDOWN ORDER. Forced diffusion is the first thing offered, as asked; Standard
    // sits last as the way back to ordinary decoding rather than as an unmentioned default.
    FORCED_DIFFUSION(
        id = "forced_diffusion",
        label = "Forced diffusion",
        detail = "Denoises a whole block of tokens in parallel, even on a model that was not " +
            "trained for it. Experimental: output is greedy and can lose coherence.",
        nativeId = 1,
        experimental = true,
    ),
    SPECULATIVE(
        id = "speculative",
        label = "Speculative decoding",
        detail = "Proposes up to eight tokens from the conversation and verifies them in one " +
            "model pass. No second model is installed, so the proposals come from earlier text.",
        nativeId = 2,
    ),
    LOOKAHEAD(
        id = "lookahead",
        label = "Lookahead / Jacobi decoding",
        detail = "Refines a 16-token block in parallel until it stops changing, then keeps the " +
            "stable prefix.",
        nativeId = 3,
    ),
    SELF_SPECULATIVE(
        id = "self_speculative",
        label = "Self-speculative decoding",
        detail = "Reuses the model's own last accepted block as the draft for the next one, so " +
            "no second model or draft head is needed.",
        nativeId = 4,
    ),
    BLOCK_PARALLEL(
        id = "block_parallel",
        label = "Block-parallel / learned parallel decoding",
        detail = "Widest block (32 tokens) refined in parallel. Fastest on repetitive text; " +
            "this runtime approximates a learned parallel head rather than shipping one.",
        nativeId = 5,
    ),
    RECURRENT_DEPTH(
        id = "recurrent_depth",
        label = "Recurrent-depth / latent reasoning",
        detail = "Extra refinement passes over short blocks. Keeps only fully converged blocks, " +
            "so it is slower than the other block modes and closer to sequential output.",
        nativeId = 6,
    ),
    HETERO(
        id = "hetero",
        label = "Heterogeneous / phase-aware execution",
        detail = "Offers every device to ggml so layers and graph nodes split across GPU, NPU " +
            "and CPU. Reloads the model when selected.",
        nativeId = 7,
        needsReload = true,
    ),
    STANDARD(
        id = "standard",
        label = "Standard decoding",
        detail = "One token at a time, sampling exactly as configured.",
        nativeId = 0,
    ),
    ;

    /** True when this mode decodes tokens in parallel rather than one at a time. */
    val isParallel: Boolean
        get() = this != STANDARD && this != HETERO

    companion object {
        /** Selectable in Settings, in the order the user sees them. */
        val selectable: List<InferenceMode> = entries.toList()

        fun fromId(id: String?): InferenceMode =
            entries.firstOrNull { it.id == id } ?: STANDARD

        /** The mode id for a stored preference, including values written by an older build. */
        fun normalise(id: String?): String = fromId(id).id
    }
}
