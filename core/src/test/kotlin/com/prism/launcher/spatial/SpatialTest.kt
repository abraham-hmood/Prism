package com.prism.launcher.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The room pipeline against a room whose answer is known: a 4 x 4 m box, 2.5 m high, rendered into
 * depth images the way a camera would see it.
 */
class SpatialTest {

    private val half = 2f
    private val ceilingHeight = 2.5f

    /** Distance along an (unnormalised) world direction from [origin] to the box's inside. */
    private fun boxHit(origin: Vec3, w: Vec3): Float {
        var best = Float.MAX_VALUE
        fun consider(t: Float) { if (t > 1e-4f && t < best) best = t }
        if (w.y < 0) consider(-origin.y / w.y)
        if (w.y > 0) consider((ceilingHeight - origin.y) / w.y)
        if (w.x > 0) consider((half - origin.x) / w.x)
        if (w.x < 0) consider((-half - origin.x) / w.x)
        if (w.z > 0) consider((half - origin.z) / w.z)
        if (w.z < 0) consider((-half - origin.z) / w.z)
        return best
    }

    private fun pose(position: Vec3, yaw: Float, pitchDown: Float): Pose {
        val xw = Vec3(1f, 0f, 0f).rotatedY(yaw)
        val yw = Vec3.UP
        val zw = Vec3(0f, 0f, 1f).rotatedY(yaw)
        val y = yw * cos(pitchDown) - zw * sin(pitchDown)
        val z = zw * cos(pitchDown) + yw * sin(pitchDown)
        return Pose(floatArrayOf(xw.x, y.x, z.x, xw.y, y.y, z.y, xw.z, y.z, z.z), position)
    }

    private val intrinsics = Intrinsics(80f, 80f, 64f, 48f, 128, 96)

    private fun render(pose: Pose): DepthFrame {
        val k = intrinsics
        val depth = FloatArray(k.width * k.height) { i ->
            val u = (i % k.width).toFloat()
            val v = (i / k.width).toFloat()
            boxHit(pose.translation, pose.rotate(k.ray(u, v)))
        }
        return DepthFrame(depth, k, pose)
    }

    private fun scanned(): RoomScanner {
        val scanner = RoomScanner()
        val eye = Vec3(0.3f, 1.5f, 0.2f)
        for (step in 0 until 12) {
            val yaw = (step * Math.PI / 6).toFloat()
            scanner.addDepth(render(pose(eye, yaw, 0.35f)))
            scanner.addDepth(render(pose(eye, yaw, -0.45f)))
        }
        return scanner
    }

    @Test
    fun `the floor, the ceiling and four walls are found and labelled`() {
        val layout = RoomLayout.build(PlaneExtractor.extract(scanned()))
        val floor = assertNotNull(layout.floor, "floor")
        val ceiling = assertNotNull(layout.ceiling, "ceiling")
        assertEquals(0f, floor.center.y, 0.06f)
        assertEquals(ceilingHeight, ceiling.center.y, 0.06f)
        assertTrue((floor.normal dot Vec3.UP) > 0.95f)
        assertTrue((ceiling.normal dot Vec3.UP) < -0.95f)
        assertEquals(4, layout.walls.size, layout.describe())
        layout.walls.forEach { wall ->
            assertTrue(abs(wall.normal.y) < 0.1f)
            // Facing into the room, from floor to ceiling.
            assertTrue((wall.normal dot (Vec3(0f, wall.center.y, 0f) - wall.center)) > 0f)
            assertEquals(ceilingHeight / 2, wall.halfV, 0.1f)
        }
    }

    @Test
    fun `a ray lands on the surface it points at`() {
        val layout = RoomLayout.build(PlaneExtractor.extract(scanned()))
        val eye = Vec3(0f, 1.5f, 0f)
        assertEquals(RoomLayout.Kind.WALL, layout.raycast(eye, Vec3(0f, 0f, -1f))?.surface?.kind)
        assertEquals(RoomLayout.Kind.FLOOR, layout.raycast(eye, Vec3(0.1f, -1f, 0f))?.surface?.kind)
        assertEquals(RoomLayout.Kind.CEILING, layout.raycast(eye, Vec3(0f, 1f, 0.1f))?.surface?.kind)
        val wall = layout.raycast(eye, Vec3(1f, 0f, 0f))!!
        assertEquals(half, wall.point.x, 0.08f)
    }

    @Test
    fun `a missing ceiling is estimated above the walls, as Quest's layout does`() {
        val scanner = RoomScanner()
        val eye = Vec3(0f, 1.2f, 0f)
        for (step in 0 until 12) scanner.addDepth(render(pose(eye, (step * Math.PI / 6).toFloat(), 0.5f)))
        val layout = RoomLayout.build(PlaneExtractor.extract(scanner))
        assertNotNull(layout.floor)
        val ceiling = assertNotNull(layout.ceiling)
        assertTrue(ceiling.inferred)
        assertTrue(ceiling.center.y >= 2.4f - 0.06f)
    }

