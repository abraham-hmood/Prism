package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filters: the operations that read a neighbourhood.
 *
 * ## Blur is premultiplied, everything else is not
 *
 * Averaging straight-alpha colours turns the transparent black around a cut-out into a dark halo,
 * because transparent pixels drag the average toward zero. A blur therefore accumulates
 * alpha-weighted sums and divides at the end; the other filters sample a neighbourhood and blend the
 * result back through the selection mask, where the same problem does not arise.
 *
 * ## Masked, not clipped
 *
 * Every filter takes the same optional [PixSelection] and blends the filtered pixel with the original
 * by the mask's coverage. That is what lets a blur be applied to one feathered region without a seam.
 */
object PixFilters {

    // ── Shared helpers ────────────────────────────────────────────────────

    private fun applyMasked(original: PrismImage, filtered: PrismImage, selection: PixSelection?): PrismImage {
        if (selection == null) return filtered
        val out = original.copy()
        for (i in out.pixels.indices) {
            val x = i % out.width
            val y = i / out.width
            val coverage = selection.coverage(x, y)
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

    /**
     * A separable box blur, premultiplied.
     *
     * Used three times to approximate a Gaussian: the central limit theorem does the work, and the
     * cost stays linear in pixels rather than quadratic in the radius. Three passes is the standard
     * number because a second is visibly boxy and a fourth is imperceptible.
     */
    private fun boxBlurOnce(image: PrismImage, radius: Int): PrismImage {
        val r = radius.coerceIn(1, 200)
        val width = image.width
        val height = image.height
        val horizontal = IntArray(image.pixels.size)

        // Premultiplied accumulation, horizontal pass.
        for (y in 0 until height) {
            val row = y * width
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (i in -r..r) {
                val px = if (i in 0 until width) image.pixels[row + i] else 0
                val a = (px ushr 24) and 0xFF
                sumA += a.toLong()
                sumR += (((px shr 16) and 0xFF) * a).toLong()
                sumG += (((px shr 8) and 0xFF) * a).toLong()
                sumB += ((px and 0xFF) * a).toLong()
            }
            val window = 2 * r + 1
            for (x in 0 until width) {
                horizontal[row + x] = pack(sumA, sumR, sumG, sumB, window)
                val leaving = x - r
                val entering = x + r + 1
                if (leaving >= 0) {
                    val px = image.pixels[row + leaving]
                    val a = (px ushr 24) and 0xFF
                    sumA -= a
                    sumR -= (((px shr 16) and 0xFF) * a).toLong()
                    sumG -= (((px shr 8) and 0xFF) * a).toLong()
                    sumB -= ((px and 0xFF) * a).toLong()
                }
                if (entering < width) {
                    val px = image.pixels[row + entering]
                    val a = (px ushr 24) and 0xFF
                    sumA += a
                    sumR += (((px shr 16) and 0xFF) * a).toLong()
                    sumG += (((px shr 8) and 0xFF) * a).toLong()
                    sumB += ((px and 0xFF) * a).toLong()
                }
            }
        }

        val out = IntArray(image.pixels.size)
        for (x in 0 until width) {
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (i in -r..r) {
                val px = if (i in 0 until height) horizontal[i * width + x] else 0
                val a = (px ushr 24) and 0xFF
                sumA += a.toLong()
                sumR += (((px shr 16) and 0xFF) * a).toLong()
                sumG += (((px shr 8) and 0xFF) * a).toLong()
                sumB += ((px and 0xFF) * a).toLong()
            }
            val window = 2 * r + 1
            for (y in 0 until height) {
                out[y * width + x] = pack(sumA, sumR, sumG, sumB, window)
                val leaving = y - r
                val entering = y + r + 1
                if (leaving >= 0) {
                    val px = horizontal[leaving * width + x]
                    val a = (px ushr 24) and 0xFF
                    sumA -= a
                    sumR -= (((px shr 16) and 0xFF) * a).toLong()
                    sumG -= (((px shr 8) and 0xFF) * a).toLong()
                    sumB -= ((px and 0xFF) * a).toLong()
                }
                if (entering < height) {
                    val px = horizontal[entering * width + x]
                    val a = (px ushr 24) and 0xFF
                    sumA += a
                    sumR += (((px shr 16) and 0xFF) * a).toLong()
                    sumG += (((px shr 8) and 0xFF) * a).toLong()
                    sumB += ((px and 0xFF) * a).toLong()
                }
            }
        }
        return PrismImage(image.width, image.height, out)
    }

    private fun pack(sumA: Long, sumR: Long, sumG: Long, sumB: Long, window: Int): Int {
        if (sumA <= 0L) return 0
        val a = (sumA / window).toInt().coerceIn(0, 255)
        val r = (sumR / sumA).toInt().coerceIn(0, 255)
        val g = (sumG / sumA).toInt().coerceIn(0, 255)
        val b = (sumB / sumA).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun boxBlur(image: PrismImage, selection: PixSelection? = null, radius: Int): PrismImage =
        applyMasked(image, boxBlurOnce(image, radius), selection)

    fun gaussianBlur(image: PrismImage, selection: PixSelection? = null, radius: Int): PrismImage {
        if (radius <= 0) return image.copy()
        // Three boxes whose radii sum to the requested one, so the total energy is unchanged.
        val each = max(1, Math.round(radius / 3.0).toInt())
        var current = image
        repeat(3) { current = boxBlurOnce(current, each) }
        return applyMasked(image, current, selection)
    }

    /** Directional blur along [degrees]. */
    fun motionBlur(image: PrismImage, selection: PixSelection? = null, degrees: Double, distance: Int): PrismImage {
        val steps = distance.coerceIn(1, 200)
        val radians = Math.toRadians(degrees)
        val dx = cos(radians)
        val dy = sin(radians)
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var sumA = 0.0
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var count = 0
            for (i in -steps..steps) {
                val sx = (x + dx * i).toInt()
                val sy = (y + dy * i).toInt()
                if (sx < 0 || sy < 0 || sx >= image.width || sy >= image.height) continue
                val px = Rgba.of(image.pixels[sy * image.width + sx])
                sumA += px.a
                sumR += px.r * px.a
                sumG += px.g * px.a
                sumB += px.b * px.a
                count++
            }
            out[y * image.width + x] = if (count == 0 || sumA <= 0) 0 else {
                val a = (sumA / count).toInt().coerceIn(0, 255)
                Rgba(sumR / sumA, sumG / sumA, sumB / sumA, a.toDouble()).packed()
            }
        }
        return applyMasked(image, PrismImage(image.width, image.height, out), selection)
    }

    // ── Focus ─────────────────────────────────────────────────────────────

    /** Unsharp mask: the difference between the image and a blur of it, added back. */
    fun unsharpMask(
        image: PrismImage,
        selection: PixSelection? = null,
        radius: Int = 2,
        amount: Double = 100.0,
        threshold: Int = 0,
    ): PrismImage {
        val blurred = gaussianBlur(image, null, radius.coerceAtLeast(1))
        val strength = amount.coerceIn(0.0, 500.0) / 100.0
        val out = image.copy()
        for (i in out.pixels.indices) {
            val src = Rgba.of(image.pixels[i])
            val blur = Rgba.of(blurred.pixels[i])
            val diff = maxOf(abs(src.r - blur.r), abs(src.g - blur.g), abs(src.b - blur.b))
            if (diff < threshold) continue
            out.pixels[i] = Rgba(
                (src.r + (src.r - blur.r) * strength).coerceIn(0.0, 255.0),
                (src.g + (src.g - blur.g) * strength).coerceIn(0.0, 255.0),
                (src.b + (src.b - blur.b) * strength).coerceIn(0.0, 255.0),
                src.a,
            ).packed()
        }
        return applyMasked(image, out, selection)
    }

    fun sharpen(image: PrismImage, selection: PixSelection? = null, amount: Double = 100.0): PrismImage =
        unsharpMask(image, selection, radius = 1, amount = amount / 2.0)

    /**
     * Median: the noise filter.
     *
     * A true median needs a sort per pixel per channel. For radii this small the sample count is at
     * most a few hundred, and a counting sort over 256 buckets beats allocating and sorting a list
     * on every pixel.
     */
    fun median(image: PrismImage, selection: PixSelection? = null, radius: Int = 1): PrismImage {
        val r = radius.coerceIn(1, 8)
        val out = IntArray(image.pixels.size)
        val buckets = IntArray(256)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var count = 0
            fun medianOf(shift: Int): Int {
                buckets.fill(0)
                count = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val sx = (x + dx).coerceIn(0, image.width - 1)
                    val sy = (y + dy).coerceIn(0, image.height - 1)
                    val value = (image.pixels[sy * image.width + sx] shr shift) and 0xFF
                    buckets[value]++
                    count++
                }
                var seen = 0
                val target = count / 2
                for (value in 0..255) {
                    seen += buckets[value]
                    if (seen > target) return value
                }
                return 128
            }
            val a = medianOf(24)
            val red = medianOf(16)
            val green = medianOf(8)
            val blue = medianOf(0)
            out[y * image.width + x] = (a shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return applyMasked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** Maximum filter: expands light areas (Photoshop's Maximum). */
    fun maximum(image: PrismImage, selection: PixSelection? = null, radius: Int = 1): PrismImage =
        morphology(image, selection, radius, dilate = true)

    /** Minimum filter: expands dark areas. */
    fun minimum(image: PrismImage, selection: PixSelection? = null, radius: Int = 1): PrismImage =
        morphology(image, selection, radius, dilate = false)

    private fun morphology(image: PrismImage, selection: PixSelection?, radius: Int, dilate: Boolean): PrismImage {
        val r = radius.coerceIn(1, 16)
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var best = if (dilate) 0 else 255
            var bestPixel = 0
            for (dy in -r..r) for (dx in -r..r) {
                val sx = (x + dx).coerceIn(0, image.width - 1)
                val sy = (y + dy).coerceIn(0, image.height - 1)
                val px = image.pixels[sy * image.width + sx]
                val luma = (0.299 * ((px shr 16) and 0xFF) + 0.587 * ((px shr 8) and 0xFF) + 0.114 * (px and 0xFF)).toInt()
                if ((dilate && luma > best) || (!dilate && luma < best)) {
                    best = luma
                    bestPixel = px
                }
            }
            out[y * image.width + x] = bestPixel
        }
        return applyMasked(image, PrismImage(image.width, image.height, out), selection)
    }

    // ── Noise and texture ─────────────────────────────────────────────────

    /**
     * Add Noise, deterministic.
     *
     * Seeded from the pixel index rather than a generator with mutable state, so adding noise twice
     * gives the same grain -- which is what makes an undo and a redo agree.
     */
    fun addNoise(image: PrismImage, selection: PixSelection? = null, amount: Double = 12.0, monochrome: Boolean = false, seed: Long = 1L): PrismImage {
        val out = image.copy()
        var state = seed * 6364136223846793005L + 1442695040888963407L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) / 2147483648.0) - 0.5
        }
        val strength = amount.coerceIn(0.0, 100.0) / 100.0 * 255.0
        for (i in out.pixels.indices) {
            val px = Rgba.of(out.pixels[i])
            if (px.a <= 0.0) continue
            val mono = next()
            val redJitter = if (monochrome) mono else next()
            val greenJitter = if (monochrome) mono else next()
            val blueJitter = if (monochrome) mono else next()
            out.pixels[i] = Rgba(
                (px.r + redJitter * strength).coerceIn(0.0, 255.0),
                (px.g + greenJitter * strength).coerceIn(0.0, 255.0),
                (px.b + blueJitter * strength).coerceIn(0.0, 255.0),
                px.a,
            ).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** One pass of the classic emboss convolution. */
    fun emboss(image: PrismImage, selection: PixSelection? = null, angle: Double = 135.0, height: Int = 3): PrismImage {
        val radians = Math.toRadians(angle)
        val dx = (cos(radians) * height).toInt().coerceIn(-16, 16)
        val dy = (sin(radians) * height).toInt().coerceIn(-16, 16)
        val out = image.copy()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val a = Rgba.of(image.pixels[y * image.width + x])
            val bx = (x + dx).coerceIn(0, image.width - 1)
            val by = (y + dy).coerceIn(0, image.height - 1)
            val b = Rgba.of(image.pixels[by * image.width + bx])
            val value = ((a.luma - b.luma) + 128.0).coerceIn(0.0, 255.0)
            out.pixels[y * image.width + x] = Rgba(value, value, value, a.a).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** Sobel edge detection; produces a high-contrast line drawing. */
    fun findEdges(image: PrismImage, selection: PixSelection? = null, strength: Double = 1.0): PrismImage {
        val out = image.copy()
        fun lumaAt(x: Int, y: Int): Double {
            val sx = x.coerceIn(0, image.width - 1)
            val sy = y.coerceIn(0, image.height - 1)
            val p = image.pixels[sy * image.width + sx]
            return 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val gx = -lumaAt(x - 1, y - 1) - 2 * lumaAt(x - 1, y) - lumaAt(x - 1, y + 1) +
                lumaAt(x + 1, y - 1) + 2 * lumaAt(x + 1, y) + lumaAt(x + 1, y + 1)
            val gy = -lumaAt(x - 1, y - 1) - 2 * lumaAt(x, y - 1) - lumaAt(x + 1, y - 1) +
                lumaAt(x - 1, y + 1) + 2 * lumaAt(x, y + 1) + lumaAt(x + 1, y + 1)
            val magnitude = (sqrt(gx * gx + gy * gy) * strength).coerceIn(0.0, 255.0)
            val alpha = (image.pixels[y * image.width + x] ushr 24) and 0xFF
            out.pixels[y * image.width + x] = (alpha shl 24) or
                (magnitude.toInt() shl 16) or (magnitude.toInt() shl 8) or magnitude.toInt()
        }
        return applyMasked(image, out, selection)
    }

    /** High Pass: a mid-grey image containing only the detail, for the classic sharpening workflow. */
    fun highPass(image: PrismImage, selection: PixSelection? = null, radius: Int = 4): PrismImage {
        val blurred = gaussianBlur(image, null, radius.coerceAtLeast(1))
        val out = image.copy()
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(blurred.pixels[i])
            out.pixels[i] = Rgba(
                (128 + a.r - b.r).coerceIn(0.0, 255.0),
                (128 + a.g - b.g).coerceIn(0.0, 255.0),
                (128 + a.b - b.b).coerceIn(0.0, 255.0),
                a.a,
            ).packed()
        }
        return applyMasked(image, out, selection)
    }

    // ── Pixel and stylise ─────────────────────────────────────────────────

    fun mosaic(image: PrismImage, selection: PixSelection? = null, block: Int = 8): PrismImage {
        val size = block.coerceIn(2, 128)
        val out = image.copy()
        for (by in 0 until image.height step size) for (bx in 0 until image.width step size) {
            var sumA = 0.0
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var count = 0
            for (y in by until min(by + size, image.height)) for (x in bx until min(bx + size, image.width)) {
                val px = Rgba.of(image.pixels[y * image.width + x])
                sumA += px.a
                sumR += px.r * px.a
                sumG += px.g * px.a
                sumB += px.b * px.a
                count++
            }
            if (count == 0 || sumA <= 0) continue
            val packed = Rgba(sumR / sumA, sumG / sumA, sumB / sumA, sumA / count).packed()
            for (y in by until min(by + size, image.height)) for (x in bx until min(bx + size, image.width)) {
                out.pixels[y * image.width + x] = packed
            }
        }
        return applyMasked(image, out, selection)
    }

    /**
     * Oil Paint: replaces each pixel with the most common colour band in its neighbourhood.
     *
     * This is the algorithm the real filter uses, not a "posterise then blur" approximation, and it
     * is why the result has flat daubs with real edges rather than soft smears.
     */
    fun oilPaint(image: PrismImage, selection: PixSelection? = null, radius: Int = 4, levels: Int = 24): PrismImage {
        val r = radius.coerceIn(1, 12)
        val bands = levels.coerceIn(4, 64)
        val out = image.copy()
        val intensity = IntArray(bands)
        val reds = IntArray(bands)
        val greens = IntArray(bands)
        val blues = IntArray(bands)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            intensity.fill(0)
            reds.fill(0)
            greens.fill(0)
            blues.fill(0)
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val sx = (x + dx).coerceIn(0, image.width - 1)
                val sy = (y + dy).coerceIn(0, image.height - 1)
                val px = Rgba.of(image.pixels[sy * image.width + sx])
                val luma = px.luma.toInt().coerceIn(0, 255)
                val band = (luma * (bands - 1) / 255).coerceIn(0, bands - 1)
                intensity[band]++
                reds[band] += px.r.toInt()
                greens[band] += px.g.toInt()
                blues[band] += px.b.toInt()
            }
            var best = 0
            for (i in 1 until bands) if (intensity[i] > intensity[best]) best = i
            val count = intensity[best]
            val original = image.pixels[y * image.width + x]
            if (count <= 0) continue
            out.pixels[y * image.width + x] = (original ushr 24 shl 24) or
                ((reds[best] / count).coerceIn(0, 255) shl 16) or
                ((greens[best] / count).coerceIn(0, 255) shl 8) or
                (blues[best] / count).coerceIn(0, 255)
        }
        return applyMasked(image, out, selection)
    }

