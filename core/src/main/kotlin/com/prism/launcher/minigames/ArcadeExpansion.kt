package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The 3310 cabinet and five newer arcade games, kept out of Arcade.kt so the original file stays
 * readable.
 *
 * Every game here is a [BaseGame] and therefore shares the engine's reset/update/input contract:
 * a scene is rebuilt from state each frame, nothing is retained by the renderer, and a crash in a
 * rule is a normal test failure rather than a lost launcher.
 */

private fun spr(
    x: Float, y: Float, w: Float, h: Float, colour: Int,
    label: String? = null, kind: Int = 0, alpha: Float = 1f,
) = ArcadeSprite(x, y, w, h, colour, label, kind, alpha)

private fun clamp01(value: Float) = value.coerceIn(0f, 1f)

// ── Nokia 3310 ─────────────────────────────────────────────────────────────

/** Space Impact: move, shoot, survive the column. The 3310's original shooter. */
internal class SpaceImpactGame : BaseGame("spaceimpact") {
    private var playerX = 0.5f
    private val enemies = ArrayList<FloatArray>()
    private val shots = ArrayList<FloatArray>()
    private var spawn = 0.0f
    private var lives = 3

    override fun resetGame() {
        playerX = 0.5f
        enemies.clear(); shots.clear(); spawn = 0f; lives = 3
    }

    override fun tick(dt: Float) {
        spawn -= dt
        if (spawn <= 0f) {
            enemies.add(floatArrayOf(rng.nextFloat() * 0.86f + 0.07f, -0.05f, rng.nextInt(3).toFloat()))
            spawn = (0.85f - min(0.4f, score / 900f)).coerceAtLeast(0.35f)
        }
        for (enemy in enemies) enemy[1] += dt * (0.20f + enemy[2] * 0.06f)
        for (shot in shots) shot[1] -= dt * 1.4f
        shots.removeAll { it[1] < -0.05f }
        val dead = HashSet<FloatArray>()
        for (shot in shots) for (enemy in enemies) {
            if (abs(shot[0] - enemy[0]) < 0.055f && abs(shot[1] - enemy[1]) < 0.06f) {
                dead.add(enemy); score += 15
            }
        }
        enemies.removeAll(dead); shots.removeAll { shot -> dead.any { abs(it[0] - shot[0]) < 0.055f && abs(it[1] - shot[1]) < 0.06f } }
        val passed = enemies.filter { it[1] > 1.02f }
        if (passed.isNotEmpty()) { enemies.removeAll(passed); lives -= passed.size }
        if (lives <= 0) over = true
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action == ArcadeAction.LEFT || input.action == ArcadeAction.RIGHT) {
            playerX = clamp01(playerX + if (input.action == ArcadeAction.LEFT) -0.14f else 0.14f)
        }
        if (input.swipe == ArcadeAction.LEFT) playerX = clamp01(playerX - 0.14f)
        if (input.swipe == ArcadeAction.RIGHT) playerX = clamp01(playerX + 0.14f)
        if (input.tapX >= 0f) { playerX = clamp01(input.tapX); shots.add(floatArrayOf(playerX, 0.82f, 0f)) }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        for (enemy in enemies) sprites.add(spr(enemy[0] - 0.035f, enemy[1] - 0.025f, 0.07f, 0.05f, 6, "▼"))
        for (shot in shots) sprites.add(spr(shot[0] - 0.008f, shot[1], 0.016f, 0.04f, 2))
        sprites.add(spr(playerX - 0.045f, 0.86f, 0.09f, 0.05f, 0, "▲"))
        return ArcadeScene("SCORE $score   LIVES $lives", sprites, aspect = 0.62f,
            message = if (over) "The column got through." else null)
    }
}

/**
 * Bantumi: Mancala on two rows of six.
 *
 * The 3310's board is exactly this: six pits each side, one store each. Captures and the extra
 * turn on the store are implemented because without them it is not the game anyone remembers.
 */
internal class BantumiGame : BaseGame("bantumi") {
    private val pits = IntArray(12)
    private val stores = IntArray(2)
    private var turn = 0
    private var aiDelay = 0f

