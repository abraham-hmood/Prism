package com.prism.launcher

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

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
 * ## Not only four directions
 *
 * The first version reduced every swipe to the dominant axis, so a diagonal or a circle became an
 * ordinary up/down/left/right. That is not what the user drew. A captured gesture now keeps a
 * normalised path, which lets a diagonal, a loop, a triangle or any other doodle be the trigger.
 * The path is compared after translation and uniform scaling, so it works anywhere on the screen and
 * at any comfortable size.
 *
 * ## Pressure is half of the gesture
 *
 * A path alone is easy to draw by accident while scrolling, so the capture also records the average
 * pressure the user applied. Triggering requires both the path and at least most of that pressure.
 * The tolerance is generous because pressure sensors report very differently between devices; a
 * device that reports a constant 1.0 behaves exactly as it always did.
 *
 * ## How a captured gesture is judged
 *
 * Direction is still the dominant axis and its sign, which is what the labels show. Distances are
 * recorded in dp so the same trigger works across densities. Horizontal straight swipes remain
 * edge-only: a horizontal swipe in the middle of a page belongs to the pager, and stealing it would
 * break page switching. A path captured by the setup is the user's own gesture, so it is not
 * edge-only and does not wait for a scroll edge -- a diagonal or loop is often drawn in the middle
 * of a scrollable page. Old triggers with no path keep the scrolling rule.
 */
