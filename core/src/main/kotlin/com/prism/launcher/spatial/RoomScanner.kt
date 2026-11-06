package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.floor

/**
 * The room as a cloud of small surface cells, built up from every depth frame the device sees.
 *
 * ## The same pipeline as Quest 3
 *
 * Quest 3 does not ask its cameras "is that a wall?". Its depth sensor measures distance, every
 * frame is fused into one 3D model of the room as the headset moves, and only then are walls, the
 * floor and the ceiling found -- as the large flat parts of that model, labelled by which way they
 * face relative to gravity (see [RoomLayout]). This is that first half: depth in, a fused surface
 * model out.
 *
 * The model is a sparse voxel grid, 5 cm per cell. Each cell keeps the average position and
 * surface normal of every depth sample that fell in it, so noise from one frame is averaged out by
 * the next and a wall seen from three places is one wall. A phone feeds it ARCore's depth images
 * from a moving camera; a computer feeds it depth estimated from a stationary webcam
 * ([MonocularDepth]). Planes a platform's own tracking found (ARCore's) can be added as samples too,
 * so textureless walls the depth missed still count.
 */
class RoomScanner(val voxelSize: Float = 0.05f, private val maxCells: Int = 250_000) {

    /** One cell's accumulated samples. */
    class Cell(val ix: Int, val iy: Int, val iz: Int) {
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        var weight = 0f

        val position: Vec3 get() = Vec3((sx / weight).toFloat(), (sy / weight).toFloat(), (sz / weight).toFloat())
        val normal: Vec3 get() = Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat()).normalized()

        /** How much the samples' normals agree: 1 for a clean plane, lower at edges and clutter. */
        val coherence: Float get() = (Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat()).length() / weight).coerceIn(0f, 1f)
    }

    private val cells = HashMap<Long, Cell>()

    /** Frames fused so far, for "keep looking around" hints. */
    @Volatile var frames: Int = 0
        private set

    private fun key(ix: Int, iy: Int, iz: Int): Long =
        ((ix.toLong() and 0x1FFFFF) shl 42) or ((iy.toLong() and 0x1FFFFF) shl 21) or (iz.toLong() and 0x1FFFFF)

    private fun index(v: Float) = floor(v / voxelSize).toInt()

    /** Adds one sample: a world position and the outward normal of the surface it is on. */
    @Synchronized
    fun addSample(p: Vec3, normal: Vec3, weight: Float = 1f) {
        val ix = index(p.x)
        val iy = index(p.y)
        val iz = index(p.z)
        val k = key(ix, iy, iz)
        val cell = cells[k] ?: run {
            if (cells.size >= maxCells) return
            Cell(ix, iy, iz).also { cells[k] = it }
        }
        cell.sx += p.x * weight; cell.sy += p.y * weight; cell.sz += p.z * weight
        cell.nx += normal.x * weight; cell.ny += normal.y * weight; cell.nz += normal.z * weight
        cell.weight += weight
    }

    /**
     * Fuses one depth image. Every [stride]th pixel becomes a sample; its normal comes from its
     * neighbours, and pixels on a depth edge (where the neighbours are on another surface) are
     * skipped rather than smeared across the gap.
     */
    fun addDepth(frame: DepthFrame, stride: Int = 2, near: Float = 0.25f, far: Float = 6f) {
        val k = frame.intrinsics
        val w = k.width
        val h = k.height
        fun point(u: Int, v: Int): Vec3? {
            val d = frame.depth[v * w + u]
            if (d < near || d > far || d.isNaN()) return null
            if (frame.confidence != null && frame.confidence[v * w + u] < 0.3f) return null
            return k.ray(u.toFloat(), v.toFloat()) * d
        }
        var v = 0
        while (v < h - stride) {
            var u = 0
            while (u < w - stride) {
                val p = point(u, v)
                // Neighbours a few pixels away: a wider baseline gives a far steadier normal.
                val gap = (stride * 2).coerceAtMost(minOf(w - 1 - u, h - 1 - v)).coerceAtLeast(1)
                val right = if (p != null) point(u + gap, v) else null
                val down = if (right != null) point(u, v + gap) else null
                if (p != null && right != null && down != null) {
                    val d = -p.z
                    if (abs(-right.z - d) < 0.06f * d && abs(-down.z - d) < 0.06f * d) {
                        var n = ((right - p) cross (down - p)).normalized()
                        // Outward means facing the camera, which is at the origin of camera space.
                        if (n dot p > 0f) n = -n
                        addSample(frame.pose.transform(p), frame.pose.rotate(n))
                    }
                }
                u += stride
            }
            v += stride
        }
        frames++
    }

    /** Cells seen often enough to trust. */
    @Synchronized
    fun cells(minWeight: Float = 2f): List<Cell> = cells.values.filter { it.weight >= minWeight }

    @Synchronized
    fun clear() {
        cells.clear()
        frames = 0
    }

    val size: Int @Synchronized get() = cells.size
}
