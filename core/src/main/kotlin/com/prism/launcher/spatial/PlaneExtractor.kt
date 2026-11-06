package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.cos

/** A flat region of the fused room model. [points] are the cells' positions. */
class Plane(val normal: Vec3, val centroid: Vec3, val points: List<Vec3>, val area: Float) {
    /** Signed distance of [p] from the plane, along [normal]. */
    fun distance(p: Vec3): Float = normal dot (p - centroid)
}

/**
 * Finds the flat parts of a [RoomScanner]'s model, by region growing: start at the best-measured
 * cell, take in neighbours that face the same way and lie on the same plane, refit, repeat. Then
 * pieces of one surface that clutter split apart (a wall behind a sofa) are merged back together.
 */
object PlaneExtractor {

    private val NORMAL_GROW = cos(Math.toRadians(35.0)).toFloat()
    private val NORMAL_MERGE = cos(Math.toRadians(8.0)).toFloat()

    /**
     * A growing region, with running sums so its least-squares plane can be refitted cheaply as it
     * grows. Membership is decided mainly by distance from that fitted plane: a single cell's own
     * normal is noisy (it comes from neighbouring depth samples, and a floor seen at a grazing
     * angle has the noisiest of all), so it is only checked loosely.
     */
    private class Region(seed: Int, p: Vec3, val facing: Vec3) {
        val members = arrayListOf(seed)
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var sxx = 0.0; var sxy = 0.0; var sxz = 0.0; var syy = 0.0; var syz = 0.0; var szz = 0.0
        var normal: Vec3 = facing
        var centroid: Vec3 = p

        init { add(p) }

        fun add(p: Vec3) {
            sx += p.x; sy += p.y; sz += p.z
            sxx += p.x * p.x; sxy += p.x * p.y; sxz += p.x * p.z
            syy += p.y * p.y; syz += p.y * p.z; szz += p.z * p.z
        }

        fun refit() {
            val n = (members.size).toDouble()
            if (n < 3) return
            val mx = sx / n; val my = sy / n; val mz = sz / n
            val c = doubleArrayOf(
                sxx / n - mx * mx, sxy / n - mx * my, sxz / n - mx * mz,
                sxy / n - mx * my, syy / n - my * my, syz / n - my * mz,
                sxz / n - mx * mz, syz / n - my * mz, szz / n - mz * mz,
            )
            centroid = Vec3(mx.toFloat(), my.toFloat(), mz.toFloat())
            smallestEigenvector(c)?.let { e -> normal = if ((e dot facing) < 0f) -e else e }
        }
    }

