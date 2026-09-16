package com.prism.launcher.nora

import com.prism.core.PrismPlatform

/**
 * Every log line Nora produces, funnelled through one place.
 *
 * Not a wrapper for its own sake. Nora is the largest subsystem in Prism and the one whose
 * failures are hardest to see from the outside -- three of the four bugs recorded in NORA.md
 * produced clean-looking output rather than a crash, and were only caught because someone
 * noticed an image was the wrong colour. Consistent tagging is what makes those legible in the
 * diagnostics log afterwards: every line is `Nora/<area>`, so filtering the log by area gives a
 * coherent story of one subsystem instead of interleaved fragments.
 *
 * It also means the decision about what deserves WARN versus ERROR lives in one file. The rule
 * used here: ERROR is something that failed and that the user will notice; WARN is something
 * that succeeded but is probably not what they wanted (a degenerate image, a caption that bound
 * nothing, a size that had to be clamped); INFO is lifecycle -- what started, what finished,
 * with what numbers.
 */
object NoraLog {

    object Area {
        const val BRAIN = "Nora/brain"
        const val TRAIN = "Nora/train"
        const val GENERATE = "Nora/generate"
        const val FEEDBACK = "Nora/feedback"
        const val GEOMETRY = "Nora/geometry"
        const val SELFTEST = "Nora/selftest"
        const val SERVICE = "Nora/service"
        const val STORAGE = "Nora/storage"
        const val HEALTH = "Nora/health"
        const val CHAT = "Nora/chat"

        /** Where large structures were placed: heap, off-heap RAM, or the swap file. */
        const val MEMORY = "Nora/memory"

        /** Native acceleration: load, parity checks, and fallbacks to Kotlin. */
        const val NATIVE = "Nora/native"
    }

    fun info(area: String, message: String) = PrismPlatform.log.info(area, message)

    fun warn(area: String, message: String) = PrismPlatform.log.warn(area, message)

    fun error(area: String, message: String, t: Throwable? = null) =
        PrismPlatform.log.error(area, message, t)

    fun success(area: String, message: String) = PrismPlatform.log.success(area, message)

    /**
     * Reports a throwable that is not an ordinary failure -- an OutOfMemoryError from an
     * over-ambitious geometry, or a StackOverflowError.
     *
     * Kept separate because these are [Error]s rather than [Exception]s, so the ordinary
     * catch-and-log paths do not see them, and because they are the failures most worth having
     * in the diagnostics file: they usually take the process with them shortly afterwards.
     */
    fun fatal(area: String, message: String, t: Throwable) {
        PrismPlatform.log.error(area, "FATAL: $message", t)
        PrismPlatform.log.flushBlocking()
    }

    /** One-line description of the current brain size, for the head of any run. */
    fun describeGeometry(): String {
        val g = NoraConfig.geometry
        return "geometry ${g.signature()} — ${NoraGeometry.formatCount(g.totalNeurons)} neurons, " +
            "${NoraGeometry.formatCount(g.totalParameters)} params, " +
            "~${NoraGeometry.formatBytes(g.estimateBytes())}, " +
            "%.2fx default cost".format(g.relativeTrainingCost()) + "; " +
            NoraPerformance.describe()
    }
}
