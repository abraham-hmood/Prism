package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a brush stroke does to the pixels it covers. */
enum class PaintMode {
    PAINT,
    ERASE,
    BLUR,
    SHARPEN,
    DODGE,
    BURN,
    SPONGE,
    SMUDGE,
    CLONE,
    HEAL,
}

/**
 * A brush's shape and strength.
 *
 * [size] is a diameter in canvas pixels, [hardness] the fraction of the radius that is fully opaque
 * before the edge falls off, and [flow] how much a single dab deposits. Opacity caps the whole
 * stroke, which is why holding still does not keep darkening: the stroke's own coverage is tracked
 * and clamped, the way a real paint program does it.
 */
data class PixBrush(
    val size: Double = 40.0,
    val hardness: Double = 0.75,
    val opacity: Double = 1.0,
    val flow: Double = 1.0,
) {
    val radius: Double get() = (size / 2.0).coerceAtLeast(0.5)
}

/**
 * The brush engine.
 *
 * ## Dabs, not lines
 *
 * A stroke is a series of stamps spaced at a fraction of the brush radius, not a line drawn between
 * two points. That is what makes a soft brush soft at every angle, what makes spacing visible at low
 * spacing values, and what lets every paint mode share one implementation.
 *
 * ## A per-stroke coverage buffer
 *
 * Depositing opacity on every dab would make a slow stroke twice as dark as a fast one. The engine
 * keeps a `FloatArray` of how much each pixel has already been covered during THIS stroke and stops
 * once the brush's opacity is reached. The buffer is one float per canvas pixel and is discarded
 * when the stroke ends.
 *
 * ## Clone and heal are one tool with two finishes
 *
 * Both sample a fixed offset from the stroke's start. Clone copies the sample exactly; heal takes the
 * sample's texture and keeps the destination's local colour, which is what stops a patched blemish
 * reading as a sticker.
 */
object PixBrushEngine {

    /**
     * Runs one stroke over [document]'s active layer.
     *
     * [points] are in document coordinates, so a layer with an offset is converted here rather than
     * by every caller. A single point is a click: one dab, which is how the pencil tool draws dots.
     *
     * [cloneSource] is the sampled point in document coordinates for the FIRST point of the stroke;
     * every later dab copies from the same relative offset. Null for every other mode.
     */
    fun stroke(
        document: PixDocument,
        points: List<Pair<Double, Double>>,
        mode: PaintMode,
        brush: PixBrush,
        color: Int,
        cloneSource: Pair<Double, Double>? = null,
    ) {
        if (points.isEmpty()) return
        val layer = document.activeLayer
        val selection = document.selectionFor(layer)
        val target = layer.image
        val coverageBuffer = FloatArray(target.pixels.size)
        val source = when (mode) {
            PaintMode.CLONE, PaintMode.HEAL, PaintMode.SMUDGE -> target.copy()
            else -> null
        }

        // The sample follows the stroke rigidly: source + (dab - first point), in layer pixels.
        val offsetX: Double
        val offsetY: Double
        if ((mode == PaintMode.CLONE || mode == PaintMode.HEAL) && cloneSource != null) {
            offsetX = (cloneSource.first - points.first().first).coerceAtLeast(-100000.0)
            offsetY = (cloneSource.second - points.first().second).coerceAtLeast(-100000.0)
        } else {
            offsetX = 0.0
            offsetY = 0.0
        }

        val spacing = max(0.75, brush.radius * 0.16)
        var previousLocal: Pair<Double, Double>? = null
        var previousDab: Pair<Double, Double>? = null

        for (point in points) {
            val localX = point.first - layer.offsetX
            val localY = point.second - layer.offsetY
            val previous = previousLocal
            if (previous == null) {
                stamp(target, source, localX, localY, mode, brush, color, selection, coverageBuffer, previousDab, offsetX, offsetY)
            } else {
                val distance = hypot(localX - previous.first, localY - previous.second)
                val steps = max(1, ceil(distance / spacing).toInt())
                for (i in 1..steps) {
                    val t = i.toDouble() / steps
                    val x = previous.first + (localX - previous.first) * t
                    val y = previous.second + (localY - previous.second) * t
                    stamp(target, source, x, y, mode, brush, color, selection, coverageBuffer, previousDab, offsetX, offsetY)
                }
            }
            previousDab = localX to localY
            previousLocal = localX to localY
        }
    }

