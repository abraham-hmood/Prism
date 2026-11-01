package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The neighbourhood filters beyond the core set in [PixFilters].
 *
 * Every entry follows the same contract: take an optional selection, return a new image, never
 * mutate the input. Sampling outside the frame is always clamped rather than treated as
 * transparent, because a distort filter that turns the border black looks like a bug even when the
 * coordinate arithmetic is right.
 */
object PixFiltersExtra {

    private fun masked(original: PrismImage, filtered: PrismImage, selection: PixSelection?): PrismImage {
        if (selection == null) return filtered
        val out = original.copy()
        for (i in out.pixels.indices) {
            val coverage = selection.coverage(i % out.width, i / out.width)
            if (coverage <= 0.0) continue
            if (coverage >= 1.0) {
                out.pixels[i] = filtered.pixels[i]
            } else {
                val a = Rgba.of(original.pixels[i])
                val b = Rgba.of(filtered.pixels[i])
                out.pixels[i] = Rgba(
                    a.r + (b.r - a.r) * coverage,
                    a.g + (b.g - a.g) * coverage,
                    a.b + (b.b - a.b) * coverage,
                    a.a + (b.a - a.a) * coverage,
                ).packed()
            }
        }
        return out
    }

    private fun sample(image: PrismImage, x: Int, y: Int): Int =
        image.pixels[y.coerceIn(0, image.height - 1) * image.width + x.coerceIn(0, image.width - 1)]

    private fun lumaAt(image: PrismImage, x: Int, y: Int): Double {
        val p = sample(image, x, y)
        return 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
    }

