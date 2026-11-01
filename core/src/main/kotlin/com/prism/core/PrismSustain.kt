package com.prism.core

/**
 * How hard Prism is allowed to work right now, from heat and memory rather than from a setting.
 *
 * ## Why this exists
 *
 * [PrismPower] records the device's power state, and until now nothing read it: the phone was
 * benchmarked, trained, mined and indexed at whatever the caller asked for, whether it was cold on
 * a charger or already throttling at 60 °C. The result is the report this file answers -- the phone
 * gets hot, holds the heat, and everything gets slower anyway because Android has already cut the
 * clocks.
 *
 * Android publishes exactly the signal needed to avoid that:
 *
 *  * `PowerManager.getCurrentThermalStatus()` / `addThermalStatusListener` (API 29) -- the same
 *    scale the platform uses to throttle, from NONE to SHUTDOWN.
 *  * `PowerManager.getThermalHeadroom(seconds)` (API 30) -- an estimate of how close the device is
 *    to SEVERE throttling over the next few seconds. 1.0 means it is there; below ~0.7 there is
 *    room. This is the useful one, because status alone is a lagging indicator: by the time it says
 *    MODERATE the clocks have already been cut.
 *  * `ComponentCallbacks2.onTrimMemory` -- pressure is visible before the process is killed.
 *
 * The strategy is deliberately boring, because a governor that oscillates is worse than none:
 *
 *  * **Trim threads before trimming clocks.** Inference is memory-bandwidth-bound; two fewer
 *    threads cost less throughput than the DVFS drop that Android would otherwise impose, and they
 *    cost it *predictably*.
 *  * **Duty-cycle, do not stop.** A long job that sleeps a fraction of each second finishes; one
 *    that is halted by the platform does not.
 *  * **Drop background work first.** Discovery beacons, crawls and preloads are the cheapest thing
 *    to give up, and nobody is waiting on them.
 *
 * Everything here is a pure function of volatile fields so both platforms share one policy. The
 * Android side ([com.prism.launcher.platform.AndroidThermalGovernor]) is the only writer.
 */
object PrismSustain {

    /**
     * Memory pressure, on the same scale `onTrimMemory` uses:
     * 0 none, 1 moderate (running low), 2 low, 3 critical.
     *
     * Set by the platform's trim callback. 0 is the normal case and the one a desktop always has.
     */
    @Volatile
    var memoryPressure: Int = 0

    /** Free RAM the platform last reported, for decisions and diagnostics; 0 when unknown. */
    @Volatile
    var availableRamBytes: Long = 0L

    /** Total RAM, for the same reason. */
    @Volatile
    var totalRamBytes: Long = 0L

    /** `PowerManager.THERMAL_STATUS_SEVERE`; kept as a number so :core stays free of Android. */
    const val THERMAL_SEVERE: Int = 3

    /** `PowerManager.THERMAL_STATUS_CRITICAL`. */
    const val THERMAL_CRITICAL: Int = 4

    /**
     * Headroom below which the device is treated as "about to throttle".
     *
     * Android's guidance is that 1.0 means SEVERE is imminent and that apps should already be
     * reducing work in the 0.7-1.0 band. 0.7 is chosen rather than 0.9 so an ordinary warm phone
     * (0.75 after a few minutes of decode) is not permanently in the reduced state; the point is to
     * act before the cliff, not at the first degree of warmth.
     */
    const val NEAR_THROTTLE_HEADROOM: Float = 0.7f

    /** Whether the thermal headroom says throttling is close, regardless of the status code. */
    val nearThrottle: Boolean
        get() = PrismPower.thermalHeadroom >= NEAR_THROTTLE_HEADROOM

    /** Whether the device is hot enough that the platform has already started cutting clocks. */
    val throttling: Boolean
        get() = PrismPower.thermalStatus >= PrismPower.THERMAL_MODERATE