    private fun stamp(
        target: PrismImage,
        source: PrismImage?,
        centerX: Double,
        centerY: Double,
        mode: PaintMode,
        brush: PixBrush,
        color: Int,
        selection: PixSelection?,
        coverageBuffer: FloatArray,
        previousDab: Pair<Double, Double>?,
        offsetX: Double,
        offsetY: Double,
    ) {
        val radius = brush.radius
        val x0 = max(0, (centerX - radius).toInt())
        val y0 = max(0, (centerY - radius).toInt())
        val x1 = min(target.width - 1, (centerX + radius).toInt())
        val y1 = min(target.height - 1, (centerY + radius).toInt())
        if (x1 < x0 || y1 < y0) return

        val paint = Rgba.of(color)
        val hard = brush.hardness.coerceIn(0.0, 1.0)
        val smudgeFromX = previousDab?.first?.toInt()
        val smudgeFromY = previousDab?.second?.toInt()

        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5 - centerX
            val dy = y + 0.5 - centerY
            val distance = hypot(dx, dy)
            if (distance > radius) continue

            val index = y * target.width + x
            val mask = selection?.coverage(x, y) ?: 1.0
            if (mask <= 0.0) continue

            // Soft edge: full strength inside the hard core, a smoothstep falloff outside it.
            val normalised = distance / radius
            val falloff = if (normalised <= hard) 1.0 else {
                val t = ((normalised - hard) / (1.0 - hard).coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
                1.0 - (t * t * (3 - 2 * t))
            }
            val wanted = falloff * brush.flow * mask
            if (wanted <= 0.0) continue

            val already = coverageBuffer[index].toDouble()
            val capacity = (brush.opacity.coerceIn(0.0, 1.0) - already).coerceAtLeast(0.0)
            val deposit = min(wanted, capacity)
            if (deposit <= 0.0) continue
            coverageBuffer[index] = (already + deposit).toFloat()

            val original = Rgba.of(target.pixels[index])
            target.pixels[index] = when (mode) {
                PaintMode.PAINT -> mix(original, Rgba(paint.r, paint.g, paint.b, max(original.a, paint.a)), deposit).packed()
                PaintMode.ERASE -> original.copy(a = original.a * (1 - deposit)).packed()
                PaintMode.BLUR -> mix(original, neighbourAverage(target, x, y), deposit).packed()
                PaintMode.SHARPEN -> {
                    val blurred = neighbourAverage(target, x, y)
                    mix(
                        original,
                        Rgba(
                            (original.r + (original.r - blurred.r) * 1.6).coerceIn(0.0, 255.0),
                            (original.g + (original.g - blurred.g) * 1.6).coerceIn(0.0, 255.0),
                            (original.b + (original.b - blurred.b) * 1.6).coerceIn(0.0, 255.0),
                            original.a,
                        ),
                        deposit,
                    ).packed()
                }
                PaintMode.DODGE -> mix(
                    original,
                    Rgba(
                        original.r + (255 - original.r) * 0.28,
                        original.g + (255 - original.g) * 0.28,
                        original.b + (255 - original.b) * 0.28,
                        original.a,
                    ),
                    deposit,
                ).packed()
                PaintMode.BURN -> mix(
                    original,
                    Rgba(original.r * 0.72, original.g * 0.72, original.b * 0.72, original.a),
                    deposit,
                ).packed()
                PaintMode.SPONGE -> {
                    val luma = original.luma
                    mix(original, Rgba(luma, luma, luma, original.a), deposit * 0.85).packed()
                }
                PaintMode.SMUDGE -> {
                    if (smudgeFromX == null || smudgeFromY == null) {
                        original.packed()
                    } else {
                        val sampled = Rgba.of(sample(target, smudgeFromX, smudgeFromY))
                        mix(original, sampled, deposit * 0.75).packed()
                    }
                }
                PaintMode.CLONE, PaintMode.HEAL -> {
                    val sampled = Rgba.of(sample(source ?: target, (x + offsetX).toInt(), (y + offsetY).toInt()))
                    val patched = if (mode == PaintMode.HEAL) {
                        val local = neighbourAverage(target, x, y)
                        // Keep the destination's local colour, take the source's texture: the
                        // difference between the sample and its own local average is the detail.
                        val textureR = sampled.r - neighbourAverage(source ?: target, (x + offsetX).toInt(), (y + offsetY).toInt()).r
                        val textureG = sampled.g - neighbourAverage(source ?: target, (x + offsetX).toInt(), (y + offsetY).toInt()).g
                        val textureB = sampled.b - neighbourAverage(source ?: target, (x + offsetX).toInt(), (y + offsetY).toInt()).b
                        Rgba(
                            (local.r + textureR).coerceIn(0.0, 255.0),
                            (local.g + textureG).coerceIn(0.0, 255.0),
                            (local.b + textureB).coerceIn(0.0, 255.0),
                            max(original.a, sampled.a),
                        )
                    } else sampled
                    mix(original, patched, deposit).packed()
                }
            }
        }
    }