    fun solarize(image: PrismImage, selection: PixSelection? = null): PrismImage {
        val out = image.copy()
        for (i in out.pixels.indices) {
            val px = Rgba.of(out.pixels[i])
            fun invert(value: Double) = if (value > 127.5) 255.0 - value else value
            out.pixels[i] = Rgba(invert(px.r), invert(px.g), invert(px.b), px.a).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** A vignette that follows the image's aspect ratio rather than a circle in pixel space. */
    fun vignette(image: PrismImage, selection: PixSelection? = null, amount: Double = 50.0, radius: Double = 60.0): PrismImage {
        val strength = amount.coerceIn(0.0, 100.0) / 100.0
        val inner = radius.coerceIn(0.0, 100.0) / 100.0 * 0.5
        val out = image.copy()
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxDistance = sqrt(cx * cx + cy * cy)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = (x - cx) / maxDistance
            val dy = (y - cy) / maxDistance
            val distance = sqrt(dx * dx + dy * dy)
            val falloff = ((distance - inner * 2) / (1.0 - inner * 2)).coerceIn(0.0, 1.0)
            val factor = 1.0 - falloff * strength
            val px = Rgba.of(image.pixels[y * image.width + x])
            out.pixels[y * image.width + x] = Rgba(px.r * factor, px.g * factor, px.b * factor, px.a).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** Diffuse Glow: a blurred, lightened copy screened over the original. */
    fun diffuseGlow(image: PrismImage, selection: PixSelection? = null, radius: Int = 8, strength: Double = 60.0): PrismImage {
        val blurred = gaussianBlur(image, null, radius.coerceAtLeast(1))
        val out = image.copy()
        val amount = strength.coerceIn(0.0, 100.0) / 100.0
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(blurred.pixels[i])
            fun glow(src: Double, blur: Double): Double {
                val lifted = blur + (255.0 - blur) * 0.25
                return (src + (lifted - src) * amount).coerceIn(0.0, 255.0)
            }
            out.pixels[i] = Rgba(glow(a.r, b.r), glow(a.g, b.g), glow(a.b, b.b), a.a).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** Offsets the whole image, wrapping around the edges. */
    fun offset(image: PrismImage, selection: PixSelection? = null, dx: Int, dy: Int): PrismImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val sx = Math.floorMod(x - dx, image.width)
            val sy = Math.floorMod(y - dy, image.height)
            out[y * image.width + x] = image.pixels[sy * image.width + sx]
        }
        return applyMasked(image, PrismImage(image.width, image.height, out), selection)
    }

    /** The average colour of a region: the Info panel and the eyedropper's 5×5 sample both use it. */
    fun averageColor(image: PrismImage, x: Int, y: Int, radius: Int = 2): Int {
        var sumA = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val sx = (x + dx).coerceIn(0, image.width - 1)
            val sy = (y + dy).coerceIn(0, image.height - 1)
            val px = Rgba.of(image.pixels[sy * image.width + sx])
            sumA += px.a
            sumR += px.r
            sumG += px.g
            sumB += px.b
            count++
        }
        if (count == 0) return 0
        return Rgba(sumR / count, sumG / count, sumB / count, sumA / count).packed()
    }

    /** A histogram of luminance, for the Levels dialog. */
    fun luminanceHistogram(image: PrismImage, buckets: Int = 256): IntArray {
        val histogram = IntArray(buckets)
        for (pixel in image.pixels) {
            val luma = (0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF))
            histogram[(luma.toInt() * buckets / 256).coerceIn(0, buckets - 1)]++
        }
        return histogram
    }

