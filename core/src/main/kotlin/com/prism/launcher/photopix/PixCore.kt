package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The arithmetic every PhotoPix feature is made of.
 *
 * ## Straight alpha, not premultiplied
 *
 * `PrismImage` is packed ARGB with straight alpha, which is what Android's `Bitmap` decodes to and
 * what `ImageIO` produces. Working in the same space means no conversion at the boundary, and the
 * price -- a divide in every composite -- is paid once per pixel of a canvas that is at most a few
 * megapixels. Keeping a second premultiplied representation would double memory for the editor's
 * largest structure, the layer stack.
 *
 * ## Blend modes are functions, not branches
 *
 * [BlendMode] carries its own formula so a new mode is one entry rather than a new `when` arm in
 * three places. The separable modes operate per channel; the four non-separable modes (hue,
 * saturation, colour, luminosity) need the whole pixel and are handled by [blendPixel].
 */
enum class BlendMode(val label: String) {
    NORMAL("Normal"),
    DISSOLVE("Dissolve"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    COLOR_DODGE("Color Dodge"),
    COLOR_BURN("Color Burn"),
    HARD_LIGHT("Hard Light"),
    SOFT_LIGHT("Soft Light"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    ADD("Linear Dodge (Add)"),
    SUBTRACT("Subtract"),
    HUE("Hue"),
    SATURATION("Saturation"),
    COLOR("Color"),
    LUMINOSITY("Luminosity");

    val separable: Boolean
        get() = this != HUE && this != SATURATION && this != COLOR && this != LUMINOSITY

    /** Separable formula, on 0..255 channels. */
    fun channel(base: Double, blend: Double): Double = when (this) {
        NORMAL, DISSOLVE -> blend
        MULTIPLY -> base * blend / 255.0
        SCREEN -> 255.0 - (255.0 - base) * (255.0 - blend) / 255.0
        OVERLAY -> if (base <= 127.5) 2 * base * blend / 255.0
        else 255.0 - 2 * (255.0 - base) * (255.0 - blend) / 255.0
        DARKEN -> minOf(base, blend)
        LIGHTEN -> maxOf(base, blend)
        COLOR_DODGE -> if (blend >= 255.0) 255.0 else minOf(255.0, base * 255.0 / (255.0 - blend))
        COLOR_BURN -> if (blend <= 0.0) 0.0 else 255.0 - minOf(255.0, (255.0 - base) * 255.0 / blend)
        HARD_LIGHT -> if (blend <= 127.5) 2 * base * blend / 255.0
        else 255.0 - 2 * (255.0 - base) * (255.0 - blend) / 255.0
        SOFT_LIGHT -> softLight(base, blend)
        DIFFERENCE -> abs(base - blend)
        EXCLUSION -> base + blend - 2 * base * blend / 255.0
        ADD -> minOf(255.0, base + blend)
        SUBTRACT -> maxOf(0.0, base - blend)
        else -> blend
    }

    /**
     * Soft light, written the way Photoshop documents it.
     *
     * The reference implementation is a piecewise function with a square-root lobe; writing it as a
     * single expression above is where every soft-light bug in every editor comes from, so the real
     * formula lives here and [channel] delegates to this function through the `else` branch.
     */
    private fun softLight(base: Double, blend: Double): Double {
        val b = base / 255.0
        val s = blend / 255.0
        val result = if (s <= 0.5) {
            b - (1.0 - 2.0 * s) * b * (1.0 - b)
        } else {
            val d = if (b <= 0.25) ((16.0 * b - 12.0) * b + 4.0) * b else Math.sqrt(b)
            b + (2.0 * s - 1.0) * (d - b)
        }
        return (result * 255.0).coerceIn(0.0, 255.0)
    }

    /** Helper for the non-separable modes: luminance of a pixel. */
    fun luma(r: Double, g: Double, b: Double): Double = 0.299 * r + 0.587 * g + 0.114 * b
}

/** One pixel, in doubles so a chain of operations does not quantise at every step. */
internal data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) {

    fun packed(): Int {
        val alpha = a.coerceIn(0.0, 255.0).toInt()
        val red = r.coerceIn(0.0, 255.0).toInt()
        val green = g.coerceIn(0.0, 255.0).toInt()
        val blue = b.coerceIn(0.0, 255.0).toInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    companion object {
        fun of(pixel: Int): Rgba = Rgba(
            ((pixel shr 16) and 0xFF).toDouble(),
            ((pixel shr 8) and 0xFF).toDouble(),
            (pixel and 0xFF).toDouble(),
            ((pixel ushr 24) and 0xFF).toDouble(),
        )
    }
}

/** The blend of one source pixel over one base pixel, returned in 0..255 straight ARGB. */
internal fun blendPixel(base: Rgba, source: Rgba, mode: BlendMode): Rgba {
    if (mode.separable && mode != BlendMode.DISSOLVE) {
        return Rgba(
            r = mode.channel(base.r, source.r),
            g = mode.channel(base.g, source.g),
            b = mode.channel(base.b, source.b),
            a = source.a,
        )
    }
    if (mode == BlendMode.DISSOLVE) {
        // A deterministic dither rather than a random one: the same image must dissolve the same
        // way twice, or an undo would not restore what the user saw.
        val threshold = ((source.r * 7919 + source.g * 104729 + source.b * 1299709).toLong() % 255).toInt()
        val keep = source.a * threshold / 255.0
        return source.copy(a = keep)
    }

    val baseLuma = mode.luma(base.r, base.g, base.b)
    val sourceLuma = mode.luma(source.r, source.g, source.b)
    val result = when (mode) {
        BlendMode.HUE -> setLum(clipColor(source.r, source.g, source.b), baseLuma)
        BlendMode.SATURATION -> setLum(setSat(clipColor(base.r, base.g, base.b), satOf(source.r, source.g, source.b)), baseLuma)
        BlendMode.COLOR -> setLum(clipColor(source.r, source.g, source.b), baseLuma)
        BlendMode.LUMINOSITY -> setLum(clipColor(base.r, base.g, base.b), sourceLuma)
        else -> Triple(source.r, source.g, source.b)
    }
    return Rgba(result.first, result.second, result.third, source.a)
}

private fun satOf(r: Double, g: Double, b: Double): Double = maxOf(r, g, b) - minOf(r, g, b)

private fun clipColor(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
    val l = 0.299 * r + 0.587 * g + 0.114 * b
    val n = minOf(r, g, b)
    val x = maxOf(r, g, b)
    var rr = r; var gg = g; var bb = b
    if (n < 0.0) {
        val denominator = l - n
        if (denominator > 0.0001) {
            rr = l + (rr - l) * l / denominator
            gg = l + (gg - l) * l / denominator
            bb = l + (bb - l) * l / denominator
        }
    }
    if (x > 255.0) {
        val denominator = x - l
        if (denominator > 0.0001) {
            rr = l + (rr - l) * (255.0 - l) / denominator
            gg = l + (gg - l) * (255.0 - l) / denominator
            bb = l + (bb - l) * (255.0 - l) / denominator
        }
    }
    return Triple(rr, gg, bb)
}

private fun setLum(rgb: Triple<Double, Double, Double>, luma: Double): Triple<Double, Double, Double> {
    val delta = luma - (0.299 * rgb.first + 0.587 * rgb.second + 0.114 * rgb.third)
    return Triple(rgb.first + delta, rgb.second + delta, rgb.third + delta).let { clipColor(it.first, it.second, it.third) }
}

private fun setSat(rgb: Triple<Double, Double, Double>, saturation: Double): Triple<Double, Double, Double> {
    val (r, g, b) = rgb
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (max <= min) return rgb
    fun adjust(c: Double) = (c - min) * saturation / (max - min)
    return Triple(adjust(r), adjust(g), adjust(b))
}

/** Straight-alpha source-over of [source] onto [base] with [mode], at [opacity] 0..1. */
internal fun compositePixel(base: Rgba, source: Rgba, mode: BlendMode, opacity: Double): Rgba {
    val alpha = (source.a / 255.0) * opacity.coerceIn(0.0, 1.0)
    if (alpha <= 0.0) return base
    val blended = blendPixel(base, source, mode)
    val baseAlpha = base.a / 255.0
    val outAlpha = alpha + baseAlpha * (1.0 - alpha)
    if (outAlpha <= 0.0) return Rgba(0.0, 0.0, 0.0, 0.0)
    // Porter-Duff source-over with a blend function: the source contributes the BLENDED colour,
    // the base contributes its own, scaled by how much of it the source covered. Written out
    // literally so it is checkable.
    fun over(baseChannel: Double, blendedChannel: Double): Double =
        (blendedChannel * alpha + baseChannel * baseAlpha * (1.0 - alpha)) / outAlpha

    return Rgba(
        r = over(base.r, blended.r),
        g = over(base.g, blended.g),
        b = over(base.b, blended.b),
        a = outAlpha * 255.0,
    )
}

object PixImageOps {

    /**
     * Composites [source] onto [base] in place.
     *
     * [offsetX]/[offsetY] are the source's position relative to the base canvas, which is how a
     * layer smaller than the document is placed.
     */
    fun draw(
        base: PrismImage,
        source: PrismImage,
        offsetX: Int = 0,
        offsetY: Int = 0,
        mode: BlendMode = BlendMode.NORMAL,
        opacity: Double = 1.0,
    ) {
        val target = Rgba(0.0, 0.0, 0.0, 0.0)
        for (y in 0 until source.height) {
            val ty = y + offsetY
            if (ty < 0 || ty >= base.height) continue
            for (x in 0 until source.width) {
                val tx = x + offsetX
                if (tx < 0 || tx >= base.width) continue
                val src = Rgba.of(source.pixels[y * source.width + x])
                if (src.a <= 0.0) continue
                val index = ty * base.width + tx
                val dst = if (base.pixels[index] ushr 24 == 0) target else Rgba.of(base.pixels[index])
                base.pixels[index] = compositePixel(dst, src, mode, opacity).packed()
            }
        }
    }

    fun flipHorizontal(image: PrismImage): PrismImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            val row = y * image.width
            for (x in 0 until image.width) out[row + x] = image.pixels[row + (image.width - 1 - x)]
        }
        return PrismImage(image.width, image.height, out)
    }