    private fun sample(image: PrismImage, x: Int, y: Int): Int =
        image.pixels[y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)]

    private fun neighbourAverage(image: PrismImage, x: Int, y: Int): Rgba {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var a = 0.0
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val px = Rgba.of(sample(image, x + dx, y + dy))
            r += px.r; g += px.g; b += px.b; a += px.a
            n++
        }
        return if (n == 0) Rgba.of(sample(image, x, y)) else Rgba(r / n, g / n, b / n, a / n)
    }

    private fun mix(from: Rgba, to: Rgba, t: Double): Rgba {
        val f = t.coerceIn(0.0, 1.0)
        return Rgba(
            from.r + (to.r - from.r) * f,
            from.g + (to.g - from.g) * f,
            from.b + (to.b - from.b) * f,
            from.a + (to.a - from.a) * f,
        )
    }

    /** Paints a single dab, for a click without a drag. */
    fun dab(
        document: PixDocument,
        x: Double,
        y: Double,
        mode: PaintMode,
        brush: PixBrush,
        color: Int,
        cloneSource: Pair<Double, Double>? = null,
    ) = stroke(document, listOf(x to y), mode, brush, color, cloneSource)
}

/**
 * Shape tools: line, rectangle, ellipse and polygon.
 *
 * Shapes are rasterised immediately onto the active layer. A vector shape layer would be a nicer
 * model and is a second renderer; these tools exist to annotate a photo, which is what the viewer's
 * mark action asks for.
 */
object PixShapes {

