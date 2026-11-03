package com.prism.launcher.messaging

import android.content.Context
import com.prism.launcher.PrismSettings

/**
 * The context a chat hands its model, shared by Sam and the Characters chat.
 *
 * ## The whole conversation, kept small enough by compaction
 *
 * Both chats send their ENTIRE conversation as context (see [AiManager.getResponse]'s
 * `conversationKey`). What keeps that inside the model's window is compaction: the older turns
 * folded into a recap by the model itself, the newest turns kept verbatim. The context pill shows
 * how full the window is and compacts on a tap; [shouldCompact] is the automatic version, run
 * before each turn.
 *
 * ## Why there is an estimate as well as the pill's measurement
 *
 * The pill reads llama.cpp's own count, which is exact but only describes the context as it is.
 * A conversation that has to be REPLAYED -- another chat used the model in between, or the app
 * restarted -- is sent whole in one prompt, and the engine refuses a prompt larger than its window.
 * So the full conversation is also estimated, at about 3.5 characters per token, and compacted
 * first if it would not fit.
 */
object ChatContext {

    /** Tokens per character for English-ish chat text, as an upper-leaning estimate. */
    private const val CHARS_PER_TOKEN = 3.5

    /** Room left for the reply and the chat template when deciding whether a replay fits. */
    private const val FIT_FRACTION = 0.75

    /** The meter and the compaction only describe a local model; elsewhere the window is not ours. */
    fun isLocal(): Boolean =
        PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL &&
            PrismSettings.getLocalAiModelPath().isNotBlank()

    /** A rough token count for [text]. */
    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt() + 1

    /**
     * Whether the next turn should compact first.
     *
     * [lines] are the conversation lines the next prompt would carry (after any recap), [recap] and
     * [persona] the blocks in front of them.
     */
    fun shouldCompact(lines: List<String>, recap: String, persona: String): Boolean {
        if (!isLocal()) return false
        val stats = GgufInferenceService.liveStats
        if (SamCompaction.needsCompaction(stats.contextFraction)) return true
        val window = stats.contextSize.takeIf { it > 0 } ?: DEFAULT_WINDOW
        val estimate = estimateTokens(persona) + estimateTokens(recap) + lines.sumOf { estimateTokens(it) + 3 }
        return estimate > window * FIT_FRACTION
    }

    /** The window GgufInferenceService opens a model with when nothing has reported one yet. */
    private const val DEFAULT_WINDOW = 2048

    /** What a compaction did, for the caller's toast. */
    sealed class Outcome {
        data class Compacted(val folded: Int) : Outcome()
        object TooShort : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Folds the older part of [lines] into a recap stored under [recapKey].
     *
     * The same routine Sam's pill always ran (see [SamCompaction]): the model writes a briefing of
     * everything but the newest turns, it is stored for that conversation, and the model's own
     * context is cleared so the next turn starts from the recap rather than paying for both.
     * [lines] are named by speaker so the briefing knows who said what.
     */
    suspend fun compact(context: Context, recapKey: String, lines: List<String>): Outcome {
        val plan = SamCompaction.plan(lines)
        if (!plan.canCompact) return Outcome.TooShort
        val summary = AiManager.getResponse(
            context,
            SamCompaction.request(plan.older),
            history = emptyList(),
            allowOffload = false,
            plainText = true,
        ).first.trim()
        val failed = summary.isBlank() || summary.startsWith("Error:") || summary.startsWith("P2P Model Error:")
        if (failed) return Outcome.Failed(summary.ifBlank { "nothing came back" })
        SamCompaction.store(recapKey, summary, plan.older.size)
        runCatching { GgufInferenceService.resetConversation() }
        return Outcome.Compacted(plan.older.size)
    }
}
