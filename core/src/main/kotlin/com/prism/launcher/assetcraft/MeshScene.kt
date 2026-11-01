package com.prism.launcher.assetcraft

import com.prism.core.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A mesh a game model produced or a user imported.
 *
 * Vertices are xyz triples in the asset's own space; triangles are three indices into them. The one
 * colour is the fallback for every face, and [faceColors] is what a procedural build or a multi-part
 * import uses to keep a trunk brown while its canopy stays green. Nothing here is platform-specific:
 * a phone, a desktop and a unit test all read this same object.
 */
class GameMesh(
    val name: String,
    val vertices: FloatArray,
    val triangles: IntArray,
    val color: Int,
    val faceColors: IntArray = IntArray(triangles.size / 3) { color },
    val category: String = "prop",
    val animation: MeshAnimation = MeshAnimation.IDLE,
) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = triangles.size / 3

    fun bounds(): MeshBounds {
        if (vertices.isEmpty()) return MeshBounds(0f, 0f, 0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var index = 0
        while (index + 2 < vertices.size) {
            val x = vertices[index]
            val y = vertices[index + 1]
            val z = vertices[index + 2]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            index += 3
        }
        return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }
}

data class MeshBounds(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val depth: Float get() = maxZ - minZ
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
    val centerZ: Float get() = (minZ + maxZ) / 2f
    val largest: Float get() = max(width, max(height, depth)).coerceAtLeast(0.0001f)
}

/** What a mesh does when the viewport plays. */
enum class MeshAnimation(val label: String) {
    NONE("Still"),
    IDLE("Idle"),
    WALK("Walk"),
    SWAY("Sway"),
    SPIN("Spin"),
}

/** One mesh placed in a scene, with the transform the viewport draws it under. */
class MeshNode(
    val mesh: GameMesh,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var scale: Float = 1f,
    var yaw: Float = 0f,
) {
    val animation: MeshAnimation get() = mesh.animation
}

/** A scene is a list of objects plus the time the animation is sampled at. */
class MeshScene(val nodes: MutableList<MeshNode> = mutableListOf()) {

    fun add(node: MeshNode) {
        nodes.add(node)
    }

    fun removeAt(index: Int) {
        if (index in nodes.indices) nodes.removeAt(index)
    }

    fun clear() = nodes.clear()

    fun isEmpty(): Boolean = nodes.isEmpty()