    override fun resetGame() {
        for (i in pits.indices) pits[i] = 4
        stores[0] = 0; stores[1] = 0; turn = 0; aiDelay = 0.8f
    }

    private fun sow(from: Int) {
        var seeds = pits[from]
        if (seeds <= 0) return
        pits[from] = 0
        var index = from
        while (seeds > 0) {
            index = (index + 1) % 12
            if (turn == 0 && index == 6) { stores[0]++; seeds--; continue }
            if (turn == 1 && index == 12) { stores[1]++; seeds--; continue }
            pits[index]++; seeds--
        }
        // Capture opposite seeds when the last seed lands in an empty pit on your side.
        if (index in 0..5 && pits[index] == 1 && pits[11 - index] > 0) {
            stores[0] += pits[11 - index] + 1
            pits[11 - index] = 0; pits[index] = 0
        }
        if (index in 6..11 && pits[index] == 1 && pits[11 - index] > 0) {
            stores[1] += pits[11 - index] + 1
            pits[11 - index] = 0; pits[index] = 0
        }
        val extra = if (turn == 0) index == 6 else index == 12
        if (!extra) turn = 1 - turn
        if (pits.take(6).all { it == 0 } || pits.drop(6).all { it == 0 }) {
            stores[0] += pits.take(6).sum(); stores[1] += pits.drop(6).sum()
            for (i in pits.indices) pits[i] = 0
            over = true; won = stores[0] > stores[1]
            return
        }
        if (turn == 1) aiDelay = 0.7f
    }

    override fun tick(dt: Float) {
        if (turn != 1) return
        aiDelay -= dt
        if (aiDelay > 0f) return
        val legal = (6..11).filter { pits[it] > 0 }
        if (legal.isEmpty()) { over = true; won = stores[0] > stores[1]; return }
        sow(legal[rng.nextInt(legal.size)])
    }

    override fun onInput(input: ArcadeInput) {
        if (turn != 0 || input.tapX < 0f || input.tapY !in 0.34f..0.63f) return
        val pit = (input.tapX * 6f).toInt().coerceIn(0, 5)
        sow(pit)
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        sprites.add(spr(0.0f, 0.0f, 1f, 0.30f, 5, "BANTUMI", alpha = 0.22f))
        for (side in 0..1) {
            for (i in 0..5) {
                val value = if (side == 0) pits[5 - i] else pits[6 + i]
                val x = 0.03f + i * 0.155f
                val y = if (side == 0) 0.38f else 0.65f
                sprites.add(spr(x, y, 0.135f, 0.22f, if (side == turn) 2 else 8, value.toString(), kind = 2))
            }
        }
        sprites.add(spr(0.02f, 0.70f, 0.20f, 0.24f, 3, "YOU ${stores[0]}"))
        sprites.add(spr(0.78f, 0.10f, 0.20f, 0.24f, 1, "CPU ${stores[1]}"))
        return ArcadeScene(
            "YOU ${stores[0]}   CPU ${stores[1]}", sprites, aspect = 0.72f,
            message = if (over) if (won) "You win the store." else "The CPU takes it." else null,
        )
    }
}

/**
 * Pairs II: the 3310's memory game. Sixteen cards, eight pairs, the fewest moves you can manage.
 */
internal class PairsGame : BaseGame("pairs") {
    private var cards = IntArray(16)
    private var open = mutableListOf<Int>()
    private var matched = HashSet<Int>()
    private var hideAt = 0f
    private var moves = 0

    override fun resetGame() {
        cards = IntArray(16) { it % 8 }.also { array -> array.shuffle(rng) }
        open = mutableListOf(); matched = HashSet(); hideAt = 0f; moves = 0
    }

