package com.prism.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Electricity, drawn rather than faked.
 *
 * ## Why this is a renderer and not a colour
 *
 * A theme can only put colours and radii on a surface, and Spark asks for something a colour
 * cannot express: a live current that travels an edge and throws arcs off it. So the theme
 * describes the EFFECT ([EDGE_ELECTRIC](com.prism.launcher.theme.EDGE_ELECTRIC)) and this file is
 * the one place that knows how to draw it. Android has the equivalent in `ElectricEdgeDrawable`;
 * both are driven by the same numbers so the two platforms look like the same theme.
 *
 * ## Everything is a pure function of the phase
 *
 * There is no particle state and no per-frame allocation of a list of sparks: a bolt is a polyline
 * whose jitter is a hash of (strike, point), and the phase only decides WHERE the strike is. That
 * is what makes the effect testable and cheap -- one infinite transition per surface, no
 * coroutines, and nothing to leak when a card scrolls away.
 */
@Composable
fun Modifier.electricEdge(
    color: Color,
    cornerRadius: Dp,
    intensity: Float = 1f,
): Modifier {
    val transition = rememberInfiniteTransition(label = "spark-edge")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "spark-phase",
    )
    return drawWithContent {
        drawContent()
        drawElectricBorder(color, cornerRadius.toPx(), phase, intensity)
    }
}

/** The perimeter current: a faint core line, plus bolts that travel and fade at the ends. */
internal fun DrawScope.drawElectricBorder(
    color: Color,
    radius: Float,
    phase: Float,
    intensity: Float,
) {
    if (size.width <= 1f || size.height <= 1f) return
    val r = radius.coerceIn(0f, min(size.width, size.height) / 2f)
    val strength = intensity.coerceIn(0.15f, 1.6f)
    val outline = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
    }
    // The line the current runs along. Always present, so the edge reads as a border between
    // strikes rather than disappearing and reappearing.
    drawPath(outline, color.copy(alpha = 0.26f * strength), style = Stroke(1f * density))
    drawPath(outline, color.copy(alpha = 0.10f * strength), style = Stroke(2.4f * density))

    val corners = listOf(
        Offset(r, 0f) to Offset(size.width - r, 0f),
        Offset(size.width, r) to Offset(size.width, size.height - r),
        Offset(size.width - r, size.height) to Offset(r, size.height),
        Offset(0f, size.height - r) to Offset(0f, r),
    )
    val strikes = (2 + (strength * 3f).roundToInt()).coerceIn(2, 6)
    corners.forEachIndexed { edge, (from, to) ->
        for (strike in 0 until strikes) {
            // Deterministic per (edge, strike): the same bolt shape every time its window comes
            // round, which is what makes it look like one current rather than noise.
            val seed = edge * 977 + strike * 131 + 17
            val drift = unit(seed)
            val travel = ((phase + drift) % 1f)
            val span = 0.10f + 0.14f * unit(seed + 3)
            val start = (travel - span / 2f).coerceIn(0f, 1f)
            val end = (travel + span / 2f).coerceIn(0f, 1f)
            if (end - start < 0.02f) continue
            // A strike fades in and out as it crosses the edge, so arcs never pile up at a corner.
            val fade = (1f - abs(travel - 0.5f) * 2f).coerceIn(0f, 1f)
            if (fade <= 0.02f) continue
            val a = Offset(
                from.x + (to.x - from.x) * start,
                from.y + (to.y - from.y) * start,
            )
            val b = Offset(
                from.x + (to.x - from.x) * end,
                from.y + (to.y - from.y) * end,
            )
            bolt(a, b, seed, fade * strength, color)
        }
    }
}

/** One jagged discharge between two points, with a soft halo under the core. */
internal fun DrawScope.bolt(
    from: Offset,
    to: Offset,
    seed: Int,
    strength: Float,
    color: Color,
) {
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length < 2f) return
    val normal = Offset(-(to.y - from.y) / length, (to.x - from.x) / length)
    val reach = (2.2f + min(5f, length * 0.045f)) * density * (0.6f + strength)
    val points = 6
    val path = Path()
    for (i in 0 until points) {
        val t = i.toFloat() / (points - 1)
        // Ends are pinned to the edge; only the middle wanders.
        val offset = if (i == 0 || i == points - 1) 0f else signed(seed + i * 31) * reach
        val point = Offset(
            from.x + (to.x - from.x) * t + normal.x * offset,
            from.y + (to.y - from.y) * t + normal.y * offset,
        )
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    val alpha = (0.35f + 0.5f * strength).coerceIn(0f, 0.95f)
    drawPath(path, color.copy(alpha = alpha * 0.30f), style = Stroke(4.2f * density))
    drawPath(path, color.copy(alpha = alpha), style = Stroke(1.25f * density))
    // The leading tip is brighter than the tail, the way a discharge actually reads.
    drawCircle(color.copy(alpha = alpha * 0.85f), radius = 1.9f * density, center = to)
}

/** A stable number in 0..1 for [seed]. */
private fun unit(seed: Int): Float {
    var x = seed * 1664525 + 1013904223
    x = x xor (x ushr 13)
    x *= 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFF) / 65535f
}

/** A stable number in -1..1 for [seed]. */
private fun signed(seed: Int): Float = unit(seed) * 2f - 1f