    /** Used by the Levels dialog's auto button and by Auto Tone. */
    fun histogramRange(image: PrismImage): IntRange {
        val histogram = luminanceHistogram(image)
        var low = 0
        while (low < 255 && histogram[low] == 0) low++
        var high = 255
        while (high > 0 && histogram[high] == 0) high--
        return low..high
    }

    /** Renders a soft radial highlight; the Lens Flare stand-in this editor ships with. */
    fun lensFlare(image: PrismImage, selection: PixSelection? = null, x: Int, y: Int, size: Int = 120, color: Int = 0xFFFFFFFF.toInt()): PrismImage {
        val radius = size.coerceIn(4, 2000) / 2.0
        val out = image.copy()
        val cr = ((color shr 16) and 0xFF).toDouble()
        val cg = ((color shr 8) and 0xFF).toDouble()
        val cb = (color and 0xFF).toDouble()
        for (py in max(0, (y - radius).toInt()) until min(image.height, (y + radius).toInt() + 1)) {
            for (px in max(0, (x - radius).toInt()) until min(image.width, (x + radius).toInt() + 1)) {
                val dx = px - x
                val dy = py - y
                val distance = sqrt(dx * dx + dy * dy.toDouble())
                if (distance > radius) continue
                val falloff = (1.0 - distance / radius).let { it * it }
                val base = Rgba.of(image.pixels[py * image.width + px])
                out.pixels[py * image.width + px] = Rgba(
                    base.r + (cr - base.r) * falloff * 0.8,
                    base.g + (cg - base.g) * falloff * 0.8,
                    base.b + (cb - base.b) * falloff * 0.8,
                    base.a,
                ).packed()
            }
        }
        return applyMasked(image, out, selection)
    }

