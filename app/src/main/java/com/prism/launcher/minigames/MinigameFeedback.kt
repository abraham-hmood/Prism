package com.prism.launcher.minigames

/**
 * A game that can tell the page how a run is going.
 *
 * The page owns the music and the win/lose animation because those are shared across every
 * cabinet, but only the game knows whether it is heating up or already finished. This is the small
 * contract between the two: an intensity for the soundtrack's tempo, and a result once there is one.
 */
interface MinigameFeedback {
    /** 0..1. Drives tempo and how many layers the music uses. */
    fun musicIntensity(): Float = 0.3f

    /** True when the player won, false when they lost, null while the run is still going. */
    fun outcome(): Boolean? = null
}