    fun bounds(): MeshBounds? {
        if (nodes.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (node in nodes) {
            val local = node.mesh.bounds()
            val corners = floatArrayOf(
                local.minX, local.minY, local.minZ,
                local.maxX, local.minY, local.minZ,
                local.minX, local.maxY, local.minZ,
                local.maxX, local.maxY, local.minZ,
                local.minX, local.minY, local.maxZ,
                local.maxX, local.minY, local.maxZ,
                local.minX, local.maxY, local.maxZ,
                local.maxX, local.maxY, local.maxZ,
            )
            var index = 0
            while (index + 2 < corners.size) {
                val p = nodeTransform(node, corners[index], corners[index + 1], corners[index + 2])
                minX = min(minX, p[0]); maxX = max(maxX, p[0])
                minY = min(minY, p[1]); maxY = max(maxY, p[1])
                minZ = min(minZ, p[2]); maxZ = max(maxZ, p[2])
                index += 3
            }
        }
        return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Every vertex placed in world space at [time], by node. */
    fun posedVertices(time: Float): List<FloatArray> = nodes.map { node ->
        val source = node.mesh.vertices
        val out = FloatArray(source.size)
        var index = 0
        val yaw = node.yaw + if (node.animation == MeshAnimation.SPIN) time * 1.2f else 0f
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        while (index + 2 < source.size) {
            val x = source[index]
            val y = source[index + 1]
            val z = source[index + 2]
            // The animation moves the MESH, before the node's own placement. A tree sways around
            // its base and a creature bobs as a whole, which keeps the transforms independent.
            val animated = animate(node, x, y, z, time)
            val sx = animated[0] * node.scale
            val sy = animated[1] * node.scale
            val sz = animated[2] * node.scale
            out[index] = node.x + sx * cosY + sz * sinY
            out[index + 1] = node.y + sy
            out[index + 2] = node.z - sx * sinY + sz * cosY
            index += 3
        }
        out
    }

    private fun animate(node: MeshNode, x: Float, y: Float, z: Float, time: Float): FloatArray {
        val phase = time * 2f * PI.toFloat()
        return when (node.animation) {
            MeshAnimation.NONE -> floatArrayOf(x, y, z)
            MeshAnimation.IDLE -> floatArrayOf(
                x + sin(phase * 0.8f) * 0.015f,
                y + sin(phase) * 0.03f,
                z,
            )
            MeshAnimation.WALK -> {
                val swing = sin(phase) * 0.12f
                // Legs are the lower third of a creature. Pushing the left and right halves of the
                // X axis in opposite directions is enough to read as a stride from a distance.
                val leg = (0.45f - y.coerceIn(0f, 2f)).coerceAtLeast(0f)
                floatArrayOf(x + swing * x.coerceIn(-1f, 1f) * leg, y + abs(sin(phase)) * 0.04f, z)
            }
            MeshAnimation.SWAY -> {
                val bend = sin(phase * 0.7f) * 0.08f * y.coerceAtLeast(0f)
                floatArrayOf(x + bend, y, z)
            }
            MeshAnimation.SPIN -> floatArrayOf(x, y, z)
        }
    }

    private fun nodeTransform(node: MeshNode, x: Float, y: Float, z: Float): FloatArray {
        val cosY = cos(node.yaw)
        val sinY = sin(node.yaw)
        val sx = x * node.scale
        val sy = y * node.scale
        val sz = z * node.scale
        return floatArrayOf(
            node.x + sx * cosY + sz * sinY,
            node.y + sy,
            node.z - sx * sinY + sz * cosY,
        )
    }
}

/** A camera orbiting the centre of a scene. All values are plain numbers, so both UIs share it. */
data class MeshCamera(
    val yaw: Float = 0.65f,
    val pitch: Float = 0.28f,
    val distance: Float = 4.4f,
)

data class ScreenPoint(val node: Int, val x: Float, val y: Float, val depth: Float)

data class Face2D(
    val xs: FloatArray,
    val ys: FloatArray,
    val color: Int,
    val depth: Float,
    val node: Int,
)

data class Line2D(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int,
    val width: Float = 1f,
)

class MeshFrame(
    val faces: List<Face2D>,
    val grid: List<Line2D>,
    val edges: List<Line2D>,
    val centers: List<ScreenPoint>,
    val backgroundTop: Int = 0xFF0D1119.toInt(),
    val backgroundBottom: Int = 0xFF202C3D.toInt(),
)

/**
 * A painter's-algorithm viewport with a small directional light.
 *
 * ## Why not a graphics API
 *
 * Android has GL and desktop has Skia, and neither is shared with the other. A mesh viewer that
 * existed twice would be two places for picking, animation and camera maths to disagree, so the
 * projection and shading live here and both platforms draw the resulting triangles. The meshes this
 * page shows are small -- hundreds to a few thousand triangles -- which is exactly the range where
 * CPU projection is not a problem.
 *
 * Depth sorting instead of a z-buffer is a deliberate trade: a scene of separate game assets is
 * mostly non-intersecting solids, and a sorted triangle list draws those correctly. It also gives the
 * UI the faces in the order to paint them, which a z-buffer would hide inside a bitmap.
 */
object MeshView {

    /** Projects [scene] into triangles and lines at [width] x [height]. */
    fun frame(
        scene: MeshScene,
        camera: MeshCamera,
        width: Int,
        height: Int,
        time: Float,
        selected: Int = -1,
    ): MeshFrame {
        if (width <= 0 || height <= 0) return MeshFrame(emptyList(), emptyList(), emptyList(), emptyList())
        val bounds = scene.bounds() ?: return MeshFrame(emptyList(), emptyList(), emptyList(), emptyList())
        // EVERYTHING IS NORMALISED TO A TWO-UNIT BOX. An imported character can be 0.02 units tall or
        // 20,000; the camera should not care which. Centring X/Z and putting the floor at y = -1 is
        // what keeps a generated tree and an imported spaceship framed the same way.
        val scale = 2f / bounds.largest
        val cx = bounds.centerX
        val cz = bounds.centerZ
        val bottom = bounds.minY

        val yaw = camera.yaw
        val pitch = camera.pitch.coerceIn(-1.15f, 1.15f)
        val eyeX = sin(yaw) * cos(pitch) * camera.distance
        val eyeY = sin(pitch) * camera.distance
        val eyeZ = cos(yaw) * cos(pitch) * camera.distance
        val fx = -eyeX
        val fy = -eyeY
        val fz = -eyeZ
        val fLen = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(0.0001f)
        val forwardX = fx / fLen
        val forwardY = fy / fLen
        val forwardZ = fz / fLen
        // right = forward x up, with up = +Y. The sign matters: `(forwardZ, 0, -forwardX)` is the
        // LEFT vector for a forward of -Z, and pairing it with `up = right x forward` produced an
        // up vector of -Y -- so every preview in Prism was the scene rotated 180 degrees. A boulder
        // is symmetric enough that it went unnoticed; a creature with its tentacles upward is not.
        var rightX = -forwardZ
        var rightY = 0f
        var rightZ = forwardX
        val rightLen = sqrt(rightX * rightX + rightZ * rightZ).coerceAtLeast(0.0001f)
        rightX /= rightLen
        rightZ /= rightLen
        // up = right x forward.
        val upX = rightY * forwardZ - rightZ * forwardY
        val upY = rightZ * forwardX - rightX * forwardZ
        val upZ = rightX * forwardY - rightY * forwardX
        val focal = (height / 2f) / tan((48f * PI.toFloat() / 180f) / 2f)
        val halfW = width / 2f
        val halfH = height / 2f

        fun project(px: Float, py: Float, pz: Float): FloatArray? {
            val dx = px - eyeX
            val dy = py - eyeY
            val dz = pz - eyeZ
            val depth = dx * forwardX + dy * forwardY + dz * forwardZ
            if (depth <= 0.08f) return null
            val vx = dx * rightX + dy * rightY + dz * rightZ
            val vy = dx * upX + dy * upY + dz * upZ
            return floatArrayOf(halfW + vx * focal / depth, halfH - vy * focal / depth, depth)
        }

        val faces = ArrayList<Face2D>()
        val edges = ArrayList<Line2D>()
        val centers = ArrayList<ScreenPoint>()
        val light = normalized(0.45f, 0.8f, 0.42f)
        val world = scene.posedVertices(time)

        world.forEachIndexed { nodeIndex, raw ->
            val node = scene.nodes.getOrNull(nodeIndex) ?: return@forEachIndexed
            val vertices = FloatArray(raw.size)
            var index = 0
            while (index + 2 < raw.size) {
                vertices[index] = (raw[index] - cx) * scale
                vertices[index + 1] = (raw[index + 1] - bottom) * scale - 1f
                vertices[index + 2] = (raw[index + 2] - cz) * scale
                index += 3
            }

            val projected = arrayOfNulls<FloatArray>(vertices.size / 3)
            var vertex = 0
            var sumX = 0f
            var sumY = 0f
            var sumDepth = 0f
            while (vertex * 3 + 2 < vertices.size) {
                val p = project(vertices[vertex * 3], vertices[vertex * 3 + 1], vertices[vertex * 3 + 2])
                projected[vertex] = p
                if (p != null) {
                    sumX += p[0]
                    sumY += p[1]
                    sumDepth += p[2]
                }
                vertex++
            }
            if (vertex > 0) {
                centers += ScreenPoint(nodeIndex, sumX / vertex, sumY / vertex, sumDepth / vertex)
            }

            val mesh = node.mesh
            val tint = if (nodeIndex == selected) 1.18f else 1f
            val triangles = mesh.triangles
            var t = 0
            while (t + 2 < triangles.size) {
                val ia = triangles[t]
                val ib = triangles[t + 1]
                val ic = triangles[t + 2]
                val a = projected.getOrNull(ia)
                val b = projected.getOrNull(ib)
                val c = projected.getOrNull(ic)
                if (a != null && b != null && c != null) {
                    val area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
                    if (abs(area) > 0.05f) {
                        val nx = vertices[ib * 3] - vertices[ia * 3]
                        val ny = vertices[ib * 3 + 1] - vertices[ia * 3 + 1]
                        val nz = vertices[ib * 3 + 2] - vertices[ia * 3 + 2]
                        val mx = vertices[ic * 3] - vertices[ia * 3]
                        val my = vertices[ic * 3 + 1] - vertices[ia * 3 + 1]
                        val mz = vertices[ic * 3 + 2] - vertices[ia * 3 + 2]
                        val n = normalized(
                            ny * mz - nz * my,
                            nz * mx - nx * mz,
                            nx * my - ny * mx,
                        )
                        val diffuse = abs(n[0] * light[0] + n[1] * light[1] + n[2] * light[2])
                        val depth = (a[2] + b[2] + c[2]) / 3f
                        val fog = (depth / 9f).coerceIn(0f, 0.35f)
                        val base = mesh.faceColors.getOrElse(t / 3) { mesh.color }
                        val shaded = shade(base, (0.42f + 0.68f * diffuse) * tint)
                        faces += Face2D(
                            floatArrayOf(a[0], b[0], c[0]),
                            floatArrayOf(a[1], b[1], c[1]),
                            blend(shaded, 0xFF0D1119.toInt(), fog),
                            depth,
                            nodeIndex,
                        )
                        if (nodeIndex == selected) {
                            val outline = 0xCC4DA3FF.toInt()
                            edges += Line2D(a[0], a[1], b[0], b[1], outline)
                            edges += Line2D(b[0], b[1], c[0], c[1], outline)
                            edges += Line2D(c[0], c[1], a[0], a[1], outline)
                        }
                    }
                }
                t += 3
            }
        }

        // Painter's algorithm: far triangles first.
        faces.sortByDescending { it.depth }
        val grid = groundGrid(::project)

        return MeshFrame(faces, grid, edges, centers)
    }

    /** A floor grid under the scene, which is what makes the objects read as standing on something. */
    private fun groundGrid(project: (Float, Float, Float) -> FloatArray?): List<Line2D> {
        val lines = ArrayList<Line2D>()
        val half = 2.3f
        val step = 0.46f
        val y = -1.02f
        val faint = 0x2A9FB4D0.toInt()
        val stronger = 0x3F9FB4D0.toInt()
        var value = -half
        while (value <= half + 0.001f) {
            val x1 = project(value, y, -half)
            val x2 = project(value, y, half)
            if (x1 != null && x2 != null) {
                lines += Line2D(
                    x1[0], x1[1], x2[0], x2[1],
                    if (abs(value) < 0.01f) stronger else faint,
                )
            }
            val z1 = project(-half, y, value)
            val z2 = project(half, y, value)
            if (z1 != null && z2 != null) {
                lines += Line2D(
                    z1[0], z1[1], z2[0], z2[1],
                    if (abs(value) < 0.01f) stronger else faint,
                )
            }
            value += step
        }
        return lines
    }

    /** The node under a screen point, used by both viewports for tap-to-select. */
    fun pick(frame: MeshFrame, x: Float, y: Float, radius: Float = 32f): Int? {
        frame.centers
            .map { it to distance(it.x, it.y, x, y) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.let { return it.first.node }
        // No centre within reach: test the triangles themselves, nearest on screen first.
        for (face in frame.faces.asReversed()) {
            if (inside(face, x, y)) return face.node
        }
        return null
    }

    /**
     * Rasterises a frame into ARGB pixels.
     *
     * Used by ModelCraft's Try panel, where the result has to become a picture like every other
     * kind's test output. The interactive viewports paint the same [MeshFrame] with their own canvas.
     */
    fun render(
        scene: MeshScene,
        width: Int,
        height: Int,
        camera: MeshCamera = MeshCamera(),
        time: Float = 0f,
        selected: Int = -1,
    ): IntArray {
        val pixels = IntArray(width * height)
        val frame = frame(scene, camera, width, height, time, selected)
        for (row in 0 until height) {
            val t = row.toFloat() / height.coerceAtLeast(1)
            val colour = blend(frame.backgroundTop, frame.backgroundBottom, t)
            val offset = row * width
            for (column in 0 until width) pixels[offset + column] = colour
        }
        for (line in frame.grid) drawLine(pixels, width, height, line)
        for (face in frame.faces) fillTriangle(pixels, width, height, face)
        for (line in frame.edges) drawLine(pixels, width, height, line)
        return pixels
    }

    private fun fillTriangle(pixels: IntArray, width: Int, height: Int, face: Face2D) {
        val minX = max(0f, min(face.xs[0], min(face.xs[1], face.xs[2]))).toInt()
        val maxX = min(width - 1f, max(face.xs[0], max(face.xs[1], face.xs[2]))).toInt()
        val minY = max(0f, min(face.ys[0], min(face.ys[1], face.ys[2]))).toInt()
        val maxY = min(height - 1f, max(face.ys[0], max(face.ys[1], face.ys[2]))).toInt()
        if (minX > maxX || minY > maxY) return
        val ax = face.xs[0]
        val ay = face.ys[0]
        val bx = face.xs[1]
        val by = face.ys[1]
        val cx = face.xs[2]
        val cy = face.ys[2]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 0.0001f) return
        for (row in minY..maxY) {
            val py = row + 0.5f
            for (column in minX..maxX) {
                val px = column + 0.5f
                val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
                val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
                val w2 = 1f - w0 - w1
                if (w0 >= -0.002f && w1 >= -0.002f && w2 >= -0.002f) {
                    pixels[row * width + column] = face.color
                }
            }
        }
    }

    private fun drawLine(pixels: IntArray, width: Int, height: Int, line: Line2D) {
        var x0 = line.x1.toInt()
        var y0 = line.y1.toInt()
        val x1 = line.x2.toInt()
        val y1 = line.y2.toInt()
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        var guard = 0
        while (guard++ < 4096) {
            if (x0 in 0 until width && y0 in 0 until height) {
                val index = y0 * width + x0
                pixels[index] = blend(pixels[index], line.color, alphaOf(line.color) / 255f)
            }
            if (x0 == x1 && y0 == y1) break
            val doubled = 2 * error
            if (doubled >= dy) {
                error += dy
                x0 += sx
            }
            if (doubled <= dx) {
                error += dx
                y0 += sy
            }
        }
    }

    private fun inside(face: Face2D, x: Float, y: Float): Boolean {
        val ax = face.xs[0]; val ay = face.ys[0]
        val bx = face.xs[1]; val by = face.ys[1]
        val cx = face.xs[2]; val cy = face.ys[2]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 0.0001f) return false
        val w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / area
        val w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / area
        val w2 = 1f - w0 - w1
        return w0 >= 0f && w1 >= 0f && w2 >= 0f
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x0 - x1
        val dy = y0 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun normalized(x: Float, y: Float, z: Float): FloatArray {
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
        return floatArrayOf(x / length, y / length, z / length)
    }

    private fun shade(color: Int, factor: Float): Int {
        val r = (((color ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun alphaOf(color: Int): Float = ((color ushr 24) and 0xFF) / 255f

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val a = ((from ushr 24) and 0xFF) * (1 - t) + ((to ushr 24) and 0xFF) * t
        val r = ((from ushr 16) and 0xFF) * (1 - t) + ((to ushr 16) and 0xFF) * t
        val g = ((from ushr 8) and 0xFF) * (1 - t) + ((to ushr 8) and 0xFF) * t
        val b = (from and 0xFF) * (1 - t) + (to and 0xFF) * t
        return (a.toInt().coerceIn(0, 255) shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }
}

// ── Procedural generation ───────────────────────────────────────────────────────────────────────

/**
 * Turns a game model's predicted shape vector into an actual mesh.
 *
 * The trainer learns a sixteen-number description of the shapes in the dataset: proportions, a
 * radial silhouette, a vertical profile and roughness. That is a real, compact representation of a
 * game asset, and this builder materialises it. Different categories use the numbers differently --
 * a tree spends the silhouette on its canopy, a building on its footprint -- which is why the prompt
 * picks the category and the model shapes it, the same split the other kinds' tests describe.
 */
object MeshGenerator {

    const val SHAPE_DIM = 16

    private val TREE_WORDS = setOf(
        "tree", "forest", "wood", "timber", "oak", "pine", "bush", "plant", "leaf",
        "branch", "trunk", "canopy",
    )
    private val BUILDING_WORDS = setOf(
        "house", "building", "tower", "watchtower", "castle", "temple", "shrine", "shop", "hut",
        "ruin", "ruined", "ruins", "wall", "bridge", "gate", "fort", "arch", "tomb", "crypt",
    )
    private val ROCK_WORDS = setOf("rock", "stone", "boulder", "mountain", "cliff", "asteroid", "meteor")
    private val TERRAIN_WORDS = setOf(
        "terrain", "landscape", "island", "ground", "world", "scene", "field", "hill", "valley",
        "crater", "cavern", "cave",
    )
    private val CRYSTAL_WORDS = setOf("crystal", "gem", "shard", "ice", "glass", "diamond", "portal")
    private val CREATURE_WORDS = setOf(
        "creature", "monster", "character", "robot", "beast", "dragon", "alien", "person",
        "enemy", "tentacle", "tentacled", "squid", "horror", "aberration",
    )
    private val PROP_WORDS = setOf(
        "sword", "weapon", "chest", "barrel", "crate", "prop", "pillar", "column", "statue",
        "cart", "wagon", "carriage", "lantern", "shield", "banner", "well", "signpost",
    )

    /** A readable category for [prompt], or one chosen from the shape when the words say nothing. */
    fun category(prompt: String, shape: DoubleArray, seed: Long): String {
        val words = prompt.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        fun hit(set: Set<String>) = words.any { it in set }
        return when {
            hit(TREE_WORDS) -> "tree"
            hit(BUILDING_WORDS) -> "building"
            hit(CREATURE_WORDS) -> "creature"
            hit(CRYSTAL_WORDS) -> "crystal"
            hit(TERRAIN_WORDS) -> "terrain"
            hit(ROCK_WORDS) -> "rock"
            hit(PROP_WORDS) -> "prop"
            else -> {
                // The shape itself decides when the prompt is generic: tall and narrow reads as a
                // tree or a tower, flat reads as ground, and anything blob-like is a rock or a
                // creature. The seed breaks ties deterministically.
                val height = shape.getOrElse(1) { 0.0 }
                val flatness = shape.getOrElse(7) { 0.0 }
                when {
                    flatness > 0.35 -> "terrain"
                    height > 0.35 -> if (seed and 1L == 0L) "tree" else "building"
                    height < -0.45 -> "rock"
                    else -> if (seed % 3L == 0L) "creature" else "rock"
                }
            }
        }
    }

    fun fromShape(shape: DoubleArray, prompt: String, seed: Long): GameMesh {
        val random = java.util.Random(seed)
        val category = category(prompt, shape, seed)
        val value = { index: Int -> (((shape.getOrElse(index) { 0.0 }).coerceIn(-1.0, 1.0)) + 1.0) / 2.0 }
        val radial = DoubleArray(4) { value(8 + it).toFloat().toDouble() }
        val vertical = DoubleArray(4) { value(12 + it) }
        val roughness = value(15).coerceIn(0.0, 1.0)
        val bboxX = 0.35 + value(0) * 1.15
        val bboxY = 0.35 + value(1) * 1.15
        val bboxZ = 0.35 + value(2) * 1.15
        val builder = Builder()
        val palette = Palette(category, seed)
        when (category) {
            "tree" -> tree(builder, palette, bboxX, bboxY, bboxZ, radial, vertical, roughness, random)
            "building" -> building(builder, palette, bboxX, bboxY, bboxZ, roughness)
            "creature" -> creature(
                builder, palette, bboxX, bboxY, bboxZ, radial, vertical, roughness, random,
                tentacled = prompt.lowercase(Locale.ROOT).let {
                    it.contains("tentacle") || it.contains("squid") || it.contains("octopus")
                },
            )
            "crystal" -> crystal(builder, palette, bboxX, bboxY, bboxZ, roughness, random)
            "terrain" -> terrain(builder, palette, bboxX, bboxY, bboxZ, roughness, random)
            "prop" -> prop(builder, palette, bboxX, bboxY, bboxZ, roughness, prompt)
            else -> rock(builder, palette, bboxX, bboxY, bboxZ, radial, vertical, roughness, random)
        }
        val animation = when (category) {
            "tree" -> MeshAnimation.SWAY
            "creature" -> MeshAnimation.WALK
            "crystal" -> MeshAnimation.SPIN
            "rock" -> MeshAnimation.IDLE
            "terrain" -> MeshAnimation.NONE
            else -> MeshAnimation.IDLE
        }
        return builder.build(
            name = "Generated $category",
            color = palette.primary,
            category = category,
            animation = animation,
        )
    }

    private fun tree(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        radial: DoubleArray,
        vertical: DoubleArray,
        roughness: Double,
        random: java.util.Random,
    ) {
        val height = (1.6 + sy * 1.3).toFloat()
        val trunk = (0.08 + sx * 0.07).toFloat()
        builder.cylinder(0f, 0f, 0f, trunk, height * 0.66f, 10, palette.trunk, palette.trunk)
        // A PINE IS LAYERED CONES, NOT BALLS ON A STICK. Three overlapping skirts with a smaller
        // crown read as a conifer from any angle, and the top tier reaches past the trunk so the
        // silhouette tapers instead of ending in a lollipop. Radial/vertical descriptors still
        // decide how broad and how tall each skirt is.
        val tiers = 4
        for (tier in 0 until tiers) {
            val t = tier.toFloat() / (tiers - 1)
            val base = height * (0.30f + t * 0.54f)
            val radius = (0.62f - t * 0.36f) *
                (0.72f + radial[tier.coerceAtMost(3)].toFloat() * 0.34f) + sx.toFloat() * 0.08f
            val coneHeight = height * (0.30f - t * 0.075f)
            builder.cone(
                0f,
                base,
                0f,
                radius,
                coneHeight,
                sides = 12,
                color = if (tier % 2 == 0) palette.primary else palette.secondary,
                capColor = palette.primary,
                jitter = roughness.toFloat() * 0.08f,
                random = random,
            )
        }
    }

    private fun building(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        roughness: Double,
    ) {
        val width = (0.8 + sx * 0.7).toFloat()
        val depth = (0.7 + sz * 0.7).toFloat()
        val height = (1.0 + sy * 1.8).toFloat()
        builder.box(0f, height / 2f, 0f, width, height, depth, palette.primary, palette.secondary)
        builder.pyramid(0f, height, 0f, max(width, depth) * 0.62f, 0.45f + height * 0.22f, palette.roof)
        // A doorway and a row of windows. They are boxes sitting just proud of the wall, which is
        // enough to read as a building without a texture pipeline.
        val face = depth / 2f + 0.01f
        builder.box(0f, height * 0.22f, face, width * 0.22f, height * 0.38f, 0.06f, palette.dark)
        val windows = 3
        for (index in 0 until windows) {
            val x = (-0.32f + index * 0.32f) * width
            builder.box(x, height * 0.68f, face, width * 0.12f, height * 0.14f, 0.05f, palette.window)
        }
        if (roughness > 0.55) {
            builder.box(width * 0.55f, height * 0.9f, -depth * 0.2f, 0.16f, height * 0.5f, 0.16f, palette.dark)
        }
    }

    private fun creature(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        radial: DoubleArray,
        vertical: DoubleArray,
        roughness: Double,
        random: java.util.Random,
        tentacled: Boolean = false,
    ) {
        val bodyY = 0.9f + (sy * 0.35).toFloat()
        val bodyR = (0.36 + radial[1] * 0.18 + sx * 0.1).toFloat()
        builder.blob(0f, bodyY, 0f, bodyR, (0.42 + vertical[1] * 0.2).toFloat(), bodyR * 0.8f, 10, 16, roughness.toFloat() * 0.08f, random, palette.primary)
        val headY = bodyY + 0.52f + (vertical[2] * 0.16).toFloat()
        val headR = (0.22 + radial[2] * 0.1).toFloat()
        builder.blob(0f, headY, 0f, headR, headR * 1.05f, headR * 0.9f, 8, 14, roughness.toFloat() * 0.05f, random, palette.secondary)
        // Eyes, so the front of the creature is unambiguous while it turns.
        val eyeZ = headR * 0.82f
        builder.box(-headR * 0.36f, headY + headR * 0.12f, eyeZ, headR * 0.16f, headR * 0.2f, 0.05f, palette.dark)
        builder.box(headR * 0.36f, headY + headR * 0.12f, eyeZ, headR * 0.16f, headR * 0.2f, 0.05f, palette.dark)
        val legX = bodyR * 0.52f
        val legHeight = bodyY * 0.55f
        builder.tube(-legX, 0f, 0f, -legX, legHeight, 0f, bodyR * 0.22f, bodyR * 0.18f, 8, palette.secondary)
        builder.tube(legX, 0f, 0f, legX, legHeight, 0f, bodyR * 0.22f, bodyR * 0.18f, 8, palette.secondary)
        builder.tube(
            -bodyR, bodyY + bodyR * 0.22f, 0f,
            -bodyR * 1.55f, bodyY - bodyR * 0.32f, bodyR * 0.24f,
            bodyR * 0.18f, bodyR * 0.1f, 7, palette.secondary,
        )
        builder.tube(
            bodyR, bodyY + bodyR * 0.22f, 0f,
            bodyR * 1.55f, bodyY - bodyR * 0.32f, bodyR * 0.24f,
            bodyR * 0.18f, bodyR * 0.1f, 7, palette.secondary,
        )
        if (tentacled) {
            // Five tapering, curling arms under the body. Each is a chain of short tubes because a
            // single cylinder cannot bend; the last segment lifts so the tips do not all stab the
            // ground, which is what made the first version read as a table leg.
            repeat(5) { index ->
                val angle = (index / 5f) * 2f * PI.toFloat() + random.nextFloat() * 0.22f
                var x = cos(angle) * bodyR * 0.7f
                var z = sin(angle) * bodyR * 0.7f
                var y = bodyY - bodyR * 0.55f
                var radius = bodyR * (0.22f + random.nextFloat() * 0.06f)
                repeat(4) { segment ->
                    val step = bodyR * (0.75f - segment * 0.09f)
                    val nextX = x + cos(angle) * step
                    val nextZ = z + sin(angle) * step
                    val nextY = y - bodyR * (0.28f - segment * 0.04f) + if (segment == 3) bodyR * 0.42f else 0f
                    val nextRadius = radius * 0.76f
                    builder.tube(x, y, z, nextX, nextY, nextZ, radius, nextRadius, 7, palette.secondary)
                    x = nextX; y = nextY; z = nextZ; radius = nextRadius
                }
            }
        }
    }

    private fun crystal(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        roughness: Double,
        random: java.util.Random,
    ) {
        val radius = (0.42 + sx * 0.5).toFloat()
        val height = (1.2 + sy * 1.6).toFloat()
        val sides = 6
        val baseY = 0.25f + random.nextFloat() * 0.1f
        builder.bipyramid(0f, baseY, 0f, radius, height, sides, palette.primary, palette.secondary)
        repeat(3) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val offset = radius * (0.9f + random.nextFloat() * 0.8f)
            val shardHeight = height * (0.3f + random.nextFloat() * 0.35f)
            builder.bipyramid(
                cos(angle) * offset,
                0.05f,
                sin(angle) * offset,
                radius * (0.22f + random.nextFloat() * 0.25f),
                shardHeight,
                5,
                palette.secondary,
                palette.primary,
            )
        }
    }

    private fun terrain(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        roughness: Double,
        random: java.util.Random,
    ) {
        val width = (1.8 + sx * 0.8).toFloat()
        val depth = (1.8 + sz * 0.8).toFloat()
        val cells = 9
        val amplitude = (0.08 + sy * 0.22 + roughness * 0.14).toFloat()
        val heights = Array(cells + 1) { FloatArray(cells + 1) }
        for (row in 0..cells) {
            for (column in 0..cells) {
                heights[row][column] = (random.nextFloat() - 0.5f) * 2f * amplitude
            }
        }
        val indices = Array(cells + 1) { IntArray(cells + 1) }
        for (row in 0..cells) {
            for (column in 0..cells) {
                val x = -width / 2f + width * column / cells
                val z = -depth / 2f + depth * row / cells
                indices[row][column] = builder.vertex(x, heights[row][column], z)
            }
        }
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val a = indices[row][column]
                val b = indices[row][column + 1]
                val c = indices[row + 1][column + 1]
                val d = indices[row + 1][column]
                val colour = blend(palette.primary, palette.secondary, (row + column).toFloat() / (cells * 2f))
                builder.quad(a, b, c, d, colour)
            }
        }
    }

    private fun prop(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        roughness: Double,
        prompt: String,
    ) {
        val width = (0.55 + sx * 0.5).toFloat()
        val height = (0.65 + sy * 1.2).toFloat()
        val depth = (0.55 + sz * 0.5).toFloat()
        val lowered = prompt.lowercase(Locale.ROOT)
        when {
            lowered.contains("cart") || lowered.contains("wagon") || lowered.contains("carriage") -> {
                // A cart is a shallow bed with four wheels and two shafts. The wheels are boxes
                // rather than cylinders: the low-poly builder has no rotated cylinder, and a
                // chunky wheel reads better than a disk floating on its edge.
                val bedHeight = height * 0.55f
                // The bed sits above the axles so the wheels are visible from the side.
                builder.box(0f, bedHeight * 1.18f, 0f, width * 1.6f, bedHeight * 0.32f, depth * 1.1f, palette.primary)
                builder.box(0f, bedHeight * 1.42f, 0f, width * 1.68f, bedHeight * 0.1f, depth * 1.18f, palette.secondary)
                // Wheels are short tubes along X, not vertical boxes: a box wheel reads as a post,
                // which is exactly what the first cart looked like.
                repeat(4) { wheel ->
                    val x = if (wheel % 2 == 0) -width * 0.82f else width * 0.82f
                    val z = if (wheel < 2) -depth * 0.42f else depth * 0.42f
                    val halfThickness = width * 0.035f
                    builder.tube(
                        x - halfThickness, bedHeight * 0.78f, z,
                        x + halfThickness, bedHeight * 0.78f, z,
                        bedHeight * 0.4f, bedHeight * 0.4f, 16, palette.trunk,
                    )
                    builder.tube(
                        x - halfThickness * 1.2f, bedHeight * 0.78f, z,
                        x + halfThickness * 1.2f, bedHeight * 0.78f, z,
                        bedHeight * 0.14f, bedHeight * 0.14f, 10, palette.dark,
                    )
                }
                builder.tube(
                    -width * 0.86f, bedHeight * 0.78f, -depth * 0.42f,
                    width * 0.86f, bedHeight * 0.78f, -depth * 0.42f,
                    width * 0.025f, width * 0.025f, 8, palette.dark,
                )
                builder.tube(
                    -width * 0.86f, bedHeight * 0.78f, depth * 0.42f,
                    width * 0.86f, bedHeight * 0.78f, depth * 0.42f,
                    width * 0.025f, width * 0.025f, 8, palette.dark,
                )
                builder.tube(
                    -width * 0.36f, bedHeight * 1.0f, -depth * 0.72f,
                    -width * 0.36f, bedHeight * 0.55f, -depth * 1.38f,
                    width * 0.035f, width * 0.03f, 8, palette.trunk,
                )
                builder.tube(
                    width * 0.36f, bedHeight * 1.0f, -depth * 0.72f,
                    width * 0.36f, bedHeight * 0.55f, -depth * 1.38f,
                    width * 0.035f, width * 0.03f, 8, palette.trunk,
                )
            }
            lowered.contains("barrel") -> {
                builder.cylinder(0f, 0f, 0f, width * 0.52f, height, 14, palette.primary, palette.secondary)
                builder.cylinder(0f, height * 0.18f, 0f, width * 0.56f, height * 0.08f, 14, palette.dark)
                builder.cylinder(0f, height * 0.72f, 0f, width * 0.56f, height * 0.08f, 14, palette.dark)
            }
            lowered.contains("chest") || lowered.contains("crate") -> {
                val body = height * 0.66f
                builder.box(0f, body / 2f, 0f, width * 1.3f, body, depth, palette.primary)
                builder.box(0f, body * 1.12f, 0f, width * 1.34f, body * 0.24f, depth * 1.03f, palette.secondary)
                builder.box(0f, body * 0.72f, depth * 0.5f, width * 0.16f, body * 0.36f, 0.07f, palette.dark)
                builder.box(-width * 0.34f, body * 0.92f, 0f, 0.07f, body * 0.9f, depth * 0.92f, palette.dark)
                builder.box(width * 0.34f, body * 0.92f, 0f, 0.07f, body * 0.9f, depth * 0.92f, palette.dark)
            }
            else -> {
                builder.box(0f, height / 2f, 0f, width, height, depth, palette.primary, palette.secondary)
                builder.box(0f, height + 0.07f, 0f, width * 1.08f, 0.14f, depth * 1.08f, palette.secondary)
                if (roughness > 0.4) {
                    builder.box(width * 0.5f, height * 0.55f, 0f, 0.1f, height * 0.4f, 0.1f, palette.dark)
                }
            }
        }
    }

    private fun rock(
        builder: Builder,
        palette: Palette,
        sx: Double,
        sy: Double,
        sz: Double,
        radial: DoubleArray,
        vertical: DoubleArray,
        roughness: Double,
        random: java.util.Random,
    ) {
        val radiusX = (0.55 + sx * 0.6 + radial[0] * 0.2).toFloat()
        val radiusY = (0.35 + sy * 0.45 + vertical[1] * 0.15).toFloat()
        val radiusZ = (0.55 + sz * 0.6 + radial[2] * 0.2).toFloat()
        builder.blob(0f, radiusY, 0f, radiusX, radiusY, radiusZ, 10, 18, (0.08 + roughness * 0.18).toFloat(), random, palette.primary)
        repeat(2) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            builder.blob(
                cos(angle) * radiusX * 0.8f,
                radiusY * 0.35f,
                sin(angle) * radiusZ * 0.8f,
                radiusX * (0.22f + random.nextFloat() * 0.2f),
                radiusY * (0.3f + random.nextFloat() * 0.2f),
                radiusZ * (0.22f + random.nextFloat() * 0.2f),
                6, 11, (0.1 + roughness * 0.18).toFloat(), random, palette.secondary,
            )
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = ((from ushr 24) and 0xFF) * (1 - f) + ((to ushr 24) and 0xFF) * f
        val r = ((from ushr 16) and 0xFF) * (1 - f) + ((to ushr 16) and 0xFF) * f
        val g = ((from ushr 8) and 0xFF) * (1 - f) + ((to ushr 8) and 0xFF) * f
        val b = (from and 0xFF) * (1 - f) + (to and 0xFF) * f
        return (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private class Palette(category: String, seed: Long) {
        private val random = java.util.Random(seed xor 0x51A7E)
        val primary: Int
        val secondary: Int
        val dark: Int = 0xFF23282F.toInt()
        val trunk: Int = 0xFF6B4A2F.toInt()
        val roof: Int
        val window: Int = 0xFFFFD36A.toInt()

        init {
            when (category) {
                "tree" -> {
                    primary = vary(0xFF3F8B4A.toInt(), 0.16f)
                    secondary = vary(0xFF57A55E.toInt(), 0.2f)
                    roof = secondary
                }
                "building" -> {
                    primary = vary(0xFFB89A72.toInt(), 0.12f)
                    secondary = vary(0xFF8E7250.toInt(), 0.1f)
                    roof = vary(0xFF7A4B3A.toInt(), 0.12f)
                }
                "creature" -> {
                    primary = vary(0xFF8A5CC4.toInt(), 0.18f)
                    secondary = vary(0xFFD18A5C.toInt(), 0.15f)
                    roof = secondary
                }
                "crystal" -> {
                    primary = vary(0xFF4FC3D9.toInt(), 0.18f)
                    secondary = vary(0xFF9BE7F2.toInt(), 0.12f)
                    roof = primary
                }
                "terrain" -> {
                    primary = vary(0xFF4C7A45.toInt(), 0.16f)
                    secondary = vary(0xFF6B8F55.toInt(), 0.14f)
                    roof = primary
                }
                else -> {
                    primary = vary(0xFF7C8188.toInt(), 0.14f)
                    secondary = vary(0xFF9AA0A6.toInt(), 0.12f)
                    roof = primary
                }
            }
        }

        private fun vary(base: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val value = (base ushr shift) and 0xFF
                val delta = (random.nextFloat() * 2f - 1f) * amount * 255f
                return (value + delta).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    /** Accumulates vertices, triangles and per-face colours while a category builds itself. */
    private class Builder {
        private val vertices = ArrayList<Float>()
        private val triangles = ArrayList<Int>()
        private val colors = ArrayList<Int>()

        fun vertex(x: Float, y: Float, z: Float): Int {
            vertices += x
            vertices += y
            vertices += z
            return vertices.size / 3 - 1
        }

        fun tri(a: Int, b: Int, c: Int, color: Int) {
            triangles += a; triangles += b; triangles += c
            colors += color
        }

        fun quad(a: Int, b: Int, c: Int, d: Int, color: Int) {
            tri(a, b, c, color)
            tri(a, c, d, color)
        }

        fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, color: Int, topColor: Int = color) {
            val x0 = cx - sx / 2f; val x1 = cx + sx / 2f
            val y0 = cy - sy / 2f; val y1 = cy + sy / 2f
            val z0 = cz - sz / 2f; val z1 = cz + sz / 2f
            val a = vertex(x0, y0, z0); val b = vertex(x1, y0, z0)
            val c = vertex(x1, y0, z1); val d = vertex(x0, y0, z1)
            val e = vertex(x0, y1, z0); val f = vertex(x1, y1, z0)
            val g = vertex(x1, y1, z1); val h = vertex(x0, y1, z1)
            quad(a, b, c, d, color)
            quad(e, h, g, f, topColor)
            quad(a, e, f, b, color)
            quad(b, f, g, c, color)
            quad(c, g, h, d, color)
            quad(d, h, e, a, color)
        }

        fun cylinder(cx: Float, baseY: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Int, capColor: Int = color) {
            val bottom = ArrayList<Int>()
            val top = ArrayList<Int>()
            for (index in 0 until segments) {
                val angle = index.toFloat() / segments * 2f * PI.toFloat()
                bottom += vertex(cx + cos(angle) * radius, baseY, cz + sin(angle) * radius)
                top += vertex(cx + cos(angle) * radius, baseY + height, cz + sin(angle) * radius)
            }
            for (index in 0 until segments) {
                val next = (index + 1) % segments
                quad(bottom[index], bottom[next], top[next], top[index], color)
            }
            val bottomCenter = vertex(cx, baseY, cz)
            val topCenter = vertex(cx, baseY + height, cz)
            for (index in 0 until segments) {
                val next = (index + 1) % segments
                tri(bottomCenter, bottom[next], bottom[index], capColor)
                tri(topCenter, top[index], top[next], capColor)
            }
        }

        /** A radial skirt with a closed base, used for the layered canopy of a conifer. */
        fun cone(
            cx: Float,
            baseY: Float,
            cz: Float,
            radius: Float,
            height: Float,
            sides: Int,
            color: Int,
            capColor: Int = color,
            jitter: Float = 0f,
            random: java.util.Random = java.util.Random(0L),
        ) {
            val ring = IntArray(sides) { index ->
                val angle = index.toFloat() / sides * 2f * PI.toFloat()
                val wobble = 1f + (random.nextFloat() * 2f - 1f) * jitter
                vertex(cx + cos(angle) * radius * wobble, baseY, cz + sin(angle) * radius * wobble)
            }
            val apex = vertex(cx, baseY + height, cz)
            val base = vertex(cx, baseY, cz)
            for (index in 0 until sides) {
                val next = (index + 1) % sides
                tri(ring[index], ring[next], apex, color)
                tri(base, ring[next], ring[index], capColor)
            }
        }

        /** A tapered tube between two points, for limbs and tentacles that cannot be axis aligned. */
        fun tube(
            x0: Float,
            y0: Float,
            z0: Float,
            x1: Float,
            y1: Float,
            z1: Float,
            radius0: Float,
            radius1: Float,
            sides: Int,
            color: Int,
        ) {
            var ax = x1 - x0
            var ay = y1 - y0
            var az = z1 - z0
            val length = sqrt(ax * ax + ay * ay + az * az)
            if (length < 0.00001f) return
            ax /= length; ay /= length; az /= length
            // Any vector not parallel to the axis works as the first basis direction. Y is the
            // usual choice, with X as the fallback for a vertical limb.
            val helperX = if (abs(ay) > 0.9f) 1f else 0f
            val helperY = if (abs(ay) > 0.9f) 0f else 1f
            var ux = ay * 0f - az * helperY
            var uy = az * helperX - ax * 0f
            var uz = ax * helperY - ay * helperX
            val uLength = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(0.00001f)
            ux /= uLength; uy /= uLength; uz /= uLength
            val vx = ay * uz - az * uy
            val vy = az * ux - ax * uz
            val vz = ax * uy - ay * ux
            val startRing = IntArray(sides)
            val endRing = IntArray(sides)
            for (index in 0 until sides) {
                val angle = index.toFloat() / sides * 2f * PI.toFloat()
                val c = cos(angle)
                val s = sin(angle)
                startRing[index] = vertex(
                    x0 + (ux * c + vx * s) * radius0,
                    y0 + (uy * c + vy * s) * radius0,
                    z0 + (uz * c + vz * s) * radius0,
                )
                endRing[index] = vertex(
                    x1 + (ux * c + vx * s) * radius1,
                    y1 + (uy * c + vy * s) * radius1,
                    z1 + (uz * c + vz * s) * radius1,
                )
            }
            for (index in 0 until sides) {
                val next = (index + 1) % sides
                quad(startRing[index], endRing[index], endRing[next], startRing[next], color)
            }
            // Closed ends. Without them a wheel is a hollow ring and a limb is an open pipe: the
            // far wall shows through and the silhouette reads as a hoop rather than a solid.
            val startCenter = vertex(x0, y0, z0)
            val endCenter = vertex(x1, y1, z1)
            for (index in 0 until sides) {
                val next = (index + 1) % sides
                tri(startCenter, startRing[index], startRing[next], color)
                tri(endCenter, endRing[next], endRing[index], color)
            }
        }

        fun pyramid(cx: Float, baseY: Float, cz: Float, half: Float, height: Float, color: Int) {
            val a = vertex(cx - half, baseY, cz - half)
            val b = vertex(cx + half, baseY, cz - half)
            val c = vertex(cx + half, baseY, cz + half)
            val d = vertex(cx - half, baseY, cz + half)
            val apex = vertex(cx, baseY + height, cz)
            quad(a, d, c, b, color)
            tri(a, b, apex, color)
            tri(b, c, apex, color)
            tri(c, d, apex, color)
            tri(d, a, apex, color)
        }

        fun bipyramid(cx: Float, baseY: Float, cz: Float, radius: Float, height: Float, sides: Int, color: Int, accent: Int) {
            val middle = baseY + height * 0.38f
            val ring = IntArray(sides) { index ->
                val angle = index.toFloat() / sides * 2f * PI.toFloat()
                vertex(cx + cos(angle) * radius, middle, cz + sin(angle) * radius)
            }
            val bottom = vertex(cx, baseY, cz)
            val top = vertex(cx, baseY + height, cz)
            for (index in 0 until sides) {
                val next = (index + 1) % sides
                tri(bottom, ring[next], ring[index], color)
                tri(ring[index], ring[next], top, accent)
            }
        }

        fun blob(
            cx: Float,
            cy: Float,
            cz: Float,
            rx: Float,
            ry: Float,
            rz: Float,
            rings: Int,
            segments: Int,
            roughness: Float,
            random: java.util.Random,
            color: Int,
        ) {
            val grid = Array(rings + 1) { IntArray(segments) }
            for (ring in 0..rings) {
                val latitude = PI.toFloat() * ring / rings
                val vertical = sin(latitude)
                for (segment in 0 until segments) {
                    val angle = segment.toFloat() / segments * 2f * PI.toFloat()
                    val noise = 1f + (random.nextFloat() * 2f - 1f) * roughness
                    val x = cx + cos(angle) * rx * vertical * noise
                    val y = cy + cos(latitude) * ry * noise
                    val z = cz + sin(angle) * rz * vertical * noise
                    grid[ring][segment] = vertex(x, y, z)
                }
            }
            for (ring in 0 until rings) {
                for (segment in 0 until segments) {
                    val next = (segment + 1) % segments
                    quad(grid[ring][segment], grid[ring][next], grid[ring + 1][next], grid[ring + 1][segment], color)
                }
            }
        }

        fun build(name: String, color: Int, category: String, animation: MeshAnimation): GameMesh {
            if (vertices.isEmpty() || triangles.isEmpty()) {
                // Never return an empty mesh: a renderer with nothing to draw is indistinguishable
                // from a failure, and a cube is an honest stand-in for a model that predicted
                // nothing usable.
                val fallback = Builder()
                fallback.box(0f, 0.5f, 0f, 1f, 1f, 1f, color)
                return fallback.build(name, color, category, animation)
            }
            return GameMesh(
                name = name,
                vertices = FloatArray(vertices.size) { vertices[it] },
                triangles = IntArray(triangles.size) { triangles[it] },
                color = color,
                faceColors = IntArray(colors.size) { colors[it] },
                category = category,
                animation = animation,
            )
        }
    }
}

// ── Detail ──────────────────────────────────────────────────────────────────────────────────────

/**
 * Raises a generated asset to a delivery polygon budget.
 *
 * The shape predictor deliberately works in a sixteen-number descriptor, which is honest but also
 * low-frequency: the mesh a category builder makes from it is a silhouette, not a finished game
 * asset. This turns that silhouette into a high-polygon mesh by repeatedly splitting every
 * triangle, then displaces the new midpoints by a tiny deterministic amount so the extra faces
 * carry surface detail instead of being coplanar padding. Edge midpoints are shared, so the result
 * stays a closed surface rather than a pile of loose triangles.
 *
 * The operation is deterministic for a given [seed], which means a model's output is reproducible
 * and a saved game can be rendered again byte for byte.
 */
object MeshDetail {

    const val DEFAULT_MIN_TRIANGLES = 80_000
    const val TEXTURE_SIZE = 1024

    fun refine(
        mesh: GameMesh,
        minTriangles: Int = DEFAULT_MIN_TRIANGLES,
        seed: Long = 0L,
    ): GameMesh {
        if (mesh.triangleCount >= minTriangles) return mesh
        var level = 0
        var projected = mesh.triangleCount
        while (projected < minTriangles && level < 8) {
            projected *= 4
            level++
        }
        if (level <= 0) return mesh
        val bounds = mesh.bounds()
        val scale = bounds.largest.coerceAtLeast(0.001f)
        val displacement = scale * 0.006f
        var vertices = mesh.vertices
        var triangles = mesh.triangles
        var colors = mesh.faceColors
        repeat(level) { depth ->
            val nextVertices = ArrayList<Float>(vertices.size + triangles.size)
            vertices.forEach { nextVertices += it }
            val nextTriangles = ArrayList<Int>(triangles.size * 4)
            val nextColors = ArrayList<Int>(triangles.size * 4)
            val edges = HashMap<Long, Int>(triangles.size * 3)

            fun midpoint(a: Int, b: Int): Int {
                val low = min(a, b).toLong()
                val high = max(a, b).toLong()
                val key = (low shl 32) or high
                edges[key]?.let { return it }
                val ax = vertices[a * 3]; val ay = vertices[a * 3 + 1]; val az = vertices[a * 3 + 2]
                val bx = vertices[b * 3]; val by = vertices[b * 3 + 1]; val bz = vertices[b * 3 + 2]
                var mx = (ax + bx) * 0.5f
                var my = (ay + by) * 0.5f
                var mz = (az + bz) * 0.5f
                // A stable value per edge, not per traversal: both triangles that share this edge
                // must move the midpoint to the same place or the surface opens up.
                var hash = (low * 0x9E3779B97F4A7C15uL.toLong()) xor
                    (high * 0xC2B2AE3D27D4EB4FuL.toLong()) xor (seed * 0x165667B19E3779F9uL.toLong())
                hash = hash xor (hash ushr 29)
                val step = (((hash and 0xFFFF).toFloat() / 0xFFFF.toFloat()) * 2f - 1f) *
                    displacement / (1f + depth)
                val length = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(0.0001f)
                mx += mx / length * step
                my += my / length * step
                mz += mz / length * step
                val index = nextVertices.size / 3
                nextVertices += mx; nextVertices += my; nextVertices += mz
                edges[key] = index
                return index
            }

            var face = 0
            while (face + 2 < triangles.size) {
                val a = triangles[face]
                val b = triangles[face + 1]
                val c = triangles[face + 2]
                val color = colors.getOrElse(face / 3) { mesh.color }
                val ab = midpoint(a, b)
                val bc = midpoint(b, c)
                val ca = midpoint(c, a)
                nextTriangles += a; nextTriangles += ab; nextTriangles += ca; nextColors += color
                nextTriangles += ab; nextTriangles += b; nextTriangles += bc; nextColors += color
                nextTriangles += ca; nextTriangles += bc; nextTriangles += c; nextColors += color
                nextTriangles += ab; nextTriangles += bc; nextTriangles += ca; nextColors += color
                face += 3
            }
            vertices = FloatArray(nextVertices.size) { nextVertices[it] }
            triangles = IntArray(nextTriangles.size) { nextTriangles[it] }
            colors = IntArray(nextColors.size) { nextColors[it] }
        }
        // A SMOOTHED SURFACE IS WHAT MAKES THE EXTRA TRIANGLES READ AS DETAIL. Pure subdivision
        // keeps the facets of the low-poly silhouette and just puts more coplanar triangles inside
        // them, which is why a subdivided boulder still looked like a cut gem. Organic kinds get
        // two light Laplacian passes; hard-surface kinds keep their edges.
        val organic = mesh.category in setOf("rock", "tree", "creature", "terrain")
        if (organic) {
            val passes = when (mesh.category) {
                "rock" -> 5
                "creature" -> 4
                "tree" -> 3
                else -> 2
            }
            // Taubin smoothing: a positive pass followed by a smaller negative one. Plain
            // Laplacian passes round the shape but also shrink it, which made a boulder pull away
            // from its own base after five passes.
            repeat(passes) {
                vertices = smooth(vertices, triangles, 0.55f)
                vertices = smooth(vertices, triangles, -0.58f)
            }
        }
        // Then a deterministic multi-octave displacement: fine surface relief at a scale the
        // subdivision can actually show, instead of the old 0.6% wobble that vanished at arm's
        // length. Buildings and props get almost none so a wall stays a wall.
        val relief = when (mesh.category) {
            "terrain" -> 0.022f
            "rock" -> 0.018f
            "creature" -> 0.014f
            "tree" -> 0.011f
            "crystal" -> 0.008f
            else -> 0.003f
        } * scale
        if (relief > 0.0001f) {
            vertices = relieve(
                vertices,
                bounds.centerX,
                bounds.centerY,
                bounds.centerZ,
                scale,
                relief,
                seed,
            )
        }
        return GameMesh(
            name = mesh.name,
            vertices = vertices,
            triangles = triangles,
            color = mesh.color,
            faceColors = colors,
            category = mesh.category,
            animation = mesh.animation,
        )
    }

    /** One Laplacian pass over the shared vertices: each point moves toward its edge neighbours. */
    private fun smooth(vertices: FloatArray, triangles: IntArray, factor: Float): FloatArray {
        val count = vertices.size / 3
        if (count == 0) return vertices
        val sums = FloatArray(vertices.size)
        val neighbours = IntArray(count)
        var face = 0
        while (face + 2 < triangles.size) {
            val a = triangles[face]
            val b = triangles[face + 1]
            val c = triangles[face + 2]
            // Each vertex receives both opposite corners of each triangle it belongs to. Edges are
            // shared, so this counts a neighbour twice, which is harmless: it is an average.
            for (vertex in intArrayOf(a, b, c)) {
                val otherA = if (vertex == a) b else a
                val otherB = if (vertex == c) b else c
                sums[vertex * 3] += vertices[otherA * 3] + vertices[otherB * 3]
                sums[vertex * 3 + 1] += vertices[otherA * 3 + 1] + vertices[otherB * 3 + 1]
                sums[vertex * 3 + 2] += vertices[otherA * 3 + 2] + vertices[otherB * 3 + 2]
                neighbours[vertex] += 2
            }
            face += 3
        }
        val out = FloatArray(vertices.size)
        for (vertex in 0 until count) {
            val n = neighbours[vertex]
            if (n == 0) {
                out[vertex * 3] = vertices[vertex * 3]
                out[vertex * 3 + 1] = vertices[vertex * 3 + 1]
                out[vertex * 3 + 2] = vertices[vertex * 3 + 2]
                continue
            }
            out[vertex * 3] = vertices[vertex * 3] * (1f - factor) + (sums[vertex * 3] / n) * factor
            out[vertex * 3 + 1] =
                vertices[vertex * 3 + 1] * (1f - factor) + (sums[vertex * 3 + 1] / n) * factor
            out[vertex * 3 + 2] =
                vertices[vertex * 3 + 2] * (1f - factor) + (sums[vertex * 3 + 2] / n) * factor
        }
        return out
    }

    /** Pushes every vertex along its radial direction by three octaves of stable value noise. */
    private fun relieve(
        vertices: FloatArray,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        scale: Float,
        amplitude: Float,
        seed: Long,
    ): FloatArray {
        val out = vertices.copyOf()
        val frequency = 11f / scale
        var index = 0
        while (index + 2 < vertices.size) {
            var dx = vertices[index] - centerX
            var dy = vertices[index + 1] - centerY
            var dz = vertices[index + 2] - centerZ
            val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.0001f)
            dx /= length; dy /= length; dz /= length
            val x = vertices[index] * frequency
            val y = vertices[index + 1] * frequency
            val z = vertices[index + 2] * frequency
            val noise = valueNoise(x, y, z, seed) * 0.62f +
                valueNoise(x * 2.17f, y * 2.17f, z * 2.17f, seed + 17L) * 0.26f +
                valueNoise(x * 4.51f, y * 4.51f, z * 4.51f, seed + 41L) * 0.12f
            val offset = (noise * 2f - 1f) * amplitude
            out[index] = vertices[index] + dx * offset
            out[index + 1] = vertices[index + 1] + dy * offset
            out[index + 2] = vertices[index + 2] + dz * offset
            index += 3
        }
        return out
    }

    private fun valueNoise(x: Float, y: Float, z: Float, seed: Long): Float {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val zi = kotlin.math.floor(z).toInt()
        val xf = x - xi
        val yf = y - yi
        val zf = z - zi
        fun hash(px: Int, py: Int, pz: Int): Float {
            var h = px.toLong() * 374761393L + py * 668265263L + pz * 2147483647L + seed
            h = (h xor (h shr 13)) * 1274126177L
            h = h xor (h shr 16)
            return ((h and 0xFFFF).toFloat() / 0xFFFF.toFloat())
        }
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        val sx = xf * xf * (3f - 2f * xf)
        val sy = yf * yf * (3f - 2f * yf)
        val sz = zf * zf * (3f - 2f * zf)
        val x00 = lerp(hash(xi, yi, zi), hash(xi + 1, yi, zi), sx)
        val x10 = lerp(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), sx)
        val x01 = lerp(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), sx)
        val x11 = lerp(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), sx)
        return lerp(lerp(x00, x10, sy), lerp(x01, x11, sy), sz)
    }
}

// ── Importing meshes ────────────────────────────────────────────────────────────────────────────

/** Reads game meshes that were not made by ModelCraft. */
object MeshIO {

    val extensions: Set<String> = setOf("obj", "ply", "glb")

    /**
     * Writes a mesh as an OBJ a modelling tool or a game engine can open.
     *
     * A Prism game's materials are face colours produced by baking an albedo onto the geometry, and
     * OBJ has no face-colour slot. A sidecar `<file>.colors` keeps them: without it, a saved scene
     * reopened with every object painted the same default grey, which is exactly what made a saved
     * textured scene lose its textures on the next open. The OBJ itself stays valid for Blender and
     * every other tool; the sidecar is ignored by anything that does not know Prism.
     */
    fun writeObj(mesh: GameMesh, file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("# Prism ${mesh.category} \u00b7 ${mesh.triangleCount} triangles\n")
            out.write("o ${mesh.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}\n")
            var index = 0
            while (index + 2 < mesh.vertices.size) {
                out.write("v ${fmt(mesh.vertices[index])} ${fmt(mesh.vertices[index + 1])} ${fmt(mesh.vertices[index + 2])}\n")
                index += 3
            }
            var face = 0
            while (face + 2 < mesh.triangles.size) {
                out.write("f ${mesh.triangles[face] + 1} ${mesh.triangles[face + 1] + 1} ${mesh.triangles[face + 2] + 1}\n")
                face += 3
            }
        }
        if (mesh.faceColors.size == mesh.triangleCount) {
            File(file.parentFile, file.name + ".colors").bufferedWriter().use { out ->
                out.write("# ARGB, one per face, in triangle order\n")
                mesh.faceColors.forEach { color -> out.write("%08x\n".format(color)) }
            }
        }
    }

    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.6f", value)

    fun load(file: File, displayName: String = file.nameWithoutExtension): GameMesh? {
        if (!file.isFile) return null
        val mesh = when (file.extension.lowercase(Locale.ROOT)) {
            "obj" -> readObj(file)
            "ply" -> readPly(file)
            "glb" -> readGlb(file)
            else -> null
        } ?: return null
        val category = MeshGenerator.category(displayName, DoubleArray(MeshGenerator.SHAPE_DIM), 0L)
        val savedColors = File(file.parentFile, file.name + ".colors").takeIf { it.isFile }
            ?.let { runCatching { it.readLines().mapNotNull { line ->
                line.trim().takeIf { text -> text.isNotEmpty() && !text.startsWith("#") }
                    ?.toLongOrNull(16)?.toInt()
            } }.getOrNull() }
        val faceCount = mesh.triangles.size / 3
        return GameMesh(
            name = displayName,
            vertices = mesh.vertices,
            triangles = mesh.triangles,
            color = mesh.color,
            faceColors = if (savedColors != null && savedColors.size == faceCount) {
                IntArray(faceCount) { savedColors[it] }
            } else {
                mesh.faceColors
            },
            category = category,
            animation = if (category == "creature") MeshAnimation.WALK else MeshAnimation.IDLE,
        )
    }

    private fun readObj(file: File): GameMesh? {
        val vertices = ArrayList<Float>()
        val triangles = ArrayList<Int>()
        val colors = ArrayList<Int>()
        val defaultColor = 0xFF9AA6B2.toInt()
        var usable = 0
        for (raw in file.readLines()) {
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        parts[1].toFloatOrNull()?.let { vertices += it }
                        parts[2].toFloatOrNull()?.let { vertices += it }
                        parts[3].toFloatOrNull()?.let { vertices += it }
                    }
                }
                line.startsWith("f ") -> {
                    val face = line.split(Regex("\\s+")).drop(1).mapNotNull { token ->
                        val index = token.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                        if (index < 0) vertices.size / 3 + index else index - 1
                    }
                    if (face.size >= 3) {
                        for (index in 1 until face.size - 1) {
                            triangles += face[0]
                            triangles += face[index]
                            triangles += face[index + 1]
                            colors += defaultColor
                            usable++
                        }
                    }
                }
            }
        }
        if (vertices.isEmpty() || usable == 0) return null
        // An OBJ's indices are validated against its own vertex list; a malformed one should be
        // rejected rather than making a renderer read outside the array.
        if (triangles.any { it < 0 || it >= vertices.size / 3 }) return null
        return GameMesh(
            name = file.nameWithoutExtension,
            vertices = FloatArray(vertices.size) { vertices[it] },
            triangles = IntArray(triangles.size) { triangles[it] },
            color = defaultColor,
            faceColors = IntArray(colors.size) { colors[it] },
        )
    }

    private fun readPly(file: File): GameMesh? {
        val lines = file.readLines()
        var start = -1
        var vertexCount = 0
        var faceCount = 0
        var ascii = false
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                line.startsWith("format ") -> ascii = line.contains("ascii")
                line.startsWith("element vertex ") -> vertexCount = line.split(" ").lastOrNull()?.toIntOrNull() ?: 0
                line.startsWith("element face ") -> faceCount = line.split(" ").lastOrNull()?.toIntOrNull() ?: 0
                line == "end_header" -> { start = index + 1; break }
            }
        }
        if (!ascii || start < 0 || vertexCount <= 0) return null
        val vertices = ArrayList<Float>()
        for (index in start until min(lines.size, start + vertexCount)) {
            val parts = lines[index].trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            parts[0].toFloatOrNull()?.let { vertices += it }
            parts[1].toFloatOrNull()?.let { vertices += it }
            parts[2].toFloatOrNull()?.let { vertices += it }
        }
        val triangles = ArrayList<Int>()
        val colors = ArrayList<Int>()
        val defaultColor = 0xFF9AA6B2.toInt()
        for (index in start + vertexCount until min(lines.size, start + vertexCount + faceCount)) {
            val parts = lines[index].trim().split(Regex("\\s+"))
            val count = parts.firstOrNull()?.toIntOrNull() ?: continue
            if (count < 3 || parts.size < count + 1) continue
            val face = (0 until count).mapNotNull { parts[it + 1].toIntOrNull() }
            if (face.size < 3) continue
            for (corner in 1 until face.size - 1) {
                triangles += face[0]; triangles += face[corner]; triangles += face[corner + 1]
                colors += defaultColor
            }
        }
        if (vertices.isEmpty() || triangles.isEmpty()) return null
        if (triangles.any { it < 0 || it >= vertices.size / 3 }) return null
        return GameMesh(
            name = file.nameWithoutExtension,
            vertices = FloatArray(vertices.size) { vertices[it] },
            triangles = IntArray(triangles.size) { triangles[it] },
            color = defaultColor,
            faceColors = IntArray(colors.size) { colors[it] },
        )
    }

    /** GLB, the binary glTF container: a JSON chunk and a buffer chunk, no external files. */
    private fun readGlb(file: File): GameMesh? {
        val bytes = file.readBytes()
        if (bytes.size < 20) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt() != 0x46546C67) return null
        buffer.getInt() // version
        buffer.getInt() // total length
        var json: JSONObject? = null
        var binary: ByteArray? = null
        while (buffer.remaining() >= 8) {
            val length = buffer.getInt()
            val type = buffer.getInt()
            if (length < 0 || length > buffer.remaining()) return null
            val chunk = ByteArray(length)
            buffer.get(chunk)
            if (type == 0x4E4F534A) {
                json = runCatching { JSONObject(String(chunk, Charsets.UTF_8).trim()) }.getOrNull()
            } else if (type == 0x004E4942) {
                binary = chunk
            }
        }
        val root = json ?: return null
        val bin = binary ?: return null
        val bufferViews = root.optJSONArray("bufferViews") ?: return null
        val accessors = root.optJSONArray("accessors") ?: return null
        val meshes = root.optJSONArray("meshes") ?: return null
        val vertices = ArrayList<Float>()
        val triangles = ArrayList<Int>()
        val colors = ArrayList<Int>()
        val defaultColor = 0xFF9AA6B2.toInt()
        val view = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)

        fun accessorValues(index: Int): FloatArray? {
            val accessor = accessors.optJSONObject(index) ?: return null
            if (accessor.optString("type", "") != "VEC3") return null
            if (accessor.optInt("componentType", 0) != 5126) return null
            val count = accessor.optInt("count", 0)
            if (count <= 0) return null
            val viewIndex = accessor.optInt("bufferView", -1)
            val bufferView = bufferViews.optJSONObject(viewIndex) ?: return null
            val base = bufferView.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val stride = bufferView.optInt("byteStride", 12).coerceAtLeast(12)
            val out = FloatArray(count * 3)
            var offset = base
            for (index2 in 0 until count) {
                if (offset + 12 > bin.size) return null
                out[index2 * 3] = view.getFloat(offset)
                out[index2 * 3 + 1] = view.getFloat(offset + 4)
                out[index2 * 3 + 2] = view.getFloat(offset + 8)
                offset += stride
            }
            return out
        }

        fun indices(index: Int): IntArray? {
            val accessor = accessors.optJSONObject(index) ?: return null
            val count = accessor.optInt("count", 0)
            val viewIndex = accessor.optInt("bufferView", -1)
            val bufferView = bufferViews.optJSONObject(viewIndex) ?: return null
            val base = bufferView.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val component = accessor.optInt("componentType", 5123)
            val width = when (component) {
                5121 -> 1
                5123 -> 2
                5125 -> 4
                else -> return null
            }
            if (base + count * width > bin.size) return null
            val out = IntArray(count)
            for (index2 in 0 until count) {
                val position = base + index2 * width
                out[index2] = when (width) {
                    1 -> bin[position].toInt() and 0xFF
                    2 -> view.getShort(position).toInt() and 0xFFFF
                    else -> view.getInt(position)
                }
            }
            return out
        }

        for (meshIndex in 0 until meshes.length()) {
            val mesh = meshes.optJSONObject(meshIndex) ?: continue
            val primitives = mesh.optJSONArray("primitives") ?: continue
            for (primitiveIndex in 0 until primitives.length()) {
                val primitive = primitives.optJSONObject(primitiveIndex) ?: continue
                val attributes = primitive.optJSONObject("attributes") ?: continue
                val positions = accessorValues(attributes.optInt("POSITION", -1)) ?: continue
                val offset = vertices.size / 3
                positions.forEach { vertices += it }
                val indexAccessor = primitive.optInt("indices", -1)
                val source = if (indexAccessor >= 0) indices(indexAccessor) else null
                if (source != null) {
                    var index2 = 0
                    while (index2 + 2 < source.size) {
                        triangles += offset + source[index2]
                        triangles += offset + source[index2 + 1]
                        triangles += offset + source[index2 + 2]
                        colors += defaultColor
                        index2 += 3
                    }
                } else {
                    var index2 = 0
                    while (index2 + 2 < positions.size / 3) {
                        triangles += offset + index2
                        triangles += offset + index2 + 1
                        triangles += offset + index2 + 2
                        colors += defaultColor
                        index2 += 3
                    }
                }
            }
        }
        if (vertices.isEmpty() || triangles.isEmpty()) return null
        if (triangles.any { it < 0 || it >= vertices.size / 3 }) return null
        return GameMesh(
            name = file.nameWithoutExtension,
            vertices = FloatArray(vertices.size) { vertices[it] },
            triangles = IntArray(triangles.size) { triangles[it] },
            color = defaultColor,
            faceColors = IntArray(colors.size) { colors[it] },
        )
    }
}