/**
 * Arcs between app icons, weighted by how often each one is launched.
 *
 * The strongest node is the one the user actually uses: it gets the most bolts and the longest
 * reach, and it also links to its nearest neighbours, so the current flows outward from the apps
 * that matter instead of flickering uniformly across the page. Only pairs where at least one end
 * is charged are drawn, which keeps a desktop with twenty icons from looking like static.
 */
internal fun DrawScope.drawSparks(
    centres: Map<Int, Offset>,
    weights: Map<Int, Float>,
    phase: Float,
    color: Color,
) {
    if (centres.size < 2) return
    val nodeIds = centres.keys.sorted()
    val beat = (phase * 7f).toInt()
    nodeIds.forEach { id ->
        val from = centres[id] ?: return@forEach
        val weight = weights[id] ?: return@forEach
        // Nearest neighbours first: an arc that crosses the whole grid reads as a random line, not
        // as current passing between two adjacent things.
        val nearest = nodeIds
            .filter { it != id }
            .map { it to (centres[it]?.let { p -> hypot(p.x - from.x, p.y - from.y) } ?: Float.MAX_VALUE) }
            .sortedBy { it.second }
            .take(if (weight > 0.6f) 2 else 1)
        nearest.forEachIndexed { slot, (targetId, _) ->
            val to = centres[targetId] ?: return@forEachIndexed
            val seed = beat * 7717 + id * 131 + slot * 37 + targetId
            // Flicker: a bolt is visible in most frames but not all, which is what separates a
            // current from a static line.
            if (unit(seed) > 0.55f + weight * 0.35f) return@forEachIndexed
            val flicker = 0.55f + 0.45f * unit(seed + 11)
            bolt(from, to, seed, weight * flicker, color)
        }
    }
}

/**
 * Fusion's ground: a deep-space wash with a soft core and a starfield.
 *
 * Procedural on purpose. A bitmap would have to ship at several densities, would band on a large
 * monitor, and would be one more asset that can go missing; four radial gradients and a fixed
 * star field cost one cached brush set per size and look the same at any resolution.
 */
@Composable
fun Modifier.nebulaBackdrop(dark: Boolean = true): Modifier = drawWithCache {
    val ground = if (dark) Color(0xFF05060F) else Color(0xFFF2F5FF)
    val violet = if (dark) Color(0xFF6B3FA0) else Color(0xFFB9A7FF)
    val cyan = if (dark) Color(0xFF1FA9D9) else Color(0xFF9BD8FF)
    val magenta = if (dark) Color(0xFF8A3FA8) else Color(0xFFD9B8FF)
    val amber = if (dark) Color(0xFFB06B2A) else Color(0xFFF3C98A)
    val star = if (dark) Color(0xFFEAF6FF) else Color(0xFFFFFFFF)

    val brushes = listOf(
        Brush.radialGradient(
            listOf(violet.copy(alpha = if (dark) 0.55f else 0.30f), Color.Transparent),
            radius = size.maxDimension * 0.62f,
        ) to Offset(size.width * 0.22f, size.height * 0.10f),
        Brush.radialGradient(
            listOf(cyan.copy(alpha = if (dark) 0.34f else 0.22f), Color.Transparent),
            radius = size.maxDimension * 0.55f,
        ) to Offset(size.width * 0.84f, size.height * 0.24f),
        Brush.radialGradient(
            listOf(magenta.copy(alpha = if (dark) 0.30f else 0.18f), Color.Transparent),
            radius = size.maxDimension * 0.50f,
        ) to Offset(size.width * 0.60f, size.height * 0.46f),
        Brush.radialGradient(
            listOf(amber.copy(alpha = if (dark) 0.20f else 0.16f), Color.Transparent),
            radius = size.maxDimension * 0.38f,
        ) to Offset(size.width * 0.12f, size.height * 0.86f),
    )
    // A fixed field: the stars must not twinkle between frames, or a static page would look like
    // it was boiling.
    val stars = List(if (dark) 110 else 0) { index ->
        val x = unit(index * 7919 + 13) * size.width
        val y = unit(index * 104729 + 7) * size.height
        val r = 0.5f + unit(index * 31 + 5) * 1.3f
        Offset(x, y) to r * density
    }

    onDrawBehind {
        drawRect(ground)
        brushes.forEach { (brush, centre) ->
            drawCircle(
                brush = brush,
                radius = size.maxDimension * 0.7f,
                center = centre,
                alpha = 1f,
            )
        }
        stars.forEachIndexed { index, (point, radius) ->
            drawCircle(star.copy(alpha = 0.30f + unit(index * 17) * 0.55f), radius, point)
        }
        // The horizon: a faint bright seam across the lower third, so the page has a ground plane
        // rather than floating in a void. This is what the reference's lower silhouette reads as.
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, cyan.copy(alpha = if (dark) 0.10f else 0.06f), Color.Transparent),
                startY = size.height * 0.38f,
                endY = size.height * 0.72f,
            ),
            topLeft = Offset(0f, size.height * 0.38f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f),
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, if (dark) Color(0xFF02030A) else Color.White.copy(alpha = 0.6f)),
                startY = size.height * 0.62f,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height * 0.62f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.38f),
        )
    }
}
