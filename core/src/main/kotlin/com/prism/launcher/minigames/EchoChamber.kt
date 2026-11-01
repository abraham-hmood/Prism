package com.prism.launcher.minigames

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Echo Chamber, as a maze and a set of things that can hear you.
 *
 * ## The idea, expressed as a rule
 *
 * Nothing is visible unless a sound has reached it. A player sees walls because their own shout
 * crossed the room and came back; they see an enemy because the same shout lit it up for a moment.
 * That means the visibility model is not a fog of war stored somewhere -- it falls out of the
 * ripples, which is also why an enemy hears the same ping that revealed it.
 *
 * ## Why the maze is a grid and the movement is not
 *
 * Walls are cells, so a maze can be generated, serialised and ray-cast deterministically. Players
 * and enemies move in continuous space because a game that snaps to tiles feels like a board game
 * rather than a dark corridor. Collision is a circle against the four cells it could be touching,
 * which is enough for a corridor a cell wide.
 *
 * ## Offline and on the mesh are one simulation
 *
 * A [EchoSession] always owns one world. With no [MinigameMesh.Match] it simulates enemies locally.
 * With a match, the accepter hosts the enemies and the other device receives their positions; both
 * own their own player and their own sound. That is the smallest split that still lets a peer's
 * shout pull an enemy toward them without either side sending a frame of the maze.
 */
object Echo {

    const val GRID = 33
    const val STEP_SECONDS = 1.0 / 60.0
    const val PING_SPEED = 9.0
    const val MARK_SECONDS = 2.4
    const val PLAYER_RADIUS = 0.24
    const val PLAYER_SPEED = 2.35
    const val PLAYER_HEIGHT = 0.56
    val FOV: Double = Math.toRadians(72.0)
    const val MAX_PING = 17.0
    const val MIN_PING = 5.5
    const val EXIT_REACHED = 0.62
    /** Most marks stored for one maze cell. Older ones lie under newer ones anyway. */
    private const val MARK_BUCKET_LIMIT = 320
    private const val MARK_TOTAL_LIMIT = 24_000
    private const val MARK_TOTAL_TRIM = 2_000
    /** How long the thing in the dark is in your face before the run is declared over. */
    const val JUMPSCARE_SECONDS = 2.4
    /**
     * How far an awake hunter can see down a clear corridor.
     *
     * Hearing finds the room; sight is what finishes the chase. Without this a hunter that had
     * heard one shout could stand in the same corridor as the player forever, because the sound
     * had stopped and it had nothing else to go on. It is short enough that the dark is still the
     * player's ally.
     */
    const val HUNT_VISION = 7.0

    enum class Status { RUNNING, WON, LOST }

    val DIFFICULTY_LABELS = listOf("SHADOW", "HUNTER", "NIGHTMARE")

    enum class EnemyState { PATROL, HUNT, STUNNED }

    /** One wall cell hit by one ray of one shout. */
    data class Hit(val delay: Double, val x: Double, val y: Double, val hue: Int)

    class Ripple(
        val id: Long,
        val x: Double,
        val y: Double,
        val maxRadius: Double,
        val hue: Int,
        val source: String,
        val speed: Double = PING_SPEED,
    ) {
        var age: Double = 0.0
            internal set
        val hits = ArrayList<Hit>(180)
        /** Which hits have already left a mark. A wave front reaches them at different times. */
        val emitted = BooleanArray(180)

        val radius: Double get() = (age * speed).coerceAtMost(maxRadius)

        fun computeHits(maze: EchoMaze) {
            if (hits.isNotEmpty()) return
            val rays = 180
            for (i in 0 until rays) {
                val angle = Math.PI * 2.0 * i / rays
                val hit = maze.cast(x, y, angle, maxRadius)
                if (hit == null) continue
                // Hue travels around the ring, so the returning wave is a rainbow rather than a
                // single coloured circle. The geometry still comes from the ray.
                val hue = ((hue + (i * 140 / rays)) % 360 + 360) % 360
                hits.add(Hit(hit.dist / speed, hit.x, hit.y, hue))
            }
        }
    }

    /**
     * A wavefront caught on a surface.
     *
     * It remembers where the shout came from, not just where it landed: the mark is drawn as an
     * arc of the circle around [originX]/[originY], which is the only way the picture can say
     * "this is the shape of the sound" rather than "something happened here".
     */
    class EchoMark(
        val x: Double,
        val y: Double,
        val hue: Int,
        val born: Double,
        val life: Double,
        val originX: Double,
        val originY: Double,
    )

    class Enemy(
        val id: Int,
        var x: Double,
        var y: Double,
        var state: EnemyState = EnemyState.PATROL,
        var targetX: Double = x,
        var targetY: Double = y,
        var alive: Boolean = true,
        var health: Int = 2,
        var attackCooldown: Double = 0.0,
        var stunUntil: Double = 0.0,
        var visibleUntil: Double = 0.0,
        /** Seconds spent pushing against a wall without moving. Reset as soon as it moves. */
        var stuckFor: Double = 0.0,
        /** The next cell centre on the hunter's path, refreshed a few times a second. */
        var waypointX: Double = x,
        var waypointY: Double = y,
        var pathAt: Double = -1.0,
        /**
         * Distance walked, in radians of tentacle cycle.
         *
         * The renderer uses this rather than the clock so a hunter pinned against a wall does not
         * run its legs while standing still, and so a hunter on a guest's screen animates from the
         * positions the host sends it.
         */
        var gait: Double = 0.0,
    )

    class Peer(
        var x: Double,
        var y: Double,
        var angle: Double,
        var health: Int = 100,
        var alive: Boolean = true,
        var visibleUntil: Double = 0.0,
        var lastSeenAt: Double = 0.0,
    )

    data class RayHit(
        val dist: Double,
        val cellX: Int,
        val cellY: Int,
        val side: Int,
        val u: Double,
        val x: Double,
        val y: Double,
    )