    override fun tick(dt: Float) {
        if (open.size == 2) {
            hideAt -= dt
            if (hideAt <= 0f) {
                if (cards[open[0]] == cards[open[1]]) {
                    matched.add(open[0]); matched.add(open[1])
                    score += 10
                    if (matched.size == 16) { over = true; won = true }
                }
                open.clear()
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f || open.size == 2) return
        val col = (input.tapX * 4f).toInt().coerceIn(0, 3)
        val row = (input.tapY * 4f).toInt().coerceIn(0, 3)
        val index = row * 4 + col
        if (index in open || index in matched) return
        open.add(index); moves++
        if (open.size == 2) hideAt = 0.7f
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        for (row in 0..3) for (col in 0..3) {
            val index = row * 4 + col
            val shown = index in open || index in matched
            sprites.add(
                spr(0.04f + col * 0.235f, 0.04f + row * 0.235f, 0.21f, 0.21f,
                    if (index in matched) 3 else if (shown) 2 else 8,
                    if (shown) "★" else "?", kind = 2)
            )
        }
        return ArcadeScene("PAIRS ${matched.size / 2}/8   MOVES $moves", sprites, aspect = 1f,
            message = if (over) "All eight pairs in $moves moves." else null)
    }

    private fun IntArray.shuffle(random: Random) {
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = this[i]; this[i] = this[j]; this[j] = t
        }
    }
}

/**
 * Bumper: the 3310's arcade table. Keep the ball alive and hit the bumpers for points.
 */
internal class BumperGame : BaseGame("bumper") {
    private var px = 0.5f
    private var bx = 0.5f
    private var by = 0.55f
    private var vx = 0.25f
    private var vy = 0.42f
    private val bumpers = listOf(floatArrayOf(0.28f, 0.28f), floatArrayOf(0.72f, 0.24f), floatArrayOf(0.5f, 0.48f))
    private var lives = 3

    override fun resetGame() { px = .5f; bx = .5f; by = .55f; vx = .25f; vy = .42f; lives = 3 }

    override fun tick(dt: Float) {
        bx += vx * dt; by += vy * dt
        if (bx < 0.04f || bx > 0.96f) { vx = -vx; bx = bx.coerceIn(0.04f, 0.96f) }
        if (by < 0.03f) { vy = -vy; by = 0.03f }
        for (bumper in bumpers) {
            val dx = bx - bumper[0]; val dy = by - bumper[1]
            if (dx * dx + dy * dy < 0.012f) {
                val len = max(0.001f, kotlin.math.sqrt(dx * dx + dy * dy))
                vx = dx / len * 0.45f; vy = dy / len * 0.45f
                score += 25; by = bumper[1] + dy / len * 0.11f; break
            }
        }
        if (by > 0.90f && bx in (px - 0.13f)..(px + 0.13f)) {
            by = 0.88f; vy = -abs(vy); vx += (bx - px) * 0.5f; score += 5
        } else if (by > 1.02f) {
            lives--; bx = .5f; by = .55f; vx = .25f; vy = .42f
            if (lives <= 0) over = true
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.dragX >= 0f) px = clamp01(input.dragX)
        else if (input.tapX >= 0f) px = clamp01(input.tapX)
        if (input.action == ArcadeAction.LEFT) px = clamp01(px - .08f)
        if (input.action == ArcadeAction.RIGHT) px = clamp01(px + .08f)
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        for (bumper in bumpers) sprites.add(spr(bumper[0] - .07f, bumper[1] - .07f, .14f, .14f, 1, "●", kind = 1))
        sprites.add(spr(bx - .02f, by - .02f, .04f, .04f, 2, null, kind = 1))
        sprites.add(spr(px - .13f, .91f, .26f, .035f, 3))
        return ArcadeScene("SCORE $score   BALLS $lives", sprites, aspect = 0.68f,
            message = if (over) "Table closed." else null)
    }
}

// ── New cabinets ───────────────────────────────────────────────────────────

/** Stack: drop each moving slab on the one below. Overhang is cut away. */
internal class StackGame : BaseGame("stack") {
    private val widths = ArrayList<Float>()
    private val offsets = ArrayList<Float>()
    private var moving = 0.5f
    private var direction = 1f
    private var currentWidth = 0.62f

