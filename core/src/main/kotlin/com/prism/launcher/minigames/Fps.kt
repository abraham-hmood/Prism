package com.prism.launcher.minigames

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The shared first-person floor.
 *
 * DOOM and Zombies are different games with one ancestor: a grid, a camera, hitscan weapons and
 * things that walk at you. That ancestor lives here so the phone and the desktop cast the same
 * walls, the enemies take the same route around a corner, and a bug fixed in a shotgun is fixed in
 * both cabinets.
 *
 * ## No engine, on purpose
 *
 * This is a launcher minigame, not a game that can afford a 3D engine dependency. A grid ray caster
 * draws a convincing corridor at 60 fps on a phone that is already running a launcher, and it keeps
 * the APK's size where it was. Walls are computed per column by the renderer from [FpsScene]; the
 * simulation only owns positions, angles and state.
 *
 * ## The map is a seed
 *
 * Every level is generated from its seed. Two devices that agree on a seed and a size are standing
 * in the same building without a byte of geometry on the wire, which is what makes co-op possible
 * on a lossy UDP mesh.
 */
object Fps {
    const val FLOOR = 0
    const val WALL = 1
    const val EXIT = 2

    const val PLAYER_RADIUS = 0.22
    const val EYE_HEIGHT = 0.54
    const val MAX_VIEW = 40.0

    /** How far a player can walk in one second, before sprinting. */
    const val WALK_SPEED = 2.7
    const val RUN_SPEED = 4.1

    /**
     * A DDA ray cast.
     *
     * [distance] is measured along the ray, not along the camera's forward axis. A renderer that
     * draws a fan of rays has to multiply by `cos(rayAngle - cameraAngle)` or the walls bow; the
     * correction belongs in the projection, not here, because line-of-sight and shooting both want
     * the true distance.
     */
    fun cast(map: FpsMap, x0: Double, y0: Double, angle: Double, maxDistance: Double = MAX_VIEW): FpsHit {
        val dx = cos(angle)
        val dy = sin(angle)
        var mapX = floor(x0).toInt()
        var mapY = floor(y0).toInt()

        val deltaX = if (abs(dx) < 1e-9) 1e30 else abs(1.0 / dx)
        val deltaY = if (abs(dy) < 1e-9) 1e30 else abs(1.0 / dy)

        val stepX: Int
        val stepY: Int
        var sideX: Double
        var sideY: Double
        if (dx < 0) {
            stepX = -1
            sideX = (x0 - mapX) * deltaX
        } else {
            stepX = 1
            sideX = (mapX + 1 - x0) * deltaX
        }
        if (dy < 0) {
            stepY = -1
            sideY = (y0 - mapY) * deltaY
        } else {
            stepY = 1
            sideY = (mapY + 1 - y0) * deltaY
        }

        var side = 0
        var distance = 0.0
        var guard = 0
        while (distance <= maxDistance && guard++ < 512) {
            if (sideX < sideY) {
                distance = sideX
                sideX += deltaX
                mapX += stepX
                side = 0
            } else {
                distance = sideY
                sideY += deltaY
                mapY += stepY
                side = 1
            }
            if (distance > maxDistance) break
            val tile = map.tileAt(mapX, mapY)
            if (tile != FLOOR) {
                val hitX = x0 + dx * distance
                val hitY = y0 + dy * distance
                val u = if (side == 0) hitY - floor(hitY) else hitX - floor(hitX)
                return FpsHit(distance, hitX, hitY, mapX, mapY, side, tile, u)
            }
        }
        return FpsHit(maxDistance, x0 + dx * maxDistance, y0 + dy * maxDistance, mapX, mapY, 0, WALL, 0.0)
    }

    /** True when the segment is not crossed by a wall. */
    fun visible(map: FpsMap, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val distance = hypot(dx, dy)
        if (distance < 1e-6) return true
        val hit = cast(map, ax, ay, atan2(dy, dx), distance)
        return hit.distance >= distance - 1e-3
    }

    /**
     * Moves a circle with wall sliding.
     *
     * Both axes are tried separately. That is what lets a player strafe along a wall instead of
     * sticking to it, and it is cheap enough to run for every actor every frame.
     */
    fun move(
        map: FpsMap,
        x: Double,
        y: Double,
        dx: Double,
        dy: Double,
        radius: Double = PLAYER_RADIUS,
    ): Pair<Double, Double> {
        var nx = x
        var ny = y
        if (!map.blocked(x + dx, y, radius)) nx = x + dx
        if (!map.blocked(nx, y + dy, radius)) ny = y + dy
        return nx to ny
    }

    /** A ray against a vertical cylinder; returns the distance or null. */
    fun rayCircle(
        ox: Double,
        oy: Double,
        dx: Double,
        dy: Double,
        cx: Double,
        cy: Double,
        radius: Double,
        maxDistance: Double,
    ): Double? {
        val lx = cx - ox
        val ly = cy - oy
        val projected = lx * dx + ly * dy
        if (projected < -radius || projected > maxDistance + radius) return null
        val closestX = ox + dx * projected
        val closestY = oy + dy * projected
        val off = hypot(cx - closestX, cy - closestY)
        if (off > radius) return null
        val back = kotlin.math.sqrt(max(0.0, radius * radius - off * off))
        val distance = projected - back
        return if (distance in 0.0..maxDistance) distance else null
    }
}

