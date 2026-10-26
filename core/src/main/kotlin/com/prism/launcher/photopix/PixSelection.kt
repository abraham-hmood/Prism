package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * An antialiased selection mask.
 *
 * ## A mask rather than a shape
 *
 * Photoshop's selection is a per-pixel alpha channel, and everything that makes it useful follows
 * from that: a feathered edge, a wand selection, a saved alpha channel and a layer mask are all the
 * same object. Keeping a rectangle or a path instead would mean four implementations of "is this
 * pixel selected" that disagree at the edges.
 *
 * 255 means fully selected, 0 fully outside, and the values between are a soft edge. Painting tools
 * multiply their own alpha by the mask, which is what makes a feathered selection behave like one.
 *
 * ## Combining
 *
 * [Mode.REPLACE] is the default, and the other three are the modifier keys Photoshop binds: shift
 * adds, alt subtracts, shift+alt intersects. The same four operations are also what saving a
 * selection into an alpha channel does, so there is one implementation rather than two.
 */
class PixSelection(val width: Int, val height: Int, val mask: ByteArray) {

    init {
        require(mask.size >= width * height) { "selection mask is ${mask.size}, needs ${width * height}" }
    }

    enum class Mode { REPLACE, ADD, SUBTRACT, INTERSECT }

    fun at(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else mask[y * width + x].toInt() and 0xFF

    fun coverage(x: Int, y: Int): Double = at(x, y) / 255.0

    val isAll: Boolean
        get() = mask.all { (it.toInt() and 0xFF) == 255 }

    val isEmpty: Boolean
        get() = mask.all { it.toInt() == 0 }

    fun copy(): PixSelection = PixSelection(width, height, mask.copyOf())

    /** Bounding box of anything selected, as `[x, y, width, height]`, or null. */
    fun bounds(): IntArray? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (mask[y * width + x].toInt() != 0) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    fun combine(other: PixSelection, mode: Mode): PixSelection {
        if (width != other.width || height != other.height) return this
        val out = ByteArray(mask.size)
        for (i in mask.indices) {
            val a = mask[i].toInt() and 0xFF
            val b = other.mask[i].toInt() and 0xFF
            out[i] = when (mode) {
                Mode.REPLACE -> b
                Mode.ADD -> max(a, b)
                Mode.SUBTRACT -> max(0, a - b)
                Mode.INTERSECT -> min(a, b)
            }.toByte()
        }
        return PixSelection(width, height, out)
    }

    fun inverted(): PixSelection {
        val out = ByteArray(mask.size)
        for (i in mask.indices) out[i] = (255 - (mask[i].toInt() and 0xFF)).toByte()
        return PixSelection(width, height, out)
    }

    /**
     * Softens the edge.
     *
     * Two passes of a box blur the right radius apart approximate a Gaussian closely enough for a
     * selection edge, and cost a fraction of a real convolution. The result is intentionally clamped
     * rather than normalised: feathering must not make an unselected pixel selected.
     */
    fun feathered(radius: Int): PixSelection {
        if (radius <= 0) return copy()
        var current = mask.copyOf()
        repeat(2) {
            current = blur(current, radius)
        }
        return PixSelection(width, height, current)
    }

    /**
     * Separable box blur over the mask.
     *
     * Horizontal then vertical, each with a running sum, so the cost is independent of the radius
     * rather than proportional to it. A 64-pixel feather on a phone-sized canvas is then cheap
     * enough to run on the main thread while the user drags the slider.
     */
    private fun blur(source: ByteArray, radius: Int): ByteArray {
        val r = radius.coerceIn(1, 128)
        val horizontal = ByteArray(source.size)
        val window = 2 * r + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (i in -r..r) sum += if (i in 0 until width) source[row + i].toInt() and 0xFF else 0
            for (x in 0 until width) {
                horizontal[row + x] = (sum / window).toByte()
                val leaving = x - r
                val entering = x + r + 1
                if (leaving >= 0) sum -= source[row + leaving].toInt() and 0xFF
                if (entering < width) sum += source[row + entering].toInt() and 0xFF
            }
        }
        val out = ByteArray(source.size)
        for (x in 0 until width) {
            var sum = 0
            for (i in -r..r) sum += if (i in 0 until height) horizontal[i * width + x].toInt() and 0xFF else 0
            for (y in 0 until height) {
                out[y * width + x] = (sum / window).toByte()
                val leaving = y - r
                val entering = y + r + 1
                if (leaving >= 0) sum -= horizontal[leaving * width + x].toInt() and 0xFF
                if (entering < height) sum += horizontal[entering * width + x].toInt() and 0xFF
            }
        }
        return out
    }