    /** A cheap bloom: screen a small, brightened blur back over the image. */
    fun bloom(image: PrismImage, selection: PixSelection? = null, radius: Int = 12, strength: Double = 40.0): PrismImage {
        val blurred = gaussianBlur(image, null, radius.coerceAtLeast(1))
        val out = image.copy()
        val amount = strength.coerceIn(0.0, 100.0) / 100.0
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val b = Rgba.of(blurred.pixels[i])
            fun screen(src: Double, blur: Double): Double {
                val bright = (blur - 96.0).coerceAtLeast(0.0)
                return src + (255.0 - src) * (bright / 255.0) * amount
            }
            out.pixels[i] = Rgba(
                screen(a.r, b.r).coerceIn(0.0, 255.0),
                screen(a.g, b.g).coerceIn(0.0, 255.0),
                screen(a.b, b.b).coerceIn(0.0, 255.0),
                a.a,
            ).packed()
        }
        return applyMasked(image, out, selection)
    }

    /** Pixel-art downscale-and-upscale: the Pixelate filter with nearest-neighbour edges. */
    fun pixelate(image: PrismImage, selection: PixSelection? = null, size: Int = 6): PrismImage {
        val block = size.coerceIn(2, 64)
        val out = image.copy()
        for (by in 0 until image.height step block) for (bx in 0 until image.width step block) {
            val sampleX = min(bx + block / 2, image.width - 1)
            val sampleY = min(by + block / 2, image.height - 1)
            val packed = image.pixels[sampleY * image.width + sampleX]
            for (y in by until min(by + block, image.height)) {
                for (x in bx until min(bx + block, image.width)) {
                    out.pixels[y * image.width + x] = packed
                }
            }
        }
        return applyMasked(image, out, selection)
    }

}
