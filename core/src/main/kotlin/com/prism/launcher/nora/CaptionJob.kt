package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running auto-caption pass, with observable progress.
 *
 * SEPARATE FROM [CaptionService] BECAUSE THE JOB OUTLIVES THE SCREEN. Captioning a few hundred
 * images takes minutes; the dialog that started it will be destroyed by a rotation and the app
 * may be backgrounded entirely. So the state lives here as a [StateFlow] that a foreground
 * service owns and any UI can attach to, rather than inside the composable or activity that
 * happened to launch it.
 *
 * AUTO-APPLY IS A DECISION MADE UP FRONT, not a default and not a prompt at the end. The original
 * `auto_caption.py` defaults to a dry run because renaming someone's content is hard to reverse,
 * and that reasoning does not stop being true just because the work moved to a background
 * service. But a background job that finishes and then waits for someone to come back and tap a
 * dialog is not really a background job. The resolution is [applyAutomatically]: the user chooses
 * before it starts, so the safety property becomes an informed choice rather than either an
 * ambush or an obstacle.
 *
 * WHEN IT DOES APPLY AUTOMATICALLY IT WRITES AN UNDO MANIFEST. That is the part that makes the
 * choice defensible -- see [writeManifest].
 */
class CaptionJob(
    val directory: File,
    private val captioner: CaptionService.Captioner,
    val applyAutomatically: Boolean,
) {

    enum class Phase { PREPARING, CAPTIONING, APPLYING, DONE, CANCELLED, FAILED }

    /**
     * @param currentName the file being captioned RIGHT NOW, under its existing name -- which is
     *   what the user recognises it by, and the thing they need to see to judge whether a caption
     *   is sensible.
     * @param lastCaption what the previous file produced. Shown rather than the current one
     *   because the current file has not been captioned yet.
     * @param etaMillis -1 until there is enough history to mean anything.
     */
    data class Progress(
        val phase: Phase = Phase.PREPARING,
        val done: Int = 0,
        val total: Int = 0,
        val currentName: String = "",
        val lastName: String = "",
        val lastCaption: String = "",
        val etaMillis: Long = -1,
        val renamed: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val message: String = "",
    ) {
        val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    /** The plan, available once captioning finishes. Null until then. */
    @Volatile
    var plan: CaptionService.Plan? = null
        private set

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Runs the pass. Blocking; callers put it on a background thread.
     *
     * Returns the plan even when auto-apply ran, so a caller can report exactly what changed.
     */
    fun run(): CaptionService.Plan {
        val total = CaptionService.countCaptionable(directory)
        if (total == 0) {
            val empty = CaptionService.Plan(directory, emptyList(), listOf("No images in ${directory.name}"))
            plan = empty
            _progress.value = Progress(
                phase = Phase.FAILED, total = 0,
                message = "No images or videos in ${directory.name}",
            )
            return empty
        }

        _progress.value = Progress(phase = Phase.CAPTIONING, total = total)

        // ETA state. Durations are collected per item and averaged, with the FIRST one discarded:
        // it carries model load on the script path and connection setup on the cloud path, and is
        // routinely five to ten times the steady-state cost. Including it makes the first estimate
        // wildly pessimistic, which is worse than showing none.
        var samples = 0
        var averageMillis = 0.0
        var itemStart = System.currentTimeMillis()

        val timed = CaptionService.Captioner { file ->
            itemStart = System.currentTimeMillis()
            _progress.value = _progress.value.copy(currentName = file.name)
            val caption = captioner.caption(file)
            val elapsed = System.currentTimeMillis() - itemStart

            samples++
            if (samples > 1) {
                // Exponential moving average, weighted toward recent items: captioning speed
                // drifts with image size and network conditions, and an arithmetic mean over the
                // whole run stops tracking that after the first few dozen.
                averageMillis = if (samples == 2) elapsed.toDouble()
                else averageMillis * 0.8 + elapsed * 0.2
            }

            val current = _progress.value
            val remaining = (current.total - current.done - 1).coerceAtLeast(0)
            _progress.value = current.copy(
                lastName = file.name,
                lastCaption = caption.orEmpty().ifBlank { "(no caption)" },
                etaMillis = if (samples > 1) (averageMillis * remaining).toLong() else -1,
            )
            caption
        }

        val built = CaptionService.plan(directory, timed) { done, all ->
            if (cancelled.get()) return@plan
            _progress.value = _progress.value.copy(done = done, total = all)
        }
        plan = built

        if (cancelled.get()) {
            _progress.value = _progress.value.copy(phase = Phase.CANCELLED, message = "Cancelled")
            return built
        }

        if (!applyAutomatically) {
            _progress.value = _progress.value.copy(
                phase = Phase.DONE,
                failed = built.failures.size,
                message = "${built.actionable} file(s) ready to rename",
            )
            return built
        }

        _progress.value = _progress.value.copy(phase = Phase.APPLYING)
        // Written BEFORE the renames, not after. A manifest written afterwards is unavailable
        // exactly when it is needed most -- when the process died halfway through.
        writeManifest(built)
        val applied = CaptionService.apply(built)

        _progress.value = _progress.value.copy(
            phase = Phase.DONE,
            renamed = applied.renamed,
            skipped = applied.skipped,
            failed = built.failures.size + applied.errors.size,
            message = "Renamed ${applied.renamed}" +
                (if (applied.skipped > 0) ", skipped ${applied.skipped}" else "") +
                (if (built.failures.isNotEmpty()) ", ${built.failures.size} uncaptioned" else ""),
        )
        return built
    }

    /**
     * Records every planned rename so it can be reversed.
     *
     * THIS IS WHAT MAKES UNATTENDED RENAMING DEFENSIBLE. `auto_caption.py` protects the user by
     * refusing to act without confirmation; running in the background removes that moment, so
     * something has to replace it. A manifest does: the mapping is written into the directory
     * itself as tab-separated `new<TAB>old` lines, so reversing the pass is a short script or a
     * few minutes by hand, and it survives Prism being uninstalled.
     *
     * Named with a timestamp so repeated passes do not overwrite each other's history.
     */
    private fun writeManifest(plan: CaptionService.Plan) {
        val actionable = plan.renames.filter { !it.unchanged && !it.blocked }
        if (actionable.isEmpty()) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val manifest = File(directory, ".prism-caption-$stamp.tsv")
        try {
            manifest.bufferedWriter().use { out ->
                out.write("# Prism auto-caption undo manifest\n")
                out.write("# To reverse: rename each file on the left back to the name on the right.\n")
                out.write("# new\told\n")
                for (r in actionable) {
                    out.write("${r.to.name}\t${r.from.name}\n")
                }
            }
        } catch (e: Exception) {
            // Not fatal, but it does remove the safety net, so it is logged loudly rather than
            // swallowed.
            PrismPlatform.log.error(
                "Prism/caption",
                "Could not write the undo manifest -- renames will not be reversible",
                e,
            )
        }
    }

    companion object {
        /** Human ETA. Deliberately coarse: a to-the-second estimate implies precision it lacks. */
        fun formatEta(millis: Long): String = when {
            millis < 0 -> "estimating…"
            millis < 10_000 -> "a few seconds left"
            millis < 90_000 -> "about ${(millis / 1000)} seconds left"
            millis < 90 * 60_000 -> "about ${(millis / 60_000).coerceAtLeast(1)} min left"
            else -> "about ${millis / 3_600_000} hr ${(millis % 3_600_000) / 60_000} min left"
        }
    }
}
