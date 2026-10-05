package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.nora.NoraTelemetry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Nora's cortex, lighting up as she works. PHASE 43.
 *
 * ## Why this reads telemetry rather than the brain
 *
 * [NoraTelemetry] is a set of flat float arrays the brain writes into and a renderer reads out with
 * [NoraTelemetry.readActivity]. That indirection is what makes this safe to draw at 60 Hz: the brain is
 * mutating large tensors on a worker thread throughout a generation, and a view that reached into them
 * would be reading half-updated state — or holding a lock the generator needs.
 *
 * It also means the visualizer costs nothing when nothing is generating. [NoraTelemetry.live] is false
 * outside a run, and the animation loop stops rather than repainting a static diagram forever.
 *
 * ## Why the layout is hand-placed
 *
 * The regions are a known, fixed anatomy — a ventral stream along the bottom and a dorsal stream above it
 * — and that arrangement is the thing a reader learns from. A force-directed or generic graph layout would
 * put them somewhere different every run and destroy the one property that makes the picture legible.
 *
 * ## Why activity is auto-scaled
 *
 * The raw units differ by orders of magnitude between regions: the retina carries luminance and IT carries
 * an abstract code. Drawing them on one absolute scale means the retina is always saturated and IT never
 * lights at all. The scale is per-frame across all regions, so what the picture shows is where activity IS
 * relative to everywhere else right now — which is the question being asked.
 */
@Composable
fun NoraBrainVisualizer(modifier: Modifier = Modifier) {
    val colors = LocalPrismColors.current
    val measurer = rememberTextMeasurer()

    val activity = remember { FloatArray(NoraTelemetry.Region.entries.size) }
    val ascending = remember { FloatArray(NoraTelemetry.Pathway.entries.size) }
    val descending = remember { FloatArray(NoraTelemetry.Pathway.entries.size) }

    // A revision counter rather than the arrays themselves: Compose compares state by equality and a
    // FloatArray that was mutated in place is still equal to itself, so the canvas would never redraw.
    var revision by remember { mutableStateOf(0L) }
    var phase by remember { mutableStateOf("idle") }

    LaunchedEffect(Unit) {
        while (true) {
            // Paced to the frame clock rather than a fixed delay, so this cannot outrun the compositor
            // and cannot busy-wait when the window is not being drawn at all.
            withFrameNanos { }
            NoraTelemetry.readActivity(activity)
            NoraTelemetry.readAscending(ascending)
            NoraTelemetry.readDescending(descending)
            phase = NoraTelemetry.phase
            revision = NoraTelemetry.sequence
        }
    }

    Box(modifier.fillMaxWidth().height(260.dp)) {
        Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            // Read so Compose treats this draw as depending on it. Without the read the canvas is
            // considered constant and is never invalidated.
            @Suppress("UNUSED_EXPRESSION") revision

            val nodes = layout(size.width, size.height)
            val peak = max(activity.max(), 1e-6f)

            drawPathways(nodes, ascending, descending, colors.accent)
            drawRegions(nodes, activity, peak, colors.accent, colors.faint, measurer)
            drawPhase(phase, colors.faint, measurer)
        }
    }
}

private data class Node(
    val region: NoraTelemetry.Region,
    val label: String,
    val centre: Offset,
    val radius: Float,
)

/**
 * Where each region sits.
 *
 * Ventral stream left to right along the middle — retina, LGN, V1, V2, V4, IT — with the dorsal motion
 * pathway (MT, MST) above it and memory (hippocampus, ATL) below. That is the anatomy, and drawing it
 * this way is what lets somebody who knows the anatomy read the picture.
 */