    /**
     * A generated maze, its spawn and its exit.
     *
     * Cells are walls; the carve pass makes an odd-coordinate spanning tree, then removes a small
     * number of extra walls to create loops. A perfect maze with no loops makes every chase a dead
     * end, which is the opposite of a game about hunting by sound.
     */
    class EchoMaze(val size: Int, private val walls: BooleanArray, seed: Long) {

        val spawnX: Double
        val spawnY: Double
        val exitX: Double
        val exitY: Double
        val enemySpawns: List<Pair<Double, Double>>

        init {
            val rng = Rng(seed)
            val open = ArrayList<Pair<Int, Int>>()
            for (y in 1 until size - 1) for (x in 1 until size - 1) {
                if (!wall(x, y)) open.add(x to y)
            }
            // Spawn at one odd cell, exit at the farthest reachable cell from it.
            val start = (1 to 1).takeIf { !wall(1, 1) } ?: open.first()
            val distance = bfs(start.first, start.second)
            val farthest = open.maxByOrNull { distance[it.second * size + it.first] } ?: start
            spawnX = start.first + 0.5
            spawnY = start.second + 0.5
            exitX = farthest.first + 0.5
            exitY = farthest.second + 0.5

            // Enemy spawns: far from the player, spread across the far half, never on the exit,
            // and never in a spot where the enemy's body would intersect a wall. The last check
            // is what stops a hunter appearing half-inside the corner of a corridor, where the
            // collision solver could never move it out again.
            val margin = PLAYER_RADIUS * 1.35
            val clear = open.filter { !blocked(it.first + 0.5, it.second + 0.5, margin) }
            val candidates = rng.shuffled(clear.filter {
                val d = hypot(it.first + 0.5 - spawnX, it.second + 0.5 - spawnY)
                d > size * 0.34 && (it.first to it.second) != farthest
            })
            val chosen = ArrayList<Pair<Double, Double>>()
            candidates.forEach { cell ->
                val px = cell.first + 0.5
                val py = cell.second + 0.5
                if (chosen.none { hypot(it.first - px, it.second - py) < 3.0 }) {
                    chosen.add(px to py)
                }
            }
            // A cramped maze can reject every far cell; fall back to any clear cell rather than
            // returning an empty list that World would fill with the exit cell.
            if (chosen.isEmpty()) {
                clear.take(4).forEach { chosen.add(it.first + 0.5 to it.second + 0.5) }
            }
            if (chosen.isEmpty()) chosen.add(spawnX to spawnY)
            enemySpawns = chosen
        }

        private fun bfs(sx: Int, sy: Int): IntArray {
            val dist = IntArray(size * size) { -1 }
            val queue = ArrayDeque<Int>()
            dist[sy * size + sx] = 0
            queue.add(sy * size + sx)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % size
                val y = index / size
                val next = dist[index] + 1
                for (d in DIRECTIONS) {
                    val nx = x + d.first
                    val ny = y + d.second
                    if (nx <= 0 || ny <= 0 || nx >= size - 1 || ny >= size - 1) continue
                    if (wall(nx, ny)) continue
                    val ni = ny * size + nx
                    if (dist[ni] == -1) {
                        dist[ni] = next
                        queue.add(ni)
                    }
                }
            }
            return dist
        }

