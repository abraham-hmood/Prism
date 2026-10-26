package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.pow

/**
 * Tonal and colour adjustments.
 *
 * ## Every adjustment is a pure function over a copy
 *
 * Non-destructive by construction: the caller keeps the previous image and decides whether to
 * replace a layer with the result. That is what makes the history stack one line per edit, and it is
 * also how an adjustment layer will work later without reimplementing any of this.
 *
 * ## LUTs where the operation is pointwise
 *
 * Brightness, curves, levels and the rest have no neighbourhood, so a 256-entry lookup table is
 * built once and applied per pixel. On a two-megapixel canvas that is the difference between a
 * visible pause and an instant response.
 *
 * ## Selection is a multiplier, not a clip
 *
 * A feathered selection blends the adjusted pixel with the original by the mask's coverage. Clipping
 * at 50% would produce a hard edge exactly where the user asked for a soft one.
 */
object PixAdjustments {

    private fun map(
        image: PrismImage,
        selection: PixSelection?,
        transform: (Rgba) -> Rgba,
    ): PrismImage {
        val out = image.copy()
        for (i in out.pixels.indices) {
            val coverage = selection?.let { sel ->
                val x = i % out.width
                val y = i / out.width
                sel.coverage(x, y)
            } ?: 1.0
            if (coverage <= 0.0) continue
            val original = Rgba.of(out.pixels[i])
            val changed = transform(original)
            out.pixels[i] = if (coverage >= 1.0) {
                changed.copy(a = original.a).packed()
            } else {
                Rgba(
                    original.r + (changed.r - original.r) * coverage,
                    original.g + (changed.g - original.g) * coverage,
                    original.b + (changed.b - original.b) * coverage,
                    original.a,
                ).packed()
            }
        }
        return out
    }

    /**
     * Builds a 256-entry table from a scalar function and applies it to each channel.
     *
     * The function receives 0..1 and returns 0..1 so the individual adjustments do not all have to
     * remember whether they are working in bytes or fractions.
     */
    private fun lut(curve: (Double) -> Double): IntArray {
        val table = IntArray(256)
        for (i in 0..255) {
            table[i] = (curve(i / 255.0) * 255.0).coerceIn(0.0, 255.0).toInt()
        }
        return table
    }

    private fun applyLut(image: PrismImage, selection: PixSelection?, table: IntArray): PrismImage =
        map(image, selection) { px ->
            Rgba(
                table[px.r.toInt()].toDouble(),
                table[px.g.toInt()].toDouble(),
                table[px.b.toInt()].toDouble(),
                px.a,
            )
        }

    // ── Light ─────────────────────────────────────────────────────────────

    /** Photoshop's Brightness/Contrast. `brightness` and `contrast` are -100..100. */
    fun brightnessContrast(
        image: PrismImage,
        selection: PixSelection? = null,
        brightness: Double,
        contrast: Double,
    ): PrismImage {
        val b = brightness.coerceIn(-100.0, 100.0) / 100.0
        // The classic slope: 1 at 0, approaching infinity at 100. Clamped so 100 is strong but finite.
        val c = (contrast.coerceIn(-100.0, 100.0) / 100.0)
        val slope = if (c >= 0) 1.0 / (1.0 - c * 0.99) else 1.0 + c
        val table = lut { value ->
            ((value - 0.5) * slope + 0.5 + b * 0.5)
        }
        return applyLut(image, selection, table)
    }

    fun levels(
        image: PrismImage,
        selection: PixSelection? = null,
        inBlack: Int = 0,
        inWhite: Int = 255,
        gamma: Double = 1.0,
        outBlack: Int = 0,
        outWhite: Int = 255,
    ): PrismImage {
        val low = inBlack.coerceIn(0, 254) / 255.0
        val high = inWhite.coerceIn(inBlack + 1, 255) / 255.0
        val g = gamma.coerceIn(0.1, 9.99)
        val outLow = outBlack.coerceIn(0, 255) / 255.0
        val outHigh = outWhite.coerceIn(0, 255) / 255.0
        val table = lut { value ->
            val normalised = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
            val corrected = normalised.pow(1.0 / g)
            outLow + corrected * (outHigh - outLow)
        }
        return applyLut(image, selection, table)
    }

