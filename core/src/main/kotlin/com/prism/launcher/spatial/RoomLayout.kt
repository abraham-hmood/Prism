package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The room: its floor, its ceiling and its walls, found the way Quest 3 finds them.
 *
 * Quest 3's scene model is a room layout -- one floor, one ceiling, wall faces between them -- plus
 * labelled objects (tables, couches), all derived from the fused depth mesh. The labelling is
 * geometric: a large horizontal surface facing up at the lowest height is the floor, one facing
 * down at the top is the ceiling, large vertical surfaces are wall faces, and they span the room
 * from floor to ceiling. Horizontal surfaces in between are furniture (TABLE), which is labelled so
 * it is NOT mistaken for floor and never offered as a place to put a page.
 *
 * When part of the room has not been seen yet, Quest still reports a complete layout -- walls run
 * from floor to ceiling even where the camera never looked. This does the same: a missing ceiling
 * is placed above the tallest wall (at least 2.4 m above the floor), a missing floor below the
 * lowest wall, and those surfaces are marked [Surface.inferred].
 */
class RoomLayout(
    val floor: Surface?,
    val ceiling: Surface?,
    val walls: List<Surface>,
    val tables: List<Surface>,
) {
    enum class Kind(val label: String) { FLOOR("floor"), CEILING("ceiling"), WALL("wall"), TABLE("table") }

    /**
     * One labelled, bounded flat surface. [u] and [v] span it ([halfU], [halfV] its half-extents
     * from [center]), and [normal] faces into the room.
     */
    class Surface(
        val kind: Kind,
        val center: Vec3,
        val normal: Vec3,
        val u: Vec3,
        val v: Vec3,
        val halfU: Float,
        val halfV: Float,
        val inferred: Boolean = false,
    ) {
        val area: Float get() = 4 * halfU * halfV

        /** Where a ray meets this surface, within its bounds plus [margin], or null. */
        fun intersect(origin: Vec3, direction: Vec3, margin: Float = 0f): Float? {
            val denom = normal dot direction
            if (denom > -1e-4f) return null // parallel, or seen from behind
            val t = (normal dot (center - origin)) / denom
            if (t <= 0f) return null
            val p = origin + direction * t
            val local = p - center
            if (abs(local dot u) > halfU + margin || abs(local dot v) > halfV + margin) return null
            return t
        }
    }

    /** Where a ray from the viewer lands on the room. */
    class Hit(val surface: Surface, val point: Vec3, val distance: Float)

    /** Floor, ceiling and walls: the surfaces a page can be put on. */
    val placeable: List<Surface> get() = listOfNotNull(floor, ceiling) + walls

    /** The nearest placeable surface along a ray, or null when it hits none. */
    fun raycast(origin: Vec3, direction: Vec3, margin: Float = 0.25f): Hit? {
        val dir = direction.normalized()
        // Furniture is in front of the walls and floor: a ray that meets a table first is blocked.
        val blockers = tables.mapNotNull { it.intersect(origin, dir) }
        val nearestBlock = blockers.minOrNull() ?: Float.MAX_VALUE
        return placeable.mapNotNull { surface ->
            surface.intersect(origin, dir, margin)?.let { t -> Hit(surface, origin + dir * t, t) }
        }.filter { it.distance < nearestBlock + 0.05f }.minByOrNull { it.distance }
    }

    val isEmpty: Boolean get() = floor == null && ceiling == null && walls.isEmpty()

    fun describe(): String = buildString {
        append(if (floor == null) "no floor" else "floor")
        append(", ").append(walls.size).append(if (walls.size == 1) " wall" else " walls")
        append(", ").append(if (ceiling == null) "no ceiling" else if (ceiling.inferred) "ceiling (estimated)" else "ceiling")
    }

    companion object {
        private const val HORIZONTAL = 0.9f   // |n·up| above this: horizontal
        private const val VERTICAL = 0.3f     // |n·up| below this: vertical
        private const val MIN_FLOOR_AREA = 0.4f
        private const val MIN_WALL_AREA = 0.35f
        private const val MIN_WALL_HEIGHT = 0.35f
        private const val MIN_CEILING_CLEARANCE = 1.6f
        private const val DEFAULT_CEILING = 2.4f
        private const val MANHATTAN_SNAP = 12.0
        private const val WALL_REACH = 1.6f

        val EMPTY = RoomLayout(null, null, emptyList(), emptyList())

        private class Bounds(var minU: Float = Float.MAX_VALUE, var maxU: Float = -Float.MAX_VALUE, var minV: Float = Float.MAX_VALUE, var maxV: Float = -Float.MAX_VALUE) {
            fun add(a: Float, b: Float) {
                minU = min(minU, a); maxU = max(maxU, a); minV = min(minV, b); maxV = max(maxV, b)
            }
            val valid get() = minU <= maxU && minV <= maxV
        }

        private fun horizontal(kind: Kind, height: Float, normal: Vec3, bounds: Bounds, inferred: Boolean): Surface {
            val u = Vec3(1f, 0f, 0f)
            val v = Vec3(0f, 0f, 1f)
            val cu = (bounds.minU + bounds.maxU) / 2
            val cv = (bounds.minV + bounds.maxV) / 2
            return Surface(
                kind, Vec3(cu, height, cv), normal, u, v,
                max(0.25f, (bounds.maxU - bounds.minU) / 2), max(0.25f, (bounds.maxV - bounds.minV) / 2), inferred,
            )
        }

        /** Labels [planes] into a room layout. [up] is the direction against gravity. */
        fun build(planes: List<Plane>, up: Vec3 = Vec3.UP): RoomLayout {
            val upward = planes.filter { (it.normal dot up) > HORIZONTAL }
            val downward = planes.filter { (it.normal dot up) < -HORIZONTAL }
            val vertical = planes.filter { abs(it.normal dot up) < VERTICAL }

            fun height(p: Plane) = p.centroid dot up
            fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

            // MANHATTAN: rooms are boxes far more often than not, and Quest's room layout assumes
            // it too. Walls within a few degrees of the room's main directions are squared up to
            // them, so a wall measured 3 degrees off does not leave a page tilted away from it.
            // The main direction is the area-weighted mean of the wall headings, modulo 90 degrees.
            var s4 = 0.0
            var c4 = 0.0
            vertical.forEach { p ->
                val h = headingOf((p.normal - up * (p.normal dot up)).normalized()).toDouble()
                s4 += p.area * Math.sin(4 * h); c4 += p.area * Math.cos(4 * h)
            }
            val axis = (Math.atan2(s4, c4) / 4).toFloat()
            fun squared(n: Vec3): Vec3 {
                if (vertical.size < 2) return n
                val h = headingOf(n)
                val quarter = (Math.PI / 2).toFloat()
                val k = Math.round(angleDifference(h, axis) / quarter).toFloat()
                val snapped = axis + k * quarter
                return if (kotlin.math.abs(angleDifference(h, snapped)) < Math.toRadians(MANHATTAN_SNAP).toFloat()) directionOf(snapped) else n
            }

            // FLOOR: the lowest large upward surface; anything level with it is more of it.
            val floorSeed = upward.filter { it.area >= MIN_FLOOR_AREA }.minByOrNull(::height)
            val floorPlanes = floorSeed?.let { seed -> upward.filter { abs(height(it) - height(seed)) < 0.12f } }.orEmpty()

            // WALLS: large vertical surfaces. Their vertical span decides the room's height when
            // the floor or ceiling has not been seen.
            class WallPiece(val plane: Plane, val normal: Vec3, val u: Vec3, val b: Bounds)
            val wallPieces = vertical.mapNotNull { plane ->
                val n = squared((plane.normal - up * (plane.normal dot up)).normalized())
                val u = (up cross n).normalized()
                // The wall's offset is the median of its cells along the (squared) normal.
                val offset = median(plane.points.map { n dot it })
                val anchor = plane.centroid - n * ((n dot plane.centroid) - offset)
                val b = Bounds()
                plane.points.forEach { p -> b.add((p - anchor) dot u, p dot up) }
                val tall = b.maxV - b.minV
                if (plane.area < MIN_WALL_AREA || tall < MIN_WALL_HEIGHT) null
                else WallPiece(Plane(n, anchor, plane.points, plane.area), n, u, b)
            }

            var floorY = floorPlanes.takeIf { it.isNotEmpty() }?.flatMap { pl -> pl.points.map { it dot up } }?.let(::median)

            // WALL FACES ARE THE ROOM'S BOUNDARY, as in Quest's layout. A vertical face with another
            // facing the same way behind it (a cabinet front, a sofa back, a desk side) is furniture
            // unless it is tall enough to be a wall in its own right.
            val boundary = wallPieces.filter { piece ->
                val reach = piece.b.maxV - (floorY ?: piece.b.minV)
                if (reach >= WALL_REACH) return@filter true
                val d = piece.normal dot piece.plane.centroid
                wallPieces.none { other ->
                    other !== piece && (other.normal dot piece.normal) > 0.95f &&
                        (other.normal dot other.plane.centroid) < d - 0.2f &&
                        (other.b.maxV - other.b.minV) > (piece.b.maxV - piece.b.minV)
                }
            }
            val floorInferred = floorY == null && boundary.isNotEmpty()
            if (floorY == null && boundary.isNotEmpty()) floorY = boundary.minOf { it.b.minV }

            val ceilingPlane = downward
                .filter { floorY == null || height(it) > floorY + MIN_CEILING_CLEARANCE }
                .maxByOrNull(::height)
            var ceilingY = ceilingPlane?.let { pl -> median(pl.points.map { it dot up }) }
            val ceilingInferred = ceilingY == null && floorY != null
            if (ceilingY == null && floorY != null) {
                val tallest = boundary.maxOfOrNull { it.b.maxV } ?: floorY
                ceilingY = max(floorY + DEFAULT_CEILING, tallest)
            }

            // Walls span floor to ceiling, as Quest's wall faces do.
            val walls = boundary.map { piece ->
                val bottom = floorY ?: piece.b.minV
                val top = ceilingY ?: piece.b.maxV
                val cu = (piece.b.minU + piece.b.maxU) / 2
                val center = piece.plane.centroid + piece.u * cu
                val base = center - up * (center dot up)
                Surface(
                    Kind.WALL, base + up * ((bottom + top) / 2), piece.normal, piece.u, up,
                    (piece.b.maxU - piece.b.minU) / 2, (top - bottom) / 2,
                )
            }

            // The floor and ceiling reach every wall, so a page can go anywhere in the room.
            fun footprint(planesHere: List<Plane>): Bounds {
                val b = Bounds()
                planesHere.forEach { pl -> pl.points.forEach { p -> b.add(p.x, p.z) } }
                walls.forEach { w ->
                    val a = w.center + w.u * w.halfU
                    val c = w.center - w.u * w.halfU
                    b.add(a.x, a.z); b.add(c.x, c.z)
                }
                return b
            }

            val floor = floorY?.let { y ->
                val b = footprint(floorPlanes)
                if (b.valid) horizontal(Kind.FLOOR, y, up, b, floorInferred) else null
            }
            val ceiling = ceilingY?.let { y ->
                val b = footprint(listOfNotNull(ceilingPlane))
                if (b.valid) horizontal(Kind.CEILING, y, -up, b, ceilingInferred) else null
            }

            // TABLES: upward surfaces clearly above the floor and below head height.
            val tables = upward.filter { plane ->
                val y = floorY ?: return@filter false
                height(plane) - y in 0.25f..1.4f
            }.map { plane ->
                val b = Bounds()
                plane.points.forEach { p -> b.add(p.x, p.z) }
                horizontal(Kind.TABLE, height(plane), up, b, false)
            }
            return RoomLayout(floor, ceiling, walls, tables)
        }
    }
}