    @Test
    fun `a saved room is found again after the tracking origin moved and turned`() {
        val layout = RoomLayout.build(PlaneExtractor.extract(scanned()))
        val saved = ArHome.signature(layout, 0f)
        // The same room, as a session that started 0.7 m to one side and 25 degrees round would see it.
        val yaw = Math.toRadians(25.0).toFloat()
        val shift = Vec3(0.7f, 0f, -0.4f)
        val moved = ArHome.Signature(
            saved.floorY,
            saved.walls.map { w -> ArHome.WallRef(w.heading + yaw, w.offset + (directionOf(w.heading + yaw) dot shift)) },
        )
        val found = assertNotNull(ArHome.RoomAlignment.solve(saved, moved))
        assertEquals(yaw, found.yaw, 0.01f)
        assertEquals(shift.x, found.shift.x, 0.02f)
        assertEquals(shift.z, found.shift.z, 0.02f)
        val p = Vec3(1f, 1.2f, -2f)
        val expected = p.rotatedY(yaw) + shift
        val got = found.apply(p)
        assertEquals(expected.x, got.x, 0.03f)
        assertEquals(expected.z, got.z, 0.03f)
    }

    @Test
    fun `nothing is reported until enough of the room has been seen`() {
        val saved = ArHome.Signature(0f, listOf(ArHome.WallRef(0f, 2f), ArHome.WallRef(1.57f, 2f)))
        assertNull(ArHome.RoomAlignment.solve(saved, ArHome.Signature(0f, emptyList())))
    }

