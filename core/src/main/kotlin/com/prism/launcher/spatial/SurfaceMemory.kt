package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The room's surfaces as remembered over a whole session, not as found in the last second.
 *
 * Each rebuild of [RoomLayout] sees only what the model holds now, and the model's view of a wall
 * changes as the user walks: a wall seen from across the room is found, then half-occluded by the
 * user, then out of view. Taking every rebuild at its word made walls flicker in and out and nudge
 * back and forth -- and pages, snapped to them, went with them. Quest keeps its room layout fixed
 * once it has it; this does the same within a session:
 *
 *  - a surface seen again is REFINED (its position eased toward the new estimate, its extent grown
 *    to cover both), never replaced;
 *  - a surface not seen this time is KEPT;
 *  - a wall becomes placeable only once it has been seen twice, or is large, so one noisy frame
 *    cannot add a phantom wall.
 */
class SurfaceMemory {

    private class Wall(var normal: Vec3, var offset: Float, var from: Float, var to: Float, var hits: Int) {
        val u: Vec3 get() = (Vec3.UP cross normal).normalized()
        val area: Float get() = (to - from) * 2.4f
    }

    private class Level(var height: Float, var minX: Float, var maxX: Float, var minZ: Float, var maxZ: Float, var inferred: Boolean)

    private var floor: Level? = null
    private var ceiling: Level? = null
    private val walls = ArrayList<Wall>()

    @Synchronized
    fun clear() {
        floor = null
        ceiling = null
        walls.clear()
    }

    private fun levelOf(s: RoomLayout.Surface) = Level(
        s.center.y, s.center.x - s.halfU, s.center.x + s.halfU, s.center.z - s.halfV, s.center.z + s.halfV, s.inferred,
    )

    private fun mergeLevel(old: Level?, new: RoomLayout.Surface?): Level? {
        if (new == null) return old
        val fresh = levelOf(new)
        if (old == null) return fresh
        // A measured level beats an estimated one outright.
        if (old.inferred && !fresh.inferred) return fresh
        if (!old.inferred && fresh.inferred) return old.also { grow(it, fresh) }
        if (abs(old.height - fresh.height) > 0.35f) return if (fresh.inferred) old else fresh
        old.height = old.height * 0.75f + fresh.height * 0.25f
        grow(old, fresh)
        return old
    }

    private fun grow(level: Level, other: Level) {
        level.minX = min(level.minX, other.minX); level.maxX = max(level.maxX, other.maxX)
        level.minZ = min(level.minZ, other.minZ); level.maxZ = max(level.maxZ, other.maxZ)
    }

    /** Folds a freshly built layout into what is remembered, and returns the remembered room. */
    @Synchronized
    fun merge(layout: RoomLayout): RoomLayout {
        floor = mergeLevel(floor, layout.floor)
        ceiling = mergeLevel(ceiling, layout.ceiling)

        layout.walls.forEach { w ->
            val n = w.normal
            val offset = n dot w.center
            val along = w.center dot w.u
            val from = along - w.halfU
            val to = along + w.halfU
            val match = walls.firstOrNull { old ->
                (old.normal dot n) > SAME_DIRECTION && abs(old.offset - offset) < 0.3f &&
                    from < old.to + 0.6f && to > old.from - 0.6f
            }
            if (match == null) {
                walls += Wall(n, offset, from, to, 1)
            } else {
                match.normal = (match.normal * 0.75f + n * 0.25f).normalized()
                match.offset = match.offset * 0.75f + offset * 0.25f
                // Re-express the new extent along the (slightly turned) wall before growing.
                val along2 = w.center dot match.u
                match.from = min(match.from, along2 - w.halfU)
                match.to = max(match.to, along2 + w.halfU)
                match.hits++
            }
        }
        // Two remembered walls that have converged on the same wall become one.
        var i = 0
        while (i < walls.size) {
            var j = i + 1
            while (j < walls.size) {
                val a = walls[i]
                val b = walls[j]
                if ((a.normal dot b.normal) > SAME_DIRECTION && abs(a.offset - b.offset) < 0.15f && b.from < a.to + 0.3f && b.to > a.from - 0.3f) {
                    a.from = min(a.from, b.from); a.to = max(a.to, b.to); a.hits += b.hits
                    walls.removeAt(j)
                } else j++
            }
            i++
        }
        return snapshot(layout.tables)
    }

    private fun snapshot(tables: List<RoomLayout.Surface>): RoomLayout {
        val confirmed = walls.filter { it.hits >= 2 || it.area > 1.5f }
        var floorY = floor?.height
        val wallBottom = floorY ?: 0f
        var ceilingY = ceiling?.height
        if (ceilingY == null && floorY != null) ceilingY = floorY + 2.4f
        if (floorY == null && ceilingY != null) floorY = ceilingY - 2.4f
        val bottom = floorY ?: wallBottom
        val top = ceilingY ?: (bottom + 2.4f)
        val wallSurfaces = confirmed.map { w ->
            val u = w.u
            val mid = (w.from + w.to) / 2
            val center = w.normal * w.offset + u * mid
            RoomLayout.Surface(
                RoomLayout.Kind.WALL, Vec3(center.x, (bottom + top) / 2, center.z), w.normal, u, Vec3.UP,
                (w.to - w.from) / 2, (top - bottom) / 2,
            )
        }
        fun level(kind: RoomLayout.Kind, l: Level?, normal: Vec3): RoomLayout.Surface? {
            l ?: return null
            var minX = l.minX; var maxX = l.maxX; var minZ = l.minZ; var maxZ = l.maxZ
            // The floor and ceiling reach every remembered wall.
            wallSurfaces.forEach { w ->
                listOf(w.center + w.u * w.halfU, w.center - w.u * w.halfU).forEach { p ->
                    minX = min(minX, p.x); maxX = max(maxX, p.x); minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
                }
            }
            return RoomLayout.Surface(
                kind, Vec3((minX + maxX) / 2, l.height, (minZ + maxZ) / 2), normal,
                Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), max(0.25f, (maxX - minX) / 2), max(0.25f, (maxZ - minZ) / 2), l.inferred,
            )
        }
        return RoomLayout(
            level(RoomLayout.Kind.FLOOR, floor, Vec3.UP),
            level(RoomLayout.Kind.CEILING, ceiling, -Vec3.UP),
            wallSurfaces,
            tables,
        )
    }

    private companion object {
        val SAME_DIRECTION = cos(Math.toRadians(12.0)).toFloat()
    }
}
