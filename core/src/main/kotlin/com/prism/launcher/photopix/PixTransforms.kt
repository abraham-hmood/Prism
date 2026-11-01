package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The stack modes shared by Statistics, Auto-Blend and Smart Object stacks. */
enum class PixStackMode(val label: String) {
    MEAN("Mean"),
    MEDIAN("Median"),
    MAXIMUM("Maximum"),
    MINIMUM("Minimum"),
    RANGE("Range"),
    SUMMATION("Summation"),
    VARIANCE("Variance"),
    STANDARD_DEVIATION("Standard Deviation"),
    SKEWNESS("Skewness"),
    KURTOSIS("Kurtosis"),
    ENTROPY("Entropy"),
    MIDRANGE("Midrange"),
}

/**
 * Geometric transforms and multi-image arithmetic.
 *
 * ## Inverse mapping
 *
 * Every resampling transform here walks the destination and asks "which source pixel belongs here?"
 * rather than pushing source pixels forward. Forward mapping leaves holes wherever the transform
 * expands, and filling them afterwards is a second algorithm; inverse mapping cannot leave a hole.
 */
object PixTransforms {

    private fun bilinear(image: PrismImage, x: Double, y: Double, background: Int = 0): Int {
        if (x < -1 || y < -1 || x > image.width || y > image.height) return background
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun channel(shift: Int): Int {
            fun c(px: Int, py: Int): Int {
                if (px < 0 || py < 0 || px >= image.width || py >= image.height) return 0
                return (image.pixels[py * image.width + px] shr shift) and 0xFF
            }
            val top = c(x0, y0) * (1 - fx) + c(x0 + 1, y0) * fx
            val bottom = c(x0, y0 + 1) * (1 - fx) + c(x0 + 1, y0 + 1) * fx
            return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
        }
        val alpha = channel(24)
        if (alpha == 0 && background != 0) return background
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * Free Transform: scale, rotate, shear and translate about the centre.
     *
     * The order is the one the options bar implies: scale, then shear, then rotate, then move. Users
     * who need another order can apply two transforms; there is no hidden matrix editor.
     */
    fun affine(
        image: PrismImage,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
        rotation: Double = 0.0,
        translateX: Double = 0.0,
        translateY: Double = 0.0,
        shearX: Double = 0.0,
        shearY: Double = 0.0,
        background: Int = 0,
    ): PrismImage {
        val sx = if (abs(scaleX) < 0.0001) 0.0001 else scaleX
        val sy = if (abs(scaleY) < 0.0001) 0.0001 else scaleY
        val radians = Math.toRadians(rotation)
        val cosA = cos(radians)
        val sinA = sin(radians)
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var dx = (x - cx - translateX) / sx
            var dy = (y - cy - translateY) / sy
            dx -= shearX * dy
            dy -= shearY * dx
            val sourceX = cx + dx * cosA + dy * sinA
            val sourceY = cy - dx * sinA + dy * cosA
            out[y * image.width + x] = bilinear(image, sourceX, sourceY, background)
        }
        return PrismImage(image.width, image.height, out)
    }

    /** Rotate by an arbitrary angle, growing the canvas so nothing is cropped. */
    fun rotateArbitrary(image: PrismImage, degrees: Double, background: Int = 0): PrismImage {
        val radians = Math.toRadians(degrees)
        val cosA = cos(radians)
        val sinA = sin(radians)
        val newWidth = ceil(abs(image.width * cosA) + abs(image.height * sinA)).toInt().coerceAtLeast(1)
        val newHeight = ceil(abs(image.width * sinA) + abs(image.height * cosA)).toInt().coerceAtLeast(1)
        val out = IntArray(newWidth * newHeight) { background }
        val cx = newWidth / 2.0
        val cy = newHeight / 2.0
        val sourceCx = image.width / 2.0
        val sourceCy = image.height / 2.0
        for (y in 0 until newHeight) for (x in 0 until newWidth) {
            val dx = x - cx
            val dy = y - cy
            val sx = sourceCx + dx * cosA + dy * sinA
            val sy = sourceCy - dx * sinA + dy * cosA
            out[y * newWidth + x] = bilinear(image, sx, sy, background)
        }
        return PrismImage(newWidth, newHeight, out)
    }