data class FpsHit(
    val distance: Double,
    val x: Double,
    val y: Double,
    val cellX: Int,
    val cellY: Int,
    val side: Int,
    val tile: Int,
    val u: Double,
)

/** One input frame. The same shape serves a human, a bot and a remote mesh player. */
data class FpsInput(
    val forward: Float = 0f,
    val strafe: Float = 0f,
    val turn: Float = 0f,
    val look: Float = 0f,
    val fire: Boolean = false,
    val reload: Boolean = false,
    val use: Boolean = false,
    val sprint: Boolean = false,
    val switchWeapon: Int = -1,
)

enum class FpsSound {
    SHOT_PISTOL, SHOT_SHOTGUN, SHOT_SMG, SHOT_RIFLE,
    HIT, ZOMBIE, GROAN, STEP, RELOAD, HURT, WAVE, REVIVE,
    EMPTY, PICKUP, EXIT, SCREAM, BUY, SWITCH,
}

data class FpsEvent(
    val sound: FpsSound,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val volume: Float = 1f,
    val text: String? = null,
    val speaker: String? = null,
    /** A footstep made while sprinting, so a renderer can raise its pitch. */
    val running: Boolean = false,
)

data class FpsSpeech(val text: String, val speaker: String, val voiceKey: Int)

/** One billboarded thing in the world. The renderer projects it with the camera. */
data class FpsSprite(
    val x: Double,
    val y: Double,
    val kind: Int,
    val size: Double,
    val alpha: Float = 1f,
    val health: Float = -1f,
    val label: String? = null,
)

object FpsSprites {
    const val ZOMBIE = 0
    const val IMP = 1
    const val GRUNT = 2
    const val HEALTH = 3
    const val AMMO = 4
    const val ARMOR = 5
    const val WEAPON = 6
    const val EXIT = 7
    const val TEAMMATE = 8
    const val PROJECTILE = 9
    const val BARREL = 10
    const val POWER = 11
    const val SPITTER = 12
    const val BRUTE = 13
}

/**
 * A full frame of game state, with nothing Android or Compose in it.
 *
 * The renderer receives the map and camera rather than a list of pre-cast columns. That sounds
 * backwards -- the renderer re-does work -- but it means the cast can happen at the exact pixel
 * width of the screen instead of at a fixed resolution that gets stretched, and it keeps the
 * simulation free of draw calls.
 */
data class FpsScene(
    val map: FpsMap,
    val cameraX: Double,
    val cameraY: Double,
    val cameraAngle: Double,
    val pitch: Double,
    val sprites: List<FpsSprite>,
    val health: Float,
    val armor: Float,
    val downed: Boolean,
    val bleedout: Double,
    val ammoMag: Int,
    val ammoReserve: Int,
    val weaponName: String,
    val weaponIndex: Int,
    val weaponCount: Int,
    val wave: Int,
    val waveLabel: String,
    val points: Int,
    val kills: Int,
    val time: Double,
    val message: String?,
    val objective: String?,
    val subtitles: List<String>,
    val crosshair: Boolean = true,
    val muzzle: Float = 0f,
    val hurt: Float = 0f,
    val flash: Float = 0f,
    val status: Int = 0,
    val score: Int = 0,
    val coOp: Boolean = false,
    val teammates: Int = 0,
)

enum class FpsStatus { RUNNING, WON, LOST }