    fun flipVertical(image: PrismImage): PrismImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            val src = (image.height - 1 - y) * image.width
            System.arraycopy(image.pixels, src, out, y * image.width, image.width)
        }
        return PrismImage(image.width, image.height, out)
    }

    fun rotate90(image: PrismImage, clockwise: Boolean = true): PrismImage {
        val w = image.height
        val h = image.width
        val out = IntArray(w * h)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val nx = if (clockwise) image.height - 1 - y else y
            val ny = if (clockwise) x else image.width - 1 - x
            out[ny * w + nx] = image.pixels[y * image.width + x]
        }
        return PrismImage(w, h, out)
    }

    fun rotate180(image: PrismImage): PrismImage {
        val out = IntArray(image.pixels.size)
        val last = image.pixels.size - 1
        for (i in image.pixels.indices) out[last - i] = image.pixels[i]
        return PrismImage(image.width, image.height, out)
    }

    /** Bilinear scale. Used for both Image Size and free transform. */
    fun scaled(image: PrismImage, width: Int, height: Int): PrismImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w == image.width && h == image.height) return image.copy()
        val out = IntArray(w * h)
        val xRatio = (image.width - 1).toDouble() / w
        val yRatio = (image.height - 1).toDouble() / h
        for (y in 0 until h) {
            val sy = y * yRatio
            val y0 = floor(sy).toInt().coerceIn(0, image.height - 1)
            val y1 = (y0 + 1).coerceAtMost(image.height - 1)
            val fy = sy - y0
            for (x in 0 until w) {
                val sx = x * xRatio
                val x0 = floor(sx).toInt().coerceIn(0, image.width - 1)
                val x1 = (x0 + 1).coerceAtMost(image.width - 1)
                val fx = sx - x0
                val p00 = image.pixels[y0 * image.width + x0]
                val p10 = image.pixels[y0 * image.width + x1]
                val p01 = image.pixels[y1 * image.width + x0]
                val p11 = image.pixels[y1 * image.width + x1]
                fun channel(shift: Int): Int {
                    fun c(p: Int) = (p shr shift) and 0xFF
                    val top = c(p00) * (1 - fx) + c(p10) * fx
                    val bottom = c(p01) * (1 - fx) + c(p11) * fx
                    return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
                }
                out[y * w + x] = (channel(24) shl 24) or (channel(16) shl 16) or
                    (channel(8) shl 8) or channel(0)
            }
        }
        return PrismImage(w, h, out)
    }

    /** Rotates by an arbitrary angle, expanding nothing: content outside the frame is lost. */
    fun rotated(image: PrismImage, degrees: Double, background: Int = 0): PrismImage {
        val radians = Math.toRadians(degrees % 360.0)
        if (abs(radians) < 0.000001) return image.copy()
        val cosA = cos(radians)
        val sinA = sin(radians)
        val out = IntArray(image.pixels.size)
        val cx = (image.width - 1) / 2.0
        val cy = (image.height - 1) / 2.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = x - cx
            val dy = y - cy
            val sx = cosA * dx + sinA * dy + cx
            val sy = -sinA * dx + cosA * dy + cy
            out[y * image.width + x] = sampleBilinear(image, sx, sy) ?: background
        }
        return PrismImage(image.width, image.height, out)
    }

    /** Bilinear lookup at a fractional coordinate; null when outside the image. */
    fun sampleBilinear(image: PrismImage, x: Double, y: Double): Int? {
        if (x < 0 || y < 0 || x > image.width - 1 || y > image.height - 1) return null
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = (x0 + 1).coerceAtMost(image.width - 1)
        val y1 = (y0 + 1).coerceAtMost(image.height - 1)
        val fx = x - x0
        val fy = y - y0
        val p00 = image.pixels[y0 * image.width + x0]
        val p10 = image.pixels[y0 * image.width + x1]
        val p01 = image.pixels[y1 * image.width + x0]
        val p11 = image.pixels[y1 * image.width + x1]
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            fun c(p: Int) = (p shr shift) and 0xFF
            val top = c(p00) * (1 - fx) + c(p10) * fx
            val bottom = c(p01) * (1 - fx) + c(p11) * fx
            val value = (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }

    fun cropped(image: PrismImage, x: Int, y: Int, width: Int, height: Int): PrismImage {
        val x0 = x.coerceIn(0, image.width - 1)
        val y0 = y.coerceIn(0, image.height - 1)
        val w = width.coerceIn(1, image.width - x0)
        val h = height.coerceIn(1, image.height - y0)
        val out = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(image.pixels, (y0 + row) * image.width + x0, out, row * w, w)
        }
        return PrismImage(w, h, out)
    }

    /** Resizes the canvas without resampling; [offsetX]/[offsetY] place the old image in the new. */
    fun padded(image: PrismImage, width: Int, height: Int, offsetX: Int, offsetY: Int, fill: Int = 0): PrismImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val out = IntArray(w * h)
        if (fill != 0) for (i in out.indices) out[i] = fill
        for (y in 0 until image.height) {
            val ty = y + offsetY
            if (ty < 0 || ty >= h) continue
            for (x in 0 until image.width) {
                val tx = x + offsetX
                if (tx < 0 || tx >= w) continue
                val src = image.pixels[y * image.width + x]
                val index = ty * w + tx
                val alpha = (src ushr 24) and 0xFF
                out[index] = if (alpha == 255) src else compositePixel(Rgba.of(out[index]), Rgba.of(src), BlendMode.NORMAL, 1.0).packed()
            }
        }
        return PrismImage(w, h, out)
    }

    /** Copies [source] into [target] at an offset, clipped. Used by paste and clone. */
    fun blit(target: PrismImage, source: PrismImage, offsetX: Int, offsetY: Int) {
        for (y in 0 until source.height) {
            val ty = y + offsetY
            if (ty < 0 || ty >= target.height) continue
            for (x in 0 until source.width) {
                val tx = x + offsetX
                if (tx < 0 || tx >= target.width) continue
                val src = source.pixels[y * source.width + x]
                if ((src ushr 24 and 0xFF) == 0) continue
                val index = ty * target.width + tx
                target.pixels[index] = compositePixel(
                    Rgba.of(target.pixels[index]), Rgba.of(src), BlendMode.NORMAL, 1.0,
                ).packed()
            }
        }
    }

    /**
     * The smallest rectangle containing every non-transparent pixel, or null for an empty image.
     *
     * Trim and auto-crop both need it, and both used to walk the buffer separately.
     */
    fun contentBounds(image: PrismImage): IntArray? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.pixels[y * image.width + x] ushr 24) != 0) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** A thumbnail that keeps the aspect ratio inside a square of [size]. */
    fun thumbnail(image: PrismImage, size: Int): PrismImage {
        val scale = minOf(size.toDouble() / image.width, size.toDouble() / image.height)
        val w = ceil(image.width * scale).toInt().coerceAtLeast(1)
        val h = ceil(image.height * scale).toInt().coerceAtLeast(1)
        return scaled(image, w, h)
    }
}
