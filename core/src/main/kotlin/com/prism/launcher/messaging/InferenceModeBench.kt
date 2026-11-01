package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * Picks an inference mode by actually running one.
 *
 * "FASTEST" IS NOT A PROPERTY THAT CAN BE READ OFF THE MODE. The same block-refinement driver is
 * a large win on a model that repeats itself and a loss on one that does not; a phone with an
 * accelerator and one without disagree about heterogeneous execution. So the only honest selector
 * is a smoke test on the model the user has selected, and that is what this does: the same short
 * prompt through every mode, with the measured rate coming from the engine's own token callback.
 *
 * The test is deliberately small -- [TOKENS] tokens per mode -- because its job is to rank modes,
 * not to produce a benchmark paper. A run that returns an error or no tokens is ranked last
 * rather than crashing the selector.
 */
object InferenceModeBench {

    /** Enough for a rate to settle, short enough that eight modes finish in well under a minute. */
    private const val TOKENS = 12

    private const val PROMPT =
        "Reply with one short sentence about the sea. Do not explain."

    data class Result(
        val mode: InferenceMode,
        val tokensPerSecond: Double,
        /** Why this mode was skipped or ranked where it was, for the result dialog. */
        val note: String = "",
    )

    /**
     * Runs the smoke test and returns every mode that produced tokens, fastest first.
     *
     * [onProgress] is called before each mode, so a caller can show which one is running. HETERO
     * is tested last: it changes the device layout at load time, and running it early would make
     * every later mode pay for a second model load.
     */
    fun run(onProgress: (index: Int, total: Int, mode: InferenceMode) -> Unit = { _, _, _ -> }): List<Result> {
        val modelPath = PrismSettings.getLocalAiModelPath()
        if (modelPath.isBlank() || !GgufInferenceService.isAvailable()) return emptyList()

        val modes = InferenceMode.selectable.sortedBy { it == InferenceMode.HETERO }
        val results = mutableListOf<Result>()

        // Warm the model before timing anything. The first call in a process includes the load,
        // and charging that to whichever mode happened to be first would rank that mode last for
        // a reason that has nothing to do with decoding.
        runCatching {
            GgufInferenceService.generateResponseStreaming(
                modelPath, "Hello.", 2, onToken = {}, modeOverride = InferenceMode.STANDARD,
            )
        }
        GgufInferenceService.resetConversation()

        modes.forEachIndexed { index, mode ->
            onProgress(index, modes.size, mode)
            GgufInferenceService.resetConversation()
            val started = System.currentTimeMillis()
            val answer = runCatching {
                GgufInferenceService.generateResponseStreaming(
                    modelPath, PROMPT, TOKENS, onToken = {}, modeOverride = mode,
                )
            }.getOrElse { error ->
                PrismPlatform.log.warn(
                    "InferenceModeBench",
                    "Mode ${mode.id} failed: ${error.message ?: error::class.java.simpleName}",
                )
                return@forEachIndexed
            }
            val elapsed = System.currentTimeMillis() - started

            val failed = answer.startsWith("Error:") || answer.startsWith("Local AI Error:")
            val generated = GgufInferenceService.liveStats.generated
            if (failed || generated <= 0 || elapsed <= 0) {
                results += Result(mode, 0.0, if (failed) answer.take(120) else "no tokens")
                return@forEachIndexed
            }

            // The engine's final rate, not elapsed wall time around the call: a mode that emits a
            // block at once would otherwise look slower than it is because the prompt is included.
            val rate = GgufInferenceService.lastTokensPerSecond
                .takeIf { it > 0.0 }
                ?: (generated * 1000.0 / elapsed)
            results += Result(mode, rate, "$generated tokens")
        }

        GgufInferenceService.resetConversation()
        return results.sortedByDescending { it.tokensPerSecond }
    }

    /** The fastest mode that actually produced output, or null when nothing could run. */
    fun best(results: List<Result>): InferenceMode? =
        results.firstOrNull { it.tokensPerSecond > 0.0 }?.mode
}