    /**
     * A curve through user-placed points.
     *
     * Linear interpolation between the points rather than a spline. A spline is smoother and can
     * overshoot, which on a tone curve means reversing the slope -- bright input producing darker
     * output, which no user asked for and every editor that ships a spline has to clamp away. The
     * polyline cannot do that.
     */
    fun curves(image: PrismImage, selection: PixSelection? = null, points: List<Pair<Int, Int>>): PrismImage {
        val sorted = points.sortedBy { it.first }
        if (sorted.isEmpty()) return image
        val table = IntArray(256)
        for (i in 0..255) {
            table[i] = when {
                i <= sorted.first().first -> sorted.first().second
                i >= sorted.last().first -> sorted.last().second
                else -> {
                    val right = sorted.first { it.first >= i }
                    val left = sorted.last { it.first <= i }
                    if (right.first == left.first) right.second
                    else {
                        val t = (i - left.first).toDouble() / (right.first - left.first)
                        (left.second + (right.second - left.second) * t).toInt()
                    }
                }
            }.coerceIn(0, 255)
        }
        return applyLut(image, selection, table)
    }

    /** Exposure in stops, plus offset and gamma as Camera Raw defines them. */
    fun exposure(
        image: PrismImage,
        selection: PixSelection? = null,
        exposure: Double = 0.0,
        offset: Double = 0.0,
        gamma: Double = 1.0,
    ): PrismImage {
        val gain = 2.0.pow(exposure.coerceIn(-5.0, 5.0))
        val shift = offset.coerceIn(-0.5, 0.5)
        val g = gamma.coerceIn(0.1, 9.99)
        val table = lut { value ->
            ((value * gain + shift).coerceIn(0.0, 1.0)).pow(1.0 / g)
        }
        return applyLut(image, selection, table)
    }

    /** Shadows (positive lifts) and highlights (positive recovers). Both -100..100. */
    fun shadowsHighlights(
        image: PrismImage,
        selection: PixSelection? = null,
        shadows: Double = 0.0,
        highlights: Double = 0.0,
    ): PrismImage {
        val s = shadows.coerceIn(-100.0, 100.0) / 100.0
        val h = highlights.coerceIn(-100.0, 100.0) / 100.0
        return map(image, selection) { px ->
            fun adjust(channel: Double): Double {
                val luma = px.luma / 255.0
                // A smooth weighting rather than a threshold: a hard cutoff at mid-grey leaves a
                // visible band exactly where shadow and highlight meet.
                val shadowWeight = (1.0 - luma).pow(2.0)
                val highlightWeight = luma.pow(2.0)
                val delta = (s * shadowWeight - h * highlightWeight) * 255.0 * 0.7
                return (channel + delta).coerceIn(0.0, 255.0)
            }
            Rgba(adjust(px.r), adjust(px.g), adjust(px.b), px.a)
        }
    }

    // ── Colour ────────────────────────────────────────────────────────────

    /** Hue rotation in degrees, saturation and lightness as -100..100. */
    fun hueSaturation(
        image: PrismImage,
        selection: PixSelection? = null,
        hueShift: Double = 0.0,
        saturation: Double = 0.0,
        lightness: Double = 0.0,
    ): PrismImage {
        val shift = hueShift / 360.0
        val satFactor = 1.0 + saturation.coerceIn(-100.0, 100.0) / 100.0
        val lightFactor = lightness.coerceIn(-100.0, 100.0) / 100.0
        return map(image, selection) { px ->
            val (h, s, l) = rgbToHsl(px.r / 255.0, px.g / 255.0, px.b / 255.0)
            val newH = (h + shift + 1.0) % 1.0
            val newS = (s * satFactor).coerceIn(0.0, 1.0)
            val newL = if (lightFactor >= 0) l + (1.0 - l) * lightFactor else l * (1.0 + lightFactor)
            val (r, g, b) = hslToRgb(newH, newS, newL.coerceIn(0.0, 1.0))
            Rgba(r * 255.0, g * 255.0, b * 255.0, px.a)
        }
    }