data class PagePickerSwipe(
    val direction: Direction,
    /** Minimum travel, in dp, for the gesture to count. */
    val distanceDp: Int,
    /** Horizontal straight triggers must start near the edge they swipe from. */
    val edgeOnly: Boolean = true,
    /**
     * The captured path, normalised so the first point is at the origin and the furthest point is
     * one unit away. Empty for triggers captured by older builds and for plain straight swipes.
     */
    val path: List<TracePoint> = emptyList(),
    /** The average pressure measured while the gesture was recorded, or null for old triggers. */
    val pressure: Float? = null,
    /** What sort of gesture this is, which decides how it is matched and labelled. */
    val shape: Shape = Shape.STRAIGHT,
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

    enum class Shape { STRAIGHT, DIAGONAL, CUSTOM }

    /** One sample along a captured path, in pixels relative to where the gesture began. */
    data class TracePoint(val x: Float, val y: Float)

    /** A human label that says what the trigger actually is. */
    val label: String
        get() = when (shape) {
            Shape.CUSTOM -> "Custom gesture"
            Shape.DIAGONAL -> "Swipe ${diagonalWords()}"
            Shape.STRAIGHT -> direction.label
        }

    /** The one-line description Settings shows under the trigger's name. */
    val detail: String
        get() = buildString {
            append("$distanceDp dp or more")
            pressure?.let { append(" · ${(it * 100).roundToInt()}% pressure") }
            if (shape == Shape.CUSTOM) append(" · drawn path")
        }

    private fun diagonalWords(): String {
        val last = path.lastOrNull() ?: return "diagonally"
        return buildString {
            append(if (last.y < 0f) "up" else "down")
            if (last.x < -0.18f) append("-left") else if (last.x > 0.18f) append("-right")
        }
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
        if (shape == Shape.DIAGONAL) {
            // A diagonal is defined on both axes. Matching only the dominant one would let a
            // mostly-horizontal swipe fire a down-right trigger. The secondary sign is not in
            // [direction], so only the axis magnitudes and the dominant sign are checked here;
            // a captured diagonal is normally matched by its whole path on release.
            val axis = threshold * 0.62f
            if (abs(dx) < axis || abs(dy) < axis) return false
            return when (direction) {
                Direction.UP -> dy <= -axis
                Direction.DOWN -> dy >= axis
                Direction.LEFT -> dx <= -axis
                Direction.RIGHT -> dx >= axis
            }
        }
        return when (direction) {
            Direction.UP -> dy <= -threshold && abs(dy) > abs(dx)
            Direction.DOWN -> dy >= threshold && abs(dy) > abs(dx)
            Direction.LEFT -> dx <= -threshold && abs(dx) > abs(dy)
            Direction.RIGHT -> dx >= threshold && abs(dx) > abs(dy)
        }
    }

    /**
     * Matches a whole drawn path against the captured shape.
     *
     * Only meaningful for [Shape.CUSTOM]; straight and diagonal gestures are matched by [matches]
     * while the finger is moving. The path comparison is deliberately tolerant: a hand-drawn circle
     * is not a parametric circle, and demanding that would make the user's own gesture fail.
     */
    fun matchesTrace(
        trace: List<TracePoint>,
        averagePressure: Float?,
        density: Float,
        distanceScale: Float = 1f,
    ): Boolean {
        if (trace.size < 3 || density <= 0f) return false
        if (!pressureAccepted(averagePressure)) return false

        val spanDp = traceSpan(trace) / density
        val sizeScale = 0.55f + (distanceScale.coerceAtLeast(1f) - 1f) * 0.15f
        if (spanDp < distanceDp * sizeScale) return false

        if (path.size < 3) {
            val dx = trace.last().x - trace.first().x
            val dy = trace.last().y - trace.first().y
            return matches(dx, dy, density, distanceScale)
        }
        val actual = normalise(trace) ?: return false
        return pathDistance(path, actual) <= MATCH_TOLERANCE
    }

    /**
     * Whether the measured pressure is close enough to what was recorded.
     *
     * Old triggers carry no pressure, so they keep working exactly as before. A sensor that
     * reports zeroes is treated the same way: requiring a measurement the hardware cannot provide
     * would make the trigger impossible.
     */
    fun pressureAccepted(averagePressure: Float?): Boolean {
        val required = pressure ?: return true
        if (required <= 0.01f) return true
        val measured = averagePressure ?: return false
        if (measured <= 0.01f) return true
        return measured >= required * PRESSURE_TOLERANCE
    }

    /**
     * Whether a gesture that started at [startX] may use this trigger.
     *
     * Only horizontal straight triggers are restricted. A horizontal swipe from the middle of the
     * screen is how pages are changed, so a page-picker trigger that claimed it would make the
     * pager feel broken. The edge test uses the side the finger moves *from*.
     */
    fun isEligibleStart(startX: Float, width: Int, density: Float): Boolean {
        if (!edgeOnly || !direction.isHorizontal || shape != Shape.STRAIGHT) return true
        val edge = EDGE_WIDTH_DP * density
        return when (direction) {
            Direction.LEFT -> startX >= width - edge
            Direction.RIGHT -> startX <= edge
            else -> true
        }
    }

    fun toJson(): String {
        val encodedPressure = pressure?.let { String.format(Locale.US, "%.3f", it) } ?: "-"
        val encodedPath = if (path.isEmpty()) {
            "-"
        } else {
            path.joinToString(";") { point ->
                String.format(Locale.US, "%.3f,%.3f", point.x, point.y)
            }
        }
        return "v2|${direction.name}|$distanceDp|$edgeOnly|$encodedPressure|${shape.name}|$encodedPath"
    }

    companion object {
        const val EDGE_WIDTH_DP = 32f

        /** How much of the recorded pressure the repeat must supply. */
        private const val PRESSURE_TOLERANCE = 0.72f

        /** Maximum average distance between two normalised paths, in path units. */
        private const val MATCH_TOLERANCE = 0.30f

        private const val PATH_SAMPLES = 16

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
            if (parts.firstOrNull() == "v2" && parts.size >= 7) {
                return deserializeV2(parts)
            }
            if (parts.size < 2) return null
            val direction = runCatching { Direction.valueOf(parts[0]) }.getOrNull() ?: return null
            val distance = parts[1].toIntOrNull()?.coerceIn(20, 260) ?: return null
            val edgeOnly = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: true
            return PagePickerSwipe(direction, distance, edgeOnly)
        }

        private fun deserializeV2(parts: List<String>): PagePickerSwipe? {
            val direction = runCatching { Direction.valueOf(parts[1]) }.getOrNull() ?: return null
            val distance = parts[2].toIntOrNull()?.coerceIn(20, 260) ?: return null
            val edgeOnly = parts[3].toBooleanStrictOrNull() ?: true
            val pressure = if (parts[4] == "-") {
                null
            } else {
                parts[4].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    ?: return null
            }
            val shape = runCatching { Shape.valueOf(parts[5]) }.getOrNull() ?: Shape.STRAIGHT
            val path = if (parts[6] == "-") {
                emptyList()
            } else {
                parts[6].split(";").mapNotNull { pair ->
                    val comma = pair.indexOf(',')
                    if (comma <= 0) return@mapNotNull null
                    val x = pair.substring(0, comma).toFloatOrNull() ?: return@mapNotNull null
                    val y = pair.substring(comma + 1).toFloatOrNull() ?: return@mapNotNull null
                    if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                    TracePoint(x.coerceIn(-3f, 3f), y.coerceIn(-3f, 3f))
                }.take(64)
            }
            if (shape == Shape.CUSTOM && path.size < 3) return null
            return PagePickerSwipe(direction, distance, edgeOnly, path, pressure, shape)
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
            val distances = samples.map { traceSpan(it.trace.ifEmpty {
                listOf(TracePoint(0f, 0f), TracePoint(it.dx, it.dy))
            }) / density }
            val shortest = distances.minOrNull() ?: return null
            val longest = distances.maxOrNull() ?: return null
            if (shortest < MIN_SWIPE_DP) return null
            // A 3x spread means the user was doing something different each time.
            if (longest > shortest * 3f) return null

            val threshold = (shortest * SAFETY_FRACTION).toInt().coerceIn(20, 220)
            val pressure = averagePressure(samples)
            // A trigger made from bare dx/dy samples has no path to match, so it stays a straight
            // directional swipe. New captures always carry their trace and get the richer shape
            // classification in [fromTraceSamples].
            return PagePickerSwipe(
                direction = direction,
                distanceDp = threshold,
                edgeOnly = direction.isHorizontal,
                pressure = pressure,
                shape = Shape.STRAIGHT,
            )
        }

        /**
         * Builds a trigger from three captured paths, or null when they are not recognisably the
         * same gesture.
         *
         * The path is compared after normalisation, so the same doodle at a different size or in a
         * different corner still counts. The threshold uses the shortest span so the trigger is
         * slightly easier to reproduce than the sample that defined it.
         */
        fun fromTraceSamples(samples: List<SwipeSample>, density: Float): PagePickerSwipe? {
            if (samples.size < 3 || density <= 0f) return null
            val traces = samples.map { it.trace }
            if (traces.any { it.size < 3 }) return fromSamples(samples, density)

            val normalised = traces.mapNotNull { normalise(it) }
            if (normalised.size != traces.size) return null

            val base = normalised.first()
            val averagePath = (0 until PATH_SAMPLES).map { index ->
                TracePoint(
                    normalised.map { it[index].x }.average().toFloat(),
                    normalised.map { it[index].y }.average().toFloat(),
                )
            }
            val canonical = normalise(averagePath) ?: return null

            // Three drawings that are not the same shape should not produce a trigger. The
            // threshold is looser than matching, because capture is where the user is still
            // deciding what the gesture is.
            val consistency = normalised.map { pathDistance(canonical, it) }.average().toFloat()
            if (consistency > 0.38f) return null

            val distances = traces.map { traceSpan(it) / density }
            val shortest = distances.minOrNull() ?: return null
            val longest = distances.maxOrNull() ?: return null
            if (shortest < MIN_SWIPE_DP) return null
            if (longest > shortest * 3f) return null

            val first = samples.first()
            val dx = first.trace.last().x - first.trace.first().x
            val dy = first.trace.last().y - first.trace.first().y
            // A loop ends where it began, so its net displacement says nothing about direction.
            // The furthest point from the start is a stable stand-in for which way it was drawn.
            val farthest = first.trace.maxByOrNull { point ->
                hypot(point.x - first.trace.first().x, point.y - first.trace.first().y)
            } ?: return null
            val direction = SwipeSample(
                farthest.x - first.trace.first().x,
                farthest.y - first.trace.first().y,
            ).direction() ?: return null
            val shape = classify(canonical, dx, dy)

            val threshold = (shortest * SAFETY_FRACTION).toInt().coerceIn(20, 220)
            return PagePickerSwipe(
                direction = direction,
                distanceDp = threshold,
                edgeOnly = shape == Shape.STRAIGHT && direction.isHorizontal,
                path = canonical,
                pressure = averagePressure(samples),
                shape = shape,
            )
        }

        private fun averagePressure(samples: List<SwipeSample>): Float? {
            val measured = samples.map { it.pressure }.filter { it > 0.01f }
            if (measured.isEmpty()) return null
            return measured.average().toFloat().coerceIn(0f, 1f)
        }

        private fun classify(path: List<TracePoint>, dx: Float, dy: Float): Shape {
            if (path.size < 3) return Shape.STRAIGHT
            val end = path.last()
            val span = max(abs(dx), abs(dy))
            if (span <= 0.01f) return Shape.CUSTOM
            val deviation = maxLineDeviation(path)
            if (deviation > 0.22f) return Shape.CUSTOM
            if (abs(dx) > span * 0.55f && abs(dy) > span * 0.55f) return Shape.DIAGONAL
            return Shape.STRAIGHT
        }

        private fun maxLineDeviation(path: List<TracePoint>): Float {
            val start = path.first()
            val end = path.last()
            val vx = end.x - start.x
            val vy = end.y - start.y
            val len = hypot(vx, vy)
            if (len <= 1e-4f) return 1f
            return path.maxOfOrNull { point ->
                abs((point.x - start.x) * vy - (point.y - start.y) * vx) / len
            } ?: 0f
        }

        /**
         * Converts a pixel trace to a translation- and scale-independent path.
         *
         * THE SHIFT AND SCALE HAPPEN BEFORE RESAMPLING. The resampler's duplicate filter is in
         * the units of its input; applying a pixel threshold to an already-normalised path would
         * throw away almost every point of a small [averagePath] and produce a completely
         * different shape from the one the user drew.
         */
        internal fun normalise(trace: List<TracePoint>): List<TracePoint>? {
            if (trace.size < 2) return null
            val origin = trace.first()
            val shifted = trace.map { TracePoint(it.x - origin.x, it.y - origin.y) }
            val scale = shifted.maxOfOrNull { hypot(it.x, it.y) } ?: 0f
            if (scale <= 1e-3f) return null
            return resample(shifted.map { TracePoint(it.x / scale, it.y / scale) }, PATH_SAMPLES)
        }

        /** Evenly spaced points by arc length, so timing and touch sample rate do not matter. */
        private fun resample(trace: List<TracePoint>, count: Int): List<TracePoint>? {
            val points = mutableListOf<TracePoint>()
            for (point in trace) {
                val previous = points.lastOrNull()
                if (previous == null ||
                    hypot(point.x - previous.x, point.y - previous.y) > 0.0001f
                ) {
                    points += point
                }
            }
            if (points.size < 2) return null
            if (points.size == count) return points

            val cumulative = DoubleArray(points.size)
            for (index in 1 until points.size) {
                cumulative[index] = cumulative[index - 1] +
                    hypot(
                        (points[index].x - points[index - 1].x).toDouble(),
                        (points[index].y - points[index - 1].y).toDouble(),
                    )
            }
            val total = cumulative.last()
            if (total <= 1e-3) return null

            return (0 until count).map { targetIndex ->
                val target = total * targetIndex / (count - 1)
                var segment = 1
                while (segment < cumulative.size && cumulative[segment] < target) segment++
                if (segment >= cumulative.size) return@map points.last()
                val before = cumulative[segment - 1]
                val span = cumulative[segment] - before
                val fraction = if (span <= 1e-9) 0.0 else (target - before) / span
                TracePoint(
                    (points[segment - 1].x + (points[segment].x - points[segment - 1].x) * fraction).toFloat(),
                    (points[segment - 1].y + (points[segment].y - points[segment - 1].y) * fraction).toFloat(),
                )
            }
        }

        private fun traceSpan(trace: List<TracePoint>): Float {
            if (trace.isEmpty()) return 0f
            val first = trace.first()
            return trace.maxOfOrNull { hypot(it.x - first.x, it.y - first.y) } ?: 0f
        }

        private fun pathDistance(a: List<TracePoint>, b: List<TracePoint>): Float {
            if (a.size != b.size || a.isEmpty()) return 1f
            return a.indices.map { hypot(a[it].x - b[it].x, a[it].y - b[it].y) }.average().toFloat()
        }

        private const val SAFETY_FRACTION = 0.72f
        private const val MIN_SWIPE_DP = 28f
    }

    data class SwipeSample(
        val dx: Float,
        val dy: Float,
        val pressure: Float = 1f,
        /** Points relative to the start of the gesture, in pixels. */
        val trace: List<TracePoint> = emptyList(),
    ) {
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
