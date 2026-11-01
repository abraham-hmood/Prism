package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The adjustments that need more than one table.
 *
 * [PixAdjustments] covers the pointwise operations that fit a 256-entry lookup. Everything here
 * either works on whole pixels (selective colour, channel mixer), depends on image statistics
 * (auto tone, equalise, match colour) or is a small colour-management operation of its own
 * (3D lookup tables). They are kept in a second object rather than growing the first past the point
 * where its LUT helper still explains most of it.
 */
object PixAdjustmentsExtra {

    private fun map(
        image: PrismImage,
        selection: PixSelection?,
        transform: (Rgba) -> Rgba,
    ): PrismImage {
        val out = image.copy()
        for (i in out.pixels.indices) {
            val coverage = selection?.coverage(i % out.width, i / out.width) ?: 1.0
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

    private fun tableOf(transform: (Double) -> Double): IntArray = IntArray(256) { i ->
        (transform(i / 255.0) * 255.0).coerceIn(0.0, 255.0).toInt()
    }

    // ── Automatic corrections ─────────────────────────────────────────────

    /** Auto Tone: a per-channel 0.5% clip, which is what removes a colour cast as well as haze. */
    fun autoTone(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val total = image.pixels.size
        val clip = (total * 0.005).toInt().coerceAtLeast(1)
        val tables = Array(3) { channel ->
            val histogram = IntArray(256)
            for (pixel in image.pixels) {
                val value = (pixel shr (16 - channel * 8)) and 0xFF
                histogram[value]++
            }
            var low = 0
            var seen = 0
            while (low < 255 && seen < clip) {
                seen += histogram[low]
                low++
            }
            var high = 255
            seen = 0
            while (high > 0 && seen < clip) {
                seen += histogram[high]
                high--
            }
            if (high - low < 4) IntArray(256) { it } else tableOf { value ->
                ((value * 255.0 - low) / (high - low)).coerceIn(0.0, 1.0)
            }
        }
        return map(image, selection) { px ->
            Rgba(
                tables[0][px.r.toInt()].toDouble(),
                tables[1][px.g.toInt()].toDouble(),
                tables[2][px.b.toInt()].toDouble(),
                px.a,
            )
        }
    }

    /** Auto Color: grey-world gains after the tone stretch. */
    fun autoColor(image: PrismImage, selection: PixSelection? = null): PrismImage {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (pixel in image.pixels) {
            sumR += (pixel shr 16) and 0xFF
            sumG += (pixel shr 8) and 0xFF
            sumB += pixel and 0xFF
        }
        val n = image.pixels.size.coerceAtLeast(1)
        val mean = (sumR + sumG + sumB) / (3.0 * n)
        val gainR = if (sumR > 0) mean / (sumR / n) else 1.0
        val gainG = if (sumG > 0) mean / (sumG / n) else 1.0
        val gainB = if (sumB > 0) mean / (sumB / n) else 1.0
        return map(image, selection) { px ->
            Rgba(
                (px.r * gainR).coerceIn(0.0, 255.0),
                (px.g * gainG).coerceIn(0.0, 255.0),
                (px.b * gainB).coerceIn(0.0, 255.0),
                px.a,
            )
        }
    }

    /** Equalize: maps each channel through the composite luminance's cumulative histogram. */
    fun equalize(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val histogram = IntArray(256)
        for (pixel in image.pixels) {
            histogram[(0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) +
                0.114 * (pixel and 0xFF)).toInt().coerceIn(0, 255)]++
        }
        val total = image.pixels.size.coerceAtLeast(1)
        var running = 0
        val cdf = DoubleArray(256)
        for (i in 0..255) {
            running += histogram[i]
            cdf[i] = running.toDouble() / total
        }
        val table = tableOf { value -> cdf[(value * 255).toInt().coerceIn(0, 255)] }
        return map(image, selection) { px ->
            Rgba(
                table[px.r.toInt()].toDouble(),
                table[px.g.toInt()].toDouble(),
                table[px.b.toInt()].toDouble(),
                px.a,
            )
        }
    }

    // ── Colour mixing ─────────────────────────────────────────────────────

    enum class ColorRange(val label: String, val centerHue: Double, val width: Double) {
        REDS("Reds", 0.0, 30.0),
        YELLOWS("Yellows", 60.0, 30.0),
        GREENS("Greens", 120.0, 45.0),
        CYANS("Cyans", 180.0, 40.0),
        BLUES("Blues", 240.0, 45.0),
        MAGENTAS("Magentas", 300.0, 40.0),
        NEUTRALS("Neutrals", 0.0, 360.0),
        BLACKS("Blacks", 0.0, 360.0),
        WHITES("Whites", 0.0, 360.0),
    }

    /**
     * Selective Colour: shifts CMYK ink amounts inside one hue family.
     *
     * A pixel's membership in the family is a soft hue distance, so a red at 20 degrees is still
     * affected by the Reds sliders rather than falling into a gap between families.
     */
    fun selectiveColor(
        image: PrismImage,
        selection: PixSelection? = null,
        range: ColorRange,
        cyan: Double = 0.0,
        magenta: Double = 0.0,
        yellow: Double = 0.0,
        black: Double = 0.0,
        relative: Boolean = true,
    ): PrismImage = map(image, selection) { px ->
        val (hue, saturation, _) = PixAdjustments.rgbToHsl(px.r / 255.0, px.g / 255.0, px.b / 255.0)
        val hueDegrees = hue * 360.0
        val membership = when (range) {
            ColorRange.NEUTRALS -> ((1.0 - saturation) * 1.4).coerceIn(0.0, 1.0)
            ColorRange.BLACKS -> if (px.luma < 64) 1.0 - px.luma / 64.0 else 0.0
            ColorRange.WHITES -> if (px.luma > 191) (px.luma - 191) / 64.0 else 0.0
            else -> {
                if (saturation < 0.08) 0.0 else {
                    var difference = abs(hueDegrees - range.centerHue)
                    if (difference > 180) difference = 360 - difference
                    (1.0 - difference / range.width).coerceIn(0.0, 1.0)
                }
            }
        }
        if (membership <= 0.0) px else {
            val (c, m, y, k) = rgbToCmyk(px.r, px.g, px.b)
            fun shift(ink: Double, amount: Double): Double {
                val delta = amount / 100.0 * membership
                return if (relative) ink * (1.0 + delta) else ink + delta * 0.5
            }
            val (r, g, b) = cmykToRgb(
                shift(c, cyan).coerceIn(0.0, 1.0),
                shift(m, magenta).coerceIn(0.0, 1.0),
                shift(y, yellow).coerceIn(0.0, 1.0),
                shift(k, black).coerceIn(0.0, 1.0),
            )
            Rgba(r * 255.0, g * 255.0, b * 255.0, px.a)
        }
    }

    /** Channel Mixer: nine source percentages plus a constant, or a monochrome mix. */
    fun channelMixer(
        image: PrismImage,
        selection: PixSelection? = null,
        redFromRed: Double = 100.0,
        redFromGreen: Double = 0.0,
        redFromBlue: Double = 0.0,
        greenFromRed: Double = 0.0,
        greenFromGreen: Double = 100.0,
        greenFromBlue: Double = 0.0,
        blueFromRed: Double = 0.0,
        blueFromGreen: Double = 0.0,
        blueFromBlue: Double = 100.0,
        constant: Double = 0.0,
        monochrome: Boolean = false,
    ): PrismImage = map(image, selection) { px ->
        fun mix(ra: Double, rb: Double, rc: Double): Double =
            (px.r * ra + px.g * rb + px.b * rc) / 100.0 + constant

        if (monochrome) {
            val grey = mix(redFromRed, redFromGreen, redFromBlue).coerceIn(0.0, 255.0)
            Rgba(grey, grey, grey, px.a)
        } else {
            Rgba(
                mix(redFromRed, redFromGreen, redFromBlue).coerceIn(0.0, 255.0),
                mix(greenFromRed, greenFromGreen, greenFromBlue).coerceIn(0.0, 255.0),
                mix(blueFromRed, blueFromGreen, blueFromBlue).coerceIn(0.0, 255.0),
                px.a,
            )
        }
    }

    /** Replace Colour: paints a new HSL into everything close to [target]. */
    fun replaceColor(
        image: PrismImage,
        selection: PixSelection? = null,
        target: Int,
        fuzziness: Int = 40,
        hueShift: Double = 0.0,
        saturation: Double = 0.0,
        lightness: Double = 0.0,
    ): PrismImage {
        val tr = ((target shr 16) and 0xFF).toDouble()
        val tg = ((target shr 8) and 0xFF).toDouble()
        val tb = (target and 0xFF).toDouble()
        val limit = fuzziness.coerceIn(0, 100) / 100.0 * 441.7
        return map(image, selection) { px ->
            val distance = sqrt((px.r - tr) * (px.r - tr) + (px.g - tg) * (px.g - tg) + (px.b - tb) * (px.b - tb))
            val membership = (1.0 - distance / limit.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            if (membership <= 0.0) px else {
                val (h, s, l) = PixAdjustments.rgbToHsl(px.r / 255.0, px.g / 255.0, px.b / 255.0)
                val newH = (h + hueShift / 360.0 + 1.0) % 1.0
                val newS = (s * (1.0 + saturation / 100.0)).coerceIn(0.0, 1.0)
                val newL = if (lightness >= 0) l + (1.0 - l) * lightness / 100.0
                else l * (1.0 + lightness / 100.0)
                val (r, g, b) = PixAdjustments.hslToRgb(newH, newS, newL.coerceIn(0.0, 1.0))
                Rgba(
                    px.r + (r * 255.0 - px.r) * membership,
                    px.g + (g * 255.0 - px.g) * membership,
                    px.b + (b * 255.0 - px.b) * membership,
                    px.a,
                )
            }
        }
    }

    /** Match Colour: transfers the source's per-channel mean and deviation onto the target. */
    fun matchColor(
        image: PrismImage,
        source: PrismImage,
        luminance: Double = 100.0,
        colorIntensity: Double = 100.0,
        fade: Double = 0.0,
        neutralize: Boolean = false,
    ): PrismImage {
        fun stats(img: PrismImage): Pair<DoubleArray, DoubleArray> {
            val mean = DoubleArray(3)
            val deviation = DoubleArray(3)
            val n = img.pixels.size.coerceAtLeast(1)
            for (pixel in img.pixels) {
                mean[0] += (pixel shr 16) and 0xFF
                mean[1] += (pixel shr 8) and 0xFF
                mean[2] += pixel and 0xFF
            }
            for (i in 0..2) mean[i] /= n
            for (pixel in img.pixels) {
                val c = intArrayOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
                for (i in 0..2) deviation[i] += (c[i] - mean[i]) * (c[i] - mean[i])
            }
            for (i in 0..2) deviation[i] = sqrt(deviation[i] / n).coerceAtLeast(0.001)
            return mean to deviation
        }
        val (sourceMean, sourceDev) = stats(source)
        val (targetMean, targetDev) = stats(image)
        val amount = 1.0 - fade.coerceIn(0.0, 100.0) / 100.0
        val color = colorIntensity.coerceIn(0.0, 200.0) / 100.0
        val luma = luminance.coerceIn(0.0, 200.0) / 100.0
        return map(image, null) { px ->
            val original = doubleArrayOf(px.r, px.g, px.b)
            val out = DoubleArray(3)
            for (i in 0..2) {
                val scale = 1.0 + (sourceDev[i] / targetDev[i] - 1.0) * luma * 0.5
                val shift = (sourceMean[i] - targetMean[i]) * color
                out[i] = (original[i] * scale + shift)
            }
            if (neutralize) {
                val mean = (out[0] + out[1] + out[2]) / 3.0
                for (i in 0..2) out[i] = out[i] + (mean - out[i]) * 0.5
            }
            Rgba(
                px.r + (out[0].coerceIn(0.0, 255.0) - px.r) * amount,
                px.g + (out[1].coerceIn(0.0, 255.0) - px.g) * amount,
                px.b + (out[2].coerceIn(0.0, 255.0) - px.b) * amount,
                px.a,
            )
        }
    }

    // ── Tone mapping ──────────────────────────────────────────────────────

    /**
     * HDR Toning: local adaptation.
     *
     * The luminance is split into a blurred base and the detail above it. Compressing the base and
     * adding the detail back is what stops a tone-mapped sky from turning into flat grey while
     * keeping the texture in the foreground.
     */
    fun hdrToning(
        image: PrismImage,
        selection: PixSelection? = null,
        exposure: Double = 0.0,
        detail: Double = 50.0,
        shadow: Double = 0.0,
        highlight: Double = 0.0,
        vibrance: Double = 0.0,
        saturation: Double = 0.0,
        gamma: Double = 1.0,
    ): PrismImage {
        val blurred = PixFilters.gaussianBlur(image, null, (image.width / 80).coerceIn(2, 24))
        val gain = 2.0.pow(exposure.coerceIn(-5.0, 5.0))
        val detailAmount = detail.coerceIn(0.0, 100.0) / 100.0
        val shadowAmount = shadow.coerceIn(-100.0, 100.0) / 100.0
        val highlightAmount = highlight.coerceIn(-100.0, 100.0) / 100.0
        val g = gamma.coerceIn(0.2, 5.0)
        val out = image.copy()
        for (i in out.pixels.indices) {
            val coverage = selection?.coverage(i % out.width, i / out.width) ?: 1.0
            if (coverage <= 0.0) continue
            val px = Rgba.of(image.pixels[i])
            val blur = Rgba.of(blurred.pixels[i])
            val base = blur.luma.coerceAtLeast(1.0)
            val local = px.luma / base
            val mappedBase = 255.0 * (base / 255.0).pow(1.0 / g) * gain
            val compressed = mappedBase +
                (255.0 - mappedBase) * highlightAmount * 0.35 -
                mappedBase * shadowAmount * 0.25
            val detailFactor = local * detailAmount + (1.0 - detailAmount)
            val targetLuma = (compressed * detailFactor).coerceIn(0.0, 255.0)
            val ratio = if (px.luma > 0.001) targetLuma / px.luma else 1.0
            var r = (px.r * ratio).coerceIn(0.0, 255.0)
            var gg = (px.g * ratio).coerceIn(0.0, 255.0)
            var b = (px.b * ratio).coerceIn(0.0, 255.0)
            val grey = 0.299 * r + 0.587 * gg + 0.114 * b
            val sat = (1.0 + saturation / 100.0).coerceAtLeast(0.0)
            r = grey + (r - grey) * sat
            gg = grey + (gg - grey) * sat
            b = grey + (b - grey) * sat
            val vibranceFactor = 1.0 + vibrance.coerceIn(-100.0, 100.0) / 100.0 * 0.5
            r = grey + (r - grey) * vibranceFactor
            gg = grey + (gg - grey) * vibranceFactor
            b = grey + (b - grey) * vibranceFactor
            val changed = Rgba(r.coerceIn(0.0, 255.0), gg.coerceIn(0.0, 255.0), b.coerceIn(0.0, 255.0), px.a)
            out.pixels[i] = if (coverage >= 1.0) changed.packed() else Rgba(
                px.r + (changed.r - px.r) * coverage,
                px.g + (changed.g - px.g) * coverage,
                px.b + (changed.b - px.b) * coverage,
                px.a,
            ).packed()
        }
        return out
    }

    /** The full Shadows/Highlights dialog: amount, tonal width, radius and the colour correction. */
    fun shadowsHighlightsFull(
        image: PrismImage,
        selection: PixSelection? = null,
        shadowAmount: Double = 0.0,
        shadowTone: Double = 50.0,
        shadowRadius: Int = 30,
        highlightAmount: Double = 0.0,
        highlightTone: Double = 50.0,
        highlightRadius: Int = 30,
        colorCorrection: Double = 0.0,
        midtoneContrast: Double = 0.0,
        blackClip: Double = 0.0,
        whiteClip: Double = 0.0,
    ): PrismImage {
        val width = (shadowTone.coerceIn(1.0, 100.0) / 100.0)
        val highWidth = (highlightTone.coerceIn(1.0, 100.0) / 100.0)
        val blurRadius = max(shadowRadius, highlightRadius).coerceIn(1, 120)
        val blurred = PixFilters.gaussianBlur(image, null, blurRadius)
        val shadowGain = shadowAmount.coerceIn(-100.0, 100.0) / 100.0
        val highGain = highlightAmount.coerceIn(-100.0, 100.0) / 100.0
        val contrast = midtoneContrast.coerceIn(-100.0, 100.0) / 100.0
        val clipLow = blackClip.coerceIn(0.0, 10.0) / 100.0
        val clipHigh = whiteClip.coerceIn(0.0, 10.0) / 100.0
        return map(image, selection) { px ->
            val luma = px.luma / 255.0
            val shadowWeight = (1.0 - luma / width.coerceAtLeast(0.01)).coerceIn(0.0, 1.0).pow(2.0)
            val highWeight = ((luma - (1.0 - highWidth)) / highWidth.coerceAtLeast(0.01)).coerceIn(0.0, 1.0).pow(2.0)
            var r = px.r
            var g = px.g
            var b = px.b
            val lift = shadowGain * shadowWeight * 60.0
            r += lift
            g += lift
            b += lift
            val recover = highGain * highWeight * 60.0
            r -= recover
            g -= recover
            b -= recover
            val grey = (r + g + b) / 3.0
            val cc = colorCorrection.coerceIn(-100.0, 100.0) / 100.0 * 0.25
            r += (r - grey) * cc
            g += (g - grey) * cc
            b += (b - grey) * cc
            val mid = (luma - 0.5) * contrast * 2.0 * 255.0
            r += mid
            g += mid
            b += mid
            fun clip(value: Double): Double {
                val low = clipLow * 255.0
                val high = 255.0 - clipHigh * 255.0
                return ((value - low) / (high - low).coerceAtLeast(1.0) * 255.0).coerceIn(0.0, 255.0)
            }
            Rgba(clip(r), clip(g), clip(b), px.a)
        }
    }

    /** Duotone / tritone: three inks interpolated by luminance. */
    fun duotone(image: PrismImage, selection: PixSelection? = null, dark: Int, mid: Int, light: Int): PrismImage =
        gradientMapStops(image, selection, listOf(0.0 to dark, 0.5 to mid, 1.0 to light))

    /** A multi-stop gradient map. Stops outside 0..1 are clamped; the list may be unsorted. */
    fun gradientMapStops(
        image: PrismImage,
        selection: PixSelection? = null,
        stops: List<Pair<Double, Int>>,
    ): PrismImage {
        if (stops.isEmpty()) return image
        val sorted = stops.sortedBy { it.first }
        return map(image, selection) { px ->
            val t = (px.luma / 255.0).coerceIn(0.0, 1.0)
            val right = sorted.firstOrNull { it.first >= t } ?: sorted.last()
            val left = sorted.lastOrNull { it.first <= t } ?: sorted.first()
            val color = if (right.first <= left.first) left.second else {
                val f = (t - left.first) / (right.first - left.first)
                mixColors(left.second, right.second, f)
            }
            Rgba.of(color).copy(a = px.a)
        }
    }

    /** Temperature, tint, vibrance, saturation and hue in one layer, as the 2026 adjustment. */
    fun colorAndVibrance(
        image: PrismImage,
        selection: PixSelection? = null,
        temperature: Double = 0.0,
        tint: Double = 0.0,
        vibrance: Double = 0.0,
        saturation: Double = 0.0,
        hue: Double = 0.0,
    ): PrismImage {
        val warmed = PixAdjustments.temperature(image, selection, temperature, tint)
        val vivid = PixAdjustments.vibrance(warmed, selection, vibrance)
        return PixAdjustments.hueSaturation(vivid, selection, hue, saturation, 0.0)
    }

    /** Colour Balance with the preserve-luminosity option. */
    fun colorBalancePreserveLuminosity(
        image: PrismImage,
        selection: PixSelection? = null,
        shadowR: Double = 0.0, shadowG: Double = 0.0, shadowB: Double = 0.0,
        midR: Double = 0.0, midG: Double = 0.0, midB: Double = 0.0,
        highR: Double = 0.0, highG: Double = 0.0, highB: Double = 0.0,
    ): PrismImage {
        val balanced = PixAdjustments.colorBalance(
            image, selection, shadowR, shadowG, shadowB, midR, midG, midB, highR, highG, highB,
        )
        val coverage = selection
        val out = image.copy()
        for (i in out.pixels.indices) {
            val amount = coverage?.coverage(i % out.width, i / out.width) ?: 1.0
            if (amount <= 0.0) continue
            val original = Rgba.of(image.pixels[i])
            val changed = Rgba.of(balanced.pixels[i])
            val delta = original.luma - changed.luma
            val corrected = Rgba(
                (changed.r + delta).coerceIn(0.0, 255.0),
                (changed.g + delta).coerceIn(0.0, 255.0),
                (changed.b + delta).coerceIn(0.0, 255.0),
                original.a,
            )
            out.pixels[i] = if (amount >= 1.0) corrected.packed() else Rgba(
                original.r + (corrected.r - original.r) * amount,
                original.g + (corrected.g - original.g) * amount,
                original.b + (corrected.b - original.b) * amount,
                original.a,
            ).packed()
        }
        return out
    }

    // ── Lookup tables ─────────────────────────────────────────────────────

    enum class LookupPreset(val label: String) {
        WARM("Warm"),
        COOL("Cool"),
        CINEMATIC("Cinematic"),
        SEPIA("Sepia"),
        NOIR("Noir"),
        VINTAGE("Vintage"),
        TEAL_ORANGE("Teal & Orange"),
        CROSS_PROCESS("Cross Process"),
        NEGATIVE("Negative"),
        MATTE("Matte"),
    }

    fun colorLookup(image: PrismImage, selection: PixSelection? = null, preset: LookupPreset): PrismImage =
        map(image, selection) { px ->
            val r = px.r / 255.0
            val g = px.g / 255.0
            val b = px.b / 255.0
            val (nr, ng, nb) = when (preset) {
                LookupPreset.WARM -> Triple(
                    (r * 1.08 + 0.03).coerceIn(0.0, 1.0),
                    (g * 1.02 + 0.01).coerceIn(0.0, 1.0),
                    (b * 0.90).coerceIn(0.0, 1.0),
                )
                LookupPreset.COOL -> Triple(
                    (r * 0.92).coerceIn(0.0, 1.0),
                    (g * 1.00 + 0.01).coerceIn(0.0, 1.0),
                    (b * 1.10 + 0.03).coerceIn(0.0, 1.0),
                )
                LookupPreset.CINEMATIC -> Triple(
                    ((r - 0.5) * 1.12 + 0.5 + 0.02).coerceIn(0.0, 1.0),
                    ((g - 0.5) * 1.02 + 0.5).coerceIn(0.0, 1.0),
                    ((b - 0.5) * 0.92 + 0.5 + 0.03).coerceIn(0.0, 1.0),
                )
                LookupPreset.SEPIA -> Triple(
                    (r * 0.393 + g * 0.769 + b * 0.189).coerceIn(0.0, 1.0),
                    (r * 0.349 + g * 0.686 + b * 0.168).coerceIn(0.0, 1.0),
                    (r * 0.272 + g * 0.534 + b * 0.131).coerceIn(0.0, 1.0),
                )
                LookupPreset.NOIR -> {
                    val grey = (r * 0.299 + g * 0.587 + b * 0.114)
                    val contrast = ((grey - 0.5) * 1.5 + 0.5).coerceIn(0.0, 1.0)
                    Triple(contrast, contrast, contrast)
                }
                LookupPreset.VINTAGE -> Triple(
                    (r * 0.95 + 0.06).coerceIn(0.0, 1.0),
                    (g * 0.92 + 0.05).coerceIn(0.0, 1.0),
                    (b * 0.80 + 0.04).coerceIn(0.0, 1.0),
                )
                LookupPreset.TEAL_ORANGE -> {
                    val luma = r * 0.299 + g * 0.587 + b * 0.114
                    Triple(
                        (luma + (r - luma) * 1.25 + 0.05).coerceIn(0.0, 1.0),
                        (luma + (g - luma) * 1.0).coerceIn(0.0, 1.0),
                        (luma + (b - luma) * 0.85 - 0.02).coerceIn(0.0, 1.0),
                    )
                }
                LookupPreset.CROSS_PROCESS -> Triple(
                    ((r - 0.5) * 1.3 + 0.52).coerceIn(0.0, 1.0),
                    ((g - 0.5) * 1.1 + 0.48).coerceIn(0.0, 1.0),
                    ((b - 0.5) * 1.4 + 0.47).coerceIn(0.0, 1.0),
                )
                LookupPreset.NEGATIVE -> Triple(1.0 - r, 1.0 - g, 1.0 - b)
                LookupPreset.MATTE -> {
                    fun lift(v: Double) = (0.06 + v * 0.90).coerceIn(0.0, 1.0)
                    Triple(lift(r), lift(g), lift(b))
                }
            }
            Rgba(nr * 255.0, ng * 255.0, nb * 255.0, px.a)
        }

    /**
     * A parsed .cube 3D LUT.
     *
     * The format stores an n×n×n lattice in blue-fastest order. Trilinear interpolation between the
     * eight neighbours is what every colour application does for a LUT this size; nearest-neighbour
     * would band visibly across a sky.
     */
    class CubeLut(val size: Int, val values: FloatArray) {
        fun lookup(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            val scale = (size - 1).toDouble()
            val x = (r.coerceIn(0.0, 1.0) * scale)
            val y = (g.coerceIn(0.0, 1.0) * scale)
            val z = (b.coerceIn(0.0, 1.0) * scale)
            val x0 = x.toInt().coerceIn(0, size - 1)
            val y0 = y.toInt().coerceIn(0, size - 1)
            val z0 = z.toInt().coerceIn(0, size - 1)
            val x1 = (x0 + 1).coerceAtMost(size - 1)
            val y1 = (y0 + 1).coerceAtMost(size - 1)
            val z1 = (z0 + 1).coerceAtMost(size - 1)
            val fx = x - x0
            val fy = y - y0
            val fz = z - z0
            fun at(ix: Int, iy: Int, iz: Int, channel: Int): Double {
                val index = ((iz * size + iy) * size + ix) * 3 + channel
                return values.getOrElse(index) { 0f }.toDouble()
            }
            fun sample(channel: Int): Double {
                var result = 0.0
                for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
                    val weightX = if (ix == 0) 1 - fx else fx
                    val weightY = if (iy == 0) 1 - fy else fy
                    val weightZ = if (iz == 0) 1 - fz else fz
                    result += at(
                        if (ix == 0) x0 else x1,
                        if (iy == 0) y0 else y1,
                        if (iz == 0) z0 else z1,
                        channel,
                    ) * weightX * weightY * weightZ
                }
                return result * 255.0
            }
            return Triple(sample(0), sample(1), sample(2))
        }
    }

    fun parseCube(text: String): CubeLut? {
        var size = 0
        val values = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("LUT_3D_SIZE", ignoreCase = true)) {
                size = line.substringAfter(' ').trim().toIntOrNull() ?: 0
            } else if (line[0].isDigit() || line[0] == '-' || line[0] == '.') {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    for (i in 0..2) values.add(parts[i].toFloatOrNull() ?: 0f)
                }
            }
        }
        if (size < 2 || values.size < size * size * size * 3) return null
        return CubeLut(size, values.toFloatArray())
    }

    fun colorLookupCube(image: PrismImage, selection: PixSelection? = null, lut: CubeLut): PrismImage =
        map(image, selection) { px ->
            val (r, g, b) = lut.lookup(px.r / 255.0, px.g / 255.0, px.b / 255.0)
            Rgba(r, g, b, px.a)
        }

    // ── Colour-space helpers ──────────────────────────────────────────────

    fun rgbToCmyk(r: Double, g: Double, b: Double): DoubleArray {
        val rr = r / 255.0
        val gg = g / 255.0
        val bb = b / 255.0
        val k = 1.0 - max(rr, max(gg, bb))
        if (k >= 0.9999) return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val denominator = 1.0 - k
        return doubleArrayOf(
            (1.0 - rr - k) / denominator,
            (1.0 - gg - k) / denominator,
            (1.0 - bb - k) / denominator,
            k,
        )
    }

    fun cmykToRgb(c: Double, m: Double, y: Double, k: Double): Triple<Double, Double, Double> =
        Triple(
            (1.0 - c) * (1.0 - k),
            (1.0 - m) * (1.0 - k),
            (1.0 - y) * (1.0 - k),
        )

    /** CIE Lab, D65. Used by the desktop renderer's soft proofing. */
    fun rgbToLab(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        fun linear(v: Double): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val x = (lr * 0.4124 + lg * 0.3576 + lb * 0.1805) / 0.95047
        val y = (lr * 0.2126 + lg * 0.7152 + lb * 0.0722)
        val z = (lr * 0.0193 + lg * 0.1192 + lb * 0.9505) / 1.08883
        fun f(t: Double): Double =
            if (t > 0.008856) cbrt(t) else (7.787 * t + 16.0 / 116.0)
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun labToRgb(l: Double, a: Double, bb: Double): Triple<Double, Double, Double> {
        val fy = (l + 16) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - bb / 200.0
        fun inverse(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 0.008856) t3 else (t - 16.0 / 116.0) / 7.787
        }
        val x = inverse(fx) * 0.95047
        val y = inverse(fy)
        val z = inverse(fz) * 1.08883
        var r = x * 3.2406 + y * -1.5372 + z * -0.4986
        var g = x * -0.9689 + y * 1.8758 + z * 0.0415
        var b = x * 0.0557 + y * -0.2040 + z * 1.0570
        fun encode(v: Double): Double {
            val c = v.coerceIn(0.0, 1.0)
            return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        }
        r = encode(r)
        g = encode(g)
        b = encode(b)
        return Triple(r * 255.0, g * 255.0, b * 255.0)
    }

    /** Scale a colour's chroma in Lab; the soft-proof gamut warning uses it too. */
    fun chromaScale(image: PrismImage, selection: PixSelection? = null, scale: Double): PrismImage =
        map(image, selection) { px ->
            val (l, a, b) = rgbToLab(px.r, px.g, px.b)
            val (r, g, bb) = labToRgb(l, a * scale, b * scale)
            Rgba(r, g, bb, px.a)
        }

    /** Mean and standard deviation per channel; exposed for the Histogram panel. */
    fun channelStats(image: PrismImage): Array<DoubleArray> {
        val mean = DoubleArray(3)
        val deviation = DoubleArray(3)
        val n = image.pixels.size.coerceAtLeast(1)
        for (pixel in image.pixels) {
            mean[0] += (pixel shr 16) and 0xFF
            mean[1] += (pixel shr 8) and 0xFF
            mean[2] += pixel and 0xFF
        }
        for (i in 0..2) mean[i] /= n
        for (pixel in image.pixels) {
            val channels = intArrayOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
            for (i in 0..2) deviation[i] += (channels[i] - mean[i]) * (channels[i] - mean[i])
        }
        for (i in 0..2) deviation[i] = sqrt(deviation[i] / n)
        return arrayOf(mean, deviation)
    }

    /** Min/max per channel, for Levels' auto buttons. */
    fun channelRange(image: PrismImage): IntArray {
        val low = intArrayOf(255, 255, 255)
        val high = intArrayOf(0, 0, 0)
        for (pixel in image.pixels) {
            for (c in 0..2) {
                val value = (pixel shr (16 - c * 8)) and 0xFF
                low[c] = min(low[c], value)
                high[c] = max(high[c], value)
            }
        }
        return intArrayOf(low[0], low[1], low[2], high[0], high[1], high[2])
    }
}