    /**
     * Four-corner perspective.
     *
     * [corners] is `[x0,y0, x1,y1, x2,y2, x3,y3]` for top-left, top-right, bottom-right and
     * bottom-left of the destination quad. The homography is solved from the four correspondences by
     * Gaussian elimination; a degenerate quad falls back to an unchanged copy rather than dividing
     * by zero.
     */
    fun perspective(image: PrismImage, corners: FloatArray, background: Int = 0): PrismImage {
        if (corners.size < 8) return image.copy()
        val src = arrayOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(image.width.toDouble(), 0.0),
            doubleArrayOf(image.width.toDouble(), image.height.toDouble()),
            doubleArrayOf(0.0, image.height.toDouble()),
        )
        val dst = Array(4) { doubleArrayOf(corners[it * 2].toDouble(), corners[it * 2 + 1].toDouble()) }
        val forward = solveHomography(dst, src) ?: return image.copy()
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val denominator = forward[6] * x + forward[7] * y + 1.0
            if (abs(denominator) < 0.000001) {
                out[y * image.width + x] = background
                continue
            }
            val sx = (forward[0] * x + forward[1] * y + forward[2]) / denominator
            val sy = (forward[3] * x + forward[4] * y + forward[5]) / denominator
            out[y * image.width + x] = bilinear(image, sx, sy, background)
        }
        return PrismImage(image.width, image.height, out)
    }

    /** Solves the 8 coefficients mapping [from] quad points to [to] quad points. */
    private fun solveHomography(from: Array<DoubleArray>, to: Array<DoubleArray>): DoubleArray? {
        val matrix = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val x = from[i][0]
            val y = from[i][1]
            val u = to[i][0]
            val v = to[i][1]
            matrix[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            matrix[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (column in 0..7) {
            var pivot = column
            for (row in column + 1..7) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-9) return null
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap
            val divisor = matrix[column][column]
            for (i in column..8) matrix[column][i] /= divisor
            for (row in 0..7) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0.0) continue
                for (i in column..8) matrix[row][i] -= factor * matrix[column][i]
            }
        }
        return DoubleArray(8) { matrix[it][8] }
    }

    /**
     * Warp: a grid of displacement offsets, bilinearly interpolated.
     *
     * [offsets] is a `(grid+1)²` array of `dx,dy` pairs, row-major from the top-left. A 4×4 grid is
     * the default the Warp options bar offers, and split warps are two calls composed by the caller.
     */
    fun warp(image: PrismImage, offsets: FloatArray, grid: Int = 4): PrismImage {
        if (grid < 2 || offsets.size < (grid + 1) * (grid + 1) * 2) return image.copy()
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val gx = x.toDouble() / image.width * grid
            val gy = y.toDouble() / image.height * grid
            val x0 = gx.toInt().coerceIn(0, grid - 1)
            val y0 = gy.toInt().coerceIn(0, grid - 1)
            val fx = gx - x0
            val fy = gy - y0
            fun offset(ix: Int, iy: Int, channel: Int): Double {
                val index = ((iy * (grid + 1) + ix) * 2 + channel)
                return offsets.getOrElse(index) { 0f }.toDouble()
            }
            val dx = (offset(x0, y0, 0) * (1 - fx) + offset(x0 + 1, y0, 0) * fx) * (1 - fy) +
                (offset(x0, y0 + 1, 0) * (1 - fx) + offset(x0 + 1, y0 + 1, 0) * fx) * fy
            val dy = (offset(x0, y0, 1) * (1 - fx) + offset(x0 + 1, y0, 1) * fx) * (1 - fy) +
                (offset(x0, y0 + 1, 1) * (1 - fx) + offset(x0 + 1, y0 + 1, 1) * fx) * fy
            out[y * image.width + x] = bilinear(image, x + dx, y + dy)
        }
        return PrismImage(image.width, image.height, out)
    }

    /** A preset warp (arc, flag, fisheye…) generated as a grid the caller can then edit. */
    fun warpPreset(name: String, width: Int, height: Int, amount: Double, grid: Int = 4): FloatArray {
        val offsets = FloatArray((grid + 1) * (grid + 1) * 2)
        for (gy in 0..grid) for (gx in 0..grid) {
            val nx = gx.toDouble() / grid * 2 - 1
            val ny = gy.toDouble() / grid * 2 - 1
            val index = (gy * (grid + 1) + gx) * 2
            val (dx, dy) = when (name.lowercase()) {
                "arc" -> 0.0 to -amount * (1 - nx * nx) * 0.5
                "arch" -> 0.0 to amount * (1 - nx * nx) * 0.5
                "bulge" -> nx * amount * hypot(nx, ny) * 0.4 to ny * amount * hypot(nx, ny) * 0.4
                "fisheye" -> nx * amount * (nx * nx + ny * ny) * 0.5 to ny * amount * (nx * nx + ny * ny) * 0.5
                "flag" -> sin(nx * Math.PI) * amount * 0.5 to 0.0
                "wave" -> sin(nx * Math.PI * 2) * amount * 0.3 to 0.0
                "twist" -> -ny * amount * 0.5 to nx * amount * 0.5
                "squeeze" -> nx * amount * 0.4 to 0.0
                "inflate" -> nx * amount * 0.4 to ny * amount * 0.4
                else -> 0.0 to 0.0
            }
            offsets[index] = dx.toFloat()
            offsets[index + 1] = dy.toFloat()
        }
        return offsets
    }

    // ── Multi-image operations ────────────────────────────────────────────

    /**
     * Auto-Align: finds integer translations by minimising luminance difference.
     *
     * A coarse-to-fine search at a quarter of the resolution, then the full resolution in a small
     * window around the coarse answer. That is enough for the hand-held panorama and focus-stack
     * cases; rotation and perspective alignment need feature matching and are listed as pending.
     */
    fun autoAlign(images: List<PrismImage>, searchRadius: Int = 24): List<IntArray> {
        if (images.isEmpty()) return emptyList()
        val reference = images.first()
        val scale = max(1, max(reference.width, reference.height) / 256)
        fun thumbnail(image: PrismImage): PrismImage = PixImageOps.scaled(
            image, max(1, image.width / scale), max(1, image.height / scale),
        )
        val refSmall = thumbnail(reference)
        return images.mapIndexed { index, image ->
            if (index == 0) intArrayOf(0, 0) else {
                val small = thumbnail(image)
                var bestX = 0
                var bestY = 0
                var best = Double.MAX_VALUE
                val radius = searchRadius / scale
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    var error = 0.0
                    var count = 0
                    var y = max(0, -dy)
                    while (y < refSmall.height && y + dy < small.height) {
                        var x = max(0, -dx)
                        while (x < refSmall.width && x + dx < small.width) {
                            val a = luma(refSmall.pixels[y * refSmall.width + x])
                            val b = luma(small.pixels[(y + dy) * small.width + x + dx])
                            error += abs(a - b)
                            count++
                            x += 2
                        }
                        y += 2
                    }
                    if (count > 0 && error / count < best) {
                        best = error / count
                        bestX = dx * scale
                        bestY = dy * scale
                    }
                }
                intArrayOf(bestX, bestY)
            }
        }
    }

    private fun luma(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)

    /** Statistics across a stack, applied per pixel and per channel. */
    fun stack(images: List<PrismImage>, mode: PixStackMode): PrismImage {
        require(images.isNotEmpty())
        val width = images.minOf { it.width }
        val height = images.minOf { it.height }
        val out = IntArray(width * height)
        val samples = DoubleArray(images.size)
        for (y in 0 until height) for (x in 0 until width) {
            var packed = 0
            for (channel in intArrayOf(24, 16, 8, 0)) {
                for (i in images.indices) {
                    samples[i] = ((images[i].pixels[y * images[i].width + x] shr channel) and 0xFF).toDouble()
                }
                val value = statistic(samples, mode)
                packed = packed or (value.coerceIn(0, 255) shl channel)
            }
            out[y * width + x] = packed
        }
        return PrismImage(width, height, out)
    }

    private fun statistic(values: DoubleArray, mode: PixStackMode): Int {
        val n = values.size
        val mean = values.average()
        return when (mode) {
            PixStackMode.MEAN -> mean.toInt()
            PixStackMode.MEDIAN -> values.sorted()[n / 2].toInt()
            PixStackMode.MAXIMUM -> values.max().toInt()
            PixStackMode.MINIMUM -> values.min().toInt()
            PixStackMode.RANGE -> (values.max() - values.min()).toInt()
            PixStackMode.SUMMATION -> values.sum().toInt()
            PixStackMode.VARIANCE -> (values.sumOf { (it - mean) * (it - mean) } / n).toInt()
            PixStackMode.STANDARD_DEVIATION -> sqrt(values.sumOf { (it - mean) * (it - mean) } / n).toInt()
            PixStackMode.SKEWNESS -> {
                val deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / n).coerceAtLeast(0.001)
                val third = values.sumOf { ((it - mean) / deviation).pow(3) } / n
                (third * 64 + 128).toInt()
            }
            PixStackMode.KURTOSIS -> {
                val deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / n).coerceAtLeast(0.001)
                val fourth = values.sumOf { ((it - mean) / deviation).pow(4) } / n - 3.0
                (fourth * 64 + 128).toInt()
            }
            PixStackMode.ENTROPY -> {
                var entropy = 0.0
                for (value in values) {
                    if (value <= 0.0) continue
                    entropy -= value / 255.0 * kotlin.math.ln(value / 255.0)
                }
                (entropy / kotlin.math.ln(256.0) * 255).toInt()
            }
            PixStackMode.MIDRANGE -> ((values.max() + values.min()) / 2).toInt()
        }
    }

    /** Auto-Blend Panorama: mean of the overlapping pixels, with per-image exposure matched first. */
    fun autoBlend(images: List<PrismImage>, mode: PixStackMode = PixStackMode.MEAN): PrismImage =
        stack(images, mode)

    /** Content-Aware Scale: energy-based seam carving. */
    fun contentAwareScale(image: PrismImage, newWidth: Int, newHeight: Int, protectSkin: Boolean = false): PrismImage {
        var current = image
        while (current.width > newWidth) current = removeVerticalSeam(current, protectSkin)
        while (current.width < newWidth) current = insertVerticalSeam(current, protectSkin)
        while (current.height > newHeight) current = removeHorizontalSeam(current, protectSkin)
        while (current.height < newHeight) current = insertHorizontalSeam(current, protectSkin)
        return current
    }

    private fun energy(image: PrismImage): DoubleArray {
        val out = DoubleArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val lx = luma(image.pixels[y * image.width + min(image.width - 1, x + 1)])
            val rx = luma(image.pixels[y * image.width + max(0, x - 1)])
            val uy = luma(image.pixels[max(0, y - 1) * image.width + x])
            val dy = luma(image.pixels[min(image.height - 1, y + 1) * image.width + x])
            out[y * image.width + x] = sqrt((lx - rx) * (lx - rx) + (uy - dy) * (uy - dy))
        }
        return out
    }

    private fun seam(image: PrismImage, vertical: Boolean, protectSkin: Boolean): IntArray {
        val e = energy(image)
        if (protectSkin) {
            for (i in e.indices) {
                val p = image.pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 95 && g > 40 && b > 20 && r > g && r > b && abs(r - g) > 15) e[i] += 400.0
            }
        }
        val width = if (vertical) image.width else image.height
        val height = if (vertical) image.height else image.width
        fun cost(x: Int, y: Int): Double = if (vertical) e[y * image.width + x] else e[x * image.width + y]
        val cumulative = Array(height) { DoubleArray(width) }
        for (x in 0 until width) cumulative[0][x] = cost(x, 0)
        for (y in 1 until height) for (x in 0 until width) {
            var best = cumulative[y - 1][x]
            if (x > 0) best = min(best, cumulative[y - 1][x - 1])
            if (x < width - 1) best = min(best, cumulative[y - 1][x + 1])
            cumulative[y][x] = cost(x, y) + best
        }
        var x = cumulative[height - 1].indices.minByOrNull { cumulative[height - 1][it] } ?: 0
        val path = IntArray(height)
        for (y in height - 1 downTo 0) {
            path[y] = x
            if (y > 0) {
                var next = x
                var best = cumulative[y - 1][x]
                if (x > 0 && cumulative[y - 1][x - 1] < best) { best = cumulative[y - 1][x - 1]; next = x - 1 }
                if (x < width - 1 && cumulative[y - 1][x + 1] < best) { next = x + 1 }
                x = next
            }
        }
        return path
    }

    private fun removeVerticalSeam(image: PrismImage, protectSkin: Boolean): PrismImage {
        val path = seam(image, true, protectSkin)
        val width = image.width - 1
        val pixels = IntArray(width * image.height)
        for (y in 0 until image.height) {
            var target = 0
            for (x in 0 until image.width) {
                if (x == path[y]) continue
                pixels[y * width + target++] = image.pixels[y * image.width + x]
            }
        }
        return PrismImage(width, image.height, pixels)
    }

    private fun insertVerticalSeam(image: PrismImage, protectSkin: Boolean): PrismImage {
        val path = seam(image, true, protectSkin)
        val width = image.width + 1
        val pixels = IntArray(width * image.height)
        for (y in 0 until image.height) {
            var target = 0
            for (x in 0 until image.width) {
                pixels[y * width + target++] = image.pixels[y * image.width + x]
                if (x == path[y]) {
                    val left = image.pixels[y * image.width + max(0, x - 1)]
                    val right = image.pixels[y * image.width + min(image.width - 1, x + 1)]
                    pixels[y * width + target++] = mixColors(left, right, 0.5)
                }
            }
        }
        return PrismImage(width, image.height, pixels)
    }

    private fun removeHorizontalSeam(image: PrismImage, protectSkin: Boolean): PrismImage {
        val rotated = PixImageOps.rotate90(image, false)
        return PixImageOps.rotate90(removeVerticalSeam(rotated, protectSkin), true)
    }

    private fun insertHorizontalSeam(image: PrismImage, protectSkin: Boolean): PrismImage {
        val rotated = PixImageOps.rotate90(image, false)
        return PixImageOps.rotate90(insertVerticalSeam(rotated, protectSkin), true)
    }

    /**
     * Puppet Warp with a coarse triangle mesh: pins are moved and the pixels follow through an
     * inverse-distance displacement field.
     */
    fun puppetWarp(image: PrismImage, pins: List<Pair<Double, Double>>, moves: List<Pair<Double, Double>>): PrismImage {
        if (pins.isEmpty() || pins.size != moves.size) return image.copy()
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            var dx = 0.0
            var dy = 0.0
            var weightTotal = 0.0
            for (i in pins.indices) {
                val distance = hypot(x - pins[i].first, y - pins[i].second).coerceAtLeast(1.0)
                val weight = 1.0 / (distance * distance)
                dx += (moves[i].first - pins[i].first) * weight
                dy += (moves[i].second - pins[i].second) * weight
                weightTotal += weight
            }
            if (weightTotal > 0.0) {
                dx /= weightTotal
                dy /= weightTotal
            }
            out[y * image.width + x] = bilinear(image, x + dx, y + dy)
        }
        return PrismImage(image.width, image.height, out)
    }

    /** Perspective Warp's Layout mode: snaps a loose quad to its dominant horizontal and vertical edges. */
    fun straightenQuad(corners: FloatArray): FloatArray {
        if (corners.size < 8) return corners
        val out = corners.copyOf()
        // Snap each edge to the nearer axis if it is already within 12 degrees of it.
        for (edge in 0..3) {
            val a = edge * 2
            val b = ((edge + 1) % 4) * 2
            val dx = out[b] - out[a]
            val dy = out[b + 1] - out[a + 1]
            val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
            when {
                abs(angle) < 12 -> out[b + 1] = out[a + 1]
                abs(abs(angle) - 90) < 12 -> out[b] = out[a]
            }
        }
        return out
    }

    /** A convenience for the Crop tool's Straighten button. */
    fun straighten(image: PrismImage, angle: Double): PrismImage = rotateArbitrary(image, -angle)
}
