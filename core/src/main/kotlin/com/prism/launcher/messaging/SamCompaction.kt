package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings

/**
 * Folds the older part of a conversation into a short recap, so a long thread can outlive the
 * model's context window.
 *
 * ## Why a recap rather than deleting turns
 *
 * The transcript is the user's record of what was said, and a launcher that quietly removed half
 * of it to make room would be destroying the thing the user was reading. What DOES have to shrink
 * is the text resent to the model on every turn, and that only needs the shape of the earlier
 * conversation -- who asked what, what was decided, which names and numbers matter -- not its
 * wording. So the turns stay on screen and the prompt carries the recap plus the recent turns.
 *
 * ## Why the split is fixed at [KEEP_RECENT_TURNS] rather than measured
 *
 * Because the number that matters is available: [GgufInferenceService.liveStats.contextFraction]
 * says how full the window is, and compaction is triggered by THAT. Counting tokens here would
 * mean a second estimate of the same quantity, one that disagrees with the engine's own count and
 * would compact either too early or too late depending on which one the user happened to read.
 *
 * ## Why the recap is persisted
 *
 * A compacted thread that forgets its recap on restart starts again from the last few turns, which
 * is what the user just paid a summarisation to avoid. It is stored per thread key in the same
 * preferences every other setting lives in, so it survives the process without a schema change.
 */
object SamCompaction {

    /**
     * The fraction of the context window at which a turn compacts before it starts.
     *
     * 0.95 rather than 1.0 because the next prompt has to FIT: a compaction started when the
     * window is already full cannot run at all -- the summarisation request itself has nowhere to
     * go. The meter turns red at 0.9, so this is the red ring actually meaning something.
     */
    const val TRIGGER_FRACTION = 0.95f

    /** Turns left verbatim after a compaction: the part a conversation actually refers back to. */
    const val KEEP_RECENT_TURNS = 8

    /** Split of a history into the part folded away and the part kept. */
    data class Plan(val older: List<String>, val recent: List<String>) {
        /** One turn is not a conversation; folding it away loses more than it saves. */
        val canCompact: Boolean get() = older.size >= 2
    }

    /** True when the context window is full enough that the next turn should compact first. */
    fun needsCompaction(fraction: Float): Boolean =
        fraction.isFinite() && fraction >= TRIGGER_FRACTION

    /** Splits [history] (oldest first, both speakers) into what is folded away and what is kept. */
    fun plan(history: List<String>): Plan {
        val spoken = history.map { it.trim() }.filter { it.isNotEmpty() }
        if (spoken.size <= KEEP_RECENT_TURNS) return Plan(emptyList(), spoken)
        val cut = spoken.size - KEEP_RECENT_TURNS
        return Plan(spoken.take(cut), spoken.drop(cut))
    }

    /**
     * The instruction that produces the recap.
     *
     * It asks for a briefing rather than a summary because the only reader is the model itself on
     * the next turn, and what it needs is the state of the conversation -- not a description of
     * one. The word limit is stated because a recap that grows to the size of the turns it
     * replaced has compacted nothing.
     */
    fun request(older: List<String>): String = buildString {
        appendLine("Write a briefing of the earlier part of this conversation, for yourself.")
        appendLine(
            "Keep what the conversation established: what the user asked for, decisions taken, " +
                "names, numbers, preferences, constraints, and anything left unfinished. Drop " +
                "pleasantries, repeated wording, and your own apologies or filler."
        )
        appendLine("Write at most 180 words as plain lines. Answer with the briefing only, no preamble.")
        appendLine()
        appendLine("Conversation:")
        older.forEach { line -> appendLine(line.trim()) }
    }

    /**
     * The recap stored for [threadKey], or "" when the thread has never been compacted.
     *
     * Failure is answered with "" rather than thrown: an unreadable preference means the thread is
     * simply not compacted, which is the state it was in before the feature existed.
     */
    fun recap(threadKey: String): String = runCatching {
        val raw = PrismSettings.getSamCompaction(threadKey)
        if (raw.isBlank()) return@runCatching ""
        raw.substringAfter('\n', "").trim()
    }.getOrDefault("")

    /** How many turns the stored recap already stands in for; 0 when there is none. */
    fun foldedTurns(threadKey: String): Int = runCatching {
        PrismSettings.getSamCompaction(threadKey).substringBefore('\n').toIntOrNull() ?: 0
    }.getOrDefault(0)

    /** Stores [recap] for [threadKey], with the number of turns it replaces. */
    fun store(threadKey: String, recap: String, foldedTurns: Int) {
        val text = recap.trim()
        if (text.isEmpty()) return
        runCatching { PrismSettings.setSamCompaction(threadKey, "$foldedTurns\n$text") }
    }

    /** Forgets the recap, so the next compaction starts from the whole transcript again. */
    fun clear(threadKey: String) {
        runCatching { PrismSettings.setSamCompaction(threadKey, "") }
    }

    /** The block a prompt carries in front of the recent turns. Empty when nothing is compacted. */
    fun preamble(recap: String): String {
        val text = recap.trim()
        if (text.isEmpty()) return ""
        return "Earlier in this conversation (compacted):\n$text"
    }

    /** What to tell the user after a compaction, so it is visible rather than inferred. */
    fun describe(foldedTurns: Int): String =
        "Compacted $foldedTurns earlier ${if (foldedTurns == 1) "message" else "messages"} into a recap."
}