    fun line(
        image: PrismImage,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        color: Int,
        width: Double,
        selection: PixSelection? = null,
    ) {
        val distance = hypot(to.first - from.first, to.second - from.second)
        val steps = max(1, ceil(distance).toInt())
        val radius = (width / 2.0).coerceAtLeast(0.5)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            disc(image, from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t, radius, color, selection)
        }
    }

    fun rectangle(
        image: PrismImage,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        color: Int,
        width: Double,
        filled: Boolean,
        selection: PixSelection? = null,
    ) {
        val x0 = min(from.first, to.first)
        val x1 = max(from.first, to.first)
        val y0 = min(from.second, to.second)
        val y1 = max(from.second, to.second)
        if (filled) {
            val shape = PixSelection.rect(
                image.width, image.height,
                x0.toInt(), y0.toInt(), (x1 - x0).toInt(), (y1 - y0).toInt(),
            )
            paintMask(image, shape, color, selection)
        } else {
            line(image, x0 to y0, x1 to y0, color, width, selection)
            line(image, x1 to y0, x1 to y1, color, width, selection)
            line(image, x1 to y1, x0 to y1, color, width, selection)
            line(image, x0 to y1, x0 to y0, color, width, selection)
        }
    }

    fun ellipse(
        image: PrismImage,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        color: Int,
        width: Double,
        filled: Boolean,
        selection: PixSelection? = null,
    ) {
        val cx = (from.first + to.first) / 2.0
        val cy = (from.second + to.second) / 2.0
        val rx = abs(to.first - from.first) / 2.0
        val ry = abs(to.second - from.second) / 2.0
        if (rx < 0.5 || ry < 0.5) return
        if (filled) {
            val shape = PixSelection.ellipse(
                image.width, image.height,
                (cx - rx).toInt(), (cy - ry).toInt(), (rx * 2).toInt(), (ry * 2).toInt(),
            )
            paintMask(image, shape, color, selection)
        } else {
            val steps = max(24, ((rx + ry) * 3).toInt())
            var previous = cx + rx to cy
            for (i in 1..steps) {
                val angle = 2 * Math.PI * i / steps
                val point = cx + cos(angle) * rx to cy + sin(angle) * ry
                line(image, previous, point, color, width, selection)
                previous = point
            }
        }
    }

    /** A closed polygon, for the pen tool's path and the lasso's outline. */
    fun polygon(
        image: PrismImage,
        points: List<Pair<Double, Double>>,
        color: Int,
        width: Double,
        filled: Boolean,
        selection: PixSelection? = null,
    ) {
        if (points.size < 2) return
        if (filled) {
            paintMask(image, PixSelection.polygon(image.width, image.height, points), color, selection)
            return
        }
        for (i in points.indices) {
            line(image, points[i], points[(i + 1) % points.size], color, width, selection)
        }
    }

    /**
     * Paints [color] through a shape mask, intersected with the document's selection.
     *
     * The two masks are combined rather than one being applied after the other, so a shape drawn
     * partly outside a feathered selection has a feathered edge at BOTH boundaries instead of a hard
     * line where the shape ends.
     */
    private fun paintMask(image: PrismImage, shape: PixSelection, color: Int, selection: PixSelection?) {
        val combined = if (selection == null) shape else selection.combine(shape, PixSelection.Mode.INTERSECT)
        val paint = Rgba.of(color)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val coverage = combined.coverage(x, y)
            if (coverage <= 0.0) continue
            val index = y * image.width + x
            val base = Rgba.of(image.pixels[index])
            val alpha = (paint.a / 255.0) * coverage
            image.pixels[index] = Rgba(
                base.r + (paint.r - base.r) * alpha,
                base.g + (paint.g - base.g) * alpha,
                base.b + (paint.b - base.b) * alpha,
                max(base.a, paint.a * coverage),
            ).packed()
        }
    }

    private fun disc(image: PrismImage, cx: Double, cy: Double, radius: Double, color: Int, selection: PixSelection?) {
        val x0 = max(0, (cx - radius).toInt())
        val x1 = min(image.width - 1, (cx + radius).toInt())
        val y0 = max(0, (cy - radius).toInt())
        val y1 = min(image.height - 1, (cy + radius).toInt())
        val paint = Rgba.of(color)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5 - cx
            val dy = y + 0.5 - cy
            val distance = hypot(dx, dy)
            if (distance > radius) continue
            // One pixel of antialiasing at the rim.
            val edge = (radius - distance).coerceIn(0.0, 1.0)
            val mask = (selection?.coverage(x, y) ?: 1.0) * edge
            if (mask <= 0.0) continue
            val index = y * image.width + x
            val base = Rgba.of(image.pixels[index])
            val alpha = (paint.a / 255.0) * mask
            image.pixels[index] = Rgba(
                base.r + (paint.r - base.r) * alpha,
                base.g + (paint.g - base.g) * alpha,
                base.b + (paint.b - base.b) * alpha,
                max(base.a, paint.a * mask),
            ).packed()
        }
    }
}