    private fun bilinear(image: PrismImage, x: Double, y: Double): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun channel(shift: Int): Int {
            fun c(px: Int, py: Int) = (sample(image, px, py) shr shift) and 0xFF
            val top = c(x0, y0) * (1 - fx) + c(x0 + 1, y0) * fx
            val bottom = c(x0, y0 + 1) * (1 - fx) + c(x0 + 1, y0 + 1) * fx
            return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Runs a mapper from destination coordinates to source coordinates. */
    private fun remap(image: PrismImage, mapper: (Double, Double) -> Pair<Double, Double>): PrismImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val source = mapper(x.toDouble(), y.toDouble())
            out[y * image.width + x] = bilinear(image, source.first, source.second)
        }
        return PrismImage(image.width, image.height, out)
    }

    private fun convolve(
        image: PrismImage,
        kernel: DoubleArray,
        radius: Int,
        scale: Double = 1.0,
        offset: Double = 0.0,
    ): PrismImage {
        val out = IntArray(image.pixels.size)
        val size = radius * 2 + 1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            var ki = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val weight = kernel.getOrElse(ki++) { 0.0 }
                val p = sample(image, x + dx, y + dy)
                val a = (p ushr 24) and 0xFF
                sumR += ((p shr 16) and 0xFF) * weight
                sumG += ((p shr 8) and 0xFF) * weight
                sumB += (p and 0xFF) * weight
                sumA += a * weight
            }
            fun clamp(value: Double) = (value * scale + offset).toInt().coerceIn(0, 255)
            out[y * image.width + x] = (clamp(sumA) shl 24) or (clamp(sumR) shl 16) or
                (clamp(sumG) shl 8) or clamp(sumB)
        }
        return PrismImage(image.width, image.height, out)
    }

    private fun blurRadius(image: PrismImage, radius: Int): PrismImage =
        PixFilters.gaussianBlur(image, null, radius.coerceAtLeast(1))

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(0.00001)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    // ── Blur ──────────────────────────────────────────────────────────────

    /** Average: one flat colour, the mean of the layer. */
    fun average(image: PrismImage, selection: PixSelection? = null): PrismImage {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumA = 0L
        for (p in image.pixels) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
            sumA += (p ushr 24) and 0xFF
        }
        val n = image.pixels.size.coerceAtLeast(1)
        val packed = ((sumA / n).toInt() shl 24) or ((sumR / n).toInt() shl 16) or
            ((sumG / n).toInt() shl 8) or (sumB / n).toInt()
        return masked(image, PrismImage(image.width, image.height, IntArray(image.pixels.size) { packed }), selection)
    }

    fun blurMore(image: PrismImage, selection: PixSelection? = null): PrismImage =
        masked(image, blurRadius(image, 2), selection)

    /** Radial Blur: spin averages around the centre, zoom averages along the ray. */
    fun radialBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        amount: Int = 12,
        zoom: Boolean = false,
    ): PrismImage {
        val steps = amount.coerceIn(1, 100)
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val sx: Double
                val sy: Double
                if (zoom) {
                    val scale = 1.0 - t * (steps / 200.0)
                    sx = cx + (x - cx) * scale
                    sy = cy + (y - cy) * scale
                } else {
                    val angle = t * (steps / 200.0) * 2 * Math.PI
                    val dx = x - cx
                    val dy = y - cy
                    sx = cx + dx * cos(angle) - dy * sin(angle)
                    sy = cy + dx * sin(angle) + dy * cos(angle)
                }
                val p = bilinear(image, sx, sy)
                sumA += (p ushr 24) and 0xFF
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            val n = steps + 1
            out[y * image.width + x] = ((sumA / n).toInt() shl 24) or
                ((sumR / n).toInt() shl 16) or ((sumG / n).toInt() shl 8) or (sumB / n).toInt()
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Shape Blur: a polygonal kernel; [sides] 0 is a disc. */
    fun shapeBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 8,
        sides: Int = 5,
    ): PrismImage {
        val r = radius.coerceIn(1, 60)
        val out = IntArray(image.pixels.size)
        val points = if (sides < 3) 40 else sides
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            var count = 0
            for (i in 0 until points) {
                val angle = i.toDouble() / points * 2 * Math.PI
                for (ring in 1..3) {
                    val distance = r * ring / 3.0
                    val p = bilinear(image, x + cos(angle) * distance, y + sin(angle) * distance)
                    sumA += (p ushr 24) and 0xFF
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
            }
            out[y * image.width + x] = ((sumA / count).toInt() shl 24) or
                ((sumR / count).toInt() shl 16) or ((sumG / count).toInt() shl 8) or (sumB / count).toInt()
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Surface Blur: a bilateral average, so flat areas smooth but hard edges survive. */
    fun surfaceBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 5,
        threshold: Int = 15,
    ): PrismImage {
        val r = radius.coerceIn(1, 20)
        val limit = threshold.coerceIn(1, 255).toDouble()
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val centre = Rgba.of(sample(image, x, y))
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            var weightTotal = 0.0
            for (dy in -r..r) for (dx in -r..r) {
                val p = Rgba.of(sample(image, x + dx, y + dy))
                val difference = sqrt(
                    (p.r - centre.r) * (p.r - centre.r) +
                        (p.g - centre.g) * (p.g - centre.g) +
                        (p.b - centre.b) * (p.b - centre.b),
                )
                val weight = 1.0 - smoothstep(0.0, limit, difference)
                sumR += p.r * weight
                sumG += p.g * weight
                sumB += p.b * weight
                sumA += p.a * weight
                weightTotal += weight
            }
            out[y * image.width + x] = if (weightTotal <= 0.0) sample(image, x, y) else Rgba(
                sumR / weightTotal, sumG / weightTotal, sumB / weightTotal, sumA / weightTotal,
            ).packed()
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Smart Blur: blur only where the local contrast is below [threshold]. */
    fun smartBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 3,
        threshold: Int = 25,
        edgeOnly: Boolean = false,
    ): PrismImage {
        val blurred = blurRadius(image, radius)
        val out = image.copy()
        val limit = threshold.coerceIn(1, 255)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val index = y * image.width + x
            val centre = Rgba.of(image.pixels[index])
            var difference = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val p = Rgba.of(sample(image, x + dx, y + dy))
                difference = max(difference, abs(p.luma - centre.luma))
            }
            val factor = if (edgeOnly) difference.coerceIn(0.0, 255.0) else {
                (difference / limit).coerceIn(0.0, 1.0)
            }
            val smooth = Rgba.of(blurred.pixels[index])
            out.pixels[index] = Rgba(
                centre.r + (smooth.r - centre.r) * (1 - factor),
                centre.g + (smooth.g - centre.g) * (1 - factor),
                centre.b + (smooth.b - centre.b) * (1 - factor),
                centre.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    /** Lens Blur: a disc bokeh weighted by a simple luminance depth cue. */
    fun lensBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 12,
        focalDistance: Int = 50,
        highlights: Double = 60.0,
    ): PrismImage {
        val r = radius.coerceIn(1, 40)
        val focal = focalDistance.coerceIn(0, 100) / 100.0
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val centre = Rgba.of(sample(image, x, y))
            val depth = (centre.luma / 255.0 - focal)
            val blur = (r * min(1.0, abs(depth) * 2.0)).toInt().coerceAtLeast(1)
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            var count = 0
            for (dy in -blur..blur) for (dx in -blur..blur) {
                if (dx * dx + dy * dy > blur * blur) continue
                val p = Rgba.of(sample(image, x + dx, y + dy))
                val weight = 1.0 + (p.luma / 255.0).pow(3.0) * (highlights / 100.0) * 3.0
                sumR += p.r * weight
                sumG += p.g * weight
                sumB += p.b * weight
                sumA += p.a * weight
                count += weight.toInt().coerceAtLeast(1)
            }
            out[y * image.width + x] = if (sumR + sumG + sumB <= 0.0) 0 else Rgba(
                sumR / count, sumG / count, sumB / count, sumA / count,
            ).packed()
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Field Blur: a uniform blur; the gallery differentiates it with animated pins elsewhere. */
    fun fieldBlur(image: PrismImage, selection: PixSelection? = null, radius: Int = 12): PrismImage =
        masked(image, blurRadius(image, radius), selection)

    /** Tilt-Shift: a focus band with a linear falloff above and below it. */
    fun tiltShift(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 10,
        focus: Double = 0.5,
        band: Double = 0.2,
    ): PrismImage {
        val blurred = blurRadius(image, radius)
        val out = image.copy()
        val centre = focus.coerceIn(0.0, 1.0) * image.height
        val half = (band.coerceIn(0.02, 1.0) / 2.0) * image.height
        for (y in 0 until image.height) {
            val distance = abs(y - centre)
            val t = ((distance - half) / (image.height - half).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            for (x in 0 until image.width) {
                val index = y * image.width + x
                if (t <= 0.0) continue
                val a = Rgba.of(image.pixels[index])
                val b = Rgba.of(blurred.pixels[index])
                out.pixels[index] = Rgba(
                    a.r + (b.r - a.r) * t,
                    a.g + (b.g - a.g) * t,
                    a.b + (b.b - a.b) * t,
                    a.a,
                ).packed()
            }
        }
        return masked(image, out, selection)
    }

    /** Iris Blur: an elliptical focus region with a feathered rim. */
    fun irisBlur(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 10,
        centreX: Double = 0.5,
        centreY: Double = 0.5,
        focusSize: Double = 0.35,
    ): PrismImage {
        val blurred = blurRadius(image, radius)
        val out = image.copy()
        val cx = centreX.coerceIn(0.0, 1.0) * image.width
        val cy = centreY.coerceIn(0.0, 1.0) * image.height
        val inner = focusSize.coerceIn(0.05, 0.95) / 2.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = (x - cx) / image.width
            val dy = (y - cy) / image.height
            val distance = sqrt(dx * dx + dy * dy)
            val t = ((distance - inner) / (0.7 - inner).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
            if (t <= 0.0) continue
            val index = y * image.width + x
            val a = Rgba.of(image.pixels[index])
            val b = Rgba.of(blurred.pixels[index])
            out.pixels[index] = Rgba(
                a.r + (b.r - a.r) * t,
                a.g + (b.g - a.g) * t,
                a.b + (b.b - a.b) * t,
                a.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    fun spinBlur(image: PrismImage, selection: PixSelection? = null, angle: Int = 12): PrismImage =
        radialBlur(image, selection, angle, zoom = false)

    fun pathBlur(image: PrismImage, selection: PixSelection? = null, distance: Int = 8, angle: Int = 0): PrismImage =
        PixFilters.motionBlur(image, selection, angle.toDouble(), distance)

    // ── Distort ───────────────────────────────────────────────────────────

    fun pinch(image: PrismImage, selection: PixSelection? = null, amount: Int = 40): PrismImage {
        val strength = amount.coerceIn(-100, 100) / 100.0
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxRadius = hypot(cx, cy)
        val out = remap(image) { x, y ->
            val dx = x - cx
            val dy = y - cy
            val distance = hypot(dx, dy) / maxRadius
            val factor = distance.pow(1.0 + strength)
            cx + dx * factor to cy + dy * factor
        }
        return masked(image, out, selection)
    }

    fun spherize(image: PrismImage, selection: PixSelection? = null, amount: Int = 50): PrismImage {
        val strength = amount.coerceIn(-100, 100) / 100.0
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxRadius = hypot(cx, cy)
        val out = remap(image) { x, y ->
            val dx = (x - cx) / maxRadius
            val dy = (y - cy) / maxRadius
            val distance = hypot(dx, dy)
            val factor = if (distance <= 0.0001) 1.0 else {
                val adjusted = distance.pow(1.0 - strength * 0.8)
                adjusted / distance
            }
            cx + dx * factor * maxRadius to cy + dy * factor * maxRadius
        }
        return masked(image, out, selection)
    }

    fun twirl(image: PrismImage, selection: PixSelection? = null, angle: Int = 45): PrismImage {
        val radians = Math.toRadians(angle.toDouble())
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxRadius = hypot(cx, cy)
        val out = remap(image) { x, y ->
            val dx = x - cx
            val dy = y - cy
            val distance = hypot(dx, dy)
            val factor = (1.0 - distance / maxRadius).pow(2.0)
            val rotation = radians * factor
            cx + dx * cos(rotation) - dy * sin(rotation) to
                cy + dx * sin(rotation) + dy * cos(rotation)
        }
        return masked(image, out, selection)
    }

    fun ripple(image: PrismImage, selection: PixSelection? = null, amount: Int = 20, size: Int = 12): PrismImage {
        val amplitude = amount.coerceIn(-100, 100) / 100.0 * 20
        val wavelength = size.coerceIn(2, 200).toDouble()
        val out = remap(image) { x, y ->
            val dx = sin(y / wavelength * 2 * Math.PI) * amplitude
            val dy = cos(x / wavelength * 2 * Math.PI) * amplitude
            x + dx to y + dy
        }
        return masked(image, out, selection)
    }

    fun wave(
        image: PrismImage,
        selection: PixSelection? = null,
        amplitude: Int = 12,
        wavelength: Int = 60,
        vertical: Boolean = true,
    ): PrismImage {
        val a = amplitude.coerceIn(0, 100).toDouble()
        val length = wavelength.coerceIn(4, 400).toDouble()
        val out = remap(image) { x, y ->
            if (vertical) x + sin(y / length * 2 * Math.PI) * a to y
            else x to y + sin(x / length * 2 * Math.PI) * a
        }
        return masked(image, out, selection)
    }

    fun polarCoordinates(image: PrismImage, selection: PixSelection? = null, toPolar: Boolean = true): PrismImage {
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxRadius = hypot(cx, cy)
        val out = remap(image) { x, y ->
            if (toPolar) {
                val dx = x - cx
                val dy = y - cy
                val radius = hypot(dx, dy) / maxRadius * image.height
                val angle = (atan2(dy, dx) + Math.PI) / (2 * Math.PI) * image.width
                angle to radius
            } else {
                val radius = y / image.height * maxRadius
                val angle = x / image.width * 2 * Math.PI - Math.PI
                cx + cos(angle) * radius to cy + sin(angle) * radius
            }
        }
        return masked(image, out, selection)
    }

    fun shear(image: PrismImage, selection: PixSelection? = null, amount: Int = 30): PrismImage {
        val strength = amount.coerceIn(-100, 100) / 100.0 * image.height / 2
        val out = remap(image) { x, y ->
            x + sin(y.toDouble() / image.height * Math.PI * 2) * strength to y
        }
        return masked(image, out, selection)
    }

    fun zigzag(image: PrismImage, selection: PixSelection? = null, amount: Int = 20, ridges: Int = 6): PrismImage {
        val strength = amount.coerceIn(0, 100) / 100.0 * 20
        val count = ridges.coerceIn(1, 40)
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val out = remap(image) { x, y ->
            val dx = x - cx
            val dy = y - cy
            val distance = hypot(dx, dy)
            val angle = atan2(dy, dx)
            val offset = abs((distance / 20.0 % 2.0) - 1.0) * strength
            x + cos(angle + offset) * offset to y + sin(angle + offset) * offset
        }
        return masked(image, out, selection)
    }

    /** Displace, using [map]'s red and green channels as the horizontal and vertical offsets. */
    fun displace(
        image: PrismImage,
        map: PrismImage,
        scaleX: Int = 10,
        scaleY: Int = 10,
        wrap: Boolean = true,
    ): PrismImage {
        val sx = scaleX.coerceIn(-200, 200) / 100.0
        val sy = scaleY.coerceIn(-200, 200) / 100.0
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val mx = x.toLong() * map.width / image.width
            val my = y.toLong() * map.height / image.height
            val m = map.pixels[(my.toInt().coerceIn(0, map.height - 1)) * map.width +
                mx.toInt().coerceIn(0, map.width - 1)]
            val offsetX = (((m shr 16) and 0xFF) - 128) * sx
            val offsetY = (((m shr 8) and 0xFF) - 128) * sy
            var destX = x + offsetX.toInt()
            var destY = y + offsetY.toInt()
            if (wrap) {
                destX = Math.floorMod(destX, image.width)
                destY = Math.floorMod(destY, image.height)
            }
            out[y * image.width + x] = sample(image, destX, destY)
        }
        return PrismImage(image.width, image.height, out)
    }

    // ── Noise ─────────────────────────────────────────────────────────────

    fun despeckle(image: PrismImage, selection: PixSelection? = null): PrismImage =
        PixFilters.median(image, selection, 1)

    /** Dust & Scratches: replaces a pixel only when it differs from its median neighbourhood. */
    fun dustAndScratches(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 2,
        threshold: Int = 12,
    ): PrismImage {
        val median = PixFilters.median(image, null, radius)
        val out = image.copy()
        val limit = threshold.coerceIn(0, 255)
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(median.pixels[i])
            val difference = max(abs(a.r - b.r), max(abs(a.g - b.g), abs(a.b - b.b)))
            if (difference >= limit) out.pixels[i] = median.pixels[i]
        }
        return masked(image, out, selection)
    }

    /** Reduce Noise: a bilateral pass, with an optional separate colour-noise pass. */
    fun reduceNoise(
        image: PrismImage,
        selection: PixSelection? = null,
        strength: Int = 5,
        preserveDetails: Int = 40,
        reduceColorNoise: Int = 30,
        sharpenDetails: Int = 0,
    ): PrismImage {
        val smoothed = surfaceBlur(
            image, null,
            radius = strength.coerceIn(1, 12),
            threshold = (255 - preserveDetails.coerceIn(0, 100) * 2).coerceIn(8, 255),
        )
        val out = image.copy()
        val colourAmount = reduceColorNoise.coerceIn(0, 100) / 100.0
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(smoothed.pixels[i])
            val grey = 0.299 * b.r + 0.587 * b.g + 0.114 * b.b
            val keepColour = 1.0 - colourAmount
            out.pixels[i] = Rgba(
                (a.r + (grey + (b.r - grey) * keepColour - a.r) * 0.8),
                (a.g + (grey + (b.g - grey) * keepColour - a.g) * 0.8),
                (a.b + (grey + (b.b - grey) * keepColour - a.b) * 0.8),
                a.a,
            ).packed()
        }
        val cleaned = PrismImage(image.width, image.height, out.pixels)
        return if (sharpenDetails > 0) {
            val sharpened = PixFilters.unsharpMask(cleaned, null, 1, sharpenDetails.toDouble(), 0)
            masked(image, sharpened, selection)
        } else {
            masked(image, cleaned, selection)
        }
    }

    // ── Pixelate ──────────────────────────────────────────────────────────

    fun colorHalftone(image: PrismImage, selection: PixSelection? = null, radius: Int = 4): PrismImage {
        val cell = radius.coerceIn(1, 20).toDouble()
        val out = IntArray(image.pixels.size)
        val angles = doubleArrayOf(15.0, 75.0, 0.0)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = sample(image, x, y)
            val channels = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            val dots = IntArray(3)
            for (c in 0..2) {
                val radians = Math.toRadians(angles[c])
                val rx = x * cos(radians) + y * sin(radians)
                val ry = -x * sin(radians) + y * cos(radians)
                val fx = (rx / cell) % 1.0 - 0.5
                val fy = (ry / cell) % 1.0 - 0.5
                val distance = hypot(fx, fy)
                val ink = channels[c] / 255.0
                val dot = 1.0 - smoothstep(0.0, 0.5, distance)
                dots[c] = if (dot > 1.0 - ink) 0 else 255
            }
            out[y * image.width + x] = (255 shl 24) or (dots[0] shl 16) or (dots[1] shl 8) or dots[2]
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    fun crystallize(image: PrismImage, selection: PixSelection? = null, cellSize: Int = 10): PrismImage {
        val size = cellSize.coerceIn(2, 80)
        val out = image.copy()
        var state = 1234567L
        fun next(): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % size).toInt()
        }
        for (by in 0 until image.height step size) for (bx in 0 until image.width step size) {
            val jitterX = if (size > 2) next() else 0
            val jitterY = if (size > 2) next() else 0
            val px = (bx + size / 2 + jitterX).coerceIn(0, image.width - 1)
            val py = (by + size / 2 + jitterY).coerceIn(0, image.height - 1)
            val colour = sample(image, px, py)
            for (y in by until min(by + size, image.height)) {
                for (x in bx until min(bx + size, image.width)) {
                    out.pixels[y * image.width + x] = colour
                }
            }
        }
        return masked(image, out, selection)
    }

    fun facet(image: PrismImage, selection: PixSelection? = null, cellSize: Int = 4): PrismImage {
        val size = cellSize.coerceIn(2, 20)
        val out = image.copy()
        for (by in 0 until image.height step size) for (bx in 0 until image.width step size) {
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var count = 0
            for (y in by until min(by + size, image.height)) for (x in bx until min(bx + size, image.width)) {
                val p = sample(image, x, y)
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
                count++
            }
            if (count == 0) continue
            val colour = (255 shl 24) or ((sumR / count).toInt() shl 16) or
                ((sumG / count).toInt() shl 8) or (sumB / count).toInt()
            for (y in by until min(by + size, image.height)) for (x in bx until min(bx + size, image.width)) {
                out.pixels[y * image.width + x] = colour
            }
        }
        return masked(image, out, selection)
    }

    fun fragment(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val out = IntArray(image.pixels.size)
        val offsets = arrayOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumA = 0.0
            for ((dx, dy) in offsets) {
                val p = sample(image, x + dx, y + dy)
                sumA += (p ushr 24) and 0xFF
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            out[y * image.width + x] = ((sumA / 4).toInt() shl 24) or ((sumR / 4).toInt() shl 16) or
                ((sumG / 4).toInt() shl 8) or (sumB / 4).toInt()
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    fun mezzotint(image: PrismImage, selection: PixSelection? = null, kind: Int = 0): PrismImage {
        val out = IntArray(image.pixels.size)
        var state = 987654321L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val source = sample(image, x, y)
            val value = if (kind % 2 == 0) {
                val block = if (kind < 3) 1 else 3
                val threshold = next()
                val local = (x % block == 0 && y % block == 0)
                if (local && threshold > 0.5) 0 else 255
            } else {
                if (next() > 0.55) 0 else 255
            }
            out[y * image.width + x] = (source and 0xFF000000.toInt()) or
                ((value and 0xFF) shl 16) or ((value and 0xFF) shl 8) or (value and 0xFF)
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    fun pointillize(image: PrismImage, selection: PixSelection? = null, cellSize: Int = 6): PrismImage {
        val size = cellSize.coerceIn(2, 40)
        val background = PixFiltersExtra.blurMore(image, null)
        val out = background.copy()
        var state = 424242L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) / 2147483648.0)
        }
        for (y in 0 until image.height step size) for (x in 0 until image.width step size) {
            val cx = x + (next() * size).toInt()
            val cy = y + (next() * size).toInt()
            val colour = sample(image, cx, cy)
            val radius = size / 2.0
            for (dy in -size..size) for (dx in -size..size) {
                if (dx * dx + dy * dy > radius * radius) continue
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until image.width && py in 0 until image.height) {
                    out.pixels[py * image.width + px] = colour
                }
            }
        }
        return masked(image, out, selection)
    }

    // ── Render ────────────────────────────────────────────────────────────

    private fun valueNoise(width: Int, height: Int, seed: Long, scale: Int): DoubleArray {
        val grid = 4.coerceAtLeast(scale)
        val gw = width / grid + 2
        val gh = height / grid + 2
        var state = seed
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        val lattice = DoubleArray(gw * gh) { next() }
        val out = DoubleArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val gx = x.toDouble() / grid
            val gy = y.toDouble() / grid
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val fx = gx - x0
            val fy = gy - y0
            val x1 = (x0 + 1).coerceAtMost(gw - 1)
            val y1 = (y0 + 1).coerceAtMost(gh - 1)
            fun value(ix: Int, iy: Int) = lattice[iy * gw + ix]
            val top = value(x0, y0) * (1 - fx) + value(x1, y0) * fx
            val bottom = value(x0, y1) * (1 - fx) + value(x1, y1) * fx
            out[y * width + x] = top * (1 - fy) + bottom * fy
        }
        return out
    }

    fun clouds(
        image: PrismImage,
        selection: PixSelection? = null,
        colorA: Int = 0xFFFFFFFF.toInt(),
        colorB: Int = 0xFF000000.toInt(),
        seed: Long = 7L,
    ): PrismImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var value = 0.0
            var amplitude = 0.55
            var scale = 96
            repeat(5) {
                value += valueNoise(image.width, image.height, seed + scale, scale)[y * image.width + x] * amplitude
                amplitude *= 0.5
                scale = max(8, scale / 2)
            }
            out[y * image.width + x] = mixColors(colorB, colorA, value.coerceIn(0.0, 1.0))
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    fun differenceClouds(image: PrismImage, selection: PixSelection? = null, seed: Long = 7L): PrismImage {
        val cloud = clouds(image, null, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), seed)
        val out = image.copy()
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(cloud.pixels[i])
            out.pixels[i] = Rgba(
                abs(a.r - b.r), abs(a.g - b.g), abs(a.b - b.b), a.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    fun fibers(image: PrismImage, selection: PixSelection? = null, variance: Int = 16, strength: Int = 4): PrismImage {
        val out = image.copy()
        var state = 991L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) / 2147483648.0) - 0.5
        }
        val amount = strength.coerceIn(1, 50).toDouble()
        val jitter = variance.coerceIn(0, 100) / 100.0
        for (y in 0 until image.height) {
            var value = 0.0
            for (x in 0 until image.width) {
                value = value * 0.86 + next() * jitter * 255
                val px = Rgba.of(image.pixels[y * image.width + x])
                val delta = (value * amount * 0.2).coerceIn(-120.0, 120.0)
                out.pixels[y * image.width + x] = Rgba(
                    (px.r + delta).coerceIn(0.0, 255.0),
                    (px.g + delta).coerceIn(0.0, 255.0),
                    (px.b + delta).coerceIn(0.0, 255.0),
                    px.a,
                ).packed()
            }
        }
        return masked(image, out, selection)
    }

    fun lightingEffects(
        image: PrismImage,
        selection: PixSelection? = null,
        intensity: Int = 50,
        angle: Int = 45,
        shine: Int = 30,
        ambient: Int = 20,
    ): PrismImage {
        val lightX = cos(Math.toRadians(angle.toDouble()))
        val lightY = sin(Math.toRadians(angle.toDouble()))
        val strength = intensity.coerceIn(0, 100) / 100.0
        val specular = shine.coerceIn(0, 100) / 100.0
        val ambientLight = ambient.coerceIn(0, 100) / 100.0
        val out = image.copy()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val index = y * image.width + x
            val px = Rgba.of(image.pixels[index])
            val dx = (lumaAt(image, x + 2, y) - lumaAt(image, x - 2, y)) / 255.0
            val dy = (lumaAt(image, x, y + 2) - lumaAt(image, x, y - 2)) / 255.0
            val length = sqrt(dx * dx + dy * dy + 0.05)
            val nx = -dx / length
            val ny = -dy / length
            val nz = 1.0 / length
            val diffuse = (nx * lightX + ny * lightY + nz * 0.7).coerceAtLeast(0.0)
            val highlight = diffuse.pow(24.0) * specular
            val light = ambientLight + diffuse * strength
            out.pixels[index] = Rgba(
                (px.r * light + 255 * highlight * 0.5).coerceIn(0.0, 255.0),
                (px.g * light + 255 * highlight * 0.5).coerceIn(0.0, 255.0),
                (px.b * light + 255 * highlight * 0.5).coerceIn(0.0, 255.0),
                px.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    /** Picture Frame: a simple inset border in a chosen colour. */
    fun pictureFrame(
        image: PrismImage,
        selection: PixSelection? = null,
        width: Int = 12,
        color: Int = 0xFFF4EFE6.toInt(),
        style: Int = 0,
    ): PrismImage {
        val border = width.coerceIn(1, 200)
        val out = image.copy()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val edge = min(min(x, y), min(image.width - 1 - x, image.height - 1 - y))
            if (edge >= border) continue
            val pattern = when (style) {
                1 -> ((x + y) / 8) % 2 == 0
                2 -> (x / 8) % 2 == (y / 8) % 2
                else -> true
            }
            if (!pattern) continue
            val index = y * image.width + x
            val amount = (1.0 - edge.toDouble() / border).coerceIn(0.0, 1.0)
            val base = Rgba.of(image.pixels[index])
            val frame = Rgba.of(color)
            out.pixels[index] = Rgba(
                base.r + (frame.r - base.r) * amount,
                base.g + (frame.g - base.g) * amount,
                base.b + (frame.b - base.b) * amount,
                max(base.a, frame.a * amount),
            ).packed()
        }
        return masked(image, out, selection)
    }

    // ── Sharpen ───────────────────────────────────────────────────────────

    fun sharpenEdges(image: PrismImage, selection: PixSelection? = null, amount: Int = 100): PrismImage =
        PixFilters.unsharpMask(image, selection, 1, amount.toDouble(), 8)

    fun sharpenMore(image: PrismImage, selection: PixSelection? = null): PrismImage =
        PixFilters.unsharpMask(image, selection, 2, 180.0, 0)

    fun smartSharpen(
        image: PrismImage,
        selection: PixSelection? = null,
        amount: Int = 120,
        radius: Int = 2,
        reduceNoise: Int = 20,
    ): PrismImage {
        val denoised = if (reduceNoise > 0) {
            surfaceBlur(image, null, 1, (255 - reduceNoise * 2).coerceIn(20, 255))
        } else {
            image
        }
        return PixFilters.unsharpMask(denoised, selection, radius, amount.toDouble(), 0)
    }

    fun shakeReduction(image: PrismImage, selection: PixSelection? = null, amount: Int = 60): PrismImage =
        PixFilters.unsharpMask(image, selection, 6, amount.toDouble(), 4)

    // ── Stylize ───────────────────────────────────────────────────────────

    fun diffuse(image: PrismImage, selection: PixSelection? = null, mode: Int = 0, strength: Int = 4): PrismImage {
        val out = image.copy()
        var state = 31337L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) / 2147483648.0)
        }
        val reach = strength.coerceIn(1, 20)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val index = y * image.width + x
            val choice = next()
            if (choice > 0.55) continue
            val dx = (next() * reach * 2 - reach).toInt()
            val dy = (next() * reach * 2 - reach).toInt()
            out.pixels[index] = when (mode) {
                1 -> {
                    val a = Rgba.of(image.pixels[index])
                    val b = Rgba.of(sample(image, x + dx, y + dy))
                    Rgba(max(a.r, b.r), max(a.g, b.g), max(a.b, b.b), a.a).packed()
                }
                2 -> {
                    val a = Rgba.of(image.pixels[index])
                    val b = Rgba.of(sample(image, x + dx, y + dy))
                    Rgba(min(a.r, b.r), min(a.g, b.g), min(a.b, b.b), a.a).packed()
                }
                else -> sample(image, x + dx, y + dy)
            }
        }
        return masked(image, out, selection)
    }

    fun extrude(image: PrismImage, selection: PixSelection? = null, size: Int = 16, depth: Int = 30): PrismImage {
        val block = size.coerceIn(4, 80)
        val out = image.copy()
        for (by in 0 until image.height step block) for (bx in 0 until image.width step block) {
            val px = (bx + block / 2).coerceIn(0, image.width - 1)
            val py = (by + block / 2).coerceIn(0, image.height - 1)
            val colour = sample(image, px, py)
            val offsetX = (bx / block * 37 + by / block * 17) % (block + depth) - depth / 2
            for (y in by until min(by + block, image.height)) for (x in bx until min(bx + block, image.width)) {
                val sx = (x + offsetX).coerceIn(0, image.width - 1)
                out.pixels[y * image.width + x] = Rgba.of(colour).let {
                    Rgba(it.r * 0.8, it.g * 0.8, it.b * 0.8, it.a).packed()
                }
                if (x - bx < 2) out.pixels[y * image.width + x] = colour
            }
        }
        return masked(image, out, selection)
    }

    fun tiles(image: PrismImage, selection: PixSelection? = null, count: Int = 8, offset: Int = 10): PrismImage {
        val tileCount = count.coerceIn(2, 40)
        val tileWidth = max(1, image.width / tileCount)
        val tileHeight = max(1, image.height / tileCount)
        val shift = offset.coerceIn(0, 100)
        val out = PrismImage(image.width, image.height, IntArray(image.pixels.size) { 0xFFFFFFFF.toInt() })
        for (ty in 0 until tileCount) for (tx in 0 until tileCount) {
            val sourceX = tx * tileWidth
            val sourceY = ty * tileHeight
            val targetX = (tx * tileWidth + (tx * 31 + ty * 17) % (shift + 1) - shift / 2)
            val targetY = (ty * tileHeight + (tx * 13 + ty * 29) % (shift + 1) - shift / 2)
            for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
                val px = targetX + x
                val py = targetY + y
                if (px !in 0 until image.width || py !in 0 until image.height) continue
                out.pixels[py * image.width + px] = sample(image, sourceX + x, sourceY + y)
            }
        }
        return masked(image, out, selection)
    }

    fun traceContour(image: PrismImage, selection: PixSelection? = null, level: Int = 128, lower: Boolean = true): PrismImage {
        val cut = level.coerceIn(0, 255)
        val out = image.copy()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val here = lumaAt(image, x, y)
            val right = lumaAt(image, x + 1, y)
            val below = lumaAt(image, x, y + 1)
            val edge = if (lower) {
                (here < cut && (right >= cut || below >= cut))
            } else {
                (here >= cut && (right < cut || below < cut))
            }
            val index = y * image.width + x
            val alpha = (image.pixels[index] ushr 24) and 0xFF
            out.pixels[index] = if (edge) (alpha shl 24) or 0x000000 else (alpha shl 24) or 0xFFFFFF
        }
        return masked(image, out, selection)
    }

    fun wind(image: PrismImage, selection: PixSelection? = null, strength: Int = 10, fromRight: Boolean = false): PrismImage {
        val reach = strength.coerceIn(1, 100)
        val out = image.copy()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sourceX = x
            var count = 0
            var sum = 0.0
            while (sourceX in 0 until image.width && count < reach) {
                sum += lumaAt(image, sourceX, y)
                sourceX += if (fromRight) 1 else -1
                count++
            }
            if (count == 0) continue
            val average = sum / count
            val index = y * image.width + x
            val px = Rgba.of(image.pixels[index])
            val streak = if (average > px.luma) px.luma + (255 - px.luma) * 0.35 else px.luma * 0.75
            out.pixels[index] = Rgba(streak, streak, streak, px.a).packed()
        }
        return masked(image, out, selection)
    }

    fun glowingEdges(image: PrismImage, selection: PixSelection? = null, radius: Int = 3): PrismImage {
        val edges = PixFilters.findEdges(image, null)
        val glow = blurRadius(edges, radius)
        val out = image.copy()
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val e = Rgba.of(glow.pixels[i])
            out.pixels[i] = Rgba(
                (a.r + e.r * 0.7).coerceIn(0.0, 255.0),
                (a.g + e.g * 0.7).coerceIn(0.0, 255.0),
                (a.b + e.b * 0.7).coerceIn(0.0, 255.0),
                a.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    // ── Other ─────────────────────────────────────────────────────────────

    fun customConvolution(
        image: PrismImage,
        selection: PixSelection? = null,
        kernel: DoubleArray,
        scale: Double = 1.0,
        offset: Double = 0.0,
    ): PrismImage {
        val size = when (kernel.size) {
            9 -> 1
            25 -> 2
            else -> 1
        }
        return masked(image, convolve(image, kernel, size, scale, offset), selection)
    }

    /** HSB/HSL: shifts the image through one colour model and back. */
    fun hsbHsl(image: PrismImage, selection: PixSelection? = null, hueShift: Double = 0.0): PrismImage {
        val out = image.copy()
        for (i in out.pixels.indices) {
            val px = Rgba.of(image.pixels[i])
            val maximum = max(px.r, max(px.g, px.b))
            val minimum = min(px.r, min(px.g, px.b))
            val brightness = maximum / 255.0
            val saturation = if (maximum <= 0.0) 0.0 else (maximum - minimum) / maximum
            var hue = when (maximum) {
                px.r -> ((px.g - px.b) / (maximum - minimum).coerceAtLeast(0.001)) % 6.0
                px.g -> (px.b - px.r) / (maximum - minimum).coerceAtLeast(0.001) + 2.0
                else -> (px.r - px.g) / (maximum - minimum).coerceAtLeast(0.001) + 4.0
            } / 6.0
            hue = (hue + hueShift / 360.0 + 1.0) % 1.0
            val (r, g, b) = PixAdjustments.hslToRgb(hue, saturation, brightness)
            out.pixels[i] = Rgba(r * 255.0, g * 255.0, b * 255.0, px.a).packed()
        }
        return masked(image, out, selection)
    }

    fun deInterlace(image: PrismImage, selection: PixSelection? = null, even: Boolean = true): PrismImage {
        val out = image.copy()
        val start = if (even) 1 else 0
        for (y in start until image.height step 2) {
            for (x in 0 until image.width) {
                val index = y * image.width + x
                val above = sample(image, x, y - 1)
                val below = sample(image, x, y + 1)
                fun channel(shift: Int) = (((above shr shift) and 0xFF) + ((below shr shift) and 0xFF)) / 2
                out.pixels[index] = (channel(24) shl 24) or (channel(16) shl 16) or
                    (channel(8) shl 8) or channel(0)
            }
        }
        return masked(image, out, selection)
    }

    fun ntscColors(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val out = image.copy()
        for (i in out.pixels.indices) {
            val px = Rgba.of(image.pixels[i])
            val y = 0.299 * px.r + 0.587 * px.g + 0.114 * px.b
            fun legal(value: Double) = (value * 0.85 + 30.0).coerceIn(0.0, 255.0)
            out.pixels[i] = Rgba(
                legal(y + (px.r - y) * 0.9),
                legal(y + (px.g - y) * 0.9),
                legal(y + (px.b - y) * 0.9),
                px.a,
            ).packed()
        }
        return masked(image, out, selection)
    }

    /** Lens Correction: barrel/pincushion, chromatic aberration and vignetting. */
    fun lensCorrection(
        image: PrismImage,
        selection: PixSelection? = null,
        distortion: Int = 0,
        chromaticAberration: Int = 0,
        vignette: Int = 0,
    ): PrismImage {
        val strength = distortion.coerceIn(-100, 100) / 100.0 * 0.25
        val ca = chromaticAberration.coerceIn(-100, 100) / 100.0 * 0.01
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxRadius = hypot(cx, cy)
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = (x - cx) / maxRadius
            val dy = (y - cy) / maxRadius
            val radius = hypot(dx, dy)
            val factor = 1.0 + strength * radius * radius
            fun channel(offset: Double, shift: Int): Int {
                val scale = (factor + offset).coerceAtLeast(0.1)
                val sx = cx + (x - cx) * scale
                val sy = cy + (y - cy) * scale
                return (bilinear(image, sx, sy) shr shift) and 0xFF
            }
            val r = channel(ca, 16)
            val g = channel(0.0, 8)
            val b = channel(-ca, 0)
            val falloff = 1.0 - (vignette.coerceIn(-100, 100) / 100.0) * radius.pow(3.0)
            out[y * image.width + x] = (255 shl 24) or
                ((r * falloff).toInt().coerceIn(0, 255) shl 16) or
                ((g * falloff).toInt().coerceIn(0, 255) shl 8) or
                ((b * falloff).toInt().coerceIn(0, 255))
        }
        return masked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Adaptive Wide Angle: the wide part of Lens Correction without the constraint UI. */
    fun adaptiveWideAngle(image: PrismImage, selection: PixSelection? = null, strength: Int = 30): PrismImage =
        lensCorrection(image, selection, distortion = strength, vignette = 0)

    /**
     * Camera Raw Filter: the Basic, Effects and Detail panels chained in the order Camera Raw
     * applies them. Masking and the AI tools are separate entry points.
     */
    fun cameraRaw(
        image: PrismImage,
        selection: PixSelection? = null,
        exposure: Double = 0.0,
        contrast: Double = 0.0,
        highlights: Double = 0.0,
        shadows: Double = 0.0,
        whites: Double = 0.0,
        blacks: Double = 0.0,
        temperature: Double = 0.0,
        tint: Double = 0.0,
        texture: Double = 0.0,
        clarity: Double = 0.0,
        dehaze: Double = 0.0,
        vibrance: Double = 0.0,
        saturation: Double = 0.0,
        grain: Double = 0.0,
        vignetteAmount: Double = 0.0,
    ): PrismImage {
        var current = PixAdjustments.temperature(image, selection, temperature, tint)
        current = PixAdjustments.exposure(current, selection, exposure, 0.0, 1.0)
        current = PixAdjustments.shadowsHighlights(current, selection, shadows, highlights)
        current = PixAdjustments.brightnessContrast(current, selection, 0.0, contrast)
        current = PixAdjustments.levels(
            current, selection,
            inBlack = (blacks.coerceIn(-100.0, 100.0) * 0.3 + 0.0).toInt().coerceAtLeast(0),
            inWhite = 255 - (whites.coerceIn(-100.0, 100.0) * 0.3).toInt().coerceAtLeast(0),
        )
        if (texture != 0.0) {
            val high = PixFilters.highPass(current, null, 3)
            current = blendLayer(current, high, BlendMode.OVERLAY, texture.coerceIn(-100.0, 100.0).let { abs(it) / 200.0 })
        }
        if (clarity != 0.0) {
            val high = PixFilters.highPass(current, null, 24)
            current = blendLayer(current, high, BlendMode.SOFT_LIGHT, abs(clarity) / 180.0)
        }
        if (dehaze != 0.0) {
            current = PixAdjustments.brightnessContrast(current, null, -dehaze * 0.15, dehaze * 0.35)
            current = PixAdjustments.vibrance(current, null, dehaze * 0.4)
        }
        current = PixAdjustments.vibrance(current, selection, vibrance)
        current = PixAdjustments.hueSaturation(current, selection, 0.0, saturation, 0.0)
        if (grain > 0.0) current = PixFilters.addNoise(current, selection, grain.coerceIn(0.0, 100.0) * 0.35, monochrome = true)
        if (vignetteAmount != 0.0) {
            current = PixFilters.vignette(current, selection, abs(vignetteAmount), 55.0)
        }
        return current
    }

    /** Composites [top] over [base] with a mode and opacity, for filter stacking. */
    fun blendLayer(base: PrismImage, top: PrismImage, mode: BlendMode, opacity: Double): PrismImage {
        val out = base.copy()
        PixImageOps.draw(out, top, 0, 0, mode, opacity)
        return out
    }

    /** A directional blur along a path; the entry point for Path Blur's animated pin. */
    fun pathBlurStreak(image: PrismImage, amount: Int = 8, angle: Int = 0, taper: Int = 0): PrismImage {
        var current = image
        val steps = amount.coerceIn(1, 40)
        for (i in 1..steps) {
            val offset = (i.toDouble() / steps * 0.5 * (1.0 - taper / 100.0) * 20).toInt()
            val dx = (cos(Math.toRadians(angle.toDouble())) * offset).toInt()
            val dy = (sin(Math.toRadians(angle.toDouble())) * offset).toInt()
            val shifted = PixFilters.offset(current, null, dx, dy)
            current = blendLayer(current, shifted, BlendMode.NORMAL, 0.5 / steps)
        }
        return current
    }
}
