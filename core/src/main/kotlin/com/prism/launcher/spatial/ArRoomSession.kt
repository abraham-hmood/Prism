package com.prism.launcher.spatial

import com.prism.launcher.spatial.ArHome.Placement
import kotlin.math.abs

/**
 * One AR home session: the room being scanned, the layout found in it so far, and the pages placed
 * on it -- shared by the phone and the desktop, which differ only in where depth comes from and how
 * the result is drawn.
 *
 * Everything here is in the platform's WORLD frame (ARCore's on a phone, the webcam's on a
 * computer), with Y up. [headingOffset] turns a world direction's heading into the room frame's
 * (see [ArHome]): a compass correction on a phone, zero on a computer.
 *
 * ## Pages stay where they were put
 *
 * Detection is never perfect: walk around and a wall seen a minute ago may not be found again, or
 * may be found a few centimetres off. So a page is PINNED in world space the moment it is placed
 * (or, in a later session, the moment the saved room is found), and from then on it does not
 * follow the room model at all -- the room model is for placing, not for holding. A phone pins
 * each page to an ARCore anchor as well, so it rides ARCore's own corrections.
 *
 * Finding the saved room is done once per session and then locked. If the room cannot be
 * recognised (the walls seen so far do not match the saved ones), pages are shown anyway after a
 * few seconds at the best guess -- the compass bearing and the floor height -- rather than hidden;
 * a confident match arriving later corrects them once.
 */