    override fun resetGame() {
        widths.clear(); offsets.clear(); widths.add(.62f); offsets.add(.19f)
        currentWidth = .62f; moving = .19f; direction = 1f
    }

    override fun tick(dt: Float) {
        moving += direction * dt * (0.55f + widths.size * 0.012f)
        if (moving < 0f) { moving = 0f; direction = 1f }
        if (moving + currentWidth > 1f) { moving = 1f - currentWidth; direction = -1f }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action != ArcadeAction.ACTION && input.tapX < 0f && input.action != ArcadeAction.DROP) return
        val belowOffset = offsets.last()
        val left = max(moving, belowOffset)
        val right = min(moving + currentWidth, belowOffset + widths.last())
        if (right - left <= 0.02f) { over = true; return }
        offsets.add(left); widths.add(right - left)
        currentWidth = right - left; moving = 0f; direction = 1f
        score += 20
        if (widths.size >= 24) { over = true; won = true }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        val baseY = 0.94f
        for (i in widths.indices) {
            sprites.add(spr(offsets[i], baseY - i * 0.075f, widths[i], 0.062f,
                if (i == widths.lastIndex) 1 else 5))
        }
        sprites.add(spr(moving, baseY - widths.size * 0.075f, currentWidth, 0.062f, 2))
        return ArcadeScene("HEIGHT ${widths.size}", sprites, aspect = 0.62f,
            message = if (over) if (won) "A tower to the top." else "The overhang was too much." else null)
    }
}

/** Lights Out: every tap flips a cross. Turn the grid off. */
internal class LightsOutGame : BaseGame("lightsout") {
    private val grid = BooleanArray(25)
    private var taps = 0

    override fun tick(dt: Float) = Unit

    override fun resetGame() {
        taps = 0
        // Start from the solved board and scramble by legal moves, so every board is winnable.
        for (i in grid.indices) grid[i] = false
        repeat(9) { toggle(rng.nextInt(25)) }
        if (grid.none { it }) toggle(12)
    }

    private fun toggle(index: Int) {
        val row = index / 5; val col = index % 5
        for ((dr, dc) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val r = row + dr; val c = col + dc
            if (r in 0..4 && c in 0..4) grid[r * 5 + c] = !grid[r * 5 + c]
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f) return
        val col = (input.tapX * 5f).toInt().coerceIn(0, 4)
        val row = (input.tapY * 5f).toInt().coerceIn(0, 4)
        toggle(row * 5 + col); taps++; score = max(0, 400 - taps * 6)
        if (grid.none { it }) { over = true; won = true; score += 300 }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        for (row in 0..4) for (col in 0..4) {
            val on = grid[row * 5 + col]
            sprites.add(spr(0.05f + col * 0.19f, 0.05f + row * 0.19f, 0.17f, 0.17f,
                if (on) 2 else 8, null, kind = 1))
        }
        return ArcadeScene("TAPS $taps", sprites, aspect = 1f,
            message = if (over) "Dark, in $taps taps." else "Turn every light off.")
    }
}

/** Reaction: wait for the colour, then tap. Early taps end the run. */
internal class ReactionGame : BaseGame("reaction") {
    private var waiting = 0f
    private var go = false
    private var startedAt = 0L
    private var rounds = 0
    private var bestMs = 0

    override fun resetGame() {
        waiting = 1.2f + rng.nextFloat() * 2f; go = false; rounds = 0; bestMs = 0
    }

    override fun tick(dt: Float) {
        if (go) return
        waiting -= dt
        if (waiting <= 0f) { go = true; startedAt = System.nanoTime() }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action != ArcadeAction.ACTION && input.tapX < 0f) return
        if (!go) { over = true; return }
        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
        bestMs = if (bestMs == 0) elapsed else min(bestMs, elapsed)
        score += max(10, 240 - elapsed / 4)
        rounds++
        if (rounds >= 5) { over = true; won = true } else {
            go = false; waiting = 0.9f + rng.nextFloat() * 1.8f
        }
    }

    override fun scene(): ArcadeScene = ArcadeScene(
        if (go) "NOW" else "WAIT",
        listOf(spr(0f, 0f, 1f, 1f, if (go) 3 else 1)),
        aspect = 1f,
        message = when {
            over && won -> "Five rounds, best $bestMs ms."
            over -> "Too early."
            go -> "Tap."
            else -> "Round ${rounds + 1}/5 · best ${if (bestMs == 0) "—" else "$bestMs ms"}"
        },
    )
}

