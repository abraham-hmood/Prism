package com.prism.launcher.nora

/**
 * The few things the model needs from whatever is hosting it.
 *
 * Kept to an absolute minimum, and every entry has to justify itself. The temptation once a hook
 * object exists is to route anything awkward through it, at which point it becomes a second,
 * undeclared platform interface with none of the discipline of the first. So: one hook, with a
 * working default, and a reason.
 */
object NoraRuntime {

    /**
     * Drops the host's long-lived brain, if it keeps one.
     *
     * Exists for the model test, which builds a second sandboxed brain to measure a candidate
     * size. At any interesting geometry two resident connectomes is the difference between
     * fitting and an OutOfMemoryError -- and failing that way would report the size as too large
     * when the real problem was the brain nobody needed at that moment.
     *
     * A no-op by default, which is correct for a host that builds a brain per run rather than
     * sharing one.
     */
    @Volatile
    var releaseSharedBrain: () -> Unit = {}
}