class ArRoomSession(
    private val headingOffset: () -> Float = { 0f },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val scanner = RoomScanner()
    private val memory = SurfaceMemory()

    @Volatile var layout: RoomLayout = RoomLayout.EMPTY
        private set

    /** How the saved room sits in this session's world, once found (or guessed). */
    @Volatile var alignment: ArHome.Alignment? = null
        private set

    /** True once the saved room was recognised from its walls, or there was no saved room. */
    @Volatile var confident: Boolean = false
        private set

    private var state: ArHome.State = ArHome.load()
    private val pins = LinkedHashMap<String, Placement>()
    private val movedThisSession = HashSet<String>()
    private val startedAt = clock()

    val setupDone: Boolean get() = synchronized(this) { state.setupDone }

    /** Re-finds the room's surfaces from everything scanned so far, and the saved room in it. */
    fun rebuild() {
        val built = RoomLayout.build(PlaneExtractor.extract(scanner))
        val remembered = memory.merge(built)
        layout = remembered
        synchronized(this) {
            val saved = state.room
            val offset = headingOffset()
            val current = ArHome.signature(remembered, offset)
            if (saved == null || state.pages.isEmpty()) {
                // Nothing to find: this session's room frame IS the room frame, and the room is
                // recorded afresh with the next page placed.
                if (saved != null) state = state.copy(room = null)
                if (!remembered.isEmpty && alignment == null) {
                    alignment = ArHome.Alignment.IDENTITY
                    confident = true
                }
                return
            }
            if (!confident) {
                val found = ArHome.RoomAlignment.solve(saved, current)
                val needed = minOf(2, saved.walls.size)
                if (found != null && found.matchedWalls >= needed) {
                    alignment = found
                    confident = true
                    pinSaved(found, replace = true)
                } else if (alignment == null && remembered.floor != null && clock() - startedAt > GUESS_AFTER_MS) {
                    // Not recognised yet: show the pages at the best guess rather than not at all.
                    val floorShift = (remembered.floor.center.y) - (saved.floorY ?: remembered.floor.center.y)
                    val guess = found ?: ArHome.Alignment(0f, Vec3(0f, floorShift, 0f), 0)
                    alignment = guess
                    pinSaved(guess, replace = false)
                }
            }
            val a = alignment ?: return
            if (confident) remember(saved, current, a)
        }
    }

    /** Walls seen now that the saved room did not have, kept so the room is easier to find next time. */
    private fun remember(saved: ArHome.Signature, current: ArHome.Signature, found: ArHome.Alignment) {
        val extra = current.walls.filter { now ->
            val n = directionOf(now.heading)
            saved.walls.none { then ->
                abs(angleDifference(then.heading + found.yaw, now.heading)) < 0.3f &&
                    abs(then.offset + (n dot found.shift) - now.offset) < 0.3f
            }
        }.map { now -> ArHome.WallRef(now.heading - found.yaw, now.offset - (directionOf(now.heading) dot found.shift)) }
        if (extra.isNotEmpty()) {
            state = state.copy(room = saved.copy(walls = saved.walls + extra))
            ArHome.save(state)
        }
    }

    /** Pins every saved page that has not been moved this session, through [a]. */
    private fun pinSaved(a: ArHome.Alignment, replace: Boolean) {
        state.pages.forEach { p ->
            if (p.key in movedThisSession) return@forEach
            if (!replace && p.key in pins) return@forEach
            pins[p.key] = p.copy(
                center = toWorld(p.center, a),
                normal = toWorldDir(p.normal, a).normalized(),
                up = toWorldDir(p.up, a).normalized(),
            )
        }
    }

    // Room frame <-> world.
    private fun toWorld(p: Vec3, a: ArHome.Alignment) = a.apply(p).rotatedY(-headingOffset())
    private fun toWorldDir(d: Vec3, a: ArHome.Alignment) = a.applyDirection(d).rotatedY(-headingOffset())
    private fun toRoom(p: Vec3, a: ArHome.Alignment) = a.invert(p.rotatedY(headingOffset()))
    private fun toRoomDir(d: Vec3, a: ArHome.Alignment) = a.invertDirection(d.rotatedY(headingOffset()))

    /** Every page shown now, in world coordinates. */
    fun placed(): List<Placement> = synchronized(this) { pins.values.toList() }

    /** Writes [world] (a pinned page) back to the saved state, in the room frame. */
    private fun store(world: Placement) {
        val a = alignment ?: return
        val inRoom = world.copy(
            center = toRoom(world.center, a),
            normal = toRoomDir(world.normal, a).normalized(),
            up = toRoomDir(world.up, a).normalized(),
        )
        val room = state.room ?: ArHome.signature(layout, headingOffset()).takeIf { a == ArHome.Alignment.IDENTITY }
        state = state.copy(room = room, pages = state.pages.filterNot { it.key == world.key } + inRoom)
        ArHome.save(state)
    }

    /**
     * Puts the page [key] where [hit] landed, seen from [viewer]. [aspect] is the page's width over
     * its height. [measured] is where the device itself measured the surface under the finger (an
     * ARCore hit, a depth sample); when it agrees with [hit] it is the more exact of the two, so the
     * page sits on the real wall even where the room model is a few centimetres off. Placing a page
     * that is already placed moves it.
     */
    fun place(key: String, hit: RoomLayout.Hit, viewer: Vec3, aspect: Float, measured: Vec3? = null): Boolean = synchronized(this) {
        val surface = ArHome.surfaceOf(hit.surface.kind) ?: return false
        alignment ?: return false
        val existing = pins[key]
        val width = existing?.width ?: ArHome.defaultWidth(surface)
        val height = width / aspect.coerceIn(0.2f, 5f)
        val point = measured?.takeIf { (it - hit.point).length() < 0.6f } ?: hit.point
        val worldUp = ArHome.face(surface, hit.surface.normal, point, viewer)
        val world = Placement(key, surface, point + hit.surface.normal * 0.01f, hit.surface.normal, worldUp, width, height)
        pins[key] = world
        movedThisSession += key
        store(world)
        true
    }

    /** Grows or shrinks a placed page by [factor], keeping its centre and shape. */
    fun resize(key: String, factor: Float) = synchronized(this) {
        val p = pins[key] ?: return@synchronized
        val width = (p.width * factor).coerceIn(0.15f, 6f)
        val resized = p.copy(width = width, height = width * p.height / p.width)
        pins[key] = resized
        movedThisSession += key
        store(resized)
    }

    fun remove(key: String) = synchronized(this) {
        pins.remove(key)
        movedThisSession += key
        state = state.copy(pages = state.pages.filterNot { it.key == key })
        ArHome.save(state)
    }

    /** "Done": the setup grid is not shown again, and taps now open pages. */
    fun finishSetup() = synchronized(this) {
        state = state.copy(setupDone = true)
        ArHome.save(state)
    }

    /** Opens the setup grid again, to place or rearrange pages. */
    fun reopenSetup() = synchronized(this) {
        state = state.copy(setupDone = false)
        ArHome.save(state)
    }

    fun placedKeys(): Set<String> = synchronized(this) { state.pages.map { it.key }.toSet() }

    /** The page under a world ray, if any. */
    fun pageAt(origin: Vec3, direction: Vec3, margin: Float = 0f): ArHome.PageHit? =
        ArHome.pageAt(placed(), origin, direction, margin)

    private companion object {
        const val GUESS_AFTER_MS = 8_000L
    }
}
