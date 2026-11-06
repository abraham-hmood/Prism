package com.prism.launcher.podcasts

import com.prism.core.PrismPlatform
import com.prism.core.PrismPower
import com.prism.core.PrismSustain
import com.prism.core.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that went wrong while making or playing podcasts, kept for System Diagnostics.
 *
 * ## Two kinds of failure
 *
 *  - **Exceptions** Prism caught: a model that would not answer, a page that would not load, a voice
 *    that would not speak. Recorded with the stage they happened in and the stack trace.
 *  - **Deaths** Prism could not catch: the process killed from outside -- by Android for memory, or
 *    on Samsung phones by the Device Health Manager (`com.sec.android.sdhms`) when the phone runs
 *    too hot. Nothing runs at the moment of such a kill, so a job writes a marker file as it goes,
 *    and the next launch that finds one knows the job died, how far it got, how hot and short of
 *    memory the phone was, and -- on Android 11 and later -- the reason the system itself gives.
 */
object PodcastDiagnostics {

    private const val TAG = "Podcasts"
    private const val MAX_INCIDENTS = 200

    data class Incident(val at: Long, val kind: String, val podcast: String, val stage: String, val detail: String) {
        fun line(): String =
            SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(at)) + "  [" + kind + "] " +
                (if (podcast.isNotBlank()) "$podcast — " else "") + (if (stage.isNotBlank()) "$stage: " else "") + detail
    }

    /** A job that was running, as its marker recorded it. */
    data class Interrupted(val podcastId: String, val kind: String, val episodeId: String, val title: String, val topic: String, val stage: String, val startedAt: Long, val updatedAt: Long, val conditions: String)

    private fun incidents(): File = File(PodcastStore.root(), "incidents.jsonl")
    private fun marker(): File = File(PodcastStore.root(), "running.json")

    /** Records a caught exception or a notable event. Never throws. */
    fun record(kind: String, podcastId: String, stage: String, detail: String, error: Throwable? = null) {
        runCatching {
            val title = PodcastStore.find(podcastId)?.title ?: podcastId
            val text = if (error == null) detail
            else detail + ": " + (error.message ?: error.javaClass.simpleName) + "\n" + error.stackTraceToString().lines().take(14).joinToString("\n")
            val o = JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("podcast", title)
                .put("stage", stage).put("detail", text + "  (" + conditions() + ")")
            val file = incidents()
            file.parentFile?.mkdirs()
            file.appendText(o.toString() + "\n")
            // Kept bounded: diagnostics are for the recent past, not an archive.
            val lines = file.readLines()
            if (lines.size > MAX_INCIDENTS) file.writeText(lines.takeLast(MAX_INCIDENTS).joinToString("\n") + "\n")
        }
        if (error != null) PrismPlatform.log.error(TAG, "$kind at $stage: $detail", error)
        else PrismPlatform.log.warn(TAG, "$kind at $stage: $detail")
    }

    fun recent(limit: Int = 30): List<Incident> = runCatching {
        incidents().takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }.takeLast(limit).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Incident(o.optLong("at"), o.optString("kind"), o.optString("podcast"), o.optString("stage"), o.optString("detail"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun clear() { runCatching { incidents().delete() } }

    /** Heat and memory right now, in one line. */
    fun conditions(): String = runCatching {
        val heat = when (PrismPower.thermalStatus) {
            0 -> "cool"; 1 -> "light heat"; 2 -> "moderate heat"; 3 -> "severe heat"; 4 -> "critical heat"; else -> "heat ${PrismPower.thermalStatus}"
        }
        val memory = when (PrismSustain.memoryPressure) { 0 -> "memory ok"; 1 -> "memory moderate"; 2 -> "memory low"; else -> "memory critical" }
        val heap = Runtime.getRuntime().let { r -> "heap ${(r.totalMemory() - r.freeMemory()) shr 20}/${r.maxMemory() shr 20} MB" }
        "$heat, $memory, $heap"
    }.getOrDefault("conditions unknown")

    // ── Job markers ─────────────────────────────────────────────────────

    /** Notes that a job is running and how far it has got. Overwritten as it goes. */
    fun running(podcastId: String, kind: String, stage: String, episodeId: String = "", title: String = "", topic: String = "", startedAt: Long = System.currentTimeMillis()) {
        runCatching {
            marker().parentFile?.mkdirs()
            marker().writeText(JSONObject()
                .put("podcast", podcastId).put("kind", kind).put("stage", stage).put("episode", episodeId)
                .put("title", title).put("topic", topic).put("started", startedAt)
                .put("updated", System.currentTimeMillis()).put("conditions", conditions()).toString())
        }
    }

    /** Updates only the stage of the running marker, cheaply. */
    fun stage(stage: String) {
        runCatching {
            val file = marker()
            if (!file.isFile) return
            val o = JSONObject(file.readText())
            o.put("stage", stage).put("updated", System.currentTimeMillis()).put("conditions", conditions())
            file.writeText(o.toString())
        }
    }

    fun finished() { runCatching { marker().delete() } }

    /**
     * The job the previous process was running when it died, or null. Records the death as an
     * incident, with [systemReason] -- what the platform says ended the process -- when known.
     * Clears the marker, so it is reported once.
     */
    fun takeInterrupted(systemReason: String?): Interrupted? = runCatching {
        val file = marker()
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        file.delete()
        val job = Interrupted(
            o.optString("podcast"), o.optString("kind"), o.optString("episode"), o.optString("title"),
            o.optString("topic"), o.optString("stage"), o.optLong("started"), o.optLong("updated"), o.optString("conditions"),
        )
        if (job.podcastId.isBlank()) return null
        record(
            "killed", job.podcastId, job.stage,
            "Prism was stopped while making this (" + job.kind + "). Last known state: " + job.conditions +
                (if (systemReason.isNullOrBlank()) ". The system gave no reason." else ". System reason: $systemReason"),
        )
        job
    }.getOrNull()
}