    /** Grows (positive) or shrinks (negative) the selection by morphological dilation. */
    fun expanded(radius: Int): PixSelection {
        if (radius == 0) return copy()
        val out = ByteArray(mask.size)
        val r = abs(radius).coerceAtMost(64)
        for (y in 0 until height) for (x in 0 until width) {
            var best = if (radius > 0) 0 else 255
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val value = at(x + dx, y + dy)
                best = if (radius > 0) max(best, value) else min(best, value)
            }
            out[y * width + x] = best.toByte()
        }
        return PixSelection(width, height, out)
    }

    companion object {

        fun none(width: Int, height: Int): PixSelection =
            PixSelection(width, height, ByteArray(width * height))

        fun all(width: Int, height: Int): PixSelection =
            PixSelection(width, height, ByteArray(width * height) { 255.toByte() })

        fun rect(width: Int, height: Int, x: Int, y: Int, w: Int, h: Int): PixSelection {
            val out = ByteArray(width * height)
            val x0 = x.coerceIn(0, width)
            val y0 = y.coerceIn(0, height)
            val x1 = (x + w).coerceIn(0, width)
            val y1 = (y + h).coerceIn(0, height)
            for (row in y0 until y1) for (col in x0 until x1) out[row * width + col] = 255.toByte()
            return PixSelection(width, height, out)
        }

        /**
         * An ellipse, sampled at pixel centres and antialiased along the boundary.
         *
         * The coverage estimate is a four-sample box: it is the cheapest thing that stops the edge
         * looking like a staircase at phone resolution, which is the only resolution this ships at.
         */
        fun ellipse(width: Int, height: Int, x: Int, y: Int, w: Int, h: Int): PixSelection {
            val out = ByteArray(width * height)
            if (w <= 0 || h <= 0) return PixSelection(width, height, out)
            val rx = w / 2.0
            val ry = h / 2.0
            val cx = x + rx
            val cy = y + ry
            val samples = arrayOf(-0.25 to -0.25, 0.25 to -0.25, -0.25 to 0.25, 0.25 to 0.25)
            for (row in 0 until height) for (col in 0 until width) {
                var hits = 0
                for ((sx, sy) in samples) {
                    val dx = (col + 0.5 + sx - cx) / rx
                    val dy = (row + 0.5 + sy - cy) / ry
                    if (dx * dx + dy * dy <= 1.0) hits++
                }
                if (hits > 0) out[row * width + col] = (hits * 255 / 4).toByte()
            }
            return PixSelection(width, height, out)
        }

        /** Fills a polygon with the even-odd rule; this is both the lasso and a pen path. */
        fun polygon(width: Int, height: Int, points: List<Pair<Double, Double>>, antialias: Boolean = true): PixSelection {
            val out = ByteArray(width * height)
            if (points.size < 3) return PixSelection(width, height, out)

            // Scanline crossing counts, computed once per row rather than per pixel.
            for (row in 0 until height) {
                val sampleY = row + 0.5
                val crossings = ArrayList<Double>(points.size)
                for (i in points.indices) {
                    val (x1, y1) = points[i]
                    val (x2, y2) = points[(i + 1) % points.size]
                    if ((y1 <= sampleY && y2 > sampleY) || (y2 <= sampleY && y1 > sampleY)) {
                        val t = (sampleY - y1) / (y2 - y1)
                        crossings.add(x1 + t * (x2 - x1))
                    }
                }
                if (crossings.isEmpty()) continue
                crossings.sort()
                var i = 0
                while (i + 1 < crossings.size) {
                    val from = crossings[i].coerceAtLeast(0.0)
                    val to = crossings[i + 1].coerceAtMost(width.toDouble())
                    var col = from.toInt()
                    while (col < to) {
                        if (col in 0 until width) {
                            val coverage = if (!antialias) 255 else {
                                val overlap = min(to, col + 1.0) - max(from, col.toDouble())
                                (overlap.coerceIn(0.0, 1.0) * 255).toInt()
                            }
                            val index = row * width + col
                            out[index] = max(out[index].toInt() and 0xFF, coverage).toByte()
                        }
                        col++
                    }
                    i += 2
                }
            }
            return PixSelection(width, height, out)
        }

        /**
         * The magic wand.
         *
         * Contiguous by flood fill, or global by scanning every pixel. Tolerance is compared in
         * RGB distance, deliberately not in each channel separately: a per-channel test accepts a
         * colour that is far away by being wrong in all three, which is the classic wand bug.
         */
        fun wand(
            image: PrismImage,
            x: Int,
            y: Int,
            tolerance: Int,
            contiguous: Boolean = true,
            antialias: Boolean = true,
        ): PixSelection {
            val width = image.width
            val height = image.height
            val out = ByteArray(width * height)
            if (x < 0 || y < 0 || x >= width || y >= height) return PixSelection(width, height, out)

            val target = image.pixels[y * width + x]
            val tr = (target shr 16) and 0xFF
            val tg = (target shr 8) and 0xFF
            val tb = target and 0xFF
            val limit = tolerance * tolerance * 3

            fun distance(pixel: Int): Int {
                val dr = ((pixel shr 16) and 0xFF) - tr
                val dg = ((pixel shr 8) and 0xFF) - tg
                val db = (pixel and 0xFF) - tb
                return dr * dr + dg * dg + db * db
            }

            if (!contiguous) {
                for (i in image.pixels.indices) {
                    val d = distance(image.pixels[i])
                    if (d <= limit) out[i] = 255.toByte()
                    else if (antialias && d <= limit * 4) {
                        val ratio = 1.0 - (Math.sqrt(d.toDouble()) - Math.sqrt(limit.toDouble())) /
                            (Math.sqrt(limit * 4.0) - Math.sqrt(limit.toDouble()))
                        out[i] = (ratio.coerceIn(0.0, 1.0) * 255).toInt().toByte()
                    }
                }
                return PixSelection(width, height, out)
            }

            val queue = ArrayDeque<Int>()
            val seen = BooleanArray(width * height)
            queue.add(y * width + x)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                if (seen[index]) continue
                seen[index] = true
                val d = distance(image.pixels[index])
                if (d > limit) continue
                out[index] = 255.toByte()
                val px = index % width
                val py = index / width
                if (px > 0) queue.add(index - 1)
                if (px < width - 1) queue.add(index + 1)
                if (py > 0) queue.add(index - width)
                if (py < height - 1) queue.add(index + width)
            }
            return PixSelection(width, height, out)
        }
    }
}