/** A generated grid. Rooms, corridors and an exit; the same seed always makes the same building. */
class FpsMap private constructor(
    val width: Int,
    val height: Int,
    private val tiles: IntArray,
    val seed: Long,
    val spawnX: Double,
    val spawnY: Double,
    val exitX: Double,
    val exitY: Double,
) {
    private var openCache: List<Int>? = null

    fun tileAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) Fps.WALL else tiles[y * width + x]

    fun isWall(x: Int, y: Int): Boolean = tileAt(x, y) != Fps.FLOOR
    fun isExit(x: Int, y: Int): Boolean = tileAt(x, y) == Fps.EXIT
    fun cell(x: Double, y: Double): Int = floor(y).toInt() * width + floor(x).toInt()

    fun blocked(x: Double, y: Double, radius: Double = Fps.PLAYER_RADIUS): Boolean {
        if (x < radius || y < radius || x >= width - radius || y >= height - radius) return true
        val r = radius.coerceAtMost(0.49)
        // The centre plus eight points on the circle: cheap, and tight enough that a player cannot
        // shave a corner and end up inside a wall.
        if (isWall(floor(x).toInt(), floor(y).toInt())) return true
        for (i in 0 until 8) {
            val angle = PI * i / 4.0
            if (isWall(floor(x + cos(angle) * r).toInt(), floor(y + sin(angle) * r).toInt())) return true
        }
        return false
    }

    fun openCells(): List<Int> {
        openCache?.let { return it }
        val result = ArrayList<Int>(width * height / 2)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (tiles[y * width + x] == Fps.FLOOR) result.add(y * width + x)
            }
        }
        return result.also { openCache = it }
    }

    fun cellX(index: Int): Int = index % width
    fun cellY(index: Int): Int = index / width

    /**
     * BFS distance from a cell, for pathing.
     *
     * Unreachable cells are [Int.MAX_VALUE]. A zombie that cannot find a route stands still rather
     * than walking into the wall between it and the player, which is less scary but more honest.
     */
    fun distanceField(fromX: Int, fromY: Int): IntArray {
        val distances = IntArray(width * height) { Int.MAX_VALUE }
        if (fromX !in 0 until width || fromY !in 0 until height) return distances
        val start = fromY * width + fromX
        if (tiles[start] != Fps.FLOOR) return distances
        distances[start] = 0
        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            val next = distances[index] + 1
            if (x > 0) relax(index - 1, next, distances, queue)
            if (x + 1 < width) relax(index + 1, next, distances, queue)
            if (y > 0) relax(index - width, next, distances, queue)
            if (y + 1 < height) relax(index + width, next, distances, queue)
        }
        return distances
    }

    private fun relax(index: Int, distance: Int, distances: IntArray, queue: ArrayDeque<Int>) {
        if (tiles[index] != Fps.FLOOR || distances[index] <= distance) return
        distances[index] = distance
        queue.add(index)
    }

    /** The cell next to [x],[y] that is closer to the field's source, or null when there is none. */
    fun stepDownhill(x: Double, y: Double, field: IntArray): Pair<Int, Int>? {
        val cx = floor(x).toInt()
        val cy = floor(y).toInt()
        val here = field.getOrNull(cy * width + cx) ?: Int.MAX_VALUE
        if (here == Int.MAX_VALUE) return null
        var best: Pair<Int, Int>? = null
        var bestValue = here
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val value = field[ny * width + nx]
                if (value < bestValue) {
                    bestValue = value
                    best = nx to ny
                }
            }
        }
        return best
    }

    fun farthestOpen(fromX: Double, fromY: Double, minimumDistance: Double = 0.0): Pair<Double, Double> {
        val field = distanceField(floor(fromX).toInt(), floor(fromY).toInt())
        var bestIndex = -1
        var bestDistance = -1
        for (index in openCells()) {
            val steps = field[index]
            if (steps == Int.MAX_VALUE) continue
            val x = index % width + 0.5
            val y = index / width + 0.5
            if (hypot(x - fromX, y - fromY) < minimumDistance) continue
            if (steps > bestDistance) {
                bestDistance = steps
                bestIndex = index
            }
        }
        if (bestIndex < 0) return spawnX to spawnY
        return (bestIndex % width + 0.5) to (bestIndex / width + 0.5)
    }

    companion object {
        /**
         * Rooms first, corridors second.
         *
         * The rooms are rejected if they touch another room, because two rooms sharing a wall read
         * as one long room and every corridor disappears into it. A one-cell gap guarantees a wall
         * between them.
         */
        fun generate(
            seed: Long,
            width: Int = 44,
            height: Int = 44,
            roomCount: Int = 11,
            roomMin: Int = 4,
            roomMax: Int = 9,
            corridorWidth: Int = 1,
            courtyard: Boolean = false,
        ): FpsMap {
            val rng = Random(seed)
            val tiles = IntArray(width * height) { Fps.WALL }
            data class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
                val cx: Int get() = x + w / 2
                val cy: Int get() = y + h / 2
                fun overlaps(other: Room): Boolean =
                    x - 1 <= other.x + other.w && x + w + 1 >= other.x &&
                        y - 1 <= other.y + other.h && y + h + 1 >= other.y
            }

            val rooms = ArrayList<Room>()
            var attempts = 0
            while (rooms.size < roomCount && attempts++ < 500) {
                val w = roomMin + rng.nextInt(roomMax - roomMin + 1)
                val h = roomMin + rng.nextInt(roomMax - roomMin + 1)
                val x = 2 + rng.nextInt((width - w - 4).coerceAtLeast(1))
                val y = 2 + rng.nextInt((height - h - 4).coerceAtLeast(1))
                val room = Room(x, y, w, h)
                if (rooms.any { it.overlaps(room) }) continue
                rooms.add(room)
            }
            if (rooms.isEmpty()) rooms.add(Room(2, 2, (width - 4).coerceAtMost(8), (height - 4).coerceAtMost(8)))

            fun carve(x1: Int, y1: Int, x2: Int, y2: Int) {
                for (y in y1..y2) {
                    for (x in x1..x2) {
                        if (x in 1 until width - 1 && y in 1 until height - 1) tiles[y * width + x] = Fps.FLOOR
                    }
                }
            }
            rooms.forEach { carve(it.x, it.y, it.x + it.w - 1, it.y + it.h - 1) }

            fun corridor(a: Room, b: Room) {
                val half = corridorWidth / 2
                if (rng.nextBoolean()) {
                    val y1 = min(a.cy, b.cy) - half
                    val y2 = max(a.cy, b.cy) + half
                    carve(min(a.cx, b.cx) - half, y1, max(a.cx, b.cx) + half, y2)
                    carve(b.cx - half, y1, b.cx + half, y2)
                } else {
                    val x1 = min(a.cx, b.cx) - half
                    val x2 = max(a.cx, b.cx) + half
                    carve(x1, min(a.cy, b.cy) - half, x2, max(a.cy, b.cy) + half)
                    carve(x1, b.cy - half, x2, b.cy + half)
                }
            }
            for (i in 1 until rooms.size) corridor(rooms[i - 1], rooms[i])
            // A couple of loops keep a generated level from being a single dead-end branch, which
            // is both boring to explore and trivial for a zombie to camp.
            repeat(2) {
                if (rooms.size >= 4) {
                    val a = rooms[rng.nextInt(rooms.size)]
                    val b = rooms[rng.nextInt(rooms.size)]
                    if (a !== b) corridor(a, b)
                }
            }
            if (courtyard) {
                val w = (width / 3).coerceAtLeast(8)
                val h = (height / 3).coerceAtLeast(8)
                carve(width / 2 - w / 2, height / 2 - h / 2, width / 2 + w / 2, height / 2 + h / 2)
            }

            val first = rooms.first()
            val spawnX = first.cx + 0.5
            val spawnY = first.cy + 0.5

            // Exit: the farthest reachable floor cell, which makes the level a journey rather than
            // a room with a door in it.
            val open = ArrayList<Int>()
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                if (tiles[y * width + x] == Fps.FLOOR) open.add(y * width + x)
            }
            fun fieldFrom(index: Int): IntArray {
                val distances = IntArray(width * height) { Int.MAX_VALUE }
                distances[index] = 0
                val queue = ArrayDeque<Int>()
                queue.add(index)
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val cx = current % width
                    val cy = current / width
                    listOf(current - 1, current + 1, current - width, current + width).forEach { next ->
                        if (next !in 0 until width * height) return@forEach
                        val nx = next % width
                        val ny = next / width
                        if (abs(nx - cx) + abs(ny - cy) != 1) return@forEach
                        if (tiles[next] != Fps.FLOOR || distances[next] <= distances[current] + 1) return@forEach
                        distances[next] = distances[current] + 1
                        queue.add(next)
                    }
                }
                return distances
            }
            val distances = fieldFrom(first.cy * width + first.cx)
            val farthest = open.maxByOrNull { distances[it] } ?: (first.cy * width + first.cx)
            tiles[farthest] = Fps.EXIT
            val exitX = farthest % width + 0.5
            val exitY = farthest / width + 0.5

            return FpsMap(width, height, tiles, seed, spawnX, spawnY, exitX, exitY)
        }
    }
}

