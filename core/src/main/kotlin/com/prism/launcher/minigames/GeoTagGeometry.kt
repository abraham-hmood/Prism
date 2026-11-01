package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The part of an ARCore plane that survives the tap.
 *
 * ARCore's [com.google.ar.core.Plane] object can be subsumed while the player is choosing a piece.
 * The center, axes and polygon are copied here so the artwork can still be nudged back inside the
 * surface it was tapped on instead of hanging over an edge.
 *
 * [polygon] is an interleaved list of plane-local x/z coordinates, matching ARCore's polygon layout.
 */
data class GeoTagPlane(
    val center: FloatArray,
    val axisX: FloatArray,
    val axisZ: FloatArray,
    val polygon: FloatArray?,
)

/**
 * The orientation of a piece of artwork on an ARCore plane.
 *
 * This is deliberately outside the Android renderer: the difference between "on the wall" and
 * "inside the wall" is a normal and a cross product, and that is much easier to verify with a
 * plain unit test than by walking around a room with a phone.
 *
 * The result is nine floats in column-major order: the image's right axis, its up axis, and the
 * axis the picture faces. The fourth vector -- where it sits -- comes from the ARCore anchor.
 */
object GeoTagGeometry {

    private val WORLD_UP = floatArrayOf(0f, 1f, 0f)
    private val WORLD_FORWARD = floatArrayOf(0f, 0f, -1f)

    /**
     * @param planeNormal the plane's normal in world space.
     * @param cameraPosition where the player's phone was when they tapped.
     * @param cameraForward the direction the camera was looking, in world space.
     * @param cameraUp the camera's up axis, used only as a fallback when the primary reference is
     *   nearly parallel to the plane.
     * @param anchorPosition the tapped point, used to decide which side of a wall the player is on.
     */
    fun basisFor(
        surface: GeoTagStore.Surface,
        planeNormal: FloatArray,
        cameraPosition: FloatArray,
        cameraForward: FloatArray,
        cameraUp: FloatArray,
        anchorPosition: FloatArray,
    ): FloatArray {
        var normal = normalize(planeNormal) ?: WORLD_UP.copyOf()
        val toCamera = floatArrayOf(
            cameraPosition[0] - anchorPosition[0],
            cameraPosition[1] - anchorPosition[1],
            cameraPosition[2] - anchorPosition[2],
        )

        if (surface == GeoTagStore.Surface.WALL) {
            // A wall has two sides. The picture belongs on the side the player can see.
            if (dot(normal, toCamera) < 0f) normal = scale(normal, -1f)
            var up = projected(WORLD_UP, normal)
            if (length(up) < 0.02f) up = projected(cameraUp, normal)
            if (length(up) < 0.02f) up = WORLD_UP
            val x = normalize(cross(up, normal)) ?: floatArrayOf(1f, 0f, 0f)
            val y = normalize(cross(normal, x)) ?: WORLD_UP
            return floatArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], normal[0], normal[1], normal[2])
        }

        // The floor faces up and the ceiling faces down, whatever ARCore's pose winding happens to
        // be. Otherwise a ceiling picture would be drawn into the room above it.
        if (surface == GeoTagStore.Surface.FLOOR && normal[1] < 0f) normal = scale(normal, -1f)
        if (surface == GeoTagStore.Surface.CEILING && normal[1] > 0f) normal = scale(normal, -1f)

        var forward = projected(cameraForward, normal)
        if (length(forward) < 0.05f) forward = projected(cameraUp, normal)
        if (length(forward) < 0.05f) forward = projected(WORLD_FORWARD, normal)
        if (length(forward) < 0.05f) forward = WORLD_FORWARD
        val x = normalize(cross(forward, normal)) ?: floatArrayOf(1f, 0f, 0f)
        val y = normalize(cross(normal, x)) ?: forward
        return floatArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], normal[0], normal[1], normal[2])
    }

    /**
     * Keeps the centre of a piece on the part of the plane that can actually hold it.
     *
     * ARCore's hit point is where the finger landed, which may be a few centimetres from the edge
     * of a detected wall. A one-metre picture centred there would hang into the room. This clamps
     * the centre so the oriented quad's bounding box stays inside the plane's polygon, and centres
     * the piece when the plane is smaller than the picture (there is nowhere better to put it).
     */
    fun fitCenter(
        anchor: FloatArray,
        basis: FloatArray,
        width: Float,
        height: Float,
        plane: GeoTagPlane?,
    ): FloatArray {
        if (plane == null) return anchor.copyOf()
        val polygon = plane.polygon
        if (polygon == null || polygon.size < 6) return anchor.copyOf()

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < polygon.size) {
            val x = polygon[i]
            val z = polygon[i + 1]
            if (x.isFinite() && z.isFinite()) {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minZ = min(minZ, z)
                maxZ = max(maxZ, z)
            }
            i += 2
        }
        if (!minX.isFinite() || !maxX.isFinite() || !minZ.isFinite() || !maxZ.isFinite()) {
            return anchor.copyOf()
        }

        val axisX = normalize(plane.axisX) ?: return anchor.copyOf()
        val axisZ = normalize(plane.axisZ) ?: return anchor.copyOf()
        val offset = floatArrayOf(
            anchor[0] - plane.center[0],
            anchor[1] - plane.center[1],
            anchor[2] - plane.center[2],
        )
        val localX = dot(offset, axisX)
        val localZ = dot(offset, axisZ)
        val artX = floatArrayOf(basis[0], basis[1], basis[2])
        val artY = floatArrayOf(basis[3], basis[4], basis[5])
        // How much of the picture's width and height falls along each plane axis. This is the
        // axis-aligned bounding box of the oriented quad in plane coordinates.
        val halfX = (abs(dot(artX, axisX)) * width + abs(dot(artY, axisX)) * height) * 0.5f
        val halfZ = (abs(dot(artX, axisZ)) * width + abs(dot(artY, axisZ)) * height) * 0.5f
        val fittedX = clampInside(localX, minX, maxX, halfX)
        val fittedZ = clampInside(localZ, minZ, maxZ, halfZ)
        return floatArrayOf(
            plane.center[0] + axisX[0] * fittedX + axisZ[0] * fittedZ,
            plane.center[1] + axisX[1] * fittedX + axisZ[1] * fittedZ,
            plane.center[2] + axisX[2] * fittedX + axisZ[2] * fittedZ,
        )
    }

    private fun clampInside(value: Float, low: Float, high: Float, half: Float): Float {
        if (high - low <= half * 2f) return (low + high) * 0.5f
        return value.coerceIn(low + half, high - half)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun scale(v: FloatArray, factor: Float): FloatArray =
        floatArrayOf(v[0] * factor, v[1] * factor, v[2] * factor)

    private fun length(v: FloatArray): Float = sqrt(dot(v, v))

    private fun normalize(v: FloatArray): FloatArray? {
        val len = length(v)
        if (len < 0.0001f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun projected(v: FloatArray, normal: FloatArray): FloatArray {
        val along = dot(v, normal)
        return floatArrayOf(
            v[0] - normal[0] * along,
            v[1] - normal[1] * along,
            v[2] - normal[2] * along,
        )
    }
}
