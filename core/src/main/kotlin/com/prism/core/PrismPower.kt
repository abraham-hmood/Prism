package com.prism.core

/**
 * What the device's power state is right now, for work that must not run at full tilt.
 *
 * ## Why this exists
 *
 * Prism keeps several loops alive for the life of the process -- mesh discovery, the display
 * beacon, social announcements, the search crawler -- and each of them was tuned against a phone
 * on a charger. On battery, and especially on a phone that is already warm, the same intervals and
 * the same redraw rates are the difference between "a launcher that is running" and "a phone that
 * gets hot and loses charge while it is plugged in".
 *
 * The state is set by the platform (Android installs a battery, power-save and thermal listener;
 * desktop leaves the defaults), and read by :core's loops so the platform does not have to reach
 * into each one. Everything is volatile and lock-free: these are single booleans read once per
 * round, never a snapshot that has to be consistent with itself.
 */
object PrismPower {

    /** Whether the screen is on. A sleeping phone should not be transmitting discovery beacons. */
    @Volatile
    var screenOn: Boolean = true

    /**
     * Whether the device is plugged in.
     *
     * Charging is the one state that makes expensive background work acceptable: a crawl, a
     * training pass, or a full-rate animation costs the user nothing when the charger is carrying
     * it. It is also the state that made the phone hot in the first place, which is why
     * [heavyWorkAllowed] also asks about heat.
     */
    @Volatile
    var charging: Boolean = true

    /** The system's battery saver, which the user has explicitly asked the whole device to respect. */
    @Volatile
    var powerSave: Boolean = false

    /**
     * The platform's thermal status, using Android's own scale:
     * 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown.
     *
     * The numbers are copied as plain integers rather than importing `PowerManager`, so :core
     * stays free of Android classes and the desktop (which never sets this) reads 0.
     */
    @Volatile
    var thermalStatus: Int = 0

    /**
     * `PowerManager.getThermalHeadroom(10)`: how close the device is to SEVERE throttling over the
     * next few seconds, where 1.0 means it is there. 0 when the platform cannot report it.
     *
     * This is the leading indicator the status code is not: by the time the status reads MODERATE
     * the clocks have already been cut. [PrismSustain] uses it to shed work before the cliff.
     */
    @Volatile
    var thermalHeadroom: Float = 0f

    /** Warm enough that adding load is how a phone starts dropping frames and throttling. */
    val hot: Boolean
        get() = thermalStatus >= THERMAL_MODERATE

    /** On battery, or throttled by the user's own battery saver. */
    val conserving: Boolean
        get() = powerSave || !charging

    /**
     * Whether a background job that spins the CPU for minutes may start right now.
     *
     * Charging, not thermally throttled, and not in battery saver. A throttled phone on a charger
     * is exactly the case the user reported: plugged in, hot, and losing charge anyway.
     */
    val heavyWorkAllowed: Boolean
        get() = charging && !hot && !powerSave

    /** Android's `THERMAL_STATUS_MODERATE`; kept here so callers do not import PowerManager. */
    const val THERMAL_MODERATE: Int = 2

    /** For diagnostics and the mesh page. */
    fun summary(): String {
        val power = when {
            charging && powerSave -> "charging, battery saver"
            charging -> "charging"
            powerSave -> "on battery, battery saver"
            else -> "on battery"
        }
        val heat = when (thermalStatus) {
            0 -> "cool"
            1 -> "warm"
            2 -> "throttling"
            3 -> "hot"
            4 -> "very hot"
            else -> "critical"
        }
        return "$power · $heat" + if (screenOn) "" else " · screen off"
    }
}