/** A weapon's numbers. Doom and Zombies use different tables; the firing code is shared. */
data class FpsWeapon(
    val name: String,
    val short: String,
    val damage: Float,
    val magazine: Int,
    val reserve: Int,
    val cooldown: Double,
    val reload: Double,
    val pellets: Int = 1,
    val spread: Double = 0.0,
    val sound: FpsSound = FpsSound.SHOT_PISTOL,
    val automatic: Boolean = false,
)

object FpsWeapons {
    val DOOM: List<FpsWeapon> = listOf(
        FpsWeapon("PISTOL", "PST", 18f, 12, 120, 0.34, 1.1, spread = 0.018),
        FpsWeapon("SHOTGUN", "SHG", 10f, 8, 64, 0.78, 1.9, pellets = 8, spread = 0.13, sound = FpsSound.SHOT_SHOTGUN),
        FpsWeapon("CHAINGUN", "CHG", 9f, 40, 200, 0.085, 1.6, spread = 0.045, sound = FpsSound.SHOT_SMG, automatic = true),
    )

    val ZOMBIES: List<FpsWeapon> = listOf(
        FpsWeapon("M1911", "M1911", 28f, 8, 80, 0.30, 1.15, spread = 0.012),
        FpsWeapon("VECTOR", "SMG", 16f, 32, 256, 0.095, 1.55, spread = 0.042, sound = FpsSound.SHOT_SMG, automatic = true),
        FpsWeapon("OLYMPIA", "SG", 12f, 6, 60, 0.72, 2.05, pellets = 8, spread = 0.12, sound = FpsSound.SHOT_SHOTGUN),
        FpsWeapon("GALIL", "RIF", 31f, 35, 245, 0.16, 1.9, spread = 0.025, sound = FpsSound.SHOT_RIFLE, automatic = true),
    )
}

open class FpsActor(
    val id: Int,
    val name: String,
    var x: Double,
    var y: Double,
    var angle: Double = 0.0,
) {
    var health: Float = 100f
    var maxHealth: Float = 100f
}

class FpsPlayer(
    id: Int,
    name: String,
    x: Double,
    y: Double,
    val isBot: Boolean,
    var isRemote: Boolean = false,
) : FpsActor(id, name, x, y) {
    var pitch: Double = 0.0
    var armor: Float = 0f
    var score: Int = 0
    var kills: Int = 0
    var weapon: Int = 0
    var magazine: Int = 8
    var reserve: Int = 80
    var reloadTimer: Double = 0.0
    var cooldown: Double = 0.0
    var downed: Boolean = false
    var bleedout: Double = 0.0
    var reviveProgress: Double = 0.0
    var muzzle: Float = 0f
    var hurt: Float = 0f
    var step: Double = 0.0
    var lastDamageAt: Double = -100.0
    var botTarget: Int = -1
    var botThink: Double = 0.0
    val alive: Boolean get() = health > 0f && !downed
}