    fun extract(
        scanner: RoomScanner,
        minArea: Float = 0.15f,
        planeTolerance: Float = 0.05f,
        mergeTolerance: Float = 0.08f,
    ): List<Plane> {
        val cells = scanner.cells().filter { it.coherence > 0.4f }
        if (cells.isEmpty()) return emptyList()
        val voxel = scanner.voxelSize
        val minCells = (minArea / (voxel * voxel)).toInt().coerceAtLeast(12)
        fun key(x: Int, y: Int, z: Int) = ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
        val byKey = HashMap<Long, Int>(cells.size * 2)
        cells.forEachIndexed { i, c -> byKey[key(c.ix, c.iy, c.iz)] = i }
        val normals = Array(cells.size) { cells[it].normal }
        val positions = Array(cells.size) { cells[it].position }
        val assigned = BooleanArray(cells.size)
        val order = cells.indices.sortedByDescending { cells[it].weight * cells[it].coherence }

        val regions = ArrayList<Region>()
        val queue = ArrayDeque<Int>()
        for (seed in order) {
            if (assigned[seed]) continue
            val region = Region(seed, positions[seed], normals[seed])
            assigned[seed] = true
            queue.clear()
            queue.addLast(seed)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val c = cells[i]
                // Two cells out: noise spreads one surface over neighbouring layers of cells.
                for (dx in -2..2) for (dy in -2..2) for (dz in -2..2) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val j = byKey[key(c.ix + dx, c.iy + dy, c.iz + dz)] ?: continue
                    if (assigned[j]) continue
                    if (abs(normals[j] dot region.normal) < NORMAL_GROW) continue
                    if (abs(region.normal dot (positions[j] - region.centroid)) > planeTolerance) continue
                    assigned[j] = true
                    region.members += j
                    region.add(positions[j])
                    queue.addLast(j)
                    val size = region.members.size
                    if (size < 64 && size % 8 == 0 || size % 64 == 0) region.refit()
                }
            }
            region.refit()
            // A failed seed gives its cells back, so a better region can claim them later.
            if (region.members.size < minCells / 3) {
                region.members.forEach { assigned[it] = false }
                assigned[seed] = true
                continue
            }
            regions += region
        }

        // Merge pieces of one surface, by their FITTED planes.
        val merged = ArrayList<Region>()
        regions.sortedByDescending { it.members.size }.forEach { region ->
            val into = merged.firstOrNull { other ->
                (other.normal dot region.normal) > NORMAL_MERGE &&
                    abs(other.normal dot (region.centroid - other.centroid)) < mergeTolerance
            }
            if (into == null) merged += region
            else {
                region.members.forEach { into.members += it; into.add(positions[it]) }
                into.refit()
            }
        }
        return merged.filter { it.members.size >= minCells }.map { region ->
            val points = region.members.map { positions[it] }
            val (normal, center) = fit(points, region.normal)
            Plane(normal, center, points, region.members.size * voxel * voxel)
        }
    }

    /**
     * The plane that best fits [points]: the direction of least spread (a least-squares fit, by
     * eigen-decomposing the covariance), facing the same way as [facing], at the MEDIAN offset.
     *
     * Averaging the cells' own normals, as region growing does, is good enough to group them but
     * biased by every noisy cell; the fit is what decides where the wall actually is. The median
     * keeps a few stray cells (a picture frame, a skirting board) from pulling the plane off the
     * wall, which is what left pages hovering in front of it.
     */
    internal fun fit(points: List<Vec3>, facing: Vec3): Pair<Vec3, Vec3> {
        if (points.size < 3) return facing to (points.firstOrNull() ?: Vec3.ZERO)
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        points.forEach { cx += it.x; cy += it.y; cz += it.z }
        val n = points.size.toDouble()
        cx /= n; cy /= n; cz /= n
        val c = DoubleArray(9)
        points.forEach { p ->
            val dx = p.x - cx; val dy = p.y - cy; val dz = p.z - cz
            c[0] += dx * dx; c[1] += dx * dy; c[2] += dx * dz
            c[4] += dy * dy; c[5] += dy * dz; c[8] += dz * dz
        }
        c[3] = c[1]; c[6] = c[2]; c[7] = c[5]
        var normal = smallestEigenvector(c) ?: facing
        if ((normal dot facing) < 0f) normal = -normal
        val offsets = points.map { normal dot it }.sorted()
        val median = offsets[offsets.size / 2]
        val centroid = Vec3(cx.toFloat(), cy.toFloat(), cz.toFloat())
        return normal to (centroid - normal * ((normal dot centroid) - median))
    }

    /** The eigenvector of a symmetric 3x3 (row-major) with the smallest eigenvalue, by Jacobi rotations. */
    private fun smallestEigenvector(m: DoubleArray): Vec3? {
        val a = m.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(50) {
            var p = 0; var q = 1
            var largest = abs(a[1])
            if (abs(a[2]) > largest) { p = 0; q = 2; largest = abs(a[2]) }
            if (abs(a[5]) > largest) { p = 1; q = 2; largest = abs(a[5]) }
            if (largest < 1e-12) return@repeat
            val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
            val theta = 0.5 * Math.atan2(2 * apq, aqq - app)
            val cs = Math.cos(theta); val sn = Math.sin(theta)
            for (k in 0..2) {
                val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                a[k * 3 + p] = cs * akp - sn * akq
                a[k * 3 + q] = sn * akp + cs * akq
            }
            for (k in 0..2) {
                val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                a[p * 3 + k] = cs * apk - sn * aqk
                a[q * 3 + k] = sn * apk + cs * aqk
            }
            for (k in 0..2) {
                val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                v[k * 3 + p] = cs * vkp - sn * vkq
                v[k * 3 + q] = sn * vkp + cs * vkq
            }
        }
        val smallest = (0..2).minByOrNull { a[it * 3 + it] } ?: return null
        val e = Vec3(v[smallest].toFloat(), v[3 + smallest].toFloat(), v[6 + smallest].toFloat())
        return if (e.length() < 1e-6f) null else e.normalized()
    }
}
