package com.prism.launcher.spatial

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prism's home screen in augmented reality, the part shared by the phone and the desktop: where
 * each page was put, finding that place again next time, and the geometry of touching a page.
 *
 * ## Saved where the room is, not where the camera was
 *
 * A phone's tracking starts from wherever the phone is when AR opens, so "1.2 m in front of where I
 * started" means nothing tomorrow. Quest keeps placements relative to the room it scanned; so does
 * this. A placement is stored in the ROOM FRAME of the session that made it: a frame with its
 * vertical at gravity and its -Z at a fixed bearing (north on a phone, from the compass; the
 * webcam's facing on a computer). With it is the room's signature: the floor's height and each
 * wall's bearing and distance.
 *
 * Next time, the new session's walls are matched to the saved ones by bearing, the small compass
 * error between the two is taken out by aligning the matched walls exactly, and two non-parallel
 * walls pin down where the saved frame's origin is now ([RoomAlignment]). Until enough of the room
 * has been seen to do that, placed pages stay hidden rather than appearing in the wrong place.
 */
object ArHome {

    private const val PREFS = "prism_ar_home"
    private const val KEY = "state"

    enum class Surface { FLOOR, CEILING, WALL }

    /** One page on one surface, in the saved room frame. [up] and [right] span its face. */
    data class Placement(
        val key: String,
        val surface: Surface,
        val center: Vec3,
        val normal: Vec3,
        val up: Vec3,
        val width: Float,
        val height: Float,
    ) {
        val right: Vec3 get() = (up cross normal).normalized()
    }

    /** A wall as the room signature remembers it: the bearing of its normal and its distance. */
    data class WallRef(val heading: Float, val offset: Float)

    data class Signature(val floorY: Float?, val walls: List<WallRef>)

    data class State(val setupDone: Boolean, val room: Signature?, val pages: List<Placement>)

    /** Turns the saved room frame into the current session's: rotate by [yaw], then add [shift]. */
    data class Alignment(val yaw: Float, val shift: Vec3, val matchedWalls: Int) {
        fun apply(p: Vec3) = p.rotatedY(yaw) + shift
        fun applyDirection(d: Vec3) = d.rotatedY(yaw)
        fun invert(p: Vec3) = (p - shift).rotatedY(-yaw)
        fun invertDirection(d: Vec3) = d.rotatedY(-yaw)

        companion object {
            val IDENTITY = Alignment(0f, Vec3.ZERO, 0)
        }
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    private fun vec(v: Vec3) = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())
    private fun vec(a: JSONArray?) = Vec3(
        a?.optDouble(0, 0.0)?.toFloat() ?: 0f, a?.optDouble(1, 0.0)?.toFloat() ?: 0f, a?.optDouble(2, 0.0)?.toFloat() ?: 0f,
    )

    fun load(): State = runCatching {
        val raw = PrismPlatform.host.prefs(PREFS).getString(KEY, null) ?: return State(false, null, emptyList())
        val json = JSONObject(raw)
        val room = json.optJSONObject("room")?.let { r ->
            val walls = r.optJSONArray("walls") ?: JSONArray()
            Signature(
                if (r.has("floor")) r.optDouble("floor").toFloat() else null,
                (0 until walls.length()).mapNotNull { i ->
                    walls.optJSONObject(i)?.let { WallRef(it.optDouble("h").toFloat(), it.optDouble("d").toFloat()) }
                },
            )
        }
        val pages = json.optJSONArray("pages") ?: JSONArray()
        State(
            json.optBoolean("setupDone"),
            room,
            (0 until pages.length()).mapNotNull { i ->
                val p = pages.optJSONObject(i) ?: return@mapNotNull null
                Placement(
                    p.optString("key"),
                    runCatching { Surface.valueOf(p.optString("surface")) }.getOrDefault(Surface.WALL),
                    vec(p.optJSONArray("c")), vec(p.optJSONArray("n")), vec(p.optJSONArray("up")),
                    p.optDouble("w", 0.9).toFloat(), p.optDouble("h", 1.6).toFloat(),
                )
            }.filter { it.key.isNotBlank() },
        )
    }.getOrDefault(State(false, null, emptyList()))

    fun save(state: State) {
        val json = JSONObject().put("setupDone", state.setupDone)
        state.room?.let { room ->
            val r = JSONObject()
            room.floorY?.let { r.put("floor", it.toDouble()) }
            r.put("walls", JSONArray().also { a -> room.walls.forEach { a.put(JSONObject().put("h", it.heading.toDouble()).put("d", it.offset.toDouble())) } })
            json.put("room", r)
        }
        json.put("pages", JSONArray().also { a ->
            state.pages.forEach { p ->
                a.put(
                    JSONObject().put("key", p.key).put("surface", p.surface.name)
                        .put("c", vec(p.center)).put("n", vec(p.normal)).put("up", vec(p.up))
                        .put("w", p.width.toDouble()).put("h", p.height.toDouble()),
                )
            }
        })
        PrismPlatform.host.prefs(PREFS).edit().putString(KEY, json.toString()).apply()
    }

    /** Forgets every placement and the room, so the next AR home starts the setup again. */
    fun reset() = save(State(false, null, emptyList()))

    /** The id a slot's page is saved under: its assignment and its position in the pager. */
    fun pageKey(serializedSlot: String, index: Int) = "$serializedSlot#$index"

    // ── The room's signature, and finding it again ─────────────────────────

    /** [layout] (in the platform's world frame) as a signature in the room frame. */
    fun signature(layout: RoomLayout, headingOffset: Float): Signature = Signature(
        layout.floor?.center?.y,
        layout.walls.map { wall ->
            val n = wall.normal.rotatedY(headingOffset)
            val c = wall.center.rotatedY(headingOffset)
            WallRef(headingOf(n), n dot c)
        },
    )

    object RoomAlignment {
        private val MATCH = Math.toRadians(35.0).toFloat()

        /**
         * How the saved room sits in the current one, or null when too little of the room has been
         * seen to say. With no saved walls (a room that was only ever the floor) the floor alone is
         * enough, and the horizontal position is taken to be unchanged.
         */
        fun solve(saved: Signature, current: Signature): Alignment? {
            if (saved.floorY != null && current.floorY == null && saved.walls.isEmpty()) return null
            val pairs = saved.walls.mapNotNull { s ->
                current.walls.minByOrNull { abs(angleDifference(it.heading, s.heading)) }
                    ?.takeIf { abs(angleDifference(it.heading, s.heading)) < MATCH }
                    ?.let { s to it }
            }
            if (saved.walls.isNotEmpty() && pairs.isEmpty()) return null
            // Yaw: the compass is close, the walls are exact.
            var sx = 0.0
            var sy = 0.0
            pairs.forEach { (s, c) ->
                val d = angleDifference(c.heading, s.heading).toDouble()
                sx += cos(d); sy += sin(d)
            }
            val yaw = if (pairs.isEmpty()) 0f else atan2(sy, sx).toFloat()
            // Shift: each matched wall says n·shift = d_now − d_then. Least squares over x and z,
            // lightly regularised so one wall alone moves the origin only across that wall.
            var a11 = 1e-3; var a12 = 0.0; var a22 = 1e-3; var b1 = 0.0; var b2 = 0.0
            pairs.forEach { (s, c) ->
                val n = directionOf(c.heading)
                val r = (c.offset - s.offset).toDouble()
                a11 += n.x * n.x; a12 += n.x * n.z; a22 += n.z * n.z
                b1 += n.x * r; b2 += n.z * r
            }
            val det = a11 * a22 - a12 * a12
            val tx = ((a22 * b1 - a12 * b2) / det).toFloat()
            val tz = ((a11 * b2 - a12 * b1) / det).toFloat()
            val ty = if (saved.floorY != null && current.floorY != null) current.floorY - saved.floorY else 0f
            return Alignment(yaw, Vec3(tx, ty, tz), pairs.size)
        }
    }

    // ── Placing ─────────────────────────────────────────────────────────────

    /** A page's default width on each kind of surface, in metres. */
    fun defaultWidth(surface: Surface) = if (surface == Surface.WALL) 0.9f else 1.2f

    /**
     * A page's face on a surface, seen from [viewer]: on a wall it stands upright; on the floor its
     * top points away from the viewer and on the ceiling towards them, so it reads the right way up
     * from where it was placed.
     */
    fun face(surface: Surface, normal: Vec3, point: Vec3, viewer: Vec3): Vec3 {
        if (surface == Surface.WALL) return Vec3.UP
        val away = (point - viewer).flat().let { if (it.length() < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
        return if (surface == Surface.FLOOR) away else -away
    }

    fun surfaceOf(kind: RoomLayout.Kind): Surface? = when (kind) {
        RoomLayout.Kind.FLOOR -> Surface.FLOOR
        RoomLayout.Kind.CEILING -> Surface.CEILING
        RoomLayout.Kind.WALL -> Surface.WALL
        RoomLayout.Kind.TABLE -> null
    }

    /** Where a ray from the viewer touches a placed page, as a fraction across (0..1) and down (0..1). */
    data class PageHit(val placement: Placement, val x: Float, val y: Float, val distance: Float)

    /** The nearest of [pages] (in one frame) along a ray in that frame, or null. */
    fun pageAt(pages: List<Placement>, origin: Vec3, direction: Vec3, margin: Float = 0f): PageHit? {
        val dir = direction.normalized()
        return pages.mapNotNull { p -> hit(p, origin, dir, margin) }.minByOrNull { it.distance }
    }

    fun hit(p: Placement, origin: Vec3, dir: Vec3, margin: Float = 0f): PageHit? {
        val denom = p.normal dot dir
        if (abs(denom) < 1e-5f) return null
        val t = (p.normal dot (p.center - origin)) / denom
        if (t <= 0f) return null
        val local = origin + dir * t - p.center
        val a = (local dot p.right) / (p.width / 2)
        val b = (local dot p.up) / (p.height / 2)
        val m = 1f + margin
        if (abs(a) > m || abs(b) > m) return null
        return PageHit(p, ((a + 1) / 2).coerceIn(0f, 1f), ((1 - b) / 2).coerceIn(0f, 1f), t)
    }

    /** The four corners of a page: top-left, top-right, bottom-right, bottom-left. */
    fun corners(p: Placement): List<Vec3> {
        val r = p.right * (p.width / 2)
        val u = p.up * (p.height / 2)
        return listOf(p.center - r + u, p.center + r + u, p.center + r - u, p.center - r - u)
    }
}