class FpsEnemy(
    id: Int,
    x: Double,
    y: Double,
    val kind: Int,
    health: Float,
    val speed: Double,
    val damage: Float,
    val ranged: Boolean,
    val attackInterval: Double,
) : FpsActor(id, "", x, y) {
    var maxHealthValue: Float = health
    var attackTimer: Double = 0.0
    var hurt: Float = 0f
    var dying: Double = 0.0
    var gait: Double = 0.0
    var state: Int = 0
    var targetId: Int = -1
    val alive: Boolean get() = health > 0f
}

class FpsPickup(
    val id: Int,
    var x: Double,
    var y: Double,
    val kind: Int,
    val amount: Float = 0f,
    val weapon: Int = -1,
    val label: String = "",
    var active: Boolean = true,
    val respawn: Double = 0.0,
) {
    var timer: Double = 0.0
}

class FpsProjectile(
    var x: Double,
    var y: Double,
    val dx: Double,
    val dy: Double,
    val speed: Double,
    var damage: Float,
    val fromPlayer: Boolean,
    val kind: Int = FpsSprites.PROJECTILE,
) {
    var life: Double = 4.0
}

data class FpsSubtitle(val text: String, val speaker: String, var until: Double)

/**
 * The common half of both games.
 *
 * It owns the actors, the hitscan, the projectiles and the subtitle clock. The two games own what
 * happens when a wave ends or a level is cleared, because those rules are not shared at all.
 */
abstract class FpsArena(val map: FpsMap) {
    val players = ArrayList<FpsPlayer>()
    val enemies = ArrayList<FpsEnemy>()
    val projectiles = ArrayList<FpsProjectile>()
    val pickups = ArrayList<FpsPickup>()
    val events = ArrayList<FpsEvent>()
    val speech = ArrayList<FpsSpeech>()
    val subtitles = ArrayList<FpsSubtitle>()
    var time: Double = 0.0
    var message: String? = null
    var messageUntil: Double = 0.0
    var status: FpsStatus = FpsStatus.RUNNING
    var score: Int = 0
    /** The player this device controls. Remote co-op keeps its own id, which is not always zero. */
    var localId: Int = 0
    protected val random = Random(map.seed)
    private var nextEnemyId = 1
    private var nextPickupId = 1
    private var nextSubtitleId = 0

    val human: FpsPlayer get() = players.firstOrNull { it.id == localId } ?: players.first()

    fun addPlayer(id: Int, name: String, isBot: Boolean, x: Double = map.spawnX, y: Double = map.spawnY): FpsPlayer {
        val safeX = if (map.blocked(x, y)) map.spawnX else x
        val safeY = if (map.blocked(x, y)) map.spawnY else y
        val player = FpsPlayer(id, name, safeX, safeY, isBot)
        players.add(player)
        return player
    }

    fun addEnemy(
        kind: Int,
        x: Double,
        y: Double,
        health: Float,
        speed: Double,
        damage: Float,
        ranged: Boolean = false,
        attackInterval: Double = 0.9,
    ): FpsEnemy? {
        if (map.blocked(x, y, 0.3)) return null
        val enemy = FpsEnemy(nextEnemyId++, x, y, kind, health, speed, damage, ranged, attackInterval)
        enemies.add(enemy)
        return enemy
    }

    fun addPickup(x: Double, y: Double, kind: Int, amount: Float = 0f, weapon: Int = -1, label: String = "", respawn: Double = 0.0): FpsPickup {
        val pickup = FpsPickup(nextPickupId++, x, y, kind, amount, weapon, label, respawn = respawn)
        pickups.add(pickup)
        return pickup
    }

    /** A spawn well away from every living player, or null when the map has nowhere safe left. */
    fun spawnAwayFromPlayers(minimumDistance: Double = 7.0): Pair<Double, Double>? {
        val living = players.filter { it.health > 0f }
        if (living.isEmpty()) return null
        val cells = map.openCells()
        if (cells.isEmpty()) return null
        var best: Pair<Double, Double>? = null
        var bestScore = -1.0
        repeat(48) {
            val index = cells[random.nextInt(cells.size)]
            val x = map.cellX(index) + 0.5
            val y = map.cellY(index) + 0.5
            if (map.isExit(map.cellX(index), map.cellY(index))) return@repeat
            val nearest = living.minOf { hypot(it.x - x, it.y - y) }
            if (nearest < minimumDistance) return@repeat
            if (nearest > bestScore) {
                bestScore = nearest
                best = x to y
            }
        }
        return best ?: living.first().let { player ->
            val far = map.farthestOpen(player.x, player.y, minimumDistance)
            if (map.blocked(far.first, far.second)) null else far
        }
    }

