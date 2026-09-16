package com.prism.launcher.nora

import com.prism.core.PrismPlatform

/**
 * Numerical tripwire.
 *
 * Nora trains with local rules and no framework underneath, so there is nothing watching for
 * divergence. The first version of this project found that out the hard way: an oversized
 * learning step drove the weights to Infinity, the resulting NaNs were silently converted to
 * zeros by the metabolic tax in IT, and the whole system went on producing plausible-looking
 * black images and polite "I haven't seen that" replies for two full training runs. The only
 * visible symptom was a NaN in the log that was easy to read as cosmetic.
 *
 * A failure that produces clean-looking output is worse than a crash. This object exists so
 * that never happens again: the first non-finite value anywhere records where it came from,
 * training aborts on it, and the message says what to do.
 */
object NoraHealth {

    /** Description of the first fault seen since the last [reset], or null if healthy. */
    @Volatile
    var firstFault: String? = null
        private set

    @Volatile
    var faultCount: Int = 0
        private set

    val healthy: Boolean get() = firstFault == null

    fun reset() {
        firstFault = null
        faultCount = 0
    }

    /** Records a fault. Only the first is kept -- after divergence everything downstream faults. */
    @Synchronized
    fun report(where: String) {
        faultCount++
        if (firstFault == null) {
            firstFault = where
            // ERROR rather than WARN, and flushed by the platform log on the spot: a numerical fault
            // is the start of a cascade, and the FIRST one is the only informative one. Every
            // fault after it is a consequence.
            PrismPlatform.log.error(NoraLog.Area.HEALTH, "First numerical fault: $where")
        } else if (faultCount == 50) {
            PrismPlatform.log.warn(
                NoraLog.Area.HEALTH,
                "50 faults since \"$firstFault\" — the network is fully diverged, not glitching"
            )
        }
    }

    /** True when [value] is usable. Reports and returns false otherwise. */
    fun check(value: Float, where: String): Boolean {
        if (value.isFinite()) return true
        report(where)
        return false
    }

    /** Scans an array for non-finite entries. Sampled on large arrays to stay cheap. */
    fun scan(data: FloatArray, where: String): Boolean {
        val step = if (data.size > 8192) data.size / 4096 else 1
        var i = 0
        while (i < data.size) {
            if (!data[i].isFinite()) {
                report(where)
                return false
            }
            i += step
        }
        return true
    }

    fun explain(): String = firstFault?.let {
        "Training stopped: the network diverged numerically.\n\n" +
            "First fault: $it\n" +
            "Subsequent faults: ${faultCount - 1}\n\n" +
            "The connectome is unusable and was not saved. Erase it and retrain. If this " +
            "recurs, the learning rate is still too high for this dataset."
    } ?: "Healthy."
}