/** Runner: one button, rising obstacles, distance as score. */
internal class RunnerGame : BaseGame("runner") {
    private var y = 0.82f
    private var vy = 0f
    private val obstacles = ArrayList<FloatArray>()

    override fun resetGame() { y = .82f; vy = 0f; obstacles.clear() }

    override fun tick(dt: Float) {
        vy -= 1.6f * dt; y += vy * dt
        if (y < 0.82f) { y = 0.82f; vy = 0f }
        for (obstacle in obstacles) obstacle[0] -= dt * (0.42f + min(0.3f, score / 2000f))
        obstacles.removeAll { it[0] < -0.12f }
        if (obstacles.isEmpty() || obstacles.last()[0] < 0.55f) {
            obstacles.add(floatArrayOf(1.1f, if (rng.nextBoolean()) 0.72f else 0.62f, 0.06f))
        }
        score += (dt * 20).toInt()
        for (obstacle in obstacles) {
            if (obstacle[0] < 0.20f && obstacle[0] + obstacle[2] > 0.10f && y > obstacle[1] - 0.14f) {
                over = true
            }
        }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.action == ArcadeAction.ACTION || input.action == ArcadeAction.UP || input.tapX >= 0f) {
            if (y >= 0.80f) vy = 0.85f
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        sprites.add(spr(0.08f, y - 0.07f, 0.08f, 0.14f, 2))
        for (obstacle in obstacles) sprites.add(spr(obstacle[0], obstacle[1], obstacle[2], 0.28f, 6))
        sprites.add(spr(0f, 0.87f, 1f, 0.03f, 8))
        return ArcadeScene("DISTANCE $score", sprites, aspect = 0.62f,
            message = if (over) "Stopped at $score." else null)
    }
}

/** Meteor: tap the falling rocks before they land. */
internal class MeteorGame : BaseGame("meteor") {
    private val rocks = ArrayList<FloatArray>()
    private var spawn = 0f
    private var shield = 3

    override fun resetGame() { rocks.clear(); spawn = 0.4f; shield = 3 }

    override fun tick(dt: Float) {
        spawn -= dt
        if (spawn <= 0f) {
            rocks.add(floatArrayOf(rng.nextFloat() * 0.9f + 0.05f, -0.05f, 0.10f + rng.nextFloat() * 0.08f))
            spawn = (0.8f - min(0.45f, score / 700f)).coerceAtLeast(0.3f)
        }
        for (rock in rocks) rock[1] += dt * (0.26f + rock[2] * 0.6f)
        val landed = rocks.filter { it[1] > 1.02f }
        if (landed.isNotEmpty()) { rocks.removeAll(landed); shield -= landed.size; if (shield <= 0) over = true }
    }

    override fun onInput(input: ArcadeInput) {
        if (input.tapX < 0f) return
        val hit = rocks.minByOrNull { abs(it[0] - input.tapX) + abs(it[1] - input.tapY) }
        if (hit != null && abs(hit[0] - input.tapX) < hit[2] && abs(hit[1] - input.tapY) < hit[2]) {
            rocks.remove(hit); score += 20
        }
    }

    override fun scene(): ArcadeScene {
        val sprites = ArrayList<ArcadeSprite>()
        for (rock in rocks) sprites.add(spr(rock[0] - rock[2] / 2, rock[1] - rock[2] / 2, rock[2], rock[2], 4, null, kind = 1))
        sprites.add(spr(0f, 0.94f, 1f, 0.03f, 0))
        return ArcadeScene("SCORE $score   SHIELD $shield", sprites, aspect = 0.72f,
            message = if (over) "The sky won." else "Tap the meteors.")
    }
}