    @Test
    fun `a page faces the room, reads upright, and is hit where it is touched`() {
        val wall = ArHome.Placement("p", ArHome.Surface.WALL, Vec3(0f, 1.5f, -2f), Vec3(0f, 0f, 1f), Vec3.UP, 1f, 2f)
        val centre = assertNotNull(ArHome.hit(wall, Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f)))
        assertEquals(0.5f, centre.x, 1e-3f)
        assertEquals(0.5f, centre.y, 1e-3f)
        val topLeft = assertNotNull(ArHome.hit(wall, Vec3(0f, 1.5f, 0f), (Vec3(-0.45f, 2.45f, -2f) - Vec3(0f, 1.5f, 0f))))
        assertTrue(topLeft.x < 0.1f && topLeft.y < 0.1f, "${topLeft.x}, ${topLeft.y}")
        // On the floor, the top of the page points away from whoever placed it.
        val up = ArHome.face(ArHome.Surface.FLOOR, Vec3.UP, Vec3(0f, 0f, -1.5f), Vec3(0f, 1.5f, 0f))
        assertTrue((up dot Vec3(0f, 0f, -1f)) > 0.99f)
        val floorPage = ArHome.Placement("f", ArHome.Surface.FLOOR, Vec3(0f, 0f, -1.5f), Vec3.UP, up, 1f, 1f)
        assertTrue((floorPage.right dot Vec3(1f, 0f, 0f)) > 0.99f, "not mirrored")
    }

    /** A cabinet standing in front of the -Z wall: clutter that must not pull the wall forward. */
    private fun cabinetHit(origin: Vec3, w: Vec3): Float {
        val min = Vec3(0.3f, 0f, -1.95f)
        val max = Vec3(1.1f, 1.0f, -1.45f)
        var tNear = -Float.MAX_VALUE
        var tFar = Float.MAX_VALUE
        for (axis in 0..2) {
            val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            val d = when (axis) { 0 -> w.x; 1 -> w.y; else -> w.z }
            val lo = when (axis) { 0 -> min.x; 1 -> min.y; else -> min.z }
            val hi = when (axis) { 0 -> max.x; 1 -> max.y; else -> max.z }
            if (abs(d) < 1e-9f) { if (o < lo || o > hi) return Float.MAX_VALUE; continue }
            var t1 = (lo - o) / d
            var t2 = (hi - o) / d
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tNear = maxOf(tNear, t1); tFar = minOf(tFar, t2)
        }
        return if (tNear <= tFar && tNear > 1e-4f) tNear else Float.MAX_VALUE
    }

    @Test
    fun `walls are found to within a few centimetres despite noise, outliers and clutter`() {
        val random = java.util.Random(11)
        val scanner = RoomScanner()
        val eye = Vec3(0.3f, 1.5f, 0.2f)
        for (step in 0 until 12) for (pitch in listOf(0.35f, -0.45f)) {
            val pose = pose(eye, (step * Math.PI / 6).toFloat(), pitch)
            val k = intrinsics
            val depth = FloatArray(k.width * k.height) { i ->
                val w = pose.rotate(k.ray((i % k.width).toFloat(), (i / k.width).toFloat()))
                val d = minOf(boxHit(pose.translation, w), cabinetHit(pose.translation, w))
                when {
                    random.nextFloat() < 0.02f -> d * (0.3f + random.nextFloat()) // a wild sample
                    else -> d * (1f + random.nextGaussian().toFloat() * 0.01f)    // 1% noise
                }
            }
            scanner.addDepth(DepthFrame(depth, k, pose))
        }
        val layout = RoomLayout.build(PlaneExtractor.extract(scanner))
        assertEquals(0f, layout.floor!!.center.y, 0.03f)
        val back = layout.walls.single { it.normal.z > 0.9f }
        assertEquals(-half, back.center.z, 0.03f, "the cabinet must not pull the wall forward")
        layout.walls.forEach { wall ->
            // Squared to the room: exactly along an axis.
            assertTrue(abs(wall.normal.x) > 0.999f || abs(wall.normal.z) > 0.999f, "${wall.normal}")
        }
    }

    @Test
    fun `a wall that drops out of view is remembered, and a phantom needs a second look`() {
        val memory = SurfaceMemory()
        val full = RoomLayout.build(PlaneExtractor.extract(scanned()))
        memory.merge(full)
        val second = memory.merge(full)
        assertEquals(4, second.walls.size)
        // The next rebuild sees only the floor: nothing it knew is forgotten.
        val partial = RoomLayout(full.floor, null, emptyList(), emptyList())
        val after = memory.merge(partial)
        assertEquals(4, after.walls.size)
        assertNotNull(after.ceiling)
        // A small wall seen once is held back until it is seen again.
        val phantom = RoomLayout.Surface(RoomLayout.Kind.WALL, Vec3(0f, 1.2f, 0.5f), Vec3(0f, 0f, -1f), Vec3(-1f, 0f, 0f), Vec3.UP, 0.3f, 1.2f)
        assertEquals(4, memory.merge(RoomLayout(full.floor, null, listOf(phantom), emptyList())).walls.size)
    }

    @Test
    fun `a placed page stays exactly where it was put when detection fails`() {
        ArHome.reset()
        val session = ArRoomSession()
        scanned().cells().forEach { c -> repeat(c.weight.toInt()) { session.scanner.addSample(c.position, c.normal) } }
        session.rebuild()
        val hit = assertNotNull(session.layout.raycast(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f)))
        assertTrue(session.place("page#0", hit, Vec3(0f, 1.5f, 0f), 0.5f))
        val before = session.placed().single()
        // Everything the camera knew is gone, and the next rebuild finds nothing.
        session.scanner.clear()
        repeat(3) { session.rebuild() }
        assertEquals(before, session.placed().single())
        ArHome.reset()
    }

    @Test
    fun `a saved page appears at the best guess when the room cannot be recognised`() {
        ArHome.reset()
        val first = ArRoomSession()
        scanned().cells().forEach { c -> repeat(c.weight.toInt()) { first.scanner.addSample(c.position, c.normal) } }
        first.rebuild()
        first.place("page#0", first.layout.raycast(Vec3(0f, 1.5f, 0f), Vec3(0f, 0f, -1f))!!, Vec3(0f, 1.5f, 0f), 0.5f)

        // Next session: only the floor is in view, so no wall can be matched.
        var now = 0L
        val second = ArRoomSession(clock = { now })
        val floorOnly = RoomScanner()
        for (step in 0 until 12) floorOnly.addDepth(render(pose(Vec3(0f, 1.5f, 0f), (step * Math.PI / 6).toFloat(), 1.2f)))
        floorOnly.cells().filter { it.normal.y > 0.9f }.forEach { c -> repeat(c.weight.toInt()) { second.scanner.addSample(c.position, c.normal) } }
        second.rebuild()
        assertTrue(second.placed().isEmpty(), "not straight away")
        now = 9_000L
        second.rebuild()
        assertEquals(1, second.placed().size)
        assertEquals(false, second.confident)
        ArHome.reset()
    }

    @Test
    fun `a webcam's relative depth becomes a metric, level room`() {
        // A webcam 1.1 m up on a desk, looking slightly down into the room.
        val camera = pose(Vec3(0f, 1.1f, 1.5f), 0f, 0.15f)
        val w = 128
        val h = 96
        val f = (w / 2f) / kotlin.math.tan(Math.toRadians(35.0)).toFloat() // a 70 degree webcam
        val k = Intrinsics(f, f, w / 2f, h / 2f, w, h)
        val scale = 3.7f
        val shift = 0.42f
        val prediction = FloatArray(w * h) { i ->
            val z = boxHit(camera.translation, camera.rotate(k.ray((i % w).toFloat(), (i / w).toFloat())))
            scale / z + shift
        }
        val solved = assertNotNull(MonocularDepth.solve(prediction, w, h))
        assertTrue(solved.regions >= 3, "regions ${solved.regions}")
        // Level: the room's up is found from the floor, not from the camera's tilt.
        val tilt = Math.toDegrees(kotlin.math.acos((solved.up dot Vec3.UP).toDouble().coerceIn(-1.0, 1.0)))
        assertEquals(Math.toDegrees(0.15), tilt, 3.0)
        // A webcam fuses a stream of frames from one place, as the desktop does.
        val scanner = RoomScanner().apply { repeat(3) { addDepth(solved.frame, stride = 1) } }
        val layout = RoomLayout.build(PlaneExtractor.extract(scanner))
        assertNotNull(layout.floor, layout.describe())
        assertEquals(0f, layout.floor!!.center.y, 0.12f)
        assertTrue(layout.walls.isNotEmpty(), layout.describe())
    }
}
