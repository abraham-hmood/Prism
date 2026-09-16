package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.LocalAi

/**
 * An AI summary of a page of search results.
 *
 * ## Local models only, and that is the whole point
 *
 * A search engine that shipped every query to somebody's API would undo what Prism's search is for.
 * [isAvailable] therefore accepts exactly three arrangements -- an imported on-device model, a
 * local-cloud (Ollama) endpoint on the user's own network, or nothing. A cloud API key configured
 * for chat does NOT enable this: the summary is off unless the model answering it is one the user
 * runs.
 *
 * ## Fed the results, not the query
 *
 * The model never browses and never sees anything the crawler did not already index. It receives
 * the titles, URLs and snippets of the results being shown and is asked to summarise those. That
 * bounds it to material the user is already looking at, and it means a summary cannot cite a page
 * the results do not contain.
 *
 * ## It can be wrong, and the page says so
 *
 * A small local model summarising crawler snippets will sometimes state things the sources do not.
 * The rendered block is labelled and sits above the results rather than replacing them, so the
 * sources are always one glance away.
 */
object PrismSearchSummary {

    private const val TAG = "Prism/search-ai"

    /** How many results are shown to the model. Enough for a summary, short enough to stay fast. */
    private const val RESULTS_USED = 8

    /** Snippet characters per result. A small local model has a small context. */
    private const val SNIPPET_CHARS = 320

    /**
     * Whether a summary can be produced right now.
     *
     * Three conditions, all required: the user turned it on, the AI mode is one Prism runs
     * locally, and that backend actually has a model selected. The last is what stops the block
     * appearing as a permanent "unavailable" for somebody who never imported one.
     */
    fun isAvailable(): Boolean {
        if (!PrismSettings.getSearchAiSummary()) return false

        return when (PrismSettings.getAiMode()) {
            // An imported .gguf/.task on this device.
            PrismSettings.AI_MODE_LOCAL ->
                PrismSettings.getLocalAiModelPath().isNotBlank() && LocalAi.available()

            // An Ollama server on the user's own network.
            PrismSettings.AI_MODE_LOCAL_CLOUD ->
                PrismSettings.getSelectedOllamaEndpoint() != null

            // Cloud keys are deliberately excluded: see the class note.
            else -> false
        }
    }

    /** Why the summary is not showing, for the settings screen rather than the search page. */
    fun unavailableReason(): String = when {
        !PrismSettings.getSearchAiSummary() -> "Turned off in Settings."
        PrismSettings.getAiMode() == PrismSettings.AI_MODE_CLOUD ->
            "Summaries only use models you run yourself, and the active model is a cloud one."
        PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL &&
            PrismSettings.getLocalAiModelPath().isBlank() ->
            "No local model is active. Import one on the Models page and select it."
        PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL_CLOUD &&
            PrismSettings.getSelectedOllamaEndpoint() == null ->
            "No Ollama server is selected."
        !LocalAi.available() -> "The local model backend is not available on this device."
        else -> "Ready."
    }

    /**
     * Summarises [results] for [query], or returns null.
     *
     * NULL RATHER THAN AN APOLOGY. If the model is missing, slow to the point of failure, or
     * returns nothing usable, the page simply shows results with no summary block -- which is the
     * normal state of a search engine and needs no explanation. An error card above every result
     * set would be worse than the feature being absent.
     */
    fun summarise(query: String, results: List<PrismSearchIndex.Result>): String? {
        if (!isAvailable() || query.isBlank() || results.isEmpty()) return null

        return try {
            val answer = LocalAi.generator.generate(
                PrismSettings.getLocalAiModelPath(),
                buildPrompt(query, results),
            )
            clean(answer)
        } catch (t: Throwable) {
            PrismPlatform.log.debug(TAG, "Summary failed: ${t.message}")
            null
        }
    }

    /**
     * The prompt.
     *
     * Explicitly bounded to the supplied text, because a model asked to "summarise search results"
     * will otherwise answer from its own training and produce something that looks like a summary
     * of pages nobody indexed.
     */
    fun buildPrompt(query: String, results: List<PrismSearchIndex.Result>): String = buildString {
        appendLine("Summarise what these search results say about: $query")
        appendLine()
        appendLine("Use ONLY the text below. Do not add facts that are not in it.")
        appendLine("If the results do not answer the question, say so in one sentence.")
        appendLine("Write 2-4 sentences of plain prose. No lists, no preamble, no markdown.")
        appendLine()
        results.take(RESULTS_USED).forEachIndexed { i, r ->
            appendLine("[${i + 1}] ${r.title}")
            appendLine(r.url)
            appendLine(r.snippet.take(SNIPPET_CHARS))
            appendLine()
        }
    }

    /**
     * Strips the wrappers small models add.
     *
     * Reasoning blocks especially: a `<think>` section rendered into the page would be longer than
     * the summary and would read as the answer.
     */
    fun clean(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        text = text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
        // An unterminated reasoning block means the model never reached its answer.
        if (text.contains("<think>", ignoreCase = true)) return null

        text = text.removePrefix("Summary:").removePrefix("summary:").trim()
        // A model that echoed the instructions back has not summarised anything.
        if (text.startsWith("Summarise", ignoreCase = true)) return null

        return text.ifBlank { null }?.take(1200)
    }
}
