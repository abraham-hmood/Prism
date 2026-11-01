package com.prism.launcher.minigames

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The hunter as both renderers see it.
 *
 * ## Why a billboard and separate tentacles
 *
 * [EchoAssets] draws the body -- mouths, eyes, wet folded flesh -- into a 1024 x 1024 sprite once.
 * The tentacles cannot be in that sprite: they have to reach and pull, and a baked pose would make
 * the creature slide around the maze like a sticker. This object projects the body to the screen and
 * generates tapered tentacle ribbons around it, so the phone and the PC animate the same limbs from
 * the same numbers.
 *
 * The sprite is square and the body radius is [BODY_FRACTION] of the full side. A caller that wants
 * a body of `r` screen pixels therefore draws the bitmap at `r / BODY_FRACTION`.
 */
object EchoMonster {

    const val SPRITE_SIZE = EchoAssets.RESOLUTION
    /** Body radius divided by the full sprite side. The body is painted 604 px across in 1024. */
    const val BODY_FRACTION = 0.295f
    const val BODY_WORLD_RADIUS = 0.42
    const val BODY_HEIGHT = 0.68
    const val TENTACLES = 11

    private const val SAMPLES = 8
    private const val TWO_PI = PI * 2

    data class Billboard(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val depth: Double,
    )

    /**
     * One tapered ribbon. The renderer fills the left/right outline and puts suckers along the
     * centre line; nothing here knows about Android canvases or Compose paths.
     */
    data class Tentacle(
        val leftX: FloatArray,
        val leftY: FloatArray,
        val rightX: FloatArray,
        val rightY: FloatArray,
        val centerX: FloatArray,
        val centerY: FloatArray,
        val width: FloatArray,
        val accent: Boolean,
    )

    /**
     * Projects the body. Returns null when the hunter is behind the camera, which is the same rule
     * [EchoAssets.project] uses for the models.
     */
    fun billboard(
        enemyX: Double,
        enemyY: Double,
        cameraX: Double,
        cameraY: Double,
        cameraAngle: Double,
        fov: Double,
        viewW: Int,
        viewH: Int,
        eyeHeight: Double = 0.56,
        scale: Double = 1.0,
    ): Billboard? {
        if (viewW <= 0 || viewH <= 0) return null
        val cosA = cos(cameraAngle)
        val sinA = sin(cameraAngle)
        val dx = enemyX - cameraX
        val dy = enemyY - cameraY
        val forward = dx * cosA + dy * sinA
        if (forward <= 0.05) return null
        val right = -dx * sinA + dy * cosA
        val focal = (viewW / 2.0) / tan(fov / 2)
        val centerX = (viewW / 2.0 + right / forward * focal).toFloat()
        val centerY = (viewH / 2.0 - (BODY_HEIGHT - eyeHeight) / forward * focal).toFloat()
        val radius = (BODY_WORLD_RADIUS * scale / forward * focal).toFloat()
        return Billboard(centerX, centerY, radius, forward)
    }