    /**
     * Saturation that protects already-saturated pixels.
     *
     * Vibrance's whole point: skin and sky are mid-saturation and get lifted, while a red that is
     * already at the gamut edge is left alone rather than clipped into a flat blob.
     */
    fun vibrance(image: PrismImage, selection: PixSelection? = null, amount: Double = 0.0): PrismImage {
        val strength = amount.coerceIn(-100.0, 100.0) / 100.0
        return map(image, selection) { px ->
            val max = maxOf(px.r, px.g, px.b)
            val min = minOf(px.r, px.g, px.b)
            val saturation = if (max <= 0.0) 0.0 else (max - min) / max
            val factor = 1.0 + strength * (1.0 - saturation)
            val luma = px.luma
            Rgba(
                (luma + (px.r - luma) * factor).coerceIn(0.0, 255.0),
                (luma + (px.g - luma) * factor).coerceIn(0.0, 255.0),
                (luma + (px.b - luma) * factor).coerceIn(0.0, 255.0),
                px.a,
            )
        }
    }

    /** Per-range colour casts. Each value is -100..100 on that channel. */
    fun colorBalance(
        image: PrismImage,
        selection: PixSelection? = null,
        shadowR: Double = 0.0, shadowG: Double = 0.0, shadowB: Double = 0.0,
        midR: Double = 0.0, midG: Double = 0.0, midB: Double = 0.0,
        highR: Double = 0.0, highG: Double = 0.0, highB: Double = 0.0,
    ): PrismImage = map(image, selection) { px ->
        fun adjust(channel: Double, shadow: Double, mid: Double, high: Double): Double {
            val luma = px.luma / 255.0
            val shadowWeight = (1.0 - luma).pow(2.0)
            val highWeight = luma.pow(2.0)
            val midWeight = 1.0 - shadowWeight - highWeight
            val delta = (shadow * shadowWeight + mid * midWeight + high * highWeight) / 100.0 * 100.0
            return (channel + delta).coerceIn(0.0, 255.0)
        }
        Rgba(
            adjust(px.r, shadowR, midR, highR),
            adjust(px.g, shadowG, midG, highG),
            adjust(px.b, shadowB, midB, highB),
            px.a,
        )
    }

    /** White balance. Positive temperature warms, positive tint moves toward magenta. */
    fun temperature(image: PrismImage, selection: PixSelection? = null, temperature: Double = 0.0, tint: Double = 0.0): PrismImage {
        val t = temperature.coerceIn(-100.0, 100.0) / 100.0
        val g = tint.coerceIn(-100.0, 100.0) / 100.0
        return map(image, selection) { px ->
            Rgba(
                (px.r + t * 28.0).coerceIn(0.0, 255.0),
                (px.g + g * 18.0 - t * 4.0).coerceIn(0.0, 255.0),
                (px.b - t * 28.0 + g * 6.0).coerceIn(0.0, 255.0),
                px.a,
            )
        }
    }

    /**
     * A black-and-white conversion with the six sliders the Camera Raw mix offers.
     *
     * Each pixel is assigned to the nearest of six hue families and its weight is interpolated from
     * there, which is what makes a red apple lighten without also lightening the green leaf behind
     * it. A plain luminance conversion has none of this control and is what [desaturate] is for.
     */
    fun blackAndWhite(
        image: PrismImage,
        selection: PixSelection? = null,
        red: Double = 40.0,
        yellow: Double = 60.0,
        green: Double = 40.0,
        cyan: Double = 60.0,
        blue: Double = 20.0,
        magenta: Double = 80.0,
    ): PrismImage {
        val weights = doubleArrayOf(red, yellow, green, cyan, blue, magenta).map { 0.3 + it / 100.0 }
        return map(image, selection) { px ->
            val (h, s, _) = rgbToHsl(px.r / 255.0, px.g / 255.0, px.b / 255.0)
            val position = h * 6.0
            val low = position.toInt() % 6
            val high = (low + 1) % 6
            val t = position - position.toInt()
            // A neutral pixel has no hue family; it keeps plain luminance rather than picking one.
            val weight = if (s < 0.05) 1.0 else weights[low] * (1 - t) + weights[high] * t
            val grey = (px.luma * weight).coerceIn(0.0, 255.0)
            Rgba(grey, grey, grey, px.a)
        }
    }

    /** A photographic warming/cooling filter, as the Photo Filter adjustment. */
    fun photoFilter(image: PrismImage, selection: PixSelection? = null, color: Int, density: Double = 25.0): PrismImage {
        val amount = density.coerceIn(0.0, 100.0) / 100.0
        val fr = ((color shr 16) and 0xFF).toDouble()
        val fg = ((color shr 8) and 0xFF).toDouble()
        val fb = (color and 0xFF).toDouble()
        return map(image, selection) { px ->
            Rgba(
                (px.r + (fr - px.r) * amount * 0.55).coerceIn(0.0, 255.0),
                (px.g + (fg - px.g) * amount * 0.55).coerceIn(0.0, 255.0),
                (px.b + (fb - px.b) * amount * 0.55).coerceIn(0.0, 255.0),
                px.a,
            )
        }
    }