    fun movePlayer(player: FpsPlayer, input: FpsInput, dt: Double, speedScale: Double = 1.0) {
        if (player.health <= 0f || player.downed) return
        val speed = (if (input.sprint) Fps.RUN_SPEED else Fps.WALK_SPEED) * speedScale
        val turn = input.turn.toDouble()
        player.angle = normalizeAngle(player.angle + turn)
        player.pitch = (player.pitch + input.look.toDouble()).coerceIn(-0.38, 0.38)
        val forward = input.forward.toDouble().coerceIn(-1.0, 1.0)
        val strafe = input.strafe.toDouble().coerceIn(-1.0, 1.0)
        var dx = (cos(player.angle) * forward - sin(player.angle) * strafe) * speed * dt
        var dy = (sin(player.angle) * forward + cos(player.angle) * strafe) * speed * dt
        val length = hypot(dx, dy)
        if (length > 1e-5) {
            // Normalise a diagonal: holding forward and strafe together must not be faster than
            // holding either one.
            val limit = speed * dt
            if (length > limit) {
                dx = dx / length * limit
                dy = dy / length * limit
            }
            val moved = Fps.move(map, player.x, player.y, dx, dy, Fps.PLAYER_RADIUS)
            player.x = moved.first
            player.y = moved.second
            player.step += length / 1.2
            if (player.step > 1.0) {
                player.step -= 1.0
                if (!player.isBot) {
                    events.add(FpsEvent(FpsSound.STEP, player.x, player.y, 0.35f, running = input.sprint))
                }
            }
        }
        player.cooldown = (player.cooldown - dt).coerceAtLeast(0.0)
        player.muzzle = (player.muzzle - (dt * 6.0).toFloat()).coerceAtLeast(0f)
        player.hurt = (player.hurt - (dt * 2.0).toFloat()).coerceAtLeast(0f)
        if (player.reloadTimer > 0.0) {
            player.reloadTimer -= dt
            if (player.reloadTimer <= 0.0) finishReload(player)
        }
    }

    fun triggerReload(player: FpsPlayer, weapons: List<FpsWeapon>) {
        if (player.health <= 0f || player.downed) return
        val weapon = weapons.getOrNull(player.weapon) ?: return
        if (player.reloadTimer > 0.0) return
        if (player.magazine >= weapon.magazine || player.reserve <= 0) return
        player.reloadTimer = weapon.reload
        events.add(FpsEvent(FpsSound.RELOAD, player.x, player.y, 0.7f))
    }

    protected open fun finishReload(player: FpsPlayer) {
        // The weapon table is not stored on the player, so the game supplies the magazine size.
    }

    fun switchWeapon(player: FpsPlayer, index: Int, weapons: List<FpsWeapon>) {
        if (index !in weapons.indices || index == player.weapon) return
        player.weapon = index
        player.reloadTimer = 0.0
        player.cooldown = 0.25
        player.magazine = weapons[index].magazine
        if (!player.isBot) events.add(FpsEvent(FpsSound.SWITCH, player.x, player.y, 0.5f))
    }

    /**
     * One shot from a player.
     *
     * Returns false when the trigger click did not become a bullet: empty magazine, still on
     * cooldown, or reloading. The caller can then play a dry click, but the common case is handled
     * here.
     */
    fun fire(player: FpsPlayer, weapons: List<FpsWeapon>, damageScale: Float = 1f): Boolean {
        if (player.health <= 0f || player.downed) return false
        val weapon = weapons.getOrNull(player.weapon) ?: return false
        if (player.reloadTimer > 0.0 || player.cooldown > 0.0) return false
        if (player.magazine <= 0) {
            if (!player.isBot) events.add(FpsEvent(FpsSound.EMPTY, player.x, player.y, 0.5f))
            return false
        }
        player.magazine--
        player.cooldown = weapon.cooldown
        player.muzzle = 1f
        events.add(FpsEvent(weapon.sound, player.x, player.y, 0.9f))
        for (i in 0 until weapon.pellets) {
            val spread = (random.nextDouble() * 2.0 - 1.0) * weapon.spread
            val angle = player.angle + spread
            val hit = nearestEnemyAlongRay(player.x, player.y, angle, 18.0)
            if (hit != null) {
                damageEnemy(hit, weapon.damage * damageScale, player)
            } else if (weapon.pellets == 1) {
                // A bullet that hits a wall still makes a noise there; it gives away the player's
                // position without inventing a target.
                val wall = Fps.cast(map, player.x, player.y, angle, 18.0)
                events.add(FpsEvent(FpsSound.HIT, wall.x, wall.y, 0.4f))
            }
        }
        return true
    }