    /**
     * The limbs, around and behind [body].
     *
     * [gait] advances with distance actually walked, not with time, so a hunter pinned against a
     * wall does not run on the spot. The slow sway uses the clock: even a still body is breathing.
     */
    fun tentacles(
        body: Billboard,
        gait: Double,
        clock: Double,
        seed: Int,
        count: Int = TENTACLES,
    ): List<Tentacle> {
        val out = ArrayList<Tentacle>(count)
        val radius = max(body.radius, 1f)
        val seedPhase = (seed % 11) * 0.37
        for (index in 0 until count) {
            val angle = TWO_PI * index / count + seedPhase * 0.12
            val c = cos(angle)
            val s = sin(angle)
            val phase = index * 1.618 + seedPhase
            val sway = sin(clock * 1.9 + phase) * 0.64 +
                sin(clock * 4.1 + phase * 1.7) * 0.36
            val step = sin(gait * 1.02 + index * 2.3 + seedPhase)
            val base = radius * 0.88f
            val baseX = body.centerX + c.toFloat() * base
            val baseY = body.centerY + s.toFloat() * base
            val length = radius * (0.82f + (index % 4) * 0.075f + step.toFloat() * 0.07f)
            val droop = radius * (0.24f + step.toFloat() * 0.07f)
            // A tangent, a curl and the step cycle together turn a spoke into an octopus arm:
            // out from the body, sideways through the middle, then hooking back at the tip. The
            // tip comes BACK toward the body on the outward axis, which is what makes the curve
            // read as a curl rather than as a bent line.
            val curl = (sin(clock * 1.3 + index * 1.9 + gait * 0.55) * 0.62 + step * 0.38).toFloat()
            val firstTangential = sway.toFloat() * 0.05f + curl * 0.03f
            val middleTangential = sway.toFloat() * 0.24f + curl * 0.50f
            val tipTangential = sway.toFloat() * 0.42f + curl * 1.10f
            val p1x = baseX + c.toFloat() * length * 0.30f - s.toFloat() * firstTangential * radius
            val p1y = baseY + s.toFloat() * length * 0.30f + c.toFloat() * firstTangential * radius + droop * 0.08f
            val p2x = baseX + c.toFloat() * length * 0.66f - s.toFloat() * middleTangential * radius
            val p2y = baseY + s.toFloat() * length * 0.66f + c.toFloat() * middleTangential * radius + droop * 0.52f
            val p3x = baseX + c.toFloat() * length * 0.58f - s.toFloat() * tipTangential * radius
            val p3y = baseY + s.toFloat() * length * 0.58f + c.toFloat() * tipTangential * radius + droop

            val centerX = FloatArray(SAMPLES + 1)
            val centerY = FloatArray(SAMPLES + 1)
            for (j in 0..SAMPLES) {
                val t = j.toFloat() / SAMPLES
                val it = 1f - t
                val a = it * it * it
                val b = 3f * it * it * t
                val cc = 3f * it * t * t
                val d = t * t * t
                centerX[j] = a * baseX + b * p1x + cc * p2x + d * p3x
                centerY[j] = a * baseY + b * p1y + cc * p2y + d * p3y
            }

            val width = FloatArray(SAMPLES + 1)
            val leftX = FloatArray(SAMPLES + 1)
            val leftY = FloatArray(SAMPLES + 1)
            val rightX = FloatArray(SAMPLES + 1)
            val rightY = FloatArray(SAMPLES + 1)
            val baseWidth = radius * (0.150f + (index % 3) * 0.018f)
            for (j in 0..SAMPLES) {
                val t = j.toFloat() / SAMPLES
                // Thick for most of its length, then a quick taper at the very tip. A linear
                // taper made the limbs look like whips; a real arm carries its muscle outward.
                width[j] = max(1.4f, baseWidth * (1f - t).pow(0.72f)) + radius * 0.008f
                val previous = max(0, j - 1)
                val next = min(SAMPLES, j + 1)
                var tx = centerX[next] - centerX[previous]
                var ty = centerY[next] - centerY[previous]
                val lengthAt = hypot(tx.toDouble(), ty.toDouble()).toFloat().coerceAtLeast(0.001f)
                tx /= lengthAt
                ty /= lengthAt
                val nx = -ty
                val ny = tx
                leftX[j] = centerX[j] + nx * width[j] * 0.5f
                leftY[j] = centerY[j] + ny * width[j] * 0.5f
                rightX[j] = centerX[j] - nx * width[j] * 0.5f
                rightY[j] = centerY[j] - ny * width[j] * 0.5f
            }
            out.add(
                Tentacle(
                    leftX, leftY, rightX, rightY, centerX, centerY, width,
                    accent = index % 3 == 1,
                ),
            )
        }
        return out
    }

    /** Screen-space size of the whole square sprite for a given body radius. */
    fun spriteSide(radius: Float): Float = radius / BODY_FRACTION

    /** A finite check used by the tests: a broken tangent must not put NaN into a Path. */
    fun isFinite(tentacle: Tentacle): Boolean {
        for (array in listOf(
            tentacle.leftX, tentacle.leftY, tentacle.rightX, tentacle.rightY,
            tentacle.centerX, tentacle.centerY, tentacle.width,
        )) {
            array.forEach { if (!it.isFinite()) return false }
        }
        return true
    }

    /** The world-space distance at which a body of [radius] screen pixels is drawn. */
    fun depthFor(radius: Float, viewW: Int, fov: Double): Double {
        if (radius <= 0f || viewW <= 0) return Double.MAX_VALUE
        val focal = (viewW / 2.0) / tan(fov / 2)
        return BODY_WORLD_RADIUS * focal / radius
    }

    /** Kept for callers that want a cheap squared distance without another import. */
    fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double =
        sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