    /**
     * How many threads [requested] should become, given heat and memory.
     *
     * The floors matter more than the fractions: one thread on a modern phone runs a small model at
     * roughly a fifth of the tokens per second of four, so collapsing to 1 as soon as the phone is
     * warm would make the governor the bottleneck it exists to avoid. Two threads is the floor
     * until CRITICAL, where the only remaining goal is to finish the current token and stop.
     */
    fun inferenceThreads(requested: Int): Int {
        val wanted = requested.coerceAtLeast(1)
        if (wanted == 1) return 1
        val status = PrismPower.thermalStatus
        val memoryCap = when {
            memoryPressure >= 3 -> 1
            memoryPressure >= 2 -> 2
            else -> wanted
        }
        val thermal = when {
            status >= THERMAL_CRITICAL -> 1
            status >= THERMAL_SEVERE -> 2
            status >= PrismPower.THERMAL_MODERATE -> maxOf(2, wanted / 2)
            // Warm but not throttling: one thread fewer, which is most of the heat saving on a
            // big.LITTLE phone because it removes a whole core from the barrier.
            nearThrottle || status >= 1 -> maxOf(2, wanted - 1)
            else -> wanted
        }
        return minOf(wanted, thermal, memoryCap).coerceAtLeast(1)
    }

    /**
     * A work/rest pattern for a loop that must keep going while the phone cools.
     *
     * Read as "work for [workMillis], then sleep [restMillis]". NONE means no rest at all, which is
     * every cold phone and every desktop.
     */
    data class DutyCycle(val workMillis: Long, val restMillis: Long) {
        val isDutyCycled: Boolean get() = restMillis > 0
        /** Fraction of wall time spent working, 0 when [workMillis] is zero. */
        val workFraction: Float
            get() = if (workMillis + restMillis <= 0) 1f
            else workMillis.toFloat() / (workMillis + restMillis)
    }

    fun dutyCycle(): DutyCycle = when {
        PrismPower.thermalStatus >= THERMAL_CRITICAL -> DutyCycle(600, 1_400)
        PrismPower.thermalStatus >= THERMAL_SEVERE -> DutyCycle(1_000, 700)
        PrismPower.thermalStatus >= PrismPower.THERMAL_MODERATE -> DutyCycle(1_500, 250)
        else -> DutyCycle(1_000, 0)
    }

    /**
     * Milliseconds to sleep between generated tokens.
     *
     * Zero unless the phone is genuinely hot. A pause here is the cheapest way to drop power draw
     * during decode: each token is a full sweep of the weights, so the gaps are exactly where the
     * SoC can fall back to a lower clock without hurting the work already in flight. It is
     * deliberately absent below SEVERE so a warm-but-working phone still produces at full rate.
     */
    fun interTokenPauseMillis(): Long = when {
        PrismPower.thermalStatus >= THERMAL_CRITICAL -> 150L
        PrismPower.thermalStatus >= THERMAL_SEVERE -> 60L
        PrismPower.thermalStatus >= PrismPower.THERMAL_MODERATE -> 15L
        else -> 0L
    }

    /**
     * Whether a background job that spins the CPU for minutes may start.
     *
     * Stricter than [PrismPower.heavyWorkAllowed]: that answers "is this a reasonable time to spend
     * the battery", this answers "is this a reasonable time to make heat". Preloading a 1 GB model
     * on a phone with 150 MB free, which is what the logs showed, is neither.
     */
    fun backgroundWorkAllowed(): Boolean =
        PrismPower.heavyWorkAllowed && !nearThrottle && memoryPressure < 2

    /**
     * Whether the caller should give memory back eagerly rather than waiting to be killed.
     *
     * `TRIM_MEMORY_RUNNING_LOW` and above: the process is in the foreground and Android is telling
     * it that the rest of the system is short. Caches are free to drop and a model that is not in
     * use is the largest cache there is.
     */
    fun shouldTrimCaches(): Boolean = memoryPressure >= 2

    /** A one-line state for logs and the diagnostics page. */
    fun describe(): String {
        val heat = when {
            PrismPower.thermalStatus >= THERMAL_CRITICAL -> "critical"
            PrismPower.thermalStatus >= THERMAL_SEVERE -> "severe"
            PrismPower.thermalStatus >= PrismPower.THERMAL_MODERATE -> "moderate"
            nearThrottle -> "warm"
            else -> "cool"
        }
        val memory = when (memoryPressure) {
            0 -> ""
            1 -> " · memory: moderate"
            2 -> " · memory: low"
            else -> " · memory: critical"
        }
        val headroom = if (PrismPower.thermalHeadroom > 0f) {
            ", headroom ${"%.2f".format(PrismPower.thermalHeadroom)}"
        } else {
            ""
        }
        return "$heat$headroom$memory"
    }
}
