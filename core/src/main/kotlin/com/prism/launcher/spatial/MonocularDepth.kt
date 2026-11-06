package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Depth for a camera that has no depth sensor: a computer's webcam.
 *
 * A phone has ARCore depth and a Quest has a depth sensor; a webcam has only a picture. A monocular
 * depth network (Depth Anything V2) estimates *relative* inverse depth from that picture -- right
 * up to an unknown scale s and shift t: `predicted = s / z + t`. This turns that into metric depth
 * the room pipeline can use, without a calibration step:
 *
 * 1. **Planes survive the unknown shift.** For a plane, 1/z is linear in pixel coordinates, so on a
 *    flat surface the prediction is linear in (u, v) too, whatever s and t are. The flat regions
 *    are found by RANSAC in (u, v, prediction) space.
 * 2. **Rooms are rectangular.** Floors, walls and ceilings meet at right angles. Each candidate
 *    shift (and each likely webcam field of view) gives every region a 3D normal; the one under
 *    which they are most nearly parallel or perpendicular to each other is the answer.
 * 3. **Scale from the camera's height.** A webcam sits on a desk or a monitor; with the floor in
 *    view, its distance below the camera is taken as [CAMERA_HEIGHT]. Without a floor, the median
 *    distance is taken as [TYPICAL_DISTANCE]. The same assumption every time keeps saved
 *    placements consistent from one session to the next.
 * 4. **Gravity from the room.** "Up" is the region normal nearest the camera's own up.
 */
object MonocularDepth {

    const val CAMERA_HEIGHT = 1.1f
    const val TYPICAL_DISTANCE = 2.5f
    private val FIELDS_OF_VIEW = floatArrayOf(55f, 62f, 70f, 78f)

    /** A planar region in prediction space: prediction = a·u + b·v + c over [count] samples. */
    class Region(val a: Float, val b: Float, val c: Float, val count: Int)

    class Solved(val frame: DepthFrame, val up: Vec3, val fovDegrees: Float, val shift: Float, val regions: Int)