private fun DrawScope.layout(width: Float, height: Float): List<Node> {
    val pad = min(width, height) * 0.10f
    val innerWidth = width - pad * 2
    val midY = height * 0.50f
    val radius = min(innerWidth / 16f, height * 0.085f)

    fun x(fraction: Float) = pad + innerWidth * fraction

    return listOf(
        Node(NoraTelemetry.Region.RETINA, "Retina", Offset(x(0.02f), midY), radius),
        Node(NoraTelemetry.Region.LGN, "LGN", Offset(x(0.18f), midY), radius),
        Node(NoraTelemetry.Region.V1, "V1", Offset(x(0.34f), midY), radius),
        Node(NoraTelemetry.Region.V2, "V2", Offset(x(0.50f), midY), radius),
        Node(NoraTelemetry.Region.V4, "V4", Offset(x(0.66f), midY), radius),
        Node(NoraTelemetry.Region.IT, "IT", Offset(x(0.82f), midY), radius),

        // Dorsal: motion, above.
        Node(NoraTelemetry.Region.MT, "MT", Offset(x(0.42f), midY - height * 0.28f), radius),
        Node(NoraTelemetry.Region.MST, "MST", Offset(x(0.58f), midY - height * 0.28f), radius),

        // Memory and semantics, below.
        Node(NoraTelemetry.Region.HIPPOCAMPUS, "Hippocampus", Offset(x(0.74f), midY + height * 0.28f), radius),
        Node(NoraTelemetry.Region.ATL, "ATL", Offset(x(0.94f), midY + height * 0.28f), radius),
    )
}

/**
 * Two lines per pathway, because prediction and error travel in opposite directions.
 *
 * THAT IS THE WHOLE POINT OF A PREDICTIVE-CODING DIAGRAM. A single edge whose thickness is total traffic
 * would hide the thing worth seeing: during perception the ascending error dominates, and during
 * generation the descending prediction does. Drawing them separately makes which mode the brain is in
 * visible at a glance.
 */
private fun DrawScope.drawPathways(
    nodes: List<Node>,
    ascending: FloatArray,
    descending: FloatArray,
    accent: Color,
) {
    val byRegion = nodes.associateBy { it.region }
    val peak = max(max(ascending.max(), descending.max()), 1e-6f)

    NoraTelemetry.Pathway.entries.forEachIndexed { index, pathway ->
        val from = byRegion[pathway.from] ?: return@forEachIndexed
        val to = byRegion[pathway.to] ?: return@forEachIndexed

        val up = (ascending.getOrElse(index) { 0f } / peak).coerceIn(0f, 1f)
        val down = (descending.getOrElse(index) { 0f } / peak).coerceIn(0f, 1f)

        // Offset perpendicular to the edge so the two directions do not overdraw each other.
        val dx = to.centre.x - from.centre.x
        val dy = to.centre.y - from.centre.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / length * 3.5f
        val ny = dx / length * 3.5f

        // Ascending (error) in the accent colour; descending (prediction) in a cool grey. A dim line is
        // still drawn at zero traffic so the anatomy is visible before anything runs.
        drawLine(
            color = accent.copy(alpha = 0.12f + 0.78f * up),
            start = Offset(from.centre.x + nx, from.centre.y + ny),
            end = Offset(to.centre.x + nx, to.centre.y + ny),
            strokeWidth = 1f + 3f * up,
        )
        drawLine(
            color = Color(0xFF8FA3C8).copy(alpha = 0.10f + 0.70f * down),
            start = Offset(from.centre.x - nx, from.centre.y - ny),
            end = Offset(to.centre.x - nx, to.centre.y - ny),
            strokeWidth = 1f + 3f * down,
        )
    }
}

private fun DrawScope.drawRegions(
    nodes: List<Node>,
    activity: FloatArray,
    peak: Float,
    accent: Color,
    faint: Color,
    measurer: TextMeasurer,
) {
    nodes.forEach { node ->
        val level = (activity.getOrElse(node.region.ordinal) { 0f } / peak).coerceIn(0f, 1f)

        // A filled disc scaled by activity inside a constant outline: the outline keeps the anatomy
        // readable when nothing is firing, and the fill is what moves.
        drawCircle(
            color = accent.copy(alpha = 0.10f + 0.70f * level),
            radius = node.radius * (0.45f + 0.55f * level),
            center = node.centre,
        )
        drawCircle(
            color = faint.copy(alpha = 0.55f),
            radius = node.radius,
            center = node.centre,
            style = Stroke(width = 1.2f),
        )

        val text = measurer.measure(node.label, TextStyle(fontSize = 9.sp, color = faint))
        drawText(
            text,
            topLeft = Offset(
                node.centre.x - text.size.width / 2f,
                node.centre.y + node.radius + 3f,
            ),
        )
    }
}

private fun DrawScope.drawPhase(phase: String, faint: Color, measurer: TextMeasurer) {
    val label = if (NoraTelemetry.live) phase else "idle"
    val text = measurer.measure(
        "phase: $label",
        TextStyle(fontSize = 10.sp, color = faint),
    )
    drawText(text, topLeft = Offset(6f, 6f))
}
