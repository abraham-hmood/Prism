package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point or direction in metres, in Prism's room frame: Y is up (against gravity), and the
 * horizontal axes are whatever the platform's tracking chose. See [RoomFrame].
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length(): Float = sqrt(this dot this)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) this else Vec3(x / l, y / l, z / l)
    }

    /** The horizontal part. */
    fun flat(): Vec3 = Vec3(x, 0f, z)

    /** Turned about the up axis so its heading ([headingOf]) grows by [radians]. */
    fun rotatedY(radians: Float): Vec3 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec3(c * x - s * z, y, s * x + c * z)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/**
 * The heading of a horizontal direction, in radians: 0 along -Z, increasing clockwise seen from
 * above, so that with -Z pointing north it is a compass bearing.
 */
fun headingOf(direction: Vec3): Float = atan2(direction.x, -direction.z)

/** The horizontal unit direction with [heading] (see [headingOf]). */
fun directionOf(heading: Float): Vec3 = Vec3(sin(heading), 0f, -cos(heading))

/** The smallest signed difference between two angles, in (-PI, PI]. */
fun angleDifference(a: Float, b: Float): Float {
    var d = a - b
    while (d > Math.PI) d -= (2 * Math.PI).toFloat()
    while (d <= -Math.PI) d += (2 * Math.PI).toFloat()
    return d
}

internal fun nearlyZero(v: Float) = abs(v) < 1e-6f

/**
 * Where a camera was and which way it faced: camera-to-world, as a rotation (row-major 3x3) and a
 * translation. Camera axes follow OpenGL, as ARCore's do: +X right, +Y up, the camera looks
 * down -Z.
 */
class Pose(val rotation: FloatArray, val translation: Vec3) {
    init {
        require(rotation.size == 9)
    }

    fun rotate(v: Vec3) = Vec3(
        rotation[0] * v.x + rotation[1] * v.y + rotation[2] * v.z,
        rotation[3] * v.x + rotation[4] * v.y + rotation[5] * v.z,
        rotation[6] * v.x + rotation[7] * v.y + rotation[8] * v.z,
    )

    fun transform(v: Vec3) = rotate(v) + translation

    /** World to camera. */
    fun inverseTransform(v: Vec3): Vec3 {
        val p = v - translation
        return Vec3(
            rotation[0] * p.x + rotation[3] * p.y + rotation[6] * p.z,
            rotation[1] * p.x + rotation[4] * p.y + rotation[7] * p.z,
            rotation[2] * p.x + rotation[5] * p.y + rotation[8] * p.z,
        )
    }

    companion object {
        val IDENTITY = Pose(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), Vec3.ZERO)

        /** From a column-major 4x4 (OpenGL's and ARCore's `toMatrix`). */
        fun fromColumnMajor(m: FloatArray): Pose = Pose(
            floatArrayOf(m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10]),
            Vec3(m[12], m[13], m[14]),
        )
    }
}

/** Pinhole intrinsics, in pixels of the image they describe. Image v runs downwards. */
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int) {
    /** The camera-space ray (OpenGL axes) through pixel ([u], [v]) at depth 1. */
    fun ray(u: Float, v: Float) = Vec3((u - cx) / fx, -(v - cy) / fy, -1f)

    /** Where a camera-space point (OpenGL axes) lands in the image, or null when behind the camera. */
    fun project(p: Vec3): Pair<Float, Float>? {
        if (p.z > -1e-4f) return null
        val depth = -p.z
        return Pair(cx + fx * p.x / depth, cy - fy * p.y / depth)
    }
}

/**
 * One depth image: metres along the camera's view axis per pixel (0 where unknown), what camera
 * took it, and where that camera was.
 */
class DepthFrame(val depth: FloatArray, val intrinsics: Intrinsics, val pose: Pose, val confidence: FloatArray? = null) {
    init {
        require(depth.size == intrinsics.width * intrinsics.height)
    }
}
