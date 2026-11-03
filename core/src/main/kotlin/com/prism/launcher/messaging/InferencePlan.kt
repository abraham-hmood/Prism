package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings

/**
 * How a generation spends its model passes, for every surface that has any.
 *
 * ## Why this exists beside [InferenceMode]
 *
 * [InferenceMode] is the user's choice, written once in Settings and read by the GGUF decoder. It was
 * only ever consulted by the chat path: a picture, a film frame, a passage of speech and a game mesh
 * all ignored it, so choosing "Jacobi decoding" changed one feature and silently did nothing
 * anywhere else. This is the translation layer -- the chosen mode, expressed as the knobs a
 * non-token generator actually has.
 *
 * ## What is genuinely transferable, and what is not
 *
 * The modes are answers to one constraint: a full pass over the model costs seconds, so get more out
 * of each pass. That is not specific to text. A diffusion sampler spends passes too, and the same
 * tricks apply:
 *
 *  - BLOCK_WIDTH is how many refinements are batched before the model is consulted again. Wider
 *    blocks mean fewer passes and coarser results.
 *  - VERIFY_EVERY is how often a proposal is checked rather than trusted. The speculative family
 *    exists because verification is cheaper than generation.
 *  - REFINE_PASSES is extra corrections over a block that has already been produced -- recurrent
 *    depth's whole idea, and the only mode here that spends MORE passes on purpose.
 *  - CONVERGENCE is the change below which further passes are not buying anything, which is what
 *    lets lookahead stop early instead of finishing a schedule it no longer needs.
 *  - REUSE_PREVIOUS is the self-speculative idea: the answer from the last pass is the best guess
 *    for this one, so start there instead of from noise.
 *
 * A surface that has no iteration at all -- a single forward pass, a pre-recorded utterance -- has
 * nothing to schedule, and each one states that rather than pretending. [noteFor] is how the UI says
 * which of those a given mode got.
 */
data class InferencePlan(
    val mode: InferenceMode,
    /** Refinements batched per model consultation. 1 is the plain sequential schedule. */
    val blockWidth: Int,
    /** How often a proposal gets a full verification rather than being accepted. 0 = always. */
    val verifyEvery: Int,
    /** Extra correction passes over a finished block. */
    val refinePasses: Int,
    /** Mean absolute change below which a block is considered converged. 0 disables early exit. */
    val convergence: Double,
    /** Start from the previous result rather than from noise, where the surface can use it. */
    val reusePrevious: Boolean,
    /** How the mode changes the work, in one sentence, for a page or a log line. */
    val summary: String,
) {

    /**
     * Sampling steps for a diffusion schedule whose standard length is [base].
     *
     * Parallel modes trade steps for width: the same amount of denoising is done, spread over fewer
     * consultations. Recurrent depth adds passes. Standard leaves it exactly alone, which is what a
     * user who never opened the setting is entitled to.
     */
    fun stepsFor(base: Int): Int {
        val safe = base.coerceAtLeast(1)
        return when (mode) {
            InferenceMode.STANDARD, InferenceMode.HETERO -> safe
            InferenceMode.FORCED_DIFFUSION -> (safe * 0.75).toInt().coerceAtLeast(2)
            InferenceMode.SPECULATIVE -> (safe * 0.85).toInt().coerceAtLeast(2)
            InferenceMode.SELF_SPECULATIVE -> (safe * 0.8).toInt().coerceAtLeast(2)
            InferenceMode.LOOKAHEAD -> (safe * 0.9).toInt().coerceAtLeast(2)
            InferenceMode.BLOCK_PARALLEL -> (safe * 0.6).toInt().coerceAtLeast(2)
            InferenceMode.RECURRENT_DEPTH -> (safe * 1.25).toInt()
        }
    }

    /** Characters of text one synthesis pass may cover, for a surface with no steps of its own. */
    fun chunkCharsFor(base: Int): Int {
        val safe = base.coerceAtLeast(120)
        return when (mode) {
            // Batching more text into a pass is the only way a non-iterative generator can spend
            // fewer passes, and it is exactly what the block modes promise.
            InferenceMode.FORCED_DIFFUSION,
            InferenceMode.BLOCK_PARALLEL,
            InferenceMode.LOOKAHEAD,
            -> (safe * 1.5).toInt()
            InferenceMode.SPECULATIVE,
            InferenceMode.SELF_SPECULATIVE,
            -> (safe * 1.2).toInt()
            InferenceMode.RECURRENT_DEPTH -> (safe * 0.75).toInt().coerceAtLeast(120)
            InferenceMode.STANDARD, InferenceMode.HETERO -> safe
        }
    }

    companion object {

        /** The user's selected mode, translated. */
        fun current(): InferencePlan = of(InferenceMode.fromId(PrismSettings.getInferenceMode()))

        /** A specific mode, for a benchmark or a per-run override. */
        fun of(mode: InferenceMode): InferencePlan = when (mode) {
            InferenceMode.FORCED_DIFFUSION -> InferencePlan(
                mode = mode, blockWidth = 8, verifyEvery = 0, refinePasses = 0,
                convergence = 0.0, reusePrevious = false,
                summary = "denoises a whole block per pass; greedy, fewer passes",
            )
            InferenceMode.SPECULATIVE -> InferencePlan(
                mode = mode, blockWidth = 4, verifyEvery = 4, refinePasses = 0,
                convergence = 0.0, reusePrevious = false,
                summary = "proposes from the last block and verifies every fourth step",
            )
            InferenceMode.LOOKAHEAD -> InferencePlan(
                mode = mode, blockWidth = 8, verifyEvery = 2, refinePasses = 1,
                convergence = 0.012, reusePrevious = false,
                summary = "refines a block until it stops changing, then stops early",
            )
            InferenceMode.SELF_SPECULATIVE -> InferencePlan(
                mode = mode, blockWidth = 4, verifyEvery = 0, refinePasses = 0,
                convergence = 0.02, reusePrevious = true,
                summary = "reuses the previous result as the starting point",
            )
            InferenceMode.BLOCK_PARALLEL -> InferencePlan(
                mode = mode, blockWidth = 16, verifyEvery = 0, refinePasses = 0,
                convergence = 0.0, reusePrevious = false,
                summary = "widest blocks, fewest passes",
            )
            InferenceMode.RECURRENT_DEPTH -> InferencePlan(
                mode = mode, blockWidth = 4, verifyEvery = 0, refinePasses = 2,
                convergence = 0.0, reusePrevious = false,
                summary = "extra refinement passes; slower and closer to sequential",
            )
            InferenceMode.HETERO -> InferencePlan(
                mode = mode, blockWidth = 1, verifyEvery = 0, refinePasses = 0,
                convergence = 0.0, reusePrevious = false,
                summary = "device layout is spread across GPU, NPU and CPU at load time",
            )
            InferenceMode.STANDARD -> InferencePlan(
                mode = mode, blockWidth = 1, verifyEvery = 0, refinePasses = 0,
                convergence = 0.0, reusePrevious = false,
                summary = "one step at a time, exactly as configured",
            )
        }

        /** What this mode means for a surface that has no iterative passes at all. */
        fun noteFor(mode: InferenceMode, surface: String): String = when (mode) {
            InferenceMode.STANDARD, InferenceMode.HETERO -> ""
            else -> "$surface has no sampling steps, so ${mode.label} reaches it only as its " +
                "pass budget."
        }
    }
}
