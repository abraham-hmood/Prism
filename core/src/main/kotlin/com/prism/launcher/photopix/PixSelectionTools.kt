package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Select menu beyond the four marquee shapes.
 *
 * The AI-labelled commands -- Sky, Subject, Focus Area -- are deliberately implemented as image
 * heuristics rather than pretending to run a model. They are useful on ordinary photographs, they
 * are deterministic, and they cost nothing; a real segmentation network belongs behind the platform
 * bridge, not inside a pure Kotlin core.
 */
object PixSelectionTools {

    /** Modify > Border: the band between the selection's outside and inside edges. */
    fun border(selection: PixSelection, width: Int): PixSelection {
        val outer = selection.expanded(width.coerceIn(1, 100))
        val inner = selection.expanded(-width.coerceIn(1, 100))
        return outer.combine(inner, PixSelection.Mode.SUBTRACT)
    }

    /** Modify > Smooth: rounds corners by blurring the mask and re-thresholding it. */
    fun smooth(selection: PixSelection, radius: Int): PixSelection {
        if (radius <= 0) return selection.copy()
        val softened = selection.feathered(radius.coerceIn(1, 64))
        val out = ByteArray(softened.mask.size)
        for (i in out.indices) out[i] = if ((softened.mask[i].toInt() and 0xFF) >= 128) 255.toByte() else 0
        return PixSelection(selection.width, selection.height, out)
    }

