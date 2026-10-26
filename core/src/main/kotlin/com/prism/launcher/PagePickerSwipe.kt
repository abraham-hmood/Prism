package com.prism.launcher

import kotlin.math.abs

/**
 * The gesture that opens the page picker.
 *
 * ## Why the user defines it
 *
 * The built-in trigger has always been a vertical overscroll, which is invisible: a page that can
 * scroll simply bounces, and a page that cannot is the only place the gesture works. Onboarding used
 * to teach a long-press that did not exist. Rather than pick another hidden gesture and hope, the
 * setup asks the user to perform their own swipe three times and stores the direction and a
 * distance derived from what they actually did.
 *
 * ## How a captured gesture is judged
 *
 * Direction is the dominant axis and its sign. Distances are recorded in dp so the same trigger
 * works across densities. Horizontal triggers are edge-only: a horizontal swipe in the middle of a
 * page belongs to the pager, and stealing it would break page switching. Vertical triggers still
 * respect scrolling -- they fire only when the page cannot scroll further in that direction.
 */
data class PagePickerSwipe(
    val direction: Direction,
    /** Minimum travel, in dp, for the gesture to count. */
    val distanceDp: Int,
    /** Horizontal triggers must start near the edge they swipe from. */
    val edgeOnly: Boolean = true,
) {

    enum class Direction {
        UP, DOWN, LEFT, RIGHT;

        val label: String
            get() = when (this) {
                UP -> "Swipe up"
                DOWN -> "Swipe down"
                LEFT -> "Swipe left"
                RIGHT -> "Swipe right"
            }

        val isHorizontal: Boolean get() = this == LEFT || this == RIGHT
    }

    /**
     * True when [dx]/[dy] (pixels) are far enough and in the configured direction.
     *
     * [distanceScale] stretches the threshold without changing the direction the user trained:
     * the global sensitivity setting multiplies it, so a slower, deliberately-difficult default
     * can share one matching rule with a trigger captured by the onboarding.
     */
    fun matches(dx: Float, dy: Float, density: Float, distanceScale: Float = 1f): Boolean {
        val threshold = distanceDp * density * distanceScale.coerceAtLeast(1f)
        return when (direction) {
            Direction.UP -> dy <= -threshold && abs(dy) > abs(dx)
            Direction.DOWN -> dy >= threshold && abs(dy) > abs(dx)
            Direction.LEFT -> dx <= -threshold && abs(dx) > abs(dy)
            Direction.RIGHT -> dx >= threshold && abs(dx) > abs(dy)
        }
    }

    /**
     * Whether a gesture that started at [startX] may use this trigger.
     *
     * Only horizontal triggers are restricted. A horizontal swipe from the middle of the screen is
     * how pages are changed, so a page-picker trigger that claimed it would make the pager feel
     * broken. The edge test uses the side the finger moves *from*.
     */
    fun isEligibleStart(startX: Float, width: Int, density: Float): Boolean {
        if (!edgeOnly || !direction.isHorizontal) return true
        val edge = EDGE_WIDTH_DP * density
        return when (direction) {
            Direction.LEFT -> startX >= width - edge
            Direction.RIGHT -> startX <= edge
            else -> true
        }
    }

    fun toJson(): String = "${direction.name}|$distanceDp|$edgeOnly"

    companion object {
        const val EDGE_WIDTH_DP = 32f

        /**
         * The built-in swipe threshold multiplier for a slider value of [percent].
         *
         * 100 is the original sensitivity -- the distance captured by onboarding or the old
         * overscroll -- and lower values make the gesture progressively harder to trigger. The
         * default of 25 is more than twice the travel, because the old gesture was easy to fire
         * while reading a page that could not scroll.
         */
        fun sensitivityScale(percent: Int): Float =
            1f + (100 - percent.coerceIn(0, 100)) / 100f * 1.5f

        fun deserialize(raw: String?): PagePickerSwipe? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split("|")
            if (parts.size < 2) return null
            val direction = runCatching { Direction.valueOf(parts[0]) }.getOrNull() ?: return null
            val distance = parts[1].toIntOrNull()?.coerceIn(24, 240) ?: return null
            val edgeOnly = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: true
            return PagePickerSwipe(direction, distance, edgeOnly)
        }

        /**
         * Builds a trigger from three swipes, or null when they do not agree.
         *
         * Agreement is checked on direction first, because that is the part a user means to
         * specify. Distance is allowed to vary by half again either way -- nobody swipes the same
         * length twice, and rejecting an honest attempt is worse than accepting a slightly
         * conservative threshold. The threshold is set to [SAFETY_FRACTION] of the shortest of the
         * three, so reproducing the gesture is easier than the sample that defined it.
         */
        fun fromSamples(samples: List<SwipeSample>, density: Float): PagePickerSwipe? {
            if (samples.size < 3 || density <= 0f) return null
            val directions = samples.map { it.direction() }
            if (directions.any { it == null }) return null
            val direction = directions.first() ?: return null
            if (directions.any { it != direction }) return null

            // Dominant axis, not the diagonal length: matching compares the axis the gesture is
            // defined on, and adding both would make a perfectly ordinary swipe need to travel
            // further than the sample it was derived from.
            val distances = samples.map { maxOf(abs(it.dx), abs(it.dy)) / density }
            val shortest = distances.minOrNull() ?: return null
            val longest = distances.maxOrNull() ?: return null
            if (shortest < MIN_SWIPE_DP) return null
            // A 3x spread means the user was doing something different each time.
            if (longest > shortest * 3f) return null

            val threshold = (shortest * SAFETY_FRACTION).toInt().coerceIn(24, 200)
            return PagePickerSwipe(direction, threshold, edgeOnly = direction.isHorizontal)
        }

        private const val SAFETY_FRACTION = 0.72f
        private const val MIN_SWIPE_DP = 28f
    }

    data class SwipeSample(val dx: Float, val dy: Float) {
        fun direction(): Direction? {
            if (abs(dx) < 1f && abs(dy) < 1f) return null
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) Direction.LEFT else Direction.RIGHT
            } else {
                if (dy < 0) Direction.UP else Direction.DOWN
            }
        }
    }
}
