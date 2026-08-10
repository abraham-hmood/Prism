package com.prism.launcher.nora

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * Nora's conversation history, kept as a JSON file rather than a Room table.
 *
 * Deliberate: Prism's Room database is configured with fallbackToDestructiveMigration, so
 * adding a table means bumping the schema version, which wipes every existing table -- Sam's
 * history and all of Nebula with it. Nora's transcript is short and command-shaped, so a flat
 * file on Nora's own storage costs nothing and risks nothing.
 */
object NoraChatStore {

    data class Entry(
        val text: String,
        val isSent: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val attachmentUri: String? = null,
        val attachmentType: String? = null,
        /** Identifies the trace a thumb acts on. Null on messages that produced no image. */
        val feedbackToken: String? = null,
        /** 0 unrated, +1 approved, -1 rejected. */
        val feedback: Int = 0,
        /**
         * Whether the thumbs are on screen for this message.
         *
         * Persisted rather than held in the adapter, because a rating is a durable statement
         * about a generation and so is the decision to stop being asked about one -- tapping a
         * bubble to dismiss the thumbs should not be undone by scrolling away or rotating.
         */
        val showFeedback: Boolean = false
    )

    private const val MAX_ENTRIES = 400

    @Synchronized
    fun load(ctx: Context): List<Entry> {
        val file = NoraConfig.chatFile()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    text = o.optString("text"),
                    isSent = o.optBoolean("sent"),
                    timestamp = o.optLong("ts", System.currentTimeMillis()),
                    attachmentUri = o.optString("uri").ifBlank { null },
                    attachmentType = o.optString("type").ifBlank { null },
                    feedbackToken = o.optString("fbt").ifBlank { null },
                    feedback = o.optInt("fb", 0),
                    showFeedback = o.optBoolean("fbs", false)
                )
            }
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not read conversation: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun append(ctx: Context, entry: Entry) {
        val all = (load(ctx) + entry).takeLast(MAX_ENTRIES)
        write(ctx, all)
    }

    /**
     * Records a rating and/or the thumbs' visibility against one generation.
     *
     * Keyed by token rather than by list index: the transcript is re-read from disk on every
     * change, and an index would go stale the moment the service appended anything.
     *
     * @param feedback the new rating, or null to leave the existing one alone (used when the
     *        only thing changing is whether the buttons are on screen).
     */
    @Synchronized
    fun setFeedback(ctx: Context, token: String, feedback: Int?, showFeedback: Boolean) {
        val all = load(ctx)
        var changed = false
        val updated = all.map { e ->
            if (e.feedbackToken != token) {
                e
            } else {
                changed = true
                e.copy(feedback = feedback ?: e.feedback, showFeedback = showFeedback)
            }
        }
        if (changed) write(ctx, updated)
    }

    @Synchronized
    fun clear(ctx: Context) {
        write(ctx, emptyList())
    }

    private fun write(ctx: Context, entries: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().apply {
                    put("text", e.text)
                    put("sent", e.isSent)
                    put("ts", e.timestamp)
                    e.attachmentUri?.let { put("uri", it) }
                    e.attachmentType?.let { put("type", it) }
                    e.feedbackToken?.let { put("fbt", it) }
                    if (e.feedback != 0) put("fb", e.feedback)
                    if (e.showFeedback) put("fbs", true)
                })
            }
            NoraConfig.chatFile().writeText(arr.toString())
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not write conversation: ${e.message}")
        }
    }

    fun lastSnippet(ctx: Context): String =
        load(ctx).lastOrNull()?.text?.take(60) ?: "Brain-based image and video generation."
}