    /**
     * Grow: expands the selection into neighbouring pixels whose colour is close to the selection's
     * mean. This is the boundary-following behaviour of Select > Grow, not a dilation.
     */
    fun grow(selection: PixSelection, image: PrismImage, tolerance: Int = 24): PixSelection {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (selection.coverage(x, y) <= 0.0) continue
            val p = image.pixels[y * image.width + x]
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
            count++
        }
        if (count == 0) return selection.copy()
        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        val limit = tolerance.coerceIn(0, 100) / 100.0 * 441.7
        val out = selection.mask.copyOf()
        val queue = ArrayDeque<Int>()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (selection.coverage(x, y) > 0.0) queue.add(y * image.width + x)
        }
        val visited = BooleanArray(out.size) { (out[it].toInt() and 0xFF) > 0 }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % image.width
            val y = index / image.width
            for (neighbour in intArrayOf(index - 1, index + 1, index - image.width, index + image.width)) {
                if (neighbour !in 0 until out.size || visited[neighbour]) continue
                val nx = neighbour % image.width
                val ny = neighbour / image.width
                if (abs(nx - x) + abs(ny - y) != 1) continue
                visited[neighbour] = true
                val p = image.pixels[neighbour]
                val dr = ((p shr 16) and 0xFF) - meanR
                val dg = ((p shr 8) and 0xFF) - meanG
                val db = (p and 0xFF) - meanB
                val distance = sqrt(dr * dr + dg * dg + db * db)
                if (distance <= limit) {
                    out[neighbour] = 255.toByte()
                    queue.add(neighbour)
                }
            }
        }
        return PixSelection(selection.width, selection.height, out)
    }

    /** Select > Similar: the global version of the wand, seeded by the selection's first pixel. */
    fun similar(
        image: PrismImage,
        selection: PixSelection,
        tolerance: Int = 24,
    ): PixSelection {
        val bounds = selection.bounds() ?: return selection.copy()
        return PixSelection.wand(image, bounds[0], bounds[1], tolerance, contiguous = false, antialias = true)
            .let { wand ->
                // A wand at the bounding corner may hit a transparent pixel; fall back to the
                // selection's own average when that happens.
                val stats = colourStats(image, selection)
                if (wand.isEmpty) colorRange(image, stats, tolerance) else wand
            }
    }

    private fun colourStats(image: PrismImage, selection: PixSelection): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (selection.coverage(x, y) <= 0.0) continue
            val p = image.pixels[y * image.width + x]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        if (n == 0) return 0xFF808080.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Colour Range: every pixel within a colour distance of [target]. */
    fun colorRange(image: PrismImage, target: Int, fuzziness: Int = 40): PixSelection {
        val tr = ((target shr 16) and 0xFF).toDouble()
        val tg = ((target shr 8) and 0xFF).toDouble()
        val tb = (target and 0xFF).toDouble()
        val limit = fuzziness.coerceIn(0, 100) / 100.0 * 441.7
        val out = ByteArray(image.pixels.size)
        for (i in image.pixels.indices) {
            val p = image.pixels[i]
            val dr = ((p shr 16) and 0xFF) - tr
            val dg = ((p shr 8) and 0xFF) - tg
            val db = (p and 0xFF) - tb
            val distance = sqrt(dr * dr + dg * dg + db * db)
            if (distance <= limit) out[i] = 255.toByte()
        }
        return PixSelection(image.width, image.height, out)
    }

    /** Select > Sky: a connected region grown from the top edge through sky-like colours. */
    fun sky(image: PrismImage, tolerance: Int = 32): PixSelection {
        val out = ByteArray(image.pixels.size)
        val queue = ArrayDeque<Int>()
        val visited = BooleanArray(image.pixels.size)
        val maxY = (image.height * 0.75).toInt()
        for (x in 0 until image.width) {
            val index = x
            if (visited[index]) continue
            visited[index] = true
            val p = image.pixels[index]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            if (b >= r - 10 && luma > 60) {
                out[index] = 255.toByte()
                queue.add(index)
            }
        }
        val limit = tolerance.coerceIn(4, 100).toDouble() * 2.4
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % image.width
            val y = index / image.width
            val centre = image.pixels[index]
            for (neighbour in intArrayOf(index - 1, index + 1, index - image.width, index + image.width)) {
                if (neighbour !in 0 until out.size || visited[neighbour]) continue
                val nx = neighbour % image.width
                val ny = neighbour / image.width
                if (abs(nx - x) + abs(ny - y) != 1 || ny > maxY) continue
                visited[neighbour] = true
                val p = image.pixels[neighbour]
                val dr = ((p shr 16) and 0xFF) - ((centre shr 16) and 0xFF)
                val dg = ((p shr 8) and 0xFF) - ((centre shr 8) and 0xFF)
                val db = (p and 0xFF) - (centre and 0xFF)
                if (sqrt((dr * dr + dg * dg + db * db).toDouble()) <= limit) {
                    out[neighbour] = 255.toByte()
                    queue.add(neighbour)
                }
            }
        }
        if (out.none { it.toInt() != 0 }) {
            val fallback = (image.height * 0.33).toInt()
            for (y in 0 until fallback) for (x in 0 until image.width) out[y * image.width + x] = 255.toByte()
        }
        return PixSelection(image.width, image.height, out).feathered(2)
    }

    /**
     * Select > Subject: separates the centre from the border by colour distance.
     *
     * The border ring is assumed to be background. Pixels far from its mean are candidates; Otsu's
     * threshold picks the cut, and a centre weighting keeps a similarly coloured object at the edge
     * from winning over the actual subject.
     */
    fun subject(image: PrismImage): PixSelection {
        val border = max(1, min(image.width, image.height) / 16)
        var sumR = 0.0
        var sumG = 0.0
        var sumBlue = 0.0
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val onBorder = x < border || y < border || x >= image.width - border || y >= image.height - border
            if (!onBorder) continue
            val p = image.pixels[y * image.width + x]
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumBlue += p and 0xFF
            count++
        }
        if (count == 0) return PixSelection.all(image.width, image.height)
        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumBlue / count
        val score = DoubleArray(image.pixels.size)
        val histogram = IntArray(256)
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val index = y * image.width + x
            val p = image.pixels[index]
            val dr = ((p shr 16) and 0xFF) - meanR
            val dg = ((p shr 8) and 0xFF) - meanG
            val db = (p and 0xFF) - meanB
            val distance = sqrt(dr * dr + dg * dg + db * db)
            val centreWeight = 1.0 - (sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) /
                sqrt(cx * cx + cy * cy).coerceAtLeast(1.0)) * 0.35
            score[index] = distance * centreWeight
            histogram[(distance / 441.7 * 255).toInt().coerceIn(0, 255)]++
        }
        // Otsu on the distance histogram.
        val total = image.pixels.size
        var sum = 0.0
        for (i in 0..255) sum += i * histogram[i]
        var sumB = 0.0
        var weightB = 0
        var best = 0.0
        var threshold = 128
        for (i in 0..255) {
            weightB += histogram[i]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += i * histogram[i]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        val cut = threshold / 255.0 * 441.7 * 0.8
        val out = ByteArray(image.pixels.size)
        for (i in out.indices) out[i] = if (score[i] >= cut) 255.toByte() else 0
        // A flat border and a flat subject give Otsu nothing to separate; a centred oval is a
        // more useful starting point than an empty marquee.
        if (out.none { it.toInt() != 0 }) {
            return PixSelection.ellipse(
                image.width, image.height,
                (image.width * 0.12).toInt(), (image.height * 0.12).toInt(),
                (image.width * 0.76).toInt(), (image.height * 0.76).toInt(),
            ).feathered(2)
        }
        return smootheAndFeather(PixSelection(image.width, image.height, out), 1, 2)
    }

    /** Focus Area: keeps the sharp pixels by local Laplacian magnitude. */
    fun focusArea(image: PrismImage, inFocusRange: Int = 50): PixSelection {
        val sharpness = DoubleArray(image.pixels.size)
        var maximum = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            fun l(px: Int, py: Int): Double {
                val p = image.pixels[py.coerceIn(0, image.height - 1) * image.width + px.coerceIn(0, image.width - 1)]
                return 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            val value = abs(4 * l(x, y) - l(x - 1, y) - l(x + 1, y) - l(x, y - 1) - l(x, y + 1))
            sharpness[y * image.width + x] = value
            maximum = max(maximum, value)
        }
        val limit = maximum * (1.0 - inFocusRange.coerceIn(0, 100) / 100.0) * 0.6
        val out = ByteArray(image.pixels.size)
        for (i in out.indices) out[i] = if (sharpness[i] >= limit) 255.toByte() else 0
        return smootheAndFeather(PixSelection(image.width, image.height, out), 2, 1)
    }

    private fun smootheAndFeather(selection: PixSelection, smoothRadius: Int, feather: Int): PixSelection {
        var current = selection
        if (smoothRadius > 0) current = smooth(current, smoothRadius + 1)
        if (feather > 0) current = current.feathered(feather)
        return current
    }

    /** Save Selection stores the mask as an alpha channel; this is the inverse. */
    fun fromAlpha(width: Int, height: Int, alpha: ByteArray): PixSelection =
        PixSelection(width, height, alpha.copyOf())

    /** A channel's bytes for the Channels panel. */
    fun toAlpha(selection: PixSelection): ByteArray = selection.mask.copyOf()

    /** Transform Selection: maps the mask through the same affine parameters as Free Transform. */
    fun transformed(
        selection: PixSelection,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
        rotation: Double = 0.0,
        translateX: Double = 0.0,
        translateY: Double = 0.0,
    ): PixSelection {
        val radians = Math.toRadians(rotation)
        val cosA = kotlin.math.cos(radians)
        val sinA = kotlin.math.sin(radians)
        val cx = selection.width / 2.0
        val cy = selection.height / 2.0
        val out = ByteArray(selection.mask.size)
        for (y in 0 until selection.height) for (x in 0 until selection.width) {
            val dx = (x - cx - translateX) / scaleX
            val dy = (y - cy - translateY) / scaleY
            val sx = cx + dx * cosA + dy * sinA
            val sy = cy - dx * sinA + dy * cosA
            out[y * selection.width + x] = selection.at(sx.toInt(), sy.toInt()).toByte()
        }
        return PixSelection(selection.width, selection.height, out)
    }

    /** Intersect two selections; a null operand is treated as "everything". */
    fun intersect(a: PixSelection?, b: PixSelection?): PixSelection? {
        if (a == null) return b
        if (b == null) return a
        return a.combine(b, PixSelection.Mode.INTERSECT)
    }

    /** The Quick Mask toggle's conversion: a soft selection becomes the painting mask. */
    fun quickMask(selection: PixSelection, color: Int = 0xFFFF0000.toInt()): PrismImage {
        val pixels = IntArray(selection.mask.size)
        val alphaBase = (color ushr 24) and 0xFF
        for (i in pixels.indices) {
            val coverage = (selection.mask[i].toInt() and 0xFF) / 255.0
            val alpha = (alphaBase * coverage * 0.5).toInt().coerceIn(0, 255)
            pixels[i] = (color and 0x00FFFFFF) or (alpha shl 24)
        }
        return PrismImage(selection.width, selection.height, pixels)
    }
}