    /**
     * [prediction] is the network's output resampled to a [width] x [height] grid that covers the
     * camera image exactly (larger = nearer).
     */
    fun solve(prediction: FloatArray, width: Int, height: Int, seed: Int = 7): Solved? {
        require(prediction.size == width * height)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        prediction.forEach { if (it.isFinite()) { if (it < lo) lo = it; if (it > hi) hi = it } }
        val range = hi - lo
        if (!range.isFinite() || range <= 1e-6f) return null

        val regions = regions(prediction, width, height, range, Random(seed))
        val cx = width / 2f
        val cy = height / 2f

        // The shift must keep every inverse depth positive: t < lo. Candidates spread below it.
        val shifts = (1..60).map { lo - range * (0.01f * Math.pow(1.13, it.toDouble()).toFloat()) }
        var best: Triple<Float, Float, Float>? = null // cost, fov, shift
        if (regions.size >= 2) {
            for (fov in FIELDS_OF_VIEW) {
                val f = (width / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
                for (t in shifts) {
                    val normals = regions.map { normalOf(it, f, cx, cy, t) }
                    var cost = 0f
                    for (i in normals.indices) for (j in i + 1 until normals.size) {
                        val c = abs(normals[i] dot normals[j])
                        cost += minOf(c * c, 1 - c * c) * regions[i].count * regions[j].count
                    }
                    if (best == null || cost < best.first) best = Triple(cost, fov, t)
                }
            }
        }
        val fov = best?.second ?: 70f
        val shift = best?.third ?: (lo - range * 0.1f)
        val f = (width / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        val intrinsics = Intrinsics(f, f, cx, cy, width, height)

        // Up: the region normal closest to the camera's own up (OpenGL camera axes: +Y).
        val camUp = Vec3.UP
        val up = regions.map { normalOf(it, f, cx, cy, shift) }
            .map { if (it.y < 0) -it else it }
            .maxByOrNull { it dot camUp }
            ?.takeIf { (it dot camUp) > 0.7f } ?: camUp

        // Depth at scale 1, then scaled.
        val depth = FloatArray(width * height) { i ->
            val w = prediction[i] - shift
            if (w > 1e-6f) 1f / w else 0f
        }
        // The floor: a region facing up whose plane passes below the camera. Of several (a desk
        // and the floor), the lowest -- the farthest below.
        val floorDistance = regions
            .filter { region -> (normalOf(region, f, cx, cy, shift).let { if (it.y < 0) -it else it } dot up) > 0.9f }
            .mapNotNull { region -> planeOffset(region, f, cx, cy, shift, width, height, up) }
            .filter { it < 0f }
            .minOrNull()?.let { -it }
        val scale = if (floorDistance != null && floorDistance > 1e-6f) {
            CAMERA_HEIGHT / floorDistance
        } else {
            val sorted = depth.filter { it > 0f }.sorted()
            if (sorted.isEmpty()) return null
            TYPICAL_DISTANCE / sorted[sorted.size / 2]
        }
        for (i in depth.indices) depth[i] *= scale

        // Camera to world: world Y along up, world -Z along the camera's horizontal heading.
        val yAxis = up.normalized()
        val forward = (Vec3(0f, 0f, -1f) - yAxis * (Vec3(0f, 0f, -1f) dot yAxis)).normalized()
        val zAxis = -forward
        val xAxis = (yAxis cross zAxis).normalized()
        val rotation = floatArrayOf(xAxis.x, xAxis.y, xAxis.z, yAxis.x, yAxis.y, yAxis.z, zAxis.x, zAxis.y, zAxis.z)
        val cameraHeight = floorDistance?.let { it * scale } ?: CAMERA_HEIGHT
        val pose = Pose(rotation, Vec3(0f, cameraHeight, 0f))
        return Solved(DepthFrame(depth, intrinsics, pose), up, fov, shift, regions.size)
    }

    /** A region's 3D normal (OpenGL camera axes) under focal length [f] and shift [t]. */
    internal fun normalOf(r: Region, f: Float, cx: Float, cy: Float, t: Float): Vec3 {
        // In OpenCV axes (y down, z forward): n ∝ (a·f, b·f, (c − t) + a·cx + b·cy).
        val nx = r.a * f
        val ny = r.b * f
        val nz = (r.c - t) + r.a * cx + r.b * cy
        // To OpenGL axes: y and z flip.
        return Vec3(nx, -ny, -nz).normalized()
    }

    /**
     * The plane's signed offset along [up] at scale 1: negative when the plane passes below the
     * camera. Any pixel gives a point on the plane's extension; the first with a positive inverse
     * depth is used.
     */
    private fun planeOffset(r: Region, f: Float, cx: Float, cy: Float, t: Float, w: Int, h: Int, up: Vec3): Float? {
        var n = normalOf(r, f, cx, cy, t)
        if ((n dot up) < 0) n = -n
        for ((u, v) in listOf(w / 2f to h * 0.9f, w / 2f to h / 2f, w / 2f to h * 0.1f)) {
            val inv = r.a * u + r.b * v + r.c - t
            if (inv <= 1e-6f) continue
            val z = 1f / inv
            val p = Vec3((u - cx) / f * z, -(v - cy) / f * z, -z)
            return n dot p
        }
        return null
    }

    /** RANSAC in prediction space: up to six regions, each at least 4% of the samples. */
    private fun regions(prediction: FloatArray, width: Int, height: Int, range: Float, random: Random): List<Region> {
        val step = maxOf(1, sqrt((width * height) / 3000.0).toInt())
        val us = ArrayList<Float>()
        val vs = ArrayList<Float>()
        val ds = ArrayList<Float>()
        for (v in 0 until height step step) for (u in 0 until width step step) {
            val d = prediction[v * width + u]
            if (d.isFinite()) { us += u.toFloat(); vs += v.toFloat(); ds += d }
        }
        val alive = BooleanArray(us.size) { true }
        val threshold = range * 0.012f
        val out = ArrayList<Region>()
        repeat(6) {
            val pool = us.indices.filter { alive[it] }
            if (pool.size < us.size * 0.04 || pool.size < 30) return out
            var bestInliers: List<Int> = emptyList()
            repeat(250) {
                val i = pool[random.nextInt(pool.size)]
                val j = pool[random.nextInt(pool.size)]
                val k = pool[random.nextInt(pool.size)]
                val fit = solve3(
                    floatArrayOf(us[i], vs[i], 1f, us[j], vs[j], 1f, us[k], vs[k], 1f),
                    floatArrayOf(ds[i], ds[j], ds[k]),
                ) ?: return@repeat
                val inliers = pool.filter { abs(fit[0] * us[it] + fit[1] * vs[it] + fit[2] - ds[it]) < threshold }
                if (inliers.size > bestInliers.size) bestInliers = inliers
            }
            if (bestInliers.size < us.size * 0.04) return out
            val refit = leastSquares(bestInliers, us, vs, ds) ?: return out
            val inliers = pool.filter { abs(refit[0] * us[it] + refit[1] * vs[it] + refit[2] - ds[it]) < threshold }
            inliers.forEach { alive[it] = false }
            out += Region(refit[0], refit[1], refit[2], inliers.size)
        }
        return out
    }

    private fun leastSquares(indices: List<Int>, us: List<Float>, vs: List<Float>, ds: List<Float>): FloatArray? {
        val m = DoubleArray(9)
        val r = DoubleArray(3)
        indices.forEach { i ->
            val row = doubleArrayOf(us[i].toDouble(), vs[i].toDouble(), 1.0)
            for (a in 0..2) {
                for (b in 0..2) m[a * 3 + b] += row[a] * row[b]
                r[a] += row[a] * ds[i]
            }
        }
        return solve3(FloatArray(9) { m[it].toFloat() }, FloatArray(3) { r[it].toFloat() })
    }

    /** Solves a 3x3 system by Cramer's rule, or null when it is singular. */
    private fun solve3(m: FloatArray, r: FloatArray): FloatArray? {
        fun det(a: FloatArray) = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        val d = det(m)
        if (abs(d) < 1e-9f) return null
        return FloatArray(3) { col ->
            val c = m.copyOf()
            for (row in 0..2) c[row * 3 + col] = r[row]
            det(c) / d
        }
    }
}
