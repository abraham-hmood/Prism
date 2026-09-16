package com.prism.launcher.aether

import com.prism.core.PrismPlatform

/**
 * Every log line Aether produces, funnelled through one place -- same rationale as NoraLog:
 * consistent `Aether/<area>` tagging makes the diagnostics log filterable into a coherent story
 * per subsystem instead of interleaved fragments, which matters most exactly when something has
 * gone wrong silently (a divergent STDP update, a dead gradient, a blank generation).
 */
object AetherLog {

    object Area {
        const val BRAIN = "Aether/brain"
        const val TRAIN = "Aether/train"
        const val GENERATE = "Aether/generate"
        const val STORAGE = "Aether/storage"
        const val SERVICE = "Aether/service"
    }

    fun info(area: String, message: String) = PrismPlatform.log.info(area, message)

    fun warn(area: String, message: String) = PrismPlatform.log.warn(area, message)

    fun error(area: String, message: String, t: Throwable? = null) =
        PrismPlatform.log.error(area, message, t)

    fun success(area: String, message: String) = PrismPlatform.log.success(area, message)

    /** For an [Error] (OOM, stack overflow) rather than an ordinary failure -- flushes before the process is likely to die. */
    fun fatal(area: String, message: String, t: Throwable) {
        PrismPlatform.log.error(area, "FATAL: $message", t)
        PrismPlatform.log.flushBlocking()
    }
}