    // ── Point operations ──────────────────────────────────────────────────

    fun invert(image: PrismImage, selection: PixSelection? = null): PrismImage =
        map(image, selection) { Rgba(255 - it.r, 255 - it.g, 255 - it.b, it.a) }

    fun posterize(image: PrismImage, selection: PixSelection? = null, levels: Int = 4): PrismImage {
        val steps = levels.coerceIn(2, 64)
        val table = lut { value -> (Math.round(value * (steps - 1)) / (steps - 1.0)) }
        return applyLut(image, selection, table)
    }

    fun threshold(image: PrismImage, selection: PixSelection? = null, level: Int = 128): PrismImage {
        val cut = level.coerceIn(0, 255)
        return map(image, selection) { px ->
            val value = if (px.luma >= cut) 255.0 else 0.0
            Rgba(value, value, value, px.a)
        }
    }

    fun desaturate(image: PrismImage, selection: PixSelection? = null): PrismImage =
        map(image, selection) { px ->
            val luma = px.luma
            Rgba(luma, luma, luma, px.a)
        }

    /** Maps luminance between two colours: the Gradient Map adjustment. */
    fun gradientMap(image: PrismImage, selection: PixSelection? = null, dark: Int, light: Int): PrismImage {
        val dr = ((dark shr 16) and 0xFF).toDouble()
        val dg = ((dark shr 8) and 0xFF).toDouble()
        val db = (dark and 0xFF).toDouble()
        val lr = ((light shr 16) and 0xFF).toDouble()
        val lg = ((light shr 8) and 0xFF).toDouble()
        val lb = (light and 0xFF).toDouble()
        return map(image, selection) { px ->
            val t = (px.luma / 255.0).coerceIn(0.0, 1.0)
            Rgba(dr + (lr - dr) * t, dg + (lg - dg) * t, db + (lb - db) * t, px.a)
        }
    }

    /**
     * Auto Contrast: stretches the histogram to the full range.
     *
     * The 0.5% clip at each end is the same rule Photoshop uses, and it is why a photo with one
     * specular highlight does not get its entire midtone range crushed to compensate for it.
     */
    fun autoContrast(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val histogram = IntArray(256)
        for (pixel in image.pixels) {
            val luma = (0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)).toInt()
            histogram[luma.coerceIn(0, 255)]++
        }
        val total = image.pixels.size
        val clip = (total * 0.005).toInt()
        var low = 0
        while (low < 255 && histogram[low] < clip) low++
        var high = 255
        while (high > 0 && histogram[high] < clip) high--
        if (high - low < 8) return image.copy()
        val table = lut { value ->
            ((value * 255.0 - low) / (high - low)).coerceIn(0.0, 1.0)
        }
        return applyLut(image, selection, table)
    }

    // ── Colour space ──────────────────────────────────────────────────────

    /** Hue 0..1, saturation 0..1, lightness 0..1. */
    fun rgbToHsl(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        if (abs(max - min) < 0.00001) return Triple(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6.0 else 0.0)
            g -> ((b - r) / d + 2.0)
            else -> ((r - g) / d + 4.0)
        } / 6.0
        return Triple(h, s, l)
    }

    fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        if (s < 0.00001) return Triple(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6.0 -> p + (q - p) * 6 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6
                else -> p
            }
        }
        return Triple(hue(h + 1.0 / 3.0), hue(h), hue(h - 1.0 / 3.0))
    }
}

/** Luminance of a straight-alpha pixel, 0..255. */
internal val Rgba.luma: Double get() = 0.299 * r + 0.587 * g + 0.114 * b

/** Blends two packed colours. Used by the swatch and gradient controls on both platforms. */
fun mixColors(a: Int, b: Int, t: Double): Int {
    val pa = Rgba.of(a)
    val pb = Rgba.of(b)
    val tt = t.coerceIn(0.0, 1.0)
    return Rgba(
        pa.r + (pb.r - pa.r) * tt,
        pa.g + (pb.g - pa.g) * tt,
        pa.b + (pb.b - pa.b) * tt,
        pa.a + (pb.a - pa.a) * tt,
    ).packed()
}