        fun wall(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= size || y >= size) return true
            return walls[y * size + x]
        }

        fun wallAt(x: Double, y: Double): Boolean = wall(floor(x).toInt(), floor(y).toInt())

        fun blocked(x: Double, y: Double, radius: Double): Boolean {
            if (wallAt(x, y)) return true
            val left = floor(x - radius).toInt()
            val right = floor(x + radius).toInt()
            val top = floor(y - radius).toInt()
            val bottom = floor(y + radius).toInt()
            for (cy in top..bottom) for (cx in left..right) {
                if (!wall(cx, cy)) continue
                val nx = x.coerceIn(cx.toDouble(), cx + 1.0)
                val ny = y.coerceIn(cy.toDouble(), cy + 1.0)
                if (hypot(x - nx, y - ny) < radius) return true
            }
            return false
        }

        /**
         * DDA through the grid. Returns the first wall, with the exact point and the texture
         * coordinate along the face that was hit.
         */
        fun cast(ox: Double, oy: Double, angle: Double, maxDist: Double): RayHit? {
            val dx = cos(angle)
            val dy = sin(angle)
            var mapX = floor(ox).toInt()
            var mapY = floor(oy).toInt()
            val deltaX = if (abs(dx) < 1e-9) Double.POSITIVE_INFINITY else abs(1.0 / dx)
            val deltaY = if (abs(dy) < 1e-9) Double.POSITIVE_INFINITY else abs(1.0 / dy)
            val stepX = if (dx < 0) -1 else 1
            val stepY = if (dy < 0) -1 else 1
            var sideX = if (dx < 0) (ox - mapX) * deltaX else (mapX + 1.0 - ox) * deltaX
            var sideY = if (dy < 0) (oy - mapY) * deltaY else (mapY + 1.0 - oy) * deltaY
            var side = 0
            var travelled = 0.0
            while (travelled <= maxDist) {
                if (sideX < sideY) {
                    travelled = sideX
                    sideX += deltaX
                    mapX += stepX
                    side = 0
                } else {
                    travelled = sideY
                    sideY += deltaY
                    mapY += stepY
                    side = 1
                }
                if (wall(mapX, mapY)) {
                    if (travelled > maxDist) return null
                    val hx = ox + dx * travelled
                    val hy = oy + dy * travelled
                    val u = if (side == 0) hy - floor(hy) else hx - floor(hx)
                    return RayHit(travelled, mapX, mapY, side, u, hx, hy)
                }
            }
            return null
        }

        /** Every cell a ray crossed, for the "what has sound touched" map. */
        fun revealRay(ox: Double, oy: Double, angle: Double, maxDist: Double, reveal: (Int, Int) -> Unit) {
            val dx = cos(angle)
            val dy = sin(angle)
            var x = floor(ox).toInt()
            var y = floor(oy).toInt()
            val deltaX = if (abs(dx) < 1e-9) Double.POSITIVE_INFINITY else abs(1.0 / dx)
            val deltaY = if (abs(dy) < 1e-9) Double.POSITIVE_INFINITY else abs(1.0 / dy)
            val stepX = if (dx < 0) -1 else 1
            val stepY = if (dy < 0) -1 else 1
            var sideX = if (dx < 0) (ox - x) * deltaX else (x + 1.0 - ox) * deltaX
            var sideY = if (dy < 0) (oy - y) * deltaY else (y + 1.0 - oy) * deltaY
            var travelled = 0.0
            while (travelled <= maxDist) {
                reveal(x, y)
                if (sideX < sideY) {
                    travelled = sideX
                    sideX += deltaX
                    x += stepX
                } else {
                    travelled = sideY
                    sideY += deltaY
                    y += stepY
                }
                if (wall(x, y)) {
                    reveal(x, y)
                    return
                }
            }
        }

        fun lineOfSight(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
            val distance = hypot(bx - ax, by - ay)
            if (distance < 0.01) return true
            val hit = cast(ax, ay, atan2(by - ay, bx - ax), distance)
            return hit == null
        }

        /**
         * The first cell to walk into on a shortest open path from one cell to another.
         *
         * A hunter that only steers at its target walks into the first wall between them, which is
         * what made them look frozen: their target was the shout on the other side of a corridor
         * bend. Breadth-first over a 33x33 grid is trivially cheap, and a few of these a second is
         * nothing next to never moving at all.
         */
        fun nextStep(sx: Int, sy: Int, tx: Int, ty: Int): Pair<Int, Int>? {
            if (sx !in 0 until size || sy !in 0 until size) return null
            if (tx !in 0 until size || ty !in 0 until size || wall(tx, ty)) return null
            if (sx == tx && sy == ty) return null
            val start = sy * size + sx
            val goal = ty * size + tx
            val prev = IntArray(size * size) { -1 }
            val seen = BooleanArray(size * size)
            val queue = ArrayDeque<Int>()
            seen[start] = true
            queue.add(start)
            var found = -1
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                if (index == goal) {
                    found = index
                    break
                }
                val x = index % size
                val y = index / size
                if (x + 1 < size && !wall(x + 1, y)) {
                    val n = index + 1
                    if (!seen[n]) { seen[n] = true; prev[n] = index; queue.add(n) }
                }
                if (x - 1 >= 0 && !wall(x - 1, y)) {
                    val n = index - 1
                    if (!seen[n]) { seen[n] = true; prev[n] = index; queue.add(n) }
                }
                if (y + 1 < size && !wall(x, y + 1)) {
                    val n = index + size
                    if (!seen[n]) { seen[n] = true; prev[n] = index; queue.add(n) }
                }
                if (y - 1 >= 0 && !wall(x, y - 1)) {
                    val n = index - size
                    if (!seen[n]) { seen[n] = true; prev[n] = index; queue.add(n) }
                }
            }
            if (found < 0) return null
            var cursor = found
            while (prev[cursor] != -1 && prev[cursor] != start) cursor = prev[cursor]
            return (cursor % size) to (cursor / size)
        }

        companion object {
            private val DIRECTIONS = listOf(0 to -2, 2 to 0, 0 to 2, -2 to 0)

            fun generate(seed: Long, size: Int = GRID): EchoMaze {
                val rng = Rng(Rng.seedOf("echo-maze", seed, size))
                val walls = BooleanArray(size * size) { true }
                val stack = ArrayDeque<Pair<Int, Int>>()
                val start = 1 to 1
                walls[start.second * size + start.first] = false
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val (x, y) = stack.last()
                    val options = DIRECTIONS.map { (dx, dy) -> x + dx to y + dy }
                        .filter { (nx, ny) ->
                            nx in 1 until size - 1 && ny in 1 until size - 1 &&
                                walls[ny * size + nx]
                        }
                    if (options.isEmpty()) {
                        stack.removeLast()
                        continue
                    }
                    val (nx, ny) = options[rng.nextInt(options.size)]
                    walls[((y + ny) / 2) * size + ((x + nx) / 2)] = false
                    walls[ny * size + nx] = false
                    stack.add(nx to ny)
                }

                // Loops. A maze with none turns every chase into a corner; a handful of openings
                // makes the sound game about misdirection rather than about dead ends.
                val extra = (size * size / 90).coerceAtLeast(4)
                repeat(extra) {
                    val x = rng.nextInt(2, size - 2)
                    val y = rng.nextInt(2, size - 2)
                    val horizontal = walls[y * size + x] &&
                        !walls[y * size + x - 1] && !walls[y * size + x + 1]
                    val vertical = walls[y * size + x] &&
                        !walls[(y - 1) * size + x] && !walls[(y + 1) * size + x]
                    if (horizontal || vertical) walls[y * size + x] = false
                }
                return EchoMaze(size, walls, seed)
            }
        }
    }

    data class Input(
        val forward: Double = 0.0,
        val strafe: Double = 0.0,
        val turn: Double = 0.0,
        val shout: Boolean = false,
        val strike: Boolean = false,
    )

    /**
     * One running game.
     *
     * [simulateEnemies] is false for the guest in a mesh match: the host owns the hunters and the
     * guest receives their positions. That is what keeps two phones seeing the same enemy without
     * sending its animation.
     */
    class World(
        val seed: Long,
        val difficulty: Int,
        enemyCount: Int,
        val playerIndex: Int,
    ) {
        val maze = EchoMaze.generate(seed)
        val revealed = BooleanArray(maze.size * maze.size)
        val ripples = ArrayList<Ripple>()
        val marks = ArrayList<EchoMark>()

        /**
         * Marks bucketed by maze cell.
         *
         * The marks are permanent now, so after a long round there can be thousands of them. The
         * renderer must never walk that whole list every frame. A cell's bucket is what lets it
         * ask "what is near me" and touch only the handful of cells it could actually see. Each
         * bucket is capped; a cell that has been painted thousands of times only needs its most
         * recent marks, because the older ones are exactly underneath the newer ones.
         */
        private val markBuckets = Array(maze.size * maze.size) { ArrayList<EchoMark>(4) }
        val enemies = ArrayList<Enemy>()
        var playerX = maze.spawnX
        var playerY = maze.spawnY
        var playerAngle = 0.0
        var playerHealth = 100
        var status = Status.RUNNING
        var score = 0
        var kills = 0
        var tags = 0
        var clock = 0.0
        var shoutCooldown = 0.0
        var strikeCooldown = 0.0
        var damageFlash = 0.0
        var lastPing: Ripple? = null
        var pingSerial = 0L
        var lastStrikeHit = false
        var exitVisibleUntil = 0.0

        /** Counts down while a hunter is in the player's face. Nothing else moves until it ends. */
        var jumpscare = 0.0
            private set
        var jumpscareEnemy: Int = -1
            private set

        /** True from the killing blow until the jumpscare has finished playing. */
        var dying = false
            private set

        var simulateEnemies = true
        var peer: Peer? = null
        var peerDistance: Double = 0.0

        /**
         * Whether the guest has actually left the exit.
         *
         * A mesh guest starts on the exit -- it is the one landmark guaranteed to be far from the
         * host -- so without this they would win on the first frame. The exit only counts once
         * they have walked away from it and come back.
         */
        private var leftGuestStart = true

        init {
            if (playerIndex == 1) {
                playerX = maze.exitX
                playerY = maze.exitY
                // The guest starts where the host's exit is, so both starts are far apart without
                // needing a second maze. They have to leave it before it can count as reached.
                leftGuestStart = false
            }
            val spawns = maze.enemySpawns.ifEmpty {
                listOf(maze.exitX to maze.exitY)
            }
            repeat(enemyCount.coerceIn(0, 12)) { index ->
                // Spawns are validated against the walls by the maze itself, so the hunter begins
                // with its whole body inside a corridor. Do not nudge it: a diagonal offset can
                // push it into a wall, which is how hunters used to start embedded in the maze.
                val (x, y) = spawns[index % spawns.size]
                enemies.add(Enemy(id = index, x = x, y = y, targetX = x, targetY = y))
            }
            revealAround(playerX, playerY, 1)
        }

        fun revealAround(x: Double, y: Double, radius: Int) {
            val cx = floor(x).toInt()
            val cy = floor(y).toInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until maze.size && ny in 0 until maze.size) {
                    revealed[ny * maze.size + nx] = true
                }
            }
        }

        fun isRevealed(x: Int, y: Int): Boolean =
            x in 0 until maze.size && y in 0 until maze.size && revealed[y * maze.size + x]

        /** Files a mark in the cell that contains it. Public so a test can plant a trail directly. */
        fun addMark(mark: EchoMark) {
            // Permanent does not mean unbounded. In an hour of play a spammer could paint
            // hundreds of thousands of marks, so the oldest few thousand are forgotten at a time;
            // recent walls, which are the ones the player can still see, are untouched.
            if (marks.size >= MARK_TOTAL_LIMIT) {
                val drop = marks.subList(0, MARK_TOTAL_TRIM)
                drop.forEach { old ->
                    val ox = floor(old.x).toInt()
                    val oy = floor(old.y).toInt()
                    if (ox in 0 until maze.size && oy in 0 until maze.size) {
                        markBuckets[oy * maze.size + ox].remove(old)
                    }
                }
                drop.clear()
            }
            marks.add(mark)
            val cx = floor(mark.x).toInt()
            val cy = floor(mark.y).toInt()
            if (cx !in 0 until maze.size || cy !in 0 until maze.size) return
            val bucket = markBuckets[cy * maze.size + cx]
            if (bucket.size >= MARK_BUCKET_LIMIT) bucket.removeAt(0)
            bucket.add(mark)
        }

        /**
         * Visits the marks in the cells around [px]/[py], nearest rings first.
         *
         * [action] returns false to stop early. [maxChecks] bounds the work even in a corridor
         * that has been shouted at for minutes. Drawing only needs the marks it can see, and this
         * is how it finds them without scanning the permanent map every frame.
         */
        fun forEachNearbyMark(
            px: Double,
            py: Double,
            cells: Int,
            maxChecks: Int,
            action: (EchoMark) -> Boolean,
        ) {
            val cx = floor(px).toInt()
            val cy = floor(py).toInt()
            var checked = 0
            val radiusLimit = cells.coerceIn(0, maze.size)
            for (ring in 0..radiusLimit) {
                for (dy in -ring..ring) {
                    for (dx in -ring..ring) {
                        if (max(abs(dx), abs(dy)) != ring) continue
                        val gx = cx + dx
                        val gy = cy + dy
                        if (gx !in 0 until maze.size || gy !in 0 until maze.size) continue
                        val bucket = markBuckets[gy * maze.size + gx]
                        for (mark in bucket) {
                            if (!action(mark)) return
                            checked++
                            if (checked >= maxChecks) return
                        }
                    }
                }
            }
        }

        /** Emits a shout. [amplitude] is 0..1; a whisper is short, a real shout crosses rooms. */
        fun shout(amplitude: Double, source: String = "you"): Ripple? {
            if (status != Status.RUNNING || shoutCooldown > 0.0) return null
            val amount = amplitude.coerceIn(0.25, 1.0)
            val radius = MIN_PING + (MAX_PING - MIN_PING) * amount
            val hue = if (source == "you") 188 else 318
            val ripple = Ripple(++pingSerial, playerX, playerY, radius, hue, source)
            ripple.computeHits(maze)
            ripples.add(ripple)
            lastPing = ripple
            shoutCooldown = 0.72 - amount * 0.30
            // A sound this loud reveals what it touched. The rays are what fill the map, not a
            // global reveal around the player.
            for (i in 0 until 72) {
                val a = Math.PI * 2.0 * i / 72
                maze.revealRay(playerX, playerY, a, radius) { x, y ->
                    if (x in 0 until maze.size && y in 0 until maze.size) {
                        revealed[y * maze.size + x] = true
                    }
                }
            }
            if (simulateEnemies) alertEnemies(ripple)
            strikeCooldown = min(strikeCooldown, 0.12)
            return ripple
        }

        fun peerShout(x: Double, y: Double, radius: Double, hue: Int, source: String) {
            val ripple = Ripple(++pingSerial, x, y, radius, hue, source)
            ripple.computeHits(maze)
            ripples.add(ripple)
            if (simulateEnemies) alertEnemies(ripple)
        }

        private fun alertEnemies(ripple: Ripple) {
            enemies.forEach { enemy ->
                if (!enemy.alive) return@forEach
                val distance = hypot(enemy.x - ripple.x, enemy.y - ripple.y)
                if (distance <= ripple.maxRadius + 4.0) {
                    // A little error, so an enemy converges on the room rather than on the exact
                    // pixel the player is standing on. Otherwise the first shout is a death
                    // sentence and the game has no second act.
                    val jitter = 0.55
                    enemy.targetX = ripple.x + (Rng.seedOf(ripple.id, enemy.id).toDouble() % 1.0 - 0.5) * jitter
                    enemy.targetY = ripple.y + (Rng.seedOf(enemy.id, ripple.id).toDouble() % 1.0 - 0.5) * jitter
                    enemy.state = EnemyState.HUNT
                    // A hunter that heard you glows for a moment: the player needs to see that the
                    // shout gave them away, or a death from the dark is just confusing.
                    enemy.visibleUntil = max(enemy.visibleUntil, clock + 1.2)
                }
            }
        }

        fun update(dt: Double, input: Input) {
            if (status != Status.RUNNING) return
            val step = dt.coerceIn(0.0, 0.08)
            clock += step

            // The jumpscare is its own little scene: the player cannot move, the hunters do not
            // act, and the marks on the walls stay exactly where they are. When it is over, the
            // run is lost and the results screen can appear.
            if (dying) {
                jumpscare = max(0.0, jumpscare - step)
                if (jumpscare <= 0.0) {
                    dying = false
                    status = Status.LOST
                }
                return
            }
            shoutCooldown = max(0.0, shoutCooldown - step)
            strikeCooldown = max(0.0, strikeCooldown - step)
            damageFlash = max(0.0, damageFlash - step * 1.8)

            // Turning first: a player looking around while walking is the normal case, and it
            // makes the joystick feel attached to the camera rather than to the world.
            playerAngle += input.turn * 2.4 * step
            if (playerAngle > Math.PI) playerAngle -= Math.PI * 2
            if (playerAngle < -Math.PI) playerAngle += Math.PI * 2

            val forward = input.forward
            val strafe = input.strafe
            val length = hypot(forward, strafe)
            if (length > 0.02) {
                val scale = 1.0 / max(1.0, length)
                val dx = (cos(playerAngle) * forward - sin(playerAngle) * strafe) * scale * PLAYER_SPEED * step
                val dy = (sin(playerAngle) * forward + cos(playerAngle) * strafe) * scale * PLAYER_SPEED * step
                movePlayer(dx, dy)
            }

            if (input.shout && shoutCooldown <= 0.0) shout(0.82)
            if (input.strike) strike()

            updateRipples(step)
            if (simulateEnemies) updateEnemies(step)
            updateVisibility()

            val exitDistance = hypot(playerX - maze.exitX, playerY - maze.exitY)
            if (!leftGuestStart && exitDistance > EXIT_REACHED * 2.0) leftGuestStart = true
            if (exitDistance <= EXIT_REACHED && leftGuestStart) {
                status = Status.WON
                score += 250 + kills * 100
            }
            playerHealth = playerHealth.coerceIn(0, 100)
        }

        private fun movePlayer(dx: Double, dy: Double) {
            if (!maze.blocked(playerX + dx, playerY, PLAYER_RADIUS)) playerX += dx
            if (!maze.blocked(playerX, playerY + dy, PLAYER_RADIUS)) playerY += dy
        }

        private fun updateRipples(step: Double) {
            var index = 0
            while (index < ripples.size) {
                val ripple = ripples[index]
                ripple.age += step
                // Every hit becomes a mark when the expanding wave front reaches it, which is
                // what makes the reveal travel outward instead of appearing all at once.
                for (h in ripple.hits.indices) {
                    if (ripple.emitted[h]) continue
                    val hit = ripple.hits[h]
                    if (ripple.age >= hit.delay) {
                        ripple.emitted[h] = true
                        addMark(EchoMark(hit.x, hit.y, hit.hue, clock, MARK_SECONDS, ripple.x, ripple.y))
                    }
                }
                // A ripple's visual life is its max radius plus enough time for the returning
                // marks to fade. It stays in the list for hit accounting until then.
                val life = ripple.maxRadius / ripple.speed + MARK_SECONDS
                if (ripple.age > life) {
                    ripples.removeAt(index)
                    continue
                }
                index++
            }
            // Marks are permanent: the walls keep every trace of every wave.
        }

        private fun updateVisibility() {
            val now = clock
            // An enemy is visible if a wave has reached it and the line between the shout and the
            // enemy is clear. This is deliberately computed from the live enemy position too: a
            // hunter walking out of the revealed pocket disappears again.
            enemies.forEach { enemy ->
                if (!enemy.alive) return@forEach
                val seen = ripples.any { ripple ->
                    val d = hypot(enemy.x - ripple.x, enemy.y - ripple.y)
                    d <= ripple.radius && ripple.age >= d / ripple.speed &&
                        maze.lineOfSight(ripple.x, ripple.y, enemy.x, enemy.y)
                }
                if (seen) enemy.visibleUntil = now + 1.8
            }
            peer?.let { remote ->
                val seen = ripples.any { ripple ->
                    val d = hypot(remote.x - ripple.x, remote.y - ripple.y)
                    d <= ripple.radius && ripple.age >= d / ripple.speed &&
                        maze.lineOfSight(ripple.x, ripple.y, remote.x, remote.y)
                }
                if (seen) remote.visibleUntil = now + 1.8
            }
            val exitSeen = ripples.any { ripple ->
                val d = hypot(maze.exitX - ripple.x, maze.exitY - ripple.y)
                d <= ripple.radius && ripple.age >= d / ripple.speed &&
                    maze.lineOfSight(ripple.x, ripple.y, maze.exitX, maze.exitY)
            }
            if (exitSeen) exitVisibleUntil = now + 2.4
        }

        private fun updateEnemies(step: Double) {
            enemies.forEach { enemy ->
                if (!enemy.alive) return@forEach
                enemy.attackCooldown = max(0.0, enemy.attackCooldown - step)
                if (enemy.state == EnemyState.STUNNED && clock >= enemy.stunUntil) {
                    enemy.state = EnemyState.HUNT
                }
                if (enemy.state == EnemyState.STUNNED) return@forEach

                val target = nearestTarget(enemy)
                if (target != null) {
                    enemy.targetX = target.first
                    enemy.targetY = target.second
                    enemy.state = EnemyState.HUNT
                }
                val distanceToTarget = hypot(enemy.targetX - enemy.x, enemy.targetY - enemy.y)
                if (distanceToTarget < 0.3) {
                    if (enemy.state == EnemyState.HUNT) {
                        // Reached the sound; cast around for a moment before going back to patrol.
                        enemy.state = EnemyState.PATROL
                        val rng = Rng(Rng.seedOf(seed, enemy.id, (clock * 4).toLong()))
                        val angle = rng.nextDouble() * Math.PI * 2
                        enemy.targetX = (enemy.x + cos(angle) * 6).coerceIn(1.0, maze.size - 2.0)
                        enemy.targetY = (enemy.y + sin(angle) * 6).coerceIn(1.0, maze.size - 2.0)
                    }
                }
                // Patrols read the walls. A hunter that comes across a fresh sound mark follows
                // the breadcrumb trail the player's own shouts left behind -- it does not need to
                // have heard that particular shout to know somebody came this way.
                if (enemy.state == EnemyState.PATROL) {
                    followNearestMark(enemy)
                }
                updatePath(enemy)
                val speed = if (enemy.state == EnemyState.HUNT) 1.62 + difficulty * 0.18 else 0.72
                stepEnemy(enemy, speed, step)

                val playerDistance = hypot(playerX - enemy.x, playerY - enemy.y)
                if (playerDistance < 0.62 && enemy.attackCooldown <= 0.0) {
                    playerHealth -= 14 + difficulty * 3
                    damageFlash = 1.0
                    enemy.attackCooldown = 1.1
                    enemy.state = EnemyState.HUNT
                    if (playerHealth <= 0) beginDeath(enemy.id)
                }
                peer?.let { remote ->
                    val remoteDistance = hypot(remote.x - enemy.x, remote.y - enemy.y)
                    if (remoteDistance < 0.62 && enemy.attackCooldown <= 0.0) {
                        enemy.attackCooldown = 1.1
                        enemy.targetX = remote.x
                        enemy.targetY = remote.y
                    }
                }
            }
        }

        /**
         * Walks a patrol to the freshest mark within sight of it.
         *
         * The marks are permanent, so a hunter that has never heard a single shout can still find
         * the corridor the player has been using and follow it. Freshness decides which mark is
         * most interesting; it does not decide whether the mark exists. Only the buckets around the
         * hunter are searched, so this costs the same after ten minutes as after ten seconds.
         */
        private fun followNearestMark(enemy: Enemy) {
            var best: EchoMark? = null
            var bestAge = Double.MAX_VALUE
            forEachNearbyMark(enemy.x, enemy.y, 5, 500) { mark ->
                if (hypot(mark.x - enemy.x, mark.y - enemy.y) > 7.5) return@forEachNearbyMark true
                val age = clock - mark.born
                if (age >= 0 && age < bestAge) {
                    bestAge = age
                    best = mark
                }
                true
            }
            val mark = best ?: return
            var targetX = mark.x
            var targetY = mark.y
            var bestDistance = Double.MAX_VALUE
            listOf(0.55 to 0.0, -0.55 to 0.0, 0.0 to 0.55, 0.0 to -0.55).forEach { offset ->
                val x = mark.x + offset.first
                val y = mark.y + offset.second
                if (maze.blocked(x, y, PLAYER_RADIUS * 0.8)) return@forEach
                val d = hypot(x - enemy.x, y - enemy.y)
                if (d < bestDistance) {
                    bestDistance = d
                    targetX = x
                    targetY = y
                }
            }
            if (bestDistance == Double.MAX_VALUE) return
            enemy.targetX = targetX
            enemy.targetY = targetY
            enemy.state = EnemyState.HUNT
        }

        private fun beginDeath(enemyId: Int) {
            if (dying || status != Status.RUNNING) return
            playerHealth = 0
            dying = true
            jumpscare = JUMPSCARE_SECONDS
            jumpscareEnemy = enemyId
            damageFlash = 2.0
            ripples.clear()
        }

        private fun nearestTarget(enemy: Enemy): Pair<Double, Double>? {
            val playerDistance = hypot(playerX - enemy.x, playerY - enemy.y)
            val remote = peer
            val remoteDistance = if (remote != null) hypot(remote.x - enemy.x, remote.y - enemy.y) else Double.MAX_VALUE
            // Hearing is proximity plus a shout's alert. A hunter that is already awake will
            // follow a player it can all but touch without needing another sound.
            val hunting = enemy.state == EnemyState.HUNT
            // Scent/trail following finds the room; a hunter that is awake and has a clear line
            // down a corridor also sees the player. This is what turns a stale trail into an
            // actual chase instead of two figures circling the same mark forever.
            val seesPlayer = hunting && playerDistance < HUNT_VISION &&
                maze.lineOfSight(enemy.x, enemy.y, playerX, playerY)
            val seesRemote = hunting && remote != null && remoteDistance < HUNT_VISION &&
                maze.lineOfSight(enemy.x, enemy.y, remote.x, remote.y)
            return when {
                seesPlayer && playerDistance <= remoteDistance -> playerX to playerY
                seesRemote -> remote!!.x to remote.y
                hunting && playerDistance < 9.0 && playerDistance <= remoteDistance -> playerX to playerY
                hunting && remote != null && remoteDistance < 9.0 -> remote.x to remote.y
                hunting -> null
                playerDistance < 1.6 -> playerX to playerY
                else -> null
            }
        }

        /** Refreshes the hunter's next waypoint along the open corridors. */
        private fun updatePath(enemy: Enemy) {
            if (clock - enemy.pathAt < 0.22) return
            enemy.pathAt = clock
            val sx = floor(enemy.x).toInt()
            val sy = floor(enemy.y).toInt()
            val tx = floor(enemy.targetX).toInt()
            val ty = floor(enemy.targetY).toInt()
            val next = maze.nextStep(sx, sy, tx, ty)
            if (next != null) {
                enemy.waypointX = next.first + 0.5
                enemy.waypointY = next.second + 0.5
            } else {
                enemy.waypointX = enemy.targetX
                enemy.waypointY = enemy.targetY
            }
        }

        private fun stepEnemy(enemy: Enemy, speed: Double, step: Double) {
            var dx = enemy.waypointX - enemy.x
            var dy = enemy.waypointY - enemy.y
            val length = hypot(dx, dy)
            if (length < 0.001) return
            dx = dx / length * speed * step
            dy = dy / length * speed * step
            val beforeX = enemy.x
            val beforeY = enemy.y
            if (!maze.blocked(enemy.x + dx, enemy.y, PLAYER_RADIUS * 0.85)) enemy.x += dx
            if (!maze.blocked(enemy.x, enemy.y + dy, PLAYER_RADIUS * 0.85)) enemy.y += dy

            // Wall following: a straight line into a corner should slide along it, not stop the
            // hunter for the rest of the round.
            var moved = hypot(enemy.x - beforeX, enemy.y - beforeY)
            if (moved < 0.001) {
                val side = Math.PI / 2 * (if ((clock * 3).toInt() and 1 == 0) 1 else -1)
                val sx = cos(side) * dy - sin(side) * dx
                val sy = sin(side) * dx + cos(side) * dy
                if (!maze.blocked(enemy.x + sx, enemy.y, PLAYER_RADIUS * 0.85)) enemy.x += sx
                if (!maze.blocked(enemy.x, enemy.y + sy, PLAYER_RADIUS * 0.85)) enemy.y += sy
                moved = hypot(enemy.x - beforeX, enemy.y - beforeY)
            }
            if (moved < 0.0005) {
                enemy.stuckFor += step
                // A target across a wall is not reachable by pressing into the wall. After a
                // moment, forget it and pick a neighbouring cell that is genuinely open.
                if (enemy.stuckFor > 0.28) retargetAdjacent(enemy)
            } else {
                enemy.stuckFor = 0.0
                enemy.gait = (enemy.gait + moved * 6.8) % (Math.PI * 200)
            }
        }

        /** Sends a stuck hunter to the centre of an adjacent floor cell. */
        private fun retargetAdjacent(enemy: Enemy) {
            val cx = floor(enemy.x).toInt()
            val cy = floor(enemy.y).toInt()
            val options = ArrayList<Pair<Int, Int>>(4)
            if (!maze.wall(cx + 1, cy)) options.add(cx + 1 to cy)
            if (!maze.wall(cx - 1, cy)) options.add(cx - 1 to cy)
            if (!maze.wall(cx, cy + 1)) options.add(cx to cy + 1)
            if (!maze.wall(cx, cy - 1)) options.add(cx to cy - 1)
            enemy.stuckFor = 0.0
            if (options.isEmpty()) {
                enemy.targetX = enemy.x
                enemy.targetY = enemy.y
                enemy.waypointX = enemy.x
                enemy.waypointY = enemy.y
                return
            }
            val pick = options[Rng(Rng.seedOf(seed, enemy.id, (clock * 8).toLong())).nextInt(options.size)]
            enemy.targetX = pick.first + 0.5
            enemy.targetY = pick.second + 0.5
            enemy.waypointX = enemy.targetX
            enemy.waypointY = enemy.targetY
            enemy.pathAt = clock
            enemy.state = EnemyState.HUNT
        }

        /** Tag or kill whatever is in front, close enough. */
        fun strike() {
            if (status != Status.RUNNING || strikeCooldown > 0.0) return
            strikeCooldown = 0.62
            val facingX = cos(playerAngle)
            val facingY = sin(playerAngle)
            val target = enemies.filter { it.alive }.minByOrNull { enemy ->
                hypot(enemy.x - playerX, enemy.y - playerY)
            }
            lastStrikeHit = false
            if (target != null) {
                val dx = target.x - playerX
                val dy = target.y - playerY
                val distance = hypot(dx, dy)
                val dot = if (distance > 0.001) (dx / distance) * facingX + (dy / distance) * facingY else 1.0
                if (distance <= 1.85 && dot > -0.1) {
                    lastStrikeHit = true
                    target.health -= 1
                    tags++
                    target.state = EnemyState.STUNNED
                    target.stunUntil = clock + 1.5
                    target.targetX = target.x
                    target.targetY = target.y
                    if (target.health <= 0) {
                        target.alive = false
                        kills++
                        score += 100
                    } else {
                        score += 25
                    }
                }
            }
            // The strike is a sound too: a scuffle makes noise, though a small one.
            val ripple = Ripple(++pingSerial, playerX, playerY, 4.0, 24, "strike")
            ripple.computeHits(maze)
            ripples.add(ripple)
            if (simulateEnemies) alertEnemies(ripple)
        }

        fun markKill(id: Int) {
            val enemy = enemies.firstOrNull { it.id == id } ?: return
            if (!enemy.alive) return
            enemy.health = 0
            enemy.alive = false
            kills++
            score += 100
        }

        fun syncEnemy(id: Int, x: Double, y: Double, alive: Boolean, health: Int) {
            val enemy = enemies.firstOrNull { it.id == id } ?: return
            val walked = hypot(x - enemy.x, y - enemy.y)
            if (walked > 0.0005) enemy.gait = (enemy.gait + walked * 6.8) % (Math.PI * 200)
            enemy.x = x
            enemy.y = y
            if (enemy.alive && !alive) kills++
            enemy.alive = alive
            enemy.health = health
        }

        fun applyRemoteDamage(amount: Int) {
            playerHealth -= amount
            damageFlash = 1.0
        }
    }

    data class StartOptions(
        val difficulty: Int = 1,
        val enemies: Int = 3,
    )

    /**
     * Binds a [World] to an optional mesh match and owns the small wire format.
     *
     * The format is terse because it rides UDP a few times a second, and the first character is the
     * only thing that needs parsing to ignore a message meant for another game.
     */
    class Session(
        val match: MinigameMesh.Match?,
        val options: StartOptions,
    ) {
        private val isHost = match == null || match.youAreFirst
        private val world = World(
            seed = match?.seed ?: System.currentTimeMillis(),
            difficulty = options.difficulty,
            enemyCount = options.enemies,
            playerIndex = if (match?.youAreFirst == false) 1 else 0,
        ).apply { simulateEnemies = isHost }

        private var sendAccum = 0.0
        private var enemyAccum = 0.0
        private var lastSentRipple = 0L
        private var closed = false

        val state: World get() = world

        init {
            if (match != null) {
                world.peer = Peer(world.maze.exitX, world.maze.exitY, 0.0)
                MinigameMesh.listenForMoves { peerIp, payload ->
                    if (peerIp == match.peerIp) apply(payload)
                }
            }
        }

        fun update(dt: Double, input: Input) {
            if (closed) return
            world.update(dt, input)
            val match = match ?: return

            // A ripple the local player just made is sent exactly once, not every frame while it
            // expands.
            val ripple = world.lastPing
            if (ripple != null && ripple.id != lastSentRipple) {
                lastSentRipple = ripple.id
                send(match, "q|${fmt(world.playerX)}|${fmt(world.playerY)}|${fmt(ripple.maxRadius)}|${ripple.hue}")
            }

            sendAccum += dt
            if (sendAccum >= 0.055) {
                sendAccum = 0.0
                send(
                    match,
                    "p|${fmt(world.playerX)}|${fmt(world.playerY)}|${fmt(world.playerAngle)}|" +
                        "${world.playerHealth}|${if (world.status == Status.LOST) 0 else 1}",
                )
            }

            if (isHost) {
                enemyAccum += dt
                if (enemyAccum >= 0.09) {
                    enemyAccum = 0.0
                    world.enemies.forEach { enemy ->
                        send(
                            match,
                            "e|${enemy.id}|${fmt(enemy.x)}|${fmt(enemy.y)}|${if (enemy.alive) 1 else 0}|${enemy.health}",
                        )
                    }
                }
            }
        }

        private fun apply(payload: String) {
            val parts = payload.split("|")
            when (parts.firstOrNull()) {
                "p" -> {
                    val peer = world.peer ?: return
                    peer.x = parts.getOrNull(1)?.toDoubleOrNull() ?: return
                    peer.y = parts.getOrNull(2)?.toDoubleOrNull() ?: return
                    peer.angle = parts.getOrNull(3)?.toDoubleOrNull() ?: peer.angle
                    peer.health = parts.getOrNull(4)?.toIntOrNull() ?: peer.health
                    peer.alive = (parts.getOrNull(5)?.toIntOrNull() ?: 1) != 0
                    peer.lastSeenAt = world.clock
                    world.peerDistance = hypot(peer.x - world.playerX, peer.y - world.playerY)
                }
                "q" -> {
                    val x = parts.getOrNull(1)?.toDoubleOrNull() ?: return
                    val y = parts.getOrNull(2)?.toDoubleOrNull() ?: return
                    val radius = parts.getOrNull(3)?.toDoubleOrNull() ?: return
                    world.peerShout(x, y, radius, parts.getOrNull(4)?.toIntOrNull() ?: 318, "peer")
                }
                "e" -> if (!isHost) {
                    val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                    world.syncEnemy(
                        id,
                        parts.getOrNull(2)?.toDoubleOrNull() ?: return,
                        parts.getOrNull(3)?.toDoubleOrNull() ?: return,
                        (parts.getOrNull(4)?.toIntOrNull() ?: 1) != 0,
                        parts.getOrNull(5)?.toIntOrNull() ?: 1,
                    )
                }
                "k" -> if (isHost) {
                    parts.getOrNull(1)?.toIntOrNull()?.let { world.markKill(it) }
                }
                "d" -> world.applyRemoteDamage(parts.getOrNull(1)?.toIntOrNull() ?: 0)
                "w" -> if (world.status == Status.RUNNING) world.status = Status.WON
            }
        }

        /** A local strike that found something must be repeated to the host. */
        fun reportStrike() {
            val match = match ?: return
            if (isHost) return
            // The nearest enemy is the one the local world would have hit; the host verifies
            // distance before marking it dead.
            val target = world.enemies.filter { it.alive }.minByOrNull {
                hypot(it.x - world.playerX, it.y - world.playerY)
            } ?: return
            if (hypot(target.x - world.playerX, target.y - world.playerY) <= 1.85) {
                send(match, "k|${target.id}")
            }
        }

        fun close() {
            closed = true
            if (match != null) MinigameMesh.stopListening()
        }

        private fun send(match: MinigameMesh.Match, payload: String) {
            val sent = runCatching { MinigameMesh.sendMove(match.peerIp, payload) }
            if (sent.isFailure) PrismPlatform.log.warn(TAG, "Could not send an Echo Chamber packet")
        }

        private companion object {
            const val TAG = "PrismEcho"

            fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)
        }
    }
}