    fun nearestEnemyAlongRay(x: Double, y: Double, angle: Double, maxDistance: Double): FpsEnemy? {
        val dx = cos(angle)
        val dy = sin(angle)
        val wall = Fps.cast(map, x, y, angle, maxDistance).distance
        var best: FpsEnemy? = null
        var bestDistance = wall
        for (enemy in enemies) {
            if (!enemy.alive) continue
            val distance = Fps.rayCircle(x, y, dx, dy, enemy.x, enemy.y, 0.42, bestDistance) ?: continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = enemy
            }
        }
        return best
    }

    fun damageEnemy(enemy: FpsEnemy, amount: Float, by: FpsPlayer?) {
        if (!enemy.alive) return
        enemy.health -= amount
        enemy.hurt = 1f
        if (by != null) {
            by.score += 10
            score += 10
        }
        events.add(FpsEvent(FpsSound.HIT, enemy.x, enemy.y, 0.7f))
        if (enemy.health <= 0f) {
            enemy.health = 0f
            enemy.dying = 0.6
            by?.kills = (by.kills + 1)
            if (by != null) {
                by.score += 50
                score += 50
            }
            events.add(FpsEvent(if (enemy.kind == FpsSprites.ZOMBIE) FpsSound.SCREAM else FpsSound.SCREAM, enemy.x, enemy.y, 0.9f))
            onEnemyKilled(enemy, by)
        }
    }

    protected open fun onEnemyKilled(enemy: FpsEnemy, by: FpsPlayer?) {}

    fun damagePlayer(player: FpsPlayer, amount: Float, source: String = "the dark") {
        if (player.health <= 0f || player.downed) return
        var remaining = amount
        if (player.armor > 0f) {
            val absorbed = min(player.armor, remaining * 0.55f)
            player.armor -= absorbed
            remaining -= absorbed
        }
        player.health = (player.health - remaining).coerceAtLeast(0f)
        player.hurt = 1f
        player.lastDamageAt = time
        if (!player.isBot) {
            events.add(FpsEvent(FpsSound.HURT, player.x, player.y, 0.8f, text = source))
        }
        if (player.health <= 0f) onPlayerDown(player, source)
    }

    protected open fun onPlayerDown(player: FpsPlayer, source: String) {
        player.downed = true
        player.bleedout = 30.0
        if (!player.isBot) message("YOU ARE DOWN — a teammate can revive you", 3.0)
    }

    fun updateProjectiles(dt: Double) {
        val iterator = projectiles.iterator()
        while (iterator.hasNext()) {
            val projectile = iterator.next()
            projectile.life -= dt
            if (projectile.life <= 0.0) {
                iterator.remove()
                continue
            }
            val steps = max(1, (projectile.speed * dt / 0.2).roundToInt())
            var gone = false
            repeat(steps) {
                if (gone) return@repeat
                projectile.x += projectile.dx * projectile.speed * dt / steps
                projectile.y += projectile.dy * projectile.speed * dt / steps
                if (map.blocked(projectile.x, projectile.y, 0.05)) {
                    events.add(FpsEvent(FpsSound.HIT, projectile.x, projectile.y, 0.4f))
                    gone = true
                    return@repeat
                }
                if (projectile.fromPlayer) {
                    val enemy = enemies.firstOrNull {
                        it.alive && hypot(it.x - projectile.x, it.y - projectile.y) < 0.42
                    }
                    if (enemy != null) {
                        damageEnemy(enemy, projectile.damage, human)
                        gone = true
                    }
                } else {
                    val player = players.firstOrNull {
                        it.health > 0f && !it.downed && hypot(it.x - projectile.x, it.y - projectile.y) < 0.38
                    }
                    if (player != null) {
                        damagePlayer(player, projectile.damage, "a fireball")
                        gone = true
                    }
                }
            }
            if (gone) iterator.remove()
        }
    }

    fun updatePickups(dt: Double) {
        for (pickup in pickups) {
            if (!pickup.active) {
                if (pickup.respawn > 0.0) {
                    pickup.timer -= dt
                    if (pickup.timer <= 0.0) pickup.active = true
                }
                continue
            }
            val nearest = players.firstOrNull {
                it.health > 0f && hypot(it.x - pickup.x, it.y - pickup.y) < 0.55
            } ?: continue
            if (collect(nearest, pickup)) {
                pickup.active = false
                pickup.timer = pickup.respawn
                events.add(FpsEvent(FpsSound.PICKUP, pickup.x, pickup.y, 0.7f))
            }
        }
    }

    protected open fun collect(player: FpsPlayer, pickup: FpsPickup): Boolean {
        when (pickup.kind) {
            FpsSprites.HEALTH -> {
                if (player.health >= player.maxHealth) return false
                player.health = min(player.maxHealth, player.health + pickup.amount)
            }
            FpsSprites.ARMOR -> {
                if (player.armor >= 100f) return false
                player.armor = min(100f, player.armor + pickup.amount)
            }
            FpsSprites.AMMO -> return onAmmoPickup(player, pickup)
            FpsSprites.WEAPON -> return onWeaponPickup(player, pickup)
            else -> return false
        }
        return true
    }

    protected open fun onAmmoPickup(player: FpsPlayer, pickup: FpsPickup): Boolean = false
    protected open fun onWeaponPickup(player: FpsPlayer, pickup: FpsPickup): Boolean = false

    fun updateSubtitles(dt: Double) {
        subtitles.removeAll { it.until <= time }
    }

    fun takeEvents(): List<FpsEvent> {
        if (events.isEmpty()) return emptyList()
        val copy = events.toList()
        events.clear()
        return copy
    }

    fun takeSpeech(): List<FpsSpeech> {
        if (speech.isEmpty()) return emptyList()
        val copy = speech.toList()
        speech.clear()
        return copy
    }

    fun subtitleLines(): List<String> = subtitles.takeLast(3).map { "${it.speaker}: ${it.text}" }

    protected fun say(text: String, speaker: String, seconds: Double = 4.0, voiceKey: Int = 0, speak: Boolean = true) {
        subtitles.add(FpsSubtitle(text, speaker, time + seconds))
        if (subtitles.size > 6) subtitles.removeAt(0)
        if (speak) speech.add(FpsSpeech(text, speaker, voiceKey))
    }

    protected fun message(text: String, seconds: Double = 2.5) {
        message = text
        messageUntil = time + seconds
    }

    protected fun tickMessage() {
        if (message != null && time > messageUntil) message = null
    }

    protected fun updateBots(dt: Double, weapons: List<FpsWeapon>, hostile: Boolean = true) {
        for (bot in players) {
            if (!bot.isBot || bot.health <= 0f || bot.downed) continue
            bot.cooldown = (bot.cooldown - dt).coerceAtLeast(0.0)
            bot.reloadTimer = (bot.reloadTimer - dt).coerceAtLeast(0.0)
            if (bot.reloadTimer == 0.0 && bot.magazine <= 0) finishReload(bot)
            bot.botThink -= dt
            if (bot.botThink > 0.0) continue
            bot.botThink = 0.22
            val target = if (hostile) enemies
                .filter { it.alive }
                .minByOrNull { hypot(it.x - bot.x, it.y - bot.y) } else null
            if (target != null && hypot(target.x - bot.x, target.y - bot.y) < 13.0 &&
                Fps.visible(map, bot.x, bot.y, target.x, target.y)
            ) {
                bot.angle = atan2(target.y - bot.y, target.x - bot.x)
                bot.pitch = 0.0
                if (bot.reloadTimer == 0.0 && bot.magazine <= 0) {
                    bot.reloadTimer = weapons.getOrNull(bot.weapon)?.reload ?: 1.5
                } else if (bot.cooldown <= 0.0) {
                    fire(bot, weapons)
                }
                // Bots drift toward the target when they are far away, so they are not turrets.
                if (hypot(target.x - bot.x, target.y - bot.y) > 3.0) {
                    val moved = Fps.move(map, bot.x, bot.y, cos(bot.angle) * 1.4 * dt, sin(bot.angle) * 1.4 * dt)
                    bot.x = moved.first
                    bot.y = moved.second
                }
            } else {
                val human = players.firstOrNull { !it.isBot && it.health > 0f && !it.downed } ?: continue
                val field = map.distanceField(floor(human.x).toInt(), floor(human.y).toInt())
                val step = map.stepDownhill(bot.x, bot.y, field)
                if (step != null && hypot(human.x - bot.x, human.y - bot.y) > 3.0) {
                    val tx = step.first + 0.5
                    val ty = step.second + 0.5
                    val angle = atan2(ty - bot.y, tx - bot.x)
                    bot.angle = angle
                    val moved = Fps.move(map, bot.x, bot.y, cos(angle) * 1.6 * dt, sin(angle) * 1.6 * dt)
                    bot.x = moved.first
                    bot.y = moved.second
                }
            }
        }
    }

    /** Enemy pathing: down a distance field toward the nearest living player, or straight at it. */
    protected fun chase(enemy: FpsEnemy, dt: Double, field: IntArray, target: FpsPlayer, speedScale: Double = 1.0) {
        val dx = target.x - enemy.x
        val dy = target.y - enemy.y
        val distance = hypot(dx, dy)
        enemy.angle = atan2(dy, dx)
        enemy.gait += dt * (1.7 + enemy.speed * 0.3)
        val stepDistance = enemy.speed * speedScale * dt
        if (Fps.visible(map, enemy.x, enemy.y, target.x, target.y)) {
            val moved = Fps.move(map, enemy.x, enemy.y, cos(enemy.angle) * stepDistance, sin(enemy.angle) * stepDistance, 0.3)
            enemy.x = moved.first
            enemy.y = moved.second
        } else {
            val step = map.stepDownhill(enemy.x, enemy.y, field)
            if (step != null) {
                val tx = step.first + 0.5
                val ty = step.second + 0.5
                val angle = atan2(ty - enemy.y, tx - enemy.x)
                enemy.angle = angle
                val moved = Fps.move(map, enemy.x, enemy.y, cos(angle) * stepDistance, sin(angle) * stepDistance, 0.3)
                enemy.x = moved.first
                enemy.y = moved.second
            }
        }
        enemy.attackTimer = (enemy.attackTimer - dt).coerceAtLeast(0.0)
        if (enemy.ranged) {
            if (distance < 9.0 && enemy.attackTimer <= 0.0 && Fps.visible(map, enemy.x, enemy.y, target.x, target.y)) {
                enemy.attackTimer = enemy.attackInterval
                projectiles.add(
                    FpsProjectile(
                        enemy.x, enemy.y, cos(enemy.angle), sin(enemy.angle),
                        speed = 5.4, damage = enemy.damage, fromPlayer = false,
                    )
                )
                events.add(FpsEvent(FpsSound.SCREAM, enemy.x, enemy.y, 0.55f))
            }
        } else if (distance < 1.15 && enemy.attackTimer <= 0.0) {
            enemy.attackTimer = enemy.attackInterval
            damagePlayer(target, enemy.damage, "a hunter")
        }
    }

    protected fun tickEnemyVisuals(dt: Double) {
        for (enemy in enemies) {
            enemy.hurt = (enemy.hurt - (dt * 3.0).toFloat()).coerceAtLeast(0f)
            if (enemy.dying > 0.0) {
                enemy.dying -= dt
                if (enemy.dying <= 0.0) enemy.state = 3
            }
        }
    }

    protected fun normalizeAngle(angle: Double): Double {
        var value = angle
        while (value > PI) value -= PI * 2
        while (value < -PI) value += PI * 2
        return value
    }

    /** Nearest living player, for enemies that do not care which human they reached. */
    protected fun nearestLivingPlayer(x: Double, y: Double): FpsPlayer? =
        players.filter { it.health > 0f && !it.downed }.minByOrNull { hypot(it.x - x, it.y - y) }

    protected fun teammateCount(): Int = players.count { !it.isBot } + players.count { it.isBot }
}
